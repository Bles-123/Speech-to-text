package com.procucev.speech.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.procucev.speech.domain.Transcription;
import com.procucev.speech.service.TranscriptionService;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Protocol (browser -> server):
 *   text   {"type":"start","language":"auto|hi|en","title":"optional"}
 *   binary 16 kHz, 16-bit, mono little-endian PCM frames
 *   text   {"type":"stop"}
 * Server -> browser:
 *   {"type":"partial","text":"..."}          one per transcribed speech segment
 *   {"type":"final","text":"...","id":123}   full text (id present when saved to DB)
 *   {"type":"error","message":"..."}
 *
 * Audio is cut into speech segments (pause detection + max length) and each segment is sent
 * to Whisper; segments are processed strictly in order per connection.
 */
@Component
public class TranscribeWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscribeWebSocketHandler.class);
    private static final int SAMPLE_RATE = 16_000;
    private static final DateTimeFormatter TITLE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    
    private final WhisperClient whisper;
    private final TranscriptionService service;
    private final ObjectMapper mapper;
    private final int minChunkMs;
    private final int maxChunkMs;
    private final int silenceMs;
    private final double silenceRms;

    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public TranscribeWebSocketHandler(WhisperClient whisper, TranscriptionService service, ObjectMapper mapper,
                                      @Value("${stt.min-chunk-ms:2500}") int minChunkMs,
                                      @Value("${stt.max-chunk-ms:10000}") int maxChunkMs,
                                      @Value("${stt.silence-ms:500}") int silenceMs,
                                      @Value("${stt.silence-rms:0.01}") double silenceRms) {
        this.whisper = whisper;
        this.service = service;
        this.mapper = mapper;
        this.minChunkMs = minChunkMs;
        this.maxChunkMs = maxChunkMs;
        this.silenceMs = silenceMs;
        this.silenceRms = silenceRms;
    }

    /** Per-connection state. */
    private static class Conn {
        final WebSocketSession ws;
        String language = "auto";
        String title = "";
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final StringBuilder full = new StringBuilder();
        long totalBytes;
        int silentMs;
        boolean speechSeen;
        boolean finished;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        Conn(WebSocketSession ws) { this.ws = ws; }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        conns.put(session.getId(),
                new Conn(new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Conn c = conns.get(session.getId());
        if (c == null) return;
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            switch (node.path("type").asText()) {
                case "start" -> {
                    c.language = node.path("language").asText("auto");
                    c.title = node.path("title").asText("");
                }
                case "stop" -> {
                    flush(c);
                    finish(c);
                }
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Bad control message: {}", e.getMessage());
            send(c, Map.of("type", "error", "message", "Invalid control message"));
        }
    }
    
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Conn c = conns.get(session.getId());
        if (c == null || c.finished) return;

        ByteBuffer bb = message.getPayload();
        byte[] frame = new byte[bb.remaining()];
        bb.get(frame);

        int frameMs = frame.length / 2 * 1000 / SAMPLE_RATE;
        if (AudioUtil.rms(frame) >= silenceRms) {
            c.speechSeen = true;
            c.silentMs = 0;
        } else {
            c.silentMs += frameMs;
        }
        // Before any speech: keep only a short pre-roll so the first syllable is not clipped.
        if (!c.speechSeen && c.buf.size() > frame.length * 2) {
            c.buf.reset();
        }
        c.buf.write(frame, 0, frame.length);
        c.totalBytes += frame.length;

        int bufMs = c.buf.size() / 2 * 1000 / SAMPLE_RATE;
        if (c.speechSeen && (bufMs >= maxChunkMs || (bufMs >= minChunkMs && c.silentMs >= silenceMs))) {
            flush(c);
        }
    }

    /** Queue the buffered speech segment for transcription (ordered). */
    private void flush(Conn c) {
        if (c.speechSeen && c.buf.size() > 0) {
            byte[] wav = AudioUtil.pcmToWav(c.buf.toByteArray(), SAMPLE_RATE);
            String lang = c.language;
            c.chain = c.chain.thenRunAsync(() -> {
                try {
                    String text = whisper.transcribe(wav, lang);
                    if (text != null && !text.isBlank()) {
                        if (c.full.length() > 0) c.full.append(' ');
                        c.full.append(text);
                        send(c, Map.of("type", "partial", "text", text));
                    }
                } catch (Exception e) {
                    log.warn("Whisper call failed: {}", e.getMessage());
                    send(c, Map.of("type", "error",
                            "message", "Transcription service unavailable: " + e.getMessage()));
                }
            }, executor);
        }
        c.buf.reset();
        c.silentMs = 0;
        c.speechSeen = false;
    }

    /** After all queued segments finish: persist the full transcript and close. */
    private void finish(Conn c) {
        if (c.finished) return;
        c.finished = true;
        final int durationSeconds = (int) (c.totalBytes / 2 / SAMPLE_RATE);
        c.chain = c.chain.thenRunAsync(() -> {
            try {
                String text = c.full.toString().trim();
                Map<String, Object> out = new HashMap<>();
                out.put("type", "final");
                out.put("text", text);
                if (!text.isBlank()) {
                    String title = c.title == null || c.title.isBlank()
                            ? "Recording " + TITLE_FMT.format(Instant.now()) : c.title;
                    Transcription t = service.create(title, text, c.language, durationSeconds);
                    out.put("id", t.getId());
                }
                send(c, out);
            } catch (Exception e) {
                log.error("Saving transcription failed", e);
                send(c, Map.of("type", "error", "message", "Could not save transcription"));
            } finally {
                try { c.ws.close(CloseStatus.NORMAL); } catch (Exception ignored) { }
            }
        }, executor);
    }

    private void send(Conn c, Map<String, ?> payload) {
        try {
            c.ws.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("send failed: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Conn c = conns.remove(session.getId());
        if (c != null && !c.finished) { // client dropped mid-recording: keep what was said
            flush(c);
            finish(c);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}

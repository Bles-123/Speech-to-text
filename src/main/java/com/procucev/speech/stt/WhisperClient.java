package com.procucev.speech.stt;

import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Talks to a self-hosted open-source Whisper server (openai-whisper-asr-webservice, POST /asr). */
@Component
public class WhisperClient {

    private final RestClient rest;
    private final String baseUrl;
    private final String initialPrompt;

    public WhisperClient(@Value("${whisper.url}") String baseUrl,
                         @Value("${whisper.initial-prompt:}") String initialPrompt) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(60_000);
        this.rest = RestClient.builder().requestFactory(f).build();
        this.baseUrl = baseUrl;
        this.initialPrompt = initialPrompt;
    }

    /** @param language ISO code ("hi", "en") or "auto" for Whisper's language detection */
    public String transcribe(byte[] wav, String language) {
        UriComponentsBuilder ub = UriComponentsBuilder.fromUriString(baseUrl).path("/asr")
                .queryParam("task", "transcribe")
                .queryParam("output", "json")
                .queryParam("encode", "true");
        if (language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language)) {
            ub.queryParam("language", language);
        }
        if (!initialPrompt.isBlank()) {
            ub.queryParam("initial_prompt", initialPrompt);
        }
        URI uri = ub.build().encode().toUri();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("audio_file", new ByteArrayResource(wav) {
            @Override public String getFilename() { return "chunk.wav"; }
        });

        Map<?, ?> res = rest.post().uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);
        Object text = res == null ? null : res.get("text");
        return text == null ? "" : text.toString().trim();
    }
}

# Bolo: real-time multilingual Speech-to-Text (Spring Boot)

Speak Hindi, English or a mix. The browser streams microphone audio to Spring Boot over a WebSocket;
the server splits it into speech segments and transcribes each with a **self-hosted open-source Whisper**
(faster-whisper). Every recording is saved to PostgreSQL with plain SQL (Spring JDBC; table in `schema.sql`), and the dashboard offers full CRUD on the history.

```
Browser (AudioWorklet, 16 kHz PCM) --WebSocket--> Spring Boot --HTTP--> Whisper (Docker, port 9000)
                                                      |
                                                  PostgreSQL  <-- REST CRUD <-- Dashboard (static HTML/JS)
```

## Run

Requirements: JDK 17+, Maven, Docker.

```bash
docker compose up -d            # PostgreSQL + Whisper (first start downloads the model)
mvn spring-boot:run             # http://localhost:8080
```

No Docker for Postgres? `mvn spring-boot:run -Dspring-boot.run.profiles=h2` uses an in-memory H2 DB
(Whisper is still required for transcription).

Open http://localhost:8080 (microphone needs `localhost` or HTTPS).

## REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/transcriptions?q=&page=&size=` | List and search, newest first |
| GET | `/api/transcriptions/{id}` | Read one |
| POST | `/api/transcriptions` | Create `{title,text,language}` |
| PUT | `/api/transcriptions/{id}` | Update |
| DELETE | `/api/transcriptions/{id}` | Delete |
| WS | `/ws/transcribe` | Live transcription (protocol in `TranscribeWebSocketHandler`) |

## How real-time works

1. `pcm-worklet.js` captures mic samples; `app.js` downsamples to 16 kHz Int16 and sends binary frames.
2. The server tracks energy per frame. A segment is cut after a pause (`stt.silence-ms`) once it is at least
   `stt.min-chunk-ms` long, or at `stt.max-chunk-ms`. Silent buffers are never sent (avoids Whisper hallucinations).
3. Each segment is sent to Whisper in order; text is pushed back as `partial` messages.
4. On Stop, the full transcript is saved and the id returned.

## Hindi + English mixing

- Language **Auto** lets Whisper detect per segment, and `whisper.initial-prompt` (Hinglish sample text) biases
  output to keep English words in Latin script and Hindi in Devanagari.
- Pick `small` or larger (`ASR_MODEL` in `docker-compose.yml`) for acceptable Hindi accuracy; `large-v3` is best.
- GPU: use the `-gpu` image tag with the NVIDIA runtime for much lower latency.

## Known limitations / next steps

- Whisper is not natively streaming; latency is "one pause + inference time", not word by word.
  For true streaming, swap `WhisperClient` for a Gemini Live / WhisperLive client (only that class changes).
- Whisper detects one language per segment, so very fast code-switching inside a single sentence can be imperfect.
- No authentication, tests or per-user history yet (add Spring Security + a `user_id` column).

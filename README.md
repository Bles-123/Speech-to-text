# Bolo: Real-time Multilingual Speech-to-Text

Speak in Hindi, English, or a mix of both and watch the transcript appear as you talk.
Every recording is saved to MySQL and managed from a dashboard with full CRUD.

**Stack:** Spring Boot 3 (Java 17) · WebSocket · Spring JDBC (plain SQL) · MySQL · self-hosted Whisper (faster-whisper) · vanilla HTML/JS

---

## How it works

```
 Browser                      Spring Boot                       Whisper server
┌──────────────┐  16 kHz PCM  ┌────────────────────────┐  WAV   ┌────────────────┐
│ Mic →        │ ───────────► │ /ws/transcribe         │ ─────► │ POST /asr      │
│ AudioWorklet │   WebSocket  │  • pause detection     │ ◄───── │ faster-whisper │
│ Live text ◄──│ ◄─────────── │  • ordered segments    │  text  └────────────────┘
└──────────────┘   partials   │ /api/transcriptions    │
        ▲                     │  • CRUD (Spring JDBC)  │ ───►  MySQL
        └──── dashboard ◄──── └────────────────────────┘
```

1. The browser captures the microphone, downsamples to 16 kHz mono PCM and streams it over a WebSocket.
2. The server cuts the audio into speech segments (after a pause, or at a maximum length) and skips silent audio.
3. Each segment goes to Whisper, and the text is pushed back to the page as it is ready.
4. When you press **Stop and save**, the full transcript is stored in MySQL.

## Features

- Live transcription of mixed Hindi + English speech (auto-detect, or force Hindi / English)
- Transcription history with **create, read, update, delete**, search and pagination
- Open-source speech model, running on your own machine
- Transcripts survive a dropped connection (saved when the socket closes)

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | or run from IntelliJ / Eclipse |
| MySQL | 8+ | local install **or** the Docker service |
| Python | 3.9–3.13 | runs the Whisper server |
| Browser | Chrome / Edge | microphone needs `localhost` or HTTPS |

---

## Quick start

### 1. Database

Use your local MySQL, or start the one in `docker-compose.yml`:

```bash
docker compose up -d mysql
```

The database `speechdb` and the `transcriptions` table are created automatically
(`createDatabaseIfNotExist=true` + `schema.sql`).

### 2. Configure

Edit `src/main/resources/application.properties` (or set environment variables):

```properties
spring.datasource.url=${DB_URL:jdbc:mysql://localhost:3306/speechdb?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8}
spring.datasource.username=${DB_USER:root}
spring.datasource.password=${DB_PASSWORD:root}
```

> **Port 3306 already used by a local MySQL?** Map Docker to `3307:3306` and use port `3307` in the URL.

### 3. Start the Whisper server

```bash
cd whisper-server
python -m pip install fastapi uvicorn python-multipart faster-whisper numpy
python server.py
```

The first run downloads the model (a few minutes). Wait for `Uvicorn running on http://0.0.0.0:9000`,
then check <http://localhost:9000/docs>. Keep this window open.

*Prefer Docker?* `docker compose up -d whisper` starts an equivalent server on the same port.

### 4. Start the app

```bash
mvn spring-boot:run
```

Open **<http://localhost:8080>**, click **Start recording**, allow the microphone, and speak.
Pause briefly between sentences: text appears after each pause.

---

## REST API

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/transcriptions?q=&page=&size=` | List and search, newest first |
| `GET` | `/api/transcriptions/{id}` | Get one |
| `POST` | `/api/transcriptions` | Create `{ "title", "text", "language" }` |
| `PUT` | `/api/transcriptions/{id}` | Update |
| `DELETE` | `/api/transcriptions/{id}` | Delete |
| `WS` | `/ws/transcribe` | Live transcription |

**WebSocket messages**

| Direction | Message |
|---|---|
| browser → server | `{"type":"start","language":"auto\|hi\|en","title":"..."}` |
| browser → server | binary frames: 16 kHz, 16-bit, mono PCM |
| browser → server | `{"type":"stop"}` |
| server → browser | `{"type":"partial","text":"..."}` per segment |
| server → browser | `{"type":"final","text":"...","id":123}` after saving |
| server → browser | `{"type":"error","message":"..."}` |

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `whisper.url` | `http://localhost:9000` | Whisper server address |
| `whisper.initial-prompt` | Hinglish sample | Nudges Whisper to keep English words in Latin script and Hindi in Devanagari |
| `stt.min-chunk-ms` | `2500` | Shortest segment before a pause may cut it |
| `stt.max-chunk-ms` | `10000` | Longest segment before a forced cut |
| `stt.silence-ms` | `500` | Pause length that ends a segment |
| `stt.silence-rms` | `0.01` | Loudness below this counts as silence (lower it for quiet mics) |

> Keep non-ASCII text in `.properties` as Unicode escapes (`\u092e\u0948\u0902`) so editor encoding settings cannot corrupt it.

---

## Project structure

```
speech-to-text/
├── docker-compose.yml          MySQL (+ optional Whisper container)
├── pom.xml
├── whisper-server/server.py    Python Whisper server (no Docker needed)
└── src/main/
    ├── java/com/procucev/speech/
    │   ├── SpeechToTextApplication.java
    │   ├── config/WebSocketConfig.java
    │   ├── stt/                 TranscribeWebSocketHandler, WhisperClient, AudioUtil
    │   ├── web/                 REST controller + DTOs
    │   ├── service/             TranscriptionService
    │   ├── repo/                TranscriptionRepository (plain SQL, JdbcTemplate)
    │   └── domain/Transcription.java
    └── resources/
        ├── application.properties
        ├── schema.sql           table definition
        └── static/              index.html, app.js, style.css, pcm-worklet.js
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Access denied for user 'root'` | Wrong MySQL password in `application.properties` / `DB_PASSWORD` |
| `Connection refused … :9000` | Whisper server is not running or still downloading the model. Check <http://localhost:9000/docs> |
| `500 … metadata_errors` | Outdated `av` package. Use the provided `server.py`, which reads WAV directly |
| `Could not import module "server"` | Run `python server.py` from inside the `whisper-server` folder |
| `pip` launcher error on Windows | Use `python -m pip install …` instead of `pip` |
| Nothing is transcribed | Speak louder, or lower `stt.silence-rms` to `0.005` |
| Hindi shows as `à¤®…` | File saved in the wrong encoding. Save as UTF-8 or use Unicode escapes |
| Microphone blocked | Click the lock icon in the address bar and allow the microphone |

## Limitations and next steps

- Whisper is not natively streaming, so latency is one pause plus inference time (much faster on a GPU or with a smaller model).
- Whisper detects one language per segment, so rapid code-switching inside one sentence can be imperfect. Larger models (`medium`, `large-v3`) help.
- No authentication or automated tests yet.
- Ideas: per-user history, export to TXT/PDF, transcript clean-up with a local LLM (Ollama), swapping `WhisperClient` for a Gemini Live client.

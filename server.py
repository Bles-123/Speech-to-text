import io
import traceback
import wave
from typing import Optional

import numpy as np
import uvicorn
from fastapi import FastAPI, File, Query, UploadFile
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel

app = FastAPI()

# tiny | base | small | medium | large-v3  (bigger = better Hindi, slower)
model = WhisperModel("small", device="cpu", compute_type="int8")


def wav_to_float32(data: bytes) -> np.ndarray:
    """Our app always sends 16 kHz, mono, 16-bit PCM WAV."""
    with wave.open(io.BytesIO(data), "rb") as w:
        frames = w.readframes(w.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


@app.post("/asr")
def asr(
    audio_file: UploadFile = File(...),
    language: Optional[str] = Query(None),
    initial_prompt: Optional[str] = Query(None),
    task: str = Query("transcribe"),
    output: str = Query("json"),
    encode: bool = Query(True),
):
    try:
        data = audio_file.file.read()
        audio = wav_to_float32(data)
        print(f"Received {len(data)} bytes ({len(audio)/16000:.1f}s), language={language}")
        segments, info = model.transcribe(
            audio,
            language=language,
            task=task,
            initial_prompt=initial_prompt,
            beam_size=5,
            vad_filter=True,
        )
        text = " ".join(s.text.strip() for s in segments).strip()
        print("Result:", text)
        return {"text": text, "language": info.language}
    except Exception as e:
        traceback.print_exc()
        return JSONResponse(status_code=500, content={"error": str(e)})


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=9000)
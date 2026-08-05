#!/usr/bin/env python3
"""
Generates Castivio's startup sound.

The asset is committed -- this is here so the sound can be regenerated,
adjusted and reviewed as a decision rather than inherited as a binary nobody
can change. Standard library only: no numpy, no encoder, nothing to install.

    python3 tools/startup-sound.py

writes `app/src/main/res/raw/castivio_startup.wav`.

## What it makes, and why it sounds like that

A major-ninth rise -- D5, A5, D6, with an F#6 blooming a beat behind them --
over a low D swell. Rising rather than falling, because a fall reads as a
dismissal and this plays when something opens. Ninth rather than a plain triad,
because a triad is a jingle and a ninth is a chord that resolves nothing and
therefore does not ask to be listened to twice.

Each voice is a stack of partials at slightly *inharmonic* multiples. That is
the whole difference between a sum of sines, which sounds like a hearing test,
and a struck bar, which sounds like an object. The attack is a raised cosine
rather than a ramp so nothing clicks, and the tail fades to true zero for the
same reason.

Mono, 44.1 kHz, 16-bit: 92 KB, which is small enough to sit in `res/raw` on a
stick with a gigabyte of RAM and avoids making the app depend on a decoder for
one second of audio.
"""

import math
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 44_100
DURATION = 1.05
OUT = Path("app/src/main/res/raw/castivio_startup.wav")

# (frequency, start, attack, decay, gain)
D5, A5, D6, FS6 = 587.33, 880.00, 1174.66, 1479.98
VOICES = [
    (D5, 0.000, 0.010, 0.55, 0.34),
    (A5, 0.070, 0.010, 0.50, 0.26),
    (D6, 0.140, 0.010, 0.60, 0.30),
    (FS6, 0.240, 0.030, 0.62, 0.18),
]
SUB = (146.83, 0.000, 0.090, 0.45, 0.12)

#: (multiple of the fundamental, relative amplitude). Deliberately not integers.
PARTIALS = [(1.0, 1.00), (2.0, 0.30), (3.01, 0.13), (4.02, 0.06), (5.04, 0.03)]

PEAK_DBFS = -1.5
FADE_OUT = 0.05


def envelope(t: float, attack: float, decay: float) -> float:
    """Raised-cosine attack into an exponential decay. Silent before t=0."""
    if t < 0:
        return 0.0
    if t < attack:
        return 0.5 - 0.5 * math.cos(math.pi * t / attack)
    return math.exp(-(t - attack) / decay)


def voice(t: float, freq: float, start: float, attack: float, decay: float, gain: float) -> float:
    level = envelope(t - start, attack, decay)
    if level < 1e-4:
        return 0.0
    elapsed = t - start
    return gain * level * sum(
        amp * math.sin(2 * math.pi * freq * mult * elapsed) for mult, amp in PARTIALS
    )


def main() -> None:
    count = int(SAMPLE_RATE * DURATION)
    samples = []
    for i in range(count):
        t = i / SAMPLE_RATE
        value = sum(voice(t, *v) for v in VOICES)
        freq, start, attack, decay, gain = SUB
        value += gain * envelope(t - start, attack, decay) * math.sin(2 * math.pi * freq * (t - start))
        samples.append(value)

    peak = max(abs(s) for s in samples) or 1.0
    scale = (10 ** (PEAK_DBFS / 20)) / peak
    fade = int(SAMPLE_RATE * FADE_OUT)

    frames = bytearray()
    for i, sample in enumerate(samples):
        value = sample * scale
        if i > count - fade:
            value *= (count - i) / fade
        frames += struct.pack("<h", max(-32768, min(32767, int(value * 32767))))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(SAMPLE_RATE)
        out.writeframes(bytes(frames))
    print(f"{OUT}: {count} frames, {count / SAMPLE_RATE:.2f}s, {len(frames) // 1024} KB")


if __name__ == "__main__":
    main()

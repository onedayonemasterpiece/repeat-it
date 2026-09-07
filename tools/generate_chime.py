#!/usr/bin/env python3
"""Original deterministic two-note notification sound; not a ringtone or external sample."""
import math
import struct
import wave
from pathlib import Path

path = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/chime.wav'
path.parent.mkdir(parents=True, exist_ok=True)
rate = 22050
samples = []
for i in range(round(rate * 0.65)):
    t = i / rate
    start = 0 if t < 0.30 else 0.34
    local = t - start
    frequency = 659.255 if t < 0.30 else 783.991
    value = 0
    if 0 <= local <= 0.29:
        envelope = min(1, local / 0.018) * math.exp(-local * 15)
        value = .22 * envelope * (math.sin(2*math.pi*frequency*local) + .18*math.sin(4*math.pi*frequency*local))
    samples.append(struct.pack('<h', round(value * 32767)))
with wave.open(str(path), 'wb') as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(rate)
    out.writeframes(b''.join(samples))

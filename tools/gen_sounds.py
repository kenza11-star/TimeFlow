"""Menghasilkan 4 nada alarm bawaan (WAV mono 16-bit 22.05 kHz) ke res/raw. Hanya stdlib."""
import math, struct, wave, os
SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "res", "raw")

def tone(f, d, vol=0.6, decay=6.0, harm=(1.0,)):
    n = int(SR * d); out = []
    for i in range(n):
        t = i / SR
        env = min(1.0, t / 0.005) * math.exp(-decay * t / d)
        s = sum(math.sin(2 * math.pi * f * (k + 1) * t) * a for k, a in enumerate(harm))
        out.append(vol * env * s / sum(harm))
    return out

def sil(d): return [0.0] * int(SR * d)

def save(name, samples):
    p = os.path.join(OUT, name + ".wav")
    with wave.open(p, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32000)) for x in samples))
    print(p, os.path.getsize(p))

# Klasik: bip-bip-bip-bip lalu jeda (loop 2 dtk)
k = []
for _ in range(4): k += tone(1000, 0.12, 0.7, 2.0, (1, .3)) + sil(0.08)
save("alarm_classic", k + sil(1.2))
# Lembut: arpeggio C-E-G-C
s = []
for f in (523.25, 659.25, 783.99, 1046.5): s += tone(f, 0.45, 0.6, 4.0, (1, .4, .15))
save("alarm_soft", s + sil(0.8))
# Digital: dua nada cepat bergantian
d = []
for _ in range(6): d += tone(1400, 0.07, 0.6, 1.5) + tone(1000, 0.07, 0.6, 1.5)
save("alarm_digital", d + sil(0.9))
# Melodi: motif sederhana
m = []
for f, du in ((659.25, .25), (783.99, .25), (880, .25), (783.99, .25), (659.25, .25), (587.33, .25), (659.25, .5)):
    m += tone(f, du, 0.55, 3.0, (1, .35, .1))
save("alarm_melody", m + sil(1.0))

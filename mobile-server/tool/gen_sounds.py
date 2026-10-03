#!/usr/bin/env python3
"""Gera os sons de novo pedido (tons sintetizados, sem licença externa).
Saída: android/app/src/main/res/raw/pedido_<id>.wav (canal da notificação) e assets/sounds/pedido_<id>.wav (pré-escuta).
Os ids precisam bater com shared/push.ts e lib/core/order_sounds.dart. Rode: python3 tool/gen_sounds.py"""
import math, random, struct, wave, pathlib

SR = 22050
root = pathlib.Path(__file__).resolve().parent.parent

def tone(f, dur, amp=1.0, decay=6.0, harm=((1, 1.0),)):
    n = int(SR * dur)
    out = []
    for i in range(n):
        t = i / SR
        env = math.exp(-decay * t) * min(1.0, t / 0.004)
        out.append(amp * env * sum(a * math.sin(2 * math.pi * f * h * t) for h, a in harm))
    return out

def mix(*parts):
    n = max(off + len(s) for off, s in parts)
    out = [0.0] * n
    for off, s in parts:
        for i, v in enumerate(s):
            out[off + i] += v
    return out

def at(sec): return int(SR * sec)

def beep(f, dur, amp=0.6):
    n = int(SR * dur)
    return [amp * math.sin(2 * math.pi * f * i / SR) * min(1.0, i / 200, (n - i) / 200) for i in range(n)]

random.seed(7)
bell = ((1, 1.0), (2.76, 0.5), (5.4, 0.25))
SOUNDS = {
    'sino': mix((0, tone(880, 1.4, 0.7, 3.5, bell)), (at(0.6), tone(1175, 1.2, 0.6, 3.5, bell))),
    'caixa': mix(
        (0, [random.uniform(-1, 1) * 0.5 * math.exp(-60 * i / SR) for i in range(at(0.12))]),
        (at(0.08), tone(1319, 0.9, 0.55, 5.0, ((1, 1.0), (2, 0.3)))),
        (at(0.2), tone(1976, 1.1, 0.5, 4.0, ((1, 1.0), (2, 0.3)))),
    ),
    'campainha': mix((0, tone(659, 1.0, 0.7, 3.0, ((1, 1.0), (2, 0.4)))), (at(0.55), tone(523, 1.4, 0.7, 2.6, ((1, 1.0), (2, 0.4))))),
    'suave': mix((0, tone(523, 1.0, 0.5, 3.5)), (at(0.25), tone(659, 1.0, 0.5, 3.5)), (at(0.5), tone(784, 1.3, 0.5, 3.0))),
    'alerta': mix(*[(at(k * 0.38), beep(1000, 0.24)) for k in range(3)]),
}

for name, samples in SOUNDS.items():
    peak = max(abs(v) for v in samples) or 1
    data = b''.join(struct.pack('<h', int(max(-1, min(1, v / peak * 0.9)) * 32767)) for v in samples)
    for d in (root / 'android/app/src/main/res/raw', root / 'assets/sounds'):
        d.mkdir(parents=True, exist_ok=True)
        with wave.open(str(d / f'pedido_{name}.wav'), 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data)
    print(name, f'{len(samples) / SR:.2f}s')

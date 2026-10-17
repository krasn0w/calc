"""Emulator smoke test: install, grant overlay, launch, press keys, take screenshots, fail on crash."""
import glob
import os
import re
import subprocess
import sys
import time

PKG = "io.github.krasn0w.calc"
os.makedirs("shots", exist_ok=True)


def sh(cmd, check=True):
    print("+", cmd, flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout.strip():
        print(r.stdout[-3000:], flush=True)
    if r.stderr.strip():
        print(r.stderr[-3000:], flush=True)
    if check and r.returncode != 0:
        sys.exit(f"command failed: {cmd}")
    return r.stdout


def shot(name):
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    with open(f"shots/{name}.png", "wb") as f:
        f.write(png)
    print(f"shot {name}: {len(png)} bytes", flush=True)


apk = glob.glob("calc*.apk")[0]
sh(f"adb install -r {apk}")
sh(f"adb shell appops set {PKG} SYSTEM_ALERT_WINDOW allow")
sh("adb logcat -c", check=False)
sh("adb shell input keyevent KEYCODE_HOME")
time.sleep(2)
sh(f"adb shell am start -n {PKG}/.MainActivity")
for _ in range(30):
    if "APPLICATION_OVERLAY" in sh("adb shell dumpsys window windows | grep -A6 'u0 " + PKG + "' || true", check=False):
        break
    time.sleep(1)
time.sleep(2)
shot("1-launch")

# Density and the overlay window's frame.
dens_out = sh("adb shell wm density")
dens = int(re.search(r"Physical density:\s*(\d+)", dens_out).group(1))
over = re.search(r"Override density:\s*(\d+)", dens_out)
if over:
    dens = int(over.group(1))
d = dens / 160.0


def dp(v):
    return int(v * d + 0.5)


dump = sh("adb shell dumpsys window windows")
frame = None
for block in dump.split("Window #"):
    if PKG in block and "APPLICATION_OVERLAY" in block:
        with open("shots/window.txt", "w") as f:
            f.write(block)
        m = re.search(r"(?<![A-Za-z])(?:frame|mFrame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", block)
        if m:
            frame = tuple(int(x) for x in m.groups())
        break
if not frame:
    sys.exit("overlay window not found in dumpsys")
fx, fy, fr, fb = frame
print("overlay frame", frame, "density", dens, flush=True)

KEY, GAP, PAD = dp(52), dp(8), dp(12)
INNER = KEY * 4 + GAP * 3
TOP = fy + dp(6)
KEYS_Y = TOP + dp(36) + dp(88) + GAP
PANEL_Y = KEYS_Y + 5 * KEY + 4 * GAP + GAP

MAIN = [["C", "±", "%", "÷"], ["7", "8", "9", "×"], ["4", "5", "6", "−"], ["1", "2", "3", "+"], ["⋯", "0", ",", "="]]
PANEL = [["MS", "MR", "M+", "M−"], ["(", ")", "xʸ", "√"]]


def tap(label):
    for rows, y0 in ((MAIN, KEYS_Y), (PANEL, PANEL_Y)):
        for r, row in enumerate(rows):
            if label in row:
                c = row.index(label)
                x = fx + PAD + c * (KEY + GAP) + KEY // 2
                y = y0 + r * (KEY + GAP) + KEY // 2
                sh(f"adb shell input tap {x} {y}")
                time.sleep(0.8)
                return
    sys.exit(f"unknown key {label}")


for k in ["1", "2", "+", "3", "×", "4"]:
    tap(k)
shot("2-typing")

tap("⋯")
time.sleep(1.5)
shot("3-panel")

for k in ["−", "√", "(", "1", "6", ")"]:
    tap(k)
time.sleep(2)
shot("4-panel-typing")

tap("=")
tap("M+")
time.sleep(0.5)
shot("5-result-memory")

# Opacity: tap the slider at ~30%.
seek_l = fx + PAD + dp(24) + dp(16)
seek_r = fx + PAD + INNER - dp(32) - dp(16)
sh(f"adb shell input tap {int(seek_l + 0.3 * (seek_r - seek_l))} {TOP + dp(18)}")
time.sleep(1)
shot("6-opacity")

with open("shots/keys.txt", "w") as f:
    f.write(sh("adb logcat -d -s Calc:D", check=False))
crash = sh("adb logcat -d -b crash", check=False)
if crash.strip():
    with open("shots/crash.txt", "w") as f:
        f.write(crash)
if PKG in crash:
    sys.exit("app crashed, see shots/crash.txt")
print("smoke OK", flush=True)

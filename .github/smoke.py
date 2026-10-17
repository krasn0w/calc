"""Emulator smoke test.

The app logs every control's on-screen bounds ("pos <A|F> <id> l t r b") after each layout,
so taps follow the real layout in both modes: A = full-screen app, F = floating window.
"""
import glob
import os
import subprocess
import sys
import time

PKG = "io.github.krasn0w.calc"
os.makedirs("shots", exist_ok=True)


def sh(cmd, check=True, quiet=False):
    if not quiet:
        print("+", cmd, flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if not quiet and r.stdout.strip():
        print(r.stdout[-2000:], flush=True)
    if r.stderr.strip():
        print(r.stderr[-2000:], flush=True)
    if check and r.returncode != 0:
        sys.exit(f"command failed: {cmd}")
    return r.stdout


def shot(name):
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout
    with open(f"shots/{name}.png", "wb") as f:
        f.write(png)
    print(f"shot {name}: {len(png)} bytes", flush=True)


def positions(mode):
    pos = {}
    for line in sh("adb logcat -d -s Calc:D", quiet=True).splitlines():
        i = line.find("pos ")
        if i < 0:
            continue
        parts = line[i:].split()
        if len(parts) == 7 and parts[1] == mode:
            pos[parts[2]] = tuple(int(x) for x in parts[3:7])
    return pos


def wait_for(mode, key, timeout=30):
    for _ in range(timeout * 2):
        p = positions(mode)
        if key in p:
            return p
        time.sleep(0.5)
    sys.exit(f"no layout for {mode}/{key}")


def tap(mode, key, settle=0.8):
    p = positions(mode)
    if key not in p:
        sys.exit(f"unknown key {mode}/{key}; have {sorted(p)}")
    l, t, r, b = p[key]
    sh(f"adb shell input tap {(l + r) // 2} {(t + b) // 2}")
    time.sleep(settle)


apk = glob.glob("calc*.apk")[0]
sh(f"adb install -r {apk}")
sh(f"adb shell appops set {PKG} SYSTEM_ALERT_WINDOW allow")
sh("adb logcat -c", check=False)
sh(f"adb shell am start -n {PKG}/.MainActivity")
wait_for("A", "=")
time.sleep(2)
shot("01-full")

# Percent: -30 + 50% = -45 (standard calculator semantics)
for k in ["3", "0", "±", "+", "5", "0", "%", "="]:
    tap("A", k)
shot("02-percent")

# Repeated "=": 2 + 3 = = = -> 11
tap("A", "clear")
for k in ["2", "+", "3", "=", "=", "="]:
    tap("A", k)
shot("03-repeat")

# Extra panel in full screen
tap("A", "more", settle=1.5)
time.sleep(1)
for k in ["(", "1", "+", "2", ")", "xʸ", "2"]:
    tap("A", k)
shot("04-full-panel")

# Collapse to the floating window
sh("adb logcat -c", check=False)
tap("A", "mode", settle=1)
wait_for("F", "corner")
time.sleep(2)
shot("05-float")

# Resize by the corner: bigger, then check the layout holds
p = positions("F")
l, t, r, b = p["corner"]
cx, cy = (l + r) // 2, (t + b) // 2
sh(f"adb shell input swipe {cx} {cy} {cx + 120} {cy + 260} 900")
time.sleep(2)
shot("06-resized-bigger")

p = positions("F")
l, t, r, b = p["corner"]
cx, cy = (l + r) // 2, (t + b) // 2
sh(f"adb shell input swipe {cx} {cy} {cx - 300} {cy - 420} 900")
time.sleep(2)
shot("07-resized-smaller")

# Close the panel in the floating window, then type
tap("F", "more", settle=1.5)
for k in ["7", "×", "8", "="]:
    tap("F", k)
shot("08-float-typing")

# Opacity ~30%
l, t, r, b = positions("F")["seek"]
sh(f"adb shell input tap {int(l + 0.3 * (r - l))} {(t + b) // 2}")
time.sleep(1)
shot("09-opacity")

# Expand back to full screen
sh("adb logcat -c", check=False)
tap("F", "mode", settle=1)
wait_for("A", "=")
time.sleep(2)
shot("10-back-full")

with open("shots/keys.txt", "w") as f:
    f.write(sh("adb logcat -d -s Calc:D", check=False, quiet=True))
crash = sh("adb logcat -d -b crash", check=False)
if crash.strip():
    with open("shots/crash.txt", "w") as f:
        f.write(crash)
if PKG in crash:
    sys.exit("app crashed, see shots/crash.txt")
print("smoke OK", flush=True)

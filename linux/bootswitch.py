#!/usr/bin/python3
# Set ROCKNIX ABL default boot target in devinfo, then reboot. Usage: bootswitch.py android|linux [--no-reboot]
# Only writes BootMode (0xA92) and BootSourceMode (0xAF0), the same two bytes the ABL menu changes.
import os, subprocess, sys
DEV = "/dev/disk/by-partlabel/devinfo"
STATES = {"android": (1, 0), "linux": (0, 3)}
target = sys.argv[1] if len(sys.argv) > 1 else ""
if target not in STATES:
    sys.exit("usage: bootswitch.py android|linux [--no-reboot]")
mode, source = STATES[target]
with open(DEV, "r+b", buffering=0) as f:
    before = f.read(4096)
    checks = [
        before[:13] == b"ANDROID-BOOT!",
        before[0xA54:0xA62] == b"\x01\x00\x08\x00\x01\x00BootMode",
        before[0xAB2:0xAC6] == b"\x01\x00\x0e\x00\x01\x00BootSourceMode",
        (before[0xA92], before[0xAF0]) in STATES.values(),
    ]
    if not all(checks):
        sys.exit("devinfo layout not recognised - nothing written")
    if (before[0xA92], before[0xAF0]) != (mode, source):
        f.seek(0xA92); f.write(bytes([mode]))
        f.seek(0xAF0); f.write(bytes([source]))
        os.fsync(f.fileno())
    f.seek(0)
    after = f.read(4096)
changed = {i for i in range(4096) if before[i] != after[i]}
if not changed <= {0xA92, 0xAF0} or (after[0xA92], after[0xAF0]) != (mode, source):
    f2 = open(DEV, "r+b", buffering=0); f2.write(before); os.fsync(f2.fileno()); f2.close()
    sys.exit("verification failed - original devinfo restored, not rebooting")
print(f"default boot target: {target}")
if "--no-reboot" not in sys.argv:
    subprocess.run(["systemctl", "reboot"])

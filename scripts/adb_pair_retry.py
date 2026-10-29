#!/usr/bin/env python3
"""Retry `adb pair` in a loop until it succeeds.

The TV's "Pair device with pairing code" dialog shows a code that expires after
a short while, and the adb client dies with a protocol fault if it is stale.
This keeps retrying so the user only has to open the dialog once.

Usage: adb_pair_retry.py <host> <code> [port ...]
"""
import os
import pty
import select
import subprocess
import sys
import time

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"


def try_pair(host, port, code, timeout=8):
    master, slave = pty.openpty()
    proc = subprocess.Popen(
        [ADB, "pair", f"{host}:{port}"],
        stdin=slave, stdout=slave, stderr=slave, close_fds=True,
    )
    os.close(slave)
    sent = False
    out = ""
    end = time.time() + timeout
    while time.time() < end:
        r, _, _ = select.select([master], [], [], 0.3)
        if r:
            try:
                chunk = os.read(master, 4096)
            except OSError:
                break
            if not chunk:
                break
            text = chunk.decode("utf-8", "replace")
            out += text
            if not sent and ("code" in text.lower() or ":" in text):
                time.sleep(0.7)
                os.write(master, (code + "\n").encode())
                sent = True
        if proc.poll() is not None:
            break
    try:
        proc.wait(timeout=5)
    except Exception:
        proc.kill()
    return proc.returncode, out.strip().replace("\n", " | ")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    host = sys.argv[1]
    code = sys.argv[2]
    ports = [int(p) for p in sys.argv[3:]] or [41941, 49152]

    print(f"[*] host={host} code={code} ports={ports}")
    print("[*] Open the TV's 'Pair device with pairing code' dialog NOW.")
    print("[*] Retrying every 3s for 5 minutes...\n")

    deadline = time.time() + 300
    attempt = 0
    while time.time() < deadline:
        attempt += 1
        for port in ports:
            rc, out = try_pair(host, port, code)
            ok = rc == 0 and "Successfully" in out
            print(f"[{attempt:3d}] port {port}: rc={rc} {out}")
            if ok:
                print(f"\n[+] PAIRED on port {port}!")
                print("[+] Now run: adb connect", f"{host}:{port}")
                return 0
        time.sleep(3)
    print("[-] Timed out. Open a fresh pairing dialog and re-run.")
    return 1


if __name__ == "__main__":
    sys.exit(main())

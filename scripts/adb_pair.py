#!/usr/bin/env python3
"""Drive `adb pair` through a pty so the pairing code prompt is handled correctly.

Usage: adb_pair.py <host> <port> <code>
"""
import os
import pty
import select
import subprocess
import sys
import time

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    host, port, code = sys.argv[1], sys.argv[2], sys.argv[3]

    master, slave = pty.openpty()
    proc = subprocess.Popen(
        [ADB, "pair", f"{host}:{port}"],
        stdin=slave,
        stdout=slave,
        stderr=slave,
        close_fds=True,
    )
    os.close(slave)

    sent = False
    deadline = time.time() + 25
    out = ""

    while time.time() < deadline:
        r, _, _ = select.select([master], [], [], 0.4)
        if r:
            try:
                chunk = os.read(master, 4096)
            except OSError:
                break
            if not chunk:
                break
            text = chunk.decode("utf-8", "replace")
            out += text
            sys.stdout.write(text)
            sys.stdout.flush()

            if not sent and ("code" in text.lower() or ":" in text):
                # Give the prompt a beat, then type the code + Enter.
                time.sleep(0.6)
                os.write(master, (code + "\n").encode())
                sent = True

        if proc.poll() is not None:
            # Drain remaining output.
            try:
                while True:
                    r, _, _ = select.select([master], [], [], 0.3)
                    if not r:
                        break
                    chunk = os.read(master, 4096)
                    if not chunk:
                        break
                    text = chunk.decode("utf-8", "replace")
                    out += text
                    sys.stdout.write(text)
                    sys.stdout.flush()
            except OSError:
                pass
            break

    proc.wait(timeout=10)
    print(f"\n[exit={proc.returncode}]")
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())

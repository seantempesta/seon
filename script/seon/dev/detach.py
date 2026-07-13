"""Spawn one argv vector in a new session and print its PID."""

import os
import subprocess
import sys


def main() -> None:
    if len(sys.argv) < 4:
        raise SystemExit("usage: detach.py <cwd> <log> <program> [args ...]")
    cwd, log_path, *argv = sys.argv[1:]
    with open(log_path, "ab", buffering=0) as log:
        child = subprocess.Popen(
            argv,
            cwd=cwd,
            env=os.environ.copy(),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            close_fds=True,
            start_new_session=True,
        )
    print(child.pid)


if __name__ == "__main__":
    main()

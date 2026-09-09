"""Measure descriptor-only status while a real development init owns its lock."""
import fcntl
import json
import queue
import subprocess
import threading
import time
from pathlib import Path


def lock_is_held():
    with Path("data/operator/root-lifecycle.lock").open("r+") as stream:
        try:
            fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        fcntl.lockf(stream, fcntl.LOCK_UN)
        return False


def main():
    process = subprocess.Popen(
        ["bin/seon", "init", "--dev", "default", "--changed", "bin/seon-hook"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    events = queue.Queue()
    lines = []

    def read_output():
        for line in process.stdout:
            lines.append(line)
            events.put(line)
        events.put(None)

    reader = threading.Thread(target=read_output)
    reader.start()
    measured = None
    try:
        deadline = time.monotonic() + 240
        while True:
            line = events.get(timeout=max(0.001, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("init exited before publication progress: " + "".join(lines))
            if "current-src:" in line:
                break
        holder_path = Path("data/operator/root-lifecycle.lock.holder.edn")
        before = holder_path.read_text()
        held_before = lock_is_held()
        started = time.monotonic()
        status = subprocess.run(["bin/seon", "status"], capture_output=True,
                                text=True, timeout=2)
        seconds = time.monotonic() - started
        after = holder_path.read_text()
        held_after = lock_is_held()
        measured = {"init_pid": process.pid, "status_seconds": seconds,
                    "status_exit": status.returncode, "status_output": status.stdout,
                    "holder_before": before, "holder_after": after,
                    "held_before": held_before, "held_after": held_after}
        print(json.dumps(measured), flush=True)
        assert held_before and held_after and before == after
        assert f":seon.boot/pid {process.pid}" in before
        assert status.returncode == 0 and seconds < 2
        assert "default" in status.stdout
        process.wait(timeout=max(0.001, deadline - time.monotonic()))
        reader.join(timeout=2)
        print(json.dumps({"init_exit": process.returncode, "init_output": "".join(lines)}),
              flush=True)
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        reader.join(timeout=2)


if __name__ == "__main__":
    main()

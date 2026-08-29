import os
import signal
import sys


accepted_path, child_pid_path = sys.argv[1:]
child_pid = os.fork()
if child_pid == 0:
    signal.pause()
    raise SystemExit(0)

with open(child_pid_path, "w", encoding="utf-8") as child_pid_file:
    child_pid_file.write(str(child_pid))

print(
    "SEON_TEST_WORKER_EDN "
    "{:seon.test.runner/worker-event :ready "
    ":seon.test.runner/worker-id \"scratch-before\"}",
    flush=True,
)
sys.stdin.readline()
with open(accepted_path, "w", encoding="utf-8") as accepted_file:
    accepted_file.write("accepted")
signal.pause()

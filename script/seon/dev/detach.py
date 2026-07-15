"""Own one generation-bound POSIX process subtree until it is drained."""

from __future__ import annotations

import atexit
import hashlib
import json
import os
import selectors
import signal
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path


START_TIMEOUT = 10.0
APPLICATION_RESULT_LIMIT = 1024 * 1024


def atomic_json(path: str, value: dict[str, object]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.", dir=target.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(value, output, separators=(",", ":"))
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def capture_application_result() -> dict[str, object] | None:
    path = os.environ.get("SEON_APPLICATION_RESULT_PATH")
    if not path:
        return None
    try:
        with open(path, "rb") as source:
            payload = source.read(APPLICATION_RESULT_LIMIT + 1)
    except FileNotFoundError:
        return {"status": "missing"}
    except OSError as error:
        return {"status": "read-error", "error": str(error)[:4096]}
    if len(payload) > APPLICATION_RESULT_LIMIT:
        return {"status": "oversized"}
    try:
        edn = payload.decode("utf-8")
    except UnicodeDecodeError:
        return {"status": "invalid-utf8"}
    return {"status": "captured",
            "edn": edn,
            "sha256": hashlib.sha256(payload).hexdigest()}


def terminal_value(generation: str, trigger: str | None,
                   anchor_exit: int) -> dict[str, object]:
    value: dict[str, object] = {"generation": generation,
                               "status": "drained",
                               "trigger": trigger,
                               "anchor_exit": anchor_exit}
    application_result = capture_application_result()
    if application_result is not None:
        value["application_result"] = application_result
    return value


def wait_json(path: str, result: str, generation: str,
              owner: subprocess.Popen[bytes],
              shutdown_grace: float,
              timeout: float = START_TIMEOUT) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with open(path, encoding="utf-8") as source:
                return json.load(source)
        except (FileNotFoundError, json.JSONDecodeError):
            if owner.poll() is not None:
                terminal = wait_terminal_json(result, generation,
                                              shutdown_grace)
                raise RuntimeError(
                    "containment owner exited before publication: "
                    f"{owner.returncode}; terminal={terminal}"
                )
            time.sleep(0.01)
    raise TimeoutError(f"timed out awaiting containment descriptor: {path}")


def launch(args: list[str]) -> None:
    if len(args) < 8:
        raise SystemExit(
            "usage: detach.py launch <cwd> <log> <generation> "
            "<descriptor> <control-socket> <result> <shutdown-grace-ms> "
            "<program> [args ...]"
        )
    cwd, log_path, generation, descriptor, control, result, grace_ms, *argv = args
    shutdown_grace = positive_grace_seconds(grace_ms)
    with open(log_path, "ab", buffering=0) as log:
        owner = subprocess.Popen(
            [sys.executable, __file__, "owner", cwd, log_path, generation,
             descriptor, control, result, grace_ms, *argv],
            cwd=cwd,
            env=os.environ.copy(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=log,
            close_fds=True,
            start_new_session=True,
        )
    try:
        value = wait_json(descriptor, result, generation, owner,
                          shutdown_grace)
        if value.get("generation") != generation or value.get("owner_pid") != owner.pid:
            raise RuntimeError("containment owner published a crossed generation")
    except BaseException:
        try:
            request_owner(control, f"drain {generation}")
        except OSError:
            pass
        try:
            owner.wait(timeout=shutdown_grace + 5.0)
        except subprocess.TimeoutExpired:
            raise RuntimeError("containment owner did not drain failed launch")
        try:
            os.unlink(control)
        except FileNotFoundError:
            pass
        raise
    print(json.dumps(value, separators=(",", ":")), flush=True)


def request_owner(path: str, request: str) -> str:
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(2.0)
        client.connect(path)
        client.sendall(f"{request}\n".encode())
        return client.recv(256).decode().strip()


def wait_terminal_json(path: str, generation: str,
                       shutdown_grace: float = 2.5) -> dict[str, object] | None:
    deadline = time.monotonic() + shutdown_grace + 5.0
    while time.monotonic() < deadline:
        try:
            with open(path, encoding="utf-8") as source:
                value = json.load(source)
            if value.get("generation") == generation:
                return value
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        time.sleep(0.01)
    return None


def positive_grace_seconds(value: str) -> float:
    milliseconds = int(value)
    if milliseconds <= 0:
        raise ValueError("shutdown grace must be a positive integer")
    return milliseconds / 1000.0


def anchor(args: list[str]) -> None:
    if len(args) < 4:
        raise SystemExit(
            "usage: detach.py anchor <cwd> <log> <shutdown-grace-ms> "
            "<program> [args ...]"
        )
    cwd, log_path, grace_ms, *argv = args
    shutdown_grace = positive_grace_seconds(grace_ms)
    # The owner ignores TERM only across its Popen→cleanup-registration cut.
    # Reset that inherited disposition before the workload inherits signals.
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    with open(log_path, "ab", buffering=0) as log:
        workload = subprocess.Popen(
            argv,
            cwd=cwd,
            env=os.environ.copy(),
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            close_fds=True,
        )
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        print(json.dumps({"anchor_pid": os.getpid(),
                          "process_group": os.getpgrp(),
                          "workload_pid": workload.pid}, separators=(",", ":")),
              flush=True)
        trigger = "workload-exit"
        while True:
            if workload.poll() is not None:
                break
            ready, _, _ = select_with_timeout(sys.stdin, 0.05)
            if ready:
                sys.stdin.readline()
                trigger = "requested"
                break
        os.killpg(os.getpgrp(), signal.SIGTERM)
        deadline = time.monotonic() + shutdown_grace
        if trigger == "requested":
            while workload.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
        else:
            time.sleep(shutdown_grace)
        try:
            print(json.dumps({"transition": "escalating", "trigger": trigger},
                             separators=(",", ":")),
                  flush=True)
        finally:
            os.killpg(os.getpgrp(), signal.SIGKILL)


def select_with_timeout(stream: object, timeout: float) -> tuple[list[object], list[object], list[object]]:
    import select
    return select.select([stream], [], [], timeout)


def owner(args: list[str]) -> None:
    if len(args) < 8:
        raise SystemExit(
            "usage: detach.py owner <cwd> <log> <generation> "
            "<descriptor> <control-socket> <result> <shutdown-grace-ms> "
            "<program> [args ...]"
        )
    cwd, log_path, generation, descriptor, control, result, grace_ms, *argv = args
    shutdown_grace = positive_grace_seconds(grace_ms)
    for path in (descriptor, control, result, f"{result}.adopted"):
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
    def interrupted(_signum: int, _frame: object) -> None:
        raise RuntimeError("containment owner interrupted")

    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    anchor_process = subprocess.Popen(
        [sys.executable, __file__, "anchor", cwd, log_path, grace_ms, *argv],
        cwd=cwd,
        env=os.environ.copy(),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1,
        close_fds=True,
        start_new_session=True,
    )
    atexit.register(abort_owner, anchor_process, generation, result, control,
                    shutdown_grace)
    signal.signal(signal.SIGTERM, interrupted)
    assert anchor_process.stdout is not None
    assert anchor_process.stdin is not None
    startup = json.loads(anchor_process.stdout.readline())
    published = {
        "generation": generation,
        "owner_pid": os.getpid(),
        "anchor_pid": startup["anchor_pid"],
        "process_group": startup["process_group"],
        "workload_pid": startup["workload_pid"],
        "shutdown_grace_ms": int(grace_ms),
        "control_socket": control,
        "result_path": result,
        "adoption_path": f"{result}.adopted",
        "application_result_path":
            os.environ.get("SEON_APPLICATION_RESULT_PATH"),
    }
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(control)
    server.listen(4)
    atomic_json(descriptor, published)
    adopted = False
    drain_requested = False
    deadline = time.monotonic() + START_TIMEOUT
    server.settimeout(0.1)
    while not adopted and not drain_requested and time.monotonic() < deadline:
        try:
            connection, _ = server.accept()
        except TimeoutError:
            continue
        with connection:
            request = connection.recv(256).decode("utf-8").strip()
            if request == f"adopt {generation}":
                atomic_json(published["adoption_path"],
                            {"generation": generation, "status": "adopted"})
                adopted = True
                connection.sendall(f"adopted {generation}\n".encode())
            elif request == f"drain {generation}":
                anchor_process.stdin.write("drain\n")
                anchor_process.stdin.flush()
                drain_requested = True
                connection.sendall(f"accepted {generation}\n".encode())
            else:
                connection.sendall(b"generation-mismatch\n")
    if not adopted and not drain_requested:
        anchor_process.stdin.write("drain\n")
        anchor_process.stdin.flush()
        drain_requested = True
    server.setblocking(False)
    selector = selectors.DefaultSelector()
    if adopted:
        selector.register(server, selectors.EVENT_READ, "control")
    selector.register(anchor_process.stdout, selectors.EVENT_READ, "anchor")
    escalating = False
    trigger = None
    try:
        while not escalating:
            for key, _ in selector.select(timeout=0.1):
                if key.data == "control":
                    connection, _ = server.accept()
                    with connection:
                        connection.setblocking(True)
                        request = connection.recv(256).decode("utf-8").strip()
                        if request == f"drain {generation}":
                            anchor_process.stdin.write("drain\n")
                            anchor_process.stdin.flush()
                            connection.sendall(f"accepted {generation}\n".encode())
                        else:
                            connection.sendall(b"generation-mismatch\n")
                else:
                    line = anchor_process.stdout.readline()
                    if line:
                        message = json.loads(line)
                        escalating = message.get("transition") == "escalating"
                        trigger = message.get("trigger")
                    elif anchor_process.poll() is not None:
                        raise RuntimeError("anchor exited without escalation evidence")
        return_code = anchor_process.wait(timeout=5)
        if return_code != -signal.SIGKILL:
            raise RuntimeError(f"anchor exited with unexpected status {return_code}")
        if not wait_group_absent(anchor_process.pid):
            raise RuntimeError("execution group remained present after anchored KILL")
        atomic_json(result, terminal_value(generation, trigger, return_code))
        atexit.unregister(abort_owner)
    finally:
        selector.close()
        server.close()
        try:
            os.unlink(control)
        except FileNotFoundError:
            pass


def drain_anchor(anchor_process: subprocess.Popen[str],
                 shutdown_grace: float) -> tuple[bool, str | None]:
    if anchor_process.poll() is not None:
        return False, None
    assert anchor_process.stdin is not None
    assert anchor_process.stdout is not None
    try:
        anchor_process.stdin.write("drain\n")
        anchor_process.stdin.flush()
    except BrokenPipeError:
        return False, None
    deadline = time.monotonic() + shutdown_grace + 5.0
    escalating = False
    trigger = None
    while time.monotonic() < deadline:
        exited = anchor_process.poll() is not None
        ready, _, _ = select_with_timeout(
            anchor_process.stdout,
            0.0 if exited else max(0.0, deadline - time.monotonic())
        )
        if ready:
            line = anchor_process.stdout.readline()
            if line:
                message = json.loads(line)
                escalating = message.get("transition") == "escalating"
                trigger = message.get("trigger")
        if exited:
            break
    if anchor_process.poll() is None:
        anchor_process.wait(timeout=max(0.1, deadline - time.monotonic()))
    return escalating and anchor_process.returncode == -signal.SIGKILL, trigger


def abort_owner(anchor_process: subprocess.Popen[str], generation: str,
                result: str, control: str, shutdown_grace: float) -> None:
    try:
        drained, trigger = drain_anchor(anchor_process, shutdown_grace)
        if drained and wait_group_absent(anchor_process.pid):
            atomic_json(result, terminal_value(generation, trigger,
                                               anchor_process.returncode))
    finally:
        try:
            os.unlink(control)
        except FileNotFoundError:
            pass


def wait_group_absent(process_group: int) -> bool:
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        try:
            os.killpg(process_group, 0)
        except ProcessLookupError:
            return True
        time.sleep(0.01)
    return False


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: detach.py <launch|owner|anchor> ...")
    mode, *args = sys.argv[1:]
    {"launch": launch, "owner": owner, "anchor": anchor}[mode](args)


if __name__ == "__main__":
    main()

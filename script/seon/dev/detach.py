"""Own one generation-bound POSIX process subtree until it is drained."""

from __future__ import annotations

import atexit
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
TERM_GRACE = 2.5


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


def wait_json(path: str, result: str, generation: str,
              owner: subprocess.Popen[bytes],
              timeout: float = START_TIMEOUT) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with open(path, encoding="utf-8") as source:
                return json.load(source)
        except (FileNotFoundError, json.JSONDecodeError):
            if owner.poll() is not None:
                terminal = wait_terminal_json(result, generation)
                raise RuntimeError(
                    "containment owner exited before publication: "
                    f"{owner.returncode}; terminal={terminal}"
                )
            time.sleep(0.01)
    raise TimeoutError(f"timed out awaiting containment descriptor: {path}")


def launch(args: list[str]) -> None:
    if len(args) < 7:
        raise SystemExit(
            "usage: detach.py launch <cwd> <log> <generation> "
            "<descriptor> <control-socket> <result> <program> [args ...]"
        )
    cwd, log_path, generation, descriptor, control, result, *argv = args
    with open(log_path, "ab", buffering=0) as log:
        owner = subprocess.Popen(
            [sys.executable, __file__, "owner", cwd, log_path, generation,
             descriptor, control, result, *argv],
            cwd=cwd,
            env=os.environ.copy(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=log,
            close_fds=True,
            start_new_session=True,
        )
    try:
        value = wait_json(descriptor, result, generation, owner)
        if value.get("generation") != generation or value.get("owner_pid") != owner.pid:
            raise RuntimeError("containment owner published a crossed generation")
    except BaseException:
        try:
            request_owner(control, f"drain {generation}")
        except OSError:
            pass
        try:
            owner.wait(timeout=START_TIMEOUT)
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


def wait_terminal_json(path: str, generation: str) -> dict[str, object] | None:
    deadline = time.monotonic() + TERM_GRACE + 5.0
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


def anchor(args: list[str]) -> None:
    if len(args) < 3:
        raise SystemExit("usage: detach.py anchor <cwd> <log> <program> [args ...]")
    cwd, log_path, *argv = args
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
        # A command byte or workload exit has the same meaning: close this
        # generation. The anchor is the sole holder of group signal authority.
        while workload.poll() is None:
            ready, _, _ = select_with_timeout(sys.stdin, 0.05)
            if ready:
                sys.stdin.readline()
                break
        os.killpg(os.getpgrp(), signal.SIGTERM)
        time.sleep(TERM_GRACE)
        try:
            print(json.dumps({"transition": "escalating"}, separators=(",", ":")),
                  flush=True)
        finally:
            os.killpg(os.getpgrp(), signal.SIGKILL)


def select_with_timeout(stream: object, timeout: float) -> tuple[list[object], list[object], list[object]]:
    import select
    return select.select([stream], [], [], timeout)


def owner(args: list[str]) -> None:
    if len(args) < 7:
        raise SystemExit(
            "usage: detach.py owner <cwd> <log> <generation> "
            "<descriptor> <control-socket> <result> <program> [args ...]"
        )
    cwd, log_path, generation, descriptor, control, result, *argv = args
    for path in (descriptor, control, result, f"{result}.adopted"):
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
    def interrupted(_signum: int, _frame: object) -> None:
        raise RuntimeError("containment owner interrupted")

    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    anchor_process = subprocess.Popen(
        [sys.executable, __file__, "anchor", cwd, log_path, *argv],
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
    atexit.register(abort_owner, anchor_process, generation, result, control)
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
        "control_socket": control,
        "result_path": result,
        "adoption_path": f"{result}.adopted",
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
                    elif anchor_process.poll() is not None:
                        raise RuntimeError("anchor exited without escalation evidence")
        return_code = anchor_process.wait(timeout=5)
        if return_code != -signal.SIGKILL:
            raise RuntimeError(f"anchor exited with unexpected status {return_code}")
        if not wait_group_absent(anchor_process.pid):
            raise RuntimeError("execution group remained present after anchored KILL")
        atomic_json(result, {"generation": generation,
                             "status": "drained",
                             "anchor_exit": return_code})
        atexit.unregister(abort_owner)
    finally:
        selector.close()
        server.close()
        try:
            os.unlink(control)
        except FileNotFoundError:
            pass


def drain_anchor(anchor_process: subprocess.Popen[str]) -> bool:
    if anchor_process.poll() is not None:
        return False
    assert anchor_process.stdin is not None
    assert anchor_process.stdout is not None
    try:
        anchor_process.stdin.write("drain\n")
        anchor_process.stdin.flush()
    except BrokenPipeError:
        return False
    deadline = time.monotonic() + TERM_GRACE + 5.0
    escalating = False
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
        if exited:
            break
    if anchor_process.poll() is None:
        anchor_process.wait(timeout=max(0.1, deadline - time.monotonic()))
    return escalating and anchor_process.returncode == -signal.SIGKILL


def abort_owner(anchor_process: subprocess.Popen[str], generation: str,
                result: str, control: str) -> None:
    try:
        if (drain_anchor(anchor_process)
                and wait_group_absent(anchor_process.pid)):
            atomic_json(result, {"generation": generation,
                                 "status": "drained",
                                 "anchor_exit": anchor_process.returncode})
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

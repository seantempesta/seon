---
type: research
status: active
tags: [research, agent, component]
---

# MLX live wiring audit — 2026-07-15

## Decision

The exact live invocation remains the existing
`seon_inspect.catalog.run_native_task` boundary. The generated database row is
constructed by `seon_inspect.tasks.milestone_lift` with milestone `db`, seed
`1`, and position `0`; that selects exactly
`database_workflow-seed1-000`. The pod remains the real model caller and
Inspect remains inert at `mockllm/model`.

The previous operational heredoc in
[[qwen25-coder-05b-database-diagnostic-2026-07-15]] is now stale in one
specific way: it does not pass the required `model_server_snapshot` callback.
There is no other production call site for `run_native_task`; repository
search finds only the function, focused tests, and documentation. The replay
must update that same one-shot admitted invocation, not add a CLI, runner,
supervisor, or model lifecycle namespace.

The callback can be fully invocation-local. Its PID is only a selector supplied
by the process owner. On every observation it reopens that PID, checks the
original creation instant, exact argument vector, and ownership of the exact
loopback listening socket. `mlx_model_server_snapshot` then hashes the complete
revision snapshot and config, the serving module, the exact argv, and the
serving package tuple. Catalog evaluates this callback before task construction
and again after terminal-log publication, retains both maps, requires exact
equality, and reopens them through the existing finalizer.

No source-code gap blocks the admitted replay. The remaining blockers are
operational and already ordered in [[../roadmap#Exact next order]]: the runtime
lane must hand off one coordinated dependency coordinate; ACME must rebuild
ready from that coordinate; the exact model path and endpoint must be
transacted into ACME and read back; and the already-owned listener must still
pass the callback when the sample starts. A PID file must not become model
identity or lifecycle authority.

## Dependency ledger

| Dependency or mechanism | Selected identity at audit | Source-grounded behavior used |
|---|---|---|
| Seon checkout | `08a9ef33917e7b2df66162d61db09078c4da6021` | Contains `84a680cb` and `6bf1e2ca`, the immutable model admission and honest endpoint changes. The concurrent dirty `reference-code/datahike` checkout is owned by the runtime lane and was not inspected or changed here. |
| Native task boundary | `src-inspect-ai/src/seon_inspect/catalog.py`, `run_native_task` | Verifies selected sources, requires static-target and model-server callbacks, snapshots both before task construction, snapshots both after Inspect publishes terminal logs, writes end metadata with Inspect's public edit API, rejects drift, and reopens retained logs. |
| Exact task factory | `src-inspect-ai/src/seon_inspect/tasks/milestone_lift.py`, `task_identity("db")` and `milestone_lift` | `milestone="db"`, `seed=1`, and `positions=[0]` call `generate_rows("database_workflow", 1, 1)` and select `database_workflow-seed1-000`. The task owns its dataset, pod solver, database oracle, and fabrication scorer. |
| Capability admission | `src-inspect-ai/src/seon_inspect/solver.py`, `require_scorable_pod_state` | Before task scoring, joins every ordered attempt to the admitted full chat-completions endpoint and absolute snapshot path; joins every successful response to the admitted response model and fingerprint. |
| Target observer | `src-inspect-ai/src/seon_inspect/cluster.py`, `static_target_snapshot` | Retains byte-exact `bin/acme status --edn`, requires ready state and selected pod URL, and does not mutate lifecycle. |
| MLX observer | `src-inspect-ai/src/seon_inspect/cluster.py`, `mlx_model_server_snapshot` | Requires a full-revision absolute Hugging Face snapshot, stable injected process observations, exact `--model` argv, `mlx-lm` and `mlx` package identities, module/config/content hashes, and a closed response identity. |
| Inspect AI | `reference-code/inspect-ai` at `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; intentional nested view overlay `f3588038f399de82eec7d189f82a31402153f553` | Existing native eval, metadata-edit, write, and read-back APIs are the only artifact path. The parent checkout's only reported dirt is the separately admitted nested overlay. |
| Inspect Evals | `reference-code/inspect-evals` at `97c99f5f6507fc5d1449fe3247f267d591f64350` | Remains part of selected-source admission even though this generated native task does not borrow an Inspect Evals dataset. |
| Python closure | `src-inspect-ai/uv.lock` SHA-256 `043c15152ad92feba7c3f9d60b9959530ada45b052f4b5ff9cc6cd6e35e1611c`; `evaluation-sources.lock.json` SHA-256 `9da7033676ea71eabb21f983301185aa04c28a83089e29bae1578f3bc224d884` | The invocation uses the selected `src-inspect-ai/.venv`; its locked Inspect closure currently includes `psutil 7.2.2` for host process observation. No runtime dependency is added by this audit. |
| MLX serving runtime | `/Users/sean/src/seon-stable/src-needle/.venv`; `mlx-lm 0.31.3`, `mlx 0.32.0`, `transformers 5.13.1`, `huggingface-hub 1.23.0` | The exact installed runtime owns `mlx_lm.server`; the observer hashes its module instead of trusting versions alone. `get_system_fingerprint` derives the same value the server installs on its response handler. |
| First artifact | `/Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen2.5-Coder-0.5B-Instruct-4bit/snapshots/6b16732e5af5cd9bd600186ad59fa618867ef7a4` | Complete revision-pinned snapshot; `config.json` declares four-bit quantization with group size 64. The launch default, Seon request model, admitted artifact path, and successful response model must all be this exact path. |

## Exact existing invocation path

The data and control flow is:

1. `run_native_task(task_identity("db"), milestone_lift, ...)` verifies the
   frozen selected-source closure.
2. `static_target_snapshot` requires the semantic ACME operator to report ready
   at the exact `http://127.0.0.1:7994/agents/run` door.
3. The new required callback calls `mlx_model_server_snapshot` and produces the
   immutable model-server start map before `milestone_lift` constructs a task.
4. `milestone_lift(milestone="db", endpoint="pod", epochs=1, seed=1,
   positions=[0], ...)` constructs only `database_workflow-seed1-000`.
5. `milestone_solver` calls the existing `pod_milestone_driver`, which posts the
   frozen prompt to ACME's `/agents/run`. The ACME pod, not Inspect's inert
   model, makes each OpenAI-compatible request.
6. `_record_result` preserves the pod's final coordinate, turn/eval evidence,
   ordered database operations, and ordered model attempts.
7. `require_scorable_pod_state` rejects infrastructure closes, validates the
   frozen execution evidence, and joins every attempt to the admitted model
   server before the unchanged database and fabrication scorers run.
8. Catalog observes source, target, and model server again, writes those end
   maps into the terminal `.eval`, requires byte equality, then the existing
   finalizer reopens the retained artifact and checks both metadata scopes.

This is the only path that can close P0b. Calling `milestone_lift` directly or
using `inspect eval` directly would bypass run-level start/end admission even
though the task itself still verifies selected source.

## Live read-only evidence

The owning root task had already started PID `36369`; this audit did not start,
stop, or signal it. Read-only observation found exactly this command and
listener:

```text
/opt/homebrew/Cellar/python@3.14/3.14.6/Frameworks/Python.framework/Versions/3.14/Resources/Python.app/Contents/MacOS/Python
  /Users/sean/src/seon-stable/src-needle/.venv/bin/mlx_lm.server
  --model /Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen2.5-Coder-0.5B-Instruct-4bit/snapshots/6b16732e5af5cd9bd600186ad59fa618867ef7a4
  --host 127.0.0.1 --port 18081
```

PID `36369` owns the `127.0.0.1:18081` listening socket. Its creation instant
is `2026-07-15T12:20:26.140768+00:00`. The root task's exact-path one-token
smoke returned the same absolute response model and fingerprint
`0.31.3-0.32.0-macOS-26.5.2-arm64-arm-64bit-Mach-O-applegpu_g17s`.

The real observer succeeded twice consecutively after that smoke and returned
byte-equal maps. The stable projections were:

- artifact manifest SHA-256
  `5b5a0fa1a9ffa796bad62edf72813ae5665d29307ef54357441fc76681bbec06`;
- artifact config SHA-256
  `3542f21e28bfe8422fe98249af39a5b7770fa41ca7d60cda1b72906c2c53b998`;
- artifact size `289601531` bytes;
- argv SHA-256
  `14f4561d3ef70e4ce2f44aa7cf9782686053eee1a8fb4be78489f0e754f333ac`;
- server-module SHA-256
  `cdfcb4ac848636f9927851a0ec7a951584526530cb7832ba58049e4a9144db8b`;
  and
- packages `mlx-lm 0.31.3` and `mlx 0.32.0`.

This proves the callback is usable and stable before the run. It is not the
admitted proof: only start/end maps retained and reopened from the finalized
sample `.eval` can close the blocker.

## Invocation-local callback

The process owner supplies the PID selected when it launched the dedicated
listener, for example as `SEON_MLX_PID`. The callback does not trust that number
by itself. Capture the first creation instant and then use this shape on every
observation:

```python
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import psutil

from seon_inspect.cluster import mlx_model_server_snapshot

MLX_PYTHON = Path(
    "/Users/sean/src/seon-stable/src-needle/.venv/bin/python"
)
MLX_SERVER = Path(
    "/Users/sean/src/seon-stable/src-needle/.venv/bin/mlx_lm.server"
)
SNAPSHOT = Path(
    "/Users/sean/.cache/huggingface/hub/"
    "models--mlx-community--Qwen2.5-Coder-0.5B-Instruct-4bit/"
    "snapshots/6b16732e5af5cd9bd600186ad59fa618867ef7a4"
)
ENDPOINT = "http://127.0.0.1:18081/v1/chat/completions"
PID = int(os.environ["SEON_MLX_PID"])

RUNTIME = json.loads(subprocess.check_output(
    [str(MLX_PYTHON), "-c", """
import json
from importlib.metadata import version
import mlx_lm.server
print(json.dumps({
  "module": mlx_lm.server.__file__,
  "fingerprint": mlx_lm.server.get_system_fingerprint(),
  "packages": {name: version(name) for name in
               ["mlx-lm", "mlx", "transformers", "huggingface-hub"]},
}))
"""], text=True))

SELECTED_START = psutil.Process(PID).create_time()

def process_snapshot():
    process = psutil.Process(PID)
    if process.create_time() != SELECTED_START:
        raise RuntimeError("owned MLX PID was replaced")
    argv = process.cmdline()
    expected = {
        "--model": str(SNAPSHOT),
        "--host": "127.0.0.1",
        "--port": "18081",
    }
    if str(MLX_SERVER) not in argv:
        raise RuntimeError("owned process is not the selected MLX server")
    for flag, value in expected.items():
        if argv.count(flag) != 1 or argv[argv.index(flag) + 1] != value:
            raise RuntimeError(f"owned MLX process has wrong {flag}")
    listeners = [
        connection for connection in process.net_connections(kind="tcp")
        if (connection.status == psutil.CONN_LISTEN
            and connection.laddr.ip == "127.0.0.1"
            and connection.laddr.port == 18081)
    ]
    if len(listeners) != 1:
        raise RuntimeError("owned MLX process does not own the selected socket")
    return {
        "pid": PID,
        "start_instant": datetime.fromtimestamp(
            SELECTED_START, timezone.utc).isoformat(),
        "argv": argv,
    }

def model_server_snapshot():
    return mlx_model_server_snapshot(
        ENDPOINT,
        SNAPSHOT,
        process_snapshot=process_snapshot,
        server_module=RUNTIME["module"],
        package_versions=RUNTIME["packages"],
        system_fingerprint=RUNTIME["fingerprint"],
        quantization="bits=4;group_size=64",
    )
```

`psutil.Process.net_connections` is used only for the already selected process;
the global macOS connection census can fail on unrelated protected processes.
If the invocation itself creates the listener with `subprocess.Popen`, use
`Popen.pid` as the selector and retain the same checks. That one-shot parent /
child ownership is an evaluation invocation, not a checked-in supervisor. It
must still clean up its child in `finally`; the semantic Seon operator must not
be extended to own this external model dependency for P0b.

## Exact post-handoff operations

After the runtime lane's explicit handoff, first require `bin/acme status
--edn` to report ready. Through the repository's cluster-qualified `eval_cljs`
boundary for `acme/root`, transact the exact request identity using the one
database API:

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.ai/id "config"
     :seon.ai/provider :openai-compat
     :seon.ai/base-url "http://127.0.0.1:18081/v1/chat/completions"
     :seon.ai/model "/Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen2.5-Coder-0.5B-Instruct-4bit/snapshots/6b16732e5af5cd9bd600186ad59fa618867ef7a4"
     :seon.ai/temperature 0.2
     :seon.ai/max-tokens 1024
     :seon.ai/thinking "false"}]})
```

Inspect the returned `:seon.db/ok?` envelope, then use a second read-only form
against the same pod and require the resolved endpoint/model/sampling values to
equal the declaration:

```clojure
(seon.ai/resolved-config {:seon.db/db @seon.db/*conn*})
```

Do not add these model values to `config/system.edn`: the `:seon.ai/config` row
is the existing owner and the runtime resolves it per request from the
database.

Then run one admitted sample with the invocation-local callback above:

```python
from pathlib import Path

from seon_inspect.catalog import run_native_task
from seon_inspect.cluster import static_target_snapshot
from seon_inspect.tasks.milestone_lift import milestone_lift, task_identity

cluster_url = "http://127.0.0.1:7994/agents/run"
run_dir = Path("evals/runs/2026-07-15-p0b-db-qwen25coder05b-admitted")

logs = run_native_task(
    identity=task_identity("db"),
    task_factory=milestone_lift,
    task_kwargs={
        "milestone": "db",
        "endpoint": "pod",
        "epochs": 1,
        "seed": 1,
        "positions": [0],
        "cluster_url": cluster_url,
    },
    target_snapshot=lambda: static_target_snapshot(
        cluster_url, ["bin/acme", "status", "--edn"]),
    model_server_snapshot=model_server_snapshot,
    evidence_dir=run_dir,
    log_dir=str(run_dir / "native-logs"),
    model="mockllm/model",
    display="plain",
)
```

The required callback is the only change from the earlier admitted replay
shape. Catalog itself performs finalization and returns only after reopening
the exact retained metadata. The operator should then explicitly read the
retained `.eval` and inspect sample id, status, source/target/model start and
end maps, the successful attempt join, database-operation evidence, final
database coordinate, unchanged scores, and any reviewed classification.

## Shortest falsifiers

1. **Missing operational wiring:** run the old heredoc unchanged. It must fail
   with `model_server_snapshot is required` before task construction. This is
   the current missing callsite in one line.
2. **Wrong task membership:** construct with any seed/position other than seed
   `1`, position `0`, or inspect a sample id other than
   `database_workflow-seed1-000`. Reject before treating the artifact as P0b.
3. **PID reuse or process replacement:** keep the numeric PID but change its
   creation instant or argv. `process_snapshot` must fail; catalog's end map
   must never normalize it into the admitted start identity.
4. **Foreign endpoint:** point the identity at port `18081` while the selected
   PID does not own its loopback listening socket. The invocation callback must
   fail before task construction.
5. **Model switching:** keep the listener but change either its `--model`, the
   database model value, or one attempt's requested model. The process observer
   or common solver admission must reject it before the milestone score.
6. **Content drift:** change one snapshot byte, module byte, package version,
   response fingerprint, or manifest membership. Start observation, end
   observation, or the successful-attempt join must differ and the terminal
   `.eval` must be retained as rejected evidence.
7. **Status drift:** change any admitted ACME status bytes or lose ready state
   during the sample. The existing target callback/finalizer must reject the
   run independently of model identity.
8. **False completion:** observe two equal model maps without running the
   sample. This remains diagnostic only; absence of the finalized native log
   cannot close the issue.

## Remaining boundary

The source path is complete for the first formal MLX row. There is deliberately
no general model-process supervisor, PID registry, or database model-server
entity. The observer callback is a trusted host admission action analogous to
the existing target callback, while its returned closed data is retained and
checked exactly.

The one limitation is operational durability: the current PID is owned by the
root task and may exit before dependency handoff. If it does, the owner may
start the same dedicated command again and pass the newly selected PID; it may
not reuse the old PID or identity map. That is not a code blocker because the
formal claim begins only when `run_native_task` observes the live listener and
the exact database configuration at run start.

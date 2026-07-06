---
type: research
status: completed
tags: [research, agent]
---

# terminal-bench adapter — mechanism proof (Unit D, 2026-07-06)

The Seon pod driven as a custom terminal-bench `BaseAgent` inside THEIR
unmodified harness, proven end-to-end on ONE vendored task (`hello-world`).
Build-only unit — no multi-task runs (owner throttle).

## What was built

- **`src-inspect-ai/src/seon_inspect/tb_agent.py`** — `SeonAgent(BaseAgent)`,
  importable via tb's `--agent-import-path seon_inspect.tb_agent:SeonAgent`
  (`agent_factory.py:64-79`). `perform_task` (`base_agent.py:125`):
  1. `container.put_archive`s the Seon runtime (the `/opt/seon` tree extracted
     ONCE from the pinned overlay volume `seon-runtime-slice3` — the SAME
     artifact the SWE-bench arm mounts — into a cached 727 MB host tarball) +
     the env-overridable `docker/seon-entrypoint` at `/seon-entrypoint`.
  2. `exec_run`s the entrypoint DETACHED to boot the cluster with the bench env
     (`SEON_FS_ROOT=<task workdir>` writable, `SEON_SHELL=1`, the model key).
  3. Reaches the pod with the BUNDLED node (`/opt/seon/node/bin/node -e …`) via
     `container.exec_run` — no curl/nc assumed in the task image; tb publishes
     no ports for us. Readiness GET + the door POST both run this way.
  4. Applies run bounds onto the ROOT agent via the SHARED
     `bench_common.apply_run_bounds` (same wire-REPL mechanism as SWE-bench).
  5. Drives `POST /agents/run` with a goal-stated contract, returns an
     `AgentResult` so THEIR oracle runs unchanged.
- **`src-inspect-ai/src/seon_inspect/bench_common.py`** — the run-bounds +
  wire-REPL machinery LIFTED out of `swebench_arm.py` (was copy-risk) so both
  arms share ONE implementation; the arm binds its own exec channel.

## The one-task result (their harness, their oracle, unchanged)

`tb run --dataset-path reference-code/terminal-bench/original-tasks
--task-id hello-world --agent-import-path seon_inspect.tb_agent:SeonAgent
--model deepseek/deepseek-chat --n-concurrent 1` (run dir under `tb-run/`):

| Signal | Value |
|---|---|
| tb verdict | **Unresolved** (accuracy 0.00, n=1) |
| oracle tests | `test_hello_file_exists` FAILED, `test_hello_file_content` FAILED — tb copied its `tests/` in + ran them in tmux + parsed |
| runtime injected | 6.0 s (put_archive 727 MB) |
| cluster boot | 14.8 s (wire-server + pod, in-container) |
| agent | 1 turn, 8 evals, 13.6 s, `deepseek-v4-pro`, `closed_reason :completed` |
| agent reply | "Done! Created `/app/hello.txt` with the content \"Hello, world!\"." |

**The mechanism is PROVEN** (every Unit-D acceptance criterion met): tb built
the task container, ran OUR agent, our cluster booted + acted inside it (timing
in `agent-logs/seon-timing.json`, door reply in `agent-logs/seon-door-reply.json`),
tb copied its own tests in, ran them, and produced its own verdict. Our agent
occupies NO tmux channel (by design) — `sessions/agent.cast` shows only the
oracle's own recording, and the pod's 8 evals are the trajectory.

**The Unresolved verdict is HONEST, attributable data (a real finding, NOT a
mechanism failure):** the agent REPLIED that it created `/app/hello.txt`, but
the file was absent when the oracle checked ~4 s later. Workspace detection was
correct (`SEON_FS_ROOT=/app`, container prompt `root@…:/app#`). Candidate roots
to hand the tooling lane: (a) an fs-verb path-doubling when the contract states
an ABSOLUTE path (`/app/hello.txt`) while the verb is rooted AT `/app`, landing
the write at `/app/app/hello.txt`; or (b) a fabricated success echo (the
standing fabricated-echo render lever). Evidence retained for the attribution.

## TB pin finding (design §4)

- The vendored submodule (`reference-code/terminal-bench` @ `1a6ffa96`, pkg
  `0.2.18`) ships **241 tasks** (`original-tasks/`) + a `registry.json` whose
  `terminal-bench-core` versions are **head / 0.1.0 (71) / 0.1.1 (80)** — there
  is **NO Terminal Bench 2.0 entry** (the published **59.1** anchor).
- **For this unit the vendored tasks are correct** — the goal is the adapter
  MECHANISM, not the anchor number. `hello-world` uses the glibc
  `ghcr.io/laude-institute/t-bench/python-3-13` base (arm64-safe on this host).
- **2.0 comparability still needs** (owner/orchestrator call, NOT done here —
  shared tree): bump the submodule to a commit carrying the 2.0 task set, OR
  pip-pin the tb package version whose registry has the 2.0 entry; then freeze
  the 2.0 subset (excluding the ~7 `--platform=linux/amd64` tasks) in
  `datasets.lock`. Until then tb rows are internal-delta / mechanism-proof only.

## No ledger row

Per the brief, a `scorecard.jsonl` row is written ONLY if a SCORED task ran as a
real capability sample. This was a mechanism smoke (n=1, one vendored task, the
adapter's first end-to-end); the honest verdict + evidence live here, not in the
capability ledger. A `terminal_bench` row enters once the 2.0 pin is resolved and
a frozen subset runs both arms (design §8 slice 5).

## How to reproduce

```bash
# sibling tb venv (py3.13; tb is NOT in the pinned src-inspect-ai/.venv):
python3.13 -m venv tmp/tb-venv
tmp/tb-venv/bin/pip install -e reference-code/terminal-bench pytest
export PYTHONPATH=$PWD/src-inspect-ai/src            # seon_inspect (stdlib-only chain)
export DEEPSEEK_API_KEY=…                            # the in-container pod reaches the API
tmp/tb-venv/bin/tb run --dataset-path reference-code/terminal-bench/original-tasks \
  --task-id hello-world --agent-import-path seon_inspect.tb_agent:SeonAgent \
  --model deepseek/deepseek-chat --n-concurrent 1 \
  --output-path evals/runs/2026-07-06-tb-adapter/tb-run --no-livestream
```

Prereqs: Docker running; the `seon-runtime-slice3` overlay volume present (the
overlay tarball caches to `tmp/seon-runtime-slice3-opt-seon.tar` on first run).

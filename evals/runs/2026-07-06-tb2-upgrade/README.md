---
type: research
status: completed
tags: [research, agent]
---

# Terminal-Bench 2.0 upgrade — distribution, adapter, arm64 reality, freeze

Goal: make our terminal-bench numbers comparable to the published **Terminal
Bench 2.0** anchor (DeepSeek NT 59.1) by getting the 2.0 task set available and
re-verifying our adapter against its harness — WITHOUT bumping the shared
`reference-code/terminal-bench` submodule or touching `tmp/tb-venv` (the shipped
0.2.18 adapter's env).

## TL;DR

- **TB 2.0 did NOT ship as a newer `terminal-bench` release.** It shipped as a
  WHOLE NEW HARNESS — **Harbor** (`pip install harbor`). PyPI `terminal-bench`
  latest is still `0.2.18` (== our vendored submodule) and the live registry has
  **no** `terminal-bench-core==2.0` row. The 59.1 anchor's task set is the
  Harbor dataset **`terminal-bench/terminal-bench-2`** — **89 tasks**.
- **New env `tmp/tb2-venv` (py3.13, `harbor==0.17.1`)**; `tmp/tb-venv` (0.2.18)
  left intact. No submodule bump.
- **Adapter drift is LARGE** (different package, async `setup`/`run`,
  `BaseEnvironment` abstraction, populate-`AgentContext`) → a focused
  **`tb2_agent.py`** (harbor `BaseAgent`) that REUSES the one injection/contract/
  door mechanism from `tb_agent.py`. `tb_agent.py` is UNCHANGED.
- **arm64 reality (the load-bearing finding):** all **89/89** prebuilt
  `alexgshaw/<task>:20251031` images are **amd64-only** (verified). The Seon
  overlay bundles an **arm64** node. So native-arm64-runnable = **0**; on this
  host TB-2 runs amd64-emulated, and under **qemu** (Docker Desktop Rosetta is
  OFF) the pytest verifier **segfaults** — a false-negative. Faithful TB-2
  scoring needs Rosetta enabled or native amd64 hardware; a live
  Seon-in-container drive additionally needs an **amd64 Seon overlay**.
- **Froze** `terminal_bench_2` into `evals/datasets.lock` (dev=10 / milestone=25
  / test=54, pinned to a committed corpus manifest); `verify_lock()` clean.

## How TB 2.0 is distributed (exact pins)

| Facet | Value |
|---|---|
| Harness | **Harbor** — `pip install harbor` / `uv tool install harbor` |
| Package pin | **`harbor==0.17.1`** (requires_python `>=3.12`; installs on arm64/py3.13) |
| Dataset id | **`terminal-bench/terminal-bench-2`** (`harbor download …` / `harbor run -d …`) |
| Dataset ref resolved | `terminal-bench/terminal-bench-2@latest` (89 tasks) |
| HF mirror | `harborframework/terminal-bench-2.0` |
| Legacy core | still reachable via `--dataset-name terminal-bench-core --dataset-version 0.1.1` |
| Corpus manifest | `evals/tb2_terminal_bench_2.corpus.json` (sha256 `f22450ac6f71d3625c06160529b7ae9feb5e4b821c94bdfbd0041dd1203b8a12`) — per-task content hash + **amd64 image digest** |
| Example task digest | `fix-git` → harbor `sha256:66be7179…485473` (`oracle-fix-git/job-lock.json`) |

The Harbor registry mechanism: `harbor download NAME` resolves a dataset to task
directories (each `task.toml` + `instruction.md` + `tests/` + `solution/` +
`environment/Dockerfile` + encrypted `protected.tar.gz.enc`) and pins a prebuilt
`[environment].docker_image`. By default (`force_build=false`) Harbor **pulls the
prebuilt image** — the Dockerfile is only used with `--force-build`, and 21/89
Dockerfiles are stubs, so the prebuilt image is authoritative.

## Task counts

- **Total: 89** — 4 easy / 55 medium / 30 hard, 16 categories (matches the
  published 2.0 composition).
- **Native-arm64-runnable: 0.** All 89 `docker_image` manifests are amd64-only
  (`docker manifest inspect --verbose`; full scan in `image-arch-89.tsv`). No
  Dockerfile uses `--platform`; the "arch" mentions in 8 task.tomls are all in
  task DESCRIPTIONS (e.g. "MIPS architecture"), not platform pins.
- **amd64-excluded (from native): 89** — i.e. every task. The constraint is
  stronger than the brief's "amd64-pinned Dockerfiles": the pin lives in the
  prebuilt IMAGE manifests, not the Dockerfiles.
- **amd64-emulation-runnable: all 89** (with the qemu caveat below).

## API drift + what changed

Harbor's agent contract is a different program from tb 0.2.18's:

| | tb 0.2.18 (`tb_agent.py`) | Harbor 0.17.1 (`tb2_agent.py`) |
|---|---|---|
| base class | `terminal_bench.agents.base_agent:BaseAgent` | `harbor.agents.base:BaseAgent` (ABC) |
| method(s) | sync `perform_task(instruction, session, logging_dir)` | async `setup(env)` + async `run(instruction, env, context)` |
| transport | `session.container` docker-py: `put_archive`, `exec_run(argv)` | `BaseEnvironment`: `env.exec(command:str)`, `env.upload_file/upload_dir` |
| result | return `AgentResult` | populate the passed `AgentContext` |
| CLI | `tb run --agent-import-path …` | `harbor run -d … --agent-import-path …` |

Because the base class, method surface, transport, AND result mechanism all
differ (two separate optional packages), one class cannot cleanly subclass both.
Per the decision rule this is the "large drift → focused second adapter" case:
**`src-inspect-ai/src/seon_inspect/tb2_agent.py`** is a harbor `BaseAgent` whose
`setup`/`run` bind Harbor's transport, but the ONE mechanism that matters —
runtime injection, the goal-stated contract, the door body, the run-bounds
wire-REPL, the finding-1 deadline — is IMPORTED VERBATIM from `tb_agent`
(pure helpers) and `bench_common`. `tb_agent.py` behaviour is unchanged.

Adjustments the drift forced (all inside `tb2_agent.py`): argv→shell-string
join (`shlex.join`) for `env.exec`; `put_archive`→`upload_file` + in-container
`tar`; `AgentResult`→`context.metadata` via a harness-neutral `context_metadata`
(reuses tb 0.2.18's SHARED `_BEHAVIOR_MISS_REASONS` set, emits a neutral string
since Harbor has no `FailureMode` enum).

## The one-task proof (oracle smoke, their harness, their oracle)

`harbor run -d terminal-bench/terminal-bench-2 -i terminal-bench/fix-git -a
oracle -n 1 -k 1` (evidence in `oracle-fix-git/`):

| Signal | Value |
|---|---|
| harness | Harbor built the task container (amd64-emulated), 22 s |
| oracle solve | RAN — git recovery + merge succeeded (`agent-oracle.txt`) |
| tests | Harbor copied `tests/` in + executed `test.sh` — apt installed curl, uv installed, pytest launched (`verifier-test-stdout.tail.txt`) |
| verdict | produced — reward `0` (`verifier-reward.txt`, `result.json`) |
| BUT | `qemu: uncaught target signal 11 (Segmentation fault)` — **pytest SIGSEGV under qemu emulation**, NOT a real oracle failure |

**The 2.0 harness mechanism is PROVEN end-to-end on this host** (build → oracle
solve → test copy+exec → verdict). The reward-0 is a **qemu artifact**: Docker
Desktop `UseVirtualizationFrameworkRosetta=False`, so amd64 is emulated by qemu
and the pytest verifier segfaults. This is the honest comparability blocker for
arm64 — a green oracle needs Rosetta (a Docker Desktop restart — deferred to the
owner, it would kill other agents' containers) or native amd64.

## Adapter binding proof (since the live Seon drive is arch-blocked)

The arm64 Seon overlay cannot boot inside an amd64-emulated TB-2 container, so a
live "Seon agent acts in the container" drive is not runnable on this host. The
strongest available substitute — proving `tb2_agent` correctly implements
Harbor's contract:

- **9/9 `test_tb2_agent.py` pass in `tmp/tb2-venv`** (harbor present), including
  harbor-COUPLED `setup`/`run` against REAL `harbor` `BaseAgent` /
  `BaseEnvironment` / `AgentContext` / `ExecResult`: overlay + entrypoint
  uploaded, cluster booted with `SEON_FS_ROOT=/app` writable, run-bounds
  transacted with the finding-1 deadline (`540000 == 0.9 × 600000`), door driven
  as the ROOT agent, `context.metadata` filled with the honest close reason.
- **Harbor's own factory resolves it:** `AgentFactory.create_agent_from_import_
  path("seon_inspect.tb2_agent:SeonAgent", …)` returns a real harbor
  `BaseAgent` (`name()=seon`, `version()=1.0.0`) — the exact `--agent-import-
  path` path a real run uses.

## Freeze entry

`terminal_bench_2` added to `freeze.py` `EXTERNAL_SOURCES` (manifest-corpus
source — no harbor in the pinned `.venv`) and `evals/datasets.lock`:

- dev=10 / milestone=25 / test=54 (total 89, seed `20260702:terminal_bench_2`),
  stratified by category, canary minted.
- pin = harbor version + dataset ref + `corpus_manifest_sha256` (a re-pushed
  environment image → new digest → LOUD diff).
- `arm64` lock block records `native_runnable_n: 0 / 89` as a first-class fact.
- `python -m seon_inspect.freeze` → **verified, no-op** (the other 6 sources
  regenerate byte-identically; only `terminal_bench_2` was added).
- Dev split NOT run (freezing only, per brief).

## pytest

- Pinned `src-inspect-ai/.venv`: **247 passed, 8 skipped** (the tb- and
  harbor-coupled tests correctly skip — neither package is in the pinned env).
- `tmp/tb2-venv` (harbor): **9 passed** (`test_tb2_agent.py`, incl. the coupled
  setup/run).

## Honestly unfinished / owner calls

1. **Live Seon-in-container TB-2 drive is NOT done — arch-blocked.** The overlay
   `seon-runtime-slice3` is arm64; all 89 TB-2 images are amd64. Unblock: build
   an **amd64 Seon overlay** (infra/owner) AND enable Rosetta (or run on amd64
   hardware). Same arch-pairing constraint the SWE-bench arm hit.
2. **Faithful oracle/agent scoring on arm64 needs Rosetta** (qemu segfaults
   pytest). Recommendation: owner enables Docker Desktop Rosetta for a clean
   green-oracle re-verify, or runs the formal TB-2 eval on native amd64.
3. **No scorecard row.** This is a mechanism + freeze unit, not a scored
   capability sample — a `terminal_bench` ledger row enters once a frozen subset
   runs on comparable (amd64/Rosetta) hardware with the amd64 overlay.

## Reproduce

```bash
# TB-2 env (do NOT touch tmp/tb-venv):
python3.13 -m venv tmp/tb2-venv && tmp/tb2-venv/bin/pip install harbor==0.17.1 pytest
tmp/tb2-venv/bin/harbor download terminal-bench/terminal-bench-2 -o tmp/tb2-dataset --overwrite
# oracle smoke (amd64-emulated):
tmp/tb2-venv/bin/harbor run -d terminal-bench/terminal-bench-2 \
  -i terminal-bench/fix-git -a oracle -n 1 -k 1 -y -o tmp/tb2-jobs
# adapter tests (harbor present):
PYTHONPATH=$PWD/src-inspect-ai/src tmp/tb2-venv/bin/python -m pytest \
  src-inspect-ai/tests/test_tb2_agent.py -v
# freeze verify (pinned env):
cd src-inspect-ai && .venv/bin/python -m seon_inspect.freeze
```

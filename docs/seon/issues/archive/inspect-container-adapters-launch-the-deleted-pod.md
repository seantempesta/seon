---
type: issue
status: resolved
severity: blocker
tags: [issue, evaluation, deletion, runtime]
---

# Replace Inspect container adapters that launch the deleted pod

## Problem

The retained `docker/seon-entrypoint` has current readers in Inspect adapters,
but the whole reader chain still launches the deleted two-process writer plus
Bun pod system. It cannot exercise the fresh single-JVM cluster and therefore
cannot supply the repository's required model-evaluation or SWE-bench proof.

The adapter tests preserve the obsolete command shape with mocks. They prove
tar/compose assembly and fabricated readiness/SCI evaluation responses, not that the
injected runtime boots or serves the current agent surface.

## Evidence

- `docker/seon-entrypoint:38-53` starts `seon.db.server`, whose namespace lives
  only at `src-old/seon/db/server.clj`; `:101-125` then launches
  `out/client/main.js`, the deleted `seon.client` Bun pod built from `src-old`.
  It also teaches deleted `config/system.edn`, capability grants, request
  socket, port-file, and `/agents/run` semantics.
- `src-inspect-ai/src/seon_inspect/catalog.py:94-124` still calls eight assessed
  benches "pod door" benches and registers the SWE-bench arm as current.
- `src-inspect-ai/src/seon_inspect/tb_agent.py:72-102,325-350` injects the root
  entrypoint, starts `/seon-entrypoint all`, waits for the pod, and posts to
  port 7890. `tb2_agent.py:49-56,158-198` reuses the same obsolete helpers.
- `src-inspect-ai/src/seon_inspect/swebench_arm.py:84-96,225-285` bind-mounts
  the same entrypoint and emits it as the sample container command; its
  `tasks/swe_bench_seon.py:51-75` wrapper advertises that arm as the live Seon
  task.
- The retained tests assert the stale shape. For example,
  `src-inspect-ai/tests/test_tb_agent.py:68-79` checks only that the tar contains
  the entrypoint, while its adapter proof uses `_FakeExec` to synthesize pod
  readiness, wire-REPL, and evaluation replies (`:118-221`).
  `test_tb2_agent.py:79-164` and `test_swebench_arm.py:36-156` likewise validate
  mocked commands/compose text. No recurring test boots this entrypoint against
  fresh `src/`.
- This is also a live-code-to-quarry edge: an executable under `docker/` invokes
  namespaces that exist only under `src-old/`, contrary to the CLJ-only fresh
  runtime boundary.

## Owner

The Inspect evaluation/runtime packaging boundary. The fresh cluster operator
and `seon.cluster` remain the one runtime owner; benchmark-specific injection
stays downstream.

## Acceptance

- Delete `docker/seon-entrypoint` and every adapter path that requires the
  writer/Bun pod, or replace each still-required benchmark arm with a thin
  binding to a current, versioned fresh-JVM artifact and operator contract.
- No benchmark calls `seon.db.server`, `seon.client`, `/agents/run`, a writer
  request socket, writer port file, or capability-grant environment variable.
- The recurring adapter gate boots the real selected artifact inside the
  target container, reaches a fresh advertised cluster endpoint, executes a
  real agent turn, and retains the resulting database facts and scorer output.
- Mock-only command-shape tests are removed or demoted behind that behavioral
  proof; no test keeps an obsolete entrypoint alive by assertion.

## Resolution

Resolved by `691517def`. The obsolete entrypoint, TB/TB2/SWE-bench adapters,
shared run-bounds helper, task/catalog registrations, frozen dataset rows and
manifest, and mock-only tests were deleted as one reader closure. Historical
run evidence under `evals/runs/` remains archival data, not an executable
reader.

The same cut removed the stale writer-port read-back functions and their live
planning/cluster readers rather than translating them into another transport.
A post-cut search over active evaluation source (excluding historical run and
PRD evidence) found no adapter, entrypoint, frozen-row, or writer-port reader.
Focused verification passed 126 tests across catalog, freeze, source admission,
cluster, and planning.

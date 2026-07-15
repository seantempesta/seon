---
type: research
status: active
tags: [research, agent, flow]
---

# Inspect batch, stream, and cancellation boundary

## Decision

Inspect remains the evaluation, scoring, limit, cancellation-accounting, and
native-log authority. Seon's pod solver bypasses Inspect's model provider and
waits on one JSON response, so Inspect provider batching or streaming cannot
change Seon's internal LLM calls. Batch versus stream is a Seon
runtime/provider configuration measured through the same frozen Inspect task;
no runner or synthetic Inspect tool-call layer is added.

The first repair retains Inspect's own terminal log when an admitted run is
interrupted. Incremental transport and parse-valid stream cutoff remain a later
runtime/HTTP unit; they are not simulated in Python.

## Dependency ledger

- Inspect AI is pinned at
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc`.
- Provider batching: `tests/model/providers/util/test_batch.py`,
  `model/_generate_config.py`, and `model/_providers/util/batch.py`. Together's
  provider treats batching and streaming as mutually exclusive.
- Cancellation and limits: `tests/test_cancellation_logging.py`,
  `tests/test_operator_interrupt.py`, `tests/test_sample_limits.py`,
  `log/_samples.py`, and `_eval/task/run.py`.
- Partial native logs: `tests/log/test_recover_e2e.py`,
  `tests/log/test_streaming_completion.py`, `log/_recorders/buffer/database.py`,
  `log/_recorders/eval.py`, and public `inspect_ai.log.recover_eval_log`.
- Ordered multiple calls: `tests/model/test_parallel_tools.py`,
  `tests/tools/test_call_tools.py`, `tests/model/test_generate_loop.py`, and
  `model/_call_tools.py`.
- Seon owners are `seon_inspect.solver`, `seon_inspect.catalog`, and
  `seon_inspect.source_admission`.

## Grounded constraints

- `BatchConfig` queues provider requests while preserving per-request results;
  it cannot reach the pod's Node model loop through `seon_pod_solver`.
- Inspect's `time_limit`, `working_limit`, token limits, and ordinary solver
  completion already own evaluation semantics. A parse-valid stream cutoff is
  successful solver completion, not operator cancellation.
- `Task.early_stopping` skips future samples or epochs after completed scores;
  it does not cancel in-flight generation.
- Inspect records one assistant message containing multiple tool calls, stable
  call ids, ordered result messages, per-call errors, and event associations.
  Seon forms are not Inspect tools. The analogous evidence is one raw assistant
  reply plus ordered eval rows with unique eval and originating-turn ids; never
  append execution results to model-authored bytes.
- The current blocking `urllib` door and `anyio.to_thread.run_sync` cannot
  promptly disconnect after a parse-valid prefix or expose incremental
  evidence. That is the falsifiable transport gap for the later stream arm.

## Implemented interruption evidence

Before admitted execution, the wrapper snapshots public `list_eval_logs` for
the selected directory. If Inspect propagates a base exception, it retains and
identity-checks newly published logs, then re-raises. If Inspect returns no
accepted logs but published terminal evidence, it retains the bytes and raises
a bounded source-admission error. Normal finalization still requires success.

Focused catalog, admission, and real-log coverage passes 42 tests. A real
OS-SIGINT probe against an actual sleeping Inspect task retained one readable
cancelled `.eval`, one partial sample, and the exact admission map, then rejected
the run. Raising `KeyboardInterrupt` directly inside a solver instead produced
a truthful `started` log; retention does not coerce statuses.

## Remaining focused experiments

1. A fake incremental pod emits one complete valid form then an infinite tail;
   the client disconnects promptly, the pod stops model work, the native log
   succeeds, and the ordinary scorer runs.
2. One reply containing three forms yields exactly three unique, ordered eval
   rows associated with the originating turn, without results echoed into the
   assistant completion.
3. Identical frozen batch and stream arms differ only by database runtime
   policy; compare semantic projections, not timestamped `.eval` bytes.
4. An Inspect early-stopping arm skips only future epochs after a completed
   score and records its native summary without relabeling running work.

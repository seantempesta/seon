---
type: research
status: active
tags: [agent, research]
---

# Inspect native control contracts

## Decision

Inspect AI remains the only experiment, cancellation, logging, and scoring
harness. Reuse its interruption, cancelled-sample retention, wall-clock, and
multi-call laws. Do not copy its tool executor into Seon and do not create a
second runner. Inspect has no syntax-aware first-Clojure-form cutoff; that is
Seon behavior measured through the existing native solver and `.eval` logs.

The dependency order is:

1. deterministic fake-door trajectory and cutoff falsifiers;
2. an owned run cancellation/result handshake;
3. retained partial turn/eval evidence;
4. strict database deadline, door timeout, then Inspect wall-clock ordering;
5. isolated database-derived mode/config arms; and
6. batch/stream plus multi-form local-model experiments.

## Dependency ledger

- Inspect AI source: `reference-code/inspect-ai`, selected revision
  `05322696a0f784ec399ef6abbafd3d2a250ea9cc` in
  `src-inspect-ai/evaluation-sources.lock.json`.
- Seon Inspect boundary: `src-inspect-ai/src/seon_inspect/solver.py` and the
  admitted `catalog.run_native_task` path.
- Seon live door: `src/seon/web/serve.cljs`.
- Seon run policy: `src/seon/agent/run.cljs`, `src/seon/agent/loop.cljs`, and
  database-derived configuration in `src/seon/config.cljs` and
  `src/seon/agent/ctx.cljs`.
- Seon stream cutoff: `src/seon/repl/internal.cljc`,
  `src/seon/ai/openai_compat.cljs`, and `src/seon/agent/turn.cljs`.
- Seon batch execution: `src/seon/eval.cljs`.

## Operator cutoff and current-state scoring

The closest exact Inspect law is
`tests/test_operator_interrupt.py::test_operator_interrupt_with_scorer_error`
plus the active-interrupt path in `src/inspect_ai/_eval/task/run.py`.
`sample_active().interrupt("score")` cancels the sample scope, retains the
latest `TaskState`, records an operator `EvalSampleLimit`, proceeds to scoring,
and is neither counted as an ordinary error nor retried.

The current Seon solver awaits blocking
`anyio.to_thread.run_sync(pod_run)`. The live door can close on its own timeout
and return final evidence, but the caller has no owned cancellation handle.
The shortest falsifier is a fake `/agents/run` that publishes one turn and then
blocks: interrupt the active Inspect sample and require prompt return, an
operator limit, one terminal native log, and retained turn/eval evidence. The
expected current failure is that the thread/HTTP request does not cooperate
soon enough to retrieve pod partials.

## Cancelled-sample evidence

The source laws are:

- `tests/test_cancellation_logging.py::test_fail_on_error_logs_cancelled_samples`;
- `test_all_concurrent_samples_accounted_for`;
- `test_keyboard_interrupt_logs_cancelled_samples`;
- the shielded cancellation finalizer in `src/inspect_ai/_eval/task/run.py`;
  and
- task-local isolation in `tests/test_task_cancel.py` and
  `src/inspect_ai/_eval/run.py::_run_task`.

Every started or cancelled sample remains accounted for with its error,
messages, transcript events, and completion time. A task-local cancellation
does not tear down siblings, and external cancellation is distinct from retry.
Seon's door already returns partial `turn_evidence` and `eval_evidence` on an
honest bounded close; the solver retains them only after an HTTP response.
First prove a fake door returning one completed turn plus one in-progress or
error turn. Then add the interruption handshake. Solo proof precedes concurrent
sibling behavior.

## Batch versus stream

No pinned Inspect test implements Seon's first-complete-Clojure-form cutoff.
The relevant harness laws are
`tests/test_sample_limits.py::{test_time_limit,test_turn_limit}` and
`tests/log/test_streaming_completion.py::test_streaming_completion_eval_output_matches_materialized`.
They establish explicit terminal limit events and byte-equivalent final sample
content across streaming and materialized recorder paths. Provider-specific
Sagemaker or Google streaming tests are not this boundary.

Run one frozen ordinary tool task against isolated, ownership-fenced database
snapshots that differ only in `:batch` versus `:stream`. Preserve model, seed,
source, membership, and starting database facts. Record outcome, latency,
estimated/provider tokens, fabricated-result tail rate, forms/evals per turn,
raw reply bytes, and as-of-resolved mode/config identity. Source admission and
local cutoff tests exist; formal live comparison waits for the lease and
partial-evidence boundary.

## Outcome-oriented limits

Use `tests/test_sample_limits.py` coverage for time limits, solver timeouts, and
combined solver/scorer timeout behavior. Treat
`tests/solver/test_basic_agent.py::test_basic_agent_defaults_to_50_message_limit`
as a negative precedent: Inspect message/turn/token limits cannot observe
Seon's internal pod model calls because the native solver never calls Inspect
`generate`.

Seon's database owns turn/form/deadline policy. Inspect owns only a looser
outer wall-clock backstop. Prove the order with a scripted never-complete run:
the database deadline closes first with truthful partial evidence, the door
budget cuts second, and Inspect's time limit records only if both lower layers
fail. The required order is:

```text
database deadline < door timeout < Inspect time limit
```

This is the route from the current database-owned 30-minute deadline toward
hours-long outcome-oriented runs without imposing an arbitrary Inspect message
limit.

## Multi-form and future parallel calls

The strongest Inspect laws are in
`tests/model/test_parallel_tools.py`:

- serial batches preserve declared order;
- explicitly parallel batches run concurrently;
- mixed serial calls form barriers;
- one recoverable error does not erase siblings;
- per-call cancellation is isolated; and
- every pending/final call event has a distinct identity.

`tests/tools/test_call_tools.py::test_tool_event_message_id_for_multiple_calls`
adds the one-turn, many-call message identity law. Seon must not adopt Inspect's
executor. `seon.eval/eval-batch!` already executes parsed entries serially in
order and continues after a partial failure; the missing fact is durable
per-turn execution position.

The shortest Seon-native experiment is one scripted reply containing three
forms: success, failure, success. Require one turn, three ordered eval
identities, proof that the third form ran, and results appearing in the next
prompt rather than fabricated after the forms. Keep execution serial.
Parallelism belongs only to future functions that explicitly declare safe
parallel semantics.

## Bounded partial output

`tests/tools/test_call_tools.py::test_sandbox_timeout_partial_output_returned_as_tool_result`
establishes that a timeout retains bounded partial output plus an error class,
not a traceback and not discarded output. A later Seon trajectory should emit
a short prefix, fail or time out, then prove the prefix and classification are
visible on the next turn while a later batch form still runs.

The current external eval projection does not expose enough bounded result and
error detail to score this completely. The hard diagnostic-cap issue and the
durable multi-form trajectory should settle those owners before this row is a
formal capability result.

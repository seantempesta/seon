---
type: research
status: active
tags: [research, agent, database, component]
---

# Inspect turn evidence retention — 2026-07-15

## Dependency ledger

- Inspect AI `0.3.246`; the maintained source dependency is
  `reference-code/inspect-ai/`, selected by `src-inspect-ai/pyproject.toml`.
  `TaskState.metadata` is the per-sample mutable metadata owner in
  `solver/_task_state.py`; `_eval/task/run.py` copies that map into the native
  `EvalSample` record.
- `inspect_evals` `0.14.3`, BFCL task version `5-B`; the existing
  `seon_inspect.catalog.run_bench` and `seon_pod_solver` remain the only task
  and live-pod bridge.
- Seon turn capture remains always-on in `seon.agent.turn`: each turn stores a
  complete rendered database coordinate plus prompt and reply blob refs.
  `seon.agent.debug/turn` is the existing reconstruction owner.
- `seon.web.serve` owns `POST /agents/run`; `seon_inspect.solver._record_result`
  owns transfer of its response into Inspect metadata. No second capture or
  evaluation system is introduced.

## Failure and acceptance

The frozen Qwen 3.5 2B baseline retained aggregate pod metadata but discarded
the identities and bytes of the turns that produced it. Later database cleanup
therefore made a scored `:no-forms` sample impossible to diagnose.

The acceptance boundary is lossless and database-native: one `/agents/run`
response carries its final complete database coordinate and a stable ordered
turn bundle containing turn id, status, stored rendered coordinate, exact
prompt, raw reply when present, token estimates, and bounded error data. The
Python solver copies those values unchanged into native Inspect sample
metadata.

## Focused and live evidence

The Python solver test proves exact preservation of nested coordinates and
turn evidence; four focused tests pass. The focused `seon.web.serve-test`
checkpoint compiles the current CLJS boundary and passes one test with eight
assertions.

A clean ACME cluster at `http://127.0.0.1:7994` then proved both rails:

- with the configured DiffusionGemma worker unavailable, one turn retained its
  23,627-token prompt, rendered coordinate, and bounded transport error while
  correctly omitting a reply;
- after a live database transact selected the already-running local
  `Qwen/Qwen3.5-2B` OpenAI-compatible server, a three-turn request retained all
  three 23,485-token prompts and all three raw 255-token replies before closing
  `:no-forms`.

The latter replies were identical degenerate digit loops. That is actionable
model/server evidence, not a parser inference.

Finally, one native BFCL `multiple_0` smoke is preserved under
`evals/runs/2026-07-15-inspect-turn-evidence-qwen-smoke/`. Its `.eval` sample
metadata contains the final database coordinate and four complete turn
records. The first raw reply is a 36-token JSON call with the wrong function
identity; the following three replies are present but empty. The run closes
`:no-forms` with zero accuracy, but the failure is now fully inspectable from
the immutable artifact without the live pod.

## Additional runtime finding

Before the clean reset, ACME's stale hot-reloaded pod crashed intentionally
with `seon.agent.ctx.run_policy is not a function` while its replica was also
rejecting replay pages. Resetting the disposable cluster rebuilt the same
source into a ready watcher, writer, and pod and removed the fault. This is
evidence for [[../../../seon/issues/hot-reload-schema-import-can-partially-fail]],
not a reason to weaken the core-fault crash policy.

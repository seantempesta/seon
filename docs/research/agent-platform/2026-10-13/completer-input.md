---
title: "The model call is a handle member"
type: research
status: complete; lane completer-input decision
basis: HEAD 1d538ec20
---

# The model call is a handle member, not a redefined Var

Date: 2026-10-13. Lane completer-input. Source: `with-redefs-inventory.md` (this directory), row `ai/complete`.

## What exists

`seon.turn/call-turn` made the one paid call as `(ai/complete projection request)`, a direct Var call. Tests replaced the
model by `with-redefs [ai/complete …]`: 48 targets in 8 files, 39 of them in `test/seon/cluster/turn_test.clj`.
The turn already receives every process-local port through one value, the cluster handle
`:seon.turn.loop/cluster` (connection, SCI context, wake, stream and completion channels, and the test-supplied
`:seon.turn.loop/await-part` function). Boot builds it once (`seon.cluster/loop-handle`, assoc'd in
`seon.cluster/start-flows!`), every agent handle is a `merge` of it (`seon.cluster.agent/branch-handle` and the
started entry), and fixtures build it through `seon.test-support/cluster-handle`.

## Decision

The model call is the handle member `:seon.turn.loop/complete`, schema `:seon.turn.loop/complete` =
`[:=> [:cat :seon.schema/projection :seon.ai/request] :seon.ai/completion]` (exactly `seon.ai/complete`'s contract),
required on `:seon.turn.loop/cluster`. `call-turn` calls `((:seon.turn.loop/complete cluster) projection request)`.

- **Production default, at the one producer.** `start-flows!` sets it to the Var `#'seon.ai/complete`, not its value,
  so a reload or re-instrumentation of `seon.ai` reaches the next turn exactly as the direct call did. Agent handles
  inherit it by `merge`, so live chains (DeepSeek through `seon.ai/targets`) take the same path unchanged.
- **Boot input.** A boot request may name `:seon.turn.loop/complete` (declared optional on `:seon.boot/start-request`,
  `:seon.boot/overrides` and `:seon.boot/config`; `resolve-bootstrap` already keeps it, as it keeps
  `:seon.store/destroy?`). Full-boot tests (`armed_test`, `program_restart_test`, `eval_instrumentation_test`,
  `concurrency_independence_test`) pass their stub there.
- **Handle tests** pass the stub as a value: `(assoc cluster :seon.turn.loop/complete f)`, the shape
  `concurrency_independence_test` already uses for `:seon.turn.loop/await-part`. `test-support/cluster-handle`
  defaults it to `#'seon.ai/complete`, so `with-provider!` tests still run the real call against a local peer.

## Rejected

- A `seon.env/environment` member: the environment is the evaluation's bindings, reaching SCI; the model call is
  the turn proc's effect, not something evaluated code reads. More reach for no gain.
- The existing `:seon.ai/send!` HTTP leaf on the request: it is the right seam for `seon.ai` tests, but turn tests
  would have to speak HTTP bodies to say "the model answers this text".
- A `get` with a default at the call site: the member would be optional with a second default beside the producer's.

## Cost

One map lookup per turn; no new mechanism. The conversion is scripted (rewrite-clj: each
`(with-redefs [ai/complete f …] body)` becomes `(let [h (assoc h :seon.turn.loop/complete f)] (with-redefs [rest] body))`
where `h` is the handle the body passes to `drive-agent!`/`turn/turn`/`turn/step`/`agent/start-flow!`, then nested
redundant lets merged); boot sites by hand. Proof: `seon.test-census-test/the-model-call-is-an-input-never-a-redefinition`
counts `with-redefs` bindings of `ai/complete`/`seon.ai/complete` in `test` and `script`: 48 on HEAD, 0 after.

---
type: landing
status: rows 2 and 11 landed; row 5 partly landed (the default is removed, the cluster name is still guessed); row 6 recorded only; install pending RESET NEEDED
lane: fix-sci-eval-3
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/cross-cutting-astra-2026-09-24.md
---

# Lane fix-sci-eval-3 — 2026-09-24

SCI now keeps its acquisition attributes on the projection's own holder, and it
identifies its refusal-recording target by `seon.db/connection-identity`. An
unreadable cluster config now refuses contract arming by name instead of arming
under a guessed `:record` policy.

## Broken first

- **Row 5 is only partly done.** SCI still finds its cluster through the
  `[:find ?cluster . …]` database guess. The context cannot supply the name yet.
  Base contexts carry no environment (live: all four of default's
  `base-context-cache` entries return `env/of` nil). `projection-state`
  (`src/seon/sci/eval.clj:174-184`) also names the invented cluster
  `"current-src"` for about 118 callers. The fix spans `instrument_test.clj`, every
  `projection-state` caller and this file. Filed as
  `docs/seon/issues/sci-arming-guesses-the-cluster-from-a-database-row.md`, with the
  route.
- **`seon.db/connection-identity` cannot return its typed released result.**
  Dereferencing a released connection throws `:connection-has-been-released`
  before the owner's check runs (live repro). SCI now propagates the declared
  value, but that branch is unreachable until db.clj is fixed. Filed as
  `docs/seon/issues/connection-identity-throws-on-a-released-connection.md`.
- **`seon.sci.eval-test/one-unloadable-row-cannot-prevent-cold-acquisition` is red on HEAD.**
  - Its first failure was the fixture: the agent row lacked the required
    `:seon.agent/branch`.
  - `0b0b1dcb4` writes the agent and namespace through the canonical helpers.
  - It now fails at `eval_test.clj:1597`. Lazy acquisition no longer refuses the
    bad row during `acquire!` (live: `:acquisition-refusals []`), so no fact exists
    to pull.
  - That premise is stale and was not changed here. The row-11 assertion added to
    this test therefore never runs.
- **Row 11 has no running regression.** The identity differs only for a remote
  writer backend, and no fixture can open one.
  - Live proof in a scratch root: every `refusal-recording-cache` key's target was
    `#:datahike{:connection-id [#uuid "bc59770c…" :agent-…]}`, the dependency's
    identity.
- **`seon.instrument-test` is red on HEAD.** It had 26 fails and 6 errors in 53
  executed tests, all duration or pre-existing.
  - `a-sovereign-sci-fork-acquires-its-own-recorder` fails identically with and
    without this lane's row-5 diff. This was checked by swapping HEAD's `eval.clj`
    into the scratch JVM.
  - `sci-installed-contracts-…-both-dials` passes 41 assertions and fails only its
    duration (7.7 s against 5 s).
- **Acquisition attributes are 3.3× slower per call.**
  - Warm `acquisition-attributes`: **5.6 µs before** (default, old LRU) and
    **18.7 µs after** (scratch, projection holder).
  - Allocation is unchanged: 6.5 KB and 6.6 KB per call.
  - The extra cost is the armed contract on `schema/projection-cache-value`. It is
    called a few times per acquisition, and one acquisition measured 379 ms, so
    about 40 µs is under 0.02 %. This is over the 20 % relative rule, not the 50 ms
    one, and is left for root's ruling.
- **The scratch root's `require seon.sci.eval :reload` took 2.1 s.** It re-runs
  `(schema.edn/load! {})` at namespace top level (`eval.clj:172`). This is not
  from this lane.
- **RESET NEEDED.** Default (pid 90471) runs archive `ce7384682`, and hook
  publication is off, so none of these commits is live there.

## Changes

1. `bc86def40`, row 2. `acquisition-attributes` uses
   `schema/projection-cache-value projection ::acquisition-attributes`. The
   fingerprint-keyed `acquisition-attribute-cache` is deleted.
   - Regression: `seon.sci.acquisition-attributes-test`. It builds `:probe/Aa` and
     `:probe/BB` dial populations, which share a fingerprint, and asserts that each
     keeps its own attributes.
   - Live before (default): the collision handed `b` the attributes of `a`
     (`:b-has-Aa true, :b-has-BB false`). This proves the wrong acquired set, which
     the report had left UNVERIFIED.
2. `1b10216c6`, row 11. The private `connection-identity` and the unused
   `datahike.store` alias are deleted. `record-refusals-once!` targets
   `db/connection-identity`, and a non-identity result for held custody becomes
   `::acquisition-recording-error`. `agent.clj:888` already throws on that.
3. `39ca6521a`, row 5 subset. `instrumentation-config` throws the typed config
   refusal when `:seon.config/on-core-error` is absent. That is a required dial,
   verified live via `required-dial-attributes`. The acquisition catch rethrows
   refusals carrying `:seon.config/error-key` instead of blaming the row.
   - Regression: `seon.sci.fault-policy-test`. It uses the member branch's value
     as of the transaction before its first config row.
   - Live before (default, old code), the same form refused with
     `Record-mode SCI instrumentation requires an acquired fault recorder.`
     (member `:seon.flow/commit-fault!`), which was the `:record` default at work.
4. `0b0b1dcb4` is the fixture fix described above.

## Row 6 record (not implemented; render.clj is not this lane's)

- Evaluation picks its projection through `evaluation-projection` (`eval.clj:683-690`):
  context, then the request, then the database's carried projection.
- `shown-result` (`eval.clj:3025-3060`) renders through `render/request-projection`
  (`render.clj:93-116`): the database first, then the request, then the context.
- **Handoff.** `evaluate` already holds `projection` (`eval.clj:3388`, `:3554`). It
  should put that projection on the request it passes to `shown-result` as
  `:seon.schema/projection`. `render/request-projection` should then put a supplied
  `:seon.schema/projection` first and delete its context fallback. Both consumers
  then read one acquired value, at O(1).
- The one render.clj change is its first `or` arm order. The eval.clj change is one
  `assoc` in `shown-result`'s caller. Proof: one evaluation whose context,
  request and database projections differ, asserting that the contract and the
  rendered declaration come from the supplied projection.

## Evidence

- Proof ran in a scratch root: a `git archive HEAD` plus the diff, with
  `reference-code` and `.cpcache` linked. Cold boot took **269.6 s** to ready,
  5.1 GB RSS. This exceeds ten seconds and is the existing `from-zero-boot-takes-minutes`.
- `bin/test-check --root … default`, on the final code:
  - `seon.sci.acquisition-attributes-test`: executed 1, pass 7, 7515 ms run.
  - `seon.sci.fault-policy-test`: executed 1, pass 5, 1916 ms run. The first
    fresh-store version took 8–11 s in its fixture, so it was replaced by the
    as-of value.
  - `seon.sci.entry-gate-test`: executed 5, pass 33.
  - `seon.sci.fork-isolation-test`: executed 1, pass 3.
- `instrumentation-config` before: 0.73 ms mean over 20 calls on default.
- The scratch root was stopped (`process-exit? true`) and deleted without following
  its symlinks. Its start log is kept at `tmp/fix-sci-eval-3-start.log`. The
  scratch namespace `tmp.fix-sci-eval-3` was removed from default.
- Probe residue on default: one LRU entry, for fingerprint `-264990134`, in the old
  `acquisition-attribute-cache`. It is harmless and vanishes on reset.

## Net lines

- `src/seon/sci/eval.clj`: +38 / −42, net −4.
- Tests: +74 / −5 (two new files, and `eval_test.clj` +13 / −5).
- Two issue files and this note.

Released paths: `src/seon/sci/eval.clj`, `test/seon/sci/acquisition_attributes_test.clj`,
`test/seon/sci/fault_policy_test.clj`, `test/seon/sci/eval_test.clj`.

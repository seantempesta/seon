---
type: landing
status: current
created: 2026-09-24
scope: Lane fault-no-llm-wake. The shipped config leaves :seon.config.error/escalate-to absent, so an unowned core fault is recorded and shown but wakes no root LLM turn.
---
# Lane fault-no-llm-wake — landing (2026-09-24)

Owned row named first: `config/default.edn` `:seon.config.error/escalate-to`.
Schema resource `resources/seon/schemas/seon.config.error.edn` is unchanged:
the dial is already declared `:seon.config/optional true`, and absence is its
documented "record, no escalation message" meaning.

## Broken or not done first

1. **Three tests I do not own assert the old shipped default and will go red.**
   They need a one-line update each, from their owner or root:
   - `test/seon/fault_test.clj:76-79`: `"root"` is the effective
     `escalate-to` and `(contains? woken "root")`. Now the dial is absent and
     root is not woken. Either assert no root wake, or give that world a
     manifest with `escalate-to "root"`.
   - `test/seon/cluster/armed_test.clj:166`: `(= "root" (:seon.config.error/escalate-to handle))`.
   - `test/seon/config_test.clj:459-463`: `explicit-absence-is-a-decision-never-a-stored-value`
     expects the baseline to be `"root"`. The absent-vs-baseline `not=` at
     :467 also goes false. That test needs a different optional dial, or an
     overlay naming "root".
   Others may be affected: any test that expects a root escalation message
   from a seeded cluster without naming `escalate-to`. `config_application_test`
   and `schedule_test` name it explicitly and are unaffected.
2. **A stale docstring in a file I don't own.** `src/seon/fault.clj:186-188`
   says "(root by default)". That is no longer true.
3. **RESET NEEDED is not claimed, but default keeps waking root until it adopts.**
   The live cluster's config row still stores `escalate-to "root"`. Root has
   to reload `seon.config` (the `defaults` constant is compiled at load) and
   run `bin/seon config apply` so that the row reconciles the retraction.
   Until then, default still wakes root on faults.

## Design

- What already exists: a declared optional dial,
  `:seon.config.error/escalate-to`. Every recorder reads it from the
  cluster's effective config and carries it on the `commit-tx` request:
  fault.clj:89, sci/eval.clj:1962, turn.clj:3333, schedule.clj:585.
  `commit-call` addresses the escalation arm only when the dial is present.
- The change: the shipped decision is now `:seon.config/absent`, which is the
  one explicit retraction form. No new key, no new code path.
- What is unchanged:
  - The fault row, the occurrence and the blob.
  - Visibility through status `open-faults`, the MCP error window and the
    problems page.
  - Steward routing: `steward-present` is still the first arm.
  - `:your-run` to an attributed agent.
- One side effect: with no escalation owner, a steward's fault message has
  `:seon.message/from` = the steward itself (`message-tx` sender fallback,
  error.clj). It stays an inside wake.
- Rejected alternative: a new boolean dial. It would duplicate what the
  optional dial's absence already means.
- Why this fixes the evidence: probed on default, 16 root→root fault
  messages. 14 were from unattributed occurrences, which is the escalation
  arm. The rest were signatures that were also attributed, where root's own
  run still gets `:your-run` by design.

## Changes

- `config/default.edn`: row → `:seon.config/absent`, with the ruling and the
  way to re-enable it (a manifest naming "root").
- `src/seon/error.clj`: comment only, on the escalation arm.
- `test/seon/error_test.clj`: new
  `a-core-fault-wakes-root-only-when-escalation-is-configured`.
  - With the shipped config read through `config/effective`, the dial is
    absent, one occurrence is recorded and no message is written.
  - With `escalate-to "root"`, root gets one message.
- Net: src +4/−1 (comment), config +13/−11, test +22.

## Evidence

- Read-only probe of `(config/compile-manifest {:seon.boot/cluster-name
  "probe"})` against the edited file. The effective map has no
  `escalate-to`; compiling took 399 ms. The loaded
  `seon.config/defaults` constant still says "root", which is stale until
  reload.
- clj-kondo on error.clj and error_test.clj: 0 errors, no warnings in the
  new lines.
- No test run (LOAD RULE).

## Root runs

- `bin/test-check default --test seon.error-test/a-core-fault-wakes-root-only-when-escalation-is-configured`
- `bin/test-check default --ns seon.error-test`
- `bin/test-check default --ns seon.fault-test` (expected red at :79 until updated)
- `bin/test-check default --ns seon.config-test` (expected red in explicit-absence test)
- `bin/test-check default --ns seon.cluster.armed-test` (expected red at :166)
- Adopt: reload `seon.config`, `bin/seon config apply`, then check
  `(find (seon.config/effective (seon.db/db (seon.cluster.boot/connection "default")) "default") :seon.config.error/escalate-to)` is nil.

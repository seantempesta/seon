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

1. **Root's first run of the regression failed: 1 pass, 4 fail, 5,892 ms
   against the 5,000 ms bound.** It counted every fact and message on the
   test branch, which inherits default's 16 live fault rows and messages
   (class 6). Follow-up commit:
   - Each arm now records under its own `:seon.error/operation`, so it has its
     own signature, and counts only that signature's occurrences and messages.
     A read-only probe showed the operation enters the signature and the
     message does not.
   - Both arms share one `with-db`. The first version had two, and each
     one forks and seeds a branch.
   - Recording a fault took 6 ms in a read-only probe on default. The branch
     fixture is the rest of the time. If the rerun still goes over 5 s, the
     cost is `with-db`'s fork plus seed, not the fault path. Then time it
     separately rather than raising the bound.
   The three stale tests are updated in the same commit (files free per the
   ledger):
   - `test/seon/fault_test.clj`. The record-mode test now asserts that the
     shipped config has no `escalate-to` and that nobody is woken. The
     panic-mode test's manifest names `"root"`, and it asserts root is woken.
   - `test/seon/cluster/armed_test.clj:166`. The handle has no `escalate-to`.
   - `test/seon/config_test.clj`
     `explicit-absence-is-a-decision-never-a-stored-value`. It now exercises
     `:seon.config.run/max-episode-runs`, which is optional with a shipped
     100. A probe showed that explicit absence removes it from both the
     effective map and the row.
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
- `bin/test-check default --ns seon.fault-test`
- `bin/test-check default --test seon.config-test/explicit-absence-is-a-decision-never-a-stored-value`
- `bin/test-check default --ns seon.cluster.armed-test` (48 s pool member; its metadata already justifies this)
- `bin/test-check default --ns seon.error-test`
- Adopt: reload `seon.config`, `bin/seon config apply`, then check
  `(find (seon.config/effective (seon.db/db (seon.cluster.boot/connection "default")) "default") :seon.config.error/escalate-to)` is nil.

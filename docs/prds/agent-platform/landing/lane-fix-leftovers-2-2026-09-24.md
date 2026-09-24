---
type: landing
lane: fix-leftovers-2
created: 2026-09-24
status: item 1 installed on default (pid 70372, checkout, publication on); item 2 not committed, because the baseline is stale far beyond two attributes and the test cannot run
---

# Lane fix-leftovers-2 (2026-09-24)

## Broken first

- **`canonical-population-native-parity` cannot reach its assertions.** Run
  `6cc4510025a5` raised an error while materializing the historical
  `:seon.bridge.parity/initial-baseline`:
  - The error is `schema/register! :seon.flow/callback-result … may not be nilable`.
  - The oracle's historical form is `[:or :nil :map …]`, and the current refusal of
    nilable value schemas rejects it.
  - This red comes from the in-flight fix-leftovers-1 edits to `seon.flow.edn` and
    `internal.cljc`, and fix-leftovers-1 also owns `test/seon/schema/datahike_test.clj`.
    That lane must settle how the historical oracle is materialized.
- **`test/seon/schema/datahike_parity.edn` is stale well beyond the two new
  attributes,** so I did not commit a two-attribute edit. Compared with default's
  projection (`projection-from-database`):
  - 322 forms have drifted.
  - The live population has 1215 attributes; the baseline lists 1191.
  - 4 listed attributes are missing and 28 are unexpected. The two
    `:seon.test.failure/message-*` attributes are 2 of those 28.
  - The live population also includes `seon.fn/*` flags, `seon.program/*` and
    `seon.source/*`.

  The test asserts the full forms map, the input digest and the ordered native
  declarations. Adding two entries leaves it red. The fix is to regenerate the current
  (non-initial) sections from the handed projection, with the drift explained. Do that
  after fix-leftovers-1 lands, so the in-flight flow edits are not captured. The native
  declarations of the two new attributes are
  `{:db/ident :seon.test.failure/message-blob :db/valueType :db.type/string :db/cardinality :db.cardinality/one}`
  and
  `{:db/ident :seon.test.failure/message-size :db/valueType :db.type/long :db/cardinality :db.cardinality/one}`.
- **`seon.issue-settlement-test` is red for foreign reasons** (run `8e368b2a541d`, 2
  pass / 1 fail / 2 errors, 18.3 s):
  - duration: 9.75 s against the 5 s bound (the earlier landing measured 31 s);
  - `Isolation requires a fresh owned branch.` from `seon.cluster.agent:848`, raised
    early in `issue-settlement-runs-tests-and-derives-completion` (1 pass recorded), so
    the converted assertions at `:162` and `:224` were **not reached**;
  - `started-issue-tests-retain-historical-authority`: the fixture was refused because
    `:seon.program/definition-digest` is missing on `my.agents.retention`.

## Item 1: issue status reads the failure body from reports

Commit `e75295fee` (+5/−3):
- In `seon.issue/status-text`, a test row that carries `:seon.test.failure/reports`
  renders through `seon.test/failure-message`, which joins `failure-text`. A row
  without reports keeps its refusal or unknown message.
- This deviates from `tmp/fix-failure-message-bound/issue.patch`. That patch would call
  `failure-message` on a refusal row whose counts are absent, and the armed
  `:seon.test.runner/captured-result` contract refuses such a row.
- `issue_settlement_test.clj:162` and `:224` now read `(tests/failure-message …)`.

Live proof on default, JVM mode: `(#'seon.issue/status-text view)`, given a red row
whose reports hold one fail report, answered
`"…a/b: failed\nfail\nboom-claim\nexpected: 1\nactual: 2"` in 3 ms.

A hook save during this lane was refused by the save gate on a foreign red:
`seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault`,
where default's two open faults leak into the observed list. The loaded Var
nevertheless carries the edit, as the probe above shows.

## Verification limits

The issue-settlement assertions that were converted were not exercised, because of
the foreign errors above. The parity test was not run to its assertions. RESET NEEDED:
none.

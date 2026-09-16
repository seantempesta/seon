---
type: research
status: current
created: 2026-09-16
tags: [research, steward, testing, fixture, absence-as-health]
---

# One fixture write path — `seon.test-support/transacted!` (2026-09-16)

Lane: fixture-write-helper. Issue:
[fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour](../../../seon/issues/fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour.md).
Commit: `477cb615c`.

## What landed

`seon.test-support/transacted!` (`test/seon/test_support.clj:224`) is the one
fixture write path. It transacts through `seon.db/transact!` with an explicit
connection and returns the transaction report only when the report is a map
carrying `:db-after` and no `:seon.error/kind`. Otherwise it throws an
`ex-info` whose `ex-data` IS the flat error (plus the authored
`:seon.db/tx-data`) and whose message carries write admission's own
diagnostic and the offending row. That matches the namespace's existing
`checked-fixture-result` (`test/seon/test_support.clj:205`), which is what
`with-database`'s `::extra-schema` and `seed-cluster!` already do, so a
fixture refusal is one failure shape, not two.

Contract (its neighbours' style):

```clojure
{:malli/schema
 [:=> [:cat :seon.db/connection :seon.store/transaction]
  :seon.db/transaction-report]}
```

`offending-fixture-row` (`test/seon/test_support.clj:212`) names the row from
the refusal's `:seon.db/path` leading index into the authored transaction data.

**Probe finding that changed the design.** The first version named
`:seon.db/entity-form` as the offending row. A live probe on the canonical
fixture showed that key carries the entity SCHEMA the row was read against,
not the row:

```
Offending row: [:map {:seon.db/attributes true, :description "The agent's one
owned plan component."} [:seon.agent/id :seon.agent/id] ...]
```

A refusal carries `:seon.db/attribute`, `:seon.db/path`, `:seon.db/offending`
(the value), `:seon.db/entity-form` (the schema) and
`:seon.db/registered-candidates`; the authored row itself is only recoverable
from the transaction data at the path's index. The regression caught this on
its first in-process run — 8 pass / 1 fail — and it now asserts the exact
`Offending row 0: {…}` bytes.

## What was replaced

| File | Before | Now |
|---|---|---|
| `test/seon/render/transcript_test.clj` | local `defn- transacted!` at old line 297 plus ten bare `db/transact!` seeds | all 18 call sites are `support/transacted!`; the local helper is deleted |
| `test/seon/reconcile_test.clj:59` | local `transact-as!` with its own `ex-info` | `transact-as!` is a three-line wrapper over `test-support/transacted!`; the two fixtures at `:29`, `:45`, `:48` no longer discard |
| `test/seon/render/web_debug_test.clj` | five write-and-discard seeds (`:41`, `:478`, `:505`, `:553`, `:670`) | `support/transacted!` |
| `test/seon/config_test.clj` | two write-and-discard seeds (`:459`, `:479`) | `test-support/transacted!` |

Sites that already read the report (`(is (:db-after …))`, `(is (nil?
(:seon.error/kind …)))`) were left alone: they are not the class, and widening
would have churned diffs for nothing.

**`seon.custody-stability-test` was examined and NOT changed.** Its three
`seon.db/transact!` occurrences (`:291`, `:296`, `:301`) are SCI *source
strings* evaluated inside an agent context, not fixture calls with a
connection in hand, and the test already asserts the written
`:seon.message/id` set on both connections. A connection-taking helper does
not apply there. Reported rather than forced.

## In-process verdicts

pid 45917, `default`, realized fixture base. Each run:
`(seon.test/run (#'seon.test/resolve-test 'ns/name) (seon.operator/connection
"default") {:seon.test.run/provenance (seon.test.runner/provenance (seon.db/db
c)) :seon.test/remaining-ms 100000})` on a daemon thread, test namespaces
reloaded through `#'seon.test/with-test-loader` first.

| Run | pass / fail / error |
|---|---|
| `seon.test-support-test/a-refused-fixture-write-is-reported-at-the-write` (first version) | 8 / 1 / 0 — the offending-row assertion, which found the `:seon.db/entity-form` mistake |
| same, after the fix | 9 / 0 / 0 (one runner-reported drift entry, below) |
| 16 `seon.render.transcript-test` tests (all but the `^{:seon.test/long}` one) | 0 fail; two runner drift entries (`the-history-query-bounds-what-the-transcript-pulls`, `one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`), which arm/disarm instrumentation in the shared JVM |
| 5 `seon.render.web-debug-test` tests | 0 fail after the fixture repair below; two were red on the first pass |
| 2 `seon.config-test` tests | 2 / 0 / 0 and 3 / 0 / 0 |
| 9 `seon.reconcile-test` tests | 0 fail, 0 error across all nine |

Full first-pass tally: 32 tests run one at a time, 0 assertion failures except
the two named below; every reported `error` was the runner's worker-global
drift detector, never an assertion.

## What the choke point immediately found

Two `seon.render.web-debug-test` fixtures were inert and nobody knew, because
they discarded the answer:

```
Fixture write was refused at the write: seon.db/transact! refused transaction
data at [0 :seon.cluster/config]: expected the required key
:seon.cluster/config … Offending row 0: #:seon.cluster{:name
"reverse-render-fixture"}.
```

`reverse-declarations-receive-the-actual-relationship-value`
(`test/seon/render/web_debug_test.clj:508`) and
`agent-identity-groups-scalars-and-keeps-declared-components` (`:556`) each
wrote a bare `{:seon.cluster/name …}` row, which write admission refuses
because `:seon.cluster/config` is required. The seed had been doing nothing
since the admission change; both tests passed anyway, which is exactly the
class. They now seed through the production path,
`support/seed-cluster!`, and are green (3 / 0 / 0 each).

The runner's drift detector reports
`:drift-added ["seon.test-support/effective-config"
"seon.test-support/transacted!"]` for in-process runs. That is this lane
reloading a test namespace into the live JVM, which arms a contract wrapper
the worker snapshot did not have; a cold gate worker builds the snapshot after
loading, so it does not drift there. Named here rather than hidden.

## Verification boundary

- Proof surface is in-process only. The cold gate is the final proof; the
  request is `tmp/orchestrator/gate-requests/fixture-write-helper.txt`.
- `seon.render.transcript-test`'s `^{:seon.test/long}` test was not run
  in-process.
- **`current-src` publication is refused for a foreign reason.** Every hook
  publication this lane queued came back
  `:seon.fn/index-refused` with one finding, in a PROTECTED path:
  `test/seon/test_failure_facts_test.clj:170` —
  `"seon.problems/problems is called with 1 arg but expects 2"`. That also
  blocks MCP `jvm` evaluations whose form statically references a test
  namespace, which is why this lane's probes resolve symbols at runtime. Not
  this lane's slice; reported, not repaired.

## The remaining half

The detector (issue note item 2) — a test function calling `seon.db/transact!`
without consuming the result becomes a generated issue — belongs to the issue
generator's detector contract
([issue-generator-2026-09-16.md](issue-generator-2026-09-16.md)) and was
deliberately not built here. Until it lands, the class can return in any
namespace nobody triages.

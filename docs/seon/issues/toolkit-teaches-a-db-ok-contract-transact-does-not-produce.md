---
type: issue
status: open
tags: [toolkit, database, agent, issue]
severity: blocker
---

# The agent toolkit teaches a `:seon.db/ok?` contract `transact!` does not produce

## Problem

`seon.db/transact!` returns either the datahike transaction report (bare
`:db-before` / `:db-after` / `:tx-data` / `:tempids` / `:tx-meta` keys plus
`:seon.capability/op-id`) or a flat `:seon.error/*` map. It has never returned
`:seon.db/ok?` or `:seon.db/tempids` on the current code. Three agent-facing
sites still teach and consume that missing contract, so the flagship worked
example of the whole toolkit returns the wrong value on its success path.

## Evidence (read at HEAD, branch `codex/runtime-reliability-refactor`)

Producer:

- `src/seon/db.cljc:414-427` — `submit-transaction!` builds the report with
  `(select-keys response [:db-before :db-after :tx-data :tempids :tx-meta])`
  plus `:seon.capability/op-id`. No `ok?`, and `:tempids` is a BARE key.
- `src/seon/db.cljc:98-111` — the registered `::transaction-report` is
  `{:closed true}` over exactly those bare keys; `::transact-response` is
  `[:or ::transaction-report ::error]`.
- No file under `src/seon/db/` mentions `ok?` at all.

Consumers that expect the missing keys:

- `src/my/kb.cljc:197-200` — `(let [{::db/keys [ok? tempids] :as env}
  (await (db/transact! ...))] (if ok? {::id (get tempids "finding")} env))`.
  `::db/keys` destructures `:seon.db/ok?` and `:seon.db/tempids`; both are
  always absent, so `remember` ALWAYS returns the raw transaction report and
  NEVER `{:my.kb/id <eid>}`. Its own docstring (`kb.cljc:171`) promises
  `; returns «map: :my.kb/id 1234»`. Its registered output
  `::remember-response [:or ::remembered :seon.db/transact-response]`
  admits the wrong branch, so instrumentation cannot catch it.
- `src/my/kb.cljc:119` — agent-facing teaching comment: "ALWAYS read it: an
  eval can succeed yet `:seon.db/ok? false`."
- `src/my/canvas.cljc:206` — agent-facing docstring: "Inspect `:seon.db/ok?`
  before claiming the visible update worked."

Why no test catches it:

- `test/my/kb_test.cljs:259-261` stubs the transact fn to return
  `{:seon.db/ok? true :seon.db/tempids {"finding" 101}}` — a shape the real
  producer cannot emit — and then asserts `(= {:my.kb/id 101} result)`. The
  test pins a fiction and passes.
- `test/my/kb_test.cljs:226` does the same with `{:seon.db/ok? true}`.

Adjacent (same class, host-internal, not agent-facing):
`src/seon/host/context.clj:1513` synthesizes a THIRD shape,
`{:seon.db/ok? true :db-after {...}}`, for host-internal receipt recording.

## Owner

`seon.db` owns the transaction response; `my.kb` and `my.canvas` own their
agent-facing text. The fix is at the consumers plus the fake-based tests, not
by adding `ok?` back to the database response — the flat
`:seon.error/message` presence check (`seon.db/error-value?`,
`src/seon/db.cljc:231`) is the discriminator the rest of `src/` already uses.

Related: `docs/seon/issues/arbitrary-database-results-collide-with-error-shape.md`
(the flat-error-by-presence discriminator is unsafe for arbitrary read
results; a transaction report is a fixed shape and is not affected).

## Acceptance

1. `my.kb/remember` returns `{:my.kb/id <eid>}` against a REAL writer
   transaction, proven by a live cluster JVM eval, not a stubbed transact fn.
2. `test/my/kb_test.cljs` no longer fabricates a transact response shape; the
   fake returns exactly what `submit-transaction!` builds, or the test runs
   against a real in-memory writer.
3. `rg -n ':seon.db/ok\?' src/my/ src/seon/agent/` returns nothing.
4. The rule that generalizes it: a toolkit function's success branch must be
   reachable by some value the producer can actually return — a test whose
   fake is the only thing that can produce the success branch is not
   coverage.

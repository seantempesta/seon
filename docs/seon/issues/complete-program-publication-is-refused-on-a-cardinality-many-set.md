---
type: issue
status: open
severity: blocker
tags: [issue, admission, program-graph, publication, class/p1]
---

# A complete program publication is refused on a cardinality-many set, and that blocks every lane's in-process verification

Observed 2026-09-16 20:15Z on the development checkout, by the composable-history
(S11) lane, while trying to restore in-process `seon.test/run`.

## What happens

`bin/seon init --dev default` (a COMPLETE publication, no `--changed`) compiles
the program population and is then refused by write admission:

```
● current-src: program population compiled: 39458 entities, 19504 identities, 35835 keyword facts
:error datahike.writer :datahike/write-rejected {:kind :transaction/validation-rejected}
✗ Program indexing transaction was refused.

seon.db/transact! refused transaction data at
  [4501 :seon.fn/keywords #{:seon.agent/id :seon.db/db}]:
  expected a set, got a keyword.
  Fix: Supply a set at [4501 :seon.fn/keywords #{:seon.agent/id :seon.db/db}].
  Entity: #:seon.fn{:sym "my.agent/identity"}.
```

The reported value IS a set (`#{:seon.agent/id :seon.db/db}`); the refusal says
"got a keyword". `:seon.fn/keywords` is cardinality-many, so the transaction
data supplies the set and the writer expands it into one datom per member —
and the validator appears to check EACH EXPANDED MEMBER against the attribute's
whole-value schema, which declares a set. The message then prints the original
set as the offending path while naming the member's type. The datom is
well-formed; the check is reading a cardinality-many attribute as if it were
cardinality-one.

The whole transaction aborts, so nothing is published and `default` keeps the
program it already had. Nothing was destroyed.

## Why it is a blocker and not a cleanup

Incremental hook publications (`init --dev default --changed PATH`) still
succeed, so the tree looks healthy — but the facts a complete publication
carries are missing from `default`. The one measured consequence today:

```
(seon.db/q '[:find [?s ...] :where [?f :seon.fn/destroys _] [?f :seon.fn/sym ?s]] db)
;; => 0 rows
```

`src/seon/operator.clj:311` declares `:seon.fn/destroys` and the indexer reads
it (`src/seon/fn.clj:592`, `:688`), but no published row carries it. So
`seon.test/destroyers` (`src/seon/test.clj:188-218`) returns its typed unknown —
correctly, by design — and EVERY in-process `seon.test/run` on `default` is
refused with:

```
No function in this program declares :seon.fn/destroys, so an in-process run
cannot tell whether a test deletes a filesystem path it did not create.
Republish the program (bin/seon init --dev default), or declare the attribute
in the owner's own metadata at its definition.
```

The remedy the refusal names is exactly the operation that is refused above.
Every lane following the REPL rule loses its in-process verification loop until
one of the two is fixed.

## Where to look

- The admission seam: `seon.db/write-map-error` and the transaction function it
  validates in (`src/seon/db.clj`) — currently being changed by the F2
  write-admission lane, so this may already be in that lane's hands.
- The attribute: `:seon.fn/keywords` in `resources/seon/schemas/seon.fn.edn`
  (cardinality-many) and its indexer write (`src/seon/fn.clj`).
- The consumer that turns it into a lane-wide stop:
  `seon.test/destroyers` / `seon.test/run` (`src/seon/test.clj:188`, `:412`).

## The check this is an instance of

The typed unknown in `destroyers` is right — it reports absence as unknown, not
as health (AGENTS.md §2.4). The defect is upstream: a validator that reads a
cardinality-many value one member at a time against the whole-value schema will
refuse every correct set, and it refuses the WHOLE transaction, so the failure
is total and silent to anything that only runs incremental publications.

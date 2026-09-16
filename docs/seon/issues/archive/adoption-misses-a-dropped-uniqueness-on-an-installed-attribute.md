---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, source, schema, class/absence-as-health]
---

# Adoption misses a dropped uniqueness on an installed attribute

## Resolution (2026-09-16, steward-platform lane)

`declaration-changes` (`src/seon/cluster.clj`) now compares the UNION of both
declarations' storage facets — `(= (dissoc declaration :db/ident) (dissoc
installed :db/ident))` — so a facet the current declaration no longer carries
refuses through the existing `incompatible-declaration-message` (RESET NEEDED)
instead of reading as compatible. Datahike's `:schema` entry holds exactly the
declaration datoms transacted for that attribute plus its `:db/ident`
(`reference-code/datahike/src/datahike/db/transaction.cljc:90`), so the two
maps are directly comparable.

Live evidence (reforked `default`, 2026-09-16): the whole canonical population
compares converged (0 declaration changes) under the union comparison, and the
same call against a database whose installed `:seon.test/reach-digest` carries
`:db/unique :db.unique/identity` refuses with `:seon.boot/refused`, naming the
attribute and the refork. Regression:
`seon.cluster-test/a-dropped-storage-facet-refuses-reopening-the-branch-in-place`.

Observed by the orchestrator on 2026-09-16 (UTC 04:10) on the `default`
development cluster (pid 7595, forked 2026-09-16T01:36Z).

`:seon.test/reach-digest` is declared as
`[:and {:seon.db/identity false} :seon.source/digest]`
(`resources/seon/schemas/seon.test.edn:2`) and the bridge derives it today
with no `:db/unique` (`seon.schema.datahike/malli->datahike-attr` → valueType
string, cardinality one). The cluster's installed declaration still carries
`:db/unique :db.unique/identity` from an earlier publication. Adoption accepted
every later publication as compatible, so the branch kept the stale
uniqueness. Sibling declarations with the same wrapper
(`:seon.test.run/program-digest`, `:seon.test.run/immutable`) are installed
without uniqueness, which dates the reach-digest install to a bridge that
still inherited the referenced form's identity.

Consequence: any test whose reach digest changes carries two identities
(`[:seon.test/sym …]` and `[:seon.test/reach-digest …]`) and
`seon.fn/index-tempids` refuses the WHOLE publication with
"Program indexing found multiple entity identities."
(`src/seon/fn.clj:1842`). Every `bin/seon init --dev default --changed …`
fails; the edit hook logs "Publication did not finish within its declared
bound"; the gate's result recording reports
`:seon.fresh-operator/live-prepl-unavailable`. The message names a program
indexing conflict, not the schema incompatibility that caused it.

## Cause

`declaration-changes` (`src/seon/cluster.clj:876`) compares the installed
declaration only on the keys the NEW declaration carries:

```clojure
(= (dissoc declaration :db/ident)
   (select-keys installed (keys (dissoc declaration :db/ident))))
```

A facet the new declaration no longer has (`:db/unique`, `:db/isComponent`,
`:db/tupleTypes`) is never compared, so a dropped facet reads as compatible.
This is the recurring class: a check that reads absence of signal as health.

## Fix

Compare on the union of both declarations' storage facets (every key of
either map except `:db/ident`); a facet present in the installed declaration
and absent from the current one is a non-accretive change and refuses with the
existing `incompatible-declaration-message` (RESET NEEDED), which is what the
lane rule in AGENTS.md §0.8 already expects. One regression in
`test/seon/cluster_test.clj` (or the source test): an installed attribute with
`:db/unique :db.unique/identity` and a current declaration without it must
refuse reopening in place.

`src/seon/cluster.clj` was under concurrent uncommitted edit (the steward
session's fixture-base fixer) when this was found, so the fix is handed to
that session rather than edited here. Recovery for `default` now: refork
(`bin/seon stop default; bin/seon init default --force; bin/seon start;
bin/seon init --dev default`).

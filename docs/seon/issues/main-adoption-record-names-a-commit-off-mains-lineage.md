---
type: issue
status: open
severity: high
created: 2026-10-20
tags: [issue, adoption, acquisition, cost, runtime]
---

# Main's adoption record names a commit off main's lineage, so main pays a branch overlay on every request

Found by lane runtime-fix3 (root's question after defn-perf), 2026-10-20, default running `ac55beffa`, read-only JVM
queries as root.

`seon.sci.eval/loaded-commit-id` reads the adoption record on the entity carrying `:seon.cluster/name`: it names
commit `6ad683c7-54db-5ae6-a178-0e97d65ba3e8`, whose newest transaction is from 2026-10-18 04:59:45. That commit is
not an ancestor of main: transaction `536870923` is 2026-10-18T04:59:45.101Z on the loaded commit and 2026-10-18T04:53:45.517Z on main, so the
lineages diverged by then; e.g.
`my.program/similar` is entity 71499 (tx `536870923`) on the loaded commit and entity 71908 (tx `536870935`, its only
assertion in main's history) on main, with the same `:seon.program/definition-digest`.

`seon.db/datoms-diff` of the loaded commit against main over `seon.sci.eval/plan-attributes`: `:seon.fn/sym` 230/230,
`:seon.test/sym` 87/87, `:seon.ns/name` 11/17, `:seon.program/definition-digest` 887/891,
`:seon.schema.admission/source` 451/455, `:seon.fn/host-bound?` 233/233 (only-loaded/only-main), 1,397 entities: the
same names and digests under other entity ids. So every acquisition on main runs the overlay diff over 1,397 entities
(`seon.sci.eval/plan-identities`, now two pulls since `fcd9949b5`) and the digest comparison, which a restart onto HEAD
should leave at zero.

Cause to find: the adoption record was written for a publication on another lineage (a branch or a store state main
was later rebuilt from) and the restart onto HEAD did not rewrite it because no definition changed
(`docs/seon/issues/adoption-refuses-a-publication-that-changes-no-definition.md` is the nearest class). The record
should name a commit on main's lineage whose program equals main's: the commit that adopted main's own rows.

Reproduction (JVM mode, root):

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      loaded (#'seon.sci.eval/loaded-program database)]
  (into {} (for [a @#'seon.sci.eval/plan-attributes]
             [a (mapv count (seon.db/datoms-diff loaded database :aevt [a]))])))
```

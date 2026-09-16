# Adoption adopts a monotonic index addition instead of forcing a refork

2026-09-16, monotonic-index lane. Subject:
[`adoption-refuses-a-monotonic-index-addition-datahike-supports`](../../../seon/issues/adoption-refuses-a-monotonic-index-addition-datahike-supports.md).

## The defect

`seon.cluster/declaration-changes` compared the installed attribute map with
the declared one using `=` and called EVERY difference incompatible:

```
Cluster `default` predates the incompatible schema change for `:seon.issue/agent`
and cannot be reopened in place.
```

Adding `:db/index` to `:seon.issue/agent` (so it could become a listened wake
attribute) is accretion, and the vendored fork applies it. Whole-map equality
is not the dependency's rule — it is our own, and it is stricter than the store.

## The dependency's own rule

`datahike.schema/find-invalid-schema-updates`
(`reference-code/datahike/src/datahike/schema.cljc:257`) is the acceptance
authority the transactor consults from `check-schema-update`
(`reference-code/datahike/src/datahike/db/transaction.cljc:930`). It accepts,
on an already-installed attribute:

| property | accepted change | source |
|---|---|---|
| `:db/index` | `nil → true` only; removal unsupported | `schema.cljc:277`, re-checked per datom at `db/transaction.cljc:105` |
| `:db/doc`, `:db/noHistory`, `:db/isComponent` | any update | `schema.cljc:285` |
| `:db/cardinality` | `one → many` unless the attribute is `:db/unique` | `schema.cljc:264` |
| `:db/unique` | only when the attribute was already unique and cardinality-one | `schema.cljc:271` |
| everything else (`:db/valueType`, tuple types…) | refused | `schema.cljc:299` |

## Live probe: the AVET backfill is real

Run in the `default` JVM (jvm mode), 2026-09-16, against a scratch in-memory
Datahike store — five `:probe/name` datoms written BEFORE the index existed:

```clojure
{:avet-before 0
 :avet-after  5
 :update      :transacted
 :schema      {:db/ident :probe/name :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one :db/index true}
 :query       [[5]]}   ; [?e :probe/name "n3"] answers afterwards
```

So transacting the declaration is the whole adoption: the transactor backfills
AVET atomically before publishing the resulting database value.

## The change

`src/seon/cluster.clj`:

- `declaration-property-changes` — the union-keyset facet diff (unchanged
  semantics; it keeps the dropped-facet class from
  `adoption-misses-a-dropped-uniqueness-on-an-installed-attribute` dead),
  now returned as data rather than collapsed into one boolean.
- `accretive-property-change?` — the per-property rule above, with the
  `reference-code` `file:line` for each clause in its docstring.
- `declaration-changes` — an attribute whose every differing facet is
  accretive RETURNS ITS DECLARATION, which the existing callers
  (`accrete-schema-population!` at `src/seon/cluster.clj:1387`, the development
  adoption at `src/seon/cluster.clj:1994`) already transact through
  `seon.db/transact!`. No new write path.
- `incompatible-declaration-message` — names the property and both values
  (`… changed :db/valueType from :db.type/long to :db.type/string`) instead of
  `predates the incompatible schema change`.

**Narrower than Datahike on purpose:** a DROP is never accretive here, even
where `find-invalid-schema-updates` would accept it. Transacting the current
declaration cannot retract a facet the branch still carries, so adopting a drop
would leave the stale facet installed — exactly the class the union compare
(a3cbcd9a8) was added to kill. `:db/unique` therefore stays refusing.

## Verification

In-process, on the reforked `default` JVM (pid 95853), through
`seon.test`'s own loader (`(with-test-loader #(require 'seon.cluster-test :reload))`
then `(seon.test/run (resolve-test 'seon.cluster-test/<t>) (seon.operator/connection "default"))`):

```
an-added-index-adopts-in-place-instead-of-forcing-a-refork      pass 9  fail 0  error 0
an-incompatible-declaration-refuses-naming-the-changed-property pass 6  fail 0  error 0
a-dropped-storage-facet-refuses-reopening-the-branch-in-place   pass 7  fail 0  error 0   (existing, stays green)
schema-row-convergence-uses-the-stores-own-semantics            pass 3  fail 1  error 0   (NOT MINE — see below)
```

The one red is `cluster_test.clj:40`,
`(= [] (changes database (schema/registered-schemas)))` — that is
`schema-row-changes`, a function this lane did not touch, on a canonical
database that re-transacts schema rows it should have found converged. It
failed identically on the first in-process run of this session and is
reported to the orchestrator as a pre-existing red in the same namespace,
not a consequence of this change.

Live proof: cluster `monotonic` in the isolated root `tmp/monotonic-index-root`, forked
from a `current-src` published WITHOUT the index, then republished and adopted
with `:seon.db/index true` added to `:seon.issue/title`:

```
● current-src: development schema declarations      <- the seam that used to refuse
● current-src: development program reconciliation
```

and, queried in that cluster's own JVM afterwards:

```clojure
(get (:schema (seon.db/db conn)) :seon.issue/title)
;; => {:db/ident :seon.issue/title :db/valueType :db.type/string
;;     :db/cardinality :db.cardinality/one :db/index true}

{:title-count 1668 :avet-title-datoms 1668 :sample-query [[43659]]}
```

1668 title datoms written BEFORE the index existed are in AVET afterwards, and
the value-bound query answers. The branch was never reforked. The scratch root
was downed and deleted after the proof; `default` was used only for read probes
and in-process test runs, never restarted or reforked by this lane.

## Verification boundary

Only `seon.cluster-test` was run in process. The batched gate request is
`tmp/orchestrator/gate-requests/monotonic-index.txt`
(`bin/test --paths src/seon/cluster.clj test/seon/cluster_test.clj -- seon.cluster-test`
plus `bin/test --platform`). No test JVM was launched by this lane.

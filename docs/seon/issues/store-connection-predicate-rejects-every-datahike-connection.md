---
type: issue
status: open
severity: blocker
tags: [issue, database, schema]
---

# Ground the store connection predicate in Datahike's own connection type

## Problem

`seon.cluster.store/connection?` is false for every value `datahike.api/connect`
can return, so `:seon.store/store` can never validate. The predicate asks for
`(:conn (meta value))`, but a live Datahike connection's metadata is the
metadata of its wrapped atom, which `conn-from-db` constructs as
`{:listeners <atom>}` and nothing later adds `:conn` to. The predicate's
docstring ("True for a live Datahike connection") also promises a liveness
check that `(:conn (meta …))` does not perform — a released connection would
pass it just as a live one would, if either could pass at all.

This blocks the B1 store rung: the implementation is complete and 6 of the 7
sealed tests pass, including the real child-JVM cross-process flock fence and
the genesis-window repair. The one failure is this predicate.

## Evidence

`reference-code/datahike/src/datahike/connector.cljc:38-45` — `Connection` is a
`deftype` whose `IMeta` implementation returns `(meta wrapped-atom)`;
`:104` — the only `with-meta`/`:meta` site in the namespace,
`(Connection. (atom db :meta {:listeners (atom {})}))`. `Connection` is not
`IObj`, so a caller cannot attach metadata to it either.

Live, against the implemented store (`clojure -M:test`):

```text
connection? => false
meta of conn => {:listeners #object[clojure.lang.Atom 0xff4aa57 {:status :ready, :val {}}]}
explain => {:schema :seon.store/store, ...
            :errors ({:path [0 :seon.store/connection],
                      :in [:seon.store/connection],
                      :schema [:fn #object[seon.cluster.store$connection_QMARK_]],
                      :value #datahike/Connection[#uuid "a6e6c579-…" :db]})}
```

`bin/test` (whole gate, commit `81341dee4`): `Ran 42 tests containing 181
assertions. 1 failures, 0 errors.` — the single failure is
`store_test.clj:84`, this predicate.

## Owner

`seon.cluster.store/connection?`. The predicate is inside the sealed contract
layer, so the orchestrator owns the correction; the implementation lane
reported it rather than satisfying it by stamping `:conn` into Datahike's
metadata atom, which would make the predicate pass without making it mean
anything.

## Acceptance

`connection?` is true for a connection returned by `d/connect` and false after
`d/release`, grounded in Datahike's own type rather than an invented metadata
key — `datahike.connector/connection?` plus the released check its own
`::connection` spec uses (`connector.cljc:104`,
`(not= @(:wrapped-atom %) :released)`). `bin/test seon.cluster.store-test` is
then fully green with no change to the store implementation.

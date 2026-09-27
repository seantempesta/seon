# Datahike retype: a total value order across a type change (2026-09-27)

Lane `fix-datahike-retype-opus`. Defect found by `fix-schema-inplace` (a390b04b9),
repro `tmp/fix-schema-inplace-opus/datahike-retype-temporal-buffer-repro.clj`.

## What was wrong

`datahike.datom/compare-value` called `compare` on two values of any type. An
attribute reinstalled with another `:db/valueType` (retract/purge its values,
`:db/retractEntity` it, reinstall — `seon.cluster/declaration-changes`) shares the
index prefix `(a, e)` with its old-typed datoms, so the temporal comparators
compare a `String` against a `Long` and `Util.compare` throws `ClassCastException`.
Only `cmp-nil` (via `safe-compare`) caught that; the `*-quick` comparators the
indices use do not.

Two carriers of an old-typed datom, both measured on a scratch store in default's
JVM (no default cluster write):

- **Retract, then retype**: history keeps `[e a "one" tx false]`; the first long
  write throws from `Leaf.add` on any history store (memory or file, any
  config). The purge path is not special.
- **Purge, then retype**: the purge removes the string datoms, but a stored
  `Branch`'s diff buffer (`:index-config :diff-buf-size`) keeps the removal keyed
  by the string datom; the next insert into that buffer throws from
  `Branch.depositKV` (`PersistentTreeMap` of buffered ops). It needs a **file**
  store whose temporal root is a Branch (reproduced at 300 datoms with
  `{:branching-factor 32 :diff-buf-size 8}` and at 20,000 with default's
  `{4096 256}`); memory stores never reproduced. Branching (`d/branch!`) is not a
  factor: the unbranched file store throws identically. The original lane saw
  "fresh stores do not" because its scratch stores lacked default's
  `:index-config`.

Not a node-sharing defect: persistent-sorted-set behaves correctly given a total
comparator.

## Fix

Fork `reference-code/datahike` commit `fe1d4e26` (pushed to
`seantempesta/datahike` main), a port of the mixed-type arm of upstream
`515786f6` ("Values with no order of their own get one", #955):

- `src/datahike/datom.cljc:265` `class-order` — orders two values of different
  types by class name (cljs `class-name` now returns a string, `:258`).
- `src/datahike/datom.cljc:271` `compare-value` — `(compare v1 v2)` inside a
  `catch ClassCastException`: different classes → `class-order`, same class →
  rethrow. cljs arm catches the same way (`:311-315`).
- `safe-compare` (`:317`) reuses `class-order`.

Guarantee: two values of different types in one attribute's index order by type
name instead of throwing; values of one type order exactly as before (the
ordinary path is unchanged, so no stored index moves). Known limit, shared with
upstream: a numeric type whose class name sorts after `java.lang.String`
(`java.math.BigDecimal`) mixed with longs and strings in one attribute is not
transitive.

Not ported from #955: arrays nested in tuples, hash order for non-comparable
values, the `collision-refusal?` path (fork is 167 upstream commits behind at
merge-base `85c40aee`; a full sync is separate work).

## Evidence

- Fork regression `datahike.test.purge-test/test-retype-attribute-keeps-a-total-value-order`
  (both carriers, file store): fixed 3 pass; pre-fix comparator in the same JVM
  errors with the ClassCastException.
- Fork namespaces purge, datom, schema, index, array, tuples, upsert: 57 tests,
  369 assertions, 0 fail, 0 error, 4.2 s.
- Seon classpath (`clojure -M` at the repo root, fork via `deps.edn` local/root):
  the repro sequence on a `d/branch!` member of a history file store with
  default's config and 20,000 padding datoms → `2` tx-datoms, value `42`, write
  25 ms. Script: `tmp/fix-datahike-retype-opus/seon-classpath-proof.clj`.
- Cost on the comparator hot path: 200,000 AVET inserts
  (`cmp-datoms-avet-quick`, median of 15 after warmup) — strings 99.4/104.3 ms
  fixed vs 105.2/105.3 ms before; longs 88.5/89.6 vs 95.8/89.7 ms. No measurable
  cost. Script: `tmp/fix-datahike-retype-opus/bench.clj`.

## Not yet proven

Default's JVM still runs the pre-fix `datahike.datom`; this lane did not reload
it. After root's restart, run
`seon.schema-in-place-test/a-non-accretive-change-replaces-the-attribute-and-drops-its-data`
and the original repro form on a member branch.

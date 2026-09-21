---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [schema, sci, projection, own-nothing-global, class]
---

# A committed storable declaration is dropped from the cluster's live projection

## What was measured

Installing the Juniper scenario on a canonical fixture cluster
(`seon.loop-proof-test/running-fixture-settles-its-seeded-wake`, in process on
`default` pid 88182, 2026-09-17) leaves the cluster's IN-MEMORY projection
missing two of the four keys the agent just declared, while the database says
all four are declared:

```clojure
#:seon.schema{:missing-rows #{}
              :missing-projection-keys #{:example/order :example/order-row}
              :carried-buckets #:example{:order #{}, :order-row #{}}
              :database-derived-missing #{}}
```

Read that carefully:

- `:missing-rows #{}` — all four `:seon.schema/key` rows are committed facts.
- `:database-derived-missing #{}` — `seon.schema/projection-from-database` at
  the cluster's own basis has all four.
- `:carried-buckets` — `:example/order` and `:example/order-row` appear in NO
  bucket of the live projection the ctx carries: not `forms`, not the
  registry, nowhere.

The two that survive (`:example/amount`, `:example/customer`) are the plain
shapes. The two that vanish are exactly the STORABLE declarations: the
`:seon.db/identity` attribute and the `:seon.db/attributes` map.

It persists after the installation completes — after `seed!`, after
`clear-history!`, and after the opening system turn.

## Why it matters

This is the write storm's disease one layer up: the writer compiles its
declaration population from a projection that CONTRADICTS the cluster's own
facts. `seon.turn/row-tx` derives at the writer from the live projection, so
any later write touching a dropped key compiles a population nobody declared.

It also explains the `:example/*` "leak"
(`context-blocks-fixture-leaks-example-schema-keys-into-the-live-cluster`):
the keys were never leaked. They were dropped at install and re-derived later
from the database, and the runner's drift detector saw that re-derivation
mid-run as keys "added" by the test — then restored them away again.

## Resolved 2026-09-17 — the cause was neither seam named below

`seon.turn/row-tx` canonicalizes every reader row through
`seon.program/declaration-row`, which RE-PRINTS a schema row's
`:seon.schema/form` (`src/seon/program.cljc:956`), so a storable declaration is
committed as `[:string #:seon.db{:identity true}]` while the request row still
holds `[:string {:seon.db/identity true}]`. `seon.sci.eval/committed-row?`
compared those two PRINTINGS as bytes and answered `false` for the cluster's own
committed declaration, so `src/seon/turn.clj:4767` skipped it and
`install-evaluated-rows!` never saw it. Plain shapes print identically and
survived — which is exactly why only the storable declarations vanished.

`seon.sci.eval/same-declaration-source?` now compares the VALUE (byte-equal, or
EDN-equal when both sides read as data); `committed-row?` and `install-row!`'s
source-mismatch throw share it. The accumulation and the basis advance the
sections below suspected were both measured correct and are unchanged.

Regressions: `seon.sci.eval-test/a-storable-declarations-committed-row-is-recognised-by-its-value`
(the seam) and the projection-equality pair inside
`seon.loop-proof-test/running-fixture-settles-its-seeded-wake` (the class, on the
canonical harness). Evidence and live proof:
[live-projection-follows-declarations-2026-09-17](../../prds/steward-platform/research/live-projection-follows-declarations-2026-09-17.md).

## Where to look

`src/seon/sci/eval.clj`:

- `install-row!`'s `:seon.schema/key` branch (~line 826) answers
  `(or prepared-projection (schema/projection-from-database db projection))`,
  and `advance-context-projection!` (~line 636) then REPLACES the ctx's
  projection with it. A `db` value older than the commit yields a projection
  without the key just committed, and the replacement is unconditional at an
  equal basis (`seon.env/advance-projection!` compares `<=`).
- The per-evaluation advance at ~line 2464 accumulates correctly, so the loss
  happens at or after the post-commit install.

The verdict wanted here is the owner law: no seam may act on a mirror its
authority will re-decide. Either the install advances with a projection
derived at the committed basis, or it does not replace the accumulated one.

## Reproduction

In process on a live cluster's JVM:

```clojure
(seon.test/run (#'seon.test/resolve-test
                'seon.loop-proof-test/running-fixture-settles-its-seeded-wake)
               (seon.operator/connection "default")
               {:seon.test.run/provenance (seon.test.runner/provenance
                                           (seon.db/db connection))
                :seon.test/remaining-ms 100000})
```

with `seon.context-blocks-fixture/declared!` checking the CTX projection
rather than the database-derived one. On `HEAD` that check reads the
authority, so the reproduction needs the one-line change back to
`(get-in @(get (:seon.sci.eval/ctx handle) seon.env/state-carrier)
          [:seon.schema/projection :seon.schema.projection/forms])`.

Related: `a-failing-turn-write-refires-without-bound-and-fills-the-store`,
`context-blocks-fixture-leaks-example-schema-keys-into-the-live-cluster`.

---
date: 2026-09-17
lane: live-projection
issue: docs/seon/issues/a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection.md
---

# The live projection dropped storable declarations because two printings of one form were compared as bytes

## The premise the issue rested on is refuted; the defect is real

The issue named the suspect seam as `install-row!`'s `:seon.schema/key` branch
answering `(or prepared-projection (schema/projection-from-database db projection))`
and `advance-context-projection!` replacing an accumulated projection at an equal
basis. **Measured live, both are innocent.** The accumulation in
`seon.sci.eval/install-evaluated-rows!` is correct, and the advance is correct.

The dropped declarations never reached the accumulation at all.

`seon.turn/row-tx` (`src/seon/turn.clj:1414`) runs every reader row through
`seon.program/declaration-row`, which for a `:seon.schema/key` row REBUILDS the
row from `seon.schema/canonical-schema-rows` and RE-PRINTS `:seon.schema/form`
(`src/seon/program.cljc:956`). A form carrying a namespaced property map is
therefore COMMITTED as

    [:string #:seon.db{:identity true}]

while the row the evaluation still holds reads

    [:string {:seon.db/identity true}]

`seon.sci.eval/committed-row?` compared those two strings with `=`. Exactly the
STORABLE declarations carry such a map — `{:seon.db/identity true}` and
`{:seon.db/attributes true}` — so `committed-row?` answered **false** for the
cluster's own committed declaration, `seon.turn.clj:4767`'s `keep` skipped it,
and the live projection never learned about it. A plain shape (`:int`) prints
identically on both sides and survived. That is the whole mechanism.

## Measured on `default` (pid 88182), before the change

A three-form turn on `default`'s juniper agent declaring one storable identity
attribute, one plain shape and one entity map:

| observation | value |
|---|---|
| `:seon.schema/key` rows committed | all three |
| projection derived at that basis | all three |
| live ctx projection | **only `:probe6/amount`** |
| `env/advance-projection!` calls on the evaluation ctx | accumulated all three correctly |
| rows reaching `install-evaluated-rows!` | `[:probe6/amount]` |
| `committed-row?` per row (traced) | `:probe6/tag` **false**, `:probe6/amount` true, `:probe6/row` **false** |
| committed vs requested form bytes | `"[:string #:seon.db{:identity true}]"` vs `"[:string {:seon.db/identity true}]"` |

Reproduced identically five times (`:probe/*` … `:probe6/*`).

## The change (`src/seon/sci/eval.clj`)

`same-declaration-source?` is the ONE comparison both install seams make:
byte-equal, or — when both sides read as EDN — value-equal. A source the reader
cannot read as data is its own value, so an unreadable source compares by exactly
the bytes it compared before. `committed-row?` and `install-row!`'s
`::install-source-mismatch` throw both use it.

Canonicalizing the request row instead (calling `program/declaration-row` at the
install) was measured and rejected: **466 ms per schema row** on `default`'s
population, because it rebuilds canonical rows for the whole registry.

## In-process runs (pid 88182, `default`, one at a time on a daemon thread, `:seon.test/remaining-ms 100000`)

| run | result |
|---|---|
| `seon.sci.eval-test/a-storable-declarations-committed-row-is-recognised-by-its-value` | 3 pass, 0 fail, 0 error |
| `seon.loop-proof-test/running-fixture-settles-its-seeded-wake` | **32 pass, 0 fail, 0 error** |
| `seon.test-support-test/a-synthetic-schema-registration-leaves-the-registry-byte-identical` | 7, 0, 0 |
| `seon.test-support-test/a-test-body-inherits-no-ambient-cluster-custody` | 3, 0, 0 |

No run after the change reported `Live cluster schema registry changed and was
restored` or `::snapshot-schema-keys` drift.

**A false red cost three runs**: `seon.context-blocks-fixture` was STALE in this
JVM (it carried the new `declared!` but an `install-running!` compiled before it,
so `install-running!` returned no `:seon.schema/keys`), and `seon.test/run`'s
`resolve-test` uses `requiring-resolve`, which reloads NOTHING already loaded.
`(#'seon.test/with-test-loader #(require 'ns :reload))` for BOTH the test
namespace and its fixture before the run is the rule the repl-rule file should
carry; without it the test namespace's own edits do not run either (the run
reported 30 assertions with two new ones added, and 32 after the reload).

## Live proof on `default` (after adoption)

- A fresh three-form declaration turn (`:probe7/tag` identity attribute,
  `:probe7/amount`, `:probe7/row` entity map): all three keys present in the live
  ctx projection after settlement. Before the change the same turn landed one.
- Live projection vs database-derived population: **2 764 = 2 764**, zero
  difference in either direction.
- `docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj`
  `install!` on `default` returned
  `{:seon.schema/keys [:example/amount :example/customer :example/order :example/order-row],
    :seon.turn/id "aa071259cfd8"}` — the same turn id the juniper-installer lane
  recorded, so the installer IS idempotent by identity. Immediately after:

```clojure
#:seon.schema{:missing-rows []
              :missing-projection-keys []
              :carried-buckets
              {:example/order      [forms reverse-schema-dependencies schema-admissions schema-dependencies shape-index]
               :example/order-row  [forms required-by-key schema-admissions schema-dependencies shape-rows]
               :example/amount     [forms reverse-schema-dependencies schema-admissions schema-dependencies shape-index]
               :example/customer   [forms reverse-schema-dependencies schema-admissions schema-dependencies shape-index]}
              :database-derived-missing []
              :live-count 2743 :derived-count 2743}
```

  Honest caveat: the four `:example/*` keys were ALREADY declared on `default`,
  so that install re-declared nothing. The decisive fresh-declaration evidence is
  the `:probe7/*` turn above and the canonical-harness regression.
- `default` was never stopped, reset or reforked; no test JVM was launched.

## Probe hygiene

The 21 `:probe*/…` keys the reproduction declared were unregistered through an
ordinary turn (closed, no errors). Their identity rows survive as tombstones by
ruling. The runner's drift detector then RESTORED four of them into `default`'s
live registry during an in-process run — the write-storm mechanism this issue
describes, seen directly — so the projection state was advanced once with
`schema/projection-from-database` at the cluster's basis. `default` now carries
exactly its pre-probe population, 2 743 = 2 743 in both directions.

## The coordinator's second sighting is a DIFFERENT seam

`seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
fails at `(str/includes? (definition default) "[] 2")`
(`test/seon/cluster/boot_test.clj:1085`), where `definition` is
`(db/pull … [:seon.fn/source] [:seon.fn/sym "adoption-probe/value"])` on the
cluster's CONNECTION — a DATABASE fact, not a projection. My defect is the exact
opposite direction: the facts were intact and the mirror was short. The adoption
path re-derives its projection from the committed database
(`src/seon/cluster.clj:2177`) and does not use `committed-row?`; its suspect is
the scalar `:seon.source/upsert-rows` branch at `src/seon/cluster.clj:2151-2167`,
which commits `scalar-rows` only when `(= prior-commit (:seon.source/commit-id
before-publication))`. Not this lane's.

## Verification boundary

- Proven: the mechanism, by tracing `committed-row?` on the live cluster; the fix,
  by re-running the same turn shape; the four in-process runs above; the live
  installer post-condition and projection equality on `default`.
- NOT proven here: the isolated gate. Request at
  `tmp/orchestrator/gate-requests/live-projection.txt`.
- NOT proven: whether any other caller compares a pre-canonical row's source
  bytes against a committed declaration. `rg` found only these two sites in
  `src/`, but the writer's own `declared-content` comparison in `seon.turn`
  (protected for this lane) canonicalizes first and was not re-examined.
- `bin/seon init --dev default --changed …` refused once with
  `:stale-branch-head` on `:current-src` while other lanes were publishing; the
  edit hook's own publication adopted the change (verified by the new docstring
  being loaded before every proof above).

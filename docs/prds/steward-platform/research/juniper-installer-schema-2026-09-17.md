---
date: 2026-09-17
lane: juniper-installer
issue: docs/seon/issues/context-blocks-fixture-leaks-example-schema-keys-into-the-live-cluster.md
---

# The Juniper scenario's schema is a fact — and the projection drops half of it

The assignment asked which of two shapes is honest: (a) the example schema is
part of the seeded scenario and belongs declared as a fact, or (b) the
installer should register into a candidate delta scoped to the seeded agent
and never touch the cluster's projection.

**The answer is (a), and it is already true today.** The premise the issue
rested on — "two undeclared example keys" — is refuted by the database. The
measurement made while proving it found a different, worse defect, filed
separately.

## Evidence for (a)

Measured live on `default` (pid 88182, fresh after the day's reset):

| question | measurement |
|---|---|
| are the orders data? | 4 `:example/order` datoms with `:example/customer` / `:example/amount` |
| is the datahike schema installed? | `:example/order` `db.type/string` `db.unique/identity`; `:example/amount` long; `:example/customer` string |
| are the declarations facts? | entities 47240 / 47252 / 47264 / 47267 carry `:seon.schema/key`, `:seon.schema/form` and `:seon.schema.admission/source :agent`, never retracted in history |
| how does a projection get them? | `seon.schema/projection-from-database` (`src/seon/schema.clj:2452`) queries `[?schema :seon.schema/key ?key]` + `:seon.schema/form` — the keys are in every projection derived at that basis |
| does the live projection agree with the facts? | after a reseed: live 2,743 keys, database-derived 2,743 keys, `live - derived = []`, `derived - live = []` |

So the installer already admits through the ordinary declaration path: the
agent evaluates `seon.schema/register!` in a real turn, `seon.turn/row-tx`
validates and commits one row per key, and the projection derives from the
rows. A key with `:seon.schema.admission/source :agent` behind it is a
declaration, not a registry mutation.

**(b) is impossible, not merely worse.** The scenario's orders are durable
datoms on a `:db.unique/identity` attribute; a candidate delta scoped to the
agent's ctx cannot admit an attribute the writer must install to accept
`seed!`'s transaction. Scoping the registration would delete the scenario.

**The leak hypothesis in the issue is refuted too.** It proposed that
`seon.sci.eval/fork-cluster-ctx`'s 4-arity hands a fixture the live
projection-state atom. `seon.test-support/fork-cluster-ctx`
(`test/seon/test_support.clj:423`) takes the state from the fixture
database's own metadata, so no fixture cluster shares `default`'s atom.

## What the drift check really caught

Installing the scenario on a canonical fixture cluster leaves the IN-MEMORY
projection missing exactly the two STORABLE declarations, while the database
has all four:

```clojure
#:seon.schema{:missing-rows #{}
              :missing-projection-keys #{:example/order :example/order-row}
              :carried-buckets #:example{:order #{}, :order-row #{}}
              :database-derived-missing #{}}
```

`:example/amount` and `:example/customer` — plain shapes — survive. The
`:seon.db/identity` attribute and the `:seon.db/attributes` map do not, and
they are missing from every bucket of the projection, not merely from
`forms`. It persists through `seed!`, `clear-history!` and the opening system
turn.

That is the write storm's disease one layer up — the writer derives its
declaration population from a projection contradicting the cluster's own
facts — and it explains the original report exactly: the keys were dropped at
install and re-derived from the database later, and the runner's drift
detector saw that re-derivation as keys "added" during a run, then restored
them away. Filed as
[a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection](../../../seon/issues/a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection.md)
(blocker; owner is `src/seon/sci/eval.clj`, protected for this lane), with the
suspect seam named: `install-row!`'s `:seon.schema/key` branch answering
`(or prepared-projection (schema/projection-from-database db projection))`
and `advance-context-projection!` replacing the accumulated projection with
it at an equal basis.

## What landed (`5553725d3`)

`test/seon/context_blocks_fixture.clj`:

- `schema-declarations` is the scenario's schema as data. `schema-keys` and
  the submitted `schema-source` derive from it; the derived source is
  byte-identical to the previous hand-written string (verified live, `=` on
  the two strings), so the agent evaluates exactly what it did before.
- `declared!` is `install-running!`'s post-condition: every scenario key is a
  durable `:seon.schema/key` row AND present in the projection derived at the
  cluster's own basis. It checks the AUTHORITY, not the in-memory mirror a
  later adoption re-decides, names the missing keys, and returns
  `:seon.schema/keys` as the installer's evidence. An absent key is a named
  refusal, never silence.

`test/seon/loop_proof_test.clj` — the class regression, inside the existing
canonical-harness test that already installs the scenario
(`running-fixture-settles-its-seeded-wake`), so no second installation was
added. By key, never by count:

- the scenario's keys are NOT in the cluster's declared population before;
- after the install the derived population is exactly
  `declared-population ∪ schema-keys`;
- the installer's own evidence names exactly those keys;
- every one of them is backed by its own durable declaration row.

## In-process runs (pid 88182, `default`, one at a time on a daemon thread)

| run | result |
|---|---|
| `seon.loop-proof-test/running-fixture-settles-its-seeded-wake` (ctx-projection post-condition) | 0/0/1 — the defect above, with its evidence map |
| same, post-condition moved to the end of `install!` | 0/0/2 — identical missing pair |
| same, post-condition at the end of `install-running!` | 0/0/1 — identical missing pair |
| same, post-condition at the authority (HEAD) | **30 assertions, 0 fail, 0 error** |
| `seon.test-support-test/a-synthetic-schema-registration-leaves-the-registry-byte-identical` | 7, 0, 0 |
| `seon.test-support-test/a-test-body-inherits-no-ambient-cluster-custody` | 3, 0, 0 |

None of the four runs after the change reported `Live cluster schema registry
changed and was restored`, and none reported `::snapshot-schema-keys` drift.

## Live proof on `default` (after the change)

- The installer ran against `default` through
  `docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj`
  `install!` and returned
  `{:seon.schema/keys [:example/amount :example/customer :example/order
    :example/order-row], :seon.turn/id "aa071259cfd8"}` — its post-condition
  passed against the live cluster.
- Live projection vs database-derived population immediately after:
  2,743 = 2,743, zero difference in either direction, all four example keys
  present.
- Drift check quiet: the two `seon.test-support-test` runs above were made
  AFTER that install and reported no leaked key and no restore.
- Juniper's opening still renders its scenario: 9,273 bytes carrying the
  objective, the `[juniper/read]` step and its `:example/order` /
  `:example/customer` criterion; the four orders (`a1` Ada 60, `a2` Ada 55,
  `b1` Bea 100, `c1` Cy 40) are queryable facts on the cluster.
- `default` was never stopped, reset or reforked; no test JVM was launched.

## Verification boundary

- Proven: the claim that the scenario's declarations are facts, on the
  canonical harness and live on `default`; the installer's post-condition on
  both; the drift check quiet after a live reseed.
- NOT proven here: the isolated gate. Request at
  `tmp/orchestrator/gate-requests/juniper-installer.txt`.
- NOT this lane's: the projection drop. It is a blocker at a protected owner
  (`src/seon/sci/eval.clj`) and the regression that would fail on it is
  deliberately NOT in this commit — a test asserting a known-open defect's
  presence is worse than the issue that names it.
- The other fixture callers (`seon.rereads-test`, `seon.turn-continue-test`,
  `seon.data-shapes-test`, `seon.help-trial-test`, `seon.run4-install-test`,
  `seon.render.web-debug-test`, `seon.schema-redeclare-test`,
  `seon.contracts-fixture`) were not run in process; `install!` keeps its
  previous return shape and `install-running!`'s return only GAINS
  `:seon.schema/keys`, so the change is accretion for them.

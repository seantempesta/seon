---
type: landing
status: landed; focused runner verification blocked by a stale test base (see Limits)
created: 2026-09-23
tags: [agent-platform, boot, validator, performance, A2]
---

# Lane validator-single-pass (2026-09-23)

Evidence source: `docs/research/agent-platform/from-zero-boot-cost-2026-09-23.md`
(audit row 1, phase 6a). Commit **`6bf3bde78`**: "Group the arity-gate decision by owning
root in one pass over the report".

## Change

`src/seon/db.clj` `write-owned-values-error`. The old code ran once per owning root. For
each root it scanned the whole `:tx-data` with `some`, and for every datom it walked that
entity's owning ancestors on both sides. It did this only to decide whether the root's
identity attributes feed the arity gate (`write-report-error`, the
`changed-identity-attributes` check). That is roots × datoms × depth.

The new code computes a `delay`, `changed-roots`, once. It holds two things:
- every entity carrying an arity-bearing datom (`:seon.fn/sym`, `:seon.test/sym`,
  `:seon.fn/call-arities`, `:seon.fn/arities`) on itself;
- every owning ancestor, before or after, of each distinct report entity, with the
  entity itself excluded.

The per-root test is now a set lookup. The work is linear in the report plus one ancestor
walk per distinct entity. Refusals, the order of `vswap!` and the `fail!` paths are
unchanged. The walk re-reads only `owners-of` entries that the owning walk has already
charged, so the node bound behaves as before.

Is the decision needed at all? The gate runs when some changed root carries a gate
identity attribute. A from-zero write asserts every root's own identity datom, so there
every root is changed and the gate always runs. The condition is still needed for
incremental writes: a documentation-only edit to a function root is scenario
`root-doc-only` below, and it leaves the gate closed. It is now derived once, lazily, only
when the first root carrying `:seon.fn/sym` or `:seon.test/sym` asks for it.

Other rescans in the validator (checked by reading `write-report-error` end to end):
- `retention-report-check` is rules × affected.
- The attempted-datom `some` is linear.
- `write-deletion-error` is per affected entity.
- `write-agent-retraction-error` is linear.
- `write-render-target-error` is one query over schema rows.

None of these is a per-root rescan of the report. `owners-of` still probes every component
attribute per entity (audit 6b, ~15 s). That is entities × 136 component attributes, which
is linear with a large constant, so it was left alone and belongs to A2's
report-proportional validation.

The function also gained its Malli contract. `test/seon/owned_value_test.clj` now declares
`:seon.program/partition :seon.data` on its fixture entity schemas, which `8a069b5e4`
requires (a retired assumption, so the expectation was fixed). It also adds the regression
`one-invalid-root-among-many-refuses-by-its-identity`.

## Proof

(a) Microbenchmark. `tmp/validator-single-pass/bench.clj` runs the function directly on a
`d/with` report of N `:seon.fn/sym` roots, each with one owned child, on a fresh memory
store. It was run in the default JVM. "Before" is the loaded HEAD fn captured as `old-fn`;
"after" is the edited `defn` evaluated into `seon.db`.

| N roots (datoms) | before ms | after ms |
|---|---|---|
| 100 (301) | 15 | 3 |
| 1,000 (3,001) | 1,182 | 31 |
| 2,000 (6,001) | 4,987 | 61 |
| 5,000 (15,001) | **36,709** | **167** |
| 20,000 (60,001) | — | 782 |

Before grows about 4× per doubling (quadratic). After grows linearly.

(b) Identical outcomes. `(scenarios f)` in the same file returns `{:changed … :refusal …}`
for each scenario, with `:seon.error/at` removed. The scenarios:
- from-zero, 50 roots;
- a child value change;
- root doc only (`:changed #{}`);
- a new root plus a doc change;
- a child `retractEntity`;
- an invalid component child (a `::value` refusal naming owner eid 66);
- an unowned entity (`::unowned-entity`).

`(= (scenarios new-fn) old-scenarios)` → **true**, and `:differs {}`. The refusal values
are identical, entity ids included.

(c) Regression, run on committed code: `seon.owned-value-test/one-invalid-root-among-many-refuses-by-its-identity`.
- The runner could not execute it (see Limits).
- Its body ran as `tmp/validator-single-pass/regression.clj` through the real
  `seon.db/transact!` and write-report validator. It ran in a JVM booted from the
  `6bf3bde78` archive, on a fresh memory store carrying the fixture projection.
- 200 valid roots plus one root missing `::value` → refused with
  `:seon.db/entity {::key "bad"}` and a request id. Basis unchanged; 205 ms.
- The 200 valid roots → accepted, 200 keys, 237 ms.

(d) From-zero boot of committed `6bf3bde78`. This was a measurement, not a gate. Frozen
`git archive` in `tmp/vsp-source` (reference-code symlinked), empty root `tmp/vsp-root`,
`bin/seon --root tmp/vsp-root start vsp`: exit 0.
- **ready-ms 74,027** against the 170,338 baseline. Wall 89.2 s, load 7.44 → 11.34.
- Program-rows transaction (453,768 history datoms at tx `536870917`): the gap from its
  `:db/txInstant` to the next transaction's is **29,865 ms**. The baseline phase 6 was
  ~125,500 ms.
- The row is appended to `docs/seon/issues/from-zero-boot-takes-minutes.md`.

## TIMINGS (own operations over 1 s)

| operation | ms |
|---|---|
| bench before N=1k / 2k / 5k | 1,182 / 4,987 / 36,709 (the defect itself) |
| bench after N=5k / 20k | 167 / 782 |
| `bin/test-fast --paths … -- seon.owned-value-test` ×2 | ~40 s each; exit 1, fixture base stale |
| from-zero `start vsp` | wall 89,174; ready 74,027 (**over 10 s: defect**, issue row added) |
| `bin/test --prepare-head-base` | 37,080; exit 1 (stale default, see Limits) |
| warm `start vsp` | wall 30,115; ready 14,469 (**over 10 s**, existing issue row) |
| `seon.test/run-owned` ×6 in vsp | ~5,000 each; fixture needs a published base, errored |
| `down` | 2,430 |

## Limits

- **The focused runner verification did not pass.** `bin/test-fast` (runs `5995c1bd98eb`
  and `3a17ec42b5a4`) errors in canonical fixture setup. The newest published base
  `d73e0a6c…` is 67 commits old and predates the partition rule. A sighting was added to
  `docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`.
- `bin/test --prepare-head-base` refuses inside the default JVM (pid 51528) with the
  `render-diff-ai` surviving-referrer error. Default still runs code older than
  `f31074521`. A sighting was added to
  `docs/seon/issues/incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives.md`.
  **RESET NEEDED** (or a reload of default), then prepare a head base and run
  `bin/test-fast --paths src/seon/db.clj test/seon/owned_value_test.clj -- seon.owned-value-test`.
- For the microbenchmark, the edited `write-owned-values-error` `defn` was evaluated into
  `seon.db` in the default JVM. It is identical to committed `6bf3bde78` and unarmed, like
  any reloaded Var. Nothing was published or adopted.
- Only one from-zero run was taken, under ~7–11 load. `owners-of` (6b) and datom-linear
  checks remain. The ~30 s write phase is now bounded by them and by Datahike insertion.
- `src/seon/db.clj` contains another lane's uncommitted `removed-definition-error` hunks.
  Only this lane's three hunks were staged.
- Scratch roots `tmp/vsp-root` and `tmp/vsp-source` were deleted after `down`, with no
  holders. The probes stay in `tmp/validator-single-pass/`.

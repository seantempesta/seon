---
type: landing
lane: fix-test-rows-e-opus
status: landed-unverified
date: 2026-09-25
authority: docs/research/agent-platform/red-baseline-census-2026-09-25e.md (HANDROW, GUARDFORM, SEEDROW, WORLD, CUSTODY tests, FLAT tests)
evidence: tmp/orchestrator/verify-6ab6920a-results.txt.d/
---

# Lane fix-test-rows-e (2026-09-25)

These are UPDATE-TEST fixes. Every world is now built through a canonical writer, and every assertion follows the
producer's current shape. No src was edited.

## Broken or unproven first

- **Nothing here has run.** Under the load rule the only checks were clj-kondo, which found no new warnings in any
  touched file (compared against `git show HEAD:<file>`), and small read-only JVM probes in the throwaway namespace
  `fix-test-rows-e-scratch` on default (pid 35446). Root's runs below are the first proof.
- **Four members need src, not a test change:**
  - **contracts-plan installed-contract (HANDROW).** The fixture does declare `:example/order-row`
    (`test/seon/context_blocks_fixture.clj:25`). Its `register!` submission reports no error, yet the writer's
    `projection-with-declarations` (schema.clj:2836) refuses the `largest-customer` row and reports the key as
    missing. The candidate cause is an entity-map (`:seon.db/attributes true`) registered key that is absent from the
    candidate registry. Owner: `seon.schema`, or `test/seon/contracts_fixture.clj`, which render-dispatch-opus holds.
    Needs one probe.
  - **CUSTODY my.catalog ×2 and effect-attempt.** The fixture writes only through `transacted!` and
    `declaration-row`, so it is not the failing writer. The refused write is inside the nested member's body:
    `label` → `seon.effect/request!` writes its receipt, and that write names branch A while the writing cluster
    is branch B. The NPE in `a-hidden-call` comes from `(resolve 'my.catalog/label)` returning nil in the nested
    member's SCI context. Owner: effect custody in a nested attempt (`src/seon/effect.clj` and the member handle
    in `src/seon/test.clj`). No test edit was made.
  - **schema-usage-guard nonidentical-change.** Writing `"seven"` after `:int`→`:string` throws a
    ClassCastException (Long vs String `compareTo`) inside `seon.db/transact-call` (db.clj:4510). That is a Datahike
    index holding old-type AVET values after adoption drops current data. FIX-SRC at seon.db's schema-change adoption.
    The printed-form assertion before it is fixed here.
  - **sci.eval evaluation-custody :1807.** `(:seon.db/invalid-read (:seon.sci.admit/value rejected))` is nil for an
    undeclared-attribute write. Probe the writer's refusal members before choosing a test or src fix.
- **Two ledger collisions.** `test/seon/oversight_test.clj` and `test/seon/sci/eval_test.clj` are on this lane's row
  and also on render-dispatch-opus's row (eval_test is scoped there to ":2221 only"). Only the hunks listed below
  were edited, and none is near :2221.
- **An out-of-scope defect class: the `(-> form (comp f g))` sweep residue.** 0f5f849bd threaded a form into `comp`,
  which built a function, so every keyword read of its result was nil. The two `problems_test` sites are fixed.
  `test/seon/render_coverage_test.clj:49` (`family-properties`) has the same defect and is not owned here. Every
  assertion that expects nil through it passes vacuously. Fix: thread
  `seon.schema/structural-schema seon.schema.internal/entity-properties`.
- RESET NEEDED: none.

## Per member

| class | member | change | commit |
|---|---|---|---|
| HANDROW | my.note-test ×4 | subject is a message sent through `seon.cluster.message/send!` (alice→bob); agents through `transacted!`; the test reads the sent id | 10d1a53ba |
| HANDROW | agent-call-edges admitted-declarations | `agent-tx` in place of the hand agent map; no-provider as the `seed-cluster!` manifest dial | 10d1a53ba |
| HANDROW | background-test | effect owner/capability `my.shell/run!` (indexed on default: eid 2038) | 10d1a53ba |
| HANDROW | contracts-plan installed-contract | not fixed: src/fixture finding above | — |
| GUARDFORM | schema-usage-guard ×4 printed-form sites (+ :372, :425) | `printed` binds `*print-namespace-maps* false`, as schema.clj:3814 stores; `schema-row` prints the same way | 10d1a53ba |
| GUARDFORM | entity-lifecycle :454/:465 | `:seon.schema/references` is `:db.type/keyword` many ("names survive retraction", seon.schema.edn:83; probe: 2458 keyword datoms on default), so edges are read directly and not joined through a target entity | 10d1a53ba |
| SEEDROW | seeds-prepare, host-body, refused-seed | seeds are complete `:seon.maintenance.result/entity` rows (their only required key is their string identity; probe of the packaged forms); the redefine seed passes `revised-row` rows to `reconcile-tx` (digest landing) | 049df4a78 |
| WORLD | sci.eval forks-have-branch-custody, evaluation-custody | cluster reads filtered to the fixture's own names (`fork-a`/`fork-b`, `ambient-a`/`ambient-b`) | 049df4a78 |
| WORLD | config apply-replaces | filtered to `ancestor`/`fork` | 049df4a78 |
| WORLD | oversight every-consumer | a cluster row now requires `:seon.cluster/config` (cluster.clj:3072), so "unconfigured" is a name with no rows; the test asserts it is absent before asserting the by-name refusals | 049df4a78 |
| FLAT | sci.eval multi-arity-miss, compiled-runtime-metadata | `::instrument/arity` and `::instrument/arglists` are top-level members (instrument.clj:397–398) | 049df4a78 |
| FLAT | schema-test refused-projection-source | the armed input contract refuses first: operation `projection-from-database`, member `:seon.fn.arity/input`, offending `[poisoned]` (instrument.clj:378, :392) | 1a3027f6c |
| FLAT | problems-test missing-projection | refusal from `seon.db/projection-fallback` (db.clj:1230): operation, layer `:seon.schema/projection`, `:seon.db/refused-read-operation`, `:seon.schema/refused-value` | 1a3027f6c |
| FLAT | problems-test missing-model (+ stale-var) | the property read compiles the form; before this it composed a function, so keyword reads were nil | 1a3027f6c |
| CUSTODY | my.catalog ×2, effect-attempt | not fixed: src finding above | — |

Changes: +90 / −79, net +11 (note_test is +35/−32, mostly re-indentation under the new `let`).

## Exact changes for held files

**test/seon/db_test.clj (fix-writer-digest-opus)**

- **`an-unknown-read-operation…` (:2195).** Remove `data (:seon.error/data unknown)`. Read `(:seon.error/member
  unknown)` and `(:seon.error/offending unknown)` at the top level.
- **`component-expanded-pull…` (:916).** The writer's projection comes from the database's schema rows, not from
  `fixture-projection`. So also transact `(schema/canonical-schema-rows fixture-projection (select-keys
  (:seon.schema.projection/forms fixture-projection) [::component-root-id ::component-value ::component-child]))`,
  then advance the fixture projection. Without those rows the component target is missing
  (`missing-component-schema`, db.clj:3617).
- **`a-write-naming-another-clusters-branch…` (:1728).** Needs a probe first. The elided write runs inside the inner
  `with-database`, so the custody is `foreign-connection`'s, and `(binding [db/*conn* writing-connection])` does not
  override it. Either wrap the elided write in `(db/call-with-custody {:seon.db/connection writing-connection} …)`
  or assert that the elided arity writes the custody branch. Root decides which contract is intended.

**test/seon/error_test.clj (render-dispatch-opus)**

- **`complete-error-children…` (:1044).** As in db_test: the `::manifest-*` keys exist only in a built projection.
  After the extra-schema, transact `(schema/canonical-schema-rows projection (select-keys forms [::manifest-id
  ::manifest-location ::manifest-observation ::manifest-explanations ::manifest-root]))`, then carry the projection
  state.

**test/seon/render/web_debug_test.clj (render-dispatch-opus)**

- **reverse-blocks (:571).** `{::hidden [:seon.agent/id "relationships"]}` is a root row with no identity, which the
  writer refuses as `unowned-entity`. Put `::hidden` on a second fixture agent instead:
  `(support/agent-tx @connection "hidden-holder")` plus
  `{:seon.agent/id "hidden-holder" ::hidden [:seon.agent/id "relationships"]}`.
- **outline-origin (:1072).** A bare `retractEntity` on a started issue is refused by db.clj:4451 ("only the creator
  may remove tests"). Retract as the creator with `{:tx-data [...] :tx-meta {:seon.db/user [:seon.agent/id
  "outline-probe"]}}`. The first clause of that check (active, with every test removed) may still refuse, and if it
  does, create the issue unstarted. Probe.
- **turn-details (:91).** The kinds come from the world's inherited `:seon.config.ai/no-provider true`. Seed the
  fixture cluster with the dial it asserts about, through `seed-cluster!`'s manifest, or assert only `"Virtual"`,
  which the no-provider world produces (transcript.clj:1177–1183). Root rules on whether member worlds carry the
  development overlay.

## Root runs (serially, after adopting 1a3027f6c)

```
bin/test-check default --ns my.note-test
bin/test-check default --test seon.agent-call-edges-test/admitted-declarations-retain-call-dependencies-across-turn-order
bin/test-check default --ns seon.background-test
bin/test-check default --ns seon.schema-usage-guard-test
bin/test-check default --ns seon.test.seed-preparation-test
bin/test-check default --test seon.sci.eval-test/acquired-source-context-forks-have-branch-custody-and-private-defs
bin/test-check default --test seon.sci.eval-test/evaluation-custody-is-derived-only-from-the-cluster-context
bin/test-check default --test seon.sci.eval-test/compiled-runtime-metadata-cannot-be-changed-by-agent-code
bin/test-check default --test seon.sci.eval-test/an-instrumented-multi-arity-miss-reads-like-clojure
bin/test-check default --test seon.config-test/apply-replaces-an-inherited-config-identity-regardless-of-provenance
bin/test-check default --test seon.oversight-test/every-consumer-reads-the-cluster-its-caller-names
bin/test-check default --test seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms
bin/test-check default --ns seon.problems-test
```

Expected: green, except the src findings above (schema-usage-guard nonidentical-change and sci.eval :1807) and
problems-test `absent-facts-produce-no-entries`, which runs at 24.9 s against its 5 s bound (class BOUND, not this
lane's).

Released: every path on this lane's ledger row.

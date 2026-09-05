---
type: issue
status: resolved
severity: blocker
tags: [issue, database, sci, runtime]
---

# Make `seon.db` the one agent-first database namespace and route everything through it

## Problem

Ruling 2026-08-02 #41 (plan README): all of Datahike's core functions
belong in `seon.db`, agent-first (ambient cluster connection,
errors-as-values, SCI-admit-clean returns), and ALL first-party code
goes through it. The measured state at ruling time:

- `seon.db` exposes only `q`, `pull`, `pull-many` and is required by
  exactly ONE namespace in src/ (`seon.render`). The namespace was
  intended as the one database surface and never adopted.
- 34 namespaces require `datahike.api` directly: 143 `d/q`, 48
  `d/pull`, 5 `d/datoms`, 4 `d/history`, 1 `d/entity`, 1 `d/as-of`,
  1 `d/since` call sites (counted 2026-08-02).
- 16 `d/transact` sites bypass `seon.cluster.store/transact!` and its
  never-throw/four-outcome/Integer→Long/schema-encode protections:
  `seon.cluster` (boot ×6), `seon.cluster.source` (×3), `seon.fn`
  (×2), `seon.render.web` (×2 — runtime message submission),
  `seon.reconcile`, `seon.test.runner`, `seon.eval.drive`.
- Missing agent surface entirely: `transact!`, `entity`, `datoms`,
  `db`, `history`, `as-of`, `since`.

Supersedes the `seon.db` fragment of
[[unlogged-findings-2026-08-01]] item 5 (its `seon.db` completion-wave
destination folds into this issue). Vocabulary (ruling #41 amendment):
the word is `seon.db`, never "facade" — it is the db namespace, and the
interception exists so Datahike failures return as flat error values
and custody defaults to the calling agent's cluster.

## Acceptance

1. `transact!` lives in `seon.db` (moved, not duplicated — store keeps
   only store/branch/flock custody; registry keeps branch management);
   the four-outcome contract, Integer→Long walk, and schema encode are
   preserved with their tests.
2. `seon.db` gains `entity`, `datoms`, `db`, `history`, `as-of`,
   `since` — each with an optional explicit db/connection first
   argument, the ambient cluster default, errors-as-values, evidence
   capture, and SCI-admit-clean returns (no lazy index seqs, no opaque
   Entity object into agent context). `listen!` stays system-side.
   TWO INTERFACES PER FUNCTION (ruling #41 amendment): Datahike's own
   positional arity AND Datahike's own argument-map arity — `{:query
   :args :offset :limit}` for `q`, `{:selector :eid}` for `pull`,
   `{:tx-data :tx-meta}` for `transact!`, `{:index :components}` for
   `datoms` (specification.cljc is the authority; never an invented
   envelope) — and BOTH forms may elide the db/conn to assume the
   calling agent's cluster's current database.
3. Every first-party `datahike.api` call site for those core functions
   migrates to `seon.db`; `datahike.api` requires remain only in
   `seon.db` itself and the store/registry custody owners. Each of the
   16 write sites is classified (runtime / boot / fixture) with its
   failure semantics stated before migration.
4. Through the SCI evaluator, an agent can with implicit cluster arguments `q`, `pull`, `transact!` a
   declared attribute, read it back, walk `history`, and get Datahike's
   own refusal value for an undeclared attribute.
5. AGENTS.md's database-access law, `architecture/data-model.md`, and
   `toolkit.md` state the landed reality in the same wave, and no
   current authority calls `seon.db` a "facade".

Historical dependency: the consolidation lane's in-flight files had to land
before the sweep could take the quiet window. The ambient-connection blocker
([[agent-evals-never-bind-the-ambient-cluster-connection]]) RESOLVED
2026-08-02 (`643719904`) — the ambient default is real; its two loose
ends transfer here: `bootstrap_drive.clj:141-155` holds a connection it
does not yet pass in its evaluation request, and the render walk's own
binding site remains the render owner (custody during an agent
evaluation now comes from the evaluator request, not the walk).

## Core namespace wave evidence — 2026-08-02

Commit `7661c0214` lands acceptance items 1, 2, and the namespace part of
4 while leaving this issue open for the counted call-site sweep:

- `transact!` moved into `seon.db`; `seon.cluster.store` retains store,
  branch-connection, and flock custody. The four outcomes, schema encode,
  and exact Integer→Long walk moved with their tests.
- The moved call sites are named explicitly: production callers
  `src/seon/cluster.clj` and `src/seon/cluster/loop.clj`; regression callers
  `test/seon/cluster/armed_test.clj`, `boot_test.clj`, `store_test.clj`,
  `store_transact_test.clj`, `turn_test.clj`, `test/seon/instrument_test.clj`,
  and the two SCI evaluation forms in `test/seon/sci/eval_test.clj`. No other
  call site was migrated in this wave.
- The dependency order is now Datahike + `seon.schema` +
  `seon.schema.datahike` + the leaf `seon.error.refusal` → `seon.db` →
  `seon.cluster.store` → higher cluster/render owners. `seon.db` requires
  neither the custody owner nor the rendering-aware `seon.error` namespace.
  Connection/database predicates and their honest generators moved down;
  the store's existing public predicates delegate to that one
  implementation. This removes the observed
  `render → error → store → db → render` load cycle.
- `entity` returns wildcard-pull ordinary data: an eager map, component
  refs recursively expanded, and ordinary refs represented as `{:db/id
  ...}`. It costs time and memory proportional to the entity plus its
  component closure and gives up lazy attribute-by-attribute navigation.
- `datoms` returns an eager vector of ordinary `{:e :a :v :tx :added}`
  maps. It costs O(n) realization time and memory and gives up Datahike's
  lazy cursor and host `Datom` operations; neither process-local value can
  escape into agent evaluation.
- Focused gate: `seon.db-test`, `seon.cluster.store-transact-test`, and
  `seon.cluster.store-test` — 32 tests, 122 assertions, zero failures or
  errors. Changed-caller gate: `seon.cluster.armed-test`,
  `seon.cluster.boot-test`, `seon.cluster.turn-test`,
  `seon.instrument-test`, and `seon.sci.eval-test` — 133 tests, 677
  assertions, zero failures or errors.
- Fresh live proof used the isolated operator root `tmp/r41-one-db`, forked
  cluster `r41` from the newly published source, and booted it without a
  cyclic load. One SCI evaluation with implicit cluster arguments exercised positional and
  Datahike argument-map forms of `q`, `pull`, and `transact!`; both writes
  returned transaction reports, both queries returned
  `#{"r41-map" "r41-positional"}`, both pulls returned their matching
  declared `:seon.cluster.message/id`, and `history` returned both ids. A
  write of undeclared `:seon.r41/undeclared` returned
  `:seon.db/rejected` carrying Datahike's `:transact/schema`. The operator
  root was taken down after the proof.

## Resolution — 2026-08-03

Resolved by the path-limited quiet-window checkpoints from `4da65e9ee` through
`09531af57`. Every planned production and test caller was handled according to
the caller-level judgment in
`docs/prds/sci-execution-runtime/research/seon-db-sweep-plan-2026-08-03.md`;
the plan's divergence log records the source corrections discovered during
execution.

The final production census has zero non-exempt `datahike.api` core calls. The
exact residual is ten exempt namespaces / 68 calls: 32 implementation calls in
`seon.db`, 30 lifecycle/custody calls in the store, registry, branch, source,
and process owners, and six listener calls in `seon.cluster.agent`,
`seon.cluster.wake`, and `seon.eval.drive`. The sole core-shaped call outside
`seon.db` is the documented `d/db` readiness check inside
`seon.cluster.store/open-store!`. Tests retain 138 calls in 26 files, all
lifecycle/listener fixtures or intentional below-boundary parity and encoded
storage probes.

A newly published isolated operator root exercised ambient agent SCI
`transact!`, `q`, `pull`, and `history`; the declared message round-tripped and
an undeclared attribute returned `:seon.db/rejected` with
`:transact/schema`. The bare final `bin/test` gate passed 883 tests / 4,405
assertions with zero failures or errors in 697.50 seconds wall time.

## Independent codec-window evidence — 2026-08-03

An adversarial probe installed a synthetic `[:or :string :qualified-symbol]`
attribute, transacted a qualified symbol through `seon.db/transact!`, and read
it through both namespaces. `seon.db` returned `clojure.lang.Symbol` through
`q`, `pull`, `entity`, and `datoms`; direct `datahike.api` returned
`java.lang.String` through every equivalent path, including query tuple and
pull-expression positions.

The production population census found 24 union declarations but zero
installed fallback mixed-union attributes. The completed sweep removed that
migration-window risk by routing logical reads through `seon.db`. Full probe
output and source anchors are in
[[adversarial-pass-2026-08-03]].

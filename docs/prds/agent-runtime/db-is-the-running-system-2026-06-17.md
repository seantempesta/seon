---
type: prd
status: draft
tags: [prd, agent, cljs, database]
---

# The database is the running system

Implementation PRD. Written for an agent picking this up in a FRESH session — every
thread is linked at the bottom (research docs, concept docs, code anchors with line
numbers, vendored source). Read the TL;DR, then "The model," then the linked
research before writing code. "Slow is fast" — verify each Phase-0 item live before
building on it.

## TL;DR

The **compiled package** (kernel + core + third-party) is the base — module-loaded,
present in the runtime, may contain ANY valid CLJS. The **DB layer** is what evolves
— agent code + overrides, restricted to `defn` / `schema/register!` / `deftest` —
and is the only thing LOADED from the DB, eval'd on top of the compiled package.
Redefining a function is just an UPSERT on `:seon.fn/sym` (last-write-wins) — exactly
like at a REPL. There is no "override" mechanism, no `set!`, no replay-skip, no
reconcile, no drift, no deltas. We never re-eval core/third-party (they're compiled),
so the load is small.

**The one fix that unblocks the load:** index `:seon.ns/requires` (captured and
UPDATED at tee). Today it is not stored (0 refs), so reconstructed agent namespaces
are missing their `(:require …)` and can't eval in order. With it stored, the load
is: query the DB layer → reconstitute namespaces → topo-sort by requires → eval.
PROVEN this session: the reconstitution query works (2 queries → 41 namespaces);
the only failure was the missing requires; eval is ~2.81 ms/fn (the DB layer is far
smaller than the whole codebase).

## The model

**Compiled package** (the base) + **DB layer** (what evolves):

1. **Compiled package = kernel + core + third-party bulk.** Shadow compiles all of
   it into the bundle; module-load puts every var in the runtime. Third-party source
   may contain LITERALLY ANY valid CLJS (atoms, helper `def`s, macros, top-level
   forms) — it is compiled in, runs once at module-load, and is never reloaded. The
   CLJS COMPILER itself is in here — it is the kernel, the thing that evals the DB
   layer; you cannot load from a DB you have not loaded the compiler to read.
2. **DB layer = agent code + overrides, restricted to `defn` / `schema/register!` /
   `deftest`.** This is the evolving program graph — the only thing LOADED from the
   DB. An override of a compiled fn is just a `defn` row that redefines the var when
   eval'd. Restricted to those three forms because they are reload-idempotent (no
   atoms, no top-level side effects) — so the DB layer is always reload-safe.

So the runtime is `compiled package ⊕ DB layer`. The compiled package is the
anything-goes base (rebuilt only when source changes / a new build); the DB is
authoritative for what evolves (agent code + overrides).

### Boot (fresh or resume — identical)

1. **Module-load the compiled package** — kernel + core + third-party all present.
   Nothing to re-load; it is already in the runtime.
2. **Re-derive the DISPLAY index of the compiled code** (core + third-party) into
   the DB — `:seon.fn`/`:seon.ns`/`:seon.schema` rows from compiled var-meta + source
   so the agent can READ its world in `<namespaces>`. This is a DERIVED, read-only
   mirror, rebuilt each boot — it never drifts (re-derived, not a persisted stale
   copy). It is NOT loaded; it is for display/search/override-targeting only.
3. **Eval the DB layer on top** — agent code + overrides (`defn`/`register`/`deftest`)
   from the DB, in dependency order (next section). Overrides redefine compiled vars;
   agent namespaces load with their requires (already satisfied by the compiled base
   or earlier agent nses). This is small — we never re-eval core/third-party.

The ONE edge (not the old reconcile): when a new build changes a core fn an agent
had overridden, "new core source vs the override" is a one-bit choice
(last-write, or keep-the-override) — decide it explicitly; it is not drift.

## Load the DB layer: query → reconstitute → topo-sort → eval

Only the DB layer (agent code + overrides) is loaded — core/third-party are already
present from the compiled package. The DB layer is one or two queries; everything
else is in-memory derivation.

- **One-query pull.** Query the DB-layer `:seon.ns`/`:seon.fn`/`:seon.schema`/
  `:seon.test` rows (source + ns + requires). Group `:seon.fn`/`:seon.schema` by
  namespace in memory. No per-ns queries, no re-parsing source. PROVEN: the
  reconstitution query ran this session in 2 queries (over the whole indexed
  codebase) → 41 namespaces.
- **Reconstitute each DB-layer namespace** = `(ns name (:require <:seon.ns/requires>))`
  + its defs (current source — redefinitions are just the current source). For an
  OVERRIDE of a compiled fn, that's the one `defn` eval'd into the already-loaded
  namespace (redefines the var); for new agent namespaces, the whole reconstituted
  ns. Memoize on max-tx if it ever gets hot.
- **Topo-sort by `:seon.ns/requires`** (the editor / `tools.namespace` model) and
  `eval-str` each whole namespace in that order. `cljs.js`'s load-fn supplies a
  required namespace's source from the DB on demand DURING a load; the ORDER of the
  set being loaded is ours (the requires-topo).
- This DELETES the per-definition replay loop, the tx-order sort, the 2-pass retry,
  and `ensure-target-ns!` — they were hand-rolled approximations of "load these
  namespaces in dependency order."

**Why `cljs.js` `*load-fn*` and not cider/nREPL:** seon ALREADY binds a load-fn
(`eval.cljs:493`) that `cljs.js/eval-str` calls to resolve requires; today it
resolves only from the compiled bundle. The fix is ADDING a DB branch to it
(reconstitute from rows), not a new engine. nREPL `load-file` is whole-file eval
with no ordering; cider delegates to `tools.namespace` (JVM-file-bound). See
`index-and-replay-from-cider-nrepl-2026-06-17.md`.

## The one fix: index `:seon.ns/requires`

- Register `:seon.ns/requires` `[:vector :keyword]`. Confirmed NOT stored today (0
  refs in `src/`); the tee writes only `:seon.ns/name` + `:seon.ns/source`.
- **Capture AND update it at tee.** After EACH successful eval, read the ending
  namespace's `:requires` from the analyzer state (the same post-eval snapshot the
  tee already takes for defs/schemas) and identity-upsert on `:seon.ns/name`
  (replaces the vector). Live: an agent that evals `(ns my.foo (:require [new]))`,
  re-evals the ns form with changed requires, or calls `(require '[new])` at the
  REPL has the index follow. Parse once at eval time; read everywhere; never re-parse
  `:seon.ns/source`.
- The analyzer entry is reliably populated for FRESHLY-EVAL'd agent code; it is EMPTY
  for never-evaled compiled core (LIVE-CHECKED: `seon.ctx` analyzer entry = `{}`),
  but core requires come from re-indexing core source, and core isn't the blocker —
  agent/third-party code is what's replayed.

## Redefinition = upsert (there is NO "override")

Redefine `seon.demo/greeting` exactly like at a REPL: write a new `(defn greeting
…)`. The tee upserts the `:seon.fn` row keyed on the sym (last-write-wins, NO origin
flag, NO override-marked file, NO `SEON_OVERRIDE_DIR`). There is just *the current
source for that sym*. When its namespace next loads, the reconstituted source carries
the new `greeting`; `cljs.js` redefines the var; compiled callers pick it up
(dev-build late-binding). The row IS what's displayed and what's loaded.

Two residual constraints (NOT mechanisms):

- **Re-export aliases.** `(def reply! message/reply!)` captures the value at
  def-time; reloading the DEFINING ns does not update an alias in an un-reloaded ns.
  Fix once: audit `seon.*` re-export aliases and eliminate them (call through the
  defining var).
- **Dev-build invariant.** Late-binding + the `:preloads` merge rely on `goog.DEBUG`
  true / `*cljs-static-fns*` false. Per Sean: NOT a blocker — flag `:advanced` as a
  known future issue.

## Immutability / reload-safety (every namespace re-evals)

Top-level mutable state MUST be `(defonce !x (atom …))`, never `(def !x (atom …))`
(a bare `def` RESETS the atom on every reload, wiping live state). LIVE-CHECKED: the
core already does this — **50 `defonce`** cover all load-bearing state
(`!compile-state`, `!conn`, caches, sessions); only **3 bare `def`-atoms** remain,
all in `seon.dev.replica-peer` (`!handlers`/`!last-db`/`!own-skips`). All code is
immutable by design; the rare needed atom is `defonce`.

## Two write surfaces, two rules

- **Agent evals → only `defn` / `schema/register!` / `deftest`** (code-defining
  forms; #7, hardened). Mutable state goes in a `defonce` atom or the DB, never a
  bare def. Transient inspection expressions still run; they define nothing.
- **Third-party source files → unrestricted** — real `.cljs` with any top-level
  forms, loaded as files, not gated like a single eval.

## Third-party bulk delivery (the only remaining third-party question)

Redefining a core fn a third party ships is just an upsert (above) — no special
path. The ONLY open question is where their BULK new code lives (decision #2):

- **Shadow-compiled (recommended when the downstream HAS a build).** `SEON_EXTRA_SRC`
  + `SEON_EXTRA_PRELOAD` — shadow compiles their source into the bundle alongside
  core; indexed into the DB for display. The plumbing is correct; B2 is operational
  only (preload unset) — fix: start `cljs-watch` with both vars + an entry ns that
  `(reset! seon.client/!extra-core-vars (seon.indexing/specced-fn-vars))`.
- **DB-indexed + eval'd (no-build downstreams / small additions).** seon reads their
  `.cljs`, indexes rows, evals on resume — uniform with agent code (~1–3 s).

## No hardcoded namespace lists

Goal: a downstream shipping 50 namespaces + 30 redefinitions edits ZERO lists.
CORRECTION (live-checked): the bootstrap `:cljs.analyzer/namespaces` is empty stubs
for compiled core, so it is NOT a free generic enumeration.

- `core-ns-set` enumeration stays **var-meta-driven** (`(:ns (meta v))`). The gap is
  fn-LESS roots (`fn-less-compiled-roots #{"my.kb" "my.soul"}`) and unspecced vars
  (`curated-core-vars`). OPEN: a generic compiled-ns enumeration (goog module
  registry, or eager analyzer-cache load) so these two literals vanish — flag,
  don't pretend.
- `included-ns?` CAN drop the `default-included-prefixes ["seon." "my."]` allowlist:
  a ns is included iff it is in `core-ns-set` OR has a `:seon.ns` row, minus
  `*.internal` — so a downstream auto-includes, no config row.

## Display vs load ordering (don't conflate)

One index, two sorts. DISPLAY (`<namespaces>`, transcript) = recency/chronological
so the stable code is a cacheable prompt prefix. LOAD (replay) = dependency order
(topo by `:seon.ns/requires`). Same rows, two sorts, two purposes.

## Usage-example tests (B9)

- An example is the language's `:test` var-meta thunk:
  `(defn f {:test (fn [] (assert (= 4 (f 2 2))))} [a b] (+ a b))` — what `with-test`
  expands to, run by `test-var`. NO new attribute.
- Distinguish example (a `defn` with `:test`) from a `deftest` by FORM HEAD —
  `defn-form?` (already in the codebase from #7). The analyzer collapses both into
  `:test true`, so the marker alone can't tell them apart.
- **Fix the live tee bug first:** a `:test`-bearing `defn` currently loses its
  `:seon.fn` row (the `(not (deftest-def? var-map))` guard) AND is filed as a
  `:seon.test` row. Gate fn rows on `(defn-form? source)` ALONE; test rows on
  `(and (deftest-def? var-map) (not (defn-form? source)))`.
- Tier with two derived section fns: general context renders the inline example next
  to each fn and omits standalone tests; `render-namespace` renders all tests.

## System prompt (B8)

Make the system prompt an IMMUTABLE core function (hardcode `repl-mechanics` +
identity into the `<system>` section), agent-overridable by NOTHING. Agents add
instructions via the existing `my.kb.instruction` → `<instructions>` section. Drop
the `my.soul` data/seed/external-file layer.

## What this DELETES

| Gone | Replaced by |
| --- | --- |
| `:override` origin / target / sort-tier / stacking / `SEON_OVERRIDE_DIR` | redefine = upsert on `:seon.fn/sym` |
| `set!` + alias re-pointing + compiled-`set!` validation | eval the `(defn …)` from the DB (normal redefinition) |
| replay-skip + `core-ns-set` provenance gymnastics | load everything from the DB; core isn't special |
| per-definition replay loop + tx-order sort + 2-pass retry + `ensure-target-ns!` | whole-namespace load in dependency order (requires + load-fn) |
| `reconcile-core!` / `core-index-tx` / `prune-core-ghosts!` + drift | one copy (DB rows ARE what runs) — index source, load it |
| baseline-cache / delta optimization | just eval it all (~1–3 s, measured) |
| `effectful-bare-def?` (done in #7) | classify on form head (`defn-form?`) |

## Live findings & measurements (this session, 2026-06-17 — evidence base)

- Full `cluster reset` → uniform `:core-seed` store (fn 192 / ns 74 / schema 408 /
  test 221; legacy `:substrate-seed` = 0). Code is rename-clean.
- `:cljs.analyzer/namespaces` entries for compiled core are EMPTY (`seon.ctx` →
  `{}`; 56 nses, on-demand). Stored core `:seon.ns/source` is a bare `(ns seon.ctx)`
  stub. Only **9 of 62** stored ns-forms carry `(:require …)`.
- `:seon.ns/requires` is NOT stored (0 refs).
- Reconstitution query works: 2 queries → 41 namespaces, 293,635 chars.
- Perf: **2.81 ms per successful `cljs.js` compile** → ~1–3 s for the whole codebase.
  (A raw 192-fn run was 1.3 s but 189 FAILED — they referenced `str/join`/`db/query`
  with no namespace-require context loaded; this is exactly the missing-requires gap,
  and it proves the model must load WHOLE namespaces with their requires, not per-fn.)
- Atoms: 50 `defonce`; 3 bare `def`-atoms in `seon.dev.replica-peer`.
- Third-party merge is DEAD: `@!extra-core-vars` = 0, `SEON_EXTRA_PRELOAD` unset.

## Phased plan

**Phase 0 — verify before building** (read-only / isolated; "slow is fast"):
- ✅ DONE: analyzer empty-stub + stub-ns-source + requires-not-stored + reconstitution
  query + perf measurement (above).
- TODO: write an agent ns WITH a `(:require …)`, confirm its `:seon.ns/source` stores
  the REAL require form (not a stub) — the load topo depends on it.
- TODO: on an isolated `:memory` conn, prove same-ns forward refs resolve in one bulk
  ns-eval, and a DB `*load-fn*` loads a 2-ns dependency chain.
- TODO: prove resume end-to-end (write agent ns → restart → it returns) — UNexercised
  since the reset wiped agent corpus.
- TODO: decide the generic compiled-ns enumeration (goog dep registry vs eager
  analyzer-cache load) so `core-ns-set` needs no curated list.

**Phase 1 — Index requires + index core→third-party in order, pre-agent.** Add
`:seon.ns/requires` and capture/UPDATE it at tee. Index core → then third-party into
the DB, upserting on `:seon.fn/sym` (third-party wins), as a PRE-AGENT boot step
(fixes B4). DELETE `core-index-tx` + `prune-core-ghosts!`.

- ✅ **Phase 1a DONE (2026-06-17, live-verified):** registered `:seon.ns/requires`
  `[:vector :keyword]` (`seon.ctx`, maps to cardinality-many keyword via the bridge);
  added analyzer-based capture `seon.analyzer-info/ns-requires` (shared `raw-ns-deps`
  extraction with `ns-deps`, aliases dropped via `(vals …)`, ns-names → keywords);
  added the diff-upsert `seon.eval/ns-requires-tx` (cardinality-many ⇒ additions +
  explicit retractions so the stored set tracks the analyzer EXACTLY); wired it into
  the tee at `eval-batch!` for the ending ns on EVERY successful eval (transient
  `cljs.user`/`seon.dynamic` gated out, rides in `record-eval!`'s atomic tx). LIVE
  proof: `(ns probe.req1 (:require [clojure.string :as s]))` → stored `#{:clojure.string}`;
  add `clojure.set` → upsert-only tx, stored `#{:clojure.set :clojure.string}`; shrink
  to `#{:clojure.set}` → retract-only tx, stored `#{:clojure.set}`. Full CLJS suite
  green (527 tests / 2344 assertions / 0 fail). Phase-2 deletion targets UNTOUCHED.
- TODO (rest of Phase 1): index core → third-party pre-agent; DELETE `core-index-tx`
  + `prune-core-ghosts!`.

**Phase 2 — DB `*load-fn*` + whole-namespace dependency-ordered load** (the spine).
Add the DB branch to the `eval.cljs:493` load-fn. Replace `replay-program-graph!`'s
per-definition loop with: one query → reconstitute → topo-sort by `:seon.ns/requires`
→ eval each whole ns. Delete the replay-skip, tx-sort, 2-pass retry,
`ensure-target-ns!`.

**Phase 3 — redefinition fixture** (the always-on proof). A dedicated demo core fn
with sibling fns; a dedicated test build that UPSERTS a new `(defn …)` for it,
reloads its namespace, and asserts: redefinition took effect, siblings byte-identical,
aliases handled. Permanent regression test. NO `:override` origin; revert = upsert the
original back.

**Phase 4 — B2 merge fix + no-hardcoded-ns.** Wire `SEON_EXTRA_PRELOAD` + the entry
ns; derive `included-ns?` from `core-ns-set` ∪ store rows (drop the prefix allowlist);
shrink/remove the curated ns literals.

**Phase 5 — B9 usage-example tests** (the tee-bug fix + `:test` thunk + tiering) and
**B8 system prompt** (immutable core fn; drop `my.soul` data).

**Phase 6 — housekeeping.** Convert the 3 bare `def`-atoms to `defonce` + a
uniformity-canary that flags any new `(def … (atom …))` in reloadable code; alias
audit/elimination; `fs.constants.F_OK`; `:substrate-seed`→`:core-seed` retag migration
only for stores with irreplaceable agent work (fresh = reset, done).

## Open decisions for Sean

1. **Core load — RESOLVED: core is COMPILED (in the package), not DB-loaded.** The
   compiled package = kernel + core + third-party (anything-CLJS); only the DB layer
   (agent + overrides, `defn`/`register`/`deftest`) loads from the DB, on top.
   Earlier "eval all from the DB" is superseded: re-eval'ing core is unnecessary
   (it's compiled) and re-introduces the third-party-atoms / perf problems. Core is
   indexed for DISPLAY only (derived each boot, read-only).
2. **Third-party bulk: shadow-compile (if build) vs DB-index.** Redefinitions are
   always upsert either way (not part of this decision).
3. **Dev-build invariant** — flag `:advanced` as a later issue, not a blocker.

## Risks

- **Bulk-per-ns error granularity** — one bad fn fails its whole ns load. Mitigate:
  per-ns try/catch + a legible "ns X failed at form Y" record; the example tests catch
  regressions.
- **Alias completeness** — an un-eliminated re-export alias silently misses a
  redefinition. Mitigate: the one-time alias audit + a uniformity-canary.
- **Migration** — pre-rename stores stay broken until reset/retag; fresh installs are
  clean.

## Deferred — explicitly OUT of scope for this PRD

- **Indexing more form types in the DB layer (FUTURE).** The DB layer is restricted
  to `defn` / `schema/register!` / `deftest` today. Anything else a third party or
  agent needs (atoms via `defonce`, helper `def`s, arbitrary top-level forms) must
  live in the COMPILED package, not the DB. IF we later want the DB layer to
  faithfully carry arbitrary top-level forms (so DB-delivered / agent code isn't
  limited to those three), we will need to index more form types — or store the full
  namespace source as the loadable unit. NOT now. This keeps the DB layer small,
  reload-safe, and the PRD a few-phase job rather than a "re-index and load the whole
  codebase" project. (Issue raised by Sean 2026-06-17: "we may need to index more
  forms later.")
- Full DB-load of core (recursive self-rewrite mode) — future, not normal boot.
- `:advanced` build support — the dev-build invariant; future, flagged.

## Reference threads (follow all of these)

### Research (this session, source-grounded)

- [[docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md]] — the
  catalog (Findings 1-6, B1-B9) + the live boot-pipeline audit + the locked direction.
  START HERE for the problem space.
- [[docs/prds/agent-runtime/research/index-and-replay-from-cider-nrepl-2026-06-17.md]]
  — THE spine: `cljs.js` `*load-fn*` model, the `eval.cljs:493` hook, the kernel
  boundary, `:seon.ns/requires`.
- [[docs/prds/agent-runtime/research/override-sandbox-verify-2026-06-17.md]] —
  redefinition works via upsert + re-eval; dev-build late-binding proven; revert
  semantics.
- [[docs/prds/agent-runtime/research/build-merge-and-cljs-semantics-2026-06-17.md]] —
  shadow merge plumbing (preload), analyzer-state authority, `*cljs-static-fns*`.
- [[docs/prds/agent-runtime/research/store-model-for-core-projection-2026-06-17.md]] —
  datahike query/retraction/history evidence (NOTE: its persist+reconcile conclusion
  is SUPERSEDED by "DB is the system / no reconcile" — read it for the datahike
  source facts, not the recommendation).
- [[docs/prds/agent-runtime/research/usage-example-tests-and-tiered-context-2026-06-17.md]]
  — `:test` thunk, the tee bug, tiering.

### Concepts (the principles this implements)

- [[docs/seon/concepts/code-as-data-runtime.md]] — the bulk-load-resume model this
  finally implements; source ↔ analyzer ↔ DB.
- [[docs/seon/concepts/reactive-context.md]] — derive-don't-store (display tiering,
  the scratch note).

### Code anchors (current line numbers)

- `src/seon/eval.cljs` — `eval` (518), `raw-eval` (461), the **load-fn binding
  (493)** = where the DB branch goes, `eval-batch!` (1511), `build-tee-entities`
  (919; ns-row creation ~1089 = where `:seon.ns/requires` capture goes; fn gate at
  the `defn-form?` `:when`), `defn-form?` (905), `deftest-def?` (782), `record-eval!`
  (1310).
- `src/seon/client.cljs` — `query-program-graph-entries` (628, replay-skip 665),
  `replay-program-graph!` (789, per-definition `doseq` 831, 2-pass retry 835),
  `replay-one!` (754), `ensure-target-ns!` (722), `core-ns-set` (1039),
  `index-core!` (1284), `core-index-tx` (1408, `have-fns` 1427),
  `prune-core-ghosts!` (1467), `extra-core-vars*` (977), `reserved-extra-nses` (991),
  `read-src-file` (1063), `extract-form-at-line` (1089). Phases 1-2 DELETE most of
  these.
- `src/seon/ctx.cljs` — `namespaces-section` (queries `:seon.ns`/`:seon.fn`),
  `included-ns?` (214), `included-prefixes` (182), `default-included-prefixes` (176),
  `full-source-roots` (234), `format-eval-row` (461, `scratch-def-note` call 539).
- `src/seon/analyzer_info.cljs` — `var-projection` (179), `defs-since` (source of
  the post-eval `:requires` snapshot).
- `src/seon/repl.cljs` — `!compile-state` (76), `!conn` (85), `ensure-bootstrap!`.
- `src/seon/agent.cljs` — `:seon.eval/*` attr regs (181-210), `:seon.eval` entity
  (395), `:seon.fn` entity (411); register `:seon.ns/requires` near the `:seon.ns/*`
  regs in `ctx.cljs` (where `:seon.ns/name`/`:seon.ns/source` live).
- `src/seon/db.cljs` — origin enum (189), `with-tx-context`.
- `src/seon/dev/replica_peer.cljs` — the 3 bare `def`-atoms (202-204).
- `bin/seon` — `SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD` (82-145; preload merge 108,
  classpath 143). `shadow-cljs.edn` — build config.

### Vendored source (read for "how the real systems do it")

- `tmp/cljs-src-1.12.145/cljs/js.cljs` — `cljs.js`: `load-deps`, `*load-fn*`,
  `*loaded*` memoization (291), circular-dep detection (390-403), ns-granular load
  (628-632). (NOTE: extracted to `tmp/` this session; the ClojureScript compiler is
  NOT yet a `reference-code/` submodule — vendoring it is housekeeping.)
- `reference-code/cider-nrepl`, `reference-code/orchard`, `reference-code/nrepl` —
  cider's `tools.namespace` reload (the topo model) for comparison.
- `reference-code/datahike` (+ `datahike-lmdb`) — query/transact/retraction/history.
- `reference-code/shadow-cljs` — preload / `:entries` / DCE.

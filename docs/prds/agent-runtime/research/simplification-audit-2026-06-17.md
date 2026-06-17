---
type: research
status: active
tags: [research, agent, cljs]
---
``
# Simplification audit — per-function override needs no new mechanism

## TL;DR

The six biggest simplifications, ranked by leverage:

1. **Per-function override needs NO new attr and NO new sort-tier.** Datahike
   identity-attr upsert on `:seon.fn/sym` already gives last-write-wins
   overwrite, and bitemporal history already retains every prior version for
   free. The ONLY real obstacle to override is the replay-skip at
   `client.cljs:665`, which drops every substrate-ns row from the replay set so
   a stored `(defn …)` can't shadow the compiled var. The minimal fix is to let
   a substrate-ns row replay when a NON-substrate-origin source is stored for
   that sym — i.e. compare provenance on the tx, not add a new
   `:seon.fn/override-target` attribute. The PRD's stated obstacle
   ("`index-substrate!` re-upserts the compiled version on every boot, bumping
   its tx PAST any stored override") is FALSE for `:seon.fn` — see Finding 3.

2. **Per-`:seon.fn` boot-index is ALREADY seed-if-absent.** `substrate-index-tx`
   (`client.cljs:1456-1464`) dedups `:seon.fn` rows purely on `:seon.fn/sym`
   PRESENCE (`have-fns`, `client.cljs:1427`). Once a sym exists in the store the
   boot index never re-transacts it. So a stored override row is never clobbered
   by a boot re-index; the override simply never gets a chance to REPLAY. This
   removes the entire premise of the proposed override sort-tier and the
   "override replays after index-substrate!" ordering language in the PRD.

3. **`:source` is already a single byte-faithful form, not a multi-form eval
   blob with comments.** `seon.repl.internal/parse-forms` splits the LLM reply
   form-by-form; comments become `:narration` (a separate field, never stored as
   `:source`), and each `:seon.eval`/`:seon.fn`/`:seon.test` row's `:source` is
   ONE form's exact text (`internal.cljc:14-18`, "BYTE-FAITHFUL"). The "replay
   the whole eval text including comments and `(comment …)`" fragility the user
   fears is mostly already prevented by the parser. The residual fragility is
   narrower than feared: a single non-`defn`/`def` top-level form (bare
   `(def x …)`, an arbitrary top-level call) can still be teed+replayed. See
   Finding 1/2.

4. **Classify on the FORM HEAD, delete the `effectful-bare-def?` heuristic.**
   The strict "persist+replay ONLY `defn` / `schema/register!` / `deftest`"
   rule subsumes `effectful-bare-def?` (`eval.cljs:956`) and its two call sites
   (`eval.cljs:1001`, `client.cljs:680`) entirely. Under the strict rule a bare
   `(def x (transact! …))` is never persisted, so there is nothing to scan for
   "effectful init calls". The whole `effectful-call-syms` /
   `form-calls-effectful?` / `bare-def-effectful?` / `read-all-forms` block
   (`eval.cljs:900-966`) is dead. The `:fn-var?` classifier (true for BOTH
   `defn` and `(def f (fn …))`, `analyzer_info.cljs:194`) is too coarse for this
   — classify on `(first form)` being `defn`/`defn-`, `schema/register!`, or
   `deftest`.

5. **No reinvention of versioning/history was found — but the PRD would
   INTRODUCE one.** Today's code correctly leans on upsert+history. The
   `overridable-substrate` PRD's `:seon.fn/override-target` + override sort-tier
   + "two packages overriding the same target is a conflict the install verb
   must refuse" machinery is exactly the "new mechanism to override functions"
   the user vetoed. Delete it; keep upsert + replay-the-latest + provenance.

6. **`core-index-tx` + `prune-core-ghosts!` are one reconcile split in two**
   (Finding 6). The boot indexer adds, partially drift-heals (ns/schema yes,
   fn/test NO — a latent staleness bug), and prunes deletions as three separate
   passes with four divergent per-kind rules. Collapse into ONE provenance-keyed
   `reconcile-core!` (upsert the built index, retract absent `:core-seed` rows) —
   deletes a function, fixes the drift hole, and is the SAME provenance gate #8
   override needs. Sequence it before #8.

## Live boot-pipeline audit (2026-06-17 PM) — build/merge/index fragilities

A live audit of the build → merge → index → seed → startup pipeline on the
running pod, including a full `bin/seon cluster reset default` (fresh-from-0
store). Every item is backed by a live query or boot-log line. CATALOG ONLY —
fixes deferred ("we'll think about how to fix everything").

### B1 — substrate→core origin literal was never migrated (fixed for fresh; existing stores stranded)

SMELL: the substrate→core rename (`41fccf0`) changed the tx-origin literal in
code (`:substrate-seed` → `:core-seed`) but shipped NO store migration.

EVIDENCE (pre-reset live store): core rows tagged `:substrate-seed` — fn **189**,
ns 54, schema 340, test 168 — vs `:core-seed` fn **0**, ns 21, schema 72, test 54.
Every provenance-keyed mechanism queries ONLY `:core-seed`, so against a
pre-rename store: `prune-core-ghosts!` saw 0 candidates (GC dead),
`namespaces-section` seed-tx classification saw 4 of 21 txs (the bulk of core
misclassified as agent-authored), the #8 override replay-skip would mis-key.

POST-RESET: re-seed is uniform — legacy `:substrate-seed` = **0**, all kinds
`:core-seed` (fn 192, ns 74, schema 408, test 221). The CODE is fully
rename-clean (zero `substrate` in `src/`; enum is `:core-seed` only, so a
`:substrate-seed` row can't even be written now).

RESIDUAL: fresh installs are clean; any EXISTING pre-rename store is silently
broken until wiped. No one-shot retag migration exists (same gap class as #7's
missing "one-release migration filter for poisoned stores").

### B2 — third-party merge is NON-FUNCTIONAL (the downstream half of the pipeline never runs)

SMELL: `SEON_EXTRA_SRC` is set (points at a downstream deps.edn project with
`<root>/src/<prefix>/*.cljs`), but NO downstream ns is compiled, merged, or
indexed.

EVIDENCE: `@seon.client/!extra-core-vars` count = **0**; `extra-core-ns-strs` =
`#{}`; `core-ns-set` has zero non-`seon.*`/`my.*` namespaces. The downstream
source files DO exist on disk under the configured root.

ROOT CAUSE: the merge needs TWO env vars; only one is set. `SEON_EXTRA_SRC` adds
the downstream as a `-Sdeps :local/root` to the **`cljs-watch`** build
(`bin/seon:143`); `SEON_EXTRA_PRELOAD` appends the downstream entry ns to
`:devtools :preloads` (`bin/seon:108`) — but `SEON_EXTRA_PRELOAD` is **unset**,
so nothing `require`s the downstream code, it never compiles into the bundle, its
vars never register into `!extra-core-vars`, and the boot index has nothing to
merge. Also the build-path injection rides `cljs-watch` (143), NOT the `pod`
command (142) — a pod-only env does nothing; the running `cljs-watch` process
must itself have been started with BOTH vars.

IMPACT: the intended pipeline ("compile core + third-party, merge, index = the
seed") does only the CORE half today. The third-party function-override demo
cannot run until this is wired (set `SEON_EXTRA_PRELOAD`, restart `cljs-watch`
with both vars, rebuild).

### B3 — core fn indexing IS complete (correcting an earlier false alarm)

A raw `(count (index-core!))` = 234 vs 192 stored looked like a 42-row gap. It is
not: DISTINCT `:seon.fn/sym` fresh = 193, stored = 192, set-difference = 1, and
that 1 is `nil` (a non-fn entry in the index row vector). Core fn indexing is
COMPLETE on a fresh seed. Lesson: count distinct syms, not raw index rows.

### B4 — the boot seed/index runs INSIDE the minted agent's scope (origin-forge warnings)

SMELL: 5× boot warnings `agent-scoped tx claims :seon.db/origin :core-seed — core
provenance from inside an agent scope (warn-only; see warn-on-seed-origin-forge!)`
(agent `nZt-…`, count 1–5; `logs/pod.log`, fresh boot).

ROOT: `start-agent!` mints an agent and the core seed/index transacts (claiming
`:core-seed`) execute within that agent's `with-tx-context` scope, so the forge
guard — built to stop AGENTS claiming core provenance — fires on the legitimate
seed. The seed is conceptually a pre-agent CORE step but is sequenced inside
agent boot. Build/merge/index (core, once) is entangled with start-agent
(per-agent), the exact conflation that makes "startup with 0 additional work"
hard to reason about.

### B5 — resume does not re-sync core code changes (drift), confirmed live

SMELL: after the #7 edits + a restart WITHOUT a wipe, the new public fn
`seon.eval/defn-form?` had NO `:seon.fn` row (in-fresh? true, in-store? false),
and `:core-seed` fn rows = 0 on that store. New/changed core defs were not
reflected on a resume boot.

ROOT: the persisted core projection is add-/drift-only and incomplete on resume
(Finding 6): `:seon.fn`/`:seon.test` dedup on PRESENCE (a changed body keeps
stale source), and the resume path does not re-run a full reconcile. Only a wipe
produces a correct projection.

### B6 — root cause tying B1/B4/B5 + Finding 6 together

All of the above are symptoms of how the core program-graph (a pure projection of
compiled+merged source) is reconciled into the store. The tempting fix — make
core EPHEMERAL (rebuild each boot, DB holds only agent code) — was RESEARCHED and
REJECTED (see `store-model-for-core-projection-2026-06-17.md`): the pod's wire
model needs core in the SHARED central store (a local-only ephemeral core means
every other reader/peer sees no core, and core history leaves datahike for git),
and the render consumers do NOT issue corpus-wide Datalog joins (they single-
pattern query + join in CLJS maps), so ephemeral buys them nothing while forcing a
two-source merge into every render site. **Resolution: KEEP persisting core; the
analyzer-rebuilt index (cheap, sub-ms — `cljs.analyzer/:cljs.analyzer/namespaces`,
already done by `analyzer_info`) is the INPUT to ONE drift-keyed `reconcile-core!`
(upsert rows whose stored source differs, `:db/retractEntity` `:core-seed` rows
absent from the build).** Drift-keyed, NOT blind retract-all+re-add: under
`:keep-history? true` a blind re-assert writes 2 temporal datoms per row into LMDB
every boot forever; drift-keyed emits zero on a steady-state boot. This single
pass dissolves B1, B5, Finding 6; B4 is fixed by running it as a pre-agent phase.

### B7 — override mechanism: sandbox-validated, two corrections

See `override-sandbox-verify-2026-06-17.md`. ZERO new attributes (re-index does
not bump a stored override; only obstacle is the blanket replay-skip line — delete
`:seon.fn/override-target` + sort-tier + stacking-conflict, Findings 3/5 confirmed
empirically). CORRECTION: revert is `:db/retractEntity` + reseed, NOT
`[:db/retract … :seon.fn/source <v>]` (that leaves no current source) — the
"history rolls it back" phrasing was mechanically wrong. `:override-dir` is absent
from the origin enum (`db.cljs:189`); add it if that vehicle wants its own
provenance.

### B8 — extensibility surface (design, for the fix pass)

- `my.soul` stores the system prompt as agent-editable DATA seeded from an
  external file. Decision (Sean): the system prompt is IMMUTABLE core (a
  function), agent-overridable by NOTHING; agents add instructions via the
  existing `my.kb.instruction` → `<instructions>` section. Drop the soul
  data/seed/file layer.
- "Hooks everywhere" — the on-reply seam registry (#27), the soul seed hook, the
  `register!` self-tee hook — collapse into the ONE function-override mechanism
  wherever they are extensibility seams (internal require-cycle hooks excepted).

### B9 — usage-example tests via fn metadata + tiered context surfacing (improvement wanted, Sean 2026-06-17)

SMELL: a large share of the agent context is TESTS (241 indexed). Most are unit
tests the agent does not need in the always-on general context.

WANT:
- Agents should write tests, specifically a USAGE-EXAMPLE test per fn — a runnable
  "here is how you call it" example — attached the PROPER Clojure way via FUNCTION
  METADATA (research the idiom in `clojure.test`/`cljs.test` source: `:test`
  var-meta, `with-test`, `test-var`; find the canonical mechanism, don't invent a
  `:seon.*` attr if the language already has one).
- Surface ONLY usage-example tests into the GENERAL context (every-turn surface) —
  usage examples teach call-shape without dumping the whole suite.
- In NAMESPACE context (the agent switches into a specific ns to work) — show ALL
  tests for that ns.

RESEARCH: the canonical Clojure metadata mechanism for an attached runnable
example, and how seon's test indexing (`eval.cljs` `deftest-def?`, `:seon.test`
rows) + context render (`ctx.cljs` namespaces/tests surface) should distinguish
usage-example vs full tests and tier them (general = examples, ns-focus = all).

### Minor

- Node `fs.F_OK is deprecated` DeprecationWarning at boot (DEP0176) — cosmetic;
  switch to `fs.constants.F_OK`.

## Synthesis & fix plan (post-research, 2026-06-17)

Three source-grounded research docs now back the fixes:
`store-model-for-core-projection-2026-06-17.md` (datahike),
`build-merge-and-cljs-semantics-2026-06-17.md` (shadow-cljs + cljs compiler),
`usage-example-tests-and-tiered-context-2026-06-17.md` (clojure.test/cljs.test).
Two priors were OVERTURNED by source: "go ephemeral" (rejected — persist +
reconcile) and "add `:seon.fn/override-target`" (rejected — zero new attrs).

Cross-cutting INVARIANT (load-bearing): the pod MUST stay dev-compiled
(`goog.DEBUG` true, `*cljs-static-fns*` false). BOTH the third-party `:preloads`
merge AND per-fn override die under `:advanced`/`:static-fns` (call sites inline,
the global read disappears). Assert `goog.DEBUG` before allowing override.

Sequenced plan:

1. **`reconcile-core!`** — collapse `core-index-tx` + `prune-core-ghosts!` into ONE
   drift-keyed pass (analyzer-rebuilt index → upsert-on-source-diff +
   `:db/retractEntity` absent `:core-seed` rows). Fixes B5/Finding-6/B1-staleness.
   Land FIRST — #8 reuses its `:core-seed` provenance gate.
2. **Pre-agent boot phase** — run seed/reconcile before `start-agent!`, own
   `:core-seed` tx-context → kills the B4 forge warnings.
3. **B2 merge** — operational: start `cljs-watch` with `SEON_EXTRA_SRC` +
   `SEON_EXTRA_PRELOAD` + a downstream entry ns that
   `(reset! seon.client/!extra-core-vars (seon.indexing/specced-fn-vars))`.
   Plumbing is already correct.
4. **#8 override** — lift the replay-skip to the provenance test (drop a core-ns
   row only when current source origin IS `:core-seed`); assert `goog.DEBUG`;
   revert via `:db/retractEntity`; add `:override-dir` to the origin enum.
   DELETE `:seon.fn/override-target` + sort-tier + stacking (Findings 3/5).
5. **B9 tests** — `:test` var-meta thunk = the usage example (no new attr); fix the
   live tee bug (a `:test`-bearing `defn` currently loses its fn row — gate fn rows
   on `(defn-form? source)` ALONE, test rows on
   `(and (deftest-def? var-map) (not (defn-form? source)))`); tier via two section
   fns (general = inline examples, ns-focus = all tests), derived.
6. **B8 system prompt** — hardcode as immutable core fn; agents add instructions via
   `my.kb.instruction` → `<instructions>`; drop the `my.soul` data/seed/file layer;
   override only via compiled-merge (step 3/4).
7. **Housekeeping** — vendor `clojurescript` (compiler) + `clojure.test`/`cljs.test`
   into `reference-code/` (absent today; extracted to `tmp/` by the research);
   `fs.constants.F_OK`.

## Direction (Sean, 2026-06-17): the database IS the running system

Locked INTENT (exact mechanics pending the CIDER/nREPL research): everything
above the irreducible bootstrap kernel is INDEXED into the DB and LOADED from the
DB via dependency-ordered replay — core, third-party merge, and agent code
UNIFORMLY. The compiled package is only the BOOTSTRAP + SEED; once booted, the DB
is authoritative and replay reconstitutes the full running system.

Consequences (these REPLACE several earlier findings):
- An OVERRIDE is an UPSERT of the target fn's `:seon.fn` row — NOT a `set!` we
  parse. This kills the Q1 problems (program-graph divergence, alias re-pointing,
  unverified compiled-`set!` semantics): the row IS the source of truth, replay
  loads the latest, last-write-wins on `:seon.fn/sym`.
- No replay-skip, no reconcile-vs-display split, no `:core-seed` provenance
  gymnastics — the DB rows ARE the system, not a mirror to keep in sync. This
  supersedes Finding 6 / B6 (reconcile) and most of B7 (override replay-skip).
- Replay must be PROPER dependency-ordered full-namespace load (load-file style),
  NOT the current per-definition + tx-order + retry hack
  (`replay-program-graph!`). The code-as-data concept doc already prescribes this;
  it was never implemented.

The ONE physics-bound exception to "no exceptions": the eval / db / replay /
schema / cljs-compiler KERNEL must be compiled to load anything from the DB it
has not loaded yet. Minimize that kernel; DB-load everything above it.

OPEN (the research must pin): the right INDEX shape (what to store per fn/ns so
dependencies are queryable) and the right dependency-ordered REPLAY mechanism,
grounded in how CIDER / nREPL / `cljs.js` self-host actually load code — not our
reconstruction.

## Finding 1 — what `:seon.fn/source` actually stores

What `:source` is, by path:

- **Agent eval (detect-and-tee).** `eval-batch!` evals one `parse-forms` entry at
  a time; `source` is that entry's byte-faithful single-form text
  (`eval.cljs:1654` binds `source` from the entry, `eval.cljs:1715` passes it to
  `build-tee-entities`, `eval.cljs:1037` sets `:seon.fn/source source`). Comments
  are NOT in `source` — they are accumulated into `:narration`
  (`internal.cljc:14-18`, `internal.cljc:243-246`). Blank lines between forms are
  not in `source` either (the parser tokenizes form-by-form). So an agent eval of
  `;; comment\n(defn f [x] …)\n(defn g [y] …)` produces TWO `:seon.fn` rows, each
  carrying exactly one `(defn …)` text, narration on the side.
- **Boot reindex (compiled substrate).** `var->fn-row` (`client.cljs:1233`) reads
  the source FILE and slices exactly the top-level form at the var's
  `:file`/`:line` via `extract-form-at-line` (`client.cljs:1089`, paren-balanced,
  reader-free). This is ALWAYS one clean defining form — never multi-form, never
  comments.

So the corpus is already close to "one canonical defining form per identity". The
gap: the agent-eval path stores whatever single form was evaled, and a single
form can be a non-defining form (a bare value `def`, or a top-level call that
happens to define nothing — though a non-defining form produces no `new-defs` and
so no `:seon.fn` row; see Finding 2 for the bare-`def` case that DOES produce a
row).

Quote (`eval.cljs:1031-1042`, the tee row):

```clojure
(cond-> {:seon.fn/sym        sym
         :seon.fn/ns         {:seon.ns/name (keyword (str ns))}
         :seon.fn/source     source        ; <-- the per-form eval text
         :seon.fn/fn-var?    fn-var?
         …}
  …)
```

RISK / why it exists: `source` must be byte-faithful for resume re-eval
(`internal.cljc:16-17`). That is correct and should stay. The simplification is
not "stop storing source" — it is "only CREATE a `:seon.fn` row when the form is a
`defn`", so the stored source is always a defining form.

## Finding 2 — fragility from replaying non-declarative content

Everything currently teed+replayed that is NOT a clean `defn`/`register!`/`deftest`:

- **Bare `(def x <pure-value>)`.** Produces a `:seon.fn` row with
  `:fn-var? false` (`var-projection`, `analyzer_info.cljs:194`). Replayed on every
  boot. Failure mode: low — re-evaling a pure value def is idempotent. But it
  shadows nothing useful and pollutes the corpus; under the strict rule it should
  be warned-not-persisted (a `def` of a value is state, not a function — if it
  must survive, it belongs in a datom, not the program graph).
- **Bare `(def x (some-call …))` with an EFFECTFUL init.** This is the
  ghost-message bug (#29). Re-evaling on boot/mint RE-FIRES the side effect
  (`message!`/`reply!`/`transact!`) — a stored `(def _ (reply! "hi"))` re-sends
  the message every boot. Currently guarded by `effectful-bare-def?`
  (`eval.cljs:956`) at tee time (`eval.cljs:1001`) AND replay time
  (`client.cljs:680`). Under the strict "only `defn`" rule this entire class is
  never persisted, so the heuristic is dead (Finding 5).
- **`(def f (fn …))`.** `:fn-var? true`, so it looks like a fn but is a bare def
  whose head is `def`, not `defn`. Replaying it works, but the PROOF-3 alias
  hazard in the override PRD is exactly this shape: a `def` captures a VALUE at
  def-time, so it does not participate in late-bound override the way a `defn`
  var does. Recommend: classify on form head — a `(def f (fn …))` is NOT a
  `defn` and should warn ("write `(defn f …)` so it is overridable and replays
  as a fn").
- **Multi-form eval text.** Not reachable via the agent turn loop (parse-forms
  splits per form). Still theoretically reachable if `build-tee-entities` is
  ever called with a multi-form `source` (e.g. a future bulk path) — note that
  `effectful-bare-def?` ALREADY uses `read-all-forms` (`eval.cljs:943`) to scan
  multiple forms, implying the author anticipated multi-form sources. Under the
  strict rule, the row creator should refuse a `source` whose `read-all-forms`
  yields anything other than a single recognized defining form.
- **Top-level arbitrary calls.** A bare `(start-something!)` defines nothing, so
  `defs-since` yields no `new-defs` and no `:seon.fn` row is created. Not a
  replay hazard. (Its effect ran once, at eval time, and is recorded only as a
  `:seon.eval` row, which is never replayed — correct.)

RISK / why some of this exists: the `:fn-var?` attribute is genuinely useful for
RENDERING (distinguishing fn vars from value vars in the namespaces tile), so
don't delete `:fn-var?`. The change is at row-CREATION: gate on the form head, not
on `:fn-var?`.

## Finding 3 — substrate-vs-agent special-casing, and the REAL override obstacle

Two paths produce `:seon.fn` rows:

- **Boot reindex** — `index-substrate!` (`client.cljs:1284`) →
  `var->fn-row` from compiled var meta + file-read, transacted under
  `:seon.db/origin :substrate-seed`. Deduped by `substrate-index-tx`
  (`client.cljs:1408`) on `:seon.fn/sym` PRESENCE.
- **Agent eval tee** — `build-tee-entities` (`eval.cljs:968`), origin `:agent`.

The replay-skip (`client.cljs:665`):

```clojure
(remove #(contains? (substrate-ns-set) (entry-ns-kw %)))
```

drops every row whose owning ns is a substrate ns, BEFORE replay. Its stated
reason (`substrate-ns-set` docstring, `client.cljs:1046-1048`): re-evaling a
substrate row's source would shadow the real compiled fn. That is the SAME root
as why override is hard — the system deliberately refuses to let a stored source
shadow a compiled substrate var.

The PRD claims the obstacle that forced `:seon.fn/override-target` is that
`index-substrate!` re-upserts the compiled version every boot, bumping its tx past
any stored override. **This is refuted by the code.** `substrate-index-tx` dedups
`:seon.fn` on sym presence (`client.cljs:1457`):

```clojure
(or (contains? have-fns (:seon.fn/sym row)) …)   ; row dropped if sym already present
```

So once a sym exists in the store, the boot index does NOT re-transact it and does
NOT bump its tx. The compiled var wins at boot for ONE reason only: the override's
source is never replayed (the substrate replay-skip). Module load defines the
compiled var; nothing re-evals the stored source over it.

**Answer to Q3: per-function override can be achieved with ONLY upsert + replay,
with NO new attr and NO new sort-tier.** The minimal change:

1. **Replay substrate-ns rows whose CURRENT `:seon.fn/source` was written by a
   non-substrate origin.** The replay-skip should drop a substrate-ns row only
   when its current source's tx carries `:seon.db/origin :substrate-seed` (the
   same provenance test `tee-registered-schema!` already does for schemas at
   `eval.cljs:1170`, and `prune-substrate-ghosts!` does at `client.cljs:1501`).
   An overridden fn's latest source datom was written by `:agent`/`:override-dir`
   /`:repl` origin, so it replays AFTER module load and shadows the compiled var.
   The compiled fn re-wins automatically on retract: retract the override source,
   the current source datom reverts to the substrate-seed one (history), the
   provenance test drops it from replay, the compiled var stands. This is the
   "datahike does time travel on its own" property the user pointed at.

2. **Replay ordering.** No new tier needed. `:ns`-first then tx-order
   (`client.cljs:693`) already replays def-shaped rows after their ns. An override
   of a substrate fn replays in its tx position, which is after the substrate ns
   was indexed (substrate index runs before replay,
   `client.cljs:1978`→`1994` order is prune → replay, and `index-substrate!` runs
   inside the per-agent boot — but the compiled var exists from module load
   regardless, so ordering vs the index tx is irrelevant). The override just needs
   to run after module load, which all replay does.

What this lets us DELETE from `overridable-substrate-2026-06-17.md`:

- `:seon.fn/override-target` and `:seon.fn/override-origin` schema registrations
  (PRD §"Data shapes"). Provenance is already on the tx (`:seon.db/origin`).
  Reuse it.
- The override sort-tier extension to `client.cljs:693` (PRD §"How replay-skip
  changes", tier 2). Not needed — Finding 3 step 2.
- The "two packages overriding the SAME target is a conflict the install verb
  must refuse" stacking logic (PRD §"Composition"). Upsert is last-write-wins by
  design; that IS the semantics. The user explicitly wants "upsert the original
  function and call it a day."
- The separate `:seon.fn/override-target`-present test driving replay-skip; it
  collapses into the origin-provenance test.

RISK / what genuinely stays: the SEAM/hook registry for AUGMENTATION (#27,
`fire-on-reply-hooks!`) is NOT a versioning reinvention — it is a legitimate
late-bound extension point and composes cleanly across N packages where override
(last-write-wins) cannot. Keep it; it is orthogonal to the override mechanism.
Also keep the kernel-target caution (overriding `seon.eval/eval` etc. can no-op on
the next boot) — that is a real chicken-and-egg constraint, but it needs only a
loud warning, not a new attribute.

## Finding 6 — `prune-core-ghosts!` is the deletion arm of a reconcile that should be ONE pass

(Added 2026-06-17 PM, from a code-audit pass over the boot indexer.)

The persisted core program-graph is a PROJECTION of the compiled source, kept in
the durable store so the `<namespaces>` render and cross-agent queries see core +
agent corpus uniformly. Keeping that projection equal to the current compiled
index is ONE conceptual operation — "reconcile `:core-seed`-origin rows to the
freshly-built index" — but today it is THREE half-measures with FOUR divergent
per-kind rules:

- **Additions** — `core-index-tx` (`client.cljs:1408`) emits only rows whose
  ident is absent on the conn.
- **Drift (source changed)** — INCONSISTENT: `:seon.ns` and `:seon.schema` rows
  re-emit when their stored source differs from the build (drift-heal,
  `1428-1454`); `:seon.fn` and `:seon.test` rows dedup on PRESENCE only
  (`1427`/`1455`), so an edited core `defn` body keeps its STALE
  `:seon.fn/source` row forever. Latent: edit a core fn, and its stored
  program-graph source no longer matches the code.
- **Deletions** — `prune-core-ghosts!` (`client.cljs:1467`), a SEPARATE boot pass
  retracting `:core-seed`-origin rows whose ident left the index.

`prune-core-ghosts!` is not a smell in isolation — it is a SYMPTOM: the deletion
arm bolted onto an add-only indexer. Both functions already gate on the SAME
`:seon.db/origin :core-seed` provenance and the SAME `registration-call-source?`
agent-corpus carve-out; they are one mechanism split in two.

**Recommendation: collapse `core-index-tx` + `prune-core-ghosts!` into one
`reconcile-core!` pass** that makes the set of `:core-seed`-origin program-graph
rows EXACTLY equal the freshly-built index — identity-upsert every built row
(heals drift UNIFORMLY across all four kinds, fixing the fn/test staleness gap)
and retract every `:core-seed` row whose ident is not in the built set (subsumes
prune). Agent-origin rows (tee / replay / `(seon.schema/register! …)` call rows)
are untouched, exactly as both fns already do. One pass deletes a function,
removes the four divergent dedup rules, and closes the latent fn/test drift hole.

**Constraint (do NOT reintroduce):** `core-index-tx` made `:seon.fn` presence-only
deliberately, to dodge the Run-3 "malformed `:seon.fn/ns` value" re-seed bug
(docstring `1419-1421`). A reconcile that re-upserts fn rows every boot MUST emit
a correct `:seon.fn/ns` — the nested-map upsert `{:seon.ns/name <kw>}`
(`eval.cljs:1036`). Fix that root cause; do not restore presence-only as a
workaround.

**Ties to #8 override:** the reconcile keys on `:core-seed` provenance, and so
does the audit's replay-skip lift (Finding 3) — an override row is agent-origin,
so the reconcile never retracts it and the replay-skip lift lets it shadow the
compiled var. Same provenance mechanism viewed twice (code-as-data:
[[docs/seon/concepts/code-as-data-runtime]]). Sequence the reconcile BEFORE
building #8 so the override path inherits one clean core-projection rule, not two.

## Finding 4 — other reinventions of DB primitives (swept)

- **Manual versioning / history tracking** — NONE found in the live code. Upsert
  + bitemporal history is used correctly throughout (the `substrate-ns-set`
  docstring and `query-program-graph-entries` both read the CURRENT db and rely on
  history for superseded sources, `client.cljs:631-633`). The ONLY proposed
  reinvention is the override PRD (Finding 3). Grade: code PASSES the
  code-as-data rubric ("identity-attr upsert means redefinitions replace; history
  retains prior versions", concept doc L65-66); PRD FAILS it.
- **Source re-parsing with rewrite-clj where the analyzer has the data** — only
  `seon.repl.internal` (parse-forms, the byte-faithful per-form SPLIT — a
  legitimate use, not program-graph extraction) and `seon.repl`. Program-graph
  extraction is analyzer-driven (`analyzer_info/defs-since` + `var-projection`,
  `eval.cljs:983`). Grade: PASSES the code-as-data rubric ("the analyzer state IS
  the authoritative view … Source re-parsing throws away information", concept
  doc L130-133). Note: `effectful-bare-def?` DOES re-parse `source` with
  tools.reader (`read-all-forms`, `eval.cljs:943`) — a minor violation that
  vanishes when the heuristic is deleted (Finding 5).
- **Parallel registries / counters for derivable state** — NONE found in the
  audited paths. The publish gate and "pending gate" surfaces are described as
  reactive section functions (concept doc L84-97), not stored flags. Grade:
  PASSES.
- **`*-v2` / parallel structures** — NONE.
- **Stored fast path + derived slow path** — NONE in the audited replay/index
  paths. `substrate-index-tx`'s ns/schema "re-emit when stored source differs"
  (`client.cljs:1428-1454`) is a HEAL of stale stored source toward the build, not
  a bifurcation; it is keyed on identity upsert. Acceptable.
- **Acknowledgement / "mark seen" state** — NONE.

## Finding 5 — `effectful-bare-def?` is dead under the strict model

`effectful-bare-def?` (`eval.cljs:956`, committed in #29) exists ONLY to stop a
bare `(def x (reply! …))` from being teed+replayed and re-firing its side effect.
Under the strict "persist+replay only `defn` / `schema/register!` / `deftest`"
rule, a bare `def` (effectful or not) is never persisted as a `:seon.fn` row, so:

- The tee-time guard (`eval.cljs:1001`) is subsumed by the form-head gate.
- The replay-time belt-and-suspenders (`client.cljs:680`) still has value for
  ONE boot — DEPLOYED stores carry historical bare-def poison rows. Keep the
  replay-time `remove` as a one-release migration filter, then delete it once
  stores are known clean (or fold it into "replay only rows whose source head is
  `defn`/`register!`/`deftest`", which drops bare-def rows for free without the
  effectful scan).
- The whole `effectful-call-syms` / `form-calls-effectful?` / `bare-def-effectful?`
  / `read-all-forms` block (`eval.cljs:900-966`) becomes dead and is deleted.

Confirmed: under the simpler model `effectful-bare-def?` is dead. The only
nuance is the one-release migration window for already-poisoned stores, which the
strict-head replay filter handles more simply than the effectful scan.

## Canonical replay model — recommendation

Store and replay ONLY a canonical defining form per identity:

1. **Create a `:seon.fn` row only when the eval'd form's head is `defn` or
   `defn-`.** Classify on `(first (first (read-all-forms source)))`, with a
   guard that there is exactly one top-level form. A `(def …)` (value or
   `(def f (fn …))`), a top-level call, or a multi-form source produces a WARN,
   not a row. (`:fn-var?` stays as a render attribute, but it no longer gates
   row creation.)
2. **`:seon.schema` rows** continue to store the replayable
   `(seon.schema/register! …)` call form (already enforced by
   `registration-call-source?`, `client.cljs:671-672`). Boot-indexed shape
   literals are rebuilt from the live registry, not replayed (already correct).
3. **`:seon.test` rows** store the single `(deftest …)` form (already correct,
   `eval.cljs:1084-1088`, classified via `deftest-def?` on the analyzer's
   top-level `:test true`, `eval.cljs:802`).
4. **Replay** re-evals only those rows, `:ns`-first then tx-order (already
   correct, `client.cljs:693`). Substrate-ns rows replay IFF their current source
   was written by a non-`:substrate-seed` origin (Finding 3) — that single
   provenance test delivers per-function override with no new attribute.
5. **Override = upsert.** An agent or consumer redefines `seon.agent.message/reply!`
   by evaling a new `(defn reply! …)` into that ns. The tee upserts `:seon.fn/sym`
   "seon.agent.message/reply!" with origin `:agent`/`:override-dir`. Next boot,
   the provenance test lets it replay and shadow the compiled var. Override a
   thousand times — each is one upsert; history holds every prior version for
   free. Retract = retract the override source datom; the substrate-seed source
   becomes current again and the compiled var stands.

The one genuine caveat to carry forward (not a simplification, a constraint):
re-export ALIASES (`(def reply! message/reply!)` in `seon.agent`) capture the
value at def-time and do NOT track an override of the defining var (PRD PROOF 3).
This is independent of the storage model — it is a CLJS late-binding fact. Tag or
eliminate such aliases regardless of which override mechanism is chosen; it is the
one piece of the override PRD worth keeping (as a one-line alias audit, not a new
attribute).

## Cross-references

- `docs/seon/concepts/code-as-data-runtime.md` — the rubric ("identity-attr
  upsert means redefinitions replace; history retains prior versions").
- `docs/seon/concepts/reactive-context.md` — the rubric (no stored flags;
  pending-gate is a section fn).
- `docs/prds/agent-runtime/overridable-substrate-2026-06-17.md` — the PRD this
  audit recommends shrinking (delete `:seon.fn/override-target`, the sort-tier,
  the stacking-conflict logic; keep the on-reply seam + alias caveat).
- `src/seon/eval.cljs` — `effectful-bare-def?` (`956`), `build-tee-entities`
  (`968`, `:seon.fn/source source` at `1037`), tee call (`1711-1716`).
- `src/seon/client.cljs` — `query-program-graph-entries` (`628`, replay-skip
  `665`, `#29` filter `680`), `var->fn-row` (`1233`, single-form file slice),
  `index-substrate!` (`1284`), `substrate-ns-set` (`1039`), `substrate-index-tx`
  (`1408`, sym-presence dedup `1457`), boot order (`1978`→`1994`).
- `src/seon/repl/internal.cljc` — `parse-forms` (byte-faithful per-form split,
  comments → `:narration`, `14-18`).
- `src/seon/analyzer_info.cljs` — `var-projection` (`:fn-var?` true for both
  `defn` and `(def f (fn …))`, `194`).

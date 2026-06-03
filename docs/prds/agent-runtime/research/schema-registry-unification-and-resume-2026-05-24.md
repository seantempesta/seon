---
type: research
status: completed
tags: [research, schema]
---

# Schema registry unification + bootstrap-from-DB resume

## TL;DR

**One registry, one source of truth, one bootstrap path. Add CLJS instrumentation; route eval-triggered validation failures to the same eval-result envelope so they land in the right turn entity.**

What's actually true (REPL-verified):

1. **There is only ONE data-schema registry today.** Sean's worry about "different registries" was the layering discussion: malli's composite of `[default-schemas, mutable(*schemas)]`, not multiple competing globals. `(m/-registry)` and the seon.schema's mutable atom point at the same composite. `mr/*registry*` (the dynamic var) is unused at our default mode setting. 268 schemas registered, of which 124 are seon.* keys.

2. **The function-schema registry is separate from the data-schema registry.** `malli.core/-function-schemas*` is a different private atom, keyed by `:cljs` or `:clj`. Today the CLJS pod has 0 function schemas registered (the `m/=>` macro and `mi/collect!` macro paths never fire — neither is reached from any CLJS code in our build). Despite ~30 fns in pod code with `:malli/schema` metadata, NONE of them is instrumented in CLJS.

3. **`malli.instrument` works in CLJS, just add it to the bundle.** The instrumentation namespace (`malli.instrument.cljs`) does globalThis var-patching to wrap fns with validation; it's a real, finished implementation. Not currently in our `:client` bundle because nothing requires it. We own the bundle — add `(:require [malli.instrument])` to `seon.dev.instrument.cljs` (new file in step 2 of migration), done. Same for `malli.generator` / `clojure.test.check.*` if we want `mi/check`'s generative validation. Bundle size grows ~150KB minified; pod isn't size-constrained.

4. **`(mi/collect!)` is a CLJ-side macro that runs at COMPILE time.** It uses `cljs.analyzer.api/ns-publics` to scan namespaces for `:malli/schema` metadata and emits runtime calls to `m/-register-function-schema!`. For substrate code that means: build-time macro run inside shadow-cljs's CLJ host, which sees the analyzer state for all compiled nses. For AGENT-eval'd code: `mi/collect!` won't auto-fire — we need a runtime equivalent that walks the bootstrap-CLJS analyzer state after each eval batch.

5. **Bootstrap-from-DB is feasible and clean.** The substrate's schemas + fn-schemas are 100% derivable from source at build time (shadow's CLJ-side knows the analyzer state for every compiled ns). Pre-render them as a single `bootstrap.edn` file checked into source, OR as a tx-data EDN that the pod loads on first boot. Resume picks up where the EDN left off — every subsequent agent-eval's `(schema/register! …)` AND every agent-defined `:malli/schema`-bearing defn writes a corresponding `:seon.schema` / `:seon.fn` datom in the same `transact!` as the eval entity (detect-and-tee). On resume, replay the agent-side log in topo order on top of the substrate bootstrap.

6. **Transient state IS small and IS already audited.** From `schema-state-architecture-audit-2026-05-23.md`: 11 defonces in pod CLJS, of which 3 are legitimately opaque runtime (`als-instance`, `timeout-sentinel`, `id-letters`), 5 are caches with invalidation discipline, 2 are config knobs, 1 is the `default-id` smell. Render a "transient state" warning section that queries the runtime for `defonce` markers (or, more honestly, lists a registered-at-init set of "this is process state, not persisted state" entries) so agents see the boundary.

The unified design: **`seon.schema/register!` keeps its current signature** (so agent ergonomics don't change), but its body fans out three writes — the in-memory malli registry (today), the function-schema registry IF the call shape is `[:=> …]`, and a `:seon.schema` datom (when called from inside an agent eval — detected via `*tx-context*` ALS). On resume, the bootstrap loader does these same three writes from EDN, before any agent-replay runs.

---

## Q1 — Registry shapes that exist in our pod today

### REPL probe — what's there

```clojure
(require '[malli.core :as m] '[malli.registry :as mr] '[seon.schema :as schema])
(let [reg (m/-registry)]
  {:total (count (mr/schemas reg))
   :seon-count (count (filter #(and (qualified-keyword? %)
                                    (str/starts-with? (namespace %) "seon."))
                              (keys (mr/schemas reg))))})
;; => {:total 268, :seon-count 124}

```

### How the composite is wired

`seon.schema.cljc:38-47`:

```clojure
(defonce ^:private *schemas (atom {}))

(defonce ^:private _registry-init
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry *schemas))))

```

What `set-default-registry!` does (from `reference-code/malli/src/malli/registry.cljc:40-46`): mutates a private `registry*` atom inside malli.registry. All schema lookups go through `(m/-registry)`, which returns a `custom-default-registry` view that dereferences that atom. So there is exactly one "current registry" pointer at any time.

`mr/*registry*` (the dynamic var) IS different — it's the `:mode "dynamic"` fallback. Our pod stays on `:mode "default"`. The dynamic var is empty, never used. **Probe-verified:**

```
(identical? mr/*registry* (m/-registry)) ;; => false  (different objects)
(pr-str mr/*registry*)                    ;; => "{}"   (empty dynamic var, unused)
(count (mr/schemas (m/-registry)))        ;; => 268   (the real registry)

```

### What gets registered

124 seon.* keys, plus malli's built-in default schemas (`:int :string :keyword :map :vector :enum :and :or :=>` etc), plus our 5 custom simple-schemas (`:inst`, `:seon.flow/dynamic`, `:seon.db/namespace`, `:seon.db/lookup-ref-value`, `:seon.db/ref`, `:seon.db/id`).

**Registration calls are scattered across ~15 namespaces** (each `seon.*` ns registers its own schemas via `schema/register!`). This is correct per CLAUDE.md (schemas colocated with the code that owns the data) but means the registry's contents are determined by which nses get required at boot. If a namespace isn't loaded, its schemas aren't registered.

### Code smell: `register!` evaluates at namespace-load time

`schema/register!` is a `defn`. It's called from top-level forms in `seon.agent`, `seon.log`, `seon.platform`, etc. When the ns loads, these fire as side effects. Implications:

- **First load:** fine. The ns is required, `register!` calls fire in order, registry populates.
- **Hot reload of a schema-owning ns:** the `register!` calls re-fire and overwrite existing entries in `*schemas`. Good (lets you edit a schema and reload).
- **Pod restart:** registry resets to empty, then every ns's load-time `register!` calls re-populate. Recomputed from scratch every boot.
- **Resume from disk:** there is no resume path today. The DB has `:seon.schema/source` strings (agent-defined ones), but the SUBSTRATE schemas aren't in the DB. They're in source code, registered at load time.

### The "different registries" Sean half-remembers

From `schema-state-architecture-audit-2026-05-23.md` §6.6: "Multi-agent v1 might want per-agent registries (so agent A can register `:agent-a/foo` without agent B seeing it). `mr/composite-registry` supports this. Deferred."

That's the only thread. It's about per-agent overlay registries, not "we have two competing registries". The current state IS coherent: one registry, populated at load time by `register!` side effects. The incoherence is the **disconnect between registry and DB** — schemas live in two unrelated places (the in-process atom + DB datoms for agent-defined ones), with no shared source of truth.

## Q2 — `:malli/schema` metadata: how it gets attached and how it'd be instrumented in CLJS

### How `:malli/schema` metadata gets attached today

When you write:

```clojure
(defn ^{:malli/schema [:=> [:cat ::request] ::response]} do-thing [m] ...)
;; OR
(defn do-thing
  {:malli/schema [:=> [:cat ::request] ::response]}
  [m] ...)

```

… the CLJS compiler attaches `:malli/schema` to the var's metadata. REPL-confirmed in the prior analyzer research:

```
(get-in @!compile-state [:cljs.analyzer/namespaces 'probe.variants :defs 'with-attr :meta])
;; => {:doc "docs" :arglists '([x])
;;     :malli/schema [:=> [:cat :int] :int]}     ← preserved verbatim

```

### What does NOT happen today in CLJS

**Nothing reads the metadata.** No instrumentation runs. The schema is data sitting on a var, dead. Reasons:

1. `malli.instrument` is not in the `:client` bundle (REPL-confirmed: `(resolve 'malli.instrument/instrument!)` → `{:resolved false}`).
2. `(mi/collect!)` is a CLJ-side macro that walks `cljs.analyzer.api/ns-publics`. It runs ONCE at compile time, in the host JVM during the shadow-cljs build. Even if we required `malli.instrument`, `mi/collect!` would need to be called from a CLJ macroexpansion site — not directly invokable from agent runtime CLJS code.
3. `m/=>` is a macro that registers function-schemas at macroexpand time (in CLJS, into the `:cljs` partition of `-function-schemas*`). We don't use `m/=>` anywhere; everything uses `:malli/schema` metadata.

REPL-verified zero instrumentation:

```clojure
(malli.core/function-schemas :cljs) ;; => {}  (count 0)

```

### What does happen on JVM today

`seon.dev.instrumentation` (Integrant component `:seon.dev/instrumentation`) calls `(mi/collect!)` + `(mi/instrument!)` at boot. JVM-only. Every public fn with `:malli/schema` meta gets wrapped, validation throws cryptic-then-rewritten errors, agents-on-JVM see them as compile errors. **None of this applies to the CLJS pod.**

### CLJS instrumentation flow we'd add

Three pieces, all already-built in malli:

1. **Collect** — at build time, run `(mi/collect! {:ns [seon.agent seon.eval seon.db ...]})` from a CLJ macro inside our build (e.g. `seon.dev.instrument-cljs.cljs` with `(:require-macros ...)` glue). This populates `(m/function-schemas :cljs)` with one entry per `:malli/schema`-bearing public var in the listed nses. Cost: a build-time pass per ns.

2. **Instrument** — at pod boot (in `seon.client/start-agent!` or via an Integrant-equivalent for CLJS), call `(mi/instrument!)`. This reads `(m/function-schemas :cljs)`, walks every `[ns sym]`, finds the var on globalThis, replaces it with a validating wrapper (see `malli.instrument.cljs:77-93` `-replace-fn`).

3. **Runtime register for agent-eval'd defns** — `(mi/collect!)` is CLJ-only. For agent-defined fns we need a runtime equivalent. The shape:

   ```clojure
   ;; Inside seon.eval/eval-batch! per-form, after a successful defn eval
   ;; and after the analyzer-driven detect-and-tee (other research note):
   (when-let [schema (-> var-map :meta :malli/schema)]
     (m/-register-function-schema! ns-sym sym schema {:metadata-schema? true} :cljs identity)
     (mi/instrument! {:filters [(mi/-filter-var
                                  (fn [v] (= (symbol (str ns-sym) (str sym))
                                             (.-sym v))))]}))

   ```

   The agent's `(defn analyze {:malli/schema [...]} [...])` now produces an instrumented runtime fn. Next call to `(analyze {…})` validates.

### Eval-context plumbing (the user's "data goes to the right place" point)

When an instrumented fn fails mid-eval, the default report path is `m/-fail!` which throws ex-info. Our `seon.eval/eval` already catches throws and packages them as `{:ok false :error <error-map>}` via `seon.error/->map`. So **failures land in the eval-result envelope automatically** — no extra plumbing needed for the BASIC case.

The richer case is: the agent's eval calls a downstream fn that calls another instrumented fn three levels deep. The throw bubbles all the way out, and we lose call-site context (which fn in the chain failed, with what args). Two fixes:

- **`:report` option for collect/instrument** — `mi/instrument! {:report (fn [type data] ...)}`. We supply a reporter that:
  1. Captures `{:malli.instrument/type type :data data :fn-name (:fn-name data)}` to the current eval context via the ALS (`*tx-context*` we already have).
  2. Throws (so eval still fails) with the captured envelope as `ex-data`.

  Catch site in `seon.eval/eval` unpacks the envelope and includes it in `:seon.error/data`. Renders show "validation failed at `seon.db/transact!`, arg 0 missing `:seon.db/conn`" instead of a stack frame.

- **`:scope` option** — defaults to `#{:input :output}`. Restrict to `:input` for downstream callers (cheaper, focuses errors on caller mistakes vs. fn-impl mistakes). Keep `:output` only for cross-boundary fns (`seon.db/*`, `seon.eval/eval`).

### Bundle additions

We own the `:client` bundle — Sean confirmed: "add whatever we need to it." For instrumentation that means:

- `malli.instrument` (required) — the `-replace-fn` + `-strument!` machinery.
- `malli.generator` (required by `malli.instrument` for `mi/check` generative validation; can be tree-shaken out if we don't expose `check`, but the require chain pulls it in regardless of usage).
- `clojure.test.check.*` (transitive from `malli.generator`).

Add one `(:require [malli.instrument])` in a new `seon.dev.instrument.cljs`, then require THAT from `seon.client`. Shadow's dep graph picks it all up. ~150KB minified additional. Not a constraint for the pod.

**Separate concern for the WASM track later** (Phase 3 — alpha-blocking, per CLAUDE.md): every KB matters there. If size is an issue, ship a `seon.instrument-lite` that imports only `malli.core/-instrument` + `malli.instrument/-replace-fn` directly, dropping the generator chain. Not a v1 decision.

## Q3 — Bootstrap-from-DB: pre-parsed datom inserts for the substrate schemas

### Why "load the substrate schemas from DB at boot" is the right move

Today: every boot re-runs ~120 `schema/register!` calls across 15 namespaces as load-time side effects. The schemas exist in source code; the in-process atom is recomputed each time; nothing persists across boots; the `:seon.schema` entities in the DB only cover agent-defined schemas, not substrate ones.

Sean's vision: a single bootstrap pass populates the DB from a precomputed source-of-truth, and resume = "play the DB into the live registry". One source of truth, one load path, identical mechanism for substrate and agent.

### Mechanism

Build-time: a CLJ macro (or build script) walks every `seon.*` namespace via the host analyzer, extracts every `schema/register!` call, reads `:malli/schema` metadata from every public defn, and emits two things:

1. **A `bootstrap.edn` checked into source** — a vector of tx-data maps:

   ```clojure
   [{:seon.schema/key :seon.agent/id
     :seon.schema/source "[:and {:seon.db/identity true} :seon.db/id]"
     :seon.schema/ns [:seon.ns/name :seon.agent]
     :seon.schema/substrate? true
     :seon.schema/at #inst "2026-05-24T..."}
    ;; ...
    {:seon.fn/sym "seon.db/transact!"
     :seon.fn/source "(defn transact! ...)"
     :seon.fn/ns [:seon.ns/name :seon.db]
     :seon.fn/arglists "([conn] [conn tx-data] [conn tx-data opts])"
     :seon.fn/malli-schema "[:function [:=> [:cat :seon.db/conn] :map] ...]"
     :seon.fn/substrate? true
     :seon.fn/at #inst "..."}
    ;; ...]

   ```

2. **An EDN list of `:seon.ns` entities** — for resume's topo-sort input (other research note Q5):

   ```clojure
   {:seon.ns/name :seon.db
    :seon.ns/source "(ns seon.db ...)"
    :seon.ns/requires [:seon.schema :seon.error :malli.core ...]
    :seon.ns/substrate? true}

   ```

`:seon.{schema,fn,ns}/substrate? true` distinguishes "shipped with the pod" from "written by an agent". Substrate entities don't get re-evaled on resume (their JS is already in the bundle); they just need to land in the registry so agent code can see them. Agent entities do get re-evaled.

### Boot sequence (proposed)

```
1. Pod starts. (defonce *schemas (atom {})) initializes empty. No load-time register! fires
   (we remove them — see Q7).
2. seon.client/-main:
   a. (dev-init!) — opens datahike conn, no schema yet.
   b. (bootstrap/load-substrate! conn) — reads resources/seon/bootstrap.edn, transacts
      every entity. Tx-listener on :seon.schema entities fires register! to populate
      *schemas as datoms land.
   c. (bootstrap/load-agent! conn) — query DB for :seon.fn / :seon.schema / :seon.ns
      with :substrate? false. Topo-sort by :seon.ns/requires. For each ns in order:
      eval its :seon.ns/source, then its :seon.schema/source entries (which call
      schema/register! and update *schemas), then its :seon.fn/source entries.
   d. (instrument!) — read (m/function-schemas :cljs), patch globalThis.
   e. start agent loop.

```

After step c, the pod is byte-for-byte equivalent to "fresh boot + every agent eval ever applied", but executed in topo order from a clean state.

### Tx-listener that mirrors `:seon.schema` writes → registry

The tx-listener replaces today's load-time `register!` side effects:

```clojure
;; Inside seon.schema (or a new seon.schema.bridge):
(defn install-registry-sync!
  "After this runs, every transact! of a :seon.schema entity updates *schemas.
   Idempotent — re-applying the same source is a no-op."
  [conn]
  (d/listen! conn ::schema-sync
    (fn [{:keys [tx-data db-after]}]
      (doseq [[e a v _ added?] tx-data
              :when (and added? (= a :seon.schema/source))]
        (let [k (:seon.schema/key (d/pull db-after [:seon.schema/key] e))
              schema (edn/read-string v)]
          (swap! *schemas assoc k schema))))))

```

This is the "decode-IS-dispatch" pattern Sean mentioned (`project_malli_decode_dispatch.md` from memory). The DB IS the source of truth; the in-process atom is a derived cache. When `:seon.schema/source` changes (agent re-defines a schema), the listener re-eval's it and `swap!`s the atom. The "two source-of-truth" problem disappears.

### Function-schema bootstrap

Same model: `:seon.fn` entities carry `:seon.fn/malli-schema` as a string (when the source contains `:malli/schema` metadata). A second tx-listener on `:seon.fn/malli-schema` calls `m/-register-function-schema!` for each. After bootstrap completes, run `(mi/instrument!)` ONCE to patch globalThis. After that, the per-eval detect-and-tee handles incremental agent-defined fns (Q2's runtime register + filter-by-var instrument).

### Cost & risks

- **Build-time cost:** a CLJ-side macro that walks ~20 nses and emits ~250 entities. Sub-second. Output is ~50-100KB EDN.
- **Boot-time cost:** one transact of ~250 entities + tx-listener fan-out. Sub-second on datahike-cljs (per prior probes).
- **Schema mutations between source bumps:** if a developer edits a `schema/register!` call in `seon.agent.cljs` and reloads, the on-disk bootstrap.edn is stale until the next build. Mitigation: a watcher that re-emits bootstrap.edn on save of any seon.* source file (similar to how shadow-cljs auto-rebuilds). Or: the build emits bootstrap.edn unconditionally on every `clj -M:cljs compile client`.
- **Stale agent entities at resume:** if an agent-defined `:seon.schema` references another schema that the substrate has since retired (changed shape, removed), the agent's resume eval will fail at validation. This is correct — surface it as a `seon.resume/schema-drift` warning entity and skip the offending entry. Agent regenerates on next prompt.

### Trade with status quo

Status quo wins: simpler (no build step, no bootstrap.edn artifact, no tx-listener registry sync). Loses: substrate schemas aren't queryable from the DB, no history, no diff "what changed when", no symmetry between substrate and agent.

Sean's stated goal — "always a 'current' runtime we can pause, flush to disk and resume the next day" — requires the symmetry. Status quo can't deliver it without inventing a separate substrate-snapshot mechanism. Unified path is cheaper long-term.

## Q4 — Resume semantics: "current runtime" persistence boundary

### What "current runtime" means

A pod boot at time T should reach the same observable state as a pod that has been running since the first turn the agent ever took, then was paused right before T. Observable state =

1. **DB contents** — already persisted (datahike on disk).
2. **In-memory malli registry** (`*schemas` atom) — derived from `:seon.schema` entities (Q3 tx-listener).
3. **`malli.core/-function-schemas*`** — derived from `:seon.fn/malli-schema` entries (Q3 tx-listener).
4. **The CLJS analyzer state** (`@!compile-state`) — substrate cache loaded from `out/bootstrap/ana/*` at init time; agent additions re-derived by re-evaling `:seon.fn/source` / `:seon.ns/source` in topo order (other research note).
5. **JS heap values bound to vars** — for substrate fns, they're in the bundle and on globalThis at boot. For agent fns, they're produced by step 4's evals.
6. **Active integrations** — datahike conn, the HTTP+SSE server, any started timers / intervals.

### What "transient state" means

Things that are correctly NOT persisted because they're meaningless after a restart:

- The datahike conn object (a JS object). Re-created on boot.
- The HTTP server's open socket file descriptors. Re-bound on boot.
- The SSE connections set (`!sse-connections`). Clients reconnect.
- AsyncLocalStorage instance (`als-instance`). New instance per Node process.
- Per-form eval timeouts in flight. Eval batch starts fresh.
- The compile-state atom's identity (we create a new atom; the CONTENT is rebuilt).

Things that LOOK transient but should be persisted:

- The agent's "current ns" — already derived from latest `:seon.eval/ns`, not stored. Good.
- The agent's `:seon.agent/state :idle | :running`. Persisted today — correct (a pod that crashes mid-turn should resume in `:running` and finish the turn).
- The agent's `:seon.agent/turns-cap`. Persisted today — correct (agent's overrides survive).

### Resume sequence (proposed)

Building on the Q3 boot sequence, with the "pause-and-resume" framing made explicit:

```
PAUSE (graceful):
  1. Stop accepting new turns. Wait for current turn to finish (or write a
     :seon.turn/status :paused to make the resume aware).
  2. db/flush! — force datahike to fsync.
  3. Process exits.

RESUME:
  1. Pod starts. seon.client/-main runs:
     a. (dev-init!) — datahike conn + bootstrap-CLJS compile-state.
     b. (bootstrap/load-substrate! conn) — idempotent. Tx of substrate
        entities is a no-op if they're already present (identity attrs upsert).
     c. (registry-sync/install! conn) — tx-listener wires future :seon.schema
        writes into *schemas.
     d. Walk the DB: for every :seon.schema entity (substrate AND agent),
        seed *schemas. This is the first time agent schemas land in registry
        on this boot. (Alternative: trust the tx-listener to fire on step b's
        replay; cleaner to do an explicit one-pass seed since the listener
        was registered AFTER substrate-load.)
     e. Walk the DB: for every :seon.fn entity, register function-schema.
     f. Topo-resume agent ns in :seon.ns/requires order:
        - For each ns: eval the :seon.ns/source (re-establishes (ns ...) form
          in the analyzer + JS heap).
        - For each :seon.fn whose :seon.fn/ns ref points at this ns
          (ordered by :seon.fn/at): re-eval the :seon.fn/source.
        - :seon.schema entities are already loaded in step d; no per-ns re-eval.
     g. (mi/instrument!) — single pass over all function-schemas.
     h. Determine "where we left off":
        - If a :seon.turn exists with :seon.turn/status :running (pod crashed
          mid-turn), this is the resume target. Either: re-run the turn from
          its last :seon.eval, or mark it :error and let the agent start a
          fresh one. Decision: mark :error + log; agent decides what to do.
        - Otherwise: idle. Wait for next user message.
     i. Start HTTP+SSE server; reconnect tx-listener kick handlers.

```

Steps b-g are deterministic and depend only on DB content. Step h is the one "policy" decision.

### What happens if the substrate changed between pause and resume

Pod was paused at T1 with substrate version V1. Engineer ships V2, agent reboots. Two cases:

- **Backwards-compatible substrate change** (added schema, added fn). Bootstrap-load transacts the V2 entities, identity-upserts replace V1 versions where overlap. Agent code that called V1 fns still works (the new globalThis bindings shadow them with V2; behavior may differ — that's the engineer's responsibility).
- **Breaking change** (removed schema, changed shape). Agent's persisted `:seon.fn/source` that referenced the removed schema will fail to re-eval (the analyzer warns, our `truly-undeclared?` escalates). The fn is marked `:seon.resume/skipped`; render shows the agent "you have N skipped functions due to substrate drift" so it can regenerate them.

The DB IS the historical record. `d/history` on `:seon.schema/source` answers "when did this schema's shape last change" — useful for diagnosing drift.

### The "context budget" point Sean raised

> An eval is going to disappear from an agent's context unless they specifically change their context to keep it around.

Implication for resume: most evals are NOT in the agent's current-render context. They're queryable in the DB (`:seon.eval` entities under the relevant `:seon.turn`), but the agent's working memory excludes them by default. The reactive-context principle says: if the agent wants to surface an eval, it writes a section function that queries for it (by ns, by tag, by error status, whatever). Resume changes nothing here — the agent that wakes up at T2 sees the same sections it had at T1, populated from the same DB.

What this DOES affect: if an instrumented downstream fn fails during eval (e.g. agent called `(seon.db/transact! conn bad-data)` and instrumentation rejected it), the error envelope lands in the eval's `:seon.eval/error` field. The eval's own context section (the one that renders the most recent N evals or evals matching a filter) will surface it — automatically. No extra plumbing. The instrumentation report just needs to throw with a structured ex-data that our error mapper handles.

## Q5 — Transient state declaration (the warning surface)

### What to declare

A registered list of "this is process-state, NOT persistent state" entries. Sean wants a warning section that says "you have these transient bindings; if you reboot, they vanish." Two sources:

1. **Substrate transient state** — known at build time. The 11 defonces audited in `schema-state-architecture-audit-2026-05-23.md`. Curate manually (it's a short list); register each via a small DSL.
2. **Agent transient state** — agent code that does `(def !my-cache (atom {}))` from inside an eval. The analyzer doesn't distinguish atoms from values, but the value's TYPE at runtime does (it's an `IAtom`). On detect-and-tee, if the var-map's eval'd value is an atom (or any IRef), tag the `:seon.fn` entity with `:seon.fn/transient? true`.

### Proposed schema

```clojure
(schema/register! :seon.transient/sym         [:string {:seon.db/identity true}])
(schema/register! :seon.transient/ns          :seon.db/ref)
(schema/register! :seon.transient/kind        [:enum :cache :connection :timer :counter :other])
(schema/register! :seon.transient/description :string)
(schema/register! :seon.transient/declared-by [:enum :substrate :agent])

```

For substrate: source-of-truth is a checked-in EDN file (`resources/seon/transient.edn`) listing each defonce + why + kind. Loaded at boot, transacted as `:seon.transient` entities. Identity upserts on sym.

For agent: per-eval detect-and-tee inspects the new var's value. If `(satisfies? IDeref value)`, write a `:seon.transient` entity alongside the `:seon.fn` entity. The render's "transient state" section queries `[:find ?sym :where [?e :seon.transient/sym ?sym] [?e :seon.transient/declared-by :agent]]`.

### Render

A new section in the agent's render layout: "Transient state (vanishes on reboot)". One row per `:seon.transient` entity, grouped by `:declared-by`. Substrate ones are always there (visual reminder); agent ones appear as the agent creates them.

This satisfies Sean's "have a warning section to declare when an agent is using transient state" — derive-not-store: the section is a query over `:seon.transient`, no notification queue, no acknowledgement state.

### Caveat — atoms that are persisted indirectly

Some agent atoms ARE the right design — e.g. an agent's local cache of computed values where re-deriving on reboot is acceptable. The warning surfaces them; it doesn't ban them. Agent reading the section says "fine, that cache rebuilds on its own"; if NOT fine, agent either changes the design or registers the atom's CONTENTS to persist (write it through to a `:seon.cache/<x>` schema, snapshot periodically).

## Q6 — Proposed unified design

### One picture

```
                  ┌──────────────────────────────┐
                  │  resources/seon/bootstrap.edn │  (checked in, regenerated by build)
                  └──────────────┬───────────────┘
                                 │ transacted at boot
                                 ▼
                  ┌──────────────────────────────┐
                  │     datahike :seon (LMDB)     │  ← single source of truth
                  │                              │
                  │  :seon.ns        entities    │
                  │  :seon.schema    entities    │
                  │  :seon.fn        entities    │
                  │  :seon.transient entities    │
                  │  + agent log (sessions/turns/evals/messages)
                  └──────────────┬───────────────┘
                                 │ tx-listeners (derive in-process caches)
                                 ▼
              ┌──────────────────┴──────────────────┐
              │                                     │
              ▼                                     ▼
   ┌───────────────────┐               ┌────────────────────────┐
   │ malli registry    │               │ function-schemas atom  │
   │ (seon.schema's    │               │ (malli.core's          │
   │  *schemas)        │               │  -function-schemas*)   │
   └─────────┬─────────┘               └────────────┬───────────┘
             │                                      │
             │                                      │ instrument!
             │                                      ▼
             │                          ┌────────────────────────┐
             │                          │  globalThis var wraps  │
             │                          │  (every fn with        │
             │                          │   :malli/schema)       │
             │                          └────────────┬───────────┘
             │                                      │
             └──────────────────────────────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │   agent eval (cljs.js)        │
                  │   reads schemas/calls fns     │
                  │   detect-and-tee on success   │
                  └──────────────────────────────┘

```

### Rules

1. **DB is the source of truth.** All schemas (substrate + agent), all fn schemas, all transient declarations live as datoms.
2. **Registry + function-schemas atom are derived caches.** Populated by tx-listeners on `:seon.schema/source` and `:seon.fn/malli-schema` attr writes. Never written to directly outside the bootstrap-load + tx-listener path.
3. **`schema/register!` keeps its current signature** (zero migration cost for agents and existing substrate code), but its body becomes:

   ```clojure
   (defn register! [k schema-form]
     (let [ctx (current-tx-context)]            ; ALS lookup
       (if (or (nil? ctx) (:bootstrap? ctx))
         ;; Direct atom write — bootstrap path, no DB roundtrip
         (swap! *schemas assoc k schema-form)
         ;; Agent-eval path — write to DB; listener mirrors to atom
         (db/transact! (:conn ctx)
                       [{:seon.schema/key    k
                         :seon.schema/source (pr-str schema-form)
                         :seon.schema/ns     [:seon.ns/name (ns-of k)]}]))))

   ```
4. **`:malli/schema` metadata is the universal declaration.** Both substrate `(defn x {:malli/schema ...})` and agent eval'd `(defn x {:malli/schema ...})` flow through the same detect-and-tee → register-function-schema → instrument! pipeline.
5. **Instrumentation is always on.** No "dev mode" toggle — the validation IS the contract. Agent-eval failures land in `:seon.eval/error` via the existing error envelope. Reporter is a single fn at the boundary.
6. **Resume = play substrate.edn + replay agent log in topo order.** No code path other than "transact entities and let the listeners fan out".

### What changes per-namespace

- `seon.schema.cljc`: `register!` body changes per rule 3. Add `current-keys` public read (for atom-diff in detect-and-tee). Add `install-registry-sync!` tx-listener installer.
- `seon.eval.cljs`: per-form loop adds the atom-diff-based detect-and-tee + the per-fn `mi/instrument!` call. Wires the instrumentation reporter to populate the eval-error envelope.
- `seon.client.cljs`: boot sequence per Q4. Bootstrap-load before agent-loop start.
- New file: `seon.dev.instrument.cljs` — bundles `malli.instrument`, exposes `start!` / `instrument-fn!` / `reporter` fns. Required from `seon.client` so it lands in the bundle.
- New build artifact: `resources/seon/bootstrap.edn` — emitted by a `seon.build.bootstrap` ns (CLJ-side macro that runs at compile time or as a manual `clj -M:bootstrap` step).
- New schemas: `:seon.transient/*`, `:seon.{ns,fn,schema}/substrate?`.

### What stays the same

- Agent ergonomics: `(schema/register! ::foo :string)` and `(defn foo {:malli/schema [:=> ...]} [m] ...)` work identically.
- `:malli/schema` metadata convention.
- Identity attr pattern `[:and {:seon.db/identity true} :seon.db/id]`.
- Reactive-context pattern for sections.
- Single shared compile-state.

## Q7 — Migration steps from today's state

Ordered for incremental value + reversibility:

1. **Add `seon.schema/current-keys`** (3-line PR). Unblocks atom-diff detect-and-tee.
2. **Add `malli.instrument` to the `:client` bundle.** One `(:require [malli.instrument])` in a new `seon.dev.instrument.cljs`. Measure bundle size delta.
3. **Build-time `(mi/collect!)` for substrate.** A CLJ macro that runs over the seon.* nses during shadow-cljs build, registers function-schemas for everything with `:malli/schema` metadata. After this, `(m/function-schemas :cljs)` is populated at pod boot but no instrumentation yet.
4. **Wire `(mi/instrument!)` call in `seon.client/-main`** after `(dev-init!)`. With a `:report` fn that captures into the error envelope. Test against the existing pod — every `:malli/schema`-bearing substrate fn becomes validated. Surfaces existing schema-vs-impl mismatches; fix as found.
5. **Detect-and-tee in `seon.eval/eval-batch!`** (covered by the analyzer-driven research). When a defn has `:malli/schema` metadata, also `m/-register-function-schema!` it and instrument-just-that-fn.
6. **Build-time `bootstrap.edn` emitter.** A CLJ ns (e.g. `seon.build.bootstrap`) that walks every seon.* ns via the host analyzer, emits the EDN. Runs as part of `clj -M:cljs compile client`. Initial output: snapshot of current substrate.
7. **Boot-time `bootstrap/load-substrate!`.** Reads `resources/seon/bootstrap.edn`, transacts. Idempotent (identity attrs upsert).
8. **`schema/register!` rewrite per Q6 rule 3.** Behind a feature flag the first time — old code path still works in parallel. Verify the tx-listener + ALS context correctly distinguishes bootstrap vs agent. Remove old path once green.
9. **Remove load-time `register!` calls from `seon.agent.cljs` / etc.** They're now redundant — bootstrap.edn has them. Build a smoke test that diffs the registry before/after the removal to confirm identity.
10. **`:seon.transient` schema + curated `transient.edn`.** Hand-write the substrate list from the audit. Add detect-and-tee atom check for agent-side.
11. **Resume entry point.** `seon.client/-main` walks DB on boot, replays agent log in topo order. Test by `bin/seon stop pod && bin/seon start pod` and asserting the live runtime matches pre-pause state.

Each step is independently shippable. Steps 1-5 deliver instrumentation. Steps 6-9 deliver the unified bootstrap. Steps 10-11 deliver true resume.

### Things to test at each step

- After step 4: every substrate fn with `:malli/schema` validates. Run the existing test suite; expect some surprises (we never validated before).
- After step 5: an agent eval'ing `(defn bad {:malli/schema [:=> [:cat :int] :int]} [x] "oops")` then `(bad 5)` returns `{:ok false :error {... validation ...}}` — instrumentation fires inside cljs.js eval.
- After step 7: rebooting the pod produces an identical registry by way of bootstrap.edn alone (load-time `register!` calls disabled to test).
- After step 11: pause mid-session, restart, verify the running turn resumes correctly OR cleanly marks itself :error with a re-render-eligible state.

## PLATFORM-FLAGs

1. **`malli.instrument` not bundled.** REPL-confirmed: `(resolve 'malli.instrument/instrument!)` → `{:resolved false}`. Add to bundle via a new `seon.dev.instrument.cljs` that `:require`s `malli.instrument` (and `malli.generator` transitively). Bundle is ours; no constraint to negotiate. Step 2 of Q7 migration.
2. **Zero function-schemas registered in CLJS today.** REPL-confirmed: `(m/function-schemas :cljs) => {}`. None of the ~30 `:malli/schema` annotations in pod code are doing anything. Until step 4 of Q7 ships, those annotations are documentation only.
3. **`schema/register!` runs at namespace-load time.** Fine for first boot, problematic for "what's the source of truth" question. Migration to a DB-backed register is the largest refactor on the path but the most architecturally meaningful change.
4. **No CLJS instrumentation component analog to JVM's `seon.dev.instrumentation`.** The JVM Integrant pattern (suspend/resume, refresh after reload) doesn't translate directly — there's no Integrant in pod CLJS. Need a simpler pattern: `seon.client/-main` calls `start!`; hot-reload of `seon.eval` rotates the init-version (same trick as compile-state); on rotation, re-collect + re-instrument.
5. **Substrate schemas aren't in the DB.** Status quo. Sean's unified vision requires bootstrap.edn or equivalent.
6. **Pod boot order is currently fragile** w.r.t. load-time `register!` side effects. Some schemas reference others (`:seon.agent/id` references `:seon.db/id`). The current files order this correctly by accident of `:require` ordering. Bootstrap.edn replaces "load-time order" with "transact-time order" — much more legible and toolable.

## Open questions back to Sean

1. **Bootstrap.edn regeneration trigger.** Re-emit on every `clj -M:cljs compile client`, OR on save of any seon.* file (via a hook), OR only when a CI step says "rebuild bootstrap"? Recommend: regen on every compile, fail the build if regen produces a diff that wasn't committed. Stops drift cold.

2. **Agent-defined schema deletion.** Today an agent can `(schema/register! ::foo :string)` then later `(schema/register! ::foo :int)` — upsert. Can an agent DELETE a schema? If so, what's the verb? `(seon.schema/retract! ::foo)` that retracts the `:seon.schema` entity? Same question for fns.

3. **Per-agent registry overlay (multi-agent v1).** Today shared. Should agent B's `(schema/register! :alice.local/x …)` be visible to agent A? If we want isolation, the tx-context-aware `register!` writes a `:seon.schema/agent` ref so query+composite-registry can scope by agent. Probably defer until contention shows up.

4. **Instrument scope policy.** Default `#{:input :output}` is strict. For agent-eval'd fns that the agent then calls from another eval, output validation catches the agent's own mistakes — good. But it doubles the validation cost. Lean: input-only for agent-defined; input+output for substrate.

5. **The "rich eval-result envelope" plumbing.** Today `seon.error/->map` produces `{:seon.error/message :seon.error/kind :seon.error/data ...}`. Instrumentation failures should land as `:seon.error/kind :malli.instrument` with `:malli.instrument/fn-name`, `:malli.instrument/arg-index`, `:malli.instrument/expected-schema`, `:malli.instrument/actual-value`. Render in the eval section uses these to point at the offending arg. Need a small contract between the instrumentation reporter and the error mapper — declare it explicitly to avoid future drift.

6. **Resume policy for an interrupted turn.** Three options: (a) mark `:error` and let agent observe; (b) re-issue the user's last message and let agent redo the turn; (c) attempt to continue from the last successful eval. Lean: (a) — it's correct, observable, and the agent (LLM) is the right entity to decide between b and c.

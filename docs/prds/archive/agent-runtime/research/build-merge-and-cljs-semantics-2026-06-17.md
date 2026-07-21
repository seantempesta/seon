---
type: research
status: active
tags: [research, cljs, web]
---

# Build-merge model + CLJS self-host semantics (B2 / B6 / #8)

## TL;DR

The MINIMAL correct third-party merge is already designed and half-wired: a
downstream entry ns must be **required into the `:client` module graph** via the
dev `:preloads` slot, and that entry ns must `(reset! seon.client/!extra-core-vars
(seon.indexing/specced-fn-vars))` in its OWN require closure — exactly the
`seon.dev.test-preload` precedent. Shadow compiles ONLY what is reachable from a
module's `:entries` (`resolve-entries`, resolve.clj:714); `:preloads` are prepended
to `:entries` in **dev mode only** (`inject-preloads`, shared.clj:252) — so
preload-as-trigger is not a smell, it is the only graph-root mechanism shadow
offers, and it is correctly dev-scoped, which is the SAME invariant the override
mechanism needs. **Ephemeral-core is viable and cheap:** seon already rebuilds its
index each boot by walking `:cljs.analyzer/namespaces` off the live compile-state
(`analyzer_info.cljs`), which is the authoritative analyzer slot
(analyzer.cljc:569-585) holding `:name/:file/:line/:arglists/:test/:private/:fn-var`
per def — no durable-store read required to reconstruct the core program-graph.
**Override works because the pod is a dev `:none` build** (live-confirmed
`goog.DEBUG=true`): `*cljs-static-fns*` is `false` (analyzer.cljc:61), so a
cross-ns call emits a fresh read of the munged global var (`emit-var`,
compiler.cljc:455-497; `:invoke :else`, compiler.cljc:1300-1309) and a re-eval'd
`(defn …)` re-assigns that global (`:def`, compiler.cljc:862-901) — every existing
caller picks up the new value. The dev-build-only nature of this is the load-bearing
invariant: under `:advanced`/`:static-fns` the call site inlines the arity method
and the override silently no-ops.

CLJS compiler source for v1.12.145 (the pinned version, deps.edn:274) was extracted
from the local maven jar to `tmp/cljs-src-1.12.145/cljs/` (analyzer.cljc,
compiler.cljc, js.cljs, closure.clj) — the only `analyzer.cljc` checked into
`reference-code/` is a partial rewrite-clj test fixture missing compiler.cljc/js.cljs,
so the jar is the authoritative source for the emit logic.

---

## Q1 — B2: the correct third-party merge into the running `:client` bundle

### Evidence: shadow compiles only what is REACHABLE from a module's entries

The compiled set is the dependency-graph closure of the module's `:entries`, not
the classpath. `resolve-entries` (reference-code/shadow-cljs,
`src/main/shadow/build/resolve.clj:714`):

```clojure
(defn resolve-entries
  "returns [resolved-ids updated-state] where each resolved-id can be found in :sources of the updated state"
  [{:keys [classpath] :as state} entries]
  (let [{:keys [resolved-order] :as state}
        (-> state
            (assoc :resolved-set #{} :resolved-order [] :resolved-stack [])
            (util/reduce-> resolve-entry entries))
    ...))
```

A source file sits on the classpath but is only compiled if `resolve-entry` reaches
it from an entry. For the `:node-script` `:client` build the entries are just
`[seon.client/-main]` (shadow-cljs.edn `:main seon.client/-main`). A downstream ns
that nothing requires is therefore **dead-code: never resolved, never compiled, its
vars never defined, `!extra-core-vars` stays empty**. This is the live state on the
pod — confirmed read-only:

```clojure
;; live default session, 2026-06-17
{:extra-core-vars 0, :extra-ns-strs #{}, :env-extra-src nil, :env-extra-preload nil}
```

### Evidence: `:preloads` is the graph-root injection, dev-mode ONLY

`:node-script` injects preloads in dev mode via `shared/inject-preloads`
(`src/main/shadow/build/targets/node_script.clj:45-48`):

```clojure
(cond->
  ...
  (= :dev mode)
  (-> (shared/inject-preloads :main config)
      (assoc ::output/eval-in-global-scope false)))
```

`inject-preloads` (`src/main/shadow/build/targets/shared.clj:252-257`) PREPENDS the
preload nses to the module's `:entries`, making each one an additional graph root:

```clojure
(defn inject-preloads [state module-id config]
  (let [preloads (get-in config [:devtools :preloads])]
    (if-not (seq preloads)
      state
      (update-in state [::modules/config module-id :entries] prepend preloads))))
```

Because it is gated on `(= :dev mode)`, a `:preloads` entry is **NOT** in a
`:release` build — the same dev-only scoping the override mechanism relies on (Q3).

### Evidence: the registration precedent already exists (`seon.dev.test-preload`)

The current build already uses preload-as-trigger for test discovery. The preload's
docstring (`src/seon/dev/test_preload.cljs:4-9`) states the rule verbatim:

> Without this, test namespaces under `test/` are on shadow's source path but
> unreachable from `:client` (per shadow-cljs.edn — each build only compiles what
> is transitively required from its `:main`).

and at the bottom (line 76):

```clojure
(reset! client/!indexed-test-vars (deftest-vars))
```

`seon.client` already provides the exact downstream slot, with usage documented in a
comment block (`src/seon/client.cljs:966-975`):

```clojure
;;   (reset! client/!extra-core-vars
;;           (into (seon.indexing/specced-fn-vars) ...))
(defonce !extra-core-vars (atom []))
```

`specced-fn-vars` (`src/seon/indexing.clj:86-104`) expands at compile time by reading
`:cljs.analyzer/namespaces` (line 92, `analyzer-namespaces`) restricted to the
**calling ns's transitive require closure** (line 93, `transitive-requires`). The
macro's visibility rule (indexing.clj:10-14): "a macro sees only the namespaces
compiled BEFORE its expansion site, so each macro restricts itself to the CALLING
ns's transitive require closure." This is why the macro MUST be invoked from the
downstream entry ns (which requires the downstream surface), not from `seon.client`
(whose closure cannot reach downstream code).

### Why preload-as-trigger is the RIGHT design, not a smell

A ns must be *required* to survive because compilation is reachability-driven by
construction (resolve.clj:714) — there is no shadow knob that says "compile this
classpath dir but don't require it". The candidate alternatives evaluated against
shadow source:

- **(a) `-Sdeps :local/root` + `:preloads` entry (current design).** `-Sdeps` puts
  the downstream `src/` on the tools.deps classpath (deps-mode shadow takes its
  classpath ENTIRELY from deps.edn — shadow-cljs.edn's own comment, lines 31-41).
  The `:preloads` entry makes the downstream entry ns a graph root so it (and its
  require closure) compile. **This is correct and minimal.** It is what `bin/seon`
  emits: `extra_src_sdeps` (bin/seon:101-105) + `extra_preload_merge`
  (bin/seon:107-111), both on the `cljs-watch` command (bin/seon:143).
- **(b) adding the root to `:source-paths`.** DEAD in deps mode — shadow-cljs.edn's
  committed comment (lines 31-41) records the live proof that `:source-paths` is
  ignored when `{:deps {:aliases [:cljs]}}`. Classpath presence alone compiles
  nothing anyway (still needs a require root). Rejected.
- **(c) a `:modules` entry.** `:modules` is a browser/`:esm`-target concept for code
  splitting; `:node-script` has a single implicit module (`:main`). A module entry
  WOULD make the ns a graph root, but it is the wrong target shape and offers nothing
  `:preloads` doesn't. Rejected.
- **(d) a build hook.** `build/api/deep-merge` (referenced in extra-src research) and
  `:build-hooks` can mutate config, but the minimal correct mutation IS prepending an
  entry — which `:preloads` already does declaratively. A hook is strictly more
  machinery for the same effect. Rejected for the first rung.

**The one genuine smell** is not preload-as-trigger but its dev-only scoping: a
`:release` build drops `:preloads` (`inject-preloads` is `(= :dev mode)`-gated), so a
downstream merge would vanish under `:advanced`. This is acceptable today because the
pod ships dev-compiled by deliberate decision (bin/test-cljs records that release
flattening breaks `goog.global` resolution), and it is the SAME dev-only boundary the
override mechanism requires (Q3). If the pod ever moves to `:release`, BOTH the
third-party merge AND per-fn override need the downstream entry merged into the
module `:entries` directly (`--config-merge '{:entries [...]}'`), not `:preloads`.

### Minimal correct wiring (what makes `!extra-core-vars` non-empty)

The build plumbing is already shipped; the missing piece on the live pod is purely
operational (B2: `SEON_EXTRA_PRELOAD` unset, so no preload entry, so the downstream
entry ns is dead-code). To make a downstream surface compile + register:

1. Downstream ships an entry ns (e.g. `acme.pod`) that `(:require …)`s its whole
   surface AND ends with
   `(reset! seon.client/!extra-core-vars (seon.indexing/specced-fn-vars))`.
2. Start `cljs-watch` with BOTH `SEON_EXTRA_SRC=<root>` (classpath) and
   `SEON_EXTRA_PRELOAD=acme.pod` (graph root) — `extra_preload_merge` only fires when
   BOTH are set (bin/seon:108). The injection rides `cljs-watch` (bin/seon:143), NOT
   `pod` (bin/seon:142) — the WATCHER process must carry both vars; a pod-only env
   does nothing for compilation (pod just runs the already-built `out/client/main.js`).
3. Rebuild. The downstream entry is now a graph root, its closure compiles, its vars
   define, the `reset!` populates `!extra-core-vars`, and `index-core!` /
   `core-ns-set` consume it (`extra-core-vars*`, client.cljs:977-989).

The `seon.*`/`my.*` reserved-prefix guard (`assert-extra-vars-unreserved!`,
client.cljs:1005-1019) throws loudly if the downstream registers under a reserved
prefix — keep it.

---

## Q2 — B6 (cljs side): ephemeral core from the analyzer

### The authoritative analyzer-state structure

The canonical slot is `:cljs.analyzer/namespaces` (the keyword form of the
`::namespaces` qualified key) inside the compiler-env atom. The CLJS source documents
this as the slot external tools should read (analyzer.cljc:569-585):

```clojure
;; External tools should look at the authoritative ::namespaces slot in the
;; compiler-env atoms/maps they're using already; this value will yield only
;; `default-namespaces` when accessed outside the scope of a
;; compilation/analysis call
(def namespaces
  #?(:clj
     (reify clojure.lang.IDeref
       (deref [_]
         (if (some? env/*compiler*)
           (::namespaces @env/*compiler*)
           default-namespaces)))
     ...))
```

Shape: `{ns-sym {:name … :defs {def-sym var-map} :requires {…} :uses {…} …}}`.

### Per-var metadata stored in `:defs`

A `def` writes its var-map into `[::namespaces ns-name :defs sym]` as
`(merge {:name var-name} sym-meta {:meta …} (source-info …) (when fn-var? …))`
(analyzer.cljc:2122-2167). The merged map carries:

- `:name` — fully-qualified var symbol (analyzer.cljc:2124).
- `:file`, `:line`, `:column` — via `(source-info var-name env)`
  (analyzer.cljc:2144); also surfaced on the nested `:meta`.
- `:arglists`, `:doc`, `:private` — from `sym-meta` (the user metadata on the def
  symbol), plus an `:arglists`/`:variadic?`/`:max-fixed-arity`/`:method-params`
  block when `fn-var?` (analyzer.cljc:2153-2167).
- `:test` — NORMALIZED to `true` for storage (analyzer.cljc:2127-2128):

```clojure
(cond-> sym-meta
  (:test sym-meta) (assoc :test true))
```

  (the real test-body fn is removed because it is non-EDN and can't be analysis-cached
  to disk — comment at analyzer.cljc:2125-2126). NOTE for B9: the COMPILED form still
  emits `var.cljs$lang$test = <test-fn>` (compiler.cljc:898-901), so the runnable test
  body exists at runtime even though the analyzer var-map only records `:test true`.
- `:fn-var` — `(not (:macro sym-meta))` when the init is a `:fn`
  (analyzer.cljc:2156); this is the field seon's `var-projection` maps to
  `:fn-var?`.

### Walking it each boot is sound and cheap — and seon ALREADY does it

seon reads exactly this slot off the live compile-state, not the durable store.
`analyzer_info.cljs`:

- `snapshot-defs` (analyzer_info.cljs:95-117) iterates
  `(get @compile-state :cljs.analyzer/namespaces)`, filters `symbol?` ns-keys (drops
  the `nil` constants-table ns) and `simple-symbol?` def-keys.
- `defs-since` (analyzer_info.cljs:119-145) diffs digests over the same map.
- `var-projection` (analyzer_info.cljs:165-198) maps `{:name :fn-var :arglists :meta}`
  → `:seon.fn/*` shapes (`:fn-var?`, `:arglists` pr-str'd, `:doc`, `:private?`,
  `:spec` from `:malli/schema`).

The boot indexer (`seon.indexing/specced-fn-vars`, indexing.clj:86-104) does the same
walk at COMPILE time to emit the core var list. So the ephemeral-core rebuild is not
new machinery — it is generalizing the walk seon already performs every boot to be
the SOLE source of the core program-graph, with the durable store holding only the
agent corpus.

**Soundness:** the map is fully populated after the build's analysis pass (the pod
boots with the whole compiled surface analyzed), and `var-projection`'s fields are all
present per the analyzer shape above. **Cost:** it is a single in-memory map walk over
~a few hundred defs — sub-millisecond, no I/O, no datahike round-trip. This is
strictly cheaper than the current durable-projection-plus-reconcile path (the
`core-index-tx` + `prune-core-ghosts!` split that the simplification audit's Finding 6
wants collapsed). **Verdict: ephemeral core is viable, cheap, and removes B1/B4/B5 +
Finding 6 by construction** — there is nothing stale to strand, drift, or GC if the
core view is rebuilt from the analyzer each boot.

One caveat for implementers: under `cljs.js` self-host eval the analyzer writes some
`:defs` keyed by FULLY-QUALIFIED multi-arity sub-records that carry no `:name`/
`:arglists` — seon already filters these with `simple-symbol?` (analyzer_info.cljs:116,
136-140). Keep that filter when generalizing the walk.

---

## Q3 — #8 / B7: override semantics from compiler source

### Why a dev `:none` cross-ns call reads the global var FRESH

`*cljs-static-fns*` defaults to `false` (analyzer.cljc:61):

```clojure
(def ^:dynamic *cljs-static-fns* false)
```

and is only set true under `:advanced` (or explicit `:static-fns`) — closure.clj:3023-3026:

```clojure
static-fns? (or (and (= (:optimizations opts) :advanced)
                  (not (false? (:static-fns opts))))
              (:static-fns opts)
              ana/*cljs-static-fns*)
```
bound at closure.clj:3043 `ana/*cljs-static-fns* static-fns?`. A dev `:none` build
leaves it false. **Live-confirmed on the pod: `goog.DEBUG=true`** (an unoptimized dev
build), so var references in the running pod are late-bound.

A var reference emits the munged fully-qualified global path (`emit-var`,
compiler.cljc:455-497):

```clojure
(defn emit-var
  [{:keys [info env form] :as ast}]
  ...
        info (cond-> info
               (not= form 'js/-Infinity) (munge reserved))]
    (emit-wrap env
      (case (:module-type js-module)
        ...
        (emits info)))))   ; <- the munged global path, e.g. seon.agent.message.reply_BANG_
```

At the call site, `:invoke` decides between direct-arity dispatch and a fresh read.
The discriminator is `fn?` (compiler.cljc:1198-1200):

```clojure
fn? (and ana/*cljs-static-fns*
         (not (:dynamic info))
         (:fn-var info))
```

When `*cljs-static-fns*` is false (dev), `fn?` is false, so the cond falls to the
`:else` (compiler.cljc:1300-1309):

```clojure
:else
(if (and ana/*cljs-static-fns* (#{:var :local :js-var} (:op f)))
  ... ; static path — NOT taken in dev
  (emits f ".call(" (comma-sep (cons "null" args)) ")"))
```

`f` here is the `:var` emitted by `emit-var` — the munged global path. So the call
compiles to `seon.agent.message.reply_BANG_.call(null, …)` — a **fresh read of the
global at every call**. This is the mechanism behind sandbox PROOF 1 (the audit's
Claim 1: `call-f` picked up `:v2-overridden` without recompilation).

### Why a re-eval'd `(defn …)` re-points the global (set! semantics)

A `:def` emits a plain assignment to that same munged global (`:def`,
compiler.cljc:862-895):

```clojure
(defmethod emit* :def
  [{:keys [name var init env doc goog-define jsdoc export test var-ast]}]
  (when (or init (:def-emits-var env))
    (let [mname (munge name)]
      ...
      (emits var)
      (when init
        (emits " = " (if-let [define (get-define mname jsdoc)] define init)))
      ...)))
```

i.e. `seon.agent.message.reply_BANG_ = <new fn>`. Re-evaling `(defn reply! …)`
re-assigns the global; every late-bound caller (above) reads the new value on its next
call. This IS the `set!`/redef re-point mechanism — no caller recompilation needed.
Replay-one! calling `(seval/eval source {:ns 'seon.agent.message})` produces exactly
this `:def` emission over the compiled var. (PROOF 2/4.)

### What breaks it under `:advanced`/`:static-fns` — the dev-build-only invariant

When `*cljs-static-fns*` is true, `fn?` is true (given `:fn-var`), and the call site
takes the direct-arity branch (compiler.cljc:1260-1272):

```clojure
;; direct dispatch to specific arity case
:else
(let [arities (map count mps)]
  (if (some #{arity} arities)
    [(update-in f [:info]
       (fn [info]
         (-> info
           (assoc :name (symbol (str (munge info) ".cljs$core$IFn$_invoke$arity$" arity)))
           ...)))) nil]
    [f nil]))
```

The caller is compiled to call `…reply_BANG_.cljs$core$IFn$_invoke$arity$1(…)` — the
specific arity METHOD captured against the var's value AT CALLER-COMPILE TIME. A later
re-point of the top-level var does NOT rewire callers that already inlined the arity
method, and `:advanced` munging/DCE can rename or drop the var entirely. **Therefore
per-function override is a dev-build-only capability.** The pod must stay
dev-compiled (`goog.DEBUG=true`, `*cljs-static-fns*` false) for override to work — the
exact same invariant the `:preloads`-based third-party merge depends on (Q1).
Implementers MUST assert/document this; an `:advanced` pod silently no-ops every
override.

### The re-export alias hazard (PROOF 3)

`(def reply! message/reply!)` compiles via `:def` to
`seon.agent.reply_BANG_ = seon.agent.message.reply_BANG_` — a VALUE assignment
captured at def-eval time. It does NOT re-read `message/reply!` on later calls (it is
not a function call site; it is a `:var` init expr evaluated once during the `:def`).
So an override of the DEFINING var `seon.agent.message/reply!` does not propagate to
the alias `seon.agent/reply!` until the alias `def` is itself re-evaled. This is a
CLJS late-binding fact independent of storage. Mitigation: after an override lands,
re-eval re-export aliases (or audit `seon.*` for `(def x other-ns/x)` shapes). A
one-line alias re-eval, NOT a new attribute.

### Validation against the sandbox PROOFs 1–4

All four sandbox proofs are consistent with the real emit:

- **PROOF 1 (late-bound cross-ns call):** confirmed — dev `:invoke :else` emits
  `f.call(…)` over the fresh munged global (compiler.cljc:1300-1309).
- **PROOF 2 (set!/redef re-point):** confirmed — `:def` emits a plain global
  assignment (compiler.cljc:876-881).
- **PROOF 3 (alias captures at def-time):** confirmed — `:def` init is a `:var` value
  expr, evaluated once; no late read.
- **PROOF 4 (replay-one! shadows the compiled var):** confirmed transitively —
  replay-one! is exactly the `:def` re-eval of PROOF 2 against a late-bound caller of
  PROOF 1.

The override therefore needs ZERO new attributes (consistent with the override-sandbox
research): the only code change is lifting the blanket core replay-skip to a
provenance test, so an `:agent`/`:override-dir`-origin source for a core sym enters the
replay set and re-points the compiled var via the above. Provenance lives on the tx
(`:seon.db/origin`); no `:seon.fn/override-target` / sort-tier / stacking-conflict.

---

## Recommended wiring + risks

### Third-party merge (Q1) — ship the operational fix, keep the dev invariant

- The build plumbing is already correct (`-Sdeps :local/root` + dev `:preloads`,
  bin/seon:101-111,143). The B2 gap is purely that `SEON_EXTRA_PRELOAD` is unset on
  the running `cljs-watch`, so the downstream entry ns is never a graph root. Fix:
  start `cljs-watch` with BOTH vars + a downstream entry ns that resets
  `!extra-core-vars`.
- RISK: dev-only `:preloads`. A `:release` pod drops the merge. Acceptable while the
  pod is dev-compiled; if it ever goes `:advanced`, move the entry into module
  `:entries` AND abandon per-fn override (same constraint, Q3).
- RISK: a brand-new downstream ns not yet required by the entry is not in the build
  (indexing.clj:22-24 freshness rule) — `restart cljs-watch` after adding it.

### Ephemeral core (Q2) — generalize the walk seon already does

- Build the core program-graph each boot from `:cljs.analyzer/namespaces` off the
  live compile-state (the `analyzer_info.cljs` walk), union it with the durable agent
  corpus at render time; persist ONLY agent code. Dissolves B1/B4/B5 + Finding 6.
- Keep the `simple-symbol?` / `symbol?` filters (drop `cljs.js` multi-arity
  sub-records + the `nil` constants ns).
- RISK: none material — it is in-memory, sub-ms, and the fields are guaranteed present
  post-analysis. The only behavioral change is that cross-agent queries over the core
  must read the ephemeral view, so render must union (ephemeral core ∪ stored agent).

### Per-function override (Q3) — the dev-build invariant is load-bearing

- Lift the core replay-skip to a provenance test (drop a core-ns row only when its
  current source's tx origin IS `:core-seed`); no new attribute. (Already detailed in
  override-sandbox-verify-2026-06-17.md §Recommendation.)
- RISK (HARD INVARIANT): override works ONLY on a dev `:none`/`:simple` build where
  `*cljs-static-fns*` is false. Assert `goog.DEBUG` (or refuse override + warn) so an
  `:advanced` pod doesn't silently no-op. This is the single most important
  cross-cutting fact tying Q1 and Q3 together: both the third-party merge AND override
  are dev-build capabilities by the same compiler mechanics.
- RISK (alias): re-export `(def x other/x)` aliases capture at def-time; re-eval them
  after an override lands (one-line audit, not an attribute).
- RISK (kernel target): overriding `seon.eval/eval`, `db/transact!`,
  `schema/register!`, `replay-program-graph!` can no-op the override on the next boot
  (the store is read THROUGH those fns). Loud warning + `:repl`-only posture, not an
  attribute.

---

## Cross-references

- `docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md` — B2/B6/B7,
  Finding 6 (this doc supplies the shadow/cljs SOURCE grounding those findings asked
  for).
- `docs/prds/agent-runtime/research/override-sandbox-verify-2026-06-17.md` — the live
  override PROOFs (this doc validates them against the real emit).
- `docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md` — the original
  `SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD` design (this doc confirms its merge mechanism
  against shadow source and pins the dev-only `:preloads` risk).
- CLJS compiler source (v1.12.145, pinned): extracted to `tmp/cljs-src-1.12.145/cljs/`
  from `~/.m2/repository/org/clojure/clojurescript/1.12.145/clojurescript-1.12.145.jar`
  — `analyzer.cljc` (`::namespaces` 569-585, `:defs` shape 2122-2167,
  `*cljs-static-fns*` 61), `compiler.cljc` (`emit-var` 455-497, `:def` 862-901,
  `:invoke` 1195-1309), `closure.clj` (static-fns binding 3023-3046).
- shadow-cljs source: `reference-code/shadow-cljs/src/main/shadow/build/resolve.clj`
  (`resolve-entries` 714), `…/targets/shared.clj` (`inject-preloads` 252),
  `…/targets/node_script.clj` (dev preload inject 45-48).
- seon: `src/seon/analyzer_info.cljs` (analyzer walk), `src/seon/indexing.clj`
  (`specced-fn-vars`/`deftest-vars` compile-time walk), `src/seon/client.cljs`
  (`!extra-core-vars` 975, `extra-core-vars*` 977, reserved-prefix guard 1005),
  `src/seon/dev/test_preload.cljs` (the preload-as-trigger precedent), `bin/seon`
  (`extra_src_sdeps`/`extra_preload_merge` 101-111, cljs-watch wiring 143).

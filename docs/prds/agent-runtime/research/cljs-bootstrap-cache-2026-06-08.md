---
type: research
status: active
tags: [research, cljs, agent]
---

# CLJS bootstrap analyzer cache — can it index the compiled substrate at runtime? (2026-06-08)

> Question (from the orchestrator): can we make the COMPILED substrate
> namespaces (`seon.db`, `seon.agent`, `seon.eval`, …) accurately indexable at
> RUNTIME — real source, arglists, specs — via the shadow-cljs bootstrap /
> analyzer-cache mechanism, so we can replace the handcrafted 7-fn stub table
> (`seon.client/core-fn-curated` + `seed-core-fns!`) with real runtime
> introspection? This note is REPL-verified against the live pod (MCP session
> "default", `:client` build) and anchored in `reference-code/shadow-cljs`.

## TL;DR / recommendation

**The bootstrap cache CAN give us accurate `:arglists` and a real SOURCE string
for any substrate ns we add to `:bootstrap :entries`. It does NOT give us
per-FUNCTION source — only whole-namespace source — and it does NOT carry the
function spec in a directly-usable place. The function SPEC (`:malli/schema`)
must come from runtime var meta in every case.**

Concrete recommendation, in priority order:

1. **Spec + doc: use runtime var meta — always, for every substrate fn.**
   `(:malli/schema (meta #'seon.db/transact!))` is the EXACT form and round-trips
   through `m/form`. This is the single most important fix (today's curated table
   hardcodes `:specced? false`, which is WRONG for `transact!`/`query`/`pull`/
   `entity`). The bootstrap cache does NOT help here — `:malli/schema` is absent
   from the cached analyzer var-map (verified below). **Do this regardless of
   whether we touch the bootstrap config.**

2. **Arglists: prefer the bootstrap analyzer cache; fall back to var meta.**
   Runtime var meta `:arglists` is MANGLED for instrumented fns (`transact!` →
   `([arg])`) but intact for non-instrumented (`query`/`pull` keep full
   destructuring). The bootstrap analyzer cache has the REAL pre-instrumentation
   `:arglists` for any cached ns. So adding `seon.db` etc. to `:bootstrap
   :entries` buys accurate arglists for the instrumented fns. Without it, a
   per-var arglists override in the substrate-vars list (the PRD's §2.2 plan)
   covers the handful of mangled fns at near-zero cost.

3. **Real per-FUNCTION source: NOT available from the cache.** The bootstrap
   `/src/<ns>.cljc` file is the WHOLE namespace source, and the analyzer var-map
   has no source key and an unreliable line-span (`:end-line` == `:line`). So you
   cannot cheaply carve a `(defn transact! …)` form out of the cache. For
   substrate fns, **omit `:seon.fn/source`** (honest absence, as the PRD already
   recommends) rather than synthesizing the `,,,` stub.

So: the cache is worth adding ONLY to get real arglists for instrumented fns and
(optionally) whole-ns source for `:seon.ns` rows — at a real build/bundle cost
(adding `seon.db` pulls datahike-cljs's analyzer subtree). The spec fix —
which is the actual bug — needs NO bootstrap change. **Net: do the var-meta
projector now (kills the curated table, fixes specs); treat bootstrap-cache
expansion as an optional arglists/ns-source upgrade, gated on measuring the
bundle cost.**

---

## 1. What the bootstrap cache holds (and what it does NOT)

The `:bootstrap` target (`shadow-cljs.edn:` the `:bootstrap` build, `:output-dir
"out/bootstrap"`) emits THREE artifacts per namespace plus one index. Verified on
disk (`out/bootstrap/`, last built **May 24** — note: STALE, see §6):

| Artifact | Path | Contents | Has SOURCE? | Has SPEC? |
| --- | --- | --- | --- | --- |
| Index | `index.transit.json` (26 KB) | per-source `{:ns :source-name :ana-name :js-name :type :requires :deps :provides :timestamp}` — a transit map. NO source text, NO ana data inline. | no | no |
| Analyzer cache | `ana/<ns>.transit.json` (45 files) | transit of `(get-in compiler-env [:cljs.analyzer/namespaces ns])` — the var-maps. | **no** | **no** |
| Source | `src/<ns>.cljc` | the **WHOLE namespace** source text as written. | whole-ns only | n/a |
| Compiled JS | `js/<ns>.js` | per-ns compiled JS (for runtime `require`). | no | no |

### Where each artifact comes from (reference-code anchors)

`shadow.build.targets.bootstrap/prepare-output`
(`reference-code/shadow-cljs/src/main/shadow/build/targets/bootstrap.clj:99-208`)
builds each source's output map:

- `:source source` (`bootstrap.clj:196`) — the raw `:source` text of the
  resource, i.e. the **entire `.cljc`/`.cljs` file**. Written to
  `src/<flat-name>` at `flush` (`bootstrap.clj:250`).
- `:ana-json (cache/write-str ana)` where `ana = (get-in state [:compiler-env
  :cljs.analyzer/namespaces ns])` (`bootstrap.clj:152-156`). This is the analyzer
  namespace map — `:defs`, `:requires`, etc. Written to `ana/<ns>.transit.json`
  (`bootstrap.clj:254`).
- `make-index` (`bootstrap.clj:91-97`) explicitly `dissoc`'s `:source`,
  `:js`, and `:ana-json` from the index — so the index is metadata-only;
  the bulky source/ana/js live in their own per-ns files, read on demand.

**Critical: the analyzer cache var-map does NOT contain the def-form source, and
does NOT contain `:malli/schema`.** REPL-verified against the live cached
`seon.schema/register!` var-map:

```clojure
;; (get-in @@seon.repl/!compile-state
;;         [:cljs.analyzer/namespaces 'seon.schema :defs 'register!])
:var-map-keys (:arglists :arglists-meta :column :doc :end-column :end-line
               :end-line :file :fn-var :line :max-fixed-arity :meta
               :method-params :name :protocol-impl :protocol-inline :variadic?)
:has-source-key? false          ; NO source text in the var-map
:malli/schema (in :meta)  => nil ; register! genuinely unspecced; but note:
                                 ; even a SPECCED fn's :malli/schema is NOT
                                 ; reliably preserved in the cached :meta —
                                 ; the analyzer keeps :doc but drops the metadata
                                 ; not relevant to compilation.
:arglists (quote ([k v]))        ; REAL, pre-instrumentation
:doc "Register a single schema…"  ; REAL
:line 119  :end-line 119         ; SAME — line span is NOT a usable source range
:file "seon/schema.cljc"

```

So the analyzer cache gives you, per fn: **real `:arglists`, `:doc`,
`:fn-var`, `:private`, `:line`/`:file`** — but **NOT source text** and **NOT the
schema form**. The whole-ns source lives separately in `src/<ns>.cljc`.

### Why `:end-line` == `:line` matters

You might hope to carve a per-def `(defn …)` substring from the whole-ns source
using `[:line :end-line]`. You can't: the analyzer records `:end-line` as the end
of the def's *first* line (119 == 119 for `register!`), not the closing paren.
Carving real per-fn source from the bootstrap cache would require re-parsing the
`.cljc` file with the reader and matching forms — at which point you've left
"introspection" and re-entered "parse source", which CLAUDE.md's code-as-data
principle explicitly says not to do.

---

## 2. How the bootstrap target decides WHAT to cache

`shadow.build.targets.bootstrap/resolve`
(`reference-code/shadow-cljs/src/main/shadow/build/targets/bootstrap.clj:45-89`):

1. Starts from `(into '[cljs.core] entries)` — the `:entries` list in the build
   config, with `cljs.core` always prepended.
2. `resolve/resolve-entries` walks the **full transitive require graph** of those
   entries (`bootstrap.clj:52-53`). EVERY transitively-required ns gets cached.
3. Macros are resolved in a second pass from each dep's `:macro-requires`
   (`bootstrap.clj:60-73`) plus any explicit `:macros` config; each becomes a
   `<ns>$macros` resource.
4. `:exclude` removes nses from macro inclusion (`bootstrap.clj:64`).

So **`:entries` is the only knob** — add a ns there and its entire transitive
subtree is cached (ana + src + js per ns). Our current `:bootstrap :entries`
(`shadow-cljs.edn`, the `:bootstrap` build):

```clojure
:entries [cljs.core cljs.test clojure.set clojure.string clojure.walk
          seon.schema malli.core malli.registry cljs.analyzer.api]
:macros  [cljs.core cljs.test]

```

That transitive closure produces the **45 `ana/` files / 88 index sources** we
have on disk — the only `seon` one is `seon.schema`. `seon.db` and `seon.agent`
are NOT entries and NOT transitively reachable from these entries, so they're
absent. REPL-verified against the live env index:

```clojure
;; @shadow.cljs.bootstrap.env/index-ref
:sources-count 88
:has-seon.schema? true   ; {:ns seon.schema :source-name "/src/seon.schema.cljc"
                         ;  :ana-name "/ana/seon.schema.transit.json"
                         ;  :js-name "/js/seon.schema.js" :type :cljs}
:has-seon.db? false

```

And the live compile-state confirms what's actually loaded:

```clojure
;; @@seon.repl/!compile-state → :cljs.analyzer/namespaces
:ns-count 55
:seon-nses [seon.agent.cUk-2606081347  ; the agent's own home ns (created at boot)
            seon.dynamic                ; loaded via require-from-eval, see §4
            seon.schema]                ; the only bootstrap-cached seon ns
:seon.db-present? false
:seon.agent-present? false

```

---

## 3. How to ADD the substrate nses to the cache (config + cost)

### The change

It's a one-line config edit in `shadow-cljs.edn`'s `:bootstrap` build:

```clojure
:entries [cljs.core cljs.test clojure.set clojure.string clojure.walk
          seon.schema malli.core malli.registry cljs.analyzer.api
          seon.db]            ; ← add the substrate nses we want indexed

```

Then rebuild: `clj -M:cljs release bootstrap` (or `compile bootstrap`). The
target re-walks transitive deps and writes fresh `ana/`, `src/`, `js/` for every
newly-reachable ns. No runtime code change is required for the cache to be
PRESENT — `seon.eval/load-all-analysis-caches!` (`src/seon/eval.cljs:152-171`)
already loads *every* `ana/*.transit.json` it finds at boot, unconditionally
(that's its whole point — "any namespace listed in `:bootstrap :entries`
automatically lands in the analyzer state"). So once `seon.db.transit.json`
exists, `var-projection` (`src/seon/analyzer_info.cljs:152`) can project it with
no further wiring.

### The cost (this is the catch — measure before committing)

Adding `seon.db` to `:entries` pulls its **entire transitive analyzer subtree**
into `out/bootstrap` (ana + src + js per ns). `seon.db` requires the
datahike-cljs stack:

- `seon.db` → `seon.db.datahike.*` → `datahike.api` → datahike-cljs core →
  `me.tonsky.persistent-sorted-set`, `konserve`, `superv.async`, hitchhiker-tree,
  etc. This is a LARGE subtree (datahike-cljs is the heaviest dep in the pod).
- Each adds an `ana/` transit file (loaded at every boot by
  `load-all-analysis-caches!`, which reads + transit-parses each file
  synchronously) and a `js/` file and a `src/` file.
- Build time: the bootstrap build already takes the longest of the CLJS builds;
  this roughly doubles its source count.
- Boot time: `load-all-analysis-caches!` is O(files) and runs on every
  `init-bootstrap!`. Doubling the ana-file count measurably slows pod start.
- It does NOT bloat the RUNTIME pod bundle (`out/client/main.js`) — bootstrap
  output is a separate dir read at eval time, not bundled. So the cost is
  build-dir size + boot load time, not the shipped binary.

`seon.agent` is even heavier (it requires the whole agent/render/handler tree).
`seon.eval` requires `cljs.js` + `shadow.cljs.bootstrap.node` (already cached as
deps of other entries, but the seon ns itself isn't).

**Verdict:** adding `seon.db` to `:entries` is a real, non-trivial cost for a
PARTIAL win (real arglists + whole-ns source, but still no per-fn source, and the
spec STILL has to come from var meta). The PRD's §2.2 call — var-meta for spec/doc
plus a per-var arglists override for the few mangled fns, deferring cache
expansion — is the right cost/benefit. Revisit cache expansion only if the corpus genuinely
needs whole-ns `:seon.ns/source` for substrate nses on the rendered namespace
view.

---

## 4. Runtime load — how cljs.js loads the cache, and lazy per-ns loading

### Two load paths, both present in the pod

**Path 1 — shadow's lazy `:load` fn (per-ns, on-demand).**
`shadow.cljs.bootstrap.node/load`
(`reference-code/shadow-cljs/src/main/shadow/cljs/bootstrap/node.cljs:156-168`) is
the `cljs.js/*load-fn*`. When cljs.js eval encounters a `(require 'foo)` it
hasn't loaded, it calls this `:load`, which calls `load-namespaces`
(`node.cljs:60-154`). `load-namespaces`:

- computes transitive deps via `env/find-deps` from the index (`node.cljs:67`),
- loads each missing ns's JS via `goog.globalEval` and analyzer cache via
  `cljs/load-analysis-cache!` (`execute-load!`, `node.cljs:42-58`),
- skips anything already in `[:cljs.analyzer/namespaces ns :name]`
  (`node.cljs:104`) and anything already in `@cljs/*loaded*`.

So shadow's design is **genuinely lazy and per-ns**: a ns's analyzer state is
loaded only when an eval requires it. This is how `seon.dynamic` got into the
live compile-state (it was `require`d from eval, not eagerly loaded) while
`seon.db` was not.

**Path 2 — the pod's eager `load-all-analysis-caches!` (all-at-once).**
`seon.eval/init-bootstrap!` (`src/seon/eval.cljs:234-267`) calls `boot/init` (which
runs Path-1 for `cljs.core` + `:load-on-init`) and THEN calls
`load-all-analysis-caches!` (`eval.cljs:258`) which force-loads EVERY
`ana/*.transit.json` file eagerly. The docstring (`eval.cljs:160-165`) explains
why: `(cljs/empty-state)` runs `dump-core` which leaves `:name`-set stubs that
make shadow's `node.cljs:104` filter short-circuit, so the lazy path wouldn't
reliably load the seon/malli entries. Eager loading is the robust answer.

### Can we do lazy per-ns load of a substrate ns at runtime?

**Yes — IF the ns is in the bootstrap index** (i.e. it's an `:entries` member or
transitive dep, so `ana/`, `js/`, and an index entry exist). Then
`(boot/load-namespaces @seon.repl/!compile-state #{'seon.db} cb)` would load just
`seon.db` (+ its missing deps) on demand. The index `:sources` entry carries
`:ana-name`/`:js-name`/`:source-name` per ns (REPL-verified for `seon.schema`),
which is exactly what `load-namespaces` consumes.

But this does NOT let us avoid the build cost: lazy LOADING is per-ns, but lazy
CACHE-GENERATION is not — the `ana/`/`src`/`js` files must exist on disk, which
means the ns must be a build entry/dep, which means its whole subtree is built and
written. Lazy load saves BOOT time (don't eagerly load every ana file) but not
BUILD time or disk size. If we add `seon.db`, we could switch
`load-all-analysis-caches!` to a lazy allowlist (load `seon.db`'s ana only when
`index-substrate!` asks for it) to claw back boot time — a reasonable follow-up,
but orthogonal to the indexing question.

### Pod wiring summary (our-code anchors)

- `seon.repl/ensure-bootstrap!` (`src/seon/repl.cljs:87-103`) — lazy-inits and
  caches the compile-state in `!compile-state` (atom-of-atom), keyed by
  `init-version` for hot-reload staleness.
- `seon.eval/init-bootstrap!` (`src/seon/eval.cljs:234-267`) — builds the state:
  `boot/init` → `load-all-analysis-caches!` → globalThis assertion.
- `seon.eval/load-all-analysis-caches!` (`src/seon/eval.cljs:152-171`) +
  `bootstrap-cache-files` (`eval.cljs:128-150`) — the eager all-ana loader.
- `seon.analyzer-info/var-projection` (`src/seon/analyzer_info.cljs:152-176`) —
  the read-side projector that turns a cached var-map into a `:seon.fn` shape.
  Reads only `:name :fn-var :arglists :meta{:doc :private :malli/schema}` — so it
  ALREADY can't see source (the cache has none) and ALREADY relies on `:meta` for
  the schema (which is why register! correctly reads unspecced).

---

## 5. Live REPL evidence (substrate fn metadata availability)

Verified against the running `:client` pod, MCP session "default":

```clojure
;; runtime var meta — the ONLY reliable source of the spec
(:malli/schema (meta #'seon.db/transact!))
;; => [:=> [:cat :seon.db/transact-request] :seon.db/transact-response]
(:malli/schema (meta #'seon.db/query))
;; => [:=> [:cat :seon.db/query-request] :any]
(:malli/schema (meta #'seon.schema/register!))
;; => nil   ; genuinely unspecced — ABSENCE is correct

;; m/form round-trips it cleanly (PRD's :seon.fn/spec string)
(malli.core/form (malli.core/schema
                   (:malli/schema (meta #'seon.db/transact!))))
;; => [:=> [:cat :seon.db/transact-request] :seon.db/transact-response]

;; runtime :arglists — MANGLED for instrumented fns, intact otherwise
(:arglists (meta #'seon.db/transact!))  ; => ([arg])   ← instrumented, mangled
(:arglists (meta #'seon.db/query))      ; => ([{:seon.db/keys [query args db conn]
                                        ;       :or {conn *conn* args []}}])  ← intact
(:arglists (meta #'seon.db/pull))       ; => ([{:seon.db/keys [pull-pattern ref db conn]
                                        ;       :or {conn *conn*}}])           ← intact
(:arglists (meta #'seon.schema/register!)) ; => ([k v])  ← intact (not instrumented)

```

This is the crux: **spec is reliably in var meta and nowhere in the cache;
arglists are reliable in the cache and unreliable in var meta for instrumented
fns.** The two sources are complementary. The PRD §2.1-2.2 hybrid is correct.

---

## 6. Side findings / code smells

- **The on-disk bootstrap cache is STALE.** `out/bootstrap/` was last built
  **May 24** (`index.transit.json`, all `ana/`/`src/` files). But `src/seon/
  schema.cljc` was modified **May 27** and is now 440 lines / 17 KB vs the
  cached `out/bootstrap/src/seon.schema.cljc` at 234 lines / 8 KB. So the live
  pod's analyzer view of `seon.schema` is ~3 weeks behind the actual source.
  Any consumer that trusts the bootstrap cache's `seon.schema` arglists/doc is
  reading May-24 reality. This is a latent correctness hazard for the
  analyzer-projection path and an argument FOR var-meta (which is always
  current) over cache for anything that changes. **Recommend: rebuild bootstrap
  (`clj -M:cljs release bootstrap`) and/or wire the bootstrap rebuild into the
  pod build so it can't drift.** Flagging — not fixing (out of read-only scope).

- **`load-all-analysis-caches!` is eager and O(all-ana-files) on every boot.**
  Fine at 45 files; becomes a boot-time tax if `:entries` grows (e.g. adding
  `seon.db`'s datahike subtree). If cache expansion happens, switch to an
  allowlist-driven lazy load (Path 1) keyed off the `index-substrate!` var list.

- **`seed-core-fns!`'s `_compile-state` arg is dead** (`client.cljs:673`) — the
  docstring's "when more substrate nses land in `out/bootstrap` we'll prefer the
  analyzer projection" is the intended-but-undone fix this research validates.
  The `,,,` synthesized source (`client.cljs:644-658`) is the stub the PRD
  already plans to delete.

---

## 7. Answer to the orchestrator's question

**Can we get real source + arglists + spec for the compiled substrate at runtime
via the bootstrap cache?**

- **Spec**: NO via cache (not stored there); YES via runtime var meta
  (`(meta #'…)` → `m/form`). Authoritative, always-current. **This is the fix
  for the actual bug** (curated table's hardcoded `:specced? false`).
- **Arglists**: YES via cache (real, pre-instrumentation) for any cached ns; via
  var meta it's intact for non-instrumented fns and mangled (`([arg])`) for
  instrumented ones. A per-var arglists override covers the mangled handful
  without touching the build.
- **Per-function source**: NO from the cache. The cache stores only whole-ns
  source (`src/<ns>.cljc`) and a var-map with no source key and an unusable line
  span. Honest answer: omit `:seon.fn/source` for substrate fns (don't
  synthesize a stub).
- **Whole-namespace source**: YES via cache (`src/<ns>.cljc`) for any cached ns —
  usable for `:seon.ns/source` if we want it, at the build cost in §3.

**Exact build/config change to enable cache-backed substrate indexing (optional
arglists/ns-source upgrade):** add the substrate nses to the `:bootstrap`
build's `:entries` in `shadow-cljs.edn` (e.g. `… cljs.analyzer.api seon.db]`),
then `clj -M:cljs release bootstrap`. No runtime wiring change needed —
`load-all-analysis-caches!` (`src/seon/eval.cljs:152`) already loads every
`ana/*.transit.json`, and `var-projection` (`src/seon/analyzer_info.cljs:152`)
already projects from the var-map. **But the real bug (specs) is fixed entirely
in code via the var-meta projector the PRD already specs — no bootstrap change
required.** Recommend doing the var-meta projector now and deferring cache
expansion until/unless we want real arglists for instrumented fns or whole-ns
substrate source in the corpus, and only after rebuilding the currently-stale
cache and measuring the datahike-subtree build/boot cost.

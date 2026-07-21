---
type: research
status: active
tags: [research, cljs, agent]
---

# Index + replay, grounded in nREPL / CIDER / cljs.js self-host

## TL;DR

The right model is **NOT CIDER/nREPL** — it is `cljs.js`'s OWN
`*load-fn*` / `:load` callback, which the pod's compiler already calls.
nREPL `load-file` is just whole-file `eval` with no dependency logic
(`nrepl/.../load_file.clj:60-63`); CIDER's reload delegates 100% to
`clojure.tools.namespace` (`cider-nrepl/.../refresh.clj:11-14,92-96`) —
a JVM-only file-graph reloader with no analogue the pod can reuse.
Meanwhile `cljs.js/eval-str` already sequences dependency loads itself:
analyze the `(ns …)` form → `ns-side-effects` → `load-deps` → `require`
→ `*load-fn*` per dep, with circular-dep detection (`*cljs-dep-set*`,
`js.cljs:390-403`) and `*loaded*` memoization (`js.cljs:291,312`). seon
**already binds** `:load (partial guarded-load …)` (`eval.cljs:493`) —
today that load-fn resolves deps from the compiled bundle + globalThis,
never the DB. The recommended design: **store one `:seon.ns` source row
per ns (ns-form + all its defs, byte-faithful) plus a denormalized
`:seon.ns/requires` for cheap topo-sort, and make replay a DB-backed
`*load-fn*`** so `cljs.js` itself sequences the dependency load exactly
as it does for compiled deps — deleting `replay-program-graph!`'s
per-definition + tx-order + 2-pass-retry hack
(`client.cljs:789-840`). Override becomes a plain upsert of the
target's `:seon.fn` row; the ns reconstitution re-emits the latest
source, last-write-wins on `:seon.fn/sym`. The irreducible kernel is
the set of nses that must be compiled because the DB-load path itself
calls them: the cljs compiler (`cljs.js`/analyzer), `seon.eval`,
`seon.db`, `seon.schema`, `seon.repl` (bootstrap compile-state), and the
replay driver in `seon.client`.

Reference code cloned this session into `reference-code/`:
`git clone --depth 1 .../cider-nrepl` and `.../orchard`. nREPL already
vendored at `reference-code/nrepl`. cljs compiler source read from
`tmp/cljs-src-1.12.145/cljs/`.

---

## Q1 — Is CIDER even the best model? (three loaders compared)

### (a) nREPL `load-file` — whole-file eval, ZERO dependency logic

`nrepl.middleware.load-file` does not load anything itself; it rewrites
the `load-file` op into an `eval` op whose `:code` is the entire file
string and delegates to `interruptible-eval`
(`reference-code/nrepl/src/clojure/nrepl/middleware/load_file.clj:60-63`):

```clojure
(-> (dissoc msg :file-path)
    (assoc :op "eval", :code file, :transport wrapped-t, :file file-path
           ::eval/stop-on-error true
           ::eval/bindings (per-file-bindings msg)))
```

The header comment is explicit that there is no dependency machinery —
it used to call `Compiler/load` and now just delegates to eval
(`load_file.clj:11-17`). **Dependency ordering is entirely the client's
problem** (the editor sends files in whatever order the user/`require`
chain dictates). Conclusion: `load-file` ≈ "eval this big string"; it
maps onto seon's *per-ns bulk eval* but contributes NOTHING about how
to order nses.

### (b) orchard + CIDER reload — delegates to `clojure.tools.namespace`

orchard's `namespace.clj` does NO topo-sort — `loaded-namespaces` /
`classpath-namespaces` only filter + alphabetically `sort`
(`reference-code/orchard/src/orchard/namespace.clj:85-94,115-123`). The
dependency-ordered reload lives in `cider-nrepl`'s `refresh`
middleware, which is a thin wrapper over `clojure.tools.namespace`
(`reference-code/cider-nrepl/src/cider/nrepl/middleware/refresh.clj:11-14`):

```clojure
[clojure.tools.namespace.dir :as dir]
[clojure.tools.namespace.find :as find]
[clojure.tools.namespace.reload :as reload]
[clojure.tools.namespace.track :as track]
```

and (`refresh.clj:85-96`):

```clojure
(vreset! refresh-tracker (track/tracker))
(vswap! refresh-tracker
        … (dir/scan-dirs (or (seq dirs) (user-refresh-dirs)) …)
        … (reload/track-reload))
```

`dir/scan-dirs` parses every file's `(ns …)` form, builds a dependency
graph from `:require`s, and writes a **topo-sorted load list** into
`::track/load`; `reload/track-reload` re-`require`s in that order. This
is the canonical JVM model: **full-namespace granularity, ordered by the
`(ns … (:require …))` graph.** But it is JVM-file-system bound
(`dir/scan-dirs`, JAR resources) and offers no self-host equivalent.
seon would have to RE-IMPLEMENT a topo-sort over its own DB rows to use
this model — which is exactly the hack `replay-program-graph!` is.

### (c) `cljs.js` self-host `*load-fn*` — the loader the pod ALREADY calls

`cljs.js/eval-str` (and `require`) sequences dependency loads natively.
`*load-fn*`'s contract (`tmp/cljs-src-1.12.145/cljs/js.cljs:74-99`):

```
Whatever function *load-fn* is bound to will be passed two arguments - a
map and a callback function: The map will have the following keys:
  :name   - the name of the library (a symbol)
  :macros - modifier signaling a macros namespace load
  :path   - munged relative library path (a string)
It is up to the implementor to correctly resolve the corresponding .cljs,
.cljc, or .js resource … Upon resolution the callback should be invoked
with a map containing … :lang :source :file :cache :source-map …
If the resource could not be resolved, the callback should be invoked
with nil.
```

When `eval-str` hits an `(ns …)` form, `ns-side-effects` calls
`load-deps` for the form's `:deps` (`js.cljs:628-632`):

```clojure
(and load (seq (:deps ast)))
(let [{:keys [reload name deps]} ast]
  (load-deps bound-vars ana-env name deps … #(check-uses-and-load-macros …)))
```

`load-deps` recursively walks deps, calling `require` (→ `*load-fn*`)
for each, in order, with **circular-dependency detection**
(`js.cljs:390-403`):

```clojure
(binding [ana/*cljs-dep-set* (… conj (:*cljs-dep-set* bound-vars) lib) …]
  …
  (if-not (every? #(not (contains? ana/*cljs-dep-set* %)) deps)
    (cb (wrap-error (ana/error ana-env (str "Circular dependency detected " …))))
    (if (seq deps)
      (let [dep (first deps) …]
        (require bound-vars dep reload opts'
          (fn [res]
            (if-not (:error res)
              (load-deps bound-vars ana-env lib (next deps) nil opts cb) …)))))))
```

and `require` memoizes via `*loaded*` so a dep is loaded exactly once
(`js.cljs:291,312,338`):

```clojure
(if-not (contains? @*loaded* aname)
  (… ((:*load-fn* bound-vars) {:name name …} (fn [resource] … (swap! *loaded* conj aname) …)))
  (cb {:value true}))
```

**This is a complete dependency-ordered loader.** It already does the
topo-walk, the cycle-detection, and the load-once memoization that
tools.namespace does on the JVM — but it is the pod's OWN compiler, and
seon already drives it.

### Recommendation (Q1)

**Follow the `cljs.js` `*load-fn*` model — not CIDER/nREPL.** Rationale,
all source-backed: (1) nREPL `load-file` gives no ordering; (2)
tools.namespace gives ordering but is JVM-file-bound and would force
seon to hand-roll a topo-sort (the current hack); (3) `cljs.js`
`*load-fn*` IS the pod's native loader, already wired at `eval.cljs:493`,
and already does dependency sequencing + cycle detection + load-once.
Making "load from the DB" a `*load-fn*` makes it native to the
compiler's own loader instead of a parallel replay engine.

---

## Q2 — The right INDEX shape

### What the JVM model needs vs. what cljs.js needs

tools.namespace orders by **full-ns granularity, keyed on the
`(ns … (:require …))` form** — there are NO per-fn dependency edges;
`dir/scan-dirs` reads only ns forms. `cljs.js` is the same: `load-deps`
walks `(:deps ast)` — the ns form's deps — not per-var edges
(`js.cljs:628-632`). **Per-fn dependency edges are NOT needed for load
order.** Forward references WITHIN an ns are resolved by the analyzer at
eval time, not by ordering (a `(defn a [] (b))` before `(defn b …)` in
the same ns string analyzes fine because the whole ns body is one
`eval-str` pass and cljs.js's analyzer tolerates same-ns forward refs;
this is why bulk-per-ns eval works where per-def eval needs the retry
hack).

### What seon stores today

- `:seon.ns/name` (identity) + `:seon.ns/source` (the ns form text,
  which contains `(:require …)`) — `ctx.cljs:74-75`, written at
  `eval.cljs:1075`, `client.cljs:1162`.
- `:seon.fn/sym` (identity) + `:seon.fn/source` (one defining form) +
  `:fn-var?` etc. — `analyzer_info.cljs:193-198`, `eval.cljs:1031-1042`.
- **`:seon.ns/requires` is NOT stored** (grep: no hits in `src/seon/*.cljs`).

So requires are recoverable from `:seon.ns/source` by reading the ns
form — but that means every topo-sort must re-read+parse N ns sources.

### Recommended minimal index

Two attrs added to the ns row, both derivable at index/tee time from the
analyzer (do NOT re-parse with rewrite-clj — the analyzer already has
`:requires`; concept doc `code-as-data-runtime.md:28,130-133`):

1. **`:seon.ns/requires` — `[:vector :keyword]`** — the ns's required
   ns names (the analyzer's `:requires`/`:deps`, filtered to nses seon
   loads from the DB; bundle/host nses excluded since `*load-fn*` answers
   those from the bundle). This is the ONLY edge set needed for a
   deterministic topo-sort, mirroring `dir/scan-dirs`. A pure
   `d/q` over `[?e :seon.ns/requires ?r]` then feeds a standard
   Kahn/DFS topo-sort — no source re-parse, no retry.
2. **`:seon.ns/source`** stays as the byte-faithful FULL ns text used
   for reconstitution, OR (preferred, see Q3) the ns source is
   *reconstituted* on demand from `:seon.ns` (ns form) + its
   `:seon.fn`/`:seon.schema`/`:seon.test` rows. Either way the per-fn
   rows remain the authoritative per-identity sources (override target).

`:seon.fn` rows keep `:seon.fn/sym` + `:seon.fn/source` + `:fn-var?`;
**no per-fn dependency attr** — load order is ns-granular. The minimal
index that makes replay deterministic is: **ns identity + ns requires +
per-fn source**, nothing finer.

---

## Q3 — The right REPLAY / LOAD mechanism

Two candidates, both grounded:

### Option A — reconstitute one source string per ns, bulk-eval, topo-ordered (the concept-doc / `load-file` model)

The concept doc already prescribes this
(`code-as-data-runtime.md:68-82`): "For each persisted `:seon.ns`,
reconstitute one source string … Topo-sort over `:seon.ns/requires`;
bulk-eval each ns-string as a file." This is the `load-file` shape (one
big string per ns) plus an EXTERNAL topo-sort seon computes from
`:seon.ns/requires`. It works, and it removes the per-def + tx-order +
retry hack — but seon owns the ordering loop, the cycle handling, and
the "did this ns's deps load yet" bookkeeping. That is re-implementing
`load-deps`/`*loaded*` by hand.

### Option B — DB-backed `*load-fn*`, let cljs.js sequence the load (RECOMMENDED)

Bind a `*load-fn*` that, given `{:name <ns-sym>}`, returns
`{:lang :clj :source <reconstituted-ns-source>}` pulled FROM THE DB.
Then a single `eval-str` of the entry-point ns (or a `require` of each
top-level ns) drives the ENTIRE dependency load: cljs.js analyzes each
ns form, sees its `:require`s, and calls the DB `*load-fn*` for each
unloaded dep, in dependency order, with cycle detection and load-once
already provided (`js.cljs:291-353,384-430,628-632`). seon writes ZERO
ordering code — the compiler's own loader sequences the DB load exactly
as it sequences a compiled-dep load.

The reconstituted ns source the `*load-fn*` returns is built the
code-as-data way (concept doc `:38-40`): `(ns …)` form (with its
`:require`s) followed by every `:seon.fn`/`:seon.schema`/`:seon.test`
row for that ns, in any order (same-ns forward refs are fine — one
`eval-str` pass). This is where **override-by-upsert lands for free**:
the reconstitution query reads the CURRENT `:seon.fn/source` per sym, so
an upserted override row IS the source the loader sees; last-write-wins
on `:seon.fn/sym`.

### Why B over A

- seon ALREADY binds `:load` (`eval.cljs:493`) and already has the
  `guarded-load` resolution-order skeleton (`eval.cljs:412-459`). The
  change is *adding a DB branch* to that load-fn, not building a new
  replay engine.
- B inherits cljs.js's cycle detection (`js.cljs:390-403`) and
  load-once memoization (`*loaded*`, `js.cljs:291`) — A re-implements
  both.
- B makes the DB a first-class resource resolver alongside the
  bundle/host, which is precisely "the database IS the running system":
  the compiler can't tell a DB-loaded ns from a compiled one.
- B deletes `replay-program-graph!` (`client.cljs:789-840`),
  `replay-one!`, the tx-order `sort-by` (`client.cljs:693`), the 2-pass
  `!failed`/retry, AND `ensure-target-ns!`'s bare-`(ns)` heal hack
  (`client.cljs:722-752`) — the heal hack exists ONLY because per-def
  replay evals a def before its ns; under B the ns form is always the
  head of its own reconstituted source, so the ns is created first by
  construction.

**Resolution order inside the DB `*load-fn*`** (extends `guarded-load`):
1. compile-state already has the ns → cljs.js short-circuits via
   `*loaded*` (no load-fn call).
2. ns is a compiled bundle/host ns → existing `boot/load` / globalThis
   answer (`eval.cljs:448-459`) — kernel + third-party-compiled.
3. ns has `:seon.ns` rows in the DB → return reconstituted source
   `{:lang :clj :source …}` (NEW branch).
4. genuinely absent → rethrow the legible `Could not require X` error
   (`eval.cljs:454-459` unchanged).

One nuance to carry: `guarded-load` runs `schema/relink-registry!`
after every load (`eval.cljs:452`) to undo malli registry stomps; the DB
branch must preserve that post-load hook.

---

## Q4 — Override-by-upsert + load-latest

Under Option B, **a fn override IS just an upserted `:seon.fn` row**,
last-write-wins on the `:seon.fn/sym` identity attr. The DB `*load-fn*`
reconstitutes the owning ns from the CURRENT `:seon.fn/source` per sym
(reads against `@conn`), so the latest source is what the loader emits —
no `set!`, no replay-skip, no provenance tier. This is exactly the
direction locked in the simplification audit
(`simplification-audit-2026-06-17.md:266-289`): "An OVERRIDE is an
UPSERT … the row IS the source of truth, replay loads the latest."

**Does cljs.js re-eval cleanly redefine a var that compiled callers pick
up?** Yes, given the cross-cutting INVARIANT the audit already pins
(`simplification-audit-2026-06-17.md:233-236`): the pod MUST stay
dev-compiled (`goog.DEBUG` true, `*cljs-static-fns*` false). Under
dev-build late binding, a compiled call site `(foo)` emits a global var
DEREF (`seon.x.foo.call(...)` reads the live `seon.x.foo`), so re-`def`ing
`foo` via `eval-str` rebinds the global and existing callers pick up the
new fn. `analyze-str*` honors `:static-fns` from opts
(`js.cljs:684`) — seon must keep it false (it does; the bootstrap is
dev-compiled). **Assert `goog.DEBUG` before honoring an override**, per
the audit.

**Ordering hazard when the override's ns is a dependency of others?**
Under B there is none that the loader doesn't already handle: cljs.js
loads a dep ns fully (analyze + emit all its defs) before the dependent
ns's deps resolve (`load-deps` awaits each `require` before the next,
`js.cljs:410-415`), so the overridden fn is defined before any dependent
ns is loaded — same guarantee a compiled dep gets. The ONE genuine
caveat (storage-independent, a CLJS late-binding fact): re-export
ALIASES `(def reply! message/reply!)` capture the value at def-time and
do NOT track an override of the defining var
(`simplification-audit-2026-06-17.md:590-595`). That is an alias audit,
not an override-mechanism concern.

**Revert** = `:db/retractEntity` the override `:seon.fn` row; the
reconstitution query then reads the original (kernel/compiled or
prior-version) source, and on next load the compiled/original fn stands
(audit B7 correction, `simplification-audit-2026-06-17.md:177-185`).

---

## Q5 — The irreducible kernel

A ns MUST be compiled (cannot be DB-loaded) iff the DB-load path itself
depends on it — otherwise loading it from the DB is circular. The kernel
is the transitive closure of "what `*load-fn*`-from-DB calls":

1. **The cljs self-host compiler** — `cljs.js`, `cljs.analyzer`,
   `cljs.compiler`, `cljs.core` (the bootstrap `dump-core`,
   `js.cljs:137`). Without these there is no `eval-str` to load anything.
2. **`seon.eval`** — owns `eval`/`raw-eval`/`guarded-load` (the
   `*load-fn*` itself) — `eval.cljs:412-516`. The loader cannot load its
   own loader.
3. **`seon.repl` / `seon.repl.internal`** — `ensure-bootstrap!`,
   `!compile-state`, the bootstrap analyzer caches the load path reads.
4. **`seon.schema`** — `register!`/`relink-registry!` run inside the
   load path (`eval.cljs:452`) and `db/transact!` validates against it;
   schema must exist before any DB row is read or written.
5. **`seon.db`** — the DB read/conn the reconstitution `*load-fn*`
   queries; circular to load it from itself.
6. **The replay driver in `seon.client`** that kicks off the top-level
   `require`/`eval-str` per ns (shrinks to a small loop under Option B —
   "for each top-level ns, `require` it"; the dependency walk is the
   compiler's).
7. **`seon.analyzer_info`** — produces the projections written at index
   time; needed to BUILD rows, on the write side of the kernel.

Everything ABOVE this kernel — the rest of core, third-party-merged
nses, and all agent code — is DB-loaded UNIFORMLY via the DB `*load-fn*`.
The kernel is small (compiler + eval + db + schema + repl + the
replay-kick) and is precisely the set the audit named
(`simplification-audit-2026-06-17.md:287-289`: "the eval / db / replay /
schema / cljs-compiler KERNEL must be compiled").

**Kernel-target override caveat**: overriding a kernel fn
(`seon.eval/eval`, a `seon.db` fn) via a DB row can no-op or self-corrupt
on the next boot (the compiled kernel loads first and the DB override
would have to re-shadow it AFTER the load path already ran). Keep the
loud-warning guard the audit specifies
(`simplification-audit-2026-06-17.md:457-459`) — a warning, not a new
attribute.

---

## Recommended design (concrete)

1. **Index** (write side, at tee + core-index): per ns store
   `:seon.ns/name` + `:seon.ns/requires` (`[:vector :keyword]`, from the
   analyzer's `:requires`, DB-loadable nses only); per fn/schema/test
   store the byte-faithful single defining form as today. No per-fn edges.
2. **Reconstitution**: a `reconstitute-ns-source(conn, ns-kw)` that
   returns `(ns …)` head (from `:seon.ns/source` or rebuilt from
   requires) + every CURRENT `:seon.fn`/`:seon.schema`/`:seon.test`
   source for that ns, concatenated into one string.
3. **DB `*load-fn*`**: extend `guarded-load` (`eval.cljs:412`) with a
   branch — if `(:name rc)` has `:seon.ns` rows, `cb {:lang :clj :source
   (reconstitute-ns-source conn name)}`; else fall through to the
   existing bundle/host/rethrow logic. Preserve `relink-registry!`.
4. **Replay-kick**: replace `replay-program-graph!` with a small loop
   that, for each top-level DB ns (or just the agent entry ns), calls
   `cljs/require`/`eval-str` with the DB `*load-fn*` bound. cljs.js
   sequences the rest via `load-deps` + `*loaded*` + cycle detection.
5. **Override**: upsert `:seon.fn/sym`; assert `goog.DEBUG`; warn on
   kernel targets; revert via `:db/retractEntity`. DELETE replay-skip
   (`client.cljs:665,680`), the tx-order `sort-by` (`client.cljs:693`),
   the 2-pass retry (`client.cljs:818-840`), and `ensure-target-ns!`
   (`client.cljs:722-752`).
6. **Kernel** stays compiled (Q5 list); assert at boot that the kernel
   nses are present in the bundle before the DB-load path runs.

## Risks / unresolved

- **`:seon.ns/requires` precision.** The analyzer's `:requires` includes
  aliases→ns maps; we want the ns VALUES, filtered to DB-loadable nses
  (exclude bundle/host nses, or the DB `*load-fn*` would be asked for
  `cljs.core`). Need a live check of the analyzer entry shape on the pod
  before committing the attr (RESEARCH-ONLY here — not run). Low risk:
  the data is already in the compile-state.
- **Reconstituted-source eval semantics vs. per-form replay.** Bulk
  per-ns eval changes warning/error granularity: a single bad fn fails
  the whole ns load under B, where per-def replay isolated it. Mitigation:
  the DB `*load-fn*` can fall back to per-fn eval-and-collect within an
  ns on error, but that reintroduces complexity — measure first. The
  concept doc claims forward-refs-within-a-ns "just work" in one pass
  (`code-as-data-runtime.md:78-82`); verify on a multi-fn agent ns.
- **`:lang :clj` vs `:js` in the load-fn return.** `*load-fn*` returning
  `:lang :clj` routes through `eval-str*` + side-effects
  (`js.cljs:304-313`), which is what we want (it re-analyzes + emits).
  Confirm the DB branch returns `:clj` (source to compile), never `:js`
  (the host-fallback empty-`:js` trick at `eval.cljs:458` is only for
  already-loaded host nses).
- **Macro nses.** `*load-fn*` is called with `:macros true` for macro
  loads (`js.cljs:296`); agent/core macro nses (`*.clj`/`.cljc` macro
  side) are NOT in the DB corpus and must rethrow to the
  bundle/host path. The existing `(:macros rc)` guard
  (`eval.cljs:455`) already does this — keep it.
- **Third-party-compiled merge interaction.** B2 (merge non-functional,
  audit) is orthogonal: third-party nses that are COMPILED into the
  bundle are answered by `boot/load` (kernel-adjacent), not the DB
  branch. Only third-party code stored AS DB ROWS would DB-load. Decide
  per package whether it is compiled-merged or DB-indexed; the audit's
  direction is "core, third-party, and agent code UNIFORMLY" DB-loaded,
  which implies indexing third-party source into rows — a larger change
  than this doc scopes.

## Cross-references

- `tmp/cljs-src-1.12.145/cljs/js.cljs` — `*load-fn*` contract (74-99),
  `require`/`*loaded*` (270-353), `load-deps`/cycle-detect (384-430),
  `ns-side-effects`→`load-deps` (628-639), `analyze-str*` static-fns
  honoring (684).
- `reference-code/nrepl/src/clojure/nrepl/middleware/load_file.clj` —
  load-file = whole-file eval, no dep logic (11-17, 60-63).
- `reference-code/cider-nrepl/src/cider/nrepl/middleware/refresh.clj` —
  delegates to clojure.tools.namespace (11-14, 85-96).
- `reference-code/orchard/src/orchard/namespace.clj` — alphabetical
  sort, no topo (85-94, 115-123).
- `src/seon/eval.cljs` — `:load` binding (493), `guarded-load`
  (412-459), `raw-eval` (461-516).
- `src/seon/client.cljs` — `query-program-graph-entries`/replay-skip
  (628-694), `ensure-target-ns!` heal hack (722-752), `replay-one!`
  (754-778), `replay-program-graph!` 2-pass retry (789-840),
  `:seon.ns/source` write (1162).
- `src/seon/ctx.cljs` — `:seon.ns/name`/`:seon.ns/source` schema
  (74-75).
- `src/seon/analyzer_info.cljs` — `var-projection` (180-198).
- `docs/seon/concepts/code-as-data-runtime.md` — bulk-load resume
  prescription (28, 38-40, 68-82, 130-133).
- `docs/prds/agent-runtime/research/simplification-audit-2026-06-17.md`
  — locked direction (266-289), dev-build invariant (233-236), override
  caveats (457-459, 590-595).

---
type: research
status: active
tags: [research, cljs, agent]
---

# CLJS self-host runtime introspection: what's recoverable for a var (2026-06-08)

> GOAL: determine exactly what RUNTIME introspection the ClojureScript
> self-host stack (cljs.js + cljs.analyzer) exposes for a var/namespace —
> real arglists, doc, `:malli/schema`, and especially SOURCE (the original
> def form as text) — so the compiled substrate's functions can be indexed
> ACCURATELY at runtime instead of with handcrafted stubs (`core-fn-curated`
> + `synthesize-fn-source`). Every claim below is REPL-verified against the
> live pod (shadow build `:client`, runtime port 7889) and anchored in
> ClojureScript 1.12.145 source.

## TL;DR

1. **The analyzer namespace cache does NOT store source.** Per-def, cljs.analyzer
   stores `:name`, `:arglists`, `:doc`, `:meta` (with `:malli/schema`,
   `:private`, …), `:fn-var`, `:method-params`, `:variadic?`, and SOURCE-INFO
   (`:file`/`:line`/`:column`/`:end-line`/`:end-column`). There is **no `:form`,
   no `:source`, no `:ast` body**. The init-expr AST is analyzed for summary
   fields only, then discarded. Verified live AND in `cljs.analyzer/analyze-def`
   (1.12.145 `analyzer.cljc:2122-2175`).
2. **Source is recoverable a different way: read the source FILE at runtime.**
   The pod is Node — `(js/require "fs")` works, cwd is the project root, and
   `src/seon/*.cljs` / `*.cljc` are present and readable. Runtime var meta
   carries `:file` + `:line` for EVERY substrate fn (survives instrumentation),
   so you can read the file and extract the exact form. This is exactly what
   ClojureScript's own `cljs.repl/source-fn` does (`repl.cljc:1508`): it reads
   from `:file`+`:line`, NOT from the analyzer cache — because the cache has no
   source. The cache-has-no-source fact is by design, and file-read is the
   canonical CLJS answer.
3. **Reliable runtime introspection for a substrate var:** `:malli/schema`
   (exact form, `m/form`-serializable) and `:doc` are 100% reliable from
   `(meta #'ns/fn)`. `:arglists` from var meta is UNRELIABLE — instrumented fns
   are mangled to `([arg])`. `:file`+`:line` ARE reliable even for instrumented
   fns. So the accurate path for arglists is "read the source form from the
   file" (which contains the real arglists), not var meta.
4. **`(resolve sym)` on a runtime symbol does NOT work** in self-host (it's a
   compile-time macro). Substrate indexing must use `#'`-literal var refs (a
   compile-time-known list) — exactly what the PRD's `substrate-vars` proposes.
5. **Recommendation (concrete):** index substrate fns from a `#'`-literal
   var-list, projecting **spec + doc from runtime var meta** and **source +
   real arglists from a file-read + paren-balance extraction keyed on
   `:file`/`:line`**. This single hybrid gives accurate spec, doc, arglists, AND
   real source for every substrate fn — including instrumented ones — making
   the PRD's per-var `:arglists` override hack UNNECESSARY. No `,,,` stub, no
   analyzer-cache expansion.

---

## 1. Where the pod holds the analyzer / compile state

The bootstrap cljs.js compile-state is boxed in `seon.repl/!compile-state`
(`repl.cljs:76`, `(defonce !compile-state (atom nil))`) — an **atom-of-atom**.
The outer atom is reset once at boot by `ensure-bootstrap!`
(`repl.cljs:87`) with the state built by `seon.eval/init-bootstrap!`
(`eval.cljs:234`, from `(cljs/empty-state)` + loaded analysis caches). Callers
deref the outer atom once and pass the inner atom (`@seon.repl/!compile-state`)
to the analyzer-reading helpers in `seon.analyzer-info`.

`seon.eval/raw-eval` (`eval.cljs:387`) calls `cljs.js/eval-str` against this
inner atom; eval-str MUTATES `[:cljs.analyzer/namespaces …]` as a side effect.
That is the agent eval path — and the reason agent-authored defs land in the
analyzer cache.

**Live shape of the inner atom:**

```clojure
(sort (keys @@seon.repl/!compile-state))
;; => (:options
;;     :cljs.analyzer/constant-table
;;     :cljs.analyzer/data-readers
;;     :cljs.analyzer/externs
;;     :cljs.analyzer/namespaces)
```

`:cljs.analyzer/namespaces` is `{ns-sym → ns-info}`. Live count = **55 nses**;
the only seon ones are `seon.schema`, `seon.dynamic`, and the agent's own home
ns (`seon.agent.cUk-2606081347` at probe time). **`seon.db` and `seon.agent`
are absent** — they're compiled into the `:client` bundle (their functions run)
but their analyzer state is not in the bootstrap cache, because they're not
`:bootstrap :entries`. This is the structural reason substrate fns can't be
indexed from the analyzer cache and need the file-read path.

`ns-info` keys (live, for `seon.schema`):

```
(:defs :deps :excludes :flags :imports :js-deps :meta :name :ns-aliases
 :reader-aliases :rename-macros :renames :require-macros :requires :seen
 :use-macros :uses :cljs.analyzer/constants :shadow/js-access-global)
```

`seon.analyzer-info/ns-deps` already reads `:requires`/`:uses`/`:require-macros`
for resume topo-sort; `snapshot-defs`/`defs-since` read `:defs`.

---

## 2. Per-var analyzer entry — exact keys, and SOURCE is absent

### 2.1 A bootstrap-cached def (`seon.schema/register!`)

```clojure
(let [vm (get-in @@seon.repl/!compile-state
                 [:cljs.analyzer/namespaces 'seon.schema :defs 'register!])]
  (sort (keys vm)))
;; => (:arglists :arglists-meta :column :doc :end-column :end-line :file
;;     :fn-var :line :max-fixed-arity :meta :method-params :name
;;     :protocol-impl :protocol-inline :variadic?)

;; :arglists      => (quote ([k v]))        ; real, quote-wrapped (A6)
;; (:doc (:meta vm)) => "Register a single schema..."   ; full docstring
;; :fn-var        => true
;; :name          => seon.schema/register!
;; :file          => "seon/schema.cljc"
;; :line/:end-line => 119 / 119
```

**Union of ALL def keys across `seon.schema`'s 17 defs** (so nothing is missed):

```
(:arglists :arglists-meta :column :doc :end-column :end-line :file :fixed-arity
 :fn-var :line :max-fixed-arity :meta :method-params :methods :name :private
 :protocol-impl :protocol-inline :ret-tag :tag :top-fn :variadic?)
```

Filtering this union for any key matching `source|form|ast|body|text|sexpr`
returns **`()`** — empty. **There is no source-bearing key.** The original def
form text is NOT in the analyzer entry.

### 2.2 An agent-authored def (via the real eval path)

Driving `seon.eval/eval` (→ `cljs.js/eval-str`) on
`(ns probe.tee3) (defn ^{:malli/schema [:=> [:cat :int] :int]} hf "a doc" [x] (* x 2))`
then reading `defs-since`:

```clojure
{:eval-ok true
 :new-def-count 1
 :new-defs [{:ns probe.tee3 :sym hf
             :vm-keys (:arglists :arglists-meta :column :doc :end-column
                       :end-line :file :fn-var :line :max-fixed-arity :meta
                       :method-params :name :protocol-impl :protocol-inline
                       :ret-tag :variadic? :malli/schema)
             :arglists (quote ([x]))
             :doc "a doc"
             :malli [:=> [:cat :int] :int]
             :source-keys ()}]}     ;; <-- again: NO source key
```

So agent-authored defs DO populate the bootstrap analyzer cache with a full
var-map (arglists, doc, `:malli/schema`, line/file) — but STILL no source.

> Caveat for future probers: a def evaluated directly in the **shadow-cljs MCP
> REPL** (not via `seon.eval/eval`) goes through shadow's compiler, NOT the
> bootstrap compile-state, so it does NOT appear in `!compile-state` at all
> (`probe.intro` had an empty `{}` ns-info). Only `cljs.js/eval-str` against
> `@!compile-state` populates the bootstrap analyzer cache. Test agent-path
> behavior by calling `seon.eval/eval` explicitly.

### 2.3 ClojureScript source anchor (authoritative)

`cljs.analyzer/analyze-def` (1.12.145, `analyzer.cljc:2122-2175`) merges into
`[::namespaces ns-name :defs sym]`:

- `{:name var-name}`
- `sym-meta` (with `:test` reduced to `true` for EDN-cacheability)
- `{:meta (-> sym-meta (dissoc :test) …)}`
- `{:doc doc}`
- `(source-info var-name env)` → `:file`/`:line`/`:column`/`:end-line`/`:end-column`
- for fn-vars: `:fn-var`, `:variadic?`, `:max-fixed-arity`, `:method-params`,
  `:arglists`, `:arglists-meta`

The body `init-expr` is `(analyze …)`d only to harvest `:variadic?` /
`:max-fixed-arity` / `:method-params`; the resulting AST is **not stored**.
There is no code path that stashes the original form or its text. This confirms
the live finding at the source level: **source is irrecoverable from the
analyzer namespace cache, by design**, for both bootstrap-cached and
agent-authored defs.

---

## 3. Runtime var-meta reliability (the `(meta #'ns/fn)` channel)

For COMPILED substrate fns referenced by `#'`-literal (compile-time resolve):

| Field | `(meta #'ns/fn)` | Reliable? |
| --- | --- | --- |
| `:malli/schema` | exact form, `m/form`-serializable | **YES** |
| `:doc` | full docstring | **YES** |
| `:file` | e.g. `"seon/db.cljs"` | **YES** (even instrumented) |
| `:line` | start line of the `(defn …)` | **YES** (even instrumented) |
| `:arglists` | mangled to `([arg])` when instrumented | **NO** for instrumented |
| `:source` | n/a | NO — compiled away |

**Live evidence (`#'`-literal var refs):**

```clojure
{:transact!  {:file "seon/db.cljs"   :line 878  :arglists ([arg])  :specced? true}
 :query      {:file "seon/db.cljs"   :line 1234 :arglists ([{:seon.db/keys [query args db conn] :or {conn *conn* args []}}]) :specced? true}
 :pull       {:file "seon/db.cljs"   :line 1248 :arglists ([{:seon.db/keys [...]}]) :specced? true}
 :entity     {:file "seon/db.cljs"   :line 1256 :arglists ([{:seon.db/keys [...]}]) :specced? true}
 :current-agent-id {:file "seon/db.cljs" :line 520 :arglists ([]) :specced? false}
 :new-id!    {:file "seon/db.cljs"   :line 387  :arglists ([])  :specced? false}
 :register!  {:file "seon/schema.cljc" :line 234 :arglists ([k v]) :specced? false}}
```

Key observations:

- `transact!` is instrumented → `:arglists ([arg])` (mangled). `query`/`pull`/
  `entity` are NOT instrumented → real map-destructuring arglists intact. This
  matches the PRD's open claim exactly.
- **`:file`/`:line` survive instrumentation for `transact!`** (`878`) just as for
  un-instrumented fns. Instrumentation rewraps the fn but preserves the var's
  source-location meta. So `:file`/`:line` is a uniform, reliable anchor for
  EVERY substrate fn regardless of instrumentation.
- `register!`'s runtime `:line` is **234** (the `.cljc`), whereas the bootstrap
  analyzer cache reported **119/end-line 119** for the same fn. The two line
  numberings differ (cache was emitted from a different build pass). **Runtime
  var meta is the authoritative `:file`/`:line` for the file-read path** — do
  not key file extraction off the analyzer-cache line.

### `:malli/schema` → `:seon.fn/spec` round-trip (confirmed)

```clojure
(pr-str (m/form (m/schema [:=> [:cat :seon.db/transact-request]
                                :seon.db/transact-response])))
;; => "[:=> [:cat :seon.db/transact-request] :seon.db/transact-response]"
```

Stable, idempotent string. This is the basis for `:seon.fn/spec` (string) that
replaces `:seon.fn/specced?` (boolean) per the PRD.

---

## 4. Source recovery via Node fs + file read (the viable path)

The pod is Node, so the filesystem IS a runtime channel:

```clojure
(let [fs (js/require "fs") path (js/require "path") cwd (.cwd js/process)]
  {:cwd cwd                                       ;; "/Users/sean/src/seon"
   :fs-available? true
   :db.cljs-exists? true                          ;; src/seon/db.cljs (59771 bytes)
   :schema.cljc-exists? true})                    ;; src/seon/schema.cljc
```

`:file` in var meta is a project-relative path (`"seon/db.cljs"`); resolve it
against `(.cwd js/process)` + `"src"` (or check both `src/<file>` candidates).
`:line` points EXACTLY at the `(defn …)` line:

```clojure
;; (:line (meta #'seon.db/query)) => 1234
;; line 1234 of src/seon/db.cljs is literally "(defn query"
```

### Two extraction strategies tested

**(a) Reader-parse — has a `::`-keyword wrinkle.** `cljs.tools.reader` IS
bootstrapped (`cljs.tools.reader.reader-types/indexing-push-back-reader`
resolves), but `cljs.tools.reader/read` chokes on auto-resolved keywords:
reading `src/seon/db.cljs` from the top dies at line 225 with
`"Invalid token: ::tx-data"` — the reader has no `*alias-map*` to resolve `::`.
ClojureScript's own `source-fn` works around this by binding
`reader/*alias-map* identity` (`repl.cljc:1527`), but that machinery isn't
wired in the bootstrap reader by default.

**(b) Paren-balance from `:line` — reader-free, robust (RECOMMENDED).** Walk
chars from the `:line` start, track paren depth (skipping string contents +
escapes), stop when depth returns to zero. No reader, so `::keywords`, `#js`,
reader-conditionals, and namespaced maps all pass through verbatim:

```clojure
;; extract-form-at-line(txt, (:line (meta #'seon.db/query)))
;; => 442 chars, head "(defn query\n  \"Run a Datalog query...",
;;    tail "...(apply d/q query db args)))", balanced? true   ✓

;; For the INSTRUMENTED, arglists-mangled transact!:
;; extract-form-at-line(txt, (:line (meta #'seon.db/transact!)))
;; => 3748 chars, head "(defn ^:async transact!\n  \"Commit tx-data...",
;;    balanced? true   ✓  -- the REAL arglists are inside this text
```

This recovers BOTH the real source AND (because the real arglists are in the
source text) the real arglists for every substrate fn — including `transact!`,
whose runtime `:arglists` meta is useless. The paren-walk is ~30 lines and has
no external dependency.

> The paren-walker must treat strings (docstrings contain unbalanced parens like
> ``"... `::db/db` ..."``) — the tested impl tracks an `in-str?`/`esc?` state.
> It does NOT need to handle `;` line comments specially for the substrate fns
> tested (none had a `)` inside a trailing comment at depth-1), but a hardened
> version should also skip `;`-to-EOL and `\(` char literals. For the substrate
> var-list this is a fixed, small set of forms — verify each extraction's
> `balanced?` once and you're done.

### `cljs.repl/source-fn` is the design precedent

`cljs.repl/source-fn` (`repl.cljc:1508-1531`) — ClojureScript's canonical
"get the source of a var" — resolves the var, takes `(:file v)`, opens the
FILE, skips `(dec (:line v))` lines, binds `reader/*alias-map* identity`, reads
ONE form, returns `(-> form meta :source)`. It reads from the file, NOT the
analyzer cache, precisely because the cache has no source. (It's JVM-flavored —
`io/file`, `ana-api/resolve` — so not directly callable in self-host, but it
validates the architecture: **`:file`+`:line` → file read IS how you get source
text in ClojureScript.**)

---

## 5. Recommendation — the accurate, non-hacky substrate-fn indexer

Replace `core-fn-curated` + `synthesize-fn-source` + `seed-core-fns!` with one
`index-substrate!` that, for a **compile-time `#'`-literal var-list**, builds a
`:seon.fn` row IDENTICAL in shape to a detect-and-tee row by combining TWO
runtime channels — no third representation, no hand-typed doc/arglists table,
no `,,,` stub:

| `:seon.fn/*` field | Source | Why |
| --- | --- | --- |
| `:seon.fn/sym` | `(str (:ns m) "/" (:name m))` from `(meta v)` | reliable |
| `:seon.fn/spec` | `(m/form (m/schema (:malli/schema m)))` when present | reliable, exact contract; ABSENT = unspecced |
| `:seon.fn/doc` | `(:doc m)` | reliable |
| `:seon.fn/source` | **file read + paren-balance from `(:file m)`/`(:line m)`** | REAL source, handles instrumented fns |
| `:seon.fn/arglists` | parse the extracted source's arglists (or `pr-str` the analyzer arglists for bootstrap-cached nses) | REAL even when var-meta arglists are mangled |
| `:seon.fn/fn-var?` / `:private?` | `(meta v)` | reliable |

Key consequences vs. the PRD's §2.2 hybrid:

1. **The per-var `:arglists` override hack is unnecessary.** The PRD proposed a
   hand-declared `{:arglists "..."}` per var where instrumentation mangles
   meta. The file-read path recovers real arglists (they're in the source text)
   AND `:file`/`:line` survive instrumentation, so there's nothing to override.
   This is strictly less hand-maintenance than the PRD's plan.
2. **Substrate fns get REAL `:seon.fn/source`, not absent and not a stub.** The
   file is right there at runtime. The PRD assumed source was unavailable and
   chose "absent" (honest) over "`,,,` stub" (a lie). File-read makes "real
   source" available — strictly better, and it makes the corpus's substrate-fn
   rows genuinely useful (the agent can read the implementation).
3. **No bootstrap-cache expansion needed.** Adding `seon.db`/`seon.agent` to
   `:bootstrap :entries` (PRD step 6, deferred) is unnecessary for source OR
   arglists — file-read covers both without pulling datahike-cljs's analyzer
   subtree into `out/bootstrap`. The analyzer-cache path remains the right
   mechanism only for nses already cached (it's free there) and for
   detect-and-tee of agent fns (where source comes from the submitted eval
   string, not the cache or the file).
4. **Resume still treats substrate fns as no-replay.** `:seon.fn/source` for a
   substrate fn is now real (descriptive) text, but it must NOT be re-evaled on
   resume (it would shadow the compiled fn). Mark substrate rows
   (`:seon.db/origin :substrate-seed`, already on tx-meta) and skip them in
   `query-program-graph-entries`/`replay-one!`, per PRD §6. Real source is for
   reading/rendering, not replay.

### Two source channels, one shape (summary)

- **Agent-authored fns** (detect-and-tee, `build-tee-entities`): source =
  the SUBMITTED eval string (`eval.cljs:756`); arglists/doc/spec from the
  analyzer var-map (`var-projection`). Replayable.
- **Compiled substrate fns** (`index-substrate!`): source + arglists from a
  FILE read keyed on var-meta `:file`/`:line`; doc/spec from var meta.
  Descriptive, not replayed.

Both feed the SAME `:seon.fn` shape; downstream readers don't branch on origin.

---

## Appendix — reusable paren-balance extractor (tested live)

```clojure
(defn extract-form-at-line
  "Return the exact text of the top-level form beginning at `line-1based`
   in `txt`, by paren-balancing (reader-free; passes ::kw / #js / reader-
   conditionals through verbatim). Tracks string + escape state so
   docstring parens don't unbalance."
  [txt line-1based]
  (let [lines (vec (clojure.string/split-lines txt))
        start (clojure.string/join "\n" (subvec lines (dec line-1based)))]
    (loop [i 0 depth 0 in-str? false esc? false started? false]
      (if (>= i (count start))
        start
        (let [c (nth start i)]
          (cond
            esc?                   (recur (inc i) depth in-str? false started?)
            (and in-str? (= c \\)) (recur (inc i) depth in-str? true  started?)
            in-str?                (recur (inc i) depth (not (= c \")) false started?)
            (= c \")               (recur (inc i) depth true false started?)
            (= c \()               (recur (inc i) (inc depth) in-str? false true)
            (= c \))               (let [d (dec depth)]
                                     (if (and started? (zero? d))
                                       (subs start 0 (inc i))
                                       (recur (inc i) d in-str? false started?)))
            :else                  (recur (inc i) depth in-str? false started?)))))))

;; Usage for a substrate var:
(let [fs   (js/require "fs")
      m    (meta #'seon.db/transact!)
      ;; :file is "seon/db.cljs"; resolve under <cwd>/src
      file (str (.cwd js/process) "/src/" (:file m))
      txt  (.readFileSync fs file "utf8")]
  (extract-form-at-line txt (:line m)))   ;; => the real (defn ^:async transact! …) text
```

(Production hardening: also skip `;`-to-EOL comments and `\(` / `\)` char
literals; verify `balanced?` per extraction over the fixed substrate var-list.)

## Source anchors

- `cljs.analyzer/analyze-def` — per-def storage, NO source/form/ast:
  ClojureScript 1.12.145 `cljs/analyzer.cljc:2122-2175` (extracted to
  `/tmp/cljs-1.12.145/` for this pass; CLJS is not vendored in `reference-code/`).
- `cljs.repl/source-fn` — file+line is the source-of-truth for source text:
  `cljs/repl.cljc:1508-1531`.
- Pod plumbing: `seon.repl/!compile-state` (`src/seon/repl.cljs:76,87`),
  `seon.eval/init-bootstrap!`/`raw-eval`/`eval`/`build-tee-entities`
  (`src/seon/eval.cljs:234,387,444,715`),
  `seon.analyzer-info/snapshot-defs`/`defs-since`/`var-projection`
  (`src/seon/analyzer_info.cljs:91,106,152`).
- PRD this informs: `docs/prds/agent-runtime/coherent-bootstrap-indexing-2026-06-08.md`.

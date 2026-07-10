---
type: research
status: active
tags: [research, agent]
---

# Clojure completion stack for the bb cursor-intelligence oracle

Companion to [[typeahead-hole-filling-2026-07-10]] (the consumer: given
`(draft-code-string, cursor-position)` return `{slot-kind, in-scope locals,
ranked candidates, arg template}`, oracle-side in `bin/oracle-server`).

**TL;DR — the whole stack runs in babashka TODAY, measured on this machine
(bb v1.12.212, clj-kondo v2025.04.07):**

1. **Repair** the broken draft with an **edamame-guided delimiter-append
   loop** (edamame is a bb built-in; its ex-data carries
   `:edamame/expected-delimiter` + `:edamame/opened-delimiter-loc` — the
   exact repair instruction). Do NOT reach for parinfer: the only
   bb-embeddable parinfer is a subprocess binary, and indent-mode trusts
   indentation the model doesn't guarantee. **Proven: 6 missing delimiters
   repaired, whole loop sub-ms.**
2. **Slot-kind + locals** via a **~200-line port of Compliment's context
   mechanism** (`__prefix__` sentinel at the cursor → `parse-context` →
   `bindings-from-context`). `compliment.context` **loads and runs in bb
   unmodified** (verified); the local-bindings source ports by stubbing
   the JVM-only tag inference (verified, port included below). This gives
   `:idx` (arg position), `:map-role :key/:value` (map slot), and the full
   lexical environment — the arg-template selector.
3. **Project-wide candidates** via the **clj-kondo pod** (resident, loaded
   once): `:analysis {:locals true :keywords true :arglists true}` gives
   `:locals` with scope ranges, `:var-definitions` with `arglist-strs`,
   keyword usages. **Measured: 2.0 ms/call resident (pod), 2.3 ms for the
   full repair→spit→analyze round; one-shot CLI ~10 ms.** Caveat proven by
   test: clj-kondo returns **zero locals on an unbalanced buffer** — the
   repair step is mandatory, which is why step 1 leads.
4. **Ranking** cloned from clojure-lsp's `priority-order` (locals ≻
   keywords ≻ core, scope-filtered via clj-kondo's `:scope-end-row/col`) —
   ~40 lines, not a dependency. The final ranking forward is the diffusion
   model itself (mode=rank, per the typeahead doc); this stack supplies
   the *legal candidate set*.

Everything below preserves the raw source excerpts.

---

## Q1 — Compliment's context system

Source fetched 2026-07-10 from `alexander-yakushev/compliment` master
(released line 0.7.x; the mechanism is unchanged since ~0.3).

### How it works

The **client** (CIDER's elisp, or us) takes the enclosing top-level form
and substitutes the symbol at the cursor with the sentinel `__prefix__`,
then sends that string as `context`. Compliment never sees a cursor
offset — the sentinel IS the cursor. Server-side, `compliment.context`:

1. **reads** the possibly-unfinished string (`safe-read-context-string`):
   first a `read-string` with `{`→`(compliment-hashmap …)` rewriting (so
   odd-entry / duplicate-key unfinished maps still read), then a
   fallback `dumb-read-form` that appends missing closers;
2. **macroexpands** only `->`/`->>`/`some->`/`some->>`/`doto` (so
   threading doesn't hide the real call);
3. **parses** into a list of levels, innermost-first, each
   `{:idx … :form … [:map-role :key|:value]}`.

`compliment/context.clj` (verbatim, the load-bearing parts):

```clojure
(def prefix-placeholder
  "Special symbol which substitutes prefix in the context, so the former can be
  found unambiguously."
  '__prefix__)

(defn- dumb-read-form
  "Take a presumably unfinished Clojure form and try to \"complete\" it so that it
  can be read. The algorithm is incredibly stupid, but is better than nothing."
  [unfinished-form-str]
  (let [open->close {\( \), \[ \], \{ \}},
        close->open {\) \(, \] \[, \} \{}]
    (loop [[c & r] (reverse (filter (set "([{}])") unfinished-form-str))
           to-append []]
      (if c
        (cond (open->close c)
              (recur r (conj to-append (open->close c)))
              (close->open c)
              (if (= c (open->close (first r)))
                (recur (rest r) to-append)
                nil))     ;; Everything is bad - just give up
        (try-read-replacing-maps (apply str unfinished-form-str to-append))))))

(defn parse-context
  "…The result is a list of maps, each map represents a level of the context from
  inside to outside. Map has `:idx` and `:form` values, and `:map-role` if the
  level is a map. `:idx` defines the position of prefix (or the form containing
  prefix) on the current level (number for lists and vectors, key or value for
  maps).

  Example: `(dotimes [i 10] ({:foo {:baz __prefix__}, :bar 42} :quux))`
  Transformed it looks like:
  `({:idx :baz, :map-role :value, :form {:baz __prefix__}}
    {:idx :foo, :map-role :key, :form {:foo {:baz __prefix__}, :bar 42}}
    {:idx 0, :form ({:foo {:baz __prefix__}, :bar 42} :quux)}
    {:idx 2, :form (dotimes [i 10] ({:foo {:baz __prefix__}, :bar 42} :quux))})`."
  [context]
  (letfn [(parse [ctx]
            (cond
              (sequential? ctx)
              (when-let [[idx rest] (first (keep-indexed (fn [idx el]
                                                           (when-let [p (parse el)]
                                                             [idx p]))
                                                         ctx))]
                (cons {:idx idx :form ctx} rest))
              (map? ctx)
              (when-let [[idx role rest] (first (keep (fn [[k v]]
                                                        (if-let [p (parse v)]
                                                          [k :value p]
                                                          (when-let [p (parse k)]
                                                            [v :key p])))
                                                      ctx))]
                (cons {:idx idx :map-role role :form ctx} rest))
              (string? ctx)
              (let [idx (.indexOf ^String ctx (name prefix-placeholder))]
                (when (>= idx 0) [{:idx idx :form ctx}]))
              (= ctx prefix-placeholder) ()))]
    (some-> (parse context) reverse)))

(defn cache-context
  "Parses the context, or returns one from cache if it was unchanged."
  [context-string]
  (let [[prev-ctx-string prev-ctx] @context-cache]
    (if (= context-string prev-ctx-string)
      prev-ctx
      (let [context (-> (safe-read-context-string context-string)
                        macroexpand-form
                        parse-context)]
        (reset! context-cache [context-string context])
        context))))
```

### The local-bindings source

`compliment/sources/local_bindings.clj` walks the parsed context levels
outward and extracts every binding form (verbatim core):

```clojure
(defn extract-local-bindings
  "When given a form that has a binding vector traverses that binding vector and
  returns the list of all local bindings."
  [form ns]
  (when (seq? form)
    (let [sym (first form)
          locals-meta (when (symbol? sym)
                        (:completion/locals (meta (ns-resolve ns sym))))]
      (cond (or (let-like-form? sym) (= locals-meta :let))
            (mapcat (fn [[x y]] (parse-binding ns x y))
                    (partition 2 (second form)))
            (or (defn-like-form? sym) (= locals-meta :defn))
            (parse-fn-body ns (rest form))
            (or (letfn-like-form? sym) (= locals-meta :letfn))
            (mapcat (fn [fn-body] (parse-fn-body ns fn-body)) (second form))
            (or (doseq-like-form? sym) (= locals-meta :doseq))
            (->> (partition 2 (second form))
                 (mapcat (fn [[left right]]
                           (if (= left :let) (take-nth 2 right) [left])))
                 (mapcat (fn [x] (parse-binding ns x nil))))
            (= sym 'as->) [(nth form 2)]))))

(defn bindings-from-context
  "Returns all local bindings that are established inside the given context."
  [ctx ns]
  (try (->> (mapcat #(extract-local-bindings (:form %) ns) ctx)
            (filter symbol?)
            distinct-preserve-tags)
       (catch Exception _)))
```

`parse-binding` handles vector/map destructuring including
`:keys/:strs/:syms` and `:as`. The ONLY JVM-bound pieces are the type-tag
inference (`utils/var->class`, `invocation-form->class`, `literal->class`)
and `ns-resolve`-based `:completion/locals` metadata — both optional.
cider-nrepl's own comment confirms the source is platform-pure:

> "The local binding analysis done by
> `:compliment.sources.local-bindings/local-bindings` doesn't perform any
> evaluation or execution of the context form. Thus, it is independent of
> the actual host platform differences."
> — `reference-code/cider-nrepl/src/cider/nrepl/middleware/complete.clj:39`

### bb compatibility — VERIFIED live

- `compliment.context` **loads and runs unmodified under bb 1.12.212**
  (dropped onto `--classpath`): balanced contexts parse correctly.
- Its `dumb-read-form` fallback **fails on nested closed pairs inside an
  unfinished form** (verified: `"(let [x {:a 1}] (assoc x __prefix__"` →
  `nil` — the closer-matching loop gives up when `]`'s opener isn't
  adjacent after a nested `{…}`). **Our edamame repair replaces it** and
  is strictly stronger — after repair, `cache-context` succeeds:

```clojure
;; measured in bb: edamame-repair → compliment context
:fixed "(let [x {:a 1}] (assoc x __prefix__))"
:ctx ({:idx 2, :form (assoc x __prefix__)}
      {:idx 2, :form (let [x {:a 1}] (assoc x __prefix__))})
;; and the map-key slot-kind detector working on an unfinished todo/add! call:
:map-key-ctx ({:idx nil, :map-role :key, :form {:my.plan/title "x", __prefix__ nil}}
              {:idx 1, :form (todo/add! {:my.plan/title "x", __prefix__ nil})})
```

- Compliment **as a dependency** is NOT the move: `compliment/utils.clj`
  imports `java.io.File`, `java.util.jar JarEntry JarFile`,
  `java.util.stream.Collectors` etc. for classpath scanning, and the
  vars/classes sources introspect the host JVM — meaningless for
  completing against the CLJS pod's program graph anyway.

**Port list (what we take, ~200 lines total):** `context.clj` minus
`try-read-replacing-maps`'s brace hack is still useful (keep it — it pads
odd maps so `__prefix__`-as-map-key reads), minus `dumb-read-form`
(edamame repair supersedes); `local_bindings.clj` minus tag inference and
minus `defsource`/vars deps. A working bb port of the locals half was
written and verified during this research:

```clojure
;; verified in bb — full lexical env from a parsed context, no JVM:
(let [c (ctx/cache-context "(defn process [items {:keys [limit] :as opts}]
                              (let [sorted (sort items)] (take limit __prefix__)))")]
  (bindings-from-context c))
;; => (sorted process items opts limit)
```

---

## Q2 — clojure-lsp completion (from clj-kondo analysis)

Source: `clojure-lsp/lib/src/clojure_lsp/feature/completion.clj` (master,
fetched 2026-07-10, 791 lines) + `parser.clj` + `feature/file_management.clj`
+ `shared.clj`.

### Inputs and analysis keys

`(completion uri row col db)` reads two things:

- **`db [:analysis uri]`** — clj-kondo bucket maps per file:
  `:var-definitions` (with `arglist-strs`, `:private`, `:deprecated`),
  `:var-usages` (`:refer`), `:locals` (**with
  `:scope-end-row`/`:scope-end-col`**), `:keyword-usages`/`-definitions`
  (`:ns`, `:alias`), `:namespace-usages`/`-definitions`/`-alias`,
  `:java-class-usages` etc.
- **a rewrite-clj zipper of the CURRENT text**
  (`parser/safe-zloc-of-file` → `z/of-string` on
  `db [:documents uri :text]`), navigated to the cursor with
  `(parser/to-pos row (dec col))` — "(dec col) because we're completing
  what's behind the cursor".

### The entry point (verbatim, condensed)

```clojure
(defn completion [uri row col db & [db*]]
  (let [root-zloc (parser/safe-zloc-of-file db uri)
        cursor-loc (when-let [loc (some-> root-zloc (parser/to-pos row (dec col)))] …)
        …
        cursor-element (q/find-element-under-cursor db uri row col)
        cursor-value (if (fast= :vector (z/tag cursor-loc)) ""
                       (if (z/sexpr-able? cursor-loc) (z/sexpr cursor-loc) ""))
        cursor-op (some-> cursor-loc edit/find-op)
        keyword-value? (or (keyword? cursor-value) (= ":" (str cursor-value)))
        …
        caller-var-definition (when (and caller-usage-row caller-usage-col)
                                (q/find-definition-from-cursor db uri caller-usage-row caller-usage-col))
        items (cond
                lib-completion-context (f.completion-lib/complete …)
                inside-refer?   (with-refer-elements …)
                inside-require? (with-ns-definition-elements …)
                aliased-keyword-value? (with-elements-from-aliased-keyword …)
                keyword-value?  (with-elements-from-keyword …)
                :else (cond-> []
                        cursor-full-ns?    (into (with-elements-from-full-ns …))
                        cursor-value-or-ns (into (with-elements-from-alias …))
                        simple-cursor?     (-> (into (with-local-items …))
                                               (into (with-clojure-core-items …)))
                        …))]
    (->> items sorting-and-distincting-items (take 600)
         (add-text-edit (some-> cursor-loc z/node meta shared/->range)))))
```

### Ranking — a static priority ladder, then label sort

```clojure
(def priority-order
  [:snippet :java-member-definitions :java-class-definitions :java-usages
   :clojurescript-core :clojure-core :ns-definition :unrequired-alias
   :required-alias :refer :keyword :keyword-same-ns :alias-keyword
   :simple-cursor :locals :kw-arg :lib-version :lib-name])

(defn ^:private sorting-and-distincting-items [items]
  (->> items
       (medley/distinct-by (juxt :label :kind :detail))
       (mapv #(-> % (assoc :score (get priority-kw->number (:priority %) 0))
                    (dissoc :priority)))
       (sort #(compare [(:score %2) (:label %1) (:detail %1)]
                       [(:score %1) (:label %2) (:detail %2)]))))
```

(Higher index in `priority-order` = higher score = first; **locals and
kw-args outrank core vars** — matches our intuition for slot filling.)

### Locals are scope-filtered by position, not re-derived

```clojure
(defmethod bucket-elems-xf :locals
  [_bucket matches-fn cursor-element]
  (comp
    ;; only locals whose scope includes the cursor
    (filter #(shared/inside? cursor-element %))
    (name-matches-xf matches-fn)))

;; shared.clj
(defn inside?
  "Checks if element `a` is inside element `b` scope."
  [a b]
  … (let [b-end-row (or (:scope-end-row b) (:end-row b) (:name-end-row b)) …]
      (and (row/col of a >= b's name pos) (row/col of a <= b's scope end))))
```

So clojure-lsp's "in-scope locals" = clj-kondo's `:locals` rows whose
`[row col, scope-end-row scope-end-col]` interval contains the cursor —
no context parsing at all. **This is the alternative to the Compliment
port for locals** (but it needs a parseable buffer; see Q3 caveat).

Nice extras worth stealing: **`:arglist-kws` keyword-arg completion** —
when the caller var's definition has kwargs, complete exactly those keys
minus ones already present in the call (`with-definition-kws-args-element-items`);
and **namespaced-map awareness** (`edit/namespaced-map` → strip the ns
prefix from keyword labels inside `#:foo{…}`).

### Broken / mid-edit buffers

- **Re-lint per change, async + version-guarded — not per keystroke
  blocking:** `did-change` applies the LSP range edits to the stored text
  and puts `{uri text version}` on `current-changes-chan`;
  `analyze-changes` consumes it, runs
  `lsp.kondo/run-kondo-on-text!` (clj-kondo on the in-memory text via
  stdin lint) in a `future`, and CAS-swaps the db only if no newer
  version arrived (`bump-version` "in case analysis completes out of
  order"). Completion therefore reads the **last successful analysis**
  (possibly stale) plus a **fresh zipper of the current text**.
- **Unparseable text:** `safe-zloc-of-string` catches and returns nil
  ("Probably not valid clojure code") after three targeted recoveries
  (`handle-end-slash-code`, keyword-with-end-slash, single-colon) —
  rewrite-clj itself tolerates a lot (a zloc can exist but not be
  `sexpr-able?`, guarded by `safe-zloc-sexpr` for cases like `[{:}]`).
  With no zloc, completion degrades to the broad candidate list; comments
  are special-cased to return `[]`.

---

## Q3 — clj-kondo as a library from babashka

Three routes; **the pod wins**:

| Route | What it is | Verdict |
|---|---|---|
| **Pod** (`pods/load-pod "clj-kondo"` → `pod.borkdude.clj-kondo/run!`) | the native binary itself speaking the pod protocol; resolves from PATH or `(pods/load-pod 'clj-kondo/clj-kondo "2025.06.05")` from the registry | **RECOMMENDED — measured 2.0 ms/call resident** |
| [clj-kondo-bb](https://github.com/clj-kondo/clj-kondo-bb) (`io.github.clj-kondo/clj-kondo-bb`) | clj-kondo interpreted from source under bb's SCI (`(require '[clj-kondo.core :as clj-kondo])`) | works, no extra process, but SCI-interpreted (slower); use if the pod dependency is unwanted |
| CLI one-shot | `clj-kondo --lint f.clj --config '{:output {:analysis …}}'` | measured ~10 ms warm per fork — fine for scripts, wasteful per denoise round |

API (identical across routes — same `clj-kondo.core/run!` contract, from
`reference-code/clj-kondo/src/clj_kondo/core.clj:67`): `:lint` (files /
`"-"` for stdin on CLI), `:lang`, `:filename`, `:cache`, `:config`.
Analysis is enabled via config:

```clojure
(k/run! {:lint ["draft.clj"]
         :config {:output {:format :edn
                           :analysis {:locals true :keywords true :arglists true}}}})
```

Measured output for a 5-line `defn` (this machine, 2026-07-10):

```clojure
;; :locals — WITH scope ranges (the position-aware in-scope filter):
({:name items,  :row 2 :col 22 :scope-end-row 5 :scope-end-col 64}
 {:name limit,  :row 2 :col 36 :scope-end-row 5 :scope-end-col 64}
 {:name opts,   :row 2 :col 54 :scope-end-row 5 :scope-end-col 64}
 {:name sorted, :row 3 :col 9  :scope-end-row 5 :scope-end-col 63}
 {:name item,   :row 5 :col 15 :scope-end-row 5 :scope-end-col 57} …)
;; :var-definitions — arg templates:
({:name process-items, :arglist-strs ["[items {:keys [limit offset] :as opts}]"]})
```

**Measured performance** (bb 1.12.212, clj-kondo v2025.04.07, M5):

- pod resident, 50-call average: **2.04 ms/call**
- CLI one-shot: 10 ms warm (70 ms first/cold)
- full oracle round — edamame repair (6 appends) + temp-file spit + pod
  analyze with locals: **2.34 ms** — comfortably inside a per-denoise-round
  budget (one decode forward is ~114 ms per the typeahead measurements).

**The load-bearing caveat (proven):** linting the UNBALANCED draft returns
`:findings [[:syntax 5 39] [:syntax 6 1]]` and **`:locals []`** — clj-kondo
does not produce locals analysis for a form it cannot parse. Repair is a
hard prerequisite, not hygiene. (Analyzing a *string* via the pod: the pod
lints file paths, so write the draft to a per-oracle temp file — the spit
is included in the 2.3 ms above. clj-kondo-bb and the CLI can lint stdin
with `:lint ["-"] :filename "draft.clj"`, which is what clojure-lsp's
`run-kondo-on-text!` does.)

Version constraints: `:analysis {:locals true}` has been in clj-kondo
since 2021 (clojure-lsp has depended on it since then); any current
binary (≥2023) is fine. Pin the pod with
`(pods/load-pod 'clj-kondo/clj-kondo "2025.06.05")` for reproducibility,
or accept PATH drift.

---

## Q4 — parinfer: which one, which mode — answer: neither, use edamame

### The embeddable implementations

- **`com.oakmac.parinfer`** (dep `org.clojars.oakes/parinfer {:mvn/version "0.4.0"}`)
  — the JVM (Kotlin) port; this is **the clojure-mcp path**
  (`reference-code/clojure-mcp/deps.edn:22`). **JVM classes — NOT loadable
  in babashka.**
- **parinfer-rust** — native binary; usable from bb only as a subprocess.
- **parlinter** — oakes' "liberal linter" applying paren-mode as a lint
  pass; a Node CLI, and paren mode *requires balanced input* anyway.

### The clojure-mcp repair pattern (verbatim — the whole thing)

```clojure
;; reference-code/clojure-mcp/src/clojure_mcp/sexp/paren_utils.clj
(defn parinfer-repair [code-str]
  (let [res (Parinfer/indentMode code-str nil nil nil false)]
    (when (and (.success res)
               (not (delimiter/delimiter-error? (.text res))))
      (.text res))))

;; reference-code/clojure-mcp/src/clojure_mcp/delimiter.clj — DETECTION is edamame
(defn delimiter-error? [s]
  (try
    (e/parse-string-all s {:all true :read-cond second
                           :readers (fn [_tag] (fn [data] data))
                           :auto-resolve name})
    false
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (and (= :edamame/error (:type data))
             (contains? data :edamame/opened-delimiter))))
    …))
```

i.e. even clojure-mcp uses **edamame to detect** and parinfer only to
rewrite, then re-verifies with edamame and returns nil unless the result
is clean.

### Mode semantics for REPAIR (change-as-little-as-possible)

- **Indent mode:** indentation is authoritative; parens are *inferred*.
  Always yields balanced output, but if the model's indentation is off
  (diffusion output makes no indentation promise) it silently **moves
  code between forms** — a semantic edit, not a repair.
- **Paren mode:** parens authoritative, fixes indentation — **cannot
  accept unbalanced input**, so it cannot repair.
- **Smart mode:** indent mode + change-detection so paren-authoritative
  *edits* are respected — designed for interactive editors with edit
  diffs; we have no edit history, so it degrades to indent mode.

So parinfer's only unbalanced-capable mode is the one that trusts the
signal we least trust. **Recommendation: skip parinfer entirely.** The
edamame error data is a *directed* repair signal (which delimiter, where
it was opened, where the parse died), and the append-loop is minimal by
construction (it only ever adds closers at the failure point / EOF).
For the hole-boundary artifacts in the typeahead doc (suffix echo,
off-by-one parens) the oracle already has overlap-trim + rescramble;
delimiter-append covers the "make it parseable enough to analyze" need.

---

## Q5 — the rest of the ecosystem

- **nrepl `util/completion.clj` (compliment-lite, vendored):** confirmed
  it carries NO context and NO local-bindings source — every source's
  `context` arg is threaded as `nil` and the `completions` entry ignores
  its options arg (`reference-code/nrepl/.../util/completion.clj:672-682`:
  `(f prefix nspc nil)`). Vars/ns/keywords/classes from the live JVM only.
  Nothing to reuse for cursor intelligence.
- **cider-nrepl + Compliment 0.7.1:** the `complete` op takes the
  `context` string from the client — CIDER's elisp substitutes
  `__prefix__` at point in the enclosing form. cider-nrepl also proves
  the local-bindings source is reused for CLJS as-is (quote in Q1).
- **suitable / clj-suitable:** cljs completions by *runtime introspection*
  — evals the prefix's object in the live cljs/JS environment and
  enumerates its properties. Needs a live compiler env + runtime;
  irrelevant oracle-side (and our candidate source is the DB program
  graph, which is strictly better grounded).
- **orchard:** `orchard.eldoc`/`orchard.info` format arglists/docs from
  live var metadata — JVM-var-bound; our arg templates come from
  `arglist-strs` (clj-kondo) + the pod's `:seon.fn` Malli schemas
  (`malli -function-info`, per the prior survey), so nothing to take.
- **cljfmt:** parses with rewrite-clj → throws on unbalanced input; no
  tolerance to exploit. (rewrite-clj node-level tolerance — zloc exists
  but not sexpr-able — is already how `bin/oracle-server` classifies read
  errors.)
- **edamame — the keystone (bb BUILT-IN, verified v1.12.212).** The error
  data, verbatim from `borkdude/edamame` `src/edamame/impl/parser.cljc`:

```clojure
;; throw-reader: every error carries
(ex-info msg (merge (assoc {:type :edamame/error}
                           (:row-key ctx) l     ; :row
                           (:col-key ctx) c)    ; :col
                    data))

;; EOF inside a collection (parser.cljc ~:292):
{:edamame/expected-delimiter (str delimiter)
 :edamame/opened-delimiter (str opened)
 :edamame/opened-delimiter-loc {:row row :col col}}

;; EOF inside a string (~:217): same keys, opened = \"
;; Mismatched closer (~:771): "Unmatched delimiter: ..." +
{:edamame/opened-delimiter (str char)
 :edamame/opened-delimiter-loc {:row row :col col}
 :edamame/expected-delimiter (str expected)}
;; NOTE it reads PAST the unexpected delimiter ("ignore unexpected
;; delimiter to be able to continue reading, fix for babashka socket
;; REPL") — so the mismatch case can also be repaired by REPLACING the
;; char at the error's :row/:col with :edamame/expected-delimiter.
```

  Live in bb: `(e/parse-string-all "(let [x 1] (+ x" {:all true})` →
  `{:type :edamame/error, :row 1, :col 16, :edamame/expected-delimiter ")",
  :edamame/opened-delimiter "(", :edamame/opened-delimiter-loc {:row 1, :col 12}}`.

---

## Recommended composition for the bb oracle op

New op in `bin/oracle-server` (alongside `parse`/`refine`), e.g.
`op:"cursor"` — `{code, cursor}` in, `{slot-kind, locals, candidates,
arg-template}` out. All dependencies: **bb built-ins (edamame) + the
clj-kondo pod (PATH binary or registry-pinned) + ~200 ported lines**.
No new maven deps, no parinfer, no Compliment dependency.

```text
draft + cursor
  │
  1. splice __prefix__ at the cursor offset (replacing the token there)
  │
  2. REPAIR: edamame append-loop (+ replace-at-loc for mismatched closers)
  │    — bounded (≤12 iterations), measured sub-ms; also fixes the
  │      hole-boundary off-by-one-paren class from the typeahead doc
  │
  3a. SLOT-KIND + LEXICAL ENV: ported compliment.context parse-context
  │     → innermost level: :idx n            ⇒ arg position n of (first form)
  │                        :map-role :key    ⇒ map-key slot (schema keys!)
  │                        :map-role :value  ⇒ value slot for key :idx
  │     → ported bindings-from-context       ⇒ locals (order = innermost-out)
  │
  3b. PROJECT VIEW: clj-kondo pod on the repaired text (temp file)
  │     :locals + scope ranges (cross-check/position-filter, clojure-lsp
  │       inside? logic), :var-definitions arglist-strs (arg template),
  │       :keywords (keyword candidates)
  │
  4. CANDIDATES: slot-kind dispatches the legal set —
  │     map-key slot in a specced call  → Malli schema keys (pod/DB side)
  │     arg slot                        → locals (3a) ∪ enum values ∪ verbs
  │     head slot (:idx 0)              → verb names from the program graph
  │   ranked clojure-lsp-style (locals ≻ kw ≻ core) for display order;
  │   final semantic ranking = the diffusion model's mode=rank (0.5-0.7 s)
  │
  5. ARG TEMPLATE: arglist-strs (3b) or the :seon.fn Malli schema →
       the clamp/hole segments for mode=fill
```

**Measured budget:** steps 2+3b = 2.3 ms; 3a is pure form-walking
(sub-ms); the whole oracle op stays ≲5 ms — noise next to one model
forward (~114 ms) and free against a fill (0.8–4 s).

**Library/version choices, final:**

| Concern | Choice | Why |
|---|---|---|
| tolerant read + repair signal | **edamame** (bb built-in) | `:edamame/expected-delimiter` + loc = directed minimal repair; proven |
| slot-kind + locals from the draft | **port** compliment `context.clj` + `local_bindings.clj` (≈200 lines, tag inference stubbed) | runs in bb verified; the only code anywhere that answers "what KIND of hole is the cursor in" |
| project analysis (locals-with-scope, arglists, keywords) | **clj-kondo pod**, registry pin `2025.06.05` (PATH `2025.04.07` works) | 2.0 ms/call resident; the same buckets clojure-lsp builds on |
| ranking ladder | clone clojure-lsp `priority-order` (~40 lines) | not worth a dependency |
| paren repair | **none** (no parinfer) | indent-mode trusts indentation we don't have; edamame repair is directed + minimal |
| candidate semantics | DB program graph + Malli schemas (existing pod oracle) | per the typeahead doc: the DB supplies legal sets, the model only ranks |

Scratch artifacts from this session (reproduce-by-rewrite):
`repair-bench.clj`, `pod-bench.clj`, `cp/compliment/{context,locals_lite}.clj`
in the session scratchpad; the compliment/clojure-lsp/edamame sources were
fetched from GitHub master 2026-07-10.

Sources: [compliment](https://github.com/alexander-yakushev/compliment) ·
[clojure-lsp completion](https://github.com/clojure-lsp/clojure-lsp/blob/master/lib/src/clojure_lsp/feature/completion.clj) ·
[clj-kondo](https://github.com/clj-kondo/clj-kondo) ·
[clj-kondo-bb](https://github.com/clj-kondo/clj-kondo-bb) ·
[edamame](https://github.com/borkdude/edamame) ·
[babashka book](https://book.babashka.org/) ·
vendored: `reference-code/{nrepl,cider-nrepl,clojure-mcp,clj-kondo}`.

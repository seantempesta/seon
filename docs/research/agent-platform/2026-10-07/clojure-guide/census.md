# Seon src census: where the 91k lines go

Base: `src/` = 107 `.clj/.cljc` files, **90950 lines** (`wc -l` basis; every % below is of this number unless it says otherwise). Snapshot: branch refactor/agent-platform at HEAD a8e3bde5b plus dirty tree, 2026-10-07. Research only: nothing outside `tmp/clojure-guide/` was edited, no JVM/test/bin/seon was run.

## Method (reproducible)

All scripts are in `tmp/clojure-guide/census/` and run with plain `python3` (3.x, stdlib only) from that directory:

- `clj.py`: a ~200-line Clojure reader (strings, chars, regex, `#_`, `#:ns{}`, `^meta`, reader conditionals) producing a tree with exact line spans; cross-checked: all 107 files parse to their last form, 4040 top-level defining forms found.
- `model.py`: finds top-level `def/defn/defn-/defmacro/defmulti/defonce/deftest/defmethod` (also inside top-level `do`/`when`).
- `census1.py` (items 1, 2): docstring lines = lines spanned by the docstring string node of a def/defn/defmacro/ns; whole-line `;` comments counted per line; `:malli/schema` lines = lines spanned from the key to the end of its value in any map (meta or attr-map). The three sets are disjoint.
- `census_patterns.py` (items 3, 4, 5): per-function AST rules, defined in each section below.
- `census_dup.py` (item 6), `census_refs.py` (items 7, 8, 9), `census_kw.py` (item 10), `census_extra.py` (breakdowns), `census_report.py` (this file). Raw outputs: `census1.json patterns.json dups.json refs.json kw.json extra.json`; full lists: `dead.tsv onecaller.tsv longfns.tsv dups.tsv`.

Every number is a heuristic count of lines. Categories can overlap (a `try` inside a function that also projects keys); the final table says where overlaps matter. Line spans include the lines of nested forms, so "lines of X" means "lines the X form occupies".

## Headline

| bucket | lines | % of src |
|---|---|---|
| docstrings (def/defn/defn-/defmacro/ns) | 13810 | 15.18% |
| whole-line comments | 4870 | 5.35% |
| inline `:malli/schema` (function metadata) | 5528 | 6.08% |
| blank lines | 6502 | 7.15% |
| everything else (code proper, ns forms, signatures) | 60240 | 66.23% |
| total | 90950 | 100% |

Documentation prose + inline contracts are 26.62% of src before a single line of logic. Outside src, `resources/seon/schemas` adds 13939 more lines of schema EDN (15.33% of src-size).

## 1. Docstrings and comments

Method: `census1.py`. Docstring = the string node in docstring position (after the name; `def` only when a value follows). Comment = a line whose first non-blank char is `;`.

| measure | lines | % of src |
|---|---|---|
| docstring lines | 13810 | 15.18% |
| whole-line comment lines | 4870 | 5.35% |
| trailing (inline) comments (lines) | 36 | 0.04% |
| `#_` discarded forms | 1 | 0.00% |
| `(comment ...)` forms | 0 | 0.00% |

### Top 10 files by docstring lines

Top 10 files: `db.clj` 741 (12%); `turn.clj` 717 (12%); `sci/eval.clj` 712 (17%); `cluster.clj` 635 (18%); `schema.clj` 623 (15%); `error.clj` 476 (17%); `render/web.clj` 412 (10%); `fn.clj` 396 (9%); `cluster/agent.clj` 361 (21%); `test/runner.clj` 328 (16%).

### Top 10 files by comment lines

Top 10 files: `turn.clj` 610 (10%); `db.clj` 367 (6%); `sci/eval.clj` 305 (7%); `cluster.clj` 268 (7%); `test.clj` 201 (8%); `schema.clj` 192 (5%); `render/web.clj` 170 (4%); `error.clj` 168 (6%); `cluster/agent.clj` 140 (8%); `instrument.clj` 122 (9%).

### Docstring anatomy

| owner | docstrings | lines | avg lines |
|---|---|---|---|
| defn | 1318 | 6988 | 5.3 |
| defn- | 1265 | 4973 | 3.9 |
| def | 139 | 345 | 2.5 |
| defmacro | 10 | 59 | 5.9 |
| ns | 107 | 1445 | 13.5 |

| docstring length | count | lines | % of docstring lines |
|---|---|---|---|
| 1 | 999 | 999 | 7% |
| 2-3 | 565 | 1391 | 10% |
| 4-6 | 630 | 3108 | 23% |
| 7-12 | 432 | 3774 | 27% |
| 13+ | 213 | 4538 | 33% |

- **Private functions (`defn-`) carry 4973 docstring lines (5.47% of src)** in 1265 docstrings; the project style says privates need contracts, evidently also prose.
- Docstrings of 7+ lines: 645 docstrings, 8312 lines (9.14% of src, 60% of all docstring lines). Docstrings of 13+ lines are 213 docstrings / 4538 lines.
- Docstring-bearing public defns: 1318 of 1319 public defns (the rest have none).

### Comment anatomy

| kind | lines |
|---|---|
| ;; form comment | 3846 |
| ;;; runtime-structure/section | 607 |
| banner rule | 337 |
| ; prose | 80 |

`;;` form comments are 3846 lines (the repo convention puts `;;` above a form), `;;;` section headers/banner rules are 944. Prose after `;` is almost absent. Sampled (`seon/turn.clj` 3088-3127): the comments are multi-paragraph design rationale above forms, i.e. a second docstring written in `;;`.

## 2. Inline Malli schemas and schema EDN

Method: every map pair whose key is `:malli/schema` (function metadata or attr-map); lines = key line through end of value. `census_extra.py` splits the forms by shape. Schema EDN: `wc -l` over `resources/seon/schemas/*.edn` (203 files).

| measure | forms | lines | % of src |
|---|---|---|---|
| inline `:malli/schema` forms | 2559 | 5528 | 6.08% |
| schema EDN (resources/seon/schemas, outside src) |  | 13939 | 15.33% |
| inline + EDN |  | 19467 | 21.40% |

### Top 10 files by inline schema lines

Top 10 files: `turn.clj` 563 (9%); `db.clj` 444 (7%); `schema.clj` 314 (7%); `fn.clj` 259 (6%); `cluster.clj` 245 (7%); `error.clj` 218 (8%); `sci/eval.clj` 203 (5%); `test.clj` 124 (5%); `render.clj` 120 (6%); `cluster/source.clj` 106 (8%).

### Top 10 schema EDN files

| file | lines | lines declaring `*-error` entries | comment lines |
|---|---|---|---|
| resources/seon/schemas/seon.error.edn | 456 | 2 | 1 |
| resources/seon/schemas/seon.print.edn | 408 | 0 | 22 |
| resources/seon/schemas/seon.ai.edn | 403 | 90 | 1 |
| resources/seon/schemas/seon.test.edn | 357 | 31 | 2 |
| resources/seon/schemas/seon.db.edn | 355 | 18 | 46 |
| resources/seon/schemas/seon.sci.eval.edn | 354 | 49 | 3 |
| resources/seon/schemas/seon.program.edn | 349 | 22 | 1 |
| resources/seon/schemas/seon.turn.loop.edn | 291 | 9 | 12 |
| resources/seon/schemas/seon.render.edn | 275 | 3 | 1 |
| resources/seon/schemas/seon.effect.edn | 274 | 67 | 0 |

### Shape of the inline schemas

| form length | forms | lines |
|---|---|---|
| 1 | 1373 | 1373 |
| 2-5 | 1027 | 2738 |
| 6-15 | 152 | 1278 |
| 16+ | 7 | 139 |

Lines (inside inline schemas) that carry a recognizable repeated element (a line can count in several):

| element | lines |
|---|---|
| lines naming database-value | 653 |
| lines naming a -error alternative | 410 |
| lines naming :seon.error/value | 190 |
| admission exemption/reason lines | 175 |
| generator hint lines | 145 |
| custom :fn error-message lines | 12 |

- 365 `:malli/schema` forms are textually identical to an earlier one (146 groups): 45 copies of the polymorphic-boundary predicate wrapper `[:=> [:cat [:any {:seon.schema.admission/exemption ... :reason "A total predicate ..."}]] :boolean]` and 33 copies of `[:=> [:cat :seon.schema/value] :boolean]`. Naming them once saves only 111 lines (they are mostly one-liners) but the exemption-reason prose repeats.
- `*-error` entries in schema EDN: 204 entries on 875 lines (6.3% of EDN). Each is a `[:map {:seon.render/ai ... :seon.render/html ... :error/message "..."} [:k :k] [:seon.error/message ...]]` declaration; their companion cost is the `[:or ... :x-error]` unions in function schemas (410 inline-schema lines name a `-error`).

## 3. Map re-keying / projection

Method (`census_patterns.py`, per `defn`/`defn-`/`defmacro`, AST based). A **simple read** is `(:k m)`, `(:k m default)`, `(get m :k)`, `(get-in m [.. :k])`, `(m :k)`, a wrapper around one (`or`, `some->`, `not-empty`, `vec`, `set`, `boolean`, `str`), or a local bound by `{:keys [...]}` / `{sym :k}` destructuring. Two measures:

- **projection entries**: a `{:k <simple read>}` pair in a map literal, or a `(assoc ... :k <simple read>)` pair; "rekey" when the written key differs from the read key. Lines = lines the pairs span.
- **packaging maps**: map literals or `assoc` chains with >=3 pairs where >=60% of values are bare symbols or simple reads (a map assembled from locals). Heavy function = packaging lines >= 50% of its non-docstring lines.

| measure | count | lines | % of src |
|---|---|---|---|
| simple-read projection entries (key copied from a read) | 1889 | 2238 | 2.46% |
|   of which rekey (written key != read key) | 817 |  |  |
| packaging maps / assoc chains from locals+reads | 2173 | 2827 | 3.11% |
| union of the two |   | 4120 | 4.53% |
| functions that are mostly packaging (>=50% of code lines) | 36 | 617 | 0.68% |
| calls to select-keys / rename-keys / zipmap / update-keys / update-vals | 343 |  |  |

Reading: pure projection is **small** (~2.46% of src as entry lines): Seon mostly reads and writes the same fully-qualified keys, so re-keying is rarer than the style-guide hypothesis assumed. The larger cost is *assembly*: 2827 lines build maps out of locals, often error or request maps (the same `:seon.error/*`, `:seon.db/*`, `:seon.cluster.eval/*` keys re-listed in each producer).

### Top 10 files by union of projection + packaging lines

Top 10 files: `turn.clj` 622 (10%); `render/web.clj` 286 (7%); `schema.clj` 240 (6%); `sci/eval.clj` 210 (5%); `cluster.clj` 204 (6%); `fn.clj` 161 (4%); `cluster/agent.clj` 140 (8%); `render/transcript.clj` 122 (5%); `db.clj` 111 (2%); `schedule.clj` 110 (14%).

### Examples (mostly-packaging functions, `file:line name lines/packaging-lines`)

- `src/seon/cluster.clj:3274 loop-handle` 50/37 lines, 14 pairs, 0 rekeyed reads
- `src/seon/turn.clj:5544 turn-completion-error` 36/22 lines, 8 pairs, 0 rekeyed reads
- `src/seon/call_preparation.clj:794 unavailable` 25/20 lines, 9 pairs, 1 rekeyed reads
- `src/seon/schedule.clj:258 request-entity` 34/20 lines, 10 pairs, 6 rekeyed reads
- `src/seon/render/transcript.clj:258 receipt-entry` 29/17 lines, 8 pairs, 8 rekeyed reads
- `src/seon/render/transcript.clj:440 projected-entry` 24/17 lines, 7 pairs, 1 rekeyed reads
- `src/seon/render/web.clj:267 debug-query-strings` 28/15 lines, 7 pairs, 7 rekeyed reads
- `src/seon/fn.clj:2708 manifest-data` 22/14 lines, 3 pairs, 0 rekeyed reads
- `src/seon/render/web.clj:1933 acquire-root` 22/12 lines, 3 pairs, 0 rekeyed reads
- `src/seon/turn.clj:3162 error-tx` 29/12 lines, 8 pairs, 1 rekeyed reads
- `src/seon/schedule.clj:542 error-request` 16/11 lines, 8 pairs, 1 rekeyed reads
- `src/seon/turn/batch.clj:800 sci-stages` 16/11 lines, 9 pairs, 0 rekeyed reads

`src/seon/fs/jvm.clj:127`

```clojure
(defn- path-observation
  [^Path path]
  (let [attrs (fs/read-attributes path "basic:*" {:nofollow-links true})]
    {:seon.fs.jvm/file-key (:fileKey attrs)
     :seon.fs.jvm/size (:size attrs)
     :seon.fs.jvm/modified-at (:lastModifiedTime attrs)
     :seon.fs.jvm/created-at (:creationTime attrs)
     :seon.fs.jvm/regular-file? (:isRegularFile attrs)
     :seon.fs.jvm/directory? (:isDirectory attrs)
     :seon.fs.jvm/symbolic-link? (:isSymbolicLink attrs)}))
```

`src/seon/render/transcript.clj:258`

```clojure
(defn- receipt-entry
  "One evaluation entity as a transcript entry. The entity itself is what
  `seon.repl/entity-emission` renders, so it carries its resolved namespace
  name here (the pull's own, else its agent's, else `user`)."
  {:malli/schema [:=> [:cat :map] :map]}
  [receipt]
  (let [namespace-name (or (get-in receipt [:seon.cluster.eval/ns :seon.ns/name])
                           (get-in receipt [:seon.cluster.eval/run
                                            :seon.turn/agent
                                            :seon.agent/namespace
                                            :seon.ns/name])
                           'user)]
    {::kind :eval
     ::entity (assoc-in receipt [:seon.cluster.eval/ns :seon.ns/name] namespace-name)
     ::id (:seon.cluster.eval/id receipt)
```

Both are the same shape: destructure or read N namespaced keys, then restate each as a key of a new map. The producer of the input and the consumer of the output usually already agree on the key set; the fix is to pass the original map (or `select-keys` it once) rather than hand-copy.

## 4. Error construction

Method (`census_patterns.py`; spans are line sets, unions per function so nothing is counted twice within a measure):

- **error maps**: map literals with at least one direct key starting `:seon.error/` (lines the literal spans).
- **try/catch**: `try` forms (whole span, includes the protected body) and, separately, only the `catch`/`finally` clause lines.
- **throw/ex-info**: lines of `(throw ...)` and `(ex-info ...)` forms.
- **union plumbing**: the *test* expressions of `if/when/cond/and/or/case` that mention a `:seon.error/` keyword or a predicate named `*error?`/`*refus*?`/`*failure?`.
- **error producer functions**: defns whose name contains `error`, `refus` or `failure` (whole defn lines, including docstring + schema).

| measure | lines | % of src |
|---|---|---|
| error-map literals | 3108 | 3.42% |
| try forms (whole span) | 5801 | 6.38% |
| catch/finally clauses only | 1486 | 1.63% |
| throw / ex-info forms | 1746 | 1.92% |
| union-plumbing test expressions | 1173 | 1.29% |
| **union of error maps + catch + throw + plumbing** | 6378 | 7.01% |
| error-producer functions (name contains error/refus/failure): 185 fns | 3847 | 4.23% |
| raw: every src line containing a `:seon.error/` keyword | 3717 | 4.09% |
| schema-side: `*-error` entries in schema EDN | 875 | 0.96% (of src-size) |
| schema-side: inline-schema lines naming a `-error` alternative | 410 | 0.45% |

Functions containing: a `try` 272, an error-map literal 413, a throw/ex-info 354 (of 3628 defn/defn-/defmacro).

### Top 10 files by union error lines

Top 10 files: `db.clj` 476 (8%); `turn.clj` 394 (7%); `schema.clj` 368 (9%); `sci/eval.clj` 294 (7%); `test.clj` 273 (10%); `error.clj` 264 (9%); `config.clj` 207 (21%); `fn.clj` 195 (5%); `render/web.clj` 161 (4%); `cluster/agent.clj` 154 (9%).

### Top 10 files by catch/finally clause lines

Top 10 files: `sci/eval.clj` 89 (2%); `turn.clj` 81 (1%); `test.clj` 69 (3%); `schema.clj` 66 (2%); `db.clj` 65 (1%); `schema/edn.clj` 65 (9%); `ai.clj` 59 (4%); `cluster/boot.clj` 56 (7%); `render/web.clj` 55 (1%); `sci/kernel.clj` 50 (5%).

Reading: error handling costs ~7.01% of src as lines that exist *only* to build, throw, catch or branch on errors, plus the declaration cost in schemas (about 1.41% more when inline `-error` alternatives and the EDN entries are added). `try` spans are 6.38% because a `try` encloses the real work; the incremental ceremony is the 1486 catch lines.

## 5. Defensive checks

Method (`census_patterns.py`): a **guard** is an `if/when/when-not/if-not/if-some/when-some/if-let/when-let/cond/cond->/assert` whose test is (or `and/or/not`-combines) a predicate from {nil? some? map? string? seq? sequential? vector? coll? keyword? symbol? number? int? integer? fn? ifn? boolean? set? instance? contains? empty? not-empty blank? seqable? char? ...}. `or`-default = `(or x <literal/empty coll/nil>)`. The raw table counts every call of the predicate anywhere in src (regex over source text).

| measure | count | lines | % of src |
|---|---|---|---|
| guard forms | 1471 |  |  |
| lines holding a guard test |   | 2385 | 2.62% |
| lines of small guard forms (<8 lines each): test + both branches |   | 3556 | 3.91% |
| `(or x <literal default>)` forms | 174 | 337 | 0.37% |

| predicate call | occurrences in src |
|---|---|
| contains? | 569 |
| map? | 423 |
| nil? | 351 |
| instance? | 194 |
| vector? | 184 |
| some? | 183 |
| empty? | 182 |
| string? | 169 |
| keyword? | 93 |
| symbol? | 64 |
| sequential? | 59 |
| int? | 54 |
| set? | 43 |
| seq? | 41 |

Distinct source lines containing any of these predicate calls: **2548 (2.80% of src)**; total predicate calls: 2742.

### Top 10 files by guard-form lines

Top 10 files: `db.clj` 356 (6%); `error.clj` 226 (8%); `turn.clj` 204 (3%); `fn.clj` 194 (5%); `schema.clj` 149 (4%); `sci/eval.clj` 140 (3%); `test.clj` 120 (5%); `render.clj` 103 (5%); `cluster.clj` 101 (3%); `render/web.clj` 90 (2%).

### Top 10 files by lines holding a guard test

Top 10 files: `db.clj` 244 (4%); `fn.clj` 138 (3%); `error.clj` 133 (5%); `schema.clj` 129 (3%); `turn.clj` 121 (2%); `test.clj` 108 (4%); `ai.clj` 84 (6%); `sci/eval.clj` 77 (2%); `sci/reader.cljc` 71 (8%); `cluster.clj` 64 (2%).

Reading: `contains?`(569) and `map?`(423) lead; these are mostly *map-shape dispatch* ("is this the error alternative or the value?") and *argument shape guards* inside functions that already carry a Malli contract. The contract rule (every function, private ones included, armed at call time) makes in-body `map?`/`string?` argument checks redundant; the dispatch checks are the error-union plumbing of section 4 seen from the other side.

## 6. Near-duplicate functions

Method (`census_dup.py`): every defn/defn-/defmacro with >=60 body tokens (1779 functions). Tokens come from the body only (docstring, attr-map and metadata removed). **Structural** tokens: delimiters + `K` for keywords, `STR`, `N`, core-vocabulary symbols kept, every other symbol -> `S`. **Lexical** tokens keep all symbols and keywords. Both are sets of 4-token shingles; similarity = Jaccard. All pairs with structural similarity >= 0.35 are kept (379); ranking below is by the mean of structural and lexical (structural alone has a ~0.4 baseline between unrelated Clojure functions, so a high structural score with low lexical score means "same shape, different content" -- a `let`/`cond` skeleton, not copy-paste).

| # | struct | lexical | a (file:line name lines) | b (file:line name lines) |
|---|---|---|---|---|
| 1 | 1.0 | 0.76 | src/seon/render.clj:1386 `render-ai` 17 | src/seon/render.clj:1404 `render-html` 17 |
| 2 | 1.0 | 0.317 | src/seon/db.clj:3009 `pull` 47 | src/seon/db.clj:3057 `pull-many` 49 |
| 3 | 1.0 | 0.283 | src/seon/edit.clj:33 `prefix-end-within` 11 | src/seon/edit.clj:45 `suffix-start-within` 11 |
| 4 | 0.639 | 0.637 | src/seon/edit/jvm.clj:94 `filesystem-refusal` 24 | src/seon/fs/jvm.clj:61 `error-value` 23 |
| 5 | 0.629 | 0.553 | src/seon/render/transcript.clj:693 `render-run-ai` 17 | src/seon/render/transcript.clj:787 `render-run-html` 18 |
| 6 | 0.637 | 0.517 | src/seon/sci/eval.clj:3658 `success-evaluation` 34 | src/seon/sci/eval.clj:3693 `failed-evaluation` 33 |
| 7 | 0.585 | 0.567 | src/seon/schema/edn.clj:167 `directory-resource?` 7 | src/seon/schema/edn.clj:222 `schema-resource-paths` 9 |
| 8 | 0.641 | 0.474 | src/seon/effect.clj:280 `settle-call` 61 | src/seon/effect.clj:342 `interrupt-call` 42 |
| 9 | 0.75 | 0.296 | src/seon/cluster/agent.clj:416 `render-creation-html` 13 | src/seon/config.clj:66 `render-html` 20 |
| 10 | 0.684 | 0.343 | src/seon/turn/world.clj:40 `agent-world` 29 | src/seon/turn/world.clj:77 `jvm-world` 18 |
| 11 | 0.667 | 0.349 | src/seon/db.clj:1928 `decode-attribute-maps` 24 | src/seon/db.clj:2531 `decode-pull-child` 9 |
| 12 | 0.698 | 0.31 | src/seon/cluster/agent.clj:430 `assigned-to` 21 | src/seon/cluster/agent.clj:452 `steward-of` 20 |
| 13 | 0.562 | 0.417 | src/seon/fn.clj:274 `stored-namespace-context` 9 | src/seon/sci/eval.clj:643 `row-bindings` 18 |
| 14 | 0.531 | 0.412 | src/seon/cache.clj:160 `lookup` 39 | src/seon/cache.clj:200 `lookup-within` 34 |
| 15 | 0.646 | 0.252 | src/seon/cluster/message.clj:745 `send-value` 40 | src/seon/cluster/message.clj:880 `decline` 39 |
| 16 | 0.455 | 0.438 | src/seon/print.cljc:663 `lookup-reference` 10 | src/seon/print.cljc:674 `entity-references` 13 |
| 17 | 0.541 | 0.336 | src/seon/error/refusal.clj:572 `interpreted-frames` 11 | src/seon/sci/kernel.clj:763 `stacktrace` 13 |
| 18 | 0.591 | 0.283 | src/seon/agent.clj:111 `settings!` 17 | src/seon/cluster/message.clj:725 `received!` 15 |
| 19 | 0.597 | 0.272 | src/seon/config.clj:221 `validate-layer` 35 | src/seon/config.clj:400 `validate-default-decisions` 39 |
| 20 | 0.567 | 0.278 | src/seon/fn.clj:322 `references-by-caller` 18 | src/seon/fn.clj:345 `call-targets-by-caller` 16 |
| 21 | 0.511 | 0.328 | src/my/program.clj:672 `retract-operation!` 37 | src/my/program.clj:743 `ns-unalias!` 47 |
| 22 | 0.586 | 0.246 | src/seon/fs/jvm.clj:195 `descend` 29 | src/seon/fs/jvm.clj:238 `directory-access` 37 |
| 23 | 0.424 | 0.409 | src/seon/db.clj:948 `revision-source` 22 | src/seon/render.clj:847 `call-reach-key` 28 |
| 24 | 0.454 | 0.331 | src/seon/agent.clj:12 `identity` 22 | src/seon/agent.clj:35 `archived?` 14 |
| 25 | 0.487 | 0.289 | src/seon/turn/batch.clj:734 `show` 26 | src/seon/turn/batch.clj:860 `jvm-show` 27 |
| 26 | 0.431 | 0.343 | src/seon/context.clj:160 `append-tx` 45 | src/seon/context.clj:206 `compact-tx` 46 |
| 27 | 0.422 | 0.35 | src/seon/sci/admit.clj:91 `utf8-length` 18 | src/seon/sci/admit.clj:250 `escaped-char-count` 16 |
| 28 | 0.487 | 0.274 | src/seon/schema.clj:293 `compilable-form` 81 | src/seon/schema.clj:4077 `bind-contract-predicates` 35 |
| 29 | 0.395 | 0.362 | src/seon/cluster.clj:1591 `derivation-form` 10 | src/seon/error.clj:1899 `expected-shown` 8 |
| 30 | 0.596 | 0.158 | src/seon/render/transcript.clj:94 `recent-message-entities` 17 | src/seon/render/transcript.clj:112 `recent-receipt-entities` 15 |
| 31 | 0.496 | 0.25 | src/seon/render/web.clj:3154 `page-response` 31 | src/seon/render/web.clj:3699 `health-response` 17 |
| 32 | 0.482 | 0.25 | src/seon/db.clj:1998 `unknown-attribute-error` 14 | src/seon/db.clj:2013 `value-type-error` 15 |
| 33 | 0.465 | 0.261 | src/seon/render/ns.clj:123 `task-html` 17 | src/seon/render/ns.clj:322 `render-html` 16 |
| 34 | 0.495 | 0.228 | src/seon/fs/jvm.clj:488 `read-window` 16 | src/seon/fs/jvm.clj:680 `write` 63 |
| 35 | 0.443 | 0.276 | src/seon/error.clj:376 `contract-violation-data` 20 | src/seon/error/refusal.clj:562 `contract-refusal` 9 |
| 36 | 0.478 | 0.239 | src/seon/fn.clj:1973 `call-reach` 33 | src/seon/fn.clj:2007 `reverse-closure` 39 |
| 37 | 0.432 | 0.279 | src/seon/render.clj:902 `call-cache-evidence` 36 | src/seon/render.clj:941 `retained-program-current?` 17 |
| 38 | 0.441 | 0.268 | src/seon/render/web.clj:2704 `write-package!` 52 | src/seon/render/web.clj:2757 `await-feed-package!` 27 |
| 39 | 0.458 | 0.25 | src/seon/error.clj:247 `bounded-admission` 26 | src/seon/error.clj:340 `bounded-error-admission` 16 |
| 40 | 0.42 | 0.28 | src/seon/turn.clj:2825 `continuing-reply?` 22 | src/seon/turn.clj:2924 `latest-answering-turn-t` 26 |

- Pairs with struct>=0.5 and lexical>=0.3 (genuinely similar content): **14 pairs**; sum of the smaller member of each = 324 lines (upper bound on what a shared helper would remove, counting each pair once; chains overlap).
- Pairs with struct>=0.35: 379; most are generic shape matches.
- Exact structural twins (struct = 1.0): `db/pull` vs `db/pull-many` (47 vs 49 lines, same skeleton), `edit/prefix-end-within` vs `suffix-start-within`, `render/render-ai` vs `render-html` (lexical 0.76).
- Strongest lexical copies: `render-ai`/`render-html` (`src/seon/render.clj:1386/1404`), `edit/jvm filesystem-refusal` vs `fs/jvm error-value` (lexical 0.64, in two namespaces), `sci/eval success-evaluation`/`failed-evaluation` (0.52), `transcript render-run-ai`/`render-run-html` (0.55), `effect settle-call`/`interrupt-call` (0.47), `schema/edn directory-resource?`/`schema-resource-paths` (0.57).
- The `render-*-html` / `render-*-ai` pairing is structural by design (two faces); the duplication pattern worth a rule is the **same function body written twice with one face-specific line** (`render-ai`/`render-html` at lines 1386/1404).

Honest conclusion: Seon has little classic copy-paste. Duplication is in *shape* (the same destructure/validate/build-error skeleton), which sections 3-5 measure directly. Full pair list: `dups.tsv`.

## 7. Dead or nearly-dead code

Method (`census_refs.py`): all `def/defn/defn-/defmacro/defmulti/defonce` in src (4037). References are resolved by namespace: every symbol node in src, test, script and `resources/**.clj` is resolved through the file's `:require :as`/`:refer` (a qualified `a/b` -> (ns of a, b); an unqualified `b` -> (this ns, b) or its `:refer` ns); references inside `:malli/schema` forms, strings, `resources/**` (EDN included), `bin/`, `deps.edn`, `docs/` and `.agents/` are matched by regex `ns/name`. References inside the function's own span (recursion, its own schema) are excluded. A definition can be live through a dynamic path no regex sees (e.g. `(resolve (symbol ns n))` with built strings); the bare-name check below flags where that is plausible.

| reference tier | defs | lines | % of src |
|---|---|---|---|
| no reference at all | 10 | 50 | 0.05% |
| only from strings/docs | 15 | 283 | 0.31% |
| only from tests | 81 | 1614 | 1.77% |
| only from schemas (:fn predicates) | 10 | 94 | 0.10% |
| only from resources/scripts | 184 | 2442 | 2.68% |
| referenced from other src code | 3737 | 77631 | 85.36% |

Call-graph reachability (callee edges from src bodies and schema forms; roots = referenced from resources/scripts/strings/top-level forms, any `my.*` def (agent-facing API), `-main`):

| set | defs | lines | % of src |
|---|---|---|---|
| **unreachable from any root, tests included (dead, including dead clusters)** | 25 | 327 | 0.36% |
| reachable **only through tests** (production roots cannot reach them) | 93 | 1767 | 1.94% |

### Dead list (unreachable from any root), by size

| file:line | name | kind | lines | refs seen (doc=docs only) | same bare name elsewhere |
|---|---|---|---|---|---|
| src/seon/eval/drive.clj:297 | run-episode! | defn | 63 | {"src": 1, "doc": 9} | 1 |
| src/seon/eval/drive.clj:364 | run-sample! | defn | 57 | {"doc": 6} | 0 |
| src/seon/eval/drive.clj:72 | inbound! | defn- | 33 | {"src": 1, "doc": 4} | 1 |
| src/seon/eval/drive.clj:30 | bootstrap-complete? | defn- | 20 | {"src": 1} | 1 |
| src/seon/eval/drive.clj:51 | await-fact! | defn- | 20 | {"src": 2, "doc": 9} | 2 |
| src/seon/turn/batch.clj:912 | test-request | defn | 20 | {"doc": 2} | 0 |
| src/seon/schema.clj:3677 | restore! | defn | 11 | {"doc": 8} | 3 |
| src/seon/eval/drive.clj:279 | full-transcript | defn- | 10 | {"src": 1} | 4 |
| src/seon/render/hiccup.clj:248 | escape | defn | 9 | {"doc": 7} | 0 |
| src/seon/render/web.clj:1765 | join-package | defn | 9 | {"doc": 5} | 2 |
| src/seon/web/extract.clj:6 | html | defn- | 9 | none | 198 |
| src/seon/cluster/boot.clj:817 | banner | defn | 8 | {"doc": 3} | 0 |
| src/seon/turn.clj:1680 | render-html | defn | 8 | {"doc": 4} | 113 |
| src/seon/turn/batch.clj:761 | read-only? | defn | 8 | none | 0 |
| src/seon/eval/drive.clj:290 | grading-branch! | defn- | 6 | {"src": 1} | 1 |
| src/seon/turn/world.clj:70 | test-agent-world | defn | 6 | none | 0 |
| src/seon/turn/world.clj:104 | merge-world | defn | 6 | none | 0 |
| src/seon/sci/batch.clj:29 | read-code | defn | 5 | none | 0 |
| src/seon/turn/batch.clj:830 | turn-request | defn | 5 | none | 0 |
| src/seon/eval/drive.clj:25 | creation-request | defn- | 4 | {"src": 1} | 1 |
| src/seon/blob.clj:30 | _input-stream-predicate | defonce | 3 | none | 0 |
| src/seon/cluster.clj:220 | *boot-progress!* | def | 2 | {"doc": 3} | 0 |
| src/seon/eval/drive.clj:22 | default-agent-namespace | defn- | 2 | {"src": 1} | 1 |
| src/seon/eval/drive.clj:361 | sample-cluster-name | defn- | 2 | {"src": 1} | 1 |
| src/seon/eval/drive.clj:18 | default-run-cap | def | 1 | {"src": 1} | 1 |

Note: 218 of the 327 unreachable lines are `seon.eval.drive` (`run-sample!` -> `run-episode!` and helpers), referenced only from docs: an eval driver with no caller.

Caveat for every list in this section: Seon agents call `seon.*` and `my.*` functions by name from the REPL (SCI), which no static scan sees. A function with a test reference but no src caller may be an agent-facing entry; confirm before deleting.

### Referenced only from tests (not a call from any src function)

| file:line | name | lines | test refs |
|---|---|---|---|
| src/seon/fn.clj:2158 | contract-findings | 150 | 3 |
| src/seon/fn.clj:3039 | plan-file-change | 108 | 3 |
| src/seon/fn.clj:3270 | backfill-contract-facts! | 83 | 2 |
| src/seon/instrument.clj:1228 | restore! | 57 | 3 |
| src/seon/turn.clj:3080 | committed-attributes | 51 | 6 |
| src/seon/context.clj:206 | compact-tx | 46 | 1 |
| src/seon/context.clj:160 | append-tx | 45 | 1 |
| src/seon/cluster/message.clj:880 | decline | 39 | 10 |
| src/seon/fn.clj:2495 | output-path-report | 37 | 2 |
| src/seon/schema.clj:4149 | function-contract | 35 | 2 |
| src/seon/context.clj:76 | selection | 33 | 7 |
| src/seon/schema.clj:1036 | admission-from-asserting-transaction | 31 | 3 |

(81 defs, 1614 lines in total; top 12 shown. 10 are in `src/my`, which is the agent API and lives by being called from agents at the REPL, so treat those as live.)

### Reachable only through tests (top 12 by lines; full list in `dead.tsv`)

| file:line | name | lines |
|---|---|---|
| src/seon/fn.clj:2158 | contract-findings | 150 |
| src/seon/fn.clj:3039 | plan-file-change | 108 |
| src/seon/fn.clj:3270 | backfill-contract-facts! | 83 |
| src/seon/cluster/source.clj:432 | record-results-at-head! | 71 |
| src/seon/fn.clj:2430 | source-output-paths | 64 |
| src/seon/render/transcript.clj:822 | agent-history-under | 51 |
| src/seon/context.clj:206 | compact-tx | 46 |
| src/seon/context.clj:160 | append-tx | 45 |
| src/seon/turn.clj:2519 | form-settlement | 42 |
| src/seon/turn.clj:2480 | assignment-facts | 38 |
| src/seon/fn.clj:2346 | output-graph | 37 |
| src/seon/fn.clj:2495 | output-path-report | 37 |

Reading: static dead code is **not** where the lines go: 10 zero-reference defs (50 lines) and 25 unreachable (327 lines), i.e. well under 1% of src. The code is densely and intentionally used. The near-dead mass is the 93 defs (1767 lines, 1.94%) that production roots cannot reach but tests can: machinery kept alive by its own tests.

## 8. Functions with exactly one caller (inlining candidates)

Method: defn/defn-/defmacro, exactly one distinct calling src function, no references from schemas, resources, scripts, strings or top-level forms. "No test" = additionally no test file references it (a test reference would have to move with an inline). Overhead = defn line + docstring + `:malli/schema` lines + 1 blank, which is what inlining deletes outright; the body moves.

| set | fns | lines | overhead lines (header+doc+schema) |
|---|---|---|---|
| exactly one src caller | 1470 | 29159 | 8418 |
|   and no test reference | 1185 | 21415 | 6039 |
|   and caller in the same namespace | 1130 | 20298 | 5596 |
|   no test ref, <=15 lines | 693 | 6097 | 2626 |
|   no test ref, public (`defn`) | 89 | 2117 |  |

36% of all 4037 functions have exactly one caller (32.06% of src).

### Top 10 files by single-caller (no-test) function lines

| file | lines | fns |
|---|---|---|
| src/seon/db.clj | 1342 | 67 |
| src/seon/render/web.clj | 1238 | 72 |
| src/seon/turn.clj | 934 | 55 |
| src/seon/schema.clj | 916 | 42 |
| src/seon/fn.clj | 877 | 41 |
| src/seon/test/runner.clj | 833 | 30 |
| src/seon/render/transcript.clj | 775 | 48 |
| src/seon/sci/eval.clj | 635 | 41 |
| src/seon/error.clj | 597 | 32 |
| src/seon/render/walk.clj | 596 | 25 |

### The 15 largest single-caller functions with no test reference

| file:line | name | lines | caller |
|---|---|---|---|
| src/seon/test/runner.clj:1610 | record-latest-tx | 178 | seon.test.runner/record-tx |
| src/seon/schema.clj:2198 | projection-build | 171 | seon.schema/build-projection |
| src/seon/test/runner.clj:1470 | complete-members | 139 | seon.test.runner/record-tx |
| src/seon/db.clj:679 | query-index-patterns | 119 | seon.db/append-query-evidence! |
| src/seon/sci/admit.clj:293 | open-node | 117 | seon.sci.admit/project |
| src/seon/schema.clj:3327 | pulled-attribute-entry | 116 | seon.schema/pulled-form-from-spec |
| src/seon/cluster/boot.clj:156 | stand-boot-layers! | 114 | seon.cluster.boot/start! |
| src/seon/render/web.clj:2785 | feed | 108 | seon.render.web/feed-response |
| src/seon/batch.clj:321 | evaluate-step | 102 | seon.batch/form-step |
| src/seon/fn.clj:3506 | compile-index-transaction | 101 | seon.fn/reconcile-tx-in |
| src/seon/cluster/boot.clj:32 | stand-cluster-runtime! | 97 | seon.cluster.boot/stand-boot-layers! |
| src/seon/schema/internal.cljc:329 | assert-complete-schema! | 96 | seon.schema/assert-complete-contract! |
| src/seon/render/walk.clj:251 | acquisition-members | 93 | seon.render.walk/root-acquisition |
| src/seon/test/runner.clj:1337 | prepare-failures! | 91 | seon.test.runner/commit-results! |
| src/seon/cluster/source.clj:1109 | proven-file | 83 | seon.cluster.source/proven-files |

Reading: many single-caller helpers are fine (a name documents a step). The target is the *small* ones whose header+docstring+contract cost more than the body: among the <=15-line group, overhead is 2626 of 6097 lines.

## 9. Long functions (>60 lines)

Method: `defn/defn-/defmacro` form span (docstring, schema and body included).

**209 functions, 21714 lines = 23.87% of src.** Of those, >100 lines: 74 fns / 11534 lines; >200 lines: 12 fns / 3287 lines.

| file:line | name | lines | of which docstring+schema |
|---|---|---|---|
| src/seon/test.clj:701 | select | 367 | 10 |
| src/seon/test.clj:2207 | run | 363 | 36 |
| src/seon/sci/eval.clj:3862 | evaluate | 359 | 33 |
| src/seon/sci/eval.clj:2315 | acquire-program! | 320 | 6 |
| src/seon/turn.clj:4377 | call-turn | 266 | 12 |
| src/seon/db.clj:4088 | write-owned-values-error | 250 | 10 |
| src/seon/turn.clj:5807 | step | 249 | 28 |
| src/seon/effect.clj:735 | request* | 237 | 1 |
| src/seon/schema.clj:2648 | projection-with-declarations | 233 | 19 |
| src/seon/test.clj:1174 | admit-run | 225 | 16 |
| src/seon/sci/eval.clj:1206 | install-row! | 210 | 5 |
| src/seon/turn.clj:2045 | system-turn | 208 | 5 |
| src/seon/cluster/agent.clj:1049 | acquire-context! | 200 | 18 |
| src/seon/schema.clj:2965 | projection-from-entities | 193 | 12 |
| src/seon/turn.clj:5072 | resume-turn | 184 | 14 |
| src/seon/schema.clj:1486 | assert-complete-contract! | 181 | 5 |
| src/seon/test/runner.clj:1610 | record-latest-tx | 178 | 13 |
| src/seon/fault.clj:138 | record! | 176 | 40 |
| src/seon/instrument.clj:429 | violation | 171 | 5 |
| src/seon/schema.clj:2198 | projection-build | 171 | 5 |
| src/seon/cluster.clj:1665 | full-source-refresh! | 170 | 14 |
| src/seon/cluster/boot.clj:648 | request! | 168 | 2 |
| src/seon/error.clj:615 | prepare | 168 | 7 |
| src/seon/render.clj:1496 | render-call | 168 | 3 |
| src/seon/error.clj:1353 | commit-call | 164 | 12 |
| src/seon/cluster/source.clj:560 | publish! | 163 | 8 |
| src/seon/cluster.clj:1228 | populate-source! | 159 | 18 |
| src/seon/cluster/agent.clj:1602 | armer-step | 154 | 18 |
| src/seon/fn.clj:2158 | contract-findings | 150 | 9 |
| src/seon/fn.clj:692 | var-entity | 145 | 1 |

### Top 10 files by lines in long functions

| file | lines in >60-line fns | fns |
|---|---|---|
| src/seon/turn.clj | 2546 | 22 |
| src/seon/render/web.clj | 1540 | 16 |
| src/seon/fn.clj | 1494 | 16 |
| src/seon/test.clj | 1481 | 9 |
| src/seon/sci/eval.clj | 1439 | 10 |
| src/seon/cluster.clj | 1263 | 13 |
| src/seon/schema.clj | 1263 | 9 |
| src/seon/db.clj | 1075 | 12 |
| src/seon/test/runner.clj | 690 | 7 |
| src/seon/error.clj | 632 | 5 |

The full list of 209 is in `longfns.tsv`. Seven functions exceed 250 lines; `seon.test/select`, `seon.test/run` and `seon.sci.eval/evaluate` exceed 350.

## 10. Namespaced keyword vocabulary

Method (`census_kw.py`): every namespaced keyword token (`:a/b`, `::b`, `::alias/b`, and bare keys under `#:ns{}` / `#::{}` map prefixes) in src, test, script and `resources/**/*.edn`; `{:keys [a/b]}` destructuring counts as a use of `:a/b`. "Occurrences" = tokens; "places" = distinct (file, line) sites. Dynamically built keywords (`(keyword ns n)`) and keywords in docs are invisible, so the low-use counts are upper bounds on true disuse.

| uses | by occurrences (tokens) | by places (lines) |
|---|---|---|
| 1 | 1464 | 1475 |
| 2 | 1255 | 1322 |
| 3 | 858 | 879 |
| 4 | 514 | 581 |
| 5 | 498 | 512 |
| 6+ | 2727 | 2547 |

- Distinct namespaced keywords: **7316**.
- Used in only one or two places: **2797 (38%)**; exactly one place: 1475 (20%).

| the <=2-place keywords are... | count |
|---|---|
| test only | 1109 |
| src only | 736 |
| declared in schema/EDN only (no src/test use found) | 416 |
| schema + src | 305 |
| other mix | 143 |
| src + test | 88 |

### Top 10 keyword namespaces by distinct keywords

`seon.test` 219 (109); `seon.db` 217 (72); `seon.schema` 174 (65); `seon.batch` 156 (19); `seon.operator` 152 (95); `seon.sci.eval` 142 (59); `seon.program` 141 (20); `seon.render.web` 129 (57); `seon.error` 126 (21); `seon.agent` 109 (30). (distinct keywords, in parentheses those used in <=2 places)

### Top 10 keyword namespaces by <=2-place keywords

`seon.test` 109 of 219; `seon.operator` 95 of 152; `seon.db` 72 of 217; `seon.schema` 65 of 174; `seon.sci.eval` 59 of 142; `seon.render.web` 57 of 129; `seon.probe` 52 of 64; `seon.flow` 45 of 87; `seon.schema-test` 45 of 72; `seon.test.runner` 41 of 78.

### Top 10 files containing the most single-use keywords (src files only)

`db.clj` 32; `cluster.clj` 30; `schema.clj` 27; `render/web.clj` 26; `sci/eval.clj` 23; `flow.clj` 20; `fn.clj` 17; `test/runner.clj` 17; `turn.clj` 16; `cluster/source.clj` 13.

Reading: roughly 38% of the vocabulary is touched in at most two places. A schema-declared key that a reader never reads is a stored fact without a consumer (project rule: "every stored fact names its consumer or is deleted"). Counts here do not prove disuse but give the audit list: `kw.json` has every keyword with its per-group counts.

## Ranked summary

Removable-line estimates are judgments, each stated with its reasoning; they are deliberately conservative and **not additive** (docstring, schema, error and projection lines overlap when a single function has all of them).

| pattern | lines | % of src | realistic removable lines | how estimated | style rule that prevents it |
|---|---|---|---|---|---|
| Docstrings | 13810 | 15.18% | 4334 | private-fn docstrings cut to nothing/one line (50% of 4973) + 25% of the rest (long public docstrings restating contract/body) | docstrings only where a name+contract does not already say it; none on `defn-`; <=3 lines, longer content goes to one Markdown page |
| Error construction/handling (maps+catch+throw+plumbing) | 6378 | 7.01% | 2551 | one error constructor from declared fields replaces hand-listed `:seon.error/*` maps; boundary-only try/catch instead of local catch+rethrow | build errors only through the one constructor; try/catch only at entrant boundaries; no local throw/catch pairs |
| Whole-line comments | 4870 | 5.35% | 2435 | banner rules + `;;;` headers (944) and second-docstring `;;` paragraphs (merge into docstring or delete) | `;;` only for a non-obvious why of one form; no banners; no commented-out code |
| Schema EDN (outside src) | 13939 | 15.33% | 2090 | `*-error` entries (875 lines) generated from one error-family declaration; keywords declared but never used | declare an error family once; delete keys without a consumer |
| Inline Malli schemas | 5528 | 6.08% | 1934 | derive/shorthand: 1-line predicate contracts, shared named input/output schemas instead of `[:or X :x-error]` repeats, drop exemption-reason/`:gen/` prose (175+145 lines) | one contract line per function: named schemas in EDN, never restated shapes or reasons inline |
| Defensive guards (guard-form lines) | 3556 | 3.91% | 1778 | `map?`/`string?`/`nil?` checks of arguments the contract already validates; keep union-dispatch only | trust the contract: no argument-shape checks inside armed functions; `or` defaults only where the schema says optional |
| Packaging/projection maps | 4120 | 4.53% | 1648 | pass the original map or `select-keys` once; do not restate N keys as N pairs | one key set per concept, carried unchanged; build derived maps with `select-keys`/`assoc` of the changed keys only |
| Single-caller helpers (overhead only) | 6039 | 6.64% | 1575 | inline helpers <=15 lines: header+docstring+schema lines vanish (60% assumed inlinable) | extract only for reuse or a named concept with its own test; otherwise `let` |
| Reachable only via tests | 1767 | 1.94% | 883 | machinery nothing in production reaches; delete with its tests (50%: some are intentional test fixtures/harness) | a function earns its place by a production caller; test-only helpers live in test/ |
| Single-use keywords (>=1 key line each) | 2797 | (count, not lines) | 839 | est. 30% of <=2-place keywords are undeclared-consumer facts; each is ~1 schema line + 1 write line | every stored fact names its consumer (already a law); audit script flags <=2-place keys |
| Dead (unreachable) | 327 | 0.36% | 327 | `seon.eval.drive` cluster + a few helpers | delete on sight; the linter flag + root sweep |
| Near-duplicate functions | 324 | 0.36% | 162 | pairs with struct>=0.5 and lexical>=0.3; shared helper keeps one copy | one function parameterized by face/operation, not two copies (render-ai/render-html pattern) |
| Long functions (>60 lines) | 21714 | 23.87% | 0 | not removable by itself; they host most of the error/guard/packaging lines counted above and are the place to look for them | functions <=40 lines, one role each |

Sum of the removable column: 19717 lines; even after discounting 30% for overlap that is ~13801 lines (15% of src). Documentation, contract and error ceremony (first 6 rows) is where the mass is; classic dead code and copy-paste are small.

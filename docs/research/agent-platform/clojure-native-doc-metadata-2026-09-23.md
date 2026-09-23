---
type: research
status: current
created: 2026-09-23
scope: Clojure-native documentation and link facts on namespaces, vars and schemas; context composed from executable forms ordered by dependency
---

# Clojure-native doc metadata and form-composed context

Implementation is frozen; this note changes nothing. Pinned revisions read:
clojure `b18d3adc` (2026-07-20), clj-kondo `57252e07` (2026-07-30; installed
binary `v2026.07.24`), sci `fcbd8862`, malli `56394c54`. Probes ran in a
scratch directory with the `clj-kondo` binary and a plain `clojure -M -e` JVM;
the Seon JVM was not touched. Every claim is marked VERIFIED (read or ran) or
UNVERIFIED.

## 1. Answer first

1. **Clojure evaluates var and namespace metadata.** `def` analyzes the symbol's
   metadata map as an expression (`Compiler.java:589`); `defn` merges its
   attr-map into that metadata (`core.clj:299-330`); `ns` does the same through
   `.resetMeta` with the unquoted map (`core.clj:5945-5948`); SCI does the same
   (`sci/impl/analyzer.cljc:880`, `:922`). So `{:see-also [str/join]}` stores a
   *function object*, which is why Clojure itself writes `:arglists '(...)`.
   VERIFIED (source + JVM probe, §2.1).
2. **`#'` var-quotes are the one link spelling that is right in all three
   worlds.** Runtime: the metadata holds Vars, `(map symbol ...)` gives
   `clojure.string/join`; an unresolvable target fails loudly at load.
   clj-kondo: each `#'x` is a resolved var-usage (aliases resolved) and a
   dangling one is an `:unresolved-var` finding. That is the owner's
   "unbreakable graph" at the producer, with no string parsing. VERIFIED (§2.3).
3. **clj-kondo already hands Seon every user metadata key.** Seon's analyzer
   config requests `:var-definitions {:meta true}` and
   `:namespace-definitions {:meta true}` (`src/seon/fn/analyzer.clj:30-39`),
   and `seon.fn` already reads `:malli/schema`, `:seon.fn/internal?`,
   `:seon.fn/doc-order`, `:inline`, `:seon.test/usage` from it
   (`src/seon/fn.clj:672-695`, `:640-660`). A new link key costs no analysis
   change. VERIFIED.
4. **But usages inside metadata carry no `:from-var`.** Var-usages and keywords
   found in a definition's metadata are reported without `:from-var` (probe
   §2.3), so `references-by-caller` files them under the `nil` caller
   (`src/seon/fn.clj:382-400`) and `keywords-by-holder` drops them
   (`src/seon/fn.clj:420-445`). Today a `:see-also` link would be analyzed and
   linted but not attributed to its var. The smallest fix reads the `:meta`
   value itself and qualifies it with the namespace context, exactly as
   `qualify-schema-symbols` already does for `:malli/schema`
   (`src/seon/fn.clj:284-297`, used at `:690-695`). VERIFIED.
5. **No tool reads docstring `[[wikilinks]]` as data.** codox and cljdoc render
   them to HTML links (URLs §2.2); clj-kondo ignores docstring contents (probe
   §2.3); Seon has no parser for them (`rg -F '\[\['` over `src/` finds none;
   48 wikilinks exist in 13 source files and 6 in schema EDN, all inert
   prose). VERIFIED. Keep them as human prose if wanted; never make them the
   link carrier.
6. **`:see-also` is a convention, not a tool-backed key.** CIDER/orchard's
   see-also comes from the ClojureDocs export, not var metadata
   (`orchard/clojuredocs.clj` `see-also`, fetched); Clojure itself uses
   `:see-also` once, with URL strings (`clojure/pprint/cl_format.clj:57`).
   The tool-backed keys are `:doc`, `:arglists`, `:added`, `:deprecated`,
   `:private`, `:macro`, `:no-doc`, `:skip-wiki`, `:test`, `:malli/schema`.
   VERIFIED (§2.2).
7. **Schemas already link with values.** Across 210 schema files, 3,267
   property maps: `:description` 407 (prose), and link-valued properties
   holding qualified symbols/keywords: `:seon.render/ai` 345,
   `:seon.render/html` 341, `:seon.db/component-schema` 136,
   `:seon.render/form` 11, `:seon.issue/cites` 8, among others (probe §2.4).
   The data side already does what the var side lacks. VERIFIED.
8. **Two render-to-context contracts coexist, and the dependency-ordered one
   is not on the live path.** `walk/ordered-episode` (`src/seon/render/walk.clj:856-925`)
   orders `{:seon.repl/comment :seon.repl/form}` entries by symbol readiness,
   but its only caller is `seon.bootstrap/next-entry-in`
   (`src/seon/bootstrap.clj:644-721`), and `next-entry`/`pull-result` have no
   production caller (only `test/seon/bootstrap_test.clj:94-158`). The live
   system turn (`src/seon/turn.clj:1852-1905`, `:2059-2235`) takes AI render
   output as **source text** built with `str` and `repl/source-text`, re-reads
   it with `planned-sources` (`src/seon/turn.clj:3107-3124`), and orders by
   walk unit order. VERIFIED by source; not probed on the installed JVM.
9. **The simplest form-composed context is ~50 lines over existing owners** and
   lets roughly 600 lines of bootstrap candidate machinery and the string
   assembly in AI renderers go (§3). The REPL entrance, read-evidence currency
   (`system-plan`, `src/seon/turn.clj:1959-2000`) and the value renderer stay.

## 2. Evidence

### 2.1 What Clojure itself defines and reads (VERIFIED)

| Key | Producer | Reader |
|---|---|---|
| `:doc` | `defn` docstring → `{:doc ...}` `core.clj:299-301`; `def` docstring `Compiler.java:578`; `ns` docstring `core.clj:5926-5929` | `clojure.repl/doc` → `print-doc` `repl.clj:83-113`, `:131-145`; `find-doc` regex over `:doc` and `:name` `repl.clj:115-129` |
| `:arglists` | `defn` computes `(sigs fdecl)` quoted `core.clj:317` | `print-doc` `repl.clj:91-92` |
| `:added`, `:deprecated` | author attr-map (e.g. `core.clj:19`, `:26`) | no clojure.repl reader; codox, clj-kondo (below) |
| `:private` | `defn-` `core.clj:5070` | `ns-publics` filters it `core.clj:4212-4218`; `ns-interns` does not `core.clj:4230-4236` |
| `:macro` | `(. (var defn) (setMacro))` `core.clj:338`, `defmacro` | `print-doc` prints "Macro" `repl.clj:97-98` |
| `:tag` | author hint; `defn` passes `:rettag` `core.clj:334` | compiler |
| `:file` `:line` `:column` | `Compiler.java:576` (`RT.LINE_KEY`, `COLUMN_KEY`, `FILE_KEY`) | `source-fn` reads the file at `:line` `repl.clj:147-170` |
| `:ns` `:name` | `Var.setMeta` adds both `Var.java:242` | `print-doc` `repl.clj:83-88` |
| `:declared` | `declare` `core.clj:2790-2793` | compiler |
| `:test` | `deftest` puts `(fn [] body)` on `:test` `test.clj:624-638`; `with-test` `test.clj:611-620` | `clojure.core/test` `core.clj:4974-4982`; `test-var` `test.clj:710-715`; `test-vars` `test.clj:736` |
| ns `:doc` `:author` | `ns` merges docstring and attr-map into the name's meta, `.resetMeta` `core.clj:5926-5948` | `namespace-doc` `repl.clj:80-81` |

`apropos` and `dir-fn` enumerate `ns-publics` names (`repl.clj:181-199`);
`resolve` is `core.clj:4395`. `:test` is Clojure's original "the test lives on
the var" mechanism: the test is a value in the tested var's metadata, not a
separately named thing.

Metadata is evaluated. `Compiler.java:589`:
`Expr meta = mm.count()==0 ? null:analyze(context == C.EVAL ? context : C.EXPRESSION, mm);`
SCI: `(analyze ctx m)` for `def` (`analyzer.cljc:880`) and
`(analyze ctx meta-map)` for `defn` (`analyzer.cljc:922`). JVM probe:

```clojure
(require '[clojure.string :as str])
(defn ^{:see-also [str/join]} a "doc" [x] x)
(defn b {:see-also '[str/join clojure.string/split]} [x] x)
(defn d {:see-also [#'str/join]} [x] x)
(:see-also (meta #'a))                 ;=> [#object[clojure.string$join ...]]
(:see-also (meta #'b))                 ;=> [str/join clojure.string/split]  ; alias NOT resolved
(map (comp symbol resolve) (:see-also (meta #'b)))
                                       ;=> (clojure.string/join clojure.string/split)
(:see-also (meta #'d))                 ;=> [#'clojure.string/join]
(map symbol (:see-also (meta #'d)))    ;=> (clojure.string/join)
(select-keys (meta #'b) [:ns :name :file :line :column :arglists])
;=> {:ns #object[Namespace "user"], :name b, :file "NO_SOURCE_PATH", :line 1, :column 82, :arglists ([x])}
```

### 2.2 Community keys with real tooling

| Key | Reader | Value kind | Status |
|---|---|---|---|
| `:no-doc` | codox ("add `:no-doc true` to the var's metadata", https://github.com/weavejester/codox README); cljdoc (https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-library-authors.adoc); clj-kondo exports it on namespaces (`analysis/README.md:88`) | boolean | VERIFIED (fetched) |
| `:skip-wiki` | Clojure's own autodoc convention; used in `clojure/pprint/cl_format.clj:84`, `clojure/java/process.clj:143`; clj-kondo's README uses it as its `:meta` example (`analysis/README.md:40`) | boolean | usage VERIFIED; the autodoc reader UNVERIFIED |
| `:added`, `:deprecated` | codox (README); clj-kondo exports both and lints `:deprecated` use (`analysis/README.md:88`, `:111`) | string (version) or `true` | VERIFIED |
| `:doc/format :markdown` | codox; enables `[[wikilink]]` rendering ("Vars in the current namespace will be matched first") | keyword | VERIFIED (fetched) |
| `[[ns/var]]` in docstrings | cljdoc renders wikilinks in markdown docstrings (URL above); codox with `:doc/format` | string inside prose | VERIFIED; no data reader exists |
| `:see-also` | no tool reads var metadata `:see-also`. orchard's see-also is `(:see-alsos (get-doc var-name))` from the ClojureDocs export, keywords → symbols (https://github.com/clojure-emacs/orchard `src/orchard/clojuredocs.clj`); clojure.pprint writes URL strings (`cl_format.clj:57`) | convention only | VERIFIED |
| `:malli/schema` | `malli.instrument/collect!` reads it from var meta and arglists (`malli/instrument.clj:45`, `:69`, `:137-142`); Seon reads it from clj-kondo `:meta` (`src/seon/fn.clj:690-695`) | Malli form (keywords, qualified symbols) | VERIFIED |
| `:test` | `clojure.test` (§2.1) | fn value | VERIFIED |
| `:style/indent` | CIDER/clojure-mode indentation spec (https://docs.cider.mx/cider/indent_spec.html) | int / vector | UNVERIFIED (not read) |

### 2.3 clj-kondo: what metadata analysis exports, and whether links resolve

Docs: user metadata is returned when requested with `:meta true` or a key
sequence (`analysis/README.md:32-42`); common keys `:deprecated :doc :author
:added :no-doc` on namespaces (`:88`) and `:private :macro :deprecated :doc
:added` on vars (`:111`). Implementation: `lift-meta-content2`
(`src/clj_kondo/impl/metadata.clj:37-83`) first runs every metadata node
through `common/analyze-expression**` ("use dorun to force analysis",
`:62-66`) — metadata is analyzed as code, matching Clojure's evaluation — then
`meta-node->map` `sexpr`s the node **as written** (`:9-20`). So `:meta` holds
the unresolved spelling; resolution appears only as separate var-usages.

Probe file (scratchpad `kondo/demo.clj`), config
`{:analysis {:var-definitions {:meta true} :namespace-definitions {:meta true} :keywords true}}`:

```clojure
(ns demo.core
  "Demo namespace. See [[clojure.string/join]]."
  {:author "x" :see-also [clojure.string/split]}
  (:require [clojure.string :as str]))
(defn ^{:see-also [str/join clojure.string/blank?]} a
  "Joins. See [[clojure.string/join]] and [[str/split]]." [xs] (str/join "," xs))
(defn b "Attr-map form."
  {:see-also '[clojure.string/trim demo.core/a] :deprecated "0.2"
   :malli/schema [:=> [:cat :string] :string] :seon/schema :seon.fn/entity}
  [s] s)
```

Output (trimmed to the relevant keys):

```clojure
:namespace-definitions [{:name demo.core :author "x"
                         :doc "Demo namespace. See [[clojure.string/join]]."
                         :meta {:author "x", :see-also [clojure.string/split]}}]
:var-definitions [{:name a :doc "Joins. See [[clojure.string/join]] and [[str/split]]."
                   :meta {:see-also [str/join clojure.string/blank?]}}
                  {:name b :deprecated "0.2"
                   :meta {:seon/schema :seon.fn/entity, :deprecated "0.2",
                          :malli/schema [:=> [:cat :string] :string],
                          :see-also '[clojure.string/trim demo.core/a]}}]
:var-usages ({:name join,   :to clojure.string, :row 6}            ; from ^{:see-also}, alias resolved, NO :from-var
             {:name blank?, :to clojure.string, :row 6}            ; NO :from-var
             {:name join,   :to clojure.string, :from-var a, :row 9} ; the body call
             ...)                                                   ; nothing for [[...]], nothing for quoted '[...]
:keywords ({:name "schema", :ns malli, :row 15} {:name "schema", :ns seon, :row 16}
           {:name "entity", :ns seon.fn, :row 16} ...)              ; NO :from-var
:findings [{:type :unresolved-namespace, :message "Unresolved namespace clojure.string. Are you missing a require?", :row 3}]
```

The last finding is itself a result: ns attr-map metadata is evaluated before
`:require`, so a namespace-level link must name an already-loaded namespace.

Unquoted attr-map `{:see-also [str/join demo.core2/missing]}` → var-usages
`join→clojure.string` and `missing→demo.core2`, plus finding
`:unresolved-var "Unresolved var: demo.core2/missing"`. Var-quoted
`{:see-also [#'str/join #'demo.core3/missing]}` → `:meta {:see-also [(var str/join) (var demo.core3/missing)]}`,
the same two resolved var-usages, and the same `:unresolved-var` finding.
With Seon's `:symbols true` (`src/seon/fn/analyzer.clj:34`), a `'`-quoted
vector reports `{:symbol str/join}` and `{:symbol clojure.string/trim}` as
written (alias not resolved, no `:from-var`); a `(quote [...])` list form
reported nothing. All VERIFIED.

Link spelling summary:

| Spelling | Runtime value | kondo resolves + lints broken link | Seon today |
|---|---|---|---|
| `[str/join]` | fn object (name lost) | yes, yes | nil-caller reference |
| `'[str/join]` | symbol as written | no (`:symbols` as written) | nil-caller `:symbols` |
| `[#'str/join]` | Var → `symbol` gives qualified name; unresolvable fails load | yes, yes | nil-caller reference |
| `"[[str/join]]"` in doc | string | no | inert prose |

### 2.4 Malli properties and Seon's use of them (VERIFIED)

Malli properties are an open map read with `m/properties`
(`malli/core.cljc:2586`); JSON Schema export keeps `:title :description
:default` (`malli/json_schema.cljc:39`). Any namespaced property holding a
qualified keyword or symbol is a value link, readable without text parsing.
Seon reads properties throughout `src/seon/schema.clj` (e.g. `:242`, `:1328`,
`:1496`, `:1820`, `:4042`; 4,194 lines).

Probe: walk every `[type {props} ...]` in the 210 files of
`resources/seon/schemas` with `clojure.edn` → 3,267 property maps. Top keys:
`:optional` 1425, `:min` 614, `:description` 407, `:seon.db/attributes` 364,
`:seon.render/ai` 345, `:seon.render/html` 341, `:error/message` 268,
`:seon.db/component-schema` 136, `:seon.db/component` 136, `:seon.config/dial`
100, `:seon.db/index` 79, `:seon.wake/context-inert` 65, `:gen/gen` 64,
`:seon.db/identity` 57, `:seon.program/partition` 53. Properties whose value is
a qualified keyword/symbol (a direct link): `:seon.render/ai` 345,
`:seon.render/html` 341, `:seon.db/component-schema` 136, `:gen/gen` 64,
`:seon.ai/request-attribute` 18, `:seon.schema.admission/exemption` 18,
`:seon.program/written-by` 13, `:seon.render/form` 11, `:seon.issue/cites` 8,
`:seon.error/refusal-shape` 8, `:seon.program/row-schema` 6,
`:seon.program/source-attribute` 6, `:seon.sci.binding/target` 5, and
`:seon.fn/reference-to` 1. `:seon.issue/cites` (`seon.issue.edn:8-14`) is
already a generic "this value names that identity" property.

### 2.5 Seon's render-to-context machinery today (VERIFIED by source)

**Path A — dependency-ordered forms (test-only).**
- Producers: `:seon.render/form` schema property on 11 schemas (e.g.
  `seon.ns.edn:15` → `seon.render.ns/namespace-form`, `seon.schema.edn:100` →
  `schema-form`, `seon.message.edn:29,69`, `my.note.edn:4,13,22`,
  `seon.error.edn:234`, `my.turn.edn:14,21`). They return a bare form
  (`namespace-form` → `(dir 'ns)`, `src/seon/render/ns.clj:738-742`) or an
  entry `{:seon.repl/comment ";..." :seon.repl/form '(help)}`
  (`situation-form`, `src/seon/cluster/agent.clj:212-218`). Entry schema:
  `resources/seon/schemas/seon.repl.edn:26-29`.
- Candidates: `bootstrap/direct-candidates` and `listing-candidates`
  (`src/seon/bootstrap.clj:303-369`) render each walk member with
  `:seon.render/output :seon.render/form`, key them `[lookup index]`, chain
  `:seon.repl/previous-key`. `pull-result` (`:579-625`) adds plan intent
  candidates.
- Ordering: `walk/ordered-episode` (`src/seon/render/walk.clj:856-925`, 70
  lines; helpers `reference-keys`, `form-symbols`, `explained-symbol?`
  `:818-854`). A candidate is ready when its subject was introduced by an
  earlier settled value and every qualified/dotted symbol in its form
  (`form-symbols`, `:840-849`) is explained. It emits one entry at a time;
  each call re-derives the prefix.
- Execution: `next-entry-in` (`src/seon/bootstrap.clj:644-721`) re-runs
  `pull-result` for every entry, matches stored sources, reads each stored
  `:seon.eval/shown` back with `edn/read-string` as a "print node", and
  throws if the stored prefix differs. This contradicts `ordered-episode`'s
  own docstring ("Saved shown text is an observation, never decoded").
- Callers: `rg 'next-entry|pull-result|ordered-episode' src test` finds only
  `test/seon/bootstrap_test.clj:94-158`. The opening episode generator is not
  called by production code.
- Size: `bootstrap.clj` 874 lines, of which `:204-735` (~530) is this
  candidate machinery; walk ordering ~110.

**Path B — AI source text (live).**
- Producers return strings. `seon.render.ns/render-ai` concatenates
  `";; I should inspect ...\n"` with `(repl/source-text (list 'dir ns))` and a
  count query (`src/seon/render/ns.clj:795-826`); `function-ai` embeds
  "(run to inspect)" forms inside `;;` comment lines, so they are never
  evaluated (`:744-774`); `seon.render.test/render-ai` wraps prose in
  `(clojure.core/identity "Test ...: ...")` and appends three reads
  (`src/seon/render/test.clj:40-62`); `render-identity-ai` returns a string or
  `{:seon.render/source-blocks [...]}` with issue origins
  (`src/seon/cluster/agent.clj:257-290`). `repl/source-text` pprints a form
  with code-dispatch (`src/seon/repl.clj:91-97`).
- Capture: `render.clj` records `:seon.render.call/source` and
  `:seon.render/source-blocks` per call (`src/seon/render.clj:1507-1544`).
- Assembly: `declared-sources` (`src/seon/turn.clj:1852-1905`) runs
  `walk/neighborhood` with `:seon.render/output :seon.render/ai`, pulls each
  captured call's source text, re-reads it with `planned-sources`
  (`:3107-3124`, the same reader path as a provider reply) and tags each
  form with its lookup.
- Currency and dedupe: `latest-evaluations` (`:1907-1939`) and `system-plan`
  (`:1959-2000`) dedupe by `[ns form]`, keep only read-only previous
  evaluations, and mark each `:none`/`:unchanged`/`:changed` through
  `db/read-evidence-current?`; unchanged reads are not evaluated.
- Execution: `system-turn` (`:2059-2235`) evaluates selected sources through
  `evaluate-sources` on the agent's context, or previews them; results are
  saved as shown text rendered by the value renderer
  (`src/seon/render/value.clj`, 715 lines).
- Ordering: walk unit order (pull/fact order) then retained reads by basis;
  no dependency ordering.

The hand-ordering substitute: `:seon.fn/doc-order`
(`resources/seon/schemas/seon.fn.edn:22`, sorted at
`src/seon/sci/eval.clj:1647`) is set only on four functions in
`src/my/plan.clj:117-170`.

## 3. Proposal

### 3.1 Documentation facts (Part A)

Principle: prose where a reader reads; values where a machine links.

| Fact | Where | Spelling |
|---|---|---|
| What it does, why, examples | docstring (`:doc`) / schema `:description` | prose; `[[x/y]]` allowed for cljdoc readers, never parsed by Seon |
| Related vars | var or ns metadata `:see-also` | vector of var-quotes `[#'ns/f #'alias/g]` |
| Related schemas/attributes | var `:see-also` or schema property | qualified keywords (kondo `:keywords` resolves `::alias/k`) |
| Contract | `:malli/schema` (existing) | Malli form |
| Lifecycle | `:added`, `:deprecated` (existing standard) | version string |
| Hidden from docs | `:no-doc` (codox/cljdoc) | `true` |
| Test ownership | `:test`/`deftest` (existing) | — |

Keys added: one (`:see-also`), stored as a derived fact `:seon.fn/see-also`
(`[:set :qualified-symbol]`), mirroring how `:malli/schema` becomes
`:seon.fn/spec`. Keywords in `:see-also` land in the existing
`:seon.fn/keywords` relation. Implementation: in the function row builder
(`src/seon/fn.clj:672-695`) add
`(:see-also metadata) (assoc :seon.fn/see-also (see-also-symbols ...))`, where
`see-also-symbols` unwraps `(var x)` and qualifies with
`qualify-schema-symbols` and the namespace context — about 10 lines plus one
schema line; the ns row builder (`:299-306`) gets the same 2 lines for
`:seon.ns/see-also`. Broken links are caught by the clj-kondo
`:unresolved-var` finding and, at load, by Clojure/SCI failing to resolve the
var-quote. Retires: `:seon.fn/doc-order` once dependency order (§3.2) exists;
any future wikilink parser before it is written.

### 3.2 Context composed from forms (Part B)

What exists: walk → per-entity AI renderer → source text → reader → forms →
currency filter → REPL entrance → shown text. The design keeps the ends and
removes the text round trip and the second, unused ordering path.

1. **Contract.** An AI render function returns a vector of `:seon.repl/entry`
   maps — `{:seon.repl/comment "; one line" :seon.repl/form form}` — the
   schema that already exists (`seon.repl.edn:26-29`). No strings, no
   `identity`-wrapped prose: prose belongs to the docstring the form's result
   (`(doc f)`) shows. HTML renderers are unchanged.
2. **Collect.** `walk/neighborhood` (unchanged) → `mapcat` entries in unit
   order → `distinct` by form.
3. **Order by definition-before-use.** Each entry *introduces* the symbols its
   form defines (`def`/`defn` name) and its subject's identity; it *uses*
   `walk/form-symbols` of its form. Stable topological sort over pull order:
   an entry waits only for introducers present in the set; uses with no
   introducer (core, libraries, already-loaded program) impose nothing.
   Clojure core has no topological sort and none is on Seon's classpath
   (`clojure -Spath` lists neither tools.namespace nor clj-reload); the
   reference shape is clj-reload's `topo-sort` (`reference-code/clj-reload/src/clj_reload/parse.clj:163-176`),
   ~15 lines with pull order instead of `sort` for determinism. A cycle is
   emitted in pull order with one flat diagnostic naming its members, never
   dropped.
4. **Evaluate** through the existing `system-plan` → `evaluate-sources` path.
   The source string stored for the evaluation is `pr-str`/`source-text` of
   the form, derived once at the writer; the comment becomes
   `:seon.cluster.eval/comment` (already selected at `turn.clj:1983-1986`).
5. **Render** results with the value renderer, clipped once there.

Size: collect + order + comment/form hand-off ≈ 40-60 lines inside
`seon.turn`/`seon.render.walk`. Deletions it enables: `bootstrap.clj`
`:204-735` candidate/episode machinery (~530) and `walk/ordered-episode`
with helpers (~90) — both test-only today — plus the `str`/`source-text`
assembly in each AI renderer, and `planned-sources` for system sources
(keep it for provider replies). Net: several hundred lines removed; needs the
bootstrap tests retired with it.

Pitfalls:
- **Replay cost.** Every turn re-derives the neighborhood; only `:changed`
  and `:none` forms may run (`system-plan` currency). Order must be a pure
  function of the entry set so an unchanged set yields identical source and
  prompt-cache-stable text; memoize on the database value and entry set.
- **Effects.** Generated forms must be reads. The existing
  `read-only-evaluation?` (`turn.clj:1941-1957`) and `generated-read-fault`
  (`:2006-2057`) checks stay the gate; an entry whose evaluation transacts or
  records an effect is refused by name, not re-run.
- **Stale shown text.** Unchanged reads reuse the saved shown text as-is;
  never decode it to recover references (the dead path does,
  `bootstrap.clj` `edn/read-string` in `next-entry-in`) and never re-render it
  under a new profile.
- **Budget.** Bound the entry count and total source at collection; elide
  whole entries with a named bound and requery form, never clip a form.
- **Readiness vs membership.** The dead path also required a subject to be
  "introduced by an earlier settled value" (walk.clj:870-876), which makes
  order depend on results. The proposal orders by forms alone; results never
  reorder context.

## 4. Open questions for the owner

1. **Link key name.**
   (a) `:see-also` unqualified, Clojure-metadata convention beside `:doc`/`:added`;
   stored as `:seon.fn/see-also`. **Recommended**: source metadata follows
   Clojure; stored facts are namespaced, as with `:malli/schema` → `:seon.fn/spec`.
   (b) `:seon.doc/see-also` everywhere — satisfies "fully namespaced keys" in
   source too, unknown to every outside reader.
   (c) Reuse `:seon.issue/cites` semantics on vars — one link property, but
   the name speaks of issues.
2. **Link spelling.**
   (a) Var-quotes `#'ns/f` — resolved by kondo, linted, loud at load.
   **Recommended.** (b) Quoted symbols `'[ns/f]` — no load coupling, no
   resolution or lint. (c) Unquoted symbols — lint yes, runtime stores fn
   objects; reject.
3. **Docstring wikilinks.**
   (a) Keep as optional prose for cljdoc; Seon never parses them.
   **Recommended.** (b) Remove them and forbid in review. (c) Parse them into
   links — a mini-language in strings; contrary to the owner's direction.
4. **Attribution of metadata usages.**
   (a) Read `:meta` and qualify in `seon.fn` (~10 lines). **Recommended.**
   (b) Join nil-caller usages to definitions by row/col span — generic, more code.
   (c) Upstream clj-kondo change to emit `:from-var` for metadata usages —
   cleanest data, dependency-fork work.
5. **Fate of path A.**
   (a) Delete bootstrap episode machinery when §3.2 lands. **Recommended.**
   (b) Delete now as abandoned code, before §3.2. (c) Wire it into
   production instead — keeps result-dependent readiness and 600 lines.

# Symbols everywhere — the complete inventory

Dated 2026-09-17, branch `steward-platform` at `ec8740ae5`. Read-only lane; no
source edits outside this file, no test JVMs, no cluster state changes. Two
read-only `mcp__seon__eval_clj` queries against `default` (jvm mode, custody via
`(seon.operator/connection "default")`, no transact) supply every measured
number below.

**The owner's words (2026-09-17):** "all functions and vars and anything that is
a symbol should be stored as a symbol and not a string. ALL OF IT. Upgrade the
DB and reset it and you can delete the entire thing from scratch. no migration."

Grounding read end to end: [AGENTS.md](../../../../AGENTS.md) §3 (the
`anything that IS a symbol is stored as a symbol` bullet, `AGENTS.md:423-434`)
and the vocabulary table (`AGENTS.md:540-600`);
[program-facts-are-the-runtime PRD §1e F5](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
(`:220-222`);
[batch-c-store-and-parked Part 2(a) and 2(b)](../plan/decisions/batch-c-store-and-parked-2026-09-17.md)
(`:375-455`);
[`reference-code/datahike/src/datahike/schema.cljc`](../../../../reference-code/datahike/src/datahike/schema.cljc);
[`src/seon/schema/datahike.clj`](../../../../src/seon/schema/datahike.clj)`:55-275`.

---

## 0. Headline

| | count |
|---|---|
| storable attributes to change | **13** |
| attributes already symbol-typed (no change) | 19 families |
| mirror tags batch C said to delete | **4 — do NOT delete, see §1.3** |
| src write/read sites naming a sym attribute | **80** lookup refs + **8** string literals + **78** `(str …)` coercions |
| src `(symbol …)` read-backs | **132** |
| test string-literal sym values | **303** |
| test lookup refs `[:seon.fn/sym …]` / `[:seon.test/sym …]` | **314** |
| test `(symbol …)` read-backs | **83** |
| `:seon.fn/sym` datoms on `default` today | 5 030 |
| `:seon.test/sym` datoms | 1 833 |
| `:seon.fn/call-arities` datoms (tuple member 0 flips with it) | 74 281 |
| `:seon.ns/name` datoms (already symbols) | 435 |

**The identity probe batch C asked for is already answered by the live store, and
the answer is yes — no drill is needed** (§3).

**One finding contradicts a grounding document** (§1.3): `:seon.search/index
:symbol` is not a type mirror; it selects the search tokenizer. Deleting it
breaks identifier search.

---

## 1. Attribute inventory

### 1.1 Must change — storable, symbol-valued, stored as `:string`

| # | attribute | declared at | current Malli | proposed | identity / tuple |
|---|---|---|---|---|---|
| 1 | `:seon.fn/sym` | `resources/seon/schemas/seon.fn.edn:156-160` | `[:string {:min 1, :seon.db/identity true, :seon.search/index :symbol, :seon.program/row-schema :seon.fn/fn, :seon.program/source-attribute :seon.fn/source}]` | `[:qualified-symbol {…same props…}]` | **`:seon.db/identity true`** |
| 2 | `:seon.test/sym` | `resources/seon/schemas/seon.test.edn:49-53` | `[:string {:min 1, :seon.db/identity true, :seon.search/index :symbol, …}]` | `[:qualified-symbol {…}]` | **`:seon.db/identity true`** |
| 3 | `:seon.fn/call-arities` | `resources/seon/schemas/seon.fn.edn:39-53` | `[:set {…} [:tuple [:string {:min 1}] [:int {:min 0}]]]` | `[:set {…} [:tuple :qualified-symbol [:int {:min 0}]]]` | **tuple member 0**; installed `:db/tupleTypes` becomes `[:db.type/symbol :db.type/long]` for free (§3.3). The docstring at `:44-47` calls the member "the callee's `:seon.fn/sym` identity value" — it must be updated in the same edit. |
| 4 | `:seon.fn/caller` | `resources/seon/schemas/seon.fn.edn:54` | `[:string {:min 1}]` | `:qualified-symbol` | — |
| 5 | `:seon.fn/callee` | `resources/seon/schemas/seon.fn.edn:55` | `[:string {:min 1}]` | `:qualified-symbol` | — |
| 6 | `:seon.fn/pending-calls` | `resources/seon/schemas/seon.fn.edn:81-83` | `[:set {…} [:string {:min 1}]]` | `[:set {…} :qualified-symbol]` | cardinality-many; writer `src/seon/fn.clj:882`, resolver `src/seon/turn.clj:1218-1253` |
| 7 | `:seon.test/pending-subject` | `resources/seon/schemas/seon.test.edn:11` | `:string` | `:qualified-symbol` | queried at `src/seon/fn.clj:1357`, `src/seon/turn.clj:1233-1246` |
| 8 | `:seon.instrument/fn` | `resources/seon/schemas/seon.instrument.edn:10` | `[:string {:min 1}]` | `:qualified-symbol` | rides `:seon.error` values; also `:seon.instrument/contract-violated` aliases it (`:23`) |
| 9 | `:seon.error/throwable-class` | `resources/seon/schemas/seon.error.edn:265` | `[:string {:min 1}]` | `:symbol` | **already a live mismatch:** `src/seon/error.clj:285` writes `(symbol class-name)` into it. Its own docstring at `:273` admits the duplication ("throwable-class retains the historical string evidence"). Once it is a symbol, `:seon.error/exception-class` (`:273`) is a second mechanism for one fact (§2.5) — dissolve it in the same commit. |
| 10 | `:seon.test/changed` | `resources/seon/schemas/seon.test.edn:141-142` | `[:vector [:or :qualified-symbol [:string {:min 1}] :seon.program/identity]]` | drop the `[:string {:min 1}]` branch | a widening-to-narrowing at a request boundary; §2.5 breakage, so it lands at the reset |
| 11 | `:seon.test/destructive-path` | `resources/seon/schemas/seon.test.edn:176-178` | `[:vector {:min 1} [:string {:min 1}]]` | `[:vector {:min 1} :qualified-symbol]` | it is "the declared call path from a test down to the destructive owner" — function symbols. Built at `src/seon/test.clj:218-262`, rendered at `:296` |
| 12 | `:seon.schema/namespace-name` | `resources/seon/schemas/seon.schema.edn:15` | `:string` | `:symbol` | a namespace name |
| 13 | `:seon.render/ai` | `resources/seon/schemas/seon.render.edn:19` | `[:or :qualified-symbol :string]` | `:qualified-symbol` | see §1.2 on how render-pair properties are stored. The `:string` branch exists only because the SAME key means rendered AI text in `:seon.render/failure` (`resources/seon/schemas/seon.render.edn:167-169`) and in `:seon.render/output` (`:192`). **One key, two meanings is the §2.5 defect** — the rendered-text use needs a different key (e.g. `:seon.render/rendered`, which already exists at `:194`) before the `:or` can close. |

### 1.2 Render pair properties — how they are stored

`:seon.render/ai` and `:seon.render/html` are Malli schema PROPERTIES
(`resources/seon/schemas/seon.render.edn:34-36`, `:185`), authored on entity
schema maps. They DO become facts: `:seon.program/projected-properties`
(`resources/seon/schemas/seon.program.edn:22-28`) declares that a program entity
map's rows "also carry every qualified Malli property projected onto them …
Its owned attributes are the row's own qualified keys", and the schema family
row map is tagged with it at `resources/seon/schemas/seon.schema.edn:63-65`. So a
schema row holds `:seon.render/ai <qualified-symbol>` as its own datom, typed by
the `:seon.render/ai` declaration above. Once the `:string` branch is gone, that
datom is `:db.type/symbol` with no further work.

`:seon.eval/renderer` (`resources/seon/schemas/seon.eval.edn:2`) is ALREADY
`:qualified-symbol` and stores the renderer that produced saved shown text —
but `src/seon/repl.clj:395` still does `(symbol renderer-symbol)` and
`src/seon/turn.clj:86` still does `[:seon.fn/sym (str (:seon.eval/renderer …))]`.
Both dissolve with item 1.

### 1.3 Mirror tags — batch C's recommendation is REFUTED, do not delete

[batch-c Part 2(a)](../plan/decisions/batch-c-store-and-parked-2026-09-17.md):413-416
says `:seon.search/index :symbol` "is a hand-maintained mirror of the value's
real type — derive-or-die's target" and should be deleted with the string.
`AGENTS.md:431-433` repeats it.

It is not a type mirror. `src/seon/search.clj:170-174`:

```clojure
indexed-text (case mode
               :symbol (str/join " " (tokens value))
               :text text)
```

`:symbol` selects the IDENTIFIER TOKENIZER (`tokens`, `src/seon/search.clj:81`,
splitting a qualified name into searchable parts) against `:text`, full-text.
The decisive evidence is the fourth site: `:seon.schema/key`
(`resources/seon/schemas/seon.schema.edn:43-46`) carries
`:seon.search/index :symbol` and is a `:keyword`, not a string — so the tag
cannot be saying "this string is really a symbol".

The four sites: `seon.fn.edn:158`, `seon.test.edn:51`, `seon.ns.edn:7`,
`seon.schema.edn:44`.

**Recommendation to the implementation lane: keep all four, change nothing about
them in this pass, and raise the naming with the orchestrator separately** — the
enum member is `[:enum :text :symbol]` (`resources/seon/schemas/seon.search.edn:4`)
and `:identifier` would be the honest name once it also covers keywords. Deleting
them silently drops identifier tokenization for functions, tests, namespaces and
schema keys — an absence-of-signal regression (search still answers, just worse).

`src/seon/search.clj:158-166` already branches on `symbol?` before `string?`;
after the change the string branch is dead and can go.

### 1.4 Already symbols — no change, and the working precedent

Verified live (§3): `:seon.ns/name` (`resources/seon/schemas/seon.ns.edn:5-9`)
is `:symbol` **with `:seon.db/identity true`**, installed as
`{:db/valueType :db.type/symbol, :db/unique :db.unique/identity}`.

Others already symbol-typed, listed so the lane does not touch them:
`:seon.effect/capability` (`seon.effect.edn:1`, `:qualified-symbol`);
`:seon.eval/renderer` (`seon.eval.edn:2`);
`:seon.call-preparation/supplier-symbol` (`seon.call-preparation.edn:43`);
`:seon.fn.binding/symbol` (`seon.fn.binding.edn:3`);
`:seon.fn.argument/label-symbol` (`seon.fn.argument.edn:11`);
`:seon.schema.map-entry/key-symbol` (`seon.schema.map-entry.edn:9`);
`:seon.error/frame` (`seon.error.edn:272`) and `:seon.error/exception-class` (`:273`);
`:seon.test.failure/throwable` (`seon.test.failure.edn:17`);
`:seon.test.runner/namespaces` (`seon.test.runner.edn:3`);
`:seon.test/namespaces` (`seon.test.edn:143`, typed by `:seon.ns/name`);
`:seon.maintenance/result-projection` (`seon.maintenance.edn:1`);
`:seon.program/written-by` (`seon.program.edn:14`);
`:seon.source/populate` / `:seon.source/activation` (`seon.source.edn:17`, `:19`);
`:seon.sci.binding/target` + `/public-namespace` (`seon.sci.binding.edn:1-2`);
`:seon.sci.reader/tag` + `/call` (`seon.sci.reader.edn:1`, `:4`);
`:seon.sci.eval/ending-ns` (`seon.sci.eval.edn:195`), `/referenced-vars` (`:60`),
`/missing-function-row` (`:49`);
`:seon.ns.alias/*`, `:seon.ns.refer/*`, `:seon.ns.import/*`;
`:seon.render/namespace` (`seon.render.edn:191`), `/rendering` (`:236`), `/html` (`:185`);
`:seon.schema/identity-projection` / `/predicate` and the finding families
(`seon.schema.edn:58`, `:61`, `:82-122`);
`:my.agent/namespace` (`my.agent.edn:2`); `:my.edit.form/head` + `/name`
(`my.edit.form.edn:1-2`); `:my.plan.item/about-token` (`my.plan.item.edn:25`).

Refs, not strings, and therefore already correct:
`:seon.fn/capability-fn` (`seon.fn.edn:5-11`), `:seon.effect/capability-fn`
(`seon.effect.edn:2-6`), `:seon.schedule.task/function`
(`seon.schedule.task.edn:3`), `:seon.error/fn` (`seon.error.edn:271`),
`:seon.issue/functions` / `/tests` / `/namespaces` (`seon.issue.edn:8-19`),
`:seon.eval/renderer-fn` (`seon.eval.edn:3`).

### 1.5 Stay strings — and why

- `:seon.issue/unresolved` (`seon.issue.edn:27`) — "exact citation tokens that
  match no installed identity value". A token that resolves to nothing may not be
  a symbol at all; string is the honest type.
- `:seon.issue/commits` (`:28`), `:seon.issue/id`, `/path` — git and filesystem
  coordinates.
- `:seon.fn/arglists`, `/spec`, `/source`, `:seon.fn.arity/arity`
  (`seon.fn.arity.edn:1`), `:seon.fn.binding/form` — `pr-str` of forms, not
  single symbols. A round trip through `read-string` is the seam, not a type.
- `:seon.fn.argument/label-string` (`seon.fn.argument.edn:10`) and
  `:seon.schema.map-entry/key-string` (`seon.schema.map-entry.edn:8`) — these
  hold genuine string labels/keys beside the symbol variants
  (`src/seon/program.cljc:649`, `src/seon/fn/schema_shape.clj:182`). Not mirrors.
- `:seon.search/identity` (`seon.search.edn:14`, `[:or [:string {:min 1}] :symbol
  :keyword]`) — search spans families keyed by issue slug, file path AND symbol.
  The `:or` is real polymorphism; keep it.
- `:seon.agent/id`, `:seon.cluster/name`, `:seon.fn.file/relative-path`,
  `:seon.error/signature`, `:seon.test/command`, `:seon.test/paths`,
  `:seon.test/next-tier` — names, paths, digests, argv.

### 1.6 The one data hazard: four symbols that do not read back

Measured on `default`: of 5 030 `:seon.fn/sym` values, **4 do not round-trip
through the reader**, all from clj-kondo's unknown-namespace placeholder:

```
:clj-kondo/unknown-namespace/postVisitDirectory
:clj-kondo/unknown-namespace/thrown-with-msg?
:clj-kondo/unknown-namespace/thrown?
:clj-kondo/unknown-namespace/visitFile
```

`(symbol ":clj-kondo/unknown-namespace" "postVisitDirectory")` constructs fine
and Datahike stores and orders it fine, but `pr-str` emits text the reader reads
back as a KEYWORD. 0 of 1 833 `:seon.test/sym` values are affected. The sites
that mint them are `src/seon/fn.clj:336`, `:342`, `:402` (from
`(::analyzer/to usage)` etc., where kondo reports `:clj-kondo/unknown-namespace`).
Any seam that `pr-str`s a program row and reads it back — an EDN manifest, a
blob, a stored form — corrupts those four. The lane must either normalize the
placeholder at the analyzer seam (`src/seon/fn/analyzer.clj`) or prove no seam
round-trips these values through the reader; either way it is a named
deliverable, not a footnote.

---

## 2. Every write and read site, by owning namespace

Counted with `rg`; `file:line` for each. Totals in §0.

### 2.1 `src/seon/fn.clj` — the indexer (**the largest seam**)

Writes the identity as a string after building the correct symbol:

- `:329` `(map #(str (symbol (str (::analyzer/ns %)) …)))`
- `:336` `(str (symbol (str (::analyzer/to usage)) …))` — call edges
- `:342` `(str (symbol (str (::analyzer/from usage)) …))`
- `:402` `(str (symbol (str (::analyzer/from entry)) …))`
- `:582` `qualified (symbol (str namespace-name) (str (::analyzer/name entry)))` — the symbol exists here
- `:600` `:seon.fn/sym (str qualified)` — and is thrown away here
- `:604` `:seon.test/sym (str qualified)`
- `:636` `:seon.fn/sym (str qualified)` (second row shape)
- `:867` `(str (symbol (str (::analyzer/ns %)) …))`

Reads/keys by the stringified symbol: `:417`, `:498`, `:507`
(`[:seon.fn/sym (str subject)]`), `:616`, `:620`, `:621`, `:624`, `:629`, `:631`,
`:654`, `:658`, `:659`, `:662`, `:667`, `:669`, `:686`, `:688`
(`[:seon.fn/sym (str capability)]` — the capability join), `:774`, `:864`
(`(symbol program-symbol)`), `:879`, `:882` (pending-calls), `:899`, `:907`,
`:1171`, `:1311`, `:1745` (`(get rows-by-symbol (str handler-symbol))`),
`:2122` (`(symbol function-symbol)`), `:2327`.

63 `:seon.fn/sym` mentions and 15 `:seon.test/sym` mentions in this file.

### 2.2 `src/seon/program.cljc` — projection and identity

- `:33` `(map #(symbol (str namespace-name) (str %)))`
- `:814` `{:seon.program/function-symbol (:seon.fn/sym row)}` — hands a string out under a `-symbol` key
- `:1084` `(let [qualified (str (symbol (str namespace-name) …))]`
- `:1087-1088` `[[:seon.fn/sym qualified] [:seon.test/sym qualified]]` — both lookup refs

### 2.3 `src/seon/sci/eval.clj` — the evaluation/admission seam

Read-backs (`(symbol (:seon.fn/sym …))`): `:670`, `:823`, `:969`, `:1276`,
`:1561`, `:2584`, `:2642`.
String writes/joins: `:318` `(let [qualified (str (symbol …))]`, `:341`,
`:404` `(= (str qualified) …)`, `:691` `:seon.fn/sym (str function-symbol)`,
`:701` `(db/pull db '[*] [:seon.fn/sym (str function-symbol)])`, `:1031`,
`:1135`, `:1146`, `:1291` `(= (str qualified) (:seon.fn/sym %))`.

### 2.4 `src/seon/test.clj` and `src/seon/test/selection.clj`

- `:59` schema shape `[:seon.fn/sym :seon.fn/sym]`
- `:63`, `:233`, `:411-412` (`(if (:db/id … [:seon.test/sym s]) [:seon.test/sym s] [:seon.fn/sym s])`), `:461`, `:573`, `:591`, `:896` — lookup refs
- `:200` `[:seon.fn/sym owner]`
- `:218-262` destructive path construction; `:263` `(namespace (symbol test-symbol))`
- `:289`, `:293`, `:296` prose joins over the string path
- `:514` `(requiring-resolve (symbol test-symbol))`
- `:521`, `:595`, `:632` `(symbol (namespace (symbol %)))`

42 `:seon.test/sym` mentions.

### 2.5 `src/seon/test/runner.clj` — recording

- `:53` `(symbol (str ns) (str name))` then `:203` `{:seon.test/sym (str test-symbol)}` — mint-and-discard
- `:222` `(symbol (.getName (class …)))` into `:seon.test.failure/throwable` (already correct)
- `:227`, `:619`, `:1122`, `:1140`, `:2135` (`(symbol (namespace (symbol (:seon.test/sym %))))`), `:2213` — read-backs
- `:989` `[:seon.fn/sym "seon.test-support/with-fresh-database"]` — string literal lookup ref
- `:2171`, `:2231` `(when-let [member-symbol (:seon.fn/sym member)] …)`

39 `:seon.test/sym` + 26 `:seon.fn/sym` mentions.

### 2.6 `src/seon/issue.clj` and `src/seon/issue/{opening,detect}.clj`

- `:451` `(symbol detector)`, `:467` `(requiring-resolve (symbol detector))`
- `:464`, `:498`, `:533` `[:seon.fn/sym detector]` lookup refs
- `:492`, `:546` `[:seon.issue/detector :seon.fn/sym]`
- `:578` `(some-> (get-in row [:seon.issue/detector :seon.fn/sym]) symbol)`
- `:588` `(mapv :seon.test/sym test-rows)` into a generated `my.test/check` form — **the values become part of an executable form the agent sees**, so the string/symbol difference is visible in agent context today
- `:602-637`, `:651-665`, `:765`, `:775-786`, `:800-821` — pulls, sorts and prose
- Citation resolution: `:140-155` (`citation-attributes`), `:173-180`
  (`citation-index`), `:194` (`resolve one token`), `:265-340`, `:686-694`.
  This is the seam that must symbol-ize a token when the cited identity attribute
  is `:seon.fn/sym`, `:seon.test/sym` or `:seon.ns/name`, and leave it a string
  for `:seon.issue/id`, `:seon.fn.file/relative-path`, `:seon.error/signature`,
  `:seon.test.run/id`, `:seon.schema/key` (keyword). The declared citation map is
  `resources/seon/schemas/seon.issue.edn:8-26`.

### 2.7 `src/seon/effect.clj`

`:146` `[:seon.fn/sym (str function-symbol)]`, `:171` `(symbol (str (ns-name …)))`,
`:489`, `:593`, `:694`, `:711`, `:717`, `:723`, `:744`, `:761`
(`:seon.effect/owner [:seon.fn/sym (str owner-sym)]`) — 10 `(str owner-sym)`
coercions in one namespace.

### 2.8 `src/seon/schedule.clj`

Five hand-written string literals in the maintenance portfolio:
`:50`, `:55`, `:60`, `:65`, `:70` (`:seon.fn/sym "seon.operator/collect!"` etc.).
Then `:88`, `:106` (`:seon.schedule.task/function [:seon.fn/sym function]`),
`:214`, `:222`, `:326`, `:350`, `:376`, `:582`
(`(requiring-resolve (symbol function))`), `:586`, `:643`, `:659`.

### 2.9 `src/seon/turn.clj` — adoption and the pending resolver

`:86` `[:seon.fn/sym (str (:seon.eval/renderer …))]`; `:130`
`[:seon.test/sym test-symbol]`; `:1218-1253` pending-calls / pending-subject
retraction by string value; `:1335`, `:1339`, `:1387` `(symbol identity-value)`,
`:1402-1403`, `:1565`.

### 2.10 `src/seon/error.clj`

`:277` frame symbols (correct); `:285` `(symbol class-name)` into the
string-declared `:seon.error/throwable-class`; `:286` `(symbol function)`;
`:570` `:seon.error/exception-class (symbol class-name)`; `:572`
`[:seon.fn/sym (str function)]`; `:1269`, `:1462`, `:1597` lookup refs.

### 2.11 `src/seon/instrument.clj`

`:167`, `:220`, `:225`, `:240`, `:247`, `:384`, `:386`, `:406`, `:496`
(`(assoc caps :seon.instrument/fn (str function-symbol))`), `:522`.
Nine `(str function-symbol)` in one namespace, all into agent-visible errors.

### 2.12 Render namespaces

`src/seon/render.clj:272`, `:673`, `:677`, `:903`;
`src/seon/render/ns.clj:271` `(let [qualified (symbol sym)]` (10 mentions);
`src/seon/render/test.clj:58` `(namespace (symbol target))`;
`src/seon/render/transcript.clj` (5 mentions);
`src/seon/render/web.clj:1`.

### 2.13 Remaining src

`src/seon/problems.clj:330`, `:333` (two `(str (symbol …))`), `:356`;
`src/seon/cluster.clj:2166`, `:2283`, `:2297-2299`;
`src/seon/cluster/source.clj:325`; `src/seon/cluster/agent.clj:260-261`;
`src/seon/call_preparation.clj:388` `(symbol supplier)`, `:989`;
`src/seon/plan.clj:632-642`; `src/seon/db.clj:2489` `(symbol function-symbol)`;
`src/seon/schema.clj:2358`, `:2385`, `:2411`; `src/seon/repl.clj:395`;
`src/seon/bootstrap.clj:29` `[:seon.fn/sym "seon.bootstrap/help-value"]`, `:451`;
`src/seon/run.clj:100` `[?function :seon.fn/sym "seon.run/walkthrough"]` (a
Datalog clause with a string constant); `src/seon/sci/kernel.clj:175`, `:572`;
`src/seon/test/arm.clj:210` `(map #(str (symbol %)))`;
`src/seon/test/accretion.clj:80`; `src/seon/search.clj:164`, `:434`;
`src/seon/maintenance.clj` (3 mentions); `script/seon/fresh_operator.clj` (2).

### 2.14 Tests that assert string identities

303 string-literal sym values and 314 lookup refs in `test/`. Heaviest:

| file | string-literal sym values |
|---|---|
| `test/seon/fn_test.clj` | 97 (125 `:seon.fn/sym` mentions total) |
| `test/seon/program_test.clj` | 29 |
| `test/seon/test/accretion_test.clj` | 16 |
| `test/seon/cluster/turn_test.clj` | 13 |
| `test/seon/test/selection_test.clj` | 12 |
| `test/seon/sci/eval_test.clj` | 10 |
| `test/seon/turn_test.clj` | 7 |
| `test/seon/source_reconciliation_test.clj` | 7 |
| `test/seon/render/entity_pairs_test.clj` | 7 |
| `test/seon/maintenance_schema_test.clj` | 6 |
| `test/seon/fn/analyzer_test.clj` | 6 |

Named assertions of the kind the assignment calls out:
`test/seon/test_reaching_test.clj:467` `(is (= test-symbol (:seon.test/sym result)))`
and `:270`; `test/seon/test_runner_test.clj:883`, `:901`;
`test/seon/render_simplification_test.clj:401`
`{:seon.fn/sym (str (symbol (str fixture-a) (str name)))}`;
`test/seon/fn_test.clj:1970`; `test/seon/config_application_test.clj:93`.

Fixture data files also carry string identities:
`test/fixtures/call_graph_fidelity/declarations.txt` (2),
`test/seon/run4_replies.edn` (1).

---

## 3. The identity probe — settled by the live store, no drill

batch C Part 2(a) `:418-424` asks for one live probe before committing the schema
change: does `:db.type/symbol` combine with `:db.unique/identity`, does AVET order
symbols, does a lookup ref resolve?

**It does, and the proof is already installed** — `:seon.ns/name` has been a
symbol-typed identity attribute all along. Read from `default` (jvm mode,
read-only):

```clojure
:ns-name-schema        {:db/ident :seon.ns/name
                        :db/valueType :db.type/symbol
                        :db/unique :db.unique/identity
                        :db/cardinality :db.cardinality/one}
:sample-types          #{"class clojure.lang.Symbol"}
:ns-name-sample        [clj-kondo babashka.fs babashka.process]
:lookup-ref-by-symbol  9776   ; (pull db … [:seon.ns/name 'seon.turn])
:datalog-symbol-binding 9776  ; [:find ?e . :in $ ?n :where [?e :seon.ns/name ?n]] with 'seon.turn
:fn-sym-schema         {:db/valueType :db.type/string :db/unique :db.unique/identity}
:test-sym-schema       {:db/valueType :db.type/string :db/unique :db.unique/identity}
:call-arities-schema   {:db/valueType :db.type/tuple
                        :db/tupleTypes [:db.type/string :db.type/long]
                        :db/cardinality :db.cardinality/many}
```

A symbol-valued `:db.unique/identity` attribute exists, holds symbols, resolves a
lookup ref, and binds through a Datalog `:in`. **Nothing needs to be installed to
find out.** Batch C's "do not commit the schema change before that probe" is
discharged by this note.

### 3.1 Why it works, in the dependency's source

- `:db.type/symbol` is a value type: `reference-code/datahike/src/datahike/schema.cljc:31`
  (`(s/def :db.type/symbol symbol?)`), and a member of `:db.type/value` at `:48`.
- Nothing restricts `:db/unique` by value type. `::schema`
  (`schema.cljc:77-78`) takes `:db/unique` as an optional key of any attribute,
  and `datahike.db/validate-schema` (`reference-code/datahike/src/datahike/db.cljc:837`)
  only checks the VALUE of `:db/unique` against `#{:db.unique/value
  :db.unique/identity}`. The value-type restrictions at `:838` are for
  `:db/isComponent`, not for uniqueness.
- Datahike itself ships a `:db.type/symbol` system attribute:
  `:db.entity/preds` at `schema.cljc:159`.

### 3.2 Lookup refs and AVET ordering

`dbu/entid` (`reference-code/datahike/src/datahike/db/utils.cljc:109-138`) resolves
`[attr value]` by requiring only that the attribute is `:db/unique`
(`:122`) and then `(-> (dbi/datoms db :avet eid) first :e)` (`:128`). The
comparator is `datahike.datom/compare-value`
(`reference-code/datahike/src/datahike/datom.cljc:262-292`), which on the JVM is
plain `clojure.core/compare` for everything but byte arrays. `clojure.lang.Symbol`
implements `Comparable` (namespace then name), so the persistent-sorted-set order
is total and stable — and the live query above is the empirical confirmation.

One caveat the lane should know: `contextual-index-range`
(`reference-code/datahike/src/datahike/db.cljc:287-289`) raises unless the
attribute carries `:db/index true`. That is `-index-range`, not `datoms :avet`
and not lookup-ref resolution; range scans over `:seon.fn/sym` (prefix search)
would need `:seon.db/index true`, exactly as they do today with strings. Nothing
changes.

### 3.3 Tuples accept symbol members

The bridge derives `:db/tupleTypes` from the Malli children
(`src/seon/schema/datahike.clj:266-269`), mapping each through
`form->datahike-value-type-in`, whose table has `:symbol` and `:qualified-symbol`
→ `:db.type/symbol` (`:66-67`) and which infers `:db.type/symbol` from a symbol
literal (`:112`). Datahike validates `:db/tupleTypes` as a vector of value types
(`schema.cljc:167-171`, `db.cljc:812-826`) and `:db.type/symbol` is in
`:db.type/value` (`schema.cljc:48`). The installed value today is
`[:db.type/string :db.type/long]`; changing the Malli form to
`[:tuple :qualified-symbol [:int {:min 0}]]` produces
`[:db.type/symbol :db.type/long]` with no bridge change.

**batch C Part 2(b)'s recommendation stands and composes**: keep the tuple, flip
its first member. 74 281 datoms, 1 datom each — the interned alternative is still
refuted on storage.

---

## 4. The plan, staged for one astra lane

One lane, one coherent slice, path-limited commits per stage. No migration —
`bin/seon reset --force` is the boundary.

### (a) Schema forms — `resources/seon/schemas/`

Edit the 13 declarations of §1.1 and their docstrings. Keep every existing
property (`:seon.db/identity`, `:seon.program/row-schema`,
`:seon.program/source-attribute`, `:seon.search/index`) verbatim — §1.3 says the
search tag stays. Update `:seon.fn/call-arities`'s docstring
(`seon.fn.edn:41-52`) so it no longer says the member is a string. Resolve the
`:seon.render/ai` key collision (§1.1 item 13) before closing that `:or`.
Files: `seon.fn.edn`, `seon.test.edn`, `seon.instrument.edn`, `seon.error.edn`,
`seon.schema.edn`, `seon.render.edn`.

### (b) Write seams

In dependency order, because the indexer feeds everything else:

1. `src/seon/fn.clj` — delete the nine `(str …)` at `:329`, `:336`, `:342`,
   `:402`, `:600`, `:604`, `:636`, `:867`, and the `(str qualified)` keys at
   `:616-669`; the map keys built from analyzer usages become symbols throughout.
   Decide the `:clj-kondo/unknown-namespace` placeholder question (§1.6) here.
2. `src/seon/program.cljc:33`, `:814`, `:1084-1088` — the projection identity.
3. `src/seon/sci/eval.clj` — agent-admitted definitions (`:318`, `:341`, `:691`,
   `:701`).
4. `src/seon/test/runner.clj:53`, `:203` — recording.
5. `src/seon/issue.clj` — citation resolution (`:140-340`, `:686-694`) and
   `:451`, `:467`; the generated `my.test/check` form at `:588`.
6. `src/seon/effect.clj` — the ten `(str owner-sym)`.
7. `src/seon/schedule.clj:50-70` — the five portfolio literals become symbols;
   `:582` drops `(symbol function)`.
8. `src/seon/error.clj:285`, `:572` and `src/seon/instrument.clj` — the nine
   `(str function-symbol)`; dissolve `:seon.error/exception-class` into
   `:seon.error/throwable-class`.
9. `src/seon/turn.clj:86`, `:1218-1253`, `:1387` — adoption and the pending
   resolver.

### (c) Read seams and lookup refs

All 80 src lookup refs lose their `(str …)`; all 132 `(symbol …)` read-backs
disappear or shrink to `(namespace sym)` / `(name sym)`. The concentrated ones:
`src/seon/test.clj:263`, `:514`, `:521`, `:595`, `:632`;
`src/seon/test/runner.clj:227`, `:619`, `:1122`, `:2135`, `:2213`;
`src/seon/render/ns.clj:271`; `src/seon/sci/eval.clj:670`, `:823`, `:969`,
`:1276`, `:2584`, `:2642`; `src/seon/plan.clj:632`; `src/seon/cluster.clj:2297`;
`src/seon/db.clj:2489`; `src/seon/repl.clj:395`; `src/seon/search.clj:158-166`
(delete the dead string branch). Datalog constants: `src/seon/run.clj:100`.
Also update `AGENTS.md:302`'s own example, which passes a string:
`(seon.fn/tests-reaching (seon.db/db) "seon.turn/open-tx")`.

### (d) Tests

303 string literals + 314 lookup refs + 83 read-backs. Mechanical: a quoted
symbol replaces each `"ns/name"` in a sym position. Start with
`test/seon/fn_test.clj` (97) and `test/seon/program_test.clj` (29) — between them
they are 42% of the literals. Fixture data: `test/fixtures/call_graph_fidelity/declarations.txt`,
`test/seon/run4_replies.edn`.

### (e) Reset — no migration

`bin/seon reset --force` (`AGENTS.md:826`: down all, destroy, republish, refork).
What must be regenerated or re-seeded afterwards:

- the Juniper fixture data must be reseeded into `default`
  (`bin/seon init --dev default` then the fixture seed; `AGENTS.md:836-845`);
- `build/` publication manifests — anything under `build/` naming
  `current-src` identities as strings must be rebuilt by the republish, not
  patched;
- `tmp/test-basis` and any recorded green basis: the gate derives selection from
  `:seon.fn/calls` reach against a recorded basis, and a basis recorded before
  the type change is stale. Delete it and let the widening path
  (`AGENTS.md:773-776`: a missing basis widens to every eligible test) take the
  first run;
- retained `tmp/test-runs/run.*` roots holding string identities are exhaust —
  sweep only after confirming no live runner holds them (`AGENTS.md:865-870`);
- any `.edn` artifact under `docs/prds/*/research/` that carries captured program
  rows is a dated record, NOT to be regenerated.

Nothing else: database data is disposable by ruling, and the refork "is already
happening eight times a day" (batch C `:412-414`).

### (f) One regression per seam

Six, each asserting the WANTED behavior, none of them a type-only assertion on a
hand-built map:

1. **Indexing** — index a canonical fixture through `seon.fn`, then assert
   `symbol?` on every value of `:seon.fn/sym`, `:seon.test/sym`, `:seon.fn/caller`,
   `:seon.fn/callee`, `:seon.fn/pending-calls`, `:seon.test/pending-subject` in
   the resulting datoms. Fails loudly on absence, not silently on an empty set.
2. **Agent evaluation** — admit a definition through the agent path
   (`src/seon/sci/eval.clj`) and assert the committed program row's
   `:seon.fn/sym` is a symbol, i.e. the admission seam and the indexer agree.
3. **Lookup ref** — `(db/pull db [:db/id] [:seon.fn/sym 'seon.turn/open-tx])`
   resolves to the same entity `:seon.ns/name` style resolution does today.
4. **Datalog `:in`** — a query binding a symbol parameter finds the entity, and
   the same query binding the equivalent STRING finds nothing (the second half is
   what makes this a real check rather than a tautology).
5. **Tuple** — a `:seon.fn/call-arities` member transacts with a symbol callee
   and reads back as `[symbol long]`; the installed schema reports
   `:db/tupleTypes [:db.type/symbol :db.type/long]`.
6. **Citation** — an issue note citing `seon.turn/open-tx`, a file path and an
   issue slug resolves the symbol citation through `:seon.fn/sym` while leaving
   the path and slug as strings, and an unresolvable token still lands in
   `:seon.issue/unresolved` as a string.

A seventh if §1.6 is fixed rather than deferred: a program row whose name comes
from `:clj-kondo/unknown-namespace` round-trips through `pr-str`/`read-string`.

### 4.1 Collisions with running lanes — for the orchestrator

- **`src/seon/fn.clj` and `src/seon/program.cljc`** — the call-graph lane owns
  both (`af800d1a0 Derive calls from declared function values and Var quotes`,
  `aace746cb`, and the plan in
  [call-graph-sources-and-storage-2026-09-17.md](call-graph-sources-and-storage-2026-09-17.md)).
  These are the heaviest write seam here (stage (b)1-2) and the same lines.
  **Sequence this AFTER the call-graph lane lands**, or the two rewrite each
  other's edges.
- **`src/seon/issue.clj`, `test/seon/issue_test.clj`,
  `resources/seon/schemas/seon.issue.edn`** — S7 holds uncommitted edits there
  right now (`git status`: modified `src/seon/issue.clj`, `seon.issue.edn`,
  `test/seon/issue_test.clj`). Stage (b)5 must wait or be handed to S7.
- **`resources/seon/schemas/seon.turn.edn`, `seon.eval.edn`,
  `seon.cluster.eval.edn`, `seon.render.edn`, `src/seon/turn.clj`,
  `src/seon/cluster/agent.clj`, `src/seon/bootstrap.clj`, `src/seon/render.clj`**
  — all uncommitted in the working tree at this note's commit. Stage (a) touches
  `seon.render.edn` and stage (b)9 touches `src/seon/turn.clj`; confirm those
  lanes have landed before the symbol lane starts.
- No collision expected on `src/seon/effect.clj`, `src/seon/schedule.clj`,
  `src/seon/instrument.clj`, `src/seon/error.clj`, `src/seon/test/runner.clj`.

---

## 5. Issues this note creates

Two, to be filed under `docs/seon/issues/` by whoever picks this up (this lane is
read-only and did not file them):

1. **`:seon.search/index :symbol` is documented as a type mirror it is not.**
   `AGENTS.md:431-433` and batch C `:413-416` instruct a future lane to delete a
   tag that selects the search tokenizer (`src/seon/search.clj:170-174`), on an
   attribute that is a keyword (`resources/seon/schemas/seon.schema.edn:43-46`).
   Deleting it degrades identifier search silently. Severity: friction. Fix the
   claim in AGENTS.md §3 in the same commit as the schema change.
2. **`:seon.error/throwable-class` is declared `:string` and written a symbol.**
   `resources/seon/schemas/seon.error.edn:265` vs `src/seon/error.clj:285`, with
   `:seon.error/exception-class` (`:273`) as a second attribute for the same
   fact. Severity: friction; it is a live contract mismatch in a fault-committing
   path, and §2.5 duplication.

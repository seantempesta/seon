# Canon: published guidance on simple, compact, idiomatic, performant Clojure and tests

Collected 2026-10-07 for a project style guide aimed at AI coding agents.
Method: WebFetch of each URL (the fetch tool returns a model-made summary of the page, not raw text) plus
direct reading of local sources under `/Users/sean/src/seon/reference-code`.
Quotes are under 15 words and come from those summaries; where only a paraphrase was possible it is marked
"(para.)". Verification status per source is given in the "Verified" line; the final section lists everything
that could not be verified. Tags: [size] [clarity] [perf] [tests].

Contents: 1 Hickey talks; 2 Style guide; 3 clojure.org guides; 4 Sierra; 5 Elements of Clojure; 6 Normand;
7 Corfield / next.jdbc; 8 borkdude / SCI / babashka; 9 Tonsky / clj-reload; 10 Malli; 11 Performance sources;
12 Testing sources; 13 Merged rule list (36 rules); 14 Conflicts and resolutions; 15 Unverified.

---------------------------------------------------------------------------------------------------

## 1. Rich Hickey talks

Verified: Simple Made Easy, Value of Values, Spec-ulation, Maybe Not read via the matthiasn/talk-transcripts
transcripts (summaries). Effective Programs via the neonvagabond.xyz mirror (summary). Quotes are as the
summaries rendered them; I could not diff against the video.

### 1.1 Simple Made Easy (2011)
URL: https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/SimpleMadeEasy.md

Definitions: simple = one fold/braid, unentangled (objective); easy = near at hand/familiar (relative).
Concrete coding consequences:
1. Judge a design by whether parts are interleaved, not by how short or familiar it looks. "Complect is obviously bad."
2. Prefer values to objects: "Objects complect state, identity, and value." -> pass immutable data, not stateful objects.
3. Prefer functions to methods: "Methods complect function and state, ordinarily." -> top-level functions of arguments.
4. Treat information as plain data: "Information is simple. The only thing you can do is ruin it." -> do not wrap
   maps/sets in classes or accessor layers that hide the data.
5. Separate what from how: "Strictly separating what from how is the key." -> declarative data describing work,
   interpreter elsewhere; rules/data over scattered conditionals.
6. Connect components with queues/values rather than direct calls where independence matters.
7. His complex-to-simple table: objects -> values; methods -> functions; variables -> managed references;
   imperative loops -> set functions (map/filter/reduce); syntax -> data; ORM -> declarative data manipulation;
   inconsistency -> transactions plus values.
8. Optimise for the long-lived artifact (correctness, fixability, changeability), not authoring speed or familiarity.

### 1.2 The Value of Values (2012)
URL: https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/ValueOfValues.md
1. "If the string is immutable, it's a value." Values are shareable across threads and processes without locks.
2. "Values aggregate to values": a persistent collection of values is itself a value; build aggregates, not
   mutable holders.
3. A function of values is reproducible: same inputs, same answer -> tests need no mocks or ordering (para.).
4. Facts are not updated, because the past cannot change: model change as new facts/values with time, not
   update-in-place (para.). (This is the Datahike premise.)
5. Values are language-independent (list, map, number, string), so system boundaries should exchange values.

### 1.3 Effective Programs (10 years of Clojure, 2017)
URL: https://neonvagabond.xyz/mirrors/effective-programs.html
1. "Just use maps": generic, open maps instead of proliferating concrete types; extra keys pass through (para.).
2. Namespace-qualified names are first class so data from different sources merges without collisions.
3. Positional semantics do not scale; prefer named attributes and generic operations over parsing into
   per-shape types (para.).
4. Immutable values plus an explicit state-transition model give most of the concurrency answer (para.).
5. Reuse comes from a small set of generic abstractions (hundreds of functions over seq/coll/associative)
   rather than per-type libraries.
6. Programs are "situated" in a messy world; information processing dominates, so handle irregular data
   rather than prove it away (para.).

### 1.4 Spec-ulation (2016)
URL: https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/Spec_ulation.md
1. Change by accretion: provide more.
2. Relax requirements: require less (accept more, demand fewer keys).
3. Promise less / open specs: do not forbid what you do not need to forbid. "breaking changes are broken" (para.
   of the talk's thesis).
4. If semantics change, give it a new name: `foo-2` or a new namespace, never silently change `foo`.
5. A name, not a version, identifies a contract.

### 1.5 Maybe Not (2018)
URL: https://github.com/matthiasn/talk-transcripts/blob/master/Hickey_Rich/MaybeNot.md
1. A map is a function from keyset to values; absence is expressed by leaving the key out, not by nil.
   "When something is missing from a set leave it out!" (para.)
2. Optionality is context dependent: shapes/schemas should say what an attribute means; which keys a
   particular function requires belongs to the function (selection), not the aggregate schema.
3. Reuse one definition of what `:a/b` is; vary requirements per call site.
4. Requirements must be able to descend into nested maps/collections, not only the root.

---------------------------------------------------------------------------------------------------

## 2. Clojure Style Guide (Bozhidar Batsov)

URLs: https://github.com/bbatsov/clojure-style-guide and https://guide.clojure.style/
Verified: both fetched; rules quoted from summaries. Rule anchors from guide.clojure.style.
Rules that bear on size and clarity:
1. Functions: "Avoid functions longer than 10 LOC; most functions should be shorter than 5 LOC." (README)
2. More than three or four positional parameters -> take an options map. (README)
3. "Prefer higher-order functions like `map` to `loop/recur`." Declarative over hand loops. (README)
4. `#dont-write-macro-if-fn-will-do`: "Don't write a macro if a function will do."
5. `#nil-punning`: test emptiness with `seq`. Use `when` rather than single-branch `if`; use `if-let`/`when-let`.
6. `#no-superfluous-comments`: do not comment obvious code, refactor. `#_` to remove forms, not comment blocks.
7. Naming: `lisp-case`; predicates end `?`; side-effecting functions end `!`; conversion functions use `->`
   (`string->int`); do not shadow `clojure.core` names; `_` for unused bindings.
8. Keywords as map keys; vectors over lists for data; prefer pre/post conditions to in-body checks (note conflict 14.6).
9. Docstring: first line a complete capitalised sentence; docstring before the arg vector; Markdown allowed;
   positional args in backticks. (`#docstring-summary` etc.)
10. Atoms: `swap!` over `reset!`; never update atoms inside STM transactions.
11. Tests (`#test-naming`, `#use-testing-blocks`): `deftest` named `something-test`; group with `testing`;
    informative assertion messages (conflict with Seon: assertion messages prose, never printing values).
12. Line length: 80 preferred, 120 maximum.

---------------------------------------------------------------------------------------------------

## 3. clojure.org guides and references

### 3.1 Destructuring
URL: https://clojure.org/guides/destructuring (verified)
1. Use `:keys` to remove duplication: bind keyword-keyed maps by name. Namespaced keys bind to the name part.
2. `:or` supplies defaults only for absent keys; `:as` binds the whole value (needed to pass extras through).
3. Keyword args are `[x & {:keys [debug]}]`; maps as last param are preferred for new APIs (para.).
4. Any bound symbol can be destructured again; keep nesting shallow for readability (para.).
5. Clojure 1.13 adds checked bindings `:keys!` that throw when required keys are absent. "Use the checked
   versions of the binding directives" (verify availability in the pinned Clojure before citing).

### 3.2 Threading macros guide
URL: https://clojure.org/guides/threading_macros (verified)
1. `->` for data-structure functions (value first: `assoc`, `update`, `get`); `->>` for sequence functions
   (value last: `map`, `filter`, `into`).
2. `as->` only when insertion position varies; `some->` short-circuits on nil; `cond->` "never short-circuits".
3. Do not mix `->` and `->>` styles in a single pipeline without `as->`/let (para.).

### 3.3 spec rationale: maps
URL: https://clojure.org/about/spec (verified)
1. "Map specs detail required/optional keys": keys sets and key semantics are independent.
2. Only namespace-qualified keywords name specs; a key's value spec is registered under the key itself.
3. Namespaced keys are checked even where no map spec declares them: open maps compose.

### 3.4 Programming at the REPL
URL: https://clojure.org/guides/repl/introduction (verified; `/programming_at_the_repl` returned 403)
1. "Build programs first by performing small tasks manually, then gradually make them more automated."
2. The REPL makes execution "tangible": reproduce a problem, observe symptoms, improvise experiments.
3. REPL leverage comes from immutable data: functions of values can be re-run at will.
4. Keep a tight feedback loop; evaluate the smallest form that tests a hypothesis (para.).

### 3.5 Transients
URL: https://clojure.org/reference/transients (verified)
1. Use only to build a collection within one function: `transient` ... `assoc!`/`conj!` ... `persistent!`.
2. "Transients require thread isolation." Do not leak them.
3. Always use the return value of `conj!`/`assoc!`: "not designed to be bashed in-place."
4. Reported 1M-iteration benchmark: 5.5 ms vs 8.4 ms (about 35 percent faster).

### 3.6 Transducers
URL: https://clojure.org/reference/transducers (verified)
1. Transducers "compose directly, without awareness of input or creation of intermediate aggregates."
2. `into` with an xform builds with transients; `transduce` for reductions; `eduction` to defer.
3. Stateful transducers must honour `reduced`.

---------------------------------------------------------------------------------------------------

## 4. Stuart Sierra

Verified: individual posts fetched (below). I did not locate a single "Clojure Do's and Don'ts" repository;
it is a blog series: https://stuartsierra.com/tag/dos-and-donts (verified list of titles).

### 4.1 Do's and Don'ts posts
1. Single-branch if (https://stuartsierra.com/2015/06/16/clojure-donts-single-branch-if):
   "An `if` should always have both 'then' and 'else' branches. Use `when`" for the nil case. (matches Style Guide)
2. Redundant map (https://stuartsierra.com/2015/08/10/clojure-donts-redundant-map): do not write
   `process-many` that only maps `process-one`; "map a function over a collection" is universal.
3. Lazy effects (https://stuartsierra.com/2015/08/25/clojure-donts-lazy-effects): "Never mix side effects with
   lazy operations" (para.). Use `doseq`, `run!`, `reduce`; `map` for pure functions only.
4. Thread as (https://stuartsierra.com/2018/07/15/clojure-donts-thread-as): "refactor the code to use
   consistent parameter placement, or just use `let`." Only use `as->` inside a `->`.
5. How to name functions (https://stuartsierra.com/2016/01/09/how-to-name-clojure-functions):
   pure functions are nouns describing the result (`age`, not `calculate-age`); do not repeat the namespace in
   the name (`products/price`); coercions named for output; side effects are verbs.
6. Short fn syntax / numbered parameters (titles verified, bodies not read): advice is to avoid `%1 %2`
   numbered parameters and keep `#()` to single simple expressions (para. of listing).

### 4.2 Perils of dynamic scope
URL: https://stuartsierra.com/2013/03/29/perils-of-dynamic-scope (verified)
1. "Define API functions to take the resource as an argument." Avoid dynamically-scoped singleton resources.
2. Dynamic scope is for single-threaded extents only; hidden dependencies are not stated in function definitions.
3. Closures + higher-order functions are the explicit alternative.

### 4.3 Component
URL: https://github.com/stuartsierra/component (verified)
1. "Never create global mutable state (for example, an Atom or Ref stored in a def)."
2. Systems inject dependencies; "No component should be aware of the system which contains it."
3. "Each function should be defined in terms of at most one component": pass what a function needs, not the system.
4. Lifecycle (start/stop) is explicit data/protocol; this makes REPL restarts cheap.

### 4.4 Uncaught exceptions
URL: https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions (verified)
1. Install a default uncaught-exception handler; "The thread dies silently" otherwise.
2. Exceptions in futures only surface on `deref`; submit vs execute differ in where exceptions go.
(Relevant to Seon's "nothing crashes, deliver every error".)

### 4.5 How to ns
URL: https://stuartsierra.com/2016/08/27/how-to-ns (page read; rules live in a linked page I could not fetch).
Only the intent is verified: one consistent ns style, because ns accepts almost any shape.

---------------------------------------------------------------------------------------------------

## 5. Elements of Clojure (Zach Tellman, 2019)

URL: https://leanpub.com/elementsofclojure (book itself not read).
Verified: only via a reader summary, https://danlebrero.com/2020/08/12/elements-of-clojure-book-summary/
Chapter structure confirmed by search: Names, Indirection, Composition.
Rules (from summary; treat quotes as the summary's words):
1. Names are narrow: a name should "exclude things it cannot represent" and reveal sense, not implementation.
2. Names are consistent and understood in context; "all functions in a namespace should operate on a common data type".
3. Data-retrieval functions are named for the data returned; transformations avoid verbs; pushes name the effect.
4. Avoid needing names: prefer not to introduce named parameters, and do not use macros for indirection.
5. Indirection: references convey values and stay open; conditionals decide and need code change; prefer an
   unordered table with unique keys for open decisions.
6. "Abstractions that fail together should stay together": group models sharing assumptions in one layer.
7. Composition: pull from the environment, transform, push to the environment; keep them separate "until the
   last possible moment"; transform code lives in its own namespace.

---------------------------------------------------------------------------------------------------

## 6. Eric Normand (data modeling)

Verified: two pages fetched, plus a search for context. I could not fetch `ericnormand.me/guide/data-modeling` (404).
1. Data-oriented programming review, https://ericnormand.me/issues/474 : separate code from data; represent data
   with generic structures; data is immutable; separate schema from representation. Practice rule: pass along the
   whole map including unfamiliar keys rather than rebuilding maps with only known keys.
2. "Build an interface around data", https://ericnormand.me/build-an-interface-around-data/ : "Build a smaller
   interface instead of saying the data is its own interface." Put structure knowledge in one namespace so a
   change in shape touches one place; avoid scattering `update-in`/`get-in` paths (conflicts with Hickey/Seon
   "no accessor layers", see 14.3).
3. Grokking Simplicity (search-level only): split actions, calculations, data; reads of immutable data are
   calculations; stratified design hides `get-in` paths behind domain-level functions.
Seon-relevant reading: Normand's accessor namespace is justified when a nested path is reused in several places
or likely to change; otherwise it is the indirection Hickey and Tellman warn about.

---------------------------------------------------------------------------------------------------

## 7. Sean Corfield / next.jdbc API design

Verified: three pages from the repo docs fetched.
URLs: https://github.com/seancorfield/next-jdbc/blob/develop/doc/friendly-sql-functions.md ;
.../doc/getting-started.md ; .../doc/differences-from-clojure-java-jdbc.md (the last redirected content was the
"differences" section).
1. Small surface: "deliberately simple and intended to cover only the most common, basic SQL operations."
   Everything else goes through three primary functions (`plan`, `execute!`, `execute-one!`).
2. Plain data in, plain data out: rows are hash maps with namespace-qualified keys (`table/column`) "to improve
   performance and not mess with the data."
3. No lazy result sets: "reduce the result set eagerly (via `plan`) or get a fully-realized" structure.
   `plan` returns a reducible so callers can `reduce`/transduce without materialising rows.
4. Explicit over implicit: only `with-transaction` creates a transaction; nested calls are the caller's decision.
5. Consistent return shapes: one map, or a vector of maps; update counts also look like result sets.
6. Compose rather than embed: SQL building is delegated to HoneySQL etc.; configuration through options maps
   (`:table-fn`, `:column-fn`), not new functions.

---------------------------------------------------------------------------------------------------

## 8. borkdude / SCI / babashka performance

Verified: local sources only. I could not retrieve a borkdude blog post on SCI performance (web search returned
only Slack logs).
Local sources (all in `/Users/sean/src/seon/reference-code/sci`):
1. `sci/README.md` "fork" section (lines ~560-585): "for performance reasons it may be desirable to initialize a
   shared context once", then fork per user: `(sci/fork sci-ctx)`. Share the base, copy cheaply on write.
2. `sci/doc/ai/adr/0010-analysis-performance/README.md` (marked AI-generated prose in `doc/ai/README.md`, read
   accordingly): loading is "parse+analysis-bound, not eval-bound" and measured by profile first
   (clj-async-profiler, `{:event :alloc}`); reported fixes: declare record fields instead of extmap keys
   (assoc with a non-field key copies the record and rebuilds the extmap); cache option maps keyed on identity
   of what they depend on; de-lazify hot helpers (`take-while`/`map` chains in the analyzer); an earlier
   "38% allocation site" reading was a mis-attribution, i.e. verify profiler attribution.
3. `sci/doc/ai/adr/0009-call-node-specialization/README.md`: specialises calls to known core fns to static host
   calls; reports cumulative wins (2.1x on a count/get loop) and states run-to-run noise (plus or minus 5-7%)
   and JIT profile pollution (0.88 ms in suite vs 0.55 ms isolated) and re-verifies in isolation.
4. Babashka README: startup time is the product goal; interpreted code is slower on hot loops (CLJS section:
   about 175 ms interpreted vs 7 ms JIT on a 10M-iteration loop), so hot loops belong in compiled code.
Checkable rules: (a) measure with an alloc and CPU profile before changing; (b) isolate benchmark runs and quote
noise; (c) one shared base context, fork per consumer; (d) use records with declared fields on hot paths.

---------------------------------------------------------------------------------------------------

## 9. Tonsky / clj-reload

URL: https://tonsky.me/blog/clj-reload/ (verified) and local `/Users/sean/src/seon/reference-code/clj-reload/README.md`.
1. Reload only what changed plus dependents, in topological order: cost follows the change, not the program.
2. "defonce works out of the box": keep state in `defonce` (or `^:clj-reload/keep` per var) rather than
   restructuring namespaces so that state is reload-proof (README: tools.namespace forces "persistent state and
   protocols into separate namespaces, not because logic dictates it").
3. Stateful namespaces provide a `before-ns-unload` hook for shutdown; `:no-reload` is config at init, not
   code in business namespaces.
4. When a reload fails, fix and call `reload` again; `unload` gives a clean slate (README "recovering from errors").
5. Pairs with Component/Integrant/mount: stop everything before reload, start after; or put non-restart-worthy
   things (a web server) in `defonce`.

---------------------------------------------------------------------------------------------------

## 10. Malli design notes (local)

Source: `/Users/sean/src/seon/reference-code/malli/README.md` (line numbers from the pinned copy).
1. Motivation (line 55): data models are first class, serialisable, editable at runtime, persistable. "Schemas
   are data."
2. Why multiple syntaxes (line 213): vector syntax is easy to read; map syntax added because "overhead of parsing
   large amount of vector-syntaxes" matters; lite syntax for convenience. Rule: choose representation by who
   consumes it; do not parse eagerly what is not used.
3. Compile once, call many (Performance, line ~3600): `m/validator`, `m/decoder`, `m/parser` return functions;
   report: validator 39 ns vs idiomatic 54 ns vs spec 450 ns; decoder with nothing to transform returns
   `identity` (5 ns). Rule: build the checker once at definition time, never per call.
4. Transform only the parts of the schema that need it; if nothing needs transforming return `identity`.
5. Mutable registries (line 3371) are "a dev-time abstraction": caches mean mutations are not seen unless
   schemas are re-created; do not rely on mutating a registry to update derived schemas (directly relevant to
   Seon's content-keyed cache rule).
6. Store schema properties as data (`:string/trim true` property read at compile time) so one generic
   transformer reads the schema rather than per-type branches (`docs/tips.md`).
7. `docs/tips.md`: `m/walk` for schema-wide rewrites instead of hand recursion.

---------------------------------------------------------------------------------------------------

## 11. Performance sources

### 11.1 Clojure Goes Fast
- Reflection: https://clojure-goes-fast.com/blog/performance-nemesis-reflection/ (verified)
  1. Enable `(set! *warn-on-reflection* true)`; warnings give file and line.
  2. Hint where reflection costs measurably; the compiler infers many types; hint only what warnings name.
  3. "Don't obsess over having zero reflection cases." Weigh the cost against added code.
  4. Reported example: minimal hints removed a 1500x slowdown.
- Laziness: https://clojure-goes-fast.com/blog/clojures-deadly-sin/ (verified via summary)
  1. "Prefer v-suffixed functions (mapv, filterv) over lazy counterparts."
  2. Use `(into [] xform coll)` for multi-step pipelines; "Don't return lazy sequences in your public functions."
  3. Reported benchmark, 1000 items, 5 steps: lazy 410 us / 480 KB; mapv 63 us / 28 KB; transducer+into 43 us / 6 KB.
  4. Laziness only for infinite sequences, huge linear files, or genuinely partial consumption.
- Benchmarking: https://clojure-goes-fast.com/blog/benchmarking-tool-criterium/ (verified)
  1. Warm up; use `quick-bench` normally; `time` is not a benchmark.
  2. Force laziness: `(map ...)` unforced measured 9.98 ns vs 102 us with `doall`; `doall` forces only the top level.
- Profiling: https://clojure-goes-fast.com/kb/ (index verified; articles on clj-async-profiler: basic usage, flamegraphs,
  differential flamegraphs, allocation profiling) -- rule: profile (CPU and allocation) before ranking work.
- Boxed math article URL I guessed returned 404; not cited.

### 11.2 Naked Performance (Tommi Reiman, ClojuTRE 2019)
URL: https://www.slideshare.net/metosin/naked-performance-with-clojure (verified via summary; slides only, I did
not watch the talk).
1. Knuth: forget small efficiencies about 97 percent of the time; do not pass up the critical 3 percent.
2. "Measure and read the source": cost knowledge comes from benchmarking and reading library code.
3. The areas to watch: reflection, boxed math, dynamic vars, and abstraction cost.
4. Reported numbers: hash-map lookup about 17 ns, array-map 7 ns, mutable HashMap 5 ns, record field 3 ns;
   `merge` cost grows linearly with argument count (about 200 ns per extra map).
5. Algorithm choice dominates: lookup router 13 ns vs trie router 460 ns on 1001 routes; data-driven compiled
   router 126 ns vs nested compojure 7.2 us.
6. Data plus a compiler (build the fast function once from a data description) beat interpretation per call
   (Malli transform 500 ns vs spec-tools 95 us as reported).
7. Use Java-backed implementations behind a Clojure API where the 3 percent is (jsonista).

### 11.3 Practicalli summary
URL: https://practical.li/clojure/reference/performance/ (verified; secondary): eliminate reflection in hot code;
primitive locals do overflow-checked primitive math; prefer `reduce` for accumulation; remove hints that do not help.

### 11.4 Local corroboration
`/Users/sean/src/seon/reference-code/clojure/changes.md` mentions that unhinted Java method values "incur
incidental reflection or boxing", supporting qualified-method `String/.length` / param-tags style over hints
(lines ~111-129; pinned release notes, not a tutorial).

---------------------------------------------------------------------------------------------------

## 12. Testing sources

Verified: clojure.test API page, test.check intro, Cognitect spec screencast blog (thin), Style Guide testing section.
1. clojure.test API, https://clojure.github.io/clojure/clojure.test-api.html
   - "Test functions may call other tests, so tests may be composed."
   - `are` expands to multiple `is`; the docs warn "This breaks some reporting features, such as line numbers."
   - Fixtures: `:each` per test vs `:once` per namespace; fixtures and `test-ns-hook` are mutually exclusive.
   - `(is (thrown? C body))` / `thrown-with-msg?` for exceptions.
2. test.check, https://github.com/clojure/test.check/blob/master/doc/intro.md
   - A property is a universal claim ("reversing twice is identity"); shrinking finds a minimal counterexample.
   - Run at least about 100 trials; generators grow with `size` so outputs stay bounded; compose with
     `fmap`, `bind`, `such-that` (filter sparingly), `recursive-gen`; `defspec` hooks into clojure.test.
3. Halloway (https://cognitect.com/blog/2016/7/26/clojure-spec-screencast-testing, thin summary): "Specs can
   generate random samples of the data they specify"; `check` runs a function against its spec, `instrument`
   verifies calls. Test.generative framing (search result): generate inputs, invoke, validate, and keep outputs as
   data for study. I could not retrieve primary Halloway text beyond that.
4. Style Guide: `deftest` named `something-test`; `testing` blocks for context.
5. Hickey (Value of Values): a function of values is reproducible, so tests need only construct input values.
6. Sierra (dynamic scope, Component): pass resources explicitly, so tests inject their own without global state.
7. "Test behavior not implementation" has no single canonical Clojure source I could cite; closest published
   statements are Hickey's value argument and Halloway's generate-and-validate model. Mark as principle, not quote.

---------------------------------------------------------------------------------------------------

## 13. Merged rule list (36 rules)

Format: number, tags, rule, sources (section numbers above). Rules are phrased for a style guide; they are
inputs for the project's own edits and do not themselves assert project policy.

### Data and design
1. [clarity][size] Model information as plain immutable maps, vectors and sets; do not wrap them in classes,
   records or accessors without a stated need. (1.1, 1.3, 6.1)
2. [clarity] Use fully namespace-qualified keys for domain attributes, so data from different producers merges
   without collision; define the meaning of a key once. (1.3, 3.3, 7)
3. [clarity] Absent means no key; never store nil to mean unknown. (1.5)
4. [clarity] Keep schema (what an attribute is) separate from selection (what this function requires); put
   requirements at the function boundary. (1.5, 3.3, 6.1)
5. [clarity] Pass the whole input map through and add to it; do not rebuild maps from known keys, so unknown keys
   survive. (6.1, 1.3)
6. [clarity] Grow by accretion: add keys and arities; relax inputs, keep outputs; give changed semantics a new
   name instead of editing the old one. (1.4)
7. [clarity][size] Prefer functions of values (arguments in, data out) over methods, stateful objects and
   hidden context; pass resources explicitly. (1.1, 1.2, 4.2, 4.3)
8. [clarity] Separate pull, transform and push: a pure transform in the middle, effects at the edges. (5.7, 6.3)
9. [clarity] Say what, not how: express work as data a small interpreter runs rather than nested conditionals. (1.1, 5.5)
10. [clarity] No global mutable state in a `def`; if state is needed give it one owner, passed explicitly and
    started/stopped by an explicit lifecycle. (4.3, 9)

### Size and idiom
11. [size][clarity] Write small functions: about 5 lines typical, 10 maximum, one role each. (2.1)
12. [size] Limit positional parameters to three or four; beyond that take one options map. (2.2)
13. [size][clarity] Prefer `map`/`filter`/`reduce`/`into`/`update` to hand loops; do not write a batch
    function that only maps a single-item function. (2.3, 4.1.2)
14. [size] Do not write a macro when a function will do; macros are sugar over a function. (2.4, 5.4)
15. [clarity] Use `when` for a one-armed condition, `if` only with both branches; `seq` to test non-empty;
    `if-let`/`when-let` for bind-and-test. (2.5, 4.1.1)
16. [clarity] Use `->` for data-structure functions and `->>` for sequence functions; `as->` only inside a
    thread as an exception, otherwise `let`. (3.2, 4.1.4)
17. [clarity] Destructure map arguments with `:keys` (and `:as` when extras pass through); keep nesting shallow;
    `:or` only supplies defaults for absent keys. (3.1)
18. [clarity] Name pure functions as nouns for their result, without repeating the namespace; side-effecting
    functions as verbs ending `!`; predicates `?`; conversions `a->b`. (2.7, 4.1.5, 5.3)
19. [clarity] Names are narrow and context-sized: say what the thing is, not how it is built; do not shadow
    `clojure.core`. (5.1, 5.2, 2.7)
20. [size][clarity] Do not comment the obvious; refactor or rename. Docstrings: capitalised one-sentence first
    line, before the arg vector. (2.6, 2.9)
21. [size] Do not add indirection (wrapper namespace, accessor, protocol) unless two real callers need it;
    an accessor namespace for a repeated, changeable nested path is the exception. (1.1, 5.4, 6.2, 14.3)

### Performance
22. [perf] Choose the algorithm and the amount of work first (lookup vs scan, what is shown vs what might be);
    the gains there dwarf micro-tuning. (11.2.5)
23. [perf] Measure before optimising: profile CPU and allocations, benchmark with warm-up, force laziness,
    quote noise, isolate runs. (8.2, 8.3, 11.1, 11.2.2)
24. [perf] Default to eager: `mapv`/`filterv`/`into` with a transducer; do not return lazy sequences from public
    functions; never run side effects in lazy `map`. (11.1, 4.1.3)
25. [perf] Build per-call-hot structures with transients inside one function and return `persistent!`; keep
    the transient local and use the return value. (3.5)
26. [perf] Reduce over a stream or result source instead of materialising it when the caller folds. (3.6, 7.3)
27. [perf] Turn on `*warn-on-reflection*` in compiled code; fix warnings on hot paths with a hint at the one
    site; do not hint everywhere. (11.1)
28. [perf] Do expensive construction once (compile a validator, a router, a transformer into a function) and
    call the compiled function many times; return `identity` when there is nothing to do. (10.3, 10.4, 11.2.6)
29. [perf] Keep hot loops in compiled code; interpreted evaluation pays a large constant factor. (8.4)
30. [perf][size] Derived values keyed by content (or by what they read) are safe to cache; mutable registries
    and per-call recomputation are the failure modes. (10.5)
31. [perf] Cost follows the change: reload or recompute only what changed and its dependents. (9.1)

### Tests
32. [tests] Test behaviour through the public function with plain data in and plain data out; tests construct
    values, not mocks. (1.2, 12.5, 12.7)
33. [tests] One behaviour per `deftest`, named `thing-test`; group related assertions with `testing`. (2.11, 12.4)
34. [tests] Prefer a property (`test.check`, bounded generators, shrinking, at least 100 trials) for any
    function with an algebraic law (idempotence, round trip, invariants) and a few examples for specific
    edge cases found. (12.2, 12.3)
35. [tests] Use `are` only for a small table of same-shaped cases and accept the line-number loss; otherwise
    write separate `is` forms in `testing` blocks. (12.1)
36. [tests] Tests receive resources as arguments or fixtures (`:once` for expensive setup, `:each` for isolation);
    no global state or redefinition of Vars. (12.1, 4.2, 4.3)

---------------------------------------------------------------------------------------------------

## 14. Conflicts and how sources resolve them

14.1 Function length: Style Guide says 10 lines maximum, 5 typical; Seon's own rule is "split long functions by
responsibility". Hickey does not count lines. Resolution: treat the number as a smell threshold; split on role
(Hickey, Tellman), not on count.

14.2 Higher-order functions vs loops vs speed: Style Guide says prefer `map` to `loop/recur` (2.3); CGF and
Naked Performance say eager reduce/transducers beat lazy `map` chains by roughly 10x; SCI ADR 0010 de-lazifies
hot analyzer helpers. Resolution: still use HOFs; choose the eager one (`mapv`, `into` with xform, `reduce`).
Drop to `loop/recur` only when a profile shows a hot path, and say so beside it.

14.3 Accessors vs plain maps: Normand wants "a smaller interface" around entities and no scattered `update-in`;
Hickey ("Information is simple. The only thing you can do is ruin it") and Tellman (do not add indirection
without need) want the map itself as the interface. Resolution: default to the map and namespaced keys; add a
named function only for a nested path used by several callers or likely to change (rule 21). Never a record or
protocol layer merely to hide the shape.

14.4 Optional keys in schemas: Hickey (Maybe Not) says aggregates should not declare optionality; Malli and
spec still have `:optional`/`:opt` in map schemas. Resolution: schema says what a key means, `:optional` marks
"may appear"; per-function requirements go in the function's contract (selection). Pragmatically both libraries
support it; use it for presence, not for nil.

14.5 Type hints: CGF says "don't obsess over zero reflection"; Practicalli says remove hints that do not help;
Reiman lists reflection as a top cost. Resolution: warn on reflection everywhere, fix only warnings on code that
runs often, with the smallest hint; measure.

14.6 Pre/post conditions: Style Guide prefers `:pre`/`:post`; Malli/Seon prefer declared schemas validated at
the boundary, and `:pre` can be elided by `*assert*`. Resolution: for a project that has contracts, the contract
is the single place; `:pre` is the fallback for libraries without a schema layer.

14.7 `as->`: clojure.org presents it as a normal tool; Sierra says only inside `->` and otherwise `let`.
Resolution: Sierra's narrower rule is safer for agents (rule 16).

14.8 Docstrings: Style Guide wants Markdown docstrings (consistent with Seon "documentation is data"); it
also says docstring first line must be a complete sentence. No conflict, but Seon adds fixed headings.

14.9 Test assertion messages: Style Guide `#informative-assertion-messages` encourages messages that say
expected vs actual; Seon forbids printing values in messages. Resolution: messages describe the behaviour
checked in prose (what should hold); the runner prints the actual value.

14.10 `are`: clojure.test documents `are` and warns it loses line numbers; the guide has no ruling. Seon
tests are data-driven; resolution in rule 35.

14.11 Atoms: Component says never an atom in a `def`; Style Guide only says prefer `swap!`; Seon treats atoms as
a smell. Resolution: stricter rule applies (rule 10).

14.12 Transients vs persistent: clojure.org shows about 35 percent gain on a 1M loop; Reiman and the SCI ADRs
show allocation and algorithm choices dominate. Resolution: transients only after measuring; `into` already uses
them (3.6).

---------------------------------------------------------------------------------------------------

## 15. What I could not verify

- Hickey talks: I read transcript summaries through a summarising fetch, not the raw transcript or video.
  Quotes marked "(para.)" are paraphrases. The Effective Programs quote lines are the summary's words.
  An earlier transcript URL for Effective Programs (matthiasn path) returned 404; the mirror was used.
- Clojure Style Guide: README and guide.clojure.style were each summarised; section anchors are from the
  second. The "10 LOC" figure is from the README; I did not confirm it still appears on guide.clojure.style.
- Clojure.org "Programming at the REPL" (the guide the request named) returned 403; I used
  `/guides/repl/introduction` instead. `:keys!` (checked destructuring, Clojure 1.13) is from the destructuring
  page summary; I did not check the pinned Clojure source at `reference-code/clojure` for it.
- Stuart Sierra: no "Do's and Don'ts" repository located. Only the posts listed in 4.1 were read. "Short fn
  syntax" and "Numbered parameters" bodies were not read. `how-to-ns` rules are on a linked page I did not read.
- Elements of Clojure: the book was not read; everything in section 5 is from a third-party summary.
- Eric Normand: the data-modeling guide URL 404'd; "Grokking Simplicity" content is from search snippets.
  His own writing on "data modeling" was not obtained in full.
- Sean Corfield: next.jdbc docs read; no separate Corfield essay on style found (dot-clojure README had none;
  one URL 404'd).
- borkdude: no blog post on SCI or babashka performance retrieved; section 8 relies on local README/ADRs.
  The ADRs are declared AI-generated by `sci/doc/ai/README.md` and are unreviewed claims.
- Tommi Reiman, Naked Performance: slides summary only; benchmark numbers are as reported by the fetch tool and
  not reproduced; the talk video was not watched.
- Clojure Goes Fast: reflection, laziness and criterium pages read; the boxed-math article URL guessed was a
  404 and no primitive-math page was read. I did not verify the 410 us / 63 us / 43 us benchmark table
  against the page text beyond the summary.
- Tonsky: the blog post was summarised; the README in `reference-code/clj-reload` was read directly for
  headings only (grep), not end to end.
- Malli: local README read at the cited line ranges only; `docs/function-schemas.md` and `docs/value-transformation.md`
  were not read. The numbers (39 ns, 450 ns) are as printed in README, not re-measured here.
- Halloway: only the thin Cognitect blog summary and search snippets. I did not find his primary "generative
  testing" talk text, and "test behavior not implementation" has no cited primary Clojure source.
- No JVM, test or `bin/seon` was run, and no project file was edited; this report is the only file written.

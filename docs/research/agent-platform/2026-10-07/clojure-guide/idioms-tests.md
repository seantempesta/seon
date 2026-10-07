# Fewer, better tests: how good Clojure libraries test, and what Seon's test tree protects

Research note, 2026-10-07. Read-only survey: no JVM, no test run. Inputs are
`reference-code/*/test` at their pinned checkouts and `test/` at `a8e3bde5b`.
Measurement scripts were throwaway Python over the source text (heuristics stated
where used). Owner goal: "get rid of useless tests, not add more."

## 0. The numbers, corrected

| Tree | Lines | `deftest` | `is` | `are` | lines / `is` (tree) | median test length | median lines before first assertion | median lines per `is` inside a test |
|---|---|---|---|---|---|---|---|---|
| **seon/test** | 97,022 | 2,365-2,430 | 11,987 | **0** | 8.1 | 24 | **12** | 6.0 |
| malli | 12,720 | 247 | 2,254 | 37 | 5.6 | 20 | 2 | 5.0 |
| sci | 9,333 | 510 | 1,698 | 4 | 5.5 | 7 | 1 | 3.1 |
| edamame | 964 | 52 | 216 | 0 | 4.5 | 9 | 1 | 3.0 |
| clj-reload | 935 | 48 | 237 | 0 | 3.9 | 11 | 4 | 4.0 |
| konserve | 3,978 | 134 | 433 | 8 | 9.2 | 12 | 8 | 5.5 |
| datahike | 35,409 | 958 | 3,841 | 61 | 9.2 | 22 | 8 | 7.6 |
| rewrite-clj | 4,129 | 159 | 748 | 0 | 5.5 | 16 | 8 | 5.7 |
| editscript | 858 | 20 | 111 | 7 | 7.7 | 14 | 4 | 4.1 |
| kaocha | 3,226 | 187 | 455 | 1 | 7.1 | 12 | 1 | 5.6 |
| clojure | 17,522 | 743 | 2,532 | **378** | 6.9 | 9 | 2 | 3.5 |

Corrections to the brief:
- "33 lines per assertion" is 33 lines per **deftest** (97k / 2.9k). Per `is` Seon is
  8.1 lines, not far from datahike (9.2). The `is` forms are not the waste; the
  **tests, the setup around them and the helper code** are.
- Seon is the only tree with **zero `are` tables** and the largest pre-assertion setup
  (median 12 lines, mean 14.9; libraries 1-8).
- 25,272 lines (26%) of `test/` are outside any `deftest`: ns forms, 1,580 top-level
  `def`/`defn` helpers (18,377 lines in `*_test.clj` files alone) and
  `test/seon/test_support.clj` (1,164 lines). Malli's core_test has none of that weight:
  3,711 lines, 54 deftests, no fixture namespace.
- 6,172 comment lines and 2,792 assertion-message strings: the tests carry prose that
  belongs to the owning spec or docstring (malli 118 comment lines; sci 214).
- Bounds: 152 tests (6%) declare `:seon.test/long`; 149 tests use `with-redefs`
  (180 sites in 66 files) and 24 `alter-var-root`, which CLAUDE.md "Run tests as agents
  run" forbids. Malli: 0 `with-redefs`, 0 private-Var references; sci: 0 private-Var
  references. Seon: 44 private-Var references in 16 files.
- Churn: since 2026-09-01, `test/` gained 131,428 lines and lost 91,861. Tests are being
  written faster than behavior changes; most are per-slice proof that outlives its slice.

## 1. How the good libraries test

### 1.1 One call in, one value out, compared whole

The dominant shape everywhere: call the public function on a literal, `=` against a
literal. No world, no helper.

- edamame `test/edamame/core_test.cljc:28-30`:
  `(is (= "foo" (e/parse-string "\"foo\"")))` / `(is (= 'foo (e/parse-string "foo")))`.
- sci `test/sci/core_test.cljc:50-60`: `(is (= 10 (eval* "(if true 10 20)")))` - the
  evaluator is tested through its entrance (`eval-string`), never its analyzer internals.
- clj-reload `test/clj_reload/core_test.clj:46-58`: one table of
  `(is (= '["Unloading" a b "Loading" b a] (modify opts 'b)))` covers the whole
  dependency-order behavior: twelve assertions, twelve lines, one helper (`modify`).

### 1.2 `are` tables for variations

Variation is data, not another deftest.

- datahike `test/datahike/test/lookup_refs_test.cljc:10-25`: one small `db-with`, then
  two tables: four lookups that succeed or return nil, three that throw with a regex.
  Setup is 4 lines for 7 cases.
- clojure `test/clojure/test_clojure/string.clj:49-58` (`s/join` table) and `:91`
  (`nil-handling`: `(are [f args] (thrown? NullPointerException (apply f args)) ...)`):
  one row per function for the shared failure class.
- malli `test/malli/core_test.cljc:382-389`: decode table, one row per branch with a
  trailing comment naming which branch matched; `:1655-1672` checks validate, explain,
  parse and unparse **together** per row, so one row is one round-trip law.

### 1.3 Generative tests where a law exists

Few, and only for algebraic laws (round trip, idempotence, agreement of two paths).

- editscript `test/editscript/core_test.cljc:196-205`: 2,000 trials of
  `(= b (patch a (diff a b)))` over `gen/recursive-gen`. One property replaces hundreds
  of example tests.
- malli `test/malli/generator_test.cljc:264-266`: `(defspec cat-test 100 (for-all
  [[s coll] ...] (m/validate s coll)))` - the generator and the validator agree.
- malli `:16`: `shrink` helper with `{:seed 0}` for deterministic shrink assertions.
- Counts: malli 2 files, datahike 2 (`diff_buf_generative.clj`,
  `lru_weighted_property_test.cljc`), rewrite-clj 3, editscript 1, clojure 11.
  Generative tests are a scalpel for laws, not a coverage strategy.

### 1.4 Fixtures: none, or one line

- malli: 0 `use-fixtures`. sci: 1 (`jit_test.cljs:14`, restores a flag). edamame: 0.
- clj-reload `core_test.clj:13-18`: `wrap-test` binds a fixture directory and resets,
  4 lines; its whole `test_util.clj` is 111 lines.
- datahike `test/datahike/test/committed_report_test.clj:7-11`: `:each` fixture clears
  one global and restores in `finally`; tests start asserting on line 2.
- sci's entire test support is `test/sci/test_utils.cljc` (57 lines), mostly the
  native-binary switch.
- konserve's backends share one **compliance suite** in `src/konserve/compliance_test.cljc:14`
  (`(defn compliance-test [store] ...)`); each backend test is three lines calling it
  (`test/konserve/memory_test.cljc:22`). One behavior spec, many worlds.

### 1.5 Error cases: assert the data, once per failure class

- malli `test/malli/error_test.cljc:105-121`: `humanize` is product output, so its
  strings are asserted; everything else asserts `m/explain` **data**
  (`:path :in :schema :value :type`, `core_test.cljc:1659-1668`).
- edamame `core_test.cljc:21-26`: `thrown-with-data?` asserts message regex plus
  `{:row 1 :col 1}` - the data the caller uses.
- sci `test/sci/error_test.cljc:9-24`: one regression for a located-frame bug asserts
  the frame **data** (`{:name 'f :line 2 :column 3}`), not the printed stack.
- clojure `string.clj:91`: one `are` row per function for the nil class.

### 1.6 What they choose not to test

- **No private functions.** malli and sci: zero `#'ns/private` references. Internals are
  reached only through the public entrance.
- **No printed text unless printing is the product** (malli `humanize`, sci `pprint_test`).
- **No schema/contract census.** Malli does not test that its own function schemas
  compile; the compile happens on load and in use.
- **No test-of-the-test-runner** except kaocha, whose runner **is** the product
  (`kaocha/test/features`, `test/unit/kaocha/*`).
- **No performance timing in unit tests** (datahike keeps benches in `benchmark/`).
- **No regression per bug when a table row suffices:** the fix adds a row
  (malli `:382-389` rows are commented "first (fallback)", "no match").

### 1.7 Setup size

Median lines before the first assertion: sci 1, kaocha 1, edamame 1, malli 2, clojure 2,
clj-reload 4, editscript 4, datahike/konserve/rewrite-clj 8. A database library
(datahike) builds its world in one `d/db-with (db/empty-db schema) [entities]` form.

## 2. What Seon's tests protect

### 2.1 Sampling

All `(deftest ...)`/`(defspec ...)` top-level forms were extracted from `test/**/*.clj[c]`
(2,365 forms, 71,750 lines; `deftest` text matches 2,430 because some are nested in
reader conditionals/comments). 180 were drawn uniformly at random (Python
`random.seed(7)`), sorted by file, and classified by name plus body; 34 bodies were read
in full. Classification is one primary category per test. Proportions are of the
sample's 5,185 lines; tree-wide extrapolation is ×13.8 (71,750 / 5,185).

### 2.2 Categories, counts and examples

| Cat | What it protects | Tests (of 180) | Lines | Tree estimate (deftest lines) |
|---|---|---|---|---|
| (a) | behavior an agent or user relies on | 86 (48%) | 2,730 (53%) | ~38k |
| (b) | internal shape/plumbing headed for rewrite | 41 (23%) | 1,215 (23%) | ~16.8k |
| (c) | printed text / incidental structure (primary purpose) | 8 (4%) | 138 (3%) | ~1.9k (+ text assertions inside (a), below) |
| (d) | regression-per-bug duplicating a contract or error union | 17 (9%) | 457 (9%) | ~6.3k |
| (e) | test machinery (runner, reach, recording, support) | 20 (11%) | 439 (8%) | ~6k (file-group count: 158 tests, 4,704 lines) |
| (f) | docstring/example execution | 1 (<1%) | 12 | ~0.6k |
| (g) | other: census/lint-in-test, presence checks | 7 (4%) | 194 (4%) | ~2.7k |

Cross-cutting text dependence (whole tree, regex over bodies): **314 tests (13,037 lines)
assert with `str/includes?`/`re-find`/`starts-with?`**; in 96 of them (2,870 lines) text
checks are at least half the assertions. 705 `str/includes?` sites in 121 files.

#### (a) behavior relied on - keep, but shrink

- `test/seon/db_test.clj:2595` `shown-value-diff-round-trips-changed-paths` (10 lines):
  library-grade; a 4-row loop that should be an `are`.
- `test/seon/repl_grammar_test.clj:24` property: diff/apply-diff round trip, EDN round
  trip, determinism (`assert-check!`, seed). Exactly editscript's pattern.
- `test/seon/render/floor_test.clj:85` `bounded-text-stops-at-its-limit` (5 lines).
- `test/seon/render/hiccup_test.clj:76` `an-attribute-map-is-not-a-child` (7 lines, two
  `is`, one sentence of why).
- `test/seon/cluster/turn_test.clj:887` evaluation follows the reader's parse-time ns;
  `:2061` a turn delivers what a form asks to send and still finishes.
- `test/seon/call_preparation_test.clj:999` a function made in one context uses the
  calling evaluation's world (real class, but 30 lines: two nested `with-database`,
  `sci/fork`, `env/carry` to reach two `is`).
- `test/seon/db_test.clj:1528` a throwing Datahike listener cannot strand a committed
  write (72 lines).
- `test/seon/cluster/agent_test.clj:893` disarm interrupts an in-flight turn: 80 lines,
  a 12 s bound, **`with-redefs` over `prompt/prompt`**, two latches - real behavior,
  library-incompatible construction.

#### (b) internal plumbing - delete with the mechanism, or now

- `test/seon/db_test.clj:2558` reads private `#'db/write-entity-schemas` and asserts its
  three map entries.
- `test/seon/cluster/turn_test.clj:517` drives two agent turns via `with-redefs
  ai/complete` to assert Datahike cache `:attribute-revisions` did not move - a perf
  property of the cache, asserted through private cache-context keys.
- `test/seon/fn_test.clj:2319` Datalog join from a function to its `relative-root`.
- `test/seon/sci/admit_test.clj:141` the admitted print-node equals the EDN read of
  itself under six `*print-*` bindings.
- `test/seon/problems_test.clj:191` a schema entity declares **no** render pair (19
  lines of comment history for 3 `is`).
- `test/seon/config_test.clj:232` writer submits only the fact difference;
  `test/seon/cluster/publication_report_test.clj:9`; `test/seon/flow_test.clj:63`
  graph construction joins taps before resume; `test/seon/render_simplification_test.clj:904`;
  `test/seon/render_source_test.clj:210`.
- `test/my/background_test.clj:33` `background-macro-expands-one-direct-call` asserts a
  macroexpansion shape.

#### (c) printed text / incidental structure

- `test/seon/render/transcript_test.clj:701` transacts a hand-built turn + evaluation
  (with `:seon.turn/opened-tx "datomic.tx"`) to assert `str/includes?` on the AI text.
- `test/seon/error_result_test.clj:88` builds `"#object[clojure.lang.Atom 0x..."` by
  hand and compares stored text.
- `test/seon/render/value_test.clj:1253` asserts `(str/starts-with? reply
  "#:seon.repl{")`, a `drop 32` dig string, and counts a regex in `(str diagnostics)`.
- `test/seon/supplied_documentation_test.clj:59`, `test/seon/dev/mcp_bridge_test.clj:248`,
  `test/seon/render/ns_test.clj:200`, `test/seon/render/transcript_test.clj:681`.

#### (d) regression-per-bug duplicating a contract

These assert that a refusal exists and has a shape the producer's Malli output union
already declares and the armed wrapper already enforces.

- `test/seon/fn/seed_admission_test.clj:95` refuses each broken entry by name;
  `test/seon/fn_test.clj:284` capability indexing refuses every malformed declaration
  (74 lines).
- `test/seon/db_test.clj:2641` pull-budget refusal is an explicit named error;
  `:2462` a refused declaration read refuses decoding.
- `test/seon/error/refusal_test.clj:33,173`, `test/seon/error_test.clj:265,653`,
  `test/seon/env_test.clj:276`, `test/seon/reconcile_test.clj:101`,
  `test/seon/schema/admission_gate_test.clj:69`,
  `test/seon/schema/predicate_metadata_refusal_test.clj:57`,
  `test/seon/reset_edges_test.clj:72` ("analysis is a required positive fact" - that is
  the schema's `:min 1`).
- 31 tests carry `^{:seon/tasks #{...}}`; most also carry a "CLASS:" comment. The
  CLAUDE.md rule "every bug fix adds or tightens the contract at the producer" makes
  most of these redundant once the contract exists.

#### (e) test machinery

`test/seon/test/*` (one_request, publication, reach, runner), `test_runner_test.clj`,
`test_failure_facts_test.clj` (649 lines), `test_reaching_test.clj`,
`test_support_test.clj`, `bounded_boundary_census_test.clj` (334),
`await_owner_census_test.clj`, `loop_proof_test.clj` (883), `operator_test.clj:110`.
Examples: `test_runner_test.clj:112` assertionless test is an attributed failure (keep:
the runner is agent-facing); `test_failure_facts_test.clj:406` "publication replaces
cardinality-many tuples" (storage plumbing of evidence); `test/seon/test/reach_test.clj:40`
re-derives the reach closure with an oracle. The skill says "B4 describes a replacement
runner" (`.agents/skills/clojure-testing/SKILL.md:12`): most of this tree is (b) for a
mechanism headed for rewrite.

#### (f) docstring/example execution

`test/my/examples_test.clj:41` runs every public docstring example (~130 forms) in one
test (45 s bound). This is the editscript `readme-test` idea at Seon scale and is the
**cheapest per assertion** in the tree: keep it, and make examples the place where
`my.*` usage is pinned. `test/my/turn_test.clj:73` (asserts the usage walkthrough has
6 entries and starts with `(dir 'my.turn)`) is incidental structure of that mechanism.

#### (g) census / lint-in-test

`test/seon/schema_test.clj:509` every `:fn` schema has `:error/message`;
`test/seon/schedule_test.clj:205` every `seon.schedule` contract compiles;
`test/seon/error_result_test.clj:17` two attributes are installed;
`test/seon/contracts_compile_test.clj:897` (991-line file) asserts that historical
breaks produce findings containing `"contracts are data"`. 52 tests are named
`every-*`/`each-*`/`all-*` (1,354 lines). These are checks the edit hook,
`bin/seon check` lint, and boot arming already run; as tests they run again per selection.

## 3. Setup cost

### 3.1 How much of a test is world construction

- Median 12 lines before the first assertion (libraries 1-8); mean 14.9. With median
  test length 24, **half of a typical Seon test is setup**.
- `with-database` appears 1,121 times (245 files); `transacted!` 1,091 (181 files);
  `fork-cluster-ctx` 248; `agent-tx` 189; `await-event!` 184; `seed-cluster!` 180.
  The typical test opens `(support/with-database (fn [connection] ...` (2 lines and one
  indentation level), then transacts a hand-assembled vector of entity maps.
- Local fixture helpers in test files: 3,438 lines of `with-*`/`*-tx`/`seed*`/
  `*-fixture` defns; largest `test/seon/turn_test.clj:516` `virtual-turn-fixture`
  (279 lines), `render/web_test.clj` `with-server` (138), `cluster/turn_test.clj:192`
  `with-cluster` (107), `cluster/agent_test.clj` `with-connection` (78).
- **Verbatim duplicates:** `with-render-context-proc` is copied in
  `test/seon/cluster/turn_test.clj:147`, `test/seon/turn_loop_test.clj:378` and a third
  file (each ~45 lines building the same five channels and Flow graph); `with-cluster`
  in 4 files, `with-connection` 4, `with-database` re-defined in 4 test files beside
  the support one, `request` 14, `world` 5.

### 3.2 `test/seon/test_support.clj` (1,164 lines)

Largest helpers: `await-event!` 81 lines (`:530`), `evaluate` 67 (`:370`),
`cluster-handle` 42, `with-published-file-database` 38, `with-fresh-database` 36,
`clone-directory!` 35, `transacted!` 34 (`:222`), `assert-check!` 31, `agent-value` 30,
`with-database` 28 (`:857`), `program-fn-entity` 28, `preserving-schema-registry` 27,
`fork-cluster-ctx` 27, `seed-cluster!` 24 (`:1090`). Usage is concentrated: the top 6
helpers account for ~3,900 of ~4,500 call sites; most of the remaining ~60 helpers serve
one or two files and should move beside their caller or die with it.

Finding: the skill's line citations are stale (`SKILL.md` cites `with-database` at
`:676`, `transacted!` at `:215`, `seed-cluster!` path at `:884/:897`; actual
`:857`, `:222`, `:1090`). CLAUDE.md calls a stale skill a high-priority defect.

### 3.3 What a library-style minimal setup looks like in Seon

Two worlds cover almost everything:

1. **Pure value, no world** (sci/edamame style). Renders, diffs, readers, error
   construction, printer, id derivation, config compilation take values: call them on
   literals. `db_test.clj:2595`, `floor_test.clj:85`, `hiccup_test.clj:76` already do.
2. **One database value, one form** (datahike `db-with` style):
   `(let [db (support/db-with [entity ...])] (are [in out] (= out (f db in)) ...))` -
   an immutable `d/with` value through the writer's validator (the skill already
   admits `with` values for pure derivations, `turn_work_cost_test.clj`). No branch, no
   release, no nesting, one line.
3. **One agent evaluation** for REPL-path behavior: `(support/eval! "source")` returning
   the reply map, the agent and branch implicit (today `support/evaluate` takes 8 keys,
   `render/value_test.clj:1266-1272`).
4. Only process/store/boot/concurrency subjects keep the full cluster fixtures, and
   they share **one** `with-cluster` in support, not four.

Target: median ≤ 3 lines before the first assertion for (1)-(3).

## 4. Rules for agents writing tests

Each rule: library example, then Seon counter-example.

1. **Do not write a test when a contract, schema or type already refuses the bad
   value.** Tighten the producer's Malli output union and let the armed wrapper and the
   REPL warning prove it. Lib: malli tests `m/explain` data once, not each schema's
   user (`error_test.cljc:105`). Seon: `test/seon/reset_edges_test.clj:72` re-proves a
   required cardinality; `test/seon/fn_test.clj:284` (74 lines) re-proves declaration
   refusals.
2. **Do not test internal plumbing** - private Vars, cache keys, intermediate maps,
   macroexpansions, graph wiring. Lib: malli/sci have zero private-Var refs. Seon:
   `db_test.clj:2558` (`#'db/write-entity-schemas`), `my/background_test.clj:33`
   (macroexpansion), `cluster/turn_test.clj:517` (cache revision keys).
3. **Do not test printed text** unless the printer is the subject (value printer tests
   and declared golden transcripts only - CLAUDE.md already says so). Assert the
   render's data. Lib: malli asserts strings only for `humanize`. Seon:
   `render/value_test.clj:1253` `(str/starts-with? reply "#:seon.repl{")`;
   `render/transcript_test.clj:701`.
4. **Test through the entrance the agent uses.** Lib: sci tests via `eval-string`
   (`core_test.cljc:50`). Seon: `sci/admit_test.clj:141` tests the admit stage's
   print-node under six `*print-*` bindings instead of what a reply shows.
5. **Variations are `are` rows, not new deftests.** Lib: datahike
   `lookup_refs_test.cljc:17-25`; clojure `string.clj:49-58`. Seon has zero `are`;
   `db_test.clj:2595` hand-rolls a `doseq` over pairs; families like
   `run_test.clj:68` `item-8-...` number cases as separate deftests.
6. **A new failure case is a row in the existing table**, not a new deftest named after
   the bug. Lib: malli rows commented "no match"/"first (fallback)" (`core_test.cljc:385-389`).
   Seon: 31 `^{:seon/tasks ...}` tests with "CLASS:" comments (`call_preparation_test.clj:999`).
7. **State a law, generate inputs, and stop.** Use a property only for round trips,
   idempotence or two-path agreement. Lib: editscript `core_test.cljc:196`. Seon good:
   `repl_grammar_test.clj:24`. Seon misuse: `cluster/registry_test.clj:210` -
   100 generated trials of a string-to-keyword naming function.
8. **Setup is one form.** If a test needs more than ~3 lines before its first `is`, the
   missing piece is a support function or the subject takes too many inputs - fix that,
   don't copy a fixture. Lib: datahike `lookup_refs_test.cljc:11-14`. Seon: median 12
   lines; `call_preparation_test.clj:999` nests two `with-database` to reach two `is`.
9. **Never copy a fixture helper into a second file.** Move it to support or the
   subject's namespace. Lib: konserve `compliance-test` shared by every backend. Seon:
   `with-render-context-proc` in three files (`cluster/turn_test.clj:147`,
   `turn_loop_test.clj:378`), `with-cluster` in four.
10. **No `with-redefs`, `alter-var-root` or stand-ins over production Vars.** Inject the
    external effect as a value (the provider is a world member). Lib: malli 0 uses.
    Seon: 149 tests, e.g. `cluster/agent_test.clj:893` redefines `prompt/prompt`;
    `cluster/turn_test.clj:517` redefines `ai/complete`.
11. **Assert data the caller uses, at the narrowest key.** Lib: edamame
    `thrown-with-data?` `{:row 1 :col 1}` (`core_test.cljc:21`). Seon:
    `render/value_test.clj:1253` counts `re-seq` matches in `(str diagnostics)`.
12. **One test, one behavior, one sentence of why at most.** History goes to Git and the
    spec. Lib: sci `error_test.cljc:9-13` has 4 comment lines for a real regression.
    Seon: `problems_test.clj:191` spends 8 comment lines narrating a removed render pair
    for 3 `is`; 6,172 comment lines tree-wide.
13. **No assertion messages that restate the assertion.** Lib: malli rarely uses
    messages. Seon: 2,792 message strings, e.g. `"the turn committed"` beside
    `(< (:max-tx before) (:max-tx after))`.
14. **Do not test presence or compilation of declarations.** Boot arming, the edit hook
    and `bin/seon check` lint already refuse it. Lib: malli has no "every schema
    compiles" test. Seon: `schedule_test.clj:205`, `schema_test.clj:509`,
    `error_result_test.clj:17`, 52 `every-*` tests.
15. **Do not test the test runner unless the runner is what you are changing.** Lib:
    only kaocha, whose product is a runner. Seon: 158 machinery tests / 4.7k lines for a
    runner the skill says is being replaced.
16. **No timing assertions in behavior tests.** Measure cost with the profiler and
    record it as evidence; a bound is the runner's job. Lib: datahike benches live in
    `benchmark/`. Seon: `analysis_identity_test.clj:11` "at one, ten and one hundred
    definitions"; 152 tests carry `:seon.test/long` prose with dated measurements.
17. **Prefer one big table test per function over many tiny tests per case** - and many
    tiny functions over one giant integration test per feature. Lib: malli
    `core_test.cljc` 54 deftests / 2k `is`. Seon: `cluster/agent_test.clj:973` 97 lines
    for one behavior.
18. **Docstring examples are the usage tests.** Put the public example in the docstring
    and let `my/examples_test.clj` run it; don't also write a deftest for it. Lib:
    editscript `readme-test` (`core_test.cljc:29`). Seon: `my/message_test.clj:33`
    reasserts the full inbox map (including `:seon.render/operations`) that an example
    would show.
19. **Hand-built production entities are setup smells.** Use the writer or a support
    builder; literal tx ids are a lie. Seon: `render/transcript_test.clj:708` writes
    `:seon.turn/opened-tx "datomic.tx"`.
20. **Error tests: one per failure class per producer, as a table of (input → error key).**
    Lib: clojure `string.clj:91` `nil-handling`; datahike `lookup_refs_test.cljc:22-25`.
    Seon: `error_test.clj`, `error/refusal_test.clj`, `db_error_test.clj`,
    `blob_error_test.clj`, `error_evidence_test.clj`, `error_recording_test.clj`,
    `error_result_test.clj`, `error_write_timing_test.clj` - eight files on one
    producer family.
21. **Name the test for the behavior, briefly.** `reload-test`, `humanize-test`,
    `test-lookup-refs`. Seon: 475 test names over 60 characters, e.g.
    `a-reply-within-every-declared-bound-adds-nothing-and-an-exceeded-bound-is-a-findings-entry`
    - the name becomes the spec and drifts from the body.
22. **A test that cannot fail on a real regression is deleted, not kept as coverage.**
    Lib: no vacuous checks in malli. Seon: `error_result_test.clj:88` has 0 `is` (38
    tests tree-wide with none at top level; some delegate to helpers).
23. **When not to write a test at all:** the value is already constrained by a schema or
    contract; the subject is private or headed for rewrite; the assertion is about text
    or ordering that no consumer reads; the behavior is a property of a dependency
    (Datahike, Malli, SCI, core.async) - they test it; the change is a refactor with
    unchanged behavior (existing tests prove it); the bug is a row in an existing table.
    If none of those applies and an agent or user would notice the break, write **one**
    test, through the entrance, asserting data.

## 5. Deletion policy

### 5.1 A lane may delete a test without discussion when any of these hold

1. **Private reach:** the test calls a private Var (`#'ns/x`) or asserts keys of an
   intermediate map no caller outside the namespace reads.
2. **Dead or condemned mechanism:** the subject is deleted, renamed away, or named in an
   owner-ruled rewrite/cut (feature cuts 2026-09-28; the replacement runner).
3. **Contract-duplicate:** the test only checks that an invalid input is refused / an
   error carries keys, and the producer's Malli input/output schema (armed) already
   declares that alternative. Before deleting, `m/explain` the offending value against
   the contract at the REPL; if it does not refuse, tighten the contract instead (that
   is the rule's own fix), then delete.
4. **Text-only:** every assertion is `str/includes?`/`re-find`/`starts-with?` or a
   literal string compare on rendered output, and the file is not the value printer's
   own test or a declared `:seon.test/golden`.
5. **Census:** the test iterates declarations to check they exist, compile, carry a
   message or a render pair - a check boot, the edit hook or lint performs.
6. **Machinery for machinery:** a test of the test runner, reach, recording or support
   that does not protect an agent-visible result of `seon.test/run` (keep: assertionless
   failure, bound failure, failure report data, branch release).
7. **Duplicate:** another test asserts the same behavior on the same entrance (same
   function, same failure class); keep the shorter one and add the other's inputs as rows.
8. **Vacuous:** no assertion can fail on a regression of the named behavior (no `is`,
   or only `some?`/`seq` on the fixture it just wrote).
9. **Perf in a unit test:** the assertion is a timing or revision-count proxy; move the
   number to profiler evidence.
10. **Stand-in tests:** the test only passes because `with-redefs`/`alter-var-root`
    replaces a production Var, and the same behavior has no injected-world test - delete
    and file one task to add the injected-world version only if (a)-class behavior is lost.

Helpers orphaned by deletions go in the same commit. Deleting needs no replacement
unless the deleted test was the only (a)-class proof of an agent-visible behavior;
then convert, don't keep.

### 5.2 Must discuss (not deletable by policy)

Concurrency, crash recovery, store lock, GC/reclamation, boot and adoption tests;
the value printer's own tests; golden transcripts; `my/examples_test.clj`; anything
that is the only test reaching an `my.*` function.

### 5.3 How much qualifies

Extrapolated from the 180-test sample and tree-wide counts (deftest lines 71,750;
helper/ns lines 25,272):

| Source | Lines deletable by policy | Basis |
|---|---|---|
| (b) plumbing: private reach, condemned mechanisms | 8-11k | half to two-thirds of ~16.8k (b) lines meet 5.1.1-2 outright |
| (d) contract duplicates | 4-5k | ~6.3k (d) lines; some lack the contract and need a tightening first |
| (c) + text-dominant tests | 2.5-3k | 96 tests / 2,870 lines are majority-text; (c) ~1.9k overlaps |
| (e) machinery | 3-4k | 4.7k file-group lines minus ~1k agent-visible runner behavior |
| (g) census | 2-2.5k | 52 `every-*` tests 1.35k + `contracts_compile_test.clj` (991) mostly + presence checks |
| duplicates within (a) (rule 7) | 2-3k | e.g. eight error files, render families, `repl_parity` vs `eval_test` |
| orphaned helpers + duplicated fixtures | 5-7k | ~30% of 18.4k test-file helper lines follow their tests; ~1k verbatim copies |
| **Total by policy** | **~27-35k lines (28-36%)** | ~700-900 deftests |

Beyond deletion, rewriting the surviving (a) tests in library form (pure values or one
`db-with`, `are` tables, no prose history, shared fixtures) should halve their size:
~38k → ~18-20k. **Plausible end state: 35-45k lines, ~1,000-1,200 deftests**, at
roughly datahike's density (9 lines per `is`) with sci-like setup. Ordering: policy
deletions first (cheap, no discussion, removes reds tied to dead code), then fixture
consolidation (one `with-cluster`, a `db-with` helper, `eval!`), then table rewrites
per file, largest first (`fn_test`, `cluster/turn_test`, `db_test`, `sci/eval_test`,
`turn_test`, `render/web_test`).

### 5.4 Findings outside the assignment (for task recording)

- 149 tests use `with-redefs` (180 sites, 66 files) and 24 `alter-var-root` sites,
  against CLAUDE.md "never `alter-var-root`, `with-redefs` ... over any Var".
- `.agents/skills/clojure-testing/SKILL.md` cites stale `test_support.clj` lines
  (`with-database :676` → `:857`, `transacted! :215` → `:222`, `seed-cluster!`).
- `with-render-context-proc` duplicated verbatim across three test files.
- 38 deftests have no top-level `is` (some delegate to helpers; each needs a check
  against the runner's assertionless rule).

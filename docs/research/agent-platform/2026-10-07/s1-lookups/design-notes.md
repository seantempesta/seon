---
title: S1 lookups and help, design
type: research
status: proposed (gate 1, design only)
date: 2026-10-07
lane: s1-lookups
---

# S1: standard REPL lookups and `(help)` (agent-loop spec §1.6, §4.7)

One line: today `doc`/`dir` are Seon macros over Datahike facts that compile contracts per call and `source`,
`apropos`, `find-doc` are unbound or print; S1 makes all five bare forms return data from SCI's `clojure.repl` walk
plus Seon's facts only where SCI cannot know (contracts, schemas, tasks, test status), installed through the one
existing declared-binding mechanism, and makes `(help)` a constant value that teaches only what is installed.

Path step served: the opening (`(help)`, the task's `doc`, references' `doc`/`source`) and every later lookup.
Each lookup stays one form, one read, one evaluation through the one REPL path, printed by the value printer.

Sources: HEAD `ec5f13d2f` (default ran `0c2a12900` until its restart at 11:57, then the same `src`; the only
working-tree diff in `src/seon/sci/eval.clj` is s5a-reply's docstring at :3719). Probes: MCP `eval_clj` as agent
`s1-lookups` (SCI, namespace `scratch.s1-lookups`) and JVM mode for reads of installed code only. SCI pin
`ce0aa53b9b` (`git ls-tree HEAD reference-code/sci` = checkout HEAD). Starting points: `../repl-lookup/design-notes.md`
(the repl-lookup design), `../plan-research/r-context-notes.md` §3 (its verification), and
`../stopped-wip/repl-lookup.patch` (judged in §8).

## 1. What exists (file:line at `ec5f13d2f`, measured)

### 1.1 Installation: three installers and one declaration mechanism

| Where | What it installs |
|---|---|
| `resources/seon/schemas/seon.sci.binding.edn` | declared bindings `help`, `dir`, `doc` (targets `seon.bootstrap/*`), `deftest`, `is` (targets `clojure.test/*`). `seon.program/base-context-injected-symbols` (`src/seon/program.cljc:184-201`) copies each target into the base SCI context; `seon.fn/interpreter-refer-entities` (`src/seon/fn.clj:878-896`) makes the analysis refer the same bare names to the same targets. |
| `build-base-ctx` (`src/seon/sci/eval.clj:348-433`) | hand-refers `dir`, `doc`, `help`, `deftest`, `is` into `clojure.core` (:412-424) and again into `seon.bootstrap` (:425-429) |
| `install-program-doc!` (`eval.clj:2137-2150`), called by acquisition (:2391) | `doc`, `dir` (SCI macro vars `program-doc-var` :2079, `program-dir-var` :2095) and `source-fn` (`stored-source-fn` :2127) into `clojure.repl`; `doc`, `dir`, `deftest`, `is` into `clojure.core` again. Its `_db _projection` arguments are unused. |
| acquisition (`eval.clj:2566-2568`) | re-refers `help` into `clojure.core`, because `seon.bootstrap` is a program namespace whose acquired Var replaces the boot copy |

So bare `doc` goes: `clojure.core/doc` → the injected copy of host macro `seon.bootstrap/doc` (`src/seon/bootstrap.clj:161-169`)
→ `(clojure.repl/doc x)` → `program-doc-var`. The analysis records the call edge as `seon.bootstrap/doc` (probe:
`(defn s1-uses-doc … (doc seon.id/symbol-in))` stored `:seon.fn/call-arities [[clojure.core/defn 4] [seon.bootstrap/doc 1]]`),
while evaluation runs `clojure.repl/doc`. A bare name with no declaration is recorded in the caller's own
namespace (`s1-uses-interned` → `[scratch.s1-lookups/interned-bare 0]`), so every new bare lookup needs a
declaration for the analysis to name what evaluation runs (`fn.clj:880-886`: "A submitted form's ANALYSIS must resolve
exactly what its EVALUATION resolves").

### 1.2 The lookups today (agent `s1-lookups`; ms and armed calls from the reply; MB from JVM-mode reads with `getThreadAllocatedBytes`)

| Form | Today | Cost |
|---|---|---|
| `(doc seon.id/symbol-in)` | `{:summary :body :example :arglists :in :out}` | first 20 ms / 9,407 calls (the contract compile through the projection cache), warm 2-3 ms / 308 calls; JVM 1.0-1.5 ms, 1.3-2.0 MB |
| `(doc seon.task/create!)` | `:in` is the contract with every registered key replaced by its schema (`agent-documentation-contract`'s `postwalk-replace`, `eval.clj:1966-1995`): map entries print as `[[schema] [schema]]` pairs, supplied keys are not removed. 1,628 tokens (r-context) for a 1-line declared spec | JVM 1.5-13 ms, 2-4 MB |
| `(doc seon.task)` | docstring parts plus `:in [] :out []` (`documentation-value` :2076) | 2 ms / 59 calls |
| `(doc :seon.task/intent)` | contract refusal, "expected a symbol, got a keyword" | 11 ms |
| `(doc clojure.string/split)` | `documentation-unavailable` | |
| `(doc <task symbol>)`, `(doc <test symbol>)` | `documentation-unavailable` (r-context §3) | |
| `(dir seon.task)` | `directory-value` (:2019): per function arglists reader + contract compile + schema postwalk, then the render pair `seon.repl/render-directory-ai` (`src/seon/repl.clj:36-55`) drops `:in`/`:out`/`:schemas`. Shown as one quoted string with escapes (`:large-value`, 2,290 value tokens, 1,500 shown) | 93 ms / 9,026 calls in SCI; JVM 30-48 ms, **48-83 MB** |
| `(clojure.repl/dir-fn 'seon.task)` | 31 names (SCI) | 1 ms |
| `(clojure.repl/source seon.id/symbol-in)` | value nil, text on `:out` (SCI's `source` macro, `reference-code/sci/src/sci/impl/namespaces.cljc:2433-2440`, over our `stored-source-fn`) | 1 ms / 74 calls |
| `(clojure.repl/source-fn 'seon.id/symbol-in)` | the stored source string | 3 ms / 3,872 calls (SCI); JVM `stored-source` 0.13 ms / 0.26 MB |
| `(clojure.repl/source-fn 'clojure.set/union)` | nil (our override reads only the DB) | 1 ms / 173 calls |
| bare `source`, `apropos`, `find-doc` | "Unable to resolve symbol" | |
| `(clojure.repl/apropos "symbol-in")` | `(seon.id/symbol-in)` | 8 ms |
| `(clojure.repl/find-doc "symbol-in")` | nil, prints 522 characters | 14 ms |
| `(help)` | `{:seon.help/lines […]}` with render pair `render-help-ai` (a joined string): 1,105 tokens, `:large-value` | 17 ms / 444 calls; JVM 17 ms, **19.6 MB** |
| `(my.program/source 'seon.id/symbol-in)` (`src/my/program.clj:874-893`) | `{:sym :kind :source :doc :contract :file :line}`; `file-line` reads the whole file from disk | JVM 4.3 ms, 5.1 MB |

Whole MCP SCI passes through the S0 probe window (`seon.pass-probe/window` over `mcp-pass`, same agent, back to back;
whole-JVM allocation, so other lanes add noise):

| Pass | wall ms | allocated | checked calls | store bytes | tokens |
|---|---|---|---|---|---|
| `(+ 1 2)` (first / last of the run) | 107 / 56 | 156 / 77 MB | 127,176 / 10,472 | 206 / 215 KB | 18 |
| `(doc seon.id/symbol-in)` | 92 | 148 MB | 38,069 | 322 KB | 115 |
| `(dir seon.task)` | 192 | 298 MB | 101,700 | 371 KB | 1,648 |
| `(help)` | 89 | 127 MB | 21,662 | 300 KB | 1,207 |
| `(clojure.repl/source-fn 'seon.id/symbol-in)` | 64 | 93 MB | 14,643 | 268 KB | 127 |

`dir` and `help` are the over-budget lookups (task `seon.sci.eval/dir-and-help-allocate-tens-of-megabytes-per-call`,
created by this lane).

### 1.3 SCI var metadata vs Datahike facts

- Program namespaces are acquired into SCI as copies of host Vars (`copy-host-var`), library namespaces on first
  `require` (`load-host-namespace`, `eval.clj:251-273`). Copies carry `:doc`, `:arglists`, `:file`, `:line`, `:ns`
  (`(meta #'clojure.set/union)` full). An agent's interpreted override carries its own `defn`'s metadata; an agent's
  plain `def` exists only in SCI. So SCI is exactly what the agent can call, on its branch.
- Exception: `sci.interrupt/clojure-string` copies (`reference-code/sci/src/sci/interrupt.cljc:308-315`) carry
  `{:ns clojure.core :name split :sci/built-in true}`, no doc, no arglists, and `replace-first` is created under the
  name `replace`. Producer is the SCI fork (`seantempesta/sci`); task
  `seon.sci.eval/sci-interrupt-string-vars-carry-core-metadata` (created).
- Datahike holds what SCI cannot: `:seon.fn/spec` (declared contract text), supplied keys
  (`seon.call-preparation/supplied-map-entries`, 4 ms / 1 MB), `:seon.schema/form` (1 ms), tasks
  (`seon.task/task-edges` 5-15 ms, up to 14.8 MB on a cold call: its `or` + join shape, existing task
  `seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding`), test results
  (`seon.test.runner/latest-results` 0.16 ms).

### 1.4 Callers

- JVM side, no SCI context: `seon.render.ns/directory` (`src/seon/render/ns.clj:235-249`) calls `directory-value`
  for the namespace entity's AI render rows `[:sym :arglists :doc :in :out]`; `seon.agent.loop` (unwired: no `src`
  caller, `test/seon/agent/loop_test.clj` only) calls `documentation-value` (:31, :35, :111), `directory-value`
  (:32, :128), `stored-source` (:104, :113) and `bootstrap/help-value` (:54).
- `my.program/source` has no `src` caller except `(help)`'s text (`bootstrap.clj:56`); `my.program/declaration`
  (:837) is also called by `declaration-pair` (:981), so it stays (spec correction).
- Tests reaching the change (grep of `directory-value|render-directory|install-program-doc|help-value|render-help|program/source|stored-source|"(dir |"(doc |"(help)"`):
  `sci/documentation_test`, `repl_parity_test` family D, `render/value_test` (:64-77, :851-864),
  `render/web_debug_test` (:281, :782-796, a `with-redefs` over `directory-value` and `render-directory-ai`),
  `data_shapes_test` :270, `repl_test` :31, :201, :267, :379, `render/ns_test` :78, `sci/eval_test` :1236-1238, :1289,
  `directory_test`, `supplied_documentation_test`, `my/program_test` :135, `my/program_read_test`, `help_test`,
  `repl_grammar_test` :84-112, `html_views_test` :78-81 (+ `fixtures/html_views_ai.edn`), `sci/supplied_database_test`
  :19, `run4_install_test` :83, `agent/loop_test`, `turn_test` :1053-1083, `loop_proof_test` :463, :488.

## 2. Duplicates

| Thing | Done by |
|---|---|
| bare-name installation | the declarations, `build-base-ctx`'s two hand lists, `install-program-doc!`, acquisition's `help` line: four installers |
| a namespace's public names | `program-documentation` (`eval.clj:1835`), `render.ns/public-functions` (`ns.clj:219`), SCI `dir-fn` |
| a var's doc and arglists | DB `function-doc-map` (re-reads stored arglists text), SCI var meta (copied from the same host Var), `my.program/source` |
| a declaration's source | `stored-source` (`eval.clj:2106`), `my.program/declaration`+`source`, SCI `source-fn` |
| contracts in `dir` | computed by `directory-value`, discarded by `render-directory-ai` |
| a symbol near a typo | `my.program/nearest`, SCI `apropos` |
| help's tools line | `help-value`'s join and `seon.agent.loop/system-message`'s toolkit lines |

## 3. Library seams (SCI `ce0aa53b9b`, `sci/impl/namespaces.cljc`)

- `dir-fn` (:2303-2309): names of a namespace, alias resolved through the current namespace. Throws on an unknown
  namespace (r-context: "No namespace … found"), so check `sci/find-ns` first.
- `apropos` (:2369-2382): sorted qualified symbols over every SCI namespace's publics; string or regex. Returns data
  already (1.7 ms for "task", 27 hits).
- `find-doc` (:2351-2367): walks `sci-all-ns` × `sci-ns-interns*` metadata (private vars included, as Clojure's
  `ns-interns`) plus namespace metadata, keeps entries with `:doc` matching name or doc, then `print-doc`s. The data
  adaptation keeps the walk and returns `[{:sym :arglists :summary}]` instead of printing. Prototype in SCI over 490
  namespaces / 4,897 interns: 20 ms.
- `source-fn` (:2409-2431): reads `:file` with `jio/file` (relative program paths miss), else calls the load-fn with
  only `{:namespace …}`. Statically, `load-host-namespace`'s contract (`eval.clj:263`) requires `:ctx`, so that call
  would be refused; not probed live because our override replaces `source-fn` (UNVERIFIED live). Clojure's own
  `clojure.repl/source-fn` reads library source from the classpath: 0.6-1.1 ms, 1.3 MB.
- `doc` (:2337-2349): `resolve` then `print-doc` of var meta, else namespace meta. Seon keeps the resolution order,
  returns data.

## 4. The smallest composition

Principal approach: the bare lookups become host definitions in `seon.bootstrap`, beside `help`, installed by the
existing declaration mechanism, so the analysis edge and the evaluated Var are the same symbol. Their bodies call data
functions in `seon.sci.eval` that use SCI's walk for names and metadata and the database only for contracts,
schemas, tasks and test status. One generic refer replaces the four installers.

### 4.1 Installation (BEHAVIOR CHANGE: three new bare names; one installer)

- `seon.sci.binding.edn`: add `:seon.sci.binding/source`, `/apropos`, `/find-doc` with targets `seon.bootstrap/*`
  and reasons. `help`, `dir`, `doc`, `deftest`, `is` stay.
- `seon.sci.eval/refer-declared-bindings!` (new, ~8 lines, contracted): refers every declared target's bare name into
  `clojure.core` from the context's current Var for that target. Called by `build-base-ctx` (replacing :412-429's two
  hand lists, keeping the native-mutation entries) and by acquisition (replacing the `help` line at :2566-2568).
- `clojure.repl/source-fn` keeps a Seon override because SCI's own cannot read branch-correct source (§3); it moves
  from `install-program-doc!` into `build-base-ctx`'s `sci/init` options beside the `future-call`/`pmap` entries
  (`(assoc-in [:namespaces 'clojure.repl 'source-fn] …)`). It is compiled platform code reading the evaluation's
  database at call time, so acquisition need not reinstall it.
- Deleted: `install-program-doc!`, `program-doc-var`, `program-dir-var`, `stored-source-fn` (its body becomes
  `source-fn` over `source-value`), the `seon.bootstrap` re-adds at :425-429.
- Qualified `clojure.repl/doc`, `dir`, `source`, `find-doc` stay SCI's own printing forms (Clojure-compatible;
  `source` prints through the override). Bare names are the data path. Alternative (§5.2) makes them the same Vars.

### 4.2 `seon.bootstrap` host definitions (expansions run in the evaluating SCI context)

```clojure
(defmacro doc [x] (list 'seon.sci.eval/documentation-value '(seon.db/db) (list 'quote x) (list 'seon.sci.eval/reference-in (list 'quote x))))
(defmacro source [x] (list 'seon.sci.eval/source-value '(seon.db/db) (list 'quote x) (list 'seon.sci.eval/reference-in (list 'quote x))))
(defmacro dir [x] (list 'seon.sci.eval/directory (list 'quote x)))
(defn apropos "..." {:malli/schema [:=> [:cat :seon.sci.eval/lookup-pattern] [:vector :qualified-symbol]]} [pattern] ...)
(defn find-doc "..." {:malli/schema [:=> [:cat :seon.sci.eval/lookup-pattern] :seon.sci.eval/var-summaries]} [pattern] ...)
```

`(doc 'x)` keeps Clojure's behavior (the macro quotes its argument; r-context §6), now as `reference-in` of a list,
which answers `documentation-unavailable` instead of a `ClassCastException` (BEHAVIOR CHANGE, row in the test table).

### 4.3 `seon.sci.eval` data functions

| Function | Does | Size |
|---|---|---|
| `reference-in` (new) | a qualified symbol with its alias expanded through the current SCI namespace (not var meta, because of the interrupt copies); a simple symbol that resolves to a Var as that Var's symbol; else unchanged. Keywords and other values unchanged | ~12 lines |
| `directory` (new) | `sci/find-ns` of the alias-expanded name, else `documentation-unavailable`; `dir-fn` names → `[{:sym :arglists :summary}]` from SCI var meta, summary by `docstring-parts` | ~10 lines |
| `apropos`/`find-doc` bodies | SCI `apropos` as a vector; SCI's `find-doc` walk returning `var-summaries` | ~4 + ~12 lines |
| `source-value` (new, from `stored-source-fn`) | `stored-source` (program families, private and tests included, branch-correct), else Clojure's `clojure.repl/source-fn` when the reference names a host Var, else `documentation-unavailable` | ~6 lines |
| `documentation-value` (rewritten, same 3-arg signature, `reference` widened to keywords) | dispatch on the reference kind: keyword → schema; simple symbol → namespace; qualified → program function, test, task, else SCI var meta | ~20 lines + per-kind ~45 |
| per kind | schema `{:key :form :description}` (one pull, `edn/read-string` of the stored form); namespace `{:summary :body :example :publics}` (program: `:seon.ns/doc` + the reverse pull of public functions, 1.6 ms; library: host ns meta + `dir-fn`); program function: `function-doc-map` with `:contract` (§4.4); test `{:sym :status [:failure]}` from `latest-results`; task `{:sym :intent :subjects :proofs}` from `task-edges` + one `latest-results` read; other Var: SCI meta `{:summary :body :example :arglists}` | |

### 4.4 `doc` of a program function shows the declared contract (BEHAVIOR CHANGE)

Replace `:in`/`:out` (the compiled, key-erased, schema-substituted view) by `:contract`, the declared
`:seon.fn/spec` read once as data (0.56 ms / 0.09 MB for `seon.task/create!` vs the compiled view), plus `:supplied`
(the world keys an agent omits, from `supplied-map-entries`, kept). An absent spec stays the typed unknown
`declaration-absent` (kept). This closes `seon.sci.eval/doc-prints-contracts-expanded-with-keys-garbled` and
`doc-and-dir-erase-request-keys`, and deletes `documentation-contract`, `documentation-schemas` and
`agent-documentation-contract`'s walk (~85 lines) with the cold 9,000-call contract compile. Root may split this row
into its own slice (§5.4); without it S1 keeps today's `:in`/`:out`.

### 4.5 `(help)` (BEHAVIOR CHANGE)

`seon.bootstrap/help-value` takes no inputs and returns `{:seon.help/lines […]}`; no database read, no render pair
(the vector of strings goes to the value printer; `render-help-ai`/`-html` deleted with their golden and grammar
assertions, the `seon.help.edn` render properties removed). Lines teach only what the S1 commit installs, each
naming forms that resolve in a fresh agent world:

1. Lookups return data: `(doc x)` for a function, namespace, schema keyword, task or test; `(source x)`; `(dir ns)`;
   `(apropos "text")`; `(find-doc "regex")`.
2. Results are data; `result/e…` names an earlier evaluation's live value: evaluate it or dig in with `get-in`.
3. Define a function with its contract: `(defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))`;
   `deftest` and `is` are referred and a deftest is durable.
4. `(my.test/check {:seon.test/changed [...]})` runs the named tests and those reaching named functions on your
   branch; `:seon.test/passed?` is true only when all are green.
5. A mistake returns `:error` data: read its type and message before retrying.
6. `seon.db/q`, `seon.db/pull`, `seon.db/transact!` read and write your branch; the database is supplied.
7. `(my.message/send {:my.message/to "root" :my.message/content "..."})` sends a message.
8. Reader: "Send code in ```` ```clojure ```` fences." This is true today (probe: `segments` reads a fenced form and
   makes the prose before it a comment). The full rule (fences; outside fences a form starts a line; a lone word is
   prose) is TARGET until S2b-1 lands; if S2b-1 lands before S1, S1 teaches it, otherwise S2b-1's commit adds it.
   Today a trailing `Done.` after a fence still evaluates as a symbol (probe), so S1 must not claim prose is safe.

Dropped (false or slated for deletion): the prompt-namespace line (it names the assigned namespace, not the current
one), "Markdown is not prose here" (false today: backticked prose became a comment in the probe), "results arrive
in your NEXT turn", `seon.turn/turns-left`, `my.agent/done`, `my.program/source`, the `my.task` step tutorial (the
opening's `(doc task)` carries the task) and the Tools line (closes `seon.bootstrap/help-lists-no-tools-for-an-agent`).
`help-value`'s source pull existed for read evidence; the refresh that consumed it is deleted in S4, and with no reads
the help value cannot go stale, so `help_test/help-evidence-tracks-its-code-version` (red on HEAD, task
`seon.bootstrap/help-evidence-test-red-on-head`) is deleted with its subject. Expected size ~350 tokens (estimate,
measured at implementation).

### 4.6 Converted callers and deletions

- `seon.render.ns/directory`: rows from `documentation-value` of the namespace's `:publics` (its own
  `public-functions` reverse pull stays for `members`), columns `[:sym :arglists :summary :contract]`. Cost ≈ N ×
  1 ms; contract compile gone.
- `seon.agent.loop`: `tool-lines` takes names from the namespace doc's `:publics`; `namespace-unit` becomes
  `(doc ns)`; `system-message` calls `(help-value)`. Alternative: delete the unwired namespace (§5.3).
- Deleted: `directory-value`, `program-documentation`, `seon.repl/render-directory-ai`/`-html` and `:seon.repl/directory`, the namespace doc's `:in [] :out []`,
  `seon.bootstrap` forwarding `dir`/`doc` bodies (replaced in place), `my.program/source`, `file-line`, `nearest` and
  `test/my/program_read_test`'s two source tests (`(doc x)` and `(source x)` answer them; the task in
  `resources/seon/tasks/my.program.edn:59` closes with its subject). `my.program/declaration` and `search` stay.
- Schemas: `seon.sci.eval.edn` gains `reference`, `lookup-pattern`, `var-summary(ies)`, `docstring-parts`,
  `namespace-doc`, `schema-doc`, `proof-status`, `task-doc`; `:seon.sci.eval/documentation-unavailable` widens to
  `:seon.sci.eval/reference` so one variant serves keywords too (the patch's separate `schema-unavailable-error` is not
  needed). `seon.repl.edn` loses `:directory`; `seon.help.edn` loses its render pair; `seon.sci.binding.edn` gains three
  declarations. Published and adopted together on a branch of the live store before landing.
- New errors use the installed constructor `seon.error.refusal/error`; `documentation-unavailable`'s existing
  `diagnostic` call is converted to it in the same commit (it is touched).

### 4.7 What disappears and why no owner already does it

Disappears: three of four installers, two SCI macro vars, the per-call contract compile and schema postwalk, a
directory render pair, a second DB name index, `my.program/source` with its disk read, and help's two queries.
SCI already owns names, metadata, `apropos` and the `find-doc` walk; S1 uses them. Nothing upstream knows Seon's
contracts, schemas, tasks or test status, so those four reads stay in `seon.sci.eval`. Estimated net:
`src` about -230 / +150 lines, schemas +25 / -15, tests: one table test added, about eight files converted.

## 5. Alternatives

1. **The stopped patch's installer (SCI macro vars in `clojure.repl`, referred into `clojure.core`).** Same data
   functions, but the analysis would still name `seon.bootstrap/doc` while evaluation runs `clojure.repl/doc`, and
   new bare names have no declaration, so their edges land in the caller's namespace (§1.1). Declaring targets as
   `clojure.repl/*` instead makes `base-context-injected-symbols` copy Clojure's printing host macros into SCI only
   for the installer to overwrite them (`sci/impl/opts.cljc:21` merges namespaces per var). Rejected: two mechanisms
   or a declaration that lies.
2. **Also make qualified `clojure.repl/doc|dir|source|find-doc` the same data Vars.** One extra `assoc-in` per name in
   `build-base-ctx`. Agents do type `(clojure.repl/source x)` (`test/seon/sci/forgive_replies.edn`). Cheap; I lean
   against it only because it diverges from Clojure for a qualified name. Root's call.
3. **Only delete.** Refer SCI's own forms: they print and return nil, read stale files on a branch and know no
   contracts, schemas or tasks. Rejected (spec §1.6).
4. **Split §4.4 (declared contract in `doc`) into its own slice.** Keeps S1 smaller; leaves the cold compile and the
   garbled view one more slice.
5. **Delete `seon.agent.loop` now instead of converting it.** It has no `src` caller and S4's opening is forms that
   ran; deleting saves ~230 lines and its test. It belongs to S4's area, so root decides.
6. **A fallback to host metadata for library Vars lacking `:doc`.** Rejected as a workaround: the producer is the SCI
   fork's interrupt copies (task above).

## 6. Risks and the tests that reach them

| Risk | Test |
|---|---|
| bare names after acquisition and in retained contexts (the reason acquisition re-refers `help`) | `sci/documentation_test`: `retained-context-receives-the-current-repl-macro-through-its-core-alias`, `retained-context-receives-new-bare-test-referrals`, `bare-test-macros-resolve-without-namespace-referrals`, converted to call `refer-declared-bindings!` |
| analysis edges match evaluation for all bare names | one table row per name asserting the stored `:seon.fn/call-arities` names `seon.bootstrap/<name>` |
| each lookup kind answers data | new `sci/documentation_test/each-lookup-answers-its-reference-kind-as-data`, one table through `support/agent-value`: program fn, private fn, library var, aliased var, own SCI `def`, program ns, library ns, schema keyword, unknown keyword, task, test, unknown symbol, quoted symbol, program/test/library source, `dir` program/library/unknown, `apropos`, `find-doc` (asserts `:out` empty) |
| `dir` sees a function defined after acquisition (SCI meta) | `directory-observes-a-function-installed-after-context-acquisition`, converted to the vector shape |
| interrupt `clojure.string` copies: `(doc clojure.string/split)` shows only arglists absent | documented in the table as today's value; fixed by the SCI fork task |
| `find-doc` cost grows with interns (4,897 here, 20 ms) | profile evidence per call; bounded by what SCI holds |
| `help` names a form that does not resolve | `help_test`: every call head in help's lines resolves in a fresh agent context (class regression) |
| history: stored `:seon.eval/renderer 'seon.repl/render-directory-ai`/`render-help-ai` rows | saved text is displayed as saved; `web_debug_test` drops its `with-redefs` over the deleted Vars and keeps the byte-for-byte assertion |
| file ownership | `src/seon/sci/eval.clj` is claimed by s5a-reply (`bin/lane status`); `src/seon/task.clj` by file-reader (read only here). S1 cannot start code until eval.clj is released |
| schema adoption of new binding declarations rebuilds the base context only at boot | proven by publication and adoption on a live-store branch, then root's restart |

repl_parity family D: D5, D6, D9 become passing as data (D5 and D9 converted from `:out` to the value); D1 changes
(`(doc parity_doc)` of an agent's own macro now answers its SCI docstring); D2, D7, D13 convert to the new shapes.

## 7. Expected S0 numbers after

S1 changes no pass stage, so the ordinary, definition and after-definition passes are unchanged (any change > 20%
is a regression to explain). Lookup passes (same probe window, warm):

| Pass | today | expected |
|---|---|---|
| `(dir seon.task)` | 192 ms, 298 MB, 101,700 calls, 1,648 tokens | ≈ `(+ 1 2)` + 1 ms (SCI prototype 0.8 ms), allocation within baseline noise, ~31 rows with summaries |
| `(help)` | 89 ms, 127 MB, 21,662 calls, 1,207 tokens | ≈ `(+ 1 2)`, ~350 tokens |
| `(doc seon.task/create!)` | first 20 ms / 9,407 calls, then 2 ms / 308 | < 2 ms first and warm (no compile), tokens below its source |
| `(source x)` | unbound | ≈ `source-fn` today (64 ms pass, 127 tokens) |
| `(find-doc "symbol-in")` | prints, nil | ≈ 20 ms evaluation, data |

The opening's lookups (`(help)`, `(doc task)`) fit the < 200 ms / < 5 MB opening-pass budget only if `task-edges`'
`or` + join cost is fixed (`seon.db/an-or-clause-joined-to-a-pattern-costs-a-scan-per-binding`; 5-15 ms, up to 14.8 MB
cold); the narrow holder read is 1.5 ms (SCI prototype). That fix is in `seon.task` (file-reader's file), not S1.

## 8. The stopped patch, judged

Keep: the per-kind `doc` functions (schema, namespace, test, task, proof status), `reference-in`, `source-value`,
SCI-based `directory`, the `find-doc` walk (but over `sci-ns-interns*` and namespace metadata as SCI's own walk,
not only publics), the added schemas except `schema-unavailable(-error)`, the `seon.repl` deletions, the table test
(plus the rows above). Change: installation (§4.1, §5.1), the `declare` before `build-base-ctx` disappears with
the installer, `render.ns/directory` (§4.6), `documentation_test`'s two lint errors (a removed `seon.schema` require
still used). Add: §4.4, §4.5, the `my.program/source` deletion, analysis-edge rows. Never loaded or run; nothing in
it is evidence.

## 9. Claims the implementation needs (verify at the REPL first)

1. A host macro from `seon.bootstrap` copied by `copy-host-var` and referred into `clojure.core` expands in SCI with
   `seon.sci.eval/*` and `seon.db/db` resolvable before and after acquisition (true for `help` today).
2. `refer-declared-bindings!` at acquisition picks up the acquired `seon.bootstrap` Vars (the reason for :2566).
3. `sci.ctx-store/get-ctx` is bound when the host `apropos`/`find-doc` functions run inside an evaluation (true for
   `stored-source-fn` today).
4. Declaring three bindings adds three refer rows in analysis and three injected symbols; no other consumer of
   `base-context-injected-symbols` changes.
5. `edn/read-string` of every stored `:seon.fn/spec` and `:seon.schema/form` succeeds (census over default).
6. The value printer shows a vector of help strings and a source string acceptably (inner quotes print escaped;
   ugly-output check at implementation).

## 10. Findings recorded

- Created: `seon.sci.eval/an-uncontracted-defn-returns-no-warning` (`4fe2a5d2ba47`): `(defn- summary-of …)` with no
  contract answered with no warning; `seon.render/a-renderer-symbol-naming-a-library-function-is-refused`
  (`c27e56a3dc36`): `{:x 1 :seon.render/ai 'clojure.core/vals}` → `:seon.render/unknown` "Selected function has no
  acquired row"; `seon.sci.eval/sci-interrupt-string-vars-carry-core-metadata` (`882787bf3c6c`);
  `seon.sci.eval/dir-and-help-allocate-tens-of-megabytes-per-call` (`53da36594add`).
- `(dir seon.task)` is shown as one quoted string with escapes while an inline render returning a large vector shows
  data; cause UNVERIFIED (the symbol-renderer path); linked to `seon.repl/directory-render-returns-text-the-printer-clips-mid-form`
  and deleted with the pair in S1.
- My own mistake, not a task: a pull naming the nonexistent `:seon.fn/analysis-digest` answered a schema error value.
- Health at probe time: default `:faulted` with open faults on `seon.turn.world/record-fault` (task `3d2fb1b41dc8`) and
  `seon.repl/entity-emission` (8 tasks); over-bound `seon.db/transact-call` 1,989 ms max and
  `seon.cluster.source/changed-identities` 1,621 ms (tasks `483c64e1796e`, `4a627750dc7e`). Default restarted at
  11:57 (root); no RESET NEEDED from this lane.

## 11. Design check

The repl-lookup design held for the data functions. It was wrong about installation: it kept two mechanisms and
missed that the analysis names the declared target, so its bare `source`/`apropos`/`find-doc` would have analysed
into the caller's namespace. It also kept the garbled contract view and left help's cost and false lines untouched.
Awkward: S1 cannot start code while s5a-reply holds `eval.clj`; `task-edges`' cost sits in another lane's file.

---
title: S1 lookups and help, design
type: research
status: proposed v3.1 (gate 1, design only; revised for the Opus review, the Astra review, root's rulings and the printer design)
date: 2026-10-07
lane: s1-lookups
---

# S1: standard REPL lookups and `(help)` (agent-loop spec §1.6, §4.7)

One line: today `doc`/`dir` are Seon macros over Datahike facts that compile contracts per call, `source`,
`apropos` and `find-doc` are unbound or print, and four installers plus a stored per-namespace snapshot put them in
place. S1 makes the five lookups data-returning macro/function Vars in SCI's own `clojure.repl`, installed once in
the base context and referred bare through `clojure.core`, so bare and qualified names are one Var. Names and
metadata come from SCI's walk; the database answers only what SCI cannot know (contracts, schemas, tasks, test
status). `(help)` becomes constant data whose examples are quoted forms.

Path step served: the opening (`(help)`, the task's `doc`, references' `doc`/`source`) and every later lookup. Each
lookup stays one form, one read, one evaluation through the one REPL path, printed by the value printer.

Sources: HEAD `0df0480b2`; v1 measurements at `ec5f13d2f` (default pid 69486, then pid 75294 after root's 11:57
restart; `src` unchanged between them). Probes: MCP `eval_clj` as agents `s1-lookups` (retired) and `s1-lookups-2`
(SCI, namespaces `scratch.s1-lookups`, `scratch.s1b`) and JVM mode for reads of installed code only. SCI pin
`ce0aa53b9b`. Inputs: `../repl-lookup/design-notes.md`, `../plan-research/r-context-notes.md` §3,
`../stopped-wip/repl-lookup.patch` (judged in §8), the Opus review `review-s1-opus-notes.md` (`bf90498cf`) and the
Astra review `review-s1-review-codex-notes.md` (`ccda061b6`, of v1 with root's decisions applied; its findings that
still apply to v2 are folded in as §0b).

## 0. Root's rulings applied (2026-10-07)

| # | Ruling | Where |
|---|---|---|
| 1 | Option B: lookup Vars live in SCI's `clojure.repl`; bare and qualified are the same Var; delete `seon.bootstrap/doc|dir` and the other duplicates; verify the base-context binding copy at the REPL | §4.1 (verified, §1.5) |
| 2 | Stop storing interpreter refers on namespace entities in S1 (task `19fd54137a74`); check write-back; reset after landing, no migration | §4.2 |
| 3 | The namespace render drops contracts and reads with one pull; `(doc seon.task/create!)` unchanged in S1 | §4.6, §7 |
| 4 | `doc` takes doc and arglists from SCI Var metadata; `copy-var*` dropping `:malli/schema` is noted for the contract slice | §4.3 |
| 5 | `(help)` holds quoted forms; the resolves-check walks them | §4.5 |
| 6 | `(doc task)` returns the task under its declared AI render (`task-ai`); walks no subjects | §4.3 |
| 7 | `source` returns code that prints as code; smallest general printer change; delete `function-ai`'s false claim | §4.4 |
| 8 | Fix the producers of `(dir 'ns)`: `seon.render.ns/namespace-form`, `run.clj:84` | §4.6 |
| 9 | Delete what deleting `seon.agent.loop` needs: schema retirement adopted on a live-store branch, the two task intents, the `reference-code.md` row, walk-delete's stale note | §4.7 |
| 10 | Add the missing reaching tests; S2a lands first and S1 rebases onto it (`web_debug_test/passive-ledger-never-reconstructs-directory`) | §6 |

## 0b. Astra review folded in (v3)

| Finding | v3 decision | Where |
|---|---|---|
| P1 task lookup reads the evaluating branch, misses task-home custody and `must-read` | `(doc task)` stays the task's declared render from one pull, read explicitly at the task home (`seon.task/home`); the `[*]` pull keeps `:seon.task/must-read`, which `task-ai` shows; no `task-edges`, no subject walk (root) | §4.3 |
| P1 a latest-green result is not current proof | no `proof-status` and no green predicate in the lookup owner; a test's `doc` shows the owner's `recorded-result` labelled as recorded evidence beside the owner's `verified?` | §4.3 |
| P1 contracts reject promised cases | one classification boundary: the data functions take authored syntax (`authored-reference`, a declared polymorphic boundary) and their dispatch answers `documentation-unavailable` for anything that is not a reference; `lookup-pattern` is string or regex, empty allowed; `var-summary`'s `:sym` is any symbol (namespaces appear in `find-doc`) | §4.3 |
| P2 namespace render rebuilds dir's work | already ruled (root 3): one pull, no contracts; measured separately in §7 | §4.6, §7 |
| P2 qualified aliases and adoption proof | the lookups are platform Vars installed once and never replaced, so both spellings stay the same Var; `help` (a program Var) is refreshed at acquisition; proof rows cover fresh and retained contexts, a branch-interpreted `help`, and analysis edges for bare, qualified and `clojure.repl :refer` callers | §4.1, §6 |
| P2 deletion closure | adds `my.program` docstrings (:918, :1039), task must-reads naming deleted producers (`seon.sci.eval.edn` :123, :190, :204), the help HTML renderer's embedded EDN scan; `nearest`'s deletion labelled BEHAVIOR CHANGE | §4.7 |
| Proposed tests | adopted where they fit v2 | §6 |

## 1. What exists (file:line at HEAD, measured)

### 1.1 Installation: four installers and a stored snapshot

| Where | What it installs |
|---|---|
| `resources/seon/schemas/seon.sci.binding.edn` | declared bindings `help`, `dir`, `doc` (targets `seon.bootstrap/*`), `deftest`, `is` (`clojure.test/*`). `seon.program/base-context-injected-symbols` (`src/seon/program.cljc:184-201`) copies each target into the base SCI context; `seon.fn/interpreter-refer-entities` (`src/seon/fn.clj:878-896`) gives analysis the same bare refers |
| `build-base-ctx` (`src/seon/sci/eval.clj:348-433`) | hand-refers `dir`, `doc`, `help`, `deftest`, `is` into `clojure.core` (:412-424) and re-adds them to `seon.bootstrap` (:425-429) |
| `install-program-doc!` (`eval.clj:2137-2150`), per acquisition (:2391) | SCI macro Vars `program-doc-var` (:2079) and `program-dir-var` (:2095) plus `source-fn` (`stored-source-fn`, :2127) into `clojure.repl`; `doc`, `dir`, `deftest`, `is` into `clojure.core` again; `_db _projection` unused |
| acquisition (`eval.clj:2566-2568`) | re-refers `help` into `clojure.core`, since `seon.bootstrap` is a program namespace whose acquired Var replaces the boot copy |
| stored refers (the fifth; Opus finding 1) | `seon.fn/runtime-analysis-batch` adds the interpreter refers to the synthetic analysis `ns` form (`fn.clj:976-1001`); `namespace-entity` stores them as `:seon.ns/refers` (`fn.clj:252`) AND the synthetic form becomes the namespace's `:seon.ns/source`; namespace installation refers them, installing a refusing Var for a missing target (`eval.clj:825-848`) |

Probe (JVM read): `my.agents.root` and `user` store `:seon.ns/source "(ns user (:require [my.message] [my.turn] [seon.db] [seon.bootstrap :refer [help dir doc]] [clojure.test :refer [deftest is]]))"` and five `:seon.ns/refers` rows. So in a namespace with a stored entity, bare `doc` is `seon.bootstrap/doc` (an injected host macro expanding to `(clojure.repl/doc x)`); in a fresh namespace it is `program-doc-var` from `clojure.core` (Opus probe). The analysis records the edge as `seon.bootstrap/doc` (v1 probe `s1-uses-doc`: `[[clojure.core/defn 4] [seon.bootstrap/doc 1]]`). A bare name with no declaration is analysed into the caller's namespace (`[scratch.s1-lookups/interned-bare 0]`), so every bare lookup needs a declaration.

### 1.2 The lookups today (agent `s1-lookups`; ms and armed calls from the reply; MB from JVM-mode reads with `getThreadAllocatedBytes`)

| Form | Today | Cost |
|---|---|---|
| `(doc seon.id/symbol-in)` | `{:summary :body :example :arglists :in :out}`; arglists re-read from stored text by `arglists-reader` (`eval.clj:1916`) | first 20 ms / 9,407 calls (contract compile), warm 2-3 ms / 308; JVM 1.0-1.5 ms, 1.3-2.0 MB |
| `(doc seon.task/create!)` | `:in` with registered keys replaced by their schemas (`agent-documentation-contract`, `eval.clj:1966-1995`); supplied keys not removed; 1,628 tokens | JVM 1.5-13 ms, 2-4 MB |
| `(doc seon.task)` | docstring parts plus `:in [] :out []` | 2 ms / 59 calls |
| `(doc :seon.task/intent)` | contract refusal "expected a symbol, got a keyword" | 11 ms |
| `(doc clojure.string/split)`, `(doc <task>)`, `(doc <test>)` | `documentation-unavailable` | |
| `(dir seon.task)` | `directory-value` (:2019) computes contracts per function, then `seon.repl/render-directory-ai` (`src/seon/repl.clj:36-55`) drops them; shown as one quoted string (`:large-value`, 2,290 value tokens) | 93 ms / 9,026 calls; JVM 30-62 ms, **48-90 MB** |
| `(dir 'seon.task)` | refused "expected a symbol, got a sequence" (Clojure also rejects it) | 19 ms |
| `(clojure.repl/dir-fn 'seon.task)` | 31 names | 1 ms |
| `(clojure.repl/source x)` | nil, text on `:out` | 1 ms / 74 calls |
| `(clojure.repl/source-fn 'seon.id/symbol-in)` | stored source string, printed with escaped quotes | 3 ms / 3,872 calls; JVM 0.13 ms |
| `(clojure.repl/source-fn 'clojure.set/union)` | nil | |
| bare `source`, `apropos`, `find-doc` | unresolved | |
| `(clojure.repl/apropos "symbol-in")` | `(seon.id/symbol-in)` | 8 ms |
| `(clojure.repl/find-doc "symbol-in")` | nil, prints 522 characters | 14 ms |
| `(help)` | `{:seon.help/lines […]}` + render pair `render-help-ai` (joined string), shown as one quoted string, 1,105 tokens | 17-25 ms / 437 calls; JVM 17-20 ms, **19.6-20.2 MB** |
| `(my.program/source 'seon.id/symbol-in)` | map with source, doc, contract, file line (reads the file) | JVM 4.3 ms, 5.1 MB |

S0 probe window (`seon.pass-probe/window` over `mcp-pass`; whole-JVM allocation, other lanes add noise):

| Pass | wall ms | allocated | checked calls | store bytes | tokens |
|---|---|---|---|---|---|
| `(+ 1 2)` (first / last of the run) | 107 / 56 | 156 / 77 MB | 127,176 / 10,472 | 206 / 215 KB | 18 |
| `(doc seon.id/symbol-in)` | 92 | 148 MB | 38,069 | 322 KB | 115 |
| `(dir seon.task)` | 192 | 298 MB | 101,700 | 371 KB | 1,648 |
| `(help)` | 89 | 127 MB | 21,662 | 300 KB | 1,207 |
| `(clojure.repl/source-fn 'seon.id/symbol-in)` | 64 | 93 MB | 14,643 | 268 KB | 127 |

`dir` and `help` exceed the 10 MB line (task `seon.sci.eval/dir-and-help-allocate-tens-of-megabytes-per-call`).

### 1.3 SCI var metadata vs Datahike facts

- Program namespaces are acquired into SCI as copies of host Vars (`copy-host-var`, `eval.clj:236`); library
  namespaces on first `require` (`load-host-namespace`, :251). `sci/copy-var*` (`reference-code/sci/src/sci/core.cljc:112-136`)
  keeps `:name :ns :macro :doc :arglists :dynamic :private :tag :file :line :column` and drops `:malli/schema`, so a
  compiled program Var shows no contract in SCI metadata while an agent's own `defn` keeps it. The DB `:seon.fn/spec`
  stays the contract source; whether `copy-host-var` should carry the schema is the contract slice's question.
- Exception: `sci.interrupt/clojure-string` copies (`reference-code/sci/src/sci/interrupt.cljc:308-315`) carry
  `{:ns clojure.core :name split}`, no doc, no arglists, and `replace-first` is created as `replace`. Producer is the
  SCI fork; task `seon.sci.eval/sci-interrupt-string-vars-carry-core-metadata`.
- Test Vars do not exist in SCI (Opus probe), so a test's `doc` reads the DB.
- The DB holds what SCI cannot: `:seon.fn/spec` and supplied keys (4 ms / 1 MB), `:seon.schema/form` (1 ms), tasks,
  test results (`latest-results` 0.16 ms).

### 1.4 Callers

- `seon.render.ns/directory` (`src/seon/render/ns.clj:235-249`) calls `directory-value` for the namespace render's
  rows; `seon.render.ns/namespace-form` (:81) emits `(dir 'ns)`; `seon.run` (`run.clj:84`) emits `'(dir 'my.turn)`.
- `seon.agent.loop` (unwired; only `test/seon/agent/loop_test.clj`) calls `documentation-value`, `directory-value`,
  `stored-source`, `help-value`. Root ruled it deleted in S1.
- `my.program/source` has no `src` caller except `(help)`'s text; `my.program/declaration` (:837) is also used by
  `declaration-pair` (:981) and stays.
- `seon.render.ns/function-ai` (:192-206) claims its `:seon.render.ns/source-lines` are "text the printer shows
  unescaped"; no printer code does that (`rg source-lines|unescaped`), so the claim is false.

### 1.5 Probes for option B (SCI, agent `s1-lookups-2`, a nested `sci/init` built from the agent's own context)

- `(sci/copy-var* (clojure.lang.RT/var "clojure.repl" "doc") repl-ns)` then `(sci/init {:namespaces {'clojure.repl {'doc copied}}})`:
  SCI's other `clojure.repl` Vars survive (`dir-fn` answers, per-var `merge-with merge`, `sci/impl/opts.cljc:21`), but
  calling the copied host macro fails: "Attempting to call unbound fn: #'clojure.repl/doc". So a declaration whose
  target is `clojure.repl/doc` must never leave the host copy visible: the lookup Vars are added after the injected
  namespaces (§4.1).
- After `(sci/add-namespace! c1 'clojure.repl {'doc data-doc})` and the same Var into `clojure.core`:
  `(clojure.repl/doc inc)`, `(doc inc)` and `(ns foo) (doc inc)` all answer the data; `(identical? (resolve 'doc)
  (resolve 'clojure.repl/doc))` is `true`; `(meta (resolve 'doc))` is `{:ns clojure.repl :macro true :name doc}`.

## 2. Duplicates

| Thing | Done by |
|---|---|
| bare-name installation | declarations, `build-base-ctx`'s two hand lists, `install-program-doc!`, acquisition's `help` line, stored `:seon.ns/refers` |
| a namespace's public names | `program-documentation` (`eval.clj:1835`), `render.ns/public-functions` (`ns.clj:219`), SCI `dir-fn` |
| a var's doc and arglists | `function-doc-map` + `arglists-reader` (re-reads stored text), SCI var meta, `my.program/source` |
| a declaration's source | `stored-source`, `my.program/declaration`+`source`, SCI `source-fn` |
| contracts in `dir` | computed by `directory-value`, discarded by `render-directory-ai` |
| a task as data | `task-ai`, `held-task-ai`, and v1's proposed `task-doc` (dropped, ruling 6) |
| help's tools line | `help-value`'s join and `seon.agent.loop/system-message` |

## 3. Library seams (SCI `ce0aa53b9b`, `sci/impl/namespaces.cljc`)

- `dir-fn` (:2303-2309): names of a namespace, alias resolved; throws on an unknown namespace, so check `sci/find-ns`.
- `apropos` (:2369-2382): sorted qualified symbols; already data (1.7 ms for "task"; Opus: "a" → 1,255 in 4.2 ms).
- `find-doc` (:2351-2367): walks `sci-all-ns` × `sci-ns-interns*` metadata plus namespace metadata, keeps entries
  with `:doc` matching name or doc, then `print-doc`s. The data adaptation keeps the walk and returns summaries
  (prototype: 490 namespaces / 4,897 interns, 20 ms).
- `source-fn` (:2409-2431): reads `:file` with `jio/file`, else calls the load-fn with only `{:namespace …}`, which
  `load-host-namespace`'s contract (`eval.clj:263`) would refuse (statically; UNVERIFIED live). Clojure's own
  `clojure.repl/source-fn` reads library source from the classpath (0.6-1.1 ms, 1.3 MB).
- `doc` (:2337-2349): `resolve`, else namespace; Seon keeps that order and returns data.

## 4. The smallest composition

### 4.1 Installation: SCI's `clojure.repl` holds the lookups (ruling 1)

- The five lookups are built once in `seon.sci.eval`: `doc`, `dir`, `source` as SCI macro Vars
  (`sci/new-macro-var`, `:ns` SCI's `clojure.repl`), `apropos` and `find-doc` as SCI Vars over plain functions, and
  `source-fn` over `source-value`'s string. They are added to `build-base-ctx`'s `sci/init` options under
  `[:namespaces 'clojure.repl]` after the injected namespaces, beside today's `future-call`/`pmap` entries, so the
  injected host copies never stay visible (§1.5). They are platform code and never change on a branch, so
  acquisition does not reinstall them.
- `seon.sci.binding.edn`: retarget `:seon.sci.binding/doc` and `/dir` to `clojure.repl/doc|dir`; add `/source`,
  `/apropos`, `/find-doc` targeting `clojure.repl/*`. Analysis then names what evaluation runs. `help`, `deftest`,
  `is` stay. Alternative to the wasted five host copies: skip targets in `clojure.repl` in
  `base-context-injected-symbols` (2 lines); the ordering alone is correct, so S1 takes the ordering and no skip.
- `refer-declared-bindings!` (new, ~8 lines, contracted): refers every declared bare name into `clojure.core` from
  the context's resolved target. Called by `build-base-ctx` (replacing the two hand lists at :412-429, keeping the
  native-mutation entries) and by acquisition (replacing the `help` line at :2566-2568, needed because `help` stays a
  program Var).
- Deleted: `install-program-doc!` (and its call at :2391), `program-doc-var`, `program-dir-var`, `stored-source-fn`
  (body moves into `source-fn`), the `seon.bootstrap` re-adds, `seon.bootstrap/dir` and `seon.bootstrap/doc` macros.
- Bare and qualified names are one Var (verified, §1.5): `(clojure.repl/source x)` and `(source x)` return the same
  data. The lookup Vars are platform code: they are never replaced at acquisition, so there is no second list to
  refresh for the qualified spelling (Astra P2). The only declared target that changes at acquisition is
  `seon.bootstrap/help` (a program Var); `refer-declared-bindings!` points bare `help` at the acquired Var at the same
  boundary where acquisition installs `seon.bootstrap`, and `seon.bootstrap/help` itself is the acquired Var. The
  retained-context tests are rewritten around that surviving behavior (the old one asserted a fresh macro
  constructor's identity, which is deleted).

### 4.2 Stop storing interpreter refers (ruling 2, task `seon.fn/analysis-stores-interpreter-refers-on-namespace-entities`)

- Producer: `runtime-analysis-batch` augments the stored namespace entity with `interpreter-refers` to build the
  analysis `ns` form (`fn.clj:976-1001`); the analysed namespace entity then stores those refers (`fn.clj:252`) and
  that synthetic form as `:seon.ns/source` (probe in §1.1).
- Fix: the namespace row stores only what the namespace itself declares. `namespace-entity` already receives the
  projection; it drops refers equal to a declared interpreter binding (`interpreter-refer-entities projection`), and
  the stored `:seon.ns/source` of a runtime namespace is the form without them. Analysis keeps seeing them.
  Seam to confirm at implementation (claim 5): whether the source drop is cleanest by building the stored form from
  the unaugmented entity, or by passing the interpreter refers to the analysis as a separate top-level `require`
  form after the `ns` form (only if clj-kondo resolves top-level `:refer` into the namespace context).
- Consumers: namespace installation (`eval.clj:825-848`) then never sees a declared name in a namespace's refers, so
  bare lookups resolve through `clojure.core` in every namespace; `seon.test` change selection (`test.clj:626-640`)
  reads refers as data and is unaffected.
- Write-back: checked statically. It writes `:seon.ns/source` by exact span (`seon.cluster.source` :1118-1136) and
  never reads `:seon.ns/refers`. Today a written-back runtime namespace would carry the synthetic
  `[seon.bootstrap :refer [help dir doc]]` require, which fails to load once `seon.bootstrap/doc` is deleted; the
  source fix removes that. Whether any runtime namespace is written back today is UNVERIFIED.
- Root resets default after S1 lands; stored rows are not migrated.

### 4.3 `seon.sci.eval` data functions

| Function | Does | Size |
|---|---|---|
| `reference-in` (new) | input: whatever the agent typed (`authored-reference`); a qualified symbol with its alias expanded through the current SCI namespace (not var meta, because of the interrupt copies); a simple symbol that resolves to a Var as that Var's symbol (SCI's public `var->symbol`, `sci/core.cljc:698-703`); anything else unchanged (so `(doc 'x)` stays the list `(quote x)`) | ~12 lines |
| `directory` (new) | `sci/find-ns` of the alias-expanded name, else `documentation-unavailable`; `dir-fn` names → `[{:sym :arglists :summary}]` from SCI var meta, summary by `docstring-parts` | ~10 lines |
| `apropos` / `find-doc` | SCI `apropos` as a vector; SCI's `find-doc` walk (interns with `:doc`, plus namespaces) returning `var-summaries` | ~4 + ~12 lines |
| `source-value` (new) | `stored-source` (program families, private and tests included, branch-correct), else Clojure's `clojure.repl/source-fn` when the reference names a host Var, else `documentation-unavailable`; shape in §4.4 | ~8 lines |
| `documentation-value` (rewritten, same 3-arg signature; both reference inputs are `authored-reference`) | dispatch on the value: qualified keyword → schema `{:key :form :description}` (one pull of `:seon.schema/form`); simple symbol → namespace `{:summary :body :example :publics}` (program: `:seon.ns/doc` + one reverse pull; library: host ns meta + `dir-fn`; an alias alone is expanded by `reference-in` first); qualified symbol → test (below), task (below), else the Var's SCI metadata for doc parts and arglists, plus, for a program function, the DB contract, supplied keys and override note exactly as today (`agent-documentation-contract`, ruling 3); anything else (a list, a number, a string, an unknown name) → `documentation-unavailable` naming what was typed | ~20 + ~40 lines |

- **Doc and arglists from SCI (ruling 4).** For every Var, `:summary/:body/:example` come from `docstring-parts` of
  `(:doc (meta v))` and `:arglists` from `(:arglists (meta v))`. `function-doc-map` stops calling `arglists-reader`;
  the DB is read only for the contract, supplied keys and override note. A program row with no SCI Var (not acquired)
  answers its arglists as the typed unknown `declaration-absent`.
- **A task (ruling 6; Astra P1 custody).** Tasks live at their task home (`seon.task/home`, `src/seon/task.clj:340-348`:
  the home branch's connection seen from a database, nil when that database is the home), as `my.task` already
  reads them (`src/my/task.clj:78,106`). Astra's probe: a task created after a branch forked is absent on the branch
  and present at the home. So `(doc task-sym)` is ONE pull at the home:
  `(db/pull (if-let [home (task/home database)] (db/db home) database) '[*] [:seon.task/sym sym])`. The value
  printer selects the schema's declared pair, `seon.render.ns/task-ai` (verified: a pulled task shows `task-ai`'s
  fields, its `:seon.task/must-read` set and related data). `must-read` is part of the pulled task, so the
  references the opening needs are there; the agent calls `(doc x)` on each. No `task-edges`, no subject walk, no
  copy of the task onto the branch. The render's related data reads the viewer's database, so reverse task tags
  (subjects, proofs) come from the worker's program world. The task is read from the home's current value: the
  opening must see the current revision; a deliberate historical read is an ordinary `seon.db/pull` on the
  historical value the agent holds, not a `doc` mode. A symbol that names a task at the home and a Var on the
  branch is a Var first (the dispatch order), since a task symbol is `ns/task-name` and never a defined Var.
- **A test (Astra P1 proof).** Test Vars are not in SCI, so a test's `doc` reads the DB. It returns
  `{:sym t :recorded (seon.test/recorded-result db t) :verified? (seon.test/verified? db t)}`: the latest admitted
  result as the owner records it (or its typed unknown), labelled recorded evidence, beside the proof owner's
  current verdict (`src/seon/test.clj:1562`: positive passes, input digest, current reach digest, source and
  fixture rules). No green predicate in the lookup owner; `verified?`'s refusal value is shown as returned. Priced:
  0.15 ms / 0.02 MB and 0.02-0.04 ms / 0.02 MB on a test with no recorded execution; the cost on a test with a
  recorded result is measured at implementation (claim 8).
- **Contracts (Astra P1).** `:seon.sci.eval/authored-reference` is the one polymorphic boundary (whatever follows a
  lookup macro, with the declared exemption and reason, as `:seon.schema.admission/polymorphic-boundary` entries
  do); `:seon.sci.eval/reference` is `[:or :symbol :qualified-keyword]` for what `reference-in` resolved;
  `:seon.sci.eval/lookup-pattern` is `[:or :string <named regex predicate>]` (SCI's `apropos` takes either and
  `find-doc` calls `re-pattern`, so `#"^symbol-in$"` and `""` are admitted); `:seon.sci.eval/var-summary`'s `:sym` is
  `:symbol`, since `find-doc` also lists namespaces (`namespaces.cljc:2359-2361`). Malli checks inputs before the
  body (`malli/core.cljc:2210-2245`), so these are input schemas, not body fallbacks.
- **Errors.** One variant: `:seon.sci.eval/documentation-unavailable`'s member widens to `authored-reference`, so
  keywords and lists use it too (no separate `schema-unavailable-error`). Touched constructors move to the installed
  `seon.error.refusal/error`.

### 4.4 `source` returns code that prints as code (ruling 7)

- What exists: the value printer already prints a render's text at the root as REPL text, unescaped
  (`seon.print/leaf-ops` `::projected`, `src/seon/print.cljc:478-484`; `seon.render.value/make-node`'s `text` branch).
  A value's own `:seon.render/ai` holding rendered data (a string) selects it. Probes: `{:a 1 :seon.render/ai
  "(defn f\n  \"Doc with \\\"quotes\\\".\"\n  [x] x)"}` shows `(defn f "Doc with \"quotes\"." [x] x)` on its lines,
  and a body with `;; a comment`, `#(inc %)`, `@(atom 'x)` shows exactly as written.
- So the smallest general change is no printer change: `(source x)` returns
  `{:seon.sci.eval/sym s :seon.render/ai text}`, the master override with rendered data, carrying the text once
  (amended for the printer design, `printer-text/design-notes.md`, `e0eca860a`: a separate `:source` key printed the
  source twice, both escaped, when the value is nested). The source prints whole as code at the root; nested in
  another value it prints as data, which is the printer's no-re-dispatch rule; the text is read with
  `(:seon.render/ai result/e…)`. `clojure.repl/source-fn` keeps answering the bare string for
  agents composing it.
- Not done: a printer rule that prints multi-line strings raw. It would make a string indistinguishable from code
  everywhere, which is a general regression, and a schema branch for "source" is ruled out.
- The printer slice (printer-text) owns what this relies on: the reply keeps root render text raw (it deletes
  `seon.repl/data-text`, the converter that quoted `(help)`'s many-form text today), and a render text over the
  budget is cut at a line end with a dig form. S1 changes no printer or reply file and lands after, or with, that
  slice.
- `function-ai`'s docstring claim ("text the printer shows unescaped") is deleted in S1 (`render/ns.clj` is touched).

### 4.5 `(help)` as data with quoted forms (ruling 5)

`seon.bootstrap/help-value` takes no inputs and returns `[:seon.help/entry …]`, each
`{:seon.help/says "one short sentence" :seon.help/forms [quoted forms]}`; no database read, no render pair
(`render-help-ai`/`-html` deleted; `seon.help.edn` redeclared). Prototype printed clean and runnable:

```clojure
[{:seon.help/says "Look things up; each answers data."
  :seon.help/forms [(doc x) (source x) (dir ns) (apropos "text") (find-doc "regex")]}
 {:seon.help/says "Define a function with its contract; deftest and is are referred."
  :seon.help/forms [(defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))
                    (deftest increments (is (= 2 (increment 1))))]}
 {:seon.help/says "Run the tests that name or reach what you changed, on your branch."
  :seon.help/forms [(my.test/check {:seon.test/changed [my.ns-test/a-test]})]}
 {:seon.help/says "Send a message." :seon.help/forms [(my.message/send #:my.message{:to "root", :content "..."})]}]
```

Plus entries for result handles (`result/e…` with `get-in`), errors as data, and `seon.db/q|pull|transact!`. The
reader entry teaches "Send code in ```` ```clojure ```` fences" (true today: `segments` reads a fenced form and
makes prose before it a comment). The full rule (outside fences a form starts a line; a lone word is prose) is
TARGET: S2b-1's design is committed (`0df0480b2`) but not landed; whichever of S1 and S2b-1 lands second adds it.
Today a trailing `Done.` after a fence still evaluates as a symbol, so help must not claim prose is safe.

Dropped (false or slated for deletion): the prompt-namespace line, "Markdown is not prose here", "results arrive in
your NEXT turn", `seon.turn/turns-left`, `my.agent/done`, `my.program/source`, the `my.task` step tutorial and the
Tools line (closes `seon.bootstrap/help-lists-no-tools-for-an-agent`). With no reads help cannot go stale, so
`help_test/help-evidence-tracks-its-code-version` (red on HEAD, `seon.bootstrap/help-evidence-test-red-on-head`) is
deleted with its subject. The class regression walks `:seon.help/forms` and resolves every list head (excluding
`defn`/`deftest`-introduced names) in a fresh agent world, with no parsing. Prototype: `doc`, `dir`,
`my.test/check`, `my.message/send` resolve; `source`, `apropos`, `find-doc` resolve after S1.

### 4.6 Converted callers (rulings 3, 7, 8)

- `seon.render.ns/directory`: no contracts, one pull. `public-functions`' reverse pull (`ns.clj:219-232`) extends to
  `[{[:seon.fn/_ns :limit nil] [:seon.fn/sym :seon.fn/private? :seon.fn/doc :seon.fn/arglists]} {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}]`;
  rows `[:sym :arglists :summary]`. Arglists text is read by `seon.sci.reader/read` with that pull's aliases
  (`arglists-reader` takes the aliases instead of pulling them). `members` reuses the same pull. No
  `documentation-value` loop, no contract compile.
- `seon.render.ns/namespace-form` (:81) → `(list 'dir (:seon.ns/name unit))`; `run.clj:84` → `'(dir my.turn)`. Closes
  `seon.turn/generated-opening-reads-fail-when-evaluated-as-written` for these producers.
- `function-ai` docstring corrected (§4.4).

### 4.7 Deletions

- `seon.sci.eval`: `directory-value`, `program-documentation`, `install-program-doc!`, `program-doc-var`,
  `program-dir-var`, `stored-source-fn`, the namespace doc's `:in [] :out []`, `arglists-reader`'s pull.
- `seon.repl`: `render-directory-ai`, `render-directory-html`; `seon.repl.edn` `:directory`.
- `seon.bootstrap`: `dir`, `doc` macros, `render-help-ai`, `render-help-html`.
- `my.program`: `source`, `file-line`, `nearest`; `test/my/program_read_test`'s two source tests; the task at
  `resources/seon/tasks/my.program.edn:59` closes with its subject. `declaration` and `search` stay; the `search` and
  `declaration-diff` docstrings (:918, :1039) stop naming `my.program/source`. Deleting `nearest` is a BEHAVIOR
  CHANGE, not an equivalent replacement: it reverse-matched substrings and ranked a capped set, while `apropos`
  matches forward and unranked; the not-found answer becomes `documentation-unavailable` and the agent runs
  `(apropos …)`.
- `bootstrap/render-help-html`'s embedded EDN scan of help lines (`bootstrap.clj:73-97`, a reader) goes with it.
- Task must-reads naming deleted producers: `resources/seon/tasks/seon.sci.eval.edn:123`
  (`seon.sci.eval/program-documentation`), :190 (`documentation-value`/`directory-value`), :204 (`directory-value`),
  and `seon.documentation.edn:4` (`help-value`, signature changes). Each is retargeted to the surviving owner or the
  task closes when S1 does what it asks (`an-agent-cannot-read-the-private-code-it-must-change`,
  `dependency-namespaces-have-no-doc-or-dir-in-an-agents-repl`, `dir-shows-each-uncontracted-def-as-two-timestamped-errors`
  are done by S1's `doc`/`dir` and get S1's table rows as proofs). No dangling references at publication.
- `seon.agent.loop` (230 lines) and `test/seon/agent/loop_test.clj` (75); `resources/seon/schemas/seon.agent.loop.edn`
  retired, published and adopted on a live-store branch before landing; intents corrected in
  `resources/seon/tasks/seon.task.edn:15` (drop the `task-context` sentence) and `seon.bootstrap.edn:13` (task closed
  by S1); the `reference-code.md:48` sentence naming `seon.agent.loop`; walk-delete's
  `design-notes.md:24,108,134` and `walk-delete-notes.md:12` marked stale (the S4 seam is the proc-held transcript).
- Schemas: `seon.sci.eval.edn` gains `reference`, `lookup-pattern`, `var-summary(ies)`, `docstring-parts`,
  `namespace-doc`, `schema-doc`, `source-value` (`[:map :seon.sci.eval/sym :seon.render/ai]`); `seon.help.edn` redeclares `help` as a vector of entries;
  `seon.sci.binding.edn` retargets two and adds three declarations. All published and adopted together on a
  live-store branch.

### 4.8 What disappears and why no owner already does it

Disappears: four installers and the stored snapshot, two SCI macro Vars and two host macros, the per-call contract
compile in `dir` and the namespace render, a directory render pair, a second DB name index, the stored-arglists
re-read in `doc`, `my.program/source` with its disk read, help's two queries and render pair, and the unwired
`seon.agent.loop`. SCI owns names, metadata, `apropos`, the `find-doc` walk and `clojure.repl`; the printer owns
code-as-text at the root. Only contracts, schemas, tasks and test status need Seon's reads. Estimated net: `src`
about -560 / +170 lines (with `seon.agent.loop`), tests about -75 / +60 plus conversions.

## 5. Alternatives

1. **Option A (v1): lookups as host definitions in `seon.bootstrap`.** Rejected by ruling 1: two names for
   qualified and bare, and `seon.bootstrap`'s other contents are the system turn S4 deletes.
2. **Skip `clojure.repl` targets in `base-context-injected-symbols`** instead of ordering. Two lines, saves five
   wasted copies at base build; not needed for correctness.
3. **Show contracts in the namespace render** by keeping per-function `agent-documentation-contract`: 41-62 ms and
   48-90 MB per render (Opus). Rejected by ruling 3; contracts are one `(doc sym)` away.
4. **A printer rule for raw multi-line strings.** Rejected (§4.4).
5. **A fallback to host metadata for library Vars lacking `:doc`.** Rejected; the producer is the SCI fork.

## 6. Risks and the tests that reach them

| Risk | Test |
|---|---|
| bare and qualified lookups are one Var in every namespace, fresh or stored, before and after acquisition | new table rows in `sci/documentation_test`; `retained-context-receives-the-current-repl-macro-through-its-core-alias` rewritten around the surviving behavior (bare `help` is the acquired `seon.bootstrap/help`, including a branch-interpreted one, in fresh and retained contexts; lookups identical bare and qualified); `bare-test-macros-resolve-without-namespace-referrals` converted to `refer-declared-bindings!`; `retained-context-receives-new-bare-test-referrals` kept |
| analysis names `clojure.repl/<name>` for each lookup | table rows asserting stored `:seon.fn/call-arities` for a bare call, a qualified call and a `(:require [clojure.repl :refer [doc]])` caller |
| task custody and references (Astra P1) | new `sci/documentation_test/task-doc-reads-home-declaration-and-branch-proofs`: a task created after the branch forked is found; a task revised at home shows the revision; a task with `must-read` and no reverse tags shows its `must-read`; reverse-tagged proofs come from the branch program, with no home result copied |
| a stale green is never certified (Astra P1) | new `sci/documentation_test/lookup-does-not-certify-a-stale-test-result`: green result, then a change to a reached definition: `:verified?` false while `:recorded` shows the old green as recorded; an incomplete latest execution never exposes an older green |
| contract rows (Astra P1) | rows in the lookup table: `(doc 'x)` and `(doc 42)` answer `documentation-unavailable`; `(apropos #"^symbol-in$")`, `(apropos "")`, `(find-doc "")` admitted; a namespace with a docstring appears in `find-doc` |
| stored namespaces carry no interpreter refers and their source no synthetic require | new row in `test/seon/fn_test` or the owning analysis test: define in a fresh namespace, pull `:seon.ns/refers` and `:seon.ns/source` |
| each lookup kind answers data | new `sci/documentation_test/each-lookup-answers-its-reference-kind-as-data`: program fn, private fn, library var, aliased var, own SCI `def`, program ns, library ns, schema keyword, unknown keyword, task (renders `task-ai`), test, unknown symbol, quoted symbol, program/test/library source (`:seon.render/ai` text), `dir` program/library/unknown, `apropos`, `find-doc` (asserts `:out` empty) |
| `dir` sees a function defined after acquisition | `directory-observes-a-function-installed-after-context-acquisition`, converted to the vector shape |
| help names a form that does not resolve | new `help_test/help-names-only-installed-forms`: walk `:seon.help/forms` heads in a fresh agent context (proves heads resolve, not that the prose is true; the reader wording stays S2b-1's) |
| existing tests kept green or converted (Astra list) | `sci/documentation_test`: `directory-observes-a-function-installed-after-context-acquisition`, `documentation-is-returned-data-without-a-second-printed-copy`, `source-at-the-agent-repl-prints-the-stored-declaration` (now data); `render.ns-test/a-namespace-renders-as-one-graph-node`; `render.web-debug-test/passive-ledger-never-reconstructs-directory` (after S2a) and `saved-history-preserves-shown-text-with-numeric-lookups`; `help-test/an-agent-identity-offers-help-as-its-operation`; loop tests retire with their namespace and schema |
| namespace form evaluates | `render/ns_test` row: evaluating `namespace-form`'s output answers the directory; `my/turn_test.clj:82`, `render/history_test.clj:68,167`, `turn_test.clj:1080-1083` convert from `(dir 'ns)` |
| source prints as code in the reply | one row asserting the reply's shown text for `(source seon.id/symbol-in)` equals the stored source (printer-face assertion, declared golden) |
| root render text shown raw in the reply, cut at a line end over budget | owned and proved by the printer-text slice (`seon.repl/data-text` deleted); S1's row asserts only `(source x)`'s value shape |
| other reaching tests (Opus finding 10) | `agent_situation_test.clj:49-53` (`(macroexpand '(seon.bootstrap/help))`), `cluster/program_restart_test.clj:324` (`(macroexpand-1 '(help))`), `turn_work_test.clj:390`, `rereads_test.clj:140` (`(doc my.task/done!)`), `sci/render_source_test.clj:55`, `repl_parity_test` family D (D1 now answers the agent macro's doc; D5, D6, D9 pass as data; D2, D7, D13 convert), `render/value_test` :64-77 and :851-864, `data_shapes_test` :270, `repl_test` :31, :201, :267, :379, `render/ns_test` :78, `sci/eval_test` :1236-1238, :1289, `directory_test`, `supplied_documentation_test`, `my/program_test` :135, `repl_grammar_test` :84-112, `html_views_test` :78-81 (+ `fixtures/html_views_ai.edn`), `sci/supplied_database_test` :19, `run4_install_test` :83, `loop_proof_test` :463, :488; fixtures `reader_fences.edn`, `run4_replies.edn`, `forgive_replies.edn`, `schema/datahike_parity.edn`; `resources/seon/tasks/seon.documentation.edn:4` (must-read `help-value`, signature changes) |
| `web_debug_test/passive-ledger-never-reconstructs-directory` | S2a lands first and converts it; S1 rebases onto S2a |
| file ownership | `src/seon/sci/eval.clj` is claimed by s5a-reply; `src/seon/task.clj` by file-reader (read only here); a `printer-text` lane exists with an empty claim (S1 changes no printer file) |
| interrupt `clojure.string` copies show no doc | documented row; fixed by the SCI fork task |

## 7. Expected S0 numbers after

S1 changes no pass stage, so the ordinary, definition and after-definition passes are unchanged (any change over
20% is a regression to explain). Lookup passes (same window, warm):

| Pass | today | expected |
|---|---|---|
| `(dir seon.task)` | 192 ms, 298 MB, 101,700 calls, 1,648 tokens | ≈ `(+ 1 2)` + 1 ms (SCI prototype 0.8 ms); ~31 rows |
| namespace render of `seon.task` (JVM) | 41-62 ms, 48-90 MB | one pull (1.6 ms, 1.8 MB measured for the reverse pull) plus 31 short reads |
| `(help)` | 89 ms, 127 MB, 21,662 calls, 1,207 tokens | ≈ `(+ 1 2)`; ~350 tokens (estimate) |
| `(doc seon.task/create!)` | first 20 ms / 9,407 calls, warm 2-3 ms / 308 | unchanged in S1 (contract slice later), minus the arglists re-read |
| `(doc <task>)` | `documentation-unavailable` | one pull at the task home (≈1-2 ms; Astra's home pull 2 ms) plus the `task-ai` render; no `task-edges` |
| `(doc <test>)` | `documentation-unavailable` | `recorded-result` + `verified?`; measured at implementation on a test with a recorded result |
| `(source x)` | unbound | ≈ `source-fn` today (64 ms pass, 127 tokens) |
| `(find-doc "symbol-in")` | prints, nil | ≈ 20 ms evaluation, data |

## 8. The stopped patch, judged

Keep: the per-kind `doc` functions for schema and namespace, `reference-in`, `source-value`, SCI-based
`directory`, the `find-doc` walk (over interns plus namespaces, as SCI's own), the `seon.repl` deletions, the table
test. Change: its installer becomes base-context-only (§4.1); `task-documentation` and `task-doc` are replaced by the
task's declared render read at the task home (ruling 6, Astra P1); `proof-status`/`proof-statuses` and their schema
are dropped for the owners' `recorded-result` and `verified?` (Astra P1); its `reference`, `lookup-pattern` and
`var-summary` schemas are corrected (Astra P1); `render.ns/directory` reads one pull (ruling 3);
`schema-unavailable(-error)` folds into the widened variant; `documentation_test`'s two lint errors (a removed
`seon.schema` require still used). Add: §4.2, §4.4, §4.5, the `my.program/source` and `seon.agent.loop` deletions,
the analysis-edge and stored-refers rows. Never loaded or run; nothing in it is evidence.

## 9. Claims the implementation needs (verify at the REPL first)

1. SCI macro Vars added under `[:namespaces 'clojure.repl]` in the `sci/init` options after the injected namespaces
   win over the host copies and survive `sci/fork` (§1.5 verified the `add-namespace!` form, not the init-options
   form).
2. `refer-declared-bindings!` at acquisition picks up the acquired `seon.bootstrap/help` Var.
3. `sci.ctx-store/get-ctx` is bound when the `apropos`/`find-doc` functions run inside an evaluation.
4. clj-kondo resolves the retargeted refers to `clojure.repl/doc|dir|source|apropos|find-doc` and records the edges
   under those names; how it treats the macro arguments (`(dir seon.task)`) as usages is observed, not assumed.
5. The interpreter-refers drop (§4.2): which seam leaves analysis unchanged and the stored source free of them.
6. `edn/read-string` of every stored `:seon.schema/form` succeeds (census over default).
7. Closed: the printer-text slice keeps root render text raw and cuts it at a line end (its design, `e0eca860a`).
8. `seon.test/verified?` on a test with a recorded result costs well under the ordinary-pass budget (only a test
   with no execution was priced here).
9. `seon.task/home` from an agent's branch value answers the home connection; from the home itself nil, so the
   lookup reads the database it was given (Astra's probe read the home this way).

## 10. Findings recorded

- Created: `seon.sci.eval/an-uncontracted-defn-returns-no-warning` (`4fe2a5d2ba47`);
  `seon.render/a-renderer-symbol-naming-a-library-function-is-refused` (`c27e56a3dc36`);
  `seon.sci.eval/sci-interrupt-string-vars-carry-core-metadata` (`882787bf3c6c`);
  `seon.sci.eval/dir-and-help-allocate-tens-of-megabytes-per-call` (`53da36594add`);
  `seon.sci.eval/a-lazy-result-realized-at-print-resolves-outside-the-evaluation-namespace` (`f6f20c557956`):
  `(->> '[doc dir my.test/check] (map (juxt identity #(some? (resolve %)))))` answers all `false`, the `mapv` form
  answers `true`.
- Linked: `seon.fn/analysis-stores-interpreter-refers-on-namespace-entities` (`19fd54137a74`, Opus),
  `seon.turn/generated-opening-reads-fail-when-evaluated-as-written`,
  `seon.repl/directory-render-returns-text-the-printer-clips-mid-form`.
- Seen, not filed: a plain `def` whose init calls `sci.core` answers a `:seon.fn/dependency-analysis-missing-error`
  diagnostic ("clj-kondo holds no analysis of sci.core"; fix: `(seon.fn.analyzer/analyze-dependencies!)`), the
  documented stale-cache case. `seon.render.value/diagnostic-line` prints a failure with `pr-str` and `bounded-text`
  (`src/seon/render/value.clj`), a second printer; for the printer lanes.
- After I retired `s1-lookups`' branch, the agent id itself is refused ("Agent s1-lookups is retired: its admission
  closed"), so probes continue as `s1-lookups-2`.
- Health at probe time: default `:faulted`; open faults on `seon.turn.world/record-fault` (task `3d2fb1b41dc8`) and
  `seon.repl/entity-emission`; over-bound `seon.db/transact-call` 1,989 ms max and
  `seon.cluster.source/changed-identities` 1,621 ms. No RESET NEEDED from this lane.

## 11. Design check

v1 held for the data functions and the costs. It was wrong about installation (it missed the stored snapshot and
put the Vars where S4 would move them again), kept per-function contract reads in the namespace render, kept a
second arglists source in `doc`, invented a third task shape, and left help as escaped strings and `source` as an
escaped string. v2 followed root's rulings. v3 folds in the Astra review: task custody (one pull at the home, keeping
`must-read`), no lookup-made proof verdict (the owner's `recorded-result` and `verified?` side by side), and input
contracts that admit what the design promises. Still open: §4.2's exact seam (claim 5) and `verified?`'s cost on a
recorded result (claim 8); the reply-converter question is closed by the printer-text slice. Awkward: S1 cannot start code while s5a-reply holds
`eval.clj`, and it rebases onto S2a.

---
title: REPL lookup design
type: research
status: proposed
date: 2026-10-07
lane: repl-lookup
---

# REPL lookup: design (lane repl-lookup, 2026-10-07)

Assignment: an agent looks things up with Clojure's standard forms (`doc`, `source`, `dir`, `apropos`,
`find-doc`), bare in every namespace, returning data the value printer shows. Task context is generated as these
forms, so `doc` must also answer a schema keyword and a task symbol. Task
`seon.sci.eval/bare-source-and-apropos-do-not-resolve-in-an-agents-repl`.

Which step of the one happy path this serves: the opening context (`(help)`, the task's `doc`, its references'
`doc`/`source`, now generated as REPL sources by `seon.agent.loop/opening-sources` in loop-pass's working tree) and
every later agent lookup. Each lookup is one form, one read, one evaluation through the one REPL path; its value is
printed by the value printer.

Line numbers below are HEAD `439401ac3` unless marked "worktree".

## 1. What exists

### Data sources

- **SCI var metadata.** Program namespaces are acquired into the agent's SCI context as copies of the host Vars
  (`copy-host-var`, `src/seon/sci/eval.clj:236`); library namespaces are copied on first `require` by the load-fn
  (`load-host-namespace`, `:251`). Each copy carries the host Var's `:doc`, `:arglists`, `:file`, `:line`, `:ns`
  (probe: `(meta #'clojure.set/union)`, `(meta (resolve 'seon.task/create!))` both full). An agent's own
  interpreted override carries the metadata of its own `defn`. This is exactly what the agent can call.
- **Datahike program entities.** `:seon.fn/sym`, `:seon.fn/doc`, `:seon.fn/arglists` (text), `:seon.fn/spec`,
  `:seon.fn/arities` (input/output schema refs), `:seon.fn/source`; `:seon.test/sym`, `:seon.test/source`;
  `:seon.ns/name`, `:seon.ns/doc`; `:seon.schema/key`, `:seon.schema/form`; `:seon.task/sym`, `:seon.task/intent`;
  test results through `seon.test.runner/latest-results`. Branch-correct: an agent's branch edits are here.

### Every way an agent looks something up today (flow list)

1. `(doc x)` → `clojure.core/doc` refer → `program-doc-var` macro (`eval.clj:2079`) → SCI `resolve` + var meta to
   qualify → `documentation-value` (`:2056`, DB) →
   - qualified symbol: pull `program-documentation-selector` (`:1827`) → `function-doc-map` (`:1997`):
     `docstring-parts` (`:1886`) + `arglists-reader` (`:1916`, a pull of the ns aliases and a reader call to re-read
     the stored arglists text) + `agent-documentation-contract` (`:1966`: `call-preparation/supplied-map-entries`,
     `documentation-contract` compiled through the projection cache, `documentation-schemas` query, a postwalk);
   - simple symbol: pull `:seon.ns/doc` → docstring parts plus a bogus `{:in [] :out []}`;
   - anything else (keyword, library var such as `clojure.string/split`, task, test): refused by the contract or
     `documentation-unavailable`.
   Cost (default, `repl-lookup` agent): `(doc seon.id/symbol-in)` first call 25 ms / 11 011 armed calls, warm 2 ms /
   308 calls; root saw 383 ms on a cold call. The cold cost is the projection-cache compile of the contract.
2. `(dir x)` → `program-dir-var` (`:2095`) → `directory-value` (`:2019`, DB): a pull of the ns with aliases and
   schemas, `program-documentation` (`:1835`, one query pulling every public fn with its arities), then **per
   function** `arglists-reader` and `agent-documentation-contract`, plus `:schemas` → a map carrying the render pair
   `seon.repl/render-directory-ai` (`src/seon/repl.clj:36`) which **drops** `:in`, `:out` and `:schemas` again.
   Cost: `(dir seon.task)` 46-51 ms / ~9 000 armed calls warm, 120 ms as a reply, shown as one 2 290-token string
   (finding `:large-value`). The same 31 names from SCI's `dir-fn` + var meta: 3 ms.
3. `(clojure.repl/source x)` → SCI's `source` macro (`reference-code/sci/src/sci/impl/namespaces.cljc:2437-2443`)
   `println`s `(clojure.repl/source-fn 'x)`; our `stored-source-fn` (`eval.clj:2127`) replaces source-fn: SCI
   resolve + meta → `stored-source` (`:2106`, DB, every program family through `program/shapes-in`). Value nil,
   text on `:out`. Bare `source` does not resolve. Library source: nil.
4. `my.program/source` (`src/my/program.clj:874`): DB pull of a function or test (`declaration`, `:837`) plus doc,
   contract, file and file line (`file-line`, `:849`, reads the file from disk), not-found error with `nearest`
   (`:862`, scans every fn and test symbol). 9 ms. Taught by `(help)` (`src/seon/bootstrap.clj:56`).
5. `my.program/search` (`program.clj:914`): substring scan of every stored source; 125-211 ms / 8 166 calls. Searches
   source text, which no clojure.repl form does.
6. `apropos`, `find-doc`: not referred. `clojure.repl/apropos` resolves to SCI's own (`namespaces.cljc:2369`) and
   works (7 ms, a sorted seq); `clojure.repl/find-doc` is SCI's, which prints.
7. `clojure.repl/dir-fn`: SCI's (`namespaces.cljc:2303`), names only, alias-aware; works today.

### How the refers are built

- `build-base-ctx` (`eval.clj:350`) adds to `clojure.core`: `dir`, `doc`, `help` from the `seon.bootstrap` macros
  (`src/seon/bootstrap.clj:161-169`, which expand to `clojure.repl/dir`/`doc`), `deftest`, `is`, and the native
  mutation guards; and re-adds `seon.bootstrap/dir|doc|help` (`:425-429`).
- `install-program-doc!` (`:2137`), called at every acquisition (`acquire-program!`, `:2381`), adds `doc`, `dir`,
  `source-fn` to `clojure.repl` and `doc`, `dir`, `deftest`, `is` to `clojure.core` again. Two installers of the
  same refers; its `db` and `projection` arguments are unused.

### JVM-side callers (no SCI context)

- `seon.render.ns/directory` (`src/seon/render/ns.clj:235`): `directory-value` → columns `[:sym :arglists :doc :in
  :out]` for the namespace graph node. Its own `public-names` (`:224`) already pulls the public names.
- `seon.agent.loop` at HEAD (`tool-lines`, `function-units`, `namespace-unit`, `test-units`) called
  `documentation-value`, `directory-value` and `stored-source`. Loop-pass's worktree replaces them with generated
  REPL sources `"(doc sym)"` and `"(clojure.repl/source-fn 'sym)"` evaluated through the one path, so those direct
  calls disappear there.
- Tests: `test/seon/sci/documentation_test.clj`, `repl_parity_test.clj` family D, `render/value_test.clj` (64-77,
  839-864), `render/web_debug_test.clj` (281, 783-796, a `with-redefs` over `directory-value` and
  `render-directory-ai`), `data_shapes_test.clj:270`, `repl_test.clj:380`, `render/ns_test.clj:78`,
  `sci/eval_test.clj:259,309`.

## 2. Duplicates (evidence)

| Lookup | Done by | Evidence |
|---|---|---|
| a namespace's public names | `program-documentation` (`eval.clj:1835`), `render.ns/public-names` (`ns.clj:224`), SCI `dir-fn` | three readers of the same set |
| a var's doc and arglists | `function-doc-map` (DB, re-reads arglists text through the reader), SCI var meta (copied from the same host var), `my.program/source` `:doc` | same docstring three ways |
| a declaration's source | `stored-source` (`eval.clj:2106`), `my.program/declaration` (`program.clj:837`), SCI `source-fn` | two DB readers of `:seon.fn/source` |
| per-function contracts in `dir` | `directory-value` computes them, `render-directory-ai` discards them | ~9 000 armed calls per `(dir seon.task)` for nothing shown |
| the `doc`/`dir` refers | `build-base-ctx` + `seon.bootstrap` macros, and `install-program-doc!` | two installers, one redundant |
| a symbol near a typo | `my.program/nearest` | SCI `apropos` answers names by substring |

## 3. The library seams

SCI's `clojure.repl` (`reference-code/sci/src/sci/impl/namespaces.cljc:2303-2523`): `dir-fn` (names of a ns,
aliases resolved), `apropos` (sorted symbols over every SCI namespace, string or regex), `find-doc` and `doc` (walk
var metadata, then `print-doc`), `source-fn` (reads `:file` from disk with `jio/file`, else asks the load-fn for
`:source`), `source`/`dir`/`doc` macros that `println`. What Seon copied: the `doc`/`dir` macro shape and its own
`source-fn`. What SCI cannot give: a program function's contract and supplied keys, branch-correct source (SCI's
`source-fn` reads the checkout's file named by `:file`, which on a branch is stale; and its load-fn call omits `:ctx`,
which our `load-host-namespace` contract refuses: probe `(sci.impl.namespaces/source-fn 'clojure.set/union)` →
contract error), schema keywords, tasks, test status. Clojure's own `clojure.repl/source-fn` reads library source from
the classpath (`RT/baseLoader`), 2-6 ms.

## 4. Proposed change: the smallest composition

SCI's `clojure.repl` underneath; a thin data layer; Seon's DB only for what SCI cannot know. One installer.

| Form | Implementation | Seon part |
|---|---|---|
| `(dir x)` | SCI `dir-fn` names + SCI var meta → `[{:sym :arglists :summary}]` | `directory`, ~10 lines |
| `(apropos s)` | SCI `apropos`, `vec` | 4 lines |
| `(find-doc re)` | SCI's walk (`sci-all-ns`, `sci-ns-publics*`, meta), data instead of `print-doc` | ~12 lines |
| `(source x)` | program (fn, private fn, test): DB `stored-source`; library: Clojure `clojure.repl/source-fn` | `source-value`, ~6 lines |
| `(doc x)` | by kind: program function → existing `function-doc-map` (contract, supplied keys); namespace → `:seon.ns/doc` + public names (program) or host ns doc + SCI `dir-fn` (library); library var → SCI var meta; schema keyword → `:seon.schema/form` + description; test → status from `latest-results`; task → intent, subjects (`seon.task/task-edges`), proof statuses | new kinds ~60 lines |
| name resolution | `reference-in`: alias-expand a qualified symbol, resolve a simple symbol to its var | ~15 lines |

Deleted: `directory-value`, `program-documentation`, `program-doc-var`, `program-dir-var`,
`seon.repl/render-directory-ai`/`-html`, `:seon.repl/directory`, the `build-base-ctx` duplicate refers, the bogus
`{:in [] :out []}` on namespace docs; with loop-pass and owner agreement, `seon.bootstrap/dir`/`doc` macros
(loop-pass's file) and `my.program/source` + `declaration` + `file-line` + `nearest` (~55 lines; `(doc x)` and
`(source x)` replace it; `(help)` text converts). Changed: `install-program-doc!` takes only `ctx`, installs all five
plus `source-fn` into `clojure.repl` and `clojure.core`, called from `build-base-ctx` and acquisition (the retained
context test proves acquisition must refresh); `render.ns/directory` builds rows from its own `public-names` and
`documentation-value` per name. Added schemas (`seon.sci.eval.edn`): `reference`, `lookup-pattern`, `var-summary(ies)`,
`docstring-parts`, `namespace-doc`, `schema-doc`, `proof-status`, `task-doc`, `schema-unavailable(-error)` (a keyword
cannot be the `:symbol`-typed `documentation-unavailable` attribute).

Result and costs (measured equivalents, warm): `dir` 46-51 ms / 9 000 calls → ~3 ms (SCI); `apropos` 7 ms; `doc` of a
function unchanged (2 ms warm, the cold contract compile stays); `source` 2-9 ms. Expected net source lines: about
-170 deleted, +110 added, before `my.program/source`'s ~55.

Loop-pass: its generated `(clojure.repl/source-fn 'x)` can become `(source x)`; `doc` of the task symbol replaces its
own proof-status and failure query (HEAD `loop.clj` `test-units`).

## 5. Alternatives

1. **Only delete.** Refer SCI's `apropos`, `dir-fn`, `source`; delete Seon's `dir`. Smallest, but SCI's `doc`,
   `dir`, `find-doc`, `source` print to `:out` and return nil (violates "return data"), `source` reads stale files on a
   branch and fails on library jars, and `doc` cannot answer contracts, schemas or tasks, which task context needs.
2. **Everything from Datahike** (my first build, worktree): `dir`, `apropos`, `find-doc` over DB facts plus host
   `all-ns` for libraries. Branch-correct for program code but a second index of what SCI already holds; the host scan
   was 15-21 ms per call and duplicated SCI's `apropos`. Rejected after the owner's concern.
3. **Patch SCI's clojure.repl in our fork** to return data. Touches a dependency for behavior only Seon wants;
   SCI's `doc` would still not know contracts or tasks. Not worth a fork divergence.

## 6. Risks and the tests that reach them

- SCI meta vs DB divergence on a branch for `dir`/library `doc`: an agent's override is interpreted in SCI with its own
  meta, so `dir` shows what the agent can call; contracts still come from the DB through `doc`.
  `documentation_test/directory-observes-a-function-installed-after-context-acquisition`.
- SCI's interrupt copies of `clojure.string/split`, `replace`, `replace-first` carry `:ns clojure.core` and no doc
  (`reference-code/sci/src/sci/interrupt.cljc:308-315`, `copy-vars/new-var` default ns); `reference-in` alias-expands
  qualified symbols instead of reading var meta, so `(doc clojure.string/split)` still names the right var but shows
  no docstring. Task to file under the SCI fork.
- The refers must survive acquisition and retained contexts: `retained-context-receives-the-current-repl-macro-through-its-core-alias`,
  `retained-context-receives-new-bare-test-referrals`, `bare-test-macros-resolve-without-namespace-referrals`.
- Render consumers of `:seon.repl/directory` and stored `:seon.eval/renderer 'seon.repl/render-directory-ai` history:
  `render/value_test`, `web_debug_test`, `data_shapes_test`, `repl_test`, `ns_test` convert; stored history keeps
  its saved text (`:seon.eval/renderer` is a symbol value, not a ref).
- Proof: one table test through `support/agent-value`, one row per kind and form (worktree
  `documentation_test/each-lookup-answers-its-reference-kind-as-data`), plus repl_parity family D (D5, D6, D9 become
  passing; D2, D7, D13 convert).

## 7. What is already built (uncommitted, under my claims)

Worktree diff: `eval.clj` +226/-131, `seon.sci.eval.edn` +23, `repl.clj` -19, `seon.repl.edn` -11, `render/ns.clj`
+8/-4, `documentation_test.clj` +44/-87, `ns_test.clj` 1 line. Never loaded or run.

- **Keep**: the per-kind `doc` functions (schema, namespace, test, task, proof status), `reference-in`,
  `source-value` (DB then Clojure `source-fn`), SCI-based `directory`, `agent-apropos` (SCI `apropos`),
  `agent-find-doc` (SCI walk), one-arg `install-program-doc!`, the added schemas, the deletions in `seon.repl`, the
  table test.
- **Rework**: `render.ns/directory` should use its own `public-names` (current worktree calls `documentation-value`
  for the names); `build-base-ctx` uses a `declare` to call the installer defined later (move the installer above
  instead); `documentation_test.clj` has two lint errors (a removed `seon.schema` require still used at :128, :193).
- **Discard**: the DB/host-scan `apropos-value`, `find-doc-value`, `searchable-vars`, `library-vars`,
  `directory-value` vector rewrite (already removed from the worktree).

Findings to file: `my.task/open` 407 ms / 18 427 calls warm on default (over one second's budget fraction, not over
one second); `(dir seon.task)` 120 ms and a 2 290-token string today; SCI interrupt `clojure.string` metadata;
`load-host-namespace` contract refuses SCI's `source-fn` load-fn call (no `:ctx`). Another lane's
`src/seon/render/web.clj:2516` was left unreadable on disk (hook report), not mine.

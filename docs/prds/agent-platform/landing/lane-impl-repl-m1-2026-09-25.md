---
type: landing
status: S1 revised after diff review; awaiting root publication and tests
lane: impl-repl-m1-opus
created: 2026-09-25
design: docs/research/agent-platform/one-repl-language-2026-09-25.md §2.2, §6 row S1 (42308d868)
task: definitions-bypass-entry-gate, handle "132fee0467c8" (resources/seon/tasks/seon.sci.eval.edn; recomputed at the REPL with (seon.issue/task-handle 'seon.sci.eval "definitions-bypass-entry-gate" :function))
---

# S1: one definition authority for an evaluated form

`seon.sci.eval/evaluate` already observed a form's changed Vars (`turn-interns`) and analyzed its source
(`definition-rows`), but it kept only the first matched row, let unmatched changes through ungated,
validated contracts outside the restoration, and restored only on a missing contract. S1 keeps that
data flow and puts one owner, `admit-program-change!`, at the existing boundary. That owner matches
every changed function or test Var to exactly one analyzed row, refuses the rest by name, and
restores the whole change at each failure point.

## Broken or unproven, first

- **The revision commits (bbbdbd5bd, 51dda24e9, a9c74ad46) are not yet green on default.**
  - P0-A/C and P0-B were published at 6ab6bdc1. Entry-gate then ran 6 executed, 2 fail, 1 error.
    - The two fails were this lane's test expectations: `:declarations` became a vector. They are
      corrected in a9c74ad46.
    - The error was "Worker-global state changed": 83 `seon.sci.eval` instrumented functions were
      removed mid-run. My working-copy `eval.clj` was published while the test ran, so it
      reflects that publication, not a behaviour.
  - a9c74ad46 (P0-D, P1-A/B/C) is proven only on a scratch copy (`m1probe.evalcopy`) against
    the real branch context.
- **Armed cost (P1-D), measured on published S1 (6ab6bf57).** Ten runs on a fresh branch,
  installed and armed, with thread allocation per form:

  | Form | Fresh branch | After 200 private defs | Pre-S1 installed (parent) |
  |---|---:|---:|---:|
  | one-line contracted `defn` | 16.3 ms, 33.1 MB | 18.8 ms, 35.1 MB | 22.4 ms |
  | `(+ 1 2)` | 1.50 ms, 2.3 MB | 2.57 ms, 4.3 MB | 2.41 ms |

  The parent column was measured the same way before S1; allocation was not recorded then, and
  the parent was not measured with 200 private defs. The 200-def case is +7% over the parent's
  fresh-branch `(+ 1 2)`: private state widens the `turn-interns` walk. Retained memory through
  batch release is not measured.
- **Verification after 6ab6bf57 (root's run, `tmp/orchestrator/verify-1840.out.d/`).**
  Entry-gate 66/0 and reply 124/0. Three reds, handled as follows:
  - `restoration-test` (4 errors): this lane's test bugs, fixed. The test passed keyword
    stand-ins where the contract (correctly) requires a database value, and a scratch context
    had no program-snapshot atom. The corrected bodies pass against the installed, armed code.
  - `reader-test/read-failures-are-isolated`: an unclosed form also produced a second error for
    its whitespace-only remainder. Only whitespace-only spans are now dropped; stray closers stay
    errors, and there is one error per unreadable span.
  - `evaluate-sources-test/ordered-evaluation-retains…:204` (`:seon.turn/recorded-content-conflict`):
    already red before this lane's changes (`verify-6ab6ae2a-results.txt:20`, 12:01, and
    `rerun-results-2026-09-25.txt:49`, 09-24). Not caused by S1.
- **Batch refusal is proven at the restore member, not by a provoked `settle-batch!` refusal.**
  The new regression drives the ordinary path's pieces directly:
  - it runs `evaluate-for-install` and `accept-candidate!`;
  - it then calls the evaluation's `:seon.sci.eval/restore`, which is exactly what
    `turn/restore-evaluations!` calls on a refusal.
- **Still refused, to be indexed next (P2-A, owner-agreed):** point-free function `def`s and
  macro-generated `defn`s. They are refused by name as unindexed changes; they are not accepted
  as indexed rows. That is the next slice: extend the analyzer's declaration classification.
  `eval`'d definitions stay refused.
- **Only the first row of a host-bound declaration is carried.** A record's `map->R` row rides
  behind `->R` in `:seon.program/row`, as before S1.
- **`(defn same.ns/f …)` is refused** as an unindexed change. The analyzer names it
  `same.ns/same.ns/f`, which is `seon.fn`'s defect. R1 (spec 1.10) is still open.
- **Classes are not restored.** A refused `defrecord` or `deftype` form still defined its JVM
  class; only the Vars are put back.
- **Operator slip.** I ran `(require 'seon.issue :reload)` once in default's JVM; it loaded root's
  committed file.

## Revision after the diff review (repl-s1-diff-review-2026-09-25.md, ef099dccf)

**P0-A and P0-C (bbbdbd5bd): provenance-matched definitions.**
- SCI stamps a defined Var with the `:line` and `:column` of the declaration that ran. A
  definition is matched only when exactly one analyzed row names it and that row's source starts
  at that position. REPL check on 10 declaration shapes: every Var position equalled its row's
  position, and the `eval` overwrite differed (row `[1 5]`, Var `[2 9]`).
- A Var whose prior root was a function or test counts as a definition. So `(def kept 42)` and
  `(def kept nil)` refuse as unindexed and restore `kept`.
- Two records in one form are two declarations. `(do (defrecord P …) (eval '(defn hidden …)))`
  refuses `hidden`: a record exempts only helpers defined at its own position.
- A repeated name refuses with `:declarations [twin twin]`.

**P0-B (51dda24e9, SCI e663d9e2 on seantempesta/sci seon-env-hook).**
- Before the fix, `alter-meta!` on the inherited `seon.note/render-notes-ai` in my branch's fork
  mutated the shared base context's Var; the probe restored it at once.
- `sci-alter-meta!` and `sci-reset-meta!` now copy a non-built-in inherited Var into the context
  before writing (`utils/owned-var`, extracted from `bind-root!`), and keep `:sci/generation`.
- The logic was proven in a throwaway `sci/init` context using scratch copies. SCI's own suite was
  not run, because that needs another JVM.
- Root refilled the dependency analysis cache before the restart (checklist:
  `(seon.fn.analyzer/analyze-dependencies!)` after any dependency bump).

**P0-D (a9c74ad46).** `context-restoration` holds a context's program state:
- its namespace maps and owned interns;
- the program snapshot, installed functions and environment state.

`evaluate` and `accept-candidate!` each carry that restoration for the context they change.
Scratch proof: accepting `base-f` `(* 100 x)` gives `(caller 1)` = 100. After restore, it gives
2, and the Var, snapshot, installed set and environment are `identical?` to before. `(base-f "a")`
still refuses at `:input`.

**P1-A.**
- A Var redefined in place and then removed gets its captured root back. On scratch SCI,
  `(g 1)` = 2 after restoration.
- Top-level unmap forms keep a failure observation.
- The throw path reads the request's database when no connection is given.

**P1-B.**
- Planning and applying a restoration share one failure boundary.
- The unavailable error is answered and kept across `regenerate-agent-context!`.
- It is fenced before acquisition and delivered through the context's `commit-fault!`.
- Admission throws it, with the original failure as its cause.
- Proven on scratch contexts: a planning failure, a reacquisition, a no-op, and delivery.

**P1-C.** The reader keeps an unmatched closer as a reader error after the forms before it, as
Clojure's `UnmatchedDelimiterReader` does, and evaluation refuses the whole input. Scratch reader
results:

| Input | Events |
|---|---|
| `(+ 1 2))` | `[:form :error]` |
| `(let [x 1]\n(f x)\n}` | `[:error :form :error]` |

The retired regression `closer-only-recovery-artifacts-are-dropped` is replaced.

**P2-B.**
- `restoration-step` is three closed alternatives, and `{}` is refused.
- The refusal constructors require at least one member.
- `:declarations` keeps order and repeats.

## What the REPL showed before the change (branch `impl-repl-m1-opus`, default pid 35446)

The installed `evaluate` was called on the branch's retained context through
`acquire-context!`, as the MCP bridge does. Each row compares the analyzed row, the changed Vars and
what resolved afterwards:

| Form | Before S1 | S1 |
|---|---|---|
| `(defn g … contract)` | one row, armed | same |
| `(do (defn a1 …) (defn b1 …))`, both contracted | row a1 only, b1 live with no row | refused `multiple-declarations`, naming both; neither left |
| `(let [k 1] (defn l1 …))` | one row | same |
| `(def d1 (fn [x] x))` | refused, missing contract | same |
| `(eval '(defn q1 [x] x))` | admitted: no row, no contract, `q1` live | refused `unindexed-change` `#{…/q1}`; restored |
| `(defn m1probe.a/self … contract)` (same ns) | admitted, no row, `(meta #'self)` = `{:name self}` (attribute map dropped) | refused `unindexed-change` (until R1) |
| `(defn m1probe.b/qf …)` (other ns) | "Var name should be simple symbol." (SCI analyzer.cljc:846) | same (R1) |
| `(defmacro mk …)` then `(mk m2)` | `mk` a row; `(mk m2)` generated an unindexed Var | `(mk …)` refused `unindexed-change` |
| multi-arity `defn` with `[:function …]` | one row | same |
| redefinition of `g` without `:malli/schema` | refused, prior root restored (`(g 5)` = 6) | same |
| `(defn bad … {:malli/schema [:=> :int]})` (invalid contract) | refused, **but `bad` stayed live**, `(bad 1)` = 1 | refused, `bad` absent |
| the same invalid contract over the inherited `my.program/callers` | refused, **the fork kept the broken replacement** (`identical?` false) | restored (`identical?` true) |
| top-level `(ns-unmap 'n 'm3)` | deletion row | same |
| nested `(do (ns-unmap 'n 'ma) 1)` | Var gone, no row, nothing refused | private Var: allowed; stored row's Var (`my.program/callers`, `seon.note/render-notes-ai`): refused `unindexed-change`, restored |
| `(do (defn z1 …) (ns-unmap 'n 'g))` | z1's row, the removal lost | refused `multiple-declarations`, `:removals #{…}` when g is stored; restored |
| `(do (intern 'my.program 'breaks (fn …)) (throw …))` | the replacement survived as a private binding | stored Var restored (`identical?` true) |
| `(do (defn e1 …) (throw …))`, e1 has no row | kept as private binding | same: private and row-less |
| `(defrecord Pair [a b])` | 3 changed Vars (`->Pair`, `map->Pair`, `__->Pair__ctor__`), 2 host-bound rows | admitted as one host-bound declaration |
| `(defprotocol P …)`, `(defmulti …)` | Vars changed, no rows | unchanged: their values are not `fn?`, so they are not definitions |

**P-S1b, settled.** SCI's `eval-def` and `bind-root!` write an inherited Var by copying it into
the fork's namespace map (`reference-code/sci/src/sci/impl/evaluator.cljc:25-49`,
`sci/impl/utils.cljc:359-379`). The held namespace map therefore keeps the unmodified prior Var,
and restoration re-interns it. The inherited `my.program/callers` was restored `identical?` this
way.

**Restore member.** An admitted `(defn kept … (* 5 x))` answers 5. Calling its evaluation's
`:seon.sci.eval/restore` brings back the prior root `(inc x)`, and the prior root stays armed:
`(kept "a")` refuses at `:input`.

**Unavailable.** Two cases, run on a disposable `sci/init` context:
- a program reacquired between capture and restoration marks the execution
  `:program-reacquired`;
- a restoration step that throws marks it `:restore-failed`, keeping the cause chain.

Either way, every later form on that context receives the stored refusal. An empty restoration
never marks the execution.

## Timings (scratch copy, unarmed; 10 runs each, same branch context)

| Probe | Parent (installed) | S1 copy |
|---|---:|---:|
| one-line contracted `defn` | 22.4 ms | 23.5 ms |
| `(+ 1 2)` | 2.41 ms | 2.54 ms |

`admit-program-change!` alone took 41 ms on a first `defn`, almost all of it the existing source
analysis that `definition-rows` already forced. The removal check walks only namespace maps that
are not `identical?` to the held ones, and pulls a row only for a removed Var (0.04 ms each).

## Changes

- `src/seon/sci/eval.clj`:
  - retires `refuse-uncontracted-definitions!` and `changed-interns`;
  - `definition-rows` now takes the definitions and answers `[qualified row-or-nil]`;
  - adds `program-definitions`, `row-backed?`, `removed-interns`, `program-restoration`,
    `apply-restoration!`, `restore-or-unavailable!`, `execution-unavailable`, `unindexed-change`,
    `multiple-declarations`, `program-identity-name`, `admit-program-change!` and `sci-var?`, each
    contracted;
  - `evaluate` builds one `restore!`, used at all four failure points;
  - the evaluation carries `:seon.sci.eval/restore`;
  - an unavailable execution refuses at entry;
  - `evaluate` gains `:seon/tasks #{"132fee0467c8"}`.
- `src/seon/turn.clj`: `restore-evaluations!` runs on both settlement-refusal branches (analysis
  refusal, `:refused-outcome`).
- `resources/seon/schemas/seon.sci.eval.edn`:
  - the three named errors: `:seon.sci.eval/unindexed-change-error`,
    `:seon.sci.eval/multiple-declarations-error` and `:seon.sci.eval/execution-unavailable-error`
    (the third names the unavailable state);
  - their members;
  - `:restore` on `:evaluation`;
  - the helper contracts' shapes.
- `test/seon/sci/entry_gate_test.clj`:
  - `every-definition-a-form-makes-is-gated-and-armed` gains `^{:seon/tasks #{"132fee0467c8"}}`.
    Its two-declaration forms now assert the refusal, and the two contracted definitions are sent
    as separate forms.
  - New `a-form-changes-the-program-by-one-indexed-declaration`, holding the edge cases above.

Size: +448/−92 over four files. Source: eval.clj +301/−81, turn.clj +15/−1, schema +67. Test:
+65/−10. **Over the ~100-line bound.** The design's S1 estimate was +45. The overage is contracts
and docstrings on eleven helpers, plus the restore-step shape; the restoration had to become data
so that "nothing to restore" never marks an execution unavailable.

## First root run (6ab6b447) and its fix

`seon.sci.entry-gate-test` went red in the new regression, with two defects:

- **Test defect.** The admitted-redefinition source had a stray trailing `)`. The form was refused,
  so the evaluation carried no `:seon.sci.eval/restore`, and calling that nil produced the NPE at
  entry_gate_test.clj:163.
- **Producer defect behind it, pre-existing.**
  - The reader accepts `(defn …))` by dropping the stray `)`: its event source is
    `(defn …)`, and `(+ 1 2))` evaluates to 3.
  - But `evaluate` handed the raw request text to `definition-rows`.
  - clj-kondo's parser then NPEs inside `rewrite-clj.parser.core/trailing-paren` (a `swap!` on a
    nil atom). The failure chain runs `seon.fn/source-rows` → `analysis-rows-by-file`
    (fn.clj:1403).

The fix sets `admit-program-change!`'s source to the reader event's `:seon.sci.reader/source`, and
the test source is corrected. A new assertion covers the stray paren: `(defn trailing …))` is
admitted with source `(defn trailing …)` and armed, `(trailing "a")` refuses at `:input`. It was
REPL-proven on a scratch copy against the published default (6ab6b7a7).

Open question for the reader's owner (this lane): Clojure refuses a trailing `)` with "Unmatched
delimiter", whereas the reader here silently drops it. The fix above does not change that.

## Root runs

1. Publish `src/seon/sci/eval.clj`, `src/seon/sci/reader.cljc` and
   `resources/seon/schemas/seon.sci.eval.edn` together, because `sci-var?` and `context-state?`
   register before the schema compiles. Default already runs SCI e663d9e2.
2. `bin/test-check default --ns seon.sci.entry-gate-test`
3. `bin/test-check default --ns seon.sci.restoration-test`
4. `bin/test-check default --ns seon.sci.reader-test`
5. `bin/test-check default --ns seon.cluster.reply-test` and `--ns seon.cluster.evaluate-sources-test`:
   stray closers in replies are now reader errors.
6. `bin/test-check default --ns seon.sci.eval-test`
7. `bin/test-check default --ns seon.cluster.turn-test`
8. P1-D: time a one-line contracted `defn`, `(+ 1 2)`, and a form after about 200 private defs,
   armed, parent against change, with allocation.

## Out-of-scope findings (no issue file written: `docs/seon/issues/` is not on this lane's row)

- The reader's syntax-quote resolver (`src/seon/sci/reader.cljc:84-104`) resolves an unreferred
  core symbol into the current namespace, whereas SCI's own `sci.impl.parser/fully-qualify:60`
  checks `clojure.core`. So `` `defn `` reads as `m1probe.a/defn`, and a syntax-quoted
  defn-generating macro fails with "Unable to resolve symbol: m1probe.a/defn". This lane owns
  `reader.cljc`; the fix is a later slice.
- `seon.fn/source-rows` names a same-namespace qualified `defn` with a doubled symbol,
  `m1probe.d/m1probe.d/self`. Owner: `src/seon/fn.clj`. R1 depends on it.

## Verification limits

- No test request, `bin/seon` operation or publication was run by this lane (LOAD RULE).
- Probe namespaces `m1probe.jvm`, `m1probe.s1` and `m1probe.evalcopy` were created in default's
  JVM and removed at the end. Branch `impl-repl-m1-opus` was retired.

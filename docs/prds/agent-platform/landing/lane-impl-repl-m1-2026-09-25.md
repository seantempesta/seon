---
type: landing
status: committed, awaiting root publication and tests
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

- **Nothing here has run on the published default yet.** Proof so far is REPL-only (below):
  - a scratch copy of the edited `eval.clj`, loaded as `m1probe.evalcopy` with its keywords
    rewritten to `:seon.sci.eval/…`, ran against the real SCI context of branch
    `impl-repl-m1-opus`;
  - the schema entries compiled into a projection of that branch.

  Root still has to publish and run the tests listed under "Root runs".
- **The contracts on the new private helpers are not measured armed.** The +5% timing below is
  the scratch copy, which is unarmed. Armed, `program-definitions` and `program-restoration`
  validate `turn-interns` maps (only generation-owned Vars) on every form. Root measures the
  armed cost.
- **Settlement refusal (failure point d)** is wired: `turn/restore-evaluations!` calls each
  evaluation's `:seon.sci.eval/restore`, last form first. It is proven only through the restore
  member (the new test calls it directly); no turn-level settlement refusal was provoked.
- **Known remaining leak at (d).** `gate-function-install` → `accept-candidate!` transfers an
  accepted candidate's roots into the base context before batch settlement. A later settlement
  refusal restores the candidate fork, not that transfer. The design scopes the candidate path
  outside S1; recorded here.
- **These changes are now refused, which is behavior change:**
  - `(def f (comp …))`, `(def xf (map inc))` and every other point-free function-valued `def` the
    analyzer gives no row. Each is now an unindexed change: define it with `defn`.
  - defn-generating macros and `eval`, also unindexed changes.
  - `alter-meta!` on a function Var.

  `(defmacro …)` itself, `defrecord` and `deftype` stay admitted. So do protocol and multimethod
  objects, whose values are not `fn?`.
- **`(defn same.ns/f …)` is refused for now** as an unindexed change. The analyzer names it
  `same.ns/same.ns/f`, a doubled qualified symbol that is `seon.fn`'s defect. Before S1 it was
  admitted with no row, no contract and with its attribute map dropped (probe below). R1 (spec
  1.10, qualified definitions) is the next slice; it is **not in this commit**.
- **Operator slip.** While looking up the task handle I ran `(require 'seon.issue :reload)` in
  default's JVM. That reloaded the committed `src/seon/issue.clj`, root's commit, into default.
  No other default Var was redefined; every other probe ran in `m1probe.*` scratch namespaces or
  on the branch context.

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

## Root runs

1. Publish and adopt `resources/seon/schemas/seon.sci.eval.edn` and `src/seon/sci/eval.clj`
   together: the new `seon.sci.eval/sci-var?` predicate must register before the schema compiles.
2. `bin/test-check default --ns seon.sci.entry-gate-test`
3. `bin/test-check default --ns seon.sci.eval-test`
4. `bin/test-check default --ns seon.sci.definition-digest-test`
5. `bin/test-check default --ns seon.sci.program-change-test`
6. `bin/test-check default --ns seon.cluster.turn-test` (settlement branch of `restore-evaluations!`)
7. Time a one-line contracted `defn` and `(+ 1 2)` on a branch, armed, against the parent
   (22.4 / 2.41 ms above).

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

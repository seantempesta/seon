---
type: landing
status: kernel committed (5a81616a3); eval.clj patch awaits root; untested (root runs the list below)
lane: repl-parity-opus-2
created: 2026-09-26
authority: docs/seon/issues/agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake.md
---

# Landing: REPL parity, lane repl-parity-opus-2 (2026-09-26)

Resumes design-lost-error-seams-opus. Reference: `clojure.main` 1.12.5 (`java -cp clojure-1.12.5.jar clojure.main -r`).
Probed on default HEAD 6ccf12a5a (committed archive, hook publication off) through MCP SCI on branch
`repl-parity-opus-2` (retired), plus read-only JVM probes in the scratch namespace `repl-parity-opus-2-scratch`.

## Broken first

1. **Not yet live:** `src/seon/sci/eval.clj` is not this lane's. Two producer fixes wait for root as
   `tmp/repl-parity-opus-2/eval.patch` (`git apply --check` clean against 6ccf12a5a; +15/−7 lines). Until
   root applies it, `(/ 1 0)` prints `Execution error (ArithmeticException) at sci.impl.analyzer$return_call…/eval
   (analyzer.cljc:1818).` (honest, but the interpreter's frame). It also still answers `user` for `(require …)`.
2. **`letfn` and `defrecord` with an inline protocol still fail** (issue
   `letfn-and-defrecord-with-an-inline-protocol-fail-in-the-agent-repl`). The root cause is in our SCI fork
   (`reference-code/sci`, fork commits 72150fd4, e663d9e2, 409888f6 "copy on write"). That fork is outside
   this lane's files, so this lane did not fix it. See the diagnosis below.
3. `Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error
   alternatives matches it.` still ends every exception (D12, `src/seon/error.clj:1918`, not this lane's).
4. The shown text for a failure names no result handle. The failure value is bound in the `result`
   namespace (probed: `(ns-publics 'result)` holds the three error maps), but its name is not printed. The
   print is owned by the render/turn path, not by this lane's files.

## Verified live, side by side (already fixed, not redone)

| Form | clojure.main | Seon agent REPL on 6ccf12a5a |
|---|---|---|
| `(/ 1 0)` | `Execution error (ArithmeticException) at user/eval1 (REPL:1).` / `Divide by zero` | class and message right. Location `(REPL:1818)` was wrong; fixed here and in the patch |
| `(inc "a")` | `… (ClassCastException) …` / the cast message | the same, with the same location defect |
| `(throw (ex-info "boom" {:a 1}))` | `… (ExceptionInfo) …` / `boom` | the same, location `(REPL:265)` |
| `(sq 1 2)`, contracted | `Wrong number of args (2) passed to: user/sq` | `expected the declared arglists (([x]))`: real arglists (fixed earlier) |
| `(try (inc "a") (catch ClassCastException e :c))` | `:c` | `:c` (e33f095df) |
| `(catch IllegalStateException e :i)` | `:i` | `:i` |
| `*ns*` | `#object[clojure.lang.Namespace 0x… "user"]` | `#namespace[user]` (f0da43c16) |
| `(require '[clojure.string :as str])` | `nil` | **`user`**: fixed in the patch |
| `(do (require '[clojure.set :as s]) (s/union #{1} #{2}))` | `#{1 2}` | **`user`**: fixed in the patch |

## Root causes (each verified at the REPL)

- **Location lost off the context's thread.** SCI attaches a form's location (`ex-info` with `:type
  :sci/error :line :column`) only when the evaluating thread is the ctx's `:main-thread-id`. That id is set
  once at `sci/init` (`reference-code/sci/src/sci/impl/opts.cljc:332`), and a fork copies it (`:382`). On any
  other thread SCI assumes an enclosing try and rethrows the bare exception
  (`sci/impl/utils.cljc:125-135`). Agents evaluate on Flow threads, while tests evaluate on the creating
  thread, which is why the 5f59b1673 parity row passed while MCP showed `(REPL:1818)`. Probe:
  `(sci/eval-form ctx form)` gives `{:type :sci/error :line 1 :column 1}` on the creating thread. Inside a
  `future` it gives a bare `ArithmeticException {}`, and with `(assoc ctx :main-thread-id <this thread>)` it
  gives the wrapper again. Then `kernel/execution-error-text` stamped "REPL" over ex-triage's interpreter
  frame line, and it also read `:line` from the agent's own `ex-data`.
  - Kernel fix (committed): only SCI's `:sci/error` wrapper is a location. Without the wrapper, ex-triage's
    frame stands whole.
  - Producer fix (patch): the form is evaluated with `:main-thread-id` set to the evaluating thread.
- **`require` answered the namespace.** `seon.sci.eval/evaluate` replaced the form's value with the
  namespace-context row's `:seon.ns/name` (`eval.clj:4413`, since a117f4603), so any form that changed
  aliases answered `user`. Plain SCI answers `nil` and `#{1 2}` (probed). The patch returns SCI's value. It
  also deletes `:seon.sci.eval/context-row` from `unmap-row`'s result, which has no other reader.
- **`letfn` / `defrecord`+protocol, in our SCI fork.** Reproduces with no Seon code:
  `(sci/eval-string* (sci/fork (sci/init {})) "(letfn [(f [] :ok)] (f))")` fails with `HasName … nil`.
  Unforked `sci/init` answers `:ok`. The defrecord form fails with `SciUnbound cannot be cast to
  Associative` only in the fork, and `with-local-vars` works. The cause is that vars created inside a
  forked generation carry no `:sci/generation` metadata:
  - `letfn*`'s `-new-var` (`namespaces.cljc:1710`) creates such a var. `sci-alter-var-root` then calls
    `owned-var` (`utils.cljc:364-388`), which treats it as inherited and calls `t/getName` on its nil `:ns`.
  - `init-var!` (`analyzer.cljc:805-837`) predeclares such a var. `eval-def` (`evaluator.cljc:33-45`) then
    binds a copy, so defprotocol's `:var (var P)`, captured at analysis, stays unbound. The
    `vars/alter-var-root` call on it in `deftype.cljc:436` fails.

  The fix belongs in the fork: stamp `generation-meta` where the ctx creates a var (init-var!, -new-var), or
  treat a var with no namespace as owned. This needs file ownership of `reference-code/sci` and a push to the
  fork. It is not in this lane's row.

## Changes

- 5a81616a3: `src/seon/sci/kernel.clj` (+13/−7). In `test/seon/sci/eval_test.clj`, the parity row builds
  the ctx on the test thread, evaluates in a `future`, asserts `… at (REPL:1:1).`, and includes `:line 99`
  in the agent's ex-data. A new row, `require-answers-nil-and-its-alias-serves-the-same-form`, was added,
  and an assertion on the deleted `:seon.sci.eval/context-row` was dropped. Net +25 lines in owned files.
- `tmp/repl-parity-opus-2/eval.patch` for root: `src/seon/sci/eval.clj`, +15/−7.

## Verification limits and root's commands

The static pre-commit checks passed: contracts-compile 2/2 and schema-edn 211/211. Default runs the
committed archive, so these ran against HEAD without this diff; no schema changed. Lint is clean apart from
existing warnings. No timing regression is possible from the kernel change (a single `isa?`). Apply the
patch, then adopt, then run:

    git apply tmp/repl-parity-opus-2/eval.patch
    bin/test-check default --test seon.sci.eval-test/an-agents-own-exception-prints-as-clojure-main-prints-it
    bin/test-check default --test seon.sci.eval-test/require-answers-nil-and-its-alias-serves-the-same-form
    bin/test-check default --test seon.sci.eval-test/both-entrances-classify-one-failure-identically
    bin/test-check default --ns seon.sci.eval-test
    bin/test-check default --ns seon.repl-parity-test

Without the patch, the first two tests are expected red (location and `user`). After adoption, repeat the
MCP probe on a branch: `(/ 1 0)` should show `at (REPL:1:1).` and `(require …)` should show `nil`.

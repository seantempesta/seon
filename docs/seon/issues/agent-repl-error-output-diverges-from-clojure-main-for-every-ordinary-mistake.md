---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, repl, error, sci, dogfood]
---

# The agent REPL's error output diverges from `clojure.main` for every ordinary mistake

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP. Root rated REPL
parity a milestone quality bar. [repl-parity-divergences](repl-parity-divergences.md) is the
standing gate. This issue is the side-by-side evidence for the error family, taken on the live
agent entrance.

## Side by side (2026-09-26)

Left column: `java -cp clojure-1.12.5.jar clojure.main -r`. Right column: MCP SCI on branch
`dogfood-repl-opus-8`, default archive 10d6fdff5. Every Seon line below also ends in the noise
line `Contract finding: <fn> returned this error, and none of its declared error alternatives
matches it.` (D12, known), which is left out here.

| Form | `clojure.main` | Seon agent REPL |
|---|---|---|
| `(/ 1 0)` | `Execution error (ArithmeticException) at user/eval1 (REPL:1).` `Divide by zero` | `seon.sci.kernel/failure-value refused  at []: expected the declared requirement (:successful-evaluation), got nil nil. Fix: Divide by zero Inspect the named requirement before retrying. Example: No docstring example is available.` |
| `(throw (ex-info "boom" {:a 1}))` | `Execution error (ExceptionInfo) at …` `boom` (and `*e` holds the data) | the same frame with `Fix: boom`. `{:a 1}` appears nowhere, `*e` is nil, and no result handle names the exception |
| `(inc "a")` | `Execution error (ClassCastException) … class java.lang.String cannot be cast to class java.lang.Number …` | the same frame: `got nil nil. Fix: class java.lang.String cannot be cast …`, with no class name |
| `(sq 1 2)` (contracted, one arity) | `Execution error (ArityException) … Wrong number of args (2) passed to: user/f` | `user/sq refused argument count at []: expected the declared arglists (nil), got an argument count of 2.` The arglists print as **`nil`**, while the thrown `ex-data`'s `:seon.error/expected` is `([x])` |
| `(undefined-thing 1)` | `Syntax error compiling at (REPL:1:1).` `Unable to resolve symbol: undefined-thing in this context` | `seon.sci.eval/evaluate refused source at []: expected a resolvable symbol (:symbol), got an unresolved symbol undefined-thing. Fix: Define or require this symbol.` Acceptable, apart from the frame |
| `(sq "a")` | not applicable (no contracts) | `user/sq refused argument 0 (0-based) at []: expected an integer (:int), got a string "a". … Called from sci.lang.Var (lang.cljc:265). …` then a stray `"a"` line. Good content; the frame is `sci.lang.Var`, not the caller |
| `(try (inc "a") (catch ClassCastException e :c))` | `:c` | `… got nil nil. Fix: Unable to resolve classname: ClassCastException …`. The class is not available to agent code at all. Test runs showed the same for `IllegalStateException` |
| `(try (/ 1 0) (catch ArithmeticException e (ex-message e)))` | `"Divide by zero"` | `"Divide by zero"`: correct |
| `*ns*` | `#object[clojure.lang.Namespace 0x57fae983 "user"]` | `#object[sci.lang.Namespace]`, with no name |
| `(require '[clojure.set :as set])` | `nil` | `user` in one run and `nil` in another (D13, known) |

## Which model is wrong

1. **An agent's own exception is presented as a platform refusal.** A thrown `Throwable` from agent
   code is wrapped by `seon.sci.kernel/failure-value` into a refusal *of `failure-value`* with
   `:successful-evaluation` expected and "got nil nil". The exception is an ordinary value
   ("errors are ordinary values"). It should print through the floor as its class, message,
   `ex-data` and cause chain (Throwable→map, the same shape `clojure.main/ex-triage` reads). The
   refusal frame belongs only to Seon's own declared refusals.
2. **The shown text is re-derived from the error's fields and loses members.** The arity refusal
   shows `(nil)` for arglists that its `ex-data` carries. The printed sentence is built from a
   different key than the one the producer filled.
3. **SCI's class allowlist omits common `java.lang` exceptions.** `ClassCastException` and
   `IllegalStateException` cannot be named in `catch`. SCI's `:classes` option is the dependency
   seam for this.

## Expected

Each mistake above shows the exception's class and message (and `ex-data` when present), in the
spirit of `clojure.main`'s `Execution error (Class) …` line. The exception is reachable as a result
handle, arglists print as declared, and ordinary `java.lang` exception classes can be caught.

## Regression

One parity row per line of the table, run through the agent entrance.

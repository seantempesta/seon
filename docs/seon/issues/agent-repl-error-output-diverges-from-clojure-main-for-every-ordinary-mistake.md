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

## Evidence (cycle 4): the shown sentence drops the members the producer declared

- `(seon.schema/register! :dogfood.book/book [:map {:seon.db/attributes true} …])` threw `ex-info` "An
  identity-bearing entity schema must declare its partition." with `:seon.schema/member :seon.program/partition`
  and `:seon.schema/expected #{:seon.program :seon.data}` (`src/seon/schema.clj:1457-1476`). The agent saw
  `seon.sci.kernel/failure-value refused … got nil nil. Fix: An identity-bearing entity schema must declare its
  partition.` The key and its allowed values, which are the whole fix, were dropped.
- `(seon.db/transact! [{:dogfood.book/id "empty" :dogfood.book/title ""}])` against `[:string {:min 1}]` answered
  `expected a string ([:string {:min 1}]), got a string "". Fix: Supply a string at [0 :dogfood.book/title].` The
  sentence is built from the schema's type, not from Malli's explanation of the failing constraint ("should have at
  least 1 character", `malli.error/humanize`).
- Before the entity schema was registered, `(dir user)` listed `:dogfood.book/id` and `:dogfood.book/title`, but
  `transact!` refused `:dogfood.book/title` as "an undeclared attribute … Use a declared attribute from the entity's
  schema". Registered-but-not-installed is not distinguished from undeclared.

## Re-verified 2026-09-26 (dogfood-repl-opus-2, cycle 10, archive 5bed888d0): mostly fixed

| Form | Now | Verdict |
|---|---|---|
| `(/ 1 0)` | `Execution error (ArithmeticException) at (REPL:1:1).` `Divide by zero` | fixed (6ccf12a5a still showed `REPL:1818`, an SCI-internal line; d9566ad05 fixed the location) |
| `(inc "a")` | `Execution error (ClassCastException) … class java.lang.String cannot be cast …` | fixed |
| `(throw (ex-info "boom" {:a 1}))` | `Execution error (ExceptionInfo) at (REPL:1:1).` `boom` | fixed (same as clojure.main, which also omits the data) |
| `(try (inc "a") (catch ClassCastException e :c))` | `:c` | fixed (e33f095df) |
| `(try (throw (IllegalStateException. "x")) (catch IllegalStateException e (ex-message e)))` | `"x"` | fixed |
| `*ns*` | `#namespace[user]` | fixed (named) |
| `(require '[clojure.set :as set])` | `nil` | fixed (d9566ad05) |
| `(keys {:a 1 :b 2})`, `(map inc [1 2])`, `(sort …)`, `(seq "ab")`, `(rseq [1 2 3])`, `(subvec [1 2 3] 1)` | `(:a :b)`, `(2 3)`, `(1 2 3)`, `(\a \b)`, `(3 2 1)`, `[2 3]` | fixed (floor issue #7 of the dogfood report) |
| `(sq 1 2)`, contracted one-arity `sq` | `… expected the declared arglists (nil), got an argument count of 2 … Called from sci.lang.Var (lang.cljc:265).` | **open**: arglists still `(nil)`, frame still `sci.lang.Var` |
| `(Math/abs -3)` | `Unable to resolve symbol: Math/abs` | **open**: clojure.main answers `3` |
| every exception | ends in `Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared error alternatives matches it.` | **open** (D12): the agent's own exception is reported as a contract finding of a platform function |
| ten forms in one run | every error says `(REPL:1:1)` | minor: clojure.main counts lines across the input, so the tenth form says `REPL:10` |

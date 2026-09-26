---
type: landing
created: 2026-09-26
lane: err-minimal-opus
status: committed, unadopted and untested (RESET NEEDED; one patch for root)
---

# Landing: err-minimal-opus (an agent's own exception as minimal complete data, like clojure.main), 2026-09-26

What exists: `seon.sci.kernel/failure-value` turns every failure of an agent's evaluation into the one flat error.
`seon.error.refusal/chain` reads the cause chain. err-s12 made the floor print that value as the saved shown text.

Approach: `failure-value` is the producer, and it declares the class of what it sees: an agent's own exception or a
refusal, never a core fault (a carried core-fault receipt is rethrown). So it reports the way
`clojure.main/ex-triage`/`ex-str` does (main.clj:219-300):

- the author's throwable (past SCI's location wrapper);
- that throwable's class and message;
- its chain, with ex-data on each link;
- SCI's own author location.

Each link's JVM frames are cut at the interpreter: `refusal/called-frames` keeps the first-party frames of host code
that the form called, up to the first `sci.impl.*` frame. It never keeps the evaluator that ran the form. Core faults keep
every first-party frame, because that is `chain`'s default.

## Broken or unproven (read first)

1. **Not adopted, and no tests run.** Loading my namespaces would redefine default's Vars. **RESET NEEDED.** After
   the reset, root runs the "after" probe through MCP. Proven at the JVM REPL: copies of the new `refusal.clj` and
   `failure-value` were loaded into scratch namespaces (since removed) and run on real SCI throws, plus a real armed
   contract refusal. The results are below.
2. **Apply `tmp/err-minimal-opus/root-files.patch` with this commit**, because none of its files are mine. It covers:
   - `seon.sci.kernel.edn`: `:seon.sci.kernel/error`'s guard-observation becomes optional. It is the one kernel error
     schema: an agent exception, or a guard's refusal or timeout, which names the observation.
   - `render.clj`: `unknown-map` reads `:seon.error/exception-class` instead of the deleted
     `[:seon.error/data :seon.sci.eval/throwable]`.
   - Three tests that used guard-observation or the data record as a marker (`instrument_test` :530, `armed_test`
     :234, `contracts_plan_test` :71). They now assert the fired guard's observation or `:seon.error/offending`.
   - Without the patch, eval_test's `both-entrances-classify-one-failure-identically` validates an agent exception
     against the old schema and goes red.
3. **Contract refusals still carry instrument's bookkeeping.** This includes `:seon.instrument/arglists`, arity, arm,
   caller, check, fn, malli and problem-count, the `expected-shape`/`data-blob` digests, and `doc`/`shown`/`result-id`.
   This is instrument.clj's producer, not one of my files. It is logged as task `a57e8b27de67`
   (`resources/seon/tasks/seon.instrument.edn`; `tasks-tx` validates it read-only, giving 1 op). The kernel side of
   this is done: the refusal gets no evaluator frames and no record, and its carrying link no longer repeats the
   message.
4. **An unresolved symbol still reads "Execution error"**, where clojure.main says "Syntax error compiling". SCI's
   analysis error (`:phase "analysis"`, no callstack) is not a location wrapper, so its ex-data (`:type :line :column
   :file nil :phase :sci.impl/symbol`) rides its link. This is left as is. `:seon.error/data {:seon.sci.eval/symbol …}`
   stays because `seon.error`'s unresolved-symbol correction reads it (error.clj:1024).
5. **A non-interpreted throw keeps its first-party frames**, for example kernel/invoke's own "no installed SCI Var"
   refusal. It never passed `sci.impl`, so the cut keeps the whole stack.

## Before: through MCP, branch `err-minimal-opus`, default at b4b90d405 (branch retired)

- `(/ 1 0)`: about 3 KB.
  - `:seon.error/exception-class clojure.lang.ExceptionInfo` (the wrapper).
  - `:seon.error/data {:seon.sci.eval/throwable "clojure.lang.ExceptionInfo" :seon.sci.admit/record {:fn-entries 0 … :allocated-bytes -1 :outcome :error}}`.
  - `:seon.sci.kernel/guard-observation {duration}`.
  - Its one link carried 33 frames plus 18 more, all from seon.sci.eval, kernel, db, call_preparation, schema, turn
    and render.
- `(throw (ex-info "boom" {:audit/offending 7}))`: the same, plus the link's data.
- `(seon.error.refusal/frame-function "not-a-symbol")`: the same 33 evaluator frames, the data record, and the
  instrument bookkeeping.

## After: the same forms through the new `failure-value` on real SCI throws (scratch JVM copies)

```clojure
;; (/ 1 0) — 480 bytes
{:seon.error/at #inst "…", :seon.error/layer :seon.sci.kernel/evaluation,
 :seon.error/operation seon.sci.kernel/failure-value,
 :seon.error/message "Execution error (ArithmeticException) at (REPL:1:1).\nDivide by zero",
 :seon.error/exception-class java.lang.ArithmeticException,
 :seon.error/chain [{:seon.error/throwable-class "java.lang.ArithmeticException", :seon.error/message "Divide by zero"}],
 :seon.sci.eval/stacktrace [[clojure.core// 1022 1] [user 1 1]]}
;; ex-info — 453 bytes: … :exception-class clojure.lang.ExceptionInfo,
;;   :chain [{:throwable-class "clojure.lang.ExceptionInfo", :message "boom", :data {:audit/offending 7}}]
;; (defn f [x] (inc x)) (f nil) — 598 bytes, :stacktrace [[clojure.core/inc 924 1] [user/f 1 13] [user/f 1 1] [user 2 1]]
;; host code that broke (a first-party fn called from SCI): its own frames are the evidence and stay —
;;   :frames [[seon.err_min_probe$boom invokeStatic "NO_SOURCE_FILE" 1] …]; no evaluator frame
;; armed contract refusal: operation, expected :symbol, offending ["x"], problems (fix), member kept;
;;   chain [{:seon.error/throwable-class "clojure.lang.ExceptionInfo"}] — no frames, no repeated message, no record
```

## Changed

- `seon.error.refusal`:
  - adds `called-frames`, a public function: first-party frames before the first `sci.impl.*` frame;
  - adds a private `first-party-frames`;
  - `chain` gains a third arity, `frames-of`. The 1- and 2-arity forms are unchanged: every first-party frame.
- `seon.sci.kernel`:
  - adds `author-throwable`, which goes past SCI's location wrappers;
  - `failure-value` chains the author's throwable with `called-frames` and names the author's class;
  - it adds guard-observation only when the deadline fired;
  - it no longer merges evidence into an existing refusal;
  - the refusal's own link drops a message identical to the refusal's.
- `test/seon/sci/eval_test.clj`:
  - new regression `an-agents-own-exception-is-minimal-complete-data`. It covers the real class, the exact message,
    a frameless chain, `[user 1 1]`, no data/guard/frame, **under 600 bytes**, the ex-info data on its link, no
    evaluator frames, and an armed refusal keeping operation/expected/offending/fix with its message said once;
  - three tests were updated to the new shape.

## Deleted

| Item | Lines |
|---|---|
| `evidence` map: `:seon.sci.eval/throwable` (the wrapper class) + `:seon.sci.admit/record` copy + `:seon.fn/sym` copy in data | 3 |
| merge of that evidence into an existing refusal's data | 1 |
| unconditional duration guard-observation on every agent exception | 2 |
| wrapper `:seon.error/throwable` handed to `diagnostic` (second chain walk and root-frame, then overwritten) | 1 |
| evaluator frames on each link of an agent exception: about 50 per link, at run time | — |
| render.clj's `:seon.sci.eval/throwable` read (patch; now reads exception-class) | 0 net |

## Size and cost

- src +95/−48: kernel +63/−42 (mostly the docstring and the conditional rewrite), refusal +32/−6.
- test +67/−25.
- The error for `(/ 1 0)` goes from about 3 KB to 480 B. That is one chain walk instead of two, since `diagnostic` no
  longer re-walks the chain.

## Static checks

- `seon.contracts-compile-test/check` over kernel.clj and refusal.clj against the checkout: `[]` (75 ms).
- clj-kondo: no new errors. The kernel's two warnings were already there at HEAD.

## Root runs (after applying the patch and adopting)

```
bin/test-check default --test seon.sci.eval-test/an-agents-own-exception-is-minimal-complete-data
bin/test-check default --ns seon.sci.eval-test
bin/test-check default --ns seon.sci.kernel-test
bin/test-check default --ns seon.error.refusal-test
bin/test-check default --test seon.instrument-test/<deadline-in-reporter test at :515>
bin/test-check default --ns seon.cluster.armed-test
bin/test-check default --ns seon.contracts-plan-test
bin/test-check default --ns seon.render-coverage-test
```

Then run `(/ 1 0)`, the ex-info and `(seon.error.refusal/frame-function "x")` through MCP on a fresh branch.

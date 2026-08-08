---
type: issue
status: resolved
severity: blocker
tags: [issue, toolkit, runtime, observability]
---

# An interrupted `my.shell/run` orphans its child process AND its receipt

## Problem

When the eval time limit fires while `my.shell/run` is waiting on a child,
three things go wrong at once and none of them is reported honestly:

1. **The OS child survives.** It keeps running for its natural lifetime,
   detached from any run, receipt, or agent. Nothing will ever reap it.
2. **The effect receipt is left dangling.** It has `:seon.effect/opened-at`
   and neither `:seon.effect/result-edn` nor `:seon.effect/interrupted-at`,
   so it is permanently `:pending` even though the run closed cleanly. The
   crash model's "mark dangling receipts interrupted" only runs at recovery;
   nothing marks it here, because the process did not crash.
3. **The interruption is never recorded as an interruption.** The eval
   receipt instead carries a CONTRACT VIOLATION from the code that was
   trying to record the problem, so the durable evidence of what happened
   is a bug report about the reporter.

`seon.effect/dispatch` (`src/seon/effect.clj:~305-320`) does cancel its
`FutureTask` on `InterruptedException`, with the comment "Capability
handlers own their resource-specific cleanup on interruption." The shell
handler does not do that cleanup, and the interruption never reaches it
here anyway — the eval is cut by sci's interrupt on the calling side.

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator
root, driven through a real run with `:seon.config.eval/time-limit-ms` set
to 4000. Complete result:
`docs/prds/sci-execution-runtime/research/probes/tool-exercise/shell-interrupt.edn`.

One form:

```clojure
(my.shell/run {:my.shell/argv ["sh" "-c" "sleep 300 # tool-exercise-sentinel"]
               :my.shell/cwd  "/Users/sean/src/seon/tmp/tool-exercise-scratch"})
```

**The child outlived the run.** 29 seconds after the 4 s limit fired and the
run had closed:

```text
$ ps -eo pid,etime,command | grep "sleep 300"
16489       00:29 sleep 300
```

**The receipt is dangling.** The complete committed effect receipt — note
what is absent:

```clojure
#:seon.effect{:request-edn "#:my.shell{:argv [\"sh\" \"-c\" \"sleep 300 …\"], :cwd \"…\"}",
              :id "[\"exercise:2b0bc7ab-…\" 0 0]",
              :owner #:seon.fn{:sym "my.shell/run"},
              :run #:seon.cluster.run{:id "exercise:2b0bc7ab-…"},
              :form-ordinal 0, :ordinal 0,
              :opened-at #inst "2026-08-08T02:59:22.568-00:00"}
```

**The recorder broke.** The eval receipt for that form:

```text
seon.problems/form-problem violated its contract (invalid-input):
[nil #:seon.sci.eval{:evaluation
  {:seon.cluster.eval/ns [{:value nil, :message "missing required key"}],
   :seon.sci.eval/ending-ns [{:value nil, :message "missing required key"}],
   :seon.print/options [{:value nil, :message "missing required key"}],
   :seon.sci.admit/capped? [… 1 more subtree; requery refused: no stable
   identity was supplied at path [] offset 0 with
   :seon.render.profile/unspecified]}}]
```

So on the interruption path the evaluation value handed to
`seon.problems/form-problem` is missing four required keys. Whatever the
interruption path was supposed to record, it did not.

## Expected

Interruption is a terminal disposition, and terminal means all three:

- the child process is destroyed (destroy, then destroyForcibly after a
  grace) before the handler's frame goes away — the handler owns its
  resource, as `effect.clj`'s own comment says;
- the effect receipt is stamped `:seon.effect/interrupted-at` by the same
  path that stops the eval, not only by crash recovery;
- the eval receipt records the interruption, not a contract violation from
  the recorder. Construct the evaluation value for the interruption arm from
  the same source the normal arm uses, so an arm cannot exist that omits
  required keys.

## Acceptance

One regression drives a real run whose form calls `my.shell/run` on a
long-lived child under a short time limit, and asserts: no matching OS
process survives the run; the effect receipt carries
`:seon.effect/interrupted-at`; and the eval receipt's error names the time
limit rather than a contract violation.

## Related ugly output

The error text above ends in an elision that refuses its own requery —
`… 1 more subtree; requery refused: no stable identity was supplied at path
[] offset 0 with :seon.render.profile/unspecified`. An agent reading this
diagnostic is told a subtree exists, told it cannot have it, and given a
profile name instead of a reason it can act on. Elision inside an error
message should either fit or name a retrievable identity.

## Resolution (tool-repairs lane, 2026-08-08, `70bcd6bcc`)

All three failures fixed at one cause each.

**The orphan.** Two limits govern a foreground child and only one was
observed. The handler waited on `:seon.config.shell/time-limit-ms`; the ARM's
deadline is the limit that ADMITTED the work, and a thread parked in a host
call makes no interpreted function entrance, so SCI's interrupt could never
reach it — which is why `sleep 300` outlived a 4 s limit by 29 s. The arm now
carries its deadline as a value and PUBLISHES it
(`seon.sci.kernel/deadline-remaining-ms` and `deadline-reached?`), which is the
interface change the timeout rule asks for: blocking work asks the one
deadline that already exists instead of carrying a second clock.
`seon.shell.jvm/await-exit` waits on whichever limit ends first and returns
which one it was, so no arm of the handler can return while its child is
alive.

**The dangling receipt.** Both timeout arms return
`:seon.effect/disposition :interrupted`, which `seon.effect/request*` already
stamps `:seon.effect/interrupted-at` from. The process and the receipt now
terminate together or not at all. No change to `src/seon/effect.clj` was
needed, and none was made — it was mid-flight under the carriage lane.

**The recorder's contract violation.** It was a SECOND constructor of
`:seon.sci.eval/evaluation`: the submission backstop hand-built four keys of a
value with nine required ones, so its own report reached
`seon.problems/form-problem` missing `:seon.cluster.eval/ns`,
`:seon.sci.eval/ending-ns`, `:seon.print/options`, and
`:seon.sci.admit/capped?` — exactly the four in the evidence above. There is
now one constructor, `seon.sci.eval/unrun-evaluation`, and an arm that does not
name the value's keys cannot omit them.

Recurring regression:
`an-evaluations-deadline-reaps-the-child-it-admitted` in
`test/seon/shell/jvm_test.clj` — a long-lived child under a 750 ms evaluation
deadline with the shell's own limit at 600 s: the handler returns in seconds
with a refusal naming the evaluation limit and the `:interrupted` disposition,
and no matching OS process survives. Its sibling
`time-limit-reaps-the-process-tree-and-marks-the-receipt-interrupted` covers
the receipt half through the door.

Remaining, filed rather than fixed: the elision inside the error text that
refuses its own requery is untouched, and is the render-quality half of this
note.
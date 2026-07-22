---
type: issue
status: open
tags: [issue, agent, architecture]
---

# Host-tier instrument rejection crashes envelope building and escapes containment

## Observed (2026-07-22, live default cluster, HEAD 47e13c47)

With `:seon.config.execution/host-tier? true`, EVERY malli instrument
rejection on the supervised JVM sci host crashes while building the
agent-facing envelope and escapes as an invocation-level error. Every
real agent turn whose reply contains one wrong-arg call to an
instrumented fn errors the whole turn and closes the run `:error`
(observed on a probe agent AND on root).

Minimal repro (pod REPL, dial ON):

```clojure
(seon.execution.host/invoke-compiled!
 db "root" 'seon.execution.runtime/eval-batch!
 [{:seon.eval/parsed (vec (seon.repl.parse/parse-forms
                           "(seon.db/entity 1 2 3)"))
   :seon.eval/starting-ns 'my.root}])
;; => {:seon.execution/message :seon.execution.message/error
;;     :seon.execution/error
;;     {:seon.error/message "nth not supported on this type: PersistentHashSet"
;;      :seon.error/kind :agent
;;      :seon.error/data {:seon.error.sci/callstack-head []
;;                        :seon.error.sci/class :runtime
;;                        :seon.error.sci/home-ns my.agent.root}}}

```

Same for `(my.blob/get "wrong-shape")` and `(seon.db/entity :not-a-db "x")`.
The pod tier renders the full structural envelope for identical calls
(verified live the same day). Valid batches (arithmetic, defn, empty)
succeed on the host; prompt render and the run fence work.

## Three defects, one owner chain

1. The JVM report path throws `nth not supported on this type:
   PersistentHashSet` somewhere in the instrument rejection chain
   (suspects: `seon.error.instrument/explain-payload` /
   `report-fn` on JVM, `seon.host.instrument` wrapper, `seon.error.sci`
   classify interplay). W3b (`12451963`) claimed hints fire on both
   tiers; the writer-gate instrument tests pass, so the live condition
   differs from the test condition (likely corpus/startup-replayed
   schema registry state vs test-registered schemas).
2. Containment break: the crash bypasses the per-form conversion in
   `seon.host.eval/eval-batch-result` — the batch dies with empty
   results instead of recording one failed eval with an error value.
   Nothing-throws requires the per-eval envelope.
3. Misclassification: an instrument rejection surfaces as
   `:seon.error/kind :agent` / `:seon.error.sci/class :runtime` (raw
   runtime blame) instead of the structural instrument kind.

## Acceptance

- Wrong-arg call to an instrumented fn on the LIVE host tier returns a
  per-eval `:seon.eval/ok? false` result carrying the same structural
  envelope the pod produces; batch continues; run does not error.
- A writer-gate regression reproduces the live condition (not just the
  test-registered-schema condition) and fails on today's HEAD.
- W5-0 must include this scenario in its retirement preflight drive.

Found by the Phase-1 composition drive (fence + instrumentation +
authored invocation under the dial); dial restored OFF the same hour.

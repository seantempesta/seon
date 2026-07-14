---
type: prd
status: planned
tags: [prd, agent, flow]
---

# Agent runtime correctness roadmap

## Outcome

One bounded agent loop preserves the exact model reply, attempts every complete
form in order, records only real execution evidence, coordinates one checkable
plan, survives ordinary provider/eval/tool failures as data, and cannot lose the
pod to an unbounded arbitrary evaluation.

## Current state

The runtime has explicit run/turn/eval facts, ordered batch parsing/evaluation,
durable declaration teeing, errors-as-values at most agent boundaries, one
retry owner, bounded result admission, query/pull work budgets, restart recovery,
and one database-backed planning mechanism. Inspect has offline long-term-plan
arms and the stable autocomplete/plan changes are integrated.

Known gaps remain: batch handling still filters alleged result text before
persistence; async structural functions can bypass output contracts; a
successful plan step has no required verification evidence; cross-agent and
address-message transitions can reopen or displace authored work; narration can
echo runtime scaffolding; and arbitrary self-host evaluation has bounded result
retention but no measured hard process-memory containment.

## Ordered work

1. Preserve raw replies, parse once, and define the exact ordered batch state
   transition for complete, incomplete, read-error, eval-error, async, and
   process-death boundaries.
2. Make async public-function instrumentation validate the awaited value without
   leaking Promises or throwing into the loop.
3. Give plan completion schema'd verification evidence and settle authority for
   authored, addressed, cross-agent, retry, and resume transitions through the
   one `my.plan` mechanism.
4. Remove narration/scaffolding ambiguity by fixing the owning context/runtime
   data rather than rewriting replies.
5. Measure and implement the smallest hard process boundary for arbitrary eval,
   with deadlines, cancellation, memory ceilings, crash attribution, receipt
   fencing, and reconstruction from committed facts.
6. Integrate Inspect tasks/scorers that falsify each transition, then run paid
   or small-model trials only after deterministic runtime gates pass.

## Graduation

- The stored reply is byte-identical to the model reply; every complete form is
  attempted once in order and only committed executions have result facts.
- Promise-returning public functions validate their resolved values, preserve
  `^:async` semantics, and return structured failures without wedging.
- Plan transitions cannot duplicate, reopen, displace, or complete work outside
  their schema'd authority; completion includes queryable verification evidence.
- Ordinary malformed/tool/provider/runtime outputs remain data and the next
  turn can proceed.
- A deliberately memory-hostile eval is terminated at the measured containment
  boundary, the pod/writer remain healthy, durable evidence is honest, and the
  agent can continue or recover without replaying effects.
- Focused pod/writer/operator gates, destructive failure injection, REPL/datoms,
  and deterministic Inspect scorers prove the full transition matrix.

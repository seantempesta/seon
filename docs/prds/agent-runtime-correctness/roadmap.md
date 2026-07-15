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
arms and the stable autocomplete/plan changes are integrated. Program
admission now refuses new agent work before owning effects and stops an eval
batch between entries when publication closes; it returns structured
unavailable data without inventing later eval rows. Hard process death and
memory containment remain separate open boundaries.

The batch path now passes one exact provider string unchanged to the existing
reply blob and parser. Parser classification, not rewriting, selects executable
forms; the result-claim sanitizer and new telemetry writes are gone while
historical installed attributes remain readable. The 95 async contracts that
were structurally excluded from the 747-contract census now pass through the
same exact Promise-aware owner as synchronous contracts. Fixed, variadic,
multi-arity, and multi-plus-variadic functions validate resolved output and
guards; exact-minimum variadic calls receive a removable callable bridge; and
the coverage denominator no longer hides async targets. The outer provider
timeout does not cancel the underlying request; a successful plan step has no
required verification evidence; cross-agent and address-message transitions can reopen or displace
authored work; narration can echo runtime scaffolding; and arbitrary self-host
evaluation has bounded result retention but no measured hard process-memory
containment. Pod-restart recovery is already transactionally fenced and
idempotent, but it is not an isolated eval-process boundary.

The exact ClojureScript `1.12.145` source is now available as official tag
`r1.12.145`, commit `bd23d9a2475d822ea8dfd65deaa6732428b9ed25`, fetched into
the reference checkout without moving its working tree. Exact Shadow `3.4.10`
is release commit `d3c04691952aa9ea33f7287ffe9a2b3109c1e510`; its parent
`2911c908…` is still `3.4.9`. The exact-source audit grounds the analyzer,
compiler, self-host, Shadow bootstrap, and Malli accessor boundaries. Async
implementation is complete in `seon.instrument`, including completion of
Malli's stale in-place unstrument marker cleanup.

## Research evidence

- [[research/agent-runtime-source-audit-2026-07-14]] — dependency ledger,
  live probes, current mechanisms, transition matrix, deletion map, ordered
  slices, and containment decision criteria.
- [[research/raw-reply-preservation-implementation-audit-2026-07-14]] —
  implementation-ready raw-evidence boundary, exact dependency ledger,
  deletion map, deterministic matrix, and live acceptance proof.
- [[research/async-contract-exact-source-implementation-audit-2026-07-15]] —
  exact ClojureScript, Shadow, and Malli identities; executable function-shape
  probes; one-owner implementation plan; and deterministic transition matrix.

## Ordered work

1. **Implemented on this branch:** preserve the exact provider reply through
   the one blob and one parser, delete the result-claim rewrite and new
   telemetry writes, and prove that only parsed real forms create ordered eval
   evidence. The existing admission checks before and between entries remain.
2. **Implemented on this branch:** one Promise-aware function-schema owner now
   covers fixed, variadic, multi-arity, and multi-plus-variadic contracts,
   including injection and guards. The census counts async contracts, resolved
   failures record once, and generation/reconciliation/removal preserve exact
   callable state. Focused proof: `tmp/test-cljs-20260715-012122-80190.log`
   (77 assertions, zero failures or errors).
3. Thread cancellation through the existing provider attempt and adapters while
   retaining one retry owner.
4. Give plan completion schema'd verification evidence and settle authority for
   authored, addressed, cross-agent, retry, and resume transitions through the
   one `my.plan` mechanism.
5. Remove narration/scaffolding ambiguity by fixing the owning context/runtime
   data rather than rewriting replies.
6. Measure and implement the smallest hard process boundary for arbitrary eval,
   with deadlines, cancellation, memory ceilings, crash attribution, receipt
   fencing, and reconstruction from committed facts.
7. Integrate Inspect tasks/scorers that falsify each transition, then run paid
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

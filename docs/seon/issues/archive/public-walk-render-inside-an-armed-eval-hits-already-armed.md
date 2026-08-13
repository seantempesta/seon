---
type: issue
status: resolved
severity: friction
tags: [issue, render, sci, runtime, wave/render-arm]
---

# Public walk render inside an armed eval hits already-armed

## Problem

`public-walk-is-callable-through-an-agent-sci-eval` fails when a selected
render invocation runs while another SCI context is armed: the nested render
call trips `:seon.sci.kernel/already-armed`. An agent calling a walk/render
function from its own eval is an ordinary, ruled-callable path (ruling #20 —
every function in the program graph is callable), so a render invocation must
either run under the requesting eval's existing arm or be handed detached
work through the admitted seam — never attempt a second arm on the same
thread.

## Evidence

Two independent lanes hit it on 2026-08-13: the nested-identity-faces lane's
broader focused gate (87 tests / 457 assertions, this one red retained) and
the gate-reds-pair lane's combined gate ("nested render calls encounter
`:seon.sci.kernel/already-armed`"). The archived
[sci-evaluate-throws-when-a-guarded-context-is-re-armed](archive/sci-evaluate-throws-when-a-guarded-context-is-re-armed.md)
closed the direct re-arm throw; this is the render-selection instance of the
same nesting question, surviving through the selected-render invocation path.

The later six-test `seon.render.web-test` HTTP wedge was independently
reattributed. Its render proc received an incomplete fixture configuration,
treated the resulting typed missing-effective value as a render profile, and
threw while fitting the terminal value; the HTTP request then waited for a
package that could no longer arrive. That wedge adds no evidence to this
armed-eval issue, whose direct SCI regression remains open.

## Owner

`seon.render` selected-render invocation (the render call executed for a
declared producer) meeting `seon.sci.kernel` arm carriage. The fix must be
one mechanism: the nested invocation inherits the live arm (the
`:interrupt-fn` already bounds it) rather than arming again.

## Acceptance

- An agent eval that triggers a selected render (directly or through a walk)
  completes without `already-armed`, and the render work remains bounded by
  the requesting eval's existing `time-limit`.
- One class regression: a render function invoked from inside an armed eval
  and the same function invoked from an unarmed system path both succeed.

## Closure — 2026-08-13

Resolved by `9108d47ab`, corrected and made explicit by `1dbb868e9`:
inheritance first selects only the arm installed in the current thread's
`ThreadLocal`, then requires that arm and the nested context to share SCI's
interpreter `:env`. An environment-carriage wrapper on that thread therefore
inherits the live deadline; a sibling thread sees no current arm and owns an
independent one even when it evaluates the same context; a genuine `sci/fork`
on an armed thread still refuses because it has a distinct `:env`.

The original sibling regression conflated arm independence with interpreter
throughput: its successful sibling had to execute 100,000,000 interpreted loop
iterations before a fixed 10-second evaluation limit, allocating about 3 GB in
the direct reproduction. Under the parallel full gate it could exhaust its own
limit without observing the other thread's arm. The corrected regression arms
both threads before either proceeds, observes one thread's 30 ms interrupt,
then calls the shared interrupt function from the still-armed sibling. It
therefore proves arm identity directly and completes in 35 ms.

Evidence: `bin/test seon.sci.eval-test` passed 61 tests / 359 assertions,
including nested selected-render inheritance, outer time-limit inheritance,
the pinned public-walk test, and sibling independence. The retained full gate
also reported `seon.cluster.agent-test/park-wake-test`; a focused complete
`bin/test seon.cluster.agent-test` passed 18 tests / 176 assertions, with
`park-wake-test` passing in 4.9 seconds, so that failure is not attributed to
arm inheritance.

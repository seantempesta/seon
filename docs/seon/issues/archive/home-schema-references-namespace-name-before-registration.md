---
type: issue
status: resolved
severity: friction
tags: [issue, schema, test, runtime]
---

# Register namespace identity before home schemas reference it

## Problem

`seon.agent.home` registers tuple and function schemas that reference
`:seon.ns/name`, but the only registration of that identity schema lives in
`seon.agent.ctx.render-fns`. A focused render test can load `seon.agent.home`
without loading `render-fns` first, so candidate projection construction fails
on an unresolved schema reference.

Loading `render-fns` explicitly in the focused test makes the test pass but
does not repair the source load-order contract.

## Evidence

During the Stage 1.5 render UI prerequisite, the focused render selection
loaded `seon.agent.home` and failed while building candidates for
`::latest-successful-ns` / `::namespace-assignment`. Those schemas reference
`:seon.ns/name` at `src/seon/agent/home.cljs:67-69`; its sole registration is
at `src/seon/agent/ctx/render_fns.cljs:47`.

The resolved issue [[archive/focused-test-schema-load-order]] establishes the
same general failure mode for a different owner, but its recovery-specific fix
does not cover namespace identity.

## Owner

The namespace identity schema must live in the dependency-neutral first-loading
owner that every referencing namespace can load before registration. Fix the
one existing registration in place and update its durable ownership comment;
do not duplicate the identity shape or make focused tests establish production
load order.

## Acceptance

- A cold focused render test loads `seon.agent.home` and builds the candidate
  projection without explicitly requiring `seon.agent.ctx.render-fns`.
- Normal execution-child boot still registers exactly one
  `:seon.ns/name` identity schema with the same form.
- Focused home, render, and execution-child schema gates pass without relying
  on test namespace order.

## Resolution

Resolved on 2026-07-23. The one `:seon.ns/name` declaration now lives in
`seon.ns.source`, the portable owner of persisted namespace facts.
`seon.agent.home` and `seon.agent.ctx.render-fns` require that owner before
registering schemas that reference namespace identity; no test establishes the
production load order.

The cold JVM falsifier changed from a missing namespace form followed by
`:malli.core/invalid-schema` to the canonical symbol-identity form and a
successful home candidate projection. A source census found one declaration.
The recurring JVM regression passed 1 test / 2 assertions; focused CLJS home,
render-function, and namespace storage gates passed 9/18, 6/24, and 1/6.
Cold `:execution` and `:execution-integration-client` builds also completed.
Full output is retained in `tmp/orchestrator/homeschema-gate.log`.

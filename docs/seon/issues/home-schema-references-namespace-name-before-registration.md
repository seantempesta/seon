---
type: issue
status: open
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

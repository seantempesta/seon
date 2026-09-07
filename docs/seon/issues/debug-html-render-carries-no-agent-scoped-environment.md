---
type: issue
status: open
severity: friction
tags: [issue, render, web, context, call-preparation]
---

# The debug page's HTML render carries no agent-scoped environment

## Problem

A render function may declare `:seon.cluster.agent/id` and let call
preparation supply it from the environment. The AI projection gets that: the
page's source preview forks the SCI context for the calling agent
(`sci.eval/fork-for-turn` in `seon.render.web/render-source-call`), so the
supplier answers. The HTML projection does not: the producer is invoked
directly against the cluster's base context, whose environment carries no
agent, so the call is refused.

On the live `juniper-context` page the `:my.plan/steps` unit shows this as its
whole HTML panel:

```text
{:seon.call-preparation/unavailable true,
 :seon.error/kind :seon.call-preparation/unavailable,
 :seon.error/message
 "Cannot call my.plan/render-plan-html: :seon.cluster.agent/id is unavailable.
  This call's environment carries no agent id; pass one explicitly.",
 :seon.error/data {:seon.fn/sym "my.plan/render-plan-html",
                   :seon.call-preparation/key :seon.cluster.agent/id,
                   :seon.call-preparation/supplier-symbol
                   seon.env/supplied-agent-id,
                   :seon.env/agent-id-absent true}}
```

The same unit's AI panel renders `(my.plan/format-plan-ai (my.plan/plan {}))`
and its result, because that side runs in the agent's fork.

The refusal is honest — nothing hides — but the two projections of one unit
disagree about what the calling agent is, and the PRD asks both projections to
be the same facts for the same agent.

## Where it is decided

- `src/seon/render/web.clj` — `debug-page-result` builds one render request
  per page and hands `(:seon.sci.eval/ctx handle)`, the cluster's base
  context, to every unit's HTML render; only `render-source-call` forks.
- `src/seon/env.clj:300` — `supplied-agent-id` reads
  `:seon.cluster.agent/id` from the environment the context carries.
- `src/seon/render.clj` — the attribute-declared producer path that now
  selects `my.plan/render-plan-html` for `:my.plan/steps`.

## Options

1. Fork once per page for the debug request's agent and hand that context to
   every unit render, AI and HTML alike — one fork per derivation, and both
   projections then agree by construction.
2. Leave HTML unscoped and require HTML producers to take their agent
   explicitly through the attribute-declaration argument.

Option 1 is the one that matches "values carry their world"; option 2 makes
every HTML producer of an agent-owned attribute carry a second argument
shape. The choice belongs to the render owner, not to the page.

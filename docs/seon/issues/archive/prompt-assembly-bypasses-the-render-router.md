---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, render, agent]
---

# Derive prompts through render units

## Problem

The fresh run loop assembles an agent prompt with a dedicated text formatter
instead of deriving the agent's `:seon.render/ai` blocks through
`seon.render/render`. The agent prompt and the human page therefore do not yet
share the one block collection and one projection router required by the UI
architecture.

## Evidence

`src/seon/cluster/prompt.cljc:122-149` formats the interrupted-run section
locally, and `src/seon/cluster/prompt.cljc:160-184` concatenates identity,
warning, trigger, and execution-instruction strings into the complete prompt.
`src/seon/cluster/loop.cljc:609-614` calls that formatter directly.

The production fresh tree has no `seon.render/render` call outside the router
namespace itself; all current calls are tests. The target contract instead
states that `:seon.render/ai` is the prompt consumer and that prompt and page
derive from the same blocks at `docs/seon/architecture/ui.md:47-64,132-145`.

## Owner

`seon.cluster.prompt` together with the N4 `seon.render.block` unit builder and
the cluster render pipeline.

## Acceptance

- Prompt acquisition derives the agent's ordered block set at one immutable
  database value and requests each AI projection only through
  `seon.render/render`.
- The current identity, interrupted-run, trigger, and execution-instruction
  sections are blocks or projections in that same set, not a parallel prompt
  formatter.
- A root agent and an ordinary agent obtain AI and HTML views from the same
  block mechanism, with presence deciding whether each output exists.
- A regression proves that changing one block's projection symbol changes both
  its prompt contribution and its human surface without editing a consumer.

## Resolution

Resolved by the context-blocks wave and verified before this archival commit.
`seon.cluster.prompt/prompt` derives one ordered membership at one immutable
database value and calls `seon.render/render` for every AI declaration.
Identity, execution, peers, namespace, and trigger prose are block projections;
`seon.render.root/blocks` and `seon.render.agent/blocks` are data consumed by
the same membership, unit, router, and surface functions.

The standing context placement property derives prompt contributions and HTML
surfaces from the same planted membership across AI-only, HTML-only, twin, and
omitting declarations. Together with the wholesale block replacement
regression, it proves that projection presence and replacement change the next
consumer derivations without consumer edits. Verification:
`bin/test seon.context-test seon.cluster.prompt-test
seon.render.block-test` — 57 tests, 186 assertions, 0 failures, 0 errors.

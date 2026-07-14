---
type: issue
status: resolved
severity: friction
tags: [issue, component, web]
---

# Operator ready URL queried a retired fleet feed

## Problem

After a successful `bin/seon restart`, the operator printed `/agents` as its
ordinary-agent URL even though the database contained an ordinary agent. A
subsequent `--open` would navigate to a POST-only route instead of that agent.

## Evidence

`seon.dev.cli/ordinary-agent-url` requested the removed `/agents/feed` path,
which redirects to `/`. It therefore found no agent links and used `/agents`
as its fallback. The live root feed at `/agent/root/feed` returned the current
database-derived fleet and contained `/agent/empty-lamps-shop`.

## Owner

The ready/open projection in `script/seon/dev/cli.clj`, consuming the one root
fleet feed defined by the database route set.

## Acceptance

- The bounded probe reads `/agent/root/feed` and selects the first non-root
  agent link.
- With no ordinary link or an unavailable feed, the fallback is the valid root
  page `/`, never the POST-only `/agents` route.
- Operator tests cover both cases.
- A default public restart prints the current ordinary agent URL.

## Resolution

Resolved by `78c544ac`. The complete operator checkpoint passed 100 tests and
592 assertions. A live Babashka invocation against the running default root
feed selected `http://127.0.0.1:7890/agent/empty-lamps-shop`; the regression
also proves that `/agent/root/debug` cannot be misread as an agent id and that
an unavailable feed falls back to `/`.

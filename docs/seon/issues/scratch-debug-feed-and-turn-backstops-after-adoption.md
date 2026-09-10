---
type: issue
status: open
severity: friction
tags: [issue, web, observability, agent]
date: 2026-09-09
---

# Scratch debug feed and turn backstops after adoption

The context page review observed two `:seon.await/backstop-fired` faults for
`:seon.render.web/feed-delta` (30000 ms) and one
`:seon.agent/turn-completion-backstop` (600000 ms, no observable open turn).
They appeared on the isolated `cookbook-page` cluster after repeated development
adoptions and native Chrome debug-page observations, with providers disabled.
They generated fault messages and additional system evaluations in Juniper's
prompt. The saved clean prompt predates those messages.

[Exact error facts](../../prds/context-generation/research/context_page_runtime_faults_2026_09_09.edn)
identify the three observations. The scratch publication was based on
`24adad072` plus the page-review changes; no claim is made about current default
or another lane's code. The cause has not been established. This is distinct
from the existing issue about two turn backstops overwriting each other: no
channel replacement was measured here.

Verify with a fresh scratch fixture and one debug-page subscription, then probe
the declared feed completion event across adoption. Closure requires the
subscription to publish or terminate without a false timeout, and an idle
no-provider agent to avoid a turn-completion timeout with no open turn.

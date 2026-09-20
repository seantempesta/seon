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

## Slice 1 fast snapshot — 2026-09-22 redesign assignment

Snapshot `f08558cb5` plus the one-JVM operator changes, PID 41038, emitted a
background exception at `2026-09-20T18:34:55.933Z` after the publication/adoption
tests had completed. `seon.turn/turn-completion-error` at `src/seon/turn.clj:5008`
called `seon.error/diagnostic` for agent `root`, no observable open turn, and
the declared 600,000 ms completion bound. The armed constructor refused the
observation because `:seon.error/at`, `:seon.error/layer`, and
`:seon.error/operation` were absent. The source call at that line still supplies
`:seon.error/kind` and diagnostic-prefixed members instead of those required
base members. The stack ends in `seon.cluster/projection-executor` on a
virtual thread. No test assertion reported this background exception.

This establishes the diagnostic-constructor boundary, not the cause of the
missing completion or of the earlier erased boot fault. The owning turn
function is outside slice 1 and is unchanged. Acceptance needs both an
honest, contract-valid diagnostic and evidence that stopped/adopted fixture
agents do not leave an unexplained completion backstop. See the
[one-JVM landing note](../../prds/steward-platform/research/one-jvm-redesign-2026-09-22.md)
for the exact fast selection and snapshot boundary.

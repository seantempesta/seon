---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, web]
---

# Pass a valid config singleton to final agent evidence

## Problem

The model-transport evidence path pulled only
`:seon.config.render/database-edn-cap`, then passed that partial map to
`config/database-edn-cap`. The accessor correctly requires the config
singleton shape, whose one required field is `:seon.config/id`.

## Evidence

`POST /agents/run` reached final evidence after a real turn, then Malli rejected
`database-edn-cap` input `{:seon.config.render/database-edn-cap 16384}` for its
missing `:seon.config/id`.

## Owner

`seon.web.serve/project-model-transport-evidence` owns the narrow config pull
and its call to the existing config accessor.

## Acceptance

- The pull includes the singleton identity and the one required cap.
- A focused test asserts the exact pull selector.
- Final agent evidence returns an ordinary response instead of throwing.

## Resolution

Commit `46fcd779` repaired the original render-cap implementation. Later
refactoring removed that cap access but left two model-transport config pulls
without the singleton identity. The current fix makes both historical and
final selectors include `:seon.config/id` and restores the known lookup-ref
identity at the merge boundary before `ai/resolved-config-from-rows` validates
the singleton (`src/seon/web/serve.cljs:1140-1171,1410-1487`).

The owning exact-selector regression is
`final-agent-evidence-pulls-a-valid-config-singleton`; the adjacent empty-window
regression still proves ordinary `{:status "absent"}` projection. Focused proof
on 2026-07-22: 2 tests, 3 assertions, 0 failures, 0 errors; full output at
`tmp/orchestrator/e2e-focused-serve.log`. A live post-fix `/agents/run` returned
HTTP 200 with ordinary final evidence at
`tmp/orchestrator/e2e-drive-evidence/turn-02-retry2-http.txt`.

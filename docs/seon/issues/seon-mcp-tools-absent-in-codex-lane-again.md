---
type: issue
status: open
severity: friction
tags: [issue, mcp, wave/dev-mcp]
---

# Seon MCP tools are absent from a Codex implementation lane again

## Problem

The predictable-reset implementation lane on 2026-09-17 had no callable
`mcp__seon__runtime_status` or `mcp__seon__eval_clj` tool. `AGENTS.md` section
4 requires both tools for live orientation and requires an immediate report
when they are absent.

The earlier issue
`docs/seon/issues/archive/lane-toolset-omits-required-seon-mcp-tools.md` was
resolved on 2026-09-05 after a fresh lane proved both tools. The failure has
therefore recurred in the current Codex tool-registration surface.

The bounded dead-turn-proc visibility lane on 2026-09-18 reproduced the same
tool-registration failure: `bin/seon status` reported the `default` cluster
alive (pid 80593), while neither `mcp__seon__runtime_status` nor
`mcp__seon__eval_clj` was present in the lane's callable tool set. The lane
did not substitute a hand-written prepl transport and recorded the missing
live-proof boundary in its landing note.

## Impact

The lane could verify the operator change only with the canonical fast test
harness and isolated operator roots. It could not perform or claim the required
live JVM/SCI probe. The lane correctly did not operate the shared `default`
cluster while the orchestrator was bringing it up.

## Acceptance

A fresh Codex implementation lane exposes both Seon MCP tools and can obtain a
complete runtime-status envelope plus one bounded JVM evaluation without a
manual transport workaround.

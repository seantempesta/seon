---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, wave/dev-mcp]
---

# Expose the required Seon MCP tools to implementation lanes

## Problem

This implementation lane was not given `mcp__seon__runtime_status` or
`mcp__seon__eval_clj`, even though `AGENTS.md` requires every Clojure change to
begin at the running system through those tools and requires missing tools to
be reported before any workaround.

## Evidence

On 2026-09-03, the callable tool inventory for lane `reap-nil-path` contained
filesystem/process tools but no `mcp__seon__runtime_status` or
`mcp__seon__eval_clj`. `/Users/sean/src/seon/bin/seon status` independently
reported the shared `ctxprobe` JVM alive, so the absence is in the lane tool
surface rather than evidence that no Seon JVM exists.

## Owner

The Codex lane tool-registration surface that exposes Seon's development MCP
server.

## Acceptance

A fresh implementation lane can call both required Seon MCP tools and receive
a complete envelope before resorting to an isolated raw-JVM or operator probe.

## Change — 2026-09-05

`~/.codex/config.toml` now declares `[mcp_servers.seon]` launching the same
`bin/mcp-server` (stdio, `bb -m seon.dev.mcp`) that `.mcp.json` gives
Claude, with `cwd` at the repository root and a 120 s startup timeout for
the Babashka boot. Lanes launched after this change should see
`mcp__seon__runtime_status`, `eval_clj`, and `get_value`; the next lane
summary that reports them available resolves this note, and one that still
reports them missing reopens it with the Codex-side error.

## Resolved — 2026-09-05

A Codex lane launched after the config change reported live MCP
connectivity from inside the lane: explicit `runtime_status` for
`ctxprobe` returned observations and JVM-mode `eval_clj` of `(+ 1 2)`
returned 3 in 2 ms (`docs/seon/issues/…prd-mixes-observation-with-unsettled-design.md`,
"Live orientation evidence"). The declaration lives in
`~/.codex/config.toml` `[mcp_servers.seon]` (a user-level file, not the
repository) — a fresh machine needs the same four lines; recorded in
`docs/seon/reference/driving-codex-agents.md` is the right home if one
does not exist yet.

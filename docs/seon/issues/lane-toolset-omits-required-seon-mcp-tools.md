---
type: issue
status: open
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

---
type: issue
status: open
severity: cleanup
tags: [issue, schema]
---
# seon.sse keyword prefix doesn't match its owning namespace

## Problem

All `:seon.sse/*` keywords are defined and used in `seon.web.sse.flow` (`src/seon/web/sse/flow.clj`), but the prefix is `seon.sse` — not `seon.web.sse.flow` or `seon.web.sse`. There is no `src/seon/sse.clj` file.

This is the clearest namespace-ownership mismatch in the codebase. The keywords should either:

1. Be prefixed `:seon.web.sse.flow/*` (matching the actual namespace)
2. Be prefixed `:seon.web.sse/*` (matching the parent)
3. Have a `src/seon/sse.clj` created to own them

## Scope

~30 references to `:seon.sse/*` keywords in `src/seon/web/sse/flow.clj`.

## File Refs

- `src/seon/web/sse/flow.clj` — all registrations and usage

## Severity

design

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.

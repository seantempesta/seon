---
type: issue
status: open
severity: friction
tags: [issue, render, sci, mcp, class/n1, wave/class-kill-queue]
---

# Contract refusal envelope repeats one blob digest six times

## Problem

A door-mode contract violation for a simple wrong-argument-type call
rendered a ~9 KB envelope in which the same blob digest (`7ccbab2e…`)
appears SIX times at six different paths, burying the one useful sentence
("should be a string") under repeated requery scaffolding. A one-line typed
refusal must render as one line plus one requery identity; shared
substructure in an admitted envelope should be emitted once.

## Evidence

2026-08-13 comprehension test deployment: `mcp__seon__eval_clj` (door) of
`(seon.fn/tests-reaching (seon.db/db) 'seon.cluster.run/open-tx)` — the
symbol-vs-string violation. The envelope repeated the identical elision
value/digest at six paths.

## Owner

`seon.print` fit/elision emission for admitted envelopes (one elision value
per distinct digest, referenced thereafter) and the MCP envelope
projection.

## Acceptance

- A contract refusal for one bad argument renders its message first and
  its evidence once; an identical digest appears at most once per envelope
  with subsequent references by identity.
- One regression pinning the single-emission property.

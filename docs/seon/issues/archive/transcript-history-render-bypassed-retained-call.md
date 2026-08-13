---
type: issue
status: resolved
severity: blocker
tags: [issue, render, prompt, context]
---

# Retain transcript history render calls

## Problem

The transcript history owner assigned stable render-call identity to a family
entity and then invoked `render-ai` directly. That bypassed the captured-call
and retained-value mechanism, so one supposedly identical second prompt pass
reran the transcript family render.

## Evidence

The W1 integration gate failed
`identical-context-skips-second-pass-renderer-invocations`. A focused probe
recorded 17 calls on the first pass and exactly
`[[:seon.render.transcript/history-entity 26494]]` on the second. The W1
history conversion made `seon.render.walk/history` the surviving prompt owner,
so rebuilding the deleted labeled-walk prompt would conceal the bypass.

## Owner

`seon.render.transcript/rendered-family`, at the call into the shared render
owner.

## Resolution

Commit `5084ee42c` calls `render-call` with explicit
`:seon.render/output :seon.render/ai`, preserving the existing stable call id.
The same commit replaces the old labeled-walk and volatile-suffix assertions
with append-only history assertions. `bin/test seon.cluster.prompt-test`
passed 7 tests / 108 assertions with no failures or errors.

The 2026-08-13 complete run found one surviving pre-terminal expectation in
that regression. It joined raw `render.walk/history` bytes and required them
to equal the prompt after the total render terminal had fitted the value. The
terminal correctly rendered a long namespace value as a bounded printed
string, so byte identity across that boundary is no longer the contract. The
test retains the direct ordered-history assertions and verifies that prompt
text is exactly the terminal contribution consumed by the model.

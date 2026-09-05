---
type: issue
status: open
severity: friction
tags: [issue, render, wave/agent-context]
---

# Check a render candidate's input and output on the same arity

## Problem

Candidate discovery can accept a function when one arity accepts the argument
and a different arity declares the requested output. No callable arity then
satisfies the combined claim.

## Evidence

Live JVM MCP probe on default, basis 536871002, 2026-09-05:
[script](../../prds/context-generation/research/scripts/design-lab-orientation-2026-09-05.clj).
Contract: int -> int, and two strings -> string. Input [7] passes
function-accepts-in? and requested output :string passes function-returns-in?;
the same-arity conjunction is false. No trial definition was installed.
Source: src/seon/schema.clj:3080-3131 and src/seon/render.clj:178-207.

## Owner

seon.schema's contract checks and seon.render candidate selection.

## Acceptance

One arity must satisfy both conditions, including supplied defaults. A regression
rejects the demonstrated false candidate and accepts a genuinely matching arity.
The inspection table identifies the selected arity and reasons for rejection.

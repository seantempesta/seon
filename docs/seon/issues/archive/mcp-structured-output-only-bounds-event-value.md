---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, repl]
---

# Bound every string in an MCP structured response

## Problem

The MCP bridge advertises an output-token budget, but structured response
trimming can reduce only `:seon.dev.mcp/events N :val`. A large terminal
`:form`, exception field, or other string remains untouched. Once every `:val`
is empty, the encoder returns the oversized envelope, so a valid evaluation
request can exceed the requested response budget by an arbitrary amount.

## Evidence

- `script/seon/dev/mcp.clj:46-60` establishes the requested character estimate.
- `script/seon/dev/mcp.clj:72-115` searches and trims only event `:val`, then
  returns the still-oversized encoding when no such value remains.
- `script/seon/dev/mcp.clj:485-495` likewise bounds only `:val` before events
  enter the response.
- `test/seon/dev/mcp_bridge_test.clj:155-207` proves large values and envelope
  preservation, but all non-`:val` strings are small and the minimum-budget
  test does not assert encoded size.
- `tmp/audit-20260801b/src/mcp_exception_probe.clj` supplied a 20,000-character
  terminal `:form` at a 128-token request. The 512-character estimate produced
  a 20,150-character response.

## Owner

The source-independent MCP response projection in `script/seon/dev/mcp.clj`.

## Acceptance

- Every structured response has a finite bound derived from the requested
  output budget, including all event and envelope fields.
- Required terminal identity survives truncation with explicit retained/total
  evidence.
- A regression uses an oversized `:form` and asserts the complete encoded
  response stays within the declared bound.

Resolved by `5a83efc2e`. Structured MCP encoding now bounds all string
fields while retaining the terminal envelope and retained/total
evidence. The oversized terminal-field regression passes.

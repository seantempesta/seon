---
type: issue
status: resolved
tags: [issue, agent, flow]
severity: friction
---

# Two `seon.agents-test` ALS tests fail under the MCP REPL harness

## Problem

`cross-await-binding-survives` and `multi-agent-interleaving-keeps-atoms-distinct`
(in `test/seon/agents_test.cljs`) fail when run via `mcp__seon_cljs__eval`:

```
No protocol method IDeref.-deref defined for type null
```

The AsyncLocalStorage `*ctx*` binding goes nil across an `await` under the MCP
eval harness, so a `@*ctx*`-style deref hits null. The test file itself flags
(~line 155) that it was "VERIFIED STATICALLY ONLY in this session (MCP cljs
offline). Awaiting live REPL run."

## Assessment

- **Pre-existing + environmental, NOT a code regression.** Confirmed unrelated to
  the 2026-06-08 context + store-time-cap work (those touch `seon.agent`/
  `seon.eval` string capping; these tests deref ALS `*ctx*` in `seon.agents`).
  Real pod turns + the new `memory_safety_test` use `await` + ALS fine.
- The MCP eval harness does not reproduce the pod's ALS context-reattachment
  across `await`, so these two tests can only be validated by a real in-pod run.

## Acceptance criteria

- Run the two tests in a real pod turn (not MCP) and confirm pass; OR
- Make the tests harness-agnostic (don't depend on ALS surviving the MCP eval
  boundary); OR
- If ALS genuinely doesn't survive a real `await` either, that's a deeper
  `seon.agents` bug — investigate `*ctx*` reattachment.

## Refs

- `test/seon/agents_test.cljs` (~line 155 self-flag)
- `seon.agents` ALS `*ctx*` / `with-agent` machinery
- Surfaced while verifying [[eval-memory-safety]] (full suite run).

## Resolution (2026-06-28 audit)

Closed RESOLVED/STALE per `docs/seon/issues-audit-2026-06-28.md`: the
`seon.agents` namespace + its test were deleted (`248f2193`); ALS now lives in
`seon.agent.*`, so the two failing `agents_test` cases no longer exist.

---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, repl, sci, render]
---

# Bound top-level string results before returning the MCP face

## Problem

An oversized top-level string is stored as a retrievable blob, but the MCP
SCI evaluation response also returns 262,147 characters inline. The envelope therefore
claims `:seon.dev.mcp/windowed? true` while bypassing its own value window at
the most common scalar edge.

## Evidence

On 2026-08-04, isolated cluster `edgefaces0804` evaluated this through the
real SCI evaluator:

```clojure
(apply str (repeat 1048576 "x"))
```

The bounded summary of the returned envelope was:

```clojure
{:edge/original-count 1048576
 :edge/result-edn-count 262220
 :edge/text-count 262147
 :edge/artifact-size 262265
 :edge/capped? true
 :edge/windowed? true}
```

The blob was retrievable at digest
`cfbbec8053dd361e864119a55d5c887b55261f4728a70153fe510415158ad261`,
so the inline bulk was unnecessary. `src/seon/cluster.clj:283-287` derives
`projected-node`, but the evaluation arm at `src/seon/cluster.clj:291-293`
passes the original `evaluation-print-node` to `evaluation-face` instead.
The existing `nested-bulk-is-bounded-by-the-shared-value-window` regression
in `test/seon/cluster/mcp_test.clj` constructs an evaluation-shaped value whose
`result-edn` is not a print node, so it does not exercise this arm.

## Owner

`seon.cluster/mcp-project` owns the one MCP value-window decision for SCI evaluator
evaluation faces.

## Acceptance

An admitted one-megabyte top-level string remains blob-retrievable, its inline
MCP envelope stays below the configured threshold, and the face supplies the
same digest and size needed to retrieve the remainder. A regression enters
through an actual evaluation print node rather than a generic nested value.

## Resolution

Resolved by the path-limited `Fix MCP scalar value window` commit. The eval
arm now passes its already fitted `projected-node` to `evaluation-face`; the
non-eval arm continues to use that same node.

The focused `seon.cluster.mcp-test` gate passed 9 tests / 44 assertions with
zero failures or errors. Its P5 regression enters through an evaluation print
node, asserts an order-of-magnitude reduction rather than an exact face size,
and independently derives the complete artifact digest and size.

Live proof on scratch cluster `mcp-window-fix-0804` evaluated
`(apply str (repeat 1048576 "x"))` through `eval_clj` before and after a loaded
Var reload. The complete MCP response fell from 263,048 to 2,953 characters
and its visible face fell from 262,147 to 2,051. Both runs retained digest
`cfbbec8053dd361e864119a55d5c887b55261f4728a70153fe510415158ad261`,
artifact size 262,265, `capped? true`, `windowed? true`, and
`retrievable? true`. This proves the loaded Var behavior; it does not claim a
current-source refork.

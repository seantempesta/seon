---
type: issue
status: open
severity: blocker
tags: [issue, ai, render, class/n11, wave/provider-context]
---

# Route failover context through the captured rendered history

## Problem

A backup-provider attempt receives a separately assembled `system` message in
addition to the captured agent prompt. The primary failure is rendered after
the prompt capture commits and is inserted directly into the provider request,
so the backup does not receive the one rendered-history value whose exact bytes
the database records.

This violates strict dogfooding: every agent-facing context byte must come from
the neighborhood walk, declared render functions, and executed receipts. A
declared error render used as an uncaptured provider-only slot is still a second
context assembly path.

## Evidence

`src/seon/cluster/loop.clj:1218-1223` conditionally adds
`:seon.ai/system` beside the already captured `:seon.ai/prompt`.
`src/seon/cluster/loop.clj:1261-1281` constructs that string by rendering the
new primary-error fact and recurs into the backup attempt. The durable capture
stores only `:seon.cluster.prompt/text` at `src/seon/context.clj:162-187`.

The provider boundary preserves the split: `src/seon/ai.clj:625-640` emits a
system-role message followed by the user-role prompt, and
`resources/seon/schemas/seon.ai.edn:143-200` declares the optional system field
on both authenticated and no-auth request shapes.

The target contract says one ordinary prompt result flows unchanged through
context capture, every retry, and the provider boundary
(`docs/seon/architecture/context.md`, "Configuration"). Ruling 28 in
`docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md`
forbids manually assembled context outside the walk, declared renders, and
executed receipts.

## Owner

`seon.cluster.loop` owns attempt reduction and `seon.ai/request-body` owns the
provider request document. The render proc owns the only context history.

## Acceptance

- Primary and backup attempts receive one byte-identical rendered-history
  value from the captured prompt contract; no call site can add a provider-only
  context slot.
- If the primary failure must enter the backup's context, it first becomes an
  ordinary history observation through the same walk/render path and the exact
  resulting bytes are captured before transmission.
- A regression compares the capture with the complete context-bearing portion
  of both primary and backup sent bodies and proves there is no uncaptured
  system fragment.

## Re-verified at HEAD (2026-09-15)

Basis: `7e35df2131c71f476a85c6a38bfc8eb292cb36f5` (committed source).

UNVERIFIABLE-WITHOUT-GATE dynamically in this triage. The mechanism remains at HEAD: `src/seon/turn.clj:4161` captures once, `:4204` adds `:seon.ai/system`, and `:4247` derives the backup fragment after recording the primary failure. `src/seon/ai.clj:687` emits it as a separate system-role message. Existing `test/seon/cluster/turn_test.clj:2531` deliberately asserts that split; the selected namespace was launched before the no-JVM correction, but no terminal verdict was obtained. A supported pure request-body probe without a handed projection refused `seon.schema/missing-projection`; repeating it inside `schema/call-with-projection` using `projection-from-database` timed out at 10,000 ms. Neither is a failover reproduction. A completed canonical virtual-primary-failure run comparing capture and both requests is required; no paid call was made. This remains a blocker for truthful generated context.

surface: context-generation

Required namespace: `seon.cluster.turn-test`, especially `an-unpaid-failure-with-a-backup-makes-exactly-two-calls`. Its existing split-context assertion must be treated as evidence of this issue, not as the wanted invariant. No new JVM launch is permitted.

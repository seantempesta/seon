---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, capture, schema, history]
---

# Captured prompt history is disabled

## Problem

`:seon.context.capture/prompt` is exact evidence of a previous provider context,
but its `:seon.db/no-history? true` declaration discards that evidence after
replacement or retraction. Keeping the capture identity in temporal history
cannot reconstruct the missing prompt bytes.

This is R J6 in the [schema review](../../prds/steward-platform/research/schema-design-review-2026-09-17.md),
tracked for the one reset in the [integration plan](../../prds/steward-platform/plan/reset-batch-2026-09-17.md).
It is distinct from a current capture disagreeing with re-rendered prompt
selection: the value here is absent from the historical database itself.

## Evidence

Before the edit, a read-only MCP JVM probe of default found 31 captures and
installed `:db/noHistory true` on the prompt attribute (7 ms). The canonical
`seon.context-capture-history-test/retracted-capture-retains-the-exact-prompt-in-history`
constructs the agent/turn through their owners and the capture through
`seon.context/capture-tx`, using `seon.test-support/transacted!` for every write.

Red fast run: 1 test, 6 assertions, 2 failures, 0 errors. Current prompt and
character count passed. After capture retraction, as-of returned no prompt and
the historical added/retracted prompt datoms were both absent.

After removing the leaf's no-history declaration, the same HEAD-plus-owned-paths
armed fast regression passed: 1 test, 6 assertions, 0 failures, 0 errors
(2026-09-16T23:34:36Z). Live publication was refused at source schema admission
for unresolved predicate `seon.search/handle?`; the change is not claimed live.

## Owner

Reset-batch integration, group 5. Only the prompt leaf declaration and this
new canonical regression are production/test changes for the seam. Remove
no-history and document the evidence semantics; preserve the existing capture
writer and identity. No repair can restore bytes already discarded by default.

## Acceptance

The same armed fast regression passes with exact prompt bytes in as-of and
both added/retracted history datoms, while the current capture is absent.
The orchestrator's one reset installs the declaration and supplies the cold
and live proof. Keep this issue open until that reset-boundary verification;
a fast fixture result alone does not close the live boundary.

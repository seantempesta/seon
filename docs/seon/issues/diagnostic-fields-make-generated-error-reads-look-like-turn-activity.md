---
type: issue
status: fix implemented; fixture and live proof pending
severity: blocker
tags: [issue, turn, schema]
---

# Diagnostic fields make generated error reads look like turn activity

The read-only default SCI preview reproduced the root boot refusal for
`:seon.cluster.eval/source` and `:seon.turn/rule`. Five error observation pulls
name these attributes on occurrence entities. The namespace-analysis error
facet reused the evaluation source attribute; the diagnostic rule also carried
the context-inert property. Neither requires a read-evidence or retry change.

The owner fix uses `:seon.fn/source` in the analysis diagnostic and removes the
activity property from the refusal rule. The root regression is blocked by the
published fixture's missing `:seon.turn/invalid-disposition-error` schema.
Do not close on source inspection alone. See the exact probes and limits in
[the landing note](../../prds/agent-platform/landing/lane-turn-parks-on-boot-2026-09-22.md).

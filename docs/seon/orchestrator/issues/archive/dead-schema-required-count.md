---
type: issue
status: resolved
severity: cleanup
tags: [issue, schema]
---

# Dead production code: schema-required-count + *schema-required-counts

## Problem

`schema-required-count` and the `*schema-required-counts` atom are dead
production code — the only consumer is a test. They add a stateful registry that
nothing in the running system reads, contradicting "derive, don't store."

## Where

- `seon.schema` — `schema-required-count` fn + the `*schema-required-counts`
  atom.

## Acceptance Criteria

- The fn + atom deleted (or the single dependent test updated/removed).
- No production reader remains; full suite green.

## Related

- [[components/schema-system]]

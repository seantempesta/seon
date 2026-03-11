---
type: issue
status: open
severity: friction
---
# Duplication: ::namespace Schema Registered 20+ Times

## Problem

`::namespace` schema is registered 20+ times across the codebase. Same pattern as `::db-name` -- every file that needs a namespace schema registers its own copy. Schema changes require updating 20+ files.

## Where

- 20+ separate files across the codebase, each with their own `schema/register!` for `::namespace`

## Acceptance Criteria

- Single canonical `::namespace` schema registration (in `seon.schema` or similar)
- All files reference the shared schema
- Changing the namespace schema only requires one edit
- Tests pass

## Related

- [[components/schema-system]]

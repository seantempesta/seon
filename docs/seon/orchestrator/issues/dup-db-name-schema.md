---
type: issue
status: open
severity: friction
milestone: M2
tags: [issue, schema, database, architecture, jvm-track, paused]
---
# Duplication: ::db-name Schema Registered 14 Times

## Problem

`::db-name` schema is registered 14 times across the codebase. Every file that touches the DB registers its own copy of `[:enum :seon :seon.runtime :seon.ai :seon.flow]`. Adding a new database requires updating 14 files.

## Where

- 14 separate files across the codebase, each with their own `schema/register!` for `::db-name`

## Acceptance Criteria

- Single canonical `::db-name` schema registration (in `seon.db` or `seon.schema`)
- All 14 files reference the shared schema instead of registering their own
- Adding a new DB name only requires changing one file
- Tests pass

## Related

- [[components/database]]
- [[components/schema-system]]

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.

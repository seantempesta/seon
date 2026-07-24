---
type: issue
status: open
severity: friction
tags: [issue, schema, database]
---

# Seon database store ID needs a named predicate schema

## Problem

`:seon.db/db` currently models `:store-id` as `[:vector :any]`. This is a legal
core-admitted third-party boundary, so it does not block admission, but the
slot remains less descriptive and less generative than the rest of the
ordinary database-value contract.

## Owner

The schema-strictness census backlog, coordinated with the maintained
Datahike/Proximum store-ID forms.

## Acceptance

- Define one named predicate schema for every supported store-ID shape, with a
  bounded error message and a generator override.
- Replace only the `:seon.db/db` store-id element with that registered schema.
- Generated values validate, and representative self-writer and remote
  database values validate through the ordinary database-value contract.

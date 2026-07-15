---
type: issue
status: resolved
severity: friction
tags: [issue, database, web, architecture]
---

# Keep Datahike system attributes out of the domain navigator

## Problem

The `/data` browser labeled `:dh.ref/*` as domain data even though those
attributes are part of Datahike's implicit system schema. The default
navigator therefore presented database implementation metadata as an
agent-authored domain.

## Evidence

The selected Datahike source registers `:dh.ref/db`, `:dh.ref/attr`,
`:dh.ref/value`, `:dh.ref/temporal`, and `:dh.ref/type` in
`datahike.schema/implicit-schema-spec`; reified references use those
attributes. A read-only default-cluster probe on 2026-07-14 returned `:dh.ref`
ahead of the downstream `my.*` domains.

## Resolution

Resolved in database-browser Slice B on 2026-07-15. The one
`seon.db.browser/system-attribute?` classifier now includes the selected
Datahike `dh.ref` namespace. The focused grouping regression proves the
default domain view omits it while the explicit system view retains it; no
entity-data scan or second web-only classifier was introduced.

---
type: issue
status: open
severity: friction
tags: [issue, database, web, architecture]
---

# Keep Datahike system attributes out of the domain navigator

## Problem

The `/data` browser labels `:dh.ref/*` as domain data even though those
attributes are part of Datahike's implicit system schema. The default
navigator therefore violates its own user/domain-first contract and presents
database implementation metadata as an agent-authored domain.

## Evidence

`seon.db.browser/system-attribute?` recognizes only `db`, `db.*`, and `seon.*`
namespaces. The selected Datahike source registers `:dh.ref/db`,
`:dh.ref/attr`, `:dh.ref/value`, `:dh.ref/temporal`, and `:dh.ref/type` in
`datahike.schema/implicit-schema-spec`; `:dh.ref/db` and `:dh.ref/value` are
AVET-indexed system attributes used for reified references.

A read-only default-cluster probe on 2026-07-14 returned `:dh.ref` as the first
of five supposed domain namespaces, and the server-side `/data/feed` frame
rendered a `:dh.ref` navigator row ahead of `:my.kb`, `:my.plan`, and
`:my.skills`.

## Owner

`seon.db.browser` owns presentation grouping, but the classification must be
derived from one source-grounded Datahike/Seon system-attribute rule rather
than another growing string-prefix list. Do not hide the attributes from the
explicit system view.

## Acceptance

- The default navigator contains agent/downstream domain attributes and omits
  all installed Datahike and Seon framework attributes.
- The explicit system view still reaches `:dh.ref/*`, `:db*`, `:seon.*`, and
  any future dependency-owned system attributes.
- Focused tests install representative `:dh.ref`, `:db.secondary`, Seon, and
  `my.*` attributes and prove the grouping without scanning entity data.

---
type: issue
status: resolved
severity: friction
tags: [issue, operator, boot, schema]
---

# Steer incompatible sovereign schema refusals

## Problem

The correct incompatible-schema guard reports a bare request to reset the
cluster. It does not name the affected cluster, the incompatible attribute,
or the two explicit operator choices, leaving a human without safe steering
at a destructive boundary.

## Evidence

- `src/seon/cluster.clj` `declaration-changes` carries the attribute plus the
  installed and current declarations in structured offense data.
- Reopening `default` correctly refused the non-accretive change of
  `:seon.ns/requires` from symbol-many to ref-many, but the message was only
  `The cluster schema cannot be changed in place; reset it.`

## Owner

`src/seon/cluster.clj` schema-accretion refusal boundary.

## Acceptance

The guard remains unchanged, while its operator-facing error names the
cluster and incompatible attribute and steers to explicit destructive refork
or export/import preservation choices. A focused regression asserts the
structured offense and actionable commands.

## Resolution

Commits `5938783ce` and `edfde01ce` preserve the strict sovereignty guard and
replace its bare reset instruction with the affected cluster and attribute
plus both explicit resolutions: destructive `bin/seon init NAME --force`, or
export/import to preserve data. The regression enters the real persisted boot
path with an incompatible declaration and asserts the structured offense and
operator-facing message. `seon.cluster.boot-test` passed 25 tests / 123
assertions with zero failures and errors.

---
type: issue
status: resolved
severity: blocker
created: 2026-09-18
tags: [issue, errors, schema, manifest]
---

# Error manifest replacements require constructor coordination

## Problem

The slice-1 assignment requires exact same-key manifest replacements while
retaining old declarations and forbidding constructor changes. Replacing
`:seon.error/value` with the new base invalidates unchanged constructors that
promise that contract. A duplicate declaration cannot preserve both shapes.

## Evidence

The [slice-1 dependency note](../../../prds/steward-platform/research/error-declaration-manifest-2026-09-18.md)
records the static EDN comparison (280 changed existing keys), exact source
consumers, and a 6 ms live wrong-arity probe missing all three required new
base members. No production edits or test success are claimed.

## Owner

The error-entities batch orchestrator coordinates declaration replacement with
constructor, wrapper and recorder consumers. This is an assignment sequencing
dependency, independent of concurrent source changes.

## Acceptance

An explicit slice boundary states when same-key replacements and their
consumers land together. The admitted constructor outputs validate against
those exact contracts under the canonical armed fixture; the real wrong-arity
refusal satisfies the arity facet without fabricated observations. Retaining
the old base alternative or weakening the new base does not meet acceptance.

## Resolution — owner ruling, 2026-09-18

The owner accepted additive slice 1: only new declarations plus expansion and
checker rules; old declarations stay untouched. The 280 measured same-key
replacements, including `:seon.error/value`, move to the PRD §6 constructor
groups. Each group lands its replacements together with constructors and
recorder changes, all in the one reset. Omit `:seon.error/result` under §1q.
The sequencing dependency is resolved by this explicit assignment; no runtime
implementation proof is implied. The additive implementation continues.

Resolution commit: `989a2d4b2`. Proof of this sequencing resolution is the
explicit additive/constructor ownership split in PRD §6; runtime proof belongs
to those implementation slices, not this issue.

---
type: issue
status: resolved
severity: blocker
tags: [issue, render, database, agent]
---

# Generic reference identities introduce turn-taking dependencies

## Root cause

On default PID 7595, `(seon.plan/plan {})` produced 29 read-evidence
entries, 14 of which pulled every installed identity while rendering a
polymorphic plan subject. The selector included context-inert turn,
evaluation and attempt identities. The generated-read check correctly refused
the resulting system turn.

The introducing behavior is `563034709`: structural AI map rendering exposed
the existing `seon.render.value/reference-identity` path. A live comparison
using only its parent `value-node*` definition gave 15 entries and no refusal
on the same database; the entering definition was restored in `finally`.
The fourteen offending entries were standalone value-renderer pulls, not
the initially suspected nested walk pulls.

## Resolution

`474234fb7` derives context-inert attributes through
`seon.cluster.wake/inert-attributes`, shared by the existing generated-read
check and both generic render identity selectors. The selectors exclude these
attributes; the check retains its refusal semantics. No read evidence is
discarded and no dependency fork changed.

Live proof with the REPL-loaded forms: Juniper fixture install returned system
turn `aa071259cfd8`; prompt acquisition returned 9,778 characters. Its stored
opening held 10 evaluations and 32 read-evidence entries, with no explicit
dependency set containing a context-inert attribute. Already supplied identity
maps remain unchanged; a generic ref with only an inert identity falls back
to its entity ID.

The existing canonical `running-fixture-settles-its-seeded-wake` regression
now checks a nonempty opening containing the plan and disjoint read attributes.
The isolated gate remains requested: initial in-process runs failed before
the body because of the shared fixture's failed delay; a later run hit its
20-second execution bound. Neither result is green.

Exact probes, requests, first 40 opening lines, commits and adoption boundaries:
[generated-read-identities landing note](../../../prds/steward-platform/research/generated-read-identities-2026-09-16.md).

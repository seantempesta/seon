---
type: issue
status: open
severity: blocker
tags: [issue, database, datahike, render, caching]
---

# Include automatic component expansion in Datahike pull dependencies

## Problem

Pulling a forward component ref without an explicit subpattern automatically
expands the component entity, but `pull-dependency-plan` records only the named
component ref attribute. A child-attribute-only transaction can therefore
change the pull result without advancing any retained dependency revision.

## Evidence

- `reference-code/datahike/src/datahike/pull_api.cljc:18-37` recursively records
  explicit subpatterns and otherwise only `:attr`.
- `reference-code/datahike/src/datahike/pull_api.cljc:187-233` detects a forward
  component ref and expands it with a wildcard `PullSpec` even when the parsed
  selector has no subpattern.
- `reference-code/datahike/src/datahike/pull_api.cljc:53-69,437-454` publishes
  that narrower plan as the evidence for `pull` and `pull-many`.
- `src/seon/db.clj:262-270,316-343,395-415` trusts the published plan for
  retained read currency before falling back to replay. If the omitted child
  attribute revision is unchanged in the plan, replay is not reached.

## Owner

The maintained Datahike pull dependency projection. Seon must not add a second
pull analyzer.

## Acceptance

- A component ref without a subpattern either widens the dependency plan to
  `:all` or derives the concrete expanded attributes from schema semantics.
- A generated regression changes only an automatically expanded component
  child's attribute and proves that changed pull result implies changed
  dependency revision/replay.
- Explicit nested selectors remain concrete; reverse refs and recursion retain
  their current canonical attributes.

---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, wave/agent-context]
---

# Juniper component reseeding refuses existing step identities

On 2026-09-08, the canonical fixture's `install! "default"` refused:

```text
Conflicting upsert: "step-render-plan" resolves both to 34903 and 68715
```

A subsequent pull showed Juniper's id but no `:seon.agent/plan` component.
The fixture uses forward tempid references alongside existing unique step
identities. The transaction refusal did not install the component.
The owning file is
`docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj`.

No data migration or refork was attempted in this observation: development
publication separately refuses loading `seon.test-support` from the live
classpath. The fresh-fork fixture proof and repeat-install proof remain
unfinished. Acceptance: fresh default can seed the new component shape,
and repeating the fixture installation does not refuse or duplicate it.

Resolved 2026-09-08: the fixture resolves existing identities at the writer's
transaction database and reuses the plan component. Default installed it,
then a second installation returned normally; the subsequent query still
returned plan entity 73475 with four root steps. The canonical component
tests and value-renderer tests passed together, 41 tests / 181 assertions.
The updated HTML renderer preserves component data rather than replacing
it with identity refs. No schema refusal or refork was needed. Evidence:
[record-render landing](../../prds/context-generation/research/record-render-landing-2026-09-08.md).

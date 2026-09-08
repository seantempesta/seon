---
type: issue
status: open
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

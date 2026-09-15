---
type: issue
status: open
severity: friction
tags: [issue, runtime, adoption]
---

# Adoption probe emits an invalid root namespace lookup

## Problem

A real armed child-JVM adoption probe emitted a Datahike entity-id syntax
error for the string `my.agents.root`. The caller has not been identified;
this may be an omitted fixture input and is not attributed to adoption code.

## Evidence

At 2026-09-15T18:20:03Z, the child running
`seon.adoption-contract-freshness-test/probe!` logged:

```text
Expected number or lookup ref for entity id, got "my.agents.root"
data: {:error :entity-id/syntax, :entity-id "my.agents.root"}
```

The test continued to its separate intended mixed-contract assertion. Full
output is retained in
[the regression log](../../prds/context-generation/research/adoption-contract-freshness-regression-before-2026-09-15.log).

## Owner

Unconfirmed. Verify the caller and the fixture's namespace input before
assigning a production owner.

## Acceptance

Reproduce with a complete namespace input and identify the caller. Correct
its lookup shape or the fixture input, and verify that the ordinary adoption
probe no longer emits this error.

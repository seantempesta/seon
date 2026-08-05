---
type: issue
status: open
severity: friction
tags: [issue, schema, testing]
---

# Resolve schema aliases within one admitted declaration set

## Problem

The supported-AST property cannot admit a three-step alias chain because the
middle alias cannot resolve the base declaration supplied in the same test
population.

## Evidence

The bare 2026-08-05 gate failed
`seon.schema.datahike-test/supported-ast-wrappers-and-aliases-have-one-declaration`
with the shrunk refusal:

```text
Schema population refused :seon.schema.datahike-test/alias-middle
(unresolved-reference).
definition: :seon.schema.datahike-test/alias-base
```

The focused reproduction at pre-rename commit `401fd300e` failed with the
same alias identities and unresolved-reference data. The test.check failure
then prints the complete throwable twice—under both `:result` and
`:result-data`—which makes the otherwise concise refusal span several
kilobytes.

## Owner

The dependency-aware declaration admission in `seon.schema.edn/admit` and the
property's declaration-set fixture.

## Acceptance

Declarations in one admitted set resolve references in dependency order, or
refuse once with exact cycle/missing-reference data when they cannot. The
three-step alias property passes and its failure face does not duplicate a
complete throwable.

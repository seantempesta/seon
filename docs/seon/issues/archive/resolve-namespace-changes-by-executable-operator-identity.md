---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, reader, program-graph]
---

# Resolve reader special forms by executable operator identity

## Resolution

`seon.sci.reader/resolved-operation` resolves the executable operator through
the current namespace context. Qualified lookalikes and quoted forms are inert;
core namespace changes and declaration macros retain their semantics. The
recurring regression
`declaration-and-namespace-semantics-use-resolved-operator-identity` proves
both directions.

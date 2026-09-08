---
type: defect
status: open
severity: friction
tags: [program-graph, schema, ruling-47]
---

# A program identity row pulls nil

Ruling 47: program identity rows never retract. `verify-p1-p6-and-backlog`
(production defect 3) observed a program identity row that pulls nil on an
instrumented cluster. Either the row was retracted (a ruling violation at
the writer) or the pull reads an identity the population never minted (the
population invariant). Reproduce, name which, fix at the writer.

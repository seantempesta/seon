---
type: issue
status: open
severity: blocker
tags: [issue, render, debug-page, runtime, turn]
---

# The debug page shows "0 evaluations · fresh" and an empty "Context now" after turns moved under the runtime component

Observed 2026-09-09 19:18 on `default` (twelfth refork, `ae0e54841`),
Juniper reseeded: the record holds turn `aa071259cfd8` under
`:seon.agent/runtime` → `:seon.runtime/turns` with eight evaluations
(help, identity pull, plan pull, inbox reverse pull, settings pull, notes
reverse pull, `dir`, the data count query), yet the page header says
"0 evaluations · fresh", "Context now" is empty, and the would-be system
turn lists every form as `:unchanged` against read basis 536870980.

The page (or `seon.eval/of-agent` behind it) still reaches evaluations
through the retired relationship (`:seon.turn/agent`) instead of the
runtime component's turns. Same class as the concern-block duplication:
the page reads a shape the writer no longer produces. Fix at
`seon.eval/of-agent` (one query over `:seon.runtime/turns` in order) and
add the page regression on the canonical fixture: seeded opening →
"8 evaluations · continuing" and the entries in "Context now".

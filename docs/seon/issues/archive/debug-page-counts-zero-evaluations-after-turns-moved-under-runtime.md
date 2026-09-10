---
type: issue
status: resolved
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

Partial resolution `fd8646edd`, 2026-09-09: The evaluation and current-turn
queries follow agent/runtime/turns. This did not resolve the page: its
newest-turn filter still hid the accumulated context after an empty turn.

Follow-up, 2026-09-09: The page now consumes every ordered evaluation from
`seon.eval/of-agent`. System-turn comparison and next-turn identity count
also follow runtime-owned turns. The canonical socket/SCI regression
removes legacy edges and checks the opening, an empty closed turn, and a
wake append. Exact gates and default adoption evidence are in
`docs/prds/context-generation/research/page-runtime-read-landing-2026-09-09.md`.

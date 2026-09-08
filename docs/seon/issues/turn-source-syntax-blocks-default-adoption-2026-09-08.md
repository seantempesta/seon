---
type: issue
status: open
severity: friction
tags: [issue, runtime, source]
---

# Turn source syntax blocks default adoption

Observed by record-render on 2026-09-08. The main-root command
`bin/seon init --dev default --changed src/seon/render/web.clj --changed test/seon/render/web_test.clj --changed src/seon/ai.clj`
refused static program publication with `:seon.fn/index-refused`.
The blocking finding is `src/seon/turn.clj:135:63`,
`Unmatched bracket: unexpected )` (`:syntax`, `:error`).
The source owner is the concurrent turn-cut lane; record-render did not
edit it or operate its session. The captured command output is
`tmp/record-render-publication7.log`. This blocks live source convergence,
not the owned-path snapshot gate. Juniper's debug GET was independently
200 after the committed render-input repair.

The lane's stop boundary and unfinished proofs are recorded in
[the landing note](../../prds/context-generation/research/record-render-landing-2026-09-08.md).

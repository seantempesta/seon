---
title: "Card RF-6 Friction from the repl-first dogfood run"
type: reference
status: active
created: 2026-10-08
---

# RF-6 Friction from the repl-first dogfood run

Each row is one task on its owner, with the exact form and reply in
`docs/research/agent-platform/2026-10-08/repl-first/repl-first.md` §5. Lane: Sonnet per row unless marked.

| # | Owner | Friction | Done when |
|---|---|---|---|
| 1 | `my.program/tests-reaching` | refuses a bare symbol; the contract example prints `#object[datahike.db.DB …]` and subject `tV` | a symbol is accepted (supplied db) and examples never print host objects |
| 2 | `my.program/diff` | empty in the same eval request as the defn | answers the request's own defns (or the reply says it reads the settled branch) |
| 3 | `seon.test/changes` | `(my.test/check {})` ran `scratch.redtriage/probe-observed`, which reaches nothing changed | changes compare against main's head, not the published program |
| 4 | `my.agent` | no way to read my connection / branch database | `(my.agent/branch)` answers it, or a documented `my.*` read |
| 5 | SCI `def` | a def whose init threw leaves an unbound Var; deref answers `ClassCastException SciUnbound` | the deref names the unbound Var |
| 6 | `seon.test.runner` (Opus) | a failing test prints the clojure.test report and 40 frames into `:out` (1,075 tokens) | the report is data through the value printer |
| 7 | `seon.profile` | no reset; `summary` is process-cumulative | a summary over one evaluation's calls |
| 8 | `seon.test/run` | 820 ms with zero executions after an unrelated program change | cost follows the reached closure, measured under 200 ms |
| 9 | `seon.cluster.source/write-back!` | `:not-analyzable` carries `:seon.error/offending nil` | the analyzer's message and targets are in the refusal |
| 10 | `seon.call-preparation-test` | `an-acquired-context-supplies-a-leading-database-to-a-first-party-read` red on an unchanged branch: SCI cannot resolve `probe-current-database` | green on a branch, or recorded as red on main |

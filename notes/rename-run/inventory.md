# rename-run inventory (2026-10-23, main edc5ab2d merged)

Scope: tracked files minus reference-code/ and notes/. Counts are hits found before `vocab_pass.py` / `attempt_to_run.py`.
Passes 1-2 (names in code, stored keywords) were already on main; what was left is below. The check
`seon.docs-test/no-text-uses-an-old-word` (allowed files in its `text-kept` map, each with a reason) is red on any new hit.

| word | hits left | rename | kept (reason) |
|---|---|---|---|
| attempt | ~1,320 (main) | run (`attempt_to_run.py`; `an attempt` -> `a run`; keys `:seon.attempt/*` -> `:seon.run/*`) | AGENTS table row; "Attempting to call unbound fn" (Jolt message); `zoom-a8-attempt-calc-neg.jpg`; `(was attempt)` in docs/vocabulary.md |
| fence | 53 | FENCES (the 2026-10-20 audit's name, 10 hits) -> GUARDS; `seon.fences` task file/ids -> `seon.audit` | Markdown code fence: src/seon/{reader,ref}.clj, seon.ref schema, their tests (CommonMark's word); owner quote in decisions.md |
| entrance | 22 | task file/ids `seon.entrance` -> `seon.server` | bin/ports + config_test (reads the earlier `:seon.config.root/entrance-port` from a checkout's local.edn); src/seon/server.clj cites v1's `proto.agents.entrance` |
| door | 5 | `a-door-defect-...` id -> `a-server-defect-...` | owner quotes |
| refusal | 21 | `a-refusal-is-the-one-error-map` -> `an-invalid-is-the-one-error-map`; `...-not-a-refusal` -> `...-not-an-invalid`; "never a refusal" (4 new on main) -> "never a reason to refuse done"; `merge-refusal` -> `merge-refused`; 2 comments in context_test | AGENTS table row; docs/vocabulary.md "(was refusal)"; `t5.refusal/*` (a past agent's test ns, seon.context.edn) |
| stand-in | 7 | scripted model / "measure" (fixtures, config_test, check.clj) | AGENTS/vocabulary rows |
| harvest | 5 | stage (source.md, source_test, seon.stage.edn) | rows |
| guarantees | 5 | instrument (reconcile_test comment, `PROTO_INSTRUMENT`) | rows; seon.db.edn plan |
| lane | 6 | `bin/lane` -> `bin/worktree` in BAGGAGE.md | owner quotes; the decision title "No locks, no lane cap, no waiting" (BAGGAGE cites it) |
| landing / land | 3 / branch names `land4`, `land6`, `land12` | none | records: branch names; "land" is plain English and not checked |
| census | 4 | `with-merge-census-cost` -> `with-merge-mg-check-cost` | plan text of seon.docs.edn |
| dragon | 5 | `the-dragon-gate-sees-fences` -> `the-failure-point-gate-sees-guards` | rows |
| merge train | 3 | `merge-trains-off-as-decided` -> `octopus-merges-off-as-decided`; `a-train-is-proven-...` -> `an-octopus-merge-is-proven-by-one-combined-run` | rows |
| dial, pillar, verb | 3, 2, 3 | none left outside the table and the pass-2 plans | rows; seon.docs.edn/seon.db.edn plans |
| admit | 11 | none | `seon.fx/admit` is admission by workload (kept sense) |

Names needing a ruling (proposed to root, done as proposed, not answered at writing): `seon.entrance.edn` -> `seon.server.edn`;
`seon.fences.edn` -> `seon.audit.edn`; the seven ids above. No `bin/` command carries an old word.

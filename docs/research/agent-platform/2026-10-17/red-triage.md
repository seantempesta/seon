---
title: "Red triage: T3a's HEAD reds, the error-test flake, and T2's agent-test reds"
type: research
status: evidence only; no source, test or task file edited
date: 2026-10-17
basis: "HEAD bebd51673 (`bin/seon check --at HEAD`, throwaway JVMs); stored runs read through seon.test.runner/latest-results on default in JVM mode; docs/research/agent-platform/2026-10-16/live-run-4.md and live-run-7.md"
---
# Red triage (2026-10-17)

## Method limits (read first)

- `seon.test.runner/latest-results` returns nothing for every requested symbol on default. The JVM-mode read (`(seon.test.runner/latest-results (seon.db/db (seon.cluster.boot/connection "default")) syms)`) answered `0` results for all 15 symbols, though `:seon.test/sym` entities for them exist on main (for example `seon.cluster.agent-test/park-wake-test`, `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner`). No `:seon.test.run/members` or test-run attribute is present on default's main lineage. So the stored-run history needed for "last green / first red" is not on default main. Last-green cannot be read from stored runs for any row below; the commit columns give only the first commit known to be red and the commit that touched the test file.
- Existing task files do not name these 14 tests except as noted in the table.
- The flake was run with `bin/seon check --at HEAD --test NS/VAR`, one at a time (five sequential runs). Earlier in the session one unintended loop, `bin/seon check --test` without `--at` (working tree), ran the same test concurrently with the five HEAD runs for part of the window. Its results are not counted here.

## Flake: `seon.error-test/entity-acquisition-observations-have-no-program-digest-promise`

Five sequential runs on HEAD `bebd51673`, each one test:

| Run | Check run id | Result | Failure |
|---|---|---|---|
| 1 | b7d7329c6b47 | red | `test/seon/error_test.clj:85`, `(not (= seon.id seon.id/id))` |
| 2 | 61ee50a2a1a4 | red | same |
| 3 | 23f55e093423 | green | pass 18, fail 0 |
| 4 | 812490eb9b83 | red | same |
| 5 | 376f12cfd0fd | red | same |

Total: 4 red, 1 green. This is not a flake in the usual sense: it is red on HEAD in four of five runs with the same assertion. The `seon.id` versus `seon.id/id` mismatch in a program-digest promise looks like an input that changes between runs (run 3 green suggests a nondeterministic value, such as a digest or a symbol that depends on evaluation order). Suggested owner: the error-constructor / entity-acquisition owner (`seon.error`), with the test as its proof. Diagnosis is not done here.

## Table

| test | existing task | last green | first red | failure | suggested owner |
|---|---|---|---|---|---|
| `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner` | none found in `resources/seon/tasks/`; cited red in `2026-10-16/live-run-4.md` (fail 2) and `live-run-7.md` (red on HEAD 225a66566) | unknown: no stored run readable | at or before 225a66566 (2026-10-16); test file last touched by fe7581ce6 and 0a43382db | red on HEAD in T3a's check | `seon.error` schema owner |
| `seon.error-test/preparation-and-recording-preserve-every-instrumentation-arm` | none found; live-run-7 reproduces it: `bin/seon check --at HEAD` answers `(not (= :guard :output))` at `test/seon/error_test.clj:760` | unknown | at or before 225a66566 (2026-10-16) | `(not (= :guard :output))` | `seon.error` / instrumentation owner |
| `seon.error-test/the-elision-ai-render-returns-data` | `docs/research/agent-platform/2026-10-16/text-render-tasks/tasks.edn` (text-render proofs, commit 0a43382db) | unknown | 0a43382db (red proof added on purpose, then red on HEAD) | AI render returns text instead of data (the red text-render proof) | render owner (`seon.render`, text-render task) |
| `seon.instrument-test/a-sovereign-sci-fork-refuses-by-throwing-under-both-dials` | none found | unknown | unknown; test body last touched in the instrument test history (b1dd803f3 and earlier) | not reproduced here (no bounded run this session) | `seon.instrument` owner |
| `seon.instrument-test/registry-sized-contract-evidence-retains-the-offending-object` | none found; `docs/research/agent-platform/2026-10-16/rank-signals/instances.jsonl` lists the test | unknown | unknown | about 31 MB of contract evidence against a 16 MB ceiling | `seon.instrument` evidence owner; a dial or evidence-size owner, since a red test's ceiling is a policy number |
| `seon.error-test/entity-acquisition-observations-have-no-program-digest-promise` (flake) | none found | green at run 3 on HEAD | red on HEAD in 4 of 5 runs | `(not (= seon.id seon.id/id))` at `test/seon/error_test.clj:85` | `seon.error` owner; diagnose the nondeterministic digest |
| `seon.cluster.agent-test/a-function-without-a-contract-is-admitted-and-stored` | `resources/seon/tasks/seon.sci.eval.edn` names the surrounding eval area, not this test | unknown | unknown | not reproduced here | `seon.cluster.agent` / contracts owner |
| `seon.cluster.agent-test/hot-reload-var-test` | `resources/seon/tasks/seon.cluster.agent-test.edn` mentions it; listed in `2026-10-16/rank-signals/instances.jsonl` | unknown | unknown | not reproduced here | reload owner (`seon.cluster.agent`) |
| `seon.cluster.agent-test/n-agent-parallel-turns-property` | none found | unknown | unknown | not reproduced here | `seon.cluster.agent` |
| `seon.cluster.agent-test/park-wake-test` | none found; listed in `rank-signals/instances.jsonl` | unknown | unknown | not reproduced here | `seon.cluster.agent` (wake) |
| `seon.cluster.agent-test/pause-during-in-flight-call-test` | none found; listed in `rank-signals/instances.jsonl` | unknown | unknown | not reproduced here | `seon.cluster.agent` (flow pause) |
| `seon.cluster.agent-test/release-joins-every-owned-graph-before-it-closes-the-branch` | none found | unknown | unknown (agent_test.clj last touched by T2 cb8274c69, which made the graph process a fact) | not reproduced here | `seon.cluster.agent` (release, graph process) |
| `seon.cluster.agent-test/stop-flow-has-a-declared-loud-turn-completion-backstop` | none found | unknown | unknown (cb8274c69 is the T2 run that reported it red) | not reproduced here | `seon.cluster.agent` (stop-flow) |
| `seon.cluster.agent-test/stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted` | `resources/seon/tasks/seon.test.edn` mentions it in a test-selection task (`select-names-...` area) | unknown | unknown (cb8274c69 is the T2 run that reported it red) | not reproduced here | `seon.cluster.agent` (stop-flow) |
| `seon.cluster.agent-test/wait-closes-in-terminal-tx-test` | none found; listed in `rank-signals/instances.jsonl` | unknown | unknown (cb8274c69 is the T2 run that reported it red) | not reproduced here | `seon.cluster.agent` (wait) |

## Notes for root

- The 9 agent-test long tests are red as reported by T2's run; they were not re-run here (budget), and there is no stored run to read. They share one file, `test/seon/cluster/agent_test.clj`, and T2 (cb8274c69) and merge P1 (5f5eb1855) both touched that file. Bisecting the first red with `bin/seon check --at REV` on one or two of them is the cheapest next step.
- Two of the three error reds were red already in 2026-10-16 live runs, so they are not new at T3a.
- The T3a red list may carry the same false reds as the flake: the error-test digest flake makes a single HEAD red unreliable for that test alone.

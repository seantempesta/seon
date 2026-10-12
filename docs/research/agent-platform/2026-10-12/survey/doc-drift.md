---
title: Survey doc-drift: living documents against HEAD
type: research
status: survey, read-only; measured at HEAD, not reviewed
basis: HEAD 45bfab18e; default not queried (its commit is b14dc2b3f per the lane rules, not used here)
method: bb scratchpad script doc-drift.clj (scratch dir /private/tmp/claude-501/-Users-sean-src-seon/a5acff01-0859-40f4-bc50-1424bc5205cf/scratchpad/survey/doc-drift.clj), run as `bb doc-drift.clj` from /Users/sean/src/seon; rg for ns declaration lists and git log for dates
---

# Survey doc-drift: living documents against HEAD

## For root

1. Docs ranked by unresolved backticked references (85 of 1049 refs in 39 living docs): `docs/seon/plan.md` 19 of 104; `docs/seon/specs/errors.md` 18 of 99 (plus 22 `path:N`); `docs/seon/architecture/vocabulary.md` 13 of 93; `docs/seon/specs/agent-loop.md` 11 of 56; `.agents/skills/data-oriented-clojure/SKILL.md` 6 of 118 (2 are tool false positives); `AGENTS.md` 3 of 59 (all in a labelled Target paragraph); `.agents/skills/repl/SKILL.md` 2 of 55 (both `[TARGET]`-labelled); `docs/seon/architecture/data-modeling-guide.md` 2 of 44 (plus 32 `path:N`).
2. Skills read most, unresolved/total: repl 2/55 (4%, labelled target); data-oriented-clojure 6/118 (5%; 1 real stale claim, see 3b); datahike SKILL and references 0 real of 123 (1 tool false positive); clojure-testing 1/49 (tool false positive); seon-flow-architecture 1/69 (this is 3a); codex-lanes 0/0 (no backticked symbols); datastar-web-ui 2/19 (both `my.canvas`, labelled proposed in ui-canvas). Premise check: `seon.batch` exists at HEAD (src/seon/batch.clj), so the assignment's deleted-namespace premise is wrong for it; `seon.cluster.squash` is mentioned nowhere in the living set.
3. The 10 worst false claims (present tense, absent at HEAD; the verification is a single rg per claim):
   a. `.agents/skills/seon-flow-architecture/references/degraded-start.md:18` says boot publishes into `seon.operator.runtime/running-instances`. No such ns: `src/seon/operator` is an empty directory at HEAD.
   b. `.agents/skills/data-oriented-clojure/SKILL.md:205` says `seon.schema/admit-declarations!` copies `:seon.schema.projection/X` to `:seon.schema/X`. No such def in src, test, script or reference-code.
   c. `docs/seon/architecture/vocabulary.md:93` says code is at `seon.task/frontier` "until CN-27 renames it". The rename is done: `seon.task/ready` exists (src/seon/task.clj, defn ready), `frontier` is gone; the note is stale.
   d. `docs/seon/architecture/vocabulary.md:55` says `seon.db/call-with-custody` exists. No such def in src/seon/db.clj.
   e. `docs/seon/plan.md:45,58,90,184` cite as done-proofs four test vars that have no def at HEAD (`seon.context-test/broken-tests-show-with-their-failures`, `seon.sci.eval-test/no-agent-work-writes-a-program-context`, `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`, `seon.db-test/equal-declaration-content-shares-one-projection-across-values`).
   f. `docs/seon/specs/errors.md:79,161` present `seon.error.refusal/caught` ("converts a Throwable to the map") and `seon.error.refusal/preserve-primary-and-suppressed-causes` as working code; neither is defined in src/seon/error/refusal.clj. The spec is unlabelled about its target status here.
   g. `docs/seon/specs/errors.md:275-276` names proof `seon.task-question-test/root-decided` and the key `:seon.error/task` as the mechanism; the test ns and key are absent, and the paragraph has no target label.
   h. `docs/seon/architecture/data-modeling-guide.md:305` declares `:seon.test.accretion/report-edn` as a `:string` attribute; the key is absent from src and resources/seon/schemas (the word "accretion test" is retired per AGENTS.md).
   i. `docs/seon/architecture/architecture.md:216` (and `ui.md:74`) say namespace responsibility is `:seon.ns/agents`; the key is in no src or resource file, and vocabulary.md marks it [TARGET]. architecture.md does not.
   j. `docs/seon/specs/agent-loop.md:458` says `:seon.agent/answered-t` "is the home basis the last committed model eval request presented"; the key is in no src or resource file (code uses `seon.turn/unanswered-triggers`), and the spec is "approved", not marked as target at that line.
4. Already filed: none of items 3a-3j is named by any resources/seon/tasks file (grep for frontier, admit-declarations, running-instances, call-with-custody, accretion/report-edn, error.refusal/caught, root-decided found nothing relevant).
5. Not filed: 3a-3j, the 4 absent done-proof names in 3e, and the 161 hand-written `path:N` citations in living docs (reference-code.md 70, data-modeling-guide.md 32, architecture.md 22, errors.md 22, agent-runtime.md 8, ui.md 5, vocabulary.md 1, design-principles.md 1; AGENTS.md forbids them outside dated evidence).

## Table

Measured by the script; every unresolved row is listed. Status: no-ns = no (ns ...) declaration in src, test, script or reference-code; no-def = namespace found but no def* of that name in its file; no-kw = seon keyword absent from src and resources/seon/schemas (namespaced-map forms checked). (lib) marks reference-code namespaces. Wildcard keywords (8) are not counted.

### Summary
living docs: 39  skipped (status superseded/shelved/draft): 16
  skipped .agents/skills/data-oriented-clojure/references/program-state.md | superseded
  skipped .agents/skills/seon-flow-architecture/references/agent-graphs.md | superseded
  skipped .agents/skills/seon-flow-architecture/references/render-delivery.md | superseded
  skipped docs/seon/architecture/decisions/001-nippy-serialization.md | superseded
  skipped docs/seon/architecture/decisions/004-schema-unification.md | superseded
  skipped docs/seon/architecture/decisions/006-separate-jvm.md | superseded
  skipped docs/seon/architecture/decisions/007-runtime-instrumentation.md | superseded
  skipped docs/seon/architecture/decisions/008-database-protocol.md | superseded
  skipped docs/seon/architecture/decisions/009-cluster-jvm-topology.md | superseded
  skipped docs/seon/architecture/decisions/013-per-run-sci-forks.md | superseded
  skipped docs/seon/architecture/decisions/014-session-curation.md | superseded
  skipped docs/seon/architecture/decisions/015-universal-output-projections.md | superseded by agent-loop.md and AGENTS.md Presentation
  skipped docs/seon/specs/merge.md | superseded by docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md (owner answers 2026-10-12); formerly approved in part (2026-10-08): git vocabulary, one merge!, target branch, norms, merge when every test is green
  skipped docs/seon/specs/render-dedup.md | draft
  skipped docs/seon/specs/run.md | superseded by agent-loop.md (2026-10-08)
  skipped docs/seon/specs/task-steps.md | shelved by the owner (2026-10-08)
ns declarations: 3780  distinct namespaces: 3422  library namespaces: 2873
refs: 1049 {:sym 617, :kw 307, :ns 125}
status counts: {:ok 949, :no-kw 39, :no-def 28, :no-ns 18, :wild 8, :task 7}

### per-doc
| doc | last commit | refs | unresolved seon | unresolved lib | task-resolved | path:N |
| .agents/skills/clojure-testing/SKILL.md | 2026-10-12 | 49 | 1 | 0 | 0 | 0 |
| .agents/skills/clojurescript/SKILL.md | 2026-10-12 | 0 | 0 | 0 | 0 | 0 |
| .agents/skills/codex-lanes/SKILL.md | 2026-10-12 | 0 | 0 | 0 | 0 | 0 |
| .agents/skills/data-modeling/SKILL.md | 2026-10-12 | 41 | 0 | 1 | 0 | 0 |
| .agents/skills/data-oriented-clojure/SKILL.md | 2026-10-12 | 118 | 5 | 1 | 0 | 0 |
| .agents/skills/datahike/SKILL.md | 2026-10-12 | 118 | 0 | 0 | 0 | 0 |
| .agents/skills/datahike/references/fork-maintenance.md | 2026-10-12 | 2 | 1 | 0 | 0 | 0 |
| .agents/skills/datahike/references/querying.md | 2026-10-12 | 3 | 0 | 0 | 0 | 0 |
| .agents/skills/datastar-web-ui/SKILL.md | 2026-10-12 | 14 | 0 | 0 | 0 | 0 |
| .agents/skills/datastar-web-ui/references/design-principles.md | 2026-10-12 | 5 | 2 | 0 | 0 | 1 |
| .agents/skills/llm-providers/SKILL.md | 2026-10-12 | 28 | 0 | 0 | 0 | 0 |
| .agents/skills/repl/SKILL.md | 2026-10-12 | 55 | 2 | 0 | 0 | 0 |
| .agents/skills/seon-context-config/SKILL.md | 2026-10-12 | 10 | 0 | 0 | 0 | 0 |
| .agents/skills/seon-flow-architecture/SKILL.md | 2026-10-12 | 23 | 0 | 0 | 0 | 0 |
| .agents/skills/seon-flow-architecture/references/decisions.md | 2026-10-12 | 4 | 0 | 0 | 0 | 0 |
| .agents/skills/seon-flow-architecture/references/degraded-start.md | 2026-10-12 | 19 | 1 | 0 | 0 | 0 |
| .agents/skills/seon-flow-architecture/references/wakes-and-faults.md | 2026-10-12 | 12 | 0 | 0 | 0 | 0 |
| .agents/skills/seon-flow-architecture/references/workloads-and-scheduling.md | 2026-10-12 | 11 | 0 | 0 | 0 | 0 |
| .agents/skills/ui-canvas/SKILL.md | 2026-10-12 | 5 | 1 | 0 | 0 | 0 |
| AGENTS.md | 2026-10-12 | 59 | 3 | 0 | 0 | 0 |
| docs/seon/architecture/README.md | 2026-10-12 | 0 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/agent-runtime.md | 2026-10-12 | 4 | 0 | 0 | 0 | 8 |
| docs/seon/architecture/architecture.md | 2026-10-12 | 8 | 2 | 0 | 0 | 22 |
| docs/seon/architecture/clusters-branches-contexts.md | 2026-10-12 | 0 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/contracts-guide.md | 2026-10-12 | 33 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/data-modeling-guide.md | 2026-10-12 | 44 | 2 | 0 | 0 | 32 |
| docs/seon/architecture/decisions/002-absence-over-nil.md | 2026-08-06 | 0 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/decisions/003-ref-type.md | 2026-08-13 | 3 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/decisions/005-flow-adoption.md | 2026-10-12 | 1 | 1 | 0 | 0 | 0 |
| docs/seon/architecture/decisions/010-circuit-breaker-limits.md | 2026-10-12 | 0 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/decisions/011-tests-find-design-issues.md | 2026-10-12 | 0 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/decisions/012-process-root-cluster-topology.md | 2026-10-12 | 3 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/decisions/016-per-agent-scheduling-and-root-maintenance.md | 2026-08-13 | 0 | 0 | 0 | 0 | 0 |
| docs/seon/architecture/reference-code.md | 2026-10-12 | 23 | 0 | 0 | 0 | 70 |
| docs/seon/architecture/ui.md | 2026-10-12 | 2 | 1 | 0 | 0 | 5 |
| docs/seon/architecture/vocabulary.md | 2026-10-12 | 93 | 13 | 0 | 0 | 1 |
| docs/seon/plan.md | 2026-10-12 | 104 | 19 | 0 | 5 | 0 |
| docs/seon/specs/agent-loop.md | 2026-10-12 | 56 | 11 | 0 | 1 | 0 |
| docs/seon/specs/errors.md | 2026-10-12 | 99 | 18 | 0 | 1 | 22 |

### per-skill (.agents/skills groups; root group = AGENTS/plan/architecture/specs)
| group | files | refs | unresolved | ratio | top named ns (refs) -> file, last commit | newest commit of its files |
| architecture+specs+plan+AGENTS | 20 | 532 | 70 | 13% | seon.task (18) -> src/seon/task.clj, 2026-10-12; seon.db (16) -> src/seon/db.clj, 2026-10-12; my.program (14) -> src/my/program.clj, 2026-10-12 | 2026-10-12 |
| clojure-testing | 1 | 49 | 1 | 2% | seon.test (4) -> src/seon/test.clj, 2026-10-12; seon.test.runner (3) -> src/seon/test/runner.clj, 2026-10-12; seon.task (2) -> src/seon/task.clj, 2026-10-12 | 2026-10-12 |
| clojurescript | 1 | 0 | 0 | - |  | 2026-10-12 |
| codex-lanes | 1 | 0 | 0 | - |  | 2026-10-12 |
| data-modeling | 1 | 41 | 1 | 2% | seon.schema.datahike (7) -> src/seon/schema/datahike.clj, 2026-10-12; seon.id (4) -> src/seon/id.clj, 2026-10-07; seon.db (4) -> src/seon/db.clj, 2026-10-12 | 2026-10-12 |
| data-oriented-clojure | 1 | 118 | 6 | 5% | seon.error (4) -> src/seon/error.clj, 2026-10-12; seon.db (4) -> src/seon/db.clj, 2026-10-12; seon.cluster (4) -> src/seon/cluster.clj, 2026-10-12 | 2026-10-12 |
| datahike | 3 | 123 | 1 | 0% | seon.db (18) -> src/seon/db.clj, 2026-10-12; seon.schema.datahike (9) -> src/seon/schema/datahike.clj, 2026-10-12; seon.test-support (2) -> test/seon/test_support.clj, 2026-10-12 | 2026-10-12 |
| datastar-web-ui | 2 | 19 | 2 | 10% | seon.repl (5) -> src/seon/repl.clj, 2026-10-11; seon.render.web (3) -> src/seon/render/web.clj, 2026-10-12; seon.render.block (3) -> src/seon/render/block.clj, 2026-09-28 | 2026-10-12 |
| llm-providers | 1 | 28 | 0 | 0% | seon.ai (14) -> src/seon/ai.clj, 2026-10-09; seon.context (2) -> src/seon/context.clj, 2026-10-12; seon.turn (1) -> src/seon/turn.clj, 2026-10-12 | 2026-10-12 |
| repl | 1 | 55 | 2 | 3% | seon.turn (4) -> src/seon/turn.clj, 2026-10-12; my.agent (4) -> src/my/agent.clj, 2026-10-12; seon.sci.eval (3) -> src/seon/sci/eval.clj, 2026-10-12 | 2026-10-12 |
| seon-context-config | 1 | 10 | 0 | 0% | seon.config (5) -> src/seon/config.clj, 2026-10-08; seon.sci.eval (2) -> src/seon/sci/eval.clj, 2026-10-12; seon.render.route (1) -> src/seon/render/route.clj, 2026-09-28 | 2026-10-12 |
| seon-flow-architecture | 5 | 69 | 1 | 1% | seon.flow (6) -> src/seon/flow.clj, 2026-10-12; seon.turn (4) -> src/seon/turn.clj, 2026-10-12; seon.fault (4) -> src/seon/fault.clj, 2026-10-12 | 2026-10-12 |
| ui-canvas | 1 | 5 | 1 | 20% | seon.render.web (2) -> src/seon/render/web.clj, 2026-10-12; seon.render.route (1) -> src/seon/render/route.clj, 2026-09-28; seon.render.block (1) -> src/seon/render/block.clj, 2026-09-28 | 2026-10-12 |

### unresolved (status no-ns | no-def | no-kw)
| doc | line | kind | status | ref | line text |
| .agents/skills/clojure-testing/SKILL.md | 279 | kw | no-kw | `:seon.test-support/extra-schema` | store-global subjects such as blob keys. `:seon.test-support/extra-schema` is only for synthetic declarations. |
| .agents/skills/data-modeling/SKILL.md | 127 | sym (lib) | no-def | `datahike.db.utils/no-history?` | removes the former guarantee (`datahike.db.utils/no-history?`). An imported |
| .agents/skills/data-oriented-clojure/SKILL.md | 171 | ns | no-ns | `seon.turn.loop` | entries for `seon.cluster`, `seon.agent`, `seon.turn.loop`, `seon.sci.eval` and `seon.config.eval`; the body also renames |
| .agents/skills/data-oriented-clojure/SKILL.md | 171 | ns | no-ns | `seon.config.eval` | entries for `seon.cluster`, `seon.agent`, `seon.turn.loop`, `seon.sci.eval` and `seon.config.eval`; the body also renames |
| .agents/skills/data-oriented-clojure/SKILL.md | 205 | kw | no-kw | `:seon.schema.projection/X` | SEON: `seon.schema/admit-declarations!` copies each `:seon.schema.projection/X` to `:seon.schema/X`, but it also |
| .agents/skills/data-oriented-clojure/SKILL.md | 205 | kw | no-kw | `:seon.schema/X` | SEON: `seon.schema/admit-declarations!` copies each `:seon.schema.projection/X` to `:seon.schema/X`, but it also |
| .agents/skills/data-oriented-clojure/SKILL.md | 290 | sym (lib) | no-def | `clojure.core/Throwable->map` | (`clojure.core/Throwable->map`, in `clojure/src/clj/clojure/core_print.clj`). Its `:via` link shape is what each of Seon's links resembles. |
| .agents/skills/data-oriented-clojure/SKILL.md | 583 | sym | no-ns | `datahike.query/source-context-unchanged?` | generations prove nothing, `datahike.query/source-context-unchanged?`); for an as-of value over a committed origin |
| .agents/skills/datahike/references/fork-maintenance.md | 57 | sym | no-ns | `datahike.query/create-plan-via-ir` | 1. `datahike.query/create-plan-via-ir` builds logical IR and lowers it |
| .agents/skills/datastar-web-ui/references/design-principles.md | 25 | ns | no-ns | `my.canvas` | theme tokens, and semantic component CSS. Its `.cljs`, pod, and `my.canvas` |
| .agents/skills/datastar-web-ui/references/design-principles.md | 136 | ns | no-ns | `my.canvas` | - generalized `my.canvas` controls and `/call`: neither appears in the exact |
| .agents/skills/repl/SKILL.md | 82 | sym | no-def | `my.turn/evals` | **[TARGET]** `my.turn/evals` and `my.turn/eval` (PRD §15; the `my.turn` namespace |
| .agents/skills/repl/SKILL.md | 82 | sym | no-def | `my.turn/eval` | **[TARGET]** `my.turn/evals` and `my.turn/eval` (PRD §15; the `my.turn` namespace |
| .agents/skills/seon-flow-architecture/references/degraded-start.md | 18 | sym | no-ns | `seon.operator.runtime/running-instances` | the instance into `seon.operator.runtime/running-instances` after every layer |
| .agents/skills/ui-canvas/SKILL.md | 21 | ns | no-ns | `my.canvas` | Do not present proposed `my.canvas` constructors as installed APIs. |
| AGENTS.md | 481 | sym | no-def | `my.agent/answer!` | `my.agent/answer!` stores the validated answer as ordinary Datahike data for its proofs to read; |
| AGENTS.md | 484 | sym | no-def | `my.task/refine!` | `my.task/current` and `my.task/refine!`, and success has no verb; a reply is `my.message/send` then `my.turn/wait`; a model agent launches only on an assigned t |
| AGENTS.md | 486 | kw | no-kw | `:seon.agent/answered-t` | answered basis is `:seon.agent/answered-t`, and system turns and context capture are gone. Archive agents; preserve |
| docs/seon/architecture/architecture.md | 47 | kw | no-kw | `:seon.config.operator/heap-max-bytes` | (4 GB, `:seon.config.operator/heap-max-bytes`, passed as `-Xmx`), `seon.cache`'s byte |
| docs/seon/architecture/architecture.md | 216 | kw | no-kw | `:seon.ns/agents` | owner. Namespace responsibility is many-to-many `:seon.ns/agents`, separate from |
| docs/seon/architecture/data-modeling-guide.md | 282 | kw | no-kw | `:seon.test.accretion/report-edn` | | `{:seon.db/no-history? true}` | `:db/noHistory true` (`:186`) | Datahike writes no history datoms for the attribute (`reference-code/datahike/src/datahike/db/ |
| docs/seon/architecture/data-modeling-guide.md | 305 | kw | no-kw | `:seon.test.accretion/report-edn` | `result-edn` and `:seon.test.accretion/report-edn` are declared `:string` and |
| docs/seon/architecture/decisions/005-flow-adoption.md | 18 | sym | no-ns | `flow.spi/ProcLauncher` | A runtime owner implements `flow.spi/ProcLauncher` when it must select over a |
| docs/seon/architecture/ui.md | 74 | kw | no-kw | `:seon.ns/agents` | shows its declarations and responsible agents; B3's target `:seon.ns/agents` is |
| docs/seon/architecture/vocabulary.md | 55 | sym | no-def | `seon.db/call-with-custody` | | checked-out branch | the branch an agent's context holds (`my.agent/branch`, `git branch --show-current`); a new checkout is a new context | custody (internal |
| docs/seon/architecture/vocabulary.md | 82 | ns | no-ns | `seon.cluster.run` | | turn | the unit a model call answers: an agent's ordered evaluations and provider attempts, open until closed (`seon.turn`) | run (meaning one turn), `seon.cl |
| docs/seon/architecture/vocabulary.md | 88 | kw | no-kw | `:seon.agent/answered-t` | | answered t | `:seon.agent/answered-t`: the database basis `t` the agent has answered; wakes after it are due (Datomic's basis t) | answered basis, handled | |
| docs/seon/architecture/vocabulary.md | 93 | sym | no-def | `seon.task/frontier` | | ready | the derived set of tasks that should be running now: leaves, not done, needs done, intent permitting, no open attempt (code: `seon.task/frontier` unti |
| docs/seon/architecture/vocabulary.md | 95 | kw | no-kw | `:seon.cluster/execution` | | intent | `:seon.task/execution`, the stored permission for an attempt: `:held` (never launched), `:running`, `:paused` (graph exists, nothing runs), `:stopped |
| docs/seon/architecture/vocabulary.md | 100 | ns | no-ns | `seon.schedule.task` | | clock | the one proc that writes a firing fact for every cron task at its due instant and calls a cron task's function when it has one | timer, schedule proc, |
| docs/seon/architecture/vocabulary.md | 102 | kw | no-kw | `:seon.agent/observed` | | heartbeat | the cron task with a function that pings every graph and writes `:seon.agent/observed` on change and `:seon.cluster/heartbeat-t` per period; older |
| docs/seon/architecture/vocabulary.md | 102 | kw | no-kw | `:seon.cluster/heartbeat-t` | | heartbeat | the cron task with a function that pings every graph and writes `:seon.agent/observed` on change and `:seon.cluster/heartbeat-t` per period; older |
| docs/seon/architecture/vocabulary.md | 124 | kw | no-kw | `:seon.ns/agents` | | **[TARGET] namespace agent**, `:seon.ns/agents` | An agent responsible for a namespace: its recorded errors, tasks and requests render into that agent's conte |
| docs/seon/architecture/vocabulary.md | 125 | ns | no-ns | `seon.message` | | **[TARGET] task**, `seon.task` | The one family for work an agent does: linked facts (subject refs, tests, errors, functions) plus an optional agent. A "templ |
| docs/seon/architecture/vocabulary.md | 125 | ns | no-ns | `seon.issue` | | **[TARGET] task**, `seon.task` | The one family for work an agent does: linked facts (subject refs, tests, errors, functions) plus an optional agent. A "templ |
| docs/seon/architecture/vocabulary.md | 145 | ns | no-ns | `seon.issue` | | `my.program` | Agent-facing program reads over one supplied database value; the existing `seon.fn`, `seon.db` and `seon.issue` owners supply selection, facts  |
| docs/seon/architecture/vocabulary.md | 155 | kw | no-kw | `:seon.ns/agents` | | `my.agents.<id>` | the DEFAULT namespace for a temp agent only; real agents own namespaces anywhere. ASSIGNMENT IS NOT IDENTITY: `:seon.agent/namespace` is no |
| docs/seon/plan.md | 27 | sym | no-def | `my.task/refine!` | proofs). **628 tasks have no tagged proof.** Agents have `my.task/current` and `my.task/send-back!`; `my.task/refine!` |
| docs/seon/plan.md | 28 | sym | no-def | `my.task/create!` | and `my.task/create!` do not exist yet. The fence revert, verification and database reset are done (§4). |
| docs/seon/plan.md | 45 | sym | no-def | `seon.context-test/broken-tests-show-with-their-failures` | | P4 | step-semantics | done | 031125438, be32e7501: `seon.context-test/broken-tests-show-with-their-failures` | | |
| docs/seon/plan.md | 50 | sym | no-def | `my.root/relaunch!` | | P9 | step-semantics | absorbed into done-and-merge | no `my.root/relaunch!`; the filed task is `seon.cluster.agent/a-capped-attempt-has-no-relaunch` | see M5, |
| docs/seon/plan.md | 55 | sym | no-def | `my.program/a-function-called-through-its-callers-is-reached-by-their-tests` | | G9 | [done-and-merge](../research/agent-platform/2026-10-12/hybrid-done-and-merge.md) | done | 98720b4eb: `seon.fn/closure-walk` continues past two levels unt |
| docs/seon/plan.md | 58 | sym | no-def | `seon.sci.eval-test/no-agent-work-writes-a-program-context` | | E2 | immutable-env | done | f424382cd: `seon.sci.eval/program-context`, `seon.sci.eval-test/no-agent-work-writes-a-program-context` | | |
| docs/seon/plan.md | 90 | sym | no-def | `seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context` | (`seon.sci.eval-test/equal-programs-on-two-branches-share-one-program-context`). Open: the base the loaded commit |
| docs/seon/plan.md | 92 | kw | no-kw | `:seon.task/file-grammar` | 2. Weights that charge a key: `:seon.task/file-grammar` 9.36 MB, `:seon.cluster/derivation-world` 10.30 MB, |
| docs/seon/plan.md | 92 | kw | no-kw | `:seon.cluster/derivation-world` | 2. Weights that charge a key: `:seon.task/file-grammar` 9.36 MB, `:seon.cluster/derivation-world` 10.30 MB, |
| docs/seon/plan.md | 93 | kw | no-kw | `:seon.task/task-basis` | `:seon.task/task-basis` 10.39 MB, `:seon.task/tasks-tx` 11.53 MB, about 41 MB for small values. Weigh the value. |
| docs/seon/plan.md | 93 | kw | no-kw | `:seon.task/tasks-tx` | `:seon.task/task-basis` 10.39 MB, `:seon.task/tasks-tx` 11.53 MB, about 41 MB for small values. Weigh the value. |
| docs/seon/plan.md | 95 | kw | no-kw | `:seon.sci.eval/loaded-digests` | 3. `:seon.sci.eval/loaded-digests`: whole-program digests (6,500 functions) per branch commit, 10 MB, 2 hits and 6 |
| docs/seon/plan.md | 97 | kw | no-kw | `:seon.cluster/file-schema-keys` | 4. `:seon.cluster/file-schema-keys`: 1,080 counted misses are a probe, not work. Add a non-counting read to `seon.cache`; |
| docs/seon/plan.md | 98 | kw | no-kw | `:seon.health/reads` | the same idiom inflates `:seon.health/reads` and `:seon.health/derived`. |
| docs/seon/plan.md | 98 | kw | no-kw | `:seon.health/derived` | the same idiom inflates `:seon.health/reads` and `:seon.health/derived`. |
| docs/seon/plan.md | 153 | sym | no-def | `my.task/refine!` | `my.task/refine!` and `my.task/create!` (steps with needs and instructions). Delete the queue and the old step |
| docs/seon/plan.md | 153 | sym | no-def | `my.task/create!` | `my.task/refine!` and `my.task/create!` (steps with needs and instructions). Delete the queue and the old step |
| docs/seon/plan.md | 184 | sym | no-def | `seon.db-test/equal-declaration-content-shares-one-projection-across-values` | `seon.db-test/equal-declaration-content-shares-one-projection-across-values`); FEEDBACK-LOOP stages 14, 15, 17; the lag |
| docs/seon/plan.md | 256 | sym | no-def | `my.task/refine!` | step's needs are done; the handoff is the subject task at main, edited by `my.task/refine!`; a step ends when its proofs |
| docs/seon/specs/agent-loop.md | 73 | sym | no-def | `my.agent/answer!` | answer's Malli schema, and `my.agent/answer!` validates the answer against it and stores it as ordinary Datahike |
| docs/seon/specs/agent-loop.md | 115 | kw | no-kw | `:seon.config.operator/heap-max-bytes` | | machine | the whole process on the owner's 16 GB machine beside the user's work: every size, count, byte budget and timeout is a configuration dial with a def |
| docs/seon/specs/agent-loop.md | 188 | kw | no-kw | `:seon.config.ai/entrance` | `:seon.config.ai/entrance` (`:repl :data`) selects a model agent's entrance. Error occurrences ride the eval request |
| docs/seon/specs/agent-loop.md | 291 | sym | no-def | `my.agent/inputs` | no `my.agent/inputs`). |
| docs/seon/specs/agent-loop.md | 352 | sym | no-def | `my.turn/complete` | | a form's value is `(my.turn/wait {:my.turn/note …})` | disposition `:wait`; no model call until a new outside wake. A reply to someone is `(my.message/send …) |
| docs/seon/specs/agent-loop.md | 358 | sym | no-def | `my.task/complete!` | | `(my.task/complete! {:seon.task/reason …})` (the reason is required) | disposition `:bailed`; the assignment moves to the assigner with the reason (an assignm |
| docs/seon/specs/agent-loop.md | 406 | sym | no-def | `my.task/complete!` | `my.turn/wait`, or `my.task/complete!` with a reason to give a task back); it is the static prefix. |
| docs/seon/specs/agent-loop.md | 412 | kw | no-kw | `:seon.config.ai/timeout-ms` | | provider timeout `:seon.config.ai/timeout-ms` | `seon.ai/complete` | a counted failed call | |
| docs/seon/specs/agent-loop.md | 449 | kw | no-kw | `:seon.agent/written-at` | agent attribute declares its side (`:seon.agent/written-at`), and the writer refuses the wrong one. |
| docs/seon/specs/agent-loop.md | 451 | kw | no-kw | `:seon.agent/assignments` | - **Held tasks.** The agent's assignments are components of it (`:seon.agent/assignments`); each refs its task and |
| docs/seon/specs/agent-loop.md | 458 | kw | no-kw | `:seon.agent/answered-t` | - **Answered basis.** `:seon.agent/answered-t` is the home basis the last committed model eval request presented. |
| docs/seon/specs/errors.md | 48 | ns | no-ns | `seon.error.edn` | (`seon.error.edn`), and `seon.error.refusal/error?` plus about 20 readers test `(:seon.error/at x)` as "is an error" |
| docs/seon/specs/errors.md | 79 | sym | no-def | `seon.error.refusal/preserve-primary-and-suppressed-causes` | in the cut (`seon.error.refusal/preserve-primary-and-suppressed-causes`), preserving the one walk. `Throwable->map` is never used as the traversal: it follows ` |
| docs/seon/specs/errors.md | 123 | kw | no-kw | `:seon.error/malformed-error` | call. On failure it returns `:seon.error/malformed-error` holding `:seon.error/attempted` (the referenced map) and the |
| docs/seon/specs/errors.md | 161 | sym | no-def | `seon.error.refusal/caught` | `seon.error.refusal/caught` converts a Throwable to the map; it does not record it. Normalize through the existing |
| docs/seon/specs/errors.md | 175 | kw | no-kw | `:seon.error/recorded` | own error and assocs the result as `:seon.error/recorded`; an agent's own mistake is delivered and not recorded. |
| docs/seon/specs/errors.md | 273 | sym | no-def | `seon.task/generate-tx` | (`seon.task/generate-tx`, ledger RT-7, RT-8, RT-12) assigned to the function's steward if one is held, else to root: |
| docs/seon/specs/errors.md | 275 | sym | no-ns | `seon.task-question-test/root-decided` | no action with a reason), its proof the generic `seon.task-question-test/root-decided`. The entity refs it as |
| docs/seon/specs/errors.md | 276 | kw | no-kw | `:seon.error/task` | `:seon.error/task`; the assignment is the one notification (an assignment is a wake), and no message is sent. The |
| docs/seon/specs/errors.md | 282 | kw | no-kw | `:seon.error/recorded` | too; the boundary assocs it as `:seon.error/recorded`. The recording transaction stores the signature on the evaluation or provider |
| docs/seon/specs/errors.md | 283 | kw | no-kw | `:seon.cluster.eval/error-signatures` | call where the failure surfaced (`:seon.cluster.eval/error-signatures`, `:seon.ai.attempt/error-signatures`, values, |
| docs/seon/specs/errors.md | 283 | kw | no-kw | `:seon.ai.attempt/error-signatures` | call where the failure surfaced (`:seon.cluster.eval/error-signatures`, `:seon.ai.attempt/error-signatures`, values, |
| docs/seon/specs/errors.md | 302 | sym | no-def | `seon.task/task-resolution` | there. `seon.task/task-resolution` reads this through `seon.error/resolution` (rewritten in place). |
| docs/seon/specs/errors.md | 314 | kw | no-kw | `:seon.problems/open-fault` | `:seon.problems/open-fault`, `open-fault`) retires; old entities are not migrated (§9.6). |
| docs/seon/specs/errors.md | 333 | kw | no-kw | `:seon.error/malformed-error` | **P1 Accrete schemas.** Variant wrappers by script, `:seon.error/malformed-error`, `:seon.error/exception`, the five |
| docs/seon/specs/errors.md | 334 | kw | no-kw | `:seon.error/digest` | Malli report variants with their render pairs, `:seon.error/digest`/`caller`/`arm`/`arities`, the entity members of §6, |
| docs/seon/specs/errors.md | 361 | sym | no-ns | `turn.batch/reply` | `turn.batch/reply` carries error maps with their results and handles, not their messages; render selection by type. |
| docs/seon/specs/errors.md | 375 | ns | no-ns | `seon.error.edn` | `seon.error`); `instrument.clj` 1326 → ~790; rethrows and literals ~1,100 → ~250; `seon.error.edn` 458 → ~140. About |
| docs/seon/specs/errors.md | 396 | kw | no-kw | `:seon.error/malformed-error` | - **T8 malformed thrown variant**: `(fail! :my.fs/not-found-error 'example/f "m")` throws `:seon.error/malformed-error` |

### task refs resolved by resources/seon/tasks (crude text match)
  docs/seon/plan.md:50 seon.cluster.agent/a-capped-attempt-has-no-relaunch
  docs/seon/plan.md:65 seon.task/main
  docs/seon/plan.md:178 seon.test-census-test/no-test-redefines-a-var
  docs/seon/plan.md:181 seon.sci.eval/a-context-holds-the-basis-it-acquired-not-the-database-value
  docs/seon/plan.md:182 seon.db/a-failed-projection-derivation-stays-cached-under-its-commit
  docs/seon/specs/agent-loop.md:352 my.agent/done
  docs/seon/specs/errors.md:248 seon.task/home

### hand-written path:N citations
  .agents/skills/datastar-web-ui/references/design-principles.md:31 (`package.json:10-13`):
  docs/seon/architecture/agent-runtime.md:23 acquisition (`src/seon/sci/eval.clj:2218`). The target acquires the base once and
  docs/seon/architecture/agent-runtime.md:36 same-generation Var in place (`reference-code/sci/src/sci/core.cljc:345-350`;
  docs/seon/architecture/agent-runtime.md:37 `reference-code/sci/src/sci/impl/utils.cljc:362-379`). Mutating a base-owned Var can
  docs/seon/architecture/agent-runtime.md:81 **Existing seams:** `src/seon/cluster/agent.clj:118` wake channel, `:540` submission,
  docs/seon/architecture/agent-runtime.md:82 `:805-845` uninstrument; `src/seon/turn.clj:2888` work, `:2980-3007` answering;
  docs/seon/architecture/agent-runtime.md:83 `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29-36,271-320`
  docs/seon/architecture/agent-runtime.md:92 outcomes. `open-call` is the existing admission owner (`src/seon/turn.clj:395`);
  docs/seon/architecture/agent-runtime.md:103 (`reference-code/sci/doc/interrupt.md:52,63-87`). Provider HTTP work has its own
  docs/seon/architecture/architecture.md:73 **Existing seams:** `src/seon/cluster/registry.clj:178` branch acquisition;
  docs/seon/architecture/architecture.md:75 `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-155`
  docs/seon/architecture/architecture.md:76 ping; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-116`
  docs/seon/architecture/architecture.md:103 **Existing seams:** `src/seon/fn/analyzer.clj:505-568` namespace context;
  docs/seon/architecture/architecture.md:135 **Existing seams:** `reference-code/datahike/src/datahike/versioning.cljc:323-367`;
  docs/seon/architecture/architecture.md:136 `reference-code/clj-kondo/src/clj_kondo/core.clj:242-270` cache processing.
  docs/seon/architecture/architecture.md:164 **Existing seams:** `src/seon/db.clj:1219` carriage, `:3586-3609` entity values,
  docs/seon/architecture/architecture.md:165 `:4050-4121` final report; `reference-code/datahike/src/datahike/db/transaction.cljc:1206-1226`
  docs/seon/architecture/architecture.md:166 validation callback; `reference-code/malli/src/malli/registry.cljc:17-30` registries.
  docs/seon/architecture/architecture.md:187 **Existing seams:** `reference-code/malli/src/malli/core.cljc:2193-2218`
  docs/seon/architecture/architecture.md:188 instrumentation; `reference-code/sci/src/sci/impl/utils.cljc:362-379`
  docs/seon/architecture/architecture.md:189 copy-on-write; `src/seon/sci/eval.clj:2218` base acquisition.
  docs/seon/architecture/architecture.md:227 **Existing seams:** `src/seon/error.clj:200-225` signature; `:1567-1681` recording;
  docs/seon/architecture/architecture.md:228 `src/seon/issue.clj:554-564` trigger identity, `:1028-1064` completion;
  docs/seon/architecture/architecture.md:229 `reference-code/datahike/src/datahike/db/transaction.cljc:903-922` tx provenance.
  docs/seon/architecture/architecture.md:259 **Existing seams:** `src/seon/test.clj:143-196` current timeout implementation;
  docs/seon/architecture/architecture.md:260 `reference-code/clojure/src/clj/clojure/test.clj:725-737` fixtures;
  docs/seon/architecture/architecture.md:261 `reference-code/datahike/src/datahike/versioning.cljc:550-585` copy limitation.
  docs/seon/architecture/architecture.md:325 **Existing seams:** `reference-code/datahike/src/datahike/versioning.cljc:734-748`;
  docs/seon/architecture/architecture.md:326 `reference-code/datahike/src/datahike/writing.cljc:860-889`; `src/seon/edit.clj:254-316`;
  docs/seon/architecture/architecture.md:327 `src/seon/fs/jvm.clj:593-712`. **Target owner:**
  docs/seon/architecture/data-modeling-guide.md:46 **Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:641-715`
  docs/seon/architecture/data-modeling-guide.md:47 upsert; `reference-code/datahike/src/datahike/db/utils.cljc:307-313` indexing;
  docs/seon/architecture/data-modeling-guide.md:48 `src/seon/schema/datahike.clj:26-27,100-106,182` symbol/identity database schema; §9 maps every declaration shape.
  docs/seon/architecture/data-modeling-guide.md:78 **Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:440-484`
  docs/seon/architecture/data-modeling-guide.md:79 history; `reference-code/datahike/src/datahike/gc.cljc:22-81,144-167` retained roots;
  docs/seon/architecture/data-modeling-guide.md:80 `src/seon/db.clj:4050-4121` final report; `src/seon/program.cljc` replacement.
  docs/seon/architecture/data-modeling-guide.md:118 **Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:831-836,998-1015`
  docs/seon/architecture/data-modeling-guide.md:119 cascade/sweep; `reference-code/datahike/src/datahike/db/utils.cljc:109-148`
  docs/seon/architecture/data-modeling-guide.md:120 ref resolution; `reference-code/datahike/src/datahike/pull_api.cljc:351-357,483-486,508-515`
  docs/seon/architecture/data-modeling-guide.md:143 **Grounding:** `src/seon/db.clj:3586-3609,4050-4121` complete values/final admission;
  docs/seon/architecture/data-modeling-guide.md:144 `reference-code/datahike/src/datahike/db/transaction.cljc:831-836,1206-1226`.
  docs/seon/architecture/data-modeling-guide.md:169 **Grounding:** `reference-code/datahike/src/datahike/db/transaction.cljc:587-625,718-770`
  docs/seon/architecture/data-modeling-guide.md:179 `src/seon/schema/datahike.clj:112-119,415-436`). Older database values whose
  docs/seon/architecture/data-modeling-guide.md:197 **Grounding:** `reference-code/datahike/src/datahike/index/persistent_set.cljc:31-132`
  docs/seon/architecture/data-modeling-guide.md:198 index keys; `reference-code/datahike/src/datahike/db/transaction.cljc:718-736,1020-1039`
  docs/seon/architecture/data-modeling-guide.md:199 tuple handling; `reference-code/datahike/src/datahike/datom.cljc:325-359` comparator;
  docs/seon/architecture/data-modeling-guide.md:209 The pinned Datahike fork's many-pull default is unlimited (`pull_api.cljc:16`). An
  docs/seon/architecture/data-modeling-guide.md:222 **Grounding:** `reference-code/datahike/src/datahike/pull_api.cljc:16,238-243,315-351`;
  docs/seon/architecture/data-modeling-guide.md:223 `src/seon/db.clj:1219` carried registry; [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md)
  docs/seon/architecture/data-modeling-guide.md:261 `compiled-storage` (`src/seon/schema/datahike.clj:77-145`) walks the compiled
  docs/seon/architecture/data-modeling-guide.md:272 | `[T {:seon.db/identity true}]` | `:db/unique :db.unique/identity` (`:182`); Datahike adds `:db/index` (`reference-code/datahike/src/datahike/db/utils.cljc:315
  docs/seon/architecture/data-modeling-guide.md:277 | `:seon.db/ref` | `:db.type/ref`, cardinality one (`:90-91`); Datahike indexes refs (`utils.cljc:319`) | A statement about a living entity (§3). The fold stops
  docs/seon/architecture/data-modeling-guide.md:278 | `[… {:seon.db/component true :seon.db/component-schema :child/key} :seon.db/ref]` | `:db/isComponent true` (`:185`) | The child is part of the parent's value:
  docs/seon/architecture/data-modeling-guide.md:279 | `[:set T]`, `[:vector T]`, `[:sequential T]` | The member attribute keeps `:db.cardinality/many`; vectors and sequences also use bridge cells (`src/seon/schem
  docs/seon/architecture/data-modeling-guide.md:282 | `{:seon.db/no-history? true}` | `:db/noHistory true` (`:186`) | Datahike writes no history datoms for the attribute (`reference-code/datahike/src/datahike/db/
  docs/seon/architecture/data-modeling-guide.md:287 | `[:map {:seon.db/attributes true} …]` (an entity schema) | no attribute of its own; each entry's attribute is selected for installation (`:229-240`) | The ent
  docs/seon/architecture/data-modeling-guide.md:288 | entity-schema properties: `:seon.program/partition`, `:seon.render/ai`, `:seon.render/html`, `:seon.render/form`, `:seon.render/units`, `:seon.db/component-sc
  docs/seon/architecture/reference-code.md:37 | Errors ([spec](../specs/errors.md)) | `malli/src/malli/core.cljc:203-207`; `datahike/src/datahike/db/transaction.cljc:27-32`; `clj-reload/src/clj_reload/core.
  docs/seon/architecture/reference-code.md:38 | Contracts, instrumentation | `malli/src/malli/core.cljc:2210-2245` (`-instrument-f`; reports at `:2233-2240`), `:3166-3187` (`-instrument`); `malli/src/malli/
  docs/seon/architecture/reference-code.md:39 | Schemas, registry | `malli/src/malli/registry.cljc:11-96` (`Registry` protocol; `fast`, `simple`, `composite`, `lazy`); `malli/src/malli/core.cljc:353-361` (`
  docs/seon/architecture/reference-code.md:40 | Data modeling, refs, components, writer validation ([data-modeling skill](../../../.agents/skills/data-modeling/SKILL.md)) | `datahike/src/datahike/db/transac
  docs/seon/architecture/reference-code.md:41 | Database reads | `datahike/src/datahike/api/impl.cljc:30-42` (`transact!`); `datahike/src/datahike/api/specification.cljc:429`; `datahike/src/datahike/pull_ap
  docs/seon/architecture/reference-code.md:43 | Branch and store lifecycle | `datahike/src/datahike/versioning.cljc:212-277` (`branch!`), `:279` (`delete-branch!`), `:494-501` (`release-materialized-db`); `
  docs/seon/architecture/reference-code.md:44 | Source analysis, exact-span write-back | `clj-kondo/src/clj_kondo/core.clj:67` (`run!`), `:242-270`; `clj-kondo/src/clj_kondo/impl/cache.clj:171-215`; `rewrit
  docs/seon/architecture/reference-code.md:45 | REPL path, reading and evaluation ([agent loop spec](../specs/agent-loop.md)) | `sci/src/sci/impl/interpreter.cljc:94-114` (`eval-string*`); `sci/src/sci/core
  docs/seon/architecture/reference-code.md:46 | Value printing | `fipp/src/fipp/edn.cljc:9-21`, `fipp/src/fipp/visit.cljc:32-52`, `fipp/src/fipp/engine.cljc:10-46`; `nrepl/src/java/nrepl/out/QuotaBoundWrite
  docs/seon/architecture/reference-code.md:47 | Reload | `clj-reload/src/clj_reload/core.clj:185-191` (`topo-sort-fn`), `clj-reload/src/clj_reload/parse.clj:163-175` (`topo-sort`), `core.clj:270-302` (`ns-u
  docs/seon/architecture/reference-code.md:48 | Agent loop, Flow ([spec](../specs/agent-loop.md)) | `core.async/src/main/clojure/clojure/core/async/flow.clj:165-200` (`process`), `:291-309` (`map->step`); `
  docs/seon/architecture/reference-code.md:49 | Render selection | `clerk/src/nextjournal/clerk/viewer.cljc:1470-1497` (`find-viewer`, `viewer-for`) | The value's explicit viewer, else the first predicate m
  docs/seon/architecture/reference-code.md:50 | Web, SSE | `hyperlith/src/hyperlith/impl/datastar.clj:122-181` (`render-handler`), `:42-49` (`send!`); `http-kit/src/org/httpkit/server.clj:345-350` (`write-s
  docs/seon/architecture/reference-code.md:51 | Test runner | `kaocha/src/kaocha/testable.clj:105-137` (`-run`, `run-hook`); `kaocha/src/kaocha/type/var.clj:30-45`; `kaocha/src/kaocha/plugin/filter.clj:10-9
  docs/seon/architecture/reference-code.md:52 | Shell | `babashka-process/src/babashka/process.cljc:95-114` (`check`), `:165-173` (`destroy-tree`), `:674-708` (`shell`) | Merge an options map with defaults 
  docs/seon/architecture/reference-code.md:53 | LLM provider ([skill](../../../.agents/skills/llm-providers/SKILL.md)) | `langchain4clj/src/langchain4clj/core.clj:249-262` (`build-model` on `:provider`) | P
  docs/seon/architecture/reference-code.md:59 | `reference-code/datahike/src/datahike/versioning.cljc:212-277` | Branch creation shares immutable indexes and updates branch-set state; it is not complete clu
  docs/seon/architecture/reference-code.md:60 | `reference-code/datahike/src/datahike/versioning.cljc:323-367,457-501,550-588` | Guarded head movement needs exclusive store-write access; immutable commit ma
  docs/seon/architecture/reference-code.md:61 | `reference-code/datahike/src/datahike/versioning.cljc:738-756`; `reference-code/datahike/src/datahike/writing.cljc:883-911` | Merge takes caller-supplied cont
  docs/seon/architecture/reference-code.md:62 | `reference-code/datahike/src/datahike/db/transaction.cljc:998-1015,1225-1234,1296` | Incoming ref sweep and final-report validation, including attempted write
  docs/seon/architecture/reference-code.md:63 | `reference-code/datahike/src/datahike/query.cljc:3010-3029`; `reference-code/datahike/src/datahike/db.cljc:423-442` | Revision-based read currency (generation
  docs/seon/architecture/reference-code.md:64 | `reference-code/datahike/src/datahike/datom.cljc:271-315,325-359`; `reference-code/datahike/src/datahike/pull_api.cljc:16,315-323` | Index ordering delegates 
  docs/seon/architecture/reference-code.md:65 | `reference-code/datahike/src/datahike/gc.cljc:22-81,144-167` | Reachable parents/heads and cutoff govern collection; retraction or branch deletion is not all-
  docs/seon/architecture/reference-code.md:66 | `reference-code/malli/src/malli/core.cljc:2210-2245,2672-2712,3166-3187` | Compiled schemas retain derived operations; instrumentation receives child contract
  docs/seon/architecture/reference-code.md:67 | `reference-code/malli/src/malli/registry.cljc:17-30` | Fast registry copies a HashMap; simple registry retains persistent data | Registry construction; count 
  docs/seon/architecture/reference-code.md:68 | `reference-code/malli/src/malli/error.cljc:44-172,288-306,374-390` | Declared error messages and structured humanization | B3 error prose composed from schema
  docs/seon/architecture/reference-code.md:69 | `reference-code/sci/src/sci/core.cljc:260-276,334-340`; `reference-code/sci/src/sci/impl/utils.cljc:410` | Intern, fork and copy-on-write Var update; shared J
  docs/seon/architecture/reference-code.md:70 | `reference-code/sci/doc/interrupt.md:52,63-87` | Interpreted function/loop interruption does not stop host calls | Evaluation and B4 termination |
  docs/seon/architecture/reference-code.md:71 | `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:29-36,257-260,271-320` | Future creation and serial proc loop; the compute transf
  docs/seon/architecture/reference-code.md:72 | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-155,265-286` | Ping returns responders; compute timeout belongs to compute transfo
  docs/seon/architecture/reference-code.md:73 | `reference-code/clj-kondo/src/clj_kondo/core.clj:242-270`; `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:171-215` | Cache processing precedes final f
  docs/seon/architecture/reference-code.md:74 | `reference-code/clojure/src/clj/clojure/test.clj:710-737`; `reference-code/clojure/src/clj/clojure/core/server.clj:275` | Canonical test fixture dispatch and 
  docs/seon/architecture/reference-code.md:75 | `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-181`; `reference-code/http-kit/src/org/httpkit/server.clj:345-350`; `reference-code/http-kit/src
  docs/seon/architecture/ui.md:26 `src/seon/render/walk.clj:1005`; B2 makes that choice explicit and removes the
  docs/seon/architecture/ui.md:33 (`reference-code/datahike/src/datahike/pull_api.cljc:16,315-323`); an explicit
  docs/seon/architecture/ui.md:57 The source model is `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-181`:
  docs/seon/architecture/ui.md:60 (`reference-code/http-kit/src/org/httpkit/server.clj:321-326`). Seon's current wait
  docs/seon/architecture/ui.md:61 is in `src/seon/render/web.clj:2704-2757` and must not disappear with package machinery.
  docs/seon/architecture/vocabulary.md:176 | `seon.id/id`, `seon.id/digest`, `seon.id/evaluation` | THE ONE IDENTITY DERIVATION: `id` hashes the value's structure with SHA-256, default length 12 or suppl
  docs/seon/specs/errors.md:69 `::m/invalid-dispatch-value`, spell-checked as a misspelled value (`malli/error.cljc:343-349`) [V]. A success alternative
  docs/seon/specs/errors.md:80 and ignores suppressed throwables (`clojure/core_print.clj:484-501`); its `:via` shape is what each link resembles.
  docs/seon/specs/errors.md:122 explainer (`m/explainer`, cached on the schema object, `core.cljc:353-361,2690-2712`); valid costs one cached validator
  docs/seon/specs/errors.md:126 `core.cljc:2236-2240` [V]). The malformed error and every error built while the registry itself fails are assembled as
  docs/seon/specs/errors.md:193 report becomes one adapter over Malli's five report kinds (`core.cljc:2219-2240` [V]):
  docs/seon/specs/errors.md:242 `evidence-path` through `call-lines` (the hand Levenshtein; Malli's is `error.cljc:245-280`).
  docs/seon/specs/errors.md:249 which receives the in-transaction database (`datahike/db/transaction.cljc:1172-1173` [V]); no counter, lock or
  docs/seon/specs/errors.md:264 structural, order-independent `id/id` hashing, which is already implemented (`src/seon/id.clj:246-261`). P4 proves
  docs/seon/specs/errors.md:331 `malli/src/malli/core.cljc:1861-1905`.
  docs/seon/specs/errors.md:337 `malli/src/malli/registry.cljc:17-96`; `malli/docs/reusable-schemas.md:116-120`.
  docs/seon/specs/errors.md:345 cases of input to type. References: `malli/src/malli/core.cljc:203-207`; `datahike/src/datahike/db/transaction.cljc:27-32`;
  docs/seon/specs/errors.md:346 `clj-reload/src/clj_reload/core.clj:367-381`.
  docs/seon/specs/errors.md:352 `malli/src/malli/core.cljc:2210-2240,3166-3187`; `malli/src/malli/error.cljc:332-390`; `malli/src/malli/util.cljc:128`;
  docs/seon/specs/errors.md:353 `datahike/src/datahike/db/transaction.cljc:1172-1173`.
  docs/seon/specs/errors.md:367 (References: `datahike/src/datahike/api/impl.cljc:30-42`; `datahike/src/datahike/pull_api.cljc:541-555`). This also
  docs/seon/specs/errors.md:370 transcripts compared in estimated tokens (References: `clerk/src/nextjournal/clerk/viewer.cljc:1470-1497`;
  docs/seon/specs/errors.md:371 `fipp/src/fipp/engine.cljc:10-46`). **S-delete**: §5/§6 spans and their tests; `seon.fault`'s remainder folds into

### deleted-namespace mentions (any living doc line)
  .agents/skills/repl/SKILL.md:23 `:seon.batch/repair`; balanced text passes through byte for byte.
  docs/seon/architecture/vocabulary.md:19 (nREPL's word; code: `pass`, `seon.batch`, CN-20); its one transaction is the **recording transaction** (code:
  docs/seon/plan.md:174 - **The concise performance line** (`seon.batch` owner; Q1): one line of about 40 tokens, only when a definition the
  docs/seon/plan.md:175 agent changed got costlier than main; request cost as `ms / MB`; `seon.batch/cost-diff`'s 500 tokens go.
  docs/seon/plan.md:308 accounting; `seon.batch` is a misleading name for the eval-request fold (rename belongs to its owner).
  docs/seon/specs/agent-loop.md:32 (nREPL's word; code still says `pass` and `seon.batch` until CN-20 is committed), its one transaction is the **recording
  docs/seon/specs/agent-loop.md:187 The read stage is the value `:seon.batch/read-stage` with a mode (`:reply`, `:code`, `:data`); the dial
  docs/seon/specs/errors.md:54 `:seon.batch/ordinal`) change in agent-loop P2.
  docs/seon/specs/errors.md:179 Entrants, each with exactly one catch [V for location]: the one REPL path (`seon.batch` stage guards, `seon.sci.kernel`);

## Notes

- Scope: the living set is AGENTS.md, docs/seon/plan.md, docs/seon/architecture/**, docs/seon/specs/** and .agents/skills/**/*.md. Front matter status superseded, shelved or draft skipped 16 files (listed in the Summary). Skills with no backticked symbols (clojurescript, codex-lanes) show 0 refs.
- Symbol check: a def of the name (defn, defn-, def, defmacro, deftest and any def* with metadata) in the file of the namespace's (ns ...) declaration. Libraries resolve against reference-code/* (test and corpus copies lose to src). Keyword check: src and resources/seon/schemas only, as specified; test/ and resources/seon/tasks are not searched for keywords, so a key used only in tests would count as absent.
- Known tool false positives, verified by hand: `datahike.query/source-context-unchanged?` and `datahike.query/create-plan-via-ir` (private defn- in reference-code/datahike/src/datahike/query.cljc, whose (ns ^{...} header defeats the metadata regex); `clojure.core/Throwable->map` (defined in core_print.clj after in-ns, not ns); `clojure.core/update-vals` is unverified against the pinned clojure revision.
- Task refs (ns/task-name) are resolved by a crude word match in resources/seon/tasks/<ns>.edn; 7 were counted resolved and not checked further.
- Labelled items: many unresolved rows sit under an explicit target, retired-words or [TARGET] label (vocabulary.md tables, plan.md, errors.md, agent-loop.md target paragraphs, the repl skill's [TARGET] lines). The script does not read labels; the For root judgements are by hand for the ten claims named there, not for all 85 rows.
- seon.batch: exists at HEAD (src/seon/batch.clj, test/seon/batch_test.clj). Living docs mention it in plan.md:174,175,308, errors.md:54,179 and agent-loop.md:32,187, with a rename still pending (CN-20 in agent-loop.md). The assignment's premise that it is deleted does not hold at HEAD.
- Dates: "last commit" is git log -1 --format=%cs per file. Nearly every living file last changed 2026-10-12, so dates do not separate stale from current docs at this granularity. The older ones (decisions 002, 003, 016 at 2026-08) are the only exceptions in the table.
- Not verified: whether any cited library function is absent only at the pinned reference-code revision versus absent upstream; the Haiku survey did not query default, so no runtime numbers are used.
- The script lives in the scratch directory (outside the repo); this file is its committed output. A reviewer can rerun the method by path from a checkout with the same scratch dir.

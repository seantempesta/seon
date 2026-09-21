---
type: research
status: draft
created: 2026-09-21
tags: [agent-platform, skills, citations, audit, docs]
---

# Skills citation audit — after today's documentation and reference-code cut

Scope: the twelve skills under `.agents/skills/*/SKILL.md` plus their
`references/*.md` (`.claude/skills` is one symlink to the same directory —
nothing is duplicated). Every `docs/` link, every `reference-code/<repo>/…`
path and every first-party `src|test|resources|bin|script` `file:line` was
resolved against HEAD and against the current submodule gitlinks. Read-only:
no JVM, no `bin/test`, no `bin/seon`. This extends
[the instructions audit §2](../../prds/agent-platform/research/instructions-and-process-audit-2026-09-21.md),
which checked 178 anchors before today's deletions and found zero missing
files; the deletions turned ten targets into dead links and the `references/*.md`
files it could not check carry eleven of the twenty-four dead instances.

**Vendoring:** every `reference-code/` path cited by a skill is in the twenty
repositories that remain (datahike, sci, malli, core.async, konserve, clojure,
clojurescript, http-kit, clj-kondo). `git submodule status` shows all nine
checked out exactly at their gitlink, so the lines read below are the lines at
the gitlink. **No skill cites an unvendored repository, and no
`reference-code/` line has drifted** — the same result as the earlier audit.
All drift is first-party.

## 1. Per-skill table

Lines = SKILL.md + its `references/*.md`. Citations = distinct `file:line`
anchors plus distinct `docs/` link targets. "Stale after the cut" counts
sections whose subject the agent-platform lane specs delete or replace.

| Skill | Lines | Citations | Verified | Drifted | Dead | Stale after the cut |
|---|---:|---:|---:|---:|---:|---:|
| clojure-testing | 168 | 19 | 14 | 5 | 0 | most of the file (B4) |
| clojurescript | 78 | 4 | 1 | 1 | 2 | whole skill (plan §deletions) |
| codex-lanes | 103 | 8 | 8 | 0 | 0 | whole skill (plan §deletions) |
| data-modeling | 205 | 24 | 22 | 2 | 0 | 3 sections (A2, B1) |
| data-oriented-clojure | 118 | 13 | 8 | 5 | 0 | 3 sections (A1, A2, B3) |
| datahike | 828 | 81 | 68 | 12 | 3 | 5 sections (A2) |
| datastar-web-ui | 282 | 27 | 24 | 3 | 0 | 2 sections (B2) |
| llm-providers | 93 | 15 | 11 | 3 | 2 | 1 section (B2/B3) |
| repl | 111 | 15 | 12 | 3 | 0 | 3 sections (B2, A1) |
| seon-context-config | 59 | 7 | 5 | 2 | 0 | 2 sections (B1, B3) |
| seon-flow-architecture | 823 | 85 | 70 | 6 | 17 | 4 sections (B2, B1) |
| ui-canvas | 56 | 5 | 5 | 0 | 0 | 1 section (B2) |
| **total** | **2,924** | **307** | **248** | **42** | **24** | — |

Dead and drifted are citation *instances*; the 24 dead instances resolve to
ten distinct deleted targets.

## 2. Dead links — `file:line → status → correction`

Ten distinct targets, 24 instances. Nine of them died in today's deletion of
`docs/prds/{archive,sci-execution-runtime}` and `docs/seon/issues/archive`;
`data-model.md` died with `docs/seon/concepts`/`components`.

| Citation | Status | Correction |
|---|---|---|
| `clojurescript/SKILL.md:9` → `docs/archive/prds/sci-execution-runtime/plan/README.md:345-351` | dead (program deleted) | the live CLJ-only ruling is `AGENTS.md:199-201` |
| `clojurescript/SKILL.md:54` → `docs/archive/prds/pre-2026-09/agent-fsm/research/cljs-async-await-2026-06-28.md` | dead | git history only; drop the link |
| `datahike/references/querying.md:169` → `docs/archive/prds/pre-2026-09/agent-fsm/research/datahike-primer.md` | dead | drop; `docs/seon/architecture/data-modeling-guide.md` is the live primer |
| `datahike/references/fork-maintenance.md:15` → `docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md` | dead (issue archive deleted) | folded by the note-lifecycle sweep; re-point at the surviving keeper in `docs/seon/issues/` or drop |
| `llm-providers/SKILL.md:14` → `docs/archive/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md` | dead (2 instances) | no live replacement; the live authority is `docs/seon/reference/llm-adapters.md` |
| `seon-flow-architecture/references/decisions.md:41,64,66,83,122,141,161` → `docs/archive/prds/sci-execution-runtime/plan/README.md:{475-500,256-269,490-500,889-899,451-467,961-981,1615-1616}` | dead (7 instances) | the rulings moved to `docs/prds/context-generation/plan/` + the ideas ledger; each row needs a live ruling or deletion |
| `seon-flow-architecture/references/decisions.md:48` and `workloads-and-scheduling.md:141` → `docs/archive/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md` | dead (2 instances) | git history only |
| `seon-flow-architecture/references/decisions.md:119` → `docs/seon/architecture/data-model.md` | dead | `docs/seon/architecture/data-modeling-guide.md` |
| `seon-flow-architecture/references/degraded-start.md:34,105` → `docs/archive/prds/sci-execution-runtime/research/{repl-workflows,checkpoint-audit}-2026-07-29.md` | dead (2 instances) | git history only |
| `seon-flow-architecture/references/workloads-and-scheduling.md:89` → `docs/archive/prds/sci-execution-runtime/research/workload-scheduling-truth-2026-07-29.md` | dead | git history only |

Two non-citation path mentions do not resolve and are prose, not anchors:
`bin/seon-fresh` (`degraded-start.md:70`, the operator is `bin/seon`) and
`test/failures` (`datahike/SKILL.md:383`, a schema concept, not a path).

## 3. Drifted citations — `file:line → status → correction`

Every one is first-party. `src/seon/db.clj`, `src/seon/sci/eval.clj`,
`src/seon/instrument.clj`, `src/seon/ai.clj` and `src/seon/flow.clj` account
for 32 of the 42.

| Citation | Status | Correction |
|---|---|---|
| `clojure-testing:13` → `bin/test:237` (`--fast` admitted) | drifted | flag parsed at `bin/test:162`; the lane refusal that admits `--fast` is `:242-243` |
| `clojure-testing:47` → `test/seon/test_support.clj:349`, `:400` (`retrying-base`, `database-base`) | drifted | `retrying-base` `:433`; `database-base` `:582` |
| `clojure-testing:68` → `test/seon/test_support.clj:707` (`run-database-body`) | drifted | `:911` |
| `clojure-testing:128` → `src/seon/instrument.clj:685` (re-arming with the supplied projection) | drifted | `arm-var!` `:860`; `apply!` `:927` |
| `clojure-testing:166` → `src/seon/id.clj:49` (`seon.id/evaluation`) | drifted | `:55` (`:49` is inside `symbol-in`) |
| `clojurescript:3` (frontmatter `description`) and `:15` → `AGENTS.md:247-254` | drifted | `AGENTS.md:199-201`; the description is read at trigger time by every agent |
| `data-modeling:21` → `src/seon/schema/edn.clj:316` (`packaged-forms`) | drifted | `:406` (`:316` is inside `merge-schema-resources`) |
| `data-modeling:186` → `src/seon/sci/eval.clj:1912-1944` (`fork-for-turn` reapplies the private layer) | drifted | `fork-for-turn` `:2184`; `regenerate-agent-context!` `:2121` |
| `data-oriented-clojure:38` → `src/seon/schema/edn.clj:303` (`packaged-forms`) | drifted | `:406` |
| `data-oriented-clojure:49` → `src/seon/instrument.clj:687` (`collect-contracts!`) | drifted | `:898` |
| `data-oriented-clojure:53` → `src/seon/instrument.clj:698` (live instrumentation owner) | drifted | `apply!` `:927` |
| `data-oriented-clojure:64` → `src/seon/db.clj:468` (`seon.db/read-evidence`) | drifted (blank line) | `read-evidence` `:904`; `append-read-evidence!` `:502` |
| `data-oriented-clojure:72` → `src/seon/sci/eval.clj:1956` (`base-ctx`) | drifted | `:2218` |
| `datahike:18` → `src/seon/db.clj:1697`, `:1892` (query and pull owners) | drifted | `q` `:1994`; `pull` `:2413` |
| `datahike:22` → `src/seon/db.clj:283` (`call-with-custody`) | drifted | `:365` |
| `datahike:64` → `src/seon/db.clj:3424` (validator wired into every admitted `transact!`) | drifted | `transact!` `:4578`; `:3424` is `write-ref-error` |
| `datahike:68` → `src/seon/db.clj:3226` (`write-report-error`), `:3052` (`write-entity-error`) | drifted | `:4050` and `:3609` |
| `datahike:79` → `src/seon/db.clj:3071` (final owning-value validation) | drifted | `write-entity-error` `:3609`; `:3071` is `perform-diff` (also cited by `data-modeling:110`, same correction) |
| `datahike:155` → `src/seon/db.clj:3002` (`write-entity-value`) | drifted | `:3586` |
| `datahike:169` → `src/seon/schema/datahike.clj:267` (`:db/tupleTypes`) | drifted | `:174` |
| `datahike:187` → `src/seon/schema/datahike.clj:123-185` (`form->datahike-value-type-in`) | drifted, symbol gone | no such Var at HEAD; the owners are `literal->datahike-value-type` `:35` and `malli->datahike-attr-in` `:141` |
| `datahike:251` → `src/seon/render/value.clj:16` (`transacted`) | drifted (blank line) | `:30` |
| `datahike:286` → `src/seon/db.clj:3605` (`seon.db/transact!`; also `datahike:20`) | drifted | `transact!` `:4578`; `:3605` is `write-error-identity` |
| `datahike:432` → `src/seon/db.clj:2204`, `:2217`, `:2231` (`history`, `as-of`, `since`) | drifted | `:2756`, `:2769`, `:2783` |
| `datahike:468` → `src/seon/turn.clj:3011-3037` (`latest-answering-turn-t`) | drifted | `:2980`; `:3007` is `unanswered-wakes` |
| `datastar-web-ui:44` → `src/seon/repl.clj:233`, `:166` (`text` owns the grammar) | drifted (`:233` blank) | `text` `:256`; `response` `:213` |
| `datastar-web-ui:78` → `src/seon/render/web.clj:1937` (`join-package`) | drifted | `:1849`; `:1937` is `calls-by-attribute` |
| `datastar-web-ui/design-principles:88` → `src/seon/render/block.clj:72-107` | drifted, past EOF | the file is 97 lines; `surface-id` is `:61-97` |
| `llm-providers:11`, `:59` → `src/seon/ai.clj:339-408` (`resolved-target`, `wire-settings`) | drifted | `resolved-target` `:391`; `wire-settings` `:606` |
| `llm-providers:25` → `src/seon/ai.clj:903-917` (`credential`) | drifted | `:1139`; `:887` is `stream-fold` |
| `llm-providers:30` → `src/seon/ai.clj:972` (`normalize-usage`) | drifted | `:1093` |
| `repl:39` → `src/seon/sci/eval.clj:1956` (`base-ctx`), `:1924` (`fork-for-turn`) | drifted | `:2218` and `:2184` |
| `repl:88` → `src/seon/instrument.clj:685` (`apply!`) | drifted | `:927` |
| `seon-context-config:18` → `src/seon/config.clj:330` (`default-decisions`) | drifted | `:511`; `validate-default-decisions` `:456` |
| `seon-context-config:31` → `src/seon/config.clj:504` (`apply!`, `effective`) | drifted | `apply!` `:842`; `effective` `:871` |
| `flow/decisions:60` and `flow/workloads:44` → `src/seon/flow.clj:83-115` (`var-process`) | drifted (2 instances) | `:132`; `:71` is `start-graph!` |
| `flow/workloads:73`, `:74` → `src/seon/flow.clj:135-137`, `:199-229` (`execute-work!`) | drifted (2 instances) | `:352` |
| `flow/workloads:96` → `src/seon/flow.clj:814` (`submit!!`) | drifted | `:871` |
| `flow/wakes-and-faults:20` → `src/seon/cluster/wake.clj:163-228` (`route!`) | drifted | `route!` `:442`; `unlisten!` `:566`; `:159` is `turn-opening-attributes` |
| `flow/degraded-start:89` → `src/seon/cluster.clj:1679-1703` (`read-advertisement`) | drifted | `:3578` |

The remaining ~40 % of anchors are ranges into EDN resources, CSS and shell
with no symbol to anchor on. Existence, EOF and the first/last line of each
range were checked and all hold; their prose claims were not re-judged.

## 4. Will be stale after the cut, grouped by lane spec

Named by spec and item. None of these is wrong today; each becomes wrong when
its lane lands.

**[lane-b4](../../prds/agent-platform/plan/lane-b4-tests-in-process.md) — the test system is the runtime.** §2 makes
`(seon.test/run request)` one function in the cluster's JVM.
- `clojure-testing:8-23` (the `bin/test` / `bin/test-fast` split, the tiered
  gate, `--paths` overlay admission, `missing-overlay-callers`) — §2, §5: the
  launcher, worker JVMs, the EDN wire protocol, the cached published base and
  the overlay all go; `bin/test-check` remains.
- `clojure-testing:41-68` (`retrying-base`, `database-base`, `run-database-body`,
  the worker-private store copy) — §2 row 8: the fixture becomes `d/branch!` +
  `sci/fork` off the cluster's base; `fork-database` (4,648 ms) dies.
- `clojure-testing:83-98` (the declared-long bound and `event-backstop-seconds`
  practice) — §7: 128 long declarations collapse to ~7.
- `datahike/SKILL.md:382-389` (`seon.test.failure`, `seon.test/reach`,
  `program-fn-row`) — §2 rows 3, 11: reach becomes the observed set written by
  the armed wrapper; member recording loses claims/workers/covered-by.

**[lane-a1](../../prds/agent-platform/plan/lane-a1-projection-carried.md) — the projection is read, never rebuilt.**
- `data-oriented-clojure:45-53` and `clojure-testing:124-136` (arming compiles
  contracts from the classpath; `collect-contracts!` walks `ns-interns`) — §0:
  `instrument/apply!`'s second bootstrap population and the eight other
  derivations go; the projection is read from `seon.db/carried-projection`.
- `repl:88-92` (instrumentation mode as a live acquisition) — same item.
- `data-modeling:19-36` (bridge compilation at acquisition) — §2.

**[lane-a2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md) — three answers become one.**
- `datahike:429-479` and `data-oriented-clojure:62-72` (read evidence, the
  since-diff, `read-evidence-changes`) — §2(a) "deleted": `index-evidence-current`,
  `replay-read`, `query-index-patterns`/`pull-index-patterns`,
  `read-evidence-changes`. Only Datahike's own attribute-revision comparison
  survives.
- `datahike:169-195`, `:352-363` and `data-modeling:23-36` (the EDN-string
  encoding for union/map-shaped values) — §2(b) "deleted": the bridge's
  union→string fallback, `edn-encoded-attr-in?` and the codec; `:db.type/any`
  stores the value.
- `datahike:240-260` (pulled-shape validation) — §2(f) "deleted":
  `pulled-entity-schema-key`, `validate-pulled-value`, `validate-pulled-result`.
- `datahike:60-90` (the final-report validator's shape) — §2(d): it moves into
  the fork's `validate-report` and gains the arity/render-target checks.
- `data-modeling:105-110` (component discovery through `total-pull-selector`) —
  §2(c) "deleted".

**[lane-b2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) — the walk is the history; the fork is the context.**
- `repl:37-56` and `data-modeling:186` (base-context acquisition and the
  private-layer diff) — §2a deletes `base-bindings`, `same-program-root?`,
  `regenerate-agent-context!`'s diff, the two kernel atoms and `copy-var*`.
- `datastar-web-ui:40-50` and `ui-canvas:29-31` (the history/transcript entry
  points) — §2c: `src/seon/render/transcript.clj` (2,443 lines) goes; the walk
  is the history.
- `flow/wakes-and-faults` (the mailbox proc, `CountedSlidingBuffer`) and
  `flow/workloads-and-scheduling:66-96` (`submit!!`, `execute-work!`) — §2b
  deletes the mailbox proc, `CountedSlidingBuffer` and `submit-evaluation!!`.
- `repl:19` / `src/seon/cluster/reply.clj` — B2 owns the rename of
  `seon.turn` → `src/seon/run.clj`; every `seon.turn` anchor in four skills moves.

**[lane-b1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md) — one publication path.**
- `seon-context-config:28-31` and `flow/degraded-start` (the operator's
  publication/adoption path, `refresh-source!`, advertisements and process
  records) — §0, §2c: seven entry points collapse to one; ~1,800 operator lines
  and the process-record/advertisement machinery go (`read-advertisement`
  included).
- `data-modeling:65-67` (`seon.source/digest`, the manifest sibling) — §2a: the
  manifest and the seal row go; the transaction report is the authority.

**[lane-b3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md) — one error model, one task family.**
- `data-oriented-clojure` and `datahike` wherever they show
  `seon.error/diagnostic` with its seven `diagnostic-*` keys — §2a: one flat
  error value; the hand-copied unions in 14 places go.
- `seon-context-config` dial vocabulary — §2c.
- `datahike:347` and `my.program`/issue vocabulary — §2b: `seon.issue` becomes
  the one `seon.task` family.

**[plan README §deletions](../../prds/agent-platform/plan/README.md) (rows 264, 267).** The plan deletes
`bin/codex-agent`, `bin/test-fast`, `bin/_test-slot`, `.codex/hooks.json`,
`SEON_CODEX_LANE` and its three consumers, the `--paths` overlay in `bin/test`,
`docs/seon/reference/{driving-codex-agents,codex-cli-hooks-2026-09-17}.md`, and
**both the `codex-lanes` and `clojurescript` skills by name**.

## 5. Status of the two named skills

**`codex-lanes` (103 lines, 8 citations, all eight verified).** Every
`bin/codex-agent:N` anchor holds exactly and the three `docs/` links resolve.
It is nonetheless the only pure-PROCESS skill: it teaches lane launch, model
and effort choice, and the gate/load rules a lane lives under. The plan deletes
`bin/codex-agent`, `SEON_CODEX_LANE` and both reference documents, so the skill
loses its subject entirely. Its own citations cannot detect that: they point at
a file that will not exist.

**`clojurescript` (78 lines, 4 citations, 1 verified / 1 drifted / 2 dead).**
It mines a pod that was deleted, through a program directory that was deleted
today: both `docs/archive/…` links are dead, and the `AGENTS.md:247-254` anchor
— repeated in the frontmatter `description` that every agent reads at trigger
time — is off by 47 lines (the ruling is `AGENTS.md:199-201`). Its only live
content is the "never do this" list. The instructions audit's verdict (shrink
to a pointer) is confirmed; the plan deletes it.

## 6. Recommendation table (recommendations only — no edits made)

| Skill | Recommendation | One line |
|---|---|---|
| clojure-testing | rewrite section | §§1–3 (gate, fixtures, bounds) are B4's subject; rewrite after B4 lands, fix the five drifted anchors now only if the skill is kept in the interim |
| clojurescript | retire | subject deleted, both links dead, trigger-time description wrong; replace with two lines in `AGENTS.md` if anything survives |
| codex-lanes | retire | delete with `bin/codex-agent` per plan README row 264 |
| data-modeling | keep | two drifted anchors; §component and §bridge sections re-check after A2 |
| data-oriented-clojure | rewrite section | the contracts/arming paragraph (`:45-53`) and the read-evidence paragraph (`:62-72`) are A1's and A2's subjects; the rest keeps |
| datahike | rewrite section | strongest skill and most-cited; five sections belong to A2 (read currency, EDN encoding, pulled shape, validator, pull selector); twelve drifted anchors are mechanical fixes |
| datastar-web-ui | keep | three drifted anchors (one past EOF); the history/transcript paragraph waits on B2 |
| llm-providers | keep | two dead links to a deleted research note and three drifted `ai.clj` anchors; re-point at `docs/seon/reference/llm-adapters.md` |
| repl | rewrite section | the base-context/private-layer section is B2's subject; three drifted anchors |
| seon-context-config | keep | two drifted anchors; publication paragraph waits on B1 |
| seon-flow-architecture | rewrite section | carries 17 of the 24 dead links, all in `references/`; `decisions.md` cites a deleted plan README seven times and is the file to rewrite or retire first |
| ui-canvas | keep | all five citations verified; target-stage skill, unaffected except by B2's render rename |

**One durable recommendation.** Hand-maintained line numbers into a churning
tree are the repository's own banned hand-maintained mirror. Every drift found
here is first-party and every vendored anchor held, because the vendored trees
do not move. The program graph already stores each declaration's span
(`:seon.fn` rows); a checker that resolves `symbol → current line` and fails on
drift would have caught all 42 instances, and would keep catching them.

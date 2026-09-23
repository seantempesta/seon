---
type: plan
status: design only; replaces B3 §5 rows 7–9 (add `seon.task` +450, convert −250, retire −3,900) with in-place slices
created: 2026-09-23
lane: b3-task-design (Opus 5.5)
extends: lane-b3-errors-tasks-dials.md §2b, §5 rows 7–9, §8; lane-namespace-agents-first-loop.md
---

# B3 task family, converted in place

**Answer.** B3 §5 row 7 adds a 450-line `seon.task` beside `seon.issue` and `seon.plan`, then
converts callers and retires the old pair. For the length of rows 7–9 that is two task writers,
two settlements and two render pairs: a parallel mechanism (AGENTS "One mechanism, accreted in
place"). Almost every function §2b names already exists in `seon.issue` or `seon.plan` and is
already on the turn path (table §2). The smallest route keeps the one entity and **shrinks it
toward §2b in seven slices**:

1. decouple the issue from the plan tree;
2. make detector completion depend only on the subject;
3. retire the plan tree;
4. retire the note pipeline;
5. turn observations into values;
6. rename by script to `seon.task`;
7. collapse the opening trials.

Every slice loads and adds under about 100 lines. Each one deletes more than it adds.
Projected net src is **≈ −2,760 lines**, and it only works with owner decision §6 (option A).
It adds no new namespace until the rename, which is a `git mv`.

## 1. What is on `default` now (measured, read-only JVM mode)

Probe namespace `b3-task-design.probe`, connection `(seon.cluster.boot/connection "default")`,
commit `6ab372ab-ebf4-5a3c-aa02-d45e6093c9ef`.

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      c (fn [q] (seon.db/q q d))]
  {:issues (c '[:find (count ?e) . :where [?e :seon.issue/id _]])          ; 396
   :with-path (c '[:find (count ?e) . :where [?e :seon.issue/path _]])     ; 396
   :detector (c '[:find (count ?e) . :where [?e :seon.issue/detector _]])  ; nil (0)
   :assigned (c '[:find (count ?e) . :where [?e :seon.issue/agent _]])     ; nil (0)
   :citations (c '[:find (count ?e) . :where [?e :seon.issue.citation/id _]]) ; 911
   :plans (c '[:find (count ?e) . :where [_ :seon.agent/plan ?e]])         ; 1 (root's)
   :plan-items (c '[:find (count ?e) . :where [?e :my.plan.item/id _]])})  ; nil (0)
```

| Fact | Value | Meaning for the design |
|---|---:|---|
| issue rows | 396, all with `:seon.issue/path` | every row came from indexing a note. None is detected, assigned or authored in the database |
| rows with tests / functions / members | 75 / 237 / 11 | citations resolved from note prose |
| citation components | 911 | `seon.issue.citation` exists only for notes |
| plan components / plan items | 1 / 0 | the plan tree is empty on `default`; root's component is the one `creation-tx` writes for every agent (`cluster/agent.clj:204`) |
| identity lookup `[:seon.issue/id (subject-id …)]` | 0.25 ms | trigger identity costs one AVET seek |
| `public-without-contract` whole population | 34.3 ms, 66 subjects | what `done?`'s detector branch reruns today to check one subject (`issue.clj:1010-1014`) |
| `public-without-doc` whole population | 12.2 ms, 41 subjects | same |

## 2. What §2b asks for that already exists

| §2b mechanism | Already installed at | What remains |
|---|---|---|
| `trigger-call` identity: present → nothing new, absent → create | Datahike upsert on the `:seon.db/identity` attribute. The fork drops an idempotent reassertion from the effective tx-data and listeners (`reference-code/datahike/src/datahike/db/transaction.cljc:606-617`, rev `131ca6360`). Identity is `issue/subject-id` (`issue.clj:538-547`). "Prose only when absent" is `generated-prose` (`issue.clj:527-530`, `:664`) | a per-finding `trigger-call` (~15 lines) arrives with its first caller: the fault recorder (B3 row 11) or D1's conflict writer. Nothing is written ahead of that caller |
| `start-call`: task + agent + first turn atomically; refuse without completion evidence | `create-tx` / `start-tx` (`issue.clj:1038-1203`): atomic creation, refusal with no tests or detector, resume by a larger budget. The branch is supplied (`78a35a2c1`) | the "existing agent" arm (root for a conflict task) lands with D1's conflict slice |
| `done?` | `issue/done?` + `tests-done-query` (`issue.clj:980-1016`) | the detector branch is whole-population. Slice 2 scopes it to the subject |
| `settle-call` | `plan/settle-call` (`plan.clj:776-797`) + `issue/exhaust-tx` (`issue.clj:1205-1235`) | the issue half moves into `seon.issue` (slice 1) |
| `run-tests!` via B4's `seon.test/run` | `plan/run-issue-tests!` (`plan.clj:697-732`) already calls `seon.test/run` `:named` over `seon.test/stale` | a move (slice 3). None of it is new |
| detectors | `issue/detect.clj:185,232,289`. Firing is `schedule.clj` `fire-call` | only a subject arity (slice 2) |
| render pair | `issue/render-ai`, `render-html` (`issue.clj:847-862`), declared in `seon.issue.edn` `:issue` | renamed only |
| listened `:seon.task/agent` | `seon.issue.edn` `:agent` (`:seon.wake/listen true`, `:seon.wake/opens-turn? true`) | renamed only |
| append-only tests after assignment | `:seon.db/append-only-after` on `:seon.issue/tests` | kept |
| `of-agent` | the reverse pull `{:seon.issue/_agent [...]}`, already a declared render unit (`seon.agent.edn:38`) | **no function**. Readers pull the reverse ref |
| plan-step cycle checks | `plan.clj:1025` `refuse-dependency-cycle!` | only under decision option B |
| `:my.plan/request` (used by `my.agent`, `my.note`, `seon.test`) | the same form is `:seon.agent/identity-request` (`seon.agent.edn:112-115`) | one sed (slice 3) |

Defects found while reading. They are recorded here because this lane writes only this doc; each
one is fixed by the slice named.

- `generate!` computes the whole detector population before the write (`issue.clj:702`). It then
  recomputes it inside the serialized writer (`:709` `[:db.fn/call #'generate request]`), which is
  twice, once under the writer lock. Slice 2 moves the derivation outside the writer and keeps
  only per-subject upserts inside it.
- `generate!` swallows the throwable: `(catch clojure.lang.ExceptionInfo error (ex-data error))`
  (`issue.clj:702-703`) drops the class, cause chain and frames (AGENTS "No swallowed errors").
  Fixed in slice 2.
- `(catch Exception _ nil)` around the note date parse (`issue.clj:420-423`). Deleted with the
  note pipeline in slice 4.
- Note indexing on publication is the existing issue
  `docs/seon/issues/issue-indexing-at-publication-costs-13-seconds.md`. Slice 4 retires it.

## 3. Slices

"Held" means held in `tmp/orchestrator/file-ownership.md` at 2026-09-23 ~07:20Z. The held files
are `cluster.clj`, `cluster/source.clj`, `schema.clj`, `instrument.clj`, `test.clj`,
`test/runner.clj`, `db.clj`, `cluster/wake.clj`, `cluster/store.clj` and `cluster/boot.clj`.

| # | Slice | Files | Added / net src | Stored shape | Held-file collision | Ready |
|---|---|---|---|---|---|---|
| S1 | issue settles itself; worker gets no plan step | `src/seon/issue.clj`, `src/seon/plan.clj`, `src/seon/render/transcript.clj`, tests | +25 / −20 | none (plan step simply not written) | none | **yes, first** |
| S2 | subject-scoped detector completion; `generate!` derives outside the writer; authored id minted once | `src/seon/issue.clj`, `src/seon/issue/detect.clj`, tests | +30 / −10 | none | none | after S1 (same file) |
| S3 | retire `seon.plan`, `my.plan`, `:seon.agent/plan` | `plan.clj`, `my/plan.clj`, `turn.clj`, `bootstrap.clj`, `cluster/agent.clj`, `cluster/status.clj`, `render/{transcript,value,walk,block}.clj`, `note.clj`, `my/{agent,note}.clj`, **`test.clj:1322`**, `issue.clj`; resources `my.plan.edn`, `my.plan.item.edn`, `seon.plan.edn`, `seon.agent.edn`, `seon.db.edn`, `seon.bootstrap.edn` | +85 (≈70 moved) / −1,800; schema −420 | retires plan attributes; in place (purge) | `test.clj`: one hunk | owner decision §6; hand the `test.clj` hunk to its holder |
| S4 | retire the note pipeline | `issue.clj`, **`cluster/source.clj:510-565`**, **`cluster.clj:2025,2495-2527`**, `fn.clj:3205-3215`, `script/seon/dev/issues.clj`; resources `seon.issue.edn`, `seon.issue.citation.edn` | +5 / −690; schema −45 | retires note attributes + citation component; **RESET boundary** (data drop) | `cluster.clj`, `source.clj` | after opus-publication releases them |
| S5 | observations are values; subject is a tuple; `status` derived | `issue.clj`, `my/program.clj:255-277`, `seon.issue.edn`, tests | +30 / −60 | type change on tests/functions/namespaces; in place, inside the same RESET window | none | after S4 (same resource) |
| S6 | rename `seon.issue` → `seon.task`, `my.issue` → `my.task` by script | every remaining referrer except `cluster/wake.clj` (comment only) | 0 / 0 | every attribute renamed; inside the same RESET window | `wake.clj` comment left to its holder | last stored-shape slice |
| S7 | collapse the opening trials to the default candidate | `issue/opening.clj` (then `task/opening.clj`), `seon.config.render.edn`, `cluster/agent.clj:277` | 0 / −180 | retires one dial; in place | none | any time after S6 (or before, pre-rename paths) |

**Total projected net src ≈ −2,760** (S1 −20, S2 −10, S3 −1,800, S4 −690, S5 −60, S6 0, S7 −180).
Resources shrink by ≈ −470 and tests by ≈ −1,800.

### S1 — the issue settles itself

- **Rule (hand edit, one owner):**
  - `issue/create-tx` stops writing the `:seon.agent/plan` step (`issue.clj:1108-1126`, −19
    lines). The plan step carried only `:my.plan.item/subject [:seon.issue/id id]` and
    `done-query`, which `plan/done-query-result` (`plan.clj:734-759`) turned back into
    `issue/done-query`: the issue's completion made a round trip through the plan tree.
  - Add `issue/settle-call [db agent-id]` (≈14 lines). It reads the agent's open assigned issue,
    writes `[:db/add issue :seon.issue/resolved-tx "datomic.tx"]` when `done?` holds, then composes
    `[:db.fn/call #'exhaust-tx agent-id]`.
  - In `plan/settle-call` (`plan.clj:776-797`), replace `#'issue/exhaust-tx` with
    `#'issue/settle-call` and remove the `(if (empty? plan-steps) [] …)` short-circuit, which
    today skips exhaustion for an agent without steps. `turn.clj`'s four `plan/settle-call` sites
    stay untouched.
  - Delete the issue branches in `done-query-result` / `completion-tx` (`plan.clj:739-740,
    766-771`), plus `plan/issue-done-query` (`:665-667`) and `issue/done-query`
    (`issue.clj:1018-1023`).
- **Transcript:** `session-stall` (`render/transcript.clj:1235-1239`) and the session objective
  (`:1253,1273`) read plan steps and objective. They pull `{:seon.issue/_agent
  [:seon.issue/problem :seon.issue/resolved-tx]}` instead. That is a reverse ref, with no new
  require and no `of-agent`.
- **Cost:** `settle-call` is one agent → issue join plus `done?` on that one issue. It is
  proportional to the issue's tests (a `tests-done-query` join over `seon.test/verified?`), not
  to the plan tree.
- **Proof on default:** `bin/seon init --dev default --changed src/seon/issue.clj
  src/seon/plan.clj src/seon/render/transcript.clj`, then one request: `bin/test-check default
  --policy incremental --changed seon.issue/create-tx seon.issue/settle-call seon.plan/settle-call`.
  That request reaches `seon.issue-settlement-test`, `seon.issue-test`
  (`detector-only-issue-starts-and-settles-from-its-subject`,
  `issue-worker-creation-is-atomic`), `seon.plan-completion-test`,
  `seon.namespace-agent-loop-test` and `seon.turn-test`. Delete the `seon.issue-test` assertion
  that finds the plan step (a test of a retired assumption: fix the expectation).

### S2 — detector completion is subject-local

- **Rule:** the three detectors (`detect.clj:185,232,289`) take the optional request key
  `:seon.issue/subject [attr value]`. With it, the query binds `?sym` from the subject instead of
  scanning `[?f :seon.fn/sym]`. `done?`'s detector branch (`issue.clj:1010-1014`) calls the
  detector with the subject and asks whether it is empty. `generate!` derives its findings once,
  outside the writer, and transacts one `[:db.fn/call #'generate-subject-call row]` per changed
  finding plus the resolutions. `generate` (`issue.clj:632-679`) shrinks to that per-subject
  decision. The `catch` at `:702-703` rethrows with the whole cause. `add-tx` (`:1267-1268`)
  mints `(id/id)` once rather than `(id/id [title subject])` (§2b: never from the title); `add!`
  reads the id back from the report.
- **Cost:** `done?` drops from whole population (12–34 ms per call, measured) to one subject.
  The writer work of `generate!` drops from population × 2 to the changed findings.
- **Proof:** adopt the two paths, then `bin/test-check default --policy incremental --changed
  seon.issue/done? seon.issue/generate seon.issue/generate! seon.issue/add-tx
  seon.issue.detect/public-without-contract seon.issue.detect/public-without-doc
  seon.issue.detect/public-without-reaching-test`. That reaches `seon.issue-generate-test`,
  `seon.issue.detect-test` and `seon.issue-test`. REPL probe: the §1 detector timing form with a
  subject argument should come in under 1 ms.

### S3 — retire the plan tree (needs decision §6)

- **Scripted rule 1:** `perl -pi -e 's/:my\.plan\/request\b/:seon.agent\/identity-request/g'
  src/my/agent.clj src/my/note.clj src/seon/test.clj`. That is six sites; the `test.clj:1322`
  hunk goes to its holder (test-overhead) or waits for its release.
- **Scripted rule 2:** `perl -pi -e 's/\bplan\/settle-call\b/issue\/settle-call/g;
  s/\bplan\/run-issue-tests!/issue\/run-tests!/g' src/seon/turn.clj`. That is seven sites
  (`turn.clj:2227,3564,3566,3603,3612,5011,5015`), plus the `[seon.plan :as plan]` →
  `[seon.issue :as issue]` require (`turn.clj:25`). `seon.issue` requires no `seon.turn`, so no
  cycle forms; `issue.clj:17-33` already resolves turn lazily.
- **Moved, not written:** `stale-issue-tests`, `run-issue-tests!` and `query-deadline`
  (`plan.clj:643-732`) move into `issue.clj` as `run-tests!` (≈70 lines moved). The
  `:my.plan/*` error members in them become the issue's declared refusal keys.
- **Hand residue, by site:**
  - `cluster/agent.clj`: drop the `:seon.agent/plan` creation line (`:204`) and the agent-table
    "current step" cell (`:340-341`); `:389` reads the new bootstrap key.
  - `cluster/status.clj:119` drops the plan pull.
  - `bootstrap.clj`: delete `admitted-intent` and the intent-subject path (`:525-581, :606-631`),
    which exist only for plan `:about` subjects. The worker issue step never set `:about`, so
    nothing observable is lost. The situation refusal (`:103-124`) moves from
    `:my.plan/agent-not-found-error` to one declared `:seon.bootstrap/agent-not-found-error`.
  - `render/transcript.clj:1774,2134-2147` drops the plan counts.
  - `render/value.clj:294-297` and `render/walk.clj:922` drop their plan cases.
  - `render/block.clj:70` needs a comment example.
  - `note.clj:68-104` drops `:my.note/about` to a plan item.
  - `seon.db.edn:10` drops `:my.plan/error` from the union.
  - `seon.agent.edn:37,55,188-190` drops the `:plan` attribute and its unit.
- **Deleted:**
  - `src/seon/plan.clj` 1,597, `src/my/plan.clj` 173, `bootstrap.clj` ≈75, other readers ≈30;
  - resources `my.plan.edn` 301, `my.plan.item.edn` 102, `seon.plan.edn` 14;
  - tests `test/my/plan_test.clj` 609, `test/my/plan_api_test.clj` 96,
    `test/seon/plan_test.clj` 45, `test/seon/plan_completion_test.clj` 71.
  - `test/seon/contracts_plan_test.clj` (99) keeps its two refusal-grammar behaviors against a
    surviving function (a surviving seam: fix the owner call, not the behavior).
- **Stored shape:** the plan attributes retire and are adopted in place (README §7 "Schema change
  and reset"). The 1.3e refusal holds because every writer (`plan.clj`, `creation-tx:204`) leaves
  in the same publication.
- **Proof:** one adoption of every S3 path. Then `bin/test-check default --gate --changed-path`
  over the S3 paths, reaching `seon.bootstrap-test`, `seon.turn-test`,
  `seon.issue-settlement-test`, `seon.cluster.agent-arming-test`,
  `seon.render.web-test` and `seon.render.value-test`. Also a REPL check that
  `(seon.db/q '[:find (count ?e) . :where [_ :seon.agent/plan ?e]] d)` refuses as an unknown
  attribute on the adopted branch.

### S4 — retire the note pipeline (collides with opus-publication)

- **Deleted from `issue.clj`:** `words` … `index!` (`:35-526`, 492 lines), apart from
  `replacement-tx` (`:284-320`), which `generate` still uses. Also the citation adoption path
  `citation-pattern` … `adopt!` (`:864-978`, 115) and `report` (`:1353-1387`, 35). That is ≈ −600.
- **Callers deleted:**
  - `cluster/source.clj:510-527` (`index-issues!`) and its `note-path?` filter (`:562`);
  - `cluster.clj:2025` (`note-path?` in the unchanged test), `:2495-2496` and `:2522-2527` (issue
    adoption);
  - `fn.clj:3205-3215` (uncited citation cleanup);
  - `script/seon/dev/issues.clj` (42).
- **Resources:** `seon.issue.citation.edn` (13) goes. From `seon.issue.edn` go `path`, `opened`,
  `keys`, `files`, `runs`, `issues`, `members`, `unresolved`, `commits`,
  `git-unbounded-error` / `notes-absent-error` and their members. `:seon.issue/cites` stays until
  S5 because `subject-row` still maps subjects through it.
- **Tests deleted:**
  - the note tests in `test/seon/issue_test.clj` (`:19,74,134,176,199,224,418,456`);
  - `test/seon/issue_deletion_test.clj`, `test/seon/issue_head_guard_test.clj`,
    `test/seon/cluster/publication_notes_test.clj`, `test/seon/dev/issues_test.clj`.
- **RESET boundary:** purging the note attributes in place would leave 396 id/title/problem rows,
  which would be the fake tasks §2b rules out. The rows are `:seon.program/partition :seon.data`,
  so the orchestrator's `bin/seon reset --force` (fork from program rows, caches kept; 4.3 s
  measured, README §7) drops them. That is ONE reset after S6, batched with B3 rows 9/10/12/13.
  Between S4 and that reset the rows are inert: unassigned, no detector.
- **Cost gained:** publication no longer reads notes or runs `git log`
  (`issue-indexing-at-publication-costs-13-seconds.md`).
- **Proof:** from the publication lane's save gate, run `bin/test-check default --gate
  --changed-path` over `src/seon/cluster.clj src/seon/cluster/source.clj src/seon/issue.clj
  src/seon/fn.clj`. A docs-only edit under `docs/seon/issues/` must then publish nothing.

### S5 — observations are values

- **Rule:**
  - `:seon.issue/tests`, `/functions` and `/namespaces` become `[:set :qualified-symbol]` values
    (§2b G2). A deleted test no longer silently sweeps out of the ref set and shrinks the done
    condition.
  - `:seon.issue/subject` is `[:tuple :qualified-keyword :seon.schema/value]`.
  - `:status` retires: open means no `resolved-tx`.
  - `tests-done-query` binds `?symbol` from the task and joins `[?test :seon.test/sym ?symbol]`. A
    symbol with no row fails the `not-join`, which reads as unknown and therefore not done.
  - `require-test-refs!` becomes a symbol-row check.
  - `create-tx`'s namespace lookup reads the symbol.
  - `citation-attributes` and `subject-row`'s cites mapping (`issue.clj:186-215, 549-590`) are
    deleted.
  - `my/program.clj:262-273` writes the value shape.
- **Stored shape:** a type change, adopted in place by purge, inside the S4 RESET window.
- **Proof:** `--changed` over `seon.issue/done? seon.issue/create-tx seon.issue/status
  my.program/breaks`, reaching `seon.issue-settlement-test`, `my.program-test` and
  `seon.namespace-agent-loop-test`.

### S6 — the rename, one script

```sh
files=$(rg -l 'seon\.issue|my\.issue|seon/issue|seon_issue' src test resources script | grep -v src/seon/cluster/wake.clj)
git mv src/seon/issue.clj src/seon/task.clj; git mv src/seon/issue src/seon/task
git mv src/my/issue.clj src/my/task.clj; git mv resources/seon/schemas/seon.issue.edn resources/seon/schemas/seon.task.edn
perl -pi -e 's/\bseon\.issue\b/seon.task/g; s/\bmy\.issue\b/my.task/g; s{seon/issue}{seon/task}g; s/\bseon_issue/seon_task/g' $files
aliased=$(rg -l '\[seon\.task :as issue\]' src test)
perl -pi -e 's/\[seon\.task :as issue\]/[seon.task :as task]/; s/(?<![\w.\/-])issue\/(?=[a-z])/task\//g' $aliased
```

- **Then:** `clj-kondo --lint` on the set, the `bin/seon-hook` syntax check, and one load.
- **Hand residue:**
  - test file names `issue_*_test.clj` → `task_*_test.clj` (`git mv` + ns line);
  - function names still saying "issue": `issues` → `tasks`, `:seon.issue.test/state`;
  - docstrings;
  - `seon.eval.edn:1` and `seon.config.render.edn:9` descriptions;
  - `seon.program.edn:235` `:seon.program/issues` → `:seon.program/tasks`, with its writer
    `my/program.clj:256`.
  - `cluster/wake.clj:119` is a comment naming `seon.issue/start!`; it goes to its holder (m4-n3)
    or waits.
- **Stored shape:** every attribute is renamed, adopted in place (retire + install in one
  publication, so 1.3e sees no surviving writer). This is the last change inside the one RESET
  window.
- **Proof:** `bin/test-check default --gate --changed-path` over the whole renamed set (one
  request).

### S7 — the opening trials

`issue/opening.clj` holds seven candidate openings behind `:seon.config.render/issue-opening`
(trial `issue-context-trials-2026-09-16.md`, concluded). Keep `default-candidate`'s forms and
delete the dial and the other candidates (≈ −180). This is B3 §2c's dial rule: the trial decided
it. The proof is `seon.issue-test/issue-worker-opening-links-its-issue` (renamed after S6).

## 4. Algorithmic costs at the end state

| Operation | Proportional to | Target |
|---|---|---|
| trigger (detected or authored) | one AVET identity seek + one pull for the prose-absent rule; idempotent reassertion elided by Datahike | O(1), < 1 ms |
| start | the task's tests + one agent creation + first-turn tx; resume = budget + settings + optional turn open | O(tests), not measured here. Nsa s1 did not time it; the implementing lane times it |
| `done?` | the task's tests (verified-at-current-reach join) + one subject-scoped detector query | O(tests + subject); today detector = population (34 ms) |
| settle | the closing agent's one open task | O(1 task), inside the closing transaction |
| `run-tests!` | the task's stale tests (`seon.test/stale`) through one `seon.test/run` | O(changed-reach tests); zero when nothing a test reaches changed |
| detector run (scheduled / publication) | the changed declarations or the population when root fires it | outside the writer after S2 |

## 5. The simplest alternative, rejected

- **Build `seon.task` fresh (B3 rows 7–9 as written).** Rejected. For two landings it runs two
  families on the turn path. It rewrites about 450 lines that exist and pass their tests
  (`issue-settlement-test`, `namespace-agent-loop-test`). Its caller conversion (row 8) touches
  the same thirteen `turn.clj` sites twice.
- **Keep the name `seon.issue` forever and skip S6.** Cheaper by one scripted slice, but the ruled
  vocabulary (AGENTS "Target, B3/D1/D2: one `seon.task` family") and the D1/C1 specs write
  against `seon.task`. The rename is a zero-line script run once at the point of fewest referrers,
  after S3–S5, so it stays.
- **Keep `:seon.issue/tests` as refs.** Rejected. A deleted test's ref sweeps silently, which
  shrinks the done condition. That is silence read as health (AGENTS habit 2).

## 6. Owner decision needed (blocks S3 only)

The plan tree has zero items on `default`. §2b replaces it with plan-step tasks (`parent`,
`needs`, `position`, with cycle, author and position-tie validation). §8 already defers "manual
`complete!`" to the owner. Three options:

| Option | Guarantee | Cost | Gives up |
|---|---|---|---|
| **A (recommended)**: retire `my.plan` now and add no replacement. `:seon.task/parent` / `needs` are declared later by the first caller that needs sub-steps (D1 or a namespace agent), in that caller's slice | one task family and one settlement; S3 lands at −1,800 src | zero new lines now; later ≈ 40 lines plus the cycle check moved from `plan.clj:1025` | agent-authored multi-step plans until a caller asks for them (0 live today) |
| B: build §2b's plan steps into the task writer first, then retire `seon.plan` | agents keep authored plans on the one entity | ≈ +120 src in one slice (over the 100-line bound, so presumed the wrong design) plus tests; S3 delayed a slice | nothing functional |
| C: keep `seon.plan` beside tasks and land S1 only | no agent-facing API retires | 0 now; 2,187 src + 417 resource lines kept as a second lifecycle | §2b's one-family target and the −1,800 shrink |

## 7. First ready slice

**S1** is the first ready slice (`src/seon/issue.clj`, `src/seon/plan.clj`,
`src/seon/render/transcript.clj`, plus the settlement tests). It is file-disjoint from every held
file above and needs no owner decision. S2 follows in the same lane (same file). S3 waits on
decision §6 and on the `test.clj:1322` hunk. S4 waits on opus-publication's `cluster.clj` and
`source.clj`.

## 8. Timings and proof boundary

| Operation | Wall | Justification |
|---|---:|---|
| census form with a whole-store `datoms :eavt` count | **7,747 ms** | this lane's probe defect: it decoded 31,714 datoms (`decode-attribute-value-in` 7,285 ms) to count issue datoms, proportional to the whole store. Dropped; not repeated, not needed for the design |
| counts + identity lookup + two detectors | 52 ms MCP (counts 2.2, lookup 0.25, detectors 34.3 / 12.2) | sub-second |
| every `rg` / `sed` / `git log` read | < 1 s each | — |

This is design only. There were no source edits, adoptions, test runs or writes to `default`.
Every `file:line` above was read at HEAD `e9e66028d` (issue.clj 1,387 lines; plan.clj 1,597). The
B3 spec's older line numbers (`issue.clj:1042-1064` and so on) are superseded by these. Estimates
of added and net lines are projections; each implementing lane reports its measured `wc -l` and
the hot-path probe on its parent and on itself.

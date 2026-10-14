---
title: "Pre-brief: TASK-CHAIN slices S1b-1, S1b-2, S1c-1, S1c-2"
type: research
status: pre-brief at HEAD 56f31a105, read-only
created: 2026-10-14
---

# Pre-brief: TASK-CHAIN slices S1b-1, S1b-2, S1c-1, S1c-2

Read-only pre-brief by Haiku, at HEAD 56f31a105 (spec `docs/seon/schedule/cards/TASK-CHAIN.md` §1, §2.1, §7). No source or test was edited. Symbols are fully qualified; `task/` means `seon.task`, `registry/` means `seon.cluster.registry`. Counts are from `rg`/`grep` over `src test script` unless marked otherwise.

## Summary of what matters

- The writer is private where the spec calls it public: `with-references`, `task-delta`, `held-tx` are `defn-` in `src/seon/task.clj`. Reds should reach them through `task/tasks-tx`, `task/adopt-tasks-tx` and `task/create!`.
- Current `task-delta` forces `:seon.task/execution :held` on every new task with no owning child (`src/seon/task.clj`, the `:else` branch of the `cond` in `task-delta`). A supplied `:running` is overwritten today. S1b-1's red is real, not stale.
- `:seon.task/outcome :done` collides with existing meaning: `:seon.task.attempt/end :done` already exists, and `seon.merge` (`src/seon/merge.clj:200`) maps an end `:done` to outcome `:merged` at merge time. Readers `seon.task/leaf-done` (`task.clj:1076`), `seon.context` (`context.clj:215-228`) and db-flow tests read `:merged` as "done". The spec does not say how `:done` relates to those. Needs a ruling before S1b-2.
- Census (rerun, 869 task-file entries): names are all strings (869 string, 0 symbol). 17 entries carry `:seon.task/needs`, 19 links, all `[:seon.task/sym ...]` lookup refs, matching the spec. Zero entries carry `:seon.task/execution`.
- Test results on HEAD: stored results could not be read (`seon.test.runner/latest-results` returned `[]` for all 18 chain/writer test symbols on default). Every test status below is **unknown**, not green.
- No lane holds any file these slices own. `git status` is clean for every owned file.
- Ordering: S1b-1 and S1c-2 read `:seon.task/plan`, which S1a (rename `intent` to `plan`) has not landed (spec status `—`). `with-references` derives from `intent` and `instructions` today. S1a must land first, or these slices must name `intent` and the rename must convert them.

---

## S1b-1: Defaults and names (Sonnet)

Change: `:seon.task/execution` `:default :held` decoded in `seon.task/task-delta` and `seon.task/held-tx`; `seon.task/symbol-name?` moves into the `:seon.task/name` schema.

Red proof (spec): `seon.task-test/task-members-have-defaults-and-kinds`, does not exist yet.

### Functions and schemas changed

| Symbol | Where | Current behaviour |
|---|---|---|
| `seon.task/task-delta` (private) | `src/seon/task.clj` ~L281 | new task with no owning child gets `:seon.task/execution :held` (overwrites a supplied value); owned children get execution dissoc'd |
| `seon.task/held-tx` (private) | `src/seon/task.clj` ~L327 | `[:db/add e :seon.task/execution :held]` for stored tasks with no execution and no `:seon.task/children` parent |
| `seon.task/symbol-name?` | `src/seon/task.clj` L55 | `defn` with `:malli/schema [:=> [:cat :string] :boolean]`; moves into `:seon.task/name` schema |
| `seon.task/create!` | `src/seon/task.clj` ~L688 | its separate `symbol-name?` check (`:invalid-name` refusal) goes |
| schema `:seon.task/name` | `resources/seon/schemas/seon.task.edn:8` | `[:string {:min 1 :description ...}]`, no predicate |
| schema `:seon.task/execution` | `resources/seon/schemas/seon.task.edn:51` | `[:enum {...} :held :running :paused :stopped :cancelled]`, no `:default` |

### Callers of the changed symbols

- `seon.task/symbol-name?`: `src/seon/task.clj:153` (`file-declarations`, refuses `:invalid-name`), `src/seon/task.clj:710` (`create!`), `test/seon/test_support.clj:1303` (docstring only). Moving it changes the `:invalid-name` path for file ingestion too: `test/seon/task_ingestion_test.clj:114` `malformed-task-files-refuse-by-name-without-writing` asserts `:invalid-name` from `:invalid-name "[#:seon.task{:name \"x y\" ...}]"` (L122). The refusal variant must keep that name, or the test changes in the same commit.
- `seon.task/create!`: src callers `src/seon/merge.clj:149`, `src/seon/cluster/agent.clj:1948`, `src/my/task.clj:82`, `src/seon/context.clj:532` (emits a `(seon.task/create! ...)` form as text), `src/my/program.clj:723` (`my.program/source` of it). Tests: about 20 test files (see the list under "Tests", below).
- `:invalid-name` readers: `src/seon/task.clj:153, 694 (docstring), 711`; `test/seon/task_ingestion_test.clj:122`.

### Tests that reach these (status: unknown on HEAD)

- `seon.task-ingestion-test/malformed-task-files-refuse-by-name-without-writing` (`test/seon/task_ingestion_test.clj:114`): reaches `symbol-name?` through `file-declarations`.
- `seon.task-ingestion-test/ingested-tasks-answer-both-ways-and-unchanged-input-writes-nothing` (L71): `test/seon/task_ingestion_test.clj:89` arms `'#{seon.task/file-declarations seon.task/task-delta}` and asserts zero calls on unchanged input. Moving or changing `task-delta` changes what that test counts. Green status unknown.
- `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` (`test/seon/task_test.clj:395`) reads `:running` on a supplied execution through `task/ready`.
- `seon.cluster.db-flow-test` runs with `:seon.task/execution :running` (`test/seon/cluster/db_flow_test.clj:116, 388, 416`).

### Quoted references (docs/skills/task files)

- `docs/seon/architecture/contracts-guide.md` (2 lines), `docs/seon/architecture/vocabulary.md` (1), `docs/seon/plan.md` (3), `docs/seon/schedule/agent-loop-ledger.md` (3), `docs/seon/schedule/cards/DYNAMIC-CONTEXT.md` (3), `docs/seon/schedule/cards/ROOT-AGENT.md` (5), `docs/seon/schedule/cards/RT-8.md` (3), `docs/seon/specs/merge.md` (2), `docs/seon/specs/task-steps.md` (2), `.agents/skills/data-oriented-clojure/SKILL.md` (1): each matches one of the target symbols (mostly `seon.task/create!`, `seon.task/declaration`, `chain!`). Not checked line by line; the convert-or-leave decision belongs to S1b-1's author.
- `resources/seon/tasks/seon.task.edn:30` names `seon.task/create!` and `seon.task/adopt-tasks-tx` in an intent string.

### Contradictions with HEAD

- `task-delta` overwrites a supplied `:running` on a new task (see above). The spec says it must survive; this is the intended red.
- `symbol-name?`'s `:invalid-name` refusal is shared with file ingestion. Moving the check into the schema must keep that variant, or `task_ingestion_test` changes too.

---

## S1b-2: Chain schemas (Sonnet)

Change: `:seon.task/next` (one non-component peer ref), `{:seon.task/owned true}` on `instructions` and `proofs`, outcome `:done` on the attempt, the chain fact (`:seon.chain/*`, non-program, `:seon.wake/starts`), and its claim clause in `registry/cluster-claims`.

Red proof (spec): owned members exactly `#{instructions proofs}` by schema property; a chain fact's branch and base survive `registry/retire-unclaimed!`, held chains included.

### Functions and schemas changed

| Symbol | Where | Current behaviour |
|---|---|---|
| `seon.task/declaration-members` | `src/seon/task.clj:99` | derives member order and set-ness from `:seon.task/declaration`; `:owned` is not read anywhere yet |
| `seon.task/declaration` | `src/seon/task.clj:108` | `[:=> [:cat :seon.schema/projection [:maybe :map]] [:maybe :seon.task/declaration]]` |
| `registry/cluster-claims` (private) | `src/seon/cluster/registry.clj:530` | claims the branch itself and each open agent row's branch, gated by a `:seon.cluster/name` row naming it as a cluster branch |
| `registry/retire-unclaimed!` | `src/seon/cluster/registry.clj:552` | `[store claimed]`: retires every branch no claim, connection or base holds |
| schema `:seon.task/declaration` | `resources/seon/schemas/seon.task.edn:22` | members ns, name, step, intent, instructions, children, needs, product, proofs |
| schema `:seon.task/outcome` | `resources/seon/schemas/seon.task.edn:75` | `[:enum {...} :merged :closed]` (no `:done`) |
| schema `:seon.task.attempt/end` | `resources/seon/schemas/seon.task.attempt.edn` | already `[:done :given-back :capped :stalled :sent-back :cancelled :error]` |
| schema `:seon.task/next`, `:seon.chain/branch`, `:seon.chain/first` | new | none exist yet |

### Callers of `registry/cluster-claims` and `registry/retire-unclaimed!`

- `registry/cluster-claims`: only `src/seon/cluster/registry.clj:569` (inside `retire-unclaimed!`). Private.
- `registry/retire-unclaimed!`: `src/seon/cluster/boot.clj:202` (boot, `#{source/current-branch}`); `src/seon/test.clj:2229` (docstring); tests `test/seon/merge_test.clj:429`, `test/seon/cluster/registry_test.clj:702, 705`, deftest `boot-retires-only-branches-no-fact-claims` (`registry_test.clj:678`).

### `:seon.task/outcome` readers (must be decided before adding `:done`)

- Writers: `src/seon/merge.clj:200, 202` (end to outcome: `{:sent-back :closed :done :merged}`), `src/seon/merge.clj:285, 289`, `src/seon/task.clj:1034, 1046` (`close!`), `src/seon/cluster/agent.clj:2230` (`:closed`).
- Readers: `src/seon/task.clj:1076` (`leaf-done`: `:merged`), `src/seon/task.clj:1148` (`missing?` relaunch), `src/seon/task.clj:1183-1185` (`resolution`), `src/seon/context.clj:215-228`, `src/seon/cluster/agent.clj:2075, 2089`, `src/my/root.clj:75, 86-87`.
- Tests: `test/seon/task_test.clj:170, 176`, `test/seon/context_test.clj:208-214`, `test/seon/merge_test.clj:529`, `test/seon/cluster/db_flow_test.clj:408, 445, 803-804, 911, 985-987, 1013, 1191, 1213, 1236, 1310, 1518`, `test/seon/cluster/merge_test.clj:349`.

### Tests that reach these (status: unknown on HEAD)

- `seon.task-test/the-task-facts-adopt-on-a-branch-of-the-live-store`, `seon.task-test/only-a-merge-completes-a-leaf` (`task_test.clj:437`, reads `:merged`).
- `seon.cluster.db-flow-test/a-scripted-full-chain-completes-without-hand-acts`, `a-hand-assignment-writes-the-attempt-on-the-agents-own-branch`, `a-refinement-on-a-branch-reaches-main-only-at-the-end`.
- `seon.cluster.registry-test/boot-retires-only-branches-no-fact-claims` (`registry_test.clj:678`), and its `^{:seon.test/platform ...}` siblings in the same file.
- `seon.merge-test/a-base-ref-keeps-the-fork-commit-only-until-retirement`.

### Contradictions and gaps with HEAD

- Spec says the chain fact is "counted by `cluster-claims` (a `[_ :seon.chain/branch ?b]` clause, holding the branch and its `base/` ref)". The current `cluster-claims` returns nil unless the branch is a cluster branch (`:seon.cluster/name` row). The clause must be evaluated on main, where the chain fact lives, inside that gate. The spec does not say so.
- Spec's `:done` outcome conflicts with `seon.merge` mapping end `:done` to `:merged`, and with readers that treat `:merged` as completion (see above). Ruling needed.
- `:seon.task.attempt/end :done` already exists, so "outcome `:done` on the attempt" is a second meaning of the same word on the same entity. The spec calls this the "one place completion lives"; the readers have not been checked against that.
- The owned-member property `{:seon.task/owned true}` has no reader. The red test must read it through the schema, not through a function that does not exist yet.

---

## S1c-1: The one task writer (Opus)

Change: `seon.task/adopt-tasks-tx`'s replacement preserves negative tempids and a supplied `:execution`; add `seon.task/template`.

Red proof (spec): three maps with tempids written in one transaction pull back linked, references derived, identities from `seon.task/task-handle`.

### Functions changed (all in `src/seon/task.clj` unless noted)

| Symbol | Visibility | Current behaviour |
|---|---|---|
| `seon.task/adopt-tasks-tx` | public | `(dissoc % :db/id)` on every entity, so tempids are stripped; then `held-tx` and `task-delta` |
| `seon.task/tasks-tx` | public | file writer; `symbol-tx`, `held-tx`, `task-delta`, `admitted`; `:duplicate-name` refusal |
| `seon.task/task-delta` | private | see S1b-1; retraction order by `refs-last` and `sort-by`; `:revision-conflict` refusal with base/current/proposed |
| `seon.task/held-tx` | private | see S1b-1 |
| `seon.task/with-references` | private | derives `:seon.task/references` from `:seon.task/intent` and `:seon.task/instructions` via `seon.ref/names`; becomes `plan` after S1a |
| `seon.task/task-handle` | public | `(id/id [ns name])` |
| `seon.task/create!` | public | `task-entity` then `task-call`; calls `symbol-name?` (goes in S1b-1) |
| `seon.task/template` | new | none |

### Callers

- `seon.task/adopt-tasks-tx`: `src/seon/task.clj:501` (`adopt-tasks-call`), `:676-678` (`create!`/`task-call`), `:768` (`chain!`), `:878` (`task-placed!`); `src/seon/cluster.clj:2527` through `seon.task/adopt-tasks-call`; tests `test/seon/task_test.clj:307` (adopt-call), `test/seon/task_ingestion_test.clj:201-213`.
- `seon.task/tasks-tx`: `src/seon/task.clj:514, 516` (`index-tasks!`); `test/seon/cache_test.clj:165` (weight counter), `test/seon/cluster/merge_test.clj:519` (`(task/tasks-tx (db/db connection) files ...)` must be `[]`).
- `seon.task/task-handle`: about 110 test call sites, and `src/seon/cluster/source.clj:801` and `:1770`.
- `seon.task/declaration`: `src/seon/cluster/source.clj:1709, 1728, 1773`; tests `test/seon/task_ingestion_test.clj:268`, `test/seon/render_cache_test.clj:164`, `test/seon/context_test.clj:68`, `test/seon/cluster/db_flow_test.clj:827`, `test/my/task_test.clj:105, 130`. Docstring-only: `test/seon/ref_test.clj:262`.

### Tests that reach these (status: unknown on HEAD)

- `seon.task-ingestion-test/adoption-carries-published-tasks-keeps-the-destinations-own-and-refuses-a-competing-edit` (L190), `seon.task-ingestion-test/a-revised-task-shows-on-the-next-call-without-reassignment` (L252), `seon.task-ingestion-test/assignment-refuses-a-task-without-proofs` (L273).
- `seon.task-test/an-adoption-keeps-a-merged-refinement-the-files-have-not-seen` (L283).
- `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion`.
- `seon.cache-test/a-kinds-weight-counts-its-value-not-its-key` (L155): counts `seon.task/tasks-tx-weight`.
- `my.task-test/refine-preserves-every-declared-member` (`test/my/task_test.clj:84`).

### Quoted references

- `docs/seon/specs/task-steps.md`, `docs/seon/architecture/contracts-guide.md` and `CLAUDE.md` name `seon.task` writer words in general. `resources/seon/tasks/seon.task.edn:30` names `seon.task/adopt-tasks-tx` (see S1b-1).

### Contradictions and gaps

- Spec says the writer "preserves negative tempids". Today `adopt-tasks-tx` removes `:db/id`, and `task-delta` uses `(:db/id (stored id))` for existing entities only. A new entity needs a tempid as part of its transaction data, and the `held` comparison `(= (held stored) (held proposed))` ignores execution, so it does not handle a supplied `:running` on an existing task.
- `seon.task/task-handle` is public, but `with-references` and `task-delta` are private: their red reaches through `tasks-tx`/`adopt-tasks-tx`.
- `chain!` is the only current source of `:seon.task/children` and `:seon.task/needs` in writes (S3e removes them). Any S1c-1 test fixture that builds children must be removed with S3e, not here.

---

## S1c-2: `chain!` (Opus)

Change: `seon.task/chain!` takes a vector of task maps, `seon.task/chain-options` (`:seon.chain/from`), request and answer schemas, forks through `registry/fork!` with target main, one transaction through the task writer, the chain fact on main, idempotence.

Red proof (spec): `seon.task-test/a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch` (does not exist yet).

### Functions changed

| Symbol | Visibility | Current behaviour |
|---|---|---|
| `seon.task/chain!` | public | `[connection subject templates customizations]`; builds children, submits through `adopt-tasks-tx` in one `written`; refuses `:unknown-task` |
| `seon.task/chain-copy` | private | copies a template's `step`, `instructions`, `obligations` proofs, `needs` before-link; deleted (S1c-2 converts its last caller, `chain!`) |
| `registry/fork!` | public | request `{:seon.store/store :seon.store/branch :seon.cluster.registry/from :seon.db/db :seon.branch/target}`; parent is `from` when it is a keyword, else `(get-in source-value [:config :branch])` |
| `seon.task/chain-options` | new | none; `:seon.chain/from` is not a key `fork!` reads today |
| `seon.task/template` | new | see S1c-1 |
| `seon.task/create-chain-fact` or similar | new | the chain fact on main, `:seon.chain/branch`, `:seon.chain/first` |

### Callers of `seon.task/chain!`

- Source: none outside its own docstring (`src/seon/task.clj:750`).
- Tests (4 deftests, 3 files): `test/seon/task_test.clj:400-433` (`one-write-clones-a-chain-and-one-running-readies-survey`, plus a case with `(chain! connection (assoc subject :seon.task/name "other") ...)` at L433); `test/seon/step_test.clj:119, 159, 182` (`survey-waits-for-the-latest-user-approval`, `a-default-judges-the-task-it-is-given`); `test/seon/cluster/db_flow_test.clj:1492-1499` (`a-scripted-full-chain-completes-without-hand-acts`). All use the old subject/templates API, so they convert or retire with S1c-2 and S4a.
- Also `test/seon/cluster/write_back_test.clj:576` (comment only) and `test/seon/cluster/db_flow_test.clj:416` (metadata).

### Callers of `registry/fork!` (for the `:seon.branch/target` and `:seon.chain/from` mapping)

- 42 call sites in 18 files. Source: `src/seon/cluster/agent.clj:1147` and `:1867` (the second passes `:seon.branch/target main`), `src/my/agent.clj:148`, `script/start_flow_benchmark.clj:150`. Tests: `test/seon/db_test.clj` (2), `test/seon/test_test.clj` (3), `test/seon/test_support.clj:1550`, `test/seon/sci/eval_test.clj:2735`, `test/seon/cluster/write_back_test.clj` (8), `test/seon/cluster/merge_test.clj` (6), `test/seon/cluster/source_test.clj:387`, `test/seon/cluster/host_test.clj:744`, `test/seon/cluster/registry_test.clj` (about 8), `test/seon/cluster/db_flow_test.clj:761`, `test/seon/test/runner_test.clj` (2), `test/my/root_test.clj` (2), `test/my/program_test.clj` (2), `test/seon/merge_test.clj:240`.

### Tests that reach it (status: unknown on HEAD)

- The four `chain!` deftests above; `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey` is the closest current shape to the S1c-2 red.
- `seon.step-test` chain deftests go with S4a.

### Contradictions and gaps

- The spec's `fork!` is called "with `:seon.branch/target` always main and `:seon.chain/from` selecting only the starting head". `fork!` does not read `:seon.chain/from`. It reads `:seon.cluster.registry/from`. The mapping must be stated in S1c-2 (`:seon.chain/from b` becomes `:seon.cluster.registry/from b`, a keyword branch, so `parent` is `b` and base is `(base-commit-id database b)` when target differs from parent).
- `fork!` with no `:seon.cluster.registry/from` takes its parent from `:seon.db/db`'s `:config :branch`. Spec's chain starts from main's head: `chain!` must pass `:seon.db/db` of main, or the request is refused ("A fork from a commit names its source value").
- Spec says `chain!` answers `{:seon.chain/branch b :seon.chain/first h :seon.task/handles [h …]}`. Today it answers the `written` report.
- Spec says create is idempotent and "a repeated call with the same first task returns the existing chain". Today's `chain!` is idempotent only through `adopt-tasks-tx`'s diff; the fork has no such check.
- Spec's "a crash after the fork and before the fact leaves an unclaimed branch, which boot retires" depends on S1b-2's `cluster-claims` clause (which is not yet present).

---

## Collisions (`bin/lane status` and `git status`)

- Active lanes, from `bin/lane status`:
  - `s1d1-text` (since 56f31a105, 6 paths): `src/my/edit.clj`, `src/seon/edit.clj`, `resources/seon/schemas/my.edit.edn`, `resources/seon/tasks/my.edit.edn`, `test/my/edit_test.clj`, `test/seon/edit_test.clj`. No overlap with S1b-1, S1b-2, S1c-1 or S1c-2. S1d-2 (`my.task/update!`) will touch `src/my/task.clj`, which `s1d1-text` does not hold.
  - `storage-nodes-missing` (1 path, docs) and `warm-request-regression` (3 paths, docs). No overlap.
- Uncommitted changes (`git status --short` on owned files): none in `src/seon/task.clj`, `src/seon/cluster/registry.clj`, `src/seon/cluster/source.clj`, `src/seon/cluster/agent.clj`, `src/seon/cluster/boot.clj`, `src/seon/merge.clj`, `src/seon/context.clj`, `src/my/task.clj`, `src/my/agent.clj`, the task schemas, `resources/seon/tasks/seon.task.edn`, or the listed tests.
- Repository-wide, unrelated to these slices: `reference-code/datahike`, `reference-code/konserve`, `tmp/test-data-assertions/proofs.edn` are modified; many untracked research files under `docs/research/agent-platform/`.

## Census (rerun, `bb` EDN read of `resources/seon/tasks/*.edn`)

- Files: 101. Entries: 869.
- `:seon.task/name`: 869 strings, 0 symbols, 0 other. (Spec: "869 measured" string names; confirmed.)
- Entries with `:seon.task/needs`: 17. Links: 19, all `:seon.task/sym` lookup refs. (Spec: 17 entries, 19 links; confirmed.)
- `:seon.task/execution` present in task files: 0 entries.
- Key frequencies: intent 857, instructions 18, children 6, step 14, product 6, needs 17.
- `:seon.task/step`: `:prove` 7, `:implement` 7.

## Tests status note

- Stored results: `seon.test.runner/latest-results` (JVM, root, default) returned `[]` for these 18 symbols: `seon.task-test/the-task-facts-adopt-on-a-branch-of-the-live-store`, `seon.task-test/an-adoption-keeps-a-merged-refinement-the-files-have-not-seen`, `seon.task-test/one-write-clones-a-chain-and-one-running-readies-survey`, `seon.step-test/survey-waits-for-the-latest-user-approval`, `seon.step-test/a-default-judges-the-task-it-is-given`, the five `seon.task-ingestion-test` deftests named above, `seon.cluster.db-flow-test/` three deftests, `seon.cluster.registry-test/boot-retires-only-branches-no-fact-claims`, `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion`, `seon.cluster.merge-test/only-an-accepted-candidate-completes-a-task`, `seon.merge-test/a-base-ref-keeps-the-fork-commit-only-until-retirement`, `my.task-test/refine-preserves-every-declared-member`, `seon.cache-test/a-kinds-weight-counts-its-value-not-its-key`. Empty means no stored result under these symbols, so status is **unknown** for all of them. A throwaway JVM run of these namespaces is needed before any slice relies on a HEAD baseline.
- Note: `latest-results` takes `[database test-symbols]`; the call was `(seon.test.runner/latest-results db tests)` with `db` from `(seon.db/db (seon.cluster.boot/connection "default"))`. Untested whether the symbols need a different form.

# `TASK-CHAIN.md` Spec-versus-Code Audit

Target: `docs/seon/schedule/cards/TASK-CHAIN.md` (lines 1–590) verified against `src/`, `resources/seon/schemas/`, `resources/seon/tasks/`, and `test/` in the working tree.

---

## Verified Discrepancies

### D1. S1a (`:seon.task/intent` → `:seon.task/plan` and `seon.cluster.agent/plan` → `launch-actions`) status and references
- **Section**: §1 The task (L92–93), §6 What is deleted (L512–513), §7 Slices (L528, L540, L584)
- **Exact claims**:
  - §1 (L92–93): `"File admission accepts a task without a plan (today's non-parent :intent requirement goes)."`
  - §6 (L512–513): `"the fork-point relaunch clauses in plan and fork-attempt!"`
  - §7 (L528): `"S1a lands first: today's reference derivation (seon.task/with-references) reads intent, and S1b and S1c are written against plan."`
  - §7 (L540, L584): `"| S1a | The intent → plan rename: inventory and script, then one atomic conversion commit over task files, src, tests, schema, CLAUDE.md, vocabulary, fixtures and namespaced maps | ... | — |"` and `"Status (2026-10-14): ready for implementation; nothing of S1–S5 implemented."`
- **What the code actually shows**:
  - Commit `e6df7e213` ("db<->flow's decision renamed so "plan" names only the task field (S1a, commit 1 of 2)") already renamed `seon.cluster.agent/plan` to `launch-actions` (`src/seon/cluster/agent.clj:1865-1925`), making the citation of `"clauses in plan"` in §6 stale.
  - S1a part 2 of 2 (`:seon.task/intent` → `:seon.task/plan`) is already applied across the working tree:
    - `resources/seon/schemas/seon.task.edn:10` defines `:plan` (replacing `:intent`), and line 173 lists `:no-plan` (replacing `:no-intent`).
    - `seon.task/file-declarations` (`src/seon/task.clj:155`) checks `(:seon.task/plan entry)` and throws `:no-plan`.
    - `seon.task/with-references` (`src/seon/task.clj:277`) already reads `{:seon.task/keys [plan instructions]}`, not `intent`.
    - All 101 task files in `resources/seon/tasks/*.edn` and tests now use `:seon.task/plan`.
  - Claiming `"nothing of S1–S5 implemented"` and listing S1a status as `"—"` is stale.
- **Severity**: `stale`

### D2. S1d-1 (`my.edit/text` and `seon.edit/exact` calling it) is already installed
- **Section**: §3a Agent verbs (L293–296), §6 What is deleted (L517–518), §7 Slices (L545, L584)
- **Exact claims**:
  - §3a (L294–296): `"It is a new pure function over strings, and seon.edit/exact (which my.edit/exact! applies to files, with a path and a digest) calls it for its replacement, so there is one editor."`
  - §6 (L517–518): `"the string replacement inside seon.edit/exact (it calls my.edit/text's core)"`
  - §7 (L545, L584): `"| S1d-1 | my.edit/text and seon.edit/exact calling it | zero, one and several matches; exact! file behavior unchanged | Sonnet | — |"` and `"nothing of S1–S5 implemented."`
- **What the code actually shows**:
  - Commit `776a04249` ("Add my.edit/text and make seon.edit/exact call seon.edit/text") already installed `seon.edit/text` (`src/seon/edit.clj:388-398`), `my.edit/text` (`src/my/edit.clj:89-106`), and updated `seon.edit/exact` (`src/seon/edit.clj:427`) to delegate to `text`:
    ```clojure
    ;; src/seon/edit.clj:427
    answer (if all? positions (text source old-string new-string))
    ```
  - The string replacement inside `seon.edit/exact` was already replaced.
- **Severity**: `stale`

### D3. S1d-3 (`resources/seon/help.md`, `(help "anchor")`, section content) discrepancy
- **Section**: §3a Agent verbs (L298–304), §6 What is deleted (L518–519), §7 Slices (L547, L584)
- **Exact claims**:
  - §3a (L298–304): `"Its text moves out of code (seon.bootstrap/help-value) into resources/seon/help.md, read by seon.ref/document and resolved by seon.ref/references. (help) answers its sections with their resolved references; (help "change-a-member") one section by its heading anchor. Cached by the file's content digest; its tokens measured with the block's. A deftest proves every reference in it resolves (seon.bootstrap-test/every-reference-in-help-resolves). Its first sections teach: change a member ((my.task/update! task k f & args)); change text ((my.edit/text s old new)); see what changed ((editscript.core/diff before after))."`
  - §6 (L518–519): `"the fixed help strings in seon.bootstrap/help-value"`
  - §7 (L547, L584): `"| S1d-3 | ... | (help \"change-a-member\") answers that section | Sonnet | — |"` and `"nothing of S1–S5 implemented."`
- **What the code actually shows**:
  - Commit `f87629f9b` ("Help is a Markdown document resolved like a plan (S1d-3)") already moved help into `resources/seon/help.md`, deleted the fixed strings from `seon.bootstrap/help-value`, and added `seon.bootstrap-test/every-reference-in-help-resolves` (`test/seon/bootstrap_test.clj:10-16`).
  - However, `resources/seon/help.md` (lines 1–60) contains no `"change-a-member"` section or sections teaching `my.task/update!`, `my.edit/text`, or `editscript.core/diff`. It still teaches `my.task/refine!` (line 35). Calling `(help "change-a-member")` returns a `:seon.ref/section-error`.
- **Severity**: `stale` (for implementation status / deleted strings) / `wrong` (for `(help "change-a-member")` and the content of first sections in `help.md`).

### D4. Task-file string `:seon.task/name` count is 872 (spec says 869)
- **Section**: §1 The task (L54)
- **Exact claim**: `"task files and runtime maps both use string names: 869 measured"`
- **What the code actually shows**: Across all 101 `.edn` files in `resources/seon/tasks/`, there are 872 task entries, and all 872 carry string `:seon.task/name` values (3 tasks added since measurement).
- **Severity**: `stale`

### D5. Present-tense description of `seon.cluster.agent/db<->flow` reading chain facts
- **Section**: §2.2 How a chain runs (L146–148)
- **Exact claim**: `"One loop: root's listener seon.cluster.agent/db<->flow, each pass, reads every chain fact on main and each chain's branch head with seon.db/branch-database (a store read needing no open connection, as seon.cluster.agent/branch-end does today), released after use (seon.cluster.source/release!)."`
- **What the code actually shows**: While `seon.cluster.agent/branch-end` (`src/seon/cluster/agent.clj:1976-1988`), `seon.db/branch-database`, and `seon.cluster.source/release!` exist today, `seon.cluster.agent/db<->flow` (`src/seon/cluster/agent.clj:2343-2345`) and `run-pass!` (`src/seon/cluster/agent.clj:2304-2327`) do not read any `:seon.chain/*` facts today. The sentence combines target chain mechanics in unqualified present tense with an explicit comparison to what `branch-end does today`.
- **Severity**: `ambiguous`

### D6. Ambiguous claim that `proved-unanswered?` and `step-proved?` read `proofs` field
- **Section**: §2.3 How a task starts (L170–171)
- **Exact claim**: `"(seon.turn/proved-unanswered? and seon.turn/step-proved? stay, reading the task's proofs field: a chain agent starts with no wake, so they are its path to a first turn)"`
- **What the code actually shows**: In `src/seon/turn.clj`, `step-proved?` (`L5267-5277`) calls `step-tests` (`L5229-5240`) → `task-obligations` (`L5212-5220`), which checks `:seon.task/step` and `:seon.task/product` and unions `:seon/tasks` metadata tags rather than reading the `:seon.task/proofs` field alone. The phrasing "stay, reading the task's proofs field" is ambiguous between describing present behavior versus specifying its target contract in S2c.
- **Severity**: `ambiguous`

### D7. Present-tense references to non-existent `my.root/run!`
- **Section**: §2.2 Run a task (L163–164), §2.7 Bail or fail (L232–234), §5 Scheduling (L478–479)
- **Exact claims**:
  - §2.2 (L163–164): `"a chain to inspect first is created :held and run with my.root/run!."`
  - §2.7 (L232–234): `"change the held task's plan or proofs and run it again (my.root/run!: one branch transaction that changes :held to :running, then a payload-free wake offered to root's existing plan channel...)"`
  - §5 (L478–479): `"my.root/run! offers its own wake, §2.7"`
- **What the code actually shows**: `my.root/run!` does not exist in `src/my/root.clj` (which defines only `instructions`, `merge-requests`, `merge!`, `close!`, and `ended`). It is target design for slice S3c-1.
- **Severity**: `ambiguous`

### D8. Present-tense description of `my.root/ended` deriving from branch attempts
- **Section**: §2.8 Restart (L246–247)
- **Exact claim**: `"Root's tools (my.root/merge-requests, my.root/ended) enumerate chain attempts on their branches; my.root/ended derives ends from them, not from end messages."`
- **What the code actually shows**: In `src/my/root.clj:137-154`, `my.root/ended` still derives ends from end messages in the agent inbox:
  ```clojure
  (let [items (message/inbox database agent-id)]
    ...
    (let [ends (mapv #(context/ended database %) (filter :seon.task/end items))] ...))
  ```
  It does not enumerate chain attempts on branches today.
- **Severity**: `ambiguous`

### D9. Ambiguous description of `seon.ref/render-doc-ai` and `seon.render.schema/description`
- **Section**: §3a What an agent can do from its prompt (L332–333)
- **Exact claim**: `"seon.render.schema/description becomes public; seon.ref/render-doc-ai keeps a section's token size and the headings below it."`
- **What the code actually shows**: `seon.render.schema/description` is currently `defn-` (private, `src/seon/render/schema.clj:94`). In `src/seon/ref.clj:657`, `resolve-path` explicitly strips both `:seon.ref/below` and `:seon.ref/tokens` via `(dissoc :seon.ref/text :seon.ref/below :seon.ref/tokens)` before `render-doc-ai` runs (`src/seon/ref.clj:662-670`). Juxtaposing `"becomes public"` with present-tense `"keeps a section's token size and the headings below it"` makes it ambiguous whether the latter is installed today.
- **Severity**: `ambiguous`

### D10. Present-tense description of `seon.test/task` return value and tag resolution
- **Section**: §4 Templates and their proofs (L353–357)
- **Exact claim**: `"seon.test/task is an optional reader: without a supplied task it answers nil and never resolves tags; a request naming a nonexistent task reports missing input before execution. The supplied value is {:seon.test/task <the task entity> :seon.test/branch <the requesting branch's database, captured after the earlier tests of the request recorded>}"`
- **What the code actually shows**: In `src/seon/test.clj:134-141`, `seon.test/task` returns `*task*`, which is populated by `seon.test.runner/task-inputs` (`src/seon/test/runner.clj:1343-1366`). When no task is explicitly supplied in the request, `task-inputs` resolves `:seon/tasks` tags via `single-tags`, returning a `:seon.test/task-input` map with keys `[:seon.task/sym :seon.task/obligations :seon.task/effective]` (`resources/seon/schemas/seon.test.edn:319-322`). It does not answer nil when a task is omitted, does not refuse tags, and does not return the `{:seon.test/task ... :seon.test/branch ...}` map described.
- **Severity**: `ambiguous` (target S2a behavior stated in the present tense).

### D11. Present-tense claim that `cluster-claims` evaluates a `[_ :seon.chain/branch ?b]` clause
- **Section**: §1 The chain fact on main (L100–103)
- **Exact claim**: `"and counted by seon.cluster.registry/cluster-claims (a [_ :seon.chain/branch ?b] clause evaluated on main, since cluster-claims answers only for a cluster branch, holding the branch and its base/ ref) so boot's seon.cluster.registry/retire-unclaimed! keeps its branch, failed or held chains included"`
- **What the code actually shows**: `cluster-claims` (`src/seon/cluster/registry.clj:530-550`) contains no clause for `:seon.chain/branch`:
  ```clojure
  (into #{branch}
        (d/q '[:find [?branch ...]
               :where [?agent :seon.agent/branch ?branch]
               (not [?agent :seon.agent/admission-closed-tx])]
             db))
  ```
  This clause is target design for slice S1b-2, but is phrased in the present tense.
- **Severity**: `ambiguous`

---

## Verified Claims That Hold (by Section)

### Header & Owner rulings (L1–48)
- All 16 research and review documents cited in L16–20 exist in `docs/research/agent-platform/2026-10-14/`.
- All 5 prerequisite commits cited in L178, L190, L340, L521, and L582 (`4bb413774`, `de1f48c23`, `35cafc1dc`, `e748f08c2`, `5a08c13af`) exist in the repository.

### §1 Data (L46–117)
- Task identity derivation: `seon.task/task-handle` and `seon.task/task-sym` exist (`src/seon/task.clj:49,65`).
- Backlog `needs`: exactly 17 task entries in `resources/seon/tasks/*.edn` carry `:seon.task/needs`, with 19 total links.
- Attempt outcome: `seon.merge/merge-tx` maps an end of `:done` to outcome `:merged` (`src/seon/merge.clj:200`).
- Completion readers: `seon.task/ready-rules` (`leaf-done`, `src/seon/task.clj:1076`) and `seon.context` (`src/seon/context.clj:215-228`) treat `:merged` as complete.
- Attempt counter: `:seon.task/attempts-made` tracks attempt counts on tasks (`src/seon/task.clj:845`, `src/seon/cluster/agent.clj:1685`).
- File admission non-parent plan requirement: `seon.task/file-declarations` (`src/seon/task.clj:155`) requires non-parent tasks to declare a plan.
- Task writer: `adopt-tasks-tx` and `held-tx` strip `:db/id` and force `:held` (`src/seon/task.clj:327,409`).
- Name validation: `seon.task/create!` performs a separate `symbol-name?` check before writing (`src/seon/task.clj:701`).

### §2 Flow (L118–248)
- Store branch read: `seon.cluster.agent/branch-end` (`src/seon/cluster/agent.clj:1976-1988`) reads branch heads with `seon.db/branch-database` and releases them via `seon.cluster.source/release!`.
- Snapshot window configuration: `:seon.config.db/snapshot-window-ms` exists in schema and config (`resources/seon/schemas/seon.config.db.edn:3`).
- Context acquisition helpers: `seon.cluster.registry/active-branch-connection` (`src/seon/cluster/registry.clj:189`) and `seon.cluster.agent/acquire-context!` (`src/seon/cluster/agent.clj:1076`) exist.
- Configuration dials: `:seon.config.ai/model` (`resources/seon/schemas/seon.problems.edn:71`) and `:seon.config.agent/max-running` (`resources/seon/schemas/seon.config.agent.edn:18`, `src/seon/cluster/agent.clj:1672,2323`) exist.
- Task execution check: `seon.turn/attempt-running?` (`src/seon/turn.clj:2224-2230`) checks main's head via `task/main-head`.
- Selection refusal: `seon.test/changes` (`src/seon/test.clj:2181-2217`) refuses empty selections via `:seon.test/policy-unscoped`, while its underlying `changed-identities` derivation returns an empty vector.
- Interrupted test world fix: commit `4bb413774` fixed `seon.test.runner/record-interrupted!` so broken test worlds fail the request rather than recording designed reds.
- Contract gaps rule: `seon.cluster.source/contract-gaps` (`src/seon/cluster/source.clj:1102`) checks missing specs, `:any`, `:some`, and respects `value-definition?` (from `de1f48c23`).
- Done proposals and verdicts: `seon.turn/task-end` (`src/seon/turn.clj:2145`) and `seon.cluster.source/results-verdict` (`src/seon/cluster/source.clj:1135`) exist.
- Lifecycle re-derivation: `seon.cluster.agent/run-pass!` (`src/seon/cluster/agent.clj:2315-2320`) re-derives lifecycle facts after `:stop` and `:merge`.
- Merge pipeline: `seon.merge/merge!`, `seon.cluster.source/prepare-merge!`, `accept-merge!`, `assignment-row`, `assigned-task`, and `tested-task-basis` exist.
- Publication and branch retirement: `seon.merge/publish!` (`src/seon/merge.clj:396`), `:seon.merge/waiting` (`src/seon/merge.clj:430`), and `seon.cluster.registry/retire-branch!` (`src/seon/cluster/registry.clj:495`) exist.
- End conditions: `seon.turn/turns-left` (`src/seon/turn.clj:1978`) and `my.task/send-back!` (`src/my/task.clj:103`) exist.
- Recovery functions: `seon.cluster/recover-runs!` (`src/seon/cluster.clj:2999`) and `seon.turn/recover-call` (`src/seon/turn.clj:1403`) exist.
- Root inspection: `my.root/merge-requests` (`src/my/root.clj:61`) and `my.root/ended` (`src/my/root.clj:137`) exist.

### §3 The plan and its references & §3a Agent verbs (L249–346)
- Markdown parsing and reference resolution: `seon.ref/document` (`src/seon/ref.clj:100`) and `seon.ref/references` (`src/seon/ref.clj:512`) exist.
- Diff dependency: `[editscript/editscript "0.6.4"]` is declared in `deps.edn:24`.
- Schema description: `seon.render.schema/description` exists as a private function (`src/seon/render/schema.clj:94`).
- Must-read render caching: commit `35cafc1dc` installed `:seon.render/invocation` caching in `seon.cache`, routing must-reads through `seon.render/render-call`.
- Context rebuilding: `seon.context/exchanges` walks evaluations (`src/seon/context.clj:50`) and `seon.context/model-context` rebuilds the block (`src/seon/context.clj:675`).
- Errors cut plan document: `docs/research/agent-platform/2026-10-14/errors-cut-plan.md` exists.
- Timeout render task: `seon.render/a-timeout-or-load-dependent-render-outcome-is-never-reused-by-read-evidence` exists in `resources/seon/tasks/seon.render.edn:24`.

### §4 Templates and their proofs (L347–475)
- Test runner and reach helpers: `seon.test.runner/designed-red?` (`src/seon/test/runner.clj:2561`) and `seon.fn/tests-reaching` (`src/seon/fn.clj:2132`) exist.
- Legacy templates and test: `resources/seon/tasks/seon.step.edn` and `test/seon/step_test.clj` exist.

### §5 Scheduling (L476–484)
- Wake router: `seon.cluster.wake/route!` exists (`src/seon/cluster/wake.clj:453`).
- Cache and obligations check: `seon.cluster.agent/basis-valid?` (`src/seon/cluster/agent.clj:1706`) uses commit-keyed cache and `:prove` successor check with `task/obligations`.

### §6 What is deleted (L485–525)
- Task tree entities: `effective`, `goal-owner`, `chain!`, `chain-copy`, `children`, `needs`, `step`, and `product` exist in `src/seon/task.clj` and schemas.
- Proof selection functions: `obligations`, `assigned-obligations`, `single-tags`, `task-obligations`, `task-proofs`, `step-tests`, `task-done?`, and `step-status` exist.
- Metadata tags: exactly 344 lines in `test/` contain `:seon/tasks` metadata tags, read by `task-links`, `task-edges`, `open-tasks`, `task-findings`, `candidate-dangling`, and `render-identity-html`.
- Annotation and tag schemas: `:seon.task/annotation` and `:seon.task/tasks` members exist in fn, test, and ns schemas.
- Lifecycle relaunch items: `assignment-actions`, `allowance-left`, `work-left?`, `seed-attempt!`, and `relaunched` exist in `src/seon/cluster/agent.clj` and `src/seon/task.clj`.
- Test harness constant: `worked-results` exists in `test/seon/step_test.clj:72`.
- Markdown dependency: `io.github.nextjournal/markdown` is declared in `deps.edn:16`.

### §7 Slices (L526–586)
- Settled baseline commits: `de1f48c23` (REPL value defs), `35cafc1dc` (render invocation cache), and `5a08c13af` (errors-cut E1) are verified in git history.

### §8 Open questions (L587–590)
- Section states "None open." and is consistent.

---

## Summary
1. Audited `docs/seon/schedule/cards/TASK-CHAIN.md` (590 lines, §§1–8) against `src/`, `resources/seon/schemas/`, `resources/seon/tasks/`, and `test/` in the working tree.
2. Verified 11 discrepancies: 4 `stale` (S1a applied in working tree and `e6df7e213` committed; S1d-1 committed in `776a04249`; string name count is 872 vs 869; §7 status table claims nothing implemented), 1 `stale` / `wrong` (S1d-3 help move committed in `f87629f9b` but `(help "change-a-member")` and new first sections are absent), and 6 `ambiguous` (present-tense descriptions of target S1b-2 `cluster-claims`, S3a `db<->flow`, S2c `step-proved?`, S3c-1 `my.root/run!`, S3a `my.root/ended`, and S2a `seon.test/task`).
3. Verified 33 installed-state claims that hold across all 8 sections, covering prerequisite commits, research notes, database and schema attributes, merge/runner/turn functions, and deletion targets.
4. Total task files: 101; task entries: 872; entries with `:seon.task/needs`: 17 (19 links); lines with `:seon/tasks` tags in `test/`: 344.
5. Read-only audit completed; zero source files modified.

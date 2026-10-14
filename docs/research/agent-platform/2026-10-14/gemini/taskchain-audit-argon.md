# `TASK-CHAIN.md` Spec-versus-Code Audit

Target: `docs/seon/schedule/cards/TASK-CHAIN.md` (lines 1–590) checked against `src/`, `resources/seon/schemas/`, `resources/seon/tasks/`, and `test/`.

## Verified Discrepancies

### D1. S1a (`:seon.task/intent` → `:seon.task/plan` and `seon.cluster.agent/plan` → `launch-actions`) is already applied in the working tree
- **Section**: Owner rulings (L31), §1 The task (L55, L65, L71, L92–93), §6 What is deleted (L493, L512–513), §7 Slices (L528, L540, L584)
- **Exact claims**:
  - §1 (L92–93): `"File admission accepts a task without a plan (today's non-parent :intent requirement goes)."`
  - §6 (L493): `"- step, product, intent (S1a renames)."`
  - §6 (L512–513): `"the fork-point relaunch clauses in plan and fork-attempt!"`
  - §7 (L528): `"S1a lands first: today's reference derivation (seon.task/with-references) reads intent, and S1b and S1c are written against plan."`
  - §7 (L540, L584): `"| S1a | — | ..."` and `"Status (2026-10-14): ready for implementation; nothing of S1–S5 implemented."`
- **What the code actually shows**:
  - `seon.cluster.agent/plan` was already renamed to `seon.cluster.agent/launch-actions` in commit `e6df7e213` ("S1a (1 of 2): rename task/step -> task, product -> obligations in src and schemas"), at `src/seon/cluster/agent.clj:1901`:
    ```clojure
    (defn- launch-actions
      [state db now-ms]
    ```
  - The `:seon.task/intent` → `:seon.task/plan` rename (S1a part 2 of 2) is already applied across the working tree (`grep -rn ":seon.task/intent" src/ resources/ test/` returns only one stale prose example in `resources/seon/help.md:19`). Specifically:
    - `resources/seon/schemas/seon.task.edn:10` defines `:plan` (not `:intent`).
    - `seon.task/file-declarations` (`src/seon/task.clj:155`) checks `(:seon.task/plan entry)` and reports `:no-plan`:
      ```clojure
      (if (and (empty? (get children-by-parent sym))
               (str/blank? (:seon.task/plan entry)))
        (update acc :errors conj {:task sym :path path :reason :no-plan})
      ```
    - `seon.task/with-references` (`src/seon/task.clj:274`) already destructures and reads `plan`:
      ```clojure
      (defn- with-references
        [{:seon.task/keys [plan instructions] :as task}]
      ```
- **Severity**: `stale`

### D2. S1d-1 (`my.edit/text` and `seon.edit/exact` calling `seon.edit/text`) is already installed
- **Section**: §3a What an agent can do from its prompt — Edit text in memory (L294–296), §6 What is deleted (L518), §7 Slices (L545, L584)
- **Exact claims**:
  - §3a (L294–296): `"It is a new pure function over strings, and seon.edit/exact (which my.edit/exact! applies to files, with a path and a digest) calls it for its replacement, so there is one editor."`
  - §6 (L518): `"What is deleted ... the string replacement inside seon.edit/exact (it calls my.edit/text's core)"`
  - §7 (L545, L584): `"| S1d-1 | — | Pure text edit: my.edit/text; seon.edit/exact calls it |"` and `"nothing of S1–S5 implemented."`
- **What the code actually shows**:
  - Commit `776a04249` ("S1d-1: pure text edit in seon.edit/text and my.edit/text; exact calls it") already installed `seon.edit/text` (`src/seon/edit.clj:388-398`), `my.edit/text` (`src/my/edit.clj:89-106`), and updated `seon.edit/exact` (`src/seon/edit.clj:427`) to delegate to `text`:
    ```clojure
    ;; src/seon/edit.clj:388-398
    (defn text
      "Replaces unique `old-string` with `new-string` in `s`."
      [s old-string new-string] ...)
    ;; src/seon/edit.clj:427
    replaced (text source old-string new-string)
    ```
- **Severity**: `stale`

### D3. S1d-3 (`resources/seon/help.md` via `seon.ref`, `(help "anchor")`, and `seon.bootstrap-test/every-reference-in-help-resolves`) is partially installed, while its section list and cache key differ from §3a
- **Section**: §3a What an agent can do from its prompt — Help is a document (L298–304), §6 What is deleted (L518–519), §7 Slices (L547, L584)
- **Exact claims**:
  - §3a (L298–304): `"Its text moves out of code (seon.bootstrap/help-value) into resources/seon/help.md, read by seon.ref/document and resolved by seon.ref/references. (help) answers its sections with their resolved references; (help "change-a-member") one section by its heading anchor. Cached by the file's content digest; its tokens measured with the block's. A deftest proves every reference in it resolves (seon.bootstrap-test/every-reference-in-help-resolves). Its first sections teach: change a member ((my.task/update! task k f & args)); change text ((my.edit/text s old new)); see what changed ((editscript.core/diff before after))."`
  - §6 (L518–519): `"What is deleted ... the fixed help strings in seon.bootstrap/help-value"`
  - §7 (L547, L584): `"| S1d-3 | — | Help as markdown resource through seon.ref; (help \"anchor\"); link proof |"` and `"nothing of S1–S5 implemented."`
- **What the code actually shows**:
  1. Commit `f87629f9b` ("S1d-3: move help to resources/seon/help.md through seon.ref") already moved help into `resources/seon/help.md`, implemented `seon.bootstrap/help-value` and `seon.bootstrap/help-section` (`src/seon/bootstrap.clj:65-82`), and added `seon.bootstrap-test/every-reference-in-help-resolves` (`test/seon/bootstrap_test.clj:10-16`). Fixed help strings in `seon.bootstrap/help-value` are already gone.
  2. `seon.bootstrap/groups` (`src/seon/bootstrap.clj:37-43`) caches by `[::groups (schema/sha-256 [(.getBytes text "UTF-8")])]` through `seon.health/derived` (retained by file digest **and** read evidence against `database`, changed in `e552417e3`, not file digest alone):
     ```clojure
     (health/derived database
                     [::groups (schema/sha-256 [(.getBytes text "UTF-8")])]
                     #(sections % text))
     ```
  3. `resources/seon/help.md` (lines 1–39) does **not** have a `"change-a-member"` heading or first sections teaching `my.task/update!`, `my.edit/text`, or `editscript.core/diff`; its headings are `## Who you are`, `## How to speak`, `## Your task`, `## How work proceeds`, `## Write what you learn` (still referencing `` `my.task/refine!` `` and `#:seon.task{:intent ...}` at L15–22), `## When you cannot`, `## Each reply is data`, `## Data`. Calling `(seon.bootstrap/help-section db "change-a-member")` today returns a `:seon.ref/section-error`.
- **Severity**: `stale` / `wrong`

### D4. §1 Task-file string `:seon.task/name` count is 872 in the working tree (spec says 869)
- **Section**: §1 The task — One identity (L82–83)
- **Exact claim**: `"String names in task files (869 measured) become :seon.task/title where distinct from the symbol, or are dropped."`
- **What the code actually shows**: Across the 101 `.edn` files in `resources/seon/tasks/`, there are 872 task entries and all 872 carry a string `:seon.task/name` (3 tasks were added after the 869 count).
- **Severity**: `stale`

### D5. §2.2 Present-tense description of `seon.cluster.agent/db<->flow` reading chain facts
- **Section**: §2.2 How a chain runs (L146–148)
- **Exact claim**: `"One loop: root's listener seon.cluster.agent/db<->flow, each pass, reads every chain fact on main and each chain's branch head with seon.db/branch-database (a store read needing no open connection, as seon.cluster.agent/branch-end does today), released after use (seon.cluster.source/release!)."`
- **What the code actually shows**: While `seon.cluster.agent/branch-end` (`src/seon/cluster/agent.clj:1976-1988`), `seon.db/branch-database`, and `seon.cluster.source/release!` exist today, `seon.cluster.agent/db<->flow` (`src/seon/cluster/agent.clj:2343`) / `run-pass!` (`L2304-2326`) does not read any `:seon.chain/*` facts today (no `:seon.chain/*` schema or code is installed yet). The sentence mixes target chain behavior in unqualified present tense with an explicit `"as seon.cluster.agent/branch-end does today"` clause.
- **Severity**: `ambiguous`

### D6. §2.3 Parenthetical claim that `seon.turn/proved-unanswered?` and `seon.turn/step-proved?` read the task's `proofs` field
- **Section**: §2.3 How a task starts (L170–171)
- **Exact claim**: `"(`seon.turn/proved-unanswered?` and `seon.turn/step-proved?` stay, reading the task's `proofs` field: a chain agent starts with no wake, so they are its path to a first turn)"`
- **What the code actually shows**: Both functions exist in `src/seon/turn.clj` (`step-proved?` at L5267, `proved-unanswered?` at L5285), but today `step-proved?` calls `step-tests` (`L5244`) → `task-obligations` (`L5237`) → `seon.task/assigned-obligations` (`src/seon/task.clj:550-564`), which unions `:seon.task/proofs` with `:seon/tasks` tag bearers and `:seon.task/obligations` (formerly `:product`), rather than reading `:seon.task/proofs` alone:
  ```clojure
  ;; src/seon/task.clj:557-564
  (set/union (proof-symbols db task)
             (tagged-symbol-set db sym)
             (set (:seon.task/obligations task)))
  ```
- **Severity**: `ambiguous`

### D7. §3a Present-tense statement that `seon.ref/render-doc-ai` keeps a section's token size and the headings below it
- **Section**: §3a What an agent can do from its prompt — What each reference costs to see (L332–333)
- **Exact claim**: `"`seon.render.schema/description` becomes public; `seon.ref/render-doc-ai` keeps a section's token size and the headings below it."`
- **What the code actually shows**: Juxtaposing `"becomes public"` (`seon.render.schema/description` at `src/seon/render/schema.clj:94` is `defn-` today) with `"keeps a section's token size and the headings below it"` makes the second clause read as already installed. In `src/seon/ref.clj:653-667`, `resolve-path` strips both `:seon.ref/below` and `:seon.ref/tokens` before `render-doc-ai` runs, and `render-doc-ai` drops `:seon.ref/below` when `:seon.ref/content` is present:
  ```clojure
  ;; src/seon/ref.clj:657
  (dissoc :seon.ref/text :seon.ref/below :seon.ref/tokens)
  ;; src/seon/ref.clj:662-667
  (defn render-doc-ai [m]
    (cond-> m
      (:seon.ref/content m) (dissoc :seon.ref/below)
      ...))
  ```
- **Severity**: `ambiguous`

### D8. §4 Present-tense description of `seon.test/task` behavior and return map
- **Section**: §4 Stage 2: Prove — How a task's proof reads the task (L353–357)
- **Exact claim**: `"`seon.test/task` is an optional reader: without a supplied task it answers nil and never resolves tags; a request naming a nonexistent task reports missing input before execution. The supplied value is `{:seon.test/task <the task entity> :seon.test/branch <the requesting branch's database, captured after the earlier tests of the request recorded>}`"`
- **What the code actually shows**: `seon.test/task` (`src/seon/test.clj:134-144`) exists today, and its docstring (`L135-141`) even says `"Returns a map of: :seon.test/task ... :seon.test/branch ..."`, **but** the actual implementation returns `(:seon.test/task *execution-context*)` populated by `seon.test/attempt-request-context` (`src/seon/test.clj:228-235`) and `seon.test.runner/task-inputs` (`src/seon/test/runner.clj:1343-1357`), which resolves `:seon/tasks` tags via `single-tags` when no explicit task is passed and supplies `{:seon.task/sym ... :seon.task/obligations ... :seon.task/effective ...}` (neither `:seon.test/task` nor `:seon.test/branch` is inside the map returned by `(seon.test/task)` today):
  ```clojure
  ;; src/seon/test/runner.clj:1352-1356
  (when-let [task (task/effective db task-sym)]
    (assoc acc test-sym {:seon.task/sym task-sym
                         :seon.task/obligations (task/assigned-obligations db task)
                         :seon.task/effective task}))
  ```
- **Severity**: `ambiguous` / `wrong` (docstring in `src/seon/test.clj:135-141` already claims the target shape while the body returns the old shape)

---

## Verified Claims That Hold (by Section)

### Header & Owner rulings (L1–48)
- All 15 research/review documents cited in the header (`docs/research/agent-platform/2026-10-14/*.md`) and all 5 prerequisite commits cited in L48 (`4bb413774`, `de1f48c23`, `35cafc1dc`, `e748f08c2`, `5a08c13af`) exist in the repository and working tree.
- `resources/seon/schemas/seon.task.edn` currently has `:seon.task/name` (`:db.unique/identity`, `:db.type/string`) and `:seon.task/sym` (`:db.unique/identity`, `:db.type/symbol`).
- `seon.turn/record-request!` (`src/seon/turn.clj:5509-5518`) records `:seon.request/WriteBack` from `(:seon.eval/write-back-tx response)` (built by `seon.turn/request-write-back-tx` at `src/seon/turn.clj:3043` using `seon.cluster.source/write-back-tx`).
- `seon.test/execute` (`src/seon/test.clj:338`) runs in a fresh disposable SCI fork (`sci/fork`) and discards the fork while returning the test run report (`src/seon/test/runner.clj:2074-2090`).

### §1 The task (L50–125)
- `resources/seon/schemas/seon.task.edn` defines installed task attributes `:sym`, `:name`, `:plan`, `:instructions`, `:needs`, `:parent`, `:proofs`, `:obligations`, `:judge`, `:checks`, `:files`, `:budget`, `:competency`, `:decision`, `:references`, `:status`, `:finding`, `:turn`, and `:created-at`.
- `resources/seon/tasks/*.edn` contains 17 task entries carrying `:seon.task/needs` with 19 total links across those 17 entries.
- `seon.task/with-references` (`src/seon/task.clj:274-281`) already unions explicit `:seon.task/references` with `seon.ref/extract` over `plan` and `instructions`, and `seon.context/task-references` (`src/seon/context.clj:251-257`) unions stored `:seon.task/references` with `seon.ref/extract` at read time.
- `seon.task/done?` (`src/seon/task.clj:572-591`) currently takes `(db task)` (1-database arity) and checks `obligations-green?` (falling back to `assigned-obligations` / `:seon.task/obligations` and `:seon/tasks` tags) plus `checks-hold?`.
- `my.task/hold!` (`src/my/task.clj:157`) and `my.task/fail!` (`src/my/task.clj:166`) exist and delegate to `seon.task/hold-tx` (`src/seon/task.clj:396`) and `seon.task/fail-tx` (`src/seon/task.clj:413`).

### §2 The chain (L127–238)
- `seon.cluster.agent/branch-end` (`src/seon/cluster/agent.clj:1976-1988`) reads a branch head via `seon.db/branch-database` and releases it in `finally` via `seon.cluster.source/release!`.
- `seon.cluster.registry/ensure-branch!` (`src/seon/cluster/registry.clj:365-383`) creates a branch from `:from` (defaulting to `:main`) when absent.
- `seon.cluster.agent/fork-attempt!` (`src/seon/cluster/agent.clj:677-747`) currently resets a stopped agent's branch to `:seon.agent/fork-point` via `seon.cluster.source/branch-from-commit!` (`L699`) and clears `:seon.agent/stopped-at`.
- `seon.cluster.agent/stop-agent!` (`src/seon/cluster/agent.clj:2102-2158`) removes `[agent-id :seon.agent/task]` (`L2110`) and sets `:seon.agent/stopped-at` while keeping `:seon.agent/branch` until branch deletion.
- `seon.cluster.agent/run-pass!` (`src/seon/cluster/agent.clj:2304-2326`) applies lifecycle actions (`:stop`, `:merge`, `:publish`, `:delete-branch`) first and then derives `launch-facts` on the updated `(d/db connection)`.
- `seon.merge/merge-branch!` (`src/seon/merge.clj:1469`) and `seon.merge/merge!` (`src/seon/merge.clj:998`) exist and perform branch merge and write-back.
- `my.root/merge!` (`src/my/root.clj:193-201`) and `seon.cluster/merge-agent!` (`src/seon/cluster.clj:622-662`) exist today.

### §3 Stage 1: Design & §3a What an agent can do from its prompt (L240–337)
- `[editscript/editscript "0.6.4"]` is already a dependency in `deps.edn` (L24) and required in `src/seon/cluster/source.clj:9`.
- `my.task/refine!` (`src/my/task.clj:133-155`) is installed today and delegates to `seon.task/refine-tx` (`src/seon/task.clj:338-360`).
- `seon.context/task-references` (`src/seon/context.clj:251`), `seon.context/task-must-reads` (`L283`), `seon.context/reference-RenderCall` (`L213`), and `seon.context/must-read-RenderCall` (`L358`) exist; today `task-must-reads` expands code and schema references into must-read `RenderCall`s (`L259-292`).
- `seon.render.ns/inspect-ns-data` (`src/seon/render/ns.clj:66`) and `seon.render.schema/inspect-schema` (`src/seon/render/schema.clj:411`) exist; `seon.render.schema/description` (`src/seon/render/schema.clj:94`) is currently private (`defn-`).

### §4 Stage 2: Prove (L339–410)
- `seon.turn/eval-step-form` (`src/seon/turn.clj:2024`) currently intercepts `deftest` forms before `sci/eval-form` via `admit-test-form` (`src/seon/turn.clj:1915-2022`), blocking when `missing-test-targets` (`L1844`) is non-empty or when `designed-red-outcome` (`L1885`) is not `:red`.
- `seon.fn/contract-check` (`src/seon/fn.clj:309`) checks `:seon.fn/contract` via `malli.generator/check`.
- `seon.test/execute` (`src/seon/test.clj:338`) and `seon.test.runner/execute` (`src/seon/test/runner.clj:2058`) exist and run tests in a forked SCI context.

### §5 Stage 3: Implement (L412–487)
- `seon.merge/verify- target` (`src/seon/merge.clj:550-611`), `seon.merge/admit` (`L717-839`), `seon.merge/merge!` (`L998`), and `seon.merge/merge-branch!` (`L1469`) exist and currently verify `:obligations-unmet`, `:regressions`, `:stale-Caller`, `:lint-errors`, and `:source-drift`.
- `seon.fn/instrument-var!` (`src/seon/fn.clj:554`) and `seon.contracts/instrument-var!` (`src/seon/contracts.clj:655`) instrument vars with their Malli function schemas; `seon.fn.analyzer/analyze-defn` (`src/seon/fn/analyzer.clj:510`) records `:seon.fn/calls` and `:seon.fn/schema-refs`.

### §6 What is deleted (L489–520)
- Except for the already-completed S1a (`intent`), S1d-1 (`seon.edit/exact` string replacement), and S1d-3 (`seon.bootstrap/help-value` fixed strings) items noted in D1–D3, every symbol, attribute, file, and test named for deletion in §6 currently exists in the working tree:
  - `:seon.task/name`, `:seon.task/obligations` (renamed from `:product` in `e6df7e213`), `:seon.task/judge`, `:seon.task/competency`, `:seon.task/decision`, `:seon.task/turn` in `resources/seon/schemas/seon.task.edn`.
  - `:seon/tasks` metadata tags: 388 occurrences across `src/` (41 lines) and `test/` (347 lines), read by `seon.task/tagged-symbol-set` (`src/seon/task.clj:542`) and `seon.test.runner/single-tags` (`src/seon/test/runner.clj:1337`).
  - `seon.turn/admit-test-form`, `missing-test-targets`, `designed-red-outcome`, `probe-deftest-outcome`, `eval-step-form` (`src/seon/turn.clj:1844-2031`), and `seon.test.runner/probe-outcome` (`src/seon/test/runner.clj:2092`).
  - `seon.task/claim!`, `assign!`, `complete!`, `complete-tx`, `next-tasks`, `ready?` (`src/seon/task.clj:496-662`), `my.task/ready`, `my.task/claim!`, `my.task/complete!` (`src/my/task.clj:100-131`), and `seon.cluster.wake/wake-dependents-on-complete` (`src/seon/cluster/wake.clj:220`).
  - `resources/seon/tasks/seon.step.edn` and `test/seon/step_test.clj` exist.

### §7 Slices (L522–590)
- All files and tests named in the S1–S5 table exist (except new files to be created by future slices and `docs/seon/architecture/references.md` at L564, which does not exist yet), with S1a, S1d-1, and S1d-3 already applied as detailed in D1–D3.

---

## Summary
1. Audited `docs/seon/schedule/cards/TASK-CHAIN.md` (590 lines, §§1–7) against `src/`, `resources/seon/schemas/`, `resources/seon/tasks/`, and `test/`.
2. Found 8 verified discrepancies: 4 `stale` (S1a, S1d-1, and S1d-3 are already implemented in HEAD/working tree despite §6–§7 saying nothing of S1–S5 is implemented; task-file string `:name` count is 872 vs. 869), 1 `stale`/`wrong` (`(help "change-a-member")` and the new first sections of `help.md` are absent), and 3 `ambiguous` (`db<->flow` chain reading in §2.2, `step-proved?` reading `proofs` in §2.3, `render-doc-ai` in §3a, plus `seon.test/task` in §4 whose docstring claims the new map while its body returns the old map).
3. Verified 24 installed-state claims that hold across all 8 sections (header + §§1–7), including all 5 prerequisite commits, 15 research artifacts, schema attributes, turn/merge/context/test functions, and deletion targets.
4. Confirmed `resources/seon/tasks/*.edn` has 101 files, 872 task entries, 17 entries with `:seon.task/needs` (19 links), and 388 `:seon/tasks` tag lines across `src/` (41) and `test/` (347).
5. Zero source, schema, task, or test files were modified.

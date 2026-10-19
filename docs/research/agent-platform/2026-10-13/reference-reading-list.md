---
title: "Reference reading list for Seon's task chain: references, proofs, task data, chains"
type: research
status: research, read-only; no code, schema or doc changed; no SCI run on default
date: 2026-10-13
basis: "HEAD refactor/agent-platform at 2b2d71818 plus working tree; files read with sed and rg; git log and git show for commit facts"
---

# Reference reading list

Markers: **[V]** = I read this text in the file or commit named. **[Q]** = quoted from a research note in this directory, not re-read by me. **[I]** = my inference from [V] text.

Paths are checkout-relative. Symbols are cited by fully qualified name; line numbers appear only as dated evidence.

---

## 1. References in text

### 1a. Ranked sources

1. **`src/seon/ref.clj`**, the whole namespace (about 470 lines). It is the one parser and one resolver. Read `parsed`, `span-candidate`, `link-candidate`, `candidate`, `resolve-name`, `resolve-path`, `inner`, `brief`, `cite-once`, `references`, `names`, `section`. It establishes everything in §1b. Its docstring already states the rule: [V] "a code span is a candidate name or path, a link is a path."
2. **`resources/seon/schemas/seon.ref.edn`**. The contracts for every ref shape: `:candidate`, `:problem`, `:resolved-name`, `:resolved-doc`, `:references`, `:section`. Read `:resolved-name` and `:resolved-doc`, because they state what each kind returns and what it never returns.
3. **`src/seon/bootstrap.clj`**, `help-value`, the `"Your task"` and `"Write what you learn"` groups. This is the text an agent is shown. [V] "A reference is a backticked fully qualified symbol or keyword, a task `ns/task-name`, or a path with a #heading anchor; your next block resolves each one." It also says [V] "`:seon.context/must-reads` are what the task cites in backticks, each resolved once".
4. **`CLAUDE.md`**, sections "Documentation is data" and "The right context is the product". The laws. [V] "A backticked span reading as one fully qualified symbol or keyword is a reference; a task's is `ns/task-name`, its identity derived from namespace and name."
5. **`docs/seon/architecture/vocabulary.md`**, row `references`. The agreed word and its retired words (must-read, evidence, reading list). [V] it names `seon.ref/names` as the one reference parser.
6. **`resources/seon/schemas/seon.task.edn`**, `:references`. The task's derived set. [V] "never authored".
7. **`docs/research/agent-platform/2026-10-13/narrow-lifecycle-2.md`**, §Q1 and §Q10. Not a reference spec, but the only dated evidence that a reference reading is correct on current code.

Not authoritative for references: `docs/seon/schedule/cards/*` (they still cite must-read; see §5).

### 1b. What the code does [V unless marked]

- **One parse per text.** `parsed` runs commonmark over the text. Headings give anchors. Code spans and links are candidates. Fenced and indented code contribute nothing. `document` caches the parse by digest.
- **Candidate rule for a code span.** If the span's first segment (up to `/`, `#` or `:`) is a top-level checkout directory name, it is a **based path**. A trailing `:N` or `:A-B` is a **line citation** and becomes a `:line-reference` problem. Otherwise it is a **name** only when `edamame` reads it as exactly one form that is a qualified symbol or keyword. Anything else is prose and is not a reference.
- **Candidate rule for a link.** [V] a scheme is not a reference. A relative destination resolves against the document's directory. A bare `#anchor` names the document itself. `link-candidate` and `candidate` implement this.
- **Kinds and what each renders**, from `resolve-name` and `resolve-path`:
  - **Function** (a qualified symbol with a `:seon.fn/sym`): its whole `:seon.fn/source`, plus `:malli/schema` (the stored contract), and the operations `my.program/callers` and `my.program/tests-reaching`.
  - **Test** (`:seon.test/sym`): its `:seon.test/source`.
  - **Schema key** (qualified keyword): `:seon.ref/schema-form` from `lookup/schema-documentation`.
  - **Task** (`:seon.task/sym`): `:seon.task/intent` and `:seon.task/instructions`.
  - **Library symbol** (not in the program): `library-name`, a one-line `:doc`, its pin, and `(doc sym)`, `(clojure.repl/source sym)` as operations. It never loads the namespace.
  - **Document heading** (based path with optional anchor): `section` returns the heading, its pin, and the headings one level below with token sizes. `resolve-path` **drops the text**, so a referenced doc contributes its heading and the operation `(my.fs/section ...)` only.
- **Nesting is one level.** `inner` gives the names a referent's own content cites, each as a `brief` (name, one-line summary, read form). `brief` never recurses. So a reference's references are shown, and their references are not.
- **Cited once.** `cite-once` shows a name in full at its first mention. Every later mention, including inside another referent's `:seon.ref/references`, is the name alone. [V] docstring: "a name resolved at top level, or already shown below an earlier referent, is cited by its `:seon.ref/name` alone in every later mention."
- **Problem kinds.** `:unresolved`, `:anchor-missing`, `:line-reference`. Each carries one fixed message that tells the agent what to do (`problem-messages`).
- **Order.** `references` lists held names first (sorted), then held paths, then the text's candidates in first-seen order, deduplicated by identity.

### 1c. Where the rule is misstated or unfinished

- [V] `CLAUDE.md` target: "the index resolves backticked references into the subject's context and reports one resolving to nothing as a problem on that var." Partly built: `references` produces `:unresolved` problems, but I did not find a per-var problem report. Treat as target [I].
- [V] `ref.clj`, `stored-form`: the comment reads "stored as source text until PR-25 stores the arglists as data". `PR-25` names a retired ticket. Stale comment.
- [V] `resources/seon/schemas/seon.task.edn` and `src/seon/task.clj` still carry `:seon/must-read` on definitions (`task-links` in `src/seon/fn.clj`). The reference rule for definitions is therefore two-tiered: tasks use `references`, definitions still use must-read. Commit `8d14faeee` says must-read was deleted from tasks only.

---

## 2. Proofs as deftests

### 2a. Ranked sources

1. **`src/seon/task.clj`**, `obligations` (around the `ready-rules` block) and `task-edges`. The one selection of a step's proofs from one database value: [V] `(db/q '[:find [?s ...] :in $ ?t :where [?e :seon.task/tasks ?t] [?e :seon.test/sym ?s]] ...)`. Also the `product` query: tests tagged with a task whose `:seon.task/needs` names this one.
2. **`src/seon/turn.clj`**, `step-tests` (about line 5219), `run-proofs` (about 5233), `test-step` (about 5482). The automatic proof run after each eval request. [V] `test-step`'s docstring: "the held task's proofs, then the tests reaching what the run changed ... on one acquisition of the agent's world".
3. **`src/seon/fn.clj`**, `task-links` (about line 658). How a deftest's `:seon/tasks` metadata becomes `:seon.task/tasks` on the test entity. [V] `(assoc :seon.task/tasks tasks)`. A `^{...}` map placed after the test name is refused as `:metadata-after-test-name`.
4. **`test/seon/test_support.clj`**. The shared helpers. The ones a proof author needs: `assign-task!`, `declare-proof!`, `define!`, `evaluate`, `agent-value`, `fork-cluster-ctx`, `with-database`, `transacted!`, `assert-check!`. [V] `assert-check!` reports a `test.check` result as `clojure.test` events, and its failure value is the shrunk smallest input.
5. **`.agents/skills/clojure-testing/SKILL.md`**, §1 "When not to add a test" and §2 "How to write one". [V] §1.5: "The case is a new entry in an existing table test (§2.4)." This is the rule that keeps proofs few. [V] §2.4: "Use `are` when one expression states the assertion; use `doseq` over named cases ..."
6. **`src/seon/test/runner.clj`** and **`src/seon/test.clj`**, `seon.test/run` request handling. The request schema is `resources/seon/schemas/seon.test.edn`, key `:seon.test/request` (about line 314). [V] It has `:seon.test/identities` (a set of symbols), `:seon.test/changed`, `:seon.test/rerun?`, `:seon.test/since-previous-run?`. It has no task key.
7. **`src/seon/test.clj`**, reuse by reach digest. [V] `:seon.test.member/reach-digest` and the comment "Reuse is content-keyed: a completed member's stored reach digest". Read this before designing any reuse.
8. **`malli.generator`** and the `:gen/elements` convention in `CLAUDE.md`'s example for `:fn` contracts. Generators are a library seam. The narrow check found generators not total (narrow-lifecycle-2, Q6/Q7 [Q]).

### 2b. How a proof is chosen and run [V unless marked]

- **Naming.** A deftest carries `^{:seon/tasks #{'ns/task-name}}` before its name. It becomes `:seon.task/tasks` on its test entity, and `seon.task/obligations` selects it by that attribute.
- **Selection.** For an `:implement` step the proofs are its tagged tests. For a `:prove` step they are the tagged tests plus the product: [V] `:seon.task/product` (declared on the step) plus tests tagged with a task that needs it.
- **After each eval request.** [V] `step-tests` takes the proofs, adds product for a Prove step, and `run-proofs` runs them as one test request inside `:seon.config.test/step-time-limit-ms`. `run-reaching` then runs the tests reaching what the run changed. `record-broken!` records what the change broke. Test results are reused when their reach digest is unchanged [V, test.clj above; the exact rule [I]].
- **Verdict.** [V] `seon.context/step-status` judges the step from `step-observation`. [V] `seon.task/leaf-done` (in `ready-rules`) counts a task done when its merged attempt or its `:satisfied` end has its product on main.
- **Inputs to a test.** [V] a test body is called with no arguments. [V] `seon.test/request` has no `:seon.test/task`. [Q] narrow-lifecycle.md Q9 says a test member's world is built from cluster name, connection, SCI context and environment only, so a test cannot see its task unless a request key is added. I did not re-read `bounded-result`, so treat that as [Q]. A test may still build its own agent with `support/assign-task!` and `support/fork-cluster-ctx`.

### 2c. The most developed path an agent already uses [I]

The agent's own path is: tag an existing failing deftest (or add a row to an existing `are` table) with `:seon/tasks`; then the eval request runs it through `step-tests`; then `my.task/current` shows its status and failing data. That path is the one that gets exercised by live agents. Generative proofs and new deftests are secondary. The `clojure-testing` skill and `test-support` are the shared vocabulary.

---

## 3. Task data

### 3a. Ranked sources

1. **`resources/seon/schemas/seon.task.edn`** (177 lines). Every attribute and its type. Read in §3b.
2. **`src/seon/task.clj`**: `file-declarations` (about line 119), `task-delta` (about 268), `tasks-tx` (about 359), `create!` (about 630), `refuse-needs-cycle` (about 244), `effective` (about 967), `ready-rules` and `ready` (about 940 to 1024), `assign!` (about 773), `close!` (about 908).
3. **`src/my/task.clj`**: `current`, `refine!` (about line 40), `send-back!` (about line 92). These are the agent's only task verbs. [V] there is no `create!` verb here, though TASK-CHAIN names one (§5).
4. **`src/seon/cluster/source.clj`**: `task-entry-node` (about line 1587), `task-file-text`, `proven-task-file`. The write-back of task files. [V] `task-entry-node` orders members by a fixed list `[:seon.task/name :seon.task/step :seon.task/intent :seon.task/instructions :seon.task/children :seon.task/needs]` plus the old entry's order. The same fixed list drops `:seon.task/product` from an appended entry.
5. **`docs/research/agent-platform/2026-10-13/narrow-lifecycle-2.md`**, Q1 and Q10: reproduced defects with the exact forms and replies (see §3c).
6. **`docs/seon/architecture/vocabulary.md`**, rows `references`, `instructions`, `ready`, `attempt`, `needs`, `product`, `obligations`. The agreed words for each key.

### 3b. The task attributes [V, from `resources/seon/schemas/seon.task.edn`]

Authored (the **entry**, closed map, one per task in `resources/seon/tasks/<ns>.edn`):
- `:seon.task/name` (string, required), `:seon.task/step` (`:prove` or `:implement`; absent reads as `:implement`), `:seon.task/intent` (string), `:seon.task/instructions` (string), `:seon.task/children` (set of lookup refs `[:seon.task/sym ns/name]`), `:seon.task/needs` (set of lookup refs), `:seon.task/product` (set of qualified test symbols).

Derived, never authored:
- `:seon.task/id` (12-char handle, `seon.id/id` over `[ns name]`), `:seon.task/sym` (unique `ns/name`), `:seon.task/ns`, `:seon.task/references` (set, derived from intent and instructions by `seon.ref/names`).

Runtime or stored on the task entity:
- `:seon.task/created-by`, `:seon.task/execution` (`:held :running :paused :stopped :cancelled`), `:seon.task/attempts-made`, `:seon.task/cron`, `:seon.task/zone`, `:seon.task/function`.
- Merge-era: `:seon.task/outcome` (`:merged :closed`), `:seon.task/reason`, `:seon.task/faults`, `:seon.task/landed`, `:seon.task/landed-git`, `:seon.task/refusal`, `:seon.task/refused-tests`.

On a definition, not a task: `:seon.task/tasks` (the `:seon/tasks` tags, a set of task symbols).

Views and derived maps: `:seon.task/effective` (id, sym, merged intent and instructions), `:seon.task/obligations` (step, proofs, product), `:seon.task/held-view`, `:seon.task/view`, `:seon.task/ended`, `:seon.task/status` (derived; `:satisfied` means every proof was already green at its fork point).

Assignment rows (`:seon.task/assignment-row`): `:seon.task/assigned`, `:seon.task/revision`, `:seon.task/agent`, `:seon.task/branch`, `:seon.task/attempt-of`, `:seon.task.attempt/number`, `:seon.task.attempt/end` (`:done :satisfied :given-back :capped :stalled :sent-back`), `:seon.task.attempt/target`, `:seon.task.attempt/reason`, `:seon.task.attempt/commit`, `:seon.task/merge-base`, `:seon.task/merge-commit`, `:seon.task/merged-declarations`, `:seon.task/git-commit`, `:seon.task/refusal`, `:seon.task.attempt/relaunch`.

### 3c. Ingestion, write-back, and `effective` [V unless marked]

- **Read.** `task-files` reads each `resources/seon/tasks/*.edn` as text. `file-declarations` reads one vector with `clojure.edn` (nothing evaluated), closes each entry against `:seon.task/entry`, refuses `:no-intent` unless a child, and derives `:id`, `:sym`, `:ns`.
- **Write (ingestion).** `tasks-tx` and `task-delta` compare stored and proposed declarations by handle. A changed declaration against the caller's basis refuses `:revision-conflict`. A new task no one owns as a child is written `:held`.
- **Write (agent).** `create!` builds `task-entity` (`:held`, `:created-by`) and writes it. `my.task/refine!` reads the held task's basis and calls `create!` with `(merge (get basis target) changes ...)`.
- **Write-back to files.** `seon.cluster.source/task-file-text` rewrites the entry through `task-entry-node`. [Q] `proven-task-file` then re-reads the file and refuses `:conflict` or `:not-equivalent` if the read-back differs.
- **`effective`.** [V] `clojure.core/merge` of `:seon.task/intent` and `:seon.task/instructions` from the root subject down `:seon.task/children`, the task's own keys last. Content never merges along `needs`. `:seon.task/product` is **not** part of `effective`.
- **Ready.** [V] `ready` returns the leaves whose `execution` is `:running`, not done, without a terminal end. A subject's single `:running` launches its leaves, because a child inherits its ancestor's execution.

Known defects, confirmed on the code path (not just the note):
- **Q1 (hand member lists).** [V] `create!` selects `[:seon.task/ns :seon.task/name :seon.task/step :seon.task/intent :seon.task/instructions :seon.task/children :seon.task/needs]` before `task-entity`, so `:seon.task/product` and any new key is dropped. [V] `task-entry-node`'s fixed order list drops `:product` from an appended entry. The `:entry` map in the schema is closed, so a new key is refused on ingestion and not on the agent path. Narrow-lifecycle-2 states a generic "hand lists" claim and marks it refuted for the hypothetical. The product drop is real.
- **Q10 (`refine!` erases product).** [V] `refine!` takes the basis (which includes `:seon.task/product`), then `create!` drops `product`, so the stored product is erased on every refinement. [Q] reproduced in narrow-lifecycle-2 on default, which I did not rerun.

---

## 4. Chains and stages

### 4a. Ranked sources

1. **`src/seon/task.clj`**, `ready-rules` (about line 940), `execution`, `ready`, `done?`, `resolution`, `assign!`. Today's launch and advance mechanism. Read these before any chain design.
2. **`src/seon/cluster/agent.clj`**, `next-actions` (about line 2119). [V] "The actions that carry each ended assignment and taskless agent in `held` through its lifecycle ... `:stop`, `:merge-base`, `:merge`, `:close`, `:publish`, `:delete-branch`." This is how an ended step advances.
3. **`src/seon/context.clj`**, `step-observation`, `step-status`, `step-verdict` (about line 389), `current-state`. The judge that says whether a step is done, and the context it is shown.
4. **`src/seon/turn.clj`**, `end-task-call` and the test step (see §2b). The writer that records an end.
5. **`docs/seon/schedule/cards/TASK-CHAIN.md`**, §3 (stages) and §3b (stage proofs, table). The intended stage sequence and what each proof asserts. Read as intent. The §2 table is stale (§5).
6. **`docs/research/agent-platform/2026-10-13/hybrid-lifecycle.md`**, §0 rules R1 to R10 and the phase list. The most recent design. Its header says it is "revised in place after the narrow check". Read the narrow check next to it, because several of its mechanisms are not built (§5).
7. **`docs/research/agent-platform/2026-10-13/chain-steps-history.md`**, §1 "Built today" (verified: only `:prove` and `:implement` exist). Its §2 onward traces each earlier step sequence (S1 to S-n) with commits. Read for history, not as current design.
8. **`docs/research/agent-platform/2026-10-13/narrow-lifecycle.md`** and **`narrow-lifecycle-2.md`**. The narrow checks of the hybrid. They are evidence, with measured replies.

### 4b. How a chain is launched and advanced today [V for names; I for the sequence]

1. A subject task and its children exist as task entries (ingestion) or are written by `create!`. Children are lookup refs in `:seon.task/children`.
2. One `:seon.task/execution :running` on the subject makes its leaves ready (`execution` rule, `ready`).
3. The reconciler (`seon.cluster.agent/next-actions` and the agent wake) assigns each ready leaf with `seon.task/assign!`, which binds the revision the assignment reviews.
4. The agent works its branch. After each eval request the turn runs the step's proofs and reaching tests (`test-step`), then `seon.context/step-status` judges the step.
5. The end is recorded by `seon.turn/end-task-call` (`:done`, `:satisfied`, `:given-back`, `:capped`, `:stalled`, `:sent-back`).
6. `next-actions` merges the branch, publishes its declarations, and deletes the branch. `leaf-done` then makes the successor ready.

### 4c. What is not built [V by grep]

- No `seon.task/chain!`, no `resources/seon/tasks/seon.step.edn`, no `:seon.task/template`, no `seon.cluster.source/merge-verdict`, no `seon.test/task` request key. All appear only in the hybrid, TASK-CHAIN or plan documents.
- No `:seon.task/exit` or stage-exit keys (`:seon.task/surveyed`, `:seon.task/defined`, and so on) in the schema.
- `:seon.task/step` allows only `:prove` and `:implement`. The stages Mine, Design, Land and Retrospective have no step kind.

---

## 5. Stale or contradicted sources

| Source | What it says | Contradicted by | Status |
|---|---|---|---|
| `docs/seon/schedule/cards/TASK-CHAIN.md` §2 table | Subject fields `:seon.task/must-read`, `:seon.task/evidence`, `:seon.task/decisions`; step `:seon.task/instructions` copied from `seon.step/<kind>` templates | Commit `8d14faeee` deleted must-read and evidence from the task schema. `seon.task.edn` has no `:seon.task/evidence` or `:seon.task/decisions`. `:seon.task/template` and `seon.step.edn` do not exist. | **Stale.** Keep §3 and §3b as intent only. |
| `TASK-CHAIN.md` §2 vocabulary | `my.task/create!` is a verb | `src/my/task.clj` defines only `current`, `refine!`, `send-back!` | **Stale.** |
| `TASK-CHAIN.md` §3 stage list | Six stages: Mine, Design, Prove, Implement, Land, Retrospective | `:seon.task/step` enum is `:prove :implement` | **Design only.** |
| `hybrid-lifecycle.md` (header, §0, R1 to R10) | `seon.cluster.source/merge-verdict` is the one judge; `seon.task/chain!` clones `seon.step.edn`; exit contracts per stage | None of these exist in `src` or `resources` (grep) | **Design, not built.** Read with the narrow check. |
| `narrow-lifecycle-2.md` Q1 wording | "three hand lists beside the declaration schema" refuted | `task-entry-node` derives members from the declaration but keeps a fixed order list; the product drop is real | **Partly right.** Its conclusion about `:product` stands. |
| `docs/seon/schedule/cards/*` and the ledger | Describe `:seon.task/must-read` | Commit `8d14faeee`: "Not done: docs/seon/schedule cards and the ledger still describe must-read" | **Stale** (author's own note). |
| `AGENTS.md`, task grammar paragraph | Entry is `:seon.task/name`, `:seon.task/intent` and optional `:seon.task/needs` | Schema entry also allows `:seon.task/step`, `:seon.task/instructions`, `:seon.task/children`, `:seon.task/product` | **Incomplete**, not contradicted. |
| `src/seon/fn.clj` `task-links` and `resources/seon/schemas/seon.task.edn` `:annotation` | Definitions still carry `:seon/must-read` | Commit `8d14faeee` says must-read was deleted | **Partly stale**: definitions still use it. |
| `src/seon/task.clj` `obligations` docstring and `src/seon/ref.clj` `stored-form` comment | `PR-25` | No PR-25 in the tree; a retired ticket reference | **Stale comment.** |
| `resources/seon/schemas/seon.task.edn` `:landed`, `:landed-git`, `land!` in its docstrings | Merge task fields named with "land" | `CLAUDE.md` retires `land` and `landing` for the merge vocabulary | **Retired word in use**, pending a rename (CLAUDE.md §Words). |
| `CLAUDE.md` "Documentation is data" target (index reports a dangling reference as a problem on its var) | Target behavior | Not found as a per-var report; `seon.ref/references` reports problems per reference only | **Target** [I]. |

---

## 6. Read these 10 first

1. **`src/seon/ref.clj`**: the whole namespace. It is the one reference parser and resolver.
2. **`resources/seon/schemas/seon.task.edn`**: every task attribute, its type and its description.
3. **`src/seon/task.clj`**: `file-declarations`, `create!`, `obligations`, `ready-rules`, `ready`, `effective`, `assign!`.
4. **`src/seon/turn.clj`**: `step-tests`, `run-proofs`, `test-step`, `end-task-call`. This is the proof run after each eval request.
5. **`src/seon/context.clj`**: `step-observation`, `step-status`, `step-verdict`, `current-state`. This is the step judge and the agent's context.
6. **`src/my/task.clj`**: `current`, `refine!` (including the product erasure, §3c), `send-back!`. These are the agent's task verbs.
7. **`test/seon/test_support.clj`**: `assign-task!`, `declare-proof!`, `evaluate`, `agent-value`, `assert-check!`. These are the test helpers.
8. **`.agents/skills/clojure-testing/SKILL.md`**, §1 and §2. How a proof is written, and when not to write one.
9. **`docs/research/agent-platform/2026-10-13/narrow-lifecycle-2.md`**: the reproduced defects Q1 and Q10, with the forms that show them.
10. **`docs/seon/schedule/cards/TASK-CHAIN.md`** §3b, then **`docs/research/agent-platform/2026-10-13/hybrid-lifecycle.md`** §0. The stage proofs and the rules, read against the code above and not as built facts.

Also useful: `src/seon/cluster/agent.clj` `next-actions`, for how an ended step advances.

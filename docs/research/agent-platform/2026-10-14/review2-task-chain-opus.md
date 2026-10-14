---
title: "Second review of TASK-CHAIN (2b5b9ccf6): reviewer opus"
type: review
status: complete; read-only review, no source or spec edited, no REPL writes
basis: "HEAD 2b5b9ccf6; code read at HEAD; evidence files of 2026-10-14; first-round reviews read so their findings are not repeated"
---

# Second review of TASK-CHAIN (opus)

**Verdict.** The data model and flow are right and much smaller than the copies design. Four things would still stop
a live chain. First, §6 deletes the first-turn opener. Second, the task-input identity is undefined. Third, the done
rule refuses Prove's recommended move. Fourth, the block cannot show the next task. These are G1-G4 below. Most of
the Sonnet-readiness risk is in S1c, S2 and S3a. They delete or reorder across owners that later slices convert.
Section 1 gives the exact text for each slice. All code citations were read at HEAD. "Inferred" marks a reading that
was not run.

## 1. Gaps that stop a live chain (most important first)

### G1. §6 deletes the code that opens the first model turn

- In `seon.turn/next-agent-work`, a fresh `:running` attempt runs `:prove` first (`seon.turn/prove-turn`, no model
  call). The `:open` arm then opens the model turn only when a wake is unanswered or `seon.turn/proved-unanswered?`
  holds.
- A chain agent starts with no wake. `proved-unanswered?` is therefore its only path to a model call.
- §6 ("Proof selection") and S2c ("the turn wrappers") delete `seon.turn/step-proved?` and `proved-unanswered?`.
  After S2c every chain agent idles. This is the same class as Run 25's M13.
- §2.3 also promises "all green at the start ends the task with no model call". That promise is the `:prove` arm.
- **Fix (§6, S2c):** keep `prove-turn`, `step-proved?` and `proved-unanswered?`. Convert their input:
  - `seon.turn/step-tests` becomes the held task's `:seon.task/proofs`, pulled on the branch.
  - Delete only `task-obligations`, `task-proofs` and the `:prove`/product branch of `step-tests`.
  - S3a's red already asserts "task two's first model call happens"; keep that assertion.

### G2. The task input has no identity that a database value can give (S2a)

- Today `seon.test.runner/task-reach-digests` keys a task-reading test by `(id/id [digest input] 64)`. The input is
  plain data (`seon.test.runner/task-input`: sym, obligations, effective).
- §4 makes the input `{:seon.test/task <entity> :seon.test/branch <db> :seon.test/main <db>}`. Datahike values cannot
  be content-digested.
- If the identity is the branch commit, every request changes it. Results are then never reused. Root's re-judge at
  `:seon.task.attempt/commit` also misses: the end commit is later than the request's capture commit. The result
  is `:proof-unknown`, and every Design and Prove done is refused.
- If the identity is the task entity alone, `prove-handoff` keeps a stale result. An example: a red `:unreached`
  stays recorded after Prove adds the covering proof. The predicate's static reach does not move when the agent
  defines a function.
- **Fix (S2a and §4):**
  - Keep `:seon.test/task` a task symbol in the request, as today. The runner builds the input itself
    (`seon.test.runner/task-input`).
  - The input value the body reads is `{:seon.test/task <pull of the task on the request database>
    :seon.test/branch <the request database>}`.
  - Its recorded identity is `[(seon.task/task-revision branch h) (seon.task/task-revision branch next-h)
    (seon.test.runner/program-revisions-digest branch)]`. `seon.test/changes` already keys its cache by that last
    digest.
  - Drop `:seon.test/main`: neither predicate in §4 reads main (see O2).
  - S2a's assertion "an unchanged second request reuses" becomes: "a second request that changes no program row and
    no task declaration reuses; defining a function, or changing the plan or the next task's proofs, reruns".
  - Add the row: "root's `current-results` with the task, at the attempt's end commit, finds the result the last
    request recorded".

### G3. The done rule refuses Prove for the move its instructions recommend

- Prove's instructions say "Prefer a new row in an existing table test".
- That test is green on main. On the branch it is red, as designed, and it is one of the next task's proofs.
- §2.4 refuses "a test the branch holds red that main holds green". It exempts only `:stray-red`, not this clause.
  The prototype in `done-rule-table.md` also exempts only the task's own proofs: `(remove proofs members)`.
- Today this is silent: main holds no runs (done-rule-table "Surprise 5"). It fires the day main records results.
- **Fix (§2.4, S2b table):** the regression clause also excludes the next task's proofs. Add the row "an existing
  test green on main, given a red row by Prove and listed in the next task's proofs → done".

### G4. Prove cannot find its next task: the block's before and after still read `needs`

- `seon.context/task-sections` builds "before" and "after" with `seon.context/chain`. That function and
  `seon.context/step-before` read `:seon.task/needs` and `:seon.task/children`.
- Prove's instructions say "the next task being the one your block shows after you", but chain tasks have no
  `needs`. Prove therefore has to guess its successor's name. The prove-handoff `:no-proofs` red then repeats until
  the agent's budget is spent.
- No slice converts this. Deleting `needs` in S1c also breaks these readers (see S1c below).
- **Fix (assign to S3a):** `chain` reads `[?t :seon.task/next ?n]` for after and `[?p :seon.task/next ?t]` for
  before. It shows the symbol, the first sentence of the plan, and the proofs with their status. `step-before`'s
  `:merged` lookup is deleted, because middle tasks never merge.
- Add to S3a's red: "task two's block names task one as before and task three as after".

### G5. `my.root/run!` names no datom that wakes root

- §2.7 says `run!` "touches the chain fact on main, the main write being the wake".
- Datahike writes nothing when a value is asserted again. `seon.cluster.wake/route!` offers only on `:added` datoms.
- The chain branch has no listen route after `:stop`, so the `:running` write on the branch wakes nothing either.
- **Fix:** declare `:seon.chain/runs` (an int, `:seon.wake/starts true`) on the chain fact, with the description
  "how many times root set a held chain task running". `run!` writes `:running` on the branch, then increments
  `:seon.chain/runs` on main. Contract:

  ```clojure
  [:=> [:cat :seon.db/connection :seon.task/sym] [:or :my.root/ran :seon.error/value]]
  ```

### G6. Root opens branch connections it never releases, and a restart does not re-follow chain agents

- **Connections.** §2.2 writes "through `active-branch-connection`, else the store owner's `open-branch!`". Nothing
  releases a connection root opened that way, so every advance or launch on an unheld branch leaks one.
  - Fix: root releases what it opened, in `finally`, as `seon.cluster.agent/close!` already does for its
    `branch-database`.
- **Restart.** After a restart, `run-pass!`'s last block starts only agents whose rows are on main (`own-branch`). A
  chain agent's row is on its chain branch.
  - Fix (§2.8, S3a): the chain reader emits each open chain attempt into `:seon.flow.plan/open`, so
    `seon.cluster.agent/follow` restarts its graph (`:start-flow` with `started?` false). That is the existing
    owner, not a new path.

## 2. Wrong claims

| Where | Claim | What the code says | Edit |
|---|---|---|---|
| §1 table | "`seon.task/declaration` lists them" (next and execution included) | `:seon.task/declaration` excludes execution. `seon.task/replacement-tx` only writes declaration keys, which is why `update!` kept `:running` (root-mechanics §3.5). Adding next or execution to the declaration would make root's execution writes conflict with every `update!` basis, and make `replacement-tx` retract them | "Declared members are `ns name plan instructions proofs` (`:seon.task/declaration`). `next` and `execution` are task attributes outside the declaration, written by `chain!` and root" |
| §1 chain fact | "its installation and retraction wake root" | `route!` offers only on `:added` | "its installation wakes root" |
| §1 writer, §2.1 | `chain!` "preserves the negative tempids"; the first task is written `:running` | `seon.task/task-delta` writes every new parentless task as `(assoc proposed :seon.task/execution :held)`. The old `chain!` links tasks by lookup ref through `seon.task/refs-last`, with no tempids | "`next` is the lookup ref `[:seon.task/id h]`, and joins `tree-attributes` in `refs-last`, which must take a one-ref value (today it iterates a set). `task-delta` keeps a proposed execution: `(get proposed :seon.task/execution :held)`" |
| §6 | deletes `step-proved?` and `proved-unanswered?` | they open the first model turn | G1 |
| §2.3 | the runner runs "the next task's proofs" for every task | For Design, the next proof is `prove-handoff`, a task-reading test. Under Design's binding it judges the wrong task and shows a red. For Prove, the next proofs are tests it defined or changed, so `seon.test/changes` already selects them | Delete "the next task's proofs" from the run list. The judged members still name them (for the `:stray-red` and G3 exemptions) |
| §3a | "Measured with the real task writer: 33–36 ms per write", for a member-only compare | It was measured with the whole-declaration basis that the writer has today. The member-only compare is unbuilt | See O1 |
| §6 | the deletion list is complete | Missing: `seon.task/refuse-needs-cycle`, `admitted`, `waits-for`, `cycle-in`; the `owned-children` clause of `task-delta`; the children clause of `held-tx`; the children branches of `done?`, `resolution` and the `execution` rule; `seon.context/chain` and `step-before`; `seon.render.ns/task-form` (it shows agents `(seon.task/obligations …)` as the requery); `seon.task/assignment`, `seon.error`'s proof check, and `seon.cluster.source/tested-task-basis`, all of which read `obligations`; `:seon.task.attempt/target` and `seon.context/send-backs` (a send-back is now a bail) | Add them, each in the slice that converts its reader |

## 3. Sonnet-readiness, slice by slice

### S1a (intent → plan)

- **Guesses left: the stored datoms.**
  - Seon has no norm owner. Runtime tasks on main (problem tasks, created-by root) keep `:seon.task/intent` datoms.
  - Re-ingestion writes `plan`. `replacement-tx` retracts only declared attributes, so `intent` lingers.
  - Text: "The restart batch that carries S1a runs `bin/seon reset` after `start --head`. The schema keeps no
    `:seon.task/intent`."
  - Verify on a branch of the live store that a schema without a still-used attribute publishes; CLAUDE.md requires
    this. The same holds for every attribute S1c and S2c delete.
- **Rewrite the `:plan` description** (agents see it in explains): "The task's plan, Markdown: it starts as the
  user's request, each task improves it, and it flows to the next task at advance."
- **Vocabulary:** record that "plan" (`:seon.task/plan`) is the task description and is not
  `seon.cluster.agent/plan` or `:seon.flow.plan/*`, root's action derivation. Two senses of one word need their
  line in `vocabulary.md`.

### S1b (schema)

Exact text:

```clojure
:next [:and {:seon.db/index true :description "The task that follows this one in its chain; absent on the last (seon.task/chain!)."} :seon.db/ref]
:instructions [:string {:min 1 :seon.task/owned true …}]
:proofs [:set {:seon.task/owned true …} :qualified-symbol]
:execution [:enum {:default :held …} :held :running :paused :stopped :cancelled]
:name [:and [:string {:min 1}] [:fn {:error/message "must read back as one symbol's name"} seon.task/symbol-name?]]
:outcome [:enum … :done :merged :closed]
```

The new file `resources/seon/schemas/seon.chain.edn`:

```clojure
:fact [:map {:seon.db/attributes true :seon.program/partition :seon.cluster}
       [:seon.chain/first :seon.chain/first] [:seon.chain/branch :seon.chain/branch]
       [:seon.chain/runs {:optional true} :seon.chain/runs]]
:first [:string {:seon.db/unique :identity}]   ; a :seon.task/handle value
:branch [:keyword {:seon.wake/starts true}]
:runs [:int {:min 1 :seon.wake/starts true}]   ; G5
```

- Split the red into one deftest per behavior (CLAUDE.md "a test checks one behavior"):
  - `seon.task-test/execution-defaults-to-held`
  - `seon.task-test/owned-members-are-instructions-and-proofs`
  - `seon.task-test/the-writer-refuses-a-bad-name`
  - `seon.cluster.registry-test/a-chain-fact-claims-its-branch-and-base`
- Delete the `symbol-name?` check in `seon.task/create!` in the same commit.

### S1c (writer and `chain!`) is too big, and it deletes before its readers convert

Its deletions reach far beyond the task writer:

- `children` and `needs` are read by `seon.task/ready-rules`, `execution`, `done?`, `resolution`, `held-tx`,
  `task-delta` and `refuse-needs-cycle`, and by `seon.context/chain`, `step-before` and `obligations`.
- `effective` is read by `seon.context/held-task`, `seon.test.runner/task-input` and `my.task/refine!`. Those are
  S2a's and S1d's owners.

Split it:

- **S1c, `chain!` only (Opus):**
  - add `seon.task/template` and the new `chain!`;
  - change `task-delta` (keep the proposed execution) and `refs-last` (one-ref `next`);
  - delete `chain-copy`, the old `chain!` and their tests (`seon.step-test/a-default-judges-the-task-it-is-given`,
    `survey-waits-for-the-latest-user-approval`, and the `task_test`, `db_flow_test` and `write_back_test` rows that
    call the old `chain!`).
  - Nothing else is deleted.
- **S3e, the tree goes (Sonnet, after S2c and S3a):** delete `children`, `needs`, `step`, `product`, `effective`,
  `goal-owner`, the readiness clauses, `refuse-needs-cycle`, the old `seon.context/chain` body and every §2-table
  item, with their stored datoms (reset).

Contracts to write into §2.1:

```clojure
:seon.task/task-map [:map [:seon.task/ns {:optional true} :seon.task/ns] [:seon.task/name {:optional true} :seon.task/name]
                     [:seon.task/plan {:optional true} :seon.task/plan] [:seon.task/instructions {:optional true} :seon.task/instructions]
                     [:seon.task/proofs {:optional true} :seon.task/proofs]]
:seon.task/named-task-map [:merge :seon.task/task-map [:map [:seon.task/ns :seon.task/ns] [:seon.task/name :seon.task/name]]]
:seon.chain/request [:map [:seon.chain/tasks [:cat :seon.task/named-task-map [:* :seon.task/task-map]]]
                     [:seon.chain/from {:optional true} :seon.store/branch]]
:seon.chain/created [:map [:seon.chain/branch :seon.store/branch] [:seon.chain/first :seon.task/handle]
                     [:seon.task/handles [:vector :seon.task/handle]]]
(seon.task/chain! connection request) ; [:=> [:cat :seon.db/connection :seon.chain/request] [:or :seon.chain/created :seon.error/value]]
(seon.task/template database sym)     ; [:=> [:cat :seon.db/database-value :seon.task/sym] [:or :seon.task/task-map :seon.error/value]]
```

- The Malli `:cat` puts "the first map names the chain" in the contract, so no separate check is needed.
- Unnamed maps take the name `"<first name>-<i>"`, where `i` is the 1-based index.
- The branch is `(keyword (str "chain-" h))`.
- A repeated call with different content answers the writer's `:revision-conflict` error. It never overwrites.

### S1d (verbs) is three lanes; help is off the critical path

- **S1d1 `my.edit/text` (Sonnet).**
  - `seon.edit/text [s old new] → [:or :string :my.edit/no-match-error :my.edit/ambiguous-match-error]`.
    `seon.edit/exact` calls it for its replacement. `my.edit/text` is its public alias in `my.edit`.
  - Red: `seon.edit-test/text-replaces-the-one-exact-occurrence` (rows: one match, none, two, and old equal to
    new).
- **S1d2 `my.task/update!` (Sonnet, after S1a and S1b).**
  - Every `my.*` function today takes a request map that the binding filler completes (`my.task/current`,
    `refine!`). The positional variadic `(update! task k f & args)` needs its contract written:

    ```clojure
    [:=> [:cat :seon.db/connection :seon.agent/id :seon.task/sym :seon.task/member :seon.fn/function [:* :any]]
     [:or :my.task/updated :seon.task/conflict-error :seon.error/value]]
    ```

  - The red needs a row proving the filler prefixes connection and agent for 1, 2 and 3 trailing args. If it cannot,
    the shape is the request map `{:seon.task/sym … :my.task/member … :my.task/update [f & args]}`.
  - The basis is the whole declaration, as the writer has it today (O1).
  - Callers that convert:
    - `seon.bootstrap` help text;
    - `test/my/task_test.clj`, `seon/context_test.clj`, `cluster/db_flow_test.clj`, `cluster/merge_test.clj`,
      `help_test.clj`;
    - the task EDN files that cite `refine!`: `seon.ref`, `seon.turn.world`, `seon.context`, `seon.step`,
      `my.task`, `seon.task`.
- **S1d3 help as `resources/seon/help.md`.** This goes beside the slices, not on the path to S6. It changes the
  `:seon.help/help` shape and `help_test`. Run it with the `references.md` side lane.

### S2a (one task input)

- Specify G2's identity.
- Split the world-refusal fix into **S2a2** (a different owner, `seon.test` run).
  - Red: `seon.test-test/a-world-refusal-is-the-requests-error`. A refused overlay answers
    `:seon.test/run-refusal` and records no member result.
  - `seon.test.runner/designed-red?` stays as it is.
- S2a's readers that pass the task symbol: `seon.context/current-results` (new arity
  `[database tests task-sym]`), `head-entries`, `step-proved?`, `step-observation` and `done-refusals`. The task
  comes from `judged`'s `:seon.task/sym`.

### S2b (done rule and selection)

- **Order against S2c.** S2b converts `step-observation` to read the proofs field. If the sweep has not run, every
  non-chain task loses its tagged proofs.
- Reorder: **S2c1, the sweep writes proofs** (data only). It is harmless, because `seon.task/obligations` already
  unions `:seon.task/proofs` with tags. Then S2a and S2b. Then **S2c2, delete tags and tag readers**.
- **Exact members:**

  ```
  judged = {:seon.task/sym t
            :seon.task/proofs own
            :seon.task/next-proofs next
            :seon.source/members (own ∪ (seon.test/changes tests) ∪ gate-sets)
            :seon.source/results …
            :seon.program/identities delta}
  ```

  `done-refusals` reads `:seon.task/next-proofs` for `:stray-red` and the G3 exemption.
- Run order in `seon.turn/test-step`: `run-reaching` over the request's changes plus `seon.test/changes`, then
  `run-proofs`. Today the proofs run first and take the step-time dial first.
- Write that the own proofs get at least 1 ms of what is left, as `run-reaching` does today.
- Variants: `:seon.source/proof-unknown-error`, `:seon.source/proof-red-error`, `:seon.source/regression-error`,
  `:seon.source/stray-red-error` and `:seon.source/contract-gap-error`. Each extends `:seon.error/base` with
  `:seon.error/offending [:vector :seon.test/sym]` (or `:seon.fn/sym`) and is added to `:seon.source/refusals`.
- Table rows to add: G3's row; "the last task (no next): any red refuses".

### S2c (sweep, then delete)

The split is above. The script is `script/seon/sweep_task_tags.clj`, committed. Its acceptance table stays as §6
says.

### S3a (root reads chains) is three lanes, and the text it needs is below

- **S3a1, the chain reader (pure).**

  ```clojure
  (seon.cluster.agent/chain-facts main store)
  ; → [{:seon.chain/branch b :seon.chain/first h :seon.flow.plan/path [h…] :seon.flow.plan/open [row…]
  ;     :seon.flow.plan/due h?}]
  ```

  - It reads `seon.db/branch-database` and releases it in `finally`. No cache (O3).
  - Red: a table over hand-written branch values: due first; open attempt (no outcome); bailed (end, no outcome:
    not due); and an inherited `:running` main task (not due).
- **S3a2, launch on the branch.**
  - The `:attempt` op writes `creation-tx` plus `seon.task/attempt-tx` on the chain branch connection, opened and
    released by root (G6).
  - `:start-flow` with `:seon.chain/branch` skips `fork-attempt!`, `write-merge-base!` and `seed-attempt!`.
  - `seon.turn/attempt-running?` reads the branch.
  - Open chain attempts join `:seon.flow.plan/open` (G6).
  - Red: task one's first model call, with zero hand acts.
- **S3a3, advance.**
  - Delete the relaunch rows. `seon.cluster.agent/assignment-actions` gets the row `:done` with next →
    `[:stop :advance]`.
  - `seon.task/advance-tx [database assignment-key]` is called as `[:db.fn/call #'seon.task/advance-tx k]` and
    answers `[]` once the attempt has an outcome.
  - `run-pass!` re-derives after `:advance`.
  - `seon.context/chain` reads `next` (G4).
  - Red: as in §7, plus G4's block assertion.
- Replace "three distinct agent ids would appear across three tasks" with "task one's and task two's attempts name
  different `:seon.agent/id`s and the same `:seon.task/branch`".

### S3b

It is specified well enough. Add: `seon.render.ns/task-form` is converted from `(seon.task/obligations …)` to
`(my.task/current {})`.

### S3c

- Split the restart table out as **S3f (Opus)**. A Sonnet lane cannot guess how to simulate a crash.
- Name the seam: stop the chain's graphs (`seon.cluster.agent/stop-flow!`), retract nothing, then call
  `seon.cluster/recover-runs!` on the chain branch connection and run one `run-pass!`.

### S3d is two lanes

- Doc views (Sonnet) are one lane.
- The proportional-assembly assertion is the other. Its cause is "under measurement" (§3a). A Sonnet lane cannot fix
  an unknown cause, so this goes to Opus or Astra after the cost dig.
- S3d also edits `seon.context`, which S3a3 (G4) holds, so it runs after S3a3, not beside it.

### S4

See §5 for the layout. Add a pure core so the tables do not build databases:

- `seon.step.design/refusals [facts]`, with the facts shape `:seon.step.design/facts`
  `{:sections … :problems … :changed-schemas … :cited-schemas …}`, and `seon.step.design/facts [database task]`.
- The same split for Prove.
- The table rows run the pure core over literal facts. Each costs microseconds and needs no canonical-writer
  fixture.
- The bound-task assertion runs `facts` then `refusals` on the agent's branch.
- Building recorded designed-red results in a table fixture would need nested test runs, at 190–750 ms each
  (prove-stage-table). That is near the 5 s bound and beyond a Sonnet lane.

## 4. Overengineering

- **O1. `update!` compares the one member it read.**
  - The writer compares the whole declaration (`task-delta` against `basis`). Comparing one member is new writer
    code.
  - It guards a race that cannot happen in a linear chain: one agent works a chain at a time, and root writes only
    `execution`, `next` and `outcome`, which are outside the declaration.
  - CLAUDE.md: never build protection against a failure not observed. Keep the existing basis, and say "a task
    changed since the read is refused".
- **O2. `:seon.test/main` in the task input.** Neither `design-handoff` nor `prove-handoff` reads main. Only
  `done-refusals` does, and it is not a test. Delete it from §4.
- **O3. The head-id cache for chain reads.**
  - Evidence: 20 chains cost 9–14 ms uncached, and the cache "pays only above roughly 100 live chains"
    (root-mechanics §1).
  - Drop it from §2.2 and S3a. Add a `seon.cache` kind only when a pass measures over budget.
- **O4. A third fork-diff owner in §4.**
  - `seon.step/changed` and `fork-database` re-derive "changed since fork" by definition digest.
  - Existing owners already do this: `seon.context/change`, which is `seon.turn/fork-commit` plus
    `seon.db/commit-database` plus `seon.program/changed-identities`, and `seon.test/changes` (by behavior digest).
    The two disagree on a docstring-only change.
  - Make `seon.step/changed` call `seon.program/changed-identities` over `(seon.turn/fork-commit branch)` and filter
    by attribute. Delete `fork-database`.
- **O5. "Run the next task's proofs"** (§2 table). It is redundant for Prove and wrong for Design.
- **O6. Two lifecycles.**
  - §6 keeps per-step merge, publish and delete "for tasks with a next", which implies main-hosted single tasks keep
    the old path: `ready`, `plan-facts`, `fork-attempt!`, `seed-attempt!` and `write-merge-base!`.
  - A one-task chain already is that case.
  - Say whether the old path stays. Recommend that, after S3b, root starts a filed task with `(chain! conn
    {:seon.chain/tasks [task-map]})`, and S3e deletes the main-hosted launch. Otherwise two launch paths stay, and
    every lifecycle fix lands twice.
- **Kept without a reason:** `:seon.task.attempt/target` and `seon.context/send-backs` (a send-back is a bail);
  `held-tx` beside the Malli `:default` (decode at ingestion and delete `held-tx`, or keep `held-tx` and drop the
  `:default`).

## 5. Parallel stage development after S3a

Today all three stages share `resources/seon/tasks/seon.step.edn`, `test/seon/step_test.clj` and the planned
`src/seon/step.clj`. Three lanes cannot edit them at once. Give each stage its own namespace. The task file
namespace is the file name (`seon.task/file-namespace`), so templates move with it.

| Owner | Design lane | Prove lane | Implement lane |
|---|---|---|---|
| template | `resources/seon/tasks/seon.step.design.edn` → `seon.step.design/template` | `seon.step.prove.edn` → `seon.step.prove/template` | `seon.step.implement.edn` → `seon.step.implement/template` |
| predicate | `src/seon/step/design.clj` (`facts`, `refusals`) | `src/seon/step/prove.clj` | none (the done rule is the check) |
| proof deftest | `test/seon/step/design_test.clj` (`handoff`) | `test/seon/step/prove_test.clj` | none |
| live-run script | `script/seon/step_design_run.clj` | `script/seon/step_prove_run.clj` | `script/seon/step_implement_run.clj` |
| graded runs | `docs/research/…/s5-design-*.md` | `s5-prove-*` | `s5-implement-*` |

**Must land first, in one owner (S4a, root or Sonnet):**

1. `src/seon/step.clj`, holding only the shared helpers: `sections`, `problems`, `changed` (O4), `results`,
   `deftest?` and `cited-fns`, each contracted.
2. The task-input shape, `:seon.step/input` (G2).
3. The deletion of `seon.step.edn`, `test/seon/step_test.clj` (survey, define, `worked-results` and the I3 test) and
   their tags.
4. One grading form, `seon.step/grade [database task] → {:refusals … :calls … :tokens … :hand-acts …}`, which every
   run script calls.

Also required before live runs: S2a, S2b (the done rule and run order) and S3a1–3. Implement needs only S2b plus
S3a. It can start before S4a's predicates exist, because its check is the done rule.

A lane changes only its own template's instructions and its own predicate. A change to a shared helper goes to S4a's
owner as a patch.

## Verification limits

- Every code claim was read at HEAD 2b5b9ccf6. No REPL probe was run: this round's questions were answered by the
  code and the 2026-10-14 evidence.
- G2's failure mode (re-judge misses at the end commit) is inferred from `seon.context/current-results` and
  `seon.test.runner/task-reach-digests`. It was not run.
- G3 is inferred from §2.4's text and the `done-rule-table.md` prototype. It cannot fire on default today.
- The behavior of the default filler on a variadic positional `my.*` contract (S1d2) is not verified.

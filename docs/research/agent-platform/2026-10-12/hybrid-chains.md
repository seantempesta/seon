---
title: "Hybrid: chains that run unattended — the model's tool is the REPL, the task is merged data, the branch is the proposal"
type: research
status: hybrid of plan-chains-opus.md (d2e15f0ff) and plan-chains-astra.md (f2e344f15, 51a60a7b3), written by Fable, who wrote neither; decisions taken in the owner's absence are listed in §7 for review
created: 2026-10-12
basis: "Both plans read end to end; the brief (chain-design-brief.md); AGENTS.md; chain-experiment-9.md (c76bf7ce1); context-review/round4 captures and suff-1/-2/-4; prebrief-m10-m12.md (606f9ec52); task-write-back.md (7c87dfb8e, d50eb5cec). Code facts checked by symbol at HEAD 51a60a7b3: seon.ai/request-body declares no tools and seon.ai/completion-text reads only choices.message.content; seon.bootstrap/help-value's eight groups; seon.merge/needed?; seon.cluster.agent/fork-attempt!, seed-attempt!, write-merge-base!, kept?, ended-problem!; seon.task/ready, next-attempt; bin/lane status: no live claims. No JVM run, no paid call."
---

# Hybrid: chains that run unattended

## 0. For root

- **Where we are.** Chains 5–9 all ended `:capped`/`:stalled`, 0 hand acts after launch, nothing merged. Chain 9 shows the
  first-order failure is the **interface, not the context**: the owner found on call 2, the three failing rows diagnosed
  by call 8, then 32 of 40 replies were native tool-call markup for tools that do not exist; `scan` defined on call 40.
  Round 4 cold reads: all CAN DO WITH SEARCHING (one extra call, never a blocker). Three code facts stop a two-step chain
  regardless of the model: a Prove product never merges (`seon.merge/needed?`), a successor forks a deleted branch
  (`fork-attempt!` throws), a capped step is terminal (`seon.task/ready`). Default: 808 tasks, 0 with children or a step.
- **Ruling on the interface (D1): DeepSeek gets ONE native tool, `eval` `{"code": string}`, read into
  `seon.turn/planned-sources` exactly as reply text is.** Not a DSML translator (the invented tools do not exist), not
  more instruction (help already forbids markup; the model read the no-forms message and continued). The model's
  trained channel becomes the REPL; the reader and the one path do not change.
- **Phases, simplest first, each accepted by a real DeepSeek run** (Haiku cold read of the real first call as the cheap
  pre-check): **H0** `eval` tool (seon.ai) → **H1** help tells the truth (per-step Done, Prove tags the successor,
  automatic merge, one tool) → **H2** chain data (`:seon.task/instructions` back, `seon.task/effective` = `merge` down
  `:children`, execution inherited so one fact launches, `refine!` intent→subject, instructions→own step) → **H3**
  `:seon.context/chain` in the block → **H4** M7 + M10 = **the two-step chain, zero hand acts** → **H5** derived relaunch
  forking the ended commit, bounded by a dial → **H6** context fits (ref dedup, no cut inside an operation form, dial
  measured) → **H7** the real chain on a mined subject. H0 and H1 land tonight, file-disjoint; H2–H4 are serial on
  `task.clj`, `context.clj`, `agent.clj`, `merge.clj`, all free (`bin/lane status`: no live claims).
- **Traps.** (1) `write-merge-base!` is the only merge-base writer on the chain path: deleting it makes every red "already
  red", silently (prebrief A1). (2) `:seon.task.attempt/fork`, the predecessor clause and `kept?`'s pin go in ONE commit.
  (3) `seed-attempt!` is needed by H5's commit fork, `attempt-rows-tx` by `assign!`: keep both. (4) A relaunch in the same
  transaction as the end is never ready. (5) Content merges down `:children` only, never along `:needs`. (6) Help teaches
  installed forms only: the `eval` line lands with H0, the refine-instructions line with H2. (7) Capture the Haiku input
  from the real assignment after its prove pass, not a fixture. (8) Streamed tool-call arguments arrive as
  `delta.tool_calls` fragments: H0 folds them too. (9) Default boots `:faulted` on `install-row!`: foreign, not ours.

## 1. Agreements (both plans, taken as settled)

1. **A chain is a subject task with step children, authored in one task file**; a step's effective task is plain
   `clojure.core/merge` of content maps, the step's own keys last; `:seon.task/instructions` comes back as the clear
   per-step field, `:seon.task/intent` is the shared goal; intent becomes optional on a child entry and required on the
   effective value; `:seon.task/references` derives from both texts (`seon.ref/names`). The file stores sparse authored
   declarations, never the expanded value (Astra §2). Both verified the merge as plain data at the REPL.
2. **Successors fork main after their needs merge** (`seon.task/ready-rules` `leaf-done` reads `:merged`, S5);
   predecessor-branch succession is deleted: `fork-attempt!`'s predecessor clause and throw, `:seon.task.attempt/fork`,
   `kept?`'s successor pin, `plan-facts`'s basis query. Keep `write-merge-base!` and `attempt-rows-tx` (prebrief).
3. **A Prove step's designed reds merge as its product** (M7): delete `seon.merge/needed?`'s declarations exclusion and
   the unconditional red veto (`seon.test/change-reds`, `red-test-refusal`), judge the merged candidate by
   `seon.context/step-status`'s one rule (Astra probe 5: designed-red *completion* exists; designed-red *merge* does not).
4. **A capped or stalled step continues as a NEW assignment** (new identity, new branch) forking the ended attempt's
   commit and retaining the proposal's merge base (`seon.cluster.registry/fork!`'s nested base), bounded, never a
   resumption of execution; its first context shows why the last attempt ended and its final proof results.
   `ended-problem!` stops saying "merge it" for a worsening branch (K4).
5. **The block gets a `:seon.context/chain` section**: the subject, the steps before (outcome `:merged`, what each
   merged, derived from `seon.cluster.source/merge-accepted`), the steps after (step kind and instructions). A need no
   longer appears only as a must-read carrying its intent.
6. **Help is corrected per step**: Prove tags the successor's name, never its own (today's line 5 is wrong); Done
   differs by step; "root reviews and merges" becomes automatic merge; the stub form with
   `:seon.error/unimplemented`; refine, send-back and ask lines name installed forms with real shapes.
7. **Acceptance of every phase that changes what agents are told, their tools or the lifecycle is a real DeepSeek run**
   (chain-experiment method, cap 40, one launch act, nothing by hand), graded on hand acts after launch, calls before
   the first `defn`, tokens, errors by signature, and whether each step reached `:done` and `:merged`. The Haiku cold
   read of the exact first-call capture (verdict CAN DO; a NO stops the paid run) is the cheap pre-check.
8. **Not to do**: no DSML-to-form interpreter of invented tools; no callers or reaching tests in context; no cap raise
   to buy progress; no trims to fit a budget; no retry around formless replies; no redo of write-back/adoption
   (7c87dfb8e, d50eb5cec); no fix of `fork-attempt!`'s throw (it is deleted); no subject with failed history for the
   first two-step run.
9. **`my.task/refine!` is durable**: branch facts → facts merge → `seon.merge/publish!` writes the task file entry
   (rewrite-clj) → adoption three-way keeps it. Exists at HEAD; nothing to build.

## 2. Disagreements, with the ruling

| # | Opus | Astra | Ruling and reason |
|---|---|---|---|
| 1 | Content merges down `:children` only; a need's content reaches the successor as `:chain :before` data; `refine!` of intent writes the **declarer** (the subject), of instructions its own step or a successor's (the hand-over) | Content merges down `:children` AND along `:needs` (ancestors, then needs in task-symbol order, own keys last; shared ancestors once); `refine!` writes the **held step's own** declaration; later steps inherit through needs | **Opus's merge path, with one simplification.** Merging along needs needs a join order, ancestor dedup and leaks a predecessor's instructions into a successor without its own. One path, one rule. `refine!` of `:seon.task/intent` writes the subject (the goal's owner, so every step sees it); of `:seon.task/instructions` its own step. **No cross-task write** (Opus Q6): the Prove step hands over by naming its proofs, stubs and shapes in the goal, which is what both plans' help text already tells it to do. One fewer write path. (D3) |
| 2 | No `:design` step kind until a Prove run fails for want of it | A `:design` kind with its own done rule (interface design with executable acceptance), introduced in C1 | **Opus.** Count first: 0 tasks carry a step; the target is a two-step chain. Prove's product is red deftests, contracted stubs and registered schemas; that is the design. A separate kind adds a done rule, help group and schema for a case that has not occurred. (D2) |
| 3 | One launch act: `:seon.task/execution` inherits down `:children` (`ready` reads the nearest declared execution up the tree; ingestion stops writing `:held` on children) | `seon.task/start!`, a writer called by root that sets the subject's leaves `:running` in one transaction | **Opus.** It is the owner's merge rule applied to one more key and it deletes code (child `:held`); `ready` already walks children for needs. Root's launch stays one `:running` fact on the subject. (D4) |
| 4 | Relaunch derived by `next-actions` when the ended branch changed something, up to a dial `:seon.config.run/relaunches`; unchanged ends close with a root task | Root's relaunch fact written automatically under a per-subject call allowance `:seon.task/call-limit` + dial `:seon.config.task/max-chain-calls`; context derives calls left in the chain | **Opus's derived relaunch and count dial, Astra's fork.** A count dial (default 2) is one dial and no new task attribute; the per-attempt cap already bounds spend. The new assignment forks the ended commit and **retains the merge base** (Astra, `registry/fork!`), so both attempts' changes form the proposal. Root's `my.root/relaunch!` is not needed for chains and is not built. (D5) |
| 5 | Successor forks main as soon as the need is `:merged`; publication runs beside | Publication (files, git) finishes before the successor starts, for the first measured chain | **Opus, with Astra's reporting rule.** `leaf-done` already reads `:merged`; main holds the merged entities whether or not the file is written, and the first chains are interpreted declarations only. No new condition in `ready`. A chain is **reported complete only when its last publication's `:seon.task/git-commit` exists**; host-bound successors are out of scope until measured. (D6) |
| 6 | Not addressed beyond "no retry around formless replies" | Verify the wire (`seon.ai/request-body`, `tool_choice`), compare another provider, or (C) support structured tool replies through the one reader with a real declared contract | **Root's leaning, which is Astra's (C) at its smallest: one tool, `eval`, `{"code": string}`.** Verified: `request-body` declares no `"tools"` and `completion-text` reads only `content`, so DSML in content is the model's trained channel with nowhere to go. Declaring the one tool and reading `tool_calls` whose name is `eval` as reply text is the request owner's fix in the data flow, not a parser of invented markup. (D1) |
| 7 | Add a chain help group; correct Done per step; keep eight groups | Rewrite the whole help as literal lines; show only the current step's group | **Astra's shape, Opus's size.** The help is rewritten in full (§4.4), shorter than today's 2,162 tokens, and `help-value` (which already takes the database and agent id) shows only the current step's Done group. Replace contradictory lines; append nothing beside them. (D11) |
| 8 | Printer rule "deeper cuts first" so a top-level referent prints whole at 6,144 (Q5 b) | Resolver shows each referent's own content; fix excess at the producer or the general printer; no per-task constant | **Two general fixes and a dial, measured, in H6.** (a) `seon.ref/references`: a name resolved at top level is cited by name only below (forgive resolved ~8 times, ~600 tokens). (b) The printer never cuts inside an operation form (`(my.program/source …1)` is unrunnable). (c) Raise `:seon.config.render.agent/token-budget` only if forgive's `scan` is still cut after (a) and (b), and measure the per-call cost. No shape rule until a capture shows it is still needed. (D8) |
| 9 | Not addressed | Root delivers messages (M16) and one starter (M13) are phases C7 | **Not on the two-step path.** Nobody answers an unattended question: help tells the agent to decide the simplest option consistent with the goal, record it with `refine!`, and send back only when the goal is impossible (Opus Q3 a; the owner's own rule). M13/M16 stay filed where they are. (D7) |
| 10 | Not addressed | A redefinition that drops comments is the agent's proposal, shown faithfully (Q5 A) | **Astra.** Nothing is built; help says the defn replaces the whole definition, comments included. (D9) |

## 3. What both missed

1. **The one native tool.** Both treat reply format as prompt or wire settings. The model's trained output channel is
   tool calls; Seon offers none, so the channel leaks into content as markup. Offering exactly one tool whose argument
   is the REPL's input removes the failure class instead of instructing around it (§2 row 6; §5 H0).
2. **The prompt grew 3× (2,608 → 7,808 tokens) between chains 8 and 9 and DSML went 0 → 32.** Neither plan isolates
   this (chain 9 names it a hypothesis). H0 makes the question moot for correctness; H1 and H6 shrink the prompt
   anyway, and each run records first-call tokens so the correlation is measured, not argued.
3. **The help is the same 2,162 tokens for every step and every call**, and both plans add to it. The help is a system
   message in every request; H1 cuts it and shows one Done group.
4. **The Prove step's test namespace.** Both plans' help says "in the shown test namespace" without saying where it
   comes from. No name guessing: the step's `:seon.task/instructions` names it as a reference (`seon.sci.forgive-test`),
   and `:seon.context/start` for a Prove step offers both `in-ns` forms. (D12)
5. **The Haiku pre-check's capture method.** Round 4 captured from a fixture agent with no prove pass, so proofs showed
   `:pending` and the cold reader counted a missing-failure call that a real first call does not have. The capture
   must come from the real assignment: launch with `:seon.config.ai/no-provider true`, render
   `seon.context/model-context` on the assignment's branch after its prove pass, then set the provider on. UNVERIFIED
   that the no-provider turn leaves the assignment in that state; H1's lane verifies it first.
6. **The chain has no data to run on.** Default holds no task with children. Every phase from H2 on needs an authored
   chain entry in a task file; the fixture chain (§4.1) and the run's subject (D10) are named here so no lane invents
   them.
7. **Attempt identity minting is duplicated** (`seon.task/next-attempt` and `seon.cluster.agent/plan` both compute
   `(inc ended)`; prebrief B.3). Astra names it; Opus does not; it is on H5's path (a removed row re-mints a branch
   `fork!` answers without forking: chain 7 P2). H5 gives the task a monotone `:seon.task/attempts-made`.

## 4. The design

### 4.1 Task data and the merge rule

One task file holds the chain. The authored entries are sparse; `seon.task/file-declarations` and `tasks-tx` ingest
every declared key unchanged.

```clojure
[{:seon.task/name "forgive-idempotent"                        ; the subject: the goal and the children
  :seon.task/intent "`seon.sci.forgive/forgive` is idempotent: forgiving forgiven text changes nothing …"
  :seon.task/children #{[:seon.task/sym seon.sci.forgive/forgive-idempotent-prove]
                        [:seon.task/sym seon.sci.forgive/forgive-idempotent-implement]}}
 {:seon.task/name "forgive-idempotent-prove" :seon.task/step :prove
  :seon.task/instructions "Add the rows `\"a]a\"`, `\"('\"` and `\"(\\\\\"` to `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`, tagged `seon.sci.forgive/forgive-idempotent-implement`; name each row in the goal."}
 {:seon.task/name "forgive-idempotent-implement"
  :seon.task/needs #{[:seon.task/sym seon.sci.forgive/forgive-idempotent-prove]}
  :seon.task/instructions "Make the proofs green; the fix lands in `seon.sci.forgive/scan`."}]
```

| Attribute | Declared on | Written by | Rule |
|---|---|---|---|
| `:seon.task/intent` | the subject (required on a task with no parent; optional on a child) | the file; `my.task/refine!` writes it on the **subject** | inherited down `:children`; a child's own value overwrites |
| `:seon.task/instructions` `[:string {:min 1}]` | each step | the file; `my.task/refine!` writes it on the **held step** | the step's own key; a parent declares none, so nothing is inherited |
| `:seon.task/references` | any | derived by `seon.task/task-delta` from intent **and** instructions | consumers unchanged (`task-edges`, `seon.contracts`, `seon.health`) |
| `:seon.task/children`, `:seon.task/needs`, `:seon.task/step` | as today | the file | unchanged; needs are the order, never a merge path |
| `:seon.task/execution` | the subject (root's one launch fact) | root | `seon.task/ready` reads the nearest declared execution up `:children`; ingestion writes `:held` on roots only |

**The effective task** is `seon.task/effective` (new, pure): the content maps (`select-keys` of intent and
instructions) from the root subject down `:children` to the step, `(apply merge …)`, the step last. It is what
`seon.context/held-task` renders and what `seon.ref/names` scans. The stored declarations never change shape. A
refinement reaches the next step because the next step forks main after the merge that carried the refined subject,
and `seon.merge/publish!` writes the entry back in the same publication (task-write-back phases A and B, merged).

### 4.2 Lifecycle, zero hand acts after the launch fact

| # | Event | Mechanism | State at HEAD → change (phase) |
|---|---|---|---|
| L0 | root writes `:running` on the subject | `seon.task/ready` inherits execution | each leaf needs its own → inherited (H2) |
| L1 | the first ready step is assigned; branch forked from main | `seon.cluster.agent/plan` → `write-attempt!`, `start-attempt!` | works (37–366 ms in chains 5–8) |
| L2 | the proofs run before the first model call | `seon.turn` prove pass (M1) | works |
| L3 | the model works through its one tool; refines when it decides | `eval` → `planned-sources`; `my.task/refine!` | content only (H0); intent to subject, instructions to own step (H2) |
| L4 | step done → end on branch → merge of the whole branch | `seon.context/step-status`; `next-actions` `:merge` → `seon.merge/merge!` | a needed step merges facts only → delete `needed?`; designed reds pass (H4) |
| L5 | publication writes functions, tests, schemas and the task entries; git commit | `seon.merge/publish!` | works (M9, 7c87dfb8e) |
| L6 | branch deleted | `:delete-branch` | works; `kept?` loses its successor pin (H4) |
| L7 | the successor is ready (`leaf-done` reads `:merged`) and forks **main** | `plan`, `fork-attempt!` | throws when the predecessor branch is gone → delete the clause and `:seon.task.attempt/fork` (H4) |
| L8 | `:capped` or `:stalled` with changes on the branch | derived relaunch: a new assignment forking the ended commit, base retained, up to `:seon.config.run/relaunches` | terminal today → (H5) |
| L9 | send-back to an earlier step | `leaf-done`'s `:sent-back` not-join readies the target; the sender waits on its need | derivation exists (UNVERIFIED live); proof in H4 |
| L10 | the last step merges → subject `done?` | `seon.task/done?` over children | exists |

Not on this path: M4 conflicts, M11 update-from-main, M13 one starter, M14 full diff, M16 message delivery, M15–M18.

### 4.3 Context per step type

The block (`seon.context/task-sections`), new members in bold:

```clojure
#:seon.context{
 :task {:seon.task/sym the.ns/step :seon.task/intent "<effective goal>" :seon.task/instructions "<this step's work>"}
 :chain {:seon.context/subject the.ns/subject                     ; absent on a task with no parent and no needs
         :seon.context/before [{:seon.task/sym the.ns/prove :seon.task/step :prove :seon.task/outcome :merged
                                :seon.context/merged {:seon.program/tests [t…] :seon.program/functions [f…]
                                                      :seon.program/schemas [k…] :seon.task/tasks [s…]}}]
         :seon.context/after  [{:seon.task/sym the.ns/implement :seon.task/step :implement
                                :seon.task/instructions "<what it will do with your product>"}]}
 :proofs …                       ; own tagged deftests: source, status, failures (unchanged)
 :step {:seon.task/step :prove :seon.task/status :working
        :seon.context/product-tests […]                            ; exists: reds tagged with a successor
        :seon.context/start {:seon.render/operations [(in-ns 'the.owner) (in-ns 'the.owner-test)]}}  ; Prove: both
 :must-reads …                   ; references of intent and instructions, each resolved once, one level inside
 :turns …}
```

| Step | Where am I | What I produce | What I rely on | Done | Refine |
|---|---|---|---|---|---|
| Prove | `:chain :after` names the next step | deftests (rows in an existing table test preferred) tagged with that name; contracted stubs throwing `:seon.error/unimplemented` | the goal's references | product tests red by assertion or that error; own proofs (if any) green; nothing broken | the goal: proof names, stub names, shapes |
| Implement | `:chain :before` names the Prove step and what it merged | the functions the proofs call | the merged tests and stubs, already on my branch | proofs green; nothing I reach broken | the goal: what settled; own instructions |

`:seon.context/merged` is one `seon.cluster.source/merge-accepted` per need (one `d/since` of its merge commit),
grouped by family; each entry is a name the resolver shows on request. Tests enter only as proofs, product tests or a
named reference (owner ruling).

### 4.4 Exactly what the agent is told: the `(help)` text after H0–H2

Every line teaches installed forms only (`seon.help-test/help-names-only-installed-forms`). Lines marked (H0) and (H2)
land with those phases; the rest with H1. `help-value` shows **one** Done group, the current step's.

**Who you are**
- You are a Seon agent at a Clojure REPL, on your own branch of the cluster's database. When your step is done,
  everything on your branch merges onto main by itself: functions, tests, schemas and your task's facts. Then the step
  after you starts from main. There is no done call and no merge call.
- The program is data: functions, tests, schemas and your task are entities you read and define at this REPL.

**How to speak** (H0)
- You have one tool, eval. Its code is Clojure forms, each starting at column zero; prose goes in ;; comments. Text
  you write outside the tool is read the same way. Nothing else exists: no other tool, no XML or JSON, no file or
  shell command.

**Your task**
- :seon.context/task: :seon.task/intent is the goal every step of your chain shares; :seon.task/instructions is your
  step's own work. Do your instructions; keep the goal true.
- :seon.context/chain names the subject (the goal's owner), the steps :seon.context/before you (merged onto main
  before your branch forked, each with what it merged) and the steps :seon.context/after you (with their
  instructions). What a step before you merged is already on your branch: read it with (my.program/source 'ns/name)
  and rely on it; do not redo it. (H2)
- :seon.context/proofs are the deftests that show your step done, each with its :seon.test/source and
  :seon.context/proof-status: :red fails, with :seon.context/proof-failures; :pending has no result for your current
  code yet; :green passes. They run by themselves after each eval; the next block shows them.
- :seon.context/must-reads are what the task cites in backticks, each resolved once: a function's :seon.fn/source and
  :malli/schema, a schema's form, a test's source, a document's heading. :seon.render/operations are forms to run for
  the rest. :seon.context/step holds :seon.context/start, the in-ns form for the owner's namespace.

**Done (Implement)**
- Every deftest in :seon.context/proofs green on your branch and no test you reach broken. They start red: read
  :seon.context/proof-failures, change the function that produces the wrong value, and read the next block.

**Done (Prove)**
- Red deftests for the step after you, tagged with ITS name from :seon.context/after, never your own: (deftest
  ^{:seon/tasks #{'the.ns/next-step}} names-the-case (is (= 3 (the.ns/f 2)))). Prefer a new row in the table test
  your instructions name; define a new deftest only for a behavior no test covers, in the test namespace your
  instructions name.
- A function the test calls that does not exist yet is a defn in the owner's namespace with its contract and the body
  (throw (ex-info "not yet" {:seon.error/type :seon.error/unimplemented})). A failing assertion or that error is a
  designed red and merges; an unresolved symbol or a setup error is not. Your reds show under
  :seon.context/product-tests. Done is those reds designed, your own proofs green, nothing broken.

**How work proceeds**
- 1. Read the owner: (my.program/source 'seon.db/q), (doc seon.db/q), (my.program/search :seon.task/intent).
- 2. Enter its namespace with the :seon.context/start form, (in-ns 'the.owner.ns). A definition changes the namespace
  it is evaluated in.
- 3. Define the function there with its contract on the defn: (defn increment {:malli/schema [:=> [:cat :int] :int]}
  [x] (inc x)). It replaces the whole definition on your branch, comments included: keep the ;; comments you were
  shown unless you mean to delete them. A defn without a contract is admitted with a warning and merge refuses it.
- 4. Call it on real inputs and read the value. Probe with let; a scratch definition you do not mean to merge goes
  away with (my.program/ns-unmap! 'the.owner.ns/scratch).
- Change code at the REPL only: no slurp, spit, clojure.java.io or shell. Root writes your definitions to files when
  your step merges.

**Write what you learn** (H2)
- (my.task/refine! {:seon.task/intent "…"}) rewrites the goal for every step of your chain;
  (my.task/refine! {:seon.task/instructions "…"}) rewrites your step's work. Refine as soon as you decide something
  the next step would otherwise rediscover: a proof's name, a stub's name, an input shape. Name code in backticks as
  fully qualified symbols or keywords; your next block resolves each one.

**When you cannot**
- Nobody answers a question while the chain runs. Decide the simplest option consistent with the goal, record it
  with refine!, and continue.
- A task that is wrong or impossible goes back: (my.task/send-back! {:my.task/reason "…" :my.task/target
  'the.ns/earlier-step :my.task/counterexample {…}}) as your last form, the target from :seon.context/chain. It runs
  again, then you do. Never weaken a proof or a contract to go green.

**Each reply is data**
- :value, :error, :findings and :result, a symbol naming the live value later forms may use. :ms and :alloc-mb are
  the form's cost. A cut value shows the form that reads the rest.
- An error is a map: read :seon.error/type, :seon.error/message and :seon.error/problems, change the form, and never
  resend it unchanged. Return values; never println.

**Data**
- Every value has a named Malli schema: reuse the names on the data flow; (my.program/source :seon.task/intent) reads
  one; (seon.schema/register! :comp.order/qty [:int {:min 1}]) names a new concept. Keys are fully namespaced
  keywords; symbols stay symbols.
- Absence is no key: an optional input is [:map [:x {:optional true} :int]]; never [:maybe …], :any or :some. For an
  existing function, read its actual contract and observed values before changing its inputs.
- Fix a bad value in the function that produces it; add no fallback, retry, catch or guard. Never with-redefs,
  alter-var-root, intern or load-file. Code you write holds no atom.
- In Malli a literal is [:= {} nil]; a contract is [:=> [:cat in1 in2] out], every input inside :cat. map and filter
  are lazy: realize them with mapv or into inside binding and try. Write fully qualified symbols; an alias works only
  if (ns-aliases *ns*) shows it.

Deleted lines: "they reach main only when root reviews and merges your work"; "5. A :prove step writes a deftest
naming its task"; "Done is every proof green … all green completes your task and tells root" (as the one rule);
"Never send tool-call markup" (replaced by **How to speak**); the ask-root line (nobody answers unattended).

## 5. Phases

Acceptance method for every phase (owner, binding): (a) capture the exact first-call request of the run's task from the
real assignment after its prove pass (§3.5); (b) a fresh Haiku reads only that capture and answers §4.3's table per
step, citing shown data; verdict CAN DO or the gaps named; NO stops the paid run; (c) the paid DeepSeek run,
chain-experiment method, cap 40, one `:running` act, nothing by hand; recorded as `chain-experiment-N.md` with hand
acts after launch, calls before the first defn, DSML or unknown-tool replies, tokens, errors by signature, each step's
`:done`/`:merged`/`:seon.task/git-commit`.

| # | Phase (goal) | Red proof: deftest and data assertion | Files | Deletes | Haiku pre-check | DeepSeek run that accepts it |
|---|---|---|---|---|---|---|
| **H0** | **The model's tool is the REPL** (1). `seon.ai/request-body` declares one tool, `eval`, parameters `{"code": string}`; `seon.ai/completion-text` and the stream fold read `choices[0].message.tool_calls` (and `delta.tool_calls` fragments) whose `function.name` is `eval`, and the reply text is content text followed by each call's `code`, in order; a tool call with another name is reported as `:seon.ai/unknown-tool-calls` on the completion so the no-forms error names it; the transcript (`seon.ai/messages`) keeps rendering the assistant's forms as content, so no `tool` role messages exist | `seon.ai-test/a-tool-call-named-eval-is-the-replys-code`: `(seon.ai/request-body projection request)` holds `"tools"` with exactly one function named `"eval"` whose required parameter is `"code"`; `completion-text` of a fixture document with content `""` and one `eval` call `{"code":"(in-ns 'seon.sci.forgive)"}` answers `:seon.ai/text` `"(in-ns 'seon.sci.forgive)"`; with content `";; plan"` and two calls, the text is the three in order; a call named `my.program/edit` is in `:seon.ai/unknown-tool-calls` and not in the text; a stream fixture of two argument fragments folds to the same text | `src/seon/ai.clj`, `resources/seon/schemas/seon.ai.edn`, `test/seon/ai_test.clj`; `src/seon/turn.clj` only if the no-forms message needs the unknown-tool names (one `cond` arm) | nothing (the first tool) | none: no context change; the help line lands in H1 | **Run 10**: chain 9's subject, cap 40, same store state. Accepts when DSML/unknown-tool replies are 0 and the first defn of `scan` arrives before call 10 (chain 9: call 40). Not graded on merge: that waits for H4 |
| **H1** | **Help tells the truth** (1, 2): §4.4 minus the H2 lines; `help-value` shows the current step's Done group | `seon.help-test/help-teaches-each-step-its-done` (a new row in the help table test): for a `:prove` agent the Done group's lines name `:seon.context/after`, `:seon.context/product-tests` and `:seon.error/unimplemented` and no line pairs `:prove` with tagging its own task; for `:implement` the Done group names `:seon.context/proof-failures`; no line says root reviews; the help's estimated tokens (`seon.ai.tokens/estimate`) are below today's 2,162 | `src/seon/bootstrap.clj`, `test/seon/help_test.clj` | the five lines named in §4.4 | a cold reader of the Implement capture states the Done rule and the one tool; of a Prove capture (fixture chain) states the tag-name rule | folded into **Run 10** when H0 and H1 land together (both file-disjoint, tonight); otherwise Run 10 repeats on H1 |
| **H2** | **Chain task data** (2, 3): `:seon.task/instructions` back (schema, `:seon.task/entry`, `:seon.task/declaration`, task-selector); intent optional on a child; `seon.task/effective`; references from both texts; execution inherited down children; `my.task/refine!` writes intent to the subject and instructions to the held step | `seon.task-test/a-steps-effective-task-is-its-subjects-merged-with-its-own` (table: subject intent + step instructions → both present; a step's own intent overwrites; a refined subject intent is the step's next view); `seon.task-ingestion-test` row: a child with instructions and no intent ingests, a root with no intent refuses by name; `seon.task-test/a-running-subject-readies-its-first-step-only`: `:running` on the subject → `ready` = the Prove step; after its `:merged`, `ready` = the Implement step; a child's own `:held` keeps it held; `my.task-test/a-refined-goal-is-the-next-steps-goal-after-merge`: Prove refines intent on its branch → merge → the Implement step's effective intent holds it and the Prove entry is unchanged; refining instructions changes only the held step | `resources/seon/schemas/seon.task.edn`, `resources/seon/schemas/my.task.edn`, `src/seon/task.clj`, `src/my/task.clj`, `test/seon/task_test.clj`, `test/seon/task_ingestion_test.clj`, `test/my/task_test.clj`; the fixture chain file `resources/seon/tasks/<fixture-ns>.edn` | `task-selector`'s one-entity view; ingestion's `:held` on child tasks | n/a (data only; H3 renders it) | none alone; accepted with H3 |
| **H3** | **The chain in the block** (1, 2): `:seon.context/chain` (§4.3); Prove's `:seon.context/start` offers both `in-ns` forms; the H2 help lines | `seon.context-test/a-steps-block-names-its-chain`: fixture subject with Prove and Implement, Prove merged: the Implement block's `[:seon.context/chain :seon.context/before 0 :seon.context/merged :seon.program/tests]` holds the Prove deftest symbol and `:seon.task/outcome :merged`; the Prove block's `:after` holds the Implement symbol and its instructions; its `:start` operations hold two `in-ns` forms; a task with no parent and no needs has no `:seon.context/chain` key; `:seon.context/task` carries the effective intent and the step's instructions | `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj`, `src/seon/bootstrap.clj` (two lines), `test/seon/help_test.clj` | the needs clause of `seon.context/task-references` (needs move to `:before`) | Prove and Implement captures of the fixture chain: CAN DO on all six questions of §4.3 | **Run 11**: a single Prove step on the two-step subject (D10), Implement held. Accepts when the Prove step reaches `:done` on its branch with 0 hand acts and its product tests are designed reds. Merge is not graded (H4) |
| **H4** | **The lifecycle: the two-step chain** (1). M7: `seon.merge/merge!` merges a needed step's declarations; designed reds pass the gate by `seon.context/step-status`'s rule; delete `needed?`, `seon.test/change-reds` and its `accept-merge!` call, `red-test-refusal`, `declarations-held-tx`. M10: `fork-attempt!` forks main only; delete its predecessor clause and throw, `:seon.task.attempt/fork` (schema, `write-attempt!`, `kept?`, `attempt-rows-tx` pull), `kept?`'s `forked?`/`waiting` pin, `plan-facts`'s basis query and the octopus pick; `plan`'s fork-point is `:main`; **keep `write-merge-base!` (reshaped to write the fork's commit after `registry/fork!`) and `seed-attempt!`** | `seon.cluster.merge-test/a-prove-steps-designed-reds-pass-the-gate`: a Prove branch holding a red deftest tagged with its successor and a stub merges; main holds both; `a-merge-whose-reached-definition-moved-writes-nothing` as filed. `seon.cluster.db-flow-test/a-join-forks-main-after-both-needs-merge` (absent today): no successor attempt before the second need is `:merged`; its row has no `:seon.task.attempt/fork`; its `:seon.task/merge-base` equals main's commit at fork; its branch holds its agent. `a-prove-steps-red-product-merges-into-main`: the Implement fork holds the Prove deftest and stub. Convert `a-successor-forks-its-predecessors-end-commit-recorded-on-its-attempt` (assert nil fork; marker present only after merge). Chain-path merge-base proof: a red judged at its merge base during a chain attempt (fails with `write-merge-base!` deleted, passes with it kept; prebrief A1). Send-back row: a sent-back Prove step is ready again and its sender is not (L9) | `src/seon/merge.clj`, `src/seon/cluster/agent.clj`, `src/seon/task.clj` (`attempt-rows-tx` pull), `src/seon/test.clj` (retired callers), `resources/seon/schemas/seon.task.attempt.edn`, `seon.task.edn` (line 108), `seon.flow.plan.edn`, `test/seon/cluster/merge_test.clj`, `test/seon/cluster/db_flow_test.clj`, `resources/seon/tasks/seon.cluster.agent.edn` (retire the filed task names) | M7 and M10 rows above; `successor-waits?` from the task intent | the Implement capture after the Prove merge: "what did my predecessor produce" answered KNOWN from `:merged` | **Run 12, the goal**: the two-step chain Prove → Implement on D10's subject; one `:running` fact on the subject; 0 hand acts after; both steps `:merged`; publication's `:seon.task/git-commit` holds the test rows, the fix and the refined task entry; the subject `done?`. Count calls per step and whether Implement's first calls rediscover what Prove refined (chain 8: 22 calls before the first defn) |
| **H5** | **A capped or stalled step continues** (1): `seon.task/ready` re-admits a leaf whose terminal end has a later relaunch and the dial allows (`:seon.config.run/relaunches`, default 2); `next-actions` derives the relaunch when the ended branch changed something (an unchanged end closes with a root task); the new assignment forks the ended commit with `registry/fork!` retaining the merge base, seeded by `seed-attempt!`; `:seon.task/attempts-made`, a monotone counter written by `attempt-tx`, replaces both `(inc ended)` computations; `ended-problem!`'s "merge it" offer deleted; the relaunched first block shows the previous end, its final proof failures and `:seon.context/changes` (M14's derivation, read only) | `seon.cluster.db-flow-test/a-capped-attempt-with-changes-relaunches-from-its-commit`: after one pass, assignment 2 exists with a new key and branch, its commit at fork equals assignment 1's end commit, its `:seon.task/merge-base` equals assignment 1's, and its branch holds its row and agent; with the dial at 0 no assignment 2 exists and the problem intent names no `my.root/merge!`; a relaunch in the same transaction as the end is not ready (B3); a hand-retracted first row does not re-mint its key (P2). `seon.task-test/ready-answers-in-under-10-ms-for-fifty-leaves` stays green | `src/seon/task.clj`, `src/seon/cluster/agent.clj`, `resources/seon/schemas/seon.config.run.edn`, `seon.task.edn`, `seon.task.attempt.edn`, `test/seon/cluster/db_flow_test.clj`, `test/seon/task_test.clj` | `ended-problem!`'s merge offer; `plan`'s own `(inc ended)`; the `:error`-forever terminal set as written | the relaunched first call: the reader states why the last attempt ended and what its last defn did | **Run 13**: forgive (chain 7's subject, 4 of 5 rows at cap) at cap 40, ≤ 2 assignments, 0 hand acts, reaches `:merged` |
| **H6** | **Context fits the work** (2): `seon.ref/references` cites a name resolved at top level by name only below; the printer never cuts inside an operation form; the budget dial measured and raised only if `scan` is still cut | `seon.ref-test/a-name-resolved-at-top-level-is-cited-by-name-below` (data: the nested entry holds only `:seon.ref/name`); `seon.print-test/an-operation-form-is-never-cut-inside` (the printer's own tests may assert text); a `seon.profile/measure` on `seon.context/model-context` for the forgive task before and after | `src/seon/ref.clj`, `src/seon/print.clj` (printer owner), `test/seon/ref_test.clj`, `test/seon/print_test.clj`, config only if the dial changes | the repeated summaries | round-5 captures of the four round-4 tasks: `scan` whole, no `…1`; all four CAN DO | **Run 14**: forgive again; graded on calls before the first defn against Run 13 |
| **H7** | **The real chain** (4): `seon.cluster.agent/the-real-chain-runs-with-no-nudges` reshaped to Prove → Implement on a subject mined from `seon.contracts/task` or the backlog, with a `refine!` in step 1 read by step 2 | as filed, asserted on data: every step `:merged`, the subject `done?`, the task file entry written back with the refinement, 0 hand acts after launch | test and task files only | — | three CAN DO reviews before the paid run | **Run 15**: hand acts 0, calls per step, tokens; the goal's measure |

Order: H0 and H1 tonight, file-disjoint, one run. H2 → H3 → H4 serial (task.clj, context.clj, agent.clj/merge.clj
are free now). H5 after H4 (agent.clj). H6 beside H5 (ref.clj, print.clj). H7 last. No phase exists only to turn a red
green: each deletes something or is accepted by a run.

## 6. What not to do

- Do not translate DSML or any invented tool into forms; do not add a second tool. One tool, `eval`.
- Do not merge content along `:needs`; do not give `refine!` a cross-task write.
- Do not add a `:design` step, a chain runner, a handoff store, a prompt store, a per-subject call allowance or
  `my.root/relaunch!` before a run shows the need.
- Do not show reaching tests or callers as context; do not trim a source or an intent to fit; no per-task budget.
- Do not raise the call cap to buy progress (chain 8 stalled at 24 of 40; chain 9's cap bound only because of DSML).
- Do not delete `write-merge-base!`, `seed-attempt!` or `attempt-rows-tx`; do not fix `fork-attempt!`'s throw.
- Do not hand-relaunch on a store holding earlier attempts (chain 7 P2/P4); wait for H5 or reset.
- Do not pick forgive for Run 11/12 (four failed attempts, a hard body); it is Run 13's relaunch subject.
- Do not make successors wait for publication; report the chain complete only after it.
- Do not run M4, M11, M13, M14's UI, M15–M18 for chains.
- Do not redo write-back or adoption; do not repair the `install-row!` fault from a chain lane.

## 7. Decisions for the owner to review

| # | Decision | Reason | Alternative |
|---|---|---|---|
| D1 | DeepSeek gets one native tool, `eval` `{"code": string}`, read as reply text into the one path; unknown tool names are reported in the no-forms error | 32 of 40 replies were the model's trained channel with nowhere to go; the request owner is `seon.ai`; the reader does not change | translate DSML at the reader (parses invented tools into unresolved symbols); more help text (already read and ignored); compare another provider (changes the benchmark) |
| D2 | No `:design` step; Prove's product is red tests, contracted stubs, registered schemas | 0 tasks carry a step; the target is two steps; a kind needs a done rule, help group and schema | Astra's interface-design kind with executable acceptance, added when a Prove run fails for want of it |
| D3 | Content merges down `:children` only; `refine!` writes `:seon.task/intent` on the subject and `:seon.task/instructions` on the held step; no cross-task write | one path, no join order, no predecessor instructions leaking; the hand-over is the goal naming the proofs and stubs | Astra: merge along needs too (task-symbol order at joins); Opus: `refine!` of a successor's instructions |
| D4 | One launch act: `:seon.task/execution` inherited down `:children`; ingestion writes `:held` on roots only | the merge rule applied to one more key; deletes code | `seon.task/start!` writing `:running` on every leaf |
| D5 | Relaunch is derived, bounded by `:seon.config.run/relaunches` (default 2), forks the ended commit, retains the merge base; unchanged ends close with a root task | one dial, no new task attribute, the per-attempt cap bounds spend; the proposal keeps both attempts' work | a per-subject call allowance `:seon.task/call-limit`; root's manual relaunch fact |
| D6 | A successor forks main as soon as its need is `:merged`; publication runs beside; a chain is reported complete only when its last `:seon.task/git-commit` exists | no new condition in `ready`; main holds the merged entities; first chains are interpreted | wait for publication before the successor (Astra), one more readiness condition |
| D7 | Nobody answers an unattended question; help says decide the simplest option, record it with `refine!`, send back only when the goal is impossible; the ask-root help line is deleted | the owner's own rule; M16 is not on the path | a root Opus agent answering root's inbox as a step (later) |
| D8 | Context size: dedup at `seon.ref/references`, printer never cuts inside an operation form, then measure; the budget dial moves only if `scan` is still cut | two general fixes, no shape rule, no per-task constant | Opus's "deeper cuts first" printer rule; raise the dial first |
| D9 | A redefinition is the agent's whole proposal, comments included; help says keep the shown `;;` comments | nothing to build; the diff shows what was dropped | a source-edit operation that preserves unrelated syntax |
| D10 | The two-step run's subject is a small, pure, interpreted Seon function chosen by root at launch with these criteria: no failed attempts, the Prove step adds rows to an existing table test, the fix is one body, red verified interpreted before launch; forgive is retired to Run 13 | chains 5–9 show the subject's history and a design question inside Implement cost more than the mechanism | Astra's `demo.area` chain (proves the mechanism, not Seon work); forgive again |
| D11 | The help is rewritten in full (§4.4), shorter than 2,162 tokens, showing only the current step's Done group | the help is in every request; two Done rules in one text contradict each other | add a chain group beside the eight groups |
| D12 | A Prove step's test namespace is named in its `:seon.task/instructions`; `:seon.context/start` offers both `in-ns` forms; no `-test` derivation | no name guessing; the author knows the table test the row belongs in | derive `ns-test` by convention |

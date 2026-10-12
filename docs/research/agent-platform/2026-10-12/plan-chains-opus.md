---
title: "Plan (Opus): chains that run unattended — task data, context, lifecycle"
type: research
status: independent plan for the chains design round (2026-10-12 night); the other plan is Astra's, unseen; a Fable hybrid follows
basis: "HEAD 97c0fd7a3 read by symbol; default (cluster default, archive 8d14faeee era, state :faulted with 1 open fault) read in JVM mode as root, read-only; SCI probes as agent plan-opus on its own branch; context-review round4 captures (e7416bdb6) and the three suff-*.md cold reads; chain experiments 5-8. VERIFIED = checked at the code or the store this session; UNVERIFIED = read from notes."
---

# Chains that run unattended: task data, context, lifecycle

## 0. For root (one screen)

- **No chain step has ever merged onto main through the merge path, and the next step could not start if one did.**
  Three code facts at HEAD block a two-step chain before any model quality matters (all VERIFIED):
  1. `seon.merge/merge!` merges a needed step's **facts only** (`(not (needed? target task-sym))`), so a Prove
     step's red deftests never reach main; its Implement successor forks main, finds no proof, and
     `seon.task/assignment-read` refuses `:no-proofs` (M7 is the fix).
  2. `seon.cluster.agent/fork-attempt!` still forks a successor from `:seon.task.attempt/fork` and **throws** when the
     predecessor's branch is gone, and a merged branch is deleted (M10 is the fix).
  3. `seon.task/ready` drops every task whose attempt ended `:capped`/`:stalled`; nothing reads
     `:seon.task.attempt/relaunch` (grep: zero readers). Every chain 5-8 ended there (M12).
- **The help teaches the Prove step the wrong tag.** `seon.bootstrap/help-value` line 5 says a `:prove` step tags
  *its own* task; `seon.task/obligations` counts as product only deftests tagged with a task that **needs** this one,
  and `seon.context/step-status` requires the Prove step's own proofs green. A Prove agent following help writes a
  red own-proof and can never be done (VERIFIED by reading both; no Prove step has run since the rule changed).
- **The agent is never told it is in a chain.** `seon.context/task-sections` shows the task, its proofs, its step and
  its intent's references. A need appears only as a must-read carrying its intent (round4 capture 4); a successor
  never appears; a parent never appears (default holds 0 tasks with `:seon.task/children`). suff-4: "It does not tell
  me whether M13 is finished". A Prove agent cannot know which name to tag.
- **The help contradicts auto-merge:** "they reach main only when root reviews and merges your work".
- The design below is one new data member (`:seon.task/instructions`, back), one pure derivation (the effective task:
  `merge` down `:seon.task/children`), one block section (`:seon.context/chain`), `refine!` writing the declarer, and
  the already-filed lifecycle phases (M7, M10, M12) in the order a chain needs them. Every phase's acceptance is a
  **real DeepSeek run** (chain-experiment method), with a Haiku cold read of the captured first-call context as the
  cheap pre-check.

## 1. Where we are, measured

### Goal 1: chains complete unattended (hand acts per chain, target 0)

| Chain | Subject | Hand acts after launch | Reached | Stopped by |
|---|---|---|---|---|
| 2, 3, 4 | various | 8, 23, 17 (36 of 48 relaunches, forks of deleted predecessors, ends left on branches) | code right on a branch; merged only as lanes' git commits | relaunch, fork, G8 |
| 5 | `seon.render.transcript/contract-runtime-duration` | 0 | `:capped` 15 calls, no defn | an unmade design decision (K2: nil positional input), no relaunch (K3) |
| 6 | `seon.sci.forgive/…` | 0 | `:stalled` 9 calls, no defn | two DSML replies; no relaunch |
| 7 | same | 0 (3 before launch: hand relaunch) | `:capped` 15 calls, defn fixing 4 of 5 rows | cap; the hand relaunch re-minted the assignment (P2), end never reached main (P4) |
| 8 | same, cap 40 | 0 (1 launch act) | `:stalled` 24 calls, no defn | two prose replies; the model planned `slurp`/`spit` instead of `in-ns`+`defn` (P11); calls 7-9 spent reading a cut intent (P10) |
| 9 (in flight or unwritten) | same, task `be71b5c7e9ea` | UNVERIFIED | assignment `6f4a9782cf90` ended `:capped` at cap 40; main's `seon.sci.forgive/scan` digest unchanged (`c54e2f6c…`) | no report yet; config still `no-provider false`, cap 40 (VERIFIED on default) |

What is automatic at HEAD (VERIFIED by symbol): assignment of a `:running` ready leaf (`seon.cluster.agent/plan` →
`write-attempt!`, `start-attempt!`); the end copied to main and the merge (`seon.cluster.agent/next-actions` `:merge`
through `seon.merge/merge!` → `merge-tx`); publication as due work (`seon.merge/publish!`, M9) when
`:seon.task/merged-declarations` is present; a need counts done only once `:merged` (`seon.task/ready-rules`
`leaf-done`, S5). What is not: relaunch (no reader of `:seon.task.attempt/relaunch`), a successor's fork after its
predecessor's branch is deleted (`fork-attempt!` throws), a Prove product reaching main (`needed?`), launching a
chain (each leaf needs its own `:running`: `seon.task/ready` reads the leaf's own `:seon.task/execution`, "no
inheritance"). Default: 808 tasks, 16 with needs, **0 with children, 0 with `:seon.task/step`**, 1 assignment, 0
outcomes. So the six-step tree `seon.cluster.agent/the-real-chain-runs-with-no-nudges` has no data to run on.

### Goal 2: context useful by construction

Round 4 (e7416bdb6) first-call contexts: 4,163–8,255 tokens; three of four hit the 6,144-token display budget and
cut. Cold reads (suff-1, -2, -4): all three **CAN DO WITH SEARCHING**, none CAN DO. The gaps, by producer:

| Gap (reader's words) | Producer | Kind of fix (owner's three) |
|---|---|---|
| the body of `seon.sci.forgive/scan`, where the fix lands, cut at `…+9971` | the value printer's one budget spread across the block (`:seon.config…` display budget 6,144) | a dial decision + a general printer rule (Q5) |
| proofs `:pending` with no failures at the first call | capture method: the fixture writes no prove pass; a launched assignment runs its proofs before the first call (hybrid-done-and-merge §1, M1) | verify on chain 9's first call (UNVERIFIED); no code change if confirmed |
| `(my.program/source …1)`: an operation cut inside, unrunnable (8 references) | value printer eliding the quoted name inside a nested operation form | general printer rule: an operation form is never cut inside |
| `seon.sci.forgive/forgive` resolved ~8 times (~600 tokens) | `seon.ref/references` one-level references re-summarize a name the block already resolves | producer: a referent resolved at top level is cited by name only below |
| no proofs block at all, proofs named only in prose (needs task) | the task has no tagged deftest; `assign!` would refuse it | data: the task is not assignable; capture chose an unassignable task |
| need's status unknown, successor unknown, parent absent | `seon.context/task-sections` has no chain section | new section (P1 below) |
| `:seon.agent/inbox`, `wake/` aliases unresolved | resolver: aliases are not qualified names | producer (`seon.ref/references`): resolve an alias against the citing namespace, or the intent writer expands it |

### Goal 3: tasks improve as work proceeds

`my.task/refine!` (7e2544e38) rewrites the held task's **intent** on the agent's branch through `seon.task/create!`;
the facts merge carries it to main; lane `task-writeback-impl` (15 paths claimed, uncommitted at HEAD) is
implementing task-write-back.md phases A (publication writes the entry with rewrite-clj) and B (adoption three-way
so a merged refinement is not reverted). `:seon.task/instructions` was deleted in 8d14faeee and the owner brought it
back tonight. refine! cannot write instructions, cannot write a successor (the Design hand-over), and on a step under
a subject it would overwrite the step's own copy of the goal instead of the goal.

### Goal 4: not started; nothing here serves it before goal 1 holds.

## 2. The design

### 2.1 The task data model for a chain

A chain is a **subject** task with **step** children. Authored in one task file:

```clojure
[{:seon.task/name "forgive-idempotent"
  :seon.task/intent "`seon.sci.forgive/forgive` is idempotent: … (the goal, with references inline)"
  :seon.task/children #{[:seon.task/sym seon.sci.forgive/forgive-idempotent-prove]
                        [:seon.task/sym seon.sci.forgive/forgive-idempotent-implement]}}
 {:seon.task/name "forgive-idempotent-prove" :seon.task/step :prove
  :seon.task/instructions "Write red deftests tagged `seon.sci.forgive/forgive-idempotent-implement` pinning …"}
 {:seon.task/name "forgive-idempotent-implement"
  :seon.task/needs #{[:seon.task/sym seon.sci.forgive/forgive-idempotent-prove]}
  :seon.task/instructions "Make the red proofs green; the fix lands in `seon.sci.forgive/scan`."}]
```

| Attribute | On | Authored / written by | Read by | Exists? |
|---|---|---|---|---|
| `:seon.task/intent` | subject (required on a task with no parent); optional on a step | the file; `my.task/refine!` writes it **on its declarer** | the effective task | exists; becomes optional on `:seon.task/entry` for a child |
| `:seon.task/instructions` `[:string {:min 1}]` | each step | the file; `refine!` (own, or a successor's: the Design/Prove hand-over) | the effective task (own wins; not inherited by siblings: it is the step's own key, and a parent declares none) | **brought back** (schema, entry, declaration, task-selector) |
| `:seon.task/references` | any | derived by `seon.task/task-delta` from **intent and instructions** (`seon.ref/names` over both texts) | `seon.task/task-edges`, `seon.contracts` | exists; derives from one more text |
| `:seon.task/children`, `:seon.task/needs`, `:seon.task/step` | as today | the file | `ready-rules`, `obligations` | exist; unchanged |
| `:seon.task/execution` | subject | root (the one launch act) | `seon.task/ready`, via inheritance (Q1) | exists; inheritance is new |

**The effective task** (the owner's ruling, plain `merge`): `seon.task/effective` (new, pure over a pulled path) =
`(apply merge (concat (map content ancestors) [(content own)]))` from the root subject down
`:seon.task/children` to the step, where `content` is `select-keys` of the declaration's content members
(`:seon.task/intent`, `:seon.task/instructions`). A step's own keys overwrite inherited ones. Verified as plain data
at the REPL (agent plan-opus): a subject intent + a step's instructions merge to the step's view; a refined subject
intent shows in the successor's effective task with nothing else changed. Needs are **not** a merge path: the
predecessor's content reaches the successor as `:seon.context/chain` data (§2.3), because a predecessor's
instructions are its own step's and must not overwrite the successor's. Needs are already inherited down children by
`ready-rules` `needs*`; unchanged.

**Who writes what.**
- The file and ingestion (`seon.task/file-declarations`, `tasks-tx`): every declared key.
- The agent, on its branch only, through `my.task/refine!`: **intent → the task that declares the effective intent**
  (the subject, or the step if it declares its own); **instructions → its own step, or a successor's** (a task whose
  `:seon.task/needs` names the held task: the only cross-task write, and it is exactly the Prove/Design hand-over).
  Each write is `seon.task/create!` with the stored declaration as `:seon.task/basis`, as refine! does today.
- Root's merge (`seon.merge/merge!`): carries those task facts to main with everything else on the branch; a
  concurrent refinement of the same subject intent by two steps is an ordinary three-way coordinate (one-sided →
  taken; both → `:root` conflict, M4's territory).
- Publication (`seon.merge/publish!`, task-write-back phase A, lane in flight): writes the changed entries back into
  the task file in the merge's git commit. Adoption (phase B) stops reverting them. So a refinement is durable at
  merge + publish, and the next step, which forks main after the merge, reads it.

### 2.2 The lifecycle with zero hand acts after launch

| # | Event | Derived by (next-actions over main + roster) | State at HEAD | Change |
|---|---|---|---|---|
| L0 | root writes `:running` on the subject (the launch; counted separately) | — | each leaf needs its own `:running` | Q1: `ready` reads the nearest declared execution up `:children` (a `running*` rule like `needs*`); ingestion writes `:held` only on roots |
| L1 | first ready step assigned, branch forked from main | `plan` → `:attempt` + `:start-flow` | works (chains 5-8: 37-366 ms) | none |
| L2 | proofs run before the first model call (prove pass) | `seon.turn` | exists (M1) | none |
| L3 | the agent works; refine! when it learns | the agent | intent only | §2.1 |
| L4 | step done (`seon.context/step-status`) → end on branch → merge | `:merge` | works for an ordinary `:implement` (M8a); a Prove step merges facts only | **M7**: delete `needed?`; designed reds (`seon.context/designed-red?`) pass the gate |
| L5 | publish | `:publish` | works (M9) when declarations moved; task entries after phase A | none here |
| L6 | branch deleted | `:delete-branch` | works | none |
| L7 | successor ready (`leaf-done` reads `:merged`) and forks **main** | `plan` | throws if the predecessor's branch is gone | **M10**: delete the predecessor clause, `seed-attempt!`, `attempt-rows-tx`, `:seon.task.attempt/fork` |
| L8 | `:capped` or `:stalled` with work on the branch | — | terminal; root's problem task says "merge it" (K4) | **M12 as derived relaunch** (Q2): assignment n+1 forks the ended branch's commit (`:seon.task.attempt/relaunch-fork`), bounded by a dial; the relaunched agent reads its predecessor attempt's changes (`:seon.context/changes`, M14's derivation, only the read) |
| L9 | send-back to a predecessor | `leaf-done`'s `:sent-back` not-join makes the target not done → ready → new assignment; the sender waits on its need | derivation exists (UNVERIFIED live) | proof only |
| L10 | agent asks root a question | `my.message/send` to root, `my.turn/wait` | nobody answers unattended | Q3 |
| L11 | last step merged → subject done (`seon.task/done?` over children) | — | exists | none |

Not on the path and not needed for a two- or three-step serial chain: M4 (conflicts), M11 (update from main), M13's
one-starter cut, M15-M18. G8 (an end left on a hand-started branch) did not recur in chains 5-8 (ends copied in
41-122 ms).

### 2.3 What the model agent is TOLD (the owner's required part)

The context is the effective task rendered by the one resolver. Every key below is data in the current-state block;
every sentence below is a `(help)` line. A cold reader must be able to do the step from these alone.

**The block, every step** (`seon.context/task-sections`, new members in bold):

```clojure
#:seon.context{
 :task {:seon.task/sym …step… :seon.task/intent "<effective goal>" :seon.task/instructions "<this step's own work>"}
 :chain                                        ; absent for a task with no parent, needs or successors
 {:seon.context/subject {:seon.task/sym …subject…}            ; the goal's owner: refine! of intent writes it
  :seon.context/before [{:seon.task/sym …need… :seon.task/step :prove :seon.task/outcome :merged
                         :seon.context/merged {:seon.program/tests [t…] :seon.program/functions [f…]
                                               :seon.program/schemas [k…] :seon.task/tasks [s…]}}]
  :seon.context/after  [{:seon.task/sym …successor… :seon.task/step :implement
                         :seon.task/instructions "<what it will do with your product>"}]}
 :proofs …          ; unchanged: own tagged deftests with source, status, failures
 :step {:seon.task/step :prove :seon.task/status :working
        :seon.context/product-tests […]                        ; exists: red deftests tagged with a successor
        :seon.context/start {…}}                               ; Prove: the test namespace's in-ns as well
 :must-reads …      ; the intent's and instructions' references, resolved once
 :turns …}
```

`:seon.context/merged` is derived from the need's assignment's `:seon.task/merge-commit` by
`seon.cluster.source/merge-accepted` (the identities publication already derives; the write-back lane widens it to
tasks), grouped by family, each entry a name the resolver shows on request. Cost: one pull per need + one
`merge-accepted` per need (`d/since` of one commit). A need not merged cannot appear: the step would not be ready.

**Help: a new group "Your step in a chain"** (replacing nothing; every line teaches installed forms only, per
`seon.help-test/help-names-only-installed-forms`):

1. "A task may be one step of a chain. :seon.context/chain names its :seon.context/subject (the goal all its steps
   share, shown as your :seon.task/intent), the steps :seon.context/before you, which merged onto main before your
   branch forked, each with what it merged, and the steps :seon.context/after you."
2. "What a step before you merged is on your branch already: its tests, functions and schemas are ordinary entities;
   read them with (my.program/source 'ns/name). Rely on them; do not redo them."
3. "Your :seon.task/instructions are your step's own work; your :seon.task/intent is the goal. Do your instructions;
   keep the goal true."
4. "When your step is done, everything your branch holds merges onto main by itself: functions, tests, schemas and
   your task's facts. Then the step after you starts from main. Define nothing you do not mean to merge; remove a
   scratch definition with (my.program/ns-unmap! 'ns/name)."

**Help "Done", corrected per step** (the current line 5 is wrong):

- `:implement`: "Done is every proof in :seon.context/proofs green on your branch, and no test you broke. Proofs
  start red; turn them green by changing the functions they call."
- `:prove`: "A :prove step is done when it has written red deftests for the step after it and broken nothing. Tag
  each with the NAME OF THE STEP AFTER YOU from :seon.context/after, never your own:
  (deftest ^{:seon/tasks #{'the.ns/next-step}} names-the-case (is (= 3 (the.ns/f 2)))). A function they call that
  does not exist yet is a stub with its contract whose body throws
  (throw (ex-info \"not yet\" {:seon.error/type :seon.error/unimplemented})); such a red is designed and merges.
  Each red appears in :seon.context/step under :seon.context/product-tests."
- Both: "Your proofs and the tests reaching your change run by themselves after each eval request; there is no done
  call."

**Help "Ask root or give the task back", made chain-aware:**

- "Write what you learn into the task, as soon as you learn it: (my.task/refine! {:seon.task/intent \"…\"})
  rewrites the goal for every step of your chain; (my.task/refine! {:seon.task/instructions \"…\"}) rewrites your
  step's; (my.task/refine! {:seon.task/sym 'the.ns/next-step :seon.task/instructions \"…\"}) tells the step after you
  what you decided (a schema's name, an input shape, the stub to fill). Refine when you decide something the next
  reader would otherwise rediscover."
- "A step before you that left something wrong goes back to it: (my.task/send-back! {:my.task/reason \"…\"
  :my.task/target 'the.ns/earlier-step :my.task/counterexample {…}}). It runs again, then you do."
- The root question line follows Q3's answer.

**Help "Who you are" corrected:** "…they reach main by root's automatic merge when your step is done."

**Per-step cold-reader questions** (what the Haiku pre-check must answer KNOWN from the context, and what the
DeepSeek run must show):

| Step | Where am I | What must I produce | What did my predecessor produce / can I rely on | What is done | How to refine | How to send back / ask |
|---|---|---|---|---|---|---|
| Prove | `:chain :after` names the next step | red deftests tagged with that name; stubs with contracts | the subject intent's references | product-tests red-designed, own proofs (if any) green, nothing broken | intent (goal), the successor's instructions | send-back to subject owner = give back (no predecessor) |
| Implement | `:chain :before` names the Prove step, `:merged` | the functions the proofs call | `:merged` lists its tests and stubs, now on my branch | proofs green, nothing broken | intent; own instructions | send-back to the Prove step with the counterexample |
| Design (if Q4 b) | as Prove | schemas, contracted stubs, the successors' instructions | — | per Q4 | successors' instructions | — |

### 2.4 Exists / changes / deleted, and why

| Part | Exists | Changes | Deleted | Serves |
|---|---|---|---|---|
| task data | intent, references, children, needs, step | instructions back; intent optional on a child entry; references from both texts | — | 2, 3 |
| effective task | nothing (parent unread by context) | `seon.task/effective`; `seon.context/held-task` pulls the path and merges | `task-selector`'s single-entity view | 2 |
| chain section | needs resolved as must-reads with intent (`seon.context/task-references`) | `:seon.context/chain` | the needs clause in `task-references` (needs move to `:before`) | 1, 2 |
| refine! | intent on own task | intent → declarer; instructions own or successor | — | 3 |
| Prove product on main | facts-only for a needed step | M7 gate with designed reds | `needed?` and its comment in `seon.merge/merge!`; `seon.test/change-reds` with `accept-merge!`'s call (M7 row) | 1 |
| successor fork | from predecessor end commit; throws | from main | `fork-attempt!` predecessor clause, `seed-attempt!`, `attempt-rows-tx`, `:seon.task.attempt/fork`, `plan-facts` basis query (M10 row) | 1 |
| relaunch | none | derived, forking the ended commit | `ended-problem!`'s "merge it" intent for an unchanged capped/stalled end (K4) | 1 |
| help | 8 groups | chain group; Done per step; refine/send-back lines; "who you are" | the wrong Prove tagging line | 1, 2 |
| launch | per-leaf `:running` | inherited down children | ingestion's `:held` on child tasks | 1 |

## 3. Phases (smallest first; every phase: red proof first, cold-read pre-check, then the DeepSeek run as acceptance)

**Acceptance method for every phase that changes what agents are told, the tools or the lifecycle** (owner,
2026-10-12): (a) capture the first-call context of the run's task as round 4 did, with the assignment's prove pass
run so proofs show their real status; (b) a Haiku cold read of only that capture answers per §2.3's table, verdict
CAN DO or CAN DO WITH SEARCHING with the gaps named (cheap pre-check; a NO stops the paid run); (c) the paid run:
chain-experiment method (provider on, cap 40, one `:running` act, nothing by hand), graded on hand acts after
launch, calls, tokens, errors by signature, and whether each step reached `:done` (on its branch) and `:merged` (on
main). Subjects: small, pure, no prior failed attempts (chain-6 N4), red verified interpreted before launch.

| # | Phase (goal) | Red proof (deftest: data assertion) | Files | Deletes | Measure (the run) |
|---|---|---|---|---|---|
| **C1** | **Tell the truth about steps** (1, 2): Done per step; Prove tags the successor; "who you are" says automatic merge; the stub form. Help only, no data change | `seon.help-test/help-teaches-each-step-its-done` (new row in the help table test): the `Done` group's lines name `:seon.context/product-tests` and `:seon.error/unimplemented`; no help line pairs `:prove` with tagging "its task"; the refine line naming `:seon.task/sym` lands with C4, not here | `src/seon/bootstrap.clj`, `test/seon/help_test.clj` | the wrong line | Haiku: a cold reader of a Prove capture states the tag name rule correctly. Run: one **single Prove step** (a subject whose successor Implement task exists, held) reaches `:done` on its branch with 0 hand acts; product-tests are red-designed |
| **C2** | **The chain section** (1, 2): `:seon.context/chain` with subject, before (outcome, merged identities), after (step, instructions) | `seon.context-test/a-steps-block-names-its-chain`: a fixture subject with a Prove and an Implement child, Prove merged: the Implement block's `[:seon.context/chain :seon.context/before 0 :seon.context/merged :seon.program/tests]` holds the Prove step's deftest symbol; the Prove block's `:after` holds the Implement symbol and its instructions; a task with no chain has no `:seon.context/chain` key | `src/seon/context.clj`, `resources/seon/schemas/seon.context.edn`, `test/seon/context_test.clj`; reads `seon.cluster.source/merge-accepted` (file held by task-writeback-impl: call only) | needs in `seon.context/task-references` | Haiku: cold reader of the Implement capture answers "what did my predecessor produce" KNOWN. Run: after C5, the same as C5's |
| **C3** | **Effective task and instructions** (2, 3): `:seon.task/instructions` back; `seon.task/effective`; intent optional on a child entry; references from both texts | `seon.task-test/a-steps-effective-task-is-its-subjects-merged-with-its-own` (table: subject intent + step instructions → both; step's own intent overwrites; a refined subject intent is the step's next view); `seon.task-ingestion-test/…` row: a child entry with instructions and no intent ingests, a root entry with no intent refuses by name | `resources/seon/schemas/seon.task.edn`, `src/seon/task.clj`, `src/seon/context.clj`, `test/seon/task_test.clj`, `test/seon/task_ingestion_test.clj`; **after task-writeback-impl releases `seon.task.clj` and the schema** | `task-selector`'s one-entity pull | Haiku on a capture whose instructions differ from intent: reader distinguishes goal and step work |
| **C4** | **refine! writes the declarer** (3) | `my.task-test/a-refined-goal-is-the-next-steps-goal-after-merge`: Prove agent refines intent and the successor's instructions on its branch → merge → the Implement step's effective task holds both; the step's own entry is unchanged | `src/my/task.clj`, `resources/seon/schemas/my.task.edn`, `src/seon/bootstrap.clj` (refine lines), `test/my/task_test.clj` | — | measured in C6's run: the Implement agent's first calls do not rediscover what the Prove agent wrote (count calls before first defn, against chain 8's 22) |
| **C5** | **The Prove product merges** (1): M7 as filed (`seon.cluster.merge-test/a-prove-steps-designed-reds-pass-the-gate`; `a-merge-whose-reached-definition-moved-writes-nothing`) | as filed | M7 row | `needed?`, `change-reds` + caller, `red-test-refusal`, `declarations-held-tx` | Run: the C1 Prove step reaches `:merged`; main holds its red deftests and stubs; publication commits them |
| **C6** | **The successor forks main** (1): M10 as filed (`seon.cluster.agent/a-join-forks-main-after-both-needs-merge`, `a-prove-steps-red-product-merges-into-main`) | as filed | M10 row | as filed | Run: **two-step chain Prove → Implement**, one launch act, 0 after: both `:merged`, hand acts 0 |
| **C7** | **A capped or stalled step continues** (1): derived relaunch forking the ended commit, bounded by `:seon.config.run/relaunches` (Q2) | `seon.cluster.db-flow-test/a-capped-attempt-with-changes-relaunches-from-its-commit`: after one pass, assignment 2 exists, its branch's commit at fork equals assignment 1's end commit, and the relaunch count is a dial; a capped unchanged attempt with the dial at 0 has no assignment 2 and its problem intent names no `my.root/merge!` | `src/seon/task.clj` (`ready`), `src/seon/cluster/agent.clj`, `resources/seon/schemas/seon.config.run.edn`, `test/seon/cluster/db_flow_test.clj` | `ended-problem!`'s merge offer for an unchanged end; the hand relaunch path | Run: chain 7's subject (4 of 5 rows at cap) reaches `:merged` across ≤ 2 assignments with 0 hand acts |
| **C8** | **One launch act** (1): execution inherited down children (Q1) | `seon.task-test/a-running-subject-readies-its-first-step-only`: `:running` on the subject → `ready` = the Prove step; after its `:merged`, `ready` = the Implement step; a child's own `:held` keeps it held | `src/seon/task.clj`, schema description | ingestion's `:held` on child tasks | Run: C6's chain launched by one fact on the subject |
| **C9** | **Context fits the work** (2): printer never cuts inside an operation form; a referent resolved at top level is cited by name below; the display budget dial per Q5 | `seon.print-test/an-operation-form-is-never-cut-inside` (printer's own tests may assert text); `seon.ref-test/a-name-resolved-at-top-level-is-cited-by-name-below` (data: the nested entry holds only `:seon.ref/name`) | `src/seon/print.clj` (printer owner), `src/seon/ref.clj`, config | the repeated summaries | round-5 captures: the forgive capture shows `scan` whole (or Q5's alternative) and no `…1`; Haiku verdict CAN DO |
| **C10** | **The three-step chain** (all): Prove → Implement on a real Seon subject mined from the backlog, plus a refine! in step 1 read by step 2; `seon.cluster.agent/the-real-chain-runs-with-no-nudges` reshaped to this tree | as filed, asserted on: every step `:merged`, subject `done?`, task file entry written back with the refinement, 0 hand acts after launch | test only + task files | — | the goal's measure: hand acts 0, calls per step, tokens |

Order: C1 now (help only, file-disjoint). C2 beside it. C3 and C4 wait for `task-writeback-impl` to commit (it holds
`seon.task.clj`, the task schema and `seon.merge.clj`). C5 → C6 are the goal lane's (agent.clj, merge.clj, turn.clj
patches). C7 after C6 (agent.clj). C8 any time after C3. C9 beside, on the printer's owner. Runs: C1's single Prove
step as soon as C1 lands (no merge needed to grade `:done`); the two-step run after C6; C7's run on chain 7's subject;
C10 last.

## 4. What not to do

- **No `:design` step kind before a Prove run has failed for want of it** (Q4). Count first: 0 tasks carry a step.
- **Do not merge along `:needs`**: a predecessor's instructions would overwrite the successor's. Needs carry data
  into `:chain`, not into the task.
- **Do not show reaching tests or callers as content**, and do not add "relevant tests" to the chain section; tests
  enter as proofs, product-tests or a named reference (owner ruling).
- **Do not raise the call cap to buy progress.** Chain 8 stalled at 24 of 40; the cap was not the limit.
- **Do not trim intents or sources in a render to fit the budget**; the fixes are the printer's general rules and
  the dial (Q5).
- **Do not run M4, M11, M13's one-starter cut, M14's full diff UI, M15-M18 for chains.** Only M14's derivation is
  read, by the relaunched agent (C7).
- **Do not hand-relaunch on a store holding earlier attempts** (chain 7: P2/P4). Reset or wait for C7.
- **Do not pick subjects with failed history or design questions inside an Implement step** (chains 5, 6).
- **Do not add a retry around formless replies** (P12) until a run shows C1's help did not fix the planning-in-prose
  stall; then it is an owner ruling, not a lane's.
- **Do not fix `fork-attempt!`'s throw**: M10 deletes it.

## 5. Questions for the owner

**Q1. How does one act launch a chain?** (a) `:seon.task/execution` inherits down `:children` like needs (a child's
own value wins; ingestion stops writing `:held` on children). (b) A root verb `my.root/launch!` writes `:running` on
every leaf of a subject in one transaction. (c) Keep per-leaf `:running` and count it as one launch act.
**Recommend (a)**: it is the owner's merge rule applied to one more key, and it deletes code (the child `:held`).

**Q2. What continues a capped or stalled step?** (a) Derived relaunch: assignment n+1 forks the ended commit when the
branch changed something, up to a dial (`:seon.config.run/relaunches`, default 2); unchanged ends close with a root
task. (b) Root writes `:seon.task.attempt/relaunch` by hand (the earlier ruling: relaunch only by root's fact). (c)
No relaunch; raise the cap and relax the two-formless-replies stall. **Recommend (a)**: a relaunch is an ordinary
assignment, not a retry of the same execution; the bound is a dial; the work carries over (chain 7 had 4 of 5 rows).

**Q3. Who answers an unattended agent's question to root?** (a) Nobody: help says decide with the simplest option
consistent with the goal, record the decision by `refine!`, and send back only when the goal is impossible; a
message to root is for platform failures. (b) A root Opus agent answers root's inbox as a chain step. (c) The owner
answers; the chain waits (a hand act). **Recommend (a)** for this stretch (it is the owner's own "don't block on
questions" rule), (b) later.

**Q4. Is Design its own step?** (a) No: Design + Prove are one `:prove` step whose product is red deftests, contracted
stubs and registered schemas, and which refines its successor's instructions. (b) A `:design` kind with its own done
rule (its successors' instructions written and its schemas compiling), proofs authored by root. (c) A question task
whose answer schema is the design. **Recommend (a)** until a Prove run fails for want of a separate design; it needs
no new done rule.

**Q5. When the block exceeds the display budget, what gives?** (a) The budget dial is raised so a referent the
intent names prints whole (forgive's block ~9k tokens; DeepSeek's window is not the limit, cost is). (b) The block
keeps 6,144 and the printer prefers cutting nested references before top-level referents (a general shape rule:
deeper cuts first). (c) Task authors cite less. **Recommend (b), then measure**: it is a general printer rule, keeps
cost bounded, and shows `scan` whole in the forgive capture because its nested references are what the cut should
take.

**Q6. Can an agent refine its successor's instructions?** (a) Yes, only a task whose needs name its held task
(§2.1). (b) No; it refines the subject's intent only and the successor reads `:chain :before`. **Recommend (a)**: it
is the hand-over the Prove step must make (a stub's name, an input shape), and the merge already carries task facts.

## Evidence and limits

- VERIFIED this session: `seon.merge/merge!` (`needed?` path), `seon.cluster.agent/fork-attempt!` (predecessor
  clause, throw), `seon.task/ready` (terminal set, own `:running`), `seon.task/obligations` and
  `seon.context/step-status` (Prove product = successor-tagged; own proofs must be green), `seon.bootstrap/help-value`
  (the Prove line, the "root reviews" line), `seon.context/task-sections` (no chain section), `my.task/refine!`
  (intent only). Default counts (JVM, read-only): 808 tasks, 16 needs, 0 children, 0 step, 1 assignment ended
  `:capped`, 0 outcomes; health `:faulted`, 1 open fault (`seon.sci.eval/install-row!` "Unable to resolve symbol:
  refine-tasks", agent `task-writeback-impl`'s branch, a lane's probe). SCI probes (agent plan-opus): the need state
  of the needs task (`done? false`, its obligations empty) in 7 ms; the effective-task merge as plain data.
- UNVERIFIED: chain 9's report (not written); that a launched assignment's first block shows red proofs with
  failures (the M1 claim); whether `merge-tx`'s projection excludes program datoms for a facts-only merge (the
  comment says so); send-back relaunching its target live (L9); that `seon.cluster.source/merge-accepted` answers
  per-family identities cheaply for a need's merge commit (task-write-back.md says one `d/since` per commit).
- Not done: no source, test or task file edited; no paid run.

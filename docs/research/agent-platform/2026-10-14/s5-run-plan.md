---
title: "S5 run plan: one Design -> Prove -> Implement chain for seon.error/an-unresolved-alias-name-offers-its-nearest-core-name, live DeepSeek, 0 hand acts after chain!, one merge. Subject reproduced on default e1db21f25; the chain form, run sheet, grading forms and readiness checklist root ticks after the next restart"
type: research
status: ready (lane s5-prep, 2026-10-14); not run. Root runs it after the restart that installs the slices below
created: 2026-10-14
---

# S5 run plan

**Broken first (found while preparing; root decides each before launch).**

1. **Default cannot run any chain yet.** It booted `e1db21f25` (09:23 UTC). On it,
   `(seon.task/template database 'seon.step.design/template)` answers `:unknown-task` for all three templates
   (S4a-1 is `840d175a8`, later), and the filed subject task is not on main (filed in `0fec36eda`). A restart onto
   HEAD comes first.
2. **A filed plan with an unresolved reference deadlocks Design.** The filed entry's plan backticks
   `seon.agent/id`, which does not resolve (`seon.ref/references` gives one `:unresolved` problem). Design's handoff
   refuses `:references` for any problem in the plan, and refuses `:problem-changed` if the agent edits Problem.
   No Design could finish that plan. The filed plan also has no `## Problem` heading, so Design's `problem` section
   would be absent. This run therefore passes an explicit plan `p` (below): the filed text under `## Problem`, with
   its references made to resolve. **Gap for S3f-1:** `seon.task/from-filed` must put the entry's plan under
   `## Problem`, and a filed entry whose plan has unresolved references must be fixed in its file first. Ingestion
   already reports such problems; nothing refuses them.
3. **`seon.ref/references` reads an alias-qualified symbol inside a one-backtick form as an unresolved reference.**
   The cited forms were `(seon.error/reply-value … 'str/subs)` and `str/blankk`. TASK-CHAIN §3a says "an alias
   form is code". The plan below wraps forms in two backticks (a literal), which resolves cleanly. Owner:
   `seon.ref/candidate`. Not filed, because this lane commits docs only: **root files it** in
   `resources/seon/tasks/seon.ref.edn`.
4. **`my.root/merge-requests` reads main only.** It queries `[?a :seon.task/assignment]` on root's `:seon.db/db`,
   but chain attempts live on the chain branch (§1, "The attempt"). It cannot show a chain mid-run. The watch forms
   below read the branch. TASK-CHAIN §2 end says root's tools enumerate chain attempts on their branches. Check this
   after S3b-2. If it still holds, it is a finding, not a blocker.
5. **`:seon.config.run/relaunches` is still 2 on default.** Its deletion (§6) has not landed, so set it to 0 for
   the run, as rough run 1 did.
6. **There is no `my.root/run!` (S3c-1 is not in tonight's list).** A bail mid-chain leaves the task `:held` with no
   resume verb. Root's only options then are a new `chain!` with `:seon.chain/from` the old branch, or abandoning
   the run. Each counts as a hand act.

## 1. Subject

**`seon.error/an-unresolved-alias-name-offers-its-nearest-core-name`** (`resources/seon/tasks/seon.error.edn`).

Why this one over the other three:

- **Repro.** The reproduction is one read-only form, plus one SCI form on a scratch branch.
- **Size.** The fix is a few lines in one owner (`seon.error/nearest-calls` / `seon.error/reply-value`), with no
  schema resource change.
- **Proof row.** A ready table exists: `seon.error-test/a-guessed-name-is-answered-with-the-nearest-real-calls`
  (`clojure.test/are [guess expected] (some #{expected} (map first (nearest guess)))`). Prove adds one row,
  `'str/subs 'clojure.core/subs`.
- **Interpretable.** `nearest-calls`, `nearest-names`, `names-in` and `reply-value` all hold
  `:seon.fn/host-bound? false`, so a branch `defn` reaches the test in SCI.
- **No lane holds its files.** `bin/lane status` at 09:45 UTC showed s2a, s2b-1 and s3b, none of which touch
  `src/seon/error.clj` or `test/seon/error_test.clj`. Neither file is on the critical-path list.

Rejected:

- `my.program/source-answers-a-task-symbol`: `src/my/program.clj` is critical-path, held by the chain lanes; a
  write-back conflict would repeat rough run 1's failure.
- `seon.fn/lint-entities-answers-a-finding-without-a-position`: the function is private, the fix needs a schema
  resource change (host-bound publication), and the repro needs a crafted clj-kondo finding.
- `seon.sci.lookup/doc-shows-the-arglists-the-defn-declares`: the plan leaves the shape open ("marked or named"),
  and its example function `seon.cluster.source/tested-task-basis` is in a file s3b holds.

**Collision to hold off:** the errors cut (`errors-cut-plan.md`) owns `src/seon/error.clj` next. Root must not
let an errors-cut lane claim `src/seon/error.clj` or `test/seon/error_test.clj` until S5 merges.

**Reproduction, 2026-10-14 ~09:50 UTC, default pid 66128 (source `e1db21f25`).**

SCI, agent `s5-prep` on its own branch:

```clojure
(ns s5-prep.scratch (:require [clojure.string :as str]))
(str/subs "abc" 1)
;; => :error {:seon.error/offending str/subs, :seon.error/message "Unable to resolve symbol: str/subs",
;;            :seon.sci.eval/stacktrace [[s5-prep.scratch 1 1]]}            ; no :seon.error/nearest, 1 ms
(str/blankk "a")      ;; the same: no :seon.error/nearest, no :seon.error/in-namespace
(seon.agent/idd)      ;; contrast: :seon.error/in-namespace {seon.agent [...]} and 8 :seon.error/nearest forms
```

JVM, read-only. The second form shows the fix direction; it is not a prescription for Design.

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      reply (seon.error/reply-value database {:seon.error/message "Unable to resolve symbol: str/subs" :seon.program/not-found 'str/subs})
      program (map first (seon.db/q '[:find ?sym :where [?f :seon.fn/sym ?sym] [?f :seon.fn/arglists _]] database))
      core (map #(symbol "clojure.core" (name %)) (keys (ns-publics 'clojure.core)))]
  {:installed-nearest (find reply :seon.error/nearest)
   :widened (seon.error/nearest-names 'str/subs (concat program core) 8)})
;; => {:installed-nearest nil,
;;     :widened [clojure.core/subs clojure.core/str clojure.core/pr-str ...]}   ; 84 ms
;; with clojure.string's publics, 'str/blankk -> [clojure.string/blank?]
```

**Cause.** `seon.error/nearest-calls` draws its candidates only from program declarations
(`[?f :seon.fn/sym ?sym] [?f :seon.fn/arglists _]`), never from library namespaces. `names-in` queries the
namespace `str`, which is an alias, so it finds no entity. `calling-forms` reads arglists from the database only,
so a library candidate would print as `(clojure.core/subs)` with no arguments. Design should choose its arglists
(for example `(:arglists (meta (resolve sym)))`).

## 2. The chain form

`seon.task/from-filed` (S3f-1) is **not on HEAD** (`(resolve 'seon.task/from-filed)` gives nil), so use the
explicit vector. `seon.task/template` takes its database explicitly as installed. The first map takes the filed
task's identity, so S3f-1's write-back (when it lands) deletes the entry at merge. Run as root, JVM mode:

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database (seon.db/db connection)
      store (:seon.store/store (get @seon.operator.runtime/running-instances "default"))
      template #(seon.task/template database %)
      p "## Problem\n\nAn agent wrote ``(str/subs \"#the-goal\" 1)`` with `str` aliasing `clojure.string`, and the reply was \"Unable to resolve symbol: str/subs\" with no `:seon.error/nearest` at all; it retried six times (the Implement attempt of `docs/research/agent-platform/2026-10-14/live-chain-rough-1.md`). A misspelled program name such as seon.agent/idd does carry a `:seon.error/nearest` list, because the candidates `seon.error/reply-value` offers are program declarations only, never the library namespaces (`clojure.core`, `clojure.string`) agents call most. Reproduced on default 2026-10-14: ``(seon.error/reply-value database {:seon.error/message \"Unable to resolve symbol: str/subs\" :seon.program/not-found 'str/subs})`` holds no `:seon.error/nearest`, and neither does the reply for the misspelled str/blankk. Done when the reply for an unresolved name whose simple name a library namespace declares (`clojure.core/subs` for str/subs) offers that name in `:seon.error/nearest`, proven by a row in an existing table test of `seon.error/reply-value`; a name nothing resembles still has no key. Read `seon.error/reply-value`, `seon.error/nearest-names` and `seon.sci.eval/interpretation-error`."]
  (seon.task/chain! {:seon.db/connection connection :seon.store/store store}
                    [(merge (template 'seon.step.design/template)
                            {:seon.task/ns 'seon.error
                             :seon.task/name "an-unresolved-alias-name-offers-its-nearest-core-name"
                             :seon.task/plan p})
                     (template 'seon.step.prove/template)
                     (template 'seon.step.implement/template)]))
;; expected: {:seon.chain/branch :chain-<h> :seon.chain/first <h> :seon.task/handles [h h-2 h-3]}
```

The literal `p` is the filed plan under `## Problem`, with references fixed and the reproduction added. Checked on
default: `seon.ref/references` problems are `[]`, `(keys (seon.step/sections p))` is `("problem")`, and it resolves
`clojure.string :seon.error/nearest`, the run-1 note, `seon.error/reply-value clojure.core clojure.core/subs`,
`seon.error/nearest-names` and `seon.sci.eval/interpretation-error`.

If S3f-2 has landed, add `:seon.task/model "deepseek-flash"` to each map only if the cluster model differs. Absent
means the cluster's `:seon.config.ai/model`, which is `"deepseek-flash"` today.

**What was verified without running it.** `chain!` resolves. The context map validates against
`:seon.task/chain-context` (`malli.core/validate` true). `(seon.cluster.registry/branches store)` reads 70
branches. Main holds no chain fact. The installed model is `"deepseek-flash"` and `no-provider` is `true`.

**Why no dry run.** `chain!` writes the chain fact on main and S3a-1 (installed) starts the first task's agent from
it, so it cannot target default without starting agents. With no-provider on, those agents would take virtual
turns: harmless, but they would leave a chain fact and branch that root must abandon. A dry run belongs on a
disposable cluster after the restart: `bin/seon init s5-dry`, then the same form with `"s5-dry"`, which checks
fork, write, fact and first start with no provider. It is optional and is not in this lane's scope.

## 3. Run sheet (root)

1. **Restart onto HEAD** once every slice in §4 has landed: `bin/seon start --head`. Then `bin/seon status`:
   `:seon.operator/source-root` must name HEAD, and `:seon.boot/missing-layers []`.
2. **Tick §4.** Every row must answer as expected. A red row stops the run. Record which row failed.
3. **Lift no-provider and cap spending.** Write `tmp/s5-config.edn`:

   ```clojure
   {:seon.config.ai/no-provider :seon.config/absent
    :seon.config.run/max-episode-runs 40
    :seon.config.run/relaunches 0}
   ```

   Run `bin/seon config apply default tmp/s5-config.edn`, then verify:

   ```clojure
   (select-keys (seon.config/effective (seon.db/db (seon.cluster.boot/connection "default")) "default")
                [:seon.config.ai/model :seon.config.ai/no-provider :seon.config.run/max-episode-runs
                 :seon.config.run/relaunches :seon.config.agent/max-running])
   ;; expect model "deepseek-flash", no-provider absent or false, 40, 0
   ```

   Expect `converged? false` (C3, as before); trust `effective`.
4. **Note the launch instant**, then evaluate the §2 form once. That is the last hand act counted as setup.
5. **Watch** (JVM mode, every 30–60 s; append each value to the run note):

   ```clojure
   (let [main (seon.db/db (seon.cluster.boot/connection "default"))
         {b :seon.chain/branch h :seon.chain/first} (seon.db/pull main [:seon.chain/branch :seon.chain/first]
                                                                 [:seon.chain/first "<h>"])
         head (when b (seon.db/branch-database main b))]
     (try
       {:chain-fact? (some? b)
        :tasks (when head
                 (loop [t [:seon.task/id h] out []]
                   (let [row (seon.db/pull head [:seon.task/id :seon.task/name :seon.task/execution :seon.task/proofs
                                                 {:seon.task/next [:seon.task/id]}] t)]
                     (if (:seon.task/id row)
                       (recur (some-> row :seon.task/next :seon.task/id (->> (vector :seon.task/id)))
                              (conj out (dissoc row :seon.task/next)))
                       out))))
        :attempts (when head
                    (seon.db/q '[:find ?k ?n ?agent ?end ?outcome
                                 :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/number ?n]
                                 [?a :seon.task/agent ?agent]
                                 [(get-else $ ?a :seon.task.attempt/end :none) ?end]
                                 [(get-else $ ?a :seon.task/outcome :none) ?outcome]] head))
        :merge-requests (my.root/merge-requests {:seon.db/db main})
        :faults (seon.problems/open-faults main)}
       (finally (when head (seon.cluster.source/release! [head])))))
   ```

   Attempts on the branch include main's inherited attempts. A chain attempt is one whose `:seon.task/assigned` is
   in the handles. Filter by that if the set is noisy. Also watch `bin/seon logs default` for `turn proc is parked`
   and `write-refusal` (the rough run 1 failure).
6. **Stop.** To stop one task, transact `:seon.task/execution :stopped` on the chain branch through the writer.
   Rough run 1 showed `:stopped` had no visible effect on a parked turn; if so, use the next step. To abandon:
   restore no-provider first (step 7), then `my.root/close!` the open attempt with a reason, then retire the branch
   (`my.agent/retire-branch!` on `:chain-<h>`) and retract the chain fact. Each of these is a hand act; name it.
7. **Restore** right after the merge, or on any stop. Write `tmp/s5-restore.edn`:

   ```clojure
   {:seon.config.ai/no-provider true :seon.config.run/max-episode-runs 100 :seon.config.run/relaunches 2}
   ```

   Run `bin/seon config apply default tmp/s5-restore.edn`, then verify with the step 3 form.
8. **Merge outcome.** After the Implement attempt's `:done`, root's pass merges automatically (S3b-1, S3b-2).
   Expect: the chain fact gone, the branch gone, `src/seon/error.clj` and `test/seon/error_test.clj` written back
   (check `git status` and `git diff`), and, if S3f-1 has landed, the entry gone from
   `resources/seon/tasks/seon.error.edn`. If S3f-1 has not landed, root deletes the entry by hand: a hand act, named
   with mechanism S3f-1. Then re-run the §1 JVM form: `(find reply :seon.error/nearest)` must name
   `clojure.core/subs` (after root's reload) or, before reload, on a branch value.

### Grading (capture before the merge retires the branch)

Run this when Implement's attempt shows an end, before the pass's publication. If that is too late, read the
`:seon.agent/end-*` facts that `seon.context/end-summary-tx` records on each agent; they outlive the branch.

```clojure
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      head (seon.db/branch-database main :chain-<h>)]
  (try
    (vec (for [[agent-id] (seon.db/q '[:find ?id :where [?a :seon.task/agent ?id] [?a :seon.task/assigned ?t]
                                       [(contains? #{"<h>" "<h-2>" "<h-3>"} ?t)]] head)]
           (let [turns (seon.db/q '[:find ?turn (pull ?att [:seon.ai.usage/prompt-tokens :seon.ai.usage/cached-tokens
                                                            :seon.ai.usage/completion-tokens])
                                    :in $ ?aid :where [?ag :seon.agent/id ?aid] [?turn :seon.turn/agent ?ag]
                                    [?turn :seon.turn/attempts ?att]] head agent-id)
                 sum #(transduce (keep (comp % second)) + 0 turns)]
             {:agent agent-id
              :calls (count (into #{} (map first) turns))
              :prompt (sum :seon.ai.usage/prompt-tokens) :cached (sum :seon.ai.usage/cached-tokens)
              :completion (sum :seon.ai.usage/completion-tokens)
              :end (seon.db/pull main [:seon.agent/end-calls :seon.agent/end-prompt-tokens :seon.agent/end-red-proofs]
                                 [:seon.agent/id agent-id])})))
    (finally (seon.cluster.source/release! [head]))))
```

Grade into a table like rough run 1's. Per task: calls, prompt tokens (cached), completion, wall time (attempt
start to end, from the transaction instants of the attempt row and its end), agent forms, end, done-refusals seen.

- **Done-refusals seen.** These are the block's done refusals in each eval reply. Read them from the agent's
  transcript (`seon.render.transcript` page, or its evaluations on the branch) and list each distinct refusal type
  with a count.
- **Hand acts after `chain!`.** The target is 0. Name each one with the mechanism that should have removed it:

  | Hand act | Mechanism |
  |---|---|
  | advance | S3a-3 |
  | first turn without a wake | S3a-2 |
  | merge | S3b-1/S3b-2 |
  | entry deletion | S3f-1 |
  | resume | S3c-1 |
  | proof recorded under the task | S2a |
  | plan-only update running the proof | S2b-1 |

- **Context frictions** from the transcripts:
  - every `my.program/source` refusal;
  - every unresolved reference;
  - every nearest-less "Unable to resolve" (the defect under repair; count them before Implement's fix);
  - any block showing a merge refusal before work (D7);
  - repeated forms.
- **Errors by signature.** Use `seon.problems/open-faults` and the agents' error evaluations.
- **Final merge outcome.** Merged or refused (and the refusal type), the declarations merged, and the written-back
  diff, reviewed for flaws as rough run 1 did.

**Baseline, rough run 1:**

| Measure | Rough run 1 |
|---|---|
| Model calls | 38 (Prove 18 + 6, Implement 14; no Design) |
| Prompt tokens | 570k (310k cached) |
| Hand acts | 6 |
| Merges | 1 auto-merge mid-chain (Prove), Implement refused by a write-back conflict |

**Design prototype run 1:**

| Measure | Design prototype run 1 |
|---|---|
| Model calls | 12 |
| Prompt tokens | 217k |
| Wall time | 41 s |
| Hand acts | 0 |

**S5 passes:**

- 0 hand acts after `chain!`;
- exactly 3 agents on one branch;
- one merge;
- `clojure.core/subs` in the reply's `:seon.error/nearest`;
- the `seon.error-test` table green with its new row;
- no test green on main gone red.

## 4. Readiness checklist (after the restart, before §3 step 3)

Run these as root in JVM mode, with `(def database (seon.db/db (seon.cluster.boot/connection "default")))` and
`(def test? #(seq (seon.db/datoms database :avet :seon.test/sym %)))`. A slice is installed when its commit is an
ancestor of the boot source: `git merge-base --is-ancestor <slice-commit> <source-root commit>`. Its proofs must be
green on main: `(seon.context/current-results database #{'…})`, or root's central round after the restart.

| Slice | Form | Expected |
|---|---|---|
| boot | `bin/seon status` `:seon.operator/source-root` | HEAD with every slice below |
| S1c-2 `chain!` | `(some? (resolve 'seon.task/chain!))` | `true` (installed today) |
| S1d-2 `update!` | `(some? (resolve 'my.task/update!))` | `true` (installed today) |
| S2b-2 done rule | `(some? (resolve 'seon.cluster.source/done-refusals))` | `true` (installed today) |
| S3a-1 root reads chains | `(some? (resolve 'seon.cluster.agent/chain-facts))` | `true` (installed today) |
| S4a-1 templates | `(map #(:seon.task/proofs (seon.task/template database %)) '[seon.step.design/template seon.step.prove/template seon.step.implement/template])` | `(#{seon.step.design-test/handoff} #{seon.step.prove-test/handoff} nil)`; today all three answer `:unknown-task` |
| S2a task input | `(test? 'seon.task-test/a-tasks-proof-is-recorded-and-read-under-its-task)` and green on main; `(some? (resolve 'seon.test.runner/result-at-reach))` | seq, green, `true` |
| S2b-1 whole-branch selection | `(test? 'seon.test-test/a-plan-only-update-runs-the-tasks-proof)`, `(test? 'seon.test-test/an-earlier-deftest-on-the-branch-is-still-selected)`, both green | seq, seq, green |
| S3a-2 turn authority and context | the S3a-2 deftest (named in its commit) green on main; it proves the first model call happens with no wake and the block shows no merge verdict before work | green |
| S3a-3 advance | `(some? (resolve 'seon.task/advance-tx))` and `(test? 'seon.cluster.db-flow-test/a-two-task-chain-reaches-its-second-model-call)` green | `true`, green; today `advance-tx` is nil |
| S3b-1 final acceptance | `(test? 'seon.cluster.merge-test/a-chain-merges-once-and-closes-every-attempt)` green | green (e958b1700, which today's default lacks) |
| S3b-2 publication and retirement | the S3b-2 deftest (named in its commit) green: publication once, then branch, base and chain fact gone | green |
| S4b-design | `(some? (resolve 'seon.step.design/handoff))`, `(test? 'seon.step.design-test/handoff)` green on main | `true`, green; today nil. The Design template's only proof is this test: without it Design can never be done |
| S4b-prove | `(some? (resolve 'seon.step.prove/handoff))`, `(test? 'seon.step.prove-test/handoff)` green on main | `true`, green; today nil (s4b-prove's files are untracked in the tree) |
| subject still red | the §1 JVM form | `:installed-nearest nil` (so a green start does not end Implement trivially) |
| proof table present | `(test? 'seon.error-test/a-guessed-name-is-answered-with-the-nearest-real-calls)` green on main | green; Prove adds its row there |
| files free | `bin/lane status` | no lane holds `src/seon/error.clj` or `test/seon/error_test.clj` |
| config | §3 step 3 form | model `"deepseek-flash"`, `relaunches 0`, cap 40 |

Optional; each improves the run but none is required:

- **S3f-1** `from-filed` and entry deletion. Without it, the entry deletion is a named hand act.
- **S3f-2** `:seon.task/model`. Absent means the cluster model.
- **S3c-1** `my.root/run!`. Without it, a bail cannot resume without a hand act.

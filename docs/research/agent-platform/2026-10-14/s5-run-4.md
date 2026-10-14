---
title: "S5 run 4: for the first time a filed-task chain ran Design -> Prove -> Implement with 0 hand acts after chain!. Design was done in 18 DeepSeek calls (65 s) and Prove in 28 (110 s), and both advanced by themselves. Implement capped at 40 calls twice: first one step before changing seon.error/nearest-calls, then, after root's my.root/run!, on a dropped `require :as` alias in a program namespace. S3c-1's bail worked both times. 126 calls, 4.26M prompt tokens, no merge; config restored"
type: research
status: complete (lane s5-run-4 acting as root, 2026-10-14 12:04-12:16 UTC); Implement bailed twice, chain left held for root (no third run!)
created: 2026-10-14
---

# S5 run 4

Script: `docs/research/agent-platform/2026-10-14/s5-run-plan.md`, as amended by run 3. No stage proof or subject proof
was run on main; readiness was checked by resolving symbols and reading committed state only. Default pid 67366
booted at 12:02:57 UTC from `data/source/bdc09786a5b9…` (HEAD `bdc09786a`), with `:seon.boot/missing-layers []`.
Lanes `identity-stable` and `prove-reach` held their files throughout; no lane held `src/seon/error.clj` or
`test/seon/error_test.clj`.

**Broken first.**

0. **`require` with `:as` in a program namespace adds no alias, and says nothing.** This alone spent the second
   Implement attempt. I reproduced it on my own agent branch (`s5-run-4-probe`, since retired):

   ```clojure
   (in-ns 'seon.error)
   (require '[seon.test-support :as test-support])   ;; => nil
   (get (ns-aliases *ns*) 'test-support)              ;; => nil
   (require '[clojure.pprint :as pp])                 ;; => nil
   (get (ns-aliases *ns*) 'pp)                        ;; => nil
   (in-ns 'user)
   (require '[seon.test-support :as test-support])   ;; => nil
   (get (ns-aliases *ns*) 'test-support)              ;; => #namespace[seon.test-support]
   ```

   - The rerun attempt `e4eeaddcbaf3` evaluated `(require '[seon.test-support :as test-support] '[seon.config :as
     config] '[seon.db :as db] '[clojure.pprint :as pp])` and then `(test-support/with-database …)` about 15 times.
     Every time the answer was "Unable to resolve symbol: test-support/with-database": 28 errors in 139 evaluations.
   - The first attempt met the same probe as an explicit refusal ("Not admitted to the program … Cyclic load
     dependency: seon.error cannot require seon.test-support, which already requires seon.error"). The second got
     silence: the require answered `nil` with no error beside it.
   - Run 3 context defect 3 was this same defect (`(require '[seon.step :as step])` in `seon.error`).
   - Owner: the namespace-form handling of SCI evaluation in a program namespace. The alias is probably dropped
     because the require becomes a program `:seon.ns/aliases` change that is refused or never recorded. The REPL path
     is total: the refusal must come back beside the value.
1. **Implement used up its 40 calls before changing the producer.** The proof stayed red at Prove's run (12:07:50).
   - At the cap the branch held four new definitions in `seon.error`: `alias-target`, `library-names`,
     `library-calling-form` and `core-namespace`. It also held a scratch `dbv`, a database value (finding 4).
     `seon.error/nearest-calls` was unchanged. The agent's last comment named that change as its next step.
   - About 25 of the 40 calls were reading: roughly 45 `my.program/source`/`search` forms, plus ns-alias queries.
     The rest went on refusals it could not act on quickly (finding 2) and re-checking what was still defined after
     its own unmaps.
   - The cap does not fit the stage: Design needed 18 calls and Prove 28, and Implement had to read the same owners
     again from scratch.
2. **Refusals that cost Implement calls (attempt 1).** Each was correct, but none showed the way out. Exact
   excerpts:
   - `seon.db/q` called with the database first, `(seon.db/q dbv '[:find ?target . :where [?a :seon.ns.alias/local
     ?local] [?a :seon.ns.alias/target-ns ?target]] 'str)`: 5 identical refusals. Each began "seon.db/q refused
     arguments and return value: expected seon.db/q's :in does not match its supplied arguments (:seon.error/expected
     and :seon.error/offending). Every source input must be a database value. Use (seon.db/q query input ...) with
     the database elide…". That last sentence teaches the order. The agent missed it, because it comes after the
     `:in` complaint, which was about the wrong problem (its query also had no `:in`). It found the right form
     itself on the sixth call.
   - `defn- alias-target` with `{:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:maybe :symbol]]}` was
     refused: "Not admitted to the program: its program facts were refused, so any Var it bound exists in your SCI
     context only and my.program/history has no such declaration. seon.error/alias-target has a bare nilable return.
     Return an explicit result/error envelope, an em[pty collection …]". The agent rewrote it as `[:or …]` within 2
     calls. That refusal works as intended.
   - `(def failing (seon.test-support/with-database …))`, evaluated in `seon.error`, was refused twice with "Not
     admitted to the program … Cyclic load dependency: seon.error cannot require seon.test-support". This is a probe
     in the target namespace: the agent wanted a value, not a program fact. It still read `failing` afterwards,
     because the Var exists in its SCI context.
   - `my.program/search` given a list `'(str/subs)`: refused, "expected a string".
3. **Design's Approach passed its predicate and was wrong.** It says to widen `seon.error/nearest-calls`'s candidates
   with "the declarations of the namespace the unresolved alias resolves to, for ``(str/subs "abc" 1)`` the `subs`
   names of `clojure.string`". `clojure.string` declares no `subs`, and `seon.error/names-in` reads program entities,
   which do not include library namespaces. The Problem says to widen "to the simple name's other owners".
   - `seon.step.design/handoff` checks presence, citations, references and modeling, not correctness, so it passed.
   - Prove's test asserts the Problem's behaviour (`clojure.core/subs`), not the Approach. Implement worked out by
     itself that `clojure.core` is needed (`core-namespace`).
   - The cost was reading: Implement could not trust the plan.
4. **Scratch work became program entities.** Each of these is now in `(seon.step/changed head …)` on the chain
   branch, and the final merge would carry it or refuse it:
   - `(def dbv (seon.db/db))` in `seon.error`, now `seon.error/dbv`, a database value (attempt 1).
   - From attempt 2: `seon.error/config-expectation-present-generator`, `seon.error/testing-dump` (defined, then
     `my.program/ns-unmap!`ed, and still listed as changed), and a schema `:tmp/probe` from
     `(seon.schema/register! :tmp/probe :string)`.
   - Nothing tells the agent that its probes in the owner's namespace are program. `:seon.context/start` puts it in
     `seon.error`.
5. **Another lane's broken working file blocks every root eval reply.** Many of root's `eval_clj` replies came back
   with the PostToolUse hook error "BLOCKED: mcp__seon__eval_clj left unreadable Clojure on disk
   src/seon/test.clj:1850:80 Unmatched bracket". The file is another lane's uncommitted edit. My JVM-mode reads did
   not write it. The hook names the wrong cause: it blames the tool call it ran after.

## Readiness (symbols and committed state only)

| Row | Answer |
|---|---|
| boot | source-root `bdc09786a5b9…` = HEAD, missing-layers `[]` |
| resolve | `chain!`, `from-filed`, `chain-handles`, `done-refusals`, `advance-tx`, `design/handoff`, `prove/handoff`, `my.root/run!`, `my.root/close!`, `my.root/merge-requests`, `merge-chains!`, `finish-chains!`: all true |
| chain facts on main | `#{}` |
| templates | `(#{seon.step.design-test/handoff} #{seon.step.prove-test/handoff} nil)` (by symbol; by keyword `:design` they answer `()`) |
| subject still red | `(:seon.error/nearest (seon.error/reply-value main {… 'str/subs}) :absent)` gives `:absent` |
| filed entry | handle `f1f6dc8e6652`, entity 935077, `:held`; `seon.step/problems`: one `:unresolved` `seon.agent/id` |
| config after apply | model `"deepseek-flash"`, no-provider absent, 40, relaunches 0, max-running 4 (`converged? false`, C3) |

**Setup act, the same as run 3:** the filed plan's "The same error for `seon.agent/id` carried" became "The same
error for the misspelled program name seon.agent/idd carried". `seon.step/problems` then gave `[]`, and
`from-filed` put the plan under `## Problem` (sections `("problem")`).

## Run

- 12:05:03.765: `(seon.task/chain! context (seon.task/from-filed database s5r4-fixed))` answered in 244 ms with
  `{:seon.chain/first "f1f6dc8e6652" :seon.chain/branch :chain-f1f6dc8e6652 :seon.task/handles ["f1f6dc8e6652"
  "34ca6ac297bf" "c12a57408f49"]}`. The first task was `:running` and the other two `:held`.
- 12:05:04: Design attempt `9107c5cedfe0` started. By 12:05:22 it had made 6 model calls with no wake.
- 12:05:52: first `my.task/update!` with the taught form `(my.task/update! 'seon.error/… :seon.task/plan my.edit/text
  old new)`, which wrote at once. D6's fix holds. The reply's `:my.task/problems` named `str/subs`, because the agent
  had backticked `str/subs` in its Approach (run plan problem 3, `seon.ref/candidate`). Two calls later it removed
  that citation.
- 12:06:09.8: Design ended `:done`, 18 calls, 65 s. There was no `:problem-changed` refusal (D7's fix, 4463dda3f).
  Root's pass advanced it, and Prove started at 12:06:10.0.
- 12:06:10-12:08:00: Prove read the owners, probed `(str/subs "abc" 1)` through `test-support/fork-cluster-ctx`
  (red, no `:seon.error/nearest`), and wrote a new deftest
  `seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name`. The test has `are` rows
  `str/subs → clojure.core/subs` and `str/replace → clojure.string/replace`, an offending-symbol check, and the case
  "a name nothing resembles still has no key". Prove registered the test on Implement with the taught
  `(my.task/update! '…-3 :seon.task/proofs (fnil into #{}) '[…])`.
  - Prove ended `:done` at 12:08:00.999: 28 calls, 110 s.
  - Premortem Finding 3 did not fire: the test calls `seon.error/reply-value` directly, which is within depth 2.
- 12:08:01.3-12:10:57: Implement attempt `5ef1a6f0140d` ended `:capped` at 40 calls (see Broken first 1 and 2).
- By 12:11:25: root processed the bail once (S3c-1). Problem task `0e8838f841bf`
  (`seon.task/unfinished-task-5ef1a6f0140d`) was on main, with this plan: "Chain attempt 5ef1a6f0140d of task
  c12a57408f49 on branch chain-f1f6dc8e6652 bailed: The attempt ended :capped. It is :closed and the task :held; the
  branch and its chain fact are kept. Change the task … run it again with (my.root/run! {:seon.task/assigned
  \"c12a57408f49\"}), abandon the chain, or build a new one …".
  - On the branch: attempt `:seon.task/outcome :closed`, `:seon.task/reason "The attempt ended :capped."`, task
    `:held`. D8 is fixed.
- 12:11:52: config restored and verified. Root was messaged after each stage ended.
- Root chose option (a): run the held task again with the plan unchanged. Re-lifting the config is a setup act.
  - 12:13:4x: config re-applied and verified: no-provider absent, 40, relaunches 0.
  - 12:13:47: `(my.root/run! {:seon.task/assigned "c12a57408f49"})`, SCI as root (a `my.root/*` tool) on root's
    instruction. It answered `{:seon.task/assigned "c12a57408f49" :seon.chain/branch :chain-f1f6dc8e6652
    :seon.task/execution :running}` in 34 ms. Its reply's test step on main cost 1.83 GB and 955 ms and carried run
    3's `:seon.test/identity-unresolved` selection refusal on
    `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion`.
  - Attempt `e4eeaddcbaf3` started on the next pass, and its first model call came within 5 s.
  - Its block was 7,075 tokens and said nothing about the previous attempt (no `5ef1a6f0140d`, no `alias-target`,
    no sign of the helpers on the branch). It re-read about 20 owners.
  - From call ~12 on, it looped on the dropped alias (Broken first 0).
- 12:15:45.9: `:capped` at 40 calls, 2 min. It bailed once more: problem task `6119863c15d5`
  (`seon.task/unfinished-task-e4eeaddcbaf3`), attempt `:closed` "The attempt ended :capped.", task `:held`.
- 12:16:03: config restored and verified. Root's instruction was not to run it a third time.

**Watch, JVM-wide** (`ThreadMXBean` allocated bytes over all live threads, so it includes unrelated work):

| At (UTC) | Calls D/P/I | Threads | Allocated (cumulative) | Heap |
|---|---|---|---|---|
| 12:04:5x (before) | – | 54 | 40.8 GB | 1.36 GB |
| 12:05:22 | 6/–/– | 57 | 48.3 GB | 2.75 GB |
| 12:06:02 | 16/–/– | 60 | 59.8 GB | 1.82 GB |
| 12:06:34 | 18/8/– | 80 | 73.0 GB | 2.21 GB |
| 12:07:22 | 18/21/– | 81 | 80.0 GB | 2.08 GB |
| 12:07:49 | 18/27/– | 85 | 97.2 GB | 1.26 GB |
| 12:08:11 | 18/28/3 | 70 | 109.4 GB | 2.39 GB |
| 12:09:36 | 18/28/22 | 73 | 140.2 GB | 1.67 GB |
| 12:10:35 | 18/28/36 | 65 | 169.6 GB | 1.72 GB |
| 12:11:52 (after bail) | 18/28/40 | 68 | 206.0 GB | 2.78 GB |

That is about 165 GB over 86 model calls, roughly 1.9 GB per call (run 3 measured about 0.9 GB per turn on the turn
thread alone). There was no livelock: calls advanced at every watch.

Thread count rose by 20 when Prove started and fell by only 10 to 15 after each `:done`. Premortem Finding 4 (an
ended chain agent's graph is not stopped) may still hold in part. I did not verify it per thread.

## Grades

Read from `:seon.turn/attempts` usage on the branch (`s5r4-usage`).

| Task | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End | Done-refusals seen |
|---|---|---|---|---|---|---|---|
| Design `f1f6dc8e6652` | 18 | 349,471 (230,656) | 5,228 | 65 s (12:05:04 to 12:06:09.8) | 66 evaluations, 0 errors | `:done`, advanced | the block's `:test-evidence-error` (proof `:pending`); the agent read the handoff checks `:sections`, `:producer-not-cited`, `:data-not-modeled` from its first test step, then `:references [str/subs]` once |
| Prove `34ca6ac297bf` | 28 | 883,157 (686,464) | 7,730 | 110 s (12:06:10.0 to 12:08:01.0) | 47 evaluations, 6 errors | `:done`, advanced | `:test-evidence-error` at start |
| Implement `c12a57408f49`, attempt 1 | 40 (cap) | 1,992,738 (1,687,936) | 14,470 | 176 s (12:08:01.3 to 12:10:57) | 167 evaluations, 9 errors | `:capped`, then bailed: `:closed`, task `:held` | `:red-test-error` (its proof, both `are` rows), each turn |
| Implement, attempt 2 (`my.root/run!`) | 40 (cap) | 1,032,959 (773,504) | 10,642 | 118 s (12:13:47.8 to 12:15:45.9) | 139 evaluations, 28 errors | `:capped`, bailed again | `:red-test-error` each turn |
| **Total** | **126** | **4.26M (3.38M cached)** | **38.1k** | 7 min 51 s of attempts | 419 | | |

About 34k prompt tokens per call, 79% cached.

**Compared with:**

| Measure | Rough run 1 | Design prototype 1 | S5 run 1 | S5 run 2 | S5 run 3 | S5 run 4 |
|---|---|---|---|---|---|---|
| Model calls | 38 | 12 | 0 | 0 | 52 (Design only) | 126 (18 + 28 + 40 + 40) |
| Prompt tokens | 570k (310k cached) | 217k | 0 | 0 | 2.29M (1.94M cached) | 4.26M (3.38M cached) |
| Hand acts after `chain!` | 6 | 0 | 0 that moved the chain | 2, then abandonment | 1 (plus 2 failed tries), then abandonment | **0** |
| Furthest point | Implement (write-back conflict) | Design done | Design open, idle | Design prove pass, livelock | Design worked to `:problem-changed`, capped | **Implement worked; capped twice (one step short, then the alias loop); bailed to root cleanly both times** |
| Merge | Prove auto-merged, Implement refused | n/a | none | none | none | none (`merge-chains!`/`finish-chains!` still untested live) |

**Hand acts after `chain!`:** 0. Design and Prove advanced by themselves, and each of Implement's caps bailed by
itself. Root's `my.root/run!` is the designed resume verb (§2.7) and is counted as root's action, not a hand act.
Setup acts, not counted: the filed-plan reference fix, two config applies and two config restores.

**S5 pass criteria:**

| Criterion | Result |
|---|---|
| 0 hand acts | yes |
| 3 agents on one branch | yes |
| one merge | no |
| `clojure.core/subs` in the reply | no; `(seon.error/reply-value main …)` unchanged |
| table green with its new row | no. Prove chose a new deftest over a row in `a-guessed-name-is-answered-with-the-nearest-real-calls`, which the instructions allow ("Prefer a new row") |
| no main-green test gone red | not judged; nothing merged |

## Context, judged as the owner would

**First blocks** (`seon.context/current-state-ai` on the branch, estimated tokens = printed characters / 4):

| Stage | Total | task | chain | proofs | step | must-reads |
|---|---|---|---|---|---|---|
| Design | 4,611 | 654 | 503 | 441 | 172 | 2,763 |
| Prove | 6,910 | 1,169 | 196 | 375 | 144 | 4,947 |
| Implement | 7,042 | 1,006 | 48 | 596 | 169 | 5,142 |

Run 3's Design must-reads were about 6k for the run note alone. They are now 2.8k: the note shows its lead and
outline only.

What worked:

- Every stage's first model call came with no wake.
- `my.task/update!` ran exactly as the template teaches, both for the plan (Design) and for the next task's proofs
  (Prove).
- Implement's block showed the proof `:red` with both failing assertions as data (`:expected`/`:actual`), the plan
  with What exists and Approach, and `my.task/send-back!` as a must-read.
- No merge refusal appeared before any work.

Defects:

1. **Design's and Prove's first blocks show the proof as `:pending` with `:seon.source/test-evidence-error`**, not
   the stage predicate's failed checks. The attempt-start prove pass had recorded run `de2b8f4b0903` (Design) and
   `8d761bd678da` (Prove). The block read no current result for them: the result is recorded under the task identity
   but read without it (premortem Findings 2 and 5, being fixed by `identity-stable`).
   - The agent learned the checks from its first test step instead ("fails with exactly three refusals: `:sections
     …`, `:producer-not-cited …`, `:data-not-modeled`"). That cost no extra calls this time, because the handoff
     deftest now compares `(mapv (juxt :seon.step.design/check :seon.error/offending) …)`.
2. **`:seon.context/must-reads` show `clojure.string` and `clojure.core` with `:seon.repl/rows []`.** The two library
   namespaces the plan cites display no declarations. That is the very gap the task is about (names-in reads program
   entities only).
   - Implement then spent about 6 calls on `ns-publics` and `seon.sci.namespaces/sci-ns-publics*` to list
     `clojure.string`.
3. **Implement re-read every owner Design had already read.** Its block carried Design's What exists, but not the
   sources. About 20 `my.program/source` calls repeated Design's and Prove's reading. A must-read set built from the
   plan's What exists citations (sources of the producer and its callers) would replace them.
4. **Scratch values in the target namespace become program facts or refusals** (Broken first 2 and 4). The block's
   `:seon.context/start` is `[(in-ns 'seon.error)]`, so every probe lands in the owner's namespace. The instructions
   do not say where to probe, and nothing says that a `def` there is program.
5. **Done-refusal maps in the block carry `:seon.error/at`, `:seon.error/layer` and the test-run id**, which is noise
   for the agent. The `step` section is 144-172 tokens for one refusal.
6. **Design's predicate cannot tell a wrong Approach from a right one** (Broken first 3). Each later stage pays for
   that in reading. This needs no fence. Prove's red proof, built from the Problem, is the real check of the
   Approach, and it held here.
7. **Root's own path:**
   - `seon.context/current-results` wants a set; run 3 found `seon.test.runner/current-results` refuses a set. The
     two disagree.
   - A JVM-mode ClassCastException ("String cannot be cast to Number") still comes back with no form position
     (run 3 finding).
   - The run plan's watch form joins `:seon.task/assigned` as a ref, but it is a handle string. The form as written
     fails.
   - Root's `my.root/run!` reply ran a test step on main, 1.83 GB and 955 ms, with the stale selection refusal.
   - Returning config to its defaults showed `:seon.cost/entrance` 1.39 GB and 586 ms on the next one-key read
     (reload of the applied config).

**Nearest-less "Unable to resolve" (the defect under repair):** `test-support/with-database` and `sci/resolve`
(Prove) both carried nearest lists. The agents never typed `str/subs` themselves. Prove's probes reproduced the
defect through `test-support/agent-value`.

## Errors by signature

| Signature / type | Where | Count |
|---|---|---|
| `seon.db/q` "`:in` does not match its supplied arguments" (database passed first) | Implement | 5 |
| Not admitted: Cyclic load dependency `seon.error` → `seon.test-support` (probe `def` in `seon.error`) | Implement | 2 |
| Not admitted: "bare nilable return" `seon.error/alias-target` | Implement | 1 |
| `my.program/search` argument 1 expected a string | Implement | 1 |
| `seon.schema/call-with-projection-state :malli.core/invalid-schema-at-call: the refusal could not be built: Symbol cannot be cast to Keyword` (`seon.sci.eval/context-namespace` probe) | Prove | 2 |
| Unable to resolve symbol (`test-support/with-database` before `in-ns 'seon.error-test`, `sci/resolve`, `clojure.core/upper-case`) | Prove | 3 |
| ArityException `clojure.test/is` (5 args) in its first deftest draft | Prove | 1 |
| open faults on main | before and after | 2 (both from before) |

The "refusal could not be built" error is Seon's own: a refusal builder that throws a ClassCastException is a defect
at its producer (`seon.schema/call-with-projection-state`).

## Residue on default

- Chain `:chain-f1f6dc8e6652` and its chain fact are kept by design (§2.7). Problem tasks `0e8838f841bf` and
  `6119863c15d5` are on main. Task `c12a57408f49` is `:held`. Branch definitions: the Design plan, Prove's deftest,
  Implement's four helpers, and the scratch entities of Broken first 4. Root decides whether to abandon the chain or
  rebuild from it.
- JVM `user` namespace: `s5r4-conn`, `s5r4-filed`, `s5r4-fixed`, `s5r4-chain`, `s5r4-launch-at`, `s5r4-watch`,
  `s5r4-jvm`, `s5r4-evals`, `s5r4-usage`, `s5r4-ev`, `s5r4-ev2`, `s5r4-u1`, `s5r4-u2`, `s5r4-block-1..3`,
  `s5r4-design-plan` (host helpers, not program).
- One SCI eval as root on default: `my.root/run!`, on root's instruction. Agent `s5-run-4-probe` reproduced the
  alias defect on its own branch, `:agent-c0a600208bcb`, which `my.agent/retire-branch!` has since retired (it answered
  `:retiring? true`).
- Config: no-provider `true`, `max-episode-runs` 100, `relaunches` 2, model `"deepseek-flash"`, verified with
  `seon.config/effective` at 12:16:03.

## To file (this lane commits docs only)

- `require :as` in a program namespace adds no alias and answers `nil` with no error (Broken first 0; reproduction
  above). This is first: it alone spent attempt 2.
- A rerun attempt's block says nothing about its task's earlier attempts, or about the definitions they left on the
  branch. Owner: `seon.context/task-sections` (the task's attempts are one hop from it).

- The turn cap for Implement is too small for a stage that must re-read what earlier stages read. Measure first, then
  decide between a per-template budget and must-reads that carry the What exists sources (context defect 3). Owner:
  `seon.context/task-sections` must-reads, or `:seon.config.run/max-episode-runs` per task.
- Library namespaces render as empty must-reads (`:seon.repl/rows []` for `clojure.core`, `clojure.string`). Owner:
  the namespace must-read render (`seon.sci.lookup/namespace-documentation`).
- `seon.db/q`'s refusal for a database passed first should name the argument order. Owner: `seon.db/q`'s input
  refusal.
- A scratch `def` of a value in a program namespace becomes a program entity (`seon.error/dbv`). Decide where probes
  belong (the instructions, or a scratch namespace in `:seon.context/start`).
- `seon.schema/call-with-projection-state`'s refusal builder throws "Symbol cannot be cast to Keyword". Reproduce
  with Prove's form, `(seon.sci.eval/context-namespace ctx 'user)` inside `test-support/with-database`.
- The bin/seon-hook PostToolUse check blames `eval_clj` for another lane's unbalanced working file.
- Thread count after a chain stage's `:done` (premortem Finding 4, part 2): verify per graph.

## Run 4b: third Implement attempt

Lane `s5-run-4b` acted as root, 12:27-12:36 UTC. Default pid 77190 booted at 12:26:13 from `data/source/14e881c6ba3c…`
(HEAD `14e881c6b`), with `:seon.boot/missing-layers []`. Fixes live since run 4: 11f8c3263 (a cycle-closing require
answers its refusal), 14e881c6b (a rerun's block shows the earlier attempts and the branch's changed declarations),
3b900522d, 65f4c292e and 064809752.

**Broken first.**

0. **Attempt 3 stalled on Seon's own error and never ended.** The run stopped there; nothing merged.
   - At turn 31 the agent evaluated these forms in `user`, as the new cycle refusal advises ("Require it from a
     namespace it does not reach, such as user or a test namespace"):

     ```clojure
     (in-ns 'user)
     (require '[seon.test-support :as test-support] '[seon.config :as config] '[seon.error :as error] '[seon.db :as db])
     (test-support/with-database
       (fn [connection]
         (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "npe-probe"})
         (let [database (db/db connection)]
           (try (count (error/nearest-calls database 'str/subs nil))
                (catch Throwable t {:message (.getMessage t) :stack …})))))
     ```

   - The third form evaluated, and its value class was `java.lang.Integer`. Its stored `:seon.eval/private` holds 18
     platform dynamic Vars: `clojure.test/*test-out*`, `sci.ctx-store/*ctx*`, `seon.db/*conn*`, `seon.db/*open*`,
     `seon.schema/*projection*`, `seon.sci.eval/*expected-identities*` and others. None of them is the agent's.
   - Turn 32 (`137720212e95`) opened at 12:30:00.002 and closed at 12:30:00.193 with no model call.
   - Fault `ec89d9a4fbaf…` (signature `ec89d9a4fbaf7c32bbbc593a07680696907c5c0dc7ea7311a434c6ac569d3af7`) is on the
     chain branch:
     - type `:seon.turn.loop/phase-failed-error`, failed step `seon.repl/def-note`, caller
       `seon.repl/response-entries (repl.clj:209)`;
     - message: "seon.repl/def-note refused argument 0 `private`: expected a set, got a vector. Fix: Supply a set.";
     - `:seon.error/expected` `[:maybe [:set :qualified-symbol]]`. The `:seon.error/offending` value is a vector
       wrapping the vector shown next.
   - The stored value, as the reply's pull returns it: `:seon.eval/private` `[clojure.test/*test-out*
     sci.ctx-store/*ctx* sci.lang/*expansion-observer* sci.lang/*write-observer* seon.batch/*world* seon.db/*conn*
     seon.db/*open* seon.db/*read-database* seon.db/*read-evidence-sink* seon.db/*receipt*
     seon.effect/*request-context* seon.print/*budget* seon.schema/*candidate-forms-overlay* seon.schema/*packaged-forms*
     seon.schema/*projection* seon.schema/*projection-state* seon.schema/*registration-admission-source*
     seon.sci.eval/*expected-identities*]`.
     - `seon.turn.batch` produces a set (`(into #{} …)`, schema `[:set :qualified-symbol]`).
     - The reply reader pulls the attribute back (`seon.repl/cost-attributes` names it), and a cardinality-many pull
       answers a vector.
   - After the fault the attempt's turn proc sat idle in the thread dump. The attempt had no
     `:seon.task.attempt/end`, so root's pass saw nothing to bail, and 8 turns were left. Watched until 12:33:26.
   - This breaks three rules:
     - the REPL path is total: a later stage's failure is attached beside the value, and here it ended the agent's
       loop instead;
     - every end that is not done bails to root (§2.7): this one wrote no end, so the attempt stays open
       indefinitely;
     - the eval's private set is classified wrong: it holds the platform's dynamic Vars.
   - Owners: `seon.repl/response-entries` and its pull (to fix the vector), the turn loop's phase failure (to give the
     attempt an end), and the private classification in `seon.turn.batch`.
   - On root's instruction, at 12:35:24 I closed it with `(my.root/close! {:seon.task/assignment "68d331fe623f"
     :seon.task/reason "stalled: reply-stage :seon.turn.loop/phase-failed-error, fault ec89d9a4fbaf, no end
     written"})`. It answered `:outcome :closed` in 49 ms; its reply's test step on main cost 2.11 GB and 943 ms. At
     12:35:34 the attempt read `:closed`, the task `:held`, `my.root/merge-requests` `[]`, and the chain fact was
     kept. `close!` wrote no problem task. The chain was not run a fourth time.
   - **Hand act 1** (mechanism: a failed reply stage ends the attempt). Today it needs root's `close!`.
1. **`my.root/merge-requests` lists the running attempt as a merge request.** It offers `(my.root/merge! …)` and
   `(my.root/close! …)` for `68d331fe623f` while that attempt is still working. With the task `:held`, the list
   was `[]`.
2. **Root's own path, again:**
   - `my.root/run!`'s reply ran a test step on main: 1.61 GB, 759 ms, with the stale
     `:seon.test/identity-unresolved` refusal on `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion`.
   - A JVM-mode ClassCastException still gives no form position. Twice in my own watch forms: a Keyword joined as an
     entity id, and a ref map sorted.
   - The Design and Prove tasks still read `:seon.task/execution :running` after their attempts ended `:done`.

**Run.**

- 12:27:4x: chain `:chain-f1f6dc8e6652` and its chain fact were present. Task `c12a57408f49` was `:held`, with
  attempts `5ef1a6f0140d` and `e4eeaddcbaf3` both `:capped`/`:closed`.
- 12:27:51: `bin/seon config apply default tmp/s5-config.edn`. Verified with `seon.config/effective`: model
  `"deepseek-flash"`, no-provider absent, 40, relaunches 0, max-running 4. The apply reported
  `converged? false` (C3).
- 12:28:02.8: `(my.root/run! {:seon.task/assigned "c12a57408f49"})` in SCI as root. It answered `:running` in 86 ms.
- 12:28:04.1: attempt 3 `68d331fe623f` (agent `attempt/68d331fe623f`) made its first model call.
- Turns 1-14, to about 12:29:15: re-read the plan, the proof and the helpers it found under `:seon.context/changes`.
  Turn 1 already read `alias-target` and `core-namespace` by name.
- Turns 15-30: fixed `alias-target`'s wrong `:qualified-symbol` result contract and wired the widened candidates into
  `seon.error/nearest-calls`. New branch declarations: `seon.error/alias-candidates` and
  `seon.error/candidate-calling-form`. The proof then went from an empty `nearest` to a NullPointerException
  ("Cannot invoke clojure.lang.IObj.withMeta") in `library-calling-form`.
- Turn 31: the probe above. Turn 32 hit the stall.
- 12:33:21: `bin/seon config apply default tmp/s5-restore.edn`. Verified at 12:33:26: no-provider `true`, 100,
  relaunches 2. Root was messaged.

**Watch, JVM-wide** (cumulative allocated bytes of live threads; the count falls when threads end):

| At (UTC) | Implement turns | Threads | Allocated | Heap |
|---|---|---|---|---|
| 12:27:5x (before) | – | 47 | 42.4 GB | 1.97 GB |
| 12:28:1x | 1 | 52 | 47.5 GB | 1.32 GB |
| ~12:29:2x | 14 | 55 | 58.8 GB | 2.30 GB |
| ~12:29:5x | 23 | 54 | 77.1 GB | 2.46 GB |
| ~12:30:1x | 32 (stalled) | 52 | 112.6 GB | 2.38 GB |
| 12:31:23 | 32 | 50 | 107.9 GB | 1.16 GB |

About 65 GB went over 31 calls, roughly 2.1 GB per call. After the stall the JVM was idle: 133 MB and 380 ms of CPU
over 35 s. There was no livelock.

**Grading form** (JVM mode; the agent's id is read from the attempt):

```clojure
(let [main (seon.db/db (seon.cluster.boot/connection "default"))
      head (seon.db/branch-database main :chain-f1f6dc8e6652)
      aid  "attempt/68d331fe623f"]
  (try
    (let [turns (seon.db/q '[:find ?turn (pull ?att [:seon.ai.usage/prompt-tokens :seon.ai.usage/cached-tokens
                                                     :seon.ai.usage/completion-tokens])
                             :in $ ?aid :where [?ag :seon.agent/id ?aid] [?turn :seon.turn/agent ?ag]
                             [?turn :seon.turn/attempts ?att]] head aid)
          sum #(transduce (keep (comp % second)) + 0 turns)
          evals (seon.db/q '[:find ?e ?err :in $ ?aid :where [?ag :seon.agent/id ?aid] [?turn :seon.turn/agent ?ag]
                             [?e :seon.cluster.eval/run ?turn] [(get-else $ ?e :seon.cluster.eval/error :none) ?err]]
                           head aid)]
      {:calls (count (into #{} (map first) turns))
       :prompt (sum :seon.ai.usage/prompt-tokens) :cached (sum :seon.ai.usage/cached-tokens)
       :completion (sum :seon.ai.usage/completion-tokens)
       :evals (count evals) :errors (count (remove #(= :none (second %)) evals))})
    (finally (seon.cluster.source/release! [head]))))
;; => {:calls 31, :prompt 727316, :cached 521600, :completion 7488, :evals 95, :errors 6}
```

| Task | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End |
|---|---|---|---|---|---|---|
| Implement, attempt 3 (`68d331fe623f`) | 31 | 727,316 (521,600) | 7,488 | 1 min 56 s of work (12:28:04.1 to 12:30:00.2), then stalled | 95 evaluations, 6 errors | no end: stalled on `seon.repl/def-note` (Broken first 0); `:closed` by root at 12:35:24 |

Errors in attempt 3:

| Count | Error |
|---|---|
| 3 | Not admitted. One is a cycle `seon.error` → `my.task`, one a cycle `seon.error` → `seon.test-support`, both now answered with their way out. The third is "`seon.error/candidate-calling-form` has a bare nilable return". |
| 1 | `seon.error/alias-target` refused its result, `clojure.string` (not namespaced) |
| 1 | `seon.error/alias-target` given a non-database first argument |
| 1 | `my.program/search` given a non-database first argument |

Calls averaged about 23k prompt tokens, 72% cached.

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | S5 run 4 (D, P, I1, I2) | **Run 4 + 4b (adds I3)** |
|---|---|---|---|
| Model calls | 38 | 126 | **157** (18 + 28 + 40 + 40 + 31) |
| Prompt tokens | 570k (310k cached) | 4.26M (3.38M cached) | **4.99M (3.90M cached)** |
| Completion tokens | – | 38.1k | **45.6k** |
| Hand acts after `chain!` | 6 | 0 | **1**: `my.root/close!` of the stalled attempt (mechanism: a failed reply stage ends the attempt). Root's two `my.root/run!` are the designed resume (§2.7), not hand acts. |
| Furthest point | Implement (write-back conflict) | Implement capped twice | **Implement wired the producer; proof still red (NPE); stalled on a reply-stage contract error** |
| Merge | Prove auto-merged, Implement refused | none | **none.** `merge-chains!`/`finish-chains!` are still untested live. |

The S5 pass criteria are unchanged from run 4: no merge, no `clojure.core/subs` in the reply, the table not green.
The post-merge check, `(str/subs "abc" 1)` in a fresh agent, was not reachable.

**Context, judged.** The block was read by JVM mode at about turn 19, not captured before the first call. It is the
raw `seon.context/current-state` on the branch, in estimated tokens (pr-str characters / 4), so it is not the AI view
run 4 measured.

| task | chain | proofs | step | attempts | changes | must-reads | cost | turns | total |
|---|---|---|---|---|---|---|---|---|---|
| 1,011 | 48 | 596 | 169 | 79 | 560 | 5,108 | 162 | 22 | ~7,750 |

- **Changes helped.** It lists `alias-target`, `core-namespace`, `library-names` and `library-calling-form` with their
  docs, and `dbv`, `config-expectation-present-generator`, `testing-dump` and `:tmp/probe` as junk.
  - Turn 1 read the helpers by name. By turn 10 the agent stated "the before-steps built `alias-target`,
    `core-namespace`, `library-names`, `library-calling-form`, but `nearest-calls` was never widened".
  - Attempts 1 and 2 never reached that change. This attempt changed `nearest-calls` within about 20 calls.
  - The section shows `seon.error/testing-dump` as `:unresolved` ("Nothing of this name … exists on your branch"),
    though it is listed as changed. The unmapped name should read as a deletion.
- **Attempts helped only a little** (79 tokens). It says two attempts ended `:capped` at 40 calls, and the agent
  budgeted around that ("I have 28 turns left and 2 capped attempts already. Let me be efficient").
  - It carries no attempt id and nothing of what the earlier attempts learned (their last comment or next step).
  - Their last comments would have saved about 10 calls of re-reading: the agent said "I have the full picture now"
    in about 8 consecutive turns.
- **The junk was not removed.** The agent never unmapped `dbv`, `config-expectation-present-generator` or
  `:tmp/probe`. Nothing in the block says junk would merge or be refused, so a merge would have carried or refused it.
  Not judged, because nothing merged.

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact. Task `c12a57408f49` is `:held`, and attempt `68d331fe623f` is
  `:closed` with root's reason (it has no `:seon.task.attempt/end`).
- Branch declarations: earlier ones plus `seon.error/alias-candidates`, `seon.error/candidate-calling-form` and a
  changed `seon.error/nearest-calls`.
- Fault `ec89d9a4fbaf…` is on the branch.
- JVM `user` host helpers: `s5r4b-conn`, `s5r4b-watch`, `s5r4b-jvm`, `s5r4b-agent`, `s5r4b-a3`, `s5r4b-turns`.
- Two SCI evals as root, both `my.root/*` tools: `run!` and `close!`.
- Config was restored and verified.

**To file (this lane commits docs only):**

- **Fix first (Broken first 0):** a reply-stage contract error ends an agent's loop with no attempt end. The fix
  belongs at the producer: `seon.repl/response-entries` reads a cardinality-many pull as a vector, and the turn
  loop's phase failure must give the attempt an end (§2.7). Reproduce with the turn 31 forms above in `user` on a
  disposable agent.
- `:seon.eval/private` classifies the platform's dynamic Vars, which a fixture binds, as the agent's private Vars.
  Owner: `seon.turn.batch`.
- `my.root/merge-requests` lists a running attempt (Broken first 1).
- `seon.context/attempts` should carry each earlier attempt's last comment or next step.
- `seon.context/changes` should show an unmapped name as a deletion, not `:unresolved`.

## Run 5: fourth Implement attempt, refused at launch

Lane `s5-run-5` acted as root, 17:12-17:16 UTC. Default pid 1083 booted at 17:10:44 UTC from
`data/source/7e2546112915…` (HEAD `7e2546112`), with `:seon.boot/missing-layers []`. No lane held any claim.

**Broken first.**

0. **The attempt was never written, so no agent ever started. There were 0 model calls.**
   - 17:12:50: `(my.root/run! {:seon.task/assigned "c12a57408f49"})` in SCI as root answered `{:seon.task/execution
     :running}` in 90 ms. Its reply's test step on main cost 1.40 GB and 756 ms, the fourth time this has been seen.
   - In the same pass (17:12:50.041), fault `482f7508e3d9…` was raised: `seon.cluster.agent/start-claimed-flow!`,
     "start-flow! refused: no such agent in facts.", `:seon.agent/id "attempt/64b05977e3eb"`. The call path was
     `run-pass!` → `apply-actions!` → `start-chain-attempt!` → `start-branch-flow!` → `start-flow!`.
   - `attempt/64b05977e3eb` is `(seon.id/id ["c12a57408f49" 4])`. The pass planned attempt 4.
   - Neither main nor the chain branch holds that agent or attempt `64b05977e3eb`.
     `(seon.task/attempt-tx head "c12a57408f49" "attempt/64b05977e3eb" :chain-f1f6dc8e6652)` answers `[]`.
   - **Cause:** two readers disagree on whether an attempt is open.
     - `seon.task/next-attempt` treats the latest attempt as open when it has no `:seon.task.attempt/end`. On the
       branch it answers `[3 "68d331fe623f"]`, which is attempt 3 itself.
     - Run 4b's `my.root/close!` wrote `:seon.task/outcome :closed` and a reason on attempt 3, but no
       `:seon.task.attempt/end`.
     - So `seon.task/attempt-tx` finds attempt 3's row and answers `[]` ("already written").
       `seon.cluster.agent/write-chain-attempt!` then writes neither the agent's creation nor the attempt.
     - The planner reads attempt 3 as ended (by its outcome) and goes on to start attempt 4's graph for an agent
       nobody created.
   - Owner: one definition of "open attempt".
     - Either `seon.task/next-attempt` reads the outcome, as the planner does: `:closed`, `:done` and `:merged` are
       ended.
     - Or `my.root/close!` writes the attempt's end as every other end does.
     - Run 4b's hand act 1 (the stalled reply stage) is what led to this. Fixing that stall at its producer
       (attempts end by themselves) removes most closes, but `close!` is still a designed verb and must leave a
       runnable task.
   - Regression: a chain task whose latest attempt `my.root/close!` closed is run again with `my.root/run!`. The
     next pass writes attempt `n+1` and its agent on the chain branch, and its graph starts.
   - State left: task `c12a57408f49` reads `:seon.task/execution :running` on the chain branch with no open
     attempt. Nothing bails it (§2.7 needs an attempt end), and no fault repeated (count 1). The JVM was idle:
     35 threads, heap about 1 GB.
1. **`my.root/run!` answers `:running` before anything has started.** Its answer is the intent it wrote, so the
   refusal above reached root only as an open fault. Once `run!` had been answered, nothing in any reply carried it.

**Run.**

- 17:12:2x: chain `:chain-f1f6dc8e6652` and its chain fact were present. Task `c12a57408f49` was `:held`, with
  attempts 1-3 all `:closed`. Main also holds a second chain fact, `:chain-e439df9798c6`, from another lane, with
  open fault `seon.cluster.store/refuse!` "branch :chain-e439df9798c6 already has a connection in this process"
  (3 occurrences, 14:54 UTC, before this boot).
- 17:12:39: `bin/seon config apply default tmp/s5r5-config.edn` (no-provider absent, 30, relaunches 0). Verified
  with `seon.config/effective`: model `"deepseek-flash"`, no-provider absent, 30, relaunches 0, max-running 4. The
  apply reported `converged? false` (C3).
- 17:12:50: `run!`, then the refusal above.
- 17:13:05, 17:13:35, 17:13:45: no new attempt, 31 turns (attempt 3's), no main outcome. Alloc stayed flat at
  34.4-34.5 GB cumulative and threads at 35.
- 17:14:34: `bin/seon config apply default tmp/s5-restore.edn`. Verified at 17:14:38: no-provider `true`, 100,
  relaunches 2.

**Grading form.** Run 4b's form, with `aid "attempt/64b05977e3eb"`. It would answer 0 calls: the agent does not
exist. There is no first block to paste, because no agent was started.

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | Run 4 + 4b | **Run 4 + 4b + 5** |
|---|---|---|---|
| Model calls | 38 | 157 | **157** (attempt 4: 0) |
| Prompt tokens | 570k (310k cached) | 4.99M (3.90M cached) | **4.99M (3.90M cached)** |
| Hand acts after `chain!` | 6 | 1 | **1**. Run 5 added none, because root ran nothing after the refusal. The refusal comes from run 4b's hand act. |
| Furthest point | Implement (write-back conflict) | Implement wired the producer, proof red, stalled | **unchanged: attempt 4 refused at launch** |
| Merge | Prove auto-merged, Implement refused | none | **none.** The published-diff review and the fresh-agent `(str/subs "abc" 1)` check could not be reached. |

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact are kept. Task `c12a57408f49` is `:running` with no open
  attempt (above). Root decides whether to repair it, hold it or abandon the chain.
- Fault `482f7508e3d9…` is open on main.
- JVM `user` host helpers: `s5r5-conn`, `s5r5-state`, `s5r5-jvm`, `s5r5-watch`.
- One SCI eval as root: `my.root/run!`. Config was restored and verified.

**To file (this lane commits docs only):**

- **Fix first (Broken first 0):** `seon.task/next-attempt` and the chain planner must share one rule for an open
  attempt. Otherwise an attempt that `my.root/close!` closed makes the next `my.root/run!` start a graph for an
  agent that was never written. Owners: `seon.task/next-attempt` or `my.root/close!`. The reproduction is this
  chain's state.
- `my.root/run!` should deliver the start's refusal, or answer the started attempt, rather than the intent alone.
- `my.root/run!`'s reply runs a test step on main: 1.4-2.1 GB and 750-950 ms, now seen four times.

## Run 5b: fourth Implement attempt, stalled on its first turn

Lane `s5-run-5b` acted as root, 17:44-17:50 UTC. Default pid 6605 booted at 17:44:10.9 UTC from
`data/source/fdcadc002b9f…` (HEAD `fdcadc002`, one definition of an open attempt), with `:seon.boot/missing-layers
[]`. No lane held a claim. Root applied `tmp/s5r5-config.edn` at 17:44:03 and then restarted. Nobody called
`my.root/run!`.

**Broken first.**

0. **Attempt 4 started by itself, then stalled on its first turn. There were 0 model calls.**
   - fdcadc002 works. The boot pass wrote attempt `64b05977e3eb` (number 4) and its agent `attempt/64b05977e3eb`
     (eid 1023629) on `:chain-f1f6dc8e6652`, and started its graph. This was the Run 5 regression, and no hand act
     was needed.
   - Turn `053ba9fb7c01` was opened by `seon.turn/open-call` at 17:44:35.648 and closed by
     `seon.turn/open-run-tx-call` at 17:44:36.364. It had 0 evaluations and 0 `:seon.turn/attempts`.
     `seon.turn/end-task-call` followed at 17:44:36.506.
   - Occurrence `401ddb0eb6e5` is on the chain branch:
     - type `:seon.turn.loop/phase-failed-error`, failed step `seon.turn/phase`, phase `"analysis"`, `:sci/error`;
     - message: "Unable to resolve symbol: seon.db/query-call-valid?";
     - a message to root, `bab65b7746fa`, carried it.
   - The attempt ended `:stalled` with outcome `:closed` ("The attempt ended :stalled."). The task went back to
     `:held`. The agent's end facts: 0 calls, 0 tokens, 1 red proof
     (`seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name`). No problem task was
     written. §2.7's bail to root held: an end was written, unlike in run 4b.
   - **Cause: the chain branch interprets the merge base's `seon.db/q` on a platform where its callee is gone.**
     - 712bb86e5 (13:09 UTC) deleted `seon.db/query-call-valid?` and changed `seon.db/q` on main.
     - The chain branch forked earlier (merge base `6acf6fed…`). It still holds both entities, and its
       `seon.db/q` source calls `seon.db/query-call-valid?`.
     - Main holds neither the function nor any source naming it. Measured on both values: branch
       `(seon.db/q seon.db/query-call-valid? seon.db/vector-query-call)`, main
       `(seon.db/q seon.db/vector-query-call)`.
     - So `seon.db/q` differs between the branch and the compiled platform. The branch interprets its own stale
       copy in SCI, and SCI cannot resolve the deleted callee.
     - The agent never touched `seon.db`. Main's change since the merge base is being read as a branch change.
     - This contradicts "a branch never changes the platform … sees a change only after merge and reload; what the
       branch evaluates from source sees its definitions". The differing set has to be the branch's own changes
       since the merge base (three-way), not the branch compared with current main.
     - Every long-lived chain branch will hit this whenever main deletes a function that the branch's merge-base copy
       calls.
     - Owner: the program-difference derivation that picks which entities a branch interprets. Reproduce with this
       chain's state: any SCI turn by an agent on `:chain-f1f6dc8e6652`.
1. **The run config did not survive the restart.** On main's config entity 781248, history shows root's
   `seon.config/reconcile-call` at 17:44:03.382 (no-provider retracted, 30, relaunches 0). The new JVM's boot
   reconcile at 17:44:21.816 then put back no-provider `true`, 100 and relaunches 2. That is the development overlay
   the cluster boots under.
   - Attempt 4 therefore launched with no-provider `true`. Had the turn got past analysis, it could not have made a
     model call.
   - Root's order has to be: restart, then `config apply`. Or the overlay must yield to applied facts.
2. Fault `3506b09a0b84…` on this boot (17:44:30.70, 2 occurrences): `seon.db/carried-projection` refused argument 0,
   "expected :seon.db/database-value … got a map", called from `seon.cluster.agent/acquire-context!`. Its link to
   attempt 4 is not established. It comes 5 s before the turn.
3. Watch-form costs, found while watching:
   - The first JVM-mode eval of this session spent 1.19 GB at the entrance.
   - A `[?e ?a <eid>]` reverse scan on the chain branch cost 4.36 GB and 1.3 s. That one was my form: a full scan.

**Run.**

- 17:44:03: root applied `tmp/s5r5-config.edn`. 17:44:10.9: default booted onto fdcadc002. 17:44:21.8: boot reconcile
  restored the overlay (Broken first 1).
- 17:44:30.7: fault `3506b09a` (Broken first 2). 17:44:35.6-36.5: attempt 4's one turn, its stall and its end.
- 17:45:26: first watch. Attempt 4 had already ended. Threads 38, heap 2.5 GB, then 37 and 1.07 GB at 17:47. Idle,
  no livelock.
- 17:46:5x: `bin/seon config apply default tmp/s5-restore.edn` (`converged? false`, 2 operations). Verified with
  `(seon.config/effective main "default")` at 17:47:03: no-provider `true`, 100, relaunches 2, model
  `"deepseek-flash"`. Root was messaged.

**Grading form.** This is run 4b's form with `aid "attempt/64b05977e3eb"`. The agent's own end facts answer it:
`:seon.agent/end-calls 0`, prompt 0, completion 0. Turn `053ba9fb7c01` has no evaluations. There is no first block
to paste, because analysis failed before any context was rendered for a call.

| Task | Model calls | Prompt tokens | Completion | Wall time | Agent forms | End |
|---|---|---|---|---|---|---|
| Implement, attempt 4 (`64b05977e3eb`) | 0 | 0 | 0 | 0.9 s (17:44:35.6 to 17:44:36.5) | 0 | `:stalled` → `:closed`, task `:held` |

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | Run 4 + 4b + 5 | **+ 5b** |
|---|---|---|---|
| Model calls | 38 | 157 | **157** (attempt 4: 0) |
| Prompt tokens | 570k (310k cached) | 4.99M (3.90M cached) | **4.99M (3.90M cached)** |
| Hand acts after `chain!` | 6 | 1 | **1**. 5b added none: the attempt started and ended by itself. |
| Furthest point | Implement (write-back conflict) | attempt 4 refused at launch | **attempt 4 launched and ended by itself; stalled in SCI analysis on a symbol main deleted** |
| Merge | Prove auto-merged, Implement refused | none | **none.** `merge-chains!`/`finish-chains!`, the published-diff review, the bb hook load and the fresh-agent `(str/subs "abc" 1)` check were not reached. |

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact. Task `c12a57408f49` is `:held`, and attempts 1-4 are all
  `:closed`.
- Occurrence `401ddb0eb6e5` and message `bab65b7746fa` are on the branch. Fault `3506b09a` is open on main.
- JVM `user` host helpers: `s5r5b-conn`, `s5r5b-state`.
- No SCI eval as root. Config was restored and verified.

**To file (this lane commits docs only):**

- **Fix first (Broken first 0):** a branch interprets an entity whose only difference from the platform is main's
  change since the merge base. Here that is the merge base's `seon.db/q`, which calls the deleted
  `seon.db/query-call-valid?`. So every turn on an older chain branch fails analysis. The differing set must be
  the branch's changes since its merge base. Regression: an agent on a branch forked before main deletes a callee
  of an unchanged function still evaluates. This chain cannot run again until that is fixed, or until root abandons
  the chain or builds a new one on current main.
- The config applied before a restart is replaced by the boot overlay (Broken first 1). Either the operator applies
  after boot, or the overlay yields to applied facts.
- `seon.db/carried-projection` is given a map from `seon.cluster.agent/acquire-context!` (fault `3506b09a`).

## Run 5c: fifth Implement attempt, stalled on its first turn by a second path

Lane `s5-run-5c` acted as root and watched, 18:01-18:10 UTC. Default had restarted onto 576009291 (a work branch
interprets only what main has not moved past since its merge base, the 5b fix). Root applied the run config after the
restart, which avoids 5b's Broken first 1, and verified it: `"deepseek-flash"`, no-provider absent, 30, relaunches 0.
Root then ran `(my.root/run! {:seon.task/assigned "c12a57408f49"})`.

**Broken first.**

0. **Attempt 5 started by itself and stalled on its first turn, with the same message as 5b. There were 0 model calls.**
   - Attempt `36da5b133005` (number 5, agent `attempt/36da5b133005`, eid 1024113) was written on
     `:chain-f1f6dc8e6652` with merge base `6acf6fed…`, the same base as before. Its one turn, `0f2c9d074cf7`,
     opened at 18:01:02.271 and closed at 18:01:02.949. It had 0 evaluations and 0 `:seon.turn/attempts`.
   - Occurrence `355372430814` is on the branch:
     - type `:seon.turn.loop/phase-failed-error`, failed step `seon.turn/phase`, `:phase "analysis"`;
     - `:sci.impl/symbol seon.db/query-call-valid?`;
     - message: "Unable to resolve symbol: seon.db/query-call-valid?".
   - At 18:01:03.059 the attempt ended `:stalled`, outcome `:closed` ("The attempt ended :stalled."), and the task
     went to `:held`. End facts: 0 calls, 0 tokens, 1 red proof. Root's pass wrote problem task `20e5c042a13d`
     (`seon.task/unfinished-task-36da5b133005`) on main. §2.7's bail held.
   - **576009291 works, and was not the path that failed.** All of these were measured on the branch head in JVM mode:
     - `merge-sides` answers the base `6acf6fed…` and target `:cluster-default`.
     - `(moved-past head sides [[:seon.fn/sym 'seon.db/q] [:seon.fn/sym 'seon.db/query-call-valid?]])` answers both.
     - `overlay-plan` holds 7 overridden functions, all `seon.error/*`, 0 added, and neither `seon.db` symbol.
     - The reverse closure of those 7 is 272 functions, and `seon.db/q` is not among them.
     - In a fresh `(seon.sci.eval/base-ctx head)`, `(seon.db/q '[:find ?x . :in $ ?x] (seon.db/db) 1)` answers `1`,
       by the compiled Var.
   - **Cause: the turn's model-context phase documents `seon.db/q` from the branch's stale row.** 5b's run had
     no-provider `true`, and that hid this path.
     - `seon.turn` runs the model-context phase only when `:seon.config.ai/no-provider` is off.
     - The call chain is `seon.context/model-context` → `seon.bootstrap/help-value` on the branch database →
       `seon.sci.lookup/function-documentation` `'seon.db/q` → `agent-documentation-contract` →
       `documentation-contract`.
     - `documentation-contract` compiles the row's stored `:seon.fn/spec` with `malli.core/function-schema`. The
       branch's spec for `seon.db/q` is the merge base's. It ends `[:fn {…} seon.db/query-call-valid?]`, and Malli's
       SCI evaluation of that symbol throws. Main's spec has no `:fn` clause.
     - The throw escapes `documentation-contract` and the help, and the turn stalls.
   - Reproduction in JVM mode, with `head` the branch head:

     ```clojure
     (seon.sci.lookup/function-documentation head 'seon.db/q)  ;; throws "Unable to resolve symbol: seon.db/query-call-valid?"
     (seon.sci.lookup/function-documentation main 'seon.db/q)  ;; => {:summary … :body … :arglists … :in … :out …}
     (seon.bootstrap/help-value head)                          ;; throws the same
     (seon.context/model-context head {:seon.db/connection conn :seon.sci.admit/caps caps
                                       :seon.sci.eval/ctx (seon.sci.eval/base-ctx head)
                                       :seon.sci.eval/time-limit-ms 30000}
                                 "attempt/36da5b133005")
     ;; throws from seon.sci.lookup/documentation-contract (via function-doc-map, function-documentation)
     ```

   - This is the same defect class as 5b, in a second reader. Documentation, and every other reader of a program row
     on a work branch, reads the branch's row even when the branch never changed that function and main has moved past
     it. 576009291 fixed the evaluator's choice (`seon.sci.eval/moved-past`). The documentation reader has no such
     choice.
   - Owners:
     - the row that `seon.sci.lookup/function-documentation` (and `seon.bootstrap/help-value`) reads for a function
       main moved past: one three-way rule, shared with `moved-past`, not a second copy;
     - `seon.sci.lookup/documentation-contract`, which throws on an uncompilable stored spec where it should answer
       its error value.
   - Regression: on a branch forked before main changes a function's contract to drop an `:fn` predicate and
     deletes the predicate, `(seon.bootstrap/help-value branch-head)` answers.
1. **`seon.sci.eval/base-ctx`'s snapshot `:functions` carries the branch's stale source for every function**
   (6,983 entries, `seon.db/q`'s included). The overlay does not bind them, so a direct call is compiled. Every reader
   of `:seon.sci.eval/function-source` on a work branch is exposed to the same defect class. I did not verify any
   other reader.
2. Root's own path:
   - My first watch form had the wrong attribute names (`:seon.task.attempt/task`, `:seon.fn/symbol`). JVM mode
     answered a ClassCastException "PersistentArrayMap cannot be cast to Symbol" with no form position, the same
     finding as runs 3 and 4b.
   - `seon.db/q`'s "uninstalled attribute" refusal was correct and listed `:seon.fn/sym` among its candidates.

**Run.**

- About 18:00: root applied the run config after the restart and ran `my.root/run!`.
- 18:01:02.27-18:01:03.06: attempt 5's turn, its stall and its end (Broken first 0).
- 18:01:28, first watch: 36 threads, 44.67 GB cumulative allocated, heap 2.39 GB. Attempt 5 had already ended.
- About 18:03: `bin/seon config apply default tmp/s5-restore.edn` (`converged? false`, 3 operations). Verified with
  `seon.config/effective`: no-provider `true`, 100, relaunches 2, model `"deepseek-flash"`.
- 18:05:41: 34 threads, 44.71 GB, heap 0.99 GB. The JVM was idle and there was no livelock. Most of the allocation
  after the end was my diagnosis forms: `base-ctx` 1.39 GB and 840 ms, and `model-context` 749 MB and 530 ms.
- Root was messaged with the end and the cause. No merge, so there was no merge message.

**Grading form.** This is run 4b's form with `aid "attempt/36da5b133005"`. The agent's end facts answer it: 0 calls,
0 prompt tokens and 0 completion tokens, and turn `0f2c9d074cf7` has no evaluations. There is no first block to paste,
because the block the model would have read is what threw (`model-context`).

| Task | Model calls | Prompt tokens | Completion | Wall time | Agent forms | End |
|---|---|---|---|---|---|---|
| Implement, attempt 5 (`36da5b133005`) | 0 | 0 | 0 | 0.8 s (18:01:02.27 to 18:01:03.06) | 0 | `:stalled` → `:closed`, task `:held`, problem task `20e5c042a13d` |

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | Run 4 + 4b + 5 + 5b | **+ 5c** |
|---|---|---|---|
| Model calls | 38 | 157 | **157** (attempt 5: 0) |
| Prompt tokens | 570k (310k cached) | 4.99M (3.90M cached) | **4.99M (3.90M cached)** |
| Hand acts after `chain!` | 6 | 1 | **1**. 5c added none: root's `run!` is the designed resume, and the attempt ended and bailed by itself. |
| Furthest point | Implement (write-back conflict) | attempt 4 stalled in SCI analysis (the overlay) | **attempt 5 got past the overlay and stalled in the model-context phase: the help documents the branch's stale `seon.db/q` contract** |
| Merge | Prove auto-merged, Implement refused | none | **none.** `merge-chains!`/`finish-chains!`, the published-diff review, the bb hook load and the fresh-agent `(str/subs "abc" 1)` check were not reached. |

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact. Task `c12a57408f49` is `:held`, and attempts 1-5 are all
  `:closed`.
- Occurrence `355372430814` is on the branch. Problem task `20e5c042a13d` is on main. `my.root/merge-requests` is `[]`.
  Main also still holds the other lane's chain fact `:chain-e439df9798c6`.
- JVM `user` host helpers: `s5c-conn`, `s5c-aid`, `s5c-jvm`, `s5c-state`. `s5c-ctx` (a `base-ctx` of the branch) was unmapped.
- No SCI eval as root by this lane. Config was restored and verified.

**To file (this lane commits docs only):**

- **Fix first (Broken first 0):** on a work branch, `seon.sci.lookup/function-documentation`, and with it
  `seon.bootstrap/help-value` and `seon.context/model-context`, compiles the branch's stale contract of a function
  main moved past. Use one three-way rule for which row a branch reads, shared with `seon.sci.eval/moved-past`. This
  chain cannot run again until that is fixed, or until root abandons the chain or builds a new one on current main.
- `seon.sci.lookup/documentation-contract` throws on an uncompilable stored spec. Its caller then stalls the turn,
  where the reply should carry the error value beside it.
- Other readers of `:seon.sci.eval/function-source` or stored `:seon.fn/spec` on a work branch may share the class
  (Broken first 1). Count them before designing.

## Run 6: sixth Implement attempt, worked 19 calls, closed by root

Lane `s5-run-6` acted as root and watched, 18:27-18:40 UTC. Default had restarted onto be9b26a09, which carries
576009291, bcce0cae1 and be9b26a09: the overlay is three-way, there is one three-way row reader with total
documentation, and as-of views carry their branch. On the chain branch root had verified that
`seon.context/current-state` builds (18.0 s and 39.7 GB cold, 160 ms and 291 MB on repeat), that help renders, and
that `(doc seon.db/q)` shows main's contract. After the restart root applied the run config to main and verified it
(`"deepseek-flash"`, no-provider absent, 30, relaunches 0). Root then ran
`(my.root/run! {:seon.task/assigned "c12a57408f49"})`.

**Broken first.**

0. **Attempt 6 got past both of 5b's and 5c's stalls, but its proof was red from a platform NPE.** That NPE was a
   result recorded six hours earlier, against an older platform. The agent spent its calls reading
   `src/seon/sci/eval.clj`.
   - The newest recorded execution of the proof
     `seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name` on `:chain-f1f6dc8e6652` is
     member 972395, completed in tx 536872687 at **12:29:44 UTC**. That is run 4b's attempt 3, two restarts and
     three platform commits ago. The member's facts are `:evaluator :interpreted`, `error-count 1`,
     `fail-count 0`, reach digest `6408418baa94…`, and failure 972590:
     - `:seon.test.failure/message` "Uncaught exception, not in assertion.", throwable
       `java.lang.NullPointerException`, "Cannot invoke \"clojure.lang.IObj.withMeta(clojure.lang.IPersistentMap)\"
       because \"x\" is null";
     - top frames: `seon.sci.eval$lazy_root$lazy_member doInvoke eval.clj:3124` ←
       `seon.test.runner$run_selected_tests runner.clj:519` (that is `(test/test-vars namespace-vars)`) ←
       `run_vars_BANG_ runner.clj:438` ← `seon.db$with_branch` ← `seon.sci.eval$run_tests eval.clj:5243` ←
       `seon.sci.eval$run_test` ← `seon.test$bounded_result`.
   - The member that threw is the test var itself. The runner calls the branch-interpreted deftest, which is bound
     to its `seon.sci.eval/lazy-root`. On that first call, `lazy-member` evaluates
     `(cond-> (d/commit-as-db (context-database executing) commit {:secondary-indices? false}) projection (vary-meta
     assoc …))`. `d/commit-as-db` answered nil, so `vary-meta` threw. Either `context-database` found no connection
     in the executing context, or that connection's store does not hold the branch commit.
   - No execution of the proof is recorded at attempt 6's start or after any of its 67 evaluations. There are 3
     members in all, at 12:07:05, 12:07:52 and 12:29:44. So the block and every test step showed the 12:29:44 red,
     although the platform that produced it (`seon.sci.eval`) has since changed. Either the reach-digest reuse
     treats a red from another platform as current, or the attempt-start prove pass did not run. I did not check
     which.
   - In SCI, the agent's own direct call no longer threw: "The call succeeds now (no NPE) but `:nearest` is empty —
     and it's slow (1.1s, 1.6GB)." The subject was still unfixed: `:nearest []` for all three cases.
   - Owners: the runner's call of a lazily bound test var, `seon.sci.eval/lazy-root` (commit-as-db nil, so it
     throws a bare NPE instead of an error value), and test-result reuse across a platform change
     (`seon.test.runner/current-results`, reach digests).
   - **Not reproduced on the current platform.** A direct call answered without the NPE, so this may be fixed. The
     defect then is that a stale red is shown as current.
1. **The block costs about 14 s and about 25 GB on every turn, defn or not.** The model's own share is about 3 s
   of each turn.
   - The turns ran back to back about 15 s apart: `open->model` 27-39 ms after open, then 14.1-15.2 s from the
     model attempt to close. The agent's 67 evaluations took only 1.8 s and 2.4 GB in total.
   - The JVM-wide counter rose from 118.6 GB to 562.8 GB over 18 turns, about 25 GB per turn. Over 6 s, the turn
     thread alone allocated 6.5 GB, about 1 GB/s.
   - Stack samples of the turn thread (40 at 300 ms): 26 in `seon.db/vector-query-call` under `seon.db/q`, 11 in
     `seon.ai/stream-fold` (the model). Under the query, the callers were:

     ```
     seon.cluster.registry/identity-digests ← moved-past ← seen-database
       ← seon.sci.lookup/function-documentation ← seon.ref/at-level ← seon.ref/answer ← seon.ref/references
         ← seon.context/task-references ← seon.context/must-reads ← task-sections ← current-state ← model-context
         ← seon.turn/phase ← seon.turn/call-turn                                              (10 of 17)
     seon.cluster.registry/identity-digests ← moved-past ← seen-database ← seon.ref/brief ← seon.ref/inner
       ← seon.ref/resolve-path ← seon.ref/answer ← … must-reads                              (5 of 17)
     seon.cluster.registry/identity-digests ← moved-past ← seen-database ← seon.ref/brief
       ← seon.context/must-reads ← … model-context                                          (2 of 17)
     ```

   - The `seon.profile` delta over one 30 s window (2 turns, plus my own sleeping form) was 1,851
     `seon.db/vector-query-call` calls (27.3 s self) and 393 `seon.db/commit-database` calls, with 6,824
     `with-declarations`. That is about 925 queries and about 196 `merge-sides` per turn.
   - Measured once, warm, in JVM mode: `merge-sides` 1.3 ms / 3.3 MB, `seen-database` 1.5 ms / 3.6-4.1 MB. So the
     cost is the count: every reference and every declaration in the must-reads re-derives `merge-sides` (a new
     `commit-database` of the merge base) and three digest queries, on every turn, and nothing reuses them.
   - Root's 160 ms repeat measure of `current-state` was not this path: the turn's `model-context` re-pays it each
     turn. A `defn` is not needed to trigger it.
   - Owner: `seon.cluster.registry/seen-database`. Derive the sides once per database value, and the moved-past
     set once per branch head (content-keyed in `seon.cache`), never once per declaration.
2. **The attempt ran on the chain branch's own config, not on main's applied config.** Every
   `:seon.ai.attempt/settings-edn` holds `:seon.config.run/max-episode-runs 40` and `:seon.config.run/relaunches 0`.
   `(seon.config/effective head "default")` on `:chain-f1f6dc8e6652` answers exactly that, 40 and 0. This is run 4's
   config, from when the branch forked at `chain!`. Main read 30 at the same time. So root's run-config apply on main
   did not reach the attempt's cap, and the restore on main (no-provider `true`) does not reach this branch either.
   Run 5b's reading that attempt 4 "launched with no-provider true" from main's overlay should be re-checked
   against the branch. Owner: which config value a chain agent reads (`seon.config/effective` on its checked-out
   branch, against the cluster's configuration on main). Running configuration is cluster configuration, and a
   branch should not freeze it at fork.
3. **A closed working attempt keeps its graph.** Root closed the attempt at about 18:32:27 with `my.root/close!`
   ("S5 run 6 stopped by root: proof red from a platform NPE … fix first"). The attempt then read
   `:seon.task/outcome :closed` and the task `:held`, and no model call came after the turn already in flight (19
   calls in all, the last turn opened at 18:32:22.2). At 18:33:51 its three threads
   (`seon-flow/default/attempt/a4de2ea6de02/seon.agent/{turn,mailbox,schedule}`) were still alive, `WAITING`, and
   allocated 0 bytes in 5 s. The graph is idle and was not stopped. This confirms root's
   `seon.cluster.agent/a-closed-working-attempt-keeps-its-graph`.

**Run.**

- 18:27:36-37.6: attempt 6 written (`a4de2ea6de02`, number 6, agent `attempt/a4de2ea6de02` eid 1025013, attempt eid
  1025016, merge base `6acf6fed…`) with settings 1025014. 18:27:38.4: first turn, and its model call 39 ms later.
- 18:28:25, first watch: 3 calls, 39 threads, heap 2.3 GB. Root was messaged.
- 18:27-18:32: the agent read the helpers left on the branch (`alias-target`, `alias-candidates`, `library-names`,
  `library-calling-form`, `candidate-calling-form`, `core-namespace`) and the proof. From about call 9 it read
  `src/seon/sci/eval.clj` to explain the NPE.
  - Its errors were `my.fs/read-lines` unresolved, `my.fs/read` given a string and then `:my.fs/max-bytes 0`,
    `test-support/with-database` unresolved, the `seon.error` → `seon.test-support` cycle refusal (a `require` in
    `seon.error`), and `d/commit-as-db` unresolved (it tried to evaluate the platform's form).
- 18:31:20: 14 calls. Root was messaged with both findings. About 18:32:27: root `close!`d the attempt.
- 18:32:35: `bin/seon config apply default tmp/s5-restore.edn` (`converged? false`, 3 operations). Verified at
  18:32:39 with `seon.config/effective` on main: no-provider `true`, 100, relaunches 2, `"deepseek-flash"`. That is
  main only; see Broken first 2.
- No livelock: calls advanced at every watch, about one per 15 s, and threads held at 38-40.

**First block.** I did not render it separately, because a render would re-pay Broken first 1. The first call's
whole prompt was 10,841 tokens (1,920 cached). Calls grew to 19,095 tokens by call 5.

**Grading form.** Run 4b's form with `aid "attempt/a4de2ea6de02"`:

| Task | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End |
|---|---|---|---|---|---|---|
| Implement, attempt 6 (`a4de2ea6de02`) | 19 | 422,092 (96,384) | 6,008 | 4 min 59 s (18:27:38.4 to about 18:32:37) | 67 evaluations, 7 errors | `:closed` by root (`my.root/close!`), task `:held`; graph left running idle |

About 22k prompt tokens per call, only 23% cached. In run 4 the cached share was 79%.

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | Run 4 + 4b + 5 + 5b + 5c | **+ 6** |
|---|---|---|---|
| Model calls | 38 | 157 | **176** |
| Prompt tokens | 570k (310k cached) | 4.99M (3.90M cached) | **5.41M (4.00M cached)** |
| Hand acts after `chain!` | 6 | 1 | **2**. Root's `close!` of a working attempt that could not finish, the same mechanism as 4b's hand act 1. |
| Furthest point | Implement (write-back conflict) | attempt 5 stalled in the model-context phase | **attempt 6 worked 19 calls, past both stalls; proof red from a stale platform NPE; 14 s / 25 GB block per turn** |
| Merge | Prove auto-merged, Implement refused | none | **none.** `merge-chains!`/`finish-chains!`, the published-diff review, the bb hook load and the fresh-agent `(str/subs "abc" 1)` check were not reached. |

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact. Task `c12a57408f49` is `:held`, and attempts 1-6 are all
  `:closed`. Attempt 6's graph threads are alive and idle (Broken first 3).
- The chain branch's config is still no-provider absent, 40, relaunches 0 (Broken first 2).
- JVM `user` host helpers: `s6-conn`, `s6-aid`, `s6-state`, `s6-turns`, `s6-evals`, `s6-replies`, `s6-errs`,
  `s6-prof`, `s6-p0`.
- No SCI eval as root by this lane. Main's config was restored and verified.

**To file (this lane commits docs only):**

- **Fix first (Broken first 1):** `seon.cluster.registry/seen-database` re-derives `merge-sides` and three
  `identity-digests` queries for every declaration on every turn. That is about 925 queries and 196
  `commit-database` calls per turn, about 14 s and about 25 GB. Derive the sides once per database value and the
  moved-past set once per head. Proof: a `seon.profile/measure` of `seon.context/model-context` on a work branch,
  parent against change.
- A red recorded against an older platform is shown as the current proof result after a restart (Broken first 0).
  Separately, `seon.sci.eval/lazy-root` throws a bare NPE when `d/commit-as-db` answers nil for a test var the
  runner calls. Reproduce on this chain: run the proof on the branch head and see whether it still throws.
- A chain agent reads its branch's config, frozen at fork, not the cluster configuration on main (Broken first 2).
- A closed working attempt keeps its graph (Broken first 3; root filed
  `seon.cluster.agent/a-closed-working-attempt-keeps-its-graph`).

## Run 7: seventh Implement attempt, a prove loop with no model call

Lane `s5-run-7` watched as root, 18:57-19:05 UTC. Default had restarted onto 33724e146, which carries 8103caba0
(seen-database cached by commit ids), e287d2b52 (a result's identity follows rows main moved past) and 33724e146
(as-of digests by index probe). Root's gates passed in JVM mode: the held Implement block on `:chain-f1f6dc8e6652`
built cold in 4.7 s and on repeat in 149 ms / 239 MB, and its proof read `:pending`. The attempt read the chain
branch's config, frozen at fork (40, relaunches 0, no-provider absent). Root ran
`(my.root/run! {:seon.task/assigned "c12a57408f49"})`.

**Broken first.**

0. **The attempt-start prove pass re-ran the proof 127 times in 86 s and never let the agent make a model call.**
   - Attempt `1addda8db45d` (number 7, agent `attempt/1addda8db45d`, eid 1030680, attempt eid 1030683) was written at
     18:57:28.3 UTC. It wrote no turn.
   - From 18:57:29.5 to 18:58:58 the branch gained 127 `:named` runs of
     `seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name`, one new run about every 0.7 s
     (`086ccd129723`, `283ee99f3ca8`, … `409e78873bcf`), each with one member.
   - **The first run was green.** Run `086ccd129723` (member 1030685, run at 18:57:29.528, basis-t 536872808,
     change-basis-t 536872685, loaded commit `6acf6f7d…`) passed 4 assertions with 0 failures and 0 errors, in 962 ms
     and 2.2 GB. The subject may already be fixed on the branch. That is not judged, because nothing merged.
   - All 126 later runs were red: error 1, "Uncaught exception, not in assertion.", `java.lang.NullPointerException`
     "Cannot invoke \"clojure.lang.IObj.withMeta(clojure.lang.IPersistentMap)\" because \"x\" is null", 15-20 ms and
     13 MB each. Top frames:
     `seon.sci.eval$lazy_root$lazy_member eval.clj:3137` ← `seon.test.runner/run-selected-tests runner.clj:520` ←
     `run-vars! runner.clj:439` ← `seon.db/with-branch` ← `seon.sci.eval/run-tests eval.clj:5256` ← `run-test 5268` ←
     `seon.test/bounded-result test.clj:362`. This is run 6's class: `d/commit-as-db` answers nil in `lazy-member`.
     It shows up on the second and later calls of the branch-interpreted test var, not the first.
   - **Why it re-ran: no run-7 member carries a reach digest.** None of the 127 members, the green one included, has
     `:seon.test.member/reach-digest`. Only the three old members do (`c8ec6428…`, `6d88b847…`, `6408418baa94…`).
     - `seon.test.runner/current-results` skips a member with no digest (`:when digest`). So
       `(seon.test.runner/current-results head [proof] nil)` answers `{}`, and every pass sees the proof as never
       run. The green start therefore never ended the task (§2.4/§2.7), and nothing bounded the re-runs.
     - The reader computes a string today: `(seon.test.runner/reach-digests head [proof])` answers `87aea83603ac…`
       in 60 ms. It answers the same on `(seon.db/as-of head 536872808)`, the green run's basis.
     - The recorder (`seon.test.runner/record-tx`) writes the digest only when `(string? (get reach sym))`. The
       completion path drops digests with `(apply dissoc digests stale)`, using `executed-roots`' `::stale`: "a result
       whose body ran a definition other than the tested one keys nothing: it is kept and never reused".
     - **Probable cause (not reproduced):** the proof is classed stale on every run. On a work branch, the body runs
       main's row for what main moved past (`seon.cluster.registry/seen-database`), while the digested closure is the
       branch's own. A `digests` error value is the other possibility, not ruled out. Reproducing either needs the
       run's `:seon.test.member/executed`, which is not stored.
     - Owners: `seon.test.runner/executed-roots` (stale against moved-past rows), and the attempt-start prove pass. A
       test result that keys nothing makes the pass re-run without end. The design has to make that impossible at the
       owner, not by capping it.
   - Cost: the test bodies took 2.9 s and 3.8 GB in total, but the JVM-wide allocated counter rose from 107 GB to
     289 GB (about 2-3 GB/s). The attempt's turn thread alone allocated 64.5 GB without one model call. About 98% of
     the cost was the pass around the test (selection, recording, digests), not the test.
1. **Root closed the attempt, and its graph was kept.** Root wrote `:closed` at 18:58:57.530 ("S5 run 7 livelock
   stopped by root: the start prove pass re-ran the proof ~100 times …"). One more member landed at 18:58:58 (one
   was in flight), then none: 134 members at both 18:59:15 and 18:59:25. JVM-wide allocation over the 10 s after
   that was 0.012 GB. The three graph threads
   (`seon-flow/default/attempt/1addda8db45d/seon.agent/{turn,mailbox,schedule}`) were alive and `WAITING`, with
   0 bytes allocated over 10 s. This is root's filed `seon.cluster.agent/a-closed-working-attempt-keeps-its-graph`
   again. Root's close wrote no problem task.
2. Root's own path: my first watch form used `:seon.agent/end-reason` and `:seon.task/handle`. The refusals were
   good: `seon.db/q` "cannot read uninstalled attribute" listed the candidates, which let me find `:seon.task/id`
   and `:seon.task/attempt-of`.

**Run.**

- 18:57:28.3: attempt 7 and its agent written. 18:57:29.5: the first prove run started (green). From 18:57:32:
  red NPE runs every ~0.7 s.
- 18:57:56, first watch: 0 turns, 39 threads, heap 1.6 GB, 107 GB cumulative. 18:58:11: 150 GB. 18:58:32: 213 GB and
  41 threads. Root was messaged at the start and when the loop was found (18:58:4x).
- 18:58:57.5: root `close!`d. 18:59:03: task `:held`, attempt 7 `:closed`, 289 GB, heap 0.95 GB. 18:59:25: no new
  members and flat allocation (Broken first 1). Root was messaged with the stop numbers and the cause.

**Grading form.** Run 4b's form with `aid "attempt/1addda8db45d"`: 0 turns, 0 calls, 0 evaluations. No first block
was captured, because no model context was built.

| Task | Model calls | Prompt tokens | Completion | Wall time | Agent forms | Proof runs | End |
|---|---|---|---|---|---|---|---|
| Implement, attempt 7 (`1addda8db45d`) | 0 | 0 | 0 | 89 s (18:57:28.3 to 18:58:57.5), all in the start prove pass | 0 | 127 (1 green, 126 NPE), 2.9 s / 3.8 GB in bodies, ~180 GB JVM-wide | `:closed` by root (`my.root/close!`), task `:held`; graph kept idle |

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | Run 4 … 6 | **+ 7** |
|---|---|---|---|
| Model calls | 38 | 176 | **176** (attempt 7: 0) |
| Prompt tokens | 570k (310k cached) | 5.41M (4.00M cached) | **5.41M (4.00M cached)** |
| Hand acts after `chain!` | 6 | 2 | **3**. Root's `close!` of a looping attempt, the same mechanism as 4b and 6: an attempt that cannot end by itself. |
| Furthest point | Implement (write-back conflict) | attempt 6 worked 19 calls; stale red | **attempt 7's first proof run went GREEN, but the result keyed no reach digest, so the start pass re-ran it until it was red (NPE)** |
| Merge | Prove auto-merged, Implement refused | none | **none.** `merge-chains!`/`finish-chains!`, the published-diff review, the bb hook load and the fresh-agent `(str/subs "abc" 1)` check were not reached. |

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact. Task `c12a57408f49` is `:held`, and attempts 1-7 are all
  `:closed`. 134 members of the proof are on the branch, 127 of them from this run. Attempt 7's graph threads are
  alive and idle. `my.root/merge-requests` was not called. Main holds no `:seon.task/merge` entity.
- JVM `user` host helpers: `s7-conn`, `s7-aid`, `s7-state`, `s7-proof`, `s7-proofs`.
- No SCI eval as root by this lane. No config applied by this lane (the attempt read the branch's frozen config).

**To file (this lane commits docs only):**

- **Fix first (Broken first 0):** a proof result recorded with no reach digest is never current, so the attempt-start
  prove pass re-runs it without end (127 runs in 86 s, about 2-3 GB/s, 0 model calls). The green start never ends the
  task. Owners: `seon.test.runner/executed-roots`'s `::stale` classification on a work branch (main's moved-past rows
  against the branch's digested closure), and the record path that keys nothing. Regression: on a work branch forked
  before main moved a callee, a branch-defined deftest run by the start pass records a reach digest, and a second pass
  reuses it.
- `seon.sci.eval/lazy-root`'s `lazy-member` throws a bare NPE (`d/commit-as-db` nil) on the second and later runs of a
  branch-interpreted test var in the same JVM. The first run passes. Reproduce: run the proof twice on the chain
  branch head.
- A closed working attempt keeps its graph (filed: `seon.cluster.agent/a-closed-working-attempt-keeps-its-graph`).

## Run 8: eighth Implement attempt, done with no model call, final merge refused

Lane `s5-run-8` watched as root, 19:16-19:25 UTC. Default had restarted onto 5898f977e (a work branch's result
that ran what main moved past is recorded at its identity). Root ran
`(my.root/run! {:seon.task/assigned "c12a57408f49"})` on `:chain-f1f6dc8e6652`. The attempt read the chain branch's
frozen config (DeepSeek, cap 40).

**Broken first.**

0. **The final merge refused, and nothing bails the refusal: the chain is parked with no problem task.**
   - `merge-chains!` ran `seon.merge/merge!` for attempt `f6f49a0b3d09`. The merge refused, and only the error's
     type was recorded on the attempt on the chain branch: `:seon.task/refusal :seon.source/write-back-error`
     (19:16:28.96, 0.3 s after `:done`). The error map itself was not kept anywhere: no fault and no main error.
   - **Reproduced read-only** in JVM mode with the same steps `seon.cluster.source/prepare-merge!` runs before any
     test: `source/compare-branch` (env store, chain branch onto main), then `#'source/replacement`, `seon.db/with`,
     and `#'source/proven-files` with root `/Users/sean/src/seon` (173 ms). The answer was
     `{:seon.source/write-back-refused :file-absent, :seon.source/path "resources/seon/schemas/tmp.edn"}`, with
     "The changed schemas' resource is absent under the root."
   - **The merge delta carries junk.** It has 12 identities:
     - the subject: `seon.error/{alias-target, alias-candidates, candidate-calling-form, core-namespace,
       library-calling-form, library-names, nearest-calls}`, `[:seon.ns/name seon.error]` and the proof test;
     - plus junk: `[:seon.schema/key :tmp/probe]` (on the branch since 12:15 UTC), `[:seon.ns/name user]` (06:10 UTC)
       and `[:seon.fn/sym seon.error/config-expectation-present-generator]`.
     - `seon.error/dbv` and `testing-dump` are not in this delta. The `:tmp/probe` schema is what refused the merge,
       for want of a file, not because it is junk.
     - Nothing was written to the checkout (`git status` shows no change under src, test or resources).
   - **The refusal is never bailed.** `seon.cluster.agent/chain-bails`, called read-only, answers `[]`. Its shape is
     `(into [] (keep ended-row) (into attempts (keep refused-row) attempts))`.
     - The refused-merge rows that `refused-row` builds carry no `:seon.task.attempt/end`, and then they pass through
       `ended-row`, whose `(and end …)` drops them. So "a refused final merge is recorded on its :done attempt" never
       reaches `bail-chain!`.
     - The task stays `:seon.task/execution :running`, with no problem task.
     - The attempt's graph threads (`seon-flow/default/attempt/f6f49a0b3d09/seon.agent/{turn,mailbox,schedule}`) are
       alive.
     - `chain-finishes` excludes an attempt that holds a refusal, so nothing loops. Thread-summed allocation stayed
       flat at about 46 GB from 19:17 to 19:20.
1. **Fixed since run 7: the start prove pass ran the proof once.** Member 1061780 was green (4 pass, 987 ms, 2.2 GB,
   `:interpreted`) and carries `:seon.test.member/reach-digest 20578dae963d…`. Branch members went from 134 to 135
   and stayed there. The green start ended the task `:done` with no model call (§2.4/§2.7), so 5898f977e did what it
   claimed.

**Run.**

- 19:16:19.1: task set `:running` (attempts-made 8). 19:16:21.0: attempt 8 (`f6f49a0b3d09`, eid 1061778, agent
  `attempt/f6f49a0b3d09`, eid 1061775) written.
- 19:16:25.6: proof run `0c9b3b2735a8` (green). 19:16:25.8: its member was recorded with a digest.
- 19:16:28.66: `:seon.task.attempt/end :done`, end commit `6acfd50c-129c-51d2-9e77-bc41ffbd412d`. The agent's end
  counters: calls 0, prompt 0, completion 0, green 1.
- 19:16:28.96: merge refusal recorded. 19:17:2x: root was messaged at the first proof result, and at 19:2x with the
  refusal and its cause.

**Done-rule evidence.** `(source/done-refusals at-end (#'seon.cluster.agent/done-judged main at-end
:chain-f1f6dc8e6652 "c12a57408f49"))` at the end commit answers `[]`. The judged map:

- run `0c9b3b2735a8` on `:chain-f1f6dc8e6652`, proofs `#{seon.error-test/an-unresolved-alias-name-is-answered-with-its-nearest-core-name}`;
- 24 identities;
- one result: 4 pass, 0 fail, 0 error, reach digest `20578dae…`, input digest `f3a42e81…`, program digest `aba0ee51…`.

**Grading form.** Run 4b's form with `aid "attempt/f6f49a0b3d09"`: 0 turns, 0 calls, 0 evaluations.

| Task | Model calls | Prompt tokens | Completion | Wall time | Agent forms | Proof runs | End |
|---|---|---|---|---|---|---|---|
| Implement, attempt 8 (`f6f49a0b3d09`) | 0 | 0 | 0 | 7.6 s (19:16:21.0 to 19:16:28.66) | 0 | 1 (green, keyed), 987 ms / 2.2 GB | `:done`, done rule `[]`; final merge refused `:file-absent` on `resources/seon/schemas/tmp.edn`; not bailed |

**Chain totals vs rough run 1:**

| Measure | Rough run 1 | Run 4 … 7 | **+ 8** |
|---|---|---|---|
| Model calls | 38 | 176 | **176** (attempt 8: 0) |
| Prompt tokens | 570k (310k cached) | 5.41M (4.00M cached) | **5.41M (4.00M cached)** |
| Hand acts after `chain!` | 6 | 3 | **3** so far. This run needed none, but the parked chain needs one from root. |
| Furthest point | Implement (write-back conflict) | attempt 7's green proof was unkeyed and looped | **Implement `:done` by the done rule; merge refused on carried junk (`:tmp/probe` schema)** |
| Merge | Prove auto-merged, Implement refused | none | **refused** (`:seon.source/write-back-error` `:file-absent`). The published-diff review, the bb hook load and the fresh-agent `(str/subs "abc" 1)` check were not reached. |

**Residue on default.**

- Chain `:chain-f1f6dc8e6652` and its chain fact. Task `c12a57408f49` is `:running`. Attempt 8 is `:done`, has no
  outcome and holds `:seon.task/refusal`. Its graph is alive.
- JVM `user` host helpers: `s8-conn`, `s8-db`, `s8-head`, `s8-proof`, `s8-members`, `s8-state`, `s8-snap`,
  `s8-alloc-gb`, `s8-store`, `s8-repro`, `s8-done`.
- No SCI eval as root by this lane, no write and no config applied. The reproduction was read-only: `seon.db/with`
  and a released compare.

**To file (this lane commits docs only):**

- **Fix first (Broken first 0):** `seon.cluster.agent/chain-bails` drops refused-final-merge rows by passing them
  through the ended-attempt filter, so a refused merge parks the chain silently. Regression: a chain whose terminal
  `:done` attempt holds `:seon.task/refusal` is bailed (task `:held`, problem task written, graph stopped) on the next
  pass.
- `seon.cluster.agent/merge-chains!` records only `(:seon.error/type merged)` on the attempt, so root has to rebuild
  the refusal by hand. Store the error value (the printed value plus ids, per "Separate storage from observation"),
  or deliver it in the bail's reason.
- An agent's scratch definitions ride into its chain's merge: `:tmp/probe`, the `user` namespace and
  `seon.error/config-expectation-present-generator`. The merge refused only because the schema had no file. Owner
  question: are the program entities of `tmp`/`user` excluded from the merge delta by the partition declared on
  entity schemas, or must the agent unmap them before `:done`, with the done rule refusing a stray?

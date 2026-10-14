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

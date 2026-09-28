---
type: research
status: in progress — loop 1 blocked at step 2 on a held-file defect (seon.turn.batch/span-of), patch with regression handed to root
created: 2026-09-28
lane: dogfood-loop-opus
jvm: default pid 9778, program 29bdfce8d (22 commits behind HEAD 90828bb93)
---

# Dogfood loop: branch → REPL prototype → propose → merge → write-back → boot (2026-09-28)

Lane `dogfood-loop-opus`. Goal: land a real change using only the REPL entrance (MCP `branch` + `eval_clj` SCI on the
branch), merged by root and written back into only the changed definitions' spans, then boot the written files.
Evidence of the previous attempt: [repl-dogfood-2026-09-27.md](repl-dogfood-2026-09-27.md).

## 1. Transcript as data

`ms` is the time the reply reported; `tok` estimates the shown reply (characters ÷ 4).

```clojure
[;; --- loop 1, task 36aa005ffd4e an-uncut-string-node-carries-no-total (seon.render.value/tree) ---
 {:step 1 :form "(branch create dogfood-uncut-total)" :ms nil :tok 60 :verdict :ok}
 {:step 2 :form "(source seon.render.value/tree)" :ms 0 :tok 90 :verdict :error
  :note "source is still not referred in user (finding #10 of 09-27; sci/* is one-path-impl-2's)"}
 {:step 3 :form "(#'v/select-nodes {...})" :ms 26 :tok 120 :verdict :ok :note "arity refusal names the declared arglist: good data"}
 {:step 4 :form "(mapv #(select-keys (:seon.render.value/tree (prepare (u %))) [...]) [\"abcdef\"@0 \"abcdef\"@2 \"\"@0 5000x@0])"
  :ms 10 :tok 90 :verdict :ok :note "reproduced: uncut string nodes carry no :seon.render.data/total; the cut one carries 5000"}
 {:step 5 :form "(defn- tree ... uncut string gets (+ (:base node 0) (:total node)))" :ms 367 :tok 900 :verdict :refused
  :note "\"its gate failed\": the JVM runs 29bdfce8d, whose turn.clj:3592 still interprets reaching file tests in SCI for a contracted defn. S4a (bb47fd6d1) deleted that gate at HEAD. BLOCKER: restart default onto HEAD."}
 {:step 6 :form "(select orientation + failure groups of result/e3b388310d8f1)" :ms 9 :tok 916 :verdict :ok
  :note "34 tests, 15 passed, 19 failed (gen/, sci/, running-instances unresolved in SCI) + auto-check ({} {}) -> nil: identical to 09-27"}
 {:step 7 :form "(seon.task id of an-uncut-string-node-carries-no-total)" :ms 3 :tok 60 :verdict :ok :note "36aa005ffd4e"}
 {:step 8 :form "(frequencies (map #(seon.test/verified? db %) (seon.fn/gate-set db 'seon.render.value/tree)))" :ms 1860 :tok 80
  :verdict :slow :note "{false 34}: no current green evidence; 55 ms per verified? call"}
 ;; --- probe of the merge path with a function no file test reaches (my.program/native-call-refusal, task 2497b961dccc) ---
 {:step 9 :form "(branch create dogfood-define-advice)" :ms nil :tok 60 :verdict :ok}
 {:step 10 :form "(alter-var-root #'seon.render.value/prepare identity)" :ms 793 :tok 959 :verdict :ok
  :note "reproduced: advises my.program/define!, which does not exist; the refusal is a :value, not an :error; 40,708-token value"}
 {:step 11 :form "(defn native-call-refusal ... advise clojure.core/defn after in-ns)" :ms 61 :tok 50 :verdict :ok
  :note "admitted on the branch (0 reaching tests: the only caller is the host hook through requiring-resolve, so the graph misses it). Its proof would test the compiled Var, not the branch's: a host-hook callee cannot be proven on a branch. Parked."}
 {:step 12 :form "(seon.issue/task-edges db \"36aa005ffd4e\") / pull [:seon.issue/id ...]" :ms 11 :tok 400 :verdict :blocker
  :note "a task is not an issue: propose-merge! records a task proposal, but prepare-merge! took :seon.issue/id and refused \"The issue is absent on the candidate\". Fixed below (B1)."}]
```

### Loop 1, attempt 2 (default 088497b3e, S4a and 0db8bf035 installed)

```clojure
[{:step 1 :form "(branch create loop1-uncut-total)" :ms nil :tok 60 :verdict :ok}
 {:step 2 :form "(totals of prepare over \"abcdef\"@0 @2, \"abc\"@3, \"\"@0)" :ms 17 :tok 90 :verdict :ok :note "[nil nil nil nil]: reproduced"}
 {:step 3 :form "(seon.task/assign! {... \"36aa005ffd4e\" mcp/loop1-uncut-total :loop1-uncut-total})" :ms 287 :tok 70
  :verdict :ugly :note "answers #object[datahike.db.TxReport]: no entity, no next step"}
 {:step 4 :form "(defn- tree ... +total)" :ms 18 :tok 330 :verdict :blocker
  :note "returns the Var, writes NO entity (branch digest = cluster digest); fault: seon.turn.batch/span-of refused nil. clj-kondo records usages inside macro expansions (-> of cond->, if of case) with no position; join-write scans every usage for an expanded definition (defn, defn-), so ANY definition whose body uses cond->/case is not persisted, and its whole batch with it."}
 {:step 5 :form "(defn p-solo ...) with MCP namespace user" :ms 0 :tok 40 :verdict :confusing
  :note "the Var landed in seon.render.value: the hosted agent keeps its namespace across calls and the MCP namespace argument is ignored while the reply says user (09-27 #15, still open)"}
 {:step 6 :form "(defn ...) in a namespace that does not exist yet" :ms 2 :tok 80 :verdict :confusing
  :note "refused 'Source submission requires an agent with an assigned namespace'; the agent has one, the target namespace is new"}
 {:step 7 :form "(my.program/ns-unmap! 'seon.render.value/p-solo)" :ms 378 :tok 150 :verdict :confusing
  :note "succeeded, but the reply labels the value :error with exception true; 69,414 armed calls for one deletion"}]
```

Loop 1 so far: 13 SCI evaluations and 1 JVM probe, about 6k tokens read, 4 replies needed a second evaluation to
understand, 0 manual steps outside the REPL (the fix itself went to a patch because its file is held).

## 2. Blockers and fixes

| # | Step | Blocker | Root cause | Fix |
|---|---|---|---|---|
| B0 | 2 (prototype) | a contracted `defn` of a function reached by file tests is refused ("its gate failed") | default runs 29bdfce8d; the install gate (`turn.clj:3592` there) was deleted by S4a bb47fd6d1, not installed | **RESTART NEEDED** (root): default onto HEAD |
| B1 | 3→4 (propose → prepare) | root cannot prepare an agent's proposal | `propose-merge!` records a task proposal (merge task, proofs); `prepare-merge!` took `:seon.issue/id` and read `:seon.issue/tests` | `source/prepare-merge!` takes the proofs (test symbols); `my.program/prepare-merge!` takes an open proposal from `proposals`; issue fixtures deleted from merge/write-back/source tests |
| B2 | 4 (accept) | accept refused unless every reaching member was green | `accept-merge!` gated the whole run; AGENTS: "reaching-test counts are shown, not gated" | accept gates on the proofs; prepare names the run's red members as `:seon.source/red` |
| B3 | 4 (close) | an accepted proposal stays open forever | no writer for `:seon.task/outcome` | `seon.task/decide!`; `my.program/accept-merge!` records `:accepted` |
| B4 | 5 (write-back) | every real change refuses `:one-file` | a function and the test proving it live in two files; write-back proved exactly one | `proven-files` groups the changes by file and proves each; `write-back!` installs each and answers one entry per file (`:seon.source/files`) |

| B5 | 2 (prototype) | a `defn` whose body uses `cond->`/`case` returns its Var but writes no entity | `seon.turn.batch/span-of`'s output contract demanded ints; positionless expansion usages are real analyzer data | contract admits the positionless span (`tmp/dogfood-loop-opus/span-of-positionless-usages.patch`, with regression `host-test/a-definition-whose-body-expands-macros-persists`; batch.clj is one-path-impl-2's) |
| B6 | 4 (close) | the agent's branch lives on after its merge | nothing retired it | `my.program/accept-merge!` retires the proposing branch through `my.agent/retire-branch!` with the tested one |

Regressions: `seon.cluster.host-test/a-proposal-waits-for-its-assigned-tasks-proofs-then-reaches-root` (extended: root
prepares the proposal it read; a decided merge task is closed), `seon.cluster.write-back-test/a-merge-writes-each-change-into-its-own-file`
(new), and the converted `seon.cluster.merge-test`, `write-back-test`, `source-test` merge cases.

## 3. Still awkward (not blockers)

- `source`/`doc` are not referred in the agent's namespace (09-27 #10).
- A function whose only caller is host code through `requiring-resolve` (the SCI hook) has no static reaching test, and
  a branch change to it cannot be observed by a test on the branch: the hook calls the compiled Var.
- A refusal returned by the native-call hook arrives as `:value`, 40k tokens, 793 ms (`breaks` on a hub; task 5087cea7800e).
- `seon.test/verified?` costs about 55 ms per test.
- The agent's branch is retired by MCP `branch retire`, not when its merge closes.
- Default's indexed file digests are its running commit's; write-back refuses `:file-changed` for any file the checkout
  changed since (13 test files under `test/my/` right now). Write-back needs default on the checkout's HEAD.

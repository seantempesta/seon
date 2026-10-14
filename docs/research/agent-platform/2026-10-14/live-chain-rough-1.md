---
title: "Live chain, rough run 1: Prove then Implement for the seon.ref/section pathless-reference defect, on the installed single-task path with DeepSeek. Prove's first attempt deleted its own proof and parked; its second reused the two existing red tests and auto-merged in 6 calls. Implement made both green in 14 calls with no hand act, but its merge is refused: a write-back conflict with lane s3d-2's uncommitted src/seon/ref.clj. 38 model calls, 570k prompt tokens, 6 hand acts after launch"
type: research
status: complete (root operator, 2026-10-14); Prove merged, Implement :done with its merge request open (write-back conflict), both tasks :held, config restored
created: 2026-10-14
---

# Live chain, rough run 1

Subject: the Design result of `seon.ref/design-a-pathless-reference-section` (run note
`design-prototype-run-1.md`), carried by root into a Prove task, then an Implement task, each launched like design
run 1: an ordinary task created on main by root, a tagged proof, one `:running` fact, the reconciler, model
`deepseek-flash`. `seon.task/chain!` was not used.

**Broken first.**

1. **Prove attempt 1 deleted its own proof and parked with no end.** At 08:37:44.979 the agent evaluated
   `(my.program/ns-unmap! 'seon.prove-prototype-test/the-next-task-has-red-proofs)`. That was its own tagged
   proof. The TASK-CHAIN §4 Prove sentence told it to: "Unmap scratch helpers and any deftest you do not mean as a
   proof … a red test outside the next task's proofs keeps you from done". The task's obligations still named the
   proof, so the test step asked for it three times. Each request was refused `:seon.test/identity-unresolved`.
   At 08:37:48.084 `seon.turn/record-write-refusal-fault!` parked the turn proc (fault signature `c10a0903fc33`):
   "with no open turn had 3 consecutive turn writes refused (bound 3); the turn proc is parked and will not
   re-fire. Latest refusal: The requested test is not eligible." There was no attempt end and no further turn.
   Root saw only an open fault. Filed as `seon.turn/an-unmapped-task-proof-parks-the-turn-with-no-end`.
2. **A closed attempt with no end blocks its task.** `my.root/close!` wrote `:seon.task/outcome :closed` and the
   reason, and deleted the branch, so attempt 1's evidence branch is gone. It wrote no `:seon.task.attempt/end`.
   `seon.cluster.agent/launch-facts` counts every attempt without an end as open, so `:running` again started
   nothing, and `:stopped` did nothing either. Root retracted the attempt row by hand (`:db/retractEntity`); only
   then did attempt 2 launch. Filed as `seon.task/a-closed-attempt-without-an-end-blocks-its-task`.
3. **The Implement merge is refused: a write-back conflict with another lane's uncommitted file.**
   `my.root/merge!` answered `:seon.source/write-back-refused :conflict` on `src/seon/ref.clj`: "The file changed these
   declarations since your branch's base, and your branch changed them too". The declarations are `seon.ref/section`
   and `seon.ref/section-error`. Lane `s3d-2` holds `src/seon/ref.clj` (and `test/seon/ref_test.clj`,
   `resources/seon/schemas/seon.ref.edn`) with uncommitted edits that rewrite `section`'s body. This is the write-back
   doing its job, but the installed merge has no way to rebase a two-line fix onto the owner's working copy. The patch
   is given below for the owner. The merge request stays open (`my.root/merge-requests` lists `51efd81cbc42`).
4. **Prove merged to main on its own, before any root review.** At `:done` the installed path auto-merged attempt
   `0ca38c28ec74` (`:outcome :merged`, 2 declarations). This follows the owner's 2026-10-12 auto-merge ruling, but
   it contradicts this run's brief ("root reviews and merges") and TASK-CHAIN (main stays clean until the chain's one
   final merge). Root reviewed the diff afterwards: the two retags below, which were correct. No write-back reached
   `test/seon/ref_test.clj` (no `implement-a-pathless` tag in the working tree). The retags live on main only,
   beside lane s3d-2's dirty copy of that file.

**What went right.** Prove attempt 2 took 6 calls and 26 s. Implement took 14 calls and about 70 s with no hand act,
turned both proofs green and kept every reaching test green. Its fix is the owner's verified two-part fix:

- the producer skips `locate` for an empty path;
- the error schema and `section-error` make `:seon.ref/path` optional.

## Setup (before launch, not counted)

- **Default**: pid 88253, booted 08:00:09 UTC from `data/source/5fddc2bfd…`. It has no `my.task/update!` (S1d-2
  is later) and the `:prove`/`:implement` step machinery of the copies design. The Design run's branch was gone (the
  store had been reset), so root rebuilt the plan from `design-prototype-run-1.md` "What it wrote". The text the note
  elided was filled from the cited sources.
- **Tasks**, created on main by root with `seon.task/create!`:
  - `seon.ref/prove-a-pathless-reference-section` (`0d0e604c3fd9`): `:seon.task/step :prove`; plan = Problem +
    What exists + Approach; instructions = TASK-CHAIN §4 Prove adapted to the installed path. In place of
    `my.task/update!` on the next task's proofs: "tag it `^{:seon/tasks #{'seon.ref/implement-a-pathless-reference-section}}`".
  - `seon.ref/implement-a-pathless-reference-section` (`90a6132f928c`): `:seon.task/step :implement`,
    `:seon.task/needs` the Prove task, the same plan, §4 Implement's instructions, `:held`. On the installed path,
    Prove's product (`seon.task/obligations`) is the set of tests tagged with a task that needs it. A product test
    red by design passes `seon.cluster.source/results-verdict`.
- **Proof** (no S4a template was committed): `seon.prove-prototype-test/the-next-task-has-red-proofs`, defined on
  main by root at the REPL, as in design run 1. Its namespace's requires were recorded correctly this time (D4 did
  not recur). It asserts:
  - the Prove task's `:seon.task/product` (read through `(seon.test/task)`) is non-empty;
  - each product test, run alone with `clojure.test/report` rebound, reports `:fail` or `:error`;
  - every function the Approach changes (`seon.ref/section`, `seon.ref/section-error`, hard-coded) is reached by
    one of them (`my.program/tests-reaching`).

  Before launch it was red on main (no product). On a probe branch it went green once one tagged red test was added
  (run `f387059febca`, 3 passes). Source:

  ```clojure
  (ns seon.prove-prototype-test
    (:require [clojure.test :refer [deftest is]] [my.program] [seon.test]))

  (defn- report-types
    "The clojure.test report types the body of test `sym` produces when run alone; [:unloaded] when no such test resolves."
    {:malli/schema [:=> [:cat :qualified-symbol] [:vector :keyword]]}
    [sym]
    (if-let [v (resolve sym)]
      (let [seen (volatile! [])]
        (binding [clojure.test/report (fn [m] (vswap! seen conj (:type m)))]
          (try ((:test (meta v))) (catch Throwable _ (vswap! seen conj :error))))
        @seen)
      [:unloaded]))

  (deftest ^{:seon/tasks #{'seon.ref/prove-a-pathless-reference-section}} the-next-task-has-red-proofs
    (let [product (vec (sort (:seon.task/product (:seon.task/obligations (seon.test/task)))))
          approach '[seon.ref/section seon.ref/section-error]]
      (is (seq product) "the next task has at least one proof")
      (is (= [] (remove #(some #{:fail :error} (report-types %)) product))
          "every proof of the next task runs red by an assertion or an error of the code under test")
      (is (= [] (remove (fn [f] (some (set product) (:seon.program/gating (my.program/tests-reaching f)))) approach))
          "every function the Approach changes is reached by a proof")))
  ```

  It is not committed as a file: `bin/lane` cannot commit a red-by-design proof (design run 1, D2).
- **Config**, the paid run: `bin/seon config apply default` with `{:seon.config.ai/no-provider :seon.config/absent
  :seon.config.run/max-episode-runs 40 :seon.config.run/relaunches 0}`. The second apply added `relaunches 0` so no
  automatic relaunch could spend. Restored after the run to `no-provider true`, `max-episode-runs 100`,
  `relaunches 2` (verified with `seon.config/effective`). Each apply answered `converged? false` (C3, as before).
- **Launch**: `[:db/add [:seon.task/id "0d0e604c3fd9"] :seon.task/execution :running]` at 08:36:53 UTC.

## Grades

| Task / attempt | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End | Merge |
|---|---|---|---|---|---|---|---|
| Prove `3fbcda3a474f` | 18 | 307,222 (181,376) | 4,477 | 55 s to park (08:36:53 → 08:37:48) | about 50 | none: parked | closed by root |
| Prove `0ca38c28ec74` | 6 | 81,152 (36,992) | 1,926 | 26 s (08:40:42 → `:done` 08:41:08.1) | 38 | `:done` | **auto-merged**, 2 declarations |
| Implement `51efd81cbc42` | 14 | 181,556 (91,520) | 3,489 | 70 s (08:42:37 → `:done` 08:43:47.6) | 44 | `:done` | refused `:seon.source/write-back-error` (conflict), open |
| **Total** | **38** | **569,930 (309,888)** | **9,892** | | | | |

No call ran to the cap of 40. No agent repeated one verdict three times, so the loop rule never fired.

**Hand acts after launch, 6:**

1. `my.root/close!` on the parked Prove attempt 1, with its reason.
2. Fixed the Prove instructions: `seon.task/create!` with `:seon.task/basis`, after setting the task `:held`. The
   unmap sentence now ends: "Your own proof, `seon.prove-prototype-test/the-next-task-has-red-proofs`, is not yours
   to change or unmap."
3. `:execution :stopped` on Prove, to stop the parked graph. It had no visible effect.
4. Relaunch: `:db/retractEntity` of attempt 1's row plus `:running`. Attempt 2 started at once.
5. The planned carry: `:running` on Implement. Plan and proofs needed no copying, since the plan was unchanged and
   the tags carried the proofs through Prove's merge.
6. `my.root/merge!` on Implement after reading its diff (refused, conflict).

After the run: both tasks set `:held`, config restored, and probe branches `live-chain-probe` and `lc-probe2`
retired with `my.agent/retire-branch!`.

**Errors by signature:**

| Signature / type | Where | Count |
|---|---|---|
| `c10a0903fc33` `seon.turn/write-refusal-error` (parked turn proc) | Prove 1 | 1 |
| `:seon.instrument/contract-error` `my.program/search` argument 0 `database` | Prove 1 | 2 |
| `:seon.instrument/arity-error` `my.program/search` (3 arguments) | Prove 1 | 1 |
| `my.program/source` "No program declaration, namespace or schema names that subject." on a task symbol | Prove 1: 3, Prove 2: 3 | 6 |
| "Unable to resolve symbol: clojure.string/subs" or `str/subs` | Implement | 6 |
| program facts refused for a `seon.ref/locate` redefinition | Implement | 1 |
| `:seon.source/write-back-error` `:conflict` on `src/seon/ref.clj` | Implement's merge (auto, then root) | 2 |

## What each agent did

- **Prove 1.** It read the right owners first: `section`, `section-error`, `locate`, `nearest-files`, the two red
  `seon.ref-test/a-reference-with-no-path-*` tests and the schemas. It reproduced the throw for `"#the-goal"` and
  `"#"`, and ran both red tests with `t/run-test-var` (fail 2/pass 1; error 2). Then it unmapped its own proof (Broken
  first, item 1). It never wrote a test.
- **Prove 2.** It made the same reads. Then it redefined the two existing red tests with
  `^{:seon/tasks #{'seon.ref/a-reference-with-no-path-is-a-missing-file 'seon.ref/implement-a-pathless-reference-section}}`,
  unchanged otherwise, and ended. The Implement task's obligations became exactly those two tests, and the Prove
  proof went green.
  - *Judgement.* Reusing the existing red tests is what the instructions prefer, and they cover the boundary rows
    (`"#the-goal"`, `"#"`, no `:seon.ref/path` key, the nearest files). But Prove added nothing: the proofs already
    existed on the filed task `seon.ref/a-reference-with-no-path-is-a-missing-file`. That matches TASK-CHAIN
    §2.1 "from a filed task", where the entry's proofs become Implement's.
- **Implement.** It read the owners, then:
  - tried `locate` with `[:string {:min 0}]` (refused) and restored it;
  - spent 6 forms on a nonexistent `str/subs`;
  - registered `:seon.ref/section-error` with `:seon.ref/path` optional, as the plan gave it;
  - changed `section` and `section-error`.

  All six reaching tests ran green on its branch: the two proofs, `a-missing-heading-reply-names-the-nearest-heading`,
  `a-referenced-doc-shows-its-heading-size-and-one-read`, `a-missing-anchor-names-the-closest-spelled-heading-first`
  and `seon.bootstrap-test/help-answers-one-section-by-its-anchor`.

## Root's review of the Implement diff

```diff
-        located (locate path)]
-    (if-not located
+        located (when (seq path) (locate path))]
+    (if (or (not (seq path)) (not located))
       (section-error :file path anchor (nearest-files path))
```

```diff
-  {:malli/schema [:=> [:cat :keyword :seon.ref/path [:maybe :seon.ref/anchor] :seon.ref/nearest] :seon.ref/section-error]}
+  {:malli/schema [:=> [:cat :keyword [:string {:min 0}] [:maybe :seon.ref/anchor] :seon.ref/nearest] :seon.ref/section-error]}
 …
-                               :seon.error/offending (cond-> {:seon.ref/path path} anchor (assoc :seon.ref/anchor anchor))
+                               :seon.error/offending (cond-> (when (seq path) {:seon.ref/path path}) anchor (assoc :seon.ref/anchor anchor))
                                :seon.error/expected nearest
-                               :seon.ref/section-missing missing :seon.ref/path path :seon.ref/nearest nearest}
+                               :seon.ref/section-missing missing :seon.ref/nearest nearest}
+                        (seq path) (assoc :seon.ref/path path)
```

The schema change: `:seon.ref/section-error` gets `[:seon.ref/path {:optional true} :seon.ref/path]`.

Verdict: **correct behavior, with three flaws to fix when the owner applies it.**

- The `path` argument is an anonymous inline `[:string {:min 0}]`, not the Approach's `[:maybe :seon.ref/path]` over a
  named schema.
- `(not (seq path))` in the `if` is redundant, because `located` is already nil.
- For `"#"` (no path, no anchor), `:seon.error/offending` is stored as nil, a nil-valued key. Absence should be no
  key.

Root would merge it as-is and file those flaws; the conflict prevents that. **Patch for lane s3d-2** (the owner of
`src/seon/ref.clj`): apply the two hunks above to its rewritten `section`, where the `located`/`if-not located` lines
are unchanged, and to `section-error`, plus the schema registration in `resources/seon/schemas/seon.ref.edn`.
Then `my.root/close!` merge request `51efd81cbc42`, or merge it once the file is committed.

## Context and REPL friction (dogfooding evidence)

- **The Prove instructions invite deleting the task's own proof**: the agent applied "unmap … any deftest you do
  not mean as a proof" to its own red proof. The same sentence is in TASK-CHAIN §4 Prove. Proposed edit: name the
  task's own proofs as untouchable, or have the block mark them "yours, judged; do not edit".
- **`my.program/search` is called wrong**: `(my.program/search :seon.test/sym "seon.ref-test/")` returned "refused
  argument 0 `database`: expected :seon.db/database-value … got a keyword :seon.test/sym". Then
  `(my.program/search {:seon.db/db (db/db)} :seon.fn/sym "a-pathless")` returned "expected a declared argument count
  (2), got an argument count of 3". The agent never got a search result and gave up on search (`my.program` tasks
  file is held: listed, not filed).
- **`my.program/source` on a task symbol is still refused** (design run 1 D6): six times across the Prove attempts,
  each for the next task or the filed task. The fix line names functions, namespaces and schemas, never
  `my.task/current`.
- **An unresolved alias var has no nearest**: `(str/subs "#the-goal" 1)` gave "Unable to resolve symbol: str/subs"
  with no suggestion of `clojure.core/subs`, and the agent retried six times. The same error for `seon.agent/id`
  carried a `:seon.error/nearest` list.
- **A misleading admission refusal**: Implement's `(defn locate … {:malli/schema [:=> [:cat [:string {:min 0}]] [:maybe :seon.ref/location]]} …)`
  was refused with "seon.ref/locate has a bare nilable return. Return an explicit result/error envelope …". The
  return was unchanged from the admitted definition; only the input differed. The agent read it as "do not change
  locate" (right outcome, wrong reason) (`seon.fn` tasks file is held: listed).
- **Root's own frictions**:
  - The nearest list offered `(seon.cluster/root-agent-id)` as a call, but it is a def (a String), so the call threw
    ClassCastException.
  - `java.time.Instant` and datahike `TxReport`/`Datom` print as `#object[…]`.
  - A namespace docstring is dropped from the recorded `:seon.ns/source`.
  - `:auto-required #{seon.ref}` fired for symbols that appear only quoted.
  - A failing `def` leaves the next form "find not supported on type: sci.impl.vars.SciUnbound".
- **Cost, measured on root's probes**:
  - One eval request that defined one test took a test step of 7.1 s and 10.3 GB (first request on a fresh agent
    branch), then 3.1 s and 6.9 GB, then 2.0 s and 2.5 GB. The tests themselves ran 31–36 ms.
  - `my.test/check` of one 36 ms test took 2.2 s and 2.1 GB.
  - During Prove 2 and its auto-merge gate, the JVM window showed 88 GB allocated and 113 s CPU over 40 s of wall
    time.
  - These belong to the filed test-step cost tasks in `resources/seon/tasks/seon.turn.edn`. They are not new.

## What TASK-CHAIN's automation must replace, act by act

| Hand act or workaround here | TASK-CHAIN mechanism |
|---|---|
| Two `seon.task/create!` calls, `:seon.task/needs`, `:step` | `seon.task/chain!` over `seon.task/from-filed` or templates (§2.1) |
| Tags `:seon/tasks` naming the next task, in place of writing its proofs | `my.task/update!` on the next task's `:seon.task/proofs` (§4 Prove) |
| A hand-written Prove proof on main, hard-coding the Approach's functions | `seon.step.prove/handoff` with `cited-fns` over `approach` (§4) |
| Root's `:running` on Implement | advance in `run-pass!` (§2.5) |
| `close!` + `:stopped` + retracting the attempt row + `:running` | bail to root with `:closed` and `:held`, then `my.root/run!` (§2.7) |
| A parked turn proc on an unmapped proof | the done rule over recorded results: a missing proof is a refusal, not a write refusal (§2.4) |
| Prove auto-merged mid-chain | one final merge of the chain branch (§2.6) |
| `relaunches 0` set by hand to stop unattended spending | no automatic relaunch (owner ruling) |
| Deriving per-attempt calls and tokens with ad hoc branch queries | root reads the chain branch (§2.2); a spend report remains ROOT-AGENT work |

## Filed

- `seon.turn/an-unmapped-task-proof-parks-the-turn-with-no-end` (`resources/seon/tasks/seon.turn.edn`).
- `seon.task/a-closed-attempt-without-an-end-blocks-its-task` (`resources/seon/tasks/seon.task.edn`).
- Listed only, because their task files are held by other lanes: `my.program/search` misuse, the unresolved-alias
  nearest, the misleading `locate` admission refusal, the `ns` docstring drop, and the def offered as a call form.

## Residue on default

- `seon.ref/prove-a-pathless-reference-section` is `:held`; attempt 2 is merged (its two retags are on main).
  Attempt 1's row was retracted (history holds it), and its branch was deleted by `close!`.
- `seon.ref/implement-a-pathless-reference-section` is `:held`; attempt `51efd81cbc42` is `:done`, its merge request
  open, and its branch kept with the fix.
- `seon.prove-prototype-test` is defined on main by root, as program only (no file).
- Config is restored. Probe branches are retired.

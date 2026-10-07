---
type: notes
status: active
created: 2026-10-07
tags: [review, errors, agent-loop, guides]
---
# Review of 18c3d1286 — review-final-codex

Reviewed the commit stat, final-revision notes and all six assigned files end to end. Agent-loop §3 was read only to
identify its boundaries and dependencies; its bytes are unchanged from 18c3d1286. No replacement context design is
proposed here. The user's 2026-10-07 rejection supersedes the old ruling wherever it is cited outside §3.

## Broken or degraded first

- Default answered both status entrances but was `:faulted`: 16 open faults, 173 errored receipts, 43 unknown tests,
  3 definition regressions, 11 over-bound warnings and one cache-discipline warning. Running archive:
  `42e569d9d911140148b401ebbe04f10c4fe61ae7`, PID 65136, start `2026-10-07T05:18:32.308Z`.
  Heap used was approximately 2.96–2.98 GB of 11.00 GB. No stop, reset or restart was performed.
- Found an installed empty-input defect in the contracts checker. Recorded task
  `seon.contracts-compile-test/empty-input-calls-analyzer-with-a-refused-empty-source-map`, handle `67618a6a2862`,
  after querying the task home and finding no task in that namespace. It is not fixed in this documentation lane.
- The error chain's claimed identity membership is not installed. Distinct equal Throwables collapse into one.
  This is additional evidence for existing task `seon.error.refusal/preserve-primary-and-suppressed-causes`.
- P5 still needs an owner decision for completion across two branch connections. Context-dependent implementation
  remains blocked on the owner's redesign. Neither is represented as executable or proven.

## Evidence conventions and boundaries

All live forms used MCP `eval_clj`, agent `root`, cluster `default`, mode `jvm`; database reads obtained
`(seon.cluster.boot/connection "default")` explicitly. No provider calls, test runs, source reloads, Var replacements,
or other agents' sessions were used. Temporary Exception objects and pure forms were local to their evaluations.
Durable operations outside Git were evaluation bookkeeping, the new checker-defect task and the tool's required
publication/adoption rehearsal on disposable branches.

Installed citations below refer to archive 42e569d9d, not the concurrently edited source tree. The original review
candidate was HEAD 392896c51. Other lanes subsequently committed source changes, so final static validation used a
new archive of HEAD 9d6b392b67921480e5d193b5e754063cbed70db5 plus this lane's exact documentation diff. No worktree
was created. Task-tags' schema/source/test edits and source-display's edits were foreign ownership boundaries;
none were copied into the candidate unless already committed, restored, edited, messaged or resumed.

Read dependency seams included Malli `-instrument-f`, `validator`, `explainer`, `with-spell-checking`,
`with-error-messages`; Datahike `:db.fn/call` and `validate-report`; Flow's proc transform/exception path; and the
Clojure collection operations used in the rewrite tables. The listed gitlinks were compared with HEAD's recorded
pins. Historical timings and line counts remain historical evidence, not new measurements. Target functions and
unwired producers are not installation proof; the live resolution probe explicitly found `caught`, `fail!`, `pass`
and `my.task/complete!` absent. No future slice's behavioral test is claimed green by this review.

## Findings and disposition

1. **Rejected context design remained an active authority — fixed outside §3.** AGENTS' completion law,
   agent-loop's opening/P4/cut/decision 7, reference-code's Flow row and testing §1 still instructed use of the
   rejected producer composition. Marked those dependencies pending the owner's design. Live `system-message` and
   `task-context` resolve, but that establishes only their existence, not wiring or approval. Section 3 is unchanged.

2. **Pass sketch could not call the installed segmenters — fixed.** `seon.turn/code-sources` and `planned-sources`
   take `[text namespace-name _max-source]` (installed lines 3047 and 3031); the sketch supplied only text. Their
   contracts return a sources/error union. `(seq sources)` also accepts a nonempty error map. Live prose-only input
   returned `:seon.cluster.reply/no-forms-error`. Replaced the incomplete sketch with explicit stage obligations,
   including union discrimination, no-forms call accounting and holding evaluated values across later failures.

3. **Pass catch claimed conversion also recorded — fixed.** Errors §3's proposed `caught` only builds a map; it
   contains no writer call. The old pass comment claimed a recorded receipt and then rethrew the original exception.
   The target now explicitly records, attaches the receipt and forwards the recorded failure without recounting;
   every submission receives one terminal reply and retains any evaluated batch. This also removes an example that
   could answer once, fail during completion, then attempt a second error reply.

4. **Cause-preserving translation could not use the shown `fail!` arities — fixed.** Neither arity accepted a cause,
   despite §2 telling translators to keep the original Throwable through it. Specified the three-argument `ex-info`
   carrier with the constructor's map and message. `fail!` now throws the returned map's message, so a malformed
   construction does not expose the authored message as though construction succeeded. Added its missing contract.
   Clojure `ex-info` is the carrier; Malli `-instrument-f` validates outputs only after a return.

5. **Boundary sketch repeated the chain despite the guide's one-walk rule — fixed.** It called `chain`,
   `cause-links`, `root-frame`, `cause-links` again and `producer`; installed `root-frame` and `producer` traverse
   again internally (`error/refusal.clj:205,552`). Removed the speculative “~20 lines” implementation. Named the
   shared-input conversion and the compressed-frame constraint explicitly. No second traversal owner is proposed.

6. **Signature's sorted problem set can throw — fixed requirement, implementation pending.** A path containing a
   keyword and another containing an integer at the same position causes default comparison to throw. Live probe
   below returned `java.lang.ClassCastException`. Use a set of problem pairs with structural hashing, explicitly
   dependent on the identity owner's pending seam. Installed `seon.id/id` hashes printed data (line 145); switching
   to an unordered set without that prerequisite would make signature order unstable. T5 now requires insertion-order
   independence and mixed path types.

7. **Report example builds nil attribution and was not a pure P4 calculation — fixed.** A top-level live form's
   `calling-definition` is nil; `var-digest` can also return nil (`refusal.clj:465–514`). The sketch unconditionally
   associated a digest and associated caller whenever the kind blamed one. Malli optional qualified-symbol fields
   reject a present nil. Replaced the incomplete code with explicit captured caller/callee/projection inputs,
   omission of unavailable optional attribution, throwing report behavior and preservation of the original report
   when the reporter itself fails. This matches Malli `-instrument-f` at 2219–2240 and AGENTS' absence law.

8. **Resolution could mistake missing proof for green — tightened.** Installed `seon.test/verified?` returns either
   a boolean or `:seon.test/host-error` (line 1548); a refusal map is truthy. `my.program/propose-merge!` already uses
   `true?` for this reason. Require nonempty proofs, literal true, current evidence and time after latest occurrence.
   Missing producer attribution or a refused lookup is unknown, not positive proof that a producer was deleted.
   T10 now includes empty/refused/stale evidence. Task-home resolution remains the stated authority.

9. **P4(c) depended on P5 while preceding it — fixed ordering.** The pass's completion stage requires the proof and
   completion owner that P5 introduces. The installed `my.task/complete!` and new pass do not resolve. Land P5 before
   P4(c); no placeholder completion implementation is prescribed. Context P4(b) remains independently blocked.

10. **P6 was falsely described as invisible — fixed activation point.** P1 adds attributes only to new settlements;
    no backfill is planned. Installed projection has neither `:seon.cluster.eval/origin` nor `:seon.agent/answered-t`;
    pre-P1 evaluations cannot satisfy the new reader queries. Prepare readers in P6 but activate them in the cut,
    with the authorized retirement of old data, not earlier under a “same data” claim.

11. **Completion spans two homes without a recovery protocol — open, flagged in P5.** §1 places disposition on the
    agent's home branch; §4 places completion and its notification at the task home. Installed `seon.task/home`
    resolves root's home (lines 296–310); those connections need not be the branch agent's. A crash after the
    completion write but before disposition can leave the loop continuing a completed assignment. The owner must
    specify idempotent recovery and the corresponding fixture before P5 lands; no third store or retry loop added.

12. **“Today revalidation uses the head captured before refusal” was stale — fixed.** Installed
    `seon.turn/settle-batch!` calls `(turn.batch/revalidate program-request (db/db connection) ...)` after
    `first-outcome` (lines 3576 onward). The spec now says to preserve that fresh acquisition.

13. **Nonrecursive literal construction failure was presented as installed — fixed.** Installed
    `refusal/error` calls `construction-refusal`, which calls `diagnostic` (lines 680–756); `diagnostic` reacquires
    projection/attribution and may build a chain. The literal emergency path is a new cut requirement. The proposed constructor also acquired its projection
    before any failure boundary; removed that incomplete body and explicitly enclosed acquisition/reference handling
    in the emergency-path requirement. Kept the
    accurate claims about cached explanation and packaged-projection fallback.

14. **Two EQUIVALENT labels lacked necessary preconditions — fixed.** With `r=nil`, no matching keys,
    `cond->` returns nil but `merge` with `select-keys` returns `{}` (live probe below). Require a map accumulator.
    `filter`/`remove` to `group-by` also changes realization: state finite input, a pure boolean predicate and a
    consumer needing both complete buckets. The quick table now preserves these restrictions instead of dropping
    them. Clojure's `select-keys`, `group-by` and `merge` are the relevant owners.

15. **“Never add a read unwrap” made new callers unsafe before S-reads — fixed.** The guide correctly said installed
    read refusals must not reach collection consumers, then forbade a new caller from handling that same union.
    `my.program/caller-data` and `seon.db/q` still expose the transitional behavior. New callers must discriminate
    the installed union and convert with their read family; the bare throwing read remains a target.

16. **Testing guidance contradicted itself about green proofs — fixed.** §1 correctly lets refactors name existing
    tests; §2.12 said every success test must start red and every all-green task is done or mis-specified. Restricted
    red-before/green-after to new behavior and regressions. Marked proof-admission enforcement as target:
    installed `seon.task/assign!` at 378–403 does not check it. Final-revision notes D4 was an owner question, not an
    accepted blanket refusal rule.

17. **The minimal identity example taught retiring behavior — replaced.** Its old outputs were accurate on default:
    `[true false false false false false]` and `abc` digests `ba7816bf8f01`/`ba7816bf`. But it duplicated a test of
    printing-dependent identity that AGENTS forbids and the identity lane is replacing. Replaced it with a short
    `symbol-in` table, whose three outputs were probed live. This is documentation only, not a new executed test.

18. **“Identity seen-set” was a false installed claim — corrected; source defect remains.** `cause-links` (line 194)
    and `chain` (line 395) use `#{}` membership, which uses equality. Two distinct equal exceptions produced only
    one primary link at default. Spec now names the installed limitation and requires identity membership in the
    existing walk at the cut; T9 includes it. Existing task:
    `seon.error.refusal/preserve-primary-and-suppressed-causes`. Source ownership was not taken from another lane.

19. **Documentation gate excluded AGENTS and skills — fixed prerequisite, commit cf0356cdd.** `bin/lane`'s
    `documentation_only` accepted only `docs/*`, so this documentation review would invoke publication/adoption.
    AGENTS explicitly says to fix a tool that publishes documentation before proceeding. Claimed `bin/lane` and
    added AGENTS.md, CLAUDE.md and skill Markdown to the existing classifier, preserving checks for other files and
    every explicit `--test`. Live `seon.test.cache/widening-path?` returned false for both skills, AGENTS.md and
    bin/lane. `bash -n` passed. The tool prerequisite itself used the ordinary lane commit check, with no tests:
    9,242 ms total (1,663 archive; 7,579 rehearsal; reported publish 5,582 and adopt 1,842 ms). These are observed
    publication costs, not a sub-second pass; no profiler established which internal derivation dominates them.

20. **Empty contract selection throws — recorded, not fixed.** Calling the installed checker with `:paths []`
    reaches `namespace-contexts` (`contracts_compile_test.clj:121–143`) and passes `{}` to analyzer `:sources`, whose
    contract requires min 1. Result `result/e0f9f608c27e4` was a contract error. A check of the cited id/refusal source
    files then passed; no claim that the empty check passed. New task named above includes this note as evidence.

21. **Filesystem refusal did not continue — fixed.** Errors §2 said `parent-access` “refuses then continues”.
    Installed `seon.fs.jvm/refuse!` throws unconditionally (line 55), and `parent-access` calls it before
    `descend` (line 225). A caught JVM probe of that helper (`result/ee1e038309ebb`) returned `:thrown`, never `:continued`. Corrected
    the source description; the target constructor conversion is still needed.

22. **Bloat cut.** Removed the copied “before” body of `transact-initialization!` (the named source is authoritative),
    the non-runnable pass and report/boundary sketches, the retiring hash fixture and the dated “mistakes lanes made”
    introduction. Kept contracts, preconditions, failure obligations and proof requirements. Corrected the opening
    error-area arithmetic: the four listed counts sum to 4,735, not 4,770. Further optional cuts: move dated census
    counts and speculative line budgets to measurements; retain the dependency/function references and slice gates.

## Reproducible live probes

Each ran in the common MCP context above. Measured ms are the form's own elapsed time, not total MCP transport or
settlement time. Result handles identify the recorded evaluation; restart can lose their live values.

```clojure
;; result/ee227bc822875, combined probe 0.934042 ms
(try (into (sorted-set) [[[0 :field] :a] [[0 1] :b]])
     (catch Exception e {:class (symbol (.getName (class e)))}))
;; => {:class java.lang.ClassCastException}
(malli.core/validate [:map [:review/caller {:optional true} :qualified-symbol]]
                     {:review/caller nil}) ; => false

;; result/ed07c2fb1bc57, combined probe 1.308125 ms
[(cond-> nil (:k {}) (assoc :k (:k {}))) (merge nil (select-keys {} [:k]))]
;; => [nil {}]
(mapv (fn [[space letter identity]] (seon.id/symbol-in space letter identity))
      [["result" \e "abc123"] ["result" \e "012345"] ["other" \r "abc123"]])
;; => [result/eabc123 result/e012345 other/rabc123]
(select-keys (seon.turn/planned-sources "Only prose." 'user 100000)
             [:seon.error/type :seon.error/operation])
;; => {:seon.error/type :seon.cluster.reply/no-forms-error
;;     :seon.error/operation seon.turn/segmented-sources}
(seon.error.refusal/calling-definition) ; => nil at this top-level entrance

;; result/e6f94685ae601, 0.188209 ms
(let [a (proxy [Exception] ["a"]
          (equals [other] (instance? Throwable other)) (hashCode [] 7))
      b (proxy [Exception] ["b"]
          (equals [other] (instance? Throwable other)) (hashCode [] 7))]
  (.initCause a b)
  [(not (identical? a b)) (= a b) (count (seon.error.refusal/cause-links a))])
;; => [true true 1], two distinct cause objects but one reported link
```

Additional live checks: `result/ea558d2d712a5` (1.167917 ms) confirmed default's timeout 180000,
no-provider true, stop `["#:seon.repl"]`, max-episode-runs 100, task-home connection identical to default,
old origin installed/new origin absent, and the target Vars absent. `result/eb97923a4376f` recorded actual Var
arglists and file/line metadata. `result/e129665906c50` confirmed the old identity example's outputs; its internal
clock was sampled too early and is not a useful timing. `result/ee227bc822875` also built the installed
`:my.fs/not-found-error` with `:my.fs/not-found` under the live projection and verified `world-reference`'s two-arg
`id/evaluation` use. No target API was called as though it existed.

## Owner questions

- Complete P5's cross-home crash/recovery protocol before its slice; how does a committed completion retire the
  branch's working disposition after a crash without sending another notification?
- Confirm when structural identity is available for the new error signature. P4 cannot rely on printed unordered
  sets. Preserve the mixed-type-path regression even after hashing changes.
- The unknown-outcome writer seam remains explicitly unverified in agent-loop §2.1. Name the terminal-outcome owner
  before implementing re-arm; do not interpret timeout as transaction exit.
- D4's proposed all-green assignment refusal is incompatible with ordinary refactoring unless tasks distinguish
  those obligations. This review does not choose an admission policy for the owner.
- Replace the context-dependent slices and tests from the context-design lane. This review neither approves §3 nor
  supplies a substitute render contract.

## Validation and root's focused list

- No test runs were started. Final candidate archive lint: `clj-kondo --lint src script test --cache false --parallel`,
  **0 errors, 1,488 warnings, 11,335 ms**. Earlier candidate lint also had 0 errors (7,404 ms). An accidental initial
  working-tree lint ran too (0 errors, 14,709 ms); it is not the candidate proof. Its log was moved immediately into
  the lane's scratch directory. Final lint was repeated because another lane committed source after the first one.
- Installed `seon.contracts-compile-test/check` against the final archive, with `:paths` selecting
  `src/seon/id.clj` and `src/seon/error/refusal.clj` and the carried projection as `:candidate`: **[]**, 102.92925 ms,
  `result/ecbde8111c990`. These are the unchanged source owners cited by the guide. There are no changed Clojure
  contracts; this was not a full-repository contract census and did not execute any test body.
- `git diff --check` and `bash -n bin/lane` passed. A direct section extraction verified agent-loop §3 equals the
  reviewed commit exactly. Documentation changes require no publication or restart. The prerequisite tool commit's
  ordinary rehearsal was on disposable branches; it did not run named tests or replace default's JVM.
- Root's current focused checks for the tool rule: `seon.test-cache-test/a-documentation-edit-never-widens-a-gate`
  and `seon.test.selection-test/documentation-only-input-changes-leave-the-input-evidence-unchanged`.
  They cover the canonical input policy; the documentation commit's output also verifies bin/lane actually skips
  publication for these paths. There is no existing standalone test of bin/lane's shell classifier.
- For eventual implementation, extend errors T3/T5/T8/T9/T10 and loop T7/T10/T16/T20 with the cases above. Preserve
  `seon.error.refusal-test/a-cleanup-failure-survives-beside-the-primary`,
  `seon.error.refusal-test/the-frame-comes-from-the-root-cause`,
  `seon.instrument-test/a-reporter-failure-is-not-relabelled-as-a-contract-violation`,
  `seon.turn-test/a-settlement-that-throws-still-shows-each-evaluated-value`,
  `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`, and
  `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`.
  These are future slice obligations, not tests this documentation change ran.

Changed runtime source/test lines: 0. The prerequisite shell tool changed +10/-4 lines. Notes and specs are the
review artifact; scratch archives and logs are owned by `tmp/review-final-codex/` and are not Git worktrees.

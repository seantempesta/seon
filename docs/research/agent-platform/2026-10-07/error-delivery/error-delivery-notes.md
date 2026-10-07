# Error delivery through MCP (lane error-delivery, 2026-10-07)

Owner ruling (2026-10-07): errors are not turned into tasks automatically; every error a call causes comes back to the
agent in its MCP reply, and agents file tasks. An agent's own mistake is only delivered; a core error with no agent
running it is recorded and root is notified.

## State at start

- `bin/seon status`: default `:faulted`, 16 open faults, 22 signatures, 174 errored receipts; `seon.error/prepare`
  over bound (mean 2.4 s, max 23.6 s, tasks already linked). Default was restarted by root at 06:14 and still runs
  42e569d9d (HEAD 0daf87818 did not adopt; root is fixing forward).
- 18 runtime tasks named `<ns>/faults of <fn>` existed on default, one per producer, all auto-created by
  `seon.error/producer-task`. Six of them were for agents' own scratch code (`user/bad3`, `user/bad4`,
  `scratch.sd-probe/measure`, `refusal-cause.probe/bad-err`, `my.auditchange/lint2`): agent mistakes recorded as
  core faults.

## Delivery table (MCP `eval_clj` on default, agent `error-delivery`)

| Class | Mode | Delivered? | Where in the reply | Handle reaches the whole error? |
|---|---|---|---|---|
| Contract refusal on a call `(my.task/open 42)` | SCI | yes | `:error` | yes (`:seon.error/type :seon.instrument/contract-error`, 24 keys) |
| same | JVM | yes | `:error` | yes |
| | | | Before the fix, both came back as one escaped **string**, because the map holds `#'seon.db/database-value?`, which EDN cannot read (fixed, below) | |
| Thrown ex-info | SCI | yes | `:error` with `:seon.error/chain` (ex-data whole) | yes; **no `:seon.error/type`** |
| same | JVM | yes | `:error` | yes; no `:ms`, `:calls` or location |
| Host exception `(Integer/parseInt "nope")` | SCI and JVM | yes | `:error`, `:exception-class` | yes; no type; JVM mode has no location |
| Deep (2000 levels) and large (200,000 items) ex-data | SCI | yes, 43 ms | `:error` with dig forms | yes: the dig forms answer depth 2000 and count 200000 |
| Later stage: renderer throws beside a good value | SCI | yes | `:value` plus `:diagnostics` | **no**: the diagnostics' dig forms are rooted at the value's handle, so `(get-in result/e… [0 …])` answers nil |
| Later stage: refused program facts (malformed contract) | SCI | yes | `:value` (Var) plus `:diagnostics` | no handle; 31 frames printed, the rest "…22" with no dig form |
| Error inside a test via `my.test/check` | SCI | yes | `:value` `:seon.test/results` with failure reports | partly: the error is stored as **printed text**, its frames are the runner's, not the throw site, and the same text is printed again into `:out` (733 tokens) |
| Timeout (30 s loop) | SCI | yes, `:interrupted` | `:error` | yes; the reply says "time-limit" while the value says "Ran out of time after 30009ms."; no type or bound |
| Core error in another thread during the call | SCI (future + `seon.fault/fault!`) | yes, same reply | `:errors` | **no handle**; the entry names layer `:seon.error/normalization` and operation `seon.sci.kernel/adopt-arm` although the boundary passed `:seon.flow/proc` / `seon.flow/probe` |

Every error the probes caused reached that same reply. No class was silently dropped. The gaps are in how
reachable and how accurate the delivered error is.

## Agent mistakes recorded as Seon errors (step 3)

- Agent forms (contract refusals at the agent's call, ex-info, host exceptions, render failures, malformed contracts,
  timeouts): **not recorded**. No signature appeared on the agent branch or the cluster branch, and root got no message.
- **An agent's own definition failing was recorded.** Reproduction:
  `(defn probe-bad "…" {:malli/schema [:=> [:cat :int] :int]} [x] (str x)) (probe-bad 1)`
  - It recorded signature `af9edf17…` on the agent branch and on the cluster copy.
  - It notified root (message `98ab3de7c2c4`).
  - It auto-created the task `scratch.error-delivery/faults of probe-bad`.
  - The producer was `scratch.error-delivery/probe-bad`, an agent definition.

  The decision is in `seon.batch/produced-failures`: it recorded every failure whose error names a producer, and the
  blame rule names the agent's own definition as the producer. **Fixed** (below).

## Fixes (this commit)

1. **No auto task.** `seon.error/producer-task` is deleted, along with `auto-task-tempid` and the `root-present` read
   that only it used. The recording transaction writes the error, its occurrence and the notifications, and no task.
   The notification to root is unchanged.
   - What this makes possible: an error is no longer a task by default, so the work queue no longer fills with
     "faults of" tasks per producer. Those tasks were what kept fixed faults open (`seon.error/a-fixed-fault-with-an-answering-task-has-no-proof-path`).
     New faults resolve on their producer's digest change.
2. **An agent's own definition is its mistake.** `seon.batch/produced-failures` now records a failure only when its
   producer is a compiled host Var (`compiled-function?`, `Namespace.findInternedVar`) that this batch has not
   redefined by that entry. The per-entry digest bookkeeping existed only to label in-batch definitions, and those
   are no longer recorded, so it is deleted.
   - BEHAVIOR CHANGE, with the regression `only-a-compiled-producer-not-redefined-by-the-batch-is-a-fault`, a table
     over `produced-failures`.
   - The old batch-level test (`a-definitions-failure-in-an-evaluation-is-its-fault-and-task`) is deleted. It asserted
     the auto task, and it was already red on the store (latest member: 1 pass, 5 fail, 1 error). Cause, found in
     candidate run 5e09c06784b2: `seon.test-support/code-request` frames every form with an empty source. The input
     contract of `seon.sci.eval/evaluate` refuses that, so no form of a fixture batch evaluates. My replacement
     through that fixture failed the same way. The fixture defect is task `53a84ea737eb`
     (`seon.test-support/code-request-frames-an-empty-source-every-form-is-refused`); it also reaches 9 callers in
     `seon.fn-test`, `seon.run-test` and `seon.turn-test`.
3. **Errors in the printer's grammar are data in the reply.** `seon.repl/data-text` decided "data or string" with the
   EDN reader, which cannot read `#'v` (every `[:fn #'pred]` contract) or `#"re"`. So every contract refusal's
   `:error` came back as one escaped string.
   - It now asks `one-form?`: Clojure's reader with `*read-eval*` false and `tagged-literal` for unknown tags, the same
     reader the reply test uses.
   - `read-shown` loses its unused tag argument.
   - Regression: `an-error-in-the-printers-grammar-is-a-map-not-a-string`, a table over a host object, a Var and a regex.
4. The errors spec's Opening paragraph, T5, T11 and the data-flow lines are revised in place: an opening creates no
   task, and an agent or root files one with `seon.task/create!`. The `seon.fault/fault!` comment no longer claims the
   cluster copy carries a producer task.

The fault-resolution machinery is **kept** because the 18 legacy tasks still close through it:

- `:seon.task/faults` in `seon.task/task-resolution` and `my.task/open`;
- the proof branch of `seon.error/resolution`.

Its tests now file the task the way root or an agent would (`triaged-task!`: `seon.task/create!` plus the legacy
`:seon.task/faults` link), because nothing writes that link anymore. Deletion is recorded as a task.

## Tasks (created with `seon.task/create!` on default)

| Task | Handle | Class |
|---|---|---|
| `seon.sci.eval/diagnostics-dig-forms-point-into-the-value` | 9b7f370c8159 | diagnostics unreachable from any handle |
| `seon.render.transcript/delivered-errors-carry-no-result-handle` | 76199fa86cc3 | `:errors` entries have no handle |
| `seon.fault/a-delivered-fault-names-the-normalizer-not-its-boundary` | f22480816fac | wrong layer/operation in delivered error |
| `seon.sci.kernel/an-agent-exception-has-no-error-type` | 7e3649d11171 | ex-info, host exception, timeout have no `:seon.error/type` |
| `seon.test.runner/a-test-error-shows-runner-frames-not-the-throw-site` | dca4513c58e5 | test error loses throw site, printed into `:out` |
| `seon.batch/an-earlier-redefinition-of-a-host-function-fails-as-a-core-fault` | 1de24e16cf30 | remaining gap of fix 2 |
| `my.task/open-offers-no-authored-tasks` | 522207da3de3 | queue empty of new work once auto tasks stop |
| `seon.error/fault-task-closing-has-no-writer-left` | 19fcd6bc2d67 | legacy `:seon.task/faults` closing; open resolution decision |
| `seon.mcp/jvm-mode-replies-omit-time-calls-and-location` | 799a4645d7cf | JVM-mode reply parity |
| `seon.test-support/code-request-frames-an-empty-source-every-form-is-refused` | 53a84ea737eb | fixture batches refuse every form; red on HEAD |

Linked rather than duplicated:

- `seon.error.refusal/errors-capture-whole-stack-traces`: 31 printed frames in a gate diagnostic, 12 in a render
  diagnostic.
- `seon.test.runner/failure-evidence-is-stored-as-text`.
- `seon.test/one-test-run-costs-seconds-around-a-147-ms-body`: `my.test/check` of two tests took 8,333 ms and 187,106
  armed calls, with 60 ms of run time per test; `seon.schema/projection-cache-value` was called 24,097 times.
- `seon.sci.eval/the-defn-reply-prints-before-its-own-change-applies`:
  `(my.test/check {:seon.test/changed ['…/probe-g]})` in the same call as the defn refused "Changed identity has no
  definition", because the definition is not settled until the pass ends.

## Probe residue on default

- The cluster now holds two probe signatures: `4e2b607d…` (simulated async core fault) and `af9edf17…`
  (`probe-bad`), plus two auto tasks the old code made for them (`seon.sci.kernel/faults of adopt-arm`,
  `scratch.error-delivery/faults of probe-bad`). Root received two notifications for them.
- `af9edf17…` reads resolved on the cluster, because its producer is not defined there.
- I resolved both on my branch. The agent's custody refused the cluster write (correct). Root:
  `(seon.problems/resolve! (seon.cluster.boot/connection "default") "4e2b607d550900c5c7f2900c276b96c69509c8ad9de880da021ef22ecffb01fc")`.

## Design check

- The ruling held against the code. The task write was one `cond->` clause in `seon.error/commit-call` and came out
  cleanly; no reader of the writer depended on its tempid.
- **Awkward part: the queue and resolution were built on the auto task.**
  - `my.task/open` lists only tasks with faults or signals.
  - `seon.error/resolution` holds a fault open until a task's proof holds.
  - With no writer for the link, both serve only legacy rows. Their tests had to write the link themselves; that is
    a one-datom fixture on a task the canonical writer made.
  - The two follow-up tasks above own the real decision: what the queue lists, and how an untriaged error resolves.
    The errors spec's Resolution section still says "a digest change alone never resolves a surviving producer".
    That is now false for faults without a task. It is the spec owner's decision, flagged rather than rewritten.
- **"Agent's own" is decided by whether a compiled Var exists.** That is sound for scratch namespaces and for
  in-batch redefinitions. It misses one case: a host function the agent redefined in an earlier batch (task
  `1de24e16cf30`). Comparing the digest that ran with the compiled one is the complete rule. It needs the branch's
  definition digest at the failure, which the batch does not read today.
- Delivery itself is sound: every probed class reached its own reply. The gaps are in reachability (handles on
  `:diagnostics` and `:errors`) and accuracy (missing type, wrong layer). Each has a task at its producer.

## Verification

- Candidate proof before the check could run: default's JVM still ran 42e569d9d, and its publication rehearsal
  refused HEAD's task symbols, which is unrelated to this slice. The proof was the operator's
  `candidate-revision!` + `candidate-tests!` in a throwaway JVM on a disposable store, on HEAD 0daf87818 plus this
  slice (candidate edaed81ce39e).
  - Run ab1dd18a0425 executed 88 tests over `seon.error.attribution-test`, `seon.error.resolution-test`,
    `seon.task-queue-test`, `seon.repl-test` and `seon.error-test`: 497 pass, 0 fail, 0 error.
  - Timing: 48.2 s of tests, 98.6 s boot, 2 m 35 s wall.
  - The earlier run 5e09c06784b2 is the one that found the fixture defect above.
- Then `bin/lane commit` with the same tests, once root reports default is up.
- RESET NEEDED: no. A restart (root's batched `start --head`) is needed to install the change on default. Until then
  default still runs the old recorder and keeps creating "faults of" tasks.

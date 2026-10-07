---
type: notes
status: active
created: 2026-10-07
tags: [notes, review, errors, agent-loop, guide]
---
# review-final-opus: review of 18c3d1286

Fresh-context Opus review of commit `18c3d1286` (errors.md, agent-loop.md, AGENTS.md, data-oriented-clojure and
clojure-testing skills, reference-code.md), per `review-final-spec.txt`. Agent-loop §3 (context) excluded.

**Edits: none.** Lane `review-final-codex` claimed all six reviewed files at `392896c51` before this lane started
(`bin/lane claim` refused `docs/seon/specs/errors.md`). So every fix below is a patch for that lane or root to apply.
Root's message on 2026-10-07 relayed four owner rulings (D2-D5); their exact patches are in "Owner rulings" below.

Probes: `bin/seon status` (default `:faulted`, running `42e569d9d`, 16 `:seon.problems/open-fault` (the notes saw 14),
173 errored receipts, 43 unknown tests, 3 definition regressions; `seon.batch/evaluate-batch` over bound at 2.2 s mean,
`seon.await/await-port-operations` at 1.7 s mean). Claims were checked against `git show HEAD:` source (src at HEAD
equals the running `42e569d9d` apart from deleted example tests) and JVM-mode reads on default. Lane rule: every
finding is recorded here with evidence. One task was created (below).

## Findings, most severe first

### 1. HIGH: the pass rethrows a recorded error, so seon.flow records it a second time (agent-loop §2, errors §3, reference-code.md)

agent-loop §2's `pass` catches, calls `(error/caught t)` ("recorded, receipt attached"), answers the request, then
`(throw t)` with the comment "Flow's error route; the supervisor decides". In Seon a transform's throw never reaches
Flow's error route first: `seon.flow/var-process` wraps every transform arity in
`(try (step-var state input message) (catch Throwable failure [(cond-> state (failed! failure) closed) nil]))`, and
`failed!` is `proc-failed!`, which records through `seon.fault/fault!`. The rethrown `t` is the original throwable,
whose ex-data has no receipt (the receipt sits on the map `caught` returned), so errors §3's "already recorded ... keeps
its receipt in its link's data and is not recounted" cannot hold. Every pass failure would be recorded twice. It also
contradicts AGENTS.md "Nothing crashes": no pass "ever ends in an uncaught exception". Upstream Flow
(`core.async/.../flow/impl.clj:257-320` at `dc35f3e0d7`) does resume with the old state after putting the exception on
`::flow/error`, but Seon's `var-process` catches first; reference-code.md's Agent loop row describes only upstream.

Patch, agent-loop §2 sketch:

```clojure
      (catch Throwable t
        (let [e (error/record! (error/caught t))]                   ; errors §3/§6: the entrant records once
          (when reply (async/offer! reply {:seon.agent/error e}))
          (when-not (:seon.error/recorded e) (throw t))))           ; only an unrecordable error reaches seon.flow's stop
```

Add a bullet: "The pass is the entrant. It answers and continues with its state. It throws only when recording failed,
so `seon.flow/var-process` runs the owner's stop request; rethrowing a recorded error would record it again."
reference-code.md Agent loop row: replace "an escaped exception goes to Flow's error channel and the loop resumes, so the
transform answers its own requests" with "upstream an escaped exception goes to Flow's error channel and the loop
resumes; Seon's `seon.flow/var-process` catches it first and records it, so a transform that records its own errors must
not rethrow them".

### 2. HIGH: AGENTS.md states target completion and context as current law, with an uninstalled function and a rejected design

"Derive work and completion" (AGENTS.md:307-316) says in the present tense: context IS a system message plus
task-generated context; a task names success tests before assignment; after a pass the tests run and all green completes
the task; `my.task/complete!` gives a task back. Installed: `(resolve 'my.task/complete!)` is nil on default;
`seon.task/assign!` checks no proofs (its `cond` has `:unknown-task` and `:unassigned` only); nothing runs proofs after a
pass; `seon.agent.loop/system-message` and `task-context` are unwired, and the owner rejected that context design on
2026-10-07. An agent reading the law will call `my.task/complete!` and get an unresolved symbol. Only the answered-t and
system-turn sentence is marked "Target". AGENTS.md's own rule: "Correct false current claims in the same commit."

Patch (replaces the bullet; folds in D2-D4):

> - **Derive work and completion.** Work is a listened wake the agent has not answered; a message subject is literal,
>   `:seon.message/from` marks inside wakes; never replay writes or effects. Context renders live through `seon.cache`
>   and is never stored. A task names its success tests (deftests carrying it in `:seon/tasks`) before it is assigned
>   or launched; a test already green before the work does not prove it. Target (agent-loop P5 and cut):
>   `seon.task/assign!` refuses a task with no proofs or whose proofs are all green; after a pass that changed code its
>   proofs reach, they run, and all green completes the task, the only success; `my.task/complete!`, with a required
>   reason, gives a broken or impossible task back to its assigner, who is notified; a model agent launches only on an
>   assigned task with proofs, and its own `:work` task ends by `my.turn/wait` or the call cap; the answered basis is
>   `:seon.agent/answered-t`, and system turns and context capture are gone. Archive agents; preserve task and agent
>   identity on loud, resumable budget exhaustion.

The "system message plus task context" clause is dropped until context-design closes (it is the excluded, rejected
design). Same dependency elsewhere, flag only: agent-loop header paragraph 2 ("builds a provider request from a SYSTEM
MESSAGE and a TASK-GENERATED context"), §6 P4(b), §8.7; reference-code.md Agent loop row's last sentence ("its producers
are `seon.agent.loop/system-message` and `task-context`"); clojure-testing §5 cites the protocol only via `my.test/check`
(fine).

### 3. HIGH: a recorded error whose task has no proofs resolves on any digest change (errors §6)

Resolution: "producer's current digest differs from `latest-digest` AND every success test naming its task has a green
run ... after `latest-at`". The task an opening creates (`adopt-tasks-tx`, one task per producer) has no deftest naming it
at creation, so "every" ranges over the empty set and is vacuously true: any edit to the producer resolves it, which is
exactly what the next sentence forbids ("A digest change alone never resolves a surviving producer"). T10 does not catch
it unless its fixture task has a proof. The owner rulings (success tests before assignment, D4) make proofless tasks
unassignable, so these tasks could also never be worked through the loop.

Patch, errors §6 Resolution second bullet: "... AND its task has at least one success test and every one has a green run
recorded on the task home after `latest-at`. A task with no proofs keeps the error open: adding the regression that names
the task is part of fixing it." Add to T10: "a task with no proofs stays open after a digest change". Open question for
the owner (Q1): is that the intended workflow (fixer writes the proof), or should an opening require root to name one?

### 4. MEDIUM: where recording happens at the boundary is unspecified (errors §3, §6)

§3's `caught` sketch builds the map and writes nothing; §6 says the recorder runs "by one `:db.fn/call`"; agent-loop's
comment says `caught` records; AGENTS.md says the entrant boundary "records it". Which errors are "Seon's own" (recorded)
versus agent mistakes (delivered only) is never defined: no predicate, no field. Patch, errors §3 after the sketch:
"`caught` is pure: it builds the map. Each entrant then calls the recorder (§6) on it when it is Seon's own error
(producer outside the agent's own namespaces: rule to be named [U: P4]) and assocs the receipt; an agent's own mistake is
delivered and not recorded." Q2 for the owner: the classification rule.

Cost note: with the recorder at the task home, every errored form of a branch agent is one home transaction before its
branch settlement (N errored forms, N home writes). Not wrong, but the spec should state it beside "under 100 ms".

### 5. MEDIUM: clojure-testing §2.12 states target completion as current

"the task cannot be assigned without them, and after a pass that changes code they reach, all green completes the task"
is false today (see 2). Patch: "the deftests naming a task are its success tests (`seon.task/task-edges` `:proofs`).
TARGET (agent-loop P5 and cut): the task cannot be assigned without them, nor when they are all green already, and after
a pass that changes code they reach, all green completes the task."

### 6. MEDIUM: the completion close value is not in the enum (agent-loop §4, P0/P5)

§4 writes `:seon.task/closed-by :completion`; installed `:seon.task/closed-by` is
`[:enum :seon.task/faults :seon.task/signal :seon.task/outcome]` (`seon.task.edn`). Missing step: P5 (or P0) adds the
value, spelled like its siblings, `:seon.task/completion`; the errors cut renames `:seon.task/faults` to the errors
value at the same time it renames `:seon.task/faults` the attribute.

### 7. LOW: `sci.eval/acquire-pass-context` is not installed (agent-loop §2, P2)

`(resolve 'seon.sci.eval/acquire-pass-context)` is nil on default. The sketch uses it as if it existed; P2 says "the
acquired context with its diagnostic" without naming it new. Patch: mark it "(new in P2: today's acquisition plus its
unbound-result diagnostic)" or name the installed acquisition it wraps.

### 8. LOW: stale "could not verify" and caveats now verified

- data-oriented §3.3 "Check the load order (`seon.test.runner` must be able to require `seon.error.refusal`)": it already
  does (`seon.test.runner` ns form, first require). Cut the sentence. The rewrite is safe: `event-signature` calls
  `throwable-signature` only `(when (instance? Throwable (:actual event)))`, so `cause-links`' `[:vector {:min 1}]`
  output holds. The final-revision notes' "could not verify" item is resolved.
- final-revision notes "That `my.test/check` with a test symbol runs exactly that test": `seon.test/select` comments "A
  test named as changed is itself a member" and `:named` with `:seon.test/changed` selects the tests reaching exactly
  those identities. Supported. clojure-testing §5 should show the form:
  `(my.test/check {:seon.test/changed ['ns/test-name]})`.
- clojure-testing §2.11 "(lane task-tags removes it)": already removed at HEAD (`819a8ed4b`; `git cat-file -e
  HEAD:test/my/examples_test.clj` fails). Patch: "removed in `819a8ed4b`".

### 9. LOW: clojure-testing examples 2 and 3 duplicate existing tests

Example 2 is `seon.reset-edges-test/analysis-is-a-required-positive-fact` minus one attribute; example 3 is the first two
assertions of `seon.sci.admit-test/an-absent-storage-bound-refuses-and-names-the-key-it-wanted`. Both are written as new
namespaces (`seon.analysis-fact-test`, `seon.invalid-call-test`); an agent copying them adds a second proof of the same
behavior, which §3 rule 2 says to delete. Patch: one line under the §4 heading: "Examples 2 and 3 show shape only; the
named source tests already prove these behaviors: do not add them."

### 10. LOW: counts and small accuracy

- data-oriented §10.1 "(45 characters, 169 uses)": `git grep -c polymorphic-boundary HEAD -- src resources` sums to 173
  lines. Cut the count (it drifts and teaches nothing).
- errors §1 "the formula is unchanged": true of `id/evaluation`, but `world-reference` reads the id under
  `:seon.turn/id` and `:seon.batch/ordinal` in the world; P2 already says it touches `world-reference`. Add "the world
  key changes in P2" to §1.
- `seon.error/message` (guide §9.1) is private (`defn-`); fine as an example, but the rule's subject is any docstring.

## Verified (supported) claims

errors: line counts (`error.clj` 2838, `refusal.clj` 787, `fault.clj` 453, `problems.clj` 657, `instrument.clj` 1326,
`seon.error.edn` 458); `chain` cycle-safe with suppressed edges, `:seon.error/suppressed-by`, `:seon.error/cause-of`;
`cause-links`, `root-frame`, `frame-function`, `producer`, `attributed`, `world-reference` (two-argument
`id/evaluation`), `references`, `calling-definition`, `var-digest`, `construction-refusal`, `checking-projection`
exist; installed `error` is `[alternative members]`, requires a qualified operation, stamps at/layer; `error?` tests
at/layer/operation; `my.fs` installed member `:my.fs/not-found` and `:my.fs/tool-error` an `[:or …]` ending in
`:seon.effect/boundary-error`; S1b sites (declared_identity_test, four in cluster/turn_test at 1468/1830/2529/2584,
turn_loop_test:328); `seon.error/signature` reads layer; `prepare`, `commit-call`, `recording`, `occurred-since`,
`resolution`, `schema-expectation`, `scalar-text`, `evidence-path`, `edit-distance`, `call-lines`, `producer-task`,
`seon.fault/print-unrecorded!`; `seon.task/home`, `home-connection`, `adopt-tasks-tx`, `task-resolution`,
`task-edges`, `seon.test/verified?`; Malli citations `core.cljc:203-207`, `:2233-2240`, `error.cljc:256-260`,
`:343-349`, `util.cljc:128`; `core_print.clj:484-501`; Datahike `transaction.cljc:27-32`, `:1172-1173`; every kept and
deleted test name and file in both specs exists at HEAD.

agent-loop: `turn.clj` 6037 lines; `outside-wake-t`, `step`, `settle-evaluated!`, `settle-batch!`,
`evaluate-sources`, `planned-sources`, `code-sources`, `record-attempt!`, `provider-targets`, `stage-reply!`,
`settlement-projection`, `receipt-terminal-attributes` (a private def), `evaluated-namespaces`,
`receipt-read-evidence-tx`, `evaluation-terminal-data`, `fold-namespace`, `previous-ending-namespace`, `recover-tx`,
`recover-call`, `turn.batch/revalidate`, `program-facts-call`, `measurement/significant-tx`, `blob/with-publication!`,
`install-evaluated-rows!`, `ai/model-observation-tx`, `ai/request-body` (system + user messages), `repl/entity-emission`,
`wake/agent-wake-datoms`, `turn-opening-attributes`, `inside-wake?`, `prompt/model-calibration`, `select`, `compose`,
`eval/of-agent`, `seon.health/derived`, `seon.db/pull-many`, `run-observation` resolving drive's `run-receipts`,
`turn-completion-backstop-ms`, `record-interruption!`, `:seon.db/transaction-outcome-unknown`,
`:seon.cluster.eval/author`, `:seon.eval/origin` a string; default dials: no-provider true, stop `["#:seon.repl"]`,
timeout 180000, max-episode-runs 100, prompt-token-budget 1000000; `ai/complete` never reads no-provider (only
`seon.turn` does); `:no-proofs` is a refusal value used by `propose-merge!`, not `assign!`; `my.turn/wait` and
`complete` exist; `seon.agent.loop` publics are `context-text`, `system-message`, `task-context`.

guides: §0 `var-entity` stores `inline?` as a boolean; worked decomposition matches `transact-initialization!`
(`refused!` throws, `entity-ready?` is `every?`); §2.1 `debug-turn-request` (seven keys; the alias list matches the ns
form); §2.5 `debug-diagnostic` seven arguments; §3.1/§12.1 `turn-function-result` (`refusal/diagnostic`,
`java.util.Date.`, two `str/replace`, `find-ns` then `io/resource`); §4.1 `interrupted-warning` (both keys registered,
`:seon.turn/missing-results` in seon.turn.edn); §4.2 45 pasted reasons; §6.2 `update-vals` keeps metadata
(`core.clj:8136-8150`); §8.2 eight `seon.db` delays (five `seon.error`, three `seon.call-preparation`); §8.3
`seon.test` resolves `seon.effect/*request-context*`; §9.4 `:metadata-after-test-name`, `seon.fn/task-links` (private),
`task-handle` of ns/name/kind; §12.1 `call-reach` snippet; testing §5 every `test_support` symbol named exists,
`refusal-data` reads at/layer, runner `assertionless-failure`/`duration-failures`, `seon.test/bounded-result`; testing
example 1 rows and `ba7816bf8f01`/`ba7816bf` probed true on default.

Still uncertain (not probed): errors §5 "207 µs per refusal" and "about 540 estimated tokens"; agent-loop §3 numbers
(excluded); the T-lists' feasibility is each slice's proof.

## Owner rulings relayed by root (D2-D5): exact patches

Not applied (files held by `review-final-codex`).

- **D2** (keep `my.task/complete!` for bailing, required reason): agent-loop §4 Bail, P5, §8.5 and T18 already say this;
  no change. AGENTS.md: in the patch of finding 2.
- **D3** (model agents launch only on assigned tasks with proofs; own `:work` tasks end by wait or the call cap):
  agent-loop §4, add after Admission: "**Launch.** A model agent is launched only on an assigned task with proofs.
  `my.task/own-task` (`:work`) stays for outside agents' tracking and never completes by tests: it ends when the agent
  waits (`my.turn/wait`) or reaches the call cap." Add to §8.5: "A model agent launches only on an assigned task with
  proofs; an own `:work` task ends by wait or the cap." AGENTS.md: in the patch of finding 2.
- **D4** (`assign!` refuses a task whose proofs are already green): agent-loop §4 Admission, append: "It also refuses a
  task whose proofs are all green on the task home (`seon.test/verified?` each): close it, or fix the test so it fails
  before the work (a new refusal value beside `:no-proofs` in `seon.task.edn`'s refusal enum, e.g. `:already-proven`)."
  Evidence bullet: replace "open tasks whose proofs are all green are a finding" with "such a task is now refused at
  assignment; already-open ones are the finding of task `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`".
  T17: "assigning a task with no proofs refuses `:no-proofs`; one whose proofs are all green refuses `:already-proven`."
  P5: "Admission `:no-proofs` and `:already-proven` in `assign!`". §8.5: "a task names its success tests, failing,
  before assignment". clojure-testing §2.12: in the patch of finding 5.
- **D5** (outside MCP runs excluded from model history): agent-loop §3 History and T21 already say this; §3 is excluded
  from this review and stays as written. No change.

D1, D6, D7 stay open.

## Tasks

- Created on default: `a60b343f0098` (`seon.cluster.agent`, "interrupted? re-walks the cause chain"):
  `seon.cluster.agent/interrupted?` keeps its own `.getCause` seen-set loop, a second copy of
  `seon.error.refusal/cause-links`. Runtime task at the task home; root carries it to a task file.
- Spec defects 1-7 are recorded here, in the spec owner's review notes; they are design text, not installed code.

## Questions for the owner

- **Q1** (finding 3): a recorded error's task has no proofs when opened. Keep it open until the fixer adds a proof
  (recommended), or does root name a proof at opening?
- **Q2** (finding 4): what makes an error "Seon's own" (recorded) versus an agent's mistake (delivered only)? Proposed:
  its producer is a first-party definition not in the failing agent's own namespaces.
- **Q3** (finding 2): AGENTS.md's context sentence is removed until context-design closes; confirm.

## Design check

The review's design held: the specs' installed-code citations are almost all accurate (the list above), and the defects
are in the joins between parts: the pass and `seon.flow` both being boundaries (1), resolution over an empty proof set
(3), and target behavior written as current law (2, 5). Awkward: the reviewed files were claimed by the parallel
reviewer, so this lane edits nothing and every fix is a patch; whoever applies them should apply finding 1 and 2 first.
No restart or reset needed.

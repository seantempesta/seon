---
type: design-review
status: revise-before-implementation
date: 2026-10-07
lane: s0-review
design-lane: s0-probe
---

# S0 probe review

**Recommendation: revise before implementation.** Keep the real entrances, the HTTP fixture, and a small
test-path measurement function. The current proposal cannot yet establish the approved S0 workload or its
budgets. In particular, it mistakes store-head writes for transactions and misses the test runner's memory
overlays. These are measurement correctness defects, not requests for another execution subsystem.

Review basis: design commit `7bbd0079f`, approved `docs/seon/specs/agent-loop.md` §§3–5, lane rules (including
One happy path), and the design's four evidence notes. Source references below are at `7bbd0079f` unless
qualified. Default is running committed archive `18a68b248`, not HEAD. The archive used for static verification
is `fb9172824`; the intervening source change is line-column's allocation slice. No other review was used.

## Findings, ranked

### P1 — The storage measurement does not cover the test entrant, and a branch-head write is not a transaction

Design §§2–3 says one physical-store hook covers test members and uses branch-key writes as durable commits.
`src/seon/test.clj:1834–1903` (`clean-request-handle`) prepares a durable checkpoint, then calls
`store/open-overlay!`; the request and every member write to a private **memory frontend**. Only recorded results
reach the recording connection. `src/seon/cluster/store.clj:644` owns this boundary. At pinned Datahike
`d9121bbbad`, `store.cljc:123–151` supplies `:frontend-only`; `physical-store-key` at :70 preserves the overlay
configuration. `connections.cljc:62–66` shares hooks only for equal physical-store keys. Default's hook therefore
does not become the overlay's hook. An overlay head commit is also not disk durability.

Independently, `datahike/versioning.cljc:270` writes a new branch head by `k/assoc` **without a transaction**,
copying the selected commit. A cold checkpoint/request/member creation can increase the proposed durable
counter without entering `seon.db/transact-call`. The proposed `attempted >= durable` assertion is therefore
not an invariant of these counters. Pin and checked-out fork `e0aaa450` both contain this behavior.

The hook itself is useful. Its event already carries the value and, for `multi-assoc`, the ordered `:kvs`
(`konserve/core.cljc:30–51,435–507`, pin `8700ca7a95`). Reducing every event to `[op key]` discards the commit
identity and entirely misses a `multi-assoc` event's keys. The design's :749 writing citation is database
creation (`:db`, then `:branches`), not an ordinary commit; ordinary commit publication is :587 at the pin.

**Smallest correction:** keep separate counts for submitted transaction attempts, committed reports, branch
publication, and durable backing-store writes. Use the existing Datahike reports/listeners for transaction
evidence on held connections, and the hook for backing writes. If the existing request owner does not expose
member observations to a test-only caller, mark that member count unavailable until its owner supplies it;
do not silently claim coverage or invent another runner. Retain only event identities/metadata needed for
counting, not whole database values. Exercise branch creation and a real one-test request as distinct rows.
An in-memory probe test must allow zero disk bytes; test file-backed byte accounting on the declared disposable
file-store integration surface. Do not change test-world isolation to make a byte assertion pass.

### P1 — The default history workload loses the very baseline S0 is intended to measure

Design §5 defaults to N forms in one MCP SCI submission. This is N evaluation rows but one run. Its own
`pass-alloc` evidence reports approximately 0.2 MB additional work per prior **run**, with 67–75 MB at run 1 and
107–121 MB after 210 runs. `turn/spent-runs`, `continuing-reply?`, `previous-ending-namespace`, and MCP
`agent/run-observation` are among the consumers. Replacing that workload with one run is not equivalent.

There is a second, future incompatibility: approved spec §4.4 shows only `:opening`, `:input`, and `:model`
exchanges to the model; MCP SCI/JVM are `:outside` and deliberately excluded. After S3/S4, filling history by
MCP submissions gives the model **zero eligible synthetic exchanges**, even if the same agent has 200 outside
evaluations. One agent shared by all four entrants does not imply the same visible history. Earlier entrant
passes and definitions also change the next entrant's acquisition state and actual history size.

**Smallest correction:** make the canonical acceptance fixture real prior passes with the same history
semantics the measured entrant consumes. Record both prior pass count and eligible exchange count. Keep the
one-submission/many-forms case only as an explicitly separate batch-scaling diagnostic, or delete that option.
Prepare at exact sizes 1/10/200 outside measurement windows, count setup, and use a fixed scenario order or
separate agents per comparable scenario. For model history, use fixture replies through the existing model
entrance; do not insert hand-built history entities or include outside rows against the spec. This review made
no provider calls. Root must deliberately authorize and run the fixture matrix when implemented.

### P1 — The record cannot yet answer the budget table

Spec §3 expressly excludes the provider and agent-authored work, includes runner setup/selection/recording/
cleanup, and requires ordinary, definition, after-definition, opening, input, cold acquisition, and warm
execution. Design §3 proposes only ordinary/defn/after and one check; §5 relabels the first repetition of each
kind cold. But `history!` has already hosted and acquired that agent. The first defn kind is not a cold agent;
the first pass after boot is not a cold acquisition of the prepared 1/10/200-exchange transcript.

`wall - fixture(received→responded)` does not remove an agent/test body, nor fixture allocation. The outer
`my.test/check` form's :ms includes the runner, so subtracting it would remove the very work §3 budgets. For
model passes the window also includes the inbound wake commit, system refresh and tail/idle derivation; those
are useful cycle totals but are not interchangeable with a single-pass budget. A raw HTTP JSON request includes
model/settings/envelope text: it is not the text shown to the model. `seon.ai.tokens/estimate` is the right
estimator (`tokens.cljc:145`), applied to the message content with a recorded calibration.

**Smallest correction:** one explicit scenario table with availability, start/end event, included work, and
history precondition for each spec row. Preserve end-to-end totals; separately identify wake/setup, provider
service time, measured agent/test-body work, and machinery where evidence permits. Keep unknown allocation
unknown: line-column intentionally makes virtual-thread per-form allocation absent (`fb9172824`). Until an
exact body subtraction exists, label the full-pass allocation as an upper bound, not Seon-only compliance.
Opening/input and proc-transcript acquisition are TARGET until S4; show unavailable rows now and add their
real entrances when installed. S8's two encodings likewise remain a named later row, not speculative machinery.

### P1 — One fixture reply does not establish one completed model pass

`turn/step` publishes its run report, then self-wakes (`turn.clj:5980–6004`); approved spec §4.5 explicitly
continues after a successful evaluated form. A queue of reply strings, a settlement listener and “then idle”
do not specify how the next provider request is prevented or assigned to the next measurement. A `defn` can
settle before installation/tail work finishes. A waiter installed after the wake can miss the event; an old
idle report can satisfy an uncorrelated read. Flow's report channel is a sliding observation buffer of 100
(`core.async/.../flow/impl.clj:101`), not a durable broadcast completion log.

The design also omits a concrete join/deadline for its fresh MCP platform thread, and what happens if the
32-second MCP bridge times out while work is still active. Bridge limits are real (`script/seon/dev/mcp.clj:163–186`),
but “one slice per call” is not a bound on history setup or teardown.

**Smallest correction:** spell out an outside wake with the installed one-call allowance and the allowance's
terminal outcome; count the cap behavior if it is part of today's baseline. Correlate the writer's result and
proc report to the triggered run/wake, register observation before triggering, then await actual pass exit.
Use `support/await-event!` and existing agent disarm/join owners for bounded cleanup. Do not add a polling loop,
sleep tail, second scheduler, or fake wait form to the ordinary workload. Verify the fixture saw exactly the
expected requests and that no pass is still active before closing a window. A timeout must preserve ownership
and mark the record incomplete, never remove hooks/retire a still-running branch and report success.

### P2 — End-of-window file lengths are not bytes written per event or exact branch attribution

Konserve reports successful API operations; it does not report encoded physical byte counts. The filename seam
`konserve.impl.defaults/key->store-key:47` is real for that file backend. However, a mutable branch head can be
rewritten several times during one model window. Summing final lengths once undercounts writes; summing the
final length once per repeated event assigns the last size to earlier versions. A concurrent writer can change
the file before stat; memory overlays have no such files. Shared index/blob
keys carry no branch attribution merely because the same hook also observed a branch head.

**Smallest correction:** name the cheap measurement honestly: final apparent size of distinct touched files,
with backend/window scope and unavailable sizes explicit. Count immutable commit records by identity and
separate mutable-head estimates if useful. If root requires exact write traffic, obtain actual encoded-size
evidence from the storage producer after profiling, rather than calling final stat exact. Default is a shared
store window; use a quiet disposable root for comparisons. Neither a hook nor thread prefixes isolate an
agent's costs on default. Preserve a raw window result when attribution is unknown.

### P2 — The window excludes some instrumentation but cannot claim exact exclusion

The design correctly reads existing counters after `cells` validation, and snapshots old counters before the
second `cells` call. Live `cells` on default returned 2,591 cells in 7.027 ms / 2,774,024 bytes (probe below),
so keeping it outside the measured work is sensible. `begin`/`explain` are not interchangeable totals:
`profile.clj:552–590` truncates by inclusive whole-millisecond time and limit. Identity matching, not symbol or
LongAdder value equality, is already demonstrated there.

But cells are weakly held (`profile.clj:120–150`). A cell installed and retired during a test window can disappear
before the end sweep. Holding only the start cells plus currently live end cells is not a complete checked-call
ledger. Likewise a newly created platform thread has no start reading, an exited thread has no end reading, and
the total/per-thread samples are not atomic. The “exited remainder” also includes sampling skew/unattributed
work. Whole-JVM allocation includes the HTTP fixture, hook callbacks, probe thread and concurrent tasks.

**Smallest correction:** retain identity-based reads and call this live-cell growth, with its coverage limit;
do not promise an exact total over ephemeral test worlds. Report per-thread deltas only where readings are
valid and call the remainder unattributed. Record a no-op instrumentation window and fixture cost, without
blindly subtracting noisy totals. A stronger scoped measurement belongs in the existing profile owner if root
requires it; no second registry, new global callback roster, or retained wrapper cache in the probe.

### P2 — The proposed test can pass while measuring the wrong operation

Positive tokens/bytes/calls and at least one commit can all come from hosting, an error reply, the wake, or the
checkpoint. They do not prove that the ordinary form returned 3, the definition was installed, the next pass
used it, or the test member actually ran successfully. A cached green `my.test/check` can exercise selection and
reuse without executing one member. The design names no exact small existing test or cold/reuse policy.

**Smallest correction:** strengthen the one table-driven entrance test with semantic data: expected value,
installed definition identity and callable result, successful **executed** named test member versus a separately
labelled reused result, exact fixture request count, correlated terminal record, and no unexpected diagnostics.
Add focused accounting cases for branch creation, overlays, `multi-assoc`, and instrument overhead where those
are Seon measurement behavior. Keep timing thresholds in profile evidence; deterministic transaction/work
counts are legitimate assertions (testing skill §1.7). Do not hide everything in one longer test declaration.

## What exists: confirmations and corrections

| Design claim | Review |
|---|---|
| MCP SCI/JVM go through `seon.mcp/call` | Correct: `mcp.clj:228` SCI submits to the ordinary agent proc; :254 JVM supplies `jvm-request`. Installed symbols resolve. Their current settlement owners differ (`turn/settle-batch!`, `turn.batch/settle:682`); test results have `seon.test.runner/record-tx`. The probe must observe these existing differences, not manufacture a common runner. |
| Fresh platform thread avoids inherited custody | Consistent with `db/transact!:5988–6003` and r-record evidence. Join it and keep errors as data. A fresh thread proves neither cold SCI acquisition nor an independent store. |
| `transact-call` is the only native `d/transact!`/`d/merge-db!` invocation in src | Confirmed at `db.clj:5721`. Its entry count means attempts reaching that helper; public preflight failures at :5992–6003 never enter it. Branch publication uses another legitimate Datahike API. Name the counter accordingly. |
| Provider endpoint overlay works for an unknown model | Correct source seam: `ai/resolved-target:356` substitutes an endpoint only when descriptor data supplies a provider; `configured-targets/targets:395–420` carries the selected settings. `no-provider` is boolean; `no-auth` is the true-only dial. Historical fixture success is evidence, not rerun in this review. |
| Shared fixtures are duplicates | Yes: `agent_test.clj:331–372` and `turn_test.clj:365–401` each own a local OpenAI-compatible server. The latter additionally applies cluster config. Preserve those separate configuration semantics while sharing the HTTP leaf. `with-running-agent` at `mcp_entrance_test.clj:26–65` is the existing world construction; five direct call sites, not the claimed seven, in this revision. |
| “Two files, both on test path” | Two new files, actually five or six touched files after fixture extraction. Enumerate that scope consistently. A test-path namespace being on the classpath does not make `seon.pass-probe/run` installed; it does not resolve on default. Load only the committed artifact after root installs it. |
| Costs are verified | Historical numbers have specimens, but the proposed 200-form build's “about a second,” full matrix 1–3 minutes / 30–50 MB, and 32-second slice fit are estimates, not measured results. The measured quadratic fold specimen had tiny forms; it does not prove the 2 KB shown-value setup cost. |
| Local measurement schema needs no schema EDN | Reasonable for unpersisted test-only data, but a normal local `def` naming a map schema is not automatically a registered Malli name accepted by every compiled contract. Spell the actual reusable contract mechanism and check it using `m/function-schema` (`malli/core.cljc:3130`) against the packaged registry. Do not add stored measurement schemas just for a report. |

No dependency supplies the complete S0 experiment, nor should one: the useful library seams are HotSpot counters,
Konserve write events, Datahike reports and immutable commit identities, Flow reports/exit, and Malli compilation.
Other servers exist in `ai_test.clj:908,968`: truncated-response and independently gated custody fixtures.
Those test different HTTP behaviors and are not evidence for merging every server into S0’s successful-reply
fixture. The existing Seon fixture, profile cells, estimator, writer, and value printer already own the rest. Returning
measurement maps and letting the existing printer show them keeps one path. Writing an independent EDN serializer,
reply parser, phase dispatcher, history recorder, or cache would violate that path. JSON transport decoding
and the existing test-support reply decoder are sufficient; they do not justify a new reader.

## Recommendations for the design's §7 questions

1. **Exclude the profile mark in S0; fix its producer separately.** Read the held counters as proposed, with
   the coverage limits above. Do not expand S0 into profiling architecture. If root schedules the small fix,
   fix `cells`' repeated validation and its contract, not merely inline its roster walk into `begin`: `cells`
   is shared by other consumers. Existing tasks are `seon.profile/reading-the-profile-validates-every-cell` and
   `seon.profile/begin-validates-every-cell-with-armed-predicates`. No new S0 measurement cache.
2. **Move the minimal shared world and HTTP leaf into `seon.test-support`, converting all callers.** This is
   justified reuse, and clearer than making a reusable probe depend on a private helper in another test ns.
   Keep turn-test's configuration wrapper; remove its duplicated server body too if this is the same fixture
   behavior. Preserve ledger, streaming/nonstreaming and error handling. Do not rename two copies and claim
   one fixture. If extraction cannot stay mechanical, defer the probe until root assigns the shared owner;
   do not add a third copy. Claim the full five/six-file scope before implementation.
3. **Use MCP SCI `(my.test/check …)` for the test entrant.** That is the agent's entrance; direct `seon.test/run`
   would skip part of the requested workload. Report outer pass, request orchestration, actual member, and
   durable recording distinctly using existing evidence. Name one bounded test and state how cold execution,
   warm execution and proof reuse are distinguished. The outer check form's :ms is useful but is not the
   test-body duration and cannot be subtracted as the body.

Owner decisions still needed: confirm the canonical history/origin workload; accept clearly named physical-byte
estimates or require storage-owner exact accounting; confirm an unavailable baseline row is preferable to a
fictional cold/opening/input row. Recommendation for each is respectively real eligible prior passes, honest
file-size evidence first, and explicit unavailable rows until the owning slice is installed.

## Cross-design reconciliation

- **loop-pass:** there is no current design-notes.md in its directory. Its preserved
  `stopped-wip/loop-pass.patch` is historical uncommitted design evidence, not an installed API or authority.
  Approved §5 now sequences origin facts S3, opening/input/transcript S4, shared settlement S5, cursor and loop
  cut S6. S0 must survive that order via stable entrances; do not depend on a speculative
  `seon.agent.loop/opening-sources` or use the stopped patch's provider sender seam.
- **reader-prose:** likewise only a stopped patch here. It includes Parinfer/trials, broader fence syntax,
  and prompt imitation choices superseded by approved §4.3/S2b-1/S6. Keep the ordinary fixture valid under
  both installed and approved grammar, with fenced model forms; separately retain reader behavior proofs.
  S0 must not pre-segment/reparse a reply to predict evaluation count.
- **repl-lookup:** opening contents change when task `doc`, data-returning `source`, and help land. S0 should
  measure the forms supplied by the opening owner, not reconstruct its docs or query task/proof data itself.
  Its old delete-`my.program/declaration` proposal is already corrected by spec §4.7. Compare identical
  installed lookup revisions when attributing S4's opening cost.
- **walk-delete:** its design still leaves capture-before-call undecided, permits a replacement captured run
  attribute, and makes `generate-turn` temporarily close empty. Approved §4.4/§5/§7 instead removes capture
  and terminates old generated rows at S4's reset. Its token-calibration deletion must leave the estimator S0
  uses or provide the same owner explicitly. S0 should keep request-content observation at the fixture edge;
  it must not resurrect the old capture/walk to measure tokens. Direct file collision: fixture extraction
  touches `cluster/turn_test.clj` and `mcp_entrance_test.clj`, both named by that design's conversions.
- **line-column / test-cost2 / Datahike:** `fb9172824` changes allocation availability and test-body thread
  choice; request overlays and proof reuse already exist. The checked-out Datahike `e0aaa450` is not the
  pinned/default `d9121bbbad`. Record Seon archive SHA, actual dependency SHAs, JVM/JDK, backend and scenario
  revision in results. HEAD alone cannot identify which code generated a number.

## Live evidence and health

No test run, model/provider request, restart, reset, load-file, intern, or alter-var-root was performed.
Read-only JVM probes used agent `s0-review`, cluster default and explicit default connections. SCI used
`scratch.s0-review`. Successful probes returned no errors/diagnostics/findings. One malformed host probe
(missing the closing binding-vector bracket before the result map) returned `isError true`, message
"The run was evaluated but not recorded," expected `:seon.batch/applied`, offending
`{:seon.batch/applied true :seon.batch/tx-count 0}`. Correcting my bracket returned the expected data;
the authoring mistake is not a new task.
Missing :ms/:calls fields in a returned envelope are unavailable, not zero and not an end-to-end timing.

- Initial operator/MCP health: pid 23951, source archive `18a68b248`, ready 45,496 ms, faulted; 1 open fault,
  7 errored receipts, 4 over-bound warnings, 3 unowned namespaces. Heap-live 1,032,089,336 bytes.
  Named profile maxima: analyzed-files 3,876 ms, adopt-entities! 4,080 ms, publish! 3,849 ms, index! 3,496 ms.
  This is observed existing health, not a claim this review caused or repaired it. Existing links:
  `seon.turn.batch/an-entrant-inside-another-custody-cannot-record`,
  `seon.batch/each-fold-step-validates-every-entry-so-far`, publication task `9665c2ed25f0`, adoption task
  `5dbb7de36bdb`. Operator also returns the absent-maintenance-receipt refusal; no collection was attempted.
- Installed seams probe (result `ec578cea4bb2b`, envelope 1 ms): Java 26.0.1, platform thread; `cells`,
  `transact-call`, Konserve add-write-hook!, mcp/call, and tokens/estimate resolve; pass-probe/run does not.
- SCI `(+ 1 2)` in scratch returned 3 (`eb12bf7884968`, envelope 0 ms). This verifies the live entrance only;
  its form time is not an S0 pass measurement.
- Installed storage-key check (`ebf89d0f3bb77`) returned file backend, unequal physical-store keys
  for that backend and an overlay configuration, and an installed branch-publication Var. This was a pure
  comparison; it created no overlay and ran no test member.
- Profile read (`e7f30d9375ff4`): 2,591 cells, a host transact-call cell, 7.026958 ms, 2,774,024 allocated bytes.

Exact profile form:

```clojure
(let [c (seon.cluster.boot/connection "default")
      database (seon.db/db c)
      b (java.lang.management.ManagementFactory/getThreadMXBean)
      a (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean b)
      t (System/nanoTime)
      cells (seon.profile/cells)]
  {:basis (seon.db/basis-t database) :cells (count cells)
   :ms (/ (- (System/nanoTime) t) 1e6)
   :allocated (- (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean b) a)
   :transaction-cells (mapv #(select-keys % [:seon.profile/sym :seon.profile/scope])
                           (filter #(= 'seon.db/transact-call (:seon.profile/sym %)) cells))})
```

## Verification, tasks, and landing

Full `src script test` lint with `--cache false --parallel` ran from a `git archive fb9172824` snapshot:
**0 errors, 1,483 warnings, 6,972 ms** (clj-kondo exit 2 for warnings). This review adds no lint inputs, so its candidate code is exactly that
archive's. Warnings are not called zero findings. Foreign working edits were excluded: initially batch/test/
turn-batch and tests, later mcp/turn; Datahike's unbumped checkout and http-kit's untracked content likewise
were not substituted for pinned evidence. No foreign file was repaired.

The installed contracts checker was initially unresolved. Its resource was verified to be the committed
`data/source/18a68b248.../test/seon/contracts_compile_test.clj`, then required normally, without reload.
For this documentation-only slice there are **zero changed contract paths**. This exact check against the
archived candidate returned `{:findings [], :changed-contract-paths [], :ms 48.3185, :allocated 47377664}`
(`result/e3c9c2737d603`):

```clojure
(do
  (require 'seon.contracts-compile-test)
  (let [c (seon.cluster.boot/connection "default")
        b (java.lang.management.ManagementFactory/getThreadMXBean)
        a (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean b)
        t (System/nanoTime)
        findings (seon.contracts-compile-test/check
                   {:seon.contracts-compile-test/root
                    "/Users/sean/src/seon/scratchpad/s0-review/candidate"
                    :seon.contracts-compile-test/paths []
                    :seon.contracts-compile-test/candidate
                    (seon.schema/projection-from-database (seon.db/db c))})]
    {:findings findings :changed-contract-paths []
     :ms (/ (- (System/nanoTime) t) 1e6)
     :allocated (- (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean b) a)}))
```

This is not a full source-contract census, publication/adoption proof, or acceptance run. Its 47 MB exceeds the
allocation rule: filed `seon.contracts-compile-test/empty-contract-selection-still-allocates-tens-of-megabytes`
(owner-minted id `3f1b34cb36fc`, created on default, result `e21129edcb8d3`), after searching existing tasks and
issues. Do not attribute it to the corpus edamame task: no selected source was parsed. Also filed
`seon.turn/repl-and-flow-skills-describe-retired-loop-contracts` (`53e2a265edb0`, result `ee2ec1624e700`): the
REPL skill says contractless defns are refused and the Flow skill says three turn writes, contradicting current
source/spec. Both are outside the notes-only ownership; their evidence is retained here rather than editing skills.

**Root's focused tests after S0 implementation**, not run by this review:

- Proposed `seon.pass-probe-test/a-pass-of-each-entrant-is-measured`, strengthened as above.
- `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads` (convert its model-history
  assumption with S3/S4), `a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`, and
  `an-agent-runs-its-own-test-on-its-own-branch`.
- `seon.cluster.turn-test/a-turn-through-a-local-provider-settles-and-exits-within-its-bound` and
  `a-batched-turn-commits-only-queryable-definition-facts` for the moved provider fixture.
- `seon.cluster.agent-test/prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound` and
  `wait-closes-in-terminal-tx-test` for its other fixture consumers.
- Existing `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings` for the newly filed
  checker finding, when its owner changes it. This review itself requires no behavior tests.

**Design check:** the real-entrance approach holds. “All counters already exist” does not: attribution,
durability and history semantics were inferred from incomplete seams. Shrink the proposal by deleting the
one-pass history shortcut from acceptance, misleading cold labels, exactness claims and speculative target
helpers; keep one measurement wrapper over the real entrances. No src/test changes: net source/test lines 0.
Before/after pass numbers, armed calls, store bytes and retained-memory deltas are unavailable because this is
a design review, not an implementation or test run. Default needs no restart or reset for these notes.

Cleanup exposed the existing retirement defect: SCI
`(my.agent/retire-branch! {:seon.store/branch (get-in (seon.db/db) [:config :branch])})`
on s0-review returned `{:seon.error/message "run transition refused: admission-closed"}` with `isError true`
and no timing. Existing task: `my.agent/a-refused-retire-leaves-the-agent-admission-closed` (7116117e6aaf).
Root then retired only this lane’s branch via the ordinary SCI tool, returning
`{:seon.store/branch :agent-45fd13650098, :my.agent/retired? true}`
(`ef431872411e9`, 66 ms, 864 calls). No review graph, server, listener, JVM, or shell remains.
The temporary lint archive was removed after verification; the lint evidence and uncommitted review draft
remain in this lane’s scratch directory pending claim reconciliation.

Publication status: the requested destination is covered by s0-probe's directory-wide claim. The initial
`bin/lane claim s0-review .../s0-probe/review-s0-review-notes.md` refused. Root/owner claim reconciliation is
required before placing and committing these notes there; no ownership file has been changed without approval.

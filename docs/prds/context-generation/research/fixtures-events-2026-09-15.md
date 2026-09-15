---
type: research
status: active
tags: [research, test, flow, database]
---

# Fixtures and events — 2026-09-15

## Class continuation checkpoint — `e4f8bbe07`

The owning construction now returns Flow's existing started value on new
agent handles and publishes completed idle passes, so consumers can await
observable events under the declared backstop. Cancellation closes the
existing completion-observer channel. Canonical cluster setup precedes handle
construction; the routing property requires nonempty production subjects and
retains `[:message]` as its empty-subject counterexample. The implementation
changes 10 paths, adding 299 lines and deleting 247.

**Gate ownership changed at 21:05Z.** The owner's orchestrator-only rule
(`fd98bc5a5`) supersedes the earlier lane gate workflow. No test invocation
was launched after that instruction. The interrupted final-byte gate
`run.eLfej9` has no claimed result; its old shell PID 17565 no longer exists.
The last completed focused gate passed 22 tests / 146 assertions, before the
final additive-schema and namespaced-verdict corrections. Final combined and
platform verification belongs to the orchestrator. The request is
`tmp/orchestrator/gate-requests/fixtures-events.txt`:

```text
seon.cluster.agent-test
seon.turn-backstop-test
seon.test-support-test
seon.render.web-test
platform
```

Resumed verification: default remains PID 69622. In 2,486 ms, a read-only JVM
probe verified the installed optional started declaration, the loaded
pass-report contract, and successful reading of root's existing armed handle.
A disposable observer probe completed in 6 ms: cancellation published channel
closure, cleared observer state, and emitted no fault. Its executor was
closed. The retained probe file includes that operation. Focused clj-kondo
reported zero errors and 43 warnings across eight Clojure files; warnings
remain in pre-existing unused/shadowed declarations. `git diff --check` passed.

P2, P3, and N2 remain open. This checkpoint supersedes the obsolete private
render-budget member with the current value-projection owner and names its
acquisition-latency residual. The graph member's remaining render-report
work, broader fixture inventory, and transcript property are explicitly
unclosed below. `src/seon/effect.clj` and `src/seon/test/runner.clj` contain
foreign projection-carriage edits and are excluded from this commit.

Cleanup verified no live runner held this lane's retained `run.SoY61d` or
interrupted `run.eLfej9` roots before removing them. The lane's three temporary
thread dumps were removed; no owned background shell remains. The orchestrator
gate request is retained.

### Implementation and probe chronology

Protected-owner follow-up, **not applied or verified**: extract the current
ping projection in `src/seon/render/web.clj` and publish it at completed-pass
output. The web owner must reconcile this diff with its concurrent edits;
then the fixture can consume the report channel instead of `await-ping!`.

```diff
+(defn- pass-report [state]
+  {::passes (::passes state 0)
+   ::watched-agents (::watched state 0)
+   ::tap-count (transduce (map long) + 0
+                         (vals @(:seon.render.web/registration state)))
+   ::streaming-agents (count (::streams state))})
@@ render-step zero-argument description
-    :ping-map-fn (fn [state] ...existing projection...)
+    :ping-map-fn pass-report
@@ render-step completed-pass output
-       [(assoc state ::last-pass-nanos (System/nanoTime))
-        (when (seq published) {::pages [published]})]
+       [(assoc state ::last-pass-nanos (System/nanoTime))
+        (cond-> {::flow/report [(pass-report state)]}
+          (seq published) (assoc ::pages [published]))]
```

The owner authorized the remaining P2/P3/N2 harness members without another
design stop. At inherited HEAD `8574992e4`, default PID 69622 answered the
explicit-connection MCP probe in 2 ms. Protected concurrent edits now also
include `test/seon/sci/eval_test.clj`; the first P2 member is deferred at that
exact boundary, without altering its source.

The graph member still had seven calls to a 25 ms polling helper. The current
slice retains Flow's existing started value on the armed handle, declares it
on that producer's schema, and publishes idle passes through Flow's existing
report channel. The tests await reports under the shared event bound; the
poll helper and count-based completion inference are deleted. Dependency:
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`
owns the sliding report channel (101), output publication (218), and stop
transition (207). First-party ownership stays at `agent/arm!` and `turn/step`.

First armed fast invocation `run.HwaYdY` reached the parallel property but
stalled in `agent/await-turn-completion!` at line 777. A virtual-thread dump
showed main waiting on `[turn-stopped failure-channel]` with no active turn
or backstop task. The cancellation path cleared the observer state without
closing its channel; an already-waiting disarm could retain the cancelled
observer forever. Cancellation now closes that same channel; disarm then
re-derives its stop wait under the existing bound. The new canonical
`cancelled-completion-observer-releases-every-existing-waiter` regression
observes that cancellation directly. The stalled owned JVM was terminated;
no other process was operated. Its shutdown reported an active fixture
connection because termination interrupted fixture teardown.

The current candidate also corrects provider-turn counting to require the
stored `:seon.turn/attempts`, observes `closed-tx` as a ref, uses the canonical
fixture's installed boot process identity, and routes both agent properties
through `assert-check!`. A live read-only query found 81 attempt-bearing
turns on default in 6 ms; system turns cannot be counted as provider calls.
These edits are not yet a passing-gate or member-closure claim.

The next fast runs measured 20 tests / 108 assertions (6 failures, 4 errors)
and 23 tests / 144 assertions (2 failures, 2 errors). The latter verified
both completion-observer regressions, provider-turn counting, park/wake,
hot reload, terminal wait, and the producer-output regression. Its routing
failure exposed fixture ordering: the handle was constructed before its
cluster configuration existed. The fixture now calls `seed-cluster!` before
constructing the handle. A subsequent held-provider routing test passed in
9.865 seconds. The install-gate expectation is corrected to the durable typed
phase diagnostic already ruled in
`archive/started-receipt-can-outlive-a-lost-settlement-fault.md`; no production
fault translation was changed. The single-provider bound fixture now
explicitly disables the inherited backup provider.

Read-only default probes subsequently verified 81 attempt-bearing turns and
the installed armed-handle declaration in 4,016 ms, then the installed idle
report declaration in 3,196 ms. These observe in-place development adoption
of schema facts; they do not claim an old armed handle was reconstructed.
The retained probe file includes both queries. The routing property now
generates an initial production agent creation and rejects the retained
empty-subject counterexample `[:message]`.

The four-namespace fast run `run.sNAHmh` completed 90 tests / 610 assertions:
5 failures and 1 error, all in the web namespace. Agent, backstop, and shared
support namespaces passed. The remaining web observations were an unrelated
transaction incorrectly expected to cause a render wake, a sampled pass-count
window that included unrelated work, and four expectations for retired
elision prose. The candidate now uses the existing render settlement reply,
asserts nonempty equivalent delivered packages, and reads the elision data's
unit, omitted count, and requery form. The held-derivation regression passed
in 9,024 ms without a tuned delay.

The shared `await-event!` seam now accepts watched references: register before
derive, deliver the accepted value, and remove the watch on every exit. The
web registration loops use this existing helper. Future failures expose the
original publisher exception; timeout cancels the awaited future before
reporting the missing event. No separate polling implementation was added.

Exact remaining P2 boundary: `src/seon/render/web.clj` is concurrently edited.
Its `render-step` exposes pass/stream counts only through `:ping-map-fn`; it
does not publish completed-pass reports when no page package changes. The
remaining `await-ping!` consumers require that producer to emit its existing
state projection on Flow's report channel. Repeated ping cannot be replaced
honestly with an unrelated settlement event for stream/runtime input ports.
This lane does not edit that protected owner or claim this member closed.
The episode-driver bound similarly requires a declared config default in
the concurrently edited config owner; its duplicated `240000` fallback is
still an explicit residual.

The first isolated gate, `run.SoY61d`, ran 91 tests / 630 assertions and
reported two assertions in one provider-backstop test. All web, shared-support,
and completion-observer tests passed. Confirmation showed that the test took
the initial idle pass's timeout fault rather than the later provider turn's
fault. It now awaits initial idle readiness and selects the exact turn ID.
The focused isolated gate `run.0btSko` then passed 22 tests / 146 assertions;
its routing property completed in 147,053 ms. The producer-output test now
uses `agent/creation-tx`, not a hand-rostered agent map.

The live loaded `turn/step` contract includes the new pass-report schema
(4 ms). A subsequent read found root and Juniper still carry their original
armed handles without the new started value (6 ms). The declaration therefore
keeps this additive field optional for existing handles during hot adoption;
new construction supplies it, and the canonical regression requires it.
No existing default graph was stopped, rebuilt, or mutated by the probes.
This is a live-adoption boundary, not a claim that existing handles were
retroactively reconstructed. Final-byte verification follows the namespaced
test verdict key and this additive declaration correction.

## Authorized continuation: runner fixes first

Implementation commit: `bc3746037`.

The owner selected option 1 and authorized continuing into inexpensive
fixture/event members without another design stop. Implementation now changes
the existing owners:

- `test-support/create-base` clones and reidentifies the published store before
  any worker connects its tiered backend. The worker's memory frontend and
  private file backend share the new store identity. Its cleanup releases the
  connection and removes the copy. The existing clone helper now awaits child
  exit and output under `event-backstop-seconds`.
- `source/record-results!` retains expected-head publication and permits one
  immediate reapplication of the exact same completion after a
  `:stale-branch-head` refusal. A second conflict still refuses. Every attempt
  retires its scratch branch. No source fingerprint is recomputed on retry.
- `test-support/assert-check!` now requires a positive trial count as well as
  a true result; the shared regression retains seed `20260728` and smallest
  failing input `[3]`, and rejects a successful zero-trial check.

The canonical concurrent-base regression checks two distinct store paths and
identities, nonempty installed program subjects, isolated writes, completed
cleanup, and SHA-256 equality for every published file before and after.
The existing real source-publication regression now controls both one conflict
(successful recording descended from the competing head) and repeated
conflicts (two attempts, no unpublished result, latest source preserved).

### Verification

- First armed fast run, snapshot `run.fKf8uo` at `ff60a3cf7`: **23 tests /
  191 assertions / 0 failures / 0 errors**. This predates the added
  repeated-conflict and zero-trial assertions.
- First three-worker gate, `run.51bnuS`, snapshot `ff60a3cf7`: **36 tests /
  326 assertions / 1 failure / 0 errors**. Both runner regressions passed;
  concurrent-base proof took **5,783 ms**, source evidence proof **87,008 ms**.
  The sole failure was the new counterexample test comparing an intentionally
  omitted nil `:result-data` entry. The assertion now verifies retained seed
  and smallest failing input. Oversight and schedule namespaces passed.
- Corrected three-worker gate, `run.0j9ayQ`, snapshot `caddf111b` plus owned
  paths: **36 tests / 327 assertions / 0 failures / 0 errors**, exit 0 with
  successful persistent result recording. Slot wait **75 s**. Concurrent-base
  proof **7,663 ms**; source evidence proof **71,054 ms**; coordinator/tests
  **143 s**. The successful isolated root was removed by the runner.
- Final platform gate, `run.kPvCZ4`, snapshot `f22f1f215` plus the four
  owned code paths: **exit 0**, persistent recording succeeded. Slot wait
  **35 s**, base preparation **60 s**, coordinator/tests **185 s**. The
  runner removed its successful root. This exercised the final source
  simplification: the second attempt is directly in the catch body and
  cannot retry recursively.

### Closures and exact remaining scope

Four notes are resolved and archived: the two runner notes (`bc3746037`),
oversight ping absence (existing fix `b5665971d`, live unknown probe plus
canonical boot/page test **40,182 ms**), and the schedule environment fixture
(existing fix `2fa2e1e17`, canonical graph test **1,204 ms**). Their archived
records retain the historical evidence and name their residual boundaries.

P2, P3, and N2 are not closed. The dated inventory below records why the
remaining members require production lifecycle events, settlement, ordering,
or nonempty program-analysis subjects beyond these inexpensive repairs.
The N2 shared assertion now rejects zero trials, but that cannot establish
the nonempty production subject of every individual property.

Exact code/skill paths: `src/seon/cluster/source.clj`,
`test/seon/cluster/source_test.clj`, `test/seon/test_support.clj`,
`test/seon/test_support_test.clj`, `.agents/skills/clojure-testing/SKILL.md`.
Documentation paths: this note, the retained read-only probe, the N2 class
note, and the four archived issue notes. No protected file was edited.
No additional runtime mechanism or schema was introduced.

The failed `run.51bnuS` root was removed after its failure was corrected and
the process table showed no holder. Both later successful roots were removed
by the runner. All lane commands have exited; no lane shell, worker,
scratch cluster, or worktree remains.

### Default live boundary

Default remains PID 69622. An MCP JVM probe returned
`:seon.config/missing-effective` with 69 missing config members while shared
config edits were in flight. This is already recorded by the other lane in
`docs/seon/issues/partial-hot-reload-produces-mixed-code-with-no-warning.md`.
The source-publication log independently reported `IndexOutOfBoundsException`
at `seon.fn/exact-source`, `fn.clj:142`, during analysis. No causal attribution
to a particular concurrent edit is made.

A later 1 ms MCP JVM probe printed the following before returning nil:

```clojure
{:probe/record-results-at-head-loaded? false
 :probe/record-results-doc
 "Publish test evidence through the result writer on a private source branch.\n  A changed expected head refuses publication; no current-src connection can\n  outlive a force-branch operation and later overwrite a newer program."
 :probe/missing-ping
 {:seon.oversight/proc :probe/absent :seon.oversight/ping :unknown}}
```

That observation was temporary. Final MCP JVM probes took **2 ms** and
**3 ms** and printed `:probe/record-results-at-head-loaded? true`, the new
public docstring describing one stale-head reapplication, and
`:probe/connection? true` from `(seon.operator/connection "default")`.
Missing pong still returned `:unknown`. Thus the new definitions are now
loaded on default through in-place development adoption. The controlled
contention behavior was exercised in the canonical file-store regression,
not by mutating default. No restart/refork or manual reload was performed.

## Initial investigation and satisfied design boundary

At the initial checkpoint there were no production edits or issue closures.
That was an investigation checkpoint,
not a completed class repair. The assignment explicitly says: "if the kill
would take hours of cross-owner work or its guarantee cannot be stated in one
sentence, STOP and write three priced options in your landing note and report."
That boundary applies to closing the entire supplied class membership.

The 2026-08-11 class rows name distinct constructions: P2 requires producers
to expose events, P3 requires complete constructor outputs, and N2 requires
nonempty production subjects and retained counterexamples. A test-support
change cannot by itself repair the remaining driver lifecycle, source branch
publication, message ordering, and non-installed program-analysis subjects.
Combining them behind another generic fixture would conceal those authorities.

This is not a stop for foreign gate breakage. No gate has run or failed in
this investigation. The protected edits below were preserved.

## Three concrete options

Costs are engineering estimates from the seams inspected here, excluding
machine-wide test-slot contention; they are not measured execution times.

| Option | Guarantee | Cost | What we give up |
|---|---|---|---|
| **1. Repair the two runner failures first — recommended** | Each worker owns every store that connect may mutate, and result recording reapplies the same completion to a newer source head under a declared bound without overwriting that head. | 4–8 hours: fixture base acquisition plus `seon.cluster.source/record-results!`, canonical concurrent acquisition and controlled head-advance regressions, serial gates. | P2/P3/N2 remain open; this closes two concrete shared-infrastructure failures rather than claiming a global class repair. |
| 2. Finish the fixture/event subset as separate coherent slices | Agent tests get production-shaped handles and wait for named completion facts/events; every repaired property proves a nonempty subject and retains its counterexample. | 1–3 engineer-days, including option 1, agent graph/report ownership where needed, and conversion of the current agent/history fixture observations. | Production driver, message ordering, and program-analysis members stay explicitly open; no whole-class closure. |
| 3. Close every current P2/P3 member and the complete N2 class | Every surviving member has either its owning construction repaired and proven or an evidence-backed supersession with its residual named. | 3–7 engineer-days across source publication, Flow handles, SCI settlement, render/feed, message transactions, operator, and program analysis; coordinate currently protected owners first. | Gives up the bounded fixture lane and fast isolated delivery; requires reviewing class tags that do not describe the class construction. |

For option 1 the smallest existing mechanism is private cloning, already used
by `populate-published-root!` and `populate-published-operator-root!`; extend
that ownership to the tiered fixture backend. Do not add a second store cache.
The result race belongs at `record-results!`, where the expected head is
checked, rather than a runner-side pre-read or removal of the expected-head
guard. The exact contention bound and retry shape remain to be designed in a
canonical fixture after this decision; no retry count has been invented here.

## Authorities and inherited state

Read end to end: `AGENTS.md`,
[issues README](../../../seon/issues/README.md),
[N2 class issue](../../../seon/issues/class-proofs-pass-without-exercising-their-premise.md),
every current tagged member listed below, the archived original members
listed below, both named runner notes, and the complete
[class-mining report](../../sci-execution-runtime/research/issue-class-mining-2026-08-11.md).
No separate P2 or P3 class note was found among tracked issue files; their
class statements and structural changes are in the mining report.
Loaded data-oriented-clojure, repl, clojure-testing, datahike, and
seon-flow-architecture skills.

Observed branch: `steward-platform`; recorded HEAD during investigation:
`22893b71383cec23c8763df7da841524259a1774`. The shared checkout is changing;
the live observations below do not establish equality between that HEAD and
the adopted program. No hot reload, adoption, stop, refork, or restart was
performed by this lane.

`bin/seon status`: default alive, PID 69622, prepl 55914, URL
`http://127.0.0.1:7994`, 404.82 GiB usable. MCP runtime status answered:
2 agents, 18 errored evaluation observations, all three reported plumbing
procs answered. The errored observations are not attributed to this lane.

Protected at the last inventory: `resources/seon/schemas/seon.sci.admit.edn`,
`src/seon/cluster.clj`, `src/seon/render/value.clj`, `src/seon/sci/admit.clj`,
`test/seon/cluster/mcp_test.clj`, `test/seon/effect_test.clj`,
`test/seon/repl_parity_test.clj`, `test/seon/search_test.clj`.
No foreign lane was contacted or operated.

Both `bin/issues-index --class class/p2` and `--class class/p3` exited 1:
the authority has eight missing schedule rows. This is the existing owner
index-reconciliation boundary, not evidence of an empty class. Reading
frontmatter directly found 8 P2 members, 8 P3 members, and 4 N2 members plus
its class note. These are dated counts, not a new maintained roster.

## Dependency ledger and source evidence

| Dependency / authority | Inspected seam | Consequence |
|---|---|---|
| Konserve | `reference-code/konserve/src/konserve/tiered.cljc:88–115` | `sync-on-connect` enumerates backend keys even when writes are frontend-only. |
| Konserve filestore | `reference-code/konserve/src/konserve/filestore.clj:672–727,814–853` | Enumeration may invoke migration; v1 migration explicitly deletes its old data path. A connect is not structurally read-only. This identifies a reachable deleting mechanism, not the actor in a historical failure. |
| Canonical fixture | `test/seon/test_support.clj:206–241` | `create-base` points each JVM's tiered backend at the shared published `base/data/store`. `:write-policy :frontend-only` does not prohibit migration during backend enumeration. |
| Existing private clone | `test/seon/test_support.clj:56–119` | Other file fixtures already clone before opening. Reuse this owner; its current subprocess wait is unbounded and needs the declared event bound when touched. |
| Published cache | `src/seon/test/cache.clj:80–126` | Cache preparation has a lock and launcher references; these do not serialize or isolate worker backend connects. |
| Source publication | `src/seon/cluster/source.clj:297–326` | Results commit on a scratch branch and force the expected head; a changed head refuses. |
| Existing race regression | `test/seon/cluster/source_test.clj:511–588` | A controlled publication during recording preserves the newer head, omits unpublished evidence, retires scratch, and a second explicit recording succeeds. Extend this production-seam proof. |
| Agent test events | `test/seon/cluster/agent_test.clj:250–279,1142–1188,1410–1450` | The shared event bound surrounds a future that still sleeps 25 ms and polls. Named database events already have a helper; pass-count/Var-reload tests need their actual completion observation. |

Git history inspected for the owners: fixture `7f484d4bb`; result publisher
`0d1f72cd0`; schedule and walk tests `d6d399561`; SCI and transcript tests
`ae0e54841`; oversight and driver `6acd8818e`; message owner `be4e3fe00`.
These are last-touch evidence, not an assertion that each commit fixes its note.

## Live probe and exact result

The retained [probe form](fixtures_events_probe_2026_09_15.clj) was evaluated
through MCP **JVM mode**, root `/Users/sean/src/seon`, cluster `default`,
with explicit `(seon.operator/connection "default")`. It reads only.
The successful MCP evaluation reported **1,497 ms**, no exception, and:

```clojure
{:probe/activation-counts
 {:seon.activation/config-defaults 24
  :seon.activation/config-required 69
  :seon.activation/executable-symbols 4637
  :seon.activation/required-attributes 310
  :seon.activation/schema-keys 70}
 :probe/basis 536871495
 :probe/catalog-count 70
 :probe/message-count 5
 :probe/missing-ping
 {:seon.oversight/ping :unknown :seon.oversight/proc :probe/absent}
 :probe/old-ordinal-installed? false
 :probe/ping-timeout-ms 20}
```

The first attempt omitted the handed projection for `config/effective` and
returned `:seon.config/missing-projection`, with a 569 ms database projection
fallback warning. Supplying the projection through the documented REPL owner
made the probe succeed. This was a refused probe input, not tool downtime.

The live result **falsifies** an initial source-reading suspicion that the
activation filter necessarily produces zero keys. The current catalog still
provides 70 matching shapes. It also proves that a missing plumbing pong is
explicitly unknown. Neither observation proves the complete historical
acceptance criteria or fresh publication behavior.

## Per-member disposition at the design boundary

All currently open notes remain open. “Source changed” below is deliberately
weaker than a completed armed regression or a live end-to-end proof.

### P2

| Member under `docs/seon/issues/` | Evidence and remaining verification |
|---|---|
| `concurrent-eval-test-calibrates-interpreted-work-to-wall-time.md` | The old finite-spin oracle is replaced: `test/seon/sci/eval_test.clj:1710–1765` arms both threads, awaits readiness, and observes one cut before the sibling check. Still has raw latch awaits and a spin loop; needs bounded completion and a focused regression before closure. |
| `eval-drives-duplicate-a-four-minute-run-clock.md` | Confirmed source premise: `src/seon/eval/drive.clj:372–373` and `src/seon/bootstrap_drive.clj:401` still derive `run-cap * 240000`; `drive.clj:329` also uses a separate bootstrap bound. These are production driver owners. |
| `observable-graph-transitions-are-polled-in-tests.md` | Confirmed current 25 ms sleep at `agent_test.clj:258`, with seven call sites in park/wake and Var-reload tests. Wrapping the poll in `await-event!` does not dissolve the construction. |
| `oversight-treats-a-20ms-ping-absence-as-state.md` | Partial premise dissolved: live missing pong is `:unknown`; `oversight.clj:223` derives mid-turn from a durable turn ID. The configured bound remains 20 ms. Busy/scheduler/stopped distinction and delayed-scheduling regression not verified. |
| `confirmation-parallel-failure-blocks-reading-worker-protocol.md` | Read the complete record. Current end-to-end confirmation exchange has not been reproduced; remains pending at `seon.test.runner`, not silently declared healthy. |
| `operator-root-inference-guesses-from-directory-names.md` | The recorded construction is root custody/naming, not clock substitution. Requires owner classification review and HEAD probe before changing the note. |
| `reset-deletes-a-bloated-store-one-lstat-at-a-time.md` | Note already records progress support in `bdceb7915`, 5,755 → 5,508 ms on 100k files, and unverified representative-scale behavior/operator wiring. No destructive drill run; no new timing claim. |
| `unowned-namespace-oversight-still-inverts-assignment.md` | Recorded construction is stewardship query semantics, not a clock. Requires owner classification review and current query verification. |

Original P2 member `archive/cohosted-second-boot-is-slow-and-trips-the-silence-backstop.md`
is already resolved; its complete record attributes the remaining progress
repair to `61cbb93ed`. No second-boot timing was repeated here.

### P3

| Member under `docs/seon/issues/` | Evidence and remaining verification |
|---|---|
| `activation-closure-records-no-schema-keys.md` | Live requirements are nonempty (70 schemas / 310 attributes). Fresh seal equality and per-category refusal remain unverified. Protected `cluster.clj` was not edited. |
| `agent-flow-fixture-omits-render-interest.md` | The note records multiple earlier fixture corrections. The broader current agent fixture failures have their own note below; a constructor enumeration regression and full named namespaces are still needed. |
| `dynamic-in-ns-cannot-persist-definition-namespace.md` | Static reader tests include nested movement (`reader_test.clj:501`), but that is not terminal definition settlement. No shared-SCI mutation or falsely equivalent JVM probe was used. Remains unverified at the settlement owner; `sci/admit.clj` is protected. |
| `feed-writer-casts-an-absent-package-number.md` | `web.clj:2737` still casts the package basis, but its caller now uses `await-feed-package!`. Reachable absence must be tested through that producer before attributing a current failure. No browser/feed mutation was performed. |
| `render-token-budgets-are-private-dials-no-producer-supplies.md` | Source changed: namespace reads the profile (`ns.clj:417`), `render.clj:1653` carries it, transcript derives it (`transcript.clj:1880`); old private token key search found no remaining match. Production budget measurement still required before closure. |
| `schedule-graph-test-constructs-a-handle-without-an-environment.md` | Current test uses `with-database`, `environment`, `cluster-handle`, `env/carry`, then the production graph constructor. Source premise dissolved; focused armed gate not run. |
| `system-generated-messages-omit-arrival-ordinals.md` | Old ordinal attribute is absent from the live schema (5 actual messages present). Current `message-order` (`message.clj:485`) sorts instant then ID; `error.clj:1144` constructs the current message family. Restoring an old field alone would not prove current transaction ordering. Needs explicit current ordering design and regression. |
| `turn-consumer-fixtures-read-retired-result-storage.md` | Some fixture work landed: `seed-cluster!` (`test_support.clj:625`) now invokes config application. Source still contains legacy history fixture identifiers (`transcript_test.clj:1026`); no broad pass claimed. The note's dated agent failures require current canonical reproduction. |

Original P3 members `archive/loop-settlement-consumer-reads-a-key-no-producer-writes.md`
and `archive/web-config-dials-ship-without-shipped-defaults.md` are already
closed; read both complete records. Their historical closures are not new
verification in this lane.

### N2 and the two runner notes

| Member under `docs/seon/issues/` | Evidence and remaining verification |
|---|---|
| `render-wave-properties-cannot-produce-their-failing-cases.md` | Old P1/P5 names and floor helpers are gone. Current walk uses production acquisition and explicit subject assertions; transcript still has a property at `:1016` plus legacy fixtures. Replacement alone does not prove retained counterexamples; needs current property audit and gate. |
| `bootstrap-o4-stops-before-causal-delegation-settles.md` | `drive.clj:110–119` still derives directly triggered turns. The production causal closure is outside fixture-only scope. No paid drive run. |
| `context-mvp-drive-can-false-green-after-cross-agent-delivery.md` | The note says its temporary driver was deleted; acceptance depends on the same causal closure as O4. Do not recreate the old script or close on target-only quiescence. |
| `output-sink-query-excludes-operator-and-mcp-scripts.md` | Complete note read. Requires a production non-installed analysis subject, not a test-side concatenation; no current complete sink census was run. |
| `parallel-test-base-connect-can-lose-a-filestore-key.md` | Concrete unsafe ownership path identified in the dependency ledger. Concurrent fixture reproduction and immutable-source verification remain to be implemented; historical deleting actor still unproven. |
| `test-result-recording-refuses-after-branch-head-change.md` | Existing controlled race test and expected-head seam read. No automatic recovery exists in the inspected function. Preserve the guard and immutable completion provenance. |

Archived original N2 records read end to end: transport taxonomy
(`270a66fd4`), public-contract census (`dac16b297`), and oversight fleet roster
(`5bc903010`). The N2 class note also records assertionless-test enforcement
in `ad3d13e9b`. None establishes complete class closure.

## Initial checkpoint verification and cleanup (historical)

- Production tests/gates: **not run**; stop is before production edits under
  the assignment's design gate. No green canonical-harness claim.
- Read-only live evidence: successful MCP JVM probe above; default unchanged.
- No worker, background shell, scratch root, worktree, provider call, or
  destructive operation was created by this lane.
- Deliverables in this checkpoint: this note and its retained read-only form.
- Next step requires selecting the scope above. Class notes cannot honestly
  be closed on this evidence.
- Checkpoint commit: `6960ee37a`. Probe lint passed with zero errors and zero
  warnings; path-limited whitespace check passed. The Markdown hook reported
  an unrelated current-gitlink citation in
  `docs/prds/context-generation/research/refusal-grammar-2026-09-15.md:70`
  (recorded SCI hash `38e627467daa3f6f1e5a8eb6421f702d2a940b7f`, checked-out
  gitlink `fcbd8862800e638dc0f8f5521111f999279cbcd2`). That foreign document
  was left untouched; this is not a claim of a green repository Markdown gate.

---
type: review
status: REVISE
created: 2026-09-25
scope: Re-review of Opus round 2, nine corrections, root ruling 9.6, and MCP fault delivery
---
# Lost-error seams design review — 2026-09-25

**Verdict: REVISE.** The stop-caller and bootstrap corrections are closed as design decisions. The dependency seams, deletion of `await/task`, separation of preparation from activation, and shared rendering direction are improvements. Remaining faults can still disappear during launcher shutdown; the activation order contains a cycle; and section M overlooks existing fault-window owners and does not yet establish cluster-wide, reproducible MCP error observations. These require changes in the existing design before the affected cuts start.

Reviewed all 705 lines of [Opus round 2](lost-error-seams-design-2026-09-25.md), commit **`e357d8f0f`**, against the nine corrections in this review's previous version, **`668d8aa79`**. Source references below are at observed HEAD **`0ecc0e6a2f6a6e17e872553585338574c20a61c7`**; all cited source paths were checked unchanged from the earlier review snapshot `19a29c683`. `design:N` means `docs/research/agent-platform/lost-error-seams-design-2026-09-25.md:N`; other paths are repository-relative. The design is unchanged at this HEAD. Root's current instruction settles §9.6: use stored program call data, and return ambiguous responsibility to the agent. That ruling is accepted, not reopened here.

This is a source/design review, not proof of installation. No source edits, runtime evaluations, tests, graph operations, other-lane messages, worktrees or provider calls were performed. Only this review is revised in place.

## Disposition of the nine corrections

### 1. Lossless Flow reporting — OPEN, with the SPI seam accepted

**Answered:** `design:44–64` replaces the sliding channel with a per-proc port passed through `ProcLauncher/start`. The upstream seam exists: `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:155–162` supplies `:outs`; `:313–319` reports through that map; `:179` closes only the graph error channel. Thus the proposed replacement can avoid both that capacity and that close. No fork is required merely to substitute the port.

**Still open:** “`UnblockingBuffer` never drops” is not a complete disposition guarantee. `Buffer/add!*` runs **under the channel mutex** (`reference-code/core.async/src/main/clojure/clojure/core/async/impl/protocols.clj:31–35`). A throw from it is caught by the channel's `add!` wrapper and sent to its exception handler (`…/impl/channels.clj:283–306`); normal return from that handler can leave the original report unrecorded while the put completes. A stuck synchronous recorder also blocks the reporting thread despite the buffer's name. `RefusingBuffer` is precedent for admission effects, not evidence that arbitrary fault storage and graph stopping are total inside this callback.

The incoming report is a map containing proc, state, input, message and throwable. `proc-failed!` currently accepts only a Throwable and constructs its own context (`src/seon/flow.clj:211–218`); forwarding only `::flow/ex` loses report evidence, while passing the map violates its contract. Its panic path also renders and flushes (`:220–231`), operations whose failure must not disappear in the channel exception handler.

**Smallest fix:** retain the SPI substitution, but specify the adapter's full report-to-fault value and its nonthrowing terminal disposition, including the channel exception-handler case and the recording deadline/failure path. Preserve the existing execution owner; do not add a reader or supervisor. If the buffer callback cannot meet that contract, use the narrow synchronous dependency report hook discussed in correction 1 of the previous review. The scratch probe must include recorder failure/stall, a disposal failure, a full `send-outputs` report, and stop overlap, not only successful puts. P1 may begin that investigation; its production guarantee is not accepted yet.

### 2. Exactly-once task disposition and real exit — OPEN

**Answered:** `design:66–75` deletes the speculative task framework. `design:94–107` names body, callback, rejection, pre-entry and running outcomes; G02 uses executor termination. This is the right ownership direction.

**Still open:** the lifecycle table sends body/callback faults to the launcher's completion input (`design:97–98`, `:113`), but stop still clears `::active?` (`design:100`). The existing disposer offers completion only while that flag is true (`src/seon/flow.clj:413–424`); stop clears it at `:632` and stops the graph at `:667`. Therefore a handler that throws after stop can be consumed by the task catch and removed from tracking without ever reaching the fault owner. If the flag guard is removed, a late `offer!` to a stopped proc is still not authoritative disposition.

There is also a concrete panic mismatch: `work-launcher-step` calls `proc-failed!` at `src/seon/flow.clj:518–519`, but that function returns a boolean. It does not stop the graph; `var-process` separately performs that action at `:291–303`. The design's statement that the completion reader's `proc-failed!` call stops it is false. Finally, deleting `::task` leaves the table's “interrupt the entered thread” without a named retained thread handle or a race-safe publication point. The current `::active-work` entries hold flags, not that handle (`:439–450`).

**Smallest fix:** have the existing terminal owner dispose thrown body and callback failures with the carried world and actual panic action **before** removing the submission, regardless of completion-channel liveness. The channel then carries replaceable launcher accounting, not the only failure evidence. Preserve both failures when body and callback fail. Specify the thread handle/entry race using the existing submission state; join the executor before resource release. Include release failure and a late declared refusal in the existing lifecycle rows. This is a correction to cut L, not a new helper.

### 3. Actual stop caller and timed Delay deref — CLOSED as a design correction

`design:534–550`, `:578–584` names the real caller and pairs its refusal with G02. At HEAD, `src/seon/cluster/boot.clj:499` discards the launcher result before branch/store release at `:500–502`; its catch at `:512–514` retains the instance. Throwing the returned timeout before release uses that existing owner correctly. The proposed test traverses this caller and retries only after real exit.

The delay replacement is also correctly located: `src/seon/cluster/agent.clj:1313–1320` installs/forces the stop delay, so waiters need an owner-published completion rather than timed deref of that object. Keep the promise's terminal outcome explicit, including failure; a `finally` notification is only operation completion, never thread-exit proof. `design:573` now measures contended deadline excess for G14/G15. None of these source observations proves the implementation installed, and cut L remains blocked by correction 2.

### 4. Loadable preparation and activation — OPEN

**Answered:** `design:359–375` leaves `record!` public during preparation, adds `seon.fault.edn`, and delays private/core-only activation until callers are converted. The named direct callers still exist at `src/seon/cluster.clj:3555`, `:3562` and `src/seon/cluster/boot.clj:362`; their conversion is an actual precondition, not documentation cleanup.

**Still open:** `design:342` requires R3 after E-activation, while `design:374`, `:404` and `:614` require E-activation/R4 after R3. Remove that cycle. In addition, `commit-call` is a Datahike transaction function returning transaction data (`src/seon/error.clj:1331–1335`, `:1443–1453`), invoked by `:db.fn/call` (`:1550–1551`). It cannot “also return the recipient” as an arbitrary second result (`design:360`). The recipient must be derived from committed message/transaction evidence or be declared transaction data, then projected into the receipt by the recorder.

The shipped default alone also does not make P2's “root by default” claim true for all core faults: the existing `interrupted?`/`:your-run`/`nil? agent-id` branches at `src/seon/error.clj:1393`, `:1430–1442` survive until R4. P2 is preparation, not completed routing.

**Smallest fix:** make P2 a compatible receipt/evidence preparation over the writer's existing return contract; identify its committed evidence read. Keep the old routing explicitly incomplete until activation. Choose one acyclic order, for example R3 using P2's still-compatible writer followed by R4, and remove `design:342`'s contrary requirement. Grant every converted caller/schema/test in that activation. Root has already ruled responsibility, so no policy question remains here.

### 5. Malli enforcement and stored-call responsibility — OPEN in the table; root's policy is sound

**Closed portion:** D-delete preserves the correct enforcement seam (`design:285–303`). Malli `-instrument-f` checks arity/input before calling the function and output/guard afterward (`reference-code/malli/src/malli/core.cljc:2202–2231`); Seon's throwing report is at `src/seon/instrument.clj:705–713`. Delete the outer refusal-returning catch at `:715–726`. The cause-chain receipt check at `src/seon/fault.clj:171–177` is also the correct reuse.

**Ruling:** stored `:seon.fn/calls` and the selected program's declaration evidence are a sound **conservative responsibility policy**, not a dynamic call trace. If both authored and installed callers can explain the failure, returning it visibly to the agent is precisely root's accepted ambiguity rule. Do not add stack-frame guessing or per-call markers to improve that case.

**Still open:** the table's “otherwise (a host caller supplied bad arguments)” (`design:310`) equates missing authored membership with positive host proof. Calls are optional stored reach edges (`resources/seon/schemas/seon.fn.edn:49`, `:159`); unresolved references and unknown call shapes are explicitly separate (`:70–89`). Missing, stale or incomplete analysis cannot prove host responsibility. Likewise, “row differs from default” (`design:311–314`) is insufficient by itself: a cluster can retain an older installed program while default advances. Responsibility must use the declaration actually exercised and its authored provenance in that agent's selected world.

**Smallest fix:** replace the table's fall-through with three evidence cases: authored, positively established installed/core, and ambiguous/unknown. The last returns the complete refusal to the agent, including absent/incomplete analysis and mixed callers. Validate stored analysis against the exercised definition; do not compare blindly to today's default. Preserve Malli's report kind, arm, offending values and cause, and distinguish a validator-engine fault from an invalid authored schema when the evidence can establish that distinction. `failure-value` currently receives a subject, throwable and diagnostic record (`src/seon/sci/kernel.clj:531–536`); name how the existing pipeline supplies the held program/analysis world and fault context there, rather than introducing ambient reads.

Revise §9.6 in place to **RULED**, remove its alternative proposals, and add missing-analysis, mixed-caller and default-advanced/retained-cluster cases to D's regression table. The +14-line estimate is unproved until that data path is named.

### 6. Unsafe artifact admission and durability — OPEN, substantially narrowed

**Answered:** `design:479–488` deletes the complete-HTML `data-blob` producer and keeps exact shown bytes plus the live subject. It no longer promises complete lazy-value round-trip. Its existing-reader census and separate storage timing are useful.

**Still open:** the assertion that the retained full evidence is bounded by `max-evidence-bytes` (`design:489–490`, `:517`) is false at HEAD. `src/seon/error.clj:527–537` admits `full-edn` under the supplied result caps, then assigns `max-evidence-bytes` to the **inline** limit. `evidence-stage` simply stages that precomputed content (`:1467–1471`). The retained path still enters `admit/admit-partitioned` (`:301–314`), whose value admission calls `Throwable->map` and traverses lazy/host collections (`src/seon/sci/admit.clj:295–297`, `:345–350`). A byte allowance does not bound an arbitrary method or lazy realization before the next byte.

**Smallest fix:** keep the HTML deletion; explicitly include the retained evidence producer in the shared safe-storage selection migration, or narrow its accepted durable inputs to already-safe retained data and mark the rest unavailable. Use the render owner's existing safe selection, opaque values and terminal cuts; do not add another artifact codec. Correct the actual storage dial and cost claim. Prove error recording itself avoids unsafe realization, not merely the subsequent AI/HTML views. The shared render design already names this hazard and mechanism (`docs/research/agent-platform/one-render-path-design-2026-09-25.md:466–478`). F is incomplete while that retained producer bypasses it.

### 7. Bootstrap classpaths — CLOSED as a design correction

`design:117–124`, `:555–558`, `:569–587` keeps operator/tool-classpath work on JDK primitives and makes runtime migrations depend on the repaired process owner. This answers the original dependency inversion: `script/seon/dev/dependency_digest.clj:7–13` is deliberately core/JDK-only, and `dev_cache.clj:208–221` loads it before the project classpath is acquired. No new runtime import is proposed there.

Implementation still owes bounded joins, both terminal outcomes, survivor ownership and proof on each actual classpath. A `.handle` result must itself be consumed; merely registering a callback is not the promised join. This does not require another design framework or runtime-only test harness.

### 8. Detached panic and producer-complete B/C migration — OPEN

**Answered:** `design:150–153` withdraws the impossible claim that privatizing a recorder prevents arbitrary catch/default bugs. `design:179` separates EDN parsing from the failed Malli walk, matching `src/seon/render/ns.clj:134–156`. `design:215` now includes both `opening-deferred?` consumers (`src/seon/turn.clj:2793`, `:2913`). C.3 names backstop, feed, HTTP and proc execution owners.

**Still open:** the new deduplication rule compares `:seon.error.occurrence/last-at` to a wake's transaction instant (`design:218–221`). `last-at` is copied from the producer's `:seon.error/at` (`src/seon/error.clj:1386`, `:1408–1409`), not an authoritative transaction ordering. Clock changes/equal millisecond times can conflate obligations, and two stale derivations can both record. The first record's timestamp does not make the second writer idempotent.

B08 also needs a finer rejection distinction: the Malli fork uses `:malli.generator/no-generator` both for an absent generator and for an exception evaluating a declared generator, preserving the latter as its cause (`reference-code/malli/src/malli/generator.cljc:472–482`, `:501–502`). Matching the keyword alone cannot promise that generator machinery failures stay red (`design:177`, `:190–191`).

Finally, E06's “failing thread belongs to no graph” (`design:385`) contradicts A's proposal that an escaping task failure reaches the uncaught handler (`:104–106`) while those tasks run on the launcher's graph-owned executor (`:541–543`). Thread death does not stop the graph or preserve the lost submission disposition.

**Smallest fix:** decide duplicate obligation disposition at the existing writer using the actual wake/basis identity or transaction evidence, and propagate read refusal rather than interpreting it as no prior occurrence. Retain the independent root message and graph-stop responsibilities. Distinguish missing-generator data from a wrapped generator failure without discarding its cause. Make the launcher boundary contain its own failures with its explicit environment/owner; reserve E06's ownerless treatment for genuinely ownerless threads. Cover two derivations from one held basis, clock-independent wake ordering, generator-with-cause, and escaped task cleanup in the existing suites.

### 9. Rebased inventory, costs and ownership — OPEN for coverage/order; arithmetic corrected

**Answered:** `design:237–249` counts the retained monitor separately; `:387` gives the literal callback search/count unit; `:502` retires F20; `:598–650` uses per-cut estimates and whole-file grants. Summing the source rows gives **−641/+338, net −303**, consistent with the rounded −640/+340. Schemas sum to −76/+11. These are planning estimates, not measured diffs.

**Still open:** the file table is not yet an exhaustive loadable assignment. P1 prices `flow_test` (`design:637`) but owns only `flow.clj` (`:607`); R1 explicitly converts `cluster/fault_message_test.clj` (`:249`) without owning it (`:610`); E activation's prose includes `instrument.clj` and remaining instrument tests (`:366–373`), absent from R4's row (`:614`). F21's `plan.clj` producer remains in the migration (`:501`) but not T's landed classes (`:618`). R2 prices tests without naming them. The R3/R4 cycle is correction 4.

Section M adds a larger omission: it budgets a new query and one reply-list line without retiring existing bridge/history window machinery (below). That invalidates its claimed one-owner scope even though the table arithmetic is now coherent.

**Smallest fix:** reconcile the existing table with its own producer and test lists; sequence every shared file, and add the M retirements to the owning pipeline/render cut. Keep current ledger checks at launch; historical holders are not permissions. No new plan or issue-index edit is needed.

## Section M: MCP is a view, and printing has one owner — OPEN

The intent at `design:418–445` is correct: obtain ordinary fault data from the Seon agent's world and include it in the same reply units and budget. No bridge classifier, printer or per-session cursor belongs in that design. But the proposed implementation is not yet that composition.

### M1. Reuse and retire the existing owners

“MCP evaluation replies carry no faults” (`design:416`) is false at HEAD. `script/seon/dev/mcp.clj:511–562` queries a fault window, maintains `window-bases`, clips to `max-window-errors`, and `:629–635` appends it as a second text block. The shared history already derives evaluation fault windows at `src/seon/render/transcript.clj:346–402`. Both use **`seon.error/occurred-since`**, implemented at `src/seon/error.clj:1571–1616`.

**Smallest fix:** make the existing error query owner supply the needed data, with any `seon.problems` function only a thin declared projection if needed. Move window selection to the agent pipeline and retire the bridge cursor, extra evaluation/query, `take`, locally generated requery text and extra JSON/text block in the same cut. Convert history to consume that same observation/selection rather than derive a competing window. Section M currently says it adds no bridge code; it also needs to name the bridge code it deletes. Existing `occurred-since` returns unresolved signatures with cumulative touched-occurrence counts, so reuse must reconcile those semantics with the owner's desired fault observation; its current counts are not automatically “new failures.”

### M2. Name the database, interval and ordinary observation

The owner asked for **the cluster's** faults on its MCP interactions. A selected agent branch can predate faults on the cluster branch. `fault/record!` records on the origin and, where held, copies to the cluster (`src/seon/fault.clj:50–55`); that does not update every other agent branch. Passing only an unspecified `database` and `basis-t` (`design:422–433`) can miss those faults. A basis from one divergent branch is not a cursor for another's commits.

“Previous recorded evaluation” also differs from “previous interaction” (`design:77–79` versus `:431–433`). System evaluations, multi-form requests, failed evaluations, status-only requests and compaction need a consistent interpretation. The evaluation schema has optional read basis and derived `:t` (`resources/seon/schemas/seon.eval.edn:10`, `:25–28`), not a proved record of a cluster-fault observation in every interaction. Using an evaluation's commit basis while querying an earlier captured database can skip a fault committed between those two events; using its start basis can repeat faults. Neither is the claimed exactly delimited observation without specifying the interval.

**Smallest fix:** supply the cluster database/identity explicitly through the agent's world and choose one immutable observation value with a lower basis and a captured upper basis. Derive the next lower basis from that agent's previous ordinary recorded **fault observation**, with its shown text, rather than a transport cursor or any unrelated evaluation. If that observation/basis is absent, return a declared unknown or conservatively replay a documented window; do not invent a branch-creation fact. Preserve historical shown bytes and use the same observation for AI/HTML. The pipeline owner must name the existing recording seam that carries this; it is not established by “one line” alone.

### M3. Query semantics, delivery evidence and counts

A since-view contains changed datoms, not complete occurrences plus unchanged signature/message relations. Datahike's `SinceDB` attaches the exclusive time predicate to searches (`reference-code/datahike/src/datahike/db.cljc:729–734`). A recurrence can update count/time without reasserting every older identity/ref. Select changed occurrence identities from the delta, then resolve their relations against the held full database, or reuse `occurred-since`'s full-database transaction predicate. Name refusal propagation and the actual work bound; O(new occurrences) is unproved merely from the word `since`.

`design:429–430` also incorrectly infers that panic makes `undelivered-count` zero. A fault can commit and then fail the delivered-recipient check; panic leaves those facts in place. Moreover, a recurrence may correctly reuse an earlier open message (`design:360`, `:394`), so “no new message in this window” is not “undelivered.” `seon.fault/delivered-to` is proposed receipt data, not yet a stored notice field; the existing `error/notice` constructor at `src/seon/error.clj:697–708` needs its real fact/message relation supplied.

**Smallest fix:** derive delivery from the same committed recipient/message evidence as P2/R4, including prior open notifications and current failed delivery. Define whether each count means signatures, occurrence rows, or events. The occurrence count/last-at attributes are explicitly no-history (`resources/seon/schemas/seon.error.occurrence.edn:3–5`), so arbitrary historical event deltas cannot be promised from cumulative counts alone. Keep an honest exact signature/row count unless retained evidence supports a stronger one.

### M4. One render path must also govern continuation and error outcome

Calling `(faults-since db t)` again at the same budget returns the same leading selection, not the omitted remainder promised at `design:451`. Use the render owner's source reference, held basis, typed path and absolute offset (`docs/research/agent-platform/one-render-path-design-2026-09-25.md:451–478`). Reusing that continuation supplies both the MCP and browser faces; M should not invent fault pagination or a second budget.

State the common pipeline outcome for a fault-bearing reply and how MCP encodes it as an error while retaining the ordinary evaluation result. Currently MCP's `isError` choice follows only the evaluation terminal; the fault block is appended afterward (`script/seon/dev/mcp.clj:631–635`). Simply moving a text block does not establish the owner's “surface as errors” requirement. The adapter should encode the pipeline's declared error outcome, not classify faults or format them independently. A storage/read failure must surface as a typed unknown/error, never an empty healthy window.

Add regression rows for two agents on different branches observing one cluster fault, recurrence with an old notification, failed delivery after a committed occurrence, faults during/after evaluation, failed/no-evaluation interactions, compaction/missing basis, and continuation beyond B. Assert the same saved observation and text through the shared agent entrance, MCP and browser adapters, including the error outcome. These are future implementation obligations; no tests were run in this lane.

## Cuts that can start now

Subject to root's current whole-file grants:

- **S scripts** and **S process** can start as the bounded JDK/process-owner repairs described in A/G. Runtime callers migrate only after A07/G05 is fixed; bootstrap remains dependency-free.
- **P0 D-delete** can start after the instrument owner releases its files: retain Malli's throwing report, remove the outer return escape, fix D02–D04, and preserve already-recorded panics. Its stated temporary classification gap remains explicit; it does not close E or authorize R4.
- **P1 can start read-only dependency/probe work**, not its production buffer implementation, pending correction 1. **P2 can start the recipient-evidence investigation**, not private/core-only activation; its return contract and staging must first be corrected under correction 4.

**Do not start R1/R1t, L, R3/R4, M1/M2 or claim F complete under this design yet.** Their named corrections affect fault preservation, shared ownership or delivery semantics. G-agent's local completion change is accepted in principle but retains its stated R1 dependency. No other lane was contacted or reassigned by this review.

## Evidence and limits

Read the shared instructions, the earlier review/audit, the full revised design, relevant caller/schema/dependency source, the data-oriented Clojure, Flow and Datahike skills, turn PRD §§13–15, and the shared render selection/continuation requirements. Dependency pins checked: core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`, Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`. The Flow skill's older commit-key context claim is not adopted; current AGENTS content-valid reuse governs. Only claims grounded by the source references above are used.

The design's historical runtime counts, SCI/reader probes and rendering timings were not rerun. Source proves counterexamples and available seams, not publication, adoption, arming, runtime health, latency or memory. Foreign dirty files and concurrent commits were preserved; none blocked this read-only review. Source/test delta **0/0**. The only changed path is this document; no timing or memory result is claimed for an unexecuted implementation.

---
type: review
status: REVISE
created: 2026-09-25
scope: Independent review of lost-error classes A–G
---
# Lost-error seams design review — 2026-09-25

**Verdict: REVISE.** Keep the deletion of the second fault channel, the removal of Malli's outer refusal-returning wrapper, and the shared render owner. The first pass does not yet make A–G impossible: it expressly retains lossy transport, leaves a release-after-timeout caller, and substitutes an incompletely specified task lifecycle. These are design corrections before implementation, not requests for another framework.

Reviewed: [Opus design](lost-error-seams-design-2026-09-25.md), commit `8d1e9ead02f30e56a539219cfbba85de074eeb3f`, all 461 lines; [my audit](lost-errors-audit-2026-09-25.md); AGENTS.md, including the agent-perspective law; the data-oriented and Flow skills; the turn PRD §§13–15; and the render design's shared-selection, artifact, regression and migration sections. Source spot-checks below are pinned to **`d3c0d630e`**, a HEAD observed during this review, unless a dependency revision is given. Subsequent HEAD movement was inspected separately; it did not change the cited critical Flow, boot-stop, instrumentation or artifact seams. Design line references below use the reviewed first pass.

No source edits, runtime evaluations, tests, graph operations, stop/reset, worktrees, other-lane messages or provider runs were performed. Source establishes counterexamples and dependency guarantees; it does not establish what is installed in the running JVM. The design's reported faulted runtime is historical evidence, not a fresh health observation.

## Required design corrections

### 1. Flow's remaining error route still loses faults, including during shutdown

**Design:** `lost-error-seams-design-2026-09-25.md:37`, `:40`, `:436`.

The residual is contrary to the requested guarantee, not merely an acceptable low-probability overload. At core.async revision `dc35f3e0d7bc2eef502e77982f48641f025c8051` (`1.10.874-alpha3`), `flow/impl.clj:102` creates a sliding error buffer. Its proc loop catches errors from output delivery and other loop operations at `:312` and `:318`, reports, and recurs with the old state. Repeated failures need not be 101 independent exceptional incidents. Absence of application `:xform` use does not prove these paths unreachable.

There is a second hole independent of capacity: `flow/impl.clj:174–183` sends stop, closes the error channel, and returns before proc exit. A proc still unwinding or delivering outputs can report to the closed channel. Putting the reader on the graph executor proves that reader exited; it does not prove every producer reported before channel closure. Nor does it handle the reader's own recording failure unless that failure stops the correct graph.

**Smallest fix:** use the dependency's existing error-production seam for the guaranteed disposition. Price a narrow maintained-dependency change that exposes a synchronous error-report hook, or an error channel with an explicit producer-exit/close contract. Preserve Flow's error maps and control machinery; Seon transforms the map and calls its existing fault owner. An observer channel may slide only after authoritative disposition. A configurable blocking buffer alone fixes overflow, not premature close; backpressure must also have a bounded, loud failure path. `deps.edn:23` consumes the Maven artifact, not the reference checkout, so a fork proposal must include the actual dependency pin/publication change. No upstream PR is required by this review.

The class regression must cover a stalled recorder, more than 100 finite reports, an output/loop error, and an error racing stop. Every failure must either receive durable disposition or leave a visible failed owner with complete emergency evidence. Documenting loss cannot close C03 or E06.

### 2. `await/task` does not yet specify an exactly-once terminal or a real exit

**Design:** `:61–69`, `:88–94`, `:342–350`.

The proposed `(try (deliver-terminal (body)) (catch Throwable t (deliver-terminal t)) …)` encloses both body and consumer. Unless the consumer is separately contained, a throwing `on-exit` invokes the consumer again. Saying its failure goes to `fault-detached!` still leaves that handler's environment, fault context, failure path and graph-stop obligation unspecified. `fault!` currently requires all of these data (`src/seon/fault.clj:190–204`). A bare callable plus callback cannot supply them automatically without ambient ownership.

Cancellation before execution is also missing. The launcher sets `::running` before constructing/submitting its FutureTask (`src/seon/flow.clj:437–468`). Cancellation can therefore prevent the callable from running after Seon labels it running: its `finally` and exit promise never execute, and the proposed queued-only CAS rule does not apply. Executor rejection is another no-body path. Conversely, delivering a promise in the callable's `finally` proves that point was reached, not that its thread returned. A02 currently joins the actual thread (`src/seon/test.clj:282–291`); replacing that with a release inside `on-exit` weakens the proof unless no resource use remains after release.

The library already has public `clojure.core.async.flow/futurize` (`flow.clj:334–346`), implemented by `FutureTask` plus the supplied executor (`flow/impl.clj:29–36`). It owns submission, not cancellation-safe exit or result disposition. The [Java Future contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Future.html#cancel(boolean)) explicitly permits cancellation to prevent startup and specifies interruption as an attempt to stop running work. It cannot replace an exit observation.

**Smallest fix:** retain `io-terminal!` as submission settlement and the actual thread/executor join as exit proof. Compose `futurize` where only library submission is needed. Specify the small remaining adapter as data: body outcome, consumer outcome, startup/rejection outcome, carried world, and one owner of disposition. Keep the body catch separate from consumer failure. Reuse the existing submission CAS for no-start/running races; do not invent another lifecycle registry. Keep `await!` an observation operation; cancellation belongs to the execution owner. Only add a shared task helper after showing the exact duplicate code it replaces across compatible callers.

Add regression rows for cancellation before body entry, rejection, body plus callback failure, fault recording failure, and release failure. Distinguish a body's returned declared refusal from a thrown core fault, and prove timely and late outcomes are not both recorded by callback and waiter. The measured 44 ns promise microbenchmark is not the cost of this missing lifecycle, arming, custody or delivery.

### 3. The stop caller actually releases after a launcher timeout; a Delay cannot use timed deref

**Design:** `:345–352`, `:360`, `:443–445`.

Question 9.4 names the wrong caller. `src/seon/cluster.clj:3711–3712` checks the fault fan-out's stop, not the work launcher. **`src/seon/cluster/boot.clj:499` discards `stop-work-launcher!`'s result; `:500–502` releases the branch and root store.** The catch at `:512–514` retains the instance only if something throws. A returned timeout map does not enter it. Merely retaining submissions inside Flow does not retain their usable world.

G03's proposed timed deref is unavailable. `src/seon/await.clj:14–16` requires `IBlockingDeref`; `:149–153` calls its timed deref. Clojure 1.12.5's `Delay` implements only `IDeref` and `IPending` (`reference-code/clojure/src/jvm/clojure/lang/Delay.java:18`, revision `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`). Its realization locks at `:40`; it exposes no timed deref. `src/seon/cluster/agent.clj:1313–1320` really stores and forces delays. Passing one to `await!` either refuses the contract or fails at the cast, rather than bounding acquisition.

**Smallest fix:** check the launcher's returned refusal in `boot/stop!` before any release, preserving the existing catch/instance retention. Include this boot hunk in the G02 atomic cut. For arming/stopping, await a completion event published by the existing operation owner, while retaining its claim; do not create an unowned future merely to dereference a delay. Keep G14/G15 as unproved triggers, but measure deadline excess under contention, not only an infinite hang: finite work can still violate a declared bound.

The regression must traverse the real stop caller and assert branch/store/instance retention, late failure consumption and eventual release. A helper-only test misses this counterexample. The existing process `onExit`/identity seam (`src/seon/cluster/process.clj:158–189`) is the right mechanism for subprocesses, provided every survivor keeps its resources and ownership.

### 4. The private-recorder cut is not loadable in the proposed order

**Design:** `:112`, `:250`, `:389–406`.

E0 makes `record!` private while lane 4 later owns its callers. At the review pin, direct external calls remain at `src/seon/cluster.clj:3555`, `:3562`, `src/seon/cluster/boot.clj:84`, `:362`. Existing test namespaces also call it directly, including `test/seon/cluster_test.clj:121`, `test/seon/instrument_test.clj:195`, `test/seon/flow_test.clj:698` and `test/seon/cluster/fault_message_test.clj:23`. Removing public access before their conversion breaks fresh namespace compilation. Existing loaded Vars are not proof that the slice loads.

E0 also says `commit-call` receives only core faults **after** B/C, yet changes its routing **before** those cuts and E09. The acquisition writer still combines `::acquisition-refusals` and `::agent-mistakes` at `src/seon/sci/eval.clj:2439`, then calls `error/commit-tx` at `:2460`. Its retirement is assigned to lane 7 after E0. D's classification tests require E delivery, despite D being declared independently landable.

**Smallest fix:** separate compatible preparation from activation. First prepare writer delivery evidence and preserve current caller contracts. Retire/classify all recorder callers and acquisition dispositions in the same activation that makes `record!` private and makes `commit-call` core-only. Convert or delete reaching tests in that cut. Alternatively leave the public recorder temporarily unchanged and explicitly mark the class guarantee incomplete until its final retirement; do not claim E0 established it. Root must grant whole-file ownership for that activation. No same-file hunk parallelism.

Add `seon.fault.edn` to ownership: its recording tuple and receipt (`resources/seon/schemas/seon.fault.edn:1–34`) must change with the new delivered recipient. Add `seon.await.edn` for any task/exit request shape. These are not covered by an unspecified set of seven fault-channel schema files.

### 5. Malli supplies the report seam; responsibility needs call provenance

**Design:** `:208–220`, `:269`.

Removing `src/seon/instrument.clj:715–726` is the smallest correct D01 fix. Keep the throwing `:report` in `m/-instrument` (`:705–714`). Malli's `-instrument-f` checks input before entry and output/guard after the body (`reference-code/malli/src/malli/core.cljc:2202–2231`, revision `befcd302a8e626a67d84b80aabee0cd1b38dadb6`). A report that returns normally does not enforce refusal; the current throwing report does. No second output validator or general error union is needed.

However, “an input refusal is an agent mistake” is too broad. A valid agent call can reach a host function that supplies invalid arguments to another host function. That input refusal is a core bug. Similarly a host default supplier can fail while preparing an otherwise declared agent call. `:guard`, `:arity` and the fork's `::invalid-schema-at-call` need explicit disposition; a validator throwing while checking input is not automatically bad user input. Classifying by whether a schema occurs in an arity's alternatives is insufficient: declared error alternatives describe possible values, not who caused them.

The proposed “host (default program)” distinction must use the declaration's actual execution/provenance evidence in the selected agent world, not namespace membership or a default-versus-branch shortcut. `kernel/invoke` at `src/seon/sci/kernel.clj:653` is an existing shared boundary, but the design must trace the same classification through the agent entrance used by MCP, tests and web. A second transport-specific classifier would violate the new law.

**Smallest fix:** retain Malli's report kind, checked arm, offending values and operation; carry the already-known caller/declaration provenance to one existing evaluation/fault boundary. State a small responsibility table including direct authored input, nested host bad call, authored output, host output/guard, validator failure and already-receipted panic. Reuse `fault!`'s cause-chain receipt detection (`src/seon/fault.clj:171–194`), rather than only checking top-level `ex-data` in the kernel. Add these rows to the real armed-composition regression. D02–D04's precise union/error propagation corrections are otherwise sound.

### 6. F01 reintroduces unsafe admission and promises an impossible artifact round-trip

**Design:** `:304–305`, `:312`, `:319–329`.

The design proposes `admit → artifact-edn → blob/put!`, then asserts that an unforced lazy value reads back as the value. The existing `artifact` is a print-node artifact, not exact arbitrary-value storage. It deliberately substitutes an omission marker when admission did not retain the value (`src/seon/render/value.clj:683–710`); `artifact-value` reconstructs that node (`:724–728`). Admission also calls `Throwable->map` before safe render selection (`src/seon/sci/admit.clj:297`). Renaming this to “the offending value's artifact” supplies no safe traversal or full-evidence guarantee.

This conflicts directly with the referenced render design: `one-render-path-design-2026-09-25.md:466–478` removes raw admission before the floor and makes artifact selection safe under a separate storage allowance. Historical exploration there retains the live object and saved text; disappeared sources answer `:gone` (`:485`). F01 cannot silently install another raw admission path into error recording.

The O(B) claim at `:325` also conflates presentation with storage. Exact serialization of an N-sized supported value has at least O(N) work; bounded presentation does not erase that cost. Finally, today's AI path is not wholly safe: the design itself confirms composite-key HTML traversal and full printed ordering. “The AI face is already bounded” does not justify claiming early producer rows close F.

**Smallest fix:** remove `prepare-result`'s complete-HTML detour through the already chosen shared-selection owner. Explicitly settle which error evidence must survive its subject: reuse an existing admitted durable source when available; keep the live subject and exact shown text; report unavailable/gone cuts honestly. If durable bounded snapshots are required, use the render owner's safe storage selection and preserve its omission semantics. Audit every reader of `data-blob` before changing its representation. Do not promise lazy-tail non-realization and complete arbitrary-value round-trip simultaneously.

Split the regression expectations within the same behavior suite: no unsafe realization across error/AI/HTML views; exact round-trip for supported retained finite artifacts; explicit unavailable evidence beyond storage allowance; exact saved shown bytes. Measure selection and durable storage separately, including allocation/memory. The 2,113 ms old HTML measurement does not measure the proposed artifact implementation.

### 7. Runtime task helpers cannot simply be imported by the bootstrap scripts

**Design:** `:79–85`, `:129`, `:363–365`, `:399`.

`script/seon/dev/dependency_digest.clj:7–13` intentionally requires only core/JDK-accessible namespaces. `dev_cache.clj:208–221` loads it on tools.deps' tool classpath, where checkout `src` and project dependencies are not acquired. Replacing its command runner with `seon.cluster.process/run-process!` introduces `seon.error.refusal` and `babashka.process` (`src/seon/cluster/process.clj:8–10`) into the mechanism that establishes the dependency cache. `seon.await` additionally requires core.async, test.check and schema (`src/seon/await.clj:3–6`). The operator runs on the separate babashka/script path. Their compatibility cannot be inferred from main-JVM source.

**Smallest fix:** keep the pre-classpath operation on its existing JDK Process/Future/stream ownership seam, consuming and joining all tasks there. Use the existing richer process owner only where it is actually available. If sharing requires relocating a small dependency-free primitive, name that relocation, the removed copies, and both caller classpaths before pricing it. Do not add a runtime framework to bootstrap. Make process-owner A07/G05 repair a dependency of callers migrating to it; otherwise those migrations merely redirect the bug. Future implementation proof needs both script/tool-classpath and runtime entrances, not a main-JVM unit test alone.

### 8. Detached panic must retain the failing execution owner, and B/C need producer-complete migrations

**Design:** `:113–127`, `:136–147`, `:168–186`, `:267`.

Moving `proc-failed!`'s body is a reasonable deletion, but its boolean is meaningful only because `var-process` immediately calls `stop-panicked-graph!` (`src/seon/flow.clj:291–303`). A detached callback can ignore the same boolean exactly as earlier callers ignored `record!`'s tuple. The A09/A10/task callback conversions must name how the failed graph becomes unavailable. “Stops through routing” for E06 is not an installed call chain: the current uncaught handler only records/prints (`src/seon/cluster/boot.clj:342–381`). Delivering a message to root is not stopping the graph that faulted.

B's per-site rules are useful, but making `record!` private cannot make an arbitrary catch-and-default impossible. B12 groups a failed Malli walk with reader parsing (`src/seon/render/ns.clj:134–151` in the audited/design revision); a narrow reader catch does not repair the walk's partial-result fallback. B08 must match Malli's actual no-generator error data, not any generator exception. The plausible B rows require their declared rejection proof, rather than silently becoming confirmed bugs to sweep.

C's typed error must reach every consumer. `src/seon/turn.clj:2793` treats any `opening-deferred?` result as truthy, whereas `:2913–2918` explicitly propagates an error. The migration names only the latter. The writer deduplicates identity/notifications, but still increments occurrence count at `src/seon/error.clj:1405`: repeated calls to `fault!` are not “record once” just because `opening` suppresses a new message. Name the existing fact/basis that prevents rediscovery from recounting the same exhausted obligation.

**Smallest fix:** keep panic disposition at the existing graph/task owner with explicit carried identity and an actual stop/unavailable action; the fault recorder should not grow a routing supervisor. For B, enumerate each transformed producer's typed rejection versus machinery failure and preserve the whole cause. For C, update all decision consumers and use held durable obligation evidence to avoid duplicate disposition. Add stalled-notification, repeated-derivation, missing-dial, pre-exhausted and ordinary-idle cases, plus C04 batch deadline and C05/C06 process-refusal cases. A single missing-episode test does not kill all six paths.

### 9. Rebase and reconcile the migration ledger; several deletions and conflict claims are not real yet

**Design:** `:75–86`, `:177`, `:270`, `:311–317`, `:389–412`.

Spot-checks against the review pin:

| Claim | Source evidence | Required correction |
|---|---|---|
| A01 unread callback failure; G02 premature settlement | `src/seon/flow.clj:398–424`, `:444–473`, `:628–646` | Confirmed; preserve these as acceptance traces. |
| D01 refusal returned outside Malli | `src/seon/instrument.clj:715–726` | Confirmed; delete this wrapper, not Malli's instrumentation. |
| C deletes about 370 lines in `flow.clj` | Named interval `:742–1108` is 367 physical lines, including retained `monitor-graph` `:902–931` (30 lines). | Gross deletion from that interval is at most 337, before adding the proposed 15-line reader; net at most 322 there. Identify any additional deleted ranges. |
| A04 deletes fan-out, also included in C03 | A table counts `flow.clj:1055–1081` and agent join; C table deletes the enclosing family and same join. | Count each range once. Similar B01/C03 and E09/§9.2 overlap needs explicit ownership, not class totals. |
| E09 has 31 callback references | At the pin, literal `:seon.flow/commit-fault!` occurs on 19 lines in `src/seon/sci/eval.clj`, plus 2 schema lines. | State the search/count unit; symbol references and physical deleted lines differ. Do not call 31 verified migration sites. |
| F20 removes nil print bindings at `mcp.clj:440` | After `1ae366d7f`, `script/seon/dev/mcp.clj:42–47` passes string bytes through and JSON-encodes other content; `:440` is a different function. | Retire the obsolete deletion. Audit remaining local diagnostic JSON/`str` separately; do not restore a bridge printer or require a live JVM to report transport failure. |
| A09 and C remove a fan-out thread | C retains a new per-graph error reader. | Do not claim the source graph loses its reader outright. Count removal of the separate committer/forwarders minus retained readers. |
| Six regression files, about 40 lines each | Named suites are A/G, B/E, C, D and the existing F/render suite. | Name actual files, reused fixtures and deleted obsolete tests. The current list is not six identified files. |

The arithmetic of rounded class estimates is approximately −1,005/+284, so “about −1,000/+285” is reasonable arithmetic. It is **not** a verified unique-line budget: duplicated deletions, schemas, old test callers, startup handling, script constraints and real terminal accounting remain unpriced.

The order table also has internal omissions: B05 belongs in `sci/kernel.clj` but is absent from D's assigned classes; F21's `plan.clj` producer is absent from lane 8; E08's `call_preparation.clj`/`test.clj` paths are not explicitly assigned with its turn-only row; A13's `operator_move_test.clj` is missing from A's file summary though present later. Lane 9 waits for G0 but is then advertised as immediately startable with G0. E0's new helper is also used by G0/A while G0 says it waits for nothing. Declare compatible preparation versus functioning activation precisely.

The ownership ledger read during review additionally holds the MCP bridge, render/program files, schema-shape/instrument boundaries, boot, test and SCI/turn work. The design knows some old holders but omits newer overlapping ones. “Check against render lanes” is insufficient: render slices explicitly touch `cluster.clj`, `web.clj`, `print.cljc`, `repl.clj`, `render/ns.clj`, `bootstrap.clj`, test-report owners and MCP. Even two non-overlapping hunks need one file owner.

**Smallest fix:** rebase the migration table to one commit; give each source/schema/test range one owning cut; mark superseded, confirmed and probe-needed paths separately. Recompute unique gross additions/deletions per cut, including required contracts and test retirement. Root grants files from the current ledger just before launch. Retain `flow.clj` sequencing, but add the atomic activation in finding 4 and the boot/process dependencies above. Remove stale “can start together” claims. Every activation must load without a future lane's repair.

## Class-by-class judgment and regression sufficiency

| Class | Does the proposed owner make it impossible? | Smallest retained approach and required class proof |
|---|---|---|
| A | **No.** Callback/rejection/pre-start cancellation and detached panic remain underspecified. | Existing submission settlement plus library submission and real exit observation. Lifecycle table must cover all terminal owners, not only one successful adapter; include cleanup and late returned-refusal outcomes. |
| B | **No.** Recorder visibility cannot enforce producer catch behavior. | Delete unjustified catches, match actual library rejection shapes, preserve cause data at the common boundary. Exercise parse, generator, unknown-observation and cleanup cases; prove all listed bypasses retired. |
| C | **No.** The retained Flow channel can lose; the regression omits batch/process exhaustion and stalled delivery. | Persist/dispose before parking through the writer; eliminate lossy authoritative transport; derive already-handled obligations from facts. Verify no repeated occurrence/delivery for one obligation and no false idle/green. |
| D | **Yes for D01's narrow return escape after the catch deletion; not for classification or all D paths yet.** | Malli's throwing report is sufficient enforcement. Preserve exact callee alternatives and errors before success projection. Test direct and nested armed calls under both policies, including validator faults. |
| E | **No.** Atomic recipient evidence is sound, but private-record activation, provenance and panic ownership are incomplete. | One writer decides durable recipient from core-fault data, one execution owner applies panic. Add existing-open notification, missing/removed steward/root, storage failure, nested receipt and failing-graph status cases. A recipient string alone is not delivery proof. |
| F | **No.** Shared render selection is right; raw artifact admission conflicts with it. | Reuse the floor's safe tree/cuts for both faces; keep producer data unexpanded. Separate presentation and storage guarantees and test the agent's shared read/eval/record/print path through its views. |
| G | **No.** The real stop caller releases, timed Delay deref is unavailable, and task-finally is not thread exit. | Existing owner claims, thread/executor joins and ProcessHandle exit identity. Test ignored interruption, pre-start cancellation, contended acquisition, stop refusal retention, late fault and subprocess survivor ownership. |

These should be behavior suites with parameter rows and canonical injected worlds, real SCI and armed contracts. Seven class headings need not mean seven new harnesses. A test that directly calls a new helper and asserts that helper's fields does not establish that MCP, tests and web use the same agent mechanism. Preserve the independent views as adapters; the invariant is the agent's task/context/branch and one recorded evaluation/value. The design currently names no cross-view proof for D/E's responsibility boundary. Add one shared entrance case and thin adapter equivalence checks, rather than three new models.

## Opinions on questions 9.1–9.4

- **9.1: option 1, with an explicit root ruling and proof of no paid turn.** Ordinary durable message delivery to steward/root meets AGENTS. Payment/provider admission remains a separate existing authority; verify the effective root configuration on each target branch rather than assuming `:no-provider`. Occurrence and ordinary message must commit together; a missing recipient panics under both modes. Option 2 weakens the current law and is not an implementation interpretation. Option 3 is unnecessary if default creation/configuration can supply the required recipient without making the early diagnostic REPL unavailable.
- **9.2: option 1 for authored mistakes.** Return them in that agent's ordinary evaluation/opening and preserve its normal shown history. Delete the duplicate acquisition writer for that subset. Do not return installed-program acquisition/compiler/configuration failures as agent mistakes merely because they occur while acquiring rows. Classify the two existing vectors at their producer; core failures use `fault!`. Option 3 for every refusal would misassign responsibility; a new recipient key for a second mistake-history writer is not justified.
- **9.3: reject option 1.** “Unrealistic load” cannot prove no loss. Prefer the smallest dependency-seam improvement described in finding 1, with local publication/pin evidence and duplicate retirement. Option 2 needs shutdown ordering as well as buffering, so its five-line estimate is unsupported. Option 3 conflicts with the stated no-PR rule and is unnecessary.
- **9.4: retain custody and refuse release, but the claimed existing guarantee is false.** Fix `cluster.boot/stop!` at `:499` in the same G02 cut, then prove the late handler cannot use a released connection or overlap a restart. A timeout is a failed observation, never permission to release resources or reset default.

## Review evidence and limits

The most useful dependency seams are already present: Malli's throwing report; Flow's public `futurize`, proc lifecycle and supplied executors; Clojure's distinction between `Delay` and `IBlockingDeref`; and `ProcessHandle.onExit` composed by the existing process owner. None supplies a lossless fault disposition or cancellation-safe ownership merely by being called. The missing application work is explicit data and terminal ownership between those seams.

The Flow skill was read for lifecycle semantics. Its statement that Flow stop is not a join is verified against `flow/impl.clj:174`; its older source coordinates and commit-key context prose are not adopted as current authority. AGENTS' content-valid reuse and agent-perspective laws govern. The read-only assignment permits only this review file, so no skill, plan, source, issue index or ownership-ledger correction was made.

The reported live counts, promise nanoseconds and rendering timings in the first pass were not reproduced. Counting signatures versus notification identities alone does not establish per-fault delivery without the writer's identity/opening relationship. Full before/after lifecycle timing, memory, publication, adoption, reload, arming, test and browser evidence remain implementation obligations. Unavailable evidence is not a pass. No foreign breakage blocked this source review and no recovery was attempted.

Changed path: this document only. Source delta **0**; test delta **0**. No runtime/test timing or memory claim. The review recommends in-place revision of the existing design, with root rulings, before implementation launches; it introduces no competing implementation plan.

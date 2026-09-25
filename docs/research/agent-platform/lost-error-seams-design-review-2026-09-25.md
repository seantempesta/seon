---
type: review
status: REVISE
created: 2026-09-25
scope: Round 3 (78b922ea4), disposition of 431a9b4ee's nine corrections and M1–M4
---
# Lost-error seams design review — 2026-09-25

**Verdict: REVISE.** Round 3 closes the stop-caller, bootstrap and safe-storage design corrections, removes the activation cycle, and adopts root's conservative responsibility policy. It does not yet preserve both asynchronous failures, establish current delivery, or make MCP observations reproducible. Revise those existing sections before their production cuts; no new framework is needed.

Reviewed all 693 lines of [round 3](lost-error-seams-design-2026-09-25.md), commit **`78b922ea4`**, against the prior review **`431a9b4ee`** and AGENTS.md. `design:N` below means `docs/research/agent-platform/lost-error-seams-design-2026-09-25.md:N`. Source references are at inspected HEAD **`54c550e4cbe86b5de98112143900a79f4b9bfc3b`**. The design's bytes match its named commit. Of the principal source owners checked against the design's `431a9b4ee` pin, only `instrument.clj` changed: its schema-shape replacement does not repair the evidence path discussed below.

This is design/source evidence only. No source edits, tests, runtime evaluations, default stop/reset, other-lane messages, worktrees or provider calls were performed. This review is the only edited file.

## Disposition of every previous correction

| Previous item | Closed? | Round-3 disposition |
|---|---|---|
| 1. Lossless Flow reporting | **No** | The WritePort avoids the channel-buffer exception trap and preserves the report map. Its whole recording/diagnostic path still lacks the claimed finite disposition guarantee; see 1 below. |
| 2. Exactly-once task disposition and real exit | **No** | Direct terminal disposal, a thread handle and executor join answer the principal ownership gaps. Suppressing the callback exception does not persist it; terminal-failure containment also misses non-runnable entrants; see 2. |
| 3. Stop caller and timed Delay deref | **Yes, design** | `design:558–567` retains the real `boot/stop!` caller's resources on timeout and gives the operation a promise carrying either outcome. Executor exit remains a separate proof. |
| 4. Loadable preparation and activation | **Partly; still open** | `design:366–367`, `:378–395`, `:612–613` remove the cycle and retain the transaction function's return contract. The committed-recipient query can count an obsolete opening, and R4 still omits an attributed-core-fault routing branch; see 3. |
| 5. Malli enforcement and stored-call responsibility | **Partly; still open** | The three evidence cases and §9.6 ruling close the policy correction. The classifier's concrete input/schema path remains incomplete; see 4. |
| 6. Unsafe artifact admission and durability | **Yes, design** | `design:517–531` includes retained evidence in the same safe-selection migration, corrects the inline/storage distinction, and explicitly leaves F incomplete until that conversion. No independent artifact codec is proposed. |
| 7. Bootstrap classpaths | **Yes, design** | `design:146–152`, `:570–595` keep scripts on JDK primitives, require outcome consumption and preserve survivor ownership. The prior acceptance stands. |
| 8. Detached panic and complete B/C migration | **Partly; still open** | The read/walk split, both opening consumers, generator-with-cause case and explicit execution owners are answered. Adding a wake to the old occurrence key does not yet establish wake-scoped idempotence; terminal containment also needs item 2; see 5. |
| 9. Inventory, costs and ownership | **Partly; still open** | P1/R1/R2 test grants, T's plan producer and M's bridge retirements are added. Missing schema/producer grants and an unproved query complexity remain; see 6 and M3. |
| M1. Reuse and retire existing owners | **Yes, design** | `design:424–433`, `:483–489` reuse `occurred-since`, retire the bridge cursor/second evaluation/extra printing, and convert shared history. |
| M2. Database, interval and ordinary observation | **No** | Cluster custody and explicit bounds improve the design, but a saved integer basis is not a saved fault observation over no-history attributes; see M2 below. |
| M3. Query semantics, delivery and counts | **No** | Full-database joins and cumulative-count labels are correct. Current-opening evidence, historical reproducibility, unresolved filtering and the indexed-range cost claim remain wrong or unproved. |
| M4. Common continuation and error outcome | **Yes, design** | `design:473–482` uses the shared source/path/offset continuation and gives MCP the pipeline's outcome to encode. Its input observation still depends on M2/M3; this is not an implementation pass. |

## 1. Keep the WritePort seam; bound and contain its actual work

**Location:** `design:51–75`.

The replacement port is a plausible, smaller composition. Flow passes `:outs` through its SPI and calls `>!!` for reports (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:155–162`, `:313–319`). A completed box satisfies that synchronous caller (`…/core/async.clj:200–211`). This removes the rejected channel-buffer exception-handler trap. The full report as ex-data with the original throwable as cause is also an answer to the prior report-loss finding.

However, the box returns **after** synchronous disposition, not “at once.” `db/transact!`'s deadline cannot bound work before the transaction. `fault/record!` calls `error/prepare` and `error/evidence-stage` before its writer (`src/seon/fault.clj:97–104`, `:126–131`); the design itself acknowledges unsafe realization in that preparation until F (`design:529`). The emergency `floor`/`prn` is also before graph stop (`design:64`). A catch handles a throw, not a stuck realization or output stream. “Ping unknown” correctly describes a stall but does not prove a bounded terminal disposition.

**Smallest fix:** keep P1 as the stated investigation. Name the bound and owner for the whole fault path, including preparation, and make its production dependency on safe admission explicit wherever that is needed. Request graph stop before fallible emergency presentation, with remaining diagnostics best effort; do not require that presentation to finish before the stop request. Narrow the port's guarantee to the actual synchronous `>!!` use or honor Handler activity/commit under its lock (`…/impl/protocols.clj:25–29`). Do not generalize an uncommitted callback adapter into an arbitrary channel. The existing six probes remain appropriate but unavailable.

## 2. Terminal ownership is corrected, but two failures are not yet retained

**Location:** `design:106–133`, `:143`.

The important correction is accepted: `io-terminal!` now owns the fault before removing the submission, regardless of completion-port liveness; a completion event carries accounting only. The explicit `::stop!`, recorded thread and executor join remove the previous boolean/stop mismatch and cancellation-as-exit assumption. Best-effort interruption plus retained ownership until actual termination is a sound design.

The new “one fault carries both” claim is false for the current producer. `error.refusal/chain` consumes `Throwable->map`'s `:via` and walks only `ex-cause` (`src/seon/error/refusal.clj:75–89`). Raw throwable admission also uses `Throwable->map` (`src/seon/sci/admit.clj:295–297`). Clojure 1.12.5's `clojure/core_print.clj:473–507` (read from the pinned Maven jar) follows only `.getCause`; neither path reads `.getSuppressed`. Thus `.addSuppressed` alone keeps the callback/release exception on a live object, not in the durable error chain. A body panic that already carries a receipt is worse: `fault!` immediately rethrows it (`src/seon/fault.clj:194`), so adding a new suppressed callback error cannot update its already-recorded evidence. The callback fault disappears after restart.

The row promising that a failure inside `io-terminal!` is caught by “the runnable's outermost catch” also misses callers outside that runnable: capacity rejection (`src/seon/flow.clj:426–432`), executor rejection (`:467–473`) and queued-stop settlement (`:633–644`). Their disposal/cleanup failures need the same execution ownership; the `::stop!` currently proposed only at execution claim is not established for a submission refused before that claim.

**Smallest fix:** retain one terminal CAS, but feed both failures as declared data through the existing error owner, or record each distinct failure through that owner. Reuse an existing receipt for the body without recounting it, and dispose of a new callback/release fault separately. Put containment at the terminal owner used by every entrant, with its world and stop action available before rejection. Reserve emergency-only handling for a failure to record/contain, rather than substituting stderr for recordable core faults. Add the receipt-bearing-body-plus-callback case and rejection/queued-stop cleanup failures to the existing lifecycle rows. No new task helper is needed.

## 3. Delivery evidence must identify this opening, and root fallback must cover attribution

**Location:** `design:379–395`, also `:464–468`.

P2 now correctly reads transaction evidence rather than asking a Datahike transaction function to return an arbitrary receipt. P2 → R1/R1t/R2/R3 → R4 is acyclic, with R2 before R3 for their shared file. Those corrections are closed.

The proposed query joins only signature → message → recipient. `message-tx` derives identity from **signature, recipient and opening** (`src/seon/error.clj:1313–1326`); `opening` distinguishes first occurrence, an unresolved opening, and a recurrence after resolution (`:1293–1311`). A message from a **previous resolved opening** still matches P2's query. For example: a signature was sent to an attributed agent, is resolved, then reopens when neither steward nor root is available. The old agent/message can still exist, so the query reports delivery and `:record` passes despite no notification for the reopened fault. Reusing a message for the same still-open fault is valid; reusing any historical message for its signature is not.

R4 also says to delete `interrupted?` and the `:your-run` branch but does not remove the remaining `(nil? agent-id)` condition on root fallback (`src/seon/error.clj:1441`). After those specified deletions, an attributed core fault with no steward still reaches no one. Defaulting `escalate-to` to root does not change that conditional.

**Smallest fix:** derive the receipt from committed evidence of the writer-selected **current opening and recipient**, per required target. Reuse an old notification only within that opening. Name the R4 replacement decision explicitly: present steward, otherwise present root, otherwise committed-but-undeliverable panic, independent of agent attribution. Use the same evidence in M. Add resolved/reopened-with-old-message and attributed-fault-without-steward rows. This is an existing writer/query correction, not a new delivery registry.

## 4. The responsibility policy is ruled; finish its explicit evidence path

**Location:** `design:315–335`, `:612`.

The table now honors missing/stale/mixed analysis as ambiguous, and avoids comparing a retained cluster to today's default. Root's policy is accepted. Keep Malli's throwing `-instrument-f` report (`reference-code/malli/src/malli/core.cljc:2202–2231`) and remove Seon's outer refusal-returning catch. No dynamic stack classifier or per-call marker is requested.

Three concrete data-path corrections remain:

1. `:seon.fn/root` is already a **string source root**, declared at `resources/seon/schemas/seon.fn.edn:14` and used by `:index-request` at `:185`. It cannot be repurposed as a symbol or form-analysis identity (`design:329`). Reuse `:seon.fn/sym` for a symbol and a separately declared, existing analysis value/identity where available; do not change the meaning of the existing key.
2. Capturing a profiling digest is not the same as putting it in a refusal. `wrap-interpreted` removes `:seon.program/definition-digest` from the arm request before the compiled wrapper (`src/seon/instrument.clj:477–489`); host `arm-var!` receives its digest in the outer profiling cell (`:779–808`). `compiled-wrapper` receives no digest (`:624–626`) and its report metadata holds only the marker and declared schema (`:710–713`). The inner report cannot simply read the outer function's metadata. Name the arm-time value passed into both report closures and give R3 the instrument owner, contracts and regressions needed for that change.
3. Adding only `db` and root does not supply `fault!`'s connection-bearing environment/context (`src/seon/fault.clj:190–204`). `failure-value` currently accepts a failure request, throwable and diagnostic record (`src/seon/sci/kernel.clj:531–536`); its declared request has neither new input (`resources/seon/schemas/seon.sci.kernel.edn:7–11`). Carry the existing evaluation world explicitly, or return the classification to the existing boundary that owns that world. Declare and grant the request/schema edits. No ambient connection lookup.

Also retain uncertainty for a validator-engine defect that happens while checking an authored schema: schema provenance alone does not establish that the authored schema is invalid (`design:321`). Preserve the report/cause and require positive rejection evidence before that classification; otherwise use the ruled ambiguity case. The regression table should distinguish those two failures.

## 5. Writer-side idempotence needs an obligation key stable across attempts

**Location:** `design:232–243`.

Moving the decision into `commit-call` closes the timestamp and stale-pre-read flaws. The generator-with-cause correction is also source-backed by `malli/generator.cljc:472–482`; no further change to that reviewed distinction is requested.

But folding the wake id into the **existing** occurrence identity preserves `turn-id`, or `process-id` when there is no turn (`src/seon/error.clj:1537–1544`). The same unanswered wake seen in another turn or after process restart then has a different occurrence id, so `old` is absent and the “exactly one” promise fails. The current two-derivations-from-one-basis regression would not expose that change of attempt identity.

**Smallest fix:** spell out the obligation-bearing identity separately within the existing `seon.id` construction: include the stable obligation and required signature/origin/agent scope, excluding changing attempt/process members for that case. Keep the old key for ordinary recurrence. Make the serialized writer check that identity and preserve read refusal. Add the same wake across a new turn/re-arm/process, plus a different wake that must record independently. This changes one identity producer, not another deduplication mechanism.

## 6. Reconcile grants and costs after these fixes

**Location:** `design:597–663`.

The corrected test ownership and M retirements are useful. The table is still not “exact file grants”:

- R3 proposes instrument report changes without `instrument.clj`, and request/diagnostic additions without their schema owners (item 4).
- P1/L add `::stop!`, `::thread` and `::disposed` while their schema column is zero; declare these members at their producers, identifying the existing owning schema resource in the grant.
- R4's delivery regressions are assigned to `cluster/fault_message_test.clj` in E.4, but R4 does not own that file and the sequence ends at R1. Sequence its R4 use as well.
- B12 names `render/ns.clj`'s parsing/walk changes (`design:203`), but B-platform does not own the file and F producers' “Lands” column claims only F. Assign B12 explicitly to that existing cut or adjust the whole-file sequence.
- M's unknown error, outcome, settled observation and reply/settlement schema changes need concrete owners, beyond “the MCP reply pipeline owner's files.” Do this at the S2 integration boundary before authorizing that cut; do not create a parallel pipeline plan.

The source rows currently total **−721/+381, net −340**; their rounded source total is coherent. Schema rows total **−70**. These are estimates, not measured diffs; revise them for the missing changes above. “And their tests” remains a grant to fill from the ledger at launch, not permission to edit a held suite.

## M2/M3. Record the observation, not only an upper basis

**Locations:** `design:437–470`, `:486–487`.

Using the cluster connection explicitly and sharing one observation between faces are correct directions. Three remaining problems prevent closing M.

**Historical reproducibility.** The only new recorded observation member specified is `fault-basis`; history later calls `occurred-since` on `as-of`. Both `last-at` and `count` are no-history (`resources/seon/schemas/seon.error.occurrence.edn:3–5`). Datahike excludes such attributes from temporal retention (`reference-code/datahike/src/datahike/db/transaction.cljc:448`, `:547–566`); `AsOfDB` filters the origin database's temporal search (`…/db.cljc:663–668`), it does not reopen an old commit. Once an occurrence advances past the saved upper bound, the older `last-at`/count may be absent from that as-of query. `occurred-since` requires both (`src/seon/error.clj:1587`, `:1593`), so the original observation can disappear. The same flaw already exists in `branch-errors`; its presence is not proof of the historical guarantee at `design:430–431`.

**Smallest fix:** compute the ordinary fault observation once from the captured cluster value before settlement, retain that value through the existing live-result mechanism, and save its exact shown text through the ordinary evaluation recording path. History reads that text rather than reconstructing it from today's no-history rows. Use the render owner's `:gone` behavior if the live observation is gone; no new durable artifact is necessary. Keep the cluster identity/lineage and lower/upper bases as evidence for the next observation, refusing or returning unknown across incomparable branches. Name the settlement writer and its failure ordering: only a successfully recorded observation may advance the lower bound. The existing unknown-on-first/compacted rule is accepted.

**Window membership and delivery.** `occurred-since` explicitly filters resolved signatures (`src/seon/error.clj:1595–1597`). Thus its result is not the promised exact set of signatures touched in the window (`design:460`): a fault that occurs and resolves before settlement is omitted. Delivery has the old-opening problem in item 3. The cluster copy is also conditional on an available home connection (`src/seon/fault.clj:142–145`); “copies every core fault” (`design:452`) needs that world precondition or an explicit missing-copy failure, not a universal installed claim.

**Smallest fix:** make window semantics explicit at the existing query owner. For the stated “faults in this interaction” guarantee, include touched signatures even when later resolved, carrying resolution as data; do not treat the existing unresolved filter as equivalent. Use current-opening delivery evidence from item 3, and make missing cluster evidence typed unknown/error. Add occurrence-then-resolution-before-settlement and recurrence-after-an-earlier-saved-observation cases. No separate query owner or bridge cursor.

**Cost.** The predicate `[(> ?tx ?since)]` does not establish an indexed transaction-range scan. Datahike's primary lookup for a bound attribute and transaction filters the AEVT range (`reference-code/datahike/src/datahike/db/search.cljc:152–153`); transaction is not the leading index key. `occurred-since` also groups and sorts returned signatures (`src/seon/error.clj:1608–1611`).

**Smallest fix:** remove the claimed O(window occurrences) bound at `design:469–470`. State and measure the existing attribute scan, joins and sort cost on the actual query plan. Reuse a proved dependency seam if the measurement requires a smaller read; do not claim an index from the Datalog predicate or add speculative indexing machinery. No query or timing was run in this review.

## Start boundary and verification limits

The previous start boundary remains: **S-scripts**, **S-process** after its holder releases, and **P0 D-delete** after the instrument grant can proceed under their existing accepted scope. **P1/P2 remain investigations only**, as round 3 itself says. Do not authorize the affected production fault-port, lifecycle, routing/classification or M cuts from this review. Safe-storage direction is accepted, with its owning render slice still responsible for implementation and proof.

Read the shared instructions, full round-3 design and prior review, relevant first-party producers/callers/contracts, the data-oriented Clojure, Flow and Datahike skills, turn PRD §§13–15, and the shared render continuation/storage section. Dependency pins inspected: core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`, Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`. The Flow skill's stale commit-key context claim is not adopted; current AGENTS content-valid reuse governs. Its file is outside this lane's write grant.

No runtime installation, publication, adoption, arming, latency, allocation, memory or healthy default is asserted. The design's historical probes were not rerun. No foreign breakage blocked this source review; concurrent changes and the dirty `reference-code/http-kit` submodule were preserved. Source/test delta **0/0**. Only this review changes; all regressions above are future implementation obligations, not tests executed here.

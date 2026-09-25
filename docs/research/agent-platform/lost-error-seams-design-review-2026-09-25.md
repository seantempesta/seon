---
type: review
status: ACCEPT-AFTER-FIXES
created: 2026-09-25
scope: Round 4 (3b3173a05), prior open items, reference-the-world rule, and fix-unbounded-capture-opus overlap
---
# Lost-error seams design review — 2026-09-25

**Verdict: ACCEPT-AFTER-FIXES.** The composition is acceptable: one fault writer, disposition at each execution owner, dependency-owned joins, and ordinary saved fault observations. Five corrections below remain before the affected production cuts. In particular, the proposed delivery query cannot identify the opening it claims to verify, and the new reference fields do not yet cross the fault writer. These require changes at existing owners, not another registry, task framework or printer.

Reviewed all 852 lines of [round 4](lost-error-seams-design-2026-09-25.md), commit **`3b3173a05`**, against the preceding review **`dc0597005`**, AGENTS.md and the owner ruling recorded by **`c29e5f040`**. `design:N` means `docs/research/agent-platform/lost-error-seams-design-2026-09-25.md:N`. Source inspections began at **`259dd1375`** and continued through **`278b22317`**; the cited error, fault, Flow, instrument and kernel owners are unchanged from the design's **`e6066f091`** pin. The design file matches its named commit.

This is a design/source review, not installation evidence. No source edits, tests, runtime evaluations, stop/reset, other-lane messages, worktrees or provider calls were performed. Only this review is edited.

## Disposition of the preceding review

| Previous item | Round-4 disposition |
|---|---|
| 1. Flow reporting and whole-path bound | **Partly closed.** The synchronous-only WritePort, Handler commit, safe-admission dependencies and stop-before-emergency direction are accepted (`design:54–102`). The actual stop operation and grant still need correction 2. P1 remains an investigation until its prerequisites and probes pass. |
| 2. Task disposition and actual exit | **Partly closed.** Separate faults replace suppression, an existing body receipt is not recounted, and containment covers task/rejection/queued-stop/capacity entrants (`design:158–241`). Reference persistence and emergency ordering need corrections 2–3. |
| 3. Stop caller and Delay deref | **Closed as design.** The real stop caller retains resources on timeout; promise outcome and executor exit remain separate (`design:707–745`). No installed claim. |
| 4. Preparation, activation and delivery | **Partly closed.** Ordering is acyclic, and R4 now removes the attribution condition on root fallback (`design:508–517`). Current-opening evidence remains incorrect; correction 1. |
| 5. Responsibility evidence path | **Closed as design.** Existing symbol/analysis identities, an arm-time digest passed into the report, pure classification, and fault disposal at the environment-owning boundary answer the prior gaps (`design:399–468`). The ruled ambiguity policy is retained, including validator-engine uncertainty. Reach cost and exercised provenance still require implementation proof. |
| 6. Safe retained evidence | **Closed as direction.** F includes retained admission and waits for shared safe selection (`design:669–683`). The actual migration owner/order needs correction 4. This does not assert safe storage is installed. |
| 7. Bootstrap classpaths | **Prior acceptance stands.** Script landings are reported, not independently tested here. |
| 8. Detached panic and obligation identity | **Identity correction closed as design.** The wake-bearing key excludes turn/process attempt identity (`design:311–328`), with cross-attempt cases. Carrying the obligation through the real fault entrance needs correction 3; stop behavior needs correction 2. |
| 9. Grants and costs | **Partly closed.** R3 schemas/instrument ownership, P1/L members, B12 and the fault-message suite sequence are added. Capture-lane overlap and the stop/reference producer grants remain in corrections 2–4. Estimates are hypotheses. |
| M1. Reuse and retirement | **Closed as direction.** One query owner and the bridge/history retirements remain (`design:598–624`). |
| M2. Saved observation | **Closed as design, integration pending.** Capture once, save exact shown text, retain only a live result handle, and advance from a committed upper bound (`design:561–597`) answer the no-history flaw. Correction 5 reconciles the consuming plan. |
| M3. Query semantics and cost | **Partly closed.** Resolved-in-window signatures, cumulative-count labeling, explicit missing-world unknown and withdrawal of O(window) are correct (`design:598–616`). Delivery inherits correction 1. |
| M4. Continuation and outcome | **Prior acceptance stands.** The common render path and pipeline outcome remain (`design:617–619`); implementation depends on M2/M3 and the shared render work. |

## 1. Preserve the writer-selected opening; do not call `opening` on db-after as a current-opening lookup

**Locations:** `design:491–498`, `design:607–609`.

`src/seon/error.clj:1293–1311` does not return the currently open notification's identity. It answers whether the *next occurrence starts an opening*: 0 before the first occurrence, a resolution/digest token before reopening, and **nil while already open**. After a successful first write, an occurrence exists and the signature is unresolved, so `(opening db-after signature)` is nil. The same is true after a reopening has been written. The proposed receipt therefore cannot validate the new message, let alone an existing message in that opening.

There is a second exact mismatch: `message-tx` at `src/seon/error.clj:1319` hashes `[signature recipient]` for opening 0, and adds the opening only when nonzero. `design:495` always hashes `[signature recipient opening]`. Even obtaining 0 would not produce the first message's id.

Do not substitute a pre-read outside the writer. Datahike's writer can batch transactions and replaces each report's `:db-after` with the batch's committed database (`reference-code/datahike/src/datahike/writer.cljc:229–264`). A receipt must identify this transaction's selected opening, not an opening changed by a later transaction in the same batch.

**Smallest fix:** keep the decision in `commit-call`; retain an explicit reference to its selected opening/message in committed evidence and consume that reference for the receipt and M. Reuse existing transaction/message evidence where it proves the selection; if no such evidence survives, declare the missing opening reference on the existing error/occurrence owner. Share the exact message-id derivation, including the first-opening special case, with `message-tx`. Preserve transaction-data return semantics. Do not infer the token from the latest message or a fresh `opening` call. Name the schema grant and cost of the chosen evidence path in P2.

Required future cases: first delivery; recurrence in the same opening; resolved then reopened with only the old message; a delivered opening resolved before observation; and another resolution/reopening in the same writer batch. These cases must distinguish undelivered from delivered without recounting a fault.

## 2. Make stop precede fallible presentation at every terminal, and correct the claimed nonblocking seam

**Locations:** `design:73–88`, `design:158–159`, `design:182–185`, `design:207`, `design:760`.

A.2 still specifies emergency `prn` **then** `::stop!` when recording throws (`design:183`). A blocked or throwing stderr operation can prevent stop, contradicting the ordering correctly requested in §0.1. The enclosing `try` does not bound a blocked output stream. Existing `proc-failed!` also prints and flushes before returning its panic boolean (`src/seon/flow.clj:220–232`), so the reference to that owner cannot silently promise the corrected ordering.

The claimed nonblocking stop is not established by the dependency. `stop-panicked-graph!` first uses `put!`, but then calls `flow/stop` (`src/seon/flow.clj:234–246`). Flow's stop takes a `ReentrantLock` and calls `send-command`, which uses blocking `>!!` (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:71–75`, `:174–183`). Its control channel has capacity 10 (`:99`). A nonblocking `poll!` of the graph reference does not make the subsequent stop nonblocking. Saturated control delivery is outside the six stated fault probes.

Also, §0.1 moves `fault!`'s config read ahead of recording, but P1 grants only `flow.clj` and its schema/test (`design:760`). `fault!` currently reads the policy at `src/seon/fault.clj:213–216`, after recording. The proposed ownership and production guarantee disagree.

**Smallest fix:** apply stop-before-emergency consistently in the existing terminal owner, including its recorder-failure path and proc wrapper; diagnostics must be independently best effort. Separate a proved nonblocking stop *request* from graph stop/actual exit, and name the dependency path and execution owner for the latter. Until a saturated-control/contended-stop probe proves otherwise, remove the nonblocking/bounded-stop claim and keep P1 investigatory. Allocate the policy-read change to the existing `fault.clj` sequence, or explicitly pass its decision to the Flow owner; do not add a second config read or scheduler. Add throwing/stalled emergency-output and saturated-control cases to the existing lifecycle proof rows.

## 3. References must reach durable error data, not merely `fault!`'s context argument

**Locations:** `design:177–181`, `design:219`, `design:313–328`.

The reference-the-world rule is correctly applied to submission/effect/body-occurrence identities and the stable wake obligation. But the current fault entrance does not persist arbitrary context members. `fault!` reads attribution/schema from context, passes the original failure into `record!`, and uses layer/operation for the receipt (`src/seon/fault.clj:201–223`). It does not forward submission id, effect id, body occurrence or obligation. Its context schema declares none of these members (`resources/seon/schemas/seon.fault.edn:10–17`). An open map accepting extra keys is not a producer declaring or persisting them.

Thus recording two exceptions can preserve both causes while losing the promised relationship; setting an obligation only on the caller's context cannot reach `recording`/`commit-call`'s new idempotence rule. L and T also have no grant to change this fault boundary, and the design has not named an alternative data path.

**Smallest fix:** choose one explicit route through the existing owner. Either construct a declared reference-bearing diagnostic at the caller and pass it as the failure source, or have P2 forward specifically declared reference members through `fault!` into the existing recording request. Keep the obligation in the request field that controls writer identity and keep failure-link references in the declared stored error/occurrence shape. Do not copy a work map, context or environment, and do not indiscriminately merge context into a fault. Name the keys, schemas and grants before L/T/R1t use them. Extend the existing body-plus-callback and repeated-wake cases to pull the committed references and compare them, including the home-branch copy.

## 4. Reconcile the expanded capture-lane hold and assign F01 once

**Locations:** `design:128–139`, `design:506`, `design:669–705`, `design:761–774`, `design:788–798`, `design:829–831`.

The division of responsibilities is right: fix world capture at its producers and identity admission; use the render owner's safe selection for arbitrary values; migrate outside producers only after that work. A world identity reference is not itself proof that arbitrary lazy values or throwable preparation are safe.

The concrete hold list only reflects the capture lane's first row. `tmp/orchestrator/file-ownership.md:446` expands its hold to `resources/seon/schemas/seon.sci.eval.edn`, `seon.env.edn`, `seon.schema.edn`, `src/seon/schema.clj`, `src/seon/sci/admit.clj`, and `src/seon/test.clj`, besides the already listed kernel/error/render files and tests. These overlap **R2**, **B-platform**, **T**, and render slice 2(i). The shared-file sequence lists only error and kernel. Root rechecking grants is necessary, but a predecessor graph must not label these other edits independent of the root-cause cut.

F01 is assigned inconsistently: `design:678–680` puts retained admission and F01 inside safe-selection slice 2(i); `design:704–705` says recording stops HTML production in P2; §9.7 offers P2 the deletion if the capture lane did not do it. F07/F22 similarly still say P2 in the migration table after being moved conditionally elsewhere. This leaves the changed producer, contracts/readers and safety activation split across alternative owners.

**Smallest fix:** include the expanded holds in the dependencies for R2/B-platform/T/render and sequence their shared schemas/tests. Record one root allocation for F01 and F07/F22 at handoff, based on the capture lane's actual diff. Book each deletion once; explicitly place retained admission's conversion in the shared safe-selection activation. If the HTML deletion can land earlier, name the surviving evidence/reader contract that makes that smaller cut coherent and update the cost/order rows to match. Otherwise keep F01 together in slice 2(i). The capture landing is **PARTIAL** and describes its identity edge as designed, not written; neither that note nor a released hold proves the safety dependency installed.

## 5. Carry M's corrected observation contract into its consuming plan

**Locations:** `design:553–555`, `design:573–589`, `design:642–645`, `design:774`; `docs/research/agent-platform/one-repl-language-2026-09-25.md:329`, `:484`, `:898`.

Saving the observation once as ordinary shown text closes the substantive no-history issue. However, the introductory installed claim still says `branch-errors`'s as-of window cannot be rewritten by later recurrences (`design:555`), while `design:568–572` correctly explains why that claim is false.

The current consuming pipeline design, revision 5 (`60e060b52`), still specifies `fault-basis` and basis-pair history reconstruction at its P3m boundary. Round 4 instead specifies `fault-window` references plus saved observation text. Deferring the integration to that plan without identifying this mismatch can restore the rejected implementation. The consumer now names `turn/settle!` and `evaluation-facts` as its recording owners; the grant can be made concrete there rather than remaining “the reply-assembly owner's files.”

**Smallest fix:** delete the false installed history guarantee in M's introduction. At the existing P3m handoff, replace the stale `fault-basis`/recomputation rows with the single captured observation, exact saved text, live handle, cluster/commit/lower/upper references, and commit-only advancement specified here. Name the settlement source, schema and adapter test files in that existing cut, with one estimate for the shared work. This review does not authorize editing the other lane's plan. M remains unstartable until its owner reconciles those rows and correction 1 supplies valid delivery evidence.

## Start boundary, evidence and limits

S-process and P0 retain their previously accepted scope **subject to their actual holder releases and root grants**. P1 and P2 remain investigations, not approved production changes. The accepted design directions above do not waive the named dependencies, adopted-schema proof, contract arming, regressions, latency/allocation measurements or root's integration proof. No lane may infer a grant from this review.

Read the prior review, full round-4 design, relevant open-items rulings and capture landing/ledger, data-oriented Clojure, Flow and Datahike skills, turn PRD §§13–15, relevant first-party producers/callers/contracts, and shared render storage/continuation and pipeline handoff sections. Dependency pins inspected: core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`, Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, Datahike `4157916b5a1b8d80d7f809c889bce1b098b1d75d`. Malli's `-instrument-f` throwing report remains the enforcement seam (`reference-code/malli/src/malli/core.cljc:2202–2231`). The Flow skill's stale commit-id context-cache claim is not adopted; AGENTS.md's content-valid reuse rule governs. That skill is outside this lane's write grant.

Source/test delta **0/0**. The source planning rows sum to **−710/+367, net −343**, not a measured diff. Runtime latency, allocation, memory, publication, adoption, arming and default health are **not measured or asserted**. All cases requested above are future implementation obligations; none was executed here. The design's “default is being nuked” is a dated statement, not a runtime observation from this review. Foreign in-flight edits and the dirty http-kit submodule were preserved; no foreign breakage blocked this static review.

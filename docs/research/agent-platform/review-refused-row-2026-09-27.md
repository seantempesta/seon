---
type: design-review
date: 2026-09-27
status: complete source review; no implementation
verdict: REVISE
reviewed-commit: 81e6f12ee1b009cc0e3433178384e6811befc01b
scope: refused-row-at-its-form proposal; source evidence only
---

# Review: a refused row at its form

**REVISE.** The proposal reuses the writer's validation implementation, but a trial of one row is not the verdict on the run's final transaction. It can reject a change that a later form repairs, omit run-dependent checks, and use a database value predating the form's own writes. Replacing the returned value with that trial refusal also contradicts the required outcome semantics. Retain the useful idea of reporting a diagnostic at its known source; do not equate source attribution, execution outcome, and publication acceptance.

The current flow is SCI evaluation → candidate gate → working-database overlay for the next form → one ordered settlement transaction → installation or reverse restoration. The smallest next cut is the common outcome required by audit §7 row 2, preserving values and completed prefixes, without changing admission or transaction boundaries.

All Seon source and test citations below refer to **81e6f12ee**. The inspected files were unchanged through concurrent HEAD `c7a0a94e7`; unrelated working-tree changes were not used as evidence. Dependency citations refer to Datahike `67b00071e020a7ccf310985d01b28128cdadec34`, SCI `743d1c4317d95af9d1af74928392b913c0d1b91d`, and Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`. This is source evidence, not proof of installed behavior. Counterexamples below are deductions from the writer, not executed probes.

## 1. Outcome semantics must precede this change

`CLAUDE.md:29–31` requires the evaluated value to survive later failures, with diagnostics beside it. `CLAUDE.md:227–233` requires the run's parsed and evaluated forms to settle in order in one transaction. `CLAUDE.md:204–213` requires producer-declared, flat error values with the whole cause chain. Audit §7 row 2 expressly requires executed/unrun distinction, original value, diagnostics, and completed-prefix preservation without changing admission (`docs/research/agent-platform/review-eval-path-audit-2026-09-26.md:216–223`; explanation at `:145–149`).

The proposed call does not satisfy this contract. `sci.eval/refuse-install` restores the context, drops the row and bindings, then calls `shown-result` with the refusal and changes the outcome to `:error` (`src/seon/sci/eval.clj:4865–4881`). The outer loop subsequently binds that replacement value to the result handle (`src/seon/turn.clj:5031–5032`). For an executed `ns-unmap`, nil is the actual value; the later publication diagnostic does not make the form return an error instead. An existing constructor and declared error union do not establish that this is the right outcome transformation.

**Required fix:** use the common outcome to keep the executed value, its handle, output, namespace/ordinal evidence, and execution status, with the original writer diagnostic and publication status beside them. A pre-entry refusal remains distinguishable from an executed body whose program change was refused. Route recording and delivery through the error owner; do not manufacture a second error shape or infer one from message text. Compile the outcome contract through the existing schema owner and Malli's `validator`/`explainer` (`reference-code/malli/src/malli/core.cljc:2672–2702`).

The existing host assertion expecting the second form's shown value to contain “surviving referrers” conflicts with this requirement (`test/seon/cluster/host_test.clj:412–424`). It needs to assert nil as the returned value and the refusal as a diagnostic. The batch-preservation test is still useful, but directly invokes `settle-batch-refusal!` and does not exercise the proposed path (`test/seon/turn_test.clj:2342–2376`). Keeping it unchanged does not establish correctness of the new outcome transformation.

## 2. A per-row trial is not final-settlement equivalence

### Same-transaction repairs are a concrete counterexample

`settled-value` derives one row's operations and passes them through `db/with` (`src/seon/turn.clj:1407–1415`). Each call therefore completes a separate speculative transaction, including final-report validation. Actual settlement emits ordered transaction-function calls into **one** transaction (`:1072–1083`), and `receipt-settle-call` applies each row before its receipt facts (`:1754–1759`). Datahike transaction functions see earlier operations (`reference-code/datahike/src/datahike/db/transaction.cljc:1171–1172`); its final validator runs after all operations (`:1224–1234,1277–1295`). Ordered operations do not imply that every intermediate database must satisfy final invariants.

Consider existing function H and caller C, with C referencing H. Two forms remove H, then remove C. At the writer boundary, the combined deletion has no surviving C and can pass this deletion invariant. The first per-row trial refuses H while C remains; restoring and dropping H then allows C's deletion, leaving H behind. The proposal changes the accepted final program even on an unchanged connection. The same issue arises when a later caller rewrite repairs a removed reference or a changed arity.

This is directly supported by `row-tx` emitting retractions without a surviving-referrer check (`src/seon/turn.clj:1279–1310`), and the final writer checking removed identities against the **final** database (`src/seon/db.clj:4150–4208,4249–4269,4460`). The arity refusal explicitly allows callers and declarations to be repaired in the same transaction (`:4483–4503`). The governing requirement is `CLAUDE.md:175–178`. Other earlier admission checks may independently refuse particular authored examples; that does not make these two writer inputs equivalent.

**Required fix:** preserve the writer's final-transaction repair semantics. Do not drop a row solely because an incomplete prefix fails a final invariant. If a separate per-form admission rule is intended, name it as a semantic change requiring an owner decision; it cannot be presented as an earlier observation of the existing settlement verdict. Do not move final invariants into per-row gates or waive the final validator.

### The trial omits material settlement inputs

| Difference | Source evidence | Consequence |
|---|---|---|
| Trial passes only `{:seon.program/row row}` to `row-tx`; final settlement passes the full receipt request. | `src/seon/turn.clj:1414,1756` | The trial omits the run identity. `declaration-diverged-since-open?` returns false without it, while the real request checks opening content and whether this run wrote the declaration (`:1233–1272,1346–1355`). An already-existing conflict can therefore escape the trial; no new concurrent write is needed. Adding the id alone is insufficient when the trial also omits the run-opening and receipt provenance needed by those checks. |
| The working value is captured before the body. | `src/seon/turn.clj:4936–4942,4961–4972`; `src/seon/db.clj:5014–5076` | The body can commit through its connection. Immediately trialling against the old working value misses that write, whereas today's next iteration reads the connection again. A form's own write can change a referrer, configuration, or declaration. |
| Final settlement transforms and adds data beyond program rows. | `src/seon/turn.clj:3702–3729,3809–3864` | Final namespace digests account for later namespace rows; settlement also includes receipt fences, read evidence, fault windows, staged data, deliveries, close, and plan settlement. A row-only trial cannot predict all their refusals. |
| Final validation depends on the attempted/effective transaction and its resulting projection. | `src/seon/db.clj:4427–4462,4532–4564` | Sequential trial successes are not a certificate for the combined report, its affected entities, or its final declarations. |

**Required fix:** describe each trial's exact basis, transaction, request/provenance inputs, and limited guarantee. Reconcile connection movement caused by the form as well as external movement; an explicit snapshot must retain its declared semantics (`src/seon/turn.clj:4888–4893`). Rebuilding an accepted prefix on a new head must surface refusals and reconcile the corresponding SCI state; merely deleting `with-settled` leaves that behavior unspecified. Re-evaluate neither bodies nor effects. Keep final writer validation and the whole-run failure path for all unpredicted failures, not only races; remove the unsupported claim that this path is rare.

## 3. Restoration helps only under a defined continuation policy

Restoring a conclusively rejected change **before** the next form executes is preferable to trying to erase its use afterward. The existing restoration owner retains prior roots, metadata and Var identity, re-interns removed definitions, and restores held program state (`src/seon/sci/eval.clj:702–770,830–862`). It can make a following lookup see the previous definition; an absent previous definition must remain absent. Both SCI lookup and database lookup need to describe that same retained program.

This is a conditional guarantee, not the proposal's general consistency proof:

- If later forms already ran using a subsequently rejected definition, restoring that definition does not undo their results, closures, atom mutations, database writes, or I/O. The restoration owner's explicit limit is `src/seon/sci/eval.clj:712–720`. Such results remain actual observations; do not publish their program changes as though they ran against the restored world, and do not replay the forms.
- Restoration is to a capture, not an isolated patch independent of when it is applied. A rebase failure on an earlier accepted row cannot be handled by calling that old restoration in the middle of a newer context without accounting for the intervening changes (`:843–862`).
- Restoration can fail or refuse after program reacquisition, leaving execution unavailable (`:801–828`). `refuse-install` throws on that result (`:4876–4877`). The outer `phase` currently encloses the whole evaluation fold; an escaping failure is settled at `first-ordinal` instead of returning its accumulated prefix (`src/seon/turn.clj:5167–5205`). Adding this call before establishing row 2 introduces another route through that known gap.

**Required fix:** specify the continuation rule for immediate rejection, an earlier row failing after a rebase, final refusal after later uses, and restoration failure. Preserve completed outcomes and mark genuinely unrun forms accurately. Keep the existing unavailable-context diagnosis and whole cause chain; do not continue ordinary evaluation in a context whose restoration failed. Prove that restoration leaves earlier accepted roots and private state intact and that subsequent acquisition cannot reinstall a dropped row.

Correct the note's current-behavior claim as well. `with-settled` does silently retain the old database on trial refusal (`src/seon/turn.clj:1417–1423`), so a within-run mismatch is possible. However, the final refused-batch arm already calls `restore-evaluations!`, last form first (`:5084–5101,5240–5243`). The claim that SCI necessarily keeps the unmap until the next acquisition (`landing/refused-row-at-its-form-opus-2026-09-27.md:28–31,67–68`) omits this path. Restoration may fail, but that requires its own evidence.

## 4. Authority, cost, and smaller alternatives

**Authority:** `db/with` uses `write-error`, `prepared-write`, Datahike's transaction evaluator, and `write-refusal` (`src/seon/db.clj:4859–4873`), sharing those owners with real transaction submission (`:4785–4815,4856–4857`). This is reuse of the writer's logic, not a second handwritten validator. Nevertheless, treating its verdict on a different transaction and basis as the final writer decision is a new admission policy. `CLAUDE.md:167–168` still requires the committing authority to validate mutable premises. Keep the canonical diagnostic and its basis; a speculative pass is not publication evidence.

**Cost:** on an unchanged basis, today's loop applies every pending row before a following form (`src/seon/turn.clj:4932–4942`). Moving that work immediately after evaluation adds one trial only when the final executed form carries a row; if a non-row form follows the last row, today's loop already trialled it. Non-row forms add no row trial. This supports a narrow operation-count claim, not a measured latency claim. A single-`defn` run goes from zero speculative row trials to one, plus the unchanged final writer work.

Let W(r, D) be the writer's row derivation, transaction application, projection and affected-value validation cost for row r on D. With no connection movement the proposal pays the sum of these costs over all attempted row trials, including refusals, plus settlement. A moved connection can require reapplying the accepted prefix; repeated movement can make row applications quadratic in the number of rows. The basis identity and accepted prefix cannot both disappear while retaining that behavior. The current scan collecting rows on every form also remains unless explicitly removed. No additional cache, validator, or replay loop is justified by this review.

The proposal's one-`defn` benchmark is necessary but insufficient. A later implementation must compare parent/change for no-row runs, a terminal declaration, several declarations, deletion with referrers, namespace/contract changes, and a form that writes the connection. Record trial count, armed owner timings, whole-run time and memory separately; enforce the existing 20%/50 ms regression rule. No timings or memory were measured here. A full final-batch trial and a per-row trial are not “the same cost” without evidence: they touch different reports and projection boundaries.

Exactly three alternatives describe the tradeoff:

| Alternative | Guarantee | Cost and sacrifice |
|---|---|---|
| **1. Complete audit row 2 first — recommended.** Keep settlement authority and restoration, establish the common outcome and prefix preservation. | Executed values and diagnostics retain their meaning; no new rejection of same-transaction repairs. | No new speculative writer pass. Requires outcome/schema/caller work already assigned to row 2; defers selective publication and leaves the working-overlay defect explicitly open for its own design. |
| 2. Make independent per-form validity a new admission constraint. | A rejected form is restored before later forms run, if basis reconciliation and restoration succeed; final publication still revalidates. | Approximately one additional terminal-row trial in the stable case, plus rebase and outcome work. Sacrifices repairs spanning forms and changes authored-program behavior; requires an explicit owner ruling and is not accepted under the current same-transaction rule. |
| 3. Preserve repairable intermediate program changes until the complete transaction is judged. | Preserves final-transaction repair semantics and records later uses as executions in a provisional world. | Requires an explicit coherent SCI/database working-world design; no verified small replacement seam or line estimate is established here. Final refusal must restore the affected program without replay or claiming effects were undone. Defers immediate selective rejection. A complete-batch preflight alone does not repair earlier observations and duplicates final work. |

The proposed approximately 15-added/12-deleted-line estimate covers none of the outcome, provenance, rebase, or continuation obligations above. Withdraw it as an estimate of the complete fix. Do not guess an offending ordinal by retrying rows after a batch refusal; retain the writer's actual diagnostic and only attribute a form when its provenance establishes that attribution.

## 5. Required regression set for the revised design

These are future requirements, not tests run by this review. Use canonical branch fixtures, real SCI and armed contracts, with one regression per behavior class and variations within it. Positively establish fixture definitions and inspect receipts, live result handles, SCI calls and stored rows through the returned settlement basis.

1. **Outcome and totality:** an executed deletion returning nil, a false result, a returned error value, a thrown body, and a pre-entry refusal remain distinguishable. Writer/gate/render/record diagnostics preserve the returned value and handle. A later restoration or diagnostic-recording failure preserves all completed ordinals; unrun forms are not reported as executed. This is the audit row 2 acceptance condition.
2. **Final-transaction repairs:** remove H then C, and repair a surviving caller after deleting or changing its callee; include arity and renderer/schema repair cases. The same complete ordered transaction must retain the writer's existing acceptance/refusal semantics. Inspect both the removed and surviving identities; do not accept a dropped first change as equivalent success.
3. **Ordered working-world consistency:** accepted declaration then use; rejected redefinition then use of the old root; rejected new definition then unresolved lookup; deletion then use of the restored definition under any approved immediate-rejection rule. Check SCI and database reads in the same run and the next run, with held Var identity, metadata, unbound/nil roots, projection and arming. Extend `a-run-reads-its-earlier-declarations` (`test/seon/cluster/host_test.clj:306–321`) without preserving its value-replacement expectation.
4. **Writes and changing bases:** a form makes one durable data write and returns a program change; following forms observe that write exactly once, even if publication refuses. Cover connection movement between forms and between trial/settlement, an earlier row becoming invalid on rebase, and conflict already present relative to the run's opening basis. Include repeated definitions by the same run and pending-open submissions so provenance is exercised, not omitted.
5. **Complete settlement and fallback:** a later namespace row changes an earlier definition's digest; separately, a row trial passes but receipt/close or final report refuses. All actual results survive, no uncommitted program row is installed, restoration runs in the correct order, one terminal is recorded, and the next run works. Retain the batch test's original-value assertion and add an entrance-level refusal after actual evaluation rather than relying only on its direct helper call.
6. **Restoration limits and failure:** later use of a provisionally accepted definition followed by final refusal preserves the observed result and one-time effects while removing uncommitted program changes. A real restoration/reacquisition failure produces the declared unavailable-context diagnostic with its cause chain and preserves the completed prefix. Include immediate rejection followed by final batch refusal to verify repeated restoration does not corrupt prior state.

The three-form host example alone proves neither selective publication nor within-run restoration: its surrounding forms are arithmetic and its definition check occurs in a later run. Add accepted program changes on both sides of a conclusively attributable refusal if selective publication remains part of the revised design.

## Review limits and delivery

No implementation, tests, runtime probes, restarts, reloads, publication, timings, or memory measurements were performed. Existing test bodies were read, not declared green. Unrelated in-flight changes did not prevent this source review; no foreign load failure was investigated or used to stop work. Only this review file was authored. Source/test line delta: zero. The requested commit contains only this document; the commit id is reported with delivery rather than embedded in itself.

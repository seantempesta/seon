---
type: review
status: changes-required
date: 2026-10-08
lane: redo-review-sol
reviewed-head: b5171f52282a75bb30bd1d96c85e1a7ad4b9d1e3
---

# Joint review of finishing, recording, merge and entrants

**Verdicts: task-finish PATCH; pass-and-recording PATCH; merge-acceptance REDO; entrants REDO.
The ledger is not yet an executable combined plan.** Keep the serial cursor, captured observations, one recording
owner, proof-only work selection, repair branch and ordinary root tools. Fix the evidence/world contract before
implementing completion or auto-accept. The simplest composition is a serial agent proc calling the existing test
runner after recording, with explicit immutable tested inputs, then deriving completion. A new proof scheduler,
automatic repair-branch rebase and arbitrary migration execution inside the shared writer are not established needs.

Authority read in full: `review/owner-rulings.md`, then `CLAUDE.md`. All four redesigns, the entire ledger and both
specs were read. Below, F, RP, MA and EN mean the four files under
`docs/research/agent-platform/2026-10-08/redo/`; AL means `docs/seon/specs/agent-loop.md`; L means
`docs/seon/schedule/agent-loop-ledger.md`. Citations use the reviewed HEAD, not another lane's working changes.
These are design findings, not claims that the proposed APIs are installed.

## 1. Rulings

| Ruling | Joint assessment |
|---|---|
| Real REPL, serial read/eval, every earlier effect visible | RP:68-86 is the right algorithm. EN:147-150 explicitly preserves a stale same-pass check and violates the law; see D1. RP's same-pass live-world lookups need named implementation/proofs, not only its owner question. |
| One transaction records; it does not restrict agent effects | RP:76-86 and EN:62 correctly allow register/transact/check effects before RECORD. RP:17's “Nothing per form touches the database” is false as written; say “no per-form machinery recording.” Count attempts separately from durable commits. RP's storage-failure retry and EN's request aggregation need corrections, D2/D7. |
| Branches never refuse REPL-valid changes | RP:89-94 intends this, but no slice changes the checked writer that still refuses render, deletion and arity integrity. MA:65-67 correctly moves selection integrity to acceptance. Repair on S needs the same permissive branch policy. D2. |
| Lightweight forgiveness | RP:70-73 and :174 explicitly retain a linear scan and SCI reading, with 3 ms/8 MB targets. No new heavyweight parser is justified. DATA's additional structural scanning must reuse that scanner and have work-count evidence. AL:405-407 leaves code-mode repair unresolved; RP:73/:142 says none. Choose once. |
| Static prefix + own history + current-state block | AL:210-247 and L:214-238 specify it well; F:100-102 agrees about proof status. F's retained steps option contradicts its deletion rows unless chosen before launch. MA's “system evaluation … recorded in root's history” (MA:114-115) is excluded by AL:251 and L:221's model-only filter; EN:64 calls it `:outside`. D8. |
| Only task proofs during work | F:72-74 correctly uses identities, no changed set, and fixes pending-test widening. Current HEAD confirms the widening (H2). The trigger must include committed form effects, not just settlement rows; proof reuse must include actual data read (D3). |
| Reaching tests at merge | MA:101 and EN:63 imply this, and HEAD has `reaching-run` (H3). Preserve that path when F1 narrows named requests. State the exact union and run provenance; a repair's `my.test/check` must not silently substitute a proof-only run. D5. |
| Renders return data | F's block and help examples are data; RP:108 and EN:76 use the printer; merge.md:92-94 describes data-oriented renders. Good direction. No redesign should add an English report formatter or render-time proof execution. |
| One value printer, errors included | RP:75/:108 and EN:76 conform in intent. RP omits the post-record FINISH stage that AL:133-135 needs to deliver late errors. RP:97's purported findings schema cannot hold its own examples. D7. |
| Clojure terms, terse messages | “elision form” is correctly used in the updated context/ledger. F:115-123 adds several long instructional sentences; combine the automatic-proof teaching into one short data entry and two runnable end forms. “S” should be introduced as the merge branch in agent-facing data. Legacy “dig form” in CLAUDE.md is already scheduled for correction, L:151. |
| Lean core values, 2,000-token defect bound | AL:96-101 and L:330-343 state the bound and bulk exemptions. The four designs have no raw-return evidence for a large diff, aggregated request failures, migration tx-data or retained captured writes. Add producer measurements; do not truncate those values or label every large internal value bulk automatically. |
| 16 GB machine, 20-40 MB per ordinary pass, small retained memory | AL:79-94 and L:323-326 cover targets and shared incremental bases. RP:179 permits 60 MB for one defn while AL:80/L:40 say <30 MB: reconcile targets (higher transient allocation itself is allowed by the owner nuance). EN:29-32 records GB operations, so EN-0/EN-7 and the measured deleting work must be on the immediate path, not wait for the whole redesign. No many-agent retained-memory proof exists here. |
| Root is an agent | MA:55-57 and T16 are right. But changing root's custody to S without changing accept's destination sends acceptance to S; separate target, repair and task-home connections, D4. A cluster-wide review proc is additional running machinery, not “root is an agent” by itself. |
| Auto-accept | MA adds an execution owner without a provider, a real improvement. Its unknown-baseline rule can accept a newly red test; its writer migration accepts data the run never saw; its schema check is sampled. These are blockers, D5/D6. |
| Bail | F:120-123/F7 provides a required reason and wait, and L:311 names impossible-task proofs. Keep both meanings distinct; delete obsolete success verbs with callers. F:117's “Keep working only while a proof is red” must include missing/pending proofs and held merges requiring repair. A green branch is not task completion. |

## 2. Blocking findings and the required patches

### D1. A same-pass check intentionally tests the wrong program

EN:147-150: **“It reads the committed program, so it tests the previous pass … Recommend: leave it so.”**
That is a deliberate violation of serial REPL semantics, not an acceptable simplification. It also directly
contradicts L:183: **“A same-pass `my.test/check` reads the live world for names the pass defined.”**
RP:111-114 distinguishes ordinary committed DB reads from program tools; this does not authorize a program-check
tool to silently test old definitions.

Use the pass's existing captured definitions and current SCI world as explicit test-run inputs; do not introduce
a second evaluator or persist an early program snapshot merely to make the check work. A named proof must read
the just-redefined function and record the new definition digest in the same pass. If that input composition is not
yet designed, EN is not ready. Delete the recommendation to teach around the defect.

### D2. Permissive recording has no writer implementation slice

RP:89-91: **“It does not check program integrity between entities: callers, contract refs, render pairs or test
subjects.”** RP-1..RP-9 change batch derivation, arm/schema and settlement, but never claim the checked writer.
At HEAD `src/seon/db.clj:5283` checks render targets, :5303 checks deletion, and :5310-5324 refuses incompatible
call arities. The dependency's final-report hook still runs those checks after transaction expansion (H7).
Removing `removal-facts`' early refusal does not remove that final refusal. Retrying without program facts would
keep the value but silently fail to persist the REPL-valid final namespace; acquisition can undo it again.

Add the writer-policy change, schema contract and branch-vs-merge proof before RP-1's claimed behavior. Keep type
and explicit malformed-data validation; turn branch program-integrity failures into findings. Define which checks
apply to ordinary branch `transact!`, register!, recording, and root acceptance. A branch cannot invent a missing
Datahike entity id; preserve symbolic unresolved references as data until merge rather than fabricate entities.
Sweeping a required-ref owner also needs a named policy for transitive owners and a finding of every changed
declaration. It is not just changing “refuse” to “warn.”

RP:92 says **“a Seon defect or a storage failure … one second attempt.”** AL:313-315 says an unknown outcome gets
no retry and storage failure stops the graph. Adopt that distinction in RP-7 and EN-2. A definite, uncommitted
validation refusal can take the one documented degraded recording path; a timeout is not proof of noncommit.
AL:309-312 additionally specifies a third attempt that neither redesign specifies. Pick one exact failure table.

### D3. Proof freshness, trigger and task completion disagree about the tested world

F:89-93: **“The member forks from that branch, so the fact is present there.”** EN:100-103 instead preserves a
clean checkpoint plus program overlay. HEAD explicitly excludes the execution's agents and other data
(`src/seon/test.clj:1834-1848`, H1). An answer, source rows used to calculate it, or migrated target data does not
magically enter that clean world. `tested-branch` alone is an identity, not a snapshot or an answer input.

F:69-70 says **“reuse by reach digest makes unchanged proofs cost milliseconds.”** HEAD's `verified?` checks
program/test-input digests, not arbitrary DB read evidence (`src/seon/test.clj:1581-1592`). F6 adds one answer digest,
but never says how reuse selection or `verified?` consumes it; sources/data can change while the answer is unchanged.
F's trigger includes the agent's own data, so this is not merely a research-task corner case.

Declare one tested-world recipe: immutable program plus explicitly declared immutable answer/data inputs, each
included in reuse and completion evidence. Extend the existing evidence owner; do not bolt on a special
`member/answer` cache while other data reads stay invisible. Test changed answer, changed answer source and
unchanged program with changed data. The task-home completion writer must check assignment revision, tested
inputs and proof identities, then notify idempotently; “Q16” in F5 is no replacement for that concrete interface.

F:65-66 triggers on **“a pass whose settlement carried any write besides its own recording rows.”** RP makes
`answer!`, register! and transact! commit before settlement. Observe those writes in the pass's effect evidence;
they need not occur in its settlement tx-data. Avoid triggering another request on the proof request's own results
or proposal bookkeeping. Also, `seon.test/changes` is not a comparison with the current cluster head as F:81 claims:
it compares with `world-base`, the published test base (`src/seon/test.clj:1812-1817`).

F:82: **“code, no declaration differs … done.”** Branch data may still differ, and the target may move between
comparison and completion. Route code success through the root decision even for an empty program delta, or
define an atomic, evidence-checked empty-merge outcome. Do not use equality observed on a worker as root acceptance.

### D4. Root's repair custody and acceptance destination are conflated

MA:55-57 places the merge-task holder's evaluation in S. `merge.md:41-42` says **“accept-merge! lands on the
branch root's context holds.”** HEAD confirms that exact data flow: `src/my/program.clj:562-568` passes the same
context to source acceptance and task decision; source acceptance writes its connection at :1020-1022.
MA-5b changes custody before MA-14 even adds the target fact. As written root can accept onto S, then retire S,
while the target remains unchanged.

The merge task must carry the explicit target branch from proposal creation. Every tool receives the three
roles distinctly: repair execution S, target connection, task home. Root's model and provider-less policy use the
same values. Test target changes, S retirement, home outcome, and the worker's completion after an ordinary
root-agent repair. No hidden fallback to default. Declare idempotent recovery of the target-commit/home-outcome
crash window; F:83's “inside acceptance” cannot make two branch transactions atomic.

### D5. Auto-accept's run and baseline are insufficiently specified

MA:101: **“green on S, or red on both S and the head. A member that is unknown on the head counts as red.”**
Under that predicate, red S + unknown head becomes red on both and passes. Unknown must HOLD, not satisfy the
baseline-red exception. Use an explicit truth table: green S passes; red S requires current, positive evidence of
the same accepted pre-existing failure on the target; absent/stale/unknown target evidence holds. Also distinguish
an existing red test acquiring a different failure from an unchanged failure, or document the weaker policy.

Name the gate request exactly: current task proof identities UNION current tests reaching the final merged delta
UNION root's chosen additional identities. Store selected identities, tested program and input evidence in one
named run on S; accept validates that selection and evidence. Preserve HEAD's `:changed` reaching request while
F1 narrows work-only `:identities` requests (H2/H3). After take/drop/defn the final delta and selected reach can
change; re-running an old selection is not sufficient. MA says repair “marks the run stale” but its only cited
check watches program writes: changing `unresolved` or migration selection alone is not a program write.

MA:121-123 still configures `#{:no-dangling :no-new-red}`, although :no-dangling became mandatory integrity and
MA:97-101's new criteria are :integrity/:no-conflict/:no-new-red. `merge.md:128` still permits root to waive
dangling/schema integrity. Correct those sections, policy schema and competency expectations together.

### D6. Migration, final-state validation and S rebasing need a smaller design

MA:32-34: **“At accept, it runs inside the writer … rows added to the head after prepare … are migrated too.”**
The proof run never tested those new data inputs. Schema validity is not proof validity: a new row may satisfy
the schema yet violate a uniqueness, total, reconciliation or other task assertion. MA:82 checks only program
movement. MA:143 promises **“schema check walks at most 200 entities”**; HEAD's `stored-value-check` checks the
first 200 and returns `:more? true` (H6). Ignoring that flag would certify unchecked data. The generic writer validates
affected owning values, not every untouched row under every changed schema.

Arbitrary SCI migration code under the store's one writer also needs a CPU/deadline and actual-exit contract;
10,000 output datoms does not bound an infinite loop, input scan or huge intermediate result. A set of migration
symbols (MA:25) has no defined dependency/order. An ordinary function contract cannot prove purity. MA-8 still
asks its implementer to discover whether schema installation is atomic, then implement a fallback; this is design
research, not a fifteen-minute implementation slice. The assertion “Adding an attribute is harmless” (MA:38)
does not prove the partial-commit, retry or cleanup semantics.

Start with **explicit migration on a candidate built from a captured target**, run proofs on declared data inputs,
then accept a checked delta whose tested read basis still holds at the writer. If relevant inputs moved, hold and
prepare afresh. For a first safe implementation, a coarse target-basis guard is simpler (but causes more holds);
improve it to content/read evidence when measured churn warrants it. Complete schema validation must either finish
within its bound or return typed unknown/hold; never call a sample integrity. Keep large migrations as a separate
root task until their staged compatibility is specified. Do not execute arbitrary code twice under different inputs
and claim the earlier run proved the second result.

MA:68-70's automatic S rebase is not needed for correctness. Retain the original S as the value containing root's
repairs, prepare a new candidate from the current head, and offer ordinary diff/take operations. Do not retire S
until those repairs are safely represented and the task closes. Automatic rebase/retry adds conflict-resolution
provenance and another lifecycle; it does not eliminate the need to re-test. Prefer HOLD with the ordinary prepare
operation. Specify proposal revision vs prepared revision so a newer green worker proposal cannot silently replace
the branch commit root is repairing.

### D7. Recording order, row conservation and shapes need one shared contract

Use a single ordered contract: read/eval/print each form with observation open → analyze once → derive/fold
captured values → record once → FINISH late diagnostics through the value printer → proof/completion work.
RP:78-86 ends at RECORD; EN:45-46 says value returned after RECORD but omits FINISH. Neither says how a late
diagnostic is saved as exact shown text without re-rendering the original lazy value or adding another record tx.
Store the pre-record shown value and structured diagnostic facts; specify what historical replay can reproduce
when recording itself failed. Do not promise a persisted exact reply after a failed persistence operation.

RP:97 proposes **`[:set [:tuple :keyword :symbol :symbol]]`**. RP:103-104 immediately supplies two-element tuples;
RP:101 supplies a digest where the schema requires a symbol. Replace it with named variant maps or a correctly
dispatched union of actual tuples, and compile every case. F:127-131 similarly invents `:task/status` in examples
but calls it `:seon.task/status`; status schemas, persisted facts and derived values need exact names before launch.

EN:54 says a test member has **“none of its own”** rows; EN:75-79 calls it “a pass with no rows.” This contradicts
AL:154 and L:250's one member pass, and leaves its minted pass id, evaluation identity, source, shown text and
diagnostics unspecified in durable evidence. Saving a captured assertion result is not automatically saving the
member pass. Batch member recording data into the request's one transaction through the same row builder, keyed
by member pass id + ordinal; this deletes per-member commits without deleting evidence. Prove reconstruction and
error ownership, not merely zero writes to the member branch.

EN-6 moves request recording but names a separate `record-request`; EN-2 keeps `settle` as the one owner. Specify
that request aggregation supplies row data to that owner, rather than quietly retaining a second recorder. In
addition, moving admission from the writer to a process roster changes the law that admission/retirement are
writer-checked facts (CLAUDE.md:65-66). HEAD's `admit-run` does more than overlap prevention: current program,
custody, identical replay and reservations (`src/seon/test.clj:1185-1196`). Account for every check, store identity in
the roster key (branch names can repeat across stores), exception-before-body and late exit. This is not a two-line
transaction optimization.

### D8. Origins, no-provider root and external-effect crash semantics

MA's `:outside` policy pass is absent from the model-only history. Do not fix that by reviving system turns.
Keep ordinary outside evaluations and show the current held decision/run in the current-state block; let the root
model's own repair forms remain history. A provider-less root can consume proposals through its existing agent
request path. Any additional proc must listen before deriving existing open proposals, serialize root context
access, redrive unfinished decisions after restart, and await actual exit. MA-12 specifies only listening for new
rows, so a crash can strand an already-written proposal. MA:112's “target cluster's Flow graph” also needs
reconciliation with one graph per agent; it should not become a parallel policy execution system.

EN:54 says **“the overlay is gone; nothing was written”** on member crash, but EN:153 permits a real shell effect.
That external consequence survives even when its receipt lived only in the released overlay. RP:150-153 expects
durable effect receipts and interruption-stamps. Decide the real-effect policy explicitly: inject external effects
in ordinary tests as CLAUDE.md requires; exceptional real-effect integration tests keep durable receipts in an
explicit owner until actual exit, with idempotence/recovery, without restoring attempt agents/turns. One attribute
name is already consistent: use EN's `:seon.effect/pass` + `:seon.effect/form-ordinal` everywhere, including RP's
recovery, schemas, fixtures and readers. Do not retain the old ref-valued `:run` with a new string meaning.

### D9. Source fidelity is still a replay problem, not only a finding

RP:117-118 says edges use the captured alias row but **“Its digest uses the final row the recording stores”**;
RP-8:202 asks reacquisition to preserve both calls. A finding does not preserve a function compiled under alias A
when the stored source later reloads under alias B. Likewise RP:235's **“record it with its enclosing top-level
form as source”** for a closure defn does not authorize re-running an enclosing transaction, random value or
external effect during adoption. Name the declaration representation that preserves closed values/resolved names,
or honestly mark unpersistable runtime closures as live-only. Do not promise reproducible program entities by
replaying arbitrary surrounding forms. These two proofs need observable side-effect counts as well as return data.

## 3. Is the slice order acyclic, loadable and fifteen minutes per slice?

**No, not as a complete executable plan.** The enumerated arrows mostly have a topological order, but required
inputs are missing, several aliases are unresolved, and at least one hidden dependency reverses a listed arrow.
L:64-65 and :387-388 still say entrants have no redesign; EN is absent from its rows. “Ready” cannot mean
independently implementable with files such as “owners found,” contracts containing ellipses, or unspecified tests.

| Problem | Exact consequence and repair |
|---|---|
| F5 → F6 at F:172/L:268 | F5 must prove a green question completes, but F6 introduces the question kind and answer input/evidence it needs. Move answer schemas/world/reuse before outcome; split F6 if necessary. The semantic F5-needs-F6 edge closes a cycle in the advertised order. |
| F2/F3 duplicated by EN-7/EN-6 | Import EN and retire aliases once. F2's one overlay tx and EN-7's overlay checkpoint cache are not identical behavior; choose the measured change. Replace F3's timing-only proof with EN-6's tx-count data plus late-exit/replay tests. |
| RP-14 dependencies | EN:132-137 adds EN-8 and EN-5; L:125 still has only RP-10/S6-01/S6-02b. One settlement owner must land before deleting open/receipt machinery. |
| Errors cut on the supposedly off-path route | EN-8 → S5-05 → E-SREADS → E-CUT (EN:129; L:246/:198). L:407 calls that cut off the path. It is currently a hard prerequisite, with E-CUT unsplit. Either prove that dependency removable or put its concrete slices on the critical path. |
| RP-15a before fixture conversion | RP:153 retires the open/close families, but RP:209-210 and L:126-127 put fixture conversion after the recovery cut. Explicitly retain every still-called helper until its last fixture converts, then delete it; otherwise the 24 `turn/open-tx` callers make intermediate HEAD unloadable. |
| MA custody before explicit target | MA-5b needs the target/home contract from D4 before changing root's world, not MA-14 afterward. Acceptance run selection, migration metadata and freshness must land together with their consumers. |
| Hand-wavy “MA rows” dependency | L:267 cannot schedule F5 against an unspecified whole family. Name the task-home completion/acceptance integration slice; it is not every migration or T16 slice. |
| Source/write-back representation | RP-8/RP-9 need the D9 representation before their reacquisition/adoption proofs can pass. RP-1 also needs D2's writer change. Those dependencies are not listed. |

The shared-file claims must include tests and schemas, not just groups. `test.clj` is shared by F1/F2, EN4-7 and
MA2; `turn.clj` by RP7/10-15, EN8, F4 and MA5b; `turn/batch.clj` by RP1/5-9/12/17 and EN2/4; `sci/arm.clj` and
`seon.batch.edn` by RP3/4 and several EN/RP rows; `task.clj`, `my/program.clj`, `my/task.clj` and task schemas by
F5-8 and MA3/5/8/11/14; `cluster.clj` by graph, memory and merge work; `pass_probe.clj` by RP0/EN0/context rows.
EN correctly says its `test-request` edit must run between RP rows (EN:135-136); no “different hunk” exception.
L's group letters are aids, not proof of disjoint ownership.

Not every slice is plausibly fifteen minutes or has a data proof:

- RP0 and EN0 are bounded measurement slices, not implementation proofs. RP6 combines stage deletion, end-stage
  wiring and three semantic changes; RP11 replaces both model/MCP readers and removes a large caller closure;
  RP14/15 change boot recovery and many callers. Split by a loadable adapter migration, then last-caller deletion.
- F0 is a diagnosis with an owner hypothesis, not a specified fifteen-minute fix. F3 has only a performance row.
  F4 adds a concurrent proc and lifecycle; F5 adds proposal replacement, cross-home completion and question/no-op
  outcomes. F7 deletes three APIs across unspecified callers. Name the exact contract and behavior proof for each.
- MA3 combines candidate persistence, conflicts and retirement. MA8 contains a dependency experiment plus a new
  migration protocol and fallback. MA9 introduces writer validation and rebase. MA12 adds running machinery.
  MA16 says “profile … fix the owners” with no known owner. These are design/diagnosis assignments until bounded.
- EN1's unspecified fixtures, EN5's effect lifecycle, EN6's admission replacement/late-exit protocol and EN8's
  settlement-owner cut exceed a small slice without exact caller inventories. EN9 needs a proof that refused
  recording preserves member values AND their diagnostics/identity, not merely a search for retired names.
- Existing named tests can be reused when they reach the behavior. A namespace-green requirement, grep, timing
  target or `contracts-compile-test` alone does not prove a deletion's retained behavior. No new tests were added
  for this documentation review; the requested no-sources test is the landing check, not proof of these designs.

A viable dependency spine after decisions is: fix stale caller → measure/fix the GB owners and define tested-world
inputs → install exact row/error/effect schemas and branch writer policy → capture/analyze/record changes → route
all entrants to the same recorder and retire their old callers → proofs with correct reuse → ordinary end-stage
completion. In parallel, explicit merge target/home/S inputs → repair → exact gate selection → current-evidence
acceptance → policy → root-model competency. Order by dependencies and file claims, not the prose numbering.

## 4. Simpler alternatives

**Proofs:** first try running the small proof-only request in the existing serial agent proc's end stage after
RECORD and before the next model call. Use the existing runner's carried bound and actual-exit ownership. A timeout
delivers a pending/held status and retains the body's resources; it never permits overlapping execution of that body.
This removes the proof proc, dirty bit, channel, start signal and races between completion and another model pass.
Use stored evidence to derive any unfinished proof/completion work on restart. Test-run recording remains the
runner's effect, distinct from the model pass's one bookkeeping commit. Measure combined overhead honestly.
This changes AL:136-137/:291-296's nonwaiting mechanism; the owner law says proofs after work, not a mandatory new
proc. If model overlap is a measured requirement, keep one async owner, with finite bounds and immutable inputs;
F:144's bare `>!!` cannot promise <1 ms under backpressure.

**Merge:** keep one repair branch and ordinary diff/take/drop; hold on relevant target movement, with explicit
fresh preparation. No automatic S rebasing or retry loop until repeated conflicts establish the need. Bind an
immutable proposal revision when preparing; a worker may publish a newer revision without changing what an
existing review means. Delete `uncovered`; keep reaching-test execution and evidence. Reuse schema-declared
program families (`src/seon/program.cljc:493-529`), not MA:18-20's four-family whitelist.

**Recording:** aggregate row data through the existing record owner. Do not create a “no rows” test exception and
another run recorder to save commits. Keep the current live-body owner; demonstrate which writer checks migrate
before adding a competing roster. Use plain data variants for findings. Store current state nowhere except its
authoritative facts; render it once at the edge. Delete task steps and duplicate session-end verbs after callers
are converted.

## 5. HEAD verification and limits

Verification used `git show b5171f522:<path>`; no uncommitted source was loaded, no default JVM definitions were
made, no private agent branch or host process was created. Source evidence is sufficient for the claims below;
the design benchmarks were not rerun and are not presented as current performance proof.

| ID | Verified seam / result | Guarantee, inputs, recomputation and cost |
|---|---|---|
| H1 | `src/seon/test.clj:1834-1900` clean-request-handle | Published base + boot checkpoint + program overlay, then in-memory member store. Excludes worker answer/ordinary data. Checkpoint keyed by base/cluster/effective/process; overlay built per request. No promise that F's answer or MA's target data exists. |
| H2 | `src/seon/test.clj:849-884`, :1562-1592 | Named identities still incur observed-change work and pending widening. `verified?` compares stored input and reach digests; no answer/read-data digest. F1 addresses a real source defect but F6 does not yet specify reuse integration. |
| H3 | `src/seon/cluster.clj:2621-2636`; `cluster/source.clj:915-937` | Prepare passes delta/touched/proof identities to reaching-run, which uses `:named` plus `:changed`. This must survive proof-only work selection. Current test reach, not a whole-suite run. |
| H4 | `src/seon/cluster/source.clj:795-812`, :975-1022 | Writer checks only carried-row digests; prechecks watch S program and proof membership. `uncovered` is a real gate at :1014. No complete target-data freshness certification. |
| H5 | `src/my/program.clj:532-535`, :562-568; `src/seon/task.clj:655-676` | Prepare reads current branch via context, not an immutable proposal-commit input; accept writes that context. Proposals recur by task-key/commit. Replacement needs an explicit reviewed revision and separate target custody. |
| H6 | `src/seon/db.clj:4016-4075` | Stored-value-check is bounded to 200 plus a 201st detection read, returns `:more?`; uses Malli validator on the carried projection. It is a diagnostic sample, not proof all stored data passes. |
| H7 | `src/seon/db.clj:5268-5324`; Datahike `src/datahike/db/transaction.cljc:1225-1235`, :1286-1296 | Final-report validation runs once on final expanded transaction output; schema/render/deletion/arity checks survive batch rewrites. This is the upstream seam for atomic final checks, not evidence arbitrary migrations are safe. |
| H8 | `src/seon/turn.clj:5021`; `turn/batch.clj:729-765`; `turn/world.clj:70-75` | SCI path removes settle; settle remains the writer-stage owner for other requests. Test-world constructor currently supplies a requester settlement connection. EN correctly identifies duplicate ownership. |
| H9 | `src/seon/test.clj:1185-1199`, :2401/:2438-2486 | Admission is a writer reservation with program/custody/replay checks; commit-results and record-exit are separate calls. Removing it needs more than a transaction-count regression. |
| H10 | `resources/seon/schemas/seon.effect.edn:99-102`, :155/:212-223 | :run is a ref, :eval an optional ref and ordinal an integer; :pass is not installed at this HEAD. New value provenance needs schema and caller conversion together. |

Dependency pins from HEAD:

| Dependency | Gitlink |
|---|---|
| `reference-code/datahike` | `e5453d967b749794944dbdfe64b751519983ba59` |
| `reference-code/sci` | `ce0aa53b9bf35029e0bc27a962d799c196a37874` |
| `reference-code/malli` | `43c6dca8d2d8df3162b13725939954807be4a4ed` |

Malli's actual `validator` seam is `reference-code/malli/src/malli/core.cljc:2672-2678`: a compiled-schema-cached
pure predicate. It validates the value supplied; it cannot prove an unchecked collection tail or migration purity.
The captured Var/alias and closure replay risks require additional behavior proofs; this review did not claim to
reproduce F0's stale-caller bug on the newer HEAD. Its existing task is
`seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass`.

`bin/seon status` and MCP `runtime_status {:cluster "default"}` both answered with `:faulted`, basis 536871819,
6 open faults and 37 errored receipts. The running source is `4b4caf1421cc3db8e48069e6e406f03505d07ecc`, not
the reviewed HEAD. Reported heap-live 954,381,648 bytes, heap-max 10,997,465,088 bytes; this is observation,
not a many-agent memory benchmark. Existing health subjects include `my.program/accept-merge!` retirement and
`seon.render.ns/task-ai`; the GB check already has `seon.test/a-one-test-proof-check-allocates-a-gigabyte`.
No reset or recovery was attempted by this reviewer.

## 6. One consolidated owner-decision list

“CLAUDE” identifies a required law-text change, or a conditional change if the original proposal is chosen.
Already-ruled items should be corrections, not another request for authorization. Recommendations are this review's.
F numbers refer to §7 questions, RP to §8, MA to §8 and EN to §6.

| # / source questions | Recommendation and one-line reason | CLAUDE |
|---|---|---|
| 1. F1 — question vs code | Explicit task kind (`:question`), not branch contents: scratch helpers must not turn research into a code merge. | Clarify question-success exception to the general merge/proof completion text. |
| 2. F2 — steps | Delete the step API after converting callers: proofs and current task data already define the work. | No. |
| 3. F3 — newer proposal | One open merge task with immutable proposal revisions; an existing review keeps its revision until root selects a newer one: preserve repairs and evidence. | No; clarify owning merge spec. |
| 4. F4 — interim 400 ms proof | Allow a measured <400 ms intermediate CPU/wall result only with no GB allocation and named next deletion; do not treat it as final acceptance. | No. |
| 5. F5 — Var status | Put automatic proof status only in the current-state block: the owner already ruled it. | **Yes**, replace the reaching-test/Var-render example. |
| 6. RP1 — concurrent writer | Record the evaluating agent's final definition plus overwrite evidence on its branch; do not veto a valid REPL result. | No new policy; align any conflicting admission wording with the existing ruling. |
| 7. RP2 — required refs | Permit branch program experimentation, preserve unresolved meaning as data, and report deliberate sweeps; enforce complete integrity at merge. | **Yes**, scope required-ref deletion/integrity rules to accepted program; keep explicit malformed-data refusals. |
| 8. RP3 + EN3 — same-pass reads/check | Live-world program tools and checks; explicit DB reads see committed effects: reject EN's previous-pass test proposal. | No; already the REPL law. |
| 9. RP4 — diagnostics | Store Seon error variants as data with references, agent-value copies as shown text: one printer still owns display. | **Yes**, distinguish diagnostic facts from stored copies of arbitrary values. |
| 10. RP5 — lost model reply | Accept loss before RECORD, preserving durable effects and unanswered work: no pre-eval intent commit. | No; already recover-facts semantics. |
| 11. RP6 — DATA cursor | Read forms lazily at the live cursor; use the existing scanner for envelope spans: alias/reader changes must take effect serially. | No. |
| 12. RP7 — closure defn | Do not approve arbitrary enclosing-form replay; specify a reproducible declaration or mark runtime-only closure live-only: source text is not captured lexical state. | No; retain no-replay law. |
| 13. MA1 — migration declaration | Name an ordered migration selection on the merge task, by tested definition identity: changing data is an explicit root decision. | No, if program/data partition stays intact. |
| 14. MA2 — policy entry | Use root's ordinary serialized agent path, including without a provider; derive open proposals on start: one owner can do both policy and model repair. | No; **system turns must remain deleted**. |
| 15. MA3 — migration bound | Do not inherit the unrelated 10,000-datom diff cap as proof of safety; specify measured scan/output/time bounds and hold incomplete work. | No. |
| 16. MA4 — conflict initial row | Keep target's row and explicit unresolved identity on S: no silent acceptance of either side. | No. |
| 17. MA5 — uncovered gate | Delete it while preserving reaching-test execution and results: coverage counts are already ruled non-gating. | No; existing law. |
| 18. MA6 — head moved | Hold and offer fresh prepare, retaining repairs; omit automatic S rebase/retry initially: no hidden resolution lifecycle. | No. |
| 19. EN1 — member rows | Aggregate member pass rows into the request's transaction using the same builder: save commits without discarding source/result/error provenance. | No with this recommendation; EN's literal no-record exception **would require a change**. |
| 20. EN2 — pre-admission | Retain writer admission until a complete one-owner roster design preserves its checks; if replaced, explicitly approve volatile common-path admission and prove replay/late-exit cases. | **Yes if removed**, current law says admission/retirement are writer-checked facts. |
| 21. EN4 — effect attribute | Use new `:seon.effect/pass` plus ordinal everywhere: a ref changing to a value deserves a new name. | No. |
| 22. EN5 — real effects in tests | Inject external effects by default; real integration effects retain durable receipts under an explicit owner: discarded overlays cannot prove crash recovery. | No with this recommendation; accepting ephemeral receipts **would weaken durable-effect rules**. |
| 23. Added — proof proc vs inline | Try bounded proof work in the existing serial end stage first: delete dirty/start signals and concurrent completion races. | No mandatory new law; amend AL's nonwaiting mechanism and status example with #5. |
| 24. Added — tested data/world | One explicit immutable input/evidence contract shared by answer proofs, same-pass checks and merge: program digests alone cannot prove data-dependent work. | No; implements current positive-proof law. |
| 25. Added — merge tested basis | Hold when relevant tested inputs changed; complete schema validation or typed unknown: never auto-accept a sample or unseen migration input. | No. |
| 26. Added — policy unknowns | Unknown baseline evidence holds; explicitly decide whether changed failure signatures are new reds: unknown is not known pre-existing failure. | No. |
| 27. Added — no-change code success | Root records an evidence-checked empty-merge decision; do not complete merely from worker/head equality: root acceptance remains the authority. | No with recommendation; F's bypass needs an explicit exception. |
| 28. Added — context origins | Keep outside policy work out of model history and surface its current decision in the block: no revived system-history channel. | No; clarify AL/MA wording. |
| 29. Added — root target and home | Carry target independently from S and home, and an idempotent acceptance/completion receipt: custody must not redirect the merge. | No; implements explicit-world and recovery laws. |

## 7. Landing evidence

Only this review document is owned and changed. Source/test line delta: **0/0**. No production behavior,
publication, adoption, model run or browser paint is claimed. No existing test reds were observed by this lane;
the named check below is intentionally one var, not the whole namespace.

Foreign working-tree boundary at review start: `src/seon/sci/eval.clj`,
`test/seon/repl_differential_test.clj`, `tmp/test-data-assertions/proofs.edn`, untracked
`test/seon/stale_caller_probe_test.clj`, other untracked docs/src-clm content and an untracked-content status in
`reference-code/http-kit`. None was read as HEAD evidence, edited, staged, restored or committed.

Before commit, an isolated `git archive` of HEAD's `src script test .clj-kondo`, with only ignored analyzer
configuration linked, ran `clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,461 warnings,
4,749 ms** (4.779 s wall). Exit 2 reflected warnings, not errors. No lint cache was written. The warnings are a
pre-existing HEAD boundary, not a clean-warning claim. The disposable archive was removed.

Required landing command: `bin/lane commit redo-review-sol -m <review message plus requested footer> --test
seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings
docs/research/agent-platform/2026-10-08/review/redo-review-sol-redo-review.md`. The commit tool's output and final
handoff report its result; no amendment is used. The check proves documentation isolation/no-source contract
checking, not the proposed implementation behaviors.

---
type: review
status: changes-required
created: 2026-10-07
lane: plan-v3-review
---

# Plan v3, second review — plan-v3-review

**Revise before approval.** V3 substantially improves the design, but does not resolve or explicitly defer every
v2 finding. In particular, its unknown-settlement fallback contradicts the invariant it retains, the reader
deletion still precedes its consumers' conversion, and opening/input recording has no complete transaction or
custody story. The smaller composition remains the existing fold, stage values, agent proc and writer; none of
these findings calls for a second loop. All suggested changes below are **TARGET**, not installed APIs.

I accept §1's rulings, including no repair, whole history, crash loss of bookkeeping, resets and ~150 KB/pass.
This is a review of whether the proposed implementation serves them, not an invitation to reconsider them.

## Evidence and operating boundary

Read lane-rules first, AGENTS.md, all five r-* research notes, both v2 reviews, the v3 plan, the agent-loop spec,
repl-lookup design and relevant dh-fix evidence. Used the data-oriented-clojure, REPL, testing, Datahike and Flow
skills. Plan/source baseline: `99bdb12aec8264e16133264f768b073a48a75c25`. Default runs archive
`da0ae05dd2f873b5ea47d577c6286c772fe4fc6a`, PID 83524, start 09:08:01.624Z; the intervening committed diff in
src/script/test/schema resources is empty. Source citations below are to that committed baseline, not another
lane's changing working tree. Pins: Datahike `d9121bbbad`, SCI `ce0aa53b9b`, edamame `196ea5e4ef`, Malli
`43c6dca8d2`, core.async `dc35f3e0d7`.

Initial operator/MCP status: default **degraded**, basis 536871116; 8 over-bound warnings, 2 errored receipts,
1 unowned namespace, no missing readiness layers, ready-ms 8665. MCP reported live heap 1,117,281,808 bytes,
cache weight 83,545,844 / 512,000,000 bytes. Top inclusive warnings included
`call-detecting-program-change` (max 2787 ms), `call-with-registration-delta` (2788), `observe` (2791),
`await-port-operations` (3057) and `sci.batch/around` (2819). These observations are not proof that their
wrappers are the cost producer. Existing related tasks include `seon.sci.eval/a-reload-between-forms-is-undetected`,
`seon.sci.eval/acquisition-rebuilds-the-whole-program` and
`seon.profile/scope-wrappers-report-callback-time-as-work`. Operator status also reports the typed unknown
`seon.maintenance/uncollected-root` for the checkout (no completed collection receipt), not a successful GC.

Foreign boundary: reset-leak owns boot/host edits; test-cost owns SCI/test/runner edits; q-orjoin and
contract-check subsequently edited db and the contract checker. Datahike/http-kit and unrelated untracked files
are also dirty. No foreign edit was loaded or included. No model calls, test runs, extra JVMs, pollers, agents
delegated, worktrees, resets or restarts. All live probes used MCP JVM, root/default, explicit connection;
no definitions or host roots were changed. No branch was created, so none needs retirement.

Landing boundary: while this note was being written, reset-leak (`71439648a`, evidence `f148e157e`) and test-cost
(`4c9ccb3f7`) landed. Their committed notes were read for the §2 audit; I did not read another reviewer's
second-round findings. Source citations remain the explicitly pinned review baseline. Repeated candidate
lint/contract checks against `4c9ccb3f7` are recorded below; these landings do not establish installation on
default or change this lane's source/test diff of zero.

## Ranked findings and concrete amendments

### 1. High — do not write the fallback call row after an unknown settlement

Plan §4.6 lines 173–175 says both “refused or unknown … writes the provider call row alone” and “unknown … keeps
the agent unavailable.” The retained spec §2.1 explicitly says **no retry and no call-alone write** for unknown
(agent-loop.md:179; regression T11 at :422). This is a regression from v2's requested invariant, not a deferral.

`seon.db/transact-call` holds the pending Datahike result only locally (`src/seon/db.clj:5717–5758`); its timeout
returns `:seon.db/transaction-outcome-unknown true` and explicitly says the queued transaction may still commit.
Writing another row cannot establish the first write's outcome. It can conflict with its identity, count the call
twice if a new id is minted, or obscure which settlement owns ordinals/answeredness. The present timeout result
does not expose the pending completion object: “until resolved” needs an identified owner/seam, not a promise.

Replace §4.6's second bullet with: “On definite refusal, preserve values and record the call and diagnostic once
under the original call id; specify whether refused program facts are dropped in that bounded fallback. On
unknown outcome, issue no retry or call-alone write; retain the agent and outstanding write until its terminal
outcome is known, or leave it unavailable with a diagnostic.” The extra fault and fallback call must share the
same allowed failure transaction. Define failure of that transaction too. Storage failure cannot spin or silently
release ownership. Retain the old spec's context/provider-failure cases: a counted call with no evaluations,
unchanged answeredness and a terminal reply. State post-commit installation failure separately from refusal.

### 2. High — S2b still deletes the read producer before the run consumers can stop using it

This is r-delete D5's explicit ordering constraint, repeated in the v2 reviews. The old submission path obtains
`sources` before enqueue (`src/seon/cluster/agent.clj:742–765`); `system-run-plan` persists `source-rows`
(:754), carried by `submission-open-tx` (`src/seon/turn.clj:777`). Model `freeze!` likewise plans rows before
evaluation. The fold later reads those rows. `turn-request` (:829) is an available stage constructor, not a
conversion of those callers. Deleting `forgive/segments` and replacing the read cursor in S2b does not satisfy them.

Split S2b's wording: **reader behavior changes may land on the existing entrance; removal of segmentation and
the second semantic read lands with conversion of the source-row/open consumers.** Alternatively bring that
complete conversion forward. Do not retain an undocumented structural pre-reader and claim “one read.” Explicitly
place D5 in the dependency-complete cut. Convert the quoted MCP segmenter and schema key too.

S5 also needs a precise intermediate contract: shared settlement may retain the old run duties until S6, but
must not claim the model already makes one machinery transaction while open/capture/attempt/intent still live.
S4 removes capture, not all remaining commits. State which guarantee first becomes true in which slice.

### 3. High — opening and input delivery introduce missing durability, basis and cross-branch decisions

V3 adds the missing input form, but §4.4 does not say whether it is its own pass or part of the model pass.
These alternatives have different costs and failure behavior:

- If it must already be a **stored exchange** when the provider sees it, it needs an earlier settlement; a wake
  cycle then contains an input pass plus a model pass (and potentially an opening pass), each one transaction.
- If it is held in the current batch and committed with the model reply, context cannot consist only of stored
  exchanges. A failed provider/record can show the input twice unless delivery identity is carried explicitly.

The smallest choice consistent with the current text is ordinary opening/input passes through the same proc and
fold, durably recorded before constructing the model request. Say that explicitly and measure the whole wake
cycle as well as each pass; do not present one commit per provider call as one commit per wake with fresh input.

“Opened fact on the assignment” creates another custody problem. Tasks/assignments live at task home
(`src/seon/task.clj:340,360,429`), while evaluations/history live on the agent branch. A branch-local opening
settlement cannot atomically write the assignment at task home. Prefer an opening completion fact beside its
evaluations, keyed by the assignment's identity/revision; otherwise specify the extra home transaction and
idempotent reconciliation. `assign!` currently derives the assignment id from `[handle agent-id branch]` (:435):
reassigning the same tuple does not itself create a new id, so “once per assignment” needs a reassign/revision rule.

Input deduplication cannot use only model `answered-t`: opening/input passes never advance it. After input is
recorded but the provider fails or the JVM restarts, the same wake must not append another input evaluation.
Derive delivered ids/basis from recorded input facts at the writer. Capture the input read's immutable basis and
carry it to the model call; a message arriving **between the input read and its settlement** must remain due,
just like one arriving during the provider call. Using the input settlement's newer head as “presented basis”
would acknowledge an unseen message. Task-home notifications need their own lineage/evidence; transaction
numbers from independent branch heads are not a common ordering clock.

### 4. High — S6's cut depends on S7, and the retained invariants omit operational details

§4.5 stops on `complete!`/proof completion, but those semantics and proof admission arrive in S7, after the cut.
S7 also says “SCI test running fixed,” while S5 has already claimed every entrant works. The old spec put the
proof owner before wiring the loop (`agent-loop.md:369–371`). Move assignment/launch checks, the bail/stop result
and the proof invocation seam before or into S6. Defer only independent refinements with an explicit working
intermediate contract. `my.test/check` versus `clojure.test/run-test-var` must be distinguished: the existing
ClassCastException task concerns the latter, not proof that every runner invocation is broken.

S6 names “the three bounds” without declaring their names, owners, outcomes or MCP wait semantics. Retain the
old §2.2 table and actual-exit contract in the replacement. The backstop dial also controls MCP's awaited reply
(`src/seon/cluster/agent.clj:781–840`); deleting the backstop alone removes that contract. Preserve terminal
reply/close on every path, ownership while a host call ignores interruption, and non-overlap through actual exit.
`turn-step` (:511–556) is the existing thread/interrupt owner, not replaceable by writer-derived ordinals.

Also retain no-provider admission, schedule-only arming versus call-opening wakes, exhausted-cap notification,
and failed/context-only calls. They are not in §4.8's list. “No forms waits” conflicts with leaving the original
wake due unless a stored disposition makes that choice observable. Give the work predicate a small case table
for no forms, read error, provider failure, cap, bail, all-green and a newer wake. Do not let a self-wake or MCP
retry refill the cap; record which outside wake's anchor each call consumed.

### 5. High — the transaction table still does not account for all faults and test machinery

“A fault … rides the pass transaction” fixes only the local part. Today `seon.fault/record!` writes the origin and
the cluster copy so evidence survives retiring the agent branch (`src/seon/fault.clj:150–167`). V3 never chooses
where the authoritative surviving fault lives, which notification is required, or how that cross-branch write
fits the count. §4.8 retains task-home custody; the table's exception only names **completion**. This v2 finding
is still open, not explicitly deferred. State fault authority, delivery, retention and each allowed transaction;
do not silently lose errors when their branch retires. Coordinate with the errors cut instead of implementing a
parallel pass-specific error recorder.

Tests are serialized by execution ownership, not by “its test branch” alone. `test/attempt-request-context`
creates the agent/run before an effect-reaching body (`src/seon/test.clj:179–201`); member completion and
request-level completion are different writes and may have different connections. The resolved host body and
SCI body use different evaluation stages (:264–285). Enumerate member pass, request aggregate and task-home
completion, including effect provenance before any evaluation entity exists. Preserve runner admission,
reach/positive evidence and late-exit handling when reusing its transaction data.

For JVM mode explicitly say **no SCI write observation, no derived program facts/kondo**; that is the installed
`jvm-request` contract (`src/seon/turn/batch.clj:887–909`). A host `defn` changes the host even if recording fails.
One cursor/evaluator owner is the guarantee, not exactly one parser/kondo invocation for a precompiled test body.
“One batched query” must mean the digest check, not every database read: `runtime-analysis-batch` still reads
available functions/namespaces (`src/seon/fn.clj:958,983`), and the writer has validation/replacement reads.

### 6. Medium-high — reader and delayed-analysis contracts need more than the listed happy cases

The analysis ordering is corrected and the admission problem is **explicitly deferred**, appropriately. However,
the paragraph calls it S4 while implementation sits in S5. Make it a blocking design proof for S5. Include
def→redef→use, def→unmap/remove-ns, a schema-only change, a private `def`, cross-namespace definitions and
definitions arising during lazy realization. Current derivation inspects mutable Var metadata/root per form
(`src/seon/turn/batch.clj:256–272,318–368`); holding just those Vars until the end loses earlier versions.
Capture necessary observations when they occur, preserving deletion/admission without a second analyzer.
`apply-pending` makes earlier facts visible to later forms (`src/seon/batch.clj:480–501`); the open question is
semantic, not just moving a call. For source spans, state whether kondo sees original or commented text and how
offsets correspond. Commenting prose changes columns; preserved original sources must still join exactly.

Keep the ruled line scan, but complete its grammar at the cursor: while a form is open, continuation lines are
part of that form, not classified as prose. Cover indentation, multiline strings, metadata (`^`), keywords,
numbers, strings and characters at a form boundary; `#` headings and line-start Markdown backticks need a stated
outcome under the chosen rule. MCP legitimately accepts such literals too. Specify fences and precedence for
prompt markers inside fences. “Same reader; MCP has no prose” does not define these cases.

Live edamame probe below: an unmatched closer can be consumed and the next form read, but an unclosed list
consumes the rest through EOF. Change “reading continues” to “continue from the reader's advanced cursor when
possible; at EOF report the unfinished form, without repair or re-reading.” Do not imply independent later forms
can always be recovered. Define imitation syntax as a deliberate reserved shape (including or excluding the
equivalent unqualified map spelling), not a detector of truthful claims. Trailing prose/no-form refusals need a
stored exchange even with zero evaluated forms.

### 7. Medium — printing before analysis fixes lazy observation but cannot finalize later diagnostics

§4.1 prints each value before end-of-pass analysis/record. Analysis, admission, publication, settlement and
post-commit installation can still add diagnostics. §4.4 says both full reply texts are printed once “at record
time,” then §4.1 delivers held texts without reprinting. Name the two values involved: print/render the **result**
once while its observation is open; finalize the reply envelope from its shown text and accumulated diagnostics
through the existing printer. A commit failure cannot already be in its failed stored envelope. Deliver it as a
separate diagnostic with durable recording only where possible, and state how next context sees it.

REPL/DATA must reuse that shown result rather than render/realize it twice. Specify how the printer composes
already printed units without reading EDN back, escaping incorrectly or clipping saved history a second time.
Both encodings in S8 increase store/allocation costs; count both. Preserve original trailing prose, output and
no-form errors, not just successful form records. Add the result lifetime sentence lost from the v2 correction:
handles retain identity but answer gone-value data after their runtime context is lost
(`src/seon/sci/eval.clj:2769–2796` at baseline).

### 8. Medium — budgets still lack owners for residual work, and some factual claims remain overstated

The numbers establish a baseline, not that the targets follow from deletion. S0 must name exact source/result
sizes and assignment population for 1/10/200 exchanges, ordinary versus defining passes, cold acquisition versus
warm execution, both transcript encodings, and next-pass-after-defn. The latter still reaches acquisition through
MCP JVM (`src/seon/mcp.clj:267`) and installation after settlement. P0 does not explicitly own that residual cost.

The 200-row 17 MB experiment is **indexing all 1,387 evaluations plus pulling 200**, before composition; it is not
an installed ordered-history API. S3's “must reach” is an explicit future design obligation, not evidence. History
work is O(exchanges + returned text); whole history cannot fit a fixed allocation ceiling for every lifetime.
State the finite S0 acceptance workload, and retain the owner's visible window refusal for larger histories.

V3 drops the measurement-write decision rather than resolving it. `significant-tx` already filters changed
windows (`src/seon/profile/measurement.clj:142–198`); those writes were 51–78% of sampled settlement datoms.
State what happens to them, the fault-window query (:3430), problem-assignment routing (:5166), stored refresh
evidence, unused token/elision fields and transaction hot/repeated metrics. Removing obsolete consumers permits
deletion; moving them to another writer breaks the transaction target. Delete `run-observation`'s reread/zip when
replying from the held batch (`src/seon/cluster/agent.clj:852`). Name these deletions in S4/S5 rather than inheriting
all 1,365 lines of the old settlement. Keep test/evaluation evidence that surviving consumers actually require.

Definition passes are exempted from the 5 MB target without a separate acceptance bound; they are not exempt
from §1's stop-and-fix policy. Kondo alone measured 4.5–7.8 MB warm in the Opus review, and the historical defn
commit wrote 604–706 KB. Treat ~150 KB as the accepted ordinary small-pass observation, not a universal storage
floor/upper bound covering definitions, blobs and duplicate S8 encodings. Proof **body** work can be agent work;
runner setup, selection, recording and cleanup remain Seon work regardless of who invokes them (§6.3).

The newly landed test-cost evidence confirms that residual problem: its later cold case is 584–702 ms /
817–822 MB and warm 126–132 ms /185–191 MB, with first-acquisition GB costs still present. P0's task having a
commit does not establish the §3 allocation budget. The note assigns remaining selection/admission history
scans and whole-program acquisition to named tasks. Carry those outstanding gates into the plan's schedule.

### 9. Medium — reset and old-history handling remain omitted, not deferred

Reset is authorized; no new approval is needed. But `reset-cluster!` replaces the branch with the selected source
commit (`src/seon/cluster/registry.clj:319–355`), not just the retiring schema attributes. Add the v2-requested
preserve/discard manifest for runtime-only definitions, task identity/revisions, assignments, settings, messages,
schedules, error evidence and live handles. Dumped task declarations do not preserve all of these. Root must
verify retained task identities/references round-trip and coordinate only owned/unclaimed branches.

S3 adds origins but cannot infer all old `:system` rows. Switching history in S4 without reset or an explicit
historical policy silently drops old exchanges/openings. With no migrations, name S4's reset/cutover point as
well as S6's, and prove schema retirement/adoption on a live-store branch **before** landing each slice. A reset
after commit does not make an unbootable commit valid. Persisted `:generate` rows need terminal handling when
S4 deletes their handler. The currently reported reset-leak fix is a prerequisite, not proof that these data
loss decisions have been made.

## Every v2 finding: disposition in v3

“Resolved” here means addressed in plan text, not implemented or tested. “Partial” means some requested duties
are absent; it is **not** an explicit deferral. O = review-plan-opus-notes; A = plan-v2-review-notes.

| v2 finding | v3 disposition |
|---|---|
| O1 analysis after evaluation | Ordering resolved; same-pass admission explicitly deferred, with wrong S4/S5 label (finding 6). |
| O2 continuation | Rule added, owner decision 1 pending; intermediate stop/proof behavior remains partial (4). |
| O3 JVM fence/ordinal authority | Proc move and writer ordinals resolved in main design; §6's unserialized alternative remains unsafe (below). |
| O4 budget producers | Kondo skip, proportional context budget and accepted store target addressed; residual stage attribution/acquisition remain partial (8). |
| O5 test cost | Explicit P0 test-cost lane and §6 choice added; distinguish runner from body work (8). |
| O6 crash/replay/call loss | Crash replay explicitly accepted by §1/§4.6; do not reopen. Unknown fallback contradicts retained invariant; context/provider failure cases partial (1). |
| O7 origin dependency | Resolved by S3 before S4; all producers/backfill-free cutover still must be specified (9). |
| O8 settlement duties and extra writes | Partially addressed by table, local faults, effects/test completion; no measurement/routing deletion decision or cross-branch fault authority (5,8). |
| O9 storage options | Owner resolved ordinary ~150 KB; no need to reopen <50 KB. Attribute-count/deletion levers and definition/blob costs still need treatment (8). |
| O10 factual corrections (all subitems) | Root shares, datom range, allocation scope, no-effect qualification, erroneous D1 labels/citation removed. Handle lifetime still absent (7). Plain reader supersedes repair dependency, but source-row order persists (2). |
| O11 probe prerequisites | Marks excluded or fixed (§3); imitation nil assigned S2b; reset leak assigned P0; stale-row deletion removed. Evidence of those future fixes still required. |
| O12 JVM/test semantics | Stage-value and resolved-body table added; explicit JVM no-program-facts and test execution/recording custody incomplete (5). |
| O13 DATA printer | Printer ownership resolved in principle; final envelope/late errors still incomplete (7). |
| O14 provider window | Explicit visible refusal added; resolved as policy, provider/context failed-call accounting still needed (1,4). |
| O15 opening gap | Seed removal and new opening paired in S4; old seeded rows and origin transition not handled (9). |
| A1 cursor/analysis/admission/spans | Sequential read and analysis timing resolved; admission explicitly deferred; original spans and mutable observations still partial (6). |
| A2 input visibility/opening/task home | Input origin and once-per-assignment added; pass boundary, delivery dedupe, basis and home custody partial (3). |
| A3 faults/effects/tests/writer reads | Effect provenance and writer authority improved; cross-branch faults, request/member facts, JVM analysis applicability and held query inputs partial (5). |
| A4 print/blob/outcomes | Print while observation open and publication named; unknown fallback regressed, post-install/late diagnostics partial (1,7). |
| A5 serialization/answeredness/exit | Main proc design and presented basis resolved; §6 alternative, input race, explicit waits/bounds and no-evaluation calls partial (3,4). |
| A6 order/size (all slice rows) | Dead-code range, HTTP fixture, current help, independent debug deletion, additive origins and split cut addressed. D5 dependency, live adoption before retirement, reset manifest and tests/completion before cut not resolved (2,4,9). |
| A7 budgets/query/measurements | P0 q-orjoin, residual 457 ms/550 MB and narrow-read target acknowledged; no after-defn attribution or measurement disposition (8). |
| A8 decisions/reset/subtrees/spec invariants | Reset/store approvals preserved, invariant names restored, old-node caveat added; loss manifest, mixed-version/durability pricing and full operational contracts remain partial (9 and decision table). |
| A9 presentation/reader precision | Handles/errors/output, one printer and literal imitation grammar added; trailing/no-form exchange, final diagnostics, literal/multiline grammar and gone handles remain partial (6,7). |

The two v2 factual-audit tables are reconciled by the audit below plus O10; their test lists are obligations,
not passing evidence. V3 does not explicitly defer the omissions marked partial.

## §2 and §4 accuracy audit

| Claim | Evidence / correction |
|---|---|
| 2.1 s / 3.65 GB / ~400k / .94 MB / six commits | Matches r-turn §3a, warm fixture. Allocation/store are windows; ~400k is the instrumented window total, not a clean stage sum. The table already says specimen. |
| Refresh ~80%, 1.3–2.3 s / 3–4 GB, 228 / 202–408 datoms | Supported across r-turn/r-record/r-context; 228 is the warm fixture. Not every turn's intrinsic reacquisition cost. |
| Remaining 457 ms / ~550 MB | Arithmetic of r-turn stages 2–6: 29+227+32+33+136 = 457 ms; allocation totals 550 MB. Includes the stub provider interval; not a measured replacement pass. |
| MCP SCI 27 datoms / ~150 KB / 205–358 ms /159–232 MB | Historical no-effect/no-fault r-record §1a. ~170 MB attribution is an approximate subtraction of whole-JVM windows in O4, not a stage measurement. |
| defn 712–733 MB, settlement 254 ms, install 104 ms | r-record §1b supports it, whole-call allocation. Store 604–706 KB matters to §3 too. |
| Forty forms, 441/667 ms | r-read §1 supports analysis time within batch, not total end-to-end MCP time. |
| 76 KB mixed reply, 4.1 s/94 GB | r-read §4: 76,408 characters, not measured UTF-8 KB; empirical cubic-like growth for that workload. Call it ~76k characters (~24k estimated tokens at prior 3.2 chars/token). |
| Batched code-currency, 60 ms/180 MB one symbol; 42 s/672 | A's exact probe and r-context §1.4 support distinct populations. `src/seon/turn.clj:1885` is one query. New q-orjoin performance unverified here. |
| Test 2.3–6.0 s, 55–110 ms body, 384 ms warm | O supports root's 6 s/55 ms report. Newly committed test-cost notes support 2,288 ms with a **23 ms** body and other bodies 23–110 ms; 384 ms is still not tied to a named specimen. Link exact forms/revisions and preserve populations; no test rerun by this lane. |
| Contract check “of every lane commit,” 1.7 s/3.18 GB/471 files | Number is A's deliberate whole-src/test check, reproduced below. **Not every lane commit:** bin/lane:129,235 skips documentation inputs; ordinary checks select inputs. Relabel “full src/test contract census via MCP.” |
| Reset leaked 16 connections/branches, 5.1 GB until restart | A/O alone showed correlation. Newly committed reset-leak notes trace the missing hosted-child stop and prove eight connected branches survive reset before the fix and none after; heap after reset 1334→339 MB. This supports the cause; cite it. The 16-host/5.1 GB specimen remains root's historical observation, not this review's probe. |
| Per-commit store “floor,” 36% roots/64% nodes | Percentages match one 152 KB specimen, r-record §4; rename “small arithmetic commit observed cost.” Not a universal floor. |
| Four behavior bullets “one real model specimen” | Mixed evidence: fabricated comments/maps are model transcripts; prose-only fallback and nil fault are reader/MCP probes (r-read §3), not all from one model specimen. Relabel “observed model transcripts and reader/MCP probes.” |
| Origin is author / :system conflation | Supported by `mcp.clj:226`, `turn.clj:1323`, r-context §2.4; live schema probe confirms author installed, new origin absent. |
| §4.1 existing apply-pending and writer conflict guard | Verified `batch.clj:480`, `turn/batch.clj:548–609`. Guard exists but uses per-row pulls today; batched digest lookup is TARGET. One analysis is available (`fn.clj:944`), one end-of-pass admission is not proven. |
| §4.2 proc and JVM threading | Verified `cluster/agent.clj:511–556`, `mcp.clj:254–292`; JVM also acquires and binds into the agent's SCI context. Test branch alone does not serialize (5). |
| §4.2 faults/effects and §4.3 reader | TARGETs; existing fault recorder writes twice, effect resolves run+ordinal, existing readers still forgive/split. Edamame continuation limitation verified below. |
| §4.4 origin/order/opening/input/presentations | TARGETs. Current order joins turn tx + ordinal (`src/seon/eval.clj:54`), reply maps rebuilt (`src/seon/repl.clj:359,497`), gone handles bound from history (`sci/eval.clj:2769`). No installed ordered narrow index/input API implied. |
| §4.5–4.6 continuation/failure | TARGETs; contradictions and omissions in findings 1,3,4. No-provider behavior and three bounds cannot be inferred from the diagram. |
| §4.7 lookup design “claims verified” | r-context verifies many, explicitly marks some unverified and corrects line/name/cost claims. Carry r-delete's correction: keep `my.program/declaration`, whose other caller survives; repl-lookup still proposes deleting it. SCI find-doc prints today; data adaptation is TARGET. |
| §4.8 old invariants retained | Names restored, operational guarantees not all restored; unknown fallback is contradictory. Keep normative details, not just the bullet list. |

## Slice feasibility and smaller order

| Slice | Minimum condition for a bootable, reviewable landing |
|---|---|
| P0 | Independent producer fixes and reviewed dependency pin; no claim that in-flight means installed. Keep turn-request. Pin bump needs root's live-store adoption and central tests. |
| S0 | Same behavior matrix at actual entrances; HTTP fixture only for the model entrant. Separate source/reader, body, runner and recording costs; preserve pre-P0 baseline. Add next-pass-after-defn and failed/context-only calls. |
| S1 | Feasible independently; installed-only help; convert both installers, render.ns caller and test-only loop; keep surviving declaration reader. |
| S2a | Feasible independently; convert routes/quoted symbols and ledger with explicit selector. |
| S2b | Reader behavior preparation only until D5's source-row consumers convert; specify cursor grammar and recovery. |
| S3 | Origin/pass/order fields and all existing writers together; opening fact custody decided; narrow history acquisition measured; old readers remain valid until deliberate cutover. |
| S4 | Input/opening passes with stable basis, dedupe and assignment scope; new context replaces seed/refresh/prompt atomically; live adoption then explicit reset/history policy. |
| S5 | Split reviewable in-place preparations: shared recording retaining old duties; JVM proc request; error/test/effect integration; separately prove deferred analysis semantics. Do not compress all into one unreviewable rewrite. |
| S6 | Only after those proofs, three explicit bounds, no-provider/admission, stop/completion seam, unknown ownership and every run reader/fixture/boot recovery caller converted. |
| S7 | Move required proof/bail/admission and SCI entrance fixes earlier; post-cut only independently working refinements. |
| S8 | Use actual loop; count both saved presentations. Reader/stop defaults must already yield usable complete replies before experimenting. |

This retains one production path at each intermediate HEAD. Delete the obsolete writer duties and rereaders
instead of adding adapters that keep them alive. Source-size targets are review prompts, not implementation proof.

## §6 options and questions for root

| Decision | Review |
|---|---|
| 1 Continuation | Legitimate open choice. Continuing gives feedback-based progress within a cap; outside-wakes-only cannot finish a multi-step task without more input. State treatment of read-only forms, errors, no forms, wait, completion and already-due wakes. No-form waiting is a disposition, not absence of facts. |
| 2 JVM serialization | Recommend existing proc. Writer-derived ordinals alone are **not a correctness-equivalent alternative**: they cannot serialize execution/private Vars/result binding/prompt construction. Any offered concurrent option needs separate context semantics and revalidation, larger cost and different behavior. Do not price it as the small alternative. |
| 3 Proof budget | Separate authored body work from runner overhead. Reclassifying the entire proof request as agent work would hide the producer defect P0 exists to fix. Give both numbers and the same stop-and-fix gate on setup/recording. |
| 4 Subtree counts | Useful three options, but recount alone is mitigation, not a split fix. Child counts require PSS format/version, mixed-node correctness, old-count convergence and crash proof; ~1 KB/node is before compression, not total per commit. Counting at split trades reads for no format change. Keep this independent of correctness gitlink bump; attach measured costs, not invented implementation hours. |
| 5 Replacement spec | After findings 1–9 and the v2 checklist are resolved or explicitly deferred with prerequisites. Carry normative bounds, launch rules, failed calls, reload/adoption overlap checks and completion recovery in addition to §4.8's names. |

Questions to resolve in the text: Which connection owns the opening marker? Are input exchanges committed in
their own pass, and what recorded identity/basis deduplicates them? Which owner retains the unknown write's
completion? Where does an error survive branch retirement? What observable stop/launch contract exists at the
S6 intermediate HEAD? These are implementation decisions serving §1.

## Exact live probes and checks

1. MCP JVM root/default, result `result/e72ce48ee3d2a`:

```clojure
(let [d @(seon.cluster.boot/connection "default")]
  {:review/basis (seon.db/basis-t d)
   :review/schema (mapv (fn [k] [k (get (:schema d) k)])
                       [:seon.cluster.eval/author :seon.cluster.eval/origin
                        :seon.cluster.eval/agent-ordinal :seon.agent/answered-t])
   :review/vars (mapv (fn [s] [s (boolean (find-var s))])
                     '[seon.turn.batch/program-facts-call seon.turn.batch/jvm-request
                       seon.contracts-compile-test/check])
   :review/task (seon.db/pull d [:seon.task/name :seon.task/intent]
                            [:seon.task/id "10d1523434db"])})
```

Basis 536871116; author keyword/cardinality-one, other three schema entries absent; all three Vars resolved.
Task is `a-reload-between-forms-is-undetected`. No errors/findings; envelope supplies no timing/call count.

2. MCP JVM root/default, result `result/ea1acc07aef85`, basis 536871117:

```clojure
(let [d @(seon.cluster.boot/connection "default")
      cases ["(inc 1)\n(+ 2 3)" "(inc 1\n(+ 2 3)" ")\n(+ 2 3)" "[1 }\n(+ 2 3)"]
      opts (edamame.core/normalize-opts {:all true})]
  {:review/basis (seon.db/basis-t d)
   :review/reads
   (mapv (fn [s]
           (let [r (edamame.core/reader s)
                 next! (fn [] (try {:form (edamame.core/parse-next r opts)}
                                  (catch clojure.lang.ExceptionInfo e
                                    {:error (ex-message e)
                                     :at (select-keys (ex-data e) [:row :col])}))) ]
             {:source s :reads [(next!) (next!)]})) cases)})
```

Valid input returns both forms. Unclosed list: `EOF while reading, expected ) to match ( at [1,1]`, row 2 col 8,
then `:edamame.core/eof`. Unmatched closer: row 1 col 1 error, then `(+ 2 3)`. Mismatched vector closer:
row 1 col 4 error, then `(+ 2 3)`. These are expected read-error data, no runtime defect. No MCP errors/findings;
no timing/call count supplied. Dependency guarantee: normalized parse-next reads from its cursor, not recovery
of already-consumed text (`reference-code/edamame/src/edamame/core.cljc:120–142`). No forms were evaluated.

3. Candidate archive `/tmp/seon-review-01a115a1/plan-v3-review/candidate`: git archive of baseline HEAD's
src/script/test/resources/.clj-kondo/deps.edn. Only ignored analyzer configuration was linked as bin/lane does;
no shared cache read/write (`--cache false`). Notes-only diff changes none of these inputs.
`clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,481 warnings**, 6,487 ms lint time,
6,527 ms command time, exit 2 for warnings. Whole-corpus requested verification, not ordinary-pass latency.

Contract check, **471 archived src/test .clj/.cljc files**, installed `seon.contracts-compile-test/check`,
`:root` the archive, `:paths` the absolute paths, `:candidate` the explicit default carried projection:
findings **[]**, **1,727.8905 ms / 3,148,765,632 calling-thread allocated bytes**, MCP duration 1,736 ms with
`:slow`, result `result/e7c99eac9ee42`. Uses the check's packaged-form equality before reusing the projection
(`test/seon/contracts_compile_test.clj:818–823`), and Malli `m/function-schema` (:719). No source loaded, no
test runner called. Existing task `seon.schema/contract-check-on-three-files-exceeds-one-second`; P0 owns cost.

The measuring form selects files with `file-seq` over `["src" "test"]`, suffix `.clj`/`.cljc`, then:

```clojure
(let [d @(seon.cluster.boot/connection "default")
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
      t (System/nanoTime)
      findings (seon.contracts-compile-test/check
                {:seon.contracts-compile-test/root root
                 :seon.contracts-compile-test/paths paths
                 :seon.contracts-compile-test/candidate (seon.db/carried-projection d)})]
  {:review/files (count paths) :review/findings findings
   :review/ms (/ (- (System/nanoTime) t) 1000000.0)
   :review/allocated-bytes
   (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) a)})
```

An initial broader invocation used `["src" "script" "test"]`. It escaped with edamame/error
“Use `:auto-resolve` + `:current` to resolve current namespace.” at script/seon/dev/settlement_probe.clj:35:67
(`::timeout`, no ns form), result `result/eebf73b635153`. No timing returned. The expanded cause chain repeated
eight links and was elided by the printer (roughly 850 estimated tokens shown); not a successful compile check.
After searching resources and the live task namespace (result `result/e86fb28145e60`), created
`seon.contracts-compile-test/script-with-auto-resolved-keyword-escapes-contract-check`, minted id `a82d2bd90862`,
result `result/e719b782be15c` (`:changed? true`). Its intent includes the single-script reproduction and proposed
proof `seon.contracts-compile-test/a-script-with-auto-resolved-keywords-returns-check-data` (not installed).
No source repair in another lane's file. Full src/script/test **lint** passed; contracts proof is src/test only.

No before/after optimization claimed; armed-call/store-byte deltas were not measured in this review. Historical
expensive query/model/history probes were not repeated merely to rediscover known defects.

4. Repeated checks after the concurrent P0 landings: archive of `4c9ccb3f728e666a2b18bb3581fd042af208c788`
plus this note, same command and linked lint configuration. **0 errors, 1,483 warnings**, 9,848 ms lint time,
9,879 ms command time, exit 2. Same contracts form with root `.../plan-v3-review/landing-candidate`, same 471
src/test files: **[]**, **3,020.701625 ms / 3,163,813,384 calling-thread bytes**, MCP duration 3,039 ms,
`:slow`, result `result/e3d9c978ab09e`. The whole-corpus cost remains linked to the existing contract-check task;
this is required static evidence, not a new timing benchmark or a test run. No foreign dirty changes included.

## Root's central proof list and landing

No runtime test is required for this Markdown-only change. For implementation, preserve/convert these exact
existing tests in root's central round (none run here):

- `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`
- `seon.mcp-entrance-test/an-agent-runs-its-own-test-on-its-own-branch`
- `seon.mcp-entrance-test/a-one-defn-run-settles-in-proportion-to-the-defn`
- `seon.run-test/item-1-a-stage-throw-is-a-diagnostic-beside-each-value`
- `seon.run-test/item-16-the-reply-is-one-key-set`
- `seon.run-test/item-19-a-write-after-the-observation-closed-is-refused-to-its-writer`
- `seon.turn-test/a-call-omitting-its-database-sees-the-forms-own-write`
- `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`
- `seon.turn-test/a-schema-key-changed-since-the-run-read-it-is-refused-at-the-writer`
- `seon.turn-test/a-settlement-that-throws-still-shows-each-evaluated-value`
- `seon.turn-test/settlement-claims-covered-messages-without-removing-their-wakes`
- `seon.effect-test/request-commits-before-io-dispatch-and-settles-once`
- `seon.effect-test/effect-refs-its-evaluation-and-handler`
- `seon.blob-error-test/publication-preserves-a-refused-root-transaction`
- `seon.cluster.agent-test/disarm-drops-the-route-before-closing-it`
- `seon.cluster.host-test/an-unsettled-host-run-keeps-ownership-until-it-exits`

New proof obligations, not claimed installed tests: unknown settlement never writes call-alone; input read→commit
race does not acknowledge a new wake; restart after input commit does not duplicate delivery; branch-local opening
completion is assignment-specific; simultaneous model/SCI/JVM requests share actual-exit ownership; one analysis
preserves same-pass define/redefine/delete/schema visibility; faults survive branch retirement with declared
transaction counts; reader continuation/literals and trailing prose; failed/context-only call accounting;
crash after effect before settlement; completion's task-home/agent-home recovery; both presentations retain late
diagnostics and reuse one result rendering.

Design check: the direction holds against code; v3 still compresses unresolved custody and cutover obligations
into prose. The smallest remedy is explicit ownership and ordering at existing seams, plus deletion of obsolete
writer duties. The note does not implement a workaround. Changed path: this note only. Net src/test lines **0**.
Only this note is claimed for `bin/lane commit`; release follows. Required lint and src/test contract census are
recorded above; no boot/adoption/concurrency/model/browser/test-success evidence claimed. Owned scratch will be
removed after commit. **No restart or reset needed for this review.** The newly filed runtime task should be
included in root's next authorized task dump before any reset.

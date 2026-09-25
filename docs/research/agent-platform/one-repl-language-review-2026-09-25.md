---
type: research
status: ACCEPT-AFTER-FIXES; revision-5 design review; no installation proof
created: 2026-09-25
reviewed-design: 60e060b52b39466e086d2cec42e0927e8c861a43
source-pin: 19a29c683
scope: seven prior findings, composition and settlement, owner rulings, MCP retirement and milestone dependencies
---

# One REPL language: revision 5 review

**Verdict: ACCEPT-AFTER-FIXES**, with the three numbered corrections below made before the
corresponding implementation slices. Revision 5 establishes the right composition: the existing
`evaluate-sources` loop accepts stage values, reads once, returns native evaluations through a
shared finalizer, and leaves persistence and execution ownership with the existing lifecycle
owners. Tests now enter that composition. MCP SCI and JVM calls use root's run under explicit
custody. No second driver or result owner is justified.

The remaining problems are localized: settlement can still change an already printed value;
the fault-window summary differs from its named owner's revised contract; and the reduced
retrieval milestone has inconsistent retirement accounting. These do not require reopening the
architecture, Q1/Q2, or the owner's three rulings.

## Evidence and authority

`D` means `docs/research/agent-platform/one-repl-language-2026-09-25.md` at **60e060b52**
(revision 5, 982 lines). Each `D:<line>` below is a file-and-line citation into that file.
`L` means `docs/research/agent-platform/lost-error-seams-design-2026-09-25.md`, and `R` means
`docs/research/agent-platform/one-render-path-design-2026-09-25.md`, both read at **60e060b52**.
AGENTS.md citations use **60e060b52**. Source citations use **19a29c683**, the design's source
pin; short source paths expand under `src/seon/`, and `mcp.clj` means
`script/seon/dev/mcp.clj`. All source reads were pinned, independent of concurrent checkout edits.

Read revision 5 and the prior review end to end, together with AGENTS.md and the relevant
fault-window and continuation contracts. Applied the data-oriented Clojure skill. The controlling
laws are the common composition (`AGENTS.md:130–146`), writer authority (`:163–165`), native
errors and visible storage failure (`:199–216`), actual exit (`:221–227`), common rendering
(`:234–250`), and measured regression bounds (`:281–290`). The user's rulings are authoritative:
errors reference the world; renderers read the stable database; faults appear as errors in MCP
replies.

Dependency inspection confirms Clojure's stage-function composition at
`reference-code/clojure/src/clj/clojure/main.clj:414–446`, gitlink
**3bc2b3e91fdff462620d84bbdfd40379d9c562e5**. Malli's `m/-instrument` checks the supplied
function schema at `reference-code/malli/src/malli/core.cljc:3131–3152`, gitlink
**befcd302a8e626a67d84b80aabee0cd1b38dadb6**; it does not infer stage compatibility.
The existing first-party seams are `turn/evaluate-sources` (`turn.clj:4681–4697`), the test
recording writer (`test.clj:2061–2087`), source submission (`agent.clj:635–723`), and terminal
settlement (`turn.clj:3793–3863`). These are source facts, not proof of installed behavior.

The previous seven-finding verdict remains in Git at **5f7558427**. This file replaces that
review in place; it does not append a second design or carry the old verdict forward.

## Disposition of the seven prior findings

| Prior finding | Revision-5 disposition |
|---|---|
| 1. Read once cannot be deferred | **Addressed in design.** D:179–188 and D:244–264 make raw segmentation distinct from semantic reading, read after preceding namespace effects, pass the event, and retain malformed trailing source. P2 precedes P3/P4 (D:910–911). The offset entry and invocation-count proof remain implementation obligations, explicitly unverified at D:975–978. |
| 2. Tests need the same composition | **Addressed in design.** D:153–157 and D:434–458 name the common callable, adapt the member's call form and captured native report, and persist through the test request's existing writer before unlinking. A run transaction per member is unnecessary; shared finalization and facts are necessary. C1's deletion remains conditional. |
| 3. The 150 ms cost is unproven | **Addressed in design, not measured.** D:504–527 withdraws the assumed price, requires comparable parent/change stage timings, allocation and retained memory, and blocks M1 on incremental refresh of a task-bearing root. Neither 250 ms nor one second replaces the regression bound. |
| 4. Final refusals and print ordering | **Partly addressed; correction 1 below remains.** D:189–202 and D:344–352 include installation and disposition replacements and preserve render-read evidence. They still promise a final print before a later writer can refuse or change the result. |
| 5. MCP-only behavior and retrieval | **Substantially addressed; corrections 2 and 3 below remain.** P5 moves status rendering and branch errors to their owners; P7 uses common continuation; A/B are labelled partial; Q3(b)'s false guarantee is withdrawn. Fault observation and reduced-milestone sequencing need reconciliation. |
| 6. Arming does not prove stage compatibility | **Addressed in design.** D:204–213 requires named stage contracts, resolution in the execution program, submission refusal for incompatible symbols, the complete evaluator request, and output validation. It adds neither structural function subtyping nor a second registry. Actual schemas and armed negative proofs remain prerequisites. |
| 7. Root/session custody and continuation | **Addressed as an explicit gated design.** D:401–427 separates continuation by stage, gives explicit namespace precedence, retains custody until exit, keeps settings with the agent, and makes P-RB the first P3 proof. Failure of that probe blocks branch SCI pending a priced writer; it does not authorize a bridge-owned agent. |

## Required fixes

### 1. Make terminal settlement and printing a coherent, honest order

**Locations:** D:190–202, D:344–348 and P1 at D:895.

Step 5 has already printed when step 7 discovers a settlement refusal. Printing that refusal
through the same function is still another invocation; saying “same call” does not put it before
the original print. Nor does the existing owner promise one transaction attempt: `settle!`
commits prepared data, then takes one bounded refusal branch if it fails
(`turn.clj:3840–3863`); batch settlement does likewise (`:3622–3633`, `:3779–3788`).

P1 also omits two surviving printers: `refusal-terminal-data` uses `pr-str` at
`turn.clj:3704`, and `settle-batch-refusal!` at `:3753`. The latter is already named as F10 in
L:693. An ordinary success can change after the proposed loop finalizer too:
`evaluation-terminal-data` adds `:my.turn/delivered-to` and re-admits the changed disposition at
`turn.clj:3466–3485`. Its saved text must correspond to that final value.

**Smallest fix:** extend the existing shared finalization/print seam to terminal-data preparation
and the bounded refusal branch, explicitly retiring both `pr-str` sites. Move the disposition
completion before its successful print. State the guarantee as one saved/returned rendering of
the committed terminal value: if a write refuses after speculative bytes were prepared, discard
those bytes, render the distinct native refusal through the same owner, and persist it through
the existing refusal branch. Count attempted renders and transaction attempts honestly; do not
claim literally one invocation on that path. Never rerun the source or render inside a transaction
function to manufacture that guarantee. If refusal storage fails, panic visibly under
AGENTS.md:213–216; do not promise a durable answer that storage could not produce.

Add these existing terminal paths to P1's scope, estimate and future proof: a writer-time
refusal publishes no speculative success, a completed disposition's value and text agree, a
batch refusal uses ordinary selection, and failed refusal storage remains a core failure.
Coordinate F10's retirement once with its owner. This is correction of the existing pipeline,
not a new recorder or retry loop.

### 2. Use the fault owner's actual window and saved-text contract

**Locations:** D:329, D:395–399, P3m at D:898, and dependencies at D:910–914.

D describes `:seon.cluster.eval/fault-basis`, “previous basis, this basis,” and transcript
“recorded fault-basis pairs” (D:484). Its named authority now specifies a different, necessary
contract: L:561–589 captures the cluster database **before** settlement, saves the observation's
exact shown text, and records one `:seon.cluster.eval/fault-window` component with cluster
identity/commit and distinct lower/upper bases. Only committed settlement advances the cursor.
The window belongs to the last form of a multi-form batch. First, compacted and changed-lineage
windows are unknown.

This is not just a key rename. Occurrence count and last-at are no-history attributes
(L:568–571), so basis pairs alone cannot reconstruct the original observation later. Also the
agent's branch basis and the cluster fault basis are different worlds. The stable database
ruling means each computation receives its appropriate immutable value; it does not make those
bases interchangeable. L:617–619 requires the observation to share the reply's presentation
budget. L:642–645 and L:774 make the fault cut depend on render slice 2(i), which D's dependency
list omits while B says it need not wait on the render plan (D:924–925).

**Smallest fix:** replace the obsolete fault-basis summary with an exact reference to L §M's
`fault-window`, saved bytes, cluster-world input, committed-cursor rule, lineage unknowns and
last-form batch rule. Preserve one query owner and one render budget. Carry L's render slice
2(i) prerequisite into P3m and M1; distinguish it from the continuation entrance that only P7
needs. Reconcile the schema and deletion estimates once across the two plans.

Keep the owner's outcome rule: new, undelivered or unknown faults make the response an error;
MCP merely encodes that result as `isError`. Use L:626–640's proof obligations, especially a
resolved-in-window fault, a later recurrence leaving old shown text unchanged, and a failed
settlement not advancing the lower bound. This accepts the ruling without recreating the bridge
window or a history renderer that recomputes it.

### 3. Reconcile the reduced retrieval milestone with P4/P7 retirement

**Locations:** D:473–502, P4/P7 at D:899–901, options at D:920–922 and Q3(a) at D:938.

P4 deletes `project-next-prepl-value!` and the artifact producer. The current `execute-get-value`
still calls that mark (`mcp.clj:765–769`). D:473–474 correctly requires converting every caller
in the deleting slice, but its table assigns the whole retrieval retirement to P7 while Q3(a)
books the same 137 lines in P4. A/B exclude P7 and their totals exclude those 137 lines, yet
promise the reduced unavailable-cut behavior. The branch of the plan that supplies that behavior
and handles the existing tool must be explicit before landing P4.

**Smallest fix:** make P4's row conditional on Q3. For the reduced option, retire the old tool and
its callers in P4 and put the unavailable-cut response at the ordinary render owner; update A/B's
counts. For the full option, require P7's owner entrance before removing its old producer/callers.
If compatibility for old blobs is deliberately retained temporarily, identify that limited
adapter, remove its deleted mark call in P4, and count only what actually retires there. Do not
credit a deletion again in P7. The owner still chooses Q3; this review recommends shared
continuation for C and explicit retirement for the reduced milestone.

R:467–475 still describes MCP artifact production, whereas D deletes it. State explicitly that
P7 imports the common continuation entrance, not that retired producer: live SCI backing can
expand, missing JVM backing answers unavailable, and nothing replays. Reconcile that producer
assumption with the render owner before integration. No new durable result store is required
for the accepted reduced scope.

## Owner rulings and implementation gates

**Errors reference the world — accepted.** D:312–317 applies references to evaluator, stage,
submission, timeout and fault values. Preserve class, message, chain and declared scalar details;
replace world objects with their identities rather than summarizing their causes. D:958 should
be read as applicable references, not a demand to invent a run/evaluation id before admission or
a blob digest where no blob exists. Absence stays absence.

**Renderers read the stable database — accepted.** D:349–352 supplies the form's immutable
value by declared schema name, honors caller arguments and keeps render reads in the form's
evidence. Correction 2 separately supplies the cluster value for a fault observation. No ambient
head lookup or copied database in an error is authorized.

**Faults in MCP replies — accepted, with correction 2.** Root's run remains the owner; an empty
success cannot replace a core fault. Delivery, persistence and `isError` must agree. The explicitly
chosen, bounded, unrecorded host diagnostic entrance remains an availability exception
(D:363–366, D:460–465), never automatic fallback.

Q1 is root's Flow run; Q2 is root custody for branchless SCI. Neither is reopened. P-RB, stage
schema compilation, current positive test evidence, adoption/arming, incremental refresh and
parent/change performance remain unproved implementation gates. Conditional acceptance of the
design supplies none of those proofs and grants no production edit or integration permission.

## Landing and verification limits

Only **this review file** changes. Production source/test delta: **0 / 0**. No test, runtime probe,
submission, provider call, adoption, publication, stop or reset was run. Default was untouched.
Runtime timing, allocation, retained memory and installed behavior are **unmeasured**. The
historical observations in D are not reproduced or treated as health evidence.

Foreign edits were preserved and were not a dependency of this review. No lane was contacted,
no worktree or alternate JVM was created, and no issue index was edited. The pre-commit hook was
read; it checks identity and staged skills, and this review-only commit invokes no tests.
Document verification is pinned citation inspection, whitespace/diff checks and exact commit
scope. Commit uses explicit `git add`, then `git commit --only --` for this path, with the requested
co-author trailer. The resulting hash and document delta are reported at delivery; this path is
released on completion.

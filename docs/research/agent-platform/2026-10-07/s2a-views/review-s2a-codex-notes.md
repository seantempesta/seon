---
type: review
status: changes-requested
date: 2026-10-07
lane: review-s2a-codex
---

# S2a design review — Codex

Review lane: `review-s2a-codex`. Design: `design-notes.md`, committed in `2c36b20c2`.
Verdict: **revise before implementation**. The deletion is the right composition, but the proposed turn label
does not follow from the recorded facts, and the stated surviving allocation is not an acceptable landing budget.

Root's decisions are applied: derive kind from the turn's facts; delete the named golden with its session-view
subject; keep system-turn displays and all three controls until S4. None of those decisions is reopened here.
No design, source or test was edited. No test run, provider call, restart, reset or worktree was made.

## Findings, ranked

### P1 — No attempt does not establish a virtual turn

The design explicitly leaves this assumption unverified. It is false on the installed call path.
At the design's pinned `ec5f13d2f`, `src/seon/turn.clj:4526-4576` derives the prompt refusal, attempts the capture
write, then handles either refusal with `fail!`. That closure (`:4415-4427`) settles the run and reports an error.
Neither branch enters `ai/complete` (`:4579`) or `record-attempt!` (`:4594`). The comments at `:4530-4536`
even distinguish capture-without-attempt from an observed call. An open provider turn is another ordinary
no-attempt state; a crash or a refused attempt write can also leave no durable attempt. The attempt writer's
contract expressly returns transaction refusals. “Every completed call attempts to record” is not “every
provider-enabled :call has a durable attempt.”

The existing first-party proof `seon.turn-test/agent-budget-counts-a-call-that-closes-before-the-provider`
(`test/seon/turn_test.clj:100`) already treats pre-provider closure as real work. It tests accounting, not the
ledger label, so it cannot substitute for the new label regression.

**Smallest correction, still option (b):** remove the opening-database/config reads, and show the observed fact
“No provider attempt” for an otherwise ambiguous `:call` without attempts. Keep “Provider” when attempts exist
and the existing system classification. If “Virtual” must remain, require positive own-turn evidence and prove
that evidence for open, refused, interrupted and empty replies first. Do not infer it from absence, add a dial
lookup, or preserve the expensive historical config read under another name. An empty reply alone is not a
general proof: a provider can return one and fail to record its attempt.

**Regression:** add no-attempt cases to `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments`,
asserting the rendered card's `:data-turn-kind`. Include an open call and a closed pre-provider refusal, beside
provider and system cases. Exercise the real owners with an injected world; no live paid call is needed.
Related task: `seon.render.transcript/turn-kind-reads-each-call-turns-opening-database`.

### P1 — The proposed landing remains over budget; the narrow read is another contributor

Section 5 proposes approximately 650 MB per request and explicitly leaves the 345 MB emission conversion and
186 MB effect acquisition. The approved spec §1.8 and lane rules say non-agent, non-provider work above 10 MB is
stop-and-fix. Calling these costs pre-existing and filing tasks does not grant the exception. The estimate also
does not prove the no-cache implementation beats both measured cold and warm requests.

Independent installed probe, without executing any model turn: **121 root evaluations, 15.577583 ms,
40,873,992 thread-allocated bytes**, default basis `536871355`, using exactly the proposed selector without
read evidence. Thus the read itself exceeds the budget before either of the two named survivors. This confirms
the design's narrow-selector order of magnitude, not its estimated complete-request cost.

**Smallest correction:** preserve the deletion, but make landing depend on measured acceptable acquisition and
rendering costs, with named owners for the producer fixes. Do not add a cache to hide the work. If root intends
to permit a temporary over-budget deletion slice, that must be an explicit exception, not an implied result of
this review. Measure initial page and one-card expansion at 1/10/200 exchanges on identical parent/change inputs;
keep acquisition, page rendering, and HTTP serialization separate.

Existing tasks: `seon.repl/entity-emission-allocates-megabytes-per-evaluation` and
`seon.render.transcript/ledger-effects-allocates-per-turn-interval`. New measured class:
`seon.eval/narrow-history-read-allocates-over-ten-megabytes`, writer-derived handle `17390920f38d`.
Its declaration names the current ordering proof and asks for the narrow-selector case and profiling evidence.

### P2 — “Linear in the agent's evaluations and turns” misses whole-program and cross-product work

At `ec5f13d2f`, `transcript.clj:1672-1696`, `ledger-effects` reads every function/test definition in the database,
then the nested `for` checks every definition against every closed-turn interval. That is O(T × D) host work
after the query, where D is the program's definitions, not this agent's evaluations. The message query also
binds intervals into the query. The existing task's phrase “as the definitions branch already does” describes
a supposed sorted pass that is **not present**: that branch is a Cartesian iteration followed by filtering.

`repl/entity-emission` (`src/seon/repl.clj:523-540`) issues a history query for each evaluation to derive
`changed?`. Removing the outer walk does not remove these N reads. `emission-label` scans projection shape rows
for each evaluation with a renderer (`transcript.clj:1386-1399` at the design revision); not every history is
the measured all-ordinal root history. These facts limit the claimed complexity and the proposed cost estimate.

**Concrete simplification:** `render-ledger` already expands only the selected and last three cards
(`transcript.clj:2232-2234`). Headings, strip marks, summaries and problem rules use saved facts, outcomes and
labels, not `::emission`. Defer `entity-emission` to the displayed body through the existing converter instead
of eagerly attaching it to every evaluation in `ledger-data`. Preserve all system-turn grouping and problem
checks. For the effects producer, read each fact family once, then associate facts to intervals with an ordered
pass that preserves overlapping intervals if those are admitted. Do not merely rename the nested loop.
This is a smaller algorithm, not a new subsystem. Profile it before promising a saving.

### P2 — The new acquisition's error union needs its callers converted explicitly

The proposed `ledger-data` returns `of-agent`'s native no-such-agent/read refusal. Today's `render-ledger`
(`transcript.clj:2224-2244`) and `render-ledger-turn` (`:1865-1877`) recognize the old adapter's
`:seon.render.web/refused-member`, while `ledger-entities`/`ledger-effects` have additional read refusals.
`walk/history` currently performs that translation (`src/seon/render/walk.clj:865-873`); removing it removes
the discriminator the renderers inspect. The survivor table says “ledger-data” without spelling out this
necessary conversion. A raw refusal cannot be destructured as an empty `{::rows ::evaluations}` success.

Specify short-circuiting each read refusal before grouping or enumerating rows, and displaying it at both
surviving entrances. `of-agent`'s no-such-agent map is distinguishable today by
`:seon.agent/no-such-agent` (`src/seon/eval.clj:42-49`); it does not stamp the target error type itself.
Use the installed union, not a future errors-spec assumption. Do not invent another wrapper/error family.

Add a refusal case through the public ledger renderer and the surviving card response; assert the refusal
content as data and no misleading empty/success card. This is also where a missing turn in `render-ledger-turn`
must stay distinct from an empty recorded turn. The HTTP ownership check stays in `debug-turn-response`.

### P2 — Test conversion must follow S1's deletion and the counter's actual scope

Both `seon.sci.eval/directory-value` and `seon.repl/render-directory-ai` are armed in default: their function
metadata carries `:malli.instrument/original`. `seon.render/acquire-context!` is armed too. Therefore the
design's “if armed, otherwise keep with-redefs” question has an installed answer for the current snapshot.

But S1's current design removes the two directory Vars. An S2a test converted to count those names before
S1 lands will fail after S1, because `support/armed-counts` refuses absent cells
(`test/seon/test_support.clj:698-720`). Coordinate the shared `web_debug_test.clj` conversion by behavior:
passive saved-history rendering must not execute the lookup or its old renderer; do not retain retired Vars to
count them. S1 and S2a both identify this exact test.

The helper sums **JVM-wide** host and SCI cells, including other threads (`:698-731`), not calls belonging to
the fixture. Root must run the zero-acquisition proof without concurrent use of that Var or provide an existing
scoped observation. A false nonzero under concurrency is not an S2a regression. The proposed effects assertion
also still asserts prose inside `::details`; prefer the underlying effect facts, alongside attempt-usage data.

## What the design got right; deletion/caller audit

- The agent route and Datastar branches are in `web/debug-response` (`web.clj:3548-3675`); the session,
  outline, captured-prefix and walk-graph closures named in §2 have the production callers the design lists.
  `debug-turn-response`'s ownership query must remain. No additional live external caller to the deleted entry
  points was found in `src test resources .agents docs/seon`; the schema pair and two task references are real.
- `web/derive-context!` really embeds ledger derivation under `::transcript/ledger?` (`:2520-2527`). Deleting
  that branch and directly reading saved facts removes an accidental prompt dependency. It does not create a
  second evaluator, recorder or loop. Retain `repl/entity-emission` and its HTML owner rather than copying them.
- `of-agent` explicitly accepts a selector, restores evaluation id/run/ordinal, and orders by turn transaction
  then ordinal (`src/seon/eval.clj:17-75`). The narrow selector and grouping preserve those relationships;
  removing read evidence from it is correct. Nil, absent, failed and unfinished evaluations still need their
  current distinctions. `:seon.eval/shown` containing an empty string remains a present result.
- The value schema `:seon.render.transcript/prefix-subject` is installed and names the captured-prefix AI
  renderer. Remove it with that function and prove publication/adoption on a live-store branch. This review's
  schema lookup confirms installation, not adoption of its future removal.
- Keep `walk/graph`/`related`, entity inspection, preview experiments and their shared helpers. They are not
  dead just because the dedicated walk-graph page is deleted. Those retained preview paths also mean this slice
  cannot claim to eliminate every parallel render experiment; the narrower S2a closure is appropriate.
- Keep system-turn/churn/stale-but-unchanged displays and System turn/Virtual turn/Compact actions until S4.
  Delete contributed-byte bookkeeping without redefining it as emission text size. Update strip legend,
  title, docstring and churn prose as well as the numeric keys; otherwise the UI still advertises removed widths.
- Delete the owner-approved golden and its now-orphaned helpers with the session subject. No replacement of its
  obsolete raw-session assertions is required. Retain current accounting/system-turn behavior tests until S4.

Line references above are the design's `ec5f13d2f` unless noted. S5a's uncommitted transcript edits shift later
lines; for example `turn-kind` is `:1079` in the reviewed working tree versus `:1061` in the design revision.
The deletion counts and CSS estimate are estimates, not an applied-patch proof. I did not regenerate CSS or
apply walk-delete's patch, so exact final line counts, selector closure and browser paint remain implementation
checks. The design's historical HTTP timings were not independently rerun; §5's “after” numbers are estimates.

## Library seams and the smallest composition

Checked gitlinks and checkout revisions: Datahike `e5453d967b749794944dbdfe64b751519983ba59`, Malli
`43c6dca8d2d8df3162b13725939954807be4a4ed`. The skill's older Datahike pin is not this checkout's pin.
Datahike `pull_api.cljc:315-357` expands only the selected attributes and explicitly selected subpatterns;
an unselected read-evidence attribute is not decoded by pull. Its `pull-many` (`:541-555`) is eager and
input-aligned, sharing the pull plan. Use Seon's existing read owner for that seam, never raw dependency calls
in the renderer. Malli `validator`/`explainer` (`core.cljc:2672-2704`) cache on compiled schemas; no new
validator/cache is needed for ledger data. Contract compilation uses its existing `function-schema` path.

The happy-path step served is inspection of recorded evaluations. The smallest composition remains deletion
plus `of-agent` → saved facts → existing REPL display owner. It lets the ledger stop building an unused prompt.
No new registry, persisted kind, cache, parser, evaluator, history service or generic view framework is justified.
Retained `readable-shown` in system-turn problem checks is a real secondary shown-text reader, but it belongs
to the S4 retirement boundary root has retained, not a reason to import walk-delete wholesale now.

## Cross-design reconciliation

| Neighbor | Concrete overlap / boundary |
|---|---|
| walk-delete | Its B1 deletes system displays and actions; root has explicitly rejected that scope for S2a. Reuse only the narrowed data shape. Its old capture/seed decisions are superseded by approved agent-loop §4.4 and S4. |
| repl-lookup / current s1-lookups | Shared `web_debug_test.clj` and passive directory-history proof; coordinate the deletion of directory Vars and retained saved text. S2a's fallback ordinal label must tolerate a deleted renderer's historical symbol without invoking it. |
| loop-pass | There is no current design note in its directory, only run evidence; `stopped-wip/loop-pass.patch` edits loop/bootstrap/turn and introduces its own transcript composition. Do not import that composer. S4/S5/S6 and the existing printer are the authority. Keep S2a off those files. |
| reader-prose | No current directory/design note; the stopped patch edits batch/forgive, includes repair/recursive segmentation. Approved §4.3 supersedes that approach. S2a renders saved source/comments and must not normalize them or parse model replies. |
| s5a-reply | Owns `transcript.clj` and several neighboring runtime files, with uncommitted fault/reply work. Disjoint hunks are not disjoint ownership. Wait for release; do not copy its held-entry/error conversion or independently redefine reply envelopes. |
| s3-prep | Its ordered-history/origin work is a later change to history acquisition. S2a should retain the current `of-agent` owner rather than invent a renderer-side index that S3 must replace. |

## Live evidence and limits

Default at review start: committed archive `ec5f13d2faf8eeb332c4e4adea43ba845e26d027`, pid `75294`, started
`2026-10-07T11:57:29.639Z`. Both status tools answered; health was **faulted**, not healthy: three open fault
signatures, ten errored receipts, five unowned namespaces, five over-bound warnings. Heap-used differed across
the two reads (988,536,056 / 1,294,953,088 bytes); these are observations, not allocation measurements.

Named existing findings from those tools: `seon.turn.world/fault-recording-refuses-cross-branch-custody`
(three occurrences); `seon.profile/scope-wrappers-report-callback-time-as-work` and
`seon.schema/the-projection-travels-ambiently` (inclusive max 145,475 ms, not proven self cost);
`seon.fn/analyzed-files-takes-two-seconds-per-call` (max 2,365 ms);
`seon.cluster/publication-tests-cost-35-seconds` (observed max 3,971 ms);
`seon.cluster/adoption-readopts-what-the-jvm-loaded` (max 1,640 ms).
`bin/seon status` additionally reports no completed maintenance receipt for this root; existing maintenance
tracking: `seon.maintenance/collection-marks-slowly-and-records-nothing`. No recovery action was taken.

All review probes named agent `review-s2a-codex`, cluster `default`; final parent-side cleanup is recorded below. JVM reads passed
`(seon.cluster.boot/connection "default")`; no source was loaded from the working tree. SCI probe:

```clojure
(in-ns 'scratch.review-s2a-codex)
{:surface :sci :review :s2a :value (+ 1 2)}
```

Returned the namespace in 0 ms and `{:surface :sci :review :s2a :value 3}` in 1 ms, handles
`result/e207d19c721ad`, `result/ebef93cc8b15a`, without diagnostics. These are evaluated-form timings,
not complete MCP-pass timings.

Installed turn census, narrow pull of `:call` turns: 24 closed turns with a reply and zero attempts; one closed
turn with reply and one attempt. It proves available stored shapes, not the universal provider assumption.
Handle `result/e60d9e9963628` also records the installed prefix schema and arming booleans. No provider-enabled
failure was deliberately executed. The pre-attempt branches are established by the installed archive's source.
An initial aggregate counted the `get-else` sentinel as one row; that probe was discarded and replaced by
counting pulled attempt refs. It is not attempt-count evidence.

Exact allocation probe (JVM mode, `result/ea556329c0931`):

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      selector '[:seon.cluster.eval/source :seon.cluster.eval/comment
                 :seon.eval/shown :seon.eval/renderer
                 {:seon.eval/renderer-fn [:db/id :seon.fn/sym]}
                 :seon.cluster.eval/error :seon.cluster.eval/output
                 :seon.error/at :seon.error/layer :seon.error/operation :seon.error/data-edn
                 :seon.cluster.eval/read-basis-transaction :seon.cluster.eval/interrupted-at
                 :seon.cluster.eval/diagnostics :seon.eval/duration-ms :seon.sci.eval/ending-ns
                 :seon.print/length :seon.print/level
                 {:seon.cluster.eval/ns [:seon.ns/name]}]
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.getId (Thread/currentThread))
      a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
      t (System/nanoTime)
      rows (seon.eval/of-agent database "root" selector)
      elapsed (/ (- (System/nanoTime) t) 1e6)
      allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) a)]
  {:evaluations (when (vector? rows) (count rows))
   :refusal (when (map? rows) (:seon.error/type rows))
   :ms elapsed :allocated-bytes allocated :basis (seon.db/basis-t database)})
```

No armed-call, store-byte or machinery-commit delta was measured around this read. Do not treat it as a full S0
row. The outer MCP call records its ordinary observation; it is outside the measured expression. No “after”
measurement exists because this is design review only. The measurement returned approximately 50 tokens.

Task creation returned `{:seon.task/id "17390920f38d" :seon.task/changed? true}`, 218 ms / 650 calls.
I corrected its initially misstated test name to the existing ordering test. My first update incorrectly passed
pulled vectors and a digest as the task declaration basis: it returned an input-contract error in 6 ms / 237
calls (`result/e25ae555bc259`). The corrected declaration sets and declaration-valued basis succeeded in
30 ms / 680 calls (`result/e17be177a0ef1`). This was my call error, not a new system defect. The task's final
declaration and this committed note preserve the finding beyond the disposable branch.

## Verification, root's test list, and questions

Lint: a `git archive` candidate of HEAD `2c36b20c2` (the only subsequent local diff is this note), with ignored
dependency lint configuration linked and no cache, ran `clj-kondo --lint src script test --cache false --parallel`:
**5,564 ms, 0 errors, 1,482 warnings**. No foreign working-tree source was included.

Contracts: required the checker from default's committed archive, then called its static `check` with the
candidate root, its carried projection, and the candidate's 475 `src`/`test` files: **1,221.979209 ms, findings
`[]`**, envelope duration 1,229 ms with a delivered `:slow` finding (`result/eb8fc8ba5cce9`). Existing task
`seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing` tracks this producer.
Allocation was not measured on that check. It ran no test bodies. An earlier broader call including `script`
failed on namespace-less `::timeout`, row 35 column 67, with “Use :auto-resolve + :current”; installed
`read-source` still uses edamame (`:90-98`), whereas committed `e843f1d52` changes the file reader. This is a
deployment boundary, not a pass for scripts' contracts or a reason to reload uncommitted code. Canonical census
scope is `src`/`test` (`contracts_compile_test.clj:925-927`).

Root's focused list for the eventual implementation (none run by this review):

- `seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments` (extended kind table).
- `seon.render.web-debug-test/ledger-derives-shared-data-once` (converted saved-fact assertions).
- `seon.render.web-debug-test/passive-ledger-never-reconstructs-directory` (coordinated with S1).
- `seon.render.web-debug-test/blocks-use-the-values-schema-documentation`.
- `seon.render.web-debug-test/saved-history-preserves-shown-text-with-numeric-lookups`.
- `seon.render.web-context-test/selected-session-defers-prompt-acquisition` (zero acquisition).
- `seon.render.web-test/inspecting-the-page-writes-no-run-evaluation-or-fault-facts`.
- `seon.render.web-test/each-agent-has-an-isolated-debug-route`.
- `seon.render.web-test/debug-algorithm-carries-the-render-evaluation-inputs`.
- `seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped` (owns the helper assertion at :621).
- `seon.turn-test/agent-budget-counts-a-call-that-closes-before-the-provider`.
- `seon.run6-stall-test/an-open-session-provider-refusal-is-the-first-problem`.
- `seon.rereads-panel-test/problems-count-every-silent-refresh-from-history`.
- `seon.eval-test/history-orders-turn-transactions-before-ordinals-and-keeps-unfinished-forms`.

Add the two refusal entrance cases described above; root assigns their final test names with implementation.
Exercise Datastar card/ledger URLs and old query fall-through, retain the three controls, and verify browser
paint and schema adoption separately. Do not run deleted view tests or preserve the approved-deleted golden.

Questions for root's reconciliation, not a request to halt this review:

1. Accept “No provider attempt” for the ambiguous own-fact case, or specify positive evidence needed for “Virtual”?
2. Which producer fixes gate the 10 MB limit before S2a lands? The current design cannot claim an exception.
3. Which lane owns the shared passive-history test after S1 and before S2a? A single conversion avoids dead counters.

Design check: deletion holds; eager work, error adaptation and missing attempt semantics need revision. Source/test
delta for this review is **0/0**. Default needs **no restart or reset for these notes**. Foreign boundary:
S5a's uncommitted runtime files were inspected as neighboring work, excluded from the candidate, never modified.

Cleanup: SCI `(let [branch (:my.agent/own-branch (my.agent/branches))]
(my.agent/retire-branch! {:seon.store/branch branch}))` returned
`{:seon.error/message "run transition refused: admission-closed"}`, MCP `isError true`, with no timing or handle.
Existing tasks: `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` and
`my.agent/a-refused-retire-leaves-the-agent-admission-closed`. The public retirement owner was then called in
parent JVM mode as root, with default's connection, store and routing explicitly supplied, and the branch read
from the review agent's own record. It returned
`{:seon.store/branch :agent-39ecb2e08caf :my.agent/retired? true}` (`result/e2f127e5ef210`). No other agent or branch
was retired. The owned static-check archive was removed; the evidence remains above. No test JVM or shell remains.

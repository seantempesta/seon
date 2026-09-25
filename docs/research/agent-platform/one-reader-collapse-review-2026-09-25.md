---
type: review
status: ACCEPT-AFTER-FIXES
created: 2026-09-25
reviewed: docs/research/agent-platform/one-reader-collapse-2026-09-25.md
reviewed-commit: 9ecc5b708dac5ef0f99083074fefa5209799e66f
supersedes-review-commit: 9b78e96c4
scope: read-only source and design review; one review document; no tests or runtime operations
---

# One-reader collapse review

**Verdict: ACCEPT-AFTER-FIXES for the narrowed Option A.** S1's dead-output
removal and S2's edamame EOF producer repair remain sound in scope. S3 should
use the evaluating SCI context, but its event-flow description and once-per-read
options proposal are not correct as written. Correct the five items below in
the design before implementation. B remains blocked; C, the coordinate change,
fence ownership, recovery optimization and error-fidelity work are deferred,
not approved implementations by implication.

The revision resolves the earlier proposal's largest mistakes: it retains the
candidate-isolation input, rejects whole-text fence blanking, preserves source
origin coordinates, recognizes edamame's namespace parser, and calls repeated
reads repeated reads. This is enough to avoid another wholesale REVISE verdict.
The remaining changes can be made locally in the design without a new parser,
cache, registry or cross-owner redesign.

Proposal citations below refer to **9ecc5b708**. The reviewed proposal in the
checkout matched that commit. The reader, SCI evaluator, reply, turn and MCP
bridge files cited below also matched that commit when inspected. Dependency
sources were inspected at the recorded gitlinks: edamame **63373df6**, SCI
**409888f6**, and clj-kondo **57252e07**. These are source guarantees and static
predictions, not evidence of installation or new test results.

## Required fixes

1. **Describe the actual supplied-event path; do not prescribe an automatic reread.**
   `docs/research/agent-platform/one-reader-collapse-2026-09-25.md:218`
   says planning events are read again by `one-event` in the current
   `(or event (one-event …))` flow. A supplied event short-circuits that call:
   `src/seon/sci/eval.clj:4203` and `:4452`. The ordinary reply path actually
   drops the planning form and carries source/comment/namespace
   (`src/seon/cluster/reply.clj:214`); `evaluate-for-install` reads that source
   once, then supplies its event to `evaluate` (`src/seon/sci/eval.clj:4464`).
   The other production supplier is the no-forms error at
   `src/seon/turn.clj:4444`, carried through `:4944`; it must remain an error,
   not be reparsed into a different outcome.

   **Smallest fix:** replace the incorrect sentence with that producer/caller
   inventory. Keep the existing event handoff from `evaluate-for-install` to
   `evaluate`; bind the target namespace and pass the SCI context at
   `one-event`. State that an executable supplied event must already have been
   read for the valid execution context; a planning event is not one merely
   because it has the same shape. Preserve the no-forms error handoff. Correct
   the retirement claim at proposal `:298`: planning-form reuse needs valid
   resolver inputs and source coordinates; this slice does not establish them.
   Do not add an unconditional reread, provenance registry or duplicate cache.
   This follows AGENTS.md's parse-once rule and its requirement to validate a
   pre-read's basis at the authority (`AGENTS.md:163`). Later implementation
   evidence must exercise both ordinary installation and supplied errors, as
   well as the direct SCI entrance.

2. **Hoist invariant policy only; preserve evolving planning state and the SCI wrapper's behavior.**
   `docs/research/agent-platform/one-reader-collapse-2026-09-25.md:154`
   and `:218` promise to build options once per read. But `parse-options`
   closes over immutable namespace, alias and refer values
   (`src/seon/sci/reader.cljc:106`). `next-reading-context` changes them after
   `ns`, `in-ns` and literal `require` (`:462`), and the current loop supplies
   the new state on every parse (`:586`, `:658`). A single resolver built from
   initial inputs would keep resolving later forms in the old namespace or
   with old aliases. For example, `(ns probe.a)` followed by `::k` must use
   `probe.a`, not the initial `user`. This is a source-derived counterexample,
   not an executed probe.

   The new direct-edamame context-free path also has more to preserve than
   option normalization. SCI supplies `:all true`, `:read-cond :allow`, location
   key/predicate settings and disabled end locations
   (`reference-code/sci/src/sci/impl/parser.cljc:44`), adjusts top-level symbol
   metadata (`:171`), and wraps parser failures (`:180`). Normalizing Seon's
   current policy map alone does not reproduce that behavior; edamame defaults
   its location keys differently (`reference-code/edamame/src/edamame/impl/parser.cljc:968`).
   “The JVM MCP read is unchanged” is not established by keeping its resolver.

   **Smallest fix:** limit immediate S3 to context-bearing execution reads.
   Keep the existing context-free SCI wrapper and state-dependent options for
   JVM/reply callers; defer replacing that wrapper until its forms, metadata,
   errors, source and policy are accounted for. Hoist only invariant policy in
   this cut, without a mutable resolver cell. Thread the new context through
   `read`'s explicit input reconstruction (`src/seon/sci/reader.cljc:867`) as
   well as `read-events`; adding it only at the caller would discard it.
   Recalculate S3 and total line estimates after this narrowing. The execution
   regression matrix already named by the proposal remains appropriate; add
   preservation of multi-form namespace/alias planning to its later evidence.

3. **Retain the honest heuristic, but remove the new unsupported recovery guarantees.**
   `docs/research/agent-platform/one-reader-collapse-2026-09-25.md:195`
   promises that every character belongs to exactly one event. Comment-only
   or whitespace-only input returns no events (`src/seon/sci/reader.cljc:606`).
   There can also be a gap before a recovery error: fences are skipped before
   `::failure-start` is recorded (`:581`, `:595`), while `error-event` starts
   at that post-fence offset (`:833`, `:846`). Original text remains available,
   but a complete partition of it into events is not an established invariant.

   Proposal `:207` calls reusing the same reader the smallest cost fix and
   says whole-text coordinates make it possible. For EOF failures, edamame
   has already consumed to EOF (`reference-code/edamame/src/edamame/impl/parser.cljc:298`)
   before Seon chooses an earlier column-zero anchor (`reader.cljc:799`).
   `advance-to!` only moves forward (`:528`); it cannot return that reader to
   the chosen anchor. Absolute coordinates do not provide rewind.

   **Smallest fix:** promise ordered emitted spans and strict recovery
   progress, explicitly retaining original text for trivia/gaps. Remove the
   complete-character-partition claim. Keep the O(k·n) diagnosis and R4's
   pending measurements; delete the assertion that same-reader reuse is an
   available repair. Sharing the line-start index removes repeated index
   construction but does not by itself remove repeated parsing or advancement.
   Leave recovery unchanged in A. Any later optimization must identify a real
   dependency seam and preserve the chosen heuristic; do not invent a seek
   abstraction. Correct the existing “proven top-level anchors” docstring at
   `reader.cljc:825` in the eventual source cut. This review changes no source.

4. **Do not report the SCI defect as established for the current JVM MCP entrant.**
   `docs/research/agent-platform/one-reader-collapse-2026-09-25.md:36`
   says core Vars and classes are misqualified both in agent execution and JVM
   MCP mode. The latter claim does not follow from this source. The bridge
   supplies namespace mappings for both Vars and classes
   (`script/seon/dev/mcp.clj:357`), and the hand resolver checks those mappings
   before qualifying an unknown name (`src/seon/sci/reader.cljc:97`). Thus the
   bare-symbol fallback does not demonstrate that current JVM `inc` or `String`
   is misresolved. This also contradicts the proposal's own context-free
   promise at `:182` and its correction of the historical bridge probe at `:299`.

   **Smallest fix:** restrict the demonstrated diagnosis to SCI execution
   and context-free reads lacking the needed mappings. Mark current JVM MCP
   misqualification unproven unless a distinct exact input demonstrates it.
   Keep JVM compatibility as a regression requirement; do not change the bridge
   to repair a defect that has not been reproduced there.

5. **Label partial probe evidence precisely and keep missing evidence pending.**
   `docs/research/agent-platform/one-reader-collapse-2026-09-25.md:61`
   and `:329` call §10 the exact forms as run. It contains two helper definitions,
   but E3 has `<as above>` and `<refuse>` placeholders, E4 uses an unspecified
   `t`, and the reply comparison/timing work is summarized in comments
   (`:355`). Complete result envelopes and per-input comparison outputs are not
   retained there. The revised distinction between parsed construction code
   and evaluated values is good; it does not supply those missing artifacts.

   **Smallest fix:** title §10 as retained helper definitions and probe
   sketches, identify which exact invocations/results are unavailable, and keep
   R1–R5 plus the committed measurement script as future evidence. Do not claim
   they have been satisfied or invent the missing envelopes. No rerun is needed
   to complete this read-only review. R3's future equivalence should retain
   regex flags as well as pattern text and compare metadata/source positions
   separately; normalization must not hide the semantics being preserved.
   Before the hot-path implementation lands, the script must record comparable
   armed parent/change timings and allocation/memory, not infer improvement
   from the current armed-versus-unarmed observations (`AGENTS.md`, laws for
   proof and performance).

## Disposition of the ten previous REVISE findings

These are design dispositions, not claims that existing defects are repaired.

| Prior finding | Revised proposal and judgment |
|---|---|
| 1. Candidate isolation and receipt implication | **Addressed by retaining the facts and blocking B.** Proposal `:240` names the isolation caller and both required conversions/proofs. No premature declaration deletion is approved. |
| 2. Source origin versus form location | **Addressed by rejecting the coordinate change.** Proposal `:232` preserves admission's origin and defers additive form coordinates with an actual-admission proof. |
| 3. Fence blanking corrupts literals | **Addressed by rejection.** Proposal `:223` keeps between-form skipping and the literal regression; there is no whole-text blanking in A. |
| 4. Execution/planning/JVM resolution | **Partly addressed; fixes 1, 2 and 4 remain.** SCI execution resolution is the right seam. Shared empty-context resolution is rejected, but the new event-flow and option-hoisting descriptions need correction. |
| 5. Recovery heuristic and cost | **Partly addressed; fix 3 remains.** Proposal `:187` withdraws top-level proof and states O(k·n), but substitutes an unsupported coverage guarantee and rewind assumption. |
| 6. edamame namespace parser | **Addressed for A's scope.** Proposal `:155` and `:262` name pure `parse-ns-form`, its limits and a comparison prerequisite for C; the unsupported deletion estimate is withdrawn. This is not approval to implement C. |
| 7. Prose evidence, classification and causes | **Addressed as retained work.** Proposal `:223` rejects token-only prose equivalence; `:273` admits guessed kinds and missing causes. E still needs its owning contract and implementation review; it is outside A. |
| 8. Scattered reads and stale bridge inventory | **Addressed at the inventory level.** Proposal `:292`–`:299` includes supervision and arglist consumers, historical declaration values, and the already-deleted bridge reader. Installation remains unverified. |
| 9. Shared parser is not parse-once | **Acknowledged, with retirement clarification in fix 1.** Proposal `:297`–`:298` admits rereads, and `:253` preserves actual-binding versus advertised-arglist comparison. A is an incremental correction, not completion of the one-reader collapse. |
| 10. Parse forms, evaluated values and retained probes | **Partly addressed; fix 5 remains.** Proposal `:44`, `:114` and `:108` correct the value/form distinction and comparison limitations. The “exact forms” label still overstates retained evidence. |

## Supported scope and later proof

S1 removes outputs with no surviving production consumer; the source-key search
still leaves `:seon.fn/sym` live. Preserve namespace/declaration behavior while
deleting obsolete output assertions. S2 fixes edamame `read-number` at its
producer (`impl/parser.cljc:192`), matching the existing nil guard in
`read-token` (`:108`). The fork commit, gitlink, loaded classpath and EOF/source
logging evidence must converge before the downstream workaround is removed.
Neither finding requires a second reader implementation.

SCI's `fully-qualify` (`reference-code/sci/src/sci/impl/parser.cljc:60`) and
`auto-resolve` (`:126`) are the supported execution seams; explicit policy is
merged last at `:167`. Binding `sci/ns` in `one-event` places the namespace at
the reading boundary, including its non-turn callers. Preserve the feature set,
EOF sentinel, tag/read-eval refusal, source-origin metadata and candidate
isolation. Do not drop terminal trivia handling with the EOF-number workaround.

B and C remain outside this acceptance. Recovery policy D is not a new owner
ruling, and E's line estimate is not a reviewed cause-chain contract. If either
becomes an assignment involving new cross-owner decisions, follow AGENTS.md's
priced-option and review requirements then. The present smallest composition
is deletion, one dependency producer repair, and use of the existing SCI
execution resolver with the current planning/JVM adapter retained.

## Review evidence and limits

Read the revised proposal and all ten prior findings, AGENTS.md, and the
data-oriented Clojure skill. Inspected the dependency readers and their options,
namespace parser, EOF producer, first-party event producers/consumers, recovery,
source-coordinate admission, signature comparison and relevant existing test
source. Tests were read, never executed. Checked the target commit, dependency
gitlinks, source differences and supplied-event/dead-output key searches.

No Clojure evaluation, test, provider call, JVM load, publication, adoption,
reload, stop or reset was performed. No installation claim is made. Runtime
timing, memory and allocation were **not measured**. Reported benchmark values
remain historical observations. No shared-tree load was attempted, so foreign
breakage is neither diagnosed nor used to stop this review. Concurrent changes
outside this document were left untouched.

Changed/released path: this review only. Net production source/test lines:
**0 / 0**. The prior review remains available at **9b78e96c4**; this document
replaces it in place. Root owns the design ruling and later implementation
acceptance. This review does not authorize a semantic sacrifice or merge.

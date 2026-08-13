---
type: prd
status: active
tags: [prd, orchestrator, runtime]
---

# Overnight plan — 2026-08-03

## REVISED ORDER — owner-ruled 2026-08-03 night (rulings #49-#50)

The owner ruled live at session open; this section supersedes the tier
sequencing below where they differ. Lane cap: 3 implementation + 1
research. No Fable subagents — opus and sol lanes only.

EVERY LANE SPEC NAMES ITS SKILLS AND ITS REFERENCE SOURCES
(owner-directed 2026-08-03): the launch prompt lists the
`.agents/skills/` skills the lane must invoke before specialized work
(flow work → `seon-flow-architecture`; any Clojure →
`data-oriented-clojure`; schema/query → `data-modeling` + `datahike`;
tests → `clojure-testing`; eval/reader → `repl`), AND the exact
`reference-code/` trees that own the seams it is writing against
(core.async flow.clj/impl.clj/spi.clj for procs and buffers; datahike
writer/writing/versioning for store semantics; sci core/interrupt for
the eval boundary; malli for schema properties; http-kit for the SSE
write path). A lane writing at a seam it has not read the dependency's
own source for is the known integration-bug generator.

1. IN FLIGHT: `suite-speed` (tier 0, a FIX is expected, not a
   diagnosis — ruling #49), `union-codec` (1A, decode EVERYWHERE
   pre-authorized, blast-radius table for morning review),
   `history-integration` (3C's six parts, authorized, live proof both
   ways), `render-simplification` (research: ruling #50's audit).
2. QUEUE for freed implementation slots, in order: the alter-meta!
   immutability SCI fork change (authorized); the blob threshold
   derivation (derived comparison, never a tuned constant); then THE
   QUIET WINDOW — pause all lanes, one lane runs the seon.db call-site
   sweep (3A) alone, full-suite gate, release.
3. AFTER the sweep: MCP value chain (3B). Its admit seam is drafted by
   the uncommitted renderer-kernel prototype
   (`tmp/renderer-kernel-prototype-2026-08-02.patch`); the 3B lane
   adopts the `admit-value` half explicitly; `eval.clj`'s
   invocation-arm half stays parked for render.
4. RENDER IMPLEMENTATION IS AUTHORIZED (owner, later same night) and
   the gate document LANDED: `research/render-simplification-audit-
   2026-08-03.md` (fc05ed9b2) — keep/kill/absorb table, nine
   fail-first falsifiers, exact 9-step landing order. Orchestrator
   dispositions of its three open points, from its own recommended
   constraints: (a) owning namespace only from an explicit ref on the
   data or traversal edge, else schema property/floor, never keyword
   text; (b) agentless-namespace stakeholder fan-out waits on the
   missing fact — owner-agent notification proceeds; (c) loading
   derives from repair-acceptance events; where that fact is absent,
   honest unavailable. Render starts after the quiet-window sweep,
   follows the audit's landing order, commits every step.
5. Hygiene now standing (ruling #49): dead test-run roots reaped (3.9
   GB → 384 MB), default root reset and rebuilt from current source,
   dead experiment clusters destroyed. bin/test gains bounded reaping
   via the suite-speed lane.
7. LATE-NIGHT STATE: ruling #41 CLOSED — the sweep landed in 18
   path-limited commits (ending `cced8d9a9`), zero non-exempt
   datahike.api calls, exemption census exact (68 calls / 10
   namespaces), live SCI ambient proof green, full suite 883/4,405/0
   in 697.5 s. The history-off graduation is LIVE-PROVEN (completed
   DeepSeek run on a non-temporal root). MCP's value chain landed
   (ruling #44 decisions 1-3 proven). The quiet-gate regressions were
   fixed at cause (`8f8c4d72d`). The parked renderer prototype was
   REMOVED from the working tree after byte-identity verification
   against `tmp/renderer-kernel-prototype-2026-08-02.patch` — its
   admit half lives in the MCP commits; its eval half is reference
   only. RENDER IMPLEMENTATION LAUNCHED as the sole lane on the clean
   fully-green tree, executing the audit's nine steps with full gates
   at steps 3/6/9 and the final live proof.
6. MID-NIGHT STATE (post suite-speed + adversarial pass): tier 0 fix
   LANDED — 586 fixtures each rebuilt the source manifest, ~23 of 36
   minutes; now one immutable in-memory base per JVM, a branch per
   test (c2857ae5c), measured up to 77x per namespace; clean full
   timing comes from the quiet-window gate. The adversarial pass
   (f87169220) CONFIRMED the codec's four read paths, the fork
   closure, and all five fixture-isolation probes, and FILED: the
   process work launcher is REPLACED AND STOPPED by every cluster
   start (blocker — cluster B's boot kills A's accepted work; fix =
   per-cluster launcher, queued first after history-unblock releases
   cluster.clj); two render consumers silently (first) an overlapping
   shapes match (feeds render step 3); one positional schema
   extraction remains; co-hosted clusters share one unbounded heap and
   the compute pool has no per-cluster fairness (ruling #51 census,
   filed). Blob threshold 343 is UNDER REVIEW — the derivation may
   have omitted the window row stored beside every blob and the settle
   fsync; follow-up running before acceptance.

Written 2026-08-02 night, after a session that landed 100+ commits. This
is the ordered work for an overnight run where lanes can go SLOWER and
take their time: deeper reading, more falsification, no rushing to a
commit. Everything here is either owner-ruled or explicitly marked as
needing a decision.

## READ THE WHOLE SPEC. DO NOT GREP IT.

This is the first standing condition because violating it cost the most
time on 2026-08-02. The orchestrator grepped
`render-pipeline-design-2026-07-29.md` twice and got the model wrong
BOTH times; a research lane reported a per-call render cache as "must be
introduced" when it was specified in two places, one of them the
architecture target's own numbered flow. Three separate wrong
conclusions came from partial reads of documents that were correct.

So: when a subject below names a document, READ THAT DOCUMENT END TO
END before designing, deciding, or reporting on it. These specs are
dense and load-bearing — a verdict section, a "differences from the
current pipeline" section, and a numbered owner-decisions list each
carry constraints that a keyword search will not surface. If a document
is long, that is the reason to read it, not the excuse for grepping it.
State in your report that you read it whole.

The specs, by subject:

| subject | read whole |
|---|---|
| render delivery, packages, caching, morphs | `research/render-pipeline-design-2026-07-29.md` (904 lines) AND `docs/seon/architecture/ui.md` |
| the render model as of tonight | `research/render-model-2026-08-02.md` |
| distance, the context walk, namespace membership | `research/context-walk-synthesis-2026-07-31.md` AND `docs/seon/architecture/context.md` |
| custody, isolation, SCI internals | `research/custody-isolation-design-2026-08-02.md` AND `research/sci-var-semantics-2026-08-02.md` |
| storage cost and the validated model | `research/store-amplification-anatomy-2026-08-02.md` AND `research/store-census-2026-08-02.md` |
| the MCP surface | `docs/prds/mcp-surface/README.md` |
| definition-time testing and accretion | `research/definition-seam-design-2026-08-02.md` |
| binding law and rulings | `AGENTS.md` whole, and `plan/README.md` rulings #20-#48 |

Other standing conditions, so each subject need not repeat them: suite
runs isolate per operator root (concurrent runs are fine, keep to two);
no CPU stress tools; use your OWN operator root under `tmp/` for
anything live and take it down after; never touch the default operator
root or the live `default` cluster; commit path-limited and DO NOT PUSH;
cite `file:line` for every claim; mark anything unverified as unverified
rather than asserting it. Stop and report at a real boundary rather than
editing another lane's file.

## The state this starts from

Landed and verified tonight: the reachability fix (agents no longer
receive private vars, live-proven with a real model turn), ctx-derived
custody, the write-door custody fence (`:seon.db/foreign-connection`),
`seon.db` as the one database namespace with dual interfaces, stream
integrity (a malformed chunk fails the completion instead of splicing),
suite isolation and liveness, the `disarm!` readiness protocol, bounded
Flow submission, the analysis-gate fix, 14 `.cljc`→`.clj` conversions,
the store anatomy validated to 0.05%, seven `:db/noHistory` attributes,
and the `:seon.schema/created-at` deletion.

Full suite at the last quiet-tree checkpoint: **840 tests / 4,164
assertions / 0 failures**. Re-run it before trusting anything below.

Rulings #41-#48 in `README.md` are binding. #46-#48 are from tonight and
have not yet been fully implemented — most of the work below is their
implementation.

## Dependency order

Later items assume earlier ones. Within a tier, lanes are independent.

### Tier 0 — the suite takes 28+ minutes (velocity incident)

A lane (`suite-speed`) was launched at session close and may not have
finished. Its brief and the orchestrator's measurements:

- 862 tests / 4,276 assertions. A run reached 642 tests in ~28 minutes —
  roughly **2.6 SECONDS PER TEST**, far too slow for tests that mostly do
  not boot anything.
- **38 full cluster boots** happen during a run: `seon.cluster.boot-test`
  23, `seon.cluster.armed-test` 8, `seon.oversight-test` 2,
  `seon.config-application-test` 2, `seon.cluster.program-restart-test`
  2, `seon.bootstrap-drive-test` 1.
- Across `test/` there are **45 `cluster/start!` call sites in 7 files**;
  `boot_test.clj` alone has 30 and `armed_test.clj` 6.
- A boot is expensive by construction: open the store, fork a branch,
  build the SCI ctx (`cluster-ctx` measured at 636 ms), instrument 447
  vars, start flow graphs and a web service. A fresh isolated root
  reached READY in 1,449 ms.
- So boots plausibly account for only ~60-110 s. **THE BULK IS
  UNEXPLAINED** and that is the real question.

THE OWNER'S QUESTION, which is sharper than the orchestrator's and
should drive the work: **why are we loading every cluster to run the
tests at all?** Part of the answer is legitimate — `boot_test`'s
subjects genuinely ARE boot (process-root store identity,
root executors and their thread kinds, REPL-first under the ten-second
bound, two-instance isolation, stale advertisements, recycled-pid
refusal, delayed stop versus replacement). Those need real boots. But 30
`start!` calls for that namespace's tests suggests some boots are
FIXTURES OF CONVENIENCE rather than the subject, and those are waste.
Separate the two before optimising either.

LEADING HYPOTHESIS for the unexplained bulk, to confirm or refute FIRST:
the per-test database fixture. If every test builds a fresh in-memory
connection AND installs the complete 652-key schema population AND
applies instrumentation, that per-test constant dominates everything
else. Measure one fixture setup in isolation and multiply by the test
count rather than inferring from the total.

PROFILE BEFORE CHANGING ANYTHING, and note the suite's new per-test
BEGIN/END progress lines carry no timestamps — adding them is itself
worth doing, because a suite that cannot say where its time goes will
drift again. FORBIDDEN: deleting or skipping tests, reducing generative
trial counts, weakening assertions, excluding slow tests from the
default run, or sharing mutable state between tests.

### Tier 1 — unblockers (start here)

**1A. The union read-decoding codec.** OWNER-RULED, not started. Mixed
`:seon.render/ai` / `:seon.render/html` unions ride Datahike's
EDN-string fallback: writes encode, production reads do NOT decode, so
a stored qualified-symbol producer returns as a STRING and would render
literally instead of being invoked. Treat this as a CORE SEAM, not a
render fix — every attribute flows through that codec. Establish the
encode and decode sites with `file:line` first (`src/seon/schema/
datahike.clj` plus the read projection and the pinned Datahike
boundary), then determine the BLAST RADIUS: what else is stored through
that fallback and read back as a string today? This is the second
round-trip failure found tonight (the first was print-node versus
semantic value in the MCP work), so check rather than assume it is the
last. Falsifier must FAIL first: store a qualified symbol, read it
back, assert a symbol not a string; then both literal arms; then a
mixed population proven distinguishable. One mechanism — no
render-specific decode path beside the general one.

**1B. Land the `:any`/`:some` audit as a durable issue note.** The
render-vocabulary lane produced it and it currently exists only in a
lane summary, which by this repository's own rule means it did not
happen. 18 schema keys carrying 19 `:any` leaves; 58 `:any` and 22
`:some` in active source; verdicts per instance (delete / replace with
a named schema / tightening-changes-behaviour / genuinely
polymorphic). Named concrete defects: `render.hiccup/raw` returns
`:any`, instrumentation returns `[:set :any]`, database inputs typed
`:any`, Var inputs typed `:any`, and 19 `:some` transaction-data
returns that should reference the existing
`:seon.store/transaction-data`.

### Tier 2 — the render model (needs 1A, and the research map)

**2A. Read the research map first.** The `renderer-kernel` lane is
producing `research/render-model-2026-08-02.md`: every affected area,
what is a rename versus a behaviour change, what must land together,
and its open questions. Nothing in tier 2 starts before that document
is read and its behaviour-change list is ruled on.

**2B. The vocabulary collapse.** ~200 references: `:seon.render/unit`
(83, an umbrella `[:map-of :qualified-keyword :any]`),
`:seon.render/output` (77, `:any`), `:seon.render/hiccup` (39 across 33
functions). Zero functions currently declare `:seon.render/html`
output. Deletions: `unit`, `output`, `literal`. Collapse: hiccup
becomes the DEFINITION of `:seon.render/html`, not a second key. This
is one sequenced wave — a half-renamed tree is the failure mode.

**2C. The guarded render kernel** (ruling #46). One guarded SCI
invocation for every agent-driven render, resolving the declaration in
the cluster's SCI ctx rather than `requiring-resolve`; definitions
installed once, live Var per cache miss; every result through bounded
admission and kind validation. `raw` dissolves — admitted output cannot
carry HTML authority — so remove the false safety assumption at
`src/seon/render/hiccup.clj:68-77`. Delete `namespace-declaration`'s
`render-<kind>` string-building (ruling #47: no naming conventions).
Budget to respect and re-measure: guarded trivial render 10.250 µs p50,
250-event Hiccup 2.448 ms p50, infinite loop interrupted at 13.25 ms
under a 10 ms limit.

**2D. Declared defaults and overrides.** A schema declares its default
render as Malli properties; a thing overrides with its own attribute.
Precedence: the thing, then its schema, then the floor. Both levels are
DECLARED — the contract query over `:seon.fn.arity/input-refs` /
`output-refs` finds and VALIDATES candidates but never silently
selects. No declared default plus multiple candidates is a MISSING
DECLARATION to surface loudly. A declaration must name a function that
actually qualifies, validated against the program graph at declaration
time rather than failing later on a page.

**2E. Render failure surface.** LOADING to the human only while a
repair is genuinely in flight; LOUD and unignorable in
`:seon.render/ai`; the agent is MESSAGED. One repair episode per
CHANGED failure signature; an unchanged failure never loops; then an
honest unavailable state. Closes
`render-resolution-and-feed-swallow-failures.md` and
`error-render-puts-its-own-failure-in-agent-context.md`. Needs
`src/seon/cluster.clj` wiring.

**2F. Remove `421612c26`'s render-specific matcher** once the
open-maps work lands and extra-attribute matching is proven to work
through open maps. Sequence it so we never have neither.

### Tier 3 — completion of settled rulings

**3A. The `seon.db` call-site sweep** (ruling #41's remaining
acceptance). 34 namespaces, 16 direct `d/transact` write sites each
classified individually (runtime / boot / fixture) with its failure
semantics stated, ~58 test files. ONE lane, quiet tree, nothing else
running — it touches nearly every namespace. A full suite afterward is
the proof.

**3B. MCP value chain** (ruling #44) — IMPLEMENTED 2026-08-03.
`seon.sci.admit` now publicly exposes its print-node→semantic
derivation and retains `:seon.sci.admit/print-node`; the artifact
declaration is in `resources/seon/schema.edn`. The corrected design is
ONE SOURCE — store the print node, derive both the drill data and the
result EDN from it. Do not store both; the fidelity falsifier proved
the semantic projection cannot reconstruct the printed form (687,341
characters original versus 302,086 reconstructed). Also needs `/data`
blob selection in `render/web.clj`'s private `data-response`, and the
`valf` projector installed in `cluster.clj` (a bridge-side wrapper
would put the projection into `*1` and break the stateful-session
contract). All named seams landed with the print node as the sole stored
source. Focused gates are green for admission/value, MCP bridge, MCP blob
drill, oversight, and config application. The `/data` artifact regression is
green; its owning namespace remains red only at
`an-agent-page-is-the-same-mechanism-as-root`, an unrelated render-walk lane
assertion. Isolated live proof retrieved the stored remainder by digest and
then read the raw prior result through `*1`.

**3C. Per-cluster history** — in flight tonight; if unfinished,
continue. Owner wants the dial genuinely per cluster (eval clusters
off, user clusters on). Blocked historically by boot-critical
`d/history` calls. Establish first whether `:keep-history?` can differ
per BRANCH in our pinned fork or is store-wide; if store-wide, that is
a finding and the mechanism becomes different (a separate store for
eval roots), to be designed rather than forced.

### Tier 4 — open, lower priority

- The custody residue decisions already made are landed; what remains
  is the standing census catching a function returning a connection
  inside a `:map` contract — a known gap in the check, worth a property.
- `alter-meta!` is process-global from agent code and instrumentation
  reads var metadata: restore immutability or record it as an explicit
  accepted residual with the consequence named.
- The definition-seam accretion feature (`definition-seam-design-
  2026-08-02.md`): candidate contexts now work via the SCI
  copy-on-write change (fork SHA `72150fd44`), so test-before-install
  is viable. The prerequisite remains a test→function call edge;
  `:seon.test` rows carry only `sym`, `ns`, `source`.
- The store's write-amplification options and GC cutoff, both filed and
  measured but unadopted.

## The mixed models to collapse

Owner direction, 2026-08-02 night: "schedule fixes for all the confusing
mixed ideas and simplify everything down." Tonight's render investigation
kept finding the same shape — TWO MODELS COEXISTING where one is a
survivor of a design we already replaced. Each entry below names the
confusion, the ONE model that should remain, and what that means
concretely. Simplification is the deliverable; a lane that leaves both
models in place has not done the work.

**1. THE RENDER PIPELINE ITSELF IS THE MIXED MODEL, and `raw` is only a
symptom.** READ `render-pipeline-design-2026-07-29.md` IN FULL before
touching any of this — the orchestrator grepped it twice and got the
model wrong both times.

The settled design (its own Verdict and owner decisions 1-7): ONE
revisioned composite package per agent page, rendered and serialized
ONCE, carrying both `keyframe-bytes` (one complete Datastar event, every
block) and `delta-bytes` (changed fragments). The render proc publishes
that immutable package through one `mult`; every tab has one sliding-1
tap. A contiguous tab applies the delta; a tab that detects a revision
gap snaps to the keyframe. Equality and delta selection live ENTIRELY in
the render proc. INITIAL PAGE LOAD IS FULLY RENDERED AND CACHED —
decision #7, recommended as cached-keyframe embedding "if it can
preserve one serialization owner" — and thereafter blocks are patched
IFF they change. Stable-ID render-unit fragments are the default
granularity; NO generic server-side Hiccup differ, because Datastar
already computes persistent IDs, matches children, and stops descending
at `isEqualNode`.

THE CURRENT CODE IS THE PREVIOUS INCARNATION. The design's own
"Differences from the current pipeline" section says `web.clj` derives a
complete `{agent-id → {surface-id → html}}` snapshot, mults it, lets
every tab diff against its own delivered map, and performs a SEPARATE
`page-of` derivation PER CONNECTION. The target has tabs hold only a
delivered revision, paints initial feed from the latest package rather
than re-deriving, uses one tap per TAB (not per render unit), and leaves
the writer doing no Hiccup or Datastar framing at all.

So: `hiccup/raw`'s four uses (`src/seon/render/web.clj:1077,1079,1104,
1109`) re-embed already-serialized bytes because the shell is still
assembling pages by hand. Under the settled design the one legitimate
embedding is the cached keyframe in the initial document, preserving ONE
serialization owner. The AI pane (`:1104`) is a SEPARATE question: it
embeds the agent's context TEXT, so if that is plain text then escaping
is correct and `raw` there is a defect, not residue.

Note the design also records that `web/render-step` is tagged `:io`
while it derives and serializes pages — the design calls that
current-state evidence, not the target, since SCI render, admission,
serialization, equality and framing are all `:compute` work.

**2. Renderer identity: output type versus declaration.** Identifying a
renderer by "returns hiccup" makes helpers (`slot`, `expand`,
`emit-hiccup`) renderer candidates. ONE MODEL: a function is a renderer
because something DECLARED it one; the output type is a VALIDITY CHECK on
that declaration, never the identity. Helpers are never declared, so they
never qualify.

**3. Renderer resolution: four paths.** Today: explicit attribute, the
`render-<kind>` NAME CONVENTION, schema shape-match, floor. ONE MODEL,
all levels DECLARED, most specific first — explicit keys on the data
(any data, regardless of its schema), then the owning namespace's
renderer, then another namespace's renderer ordered by DISTANCE, then the
schema's default property, then the floor. The name convention is
deleted (ruling #47).

**4. Distance: two jobs, one word.** `:seon.render/distance` today
governs the context WALK — ref hops, spent per connection, one default
site at `src/seon/render/block.clj:168-178`. The renderer hierarchy needs
distance between NAMESPACES. DECIDE AND RECORD whether that is the same
measure reused or a second notion that needs its own name, and say what
actually measures namespace distance (the require graph? shared prefix
depth? something already in the program graph?). Two unrelated namespaces
both declaring a renderer must be orderable, or the hierarchy has a hole.
Owner says this was spec'd; find the spec before designing.

**5. Render caching: it is DESIGNED, not missing.** The render research
reported "a real per-call cache must be introduced"; that is wrong, and
so was the orchestrator for repeating it. The pipeline design specifies
a SINGLE-WRITER LATEST-PACKAGE SNAPSHOT owned by the render proc
(required because vendored `mult` does not replay to late taps —
REPL-confirmed in the design), plus an equality cache, plus the explicit
rule that initial paint and the cached keyframe REUSE CACHED BYTES
RATHER THAN CALL THE RENDERER. Both are "disposable derived memory, like
equality suppression — not a database fact, replay log, or second render
owner." ONE MODEL: reuse that. Do not build a second cache.

The genuinely open question is narrow: the design assumed COMPILED
renderers, and under ruling #46 every render becomes a guarded SCI
invocation whose declaration may resolve differently per cluster. So
does the equality key (the actual serialized fragment bytes) still hold,
and does the snapshot need to be per cluster? Answer that against the
design rather than inventing around it.

**6. Closed maps masking schema debt.** Ruling #48 opened the maps and
immediately exposed a forbidden `:any` in `:seon.ai/usage` that broke
publication, plus six unions that relied on closedness to be
distinguishable. ONE MODEL: declarations are honest and open; ambiguity
is resolved by an EXPLICIT DISCRIMINANT, never by forbidding extra keys.
Two issue notes carry this —
`closed-map-contracts-survive-outside-schema-population.md` (inline
`{:closed true}` in `:malli/schema` metadata, so the migration is wider
than the 172 in the schema resource) and
`map-unions-have-no-explicit-discriminants.md`.

**7. Anonymous and `:any` contracts.** 18 schema keys carrying 19 `:any`
leaves; 58 `:any` and 22 `:some` in active source, each verdicted. ONE
MODEL: a named schema for every shape that has one; `:any` only at a
PROVEN polymorphic boundary. Concrete: `render.hiccup/raw` returns
`:any`, instrumentation returns `[:set :any]`, database and Var inputs
typed `:any`, and 19 `:some` transaction-data returns that should
reference `:seon.store/transaction-data`.

**The test for every one of these:** after the change, can a reader name
ONE model and find ONE mechanism implementing it? If both models still
exist — even with one marked deprecated — the simplification did not
happen. Git is the archive; delete the superseded path in the same
change.

## What needs the owner, not a lane

- The render research map's BEHAVIOUR-CHANGE list (tier 2A) — renames
  are safe, behaviour changes are not, and only the owner should rule
  on those.
- Whether the union codec fix (1A) reveals other affected attributes
  that change semantics.
- Whether `alter-meta!` immutability is restored or accepted.

## The epistemic warning for whoever runs this

FIVE recorded figures were disproven tonight, four of them in storage
alone, and one of those by our OWN fresh measurement hours later:
"86× amplification" was a misreading (growth is linear in payload,
quadratic in commit count); "42 MB per sample" summed overlapping
intervals (real: 9.793); "1.5 MB per transaction" was false; and a
per-attribute census attributed 187,360,394 B to `created-at` while
deleting it saved 9,661,654 B — a nineteenth — because ATTRIBUTION IS
NOT A COUNTERFACTUAL when persistent-set nodes are shared.

Three of the orchestrator's own claims were also wrong: a per-cluster
injection design (refuted — one compiled Var per JVM), a flock deadlock
diagnosis (refuted — `tryLock` never blocks), and a derive-don't-store
correction (refuted — the printed form is not derivable). Each was
caught by someone reading source or running a probe.

So: re-derive before repeating, prove the counterfactual before
promising a saving, and treat every inherited number in this repository
as suspect until reproduced.

## Rulings batch — 2026-08-03 morning (owner, via question batch)

- ONE BLOB RULE: unify attempts/messages onto the per-value derived
  comparison; the 65,536 dial is deleted in that migration. Queued.
- SCHEMA SPLIT approved, AFTER render lands: domain files, glob-loaded,
  ONE merging loader refusing exact duplicates; AGENTS.md gains the
  registry-query-first discovery rule (search before declaring, reuse
  before creating).
- LOAD TEST: synthetic-only for now — 1000 synthetic agents with
  thread/memory/CPU curves recorded; live scaling later. Queued.
- RUN-FROM-SCI: continue wave-by-wave after the kernel merge (render
  kernel + evaluate converge on one owner — owner said "do it"); each
  wave states what left the shared surface and its measured cost.
- Clarifications recorded: the 100 ms figure was a TEST-configured
  provider timeout proving bounded teardown, never a function limit;
  the live default boot failure was a stale-branch data gap (rows
  predating the provenance migration), fixed by reset/republish per
  ruling #49; MCP is verified end-to-end from fresh client bindings.

## Owner direction — 2026-08-03 morning: the accretion-testing pipeline

Ruled direction, verbatim intent: functions WITHOUT schemas are
REJECTED at definition; functions without declared effects get
AUTOMATIC GENERATIVE TESTS (never auto-generate against effectful
functions — and evaluate whether effect-reach is DERIVABLE via SCI
analysis/call-graph rather than only declared); existing tests run
too, plus downstream consumers' tests via the call edge; missing tests
produce ENCOURAGEMENT in the agent's returned context. The candidate
context tests the new function AND its new tests together as one unit
before install (owner's own derivation). Design the RETURN VALUES of
every step deliberately — the right context at the right time; the
owner is moving from monolithic instruction walls to a minimalist
bootstrap-forms model where the bootstrap series educates and pulls
live context, with caching keeping LLM evals cheap.

## Owner direction — 2026-08-03: the tools spine, ruled details

- MULTI-LANGUAGE EDITING over Clojure-editing: Clojure's real editor is
  the REPL (overwrite a defn -> upserted live for the cluster; write a
  schema -> installed; DELETION via REPL is a known gap to complete).
  File tools target OTHER languages: LSP integration / per-language
  validation (python lint/indent etc.) for good multi-language
  feedback.
- NO HAND-MAINTAINED EFFECT LISTS: investigate deriving effect/system
  boundaries from SCI's own parsed internals (host-interop observation
  already exists in our fork); when an agent evals something needing OS
  access, FEEDBACK tells them to annotate; schemas catch errors at
  function boundaries BEFORE execution. All outputs flow through the
  render system (no context dumps); evaluate PER-TOOL custom renders
  and sensible default limits. Extend the blob mechanism as THE way
  agents read large results in chunks at leisure (real data
  structures, not text); DETECT re-run-to-tail patterns and teach the
  drill mechanism instead.
- METADATA: owner wants ONE central annotation story (workload, effect,
  schema, privacy) — inventory current metadata, find double-duty and
  suboptimal encodings, exploit for scheduling/processing/evals.
- ASYNC/BACKGROUND WORK: separate research + PRD (launched): virtual
  threads make blocking cheap, but agents need await-or-notify —
  possibly auto-backgrounding IO + explicit (background ...) form;
  must compose with flow (NO new scheduler, NO process management if
  avoidable; babashka.process is for OS subprocesses only).
- THE ORCHESTRATOR PERSONALLY writes the core tool implementation specs
  (naming, composition, shared data shapes so tool outputs chain) after
  reading research/agent-tools-design-2026-08-03.md whole.

---
type: issue
status: open
severity: friction
tags: [issue, render, performance]
created: 2026-09-15
---

# The first debug page after an adoption takes eighteen seconds

## 2026-09-16 cold-page follow-up

The [cold-page-kills landing](../../prds/context-generation/research/cold-page-kills-2026-09-16.md)
records explicit read-only intent across MCP evaluation and its three discovery
observations. Live retained calls now remain **11 → 11**. The armed canonical
page regression passed **12 / 0 / 0**, result run **68082**. Keep this issue open:
the two-minute root measurement still included an adoption and cost **0.667470 s
cold / 0.139521 s warm**. Slice 1 is commit **0dd6bc0aa**; its orchestrator
gate remains pending. Slice 2 stopped at the requested design gate: the
database-aware dependency prototype returned a stale `{:db/id 39410}` after
entity deletion, while uncached execution returned nil. The original Vars
were restored and the dependency fork is unchanged. The landing records the
reproducible probe and three priced options. Residual: precise history
dependencies must preserve entity-existence revisions before narrowing, and
the two-minute cold-page target remains unproven.

## Problem

Default, 2026-09-15 22:05Z, right after the hook adopted `c395610db`:
three consecutive `GET /agent/juniper/debug` took **18.43 s, 0.195 s,
0.175 s**. The warm floor is now under 200 ms (debug-page-cost kills 1–2),
so the cold request is the whole cost: an adoption invalidates every
retained render and the first page re-renders the complete history (45
turns, 88 evaluations) through the render pair, plus re-acquires the
schema projection. The page-speed landing measured cold 2.4–3.3 s this
morning on a smaller population; it scales with history.

## Wanted

A cold page after adoption costs what the CHANGED renders cost, not the
whole history: retained render identity keyed on the evaluation's shown
text and the render pair's program identity survives an adoption that did
not change them (law 2.1: the retained value carries what it derives from).
Regression: after an adoption that changes an unrelated namespace, the
debug page re-renders zero evaluations. Owner: debug-page-cost lane after
kill 3.

## Landing

Lane `debug-page-cost`, 2026-09-15. Read this issue end to end; the preceding
slice read the approved debug-page-cost plan, AGENTS.md §§2.1/2.4/2.5 and
the Datastar skill end to end. Applied the Clojure, Datahike, REPL and testing
skills. Default remained pid 69622 throughout; no test JVM, stop, restart,
refork, provider call, or foreign session operation.

### Retained identity

The shared cache survives development adoption. The invalidation was in
`render/call-cache-evidence`: the adoption commit, whole SCI snapshot pointer,
and projection pointer all changed even when a renderer's inputs did not.
`cluster/acquire-development!` calls the existing `sci.eval/acquire!` owner;
`kernel/cache-program!` replaces that immutable snapshot.

Retained calls now carry the selected function's transitive recorded
`:seon.fn/calls` dependencies and their acquired function/namespace rows.
They compare those rows, schema forms/contracts, invocation inputs (including
saved shown text), and profile. Private SCI definitions compare their actual
callable identity. The committed database shortcut and read evidence remain
in force. The history acquisition checks every child's program evidence
before retaining the complete result. No additional cache or adoption hook.

Dependency ledger: `src/seon/sci/kernel.clj` owns acquired program rows;
`src/seon/sci/eval.clj` owns their acquisition; `src/seon/fn.clj`'s `reaches?`
demonstrates finite traversal of recorded call edges; `src/seon/db.clj`
owns read currentness; Datahike's committed-value identity remains the
connection/generation/commit authority. A recursive rule probe returned only
the five direct callees for `seon.repl/render-ai`; explicit traversal returned
62 functions in six layers (9.42 ms). The implementation uses that explicit
traversal, not the unverified recursive query result.

### Cold acquisition finding

Zero evaluation renders alone did not remove the cold delay. An unrelated
hook adoption produced **13.785702 s, 0.136238 s, 0.228593 s**, all HTTP 200
and all **zero evaluation renders**, at unchanged basis 536871721 and adopted
source `6aa9bdf2-ae5e-55f7-9adc-02cc97b91765`.

The next section probe measured read-currentness **1.64 ms**, history walk
**1,199 ms**, and ledger-data acquisition **11,961 ms** (inclusive section
times). Its effects query rescanned the program for each turn interval.
The existing bulk acquisition now reads each definition once, then relates
it to the stored intervals in memory. At basis 536871733, the two formulations
returned exactly the same two effects: **11,827.265 ms / 3,965.991 MB** before,
**85.570 ms / 91.722 MB** after (`nanoTime`, ThreadMXBean on the same JVM thread).
After hot adoption, ledger acquisition measured **109.341 ms** and the whole
GET **1.859564 s**; the remaining history walk was **867.073 ms**.

Broad read dependencies still conservatively invalidate history and ledger
acquisition: a probe identified the evaluation-history query's wildcard
read-evidence pull and the turn-row query's transaction metadata pull. This
slice preserves that database owner's decision; it does not claim that an
unrelated adoption costs the same as a warm page. Keep this issue open for
that remaining acquisition cost.

### Verification boundary

Added `seon.render.retained-test/adoption-of-an-unrelated-namespace-re-renders-zero-evaluations`
using the canonical fixture and actual SCI acquisition. It also asserts that
changed shown text re-renders one evaluation, a changed render pair re-renders
both, and a changed profile invalidates. The earlier stamp-only page regression
now expects retained output; private renderer redefinition remains covered.
No test JVM was launched. During this slice, the concurrently updated hook
enabled reached tests in the existing JVM: its first batch reported 7 run,
4 passed, 3 failed. Two failures exposed incomplete fixture transaction maps;
the new regression now uses explicit `:db/add` updates, and an unnecessary
synthetic function fixture was removed. The third failure was the existing
`turn-details-use-the-loop-opening-and-exact-segments` fixture refusing an
unavailable `:example/order` schema. The isolated orchestrator gate is still
required; namespaces are appended to
`tmp/orchestrator/gate-requests/debug-page-cost.txt`.
An explicit canonical-fixture REPL probe verified the corrected namespace,
adoption-stamp, and renderer-source datom updates commit successfully.

Reproducible probes in the research directory:
`debug_page_adoption_probe_2026_09_15.clj` counts real SCI renderer calls around
three actual bounded curl GETs; `debug_page_adoption_sections_2026_09_15.clj`
times inclusive owners; `debug_page_effects_probe_2026_09_15.clj` verifies
query-result equality and records time/allocation. All supply default's
connection explicitly, without a supplied-projection binding.

Foreign boundary: concurrent changes to the test owner, cluster adoption,
and edit hook remained untouched. Publication sometimes reported
`:seon.cluster/source-changed-during-adoption` and retried through subsequent
hook work. Measurements spanning changing source/basis are not the final
controlled comparison. No foreign process was resumed, messaged, or edited.

### Final unrelated-adoption measurement

Entry curl measurements were **17.909071 s, 0.125783 s, 0.119813 s**;
the owner independently reported **19.46 s, 0.150 s, 0.135 s**.
After the implementation, a one-line `seon.schedule/valid-cron?` docstring
edit went through hook publication `667034e8-2fc4-4ed3-bb91-219a166f6aa1`,
which reported successful adoption of
`6aa9bff0-a23c-5975-9f86-44459131e3c1`.
The first subsequent curl and its two repeats were **1.697270 s,
0.059250 s, 0.068848 s**, all HTTP 200, all **zero evaluation renders**.
Source remained that commit and basis remained **536871753** across all
three GETs. The unrelated namespace docstring changes are included so the
adopted bytes are reviewable. The later fixture corrections create a separate
test-only publication; they are not represented as part of this measurement.

## 2026-09-16 03:00Z — owner decision

Slice 1 landed (`0dd6bc0aa`: read-only MCP returns preserve retained pages).
Slice 2 (sound narrowing of the history walk's `:all` dependency record) is
DEFERRED by the owner: "that's fine, let's leave it for now." Cause stated in
[cold-page-kills-2026-09-16.md](../../prds/context-generation/research/cold-page-kills-2026-09-16.md):
every-minute maintenance commits invalidate the `:all`-dependent history
walk; narrowing to concrete attributes went stale on entity deletion, so
entity-existence changes must enter the revision evidence first. Cold cost
today ~1.7–2.0 s, warm 60 ms. Reopen when the reach-digest and read-evidence
work make existence evidence available.

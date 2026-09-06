---
type: prd
status: active
tags: [prd, agent, context, architecture]
---

# The working edge — context-generation program

## Design-lab planning checkpoint — 2026-09-06

Owner cache direction, 2026-09-06: evaluating a renderer for visualization
must populate the same invocation cache later consumers use. Presentation
call IDs are not invocation identity. Reuse requires the same actual function,
output, prepared inputs and program world, with current database read evidence;
do not scan every retained call to discover a hit. The existing render proc
owns this cache. Executed source forms use their ordinary stored results;
opening an inspection must not rerun them. Cross-surface reuse is implemented in the existing render proc. The focused
regression counts actual SCI invocations: two presentation IDs reuse one
invocation, an unrelated transaction reuses it, and a changed queried fact
invokes again. Eight focused checks passed with 131 assertions and no failures
or errors. This is not a claim that the entire web suite is green.

Rendering-limit checkpoint, 2026-09-06: `cc667a426` disables the formatter,
prompt, and render-admission cuts; `f48d27012` renders all namespace/transcript
data admitted by their query bounds. Focused admission/print gate: 25 tests,
111 assertions, zero failures/errors. A live hot-reload probe preserved full
data under depth/width/string caps of 1 and a two-node cap. The actual
`/ns/seon.flow/debug` browser now contains both AI and HTML function summaries
through the final output path (308 ms observed ready time, no JavaScript
errors); `debug_complete_render_probe_2026_09_06.cjs` preserves the check.
A later browser pass exposed missing function summaries: generic producer
preparation dropped the entity `:db/id` before the namespace renderer queried
its functions. The live database has 50 matching functions; the prepared
argument lacks the id. Restoring the acquired root id at generic producer preparation restores the
actual namespace outputs in both projections. The browser probe checks the
qualified HTML summary and the actual AI namespace plus unqualified `defn`;
these are intentionally different outputs. The final browser pass passed at 1440 and 390 pixels with no JavaScript errors
and 161 ms observed completion. The graph library already resizes its canvas;
the probe now waits for that observable result instead of assuming two animation
frames suffice. The actual plan entity 32011 also appears in both projections.
These are hot-loaded Var proofs on the same branch. These observations exercise hot-loaded JVM Vars
on `lab-browser-0906`, not a fresh fork's indexed source. Initial source hook
publication encountered concurrent initialization lock contention; subsequent
publication succeeded.

Owner clarification — all rendering limits, 2026-09-06: disable presentation
size, depth, child-count, and token-budget enforcement for experimentation.
Keep measured costs visible; query execution bounds and evaluation deadlines
are separate from rendering. Do not replace limits with enormous constants.
Implementation and live verification are in progress, not yet complete.

Owner visualization direction, 2026-09-06: show both `:seon.render/ai` and
`:seon.render/html` outputs in each ordered renderer comparison. The list runs
from most specific to least specific and distinguishes the current selection,
lower-priority alternatives, rejected contracts, and missing projections.
Derive ordering from the existing selection owner and pair projections through
declarations or their selection stage, never function-name guesses. Actual
candidate previews must reuse the existing invocation and retained-call path.

Owner steering — visualization and design iteration, 2026-09-06: stop trying
to solve namespace rendering or the entire platform before experimentation.
Implementation agents are paused and their unfinished shared-tree edits are
preserved. The next work is feedback-driven iteration on the visualization,
using the default renderer and inspectable candidates; specialized-renderer
perfection is not a prerequisite. Automatic goal continuations do not override
this direction or authorize restarting paused implementation lanes.

An interactive design example was shown in the conversation at
`/Users/sean/.codex/visualizations/2026/09/05/01a072a8-9fd1-7863-b8c1-34b2c3d9e577/seon-inspection-layout.html`.
It compares sidebar versus inline inspection and simulates attribute selection
and renderer previews. Browser checks verified these local interactions and
360-pixel containment. Its data, ranking, and producing forms are illustrative,
not evidence of executed forms or adopted renderer precedence. It is not the
completed production lab. Await owner feedback before choosing its layout or
resuming the platform changes.

Candidate and prompt UI integration checkpoint, 2026-09-06: retained arguments
and Malli contracts now have disclosures; candidate/selected function links
navigate to their actual database entities, and AI/HTML links preserve viewer
and subject. Browser clicks proved the round trip and definition navigation
with no JavaScript errors. Candidate focused checks: 84 assertions passed.
Prompt comparison separately displays historical capture and current preview;
83 focused assertions and actual browser comparison passed. Browser scripts
`debug_candidate_controls_probe_2026_09_06.cjs` and
`debug_prompt_browser_probe_2026_09_06.cjs` preserve those checks. The goal remains
open: normal durable source submission and namespace-output readability are
being improved at their existing owners, with no per-render interpreter.

Owner clarification, 2026-09-06: reuse the normal source execution and
settlement mechanism for lab comments/forms/results. The parser does not own
SCI context creation: `reply/sources` parses source, while the existing run
loop freezes intent, `resume-turn` obtains `sci.eval/fork-for-turn`, evaluates
ordered forms and binds their results, then settles the batch. The lab should
use that complete path and its ordinary stored results. Do not replace each
renderer invocation with a separately invented parser/fork mechanism. The
previous fork-per-render design question is superseded by this clarification;
trace the smallest existing entry seam for system-authored source without a
paid model call. Read-only inspection itself must remain read-only.

Two-viewer and reopen checkpoint, 2026-09-06: actual browser inspection of
the same plan entity now selects at namespace stage for `my.plan` and schema
stage for `seon.flow`, with the same database snapshot, graph, and actual output.
No agents or transactions were created by opening those agentless debug routes.
Both headers show the sovereign indexed-source digest and handed projection
fingerprint. Browser-discovered namespace return-contract mismatch and stale
closed-viewer runtime caches were fixed in `f4b08e7c5` and `289c9913f`
(79 and 77 focused assertions). Stronger browser assertions pass after the
existing runtime-eval event; no cache-busting URL or reset is used.

Live delivery checkpoint, 2026-09-06: the isolated `lab-browser-0906` probe
now passes a real database transaction through SSE into the same open browser.
A plan title changed and a new dependency ref appeared; basis advanced
536870953→536870954, nodes/edges changed 2/1→3/2, and container, Cytoscape
instance, zoom, and pan survived without navigation or JavaScript errors.
The initial attempt exposed selection/invocation using different pulled-ref
shapes; `f609acb71` fixes the shared argument preparation (76 focused assertions
passed). Reproducible fixture, exact transaction, and proof limits are in
[live feed proof](../research/debug-live-feed-proof-2026-09-06.md).
The subsequent SCI definition-only probe also passed: an MCP redefinition of
`my.plan/render-item-html` changed the open browser output at the unchanged
database basis, preserving graph/container/viewport without navigation. The
original definition was restored immediately from the scratch database's exact
source. Actual producing-form display and the remaining audited inspection
requirements remain open; the complete goal is not yet achieved.

Browser checkpoint, 2026-09-06: the live debug page now draws the actual
34-node/33-reference graph alongside selected output. Browser-discovered
container, event namespace, status replacement, and parent-selector defects
were fixed (see `docs/seon/issues/debug-graph-model-did-not-prove-visible-interaction.md`).
The committed browser probe verifies preserved instance/viewport/selection on
client reconciliation, edge detail, physical node-click navigation retaining
the viewing namespace, and no horizontal overflow at 390 px after native
resize. No JavaScript errors. Warm local ready samples ranged from roughly
56 to 686 ms; these are observations, not a latency guarantee. Proof used
hot-reloaded JVM web Vars and current static assets on default PID 14798,
not a freshly forked cluster's indexed program. Actual database-change→SSE→
browser update remains unproven by this probe. The complete goal remains open.

Owner decision, 2026-09-06 (supersedes the thinking-field experiment below):
drop `:seon.render/thinking` for now. Author ordinary source containing thinking
comments and executable forms; reuse the existing reader and evaluation path,
and render the ordered source plus actual results. Results never become
executable source merely because their printed representation contains forms.
Keep this structure available for graph coverage and dependency analysis.

Owner coverage objective, 2026-09-06: efficiently explain the data within N
hops of the agent entity using knowledge of the actual graph and the queries
that generated each block. Define the neighborhood from one immutable database
value with explicit reference directions. Measure attribute/value coverage,
not merely visited entity IDs. Query/pull selection identifies source data;
rendering and final fitting determine what was actually displayed, summarized,
or elided. Compare candidate blocks by additional coverage, repetition, complete
context tokens, and database work. Encourage ordinary agent-authored functions
over these facts; derive provenance at existing read/render seams rather than
requiring agents to hand-maintain coverage lists. This is the proposed
experiment; exact displayed-fact provenance is not implemented yet.

Owner proposal, 2026-09-06: let an AI render produce source for the existing
reply reader, forming blocks of explanation, executable forms, and evaluated
results. Analyze the source with the existing clj-kondo owner; combine those
references with actual database-read and displayed-value provenance to select
and order explanations across blocks. This is an experiment, not a changed
render contract. Source references, facts read, facts displayed after fitting,
and concepts explained are distinct observations. `seon.print/references`
already discovers symbols and schema-derived identities in structured print
nodes, but does not prove which attributes were visibly explained. Never
reparse evaluated output as executable source or infer database identities
from arbitrary prose/numbers. Preserve actual history and effect order;
compare dependency ordering of independent generated explanation reads.

Owner direction, 2026-09-06: context discovery must start from the database,
including the agent entity and its related entities. The plan should introduce
current work and teach direct queries and transactions over those same facts,
including creating related data. Inspect and improve `my.plan` in place. Current
source already declares an optional `:my.plan/anchor` on agent state and
`:my.plan.item/agent`, `/parent`, and `/needs` refs on items; do not add a
mirrored plan population merely to make the connection visible. Verify actual
population, reverse-pull discovery, and direct transaction semantics before
claiming this works end to end.

The owner rejects hand-written layout as the general solution. The fixed
three-entry primer below is a return-shape experiment, not the proposed
composition algorithm. Its latest third form returns thinking conditional on
the actual plan plus its AI rendering; all three evaluations succeeded, but
this still does not establish general discovery, ordering, or composition.
Current ordering is owned by `seon.render.walk/ordered-episode`, while
`my.plan/render-plan-ai` and `/render-plan-html` manually assemble sections.
Evaluate recursive rendering of ordinary data and forms through the existing
selection/floor mechanism; distinguish executable dependencies, explanatory
ordering, and visual layout rather than assuming one graph order solves all
three. The Datahike findings are in
[context-query generation](../research/datahike-context-query-generation-2026-09-06.md).

Temporal teaching experiment, owner discussion 2026-09-06: compare compact
forms with a useful mix of `pull`, `q`, `since`, `as-of`, and `history` when
those operations answer a relevant question. Do not require all operations in
every primer. Keep information/concept coverage, duplicate information,
complete context tokens, database work/latency, and source characters as
separate measurements; shortest source is not yet a ruled optimization target.
The Datahike source research owns the exact temporal semantics and query
examples. Receiving a message, processing it, and actually including it in an
agent context must not be conflated when deriving what is new.

Executed primer experiment is persisted in
`../research/thinking_primer_probe_2026_09_06.clj`: three forms in a disposable
SCI turn fork (`dir my.plan`, one bounded query for two function docs/arglists,
and `render-plan-ai` composed with `plan`). All three returned without eval
errors; observed evaluations were 14.4, 11.8, and 9.3 ms respectively, one
sample only. `dir` produced 12 public names; the batched query produced two
function maps; root's plan output had zero obligations, ready items, blocked
items and recent completions. The admitted values were inspected directly;
the file now emits their ordinary printed representation. Its thinking text
is still authored per example, so it does not yet prove data-dependent thinking
or minimal-form selection. Live macro expansion also proved `doc` and `dir`
are single-argument, contrary to the suspected completed bulk extension.

Owner display direction, 2026-09-06: keep the clean layout, warm the palette
to complement the existing Phosphor Terminal theme, and avoid excessive orange.
The updated mockup uses warm charcoal/cream, muted sage selections and sparse
amber. The default inspection should show the rendered value plus its producing
Clojure form, with raw stored attributes/datoms behind disclosure. A short
description may come from the selected function's docstring.

Open design experiment from the owner: optional `:seon.render/thinking` for a
data-dependent explanation. Proposed interpretation is authored explanation
metadata computed alongside a render result, sharing its input snapshot and
read dependencies; it is not a third output projection or a new stored mirror.
Docstring text describes the function generally and must not be presented as
a data-dependent explanation. The producing form must come from actual
acquisition/invocation evidence; never fabricate a replayable query from only
the printed result. This is a design proposal, not an installed schema or a
settled return-contract change.

The owner approved the planning approach and requested research agents plus
a mockup. Research is committed: [graph source review](../research/graph-visualization-source-review-2026-09-05.md)
(`70ea7a53c`) and [interaction semantics](../research/inspection-interaction-review-2026-09-06.md)
(`2244b15a4`). Cytoscape 3.30.4 is pinned under `reference-code/cytoscape`.
The interactive conversation mockup was presented with explicit example data;
it is not production evidence or approval of every interaction shown.

The implementation dependencies are: correct linked datoms and distinct
subject/viewer first; one persistent graph of that same bounded observation
second; same-snapshot multi-subject composition before accumulated expansion;
candidate source/contracts from actual selection evidence alongside that work.
Alternative renderer execution remains a separately labelled experiment; the
mockup's local preview must not become an implicit production invocation.

| Work / uncertainty | Smallest decisive verification | Stop / reuse rule |
|---|---|---|
| Reference navigation | One focused case covering outgoing ref, incoming source, attribute identity, scalar non-ref, and retained viewer | No namespace-wide suite; reuse this proof until navigation inputs change. |
| Unrelated transactions rerender | `unrelated-transaction-reuses-debug-observation-and-render-call` is the retained proof | Resolved after `b080f42d1`: observation/discovery/invocation remained 1/1/1 after an unrelated transaction and became 2/2/2 only after selected data changed. Focused result: 76/0/0. |
| Useful bounded HTML | Finish the existing preview-focused cases and inspect actual HTML once | Do not rerun the long grammar property while iterating on preview presentation. |
| Graph identity and lifecycle | One directed multiedge/self-loop specimen; ten model patches, then remove the graph element | One instance, preserved positions, exact qualified attributes, destruction on removal. No new backend or layout benchmark until this passes. |
| Accumulated exploration | Expand two subjects and transact a connecting ref; assert every returned page has one database identity | Client-only unions fail this requirement. Compose bounded existing observations server-side before claiming live accumulated exploration. |
| Actual output and updates | One already-open page; selected-data change and rendering-helper change, then restore both | Same captured render-call supplies explanation and output. Measure complete paint, not only loading-shell HTTP latency. |

Current runtime evidence: MCP answers on PID 14798, start
2026-09-06T00:19:25Z, prepl 55586. Publication succeeded after removing the
proven stale kondo CLJ cache entry; default was reforked from
`6a9cb133-e5c9-50d3-ae7c-b373eac32476`. This does not certify the runtime clean:
MCP reports one `seon.fn/analyze-forms` invalid-output core fault, one
maintenance settlement refusal, and one failed root run. These are tracked
in the existing fault-storage issue pending cause inspection; do not start
another broad repair campaign unless one blocks the decisive UI proof.

Historical pre-fix verification: DB semantic replay 78/0/0, bounded/opaque
request refusal 1/0/0, lazy/opaque safety 4/0/0, runtime-evaluation wake 8/0/0;
web unrelated-transaction reuse 2/2/0. Counts are pass/fail/error, from
`tmp/design-lab-cache-final.log`. The later `b080f42d1` focused proof resolved
that web failure: 76/0/0, with 1/1/1 initial calls, unchanged 1/1/1 after an
unrelated transaction, and 2/2/2 after selected data changed.

## Active execution goals — dinner continuation, 2026-09-05

**Owner correction: planning before further implementation (2026-09-05).**
The immediate deliverable is a source-grounded UI design and an efficient
execution plan, not another round of reactive repairs. Preserve the complete
debug-UI objective below, but defer its implementation sequence until this
planning checkpoint is recorded:

- Specify what each screen region displays, what each click does, and how
  graph selection, stored datoms, renderer ranking, and actual output stay
  connected. Distinguish existing behavior from proposed behavior.
- Use the graph research agent's pinned Cytoscape source review to choose
  navigation, incoming/outgoing expansion, large-data limits, incremental
  updates, and lifecycle handling. Produce a concrete visual proposal before
  spending more time polishing the current layout.
- Classify each remaining defect as blocking that experience or independently
  deferrable. Work only on the critical path; keep unrelated platform findings
  in their existing issue notes rather than expanding the implementation.
- For each uncertainty, name the smallest falsifying REPL probe or focused
  test, its expected result, its bound, and the decision it will resolve.
  Preserve successful evidence with its code basis; repeat only after a
  relevant change or a new counterexample.
- The orchestrator owns one serial integration gate. Agents may run only the
  explicitly assigned focused tests; namespace-wide/full suites, automatic
  confirmation fanout, repeated source publication, and independent resets
  are not default lane work. Inspect live process ownership before stopping
  an existing run; never infer completion from silence.
- Delegate bounded source research and disjoint implementation only after
  inputs, outputs, ownership, and acceptance are written. Before each launch,
  explain how its result changes the next decision. Stop obsolete work.

Completion of this checkpoint means a reviewable visual proposal, an ordered
implementation dependency list, and a bounded verification matrix. It does
not mark the full active debug-UI goal complete. The owner asked to discuss
the display, so do not treat an unanswered layout preference as approval.

The owner asked for continued autonomous progress while away. This ordering
supersedes the older session checkpoints below for the current design-lab work.
The existing active goal remains incomplete; source edits, delegation, an HTTP
loading shell, and tests alone are not completion evidence.

1. **Run a coherent current program.** Resolve the reproducible source-analysis
   arity discrepancy, finish and commit the bounded HTML preview, then publish,
   refork and start the shared default cluster. Verify that `1f3c099d2` is
   included, record the published source identity and process identity, and
   prove a clean generated opening, including the previously missing empty
   agent namespace. The cluster is stopped at this checkpoint.
2. **Complete the first usable debug inspection.** Show actual entity datoms
   and reference navigation, the real ranked render selection, and useful
   bounded AI/HTML output. Verify arbitrary subjects and distinct viewers,
   pagination, missing-data diagnostics, and a readable wide/narrow layout.
   Use an isolated headless browser if the desktop remains locked.
3. **Prove live updates and bounded work.** On an already-open view, change and
   restore relevant data and a rendering helper through the MCP evaluation
   tools. Verify automatic repaint, unchanged-input reuse, unrelated-transaction
   reuse before discovery/invocation, and recovery by a late-joining feed.
   Record cold/warm complete-render timings and actual work counts; investigate
   surprising cost instead of hiding it behind delivery suppression.
4. **Close the storage and recovery defects needed for reliable experiments.**
   Confirm bounded fault facts on the fresh cluster and measure store growth.
   Check the existing garbage collector before adding machinery; reclaim only
   proven inactive disposable test roots. Coordinate the global write bound and
   missing-result recovery with the other agent's already-owned work.
5. **Simplify evaluation bookkeeping from the completed audit.** Prototype the
   one-form-entity model and recovery ordering against actual transactions,
   including newly appended forms after an interrupted generated run. Delete
   redundant state only after those invariants are proven. If a cross-owner
   design decision is still necessary, persist concrete alternatives and keep
   advancing independent work; do not invent a choice for the absent owner.

At every coherent checkpoint: run the relevant focused checks, verify the live
behavior, commit explicit owned paths, push, and update the linked
[inspection evidence](../research/design-lab-inspection-slice-2026-09-05.md).
Report failures and limits honestly. No paid model calls are needed for these
checks. The first inspection milestone may complete before the wider storage
and simplification work; do not report the whole platform finished on that basis.

*THE one live record of current state and ordering (owner ruling,
2026-08-29): write-through in the session it changes, path-limited
commits. The sci-execution-runtime `unsettled.md` is tombstoned and
historical. Dates are absolute; a stale claim here is a defect.*

## Current state (2026-09-02, afternoon)

**Owner reframe (2026-09-02, conversational, supersedes the reader-centric
spelling of 53–55):** the agent is dropped into a Clojure REPL in its own
namespace. Context = the data discovered from the agent's entity outward
+ the BEST render function for each value (priority chain: an inline
render on the value → a function in the agent's own namespace whose input
schema is the data's schema and whose output schema is `:seon.render/ai`
→ the family's schema-declared face → the floor) + the teaching needed to
explain what was shown, derived by walking back from the render/query
functions used: `doc` and `dir`, never prose walls. `doc` becomes
polymorphic — anything, or a list of anythings (namespace, function,
test, schema, value) — showing the relevant parts; we own every tool and
tailor it to the system while keeping its Clojure spirit. Queries stay
legit Datalog/pull with good examples: easy first query, then "what is
new" via `since`/`as-of`/tx-meta conventions. Every agent in every
namespace is tutorialized on ITS neighbourhood and encouraged to write
its own render functions, which also become HTML interfaces. Delta
mechanism: OPEN — probe in the REPL first (evidence:
[repl-first-probes-2026-09-02.md](../research/repl-first-probes-2026-09-02.md)).
Budget: compaction over evals (ruled). Sequencing: reds first, then the
generator concurrent with the bridge lane (ruled).

**Platform (derived 2026-09-02, `bin/test --all` at 43e5e2fff,
tmp/gate-all-2026-09-02.log):** platform tier 72 tests, 1 error —
`cohost-boot-test/a-second-cluster-boots-…` hit the 270 s exchange bound
under heavy load and passed its isolated confirmation (202 s); five
platform tests take 200–244 s each (slow-is-a-bug, unmeasured cause).
The bulk tier did not run. NEW DEFECTS this session, lanes launched (all
six first launches died to intermittent DNS failures reaching the Codex
endpoint; relaunched under `-2` names): bare `bin/test` cannot record
persistent results while any cluster holds the store AND the refusal
throws before the tally (`gate-evidence-2`); `runtime_status`
missing-projection (`mcp-status-2`); `/agent/<id>/debug` swallows the
prospective-context cause (`debug-page`, relaunch owed); two
`gen.loop-test` census errors (`gen-loop-2`); the two armed reds
(`armed-reds-2`); the attempt-traces blocker (`attempt-traces-2`). Filed
without a lane yet: `seon.db` reads rebuild the projection per call when
none is handed — 2.4 s vs 0.1 ms raw
([issue](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md),
class/p1, blocker for "context is queries"); `doc` contract lines print
schema bodies and flatten arities
([issue](../../../seon/issues/doc-contract-lines-print-schema-bodies-and-flatten-arity-alternatives.md),
now critical-path teaching work).

## Session resumed (2026-09-02, evening) — lane round 1 outcome, round 2 launched

**Landed (reviewed):** `3c162853f` armed-reds — both armed reds green; the
fault WAS committed as a fact all along: core.async.flow retains a proc's
pre-transition `:paused` status after a throwing transition
(`reference-code/core.async/.../flow/impl.clj:282`), so the test wedged in
teardown's armer-quiescence wait, not in the fact await; the boot-window
test now awaits the run whose trigger is the boot-window message.
`927229929` gen-loop — the fixture treated receipt-row presence after a
fixed drive loop as settlement; it now awaits the exact terminal census
through the bounded event boundary (production settlement is synchronous
and atomic, `loop.clj:682`/`run.clj:1108`; a writer race was REFUTED).
`fb3a61abe` gate-evidence — bare-gate evidence routes through the live
store holder's advertised prepl (offline when no holder), the tally
prints BEFORE persistence and the exit code derives from tests alone,
recording failure is one loud typed line. `ab9559929` attempt-traces —
the fixture's no-backup world made explicit; whether the seed is green at
HEAD is UNVERIFIED (lane round 2). `612122fa6` issue: the preflight sweep
race.

**New blockers found by the lanes:** the evidence write through the live
holder is REFUSED because instrumentation installs the SIBLING's contract
on `seon.schema.datahike/resolve-datahike-form`
([issue](../../../seon/issues/instrumentation-installs-sibling-contract-on-datahike-resolver.md),
class/p1 — stale-green stays UNKNOWN until fixed); `bin/test`'s preflight
sweep races concurrent invocations and aborts gates
([issue](../../../seon/issues/bin-test-preflight-sweep-races-concurrent-invocations.md)).
Diagnosed without landing: `runtime_status` (cluster.clj:509 lacks the
projection; fix pattern = `mcp-effective`), `/agent/<id>/debug` (page
renders only the message; suspected missing `:seon.render/profile`).

**Round 2 lanes (launched ~21:30Z, tree quiet):** `mcp-status-3`,
`debug-page-3`, `sibling-contract`, `sweep-race`, `db-projection`
(the class/p1 per-call projection rebuild), `attempt-traces-3`. Exhaust:
six returned lane roots swept; one orphan runner-exchange helper reaped;
`tmp/test-runs` holds 1.0 GB of retained roots (holderless; sweep after
the sweep-race lane lands).

**Design (2026-09-03):** the owner ruled the four forks (ledger 56) and set the phase rule — BEHAVIORS FIRST: [repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md) is the behavior spec under markup (B1–B12, 19 ❓ each with a recommendation); implementation talk only after every ❓ is gone. The `doc-polymorphic` lane was stopped for that reason (resume when B6 settles). Earlier text: the [design draft](repl-first-context-design-2026-09-02.md) §9 carried four forks for the owner (faces as contracted functions vs the
schema property; since-shaped delta vs revision diff; implicit printer vs
an explicit agent-callable; identities on `[*]` ref leaves). Generator
work starts only after his answers and the platform items above.

## 2026-09-03 (afternoon) — round 2 outcome, round 3, the behavior spec

**Landed:** `1b22034b6` sweep-race (claim-before-sweepable + vanished path
= success); `768c6a0e0` db-projection (projection cached by exact
committed identity: wrapper 0.220 → 0.048 ms; issue stays open only for
the literal 2× ratio — owner decision on a decoded-result cache);
`8fa146805` sibling-contract REFUTED (the 1-arg resolver's body calls the
2-arg sibling, which truthfully names itself; the real defect is the
projectionless naked call — owned by
`malli-form-predicate-resolves-the-declaration-population-itself`);
`98b6175be` attempt-traces closed (fixture-side, seed proof recorded);
`1ecd7054e` mcp-status landed by the orchestrator from lane -3's draft
(issue archived). Still running: `debug-page-3`, `parallel-paths-census`
(research: the refactor/merge/delete register the owner asked for),
`analyze-form-row` (BLOCKER: root's contracted defn never settled —
analyze-form returned `{:seon.ns/name nil}`; root burned a 44-form paid
run), `reap-nil-path`.

**Behavior spec:** [repl-first-behavior-2026-09-03.md](repl-first-behavior-2026-09-03.md)
now carries §G (the walk as forms), B4 with the MEASURED fit ladder
(strings halve to zero first — the dumb clipping), B5 with real diff
bytes and the `since` lookup-ref trap, G6 render provenance (cost fact
lacks the render function symbol). Rulings 56–57 sealed. Open ❓ listed at its
foot; the owner rules them, then implementation talk begins.

**Tool defects this round:** `get_value` elides large strings with no
paging ([issue](../../../seon/issues/mcp-get-value-elides-large-strings-with-no-way-to-page-them.md));
MCP result projection prints zero-character strings above the blob threshold (same
issue). `runtime_status` fix landed but the live `ctxprobe` JVM serves
pre-fix code until restart.

## 2026-09-03 (late afternoon) — round 3 landed, round 4 launched, ruling 58

**Landed:** `ee11cfa45`/`ba94d7b3c` analyze-form-row — a nil namespace pull
now returns a typed `:seon.fn/namespace-unresolvable` error, never a
partial row; the exact prose-prefixed defn settles at HEAD (real run-loop
regression added; the historical cause on ctxprobe is attributed to that
JVM's age, not the current path); `b136f574f` reap-nil-path — malformed
claims are named refusals in the reap result (the nil was NOT a root path,
see the new class below); `78cee3fea` debug-page-3 — debug requests carry
the agent profile and the page renders the diagnostic. Research landed:
[parallel-paths-register-2026-09-03.md](../research/parallel-paths-register-2026-09-03.md)
(three semantic context assemblers, two prompt glue paths; the
refactor/merge/delete register for the implementation phase).

**New class found by two lanes independently — instrumented Vars refuse
values their own declarations allow:** `seon.fs/delete-recursively!`'s
2-arity re-enters the wrapped 3-arity with nil options → every 2-arg
deletion refuses under instrumentation
([issue](../../../seon/issues/delete-recursively-two-arity-refuses-under-instrumentation.md));
`seon.context/message-custody` declares `[:maybe run-id]` and is refused
nil → the history walk fails for any message when the agent has no
current run — a context-generation blocker between runs
([issue](../../../seon/issues/instrumentation-rejects-message-custodys-declared-absent-run.md)).
Lane `instrument-absent-args` owns the class. Also: the operator
parked-collection regression no longer reaches its GC latch
([issue](../../../seon/issues/parked-collection-regression-no-longer-reaches-gc-latch.md),
lane `parked-collection`); lanes are not given the Seon MCP tools
([issue](../../../seon/issues/lane-toolset-omits-required-seon-mcp-tools.md)).

**Ruling 58 (owner):** `(help)` bootstraps generatively; render functions
accrete ACROSS agent namespaces with the whole order as one query; render
provenance rides the existing after-value comment; compaction = a fresh
session that loses nothing, and the system is developed for FULL
REGENERATION EVERY TURN first — incremental diffs (56b/B5) are a later
wave. The behavior spec's B1/B3/B5/B8 carry it.

## 2026-09-03 (evening) — rulings 58–59, the ONE-platform document

Owner rulings 58 (help bootstraps generatively; cross-namespace render
accretion with a single-query order; provenance on the result-handle
line; full regeneration every turn first, diffs later) and 59 (NO
DELETION before one design doc; floor-only provenance; `result/<id>`
handles revived as real symbols; closest-then-newest) are in the ledger
and the behavior spec (B1, B3, B5→later, B8, B13, G6). The one-platform
document the owner required —
[repl-first-one-platform-2026-09-03.md](repl-first-one-platform-2026-09-03.md):
what survives, what we add (generator as generated evals, the
render-selection query measured live, polymorphic doc/dir, smart fit,
result handles via the def-restore seam, floor provenance), what we
refactor in place (11 rows), what we delete (D1–D11, each with replacement
and proof gate), six waves — is drafted for markup. Wave 1 (platform
floor) is proposed to start now; nothing else before the behavior ❓
list empties. Lanes running: `instrument-absent-args`,
`parked-collection`.

## 2026-09-03 (night) — ruling 60, the document restructured for review, lanes quiet

Owner ruling 60 (render function = any function whose inputs are
satisfiable from the value + injectables and whose output is `/ai` string
or `/html` hiccup; inline content or symbol allowed; handle ids derived;
budget = config fact in TOKENS, 5k; NO CODING until the document
convinces). The one-platform document now opens with the goal, what we
have (register in one screen), and §0b TURTLES — the render-function
dolls by family (layout → entry → value → floor), the transcript as a
pull rendered, the no-hardcoding test. Landed: `5ec65c82c`
instrument-absent-args (delete-recursively! arities share one
implementation; the message-custody attribution was REFUTED — Malli
honors `:maybe`; the debug page's real cause is a missing agent id,
[issue](../../../seon/issues/prospective-debug-walk-omits-agent-id.md));
`8a261c9c1`/`6e2fb68f7` parked-collection (production still collects;
the regression now parks the direct seam; note: that lane amended the
orchestrator's commit `d5889c194` → `f8bac9cd4`, content intact). No
lanes running. Platform fixes continue only on code that stays (§4 of the
one-platform doc names what does not).

## 2026-09-04 — ruling 61, the visual

Owner: pivot away from custom reader functions toward generated,
demonstrated queries (intent comment + real form); the WATCH is the delta
driver. Verified at HEAD: Datahike `listen!` wrapped once by
`seon.cluster.wake/route!` (payload-free, never throws/parks; ruling 41
keeps listeners system-side) and per-query interest already derived from
read-evidence attributes (`:seon.render.web/interest`). The reusable
design language — GRAPH (live edge counts) → WALK (generated forms with
derived intent comments) → TRANSCRIPT (rendered, `result/<id>`), the
watch as a loop — is
[repl-first-visual-2026-09-04.md](repl-first-visual-2026-09-04.md);
behavior B14 added. Still NO CODING toward the design (ruling 60).

## 2026-09-04 (evening) — the Atlas

The owner asked for ONE guiding, iterable visualization instead of temp
diagrams: [repl-first-atlas.html](repl-first-atlas.html) — a single
data-driven page (Cytoscape.js entity graph + native mermaid), published
as an artifact and republished to the same link on every edit. Five
views: the data model (target vs HEAD, click a family for attributes,
writers, render fn, generated forms) with a SESSION PLAYER that steps
through forms showing reads (blue), writes (instances appear under the
record), pops (retract), and diffs; the walk → forms → transcript;
the query language (when q, pull, pull-in-q, aggregates, recursion,
diff, since, history, transact, retract, schema declaration — real forms
+ the Datahike grammar each relies on); the watch; where code is indexed.
Everything draws from the `MODEL` object at the top of the file; the open
mark is a committed script that regenerates its edges from the live
schema. The markdown visual stays as the printable twin.

## 2026-09-04 (night) — the condensed design and the second opinion

The owner could not follow the trail and asked for a second opinion. THE
ENTRY DOCUMENT for the design question is now
[agent-centric-design-2026-09-04.md](agent-centric-design-2026-09-04.md):
the goals in his words, the data model built AROUND the agent (an agent
entity owning inbox/evals/defs/notes/plan/data as component collections;
NO cluster-level message family — a message lives in one inbox and is
popped; the sender keeps its eval; system facts point at the agent), how
the context generates from it, the evidence table, the unknowns, and the
orchestrator's own errors (chief among them: keeping
`seon.cluster.message` in every draft). Lane `second-opinion` (Codex, no
session context) critiques it into
`research/second-opinion-2026-09-04.md`, re-running the probe scripts on
its own scratch cluster. The atlas and the one-platform §0e still carry
the old family names until the design is agreed. Ruling 66 (help =
record summary via render fns + processing fns by contract) is in the
ledger; tx provenance is test-proven (`receipt_write_carrier_test`); the
`:seon.db/user` stamp is the gap.

## 2026-09-04 (late night) — the second opinion landed: NOT READY

[second-opinion-2026-09-04.md](../research/second-opinion-2026-09-04.md)
(Codex, no session context, claims verified at the bytes, probes re-run
on its own root): the agent-centric target is faithful to the wanted
EXPERIENCE and wrong about what it forces the database to own. Refuted:
"every identity attribute names one family" (41 pairs / 38 attrs); "one
pull is the record" (Datahike pulls ≤1,000 children per attribute
silently); "eval + process stamps" (the test proves receipt + user);
components as universal ownership (one child under two owners, cascade
measured); several agents per namespace (HEAD enforces one owner).
Most likely failure: deleting the run/receipt authority. Three owner
decisions before code (view vs ownership; turn authority; bounded
collection contract) and the first build = a transaction-only crash
falsifier, not `(help)`. The design doc's §4–§6 carry the corrections and
§9 the verdict. Lanes quiet.

## 2026-09-05 — the graph perspective

Owner: the model is agent-centric in the GRAPH sense — any attribute on
any entity; the agent's plumbing (turn, process, trigger, context basis)
are attributes ON the agent entity; evals, messages, faults, and domain
rows point AT the agent by refs readable from both ends; no inbox table,
no information hiding; refs to what it does not own (namespace, code,
cluster). Context = discover the agent's attributes and connections →
propose forms (intent comment + query WRAPPED in the render function it
names) → derive the order → execute all in parallel → render; the
transcript is discovery + wrapped queries + the agent's own forms. Root is
the same discovery with a different projection (`agents-summary`).
Written into the design doc §2–§3 with the inventory of what every agent
cares about (§3.1) and root (§3.2); §9 records how this answers the
reviewer's three decisions. Still no code; the crash falsifier is first.

## 2026-09-05 (later) — three projections of one record

Design doc §3.3: the transcript is a projection of the agent's data
(comments, forms, results by execution time) with THREE consumers and
formats — the SCI environment (installers: require, install-def,
bind-result, wrap-contract; dependency order), the completion prompt
(`/ai` functions; the layout order; teaching pre-requisites as a demand
DAG), the page (`/html` functions; header · transcript blocks with
highlighted source, markdown prose, handle chips · panels = any function
returning hiccup over the agent's data, requested by evaluating it). One
composition rule; pre-requisites derived per consumer; compaction touches
no fact. Atlas v4 adds the "Three projections" view (raw datum → entity
and links → the function and order each projection gives it).

## 2026-09-05 (evening) — the Design Lab PRD

Owner: the atlas is made-up data; he wants the real thing — a
programmatically driven visualization of ACTUAL entities (all attributes
namespaced, actual values, links both ways) where clicking shows the
render candidates, their outputs on that data, and the assembly into the
SCI environment, the completion prompt, and the page; Clojure against the
real database so schemas and designs can be tried on branches before any
production code. PRD:
[design-lab-prd-2026-09-05.md](design-lab-prd-2026-09-05.md) — Cytoscape.js
inside the existing Datastar page, one dev namespace `seon.dev.lab`, four
waves (graph of real data → candidates + outputs → scenarios incl. diff
against the real captured prompt → experiment branches + decision log).
"Family" is retired as a word; the PRD says "entity schema". The atlas's
hand-typed MODEL retires when W1 lands.

## 2026-09-05 (night) — the Design Lab, ruled shape

Owner rulings on the lab (PRD §9): a dev route in the existing server; the
lab IS `/agent/{id}/debug` (no new names, no new infrastructure — the lab
is a cluster run by the existing operator); fresh environments from a
load definition kept as real Clojure; everything real (functions are
program rows; agent functions via real turns); visualize the context to be
sent through the existing debug machinery (no paid runs); ordering is
algorithmic and live, local to each projection (context = logical order,
page = layout); the first steward works `my.note`; depth one hop, expand
on click; trying a storage format = a code branch + reset. Agent creation
is the existing route (the manifest declares no agents — corrected).

## 2026-09-05 (late) — the Lab PRD rewritten whole; review running

The PRD accreted rulings into contradictions; rewritten as one document
([design-lab-prd-2026-09-05.md](design-lab-prd-2026-09-05.md)): why, one
sentence, the rulings as constraints (no new infra/names — the lab IS
`/agent/{id}/debug`; everything real; fresh envs from real Clojure;
visualize the context to be sent; ordering algorithmic and local; agents
have a home namespace and may steward others; first world = a steward of
`my.note`), the page, what it computes, waves with acceptance, the
questions it answers, risks. Owner is reading; lane `prd-review` (no
session context) critiques it into
`research/design-lab-prd-review-2026-09-05.md` and measures init/start
timing on its own root. The agent-centric design §2.1 now carries
`:namespace` (home) + `:stewards`.

## 2026-09-05 (night, later) — the PRD review landed; PRD revised

[design-lab-prd-review-2026-09-05.md](../research/design-lab-prd-review-2026-09-05.md):
"do not start wave 1 from this PRD yet" — the PRD specified a UI without
first specifying the bounded observation VALUES it consumes, so empty
graphs and unavailable prompts could pass acceptance. Measured: full
publication 60.6 s + start 11.3 s (the 15 s reset was a target, not a
fact); the fresh debug page returns HTTP 200 with an UNAVAILABLE
prospective prompt while its test passes on a fixture that supplies what
production omits; `producer`/`candidates` are private; no stewardship
attribute; Cytoscape not vendored; the web service owns one connection.
PRD revised: wave 1 split into 1A (pure `neighbourhood-page` contract) and
1B (graph); public `candidate-explanation` + read-only diagnostic boundary
named for wave 2; a shared entry value + an SCI-description owner for
wave 3; connection owner + compatibility fence + same-origin POSTs for
wave 4; decisions recorded in the design doc, never on the disposable
cluster; the decision procedure ("best", "done") written into §0.
Precondition lane launched: fix the prospective prompt through the
production request shape. Nothing else coded.

## 2026-09-05 (night, platform round) — "get it running and fix what we know"

Owner: fix the baseline and every known issue on code that stays; reset
is too slow ("deleting info and reloading — make sure we aren't doing dumb
shit"); `defn-` is a convention, not a restriction (the review's "private
API" objection is void); no stewardship attribute now; the lab is not a
separate system — the same machinery at the global (`/`), namespace
(`/ns/{ns}/debug`), and agent (`/agent/{id}/debug`) levels; make
iterating on the data and previewing its assembly easy. PRD simplified
accordingly. Honest state: the platform TIER is green but the issue index
carries ~50 open blockers, many in render/context code the redesign
replaces (left alone); the ones that tax every loop are in flight. Lanes
running: `prospective-prompt` (the fresh debug page's unavailable prompt;
its test passes on a fixture that supplies what production omits),
`init-speed` (publication 60.6 s measured — profile the phases, remove
the dumb work), `print-floor-strings` (zero-character string windows +
`get_value` paging + unconditional fit at the MCP result projection), `status-face`
(bound the 100 KB UNKNOWN wall + PROVE stale-green live end to end),
`run-loop-velocity` (2.4 s between every form). Queued behind
loop.clj: reply durability (prose-only replies as facts; no-forms
replies get a correction turn), message completion from the wrong agent.
Also to fix: Codex lanes are not given the Seon MCP server (Claude
launches `bin/mcp-server` via `.mcp.json`; `~/.codex/config.toml` has no
`[mcp_servers.seon]`).

## 2026-09-05 (night, later) — status-face collided; relaunch after init-speed

`status-face` stopped without committing: `init-speed` (granted
`fresh_operator.clj` "for the init command's own steps" — the
orchestrator's ownership mistake) edited the same file mid-lane. Its
uncommitted work sits in the tree (fresh_operator.clj, runner.clj, the two
test files): one aggregate loud UNKNOWN line by default, bounded
per-namespace summaries with evidence, details behind `status --verbose`,
and a real finding — bare `bin/test` hard-wires persistent evidence to the
checkout root despite `SEON_TEST_RESULT_ROOT`, so the isolated stale-green
proof was impossible without their routing fix. Relaunch as
`status-face-2` once `init-speed` has committed its `fresh_operator.clj`
hunks; the proof of stale-green live is still owed.

## 2026-09-05 (night) — run-loop velocity: one term removed, gate blocked by the in-flight tree

`4e68150f5` (lane `run-loop-velocity`): receipt settlement rebuilt the
COMPLETE schema projection inside every settlement transaction
(`run.clj:1596`) although the outer `:db.fn/call` codec already carries it
— 360–550 ms per form, removed; regression asserts settlement never
re-enters `projection-from-database`. Measured per-form table: evaluation
47–60 ms; `runtime-analysis` 227–309 ms (the cached prelude is reached but
the form is reparsed — the next term); settle call 414–629 ms before the
fix. The focused gate could not run: the shared test base's indexer
refused `Conflicting upsert: "seon.fn.index/10477" resolves both to 593
and 2994` — the test base compiles the WORKING TREE, which carried
`init-speed`'s uncommitted `fn.clj` edits; a lane's gate is hostage to
another lane's half-edit (the torn-tree class). Remeasure + the 200 ms
target relaunch as `run-loop-velocity-2` after `init-speed` lands, together
with `status-face-2`.

## 2026-09-05 (night) — three verified fixes parked by the torn tree

`prospective-prompt` verified its root cause and fix (the shared
`walk-request` now carries `:seon.cluster.agent/id`; the debug page went
from `unavailable` to a non-empty prospective prompt on a hot-reloaded
isolated cluster; regressions go through the production route) but could
not run its gate; same for `run-loop-velocity`'s remeasure and
`status-face`. Cause, filed as a blocker:
[bin-test-shared-base-compiles-other-lanes-half-edits](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md)
— the shared test base compiles the WORKING TREE, so `init-speed`'s
in-flight `fn.clj` edit refused every other lane's base. Relaunch the
three (`-2` names) the moment `init-speed` commits; then decide the
`bin/test` base option (recommended: HEAD + the lane's own files).

## 2026-09-05 (night) — print floor fixed in the tree, parked too

`print-floor-strings`: root causes verified (fit reduced strings before
breadth and depth to zero; `mcp-project` fitted only above the blob
threshold; `mcp-get-value` treated strings as unpaged scalars); fix
implemented — breadth → depth → strings with the existing 72-character
print-width floor, unconditional fitting through a `:seon.render.profile/mcp`
profile, character-offset string paging — with a GREEN focused tally
(29 tests / 136 assertions) before a fixture refinement; the rerun was
refused by the torn base like the others. Four lanes' verified changes now
sit uncommitted in the tree on their owned paths; relaunch specs prepared
(`tmp/lanes/*-2.md` + `_relaunch.md`) to fire when `init-speed` commits.

## 2026-09-05 (afternoon, day 2) — publication: dumb work removed, the Datahike floor found

`f557c3a17` (lane `init-speed`): source bytes/line offsets/ns forms derived
ONCE per population (were re-derived per row); program references compiled
to transaction-local tempids; namespaces, declarations, keyword facts, and
calls enter Datahike in ONE population transaction (were five dependent
ones). Instrumented publication 56.3 → 38.4 s; kondo 7.1 → 2.5 s (it
analyzes only src/ + test/, 236 files, cache already used). Cold `init`
68.4 → 63.3 s only, because the floor moved: ONE Datahike commit of
207,915 datoms takes 26.0 s (~8k datoms/s) and JVM + operator load is the
rest. Lane `datahike-bulk-commit` decomposes that commit (index type,
`:keep-history?` on the publication branch, tempid/lookup resolution,
derivable attributes ruled dead by 50, chunking, konserve flush). The
four parked lanes relaunched as `-2` on the whole tree.

## 2026-09-05 (day 2, afternoon) — the lab's precondition is green

`e28de63bc`/`524c4cd08` (lane `prospective-prompt-2`): the shared
`walk-request` carries `:seon.cluster.agent/id`, so a fresh cluster's
`/agent/{id}/debug` renders a real prospective prompt (cold isolated proof
pasted in the summary); regressions go through the production request
constructor, require a non-empty prompt, verify custody inputs, and prove
no render-cost facts are written; an unavailable pane keeps HTTP 200 (the
page is composite) but must show its diagnostic and no healthy `<pre>`.
Issue archived. `bin/test seon.render.web-test`: 40 / 330 / 0. The Design
Lab PRD's precondition (§6) is met; wave 1A waits only on the owner's go.
Shared root reset to HEAD and `default` running at http://127.0.0.1:7994
(this fix lands there on the next restart).

## 2026-09-05 (day 2, late afternoon) — run-loop velocity and the print floor landed

`run-loop-velocity-2`: gate `bin/test seon.cluster.loop-test
seon.cluster.turn-test` 80 / 500 / 0; the redundant settlement projection
build (360–550 ms per form) is gone (`4e68150f5`), fixtures repaired
(`25adaeaf8`), the issue updated with the measured table (`c2cc606d0`).
Remaining floor per form: `analyze-form` 228–311 ms — the cached prelude is
reached but the form is REPARSED; next lane `analyze-form-speed` (fn.clj)
once `datahike-bulk-commit` releases fn.clj. Also open from that drive: the
scripted terminal `my.run/complete` form hits an admission refusal.
`print-floor-strings-2`: `d5b212f88` (breadth → depth → strings, one-line
floor; the MCP result projection fits every result under a `:seon.render.profile/mcp`)
and `ba3518afb` (`get_value` pages strings by character offset; never an
empty window) — issue archived. Owner asked for a clean tree: two lanes'
in-flight work committed path-limited (`b3ba7d998`, `25adaeaf8`), tool
caches ignored. Lanes reported the MCP tools "unavailable" during the
window when the shared root was being reset (ctxprobe down, default
starting) — not a config regression.

## 2026-09-05 (day 2, evening) — status face landed; the silence backstop now blocks gates

`status-face-2`: gate `bin/test seon.test-runner-test
seon.dev.fresh-operator-test` 60 / 447 / 2 failures / 0 errors — the two
failures are ONE class: the isolated confirmation of
`live-init-reloads-schema-runtime-and-moved-predicate-owners-before-admission`
trips the prepl silence backstop (30 s) while the population commit runs
silent (`fn.clj:1965`) — the already-filed blocker
[init-program-population-can-still-trip-the-silence-backstop](../../../seon/issues/init-program-population-can-still-trip-the-silence-backstop.md).
The status-face code itself (bounded default face, `status --verbose`,
`SEON_TEST_RESULT_ROOT` precedence, regressions) is committed
(`b3ba7d998`) and its own regressions passed. STILL OWED: the after-status
output and the isolated stale-green live proof — both blocked by the same
backstop. Priority: the population commit must report progress inside the
transaction (or be split/fast) — owner `seon.fn/index!`'s progress path;
lane `datahike-bulk-commit` (running) owns that seam and gets the note on
its return.

## 2026-09-05 (day 2, evening) — a second session is working in the tree

Uncommitted edits from another session (not this orchestrator's lanes):
the Design Lab PRD rewritten (−296/+278 lines, uncommitted), four rows
added to `docs/seon/issues/index.md`, a new research doc
`research/design-lab-investigation-2026-09-05.md` ("the owner explicitly
reopened the design: defaults bootstrap every namespace's agent, each agent
may query any connected data and define its own render functions, and the
current UI is not the desired design"), and three new render issues
(candidate checks mix arities; selection loses the viewing namespace;
namespace layout confines content to scroll boxes). This orchestrator
PRESERVES them untouched and does not edit the PRD until the owner says
which session's version is authoritative. `store.clj`/`fn_test.clj`
modifications are lane `datahike-bulk-commit`'s in-flight work.

## 2026-09-05 (day 2, night) — the publication floor was fsync amplification

`e8d218690` (lane `datahike-bulk-commit`): the 26.0 s population commit was
konserve forcing EVERY index file to disk — persistent-set's default
branching factor 512 produced 3,029 durable index writes for 207,915
datoms (pure Datahike work 2.73 s; with force barriers off 2.62 s).
Branching factor 4,096 at `store.clj:168` → 357 writes, commit 4.87 s
(−81%); fresh `init` 39.6 s (from 63; JVM + operator load is the rest).
Gate `seon.fn-test seon.cluster.source-test` 38 / 307 / 0. Decomposition,
rejected levers (history on the publication branch, chunking, tempids),
and the top-ten attribute census are in the publication issue. Fallout:
`store_test.clj:85` kept the old literal (lane `store-config-expectation`
fixes it and CONFIRMS the silence-backstop blocker is cleared by
consequence); existing stores keep 512 until reset — the shared root was
reset and `default` restarted on HEAD (all of today's fixes live at
http://127.0.0.1:7994). The other session committed its PRD rewrite
(`c965333cf`); this orchestrator has not read it yet.

## 2026-09-05 (day 2, night) — ruling 67: batch the turn

The owner called the per-form bookkeeping an algorithmic failure and ruled
the shape: evaluate every form of a reply quickly (fixup on failure; the
parser never crashes the turn), bind results as real `result/<id>`
symbols in the fork, write the results in ONE transaction. Lane
`turn-batch` (relaunch of `per-form-overhead`, stopped after its brief):
intent tx at reply arrival (raw reply + eval rows with source — durable
prose-only replies included), evaluation in memory with handle binding,
one kondo batch for defining forms, one settlement tx (values not print
nodes; read-evidence = plans + revisions, no stored read-result), render
once; crash falsifier as the regression. Confirmed today by lane
`store-config-expectation`: the silence-backstop blocker is gone by
consequence (`seon.dev.fresh-operator-test` 34 / 219 / 0; fresh init 38.9 s,
no trip); `store_test` derives the index config from the declaration
(`ab813db44`, `770de4c8b`). The other session keeps committing lab PRD work
(`ffdd7a308`).

## 2026-09-05 (day 2, late night) — indexing settled; usage and error facts

Owner rulings in dialogue: (1) only DEFINITIONS index (function, test,
schema, agent def); their facts come from the var after evaluation, the
Malli form, and ONE kondo batch per turn over the defining forms with the
real `ns` form and no stub prelude (verified: ~10 ms; a let-bound `map` is
not recorded; a qualified ref to a namespace absent from the `ns` form IS
dropped, so the ns form must require every namespace the source mentions).
Ordinary forms record nothing static. (2) Owner wants every downstream
question answerable (callers, tests reaching, schemas to explain, usage by
other agents, errors while using a function, hotspots, dead weight) and
stewards/root woken on problems with the erroring agent's context pulled
in → design §2.4b (`:called`, `:failed-call` on the eval row from the
call-preparation hook) and §2.4c (the steward's wake: one `route!` case +
the existing watch + render fns following refs). Lane `turn-batch`
resumed twice: once for the indexing correction, once refuting its
"another lane's change" attribution (runner.clj:1057 auto-gensym is HEAD
code; the reader test asserted form-equality Clojure never promised).
Follow-up for the lane once its gate is green: §2.4b's two attributes +
hook accumulator in the settlement tx. Owner: NO automatic wakes or
agents reacting to these facts yet (§2.4c marked LATER) — capability only. bin/codex-agent resume now names
the model (`4a683cd6b`).

## 2026-09-05 (day 2, late night, 2) — ruling 68: the forgiving parser returns

Owner: restore all the forgiving-parser behaviour; repair delimiters
before reading and store the FIXED source (no repaired flag; the raw reply
in the intent tx is the provenance); show an error only when repair
cannot make the form read. Lane `turn-batch` redirected a third time
(reader recovery invariants + old corpus, ruling 68 repair in the turn).
clojure-mcp pulled to v0.4.0; its ideas summarised to the owner (repair
before eval, Edamame structured delimiter errors, error-flag discipline,
clojure-mcp-light hook-based repair for CLI assistants).

## 2026-09-05 (night, 3) — batched turn LANDED (`1f3c099d2`); the 191 GiB incident

- **Landed:** rulings 67/68 as code — intent tx + in-memory eval with
  `result/eN` + one settlement tx; definitions-only kondo batch without the
  stub prelude; `:seon.db/read-result` retired; reader recovery invariants
  and nine old regressions; span-local parinferish repair before reading
  (fixed source stored, no flag). Six-form bookkeeping 9.9 s → 196 ms.
  The orchestrator finished the last two regressions after stopping the
  lane (owner: "kill the agent, make the changes yourself"). Left behind:
  `seon.cluster.work` still derives retired E3 routing states (noted in the
  velocity issue); the evaluator now refuses recovered error events.
- **Incident:** `data/store` 0.29 → 191 GiB in 36 min (issue
  `fault-facts-store-megabyte-evidence-inline-and-rewrite-gigabyte-leaves`):
  fault facts inline 4.3 MB proc-state evidence × persistent-set leaf
  copy-on-write at branching 4096 × history. Reset reclaimed it; the fresh
  default then refilled 0.5 GiB in 3 min because `bin/seon reset` published
  the TORN working tree (lane's half-done loop) — torn-tree class, second
  bite today. The lab session owns the committer fix (`error/prepare` with
  an inline limit is in its tree); owner: NO automatic wakes on these facts
  yet. Follow-ups owed: a byte guard at the one `seon.db` write seam;
  reset/init from committed HEAD; re-measure branching 4096 against leaf
  bytes.
- Lane orchestration lesson (owner, "2+ hours for this?"): four redirects
  and a gate-everything-then-commit rule cost the afternoon; commit
  coherent slices, name foreign breakage, redirect at most once.

## Previous state (2026-08-29, evening)

**Design track (owner still forming — NO implementation until he says):**
rulings 47–55 sealed in the [ledger](design-ideas-ledger-2026-08-13.md):
the population invariant + symbol identities (47), scalar identity +
result projections + the rename pass scope (48), keys-law as amended
(49), the full-parse bridge (50, design verified against kondo:
[full-parse-bridge-design-2026-08-29.md](full-parse-bridge-design-2026-08-29.md)),
graph closure + settled-form usages + derived self-improvement (51),
VIEW-1-ONLY stable regeneration + coverage-set help + backward
demand-driven generation (52/52a/52b), faces return forms (53), the
database transaction function + missile rule + steward drive scenario (54), instance
args (55). The consolidation of 47–55 + the
[render-data plan](render-data-plan-2026-08-28.md) into one
implementable generator spec is OFFERED, awaiting the owner's go.

**Base-system track (active):** platform tier GREEN; bulk tier legible
(runner: serialized loads, bounded exchanges, one-retirement-one-
failure); walk acquisition 8.5 ms; hook feedback restored after a
15-day silent outage; fixture derivation primitive + 53-site sweep
landed; five graph-consequence regressions fixed; schema lifecycle
over persisted references repaired; the bare-remainder singletons
landed (`c5036aaa2`) — and their one refused red exposed the
25-minute curation replay storm, killed by the population-revision
prelude cache (`e8c8ea6d0`: the prelude derives once per program
population, not once per settled form). The masking meta-lesson:
four Aug-14 breakages hid behind the 12-day bulk blackout.

## The ordering (owner rulings, 2026-08-29 question round)

1. **Doomed-nine deletion pass** (owner: bare reads fully green before
   any rename): delete dead `walk/prose` + `effect/context-suffix` (+
   their tests); neutralize the 6 `render.web-test` + 3
   `render.value/ns-test` reds with wave-G/S2-F issue links — never
   polish, delete or park with a named replacement.
2. **Stale-green visibility lane** (before the freeze): persistent
   operator-owned bare-gate results branch; `bin/seon status` derives
   per-namespace "all current tests last known green; oldest proof
   basis T, N days ago"; unknown ≠ green. Bundles the dev-cache
   `ensure-cache` wiring (same never-stale-silently class).
3. **The atomic identity freeze** (orchestrator, quiet tree):
   `:seon.fn/sym`/`:seon.test/sym` string→symbol + the sym↔`/ns` drift
   regression (47/48a) + receipts→evals rename (48c). Retype + reset,
   never migrate. `bin/seon init` + full gates close it.
4. **The full-parse bridge lane** (50, born compliant on the clean
   identities) — then result projections at settlement (48b).
5. Then the generator work — gated on the owner's context-design go.

## The armed-boot regression round (2026-08-29 night, lane running)

The distance-2 bootstrap fix (28 s -> 1.2 s per advance, measured
live) unstarved the armed backstops and exposed three REAL
regressions from the day's landed work, now with the
`armed-regressions` lane: (1) BOOT SPENDS A MODEL CALL — the
no-model-at-boot gate broke (`booting-spends-no-model-call` red,
`:seon.ai/no-credential` where an injected kind belonged); (2) a
boot-window message's run opens with trigger `bootstrap-task:root`
instead of the message; (3) an agent's own def fails to resolve in
its live ctx across cohosted clusters. Evidence root:
`tmp/test-runs/run.FiH5MT`. ROUND OUTCOME: the armed-regressions lane
closed the model-call gate (bootstrap closes atomically before the
model boundary, `108b753ca`) and grounded the cross-cluster fixture
(`801347921`); the orchestrator fixed the backup-target expectation
(402-failover config), and the exchange-vs-watchdog horizon collision
(exchange bounds now fire strictly inside the silence horizon). THE
FREEZE REMAINDER IS EXACTLY THREE: (1)
`the-first-cluster-proc-fault-at-resume-becomes-a-fact` — the injected
first resume fault never reaches its terminal worker event (real
behavior question in the fault-at-resume path); (2)
`a-message-committed-during-boot-arming-is-conserved` — the test
conflates run opening with downstream provider progress and needs its
await reworked onto the run-open fact; (3) the standing seed-recorded
generated-attempt-traces blocker.

## Open blockers the edge tracks

- [generated-model-attempt-traces-diverge-from-durable-facts](../../../seon/issues/generated-model-attempt-traces-diverge-from-durable-facts.md)
  — exact seed `202607280402` + shrunk case recorded; the one
  legitimate red expected in bare until fixed.
- The 69-GB store-growth class (exclusive-sweep wave) and the
  dev-cache staleness issue (rides item 2).
- `effective-config` deferred census rows in lane-protected files
  (effect_test launcher rows 2–3) — sweep them when those files quiet.
- `seon.cluster.curate-test` re-verify after the visibility lane
  lands: its residual red ("first-party program namespace
  seon.dev.fresh-operator-test could not be loaded") coincides with
  that lane's in-flight edits to exactly that file — torn-snapshot
  suspicion, not yet attributed.

## Standing session-start line

Read the
[context-as-queries handoff](context-as-queries-handoff-2026-08-29.md)
first — it is the entry document for the next session (the goal, the
owner's "it's all queries" idea to explore WITHOUT rushing, the trials,
and the platform gate). Then THIS file end to end, then the ledger's
newest rulings, then `bin/seon status` + `git log --oneline -15`.

## Design lab implementation resumed — 2026-09-05

The owner authorized implementation and smaller models for simple delegated
work. The current checklist and visual sequence live in the
[design lab PRD](design-lab-prd-2026-09-05.md#visual-overview); ownership and
REPL evidence live in its linked investigation. Terminology review is complete.
The same-arity renderer selection fix is in progress in `seon.schema` and
`seon.render`, with schema and candidate-selection regressions. Candidate SCI
metadata isolation must integrate with the concurrent turn-batching edits in
`seon.sci.eval`; preserve that work. No milestone is complete merely because
the helper exists: focused tests and a named live proof remain required.

At this checkpoint MCP `runtime_status` answered for `default` (PID 94171,
start 2026-09-05T19:02:38Z), reporting eight stale Vars and four errored
receipts. This is connectivity evidence, not a clean runtime baseline.

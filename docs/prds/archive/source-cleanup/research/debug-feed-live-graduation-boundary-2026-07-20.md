---
type: research
status: active
tags: [research, prd, architecture, testing, web, database, agent]
---

# Debug-feed and turn-debug live graduation boundary (2026-07-20)

## Decision

The two turn-debug defects have source and focused regression coverage, but
neither issue has its required live `/agents/run` proof. The debug-feed issue
is more than a missing live observation: the current source preserves the
database value but drops the execution child's Datahike read evidence before
the shared reactive owner installs the debug subscription's writer interest.
The feed can therefore miss a commit that changes only prompt data.

Do not close any of the three notes from the existing focused tests. First
repair the one evidence handoff through the existing prompt and feed
mechanisms. Then run the focused gates and the server-side frozen live matrix
below. No browser-agent observation can substitute for the server-side SSE
and writer-interest evidence.

## Dependency ledger

Audit source revision: `6c81f02655e6`.
Concurrent tracked edits outside this report were present and are not evidence.

| Dependency or Seon owner | Selected revision | Grounding and consequence |
|---|---|---|
| Datahike | `6f2569087ed3` | `reference-code/datahike/src/datahike/query.cljc` owns execution-aware, source-positioned query dependency plans; `pull_api.cljc` derives pull dependencies; `api/types.cljc` defines `:all` as the conservative alternative. Seon must transport those plans, not infer attributes from query text. |
| Datastar | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | Datastar supplies the morph event vocabulary. The maintained Seon framing owner remains `seon.web.datastar/patch-elements`; no debug-specific stream is authorized. |
| Datastar Clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` grounds a separate long-lived SSE GET and flushable frames. Browser automation cannot reliably hold that stream, so the feed proof is server-side. |
| Execution IPC | current Seon revision | `seon.execution/terminal-message` already carries the child's optional `:seon.db/read-evidence` in the closed result frame. No protocol addition is needed. |
| Prompt owner | current Seon revision | `seon.agent.turn/render-prompt` is the one prompt orchestration door and validates the returned database value. It currently selects only `::execution/result`, losing sibling child evidence. Strengthen this owner in place. |
| Reactive owner | current Seon revision | `seon.web.datastar/render-read` combines parent-captured and renderer-embedded evidence; `seon.reactive` installs and replaces the one writer interest from that evidence. The downstream mechanism already exists. |

First-party behavioral precedents are
`test/seon/web/datastar_test.cljs` (`complete-render-returns-event-value-and-child-read-evidence`,
`child-message-read-evidence-is-authoritative`, and the equivalent-socket
test), `test/seon/agent/debug_test.cljs` (one database value, pull-ref
projection, and failed-query short circuit), and
`test/seon/web/serve_test.cljs` (shell and `/agents/run` evidence behavior).

## Current source truth

### Immutable database threading is landed

`seon.web.debug/debug-feed-definition` receives the database value supplied by
the shared reactive computation. Its renderer passes that exact value to both
`agent-debug/ctx-preview` and `system/acquire-fleet-summary`.
`agent-debug/ctx-preview` passes the same value to
`turn/render-prompt`. This closes the original second-dereference defect and
prevents one render from silently mixing database values.

The current focused tests prove the parent-visible part of this contract:

- `preview-keeps-system-and-context-on-one-database-value` passes the exact
  supplied database value to the prompt owner;
- `turn-reconstruction-uses-one-ordinary-database-value` passes one value to
  both query and pull; and
- generic Datastar tests prove that a renderer may return embedded child read
  evidence and that `render-read` retains it.

These are source and local behavioral evidence, not live feed evidence.

### Child dependency evidence is currently lost

The execution child wraps the compiled prompt invocation in
`db/with-read-evidence`. Its closed success frame includes both
`::execution/result` and `:seon.db/read-evidence`. The parent receives that
frame in `turn/render-prompt`, but returns only `::execution/result` after
validation. `agent-debug/ctx-preview` consequently cannot expose the evidence,
and `seon.web.debug/render-debug!` returns only Hiccup rather than the existing
`{::datastar/element ... :seon.db/read-evidence ...}` observed-render shape.

Parent-side `db/with-read-evidence` in `seon.web.datastar/render-read` cannot
capture reads performed in another process. At present it can see the fleet
summary reads, but not the prompt child's query, pull, entity, schema, index,
or selected-render reads. Installing an interest from that partial evidence
can suppress a prompt-only relevant commit. The old issue language about
657 "foreign" observations predates the current remote evidence contract, but
its acceptance law remains: all reads that determine the view must reach the
one reactive interest.

This is the shortest falsifier for the issue. A live audit that merely reports
"some dependency plans were installed" or "the feed produced an initial
frame" is insufficient.

## Minimal source boundary

This repair belongs to the Stage-5 reactive/envelope owner after the current
`turn.cljs` owner releases its path and after the Stage-4 route/reactive cuts
freeze their contracts. It does not depend on Stage 1.5's data-browser
projection or value transport, and must not be folded into those units.

Strengthen only the existing chain:

1. `seon.agent.turn/render-prompt` retains the validated execution child's
   `:seon.db/read-evidence` beside the ordinary rendered-prompt data. Extend
   the one rendered-prompt schema with that optional registered field. Do not
   add a second prompt call or recapture reads in the parent.
2. `seon.agent.debug/ctx-preview` preserves that evidence while adding its
   display projection. Error values remain ordinary errors and conservatively
   produce broad evidence only at the shared render boundary.
3. `seon.web.debug/render-debug!` returns the existing observed-render shape:
   `::datastar/element` plus the child's read evidence. The surrounding
   `render-read` continues to combine it with parent-side fleet-summary reads
   and serialize one whole `#app-view` morph.
4. `seon.reactive` and execution IPC remain unchanged. Their existing
   evidence transport, interest replacement, equality suppression, and final
   consumer release are the one mechanisms.

The focused regression must inspect the installed evidence, not only rendered
HTML. Use a prompt-child plan whose only dependency attribute is absent from
the fleet summary and assert that the debug renderer returns it with the exact
database value and source argument position. Then prove the combined set
contains both parent and child evidence, without converting a precise vector
to `:all`.

## Turn-debug issue dispositions

### Database error consumed as entity id

Commit `0a15a116` landed the direct short circuit. The focused regression
`turn-reconstruction-preserves-a-database-read-error` returns
`::debug/ok? false`, retains the database message, and proves `db/pull` is not
called. The source therefore satisfies the local half of
[[../../../seon/issues/turn-debug-treated-database-error-as-entity-id]].

The issue stays open for two reasons:

- its acceptance explicitly requires the real `/agents/run` final evidence
  path under database-capacity refusal; and
- the source-cleanup result-union ruling replaces message-presence inference
  with the closed database result discriminator. Close the note only against
  that final owner and the live structured-error observation, not against the
  transitional `:seon.error/message` predicate alone.

### Rendered transaction pull ref

Commit `bab67136` landed projection of a pulled
`:seon.agent.turn/rendered-tx` map to its `:db/id`. The focused real-pull-shape
fixture returns numeric basis transaction `40`. This satisfies the two local
criteria in
[[../../../seon/issues/turn-debug-must-project-rendered-transaction-ref]].

The note remains open only for its `/agents/run` live criterion. The live row
must show an integer `rendered_transaction`, and the request must complete
without Malli output failure. This issue has no Stage-1.5 or Stage-4 source
dependency and may be archived as soon as that frozen observation is attached.

## Frozen live proof matrix

Run this only after all source-editing lanes hand off, the tracked artifact
inputs are clean, and the default cluster is ready at one recorded commit and
artifact digest. Preserve the owner's retained branches and caches; they are
not part of this proof. Do not start or restart the cluster solely from this
lane.

### Feed setup and initial evidence

1. Open a unique server-side client on
   `/agent/root/debug/feed?view=<owned-uuid>` with
   `Accept-Encoding: identity`. Record response status, content type, view id,
   and the first complete `datastar-patch-elements` frame containing one
   `id="app-view"` element and `id="debug-exact-prompt"`.
2. Record matching current-generation `FEED OPEN` log data and the baseline
   `seon.web.datastar/performance-snapshot` plus
   `seon.reactive/measurements`. The browser may separately confirm the shim,
   static theme, and absence of console errors, but cannot prove the SSE feed.
3. Inspect the exact debug registration's installed read evidence through one
   read-only REPL probe. It must contain the child's prompt dependencies and
   the parent fleet-summary dependencies at the same database identity. A
   blanket `:all` is honest if a real child operation requires it, but cannot
   be manufactured by lost or malformed evidence.

### Unrelated and relevant commits

Use registered, reversible probe facts on an owned disposable entity. Choose
the attributes only after inspecting the installed plans:

- the unrelated attribute must be outside every plan for this registration;
- the relevant attribute must occur only in the prompt-child evidence, not in
  the parent fleet-summary evidence; and
- neither transaction may change route, config, agent identity, fleet state,
  or the feed's own ownership.

Reset bounded measurements after the initial frame. Transact the unrelated
fact and wait past maximum reactive latency. Assert no new SSE application
frame, no increase in debug registration evaluations, no prompt-child
invocation, no Hiccup serialization, and no token-estimation work. A heartbeat
comment is not an application frame.

Then transact the prompt-only relevant fact. Assert exactly one settled debug
evaluation, one new whole-element patch, and content reflecting the new
database value. Equivalent sockets, if deliberately opened, must share that
one computation and receive byte-identical event bytes. Do not count retries,
reconnect initial paints, or another feed's work.

Close the owned stream and prove the final consumer releases its exact
reactive registration and writer interest. A later relevant transaction must
perform zero debug reads, child invocations, rendering, serialization, and
notification work.

### `/agents/run` turn evidence

Run one ordinary bounded agent request that completes a turn. Its final
evidence response must contain an integer `rendered_transaction` and no Malli
input/output exception. Query the persisted turn and show the integer equals
the pulled rendered transaction entity's `:db/id`.

For the capacity-error criterion, use the maintained bounded database
capacity-injection seam rather than saturating the live writer with
uncontrolled traffic. Make the turn-identity query return the ordinary closed
database failure. The final `/agents/run` response must contain structured
failure data, must not call pull with that failure as a ref, and must not emit
an uncaught Malli error or crash/degrade the client. Restore the seam in a
`finally` boundary and record the post-probe ready status.

## Issue closure and durable evidence

Close notes only in the unit that owns their last acceptance evidence:

| Issue | Remaining close requirement | Owning boundary |
|---|---|---|
| `debug-feed-captures-foreign-database-reads` | Child evidence handoff, focused installed-plan regression, unrelated/relevant/closed server-side live matrix | Stage 5 reactive plumbing, after Stage 4 freezes route/reactive ownership |
| `turn-debug-treated-database-error-as-entity-id` | Closed result discriminator plus frozen `/agents/run` structured capacity-error proof | Stage 5 result-union/envelope unit |
| `turn-debug-must-project-rendered-transaction-ref` | Frozen `/agents/run` integer rendered-transaction proof | Earliest frozen live checkpoint after the active turn owner releases |

Each closure records the exact implementation commit, focused test counts,
frozen HEAD and artifact digest, live agent/turn/view identifiers, server-side
SSE excerpts, measurement deltas, and post-probe readiness. Move or archive the
note according to the issue runbook only in that same path-limited commit.

## Program ordering

This finding does not displace the current dependency spine. Finish the
Stage-1.6 frozen live/browser boundary first. Stage 1.5 Units 1A through the
universal UI then establish their own projection and transport contracts.
Stage 4 freezes route rows and the reactive-router attachment before the
Stage-5 debug evidence handoff and feed closure. The turn-ref note can close at
an earlier frozen `/agents/run` checkpoint, but the error-as-entity note waits
for the ruled Stage-5 discriminator. All three closures must precede the final
twice-consecutive full-suite pair and global frozen live graduation session.

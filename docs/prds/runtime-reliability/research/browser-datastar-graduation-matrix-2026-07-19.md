---
type: research
status: complete
tags: [research, web, cljs, database, agent]
---

# Browser and Datastar graduation matrix

## Purpose

This is the exact proof plan for section 6 of
[[overnight-integrated-graduation-plan-2026-07-18]]. It is source preparation,
not live evidence. No browser, lifecycle operation, or shared default cluster
was used while writing it.

Graduation means one real browser journey and independent server-side SSE
clients agree that the current system has one behavior:

```text
browser action
  -> one HTTP action door
  -> ordinary database transaction
  -> one database interest
  -> one normalized Datastar subscription render
  -> one complete #app-view event
  -> independent socket writes
  -> idiomorph updates the live DOM
```

The matrix deliberately separates three kinds of evidence:

- **browser evidence** proves controls, focus, DOM transitions, tabs, and
  console behavior;
- **server-side feed evidence** proves the long-lived identity/gzip stream,
  complete events, reconnect, backpressure, and prompt delivery; and
- **database/process evidence** proves actions happened exactly once, agents
  and namespaces are correct, errors are durable when promised, and all
  interests/children are reclaimed.

The in-app browser bridge is not the SSE authority: its network layer may
return 503 for an otherwise healthy long-lived event stream. A browser claim
therefore never substitutes for a decoded server-side event and pod
measurement.

## Dependency ledger

| Source | Selected revision | Constraint used by this plan |
|---|---|---|
| Datastar | `reference-code/datastar` at `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | `@get` reconnects with bounded backoff; `openWhenHidden: false` closes while hidden and reopens on visibility; form submit is automatically prevented; `data-bind` supplies input/select/checkbox signals; whole-element patches use idiomorph. |
| Datastar Clojure | `reference-code/datastar-clojure` at `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | Separate long-lived GET and gzip flush are the maintained SSE idiom. |
| Reitit | `reference-code/reitit` at `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | One compiled Ring handler dispatches database-projected routes; handler symbols may resolve late without a second router. |
| Bun | `reference-code/bun` at `d8ecf098572e2b8265b23e40c04efb4067e516cc` | `Bun.serve` owns HTTP; direct `ReadableStream` controllers expose socket backpressure and asynchronous flush independently per response. |

First-party owners:

- `src/seon/web/serve.cljs` — `Bun.serve`, `/agents`, `/chat`, process-control
  POSTs, and installed route dispatch;
- `src/seon/web/router.cljs` — one reitit route projection and `/call` route;
- `src/seon/web/datastar.cljs` — one feed registry, selective interest,
  coalescer, rendering, serialization, gzip/direct writers, and socket
  lifecycle;
- `src/seon/web/reactive/{transform,call}.cljs` — handler-form rewrite,
  data-only arguments, capability gate, and supervised function invocation;
- `src/my/canvas.cljs` — button, input, select, toggle, form, read, and write
  interface;
- `src/seon/execution/runtime.cljs`, `src/seon/render.cljs`, and
  `src/seon/ui/agent_view.cljs` — complete child-derived agent view; and
- `src/seon/db.cljs` — one database value cache and interest owner per Bun
  process.

Existing focused proof covers the mechanics but not the integrated browser
claim:

- `test/seon/web/datastar_test.cljs` proves exact database-value rendering,
  Promise settlement, compression negotiation, selective invalidation,
  shared reconnect bytes, latest-only socket backpressure, and bounded
  measurements;
- `test/seon/web/reactive/{transform,call}_test.cljs` proves handler symbol
  qualification, shared-namespace calls, signal decoding, data-only arguments,
  capability refusal, one immutable database value, and no duplicate response
  body on Datastar success;
- `test/my/canvas_test.cljs` proves the five canvas constructors and exact
  qualified field preservation;
- `test/seon/ui/agent_view_test.cljs` proves one complete stable `#app-view`;
  and
- `test/seon/db_remote_contract_test.cljs` proves interest replacement,
  reconnect restoration, and unlisten during reconnect.

## Current source assessment

### What is ready to prove

- The page shim places the feed opener and chat form outside `#app-view`.
  A complete morph therefore cannot replace the active human input.
- The feed opener uses `retryMaxCount: Infinity` and
  `openWhenHidden: false`. Datastar source confirms hidden tabs close the
  fetch and visible tabs reopen it.
- Equivalent sockets share one `:seon.web.feed/key` subscription. A completed
  event and its exact database value become the shared reconnect result.
- Each socket owns its own direct-stream backpressure state. A blocked socket
  retains only its newest pending event; it cannot accumulate an unbounded
  queue or prevent another socket's write.
- Runtime-observed database reads become the subscription attribute set.
  Unrelated attributes skip the render; missing evidence fails open.
- Agent-authored handler symbols can come from any shared application
  namespace. The URL route identifies the execution agent; the qualified
  function symbol identifies the function. The capability query requires a
  live route agent and an agent-authored, non-private source fact.
- Canvas form fields survive the browser as fully namespaced keywords. Page
  signals are discarded when an encoded canvas field exists.
- A successful Datastar action returns an empty 204 and relies on the database
  feed for UI change, so no second response-render mechanism exists.

### Preconditions that must be closed before claiming graduation

These are not optional test enhancements. Current source cannot prove the
corresponding product claim honestly.

1. **Namespace-targeted creation has no deterministic browser control.**
   `handle-create-agent!` parses only `purpose` and calls `agent/start!`; the
   root fleet view has no namespace field. A human can message root and ask it
   to call `delegate!`, which exercises the real model path, but this is not a
   deterministic UI control. Section 6 may use that root-chat journey only if
   database evidence proves the requested namespace and exact message.
   Otherwise strengthen the existing `/agents` form and handler in place with
   optional `namespace` and initial `message`; do not add another route.

2. **A failed canvas action is not visibly rendered by the action door.**
   `/call` returns JSON 422. Datastar reports the failed fetch, but unless the
   invoked function writes an ordinary domain/error fact there is no database
   change for the canvas to render. The graduation fixture must make
   validation an ordinary function result that the handler transacts and the
   renderer displays. If product intent is to show every rejected invocation,
   strengthen the one call owner to record the existing standard error value;
   do not add client-only error state.

3. **Rapid-submit cancellation needs live falsification.** Datastar's request
   option defaults `requestCancellation` to `"auto"` and cancels the previous
   request owned by the same action cleanup. A server may already have accepted
   the canceled request. The browser test must prove the intended semantic
   count, not merely that two clicks occurred. If every deliberate submission
   must commit, configure that existing action with the appropriate Datastar
   cancellation policy or disable the control while pending; never deduplicate
   messages from text/timing guesses.

4. **Render sharing is semantic-key sharing, not arbitrary function memoization.**
   Current subscription identity is `:seon.web.feed/key` (`agent`, `debug`, or
   `data` selection). Two sockets for the same agent share computation. Two
   different feed keys that coincidentally call the same function with equal
   arguments do not. The graduation assertion must use equivalent semantic
   subscriptions, or the owner must derive a stronger key from the actual
   render identity and arguments. Do not claim a generic function cache from
   one-agent fanout evidence.

5. **Stable DOM markers are incomplete for evidence collection.** Status has
   `data-agent-state`, and primary surfaces have `data-agent-primary`, but
   message, plan, canvas values, and visible errors mostly require semantic
   text/structure inspection. Tests should prefer durable domain IDs and
   attributes where they exist. Add a minimal `data-*` evidence marker to the
   existing renderer only when text would be ambiguous; never create a test UI.

## Isolated graduation setup

The owner should source-freeze the exact revision, then launch a named isolated
cluster through `bin/seon`, on a dynamic HTTP port and its own cluster/process/
log directories. Do not use the shared default or ACME cluster. Configure gzip
explicitly for the feed phase. Record:

- Git SHA, application digest, client/execution artifact digests, Bun version,
  Datahike revision, Datastar revision, selected config digest, database name,
  database branch head, HTTP URL, and process generations;
- baseline `seon.web.datastar/performance-snapshot` after resetting only
  measurements;
- baseline database counts for agents, namespaces, messages, runs, turns,
  evals, plans, errors, and the scenario's domain identities; and
- baseline process/resource evidence and interest count.

Use one unique application namespace family such as
`my.graduation.browser.<run-id>`. The scenario agent defines one renderer and
ordinary schema'd handlers in that family; there is no test-only route or
renderer. Its canvas should show:

- a durable revision/count and last-action identity;
- one button with render-time data;
- one text input, select, and toggle inside one form;
- one visible validation/result region derived from database facts;
- the agent's current plan summary; and
- one deliberate renderer-failure switch whose failure uses the existing
  guarded render path.

Every accepted action writes a generated unique action ID plus submitted
values. This lets database queries distinguish “browser dispatched twice,”
“server committed twice,” and “one render happened twice” without guessing
from logs.

## Proof matrix

| ID | Claim | Exact action | Required observable evidence | Failure |
|---|---|---|---|---|
| B1 | Root can launch a namespace-targeted resident through the web UI | In root's own page, send one explicit request naming the unique namespace and initial task. If the deterministic `/agents` field exists, use it; otherwise send root chat and require root to use ordinary `delegate!`. | Browser receives 2xx; root and target views update without navigation reload. Database has exactly one agent connected to the exact `:seon.ns/name`, exactly one initial message with user/root `from` and resident `to`, and the created agent starts in that namespace. | Purpose-only agent, generated home namespace instead of requested namespace, duplicate resident, missing initial message, or proof based only on transcript prose. |
| B2 | A later namespace-targeted message reuses the resident | Submit a second distinct message addressed to the same namespace from root's UI path. | Same immutable agent ID; message count increases by one; no new namespace/agent; target transcript and status morph. | New agent, rewritten history, lost first message, or full page reload. |
| B3 | Agent lifecycle status is reactive | Observe idle, submit work, observe running, then completion/idle on the same DOM node. | `#agent-view-header[data-agent-state]` transitions at monotonically newer database values; decoded feed events contain the same ordered states; no navigation request. | Stale state, state from a different agent, or status changes only after reload. |
| B4 | Messages are reactive and exactly once | Keep target tab open; submit one uniquely marked chat message and allow one uniquely marked reply. | One inbound and one outbound database message; one later complete event contains both; DOM contains each once; no Promise text or duplicate bubble. | Duplicate database message, duplicate DOM item, missing reply, unordered replacement, or Promise serialization. |
| B5 | Plan changes are reactive | Agent creates a two-step plan, advances one step, then completes it while page stays open. | Database plan identity is stable; status facts advance; each committed state appears in a later complete event and DOM; unrelated facts do not fabricate progress. | New plan per update, stale plan, text-only claim without plan datoms, or reload required. |
| B6 | Canvas data changes are reactive | Pin the scenario renderer, then transact one domain change without using a browser action. | Canvas revision/value changes once; `rendered-db` advances to that database value; affected subscription count increments; no second feed/renderer. | Stale canvas, duplicate render paths, or unrelated view replacement. |
| B7 | Button carries render-time data through shared function routing | Click a button whose handler is a qualified function from a namespace different from the route agent. | Network URL retains route agent and qualified shared function; handler writes exact captured ID; database and canvas show one accepted action. | Function requalified to agent home, route refusal for valid shared function, code-shaped argument, or duplicate commit. |
| B8 | Input/select/toggle/form preserve exact namespaced fields | Enter Unicode text, select the non-default option, toggle on, and submit. | Handler receives exactly the three fully namespaced keys and no page signals; database read-back matches text/string/boolean semantics; canvas morph shows them. | Bare keys, `seon_*` transport names in domain data, omitted false/unchecked semantics, page `t/live/text` leakage, or DOM-only evidence. |
| B9 | Validation is visible and recoverable | Submit one invalid combination, then correct it in the same controls and resubmit. | Invalid submission creates one ordinary bounded validation/result fact and visible error region; corrected submission clears the derived error by changing underlying facts and shows success; agent/page remain usable. | Only console/HTTP error, stored acknowledgement flag, hidden failure, process crash, or stale error after correction. |
| B10 | A throwing renderer cannot break the feed | Toggle the database fact that makes the scenario renderer throw, then toggle it back through a separate safe control or transaction. | One complete error/placeholder morph appears; sibling header/status remains; feed stays open; root/error evidence follows configured fault policy; next clean database value self-heals. | Blank canvas, closed feed, whole pod failure for an agent-authored error, repeated error transaction loop, or manual reload needed. |
| B11 | Human input focus survives background morphs | Focus the chat input and type an unsent sentinel. While focused, cause status, plan, and canvas commits. Repeat inside the canvas text input for non-structural morphs. | `document.activeElement` remains the expected input, selection range and unsent value remain, and chat form/feed opener remain siblings outside `#app-view`. | Input replaced, value reset, caret jump, duplicate opener, or accidental submit. |
| B12 | Rapid submissions have explicit semantics | Dispatch five uniquely marked submissions faster than one render settle window; do the same with the canvas form. | Expected database action/message count is exactly five (or the explicitly documented single-latest policy); IDs are unique; no hanging request; final canvas equals latest committed database value; focus remains usable. | Ambiguous count, canceled-but-committed duplicates, missing accepted requests, accidental double action, or unbounded render queue. |
| B13 | Reconnect repaints the current view | Open decoded gzip feed, capture event/database evidence, disconnect it, commit while disconnected, and reconnect with a new socket. | First reconnect event is a complete current `#app-view`, not a replay dependency; gzip header and prompt flush are valid; completed shared bytes may be reused only with their exact database value. | Stale first repaint, dependency on lost event ID, malformed gzip, or missing current commit. |
| B14 | Pod restart restores browser and tool paths | Keep browser page and server-side client retrying, restart the isolated pod normally, then invoke a real child-backed render/action after readiness. | New pod generation, same database/program/agent identity, Datastar reconnect and full repaint, tool reconnect selects the new runtime, one replacement child, no stale old-session completion. | Page needs manual reload, tool pins dead runtime, duplicate action, lost program, or old child/session survives. |
| B15 | Equivalent feeds render and serialize once | Open 10 sockets with distinct safe `view` IDs for the same agent feed, reset measurements, commit one relevant change. | `view-count=10`, `subscription-count=1`, `render-started=1`, `render-completed=1`, fanout sample 10, identical event bytes and database value on all sockets. | Ten renders, unequal bytes, mixed database values, or an arbitrary function-cache claim beyond the shared feed key. |
| B16 | Unrelated writes skip rendering | With B15 sockets open, transact an attribute outside the learned dependency set. | Database value advances; affected subscription count is zero, skipped count is one, render-started remains zero, cached complete bytes advance only under the source-proven unchanged rule. | Render starts, dependency set misses a genuinely read attribute, or view becomes stale after a relevant follow-up. |
| B17 | Slow client cannot block fast client | Open one socket that stops consuming after its initial event and one continuously reading socket. Commit a bounded burst of uniquely numbered relevant updates. | Fast client promptly receives the latest complete state; slow socket records backpressure and pending replacements, retaining at most one pending application event; render counts are shared; pod event-loop delay remains bounded. | Fast-client latency follows slow client, pending queue grows with burst, render duplicates per socket, or process memory grows without bound. |
| B18 | Hidden tabs and closed tabs release feed interest | Open two tabs for the same agent, hide one, close the other, then reveal and finally close the hidden tab. | Visible pair initially means two views/one subscription. Hidden tab closes its socket because `openWhenHidden=false`; reveal opens one fresh socket/current repaint. Final close yields zero views/subscriptions, uninstalls `::views` interest, and stops heartbeat/coalescer ownership. | Hidden feed stays permanently live, reconnect creates duplicate view ownership, final database interest remains, or heartbeat/timer leaks. |
| B19 | Independent tabs do not fight local focus | In two tabs choose different surface rail selections and keep different unsent chat values while common database updates arrive. | Each tab retains its own Datastar `selected` signal and input value; both receive the same database-derived content; neither writes focus into shared database facts. | One tab changes the other's selection/value, focus persisted as global agent state, or one tab stops updating. |
| B20 | Teardown reclaims all transient owners | Close all browser tabs and raw feed clients, stop/retire the scenario agent child through normal product/operator behavior, then inspect after idle timeout. | Zero Datastar views/subscriptions/active/pending renders; no `::views` database interest; no heartbeat/coalescer timer; execution child absent after retirement; durable agents/messages/plans/domain facts remain. | Live socket, listener, timer, child, or pending Promise remains; or durable database facts disappear with process state. |

## Exact execution order

The order avoids using later behavior to hide an earlier failure:

1. Record source/process/database baseline and create one browser-owned tab.
2. Run B1–B2 and query database identity before continuing.
3. Navigate to the resident and run B3–B6.
4. Install the one scenario canvas through ordinary agent-authored code; run
   B7–B10 and query every action identity.
5. Run B11–B12 while capturing browser network requests before each action.
6. Open server-side identity and gzip clients; run B13.
7. Run B15–B17 from a reset Datastar measurement snapshot.
8. Run B18–B19 with tabs created and owned by this proof only.
9. Coordinate a source freeze and run B14 through the one operator. Do not
   hand-kill the pod or child.
10. Run B20, retain logs/metrics/database queries/screenshots, and stop the
    isolated cluster through the operator.

If any step changes source, discard all later evidence, build one new exact
artifact under a coordinated freeze, and restart the matrix at step 1.

## Evidence capture

### Browser

For every browser action record before/after:

- URL, tab ID, page title, `#app-view` identity, and no-navigation assertion;
- relevant `data-agent-state`/`data-agent-primary` and semantic DOM text;
- `document.activeElement`, input value, selection start/end, and local
  Datastar surface signal;
- the exact POST URL/status and whether its body is form or JSON signals; and
- console errors filtered for Datastar, Promise, fetch, morph, and uncaught
  exceptions.

Screenshots prove layout and visible errors but never exact action count.

### Server-side feeds

Use a client that can leave a response unread as well as one that incrementally
decodes gzip. For every event record socket/view ID, response headers, SSE
event name, complete bytes/hash, embedded stable DOM markers, arrival time,
and the database value observed from the pod measurement/debug boundary.
Heartbeats are transport evidence and must not be counted as application
renders.

### Database and process

Use `seon.db` queries over one captured database value to prove:

- resident agent ID and namespace ref;
- message IDs and from/to refs;
- run/turn/eval/plan relations and states;
- scenario action IDs and exact submitted values;
- standard error evidence where the selected policy promises it; and
- no duplicate identity or unexpected transaction.

Capture `seon.web.datastar/performance-snapshot` before/after each fanout phase.
The meaningful counters are `view-count`, `subscription-count`,
`render-requested`, `render-started`, `render-completed`, `render-superseded`,
`render-duration-ms`, `serialized-event-bytes`, `fanout`,
`write-backpressured`, `pending-replacement`, `drain-duration-ms`, affected
subscriptions, and skipped subscriptions.

## Quantitative admission thresholds

Correctness is exact; latency thresholds are regression detectors, not excuses
to weaken behavior:

- every state-changing browser POST resolves or returns a truthful error—no
  hanging request;
- relevant ordinary work begins after the 16 ms settle window and continuous
  structural work cannot defer beyond the source-owned 500 ms maximum;
- 10 equivalent sockets perform exactly one render/serialization per admitted
  database value;
- the slow socket retains at most one newest pending application event;
- the fast socket receives the latest state without waiting for the slow
  socket to drain;
- closing the last view eventually yields exactly zero feed owners and removes
  the one database interest; and
- no serialized event contains `Promise`, no browser console has an uncaught
  error, and no action is inferred solely from a log line.

The later performance gate owns 1/10/50/100-feed latency percentiles and memory
budgets. This graduation matrix proves the architecture and collects the raw
measurements; it does not invent an unmeasured pass number.

## Graduation decision

Section 6 closes only when B1–B20 pass against one exact source revision and
the retained evidence includes browser, decoded gzip, database, measurement,
and teardown records. Focused CLJS tests are prerequisite evidence, not a
substitute.

The shortest current path is to first decide and close the deterministic
namespace-targeted browser input and visible validation/error facts, then run
the matrix without adding any route, renderer, feed, cache, or client state
mechanism. Those are the earliest source gaps; fanout, reconnect, and
backpressure already have the right single-owner seams for live graduation.

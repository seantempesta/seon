---
type: research
status: completed
tags: [research, web, agent, database, flow]
---

# Root workspace and browser-session source audit — 2026-07-14

## Verdict

The architecture's root workspace and browser-tab session contract is not a
partially wired feature. The active source implements neither persistent web
sessions nor message/turn session provenance. The current browser authority is
split between an ephemeral feed `view-id`, Datastar signals, and the local URL:
none survives reload as database truth and none identifies the tab that sent a
message. Root therefore cannot target one originating tab without guessing.

Root also has no dedicated system layout. `/` writes the ordinary agent shim
for the literal id `"root"`; the shim opens `/agent/root/feed`; that feed runs
the ordinary `seon.ui.agent-view`; and the fleet dashboard is root's pinned
canvas inside it. Live read-only HTTP proof on 2026-07-14 returned the title
`seon · agent root`, the composer `message agent root`, and the
`/agent/root/feed` opener. `/agent/root` correctly canonicalized with `302 /`.

This PRD should consume the general observed render-unit transition settled by
`reactive-render-units`, then deliver two coherent mechanisms:

- one dedicated root layout over the shared route, unit, render, and gzip-feed
  machinery; and
- one `seon.web.session` entity per browser tab whose sole durable state is a
  normalized same-origin location.

Do not equate the feed `view-id` with a browser session, store a selected-agent
ref, create a root-only socket, or let the browser upsert an absent identity.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Source read | Constraint for this unit |
|---|---|---|---|
| Datastar browser runtime | Shipped RC.7-line bundle; reference checkout `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` (`v1.0.0-RC.7-8`) | `reference-code/datastar/library/src/plugins/attributes/{init,effect}.ts`, `plugins/actions/fetch.ts`, `plugins/watchers/patchElements.ts`, `engine/signals.ts` | `data-init` runs once when its element loads; `data-effect` tracks signals; a new fetch cancels through its action lifecycle; outer patches target stable element ids and execute newly inserted scripts. Signals are transient UI state, not durable tab identity. |
| Datastar Clojure SDK examples | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2`, tag `v1.0.0-RC7` | `src/dev/examples/tiny_gzip.clj`, `src/dev/examples/redirect.clj`, `libraries/sdk/.../api.clj:489-500` | A redirect is an ordinary auto-removing script patch (`execute-script!`), not a new event family. Seon can emit the equivalent patch on the already-open feed. |
| Reitit | `0.10.1`; exact reference commit `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | `modules/reitit-core/src/reitit/core.cljc:42-78,331-380`, `reitit/impl.cljc:121-131`, `modules/reitit-ring/src/reitit/ring.cljc:360-402` | `match-by-name` plus `match->path` is the reverse-routing authority. Nested route data meta-merges. A socket-owning handler must return Seon's truthy hijack sentinel because sync `ring-handler` otherwise invokes the default handler. |
| Datahike | Maintained fork `6f90b339768b1a02066dce3b6fcc93a200758fcc` in both writer and CLJS overrides | `db/transaction.cljc:569-687,917-935,1190-1240`, `db/utils.cljc:112-129`, `db.cljc`, `versioning.cljc` | `:db.unique/identity` gives lookup-ref/upsert semantics; generated ids must still use Seon's serialized writer allocation. Plain refs do not cascade. `retractEntity` removes inbound refs as well as the entity. History/transaction metadata supplies recency; do not copy `updated-at` or presence. |
| Seon id allocation | `seon.db.id/allocate!` plus typed writer protocol | `src/seon/db/id.cljc`, writer allocation tests | Session creation must allocate the compact identity and transact id, human ref, and initial location atomically. A browser-supplied missing id must not become an upsert path. |
| Seon routing | `seon.route` facts projected by `seon.web.router` | `src/seon/route.cljs`, `src/seon/web/router.cljs`, route/router tests | Location normalization and agent targeting derive through the database-derived router. Do not duplicate route name, agent id, or selected surface as session attrs. |
| Shared reactive UI | `seon.web.datastar`, `seon.web.view-unit`, `seon.render.surface` | current source plus [[root-reactive-system-view-audit-2026-07-14]] and [[reactive-render-source-audit-2026-07-14]] | Root must wait for and consume the one runtime-observed unit transition. A distinct layout does not imply a distinct feed engine. |

The exact selected sources are present for all four load-bearing external
domains. No API behavior in this plan is inferred from a package name alone.

## Current owner and transition map

| Transition | Current owner and facts | Gap |
|---|---|---|
| `GET /` | `seon.route/root` resolves to `seon.web.datastar/serve-root!`; it calls `write-agent-page! "root"` (`datastar.cljs:1300-1311`) | Root inherits the ordinary agent title, body classes, composer, focus signals, primary panel, and context rail. |
| Root feed open | The static shim's hidden `data-init` opens `/agent/root/feed`; `open-agent-feed!` keys the subscription as `[:seon.web.feed/agent "root"]` and invokes `agent-view` (`datastar.cljs:938-952,1313-1367`) | No root page plan or session id reaches the feed. |
| Feed/socket identity | `requested-view-id` or a random UUID indexes process-local `!feeds`; reconnect may replace that socket (`datastar.cljs:954-1206`) | It is an ephemeral render activation/socket coordinate. It has no human ref, database attachment, location, or post-restart meaning. |
| Agent focus | `seon.ui.agent-view` initializes `$selected`, `$seenrevision`, and `$pinnedselection`; rail clicks and pin buttons mutate signals (`agent_view.cljs:114-170,240-297`) | Explicit pin is not encoded in the URL or database. Reload loses it; root cannot inspect it; two tabs are independent only accidentally while both remain open. |
| Browser navigation | Ordinary links set `window.location`; the route derives the viewed agent from the URL | The server never reconciles an observed normalized location to a tab entity. |
| Human message | Static form POSTs only `text` to `/chat?agent=<id>` (`datastar.cljs:905-936`); `handle-chat!` calls `agent/message!` with from, to, content (`serve.cljs:530-585`) | No web-session id is posted, injected, validated, or persisted. |
| Stored message | `seon.agent.message` registers id/from/to/content/at/hops/origin and `message!` allocates the id (`message.cljs`) | `:seon.agent.message/web-session` is absent from schema, request, entity shape, pull patterns, and tests. |
| Turn assignment | `seon.agent.turn/with-turn!` stores run, prompt projection, timestamps, and results (`turn.cljs:250-303`) | `:seon.agent.turn/cause-message` is absent. The run opener is not sufficient because one run can absorb later human messages. |
| Root navigation command | No `seon.web.session` namespace or `select-agent!` exists | Root has no protected function that can reverse-route a target and update only the originating session. |
| Root dashboard | `seon.render.system/system-view` includes root in `all-agent-ids`, renders a special root card, and returns the whole dashboard as root's canvas | Architecture requires a dedicated root page, no recursive root card, and the same fleet projections split into shared render units. |

## Two-tab and lifecycle gaps

### First load and reload

`sessionStorage` does not occur anywhere in active source. A first load opens a
feed immediately. There is no attachment tuple, no validation against
`{database-id, branch}`, no lookup-ref check for the current human, and no
writer allocation. Reload therefore creates or accepts only an ephemeral view
id and resets the page's manual pin.

The target bootstrap order must be strict:

1. Read the tab-local tuple from `sessionStorage`.
2. Compare its database id and branch with the live attachment.
3. Resolve the session lookup ref and verify its human ref.
4. Reuse it only when all checks pass; otherwise ask the writer to allocate a
   replacement entity with the current normalized location.
5. Store the returned tuple and then open the feed keyed by that session.

This ordering prevents a ghost feed from observing or redirecting before the
tab has durable identity.

### Concurrent tabs

Today two tabs can hold different Datastar signal values, but the distinction
is neither durable nor addressable. A root message has no way to name which tab
sent it. Implementing only a global agent selection would make tabs fight.

The durable fact should remain exactly:

```clojure
{:seon.web.session/id       <writer-allocated compact id>
 :seon.web.session/user     [:seon.user/id "user"]
 :seon.web.session/location "/agent/example?surface=plan"}
```

Route, agent target, and optional explicit surface pin derive from that one
normalized string. Scroll, open disclosures, unpinned preview selection, form
signals, and socket/view ids remain transient.

### Reset, deletion, and reconnect

Datahike lookup refs make a missing session explicit. `retractEntity` removes
inbound refs, so deleting a session also removes a message's plain session ref;
history retains the prior assertion when history is enabled, but the current
message no longer carries a live ref. Product code must decide this deliberately
rather than assume a dangling ref survives.

The architecture's current reset behavior is sound: when the session disappears
beneath an open feed, send one auto-removing control script that clears only
this tab's tuple and reloads its current same-origin location. Bootstrap then
allocates a replacement. Do not client-upsert the missing id or redirect every
equivalent agent feed.

### Message and turn provenance

The session ref is domain linkage on the inbound human message, not transaction
provenance. The exact turn-to-message assignment is also a domain fact because
it cannot be reconstructed safely from the run opener. The required chain is:

`turn -> cause-message -> web-session -> location`.

The eval boundary injects the reached session id as context-only data; model
input cannot override it. A scheduled or agent-originated turn has no human
session and `select-agent!` must return an explicit error envelope rather than
guessing the latest tab.

## Root-layout gaps

The root page violates the target in four concrete ways:

- `serve-root!` emits the ordinary agent shell and title.
- `agent-view` materializes root's fleet canvas as both an expanded primary
  surface and a compact rail surface, retaining irrelevant pin/selection chrome.
- `system-view` includes a root self-card even though root is the page owner.
- the whole fleet/activity/recovery dashboard is one root canvas render instead
  of independently observed agent-card and aggregate units.

The desired boundary is not a `root-v2` renderer. Add one page-layout owner
such as `seon.ui.root-view`; reuse the shared system header, fleet/focus/plan
projections, route resolution, render-unit descriptors, surface materializer,
feed registry, gzip stream, and patch framing. Keep the bounded AI twin in
`seon.render.system`, derived from the same projections, because root still
needs fleet context even after the human page stops using the dashboard as its
canvas.

## Deletion and migration map

| Current path | Action after replacement is proven |
|---|---|
| `serve-root! -> write-agent-page! "root"` | Replace with the dedicated root shim/page plan. Keep `/agent/root -> /`. |
| Root use of `/agent/root/feed` through `open-agent-feed!` | Route `/` to a root feed descriptor over the shared feed engine; do not add a second registry/listener. |
| Root ordinary `agent-view` primary/rail/pin layout | Remove root from this caller; keep it only for ordinary agents. |
| Root recursive card in `seon.render.system` | Delete from the human grid. Retain root status as a small nonrecursive system region and in the AI projection. |
| Full dashboard as root canvas HTML | Split human layout into root units; retain the root AI context projection from the same data. |
| `$pinnedselection` as the explicit durable pin | Encode only an explicit pin in normalized location; keep unpinned `$selected` transient. Delete signal-only persistence assumptions. |
| Random feed `view-id` used as reconnection identity | Keep as ephemeral socket/activation identity only; never migrate it into session schema. |
| `/chat` request without session context | Add protected session injection and message linkage; reject a supplied session that does not resolve to this attachment/human. |
| Any proposed selected-agent, route-name, updated-at, active, or ack attr | Do not create it. Derive from location and transaction metadata. |

## Ordered implementation slices

### Slice 0 — consume reactive-render-unit graduation

Land only after the general observed unit transition owns activation,
dependencies, and stable target updates. Preserve the existing route and
transport while this prerequisite is incomplete.

### Slice 1 — pure location and reverse-routing contract

Create `seon.web.session` with schemas and pure functions for same-origin
normalization, optional surface query encoding, route matching, and target
reverse routing. Reject absolute/cross-origin URLs, fragments, unknown agents,
and invalid surface selectors as error values. Unit-test idempotence and
canonical query ordering before any database writes.

### Slice 2 — writer-allocated bootstrap and reconciliation

Register only id, user ref, and location plus one entity shape. Add one typed
HTTP/database boundary that validates a stored attachment tuple or atomically
allocates a replacement. Reconcile observed location only when it differs.
Use the existing id allocator and database protocol; do not introduce a browser
identity generator or session registry atom.

### Slice 3 — session-keyed shared feed lifecycle

Have the page bootstrap session identity before opening the existing feed. Key
tab-specific page focus and redirect decisions by the validated session while
continuing to share normalized data/render units where inputs are equivalent.
Implement stale-session control patch/reload through normal Datastar patch
framing.

### Slice 4 — message and turn cause chain

Extend the one message model with an optional web-session ref for human input.
Extend the turn model and assignment transition with optional cause-message.
Thread the exact queued message chosen for a turn rather than copying the run's
opening cause. Preserve absence for scheduled/internal turns.

### Slice 5 — protected root-directed navigation

Implement one fully specified `seon.web.session/select-agent!`. Inject the
current turn's originating session at the eval boundary, prohibit caller
override, reverse-route the target, compare locations, and transact only a real
change. The session's already-open feed emits the official auto-removing script
redirect only when its stored location differs from its local route.

### Slice 6 — dedicated root workspace

Build the root page plan on the shared unit engine. Show non-root agent work
cards, plan-position/purpose/conversation fallback, recovery and activity as
separate bounded units, and a small root status region. Delete the ordinary
agent wrapper and recursive card from `/` after the dedicated layout passes.

### Slice 7 — deletion, restart, and full proof

Delete superseded root/session paths, cold-reset and rebuild, then run the
focused suites, operator gate, server-side gzip feed probes, and real two-tab
browser matrix below.

## Falsifiable test matrix

| Layer | Test | Falsifiable evidence |
|---|---|---|
| Location pure functions | Normalize path/query; encode/retract one surface pin; reject external URL and unknown target | Equal semantic locations compare equal; invalid input returns a typed error value. |
| Schema bridge | Session id identity, user plain ref, location string; message and turn plain refs | Derived Datahike facets match; no extra session attrs are installed. |
| Bootstrap | Empty storage, valid tuple, wrong database/branch, missing lookup ref, wrong human, concurrent allocation | Exactly one valid session is returned; stale tuples are replaced; a browser value never upserts a missing identity. |
| Reconcile | Reobserve same URL, navigate agent, pin/unpin, reload | Equal location writes zero datoms; changed location writes one cardinality-one replacement; reload restores only explicit pin. |
| Deletion | Retract a session with linked message and an open feed | Current session and inbound plain ref are gone per Datahike semantics; that tab clears/rebootstraps; another tab does nothing. |
| Message/turn | Two human messages enter one run from different tabs | Each assigned turn points to its exact cause message and reached session; the run opener is not reused incorrectly. |
| Protected navigation | Root calls select from human turn, scheduled turn, deleted session, unknown agent, and same target | Only the originating valid session changes; absence/errors return envelopes; same location is a no-op. |
| Root layout | Render root with root plus several ordinary agents | No ordinary agent heading/rail/pin, no recursive root card, exactly one card per ordinary agent, same shared header/unit owners. |
| Feed | Relevant session location change and unrelated database transaction | One redirect script patch targets the matching session; unrelated tab/feed receives no redirect and unchanged units do not render. |

## Live two-tab browser matrix

Use two agent-owned browser tabs plus a server-side gunzip client for each
long-lived feed; the browser bridge alone cannot prove SSE liveness.

1. Open `/` in tabs A and B with empty storage. Prove distinct session ids and
   correct `{database-id, branch}` tuples; the database has two session rows.
2. Navigate A to one ordinary agent and B to another. Query both session rows;
   each location matches its own URL and neither transaction changes the other.
3. Pin a surface in A, reload A, and prove the pin returns. Make an unpinned
   rail selection in B, reload B, and prove it does not persist.
4. Send a root message from A. Query message -> session and the answering turn
   -> cause-message. Ask root to select an ordinary agent; only A navigates.
5. Send a root message from B while A remains open. Root selection from that
   turn moves only B.
6. Delete A's session through a controlled test boundary. Prove A clears only
   its tuple and allocates a replacement; B's tuple, URL, and feed are stable.
7. Restart the pod and then the complete cluster without resetting the database.
   Both tabs reconnect to valid locations. After a destructive cluster reset,
   both detect missing sessions and allocate replacements without ghost ids.
8. Throughout, record console errors, redirect/unit target ids, feed open/close,
   database writes, render counts, patch token estimates, and root layout
   screenshots. Any cross-tab movement, equal-location write, ordinary root
   rail, recursive root card, or second feed registry fails graduation.

## Risks and decisions preserved

- Database-backed location is desired UI state and provenance, not
  authentication or presence.
- Session id is scoped by its database attachment; database id and branch stay
  in browser reconnect data, not copied onto the session entity.
- Sharing subscriptions remains valid only when every rendering input is equal.
  A redirect decision is session-specific even when two tabs view one agent.
- The root layout may follow session plumbing in implementation order if that
  lowers churn, but both depend on the general render-unit engine and neither
  should recreate it locally.
- The existing reactive root audit's stale-invalidation blocker remains owned
  by `reactive-render-units`; this PRD should not patch around it.

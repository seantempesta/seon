---
type: research
status: active
tags: [research, agent, web, database]
---

# Root view, browser session, crash, and batch audit (2026-07-13)

## TL;DR

The required mechanisms mostly exist, but their ownership has drifted:

- The root fleet dashboard exists, yet a fresh `config/system.edn` no longer
  pins it to root's canvas. `config/legacy.edn` still does, so an old database
  can mask the fresh-boot failure.
- An agent page already computes the shared agent-derived focus from the most
  recent deliberate update and can materialize one compact face. The root dashboard ignores
  that authority and always renders the agent's canvas. Move the existing
  catalog/focus/materializer into one lower `seon.render.surface` owner and use
  it from both views; do not add another preview rule.
- The root home require override correctly replaces the base list as one scalar
  value, but the code fallback still grants `seon.agent` to every agent. The
  fallback contradicts the manifest and can make root-only orchestration look
  universal.
- The live Datastar feed already has the right ephemeral socket registry,
  render grouping, latest-wins backpressure, and render timing. It does not have
  a durable per-tab session identity or a typed host CPU/RSS projection.
- Crash recovery closes every open run sequentially and sends one plain message
  per run. It does not atomically record one recovery fact or terminalize a
  running turn. The target is one CAS-fenced reconciliation transaction and one
  recovery anchor in that transaction. Root derives the structured notice from
  the transaction's changed datoms, with no replay or automatic wake.
- Batch evaluation already attempts ordinary failed forms and continues. Its
  remaining hole is an unexpected exception escaping the per-entry boundary,
  which aborts later entries. Catch only at that boundary; continue when the
  failure can still be recorded, and fail loudly if persistence itself failed.
- There is no reusable live process telemetry path today. `bin/seon status`
  owns process identity and liveness but prints only PID/start time, while the
  pod owns feed pressure and render timing. One on-demand host snapshot can
  compose those existing facts into an optional system-status surface without
  database projections or a second metrics stream.

## One mechanism after the refactor

The root view should remain root's ordinary agent page with a database-derived
canvas. Each non-root card is a bounded projection over one frozen database
value:

1. identity, purpose, and derived status;
2. the existing shared agent-derived focus (pin, then agent recency, then welcome);
3. that selected surface's compact HTML face;
4. at most five recent human/agent messages;
5. recent failed eval summaries, not source/result trees.

The page's database-driven pieces update through the existing changed-attribute
Datastar feed. Host telemetry is a separate, optional unit inside the same root
view and same feed. It samples only while that unit is active. It is not stored
as domain state and it is not included in every root prompt.

## Exact current-to-target mapping

| Concern | Current source | Current behavior | Target owner and change |
|---|---|---|---|
| Root canvas pin | `config/system.edn:203-218`, `config/system.edn:269-319` | The base contains a canvas context block, but root's sparse override explicitly omits `:seon.render.canvas/content`; comments still claim it sets the dashboard. | Restore the root canvas pin in the existing root override. No hardcoded root render branch. |
| Masking legacy state | `config/legacy.edn:203-220` | The archived config still pins `seon.render.system/system-view`; databases seeded from it can retain the pin and make current fresh config appear correct. | Destructive fresh-database proof must be authoritative. Remove stale active comments after the cutover; legacy stays historical. |
| Root override merge | `src/seon/config.cljs:1297-1321` | Root scalar keys, including `:seon.eval/home-requires`, replace the base scalar; context blocks upsert by name. This merge shape is correct. | Keep one scalar-list replacement. Do not concatenate base and root requires or create role-specific merge code elsewhere. |
| Home-require drift | `config/system.edn:219-233`, `config/system.edn:281-319`; `src/seon/agent/home.cljs:35-52`, `src/seon/agent/home.cljs:54-92` | The manifest gives `seon.agent` only to root, but the no-config fallback gives it to every agent. | Make the fallback agree with the ordinary base manifest; root's complete scalar override remains the sole elevation. |
| Root system projection | `src/seon/render/system.cljs:70-143`, `src/seon/render/system.cljs:430-457` | Fleet identity/status/turn/eval/activity facts are derived from the database and returned as HTML plus AI render. | Keep the pure database projection. Add focused-surface labels and bounded detail from the shared surface/message/error projection. |
| Wrong card preview authority | `src/seon/render/system.cljs:297-331` | Every non-root card invokes `render/render-agent-canvas`; it cannot show transcript, plan, or another currently focused surface. | Consume the same catalog/agent-derived-focus/materializer used by an agent page without a session override and render only that compact face. Root's own card remains nonrecursive. |
| Existing focus authority | `src/seon/ui/agent_view.cljs:164-217`, `src/seon/ui/agent_view.cljs:557-630` | Deliberate recency is derived from attributed REPL transactions and human-facing replies. Public `surface-catalog`, `latest-focus-selection`, and `materialize-surface` already express the needed behavior. | Move these facts/functions into one lower `seon.render.surface` namespace with colocated schemas. Both agent and root views call it. Preserve behavior; do not duplicate the query. |
| Require cycle blocking reuse | `src/seon/ui/agent_view.cljs:10-22`; `src/seon/ui/header.cljs:1-38` | `agent-view` requires `header`, and `header` requires `render.system`; directly requiring `agent-view` from `render.system` would cycle. | Extract the pure surface projection below both UI/render namespaces. Do not work around the cycle with a copied focus algorithm. |
| Global activity vs card detail | `src/seon/render/system.cljs:171-242`, `src/seon/render/system.cljs:355-382` | One unfiltered global stream shows messages and eval source snippets. It does not show each agent's last five messages or a bounded recent-error summary. | Add bounded per-agent queries to the root-card data projection. Keep full source/result/error trees behind lazy expanded units. |
| Obsolete database inventory panel | `src/seon/render/system.cljs:145-157`, `src/seon/render/system.cljs:333-353`, `src/seon/render/system.cljs:407-416` | The root view performs and renders a complete database inventory using the retired “store” vocabulary. | Remove the panel and its AI prose. Keep a lightweight `/data` link; the database browser owns inspection. |
| Fresh boot | `src/seon/client.cljs:2356-2471` | Cold boot creates/completes only root. It then returns the first sorted resumable agent, which can be root and is not an explicit first ordinary agent. | After root birth, derive the durable absence of any non-root agent and create exactly one ordinary word-id agent, parented by root, through the shared private atomic birth compiler that also backs public `start!`. Return that ID and URL explicitly. |
| Operator open path | `bin/seon:314-331`, `bin/seon:1418-1446` | The active stack is watcher → writer → pod; status reports a URL, but there is no single `up --open` path that opens the ordinary first agent. | The rewritten operator owns `up`, readiness, and optional browser open. The pod returns the ordinary ID; the operator opens `/agent/{id}`. `/` remains root mission control. |
| Browser view identity | `src/seon/web/datastar.cljs:105-131`, `src/seon/web/datastar.cljs:683-771`, `src/seon/web/datastar.cljs:907-920` | Open feeds live in one appropriate process-local atom, but absent IDs fall back to random UUIDs and normal agent feeds do not have a durable tab identity. | Keep `{database-id, branch, session-id}` in `sessionStorage`. Reuse only when the attachment matches and the lookup ref exists for the current human; otherwise writer-allocate a replacement. If restore removes an open feed's session, clear/re-bootstrap rather than client-upsert it. Sockets/queues remain process-local. |
| Human location | `src/seon/agent/message.cljs:51-63`, `src/seon/web/serve.cljs:580-625` | Messages know the shared human user but not the originating browser tab/location, and turns do not record which absorbed run message they are assigned to answer. | Store only session ID, user ref, and normalized local location; add optional message→session and turn→cause-message refs. Route name/agent/recency/presence remain derived. |
| Root-directed navigation | `src/seon/web/datastar.cljs:221-240`, `src/seon/web/datastar.cljs:376-404` | The existing feed can push normal Datastar events with latest-wins backpressure, but there is no redirect helper or session-targeted root function. | Add protected `seon.web.session/select-agent!` with the context-only session ID reached through current turn → cause-message → web-session. Reject an agent-supplied ID. Reverse-route and compare/transact-if-changed the location; patch only a feed whose normalized current route differs. It is not a separate event family, WebSocket, or event bus. |
| Feed pressure facts | `src/seon/web/datastar.cljs:212-215`, `src/seon/web/datastar.cljs:376-431`, `src/seon/web/datastar.cljs:733-770` | The pod already knows open feed count, grouping, pending/draining state, target count, and render time. | Expose one read-only typed snapshot over this existing registry; do not persist or mirror it. |
| Process facts | `bin/seon:828-835`, `bin/seon:965-1065`, `bin/seon:1418-1446` | The supervisor owns process names, PID/start-stamp identity, ownership, commands, liveness, start time, and port. It does not sample CPU or RSS. | The rewritten Babashka/Clojure operator exposes one typed, read-only process-status snapshot including bounded CPU delta, RSS, uptime, and readiness. |
| Telemetry composition | `src/seon/render/system.cljs:430-457` | `system-view` accepts only database input and has no live runtime telemetry. | Keep the database renderer pure. An optional system-status unit receives an explicit host snapshot and existing feed snapshot; it refreshes only while active and adds only anomalies to AI context. |
| Crash recovery | `src/seon/agent/run.cljs:773-800`, `src/seon/client.cljs:2381-2388` | Boot scans all open nonterminated runs and closes them one at a time before listeners start. | Build one deterministic recovery transaction with CAS fences, close every interrupted run, mark its running turn `:interrupted`, clear owned pointers, and write one idempotent recovery anchor. Do not fabricate an eval row. |
| Crash notifications | `src/seon/agent/run.cljs:405-470`, `src/seon/agent/run.cljs:472-515` | Each run close sends plain parent/root messages after the close transaction. A crash between close and message can lose the notice; many runs create many notices. | Root derives one notice by joining the anchor's transaction to its run/turn/pointer datoms and commit parent. No copied affected refs, acknowledgement field, or automatic wake/replay. |
| Batch continuation | `src/seon/eval.cljs:4697-4704`, `src/seon/eval.cljs:4710-4829`; `src/seon/agent/turn.cljs:392-426` | Expected read/compile/runtime failures are recorded and later forms continue. An unexpected exception escaping `run-entry!` aborts the `doseq`. Batch counts attempted success plus failure; stream intentionally stops at one complete form. | Add one catastrophic per-entry catch around the existing dispatch/record boundary. Record a core-fault eval and continue when durable recording succeeds; fail loudly if the recorder/database is broken. Do not change batch wording or add response-text tests. |

## Telemetry: what exists and what does not

There is no active `process.memoryUsage`, `process.cpuUsage`, event-loop monitor,
or shared CPU/RSS sampler in `src/seon/**/*.cljs`. Prior profiling installed
temporary runtime probes and then removed them; those experiments are evidence,
not a production monitor.

The useful facts are already split at their natural authority boundary:

- the operator owns the process graph, stable `(PID, start-stamp)` identity,
  command, ownership, liveness, readiness, start time, and port;
- the Node web process owns its own memory/CPU primitives and the live feed
  registry, pending/backpressure state, render count, target count, and render
  duration;
- the database owns durable agent/run/turn/eval/message facts.

One live system-status surface can compose these without storing projections.
The clean shape is an operator endpoint or local socket returning a fully
specified snapshot plus a pod-local feed snapshot. CPU percentage necessarily
uses two samples; the previous sample is legitimate bounded sampler state, not
database authority. Start one shared modest-cadence sampler only while at least
one root system-status unit is active, fan it out through the existing feed, and
stop it when none are active. This is another stimulus for the existing live
channel, not another metrics channel or monitor registry.

Do not smuggle runtime atoms into the pure `system-view` function. Give the
optional unit an explicit namespaced telemetry input. Database-only and AI
renders can omit it; only an anomaly summary should enter root's context.

## Settled choices from owner constraints

- A crash-recovery notice derived from the recovery transaction remains
  prominent while an affected agent has no later run; later work makes it
  ordinary history. There is no copied notice entity or stored acknowledgement
  flag.
- Every root card shell and focus label is cheap; only visible non-root cards
  activate a compact focused-surface renderer through the existing view-unit
  mechanism. Root's self-card remains summary-only. Large fleets do not eagerly
  materialize every surface.
- The human system-status surface may show live telemetry, while root's AI
  context receives only anomalies: dead/unready processes, sustained CPU/RSS,
  feed backpressure, or render-budget violations.

These choices follow the owner's minimal-context, derive-don't-store, and
pay-for-open-work constraints; no further owner answer is required.

Everything else in this slice is already sufficiently settled by the current
architecture: fresh database means root plus one ordinary agent; `up --open`
opens the ordinary agent; root navigation targets only the originating tab;
crash recovery idles agents without replay or automatic wake; batch attempts
every parsed form; missing blocks and unavailable previews are omitted.

## Minimal first implementation slice

1. Restore the root system-view pin in `config/system.edn`; align the ordinary
   fallback require list with the base manifest; cold-reset and prove `/` is the
   dashboard while the returned/opened agent is non-root.
2. Extract the existing catalog/focus/materializer unchanged into
   `seon.render.surface`. Convert `agent-view` and `render.system` together, then
   delete the old definitions. Add bounded per-agent message/failure projections
   and remove the database inventory panel.
3. Add session schema/facts, message→session linkage, and the exact
   turn→cause-message assignment. Bootstrap validates the
   stored attachment/session tuple and uses the writer for first or replacement
   allocation; a feed whose session disappears clears/reboots that same path.
   `select-agent!` receives only the context-only ID reached from the current
   turn's cause message. Reuse the existing keyed feed and compare/transact/redirect
   only on real location differences.
4. Replace sequential crash closes with one fenced recovery builder/transaction
   plus one recovery anchor; terminalize running turns without fabricating evals;
   add the per-entry batch catastrophic catch.
5. After the operator rewrite exposes a typed process snapshot, add the optional
   active-only system-status unit. Do not block the database-derived root view on
   telemetry.

## Mechanical proof

- Fresh database: exactly root plus one ordinary agent; `/` renders root's fleet
  canvas; operator output/open URL names the ordinary agent.
- Root card parity: for a canvas write, agent reply, and plan change, the root
  card label/compact preview matches an agent page with no session override;
  the focused preview is not duplicated inside a card. A manual selector changes
  only its own tab and leaves the shared root-card focus unchanged.
- Large fleet: only visible/active preview and expanded-detail units materialize;
  one agent transaction does not render unrelated cards.
- Two tabs: messages link to different session refs; root navigation changes and
  redirects only the originating tab; reconnect retains the same writer-issued
  identity; an equal route observation writes and redirects nothing.
- Crash injection: all open runs close once, all running turns become terminal,
  agents derive idle, exactly one recovery anchor exists, its transaction join
  returns the affected refs, and no work replays or auto-wakes.
- Batch: read, compile, runtime, and unexpected-but-recordable failures do not
  prevent later forms; a persistence failure stops loudly rather than claiming
  complete results.
- Telemetry: no root status unit means no sampler; one or many equivalent open
  units share one sample; closing the last stops it; no CPU/RSS/history datoms
  appear in the database.

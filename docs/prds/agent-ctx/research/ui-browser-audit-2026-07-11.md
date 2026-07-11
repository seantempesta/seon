---
type: research
status: active
tags: [research, web, agent]
---

# UI browser audit — default pod, post context-rebuild cutover (2026-07-11)

Live default pod `http://127.0.0.1:7890`, fresh store (core seed only, reset ~1h
before the audit). Real Chrome (headless, playwright-core, own profile — no shared
tabs touched). Read/observe only; no src edits, no pod restarts. Screenshots live in
the session scratchpad
`/private/tmp/claude-501/-Users-sean-src-seon/ffd51cfc-bd3e-445f-93dc-4106bd33c0ab/scratchpad/`
and are referenced by filename below.

**TL;DR** — the cutover UI is healthy: root's world is orienting on a fresh store,
the interactive plan tile works and all four signals survive a live morph, SSE is
verified server-side, the debug page IS the visual proof of the minimal tree, and
zero console errors appeared on any screen. The real gaps: the debug page's rendered
pane dumps raw EDN for ai-only blocks, there is no agent roster surface left (the
"all agents" links are a circular 302), and `plan!` silently drops
`:my.plan/description` on children (found live, root-caused in source).

## Root's view — the owner's question first

**`/` (= root's world) on a fresh store is orienting, not blank**
(`root-world.png`). Post-cutover, with the old dashboard block retired, a fresh
visitor sees:

- A greeting canvas placeholder — "Good afternoon. … I'm still finding my purpose —
  tell me what you need. I'll update this tile as I work" — with a live date/time
  stamp. This is the live-tile slot speaking for an agent that hasn't rendered one
  yet; it tells the visitor what the slot IS and invites input. Good default.
- The transcript tile (collapsed-empty with teaching text: "no events yet — every
  message and eval this agent makes appears here live").
- The plan tile ("no plan yet" quiet one-liner when empty; the full interactive tree
  once a plan exists).
- A masthead status strip (agents/idle/running, tok/s, datom count, embed state), a
  time-travel scrubber pinned bottom, and the message-agent input bar.

`/agent/root` renders the identical page (`agent-root.png`) — `/` and `/agent/root`
are the same world, as designed. Root's canvas is NOT degraded by the dashboard
retirement; the placeholder + transcript + plan stack reads as a coherent
"agent home".

**What IS degraded: the roster.** `/world` + `/world/feed` are retired by design
(`seon.route` seed comment: "/ IS the dashboard"), and the no-match handler 302s
everything home. But the UI still carries "← all agents" (agent page breadcrumb),
"agents" + "home" (masthead), and three `/world` links in `seon.web.debug` — all of
which now 302 straight back to `/`, i.e. a circular link. `seon.web.datastar/world-view`
(the roster) is now unreachable dead code (only referenced from the legacy `handle!`
dispatch at `datastar.cljs:594`, which sits behind the 302). With one agent this is
cosmetic; with N agents there is NO screen that enumerates agents — the masthead
count ("1 agent · 1 idle") is the only roster signal, and it isn't a link to
anything useful.

## Route surface (enumerated from the live db + `router.cljs`, not memory)

Seeded `:seon.route/*` datoms (4): `/` GET `serve-root!`, `/agent/{id}` GET,
`/agent/{id}/feed` GET, `/agent/{id}/call` POST. Static supplement
(`router.cljs static-supplement`): `/css/*` `/js/*` `/sse`, `/data` `/data/sse`,
`/agent/{id}/debug` `/agent/{id}/debug/sse`, and the POST doors `/chat /stop /resume
/clear /log /agents/new /agents/run /call /agent/{id}/complete`. No-match → 302 `/`.

## Screen-by-screen

| Route | Rating | Evidence / observations |
|---|---|---|
| `/` (root's world) | USEFUL | `root-world.png` — orienting on a fresh store (greeting canvas, transcript, plan, scrubber, input). Zero console errors. Theme: warm blacks/cream/amber, mono, dot+text ✓ — EXCEPT the native bright-blue scrubber slider + blue focus ring on the input (unstyled native controls, loudest elements on the page). |
| `/agent/root` | USEFUL | `agent-root.png` — identical to `/` (by design). "← all agents" breadcrumb is a circular 302. |
| `/agent/{unknown}` | PARTIAL (by design) | `agent-bogus.png` — well-formed unknown id 302s home (#28 graceful redirect, read in source, not a fallback bug). No notice that the agent didn't exist — a stale bookmark silently lands on root's world looking like a real page. |
| `/agent/root/debug` | USEFUL (1 defect) | `debug-root.png`, `debug-system-open.png` — two-pane inspector + token bar. **This is the cutover's visual proof** (see below). Defect: the rendered-view pane prints raw EDN `{:db/id 2675, :seon.agent.ctx/name :skill/repl}` for every ai-only block (skill/repl, namespaces, core-faults, instrumentation-gaps, orphaned-agents) — 5 raw-EDN boxes on the flagship inspector. Minor: bottom ctx-bar right edge, "stable prefix amber · vola…" clipped under the "cache-line 13222" badge. |
| `/data` | USEFUL | `data.png`, `data-expanded.png` — attr-namespace list with row counts → clean paginated EAV table on click; "show system data" toggle; the `:seon.agent.ctx` group doubles as a live view of the context tree. Wide EDN cells (transcript ref vector) truncate with `…` rather than dumping — good. |
| `/agent/root/feed` (SSE) | USEFUL | Server-side gunzip client (`feed-check.js`): 200, `content-encoding: gzip`, `datastar-patch-elements` whole-`#world` morph frame on open (~12.7k), and a new frame pushed on a live tx (observed at 18:52:09 after a `db/transact!`). Shape intact. |
| `/agent/root/debug/sse` | USEFUL | Non-gzip event-stream; on open sends `: connected` + 4 per-pane patches (`inspect-header-root`, `inspect-ai-root` ~68k, `inspect-html-root`, `inspect-ctxbar-root`). |
| `/world`, `/world/feed` | RETIRED | 302 → `/` (deliberate; seed comment in `seon.route`). Stale in-UI links remain (header, breadcrumb, `seon.web.debug` ×3); `world-view` roster is dead code. |

Browser console: **zero errors/warnings on every page loaded** (`/`, `/agent/root`,
`/agent/root/debug`, `/data`, `/agent/bogus-agent`).

## Interactive plan tile — click-through (all 4 signals, morph-survival proven)

Seeded live as root via the pod REPL: `my.plan/plan!` "UI audit demo plan" with 4
children + one nested child under "click all 4 tile signals" (the `$planclosed`
chevron only renders on non-leaf rows, so one depth-2 node was required — see the
rollup note). `done!` on "screenshot every route", `active!` on "click all 4 tile
signals". Driven headless-Chrome with a mid-run pause; the morph was triggered by a
REAL tx (`my.plan/done!` on the SSE step) while the page sat with all signals set.

| Signal | Action | Result | Screenshot |
|---|---|---|---|
| `$planstep` | click step title | detail panel opens in place: id, created stamp, "waits on ✓ screenshot every route", "blocks write the audit doc" | `tile-1-planstep.png` |
| `$planclosed` | click chevron on the non-leaf row | subtree collapses (nested child hidden), chevron flips ▾→▸; re-click restores | `tile-2-planclosed.png` |
| `$planfull` | click "show all 1 completed steps in place" | done rows appear in tree position, label flips to "hide completed steps" | `tile-3-planfull.png` |
| `$plandone` | click "recently completed (1)" | timestamped ✓ tail expands | `tile-4-plandone.png` |

**Morph survival** (`tile-5-aftermorph.png`, after the live `done!` tx): the
whole-element morph re-rendered the tile (rollup 1/4 → 2/4, progress bar grew,
"recently completed" count (1) → (2), the SSE step flipped to ✓ in place) and **every
signal-backed state survived** — the `$planstep` detail panel still open, `$planfull`
still showing completed rows, the `$plandone` tail still expanded with the NEW entry
in it. The `data-signals__ifmissing` design does exactly what it claims.

Two live findings from the click-through:

- **BUG (new, root-caused): `plan!` silently drops `:my.plan/description` on
  children.** The tile's detail panel renders a `desc` row when present
  (`internal.cljs step-detail-html:749`), but a description authored through
  `plan!`'s `:my.plan/children` never reaches the db — `compile-plan`'s pass-1 walk
  (`my/plan/internal.cljs:~265`) captures title/goal/pace/expect and omits
  description (the `::plan-request` schema ACCEPTS it — it validated fine, then
  vanished). Verified live: `(db/pull … [:my.plan/id "byw-…"])` → no description.
  `step!` persists it correctly.
- **Known depth>1 rollup bug, where it shows:** with the depth-2 tree the root card
  printed "1/4 done" (later "2/4") over a 5-step subtree — the nested grandchild is
  invisible to the roll-up. The child's own "0/1" was correct. Corrupts the header
  count + progress-bar % on any nested plan; attributed to the in-flight datahike
  planner fix, not re-litigated here.

Also noted while seeding: `:my.plan/ref`/`:my.plan/after` require STRING labels;
keywords are rejected by instrumentation with a precise humanized error — the
error envelope experience was excellent.

## Debug page = the cutover's visual proof

`debug-system-open.png`: the `:seon.render/ai` pane shows exactly the minimal tree —
`system (499 tokens)` (expanded: the v3.1 system text — ONE source: live-REPL rules,
`⟹`/`⟸` glyph grammar, persist-across-restart rules, errors-as-data,
`(message/user …)`/`(complete …)`/`(wait)`), `repl (1,271)`, `namespaces (11,452)`,
`plan`, `transcript` — and nothing else. The `/data` `:seon.agent.ctx` table
(`data-expanded.png`) confirms the same 7 ctx rows with priorities: skill/repl 16,
namespaces 20, core-faults 41, instrumentation-gaps 42, orphaned-agents 43, plan 45,
transcript 100. Root's 3 derived fault surfaces are present as ai-only blocks. No
legacy dashboard block anywhere. Cutover visually confirmed.

The context token bar (total / cache-line split, per-section segments) is genuinely
useful and reports in tokens.

## Ranked fix list

1. **Debug rendered pane: raw EDN for ai-only blocks** — `/agent/root/debug`, right
   pane. Problem: 5 of 7 blocks render as `{:db/id …, :seon.agent.ctx/name …}` raw
   maps (blocks with `:seon.render/ai` but no `:seon.render/html` fall through to
   printing the ctx entity). Useful: suppress html-less blocks, or render the block
   name + its ai text (or a dim "ai-only — see left pane") — never the entity map.
   **S**.
2. **No roster surface + circular "all agents" links** — site-wide. Problem: /world
   retired, `world-view` dead code, but "← all agents", masthead "agents", and 3
   `seon.web.debug` links all 302 home; with N agents nothing enumerates them.
   Useful: either a subagents/roster tile on root's world (fits "/ IS the
   dashboard") with the stale links retargeted/removed, or delete the links + dead
   `world-view`. **M**.
3. **`plan!` drops `:my.plan/description` on children** —
   `my/plan/internal.cljs compile-plan`. Problem: schema-accepted data silently not
   persisted; the tile's desc row can never show for plan!-authored steps. Useful:
   carry description through the walk + tx (one keyword in two places), test with a
   pull-back. **S**.
4. **Native-blue controls break the Phosphor theme** — `/` + `/agent/{id}`. Problem:
   the time-scrubber `<input type=range>` renders default bright blue and the
   message input's focus ring is default blue — the two loudest colors on an
   otherwise warm-black/amber page. Useful: `accent-color`/explicit track+thumb
   styling and an amber focus ring. **S**.
5. **Depth>1 rollup counts visibly wrong on the root card** — plan tile header +
   progress bar (KNOWN bug, datahike planner fix in flight). Where it corrupts:
   "N/M done" and the bar % on any nested plan (observed "2/4" over 5 steps). Listed
   for tracking, not action here. **(owned elsewhere)**.
6. **Debug ctx-bar right-edge overlap** — "stable prefix amber · volatile…" legend
   clipped under the cache-line badge at 1440px. Useful: flex-wrap or truncate the
   legend, keep the badge. **S**.
7. **Unknown-agent silent redirect** — `/agent/{unknown}` 302s home with no notice;
   a stale bookmark quietly impersonates a live page. Useful: keep the redirect but
   land with a dismissible one-line notice (derived, not stored). **S**.

## Method notes

- Chrome MCP tools were unavailable in this session; the audit used headless real
  Chrome via `playwright-core` in the scratchpad (own profile/instance — no shared
  tab risk). Scripts: `shot.js` (screenshot + console capture), `tile-clicks.js`
  (signal click-through with a GO-file pause for the mid-run tx), `feed-check.js`
  (server-side gunzip SSE client), `debug-clicks.js`.
- SSE was verified server-side per the browser-automation skill; notably headless
  Chrome DID hold the streams fine (live morphs rendered during the click-through),
  which is what made the morph-survival test possible in-browser.

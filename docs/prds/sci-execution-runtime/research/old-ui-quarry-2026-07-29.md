---
type: research
status: complete
tags:
  - sci-execution-runtime
  - ui
  - datastar
  - quarry
---

# Old UI quarry — 2026-07-29

## Verdict

The old UI contained three things worth recovering first: the human message
bar and its end-to-end POST contract, the ordinary agent page's
transcript-plus-focus layout, and the agent-authored control adapter. The
fresh system already recovered the visual language, the one render router,
generic data/error rendering, per-block Datastar morphs, the root page, the
database drill, streaming, and the agent-family neighbourhood. Rebuilding any
of those would create a parallel system.

Human message entry does **not** exist in fresh `src/`. The fresh web handler
dispatches only `GET /`, `GET /agent/{id}`, `GET /feed/{id}`, `GET /data`, and
static assets (`src/seon/render/web.clj:531-614`). It has no POST branch. The
fresh message family can render a message and `:seon.cluster.message/to` is
already the wake attribute (`src/seon/schema/message.edn:1-44`;
`src/seon/cluster/message.cljc:1-16,296-339`), but no browser boundary creates
the external message fact.

The last working message and agent-creation forms were removed by
`48b89dd7d` ("Cut pod agent action routes from render tier"). This quarry
therefore cites both the surviving `src-old/` files and their immediate
pre-deletion source as `48b89dd7d^:src/...`.

## Scope and dependency ledger

The inventory covers every UI-bearing file returned by
`rg --files src-old | rg -i "web|ui|render|css|canvas|route"`, plus the
first-party CSS, font, JavaScript, fresh render code, route history, and the
relevant dependency sources. The path search also returns
`src-old/seon/agent/web.cljc`, `agent/web/core.cljc`, and `agent/web/host.clj`;
those are the browserless web-fetch capability, not web UI
(`src-old/seon/agent/web.cljc:1-7`), so they have no page/component entry.

| Dependency or mechanism | Selected revision | Grounding |
|---|---|---|
| Datastar | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | `reference-code/datastar/bundles/datastar.js`; shipped copy at `resources/public/js/datastar.js` |
| Datastar Clojure SDK | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | form POST and `patch-elements!` examples at `reference-code/datastar-clojure/src/dev/examples/forms/datastar.clj:29-78`; SSE/write semantics under `reference-code/datastar-clojure/src/main/` |
| Hyperlith | `b08a8e8689e1654fd7e0ce654064a703ca1f4772` | whole-page/live UI idioms under `reference-code/hyperlith/src/hyperlith/`; the small chat example at `reference-code/hyperlith/examples/chat_atom/src/app/main.clj:43-56` |
| Reitit | `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | method endpoints, middleware, and Ring dispatch at `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:121-151,360-404` |
| core.async flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | fresh render proc and report-channel owner; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/` |
| Fresh render router | current checkout | `src/seon/render.clj:81-135`; `src/seon/render/block.clj:331-464` |
| Fresh live page | current checkout | `src/seon/render/web.clj:1-45,174-256,433-614` |
| Fresh message/wake path | current checkout | `src/seon/schema/message.edn:1-44`; `src/seon/cluster/message.cljc:1-16,201-290`; `src/seon/cluster/wake.cljc:83-101` |

## What the 2026-07-27/28 fresh wave already landed

These are settled owners, not shopping-list items.

| Landed mechanism | Evidence | Consequence for conversion |
|---|---|---|
| One typed render router and one block mechanism | `src/seon/render.clj:81-135`; `src/seon/render/block.clj:331-464`; commits `6dcda1ab9`, `5e71715c1` | Do not port `src-old/seon/render.cljc` or its registry. |
| Stable per-block morph targets | `src/seon/render/block.clj:77-89`; `src/seon/render/web.clj:174-256,433-505`; commit `065542731` | New page pieces are blocks; no whole-page feed registry. |
| One derivation per cluster and per-tab sliding-1 taps | `src/seon/render/web.clj:1-45,433-505`; commit `2e372027d` | Old mailboxes, subscription caches, heartbeats, and structural-settle logic stay dead. |
| Generic data panel, error card, serializer, and bounded admission | `src/seon/render/block.clj:895-1039`; `src/seon/render/hiccup.clj:1-511`; commits `5e71715c1`, `ed26ccaa6` | Do not port the old value explorer, serializer, or render-error path. |
| Database drill | `src/seon/render/data.clj:40-221`; `src/seon/render/web.clj:581-604`; commits `f2a6ec0f2`, `4c62057fa` | Retire both old `/data` implementations. |
| Root page and Phosphor Terminal presentation | `src/seon/render/root.clj:36-274`; `resources/public/css/input.css:739-1163`; commits `16c050be5`, `1f12eb254` | Preserve and extend the current semantic classes; do not restore old utility-heavy root cards. |
| Distance semantics and agent neighbourhood | `src/seon/render/agent.clj:61-235`; `src/seon/render/walk.clj:1-461`; commits `d28598214`, `1a499558a`, `0552b68ad` | Agent pages compose these family renders; they do not recreate context acquisition. |
| Channel-borne streamed reply and tokens | `src/seon/render/root.clj:156-197`; `src/seon/render/web.clj:356-409`; commits `5daf05e24`, `abf8680d7` | No database partials or streaming-specific renderer. |

## Complete inventory

There are **83 inventory entries** below. Historical entries are included
because the current quarry has already had some of the owner's working UI cut
out of it.

### Pages, routes, and human inputs

| ID | Component | File:line | What it does | Design-language notes |
|---|---|---|---|---|
| P01 | Database route manifest | `src-old/seon/route.cljs:43-80,89-112` | Stores method, pattern, handler, middleware, and route identity as database data for root, agent, value, feed, debug, and call. | Route truth is data; do not return to a hand-written dispatch ladder. |
| P02 | Surviving old root shell | `src-old/seon/web/datastar.cljs:553-623,929-980` | Builds the branded HTML shell, `#app-view`, feed opener, and root response after the action forms were cut. | Warm-black terminal shell; input and feed opener are siblings of the morph target. |
| P03 | Last working root page | `48b89dd7d^:src/seon/web/datastar.cljs:1013-1026` | Composes the new-agent form, root message bar, and root feed opener around the live root view. | Root is operable, not merely an observation dashboard. |
| P04 | Last working agent page | `48b89dd7d^:src/seon/web/datastar.cljs:993-1011` | Serves a full-height agent page with a bottom message bar and hidden feed owner. | The input bar is normal-flow chrome outside `#app-view`, so morphs do not steal focus or text. |
| P05 | Message input bar | `48b89dd7d^:src/seon/web/datastar.cljs:625-667` | `#app-chat` POSTs form data to `/chat?agent=<id>`, binds `$text`, rejects blank input, clears after send, and relies on the feed for the reply. | Dense one-line terminal bar: dark field, amber send action, explicit target in placeholder. |
| P06 | Agent creation bar | `48b89dd7d^:src/seon/web/datastar.cljs:669-706` | POSTs optional namespace, purpose, and initial message to `/agents`. | Same bottom-chrome grammar as message entry; three labeled-by-placeholder fields and one amber action. |
| P07 | Message POST handler | `48b89dd7d^:src/seon/web/serve.cljs:221-249,1602-1648` | Parses form data, resolves target, rejects blank text, calls the old message owner, logs intake, and returns 204/422. | A 204 deliberately emits no competing morph; the ordinary feed paints the result. |
| P08 | Agent creation POST | `48b89dd7d^:src/seon/web/serve.cljs:620-704`; `48b89dd7d^:src/seon/route.cljs:98-114` | Validates namespace and calls the old start/delegate path; route is same-origin gated. | The browser supplies optional purpose and first message in one action. |
| P09 | JVM `/data` page | `src-old/seon/web/data.clj:9-106`; `src-old/seon/web/server.clj:200-239` | Serves a bounded database view and its feed from the interim JVM server. | Operator page reuses header, entity panels, and terminal value presentation. |
| P10 | Debug page and feed | `src-old/seon/web/debug.cljs:35-175` | Serves `/agent/{id}/debug`, finds the latest persisted turn, and live-paints its prompt or reply. | Global header plus small amber page title and unornamented exact text. |
| P11 | CLJS `/data` page and feed | `src-old/seon/web/debug.cljs:177-304` | Pages AEVT data, acquires entities, renders bounded panels, and streams the view. | Entity heading, database link-back, and consistent error/empty treatment. |
| P12 | Agent value route | `src-old/seon/web/value.cljs:8-58`; `src-old/seon/render/handlers/eval.cljc:136-174`; `src-old/seon/route.cljs:102-105` | Lazily drills a retained eval/entity value when a disclosure opens. | Keeps large technical payloads out of normal transcript morphs. |
| P13 | Agent action route | `src-old/seon/route.cljs:110-112`; `src-old/seon/web/reactive/call.cljs:206-270` | POST `/agent/{id}/call` validates and records an interactive function request. | Agent-authored controls look local but cross one visible, guarded HTTP boundary. |
| P14 | Static and operator routes | `src-old/seon/web/router.cljs:255-305`; `src-old/seon/web/server.clj:169-239` | Supplements database routes with CSS, JS, readiness, data, chat, and operator endpoints. | Same-origin and loopback policy are route data, not ad hoc handler checks. |
| P15 | Graceful route miss | `src-old/seon/web/router.cljs:291-305` | Redirects unmatched browser paths home instead of leaving a raw dead-end. | Keeps navigation inside the product shell. |

### Page composition, transcript, surfaces, and canvas

| ID | Component | File:line | What it does | Design-language notes |
|---|---|---|---|---|
| C01 | Persistent system header | `src-old/seon/ui/header.cljs:17-47` | Fixed header with brand mark, agent/running counts, data link, home link, and spacer. | Diamond mark, pipe separators, dim labels, amber live dot, 2.25rem reserved height. |
| C02 | Agent-local header | `src-old/seon/ui/agent_view.cljs:34-51` | Back link, agent id, debug link, and derived state dot/text. | Status is dot plus text, never a decorative pill. |
| C03 | Agent page layout | `src-old/seon/ui/agent_view.cljs:72-93` | Full-height three-column layout with a two-column primary surface and one-column context rail. | One focal surface, subordinate previews; terminal density over dashboard chrome. |
| C04 | Primary surface | `src-old/seon/ui/agent_view.cljs:53-59` | Shows the expanded face whose selection matches `$selected`. | Bordered warm-black panel, contained scrolling, stable id. |
| C05 | Context rail card | `src-old/seon/ui/agent_view.cljs:61-70` | Shows compact faces for nonselected surfaces and changes selection on click. | Small label strip over a clipped preview; the preview cannot capture events. |
| C06 | Surface materialization | `src-old/seon/render/surface.cljc:30-87` | Gives context/canvas results stable selection, label, reads, touch, compact, and expanded faces; chooses most recent focus. | Canvas wins an untouched tie; compact/expanded are presentations of one unit. |
| C07 | Root fleet summary | `src-old/seon/render/system.cljs:25-73,90-130` | Derives agent state and produces synchronized human and AI cluster views. | Count strip plus auto-fill agent collection; state is a dot, word, and muted/amber color. |
| C08 | Root agent card | `src-old/seon/render/system.cljs:75-88` | Links each agent, showing purpose, state, root marker, and open affordance. | Warm-black card, thin border, no shadow; root gets restrained amber emphasis. |
| C09 | Conversation bubble family | `src-old/seon/render/chat.cljc:46-130` | Classifies human, agent, peer, and system messages, renders direction-sensitive bubbles, and supplies an empty invitation. | Right/amber human, left/cream agent, subordinate peer, calm centered system line. Preserve hierarchy, not the oversized rounded styling. |
| C10 | Message family renderer | `src-old/seon/render/handlers/message.cljc:34-114` | Formats one message for AI and HTML with sender/recipients, timestamp, and server-side Markdown. | Chat-first alignment with compact metadata; no hop counter in presentation. |
| C11 | Transcript activity row | `src-old/seon/render/handlers/eval.cljc:176-198` | Compresses an eval to narration/operation, duration, and done/failed. | One quiet technical row; historical source/results stay out of every live morph. |
| C12 | Eval detail card | `src-old/seon/render/handlers/eval.cljc:200-270` | Provides deliberate disclosure of narration, highlighted source, live result, or full failure. | Details/summary is the density tool; ordinary eval failure is calm, not a core fault card. |
| C13 | Canvas dual-face contract | `src-old/seon/render/canvas.cljc:1-94`; `resources/public/css/input.css:336-374` | One render contains compact and expanded faces selected by container size or focus. | Compact is clipped; expanded is scrollable; focus always chooses expanded. |
| C14 | Canvas validation and error face | `src-old/seon/render/canvas.cljc:135-279,576-639` | Validates Hiccup and turns invalid/throwing render output into a visible error response. | Small red edge and ordinary terminal prose; never a blank canvas. |
| C15 | Canvas greeting/default | `src-old/seon/render/canvas.cljc:334-438,538-575` | Resolves pinned/default content, provides the welcome face, and shows the source needed to replace it. | Empty state teaches the next action instead of presenting an empty rectangle. |
| C16 | Canvas controls | `src-old/my/canvas.cljc:214-303` | Pure constructors for button, input, select, toggle, and form with qualified field signals and handler references. | One compact control vocabulary, amber focus, dark fields, visible disabled/pending behavior. |
| C17 | Dual human/AI UI helpers | `src-old/my/ui.cljc:41-272` | Builds status lines, key/value tables, badges, bullets, progress, tables, and sections as synchronized HTML/AI values. | Dense semantic data presentation; the old rounded badge conflicts with the newer dot/text rule. |

### Render functions and reusable formatting

| ID | Component | File:line | What it does | Design-language notes |
|---|---|---|---|---|
| R01 | Entity schema selection | `src-old/seon/render.cljc:243-302` | Selects a primary entity schema and its declared renderer. | The useful rule is family ownership; the implementation predates the fresh open router. |
| R02 | Entity HTML render | `src-old/seon/render.cljc:382-426` | Resolves and invokes an entity's HTML renderer with an acquired node. | Family-specific treatment without page-specific branches. |
| R03 | Entity AI render | `src-old/seon/render.cljc:942-978` | Resolves the same entity family for model-facing prose. | Human/AI twins stay synchronized by ownership. |
| R04 | Typed block renderer | `src-old/seon/render.cljc:428-449,822-940` | Handles Markdown, source, value requests, error values, Hiccup, and generic data. | The behavior catalog is useful; the old branch mechanism is not. |
| R05 | Recursive renderer | `src-old/seon/render.cljc:992-1132` | Walks arbitrary values with custom, schema, and generic fallbacks. | One guarded walk was better than callers branching, but fresh block/walk now owns it. |
| R06 | Loud render failure | `src-old/seon/render.cljc:303-380` | Normalizes response keys and applies the development-panic/production-error policy. | Visible flat error values; no blank or swallowed render failure. |
| R07 | Generic fallback | `src-old/seon/render.cljc:1016-1087` | Chooses custom, schema, or generic rendering when no specialist exists. | Ordinary data remains legible without a bespoke component. |
| R08 | Value projection and drill | `src-old/seon/render/value.cljc:79-226,310-443,486-1060,1250-1927` | Admits, samples, summarizes, paginates, and renders bounded arbitrary values. | Visible whitespace, stable paths, truncation markers, and tabular drill affordances. |
| R09 | Hiccup serializer | `src-old/seon/ui/html.cljc:72-353` | Escapes text and attributes, supports raw trusted text, parses shorthand tags, and serializes Hiccup. | Server-produced HTML stays morph-safe and XSS-safe. |
| R10 | Markdown renderer | `src-old/seon/ui/markdown.cljc:33-226` | Renders safe inline links, headings, lists, code, quotes, and blocks to Hiccup. | Server-side rendering avoids a client pass racing a morph. |
| R11 | Clojure source renderer | `src-old/seon/ui/clojure.cljc:35-192` | Tokenizes Clojure source into semantic highlight spans. | Server-side highlighting survives morphs and keeps technical detail compact. |
| R12 | Function family renderer | `src-old/seon/render/handlers/fn.cljc:18-137` | Shows function name, args, doc, schema/compile status, and bounded source. | Documentation first; source is disclosed only when useful. |
| R13 | Namespace family renderer | `src-old/seon/render/handlers/ns.cljc:14-110` | Summarizes a namespace and its contents for AI and HTML. | Compact navigation-friendly code inventory. |
| R14 | Schema family renderer | `src-old/seon/render/handlers/schema.cljc:12-73` | Summarizes live Malli shape and code form. | Type/shape is a small technical disclosure. |
| R15 | Test family renderer | `src-old/seon/render/handlers/test.cljc:29-156` | Derives pass/fail state, glyph, summary, and details. | Status color and glyph carry state; details disclose failure text. |
| R16 | Eval family renderer | `src-old/seon/render/handlers/eval.cljc:41-270` | Supplies exact AI transcript, compact activity, and deliberate technical HTML. | One family can have a transcript projection and a forensic projection without two registries. |
| R17 | Stable view identity | `src-old/seon/render/view_unit.cljc:1-49`; `src-old/seon/render/surface.cljc:30-36` | Encodes ordinary identity data into deterministic DOM/view tokens. | Stable ids preserve focus and morph matching. |
| R18 | Old renderer/config schema owners | `src-old/seon/render/core.cljc:1-45`; `src-old/seon/render/configuration.cljc:1-47`; `src-old/seon/render/schema.cljc:1-30` | Resolve old handler symbols and define the old response/config shapes. | Administrative plumbing, not design language. |

### Upstream context and interaction projections

| ID | Component | File:line | What it does | Design-language notes |
|---|---|---|---|---|
| U01 | Context acquisition facade | `src-old/seon/agent/ctx/acquisition.cljc:1-22` | Wraps execute-many, selected function calls, and Promise aggregation for old context renderers. | No visual language; it kept async acquisition out of pure formatting functions. |
| U02 | Automatic canvas selection | `src-old/seon/agent/ctx/canvas.cljc:23-178,307-354` | Finds authored Hiccup twin functions, infers the most recently touched one, renders it, and publishes a canvas context block. | The human canvas and agent context shared one twin, but touch-history selection was implicit machinery. |
| U03 | Derived render-function blocks | `src-old/seon/agent/ctx/render_fns.cljc:19-138` | Discovers public functions whose output schema declares AI/Hiccup twins and turns them into auto-run context blocks. | The valuable invariant is schema-declared twins; automatic block creation is old scaffold. |
| U04 | Interaction outcome surface | `src-old/seon/agent/interaction/render.cljc:12-90` | Queries the latest terminal action fact and renders compact/expanded status, handler, and result/error. | Reconnect derives the outcome from database truth; green/red state and disclosure match the canvas language. |

### Datastar feeds, signals, focus, and action idioms

| ID | Component | File:line | What it does | Design-language notes |
|---|---|---|---|---|
| L01 | Patch-elements framing | `src-old/seon/web/datastar.cljs:127-159` | Formats a whole-element Datastar morph and a visible error patch. | The target element's stable id is the interaction contract. |
| L02 | Render-to-patch normalization | `src-old/seon/web/datastar.cljs:161-212` | Awaits render results, serializes Hiccup, and avoids pushing unchanged HTML. | Equality suppression keeps the interface still when facts did not change. |
| L03 | Gzip feed writer | `src-old/seon/web/datastar.cljs:214-337` | Selects encoding, writes gzip SSE, tracks backpressure, and drains complete events. | Transport detail is invisible to component authors. |
| L04 | Feed heartbeat | `src-old/seon/web/datastar.cljs:340-390` | Maintains a global interval for idle feed connections. | A transport backstop, not page behavior. |
| L05 | Database read capture | `src-old/seon/web/datastar.cljs:395-461` | Captures read evidence and renders a full view after relevant database changes. | View remains a pure function of a database value. |
| L06 | Subscription registry | `src-old/seon/web/datastar.cljs:463-541` | Attaches/detaches views to normalized interests and survives hot reload. | Operational machinery only; fresh flow replaced it. |
| L07 | Feed/view identity | `src-old/seon/web/datastar.cljs:641-815` | Validates per-tab view ids and opens/replaces/releases feed connections. | Each tab owns its connection, not the rendered fact. |
| L08 | Feed opener placement | `src-old/seon/web/datastar.cljs:553-623,625-639` | Places `data-init @get(...)` on a hidden sibling outside the morph target with infinite retry and hidden-tab close/reopen. | This is the key focus rule: the stream owner and human inputs cannot be morphed away by their own stream. |
| L09 | Form signals | `48b89dd7d^:src/seon/web/datastar.cljs:636-706` | Uses `data-on:submit`, `data-bind`, form content type, and client-side required fields for chat/create. | Browser state holds unsubmitted text; database facts begin only at accepted POST. |
| L10 | Handler-slot transform | `src-old/seon/web/reactive/transform.cljs:60-132,177-267` | Encodes pure data args, qualifies function symbols, and rewrites `:on-*` Hiccup slots to `data-on:* @post('/agent/{id}/call')`. | Agent renderers author Clojure values, not Datastar URL strings. |
| L11 | Action lifecycle signals | `src-old/seon/web/reactive/transform.cljs:136-171` | Derives stable pending/error signal names and adds indicator, disabled, aria-busy, and visible failure text. | Pending and failure are visible at the control, with retry guidance. |
| L12 | Action capability gate | `src-old/seon/web/reactive/call.cljs:24-87` | Proves target agent and committed function ownership before admitting an action. | The control is not an authority; the POST boundary is. |
| L13 | Action request record | `src-old/seon/web/reactive/call.cljs:89-142,149-270` | Parses signals/args, filters ambient page state, commits one pending interaction, and returns 204/JSON error. | One action identity joins browser intent to later database-derived presentation. |
| L14 | Interim JVM feed mailboxes | `src-old/seon/web/feed.clj:14-170` | Runs per-connection latest-value queues, drains gzip writers, sends heartbeats, and exposes metrics. | Correct loss semantics, but duplicate machinery after the fresh mult/sliding-1 pipeline. |

### Phosphor Terminal design system and assets

| ID | Component | File:line | What it does | Design-language notes |
|---|---|---|---|---|
| D01 | Runtime utility safelist | `resources/public/css/input.css:17-52` | Emits the bounded layout, typography, palette, border, overflow, and control utilities agent Hiccup may use. | Small vocabulary; semantic HTML is preferred over utility soup. |
| D02 | Font and type scale | `resources/public/css/input.css:54-64`; `resources/public/fonts/jetbrains-mono-500.woff2` | Declares JetBrains Mono fallback stack and the dense `text-2xs` token. | Mono everywhere, roughly `text-xs` primary and `text-2xs` metadata. |
| D03 | Warm-black base tokens | `resources/public/css/input.css:66-71` | Defines base 950, 900, 850, 800, and 700. | Layered warm blacks replace white cards, gradients, and shadows. |
| D04 | Cream text ramp | `resources/public/css/input.css:73-85` | Defines cream 50 through dim 700. | Hierarchy comes from restrained contrast, never pure white versus gray. |
| D05 | Semantic and log colors | `resources/public/css/input.css:87-102` | Defines amber signal, success, error, warning, info, eval, and log event colors. | Amber is action/live emphasis; red is error; green is healthy/done. |
| D06 | Morph motion | `resources/public/css/input.css:105-312` | Provides skeleton, disclosure, row entrance, and morph highlight animations. | Motion identifies change; it is not ornamental page transition. |
| D07 | Canvas card system | `resources/public/css/input.css:336-374` | Selects compact/expanded faces with a 480px container breakpoint and forces expanded focus. | One content unit adapts to card versus focal surface. |
| D08 | Agent content containment | `resources/public/css/input.css:376-451` | Bounds long prose/code, sizes rail typography, compacts activity, plan, and reply content. | No authored value can blow out its column. |
| D09 | Semantic content layer | `resources/public/css/input.css:453-519` | Styles classless headings, lists, tables, code, quotes, links, and emphasis inside render containers. | Agents can write semantic Hiccup and inherit the design language. |
| D10 | Fresh semantic render classes | `resources/public/css/input.css:521-648` | Styles problems, error cards, and generic data panels by one semantic class per thing. | This is the fresh direction: Clojure says what a thing is; CSS says how it looks. |
| D11 | Fresh data and stream base | `resources/public/css/input.css:650-737` | Styles data breadcrumbs/windows/paging and streaming token/reply strips. | Tabular values stay tabular; streams read as live instruments. |
| D12 | Fresh root rhythm | `resources/public/css/input.css:739-954` | Gives the current root page its narrow measure, masthead, section rules, lists, hover, and empty states. | Quiet strip, uppercase micro-labels, real counts, no decorative dashboard cards. |
| D13 | Neighbourhood/family language | `resources/public/css/input.css:955-1017` | Styles distance-walk connections and family entries with restrained left rules. | Connection label is metadata; family prose is the subject. |
| D14 | Fleet state dots and stream strip | `resources/public/css/input.css:1019-1163` | Styles the one real metrics panel, state dots, plumbing footnote, and paired stream blocks. | Dots plus text encode state; independent morph targets still read as one strip. |
| D15 | Data drill and code assets | `resources/public/css/input.css:1165-1307`; `resources/public/css/highlight-github-dark.css:1-10`; `resources/public/js/highlight-*.js`; `resources/public/js/seon-debug.js`; `resources/public/js/reactive-demo.js` | Finishes `/data` grid/path/pager presentation and carries legacy syntax/debug/demo assets. | The current drill CSS stays; server-side fresh renderers should make most client highlight/debug scripts dead. |

## Conversion map

Every inventory id is assigned below. "Dead" means the lessons survive in the
named fresh owner; it does not mean the behavior was unimportant.

| Inventory pieces | Landing | Fresh owner and conversion |
|---|---|---|
| P01, P14, P15 | **boundary concern in `seon.render.web`** | Replace the temporary `cond` in `src/seon/render/web.clj:531-614` with the one Reitit route tree. Seed root, agent, feed, data, action, birth, message, and debug methods there; apply same-origin policy once. Route facts may accrete later, but there must never be a second dispatcher. |
| P02 | **DEAD — subsumed** | The common shell and hidden feed opener already exist at `src/seon/render/web.clj:128-168`; fresh feeds morph blocks rather than `#app-view`. |
| P03, P06, P08 | **boundary concern in `seon.render.web`** plus **block with `:seon.render/html`** | Restore an operable root creation surface only after the fresh agent-birth transition exists. The form/chrome belongs at the web boundary; any database-derived list/status remains a root block. Do not call old `start!`/`delegate!`. |
| P04 | **boundary concern in `seon.render.web`** | The agent shell owns stable nonmorphed human controls and the feed opener. Its database-derived content is only a fresh block page. |
| P05, P07, L09 | **boundary concern in `seon.render.web`** | Rebuild human message intake against fresh message facts. Preserve form-mode POST, blank rejection, 204-with-no-morph, `$text` binding, focus retention, and feed-derived reply. Do not call old `agent/message!`; commit the fresh external message row with no `from`. |
| P09, P11, P12, R08 | **DEAD — subsumed** | `src/seon/render/data.clj`, fresh generic data rendering, and `/data` own the drill. Carry no old AEVT page, `/value`, retained-value, or duplicate server. |
| P10 | **block with `:seon.render/html`** plus **boundary concern in `seon.render.web`** | A seeded debug route selects a debug block page using the same page/feed mechanism. The block renders exact prompt, replies, facts, receipts, and errors from fresh families; no debug feed registry. |
| P13, L10-L13 | **boundary concern in `seon.render.web`** | Recreate the agent-authored action boundary only after fresh interaction facts and the capability owner exist. The pure Hiccup transform may survive as a renderer-edge transform; validation, request identity, same-origin, and transaction admission stay in one POST handler. |
| C01 | **block with `:seon.render/html`** | Make the persistent header an ordinary shared page block or one shell projection with a stable id. Reuse live counts and navigation; root's landed masthead must not be replaced by the old fixed utility string. |
| C02-C05 | **block with `:seon.render/html`** | Compose the ordinary agent page from an agent header, focal transcript/canvas slot, and context rail using fresh `block/page`, slots, and distance. Browser selection is presentation state, not a new database registry. |
| C06, R17 | **DEAD — subsumed** | Fresh `surface-id`, `slot`, `entity-slot`, `distance`, `surfaces`, `page`, and `select` own identity and placement (`src/seon/render/block.clj:77-846`). Preserve focus semantics through those functions, not the old surface map. |
| C07, C08 | **DEAD — subsumed** | Fresh root blocks and the fleet-oversight family already tell this story. Keep the current root rhythm and state-dot CSS. |
| C09-C12, R16 | **family default renderer** plus **block with `:seon.render/html`** | Messages, runs, receipts, evals, and errors keep one family lens each. A transcript block selects/orders their ordinary units and applies those family defaults. Preserve direction hierarchy, Markdown, activity compaction, and deliberate forensic disclosure; do not revive a transcript-specific registry. |
| C13, C15 | **block with `:seon.render/html`** | Re-express canvas/default/welcome content as ordinary blocks and slots. Distance and page selection replace pin/touch machinery; the empty canvas still teaches the next action. |
| C14 | **DEAD — subsumed** | Fresh Hiccup admission and the fresh error block already own malformed/throwing output. |
| C16 | **family default renderer** plus **boundary concern in `seon.render.web`** | Keep pure button/input/select/toggle/form constructors as agent-facing render functions. Qualified field encoding and event rewriting feed the one action POST boundary. Old effectful `show!`, `clear!`, `pinned`, `state`, and `save!` (`src-old/my/canvas.cljc:73-212`) are not ported; redesign future agent changes as values, capability requests, or durable facts. |
| C17 | **family default renderer** | Quarry the pure dual projections as small semantic render helpers. Keep status-line, tables, bullets, progress, and section behavior; replace the rounded badge with the current dot/text or restrained label language. |
| R01-R05, R07, R18 | **DEAD — subsumed** | Fresh schema-declared `:seon.render/ai`/`:seon.render/html`, `seon.render/render`, block selection, and distance walk are the only router. Porting the old registry would violate the one-router rule. |
| R06 | **DEAD — subsumed** | Fresh admission, error values, and `seon.render.block/error-card` own the same loud/degrade contract. |
| R09 | **DEAD — subsumed** | `src/seon/render/hiccup.clj` is the maintained serializer. |
| R10, R11 | **family default renderer** | Restore server-side Markdown and Clojure-source defaults only when a fresh family needs them. They return admitted fresh Hiccup and never run a client-side postprocessor. |
| R12-R15 | **family default renderer** | Rebuild only the useful family-specific functions—function, namespace, schema, and test—on fresh unit maps and schemas. The owning schema declares each twin; no handler table. |
| L01-L08, L14 | **DEAD — subsumed** | Fresh `seon.render.web` owns one flow derivation, equality suppression, one mult, per-tab sliding-1 tap, reconnect repaint, and per-block morph. Keep only the nonmorphed-opener lesson already present at `src/seon/render/web.clj:161-168`. |
| U01-U03 | **DEAD — subsumed** | Fresh blocks, family render declarations, distance walk, and the single flow derivation replace async context acquisition, auto-run render blocks, and touch-derived canvas choice. Preserve no hidden discovery path. |
| U04 | **family default renderer** | If fresh interaction facts land, their family owns a compact and expanded HTML twin. Selection is an ordinary block query; reconnect still derives the latest relevant outcome from facts. |
| D01-D05, D07-D14 | **CSS tokens into `resources/public/css` — already landed** | These are the maintained design language now. Extend semantic classes in `resources/public/css/input.css`; never create a second theme file or copy the old utility strings into every renderer. |
| D06 | **CSS tokens into `resources/public/css`** with pruning | Keep motion that communicates a fresh/changed row or disclosure. Remove legacy selectors that can no longer occur after the fresh pages settle. |
| D15 | **CSS tokens into `resources/public/css`** plus **DEAD assets** | Keep the current drill rules and font. Keep highlight colors only if fresh server-side source Hiccup emits their classes. Delete client highlight, debug, Scittle, and reactive-demo scripts when no fresh route references them. |

## The missing human-message boundary

### What worked before

The strongest old contract is the separation of responsibilities:

1. `#app-chat` lived outside the morph target, so a reply could not erase the
   input, steal focus, or terminate the feed
   (`48b89dd7d^:src/seon/web/datastar.cljs:625-650`).
2. `data-bind="text"` held unsubmitted browser state; the form submitted
   `application/x-www-form-urlencoded`, required nonblank text, and cleared the
   signal after send (`48b89dd7d^:src/seon/web/datastar.cljs:651-667`).
3. The handler admitted one message and returned 204; the transaction woke the
   agent and the ordinary feed rendered both accepted input and later reply
   (`48b89dd7d^:src/seon/web/serve.cljs:1602-1648`).

That interaction shape should return. Its old domain call should not.

### What fresh has

- A message entity requires id, `to`, content, and time; `from` is optional
  (`src/seon/schema/message.edn:5-44`).
- Absence of `from` deliberately means outside the agent population: human or
  system (`src/seon/schema/message.edn:14-25`).
- A new `to` datom is the wake; there is no queue or acknowledgement flag
  (`src/seon/cluster/message.cljc:11-16`).
- The message family already supplies both AI and HTML defaults
  (`src/seon/cluster/message.cljc:296-339`).
- The fresh root can list message facts but cannot produce one
  (`src/seon/render/root.clj:124-154`).
- The web handler has no state-changing route
  (`src/seon/render/web.clj:531-614`).

### Required fresh contract

The conversion slice must settle one same-origin POST route in the one Reitit
tree and one function at the web boundary that:

- resolves the target `:seon.cluster.agent/id` against the selected cluster's
  current database value;
- rejects a missing target and blank/oversized content as user-input values;
- allocates a unique nonempty `:seon.cluster.message/id` through the surviving
  identity owner—the fresh code does not yet expose a browser-message identity
  helper, so the conversion must settle that contract rather than invent one
  inside the handler;
- transacts exactly one row with `id`, `to`, `content`, and `at`, omitting
  `from`;
- records normal transaction provenance, without copying provenance onto the
  message;
- returns 204 after commit and lets the existing feed repaint; and
- presents refusal text at the input without creating a notification queue.

The route's final path is unsettled. `/chat?agent=` is the proven historical
wire, while the current architecture route table names no human-message POST
(`docs/seon/architecture/ui.md:537-592`). Name it once in the route-data slice;
do not temporarily add `/chat` to the `cond` and then add a second canonical
route later.

## Ordered conversion wave

### Slice 1 — human message, one vertical proof

Land the one Reitit route tree and same-origin middleware needed by the first
POST. Add the external-message transaction boundary and a stable message bar
outside the agent page's morphable blocks. Prove:

- browser text produces one no-`from` message fact;
- its `to` datom wakes the named agent;
- the settled reply appears through the existing per-block feed;
- reconnect repaints the same facts;
- focus and typed-but-unsubmitted text survive unrelated morphs; and
- blank, unknown-agent, and duplicate-submit cases are visible and bounded.

This is the first slice because the owner can otherwise look at an agent but
cannot talk to it.

### Slice 2 — ordinary agent page and transcript

Compose `/agent/{id}` from fresh blocks:

- persistent/global header;
- agent-local identity and live state;
- transcript block selecting human, agent, peer, run/receipt, and error facts;
- focal content slot; and
- context rail filled through fresh distance/slot semantics.

Use family defaults for each transcript unit. Keep compact activity rows in the
normal transcript and exact source/results in a deliberate detail or debug
surface. Selection must remain tab-local presentation state and survive block
morphs; it must not become a stored focus flag or a second surface registry.

### Slice 3 — agent birth from root

Restore `POST /agents` in the same route tree and a root creation surface using
the fresh agent/flow creation transition. Preserve optional purpose and initial
message only if the fresh blueprint admits them atomically. Do not port the old
start/delegate functions or make birth a special render path.

### Slice 4 — agent-authored controls

Quarry the pure `my.canvas` constructors, qualified field-signal codec, Hiccup
handler transform, visible pending/error state, and `/agent/{id}/call`
capability checks. Express the admitted action as the fresh durable
interaction/request fact and let ordinary blocks render its outcome. No direct
in-eval mutation, side-channel callback, or second action dispatcher survives.

### Slice 5 — debug as an ordinary page

Seed a debug page using the same block/page/feed path. Compose exact prompt,
input blocks, rendered context, receipts, errors, provider response, and timing
from family renderers. The old debug feed and page cache remain deleted.

### Slice 6 — family rendering library

Bring back server-side Markdown, Clojure source highlighting, and useful
function/namespace/schema/test family defaults as demand reaches them. Convert
one family at a time through its schema's `:seon.render/ai` and
`:seon.render/html`; never batch-port the old renderer.

### Slice 7 — CSS and asset closure

Add only semantic classes demanded by slices 1-6 to
`resources/public/css/input.css`. Reuse the landed palette, type scale, content
layer, card faces, status dots, root rhythm, and drill. Once reference searches
show no consumers, remove legacy client highlight/debug/demo scripts and CSS
selectors that target the deleted whole-page renderer.

## Graduation gate

The conversion wave is complete when one browser can start or open an agent,
send a message, see the message fact wake that agent, receive the settled reply
through the one block feed, switch focal context without losing input or
selection, open exact debug evidence, and reconnect to the same database-derived
page. Route dispatch is one Reitit tree; rendering is one family/block router;
placement uses slots and distance; presentation state stays in the tab; durable
truth stays in the database; and no `src-old` feed, registry, serializer,
value-explorer, or canvas-mutation mechanism has been restored.

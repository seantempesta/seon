---
type: research
status: completed
tags: [research, web, agent]
---

# Surface vocabulary and dead UI path audit — 2026-07-13

## TL;DR

The desired model already exists in the newest agent-view code, but the cutover is
only partial:

- A **block** is a database-owned context unit. It may have an agent-facing render,
  a human-facing render, or both.
- A **surface** is a resolved human-facing render shown by the web UI.
- The **canvas** is the focal, agent-controlled surface. Its persisted attribute is
  already the correct fully namespaced `:seon.render.canvas/content`.
- A **card** is only a visual CSS component. It is not a database entity, render
  protocol, or architectural concept.
- A **slot** remains a useful named layout placement. A **view/page** remains a
  route-level composition of surfaces.

`seon.ui.agent-view` already models a surface catalog, a focused surface, and a
supporting-surface rail. The database-driven reactive path is also already singular:
Datahike transaction → listener/fan-out → Datastar whole-element morph. This work
does not need a new renderer, event bus, cache, route family, or compatibility API.

The incomplete parts are naming and dead-path removal:

- Active public symbols and schemas still expose `tile`, including
  `last-updated-tile`, `::tile-sym`, `check-tile-unresolved`, `error-tile`, and
  `steps-tile-html`.
- Active DOM and CSS still expose `tile-*` and `.seon-tile*`.
- Three separately maintained skill trees have drifted. The runtime fallback under
  `.claude/skills` still teaches deleted `my.tile`, `:seon.render.live-tile/content`,
  `/world`, and `seon.ui.world`, even though production source has moved on.
- `src/my/tile.cljs` has already been removed and `my.canvas` is the one active API,
  but live docs and skills still teach both vocabularies.
- Four context-block display namespaces are dormant and should be deleted:
  `seon.agent.ctx.findings`, `seon.agent.ctx.inventory`,
  `seon.agent.ctx.jobs`, and `seon.agent.ctx.testrun`. This is a precise namespace
  deletion, not a global deletion of findings, inventories, jobs, or test-run facts.
- Production no longer has a world view or inspector namespace. Remaining active
  `world`/`inspector` branding is primarily stale documentation and runtime skills.

The cutover should be atomic because there is no data migration requirement. Rename
the existing one mechanism in place, update all producers and consumers together,
regenerate CSS, delete the four dormant block adapters, converge the skill source,
then prove behavior through database-driven UI updates and scoped tests. Do not add
legacy aliases or parallel namespaces.

## Scope and method

This audit covers the active CLJS pod, its current config, active architecture and
component documentation, developer/runtime skills, tests for the active path, and
the downstream ACME consumer. It inventories the current tree; it does not modify
production code.

The audit deliberately excludes generated output, stores, logs, captured eval runs,
vendored library source, old worktrees, archived research, and historical PRD
narratives. Those records must continue to describe the code that existed at the
time. A repository-wide blind substitution would corrupt legitimate words such as
`volatile`, the mathematical phrase “tile the frame,” WebAssembly's WIT `world`
keyword, Node's Inspector/CDP API, and the `inspect.ai` product name.

The current branch also has an active, shared ACME work lane. ACME was inspected
read-only. Its changes must be coordinated after that lane is clean rather than
edited concurrently.

## Target vocabulary and invariants

| Term | Exact meaning | Stored? | Examples |
|---|---|---:|---|
| block | A database-owned context unit with zero or more render declarations | yes | `:seon.agent.ctx/name`, `:seon.render/ai`, `:seon.render/html` |
| render | An ephemeral projection of a block or value for one audience/format | no | AI text, HTML-response, hiccup |
| surface | A resolved HTML render displayed by the web UI | no | transcript surface, plan surface, warnings surface |
| twin | The AI and HTML projections of the same block/function | no | a block with both `:seon.render/ai` and `:seon.render/html` |
| canvas | The focal agent-controlled surface in an agent view | pointer only | `:seon.render.canvas/content`, `seon.render.canvas`, `my.canvas` |
| card | A visual CSS component or compact/expanded face | no | roster card, transcript card, error card |
| slot | A named layout placement for a surface | no | `seon.render/slot`, `:seon.render/slot` |
| view/page | Route-level layout composed from surfaces | no | `/agents`, `/agent/{id}`, debug view |

The render-presence rules remain unchanged:

- `:seon.render/ai` only: prompt/context only; omit it from the visual surface rail.
- `:seon.render/html` only: web surface only; it consumes no prompt tokens.
- both: one block with dual renders/twins.
- neither or a renderer returning `nil`: omit it.

The canvas remains a normal database-derived surface with one optional pin:

1. If `:seon.render.canvas/content` is present, resolve that symbol or literal hiccup.
2. Otherwise derive the agent's most recently updated authored surface.
3. Otherwise show `seon.render.canvas/welcome`.

When a surface is focused in the primary area, the same surface is omitted from the
supporting rail. `seon.ui.agent-view` already implements this behavior; do not add a
second focus state or a client-only copy.

## Current cutover state

Commit `dd5f3fbc` performed an initial file/API move:

- `src/my/tile.cljs` was replaced by `src/my/canvas.cljs`.
- `src/seon/render/live_tile.cljs` became `src/seon/render/canvas.cljs`.
- `src/seon/agent/ctx/live_tile.cljs` became
  `src/seon/agent/ctx/canvas.cljs`.
- the newer `ui-canvas` skill replaced `ui-live-tiles` in two skill trees.

That move established the right persisted canvas key and the right `my.canvas`
namespace, but it did not complete the semantic cutover. It also left three
independent skill copies with different content. The current implementation should
be refined in place; another canvas/surface namespace would recreate the problem.

## Exact active API and schema inventory

### Identifiers that must change

| Current identifier | Owner/file | Target | Reason |
|---|---|---|---|
| `::tile-sym` | `src/seon/agent/ctx/render_fns.cljs` | `::surface-sym` | It identifies a renderable surface function, not a visual component. |
| `last-updated-tile` | `src/seon/agent/ctx/render_fns.cljs` | `last-updated-surface` | This is the database-derived canvas default. |
| `check-tile-unresolved` | `src/seon/warn.cljs` | `check-canvas-unresolved` | The check validates the canvas pointer specifically. |
| `:tile-unresolved` | `src/seon/warn.cljs` | `:canvas-unresolved` | Derived warning kind must describe the fact being checked. It is not persisted state. |
| `tile-line` | `src/seon/render/canvas.cljs` | `welcome-line` | It is welcome-copy shared by the welcome renders. |
| `default-error-tile` | `src/seon/render/canvas.cljs` | `default-error-card` | This is a visual error component. |
| `error-tile` | `src/seon/render/canvas.cljs` | `error-card` | This is the one overridable visual error renderer. |
| `steps-tile-html` | `src/seon/agent/ctx/typeahead_steps.cljs` | `steps-surface-html` | It is the HTML surface for the typeahead-steps block. |
| `tile-preview` | `src/seon/web/datastar.cljs` | `canvas-preview` | The roster previews the agent's current canvas. |
| `agent-tile` | `src/seon/web/datastar.cljs` | `agent-card` | The roster item is a visual/link card. |

`last-updated-tile` and `::tile-sym` have exact callers in:

- `src/seon/render.cljs`
- `src/seon/ui/agent_view.cljs`
- `src/seon/agent/ctx.cljs`
- `src/seon/agent/ctx/canvas.cljs`
- `test/seon/agent/ctx/render_fns_test.cljs`
- `test/seon/ui/agent_view_test.cljs`

`error-tile` has exact active callers in:

- `src/seon/render.cljs`
- `src/seon/agent/ctx/render_fns.cljs`
- `src/seon/handlers/eval.cljs` documentation/contracts
- `test/seon/render_test.cljs`
- `test/seon/render/block_test.cljs`
- downstream `acme/src/acme/overrides.cljs` once the ACME lane is available

`steps-tile-html` has exact consumers in:

- its block declaration in `src/seon/agent/ctx/typeahead_steps.cljs`
- `config/acme.edn`
- `test/seon/agent/ctx/typeahead_steps_test.cljs`

### Fully namespaced keys that are already correct

Do not rename or alias these:

- `:seon.render.canvas/content`
- `:seon.render/html`
- `:seon.render/hiccup`
- `:seon.render/html-response`
- `:seon.render/ai`
- `:seon.render/error`
- `:seon.render/slot`
- `:seon.agent.ctx/name`

These keys describe format, ownership, or placement and already obey the repository's
fully namespaced data rule. In particular, `:seon.render.canvas/content` is the one
persisted attribute attached to an agent. A `:surface` attribute or a `:card` entity
would be duplicate derived state.

### Namespaces and public functions that remain

- `seon.render.canvas` remains the canvas contract and rendering owner.
- `my.canvas` remains the single agent-facing canvas/control API.
- `seon.render/render-agent-canvas` remains the one focal-render entry point.
- `seon.render/block`, `seon.render/slot`, and the block render keys remain the one
  context-to-web projection path.
- `seon.ui.agent-view/surface-catalog` and the existing surface materialization/focus
  functions remain. They are the most complete expression of the target model.

### `my.canvas` is the one agent-facing API

`src/my/canvas.cljs` is current and should be documented from source rather than
reimagined. It exposes:

- `view`
- async `show!`, `clear!`, and `save!`
- `pinned` and `state`
- `button`, `input`, `select`, `toggle`, and `form`

Its public contracts already use fully namespaced `:my.canvas/*` keys and the runtime
injects the current `:seon.agent/id`/database context. Agents should not need to carry
their own identifiers through every control callback.

`src/my/tile.cljs` is already gone. There should be no compatibility `my.tile`
namespace, forwarding var, deprecated alias, or second instructions path. Stale live
references remain in:

- `src/my/CLAUDE.md`
- `.claude/skills/data-oriented-clojure/SKILL.md`
- `.claude/skills/ui-live-tiles/SKILL.md`
- `.claude/skills/seon-context-config/SKILL.md`
- prose in `src/my/ui.cljs`
- newer `ui-canvas` skill prose that still calls the API a tile

`docs/seon/architecture/toolkit.md` is not merely using the wrong noun. It describes
an older, different `my.canvas` API (`:seon.canvas/view` and prebuilt note/pros-cons
constructs) that does not match `src/my/canvas.cljs`. The architecture doc must be
rewritten from the actual public functions and schemas during the cutover.

## Exact DOM and CSS inventory

### CSS source rename

The source-of-truth stylesheet is `resources/public/css/input.css`:

| Current selector | Target selector | Semantics |
|---|---|---|
| `.seon-tile` | `.seon-card` | visual container only |
| `.seon-tile-compact` | `.seon-card-compact` | compact face |
| `.seon-tile-expanded` | `.seon-card-expanded` | expanded face |
| `.seon-tile-reply` | `.seon-card-reply` | clamped reply excerpt |
| `.tile-hero` | `.surface-focus` | primary layout role, not a card identity |

The semantic-content scope must change from
`:is(.seon-tile, .seon-bubble, .markdown, .seon-agent-content)` to the equivalent
`.seon-card` scope. Comments in the same stylesheet must use surface/card vocabulary.

Exact active hiccup producers of these classes are:

- `src/seon/render/canvas.cljs`
- `src/seon/render/default.cljs`
- `src/seon/render/system.cljs`
- `src/seon/agent/ctx/transcript.cljs`
- `src/seon/ui/agent_view.cljs`
- `src/seon/warn.cljs` example code
- downstream `acme/src/acme/widget.cljs` and
  `acme/src/acme/overrides.cljs` when that lane is available

`resources/public/css/output.css` is generated. Change `input.css`, run
`npm run css:build`, and commit the regenerated output; do not edit output by hand.
No old-class compatibility selectors are needed because this testing store has no
migration requirement. Updating all known producers atomically is simpler and makes
unknown agent-authored stale classes fail visibly during development.

### DOM ids and layout names

| Current DOM/API name | Target | Owner |
|---|---|---|
| `#tile-<block-name>` | `#surface-<block-name>` | `seon.render/slot` in `src/seon/render.cljs` |
| `#app-agent-<id>-tile` | `#app-agent-<id>-card` | roster preview in `src/seon/web/datastar.cljs` |
| `tile-hero` | `surface-focus` | primary surface in `src/seon/ui/agent_view.cljs` and CSS |

Keep these existing names:

- `#agent-view-primary-canvas`
- `agent-view-primary-*`
- `agent-view-rail-*`
- `data-agent-primary`
- `data-slot`
- `#app-view`
- route names `/agents`, `/agent/{id}`, and `/agent/{id}/debug`

`data-slot` describes layout placement and is not stale tile vocabulary. DOM ids are
not database identity; the block's stable database key remains
`:seon.agent.ctx/name`.

## Production source terminology inventory

The following active files contain architectural `tile` language even where no
public identifier changes. Their docstrings, comments, agent-facing strings, and
example function names should be converted according to the semantic map rather
than by blind replacement:

- `src/my/ui.cljs`
- `src/my/plan.cljs` and `src/my/plan/internal.cljs` after the current shared lane
  is clean
- `src/seon/agent.cljs`
- `src/seon/agent/ctx.cljs`
- `src/seon/agent/ctx/canvas.cljs`
- `src/seon/agent/ctx/namespaces.cljs`
- `src/seon/agent/ctx/render_fns.cljs`
- `src/seon/agent/ctx/transcript.cljs`
- `src/seon/agent/ctx/typeahead_steps.cljs`
- `src/seon/agent/message.cljs`
- `src/seon/ai/typeahead.cljs`
- `src/seon/error.cljs`
- `src/seon/eval.cljs`
- `src/seon/handlers/eval.cljs`
- `src/seon/render.cljs`
- `src/seon/render/canvas.cljs`
- `src/seon/render/chat.cljs`
- `src/seon/render/default.cljs`
- `src/seon/render/sci.cljs`
- `src/seon/render/system.cljs`
- `src/seon/test/runner.cljs`
- `src/seon/ui/agent_view.cljs`
- `src/seon/ui/markdown.cljs`
- `src/seon/warn.cljs`
- `src/seon/web/datastar.cljs`
- `src/seon/web/reactive/call.cljs`
- `src/seon/web/reactive/transform.cljs`

The most important distinction while editing those files is:

- “tile function” → **surface renderer**, unless it is specifically the function
  wired to `:seon.render.canvas/content`, in which case **canvas renderer** is clearer.
- “tile twin” → **HTML surface** or **HTML twin**.
- “supporting tile” → **supporting surface**; its visual wrapper is a **card**.
- “error tile” → **error card** when referring to visual hiccup, or **render error
  surface** when referring to placement/behavior.
- “agent tile” → **canvas** for the focal area, **agent card** for a roster item.

Do not rename `src/seon/diffusion/scaffold.cljs` or its tests where spans “tile the
frame”; that is a mathematical verb. Likewise, `volatile` and `hostile` contain the
characters `tile` but are unrelated identifiers.

## Dormant context-block deletion inventory

Delete exactly these four display adapter namespaces:

| File/namespace | What it currently does | Why deletion is safe |
|---|---|---|
| `src/seon/agent/ctx/findings.cljs` / `seon.agent.ctx.findings` | Deprecated findings context renderer | Not installed by the active manifests; durable finding facts live elsewhere. |
| `src/seon/agent/ctx/inventory.cljs` / `seon.agent.ctx.inventory` | Deprecated store-inventory context renderer | Not installed; database inventory APIs are independent. |
| `src/seon/agent/ctx/jobs.cljs` / `seon.agent.ctx.jobs` | Deprecated shell-jobs context renderer | Not installed; shell job execution is independent. |
| `src/seon/agent/ctx/testrun.cljs` / `seon.agent.ctx.testrun` | Deprecated test-run context renderer | Not installed; parsed/persisted test-run facts and lifecycle gates are independent. |

Remove their unconditional boot requires from `src/seon/client.cljs`. Remove the
stale `:inventory` documentation routing in the `src/seon/agent/ctx.cljs` namespace
documentation. There is no active config block to migrate: `system.edn`, `acme.edn`,
and the minimal manifests do not install these four blocks.

Delete or trim only their display-adapter tests:

- delete `test/seon/agent/ctx/findings_test.cljs`
- remove `seon.agent.ctx.findings` and `seon.agent.ctx.inventory` requires and their
  block-render sections from `test/seon/ctx_test.cljs`
- remove the `seon.agent.ctx.jobs` require and only the `jobs-block` assertions from
  `test/seon/agent/shell_test.cljs`
- remove the `seon.agent.ctx.testrun` require/helper/block assertions from
  `test/seon/agent/testrun_test.cljs`

### Explicit no-global-deletion boundary

Deleting the context adapters does **not** authorize deleting similarly named facts
or runtime systems. Keep:

- `my.kb.finding` and any other durable finding/knowledge facts
- `seon.db/store-inventory` and general database inventory queries
- `seon.agent.shell` job execution, persisted job facts, and behavioral tests
- `seon.agent.testrun`, its schemas/datoms/parser, lifecycle completion gate, and
  domain tests
- generic “findings” in lint, knowledge, documentation, and research domains

Search and delete by exact namespace/symbol, never by bare words such as `findings`,
`inventory`, `jobs`, or `testrun`.

## Skill-source drift and required convergence

There are three physically separate skill trees:

- `.agents/skills`
- `.claude/skills`
- `seon-skills`

They are not links and are not identical. This is an active reliability bug because
`src/seon/config.cljs` falls back to `.claude/skills`, while Codex development uses
`.agents/skills`, and packaged skills appear under `seon-skills`. An agent and a
developer can therefore be taught different APIs for the same system.

The clearest failure is `.claude/skills/ui-live-tiles/SKILL.md`, which still teaches:

- the deleted `my.tile` namespace
- deleted `:seon.render.live-tile/content`
- deleted live-tile file paths and terminology

`.claude/skills/seon-context-config/SKILL.md` still describes a root `:live-tile`
config block. `.claude/skills/data-oriented-clojure/SKILL.md` still recommends
`my.tile`. The runtime `.claude` browser/Datastar skills also teach deleted world
routes and namespaces.

The cutover should choose one canonical packaged skill source and make the runtime
and developer consumers link to or generate from it mechanically. Do not hand-edit
three authoritative copies. The canonical `ui-canvas` skill must then be rewritten
to teach:

- canvas for the focal area
- surface for renderable context views
- card only for visual faces/classes
- the actual `my.canvas` API from `src/my/canvas.cljs`
- the existing block → dual render → surface path
- the existing Datastar `/call` interaction path and error envelopes

The skill should remain concise. It should not compensate for broken APIs by adding
large prompt instructions; source contracts, Malli errors, function names, and
docstrings should make correct use discoverable.

Never edit `.claude/worktrees/**`; those are other complete worktrees, not canonical
skill sources.

## World and inspector retirement

### World

There is no active production `seon.ui.world`, `/world`, `world-view`, or
`world-layout` source path. The remaining architectural world concept is stale skill
content:

- `.claude/skills/browser-automation/SKILL.md`
- `.claude/skills/datastar-web-ui/SKILL.md`
- `.claude/skills/datastar-web-ui/references/design-principles.md`

Replace those runtime copies through skill-source convergence, not by maintaining
another bespoke patch. Small active prose cleanups are also needed:

- `README.md`: “fresh world” → “fresh cluster/store”
- `CONTRIBUTING.md`: “fresh world” → “fresh cluster/store”
- `docs/seon/concepts/reactive-context.md`: “experience their world” → “experience
  the system/context”
- `docs/seon/components/extra-src.md`: “downstream world dir” → “downstream source
  directory”
- `docker/seon-entrypoint`: “mutable world” → “mutable state/data directory”

Keep unrelated uses:

- WIT's `world` keyword and browser/client runtime build commands
- “hello world” fixtures
- ordinary “real-world” prose where it is not a Seon architecture noun
- historical docs explaining the old `/world` route or world→session rename

### Inspector

Production already uses `seon.web.debug`; there is no active
`seon.web.inspector` namespace. Rename only the remaining product-brand prose:

- `CONTRIBUTING.md`: “inspector UI” → “web UI”
- `examples/third-party-override/README.md`: “inspector” → “web UI”
- `acme/branding/acme.css`: inspector branding → web UI branding when the ACME lane
  is available

Keep Node Inspector/CDP terminology, `inspect.ai`, historical issue/PRD names, and
any namespace whose actual name is `inspect` rather than obsolete UI branding.

## Active config inventory

| File | Required action |
|---|---|
| `config/system.edn` | Change comments that list `tile`/“live plan tile” to canvas/surface. It already requires `[my.canvas :as canvas]`. |
| `config/acme.edn` | Change `steps-tile-html` to `steps-surface-html` and update comments after coordinating with the ACME lane. It already requires `my.canvas`. |
| `config/legacy.edn` | The broader runtime-reliability work plans to remove this legacy config. Delete it rather than spending effort preserving stale vocabulary. |
| `config/minimal.edn` and minimal variants | No tile API wiring to migrate; retain their current block render declarations. |

Config continues to write desired database facts at startup, and the database remains
the source from which context and surfaces render. This vocabulary cutover does not
add config-only presentation state.

## Active documentation inventory

### Architecture documents requiring semantic rewrite

- `docs/seon/architecture/architecture.md`: glossary, block/render model, DOM ids,
  surface rail, and canvas definition
- `docs/seon/architecture/ui.md`: end-to-end block/render/surface model, slots,
  last-updated surface, errors, override seams, and route/view composition
- `docs/seon/architecture/context.md`: dual-render twins, surface derivation, canvas
  default, and debug-view relationship
- `docs/seon/architecture/data-model.md`: render attributes, DOM naming, warning/error
  projections, and links to the UI vocabulary
- `docs/seon/architecture/toolkit.md`: actual `my.canvas` API and interaction path,
  not the obsolete imagined API
- `docs/seon/architecture/agent-runtime.md`: human-facing status surface terminology
- `docs/seon/architecture/laws.md`: canvas-first/derived-surface terminology
- `docs/seon/architecture/library-grounding.md`: active Datastar/SCI comparison language

### Current root/component/runbook documents

- `AGENTS.md`
- `CLAUDE.md`
- `README.md`
- `CONTRIBUTING.md`
- `src/my/CLAUDE.md`
- `src/seon/render/CLAUDE.md`
- `docs/seon/components/agent-content-css.md`
- `docs/seon/components/acme-harness.md`
- `docs/seon/components/extra-src.md`
- `docs/seon/components/renderer.md` where describing current behavior
- `docs/seon/concepts/reactive-context.md`
- `docs/prds/runtime-reliability/roadmap.md`
- `examples/third-party-override/README.md`

Historical filenames such as `tile-isolation-prd-2026-06-21.md` remain valid links.
Do not rename a referenced historical file just to remove the word from a path.

### Historical no-touch boundary

Do not mass-rewrite:

- `docs/**/research/**` other than this new current audit
- `docs/**/archive/**`
- completed/old PRD work records
- `evals/runs/**`, snapshots, prompt/eval blobs, or saved forensic transcripts
- `reference-code/**`
- generated `out*/`, `data/**`, or `logs/**`

The acceptance search must be scoped to current truth, not aimed at making a raw
repository-wide grep return zero.

## Active test inventory

### Behavioral tests that move with renamed contracts

- `test/my/canvas_test.cljs`
- `test/my/ui_test.cljs`
- `test/seon/agent/ctx/render_fns_test.cljs`
- `test/seon/agent/ctx/typeahead_steps_test.cljs`
- `test/seon/ctx_test.cljs`
- `test/seon/render/canvas_test.cljs`
- `test/seon/render/sci_unspecced_helper_test.cljs`
- `test/seon/render_test.cljs`
- `test/seon/render/block_test.cljs`
- `test/seon/ui/agent_view_test.cljs`
- `test/seon/ui/html_test.cljc`
- `test/seon/warn_test.cljs`
- `test/seon/web/datastar_test.cljs`

### Fixtures/comments that should use current vocabulary without changing behavior

- `test/my/plan_test.cljs` after the shared lane is clean
- `test/seon/agent_lifecycle_test.cljs`
- `test/seon/ai/typeahead_test.cljs`
- `test/seon/error_record_test.cljs`
- `test/seon/eval/repair_batch_test.cljs`
- `test/seon/instrument_smoke_test.cljs`
- `test/seon/render/chat_test.cljs`
- `test/seon/repair_test.cljc`
- `test/seon/repl/internal_test.cljc`
- `test/seon/web/reactive/call_test.cljs`
- `test/seon/web/reactive/transform_test.cljs`

The repair/REPL fixtures currently define names such as `my-start-screen-tile` and
describe an old agent-authored tile. They test parser/repair behavior rather than a
legacy compatibility contract, so their example names can become canvas/surface
renderers without text-specific assertions. Preserve the malformed structural shape
that each test is actually exercising.

Keep `test/seon/diffusion/scaffold_test.cljs`'s `spans-tile-the-frame-without-overlap`:
it tests geometric coverage and does not expose the retired UI vocabulary.

No test should assert long prompt/context prose. Prefer behavior: returned schema,
surface omission/presence, focus selection, DOM identity, transaction-triggered
morph, interaction envelope, or self-healing after a renderer is repaired.

## Semantic rename map

| Old phrase/name | New phrase/name |
|---|---|
| tile, when it means a rendered context view | surface |
| tile function | surface renderer |
| canvas tile function | canvas renderer |
| tile twin | HTML twin / HTML surface |
| supporting tile | supporting surface |
| focused tile | focused surface / canvas |
| live tile | live surface, or canvas when focal |
| tile card/container/face | card |
| agent roster tile | agent card |
| last-updated tile | last-updated surface |
| broken tile warning | unresolved canvas warning, when checking the canvas pointer |
| inspector UI | web UI |
| world view | agent view / web UI, according to actual route |

This map is semantic. For example, a context block's HTML projection is a surface,
while the border around it is a card. Replacing both with the same noun would merely
create a new ambiguity.

## Ordered implementation dependency plan

### 1. Freeze the vocabulary in current architecture

Update `architecture.md`, `ui.md`, `context.md`, and `toolkit.md` first in the same
implementation branch. These documents define the reviewable contract: block,
render, surface, canvas, card, slot, view. Correct the stale `my.canvas` API from
source while doing so.

### 2. Rename core contracts in place

Rename `::tile-sym`/`last-updated-tile`, the unresolved-canvas warning, canvas error
card seam, welcome line, and typeahead surface renderer. Update all callers and
schemas atomically. Do not leave forwarding vars or deprecated schemas.

### 3. Rename DOM and CSS as one patch

Change slot/roster DOM ids, card/face selectors, all hiccup producers, and behavioral
tests together. Rebuild `output.css`. This avoids a half-state where HTML and CSS use
different vocabularies.

### 4. Delete the four dormant context adapters

Delete only their namespaces, boot requires, stale docs routing, and display tests.
Re-run exact-reference searches to prove the runtime domain systems remain.

### 5. Converge skill sources and agent-facing docs

Choose one canonical skill tree, remove the stale `ui-live-tiles` skill, and make the
runtime/developer consumers derive from the canonical source. Rewrite `ui-canvas`
and related Datastar/config skills from the now-renamed source APIs. This step comes
after code identifiers so the skills cannot teach an intermediate contract.

### 6. Coordinate and update ACME

After the shared ACME lane is clean, update its CSS classes, error-card override,
surface symbol names, comments, and branding. Do not retain compatibility aliases in
core for one downstream consumer.

### 7. Update remaining active prose and focused tests

Sweep current architecture/components/runbooks/source docstrings/tests using the
scoped searches below. Leave history and unrelated language intact.

### 8. Cold reset and live proof

Perform a full cluster reset so config and generated assets load from a known state.
Then drive real agents through canvas creation, database-backed updates, buttons,
text inputs, focus changes, and debug view. Observe the database, server feed, browser
DOM, and agent feedback rather than inferring success from compilation.

## Behavioral proof plan

### Static and schema proof

- The active CLJS build compiles with no references to retired symbols.
- Malli instrumentation registers and validates `::surface-sym` and the renamed
  public functions.
- No `my.tile`, `seon.render.live-tile`, `:seon.render.live-tile/*`, `/world`, or
  `seon.ui.world` appears in active runtime source/config/skills.
- `:seon.render.canvas/content` remains the only persisted canvas pointer.
- The four deleted context namespaces have no exact remaining require, symbol, or
  config reference.
- `seon.agent.shell`, `seon.agent.testrun`, `seon.db/store-inventory`, and durable
  finding facts still compile and pass their domain tests.

### Database/render proof

With a fresh store:

1. Query an agent with no canvas pin and no authored renderer; the canvas resolves to
   welcome.
2. Define two surface renderers and transact facts read by each. The canvas resolves
   to the most recently touched authored surface through
   `last-updated-surface`.
3. Pin `:seon.render.canvas/content`; the pin wins over recency.
4. Clear the pin; recency resumes without a compensating transaction.
5. Verify a focused surface is absent from the supporting rail while all other
   present HTML surfaces are sorted by latest change.
6. Verify AI-only blocks do not create blank rail cards and HTML-only blocks do not
   add prompt tokens.
7. Break a canvas symbol or renderer. The web UI shows the calm canvas failure and
   the agent receives the derived `:canvas-unresolved`/render feedback.
8. Define or repair the missing renderer. The warning and error surface disappear
   automatically without acknowledgement state.

### Reactive interaction proof

Use one real agent to build a small database-backed UI using `my.canvas`:

- text input bound to a signal
- submit button routed through the existing Datastar `/call` transform
- transaction writes a fully namespaced fact
- response returns the existing capability/result envelope
- the open `/agent/{id}/feed` observes the transaction and morphs the canvas
- the updated value appears without manual refresh or a second client state store
- invalid input and a throwing handler produce visible, structured feedback to the
  agent and do not wedge the SSE stream

Repeat for `button`, `select`, and `toggle`. Confirm rapid presses are serialized or
batched by the existing writer/feed path and do not cause duplicate writes. Verify
the feed server-side with the gzip-capable client because the browser automation
transport cannot own a long-lived SSE stream reliably.

### Browser/layout proof

- `/agents` renders agent cards with `app-agent-<id>-card` identities.
- `/agent/{id}` fills the full primary area without overlapping the hidden live bar.
- the right rail has full height, scrolls independently, and uses readable cards.
- clicking a supporting surface focuses it and removes its duplicate from the rail.
- an updated canvas becomes primary; a latest user/assistant transcript update can
  focus the transcript according to the existing deliberate-update policy.
- main and compact transcript faces start at the latest reply/bottom.
- `/agent/{id}/debug` is lazy: debug-only AI/raw/HTML/token-breakdown work is not
  rendered or streamed when the debug route is not open.

### Performance proof

Profile with a grown transcript and an open SSE feed:

- an unrelated transaction does not re-run every renderer unnecessarily
- unchanged surfaces reuse the existing bounded render cache
- one changed block produces one composed page morph, not a parallel per-tile
  protocol
- no repeated SCI render-budget blowups or large RSS sawtooth occur for unchanged
  content
- compressed bytes are recorded as transport evidence, while all human-visible
  size reporting remains estimated tokens per repository policy

The vocabulary change itself must not create a new cache key, state atom, or
invalidation channel. It should exercise the same database/value-based render cache
and Datastar morph path.

### Focused test commands

Run the smallest relevant CLJS groups while iterating, then the active pod suite at
the integration boundary. The exact file groups are the test inventory above; use
`bin/test-cljs`'s current namespace/file-selection options rather than running every
paused JVM test. Build CSS before browser proof. Finally cold-reset the cluster with
the canonical startup command from the updated runtime-reliability runbook.

## Scoped acceptance searches

These are review aids, not substitutes for behavior:

```bash
rg -n 'last-updated-tile|tile-sym|check-tile-unresolved|:tile-unresolved|error-tile|default-error-tile|steps-tile-html' \
  src config test

rg -n 'seon-tile|tile-hero|id \(str "tile-|app-agent-.*-tile' \
  src resources/public/css/input.css config test acme

rg -n 'my\.tile|render\.live-tile|:seon\.render\.live-tile|/world|seon\.ui\.world|world-view|world-layout' \
  src config .agents/skills .claude/skills seon-skills docs/seon/architecture

rg -n 'seon\.agent\.ctx\.(findings|inventory|jobs|testrun)' \
  src config test
```

Expected exceptions should be explicit and reviewed, not hidden with a repository-
wide exclusion. Known legitimate exceptions include historical `tile-isolation`
document links, geometric “tile the frame” tests, WIT `world`, Node Inspector/CDP,
`inspect.ai`, ordinary “real-world” prose, `volatile`, and preserved historical
artifacts.

## Principal risks

- **Skill drift:** fixing source while leaving `.claude/skills` stale will reproduce
  agent failures even with correct runtime code. Convergence is part of the cutover,
  not optional documentation cleanup.
- **Half-renamed CSS:** changing hiccup or selectors alone makes valid surfaces appear
  blank/unbounded. Treat CSS/DOM/producers/generated output as one unit.
- **Semantic over-replacement:** surface and card are not synonyms. A blind rename
  would preserve the ambiguity under a new word.
- **Deleting domain data with adapters:** the four dead context namespaces are thin
  display paths. Their underlying facts and runtime mechanisms remain valuable and
  must be proven independently.
- **Shared-tree collision:** ACME and plan files are currently being changed by
  another agent. Integrate only after those commits land.
- **Text-fragile tests:** do not introduce prompt-copy assertions. Test render
  presence, schema, database derivation, DOM identity, error recovery, and live
  morph behavior.

## Definition of done

The cutover is complete when current source, config, active architecture, active
skills, tests, and ACME all use one coherent vocabulary; the four dead context
adapters are gone; `my.canvas` is the only canvas/control API; the database remains
the source of truth; agent views react through the one existing Datastar path; and a
cold-reset real-agent interaction proves inputs, buttons, transactions, feedback,
focus, omission, and live updates without legacy aliases or parallel systems.

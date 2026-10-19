---
type: research
status: draft
created: 2026-10-13
tags: [research, ui, rendering]
---

# UI renders, actions and layout: inventory and smallest path

Research only, 2026-10-13. Tag key: [V] = verified in source, schema, or git (symbol, path#heading, or commit sha quoted); [I] = inference from the evidence. Counts come from a parser run on `resources/seon/schemas/*.edn` (script deleted after use).

## 1. Render-pair inventory

Counts, approximate [I]: the parser attributes each render key to the nearest preceding `:name [` line, so a nested keyword can be mis-attributed. Treat the per-schema attribution as ±a few; the totals are the reliable part.

| Face | Occurrences in `resources/seon/schemas/` [V] | Schema entries (parser) [I] |
|---|---|---|
| `:seon.render/ai` | 107 (across schemas and src) | 103 |
| `:seon.render/html` | 94 (across schemas and src) | 91 |
| `:seon.render/form` | 25 (schemas) | 25 |

- **AI renders without a schema HTML face: 12 entries [I].** `seon.agent.edn` `finish?` and `protocol-namespaces` (AI + form), `seon.test.run.edn` `destination` (AI + form), `seon.render.edn` `form` and `rendered`, `seon.context.edn` `unread`, `seon.db.edn` `connection-id`, `seon.dev.mcp.edn` `projection-offending-class`, `seon.error.edn` `occurrence`, `seon.print.edn` `raw?`, `seon.test.edn` `seon.test/pending`, `my.program.edn` (top level). Several of these are attributes or nested keys, not schemas.
- **HTML-only entries: 0 [I].**
- **Shared renderer functions carry most of the pairs [V].** `seon.error/render-ai` (41 refs) and `seon.error/render-html` (42 refs) cover the error family, so error coverage is broad at the function level.
- **Render functions that exist only as an AI face [V].** These AI renderers have no HTML twin defined anywhere in `src/`:
  - `seon.render.test/render-run-result-ai` (schema `seon.test.edn`)
  - `seon.render.ns/agent-namespace-ai` (schema `seon.agent.edn`)
  - `seon.render.value/render-database-identity-ai` (schema `seon.db.edn`)
  - `my.program/render-source-ai`, `my.program/render-breakage-ai` (schemas `my.program.edn`, `seon.program.edn`)
  - `seon.context/current-state-ai` (schema `seon.context.edn`)
  - Prose helpers `seon.error/ai-prose`, `seon.error/mcp-prose`, `seon.error/elision-prose`, `seon.error/finding-ai` (AI-only by name; not schema faces)
  - `seon.print/render-elision-ai`, `seon.render.ns/render-ai` pairs exist for some but not all siblings
- **Not AI-only, corrected [V].** `seon.render.transcript/render-history-ai` has an HTML twin, `render-history-html` (`src/seon/render/transcript.clj`), wired from `resources/seon/schemas/seon.turn.edn`. The parser's AI-without-HTML list does not include it.
- **Form faces [V].** The 25 `:seon.render/form` entries are the "what to run to find this again" data (for example `seon.render.ns/task-form`, `seon.error/render-form`). They are shown as data beside values and are never executed by the renderer, per CLAUDE.md "Presentation".

**Count of missing HTML faces:** 12 schema entries [I], plus the 7 AI-only function names above [V]. The owner's goal "every render function has an HTML version" is not met today. A cheap uniform fallback is a schema-level HTML face that is the value printer's HTML face of the AI data, which CLAUDE.md already describes as the render-failure fallback [V: CLAUDE.md, "A render that fails falls back to the value itself, printed"].

## 2. Actions as function calls: what exists today

**Operations are data, not controls [V].** `:seon.render/operations` is attached as forms by many producers, for example `src/my/root.clj` (`my.root/merge-requests`), `src/seon/render/var.clj:147` (`my.test/check`), `src/seon/cluster/message.clj:638` (`my.message/received!`), `src/seon/ref.clj:177,284` (`doc`, `my.program/callers`). The contract says these are forms "to evaluate … send one to run it" (`src/seon/bootstrap.clj:68`).

**The HTML face does not turn operations into controls [V].** The one place an operation reaches HTML is `related-html` in `src/seon/render/walk.clj`, which shows the first operation through `render.value/html` as a value, not as a button. So the offered call is displayed, never clickable.

**The only clickable action today is a hard-coded `received` [V].**
- `received-button` (`src/seon/cluster/message.clj`, section "render-inbox-html") is a `<form>` with `data-on:submit` posting `@post('…/agent/{id}/context', {contentType:'form'})` with hidden `action=received` and `message-id`.
- Route `::route/agent-context` (`src/seon/render/route.clj`, POST `/agent/{id}/context`, same-origin middleware) goes to `context-response` (`src/seon/render/web.clj`). Its `case` admits `"create-owner"` and `"received"` and answers `not-found` for everything else.
- `received-response` calls `message/received!` directly. There is no SCI resolve, no eval request, and no form read from the posted text.
- The human's message bar is a separate form that posts to `::route/agent-message` (`message-bar-html`, `src/seon/render/web.clj`), which commits an inbound message, not an evaluation.

**The old action door: a fn call resolved against the agent's own fns [V, historical].** The pre-split pod had a working design, now deleted:
- `.claude/skills/ui-live-tiles/SKILL.md` (at `f23a2aa4b^`), section "Interactive controls — `my.tile` buttons/forms call YOUR fns back": a control's action is a fn the agent defined in its home ns, passed as a fn-CALL `(list 'approve! order-id)` (args captured at render time) or fn-REF `'save-note!` (the posted signals arrive as one map). Helpers `my.tile/button|input|select|toggle|form`.
- `src/seon/web/reactive/transform.cljs` (pre-split, `f25e34594^`), ns docstring: a render-time postwalk rewrites agent hiccup `:on-click` into `@post('/agent/<id>/call?fn=…&args=…')`. "The capability gate proves the route agent is live and the function is an agent-authored fact."
- `src/seon/web/router.cljs` (pre-split): `/agent/{id}/call` is seeded as a route datom and resolves its handler symbol late through `seon.web.reactive.call`.
- Commits: `28090751b` (2026-06-28, "backbone exists" research, names the gap "render.cljs:678 gates the transform on a symbol → literal-hiccup tiles get dead buttons"); `60777866c` (2026-07-17, "Unify the agent action door", the gate moved to one door); `f25e34594` (2026-07-27, "R0: the tree split"), which removed the pod. The owner's pointer `4a309f5ff` (2026-07-02, "canvas = last-updated tile") is the canvas commit, not the deletion commit [V].
- Attribution in that design: the call runs as the rendering agent (`/agent/<id>/call` names the agent whose fn it is), not the viewer [V].

**Current route table has no call route [V].** `src/seon/render/route.clj` lists `/agent/{id}/message` and `/agent/{id}/context` only. `src/seon/render/hiccup.clj:177` contains `{:data-on-click "@post('/call')"}` only inside a generator used by a property test, not a live route [V].

**Doc claim not supported [V].** `docs/seon/architecture/ui.md`, section "4. Controls and proof", says "Debug controls submit through the ordinary turn/evaluation owner with the explicit checked-out branch and the same serial permit." The grep for submit or post controls in `src/seon/render/` found only the message bar, `received-button`, and the create-owner form (`create-owner-form`, `src/seon/render/web.clj`). No debug control posts an evaluation. Treat that section as target text, not current behavior [I].

## 3. Layout and composition

**A page is a flat ranked list of units [V].** `page-result` (`src/seon/render/web.clj`, section `page-result`) derives `render.walk/neighborhood`, assigns each unit a rank by its index, and serializes each with `surface-html`, which sets `style="order:<rank>"` and `data-rank`. There are no named slots, no canvas, no pin, and no attribution label.

**Nested renders are explicit calls [V].** `related-html` and `with-related` call `render.value/html` on each piece, and CLAUDE.md states the rule: "A render that wants a nested thing rendered calls that render explicitly." So composition is possible without a new mechanism. The page composes units by ranking them, not by embedding.

**Viewer versus viewed agent [V, partial].** `seon.render.web/feed-response` and the page derivation (around `src/seon/render/web.clj`, `:seon.render.web/root-agent-id`) carry the service's own agent id beside the path's `:seon.agent/id`. [I] That is the pattern for "user agent versus viewed agent", but nothing labels which unit belongs to which agent, so the chat-window attribution the owner wants is absent.

**Prior canvas design [V, historical].**
- `4a309f5ff` (2026-07-02, "feat(render): canvas = last-updated tile — derived default, pin to override"): canvas = pin (`:seon.render.live-tile/content`) → derived (`seon.agent.ctx.render-fns/last-updated-tile`, the agent's own authored tile fn with the latest touch tx) → welcome. Pin is one attribute; `[:db/retract … :seon.render.live-tile/content]` returns to derived.
- The deleted skill: "the canvas is the focal tile at the top … below it sits the transcript", with provenance named in the agent's context.
- Current tree: `live-tile`, `my.tile`, `my.ui` have zero hits in `src/`, `resources/`, `test/` [V]. The canvas design is fully gone; the derived-tile idea survives only in history.

## 4. Smallest path

**(a) HTML face for every render [I, recommendation].** Add a schema-level default: where a schema has an AI face and no HTML face, its HTML face is `render.value/html` over the AI data. This is one line in the walk's pair selection, not twelve bespoke renderers. It keeps the single value printer (CLAUDE.md "There is exactly ONE printer"). The seven AI-only functions above then get real HTML only where they carry structure; the rest inherit the default. Proof: a table test that every schema with `:seon.render/ai` resolves an HTML face, red today on the 12 entries.

**(b) Operations clickable, evaluated as the user's agent [I].**
- Render an operation as a form in HTML whose body carries the offered form's digest (not its text), posting to a route under `/agent/{id}` (the existing `::route/agent-context` `case` is the natural extension point).
- The server resolves the digest to the stored form the render offered, then sends it through the one evaluation path (read → evaluate → record → print) as an eval request of the viewer's agent, on its checked-out branch.
- Guard: none. CLAUDE.md forbids a fence. The digest check is the resolution itself (an unknown digest is "not found"), not an allowlist beside a mechanism. Pass the viewer's identity as the evaluation's agent binding.
- Result: shown as a new surface beneath the operation, attributed to the user agent by its label.
- Cost: the one eval request already reads and records; no new executor.

**(c) Layout render with user-agent composition and canvas = most recent surface [I].**
- A user-agent layout render calls each viewed-agent render explicitly (`render.value/html` per entity), so attribution is the render's own data, not a post-hoc label. Each unit carries its agent id; the layout labels it.
- Canvas: the most recent HTML surface, derived by max `t` over surfaces the user agent's evaluations produced (derived, not stored, per CLAUDE.md "Derive work and completion"). Pin is an optional override of one ref, as in `4a309f5ff`. This reuses the old canvas rule with the pin kept as one attribute.
- Replace the flat `order:<rank>` in `page-result` with slots: canvas first, then the transcript in agent order.

## 5. Top findings

1. Only one HTML action exists, and it is hard-coded (`received-response`, `message/received!`). There is no generic action path in the current tree.
2. The working action design (fn-call and fn-ref through a gate) existed in the pod and was deleted at `f25e34594`. Its lesson survives: args captured at render time, posted signals as one map, and a rendering-agent attribution.
3. The current web layer already has the composition primitive (explicit `render.value/html` calls) but no slots, canvas, or attribution.
4. HTML coverage: 12 schema entries [I] plus 7 AI-only functions [V] lack an HTML face. A schema-level default closes all of them at once.
5. `ui.md` section 4 claims debug controls submit through the evaluation owner. That is not in the code today [V]; it is target text.
6. The owner's pointer `4a309f5ff` is the canvas commit, not the pod deletion. The deletion was `f25e34594` [V].

## Limits

- Schema attribution is parser-based [I]; the 12-entry list is a lead to verify, not a final count.
- No live UI run; the browser and running server were not exercised.
- No SCI or eval was run on default, per the owner's rule.

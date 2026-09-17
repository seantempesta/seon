---
type: research
status: review
created: 2026-09-17
tags: [render, web, debug, history, prompt, landing-note]
---

# Landing note — the debug session panel is an outline, not a dump

The owner's ask, verbatim (2026-09-16): *"I want the debug interface for an
agent that we have prepped to do one of the tasks and I want to inspect the
generated context myself. If the debug isn't working right then we need to fix
it."* — and, on the wall of text the page served: the session panel should be
an OUTLINE.

## 1. What was read end to end, before any edit

| document / source | why |
|---|---|
| `AGENTS.md` §0–§5, and the vocabulary rows for block, render function, `:seon.render/ai`, `:seon.render/html`, the agent's history, shown text | the lane rules, the one clipping spot, the names |
| [`.claude/skills/datastar-web-ui/SKILL.md`](../../../../.claude/skills/datastar-web-ui/SKILL.md) (all 96 lines) | the web render owners' contract, morph targets, delivery |
| [`composable-history-2026-09-17.md`](composable-history-2026-09-17.md) (all 506 lines) | how the history is built, unit by unit, and what each unit stores |
| `git show a56739309 9a6bb9f1d --stat` and the S11 landing note | what already landed: `select`/`compose`, the deleted re-fit, the thinking block |
| `src/seon/render/web.clj` — `debug-response`, `debug-turn-response`, `session-controls`, `debug-turn-request`, `history-segments`, `derive-context!` | the route the panel is served on |
| `src/seon/render/transcript.clj` — `turn-rows`, `turn-kind`, `session-origin`, `session-header`, `render-session-loading`, `render-session`, `emission-label`, `identity-attributes` | the owner being accreted |
| `src/seon/cluster/prompt.clj` (385 lines) | `select` / `compose` / `prompt`, and what "the composed prompt" actually is |
| `src/seon/repl.clj` — `entity-emission`, `text`, `thinking-text`, `input-text`, `render-ai`, `render-html`, `live-response` | the declared evaluation pair and its thinking block |
| `src/seon/eval.clj`, `resources/seon/schemas/seon.eval.edn`, `seon.render.history.edn` | what a unit knows about its own origin |
| [turn PRD §13–§15](../../context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md) | the evaluation entity, shown text, the history |

## 2. What was wrong

`/agent/<id>/debug?prompt=true` deferred to `transcript/render-session` with
`::raw? true`, whose whole body is

```clojure
[:pre {:class "seon-session-raw" :data-prompt-bytes n} [:code prompt]]
```

— one `<pre>` holding every byte of the composed prompt. That is the wall of
text. The non-raw arm was no better as an *outline*: it is a flat list of every
emission in acquisition order, with no turn grouping a reader can collapse, no
per-unit size, and no statement of what produced any given unit.

Nothing was broken in the sense of refusing; the panel was serving the right
bytes in a shape nobody can read.

## 3. The change

One new render function, `seon.render.transcript/render-outline`, plus its
route. No second renderer and no second assembly: every byte and every number
comes from the same `render/acquire-context!` the prompt composes.

| path | change |
|---|---|
| `src/seon/render/transcript.clj` | NEW `render-outline` (public) and private `outline-url`, `outline-signal`, `outline-turn-kind`, `outline-calibration`, `outline-origins`, `outline-renderer`, `outline-unit`, `outline-everything`. `render-session-loading` defers to the outline url when the request carries `::outline?` |
| `src/seon/render/web.clj` | NEW private `debug-outline-response`; one `debug-response` branch for the Datastar `outline=true` fetch; `?prompt=true` now marks the deferred request `::transcript/outline?` |
| `resources/public/css/input.css` + `output.css` | the outline's own rules (collapsed rows, the AI toggle, the wrapped `<pre>`) |
| `test/seon/render/web_debug_test.clj` | three regressions and one fixture |

### 3.1 The four things the owner asked for

1. **One line per turn, collapsed, with its kind and its size.** A `<details>`
   per turn carrying `data-turn-kind` and `data-turn-id`. The kind DERIVES —
   `turn-kind` reads the turn's own attempts, situation and reply, and
   `outline-turn-kind` names the agent's first system turn its *Opening*, the
   same rule `session-origin` already uses for a unit. There is no kind stamp.
2. **One line per unit, collapsed.** A nested `<details>` per evaluation
   carrying `data-evaluation-id` and `data-unit-origin`, whose summary names
   the ordinal, the origin (`opening` / `re-read` / `agent` / `error`, from
   `session-origin`), the entity whose render declared it
   (`:seon.eval/origin`, resolved through the identity attributes actually
   installed on the database — never a naming convention), the schema title and
   renderer (`emission-label` + `:seon.eval/renderer`), and its tokens.
3. **Expanded: the HTML render by default, one toggle to the exact AI bytes.**
   The HTML comes from `render/render-call` with `:seon.render/html`, which
   SELECTS the evaluation schema's declared pair — the same selection the agent
   page makes, so the `;;` comment lands in its own `seon-eval-thinking` block
   that S11 already built. The toggle is one Datastar signal per unit and
   serves `:seon.render.history/bytes` unchanged: the bytes acquisition
   composed, which are the stored shown text inside the REPL grammar.
4. **"Show everything".** One `<details>` at the foot holding
   `seon.cluster.prompt/prompt`'s own `:seon.cluster.prompt/text` — `compose`
   over the selection, then the turn frame. Not a re-join.

### 3.2 Three properties worth naming

- **No presentation clipping anywhere on this path** (AGENTS §2.4). Each unit's
  shown text was bounded once, at evaluation time, by the value renderer; the
  outline re-fits nothing and the composed prompt is shown whole.
- **Every failure state is typed.** A refused `turn-rows`, a refused
  acquisition, a refused per-unit render and a refused prompt each render
  through `seon.error/render-html`. The only "Loading…" left is the first-paint
  placeholder that the Datastar fetch replaces, and it is replaced by the
  refusal when there is one.
- **Sizes are estimated tokens, not character counts** (AGENTS §2.4), priced
  under the agent's own calibration (`prompt/agent-calibration` on the newest
  attempt's model, the measured shipped prior when nothing has settled), so a
  unit's number and its turn's number are the same currency the prompt budget
  spends.

## 4. Regressions

`test/seon/render/web_debug_test.clj`, over a fixture whose turns are opened by
`seon.turn/open-tx` — a hand-written turn map carries no `:seon.runtime/turns`
edge, so the history query finds nothing and the panel would read that absence
as an agent with no history. (That is the recurring failure class AGENTS names;
it is what the first draft of this fixture did, and the outline correctly said
"No turns recorded.")

| test | what it proves |
|---|---|
| `the-debug-outline-is-one-line-per-turn-and-one-line-per-unit` | two collapsed turn lines with derived kinds `["Opening" "Provider"]` and a token estimate each; two collapsed unit lines with ordinals, origins `["opening" "agent"]`, the declaring entity, the renderer and tokens; exactly ONE composed prompt on the page |
| `the-outline-toggle-serves-the-exact-stored-shown-text` | the AI pane's text equals `acquire-context!`'s `:seon.render.history/bytes`, element for element, and equals `seon.repl/render-ai` of the same saved rows — no second renderer; the comment is one thinking block |
| `the-outlines-show-everything-is-the-composed-prompt` | the raw pane equals `prompt/prompt`'s text and starts with `prompt/compose` of `prompt/select` over the same units |

## 5. Verification boundary — read this before trusting anything above

**Iteration, not the cold gate.** Everything below is
`bin/test-fast --paths src/seon/render/transcript.clj src/seon/render/web.clj
test/seon/render/web_debug_test.clj -- seon.render.web-debug-test`. That shares
the worker's contract arming but not its isolation, retained run roots,
platform tier, or recorded result facts. The cold
`bin/test --paths … -- seon.render.web-debug-test seon.render.web-test
seon.render.transcript-test` plus `bin/test --platform` HAS NOT RUN: three test
slots were held continuously by other lanes' gates for the whole session (six
to eight concurrent `bin/test-fast` JVMs; load average 27 at its peak), and each
of my own invocations waited 6–15 minutes for a slot.

Final iteration, `seon.render.web-debug-test`, 16 tests / 237 assertions:

- **The three new regressions pass.**
- **One red, foreign:** `saved-history-preserves-shown-text-with-numeric-lookups`
  (`web_debug_test.clj:396`) fails with `:seon.render/capture-mismatch` from
  `seon.render/captured-history`. That is the open S11 issue
  [`a-selected-prompt-no-longer-reconstructs-from-the-full-join`](../../../seon/issues/a-selected-prompt-no-longer-reconstructs-from-the-full-join.md),
  whose owners are `src/seon/render.clj` and `src/seon/cluster/prompt.clj` —
  neither touched by this lane. It was green in the previous iteration of the
  same tree and red in this one, so it is also intermittent.

### The live proof on `default` did NOT complete, and why

The route is live and correct on `default` (pid 33583, never restarted):

- `GET /agent/juniper/debug?prompt=true` → 200, and the session panel now defers
  to `data-init="@get('/agent/juniper/debug?outline=true')"` —
  [`live-session-page-2026-09-17.html`](debug-outline/live-session-page-2026-09-17.html).
- `GET /agent/juniper/debug?outline=true` with `datastar-request: true` → 200,
  3,480 bytes — [`live-outline-refusal-2026-09-17.html`](debug-outline/live-outline-refusal-2026-09-17.html).

**But it renders a refusal, not an outline**, because the `default` cluster's
effective configuration has lost every required fact. Filed as
[`the-default-clusters-effective-configuration-lost-every-required-fact`](../../../seon/issues/the-default-clusters-effective-configuration-lost-every-required-fact.md).
Every render path that needs a profile refuses, and
`mcp__seon__eval_clj` answers that same config error for every form in 1–3 ms
without evaluating. **No screenshot of a populated outline exists**; the saved
HTML is what the live page actually serves right now.

One thing that response DOES prove, and it is part of the ask: the panel's
failure state is typed. The body carries the full `seon.eval/of-agent`
contract refusal with its expected shape and offending value — never
"Loading…".

Three `bin/seon init --dev default --changed …` attempts all failed (a 180 s
`:seon.operator/lock-hold-timeout` during development program reconciliation,
then two "source changed while … was being analyzed" refusals under concurrent
lane edits), so the code proven live is a **hot-reloaded Var**
(`(require 'seon.render.transcript 'seon.render.web :reload)` through the JVM
prepl), not a publication adoption. The new namespaces' contracts are therefore
unarmed in that JVM.

### What still owes a proof

1. The cold gate: `bin/test --paths src/seon/render/transcript.clj
   src/seon/render/web.clj test/seon/render/web_debug_test.clj --
   seon.render.web-debug-test seon.render.web-test seon.render.transcript-test`,
   plus `bin/test --platform`.
2. A screenshot of the populated outline — collapsed, one unit expanded, the
   toggle in both states — once `default` has a working configuration.

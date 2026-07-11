---
type: research
status: active
tags: [research, agent, context]
---

# Context audit: is model "thinking" stored in the agent's context?

## TL;DR

No. Model thinking/reasoning is **dropped at the adapter parse boundary** before it can be captured — the Anthropic adapter joins only `"text"` content blocks and skips `"thinking"` blocks (`src/seon/ai/anthropic.cljs:186-193`), and openai-compat reads `reasoning_content` only to build a diagnostic when visible content is empty (`src/seon/ai/openai_compat.cljs:239,247-258`). It cannot accumulate even if it were captured, because the agent context is **not an accumulating message array** — it is re-derived from the DB every turn by `seon.ctx/assemble-context`, and every turn sends ONE freshly-built user message. There is no context-explosion risk from thinking; the genuine growth vector is the `:namespaces` section (the agent's own code corpus), which has no total-section budget.

## How the agent context is built

The context is **stateless and recomputed every turn**. There is no accumulated API message array. `seon.ctx/assemble-context` (`src/seon/ctx.cljs:2272`) merges the 12 core-default sections (`core-default-ctx`, `ctx.cljs:2081-2104`) with the agent's own `:seon.agent/ctx` sections by a single priority sort, pulls the agent entity ONCE (without the session log), renders each section via its `:seon.render/ai` fn against the live db value, and returns one joined string plus split stable/volatile halves (`:seon.render/text`, `:seon.render/stable-text`, `:seon.render/volatile-text`, `:seon.render/sections`, `:seon.render/section-texts`, `:seon.render/token-estimate`).

Nearly every section is a DB-derived projection queried at render time (reactive-context); only `:system` is static text. Core-default sections, in priority order:

- `system` (10) — static universal concept paragraphs; byte-identical every turn/agent
- `namespaces` (20) — THE BODY: one `<namespace>` block per included ns (`seon.*` + `my.*` minus `*.internal`), DB-derived from indexed code
- `your-entity` (30) — the agent's own entity, pulled once
- `live-tile` (35) — what the human currently sees; reactive
- `warnings` (40) — current problems; vanishes when fixed
- `open-todos` (45) — open work items; blank when idle
- `findings` (48) — stored user-domain rows IN FULL; vanishes when store empty
- `transcript` (50) — interleaved messages + evals as one REPL stream
- `turns` (90) — turn-budget countdown; vanishes when idle
- `findings-pointer` (95) — relevance pointer; vanishes when idle
- `inventory` (97) — one line per stored kind with live row counts
- `prompt` (99) — status line + REPL prompt tail; volatile

The "conversation history" surfaces as the `transcript` section (`ctx.cljs:1720`), which interleaves the agent's stored messages (`messages`, `ctx.cljs:565`) and evals (`session-evals`, `ctx.cljs:635`) chronologically. It renders ONLY visible assistant text plus eval source/output/result — `format-eval-row` (`ctx.cljs:488-498`) and `format-message-row` (`ctx.cljs:385-386`) destructure no thinking/reasoning field.

Call path: `run-turn!` (`agent.cljs:1171`) → `render-prompt` → `assemble-context` → single string → `(llm-fn prompt-text)` (`agent.cljs:1088-1089`) → the Anthropic adapter builds `:messages [{:role "user" :content ctx}]` (`anthropic.cljs:163`). One user message, rebuilt fresh each turn. `current-llm-fn` is itself rebuilt fresh per call (`client.cljs:1511-1532`).

## The thinking / reasoning lifecycle

### Request

Thinking is **OFF by default** for every provider. The `:seon.ai/config` row's `::thinking` is a control string parsed by `seon.ai/thinking-mode` (`ai.cljs:298-308`); absent or `"false"` → `false`.

- Anthropic: truthy config → `(assoc :thinking {:type "adaptive"})` inside a `cond->`; falsy omits the key entirely (`anthropic.cljs:164-167`).
- openai-compat: `:deepseek` always sends an explicit `enabled`/`disabled` toggle (default disabled); `:openai-compat` sends the field only when truthy (`openai_compat.cljs:203-205`).

### Parse — where thinking is dropped

- Anthropic: `text-of-blocks` filters `:content` to `(filter #(= "text" (:type %)))` and joins only those; thinking blocks are explicitly skipped (`anthropic.cljs:186-193`). `tool_use` goes to `:seon.ai/tool-calls`. `known-message-keys` includes `:content` (`anthropic.cljs:205-206`), so `extras` (`(apply dissoc body known-message-keys)`, `anthropic.cljs:224`) cannot leak thinking-inside-content into `:seon.ai/provider-fields`. The returned map carries no thinking.
- openai-compat: `reasoning_content` is read into a local binding (`openai_compat.cljs:239`) used ONLY for a `js/console.debug` diagnostic when visible content is empty (`openai_compat.cljs:247-253`); the returned `cond->` map never includes it (`openai_compat.cljs:254-258`).

The empty-turn guard exists precisely because thinking-mode completions can return EMPTY visible content with all tokens in the reasoning field (`agent.cljs:1336-1353`) — confirming reasoning is dropped and the visible text can be empty.

### Persist — or not

The agent loop is **text-extraction, not native tool_use**: `reply-text = (or (:text resp) "")` (`agent.cljs:1026`), then `(repl/parse-forms reply-text)` (`agent.cljs:1032`) → `eval-batch!`. A grep for `tool-calls`/`tool_use`/`tool_calls` over `agent.cljs` is empty — the loop never round-trips native tool_use blocks, so there is no Anthropic interleaved-thinking constraint requiring thinking blocks to be preserved across an assistant turn (no 400 risk).

The persisted assistant self-message stores only `reply-text` as `:seon.agent.message/content`, and stores nothing when blank (`agent.cljs:1056-1063`). Per-turn telemetry `:seon.agent.turn/llm-usage` (pr-str usage) and `:seon.agent.turn/llm-meta` (pr-str provider-fields, which exclude content/thinking) are stored as opaque strings (`agent.cljs:1123-1146`) and are **never read into any ctx section**. The only thinking-related registered attr anywhere is the config control string `:seon.ai/thinking [:string {:min 1}]` (`ai.cljs:125`).

### Replay — or not

The transcript section reads only message content + eval rows; `llm-meta`/`llm-usage`/`debug-dir` appear in no ctx/render/web reader. The debug-capture blob (`debug.cljs:172-195`) writes the raw response to disk but is never read back into context. **Thinking is not captured, not stored, never replayed.**

## What ACTUALLY grows the context (ranked, with live numbers)

Measured read-only against the live pod (HTTP 7890, agent `AGTcapture0001`, eid 94). Live total: **4,302 tokens / 17,211 chars**. Live section breakdown (chars):

| Section | Live chars | Growth axis |
| --- | --- | --- |
| findings | 8,706 | grows with stored user-domain rows (rendered IN FULL) |
| system | 5,494 | static, bounded |
| live-tile | 941 | bounded |
| inventory | 881 | grows with distinct stored kinds (one line/attr) |
| your-entity | 675 | bounded |
| transcript | 215 | windowed — hard cap 24,000 chars |
| prompt | 164 | bounded |

Ranked real growth vectors:

1. **`:namespaces` — the agent's own code corpus. UNBOUNDED.** On a fresh reset this measured ~47,290 tok (~85% of a ~55k total) per [[docs/seon/orchestrator/issues/context-budget-fn-head-lean]]. `namespaces-section` (`ctx.cljs:1101-1199`) emits one block per included ns — current ns FULL source, others compact — joined with NO total-section budget and NO eviction (docstring `ctx.cljs:233`: "no lists, no budgets"). Grows monotonically with authored code, NOT with turn count. **This was ABSENT in the live store** because the capture-test world has zero program-graph entities installed — so the live 4,302-tok total understates a real working agent.
2. **`findings` (and `inventory`) — core-derived, rendered in full, grow with stored rows.** Largest section in the live store (8,706 chars).
3. **`transcript` — conversation history. WINDOWED, not unbounded.** Hard-capped at `transcript-char-budget = 24000` (~6k tok) with newest-first retention / oldest-eval-first eviction; messages always kept but individually capped (`message-render-cap = 4000`), agent eval results capped (`eval-render-cap = 1500`), core evals backstopped (`core-eval-render-cap = 50000`) (`ctx.cljs:1693-1811`). The docstring cites a prior audit where an UNBOUNDED transcript hit 90,468 chars by turn 58 before this cap was added. Live: only 215 chars (3 turns) — the smallest content section.
4. **Agent-authored `:seon.agent/ctx` sections — BOUNDED.** Share `agent-section-char-budget = 8000` (~2k tok); over budget the lowest-priority section truncates with a loud marker (`ctx.cljs:2127-2240`).
5. **Model thinking / LLM message array — ZERO.** Never enters context. Each turn is one freshly-recomputed user message.

Per-turn prompt sizes grew 8,942 → 13,418 → 13,998 chars across the agent's 3 turns — attributable to agent-authored sections under their own budget, NOT transcript accumulation.

## Real context-explosion risks + recommendations

The user's "context explosion" worry is real — but the vector is the code corpus, not thinking and not conversation history.

1. **Add a total-section budget + eviction to `:namespaces`.** This is the one unbounded growth axis (`ctx.cljs:1101-1199`). It dwarfs everything else (~47k of ~55k tok on a fresh reset) and grows with every fn the agent authors. Today only per-member clipping exists; the SUM is uncapped. A recency/relevance-ordered budget mirroring the transcript's eviction model is the natural fix.
2. **Continue fn-head leaning per [[docs/seon/orchestrator/issues/context-budget-fn-head-lean]].** That issue measured the namespaces section as the dominant cost and recommends rendering fn heads (signature + docstring) for non-current namespaces rather than full bodies. This directly attacks vector #1.
3. **Watch `findings`/`inventory` as stored user-domain data grows** — they render IN FULL with no budget. If a working agent accumulates many domain rows, these become the second growth axis. Consider a salience cap analogous to the transcript budget.
4. **No action needed on thinking or transcript** — thinking never enters context, and the transcript is already hard-windowed.

## Caveats / open questions

- **Live sample is minimal.** `AGTcapture0001` has 1 session / 3 turns / 2 evals / 2 messages, so the 24,000-char transcript cap is proven by CODE + live-resolved constant, not exercised near the ceiling. The store has ZERO program-graph entities, so the dominant real-world section (`:namespaces`) was absent — the live 4,302-tok total is NOT representative of a working agent.
- **Adapter-boundary risk (not a current defect).** If a future provider returned thinking text INSIDE a `"text"` block rather than a typed thinking block, `text-of-blocks` would pass it through and it would be stored as message content. Anthropic emits typed thinking blocks today, which are filtered out.
- **Native tool_use is off by default**, and even if enabled the loop parses text forms and ignores `:seon.ai/tool-calls` — so it would still not round-trip thinking today. Turning on real tool-driven execution later would require re-auditing the interleaved-thinking constraint.
- **Provider-side cost, not context explosion.** Anthropic adaptive thinking can bill thinking/output tokens that inflate per-call cost/latency, but only the token-count usage map is stored as a string and never re-fed.
- **Why was `:namespaces` absent for this live agent** (vs the fresh-reset profile)? Confirmed: zero program-graph entities in this capture-test store. A working agent would surface this section as dominant.
- **Token estimates** for the fresh-reset profile are chars/4 from the issue doc; live numbers above are resolved from the running pod.

## Context section breakdown & serving-the-agent analysis

This section answers a second question: not "does thinking explode the context"
(answered above — no), but "what does each section ACTUALLY render, and how well
does the assembled context serve the agent?" All sections are re-derived every
turn by `seon.ctx/assemble-context` (`src/seon/ctx.cljs:2272`), shared by both
the agent-prompt path and the inspector so they cannot diverge.

### The twelve core sections

Declared in `core-default-ctx` (`ctx.cljs:2035`) by ascending priority. Agents
can ADD or OVERRIDE sections via `:seon.agent/ctx` maps (name-collision =
agent wins). Each renders via the single `:seon.render/ai` slot — a string
renders verbatim; a qualified symbol resolves late via `seon.eval/lookup-value`.

| Section (priority) | What it renders (plain language) | Source | Budget | Growth | Appears when |
|---|---|---|---|---|---|
| system (10) | Fixed doctrine: REPL-as-context, eval mechanics (forms + `;;`, no tool calls), errors-are-values, result-vars, the `my.*`/`my.kb.*`/`seon.*` namespace law, STANDING TEACHINGS | static `def` (`ctx.cljs:759`); NO query | none (~5.5k chars) | bounded (edit-only) | always |
| namespaces (20) | The agent's tool catalog: every included ns as `(ns…)` + schemas in FULL + each fn as elided `defn` (head, body→`…`); current ns in FULL source | DB `:seon.ns/:seon.fn/:seon.schema` rows reconstituted (`ctx.cljs:1101`) | no hard cap; body-tiered | grows with authored code | ≥1 program-graph entity exists |
| your-entity (30) | The agent's own entity map (id, state, purpose, ctx, tile wiring) + how to transact to yourself by lookup ref + purpose nudge (only while purpose absent) | once-pulled agent entity (`ctx.cljs:1576`) | none | grows with self-notes | always |
| live-tile (35) | What the human currently SEES on screen — the wired tile value (or the core welcome default) + how to replace it | `render/render-agent-tile` (`ctx.cljs:1502`) | none | bounded (one tile) | always |
| warnings (40) | Current defects clustered by kind: one explanation + one fix example + affected locations | `seon.warn/render-warnings` (`ctx.cljs:1620`) | (in `seon.warn`) | reactive | a warning query returns rows |
| open-todos (45) | The agent's open work items, oldest first; empty = done | agent-scoped todo query (`todo.cljs:244`) | none (one line each) | reactive | ≥1 open todo |
| findings (48) | STORED USER-DOMAIN ROWS IN FULL (`pull [*]`, pretty-printed) — content, not just names; cross-agent | `store-inventory` minus core kinds, string-content kinds only (`findings.cljs:161`) | per-kind cap 20,000 chars (never bites) | grows with stored rows | a non-core string-content kind exists |
| transcript (50) | ONE chronological REPL session: messages + eval rows interleaved, with `(result :id)` handles + timing | messages + session-evals interleaved (`ctx.cljs:1720`) | **24,000 chars; eval-rows evicted oldest-first** | windowed | always (when any history) |
| turns (90) | Turn-budget meter: "turn N of CAP — reply before the cap" | `turns-since-inbound`/`turns-cap` (`turns.cljs:50`) | none (one line) | reactive | mid-task only |
| findings-pointer (95) | Names the stored kinds whose terms overlap the open question; points back at `<findings>` | term-overlap scoring (`findings.cljs:327`) | ≤3 kinds | reactive | mid-task + ≥2 shared distinctive terms |
| inventory (97) | Existence/sparsity map: one line per KIND, each attr NAME + its live ROW count | `store-inventory` (`db.cljs:812`, via `ctx.cljs:1662`) | none (few hundred chars) | grows with distinct attrs | post-bootstrap data exists |
| prompt (99) | Volatile tail: status line (ns · turn · since-user/cap · clock · inbox · agent id) + escalating turn-pressure + the `<ns>=>` prompt line | turn counters (`ctx.cljs:1918`) | none | bounded | always |

### Two corrections

1. **The transcript window is 24,000 chars with eviction — NOT "3 turns."**
   `transcript-char-budget = 24,000` (≈6k tokens), eval-rows evicted oldest-first;
   messages and resume-markers are never evicted (`ctx.cljs:1720`). The "3 turns"
   in measurement B is merely how many turns the tiny test store contains, not a
   window size. In a busy world this section is ~24k chars regardless of turn count.

2. **What `findings` and `inventory` ACTUALLY are.**
   - `findings` renders STORED ROW CONTENT in full (`pull [*]`), not namespaces.
     It keeps every non-core kind that has a live string value and pretty-prints
     every row. Real one-line sample:
     `;; :seon.eval — 2 rows; re-read: (seon.db/query '[:find (pull ?e [*]) :where [?e :seon.eval/source _]])`
     followed by `{:seon.eval/source "(+ 1 2)" :seon.eval/result-edn "3" ...}`.
   - `inventory` renders PER-ATTRIBUTE live ROW counts grouped by kind (the user's
     "datoms per attribute" guess is essentially correct — precisely it counts
     *entities carrying the attr*, equal to datom count for cardinality-one).
     Real one-line sample:
     `seon.agent.turn: at 3 debug-dir 1 evals 2 id 3 messages 2 prompt-chars 3 prompt-file 1 status 3`
     (`status 3` = 3 entities carry `:seon.agent.turn/status`; the namespace is
     stripped off each attr because the line label already names the kind).

### Why the two measurements show different sections (reactive behavior)

Sections are functions of the DB at render time — `assemble-context` drops any
section whose fn returns blank (`(remove (comp str/blank? :seon.render/text))`,
`ctx.cljs:2328`). Same composer, different DB → different sections:

- **Measurement A (fresh-reset, ~55k tok)** has the seeded core program-graph, so
  `namespaces` dominates at 47,290 tok (85%), `warnings` is present (broken-tile),
  and `findings` is ABSENT (no user-domain rows yet).
- **Measurement B (capture-test, 4,302 tok)** has ZERO `:seon.ns/:seon.fn/:seon.schema`
  rows, so `namespaces` VANISHES entirely; `warnings` is absent (clean store); and
  the agent's own operational rows make `findings` the largest section (8,706 chars).

Both are corner cases. A REAL working agent would stack `namespaces` (~47k) +
`findings` (real knowledge) + `transcript` (up to 24k) simultaneously — likely
80k+ tokens, which is unmeasured.

### How well it serves the agent (synthesis of three lenses)

**Top strengths (all three lenses agree):**

- The reactive "section = function of the DB at render time" design means the
  agent never reasons from stale accumulated state. This is the single best
  capability property.
- ONE composer shared by prompt and inspector — the old "three context builders"
  problem (`overlap-three-ai-context.md`) is RESOLVED on the active CLJS track
  (that issue note is stale and cites dead JVM `.clj` paths).
- The volacanvas/cacheable design intent is sound: `prompt` carries every per-turn
  byte (incl. the agent id) so everything above stays cacheable.
- `warnings` + `open-todos` are the cleanest actionability surfaces: clustered
  fix-examples that self-heal, and an explicit done-signal.

**Per-section verdict (consensus):** essential — system, your-entity, live-tile,
warnings, open-todos, transcript, prompt, inventory. Useful — turns, findings-pointer.
**Wasteful / risky / redundant — namespaces (encoding) and findings (scoping).**

**Top problems, ranked:**

1. **`findings` dumps the agent's OWN operational rows, not knowledge** (high; all
   three lenses). `user-domain-kinds` (`findings.cljs:87`) subtracts only CORE
   kinds — never the agent's `seon.agent*`/`seon.eval` operational kinds. With no
   `my.kb.*` data it `pull [*]`-dumps agent/session/turn/eval/message (8,706 chars,
   51% of the prompt), duplicating `transcript` + `your-entity`, with the same 5
   entities recurring ~3x via component-ref expansion. It contradicts its own
   docstring ("stored user-domain rows") and the system prose points the agent at
   this noise as "stored knowledge — consult first."

2. **Fn-head / schema contract is in the prompt TWICE** (high; token-economy).
   `elide-defn-body` (`ctx.cljs:978`) keeps the whole fn head including the inline
   `:malli/schema` attr-map (~30,637 tok of fn-heads), and `schema-full-source`
   (`ctx.cljs:1024`) renders the same registered contract AGAIN as 401 `register!`
   rows (~11,318 tok). Same contract, twice. (Per fn-head-lean issue note.)

3. **Cache split is mis-aligned with where the slow-changing bytes are** (high/medium).
   The stable boundary = sections through `namespaces` (`ctx.cljs:2336`). `findings`
   (8.7k) and `inventory` fall in the VOLATILE tail and are re-sent uncached every
   turn — yet they change only on a user-domain transact, not per turn. The 47k
   `namespaces` is correctly cached; the derived-but-slow content is not.

4. **No question-adjacency pruning of the `namespaces` body** (high/medium). Every
   included ns renders in full every turn regardless of relevance to the open
   question. `findings` has `findings-pointer`; code has no equivalent. namespaces
   is 85% of the fresh budget.

5. **`namespaces` vanishes entirely when the program-graph index is empty** (high;
   capability lens). The agent then has NO visible code surface, yet `system` prose
   still says "THE NAMESPACES BELOW are real loaded code." Doctrine-vs-reality gap.

6. **Triple `store-inventory` / double `user-domain-kinds` per render** (low/medium;
   perf, not prompt bytes). `findings`, `findings-pointer`, `inventory` each
   recompute independently with no memoization across one assemble call.

7. **`live-tile` default-welcome boilerplate** (low). ~600 chars of welcome-card
   prose every turn on any un-customized agent.

**Where the lenses disagree:** the token-economy lens rates `transcript` and
`inventory` as well-spent and focuses the waste on namespaces+findings encoding;
the capability lens additionally flags `namespaces` vanishing as a *blindness*
problem (not just a token problem) and rates `findings` "risky" rather than merely
"redundant" because the system prose actively misdirects the agent to it. The
redundancy lens is harshest on `findings` (calls it "redundant") and uniquely
flags the stale `overlap-three-ai-context.md` issue note. All three converge on
the same two fix targets.

**What's missing (union):**

- A guaranteed core-API/tool surface when the program-graph index is empty (the
  agent cannot discover `seon.db`/`seon.agent`/`seon.agent.todo` from context alone).
- Question-relevant FULL-body code retrieval (today only the current ns shows bodies).
- A per-section token meter shown TO THE AGENT so it can self-prune (the way `turns`
  lets it self-moderate the loop).
- A consolidated registered-schema-shape catalog (the "register once" rule has no
  dedicated discovery surface separate from the namespaces blocks).
- Cross-agent work-in-progress (other agents' todos/activity, not just their data).
- A combined-world measurement (namespaces + real `my.kb.*` findings + transcript
  at once) — both on-record measurements are extremes.
- An explicit de-duplication contract: each entity-kind owned by exactly ONE
  section (eval→transcript, agent→your-entity, knowledge→findings, counts→inventory).

### Recommendations (ranked)

1. **Scope `findings` to genuine knowledge.** In `user-domain-kinds` (`findings.cljs:87`),
   also exclude the agent's own operational kinds (`seon.agent*`, `seon.eval`,
   session/turn/message) — or gate to a `my.kb.*`/`my.*` prefix. Frees ~8k chars,
   makes the section match its docstring, removes the largest duplication, and
   stops the system prose from misdirecting the agent. **Highest signal; cheap.**

2. **Strip the inline `:malli/schema` from the compact fn-head** (`elide-defn-body`,
   `ctx.cljs:978`), letting the `register!` rows be the single contract source.
   Projects `namespaces` 47k → low-30s. Verify afterward that agents still call
   fns correctly without the inline contract (before/after eval-error-rate).

3. **Move `findings` + `inventory` above the stable boundary** (price below
   `namespaces`) so the Anthropic adapter's `cache_control` covers derived-but-slow
   content. Biggest per-turn token saver in data-rich worlds; compounds with #1.

4. **Always render at least the CORE compiled API surface** (signatures + schemas)
   even when the store holds zero program-graph rows, so `namespaces` is never
   empty and the agent is never code-blind. Fix the `system` prose to stop naming
   sections that may be absent (move the "how to read namespaces" note into the
   namespaces-header that only renders with the section).

5. **Add question-adjacency tiering to `namespaces`** (the `findings-pointer`
   pattern for code): full heads for current ns + term-overlapping nss, signature-only
   for the rest, with a pointer to read others on demand. Secondary lever after #2.

6. **Memoize `store-inventory` + `user-domain-kinds` once per render**, threaded
   through the section input map (alongside the once-pulled agent entity). Perf/dedup.

7. **Collapse the `live-tile` default-welcome case to one line.** Saves ~600
   chars/turn on every un-customized agent.

8. **Close out `overlap-three-ai-context.md`** as completed/abandoned for the
   active track (or rescope to the paused JVM track) — it masks the real remaining
   redundancy.

## Duplication & waste — fix plan

Synthesis of three verified investigations (transcript/eval-format,
namespaces/tests, findings/inventory), cross-checked against live source on
2026-06-18. The user's spec: "only metadata + functions + schemas + one usage
test; no raw data unless queried; REPL-style eval results."

### Are-you-sure cross-check (verdicts)

- **findings dumps the agent's OWN operational rows** — CONFIRMED.
  `findings-block` (`src/seon/agent/findings.cljs:133-159`) pulls `[*]` of every
  user-domain kind. `user-domain-kinds` (`:96-98`) removes only
  `db/core-kinds` = kinds whose `:seon.schema/key` row is a BOOTSTRAP row
  (`src/seon/db.cljs:790-810`). `:seon.agent*` / `:seon.eval` are
  runtime-registered, not bootstrap, so they leak and get dumped.
- **Eval forms duplicated across findings + transcript (~4x + 1)** — CONFIRMED.
  `:seon.session/turns`, `:seon.turn/evals|messages`, `:seon.agent/sessions`
  are `{:seon.db/component true}` refs (`agent.cljs:247,298-299,323`); datahike
  `pull [*]` expands forward component refs inline
  (`reference-code/datahike/src/datahike/pull_api.cljc:145,163-167`). So one
  eval shows as a bare `:seon.eval` row + ~3x nested in agent/session/turn trees
  in findings, plus once in `<transcript>`.
- **Tests elided; one-in-fn-metadata mechanism real** — CONFIRMED. Standalone
  `deftest` mints a separate `:seon.test` row (`eval.cljs:1270-1284`);
  `compact-ns-source` queries only `:seon.schema` + `:seon.fn`
  (`ctx.cljs:1037-1088`), never `:seon.test`. A `defn`'s attr-map `:test`
  survives `elide-defn-body` (`ctx.cljs:978-991`) because the slice runs through
  the end of the arglist (the attr-map sits before it). The `<system>` header
  teaches exactly this (`ctx.cljs:835-837,859-864`).
- **Eval result rendered as a comment, not `=> value`** — CONFIRMED, and it is
  BY DESIGN, pinned. Suffix on success = `  ; ⇒ (result :<id>) · <dur>ms`
  (`ctx.cljs:519-522`), byte-pinned by `format-eval-row-pinned-glyph`
  (`test/seon/agent_context_test.cljs:817-843`). The plain-`=> value` form the
  user wants is a NEW spec change — NO recorded intent for it anywhere
  (PRDs/issues/commits). UNVERIFIED-as-intent: flagged plainly, not papered over.
- **`assistant> (+ 1 2)` echo root cause** — CONFIRMED + traced. It is the raw
  LLM reply text stored verbatim as a self→self message
  (`agent.cljs:1056-1063`, `reply-text`). When the model's whole reply is just
  the form (no prose), the message content === the eval source, so the form
  doubles. The renderer is faithful; the doubling is a storage artifact.

### Ranked fixes (value / effort)

1. **Delete the raw `<findings>` dump.** Locus:
   `src/seon/agent/findings.cljs:133-168` (`findings-block` +
   `findings-section`) and the `:findings` entry in `core-default-ctx`
   (`src/seon/ctx.cljs` section table, priority 48). Rewrite the pointer copy
   `findings.cljs:320-323` from "Full rows are in `<findings>` above" to "run
   the read-back query." REMOVES the entire 8,706-char dump, the ~4x eval
   duplication, AND the operational-kind leak in one move — without a name-list.
   KEEP `user-domain-kinds` + `read-query-hint` + `findings-pointer` (the
   inspector pane and the metadata-only pointer still use them). Risk: verify
   the inspector findings pane still resolves; confirm no other code references
   the `:findings` section name.

2. **Suppress the redundant `assistant> <form>` echo at its source.** The dup is
   storage, not render: `agent.cljs:1056-1063` stores the verbatim LLM reply as
   a self-message even when that reply is only the code already captured as
   evals. Cheapest correct fix: when the parsed reply has NO prose outside the
   code forms (the message would just re-show the evals), store no self-message
   (or store only the non-code prose). Do NOT special-case the renderer to drop
   form-shaped messages — that masks the storage smell and risks hiding genuine
   prose. Risk: ensure genuinely empty-prose turns still leave a turn record
   (they already do via `:seon.agent.turn` even with no message).

3. **Eval result format → plain REPL (NEW spec — confirm before doing).** Locus:
   `ctx.cljs:519-522` (suffix cond) + `:532-538` (row assembly). To render
   `(+ 1 2)\n=> 3`: drop the `; ⇒ (result :<id>) · Nms` success suffix and
   `; # <id>` error footer, render the value on a `=> ` line. The result-var id
   must be relocated — the `(result :id)` retrieval UX depends on it being
   visible (`ctx.cljs:464`). MUST rewrite the pinned byte-test
   (`agent_context_test.cljs:817-843`) in the same patch. This CONTRADICTS the
   V4-4 pinned design with no recorded intent; treat as a real decision, not a
   bugfix.

4. **Memoize `store-inventory` / `user-domain-kinds` once per render** (perf,
   not correctness) — thread through the section input map. Minor once #1 lands.

Namespaces section: NO change — it already matches the spec exactly (metadata +
schemas-in-full + fn heads with inline `:test`, standalone tests dropped, zero
raw rows). `inventory` already satisfies no-raw-data (metadata-only,
`db.cljs:812-898`).

## Context clarity audit — what's useless

Measured against the live EMZ-2606181326 fresh-seed render (210,050 chars / ~52.5K tokens).

### 1. Context shape, one line

**210,050 chars / ~52.5K tokens. The `namespaces` section is 197,713 chars = 94.1% of the entire context. Every other section combined is 12,337 chars = 5.9%.** Any token conversation that is not about `namespaces` is rounding error. And inside `namespaces`, the dominant cost is not code — it is **docstring prose (55%).**

### 2. Per-section table (ranked by size)

| Section | Chars | What it is (plain) | Verdict | The cut |
|---|---|---|---|---|
| namespaces | 197,713 | Compact dump of 56 namespaces: per-fn signature + `:malli/schema` + docstring (body elided to `…`), plus all `register!` schema rows. The agent's code corpus. | **bloated** | Truncate docstrings to first line (~93K), drop test/web/debug nses (~49K) |
| system | 5,556 | The one universal `<system>` block (cached, identical every turn): 8 concept paragraphs (REPL model, live-context, eval mechanics, render-twin, store laws) + 5 standing teachings. | **useful** | Trim ~1,200: kill the 3x-repeated "consult stored knowledge first" + the duplicated `:test` example |
| transcript | 3,267 | Recent eval history this session (reasoning comment + form + `=>` value). Fresh seed = 3 bootstrap evals. | **bloated** | Trim ~1,200 (other agent's lane): clamp the tx-report value dump |
| live-tile | 932 | What the human sees on this agent's panel + how to take it over. | **useful** | Trim ~400: drop verbatim welcome-card copy + duplicated change-it paragraph |
| inventory | 818 | Density map of stored data, one line per attr-namespace with row counts. Fresh seed = only the agent's own plumbing. | **useful** | Trim ~250: collapse header, de-dup vs system, hide agent's own seon.eval/turn plumbing |
| your-entity | 742 | The agent's own entity map, re-pulled each turn + unset-purpose nudge. | **useful** | Trim ~150: drop `:db/id`, fold redundant aside |
| warnings | 721 | Reactive defect surface. Fresh seed fires `[unmarked-entity-kinds]` against CORE schemas. | **bloated** | Cut entirely at agent boundary: it nags the agent to fix `seon.ctx`/`seon.handler` core schemas it was told never to touch |
| prompt | 164 | Status line + REPL prompt. ALL per-turn-volatile bytes live here (cache boundary). | **essential** | Keep as-is |

### 3. What's useless / wasteful — biggest first

**1. Docstring prose: 106,781 chars (55% of namespaces, ~27K tokens). THE single biggest waste in the whole context.** Split: ns-docstrings 69,773 + fn-docstrings 37,008. 112 of 130 fn docstrings are multi-line; the biggest single docstrings run 1,300–2,838 chars EACH (e.g. `seon.db/store-inventory` doc = 2,838; `my.kb` ns-doc = 2,111; `my.kb.system` = 1,850). **Truncating every docstring >60 chars to its first line recovers ~93,338 chars (~23K tokens, 44% of the WHOLE context) with near-zero contract loss** — the Malli contracts are rendered separately as `register!` rows and `:malli/schema` lines. The prose is redundant with the schema.

   The canonical waste, verbatim — `write-file`'s docstring restates in English exactly what its `register!` row already encodes in Malli:
   ```
   (defn write-file
     "Write … Returns:
        {:seon.agent.fs/ok? true :seon.agent.fs/path <p>}                          ; ok
        {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error <s>}"   ; ~250 chars of prose
     {:malli/schema [:=> [:cat :seon.agent.fs/write-request] :seon.agent.fs/write-response]}  ; the GOOD line — references, doesn't inline
     ...)
   (seon.schema/register! :seon.agent.fs/write-response
     [:map [:seon.agent.fs/ok? :boolean] [:seon.agent.fs/path :string] [:seon.agent.fs/error {:optional true} :string]])  ; same info, in Malli
   ```
   The waste is the prose, not the schema. This pattern repeats across ~130 fn docstrings.

**2. Test namespaces leaking into every prompt: 15,703 chars (~3.9K tokens).** `seon.test.runner` (13,363), `seon.handlers.test` (978), `seon.db.envelope-test` (824), `seon.test` (538). **This is a bug:** `included-ns?` (`ctx.cljs:228-242`) excludes only `*.internal` (the regex at lines 153-154 checks `.internal` only). `-test` names are NOT filtered — even though the file already has a `strip-trailing-test` helper at line 253 proving the codebase knows about the suffix. `seon.db.envelope-test` is flat-out a test file in every agent's standing prompt. An agent writing app code does not need 13K chars of the test harness on every turn.

**3. Web/UI rendering namespaces an agent never authors: ~28,148 chars (~7K tokens).** `seon.render.live-tile` (10,241), `seon.render` (5,905), `seon.web.brand` (4,370 — **a color palette**), `seon.render.chat` (4,285), `seon.render.default` (1,438), `seon.web.serve` (1,342), `seon.ui.html` (567). `seon.web.brand` is the Phosphor Terminal palette — zero value to an agent writing Clojure, pure dead weight. The rest is inspector/HTML machinery the agent does not author; surface on demand.

**4. AI adapter internals: ~16K chars (~4K tokens).** `seon.ai` (7,936) + `seon.ai.openai-compat` (4,299) + `seon.ai.anthropic` (3,894). The agent calls one entry point, not the adapter guts.

**5. `seon.debug`: 5,448 chars (~1.4K tokens).** Dev-only disk-write capture mode (behind `SEON_DEBUG_CAPTURE`). NOTE: the input data called this "untracked" — that is stale; it was committed in `52bc3b8`. Tracked or not, it is dev tooling, useless in standing agent context.

**6. The `warnings` section is misfiring (721 chars of standing noise).** On the fresh seed it fires `[unmarked-entity-kinds]` against `:seon.ctx/config-id` and `:seon.handler/key` — both CORE `seon.*` kinds. The system block explicitly tells the agent "the core is `seon.*` — never redefine its fns." So the warning instructs the agent to re-register a core schema it was told not to touch, and closes "Please correct before moving on" — on a fresh seed with no task. It fires identically on every fresh-seed agent. The renderer is good (reactive, self-healing); the data feeding it is wrong. Fix at the source: mark the core `:map` schemas `{:seon.db/entity true}`, and exclude core `seon.*` from agent-facing warnings.

**7. System block repeats itself (~1,200 cuttable chars).** "Consult stored knowledge FIRST" appears three times (system + inventory header + transcript). The `:test`/keep-fns-small example is stated twice (standing teachings AND the namespaces paragraph). Defensive asides ("a clipped display is NOT a clipped value", "telling your human something threw is wrong") are padding.

**8. Transcript dumps a raw tx-report (~1,400 of 3,267 chars = 43%).** A 2-attribute `transact!` whose meaningful result is `{:seon.db/ok? true}` renders its ENTIRE raw datahike tx-report — full `:tx-data` datom vector, `:db-before`/`:db-after` with max-tx/max-eid, `:db/commitId` uuid, request-id, plus 9 internal datoms (txInstant, agent-id, session-id, turn-id, origin, eval-id, request-id) the agent never wrote. The other two evals render as `=> []` — proof the transact value just isn't being clamped. (Transcript renderer is another agent's lane — flag, don't edit.)

**9. Inline-shape contract duplication: ~4,344 chars.** 56 fn-heads inline a `:catn`/`:map`/`:tuple` shape directly in `:malli/schema` AND that shape also exists as a `register!` row. The correct pattern (73 other defns, 3,101 chars) references the registered keyword. Small-scale, but real drift risk.

### 4. Ranked cuts (pick from these)

| # | Change | Savings | Risk | Lane |
|---|---|---|---|---|
| 1 | **Truncate every docstring (ns + fn) in namespaces-section to first line** (in `elide-defn-body` + `extract-ns-form`). Full docstring stays in on-demand `render-namespace`. | **~93,338 chars / ~23K tok (44% of whole context)** | Low — Malli contracts rendered in full separately; one-line keeps intent | ctx.cljs (ours) |
| 2 | **Exclude `*-test` nses + test runner from `included-ns?`** — add a structural rule beside the `*.internal` exclusion (the `strip-trailing-test` helper already exists). | ~15,703 chars / ~3.9K tok | Very low — tests aren't agent surface; `envelope-test` is plainly a leak | ctx.cljs (ours) |
| 3 | **Drop web/UI render nses from standing context** (`seon.web.brand`, `seon.render*`, `seon.web.serve`, `seon.ui.html`) via a render/web-prefix exclusion. | ~28,148 chars / ~7K tok | Low–medium — needed only if agent edits the inspector UI (exception; pull on demand). `seon.web.brand` unambiguously useless. | ctx.cljs (ours) |
| 4 | **Compact AI adapter internals** to public entry signatures or exclude (`seon.ai.openai-compat`, `seon.ai.anthropic`). | ~6–12K chars / ~1.5–3K tok | Medium — verify which entry the agent actually calls first | ctx.cljs + maybe source |
| 5 | **Drop `seon.debug` from standing context.** | ~5,448 chars / ~1.4K tok | Very low — dev tooling | ctx.cljs (ours) |
| 6 | **Trim system block dedup** — collapse "consult stored knowledge" to one line, state `:test` example once, tighten asides. Target ~4,300 chars. | ~1,200 chars / ~300 tok | Low | system def (ours) |
| 7 | **Trim live-tile / inventory / your-entity** default-state verbosity (drop verbatim welcome copy, collapse headers, drop `:db/id`, hide agent's own plumbing kinds). | ~800 chars / ~200 tok | Low | ctx.cljs (ours) |
| 8 | **Replace 56 inline fn-head shapes with registered keyword refs.** | ~3–4K chars / ~1K tok | Medium — edits source fns' `:malli/schema`, only where a registered key exists | source fns (ours) |
| W | **Fix `warnings` at the source** — mark core `:map` schemas `{:seon.db/entity true}`, exclude core `seon.*` from agent-facing warnings. | ~721 chars/turn + stops nagging | Low | warn + core schemas (ours) |
| T | **Clamp transcript tx-report value** to `{:seon.db/ok? true …N datoms}`. | ~1,200 chars / ~300 tok | Low | **transcript renderer — ANOTHER AGENT'S LANE; flag, don't edit** |

**Bottom line.** Cuts #1+#2+#3+#5 alone remove ~142,637 chars (~35.5K tokens) — they shrink the context from 210K to ~67K chars (~17K tokens), a **68% reduction**, with near-zero contract loss because the Malli schemas (the real contract) are rendered in full and untouched. Cut #1 is the headline: **half the context is docstring prose restating contracts the schema rows already encode.** Everything in tracks #4–#8 is incremental polish on top of that.

All cuts except T are in our lane (`ctx.cljs`, the system def, `seon.warn`, and source fns). T touches the transcript renderer owned by another agent — flag it, do not edit.

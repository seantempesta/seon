---
type: research
status: active
tags: [research, agent, prd]
---

# Review: The context is a render of the database

## TL;DR

**Overall design quality: 61 / 100.**

The thesis is right and — crucially — already half-built: "the context is a recursive render of DB renderables, dispatched by the entity's schema, with agent-overridable per-view slots" is not aspirational, it is the live `seon.render` mechanism (`entity-primary-kind` / `entity-render-slot`, the `:seon.schema/render-fn` materialization, the message entity carrying `:seon.render/ai`/`:html` slots). The PRD correctly extends that mechanism instead of forking a parallel one, and the no-parallel-system discipline (D2 keeps slots as fn-pointers, D6 feeds the existing cache boundary, D11 self-prune as a property, the message↔todo "addressed" derive) is internalized throughout. The dependency-ordered sequence (P5 first, keystone-with-deletion second, P4 both-halves-together) is genuinely well-phased. **But the spec is not implementation-ready.** A single load-bearing contradiction runs through the whole document — the renderable HANDLE and TIME (`:seon.render/id` / `:at` / `:ordinal`) are declared NON-existent by D1 yet read/written by the render fn, the composer sort, the transcript renderable, the override example, and D12 — and an implementer literally cannot build from it. Two further blockers: `resolve-slot` omits the EDN-decode that every live slot read performs (so every symbol slot dispatches as a literal string), and the transcript's "section" has two irreconcilable recursion models (query-derived children vs the stored-component `:seon.render/children` the section renderer reads). Settle the four blockers and the design is sound; ship as-is and two implementers build two subtly different things.

## BLOCKERS — must settle before writing code

- [ ] **Unify the renderable HANDLE/TIME story (`:seon.render/id` / `:at` / `:ordinal`).** PRD D1 (lines 37-43) + schema block (44-56) ban these as duplicated attrs, but the render fn (92, 99-100), generic-default (152-155), composer sort (184), transcript renderable (196), override example (167), and D12 (279-281) all read/write them. Pick the resolver approach: add `renderable-id`/`renderable-at` fns that dispatch on attribute presence to the entity's own id/at, reusing the existing derivation in `src/seon/render.cljs:275-318`. Rewrite the render fn, generic-default, composer sort, and D12 to call them; retarget the override example at the entity's real identity attr; delete the literal `:seon.render/id :transcript` from line 196.
- [ ] **Add the EDN-decode step to `resolve-slot`.** PRD `resolve-slot` (lines 71-84) reads the raw slot, but `:seon.render/ai`/`:html` are mixed-`:or` attrs stored pr-str'd, so a symbol slot comes back as the STRING `"seon.handlers.message/render-ai"` and hits the `(string? slot)` branch — returned verbatim, never invoked. Decode first, exactly as `entity-render-slot` (`src/seon/render.cljs:311-312`) and `decode-section` (`src/seon/ctx.cljs:1583-1587`) do via `db/decode-edn-value` (`src/seon/db.cljs:959-971`). Add a worked example showing the decode.
- [ ] **Reconcile the two transcript-section recursion models.** PRD line 116 says "a section is recognized by `:seon.render/children`" and `section-renderers` (121-133) maps over `(:seon.render/children node)` (a stored `[:vector {:seon.db/component true} :seon.db/ref]`), but the transcript (196-201) has an `:ai` SYMBOL slot and NO stored children (it is DERIVED per D8). Choose option B: the transcript's `:ai` fn calls the injected `:seon.render/render` handle (PRD line 95) over its query results and returns a String — matching today's string-returning `transcript-section` (`src/seon/ctx/transcript.cljs:273`). Fix lines 197-201 and the D4 "children" prose accordingly.
- [ ] **Fix the `:seon.render/html` schema — it references the deleted `:seon.render/hiccup`.** PRD line 45 registers `:seon.render/html [:or :seon.render/hiccup :qualified-symbol]`, but `:seon.render/hiccup` is no longer a registered schema (deleted in the 2026-06-11 sweep because recursive seqex trips `:malli.core/potentially-recursive-seqex`; `src/seon/render.cljs:54-65`). `register!`'s compilability guard (`src/seon/schema.cljc:294-324`) throws on the unregistered ref. Change line 45 to reference the live `:seon.render.live-tile/content` (`src/seon/render.cljs:87`); note the `:symbol`→`:qualified-symbol` tightening on line 44 is intentional and relies on clean-reset.

## Problems by severity and dimension

### Blockers

- **[architecture + data-model + completeness] The renderable HANDLE/TIME is self-contradictory.**
  - PRD: D1 (37-43), schema block (44-56) vs render fn (92, 99-100), generic-default (152-155), composer sort (184, 186), transcript renderable (196), override example (167), D12 (279-281), schema inventory (391).
  - Target: `docs/prds/agent-runtime/context-render-of-db-prd-2026-06-24.md:37-55`, `:86-100`, `:149-157`, `:182-201`, `:279-281`, `:391`; precedent `src/seon/render.cljs:275-318`; real handles `src/seon/agent/message.cljs:29`, `src/seon/agent.cljs:138`, `src/seon/ctx.cljs:96`.
  - Fix: keep D1; add `renderable-id`/`renderable-at` resolvers (dispatch on attribute presence, reuse `entity-primary-kind`); rewrite every `(:seon.render/id node)`/`:seon.render/at` site to call them; the same fix must cover `:at`/`:ordinal` since D12 and the composer reintroduce them. Strike `:seon.render/ordinal` from line 391 (it is a render-time index per line 43, not a stored attr).
  - Note: the three dimensions filed this separately; it is one defect. The eval-id home is `agent.cljs:138`, not `eval.cljs:2178`.

- **[render-impl] `resolve-slot` reads raw slots; every symbol slot fails dispatch.**
  - PRD: `resolve-slot` (71-84).
  - Target: `docs/prds/agent-runtime/context-render-of-db-prd-2026-06-24.md:71-84`; decode precedent `src/seon/render.cljs:311-312`, `src/seon/ctx.cljs:1583-1587`, `src/seon/db.cljs:959-971`, `src/seon/db/internal.cljs:362-410`.
  - Fix: `(db/decode-edn-value view (get node view))` before the `cond`; add a worked example pulling a stored slot.

- **[render-impl + completeness] Two irreconcilable recursion models for the transcript section.**
  - PRD: section renderers (120-133) vs transcript renderable (196-201) + D4 (222-225) + D8 (266).
  - Target: `docs/prds/agent-runtime/context-render-of-db-prd-2026-06-24.md:116-133`, `:196-201`, `:222-225`; today's string-returning fn `src/seon/ctx/transcript.cljs:273`, leaf model `src/seon/ctx.cljs:1619-1643`.
  - Fix: adopt option B (transcript `:ai` fn recurses internally via the injected handle, returns a String). Update line 117 to drop "children-attr is the sole recognition signal."

### Major

- **[data-model] `:seon.render/html` references the deleted `:seon.render/hiccup` (register-order throw).**
  - PRD: schema block (line 45).
  - Target: `:44-45`; `src/seon/render.cljs:54-65`, `:87`; `src/seon/schema.cljc:294-324`; live shallow shape `src/seon/render/live_tile.cljs:307,314`.
  - Fix: reference `:seon.render.live-tile/content`; cite the recursive-seqex platform law. (Filed by architecture, data-model, and render-impl — one defect.)

- **[data-model + completeness] Three-way contradiction on whether `:seon.render/ordinal`/`:at` are stored.**
  - PRD: D1 (41-43) vs D12 (280-281) vs schema inventory (391-393, self-tagged `[OPEN]`) vs Q1 (441-442, "NO stored ordinal").
  - Target: `:41-43`, `:279-281`, `:391-393`, `:440-442`; time sources already exist `src/seon/agent/message.cljs:33`, `src/seon/agent.cljs:139`.
  - Fix: resolve to DERIVE — ordinal = render-time index over the `:at` sort, time = the entity's own `:at`. Delete `:seon.render/at` + `:seon.render/ordinal` from line 391-393, drop D12's "RESOLVED" stamp, register nothing new. (Same defect underlies the agent-behavior "ordinal teaches a moving target" finding — see Major below.)

- **[data-model] Q1 falsely claims `:seon.db/request-id` is auto-stamped; conflates two origin enums.**
  - PRD: Q1 (439-442).
  - Target: `:439-442`; real tx-meta `src/seon/db/internal.cljs:101-106` + `src/seon/db.cljs:247-253`; message provenance enum `src/seon/agent/message.cljs:47`.
  - Fix: strike `:seon.db/request-id` (does not exist). State the real tx-meta set. Disambiguate: transcript user-filter uses `:seon.agent.message/origin = :human`; tx-meta `:seon.db/origin`'s user value is `:user`. (The `:54-55` target in the original finding is misattributed — the error lives at `:439`.)

- **[data-model + render-impl] `register-renderer!` may be a parallel mechanism to the existing schema-property slots.**
  - PRD: registering/overriding (170-174), Q4 (459-462), function list (400-401).
  - Target: `:170-174`, `:83`; single store today `src/seon/agent/message.cljs:74-75`, `src/seon/agent.cljs:255-257`, resolved via `src/seon/render.cljs:202-240`. Scope doc already flags it: `context-render-of-db-scope-2026-06-24.md:86`.
  - Fix: define `register-renderer!` as sugar that UPSERTS `:seon.render/ai`/`:html` props onto the existing registered `:map` schema; make `resolve-slot` step 2 read those props via `renderable-kinds`/`:seon.schema/render-fn`. State that `handlers.*` are absorbed and props repointed in the same patch — no second registry.

- **[render-impl] Six top-level fns the composer/render depend on are undefined; several missing from the "complete" function list.**
  - PRD: composer (182-201) + "New/changed functions (complete)" (396-412).
  - Target: `:183-184`, `:138-143`, `:83-84`, `:157`, `:197`, `:396-412`; existing dispatch `src/seon/render.cljs:202-318`, `src/seon/ctx.cljs:1711`.
  - Fix: enumerate `top-renderables`, `pin-or-churn`, `who`, `schema-default-renderer` with signatures. Crucially, define `pin-or-churn`'s per-kind churn-class→sort-key TABLE concretely (not "measure") — the keystone composer cannot be written without it. Confirm `register-renderer!` writes `:seon.schema/render-fn` + `:seon.schema/render-html-fn`.

- **[architecture] No-kinds dispatch contradicted by the scope doc's `:seon.render/kind` enum.**
  - PRD: D1 (205-209) vs scope keystone.
  - Target: `:205-209`; `context-render-of-db-scope-2026-06-24.md:54-58`; transient derivation `src/seon/render.cljs:275-318`.
  - Fix: strike `:seon.render/kind` from scope lines 54-57. Add a sentence to D1 that `entity-primary-kind`'s returned kind keyword is a transient render-time derivation, never persisted. (PRD D6 line 231 already supersedes the `:seon.ctx/cache-tier` half; the kind half is unreconciled.)

- **[architecture] 3-step dispatch under-specified for entities satisfying MULTIPLE schemas.**
  - PRD: D3 (218-221), resolve-slot (71-84), children⇒section (116-119).
  - Target: `:71-84`, `:116-119`, `:205-221`; rule lives at `src/seon/render.cljs:275-298`.
  - Fix: state the most-required-attrs + alpha tiebreak in D3 citing render.cljs:284-298. Fold "has `:children` ⇒ section" into the same specificity machinery (register a section entity-schema whose required attr is `:seon.render/children`). Add one worked two-schema example.

- **[architecture] Explicit `:children` (stored component vec) vs the transcript's QUERY-derived children — "section" means two things.** (Same root as the transcript blocker; tracked there. Filed by architecture and completeness.)
  - Target: `:51-52`, `:115-133`, `:194-201`, `:222-225`, `:437-444`.
  - Fix: unify "section = a node whose render fn returns a vector of child renderables," sourced from EITHER a pulled component vector OR a slot fn; both feed `(map render children)`.

- **[architecture] Renderable control-attr set: churn storage status self-contradicts (D6 vs D23) and the composer lacks a churn-class derivation.**
  - PRD: schema (44-56), D6 (230-261), D23 (330-337), composer sort (184).
  - Target: `:330-331` ("the `:seon.render/churn` attr") vs `:53-55` ("NOT a stored attr"); `pin-or-churn` undefined at `:396-412`.
  - Fix: churn is a per-kind CLASS derived at render (fn of `entity-primary-kind`), NOT an attr — fix D23 line 330-331, add the derivation to the function list. (The clip-vs-hidden? overlap sub-claim is refuted — D10/D11 give them distinct semantics; drop it.)

- **[architecture] `:seon.render/ai`/`:html` registered shapes silently changed from the live `[:or :string :symbol]` / `:seon.render.live-tile/content`.** (Overlaps the hiccup blocker; the `:symbol`→`:qualified-symbol` half is minor — classification is preserved because `:qualified-symbol` is unmappable and still EDN-encodes.)
  - Target: `:44-45`; `src/seon/render.cljs:80-87`; `src/seon/db/internal.cljs:362-379`.

- **[agent-behavior] Ordinal decision contradicts itself — a weak model is taught to cite a moving target.** (Same root contradiction as the data-model ordinal finding; the behavioral consequence is the addition.)
  - PRD: D12 (279-281) vs Q1 (441-442) vs schema comment (42-43).
  - Target: `:279`, `:391`, `:42`, `:441`.
  - Fix: drop the "cite event N" positional teaching; teach id + relative time (the id is the durable handle). A render-time index is unstable across turns once D11 prune / D6 windowing removes anything.

- **[agent-behavior] Auto-todo hook predicate is wrong/under-specified.**
  - PRD: D17 (297-299), function list (408-409), P4 (366).
  - Target: `:408`; single write path `src/seon/agent/message.cljs:1-19,252-289`, origin derivation `:222`; wake predicate `src/seon/ctx/transcript.cljs:88-102`.
  - Fix: gate the auto-todo strictly on `origin = :human` (already committed at PRD line 442 but omitted at D17/408). `message!` is a defensible hook SITE (origin is derived there); the defect is the missing predicate — without it, todos mint for the agent's own outbound and peer chatter. State the predicate explicitly in D17.

- **[agent-behavior] D7 no-clipping + P7 window-pressure warning + D7↔scope eviction contradiction.**
  - PRD: D7 (263-264), D14/P7 (286-288, 410), D11 (275-278).
  - Target: `:263` ("eviction OFF for transcript") contradicts `context-render-of-db-scope-2026-06-24.md:253-257` ("eviction loop STAYS as per-agent token budget"); warning has no backstop at `:286`.
  - Fix: reconcile D7-vs-scope on transcript eviction, name a HARD safety clip far above the soft budget as a backstop, and wire the window-pressure warning to a concrete recourse (the existing lifecycle verbs end the loop). Note: D11 prune CAN shrink agent evals/messages (it only refuses `:human`), so the "no lever at all" framing overstates it; the real gap is the unstated backstop + the two docs disagreeing.

### Minor

- **[architecture] Composer keeps `merge-sections` (old override-by-name) alongside the new `top-renderables` walk.**
  - Target: `:182-190`, `:305-308`, `:397-399`; `src/seon/ctx.cljs:1598-1612`; scope `:70-80,165,234-235,390`.
  - Fix: show `top-renderables` subsuming `merge-sections` override-by-name (union core converters + agent `:seon.agent/ctx` overriding by id + derived rows in ONE query); make D20's bootstrap a renderable in that union; delete `merge-sections` in step 2.

- **[render-impl] Converters read the node at `:seon.render/node`; existing renderers read `:seon.render/entity` — "absorb" is an input-contract rewrite.**
  - Target: `:135-144`; `src/seon/handlers/message.cljs:60,80`, `src/seon/render.cljs:337,474`.
  - Fix: one-line clarification that "absorb" = rewrite the destructure key across all `seon.handlers.*` + `render-entity-*`; stored agent renderers covered by the clean-reset baseline (PRD answer #1, line 443). Do NOT inject under both keys (that is the banned dual-path).

- **[render-impl] Soul-into-composer (D16/P3) vs the anthropic adapter's block-1 cache breakpoint.**
  - Target: `:293-296`, `:363-364`; `src/seon/ai/anthropic.cljs:148-163`, `src/seon/ai.cljs:357-383`; scope `:147-182`.
  - Fix: the decision IS made (D16: "fully in composer, no block-1 special case"; scope deletes `effective-system-prompt`/`debug-full-prompt`). Document the concrete post-move system-block layout and update `anthropic.cljs` in the same patch; verify the 2-breakpoint behavior holds.

- **[render-impl + completeness] `render`/generic-default read `:seon.render/id` but no entity carries it.** (Subset of the HANDLE blocker; the fix is the `renderable-handle`/`renderable-id` resolver. Defect lives in the PRD's prescribed code at `:92,99-100,124,130,137,141,152-155`, NOT in current `render.cljs`.)

- **[render-impl] Transcript-as-renderable under-specifies `prior?`-per-eval, turn-marker interleave, and per-eval body caps.**
  - Target: `:192-201`, `:264`; `src/seon/ctx.cljs:475-617`, `src/seon/ctx/transcript.cljs:177-346`.
  - Fix: specify how `transcript-children` derives `prior?` at session boundaries, how turn markers interleave by `:at`, and that per-eval body caps (echoed source/stdout 1500 vs result body 16384) live in the eval converter — distinct from `:clip :none` turn clipping. Make the two clip concepts explicit.

- **[data-model] `:seon.render/clip` map alternative uses bare `:ai`/`:html` keys.**
  - Target: `:47-49`.
  - Fix: `[:map [:seon.render/ai {:optional true} :int] [:seon.render/html {:optional true} :int]]` — full namespaced keys, matching the walker's dispatch keyword.

- **[data-model + completeness] Register-order unstated; `:seon.render/id` used everywhere but missing from the schema list.**
  - Target: `:44-56`, `:383-394`; `src/seon/render.cljs:84-87`. (`:clip`/`:hidden?`/`:children` ARE covered via the "— above" delegation; only `:seon.render/id` is genuinely absent-and-used — resolved by the HANDLE blocker.)
  - Fix: add a register-order note (render-control attrs before any entity `:map` referencing them; `seon.ctx`↔`seon.render` load order pinned).

- **[completeness] message→todo link needs a new `:seon.agent.todo/message` ref attr omitted from the "complete" schema list.**
  - Target: `:383-394`, `:446-450`; `src/seon/agent/todo.cljs:40-47,62-67`.
  - Fix: add `:seon.agent.todo/message :seon.db/ref` and the `::add-request` `::message` slot. `handled?` removal is already tracked at Q2:449-450 (not a separate gap); the clipped preview can reuse `::title`.

- **[completeness] D18 (extract lifecycle) vs Q5 (defer) reach opposite conclusions.**
  - Target: `:300-302`, `:372`, `:407`, `:463-467`; research `destub-curate-and-behavior-2026-06-24.md:363-371`; verbs at `src/seon/agent.cljs:520,538,562`; whitelist `src/seon/ctx/namespaces.cljs:124`.
  - Fix: propagate Q5's DEFER (the later, research-backed answer) — remove `seon.agent.lifecycle` from the P5 curated set (372) and function list (407), keep `seon.agent` whole-file, downgrade D18 to `[DEFERRED] — see Q5`.

- **[completeness] Q3 (AGENTS.md standardization) marked RESOLVED but the direction + blast radius are unstated.**
  - Target: `:451-458`; `src/my/soul.cljs:33-36` (reader reads `AGENTS.md`, root has `AGENT.md`).
  - Fix: state the direction. Cheapest: change `agents-md-path` to `"AGENT.md"` (one line). The renderable conversion (D16) is the substantive work. (The headline "backwards factual claim" is itself wrong — the PRD states the discrepancy correctly; only the direction is unmade.)

- **[completeness] No rollback/checkpoint-failure gate despite "delete originals in the same patch."**
  - Target: `:414-430`; behavior battery `destub-curate-and-behavior-2026-06-24.md:135-171`.
  - Fix: add a one-line gate — each unit's checkpoint is GREEN `bin/test-cljs` AND a drive no-worse than baseline on the P1-relevant ranked problems (#1 task-forgotten, #3 inline-backtick, #4 result/<id> deref — three, not four); on regression, revert the atomic unit.

- **[agent-behavior] Lifecycle verbs use banned `[:maybe]` and awkward `[:or :state response]` returns.**
  - Target: `src/seon/agent.cljs:345` (`[:maybe :seon.agent/state]` violates the rule stated in the same file at `:246`), `:526,544,568`.
  - Fix: replace `[:maybe]` in `current-state`'s return; confirm the keyword-or-map return shapes are intended. Treat the lifecycle extraction as behavior-load-bearing (`complete`/`terminate` are unexercised in drives) — prefer real-source over prose-only.

### Nits

- **[architecture] "One walk, two views" is overstated — it is one parameterized walk run once per view.** Reword the section header (line 62); the thesis at line 14 ("one recursive WALKER") is already accurate. No code change.
- **[data-model] Eval-scoping query for the transcript glossed as trivial.** Evals scope via the `agent→sessions→turns→evals` component walk (already live: `src/seon/ctx.cljs:688-716`, `src/seon/ctx/transcript.cljs:215-226`); messages scope via from/to refs. One clarifying sentence in Q1; no design change, no new ref.
- **[data-model] Todo time attr is `:created-at`, not `:at`.** If todos interleave the `:at`-sorted stream, read each kind's own timestamp via a per-kind accessor (message `:at` / eval `:at` / todo `:created-at`) — no duplicated attr. Scope `:191`; `src/seon/agent/todo.cljs:44`.
- **[agent-behavior] D6 churn-reordering of non-transcript sections lacks a task-location falsification check.** The transcript itself is pinned bottom + flat time-ordered (the steering surface is stable), so the worry is narrower than filed; add a DeepSeek drive confirming task-location accuracy doesn't drop when a non-transcript section reorders. The warning-float slot is already `[OPEN]/measure` (line 259).
- **[agent-behavior] D20 first-turn bootstrap ordering vs the just-fixed first-wake hijack.** The empty-inbox vacuum is already gated against (D20 "when there is fresh unaddressed work"; the flat event-log subsumes the deleted no-turns branch). Genuine residual: specify the bootstrap renders BELOW the inbound task lines, and add a regression check that a fresh agent with a pending task acts (not greets). `:305`; `src/seon/ctx/transcript.cljs:377`.
- **[agent-behavior] Auto-todo "memory aid, not an obligation" (D17) vs "complete the message-todo" (D22).** The done-signal is NOT destroyed — scope-design §2 (`:445-450`) specifies completion as the close mechanism. Reconcile the two phrasings (a teaching refinement), don't redesign.
- **[completeness] `resolve-slot` string/vector arms discard injected ctx; explicit-slot-vs-children precedence unstated.** Real but describes a non-pattern (the design never pairs a verbatim string `:ai` with stored children). One-line clarification: children-bearing section nodes use the section renderer regardless of an `:ai` string.

## Problems left to resolve (genuine open design questions)

1. **The HANDLE/TIME representation** — the recommended `renderable-id`/`renderable-at` resolver path is clean, but the team must confirm it (vs a uniform stored `:seon.render/id`, which D1 bans). This single call rewrites nearly every code example in the PRD; it is the one decision that must precede line one.
2. **`pin-or-churn`'s per-kind churn-class table** — marked `[OPEN]/measure`, but the keystone composer's entire ordering hinges on it. Can the keystone ship with a placeholder table tuned later, or must the table exist first? The PRD should state which.
3. **Migrate vs derive for P1 events** — the PRD's own `[OPEN]` at lines 392-393 is unclosed despite Q1 being marked RESOLVED. Recommended: derive (no new attrs). Confirm.
4. **The post-move anthropic system-block layout** — D16 deletes block-1; the exact cache-breakpoint layout the adapter sends afterward (does block 1 become the cluster-static prefix including the soul-as-renderable?) and whether a third in-prefix breakpoint is added are flagged but unspecified.
5. **Window-pressure recourse** — what the agent concretely DOES at ~80% given D7 forbids transcript clipping; needs either a hard backstop clip or an explicit taught action, plus reconciling D7 with the scope doc's "eviction stays on as token budget."
6. **`seon.agent.lifecycle` scope** — extract (D18) or defer whole-file (Q5/research). The three sources disagree; pick one.

## What's already strong

- The schema-default dispatch the PRD proposes **already exists** (`entity-primary-kind`/`entity-render-slot`, the `:seon.schema/render-fn` materialization, the message entity's slots) — the design extends a real mechanism, the opposite of a parallel-system risk.
- No-parallel-system discipline is internalized and repeated: D2 keeps slots as fn-pointers (rejecting "make `:ai` the rendered text"), D6 feeds the existing cache boundary, D11 self-prune as a property, the message↔todo "addressed" derive removing the redundant `:handled?` attr.
- The dependency-ordered sequence is genuinely well-phased and each step is independently testable; the "delete originals in the same patch" + clean-reset baseline make the refactor atomic and cheap to revert.
- One-directional refs + free-VAET-reverse, throw-to-legible-banner guards, and `:seon.render/token-estimate` routing through `seon.tokens` are all faithful to the live code.

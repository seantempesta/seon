---
type: research
status: active
tags: [research, agent, config, decision]
---

# Config-driven agent init — DECISION LOG (owner-settled)

The durable, linkable record of every owner decision + directive driving the
config-driven-agent-init refactor. **This is the INPUT (decisions).** The full
DESIGN lives in [[config-driven-agent-init-spec-2026-07-01]] — the agent folds
these decisions into that spec. Update THIS log as new decisions land; do not
duplicate the spec's content here.

**Paste-to-reorient:** hand an agent
`docs/prds/agent-fsm/research/config-driven-agent-init-decisions-2026-07-01.md`
+ the spec doc, and it has the complete, current instruction set — no reliance
on chat history or a message queue.

## The goal (owner)

ONE `init-agent!` consumes a Malli-spec'd, aero-loaded config map describing the
ENTIRE agent + its context. Rip out ALL the extraneous scattered mechanisms so
there is exactly one config-data-driven way to set everything up — and then it's
changed by **modifying the config on the agent's record**, never by manually
tweaking other state.

## Settled decisions

1. **Flat, fully-namespaced keys — NOT nested maps.** The keyword-namespace IS
   the grouping (a block is a namespace; its sub-dials are the keys under it).
   Rationale: datom-native (no nested maps in datahike), no bare keys, uniform.
   Nested VALUES under a flat key (tier vector, require-spec vector) are fine.

2. **Reactive config-on-record (the keystone).** The config dials are scalar
   attrs ON the agent's record. EVERY renderer reads its config from the agent's
   datoms AT RENDER TIME — not from a hardcoded default (`default-seed-blocks`,
   `default-namespaces-policy`) and not from a boot-frozen value. So a single
   `db/transact!` to a config attr re-derives the agent's next context AND the
   datastar UI, with no apply step (the reactive-context model). The agent can
   reconfigure ITSELF by transacting its own config attrs. The actual refactor =
   move every hardcoded-default read to an agent-datom read.

3. **Colocation naming.** Every key's namespace = the real CODE namespace that
   owns/operates on it, so the `schema/register!` and the reading fns colocate.
   All keys are attrs on the agent entity, but each attr's namespace points at
   its operating code. Confirmed mapping (agent confirms the ~8 flagged from
   source):
   - `seon.agent.ctx` — blocks, block-priorities, extra-blocks, cache-breakpoint, escape-clipping?
   - `seon.agent.ctx.namespaces` — include, current-ns?, include-referred-local?, third-party?, view, current-ns-view
   - `seon.agent.ctx.transcript` — tiers, turns-retained, summary-head?, result-decay
   - `seon.ai` — provider, model, temperature, max-tokens, context-window, thinking, max-retries (match the existing `:seon.ai/config` row)
   - `seon.eval` — home-requires, toolkit
   - `seon.agent.fs` — roots, read-only?
   - `seon.agent.run` — default-turn-limit, default-deadline-ms
   - `seon.agent.schedule` — schedules
   - `seon.agent.lifecycle` — auto-terminate?
   - `seon.client` — wake?
   - `seon.db` — origin-scope
   - `seon.render.live-tile` — enabled? (was canvas?), content (was canvas-content)
   - **CONFIRM from source:** skills/load + skills/order; findings/warnings/inventory/relevant-source/cite-card `/enabled?`; capabilities gate (grep/exec/http); soul + persona (ctx or own ns?).
   - Constraint: NEVER invent a keyword-ns prefix that isn't a real code ns.

4. **Defaults live IN the schema (malli-native).** Each key's `register!`
   carries a `:default` property (`:default/fn` for computed). Verified in
   `reference-code/malli/src/malli/transform.cljc:484-520`:
   `(m/decode schema v (mt/default-value-transformer {::mt/add-optional-keys true}))`
   fills every unspecified key from its default. `::add-optional-keys true` is
   REQUIRED (our keys are all `{:optional true}`). DELETE the spec's separate
   "code-defaults layer / default-agent-context const" — `resolve-agent-context`
   = merge aero named-config + per-mint override, then `m/decode` through the
   default-value-transformer.

5. **Tighten every spec per malli idiom** (immerse in `reference-code/malli`):
   bounded ints (`[:int {:min 0}]`), real `:enum` value sets, and REGISTERED
   SHARED SHAPES referenced by name (`:seon.agent.ctx/block`,
   `:seon.agent.schedule/schedule`, `:seon.agent.ctx.transcript/tier`, the
   home-require-spec shape) — NOT inline `:map`/`:any` (register once, reference
   everywhere). Note leaf-rule cases where `seon.config` must stay loose and
   validation happens downstream.

6. **Per-agent LLM: KEEP** the `seon.ai/*` group (per-agent override; `:inherit`
   default = the global `:seon.ai/config` row). It "falls out free" as attrs on
   the record.

7. **NO whole-context token-budget / fixed-growth clipping (not yet).** Blocks
   render FULL by default. Instead: per-surface render-LEVEL config keys (add
   more per thing over time) + transcript clipping + eval-result DECAY.

8. **Eval-result DECAY (investigate + design configs).** A result renders
   NEAR-FULL right after execution (so the agent can view a big file it just
   read), then decays to clipped over subsequent turns — possibly TWO levels
   (near-full → partial → clipped). Config keys: per-level token caps + the
   turn-offsets at which each level kicks in. Investigate the current
   eval-result render + any decay in `seon.agent.ctx.transcript` first.

9. **Namespace VIEWS, not `:full|:signature`.** Which nses render (the flat set)
   PLUS what ASPECT each shows — source / tests / signatures as selectable
   aspects. The CURRENT ns gets a richer default view (renders its TESTS too);
   other included nses render source only. Drop the single `:full|:signature`
   key; signatures become one selectable aspect.

10. **Skills — trace + unify the summary→expand path.** Owner suspects the
    skills block shows all skills + mini-descriptions and the agent "loads full
    and it expands" — a separate expand mechanism/state duplicating the
    namespaces summary-vs-full pattern. Trace it in source (how it renders, how
    expand works, WHERE the expand-state lives). Fold into the config model:
    which skills render full vs summary = a key on the agent's record, reactive,
    NO separate manual expand-state. Report whether it's a duplicated path + the
    unified design.

11. **Config-by-identity, not `:kind`.** Two named configs
    (`:seon.config/agent-context` + a sparse `:seon.config/root-context`),
    selected by `:seon.agent/id` ("root" vs the rest). No stored `:kind`/`:role`
    datom.

12. **Build ALL keys ATOMIC** (one unit). **Behavior parity:** a no-override
    boot must produce a byte-identical seed to today; the ONLY intended changes
    are `escape-clipping?` default true (#43) and transcript tiers ON (#62) —
    both gym-measured before/after.

## Prior owner decisions folded in (from core-handoff §B)

- #43 escape value-clipping → `escape-clipping?` default true.
- #45 disable inventory → the inventory-enabled key defaults false (owning ns
  confirmed from source — one of the ~8 flagged in decision 3, likely
  `seon.agent.ctx.inventory` if that render ns exists, else `seon.agent.ctx`).
- #56/#73 home-ns aliases are REAL requires in the ns form (no magic).
- #74 (todo signature-trim) is MOOT — signatures retired; `todo` is just a
  member of the namespaces include-set.

## v3 reframe (owner iteration) — the two-level entity model

The v2 flat-agent-only model didn't match how per-block / per-namespace config
should work. Refined:

13. **Two-level entity model.** The context is the AGENT entity + a set of
    component-ref'd BLOCK entities. AGENT-level config (model, turn-limit,
    home-requires, toolkit, origin-scope, wake, capabilities) = attrs on the
    agent entity. Per-BLOCK config = attrs ON EACH BLOCK entity, colocated with
    that block's render code. "Which blocks I want" = which block entities exist
    on `:seon.agent.ctx/blocks`; "a block's config" = that block's datoms. More
    datom-native than v2; matches "each block has its own config".

14. **Per-namespace render map (folds in third-party + views).** The namespaces
    block's config is `:seon.agent.ctx.namespaces/render` = a `:map-of` ns-kw →
    aspect-set (`#{:source :tests :signatures}`), with a special `:current` key
    for the current ns. REPLACES the v2 global `third-party?` bool + the separate
    `view`/`current-ns-view`/`view-overrides` keys — a third-party ns is just an
    entry; per-ns aspects are the value. `include-referred-local?` stays a
    namespaces-block bool that auto-adds local-require nses to the render map.

15. **`enabled?` bools dissolve.** A block's PRESENCE is its "on"; dropping the
    block entity is its "off". Removes the scattered findings/warnings/inventory/
    relevant `enabled?` keys — add/remove the block instead.

16. **Loading = component-ref nested transaction.** `:seon.agent.ctx/blocks` is a
    COMPONENT ref (`:db/isComponent`, cardinality-many). Pipeline: aero reads a
    SPARSE manifest (namespaced block maps) → `resolve-agent-context` merges
    (agent-context ← root-context ← per-mint override) then `m/decode` through
    `default-value-transformer {::mt/add-optional-keys true}` (RECURSES — fills
    agent AND per-block defaults, so sparse block maps get their config) → ONE
    `db/transact!` of the nested map → datahike auto-creates each block entity +
    wires the refs. Changing config later = transact (retractEntity a block, add
    a block map, or assoc a block's config attr) → reactive re-render.

17. **Block identity is not a `:kind`.** `:seon.agent.ctx/name` is an
    identity/ordering handle (upsert-by-name, sort) — NOT a kind stamp. What a
    block DOES follows from its attributes (a block with
    `:seon.agent.ctx.namespaces/render` is what the namespaces renderer picks up).
    The spec must nail the discriminator so no `:kind` sneaks in.

## Build decisions (owner, 2026-07-01)

18. **Sequencing: INTERLEAVE.** The config refactor is the main build focus; the
    inspect-bridge benchmark (#86) finishes in the background (different lane). #85
    memory-evolution comes after. Neither stalls the other.

19. **Build shape: builder's-choice ORDER, but with hard discipline** (the owner
    has repeatedly been told "done" only to find it partial — this is the #1
    constraint):
    - **Proper git-commit CHECKPOINTS** at every stable step (so a clean break is
      always available + we can bisect breakage).
    - **A completeness LEDGER** — every deletion, every renderer→datom move, every
      schema registration, every wire-up is a tracked item verified to done. "Done"
      = every box checked + gym-green + byte-parity proven + a live drive. NO early
      victory, NO "declare done while partial".
    - **Clean-break-then-find-all-breakage:** make the structural change, checkpoint,
      then systematically sweep for EVERY breakage (compile errors, test failures,
      grep the ripped symbols to zero, live drive) and drive it to zero — rather
      than fragile keep-green-at-every-micro-step.
    - **SIMPLIFY / CONVERGE, never redo 3 ways.** The entire point is ONE config
      path. At each step collapse duplicated paths; never leave a `foo`/`foo-v2`.
    - **Byte-identical gym parity** is the gate: a no-override boot reproduces
      today's context exactly; only escape-clipping true + transcript tiers change.

20. **Decay is CONFIGURABLE too** — the eval-result decay schedule is a config key
    (`result-decay`) with a sensible default, NOT hardcoded. (Same for the per-tier
    caps generally: levels are data, tunable per-agent + gym-tuned at build.)

21. **PARK ≠ FORGET.** These net-new keys are DEFERRED to a later pass but stay in
    the spec as explicit "deferred / phase-2" items (registered-or-documented so
    they are not lost): `persona`, `auto-terminate?`, per-agent `fs/roots`,
    per-agent `llm/context-window`, `schedule/seed`, `origin-scope`. The core
    net-new mechanisms STAY IN v1: per-agent LLM (provider/model/temp/etc.),
    namespace views + the tests aspect, and the configurable eval-result decay.

## v4 correction (cross-lane, namespace-display agent + verified in source)

22. **NO `:map-of` / vector-of-map CONFIG values — datahike serializes them to
    opaque blobs.** VERIFIED in source: the `seon.db` bridge stores any complex
    value as a `pr-str`'d EDN string (`db/internal.cljs:243,364` — "datahike's
    typed schema cannot hold" complex shapes), which kills per-element
    queryability + reactivity (the decision-2 keystone). So model config as:
    - **(a) cardinality-many presence/value attrs** for flat sets + booleans —
      e.g. the namespaces block's `:seon.agent.ctx.namespaces/full-source` +
      `::with-tests` as `[:vector :seon.ns/name]`, **presence = config, absence =
      compact** (matches the EXISTING `:seon.agent.ctx/render-namespaces`
      `[:vector :keyword]`). This REPLACES decision 14's `::render :map-of`.
    - **(b) reified component entities** (`:db/isComponent`-ref'd off the parent)
      the moment a per-element facet carries a VALUE — **transcript tiers** and
      **result-decay levels** each become a tier/level ENTITY (with `from-turn` /
      `token-cap` attrs), NOT a `[:vector [:map …]]` blob.
    - **(c) a serialized EDN blob ONLY** where you deliberately never query
      per-element (a set-once config value like maybe `home-requires`).
    - `block-priorities` (`:map-of`) DISSOLVES → a per-block `:seon.agent.ctx/priority`
      attr (blocks are already entities). `skills/load`, `toolkit`, `capabilities`
      are already flat keyword SETS → cardinality-many, fine.
    Rule of thumb: **presence-set for booleans/flat-sets; reify-with-component the
    moment a value attaches; blob only for never-queried set-once config.**

23. **Namespace-display specifics** (from the additions note + card spec):
    - Most nses render as a **compact CARD** (schema block + one-line fn heads,
      bodies elided; 3–5× smaller) — NOT the dead `:signatures` view. `:signatures`
      DROPPED (0× adoption footgun; the card supersedes it).
    - **Examples DROPPED** — real evals are in the transcript; harvesting into the
      cached block busts the prompt cache.
    - ns value type = `:seon.ns/name` **keyword** (tolerates dynamic/unindexed
      `my.agent.*` nses) + a **DERIVED warning** for a configured-but-unmatched ns
      (fail-visible, no silent typo — the reactive-warning pattern).
    - Current ns = two scalar bools (`::current-full?` / `::current-tests?`), NOT a
      magic `:current` map key.
    - Byte-parity: v1 defaults keep today's full set full; the compact-everywhere
      flip is a SEPARATE owner-gated A/B step (render-prominence 0× guardrail),
      after the atomic parity build.
    - Depth: [[config-driven-agent-init-namespaces-additions-2026-07-01]] +
      [[compact-namespace-cards-spec]]. Card renderer sequences BEHIND the config
      foundation (block-entity + these attrs) landing.

## Open (agent to resolve in the spec, owner reviews)

- The ~8 colocation homes flagged in decision 3.
- The exact eval-result decay levels + turn-offsets (from source).
- The exact namespace-view aspect set + current-ns default view.
- Whether the skills path is genuinely duplicated + the unified design.

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

## Open (agent to resolve in the spec, owner reviews)

- The ~8 colocation homes flagged in decision 3.
- The exact eval-result decay levels + turn-offsets (from source).
- The exact namespace-view aspect set + current-ns default view.
- Whether the skills path is genuinely duplicated + the unified design.

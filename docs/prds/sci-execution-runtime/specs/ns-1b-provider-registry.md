---
type: prd
status: active
tags: [prd, architecture, agent]
---

# NS-1b — provider registry + diffusiongemma relocation

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. As you work, answer two
questions and report them in your summary: (a) now that you've read the
source, is there a better seam than the one this spec names? (b) what do
the existing owners call each thing — use their exact terms, never a
new synonym. **Stopping early to report a concern or a better seam is
FREE** — the session resumes with full context, and seam corrections are
exactly what we want. If anything in this spec contradicts what you find
in the source, stop and report rather than improvising.

Read first: `src/seon/ai/AGENTS.md` (the localized authority — provider
contract, abort-signal rule, no call-site provider conditionals),
`docs/prds/sci-execution-runtime/research/namespace-hierarchy-design-2026-07-21.md`
§7.2b (the accepted mechanism), and the fence gate test NS-1a added
under `test/seon/`.

## Goal

Complete the diffusion fence: `seon.ai.diffusiongemma` moves into the
diffusion tree as **`seon.diffusion.gemma`**, and the two main-tree
files that statically require it (`seon.ai.dispatch`,
`seon.ai.typeahead`) stop doing so — replaced by ONE provider registry
in `seon.ai.dispatch`, filled by provider self-registration at
namespace load. This strengthens the existing dispatch in place (there
is no existing late-binding provider mechanism); it must not become a
second dispatch path. Owner decisions D9/D11/D12 approved; typeahead is
CORE by owner ruling (implementable without diffusion).

## Verified current state (grounded 2026-07-21, this session)

- `src/seon/ai/dispatch.cljs` is a static `case` over
  `(:seon.ai/provider config)` with static requires of every provider
  (`:8-15`, `adapter` fn `:40-67`): `:anthropic`, `:diffusiongemma`
  (with a `:seon.ai/dg-backend` sub-case — `:control` → dg adapter,
  else → openai-compat), `:typeahead` (gated on
  `diffusiongemma/api-configured?`), default → openai-compat. Every
  branch falls to `stub` when credentials are missing.
- The provider ENUM lives in `src/seon/ai/provider.cljc` (a leaf ns
  that breaks the planning/runtime cycle): `provider-locality` map
  `{:deepseek :frontier, :anthropic :frontier, :openai-compat :frontier,
  :diffusiongemma :local-worker, :typeahead :local-worker}` and
  `(schema/register! :seon.ai/provider (into [:enum] (keys …)))`.
  The design doc's "enum at ai.cljs:71" is stale — THIS file is the
  enum owner.
- `src/seon/ai/typeahead.cljs` requires dg at `:54`
  (`[seon.ai.diffusiongemma :as dg]`), speaks `::dg/*` wire keys at
  `:927-934` and `:1027` (`mode`/`prompt`/`policy`/`prefills`/
  `committed`/`draft`/`worker-output`), and — important — its own
  REGISTERED schemas reference dg-owned schema names at `:156`
  (`:seon.ai.diffusiongemma/offers`) and `:172`
  (`:seon.ai.diffusiongemma/policy`).
- Persisted `:seon.typeahead/*` step projections are typeahead-owned
  and MUST NOT change. Provider ids (`:diffusiongemma`, `:typeahead`),
  env names (`SEON_DG_*`), and the per-agent routing attribute
  `:seon.ai/agent-dg-backend` are config vocabulary — they do NOT
  rename with the namespace.
- `src/seon/ai/AGENTS.md` names `diffusiongemma.cljs` as an adapter —
  the localized authority must be updated in the same change.
- Other referencers of `seon.ai.diffusiongemma` exist beyond
  dispatch/typeahead — rg the full set (requires, quoted symbols,
  literal `:seon.ai.diffusiongemma/*` keywords, docstrings) before
  editing and enumerate them in your summary; tests included.

## Work

1. **Registry in `seon.ai.dispatch`** (strengthen in place): one
   process-local registry atom (legitimate — process wiring, not
   durable state) mapping provider id → descriptor with the fields the
   current `case` actually needs (at minimum an `agent-adapter` fn and
   a `configured?` fn; follow what the source needs, e.g. the
   dg-backend sub-selection belongs to the diffusion provider's own
   descriptor logic, not to dispatch). ALL providers register —
   anthropic, openai-compat, stub, typeahead, and (when loaded)
   the diffusion provider — so the registry is the one mechanism, not
   a diffusion special case. Unregistered or unconfigured selection
   falls to `stub` exactly as the missing-credentials branches do
   today. Selection semantics must be behavior-identical for every
   provider id.
2. **Enum derivation**: `:seon.ai/provider` in `provider.cljc` must not
   become a hand-maintained list that can drift from the registry.
   Reconcile the leaf enum with registration (e.g. locality data stays
   the leaf's, registration asserts membership, or the enum opens with
   a documented rule) — read both sides and pick the honest seam;
   report your choice. Do NOT create a dependency cycle (provider.cljc
   must stay a leaf below `seon.ai` and `my.plan.internal`).
3. **Step-backing contract in `seon.ai.typeahead`**: typeahead defines
   its own `:seon.ai.typeahead/*` step request/response vocabulary
   (including re-owning or locally defining the offers/policy shapes
   its registered schemas currently borrow from
   `:seon.ai.diffusiongemma/*`) and calls a registered step-backing fn
   instead of `dg/complete` with `::dg/*` keys. The diffusion provider
   translates typeahead terms ↔ its worker wire terms at its own
   boundary (producer/consumer translation, no third umbrella noun).
4. **Relocate**: `git mv src/seon/ai/diffusiongemma.cljs
   src/seon/diffusion/gemma.cljs`, ns → `seon.diffusion.gemma`; its
   `::`-keys follow the ns (safe: transient wire data, never persisted
   datoms — verify and report if you find a persisted exception). It
   self-registers its agent adapter and the typeahead step backing on
   load.
5. **Opt-in load door (D12)**: the default client build entry must NOT
   require the diffusion tree. Wire the opt-in as a build-override /
   preload entry require (the `seon.demo`-proven shadow door) so a
   diffusion-enabled build loads `seon.diffusion.gemma` and gets the
   registrations. Show in your summary exactly where the door lives
   and how it is activated.
6. **Fence gate**: after the move, the NS-1a fence test must PASS with
   its single eval.cljs allowlist row — dispatch/typeahead no longer
   require any diffusion ns. Do not weaken the gate.
7. **Docs**: update `src/seon/ai/AGENTS.md` (adapter list, registry
   mechanism, opt-in door) and `src/seon/diffusion/AGENTS.md`
   (membership row for `gemma`) in the same change.

## Owned paths (touch nothing else)

- `src/seon/ai/dispatch.cljs`, `src/seon/ai/provider.cljc`,
  `src/seon/ai/typeahead.cljs`
- `src/seon/ai/diffusiongemma.cljs` → `src/seon/diffusion/gemma.cljs`
- the build-config opt-in door (`shadow-cljs.edn` — the minimal edit
  for the D12 door only)
- `src/seon/ai/AGENTS.md`, `src/seon/diffusion/AGENTS.md`
- tests for dispatch/typeahead/gemma (rg for their test files; move or
  edit requires and dg-key references to the new contract)

Protected: everything else — in particular `seon.agent.turn` (the sole
retry authority), all other provider adapters' internals beyond their
one self-registration call, and the persisted `:seon.typeahead/*`
schema. Do not run `bin/seon`, do not commit; leave the diff for
orchestrator review. NOTE: `seon.ai.typeahead` is actively owned by the
repl-autosuggest lane on a separate checkout — keep the typeahead edits
MINIMAL and mechanical (contract keys + backing call), so the eventual
merge stays reviewable.

## Gates (run them; report honest results)

- `bin/test-cljs` focused ai/dispatch/typeahead/fence selectors, then
  the FULL suite once at the end.
- Both worker bundles still compile
  (`npx shadow-cljs compile worker-validator worker-oracle-eval`).
- rg proof: zero `seon.ai.diffusiongemma` tokens anywhere in
  `src/ test/` (any context).
- Behavior proof is the ORCHESTRATOR's (live step-loop round-trip needs
  a running cluster): your gate is tests + compile + the stub-fallback
  unit behavior — with no diffusion provider loaded, selecting
  `:diffusiongemma` or `:typeahead` falls to `stub`; assert an honest
  steering path exists that names the missing provider registration
  (follow the existing stub/steering idiom; do not invent a new error
  shape).

No feature loss: with the opt-in door active, the diffusion-backed
paths must behave identically at the wire.

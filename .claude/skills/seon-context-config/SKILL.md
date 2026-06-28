---
name: seon-context-config
description: "Customize what a seon agent SEES — its context blocks, skill loadout, and render caps. Use when editing config/system.edn or config/acme.edn, adding a manifest section, changing which skills/blocks load per cluster, working in seon.config / the :namespaces block / seon.agent.ctx default-seed-blocks, tuning a SEON_RENDER_*_CAP, or chasing why an agent's prompt shows (or omits) something. Carries the durable config/context footguns every Claude Code lane must know."
---

# Seon — Context & Config Customization

This is the **propagating channel for durable config/context footguns**. A gotcha
that bites every lane lives HERE (it auto-loads into every Claude Code session).
One-off live issues do NOT — they go in `docs/prds/agent-fsm/coordination.md` +
the task list with `file:line` (see [§ Where issues go](#where-issues-go)).

Source of truth (read before changing): `src/seon/config.cljs`,
`config/system.edn`, `config/acme.edn`, `src/seon/agent/ctx.cljs`
(`default-seed-blocks`), `src/seon/agent/ctx/namespaces.cljs`.

## How context is actually controlled — the seam

An agent's prompt is `default-seed-blocks` (in `seon.agent.ctx`) seed-COPIED into
the agent at creation, then priority-sorted at render. TWO independent dials shape
it, and they do DIFFERENT jobs:

- **Folder = corpus** (`SEON_SKILLS_DIR`, default `seon-skills/`). The boot scan
  loads ALL skills it finds. Dev/Claude-Code skills (this one,
  `browser-automation`, `clojure-testing`) live physically apart in
  `.claude/skills/`, so they are never in the agent corpus to begin with.
- **Config = per-cluster override** (`SEON_CONFIG` path, `SEON_PROFILE` `#profile`,
  `#env` interpolation). **Absent → the pod boots byte-identically to a no-config
  world.** Present → it curates per cluster. `config/system.edn` is the default;
  `config/acme.edn` is the acme harness's (proof of the per-cluster seam).

Config shapes exactly **three** things (each an optional manifest key with one
`resolve-*` fn in `seon.config`):

1. `:seon.config/skills` — `include`/`exclude` over the scanned corpus (per-cluster
   curation, e.g. a test cluster trimming the corpus). Not for dropping dev skills —
   the directory split already does that.
2. `:seon.config/loadouts` — per-role context loadouts merged over
   `default-seed-blocks`: `default-load` (skill bodies always-on, seeded as
   priority-16 `:skill/<name>` blocks), `blocks` (extra ctx blocks), `removes`
   (drop seeded blocks by name), `strategy` (`:replace` starts from empty).
3. `:seon.config/routes` — drop seeded `:seon.route/*` rows per cluster.

**Roles are a config SELECTOR (`:default`/`:root`/`:worker`), never a stored
`:kind`.** Root is identified by id `"root"` (`config/agent-role`), not a datom.

## Durable footguns (the reason this skill exists)

- **Config adds/removes/replaces WHOLE blocks only — it CANNOT render-trim WITHIN a
  block.** Whole-block removal moves ~5% of the prompt. The big `:namespaces` win
  (~64% / ~13k tok) needs the RENDER layer (issue #42), not config.
- **The `:namespaces` selection is HARDCODED today.** Which nses render full vs
  signature lives in `src/seon/agent/ctx/namespaces.cljs` as defs:
  `full-source-whitelist` (`#{:seon.agent.todo}`), `verb-signature-whitelist`
  (`#{:seon.agent.message :seon.agent.lifecycle}`), plus the rules "every `my.*` ns
  always full" and "current-ns always full". **Changing which nses an agent sees =
  a CODE edit there** (needs `bin/acme build` / cljs rebuild), NOT config — until
  #42 wires those whitelists to `seon.config` profiles (owner-directed).
- **Config is runtime EDN → NO rebuild.** Edit `config/*.edn`, then just
  `bin/seon restart pod` (or `bin/acme restart pod`). Only `.cljs`/`src` changes
  need `bin/acme build` first. Removed manifest rows fully vanish only after
  `bin/seon cluster reset <name>` (re-seeds the store).
- **An unknown manifest key fails LOUD.** `load-manifest` validates against
  `:seon.config/manifest` and throws a file-named error on a typo. That crash is
  INTENDED — never a silent ignore. A new section is the 4-step contract (register
  shape → add manifest key → write `resolve-*` → call at seed point).
- **Tokens, never chars.** Every display cap is the `SEON_RENDER_*_CAP` family and
  ALL of it flows through `seon.config` (`env-string`/`env-int` — the ONE typed env
  surface). Don't read `js/process.env` for a knob elsewhere; add an accessor here.
  Caps include `SEON_RENDER_RESULT_CAP`, `_MESSAGE_CAP`, `_EVAL_CAP`,
  `_TRANSCRIPT_TOKEN_CAP`, `_STORE_EDN_CAP`, and the `_VALUE_*` value-skeleton
  sub-family.

## How to do common edits

- **Drop/keep a skill body always-on per cluster:** edit `:seon.config/loadouts`
  `default-load` in the right `#profile`. (`config/system.edn` `:default` loads
  `[:repl]`; `:minimal` loads none. `config/acme.edn` `:default` loads the full
  set.)
- **Lean-context A/B without touching files:** `SEON_PROFILE=minimal bin/seon
  restart pod`. aero falls back to `:default` for any unmatched profile, so a bare
  boot is byte-identical to no profile.
- **Add an extra always-on block per cluster:** a `:seon.config/blocks` entry
  (a `:seon.agent.ctx/block` map — name + priority + a `:seon.render/ai` symbol)
  in the loadout. Upserted by name over the seed.
- **Change which nses render (full vs signature):** code edit in
  `ctx/namespaces.cljs` (see footgun above), not config — for now.

## Where issues go

- **Durable footgun** (bites every lane, survives across sessions) → add a bullet
  to [§ Durable footguns](#durable-footguns-the-reason-this-skill-exists) above.
  This file propagates to every Claude Code lane automatically.
- **Live cross-lane issue** (a specific bug, a mid-flight casualty) →
  `docs/prds/agent-fsm/coordination.md` + a tracked task with `file:line`.

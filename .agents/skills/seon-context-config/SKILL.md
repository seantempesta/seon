---
name: seon-context-config
description: "Customize what a seon agent SEES — its context blocks, skill loadout, and render caps. Use when editing config/system.edn or config/acme.edn, adding a manifest section, changing which skills/blocks load per cluster, working in seon.config / the :namespaces block / seon.agent.ctx, tuning a render cap, or chasing why an agent's prompt shows (or omits) something. Carries the durable config/context footguns every Codex lane must know."
---

# Seon — Context & Config Customization

This is the **propagating channel for durable config/context footguns**. A gotcha
that bites every lane lives HERE (it auto-loads into every Codex session).
One-off live issues do NOT — they go in `docs/prds/agent-fsm/coordination.md` +
the task list with `file:line` (see [§ Where issues go](#where-issues-go)).

Source of truth (read before changing): `src/seon/config.cljs`,
`config/system.edn`, `config/acme.edn`, `src/seon/agent/ctx.cljs`
(`seed-default-ctx!`), `src/seon/agent/ctx/namespaces.cljs`.

## The seam — ONE manifest file, per-cluster by path

- **ONE consolidated manifest** (`config/system.edn` default; `SEON_CONFIG=path`
  overrides). **A per-cluster variant is a SEPARATE edn file** — there is NO
  `#profile`, and **`SEON_PROFILE` is INERT in the pod config path** (`bin/seon`
  still exports it, but nothing in `seon.config` reads it; memoization keys on
  `SEON_CONFIG` only). `config/acme.edn` = the harness variant (minimal
  overrides), `config/test.edn` = tests.
- **Absent/empty manifest → the pod boots byte-identically to a no-config world**
  (every key optional; defaults live in the schemas + accessors). Present → it
  overrides only the keys it lists.
- **Folder = corpus** (`skills-dir`: manifest `:seon.config/skills`
  `:seon.config/dirs` first entry, else `SEON_SKILLS_DIR`, else `.Codex/skills`).
  The boot scan loads ALL skills it finds — with the default dir that is BOTH the
  agent skills (symlinks into `seon-skills/`) AND the dev/Codex skills
  (`browser-automation`, `datastar-web-ui`, this one). There is NO
  include/exclude curation — a cluster that wants a different corpus points
  `SEON_SKILLS_DIR` (or `:seon.config/dirs`) at its own dir.

## The manifest sections (`:seon.config/manifest`, config.cljs)

1. `:seon.config/agent-context` — the v3 two-level context config the GENERIC
   loader (`resolve-agent-context` → `seed-default-ctx!`) decodes and transacts
   at agent creation. SPARSE: the recursive default-value-transformer fills every
   absent key, so `{}` reproduces the default tree. Agent-level keys:
   - `:my.skills/load` — the always-on skill-BODY presence-set (default
     `[:repl]`), expanded by `expand-skill-blocks` into priority-16
     `:skill/<name>` blocks (the cached prefix, below cache-breakpoint 20).
     `[]` seeds no bodies. Consumed at seed, never an agent datom.
   - `:seon.eval/home-requires` — the home-ns toolbelt (each entry needs
     `:as`/`:refer`; bare `[ns]` is rejected). These requires ARE the verb
     surface a fresh agent sees (they render as compact cards — see below).
   - `:seon.client/wake?` — gates the wake trigger.
   - Per-agent LLM overlays — `:seon.ai/agent-provider` / `:seon.ai/agent-model`
     / `:seon.ai/agent-temperature` / `:seon.ai/agent-max-tokens` /
     `:seon.ai/agent-thinking` (default `:inherit` → the global
     `:seon.ai/config` env→DB row; an explicit value overrides that ONE agent —
     read by `seon.ai/effective-config-for`). Example: `config/acme.edn`.
   - `:seon.agent/ctx` — the block tree (see FOOTGUN below). The transcript
     block carries the eval-decay + eviction dials:
     `:seon.agent.ctx.transcript/result-decay` (age-offset → token-cap levels;
     default 0→16384, 2→1500, 5→200) and `::tiers` (age-band eviction; empty =
     render-all). Read reactively at render — transact and the next render
     re-bands, no apply step. `config/acme.edn` sets its own 2-band decay.
2. `:seon.config/root-context` — a SPARSE override merged over agent-context by
   IDENTITY (id `"root"`, never a stored kind): scalar keys merge, `:seon.agent/ctx`
   blocks upsert-by-name. Its `:live-tile` block sets root's canvas =
   `seon.render.system/system-view`.
3. `:seon.config/namespaces` — `{:seon.config/always [ns-syms]
   :seon.config/current-ns :full|:off}` (`resolve-namespaces`, key-level merge
   over the default). `:always` is the BOOT-STORAGE superset: those nses get
   their REAL FULL FILE SOURCE stored at boot so they CAN render full. Which
   nses actually render is per-agent (next section).
4. `:seon.config/render` — the global display caps (#46) as plain literals
   (`store-edn-cap` 16384, `eval-cap` 1500, `message-cap` 4000, the `value-*`
   skeleton bounds…), read via the `seon.config` accessors. An env override per
   knob is possible via aero's `#long #or [#env …]` tags in the manifest but is
   NOT pre-wired. (`SEON_RENDER_STRICT` — the fail-loud render dial — is a
   separate env-only flag.)
5. `:seon.config/skills` — only the `:seon.config/dirs` corpus-dir override.
6. `:seon.config/routes` — drop seeded `:seon.route/*` rows per cluster.

## What renders in the prompt body — curation, not compression

`namespaces-block` (compact-everything-except-current) — THREE rules, driven by
per-agent dials on the agent's `:namespaces` block entity (reactive datoms; a
`db/transact!` changes the next render, no restart):

- **FULL** (real whole-file source) — the agent's CURRENT ns
  (`::current-full?`, default true; policy `:current-ns :off` kills it) + any
  ns in the per-agent `::full-source` presence-set (default empty).
- **COMPACT CARD** — every ns the current ns `:require`s (schemas + one-line
  fn heads, ~3–5× smaller). So `:seon.eval/home-requires` is the lever for a
  fresh agent's visible toolbelt.
- **DROPPED** — everything else (still grep/render-namespace reachable);
  `*.internal` / `*-test` never render. `::with-tests` / `::current-tests?`
  ride the indexed test source along.

Signature-whitelist rendering is RETIRED — there is no hardcoded ns allow-list
in the renderer; token budget is bound by CURATION (which nses, via requires +
`::full-source` pins), never by a compressed rendering of a ns.

## Durable footguns (the reason this skill exists)

- **A manifest that supplies `:seon.agent/ctx` REPLACES the default block tree
  wholesale** — malli default-fill only fills ABSENT keys; vectors don't merge.
  To customize ONE block you must re-list EVERY default block you keep, or
  `:namespaces` and the rest silently vanish (see `config/acme.edn`'s comment).
  `:seon.config/root-context` blocks are the exception: upsert-by-name.
- **Config is runtime EDN → NO rebuild.** Edit `config/*.edn`, then
  `bin/seon restart pod` (or `bin/acme restart pod`). Only `.cljs`/`src` changes
  need `bin/acme build` first. The agent-context is SEED-COPIED at creation —
  existing agents keep their blocks; removed manifest rows fully vanish only
  after `bin/seon cluster reset <name>`.
- **An unknown manifest key fails LOUD.** `load-manifest` validates against
  `:seon.config/manifest` and throws a file-named error on a typo. That crash is
  INTENDED. A new section is the 4-step contract (register a
  `:seon.config/<section>` shape → add the manifest key → write `resolve-*` →
  call at the seed point).
- **Config reads are memoized per `SEON_CONFIG`** (`namespaces-policy`,
  `render-config`). A dev edit is picked up on the next hot-reload (the caches
  are `def`, not `defonce`) or a pod restart — not mid-process.
- **Tokens, never chars.** Any size shown to a human/agent is
  `seon.ai.tokens/estimate`. Process knobs go through `seon.config`
  (`env-string`/`env-int`) — never a raw `js/process.env` read elsewhere.

## How to do common edits

- **Skill bodies always-on per cluster:** set `:my.skills/load` in
  `:seon.config/agent-context` (acme loads the full set; `[]` = none).
- **Change a fresh agent's verb surface:** edit `:seon.eval/home-requires`
  (acme swaps in `acme.helpers`/`acme.notes` with zero src edits).
- **Lean-context A/B:** a variant = a SEPARATE edn file (override only the
  keys you change) — `SEON_CONFIG=config/<variant>.edn bin/acme restart pod`.
- **Pin a ns full for one agent:** transact `::full-source` onto its
  `:namespaces` block (or agent entity) — reactive, no restart.
- **Per-agent LLM:** set `:seon.ai/agent-provider` etc. in the agent-context
  (`:inherit` = the global row) — or transact the datom onto a live agent.
- **Tune a render cap:** edit the literal in `:seon.config/render`, restart pod.

## Where issues go

- **Durable footgun** (bites every lane, survives across sessions) → add a bullet
  to [§ Durable footguns](#durable-footguns-the-reason-this-skill-exists) above.
  This file propagates to every Codex lane automatically.
- **Live cross-lane issue** (a specific bug, a mid-flight casualty) →
  `docs/prds/agent-fsm/coordination.md` + a tracked task with `file:line`.

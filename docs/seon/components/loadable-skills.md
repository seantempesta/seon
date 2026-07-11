---
type: component
status: active
tags: [component, agent, context]
---

# Loadable skills (`my.skills`)

> Knowledge an agent dials INTO its own context only while it needs it, then
> drops so it stops paying for what it isn't using.

A skill is **not a new subsystem** — a loaded skill IS a
`:seon.agent.ctx/block`. `my.skills/load` calls
[[components/context]]-sibling `seon.agent.ctx/install!` to put a `:skill/<name>`
block on the agent's own `:seon.agent/ctx`; `unload` calls `remove!`. The body
rides the existing file-block read+quote path, and its token cost is DERIVED at
render — nothing stored that needs clearing (reactive context).

## Namespace

| Namespace | File | Role |
|-----------|------|------|
| `my.skills` | `src/my/skills.cljs` | corpus scan + seed, `load`/`unload`/`list` functions, L0 catalog + L2 body render fns |

## The model — attributes, not a `:kind`

A row "is a skill" because it carries `:my.skills/name` (the
`:db.unique/identity` catalog key AND the load/unload handle). It is
"file-backed" because it carries `:seon.agent.ctx/file-path` (body stays in the
SKILL.md, read fresh every render) and "inline/agent-authored" because it
carries `:my.skills/body` instead. Where it came from is `:seon.db/origin` on
the seeding tx, not a field. `loaded?` is a pure projection of the agent's own
`:skill/<name>` blocks, never a stored flag.

## Two disclosure levels

- **L0 catalog** (`catalog-block`, priority 12 — the CACHED prefix) — one
  always-on `;`-line per skill (name + description + a derived ●/○ loaded
  marker). Cheap discovery; the body costs nothing until loaded. Drops to `""`
  when no skill rows exist.
- **L2 body** (`skill-block`, priority 30 — the VOLATILE band, so load/unload
  never busts the cacheable static prefix) — the whole SKILL.md (frontmatter
  stripped), `;`-commented to keep the prompt eval-valid, with a DERIVED
  token-cost footer + an explicit unload hint. Reactive: if the row is retracted
  or the file vanishes, the body resolves blank → the block drops.

## The corpus — `seon-skills/` (agent) vs `.claude/skills/` (dev)

At boot `seed-skills-tx-data` scans `skills-dir` (env `SEON_SKILLS_DIR`, default
`seon-skills/`) and emits one identity-upsert row per `SKILL.md`. No YAML/markdown
parser: a ~10-line scanner pulls only the frontmatter `name` + `description`; the
body stays in the file. Drop a standard `<name>/SKILL.md` in there and it appears;
edit it and the agent gets the edit.

**ONE corpus, split by consumer on disk.** `seon-skills/` is the dedicated AGENT
corpus (datahike, clojurescript, repl, data-oriented-clojure, ui-live-tiles, …);
`.claude/skills/` holds the Claude-Code/dev skills (browser-automation,
clojure-testing) that an agent should NOT load, **plus symlinks back to the shared
seon-skills entries** so Claude Code reads them natively too. The directory split —
not an exclude list — is what keeps dev skills out of the agent catalog. The shared
skills were curated seon-current (off the paused-JVM stack onto the active pod):
datastar-web-ui, clojure-testing, browser-automation, and the authored
data-oriented-clojure.

`list-skill-files` resolves each entry with **`statSync` (which FOLLOWS
symlinks)**, not the `readdirSync` Dirent flags — a `<dir>/<name>` that is a
SYMLINK to a skill directory reports `.isDirectory? = false` on its Dirent and
would be silently dropped. `.claude/skills` symlinks the shared `seon-skills/*`
dirs in exactly this shape, so following links is mandatory.

## Config override — `seon.config` curates + seeds (no code change)

`seon.config` (`src/seon/config.cljs`, aero-backed `config/system.edn`,
`SEON_CONFIG` path + `SEON_PROFILE` #profile + `#env`) is an OPTIONAL manifest that
overrides the dir scan + the default seed without touching code. ABSENT → the pod
boots byte-identically (full env-dir scan + `default-seed-blocks`). PRESENT it
shapes three things:

- **skill corpus** (`:seon.config/skills` `include`/`exclude`) — per-cluster
  curation over the scanned `seon-skills/` rows (e.g. a test cluster trimming the
  corpus). Resolved by `resolve-skill-rows`.
- **per-role loadouts** (`:seon.config/loadouts`, role `:default`/`:root`/`:worker`)
  — a `default-load` set whose skill BODIES are seeded always-on as `:skill/<name>`
  blocks at the cached-prefix priority (16, between the L0 catalog and
  `:namespaces`), merged over `default-seed-blocks` by upsert-on-name; plus extra
  `:blocks` and `:removes`. Resolved by `resolve-loadout`. The shipped manifest
  default-loads `:repl` for every agent.
- **routes** (`:seon.config/routes` `:removes`) — drop seeded `:seon.route/*` rows
  per cluster. Resolved by `resolve-routes`.

Role is a config-composition SELECTOR (`:root` for id `"root"`, else `:worker`),
never a stored `:seon.agent/role`/`:kind`. A new config concern is four mechanical
steps (register a `:seon.config/<section>` shape, add its manifest key, write one
`resolve-<section>` fn, call it at the seed point); an unknown manifest key fails
LOUD at validation. NOTE: there are now two `seon.config` namespaces in different
lanes — this pod `.cljs` manifest reader and the `[JVM track — paused]` aero
Integrant loader (`config.clj`); they share the aero + `system.edn` mental model.

## Functions

`load`/`unload` are `^:async` (they await the `install!`/`remove!` transact); the
eval path auto-awaits, so an agent calling `(my.skills/load :datahike)` gets the
result MAP, not a Promise. `list` is a synchronous derived query — the catalog
with each skill's `::loaded?` against the agent's own blocks. Functions are
errors-as-values (`{::ok? false ::message …}` when no such skill / install fails).

## Key files

- `src/my/skills.cljs`
- `seon-skills/**/SKILL.md` (the agent corpus, `SEON_SKILLS_DIR`)
- `.claude/skills/**/SKILL.md` (Claude-Code/dev skills + symlinks into `seon-skills/`)
- `src/seon/config.cljs` + `config/system.edn` (the optional curation/loadout override)
- relies on `seon.agent.ctx/install!`/`remove!` ([[components/context]] CLJS
  sibling) + `seon.ai.tokens/estimate` (the one token estimator)

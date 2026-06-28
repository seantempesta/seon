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
| `my.skills` | `src/my/skills.cljs` | corpus scan + seed, `load`/`unload`/`list` verbs, L0 catalog + L2 body render fns |

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

## The corpus

At boot `seed-skills-tx-data` scans `skills-dir` (env `SEON_SKILLS_DIR`, default
`.claude/skills` — the SAME directory humans edit) and emits one
identity-upsert row per `SKILL.md`. No YAML/markdown parser: a ~10-line scanner
pulls only the frontmatter `name` + `description`; the body stays in the file.
Drop a standard `<name>/SKILL.md` in there and it appears; edit it and the agent
gets the edit.

`list-skill-files` resolves each entry with **`statSync` (which FOLLOWS
symlinks)**, not the `readdirSync` Dirent flags — a `<dir>/<name>` that is a
SYMLINK to a skill directory reports `.isDirectory? = false` on its Dirent and
would be silently dropped. `.claude/skills` symlinks the shared `seon-skills/*`
dirs in exactly this shape, so following links is mandatory.

## Verbs

`load`/`unload` are `^:async` (they await the `install!`/`remove!` transact); the
eval path auto-awaits, so an agent calling `(my.skills/load :datahike)` gets the
result MAP, not a Promise. `list` is a synchronous derived query — the catalog
with each skill's `::loaded?` against the agent's own blocks. Verbs are
errors-as-values (`{::ok? false ::message …}` when no such skill / install fails).

## Key files

- `src/my/skills.cljs`
- `.claude/skills/**/SKILL.md` (the corpus; symlinks into `seon-skills/`)
- relies on `seon.agent.ctx/install!`/`remove!` ([[components/context]] CLJS
  sibling) + `seon.ai.tokens/estimate` (the one token estimator)

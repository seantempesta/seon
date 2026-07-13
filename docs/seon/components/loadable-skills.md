---
type: component
status: active
tags: [component, agent, database]
---

# Loadable skills (`my.skills`)

Skills are optional imported reference material, not a standing context
subsystem. The default and test agent context trees contain no skills block.
Normal discovery comes from namespace cards, current source, database-derived
context blocks, and the manuals those functions point to.

## Current database model

An imported skill row is identified by `:my.skills/name`. Its description is
stored as `:my.skills/description`; the current checkout-backed importer stores
`:seon.agent.ctx/file-path` to its `SKILL.md`. A row is recognized by attribute
presence, not a type or kind field. Import provenance belongs to transaction
metadata.

Pod boot calls `my.skills/seed-skills-tx-data` and reconciles the selected
directory into the database by identity. Rows absent from the desired import
set are retracted. The selected directory is, in order:

1. the first `:seon.config/dirs` entry under `:seon.config/skills`;
2. `SEON_SKILLS_DIR`; or
3. `.claude/skills` when neither is supplied.

`config/system.edn` explicitly selects `seon-skills/`, so the shipped runtime
corpus does not depend on either development-tool adapter directory.

The importer intentionally reads only `name` and `description` from
frontmatter. The body is rendered through the existing context file reader when
explicitly loaded; it is not copied into every agent prompt.

## Loading is the normal context-block mechanism

`my.skills/load` installs one `:skill/<name>` block on the current agent's
`:seon.agent/ctx`. `my.skills/unload` removes that block. `my.skills/list`
queries the catalog and derives whether each name is loaded from block presence;
there is no stored loaded flag, acknowledgement record, or skill-specific event
bus.

The functions return data envelopes. `load` and `unload` are asynchronous but
the agent evaluation boundary awaits them, so callers receive the result map.

```clojure
(my.skills/list)
(my.skills/load :datahike)
(my.skills/unload :datahike)
```

The loaded block uses the same render, token estimate, ordering, and reactive
database path as every other context block. Removing it removes its prompt cost
on the next context render.

## What is disabled by default

There is no always-on catalog block, implicit body load, config loadout, or role
selector. The manifest's `:seon.agent/ctx` vector is the complete initial block
tree. `config/system.edn` contains namespaces, canvas, plan, and transcript; it
does not contain a skills context block.

The system instruction may mention the qualified `my.skills/load` escape hatch,
but that is a pull reference, not injected skill content.

## Corpus and development-tool adapters

The directories currently serve different consumers:

| Directory | Consumer |
|---|---|
| `seon-skills/` | runtime import corpus selected by the shipped manifest |
| `.agents/skills/` | Codex project-skill discovery |
| `.claude/skills/` | Claude Code project-skill discovery and the unconfigured runtime fallback |

Runtime-importable skills originate in `seon-skills/`; `bin/seon skills sync`
generates their exact `.agents` and `.claude` adapter trees. Codex-only operator
skills originate in `.agents/skills/` and generate their matching Claude
adapters. Claude-only skills are outside that graph and remain untouched.

`bin/seon skills check` reports every changed, missing, or extra adapter file.
The operator suite runs the same check, so a tool-facing copy cannot quietly
become another authority. A symlink is not assumed safe because project-skill
discovery is owned by external tools whose link traversal contracts Seon does
not control.

## Remaining database-ownership gap

The current row points at a checkout file, so a config-free restore cannot
render that body after the original corpus disappears. The intended refactor is
to transact canonical name, description, and content (or a content-addressed
blob reference) at import time. After that transaction, the database is the
source of truth and the original directory is only an optional future import
input.

That work must refine `my.skills` in place. Do not introduce a parallel skill
loader, a second block collection, or an atom-backed registry.

## Key files

- `src/my/skills.cljs` — scanner, database rows, list/load/unload functions
- `src/seon/config.cljs` — selected corpus directory
- `src/seon/client.cljs` — boot desired-state reconciliation
- `src/seon/agent/ctx.cljs` — shared context file rendering
- `config/system.edn` — shipped corpus selection and initial context tree
- `script/seon/dev/skills.clj` — deterministic adapter generation and drift gate
- `test/my/skills_test.cljs` — current behavioral coverage

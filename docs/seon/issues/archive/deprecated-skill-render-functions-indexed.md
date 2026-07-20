---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, index]
---

# Deprecated skills and context functions remain eligible for program indexing

## Problem

`my.skills` and `seon.agent.ctx` functions were explicitly deprecated in
their docstrings, but remained public first-party functions. The build-derived
program index therefore exposed them as callable function rows and potential
context/autocomplete distractors. Some context functions also still had
source and test callers, so neither blind deletion nor ignoring the stale
deprecation claim was sound.

## Evidence

- `src/my/skills.cljs` and `src/seon/agent/ctx.cljs` began four docstrings
  with `DEPRECATED` (`skill-block`, `file-block`, `file-block-ai`,
  `file-block-html`). The note originally counted five; the fifth,
  `seon.config/identity-file-blocks`, was already deleted in c35677fa.
- `src/seon/indexing.clj::public-fn-vars` intentionally indexes every public
  first-party function in the build require closure. It has no deprecation
  concept, so the functions remained eligible by construction.
- `tool-surface-overhaul-2026-07-12.md` observed the skills functions in the
  exported index as stale-card distractors.

## Owner

`src/my/skills.cljs` and `src/seon/agent/ctx.cljs` own whether each function is
canonical or retired. The one analyzer/program-graph indexing mechanism owns
any general callability rule.

## Resolution (2026-07-20)

Audit verdicts — all four functions are live mechanisms; every `DEPRECATED`
marker was a false claim and has been removed:

- `my.skills/skill-block` — CANONICAL. It is the `:seon.render/ai` slot that
  `my.skills/load` installs as the agent's `:skill/<name>` context block
  (`src/my/skills.cljs` load, line ~204) and is exercised directly by
  `test/my/skills_test.cljs`.
- `seon.agent.ctx/file-block` + `file-block-ai` + `file-block-html` — LIVE
  MECHANISM, currently without shipped users. They are the one way a config
  manifest or `ctx/install!` declares a file-backed context section. The
  former auto-seeding path (`seon.config/identity-file-blocks`, which emitted
  these render symbols for SOUL.md/AGENTS.md) was deleted in c35677fa
  (2026-07-11); no shipped `config/*.edn` manifest declares a file-block and
  the live default cluster has no render node carrying these symbols
  (REPL query over `:seon.render/ai`: zero `seon.agent.ctx/file-block*`
  rows). Their docstrings now state exactly that instead of claiming
  deprecation; deleting them would remove the only file-backed-section
  capability with no replacement (file-backed skills are a different,
  catalog-scoped mechanism), which is an owner-level product decision, not a
  cleanup.

With no function left carrying a deprecation claim, the indexing acceptance
items (a general `:seon.fn` lifecycle attribute, eligibility filtering, and
absent-from-callable-surface proof for retired functions) are moot: nothing
is retired, so nothing needs filtering.

Proof: `bin/seon test changed --path src/seon/agent/ctx.cljs --path
src/my/skills.cljs` — 46 namespaces (including `my.skills-test`,
`seon.agent.ctx.canvas-test`, `seon.agent.ctx.menu-test`,
`seon.agent.ctx.namespaces-test`, `seon.agent.ctx.transcript-test`,
`seon.ctx-test`), 513 tests / 2457 assertions, 0 failures, 0 errors.

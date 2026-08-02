---
type: issue
status: open
severity: blocker
tags: [issue, skills, rendering, context]
---

# Re-ground the web UI skills in the live namespace-page renderer

## Problem

The maintained `datastar-web-ui` skill still teaches the pre-W4 route and
render boundary. It explicitly says Reitit and debug pages/feeds are not
current, omits the canonical namespace routes, and calls the broader context
UI tabled. `ui-canvas` repeats the stale dependency claim that context
rendering has not yet been understood.

This is direct negative guidance: an agent obeying it will reject the live
route owner, debug surface, and namespace-page contract as nonexistent target
work. The route facts are not database-backed, and generalized canvas controls
remain absent; those surviving cautions must not be lost while correcting the
rest.

## Evidence

- `.agents/skills/datastar-web-ui/SKILL.md:23-38` lists only `/`, agent,
  feed, message, data, and static resources, then says Reitit and debug pages
  are not current.
- The current one route table is Reitit data in
  `src/seon/render/route.clj:1-34`. It includes `/ns/{namespace}`,
  `/ns/{namespace}/debug`, and `/agent/{id}/debug` alongside the aliases and
  feed.
- `src/seon/render/web.clj:1041-1087` renders the two-pane AI/HTML debug page
  and resolves canonical namespace pages through their owning agents;
  `src/seon/render/web.clj:1198-1220` binds the route table into the one Reitit
  Ring handler.
- `.agents/skills/datastar-web-ui/SKILL.md:91-108` still labels debug pages as
  target-only and says UI is tabled until context rendering is understood.
  `.agents/skills/ui-canvas/SKILL.md:39-60` repeats that prerequisite.
  The active ledger instead records namespace pages, the two-pane debug, and
  context-as-one-visible-walk as live in
  `docs/prds/sci-execution-runtime/plan/unsettled.md:250-263`.
- The current shell and message form remain real, and no `my.canvas`, `/call`,
  or generalized control API exists. This issue is not authority to invent
  those surfaces.

The reader chain is repository-wide. `docs/TRANSFER_PROMPT.md:122` routes web
and canvas work to these skills, and `.claude/skills` plus `seon-skills` are
symlinks to `.agents/skills`. `bin/test:16-31` refuses a checkout where those
three trees do not resolve to the same bytes. One stale skill therefore
teaches Codex development, Claude development, and imported/runtime readers.

No existing open issue records this post-W4 drift. The archived
`datastar-skill-cited-archived-namespace-ui-authority` and
`five-skills-teach-the-deleted-pod-system` notes describe earlier repairs that
this later implementation wave has now outgrown.

## Owner

The `datastar-web-ui` and `ui-canvas` skills, including
`datastar-web-ui/references/design-principles.md`, reviewed against the current
`seon.render.route`, `seon.render.web`, render walk, active ledger, and target
architecture.

## Acceptance

- The Datastar skill names the exact live route table, Reitit owner,
  namespace aliases, debug variants, and current walk-based AI/HTML rendering.
- Built mechanisms and still-tabled mechanisms are separated individually;
  no blanket pre-W4 statement hides current namespace/debug behavior.
- The canvas skill keeps the truthful refusal of nonexistent generalized
  controls while removing fulfilled context-mining prerequisites from its
  current-state explanation.
- Every current claim carries checked source lines, and an independent reader
  verifies the skill through both symlinked audiences.

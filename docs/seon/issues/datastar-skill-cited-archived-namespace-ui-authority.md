---
type: issue
status: open
tags: [web, issue]
severity: friction
---

# Datastar skill cited archived namespace UI authority

## Problem

After the namespace-UI PRD was archived, the maintained Datastar web-UI skill
still named its design-system document as a current authority and retained a
now-broken pre-archive path.

## Evidence

`.agents/skills/datastar-web-ui/SKILL.md` names
`docs/prds/namespace-ui/design-system.md` in its theme guidance and key
references. Hidden `.agents/` content was not covered by the archive lane's
ordinary repository sweep.

## Owner

The Datastar skill owns current web-UI workflow guidance. Maintained theme
tokens live in `resources/public/css/input.css`; detailed design guidance lives
in the skill's own `references/design-principles.md`.

## Acceptance

- The active skill cites only maintained authorities for current behavior.
- No active source, localized authority, or skill cites the archived PRD as
  current design authority.
- The repository Markdown validator passes for the changed documents.

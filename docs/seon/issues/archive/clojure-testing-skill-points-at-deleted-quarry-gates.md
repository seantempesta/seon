---
type: issue
status: resolved
severity: cleanup
tags: [issue, skills, testing, tooling]
---

# Remove deleted quarry gates from the Clojure testing skill

## Problem

The `clojure-testing` skill says `bin/test-cljs` and `bin/test-writer` still
serve the `src-old`/`test-old` quarry. Neither script exists. The intended
message—fresh JVM correctness has one `bin/test` gate and CLJS is off—is right,
but the sentence gives two dead entry points apparent reader authority.

## Evidence

- `.agents/skills/clojure-testing/SKILL.md:18-20` names both scripts as
  serving quarry tests.
- `bin/test-cljs` and `bin/test-writer` are absent from the checkout; the only
  matching live test entries are `bin/test` and the separate stale
  `bin/test-parser` reader that also mentions `bin/test-cljs`.
- Root `AGENTS.md` states that `bin/test` is the one fresh-system correctness
  gate and `src-old`/`test-old` are disabled quarry. The `clojurescript` skill
  correctly teaches source archaeology without claiming a runnable CLJS gate.

The reader chain reaches every test author because root `AGENTS.md:865` and
the active PRD runbook require `clojure-testing` for test mechanics. The three
skill paths resolve to one canonical file.

No current issue note names this exact surviving reader. If the dead-code
audit independently finds `bin/test-parser` obsolete, both readers should be
cut in one outermost-reader-first wave rather than restoring either deleted
gate.

## Owner

The `clojure-testing` skill together with the owner of the separate
`bin/test-parser` reader chain.

## Acceptance

- The testing skill names only executable current gates.
- Quarry status is explained without suggesting a deleted command exists.
- `rg 'test-cljs|test-writer'` over live instructions and scripts either finds
  no current reader or only explicitly historical evidence.
- Nothing restores a CLJS or writer gate to satisfy the stale sentence.

## Resolution

Resolved by `683158bca`. The testing skill now names `bin/test` as the sole
fresh JVM correctness gate, identifies `src-old` and `test-old` as disabled
quarry without executable gate claims, and cites the current launcher and
aliases (`.agents/skills/clojure-testing/SKILL.md:15-23`). Skill validation
passes. The separate protected `bin/test-parser` reader still contains its own
historical `bin/test-cljs` reference; this repair did not restore or edit that
outer reader.

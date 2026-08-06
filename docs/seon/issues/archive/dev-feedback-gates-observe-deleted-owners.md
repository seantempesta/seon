---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, deletion, testing]
---

# Delete dev feedback gates that observe deleted owners

## Problem

Two active edit-feedback rules read owners deleted with the pod/CLJS system.
Both turn absent evidence into a clean result: the core-fault gate ignores the
database fault committer and the skill-floor rule compares against an empty
corpus.

## Evidence

- `bin/seon-hook:998-1020` claimed core faults land in `logs/pod.log`, reread
  `SEON_CONFIG`/`config/default.edn`, and recognized retired
  `:gate/:crash/:log` values.
- `bin/seon-hook:1022-1065` ran only when the obsolete log existed and caught
  internal failures as `nil`; `:1138-1141` still invoked it after Clojure
  edits.
- `script/seon/dev/markdown.clj:598-611` named deleted
  `src/seon/agent/ctx.cljs` as the live system-text floor.
- `script/seon/dev/markdown.clj:629-648` converted the missing file into an
  empty n-gram set; `:705-713,739` kept the rule enabled.
- The owning hook/Markdown tests mentioned neither stale mechanism.

## Owner

The database-backed fault committer and the current instruction-block facts.

## Acceptance

Delete both rules unless their current owners can publish exact evidence to
the existing feedback pipeline. Missing evidence fails honestly rather than
meaning “no fault” or “no duplication,” and no edit hook reads runtime config
from files or environment variables.

## Resolution

Resolved by audit-finding-9 commit `92661b9f6`. The hook no
longer reads pod logs, environment configuration, or a file manifest to infer
core faults, and the Markdown checker no longer compares skills with the
deleted CLJS floor. Exact-name searches over first-party source and skills find
no surviving reader for either closure. Proof: the focused Markdown and edit
feedback suites ran 34 tests / 394 assertions with zero failures and errors;
the selector-derived instrumentation suite ran 15 / 68 with zero reds.

---
type: issue
status: open
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

- `bin/seon-hook:833-867` claims core faults land in `logs/pod.log`, rereads
  `SEON_CONFIG`/`config/default.edn`, and recognizes retired
  `:gate/:crash/:log` values.
- `bin/seon-hook:867-902` runs only when the obsolete log exists and catches
  internal failures as `nil`; `:967` still invokes it after Clojure edits.
- `script/seon/dev/markdown.clj:598-611` names deleted
  `src/seon/agent/ctx.cljs` as the live system-text floor.
- `script/seon/dev/markdown.clj:629-648` converts the missing file into an empty
  n-gram set; `:705-713` keeps the rule enabled.
- The owning hook/Markdown tests mention neither stale mechanism.

## Owner

The database-backed fault committer and the current instruction-block facts.

## Acceptance

Delete both rules unless their current owners can publish exact evidence to
the existing feedback pipeline. Missing evidence fails honestly rather than
meaning “no fault” or “no duplication,” and no edit hook reads runtime config
from files or environment variables.

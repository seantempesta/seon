---
type: issue
status: resolved
severity: friction
tags: [issue, test, sci, reader]
---

# Reader policy test used refused SCI init options

## Problem

`explicit-reader-policy-overrides-a-hostile-sci-context` built its hostile
context by passing `:readers` and `:read-eval` to `sci/init`. Maintained SCI
commit `f934044` deliberately made unknown init options a loud refusal, so the
fixture failed during context construction and every read appeared as
`:seon.sci.reader/unreadable`. It no longer exercised Seon's explicit
per-parse reader policy.

## Resolution

Commit `56c2d7b71` constructs a valid SCI context and then adds hostile context
values. `seon.sci.reader` still supplies its accepted-tag and read-eval policy
directly to every `parse-next+string` call. The existing regression again
proves built-in tags, refused tags, `#=`, and caller-supplied tag readers, with
no production change.

---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# References in the task with no declared render

| Reference in the task | Kind | What an agent gets today | Keep it in the brief? |
|---|---|---|---|
| `d/q` (prose), meaning `datahike.api/q` | alias-qualified dependency Var | `(doc datahike.api/q)` answers `:seon.sci.eval/documentation-unavailable` with an 11-key error map | Name it as the comparison baseline only, never render it |
| `cefd8a2bf` ("db? was fixed in the fork") | commit id | nothing; `git show` is outside the REPL | No: history, which belongs in evidence |
| `docs/research/agent-platform/landing/fix-write-cost-2026-09-26.md` | evidence path | `(my.fs/stat …)` / `(my.fs/read …)` | As a path only, not content (the schema says "linked, never parsed") |
| `docs/research/agent-platform/perf-profile-2026-09-26.md` | evidence path | `(my.fs/stat …)` answered 11,197 bytes, 79 ms | Same |
| `"d6e3b775f474"` inside `seon.db/q`'s `:seon/tasks` | task handle (installed tag syntax) | a string | No: the brief already is the task |
| `encode-query-request`, `decode-query-result`, `query-value-type-error`, `query-attribute-error` (prose, unqualified) | bare private names | ambiguous; `seon.db/query-attribute-error` does not exist on default | Only if qualified and resolving; they are cost evidence, not subjects |

The task brief should list unresolvable references once, as data
(`{:ref "query-attribute-error" :resolves-to :nothing}`), so the agent knows a name is stale rather than
searching for it.

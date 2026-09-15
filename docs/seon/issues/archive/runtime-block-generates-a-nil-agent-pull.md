---
type: issue
status: resolved
severity: blocker
tags: [issue, render, runtime, wave/agent-context]
---

# The runtime block generates a pull with a nil agent identity

## Problem and evidence

Observed on default PID 83040 after development adoption, 2026-09-10
01:38:26–01:38:34 UTC. The authorized loop probe message
`loop-live/runtime-default-2026-09-09` settled as ordinary turn
`9bceed705a33` in 7,900 ms, stored four evaluations, decremented turns-left
to 19, and committed no new core fault.

Evaluation `9811cc17667f` stores a generated `seon.db/pull` over
`:seon.agent/runtime`, selecting its turns, trigger, and listens. Its
lookup is **`[:seon.agent/id nil]`**. The stored evaluation error is:

```
seon.db/pull received nil for :seon.agent/id, whose installed value type is :db.type/string.
```

## Owner

This is a generated-form/renderer boundary, not a failure to close the
ordinary turn. `src/seon/render/transcript.clj` and the runtime schema were
concurrently held by context-cookbook; the loop lane did not edit them.

## Acceptance

Follow the runtime component's actual owner ref when constructing the
lookup, and execute that exact generated form in the canonical fixture.
An absent owner must never become a generated nil lookup.

The exact source and terminal facts are retained with the
[loop live landing note](../../../prds/context-generation/research/loop-live-landing-2026-09-09.md).

## Resolution (2026-09-15 triage)

surface: context-generation

Fix `fd8646edd` introduced runtime ownership resolution. At HEAD `859c9258c`, `src/seon/render/transcript.clj:1058–1077` follows `:seon.runtime/agent`, falls back to an explicitly supplied agent id, and returns a typed diagnostic if neither resolves. `:1083–1097` returns that diagnostic before constructing source, otherwise places the resolved id into the lookup. Verified with `git show HEAD:src/seon/render/transcript.clj` and `git log -S runtime-owner -- src/seon/render/transcript.clj`. The missing-owner path can no longer construct the recorded nil lookup.

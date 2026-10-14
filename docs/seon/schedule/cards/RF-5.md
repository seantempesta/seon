---
title: "Card RF-5 Delete the hook's disabled Gemini review batch"
type: reference
status: done (dbd488520)
created: 2026-10-08
---

# RF-5 Delete the hook's Gemini review batch

`bin/seon-hook` schedules an asynchronous `agy -p` review of edited files (`schedule-gemini-review!`,
`run-review-worker!`, `call-agy`, the pending-file lock and worker files, about 400 lines from
`;;; Rate-limited Gemini review`). It is disabled (`.claude/seon-hook.edn` `:review :enabled false`). When enabled
it is a provider call on every edit window, which the default-no-paid-calls rule forbids; reviews are dual
Opus + Astra lanes now. Delete the section, its config keys and its tests (`seon.dev.hook-test` rows naming the
review), or root keeps it with a stated consumer. Lane: Sonnet.

## Proof

`seon.dev.hook-test` green after the deletion; `rg -n "agy|review" bin/seon-hook` empty.

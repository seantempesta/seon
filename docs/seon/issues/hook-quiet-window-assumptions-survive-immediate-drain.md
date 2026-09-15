---
type: issue
status: open
severity: cleanup
tags: [issue, docs, wave/dev-tooling-face-hygiene]
---

# Retire the quiet-window claim in AGENTS.md

## Problem

AGENTS.md §6 still says a configured quiet window coalesces edits. The
approved hook drains immediately and merges edits arriving during a
publication into one pending successor batch.

## Evidence

The current `.claude/seon-hook.edn` no longer declares `:quiet-seconds`,
and `run-source-worker!` no longer consults a quiet timer.

The stale integration-test assumption was fixed in `db7e653ca`. Its event
probe injects six editor requests covering five distinct paths while the
first publication is active. The default REPL invoked that exact Babashka
probe: **2** publications, **6** responses naming the same successor,
**5** successor paths, **2** terminal refusals, and no remaining pending
batch or worker claim. No timer schedules admission. No test JVM was
launched; the orchestrator's canonical gate is still requested.

The owned regression `seon.dev.hook-test/idle-edit-starts-without-quiet-delay`
exercises the actual pending-file/lock/drain owner and proves exactly one
successor for edits submitted during a publication. Its before/after probe
is recorded in [the landing note](../../prds/context-generation/research/slow-surfaces-plan-2026-09-15.md#landing).

## Owner

AGENTS.md §6. The authorized test scope extension is complete; this note
is now limited to the remaining documentation claim.

## Acceptance

AGENTS.md describes immediate draining and one pending successor instead
of a quiet window.

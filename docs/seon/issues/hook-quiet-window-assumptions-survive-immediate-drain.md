---
type: issue
status: open
severity: friction
tags: [issue, test, docs, wave/dev-tooling-face-hygiene]
---

# Retire quiet-window assumptions after immediate hook draining

## Problem

The approved hook drains immediately and merges only edits arriving before
the next publication starts. A burst of independent editors is no longer
guaranteed to produce exactly one publication.

## Evidence

`test/seon/dev/edit_feedback_test.clj:88` still requires exactly one result
for five editors and assumes the worker claim exists after all editor
responses. Immediate publication may finish before those responses, and
later edits may belong to successor batches. This stale test was identified
by source inspection; no lane test JVM was launched.

AGENTS.md §6 still says a configured quiet window coalesces edits. The
current `.claude/seon-hook.edn` no longer declares that setting.

The owned regression `seon.dev.hook-test/idle-edit-starts-without-quiet-delay`
exercises the actual pending-file/lock/drain owner and proves exactly one
successor for edits submitted during a publication. Its before/after probe
is recorded in [the landing note](../../prds/context-generation/research/slow-surfaces-plan-2026-09-15.md#landing).

## Owner

`test/seon/dev/edit_feedback_test.clj` and AGENTS.md §6 are outside the
startup-and-hook-waste assignment's explicit owned paths; a scope extension
was requested for the existing test.

## Acceptance

The editor integration test verifies every submitted path has terminal
publication evidence and bounds its event waits without requiring an idle
worker claim to remain present. It does not assume a single batch for edits
that may span publication completions. AGENTS.md describes the immediate
drain and one pending successor instead of a quiet window.

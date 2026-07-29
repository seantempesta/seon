---
type: issue
status: resolved
severity: friction
tags: [issue, agent-runtime, database, prompt]
---

# A nil query input matches anything, so prompt cannot refuse a nil trigger

## Problem

`seon.cluster.prompt/prompt` documents that it refuses `::no-trigger`
when the named message does not exist. That refusal never fires for a
NIL message id: Datahike treats a nil `:in` binding substituted into a
query pattern as a wildcard, so `trigger-content` with
`?message-id = nil` matches an arbitrary message's content instead of
returning nil (`src/seon/cluster/prompt.cljc:73-80`; the same shape
risk exists for `trigger-sender`).

This was load-bearing until 2026-07-28: the loop's `:call` branch
re-asked `work/unanswered-triggers` for the prompt's trigger, the
opening transaction had already ANSWERED it, so the re-ask returned
nil for the ordinary one-message case — and the prompt only carried
the right content because nil-as-wildcard happened to match the one
message. With two messages the re-ask selected the wrong one (the
confirmed group-3 defect, fixed in 7bb7ccbfe by deriving the trigger
from `message/trigger` on the held run). The wildcard hole itself
remains: any caller passing a nil id gets an arbitrary message's
content instead of a loud refusal.

## Evidence

`tmp/trigger-probe.clj` (2026-07-28): after an `:open` transaction
carrying `:seon.db/trigger` tx-meta, `unanswered-triggers` returns
`[]`, yet the pre-fix `:call` pass prompted successfully — proving the
nil id matched a message. The class is Datahike's own input
substitution, not a prompt-local bug: any `d/q` whose `:in` value can
be nil silently degrades from "match this" to "match any".

## Expected owner

`seon.cluster.prompt` should refuse a nil/absent message id BEFORE
querying (the request schema already names the key; make it required
and validate, or check `some?` at entry). If more nilable `:in` sites
exist, the choke-point fix is a query helper that refuses nil inputs
rather than per-site vigilance.

## Acceptance

`(prompt/prompt db {:seon.cluster.agent/id "a" :seon.cluster.message/id nil})`
throws the documented `::refused`/`::no-trigger` ex-info with a
database containing other messages; a regression asserts it.

## Update (2026-07-28, custody revision — the consumer is sealed)

The loop-side consumer is closed: `prompt` refuses `::no-trigger`
before any query, and the loop's one `:call` site now catches that
refusal and records it as a flat error value
(`test/seon/cluster/turn_test.clj`
`a-prompt-refusal-is-a-recorded-error-value-never-a-throw`). This note
STAYS OPEN for the query-layer root cause — any `d/q` whose `:in`
value can be nil still degrades from "match this" to "match any"; the
choke-point fix (a query helper refusing nil inputs) is unowned.

Resolved by `7bb7ccbfe`: current `prompt` derives the trigger from the held
run and refuses `::no-trigger` before rendering; no current nil-id query caller
remains to support the note’s generic helper claim.

---
type: issue
status: open
severity: friction
tags: [issue, database, architecture, web]
---

# Bespoke reactive loops duplicate seon.reactive outside its owner

## Problem

`seon.reactive` owns registered reactive reads (registration, one active
computation, newest pending database value, interest replacement, equality
suppression, final-consumer release), yet three sites still hand-roll that
machinery with their own `db/listen!` owner guards, settle loops, or database
polling timers.

## Evidence

Full sweep with method, replacement shapes, preserved behavior, and risk:
`docs/prds/source-cleanup/research/bespoke-reactive-sweep-2026-07-20.md`.

- `src/seon/web/router.cljs:332-436` — `attach!` listen/settle/cache loop
  (~105 lines). Already designed away by the route-authority collapse
  ([[static-routes-bypass-database-route-authority]]; design §4 in
  `docs/prds/source-cleanup/research/route-authority-collapse-2026-07-20.md`).
- `src/seon/client.cljs:344-539` — runtime-advertisement listener: owner
  token, attach-race promise, freshness re-read, initial-read-after-listen,
  plus `reconcile-agent-runtimes!` consuming `tx-data` for resume side
  effects (~150 lines). One `reactive/observe!` over
  `agent/resumable-agent-ids!` replaces both halves; `resume!` idempotency
  must be verified first.
- `src/seon/web/serve.cljs:1223-1290` — `handle-agent-run!` polls every
  1500 ms re-querying derive-state/run-start/turn settlement until
  done/timeout. A request-scoped registration plus one plain timeout timer
  replaces the poll.
- Related gap: `seon.reactive/close!` has no caller — shutdown
  (`seon.client/drain-runtime-owners!`) never releases registrations through
  the owner.
- Minor doc drift: `src/seon/web/reactive/call.cljs:14` still describes the
  feed as `listen!`-driven; it observes through `seon.reactive`.

## Acceptance

Each bespoke listen/timer path is deleted in the same commit that installs its
registration (one mechanism, no compatibility path). Live proof per site: a
route transaction re-derives the router; a restarted pod resumes its agents
without double-resume on hot reload; `POST /agents/run` returns on the
settling commit rather than a poll tick; `reactive/measurements` shows each
registration active then released; shutdown calls `reactive/close!` before
`db/close-session!`.

Owner: source-cleanup PRD (router = stage 4; serve poll + client
advertisement + close! call = stage 5, ordered after the router fold).

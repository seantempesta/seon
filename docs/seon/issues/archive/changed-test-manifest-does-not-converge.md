---
type: issue
status: resolved
severity: friction
tags: [issue, agent]
---

# Changed-test manifest can fail to converge

## Problem

An edit hook can wait for a managed Shadow manifest that does not match current
source, delaying feedback and returning only a build-unavailable advisory.

## Evidence

Three hook runs on 2026-07-14 waited 30 seconds and reported that the managed
manifest did not converge. Full logs and the stable EDN report retained the
failure, but the delay occurred once per edit.

## Owner

`seon.dev.changed-test` and the existing Shadow build artifact boundary.

## Acceptance

Normal `.clj`, `.cljs`, and `.cljc` edits reach one bounded selection; a stale
or missing manifest widens honestly or reports the actual watcher/build fault
without a 30-second delay per edit; full logs and the EDN report retain the
cause; and passing or failing tests remain advisory.

## Resolution

Resolved by `a148a4e3`. Exact-manifest waiting is bounded at three seconds and
then delegates to the existing full `bin/test-cljs` one-shot gate; stale
artifacts are never run. Real Codex edits proved CLJ (operator 13/31), CLJS
(pod 4/16), and CLJC union (writer 12/75 plus pod 11/68). Every result stayed
advisory and retained full logs plus `tmp/test-changed/latest.report.edn`.

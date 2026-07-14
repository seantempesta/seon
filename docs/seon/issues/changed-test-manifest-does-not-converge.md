---
type: issue
status: open
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

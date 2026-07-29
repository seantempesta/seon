---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, agent]
---

# Make lane status identify live work

## Problem

`bin/codex-agent status` exposed process records without a useful lane-oriented
summary and listed placeholder `lane-summary: in-progress` files as if their
summaries had landed. Missing `watch` and `summary` targets fell through to
generic shell errors.

## Evidence

The lane state already stores the lane name, command, wrapper PID, and process
start time, but the status output did not assemble those facts. Summary files
are created before completion, so recency alone cannot mean landed.

## Owner

`bin/codex-agent`, the one tracked lane harness.

## Acceptance

Status names each live lane with PID, elapsed time, and command; it omits
in-progress summary markers. Invalid or absent watch/summary targets fail with
the lane name and expected path.

## Resolution

Commit `9e79b77e9` derives live rows from the lane-state directories, filters
the explicit in-progress marker, and validates lane names and target files.

## Proof

The live status output named `cluster-priming`, `operator-reconciliation`, and
`tool-sharpening` with their PIDs, elapsed times, and `run` commands, while the
tool-sharpening placeholder was absent from landed summaries. A missing summary
exited 1 and printed its exact expected path.

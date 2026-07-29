---
type: issue
status: resolved
severity: friction
tags: [issue, tooling]
---

# Refuse contradictory lint modes

## Problem

`bin/lint` accepted mutually exclusive mode flags and absent paths. Its branch
order could silently select one requested linter, so the command line appeared
broader than the work actually performed.

## Evidence

The script exposes `--kondo`, `--splint`, and `--metrics` as exclusive
selectors, plus `--fix` as a Splint operation, but previously did not validate
their combinations or input paths before invoking dependencies.

## Owner

`bin/lint`, the one combined Clojure lint entry point.

## Acceptance

Contradictory selectors and `--fix --kondo` exit with usage errors; a missing
path and a missing required executable fail before a partial lint run begins.

## Resolution

Commit `9e79b77e9` counts exclusive modes, validates the fix boundary and every
path, and preflights clj-kondo and Babashka according to the selected mode.

## Proof

Conflicting modes and `--fix --kondo` exited 64 with actionable messages; an
absent path exited 66 and named the path.

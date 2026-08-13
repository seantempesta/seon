---
type: issue
status: open
severity: friction
tags: [issue, agent, test, render, class/n11, wave/evolving-session-phases]
---

# Opening walkthrough replicates a usage test

## Problem

The generated opening defines `largest-usage` with
`:seon.test/usage true` in every agent namespace. That metadata declares each
demonstration artifact as a canonical usage surface, even though the recurring
anti-rot declaration belongs to the system's `my.run/walkthrough` test.

## Evidence

`src/my/run.clj:41-83` returns the executable walkthrough, and lines 70-76
place `:seon.test/usage true` on the agent-authored `largest-usage` test.
`test/my/run_test.clj:99` separately and correctly marks the maintained
system walkthrough test as usage. Executing the demonstration therefore
duplicates the declaration into every agent's program facts.

## Owner

`my.run/walkthrough` owns the demonstrated form. Its example test should prove
the demonstrated function without declaring itself a program-wide usage
surface.

## Acceptance

- The generated example test remains executable and indexed as an ordinary
  test.
- It does not carry `:seon.test/usage true`.
- The maintained `my.run/walkthrough` usage test remains the one recurring
  declaration that gates rendering.

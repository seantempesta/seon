---
type: issue
status: open
severity: friction
tags: [issue, agent, run-loop, observability, wave/why-awake]
date: 2026-09-08
---

# Two turn backstops fire and the sliding fault channel keeps the wrong one

## What

`seon.cluster.agent-test/disarm-has-a-declared-loud-turn-completion-backstop`
asserts that the failure `disarm!` throws is the same value that reached the
agent's fault channel. After the listened-attributes change it fails, twice,
and the two halves say the same thing:

```text
expected: (= failure (:clojure.core.async.flow/ex fault))
expected: (= run-id (:seon.cluster.run/id fault))
  actual: (not (= "100c1b13-…" nil))
```

The thrown failure names the held run. The fault ON THE CHANNEL names none —
its text is "with no observable held run". So two distinct backstop failures
were constructed: one while the turn still held its run, one after custody was
released. `disarm!` joined the first; the agent's fault channel is
`(sliding-buffer 1)`, so it kept the second.

## Why it is a defect and not just a stale expectation

`offer-turn-backstop-fault!` (`src/seon/cluster/agent.clj:482-503`) RE-DERIVES
`held-run-id` at fire time instead of carrying the decision the bound was
armed for. Two firings therefore describe two different worlds, and the one a
reader sees depends on which arrived last on a sliding buffer. That is the
owner law's pre-read shape: the seam re-decides something its authority
already decided.

The second firing itself is the other half — a second turn pass arms a second
backstop while the first turn is still blocked in the provider. It is not
clear this pass does anything useful.

## Attribution

The test is GREEN at `HEAD~1` and red at the listened-attributes commit, in
isolation as well as in a full run (`tmp/wake-lane-t5.log`,
`tmp/wake-lane-base3.log`). The derivation itself is not the cause: a probe
against a closed, released run derives `nil` work correctly
(`tmp/wake_probe.clj`). What changed is timing, which is exactly what a
re-derived pre-read is sensitive to.

## What would close this

Carry the run identity the bound was armed against into
`offer-turn-backstop-fault!` instead of re-deriving it, and establish whether
the second pass should exist at all.

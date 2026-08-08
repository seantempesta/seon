---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, ai, live-drive]
---

# Stop a failed turn from waking itself through its own fault message

## Problem

When a turn fails, the failure is committed as a durable fault message
addressed to the same agent. That message is an ordinary wake fact, so it
triggers the next run. If the cause of the failure is still present — as a
broken rendered context always is — the next run fails the same way and
commits another fault message.

The result is a self-sustaining loop that makes a real, paid provider call on
every lap. Nothing in the cycle is rate-limited, deduplicated by signature, or
gated on the fault being distinct from the one that woke the run.

This is separate from the defect that empties the context
([Give an as-of database value a dependency revision](walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md)).
That one explains why the turns fail; this one explains why they keep
repeating, and it would turn any recurring turn failure into the same loop.

## Evidence

Cluster `default` (pid 79576, prepl 54233), observer lane, 2026-08-08.
Read-only census at 04:44:22Z, eleven minutes after the cluster came up:

Six runs, of which five were triggered by a message. Four of those five
triggers are faults the system committed about itself:

| Trigger eid | Message content (first 60 chars) | Claimed |
|---|---|---|
| 25346 | `Collection did not preserve and verify every recorded root. ` | yes |
| 25356 | `The process census could not read every external claim. (:se` | yes |
| 25361 | `The reaper cannot read every external claim. (:seon.operator` | yes |
| 25367 | `The turn :step failed with :seon.instrument/contract-violate` | yes |
| 25372 | `LIVE-DRIVE-0808-A. Inspect the message that woke you and you` | yes |
| 25419 | `A run phase failed: Invalid symbol: refused:` | not yet |
| 25444 | `A run phase failed: Reader tag is not accepted: :message` | not yet |

Runs `cf7cc2f1` and `84799227` each opened and closed within the same second
with **zero** forms — the model reply failed to read — and each of those two
closures committed one of the two `A run phase failed:` messages above, which
are themselves queued to wake the agent again.

The cost, from the durable `:seon.ai.attempt/usage-edn` facts. Every attempt
used `deepseek-v4-flash` and finished `stop`:

| Attempt | At | Prompt | Completion | of which reasoning |
|---|---|---:|---:|---:|
| `a7e24a23-…-attempt-0` | 04:38:02Z | 225 | 10,502 | 9,840 |
| `cf7cc2f1-…-attempt-0` | 04:39:47Z | 225 | 9,992 | 9,447 |
| `20768b1f-…-attempt-0` | 04:41:32Z | 225 | 3,995 | 3,459 |
| `84799227-…-attempt-0` | 04:42:18Z | 225 | 2,463 | 895 |

900 prompt tokens produced **26,952 completion tokens** in four minutes — a
30:1 completion-to-prompt ratio, 23,641 of them reasoning. The prompt is
identical on every lap (509 characters; `prompt_cache_hit_tokens` 128 after
the first), so the loop has no way to converge: the same input is resent and
re-reasoned indefinitely.

For calibration, the 2026-08-06 drive recorded 44,306 prompt / 7,329
completion tokens for one turn. The prompt has since collapsed 197× and the
completion has grown — a starved prompt costs MORE, because the model
substitutes reasoning for the context it was not given.

## Owner

The run loop's failure path in `src/seon/cluster/loop.clj` together with the
fault-committer that turns a run-phase failure into a `:seon.cluster.message`
addressed to the failing agent.

## Acceptance

- A fault arising from a run does not, by itself, open a further run for the
  same agent when the new fault's signature equals the one that woke it.
- A run that closes with zero forms because its reply could not be read is
  distinguishable by query from a run that closed having done work.
- A cluster left alone with a persistently broken context reaches a quiet
  terminal state and makes no further provider calls.
- One class regression drives a turn whose reply cannot be read and asserts a
  bounded number of provider attempts rather than an unbounded sequence.

## Note for whoever fixes this

The two reader refusals seen here are worth keeping as fixtures:
`Invalid symbol: refused:` and `Reader tag is not accepted: :message`. Both
arose from ordinary model prose, and both produced a zero-form run rather than
a legible refusal the agent could act on.

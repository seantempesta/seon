---
type: issue
status: open
severity: friction
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

## Partial resolution — 2026-08-08, commit `7f0cb6bda`

The unbounded half is fixed at cause. There were TWO paths committing a wake
about a run's own failure, and only one of them was governed:

1. `seon.error/commit-tx` — the ONE designed owner. Its docstring already
   states the policy and the reason: delivery is the wake attribute, so
   error -> message -> wake -> turn -> error is a real cycle, and every
   notification is bounded by the per-signature recurrence fence. It already
   skips a recurrence escalation to the attributed agent.
2. `seon.cluster.loop/refusal-terminal-data` — a second copy, which
   `dissoc`ed the escalation dial to keep owner 1 quiet and then hand-rolled
   its own `"A run phase failed: …"` message. Unbounded, and it never asked
   who had failed. `error-tx`'s own docstring names this exact hazard: "a
   second copy of it is how one of them quietly stops escalating."

On a single-agent cluster `:seon.config.error/escalate-to` names root and root
is the only agent, so path 2 mailed the failing agent about itself on every
lap. That was the engine of the nine paid calls above. Path 2 now refuses to
escalate to the agent whose run was refused; a cross-agent escalation is
unchanged, which is the whole point of the dial.

Class regression:
`seon.cluster.loop-test/a-refused-phase-never-escalates-to-the-agent-whose-run-was-refused`,
which asserts BOTH directions — a supervisor still hears about a worker's
refused phase — because asserting only the self case would leave the fix
indistinguishable from switching escalation off.

Live: the loop stopped. After the context fix and this one, the cluster made
no unstimulated provider call; the two human messages driven afterwards each
opened exactly one run and closed it.

### Still open after that partial fix

- **Path 2 is still a second mechanism.** It should be deleted and its intent
  folded into `seon.error/commit-tx`, so run-phase escalation is bounded by
  the same recurrence fence as everything else. It was NOT deleted there
  because `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`
  pins the per-failure cross-agent escalation as intended behavior, and that
  file was protected during that lane. Deleting the path and amending that
  property is the follow-on. **Closed 2026-08-08 night — see below.**
- **Path 2 is still unbounded across agents.** A worker refusing a hundred
  times mails a supervisor a hundred times. Not a self-feeding loop, but the
  same storm the fence exists to prevent. **Closed 2026-08-08 night.**
- **The acceptance clause about a distinguishable zero-form run is already
  satisfied** and needs no work: `refusal-terminal-data` writes
  `:seon.cluster.run/error` whenever a run closes with no ordinal, and four
  such runs were queryable on the live cluster.
- **The reply-reader refusals remain**, and are the reason those turns failed
  at all: `Invalid symbol: refused:` and `Reader tag is not accepted:
  :message`, both from ordinary model prose.

## The second mechanism is deleted — 2026-08-08 night (owner ruling)

`seon.cluster.loop/refusal-terminal-data` no longer `dissoc`es the escalation
dial and no longer builds a message. It calls `error-tx` with the cluster it
was handed, and `seon.error/commit-tx` decides who is told, exactly as it does
for every other failure in the system. The interim `(not= escalate-to
agent-id)` guard went with the copy it was guarding: it existed only to stop a
message this code no longer writes.

Both remaining hazards die with the copy, and neither is now REPRESENTABLE
rather than merely checked:

- **self-wake** — a phase failure is a VALUE, not a Throwable, so commit-tx's
  `:your-run` arm never fires for one at all, and its recurrence escalation
  already skips the attributed agent. There is no argument at this call site
  that could address the failing agent;
- **the cross-agent storm** — escalation is now one message per SIGNATURE per
  process, at `:seon.config.error/recurrence-limit` and never after it. A
  worker refusing a hundred times mails a supervisor once.

**Ported detail, not a second mechanism.** The deleted message carried one
thing commit-tx's `:recurring` prose did not: the failure's own message text.
That clause is now folded into the one owner (`seon.error/notice-ai-prose`),
along with the run the fault interrupted — both read off declared facts on the
error entity and both omitted when absent, so an escalation names what failed
instead of only a kind and an id. Nothing else in the deleted path was worth
keeping; its `:seon.error/data {:seon.cluster.loop/phase …}` was already
committed as `:seon.error/data-edn` by commit-tx.

### Class regressions

- `seon.cluster.loop-test/a-refused-phase-escalates-once-per-signature-and-never-to-itself`
  — six identical refusals, both directions: `[[] [] ["supervisor"] [] [] []]`
  cross-agent, `[[] [] [] [] [] []]` self, six error facts committed in both.
  Asserting only the self case would leave the fix indistinguishable from
  switching escalation off, and asserting only one refusal would miss the
  bound, which is the half that makes the class unrepresentable;
- `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`
  — the per-sample escalation assertion (which pinned the deleted shape) is
  replaced by a ledger assertion over the whole property run: every fault
  message goes to the escalation owner and never to the failing agent, and
  there is exactly one per signature.

### Live proof — the scenario does not reproduce

Scratch cluster `escalation-probe` in isolated root `tmp/lane-escalation`,
reproducing the reported conditions exactly: `:seon.config.error/escalate-to`
= `root`, recurrence limit 3, and `root` the only agent. Six refused `:prompt`
phases driven through `seon.cluster.loop/settle!`:

| | fault messages | runs |
|---|---:|---:|
| before | 4 (all pre-existing boot maintenance faults) | 5 |
| after six refusals | **4** | **5** |

Zero new messages, zero new runs, zero provider calls. Under the old path this
is the shape that produced nine paid calls in twenty minutes.

### Still open in this note

Only the fourth acceptance clause: **one class regression that drives a turn
whose reply cannot be read and asserts a bounded number of provider
attempts.** The escalation half is proven at the settle boundary above; what is
not yet claimed by a recurring surface is the full turn — reply refusal to
attempt count. The two reader refusals below remain the fixtures for it.

## Note for whoever fixes this

The two reader refusals seen here are worth keeping as fixtures:
`Invalid symbol: refused:` and `Reader tag is not accepted: :message`. Both
arose from ordinary model prose, and both produced a zero-form run rather than
a legible refusal the agent could act on.

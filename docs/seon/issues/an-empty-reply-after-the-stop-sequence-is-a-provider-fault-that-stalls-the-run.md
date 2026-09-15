---
type: issue
status: open
severity: blocker
tags: [provider, stop, reader, turn, faults, live-test]
created: 2026-09-15
---

# A reply cut to nothing by the stop sequence is treated as a provider fault, and the run stalls silently

## Repair, 2026-09-15

Normal `stop` with empty assistant text now reaches the existing no-form
reader path. It records one error evaluation saying `Your reply began with
a response; send a form.`, accepts the reply, and opens the next turn.
The completion schema admits empty strings; the reader's short arities
still supply a positive source bound. No alternate turn transition was added.

The canonical running-graph regression uses the real completion parser and
confirms two attempts, one error evaluation, and the error in the next
prompt. Its malformed-JSON case uses the real stream parser and still
defers after one attempt with two turns remaining. Visibility is addressed
in the following landing slice; see
[the evidence](../../prds/context-generation/research/run6-blockers-landing-2026-09-15.md).

## Observed (live run 6, 14:46:11Z)

Turn 6's attempt returned no assistant text (the reply began with the
stop sequence `#:seon.repl`, i.e. a fabricated response with nothing
before it). `seon.ai` reported `:seon.ai/unparseable-body` "The provider's
response carried no assistant text." as a core fault; the turn became a
provider refusal; the loop deferred until an outside wake; the run sat
idle with 25 turns left. The debug page said "Checks passed · 10", "0
faults delivered", state "idle" — a stalled paid session read as health.

## Wanted

- An empty reply (after stop) is the AGENT's mistake: one `:error`
  evaluation ("your reply began with a response; send a form") in the
  agent's history, an accepted reply, the session continues. Only a
  transport/HTTP/JSON failure is a provider fault.
- A stalled run is visible: when a provider refusal defers an agent whose
  session is open (steps remaining, budget left), the state line reads
  "stalled: provider refusal <kind> at HH:MM, waiting for an outside wake"
  and the problems panel lists it first (same wanted as the wedge issue).
- Regression: an attempt whose text is empty with finish_reason stop
  yields the error evaluation and the next turn opens.

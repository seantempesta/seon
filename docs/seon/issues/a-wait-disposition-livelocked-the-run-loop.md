---
type: issue
status: resolved
severity: blocker
tags: [issue, agent-runtime, run-loop]
---

# A wait disposition livelocked the run loop on an unclosable run

## Problem

`my.run/wait` released custody and left the run open with its plan fully
executed. `seon.cluster.work/next-work` derives `:close` for any open planned
run whose forms all carry terminal receipts — including one nobody holds — and
`seon.cluster.run/close-call` refuses a run the calling process is not the
holder of (`::not-the-holder`). So the close failed, the derivation still said
`:close`, `more-work?` was still true, and the loop's self-rewake fired again.

The result was a HOT LIVELOCK: the run loop spun at full speed for as long as
the process lived, committing one durable error fact per pass. The error path's
recurrence fence bounded the escalation MESSAGES at three, so nothing was
mailed after that, but the facts kept accumulating and the agent stayed busy
forever — its `:seon.cluster.agent/run` pointer was never retracted, so no
later trigger for that agent could ever open a run.

The class is broader than `wait`: any open planned run with all forms settled
and no holder reaches the same branch, which includes a run whose holder died
after committing the last terminal receipt.

## Evidence

Measured on the wait path before the fix (probe over `seon.cluster.turn-test`'s
own fixture, twelve passes):

```text
SITUATIONS: [:open :call :resume :close :close :close :close :close :close :close :close :close]
OUTCOMES:   [:released :released :released :error :error :error :error :error :error :error :error :error]
next-work after: {:seon.cluster.work/situation :close, ...}
error facts: 9
```

The existing suite hid it. `a-waiting-disposition-releases-custody-and-leaves-
the-run-open` asserted that the run was still open after a wait, which was
TRUE — and true precisely because every close refused. A test can pin a
livelock and stay green.

## Owner

`src/seon/cluster/loop.cljc`, the `:close` branch of `turn`.

## Resolution

The `:close` branch now takes custody before closing when it does not already
hold the run — the same takeover `settle-interruption!` uses — and treats a
refused claim (somebody else holds a live lease) as a non-error outcome rather
than closing a run that is not its own. After the fix the same probe reports
four passes, `:closed`, `next-work` nil, and zero error facts.

The test was replaced rather than adjusted:
`a-waiting-disposition-frees-the-agent-and-keeps-its-note` asserts the
surviving mechanism — four passes then idle, no error facts, the agent pointer
retracted so a later trigger can open a new run, and the wait note still
readable in the receipt (which is where `seon.cluster.prompt` reads it back to
tell the agent what it was waiting for).

Full gate green with the change. What is NOT settled by this fix is a design
question recorded for the owner: `wait` releases and the very next pass closes,
so a "waiting" run has no observable resumable state and `my.run/wait`'s
docstring promise that "the run resumes on a later wake" is not what happens —
the agent gets a NEW run from its next trigger. Either the docstring is
corrected (done) or `wait` should close directly in the terminal transaction
and `release-tx` should leave this path.

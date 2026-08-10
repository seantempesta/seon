---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, agent, ai, live-drive]
---

# Settle an unreadable reply as a form the agent can see

## Problem

When a provider reply arrives complete and well-formed at the HTTP level but
its TEXT does not READ as Clojure forms, Seon records
`:seon.cluster.reply/unreadable` and closes the run with **zero forms and zero
receipts**, in the same second it opened. The agent is never told. Its next
turn begins with no evidence that the previous turn happened at all.

This is a DIFFERENT mechanism from
[a mid-stream provider disconnect](../a-mid-stream-provider-disconnect-discards-the-whole-turn.md),
and must not be folded into it. There the body was truncated in transport
(`:seon.ai/unparseable-body`, "not readable JSON: closed"). Here the body
arrived whole, the JSON parsed, and the attempt recorded
`:seon.ai.attempt/finish-reason "stop"` — the model simply emitted unbalanced
source.

Observed on cluster `default`, pid 91415, 2026-08-10T21:26:10Z:

```clojure
;; the run
{:seon.cluster.run/id        "deb721df-ca2f-4695-b4e1-40aec76f1fc8"
 :seon.cluster.run/opened-at "2026-08-10T21:26:10Z"
 :seon.cluster.run/closed-at "2026-08-10T21:26:10Z"   ; same second
 :forms 0 :receipts 0}

;; the error fact
{:seon.error/kind    :seon.cluster.reply/unreadable
 :seon.error/message "EOF while reading, expected ) to match ( at [12,1]"
 :seon.error/run     #:db{:id 25905}
 :seon.error/at      #inst "2026-08-10T21:26:10.120-00:00"}

;; the attempt for that same run — a complete, paid-for, finished reply
{:seon.ai.attempt/finish-reason "stop"
 :seon.ai.attempt/usage-edn
 "{\"prompt_tokens\" 10665, \"completion_tokens\" 1229, …}"}
```

Two costs, one of them the real one:

1. **We paid for 11,894 tokens and recorded nothing but the error.** The reply
   text is not retained as a form, so the eleven forms that presumably read
   fine before position `[12,1]` are discarded along with the twelfth.
2. **The failure is invisible by query.** A run with zero forms, zero receipts,
   no `:seon.cluster.eval/interrupted-at`, and a same-second close is
   indistinguishable from a run that legitimately had nothing to do. The only
   trace is a separate `:seon.error` row that nothing on the run points at
   from the run's own side. This is the same class as
   [recovery closing an interrupted run without marking it](../recovery-closes-an-interrupted-run-without-marking-it.md):
   two very different outcomes share one database signature.

The agent also learns nothing. A reader error is the single most correctable
mistake a model can make — it is a missing paren at a named position — and the
current design is the one that guarantees the model cannot correct it, because
its next prompt contains no evidence the mistake occurred.

## Wanted

An unreadable reply is an agent mistake, so it should become a VALUE the agent
sees, exactly like every other agent mistake:

- record the reply as a form whose receipt is the flat reader error, naming the
  position and the expected delimiter, so the run has forms, the transcript has
  a receipt, and the next prompt shows the agent what it wrote and why it did
  not read;
- retain the reply source so the discarded work is inspectable rather than
  merely reported as a length;
- make a run that produced nothing distinguishable by query from a run that
  produced something unreadable, without needing to join to `:seon.error`.

## Acceptance

- a regression that feeds a run an unbalanced reply and asserts the run closes
  with at least one form, a settled receipt carrying a
  `:seon.cluster.reply/unreadable` error value, and the reply source retained;
- the same regression asserts the next prompt for that agent contains the
  reader error, so the correction loop is closed;
- a query distinguishing "no forms because nothing was asked" from "no usable
  forms because the reply did not read" without consulting `:seon.error`.

## Evidence

- [model-authoring-observer-2026-08-10.md](../../../prds/sci-execution-runtime/research/model-authoring-observer-2026-08-10.md)
  — verdict 4, the discarded turn; raw dump `tmp/observer-0810-errors.edn`.

## Resolution

Resolved in the commit that archives this note. The reply reader already
returned the complete flat `:seon.cluster.reply/unreadable` value, including
the original text and SCI's delimiter position. The loss happened one boundary
later: `call-turn` sent every reply refusal through the pre-form failure path,
whose absent ordinal can only close with `:seon.cluster.run/error`.

The unreadable branch now freezes one exact-source form and starts ordinal
zero's receipt in the same Datahike transaction. After that commit, a silent
formless close is structurally impossible: the receipt exists and either
settles with the reader refusal or recovery records its interruption. The
receipt settles through the existing evaluation-result projection, so the
complete refusal uses ruling #25's existing inline/blob result split. The
successful provider attempt remains unchanged and retains usage and finish
evidence.

The one class regression,
`an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence`, proves:

- the turn reaches `open → call → close` with one form and one receipt;
- the form retains the exact reply source and the receipt carries
  `:seon.cluster.reply/unreadable` plus the reader message;
- the outcome is queryable through the run's form and receipt without joining
  `:seon.error`;
- the successful attempt retains 11,894 tokens of usage evidence and finish
  reason `"stop"`; and
- the next prompt contains both the malformed source and `EOF while reading`.

Proof: `bin/test seon.cluster.turn-test` passed 51 tests / 362 assertions, and
`bin/test --changed src/seon/cluster/loop.clj --changed
test/seon/cluster/turn_test.clj` passed 134 tests / 755 assertions with zero
failures or errors.

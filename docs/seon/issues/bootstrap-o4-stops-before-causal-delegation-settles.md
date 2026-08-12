---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, test, bootstrap]
---

# Make bootstrap O4 wait for the causal delegation

## Problem

`seon.eval.drive/run-episode!` ends an episode from only the initiating
agent's runs directly triggered by the outside objective. Current
`my.run/wait` semantics close that run, so O4 freezes and grades its database
basis while the causally reached peer run is still executing. The reported
0/10 is a false negative for runtime delegation and blocks trustworthy
bootstrap generation experiments.

The same scope supplies only the initiating run's receipts to `grade-o4`.
Even if the snapshot were delayed, the continuation run triggered by the peer
reply—and therefore the function call and final `"42"`—would remain invisible
to P4c and P4d.

## Evidence

The embedded attempt in
[`bootstrap-baseline-2026-08-04.md`](../../prds/sci-execution-runtime/research/bootstrap-baseline-2026-08-04.md)
contains a committed main-to-peer message and a main `wait`, while the peer
transcript contains the message but no peer form or reply.

The live isolated reproduction in
[`o4-delegation-diagnosis-2026-08-05.md`](../../prds/sci-execution-runtime/research/o4-delegation-diagnosis-2026-08-05.md)
completed the same objective through three triggered runs and ended at
`"42"`, with no error facts after the objective. At the basis immediately
after the initiating wait closed, the existing predicate returned:

```clojure
{:terminal
 #:seon.eval.drive{:outcome :stopped,
                   :run-ids
                   ["8415746c-548c-43d4-8e61-5c99c6316d5d"]},
 :peer-runs 1,
 :reply-count 0}
```

The broken source boundary is:

- `src/seon/eval/drive.clj:110-119` selects only direct trigger runs;
- `src/seon/eval/drive.clj:235-272` derives terminal state only for those runs
  and the initiating agent;
- `src/seon/eval/drive.clj:327-352` freezes and reports that incomplete basis;
- `src/seon/bootstrap_drive.clj:242-256` searches the incomplete receipt set
  for the continuation call and completion; while
- `src/seon/cluster/loop.clj:328-344` correctly makes `wait` close the current
  run, with the settled proof at `test/seon/cluster/agent_test.clj:1590-1660`.

Neither `test/seon/eval/drive_test.clj` nor
`test/seon/bootstrap_drive_test.clj` proves a multi-agent causal episode.

## N2 disposition — 2026-08-11

The recorded isolated probe is the retained counterexample for this member:
the old terminal query returned `:stopped` with one peer run and zero replies.
The proof therefore reached its success-shaped terminal while the causal
subject was incomplete.

This is a production-constructor defect, not a fixture-only omission.
`seon.eval.drive` does not yet construct the complete causal run/message
closure that both the terminal predicate and O4 grader must consume. A test
cannot honestly derive a nonempty complete subject from that production query
until the query exists. The test owners are also in the live `projection`
lane's protected set, so N2 defers this member to that named holder without a
test-side seed or alternate closure query.

## Owner

The live `projection` lane, at the existing `seon.eval.drive` fact-space
episode boundary and the O4 grader in `seon.bootstrap-drive`. Strengthen the
one driver; do not change runtime message delivery or restore open-run `wait`
semantics.

## Acceptance

- Starting from one outside objective message, derive the complete causal
  message/run closure through `:seon.cluster.run/trigger` and
  `:seon.cluster.message/caused-by`.
- A closed initiating `wait` run is not terminal while that closure contains a
  live peer run, an unhandled causal message, or a possible initiating-agent
  continuation.
- The ending commit contains the peer reply and the initiating agent's final
  completion, and the reported run ids, receipts, attempts, and transcripts
  include every run in the causal closure.
- A recurring scripted-completer test executes main send/wait → peer permanent
  contracted defn/complete → main call/complete without a real provider and
  makes P4a, P4b, P4c, and P4d all true.
- A semantic miss terminates nonzero or as an explicit failed/capped outcome
  with the complete observed closure; it never freezes a success-shaped
  grading branch at main-only quiescence.
- The live DeepSeek O4 drive passes from a fresh published cluster, while the
  existing close-on-wait regression remains green.

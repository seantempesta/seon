---
type: issue
status: closed
severity: friction
tags: [issue, web, database]
---

# `/agents/run` polled for settlement

## Problem

`seon.web.serve/run-agent-task!` slept for 1500 milliseconds and reacquired a
database value before checking whether the request's run and turns had
settled. Task progress only changes through committed transactions, so the
loop added completion latency and repeated unchanged database reads.

## Resolution

The request now attaches one request-scoped `seon.reactive` consumer. Its
computation captures the existing state, latest-run-start, and terminal-turn
reads against one immutable database value. The exact prior completion
predicate is unchanged. The committing database value can therefore resolve
the request immediately, while the genuine wall-clock timeout remains a
single timer.

The consumer is unobserved in `finally` on success, database failure, or
timeout. A timeout still closes the current run with
`:seon.agent.run/closed-reason :superseded` before the truthful final database
projection is acquired.

## Evidence

- `seon.web.serve-test/agent-run-settlement-is-commit-driven-and-released`
  proves a nonterminal initial value followed by an immediately delivered
  settling commit and zero remaining request consumers.
- `seon.web.serve-test/agent-run-settlement-timeout-releases-and-supersedes`
  proves timer settlement, unconditional release, and the preserved
  `:superseded` close request.
- The focused selectors pass 2 tests / 10 assertions, and the complete
  `seon.web.serve-test` namespace is the unit gate.

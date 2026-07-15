---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Derive the composition-door timeout from run policy

## Problem

`POST /agents/run` used a hardcoded five-minute request timeout when the caller
omitted `timeout_ms`. The run owner already derived a 30-minute default from
the database config singleton, so the Inspect composition door could terminate
a healthy task under a different, invisible bound.

## Dependency ledger

- `seon.agent.run/open-run!` owns effective run-deadline precedence: explicit
  run deadline, agent override, then `seon.agent.ctx/run-policy`.
- `seon.agent.ctx/run-policy` reads the frozen `:seon.config` singleton and
  falls back through the registered config schema.
- `seon.web.serve/run-agent-task!` is the one `POST /agents/run` composition
  door used by `seon_inspect.solver`; no second evaluator or lifecycle exists.
- Focused behavioral owners are `seon.agent.run-test` and
  `seon.web.serve-test`.

## Acceptance

- An explicit `timeout_ms` remains the caller-selected request bound.
- An absent value derives through the same database/agent precedence as the
  run opened by the message.
- No literal default remains in the HTTP handler.
- Focused CLJS tests and a live ACME REPL probe prove the selected duration.

## Resolution

The resolving commit extracts one pure `effective-deadline-ms` owner in
`seon.agent.run`, reuses it when opening a run and when the composition door
receives no explicit request timeout, and preserves explicit Inspect bounds.
Focused `run` and `serve` tests pass 15 tests and 65 assertions. The running
ACME pod resolves 1,800,000 ms from its live database for root through the
repository CLJS REPL.

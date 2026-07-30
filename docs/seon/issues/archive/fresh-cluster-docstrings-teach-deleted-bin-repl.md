---
type: issue
status: resolved
severity: cleanup
tags: [issue, tooling, operator, repl]
---

# Fresh cluster docstrings taught the deleted REPL launcher

## Problem

The duplicate `bin/repl` launcher was deleted, but two comments in the fresh
cluster owner still taught that command.

## Evidence

The boot instrumentation comment named `bin/repl` as its development caller,
and the public `readiness` docstring claimed that `bin/repl` printed its
result. The maintained development entry point is the fresh operator.

## Owner

The public documentation embedded in `src/seon/cluster.clj`.

## Acceptance

Active source contains no `bin/repl` reference, and the documentation states
only behavior the maintained cluster API and fresh operator provide.

## Resolution

Resolved by `cb151d220` (`Name destructive cluster operation refork`). The
instrumentation comment now names the fresh operator as the development owner,
and `readiness` simply documents its ordinary data return. A repository search
over active `src/`, `test/`, and `script/` returns no `bin/repl` reference.

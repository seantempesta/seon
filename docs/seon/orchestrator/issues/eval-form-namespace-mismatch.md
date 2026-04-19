---
type: issue
status: open
tags: [issue, agent]
---
# eval-form! doesn't set namespace before nREPL eval

## Problem

`seon.repl/eval-form!` accepts a `::namespace` parameter but uses it only for Datalevin storage. The actual nREPL eval via `eval-via-flow!` → `pool/nrepl-eval!` sends the code string without prepending `(in-ns ...)`.

If the submitted source contains `::keyword` forms, they expand based on the nREPL session's current namespace (set once by `setup-namespace!` at claim time), not the `::namespace` parameter.

This means:

1. If agent code ever changes `*ns*` during eval (e.g., a `(ns ...)` form), subsequent evals will have `::` expand in the wrong namespace
2. If `eval-form!` is called with a `::namespace` that differs from the session's current `*ns*`, keywords silently expand wrong

## Likely Fix

Prepend `(in-ns 'the-namespace)` before every eval, or at minimum assert the session namespace matches the parameter.

## File Refs

- `src/seon/repl.clj` — `eval-form!` line ~293, `eval-via-flow!`
- `src/seon/flow/pool.clj` — `nrepl-eval!`, `setup-namespace!`

## Severity

friction

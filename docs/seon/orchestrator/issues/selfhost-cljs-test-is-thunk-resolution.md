---
type: issue
status: active
tags: [issue, agent, cljs]
severity: friction
---

# Self-host `cljs.test/is` throws inside a dynamically-evaled `:test` thunk

## Problem

When a B9 usage-example fn — a `(defn f {:test (fn [] (cljs.test/is …))} …)` —
is defined through the live pod's self-host eval path (`seon.eval/eval-batch!`
→ `cljs.js/eval-str`) and then driven by `seon.test.runner/run!`, the `:test`
thunk is now correctly RESOLVED (the runner fix below) but INVOKING it throws:

```text
TypeError: Cannot read properties of undefined (reading 'call')
```

reported as an `:error` event, so the run summary shows `{:test 1 :pass 0
:fail 0 :error 1}` instead of a clean `:pass`.

LIVE-PROVEN 2026-06-17 on the pod: `resolve-test-fn` returns the right thunk
(`resolved === cljs$lang$test thunk`, NOT the impl fn), and `(f 4 5)` returns
the correct value — but the thunk's `(cljs.test/is …)` body, as COMPILED in
self-host, references a `cljs.test` runtime var that is undefined in that
dynamically-evaled context.

## Root cause (suspected)

`cljs.test/is` is a macro that expands to `try-expr` / `do-report` plumbing.
When the example's source is compiled by `cljs.js/eval-str` (often with
`:analyze-deps? false`, since the bootstrap bundle only carries `cljs.core`),
the `is` expansion's references into `cljs.test`'s own runtime don't fully
resolve to the loaded globals the way shadow's standard compilation does. The
thunk body therefore carries a dangling reference that throws when called.

This is a SELF-HOST-COMPILATION concern, NOT a runner defect: the same example
shape compiled by shadow (the `bin/test-cljs` `:node-test` build) runs its
`is` assertions cleanly — see the green probes in
`test/seon/test/runner_probes.cljs` (`probe-example-add` /
`probe-example-armed`) and the driving tests
`run!-runs-a-defn-with-test-usage-example` /
`run!-surfaces-a-failing-defn-with-test-example` in
`test/seon/test/runner_test.cljs`, which assert the example's pass/fail fires.

## Scope / impact

- B9's "examples RUN" contract is PROVEN in the standard-compiled suite (the
  durable proof). The gap is only the LIVE-POD self-host path: an agent's
  freshly-evaled example may report `:error` instead of `:pass`/`:fail` on its
  FIRST auto-test-run, even though the fn and thunk are correct.
- Low blast radius: the `:test` example still SHOWS correctly in context
  (it's verbatim source in the elided `defn`), instrumentation still validates
  the fn, and the example re-runs correctly once the ns is loaded through the
  standard path (resume / suite). It does not corrupt data or block a turn.

## What is already fixed (do NOT re-investigate these)

1. **`resolve-test-fn` unwraps Malli instrumentation.** An example fn carries
   `:malli/schema`, so eval auto-instruments it — malli replaces the global
   with a wrapper and stashes the real fn (carrying `cljs$lang$test`) at
   `malli$instrument$original` (`reference-code/malli/.../instrument.cljs:13,57`).
   The runner now unwraps to the original before reading the thunk
   (`src/seon/test/runner.cljs` `resolve-test-fn`). Without this it ran the
   INSTRUMENTED IMPL at arity 0 → `:malli.core/invalid-arity`.

2. **`ensure-analyzer-ns!` primes the target ns.** A `def` under
   `:def-emits-var true` into a ns with no `:cljs.analyzer/namespaces` entry
   threw `Assert failed: (ana/ast? sym)` (`get-namespace` →
   nil current-ns → `var-ast` → nil → `:the-var` node missing `:sym`/`:meta`).
   `seon.eval/raw-eval` now primes the target ns via a real `(ns …)` eval first
   (`src/seon/eval.cljs`). Fixed + live-proven.

## Possible fix directions (unstarted)

- Ensure `cljs.test` is fully analyzed/linked into the bootstrap compile-state
  so `is` expansions resolve in self-host (analyze-deps for cljs.test once at
  boot, or preload its analysis cache like the other core nses).
- Or: compile example thunks against the standard `cljs.test` runtime globals
  explicitly before the auto-test-run.
- Or: accept the first-run `:error` and rely on the standard-path re-run; the
  reactive warnings surface a currently-failing test, which self-heals once the
  ns loads cleanly.

## Cross-references

- `docs/prds/agent-runtime/db-is-the-running-system-2026-06-17.md` §"Usage-example tests (B9)"
- `src/seon/test/runner.cljs` — `resolve-test-fn`
- `src/seon/eval.cljs` — `ensure-analyzer-ns!`, `raw-eval`
- `test/seon/test/runner_test.cljs` — the green standard-compiled proofs

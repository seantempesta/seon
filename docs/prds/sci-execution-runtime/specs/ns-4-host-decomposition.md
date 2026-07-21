---
type: prd
status: active
tags: [prd, architecture]
---

# NS-4 — host.clj five-band decomposition

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. As you work, answer two
questions and report them in your summary: (a) now that you've read the
source, is there a better seam than the one this spec names? (b) what do
the existing owners call each thing — use their exact terms, never a
new synonym. **Stopping early to report a concern or a better seam is
FREE** — the session resumes with full context, and seam corrections are
exactly what we want. If anything in this spec contradicts what you find
in the source, stop and report rather than improvising.

## Goal

Split the JVM execution host god-file `src/seon/host.clj` (now 1,320
lines after the W0 hardening series) into its five proven bands so that
the upcoming W3 parity work (repl-form dispatch, preflight repair,
print capture, typed interrupts, authored invocation) lands in a
~330-line owner instead of a 1,300-line one. The split is
BEHAVIOR-IDENTICAL: same frames on the wire, same receipts, same error
values, same gates green before and after.

Design authority (read §3 completely):
`docs/prds/sci-execution-runtime/research/namespace-hierarchy-design-2026-07-21.md`
— it was written from a complete read of host.clj at 1,141 lines; the
W0.4 (writer pool), W0.6 (escape hardening), and W0.8 (schema staging
overlay) units have since grown the file, so its LINE NUMBERS are
stale but its BAND definitions, shared-private-fn evidence, and require
DAG remain the authority. Re-derive the exact boundaries from current
source; report any function whose band assignment the design got wrong
or that the W0 series added without an obvious band.

## Target shape (from the design, confirmed against current source)

Current anchor positions (verified 2026-07-21 night): frame/error
builders at :210-231 with `send-frame!` at :429 and `bounded-result` at
:434; sampling/retention at :266, :506, :551; eval serving at :462,
:484, :616, :683; invocation lifecycle at :848-:1047; session loop and
assembly at :1047-:1320.

- **`seon.host.session`** — bottom leaf: `now-ms`, `error-value`,
  `error-frame`, `result-frame`, `send-frame!` (write-lock
  discipline), `bounded-result`, invalid-message/startup-error
  builders, session-map construction and its shape schema. Session-map
  keys renamespace `:seon.host/*` → `:seon.host.session/*` — the
  design verified the session map never crosses the process boundary
  (only frame CONTENTS with `:seon.execution/*` keys hit the socket);
  RE-VERIFY that against current source and stop if it no longer
  holds.
- **`seon.host.sample`** — value retention + sampling: the get-in
  value serving band (`serve-value-sample!` and friends),
  `retain-live-value!`/retained-entry accessors,
  `acquire-sampling-policy!`.
- **`seon.host.eval`** — eval-batch serving: `agent-home-ns`,
  `wire-safe-value`, `eval-form!`, `eval-batch-result`, read-error/
  batch-summary/declared-next-ns helpers, interrupted-batch
  classification.
- **`seon.host.invoke`** — invocation lifecycle: `settle!` (the CAS
  terminal), `run-invocation!` (watchdog + W0.3 token-identity),
  `begin-invocation!`, `cancel-active!`, shutdown of active work.
- **`seon.host`** keeps: band A (the wire-contract projection /
  message schema registrations — FROZEN, W5 deletes it; spend zero
  effort there), `accept-startup!`, `serve-session!`, `start!`,
  `stop!`, `-main`, and the `::start-request`/`::host` schemas.
  `:seon.host/socket-path` and every `::start-request` key are
  UNCHANGED (they appear in launch EDN — `bin/test-writer`, operator
  config).

Require DAG (must stay acyclic):
`seon.host` → `.invoke` → `.eval` → `.sample` → `.session`, with
invoke/eval also allowed direct `.session` requires, and the existing
siblings `seon.host.context`/`.record`/`.graduate` unchanged.

Formerly-private fns that cross a new file boundary become public in
their new owner with their existing names and a correct
`:malli/schema`; do not rename them while moving (one change class per
unit — renames beyond the namespace itself are out of scope).

## Amendment (2026-07-21 night, after the implementer's DAG stop)

Band A's constants are consumed by the leaf bands, so "band A stays in
`seon.host`" breaks the require DAG. Resolution: band A (the
wire-contract projection — protocol-version, message vocabulary,
registrations) moves WHOLESALE into `seon.host.session` as its marked
wire-contract section, unchanged, still carrying the W5-deletion
comment. One owner, no duplication; W5's deletion targets session.clj.
The implementer's `.cljc`-promotion alternative is recorded as W5
evidence, not done now (it entangles death-row CLJS bands).
`seon.host` may require `seon.host.eval` directly (`agent-home-ns`);
the DAG constraint is acyclicity, not chain-only. Pre-split baseline:
342/2584 green.

## Owned paths (touch nothing else)

- `src/seon/host.clj`
- new: `src/seon/host/session.clj`, `src/seon/host/sample.clj`,
  `src/seon/host/eval.clj`, `src/seon/host/invoke.clj`
- writer-side tests that require adjusting ONLY because a moved fn's
  namespace changed (enumerate them in your summary; the host
  conformance/pool/graduate suites must otherwise stay untouched —
  they assert behavior through the public session/wire surface).

Protected: everything else — especially `src/seon/host/context.clj`,
`src/seon/host/record.clj`, `src/seon/host/graduate.clj`,
`src/seon/db/**`, and all CLJS (another lane owns `src/seon/ai/*` right
now). Do not run `bin/seon`, do not commit; leave the diff for
orchestrator review.

## Gates (run them; report honest results)

- `bin/test-writer` FULL — including the host conformance, pool,
  graduate, and robustness-battery suites — green BEFORE the split
  (record the baseline counts) and green AFTER with the same counts.
- Zero behavior change: no frame, receipt, or error-value shape
  differs. If any test asserts on a `:seon.host/*` session key that
  you renamespaced, that test edit is in scope — but a WIRE assertion
  that breaks means the split changed behavior: stop and report.
- If a run fails on infrastructure (port/lock contention) rather than
  an assertion, wait briefly and retry once before reporting.

---
type: prd
status: active
tags: [prd, architecture, agent, database]
---

# W3c1 — the host consumes the run fence

## Grounding preamble (mandatory)

Read the actual source of every file you touch and every interface you
connect to before editing. Report: (a) a better seam if found; (b) the
owners' exact terms. **Stopping early to report is FREE.** If source
contradicts this spec, stop and report.

Read FIRST:
`docs/prds/sci-execution-runtime/research/w3-parity-grounding-2026-07-21.md`
§W3c — every interface below is file:line-grounded there. Note its
corrections: `invoke/settle!` is process-local terminal-frame ownership,
NOT the run fence; the wire's optional `:seon.execution/run-fence`
(host/session.clj:54) already arrives and is silently unconsumed.

## Goal

Host eval batches honor the database run fence exactly as the child
does: at batch start, exactly ONE `db/cas-assert`-shaped transaction
against the run pointer, with expected values from the INVOCATION
database value (the child idiom: eval.cljs:5172-5240; fence meaning:
the agent still points at the named open run, agent/run.cljs:381-389).
On CAS loss: the batch returns empty counts plus `:seon.eval/fenced?
true`, creates ZERO receipts, and evaluates nothing. Absent fence =
today's behavior unchanged (the field is optional).

Known seam gap (grounding): the host's current transact path resolves
the current head (host/context.clj:1440) and cannot pin the invocation
database; the pinned-read seam (context.clj:1473) shows the explicit-
immutable-database idiom. Strengthen the ONE existing transact seam to
accept the explicit database value — do not add a parallel transact
path. If the writer protocol side lacks what the CAS-at-invocation-db
needs, STOP AND REPORT (the child does this today through seon.db —
read how its cas-assert request carries the database value and mirror
the same protocol usage).

## Falsifiers (bake into tests — grounding risks)

- Capture the writer request: it uses the invocation database value,
  contains exactly one run-pointer CAS, nothing else.
- Failed CAS: zero receipts, zero eval-form! calls, `:seon.eval/fenced?
  true` in the batch envelope, and the settled frame reports it
  honestly.
- Fence held: behavior byte-identical to today (receipts, results,
  output unchanged).

## Owned paths (touch nothing else)

- `src/seon/host/invoke.clj` (destructure + thread the fence),
  `src/seon/host/eval.clj` (batch-start fence + fenced skip),
  `src/seon/host/context.clj` (the transact-at-explicit-database
  strengthening), `src/seon/host/session.clj` only if the schema needs
  a field it lacks (read first — the field already validates).
- Writer-side tests that assert batch behavior (enumerate; the host
  conformance suite is the home).

Protected: everything else — `seon.execution*` (pod side; its
result-current? check stays), settle!'s CAS (different concern), the
child (death row, read-only reference). Another lane owns the three
CLJS conformance tests (q23) — don't touch them. No commits, no
lifecycle ops.

## Gates

Full `bin/test-writer` (baseline 354/2670 — record after). A live
fence proof through MCP eval if the running cluster makes one honest
(a batch with a stale fence against the live writer; report what you
could and couldn't prove live).

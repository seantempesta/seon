---
type: landing
status: current
lane: fix-nil-call-preparation
created: 2026-09-24
scope: Census rows `hook` and `plan-for` in docs/research/agent-platform/ambiguous-nil-producers-2026-09-24.md, plus the contracts-census bypass row for src/seon/call_preparation.clj.
---

# Lane fix-nil-call-preparation (2026-09-24)

## Broken or not proved first

- **The installed tier did not run these changes.** default (pid 90471) runs the committed
  archive `ce7384682`, and hook publication is off. `bin/test-check default --test
  seon.call-preparation-test/unreadable-custody-refuses-the-call-instead-of-running-it-unprepared`
  answered "The requested test is not eligible." All evidence below comes from namespace copies
  of HEAD and of the change. They were loaded into default as throwaway `tmp.fix-nil-cp.*`
  namespaces, never over `seon.call-preparation`, and removed afterwards. The copies are
  **unarmed**, so no contract of the changed functions was exercised. The harness is
  `tmp/fix-nil-call-preparation/{harness,test_copy}.clj`, copied from `tmp/arity-snapshot/`.
  **RESET NEEDED** (root): this is needed before the installed request can run.
  `seon.dev.mcp/branch-errors` also still reports "seon.error has no occurred-since (RESET NEEDED)".
- **The contracts-census bypass request was not carried out as asked.** Routing hook's three
  `mi/-f->original` calls through the armed functions is not needed to avoid recursion, because
  no armed wrapper re-enters SCI. It would cost +15.6 µs on a hook of about 8.5 µs, and the hook
  runs on every SCI Var call. That is about +180%, far over the 20% bound. I kept the bypasses and
  replaced the stale cost comment (87-of-93 µs) with today's measurements. The owner has to rule on this.
- In the copy runs of the whole namespace, 14 tests fail the **same way on HEAD and on the change**.
  The causes are fixture writes refused at `:seon.program/definition-digest`, and copy-ns probe
  identities that are absent from the program rows. These come from the harness and the stale JVM,
  not from this diff.

## What changed

- `plan-for`: contract-transaction's declared `:seon.db/error-result` used to become nil
  ("not contracted") through `(when (number? …))`. It is now returned. The six `db/q` reads and
  `argument-validators`' read are also checked, by the new private `refused-read`.
  `plan` returns the error and caches nothing. The contracts of both functions gained the
  `:seon.db/error-result` arm. The `db.clj` arity callers (`:3780`, `:3828`) already propagate a
  refused plan, so no caller needed converting.
- `hook`: an invalid `db/db` read (for example a released connection), an invalid snapshot or an
  invalid plan used to return the arguments unchanged, so the call ran without its supplied
  defaults. Now the hook returns `(reduced read-error)` with `:seon.call-preparation/target-symbol`
  naming the callee. Two cases still pass through, because they genuinely have nothing to prepare:
  a callee with no program identity, and a ctx with no call-preparation state or environment.
  Both hook callers (`sci/eval.clj:249`, `sci/kernel.clj:632`) already handle `reduced`.

## Evidence (default JVM, copies, 2026-09-24)

| Probe | HEAD | change |
|---|---|---|
| REPL: `hook` on a ctx with a released connection, `#'seon.db/pull ['[*] 1]` | `[[*] 1]` (runs bare) | — |
| `an-unreadable-program-graph-is-not-an-uncontracted-function` | 3 fail (nil returned), 3 pass | 6 pass, 197 ms |
| `unreadable-custody-refuses-the-call-instead-of-running-it-unprepared` | error: `Wrong number of args (1) passed to probe-received-database?` (ran unprepared) | 5 pass, 258 ms |
| `an-unavailable-supplier-refuses-before-the-body`, `supplier-failures-…` | pass | pass |
| hook pass-through (`str`), µs/call, 3 × 20 000 | 8.34 / 8.56 / 8.80 | 8.46 / 8.60 / 9.24 |
| hook with a closure callee, µs/call | 8.25 / 8.34 / 8.94 | 8.31 / 8.35 / 8.48 |
| armed vs unarmed (µs): `env/of`, `db/db`, `current-snapshot` | 4.19 / 7.88 / 11.63 vs 1.25 / 5.70 / 1.18 | unchanged (bypasses kept) |

The unreadable program graph used in the tests is a `:write` in-memory database with no
`:seon.fn/sym` attribute. Datahike refuses the AVET read, and `seon.db/datoms` returns
`:seon.db/invalid-read` with `refused-read-operation seon.db/datoms`. A function that is absent
from a readable graph still gets `nil`. Running the whole namespace copy (both copies) took 78 s.
That was a one-off harness cost, and I did not repeat it.

## Commits and sizes

- `72ae5a2c1` plan-for returns its read error. `19f21d9a1` hook refuses a call it cannot prepare.
  `c68666dba` bypass comment with measured cost.
- `src/seon/call_preparation.clj`: +106 / −64 (net +42). About 20 of the added lines only re-indent
  existing lines. `test/seon/call_preparation_test.clj`: +56.
- clj-kondo: 0 errors. The 3 shadowed-var warnings in source and the unused `seon.error` require in
  the test were already there.

## Out of scope, still open

- The census row `newest-row-transaction` (S2, `call_preparation.clj` ~:285) was not in my
  assignment. An unreadable `db/history` still falls back to the current database. See
  docs/research/agent-platform/ambiguous-nil-producers-2026-09-24.md.
- `argument-validators` has no Malli contract. That was already true before this lane.

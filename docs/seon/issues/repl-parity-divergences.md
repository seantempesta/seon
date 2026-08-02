---
type: issue
status: open
severity: friction
tags: [issue, sci, testing]
---

# The REPL-parity gate proves 24 divergences from stock Clojure

`test/seon/repl_parity_test.clj` is the standing discovery gate built
2026-08-01 from the mined checklist
(`research/repl-parity-test-mining-2026-08-01.md`). It runs behavior
rows through the PRODUCTION door (`fork → acquire! → evaluate`) and
asserts STOCK Clojure's behavior, so our failings surface as named
divergences instead of passing tests that hide the gap. Known
divergences are derived from test metadata, reported loudly, and the
gate FAILS if one unexpectedly starts passing (someone fixed it and
must promote the row).

Accounting at landing: **88 checklist rows** (the earlier "59" omitted
all 21 route-(a) rows), 69 executable tests, **35 passing, 34 known
divergences, 19 pending with explicit reasons**. `bin/test
seon.repl-parity-test seon.sci.eval-test` — 89 tests, 147 assertions,
green.

The proven divergence families, each a row to promote as it is fixed:

- sequence/list printing and elision faces (every seq prints as a
  vector; markers are not Clojure's `...`);
- missing REPL history and error bindings (`*1 *2 *3 *e`);
- absent `clojure.repl` helpers (`source`, `apropos`, `find-doc`,
  `pst`);
- error-face and location differences from `clojure.main`'s triage;
- merged stdout/stderr;
- incomplete Var, namespace, and metadata faces.

Most of this family is closed by the SEALED print-path contract
(`plan/print-path-design-2026-08-01.md`, ruling #26) once implemented —
the gate is its acceptance evidence.

Newly discovered by building the gate, not previously known:

- Float infinity conversion failures;
- `ns-unmap` isolation before the terminal transaction applies.

Closed while landing: `#inst`/`#uuid` were refused by an accident of
the reader wrapper (one computed line — defer to
`clojure.core/default-data-readers`); the five obsolete
`seon.sci.reader-test` assertions that required their rejection are
updated.

Also recorded in the mining ledger: several upstream citations in the
checklist were incorrect or overstated and are corrected there.

## Backlog triage 2026-08-02

**Still real, with ten rows promoted.** Commit `050fff5c7` promoted A1, A9,
B1, B4, B9, B10, E4, H1, H4, and H6 after the sealed print path landed. The
independently recounted current partition is 88 rows: 45 passing executable,
24 known divergences, and 19 non-executable pending rows (the roadmap's
temporary “23 pending” wording counted four newly executable rows twice).
The destination is the REPL-parity follow-up, one semantic family at a time;
the gate remains the recurring acceptance surface.

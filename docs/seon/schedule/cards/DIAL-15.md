---
title: "DIAL-15: Descendant sample interval is a dial"
type: reference
status: waits on DIAL-5, SP-3
created: 2026-10-08
---

# DIAL-15: Descendant sample interval is a dial

Ledger row DIAL-15 (group O). Source: `dials/dials.md` D29. Model: Sonnet. Host-bound (process supervision):
throwaway check.

## Site (verified at `1fcdbf47a`)

`seon.shell.jvm/descendant-sample-ms` 50 (`src/seon/shell/jvm.clj:243`, measured ~0.4 ms per sample, under 1% of
a core at 50 ms, `:242`), read at `:271` and `:277` in the wait loop.

## Files

- `resources/seon/schemas/seon.config.shell.edn` (dirty at `1fcdbf47a`; DIAL-5 adds `:cleanup-ms` to it)
- `src/seon/shell/jvm.clj` (dirty at `1fcdbf47a`; SP-3 / impl-sp3 holds it)
- `test/seon/shell/jvm_test.clj`

## Schema

`:descendant-sample-ms [:int {:min 1 :seon.config/dial true :seon.config/default 50 :description "How often a waiting shell command samples its process descendants (one sample ~0.4 ms)."}]`

## Functions

Delete the `def`; the wait loop's function takes the interval from the request it already receives (add the key as
optional to that request's contract, filled by the caller from the effective configuration as DIAL-5 does for the
cleanup bounds; the declared default through DIAL-5's `declared-bound` reader when absent).

## Proof

New `seon.shell.jvm-test/descendant-sample-comes-from-config`: a command run with `:seon.config.shell/descendant-sample-ms 200`
that exits after 10 ms answers no sooner than its first sample allows and records the interval it used (assert the
data the run result carries, not timing alone: add the interval to the result only if a consumer reads it;
otherwise assert the request reached the loop by a 1 ms interval completing within 100 ms).

`--test seon.shell.jvm-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-15: the descendant sample interval is a dial" --test seon.shell.jvm-test --test seon.config-defaults-test resources/seon/schemas/seon.config.shell.edn src/seon/shell/jvm.clj test/seon/shell/jvm_test.clj`

## Dependencies

DIAL-5 (schema file, reader), SP-3 (`shell/jvm.clj`).

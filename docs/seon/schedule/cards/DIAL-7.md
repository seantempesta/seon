---
title: "DIAL-7: Analysis cache age is a dial"
type: reference
status: waits on SR-S, PB-12
created: 2026-10-08
---

# DIAL-7: Analysis cache age is a dial

Ledger row DIAL-7 (group E). Source: `dials/dials.md` D16. Model: Sonnet. Host-bound (boot-time analysis):
throwaway check.

## Site (verified at `1fcdbf47a`)

`src/seon/fn/analyzer.clj:399`: `stale-before (- (System/currentTimeMillis) (* 24 60 60 1000))`, inside the boot
lint of non-program checkout roots (`seon.fn.analyzer/ensure-dependency-analysis!`, `:388`, contract `[:=> [:cat [:vector :string]] :map]`):
sibling analysis cache directories older than a day are deleted.

## Files

- `resources/seon/schemas/seon.config.maintenance.edn`
- `src/seon/fn/analyzer.clj`
- `test/seon/fn/analyzer_test.clj`

## Schema

`:analysis-cache-max-age-ms [:int {:min 0 :seon.config/dial true :seon.config/default 86400000 :description "A sibling clj-kondo analysis cache directory older than this is deleted at boot."}]`

## Functions

`ensure-dependency-analysis!` gains a second argument `max-age-ms` (contract `[:=> [:cat [:vector :string] [:int {:min 0}]] :map]`);
its boot caller passes the applied configuration's value (CLAUDE.md: boot reads each dial from the configuration
it applied). Every caller converts in the same commit (`rg -n "ensure-dependency-analysis!" src script test`).

## Proof

New `seon.fn.analyzer-test/analysis-cache-age-comes-from-config`: two sibling cache directories under a fixture
parent, one with mtime two hours old; with `max-age-ms` one hour it is deleted, with one day it stays.

`--test seon.fn.analyzer-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-7: the analysis cache age is a dial" --test seon.fn.analyzer-test --test seon.config-defaults-test resources/seon/schemas/seon.config.maintenance.edn src/seon/fn/analyzer.clj test/seon/fn/analyzer_test.clj`

## Dependencies

SR-S (same schema file), PB-12b and UL-21 (`analyzer.clj`).

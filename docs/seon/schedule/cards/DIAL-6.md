---
title: "DIAL-6: Git inventory bound is a dial"
type: reference
status: waits on DIAL-3
created: 2026-10-08
---

# DIAL-6: Git inventory bound is a dial

Ledger row DIAL-6 (group E). Source: `dials/dials.md` D12. Model: Sonnet. Host-bound (test input selection):
throwaway check.

## Site (verified at `1fcdbf47a`)

`seon.test.cache/inventory-bound-ms` (`src/seon/test/cache.clj:17-20`), a `def` of 30000: the bound on Git's
local file inventory; expiry refuses selection rather than omitting inputs. Read by `git-output` (`:22`) and its
callers.

## Files

- `resources/seon/schemas/seon.config.test.edn`
- `src/seon/test/cache.clj`
- `test/seon/test_cache_test.clj` (the existing `seon.test-cache-test`; there is no `test/seon/test/cache_test.clj`)

## Schema

`:inventory-bound-ms [:int {:min 1 :seon.config/dial true :seon.config/default 30000 :description "Bound on Git's local file inventory for test input selection; expiry refuses selection."}]`

## Functions

Delete the `def`. `git-output` (`:22`) is called by `input-paths` (`:36`, call at `:46`) and `gitlink-digests`
(`:178`, call at `:189`); both public functions take `:seon.config.test/inventory-bound-ms` from the request map its caller passes (`seon.test/run`
already holds `effective`, `test.clj:~2282`), with the declared default when called without one (read through
the schema resource as DIAL-5's `declared-bound` does; one shared reader if DIAL-5 landed it in a common place,
otherwise a private one here).

## Proof

New `seon.test-cache-test/the-inventory-bound-comes-from-config`: with `:seon.config.test/inventory-bound-ms 1`
the inventory read refuses with the bound's error naming the dial; with the default it answers.

`--test seon.test-cache-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-6: the Git inventory bound is a dial" --test seon.test-cache-test --test seon.config-defaults-test resources/seon/schemas/seon.config.test.edn src/seon/test/cache.clj test/seon/test_cache_test.clj`

## Dependencies

DIAL-3 (same schema file).

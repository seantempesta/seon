---
type: issue
status: resolved
severity: blocking
created: 2026-09-23
tags: [issue, analyzer, clj-kondo, cache, tests]
---

# The analyzer refuses a checkout file outside the source roots as a foreign kondo entry

## Problem

`seon.fn.analyzer/invoke-kondo` (`src/seon/fn/analyzer.clj:320`) lints with the
checkout's shared cache (`.clj-kondo/.cache`). clj-kondo writes a cache entry for
every namespace the linted files define. `stale-cache-entries` (`:244`) then marks
the entry for a namespace defined by a file inside the checkout but outside every
declared source and dependency root as `::foreign` (`:307-310`). A foreign entry
in the shared cache has no `::source` (`:317-318`), so it is deleted and not
rebuilt, and the rerun writes it again. The second check finds it again and
throws "clj-kondo cache entries changed again during analysis." (`:370`).

So analyzing any file under `tmp/` always throws. The fixture
`seon.fn-test/with-provenance-file` (`test/seon/fn_test.clj:1821`) writes into
`tmp/fn-test/<uuid>/`, so every test that analyzes such a file is red.

## Evidence (2026-09-23, default pid 90963, lane fixture-reds)

Probe, JVM mode, throwaway namespace `tmp.fixture-reds`: write the
`test/fixtures/program_facts_s1/source.txt` source to
`tmp/fixture-reds-probe/<uuid>/sample/s1.clj`, then call
`(#'seon.fn.analyzer/invoke-kondo {:lint [path]})`. It threw in 49 ms with
`::stale [{::cache-file ".clj-kondo/.cache/v1/clj/sample.s1.transit.json"
::namespace sample.s1 ::reason ::foreign}]`.

Red it causes: `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`
(runs 3cc834ea6b90, e2bfb847b456, c55c8bc1da2d). It is deterministic and runs
alone in a 700 ms body.

## Wanted

An entry this run's own `:lint` files define is not foreign to this run. Either
it is excluded from the foreign check, or the fixture analyzes with an explicit
cache root it owns, since a supplied root "belongs to its caller" (`:261-263`).
The analyzer owner decides which. The fixture file `test/seon/fn_test.clj` and
`src/seon/fn/analyzer.clj` are outside lane fixture-reds.

## Resolution (2026-09-23, lane analyzer-tmp)

`stale-cache-entries` marks an entry `::foreign` only when this analysis does
not define its namespace (`(nil? current)`): the entry of a namespace this run
defines at that path was just written from its bytes, and a mirror maps back to
that path through `analyzed-source-path`. A leftover entry read by a run that
does not define it is still foreign and deleted. Probes on default (JVM,
`tmp.analyzer-tmp`): `seon.fn/rows` over a `tmp/` root threw in 570 ms before,
returns 6 rows in 475 ms after; `stale-cache-entries` on the same entry answers
`[::foreign]` for a non-defining run and `[]` for the defining run.
`seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`
no longer throws here; it now reds later, in its evaluated half, on the
identity-only namespace row class (see
`turn-writers-upsert-bare-namespace-rows-without-a-definition-digest.md`,
`source-rows` residue). Landing:
`docs/prds/agent-platform/landing/lane-analyzer-tmp-2026-09-23.md`.

---
title: "DIAL-14: Competency run bounds are dials"
type: reference
status: waits on C0
created: 2026-10-08
---

# DIAL-14: Competency run bounds are dials

Ledger row DIAL-14 (group K). Source: `dials/dials.md` D28. Model: Sonnet. Host-bound (schema resource; the driver
boots sample clusters): throwaway check.

## Sites (verified at `1fcdbf47a`)

- `seon.eval.drive/default-run-cap` 6 (`src/seon/eval/drive.clj:18`), read in `run-sample!` (`:376`).
- `(* run-cap 240000)` (`:378`): 240 s per run when the request gives no `:seon.eval.drive/remote-timeout-ms`.

**An existing dial already answers the cap**: `:seon.config.run/max-episode-runs`
(`resources/seon/schemas/seon.config.run.edn`, per agent, optional), which `seon.repl` reads
(`src/seon/repl.clj:74-75`). The driver uses that dial instead of a new one; only the per-run time is new.

## Files

- `resources/seon/schemas/seon.config.run.edn`
- `src/seon/eval/drive.clj`
- `test/seon/eval/drive_test.clj`

## Schema

`:run-time-limit-ms [:int {:min 1 :seon.config/dial true :seon.config/default 240000 :description "Wall bound per run of a competency episode; the episode's bound is runs × this."}]`

## Functions

- Delete `default-run-cap`. `run-sample!`'s cap: `(or (:seon.eval.drive/run-cap request) (:seon.config.run/max-episode-runs effective) 6)`
  is **not** acceptable (a literal fallback); the shipped decision for `max-episode-runs` must exist in
  `config/default.edn` or as the schema's `:seon.config/default` (add `:seon.config/default 6` if it has none),
  and the driver reads the sample cluster's effective row after boot (it boots the sample cluster in
  `run-sample!`; read the row there with `config/effective`).
- The timeout: `(or (:seon.eval.drive/remote-timeout-ms request) (* run-cap (:seon.config.run/run-time-limit-ms effective)))`.

## Proof

New `seon.eval.drive-test/run-bounds-come-from-config`: the pure part, extracted as private
`episode-bounds` `[request effective]` → `{:run-cap n :timeout-ms m}` (contract
`[:=> [:cat :seon.eval.drive/sample-request :map] [:map [:run-cap [:int {:min 1}]] [:timeout-ms [:int {:min 1}]]]]`),
answers `{:run-cap 2 :timeout-ms 20}` for an effective row with `max-episode-runs` 2 and `run-time-limit-ms` 10,
and the request's values win when given.

`--test seon.eval.drive-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-14: competency run bounds come from config" --test seon.eval.drive-test --test seon.config-defaults-test resources/seon/schemas/seon.config.run.edn src/seon/eval/drive.clj test/seon/eval/drive_test.clj`

## Dependencies

C0 (the competency driver rewrite, which may move `run-sample!`).

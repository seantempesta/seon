---
title: "DIAL-10a/b/c: Profile thresholds are dials"
type: reference
status: waits on RO-10
created: 2026-10-08
---

# DIAL-10a, DIAL-10b, DIAL-10c: Profile thresholds are dials

Ledger rows DIAL-10a (D19, D20), DIAL-10b (D21), DIAL-10c (D22). Model: Sonnet. Host-bound (profiling wraps every
armed call; new schema family): throwaway check.

## Sites (verified at `1fcdbf47a`)

- `seon.profile/slow-ms` 1000 (`src/seon/profile.clj:50-52`, "the owner's bound"), read at `:434` and in
  `transaction-observation` (`:446`).
- `seon.profile/explained-calls` 10000 (`:436-439`), `seon.profile/hot-limit` 20 (`:441-443`), both read by
  `transaction-observation`.
- `seon.batch/repeated-calls` 100 (`src/seon/batch.clj:114-128`).
- `seon.profile.measurement/min-calls` 20 (`src/seon/profile/measurement.clj:34`).

## DIAL-10a (profile.clj)

- New family `resources/seon/schemas/seon.config.profile.edn` (copy an existing family file's header, e.g.
  `seon.config.test.edn`):
  `:slow-ms [:int {:min 1 :seon.config/dial true :seon.config/default 1000 :description "An operation over this many milliseconds is explained (CLAUDE.md: one second)."}]`,
  `:explained-calls [:int {:min 1 :seon.config/dial true :seon.config/default 10000 :description "Armed calls since a world's previous transaction above which its work is stamped even under slow-ms."}]`,
  `:hot-limit [:int {:min 1 :seon.config/dial true :seon.config/default 20 :description "Definitions a stamped transaction names by self time."}]`.
- `transaction-observation` reads the three from its world: add `:seon.config.profile/thresholds`
  (`[:map [:seon.config.profile/slow-ms :int] [:seon.config.profile/explained-calls :int] [:seon.config.profile/hot-limit :int]]`)
  to the world the constructors build (they read the effective configuration once per world; see DIAL-4), and the
  `:434` reader takes the threshold as an argument from its caller. Delete the three `def`s.
- Proof `seon.profile-test/profile-thresholds-come-from-config`: `transaction-observation` over a world whose
  `slow-ms` is 1 and a 5 ms operation stamps it; with 1000 it does not.
- `--test seon.profile-test --test seon.config-defaults-test --test seon.turn-test`
- Commit: `bin/lane commit <lane> -m "DIAL-10a: profile thresholds are dials" --test seon.profile-test --test seon.config-defaults-test --test seon.turn-test resources/seon/schemas/seon.config.profile.edn src/seon/profile.clj src/seon/turn/world.clj test/seon/profile_test.clj`
- Deps: RO-9, RO-10 (`profile.clj`); DIAL-4 (`turn/world.clj`).

## DIAL-10b (batch.clj)

- `:repeated-calls [:int {:min 1 :seon.config/dial true :seon.config/default 100 :description "A function called more than this many times in one evaluation is reported as repeated."}]`
  in `seon.config.profile.edn`; `seon.batch/call-profile` (`:119-128`) reads it from the world's
  `:seon.config.profile/thresholds`. Delete the `def`.
- `seon.batch/call-profile` (`:119`) reads the threshold from its world argument instead of the `def`.
- Proof: `seon.repl-test` (which asserts the call profile today; also `seon.run-test`) gains a case: a world with
  `repeated-calls` 2 and a form calling one function three times names it in the call profile.
- `--test seon.repl-test --test seon.run-test --test seon.config-defaults-test`
- Commit: `bin/lane commit <lane> -m "DIAL-10b: the repeated-call threshold is a dial" --test seon.repl-test --test seon.run-test --test seon.config-defaults-test src/seon/batch.clj resources/seon/schemas/seon.config.profile.edn test/seon/repl_test.clj`
- Deps: DIAL-10a; CN-20 (`batch.clj` folds into `repl.clj`: land before it or inside it).

## DIAL-10c (measurement.clj), only if it survives

- If S5-02 deleted stored measurements (and `min-calls`'s consumer), this row is dropped: check
  `rg -n "min-calls" src` first.
- Otherwise `:min-calls [:int {:min 1 :seon.config/dial true :seon.config/default 20}]` in
  `seon.config.profile.edn`, threaded from the measurement's caller.
- Deps: S5-02.

## Cost

D: one map read per stamped transaction instead of a constant.

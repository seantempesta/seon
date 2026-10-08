---
title: "Card SP-1c Delete the error evidence dial"
type: reference
status: active
created: 2026-10-08
---

# SP-1c Delete the error evidence dial

Design: `docs/research/agent-platform/2026-10-08/storage-point/storage-point.md` §1 (row
`:seon.config.error/max-evidence-bytes`). Lane: Sonnet, one scripted sweep.

After SP-1a and SP-1b nothing reads `:seon.config.error/max-evidence-bytes`; it is still declared, defaulted and
carried through eight namespaces. Delete it and every carrier in one commit.

## Files

`config/default.edn` (`:245-251`, the dial and its comment), `resources/seon/schemas/seon.config.error.edn`,
`resources/seon/schemas/seon.error.edn`, `resources/seon/schemas/seon.instrument.edn`,
`resources/seon/schemas/seon.turn.loop.edn` (`:252-253`), `src/seon/error.clj` (`:558`, `:1534`),
`src/seon/instrument.clj` (`:623-624`, `:667-669`, `:1085`, `:1107-1109`), `src/seon/sci/eval.clj` (`:768`, `:783`,
`:796-797`, `:2315-2316`, `:2388`), `src/seon/cluster.clj` (`:2369-2371`, `:2597-2598`, `:3317-3318`),
`src/seon/turn.clj` (`:3189-3190`, `:4155-4156`), `src/seon/schedule.clj` (`:552-553`), `src/seon/test.clj`
(`:2196`, `:2232`), `src/seon/fault.clj` (`:168`, `:205-206`), `bench/seon/contracts_corpus.edn`, and the tests
`rg -l max-evidence-bytes test` lists (21 files on 2026-10-08: `test_support.clj`, `error_storage_bytes_test.clj`,
`ai_stream_fold_test.clj`, `run6_stall_test.clj`, `error/branch_test.clj`, `turn_test.clj`, `turn_boot_test.clj`,
`reset_edges_test.clj`, `instrument_test.clj`, `render/web_debug_test.clj`, `schema_test.clj`, `profile_test.clj`,
`render/faults_test.clj`, `error_evidence_test.clj`, `error_test.clj`, `schema/datahike_parity.edn`,
`schema/predicate_metadata_refusal_test.clj`, `problems_test.clj`, `cluster/wake_test.clj`, `cluster_test.clj`,
`error_result_test.clj`).

## Functions

- One script removes every map entry, `select-keys` member, destructured binding, `assoc` and schema map entry
  naming `:seon.config.error/max-evidence-bytes`; then `clj-kondo --lint src script test --cache false`, load, and
  hand-fix what the script cannot (a `cond->` left with one clause, an `or` default left with one arm).
- `:seon.error/normalize-request` and `:seon.error/prepare-request` (in `seon.error.edn`) lose the required member.
- `seon.fault`'s docstring at `:168` loses the sentence naming the dial.

## Proof

`seon.config-test/no-error-evidence-dial` (new, `test/seon/config_test.clj`): the effective configuration of
`"default"` read through `seon.config/effective` has no `:seon.config.error/max-evidence-bytes` key, and the
installed projection's registry does not resolve it (`(mr/schema registry :seon.config.error/max-evidence-bytes)`
throws `::m/invalid-schema`, asserted with `thrown?`).

## Other tests reaching it

`seon.error-test`, `seon.error-evidence-test`, `seon.instrument-test`, `seon.turn-test`, `seon.cluster-test`,
`seon.problems-test`, `seon.config-test`.

## Cost

None at runtime; eight namespaces stop carrying one key.

## Deleted

`:seon.config.error/max-evidence-bytes`, its default and every carrier.

## Commit

`bin/lane commit <lane> -m "SP-1c: delete the error evidence dial" --test seon.config-test --test seon.error-test --test seon.error-evidence-test --test seon.instrument-test --test seon.turn-test --test seon.cluster-test --test seon.problems-test <every file above>`

## Dependencies

SP-1b. `src/seon/turn.clj` is held by lane `f4a`; `src/seon/fault.clj` moves into `seon.error` in E-SDELETE: land
SP-1c before E-SDELETE, and after `f4a` releases `turn.clj`.

## Reset

None (adoption retires the dial; a stored config value is retracted with it).

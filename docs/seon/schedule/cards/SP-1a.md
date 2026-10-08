---
title: "Card SP-1a A recorded error stores its shown text and no data-edn"
type: reference
status: active
created: 2026-10-08
---

# SP-1a A recorded error stores its shown text and no data-edn

Design: `docs/research/agent-platform/2026-10-08/storage-point/storage-point.md` (§§ trigger, 3, 4). Supersedes the
error half of ledger row SR-T. Lane: Sonnet.

The error's one stored form is the value printer's shown text, already produced by `seon.error/prepare-result`
(`src/seon/error.clj:492-530`) and placed by `seon.blob/inline-or-stage`. The second serialization
`:seon.error/data-edn`, its fitting loop and the b233d838f restoration are deleted together.

## Files

`src/seon/error.clj`, `resources/seon/schemas/seon.error.edn`, `resources/seon/schemas/seon.error.occurrence.edn`,
`resources/seon/tasks/seon.error.edn`, `src/seon/render/transcript.clj` (`:60`, `:1057`), `src/seon/eval/drive.clj`
(`:212`), and the tests naming the deleted keys: `test/seon/error_evidence_test.clj`, `test/seon/error_test.clj`,
`test/seon/error_result_test.clj`, `test/seon/fault_test.clj`, `test/seon/problems_test.clj`,
`test/seon/test/one_request_test.clj`, `test/seon/sci/eval_test.clj`, `test/seon/cluster/fault_storage_test.clj`,
`test/seon/cluster/turn_test.clj`, `test/seon/cluster/listener_failure_test.clj`, `test/seon/cluster/armed_test.clj`,
`test/seon/schema/datahike_parity.edn` (`rg -l 'data-edn|:seon.error/capped\?|:seon.error/data-size' test`).

## Functions

- DELETE from `seon.error/prepare` (`error.clj:548-723`): `admitted`, `full-edn`, `inline-limit`, `marker`,
  `data-size`, the `(assoc :seon.error/data-edn full-edn)` on the observation (`:604-606`), the `fit-fact-payload`
  call (`:687-690`) and the `capped?` recomputation (`:691-698`). `fact` becomes `base-fact` with
  `:seon.error/message (message source failure)` (a string; the errors rule makes it short fixed text) plus the
  instrument fields exactly as `fit-fact-payload` assoc'd them today (SP-1b deletes those). Drop
  `:seon.error/capped? true` from `base-fact`.
- REVERT b233d838f (`:710-717`): `:seon.error/source` is `(merge observation result)` with the producer-digest
  `cond->` kept; delete the three comment lines above it.
- DELETE `fit-fact-payload` (`:448-490`), `value-edn` (`:330-337`), `bounded-error-admission` (`:339-354`),
  `classifying-error-data` and `classifying-error-keys` (`:273-313`), `evidence-source` (`:315-328`) when `rg` shows no
  other caller after the edit; keep `bounded-admission`, `evidence-caps`, `bounded-text`, `admitted-size`,
  `project-observation` (SP-1b deletes them).
- DELETE `fact-source` (`:760-775`); in `rendered-reply` (`:2019-2035`) drop `source` and call `reply-value` with
  `value`.
- CHANGE `commit-call`'s `evidence` select-keys (`:1345-1357`): remove `:seon.error/data-edn :seon.error/data-size
  :seon.error/capped?`.
- SCHEMA `seon.error.edn`: delete `:data-edn`, `:data-size`, `:capped?` and every map entry naming them (`:66`,
  `:135-138`, the `:seon.error/normalization-error` form at `:424` becomes `:seon.error/base`, keeping its key);
  `seon.error.occurrence.edn`: delete the `data-edn`, `data-size`, `capped?` entries (`:48` and neighbours).
- READERS: remove `:seon.error/data-edn` from the pull selectors at `render/transcript.clj:60,1057` and
  `eval/drive.clj:212`.
- TASK `seon.error/commit-tx-stored-the-whole-admitted-data-edn-over-the-fitted-one`
  (`resources/seon/tasks/seon.error.edn:113-117`): intent "an error's stored evidence is its shown text, placed by
  the blob threshold; no second serialization is built", proof unchanged (below), evidence adds this card's commit.
- TESTS: delete assertions on the three keys; where a test asserted evidence content through `data-edn`, assert it
  on `:seon.error/shown` instead (string containment of the offending value's printed form). Delete
  `seon.cluster.fault-storage-test/lossy-subthreshold-fault-evidence-says-it-is-capped` (it tests the deleted
  `capped?`; ledger RED-4 names it red already).

## Proof

`seon.error-evidence-test/a-capped-turn-fault-says-it-is-capped` (kept, the b233d838f guard, renamed by this card to
`a-large-turn-fault-stores-only-its-shown-text`, `:seon/tasks` kept): the 300 KB map-sourced fault's occurrence has no
`:seon.error/data-edn`; it has `:seon.error/shown-size`, and either `:seon.error/shown` with
`(<= (seon.blob/utf8-size shown) 4096)` or `:seon.error/shown-blob`; settlement under 1,000 ms (kept).
`seon.error-storage-bytes-test/one-recorded-error-stores-its-shown-text-and-no-blob` (existing) stays green.

## Other tests reaching it

`seon.error-test`, `seon.error-result-test`, `seon.fault-test`, `seon.cluster.fault-storage-test`,
`seon.cluster.listener-failure-test`, `seon.test.one-request-test`, `seon.problems-test`.

## Cost

Error recording drops from about 17 passes over the evidence (admission, canonical EDN, up to 14 fit steps, size
re-admission) to the one printer pass of `prepare-result`; stored bytes per error drop by `data-edn` (observed max
4,751, sum 29,823 over 19 errors on default).

## Deleted

`:seon.error/data-edn`, `:seon.error/data-size`, `:seon.error/capped?`, `fit-fact-payload`, `fact-source`,
`value-edn`, `bounded-error-admission`, `classifying-error-data`, `evidence-source`, the b233d838f restoration.

## Commit

`bin/lane commit <lane> -m "SP-1a: a recorded error stores its shown text and no data-edn" --test seon.error-evidence-test seon.error-test seon.error-result-test seon.error-storage-bytes-test seon.fault-test seon.cluster.fault-storage-test seon.cluster.listener-failure-test seon.test.one-request-test seon.problems-test <every file above>`

## Dependencies

`src/seon/error.clj`, `test/seon/error_evidence_test.clj` and `resources/seon/tasks/seon.error.edn` are held by lane
`cards-audits` (uncommitted `:seon/tasks` line on `prepare`): start after it commits and releases. Schema change:
prove publication and adoption on a branch of the live store (`bin/lane commit` does). Before E-SDELETE (it moves
`seon.fault` into `seon.error`).

## Reset

None (adoption retires the attributes; stored values on old facts are retracted by adoption's retirement path).

---
type: issue
status: open
severity: blocker
tags: [issue, runtime, store, error-model, class/bounded-output]
---

# Fault facts store megabyte evidence inline, and every commit rewrites gigabyte index leaves — 191 GiB in 36 minutes

## Problem

`data/store` grew from 0.29 GiB to 191 GiB between 16:05 and 16:41 on
2026-09-05 (the shared root, cluster `default`, freshly reset by the
other session at 16:03). Nothing was deleted; the disk went from 624 GiB
free to 430 GiB.

Two defects compound:

1. **Unbounded fault evidence.** The fault committer writes
   `:seon.error/data-edn` INLINE as a string. Live measurement on
   `default`: 483 fault facts hold 2,055,363,827 characters of
   `data-edn`; 472 of them exceed 1 MB; the largest are 4,356,373
   characters each — a printed `clojure.core.async.flow` proc state
   (`:seon.render.web/render`, registrations, channels) captured as the
   offending argument of a `seon.instrument/contract-violated` fault from
   `seon.render.data/at`. 342 of the 483 are that one violation
   repeating. The transport law says bulky payloads are BLOBS; results and
   defs already obey it (`:seon.config.eval.result/blob-threshold`);
   faults do not.
2. **Leaf copy-on-write amplification.** The index is
   `datahike.index/persistent-set` with `:branching-factor 4096` (set by
   `e8d218690` to cut fsync count). A leaf holds up to 4096 datoms; with
   4 MB values a leaf serializes to 0.5–2.0 GiB (`pss/leaf` visible in the
   file header). Every transaction that adds a datom to such a leaf writes
   a NEW copy of the whole leaf. Retention of old commit snapshots depends
   on the garbage collector's `remove-before` policy; `:keep-history? true`
   additionally preserves temporal datom indices, not every old leaf forever.
   142 leaf files of 0.5–2.0 GiB, sizes rising
   monotonically per commit, are exactly that sequence.

Growth stopped when the fault storm stopped (log: last `render.data/at`
violation 22:39Z). Any new fault of that shape resumes it at ~1.5 GiB per
commit.

## Evidence

- `bin/seon status`: `root footprint: 190.73 GiB`.
- `ls -lS data/store | head`: 2,011,981,054 bytes, 16:41; 141 files over
  500 MB, oldest 16:05.
- `strings` on the largest file: `pss/leaf … datahike.datom.Datom
  seon.error … data-edn #:seon.print{:face :seon.print/map … :clojure.core.async.flow/pid :seon.render.web/render …`.
- Live query on `default` (jvm mode): `{:error-facts 483 :total-data-edn-chars 2055363827 :over-1mb 472 :largest-5 [[4356373 seon.instrument/contract-violated 32007] …] :kinds {contract-violated 342, unclassified 119, terminal-refusal-settlement-refused 11, turn-completion-backstop 11}}`.
- `(:index-config (:config db))` → `{:branching-factor 4096 :diff-buf-size 256}`.
- `data/clusters/default/logs/seon.log`: `SEON CORE FAULT (dev panic): seon.render.data/at violated its contract (invalid-input): invalid type`, repeated; root bootstrap turn backstop at 600 s; `write-rejected … no-such-receipt` storms.

## Owner

- Fault committer (`seon.error` / the fault-commit proc): bound the
  evidence at the seam — admit `data-edn` through the one `seon.print/fit`
  owner with the fault profile, and stage anything above the existing
  result blob threshold as a blob with a digest on the fact, exactly as
  evaluation results do. A fault fact remains within the configured inline
  byte limit and carries a digest for larger meaningful evidence.
- `seon.render.data/at` contract violation storm: a separate defect on the
  render side (what is passing a flow proc state where a value is
  expected); file/attach when the render owner reads this.
- Store index config: re-evaluate `:branching-factor 4096` against value
  size — a leaf's byte size, not its datom count, is what a commit rewrites.
  With evidence bounded, 4096 may be fine; measure before changing.

## Acceptance

- One regression: a fault whose evidence exceeds the threshold commits a
  fact under the bound with a blob digest; `data-edn` on the fact is never
  larger than the fit profile allows.
- Reset `default` (data is disposable by ruling) to reclaim the 191 GiB;
  record the footprint after reset in `bin/seon status`.
- A fault storm of 500 identical violations grows the store by kilobytes,
  measured.

## Implementation checkpoint

On 2026-09-05, `seon.error/prepare` was added as the pure boundary that
returns a fitted fact and the full meaningful admitted evidence. It excludes
the flow proc's disposable `::flow/state`, bounds the complete serialized fact
to the supplied UTF-8 inline limit, and retains the full meaningful projection
for the existing blob publication mechanism. The focused regression
`seon.error-test/fault-preparation-bounds-the-fact-and-omits-disposable-flow-state`
passed with 9 assertions, including a 100,000-character Throwable message,
contract schema, and offending arguments under a 4,096-byte fact bound.

The issue remains open: the file-backed measurement below does not meet the
original kilobyte-growth target, and the final integrated live proof is owed.

The first combined file-backed gate (`tmp/test-runs/run.7Lwok8`) ran 34 tests
and 224 assertions with 6 failures and no errors. Its 500-fault measurement
was 56,381,169 bytes before the first fault, 56,457,046 after one, and
147,575,152 after 500: 91,193,983 bytes of growth. The largest inline fact was
658 bytes, so inline bounding worked while the store still exceeded the
required growth bound. This measurement alone does not attribute the excess
to blob publication. This is measured red evidence, not acceptance.

A second pure probe found a publication edge below the nominal threshold: a
2,000-character flat error produced 2,289 bytes of admitted `data-content`, a
520-byte fitted `data-edn`, and a 1,941-byte serialized fact. A publisher that
stages only when `data-size` exceeds 4,096 would discard the omitted admitted
evidence. Blob publication must therefore also occur whenever fitted
`data-edn` differs from `data-content`; short unchanged evidence remains inline
without a blob.

The corrected same-policy file-backed regression measured 52,750,724 bytes
after initial collection, 53,263,771 after one fault, and 143,665,214 after
500. Collection reduced that to 56,242,009 bytes: 3,491,285 bytes of retained
growth. All 500 facts and the one shared evidence blob remained retrievable;
the largest inline evidence was 658 characters. Two tests passed with 18
assertions. The full measurement and reference-code grounding are in the
[storage audit](../../prds/context-generation/research/form-evaluation-storage-audit-2026-09-05.md).
This proves bounded inline evidence and working reclamation under the tested
policy, not flat storage growth or an adequate production collection schedule.

Live checkpoint 2026-09-06: default PID 14798 (started 00:19:25Z) answers MCP.
One immutable-database observation through `seon.problems` found one fault of
each signature: `seon.instrument/contract-violated`, message
`seon.fn/analyze-forms violated its contract (invalid-output): missing required key`,
and `seon.schedule/settlement-refused`, message
`The maintenance receipt transaction was refused.` The failed root run is
`1161c65f-2c79-4949-8fbe-b9c78eedb544`, attributed to the former message.
These are actual remaining errors; recurrence rate and their underlying causes
were not established by this single observation. The historical core-fault
storm must not be declared gone merely because the process is alive.

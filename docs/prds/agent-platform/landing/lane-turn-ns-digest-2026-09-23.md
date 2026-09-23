# Lane turn-ns-digest (2026-09-23)

Issue: `docs/seon/issues/turn-writers-upsert-bare-namespace-rows-without-a-definition-digest.md` (resolved).

## Slice 1: namespace rows through their digested declaration

- `append-generated-call`, `generated-run-tx`: the bare `{:seon.ns/name n}` row is
  deleted; both already reference the namespace by lookup ref.
- `record-evaluated-call`: an existing namespace is referenced (identity-only upsert
  resolves the tempid); an absent one takes the evaluation's own
  `:seon.program/row` (the ending-namespace row `seon.sci.eval` builds through
  `program/declaration-row`), so the digest is derived by the one owner.
- Cost: one `[:find ?n . ...]` identity lookup per distinct namespace in the reply
  (proportional to namespaces touched, typically 1-2), plus a linear scan of the
  reply's evaluations only for an absent namespace.
- Contracts: none touched. Changed path: `src/seon/turn.clj` (+25/-22).

## Evidence (default pid 90963)

| operation | wall ms | note |
|---|---|---|
| baseline `bin/test-check ... --test seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` | 25958 | run 8ad8eb11b01b: refused `[47279 :seon.program/definition-digest] ... Entity: #:seon.ns{:name my.agents.probe}` |
| `bin/seon init --dev default --changed src/seon/turn.clj` (1st) | 18377 | refused "The source head changed before publication." (concurrent refresh; `refresh-source!` x3 in the profile) |
| same (retry) | 40923 | adopted, commit 6ab356bb-4ce3-5759-9a82-9346c9ed1b41; profile shows `seon.test/run` and `refresh-source!` x4 from other lanes concurrently |
| after-fix test-check, same test | 41394 | run 83087cf65291: pass 38, error 0, fail 1 = duration 8820 ms over the 5000 ms bound |

Every row above is over ten seconds and is a defect outside this lane's paths:
adoption of one file is proportional to whole-program refresh
(`seon.cluster/refresh-source!`, `seon.fn/analyzed-files`), and the test runner spends
~32 s beyond the test body. Closest existing notes:
`docs/seon/issues/a-five-file-publication-spends-44-seconds-in-its-reconciliation-transaction.md`,
`docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`.
The test body itself (8.8 s under concurrent load, 11.4 s baseline) exceeds its bound.

Hot-path probe parent vs self: not comparable; the parent refuses this write.

Not converted: `source-rows` (plan-time receipts) still upserts identity-only rows.

RESET NEEDED: no.

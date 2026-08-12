---
type: research
status: active
tags: [testing, performance]
---

# Fast-tier top-20 disposition, 2026-08-03

## Measurement boundary

The timestamp-derived diagnostic profile spans 262.275 seconds and reports
892 tests / 4,054 assertions / 6 failures / 62 errors. Its top 20 account for
157.224 seconds, or 59.95% of the timestamped runner span. The source snapshot
was not a correctness gate: it included another lane's incomplete Flow IO
configuration transition. The timings remain useful attribution evidence; no
before/after claim uses its red census.

Raw artifacts:

- `tmp/test-profiles/fast-tier-lane-before-timestamp-span-2026-08-03/profile.edn`
- `tmp/test-profiles/fast-tier-lane-before-timestamp-span-2026-08-03/tests.tsv`
- `tmp/test-profiles/fast-tier-lane-before-2026-08-03/runner.log`

## Dependency ledger

- Clojure 1.12.5: `reference-code/clojure/src/clj/clojure/test.clj:711-779`
  establishes test-var metadata and fixture execution.
- Datahike `0e8601d7f2f6`:
  `reference-code/datahike`, supplying immutable database values and isolated
  child branches.
- clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`:
  `reference-code/clj-kondo`, invoked by `seon.fn/build-manifest` for static
  source publication.
- First-party owners: `test/seon/test_support.clj:29-70`,
  `src/seon/cluster/source.clj:139-190`, and
  `src/seon/cluster.clj:609-653`.

## Top-20 dispositions

| Rank | Seconds | Share | Disposition | Test and reason |
|---:|---:|---:|---|---|
| 1 | 24.688 | 9.41% | DEMOTE | `seon.dev.edit-feedback-test/split-schema-edits-run-admission-before-publication` disables current-source publication and starts the real hook subprocess twice. It protects schema admission at the edit-hook process boundary and runs in the full checkpoint. |
| 2 | 16.248 | 6.19% | FIXABLE | `seon.schema.admission-test/source-publication-records-core-on-every-program-row` performs one necessary real publication but omitted the already-built immutable manifest. It now passes `test-support/source-manifest` through `:seon.source/population-data`. |
| 3 | 15.251 | 5.82% | KEEP | `seon.reconcile-test/apply-then-reapply-converges-over-generated-populations` is one broad 60-trial mutating convergence property. Every trial requires an isolated database branch and observes committed transaction state; it does not republish source. |
| 4 | 13.825 | 5.27% | DEMOTE | `seon.oversight-test/a-booted-cluster-tells-its-live-fleet-story` boots a real cluster, awaits its bootstrap facts, and fetches the real root-page socket. It protects live fleet integration in the full checkpoint. |
| 5 | 11.052 | 4.21% | DEMOTE | `seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold` opens a real file store and work launcher and awaits durable background receipts. The file-backed integration remains in the full checkpoint. |
| 6 | 10.527 | 4.01% | KEEP | `seon.render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded` is the single 40-trial transcript law covering order, totality, equality of both projections, elision, and token bounds. |
| 7 | 10.076 | 3.84% | DEMOTE | `seon.flow-test/forced-child-jvm-death-preserves-committed-facts` forcibly terminates a child JVM after an observed commit. It protects the process-death crash boundary in the full checkpoint. |
| 8 | 9.740 | 3.71% | DEMOTE | `seon.cluster.store-test/an-in-process-refusal-never-drops-the-os-fence` needs a foreign JVM to prove the operating-system fence survives a same-process refusal. |
| 9 | 9.606 | 3.66% | DEMOTE | `seon.cluster.store-test/the-flock-fences-across-processes` starts and forcibly terminates a child JVM to exercise the real `flock` boundary. |
| 10 | 5.774 | 2.20% | KEEP | `my.background-test/poll-and-await-derive-terminal-presence-without-acknowledging` is cheap after initialization; this first fixture user was charged the one-per-JVM immutable database-base construction. Moving that cost would not reduce the suite. |
| 11 | 5.482 | 2.09% | KEEP | `seon.sci.eval-test/one-context-arms-concurrent-threads-independently` needs one interpreted evaluation to cross its time limit while its sibling completes. The duration is the concurrency separation it proves, not repeated setup. |
| 12 | 4.432 | 1.69% | FIXABLE | `seon.sci.reader-test/source-round-trips-and-spans-partition-the-tree` read every fresh source file three times in each of ten rotations. Source bytes are frozen and the relation is pure, so it now computes each file result once while retaining all ten seeded rotation trials. |
| 13 | 3.555 | 1.36% | KEEP | `seon.custody-stability-test/foreign-context-integrity-is-invariant-under-agent-evaluation` is the standing two-context, ten-trial isolation property over generated foreign forms. |
| 14 | 3.018 | 1.15% | FIXABLE | `seon.reconcile-test/reconciliation-uses-current-provenance-without-history` requires a distinct non-temporal physical database, but its population need not repeat static source analysis. It now consumes the same immutable manifest. |
| 15 | 2.928 | 1.12% | KEEP | `seon.test-support-test/a-canonical-database-is-the-production-source-population` deliberately reconstructs the expected program/schema census independently of the fixture it verifies. Sharing the fixture's expected rows would green-wash the oracle. |
| 16 | 2.342 | 0.89% | KEEP | `seon.blob-test/generated-binary-values-round-trip-through-staging-and-chunks` is the 20-trial file-backed staging, digest, inline-prefix, and chunk reconstruction property. Fresh stores prevent prior content from satisfying a later trial. |
| 17 | 2.312 | 0.88% | KEEP | `seon.cluster.message-test/generated-message-histories-preserve-identity-fanout-and-depth` is the single 60-trial state-machine property for durable message identity, fan-out, and chain bounds. |
| 18 | 2.185 | 0.83% | DEMOTE | `seon.cluster.boot-test/incremental-source-refresh-preserves-agreement-across-real-edits` publishes real source edits and forces a complete fallback after an unreported edit. It protects the publication boundary in the full checkpoint. |
| 19 | 2.143 | 0.82% | KEEP | `seon.schema.datahike-test/supported-ast-wrappers-and-aliases-have-one-declaration` is a pure 80-trial equivalence property over supported Malli AST shapes. |
| 20 | 2.040 | 0.78% | KEEP | `seon.flow-test/flow-monitor-attaches-and-publishes-the-render-graph` exercises a live in-process Flow graph and monitor socket in about two seconds; no process or publication boundary justifies demotion. |

## Mechanism changes

`seon.test-support/source-manifest` is now the public one-per-test-JVM delayed
immutable manifest. The canonical database fixture, real publication fixture,
and non-temporal reconciliation fixture all consume that same analysis while
retaining their own stores, connections, transactions, histories, and branch
semantics. A direct probe measured first force at 4,979.300 ms and the second
force at 0.002 ms with object identity preserved.

The reader property retains its ten fixed-seed trials and its complete
fresh-tree file census. Only the pure, frozen input/output relation moved
outside the rotation loop; no case or assertion was deleted.

Seven genuine process/file-store/publication integration tests now declare a
non-blank `:seon.test/long` reason. Bare `bin/test` reports each omission, and
explicit namespace selection plus `bin/test --full` continue to run them.

## Remaining proof

Run the focused FIXABLE selection, then the bare timestamped tier after the
current source/config wave reaches a coherent commit. Reconcile the fast test
count, assertion count, skipped-long count, failures, errors, and runner wall
against the terminal output before claiming an improvement.

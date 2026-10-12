---
title: "Central round: the compiled full tier on HEAD, 2026-10-12 (round 4)"
type: research
status: evidence
created: 2026-10-12
---

# Central round 4, 2026-10-12

Lane `round`. Question: what is red on HEAD after round 3 (aee027806: 2,486 green, 229 red, 117 error of 2,832), and
which class is each of red-triage.md's "E" rows (cf8b69549)?

**Answer: 2,819 of 2,905 tests ran: 2,472 green, 231 red, 116 error (14 of the errors are tests that never exited), 86 not run.
Against round 3 the totals are flat (green -14, red +2, error -1 on 86 fewer tests). Neither big owner is red: no result
carries C2's `seon.db/q` nil database or C1's carried-projection refusal.** The round is incomplete and spans several
commits (see Limits); read the totals as "about the same as round 3", not as a per-test regression ledger.

## Limits (read first)

- **Incomplete.** 86 tests did not run. A batch of 25 containing a hung member answers "outcome unknown" after 300 s; the
  driver then rebuilt the root and deferred the batch's members to run in batches of 5 and then alone. The driver was
  stopped on the coordinator's request before those finished. Not run: nearly all of `seon.cluster.boot-test`,
  `publication-*`, `source-*`, `squash-test`, `wake-test`, `write-back-test`, `status-test`, `registry-test` (6),
  `reload-per-declaration-test`, `store-test`, `root-seed-digest-test`, `competency-grade-test`, `competency-oracle-test`
  and a few more singles. Their round-3 results stand unverified. So `registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection`
  (red-triage #5) is **not run**.
- **Several commits.** The driver nuked and rebuilt the root after each hang, and a nuke boots the current HEAD, which
  other lanes moved meanwhile. Nuke boots, in order: fec3d7737, 48bb5cfee, aea2a1052, 45bfab18e, 7ef4967db, then 61b9534d7
  seven times. Every result carries `:seon.test/run-at` but I did not map results to boots, so I cannot say which commit
each row ran on; the early batches (the `my.*` tests and `seon.cluster.*` up to `registry-test`) ran before the first
rebuild (fec3d7737) and the tail on later boots. Treat each row as "red/green at one of these commits".
- **Two earlier attempts discarded.** A stop and start of the root at a different commit than the store's last boot made
  every later test answer "Program row could not be installed; correct the reported acquisition evidence." (2,596 of
  2,893 results). That is a finding in its own right (a restart of an isolated root across a HEAD change adopts into a
  state that cannot install any program row); the results were discarded and the round redone from a nuke.
  Also, a root started from a tool call dies when the call returns; the round ran from a detached shell script.
- Per-test comparison with round 3 is only possible where round 3 named the test (central-round.md lists); its raw
  per-test results were in the gitignored `tmp/` and are gone. The raw results of this round are
  `tmp/central-round-head/results.edn` (one EDN map per test, 2,819 lines, gitignored, not committed); the
  non-green list is in the appendix below.

## Totals

| | c53c9306e | cd59c9fa3 | 2319152dc (r3) | this round |
|---|---|---|---|---|
| tests in the population | 2,770 | 2,830 | 2,832 | 2,905 |
| tests that ran | 2,770 | 2,830 | 2,832 | 2,819 |
| green | 2,365 | 2,346 | 2,486 | 2,472 |
| red | 232 | 246 | 229 | 231 |
| error | 106 | 238 | 117 | 116 |
| not run | 0 | 0 | 0 | 86 |

The population grew by 73 tests since round 3 (31 of the tests that ran are not in any earlier list: new tests).
Wall: about 100 minutes including 9 rebuilds and about 40 minutes of hang waits; the clean batches ran at 4 to 60 s
per 25 tests.

## Unfinished: tests whose bodies never exit

14 tests did not exit within `:seon.test/check-time-limit-ms`; the runner reports them as errors and keeps their
branches live ("thread N remains live"). The next request on the same JVM then blocks, so each needs a rebuilt root.

- `seon.blob-publication-test/publication-and-collection-are-exclusive-in-both-orderings`
- `seon.cluster.agent-test`: `n-agent-parallel-turns-property`, `park-wake-test`, `pause-during-in-flight-call-test`,
  `release-joins-every-owned-graph-before-it-closes-the-branch`, `restamp-recovery-test`,
  `routing-conservation-waits-for-terminal-evidence`, `stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted`,
  `wake-routing-conservation-property`
- `seon.cluster.armed-test`: `a-message-committed-during-boot-start-is-conserved`, `an-escaped-throwable-becomes-a-fact-and-a-message`,
  `boot-seeds-the-root-agent-and-starts-the-loop`, `booting-spends-no-model-call`, `the-first-cluster-proc-fault-at-resume-becomes-a-fact`

Also hung but with no unfinished record (a batch answered "PREPL emitted no next output ... outcome unknown" twice):
batches starting at `seon.cluster.agent-test/wait-closes-in-terminal-tx-test`, `...agent-namespace-test/source-bearing-namespaces-without-a-steward-derive-one-problem-line`,
`...program-change-test/a-launch-on-analyzer-bytes-with-unchanged-definitions...`, `...publication-inputs-test/file-entity-selection-follows-declaration-...`.
These are the strongest blockers to a clean round: a hung member should not take the request with it. They belong to
one task on the runner (a body outliving its bound is released), not to the tests.

## Did the two big owners recover?

- `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database` (C2): **not red.** No result mentions `seon.db/q`
  with a nil database, and the defn test step ran in the tests that need it (`seon.turn-continue-test` has 3 reds, none
  of that shape).
- `seon.db/a-write-to-a-store-without-declaration-rows-takes-the-projection-its-caller-hands` (C1): **not red.** The
  `db-test` tests round 3 and red-triage named (`a-store-without-program-schema-admits-its-writes`,
  `a-definition-write-keys-its-projection-by-its-own-datoms`, `a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program`)
  are green, and the old message ("requires a carried schema projection") appears in no result. A neighbour class remains,
  8 tests that die at a fixture write ("Fixture write was refused at the write"), each for a different cause:
  `my.test-test/an-agents-own-test-reaches-its-cluster-through-the-elided-arity` (`:seon.agent/branch` given a map),
  `seon.fault-no-history-test/fault-occurrence-churn-drops-superseded-history` and
  `seon.test-provenance-test/verified-requires-the-subject-positive-assertions-and-exact-program` (the same
  `seon.db/transact!` refusal), `seon.cluster.publication-delta-test/changed-declarations-reload-their-namespace-and-dependents`,
  `.../transaction-report-identities-select-only-changed-program-entities` and
  `seon.cluster.reload-per-declaration-test/a-moved-macro-reloads-no-dependents` ("The stored definition digest of
  [:seon.fn/sym sample...] is not the one its definition produces": a fixture storing a digest its definition does not
  produce, the shape of `seon.db/a-retained-branch-fixture-writes-the-digest-its-definition-produces`),
  `seon.reset-edge-parity-test/indexed-symbol-reverse-walk-matches-the-same-reference-population`
  (`seon.schema.datahike/value-schema` refused a compiled argument) and
  `seon.turn-disposition-test/a-refused-disposition-settles-through-resume` ("run transition refused: no-such-agent").

## Red-triage E rows reclassified

Classes: A delete, B update the retired assumption, C fix the surviving owner, D flaky or collision. "Green" rows leave the
list. A row's reading is from the round's failure message.

| # | Test | Now | Failure in this round | Class |
|---|---|---|---|---|
| 1, 2 | `chain2.proofs/...`, `chain3.proofs/...` | no result | task proofs, not deftests; never in the test population | intended red (not a test) |
| 3 | `my.program-test/a-function-called-through-its-callers-is-reached-by-their-tests` | green | | stale stored run; remove from the list |
| 4 | `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` | red (error) | "its fresh attempt opened a model turn, whose reply wrote its product" | B: asserts the retired rule (6a687ac17) |
| 5 | `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` | not run | | open (registry-test 6 tests not run) |
| 6 | `seon.cluster.turn-test/acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles` | red | `(= 6 (:seon.sci.eval/installed acquired))` actual 7 (was 17 vs 6) | B: update the count (E4 fork carries own namespaces; 7 now) unless 7 is a leak |
| 7 | `seon.context-cost-test/one-agents-current-state-costs-only-its-attachments` | red | jvm-window 68.3 MB against < 50 (was 63.4) | C: cost regression, worse; owner is the P4-slice-2 path (9e7958e30) |
| 8 | `seon.repl-differential-test/promoting-a-closure-costs-its-walk-and-the-callers-called` | red | alloc 383 MB against < 210 MB and the sub-second bound | C: cost, owner `seon.sci.eval` promotion (e1c424db5) |
| 9 | `seon.sci.eval-test/database-provenance-regenerates-the-base-and-reverts-for-retained-forks` | green | | C2 fixed |
| 10 | `seon.start-flow-benchmark-test/commit-to-paused-flow-costs` | red | cold ms 400 against 160, plus alloc | C: task `seon.flow/cold-paused-graph-construction-keeps-its-cost-bound` (worse than r3: 1,346 ms cold sci then) |
| 11 | `seon.test-failure-facts-test/a-changed-reach-member-retracts-only-itself` | red | datoms are `[:seon.test/reach :seon.db.transaction/operation]`, 2 datoms not 1 | B: the test predates provenance on the transaction (474174b52 says red on parent) |
| 12 | `seon.test-failure-facts-test/an-unchanged-re-record-emits-no-datom` | red | an identical re-record emits one `Datom`, not `[]` | B/C: same as 11; decide whether the re-record owes zero datoms |
| 13 | `seon.test-provenance-test/runs-are-distinct-immutable-and-linked-atomically` | red | `:seon.test/pass-count` is nil in the stored run | C: owner `seon.test` recording (the stored run lacks the count) |
| 14 | `seon.test-provenance-test/verified-requires-the-subject-positive-assertions-and-exact-program` | error | fixture write refused at `seon.db/transact!` | C: fixture-write class above |
| 15 | `seon.test-reaching-test/two-branches-of-one-program-share-reach-declarations` | green | | fixed |
| 16 | `seon.test-test/one-interpreted-test-run-allocates-a-bounded-multiple-of-its-body` | green | | fixed |
| 17 | `seon.test-test/recording-distinguishes-run-replay-from-a-new-event` | red | a refused read's error is not equal to the one recorded (`:seon.error/data` dissociated) | C: owner `seon.test` read refusal shape |
| 18 | `seon.test.reach-test/a-digest-follows-its-closures-content-and-nothing-else` | green | | D confirmed (fixture state); fixed here |
| 19 | `seon.test.request-acquisition-test/a-member-installs-its-program-unobserved-by-the-calling-evaluation` | error | `seon.sci.observe/observe refused argument 0 at [:seon.batch/text]` | C: owner `seon.sci.observe/observe` caller passes a non-text batch |
| 20 | `seon.test.runner-test/a-program-change-reads-only-the-reach-attributes-it-wrote` | green | | fixed |
| 21 | `my.task-test/current-answers-the-held-steps-instructions` | green | | fixed (and `seon.cluster.host-test/a-branch-agents-test-member-writes-its-own-world` is red on a stale expectation naming `current-answers-the-held-task-with-its-proofs`: B) |
| 22 | `seon.error-test/a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas` | red | `:seon.error/nearest` is nil for `:inst` | C: owner the refusal (`seon.error`), nearest-schema lookup |
| 23 | `seon.instrument-test/a-sovereign-sci-fork-refuses-by-throwing-under-both-dials` | red | the source context holds no `:seon.instrument/interpreted-original` wrapper | C: owner `seon.instrument` |
| 24 | `seon.instrument-test/refusal-value-projection-obeys-the-profile-in-both-faces-and-the-whole-value-stays-retrievable` | red | the refusal does not retain the offending object (`identical?` fails) | C: owner `seon.instrument` |
| 25 | `seon.instrument-test/registry-sized-contract-evidence-retains-the-offending-object` | red | construction alloc 50.2 MB against a 16.8 MB ceiling | C: cost, `seon.instrument` |
| 26 | `seon.run6-db-test/database-read-arities-refuse-stray-inputs` | red | `datoms-map`: the refusal fails the handed-projection validator | C: owner `seon.schema/projection-validator` with the refusal schema |
| 27 | `seon.call-preparation-test` | 3 red/error | held snapshot fork; `probe-current-database` unresolved; contracted first-party function receiving its declared connection | C: `seon.call-preparation` (supply-from-world); the unresolved probe may be B |
| 28 | `seon.cluster.host-test/a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged` | green | | D fixed by 83acdfbf3 |
| 29 | `host-test/a-message-to-a-stopped-admitted-agent-...` and `retirement-closes-admission-at-the-writer-and-races-submission` | red / error | message not written; "test channel closed before its..." | C: owner `seon.cluster.agent` (C9 family) |
| 30 | `seon.cluster.agent-test/pause-during-in-flight-call-test` | error (unfinished) | did not exit within the limit; thread stays live | C: runner release of an unexited body, plus the test's own hang |
| 31 | `seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts` | green | | fixed (turn-test has 12 other reds below) |
| 32 | `seon.sci.eval-test/acquisition-binds-loaded-first-party-compiled-vars` | red | "loaded dependencies are not first-party merely because loaded" `(failed? external)` | C: owner `seon.sci.eval` acquisition |
| 33 | `seon.source-census-test` | 3 red | hardcoded-number sites, dates/file-line in source and docstrings | C/B: sweeps (census checks a property of the source; fix the sources or the census) |
| 34 | `seon.db-test/a-store-without-program-schema-admits-its-writes` | green | | C1 fixed |
| 35 | `seon.db-test/a-definition-write-keys-its-projection-by-its-own-datoms` | green | | fixed |
| 36 | `seon.db-test/a-retained-branch-reads-with-the-jvm-builder-not-its-recorded-program` | green | | fixed |
| 37 | `seon.test/two-tests-never-share-a-test-world` and the tq-chain tests | no result | task, not a deftest | open |

Counts of the 37 rows: 12 green (3, 9, 15, 16, 18, 20, 21, 28, 31, 34, 35, 36); 21 still red or error (B 4: rows 4, 6, 11, 12;
C 17: rows 7, 8, 10, 13, 14, 17, 19, 22-27, 29, 30, 32, 33; none A, none D); 1 not run (5); 3 with no result because they
are task proofs (1, 2, 37). Row 21's neighbour `host-test/a-branch-agents-test-member-writes-its-own-world` is a further B.

## Largest reds by test count (all ran)

| Namespace | Non-green | Likely owner |
|---|---|---|
| `seon.cluster.agent-test` | 13 | runner release of unexited bodies, `seon.cluster.agent` |
| `seon.cluster.turn-test` | 12 | `seon.cluster.turn`: delivery and error facts (messages not written) |
| `seon.effect-test` | 10 | not examined |
| `seon.contract-coverage-test` | 10 | contract back-fill (functions without a complete contract) |
| `seon.cluster.host-test` | 9 | `seon.cluster.agent` (C9) |
| `seon.test.one-request-test` | 7 | the runner |
| `seon.render-simplification-test` | 7 | not examined |
| `seon.request-cost-test` | 6 | cost accounting |
| `seon.cluster.armed-test` | 6 | 5 are unfinished bodies |
| `seon.sci.eval-test`, `seon.reset-edges-test`, `seon.program-test`, `seon.mcp-entrance-test`, `seon.competency-test`, `seon.competency-oracle-test` | 5 each | |

The lanes blocked most by one cause: the hung bodies (they stop any central round and any bulk run: 14 tests plus the 4
silent batch hangs), then the fixture-write class (8 tests, 6 causes).

## Newly red since round 3, as far as round 3 named them

Round 3 named its non-125 reds: `seon.cluster.agent-test` (3), `seon.cluster.armed-test` (2), `seon.cluster.agent-arming-test`,
`seon.my.test-check-test/a-reused-check-answers-a-run-id-it-recorded`, `seon.error-test/a-guessed-...`,
`seon.sci.acquisition-cost-test` (2). Now: agent-test 13 (5 more), armed-test 6, error-test row still red. The newly named
non-green tests (not in round 3's lists): the 12 `cluster.turn-test` reds, the 3 `source-census-test` reds, 3
`call-preparation-test`, and the 8 fixture-write errors above; I cannot say which were green in round 3 because its
per-test results are gone. The one round-3 regression `seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm`
was not checked here.

## Next steps

- File the hung-body release (one task on the runner) first; run the 86 unrun tests after it lands, from one commit.
- Round script: a hung request should end the request, not the round; the driver should record the hung member and
  continue on the same JVM or on a nuke at the same commit (`bin/seon check --at REV` pins one).
- Re-run this round from a pinned `git archive` of one commit once the hang task lands.

## Appendix: every non-green test that ran (347: 231 red, 116 error)

- `seon.cluster.agent-test` (13): a-function-without-a-contract-is-admitted-and-stored [red]; concurrent-start-and-release-never-deadlock [error]; hot-reload-var-test [error]; n-agent-parallel-turns-property [error]; park-wake-test [error]; pause-during-in-flight-call-test [error]; release-joins-every-owned-graph-before-it-closes-the-branch [error]; restamp-recovery-test [error]; routing-conservation-waits-for-terminal-evidence [error]; stop-flow-has-a-declared-loud-turn-completion-backstop [error]; stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted [error]; wait-closes-in-terminal-tx-test [error]; wake-routing-conservation-property [error]
- `seon.cluster.turn-test` (12): a-lost-model-call-leaves-a-durable-readable-reason [red]; a-refused-delivery-becomes-a-durable-error-fact [red]; a-run-prompts-from-its-opening-database-value [red]; a-turn-delivers-what-a-form-asks-to-send-and-still-finishes [error]; accepted-first-party-definition-reaches-an-existing-agent-next-turn [red]; acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles [red]; import-addition-is-ordinary-data-and-reacquires-exactly [red]; import-only-ns-unmap-installs-exactly-after-its-context-commit [red]; reasoning-starvation-persists-usage-finish-and-the-named-error [red]; refused-terminal-program-transactions-settle-and-do-not-refire [red]; runtime-schema-key-changes-pass-the-one-usage-guarded-decision [red]; runtime-schema-registration-commits-the-evaluated-form-and-attribute [error]
- `seon.contract-coverage-test` (10): cluster-branch-is-contracted [red]; context-projection-is-contracted [red]; edn-encoded-is-contracted [red]; fs-attributes-is-contracted [red]; function-arities-in-is-contracted [red]; instruction-entity-changes-is-contracted [red]; output-observed-is-contracted [red]; query-params-is-contracted [red]; resource-population-is-contracted [red]; runtime-duration-is-contracted [red]
- `seon.effect-test` (10): an-oversized-request-is-refused-and-never-dispatched [red]; background-settlement-carries-its-connection-across-a-thread-hop [error]; background-work-outlives-the-deadline-of-the-turn-that-started-it [error]; detached-work-is-bounded-by-its-own-limit-config-then-the-form [error]; guarded-sci-evaluation-supplies-the-effect-identity-context [red]; interrupted-handlers-mark-the-open-receipt-without-a-result [red]; invalid-requests-never-open-a-receipt [red]; request-commits-before-io-dispatch-and-settles-once [red]; settlement-records-only-successful-handler-result-attributes [red]; the-effect-boundary-runs-its-handler-under-the-requesting-evaluations-arm [red]
- `seon.cluster.host-test` (9): a-branch-agents-test-member-writes-its-own-world [red]; a-merge-request-waits-for-its-assigned-tasks-proofs-then-reaches-root [red]; a-message-to-a-stopped-admitted-agent-hosts-it-and-its-db<->flow-takes-it [red]; a-message-to-an-admitted-agent-whose-branch-was-deleted-refuses-branch-absent [red]; a-refused-deletion-is-associated-only-with-the-one-evaluation-proposing-it [red]; a-refused-deletion-is-its-forms-refusal-and-the-run-closes [red]; an-agent-creates-a-task-at-its-repl-and-it-is-a-cluster-task [red]; an-sci-send-from-the-cluster-supplies-its-handle-and-resumes-a-stopped-agent [red]; retirement-closes-admission-at-the-writer-and-races-submission [error]
- `seon.render-simplification-test` (7): a-program-change-replaces-a-private-definition [red]; attribute-declared-producers-select-for-every-projection [red]; authored-source-invocation-reuses-one-stored-run-across-presentations [red]; generic-renderer-receives-the-acquired-entity-id [red]; renderer-invocation-is-sci-only-and-live-var-backed [red]; shared-invocation-cache-reuses-identical-entity-inputs-across-call-ids [red]; unchanged-retained-call-skips-discovery-and-invocation [red]
- `seon.test.one-request-test` (7): a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs [red]; a-member-whose-test-does-not-resolve-is-red-and-its-branch-released [error]; a-refused-recording-write-is-a-stored-core-fault-carrying-its-whole-refusal [error]; a-request-admits-and-releases-in-bounded-batches [red]; a-stopped-requests-unstarted-member-is-unknown-and-an-identical-request-reruns-it [error]; an-excluded-long-member-is-never-read-as-green [error]; one-request-runs-each-member-as-an-isolated-agent [error]
- `seon.cluster.armed-test` (6): a-message-committed-during-boot-start-is-conserved [error]; an-escaped-throwable-becomes-a-fact-and-a-message [error]; boot-seeds-the-root-agent-and-starts-the-loop [error]; booting-spends-no-model-call [error]; the-first-cluster-proc-fault-at-resume-becomes-a-fact [error]; two-clusters-in-one-jvm-own-distinct-live-program-contexts [error]
- `seon.request-cost-test` (6): a-jvm-reply-carries-its-request-cost [red]; a-request-shows-the-all-thread-window-and-accounts-for-unnamed-time [red]; a-sample-is-judged-against-the-digest-of-main-s-program-row [error]; an-entrances-self-time-is-never-given-a-ratio [red]; an-interpreted-live-value-is-never-given-a-ratio-against-a-compiled-main-value [red]; an-interpreted-sample-of-the-digest-main-holds-shows-no-entry [red]
- `seon.competency-oracle-test` (5): t1-goes-red-to-green [red]; t2-goes-red-to-green [red]; t3-goes-red-to-green [red]; t4-goes-red-to-green [red]; t5-goes-red-to-green [red]
- `seon.competency-test` (5): t1-area-is-contracted [red]; t2-quantity-schema-then-function [red]; t3-slug-has-a-passing-test [red]; t4-app-calls-util-through-an-alias [red]; t5-slug-removes-edge-hyphens [red]
- `seon.mcp-entrance-test` (5): a-defn-whose-contract-has-a-qualified-property-key-is-admitted [red]; a-fn-schema-over-a-predicate-the-agent-defined-is-registered [red]; a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle [red]; a-jvm-reply-keeps-its-duration-after-an-earlier-run [red]; an-sci-reply-is-what-the-run-saved [red]
- `seon.program-test` (5): a-declaration-added-after-the-first-call-is-a-cache-miss [error]; declaring-an-attribute-on-a-program-entity-schema-is-sufficient [error]; indexed-and-evaluated-declarations-are-the-same-entities [red]; runtime-schema-declarations-project-namespaced-properties [red]; typed-cross-namespace-deletion-retracts-function-and-test [red]
- `seon.reset-edges-test` (5): namespace-and-schema-retractions-return-every-surviving-value-referrer [red]; recording-a-fault-does-not-mint-a-program-identity [red]; removing-a-name-from-a-surviving-entity-refuses-its-callers [red]; source-publication-refuses-an-outside-caller-and-keeps-its-head [error]; stored-many-members-never-stand-in-for-the-producing-fact [red]
- `seon.sci.eval-test` (5): a-context-definition-survives-its-symbols-first-install [error]; acquisition-binds-loaded-first-party-compiled-vars [red]; an-unchanged-program-context-is-derived-once-and-reused [error]; bare-dir-and-program-derived-doc-are-repl-native [red]; root-acquisition-and-the-web-share-one-pull-plan [red]
- `seon.cluster.merge-test` (4): a-function-and-its-schema-land-as-one-unit [error]; a-green-request-lands-on-main-and-disk-in-one-call [error]; a-require-in-the-agents-user-namespace-lands-with-its-change [error]; an-end-message-naming-a-merge-task-is-not-a-request [error]
- `seon.fn-test` (4): a-contract-naming-a-local-def-is-kept-as-data-and-the-namespace-publishes [red]; publication-is-first-party-only [red]; sci-evaluation-has-one-first-party-owning-namespace [red]; the-indexer-emits-no-attribute-the-program-entity-schema-drops [error]
- `seon.fn.incremental-deletion-test` (4): a-declaration-adding-a-target-reanalyzes-its-invokers [error]; a-deletion-recomputes-its-unchanged-callers-edges [error]; a-replacement-under-a-declared-invocation-keeps-its-new-edge [error]; a-supplied-file-entity-never-widens-the-reconciliation [error]
- `seon.transact-feedback-test` (4): bad-value-type [red]; missing-required-identity-member [red]; tempids-and-current-transaction-are-admitted [red]; unknown-attribute-with-candidates [red]
- `seon.call-preparation-test` (3): a-new-branch-at-a-derived-commit-reads-the-held-snapshot [red]; an-acquired-cluster-context-prepares-its-calls [error]; an-acquired-context-supplies-a-leading-database-to-a-first-party-read [error]
- `seon.cluster.publication-delta-test` (3): changed-declarations-reload-their-namespace-and-dependents [error]; selected-entities-reconcile-without-a-manifest [error]; transaction-report-identities-select-only-changed-program-entities [error]
- `seon.cluster.publication-inputs-test` (3): a-partial-publication-reuses-the-published-test-input-digest-until-an-input-changes [error]; an-unchanged-explicit-path-is-captured-once-and-keeps-the-head [red]; changed-paths-hash-only-the-named-files-and-use-recorded-pins [error]
- `seon.cluster.rehearsal-test` (3): a-changed-file-rehearses-without-writing-the-store [error]; a-malformed-schema-resource-refuses [error]; a-task-file-that-is-not-one-vector-refuses [error]
- `seon.cluster.store-transact-test` (3): a-committed-transaction-returns-its-report [error]; a-datahike-abort-is-distinguishable-from-ours [red]; heterogeneous-unions-round-trip-through-the-one-codec [error]
- `seon.custody-stability-test` (3): acquired-first-party-reachability-is-public-plus-every-indexed-function [red]; indexed-custody-returning-surface-is-derived-and-exact [red]; non-function-private-custody-roots-and-operator-state-stay-unreachable [red]
- `seon.dev.edit-feedback-test` (3): post-edit-coalesces-one-optional-review-worker-and-drops-failure [error]; pre-edit-exact-reconstruction-uses-structural-edit-refusals [red]; split-schema-edits-run-admission-before-publication [error]
- `seon.instrument-test` (3): a-sovereign-sci-fork-refuses-by-throwing-under-both-dials [red]; refusal-value-projection-obeys-the-profile-in-both-faces-and-the-whole-value-stays-retrievable [red]; registry-sized-contract-evidence-retains-the-offending-object [red]
- `seon.problems-test` (3): a-healthy-cluster-derives-an-empty-value [red]; absent-facts-produce-no-entries [red]; configured-models-without-registry-entities-are-derived [red]
- `seon.render.web-test` (3): a-reconnect-refuses-a-pass-derived-before-it-connected [red]; debug-algorithm-carries-the-render-evaluation-inputs [red]; declared-units-are-components-in-schema-order [red]
- `seon.repl-differential-test` (3): a-file-test-runs-the-branch-redefinition-of-a-file-function [error]; an-inherited-caller-sees-a-same-pass-redefinition [red]; promoting-a-closure-costs-its-walk-and-the-callers-called [red]
- `seon.schema-test` (3): a-refused-registration-is-an-error-value-never-a-throw [error]; canonical-reference-values-use-the-pull-collection-grammar [red]; every-predicate-schema-declares-what-it-accepts [red]
- `seon.schema.admission-test` (3): missing-recorded-admission-source-fails-closed [error]; recorded-admission-is-representation-independent [error]; shipped-schemas-remain-core-in-a-non-temporal-database [red]
- `seon.schema.program-test` (3): database-shape-render-declarations-resolve [red]; entities-have-one-canonical-persisted-shape [red]; program-owned-values-validate-at-their-producer [red]
- `seon.sci.documentation-test` (3): a-contract-mistake-refuses-and-doc-answers-its-contract [red]; documentation-is-returned-data-without-a-second-printed-copy [error]; retained-context-receives-the-current-repl-macro-through-its-core-alias [red]
- `seon.sci.render-source-test` (3): a-pair-s-forms-are-shown-with-their-results [red]; a-render-form-that-fails-shows-the-value-and-one-diagnostic [error]; the-next-context-reads-the-evaluated-render-without-evaluating [red]
- `seon.source-census-test` (3): no-timeout-or-limit-is-a-hardcoded-number [red]; problems-prose-carries-no-date-or-file-line [red]; source-text-carries-no-date-or-file-line [red]
- `seon.test-provenance-test` (3): program-identity-excludes-results-and-includes-admitted-source [red]; runs-are-distinct-immutable-and-linked-atomically [red]; verified-requires-the-subject-positive-assertions-and-exact-program [error]
- `seon.test.request-acquisition-test` (3): a-member-installs-its-program-unobserved-by-the-calling-evaluation [error]; acquiring-a-context-installs-its-program-unobserved-by-the-calling-evaluation [error]; members-of-one-request-share-its-acquired-program [red]
- `seon.turn-continue-test` (3): a-green-end-lands-its-merge-without-root [error]; provider-progress-has-its-own-bound [red]; read-results-arrive-before-done [red]
- `seon.classification-test` (2): dial-membership-and-request-routes-accrete-from-declarations [red]; namespace-context-follows-relevance-not-spelling [red]
- `seon.cluster.boot-test` (2): a-reset-development-cluster-keeps-its-overlay [error]; cold-start [error]
- `seon.cluster.evaluate-source-test` (2): a-failed-read-is-its-forms-error-and-reading-continues-in-sci-and-jvm [red]; a-run-whose-open-is-refused-still-answers-its-values [red]
- `seon.cluster.fault-storage-test` (2): lossy-subthreshold-fault-evidence-says-it-is-capped [error]; oversized-fault-evidence-is-bounded-and-retrievable [red]
- `seon.concurrency-streams-test` (2): message-flood-retains-every-source-vector-entry [error]; unique-namespace-race-keeps-one-winner-and-one-flat-refusal [red]
- `seon.config-application-test` (2): applied-values-shape-the-running-system [red]; every-config-entry-has-an-honest-application-contract [red]
- `seon.config-test` (2): config-loads-the-packaged-population-in-a-fresh-jvm [red]; the-default-document-has-one-canonical-complete-location [red]
- `seon.db-test` (2): equal-declaration-content-shares-one-projection-across-values [red]; the-stable-walk-costs-less-than-encoding-the-read-result [red]
- `seon.dev.source-instrumentation-test` (2): generated-init-compiles-before-runtime-owners-are-loaded [error]; source-publication-restores-contracts-after-early-reload-failure [error]
- `seon.error.branch-test` (2): a-fault-is-delivered-and-shown-open-once-per-opening [red]; an-error-is-seen-on-its-own-branch-once-per-window [red]
- `seon.help-test` (2): dir-lists-what-an-agent-calls-as-an-agent-calls-it [red]; help-evidence-tracks-its-code-version [red]
- `seon.html-views-test` (2): fault-pairs-preserve-ai [red]; identity-pairs-preserve-ai [red]
- `seon.read-evidence-test` (2): a-fresh-connection-keeps-an-unrelated-read-current [red]; another-recipients-message-keeps-junipers-inbox-current [red]
- `seon.ref-test` (2): a-reference-with-no-path-answers-an-error-that-holds-no-path [error]; a-reference-with-no-path-is-a-missing-file [red]
- `seon.render-source-test` (2): preview-cache-is-memory-only-and-invalidates-on-read-changes [red]; source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute [red]
- `seon.render.entity-pairs-test` (2): an-inline-face-overrides-the-schema-default-for-just-that-face [red]; namespace-page-renders-one-full-namespace-and-linked-references [red]
- `seon.render.history-test` (2): form-is-the-third-output-of-the-existing-selection-chain [red]; form-output-validation-is-the-declared-open-shape [error]
- `seon.render.web-context-test` (2): a-context-view-string-cut-links-to-its-next-page-with-the-basis-and-text-answers-plain [error]; root-page-blocks-read-attribute-bounded [red]
- `seon.schema-reference-graph-test` (2): datalog-walks-the-schema-reference-closure [red]; persisted-reference-edges-equal-the-canonical-projection [red]
- `seon.schema.admission-gate-test` (2): an-undefined-slot-is-advice-a-stored-one-is-refused [red]; open-maps-cannot-regress-in-declarations-or-contract-admission [red]
- `seon.sci.branch-execution-test` (2): a-file-test-callers-away-from-a-branch-change-runs-interpreted [red]; branch-definitions-and-unchanged-callers-share-one-context [error]
- `seon.sci.forgive-test` (2): a-stray-closer-keeps-tokens-apart [red]; forgiving-forgiven-text-changes-nothing [red]
- `seon.test-failure-facts-test` (2): a-changed-reach-member-retracts-only-itself [red]; an-unchanged-re-record-emits-no-datom [red]
- `seon.test-test` (2): recording-distinguishes-run-replay-from-a-new-event [red]; resolution-follows-admitted-source-and-acquisition [error]
- `seon.test.interrupted-request-test` (2): a-deleted-tests-red-member-is-history-not-a-failed-test [red]; one-incomplete-member-is-its-own-unknown-and-its-run-mates-stay-readable [red]
- `seon.turn-loop-test` (2): a-clean-last-form-without-a-disposition-is-loud-terminal-evidence [error]; a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again [error]
- `seon.turn-work-test` (2): a-branch-agent-assigned-after-its-fork-opens-its-model-turn [red]; situation-totality-property [red]
- `seon.turn.batch-test` (2): a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal [red]; a-rewritten-namespace-row-stores-the-digest-of-what-it-keeps [red]
- `my.edit-test` (1): public-entries-declare-resolvable-io-capabilities [red]
- `my.fs-test` (1): public-entries-declare-resolvable-io-capabilities [red]
- `my.test-check-test` (1): a-reused-check-answers-a-run-id-it-recorded [red]
- `my.test-test` (1): an-agents-own-test-reaches-its-cluster-through-the-elided-arity [error]
- `seon.adoption-contract-freshness-test` (1): adopted-contracts-govern-both-sci-and-host-calls [red]
- `seon.adoption-diagnostic-test` (1): operator-reports-the-typed-refusal-over-a-real-prepl [red]
- `seon.agent-call-edges-test` (1): admitted-declarations-retain-call-dependencies-across-turn-order [error]
- `seon.ai-test` (1): captured-run4-replies-stop-before-fabricated-responses [red]
- `seon.analysis-identity-test` (1): shifted-findings-update-only-positions-at-one-ten-and-one-hundred-definitions [error]
- `seon.armed-calls-test` (1): a-database-passed-for-a-connection-is-an-ordinary-argument-refusal [red]
- `seon.await-owner-census-test` (1): bounded-await-owner-has-exactly-the-declared-production-callers [red]
- `seon.background-blob-test` (1): background-binary-results-remain-exact-across-the-inline-threshold [error]
- `seon.blob-publication-test` (1): publication-and-collection-are-exclusive-in-both-orderings [error]
- `seon.bounded-boundary-census-test` (1): every-foreign-subprocess-boundary-declares-a-bound [red]
- `seon.census-test` (1): a-docstring-census-reads-the-branchs-database [red]
- `seon.cluster.acquisition-test` (1): acquisition-owns-only-its-isolated-resources [error]
- `seon.cluster.agent-arming-test` (1): an-agent-created-while-the-cluster-runs-is-started-by-its-own-creation [error]
- `seon.cluster.db-flow-test` (1): a-prove-step-admitted-by-its-successor-opens-a-model-turn [error]
- `seon.cluster.entrance-supplier-test` (1): supplied-context-acquires-and-releases-an-isolated-evaluation [error]
- `seon.cluster.evaluate-sources-test` (1): ordered-evaluation-retains-one-explicit-basis-without-publication [red]
- `seon.cluster.fault-message-test` (1): panic-requests-the-owners-stop-before-it-records [red]
- `seon.cluster.instruction-test` (1): every-qualified-call-in-instruction-code-resolves-at-its-arity [red]
- `seon.cluster.lazy-agents-test` (1): idle-agents-wait-for-work-but-schedule-owners-need-their-timers [red]
- `seon.cluster.program-restart-test` (1): an-agent-definition-survives-restart-and-another-agent-calls-it [error]
- `seon.cluster.publication-concurrency-test` (1): concurrent-publications-return-one-committed-head [red]
- `seon.cluster.publication-findings-test` (1): publication-reports-finding-count-and-delta-without-branches [red]
- `seon.cluster.publication-host-test` (1): live-init-keeps-the-host-process-and-launches-no-jvm [error]
- `seon.cluster.reload-per-declaration-test` (1): a-moved-macro-reloads-no-dependents [error]
- `seon.cluster.store-test` (1): datahike-logs-through-seon-bounded-and-expected-refusals-below-error [red]
- `seon.cluster.uncaught-handler-test` (1): an-uncaught-failure-is-stored-in-the-world-it-carries [error]
- `seon.cluster.wake-test` (1): a-fault-wakes-the-steward-of-the-failing-functions-namespace [error]
- `seon.concurrency-independence-test` (1): n-agents-fold-independently-on-one-live-cluster [error]
- `seon.concurrency-test` (1): two-clusters-run-concurrent-agent-turns [error]
- `seon.config-functions-test` (1): manifest-functions-must-exist-before-reconciliation [red]
- `seon.context-cookbook-trial-test` (1): a-provider-refusal-is-not-a-comprehension-score [error]
- `seon.context-cost-test` (1): one-agents-current-state-costs-only-its-attachments [red]
- `seon.contracts-install-test` (1): function-literal-contract-is-refused-before-printing-or-installing [red]
- `seon.db-immutability-test` (1): a-held-database-value-is-immutable-under-later-transactions [red]
- `seon.db-transact-shape-test` (1): transact-return-shape-is-independent-of-dynamic-custody [red]
- `seon.dev.fresh-operator-export-test` (1): export-verb-produces-an-openable-queryable-store [error]
- `seon.dev.prepl-retention-test` (1): a-reply-that-cannot-be-printed-is-a-declared-error-and-the-session-answers-on [red]
- `seon.directory-test` (1): an-empty-directory-observes-later-schema-declarations [red]
- `seon.error-test` (1): a-guessed-schema-key-is-answered-with-the-nearest-registered-schemas [red]
- `seon.error-write-timing-test` (1): program-call-changes-still-enter-final-arity-validation [red]
- `seon.eval-alloc-test` (1): an-evaluation-reply-shows-what-it-allocated [red]
- `seon.eval.drive-test` (1): transcript-projects-the-evaluation-time-limit [red]
- `seon.fault-no-history-test` (1): fault-occurrence-churn-drops-superseded-history [error]
- `seon.flow-configuration-test` (1): every-built-graph-proc-declares-a-specific-workload [error]
- `seon.flow-test` (1): forced-child-jvm-death-preserves-committed-facts [error]
- `seon.fn-core-calls-test` (1): core-call-census-is-non-vacuous [red]
- `seon.fn.publication-cache-test` (1): lint-direct-callers-after-committed-declaration-changes [error]
- `seon.fn.publication-test` (1): incremental-indexing-reconciles-only-selected-file-entities [red]
- `seon.gen.loop-test` (1): completion-prose-does-not-erase-failed-evaluations [error]
- `seon.incremental-publication-test` (1): non-program-inputs-have-their-own-publication-owner [red]
- `seon.loop-proof-test` (1): running-fixture-settles-its-seeded-wake [error]
- `seon.maintenance-schema-test` (1): empty-maintenance-memberships-retain-their-positive-observation [red]
- `seon.operator-outcome-test` (1): a-start-runs-the-committed-archive-never-the-checkout [red]
- `seon.pass-cost-test` (1): jvm-render-settings-are-acquired-once-per-pass [red]
- `seon.pass-probe-test` (1): a-pass-of-each-entrant-is-measured [red]
- `seon.print-census-test` (1): no-value-is-printed-outside-the-value-printer [red]
- `seon.public-contract-test` (1): every-fresh-public-function-has-a-complete-contract [red]
- `seon.publication-validation-test` (1): documentation-writes-do-not-check-unrelated-arities-or-renderers [red]
- `seon.receipt-write-carrier-test` (1): evaluation-receipt-is-transaction-metadata [red]
- `seon.reconcile-test` (1): apply-then-reapply-converges-over-generated-populations [red]
- `seon.render-coverage-test` (1): a-refused-render-producer-contributes-a-stable-typed-unknown [red]
- `seon.render.data-test` (1): index-pages-enforce-native-bounds [red]
- `seon.render.faults-test` (1): pulled-fault-concern-uses-the-entity-pair [error]
- `seon.render.retained-test` (1): equal-committed-database-skips-read-replay [red]
- `seon.render.route-test` (1): every-route-name-reverses-and-round-trips [red]
- `seon.render.value-options-test` (1): data-response-reads-the-presentation-window-per-request [red]
- `seon.render.web-feed-test` (1): first-paint-precedes-proc-deltas-and-refusal-precedes-close [red]
- `seon.render.web-performance-test` (1): one-pass-derives-one-profile-for-every-page-unit [error]
- `seon.reset-edge-parity-test` (1): indexed-symbol-reverse-walk-matches-the-same-reference-population [error]
- `seon.returned-error-test` (1): returned-refusal-is-a-schema-first-error-with-unchanged-history [red]
- `seon.run-test` (1): d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change [red]
- `seon.run4-install-test` (1): refused-run4-definition-never-enters-the-retained-context [error]
- `seon.run6-db-test` (1): database-read-arities-refuse-stray-inputs [red]
- `seon.schedule-test` (1): scheduled-operational-events-do-not-expand-or-invalidate-the-agent-page [red]
- `seon.schema-audit-test` (1): every-permissive-graph-position-has-its-own-justification [red]
- `seon.schema-redeclare-test` (1): seeded-schema-families-survive-development-adoption [red]
- `seon.schema-retirement-test` (1): schema-retirement-keeps-source-write-obligations [red]
- `seon.schema-usage-guard-test` (1): generic-schema-deletion-refuses-committed-dependencies [red]
- `seon.schema.projection-writer-test` (1): a-new-branch-at-an-equal-commit-reads-the-held-projection [red]
- `seon.sci.acquisition-cost-test` (1): a-config-read-reuses-its-carried-projection [red]
- `seon.sci.lazy-acquisition-test` (1): cluster-program-is-acquired-on-first-use-and-reused-for-that-database [red]
- `seon.shell.jvm-test` (1): time-limit-reaps-the-process-tree-and-marks-the-effect-interrupted [error]
- `seon.source-reconciliation-test` (1): source-reconciliation-preserves-identities-and-unrelated-facts [red]
- `seon.start-flow-benchmark-test` (1): commit-to-paused-flow-costs [red]
- `seon.state-census-test` (1): every-atom-in-src-is-a-declared-cell [red]
- `seon.store-growth-test` (1): one-listened-write-produces-one-commit-and-no-fault-write [error]
- `seon.supplied-documentation-test` (1): docs-separate-runtime-keys-and-preserve-callable-examples [error]
- `seon.test-cache-test` (1): a-documentation-edit-never-widens-a-gate [red]
- `seon.test-census-test` (1): no-test-redefines-a-var [red]
- `seon.test-support-test` (1): shared-property-reporting-is-a-clojure-test-assertion [error]
- `seon.test.accretion-test` (1): source-harvest-keeps-refusal-render-contracts-coherent [red]
- `seon.test.clean-world-test` (1): a-member-world-is-the-loaded-program-with-boot-facts-only [red]
- `seon.test.effect-attempt-test` (1): an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt [error]
- `seon.test.fixture-timing-test` (1): fixture-acquisition-is-a-pointer-branch-off-the-executing-commit [red]
- `seon.test.publication-test` (1): publication-preserves-every-admitted-member [red]
- `seon.test.redefined-callee-test` (1): a-test-reading-task-tags-reruns-when-a-test-is-tagged [red]
- `seon.transaction-report-test` (1): changed-entities-and-tempids-share-two-reads-at-one-ten-and-one-hundred [red]
- `seon.transaction-result-test` (1): agent-transactions-return-resolved-changes-and-preserve-refusals [red]
- `seon.turn-backstop-test` (1): completion-observer-uses-the-admitted-provider-and-evaluation-parts [error]
- `seon.turn-disposition-test` (1): a-refused-disposition-settles-through-resume [error]
- `seon.vocabulary-test` (1): living-prose-names-no-retired-word [red]

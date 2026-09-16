---
type: research
status: active
tags: [research, test, runtime, class/p3]
---

# Turn test reds — 2026-09-16

Latest continuation: [transaction-cache repair and batch 23](turn-test-reds-cache-2026-09-16.md).

Continuation: [batch-19 class work and batch-20 seed verification](turn-test-reds-batch19-2026-09-16.md)
records the later occurrence/configuration/prompt slices, exact blocked members,
and the independently verified transaction-cache boundary. This earlier record's
counts remain its dated census, not the current gate result.

Bounded assignment on `steward-platform`; starting HEAD
`a55bdfc8065c556c988d10afdb8b4b79685579b3`. Started 00:51 UTC, 90-minute limit.
This record is updated per verified slice. The namespace is not yet green.

## Grounding and baseline

Read AGENTS.md, the issues README and localized instructions, the existing
[consumer-fixture issue](../../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md),
the archived deletion record, the retained canonical-fixture issue, and the
schema-key refinement owner-ruling issue end to end. Read the class-mining
structural-kill table, active roadmap entry and working edge, and turn PRD
§§13–15. Read the batch-17 cold report and pre-wave baseline comparison.
Applied data-oriented-clojure, repl, clojure-testing, datahike,
seon-flow-architecture, llm-providers and seon-context-config skills.

The fresh canonical baseline ran all **59** tests serially through
`seon.test/run` in default's JVM: **189 passes, 76 failures, 31 errors**;
44 tests were non-green. These are assertion counts, not 107 failing tests.
The [complete result values](turn-test-reds-baseline-2026-09-16.edn) include
recorded run refs, timestamps and diagnostics. Each fixture used a new branch
and SCI context of a freshly constructed canonical base, with contracts armed.
No `bin/test` or `bin/test-fast` was used for iteration.

Default was PID 69622. Its initial health already contained four error
signatures, 31 errored evaluations and one failed turn. It was never stopped,
reforked or restarted. No paid provider requests were made.

## Class table

| Cause | Tests | Disposition | Recurring regression |
|---|---|---|---|
| Missing deletion target passed as nil to map-only definition comparison | `ns-unmap-retracts-the-owned-function-after-the-terminal-commit`, `qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context`, `absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx`, `runtime-tests-install-run-redefine-and-delete-exactly` | Resolved in `ecdd61ffe` at `remaining-definition-facts`; before/after proof below | First test asserts never-existing identity, live definition, and surviving tombstone; other tests retain distinct SCI/fresh-acquisition observables |
| Removed result codec and queries mixing system reads with submitted evaluations | Ten named tests in slice 2; schema registration in slice 4 | Resolved at the shared `agent-evaluations` observation helper, `767a4238f` plus slice 4 | Ordered, author-qualified query; exact saved shown text and existing durable/live effects |
| Incomplete injected installation envelope | combined evaluation; whole-turn transitions; real SCI end-to-end; defn/println | Resolved in `78b7ad4fb` by real evaluation at the fixture seam | Real terminal transaction, persistent private objects, declared definitions, output, and read evidence |
| Incomplete environment/config construction | acquisition ordering; function case count; provider retention | Resolved in `78b7ad4fb` using carried projection and canonical configuration application | Fresh acquisition resolves actual durable source and contracts; configured cases are asserted |
| Retired reader/no-evaluation and returned-value message delivery | unreadable/prose reply; successful send; refused send | Replaced in slice 4; two redundant obsolete tests deleted | Rejected replies retain evaluation and provider evidence; immediate send survives discarded return value |
| Stale mid-batch projection during schema deletion | runtime schema unregister | Proven production residual at protected `seon.turn/row-tx`; issue open | Existing unused-attribute removal assertion retained; complete owning-seam trace below |
| Performance bound | delimiter repair | Semantic candidate green; 6694.698498 ms exceeds unchanged 300 ms bound; candidate not retained | Existing performance assertion retained; phase attribution required |
| Retired private storage, generated-turn, prompt, recovery and streaming assumptions | Remaining tests listed in final census | Unrepaired within this bounded assignment; no fallback or assertion weakening | Must verify current mechanism before changing each observable |
| Schema refinement ownership | runtime schema key changes | Existing unresolved owner ruling preserved | No inferred policy change |

## Deletion seam evidence

`reference-code/datahike/src/datahike/pull_api.cljc:509` resolves a missing
lookup identity to no entity. `src/seon/sci/eval.clj:717` previously called
`dissoc` on that nil and handed it to `seon.program/changed-attributes`, whose
map contract correctly refused it. Deletion can name function and test
identities together; an identity that never existed must not be manufactured.
Existing identities remain tombstones under ruling 47.

Archaeology: `d16466b1d` introduced the comparison and `1f3c099d2` uses it in
batch settlement. At HEAD all four named tests errored with the same
`changed-attributes refused current … expected a map, got nil` diagnosis.

Direct read-only default probe: pull of
`[:seon.test/sym "my.agents.turn-reds/never-declared"]` returned nil;
`committed-row?` for its deletion threw the map-input refusal. Evaluating the
candidate private function through MCP before editing production changed the
same probe to `{:absent-row nil :deleted? true}`.

**Guarantee:** deletion verification compares definition attributes only for
an identity that actually exists; absence has no definition attributes.
This is a presence branch at the existing owner, not a map-contract widening
or a fabricated empty row.

## Isolation boundary

The first candidate fixture construction failed before tests: concurrent
schema edits exposed an unsupported storable tuple (`:seon.fn/form-span`).
A HEAD worktree with this lane's candidate overlay and canonical schema
resource directory then exposed a second boundary: default's live
`seon.program/identity-attributes` included `:seon.fn.file/path`, while HEAD's
armed identity contract did not. Restoring another lane's live Vars is not
permitted. An isolated snapshot JVM is used to continue through MCP.
These setup errors are not test verdicts for the candidate.

Protected foreign edits include the operator/hook assignment and subsequently
observed program/AI/test schema, test runner, turn, prompt/status/transcript
and other test files. None is changed by this lane.

## Verification and landing

Final cold gate pending. Gate request names `seon.cluster.turn-test` and
`seon.sci.eval-test`; the orchestrator owns the final namespace/platform gate.

### Slice 1 — deletion presence (landed source, 01:31 UTC)

Candidate runs on the isolated HEAD-plus-owned-paths JVM passed **4 tests / 19
assertions / 0 failures / 0 errors**. After in-place development adoption to
`turn-test-reds` at source commit `6aa9f0ad-d1e3-59e7-a4c4-2f65d9709d81`, the
same four fresh-base in-process runs passed **19 / 0 / 0** again. Recorded run
entities: 41318, 41319, 41320, 41321, in table order. No contract was weakened.
The class regression distinguishes absent, live, and tombstoned identities;
the three other tests retain their distinct live/fresh SCI observables.

Default adoption was attempted through the normal operator and refused during
publication because `:seon.issue/issue` names `seon.issue/render-ai` without a
declared function contract in the concurrent source population. This is a
publication boundary, not a passing default-adoption claim. The isolated
adoption succeeded and supplied the after-edit proof. Default remained alive.

The other 11 observation candidates produced ten green tests and one residual
schema-unregister assertion: its Datahike attribute remains installed after
its program definition disappears. No weakening of that assertion is retained.

The [exact fresh-base probe](turn-test-reds-probe-2026-09-16.clj) is preserved.
It uses the canonical `create-base`/`close-base!` owners, scoped fixture delays,
real `seon.test/run` and recorded results. The snapshot root is explicitly
named in the script, not inferred from a global database. Before the isolated
runs, the complete `:test` classpath from `clojure -Spath -A:test` was added to
an owned DynamicClassLoader; its context loader was installed only around the
serial future and restored in `finally`. This was necessary for fresh SCI
acquisition to load `seon.dev.dependency-cache-test` and its tools.build
 dependency. The first four-test candidate had three green tests and one
classpath setup error before this correction; it is not the final verdict.

### Slice 2 — current evaluation observations

Ten tests pass **32 assertions / 0 failures / 0 errors** before source edits
and again after editing, on the adopted snapshot definitions. Recorded runs:
41339–41344, 41349–41352. The [complete after-edit results](turn-test-reds-observations-after-edit-2026-09-16.edn)
name every test. They cover prose/doc, aliases, parse-time namespaces,
contracted redefinition, mixed private/durable definitions, import removal,
cross-agent calls, red-form continuation, cross-agent contract refusal, and a
bounded nonterminating evaluation.

The single observation helper queries actual agent-authored evaluations in
turn/ordinal order and throws if its query returns a typed refusal. Saved
`:seon.eval/shown` is compared as text, never decoded as an obsolete print
node. The error-data codec is unchanged. The namespace assertion now compares
all three expected namespaces instead of comparing a row's namespace with
itself. One-reply fixtures execute the two transitions they intend; they no
longer accidentally drive later continuation turns. No production continuation
or reader behavior changed.

### Protected schema-deletion residual

The [complete owning-seam trace](turn-test-reds-unregister-trace-2026-09-16.edn)
proves stale projection input: current and candidate forms are both absent,
but the transaction database contains the declaration and a live indexed long
attribute. The diff returns `[]`. The writer must derive its projection from
its actual mid-batch database facts before calculating schema changes. The
protected `seon.turn/row-tx` owner was not edited. Its issue records this exact
needed change; the old failing removal assertion remains intact.

A final read-only probe of the hot-reloaded deletion owner in default returned
`{:turn-test-reds/absent? true :turn-test-reds/deleted? true}` in **4 ms**.
This is direct live Var evidence, not a claim of complete default publication
or a namespace gate. The exact form is retained in the probe script.

### Slice 3 — complete execution and configuration fixtures

Eight tests pass **69 assertions / 0 failures / 0 errors** after editing and
in-place snapshot adoption at `6aa9f4da-6be3-5d8a-9558-2c59863ee53b`;
recorded runs 41494–41501. Each candidate passed its fresh-base in-process run
before the file edit. The [complete after-adoption results](turn-test-reds-fixtures-after-adoption-2026-09-16.edn)
name all eight tests.

The constructor seam is the real SCI evaluation and canonical configuration
application. Tests no longer invent impossible terminal envelopes, return a
vector from a map-returning installation owner, omit a required carried
projection, or pass an incomplete singleton to configuration reconciliation.
The combined-evaluation regression wraps the real installation function and
asserts one transaction for output, shown text, schema declaration, and closure.
The acquisition regression uses durable source and contracts rather than retired
private-root blobs. Provider cases exercise default retention and explicit
retention with complete configuration and real evaluation; normalized usage is
still checked from its stored provider document at this snapshot's HEAD.

The delimiter candidate's semantic assertions passed, but its existing
300 ms bookkeeping assertion measured **6694.698498 ms**. It is not retained or
weakened. This performance residual remains outside the landed fixture repair.

The [complete passing before-edit results](turn-test-reds-before-edit-2026-09-16.edn)
retain every final candidate's exact run identity and counts. These precede
their corresponding main source edits; the schema registration candidate ran
in the prior snapshot adoption group before it was written to the main file.

### Slice 4 — current rejection and message protocols

Four tests pass **53 assertions / 0 failures / 0 errors** before source edits
and after snapshot adoption `6aa9f6b4-2c5c-5fdd-a216-040762f4c6d0`. Recorded
runs 41533–41536; [complete results](turn-test-reds-protocol-after-adoption-2026-09-16.edn).
Unreadable and prose-only replies both record exactly one error evaluation,
retain successful provider-attempt evidence, expose the diagnostic in history,
and close on the following `:close` transition. The old pure-prose test claiming
no recorded evaluation is deleted; the parameterized rejection test covers it.

Message delivery is an immediate write at `my.message/send`, including inside
`do` when its return value is discarded. The successful-send test verifies the
message transaction precedes evaluation settlement, both message endpoints,
the reply chain and both agents' final state. The refusal test observes the
real typed unknown-recipient value and its saved shown text, with no message
written. The old returned-value/deferred-delivery transaction test is deleted
and superseded by these two current regressions. Schema registration reads
saved shown text and checks declaration provenance separately from the global
unique identity attribute. No reader, messaging, or schema production behavior
was changed in this slice.

### Dependency and verification boundaries

The deletion design uses Datahike's absent pull result
(`reference-code/datahike/src/datahike/pull_api.cljc:509`) and the existing
first-party `remaining-definition-facts` → `program/changed-attributes` seam.
SCI execution and isolation use the existing `sci.core/init`/`fork` mechanism
(`reference-code/sci/src/sci/core.cljc`) through canonical
`test-support/fork-cluster-ctx`; no result serializer or second context owner
was added. Fresh populations use the existing `test-support/create-base` and
`close-base!`, and test execution uses `seon.test/run` with the configured bound.

The successful adoptions accepted the selected source files with no blocking
syntax/name/arity errors. Non-blocking lint output includes the existing
duplicate `seon.cluster.message` require and shadowed bindings in this test
namespace; these remain part of the open consumer-fixture cleanup, not hidden
proof failures. The final census is a serial in-process namespace run on the
isolated initial-HEAD snapshot plus this lane's files. It is not the
orchestrator's cold namespace/platform gate and does not verify subsequent
concurrent production changes.

The unretained [schema-unregister candidate](turn-test-reds-unregister-candidate-2026-09-16.clj)
is preserved so the exact four observations can be rerun after repairing the
protected writer. It is loaded in `seon.cluster.turn-test` only for that probe;
it does not replace the checked-in failing test.

## Complete namespace census before slice 5

The serial fresh-base in-process census after slice 4 ran **57 tests / 321
passing assertions / 47 failures / 8 errors**. All **26 repaired tests** pass
within that run; **two obsolete tests** are superseded by current regressions.
**17 tests remain non-green.** [Complete values](turn-test-reds-final-census-2026-09-16.edn)
retain every failure and run identity. This is an isolated snapshot census,
not a same-JVM causal comparison with the initial default baseline.

| Test | Initial default pass/fail/error | Final snapshot pass/fail/error | Disposition |
|---|---:|---:|---|
| `a-batched-turn-commits-only-queryable-definition-facts` | 7/0/0 | 7/0/0 | Unchanged; green |
| `a-combined-evaluation-projects-every-terminal-receipt-datom` | 2/2/2 | 10/0/0 | Repaired; green |
| `a-completing-disposition-closes-in-the-terminal-transaction` | 2/0/0 | 2/0/0 | Unchanged; green |
| `a-held-runs-paid-call-is-never-duplicated` | 5/0/0 | 5/0/0 | Unchanged; green |
| `a-lost-model-call-leaves-a-durable-readable-reason` | 2/1/0 | 2/1/0 | Open; retained assertion |
| `a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact` | 6/0/0 | 6/0/0 | Unchanged; green |
| `a-prompt-refusal-is-a-recorded-error-value-never-a-throw` | 1/3/0 | 1/3/0 | Open; retained assertion |
| `a-prose-prefixed-contracted-defn-settles-and-doc-answers` | 3/0/1 | 4/0/0 | Repaired; green |
| `a-pure-prose-reply-refuses-and-records-no-unsettleable-form` | 3/2/1 | — | Superseded by slice 4 current-protocol regression |
| `a-real-evaluation-that-runs-away-is-stopped-and-recorded` | 1/1/0 | 2/0/0 | Repaired; green |
| `a-red-form-routes-to-its-namespace-owner-and-the-fold-continues` | 4/1/0 | 5/0/0 | Repaired; green |
| `a-refused-contract-commits-a-receipt-and-no-row` | 2/0/0 | 2/0/0 | Unchanged; green |
| `a-refused-definition-stays-in-its-agents-defs` | 0/0/1 | 0/0/1 | Open; retained assertion |
| `a-refused-delivery-becomes-a-durable-error-fact` | 0/0/1 | 6/0/0 | Repaired; green |
| `a-run-prompts-from-its-opening-database-value` | 7/4/0 | 7/4/0 | Open; retained assertion |
| `a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` | 1/3/1 | 9/0/0 | Repaired; green |
| `a-turn-hands-its-clusters-projection-to-every-database-call` | 6/0/0 | 5/1/0 | Open; retained assertion |
| `a-waiting-disposition-frees-the-agent-and-keeps-its-note` | 6/0/0 | 6/0/0 | Unchanged; green |
| `a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end` | 1/0/1 | 14/0/0 | Repaired; green |
| `a-whole-turn-runs-from-trigger-to-closed-run` | 1/3/0 | 6/0/0 | Repaired; green |
| `absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx` | 0/0/1 | 4/0/0 | Repaired; green |
| `acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles` | 0/0/1 | 7/0/0 | Repaired; green |
| `agent-code-with-defn-and-println-folds-green-without-in-ns` | 1/2/2 | 6/0/0 | Repaired; green |
| `an-unpaid-failure-with-a-backup-makes-exactly-two-calls` | 13/2/0 | 13/2/0 | Open; retained assertion |
| `an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence` | 0/0/1 | 28/0/0 | Repaired; green |
| `another-agent-calls-the-live-cluster-definition-without-reinstall` | 0/1/0 | 1/0/0 | Repaired; green |
| `another-agent-sees-a-flat-contract-violation-after-live-install` | 0/1/1 | 2/0/0 | Repaired; green |
| `concurrent-streams-share-one-conn-test` | 0/0/1 | 0/0/1 | Open; retained assertion |
| `contracted-redefinition-exactly-replaces-the-row` | 4/0/1 | 5/0/0 | Repaired; green |
| `delimiter-repair-is-span-local-and-precedes-intent` | 8/3/4 | 8/3/4 | Open; retained assertion |
| `delivery-rows-and-refusal-facts-share-the-terminal-transaction` | 0/3/1 | — | Superseded by slice 4 current-protocol regression |
| `evaluation-follows-the-readers-parse-time-namespace` | 2/0/1 | 3/0/0 | Repaired; green |
| `function-install-reads-the-case-count-from-cluster-facts` | 2/2/0 | 4/0/0 | Repaired; green |
| `generated-fixed-point-closes-the-run` | 1/3/0 | 1/3/0 | Open; retained assertion |
| `generated-membership-failure-never-advances-the-run-to-call` | 1/3/0 | 1/3/0 | Open; retained assertion |
| `generated-model-attempt-traces-preserve-presence-and-episode-laws` | 0/1/0 | 0/1/0 | Open; retained assertion |
| `generated-phase-failures-converge-through-one-terminal-exit` | 2/3/0 | 2/3/0 | Open; retained assertion |
| `import-addition-is-ordinary-data-and-reacquires-exactly` | 2/0/0 | 2/0/0 | Unchanged; green |
| `import-only-ns-unmap-installs-exactly-after-its-context-commit` | 3/0/1 | 4/0/0 | Repaired; green |
| `incompatible-clusters-alternate-runtime-schema-validation-without-bleed` | 9/0/0 | 9/0/0 | Unchanged; green |
| `mixed-plan-publishes-only-the-contracted-function` | 2/1/1 | 4/0/0 | Repaired; green |
| `ns-unmap-retracts-the-owned-function-after-the-terminal-commit` | 0/0/1 | 7/0/0 | Repaired; green |
| `one-successful-call-leaves-exactly-one-attempt-fact` | 8/0/0 | 8/0/0 | Unchanged; green |
| `qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context` | 0/0/1 | 2/0/0 | Repaired; green |
| `reasoning-only-time-limit-persists-its-flat-diagnostic` | 9/0/0 | 9/0/0 | Unchanged; green |
| `reasoning-starvation-persists-usage-finish-and-the-named-error` | 4/1/0 | 11/0/0 | Repaired; green |
| `refused-import-only-ns-unmap-leaves-the-run-sci-ctx-unchanged` | 3/0/0 | 3/0/0 | Unchanged; green |
| `refused-runtime-schema-registration-mutates-neither-row-nor-projection` | 5/0/0 | 5/0/0 | Unchanged; green |
| `refused-terminal-program-transactions-settle-and-do-not-refire` | 19/13/0 | 19/13/0 | Open; retained assertion |
| `reply-reading-follows-evaluated-alias-and-dynamic-require-state` | 0/0/1 | 2/0/0 | Repaired; green |
| `runtime-declarations-install-only-from-a-successful-terminal-db-after` | 4/0/0 | 4/0/0 | Unchanged; green |
| `runtime-schema-key-changes-pass-the-one-usage-guarded-decision` | 2/4/0 | 2/4/0 | Open; retained assertion |
| `runtime-schema-registration-commits-the-evaluated-form-and-attribute` | 6/3/0 | 10/0/0 | Repaired; green |
| `runtime-schema-unregister-removes-one-unused-global-schema` | 0/0/1 | 0/0/1 | Open; retained assertion |
| `runtime-tests-install-run-redefine-and-delete-exactly` | 0/0/1 | 6/0/0 | Repaired; green |
| `singleton-enum-uses-are-members-of-their-declared-enums` | 2/0/0 | 2/0/0 | Unchanged; green |
| `streaming-writes-zero-datoms-test` | 7/2/1 | 7/2/1 | Open; retained assertion |
| `successful-call-persists-the-providers-open-usage-document` | 0/4/1 | 11/0/0 | Repaired; green |
| `turn-intent-is-the-complete-crash-falsifier` | 10/4/0 | 10/4/0 | Open; retained assertion |

### Residual attribution after the census

[Complete observer values](turn-test-reds-residual-observations-2026-09-16.edn)
show that the backup fixture's sparse entity write is refused for the missing
manifest digest, while the effective configuration still has the shipped
backup model. `with-cluster` upserts its compiled desired row over a seeded
configuration; omission of the backup key does not retract the seeded value.
That constructor must exact-reconcile its intended configuration. The generated
retry fixture has the same unobserved sparse-write problem. These are recorded
in the open consumer-fixture issue, not attributed to the production provider.

The projection-carrying test's fresh-base rerun passes **6 / 0 / 0**, run 41646;
the observer captures no derivation on its bare thread. This does not explain
the census's one derivation, so that residual remains unproven and its assertion
is unchanged. MCP twice reported the scratch cluster state as `unknown` while
evaluation answered; runtime status then showed readiness and all three
plumbing procs replying, and subsequent envelopes again reported `alive`. No
MCP outage workaround or process restart was performed.

### Residual classes and exact next boundary

| Class | Remaining tests | Evidence and needed work |
|---|---|---|
| Generated context and prompt protocol drift | lost model reason, prompt refusal, opening database prompt, generated fixed-point/membership/phase tests | Recorded assertions depend on old opening membership, replaced prompt refusal premises, or injected values replacing actual system reads; verify current protocol before rewriting |
| Removed storage and settlement protocol | refused private definition, refused terminal program transactions, crash-intent test | Old private storage queries and per-evaluation settlement injection no longer reach the actual writer; retain current refusal/recovery guarantees at their owners |
| Configuration construction | generated model attempt traces | Sparse config updates are refused; seeded optional backup is not retracted by a desired-row map upsert; exact-reconcile the fixture before interpreting retry outcomes |
| Streaming fixture and terminal observations | streaming-writes, concurrent streams | Retired result attribute plus injected completion and request indexing; use real evaluation and exact terminal facts before changing stream owner |
| Schema change policy and projection | schema-key changes, unregister | Preserve the existing [owner-ruling issue](../../../seon/issues/within-run-schema-key-refinement-needs-an-owner-ruling.md); unregister's writer uses a stale mid-batch projection, with trace in its separate issue |
| Reader/performance | delimiter repair | Saved-text candidate semantic assertions pass, but 6.7 s exceeds the unchanged 300 ms bound; no unverified performance fix retained |
| Projection carry | bare-thread projection test | Full census records one derivation; fresh rerun records none. Cause remains unknown, not assigned to a foreign lane |

These residuals remain in the open class issue; the class is not claimed
closed. The production absence-comparison class is closed by construction,
and the landed test slices remove the named stale protocols without adding
a fallback or weakening an asserted invariant.

### Slice 5 — fail loudly at backup fixture construction

`configure-backup!` now supplies explicit attribute additions to the existing
configuration identity instead of an incomplete entity map. A writer refusal
throws at setup, so later assertions cannot silently test the shipped target.
It preserves the primary settings and uses the existing database transaction
API; it does not introduce a fallback or change provider behavior.

The sole caller's unchanged regression passes **15 / 0 / 0** before the edit
(run 41652) and after in-place adoption at
`6aa9f9fd-e0b5-592e-97aa-2d953a2ad808` (run 41665).
[Complete before/after values](turn-test-reds-backup-before-after-2026-09-16.edn).
This brings the landed total to **27 repaired tests / 188 passing assertions**
across the focused after-edit runs, plus two superseded tests. The complete
census above predates this last helper repair; it is not silently rewritten
as a later whole-namespace result. Sixteen census members remain unresolved,
including the projection test that passed its fresh rerun without an attributed
cause. Cold namespace/platform verification remains the orchestrator's gate.

Final default status is degraded: its advertised PID is now 7595 (the lane
started against 69622), and `seon.problems/problems` refuses an error occurrence
count with “expected an integer, got an integer.” The exact boundary is in
[the status issue](../../../seon/issues/runtime-status-refuses-error-occurrence-count.md).
No default stop, refork or restart was performed by this lane; no foreign
status/problem/error owner was edited to repair the observation.

## Landed commits and owned paths

`ecdd61ffe`, `767a4238f`, `78b7ad4fb`, `517e045d5`, `4b3322b04`.

- `docs/prds/context-generation/research/turn-test-reds-2026-09-16.md`
- `docs/prds/context-generation/research/turn-test-reds-backup-before-after-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-baseline-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-before-edit-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-candidate-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-deletion-after-adoption-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-delimiter-candidate-2026-09-16.clj`
- `docs/prds/context-generation/research/turn-test-reds-delimiter-result-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-final-census-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-fixtures-after-adoption-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-observations-after-edit-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-probe-2026-09-16.clj`
- `docs/prds/context-generation/research/turn-test-reds-protocol-after-adoption-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-residual-observations-2026-09-16.edn`
- `docs/prds/context-generation/research/turn-test-reds-unregister-candidate-2026-09-16.clj`
- `docs/prds/context-generation/research/turn-test-reds-unregister-trace-2026-09-16.edn`
- `docs/seon/issues/archive/turn-declaration-deletion-refuses-after-commit.md`
- `docs/seon/issues/runtime-schema-unregister-retains-installed-attribute.md`
- `docs/seon/issues/runtime-status-refuses-error-occurrence-count.md`
- `docs/seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md`
- `docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md`
- `src/seon/sci/eval.clj`
- `test/seon/cluster/turn_test.clj`

## Cleanup and handoff

The isolated operator completed `down`: PID 3851 exited, the store flock was
free and its branch roster readable. Its worktree and lane scratch directory
were then removed; the shared `reference-code` source remains present. All
owned command sessions and serial test futures completed before cleanup.
Foreign worktrees, processes, source edits and test roots were preserved.

The gate request is `tmp/orchestrator/gate-requests/turn-test-reds.txt`, with
`seon.cluster.turn-test` and `seon.sci.eval-test` on separate lines. No cold
`bin/test` or platform result is claimed: the assignment delegates that final
proof to the orchestrator. This lane lands the five bounded repair slices
above; the complete remaining class is explicitly open.

Handoff recorded 2026-09-16 02:12 UTC, within the 90-minute assignment bound.


## Batch-23 continuation handoff — 2026-09-16

The [continuation landing](turn-test-reds-cache-2026-09-16.md) supersedes the
earlier residual census for the 44 batch-23 red cluster-turn members. The
transaction-cache structural repair landed in Datahike `49ea5933` and main
`c1d7d4695`. Eleven additional members have 92 passing focused assertions;
29 replay green unchanged; four remain explicitly unresolved. The continuation
contains every member verdict, exact runs, complete returned evidence, three
priced options for provider diagnostic visibility, and the four-namespace gate
request. No cold namespace/platform result is claimed.

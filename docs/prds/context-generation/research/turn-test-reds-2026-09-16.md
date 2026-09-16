---
type: research
status: active
tags: [research, test, runtime, class/p3]
---

# Turn test reds — 2026-09-16

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
Applied data-oriented-clojure, repl, clojure-testing and datahike skills.

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
| Removed result codec and queries that mix system reads with submitted evaluations | prose/doc, alias reading, parse namespace, contracted redefinition, mixed plan, schema unregister, import-only unmap, cross-agent live call | Seven observation candidates verified; schema-unregister residual recorded below | Ordered, author-qualified evaluation query; exact saved text and existing durable/live effects |
| Incomplete injected installation envelope | combined evaluation | Unrepaired: fixture returns the vector from `swap!`, violating the map result contract | Must retain terminal transaction observables or identify current coverage before retiring serialization assertions |
| Incomplete environment/config construction | acquisition ordering, function case count, refused delivery | Unrepaired | Supply canonical projection/config compiler output |
| Retired execution/storage assumptions | returned message effects, private definition storage, old generated-turn fixtures, crash intent, prompt refusal | Unrepaired; requires individual ruled-behavior evidence before disposition | Existing current owners must provide the proof |
| Continuation/system-read interference in injected evaluator fixtures | whole-turn fixture, runaway count, opening prompt, streaming and generated failure traces | Unrepaired | Real SCI and explicit terminal boundaries |
| Provider reasoning retention expectation | reasoning starvation, successful usage | Unrepaired | Explicit configured retention policy |
| Schema refinement ownership | runtime schema key changes | Unrepaired; preserve existing unresolved owner ruling | No inferred policy change |

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

Final cold gate pending. Gate request will name `seon.cluster.turn-test` and
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

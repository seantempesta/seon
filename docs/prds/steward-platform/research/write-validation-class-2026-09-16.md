---
type: research
status: active
tags: [research, database, schema, test]
---

# Write validation class — 2026-09-16

Implementation: `20d30a0bd` on steward-platform. Integration gate pending.
No test JVM was launched. Default pid 7595 was never stopped, reforked or
restarted. All tests below ran serially through MCP JVM mode.

## Class and guarantee

A raw write map selects entity contracts only through an installed unique
identity it asserts that the contract requires; optional mentions and reverse
refs never select another entity contract, and maps asserting no identity
validate only their supplied attributes.

The owner is `seon.db/write-entity-schemas`, consumed by
`seon.db/write-map-error`. The old optional-mention selection is removed.
The cache key now names required-identity selection so existing live projection
caches cannot reuse the old selection. No second registry or transaction engine
was added. Reverse map refs use Datahike's reverse-ref and collection semantics,
then the existing recursive reference validator. Datahike still resolves refs.

What this gives up: this is not whole-entity validation after every datom.
Identity-free partial maps and :db/add remain attribute writes. Conversely,
asserting a required identity in a map still requests its complete entity
contract. Arbitrary incomplete identity upserts remain a named residual; making
them pass would contradict the assignment's incomplete-evaluation regression.
No racy entity pre-read was introduced.

Fixture setup now checks every flat result from its own population, provenance
transaction, extra-schema transaction, configuration and cluster seed.
`checked-fixture-result` throws with the complete refusal as ex-data before
continuing. This covers the canonical helper owners, not arbitrary test bodies
that deliberately call db/transact! to examine a refusal. Test-local seed
helpers outside this lane still need conversion; see the residual below.

## Authorities and dependency ledger

Read AGENTS.md, docs/seon/issues/README.md, the raw-write issue and its dated
2026-09-09 / batch-20 records, the class-mining report, and error-graph-2026-09-16.md
end to end. The assigned write class is newer than the 2026-08-11 inventory;
there is no separate mining class note/member roster for it. Its fixture
consequence is N2's non-vacuous-proof construction: production constructors
must supply complete subjects and a failed premise must be observable.
Read the context-generation roadmap and working edge. Applied
data-oriented-clojure, datahike, data-modeling, repl and clojure-testing.

- Datahike `reference-code/datahike/src/datahike/db/utils.cljc:67` and
  `reference-code/datahike/src/datahike/db/transaction.cljc:717`: reverse-ref
  recognition, forward ref requirement, scalar/collection/lookup syntax.
- Malli entry shape: `src/seon/schema/form.cljc:30`; optional entry properties
  distinguish a mention from a required identity.
- `src/seon/schema/datahike.clj:299`: storable-attribute-in? checks storage
  derivation, not entity ownership. The bridge does not need a change.
- `src/seon/db.clj:2596`, `:2702`, `:2765`: candidate selection,
  attribute/reference checks and entity-map admission.
- `test/seon/test_support.clj:201`: one checked fixture result owner.
- `26ec13420` introduced the faulty selection on 2026-09-09; read its
  history before changing it.

## Per-member verdicts and exact live bytes

1. **Optional identity mention: resolved by 20d30a0bd.**
   Before: `{:seon.problems/id "x"}` refused at
   `[0 :seon.cluster.eval/id]`, “got a map missing :seon.cluster.eval/id”.
   After: admission returns nil; the canonical transaction commits and a
   separate pull returns "x". No unrelated evaluation contract is selected.
2. **Reverse refs: resolved by 20d30a0bd.**
   Before `{:seon.turn/_attempts 1}`: “expected an installed attribute,
   got an undeclared attribute.” After: no admission error. Canonical
   regression commits the reverse lookup-ref shape and queries its forward
   :seon.turn/attempts edge. Invalid nested identity data retains
   `[0 :seon.turn/_attempts 0 :seon.turn/id]`.
3. **Incomplete identified entities: preserved refusal, named residual.**
   `{:seon.cluster.eval/id "incomplete"}` still refuses at
   `[0 :seon.cluster.eval/run]`; no transaction is committed.
   `{:seon.turn/id "gauge-run"}` still refuses its required agent.
   A direct model probe with :seon.ai.model/id "deepseek-flash" and
   :seon.ai.model/max-output-tokens 1000 likewise refuses its required
   :seon.ai.model/provider. This confirms the original model partial-upsert
   observation remains within the declared constraint.
   [Residual](../../../seon/issues/identity-upserts-still-require-complete-entity-maps.md).
4. **Default's fault-map refusal: already dissolved at HEAD.**
   Read-only probe before edits showed installed :seon.error/signature has
   :db.unique/identity. The reported map (64-character id and signature,
   :seon.error/kind :seon.error/unclassified) returns no write admission
   error. Installed :seon.error/error requires signature and kind and makes
   id optional; no required :seon.error/at remains. No RESET NEEDED is
   established for this member now. This does not claim old error rows
   have been migrated; error-graph's legacy-data warning remains its boundary.
5. **Silent canonical setup refusal: resolved by 20d30a0bd.**
   Actual wrong attribute value in extra-schema throws :seon.db/invalid-write,
   path [0 :my.plan.item/title], offending 42; the test body never runs.
6. **Refusal grammar: open, outside owned files.**
   `seon.error/explain-problem` at src/seon/error.clj:727 owns the wording.
   [Exact bytes and acceptance](../../../seon/issues/refusal-grammar-describes-composite-schemas-as-unknown-error.md).
7. **Stale transcript/turn fixtures: open, separate owner.**
   The old failure was unmasked: seed-populated-history! now refuses at
   `[7 :seon.cluster.eval/result-edn]`: “expected an installed attribute,
   got an undeclared attribute.” It previously stopped at the problems id.
   [Exact handoff](../../../seon/issues/transcript-and-turn-fixtures-retain-deleted-storage-and-turn-shapes.md).

The original compound issue is superseded with those residuals, not falsely
reported as complete Datahike partial-upsert parity.

## REPL-first implementation and tests

Every changed helper was evaluated as a candidate in its existing namespace
through MCP before its source edit. write-entity-schemas and
write-attribute-error were called with default's carried projection and real
database. checked-fixture-result, run-database-body, populate-database! and
seed-cluster! were exercised through real canonical setup, including fresh
in-memory population. No wrappers were disabled, no mocked writer was used.

Exact ordinary invocation for each listed Var:

```clojure
(seon.test/run #'<fully-qualified-test> (seon.operator/connection "default"))
```

- `seon.transact-feedback-test/raw-write-maps-select-only-their-asserted-required-identity`:
  candidate 12/0/0 at 03:17:21Z; local binding rename candidate 12/0/0
  at 03:22:00Z; adopted 12/0/0 at 03:27:39Z, run 71855,
  basis 536871925.
- `seon.test-support-test/fixture-setup-refusals-stop-before-the-body`:
  negative premise first 4 passing assertions; the initial additional
  fresh-store check correctly refused missing fixture-observation, then hit
  the default 20,000 ms bound. With explicit fixture observation and
  `{:seon.test/remaining-ms 50000 :seon.test.run/provenance
  (seon.test.runner/provenance (seon.db/db c))}`, the combined real fresh
  population/seed probe passed 6/0/0 before edit (28,739 ms) and after edit
  (23,298 ms). The recurring test uses an ordinary branch rather than
  charging fresh population every iteration: candidate 6/0/0 at 03:27:33Z,
  post-edit candidate 6/0/0 at 03:28:25Z, run 71861, basis 536871929.
  The final test-only edit's hook was refused later; its automatic adoption
  is unverified. The production helper edit had already converged and passed
  the fresh-store check after adoption.
- `seon.render.transcript-test/about-identity-resolution-pulls-one-deterministic-ordered-id-vector`:
  baseline triage 2/7/1; now 10/0/0 (3,092 ms).

The initial fresh-store candidate had one unmatched parenthesis, refused by
the MCP reader before execution, then was corrected. A third-arity run without
provenance also correctly refused; neither is a green test result.

Hook publication cfd0ab08-71fb-4a32-bfb8-d222ce4fcdcd reported convergence
to source commit 6aaa0c28-fb31-5521-9bd8-c9c64e5f123f. A read-only program-row
probe found both required-identity selection and checked fixture source in
default. This is in-place development adoption, plus explicit candidate Var
evaluation; not a new fork or a browser-paint claim. Hooks reported zero
reaching tests, so manual in-process results above are the actual evidence.

Final freshness observation: default's adopted commit remained
6aaa0c28-fb31-5521-9bd8-c9c64e5f123f while current-src was
6aaa0d89-cc86-5f33-b70a-db191a36782d. Hook b8e03801-8744-492e-a311-8fd61ea5a753
reported publication refused. The 03:32:32Z failure log names an uninstalled
:seon.issue/agent during branch publication. The issue owner and schemas
were concurrently edited; this is the exact foreign publication boundary,
not a cause inferred from timing. No protected owner was changed to repair it.

## Affected tests, serial in-process observations

Counts are pass/fail/error. This is a dated observation, not a maintained
test roster. The long property was not run. Every red below is handed to
the transcript/turn fixture owner, not claimed fixed by admission.

| Test Var | Counts |
|---|---|
| `seon.render.transcript-test/about-identity-resolution-pulls-one-deterministic-ordered-id-vector` | 10/0/0 |
| `seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable` | 8/4/0 |
| `seon.render.transcript-test/the-history-query-bounds-what-the-transcript-pulls` | 4/0/0 |
| `seon.render.transcript-test/historical-shown-text-keeps-its-original-elision` | 9/0/0 |
| `seon.render.transcript-test/supersession-chains-vanish-from-the-history` | 1/4/0 |
| `seon.render.transcript-test/durable-history-entries-never-invent-executions` | 3/0/0 |
| `seon.render.transcript-test/same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order` | 10/5/2 |
| `seon.render.transcript-test/selected-evaluations-project-only-their-stored-source-and-result` | 0/0/1 |
| `seon.render.transcript-test/the-transcript-is-whole-and-the-ai-boundary-elides-it` | 10/6/0 |
| `seon.render.transcript-test/error-receipt-without-triage-has-an-execution-error-face` | 7/0/0 |
| `seon.render.transcript-test/selected-run-keeps-status-outside-agent-visible-text` | 0/1/1 |
| `seon.render.transcript-test/receipt-content-enters-the-shared-capped-floor` | 9/5/0 |
| `seon.render.transcript-test/populated-history-restores-the-repl-fidelity-checklist` | 10/11/0 |
| `seon.render.transcript-test/stored-evaluations-are-terminal-transcript-values` | 1/9/0 |
| `seon.render.transcript-test/history-unit-derives-both-projections-from-one-bounded-derivation` | 1/7/1 |
| `seon.render.transcript-test/admitted-top-level-string-is-terminal-text` | 0/0/3 |
| `seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` | 32/4/1 |
| `seon.render.transcript-test/reasoning-is-html-only-and-inline-blob-history-has-one-disclosure` | 13/0/0 |
| `seon.turn-loop-test/attempt-settlement-updates-the-registered-model-gauges` | 0/4/0 |
| `seon.turn-loop-test/a-refused-terminal-commit-still-closes-the-run` | 1/1/0 |
| `seon.turn-loop-test/kill-positions-per-agent-test` | 1/5/1 |

The 18 ordinary transcript tests yielded 6 green and 12 red. The three
requested turn-loop tests remain red. The exact known causes are the deleted
bounded-result (ff9507c1b), deleted result-edn storage, missing runtime/turns
fixture links (ae0e54841), closed-tx as a ref (ae0e54841), and incomplete
gauge-turn creation. The rest require owner verification rather than blanket
attribution. kill-positions also recorded an error in this run.

## Boundaries, gates and cleanup

No protected issue.clj, cluster/source.clj, bin, turn.clj or plan.clj changed.
The db.clj diff was checked before edits and commit and contained only this
lane's admission hunks. The bridge was read but unchanged. Existing source
lint warnings outside these hunks remain; the new shadowed local was renamed.
After the implementation commit, another lane began its db.clj retention
changes. Those later uncommitted hunks are not this lane's work. Markdown
hooks also reported 12 existing gitlink citation errors in
docs/prds/context-generation/research/agents-md-audit-2026-09-15.md; the
elided feedback is not a complete independent audit of those findings.

Two MCP read-only result inspections timed out at 30 seconds; smaller
projections subsequently succeeded. The timeout does not prove default
stopped. No alternate REPL transport or restart was used.
[Tool record](../../../seon/issues/mcp-jvm-small-result-projection-fails-during-live-adoption.md).

Requested orchestrator gate: src/seon/db.clj, test/seon/test_support.clj,
test/seon/test_support_test.clj and test/seon/transact_feedback_test.clj;
namespaces seon.transact-feedback-test and seon.test-support-test, followed
serially by --platform. Request at tmp/orchestrator/gate-requests/write-validation-class.txt.
The namespace/platform gate has NOT run in this lane. git diff --check passed.

No lane-owned background shell, scratch cluster or worktree was created.
The replayable probe is [write-validation-class-probe-2026-09-16.clj](write-validation-class-probe-2026-09-16.clj).

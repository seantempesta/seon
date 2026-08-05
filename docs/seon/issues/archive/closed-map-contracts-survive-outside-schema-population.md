---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, agent, documentation]
---

# Remove closed map contracts outside the canonical schema population

## Problem

Owner ruling #48 applies to function arguments and entity/value shapes alike,
but the bounded schema-population wave cannot finish the repository-wide rule.
After opening `resources/seon/schema.edn` and the `seon.schema` owner, 34
`:closed true` properties remain under `src/`. They include public function
contracts in bootstrap, run custody, config, indexing, rendering, and SCI
reading.

The bootstrap is actively wrong rather than merely old source style:
`resources/seon/bootstrap.edn:19-22` tells agents that input maps must declare
`{:closed true}`, its worked repair at lines 54-59 adds closed input and output
maps, and `src/seon/bootstrap_drive.clj:260-270` grades the now-deleted
`:seon.schema/open-argument-map` refusal. The data-modeling skill also still
describes the three derived config composites as closed.

## Evidence

`rg -F ':closed true' src` reports 34 occurrences after the canonical
population reaches zero. `rg` reports three more in
`resources/seon/bootstrap.edn`. The bootstrap regression at
`test/seon/bootstrap_test.clj:75-78` asserts that the repair adds the property,
and `test/seon/bootstrap_drive_test.clj:7-18` supplies another closed contract.

The 2026-08-04 isolated dogfood pass confirmed that this is the first guidance
a newly created agent actually receives. `(help)` rendered this current face:

```text
The contract is checked, so write it honestly: input maps must say
{:closed true}, and a return may not be a bare [:maybe ...].
```

The very next worked definition used an open map and was admitted, returning
the new Var face. The instruction therefore contradicts both the owner ruling
and the behavior in the same bootstrap run.

## Owner

The public contract owner in each namespace, plus the one bootstrap plan and
its evaluation grader.

## Acceptance

Every live first-party function and value contract is open unless a later
owner ruling names an exception. The bootstrap teaches and demonstrates
accretion, its grader proves an open argument map publishes successfully, and
the data-modeling skill cites current open derived config forms. A full
`rg -F ':closed true'` census contains only historical archaeology or a
deliberate regression fixture that constructs the former behavior explicitly.

## Resolution

Resolved by `ce099ce79`. The authored-contract admission owner no longer has
an open-map refusal; every surviving refusal remains. All live first-party
contract properties found in the re-derived census were removed, including 16
in `src/seon/cluster/run.clj`, one in `src/seon/cluster/loop.clj`, one in
`src/seon/cluster.clj`, one in `src/seon/cluster/store.clj`, two in
`src/seon/fn/analyzer.clj`, and one in `src/seon/sci/reader.cljc`.
`src/seon/config.clj` also stopped enforcing the same closedness by a parallel
unknown-key check: it now projects declared keys, validates those rigorously,
and ignores extras. `src/seon/schema/internal.cljc` retains the surviving
undefined, incomplete-predicate, nilable-map-value, and nilable-return
refusals, with no `:seon.schema/open-argument-map` arm.

The bootstrap refusal/repair beat now uses
`:seon.schema/undefined-contract`: form 8 authors `:any`, and form 9 repairs it
with the concrete open schema. Help states that declared keys validate and
extras are ignored. This changes the bootstrap plan digest. Existing clusters
retain their sovereign old plan; only newly populated clusters receive the new
plan.

The standing guard in `test/seon/schema/admission_gate_test.clj` parses every
declaration under `resources/seon/schemas/` and refuses any map property with
`:closed true`. It also scans the authored-contract admission path, so the
deleted rule cannot silently return. `test/seon/cluster/turn_test.clj` proves
an open input-map contract publishes its exact `:seon.fn/spec`, and calls it
with an ignored extra key.

## Complete census disposition

Deleted or aligned production and resource sites:

- `resources/seon/bootstrap.edn`, `src/seon/bootstrap_drive.clj`,
  `src/seon/cluster.clj`, `src/seon/cluster/loop.clj`,
  `src/seon/cluster/run.clj`, `src/seon/cluster/store.clj`,
  `src/seon/config.clj`, `src/seon/fn/analyzer.clj`,
  `src/seon/schema/internal.cljc`, and `src/seon/sci/reader.cljc`.
- No declaration under `resources/seon/schemas/` contains the property.
- `src/seon/schema/admission.clj` deliberately retains the inverse guard that
  refuses a first-party declaration containing `{:closed true}`; it teaches
  open maps and is not a closed validation call.

Deleted or aligned regression sites:

- `test/seon/bootstrap_drive_test.clj`, `test/seon/bootstrap_test.clj`,
  `test/seon/cluster/turn_test.clj`, `test/seon/config_test.clj`,
  `test/seon/dev/fresh_operator_test.clj`, `test/seon/instrument_test.clj`,
  and `test/seon/schema/admission_gate_test.clj`.
- `test/seon/dev/edit_feedback_test.clj` and
  `test/seon/schema/admission_gate_test.clj` retain deliberate source-text
  fixtures proving closed declarations refuse. The bootstrap test retains a
  negative assertion. `test/seon/cluster/run_test.clj` uses `:closed` as a run
  lifecycle boolean, not a Malli property.

Aligned current documentation and skills:

- `.agents/skills/data-modeling/SKILL.md`,
  `.agents/skills/seon-context-config/SKILL.md`,
  `docs/seon/architecture/context.md`, `docs/seon/reference/config-operations.md`,
  `docs/prds/source-cleanup/roadmap.md`,
  `docs/prds/sci-execution-runtime/conversion-wiki.md`, its exact
  `plan/reference/` copy, `plan/ai-settings-design-2026-08-01.md`,
  `plan/bootstrap-vector-design-2026-08-01.md`,
  `plan/context-blocks-contracts-2026-07-28.md`,
  `plan/generate-code-v0-plan-2026-07-29.md`, and
  `specs/w1.7-floors-exposure-reference.md`.
- `docs/seon/issues/instrumentation-headline-unbounded-when-caps-absent.md`,
  `docs/seon/issues/observable-graph-transitions-are-polled-in-tests.md`, and
  `docs/seon/issues/thinking-tool-continuations-have-no-faithful-request-shape.md`.
- Remaining exact mentions in `docs/conventions.md`,
  `docs/prds/agent-bootstrap/README.md`, the active plan README, overnight
  ledger, unsettled ledger, and both conversion wikis either prohibit the
  property or record its removal; none teaches closed maps.

Retained as historical evidence, not current guidance:

- Every `docs/prds/archive/**` occurrence.
- The dated `docs/prds/source-cleanup/research/` files
  `browser-validation-benchmark-2026-07-20.md`,
  `database-result-union-boundary-2026-07-20.md`,
  `per-operation-config-boundary-2026-07-20.md`,
  `plan-value-ui-crossing-ruling-2026-07-20.md`,
  `plan-value-ui-migration-readiness-2026-07-20.md`,
  `schema-aware-inspector-2026-07-20.md`,
  `stage1-6-live-graduation-runbook-2026-07-20.md`,
  `stored-rows-schema-projection-boundary-2026-07-20.md`,
  `transact-response-union-boundary-2026-07-20.md`,
  `universal-data-browser-ui-crossing-ruling-2026-07-20.md`, and
  `value-drill-public-schema-ruling-2026-07-20.md`.
- The dated `docs/prds/sci-execution-runtime/research/` evidence in
  `admission-caps-and-blob-fallback-2026-08-01.md`,
  `agent-model-override-quarry-2026-07-31.md`, `b2-plan-2026-07-27.md`,
  `bootstrap-curriculum-2026-08-03.md`,
  `cache-economics-measurement-2026-08-03.md`,
  `issues-triage-2026-08-03.md`, `malli-schema-parsing-2026-08-01.md`,
  `mvp-seams-notes-2026-07-31.md`, `percall-llm-config-2026-07-29.md`,
  `refusal-continuation-notes-2026-07-31.md`,
  `repl-dogfood-code-2026-08-04.md`, `seondb-facade-quarry-2026-07-29.md`,
  `visual-qa-2026-07-31.md`, and its three raw `ai-*.txt` captures.
- Archived issue evidence in
  `docs/seon/issues/archive/config-derivation-drops-one-backup-attribute.md`,
  `mcp-truncates-instead-of-using-the-value-system.md`,
  `message-resolves-recipient-at-stale-basis.md`,
  `output-map-closedness-decides-accretion-legality.md`,
  `sci-eval-evaluation-schema-does-not-resolve-its-predicate.md`, and
  `toolkit-teaches-a-db-ok-contract-transact-does-not-produce.md`.

No load-bearing exception or third-party boundary was escalated.

## Proof

- Focused config/schema/bootstrap/instrumentation gate: 55 tests, 256
  assertions, zero failures or errors.
- Full turn gate: 47 tests, 272 assertions, zero failures or errors, including
  the persisted open-map contract regression.
- Fresh isolated operator root `tmp/open-everywhere-proof` published digest
  `ea55a71e0173248eedcc4216e3f706c65c61e9b7db4867951a9d230ba170465f`.
  Its live run persisted
  `[:=> [:cat [:map [:my.agents.open-map-proof/value :int]]] :int]`; calling
  the installed function with `:my.agents.open-map-proof/extra` returned `43`.
- The newly populated cluster evaluated packaged form 8 through the guarded
  door and returned `:seon.schema/undefined-contract` with the readable
  `uses :any in an agent-authored contract` refusal.
- The unrelated `seon.sci.eval-test` gate is red at the landed foreign
  boundary: 51 tests / 243 assertions, five failures in
  `bare-dir-and-program-derived-doc-are-repl-native` and
  `agent-contracts-apply-on-acquire-and-cold-recovery`. This change does not
  edit that owner or green-wash the result.

Ugly output observed: expected Datahike rejections still print full writer
exceptions in bulk, tracked by
`docs/seon/issues/archive/datahike-expected-rejections-log-full-writer-exceptions.md`;
the live MCP proof still wraps and duplicates the useful refusal inside a
JSON-string envelope, covered by
`docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`.

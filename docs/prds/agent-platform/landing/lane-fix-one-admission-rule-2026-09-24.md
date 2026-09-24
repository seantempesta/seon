---
type: landing
lane: fix-one-admission-rule
status: landed-unadopted
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/contracts-2026-09-24.md row 7
---

# Lane fix-one-admission-rule — one completeness rule for every entrance (2026-09-24)

What exists: `seon.schema.internal/assert-complete-schema!` is the completeness rule.
`seon.schema/register!` reaches it through `seon.schema.edn/admit` with a declared
`:seon.schema.admission/source`. The edit hook's `seon.schema.admission/admit` also
walked the authored EDN a second time, with its own polymorphism and `:fn` rules.
The fix deletes that second walk. For each changed declaration, admission now makes the
calls `register!` makes: `internal/assert-non-nilable-value-schema!`, then
`schema.edn/admit` with identity and source. The verdict comes from the same owner, so
the same text and source get the same answer at both entrances.

## Broken or unproven first

- **The hook's schema gate was dead in the live JVM before this fix.** Every
  `admission/admit` call through a real registry refused itself as
  `:schema-admission-failure`. The armed contract of `similar-identities` refused
  Malli's unqualified `:inst` key, which `resources/seon/schemas/malli.edn:1`
  registers. It also refused an unqualified candidate key. Live proof (default, archive
  ce7384682): `admit` on `my.fs.edn` → *"similar-identities refused existing at [572]
  … got a keyword :inst"*. `bin/test-check default --ns seon.schema.admission-gate-test`
  → run a237ed4c3c51 has 1 red, `house-rules-are-distinct-error-findings`, for the same
  cause. Both are fixed in admission.clj. The fix is proven only in a snapshot (below).
- **Not installed.** Default runs the committed archive `data/source/ce7384682…` with
  hook publication off, so the live verdict is still the old one. **RESET NEEDED**, or
  `start --head` after commit, then rerun
  `bin/test-check default --ns seon.schema.admission-gate-test`.
- **Guard removed for core EDN, pending the owner's ruling.** The deleted walker was the
  only thing that refused unexempted `:any`/`:some`/`:maybe` in packaged schema files
  (as `:schema-polymorphism`). It was also the only thing that warned on a `:fn` with no
  `:error/message`. Under the one rule, `:core` declarations get what activation gives
  them: compile, resolve, nilable-value, honest-generator/registered-predicate, and
  error-declaration checks. `:agent` declarations get the full agent rule: undefined
  slots, complete and pure predicates, nilable map values, and nilable returns. Whether
  core must meet the agent rule ("one experience") is the owner's question, and this lane
  does not decide it.
- **Out-of-scope finding (not fixed; files not mine).** With no handed projection,
  `schema.edn/admit` with an identity takes the bootstrap `assert-complete-contract!`
  path. That path refuses any declaration that reaches `:inst` as
  `::unresolved-reference`. The cause is `{:type :malli.core/invalid-schema, :schema
  :inst}`: `malli.edn` declares `:inst inst?`, and the bootstrap registry does not
  bind it. Example: re-admitting an edited `:seon.config/error` from `seon.config.edn`.
  `register!` inside an evaluation delta uses the projection path and admits it. The hook
  hands no projection, so a hook edit to such a declaration is falsely refused. The owners
  are `src/seon/schema/edn.clj` (`admit`) and `src/seon/schema.clj`
  (`assert-complete-contract!`). A second one: activation's
  `projection-registry` refuses `[:maybe X]` value schemas (`schema.clj:607`), but
  `projection-with-schema` does not. Only `register!`'s pre-check covers that path. The
  pre-check belongs inside `schema.edn/admit`, so neither entrance has to repeat it.
- The `seon.schema.admission-test` namespace shows 4 errors under plain
  `clojure.test` ("No declaration projection was handed"). HEAD shows the same 4, so they
  are unrelated to this diff.
- The data-oriented-clojure skill cites `assert-complete-schema!` at
  `internal.cljc:291`. It is now at :300. The skill is not mine.

## Change

- `src/seon/schema/admission.clj`:
  - Deletes `polymorphism-exemption`, `generator-properties`,
    `recorded-polymorphism-exemption?`, `polymorphic-form?` and the two
    polymorphism/`:fn`-message walkers.
  - `compilation-finding` (a full-population `schema.edn/admit`) becomes
    `registration-finding`: the per-identity `register!` sequence with the request's
    source. The source is `::source`; when absent it is `:core` for files and
    `:agent` for `::declarations`.
  - Only declarations whose form differs from the registered population are judged and
    advised. An unchanged file previously produced 223 advisory warnings; it now produces
    none.
  - Name overlap skips unqualified keys.
  - Refusals carry `:seon.schema/error` and type `:schema-incomplete-contract`.
- `src/seon/schema/internal.cljc`: the docstring names `assert-complete-schema!` as the
  one completeness rule and lists its entrances. Its rules are unchanged.
- `test/seon/schema/admission_gate_test.clj`:
  - Deletes the three tests of the deleted walkers (polymorphism exemption, predicate
    message, packaged predicate messages).
  - The malformed-declaration test now asserts the refusal names the declaration.
  - Adds the class regression `one-contract-text-gets-one-verdict-at-every-entrance`.
    It takes 7 declaration texts × {`:agent`, `:core`} and requires
    `schema/register!`, staged in a registration delta over the packaged projection, to
    give the same verdict as `admission/admit`.

Net lines: admission.clj +56/−71 (−15), internal.cljc +5, test +65/−61.

## Evidence (snapshot JVM = `git archive HEAD` + this diff, checkout `-Spath -A:test` classpath, contracts armed through `seon.test.arm/initialize-contracts!`, 1844 Vars)

| Probe | HEAD | HEAD + diff |
|---|---|---|
| `admit` on `seon.config.edn`, unchanged (3 runs) | crash `:schema-admission-failure`, 74–157 ms | no findings, 44–97 ms |
| `admit` on `my.fs.edn`, unchanged | crash, 117–130 ms | no findings, 44–48 ms |
| `seon.config.edn` + `[:maybe :string]` + a clean key (prospective source) | crash | 1 refusal `:seon.schema/nilable-value-schema` naming the key, 122 ms |
| agent declaration `[:fn {:gen/schema :int} int?]` | crash (before: warning only) | `:seon.schema/unproved-predicate-purity`, same as `register!`, 74–83 ms |
| agent `[:map [:k [:maybe :string]]]` | crash (before: `:schema-polymorphism`) | `:seon.schema/nilable-map-value`, same as `register!` |
| regression test on HEAD source | 15 failures | 0 failures, 1658 ms including the projection build |
| `seon.schema.admission-gate-test` | 1 fail (`:inst` crash) | 9 tests, 36 pass, 1452–1605 ms |
| `seon.schema.agent-nil-contract-test` | 371 pass | 371 pass |

Before the change-following filter, an unchanged-file admission took 2.4–6.3 s: every
declaration went down the per-identity path, and the advice covered unchanged keys. The
filter fixed this before commit. Arming the snapshot JVM takes about 4.9 s; that is
acquisition, not ordinary work. clj-kondo: 0 errors, 0 warnings on the changed files.

## Core contracts the one rule refuses for agents (not rewritten; owner ruling)

The scan covered every loaded `seon.*` function contract that round-trips as EDN (1716),
admitted through `schema/projection-with-function-contract` as `:agent` over the
packaged core projection. It took 10.0 s: one probe walk over the whole program, not an
ordinary operation. Outcomes:

- 1335 admitted
- 185 `:seon.schema/nilable-return`
- 112 `:seon.schema/undefined-contract`
- 10 `:seon.schema/unproved-predicate-purity`
- 4 `:seon.schema/nilable-map-value`
- 70 probe artefacts: render pairs whose partner function contract is absent from a
  schema-only projection

Of the 185 nilable returns, 72 are `[:maybe X]`, refused before as well. **113** are a bare
`[:or … :nil …]` return, which the fix-entry-schema lane reported as 111. Its load set
did not include every namespace this JVM loaded. The 113:

`seon.bootstrap/next-entry`, `seon.bootstrap/next-entry-in`, `seon.call-preparation/coherent-supplier`, `seon.call-preparation/contract-transaction`, `seon.call-preparation/plan`, `seon.call-preparation/plan-for`, `seon.cluster.message/agent-reference-id`, `seon.cluster.message/format-ai`, `seon.cluster.process/process-start-instant`, `seon.cluster.prompt/capture-mismatch`, `seon.cluster.source/commit-database`, `seon.cluster/accrete-schema-population!`, `seon.cluster/format-ai`, `seon.cluster/lookup-resolution`, `seon.cluster/mcp-effective`, `seon.cluster/populate-source!`, `seon.db/as-of-key`, `seon.db/changed-read-attributes`, `seon.db/commit-id`, `seon.db/committed-value-identity`, `seon.db/entity`, `seon.db/foreign-connection-error`, `seon.db/index-evidence-current`, `seon.db/lookup-ref-error`, `seon.db/malformed-query-pattern-error`, `seon.db/missing-pull-selector-error`, `seon.db/missing-transaction-data-error`, `seon.db/pull-call`, `seon.db/removed-definition-error`, `seon.db/source-argument-error`, `seon.db/validate-pulled-result`, `seon.db/write-agent-retraction-error`, `seon.db/write-render-target-error`, `seon.db/write-report-error`, `seon.error.refusal/frame-function`, `seon.error.refusal/refusal`, `seon.error.refusal/root-frame`, `seon.error/admitted-size`, `seon.error/collection-member-problem`, `seon.error/contract-violation-data`, `seon.error/evidence-text`, `seon.error/faults-form`, `seon.error/offending-entry`, `seon.error/read-target-group`, `seon.error/reader-correction`, `seon.error/refusal`, `seon.error/refusal-data`, `seon.error/refusal-text`, `seon.error/stack-failing-function`, `seon.error/steward`, `seon.error/throwable`, `seon.fault/carried-receipt`, `seon.flow/join-graph!`, `seon.flow/stop-error-fanout!`, `seon.flow/stop-work-launcher!`, `seon.fn/incremental-projection`, `seon.fn/reconciled-paths`, `seon.instrument/caller-frame`, `seon.instrument/predicate-callable`, `seon.instrument/supplied-entry-problems`, `seon.instrument/supplied-projection`, `seon.issue/adopt!`, `seon.issue/adopt-selected!`, `seon.plan/agent-eid`, `seon.plan/agent-plan-pull`, `seon.plan/changed-item`, `seon.plan/plan-eid`, `seon.plan/ref-eid`, `seon.plan/step-eid`, `seon.plan/subject-eid`, `seon.profile/explain-slow`, `seon.render.transcript/bootstrap-task-message-eid`, `seon.render.transcript/ledger-evaluations`, `seon.render.transcript/render-captured-prefix`, `seon.render.transcript/reply-intent`, `seon.render.value/prepare`, `seon.render.walk/distance-cap-unit`, `seon.render.web/agent-namespace`, `seon.render.web/current-cluster-name`, `seon.render.web/graph-model`, `seon.render/custody-cluster-name`, `seon.render/declared-producer`, `seon.render/invocation-unknown`, `seon.render/namespace-owner`, `seon.render/render-ai`, `seon.render/render-call`, `seon.render/render-html`, `seon.render/render-program-evidence`, `seon.render/schema-producer`, `seon.schedule/latest-nominal-at-or-before`, `seon.schedule/next-nominal-after`, `seon.schema/reverse-target-schema`, `seon.schema/selector-target-schema`, `seon.schema/unresolved-reference-in`, `seon.sci.eval/base-ctx-key`, `seon.sci.eval/commit-id-of`, `seon.sci.eval/installation-covers-program-change?`, `seon.sci.eval/program-identity`, `seon.sci.kernel/context-projection`, `seon.sci.kernel/current-arm`, `seon.sci.kernel/deadline-remaining-ms`, `seon.sci.kernel/program-function`, `seon.sci.kernel/program-namespace`, `seon.shell.jvm/task-result`, `seon.test.runner/program-revisions`, `seon.test.runner/reach-revisions`, `seon.turn/current-run`, `seon.turn/disposition-rule-error`, `seon.turn/generated-read-fault`, `seon.turn/max-episode-runs`, `seon.turn/next-agent-work`, `seon.turn/record-attempt!`, `seon.turn/repaired-span`.

## Commits

See `git log -- src/seon/schema/admission.clj` (lane fix-one-admission-rule). Released
paths: `src/seon/schema/admission.clj`, `src/seon/schema/internal.cljc`,
`test/seon/schema/admission_gate_test.clj`, and this note.

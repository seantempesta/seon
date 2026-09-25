---
type: research
status: draft inventory — nothing ingested; ids recompute if an aspect changes
created: 2026-09-25
scope: every actionable item in open-items.md, the plan README §4 open steps, docs/seon/issues and language requirements §6, proposed as task entries for the first migration after task ingestion (spec 7.11, 7.12, 7.15)
---

# Task migration inventory — 2026-09-25

Lane inventory-plan-tasks-opus, read-only apart from this note and `tmp/task-inventory/draft-tasks.edn`, at HEAD
`68ff27ce5`. The draft EDN holds 470 task entries (actionable plus vague), grouped by owning namespace. Stale,
obsolete and duplicate items are listed only here.

## Broken or unproven first

- **A digit-led id cannot be a keyword, so the spelling of the literal token is undecided.** `seon.id/id` returns
  12 lowercase hex characters, and 324 of the 522 ids below begin with a digit. Probed on default (pid 35446):
  `(read-string ":seon.task/9a05cf9b8e18")`, `clojure.edn/read-string` and `edamame.core/parse-string` all refuse
  it ("Invalid token" / "Invalid keyword"). The landed `:seon.task/tasks` is `[:set :qualified-keyword]`
  (`resources/seon/schemas/seon.task.edn`), so these ids cannot be written in `:seon/tasks` as keywords. Slice 1b
  must choose one spelling for task EDN, `:seon/tasks` and rendered context (spec 1.9, 3b.6). There are two
  candidates: the id string exactly as `seon.id/id` returns it (`:seon/tasks #{"9a05cf9b8e18"}`, one exact grep),
  or a letter-led handle as `seon.id/symbol-in` already does for evaluations. This draft stores the string. The
  finding lives in this note; it is outside this lane's scope, and `docs/seon/issues/` is not editable here.
- **Tier b subjects are cited, not verified.** 85 tier-a rows were read in source: subject, `file:line`, and fix
  commit or proof. The 437 tier-b rows come from one scripted pass over the notes. Their subjects are the live
  first-party definitions each note cites in backticks, their owning namespace is the most-cited source
  namespace, and their intent is the note's title. They are *symptom locations until a triage task reads the
  producer*. Four tier-a corrections found by reading show how often that differs:
  - `mcp-value-window-inherits-the-agent-token-budget` is produced by `seon.cluster/mcp-project`, not the MCP
    script;
  - `captured-prompt-history-is-disabled` was already fixed in `79c106925`;
  - `settle-is-public-without-a-complete-contract`'s namespace was deleted;
  - `the-indexer-resolves-its-declaration-world-per-file-and-per-row` carries its own "Resolved 2026-09-16"
    section.
- **The spec's §6 defect list is itself partly stale.** Five of its 13 bullets are already fixed at HEAD:
  - `8a531008a` fixed the definition authority and the uncontracted redefinition;
  - `dcd76fc21` fixed `:nil` in agent contracts and the core-contract re-entry;
  - `0dce6e31c` fixed the branch SCI aliases.

  7.12 says these are the first tasks migrated, so the spec owner should correct §6 in place.
- **33 notes under `docs/seon/issues/` are not `open`**, yet sit outside `archive/`. `seon.issue` admits only
  `open`, `resolved` and `superseded` (`src/seon/issue.clj:374`, `:invalid-status`), so several of them refuse at
  indexing. They are listed below for the issue owner to archive.
- **No proof was run.** Every named proof was verified by reading (the deftest exists and exercises the subject),
  not by `bin/test-check`, per the load rule. The ids were computed in one read-only JVM evaluation (522 aspects,
  20 ms).

## Identity used

Aspect = `[owning-ns name subject-kind]`. The owning namespace is the subject producer's namespace, the name is
a readable kebab string, and the kind is one of `:function`, `:namespace`, `:schema`, `:test`, `:tasks` (a
decomposition task whose deliverable is tasks, 2.9) or `:triage`. The id is `(seon.id/id aspect)`. Intent,
needs, evidence and subjects are content, never identity inputs (2.5, 3b.6). A migrated issue's name is its
note slug, so the old path stays its historical association (3b.6, 7.15).

The seed `:seon.fn.task/tests-reaching-saturates` in `resources/seon/tasks/seon.fn.edn` re-keys to
`300aca1ad57c`, from `[seon.fn "tests-reaching-saturates" :function]`.

Draft entries carry `:seon.task/name`, `:seon.task/aspect` and `:seon.task/needs`. None of these is in the closed
`:seon.task/entry` of `1de64b45b`. Spec 1.2 and 3b.6 require the name and the aspect, and needs is authored
content (5b.2). Every `:seon.task.draft/*` key is inventory evidence to strip before ingestion.

## Counts per source

| source | items | actionable | duplicate | stale | obsolete | vague |
|---|---|---|---|---|---|---|
| language requirements §6 (13 bullets; bullet 8 splits in two) | 14 | 7 | 1 (bullet 11 is bullet 4); bullets 6, 7 and 8b also merge with an open-items finding, the seed and an issue note | 4 rows covering bullets 1, 4, 12, 13 | 0 | 2 |
| open-items.md | 56 | 34 | 12 (items that are open issue notes, table below) | 3 | 3 | 4 |
| plan README §4 open steps | 23 | 1 (derived-contract decomposition) | overlaps kept as needs or noted: `projection-passed-explicitly` with `fc25260a871b`, `one-test-harness` with `test-redefinitions-of-default-vars`, `cheap-environments-and-collaboration` needs `62434adb50af` | 0 | 0 | 22 (plan steps need a decomposition task, 2.9) |
| docs/seon/issues (481 notes; README/index excluded) | 481 | 5 verified + 328 cited-unverified | 11 merged into tier a + 1 within issues | 2 verified + 6 in-note + 33 non-`open` status | 1 verified + 32 candidates (31 whose every cited definition is absent at HEAD, 1 by reading) | 70 (no live definition or source file cited) |

Stale and obsolete candidates are not ingested. The 32 "every cited definition absent" rows are candidates: a
renamed definition would look the same, so the issue owner confirms each one before archiving it.

## Proposed tasks, tier a (read in source)

**my.program**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `bdc132dc3601` | native-ns-unmap-bypasses-the-deletion-authority **(batch)** | actionable | `my.program/native-call-refusal` | `my.program-mutation-test/native-program-mutations-refuse-at-the-installed-hook` |

**seon.blob**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `9b16848cd6dc` | interrupted-staging-leaves-no-artifact **(batch)** | actionable | `seon.blob/stage!`, `seon.blob/stage-binary!` | `seon.blob-test/interrupted-staging-leaves-the-store-unchanged` |

**seon.cluster**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `3eea9d2e5c9f` | changed-path-publication-reads-every-schema-resource | actionable | `seon.cluster/full-source-refresh!` |  |
| `b21b5249b19c` | mcp-value-window-inherits-agent-budget **(batch)** | actionable | `seon.cluster/mcp-project` | `seon.cluster.mcp-test/nested-bulk-is-bounded-by-the-shared-value-window` |
| `0d9a80e83918` | publication-half-applies-on-a-red-save-gate | actionable | `seon.cluster` |  |
| `5d5af9b94507` | reserve-cluster-dead-family | obsolete |  |  |

**seon.cluster.agent**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `7fb495b9c18f` | acquire-context-holds-the-contexts-monitor | actionable | `seon.cluster.agent/acquire-context!` |  |
| `aded32b7d587` | creation-tx-refuses-fixture-branches | actionable | `seon.cluster.agent/creation-tx` |  |
| `08b04586a245` | issue-settlement-test-stops-on-isolation-refusal | vague |  |  |

**seon.cluster.boot**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `776a0901a48b` | start-runs-a-program-that-differs-from-the-checkout | actionable | `seon.cluster.boot` |  |

**seon.cluster.loop**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `51be0e13722b` | settle-is-public-without-a-contract | obsolete |  |  |

**seon.cluster.prompt**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `0eeeaf250ac1` | root-idle-prompt-takes-five-seconds | actionable | `seon.schema/canonical-value-string` |  |

**seon.cluster.source**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `97cb2099af3a` | b1-remaining-commits | vague |  |  |
| `2f295d4ea9b9` | cheap-environments-and-collaboration | vague |  |  |
| `62434adb50af` | merge-refuses-added-definitions | actionable | `seon.cluster.source/prepare-merge!` |  |
| `a9b91bfce2e0` | publication-envelope-dissolved | vague |  |  |
| `14c9974e639d` | reference-code-gitlinks-demand-a-jvm-restart **(batch)** | actionable | `seon.cluster.source/classify-paths` | `seon.cluster.publication-inputs-test/publication-classifies-configuration-and-loaded-dependencies` |
| `af603276bc87` | write-back-of-accepted-rows | vague |  |  |

**seon.cluster.store**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `287fa9f260f8` | storage-retention-track | vague |  |  |

**seon.context**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `b66b5d05f782` | captured-prompt-history-disabled | stale |  | `seon.context-capture-history-test/retracted-capture-retains-the-exact-prompt-in-history` |

**seon.db**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `d37544640b35` | a2-remaining-commits | vague |  |  |
| `e54c81ba2ece` | arity-check-rebuilds-whole-program-input-per-branch | actionable | `seon.db/arity-content-key` |  |
| `2606a3d8c3dc` | as-of-reads-rebuild-their-projection | actionable | `seon.db` |  |
| `292b26e2455c` | datahike-fork-gaps | vague |  |  |
| `7440d6522748` | retention-refusal-lacks-error-at | stale |  |  |
| `c71f69508ab3` | stamp-and-hand-cache-deletions | vague |  |  |
| `4057bc69c686` | writer-selects-entity-schemas-by-required-identity | actionable | `seon.db/write-entity-schemas` |  |

**seon.dev.markdown**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `97e1584a6944` | pin-check-reads-dated-landings-as-living **(batch)** | actionable | `seon.dev.markdown/run-rules`, `seon.dev.markdown/validate-repository-pins` | `seon.dev.markdown-test/repository-dependency-pins-are-current-test` |

**seon.dev.mcp**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `870fffb9f70b` | mcp-entrance-acquires-branches-without-an-agent | actionable | `seon.dev.mcp` |  |
| `bb280604ad6e` | mcp-eval-echoes-every-kondo-stderr-line | actionable | `seon.dev.mcp` |  |
| `cb4a295857eb` | mcp-jvm-eval-loses-reader-fidelity | actionable | `seon.dev.mcp` |  |
| `692ad2dd692e` | new-mcp-branch-lists-inherited-errors | actionable | `seon.dev.mcp/branch-errors!` |  |
| `2f9fc637d649` | stale-mcp-server-runs-the-old-evaluation-path | vague |  |  |

**seon.env**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `1aa87aba9996` | supplied-agent-id-refusal-carries-the-whole-environment **(batch)** | actionable | `seon.env/supplied-agent-id` |  |

**seon.error**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `8eaeb939edaa` | capped-faults-recorded-without-a-blob | actionable | `seon.error/commit-tx` |  |
| `2836d045bafe` | resolved-shown-after-recurrence | vague |  |  |

**seon.error.refusal**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `baf982fa595b` | refusals-repeat-their-location-trees | vague |  |  |

**seon.fault**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `7ca365eee2de` | member-branch-faults-are-not-test-errors | actionable | `seon.fault/record!`, `seon.test` |  |

**seon.flow**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `1453e7e596c1` | committer-graph-deletion | vague |  |  |
| `fac4a3c135e0` | flow-owns-running-machinery | vague |  |  |

**seon.fn**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `e21685e7b139` | contracts-derive-from-destructured-keys-and-return-tags | actionable | `seon.fn` |  |
| `079fd5cfd9db` | function-contract-is-a-printed-string | actionable | `seon.fn/var-row`, `seon.sci.eval/install-function-contract!`, `seon.sci.reader/function-declaration` |  |
| `0b8fa3dd143e` | indexer-resolves-declaration-world-per-row | stale |  | `seon.fn-test/indexing-resolves-its-declaration-world-once-per-operation` |
| `300aca1ad57c` | tests-reaching-saturates **(batch)** | actionable | `seon.fn/gate-set`, `seon.fn/tests-reaching` |  |

**seon.fn.analyzer**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `330dce00c141` | kondo-dependency-cache-empty-after-restart | actionable | `seon.fn.analyzer/analyze-dependencies!` |  |

**seon.fn.schema-shape**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `edbef0c85809` | schema-shape-family-leaves | vague |  |  |

**seon.id**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `6f49746fbb6a` | hasch-identity-switch | vague |  |  |

**seon.instrument**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `fbfdeaaeadbd` | script-and-dev-namespaces-are-never-armed | actionable | `seon.instrument` |  |
| `21d5206451fc` | wrappers-read-the-retained-contract | vague |  |  |

**seon.issue**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `882d8613930b` | b3-task-family-s3-s7 | vague |  |  |

**seon.maintenance**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `ef4b045c0521` | cleanup-result-projection-has-no-producer **(batch)** | actionable | `seon.maintenance/project-cluster-cleanup-result` | `seon.maintenance-schema-test` |
| `a87f1435f925` | maintenance-opens-a-store-outside-the-refcount | actionable | `seon.maintenance/with-root-store!` |  |

**seon.operator**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `5972b7b2c5b5` | script-subprocess-waits-declare-no-bound **(batch)** | actionable | `seon.dev.citations`, `seon.dev.dependency-digest`, `seon.operator/connected!` | `seon.bounded-boundary-census-test/every-foreign-subprocess-boundary-declares-a-bound` |

**seon.profile**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `24c60947bbca` | c1-remaining-slices | vague |  |  |

**seon.program**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `a060468a26a0` | declaration-row-output-refusal-names-the-wrong-member | actionable | `seon.program/declaration-row` |  |
| `7b13b64d73b6` | one-happy-path-deletions | vague |  |  |

**seon.render**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `2aec28c848e8` | agent-context-rendering | vague |  |  |
| `b0f3952b31fe` | one-render-path-slices | vague |  |  |
| `b7aeefb9b45d` | update-the-plan-artifact | obsolete |  |  |

**seon.render.web**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `281db700d724` | opaque-generators-share-live-objects **(batch)** | actionable | `seon.render.web/mult-generator`, `seon.render.web/server-generator`, `seon.sci.eval/ctx-generator` | `seon.public-contract-test/lifecycle-generators-make-a-fresh-sample-each-generation` |

**seon.schema**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `22e85ffcf5bf` | agents-can-validate-a-value-against-a-key | actionable | `seon.schema`, `seon.schema/explain (absent)`, `seon.schema/valid? (absent)` |  |
| `a0edc46ee6a3` | datahike-parity-baseline-is-hand-maintained | actionable | `test/seon/schema/datahike_parity.edn` |  |
| `cc8f897d9d26` | dependent-shape-fingerprints-stale | stale |  | `seon.call-preparation-test/the-shipped-database-suppliers-are-coherent-in-the-executing-program` |
| `ef21da77d79e` | error-members-reuse-another-entitys-identity | actionable | `:seon.context/selection-agent-id`, `:seon.message/unknown-recipient`, `:seon.test.runner/default-cluster-refused`, `:seon.test.runner/long-test-ns-hook`, `:seon.turn/compaction-agent-id` |  |
| `02dc23245db2` | orphan-development-helpers-banner | stale |  |  |
| `5a4cb0c694ab` | projection-passed-explicitly | vague |  |  |
| `7c6c87b319f1` | schema-declaration-cannot-reach-a-branch | actionable | `seon.schema/register!` |  |

**seon.schema.admission**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `5aa2550e6b69` | restore-core-any-some-guard | obsolete |  |  |

**seon.schema.internal**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `5c12a2f8fdf4` | agent-contracts-refuse-nil | stale | `seon.schema.internal/contract-error!` | `seon.schema.agent-nil-contract-test/agent-contracts-admit-nil-and-refuse-nilable-unions-alike` |

**seon.sci.eval**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `069f1ee0a72e` | branch-sci-namespace-lacks-file-aliases | stale |  | `seon.sci.entry-gate-test/every-program-namespace-carries-its-stored-aliases` |
| `9a05cf9b8e18` | evaluation-defines-outside-the-entry-gate | stale | `seon.sci.eval/refuse-uncontracted-definitions!` | `seon.sci.entry-gate-test/every-definition-a-form-makes-is-gated-and-armed` |
| `2034426bf3bc` | uncontracted-redefinition-drops-the-contract | stale | `seon.sci.eval/refuse-uncontracted-definitions!` | `seon.sci.entry-gate-test/a-definition-without-a-contract-is-refused-and-restored` |

**seon.test**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `80a093412a7f` | a-seed-redefining-its-test-runs-the-old-var | actionable | `seon.test` |  |
| `5d1ce861c450` | b4-remaining-commits | vague |  |  |
| `eab0fb04d4fc` | failure-text-prints-an-unfollowable-blob-hint | actionable | `seon.test/failure-text` |  |
| `b048e6bd800b` | one-test-harness | vague |  |  |
| `474ae4c203dd` | refused-members-count-red-not-unknown | actionable | `seon.test` |  |
| `e15dd8259040` | test-bound-covers-only-the-body | actionable | `seon.test` |  |
| `0b809482894b` | test-redefinitions-of-default-vars | vague |  |  |
| `ab41896336ca` | tests-run-as-agents-run | vague |  |  |

**seon.test.accretion**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `2b3b45973e8e` | generatable-dominates-a-hub-schema-edit | actionable | `seon.test.accretion/generatable?` |  |

**seon.test.runner**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `f9fadea8c1b6` | reach-leaves-omit-fixture-metadata | actionable | `seon.test.runner` |  |
| `6c2e42bf883c` | runner-failure-report-prints-whole-values | actionable | `seon.test.runner/failure-identity`, `seon.test.runner/failure-report` |  |

**seon.turn**

| id | name | verdict | subjects | proof |
|---|---|---|---|---|
| `b14ee3207d68` | cut-4-turn-context-render | vague |  |  |
| `ad1005fac9fd` | latest-answering-turn-t-passes-a-refusal-as-an-int | actionable | `seon.turn/latest-answering-turn-t` |  |

## Proposed tasks, tier b (scripted from the issue notes; subjects cited, unverified)

Intent is the note's title; `must-read` is the same cited set. A triage pass should replace each subject with the
producer it names in its "Cause" or "Owner" section before annotating code.

**my.edit** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `c67a2ba68972` | an-agent-cannot-make-its-own-source-edit-live | `my.edit/exact!`, `my.edit/form!`, `my.test/check` |  |

**my.fs** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `4606d3bc91dc` | my-fs-write-docstring-hides-its-own-request-shape | `my.fs/read` |  |

**my.program** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `7ee7090c78f1` | my-program-native-evaluation-adds-a-second-sci-owner | `my.program/native!` |  |
| `3a6072ec0e83` | program-namespace-retraction-needs-shared-schema-writer | `my.program/remove-ns!`, `seon.schema/unregister!` |  |
| `57b4646559d4` | test-fast-path-snapshots-are-misclassified-as-full-gates | `my.program`, `seon.issue` |  |

**my.shell** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `3ef120813d1b` | shell-timeout-refusal-is-not-admitted-by-run-contract | `my.shell/run!` |  |

**my.test** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `fb6afc644eeb` | test-check-defaults-an-omitted-cluster | `my.test/check`, `seon.env/supplied-agent-id`, `seon.instrument/apply!` |  |

**seon.agent** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `c5c92d636ae0` | default-eligibility-matched-differently-named-schema-aliases | `seon.agent/archived?`, `seon.agent/effective-settings`, `seon.agent/identity`, `seon.agent/open?`, `seon.agent/settings`, `seon.cluster.message/send` | `seon.sci.supplied-database-test/own-record-reads-supply-the-same-database-and-agent-as-explicit-calls` |

**seon.ai** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `9b0a82518b6f` | provider-output-token-wire-key-is-hard-coded | `seon.ai` |  |
| `58360df0c1db` | thinking-tool-continuations-have-no-faithful-request-shape | `seon.ai` |  |
| `6d09140c5db8` | turn-consumer-fixtures-read-retired-result-storage | `seon.ai/complete`, `seon.bootstrap/next-entry`, `seon.config/apply!`, `seon.sci.eval/evaluate` | `seon.fn-test/settled-form-records-calls-across-every-program-namespace` |

**seon.await** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `48e33ed0f688` | orderly-stop-completion-joins-have-no-bound | `seon.await/await!` |  |
| `e140948b8889` | test-check-classifies-completed-errors-as-expiry | `seon.await/await!` |  |

**seon.blob** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `14451063beb7` | blob-get-assumes-file-store-callback-shape | `seon.blob/get` |  |

**seon.bootstrap** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `ca56fafa7935` | generation-dependency-analysis-ignores-keywords | `seon.bootstrap` |  |

**seon.call-preparation** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `6d8ee3238799` | one-cluster-holds-two-plus-n-datahike-listeners | `seon.call-preparation`, `seon.cluster.wake`, `seon.schedule` |  |

**seon.cluster** (19)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0db781dd2d7a` | a-db-adoption-refuses-while-another-lane-edits-a-dependent | `seon.cluster.boot/request!`, `seon.cluster.source/publish!`, `seon.cluster/full-source-refresh!`, `seon.cluster/refresh-source!`, `seon.db/with-declarations`, `seon.fn/analyze-rows` |  |
| `3e366f58a8df` | a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently | `seon.cluster`, `seon.cluster.agent` |  |
| `b553a4ae87f4` | a-missing-required-dial-kills-every-io-prepl-connection | `seon.cluster/mcp-io-prepl` |  |
| `15722e62999b` | adoption-cannot-name-a-deleted-namespace | `seon.cluster/full-source-refresh!`, `seon.fn/source-context` |  |
| `6834cf683607` | adoption-refuses-a-publication-that-changes-no-definition | `seon.cluster/development-source-refresh!` |  |
| `a96dc937da80` | an-aborted-publication-leaves-no-record | `seon.cluster/report-source-progress!`, `seon.fn/commit-index-phase!` |  |
| `0bd8e78d94be` | an-unfinished-test-run-hides-every-problem-family | `seon.cluster/mcp-runtime-observation`, `seon.cluster/recover-runs!`, `seon.problems/problems`, `seon.test.runner/record-interrupted!`, `seon.test/run` |  |
| `505009f642da` | canonical-fixture-population-missing-carried-projection | `seon.cluster/accrete-schema-population!`, `seon.db/transact!` |  |
| `1434fab1c58a` | cluster-named-store-collides-with-the-store-directory | `seon.cluster` |  |
| `a0bfac8cad0e` | cold-publication-returns-a-blank-commit-without-publishing | `seon.cluster/operator-root` |  |
| `0bfc14f89c13` | context-wave-leaves-three-small-honesty-defects | `seon.cluster`, `seon.fn`, `seon.render.ns` |  |
| `bfe7f6094e75` | development-adoption-can-mix-host-and-sci-generations | `seon.cluster`, `seon.cluster.agent`, `seon.sci.eval`, `seon.sci.kernel` |  |
| `484a13c3fcad` | development-adoption-drops-the-web-server | `seon.cluster/refresh-source!` |  |
| `4b2cf1cacd22` | development-adoption-window-loses-newer-issue-and-test-facts | `seon.cluster`, `seon.issue` |  |
| `3685b498ab25` | development-adoption-writes-program-rows-seconds-before-its-record | `seon.cluster/development-source-refresh!`, `seon.fn/index!`, `seon.issue/adopt!` |  |
| `6a2cf4480a1b` | incremental-publication-cannot-select-the-live-operator | `seon.cluster/refresh-source!` |  |
| `51b8bf8c5856` | operator-root-inference-guesses-from-directory-names | `seon.cluster` |  |
| `476200684965` | search-index-property-collides-with-process-index-id | `seon.cluster` |  |
| `aa9fe594900f` | the-publication-export-does-not-identify-the-exported-program | `seon.cluster.source/database`, `seon.cluster/refused!` |  |

**seon.cluster.agent** (11)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `dee2fb0488e3` | a-blocking-realization-is-not-bounded-by-the-interrupt | `seon.cluster.agent/submit-source!` |  |
| `a149b0d5a548` | a-committed-database-holds-its-fused-roots-only-softly | `seon.cluster.agent/turn-step`, `seon.maintenance/collect!` |  |
| `4a97fc722ba1` | a-parked-turn-proc-pings-unknown-on-a-healthy-agent | `seon.cluster.agent` |  |
| `0d43d389df96` | a-turn-that-dies-before-replying-still-answers-its-wakes | `seon.cluster.agent/submit-source!` |  |
| `b4e911f2bf6d` | agent-arm-and-disarm-hold-one-monitor-across-seconds-of-work | `seon.cluster.agent` |  |
| `cdf0276119da` | an-unrelated-fixture-transaction-mints-a-half-agent | `seon.cluster.agent` |  |
| `dd5bf02fcaea` | class-readerless-duplicate-mechanisms-survive-cuts | `seon.cluster.agent/render-identity-html` |  |
| `660e0eda9655` | namespace-responsibility-and-issue-workers-are-singular | `seon.cluster.agent/steward-call` |  |
| `def653e7aab4` | observable-graph-transitions-are-polled-in-tests | `seon.cluster.agent/arm!` |  |
| `a652573f1eee` | unowned-namespace-oversight-still-inverts-assignment | `seon.cluster.agent/steward-of` |  |
| `ead66c5d0080` | warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor | `seon.cluster.agent/arm!`, `seon.cluster.boot/stand-cluster-runtime!`, `seon.cluster/arm-agents!`, `seon.error/commit-call` |  |

**seon.cluster.boot** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `fd33c3a7c2dc` | from-zero-boot-takes-minutes | `seon.cluster.agent/arm!`, `seon.cluster.boot/start!`, `seon.operator/nuke!` |  |
| `ebe2cb360ed3` | test-fast-runs-on-a-published-base-older-than-heads-schema-validator | `seon.cluster.boot/start!` |  |

**seon.cluster.instruction** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `b249398e0a27` | opening-does-not-explain-per-line-comment-markers | `seon.cluster.instruction` |  |
| `085c81abc5d2` | production-docstrings-teach-deleted-semantics | `my.message`, `seon.cluster.instruction`, `seon.cluster.reply`, `seon.context`, `seon.flow`, `seon.reconcile`, `seon.sci.eval` |  |

**seon.cluster.message** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `ef28fed00730` | documentation-fixture-omits-interpreted-contract-configuration | `my.message/send`, `seon.cluster.message/send!` |  |
| `31d556c88ca7` | message-completion-replies-from-the-wrong-agent-and-duplicates-the-trigger | `seon.cluster.message/reply` | `seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` |

**seon.cluster.process** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `d30208fb49c3` | subprocess-descendants-die-of-sigpipe-after-the-root-exits | `seon.cluster.process/run-process!` |  |

**seon.cluster.prompt** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `48bfc469d721` | a-selected-prompt-no-longer-reconstructs-from-the-full-join | `seon.cluster.prompt/select` |  |
| `5be7f606ac17` | root-turns-on-a-fresh-dev-cluster-hit-the-context-acquisition-backstop | `seon.cluster.prompt` |  |

**seon.cluster.registry** (5)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `207c3659bc8b` | blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact | `seon.cluster.registry/blob-digest-attributes` |  |
| `124e032a7576` | class-destructive-reachability-changes-are-not-atomic | `seon.blob`, `seon.cluster.registry`, `seon.operator` |  |
| `8040f64b8635` | give-offline-roster-discovery-a-current-read-only-helper | `seon.cluster.registry/roster` |  |
| `ec2cbc09bc4f` | shared-store-grows-during-render-source-work | `seon.cluster.registry/collect!` |  |
| `17bfb4ced8b3` | storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing | `seon.cluster.registry/collect!` |  |

**seon.cluster.reply** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `d50ac1129847` | inline-form-in-reply-prose-becomes-an-evaluation | `seon.cluster.reply` |  |
| `e3772f714fd9` | reply-sources-return-contract-disagrees-with-vector-results | `seon.cluster.reply/sources`, `seon.schema.internal/assert-error-declaration!`, `seon.sci.reader/error-value` |  |

**seon.cluster.source** (7)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `39d256b538a1` | a-deleted-source-file-is-not-a-publication-difference | `seon.cluster.source/deleted-identities` |  |
| `562394fa7052` | an-explicit-collection-sweeps-the-clusters-adopted-source-commit | `seon.cluster.source/refuse!`, `seon.sci.eval/loaded-program` |  |
| `49f6f5b5ab76` | full-publication-tests-exceed-liveness-while-compiling-the-commit-projection | `seon.call-preparation/incoherent`, `seon.cluster.source/database`, `seon.cluster.source/publish!`, `seon.db/arity-mismatches-with`, `seon.db/supplied-connection`, `seon.issue/index-tx` |  |
| `66a84435c0e7` | publication-issue-indexing-refuses-in-test-runner-fixtures | `seon.cluster.source/index-issues!`, `seon.cluster.source/refuse!` |  |
| `efb2c92ae2be` | rebuilt-test-evidence-refers-to-another-transaction-history | `seon.cluster`, `seon.cluster.source`, `seon.test.runner` |  |
| `d4b1b9ae3441` | retiring-a-contract-referenced-schema-member-refuses-as-raw-malli | `seon.cluster.source/publish!`, `seon.db/transact!`, `seon.schema/refuse-unresolved-reference!` | `seon.schema-test/an-undeclared-reference-refuses-as-the-declared-schema-refusal`, `seon.schema-test/retiring-a-referenced-member-names-every-referrer` |
| `b5f4b5b41321` | test-results-persistence-can-time-out-during-development-adoption | `seon.cluster.source/record-results!` |  |

**seon.cluster.store** (5)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `1aa947d984c0` | class-mutable-resources-lack-explicit-root-and-lifetime | `seon.artifact`, `seon.cluster.store`, `seon.operator` |  |
| `6162b9010743` | file-store-commits-pay-five-times-the-fsyncs-they-need | `seon.cluster.store/datahike-configuration` |  |
| `9493337cd16b` | keep-history-is-on-by-default-without-a-decision | `seon.cluster.store/datahike-configuration` |  |
| `84032024d83d` | reset-refork-refuses-held-elsewhere-without-naming-the-holder | `seon.cluster.store/open-store!` |  |
| `ae59274c7f4e` | seon-db-has-no-branch-or-commit-reads | `seon.cluster.store/open-branch!` |  |

**seon.cluster.wake** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `62a596cd3d32` | retracting-a-listened-entity-broadens-the-pattern | `seon.cluster.wake` |  |
| `edb519318838` | the-wake-router-queries-on-the-writer-thread-and-drops-its-own-fault | `seon.cluster.wake` |  |

**seon.config** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `32fcbcce9f91` | a-database-reads-error-value-is-read-as-a-row-by-its-caller | `seon.config/effective`, `seon.config/effective-in`, `seon.config/population-transaction-data`, `seon.db/pull`, `seon.fs.jvm/glob`, `seon.test.runner/record-tx` | `seon.instrument-test/the-selection-is-declared-vars-with-schemas-and-nothing-else` |

**seon.context** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `388c4cc7b45a` | context-capture-does-not-carry-error-recording-custody | `seon.context/contribution-row`, `seon.error/recording` |  |
| `7356c4e2dd72` | eval-samples-cost-42mb-of-store-each | `seon.context/capture-tx` |  |

**seon.db** (36)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `d1d8655640f0` | a-stored-entity-schema-requires-a-cardinality-many-key-that-empty-cannot-satisfy | `seon.db/write-entity-value` |  |
| `7c6c705ebf1c` | a-turn-spends-seconds-before-its-provider-call | `seon.ai/complete`, `seon.cluster.agent/acquire-context!`, `seon.cluster.agent/interrupt-turn!`, `seon.db/carried-projection`, `seon.db/with-declarations`, `seon.turn/open-turn` |  |
| `ffd011b2ba67` | a-wildcard-pull-is-still-cut-at-one-thousand-members | `seon.db/entity` |  |
| `2bbf3513776a` | an-as-of-view-older-than-a-tightened-schema-rule-is-unreadable | `seon.db/carried-projection`, `seon.db/q`, `seon.error/occurred-since` |  |
| `e64aeb74be72` | blocked-plan-values-refuse-pull-during-ai-projection | `seon.db/pull` |  |
| `4e79ce950a93` | bound-pull-selector-evidence-retains-all-attributes | `seon.db/query-index-patterns`, `seon.db/read-evidence`, `seon.render.ns/render-ai`, `seon.sci.eval/directory-value` |  |
| `294aa30d86ef` | call-graph-fidelity-selection-awaits-adopted-proof | `my.agent/identity`, `seon.db/pull-many`, `seon.db/transact!`, `seon.db/write-render-target-error`, `seon.fn.analyzer/attributed-usages`, `seon.render.test/render-html` |  |
| `2b14e6fed73c` | cold-reach-derivation-exceeds-one-second | `seon.db/q`, `seon.db/query-binding-values`, `seon.db/with-declarations`, `seon.id/digest`, `seon.test.runner/reach-digests`, `seon.test.runner/reach-facts` |  |
| `5c5f03960873` | collection-attribute-query-bindings-leak-edn-storage-values | `seon.db/q`, `seon.schema/canonical-schema-rows` |  |
| `038434550466` | compiled-guard-error-functions-refuse-error-observation | `seon.db/q`, `seon.db/query-guard-message`, `seon.fn.schema-shape/normalized-form`, `seon.instrument/bind-contract-predicates`, `seon.schema/canonical-definition` |  |
| `9cd202e0b733` | db-diff-render-bypasses-print-fit-and-has-no-html | `seon.db/diff`, `seon.print/fit` |  |
| `e7e602675f19` | db-test-still-expects-a-unique-agent-namespace | `seon.db` | `seon.db-test/unique-rejection-names-the-existing-owner-as-data` |
| `86367a52718d` | default-pull-unknown-attribute-omits-candidates | `seon.db/pull`, `seon.schema/call-with-projection` |  |
| `63b422b3b3c9` | edit-hook-kondo-false-positives-on-seon-db-dynamic-vars | `seon.db/*conn*`, `seon.db/*read-evidence-sink*`, `seon.dev.clj-kondo/ensure-dependency-cache!`, `seon.fn/build-manifest`, `seon.fn/source-roots`, `seon.print/fit-text` |  |
| `cf18e49f6f83` | evaluation-reader-and-writer-use-different-entity-schemas | `seon.db/write-entity-schemas`, `seon.eval/of-agent` |  |
| `f05afe021a90` | fixtures-that-ignore-a-refused-transaction-read-absence-as-behaviour | `seon.db/transact!` |  |
| `2bbab3ff4180` | foreign-write-fence-reads-only-the-dynamic-var | `seon.db/*conn*`, `seon.db/transact!` |  |
| `efd01d3d7872` | heterogeneous-identity-query-refuses-during-reach-acquisition | `seon.db/q`, `seon.id/id` | `seon.id-test/data-shape-and-explicit-length-determine-identity` |
| `b4aacfd93d89` | historical-call-edge-analyses-need-rederivation | `seon.db/q`, `seon.fn/analyze-forms` |  |
| `eb67d7c42808` | incremental-adoption-refuses-owned-values-plans-under-a-stale-contract | `seon.db` |  |
| `7000a7fd9adb` | index-evidence-current-refuses-nil-source-on-agent-page-get | `seon.db/index-evidence-current` |  |
| `712810ba945b` | kondo-does-not-resolve-datalog-parser-generated-variable-constructor | `seon.db` |  |
| `3d7e4f28e2c9` | mcp-jvm-small-result-projection-fails-during-live-adoption | `seon.cluster/mcp-effective`, `seon.db/pull`, `seon.render.value/artifact-value` |  |
| `5424e899196b` | mcp-sci-error-projection-passes-a-nil-database | `seon.db/q` |  |
| `4dfb877ce1aa` | pull-caps-cardinality-many-at-1000-so-recorded-reach-reads-truncate-silently | `seon.db/q` |  |
| `c4faacc71af5` | read-and-admission-producers-still-require-thread-projections | `seon.db/db`, `seon.db/projection-fallback` |  |
| `f2a4a6b60db3` | read-refusal-checks-test-error-at-not-invalid-read | `seon.cluster.source/changed-identities`, `seon.db/q` |  |
| `ad1c38da7c95` | retained-identities-have-no-declared-retirement-state | `seon.db`, `seon.program` |  |
| `a5632fe59314` | runtime-block-html-is-raw-ids-and-instants | `seon.db/identity-attributes` |  |
| `b985018403c5` | shown-value-diff-disables-the-dependency-work-bound | `seon.db`, `seon.turn` |  |
| `3632d3723a43` | test-world-preparation-spends-seconds-in-config-apply-and-unmemoized-reads | `seon.cluster/seed-cluster-facts!`, `seon.config/apply-compiled!`, `seon.db/transact!`, `seon.reconcile/plan`, `seon.test/clean-request-handle`, `seon.test/program-overlay-tx` |  |
| `f30a49f3659d` | the-thirty-second-write-bound-fails-program-publication-under-load | `seon.db/transact!` |  |
| `5664562cfba6` | transaction-feedback-regressions-disagree-with-final-report-validation | `seon.db` |  |
| `4e98903a9c75` | transaction-html-has-a-second-generic-value-renderer | `seon.db`, `seon.render.value` |  |
| `a0084d6aabe2` | unlogged-findings-2026-08-01 | `seon.db/*conn*`, `seon.db/q` |  |
| `2babced64b76` | write-reference-validation-substitutes-zero-before-malli | `seon.db/write-value` |  |

**seon.dev.clj-kondo** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `1f5acb989a1e` | source-publication-cache-contention-hides-dependency-analysis-failure | `seon.dev.clj-kondo` |  |

**seon.dev.docstring** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `1b5d767d6a37` | development-adoption-cannot-compile-docstring-var-contracts | `seon.dev.docstring/check-file`, `seon.dev.docstring/check-file-request-schema`, `seon.dev.docstring/check-response-schema`, `seon.instrument/apply!`, `seon.instrument/restore!` |  |
| `a96568ad1cb5` | the-loaded-producer-guard-refuses-a-freshly-booted-host | `seon.dev.docstring/check-file` |  |

**seon.dev.mcp** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `c58b88ed9f96` | mcp-parent-watchdog-can-follow-a-reused-pid | `seon.dev.mcp` |  |
| `347bc1979d8c` | mcp-session-loss-claims-unobserved-restart | `seon.dev.mcp` |  |

**seon.edit.jvm** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `36ad99e42359` | filesystem-dials-absent-throws-a-bare-nullpointerexception | `seon.edit.jvm/filesystem-refusal` |  |

**seon.effect** (5)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `184e1cd5f2c3` | capability-component-arguments-stay-in-request-edn | `seon.effect/declared-datoms` |  |
| `26d5fdec9ab3` | class-accepted-work-can-end-without-terminal-evidence | `seon.effect`, `seon.turn` |  |
| `953f1dd62bca` | class-outward-values-bypass-total-render-contract | `seon.effect/render-ai`, `seon.error/render-ai`, `seon.fn/output-path-report`, `seon.instrument/violation`, `seon.print/fit`, `seon.sci.kernel/failure-value` |  |
| `3f506f49ebb8` | effect-feedback-orders-receipts-by-id | `seon.effect` |  |
| `315f19696f4b` | effect-run-and-form-ordinal-duplicate-the-evaluation-ref | `seon.effect/evaluation-eid`, `seon.effect/interruption-stamps`, `seon.id/evaluation` |  |

**seon.env** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `e5f23172d37c` | fixture-schema-readmission-after-adoption-refuses-environment | `seon.env/advance-projection!` |  |

**seon.error** (11)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `4d590de9062b` | a-collection-member-refusal-does-not-name-the-members-index | `seon.error/collection-member-problem`, `seon.error/explain-problem`, `seon.error/render-html` | `seon.instrument-test/refusal-value-projection-obeys-the-profile-and-html-keeps-the-whole-value` |
| `5c05d5efa52c` | a-failed-turn-wakes-itself-through-its-own-fault-message | `seon.error/commit-tx`, `seon.error/notice-ai-prose` | `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit` |
| `f8c38e5319f9` | a-negation-predicate-loses-a-transaction-position-variable | `seon.error/occurred-since` |  |
| `a9bc6c02ebe5` | error-observation-component-walk-passes-an-invalid-schema-to-malli | `seon.error/stored-observation` | `seon.error-test/recurrence-counting-does-not-require-a-notification-threshold` |
| `b5147a1fb754` | error-result-retirement-crosses-held-readers | `seon.blob/put!`, `seon.error.refusal/diagnostic`, `seon.error/prepare`, `seon.error/prepare-result`, `seon.sci.admit/result-handle`, `seon.sci.eval/bind-result!` |  |
| `f5827fc1dbb4` | instrumentation-record-mode-has-no-acquired-fault-recorder | `seon.error.refusal/refusal`, `seon.error/commit-call`, `seon.error/latest-fact`, `seon.error/refusal`, `seon.render.value/transacted`, `seon.sci.kernel/failure-value` |  |
| `fdfeda23a918` | provider-failure-diagnostic-is-absent-from-next-prompt | `seon.error/faults-form` |  |
| `c477f6e0cb53` | render-contract-coherence-stops-at-a-transparent-schema-wrapper | `seon.error/edit-prose` |  |
| `2d3a60aa7de2` | system-generated-messages-omit-arrival-ordinals | `seon.error`, `seon.render`, `seon.render.transcript` |  |
| `cc01c4ddf7ba` | throwable-class-is-declared-a-string-but-written-as-a-symbol-beside-exception-class | `seon.error` |  |
| `aebfc027d403` | wildcard-pull-rearming-refuses-a-loaded-contract | `seon.error`, `seon.sci.eval` |  |

**seon.eval.drive** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `e358aef94ce1` | bootstrap-o4-stops-before-causal-delegation-settles | `seon.eval.drive/run-episode!` |  |
| `9d7345f06f85` | eval-drives-duplicate-a-four-minute-run-clock | `seon.eval.drive` |  |

**seon.flow** (6)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `a883afca26a0` | class-anonymous-contracts-cannot-survive-publication | `seon.flow/start-graph!` |  |
| `02c914f9f67a` | flow-error-proc-has-no-declared-step-function-ref | `seon.cluster.agent`, `seon.error`, `seon.flow` |  |
| `5221f05f6e70` | flow-report-channels-have-no-reader | `seon.flow` |  |
| `e5cc368516c3` | flow-work-launcher-error-channel-has-no-reader | `seon.flow/join-error-fanout!` |  |
| `2b31cca92df3` | flow-work-launcher-graph-omits-its-root-io-executor | `seon.flow/work-launcher-graph-definition` |  |
| `ccc9ebcb768f` | root-compute-executor-has-no-per-cluster-fairness | `seon.flow` |  |

**seon.fn** (22)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `904ba9c1a860` | a-callee-macro-kind-change-leaves-callers-host-bound-stale | `seon.fn/analyzed-files`, `seon.fn/host-bound-callers` |  |
| `47be69ce3d92` | a-collection-bound-edge-clause-costs-half-a-second-per-read | `seon.fn/assert-capability-contracts!` |  |
| `023b8ad3e349` | a-fast-gate-jvm-dies-on-the-shared-kondo-cache-lock | `seon.fn.analyzer/invoke-kondo`, `seon.fn/build-manifest` | `seon.concurrency-independence-test/receipt-diagnostic-selects-only-present-failure-facts` |
| `3b7794a18e66` | a-file-digest-does-not-identify-complete-caller-analysis | `seon.fn/build-manifest` |  |
| `cf03d7f48f2d` | a-five-file-publication-spends-44-seconds-in-its-reconciliation-transaction | `seon.db/carried-projection`, `seon.db/transact!`, `seon.fn/analyzed-artifacts`, `seon.fn/index!` |  |
| `a9f45596c31e` | a-full-reanalysis-publication-takes-twenty-five-seconds | `seon.cluster/full-source-refresh!`, `seon.db/pull`, `seon.db/with-declarations`, `seon.fn/analyze-rows`, `seon.fn/file-rows`, `seon.fn/published-index-rows` |  |
| `178dcbc0f51a` | a-one-test-in-process-test-check-takes-twenty-to-fifty-seconds | `seon.fn/gate-sets` | `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage`, `seon.db-test/a-system-write-the-writer-never-acknowledges-returns-outcome-unknown-at-the-bound` |
| `8a8aaacb6499` | canonical-fixture-retains-old-function-contracts-after-adoption | `my.turn/wait`, `seon.blob/with-publication!`, `seon.cluster.source/publish!`, `seon.db/q`, `seon.fn/analyze-forms`, `seon.program/shape` | `seon.cluster.publication-inputs-test/file-row-selection-follows-declaration-and-finding-refs`, `seon.cluster.publication-test/previous-file-artifacts-derive-from-published-facts`, `seon.error-result-test/a-canonical-result-declarations-are-installed`, `seon.fn-test/indexed-declarations-carry-exact-file-bytes` |
| `30e113144269` | class-loaded-artifacts-lack-source-identity | `seon.fn/build-manifest` |  |
| `d14de5cb905b` | concurrent-publications-serialize-past-the-hook-bound | `seon.fn/tests-reaching` |  |
| `fe1bcf1e1998` | core-program-stubs-prevent-required-file-provenance | `seon.fn/desired-rows` |  |
| `14476c32550d` | datahike-avet-seek-casts-a-bound-value-of-another-type | `seon.fn/declared-reference-rules` |  |
| `e8cf5eed048a` | gate-selection-refusal-contracts-only-distinguish-markers | `seon.fn/gate-sets`, `seon.issue.detect/public-without-reaching-test` |  |
| `438c1da093ed` | gate-set-rederives-the-declared-reference-population-on-every-call | `seon.fn/functions-without-tests`, `seon.fn/gate-set`, `seon.fn/gate-sets`, `seon.issue.detect/public-without-reaching-test` |  |
| `da25e7eace11` | lint-identities-collide-for-multiple-findings-at-one-location | `seon.fn/assert-one-row-per-identity!`, `seon.fn/lint-rows` |  |
| `fe46f77b115d` | namespace-binding-targets-are-symbols-not-refs | `seon.fn`, `seon.sci.eval` |  |
| `87d7b9fcf4ba` | output-sink-query-excludes-operator-and-mcp-scripts | `seon.fn/output-path-report`, `seon.fn/rows`, `seon.fn/source-roots` |  |
| `d5aa405bb26f` | parallel-test-stress-exposes-eleven-isolation-sensitive-tests | `seon.cluster.agent/turn-step`, `seon.fn/build-artifact`, `seon.render/acquire-context!` | `seon.cluster.agent-test/n-agent-parallel-turns-property`, `seon.cluster.agent-test/wake-routing-conservation-property`, `seon.sci.eval-test/generated-sources-compose-fork-guard-and-admission` |
| `d151e2a93e31` | recorded-selection-completion-leaves-changed-members-pending | `seon.fn/declared-reference-edges`, `seon.test/admit-run` |  |
| `8dd3198bed5c` | recursive-call-coverage-outlives-its-cancelled-future | `seon.fn/functions-without-tests` |  |
| `a8bb34d919e1` | the-cold-fixture-base-outruns-the-liveness-silence-backstop | `seon.cluster.export/reidentify-at!`, `seon.eval.drive/await-fact!`, `seon.fn/index!`, `seon.fn/index-tempids` | `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer`, `seon.program-test/typed-cross-namespace-deletion-retracts-function-and-test` |
| `575ba99c41bb` | write-seam-is-a-named-set-not-a-declared-fact | `seon.db/transact!`, `seon.fn/write-seam-symbols` |  |

**seon.fn.schema-shape** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `8d7c1e48dac8` | schema-field-types-admit-contradictory-owning-values | `seon.fn.schema-shape` |  |
| `2ab2b39cadb8` | the-index-cache-retains-4gb-of-expanded-schema-shape-forms | `seon.call-preparation`, `seon.fn.schema-shape`, `seon.instrument` |  |

**seon.fs** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0631798ae9c9` | an-archive-booted-default-relativizes-hook-paths-outside-its-source | `seon.cluster/refresh-source!`, `seon.fs/source-directory` |  |
| `9e7e6c4a1cfb` | reset-deletes-a-bloated-store-one-lstat-at-a-time | `seon.fs/delete-recursively!` |  |

**seon.fs.jvm** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `19596b6d1331` | entity-pair-debug-parity-remains-unverified-after-adoption | `seon.fs.jvm/read` | `seon.fn-test/quoted-private-handler-symbol-is-indexed-as-the-runtime-symbol` |
| `2cd8aaf330e3` | filesystem-stat-refusal-fails-its-return-contract | `seon.fs.jvm/path-plan`, `seon.fs.jvm/stat`, `seon.shell.jvm/cwd-path` |  |

**seon.id** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0c8c56b83fbe` | agent-install-gate-cannot-resolve-selected-test-bindings | `seon.id/valid?` | `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer`, `seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol`, `seon.id-test/data-shape-and-explicit-length-determine-identity`, `seon.schedule-test/returned-and-thrown-handler-errors-use-the-existing-root-wake` |
| `ec9dfde4e2cb` | incremental-publication-omits-callability-changes | `seon.id/id` |  |
| `5eeec96a2aee` | mcp-runtime-status-lists-no-clusters-for-an-explicit-root | `seon.id/id` |  |

**seon.instrument** (5)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `7148f7c82c2c` | a-live-cluster-arms-ten-fewer-contracts-than-it-declares | `seon.fs.jvm/write`, `seon.instrument/apply!` |  |
| `341ca7ae5d7f` | armed-error-validation-throws-on-non-keyword-sorted-maps | `seon.instrument/compiled-wrapper` | `seon.test.selection-test/changed-inputs-are-decided-by-content-not-modification-time` |
| `29000a4c9b1b` | cluster-boot-instruments-in-flight-working-tree-vars | `seon.await/await!`, `seon.instrument/apply!` |  |
| `cc0fe8c9bbf3` | instrumented-vars-hide-their-constant-pool-from-runtime-dependency-inspection | `seon.instrument/arm-var!` |  |
| `dc24320ccb1d` | test-refusal-observations-overflow-in-projection-acquisition | `seon.blob/with-publication!`, `seon.error.refusal/refusal`, `seon.error/config-expectation-present?`, `seon.fn.schema-shape/normalized-form`, `seon.instrument/arm-var!`, `seon.sci.eval/acquisition-refusal` | `seon.fn-test/a-refused-reference-read-refuses-gate-set-derivation` |

**seon.issue** (6)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `fe3f010eaa05` | an-agent-turn-proc-dies-on-every-pass-and-oversight-still-reports-it-armed | `seon.issue/start!`, `seon.oversight/flow-status`, `seon.sci.eval/acquired-program`, `seon.turn/next-agent-work` |  |
| `f21494ede574` | generated-issue-opening-promises-tests-it-does-not-have | `my.test/check`, `seon.issue/check-form`, `seon.issue/render-ai` |  |
| `cdfb5047aaf0` | generated-issues-carry-no-tests-so-start-refuses-them | `seon.issue.detect/entity-map-without-pair`, `seon.issue.detect/public-without-doc`, `seon.issue/generate`, `seon.issue/start!`, `seon.issue/start-tx` |  |
| `4f284565335d` | issue-indexing-at-publication-costs-13-seconds | `seon.cluster.source/publish!`, `seon.db/pull-many`, `seon.issue/adopt!`, `seon.issue/index!`, `seon.issue/index-tx` |  |
| `4c22c9756eae` | prospective-issue-refs-render-as-unnamed-checks | `seon.issue/status-text` |  |
| `647d0d56261e` | the-issue-ai-render-no-longer-teaches-its-requery-form | `my.issue/status`, `seon.issue/render-ai` |  |

**seon.issue.detect** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `8412bfb6dfd4` | orphan-ast-nodes-outlive-the-declaration-that-held-them | `seon.issue.detect/entity-map-without-pair` |  |
| `451161a7e8d9` | the-indexer-drops-clj-kondos-defined-by-so-generated-declarations-look-authored | `seon.issue.detect/public-without-contract`, `seon.issue.detect/public-without-doc` |  |

**seon.maintenance** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `d41edc3b163c` | published-head-references-never-written-index-nodes | `seon.maintenance/collect!` |  |

**seon.operator** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `b0224d45cfa1` | move-to-head-takes-a-minute-or-more-not-seconds | `seon.cluster.boot`, `seon.operator` |  |
| `92f16abe2362` | script-namespaces-loaded-by-the-jvm-are-not-published | `seon.operator` |  |

**seon.oversight** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0299f8a1d4b7` | a-three-file-changed-path-publication-takes-a-minute | `seon.oversight`, `seon.render`, `seon.render.web` |  |

**seon.print** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `75dfea7fc883` | namespace-layout-confines-most-content-to-scroll-boxes | `seon.print/fit` |  |
| `2a7ea891d099` | the-published-graph-shows-zero-out-edges-for-seon-print-emit-after-the-protocol-impls-fix | `seon.print/emit`, `seon.print/emit-sequential` |  |

**seon.problems** (4)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `49cd70b06830` | one-lanes-intermediate-edit-refuses-adoption-for-every-lane | `seon.problems/problems`, `seon.test/run` |  |
| `84e4ac0d758a` | problems-refuses-its-own-zero-occurrence-signature | `seon.error/recording`, `seon.problems/problems` |  |
| `1f3862cbb89a` | problems-rejects-signatures-with-no-occurrences | `seon.problems/problems` |  |
| `8caa9ac3fa20` | runtime-status-refuses-error-occurrence-count | `seon.problems/problems` |  |

**seon.program** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `d3491becdac2` | fault-injection-drops-an-existing-callable-arity | `seon.program/canonical-row` |  |
| `985e9325a5cd` | live-resources-outrun-the-loaded-program-identity-list | `seon.program/authored-shapes`, `seon.program/identity-attributes`, `seon.schema.edn/declaration-stamp`, `seon.schema.edn/packaged-forms`, `seon.test/run` |  |
| `b92a7cb50f62` | schema-declaration-regression-disagrees-with-current-row-shape | `seon.db/pull`, `seon.program/with-contract-facts`, `seon.schema/canonical-schema-rows`, `seon.sci.eval/row` | `seon.cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute`, `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`, `seon.sci.eval-test/declared-row-evaluates-a-schema-once-inside-its-delta` |

**seon.reconcile** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `d6d2d99412a6` | duplicate-identity-refusal-evidence-is-unordered | `seon.reconcile` |  |
| `9c1b1910993d` | incremental-population-owners-accrete-but-never-retract | `seon.reconcile/plan` |  |

**seon.render** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `b5231e2f6120` | a-generic-attribute-scoped-render-answers-with-the-owning-entity | `seon.cluster.agent/render-identity-ai`, `seon.render.value/render-ai`, `seon.render/render-form`, `seon.render/render-invocation-argument` | `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain` |
| `2d1a4f816f4c` | a-render-producers-contract-refusal-is-too-large-to-admit-so-the-typed-unknown-loses-its-kind | `seon.error/bounded-admission`, `seon.render/invocation-unknown`, `seon.render/unknown-stable-evidence`, `seon.sci.admit/admit-value`, `seon.sci.kernel/failure-value`, `seon.sci.kernel/invoke` | `seon.render-coverage-test/a-refused-render-producer-contributes-a-stable-typed-unknown` |
| `f0b4edc4dd0f` | render-selection-loses-the-viewing-namespace | `seon.render/selection` |  |

**seon.render.hiccup** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `9985536b7e2a` | anonymous-runtime-contracts-have-recurred | `seon.ai/completion-text`, `seon.ai/sink?`, `seon.config/apply-compiled!`, `seon.config/effective`, `seon.flow/var-process`, `seon.instrument/instrumented` |  |

**seon.render.ns** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0b1c4f787de3` | namespace-require-summary-renders-nil-identities | `seon.render.ns` |  |

**seon.render.transcript** (4)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `6852d84e5abf` | debug-reread-summary-duplicates-shown-value-and-diff-owners | `seon.db`, `seon.render.transcript`, `seon.repl`, `seon.turn` |  |
| `75747df07165` | ordinary-turns-do-not-use-the-additive-system-turn | `seon.render.transcript/render-history-ai` | `seon.loop-proof-test/virtual-loop-end-to-end` |
| `ef6c4eb0044c` | the-agent-history-render-throws-on-a-string-where-it-casts-an-instant | `seon.render.transcript` |  |
| `e521140c1993` | transcript-candidate-window-orders-receipts-and-comments-by-id | `seon.render.transcript` |  |

**seon.render.value** (4)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `58469eeb9144` | a-namespace-page-serves-twenty-seven-megabytes-in-thirty-four-seconds | `seon.render.ns/full-html-view`, `seon.render.value/value-node*`, `seon.render/project-node`, `seon.sci.kernel/invoke` |  |
| `75066d34d089` | the-agent-profile-no-longer-cuts-an-oversized-rendered-string | `seon.render.value/transacted`, `seon.render.value/window`, `seon.render/agent-render-profile` |  |
| `37dc4c29f622` | the-value-renderer-sorts-every-visited-map-whole-before-choosing-children | `seon.print/compare-values`, `seon.render.value/ordered-head`, `seon.render.value/value-node*`, `seon.render.value/window`, `seon.test.runner/report-value` |  |
| `5ffbc8b780b1` | value-floor-fixtures-still-hand-strings-to-symbol-typed-attributes | `seon.render.value` |  |

**seon.render.walk** (2)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `235cf5cda869` | opening-generator-pushes-undemanded-candidates | `seon.bootstrap/pull-result`, `seon.render.walk/ordered-episode` |  |
| `df671d48e7e9` | the-root-page-first-paint-after-a-reset-exceeds-forty-seconds | `seon.instrument/arm-var!`, `seon.render.walk/neighborhood` |  |

**seon.render.web** (8)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `6a116d994e1c` | agent-flow-fixture-omits-render-interest | `seon.render.web/render-step` | `seon.cluster.message-test/inbound-wakes-the-named-agent-test` |
| `de9a650ee853` | class-local-updates-recompute-global-projections | `seon.render`, `seon.render.web` |  |
| `7fc72059775e` | debug-feed-waits-fail-only-in-pooled-worker | `seon.render.web` | `seon.render.web-test/reconnect-is-repaint`, `seon.render.web-test/reconnect-is-repaint-wire-test`, `seon.render.web-test/reconnect-mid-stream-is-a-fact-only-repaint` |
| `728bf60807a8` | debug-html-render-carries-no-agent-scoped-environment | `seon.render.web/render-source-call` |  |
| `f6e6a237b4a5` | default-web-request-times-out-during-partial-adoption | `seon.render.web` | `seon.render.web-test/an-agent-page-is-the-same-mechanism-as-root` |
| `b70709ed4adf` | projection-schema-admits-a-projection-without-a-registry | `seon.render.web/attribute-description` |  |
| `2dc46465ac9c` | root-page-warm-read-evidence-replay-exceeds-300ms | `seon.render.web/candidate-call-ids` |  |
| `917b7b147c0d` | turn-source-syntax-blocks-default-adoption-2026-09-08 | `seon.ai`, `seon.render.web`, `seon.turn` |  |

**seon.repl** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `9a982d868fb8` | the-first-debug-page-after-an-adoption-takes-eighteen-seconds | `seon.repl/render-ai`, `seon.schedule/valid-cron?` | `seon.render.retained-test/adoption-of-an-unrelated-namespace-re-renders-zero-evaluations` |

**seon.schedule** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `47124e0c0d2d` | flow-stop-transition-errors-are-dropped-on-a-closed-error-channel | `seon.flow`, `seon.schedule` |  |

**seon.schema** (14)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `8a3fcfc36031` | a-search-contract-predicate-cannot-be-made-durable | `seon.instrument/apply!`, `seon.schema/canonical-definition` |  |
| `4e704f1aef8f` | activation-closure-records-no-schema-keys | `seon.schema/projection-from-database` |  |
| `3a0ab3ae83bb` | adopting-the-instrumentation-owner-leaves-unreloaded-namespaces-unarmed | `seon.schema/converged-predicate-var` |  |
| `30535ecebfbf` | call-preparation-test-rebuilds-a-projection-per-call | `seon.schema/declaration-projection` |  |
| `2fc7b7e19c3e` | data-page-takes-five-and-a-half-seconds-for-three-kilobytes | `seon.config/effective`, `seon.schema/!fallback-counts` |  |
| `2f6853aa0317` | guarded-public-walk-exceeds-allocation-bound | `seon.db/validate-pulled-value`, `seon.instrument/compiled-wrapper`, `seon.schema/projection-from-rows`, `seon.schema/projection-with-pulled-form-in`, `seon.schema/pull-selector?`, `seon.sci.eval/cluster-ctx` |  |
| `28dd8cbc54b0` | platform-fixtures-refuse-function-schema-acquisition | `seon.instrument/compiled-wrapper`, `seon.schema/projection-registry`, `seon.test.runner/duration-failures` |  |
| `bc32ce08a6c5` | projection-replacement-generations-retain-replaced-compiled-schemas | `seon.schema/projection-registry`, `seon.schema/projection-with-declarations` | `seon.schema-test/an-incremental-projection-equals-its-full-build` |
| `2b2519de4687` | render-candidate-checks-mix-different-arities | `seon.render.value/transacted`, `seon.schema.edn/packaged-forms`, `seon.schema/function-accepts-and-returns-in?` |  |
| `f3239d3524b2` | schema-projection-state-contract-invokes-deref-as-a-predicate | `seon.schema/call-with-projection-state` |  |
| `3fdd68c3bda6` | sci-acquisition-must-derive-from-current-identity-provenance | `my.program/overrides`, `seon.eval.drive/uuid-text`, `seon.id/valid?`, `seon.program/overrides`, `seon.schema/admission-from-asserting-transaction` |  |
| `cecd3c81d820` | turn-integration-fixture-and-two-ordinary-passes-take-forty-seconds | `seon.cluster.agent/acquire-context!`, `seon.db/q`, `seon.reconcile/plan-transaction-data`, `seon.schema/canonical-coll-string`, `seon.schema/projection-from-rows`, `seon.sci.eval/base-ctx` |  |
| `024d1343c16f` | wildcard-pulled-collections-do-not-satisfy-entity-set-contracts | `seon.schema` |  |
| `5955c7a73930` | within-run-schema-key-refinement-needs-an-owner-ruling | `seon.schema/schema-removal-blockers` |  |

**seon.schema.datahike** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `25f877d8efb9` | a-stored-or-attribute-drops-its-childrens-nil-refusal | `seon.schema.datahike/compiled-storage`, `seon.schema.datahike/malli->datahike-attr-in` |  |
| `136917f61238` | secondary-only-attributes-have-no-covering-index | `seon.schema.datahike` |  |
| `97efea27a515` | writer-rejection-prints-the-complete-program-projection | `seon.schema.datahike/encode-call-output-in` |  |

**seon.schema.edn** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `25bb9e7f636b` | packaged-forms-per-call-reads-recur-at-every-new-caller | `seon.schema.edn/packaged-forms` |  |

**seon.schema.internal** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `ffd5f85a11a6` | stored-runner-facets-require-an-unstorable-offending-value | `seon.error`, `seon.schema.internal` |  |

**seon.sci.admit** (3)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `159922ad930c` | admit-inst-overlap-prefers-collection-shape | `seon.sci.admit` |  |
| `88d7c6132f5b` | effect-settlement-cannot-classify-polymorphic-handler-results | `seon.sci.admit/admit-value` |  |
| `a0ebaeb51462` | repl-parity-divergences | `seon.sci.admit/result-handle`, `seon.sci.eval/evaluate` | `seon.sci.eval-test/contracted-defn-renders-the-var-it-declared` |

**seon.sci.eval** (20)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `cc7b1553cbe9` | a-data-only-commit-rebuilds-the-whole-sci-program | `seon.sci.eval/acquire!`, `seon.test/run` |  |
| `5cb8197dd91e` | acquired-dir-data-has-no-read-evidence | `seon.sci.eval` |  |
| `2d1301cdfbd7` | agent-repl-cannot-require-clojure-pprint | `seon.sci.eval`, `seon.sci.kernel` |  |
| `b8de1b1e9256` | as-alias-recorded-as-a-require-refuses-interpreted-acquisition | `seon.fault`, `seon.flow`, `seon.fn`, `seon.sci.eval` |  |
| `97f34afb9f22` | base-context-membership-still-varies-with-what-happened-to-load | `seon.sci.eval/host-namespace!`, `seon.test/with-test-loader` |  |
| `b84f522b86b3` | candidate-context-shares-parent-program-metadata | `seon.env`, `seon.sci.eval`, `seon.sci.kernel` |  |
| `2913ce7ecfaf` | cluster-ctx-delegating-arities-refused-under-instrumentation | `seon.sci.eval/cluster-ctx` |  |
| `073e010de9ca` | cohosted-clusters-share-one-unbounded-agent-heap | `seon.sci.eval` |  |
| `5645eb74b364` | concurrent-eval-test-calibrates-interpreted-work-to-wall-time | `seon.sci.eval` |  |
| `d8460b7e257c` | flow-skill-forbids-current-turn-acquisition | `seon.sci.eval` |  |
| `cec980fd8d35` | guarded-schema-declarations-still-exceed-the-allocation-regression-bound | `seon.sci.eval/evaluate` |  |
| `9cc0f77adbd1` | namespace-page-repeats-renderer-unavailable | `seon.cluster.agent/render-identity-html`, `seon.render.value/render-html`, `seon.render/invoke-selected`, `seon.sci.eval/install-function-from-database!`, `seon.sci.kernel/invoke`, `seon.sci.kernel/program-function` |  |
| `9f589d24cda6` | negative-import-masks-escape-static-admission | `seon.fn`, `seon.fn.analyzer`, `seon.sci.eval` |  |
| `a1460290f09a` | runtime-turn-and-evaluate-kernels-conflate-boundaries | `seon.sci.eval/evaluate` |  |
| `fc25260a871b` | sci-arming-guesses-the-cluster-from-a-database-row | `seon.config/effective`, `seon.sci.eval/database-effective-config` |  |
| `1852c9b4ea50` | sci-fork-three-arity-passes-nil-to-a-required-projection-state | `seon.sci.eval/fork-cluster-ctx` | `seon.test-test/resolution-follows-admitted-source-and-acquisition` |
| `96ece19f4b12` | sci-test-declarations-drop-explicit-usage-metadata | `seon.fn`, `seon.sci.eval` |  |
| `d9097d870122` | task-execution-fixture-has-no-acquired-sci-program | `seon.sci.eval/acquire!`, `seon.sci.eval/cluster-ctx`, `seon.test/with-test-loader` |  |
| `668918d8c8e6` | turn-settlement-mistakes-kind-free-refusal-for-report | `seon.db/transact!`, `seon.sci.eval/install-evaluated-rows!`, `seon.turn/phase` | `seon.turn-loop-test/a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again` |
| `12909697b34f` | worktree-edit-hook-publication-targets-main-root | `seon.sci.eval` |  |

**seon.sci.kernel** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0dd134f20728` | sci-error-unions-name-indistinguishable-test-facets | `seon.sci.admit/semantic-value`, `seon.sci.kernel/failure-value` |  |

**seon.sci.reader** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `f772c90f8754` | documentation-arglists-with-auto-keywords-are-not-edn | `seon.sci.reader/read` |  |

**seon.shell** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `317094c739ba` | scratch-boot-refuses-the-shell-stdin-predicate | `seon.shell/stdin?` | `seon.config-test/config-loads-the-packaged-population-in-a-fresh-jvm` |

**seon.test** (17)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `59d372f9bc6e` | a-hundred-megabyte-failure-message-rides-every-new-entity-commit | `seon.test/run` | `seon.schema-test/an-incremental-projection-equals-its-full-build` |
| `19a068f04261` | an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm | `seon.cluster.registry/branch!`, `seon.test/run` |  |
| `72f70a1e78b5` | bounded-admission-prints-a-whole-leaf-before-its-bound | `seon.error/project-observation`, `seon.instrument/boundary-refusal`, `seon.render.value/floor`, `seon.sci.admit/canonical-edn`, `seon.test/bounded-result` |  |
| `d3a0b4979965` | complete-program-publication-is-refused-on-a-cardinality-many-set | `seon.db/write-map-error`, `seon.test/destroyers`, `seon.test/run` |  |
| `8ba8e2232c59` | declaration-settlement-consumes-invalid-read-as-ref | `seon.db/q`, `seon.test/run` | `seon.fn-test/settled-agent-form-has-static-index-edge-parity`, `seon.program-test/identical-runtime-redeclaration-builds-no-datoms`, `seon.turn-test/batch-settlement-preserves-declaration-order` |
| `5faef07aa86c` | default-component-probe-times-out-after-adoption | `my.message/read`, `my.plan/add!`, `seon.cluster.message/render-inbox-ai`, `seon.db/pull`, `seon.schema/call-with-projection`, `seon.test/run` | `seon.data-shapes-test/attempt-usage-is-queryable` |
| `f4079171809b` | in-process-test-timeout-precedes-fixture-release | `seon.test` | `seon.cluster.turn-test/generated-model-attempt-traces-preserve-presence-and-episode-laws` |
| `bcdd47f5df22` | issue-worker-creation-in-process-test-exceeds-bound | `seon.test/run` |  |
| `628bdc332b0e` | platform-flow-census-reaches-root-cleanup-through-scheduler | `seon.test/run` |  |
| `238916de26a0` | post-adoption-check-times-out-on-message-contract-test | `seon.test/run` |  |
| `7a1a378fa150` | publication-refuses-while-the-candidate-branch-holds-a-connection | `seon.cluster/refresh-source!`, `seon.test/green?` | `seon.db-test/transaction-success-and-refusal-shapes-are-declared` |
| `16da6bc36b30` | recomputed-program-digest-disagrees-with-the-stored-run-digest | `seon.test.runner/program-digest`, `seon.test/verified?` |  |
| `e19ad2b759f5` | test-evidence-is-lineage-keyed-so-reset-cannot-keep-it | `seon.program/program-attributes`, `seon.test/changed-since-green` |  |
| `a4b57a043d26` | test-fixture-worlds-inherit-the-live-clusters-errors-and-messages | `seon.test/run` |  |
| `43b7b5b8f5d2` | test-host-classification-and-path-use-different-program-edges | `seon.fn/tests-reaching`, `seon.test/destructive-reach`, `seon.test/host` |  |
| `b954c6a1f372` | the-default-jvm-loaded-dependency-classes-the-current-cache-no-longer-selects | `seon.dev.clj-kondo`, `seon.render.ns`, `seon.render.walk`, `seon.test` |  |
| `1eb95e09af55` | the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency | `seon.test/resolve-test`, `seon.test/run` |  |

**seon.test.cache** (1)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `c38e2b806e76` | operator-fast-iterations-end-on-unattributed-term | `seon.test.cache`, `seon.test.runner` |  |

**seon.test.runner** (9)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `4177b2a8fe49` | a-fixture-refusal-loses-its-diagnostic-at-the-test-reporter | `seon.test.runner` |  |
| `17098d8cbe7f` | admit-run-derives-the-program-digest-from-every-change-since-the-seal | `seon.test.runner/program-digest` |  |
| `46ff46fc9c85` | admitted-test-results-are-invisible-to-legacy-consumers | `seon.test.runner/latest-results`, `seon.test.runner/record-tx`, `seon.test/recorded-result`, `seon.test/run` |  |
| `7c3cdf529f6a` | class-proofs-pass-without-exercising-their-premise | `seon.test.runner/assertionless-failure` |  |
| `dc0a30c0b0aa` | confirmation-parallel-failure-blocks-reading-worker-protocol | `seon.test.runner` |  |
| `98f16ae0c47e` | moving-a-failing-assertion-conflicts-with-its-immutable-report | `seon.test.runner/record-tx` |  |
| `c40e6377d9c6` | runner-live-cluster-snapshot-reads-a-referred-var-with-find-var | `seon.test.runner` |  |
| `a997683bffe7` | the-admission-program-write-check-scans-program-attribute-history | `seon.test.runner/program-written-since?`, `seon.test/run` |  |
| `32c50ef12735` | the-drift-snapshot-copies-every-namespace-map-twice-per-test | `seon.instrument/instrumented`, `seon.test.runner/run-vars!` |  |

**seon.turn** (21)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `0d2bd098c7a6` | adopted-default-no-provider-turn-does-not-settle | `seon.cluster.agent/arm!`, `seon.turn/close-call`, `seon.turn/receipt-settle-call` |  |
| `78d1aa3b1179` | adoption-can-leave-a-changed-contract-armed-with-its-previous-shape | `seon.test/run`, `seon.turn/write-refusal-bound` | `seon.turn-test/a-refused-turn-write-is-bounded-and-commits-exactly-one-fault` |
| `18e91d89fb6e` | an-agent-graph-steps-after-its-test-branch-store-closes | `seon.db/q`, `seon.turn/episode-runs`, `seon.turn/step`, `seon.turn/turn` |  |
| `0c5d5a285814` | bootstrap-next-entry-has-no-production-caller | `seon.bootstrap/next-entry`, `seon.turn/generate-turn` |  |
| `768e8e79b369` | bound-transaction-input-selects-an-older-turn | `seon.turn/continuing-reply?` |  |
| `88876be61a8a` | cluster-threads-outside-flow-have-unobserved-exits | `seon.render.web`, `seon.schedule`, `seon.turn` |  |
| `d6aaccdb736f` | development-adoption-retains-old-web-service-inputs | `seon.env/advance-projection!`, `seon.turn/preview-sources`, `seon.turn/step` |  |
| `5deb1c861a5c` | effective-settings-read-refreshes-after-system-turn | `seon.turn/episode-runs` |  |
| `073aa490ce0f` | error-recording-drops-declared-distinguishing-members | `seon.turn/phase` | `seon.turn-loop-test/install-gate-failure-settles-the-started-receipt-as-a-failure` |
| `5456bcd06759` | expected-refusal-logs-raw-datom-error-twice | `seon.db/transact!`, `seon.effect/open-call`, `seon.turn/refuse!` | `seon.effect-test/request-commits-before-io-dispatch-and-settles-once` |
| `5b6145355d9e` | issue-worker-opening-turn-closes-and-no-reply-turn-opens | `seon.issue/start!`, `seon.render/invoked`, `seon.turn/next-agent-work`, `seon.turn/resume-turn` | `seon.cluster.agent-arming-test/starting-an-issue-leaves-one-unanswered-wake-its-first-reply-answers`, `seon.namespace-agent-loop-test/an-issue-worker-started-on-its-candidate-branch-runs-there-alone` |
| `fe182823a143` | renderer-codec-fixture-loses-a-declared-contract | `seon.turn/row-tx` |  |
| `2312f84f11c9` | runtime-listens-do-not-yet-participate-in-turn-eligibility | `seon.turn` |  |
| `5c3d5fb56cf3` | scratch-debug-feed-and-turn-backstops-after-adoption | `seon.cluster/projection-executor`, `seon.turn/turn-completion-error` |  |
| `87f08622677a` | terminal-refusal-error-fact-fails-on-oversized-data | `seon.blob/get`, `seon.error/bounded-admission`, `seon.turn/close-call`, `seon.turn/open-run-tx-call` |  |
| `433658d6be95` | the-live-juniper-fixture-wipes-turns-under-a-running-agent-loop | `seon.turn/compact-call`, `seon.turn/open-run-tx-call` |  |
| `90f7420faac8` | the-turn-proc-cannot-answer-ping-or-stop-during-a-model-call | `seon.turn` |  |
| `6395d77379a0` | turn-bookkeeping-exceeds-recorded-regression-bound | `seon.turn/gate-function-install` | `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent` |
| `dffb9f359181` | turn-declaration-transactions-have-no-agent-or-turn-metadata | `seon.turn` |  |
| `69beecc73f58` | turn-evaluations-bypass-work-submission | `seon.flow`, `seon.turn` |  |
| `ff087f2d9efc` | virtual-turn-control-loses-agent-routing | `seon.cluster/arm-agents!`, `seon.render.web/change-context`, `seon.turn/virtual-turn!` |  |

**~triage (no owning namespace)** (75)

| id | name | cited subjects | cited tests |
|---|---|---|---|
| `f47ffc77550e` | a-call-preparation-facet-requires-unstorable-candidates |  |  |
| `4a2538dd2efc` | a-concurrent-classpath-resolution-corrupts-tools-deps-and-aborts-the-gate |  |  |
| `7c6954c5b6ef` | a-one-form-resume-turn-takes-seconds |  |  |
| `d40ab3005197` | a-program-identity-row-pulls-nil |  |  |
| `53954802086e` | a-throwable-fault-keeps-no-inline-evidence-at-any-plausible-bound |  |  |
| `8b90def54da5` | adoption-probe-emits-an-invalid-root-namespace-lookup |  |  |
| `35e3de255b1b` | an-open-map-keyed-only-by-universal-attributes-shadows-every-entity |  |  |
| `33ff0e6ad2be` | boot-initialization-still-names-deleted-search-supplier |  |  |
| `3f2eb1f614c2` | browser-ui-observation-has-no-accessible-window |  |  |
| `a75e0fb148b9` | cache-reuse-regression-exceeds-live-test-bound |  |  |
| `54a3b34bbd02` | call-preparation-refusal-dumps-the-acquired-projection |  | `my.program-test/repl-documentation-and-supplied-database-use-the-canonical-population` |
| `ed10c1286059` | cancelled-future-is-not-body-exit-evidence |  |  |
| `791d69aa06ae` | chart-plan-examples-retain-old-juniper-step-ids |  |  |
| `f5afdb6a3c29` | class-classification-is-inferred-from-hand-lists |  |  |
| `e9230ffc3b9e` | class-dependency-representations-leak-past-boundaries |  |  |
| `ccee59ca0cd7` | class-documentation-restates-executable-contracts |  |  |
| `e24b8b94dfb2` | class-domain-order-falls-through-to-strings-and-hashes |  |  |
| `e084bb2d385f` | cluster-status-renders-unknown-observations-as-raw-error-maps |  |  |
| `a69b8d49f78f` | context-cookbook-trial-provider-has-no-credits |  |  |
| `a4fa5583cd78` | cua-browser-surface-is-unavailable-in-the-implementation-session |  |  |
| `87866bba51a2` | database-value-shape-name-duplicates-the-db-key |  |  |
| `10130fcd8c5c` | datahike-fork-is-28-commits-behind-upstream |  |  |
| `da653d11261d` | datahike-pull-find-dependencies-widen-to-all |  |  |
| `ba3b4327ddd8` | debug-feed-backstop-fault-wakes-the-viewed-agent |  |  |
| `585ad9cbcdfb` | debug-feed-subject-change-regression-times-out |  | `seon.render.web-test/canonical-debug-feed-repaints-when-the-subject-changes` |
| `1b46ef0ff121` | dependency-resolution-can-race-maven-model-validation |  |  |
| `aefeea3c18f4` | drive-one-starts-without-required-plan-facts |  |  |
| `f7f651a7c130` | dynamic-in-ns-cannot-persist-definition-namespace |  |  |
| `03a9c6ee4a6c` | evaluation-fixtures-still-read-retired-lifecycle-shapes |  |  |
| `a48814a4367b` | fast-overlay-admission-prints-the-complete-program-manifest |  |  |
| `d9ee5dc9515b` | feed-writer-casts-an-absent-package-number |  |  |
| `8d9de41a4a16` | fixture-agent-rows-lack-the-required-agent-branch |  | `seon.db-test/retained-read-evidence-invalidates-only-on-a-depended-attribute` |
| `7353ef09a1b0` | help-trial-copies-prompt-and-invents-results |  |  |
| `aaed69125bb2` | historical-function-values-do-not-identify-an-error-entity |  | `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer` |
| `af29ea65eb04` | history-prompt-fixture-retracts-required-turn-attributes |  |  |
| `5b8fbf03619d` | hyperlith-pin-behind-lockstep-rework |  |  |
| `84725b76051c` | identity-upserts-still-require-complete-entity-maps |  |  |
| `808d63737cb1` | inbox-block-omits-message-content |  |  |
| `5af43d04b9ed` | isolated-operator-init-requires-a-source-checkout |  |  |
| `4501d3ed8778` | juniper-submission-repeats-existing-turn-refusals |  |  |
| `55e795be8713` | map-unions-have-no-explicit-discriminants |  |  |
| `f60298a554eb` | mcp-eval-refuses-map-results-without-cluster-projection-state |  |  |
| `b8a44e74418a` | namespace-page-first-byte-exceeds-ten-seconds |  |  |
| `3a7243d1ec48` | nested-test-snapshot-overwrites-its-fresh-run-claim |  |  |
| `e595a72a203f` | operator-status-refuses-foreign-live-root |  |  |
| `af0a3153fc6c` | operator-test-status-still-reads-retired-results-branch |  |  |
| `5e6d7b6fa044` | posh-cardinality-one-pull-analysis-has-an-arity-defect |  |  |
| `0cc9e97bca35` | program-graph-tests-do-not-carry-their-current-contract-projection |  |  |
| `399f0c4937e2` | publication-clock-script-requires-a-worktree-and-a-from-zero-start |  |  |
| `4f24b96a1ead` | raw-identity-projection-hides-selected-steward |  |  |
| `5802b465c293` | reader-test-expects-declaration-symbols-as-strings |  |  |
| `ed01013a6d4b` | root-empty-plan-read-shows-nil |  |  |
| `984bbab04858` | root-maintenance-context-exceeds-provider-budget |  |  |
| `bb6ce63b74a8` | runner-launcher-failures-repeat-complete-classpaths |  |  |
| `8e8e0e814a9d` | runtime-status-throws-on-a-map-entry |  |  |
| `e4d44ca572bd` | schema-guard-refuses-accretive-loosenings-with-data |  |  |
| `9feff895c2b5` | sci-evaluation-tests-still-expect-retired-storage |  |  |
| `cadcb25c0b5a` | scratch-root-has-agent-errors-without-attempt-facts |  |  |
| `34116c43032a` | seon-mcp-tools-absent-in-codex-lane-again |  |  |
| `fd83e869d45e` | september-14-issue-notes-leave-the-issue-authority-invalid |  |  |
| `0effdb242013` | source-load-is-118s-against-the-ten-second-law |  |  |
| `ad61be910201` | source-publication-fingerprint-collision-after-config-property-reordering |  |  |
| `0cabc9aa6ce6` | stale-dev-dependency-cache-serves-wrong-classes-silently |  |  |
| `ce8c4f70fda2` | store-grew-to-69-gigabytes-in-one-day-of-lanes |  |  |
| `b30a195fc33c` | test-provenance-probe-exceeds-mcp-bound |  |  |
| `0c6a71c04dfa` | the-lifecycle-watchdog-measures-silence-from-lock-acquisition |  |  |
| `e8ab1034bf6c` | the-query-planner-rejects-a-negation-bound-by-a-recursive-rule |  |  |
| `1941f6284949` | the-supported-mcp-runtime-tools-were-absent-from-a-bounded-lane |  |  |
| `d8849f8aa9c6` | transcript-and-turn-fixtures-retain-deleted-storage-and-turn-shapes |  |  |
| `d39148f53be7` | turn-loop-regressions-still-expect-retired-state-and-time-shapes |  |  |
| `81441d73bd10` | two-keyword-value-members-are-mistaken-for-one-lookup-ref |  |  |
| `2d31c42922d2` | vendor-parinferish-under-reference-code |  |  |
| `6cdc83942db4` | vendored-transit-clj-drifts-from-the-pinned-artifact |  |  |
| `a1f01e750ca7` | virtual-loop-fixture-submission-can-race-an-armed-turn |  | `seon.loop-proof-test/virtual-loop-end-to-end` |
| `e18c61e7f53e` | web-debug-fixture-transactions-and-budget-expectations-fail |  |  |

## Stale, obsolete and duplicate (not ingested)

| id | name | tier | verdict | why |
|---|---|---|---|---|
| `5c12a2f8fdf4` | agent-contracts-refuse-nil | a | stale | fixed dcd76fc21 (2026-09-24): :nil admitted in agent contracts, contract-error! accepts a symbol; covers the core-contract re-entry defect too. Residue unverified: the `[:= nil]` not-EDN-readable claim |
| `069f1ee0a72e` | branch-sci-namespace-lacks-file-aliases | a | stale | fixed 0dce6e31c (every program namespace row installs its aliases and imports) and 4f880a823 (aliases made outside the ns form) |
| `b66b5d05f782` | captured-prompt-history-disabled | a | stale | fixed 79c106925 (retain captured prompts in temporal history): no :seon.db/no-history? remains in seon.context schemas |
| `cc8f897d9d26` | dependent-shape-fingerprints-stale | a | stale | fixed a0d2db39a + 757bd7004 + 7610ba9e9 (open-items RESOLVED 2026-09-25 03:30Z); residue: 39 referenced shapes whose own row is correct are not healed |
| `9a05cf9b8e18` | evaluation-defines-outside-the-entry-gate | a | stale | fixed 8a531008a (2026-09-24): refuse-uncontracted-definitions! reads definition-rows matched to the Vars SCI changed; (do (defn)), (let (defn)), (def f (fn)) and macro expansions are gated and armed |
| `0b8fa3dd143e` | indexer-resolves-declaration-world-per-row | a | stale | note's own "Resolved 2026-09-16" section: landed as designed after 28f1a761e; counts re-measured; status never moved to archive |
| `02dc23245db2` | orphan-development-helpers-banner | a | stale | src/seon/schema.clj no longer carries the ;;; Development Helpers banner (only script/seon/dev/{markdown,docstring}.clj do) |
| `5d5af9b94507` | reserve-cluster-dead-family | a | obsolete | subject deleted: no reserve-cluster! remains in src/ |
| `5aa2550e6b69` | restore-core-any-some-guard | a | obsolete | superseded by ruling 10.1 and a1f1d80ce: an undefined :any/:some slot is advice at every entrance, never a refusal |
| `7440d6522748` | retention-refusal-lacks-error-at | a | stale | fixed 2b1027b5d (issue-test retention refusal returns as a write refusal, not an unknown outcome) |
| `51be0e13722b` | settle-is-public-without-a-contract | a | obsolete | subject deleted: src/seon/cluster/loop.clj no longer exists |
| `2034426bf3bc` | uncontracted-redefinition-drops-the-contract | a | stale | fixed 8a531008a: a definition without :malli/schema is refused and the prior root and metadata restored |
| `b7aeefb9b45d` | update-the-plan-artifact | a | obsolete | 5.11: the plan becomes a rendered view of task data; the hand artifact is retired by this migration |
| `e76a61f78c11` | a-declared-render-pair-on-an-all-optional-map-is-never-selected | b | stale | selection fixed 14a9291ad (note's 2026-09-24 section); only the live adoption gap remains |
| `1ac8a0ef79bc` | a-focused-test-jvm-spends-thirty-seconds-before-its-first-test | b | obsolete | the separate focused test JVM was replaced by in-process test requests (1.3d, bin/test-check default) |
| `40d003f49f26` | a-focused-test-jvm-spends-twenty-seconds-before-its-first-test | b | duplicate | duplicate of a-focused-test-jvm-spends-thirty-seconds-before-its-first-test |
| `d7c235e36641` | a-nonexistent-test-namespace-crashes-the-coordinator-instead-of-a-typed-tally | b | obsolete | every cited definition is absent at HEAD: seon.test.runner/run-coordinator! |
| `3d3f8d874030` | a-platform-test-leaves-its-worker-stripped-of-every-contract | b | obsolete | every cited definition is absent at HEAD: seon.test.runner/reassert-contracts! |
| `0ab33b31e9bc` | about-identity-resolution-logs-an-error-per-non-matching-attribute | b | obsolete | every cited definition is absent at HEAD: seon.render.transcript-test/every-generated-history-is-ordered-and-total, seon.render.transcript/about-identities, seon.render.transcript-test/about-identity-resolution-pulls-one-deterministic-ordered-id-vector |
| `e678f41b1400` | an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry | b | obsolete | every cited definition is absent at HEAD: seon.cluster/source-change-phases |
| `8b97fbe9a389` | background-binary-settlement-does-not-publish-required-event | b | obsolete | every cited definition is absent at HEAD: seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold |
| `50a3e835688e` | cluster-handle-retains-old-environment-projection-after-adoption | b | obsolete | every cited definition is absent at HEAD: seon.instrument/supplied-projection |
| `7da7a2f73733` | cohost-start-races-the-reachability-sweep | b | obsolete | every cited definition is absent at HEAD: seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation, seon.cluster/start! |
| `328f4a9f6227` | dead-process-census-identities-require-live-observation-fields | b | obsolete | every cited definition is absent at HEAD: seon.maintenance/project-process-census-result, seon.maintenance-test/result-projection-is-declared-and-keeps-census-evidence-queryable |
| `b5aafb8ee82c` | development-adoption-cannot-load-test-support | b | obsolete | every cited definition is absent at HEAD: seon.test/check |
| `191aef1d6708` | error-observation-aliases-inherit-native-uniqueness | b | obsolete | every cited definition is absent at HEAD: seon.schema-test/error-facets-and-their-owned-members-are-storable |
| `d0f24b63cb33` | final-report-validation-runs-unbounded-on-the-writer-thread | b | obsolete | every cited definition is absent at HEAD: seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows |
| `4946f9fc87de` | fixture-branch-keywords-are-not-readable-edn | b | obsolete | every cited definition is absent at HEAD: seon.render-simplification-test/nested-ai-values-retain-data-and-html-uses-declared-faces |
| `09d9bf063132` | fixture-namespace-rows-lack-the-required-definition-digest | b | stale | open-items: namespace rows DONE, 31 digest reds to 0 (53ae022b2 landing) |
| `a70052a1b449` | fresh-cljc-files-are-jvm-only | b | stale | note's "Resolution evidence" lists the renames; verify no false .cljc claimant remains |
| `692dd070f336` | history-policy-refusal-test-is-load-flaky | b | obsolete | every cited definition is absent at HEAD: seon.cluster.boot-test/operator-root-history-policy-is-creation-fixed |
| `267279584982` | hook-quiet-window-assumptions-survive-immediate-drain | b | obsolete | every cited definition is absent at HEAD: seon.dev.hook-test/idle-edit-starts-without-quiet-delay |
| `7a28e138f3d8` | instant-vector-admission-and-instrumentation-disagree | b | obsolete | every cited definition is absent at HEAD: seon.test.runner/claim-member |
| `c3846b7ae586` | mcp-exception-projection-is-opaque-after-the-kind-removal | b | stale | note's own "Resolved — 2026-09-21" section (510a9236d) |
| `e75db23e6292` | platform-tier-rejects-small-publication-fixture-observations | b | obsolete | every cited definition is absent at HEAD: seon.test.runner-test/the-canonical-platform-tier-preserves-file-local-uncertainty, seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate |
| `dc3bef2ae3bc` | priming-indexes-with-the-live-jvms-loaded-code | b | stale | note's own "Historical resolution" section (2026-07-30 static publication) |
| `e11a0704a821` | prompt-tests-retain-incompatible-turn-fixtures | b | obsolete | every cited definition is absent at HEAD: seon.cluster.prompt-test/a-held-run-without-a-trigger-refuses |
| `55830273b864` | provider-reference-and-price-schedule-drift | b | obsolete | every cited definition is absent at HEAD: seon.ai/thinking-inert-settings |
| `1c4e7757f7d0` | reap-dead-roots-calls-delete-recursively-with-a-nil-path | b | obsolete | every cited definition is absent at HEAD: seon.operator/reap-dead-roots! |
| `b1da59f8751b` | render-fixtures-dump-context-on-stale-assertions | b | obsolete | every cited definition is absent at HEAD: seon.test-runner-test/gate-completions-travel-as-a-file-not-as-code |
| `57c5754766ec` | retired-form-projection-still-declared-and-selected | b | obsolete | every cited definition is absent at HEAD: seon.render/schema-stage |
| `4d83595ff105` | running-fixture-regression-exceeds-in-process-bound | b | obsolete | every cited definition is absent at HEAD: seon.turn/refused |
| `da2c1af3a1f1` | schema-source-provenance-accumulates-in-a-global-atom | b | obsolete | every cited definition is absent at HEAD: seon.schema.edn/!source-files |
| `85cf50c66509` | scratch-boot-cannot-load-the-cluster-namespace | b | obsolete | every cited definition is absent at HEAD: seon.test.cache/classpath |
| `837db733e8ff` | stop-contract-rejects-stopped-instances | b | obsolete | every cited definition is absent at HEAD: seon.cluster/stop! |
| `3dfafd4037ab` | test-coordinator-label-attributes-worker-startup-to-analysis | b | obsolete | every cited definition is absent at HEAD: seon.test.runner/run-coordinator! |
| `708ee4ed381f` | test-launcher-fixtures-omit-required-helpers | b | obsolete | every cited definition is absent at HEAD: seon.test-runner-test/selected-paths-overlay-head-for-preparation-and-every-worker |
| `5a9aa6ac3b4e` | test-recording-work-exceeds-claim-deadlines | b | obsolete | every cited definition is absent at HEAD: seon.test.runner-test/no-double-execution, seon.test.runner/claim-member |
| `3b01885968a0` | the-debug-page-poller-observes-adoption-stages-twice | b | obsolete | every cited definition is absent at HEAD: seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters |
| `1c9dd1b696c3` | the-over-bound-evaluation-path-returns-a-lookup-ref-where-its-contract-promises-a-string | b | stale | note's own "Resolved — 2026-09-17" section |
| `6312c9569cf8` | the-test-reporter-attributes-a-failing-is-to-its-enclosing-let-binding-line | b | obsolete | every cited definition is absent at HEAD: seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows |
| `934bb782c799` | two-turn-backstops-fire-and-the-sliding-fault-channel-keeps-the-wrong-one | b | obsolete | every cited definition is absent at HEAD: seon.cluster.agent-test/disarm-has-a-declared-loud-turn-completion-backstop |

**Notes outside `archive/` whose status is not `open`** (the issue owner moves them; several carry statuses
`seon.issue` refuses):

- `a-lookup-ref-pull-sorts-the-whole-installed-schema-on-the-happy-path` — status `resolved`
- `affected-callers-fail-to-interpret-against-uninstalled-bindings` — status `resolved`
- `agent-custody-defaults-to-the-cluster-label-branch` — status `fix implemented; fixture sweep pending`
- `agent-printed-output-has-no-storage-bound` — status `resolved`
- `analyzer-refuses-a-checkout-file-outside-source-roots-as-a-foreign-kondo-entry` — status `resolved`
- `connection-identity-throws-on-a-released-connection` — status `fixed`
- `core-async-flow-stop-drops-proc-errors` — status `open (upstream; not forked)`
- `datahike-bound-attribute-variable-scans-the-store` — status `resolved (datahike fork f4614c9d + 620721d6; Seon workaround retired)`
- `db-test-edn-codec-fixture-writes-are-refused` — status `resolved (fixture built wrong: with-codec-database now writes its schema rows, 9b5eefbb7)`
- `development-adoption-leaves-the-loaded-program-at-the-boot-commit` — status `closed`
- `diagnostic-fields-make-generated-error-reads-look-like-turn-activity` — status `fix implemented; fixture and live proof pending`
- `gitlink-pins-refuse-every-from-zero-publication` — status `closed`
- `incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives` — status `fixed-pending-default-reload`
- `issue-test-retention-refusal-escapes-as-outcome-unknown` — status `fixed-unverified`
- `llm-provider-skill-names-retired-default-model` — status `closed`
- `or-contract-refusal-message-names-an-unrelated-alternative` — status ``
- `prepare-head-base-reads-the-live-checkouts-resources` — status `resolved`
- `process-test-term-trap-marker-is-intermittently-absent` — status `resolved`
- `publication-analysis-reads-a-stale-kondo-cache-entry-for-an-unindexed-caller-target` — status `closed`
- `read-evidence-currency-hands-a-nil-source-to-index-evidence` — status `resolved`
- `reader-ns-conform-allocates-per-namespace-form` — status `resolved (file ns context from clj-kondo analysis, 9bba9e000; the reader conform serves REPL forms only)`
- `restart-needed-refuses-the-publication-a-restart-should-make` — status `closed`
- `restorable-node-has-no-caller-after-the-shown-text-cut` — status `resolved`
- `runtime-lint-does-not-resolve-namespace-aliases` — status `resolved`
- `save-gate-runs-the-previous-compiled-test-body` — status `resolved (da9910fec: a changed :core test is host-bound, reloaded before its gate runs)`
- `the-edit-hook-load-files-the-contract-checker-into-default` — status `closed`
- `the-o1-bootstrap-drive-test-is-red-at-head` — status `resolved`
- `the-writer-admits-a-definition-digest-its-row-does-not-produce` — status `fixed-unverified`
- `turn-writers-upsert-bare-namespace-rows-without-a-definition-digest` — status `resolved`
- `unstored-panic-prints-whole-throwable-map-to-stderr` — status `fixed`
- `vendored-babashka-process-carries-a-local-aot-patch` — status `closed`
- `wake-delivery-contract-confuses-registration-key-with-agent-id` — status `fix implemented; recorded integration pending`
- `whole-program-index-rows-overflow-on-an-agent-steward-cycle` — status `resolved`

**open-items entries that are already open issue notes** (one task each, the note's):

| open-items entry | note | task id |
|---|---|---|
| Subprocess descendants die of SIGPIPE (queued) | `subprocess-descendants-die-of-sigpipe-after-the-root-exits` | `d30208fb49c3` |
| find-var on a referred Var empties the drift snapshot (queued) | `runner-live-cluster-snapshot-reads-a-referred-var-with-find-var` | `c40e6377d9c6` |
| A stored :or attribute drops its children's nil refusal (queued) | `a-stored-or-attribute-drops-its-childrens-nil-refusal` | `25f877d8efb9` |
| Publication refuses while the candidate branch holds a connection (queued) | `publication-refuses-while-the-candidate-branch-holds-a-connection` | `7a1a378fa150` |
| Test worlds inherit the live cluster's errors and messages (queued) | `test-fixture-worlds-inherit-the-live-clusters-errors-and-messages` | `a4b57a043d26` |
| Node-not-found: published head references unwritten index nodes | `published-head-references-never-written-index-nodes` | `d41edc3b163c` |
| script/ namespaces loaded by the JVM are not published | `script-namespaces-loaded-by-the-jvm-are-not-published` | `92f16abe2362` |
| Full reanalysis over 10 s (authorized until cheap) | `a-full-reanalysis-publication-takes-twenty-five-seconds` | `a9f45596c31e` |
| A lane's held dependent refuses an unrelated adoption (class 4) | `a-db-adoption-refuses-while-another-lane-edits-a-dependent` | `0db781dd2d7a` |
| Agent graphs keep stepping after their test branch's store closes (harness 2b) | `an-agent-graph-steps-after-its-test-branch-store-closes` | `18e91d89fb6e` |
| SCI arming guesses the cluster from a database row (projection passed explicitly) | `sci-arming-guesses-the-cluster-from-a-database-row` | `fc25260a871b` |
| seon.sci.reader-test 19 reds, symbols as strings | `reader-test-expects-declaration-symbols-as-strings` | `5802b465c293` |

## `:seon/tasks` annotations to add (tier a, actionable)

Write each id as the string literal chosen by slice 1b. A bare namespace subject means the producer is known only to
namespace precision: narrow it to the defining form before annotating. A schema key takes the annotation in its
schema properties (7.2). The data file takes none; it is named in the task's EDN (7.4).

| subject | ids | tasks |
|---|---|---|
| `:seon.context/selection-agent-id` | `"ef21da77d79e"` | error-members-reuse-another-entitys-identity |
| `:seon.message/unknown-recipient` | `"ef21da77d79e"` | error-members-reuse-another-entitys-identity |
| `:seon.test.runner/default-cluster-refused` | `"ef21da77d79e"` | error-members-reuse-another-entitys-identity |
| `:seon.test.runner/long-test-ns-hook` | `"ef21da77d79e"` | error-members-reuse-another-entitys-identity |
| `:seon.turn/compaction-agent-id` | `"ef21da77d79e"` | error-members-reuse-another-entitys-identity |
| `my.program/native-call-refusal` | `"bdc132dc3601"` | native-ns-unmap-bypasses-the-deletion-authority |
| `seon.blob/stage!` | `"9b16848cd6dc"` | interrupted-staging-leaves-no-artifact |
| `seon.blob/stage-binary!` | `"9b16848cd6dc"` | interrupted-staging-leaves-no-artifact |
| `seon.cluster` | `"0d9a80e83918"` | publication-half-applies-on-a-red-save-gate |
| `seon.cluster.agent/acquire-context!` | `"7fb495b9c18f"` | acquire-context-holds-the-contexts-monitor |
| `seon.cluster.agent/creation-tx` | `"aded32b7d587"` | creation-tx-refuses-fixture-branches |
| `seon.cluster.boot` | `"776a0901a48b"` | start-runs-a-program-that-differs-from-the-checkout |
| `seon.cluster.source/classify-paths` | `"14c9974e639d"` | reference-code-gitlinks-demand-a-jvm-restart |
| `seon.cluster.source/prepare-merge!` | `"62434adb50af"` | merge-refuses-added-definitions |
| `seon.cluster/full-source-refresh!` | `"3eea9d2e5c9f"` | changed-path-publication-reads-every-schema-resource |
| `seon.cluster/mcp-project` | `"b21b5249b19c"` | mcp-value-window-inherits-agent-budget |
| `seon.db` | `"2606a3d8c3dc"` | as-of-reads-rebuild-their-projection |
| `seon.db/arity-content-key` | `"e54c81ba2ece"` | arity-check-rebuilds-whole-program-input-per-branch |
| `seon.db/write-entity-schemas` | `"4057bc69c686"` | writer-selects-entity-schemas-by-required-identity |
| `seon.dev.citations` | `"5972b7b2c5b5"` | script-subprocess-waits-declare-no-bound |
| `seon.dev.dependency-digest` | `"5972b7b2c5b5"` | script-subprocess-waits-declare-no-bound |
| `seon.dev.markdown/run-rules` | `"97e1584a6944"` | pin-check-reads-dated-landings-as-living |
| `seon.dev.markdown/validate-repository-pins` | `"97e1584a6944"` | pin-check-reads-dated-landings-as-living |
| `seon.dev.mcp` | `"cb4a295857eb"` `"bb280604ad6e"` `"870fffb9f70b"` | mcp-jvm-eval-loses-reader-fidelity, mcp-eval-echoes-every-kondo-stderr-line, mcp-entrance-acquires-branches-without-an-agent |
| `seon.dev.mcp/branch-errors!` | `"692ad2dd692e"` | new-mcp-branch-lists-inherited-errors |
| `seon.env/supplied-agent-id` | `"1aa87aba9996"` | supplied-agent-id-refusal-carries-the-whole-environment |
| `seon.error/commit-tx` | `"8eaeb939edaa"` | capped-faults-recorded-without-a-blob |
| `seon.fault/record!` | `"7ca365eee2de"` | member-branch-faults-are-not-test-errors |
| `seon.fn` | `"e21685e7b139"` | contracts-derive-from-destructured-keys-and-return-tags |
| `seon.fn.analyzer/analyze-dependencies!` | `"330dce00c141"` | kondo-dependency-cache-empty-after-restart |
| `seon.fn/gate-set` | `"300aca1ad57c"` | tests-reaching-saturates |
| `seon.fn/tests-reaching` | `"300aca1ad57c"` | tests-reaching-saturates |
| `seon.fn/var-row` | `"079fd5cfd9db"` | function-contract-is-a-printed-string |
| `seon.instrument` | `"fbfdeaaeadbd"` | script-and-dev-namespaces-are-never-armed |
| `seon.maintenance/project-cluster-cleanup-result` | `"ef4b045c0521"` | cleanup-result-projection-has-no-producer |
| `seon.maintenance/with-root-store!` | `"a87f1435f925"` | maintenance-opens-a-store-outside-the-refcount |
| `seon.operator/connected!` | `"5972b7b2c5b5"` | script-subprocess-waits-declare-no-bound |
| `seon.program/declaration-row` | `"a060468a26a0"` | declaration-row-output-refusal-names-the-wrong-member |
| `seon.render.web/mult-generator` | `"281db700d724"` | opaque-generators-share-live-objects |
| `seon.render.web/server-generator` | `"281db700d724"` | opaque-generators-share-live-objects |
| `seon.schema` | `"22e85ffcf5bf"` | agents-can-validate-a-value-against-a-key |
| `seon.schema/canonical-value-string` | `"0eeeaf250ac1"` | root-idle-prompt-takes-five-seconds |
| `seon.schema/register!` | `"7c6c87b319f1"` | schema-declaration-cannot-reach-a-branch |
| `seon.sci.eval/ctx-generator` | `"281db700d724"` | opaque-generators-share-live-objects |
| `seon.sci.eval/install-function-contract!` | `"079fd5cfd9db"` | function-contract-is-a-printed-string |
| `seon.sci.reader/function-declaration` | `"079fd5cfd9db"` | function-contract-is-a-printed-string |
| `seon.test` | `"7ca365eee2de"` `"e15dd8259040"` `"474ae4c203dd"` `"80a093412a7f"` | member-branch-faults-are-not-test-errors, test-bound-covers-only-the-body, refused-members-count-red-not-unknown, a-seed-redefining-its-test-runs-the-old-var |
| `seon.test.accretion/generatable?` | `"2b3b45973e8e"` | generatable-dominates-a-hub-schema-edit |
| `seon.test.runner` | `"f9fadea8c1b6"` | reach-leaves-omit-fixture-metadata |
| `seon.test.runner/failure-identity` | `"6c2e42bf883c"` | runner-failure-report-prints-whole-values |
| `seon.test.runner/failure-report` | `"6c2e42bf883c"` | runner-failure-report-prints-whole-values |
| `seon.test/failure-text` | `"eab0fb04d4fc"` | failure-text-prints-an-unfollowable-blob-hint |
| `seon.turn/latest-answering-turn-t` | `"ad1005fac9fd"` | latest-answering-turn-t-passes-a-refusal-as-an-int |
| `test/seon/schema/datahike_parity.edn` | `"a0edc46ee6a3"` | datahike-parity-baseline-is-hand-maintained |

Tier b would annotate 412 distinct cited subjects, but only after triage names the producers.

## Docstring must-read and see-also additions

These are for the tier-a subjects whose worker needs a pointer that the call graph does not already give
(`:seon/must-read` for reading first, `:seon/see-also` for siblings). Write them as quoted, fully qualified symbols
in the attribute map (1.5).

| definition | `:seon/must-read` | `:seon/see-also` |
|---|---|---|
| `seon.cluster.source/prepare-merge!` | `'seon.program/three-way` | `'seon.cluster.source/accept-merge!` |
| `my.program/native-call-refusal` | `'my.program/ns-unmap!` | `'my.program/remove-ns!` |
| `seon.cluster.source/classify-paths` | `'seon.cluster.source/loaded-dependencies` | — |
| `seon.cluster/mcp-project` | `'seon.render/agent-render-profile` | — |
| `seon.test/failure-text` | `'seon.blob/get` | — |
| `seon.fn/tests-reaching` | `'seon.fn/reverse-closure` | `'seon.fn/gate-sets`, `'seon.fn/gate-set` |
| `seon.fn/var-row` | `'seon.program/declaration-row` | `'seon.sci.reader/function-declaration`, `'seon.sci.eval/install-function-contract!` |
| `seon.schema/register!` | `'seon.schema/declaration-population` | `'seon.schema/unregister!` |
| `seon.db/write-entity-schemas` | — | `'seon.program/declaration-row` (the `:seon.program/row-schema` owner) |
| `seon.render.web/server-generator` | — | `'seon.render.web/mult-generator`, `'seon.sci.eval/ctx-generator`, `'seon.cluster/socket-server-generator` (the fixed sibling) |
| `seon.dev.markdown/validate-repository-pins` | — | `'seon.dev.markdown/run-rules` |
| `seon.maintenance/with-root-store!` | `'seon.cluster.registry` (the refcount owner) | — |

Two docstrings teach something false today and are corrected in the same slice as their task:

- `seon.test/failure-text`'s printed `(seon.blob/get digest)` hint;
- `my.program/ns-unmap!`'s claim to be the deletion path, which a native `ns-unmap` bypasses.

## First batch (about ten, right after slice 2)

Each has one producer read in source and a proof that is either an existing red regression or an existing
regression that gains one assertion, red on the base for its own reason (3.9, 3.10). None needs another task.

| id | name | subjects | proof |
|---|---|---|---|
| `300aca1ad57c` | tests-reaching-saturates | `seon.fn/gate-set`, `seon.fn/tests-reaching` |  |
| `bdc132dc3601` | native-ns-unmap-bypasses-the-deletion-authority | `my.program/native-call-refusal` | `my.program-mutation-test/native-program-mutations-refuse-at-the-installed-hook` |
| `14c9974e639d` | reference-code-gitlinks-demand-a-jvm-restart | `seon.cluster.source/classify-paths` | `seon.cluster.publication-inputs-test/publication-classifies-configuration-and-loaded-dependencies` |
| `1aa87aba9996` | supplied-agent-id-refusal-carries-the-whole-environment | `seon.env/supplied-agent-id` |  |
| `ef4b045c0521` | cleanup-result-projection-has-no-producer | `seon.maintenance/project-cluster-cleanup-result` | `seon.maintenance-schema-test` |
| `97e1584a6944` | pin-check-reads-dated-landings-as-living | `seon.dev.markdown/run-rules`, `seon.dev.markdown/validate-repository-pins` | `seon.dev.markdown-test/repository-dependency-pins-are-current-test` |
| `9b16848cd6dc` | interrupted-staging-leaves-no-artifact | `seon.blob/stage!`, `seon.blob/stage-binary!` | `seon.blob-test/interrupted-staging-leaves-the-store-unchanged` |
| `281db700d724` | opaque-generators-share-live-objects | `seon.render.web/mult-generator`, `seon.render.web/server-generator`, `seon.sci.eval/ctx-generator` | `seon.public-contract-test/lifecycle-generators-make-a-fresh-sample-each-generation` |
| `b21b5249b19c` | mcp-value-window-inherits-agent-budget | `seon.cluster/mcp-project` | `seon.cluster.mcp-test/nested-bulk-is-bounded-by-the-shared-value-window` |
| `5972b7b2c5b5` | script-subprocess-waits-declare-no-bound | `seon.dev.citations`, `seon.dev.dependency-digest`, `seon.operator/connected!` | `seon.bounded-boundary-census-test/every-foreign-subprocess-boundary-declares-a-bound` |

What the proof needs, per task:

- **pin check, blob staging, MCP window, subprocess bounds:** the named test is red today, according to its note.
  Verify it red on the base first.
- **native `ns-unmap`:** add `clojure.core/ns-unmap` to `native-program-mutations-refuse-at-the-installed-hook`.
- **generators:** add `seon.render.web/server-generator`, `mult-generator` and `seon.sci.eval/ctx-generator` to
  `lifecycle-generators-make-a-fresh-sample-each-generation`. It covers only flow and socket samples today.
- **gitlinks:** add a changed `reference-code/<x>` gitlink that is off the classpath to
  `publication-classifies-configuration-and-loaded-dependencies`. It must stay `:selected`.
- **cleanup-result:** the proof is absence. Delete the function and its two uses in `seon.maintenance-schema-test`.
- **tests-reaching:** needs one bounded regression (a core function's reach, under a declared bound). This is the
  seed.
- **supplied-agent-id:** needs one assertion that its refusal's `:seon.error/offending` is the supplied value, not
  the environment.

Batch two, next by readiness:

- `function-contract-is-a-printed-string` (`079fd5cfd9db`), after the derived-contract decomposition
  (`e21685e7b139`);
- `merge-refuses-added-definitions` (`62434adb50af`), which gates `cheap-environments-and-collaboration` and
  write-back;
- `schema-declaration-cannot-reach-a-branch` (`7c6c87b319f1`);
- `writer-selects-entity-schemas-by-required-identity` (`4057bc69c686`);
- `failure-text-prints-an-unfollowable-blob-hint` (`eab0fb04d4fc`).

## Method and limits

A scripted pass read all 481 notes. It extracted frontmatter, title, backticked qualified symbols, repository
paths and cited fix commits. It checked each symbol against a definition index of `src/`, `test/`, `script/`,
`dev/` and `resources/seon/operator/` (7,629 definitions), each path against the checkout, and each commit with
`git cat-file`.

Duplicates were found in two ways:

- **mechanically:** title Jaccard ≥ 0.45 or three or more shared live subjects, which gave two candidate pairs;
- **by hand:** tier a against the notes, and open-items against the notes.

"Open" follows `seon.issue` (`note-path?`, `issue.clj:124–134`, and the status check at `:374`). The scripts are
disposable and not committed; the extraction is reproducible from this description. The plan steps were taken from
README §4 "Where we are" and "What is left". Their lane specs were not read in full: each is one decomposition
task citing its spec.

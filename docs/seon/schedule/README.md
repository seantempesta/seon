# Schedule (single plan; rewritten 2026-10-07 by the tasks-inventory lane)

Open work is Seon tasks (resources/seon/tasks/<ns>.edn; runtime-only tasks die with the default reset). Every task is
triaged once in `tmp/orchestrator/triage.tsv` (ns, name, bucket, phase/slice, has-proof-test, source, reason). Every
problem below appears exactly once: as a task in one phase, or as an action/decision with no task.

**Counts (347 tasks = 269 file + 70 runtime-only on default + 8 new; 56 runtime-only persisted to files today):**
A errors-spec slice 47 · B agent-loop slice 23 · C obsolete/closes-with 44 · D open, phased 227 · E owner decision 6.
Proof tests: 24 tasks are named by a deftest (`:seon/tasks`), 49 name a test only in prose, 274 have none.

## Goals (owner rulings 2026-10-07)

- Live agents solving real tasks so we can experiment: first live DeepSeek (deepseek-flash) task at the end of Phase 4.
- Errors: one error map (type, operation, message; `:multi` variants), one constructor, returned or thrown as ex-info and
  taken back once at the boundary; instrumentation from malli's report. **No separate "fault" concept**: Seon's own
  failures are errors, recorded once per signature with a count and a task; the fault vocabulary (seon.fault,
  :seon.fault/*, "open-fault") is retired by the errors cut.
- Agent data on the agent entity; no turn or run entities; context rendered live through the one seon.cache.
- Tasks are self-contained and test-proven: **a task launches only with a definition of success (named tests)**;
  completion is test-based (after a pass that changed code the task's tests reach, run them; all green = done).
- Fewer, better tests; docs live in the code; 10k lines is direction, not a hard requirement.

## Process rules

- **No rushing.** Each lane writes a design check (did the spec hold against real code? what was awkward?) before
  committing and STOPS to report when the design looks wrong; design surprises go to the owner as options.
- **Dual review** of every slice, spec and guide: one fresh-context Opus agent + `LANE_MODEL=gpt-6-astra LANE_EFFORT=high
  bin/codex-agent run …` with the identical prompt; reconcile, verify findings against code, then report.
- Lanes prove their slice with `bin/lane commit LANE -m … --test NS… PATHS`; root batches restarts and broad tiers.
- **Bucket-D tasks with has-proof-test = no/unknown get their proving test written (red) as their first step**, named
  in the task, before the fix is launched. New tasks name their proof test in the intent.
- Fix rule: problems on code you touch: fix if ~15 min, else create/link a task (`ns/task-name`) and cite it in the
  docstring. Never hand-type task ids.

## Phase 0 — cleanup (no code changes)

- Retire throwaway agent `loop-s1-probe` (branch `:agent-a44a0509958c`) from root and unmap `user/probe-*` Vars in
  default's JVM (tmp/agent-loop/s1-notes.md). If retire refuses, that is my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed (P6).
- Delete the untracked file whose name contains a newline:
  `docs/research/agent-platform/landing/analysis-content-2026-09-28.md\ndocs/` (inspect it first).
- Lane errors-s1 still claims 22 files: release after S1b lands (or root releases now and S1b re-claims).
- Close the already-fixed / already-deleted tasks listed under "C → P0-close" below, each after its stated check.

## Phase 1 — guide, CLAUDE.md, specs approval

- Clojure guide revision (tmp/clojure-guide/*), incl. reference-code.md seam rows unverified/stale (guide-notes §d): handled by the guide revision, no task.
- Owner approves errors.md and agent-loop.md revisions (another agent is revising them now), incl. the S2 re-slicing (see E) and loop-S1 notes §3b-e (settlement helpers, fault windows and folded repeats not named in §2.1, calibration change in prompt.clj, origin name collision).
- Census note: agent-loop spec §4 converts eval/drive.clj, but census §7 finds its episode driver dead: delete instead (task seon.eval.drive/unreachable-definitions-remain-in-src).
- Dual review of the revised specs before Phase 2 launches.

## Phase 2 — errors S1b, then the remaining prep slices

First classify the reds so slices can name whole namespaces, then S1b, then S2..S7 per errors.md §7 (S2 re-sliced per owner).
- `seon.test/red-tests-on-head-are-unclassified` [proof: unknown] **new**

### S1b (2)

- `seon.error/a-converted-error-keeps-its-transient-type` [proof: unknown] **new**
- `seon.error/s1-left-metadata-readers-and-stamps-in-three-test-files` [proof: unknown] **new**

### S2 constructor sweep (8)

- `seon.db/the-missing-connection-refusal-has-no-fix-key` [proof: no]
- `seon.error.refusal/a-refusal-omits-the-data-that-explains-it` [proof: no]
- `seon.error.refusal/errors-reference-worlds-never-copy-them`
- `seon.error.refusal/hand-built-error-maps-remain-across-the-namespaces` [proof: no]
- `seon.fn/dependency-analysis-error-is-not-an-error` [proof: no]
- `seon.sci.eval/shown-result-ignores-the-floor-refusal` [proof: no]
- `seon.test-support/refusal-data-recognizes-errors-by-at-and-layer` [proof: no] **new**
- `seon.turn/refusal-schemas-are-guessed-by-key`

### S3 boundary (8)

- `seon.cluster.agent/a-wrapped-stop-interrupt-is-recorded-as-a-core-fault` [proof: no]
- `seon.error.refusal/preserve-primary-and-suppressed-causes`
- `seon.fault/carried-receipt-hangs-on-cyclic-cause-chain` [proof: no]
- `seon.render.web/an-unreportable-page-failure-is-only-a-log-line` [proof: no]
- `seon.sci.eval/deliver-evaluation-errors-as-values`
- `seon.test/a-test-run-refusal-escapes-as-an-exception` [proof: no]
- `seon.turn/a-gate-exception-refuses-the-whole-run` [proof: no]
- `seon.turn/late-faults-accompany-evaluated-values`

### S4 reads throw (4)

- `seon.db/a-query-refusal-holds-a-parser-object` [proof: no]
- `seon.db/a-unique-rejection-message-is-the-raw-exception` [proof: unknown]
- `seon.db/q-lets-a-dependency-throw-escape` [proof: no]
- `seon.render.web/a-page-renders-with-a-refused-database` [proof: no]

### S5 instrumentation (9)

- `seon.error.refusal/refusal-offending-arguments-held-by-reference` [proof: no]
- `seon.error/a-missing-key-message-reads-badly` [proof: no]
- `seon.error/an-enum-refusal-prints-the-schema-properties` [proof: no]
- `seon.instrument/a-map-for-a-sequential-crashes-the-refusal-builder` [proof: unknown]
- `seon.instrument/an-oversized-refusal-hides-its-cause`
- `seon.instrument/contract-refusal-carries-wrapper-bookkeeping` [proof: no]
- `seon.instrument/violation-construction-is-quadratic-in-explain-errors` [proof: no]
- `seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema` [proof: no]
- `seon.schema/canonical-definition-cannot-invert-evaluated-roots`

### S6 errors recorded once per signature (fault machinery retired) (12)

- `seon.db/projection-fallback-prints-instead-of-returning` [proof: no]
- `seon.error/a-deleted-functions-fault-never-resolves` [proof: no]
- `seon.error/a-fault-notification-is-its-errors-edn-as-text` [proof: no]
- `seon.error/a-fixed-fault-with-an-answering-task-has-no-proof-path` [proof: no]
- `seon.error/deliver-core-faults-to-responsible-agent`
- `seon.error/error-transactions-carry-references-never-worlds` [proof: no]
- `seon.error/fault-observation-returns-the-recorded-error` [proof: no]
- `seon.error/health-stays-faulted-on-dead-process-faults` [proof: no]
- `seon.error/the-error-observation-pull-names-541-attributes` [proof: no]
- `seon.fault/fault-write-bound-is-wall-clock-under-contention` [proof: unknown]
- `seon.render.transcript/keep-fault-observations-historical`
- `seon.repl/every-reply-repeats-open-faults` [proof: no]

### S7 renders (4)

- `seon.db/render-rejection-ai-fails-its-own-input-contract` [proof: no]
- `seon.error/a-distance-zero-fault-render-omits-whether-it-is-open` [proof: no]
- `seon.error/refusal-html-reads-as-fragments` [proof: no]

## Phase 3 — agent-loop prep slices (S1-S4; S1 may start beside errors S1-S2, spec Q8)

- `seon.ai/deepseek-model-rows-and-dial-classification-are-stale` [proof: no] **new**

### S1 one real pass (10)

- `seon.cluster.prompt/a-later-wake-message-never-reaches-the-context` [proof: no]
- `seon.cluster.prompt/an-agents-context-omits-the-faults-mcp-shows` [proof: no]
- `seon.cluster.wake/wake-delivery-never-arrives-in-the-test-world` [proof: unknown]
- `seon.config/no-provider-cannot-be-lifted-per-agent` [proof: no]
- `seon.render/a-cold-prompt-renders-every-evaluation-again` [proof: no]
- `seon.sci.eval/agent-form-writes-name-an-unwritten-evaluation`
- `seon.turn/a-definition-is-settled-twice` [proof: no]
- `seon.turn/eval-origin-has-no-producer-or-consumer` [proof: no]
- `seon.turn/history-acquisition-decodes-stored-read-plans` [proof: no]
- `seon.turn/receipt-attributes-are-a-hand-list` [proof: no]

### S2 the proc (4)

- `seon.cluster.agent/an-escaped-pass-closes-the-reply-without-its-fault` [proof: no]
- `seon.turn/a-trivial-agent-turn-takes-eleven-seconds` [proof: no]
- `seon.turn/a-turn-pass-derives-its-work-twice`
- `seon.turn/an-mcp-run-of-root-waits-behind-roots-faulting-system-turn` [proof: no]

### S3 outside agents and tests (3)

- `seon.batch/a-jvm-run-answers-evaluated-but-not-recorded` [proof: no]
- `seon.cluster.agent/a-trivial-form-waits-on-two-durable-commits`
- `seon.mcp/observation-reads-the-run-twice` [proof: no]

## Phase 4 — the cut (errors + loop), default reset, first live DeepSeek task

Order: loop S4 readers -> reset default (spec Q2: stored evaluations without agent; error data reset, no migration) -> errors/loop deletion slice (loop S5, errors fault-machinery deletion) -> restart -> profile first forms (seon.sci.eval/forms-just-after-boot-cost-hundreds-of-ms) -> first live task on deepseek-flash, proven by its tests. Runtime-only tasks were persisted 2026-10-07; re-check `(my.task/open db)` for new runtime tasks before the reset.
Prerequisites:
- `seon.cluster/retiring-an-attribute-with-stored-component-data-refuses-adoption` [proof: no]
- `seon.test/a-test-world-has-no-agent` [proof: no]
- `seon.test/an-agent-branch-test-run-cannot-branch-its-fresh-checkpoint` [proof: no]

### Loop S5 deletion (6)

- `seon.db/the-checked-writer-is-slow-on-a-fresh-branch` [proof: no]
- `seon.turn/a-refused-refusal-settlement-throws-past-the-reply` [proof: no]
- `seon.turn/a-refused-settlement-is-stored-as-a-core-fault`
- `seon.turn/a-transient-write-refusal-parks-the-agent-until-restart` [proof: no]
- `seon.turn/close-turn-is-a-third-commit` [proof: no]
- `seon.turn/turn-continue-read-results-arrive-before-done-takes-six-seconds` [proof: no]

## Phase 5 — test policy: deletions and conversions (with-redefs, slow and red tests)

- `seon.ai/the-ai-test-captured-run4-reply-is-unread` [proof: unknown]
- `seon.cluster/a-test-adoption-moves-the-jvms-loaded-identity` [proof: unknown]
- `seon.cluster/incremental-publication-test-redefines-process-wide-vars` [proof: unknown]
- `seon.cluster/mcp-reads-the-jvm-wide-instance-roster` [proof: unknown]
- `seon.cluster/publication-tests-cost-35-seconds` [proof: unknown]
- `seon.db/the-datahike-parity-test-names-an-unadmitted-callable` [proof: unknown]
- `seon.documentation/documentation-test-reads-a-retired-doc-and-dir-shape` [proof: unknown]
- `seon.fn/build-artifact-is-a-second-indexing-path` [proof: no]
- `seon.fn/fn-test-redefines-vars` [proof: unknown]
- `seon.fn/refused-publication-reads-have-no-redefinition-free-regression` [proof: unknown]
- `seon.html-views/html-views-identity-and-fault-pairs-are-red` [proof: no]
- `seon.instrument/a-re-evaluation-test-leaves-a-replaced-root` [proof: unknown]
- `seon.instrument/a-test-redefines-arming-jvm-wide` [proof: unknown]
- `seon.profile/call-counters-are-jvm-wide-so-counting-tests-race` [proof: unknown]
- `seon.render.test/render-coverage-reads-the-retired-render-unknown-refusal-key` [proof: no]
- `seon.render.value/test-messages-print-whole-worlds` [proof: unknown]
- `seon.render.web/reconnect-wire-test-walks-the-whole-window` [proof: unknown]
- `seon.schedule/the-schedule-test-root-page-members-differ-after-a-firing` [proof: no]
- `seon.test/test-release-closes-branch-connections-out-of-order` [proof: no] **new**
- `seon.test/tests-redefine-default-vars` [proof: unknown]
- `seon.test/the-transitions-test-runs-six-to-seven-seconds-against-a-five-second-bound` [proof: no]
- `seon.turn/row-tx-is-a-second-declaration-writer` [proof: no]

## Phase 6 — agent REPL path and tools (my.*), from live-agent friction

- `my.agent/a-context-only-branch-read-is-not-supplied` [proof: no]
- `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` [proof: no]
- `my.fs/fs-read-ignores-limit-and-has-no-line-addressing` [proof: no]
- `my.program/a-merged-change-reaching-the-files-is-unproven` [proof: no]
- `my.program/a-one-line-change-resends-the-whole-definition` [proof: no]
- `my.program/an-agent-cannot-see-or-choose-its-change` [proof: unknown]
- `my.program/breaks-costs-seconds-on-a-hub` [proof: no]
- `my.program/breaks-plans-a-contract-change-as-a-retraction` [proof: no]
- `my.program/history-shows-the-source-twice-and-no-commit-id` [proof: no]
- `my.program/native-calls-are-refused-instead-of-routed` [proof: no]
- `my.program/ns-unmap-and-remove-ns-cost-quarter-thousand-tokens-and-most-of-a-second` [proof: no]
- `my.program/propose-refusal-names-no-next-step` [proof: no]
- `my.program/refusal-names-nonexistent-define` [proof: no]
- `my.program/the-merge-prepare-costs-three-seconds-and-400k-calls` [proof: no]
- `my.task/no-per-function-health-view-and-open-misses-over-bound-tasks` [proof: no]
- `my.task/task-creation-is-not-an-agent-call` [proof: no]
- `my.task/task-writes-are-untimed-and-refusals-are-hand-built` [proof: no]
- `my.task/the-queue-on-an-agent-branch-offers-tasks-claimed-on-the-cluster` [proof: no]
- `my.test/run-answers-a-whole-namespace-without-a-verdict` [proof: unknown]
- `my.test/run-owned-reply-repeats-its-output` [proof: no]
- `seon.ai/no-auth-refuses-at-complete` [proof: no]
- `seon.background/dogfood-followups-background-and-shell-weight` [proof: no]
- `seon.bootstrap/help-does-not-teach-how-a-test-names-a-task` [proof: no]
- `seon.bootstrap/help-teaches-the-wrong-tools` [proof: no]
- `seon.call-preparation/a-positional-database-is-not-supplied` [proof: no]
- `seon.call-preparation/acquired-functions-get-no-supplied-defaults`
- `seon.call-preparation/supply-reads-the-connection-head` [proof: no]
- `seon.effect/a-file-stat-costs-a-thousand-armed-calls` [proof: no]
- `seon.effect/background-poll-answers-edn-strings` [proof: no]
- `seon.fn/a-used-class-makes-a-row-host-bound` [proof: no]
- `seon.fn/agent-mistakes-read-as-missing-dependency-analysis` [proof: no]
- `seon.fn/contract-findings-has-no-scope` [proof: no]
- `seon.fn/host-bound-class-list-copies-interpreter-config`
- `seon.profile/no-per-symbol-profile-and-zero-calls-reads-as-unmeasured` [proof: no]
- `seon.profile/slow-explanation-blames-the-agent-for-platform-time` [proof: no]
- `seon.render.var/a-deftest-reply-shows-only-its-var` [proof: unknown]
- `seon.repl/a-jvm-def-is-told-it-lives-in-sci` [proof: no]
- `seon.repl/a-slow-reply-names-counts-not-proportionality` [proof: no]
- `seon.repl/a-slow-reply-names-nothing` [proof: no]
- `seon.repl/directory-render-returns-text-the-printer-clips-mid-form` [proof: no]
- `seon.repl/offered-operations-cannot-name-the-prior-result` [proof: no]
- `seon.repl/reply-map-is-not-edn-when-elided-or-rendered`
- `seon.schema/agents-can-validate` [proof: no]
- `seon.schema/register-works-only-top-level` [proof: no]
- `seon.sci.admit/large-printed-output-refuses-the-evaluation` [proof: no]
- `seon.sci.arm/changed-namespaces-flags-a-namespace-sci-merely-touched` [proof: no]
- `seon.sci.batch/the-fold-sci-stage-binds-no-output` [proof: no]
- `seon.sci.eval/a-refused-run-leaves-its-program-in-the-context` [proof: no]
- `seon.sci.eval/a-result-handle-is-bound-at-three-sites` [proof: no]
- `seon.sci.eval/acquisition-rebuilds-the-whole-program` [proof: no]
- `seon.sci.eval/an-agent-cannot-read-the-private-code-it-must-change` [proof: no]
- `seon.sci.eval/analysis-order-differs-from-evaluation-order` [proof: no]
- `seon.sci.eval/branch-change-measured-against-loaded-program` [proof: no]
- `seon.sci.eval/clojure-test-parity-residue` [proof: no]
- `seon.sci.eval/copied-jvm-import-hides-sci-import` [proof: no]
- `seon.sci.eval/copied-vars-pin-old-wrapper-generations` [proof: no]
- `seon.sci.eval/defmacro-in-let-loses-its-location` [proof: no]
- `seon.sci.eval/doc-and-dir-erase-request-keys` [proof: no]
- `seon.sci.eval/docstring-parts-reads-everything-after-example-as-the-example` [proof: no]
- `seon.sci.eval/forms-just-after-boot-cost-hundreds-of-ms` [proof: no]
- `seon.sci.eval/incremental-install-leaves-callers-on-the-jvm-definition` [proof: no]
- `seon.sci.eval/interpreted-rows-lack-own-namespace-class-imports` [proof: no]
- `seon.sci.eval/non-defn-definitions-store-no-entity` [proof: no]
- `seon.sci.eval/program-change-check-scans-the-whole-history`
- `seon.sci.eval/program-movement-is-checked-three-ways-per-form` [proof: no]
- `seon.sci.eval/repl-namespace-changes-drift-from-the-ns-text` [proof: no]
- `seon.sci.eval/replaced-and-acquisition-ms-have-no-producer` [proof: no]
- `seon.sci.eval/the-defn-reply-prints-before-its-own-change-applies` [proof: no]
- `seon.sci.kernel/sci-pmap-escapes-the-arm` [proof: no]
- `seon.sci.reader/fabrication-refusal-guards-every-reader` [proof: no]
- `seon.sci.reader/reader-head-check-is-a-second-declaration-derivation` [proof: no]
- `seon.turn/a-reload-between-forms-is-undetected` [proof: no]
- `seon.turn/an-agent-form-is-read-again-by-each-consumer` [proof: no]

## Phase 7 — render/web dedup and value printer; test runner and selection

Render: remove duplication, keep features (owner 2026-09-28). Future-spec candidates (no task, tmp/clojure-guide/map-render-web.md §4): test.check generators out of print.cljc/hiccup.clj (~-220), layout predictor -> flat width + fipp. Test runner (map-cluster-test-tools.md §3 #1-2): one result entity per (member, run) (~-700), one incremental selection rule (~-700).
- `seon.cluster.message/unresolved-sender-logs-unbounded` [proof: no]
- `seon.db/call-reach-is-recomputed-per-render` [proof: no]
- `seon.fn/functions-without-tests-is-the-last-datalog-reach` [proof: no]
- `seon.fn/gate-sets-map-arity-gets-a-supplied-database` [proof: no]
- `seon.fn/mention-closure-survives-beside-call-reach` [proof: no]
- `seon.fn/tests-reaching-saturates` [proof: no]
- `seon.instrument/instrumentation-is-jvm-global` [proof: no]
- `seon.mcp/entrance-stages-select-no-tests` [proof: no]
- `seon.operator/a-deep-check-queue-times-out-waiting-for-a-slot` [proof: unknown]
- `seon.operator/check-cannot-run-tests-of-host-bound-namespaces-against-a-candidate` [proof: no]
- `seon.operator/check-test-cannot-run-platform-tests` [proof: unknown]
- `seon.operator/check-test-reports-failed-for-an-excluded-long-test` [proof: unknown]
- `seon.operator/committed-source-same-sha-race` [proof: no]
- `seon.problems/ai-prose-returns-printed-text-not-data` [proof: unknown]
- `seon.render.test/a-failed-test-renders-in-28-ms` [proof: no]
- `seon.render.test/a-test-entity-shows-its-failures-twice` [proof: no]
- `seon.render.value/a-count-prints-as-an-entity-reference` [proof: no]
- `seon.render.value/a-cut-operation-in-render-data-is-not-runnable` [proof: no]
- `seon.render.value/a-lazy-seq-that-throws-shows-as-empty` [proof: no]
- `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk` [proof: no]
- `seon.render.value/an-uncut-string-node-carries-no-total` [proof: no]
- `seon.render.value/bounded-text-survives-in-the-render-namespace` [proof: no]
- `seon.render.value/continuations-cannot-navigate-derived-nodes` [proof: no]
- `seon.render.value/nested-hiccup-exceeds-the-printers-charge` [proof: no]
- `seon.render.value/nested-renders-bypass-the-floor` [proof: no]
- `seon.render.value/short-strings-in-pairs-are-cut` [proof: no]
- `seon.render.web/stale-debt-tags-remain-in-web-and-schema` [proof: no]
- `seon.render.web/the-render-cache-keeps-every-debug-view` [proof: no]
- `seon.render.web/the-web-worker-executor-is-never-shut-down` [proof: no]
- `seon.render/a-nested-declared-value-prints-as-an-object` [proof: unknown]
- `seon.render/a-render-against-another-database-reads-the-context-custody` [proof: unknown]
- `seon.render/a-renderer-is-chosen-by-key-presence`
- `seon.render/a-transaction-report-prints-its-runs-bookkeeping` [proof: no]
- `seon.render/an-unchanged-retained-call-selects-and-invokes-again` [proof: unknown]
- `seon.render/captured-invocations-blank-source-faces` [proof: no]
- `seon.render/cold-renders-recompute-read-evidence` [proof: no]
- `seon.render/face-vocabulary-remains-in-code` [proof: no]
- `seon.render/landing-reds-2-render-bow-outs` [proof: no]
- `seon.render/unit-sweep-retyped-non-map-render-inputs` [proof: no]
- `seon.test.accretion/schema-row-compiles-every-reached-form` [proof: no]
- `seon.test.cache/dependency-revisions-are-not-test-inputs` [proof: unknown]
- `seon.test.runner/a-green-re-record-sweeps-the-failure-cell` [proof: unknown]
- `seon.test.runner/a-rerun-failure-report-conflicts-and-faults-the-request` [proof: no]
- `seon.test.runner/admission-filters-the-whole-program-history` [proof: no]
- `seon.test.runner/drift-is-blamed-on-concurrent-requests` [proof: unknown]
- `seon.test.runner/drift-prints-unbounded` [proof: no]
- `seon.test.runner/failure-evidence-is-stored-as-text` [proof: no]
- `seon.test.runner/reach-over-every-test-takes-thirty-seconds` [proof: no]
- `seon.test.runner/worker-drift-prints-its-sets-unbounded` [proof: no]
- `seon.test/a-changed-check-builds-a-world-per-request` [proof: unknown]
- `seon.test/a-check-with-nothing-to-run-answers-green` [proof: no]
- `seon.test/a-test-request-refuses-whole-when-one-namespace-has-no-eligible-tests` [proof: unknown]
- `seon.test/check-fails-on-agent-registered-schemas` [proof: no]
- `seon.test/one-test-run-costs-seconds-around-a-147-ms-body` [proof: unknown]
- `seon.test/property-members-pay-a-commit-per-step` [proof: no]
- `seon.test/release-and-rethrow-sites-repeat-instead-of-one-with-release` [proof: no]
- `seon.test/reuse-only-selection-costs-a-second` [proof: unknown]

## Phase 8 — db, schema, program, contracts

Future-spec candidates (map-data-contracts.md §4): error text via malli.error (done by errors S5/S7), read evidence by revision only.
- `seon.cluster/schema-adoption-refuses-instead-of-dropping-an-edn-encoded-value` [proof: no]
- `seon.cluster/schema-convergence-sees-order-the-store-does-not-keep` [proof: unknown]
- `seon.config/a-config-read-walks-the-whole-registry` [proof: no]
- `seon.db/a-report-db-before-names-a-stale-commit` [proof: no]
- `seon.db/a-transaction-of-many-profile-measurements-hits-the-validation-node-limit` [proof: no]
- `seon.db/admission-without-projection-embeds-its-error` [proof: no]
- `seon.db/an-entity-only-pattern-scans-past-the-query-work-bound` [proof: no]
- `seon.db/arity-caches-copy-the-indexes` [proof: no]
- `seon.db/capturing-read-evidence-costs-0.8-ms-per-index-seek` [proof: no]
- `seon.db/deletion-error-has-no-caller` [proof: no]
- `seon.db/hot-total-predicates-run-hundreds-of-millions-of-times` [proof: no] **new**
- `seon.db/the-query-wrapper-costs-36x-datahike`
- `seon.db/the-world-schema-refuses-the-database-value` [proof: no]
- `seon.eval.drive/unreachable-definitions-remain-in-src` [proof: no] **new**
- `seon.instrument/a-contract-with-an-fn-ifn-cannot-arm-on-a-sci-branch` [proof: no]
- `seon.instrument/hot-internals-pay-armed-validation` [proof: no]
- `seon.program/a-defn-scans-every-projection-form-per-call` [proof: no]
- `seon.program/a-docstring-edit-changes-the-definition-digest` [proof: no]
- `seon.program/program-attributes-trust-any-partition` [proof: no]
- `seon.program/row-vocabulary-sweep` [proof: no]
- `seon.schema.datahike/the-codec-reprints-values-to-compare-them` [proof: no]
- `seon.schema.edn/declaration-stamp-has-no-caller` [proof: unknown]
- `seon.schema/a-declaration-write-reloads-the-whole-projection` [proof: no]
- `seon.schema/a-function-contract-changes-the-schema-projection` [proof: no]
- `seon.schema/map-validation-throws-on-sorted-maps-with-foreign-keys` [proof: no]
- `seon.schema/pull-selector-predicate-compiles-per-check` [proof: unknown]
- `seon.schema/schema-registration-rechecks-the-whole-program-per-key` [proof: no]
- `seon.schema/the-projection-travels-ambiently` [proof: no]
- `seon.schema/validation-throws-on-non-keyword-sorted-maps` [proof: no]
- `seon.sci.eval/a-contract-predicate-is-refused-both-ways` [proof: no]
- `seon.sci.reader/schema entities lose their declaring namespace` [proof: unknown]
- `seon.turn/schema-redeclaration-revalidates-unchanged-attributes` [proof: no]

## Phase 9 — cluster, store, boot, flow, health

Future-spec candidates (map-cluster-test-tools.md §3 #3-9, no ruling yet): accrete-only schema migrator, clj-reload for reload order, schedule -> one ScheduledExecutorService, flow work launcher -> one :io proc, babashka.fs/process for fs and shell, docstring prose to specs. Repl-path (map-repl-path.md §3): one show and one settle (loop S1 generalizes the settlement).
- `seon.cluster.registry/a-status-read-pays-a-store-read-for-the-current-source-commit` [proof: no]
- `seon.cluster.registry/branch-creation-is-serialized-store-wide` [proof: no]
- `seon.cluster.source/changed-identities-walks-history-for-an-adopted-commit` [proof: no]
- `seon.cluster.store/a-small-commit-rewrites-thirty-index-nodes` [proof: no]
- `seon.cluster.store/an-ordinary-commit-writes-26-files-and-136-kb` [proof: no]
- `seon.cluster.store/collection-lists-the-store-keys-twice` [proof: no]
- `seon.cluster.store/each-commit-forces-the-directory-once-per-node` [proof: no]
- `seon.cluster.store/each-commit-serializes-its-root-record-twice` [proof: no]
- `seon.cluster.store/index-roots-lose-their-subtree-counts` [proof: no]
- `seon.cluster/a-missed-quiescence-keeps-the-route-listener` [proof: no]
- `seon.cluster/adoption-readopts-what-the-jvm-loaded` [proof: no]
- `seon.cluster/adoption-write-costs-sixteen-seconds` [proof: no]
- `seon.cluster/boot-publication-phases-are-invisible` [proof: no]
- `seon.cluster/runtime-status-throws-on-a-boot-window-read` [proof: no]
- `seon.cluster/schema-form-keys-re-derive-files-whose-entities-do-not-change` [proof: no]
- `seon.cluster/status-casts-a-keyword-to-a-symbol-during-boot` [proof: no]
- `seon.config/compute-concurrency-dial-has-no-executor` [proof: no]
- `seon.db/encoding-only-storage-changes-migrate-reused-entities` [proof: no]
- `seon.db/read-evidence-carries-result-digests` [proof: no]
- `seon.flow/a-cancelled-future-is-not-exit` [proof: no]
- `seon.flow/blocking-konserve-io-bursts-the-mixed-pool-to-five-hundred-threads` [proof: no]
- `seon.fn.analyzer/local-root-dependency-analysis-goes-stale` [proof: unknown]
- `seon.fn/an-armed-first-population-takes-a-hundred-seconds` [proof: unknown]
- `seon.fn/caller-relint-follows-contract-consumers` [proof: no]
- `seon.fn/cross-file-derivation-inputs-rely-on-dependent-relint` [proof: no]
- `seon.fn/report-identities-costs-a-second-per-call` [proof: no]
- `seon.maintenance/collection-marks-slowly-and-records-nothing` [proof: no]
- `seon.maintenance/root-verification-compares-a-moving-head` [proof: no]
- `seon.maintenance/the-daily-collection-keeps-every-commit-of-a-live-branch` [proof: no]
- `seon.mcp/entrance-profile-counts-the-requested-evaluation` [proof: no]
- `seon.operator/status-prints-ok-while-the-cluster-is-still-starting` [proof: no]
- `seon.problems/a-status-observation-costs-half-a-gigabyte` [proof: no]
- `seon.profile/reading-the-profile-cells-costs-3-ms` [proof: no]
- `seon.profile/scope-wrappers-report-callback-time-as-work` [proof: no]
- `seon.profile/the-bracket-leaves-a-thread-local-entry-per-cell-on-every-thread` [proof: no]
- `seon.schedule/a-scheduled-collection-runs-inside-the-schedule-proc-step-and-reports-as-in-world` [proof: no]
- `seon.sci.kernel/memory-pressure-crossed-during-test-rounds` [proof: no]
- `seon.sci.kernel/the-memory-gate-names-in-flight-work-it-cannot-see` [proof: no]

## C — obsolete; closes with the named change (not deleted now)

### P0-close: already fixed or machinery already deleted (verify, then close in Phase 0) (26)

- `seon.cache/one-weighted-cache-under-a-memory-cap` [proof: no]
- `seon.call-preparation/the-call-state-is-an-atom` [proof: no]
- `seon.cluster/mcp-get-value-admits-a-negative-offset` [proof: no]
- `seon.cluster/mcp-reply-without-a-database-prints-unbounded` [proof: no]
- `seon.cluster/record-jvm-evaluations-on-shared-path` [proof: no]
- `seon.cluster/the-mcp-value-is-a-converted-tree` [proof: no]
- `seon.db/custody-scopes-widen` [proof: no]
- `seon.db/the-sci-call-hook-pays-armed-database-resolution` [proof: no]
- `seon.dev.mcp/a-live-io-prepl-session-has-no-idle-bound` [proof: no]
- `seon.dev.mcp/branch-eval-answers-no-events` [proof: no]
- `seon.dev.mcp/branch-mcp-takes-one-form` [proof: no]
- `seon.dev.mcp/consume-recorded-diagnostic-observation`
- `seon.dev.mcp/mcp-failure-answers-carry-a-babashka-stack-trace` [proof: no]
- `seon.fault/delete-panic-record-policy-branch`
- `seon.fn/call-reach-refuses-every-gate` [proof: no]
- `seon.fn/identity-names-refuses-set-callers` [proof: no]
- `seon.maintenance/a-test-request-collects-the-whole-store-under-the-exclusive-gate` [proof: no]
- `seon.render/declared-metadata-selects-a-value-that-no-longer-fits` [proof: no]
- `seon.render/error-operation-read-as-returner`
- `seon.sci.eval/required-library-vars-read-as-definitions` [proof: no]
- `seon.task/no-repl-entrance-creates-a-task` [proof: no]
- `seon.turn/a-nested-in-ns-loses-the-whole-run` [proof: no]
- `seon.turn/a-refused-multi-form-settlement-returns-nothing` [proof: no]
- `seon.turn/analyze-settlement-is-unreached` [proof: unknown]
- `seon.turn/failed-run-left-unsettled`
- `seon.turn/the-unbound-check-walks-the-live-value-without-a-bound` [proof: no]

### Runtime records that vanish with the Phase 4 reset (13)

- `my.auditchange/faults of lint2` [proof: no]
- `my.fs/faults of read` [proof: no]
- `my.fs/faults of write!` [proof: no]
- `my.program/faults of diff` [proof: no]
- `my.shell/faults of run!` [proof: no]
- `refusal-cause.probe/faults of bad-err` [proof: no]
- `seon.cluster.prompt/merge 02b3f1f75a77` [proof: no]
- `seon.cluster.registry/faults of retire-branch!` [proof: no]
- `seon.db/faults of transact!` [proof: no]
- `seon.schema.edn/faults of unreadable-file!` [proof: no]
- `user/faults of bad3` [proof: no]
- `user/faults of bad4` [proof: no]
- `user/work of lane-ots` [proof: no]

### Close when the context design deletes the kept system-turn code (loop spec §5.6) (4)

- `seon.turn/a-blind-cross-branch-writer-is-promoted-to-a-read` [proof: no]
- `seon.turn/a-preview-reparses-retained-source` [proof: no]
- `seon.turn/compaction-erases-no-history-form-text` [proof: no]
- `seon.turn/opening-run-id-from-a-stale-count` [proof: no]

### Close with the owner-ruled issue* cut (1)

- `seon.cluster.source/index-issues-drops-every-opened-date-in-a-git-less-archive` [proof: no]

## E — owner decisions

Tasks:
- `my.edit/edit-hook-budgets-need-owner-numbers` [proof: no]
- `my.edit/file-edit-tools-bypass-write-back` [proof: no]
- `my.examples-test/docstring-examples-needing-a-live-cluster-cannot-run-in-the-examples-fixture` [proof: unknown]
- `my.examples/the-examples-test-reads-a-docstring-example-as-an-invalid-token` [proof: unknown]
- `seon.id/identities-hash-structure-not-printed-text` [proof: no]
- `seon.test/checks-write-into-live-custody`
Decisions without a task (added 2026-10-07 evening; each blocks the named work):
- Task handles vs structural hashing (blocks `structural-hash`, docs/research/agent-platform/2026-10-07/structural-hash/notes.md): A scripted old->new handle sweep; B handles minted once, uniqueness only; C keep printing for handles (against the one-printer law); root's proposal D: strip `:id` literals from task files, ingestion derives handles from ns+name+kind (tasks are referenced by ns/task-name).
- Reach graph facts in a producer-written digest (a420a03e3 notes): today the new graph-facts read costs 52 ms per new commit and as-of databases 65 -> 370 ms per call; writing call edges into a producer-owned digest removes it but changes identity semantics.
- `transaction-report-datom?` ~20 checks per datom (commit-validation notes): looser contracts on the writer's internal functions, or a platform change to how armed predicate checks run; <0.1 ms per trivial commit, not urgent.
- Context render scope (context-examples README): spec §3 has no system message and a steps-only task view; the built producers are system-message + task-context; approve the full budgeted render shape.
- Cut values after restart (c08e2c766): an evaluation's value is no longer serialized or stored; after a restart its handle binds to a `:live-only` marker with the value's class. This reverses root ruling (a) of 80a02b4a4 (cut values survived restarts) and follows CLAUDE.md "never serialize live results"; owner confirms.
- Remaining stored copies (value-storage notes): what a stored value is for fault evidence, effect results, `:seon.effect/request-edn`, the test-failure actual blob, render.clj:1112 and render.value/artifact-edn: (a) the value printer's shown text, (b) data as Datahike values, (c) nothing beyond shown text and identities. Lane recommends (c) for request-edn and test-failure actual, (a) for fault evidence and effect results. Also: admission builds `:seon.sci.admit/edn` on every call and nothing reads it (delete; a REPL-path change).
- `examples_test` deletion: RULED delete (2026-10-07); lane examples-world still holds the dirty file.
- `my.edit`: RULED keep for non-Clojure files (refuse .clj edits); edit-hook budgets RULED warnings + review.
- Errors S2 re-slicing (docs/research/agent-platform/2026-10-07/errors/s1-s2-notes.md): S2a throwable-free sites only, S2b typing every producer, drop
  `:at`/`:layer` with S6; and the type for functions whose output alternative is only `:seon.error/base` or a union of
  several errors (generic `:seon.error/base` type, or a named variant first).
- `test/my/examples_test.clj`: lane examples-world holds it with uncommitted changes; owner wanted useless tests gone.
  Keep, shrink or delete (decides my.examples/* and my.examples-test/* tasks above).

## Owner actions (no task)

- Rotate `DEEPSEEK_API_KEY`: a lane grep echoed the `.env` line into its transcript (docs/research/agent-platform/2026-10-07/deepseek/findings.md).
- "1/4 upper-left rendering at ~30 fps": the surface is unknown and the question is unanswered; say which page/app.
- Commit 62646ee70 swept the owner's `.gitmodules`/CLM changes; owner checks what to restore (src-clm/ is untracked).
- Foreign dirty files nobody claims: `tmp/test-data-assertions/proofs.edn`, `reference-code/http-kit`; also dirty
  `.agents/skills/*/SKILL.md`, `docs/seon/architecture/reference-code.md` (guide revision lane, presumably).

## Problems found 2026-10-07 and where each is tracked

1. Errors S1 defects -> seon.error/a-converted-error-keeps-its-transient-type, seon.error/s1-left-metadata-readers-and-stamps-in-three-test-files (S1b).
2. Pre-existing reds on a5a21fd58 -> seon.test/red-tests-on-head-are-unclassified (P2).
3. Full-tier harness crash in release-store! -> seon.test/test-release-closes-branch-connections-out-of-order (P5).
4. retire-branch! "Connection has been released." -> same task as 3; the stale unrelated error on an MCP reply's :errors -> seon.repl/every-reply-repeats-open-faults (errors S6).
5. Health top: hot predicates -> seon.db/hot-total-predicates-run-hundreds-of-millions-of-times (P8); over-bound with-branch and call-detecting-program-change (600 s max from the hung run) -> seon.profile/scope-wrappers-report-callback-time-as-work (P9); the 62,924-call fault query -> seon.db/the-query-wrapper-costs-36x-datahike (P8) + seon.error/the-error-observation-pull-names-541-attributes (S6).
6. DeepSeek config -> seon.ai/deepseek-model-rows-and-dial-classification-are-stale (P3).
7. Loop-map findings: stale "orphan" docstrings (turn.clj step, cluster/agent.clj ns doc) -> deleted/converted by loop S5/S2; :seon.turn/handled and :seon.runtime/trigger retired in loop S5; :seon.cluster.eval/author -> origin (loop S3); :seon.turn/opened-tx stays while the kept system-turn code reads it and goes with the context design (spec §4) — no new tasks.
8. seon.test-support/refusal-data -> seon.test-support/refusal-data-recognizes-errors-by-at-and-layer (S2); transacted!'s pr-str -> seon.render.value/test-messages-print-whole-worlds (P5).
9. with-redefs/alter-var-root census -> seon.test/tests-redefine-default-vars (P5).
10. reference-code.md seam rows -> Phase 1 guide revision.
11. Leftovers -> Phase 0 and E above.
12. Owner-only items -> Owner actions above.
13. Research reductions -> dead code: seon.eval.drive/unreachable-definitions-remain-in-src (P8, owner ruling exists); the rest listed as future-spec candidates under Phases 7-9.

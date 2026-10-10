# Interpreted tier, 2026-10-10

This page records what happens when a non-root agent runs the file tests interpreted (lane sci-tests, e361536a9 on a3eff174a). Each red is classified by cause against the same tests run compiled.

## Method

- **Interpreted run.** Throwaway `bin/seon check` JVM, check run 85bf2ccf1d70. A non-root agent ran `my.test/check` over every stored test symbol, sorted, in batches of 25 per eval request; its file tests resolve interpreted (`:seon.test/interpreted-files?`). The check stopped at its 600 s cap, so the tier is partial: 1,075 of 2,677 tests were attempted, 846 executed and 290 were red. Five batches ended in `time-limit`, which is 30 s per eval request.
- **Compiled baseline.** The 290 reds were run compiled, as root, in four checks: runs 2924be09d8c7, 831aa10bbbdb, 371097b19ddf and 512c3ae4f32a. 85 are red compiled too; run 2924be09d8c7 also left 1 test unfinished and 14 pending. That leaves 205 tests red only when interpreted. Default holds no compiled evidence for these tests to compare with.
- **Failure messages.** The 205 interpreted-only reds were re-run interpreted (check run 529d04c459ef) and their failure messages grouped by cause. "Redefines a Var" means the deftest's stored source or a function of its namespace names `with-redefs`, `alter-var-root` or `intern`; it takes precedence over the message.

## Classes

| Class | Tests | Owner task |
|---|---|---|
| R: Redefines a Var | 50 | `seon.test-census-test/no-test-redefines-a-var` |
| D: Requires a dev/ namespace the cluster never loads | 25 | `seon.sci.eval/a-dev-namespace-test-cannot-run-interpreted-on-a-cluster` |
| F: A test-namespace def failed to interpret | 20 | `seon.sci.eval/a-test-namespace-def-that-fails-to-interpret-is-used-as-its-refusal` |
| O: SCI *out* unbound on the member thread | 17 | `seon.sci.eval/sci-out-is-unbound-on-a-test-member-thread` |
| E: await-event! refuses the event source, or the wake is not seen | 14 | `seon.sci.eval/await-event-refuses-an-interpreted-tests-event-source` |
| M: Test macro reads the compiler &env | 10 | `seon.sci.eval/a-test-macro-reading-the-compiler-env-cannot-interpret` |
| P: proxy unsupported in SCI | 7 | `seon.sci.eval/a-proxy-in-interpreted-code-is-unsupported` |
| G: test.check or Malli rejects SCI values | 5 | `seon.sci.eval/remaining-interpreted-only-test-differences` |
| I: Instrumentation keyed by the host Var | 3 | `seon.sci.eval/remaining-interpreted-only-test-differences` |
| N: SCI namespaces carry no metadata | 2 | `seon.sci.eval/an-interpreted-namespace-carries-no-metadata` |
| T: Over its bound interpreted | 3 | `seon.sci.kernel/a-file-test-run-interpreted-inherits-the-runners-arm-and-overruns-its-bound` (same class) |
| V: Test row did not interpret (other) | 3 | `seon.sci.eval/remaining-interpreted-only-test-differences` |
| U: Unclassified assertion difference | 46 | `seon.sci.eval/remaining-interpreted-only-test-differences` |
| Total | 205 | |

### R (50)

Redefines a Var: the deftest body or a helper in its namespace uses `with-redefs`, `alter-var-root` or `intern`. The redefinition replaces only the SCI copy, so compiled callers keep the original.

- `seon.blob-test/large-binary-write-and-small-chunk-have-bounded-allocation`
- `seon.cluster-test/boot-recovery-refuses-an-unreadable-open-turn-query`
- `seon.cluster.agent-test/a-failed-turn-transform-releases-its-permit-and-retires-its-backstop`
- `seon.cluster.agent-test/disarm-drops-the-route-before-closing-it`
- `seon.cluster.agent-test/hot-reload-var-test`
- `seon.cluster.release-context-test/a-failing-connection-release-still-unlinks-and-forgets-the-context`
- `seon.cluster.release-context-test/every-release-failure-surfaces-with-the-later-ones-suppressed`
- `seon.cluster.subprocess-test/foreign-process-deadline-covers-output-capture`
- `seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts`
- `seon.cluster.turn-test/a-changed-arity-redefinition-is-callable-in-the-same-pass`
- `seon.cluster.turn-test/a-combined-evaluation-projects-every-terminal-receipt-datom`
- `seon.cluster.turn-test/a-function-using-a-schema-registered-in-the-same-form-is-admitted`
- `seon.cluster.turn-test/a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact`
- `seon.cluster.turn-test/a-prose-only-provider-reply-settles-without-parking-the-turn-proc`
- `seon.cluster.turn-test/a-provider-failure-is-one-call-and-an-error-value`
- `seon.cluster.turn-test/a-real-evaluation-that-runs-away-is-stopped-and-recorded`
- `seon.cluster.turn-test/a-red-form-stays-with-its-author-and-the-fold-continues`
- `seon.cluster.turn-test/a-refused-contract-commits-a-receipt-and-no-entity`
- `seon.cluster.turn-test/a-refused-definition-stays-in-its-agents-defs`
- `seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`
- `seon.cluster.turn-test/a-turn-hands-its-clusters-projection-to-every-database-call`
- `seon.cluster.turn-test/a-turn-through-a-local-provider-settles-and-exits-within-its-bound`
- `seon.cluster.turn-test/a-waiting-disposition-frees-the-agent-and-keeps-its-note`
- `seon.cluster.turn-test/a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end`
- `seon.cluster.turn-test/agent-code-with-defn-and-println-folds-green-without-in-ns`
- `seon.cluster.turn-test/an-ordinary-turn-advances-no-program-attribute-revision`
- `seon.cluster.turn-test/an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence`
- `seon.cluster.turn-test/another-agent-sees-a-flat-contract-violation-after-live-install`
- `seon.cluster.turn-test/contracted-redefinition-exactly-replaces-the-entity`
- `seon.cluster.turn-test/evaluation-follows-the-readers-parse-time-namespace`
- `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`
- `seon.cluster.turn-test/incompatible-clusters-alternate-runtime-schema-validation-without-bleed`
- `seon.cluster.turn-test/mixed-plan-publishes-only-the-contracted-function`
- `seon.cluster.turn-test/ns-unmap-retracts-the-owned-function-after-the-terminal-commit`
- `seon.cluster.turn-test/reply-reading-follows-evaluated-alias-and-dynamic-require-state`
- `seon.cluster.turn-test/runtime-schema-unregister-removes-one-unused-global-schema`
- `seon.cluster.turn-test/runtime-tests-install-run-redefine-and-delete-exactly`
- `seon.cluster.turn-test/spending-the-last-episode-run-is-a-loud-core-fault`
- `seon.cluster.turn-test/streaming-writes-zero-datoms-test`
- `seon.cluster.turn-test/successful-call-persists-the-providers-open-usage-document`
- `seon.cluster.turn-test/turn-intent-is-the-complete-crash-falsifier`
- `seon.config-test/config-writer-submits-only-the-fact-difference`
- `seon.config-test/converged-apply-uses-carried-projection-and-remains-exact`
- `seon.config-test/packaged-defaults-use-the-same-shipped-document-authority`
- `seon.db-test/an-entity-pull-is-checked-only-on-the-attributes-that-changed`
- `seon.db-test/current-database-resolves-once-per-call`
- `seon.db-test/pull-many-preserves-input-alignment-with-one-shared-plan`
- `seon.db-test/transaction-wrappers-cannot-hide-a-classified-refusal`
- `seon.dev.hook-test/output-does-not-extend-the-hook-terminal-bound`
- `seon.dev.publication-test/publication-forwards-output-and-returns-terminal-value`

### D (25)

The test namespace requires a `dev/` (Babashka) namespace that the cluster JVM never loads, such as `seon.dev.mcp` or `seon.dev.docstring`, so its test rows do not interpret ("The admitted identity has no executable test Var").

- `seon.dev.docstring-test/compliant-first-line-test`
- `seon.dev.docstring-test/format-findings-test`
- `seon.dev.docstring-test/lints-its-own-source-clean-test`
- `seon.dev.docstring-test/scan-test`
- `seon.dev.docstring-test/test-and-internal-ns-skipped-test`
- `seon.dev.docstring-test/too-long-test`
- `seon.dev.mcp-bridge-test/a-call-answers-the-jvms-result`
- `seon.dev.mcp-bridge-test/a-call-forwards-the-request-unchanged`
- `seon.dev.mcp-bridge-test/a-call-routes-to-the-only-live-cluster`
- `seon.dev.mcp-bridge-test/a-call-with-no-live-jvm-names-the-start-remedy`
- `seon.dev.mcp-bridge-test/a-failure-after-sending-is-not-retried`
- `seon.dev.mcp-bridge-test/a-fetched-tool-list-is-cached-for-a-jvm-down-start`
- `seon.dev.mcp-bridge-test/a-jvm-exception-shows-the-jvms-cause`
- `seon.dev.mcp-bridge-test/a-notification-answers-nothing`
- `seon.dev.mcp-bridge-test/a-refused-connection-rediscovers-once`
- `seon.dev.mcp-bridge-test/a-replaced-jvm-sends-list-changed-before-the-result`
- `seon.dev.mcp-bridge-test/a-silent-jvm-times-out-as-an-error-result`
- `seon.dev.mcp-bridge-test/an-unknown-method-with-an-id-is-method-not-found`
- `seon.dev.mcp-bridge-test/an-unparseable-line-answers-a-parse-error`
- `seon.dev.mcp-bridge-test/initialize-advertises-list-changed`
- `seon.dev.mcp-bridge-test/the-bridge-sends-exactly-the-two-entrance-forms`
- `seon.dev.mcp-bridge-test/the-same-jvm-is-not-asked-for-its-tools-again`
- `seon.dev.mcp-bridge-test/the-tool-list-comes-from-the-live-jvm`
- `seon.dev.mcp-bridge-test/with-no-jvm-and-no-cache-the-static-list-is-served`
- `seon.dev.publication-launch-test/launch-loads-the-cluster-without-an-inlined-input-inventory`

### F (20)

A top-level `def` in the test namespace failed to interpret, so its Var holds the refusing root, which the test then uses as a value (`projection` "got an instance of seon.sci.eval$refusing$refused", "requires a carried schema projection"). The refused `def`s still need naming.

- `seon.blob-test/binary-content-round-trips-on-both-sides-of-the-inline-threshold`
- `seon.blob-test/interrupted-staging-leaves-the-store-unchanged`
- `seon.blob-test/published-blob-is-compressed-and-deferred`
- `seon.cluster.registry-test/retiring-one-cluster-reclaims-only-its-own-tail`
- `seon.cluster.registry-test/the-plain-collection-keeps-held-values-adopted-commits-and-concurrent-writes`
- `seon.cluster.registry-test/two-clusters-write-independently`
- `seon.cluster.store-test/genesis-window-repairs-by-recreate`
- `seon.cluster.store-test/open-write-release-reopen-preserves-data`
- `seon.cluster.store-test/temporal-stores-answer-all-three-database-views`
- `seon.cluster.store-test/transact-normalizes-only-jdk-integers`
- `seon.cluster.store-transact-test/invalid-logical-and-storage-values-refuse-loudly`
- `seon.datahike-fork-test/alpha-renaming-does-not-change-plan-selection`
- `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage`
- `seon.db-test/a-store-without-program-schema-admits-its-writes`
- `seon.db-test/committed-edn-reads-as-written-unless-its-storage-is-malformed`
- `seon.db-test/component-expanded-pull-evidence-detects-a-child-only-change`
- `seon.db-test/edn-backed-reads-return-distinguishable-logical-values`
- `seon.db-test/query-symbol-values-use-the-declared-codec-in-every-binding-shape`
- `seon.db-test/return-map-queries-preserve-ordering-and-limit`
- `seon.db-test/storing-and-reading-a-value-prints-it-once`

### O (17)

SCI `*out*` (and `*in*`) is unbound on the test member thread, so printing fails with "SciUnbound cannot be cast to java.io.Writer", and a value read from it arrives as SciUnbound (`seon.ai/stream-event` "got SciUnbound").

- `my.program-query-test/predicate-arguments-are-bindings-not-nested-clojure-evaluations`
- `my.program-query-test/scalar-attribute-bindings-preserve-an-encoded-value-type`
- `seon.ai-stream-fold-test/a-sink-sees-monotonically-growing-complete-snapshots`
- `seon.ai-stream-fold-test/a-throwing-sink-cannot-change-the-completion`
- `seon.ai-stream-fold-test/presentation-noise-is-silent-but-malformed-data-refuses-the-stream`
- `seon.ai-stream-fold-test/the-fold-accumulates-text-and-retains-the-newest-usage`
- `seon.ai-stream-fold-test/usage-is-retained-independently-of-choices`
- `seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault`
- `seon.db-test/carried-queries-stay-within-the-five-millisecond-budget`
- `seon.db-test/transaction-success-and-refusal-shapes-are-declared`
- `seon.error-result-test/a-large-off-path-request-stores-only-its-shown-text`
- `seon.error-result-test/core-results-use-the-printer-for-arbitrary-objects`
- `seon.error-result-test/error-stores-shown-text-not-node-tree`
- `seon.error-result-test/large-result-stores-the-profile-capped-printer-text`
- `seon.error-result-test/shown-text-over-the-threshold-is-staged-with-the-fact`
- `seon.error-test/complete-error-children-validate-through-the-writer`
- `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner`

### E (14)

`seon.test-support/await-event!` refuses the event source the interpreted test builds ("not a channel, latch, future, or watched reference"), or the awaited wake is never observed.

- `seon.cluster.host-test/a-message-to-a-stopped-admitted-agent-hosts-it-and-its-armer-takes-it`
- `seon.cluster.host-test/an-ordinary-message-wakes-the-idle-hosted-agent`
- `seon.cluster.host-test/an-sci-send-from-the-cluster-supplies-its-handle-and-resumes-a-stopped-agent`
- `seon.cluster.host-test/retirement-closes-admission-at-the-writer-and-races-submission`
- `seon.cluster.listener-failure-test/a-failed-listener-is-one-stored-fault-and-retires-only-its-own-registration`
- `seon.cluster.message-test/inbound-wakes-the-named-agent-test`
- `seon.cluster.wake-test/a-closed-render-channel-delivers-a-fault`
- `seon.cluster.wake-test/a-declared-attribute-wakes-with-no-code-change`
- `seon.cluster.wake-test/an-unrouted-recipient-reaches-the-armer`
- `seon.cluster.wake-test/render-wake-intersects-the-current-published-interest`
- `seon.cluster.wake-test/runtime-listens-route-and-refresh-with-entity-and-value-constraints`
- `seon.concurrency-test/messages-stay-on-their-connection`
- `seon.db-test/a-system-write-the-writer-never-acknowledges-returns-outcome-unknown-at-the-bound`
- `seon.db-test/a-throwing-datahike-listener-cannot-strand-a-committed-write`

### M (10)

A test-file macro reads the compiler `&env` (`clojure.lang.Compiler$LocalBinding`); SCI passes symbols instead ("Symbol cannot be cast to Compiler$LocalBinding"), so the macro does not interpret. `seon.cluster.agent-test/with-connection` and `seon.cluster.turn-test/with-render-context-proc` also redefine Vars, so most of their tests are counted under R.

- `seon.cluster.agent-test/a-changed-core-entity-refuses-by-name-and-every-agent-still-arms`
- `seon.cluster.agent-test/a-run-defining-with-an-alias-its-own-ns-form-required-settles`
- `seon.cluster.agent-test/canonical-agent-handle-supplies-declared-producer-outputs`
- `seon.cluster.agent-test/episode-cap-refusal-test`
- `seon.cluster.agent-test/fenced-is-the-derived-quarantine-state`
- `seon.cluster.agent-test/routing-proof-rejects-an-empty-production-subject`
- `seon.cluster.agent-test/system-source-submission-uses-the-ordinary-durable-run`
- `seon.cluster.agent-test/turn-start-has-the-same-declared-loud-completion-backstop`
- `seon.cluster.agent-test/wait-closes-in-terminal-tx-test`
- `seon.cluster.agent-test/wake-routing-conservation-property`

### P (7)

`proxy` is unsupported in SCI ("no proxy-fn"); the reify gap was closed in e361536a9, proxy remains.

- `seon.ai-test/a-reasoning-only-time-limit-names-the-missing-assistant-text`
- `seon.ai-test/a-stream-that-ends-before-any-text-is-named-truncation-not-bad-json`
- `seon.ai-test/a-stream-that-ends-mid-body-keeps-every-character-that-arrived`
- `seon.ai-test/a-truncated-stream-never-reports-output-that-never-arrived`
- `seon.blob-error-test/stalled-input-keeps-the-complete-producer-observation`
- `seon.error.refusal-test/a-place-in-an-error-is-named-never-printed-through`
- `seon.error.refusal-test/every-producer-references-the-worlds-it-was-handed`

### G (5)

test.check or Malli rejects values built in SCI (`gen/tuple` "Args to tuple must be generators", `:malli.core/invalid-schema`).

- `seon.blob-test/generated-binary-values-round-trip-through-staging-and-chunks`
- `seon.error-test/arity-declared-schema-preserves-real-refusal-observations`
- `seon.error-test/normalization-is-total`
- `seon.error.refusal-test/armed-refusal-returns-every-declared-family-without-refusing`
- `seon.error.refusal-test/constructor-output-keeps-the-producing-contract`

### I (3)

Instrumentation is keyed by the host Var, so `(contains? (instrument/instrumented) (var x))` with the SCI Var answers false, and the caller frame is absent.

- `seon.cluster-test/a-commit-tx-request-without-the-evidence-bound-names-the-key`
- `seon.context-test/message-custody-accepts-an-absent-run-when-instrumented`
- `seon.db-test/instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary`

### N (2)

SCI namespaces carry no metadata, so `(:doc (meta (the-ns ...)))` is nil.

- `my.message-test/the-surface-is-exactly-five-functions`
- `my.turn-test/the-lifecycle-surface-has-two-actions-and-its-own-presentation`

### T (3)

Runs past its bound when interpreted.

- `seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence`
- `seon.db-test/query-and-pull-append-evidence-only-when-captured`
- `seon.error-test/every-error-owner-function-declares-its-input-and-output`

### V (3)

The test row did not interpret for another reason, such as a library private Var SCI does not copy (`query/create-plan-via-ir`).

- `seon.cluster.host-test/an-unsettled-host-run-keeps-ownership-until-it-exits`
- `seon.cluster.program-classes-test/a-namespace-loaded-from-its-classes-keeps-protocol-and-record-identity-across-reload`
- `seon.datahike-fork-test/a-write-error-log-carries-no-stack-trace`

### U (46)

Unclassified assertion difference: the test runs but asserts something that differs when interpreted (world members, error attribution frames, docstring rules over `dev/` code). Each needs its own reading.

- `my.program-test/a-map-argument-lacking-world-members-is-filled-from-the-world`
- `my.web-test/public-entries-declare-one-io-capability`
- `seon.adoption-margin-test/closed-publication-observer-refuses-at-the-next-phase`
- `seon.ai-stream-fold-test/a-streamed-call-returns-the-same-shape-as-a-one-shot-call`
- `seon.call-preparation-test/a-function-made-in-one-context-uses-the-calling-evaluations-world`
- `seon.call-preparation-test/an-optional-world-member-is-supplied-and-a-required-one-refuses`
- `seon.call-preparation-test/two-clusters-supply-their-own-custody`
- `seon.call-preparation-test/unavailable-custody-refuses-the-call-instead-of-running-it-unprepared`
- `seon.cluster.boot-test/an-uncaught-throwable-without-its-world-is-printed-and-never-stored-elsewhere`
- `seon.cluster.host-test/a-message-is-no-proposal-and-a-branch-agent-accepts-in-its-own-context`
- `seon.cluster.host-test/a-refused-deletion-is-its-forms-refusal-and-the-run-closes`
- `seon.cluster.host-test/one-identity-is-homed-on-one-branch-decided-by-the-writer`
- `seon.config-test/initialization-entities-apply-query-and-converge-with-the-config-entity`
- `seon.context-test/a-message-a-closed-turn-handled-leaves-the-unread-section`
- `seon.context-test/a-request-that-cannot-run-the-help-render-answers-a-refusal`
- `seon.db-overhead-test/query-preparation-has-bounded-armed-work`
- `seon.db-test/a-definition-write-keys-its-projection-by-its-own-datoms`
- `seon.db-test/nested-native-reports-admit-bounded-reference-identities`
- `seon.db-test/read-evidence-currency-declares-its-database-by-schema-name`
- `seon.db-test/uncarried-read-reports-one-projection-fallback-per-call`
- `seon.dev.dependency-cache-test/a-snapshot-roots-recorded-pins-decide-its-dependency-cache-digest`
- `seon.dev.dependency-cache-test/discovery-compiles-a-namespace-before-the-extension-it-tail-requires`
- `seon.dev.docstring-test/blank-first-line-test`
- `seon.dev.docstring-test/cljs-constructs-test`
- `seon.dev.docstring-test/missing-docstring-test`
- `seon.dev.docstring-test/no-terminal-punctuation-test`
- `seon.dev.docstring-test/non-defn-forms-skipped-test`
- `seon.dev.docstring-test/private-fns-skipped-test`
- `seon.dev.docstring-test/reserved-glyph-literal-test`
- `seon.dev.mcp-bridge-test/the-bridge-names-only-the-entrance-and-operator-discovery`
- `seon.dev.prepl-retention-test/a-returned-value-is-collectible-with-no-further-evaluation`
- `seon.edit-test/changed-programs-since-basis-is-a-query`
- `seon.edit-test/edit-refusals-keep-their-filesystem-evidence`
- `seon.edit-test/form-edit-refs-the-program-entity-it-changed`
- `seon.error-test/a-body-on-its-own-branch-is-supplied-that-branchs-database`
- `seon.error-test/a-contract-violations-fault-keeps-the-value-that-broke-it`
- `seon.error-test/attribution-is-a-lookup-ref-or-nothing`
- `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer`
- `seon.error.attribution-test/a-fault-in-an-agents-world-is-copied-to-the-cluster`
- `seon.error.attribution-test/a-recorded-violation-lands-on-the-event-graph`
- `seon.error.attribution-test/blame-follows-the-producer`
- `seon.error.attribution-test/only-a-compiled-producer-not-redefined-by-the-batch-is-a-fault`
- `seon.error.refusal-test/a-helper-built-wrapper-keeps-the-call-site-frame`
- `seon.error.refusal-test/a-wrapped-cause-is-recorded-whole`
- `seon.error.refusal-test/constructor-consumes-only-the-throwable-input`
- `seon.error.refusal-test/constructor-preserves-open-domain-maps`

## Cost

One non-root `my.test/check` of 4 tests took 2,015 ms and allocated 1,355 MB. Each member's test resolution took 180 to 470 ms. Interpreting the shared helpers once per request cut resolution to 130 to 260 ms but raised the total to 2,327 ms and 2,115 MB, so that change was not landed. Most of the per-member cost is somewhere other than `seon.test-support`; `seon.test/one-test-run-at-the-repl-allocates-over-a-gigabyte` holds the cost evidence.

## Update, 2026-10-11: the 205 rerun

Lane interp-honest re-ran the 205 interpreted-only reds above as a non-root agent (`interp-census-1`, on its own branch of default at 26b25941f, which includes the evaluator-aware evidence, namespace-object and fixture fixes of 7ab559153, ad86afd43 and b097db713), in batches of 25 per `seon.test/run` request with `:seon.test/rerun? true` and declared-long tests included: `bb -cp script:src -m seon.interpreted-census /path/to/checkout default interp-census-1 docs/research/agent-platform/2026-10-11/interpreted-census-before.edn docs/research/agent-platform/2026-10-11/interpreted-census-after-205.edn 25` (`script/seon/interpreted_census.clj`). Each batch took 1 to 35 s, about 106 s in all. Every member ran: none was pending, refused or excluded. The per-test results are in [interpreted-census-after-205.edn](../2026-10-11/interpreted-census-after-205.edn), with the class each test had above.

| Class | Before | Still red | Now green |
|---|---|---|---|
| R: Redefines a Var | 50 | 49 | 1 |
| D: Requires a dev/ namespace | 25 | 0 | 25 |
| F: A test-namespace def failed to interpret | 20 | 9 | 11 |
| O: SCI *out* unbound on the member thread | 17 | 1 | 16 |
| E: await-event! refuses the event source | 14 | 1 | 13 |
| M: Test macro reads the compiler &env | 10 | 10 | 0 |
| P: proxy unsupported in SCI | 7 | 7 | 0 |
| G: test.check or Malli rejects SCI values | 5 | 3 | 2 |
| I: Instrumentation keyed by the host Var | 3 | 3 | 0 |
| N: SCI namespaces carry no metadata | 2 | 2 | 0 |
| T: Over its bound interpreted | 3 | 3 | 0 |
| V: Test row did not interpret (other) | 3 | 3 | 0 |
| U: Unclassified assertion difference | 46 | 19 | 27 |
| Total | 205 | 110 | 95 |

Limits:

- A green here is green interpreted. It was compiled-green in the baseline above, so it no longer differs; this rerun did not repeat the compiled run.
- The remaining F reds are uncaught exceptions in `seon.db-test`, `seon.blob-test`, `seon.cluster.store-transact-test` and `seon.datahike-fork-test`. The one O red (`seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault`) and the one E red (`seon.cluster.host-test/retirement-closes-admission-at-the-writer-and-races-submission`) fail on an assertion, not on the class's cause. The EDN keeps the first three lines of each failure message only, so reclassifying the 19 U reds needs a rerun of those tests.
- The full tier (2,677 tests) was not rerun, so interpreted reds outside these 205 are not counted. The same script takes a vector of every stored test symbol; at about 13 s per batch of 25 it needs about 25 minutes. A compiled baseline is needed to tell interpreted-only reds from the rest.

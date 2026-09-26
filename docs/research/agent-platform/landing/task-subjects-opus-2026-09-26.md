# task-subjects-opus — open defect tasks carry their subject in code (2026-09-26)

Owner (2026-09-26): "Please don't lose track of these extreme inefficiencies. If we are attributing it to specific
code we should start documenting it in the code so we can't ignore these problems." Language spec §7.2/7.4/7.6: the
subject's attribute map lists `:seon/tasks #{"<handle>"}`; ingestion (`seon.fn/task-links`, fn.clj:625-643) stores
it as `:seon.task/tasks`, and `seon.issue/task-edges`/`task-findings` read subjects only from `:seon.fn/sym` and
`:seon.ns/name` holders (issue.clj:727-763), so schema resources and `script/` files cannot hold a subject edge today.

## Broken / needs root first

- Eight held files are **patches, not edits**: `tmp/task-subjects-opus/seon.{cache,cluster,db,error,render.value,schema,sci.eval,turn}.clj.patch`
  (44 subject edges, metadata only). Apply after the one-commit and cache-central lanes release those files.
- Many task EDN entries describe defects whose fix commits already landed (list below). Nothing settles them: the
  EDN entry stays and triage (`task-findings`) will list them as subject-less. Root should verify and delete them.
- Several tasks' must-read names no longer exist (`seon.call-preparation/hook` was replaced by the armed wrapper in
  1b77876af); those are left unmarked below rather than guessed.

## Method and evidence

- Handles computed in the JVM with `(seon.issue/task-handle ns name kind)` over all 43 files: 135 tasks, 0 id
  mismatches. No id invented.
- One scripted edit (bb + rewrite-clj, positions from the parsed tree): add `:seon/tasks #{…}` as the first entry of
  the existing attribute map, merge into an existing set, or insert a map after the docstring (namespaces: after the
  ns docstring). No behaviour, docstring or number changed; one hand fix (`seon.issue/require-test-refs!` layout).
- `clj-kondo --lint <27 changed files> --cache false`: 0 errors; warnings identical to HEAD (122 = 122, compared with
  the same config). The eight patched copies: 0 errors (30 = 30 warnings vs HEAD).
- `seon.contracts-compile-test/check` over the 35 edited/patched texts against the checkout: 0 findings, 1,346 ms
  (clj-kondo analyze 260 ms of it, over one second: the check analyses all 35 files in one call).
- Not proven: ingestion of the new edges on a live store (default was not restarted by this lane; RESET NEEDED to see
  them in `task-edges`).

## Subjects marked (task → subject → status)

The producer that must change, per the task's intent (spec 2.3), not the symptom site.

| task | subject | status |
|---|---|---|
| `6a6e13871530` file-edit-tools-bypass-write-back | `my.edit` | marked |
| `20e88abfc982` native-calls-are-refused-instead-of-routed | `my.program/native-call-refusal` | marked |
| `2497b961dccc` refusal-names-nonexistent-define | `my.program/native-call-refusal` | marked |
| `70bf0e12569e` an-agent-cannot-see-or-choose-its-change | `my.program/overrides` | marked |
| `70bf0e12569e` an-agent-cannot-see-or-choose-its-change | `my.program/propose-merge!` | marked |
| `0a149b4c6d27` breaks-plans-a-contract-change-as-a-retraction | `my.program/breaks` | marked |
| `5087cea7800e` breaks-costs-seconds-on-a-hub | `my.program/proposed-plan` | marked |
| `2c66ac931003` help-teaches-the-wrong-tools | `seon.bootstrap/help-value` | marked |
| `edc8cb02dfc2` one-weighted-cache-under-a-memory-cap | `seon.cache` | patched (tmp/task-subjects-opus/seon.cache.clj.patch) |
| `775e3222a980` a-positional-database-is-not-supplied | `seon.call-preparation/prepare` | marked |
| `1d92b52b2470` adoption-readopts-what-the-jvm-loaded | `seon.cluster/development-source-refresh!` | patched (tmp/task-subjects-opus/seon.cluster.clj.patch) |
| `1d92b52b2470` adoption-readopts-what-the-jvm-loaded | `seon.cluster/adopt-rows!` | patched (tmp/task-subjects-opus/seon.cluster.clj.patch) |
| `0b3f443d1d94` the-mcp-value-is-a-converted-tree | `seon.cluster/mcp-project` | patched (tmp/task-subjects-opus/seon.cluster.clj.patch) |
| `5786fd38774b` a-missed-quiescence-keeps-the-route-listener | `seon.cluster/disarm-agents!` | patched (tmp/task-subjects-opus/seon.cluster.clj.patch) |
| `17027736987a` boot-publication-phases-are-invisible | `seon.cluster/refresh-source!` | patched (tmp/task-subjects-opus/seon.cluster.clj.patch) |
| `d6e3b775f474` the-query-wrapper-costs-36x-datahike | `seon.db/q` | patched (tmp/task-subjects-opus/seon.db.clj.patch) |
| `126f25106b63` a-query-refusal-holds-a-parser-object | `seon.db/q` | patched (tmp/task-subjects-opus/seon.db.clj.patch) |
| `991ce24cca22` call-reach-is-recomputed-per-render | `seon.db/stored-call-reach` | patched (tmp/task-subjects-opus/seon.db.clj.patch) |
| `0caf995ddc22` arity-caches-copy-the-indexes | `seon.db/arity-mismatches` | patched (tmp/task-subjects-opus/seon.db.clj.patch) |
| `b2c9509deb26` deletion-error-has-no-caller | `seon.db/deletion-error` | patched (tmp/task-subjects-opus/seon.db.clj.patch) |
| `7bfe767bec09` background-poll-answers-edn-strings | `my.background/poll` | marked |
| `3d1e36226ec4` a-deleted-functions-fault-never-resolves | `seon.error/resolution` | patched (tmp/task-subjects-opus/seon.error.clj.patch) |
| `a4b6cdcf40d6` a-missing-key-message-reads-badly | `seon.error/explain-problem` | patched (tmp/task-subjects-opus/seon.error.clj.patch) |
| `c75ab14cb365` deliver-core-faults-to-responsible-agent | `seon.error/commit-call` | patched (tmp/task-subjects-opus/seon.error.clj.patch) |
| `5e008f619131` error-transactions-carry-references-never-worlds | `seon.error/commit-call` | patched (tmp/task-subjects-opus/seon.error.clj.patch) |
| `f58c3aab9277` fault-observation-returns-the-recorded-error | `seon.error/occurred-since` | patched (tmp/task-subjects-opus/seon.error.clj.patch) |
| `465ff60ccda0` carried-receipt-hangs-on-cyclic-cause-chain | `seon.fault/carried-receipt` | marked |
| `f12bf9eda5f1` a-cancelled-future-is-not-exit | `seon.flow/stop-work-launcher!` | marked |
| `547fc42c47a5` local-root-dependency-analysis-goes-stale | `seon.fn.analyzer/ensure-dependency-analysis!` | marked |
| `56e90b0bf6b7` agent-mistakes-read-as-missing-dependency-analysis | `seon.fn/analyze-forms` | marked |
| `e4c8eac6a69a` mention-closure-survives-beside-call-reach | `seon.fn/gate-sets` | marked |
| `e4c8eac6a69a` mention-closure-survives-beside-call-reach | `seon.fn/gate-sets-in` | marked |
| `96ac997dcf2f` hot-internals-pay-armed-validation | `seon.instrument` | marked |
| `f985bfc97116` instrumentation-is-jvm-global | `seon.instrument` | marked |
| `a57e8b27de67` contract-refusal-carries-wrapper-bookkeeping | `seon.instrument/compiled-wrapper` | marked |
| `68dd03f5e3a2` issue-references-read-the-cluster | `seon.issue/require-test-refs!` | marked |
| `dea15d688116` the-issue-render-hides-its-problem | `seon.issue/render-ai` | marked |
| `534966f34876` collection-marks-slowly-and-records-nothing | `seon.maintenance/collect-released!` | marked |
| `ed8c6db31a85` status-is-blind-to-hosted-graphs | `seon.oversight/flow-status` | marked |
| `41a8e43ce5a4` a-status-observation-costs-half-a-gigabyte | `seon.problems/deferred-agents` | marked |
| `c7bc0c506aa4` slow-explanation-blames-the-agent-for-platform-time | `seon.profile/explain-slow` | marked |
| `5f54ae50d841` scope-wrappers-report-callback-time-as-work | `seon.profile/top` | marked |
| `0798a2fb94f3` a-docstring-edit-changes-the-definition-digest | `seon.program/definition-digest` | marked |
| `e457c998969e` program-attributes-trust-any-partition | `seon.program/program-attributes` | marked |
| `c5e9496b4cb1` captured-invocations-blank-source-faces | `seon.render/render-call` | marked |
| `76e1261553f3` cold-renders-recompute-read-evidence | `seon.db/read-evidence` | patched (tmp/task-subjects-opus/seon.db.clj.patch) |
| `785acaa52916` continuations-cannot-navigate-derived-nodes | `seon.render.value/prepare` | patched (tmp/task-subjects-opus/seon.render.value.clj.patch) |
| `0e6dc884b838` nested-renders-bypass-the-floor | `seon.render.value/prepare` | patched (tmp/task-subjects-opus/seon.render.value.clj.patch) |
| `3c8d05fc3de9` join-membership-has-no-caller | `seon.render.walk/join-membership` | marked |
| `befc26639ad0` the-codec-reprints-values-to-compare-them | `seon.schema.datahike/storage-compare` | marked |
| `befc26639ad0` the-codec-reprints-values-to-compare-them | `seon.schema.datahike/canonical-print-string` | marked |
| `6c1b8083edbe` validation-throws-on-non-keyword-sorted-maps | `seon.schema/valid-candidate-value?` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `5c8cf0549616` map-validation-throws-on-sorted-maps-with-foreign-keys | `seon.schema/valid-candidate-value?` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `b9163458502a` register-works-only-top-level | `seon.schema/register!` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `c87892f697b8` the-projection-travels-ambiently | `seon.schema/call-with-projection` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `c87892f697b8` the-projection-travels-ambiently | `seon.schema/call-with-projection-state` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `c87892f697b8` the-projection-travels-ambiently | `seon.schema/handed-projection` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `b2e2909e662d` a-declaration-write-reloads-the-whole-projection | `seon.schema/load-projection` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `f20577d4eb4c` projection-memos-stampede-and-swap-on-every-hit | `seon.schema/build-projection` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `f20577d4eb4c` projection-memos-stampede-and-swap-on-every-hit | `seon.schema/declaration-projection` | patched (tmp/task-subjects-opus/seon.schema.clj.patch) |
| `f20577d4eb4c` projection-memos-stampede-and-swap-on-every-hit | `seon.schema.edn/packaged-population` | marked |
| `57f97689b302` storage-edn-is-print-nodes | `seon.sci.admit/admit-partitioned` | marked |
| `6af99d6bdb94` large-printed-output-refuses-the-evaluation | `seon.sci.admit/refuse-over-bound!` | marked |
| `25cc3e2db48d` requiring-a-dependency-is-refused | `seon.sci.eval/unindexed-change` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `f548d10ecb69` evaluate-declares-too-few-refusals | `seon.sci.eval/evaluate` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `e5af812dbf95` doc-and-dir-erase-request-keys | `seon.sci.eval/agent-documentation-contract` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `d653ed4b633f` install-gate-interprets-file-tests-in-sci | `seon.sci.eval/run-candidate-test!` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `c5f80919df38` incremental-install-leaves-callers-on-the-jvm-definition | `seon.sci.eval/install-row!` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `9949c640e249` branch-change-measured-against-loaded-program | `seon.sci.eval/install-row!` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `60fa86de1b8c` repl-namespace-changes-drift-from-the-ns-text | `seon.sci.eval/namespace-context-row` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `d215b1ebde7c` acquisition-rebuilds-the-whole-program | `seon.sci.eval/acquire-program!` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `07317379b8ae` copied-vars-pin-old-wrapper-generations | `seon.sci.eval/copy-host-var` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `c088e4367dc5` shown-result-ignores-the-floor-refusal | `seon.sci.eval/shown-result` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `e32aa6d5b3eb` a-do-with-two-definitions-refuses-instead-of-admitting-both | `seon.sci.eval/top-level-evaluation` | patched (tmp/task-subjects-opus/seon.sci.eval.clj.patch) |
| `e13e5a9c557f` failure-value-contract-finding-on-every-exception | `seon.sci.kernel/failure-value` | marked |
| `430c53fde483` fabrication-refusal-guards-every-reader | `seon.sci.reader/read-events` | marked |
| `5f48e860a402` reader-head-check-is-a-second-declaration-derivation | `seon.sci.reader/function-declaration` | marked |
| `5f48e860a402` reader-head-check-is-a-second-declaration-derivation | `seon.sci.reader/test-declaration` | marked |
| `2fe9c6b7fe31` no-repl-entrance-creates-a-task | `seon.task` | marked |
| `62a5644dd469` the-auto-check-writes-into-the-agents-branch | `seon.test.accretion/auto-check` | marked |
| `845e1bce2592` a-changed-check-builds-a-world-per-request | `seon.test/clean-request-handle` | marked |
| `c86d834ab695` a-test-world-has-no-agent | `seon.test/clean-request-handle` | marked |
| `f868dc9e74f5` admission-filters-the-whole-program-history | `seon.test.runner/program-written-since?` | marked |
| `32db32ffe15c` failure-evidence-is-stored-as-text | `seon.test.runner/record-tx` | marked |
| `f491f58f9973` a-preview-reparses-retained-source | `seon.turn/planned-sources` | patched (tmp/task-subjects-opus/seon.turn.clj.patch) |
| `0e9208e52dea` close-turn-is-a-third-commit | `seon.turn/close-turn` | patched (tmp/task-subjects-opus/seon.turn.clj.patch) |
| `9fb12ed97bb2` a-reload-between-forms-is-undetected | `seon.turn/evaluate-sources` | patched (tmp/task-subjects-opus/seon.turn.clj.patch) |
| `ffb23155d2a0` a-blind-cross-branch-writer-is-promoted-to-a-read | `seon.turn/read-only-evaluation?` | patched (tmp/task-subjects-opus/seon.turn.clj.patch) |
| `62c45543598c` a-turn-pass-derives-its-work-twice | `seon.turn/more-agent-work?` | patched (tmp/task-subjects-opus/seon.turn.clj.patch) |

Already marked before this lane (unchanged): `300aca1ad57c` → `seon.fn/gate-set`, `seon.fn/tests-reaching`;
`cea47b015b23` → ns `seon.schema`; `132fee0467c8` → `seon.sci.eval/evaluate`.

## Fix commit landed — not marked; verify and settle (remove the EDN entry)

| task | fix commit(s) |
|---|---|
| `0261d4d9bbe6` acquired-functions-get-no-supplied-defaults | 1b77876af, ec0016c80 (M2b; `hook` gone) |
| `bae29b83a938` a-config-read-walks-the-whole-registry | c06501f9d |
| `4e824da5bfb7` each-connection-decodes-its-own-index-nodes | fc0ff40b9 (datahike fork 67b00071; commit cites 4bb788513862) |
| `4bb788513862` the-checked-writer-is-slow-on-a-fresh-branch | fc0ff40b9 claims it; the write-report-error 211 ms part is unverified — measure after restart, mark `seon.db/write-report-error` if it remains |
| `18051e0ee3e2` consume-recorded-diagnostic-observation | d404c8ff3 |
| `4c559398c4be`, `480378b94c9b` error references / causes | 09fe1d064 |
| `6b8d26a16ce7` delete-panic-record-policy-branch | 0cbf4336f, 7c40396b3 |
| `bd7cb669b45d`, `46513360f817` host-bound class list | 28c2603fd |
| `bca96d7b84af`, `4a5523d8ab90` identity-names / call-reach refusals | e9b2066ac |
| `7095e91b3f42`, `15a0784f82d5` | 8b4a10a44 |
| `135d92ea1308` a-renderer-is-chosen-by-key-presence | 958bf0ed6 |
| `0ae6b217abdd`, `b331d31540bb`, `3638f3f3bd57` | e0e730dfb (+ d404c8ff3) |
| `407264e2b69d` canonical-definition-cannot-invert | c527ec4cb |
| `c9e0cde3edb3` agent-form-writes-name-an-unwritten-evaluation | 55b5b580f |
| `d741e6812a31` a-trivial-form-waits-on-two-durable-commits | 1df3d5769 (one commit per form; unmeasured) |
| `023acee5f88d`, `47b64f4cebf4` | f83931226, faedc30f1 |
| `1bf47dae6f04` refusal-schemas-are-guessed-by-key | 3cf5ff927, c236d2d97 |
| `18c37812b5bc` reply-map-is-not-edn | d6942b298 |
| `9aed35a8bbc6` required-library-vars-read-as-definitions | 926326d8c |
| `6e5637a4afc8` throwable-safe-memoize-retains-classes | `seon.render.value/throwable-safe?` is no longer memoized (its docstring cites the task) |

## Ambiguous subject — listed, not guessed

| task | why |
|---|---|
| `d62c97ea13f2` supply-reads-the-connection-head | must-read `hook` is gone (1b77876af); the supply path moved into the armed wrapper — re-verify on the running system |
| `e2b07f4e2c47` the-call-state-is-an-atom | the snapshot memo is now projection-held; `snapshot-read-attributes` is still an atom — possibly fixed |
| `a8e1644f2c19` the-sci-call-hook-pays-armed-database-resolution | `hook` gone; the cost may now sit in the wrapper — re-measure |
| `5188bb532195` status-casts-a-keyword-to-a-symbol-during-boot | "not investigated"; throw site `seon.instrument/replaced-roots`, producer unknown |
| `d8f73961e44f` adoption-write-costs-sixteen-seconds | names no function; `seon.db/with-declarations` is the hot callee, the producer is the adoption write in `seon.cluster` |
| `d46e436e65f4` custody-scopes-widen | waits on the owner's delivery ruling, which decides the subject |
| `8aba59c87b88` record-jvm-evaluations-on-shared-path | partly served by d404c8ff3; JVM-mode recording (spec 8.6) owner is not yet chosen (`seon.cluster/mcp-valf`?) |
| `6a977ebf5ec9` branch-eval-answers-no-events | intermittent, cause unknown; must-read `seon.dev.mcp/failure` is in `script/` |
| `2f93fc24c48f` gate-sets-map-arity-gets-a-supplied-database | `seon.test` callers (test.clj:835, :1066) or call preparation's supply — undecided |
| `a4f403e8c826` in-ns-inside-do-is-refused | analysis attribution (seon.sci.eval admission vs seon.fn analysis) |
| `fc812316b5da` a-registration-lets-a-later-redefinition-skip-its-gate | `seon.turn/gate-function-install` vs `seon.sci.eval/evaluate` |
| `85bb0e44a4b9` a-contract-predicate-is-refused-both-ways | two stages (`definition-row`, `seon.schema/canonical-definition`); which one changes is a design choice |
| `349ec82bd051` clojure-test-parity-residue | five residues with different owners |
| `56b1dad969bd` forms-just-after-boot-cost-hundreds-of-ms | not reproduced; cause unnamed |
| `2d2e18e61f60` analysis-order-differs-from-evaluation-order | partly fixed in 582e9482e; residue owner unclear |
| `35a7b8abc200` check-fails-on-agent-registered-schemas | `seon.test/clean-request-handle` vs `seon.sci.eval/install-function-contract!` |
| `bda75cdae36e` a-check-with-nothing-to-run-answers-green | `seon.test/run` vs `seon.test/select` |
| `bc7224ccaa61` property-members-pay-a-commit-per-step | harness world construction; the fast-harness slice has no function yet |
| `d6fa80e274ac` checks-write-into-live-custody | owner decision on the gate world pending |

## Subjects that cannot hold an edge today

- **Schema resources** (ingestion reads only fn/test/ns holders): `8c53e4176def` dependency-analysis-error-is-not-an-error
  (`:seon.fn/dependency-analysis-missing-error`), `d7b76a5a0481` explanation-schemas-have-no-producer
  (`seon.instrument.explanation(s).edn`; its generators in held `seon.error` could carry it instead).
- **`script/`** (not analysed as program): `2cd1f9deb10c` mcp-failure-answers-carry-a-babashka-stack-trace
  (`script/seon/dev/mcp.clj` `failure`/`mcp-error`).
- **Dependency forks**: `b08e692710dc` each-commit-forces-the-directory-once-per-node (Konserve `write-pending-kvs!`);
  `534966f34876` is also partly Datahike gc (marked on `seon.maintenance/collect-released!`).
- **Tests only**: `b9933ca067dc` tests-redefine-default-vars (~300 sites in `test/`).

## Tasks that name no code at all

- `d8f73961e44f` adoption-write-costs-sixteen-seconds (no must-read; above).
- `2497b961dccc` refusal-names-nonexistent-define (no must-read; subject taken from its intent, `my.program/native-call-refusal`).

## Changed paths

27 src files (metadata only), this landing, `tmp/task-subjects-opus/*.patch` (8).

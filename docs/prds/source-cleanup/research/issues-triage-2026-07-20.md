---
type: research
status: active
tags: [research, issue, architecture, agent, database]
---

# Open-issue triage against the source-cleanup program (2026-07-20)

Every open note in `docs/seon/issues/` (124 files, excluding `index.md`,
`README.md`, `AGENTS.md`, and `archive/`) classified against the
source-cleanup roadmap (stages 0-5 including 1.5/1.6), its six domain PRDs,
and the live bug ledger. Classes: COVERED (planned work already fixes it —
the named unit inherits closing the note), FOLD (small addition to a named
stage), NEW GAP (problem class the plans missed), INDEPENDENT (real but
orthogonal), STALE (describes removed code; close with evidence).

Counts: COVERED 9 · FOLD 10 · NEW GAP 11 · STALE 7 · INDEPENDENT 87 = 124.

## COVERED — per-stage inheritance lists

Each implementation lane must close and archive its notes with its commit
evidence.

### Stage 1 (async-facade / warn work, lane A in flight)

- `warn-check-guidance-names-removed-conn-var` — the exact `warn.cljs:1064`
  guidance rewrite named in the stage text. Source check 2026-07-20: the
  `deref seon.db/*conn*` string is already gone from `src/seon/warn.cljs`;
  lane A should attach its commit and archive.
- `record-error-warning-check-reads-dead-attribute` — ledger B12, named in
  the roadmap. Source check: `check-record-errors` and the
  `warnings.cljs` `:seon.eval/record-error` read no longer exist in `src/`;
  only the `warn.cljs:757` comment and `repair.cljc:102` citation may
  remain. Close with the excising commit.
- `canvas-state-returned-a-promise-as-render-data` — B5 names
  `my/canvas.cljs:149-153` sync facade reads; the async `my.canvas/state`
  contract in this note is the acceptance shape for that B5 site plus the
  `ui-canvas` skill update.

### Stage 4 (config single-owner collapse and route fold-in)

- `static-routes-bypass-database-route-authority` — the stage-4 route
  collapse paragraph is this note verbatim.
- `config-apply-rebuilds-unchanged-runtime` — the stage-4 gate's
  "config-apply idempotence proof" cannot pass while apply routes through
  the full build/reconcile path; the unit closes this note.

### Stage 5 (deletions, envelope/ok? convergence, reactive collapse)

- `bespoke-reactive-loops-outside-seon-reactive` — the stage-5 items from
  `research/bespoke-reactive-sweep-2026-07-20` (serve.cljs poll,
  client.cljs advertisement machinery, `reactive/close!`) are this note's
  three sites; stages 4-5 jointly close it.
- `render-entity-converters-silently-vanish-on-unresolved-symbol` — named
  in stage 5 ("fix render.cljs silent nil-vanish", unresolved-symbol
  semantics convergence).
- `installed-schema-map-misclassified-as-database-error` — the stage-5
  ok?-discriminator ruling owns this class. **Evidence flag for the
  ruling**: this incident is a direct counterexample to blessing bare
  message-presence — Datahike's installed-schema map legitimately contains
  `:seon.error/message` as an attribute KEY, and truthy-map sniffing crashed
  every fresh execution child. The ruling must distinguish "map with the
  `:seon.error/message` entry at top level" from "map that mentions the
  attribute", or prefer `::db/ok?` presence at db boundaries.
- `turn-debug-treated-database-error-as-entity-id` — same envelope class
  (truthy error map consumed as an entity id); the stage-5 failure-payload
  convergence unit inherits the focused regression this note demands.

## FOLD — small additions to named stages

| Note | Stage | One-line addition |
|---|---|---|
| `compiled-program-contains-nilable-value-schemas` | 1.6 (G2) | Fix the three maintained `[:maybe]` registrations (`my.plan/tree-response`, `datastar/optional-view-id`, `agent.home/id` — the latter two still in source) in the same unit that makes `register!` reject banned shapes, or G2's strictness breaks the boot corpus |
| `transact-output-schema-crashed-child-on-ordinary-error` | 1.6 (G1) | `:seon.db/transact-response` becomes the union of transaction-report and error data so instrumentation never converts an errors-as-values response into a child crash — same owner as G1's db error-value work |
| `root-context-replaces-base-capability-requires` | 4 | Manifest reconciliation makes `:seon.eval/home-requires` additive over the base capability vector (root and downstream stop copying the whole ordinary vector) |
| `shadow-deps-mode-declaration-drift` | 4 | The duplicated-defaults collapse also deletes the inert npm Shadow CLI declaration, `client:*` package scripts, dead `:source-paths`, and fixed-port instructions |
| `als-unify-tx-meta` | 5 | Unify the request-scoped AsyncLocalStorage stores in `seon.db.internal` (three `AsyncLocalStorage` constructions remain at `internal.cljs:17,21,25`) and rename `with-tx-context` -> `with-tx-meta` |
| `parse-forms-entry-schema-and-bare-keys` | 5 | Namespace `parse-forms` entry keys and add the missing `:malli/schema` to `parse-forms`/`strip-code-fences` (bare-key rule conformance) |
| `debug-feed-captures-foreign-database-reads` | 5 | Thread the one captured database value into the debug/data feeds' initial-render thunks so `capture-reads` stops classifying its own reads as foreign — same reactive-plumbing territory as the stage-5 collapse |
| `turn-debug-must-project-rendered-transaction-ref` | 5 | Envelope-conformance batch: project `:seon.agent.turn/rendered-tx` to the stored basis transaction instead of the pull ref map |
| `eval-schema-tee-test-assumes-empty-schema-corpus` | 5 | Test-hygiene batch (with B9/fixture renames): make the tee test assert its owned row instead of a singleton over every schema row |
| `preflight-repair-focused-selector-relies-on-ambient-schemas` | 5 | Same test-hygiene batch: the focused selector declares its own schema dependencies |

## NEW GAP — problem classes the plans missed

### A. Corrective-steering extensions (stage 1.6 candidates)

The 1.6 audit (G1-G7) covers db error text, register! strictness,
stream-tail narration, and coercion. These four open notes are the same
principle in mechanisms the audit did not sweep; recommend adding them as
G8-G11 in `research/corrective-steering-audit-2026-07-20.md` (each satisfies
the persist-time-or-pure-render constraint):

- `agent-tool-unknown-key-acceptance` — `my.*` request schemas that are not
  closed maps silently drop misspelled keys; the agent's intent partially
  vanishes with an ok response. Fix: closed request schemas plus a directive
  unknown-key error naming the accepted keys (same shared-predicate pattern
  as G2). Persist-time; single execution.
- `narration-ghost-echo-not-neutralized` — model-echoed scaffolding
  (masthead/readline/transcript-box text) can be attributed structurally as
  a genuine inbound event. Fix is a pure render-time discrimination: only
  database-fact-backed events render as events; echoed scaffolding stays
  prose. Deterministic at first render, so byte-identity safe.
- `database-query-tuple-shape-legibility` — agents confuse Datalog
  tuple/set results with entity maps and write invalid follow-up code.
  Partly stage-1.5 territory (the universal browser renders every value
  through one schema-aware projection); the 1.6-sized remainder is a
  deterministic result-shape framing line on the eval row at record time.
  Recommend: fold the rendering half into the 1.5 acceptance list and the
  framing half into 1.6.
- `canvas-controls-hide-pending-and-failure` — `my.canvas` buttons/forms
  show neither in-flight state nor a visible structured failure, so a slow
  or failed handler leaves the human (and the AI twin's rendered state)
  with no corrective signal. Owner: `my.canvas`/`ui-canvas`; the steering
  fix is a derived pending/failure rendering on the existing control feed,
  never a stored acknowledgement flag.
- `persisted-program-error-prevents-agent-repair` — the strongest steering
  gap in the folder: after restart, a persisted program-preparation error
  closes the very eval door the agent needs to repair its program. This is
  runtime-corrects-the-agent at the program boundary, but it is
  execution/eval-owned and bigger than a persist-time rewrite — recommend a
  named bounded-repair-door unit in the execution owner's roadmap
  (database-authority-mesh), cross-referenced from 1.6 rather than absorbed
  into it.

### B. Context purity over one database value

Two notes show the byte-identity law (1.6 audit section 3) is violated
upstream of rendering, a class no PRD claims:

- `ai-context-is-not-pure-over-database-value` — the transcript's
  `result/<id>` visibility depends on a process-local cache, so the same
  immutable database value renders different historical prompt bytes across
  restarts. Directly contradicts the audit's "never a render that consults
  live state".
- `turn-retries-reread-provider-inputs` — each provider attempt re-reads
  the ambient connection, so a retry can change model config, system text,
  or coordinate after the prompt rendered. One turn must consume one frozen
  database-derived input.

Recommend one "turn/render input purity" unit (owner: `seon.agent.turn` +
transcript converter), recorded beside 1.6 since its byte-identity gate is
the acceptance measure. The render-twin double-run note (Observability
theme below) and the stage-5 debug-feed fold are the same family's edges.

### C. Callable projection corrupts agent-facing contracts

Three notes are one defect class in the shared compact callable projection —
every consumer (namespace cards, menus, `my.ns/functions`, autocomplete)
renders wrong contracts, which is steering-quality damage at scale:

- `callable-contract-output-data-becomes-phantom-arity` — implementation
  body data rendered as a bogus second positional arity;
- `compact-pure-variadic-contract-mislabels-logical-arities` — logical
  Malli arities paired positionally with the one variadic arglist;
- `program-indexer-drops-valid-specs-outside-active-schema-projection` —
  indexed contracts depend on which schema projection was active.

No PRD claims `compact-fn-head`/`callable-contract`/`client/var->fn-row`.
Recommend one bounded "callable projection correctness" unit; it also feeds
the autosuggest data-quality lane (renders are its mechanical oracle).

### D. Context block order (recorded, not folded)

- `context-block-order-is-static` — hand-set `:seon.agent.ctx/priority`
  versus the architecture's derived cache gradient. Real, resonates with
  steering, but it is an architecture delta (measure observed volatility,
  derive order), not a cleanup-sized fix. Recommend recording it as a
  context-architecture roadmap item; do not stretch 1.6 to hold it.

## STALE — close with evidence

Verified against the working tree 2026-07-20:

- `embedding-first-write-lookup-noise` — root-caused to
  `seon.server.wire/augment-tx` (`src/seon/server/wire.clj`); that
  namespace and the embedded-JVM wire server no longer exist (`src/seon/
  server/` absent). Embed now lives in `src/seon/embed.clj` on the writer;
  re-file only if the noise reproduces there.
- `eval-scratch-conn-no-commit` — describes the `*conn*` binding lost
  across the `cljs.js` await boundary; `*conn*` no longer exists in
  `src/seon/db.cljs` or `src/seon/eval.cljs` (local-connection cut).
- `legacy-replica-load-blocks-cljs-tests` — `src/seon/db/replica.cljs` is
  deleted and `rg` finds no `seon.db.replica` imports in `src/` or `test/`;
  the note's own acceptance ("the replica mechanism is deleted") is met.
- `message-wake-attaches-catch-to-the-handler-function` — the wake sites in
  `src/seon/agent/loop.cljs` (733, 762, 784, 830) all attach `.catch`
  through `(-> (js/Promise.resolve ...) (.catch ...))`; the missing-paren
  shape is gone.
- `run-opening-pulls-obsolete-run-default-attributes` — `run.cljs` now
  reads `:seon.agent/default-turn-limit`/`default-deadline-ms`; no
  `:seon.agent.run/default-*` pull remains. One stale docstring mention at
  `src/seon/agent/ctx.cljs:281` — fix that line when archiving.
- `my-ns-functions-points-to-removed-renderer` — `src/my/ns.cljs` contains
  no `render-namespace` reference and now implements the demanded
  `full!`/`compact!` operations; verify the focused `my.ns-test` coverage
  then archive.
- `issue-authority-frontmatter-drift-blocks-index` — B13 CLOSED
  (`927d5b6e`+`9d638b57`): 115 notes normalized, index regenerates clean.
  The note itself was left open; archive it against those commits.

## INDEPENDENT — themes and counts (87)

Real backlog, orthogonal to source-cleanup. Grouped so the owner sees the
shape; no ordering implied.

- **Restore/branch lifecycle (6)** — `restore-completion-cannot-precede-admission`,
  `restore-completion-reuses-operator-intent-identity`,
  `restore-intent-does-not-freeze-client-artifact`,
  `restore-intent-lacks-exclusive-writer-fence`,
  `restore-writer-admin-transition-is-unimplemented`,
  `planned-restart-cannot-observe-writer-drain-result`. A coherent
  blocker-heavy cluster; five of six are blockers and belong to one restore
  owner.
- **Execution-child / agent-runtime reliability (13)** —
  `core-selected-render-errors-bypass-crash-policy`,
  `database-program-query-results-can-be-sets`,
  `eval-process-isolation-memory-containment`,
  `execution-artifact-packages-local-datahike`,
  `execution-child-program-load-omitted-instrumentation`,
  `execution-children-retain-hundreds-of-megabytes`,
  `execution-config-pull-had-one-node-budget`,
  `execution-process-proof-seeds-incomplete-schema-population`,
  `execution-result-diagnostic-retained-invalid-map-key`,
  `nested-authored-render-hides-child-reload`,
  `rendering-and-turns-collided-in-one-execution-child`,
  `wake-and-replay-can-drive-the-same-open-run`,
  `welcome-canvas-received-the-agent-under-the-wrong-key` (runtime.cljs:510
  still builds with `:seon.render/entity` — verify current fix state).
- **Dev operator / build & test infrastructure (12)** —
  `bootstrap-analyzer-api-emits-undeclared-var-warnings`,
  `changed-test-hooks-queue-stale-runs-behind-active-owner` (blocker),
  `changed-test-new-cljs-namespace-misses-runtime-file`,
  `clean-or-force-evidence-can-cross-or-falsely-report-absence` (blocker),
  `dead-process-group-leader-blocks-safe-subtree-drain` (blocker),
  `dependency-preparation-can-crash-inside-clojure-hashmap`,
  `shadow-runtime-stops-reconnecting`,
  `shared-bootstrap-output-mutates-running-artifact` (blocker),
  `tailwind-node-module-register-deprecation`,
  `test-runner-does-not-prepare-selected-git-dependencies`,
  `watcher-status-conflates-drift-with-failure`,
  `worktree-edit-hook-checkout-drift`.
- **Inspect / eval harness (9)** — `inspect-concurrent-agent-messages`,
  `inspect-live-cluster-caller-drift`,
  `inspect-model-transport-evidence-is-incomplete`,
  `inspect-pod-solver-cannot-address-existing-agent`,
  `inspect-product-snapshot-assumes-nonexistent-evidence`,
  `inspect-reachability-assumes-nonexistent-evidence`,
  `inspect-source-dependency-is-not-content-pinned`,
  `final-agent-evidence-pulled-a-partial-config-without-identity`,
  `transcript-decay-does-not-bound-total-context` (mechanical bound fixed;
  only the Inspect schedule comparison remains, so the remainder is
  harness-side).
- **Datahike fork internals (8)** — `bound-temporal-index-page-work`,
  `datahike-cljs-cardinality-many-collapses-large-bigints`,
  `datahike-execute-many-predicate-query-fails`,
  `datahike-force-branch-does-not-preserve-secondary-root`,
  `datahike-http-remote-connection-identity-mismatch`,
  `datahike-read-dependencies-miss-valid-query-and-pull-inputs`,
  `multi-source-query-cache-retains-foreign-database-values`,
  `temporal-query-work-is-not-shared`.
- **my.plan (6)** — `plan-address-step-priority`,
  `plan-allocation-builder-set-database-value`,
  `plan-completion-verification-evidence`,
  `plan-reconcile-scope-can-delete-unseen-work`,
  `plan-reopen-cross-agent-authority`,
  `single-entity-pulls-budgeted-as-one-result-node`.
- **Database session / protocol mesh (5)** —
  `atomic-client-authority-cut-in-progress` (active lane; its own
  invariants forbid checkpoints between cohorts — coordinate before any
  stage-2 freeze), `database-protocol-coordinate-is-incomplete`,
  `database-session-concurrent-open-is-not-shared`,
  `uds-codec-capacity-can-delay-control-entry`,
  `uds-fragment-accumulation-recopies-complete-prefix`.
- **Eval/transcript mechanics (5)** — `multi-form-eval-order-is-not-durable`
  (largely improved per the note), `preflight-repair-consumed-referred-macros`,
  `selfhost-cljs-test-is-thunk-resolution`,
  `successful-eval-receipt-called-state-on-nil`,
  `transcript-grouped-reads-omitted-their-database-source`.
- **Web UI / reactive (5)** — `datastar-feed-retains-failed-render-after-hot-reload`
  (blocker; adjacent to but not claimed by the stage-5 reactive collapse —
  candidate follow-on for that lane),
  `lazy-view-unit-activation-drops-read-observations` (blocker, same
  adjacency), `pod-remains-ready-after-web-listener-loss`,
  `root-page-is-an-ordinary-agent-layout`,
  `web-session-navigation-provenance-is-missing`.
- **Downstream / acme / autocomplete / diffusion (5)** —
  `acme-operator-migration-drift`, `acme-typeahead-worker-unavailable`,
  `autocomplete-data-quality-pipeline-drift`,
  `autocomplete-worktree-evidence-preservation`, `lora-audit-runner-drift`.
- **Context/toolkit capability (3)** — `my-ns-compact-can-hide-namespace`
  (verify against the new `my.ns/full!`/`compact!` and the namespaces
  renderer's compact support before working it),
  `configured-turn-limit-masks-mode-specific-budget` (remainder: measured
  profiles), `namespace-addressed-resident-agents` (design/feature).
- **Observability / performance (3)** —
  `agent-turns-lack-database-read-cost-attribution`,
  `render-twin-runs-function-twice` (cross-ref NEW GAP B),
  `surface-recency-recomputed`.
- **Packaging / deployment (2)** —
  `container-launch-omits-execution-artifact` (blocker),
  `downstream-runtime-package-is-not-self-contained` (blocker).
- **Docs / coverage backlog (3)** — `stale-reference-docs`,
  `subagents-block-is-implemented-but-not-installed` (doc-accuracy only;
  absence is deliberate sequencing),
  `removed-embedded-multiagent-coverage-needs-owner`.
- **Provider (1)** — `kimi-k3-continuation-compatibility`.
- **Platform (1)** — `bun-enterwith-toplevel-segfault`.

## Roadmap-side staleness noticed while triaging

Stage 5's own text has drifted ahead of the tree (no action beyond ledger
hygiene):

- `dev/storage-shootout.js` is already deleted;
- the `reference-code/integrant` submodule is already removed from
  `.gitmodules`;
- `deprecated-skill-render-functions-indexed` is already archived
  (`docs/seon/issues/archive/`), so the stage-5 wikilink points at a
  resolved note.

Also: the stage-1/B12 and lane-A warn targets appear already excised from
source (see COVERED stage 1) — the lane should confirm and move those rows
to CLOSED rather than re-implement.

## Unclassified

None. All 124 open notes are named exactly once above.

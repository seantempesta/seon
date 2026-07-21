---
type: research
status: completed
tags: [research, agent, database]
---

# Inspect and autocomplete source/evidence audit — 2026-07-14

## Scope and verdict

This audit establishes what the current checkout can honestly claim about
Inspect, autocomplete, preserved lane evidence, and the planned
large-planner/small-executor experiments. It is read-only with respect to
runtime code, clusters, old worktrees, and model providers. It made no paid
model call. The separately owned ACME refinement checkout was not entered or
inspected, and the protected shared-schema research file was not read, hashed,
staged, moved, or changed.

The result is a useful but incomplete foundation:

- Inspect already owns a substantial offline task/scorer/freeze/scorecard
  system, standard `inspect_evals` adapters, deterministic oracles, planning
  scorers, and a historical typeahead replay corpus.
- The current static pod solver correctly preserves an upstream task's solver
  formatting and scorer, rather than replacing the benchmark with a Seon-only
  imitation.
- Live per-sample evaluation remains deliberately disabled because the
  operator has no token-fenced lease. Static-URL runs do not supply resource
  ownership, restart authority, or cleanup safety.
- The claimed Inspect source pin is not real: the installed framework, current
  reference checkout, and declared local-directory dependency name different
  bytes. The same problem affects the separately installed `inspect_evals`
  package, and Python `openai` is version-locked only indirectly by the current
  lockfile.
- The ClojureScript autocomplete exporter is a real as-of database projection,
  but not yet a canonical evidence artifact. It records a bare database name,
  basis `t`, and Git SHA instead of a complete immutable database coordinate,
  runtime/config/profile identities, schema closure, frozen row membership,
  staging verdicts, and retained rejection evidence.
- The preserved small-model measurements are requirements and calibration
  cases, not importable runtime/scorer code. Training remains paused.

The implementation order is therefore source pinning, lease ownership,
canonical export, preserved-evidence replay, ACME handback review, then the
model ladder. Running a small model sooner would produce a number whose
framework, source world, and task ownership cannot be reproduced.

## Immutable dependency and mechanism ledger

Snapshot repository HEAD was
`dc778de958c072e6999de128585168d522ab80ec`. File identities below are
SHA-256 unless a Git identity is named.

| Dependency or mechanism | Selected or observed identity | Exact source read | Constraint or finding |
|---|---|---|---|
| Inspect AI declaration | `src-inspect-ai/pyproject.toml` declares mutable local path `../reference-code/inspect-ai`; `uv.lock` hash `a7933b792c2b8f8384f85d7c97d7c271b598a7e09c65eb41be37bc44e3e7cec5` records a directory source, not its bytes | `src-inspect-ai/pyproject.toml`, `uv.lock` | The environment lock does not content-identify the sibling checkout. |
| Inspect AI installed runtime | `0.1.dev1+g92dd737b9` in `src-inspect-ai/.venv` | installed package used only to identify the executed gate | The offline suite proves these installed bytes, not the reference checkout below. |
| Inspect AI reference source | `05322696a0f784ec399ef6abbafd3d2a250ea9cc`, describes `0.3.246-dirty`; dirty `src/inspect_ai/_view/ts-mono` | `solver/_solver.py`, `scorer/_scorer.py`, `_eval/eval.py`, `log/_log.py`, `model/_model.py` | Exact source mismatch is a run-admission failure, not harmless documentation drift. |
| Inspect Evals reference source | `97c99f5f6507fc5d1449fe3247f267d591f64350`, tag `v0.14.3`, clean | `reference-code/inspect-evals/src/inspect_evals`, especially BFCL and sandboxed task/scorer definitions | Standard task pins and scorers are real upstream mechanisms. |
| Inspect Evals installed runtime | `0.0.1.dev1+unknown.gce900d638` | installed metadata plus current imports in `catalog.py` and tests | Installed bytes do not match the inspected `v0.14.3` checkout and are not declared in `pyproject.toml`; a fresh environment cannot reproduce them from this package alone. |
| Python OpenAI dependency | installed `openai 2.45.0`; `pyproject.toml` declares unbounded `openai` | Inspect provider use through the installed framework; Seon's live pod provider is a different Node boundary | The current `uv.lock` freezes one resolution, but the manifest does not express a supported range or include provider identity in scorecard admission. |
| Node OpenAI SDK | npm lock `openai 6.42.0`; reference source `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472`, tag `v6.42.0` | `reference-code/openai-node/src/client.ts`, `internal/request-options.ts`, `lib/ChatCompletionStream.ts`; `src/seon/ai/openai_compat.cljs` | `maxRetries: 0` correctly leaves retry authority in Seon. SDK request options accept `AbortSignal`; provider cancellation must use that existing seam. |
| Node Anthropic SDK | npm lock `@anthropic-ai/sdk 0.104.2`; reference source `fbee0d149ce08532885d766d9b1dc99133181d8e`, tag `sdk-v0.104.2` | `reference-code/anthropic-sdk-typescript/src/client.ts`, `internal/request-options.ts`, `lib/MessageStream.ts`; `src/seon/ai/anthropic.cljs` | The stream owns `.finalMessage()` and forwards a signal. Seon's one-attempt adapter matches the SDK, while runtime cancellation remains a separate runtime-correctness gap. |
| Inspect solver contract | reference checkout above | `inspect_ai/solver/_solver.py` | A solver transforms `TaskState`; its supplied `Generate` may loop tool calls. Seon's solver may own the model loop, but adapters must preserve upstream prompt/parse steps that call `generate` internally. |
| Inspect scorer contract | reference checkout above | `inspect_ai/scorer/_scorer.py`, `scorer/_metric.py`, `log/_log.py` | A scorer consumes final `TaskState` plus `Target` and returns `Score` with metadata. Full sample messages/events/scores belong in Inspect logs; a summary JSONL is not a transcript substitute. |
| Inspect eval override | reference checkout above | `inspect_ai/_eval/eval.py` | `eval(..., solver=...)` is the supported override. `catalog.swap_generate` and `pod_backed` correctly retain the benchmark's own dataset, solver formatting/parsing, and scorer. |
| Dataset and image freeze | `evals/datasets.lock` hash `ff2496fa6fcf2efe592335c4d7b31d728c162de10da08ce49dc85cee72231ee1`, schema version 1 | `seon_inspect/freeze.py`, lock contents | External sample ids, corpus hashes, upstream pins, canaries, and selected container digests exist. Several bespoke rows remain `pending-generator`; the lock does not pin the Inspect framework itself. |
| Scorecard | `evals/scorecard.jsonl` hash `dae5084aae8cd34cb5557f796443c2eaa59965285fd82b77ca07bb257e7cb65f` | `seon_inspect/scorecard.py` | Append-only reducers, flake exclusion, model-config capture, and regression alarms exist. Historical rows omit framework, task-source, full cluster coordinate, artifact/config digests, and scorer identity. |
| Canonical autocomplete projection owner | `src/seon/repl/autocomplete.cljs` hash `554a8ae9a93db66c64fc31ac9790078353f80054aa957971719e7f2233d88ee4` | `context`, `export!`, `seon.agent.ctx/render-context`, `seon.agent.ctx.namespaces/compact-fn-head` | The exporter uses the real render path and as-of db value, but emits presentation strings and partial provenance rather than a versioned schema-closed artifact. |
| Historical typeahead corpus | `evals/typeahead_replay.corpus.json` hash `2a31a33d31e7bb941ff662f45a96e292fedabb4bbe0f9b100b7a073c11b02dae`; generated 2026-07-11 from cluster `acme`, 10 rows | `typeahead_corpus.py`, `tasks/typeahead_replay.py` | It preserves prompt/reply blob hashes, selected verbatim sections, offers, task intent, predicates, and model config. It lacks full database/source/runtime identity and depends on direct cluster blob paths plus arbitrary writer-REPL forms. |
| Canonical editable tool surface | repository program graph at snapshot HEAD; `my.*` source plus registered Malli schemas | `docs/seon/architecture/toolkit.md`, `src/my/`, home requirements, program indexing | Experiment context must be generated from actual function/schema facts. A benchmark-only tool catalog or prompt manual would invalidate the experiment. |
| Preserved old-lane evidence | hashes and database identities in the preservation manifest and legacy retirement/read-back audits | current checkout reports only; no old worktree mutation | Old scorer/probe/data bytes remain evidence-gated. None authorizes a cherry-pick or cleanup. |

The provider ledgers intentionally distinguish two `openai` dependencies:
Python Inspect's provider package and the Node SDK used by the Seon pod. A
matching brand name does not make them one runtime or one provenance field.

## Inspect behavior that truly exists

### Correct integration mechanisms

Current source contains these maintained, test-backed mechanisms:

- `seon_pod_solver` drives `POST /agents/run`; the Seon pod owns its own agent
  loop while Inspect owns the sample and host-side score.
- `catalog.swap_generate` replaces only generation. It wraps composite solver
  steps such as multiple-choice formatting/parsing so their internal
  `generate` callback reaches Seon, and uses a pod-run marker to avoid a second
  fallback run. This matches Inspect's actual solver protocol.
- `catalog.BENCHES` identifies case-1 text/final-answer tasks, BFCL's
  host-side AST subset, and the separate SWE-bench sandbox arm. It does not
  pretend HumanEval, GAIA, or sandbox/tool tasks fit the plain pod door.
- BFCL retains the upstream scorer and synthesizes `ToolCall` values from a
  bounded adapter. The historical Clojure-form A/B remains evidence that a
  text form is not equivalent to an eval-native registered function.
- `freeze.py` records deterministic splits, corpus hashes, upstream pins,
  canaries, and selected image digests; milestone and test access have
  structural guards.
- task-specific deterministic oracles exist for generated shell, file, web,
  Clojure, and planning rows. Planning checks database-derived plan history,
  report ordering, provenance, and restart continuity rather than trusting a
  final narrative.
- `scorecard.py` computes pass rate, `pass_at_k`, `pass_hat_k`, flake rate, and
  the standing dev regression alarm. Pod-reported model configuration is
  captured when the endpoint supplies it.
- selected run drivers copy native `.eval` logs into their evidence directory.
  Those logs, not the scorecard summary, are the Inspect-native sample record.
- the current offline boundary is healthy for the installed environment:
  `311 passed, 8 skipped, 13 warnings` in 7.21 seconds on 2026-07-14.

### Claims that do not yet hold

- A fresh environment does not reproduce the inspected framework source.
  Installed Inspect is `g92dd737b9`; the reference checkout is a dirty
  `05322696...`; the local path declaration content-pins neither. Installed
  `inspect_evals` similarly differs from the inspected checkout.
- `save_eval_logs` is best-effort and silently continues on copy errors. An
  accepted scored run must instead fail admission/finalization if its required
  raw log and provenance bundle are absent.
- Scorecard rows do not identify Inspect/Inspect Evals source, scorer
  implementation/config, Python lock, task definition hash, operator artifact,
  config manifest, or complete database coordinate. Historical rows are useful
  measurements but not fresh-environment reproduction proofs.
- Per-sample create, fork, restart, and release intentionally raise
  `ClusterLeaseUnavailable`. There is no owner token, target allocator,
  content-pinned artifact selection, or idempotent token-fenced release.
- Static URL mode shares a long-lived cluster. It can be useful for an
  explicitly coordinated smoke, but cannot establish sample isolation or safe
  cancellation/cleanup.
- `typeahead_corpus.py` now requires explicit endpoints, which is better than
  guessed ports, but it still evaluates raw `datahike.api` forms through the
  writer REPL and reads `data/clusters/<name>/blobs` directly. That bypasses the
  typed database/debug/blob boundaries and cannot be the production lease
  consumer.
- The live planning scorer is pure and well-tested, while its current driver
  still depends on the missing create/restart/release lease.

## Autocomplete and data-export truth

### What the current exporter gets right

`seon.repl.autocomplete/context` calls the ordinary
`seon.agent.ctx/render-context` producer with an autocomplete profile. At
export, `export!` renders an as-of database value, re-renders it to check byte
determinism, selects successful eval sources in order, resolves function
symbols through the actual indexed program graph and home aliases, and emits
the same inert compact function heads used by namespace context. It rejects
explicitly excluded turns and returns honest skip/card counters as an error
envelope.

Those are the correct one-mechanism foundations. They supersede scratch Python
renderers that strip braces or fabricate `(defn ...)` cards.

### Why it is not yet the canonical artifact

The current row is `context/cards/target/meta`, where metadata contains
`turn-id`, agent id, bare `basis-t`, database-name string, projection Git SHA,
coverage, and optional rating. That leaves these gaps:

- **No complete source coordinate.** The target requires
  `{database-id, branch, commit-id, t}`. A bare `t` plus directory-derived name
  cannot distinguish lineage or reproduce the exact program/data world.
- **No content pin.** A Git commit does not identify a dirty tree, runtime
  bundle, bootstrap, config manifest, profile, renderer source closure, npm
  lock, or maintained dependency graph.
- **No artifact schema/version.** Consumers cannot reject incompatible row
  shapes or projection semantics.
- **No referenced-schema closure.** Function cards can cite registered request,
  response, and nested shapes that the exported input never emits once.
- **No frozen row/split manifest.** The separate Inspect dataset lock does not
  assign stable identities and splits to autocomplete rows.
- **No explicit target semantics.** Observed historical eval bundles,
  counterfactual re-projections, and substantive-next-form targets remain
  distinguishable only by convention. Mechanical `in-ns` must never be
  silently removed or treated as substantive work.
- **No current-world replay verdict.** Successful historical eval rows are not
  proof that the target parses, resolves, executes, and produces the expected
  database effect under the current schema/program graph.
- **No rejection corpus.** Skips are counters, not addressable rows with reason,
  source identity, and replay evidence. That destroys the most useful data for
  improving schemas and tool names.
- **Filesystem write is the primary return.** A dated filename is not a content
  address, and wall-clock naming prevents byte-identical repeated export from
  producing the same manifest identity.

`src-needle` remains a downstream model-work consumer. Its staged-world and
fair-scoring experiments contain useful failure classes, but hard-coded pinned
checkout paths, presentation parsers, static function indexes, and fake-card
renderers prevent it from owning the canonical export or score.

## Preserved lane evidence and retirement gates

No old lane is safe merely because its commits were integrated. Preserve these
evidence classes before cleanup:

| Evidence | Current meaning | Gate before retirement or promotion |
|---|---|---|
| Stable continuation design/probe/raw outputs | First-form stopping improved shape (`.19` to `.81` balanced single-form yield on the reported 16-row Qwen3.5-2B slice) but did not establish accuracy | Preserve the recorded report/probe/raw-output hashes; reproduce scanner acceptance through current Inspect before claiming a supported serving primitive. |
| Stable fair scorer/report | Layered creative-alternative calibration and the reported `.264` to `.436` audited frontier change | Re-express its acceptance cases in Inspect over canonical rows; do not import its pinned-worktree staging or obsolete card grammar. |
| Pin LoRA audit | 149 of 557 retained pairs hard-failed the historical live REPL | Preserve fixture/result identity until current-world replay reproduces the important classes; then remove the private Shadow runner in the same unit. |
| Display-v3 exports and database | Unique raw/v2/v3 rows plus a live legacy database with autocomplete facts | Quiesce owner, package closed bytes, content-hash, extract/verify, historical read-back, durable off-worktree promotion, and owner acceptance. Never import the v3 renderer. |
| Plan-pilot database | Real-world planning/database-memory seed and live behavioral evidence | Archive exact identity and prove a current read-only canonical export/staging operation writing only outside the archive. |
| Stable/display legacy processes | Live mutable evidence on the recorded legacy ports | Stop pod first, capture final identity through its writer, stop writer, then archive; no current operator may adopt the old store. |
| Current checkout legacy ACME package | Internally packaged/read back, but stored on the same volume | Promote to owner-approved durable storage and verify there before deleting source bytes. |
| Active ACME refinement lane | User-owned in-progress commits and evidence | Excluded from retirement and this audit until explicit handback. |

`seon-plan-fix` remains the only old checkout classified as eligible for later
user-authorized worktree removal. This report executes no cleanup and broadens
no deletion authority.

## ACME refinement handback review procedure

The active lane is not an informal shared-tree input. After its owner explicitly
hands it back:

1. Record the handed-back branch, base, tip, commit range, dirty/untracked
   inventory, claimed tests, model calls, dataset/log paths, and exact ACME
   cluster/artifact/config identities. The owner must identify which bytes are
   source versus generated or private evidence.
2. Review commits in order from Git objects in the integration checkout. Do not
   enter, clean, reset, or run the owner checkout. Reject a range whose base or
   evidence identity moved after handback.
3. Classify every change as canonical `my.*` function/schema, protected
   `seon.*` substrate, generated default context/program graph, test, Inspect
   task/scorer, ACME-only downstream customization, or evidence. There is no
   catch-all “tool refinement” category.
4. For each function change, read its actual dependencies and first-party
   idioms, then verify namespaced map contracts, Malli schemas, unknown-key
   behavior, errors-as-values, bounds, docstring discoverability, and absence
   of a parallel registry/context/tool protocol.
5. Reproduce deterministic tests and offline Inspect cases before importing
   model-derived conclusions. A failure must retain the exact sample, visible
   function/schema context, attempted call, envelope, and scorer outcome.
6. Integrate coherent commits or manually reimplement the accepted mechanism
   in its current owner when the branch ancestry is stale. Never wholesale-copy
   ACME config, generated bundles, model checkpoints, secrets, database bytes,
   or benchmark-only prompt prose into Seon.
7. Prove the default cluster first: focused tests, full relevant boundary, live
   function discovery/call, database effect, and generated context. Only then
   rebuild the ACME artifact flavor and rerun the same sample there.
8. Record accepted, superseded, and rejected commits plus evidence disposition
   in this PRD. Only after durable evidence promotion and owner acceptance may
   that active worktree/branch enter the existing retirement audit.

## Large-planner/small-executor experiment ladder

The experiment tests whether a stronger model can hand down strategy while a
small model reliably encodes and executes it through the ordinary Seon surface.
It does not test whether more prompt prose can compensate for unclear tools.

### Task family

Use representative ordinary system work with deterministic outcomes:

- inspect/query existing database facts;
- transform data and derive an answer;
- register a small domain shape and store/query schema-valid facts;
- maintain a durable multi-step plan across a pod restart;
- recover from one intentionally invalid call by reading its error value;
- report the verified result through the ordinary user/message or canvas path.

Each sample declares outcome predicates, not Seon function names. Function
names, request keys, and schemas come only from the generated real program
surface.

### Arms

1. **Large planner → small executor.** The planner produces short goal/ordered
   step/expectation text. The small executor must encode it into `my.plan`
   facts through ordinary functions, execute ready steps, verify outcomes, and
   close them honestly.
2. **Small executor alone.** Same task and budget, no handed-down plan. This
   isolates the value of strategy handoff.
3. **Large model end to end.** Reference for capability ceiling and frontier
   token cost, not the default production policy.
4. **Pretransacted plan diagnostic.** The same plan is inserted as valid facts
   before the small worker starts. This separates plan-understanding/execution
   from plan-encoding failures; it is a diagnostic arm, not the intended user
   flow.

### Rungs

1. Pure scorer fixtures: good, missing-plan, premature-close, fabricated reply,
   wrong-provenance, restart-loss, and incomplete-address cases.
2. Mock solver/worker choreography with deterministic transcripts and exact
   database outcome rows.
3. Local simple model with schema/function definitions only, beginning at the
   smallest available model that can emit parseable forms; no paid calls.
4. 0.8B/2B/4B-or-smaller comparison where locally available, fixed quantization,
   seed, decoding bounds, context, and artifact. Simpler is the stronger signal
   only when the oracle and harness are already green.
5. One paid large-planner reference after source pin, lease, export, and offline
   gates pass. The executor model remains fixed across planner arms.
6. Battery-wide repeat after any tool/schema change. Retain a refinement only
   when the target failure improves without regressing the broader ordinary-work
   battery.

### Measurements

- task outcome at fixed wall-clock/work budget;
- plan encoding fidelity and time-to-durable-plan;
- steps closed with verified expectation divided by steps closed;
- restart continuity from the same agent/database coordinate;
- parse, schema, eval, database-effect, and report-delivery results separately;
- recovery after an error value;
- tool discovery/call success by function and request field;
- frontier input/output tokens per completed task;
- small-executor turns/forms, latency, and flake taxonomy;
- complete source/model/provider/config/cluster/scorer provenance.

When a simple model fails, first classify the failure: absent function,
undiscoverable name, ambiguous request field, schema too broad/narrow, envelope
illegibility, missing example shape, plan-authority defect, model reasoning
miss, or harness defect. Only general surface failures justify changing a
function/schema/docstring. Prompt padding and regex repair are rejected.

## Ordered implementation slices

1. **Pin and admit sources.** Make Inspect, Inspect Evals, Python provider
   dependencies, task source, and lock identities immutable; reject dirty or
   mismatched bytes before task construction. Extend every accepted run bundle
   with those identities.
2. **Publish the operator lease.** One token-fenced create/restart/release
   contract returns cluster/database identity, complete database coordinate,
   artifact/config/source digests, and dynamic web/CLJ/CLJS endpoints. Release
   is idempotent and can affect only its token's resources.
3. **Migrate live consumers.** Replace raw writer REPL, direct blob paths,
   private port files, and static lifecycle assumptions with the lease plus
   typed MCP/debug/blob/database boundaries. Preserve native Inspect `.eval`
   logs as a required finalization step.
4. **Define the canonical autocomplete artifact.** Schema-register one manifest
   and row/rejection contract with full source coordinate, projection semantics,
   schema closure, stable row ids, content digests, and byte-identical export at
   a fixed coordinate.
5. **Freeze and replay.** Assign immutable splits; stage candidate worlds from
   facts; render through the serving path; parse/eval through the current
   boundary; derive database outcomes; retain both accepted and rejected rows.
6. **Rebuild the scorer in Inspect.** Port the fair-scoring acceptance cases as
   layered metrics over canonical artifacts. Label historical model scores
   non-comparable unless replayed.
7. **Review ACME handback.** Apply the commit/evidence procedure above; land
   only canonical surface changes and prove default before ACME.
8. **Run the model ladder.** Deterministic and offline first, then local simple
   models, then the bounded planner/executor reference. Iterate on tools, not a
   benchmark-only context.
9. **Retire superseded lanes.** Only after accepted evidence is durably promoted,
   read back, and owner-approved.

Slices 1, 4, and preserved-evidence packaging can be researched independently.
Slice 3 depends on slice 2. Scoring depends on slice 4. Model trials depend on
1–7.

## Acceptance matrix

| Layer | Gate | Required evidence |
|---|---|---|
| Deterministic | Source admission | Fresh environment resolves exact clean Inspect/Inspect Evals/provider/task bytes; deliberate dirty or revision mismatch fails before a task constructs. |
| Deterministic | Solver/scorer contract | Fixtures prove upstream prompt/parse steps remain, no double pod run occurs, scorer metadata survives, and missing native log rejects finalization. |
| Deterministic | Export identity | Repeated export at one complete coordinate is byte-identical; branch/commit/profile/source change yields a new content identity. |
| Deterministic | Schema/split/rejection | Referenced schemas close exactly once; row ids and split assignments cannot drift silently; every rejection has an addressable reason and source coordinate. |
| Deterministic | Planning scorer | Fabricated narration, missing plan, premature close, wrong provenance, restart loss, or missing delivery cannot pass. |
| Offline | Python suite | Current suite stays green from the newly pinned environment; current installed baseline is 311 passed / 8 skipped. |
| Offline | Historical evidence calibration | LoRA failure classes, fair-scoring reasonable alternatives/errors, and continuation scanner cases reproduce over canonical fixtures without old worktrees. |
| Offline | Simple-model fixtures | Mock and local-model executions preserve full context/call/envelope/outcome evidence; no network or paid provider is required. |
| Live | Lease lifecycle | Concurrent samples receive disjoint targets; CLJ and CLJS endpoints resolve dynamically; restart preserves lease identity; cancellation/timeout releases only owned resources. |
| Live | Runtime truth | A sample records the exact prompt/reply blobs, complete database coordinate, datoms/evals, artifact/config/source identities, native Inspect log, and scorer result. |
| Live | Planner/executor | The large planner hands down strategy, the fixed small executor encodes a durable plan, survives restart, completes read/process/write/report work, and recovers from an error value. |
| Live | Cross-cluster | Default proof passes first; the same canonical tool/context and scorer behavior then passes on ACME without copied forks, guessed ports, or benchmark-only context. |
| Graduation | Battery and preservation | Improvement survives the full frozen battery; accepted/rejected evidence is durable; no required score depends on an old worktree, scratch scorer, hidden prompt, or mutable source directory. |

## Issue ownership

- [[docs/seon/issues/inspect-source-dependency-is-not-content-pinned]] owns
  framework/provider/task source admission and run provenance.
- [[docs/seon/issues/inspect-live-cluster-caller-drift]] owns the token-fenced
  operator lease and live caller migration.
- [[docs/seon/issues/autocomplete-data-quality-pipeline-drift]] owns the
  schema-closed canonical export, replay, split, and Inspect scorer.
- [[docs/seon/issues/autocomplete-worktree-evidence-preservation]] owns durable
  promotion/read-back and retirement gates.
- [[docs/seon/issues/lora-audit-runner-drift]] owns removal of the private
  audit runner after equivalent current evidence exists.
- [[docs/seon/issues/deprecated-skill-render-functions-indexed]] owns stale
  callable eligibility at the program graph, never an exporter blacklist.

No additional issue is needed: every newly sharpened gap has one existing
owner above.

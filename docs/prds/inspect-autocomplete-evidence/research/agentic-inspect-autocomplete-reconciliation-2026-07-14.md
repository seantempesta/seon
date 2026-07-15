---
type: research
status: completed
tags: [research, agent, database, schema]
---

# Agentic Inspect and autocomplete reconciliation — 2026-07-14

## Question and verdict

This audit reconciles the completed ACME agent-testing lane, current Inspect
source, autocomplete work, old branches/worktrees, and the two successor PRDs
that now own the remaining work. It reviewed Git objects and ignored-state
inventories read-only, did not switch branches or enter a model/provider run,
and did not read or alter the protected shared-schema report.

There is no missing source range to merge from
`codex/acme-agentic-tool-refinement`. Its eight commits are integrated,
patch-equivalent, or superseded on `codex/runtime-reliability-refactor`, and
the dedicated worktree is clean. Current source also contains three important
post-handback gains that the earlier Inspect audit did not credit:

- exact turn evidence and complete database coordinates survive in native
  Inspect sample metadata for static-URL runs;
- BFCL's contradictory bare-JSON instruction is replaced by one ordinary
  `(complete "...")` call while its upstream AST scorer stays unchanged; and
- positive `:seon.fn/agent-facing?` program facts drive compact namespace
  cards and both function menus, so public implementation functions are no
  longer silently presented as tools.

The remaining work is not another merge. It is four dependency-ordered
mechanisms: reproducible source/run admission, an ownership-fenced operator
lease, one schema-closed content-addressed autocomplete artifact, and Inspect
replay/scoring over that artifact. Only then is a broad small-model or
large-planner/small-executor comparison meaningful.

## Dependency and mechanism ledger

Audit snapshot HEAD was `72768a59a4a771cf824e438f22641f0a931709e0`;
later top-level documentation commits do not change the findings below.

| Dependency or mechanism | Selected identity | Source and first-party owner read | Current finding |
|---|---|---|---|
| Inspect AI | root Gitlink `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; installed `0.3.247.dev0+g05322696a.d20260715` | `reference-code/inspect-ai/src/inspect_ai/solver/_task_state.py`, `_eval/task/run.py`, `src-inspect-ai/pyproject.toml`, `uv.lock` | The synchronized Python install now names the selected commit, improving on the earlier stale environment. The local-directory lock is still not self-authenticating, and the nested viewer submodule is dirty at `f3588038…` instead of the parent-selected revision. No run-admission check rejects source drift. |
| Inspect Evals | root Gitlink `97c99f5f6507fc5d1449fe3247f267d591f64350` / `v0.14.3`; installed `0.0.1.dev1+unknown.gce900d638` | `reference-code/inspect-evals/src/inspect_evals/bfcl/solve/single_turn_solver.py`, `score/scorer.py`; `seon_inspect.catalog` | The inspected task/scorer source is pinned by Git, but `src-inspect-ai` does not declare that local dependency and the installed distribution does not identify the selected Gitlink. |
| Python OpenAI provider | installed and locked `2.45.0`; manifest says unbounded `openai` | `src-inspect-ai/pyproject.toml`, `uv.lock`; provider is reached through Inspect, not the Node pod SDK | The lock currently reproduces one wheel, but supported/admitted provider identity is not explicit in accepted evidence. |
| Inspect sample evidence | Inspect commit above | `TaskState.metadata` and `make_eval_sample`; `seon_inspect.solver._record_result`; `seon.web.serve` | Current static runs retain the final complete database coordinate plus ordered prompt/reply/error turn evidence in the native `.eval`. This is forensic retention, not sample lifecycle isolation. |
| BFCL bridge and scorer | Inspect Evals `v0.14.3`, task `5-B` | `seon_inspect.bfcl_adapter`; upstream `_extract_tool_calls` and `ast_match`; focused tests | The native `complete` bridge landed and one identical Qwen 3.5 2B sample moved from a four-turn no-form failure to one eval, `:completed`, and upstream score 1.0. It proves an adapter correction, not general tool usability. |
| Datahike | `6f90b339768b1a02066dce3b6fcc93a200758fcc` | `reference-code/datahike/`; complete coordinates through `seon.db`, turn capture, and autocomplete | Turn export now resolves one immutable `{database-id, branch, commit-id, t}` point. The earlier bare-basis finding is superseded. |
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f`, application `0.20.0` | `reference-code/malli/`; `seon.schema`; program facts and namespace renderer | Function eligibility and complete contracts are database facts. The export still does not emit a shared transitive schema closure once per artifact. |
| ClojureScript | reference `946d75f3483c0c8e784e6668bff2c71a25619a77`, runtime `1.12.145` | `reference-code/clojurescript/`; `seon.repl.autocomplete`, analyzer/indexing/eval tee | The current exporter is a real as-of projection through the ordinary renderer, not a scratch Python card builder. |
| Agent-facing tool surface | current program graph | `seon.analyzer-info`, boot indexing, eval tee, `seon.agent.ctx.namespaces`, menus, `my.ns/functions` | Colocated positive metadata is implemented and default/ACME proof exists. It replaces public-visibility inference without a second registry or benchmark blocklist. |
| Dataset and run evidence | `evals/datasets.lock` Git blob `2c5ca117…`; current offline suite | `seon_inspect.freeze`, scorecard, task/oracle modules | Deterministic task machinery exists, but the representative ordinary-work development/milestone/blind memberships and per-category floors requested by the refinement PRD are not frozen as one graduation battery. |
| Canonical autocomplete export | `src/seon/repl/autocomplete.cljs` Git blob `f962b8ae…` | `context`, `export!`, ordinary renderer, compact cards | Rows now carry a complete coordinate. They still lack artifact/schema version, source/config/profile identities, shared schema closure, stable row/split manifests, explicit target projection semantics, current-world verdicts, content-derived naming, and retained rejection rows. |

The exact source blobs observed for the Python declaration/lock, solver, BFCL
adapter, typeahead corpus, autocomplete exporter, and dataset lock were
`a97f0758…`, `bbd6875c…`, `cd5a61ec…`, `9f5f1a3d…`, `b351ccc8…`,
`f962b8ae…`, and `2c5ca117…`, respectively.

## What the ACME lane actually contributed

The old branch's commits have these current dispositions:

| Old branch change | Current disposition |
|---|---|
| establish the tool-refinement PRD | Reconciled and expanded in the current PRD. |
| bounded root telemetry | Reimplemented in current `de414b99`; later architecture work owns purity. |
| database-derived run/transcript policy | Reimplemented in current `5cfc0127` and corrected for warm schema in `131e438c`. |
| recovery load-order correction | Reimplemented in current `0ebe5f43`. |
| live config apply | Reimplemented in current `b1337b41` through the canonical pod/database boundary. |
| config proof and shared-checkout policy | Integrated in current documentation. |
| ten-sample Qwen 2B BFCL baseline | Patch-equivalent current evidence; later exact-turn retention explains why its aggregate zero is not a tool-quality verdict. |

Subsequent current-branch commits add the more valuable experimental lessons:

- **Evidence before diagnosis.** The original BFCL run had scores and aggregate
  counts but no reconstructable prompts/replies. `582d0c4d` now retains exact
  database and turn evidence in native Inspect metadata.
- **Respect the native agent protocol.** `2d68c96e` removed a benchmark prompt
  that contradicted Seon's executable-form contract. A small model succeeded
  immediately on the identical sample through an existing lifecycle function.
  That is a harness defect correction, not prompt coaching or JSON regex repair.
- **Public is not callable.** `bc2f587b` and `cd73b0f3` persist positive
  function eligibility through the one program graph and make downstream
  discovery consume it. The live eligible set is 114 of 1,034 indexed
  functions, and the measured namespace block fell from 22,106 to 20,406
  estimated tokens without hiding program source.
- **Context weight is now localized.** The remaining weight is mostly complete
  referenced schema definitions, especially filesystem, plan, shell, search,
  and web contracts. Exact duplicates explain only about 988 tokens. Any next
  compression must present a shared schema closure once; dropping contracts or
  more tools would destroy the experiment.
- **One successful BFCL sample is a protocol proof only.** It says neither that
  Qwen 2B meets ordinary-work goals nor that 24k-token prompts are acceptable
  for navigation/composition. Those claims require the frozen representative
  battery.

## Autocomplete findings that remain load-bearing

The old lanes still contribute requirements, not importable implementations:

- first-form stopping improved balanced single-form shape from `.19` to `.81`
  on the reported 16-row Qwen slice but head correctness remained near zero;
- 42 of 213 historical bundles begin with mechanical `in-ns`, so observed,
  counterfactual, and substantive-next-form targets must be named separately;
- the historical LoRA audit hard-failed 149 of 557 retained pairs, proving that
  successful historical eval rows and text cleanliness do not certify current
  database effects; and
- the fair scorer's `.264` to `.436` frontier movement is a useful calibration
  specification, but its pinned staged worlds and obsolete synthetic-card
  parser must be rebuilt as Inspect metrics over canonical rows.

The current exporter strengthened one important part of the contract after the
earlier audit: every accepted row carries the turn's complete rendered
coordinate and resolves an exact as-of database value. The open issue and old
roadmap wording that still describe a bare basis `t` are stale. All other
artifact/data-quality gaps remain.

## Branch and worktree retirement boundary

`/Users/sean/src/seon-acme-agentic-tool-refinement` is registered at clean tip
`ecd8d889`. No process command references that checkout. Its 737 tracked run
files are a strict subset of the current checkout's 740 tracked files. A
byte-for-byte comparison of every file under its `evals/runs/` found zero
missing or changed files in the current checkout, including ignored evidence.

The worktree is not yet safe to remove solely from that result. It still holds
about 238 MB under ignored `data/clusters/`, primarily a 226 MB
`acme-agentic-tool-refinement` database plus a 15 MB `acme` database. Those
database bytes need an explicit duplicate/reproducible-discard or archive and
read-back disposition in the preservation manifest. This is a much narrower
gate than the old active-lane exclusion: tracked source and run artifacts are
already reconciled; only ignored database evidence remains unclassified.

## Dependency order and parallel work

Three units can move independently now:

1. **Source/run admission.** Pin and verify Inspect, Inspect Evals, provider,
   task, scorer, Seon source, artifact, config, and dataset identities.
2. **Canonical export design and implementation.** Strengthen the existing
   ClojureScript export with one registered versioned manifest, shared schema
   closure, row/rejection identities, frozen splits, and content addressing.
3. **Measurement contract and evidence preservation.** Freeze ordinary-work
   development/milestone/blind memberships and classify the dedicated ACME
   databases without changing model/tool behavior.

The lifecycle PRD independently owns the token-fenced operator lease. Once it
lands, live caller migration can proceed in parallel with autocomplete replay
and scorer fixtures. The model ladder waits for source admission, the lease,
the canonical export, replay/scorer calibration, and the frozen battery.

## Smallest next implementation slice

Implement **Inspect source and run admission** before another scored model run:

1. Declare Inspect Evals as an explicit selected dependency beside Inspect and
   bound the Python provider version selected by the lock.
2. At task construction, compare the root-selected Gitlink revisions, relevant
   tracked-source cleanliness/content, installed distribution identities,
   Python lock digest, task/scorer source digest, Seon Git/tree identity,
   dataset lock, and selected model/provider configuration.
3. Reject a deliberate commit, dirty-source, installed-distribution, task, or
   lock mismatch before the task can run.
4. Make the admitted identity map required metadata for the native `.eval` and
   scorecard row; missing raw-log finalization rejects the run.
5. Recreate the environment from the declared sources and run the complete
   offline gate. The observed current checkpoint is **312 passed, eight
   skipped**, not the 311 or 314 counts preserved in older docs.

This slice is bounded to the Python harness/provenance owner, has deterministic
failure cases, requires no pod lifecycle or paid call, and makes every later
parallel result attributable.

## Success measures for the full lane

- A fresh environment and deliberate mismatch fixtures prove source admission.
- Concurrent live samples acquire disjoint token-fenced targets, resolve CLJ
  and CLJS dynamically, survive an identity-preserving restart, and release
  only owned resources.
- Repeated export at one complete coordinate is byte-identical; every source,
  branch, config, profile, schema, split, or target-mode change changes the
  manifest identity deliberately.
- Schema closure is complete and emitted once; every rejection is retained and
  addressable; current-world replay derives parse/schema/eval/database outcome.
- Historical LoRA, fair-scoring, and first-form acceptance cases reproduce
  through Inspect without an old worktree, scratch renderer, or private runner.
- The frozen large-plan/small-execute, small-alone, large-alone, and
  pretransacted-plan arms complete representative read/process/write/restart/
  report work with full evidence. Tool changes are retained only when the whole
  frozen battery improves without a category regression.

## Implementation checkpoint

The smallest source/run-admission slice is now implemented in
`seon_inspect.source_admission` and the existing catalog boundary:

- `evaluation-sources.lock.json` selects the two root Gitlink revisions, exact
  Python OpenAI version, admitted source paths, and lock artifacts;
- direct task loading and prebuilt catalog runs share one pre-task verification
  of Gitlink/checkout revisions, selected-source cleanliness, installed source
  and version, provider version, Python/dataset lock digests, and committed Seon
  harness source;
- the immutable identity map enters native Inspect eval metadata as
  `seon_source_admission`;
- an accepted run requires at least one readable native `.eval`; requested
  evidence copies are byte-digest verified, and absence/copy failure rejects
  finalization; and
- the synchronized environment passes 321 tests with eight expected skips.
  The focused admission/catalog/native-log gate passes 27 tests, including one
  real offline Inspect log read-back with the source identity intact.

This completes source admission. It does not invent the lifecycle lease or
claim that static URL mode is sample isolation. Stable scorecard-to-native-log
correlation remains part of the frozen measurement-contract work.

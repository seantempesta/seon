---
type: research
status: active
tags: [research, agent, database]
---

# Inspect and autocomplete lane integration audit — 2026-07-14

## Question and authority

This audit answers whether the old Inspect AI, repl-autosuggest, function-
surface, display-v3, planning, Needle, and retired-gym lanes contain work that
still belongs on the runtime-reliability branch. The active [[../roadmap.md]]
is the current-state and sequencing authority. Architecture and the old
repl-autosuggest documents describe targets and experiment history; they do
not prove that current source implements a claim.

The audit compared branch patches with the current lineage, inspected every
registered related worktree including dirty and ignored state, read current
source and tests, and ran the complete offline `src-inspect-ai` suite. It made
no paid/model call, did not modify production code or an old worktree, and did
not start, stop, or reset ACME.

## Result

The stable lane's five reviewed behavioral changes are integrated. No old
Inspect, plan, toolkit, function-surface, gym, or display-v3 commit is a safe
new cherry-pick. The current branch already carries the intended behavior or a
stronger implementation adapted to the refactored runtime.

That does not mean the lanes are finished. Four current-mechanism units remain:

- migrate live Inspect callers from retired cluster commands and hard-coded
  endpoints to the one operator lease/artifact/endpoint contract;
- replace text-parsed autocomplete datasets with one versioned, database-
  derived export containing referenced-schema closure and frozen membership;
- preserve unique ignored databases, fair-scoring results, and continuation-
  drive evidence before removing worktrees; and
- rebuild data-quality and scoring through Inspect before resuming training.

## Commit disposition

### Stable Inspect and planning behavior

`git cherry` reports patch equivalents for the five reviewed stable-lane
changes:

| Stable patch | Current disposition |
|---|---|
| `c8b907ea` provider-derived SWE-bench egress | Current `6ca0aec4`; keep. |
| `91dee957` standard OpenAI dependency | Current `71527299`; keep. |
| `61813a97` first-class `long_term_planning` task | Current `1946850e`; keep. |
| `2bf4eb14` same-title open-plan guard | Patch-equivalent current implementation; do not cherry-pick. |
| `39fd81ec` EDN-only reconcile path | Patch-equivalent current implementation; do not cherry-pick. |

Current `8df08bd0` additionally integrates the three-arm planning evidence and
pure scorer. The full offline suite passed **314 tests with eight expected
environment-gated skips** in 7.86 seconds. The active roadmap and localized
PRD instructions still report 293/eight and must be corrected; this is a
documentation-count mismatch, not a test regression.

Stable documentation-only commit `d5a456ed` is not patch-identical, but its
local 35B HumanEval runbook and three gotchas are retained in
[[../../repl-autosuggest/research/inspect-harness-integration-2026-07-14]]. The
stable lane's `dd8bc8a7` and `8d1631cf` compact-card patches are superseded by
the current database-derived inert callable records and removal of obsolete
namespace plumbing. Importing them would restore an older renderer ancestry.

### Toolkit and function surface

The toolkit-gap and plan-fix commits are patch-equivalent to current lineage.
The function-surface lane's broad docstring and request-schema commits are not
patch-identical because the surrounding runtime changed, but current
`608b2331`, `6b75705c`, and `f6cd9761` carry their measured intent. Current
source has the qualified query/entity/pull/thunk/time-point shapes and the
current callers/tests. There is no missing old toolkit implementation to
import.

### Display-v3 and Needle

All four display-v3 patches (`1fe6866d`, `b7dfd32d`, `5da97025`, and
`b7be18be`) are unique and all four are unsafe cherry-picks:

- mutable Malli-registry expansion conflicts with the database program graph;
- ASCII/card rewriting would add a second presentation mechanism;
- the Python v3 builder enriches and parses an obsolete synthetic `defn` card
  grammar; and
- historical rows are silently reprojected rather than naming observed versus
  counterfactual projection modes.

The valid findings survive as requirements: reject stale/deprecated callable
targets through the program graph, close referenced schemas, freeze held-out
membership, and version the structured export. Current
`seon.repl.autocomplete/export!` still writes presentation strings plus a Git
SHA; `src-needle` still strips or constructs cards independently. Model work
must remain paused.

### Retired gym

The gym worktree contains a content-preserving paid-test rename and scenario
translations to newer lifecycle facts. They are migration history for a
deleted evaluator, not source for Inspect. Importing them would restore the
second harness explicitly removed by the runtime refactor.

## Subtle agent-testing findings to retain

### Planning evidence is stricter than a final answer

The integrated planning scorer correctly requires database-derived outcome,
plan-root history and provenance, verified close-basis expectations, a report
followed by an observed `run_closed`, and complete address-step observations.
A plausible narrative, caller-supplied arm label, final-state-only root read,
or empty observation set cannot pass. The pure scorer and offline fixtures are
ready; the live adapter that derives those records from current run/turn/
transaction facts is not.

Standard Inspect mode, pod-backed text-in/text-out, and pod-in-sandbox answer
different questions. They must not be presented as interchangeable evidence.
Standard HumanEval measures the model, the planning task measures Seon's
database-backed loop, and the SWE-bench arm is the bench-specific system path.

### Fair scoring is useful evidence, not importable code

The dirty stable fair scorer mechanically lifted the audited DeepSeek frontier
from `.264` to `.436`, while the two small-model arms moved down, and passed
the predeclared reasonable-alternative versus real-error cases. This is a
valuable scorer specification. Its implementation is not importable: it
depends on the retired pin, scratch staged worlds, and the obsolete `defn` card
grammar. Inspect must own its rebuilt layered parse/schema/eval/productivity/
history result over the canonical export.

### First-form stopping improves shape, not correctness

The stable worktree also contains an untracked continuation-drive design and
probe omitted from the earlier commit-only map. On Qwen3.5-2B-Base, 16 held-
out turns, a string/comment-aware balanced first-form stop improved balanced
single-form yield from `.19` to `.81`, reduced cap hits from 13/16 to 2/16,
and reduced median generation from 1,024 to 41 tokens. A repetition penalty of
1.15 removed cap hits but reduced balanced-form yield from `.81` to `.56`, so
a bounded first-form budget is the better guard.

The probe's disposable self-test was re-run during this audit: all 12 crafted
reader cases passed and first-form extraction was clean for all 213 historical
bundles. This verifies the scanner fixture, not the old model measurement or
serving accuracy.

This does **not** establish autocomplete accuracy: the small slice's head match
was approximately zero. It establishes a future raw-completion serving
primitive only. Its other two findings belong in the canonical data contract:

- 42 of 213 old bundles begin with mechanical `in-ns`; a continuation arm
  should score an explicitly named substantive-next-form projection rather
  than silently count namespace bookkeeping as model work; and
- train/serve rows should be decomposed from database-proven trajectories at
  the as-of basis before each form, not reconstructed from rendered prose.

The old ellipsis-contamination finding is already resolved in current runtime
cards: `compact-fn-head` emits an inert record and no fake `defn` body. Needle's
old parsers still assume the contaminated grammar, which is another reason not
to import the probe directly.

### Data-cleanliness remains the hard gate

The LoRA audit's 149 of 557 hard REPL failures remains the decisive result.
`ok?`-only mining, fabricated context text, and function-name existence cannot
certify a trajectory. The replacement order is stage database facts, render
through the serving profile, execute through current eval, derive the outcome,
then retain or reject with an explicit reason.

## Worktree and evidence disposition

No related worktree showed a running process by checkout path during this
audit. That is not sufficient authorization to delete it; ignored database and
artifact state remains:

| Worktree | Unique or risky state | Disposition before removal |
|---|---|---|
| `seon-display-v3` | 4.2 GB ACME database; 5.8 MB tune artifacts; four unique but rejected commits | Archive database and v3/raw artifacts with hashes and a read-back check. |
| `seon-plan-pilot` | 373 MB ACME database named as a real-world training seed | Preserve identity/basis and prove a current read-only staging recipe. |
| `seon-stable` | 44 MB cluster; dirty fair scorer; untracked continuation report/probe; 404 KB raw continuation outputs | Preserve scorer/probe/report/raw outputs content-addressably; do not import ACME config edits. |
| `seon-pin` | 90 MB default/audit cluster and stale `:lora-audit` wiring | Preserve historical audit evidence, then migrate/remove the runner. |
| `seon-fn-surface` | 17 MB cluster; generated bundle/modules | Confirm committed re-lint evidence is sufficient, then discard generated state. |
| `seon-toolkit-gaps` | 45 MB clusters; generated bundle/modules | Confirm no unique database evidence, then discard. |
| `seon-plan-fix` | integrated commits plus generated modules | Discard after ordinary ignored-file inventory. |
| gym validation | dirty deleted-harness scenarios | Retire as historical migration evidence; do not restore gym. |

Unique stable evidence hashes:

- continuation design: `9af4bb2809be1041dfeb17f24668a45dfc3fac07ffe5ddd826f9b70c6dc06151`;
- continuation probe: `9df829f34331f066cec8cb3a35d3981c4235043cab4f248240cf1240a5eb9231`;
- fair scorer: `8eba9f6c505f4c06bd3c640bed5b05d2ab2bb1a6be70239426a40f59c484c45d`;
- completed fair-scoring report:
  `568d7c7f19c8629515172bcea2ce36557bc073a0636e1d0b7c09953568e7befb`.

The 404 KB continuation output directory contains seven prediction JSONL files
and seven example reports; its per-file hashes were observed during the audit
and should enter the final cleanup manifest rather than this narrative report.

## Safe implementation and retirement order

1. Finish and live-prove the default runtime; do not touch ACME first.
2. Define the operator lease/artifact/dynamic-endpoint contract, then migrate
   Inspect live callers and ACME to consume it.
3. Content-address the worktree evidence and verify database/artifact restore
   or read-back before removing any checkout.
4. Define one schema-registered autocomplete artifact with explicit observed/
   counterfactual/substantive projection mode, schema closure, immutable split
   membership, as-of provenance, and rejection data.
5. Migrate runtime export, Needle consumers, and Inspect scoring together;
   delete fake-card and brace-stripping parsers in the same unit.
6. Reproduce the historical fair-scoring acceptance cases and data-failure
   classes through Inspect, then create a clean held-out baseline.
7. Only after those gates, decide whether a bounded continuation worker earns
   implementation and training spend.
8. Remove worktrees before deleting branches. Delete only branches whose
   unique evidence is preserved and whose patches are present, superseded, or
   explicitly rejected by this audit.

## Open issue ownership

- [[docs/seon/issues/inspect-live-cluster-caller-drift]] owns live Inspect
  operator migration.
- [[docs/seon/issues/autocomplete-data-quality-pipeline-drift]] owns the one
  structured export, schema closure, split, staging, and scorer boundary.
- [[docs/seon/issues/autocomplete-worktree-evidence-preservation]] owns the
  preservation manifest and safe cleanup gate.
- [[docs/seon/issues/lora-audit-runner-drift]] owns removal of the stale
  pin-only audit target after equivalent evidence exists.
- [[docs/seon/issues/deprecated-skill-render-functions-indexed]] owns stale
  callable eligibility at the program-graph source rather than an exporter
  blacklist.

---
type: research
status: active
tags: [research, agent]
---

# Autocomplete worktree integration audit — 2026-07-14

## Question and method

This is the retirement audit for the autocomplete, function-surface, pin,
planning-pilot, toolkit-gap, and retired-gym lanes. It compares their commits,
dirty files, ignored data, and research with the current runtime-reliability
branch. It does not treat an old worktree's generated output as current runtime
truth. The active [[../roadmap.md]] remains the current-state and sequencing
authority.

The audit was read-only. It inspected Git history and patch identity, exact
file contents, current source and tests, the relevant research, and the
Shadow/ClojureScript behavior in `reference-code/`. It did not run paid or live
agents, mutate a database or process, edit an old worktree, or import code.

## Executive result

Most implementation commits in the function-surface, plan-fix, toolkit-gap,
pin, and stable lanes are already present or have been superseded by a stronger
current implementation. They must not be cherry-picked again. The retired gym
contains historical migration evidence, not a harness to restore.

The four display-v3 commits are unique patches but are not coherent
cherry-pick candidates. Several mechanisms conflict with settled current
contracts. They nevertheless expose three unresolved needs:

- a structured, versioned autocomplete-card export contract;
- database-derived referenced-schema closure for exported function cards;
- a canonical stale/deprecated-card and held-out-membership data-quality gate.

The current runtime card representation and the paused Needle consumers have
drifted apart. Model work therefore remains paused. Before deleting old
worktrees, preserve the unique ignored databases and display-v3 artifacts that
Git status does not reveal.

## Per-worktree integration map

### `seon-fn-surface`

| Commit or file | Classification | Current disposition |
|---|---|---|
| `5258e166` — toolkit recall/functions | Equivalent | Exact patch duplicate of current-lineage `d8078c91`; do not cherry-pick. |
| `299b37f7` — `my.ns` lookup fix | Equivalent | Exact patch duplicate of current-lineage `7c08240e`; do not cherry-pick. |
| `fa6a1fba` — FIX8 docstrings | Equivalent | `git cherry` reports an equivalent patch on the current lineage. |
| `65a662ac` — capability line-1s | Equivalent intent, adapted current implementation | Current `608b2331` carries the measured capability rewrite through the newer runtime. Patch identity differs because the parents and surrounding runtime differ. |
| `cc22084f` — request shapes | Equivalent intent, adapted current implementation | Current `6b75705c` carries the request-schema sharpening through the newer runtime. |
| `17a82314` — message/user wording | Equivalent | Exact patch duplicate of current `f6cd9761`. |
| `out-acme/client/main.js` | Generated-only | Discard with the worktree. |
| `node_modules/` | Generated-only | Discard with the worktree. |
| ignored cluster data, about 17 MB | Evidence-only or ephemeral | The durable re-lint result is already in [[tool-surface-overhaul-2026-07-12]]. Confirm no unique raw evidence is wanted, then discard. |

The current source contains the intended qualified schemas for query forms,
entity references, pull patterns, thunks, time points, and plan roots, with
current callers and tests migrated. Importing the old commits would regress
through obsolete surrounding code rather than add a missing feature.

### `seon-plan-fix`

| Commit or file | Classification | Current disposition |
|---|---|---|
| `d8078c91` | Equivalent/current lineage | Already present. |
| `7c08240e` | Equivalent/current lineage | Already present. |
| untracked `node_modules/` | Generated-only | Discard. |

This worktree has no identified unique implementation or durable database.

### `seon-toolkit-gaps`

| Commit or file | Classification | Current disposition |
|---|---|---|
| `5258e166` | Equivalent | Same toolkit gain already represented by `d8078c91`. |
| `299b37f7` | Equivalent | Same namespace lookup fix already represented by `7c08240e`. |
| `out-acme/client/main.js` | Generated-only | Discard. |
| `node_modules/` | Generated-only | Discard. |
| ignored clusters, about 45 MB | Likely ephemeral | Confirm no unique raw evidence is needed, then discard. |

### `seon-plan-pilot`

The worktree head is the already-integrated `299b37f7`. Its dirty tracked state
is generated `out-acme/client/main.js`, and `node_modules/` is generated. The
worktree is nevertheless **not retirement-ready**: ignored
`data/clusters/acme` is about 373 MB and is named by
[[root-cause-fixes-2026-07-13]] as a clean real-world/training seed. Export or
archive that database, record its provenance, and verify the copy before
removing the worktree.

### `seon-pin`

| Commit or file | Classification | Current disposition |
|---|---|---|
| modified `shadow-cljs.edn` `:lora-audit` target | Equivalent but stale | Main already received the target in `3e0e0bff` and later preload work. Do not import the pin edit. Audit whether the main target should now be repaired or removed. |
| untracked `test/seon/needle_lora_audit_test.cljs` | Exact duplicate | Byte-identical to tracked `src-needle/audit/seon/needle_lora_audit_test.cljs`; discard. |
| ignored default cluster, about 90 MB | Evidence-only or ephemeral | Durable results live under `src-needle/data/lora/` and in research. Confirm the raw cluster is unnecessary before deletion. |

The surviving main `:lora-audit` target is questionable. Its comment names
`src-needle/audit`, while deps-mode Shadow receives ClojureScript extra paths
from the `:cljs` alias in `deps.edn`, currently `test` and `script`. The old
runbook copied the audit test into the pin worktree's `test/seon` directory to
make it visible. Shadow's source confirms that `node-test` selects namespaces
from namespaces already visible on the build classpath and synthesizes a
runner requiring those selected namespaces. The isolated runner was a sound
experiment; the retained classpath wiring is now stale operational residue.

### `.claude/worktrees/gym-metric-validation`

The staged `paid_test.cljs.disabled` to `paid_test.cljs` change is a 100%
content-preserving rename. Eight scenario edits translate deleted stored
agent-state/session/wake assumptions to the current run/cause/status/turn
model:

- `consults-findings-run8`;
- `err-recovery-unregistered-attr`;
- `s21-log-workout-existing-schema`;
- `s32-consult-before-research`;
- `todo-multistep-tracking`;
- `x1` subscriptions;
- `x12` narrow question;
- `x3` expense.

These edits are migration evidence only. The gym and paid runner are deleted,
and [[inspect-harness-integration-2026-07-14]] explicitly rejects importing
their implementation. Inspect AI is the one agent/model harness. Retire this
worktree without restoring either the test or the scenarios.

### Generated-only branches

`seon-plan-fix` and `seon-toolkit-gaps` contain no unique commits after patch
comparison. Their remaining tracked changes are generated bundles. They may be
retired after their small ignored directories are explicitly inventoried.
`seon-plan-pilot` is not in this category despite similar Git status because
its large ignored database is unique evidence.

## Display-v3 commit audit

All four commits on `repl-autosuggest/display-v3` are unique according to
`git cherry`. Unique does not mean suitable for integration.

### `1fe6866d` — display v3

This commit adds per-block ASCII rewriting, mutable-registry schema expansion,
stale/deprecated filtering, exporter accounting, config flags, and tests.

- The ASCII transform is **superseded/rejected**. Current profiles select and
  cap canonical blocks; they do not rewrite block contents. Current cards have
  already removed the imitation-prone ellipsis body without adding a second
  display mechanism.
- The three-arity mutable-registry "spec face" is **superseded technically**.
  Current `compact-fn-head` emits one inert, fully qualified, DB-derived
  callable-contract record. Current tests cover reader safety, map-input
  visibility, and runtime-object omission. The old expansion used the mutable
  Malli registry, obsolete arities, and ancestry from synthetic `defn` cards.
- Stale/deprecated filtering is an **unresolved valid finding**. Filtering a
  rendered card alone is insufficient: the canonical
  stage → render → eval → keep-clean boundary must reject a stale target and
  report the cause. Deprecated `my.skills` catalog/block functions remain a
  concrete cleanup candidate.
- Referenced-schema visibility is an **unresolved valid finding**. The current
  single-function export calls the canonical card renderer on one function
  row, so a request-schema reference can remain opaque. Namespace context has
  an existing DB-derived referenced-schema block. The export path should use
  that schema graph or a shared structured projection, never restore registry
  expansion.

### `b7dfd32d` — enriched JSON parameters and v3 builder

The new Python builder and description translation are **evidence-only**.
Current runtime cards no longer use the synthetic `(defn name ...)` grammar,
but `src-needle/scripts/split_forms.clj`, `build_v2`, and the fair scorer still
parse that grammar. Parameter-description enrichment also lacks a new measured
result and conflicts with the earlier compact type-only recommendation.

The optional-column handling in `extended_fit` is a potentially reusable
robustness improvement, but only when the canonical dataset pipeline is
rebuilt with tests. It should not be imported independently into the paused
pipeline.

### `5da97025` — one display per profile and held-out lock

The frozen v1 held-out turn-ID membership is a **concept to integrate** into a
canonical dataset manifest. Newer turns should be reported separately instead
of silently changing the evaluation population.

Forcing every historical row through the export-head profile is an
**unresolved design choice**, not a patch to import. The A1 contract records
the actual pre-turn, as-of database context. Reprojecting history through the
current profile creates a counterfactual training view. If both are useful,
they need explicit named projection modes and metadata; the counterfactual
mode must not masquerade as what the model originally saw.

### `b7be18be` — printable live schema values

The fallback from expanded runtime values to schema references is
**superseded**. The current DB-derived renderer uses readable serialization
and omits non-readable runtime objects, with tests that reject `#object`
output. Do not port this registry-specific fallback.

## Current card-versus-Needle contract drift

Current runtime code emits inert human-readable records such as
`fn qualified/name — ...`. It deliberately does not emit fake executable
`defn` forms, namespace-local `::` keywords, Malli callable grammar, or an
ellipsis body a model might imitate.

Paused consumers still assume the removed display contract:

- `src-needle/scripts/split_forms.clj` recognizes `(defn ...)` cards;
- `src-needle/scripts/fair_score.py` matches the same old card prefix;
- `build_v2`, KT1, and related scripts consume the old synthetic-code shape;
- the fair scorer also hardcodes `/Users/sean/src/seon-pin`.

Consequently the runtime card overhaul is integrated, but the Needle dataset,
fit, and fair-scoring consumers are not compatible with it. Presentation text
must no longer be the machine interchange format.

The bounded replacement is one versioned structured record derived from the
database program graph. It should carry at least the qualified function
identity, stable callable input/output data, referenced-schema closure,
source/as-of provenance, profile/projection mode, and deprecation/liveness
status. Human card text may be a projection of that record. Exporters,
builders, fit reports, and scorers should consume the record rather than parse
the projection.

## Schema closure and export contract

The existing namespace renderer can follow DB-derived schema references, but
the autocomplete export's single-function card path does not currently carry
that closure. This is most damaging for opaque request-map schemas: the card
names a schema while omitting the keys and types the model must copy.

One implementation unit should strengthen the existing database graph:

1. Derive the transitive, bounded referenced-schema closure for a selected
   function from the same DB facts used by context rendering.
2. Serialize only readable schema data; preserve symbolic references where a
   runtime predicate or regular expression cannot be represented as data.
3. Emit one versioned structured export record and derive the human card from
   it.
4. Reject stale targets and record stale/deprecated ingredients as explicit
   data-quality failures.
5. Test qualified symbols, map inputs, nested references, runtime predicates,
   stale functions, deterministic ordering, and reader-safe output.

This is not authorization to add a second renderer, registry, or graph.

## Fair scoring and Inspect ownership

Inspect AI owns agent/model execution and scoring. The current branch has
integrated provider-aware egress, OpenAI support, a first-class
`long_term_planning` task, and the pure three-arm planning scorer. Live
provider adapters and equal-budget live execution remain gates recorded in
[[inspect-harness-integration-2026-07-14]].

The dirty stable fair-scoring implementation is **evidence, not importable
code**. It depends on the paused scratch dataset, hardcoded pin paths, and the
obsolete `defn` card grammar. Its useful staged-world result and scorer
false-negative findings are retained in research. Fair autocomplete scoring
must be rebuilt as a canonical data-quality/Inspect component over the
versioned structured export, not as another harness or parallel scorer stack.

Current ignored `src-needle/data/fair` summaries are not graduation evidence:
one reported DeepSeek result has zero level-2 gated rows and missing evaluation
evidence. No model, scorer, or display should graduate from those files.

## Ignored evidence and retirement gates

Git status does not show ignored databases. A forced worktree removal can
therefore destroy the most valuable artifact while appearing clean.

| Worktree | Ignored data observed | Retirement gate |
|---|---:|---|
| `seon-display-v3` | about 4.2 GB, 214,057 ACME store files; about 5.8 MB tune artifacts | Preserve or export the database and copy the raw/v3/meta artifacts with hashes and provenance. Keep the old store until a canonical re-export passes acceptance. |
| `seon-plan-pilot` | about 373 MB, 26,493 ACME store files | Preserve/export the roadmap-named clean seed and verify the copy. |
| `seon-pin` | about 90 MB default/audit cluster | Confirm durable audit results make the raw cluster unnecessary. |
| `seon-fn-surface` | about 17 MB re-lint cluster | Confirm the committed research is sufficient. |
| `seon-toolkit-gaps` | about 45 MB clusters | Confirm no unique evidence, then discard. |

The display-v3 artifacts observed during the audit were:

- raw export: 218 rows, SHA-256
  `a4b0351769df61fb3bb5b5fc3f2021403206bbdee23bfc2881d605a1a9239cd1`;
- v3 export: 211 rows, SHA-256
  `41595a0e688abe1f01d5b4c6d9278a5f3aa3d832d0744a63e0728c91b1bdef55`;
- metadata SHA-256 prefix `7c76a9c`;
- sidecar counts: 3 excluded turns, 7 newer locked-out turns, 480 cards,
  107 index matches, 373 local cards, 0 unparseable cards, 14 alias hits, and
  287 rows with notes.

These are evidence from an obsolete renderer, not a gold dataset. Preserve
them so the later canonical exporter can explain differences; do not train on
them as-is.

## Recommended bounded order

1. **Preserve evidence before cleanup.** Archive the display-v3 artifacts and
   database, then the plan-pilot seed. Record paths, hashes, provenance, and a
   successful read/check before worktree deletion.
2. **Retire equivalent lanes.** After explicit ignored-data checks, remove
   fn-surface, plan-fix, toolkit-gaps, pin, and gym worktrees. Delete branches
   only after the worktree removal and one final containment check.
3. **Resolve stale test tooling.** Remove or repair the retained
   `:lora-audit` target and eliminate pin-hardcoded paths. Do not introduce a
   second test runner.
4. **Define the structured export contract.** Version the DB-derived function
   and schema-closure record, including projection mode and held-out manifest
   membership.
5. **Migrate all consumers together.** Update autocomplete export, Needle
   builders/splitters/fit tools, and fair scoring with fixtures. Delete the old
   text parsers in the same unit.
6. **Build the real clean-data gate.** Stage a real database, render actual
   context, evaluate the target, and keep only clean rows. Stale targets and
   unresolved cards are failures with recorded causes.
7. **Resume Inspect evaluation before training.** Run canonical, equal-budget
   evaluations over frozen manifests only after the adapters and scorer inputs
   are real. Training remains downstream of that evidence.

## Current conclusion

No unique old implementation should be bulk-merged. The integration work is a
small sequence of current-mechanism repairs: preserve evidence, retire
equivalent lanes, remove stale audit wiring, define one structured DB-derived
card contract with schema closure, migrate every consumer, and prove the clean
data boundary through Inspect. Until those gates pass, the old display-v3 and
fair-scoring outputs remain research evidence rather than current-state proof.

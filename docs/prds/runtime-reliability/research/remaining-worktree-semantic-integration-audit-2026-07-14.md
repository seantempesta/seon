---
type: research
status: completed
tags: [research, archive, agent, capability]
---

# Remaining worktree semantic integration audit — 2026-07-14

## Question and verdict

This read-only audit asks whether any committed source or documentation still
needs to move from the retained ACME refinement, autocomplete, function-
surface, planning, or toolkit worktrees into the current branch before those
Git histories can eventually be retired.

The answer is **no direct commit remains to import**. Every retained commit is
one of:

- patch-equivalent to the current lineage;
- integrated semantically by a reviewed current-lineage implementation;
- obsolete or contradictory to the database-derived program/export owners;
- documentation-only integration history whose useful evidence is already
  retained; or
- a useful requirement that remains to be implemented through the current
  canonical mechanism, not by cherry-picking the old patch.

This settles source integration, not filesystem cleanup. The old worktrees
still contain ignored databases, blobs, generated artifacts, or experiment
evidence covered by
[[worktree-evidence-preservation-manifest-2026-07-14]] and
[[legacy-lane-retirement-audit-2026-07-14]]. No worktree, branch, process, or
database was changed by this audit, and no cleanup is authorized here.

The protected current-checkout shared-schema report and
`e1_inspect_samples.jsonl` were not read, hashed, staged, or changed. Active
uncommitted autocomplete/Inspect work in the shared checkout was also not
credited as committed `HEAD` behavior.

## Dependency and evidence ledger

| Authority or mechanism | Snapshot identity | Use |
|---|---|---|
| Current integration branch | `codex/runtime-reliability-refactor` at `41f78b98` | Semantic comparison target |
| Git patch equivalence | `git cherry`, merge bases, commit diffs, file histories, and current `HEAD` blobs | Separate exact patch equivalence from semantic integration |
| Current program ledger | [[../roadmap.md]] | Current state, dependency order, and success authority |
| Inspect/autocomplete reconciliation | [[../../inspect-autocomplete-evidence/research/agentic-inspect-autocomplete-reconciliation-2026-07-14]] | Reviewed ACME handback and current canonical gaps |
| Earlier lane audit | [[../../inspect-autocomplete-evidence/research/inspect-autocomplete-lane-integration-audit-2026-07-14]] | Stable/display/function-surface behavioral disposition |
| Preservation manifest | [[worktree-evidence-preservation-manifest-2026-07-14]] | Dirty, ignored, database, blob, and experiment-evidence gates |
| Data-oriented Clojure guidance | `.agents/skills/data-oriented-clojure/SKILL.md` plus current program-graph source | Reject old parallel presentation/schema mechanisms rather than judging patches by names alone |

This unit assumes no unverified dependency API behavior. The semantic checks
compare first-party implementations and tests; no library behavior was
reconstructed from memory.

## Current worktree graph

The relevant registered tips are unchanged:

| Worktree | Tip | Tracked status | Source-integration result |
|---|---|---|---|
| `seon-acme-agentic-tool-refinement` | `codex/acme-agentic-tool-refinement` / `ecd8d889` | Clean | All eight commits integrated or patch-equivalent |
| `seon-display-v3` | `repl-autosuggest/display-v3` / `b7be18be` | Dirty generated/reference-link state | No safe source import; two surviving export requirements remain current work |
| `seon-fn-surface` | `repl-autosuggest/fn-surface-pin` / `17a82314` | Generated bundle only | All six commits integrated or patch-equivalent |
| `seon-pin` | detached `93c8d8ad` | Dirty config plus audit fixture | No commit beyond the current merge base |
| `seon-plan-fix` | detached `7c08240e` | Only untracked modules link | Both commits patch-equivalent; still the sole cleanup-eligible checkout after authorization |
| `seon-plan-pilot` | detached `299b37f7` | Generated bundle/modules | Both commits patch-equivalent; ignored database evidence still blocks removal |
| `seon-stable` | `repl-autosuggest/stable` / `609c4006` | Dirty scorer/config/evidence state | Five behavioral commits patch-equivalent; all other commits classified below |
| `seon-toolkit-gaps` | `repl-autosuggest/toolkit-gaps-pin` / `299b37f7` | Generated bundle/modules | Both commits patch-equivalent; ignored database/blob evidence still blocks removal |

The ACME refinement checkout currently allocates about 244,104 KiB below its
ignored `data/` directory. Its clean Git state therefore does not make the
worktree removable: the database evidence still needs an explicit reproducible
discard or archive/read-back disposition.

## Non-patch-equivalent commit disposition

The tables below classify every `+` result from `git cherry HEAD <tip>`.

### ACME agentic tool refinement

| Commit | Classification | Current disposition |
|---|---|---|
| `c0d0eecf` establish the refinement lane | **Integrated semantically** | Current `595c8e5b` reconciles the PRD/operator change into the shared branch. |
| `3bf5b953` bounded root host telemetry | **Integrated semantically** | Current `de414b99` owns the implementation and current architecture wording. |
| `b7ccbe0e` derived run/transcript policy | **Integrated semantically** | Current `5cfc0127` carries the policy; `131e438c` additionally repairs warm-schema defaults. |
| `aa6737cb` recovery load-order correction | **Integrated semantically** | Current `0ebe5f43` owns the correction. |
| `2f348806` live database config apply | **Integrated semantically** | Current `b1337b41` applies config through the canonical pod/database boundary. |
| `d84527d1` live config issue proof | **Integrated semantically** | Current `7b1d7cde` retains the evidence in the issue authority. |

The remaining branch commits, `91d14430` (shared-checkout policy) and
`ecd8d889` (Qwen 2B BFCL baseline), are patch-equivalent. Current commits after
the handback are stronger: source/run admission is content-pinned, native
Inspect logs retain exact turn/database evidence, BFCL uses Seon's executable-
form protocol, and positive program facts own agent-facing function discovery.
There is no missing ACME branch source range.

### Display-v3 autocomplete and Needle

| Commit | Classification | Current disposition |
|---|---|---|
| `1fe6866d` spec-face/ASCII/stale-card display | **Obsolete or contradictory** | Mutable Malli expansion and a second ASCII/card presentation path conflict with the database program graph and one renderer. Stale callable discovery is now owned by positive `:seon.fn/agent-facing?` facts. |
| `b7dfd32d` enriched JSON descriptions and Python v3 builder | **Obsolete or contradictory** | The builder parses the retired synthetic card grammar and creates another export owner. Its valid schema-description requirement survives as the canonical shared schema-closure requirement. |
| `5da97025` one resolved profile and frozen membership | **Still valuable missing work** | The requirement is correct: one artifact must bind one profile/config identity and one frozen row/split membership. Committed `HEAD` does not yet provide the complete content-addressed manifest; reimplement through `seon.repl.autocomplete`, never cherry-pick the old mixed ClojureScript/Python patch. |
| `b7be18be` unprintable live-value expansion guard | **Obsolete or contradictory** | The current owner persists reader-safe schema source/program facts and must not expand the mutable live Malli registry for display. The old guard fixes a mechanism that should not return. |

One additional valuable requirement extracted from `b7dfd32d` remains coupled
to `5da97025`: emit the transitive referenced-schema closure once per artifact,
then let Inspect consume that structured manifest without reparsing rendered
cards. The active unit-7 roadmap already owns this work. The currently modified
autocomplete/Inspect files in the shared checkout appear to be advancing that
slice, but this audit deliberately does not treat uncommitted work as `HEAD`.

### Function-surface pin

| Commit | Classification | Current disposition |
|---|---|---|
| `65a662ac` capability-oriented line-one docstrings | **Integrated semantically** | Current `608b2331` carries the measured wording intent; later `bc2f587b` further curates the callable surface from program facts. |
| `cc22084f` self-describing request shapes | **Integrated semantically** | Current `6b75705c` carries qualified query/entity/pull/thunk/time-point shapes and the plan request correction; current callers/tests use them. |

The other four function-surface commits (`5258e166`, `299b37f7`, `fa6a1fba`,
and `17a82314`) are patch-equivalent. No old function implementation or schema
needs import.

### Stable Inspect/autocomplete lane

| Commit | Classification | Current disposition |
|---|---|---|
| `dd8bc8a7` fully qualified compact-card keywords | **Obsolete or contradictory** | Its bug is resolved by the current inert database-derived renderer; importing the patch restores removed namespace-abbreviation ancestry. |
| `00dd53f6` integration note for `dd8bc8a7` | **Aspirational/docs-only** | Superseded integration bookkeeping; no current authority should import it. |
| `8d1631cf` delete abbreviation plumbing | **Obsolete or contradictory** | The current renderer already removed the obsolete plumbing through its own refactor. |
| `5dbcc951` integration note for `8d1631cf` | **Aspirational/docs-only** | Superseded integration bookkeeping. |
| `d5a456ed` local 35B HumanEval runbook | **Aspirational/docs-only** | Useful evidence is retained in the current Inspect harness audit linked by the earlier lane audit; it is not a runtime implementation or a current graduation result. |
| `27738fc8` plan/Inspect integration list | **Aspirational/docs-only** | Its behavioral items are integrated; the list is historical sequencing only. |
| `e03f37ce` integration-plan recheck | **Aspirational/docs-only** | Its central conclusion—drop redundant compact-renderer patches—is reflected in current source and audits. |
| `c7f1b178` risk/incomplete integration audit | **Aspirational/docs-only** | Useful operator/export risks were localized into current issues and PRDs; the old integration plan is not an authority. |
| `609c4006` final integration recheck | **Aspirational/docs-only** | Historical branch comparison only. |

Stable's five behavioral commits—provider-derived SWE-bench egress, declared
OpenAI dependency, first-class long-term planning, same-title plan guard, and
EDN-only reconciliation—are patch-equivalent. The stable dirty scorer,
continuation probe/report/raw outputs, config, databases, and blobs remain
preservation evidence, not source to merge.

## Detached and patch-equivalent lanes

- Detached `seon-pin` has no commit beyond its merge base. Its untracked LoRA
  audit fixture and ignored blob/database evidence remain a preservation gate.
- Detached `seon-plan-fix` has two patch-equivalent commits and no unique
  database. After a fresh process/status check and explicit authorization, it
  remains the only checkout presently eligible for removal.
- Detached `seon-plan-pilot` and branch-backed `seon-toolkit-gaps` both end at
  the same two patch-equivalent toolkit commits. Their separate ignored
  databases are not duplicates merely because the Git tip is shared.

## What is integrated versus what remains

The retained histories contain no unmerged Inspect task, ACME operator,
function-surface, toolkit, plan, or autocomplete implementation that should be
cherry-picked. The current program still has real work, but its owners are now
unambiguous:

1. `inspect-autocomplete-evidence` owns the content-addressed autocomplete
   manifest, schema closure, frozen row/split membership, rejection/current-
   world evidence, canonical Inspect ingestion, and calibrated model trials.
2. `database-lifecycle-recovery` owns token-fenced lifecycle admission and the
   eventual live sample lease; static cluster URLs are not sample isolation.
3. `independent-downstream-distribution` owns no-source ACME packaging. A clean
   old ACME branch is not proof that a third party can consume released Seon
   artifacts independently.
4. The preservation/retirement runbook owns ignored databases, blobs, dirty
   scorers, generated outputs, process quiescence, archive/read-back, and the
   final authorized worktree/branch removal.

This distinction matters: remaining product work is not evidence that old
branch commits were missed.

## Retirement boundary

Source integration is complete for every branch named in this audit. Cleanup
must still proceed through the existing evidence gates:

- do not remove ACME refinement until its 244,104 KiB ignored data has a
  reproducible-discard or archive/read-back decision;
- do not remove display-v3 or stable until live/dirty experiment evidence is
  quiesced, content-addressed, restored, and accepted;
- do not remove fn-surface, toolkit-gaps, plan-pilot, or pin until their
  database/blob/audit evidence is classified and preserved or proven
  reproducible; and
- remove no branch before its worktree is gone and its non-merged commits have
  this semantic disposition plus accepted evidence preservation.

Only `seon-plan-fix` remains eligible for a later explicitly authorized
worktree removal. This report provides no such authorization.

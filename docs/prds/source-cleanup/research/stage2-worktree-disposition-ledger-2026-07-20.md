---
type: research
status: active
tags: [prd, architecture, runtime]
---

# Stage 2 worktree disposition ledger

## Decision

All ten registered Git worktrees now have an explicit proposed Stage 2
disposition. This closes the missing inventory, but it does not authorize the
atomic rename: the shared checkout still has active source owners, and the
`seon-stable` and `seon-display-v3` owners still control live legacy processes
and unique experiment evidence.

No retained branch has source that should be bulk-merged into the current
lineage. The prior semantic audit proves every old commit is patch-equivalent,
already integrated, obsolete, historical, or a requirement to reimplement
through the current mechanism. The remaining worktree problem is ownership and
evidence preservation, not source recovery.

This was a read-only audit except for this report. It did not change a
worktree, branch, index outside this report, lifecycle record, process, port,
cache, database, source file, or shared roadmap.

## Observation and authorities

The snapshot was taken at primary-checkout HEAD `cf148643` on 2026-07-20. The
shared checkout was moving under authorized source lanes, so this is a
disposition ledger rather than a freeze-base claim. Exact paths, refs, tips,
and dirty summaries were recomputed with `git worktree list --porcelain` and
per-worktree `git status --short`.

The durable classification authorities are:

- [[stage2-freeze-readiness-delta-2026-07-20]] and
  [[stage2-freeze-readiness-refresh-2026-07-20]] for the Stage 2 entry gates;
- [[../../runtime-reliability/research/remaining-worktree-semantic-integration-audit-2026-07-14]]
  for commit-level import decisions;
- [[../../runtime-reliability/research/worktree-evidence-preservation-manifest-2026-07-14]]
  and
  [[../../runtime-reliability/research/legacy-lane-retirement-audit-2026-07-14]]
  for dirty, ignored, database, blob, and process evidence; and
- [[../../../seon/issues/autocomplete-worktree-evidence-preservation]] for the
  still-open owner acceptance gate.

The four dispositions have deliberately narrow meanings:

- **merge-before-rename** — settle and release active work into the primary
  checkout before the freeze;
- **translate-after** — retain a lane across the cut and translate it at the
  recorded Stage 2 completion point;
- **preserved-out-of-scope** — the checkout is not a Stage 2 source input and
  stays untouched under its evidence-preservation owner; and
- **requires-owner-decision** — evidence does not authorize choosing a
  destructive retirement or a cross-cut sequencing policy for the owner.

## Complete ten-worktree ledger

| # | Exact path | Branch / exact HEAD | Dirty state at snapshot | Known owner and evidence | Proposed Stage 2 disposition |
|---:|---|---|---|---|---|
| 1 | `/Users/sean/src/seon` | `codex/runtime-reliability-refactor` / `cf1486436f49e4c836d66a16a4127aa0fcbbc868` | Active: seven tracked source/test edits and five untracked paths were observed during the audit; `.shadow-cljs-b2/` and `out-b2/` remain protected U-series caches | Source-cleanup orchestrator plus current SCI/renderer owners; the primary checkout is the rename target, not a retirement target | **merge-before-rename** — every active owner must land or hand off coherently, release paths, and acknowledge the freeze at one newly recorded stable HEAD |
| 2 | `/Users/sean/src/seon-acme-agentic-tool-refinement` | `codex/acme-agentic-tool-refinement` / `ecd8d889d0f1c218f33b8bd777104cab3d8693b9` | Git-clean; ignored database state remains | ACME refinement handback; all eight commits are integrated or patch-equivalent, while roughly 238 MiB of database state still needs archive/read-back, hash-backed duplicate, or reproducible-discard acceptance | **preserved-out-of-scope** — no Stage 2 source import; leave the checkout and data untouched under its evidence owner |
| 3 | `/Users/sean/src/seon-display-v3` | `repl-autosuggest/display-v3` / `b7be18be5758c91a970de6bae50388e6f5232908` | 83 tracked paths: generated bundle, `shadow-cljs.edn`, and reference-link type changes; untracked/ignored modules, virtualenv, and checkpoints | Retired autocomplete evidence owner; four unique commits are explicitly rejected as imports. A live Node/writer pair still listens on 7982/7983 and the roughly 4.2 GiB cluster plus tune exports require owner-controlled quiescence and archive/read-back | **preserved-out-of-scope** — do not merge, translate, stop, clean, or retire it as part of Stage 2 |
| 4 | `/Users/sean/src/seon-fn-surface` | `repl-autosuggest/fn-surface-pin` / `17a82314e8bfb3597706d34fb0ee2f6184186274` | Generated `out-acme/client/main.js` plus untracked modules | Function-surface lane; all six commits are integrated or patch-equivalent, but its ACME database remains unclassified | **preserved-out-of-scope** — no rename input; preserve until the database has an accepted evidence disposition |
| 5 | `/Users/sean/src/seon-pin` | detached / `93c8d8adbe9a9b51f1593e6921c0af045e04eae7` | Modified `shadow-cljs.edn` plus untracked LoRA audit fixture | Inspect/autocomplete evidence owner; no commit beyond the merge base, but the fixture and roughly 92 MiB blob corpus remain a preservation gate | **preserved-out-of-scope** — current Stage 2 must not absorb or discard its audit evidence |
| 6 | `/Users/sean/src/seon-plan-fix` | detached / `7c08240e18a18ebb1eeaabae357788f9f2bc16b3` | Tracked-clean; only an untracked `node_modules` symlink was observed | Retired planning lane; both commits are patch-equivalent and the retirement audit found no unique database, blob, or process evidence | **requires-owner-decision** — it is the sole checkout eligible for later user-authorized removal, but this audit does not infer or execute that destructive choice; preserving it through Stage 2 is safe |
| 7 | `/Users/sean/src/seon-plan-pilot` | detached / `299b37f7a00f7252fabfea3240521dee3c61ab53` | Generated ACME bundle plus untracked modules | Planning/autocomplete evidence owner; both commits are patch-equivalent, while the roughly 373 MiB training-seed database lacks archive/read-back and current staging proof | **preserved-out-of-scope** — no source import; retain the database evidence untouched |
| 8 | `/Users/sean/src/seon-stable` | `repl-autosuggest/stable` / `609c40065efd7ae058cd62fe7a927d96f34b7a51` | Five tracked source/config/report/scorer/bundle edits plus four untracked authority/evidence files | Stable autocomplete/Inspect owner; five behavioral commits are patch-equivalent and the remainder is classified. Its live Node/writer pair owns 7980/7981, its dirty `acme/src/acme/pod.cljs` overlaps the term cut, and its databases/scorer/continuation evidence require owner-controlled archive/read-back | **requires-owner-decision** — owner must choose either pre-cut evidence closure with no import, or an explicit translate-after handoff anchored to the Stage 2 completion range; Stage 2 must never kill, adopt, merge, or rewrite it implicitly |
| 9 | `/Users/sean/src/seon-toolkit-gaps` | `repl-autosuggest/toolkit-gaps-pin` / `299b37f7a00f7252fabfea3240521dee3c61ab53` | Generated ACME bundle plus untracked modules | Toolkit/autocomplete evidence owner; both commits are patch-equivalent, while ACME/default database and blob evidence remains unclassified | **preserved-out-of-scope** — no source import; retain until evidence is proven duplicate, reproducible, or archived |
| 10 | `/Users/sean/src/seon/.claude/worktrees/gym-metric-validation` | `gym-metric-validation` / `684445a271ba2a00213c0ab506ae0eb3a740f720` | Staged paid-test rename plus eight modified retired-gym scenarios | Retired-gym evidence owner; no patch-unique commit, but the dirty migration patch still needs a content-addressed artifact and checksum verification | **preserved-out-of-scope** — never restore the deleted gym and never discard the patch during Stage 2 |

## Shortest owner coordination list

Only three acknowledgements are needed before this ledger can stop blocking a
freeze candidate:

1. **Primary checkout owners:** commit or explicitly hand off every active
   rename-scope path, release the artifact, and acknowledge one stable HEAD.
2. **Stable owner:** choose and record its pre-cut versus translate-after
   sequencing, quiesce its 7980/7981 pair through that owner when required,
   and accept the evidence archive/read-back result. This is the one retained
   checkout with a dirty living source path that directly overlaps the term
   rename.
3. **Display/evidence owner:** acknowledge that display-v3 and the other seven
   old lanes are preserved-out-of-scope for Stage 2, and coordinate the live
   7982/7983 pair independently. Their eventual cleanup is not a prerequisite
   source merge and must not be smuggled into the rename.

`seon-plan-fix` needs a user decision only if someone wants to remove it. No
decision is needed to leave it safely preserved across Stage 2.

## Serious ambiguity and freeze consequence

The only serious ambiguity is `seon-stable`. The source-integration audit says
there is no safe commit to import, but its dirty ACME client file contains the
retired term and its live legacy pair owns the configured ACME ports. That
combination makes neither automatic merge-before nor silent
preserved-out-of-scope treatment honest. The owner must record whether the
checkout is frozen historical evidence after quiescence or a future consumer
that must translate at the exact Stage 2 completing commit range.

Until that answer and the primary source handoffs exist, Stage 2 remains
not freeze-ready. This is an ownership blocker, not authority to retire any
worktree or process.

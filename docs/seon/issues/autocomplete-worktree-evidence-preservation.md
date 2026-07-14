---
type: issue
status: open
severity: friction
tags: [issue, archive, database, research]
---

# Autocomplete worktrees contain unclassified database and model evidence

## Problem

Retired autocomplete worktrees cannot be cleaned safely because their ignored
database directories and generated artifacts have not been inventoried,
content-addressed, or assigned a durable disposition. Git commit comparison
alone cannot prove that their unique experimental evidence is preserved.

Autocomplete training remains paused. No worktree or cluster data should be
removed merely to reclaim space before this preservation gate is complete.

## Evidence

- `/Users/sean/src/seon-display-v3` remains registered at `b7be18be` and holds
  a multi-gigabyte `data/clusters/acme` database plus ignored display/export
  artifacts. The completed audit measured approximately 4.2 GB of database
  and unique artifacts; a current gross filesystem measurement is larger,
  confirming that Git history is not the whole payload.
- `/Users/sean/src/seon-plan-pilot` remains detached at `299b37f7` with an
  ignored `data/clusters/acme` database. The completed audit measured an
  approximately 373 MB seed payload; current gross size is larger.
  `root-cause-fixes-2026-07-13.md` explicitly names that database as the
  training-legal real-world seed for query/report trajectories.
- The worktree registry also retains `seon-pin`, `seon-fn-surface`,
  `seon-plan-fix`, `seon-stable`, `seon-toolkit-gaps`, and the retired gym
  validation worktree. Their tracked commits, dirty state, ignored artifacts,
  running-process ownership, and database/blob disposition are not captured by
  one cleanup manifest.
- `seon-stable` contains evidence not represented by its branch commits: a
  44 MB cluster, a dirty completed fair scorer/report, an untracked
  continuation-drive design and probe, and 404 KB of ignored prediction/example
  outputs. The design, probe, scorer, and report SHA-256 values are recorded in
  [[docs/prds/runtime-reliability/research/inspect-autocomplete-lane-integration-audit-2026-07-14]].
  Removing the worktree from Git status alone would lose this evidence.
- The root-cause runbook says to inspect display-v3 and plan/reconcile work
  before removing worktrees and to preserve the held-out export first. It does
  not provide artifact digests or a per-cluster keep/archive/rederive/delete
  decision.

The 2026-07-14 re-audit confirmed the earlier measured database sizes rather
than the misleading gross worktree sizes: display-v3 ACME is about 4.2 GB,
plan-pilot ACME about 373 MB, and stable ACME about 44 MB. It also found:

- stable owns the live 7980/7981 process pair and has dirty ACME source/config,
  Inspect scoring research/scripts, a generated bundle, and an untracked
  regular `acme/CLAUDE.md` that must be reconciled into `AGENTS.md` authority;
- display-v3 owns the live 7982/7983 process pair and has dirty generated
  bundle/reference-link state;
- fn-surface, plan-pilot, and toolkit-gaps have dirty generated ACME bundles
  and local `node_modules`; and
- pin and the retired gym worktree retain audit/test changes.

Git patch-id comparison does confirm that the five selected stable-lane
implementation commits for provider egress, the `openai` dependency,
first-class long-term planning, same-title plan guarding, and EDN-only
reconciliation are already integrated on the current branch. That narrows the
tracked-code merge question, but it does not classify the ignored databases or
dirty experiment evidence above.

## Owner

The repl-autosuggest evidence/archive owner inventories the experiments; the
operator/database owner provides safe quiescence and database identity. Git
worktree cleanup consumes that manifest only after the owning lane accepts each
artifact's disposition.

## Acceptance

- A durable manifest covers every registered non-primary worktree and every
  local cluster beneath it: path, commit/branch, dirty tracked/untracked state,
  database identity and basis, blob/database sizes, generated artifact paths,
  content digests, producing command/version, reproducibility status, and
  owning experiment.
- The manifest includes the stable fair-scoring and continuation raw outputs,
  not only their summary documents. It distinguishes measured decoding-shape
  evidence from unproven accuracy and records why the code is preserved rather
  than imported.
- Display-v3's database and unique v3 exports are either promoted into the
  canonical versioned autocomplete artifact/evidence location or archived
  content-addressably with a verified restore/replay check. Plan-pilot's seed
  database is preserved with identity/basis and a proven read-only staging
  recipe before its worktree is touched.
- Every other worktree and cluster receives an explicit keep, promote,
  content-addressed archive, reproducibly discard, or duplicate disposition.
  “Duplicate” is backed by hashes; “reproducible” names the current supported
  command and inputs.
- Operator status proves no preserved database, writer, pod, or port is owned
  by a worktree before removal. Cleanup is coordinated, removes only accepted
  paths, and never resets or discards shared-tree work.
- After cleanup, the manifest's retained artifacts pass checksum verification
  and the canonical data-quality path can consume the display/held-out and
  plan-pilot evidence without those worktrees.

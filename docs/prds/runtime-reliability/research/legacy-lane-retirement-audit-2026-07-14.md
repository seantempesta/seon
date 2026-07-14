---
type: research
status: completed
tags: [research, archive, agent]
---

# Legacy lane retirement audit — 2026-07-14

## Scope and result

This read-only audit classifies the registered autocomplete, Inspect, plan,
function-surface, and retired-gym worktrees for eventual retirement. It
rechecks [[worktree-evidence-preservation-manifest-2026-07-14]],
[[inspect-autocomplete-lane-integration-audit-2026-07-14]], and
[[legacy-acme-archive-readback-runbook-2026-07-14]] against current Git,
filesystem-allocation, process, and listening-port metadata.

No branch was switched, no process was signalled, no database was opened, no
worktree was reset/cleaned/removed, and no ignored database contents were read.
The protected current-tree
`docs/prds/repl-autosuggest/research/shared-schema-section-2026-07-13.md` was
not read, hashed, staged, or changed. The user-owned
`/Users/sean/src/seon-acme-agentic-tool-refinement` worktree and
`codex/acme-agentic-tool-refinement` branch were identified only from the Git
worktree registry and otherwise left untouched.

**One checkout is eligible for later user-authorized removal now:**
`/Users/sean/src/seon-plan-fix`. It is detached, has no patch-unique commits,
tracked changes, database, blob directory, or live process, and retains only
a reproducible untracked `node_modules` symlink to the current checkout. No
branch deletion accompanies it.

Every other old checkout remains blocked by unique database, blob, dirty patch,
or experiment evidence. Stable and display-v3 additionally remain live and
mutable on ports 7980–7983. This report authorizes no cleanup.

## Audit dependency ledger

| Authority or mechanism | Selected identity | Use in this audit |
|---|---|---|
| Git worktree/ref graph | repository Git metadata at current `cbf7461a`; `git worktree list --porcelain`, `rev-list`, `cherry`, status, diff, and log | Registered ownership, divergence, patch equivalence, and dirty state |
| OS process/socket metadata | macOS `ps` and `lsof`; PID, parent, start time, cwd, command, and listening socket | Distinguish active runtime ownership from filesystem residue without signalling anything |
| Preservation manifest | [[worktree-evidence-preservation-manifest-2026-07-14]] | Prior per-file hashes, database/blob inventory, and cleanup gates |
| Historical read-back | [[legacy-acme-archive-readback-runbook-2026-07-14]] | Exact old Datahike/Konserve locks and the closed-copy verification protocol |
| Lane integration audit | [[inspect-autocomplete-lane-integration-audit-2026-07-14]] | Commit-level import, supersession, and rejection decisions |
| Current operator boundary | `bin/seon`; default writer PID 21496 and pod PID 21498 at this snapshot | Separates the maintained default cluster from orphaned legacy processes |

This unit changes no Clojure behavior, so it introduces no new dependency API
assumption to ground in `reference-code/`. Database read-back remains bound to
the exact historical Datahike/Konserve source identities already recorded in
the archive runbook; a current dependency must never open an old live store.

## Registered worktrees and ref disposition

`git worktree list --porcelain` currently registers ten worktrees: the current
integration checkout, the separately owned ACME refinement checkout, and the
eight old lanes below. The prior preservation manifest counted nine before the
new ACME refinement checkout was created; its old-lane inventory remains
complete.

| Worktree | Ref / HEAD | Current owner state | Commit disposition | Retirement state |
|---|---|---|---|---|
| `/Users/sean/src/seon` | `codex/runtime-reliability-refactor` / `cbf7461a` | Active shared integration checkout | Keep; it is the comparison target and contains the reviewed integration commits | Not a retirement target |
| `/Users/sean/src/seon-acme-agentic-tool-refinement` | `codex/acme-agentic-tool-refinement` / `c0d0eecf` | Active, user-owned parallel lane; not inspected beyond registry metadata | Review only after explicit handoff | Excluded from cleanup |
| `/Users/sean/src/seon-display-v3` | `repl-autosuggest/display-v3` / `b7be18be` | Dirty: generated bundle, Shadow config, 81 reference-link type changes, ignored model environment/checkpoints; live pod/writer | Four patch-unique commits (`1fe6866d`, `b7dfd32d`, `5da97025`, `b7be18be`) are explicitly rejected as imports; their valid requirements are retained for canonical reimplementation | Blocked by live 4.3 GiB allocated cluster, tune exports, dirty patch, and archive/read-back gate |
| `/Users/sean/src/seon-fn-surface` | `repl-autosuggest/fn-surface-pin` / `17a82314` | Dirty generated bundle and ignored modules; no process | Two patch-unique commits (`65a662ac`, `cc22084f`) are superseded by current qualified schemas/docstrings and must not be cherry-picked | Blocked by unclassified 15,576 KiB ACME database |
| `/Users/sean/src/seon-pin` | detached `93c8d8ad` | Dirty Shadow config plus untracked LoRA audit fixture; no process | Zero commits patch-unique to current | Blocked until equivalent current Inspect evidence preserves or supersedes the audit fixture and 92,288 KiB blob corpus |
| `/Users/sean/src/seon-plan-fix` | detached `7c08240e` | Tracked-clean; untracked `node_modules -> /Users/sean/src/seon/node_modules` symlink; no database or process | Zero commits patch-unique to current | **Eligible for user-authorized worktree removal now** |
| `/Users/sean/src/seon-plan-pilot` | detached `299b37f7` | Dirty generated bundle and ignored modules; no process | Zero commits patch-unique to current | Blocked by 381,992 KiB training-seed cluster and missing current staging/read-back proof |
| `/Users/sean/src/seon-stable` | `repl-autosuggest/stable` / `609c4006` | Dirty source/config/scorer/report/bundle plus four untracked evidence/authority files; live pod/writer | Five behavioral commits are patch-equivalent (`c8b907ea`, `91dee957`, `61813a97`, `2bf4eb14`, `39fd81ec`). Nine remaining patch-unique commits are old compact-context or integration-document history; their useful 35B runbook content is already retained and none is a safe cherry-pick | Blocked by live database, scorer/continuation/raw evidence, instruction drift, dirty patch, and archive/read-back gate |
| `/Users/sean/src/seon-toolkit-gaps` | `repl-autosuggest/toolkit-gaps-pin` / `299b37f7` | Dirty generated bundle and ignored modules; no process | Zero commits patch-unique to current | Blocked by unclassified 43,468 KiB ACME database and 2,188 KiB default blobs |
| `/Users/sean/src/seon/.claude/worktrees/gym-metric-validation` | `gym-metric-validation` / `684445a2` | Staged paid-test rename plus eight modified retired-gym scenarios; no process | Zero commits patch-unique to current; dirty patch is migration history, not code to restore | Blocked until the recorded binary patch is preserved and verified |

The detached plan-pilot and toolkit-gaps checkouts intentionally share
`299b37f7`. That shared commit does not make their ignored databases
duplicates. The annotated
`runtime-reliability-pre-refactor-2026-07-13` tag remains the durable pre-cut
archive anchor and is not a cleanup target.

Related branch deletion must happen after its worktree is removed. Display-v3,
stable, and fn-surface carry patch-unique commits whose disposition is explicit
rejection or supersession, not merge ancestry; deleting those branches will
therefore require a separately authorized forced ref deletion after their
dirty and ignored evidence is archived. Toolkit-gaps and gym carry no
patch-unique commits but remain registered and evidence-blocked. Detached
checkouts have no associated branch ref to delete.

## Preserved data and existing read-back gates

| Owner | Current allocated payload | Existing gate | Missing evidence before deletion |
|---|---:|---|---|
| Current checkout legacy ACME | 213,460 KiB under `data/preserved-clusters/current-acme-legacy-288d4461` | Internal package `38409f97…` exists; 11,791 files verify; historical network-denied read-back recovered basis `536871171`, 220 schema attrs, 3 agents, 44 evals, 14 plans, 30 context blocks, and all 38 referenced blobs | Promote package to owner-approved durable/off-machine storage and verify it there; source bytes remain until then |
| display-v3 ACME | 4,435,308 KiB at recheck | Live checkpoint basis `536877667`, 269 schema attrs, schema `aceac1c…`, 18 agents, 533 evals, 78 plans, 29 autocomplete/typeahead attrs; seven tune/export files already have per-file hashes | Stop pod, capture final writer identity, stop writer, package closed cluster plus artifacts/dependency source, verify extraction, perform historical read-back, record durable URI and owner acceptance |
| stable ACME/default | 44,860 KiB / 104 KiB | Live checkpoint basis `536870984`, 202 schema attrs, schema `0d98d8b1…`, 3 agents, 11 evals, 2 plans; scorer/probe/report and 14 raw outputs have hashes | Same final quiescence/package/read-back gate; archive the complete fair-scoring directory, continuation files, dirty patch, and instruction-drift reconciliation |
| plan-pilot ACME | 381,992 KiB | Named training-legal seed; Git identity known | Archive identity/basis, historical read-back, and one current read-only staging/export proof writing only outside archive |
| pin default | 92,288 KiB blobs plus audit fixture | Fixture hash recorded | Equivalent current Inspect evidence or explicit preservation package/read-back disposition |
| fn-surface ACME | 15,576 KiB | No unique evidence identified | Hash-backed duplicate or named supported reproduction proof; size is not identity |
| toolkit-gaps ACME/default | 43,468 KiB / 2,188 KiB | No unique evidence identified | Hash-backed duplicate or named supported reproduction proof |
| gym dirty patch | No database | Prior binary patch digest `a2402005…` | Content-addressed patch artifact plus checksum verification |

The current-checkout package is materially further along than the original
manifest's final blocker list: package and disposable-copy read-back now exist.
It remains deletion-blocked only because internal same-volume staging is not
the owner-approved durable promotion required by the runbook.

## Current process and port ownership

The four previously recorded legacy processes remain alive with the same PID,
parent PID 1, cwd, role, and port. Their elapsed age and continued ownership
confirm they were not adopted by the current operator.

| Lane | Role | PID | Cwd | Listening endpoint | Ownership consequence |
|---|---|---:|---|---|---|
| stable | retired Node pod | 31038 | `/Users/sean/src/seon-stable` | `127.0.0.1:7980` | Still capable of submitting writes; stop first during maintenance |
| stable | retired JVM writer | 30873 | `/Users/sean/src/seon-stable` | `127.0.0.1:7981` | Old `seon.server.boot`, `store` layout, Datahike `67934f65`, Konserve `df6818d4`; capture final identity after pod exits |
| display-v3 | retired Node pod | 52189 | `/Users/sean/src/seon-display-v3` | `127.0.0.1:7982` | Still capable of submitting writes; stop first during maintenance |
| display-v3 | retired JVM writer | 45003 | `/Users/sean/src/seon-display-v3` | `127.0.0.1:7983` | Old `seon.server.boot`, `store` layout, Datahike `6e2d9bee`, Konserve `0.9.353`; capture final identity after pod exits |
| current default | maintained JVM writer | 21496 | `/Users/sean/src/seon` | `127.0.0.1:64849` | Current operator-owned process; not part of cleanup |
| current default | maintained Node pod | 21498 | `/Users/sean/src/seon` | `127.0.0.1:7890` | Current operator-owned process; not part of cleanup |

No persistent runtime process command or cwd matched fn-surface, pin, plan-fix,
plan-pilot, toolkit-gaps, or gym. A concurrent read-only Git audit can briefly
hold plan-fix as its cwd; that transient observer is not a lane owner and must
also be absent before removal. Recheck immediately before any removal; absence
in this snapshot is not a permanent lease.

## Exact safe-to-remove-now list

Subject to a fresh read-only recheck and explicit user authorization, the
complete list is:

1. Worktree `/Users/sean/src/seon-plan-fix` at detached `7c08240e`.

There is no corresponding branch deletion. Its only untracked content is a
`node_modules` symlink into the current checkout, not unique dependency bytes.
All other worktrees and all related branch refs remain blocked as specified
above.

## Sequenced cleanup commands for later authorization

These commands are a reviewed runbook, not authorization to execute them.
Run one lane at a time and stop at the first identity mismatch.

### Non-destructive preflight

```bash
git -C /Users/sean/src/seon worktree list --porcelain
git -C /Users/sean/src/seon-plan-fix status --short --untracked-files=all
readlink /Users/sean/src/seon-plan-fix/node_modules
git -C /Users/sean/src/seon rev-list --left-right --count --cherry-pick codex/runtime-reliability-refactor...7c08240e
lsof -nP +D /Users/sean/src/seon-plan-fix
git -C /Users/sean/src/seon worktree prune --dry-run --verbose

```

Expected plan-fix evidence is exactly the untracked `node_modules` symlink to
`/Users/sean/src/seon/node_modules`, `0` patch-unique right commits, and no
process/open-file owner. If any result differs, do not remove it.

For stable/display-v3 maintenance, first revalidate exact identities and save
fresh metadata without signalling:

```bash
ps -p 31038,30873,52189,45003 -o pid=,ppid=,lstart=,command=
lsof -a -p 31038,30873,52189,45003 -d cwd
lsof -nP -iTCP:7980 -iTCP:7981 -iTCP:7982 -iTCP:7983 -sTCP:LISTEN
git -C /Users/sean/src/seon-stable status --porcelain=v2 --branch --untracked-files=all
git -C /Users/sean/src/seon-display-v3 status --porcelain=v2 --branch --untracked-files=all

```

Then execute the bounded identity expression from
[[legacy-acme-archive-readback-runbook-2026-07-14]] against each already-open
writer and compare it with the recorded live checkpoint.

### Destructive maintenance after explicit authorization

Plan-fix can be removed independently:

```bash
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-plan-fix
git -C /Users/sean/src/seon worktree list --porcelain
git -C /Users/sean/src/seon worktree prune --dry-run --verbose

```

Stable and display-v3 require the complete archive runbook. The only safe
process order is explicit, revalidated PIDs: `TERM` one pod; prove its port
closed; capture final writer identity; `TERM` its writer; prove all endpoints
closed and no file open below the cluster; then package the closed bytes. Never
use `pkill`, a port-wide kill, or a current operator command against these old
processes.

After the package is content-addressed on owner-approved durable storage,
extracted checksums pass, the historical disposable-copy read-back exactly
matches final identity, every dirty/untracked artifact digest verifies, and
the owner accepts the disposition, remove worktrees individually:

```bash
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-stable
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-display-v3
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-plan-pilot
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-pin
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-fn-surface
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon-toolkit-gaps
git -C /Users/sean/src/seon worktree remove --force /Users/sean/src/seon/.claude/worktrees/gym-metric-validation

```

That block is deliberately not all-at-once: execute one line, verify retained
archive checksums and `git worktree list`, then consider the next lane. `--force`
is present only because the accepted evidence currently includes dirty or
untracked paths; it must never substitute for preservation.

Only after the corresponding worktree is gone and archive checksums still
verify may the owner authorize deletion of these non-merged refs:

```bash
git -C /Users/sean/src/seon branch -D repl-autosuggest/stable
git -C /Users/sean/src/seon branch -D repl-autosuggest/display-v3
git -C /Users/sean/src/seon branch -D repl-autosuggest/fn-surface-pin
git -C /Users/sean/src/seon branch -d repl-autosuggest/toolkit-gaps-pin
git -C /Users/sean/src/seon branch -d gym-metric-validation

```

Use `-D` only for the three audited non-merged histories whose useful evidence
has been preserved and whose code import was explicitly rejected/superseded.
If a `-d` command refuses, stop and re-audit rather than escalating it. Keep
the pre-refactor tag. Do not include the active ACME refinement worktree or ref
in any cleanup command.

## Remaining retirement blockers

- Owner authorization for any destructive step is absent.
- Stable and display-v3 are live; final post-pod identities, closed packages,
  durable promotion, historical read-back, and shutdown acceptance are absent.
- Plan-pilot lacks archive/read-back and current staging proof.
- Pin lacks equivalent current Inspect evidence or an accepted preservation
  package.
- Fn-surface and toolkit-gaps lack hash-backed duplicate/reproducible proof.
- Stable's complete scoring/continuation evidence and instruction drift, the
  display-v3 tune corpus, and the gym patch are not yet in verified durable
  packages.
- The current-checkout legacy package is verified only on the internal volume;
  its preserved source bytes cannot be deleted before durable promotion.
- The active ACME refinement lane has not handed back its commits/evidence and
  is categorically outside this retirement audit.

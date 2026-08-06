---
type: research
status: active
tags: [prd, architecture, runtime]
---

# Stage 2 freeze-readiness refresh

## Decision

Stage 2 is still **not freeze-ready**, but the U4-specific blockers from the
first readiness delta are resolved. Commit `b7808e35` settles the U4 host and
database boundary, and U4 closed its retained `u15` branch: there is no branch
process record, filesystem branch coordinate, or matching Git branch left.
Those facts must not remain in the active blocker count.

The shortest current falsifier is instead an external concurrent source lane
whose owner has not yet been identified. At observed HEAD `c7700584`, five
`src/my` namespaces are being translated from `.cljs` to `.cljc`, apparently as
part of JVM-portability integration, so the default watcher is rebuild-pending
and its old client is drained. Stage 2 cannot freeze, quiesce, or compute a
rename manifest across that moving source boundary. Stage 1.5 Unit 1A
explicitly does not own these paths.

This refresh is read-only evidence. It did not run a lifecycle command, touch
a branch or worktree, stop or adopt a process, prune a cache, edit source, or
change the shared roadmap. `.shadow-cljs-b2/` and `out-b2/` remain protected
for U-series replay and U11 cleanup.

## Observation basis

The audit read the closest `AGENTS.md`, [[../roadmap]], the original
[[stage2-freeze-readiness-delta-2026-07-20]], the settled pod-term plan, current
operator records, Git status and worktree metadata, process listeners and
working directories, and the runtime-reliability preservation evidence.

The observed base was `c7700584`. The probes were deliberately non-mutating:
`git status`, `git worktree list --porcelain`, `find`/`sed` over process
records, `bin/seon status`, `bin/acme status`, `lsof`, and `ps`.

## Resolved since the first delta

| Prior blocker | Current evidence | Disposition |
|---|---|---|
| U4 host/database source ownership | U4 landed `b7808e35`; the formerly dirty `db/id.cljc`, `host.clj`, `host/context.clj`, `host/record.clj`, host tests, and drill paths are no longer dirty | Resolved for Stage 2; translate the committed result during the rename |
| Retained `u15` branch and `pod.edn` | `tmp/seon-operator/branch-processes` is empty, `git branch --list '*u15*'` is empty, and the active process-record census contains no `u15` path | Resolved by its owner; do not recreate or further reconcile it |
| Unknown owner of ports 7980/7981 | PID 31038 (Node) and PID 30873 (JVM) both have cwd `/Users/sean/src/seon-stable`; the preservation ledger identifies this as the retained stable worktree pair | Ownership identified, but shutdown/handoff remains unresolved |
| Four pre-rename `pod.edn` records | Exactly three remain: default, `kimi-k3-test`, and `reactive-proof` | Count corrected; the remaining three still block the identity cut |

## Current blocker ledger

### 1. Unidentified source owner and incoherent default artifact

The current tracked/untracked pair set is:

```text
D  src/my/kb.cljs                 ?? src/my/kb.cljc
D  src/my/kb/shared.cljs          ?? src/my/kb/shared.cljc
D  src/my/plan.cljs               ?? src/my/plan.cljc
D  src/my/plan/internal.cljs      ?? src/my/plan/internal.cljc
D  src/my/ui.cljs                 ?? src/my/ui.cljc
```

These paths do not belong to Stage 1.5 Unit 1A or Stage 2. They appear related
to concurrent JVM-portability integration, but that is not sufficient evidence
to assign ownership. The default status consequently reports watcher
`rebuild-pending`, writer alive, and the old client drained/not-ready. The
orchestrator must identify the actual owner; that owner must commit and
explicitly release these paths (and any related `host/context.clj` integration
boundary), then the orchestrator must obtain a coherent default build before
considering a freeze base.

### 2. Three persisted pre-rename client identities

The exact remaining records are:

```text
tmp/seon-clusters/kimi-k3-test/processes/pod.edn
tmp/seon-clusters/reactive-proof/processes/pod.edn
tmp/seon-operator/processes/pod.edn
```

The two named-cluster records point to July 19 generations whose recorded PIDs
are no longer present. They are stale records, but that does not authorize
manual deletion: the owners must resolve them through the pre-rename operator
and provide absence evidence. The default record points to the currently
drained generation and is likewise removed only by coordinated pre-rename
quiescence. No restore- or intent-named EDN file was found; database-backed
restore intent is still not proved absent.

### 3. Retained stable processes on the ACME port pair

`bin/acme status` reports its configured target down, while 7980 and 7981 are
still listening. Unlike the first delta, the owner is now concrete:

| Port | PID | Process | cwd / owner |
|---|---:|---|---|
| 7980 | 31038 | `node out-acme/client/main.js` | `/Users/sean/src/seon-stable` |
| 7981 | 30873 | legacy `seon.server.boot` writer | `/Users/sean/src/seon-stable` |

The Stage-2 orchestrator needs the stable-worktree owner's explicit handoff
and owner-executed stop plus port-absence proof. It must not kill, adopt, or
reinterpret this pair through the current checkout's ACME operator.

### 4. Worktree dispositions remain incomplete

`git worktree list --porcelain` still reports ten worktrees total. Existing
preservation reports classify much of their evidence and specifically name
the stable pair, but Stage 2 still lacks an accepted freeze disposition for
every entry: merge-before-rename, translate-after, preserved-but-out-of-scope,
or separately authorized retirement. Worktree existence is not itself a
failure; missing owner acknowledgement and rename sequencing are.

### 5. Old packages and fresh terminology manifest

`tmp/package-v8` through `tmp/package-v14` and relocation probes remain
pre-rename artifacts. They are invalid as post-cut evidence and must be
regenerated after the identity cut, not deleted as a readiness shortcut.
Because U4 and later source-cleanup commits changed living source after the
dated terminology inventory, the vendor-excluded, per-file sweep must still
be recomputed at the final stable freeze HEAD.

The B2 caches are explicitly outside both concerns. Preserve
`.shadow-cljs-b2/` and `out-b2/` until U11.

## Shortest safe readiness path

1. Identify the external owner of the five `.cljs` to `.cljc` translations and
   any related `host/context.clj` portability work. Obtain that owner's coherent
   commit, proof, and explicit path release. Rebuild default to one coherent
   ready artifact; any further source movement invalidates the candidate.
2. Obtain explicit dispositions from every source lane and all ten worktrees.
   The stable owner must acknowledge the rename sequence and stop its 7980/
   7981 pair; the `kimi-k3-test` and `reactive-proof` owners must retire their
   stale records through the pre-rename operator.
3. Announce the lane freeze and record one stable HEAD. Under that exact
   pre-rename code, quiesce default and configured ACME through their owners.
   Prove all three `pod.edn` records absent, ports 7890/7891/7980/7981 unbound,
   locks absent, and database-backed restore intent absent.
4. Recheck HEAD and tracked status, then compute and classify the fresh
   vendor-excluded terminology manifest. Abort and restart the freeze if the
   source or HEAD moves.
5. Only after steps 1-4 pass, run the three full suites at the unchanged freeze
   commit and begin the atomic four-part rename. Cross the identity boundary
   with cold `up`, never `restart`, then regenerate packages and perform the
   required default, ACME, web UI, restarted-MCP, skill-sync, and final sweep
   proofs.

## Readiness verdict by falsifier

| Order | Falsifier | Refreshed result |
|---|---|---|
| 1 | Stable HEAD and no active edits in rename scope | **Fail**: five in-flight namespace translations have an unidentified external owner |
| 2 | Explicit lane and ten-worktree dispositions | **Fail/not fully evidenced** |
| 3 | No pre-rename process records | **Fail**: three `pod.edn` records remain; `u15` is resolved |
| 4 | Both operator surfaces down and all four ports absent | **Fail**: default is degraded/alive; stable owns live 7980/7981 |
| 5 | Restore intent absent | **Not proved**: filesystem probe is negative only |
| 6 | Fresh per-file terminology manifest at freeze HEAD | **Pending** until source stabilizes |
| 7 | Three full suites at unchanged freeze commit | **Premature** while 1-6 fail |

The earliest dependency-ready action is therefore owner coordination and
source stabilization, not a rename edit or lifecycle cleanup.

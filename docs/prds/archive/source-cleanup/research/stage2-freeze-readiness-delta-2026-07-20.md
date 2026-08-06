---
type: research
status: active
tags: [prd, architecture, runtime]
---

# Stage 2 freeze-readiness delta

## Decision

Stage 2 is **not freeze-ready** at the observed tree. The atomic rename plan is
still the correct contract, but its entry gates fail before any rename work is
authorized:

- the checkout has active SCI U4 edits in `src/seon/db/id.cljc`,
  `src/seon/execution/host.cljs`, `src/seon/host/context.clj`, and the new
  `src/seon/host/record.clj`;
- the source-cleanup orchestrator is concurrently integrating its own roadmap,
  register, and value-route research changes;
- the default cluster is running pre-rename `pod` identity while its watcher is
  rebuild-pending;
- four `processes/pod.edn` records remain, including U4's retained `u15`
  branch;
- the configured ACME operator reports down while ports 7980 and 7981 are
  nevertheless bound by a Node/JVM pair, so that status is not cluster-wide
  absence proof;
- ten Git worktrees still require explicit merge-before, translate-after, or
  disposable dispositions; and
- all existing release packages predate the identity cut and remain invalid as
  post-rename evidence.

This report is a delta audit only. It does not change the settled mapping or
execution order in
[[../../database-authority-mesh/research/pod-term-retirement-plan-2026-07-20]].

## Observation basis

The audit began at `e27ada04` and observed HEAD advance through `f9fa01fd`
while other authorized lanes committed and integrated work. That movement is
itself sufficient to reject a freeze base. The evidence below was collected
read-only on 2026-07-20; a real freeze must recompute it at one stable HEAD.

Read authorities:

- root `AGENTS.md` and `docs/prds/source-cleanup/AGENTS.md`;
- [[../roadmap]] and [[../register]];
- the pod-term retirement plan;
- `docs/prds/sci-execution-runtime/{AGENTS.md,roadmap.md}`; and
- the current status, tracked diff, process records, listeners, worktree list,
  packages, and vendor-excluded terminology sweep.

No lifecycle command, branch operation, cache deletion, source edit, or shared
roadmap edit was performed.

## Ownership boundary

The current dirty paths belong to two different programs and must not be
silently absorbed into Stage 2:

| State | Actual owner | Stage-2 disposition |
|---|---|---|
| `db/id.cljc`, `execution/host.cljs`, `host/context.clj`, new `host/record.clj` | SCI execution-runtime U4 | External active program work. Wait for a coherent U4 commit, proof, and explicit release. The rename may then translate the settled result. |
| retained branch `default-u15` and its `pod.edn` | SCI U4 kill/replay proof | Protected. Leave untouched; U4 closes it during its integration pass. Stage 2 verifies absence only after that owner action. |
| `.shadow-cljs-b2/`, `out-b2/` | completed SCI B2 experiment; possible U-series replay input | Protected reproducible caches. Hold through U-series work; U11 owns cleanup. They are neither a clean-tree failure nor proof. |
| source-cleanup `roadmap.md`, `register.md`, and new value-route research | source-cleanup top-level orchestrator | Active in-program integration, but not Stage-2-owned. Wait for a path-limited commit and release before freeze. |
| broad code/downstream/docs/skills rename | Stage-2 orchestrator | Protected from every other lane only after all owners explicitly acknowledge the freeze. |

The Stage-2 rename remains orchestrator-owned because its first code commit
crosses `src/`, `script/`, `bin/`, `config/`, tests, Shadow config, MCP config,
release manifests, and process identities. A source-cleanup dependency does not
turn another program's uncommitted file into Stage-2 ownership.

## Current entry-gate failures

### Active work and moving base

`git status --short` observed the four SCI paths above plus concurrent
source-cleanup documentation. The default watcher reported `rebuild-pending`,
so neither the running artifact nor the current source tree is a coherent
candidate for three-suite or live proof. The current U4 edits also introduce
fresh living uses such as “registered pod-side” and “the pod stamps” in the
new host boundary. Those are normal pre-rename work, not defects to repair in
the U4 lane; they become rename inputs only after U4 settles them.

### Persisted process identities

Exactly four pre-rename process records were observed:

```text
tmp/seon-clusters/kimi-k3-test/processes/pod.edn
tmp/seon-clusters/reactive-proof/processes/pod.edn
tmp/seon-operator/branch-processes/default-u15/processes/pod.edn
tmp/seon-operator/processes/pod.edn

```

The default record names a live client generation. `bin/seon status` reported
watcher, writer, and `pod` alive, default rebuilding, and `default-u15` down
but retained/ready. A down retained branch is not absent persisted identity:
the old record remains unreadable by the post-rename process-id lookup.

No `processes/*restore*.edn` record was found by the filesystem probe. That is
useful but insufficient: the freeze still must prove the database-backed
restore intent absent through the pre-rename operator's owning status/query.

### ACME absence contradiction

`bin/acme status` reported its configured watcher and `pod` absent. At the
same observation boundary, listeners existed on 7980 and 7981, owned by Node
and Java processes. This likely reflects a separately coordinated downstream
process coordinate, but ownership was not inferred from PIDs. The shortest
safe conclusion is only that “`bin/acme status` says down” does not yet prove
the ACME port set absent. The freeze owner must identify that lane and receive
its explicit handoff; it must not kill, delete, or adopt the processes.

### Worktrees

`git worktree list --porcelain` returned ten worktrees:

1. the shared source-cleanup checkout;
2. `seon-acme-agentic-tool-refinement`;
3. `seon-display-v3`;
4. `seon-fn-surface`;
5. detached `seon-pin`;
6. detached `seon-plan-fix`;
7. detached `seon-plan-pilot`;
8. `seon-stable`;
9. `seon-toolkit-gaps`; and
10. `.claude/worktrees/gym-metric-validation`.

The settled plan already calls out `seon-stable` as a mandatory sequencing or
translate-after decision because `acme/src/acme/pod.cljs` overlaps the rename.
This audit found no durable explicit disposition for the other nine. Their
existence alone is not a blocker; the missing disposition and owner
acknowledgement are.

### Packages and caches

`tmp/package-v8` through `tmp/package-v14` and their relocation probes remain.
They embed the old `:seon.release.member/pod` member and cannot cross the cut.
Do not delete them as a readiness shortcut: invalidate them as evidence, then
regenerate and verify one package after code-identity step 1.

The B2 caches are intentionally excluded from this concern. They are ignored,
reproducible build inputs retained for U-series proofs and stay untouched until
U11.

## Terminology sweep delta

The dated inventory remains a planning aid, not a rename manifest. The current
vendor-excluded sweep already differs from it:

- `shadow-cljs.edn` has 21 non-RunPod matches rather than 20;
- `src-inspect-ai/README.md` and `src-inspect-ai/pyproject.toml` now contribute
  active matches absent from the dated table;
- the SCI host boundary now contributes `src/seon/host.clj`,
  `src/seon/host/context.clj`, and `src/seon/execution/host.cljs` matches; and
- living issue/docs and skill files have moved as cleanup work landed.

The old inventory also includes dated research/history that the final sweep is
allowed to retain, so comparing one aggregate count would be misleading. At
the recorded freeze HEAD, compute a fresh per-file manifest over the settled
scope, classify every hit as one of:

1. Seon runtime/process vocabulary to translate;
2. frozen RunPod vendor vocabulary to preserve byte-for-byte;
3. frozen `pod-host/` historical owner; or
4. deliberate dated research/history citation.

The last sweep succeeds only when every residual has one of the final three
classifications. Never use a case-insensitive mechanical replacement across a
`runpod` substring.

## Pre-rename quiescence protocol

Run this only after Stage 1.5/1.6 and all SCI/source-cleanup owners have
committed and released their paths:

1. Enumerate this session's lanes and separately launched tasks. Obtain an
   explicit commit/handoff acknowledgement from every owner whose paths fall
   inside the rename scope. Record all ten worktree dispositions. Do not infer
   release from a clean-looking diff.
2. Confirm U4 has completed its retained-branch proof and closed `u15` itself.
   Resolve the `kimi-k3-test` and `reactive-proof` records with their owners;
   never delete their `pod.edn` files by hand.
3. Announce the source freeze and record the candidate HEAD. Recheck that HEAD
   and tracked status before every destructive or lifecycle boundary.
4. Under the still-pre-rename operator, run `bin/seon down` and `bin/acme
   down`. Coordinate any separately configured downstream operator identified
   by the 7980/7981 listeners through its owner.
5. Prove absence: both status surfaces down; ports 7890, 7891, 7980, and 7981
   unbound; no `pod.edn` or restore-admin record in default, ACME, cluster, or
   branch process roots; no relevant live operator lock; and no retained
   database restore intent. Ignore historical test-fixture `locks/` trees,
   which are not live operator coordinates.
6. Recheck tracked status and HEAD. Run all three complete suites at that exact
   freeze base and record their full counts. If any source or HEAD moves,
   discard the candidate base and restart steps 3-6.
7. Compute the fresh per-file terminology manifest plus pre-freeze RunPod
   tripwire counts. Only then begin the four uninterrupted, path-limited rename
   commits from the settled plan.
8. Cross the identity boundary with cold `up`, never `restart`; regenerate the
   release package; restart the MCP client before its round-trip; then perform
   default, ACME, web, downstream, skill-sync, and final sweep proof.

## Shortest readiness falsifiers

These checks are ordered to avoid expensive suites when the freeze is already
impossible:

| Order | Falsifier | Current result |
|---|---|---|
| 1 | Stable HEAD plus no unacknowledged tracked edits in rename scope | **Fail**: HEAD moved during audit; SCI and orchestrator paths active. |
| 2 | Explicit owner acknowledgements and ten worktree dispositions | **Fail/not evidenced**. |
| 3 | `find` over active process roots returns no `pod.edn` or restore-admin record | **Fail**: four `pod.edn` records. |
| 4 | Both operator status surfaces down and 7890/7891/7980/7981 unbound | **Fail**: default alive; 7890, 7980, and 7981 bound; ACME status contradicts its port set. |
| 5 | Pre-rename retained restore intent absent | **Not proved**: filesystem negative only. |
| 6 | Fresh vendor-excluded per-file manifest classified at freeze HEAD | **Fail/stale**: known inventory drift. |
| 7 | Three full suites at the unchanged freeze commit | **Premature** while 1-6 fail. |

The earliest dependency-ready action is therefore not a rename edit. It is to
let U4 and the source-cleanup integration finish, obtain explicit handoffs,
then rerun falsifiers 1-6. Only a fully green result authorizes the expensive
freeze-base suites and atomic Stage-2 cut.

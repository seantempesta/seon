---
type: research
status: complete
tags: [research, operator, store, boot, decision]
---

# R3 — store path and operator noun options — 2026-08-13

## Verdict

The defect is not the word "clusters". It is that `data/clusters` holds
three kinds of children — cluster directories, the one process-root
store, and blob staging — while its name promises one kind, and that the
operator root is then recovered by matching those two literal path
segments upward (`src/seon/cluster.clj:595-601`,
`script/seon/fresh_operator.clj:556-561`). Renaming the directory without
removing the upward inference moves the confusion; removing the inference
is the change that deletes a mechanism.

Recommendation: **Option 2** (move the non-cluster children out to
`data/store`, `data/blob-staging`), with the reserved-name refusal from
the addendum landed in the same commit. Option 3 is the deeper fix and is
correct in direction, but it changes what `:seon.boot/root` MEANS, which
AGENTS.md §2.5 classes as breakage requiring a new key across 92 sites in
23 files — a materially larger program that should be its own numbered
item, not folded into R3.

## 1. Current reality, with evidence

### 1.1 Where the path comes from

One default, one derivation:

- `src/seon/cluster.clj:508-514` — `resolve-bootstrap` defaults
  `:seon.boot/root` to the string `"data/clusters"` and derives
  `:seon.boot/store-dir` as `<root>/store`. This is the ONLY place the
  default store path is constructed for a booting cluster.
- `src/seon/cluster.clj:527-544` — `cluster-paths` derives every
  per-cluster path as `<root>/<cluster-name>`; its docstring states the
  store is per process root and "never a per-cluster derivation".
- `resources/seon/schemas/seon.boot.edn:14,121,145` — `:seon.boot/root`,
  `:seon.boot/store-dir`, and `:seon.boot/cluster-name` are all
  `[:string {:min 1}]`. Nothing in the schema constrains the relationship
  between them, and nothing reserves a cluster name.

Consequence of the two derivations sharing a parent: `<root>/store` (the
store) and `<root>/store` (a cluster literally named `store`) are the
same directory. See the addendum.

### 1.2 Who consumes it

Every site that spells the two segments, or builds `<cluster-root>/store`:

| Site | What it builds |
|---|---|
| `src/seon/cluster.clj:509` | default `:seon.boot/root "data/clusters"` |
| `src/seon/cluster.clj:513-514` | `:seon.boot/store-dir` = `<root>/store` |
| `src/seon/cluster.clj:490-494` | docstring stating both |
| `src/seon/cluster.clj:595-601` | `operator-root`: walks UP, requires the directory be named `clusters` with parent named `data`, else returns the cluster root itself |
| `src/seon/cluster.clj:653-657` | `root-store-key` — the store's canonical path is the process-wide holder key |
| `src/seon/operator.clj:281` | cleanup target `<managed-root>/data/clusters` |
| `src/seon/operator.clj:502-504` | `store-dir` = `<managed-root>/data/clusters/store` |
| `src/seon/operator.clj:540` | quiesce cluster-root `<managed-root>/data/clusters` |
| `src/seon/artifact.clj:44-45` | `cluster-root` = `<operator-root>/data/clusters` |
| `src/seon/artifact.clj:57` | store dir = `<root>/store` |
| `src/seon/schedule.clj:483` | log-dir fallback `<managed-root>/data/clusters/<name>/logs` |
| `src/seon/blob.clj:80-91` | blob staging = `<parent-of-store>/blob-staging`, i.e. `data/clusters/blob-staging` |
| `src/seon/cluster/export.clj:284-303` | export target `<parent-dir>/store` |
| `src/seon/bootstrap_drive.clj:399` | `tmp/bootstrap-drives/<id>/clusters` — the `data` segment is ABSENT here |
| `resources/seon/operator/state.clj:5,187` | docstrings naming the `data/clusters` tree |
| `resources/seon/operator/state.clj:470-484` | claim record's `:seon.store/path` and the store id derived from that path |
| `resources/seon/operator/state.clj:643` | advertisement path `<root>/data/clusters/<name>/prepl.edn` |
| `resources/seon/operator/state.clj:652` | advertisement enumeration directory |
| `resources/seon/operator/state.clj:961` | footprint target |
| `script/seon/fresh_operator.clj:110-112` | `cluster-root` = `<root>/data/clusters` |
| `script/seon/fresh_operator.clj:120` | store lock path `<cluster-root>/store` + `.lock` |
| `script/seon/fresh_operator.clj:556-561` | operator root inferred as `.getParentFile .getParentFile` of the configured cluster root |
| `script/seon/fresh_operator.clj:619,628` | offline roster opens `<cluster-root>/store` |
| `script/seon/fresh_operator.clj:2008` | reset-path store open |
| `script/seon/dev/mcp.clj:289` | advertisement path in the not-found diagnostic |
| `bin/test:329` | `result_root="$run_root/data/clusters"` |
| `test/seon/cluster/boot_test.clj:358,380` | asserts the literal default `"data/clusters"` |
| `test/seon/maintenance_schema_test.clj:54,135`, `test/seon/maintenance_test.clj:203`, `test/seon/schedule_test.clj:53` | fixture paths spelling the tree |
| `AGENTS.md:81` | "today at `data/clusters/store`" |
| `docs/seon/architecture/agent-runtime.md:262`, `docs/seon/architecture/observability.md:224` | the `data/clusters` tree as the cleanup/catalog subject |

Two facts make the path load-bearing beyond string spelling:

1. **The store id IS the path.** `src/seon/cluster/store.clj:154-163` and
   `resources/seon/operator/state.clj:470-484` both derive the Konserve
   store id as `UUID/nameUUIDFromBytes` over the canonical store path.
   Moving the directory therefore changes the store's identity — an
   existing store at the old path does not simply "still work" at a new
   one.
2. **A first-party re-identification path already exists.**
   `src/seon/cluster/export.clj:219-282` (`reidentify-at!` /
   `reidentify!`) stamps a store directory with the identity its path
   derives. This is what makes a move technically survivable — but
   database data is disposable by ruling, so this is noted as
   archaeology, NOT as a migration proposal.

### 1.3 What is actually wrong

- **The plural noun is inherited from a layout that no longer exists.**
  `7736702d8` (2026-06-03) introduced `data/clusters/default/store` — one
  store PER cluster — and the commit says so explicitly: the path strings
  "carry `cluster` in their names ... for the eventual multi-DB layout".
  The fresh tree kept the segment (`1e3aff7d6`, 2026-07-27, default root
  `"data/clusters"`), and `5c95e259c` (2026-07-27, revised cluster
  contracts) introduced `derived-store-dir` = `<root>/store`, collapsing
  many stores into one. The one store was placed where the many used to
  live — inside the directory named for the many.
- **The directory's children are heterogeneous.** Cluster directories,
  `store`, `store.lock` (`resources/seon/operator/state.clj:28-32`, a
  canonical sibling of the store directory), and `blob-staging`
  (`src/seon/blob.clj:91`). Any enumeration of the directory must filter;
  `resources/seon/operator/state.clj:652-660` filters on the presence of
  `prepl.edn`, which is correct, but a past enumeration that did not
  reported `store` AS a cluster
  ([mcp-toolset-audit](mcp-toolset-audit-2026-08-01.md):184).
- **The operator root is recovered by name matching.**
  `src/seon/cluster.clj:595-601` returns the grandparent only when the
  directory is literally named `clusters` under a parent literally named
  `data`; `script/seon/fresh_operator.clj:556-561` assumes the same depth
  positionally. This is inference from a naming convention where a
  declared fact belongs (AGENTS.md §2.2). It is already inconsistent in
  the tree: `src/seon/bootstrap_drive.clj:399` passes
  `tmp/bootstrap-drives/<id>/clusters`, whose parent is not `data`, so
  the inference silently returns the cluster root itself as the operator
  root.
- **The word "root" is overloaded three ways.** `:seon.boot/root` is the
  CLUSTER root; `:seon.operator/managed-root` and
  `seon.operator.state/control-root` are the operator/installation roots
  (`resources/seon/operator/state.clj:186-189`); and AGENTS.md §1 calls
  the store "the process-root store". So the key named `root` is the one
  root that is not the process root.

## 2. Options

### Option 1 — Vocabulary only; no path change

**Shape.** Disk layout unchanged. `AGENTS.md:81` and the two architecture
docs are reworded to state the true shape: `data/clusters` is the boot
`:seon.boot/root`, holding one directory per cluster PLUS the one
process-root store at `data/clusters/store`, its `store.lock` sibling,
and `blob-staging`. `src/seon/cluster.clj:490-494` and
`resources/seon/operator/state.clj:5,187` docstrings say the same.

**Guarantee.** No existing operator root breaks. No store id changes. No
gate risk beyond docstring edits.

**Cost.** ~6 files, docs and docstrings only: `AGENTS.md:81`,
`docs/seon/architecture/agent-runtime.md:262`,
`docs/seon/architecture/observability.md:224`,
`src/seon/cluster.clj:490-494`,
`resources/seon/operator/state.clj:5,187`. Existing roots: unaffected.
Vocabulary table: no new row required (store, branch, cluster, process
root, operator are all already grounded).

**What we give up.** The heterogeneous directory stays, so every future
enumerator must keep filtering, and the `store`-named-cluster collision
stays open unless the addendum lands. The upward name-matching inference
stays load-bearing.

**Defensible?** Yes, on its own terms — the confusion is genuinely
documentation-shaped in part, and the ruling that data is disposable does
not make breaking every live root free of friction. But it leaves a
naming-convention inference in production code, which §2.2 forbids.

### Option 2 — Move the non-cluster children out (RECOMMENDED)

**Shape.**

```
data/
  clusters/<name>/{logs,prepl.edn,derived,...}   only clusters
  store/                                          the one process-root store
  store.lock                                      its canonical sibling
  blob-staging/                                   blob staging
  operator/                                       unchanged control root
```

`:seon.boot/root` keeps its meaning (the cluster root) and its default
`"data/clusters"`. `:seon.boot/store-dir` is derived from the operator
root rather than from `<root>/store`: `src/seon/cluster.clj:513-514`
calls the existing `operator-root` helper and appends `store`.
`src/seon/blob.clj:80-91` stops deriving its staging directory as the
store's parent and takes the operator root explicitly.

**Guarantee.** `data/clusters` contains exactly clusters; enumeration
needs no filter to be correct, and a cluster can never collide with the
store directory. No schema key changes meaning, so §2.5 accretion holds.

**Cost.** Code: `src/seon/cluster.clj:513-514,490-494`;
`src/seon/operator.clj:502-504`; `src/seon/artifact.clj:44-45,57`;
`src/seon/blob.clj:80-91`; `src/seon/bootstrap_drive.clj:399` (its root
must gain the operator-root shape or pass `:seon.boot/store-dir`
explicitly); `resources/seon/operator/state.clj:470`;
`script/seon/fresh_operator.clj:120,619,628,2008`; `bin/test:329`
(unchanged in spelling but its `result_root` now also needs the store
sibling). Tests: `test/seon/cluster/boot_test.clj:358,380` assert the
literal derivation and must be re-expected; the three fixture files
spelling log paths are unaffected. Docs: `AGENTS.md:81` and the two
architecture docs.

Existing roots: **BREAK**. The store id derives from the path
(`src/seon/cluster/store.clj:154-163`), so every existing operator root
must be re-created with `bin/seon reset --force`; stale root claims under
`data/operator/claims/roots` carry the old `:seon.store/path`
(`resources/seon/operator/state.clj:470-484`) and are stale until
reclaimed. Any `tmp/` scratch root and any retained `tmp/test-runs/run.*`
root is exhaust and simply discarded.

**What we give up.** The upward `operator-root` inference
(`src/seon/cluster.clj:595-601`) is not removed — it becomes MORE
load-bearing, because the store path now depends on it rather than only
on `<root>/store`. That is the honest cost of the cheap option, and it
argues for Option 3 as a later, separately-numbered item.

### Option 3 — Invert the root: the process root is the configured root

**Shape.** A new key `:seon.boot/process-root` (default `"data"`) names
the process root; the cluster root is derived DOWNWARD as
`<process-root>/clusters`, the store as `<process-root>/store`, blob
staging as `<process-root>/blob-staging`. `:seon.boot/root` is deleted in
the same refactor (git is the archive; §2.5 forbids keeping both).
`src/seon/cluster.clj:595-601` and
`script/seon/fresh_operator.clj:556-561` are DELETED — nothing infers a
root upward from a path spelling, because the process root is the
declared input.

**Guarantee.** Everything Option 2 guarantees, plus: no production code
matches directory names to recover a root, and the three overloaded
"root" nouns collapse to two (process root, control root) with
`:seon.operator/managed-root` becoming a synonym to retire or keep
deliberately.

**Cost.** Everything in Option 2, PLUS the key change. `:seon.boot/root`
appears at **92 sites across 23 files** (`src/seon/operator.clj`,
`src/seon/artifact.clj`, `src/seon/cluster.clj`,
`src/seon/bootstrap_drive.clj`, `src/seon/test/runner.clj`,
`src/seon/eval/drive.clj`, 13 test namespaces,
`script/seon/fresh_operator.clj`, and three schema files —
`resources/seon/schemas/seon.boot.edn`, `seon.operator.edn`,
`seon.test.runner.edn`). Every one of those call sites passes a value
whose MEANING changes by one directory level, so a mechanical rename is
not sufficient — each must be read. Existing roots: break exactly as in
Option 2. Docs: Option 2's set plus the vocabulary table (a new row for
`:seon.boot/process-root`, with `:seon.boot/root` recorded as a legacy
spelling in the third column) and AGENTS.md §1's boot-order text.

**What we give up.** A large red-tolerant batch landing during an
already-batched rename program (R1/R2/R4 in
[unsettled.md](../plan/unsettled.md):145-172). The blast radius overlaps
the test-runner root plumbing, which is the machinery the closing
`bin/test --all` depends on — a bad thing to be mid-flight in when the
gate runs.

## Addendum — applies to every option

`:seon.boot/cluster-name` is `[:string {:min 1}]`
(`resources/seon/schemas/seon.boot.edn:14`) with no reserved names, and
`cluster-paths` (`src/seon/cluster.clj:541`) places a cluster at
`<root>/<name>`. Under today's layout, `bin/seon start store` therefore
resolves the cluster directory to exactly the process-root store
directory. `script/seon/dev/mcp.clj:82-85` validates only name shape.
Under Option 1 this stays live; under Options 2 and 3 the collision is
structurally gone, but a refusal naming the reserved names is still worth
one predicate at the one admission seam.

## Sources read end to end

- `src/seon/cluster.clj` (bootstrap resolution, cluster paths, root store
  acquisition), `src/seon/cluster/store.clj`,
  `src/seon/cluster/process.clj`, `src/seon/cluster/export.clj`
- `script/seon/fresh_operator.clj` (path helpers, status derivation,
  reset), `script/seon/dev/mcp.clj`, `bin/seon`, `bin/test`
- `resources/seon/operator/state.clj`, `resources/seon/schemas/seon.boot.edn`
- `docs/prds/sci-execution-runtime/plan/unsettled.md` (R3 statement)
- Git: `7736702d8` (2026-06-03), `1e3aff7d6` and `5c95e259c` (2026-07-27)

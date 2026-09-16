# Cohosted development adoption: the publication re-read the roots its own reload had redefined

2026-09-16 · branch `steward-platform` · fix `19874b71b` on `9cf51b7cf`

Triage of `seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
(batch 79, run root `tmp/test-runs/run.XCm4Xm`, failure block in
`tmp/orchestrator/gate-results/batch-79/named.log:269-300`), which ran to
completion cold and failed twice:

1. `boot_test.clj:1073` — `[200 200 200 200]` observed `[200 200 200 200 200 200 200 200]`.
2. `(str/includes? (definition default) "[] 2")` threw NPE: the adopted
   definition read **nil** on `default` after adoption.

Both reds are one cause. Neither is about scalar rows, cohosting, or
`adoption-identities`; the lead's three candidate causes are all refuted below.

## The cause

`development-source-refresh!` reloads the program's namespaces
(`src/seon/cluster.clj:2240-2247`, `(require namespace-name :reload)`), and on a
cluster with no recorded `:seon.source/commit-id` that set is EVERY namespace
carrying `:seon.ns/source` — 107 of them in the probe, `seon.fn` and
`seon.cluster` among them. Re-evaluating those namespaces re-evaluates
`(def seon.fn/source-roots …)` and `(def seon.cluster/source-roots …)`.

The post-adoption compare then re-read those vars:

```clojure
(when-not (= (:seon.source/digest published)
             (:seon.source/digest (current-source-snapshot)))   ; pre-fix
  (refused! "Source changed during development adoption; …"))
```

`current-source-snapshot` derived the root set from the very vars the adoption
had just re-decided — a seam acting on a mirror its own authority replaces
(§2.1). Where the running roots differ from the ones the publication was
analyzed over (in this drill the test's `with-redefs` of both root vars, which
the reload silently undoes), the compare reports a source change that never
happened:

- red 1: the whole publication takes its one retry
  (`retrying-source-change`, `src/seon/cluster.clj:624`), so the four observed
  adoption stages run twice — 8 responses, all 200;
- red 2: the retry republishes over the REVERTED roots, where the probe file is
  no longer under any source root. Its file artifact is missing and its
  identities retract, so `[:seon.fn/sym "adoption-probe/value"]` pulls nil on
  `default` while `beta`, which adopts nothing, keeps `[] 1`.

The test's `with-redefs` is not the defect; it only makes the defect visible.
Any publication whose declared roots are not the roots it was analyzed over —
a reloaded `def`, a future config-carried root set — hits the same compare.

## Measured, cohosted, on a frozen tree

Probe: two cohosted clusters (`default`, `beta`) on one fresh root, an extra
source root holding `adoption-probe/value`, edited `1 → 2` and published with
`(refresh-source! root [path] "default")`. Run in a throwaway worktree
(`git worktree add tmp/cohost-wt HEAD`, `reference-code` symlinked) so no
neighbouring lane's edit could move the tree. Reads are taken under the
cluster's own projection.

| | stages observed | `default` after | `beta` after |
|---|---|---|---|
| HEAD~1 (`9cf51b7cf`) | **8** (`… JVM instrumentation`, `source changed during adoption; retrying publication once`, second pass) | **nil** | `[] 1` |
| HEAD (`19874b71b`) | **4**, then `development cluster converged` | `[] 2` | `[] 1` |

Pre-fix, the retry's publication reported
`complete publication: (:attribute-retraction :missing-desired-artifact :schema-resource)`
and re-analyzed **2047** source inputs against the reverted roots; post-fix the
one pass stays on `incremental scalar publication: 1 paths; reasons=()`, and the
published digest is identical across repeated runs (`19c3223…`).

Earlier runs in the live checkout reproduced the same 8-stage/nil pair twice
(`tmp/cohost-triage/cohost2.log`), with the digest diff naming
`docs/seon/issues/**` appearing in the post-adoption snapshot — files that
belong only to the REAL `cluster/source-roots`, which is the direct evidence
that the reload had restored it mid-publication.

## The three candidate causes, refuted

- *"the second cluster's prior commit no longer equals the pre-publication
  commit, so the scalar branch is skipped"* — refuted: adoption runs for the
  NAMED cluster only (`refresh-source!` looks up one instance,
  `src/seon/cluster.clj:2295`), and `beta` is never reconciled. Its definition
  stays `[] 1` in every run, pre- and post-fix.
- *"the first adoption advances a shared value the second reads"* — refuted:
  there is no second adoption. The repeat is one cluster adopting twice under
  the one retry.
- *"the scalar path commits rows the live projection never re-derives
  (`adoption-identities` → `program/row-identity`)"* — refuted: post-fix the
  same scalar path (`reasons=()`, 1 path) commits the row and the definition
  reads `[] 2` immediately. Pre-fix the failing pass was not scalar at all; it
  was the retry's complete rebuild retracting the identity.

## What changed (`19874b71b`)

- `publication-roots` (`src/seon/cluster.clj:1641`) reads BOTH root sets once,
  at `refresh-source!` entry (`src/seon/cluster.clj:2329`), and hands that value
  to `source-snapshot`/`current-source-snapshot` (`:1667`, `:1693`),
  `stable-manifest` (`:1891`), `full-source-refresh!` (`:1922`),
  `incremental-source-refresh!` (`:1957`), `development-source-refresh!`
  (`:2148`), and the populate request for a manifest-less population
  (`:1697`). `source-snapshot` keeps its zero-arity for callers that mean "the
  declared roots" (three boot tests and `fresh_operator_test` use it).
- `test/seon/cluster/boot_test.clj` — the drill's `[200 200 200 200]` was a
  hand-written count that mirrored both the observed stages AND how many times
  adoption runs. It now derives from the one `adoption-stages` set it already
  needs for filtering: every observation is 200, and
  `(frequencies (map first @responses))` equals one per stage. A second
  adoption pass now fails as "adoption ran twice", not as arithmetic.
  The three unit tests that call the private publication functions directly
  were updated to the new arity.

## Not verified

- No test JVM was run (assignment constraint): `bin/test --paths … --
  seon.cluster.boot-test seon.cluster-test` and `--platform` remain owed. The
  evidence above is a live probe, not the gate.
- The `:seon.test/adoption-identities` fact and `seon.issue/adopt!` were not
  re-examined; they were never implicated.
- Why a freshly forked cluster records no `:seon.source/commit-id` (so its FIRST
  development adoption reloads all 107 namespaces and reconciles every
  namespace) was observed but not investigated. It is the reason this defect
  needed only one edit to appear.
- A separate seam of the same event is filed as
  [an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry](../../../seon/issues/an-analysis-time-snapshot-change-never-reaches-the-one-publication-retry.md):
  two snapshot-compare refusals carry no declared source-change cause, so they
  refuse without the one retry. Reproduced once in the shared checkout.

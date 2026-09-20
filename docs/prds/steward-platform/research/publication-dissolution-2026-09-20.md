---
type: research
status: active
created: 2026-09-20
tags: [publication, test-system, wave/publication-velocity]
---

# Publication dissolution — decision checkpoint

Items 1–4 are **not implemented**. No production path was changed, no
publication was run, and no performance improvement is claimed. This
checkpoint records a dependency probe before the binding specification's
owner decision gate. Item 5 remains outside this assignment.

## Decision: what constitutes an analysis input?

The [binding specification](../plan/publication-dissolution-spec-2026-09-20.md)
requires both N-file work and analysis cached by a file's content digest.
That digest alone cannot identify a complete clj-kondo result: a caller's
findings and resolved usage metadata depend on the callee's declaration.
This matters before replacing the existing complete-analysis fallbacks in
item 1, not only when adding the persistent cache in item 3.

The native dependency probe changed `f` from one argument to two while
leaving the caller's SHA-256 unchanged. clj-kondo v2026.07.24 returned:

| Analysis | Invalid-arity findings | Elapsed |
|---|---:|---:|
| Original callee and caller | 0 | 42 ms |
| Changed callee alone | 0 | 44 ms |
| Changed callee and unchanged caller | 1 | 47 ms |

The new finding is `publication-probe.callee/f is called with 1 arg but
expects 2`. Reusing the original caller analysis by its own digest would
retain zero findings. This is a native dependency probe, **not** a canonical
armed Seon regression or a publication benchmark. It ran once, launched
zero JVMs, and removed its private temporary directory on exit.

Reproduce from the checkout:

```sh
python3 docs/prds/steward-platform/research/publication-analysis-input-probe-2026-09-20.py
```

## Dependency ledger and first-party seams

- Vendored clj-kondo reads `called-fn` arities and compares them with the
  caller's arity in `reference-code/clj-kondo/src/clj_kondo/impl/linters.clj:602`;
  it emits `:invalid-arity` at line 649 and checks privacy at line 660.
- `src/seon/fn/analyzer.clj:122` retains callee arities, privacy and macro
  information in a usage. `invoke-kondo` at line 245 uses the dependency's
  namespace cache; `analyze` at line 373 is the existing analysis owner.
- `src/seon/fn.clj:2033` analyzes one artifact; `build-manifest` at line
  2150 analyzes all captured inputs together. The manifest digest hashes
  file paths and source digests in `manifest-data`, not the resulting
  findings. Equal manifest digests alone therefore do not establish
  incremental/full-analysis parity.
- `src/seon/cluster.clj:2240` begins the existing incremental publication
  owner in the inspected working tree. Structural changes currently reach
  complete analysis. The replacement must preserve the relevant diagnosis
  while deleting that whole-publication fallback.
- The lineage repair remains separately mandatory: `publish!` in
  `src/seon/cluster/source.clj` branches from `:db`, whereas
  `record-results-at-head!` branches from the current commit. Datahike's
  `force-branch!` (`reference-code/datahike/src/datahike/versioning.cljc:323`)
  changes the supplied value's branch and parents; it does not merge the
  prior history's datoms. `seon.fn/index!` currently refuses an existing
  program unless handed `:seon.source/previous-database`.

## Three options for the owner

Estimates below are rough additional implementation effort, not measured
runtime or delivery promises.

1. **Recommended: invalidate affected analysis when declaration interfaces
   change.** Ordinary body edits analyze the changed files; interface edits
   additionally revisit affected callers using declared analysis dependencies.
   Guarantee: retain complete-analysis correctness, with no unconditional
   whole-program rebuild. Cost: approximately 1–2 additional lane-days for
   dependency fingerprints and parity regressions. Give up: a literal
   N-files ceiling for interface changes. Config and analyzer changes also
   have to participate in the fingerprint; an unavailable dependency cannot
   be interpreted as no dependency.
2. **Constrain the N-file path and refuse unsupported changes.** Admit only
   edits whose analysis dependencies remain identical; explicitly refuse
   interface, schema or analyzer-input changes outside that constraint.
   Guarantee: a reused finding never claims unverified current analysis.
   Cost: approximately half a lane-day for admission checks, with broader
   publication still deferred. Give up: automatic publication for those edits.
3. **Separate cached parsing from cross-file resolution.** Retain per-file
   syntax evidence and recompute affected resolution and findings through
   the existing analyzer/indexer owners. Guarantee: N changed source files
   are parsed; dependent facts are still revisited as needed. Cost:
   approximately 3–5 additional lane-days and a larger analyzer change.
   Give up: the bounded scope and literal N-files total-work guarantee.

The stop is required by AGENTS.md §2.5: “when a decision would create hours
of cross-owner work or its guarantees cannot be stated simply, STOP before
production edits”. The binding assignment separately directs this lane to
stop at a genuine decision with three priced options.

Issue: [a file digest does not identify complete caller analysis](../../../seon/issues/a-file-digest-does-not-identify-complete-caller-analysis.md).

## Ownership and verification boundary

Started on `steward-platform`, HEAD `83c32bfc7`. During inspection another
lane advanced HEAD to `44b51bab0`; its source evidence transfer edits became
committed. No foreign session was operated or messaged.

At the last ownership check, `src/seon/cluster.clj`,
`src/seon/test/cache.clj`, and `bin/test` remained dirty. The explicitly held
cluster, turn and schema owners remain outside this lane's edits. No
worktree, scratch cluster, background shell, test suite, default operation,
or cold gate was started. No production paths have been deleted.

Read the binding specification end to end, the supplied AGENTS.md sections
1–3 and lane rules 11–16, the requested working-edge tail, the step-3 census
and required-production-change sections, and both named issues. Read the
launcher, hook, cache, source and fresh operator; inspected the named cluster
publication functions. A whole-file read of unrelated cluster sections and
the remainder of the results-reuse research note is still outstanding.

The spec's before numbers (approximately 200 seconds JVM work, including
27 seconds analysis and 64 seconds population) are inherited evidence, not
measurements from this checkpoint. One-file and complete-publication
before/after phase measurements, the lineage/reuse regression, and item-2
scratch-root verification remain owed after the decision and implementation.

The orchestrator's eventual cold proof is the landed implementation's exact
owned paths with `bin/test --paths … -- seon.test.cache-test
seon.cluster.source-test seon.test-runner-test seon.fn-test`, plus affected
namespaces and `bin/test --platform`. This is an owed command template;
there is no implementation path set or green tally yet.

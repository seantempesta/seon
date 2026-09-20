---
type: issue
status: open
severity: friction
created: 2026-09-20
tags: [issue, publication, wave/publication-velocity]
---

# A file digest does not identify complete caller analysis

## Problem

Publication dissolution proposes caching clj-kondo results by each source
file's content digest and analyzing only edited files. A callee declaration
change can invalidate findings and resolved usage metadata in an unchanged
caller. Removing complete-analysis fallbacks needs an explicit dependency
invalidation guarantee first. This note records a proposed-design defect;
it does not claim a persistent per-file cache already exists.

## Evidence

The [publication decision checkpoint](../../prds/steward-platform/research/publication-dissolution-2026-09-20.md)
records the reproducible native clj-kondo probe: an unchanged caller has
zero invalid-arity findings before its callee changes, and one afterwards.
Analyzing only the edited callee finds zero. No Seon JVM or database was
used; this evidence proves the dependency semantics, not publication parity.

Vendored clj-kondo consumes the callee's arities at
`reference-code/clj-kondo/src/clj_kondo/impl/linters.clj:602` and emits the
caller finding at line 649. `src/seon/fn/analyzer.clj:122` also retains
callee metadata in usage results. A source-only manifest digest can remain
the same across stale and current analysis of the same final tree.

## Owner

Publication dissolution, through the existing `seon.fn.analyzer` and
`seon.fn` analysis owners. The owner settled declaration-change invalidation
through published calls and references. A subsequent body-only probe keeps
name, arity and an explicit Malli contract unchanged but changes an inferred
return type; the unchanged caller gains a type-mismatch warning. The
checkpoint records the reproducer, dependency source and three options for
that narrower policy decision. The owner subsequently ruled exclusion of
body-inferred type findings; the analyzer now declares seven such classes
off for publication, with a real-analyzer regression. No persistent cache
has been implemented.

The namespace-metadata gap was ruled closed by extending invalidation:
adding deprecation to a
required namespace adds a warning to an unchanged require-only file, which
has no function call/reference edge to that namespace. The landing note's
third decision now uses the already-recorded `:seon.ns/requires`
relation. This is a declaration dependency, not another inferred-type class.

The next measured gap is the **public-only** qualification. A legal reference
to a private function, `#'callee/f`, receives a `deprecated-var` finding when
the private declaration gains deprecation metadata. Neither namespace metadata
nor its public declaration name set changes. Existing function edges record
the reference, but the ruled public-only seed set excludes the changed private
declaration. The committed `publication-private-interface-probe-2026-09-20.py`
in the same research directory reproduces this with explicit Malli contracts:
0 findings before, 0 from changed-only analysis, 1 from complete analysis;
41 / 38 / 39 ms. No blocking errors occur. This is another declaration
dependency, not an inferred-body finding. The landing note prices extending
the existing closure to private declarations versus changing finding or source
admission policy. No cache implementation is claimed.


The owner subsequently generalized the closure to **every** recorded declaration
and edge; the public/private and namespace finding decisions above are settled.
The new armed replay proves a different dependency: a body-only change to the
executing indexer changes stored call edges in unchanged inputs. The historical
`call-target` replay (before `af800d1a0` versus current) retains
`#{clojure.core/defn}` incrementally but produces `#{pub.alpha/f}` completely,
while both source digests are equal. Run `1b08bd6813ce`: 2 tests, 24 assertions,
zero failures/errors. The landing note preserves the applicable prototype and
prices shared toolchain provenance, producer-dependency evidence, and explicit
publisher upgrades. No incremental publisher has been installed. This is a
storage identity decision, not a further finding class to exclude.

## Acceptance

The chosen cache contract explicitly includes every input capable of changing
analysis. A canonical publication regression changes a callee declaration
without editing its caller and compares the incremental findings and program
facts with complete analysis. Repeated unchanged requests reuse the evidence;
missing dependency evidence never reports a cache hit.


## Ruled toolchain identity and implementation status

The owner accepted `c4ffc428b` and ruled the producer requires closure plus
vendored pins/dependency coordinates into publication identity. The continuation
implements that comparison in `seon.fn/build-manifest`, carries its digest to
the source seal, and extends the general declaration closure and selected-row
index request. The historical indexer replay now invalidates all inputs and
compares equal artifacts with complete analysis. The issue remains open until
the held cluster population/refresh callers adopt the API and the lineage and
publication-level parity regressions pass. Analysis-level parity is not a claim
that the three publishers have dissolved or that their evidence history survives.

---
type: research
status: complete
title: One clj-kondo cache directory per dependency set
date: 2026-09-28
---

# One clj-kondo cache directory per dependency set

clj-kondo owns the cache and analyzes every supplied file. Seon selects one
directory from sorted dependency classpath entries, the clj-kondo version,
recorded archive gitlinks and Git HEADs of local dependency checkouts. Boot
fills that directory once and removes the other digest directories. Boot also
lints non-program checkout roots and top-level Clojure files, including script/
and dev_cache.clj. Publication continues to lint its selected program files.

This replaces the reverted per-entry experiment. There are no SHA sidecars,
namespace staleness checks, timestamp checks, classpath guesses for a namespace,
analysis reruns, or "changed again" refusal. The former cache observations
and their machinery-specific tests are deleted. Private fixture analysis and
captured-source mirrors remain; a caller supplying a private cache owns its
contents. The database's dependency-analysis reader and publication use the
same selected directory.

The input identity is computed once when this namespace loads. Sorting is
O(dependency entries log dependency entries); Git HEAD reads are once per local
checkout. Normal analysis is one clj-kondo call. Unchanged boot reuses the
dependency directory and only refreshes non-program checkout source. A changed
dependency set pays one full dependency fill. Jar coordinates identify their
versions; mutating a versioned jar in place is not this key's promise.

The dependency seam is clj-kondo 57252e07975710aa579b24f0d1b2b1e04195caa2:
impl/cache.clj:23 reads namespace entries; impl/core.clj:493 and :603 manage jar
skip marks. No per-entry repair is layered over that seam. A completion file is
written only after a full dependency fill returns; a failed fill cannot mark a
directory complete. Generated target/ class directories are excluded from the
dependency input set: enabling the existing compiled program cache must not
change source-analysis identity.

## Disposable boot proof

All proof commits and roots were disposable, created from git archives, never
git worktrees. The main checkout and its submodules were not switched. No drill
or test request ran. Exact operator logs, byte snapshots, dependency diff and
measurement scripts are under tmp/astra-analysis-simple-20260928.

| Operation | Ready ms | Operator total ms | Store byte change | Dependency cache |
| --- | ---: | ---: | ---: | --- |
| First successful fresh population (intermediate candidate) | 57,064 | 66,997 | 117,258,491 total bytes | Filled |
| Final source candidate 367003382, boot 3 | 34,345 | 49,084 | +2,788,881 from prior intermediate boot | 614a339cb74c filled |
| Same commit and inputs, boot 4 | 6,268 | 11,493 | +102,943 | Same directory, completion inode and nanosecond mtime |
| Dependency bump, boot 88147e26f | 17,089 | 59,874 | +679,655 | 348d8b633a82 filled; old directory removed |

The unchanged boot grew allocated storage by 120 KiB; the dependency bump by
772 KiB. These are complete boot/adoption operations, not evaluations. At the
observed rate, 1,000 unchanged restarts would write about 103 MB of logical
store bytes; this is not a per-evaluation estimate. The cache-only proof reads
write zero store bytes. The final disposable store held 120,919,803 logical
bytes / 179,244 KiB allocated, chiefly the fresh program population. No probe
wrote to default's store beyond the earlier ordinary MCP diagnostic recordings.

For the bump, an owned copy of Datahike 05a02804 gained the public declaration
astra-analysis-cache-proof, producing e27d9ff6. Only the disposable candidate's
gitlink moved. Its boot completed; cache-proof.clj read the exact newly filled
entry and found both datahike.api/connect and the new declaration. This proves
new dependency facts were analyzed rather than an old directory merely renamed.
The dependency-class fill took 28,032 ms of the bump's operator total; it is a
separate existing cache, not analyzer self time. Fresh population and changed
dependency fill are whole-input operations explicitly requested for this proof.

Two failures during development informed the final cut: requiring a script/
helper from src broke dependency-class discovery, so the final key reads pins
directly; excluding the checkout's top-level files left dev-cache unanalyzed,
so boot now supplies those files. A first pair of successful boots revealed
different directories when compiled program classes appeared; excluding
generated target/ inputs fixed that, and the final pair proves exact reuse.

## Verification and boundaries

Archive f9bf494bea987178014dabfd59150dd1fcbd08dd plus the five owned source/test
diffs: clj-kondo src script test --cache false reports zero errors and 1606
existing warnings. Contract compilation checked 506 files in 6,200 ms: no
touched findings; the same ten foreign schema-reference findings remain in
script/seon/dev/docstring.clj and script/seon/dev/markdown.clj. Full compilation
is not green. Exact findings are recorded in contracts.log.
No tests were run. Root's focused list:

- seon.fn.analyzer-test/dependency-cache-identity-follows-its-inputs
- seon.fn.analyzer-test/supplied-checkout-files-are-analyzed-once-with-their-current-content
- seon.fn.analyzer-test/scratch-analysis-does-not-write-the-shared-cache
- seon.fn.analyzer-test/complete-roots-and-individual-files-have-parity
- seon.fn-test/submitted-declarations-share-the-dependency-analysis-join
- seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound

The key regression covers 1/10/100 inputs, reordered entries, changed pins,
changed kondo versions and changed classpath entries, with exactly one identity
hash. The source regression checks current arity facts and one analyzer call.
The removed publication tests expected the deleted implicit callee reanalysis
or per-entry scratch-cache repair; current supplied-source behavior and scratch
isolation have direct replacements above.

Source +78/-285 (net -207); tests +32/-248 (net -216). Released owners are
src/seon/fn/analyzer.clj, src/seon/fn.clj, src/seon/cluster/boot.clj,
test/seon/fn/analyzer_test.clj and test/seon/cluster/publication_inputs_test.clj.
No shared-cache, profile, instrumentation, Flow or other lane file was edited.
Default was unavailable after the owner's restart; bin/seon status and MCP
runtime_status both reported no live JVM. The disposable root's down command
confirmed no surviving process. Further live profiling of the queued writer,
derivation and filesystem costs needs root's default JVM restored.

# Loaded file selection and dependency admission

Publication needs the file bytes the JVM loaded. Read those digests directly
from the adopted commit, or use the captured launch manifest before adoption.
Delete the separate whole-program analyzer identity derivation; the existing
selected-analyzer-digest remains the authority for publication seals.

The former loaded-analyzer-digest read all call edges and derived analyzer
reach before returning the file map. The replacement is O(files), with one
indexed query and no schema projection or program graph query. Unknown loaded
state and an adopted commit absent from this store still return no known files.

## Live evidence

Default ran c537242c0. Scratch namespace scratch.astra.one-writer held candidate
functions; no default Var was replaced or reloaded. On the same held store,
all three comparisons returned the same 858 file digests:

| Parent ms | Candidate ms |
| ---: | ---: |
| 113.093 | 3.803 |
| 102.802 | 2.872 |
| 19.049 | 2.755 |

The parent outer function was armed; the scratch candidate outer function was
unarmed, with its installed nested owners armed. These are file-selection
measurements, not whole-publication or cold-boot claims. No memory reduction
was separately measured.

The actual new loaded-files declaration was constructed through source analysis,
published through db/transact! on a disposable live-store branch, and adopted
through the reconcile writer onto a second branch. Published and adopted portable
declarations were equal; adoption reported one operation. The complete envelope
reported 841 ms. Both branch connections were released and branches retired in
finally. This proves entity publication/adoption; host code installation awaits
root's batched restart.

That probe found a producer defect: source-rows omitted the dependency-analysis
join already used by analyze-forms. The dependency cache contained datahike.api,
and shared-dependency-analysis read it successfully, but source-rows refused its
calls as unanalyzed. Three lines apply the existing join once per submitted batch.
Parent admission of one d/db caller refused in 47.044 ms; the candidate admitted
1/10/100 declarations with all target edges present in 57.255/21.716/35.606 ms.
This is a correctness fix, not a claimed timing improvement. The publication
probe used that same candidate analysis plus the installed contract finalizer
and digest owner; it did not fabricate or relax a declaration.

Exact candidate forms, probes and returned envelopes are committed under
tmp/astra-publication-20260928. The source-rows regression requires one dependency
read and one join for batches of 1/10/100 declarations. The loaded-files regression
requires zero db/q program reads and at most one commit acquisition per request.

## Verification and boundaries

Archive basis 2065d482bd643f6ba5341e570421d10f3d421e53 plus these five changed
source/test files: clj-kondo over src script test with --cache false completed
in 6928 ms, zero errors, 1617 repository warnings. Contract compilation examined
507 files in 4596 ms. No touched contract findings; ten existing foreign findings
remain in script/seon/dev/docstring.clj and script/seon/dev/markdown.clj (their
local schema references). Whole-repository contract compilation is not green.

No drill or test request ran. Root's focused list:

- seon.cluster-test/loaded-file-selection-does-not-query-the-program-graph
- seon.cluster-test/the-analyzer-digest-follows-the-analyzing-declarations-only
- seon.cluster.program-change-test/a-launch-on-analyzer-bytes-with-unchanged-definitions-publishes-selected
- seon.fn-test/submitted-declarations-share-the-dependency-analysis-join
- seon.fn-test/a-dependency-macro-sci-cannot-expand-is-host-bound

The obsolete launched-analyzer test is deleted with its deleted producer; the
surviving selected-analyzer test retains the changed-loaded-bytes behavior.
Source changes are +38/-103, net -65 lines; tests +43/-25, net +18 lines.
Foreign health, cache, instrumentation, MCP and root source cuts were preserved.

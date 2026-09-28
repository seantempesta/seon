# Adopt content changes, not publication-only derivation stamps

The reconcile writer already holds current and desired rows. Its adoption
comparison now ignores a file's derivation digest when every other attribute
is equal. Indexing still records that key: publication consumes it to decide
what to reanalyse. Genuine file changes adopt the whole desired row. No
pre-read decision, extra index, second writer or duplicated pull is introduced.
The comparison remains O(touched rows), with one pull-many and two normalizations
per row. The added boolean is explicit in both private contracts and every caller.

## Live evidence

On default c537242c0, scratch candidate reconcile-after called installed owners
and wrote only disposable live-store branches. Same held base and same desired
rows, with only file derivation digests changed:

| Files | Parent ms | Candidate ms | Parent new index bytes | Candidate new index bytes | Changed identities |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 56.925 | 35.035 | 716193 | 417540 | 1 → 0 |
| 10 | 53.292 | 47.617 | 1597074 | 417540 | 10 → 0 |
| 100 | 75.561 | 37.261 | 1885498 | 417540 | 100 → 0 |

Both paths still commit once; four provenance datoms remain on the candidate.
Bytes are new reachable index-node file sizes relative to the base, measured
through Datahike's index mark protocol, not total store growth or GC reclamation.
The parent's outer function was armed, the scratch outer candidate unarmed;
nested owners were installed and armed. These are single paired samples.
All file attributes other than the intentionally unadopted derivation key were
equal. Branch connections were released and branches retired in finally.

A separate publication/adoption probe published 100 restamped files, including
one genuine file digest change. The adopter changed exactly that one identity,
including its new derivation key, and retained the other 99 rows byte-for-value.
The complete MCP envelope took 259 ms. Exact probes and results are under
tmp/astra-adoption-digests-20260928. No host Var was replaced; installation awaits
root's batched restart.

## Verification

Archive ed265cbaaa59490f94309ccb1e3cc0e62ab53dca plus this diff: src/script/test
lint with --cache false, zero errors and 1617 existing warnings, 8371 ms.
Contract compilation checked 507 files in 5957 ms: no touched findings, ten
foreign schema-reference findings in script/seon/dev/docstring.clj and
script/seon/dev/markdown.clj remain. The repository-wide compile is not green.

No tests or drills ran. Root should run
seon.fn-test/adoption-ignores-only-file-derivation-digests, which checks
1/10/100 inputs, one pull-many, exactly 2n normalizations, publication retaining
the keys, and genuine file changes remaining actionable. The three existing
private-seam callers were converted with the changed arity.

Source +19/-11 (net +8); tests +35/-3 (net +32). Only fn.clj and fn_test.clj
changed; foreign root, MCP, health and cache work was untouched.

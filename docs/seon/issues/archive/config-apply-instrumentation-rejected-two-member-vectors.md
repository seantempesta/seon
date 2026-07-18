---
type: issue
status: resolved
severity: blocker
tags: [issue, pod, schema, cljs]
---

# Config apply instrumentation rejected two-member vectors

## Evidence

On 2026-07-18, `bin/seon config apply config/system.edn` returned HTTP 422
under the normally instrumented Bun pod. `seon.state/lookup-ref-pairs` treated
every two-member vector as a possible Datahike lookup ref and passed its first
member to `seon.schema/identity-attr?`. A context-block vector whose first
member was an ordinary map therefore violated that function's keyword input
contract before the recursive traversal could continue.

## Resolution

The lookup-ref recognizer now verifies that the first member is a keyword
before asking whether it is a registered identity attribute. An ordinary
two-member vector of maps remains recursively traversable, including lookup
refs nested inside either map.

The same live proof then exposed a second defect: the reconciliation queries
resolved each identity keyword to its schema entity ID and placed that numeric
ID in Datahike's attribute position. Datahike accepts one scalar bound identity
keyword there; it intentionally does not resolve keywords arriving through a
collection binding in attribute position. Reconciliation now submits one
indexed scalar query for each managed identity attribute inside the same
immutable database value and `execute-many` request, then collapses duplicate
entity rows before comparison.

The focused state regressions pass 21 tests/78 assertions together with startup
initialization. Two consecutive real instrumented applies both returned
`:seon.state/changed? false` and zero operations against the already-converged
default database. The config subset admits both boot and config provenance
because initial database creation installs the selected manifest under the
boot process; later explicit applies use the config process but continue to own
that same declared subset.

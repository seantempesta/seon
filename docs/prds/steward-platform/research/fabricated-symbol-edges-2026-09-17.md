---
type: research
status: landed
created: 2026-09-17
tags: [program-graph, analyzer, symbols]
---

# Fabricated symbol edges

## Result

The program analyzer no longer turns clj-kondo's
`:clj-kondo/unknown-namespace` sentinel into a qualified symbol. The one
stored program-edge member schema now also refuses any symbol that does not
round-trip through `pr-str` and `clojure.edn/read-string` as the same symbol.

I read `AGENTS.md` sections 0–5, the `data-oriented-clojure`,
`clojure-testing`, and `data-modeling` skills, and
`docs/prds/steward-platform/plan/test-system-stage1-3-design-2026-09-17.md`
and its named authorities end to end before the change.

## Reproduction

clj-kondo records an unresolved usage's target namespace as the keyword
`:clj-kondo/unknown-namespace`
(`reference-code/clj-kondo/src/clj_kondo/impl/analyzer/usages.clj:29`,
`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:3813`). The old
`usage-symbol` stringified that keyword into a symbol namespace. A direct
probe showed that
`(symbol ":clj-kondo/unknown-namespace" "visitFile")` prints as
`:clj-kondo/unknown-namespace/visitFile`, which `clojure.edn/read-string`
reads as a keyword rather than the original symbol.

The canonical fixture refusal in
`tmp/orchestrator/gate-results/post-reset-platform.log:2109` was the
`:seon.fn/references` set on entity `34838`. Its exact malformed members were:

```clojure
#{:clj-kondo/unknown-namespace/do
  :clj-kondo/unknown-namespace/original-resolve#
  :clj-kondo/unknown-namespace/overrides#}
```

The same log's persistent-result refusal at line 2875 named
`:clj-kondo/unknown-namespace/text`, already changed into a keyword after the
print/read boundary.

On default at basis `536871500`, a read-only query measured 50 stored
fabricated members: 4 in `:seon.fn/calls` and 46 in
`:seon.fn/references`. This is the same total as the assignment's earlier
basis `536871293` measurement of 2 calls and 48 references; concurrent source
publication changed the distribution, not the class count.

## Seam and fix

`src/seon/fn.clj:332-345` is the common projection from a clj-kondo usage to
a program symbol. It now returns nil for the unknown-namespace sentinel, so
Java interop method names, special forms, gensyms, and unresolved aliases do
not become calls, references, call-arity targets, or file-reference targets.
The existing conservative file join is target-keyed
(`src/seon/fn.clj:1400-1404`): without a real target identity there is no
truthful symbol to store there. The file's analyzed source digest remains the
positive fact that analysis ran and found no target-keyed edge.

The runtime analyzer formerly remapped the sentinel to the usage's
`::analyzer/from` namespace. That was not correct for agent forms: it changed
unknown evidence into a fabricated first-party identity which might not have
a declaration. The remap is removed at `src/seon/fn.clj:859-861`, so runtime
and file analysis use the same projection.

`src/seon/program.cljc:19-41` owns the total EDN round-trip predicate.
`resources/seon/schemas/seon.program.edn:5-13` declares the shared
`:seon.program/edge-symbol` member type once. Calls, references,
file-reference targets, call-arity symbols, callers, callees, recorded test
reach, and test subjects reuse it at
`resources/seon/schemas/seon.fn.edn:28-69` and
`resources/seon/schemas/seon.test.edn:1-10`. A deliberately malformed member
now reaches the real writer and is refused as `:seon.db/invalid-write`.

`test/seon/fn_test.clj:2505-2551` is the class regression. It analyzes one
fixture containing an unresolved alias, Java interop methods, and a gensym;
checks every emitted call, reference, and call-arity target for exact EDN
round-trip; admits the rows through the canonical database fixture; and
proves the shared schema refuses a deliberately malformed member. It uses
`with-provenance-file`, whose database is `seon.test-support/with-database`,
and `seon.test-support/program-fn-row`; it has no hand-rostered schema or
mock analyzer.

## Measurements and verification

A complete post-fix analysis of `src` and `test` produced 7,695 program rows
and measured zero symbols anywhere in those rows whose namespace is
`":clj-kondo/unknown-namespace"` (before: 50; after: 0).

Owning iteration:

```text
bin/test-fast --paths src/seon/fn.clj src/seon/program.cljc resources/seon/schemas/seon.program.edn resources/seon/schemas/seon.fn.edn resources/seon/schemas/seon.test.edn test/seon/fn_test.clj -- seon.fn-test
60 tests, 420 assertions, 0 failures, 0 errors
```

Required combined iteration:

```text
94 tests, 652 assertions, 4 failures, 10 errors
```

`seon.fn-test` was green. In `seon.test-support-test`, both named regressions
`the-canonical-base-populates-from-an-empty-store` and
`failed-base-construction-retries-without-caller-interruption` were green.
The namespace's one remaining red was the pre-existing
`a-canonical-database-is-the-production-source-population`: first line
`clock-free schema reconciliation is idempotent`; its second reconciliation
advanced `:max-tx` from 536870919 to 536870920. The same failure is recorded
before this lane in
`docs/prds/steward-platform/plan/unsettled.md:3072-3074` and is outside the
program-edge seam.

The remaining `seon.cluster.source-test` reds are the protected foreign
boundary in `test/seon/cluster/source_test.clj`. Their first lines/classes on
the integrated HEAD were:

- `stale-incremental-upsert-preserves-the-newer-publication`,
  `incremental-publication-does-not-change-an-existing-cluster`,
  `incremental-upsert-seals-one-activation-on-the-expected-commit`,
  `failed-and-stale-builds-preserve-the-published-head`,
  `existing-clusters-remain-on-their-chosen-source-commit`,
  `publication-advances-one-branch-and-retires-scratch`,
  `incremental-upsert-derives-scalar-safety-from-installed-schema`, and
  `an-activation-closure-with-empty-member-collections-seals`: published
  `:seon.program/unresolved-report` missing required `:seon.db/basis-t`.
- `incremental-first-party-publication-retains-complete-scalar-rows`:
  expected only `seon.id/id` but received all functions in the file; its
  component checks still queried removed `:seon.fn/ast` and observed missing
  namespace aliases and arities.
- `source-tombstone-provenance-does-not-prevent-live-removal`:
  `program-fn-row` received a Datahike connection where
  `seon.db/carried-projection` requires an immutable database value.
- `latest-test-evidence-survives-rebuilding-from-an-older-base`: string test
  symbols in `reach-digests` were refused. The former fabricated
  `:seon.fn/references` member refusal is gone.

`activation-refusal-bounds-the-operator-face` and
`publication-refuses-each-missing-activation-prerequisite-before-fork`, which
were red in the isolated pre-integration snapshot, were green after the
foreign fixture commit landed.

No cold gate was run; the orchestrator still owes the isolated cold and
platform proof.

## RESET NEEDED

**RESET NEEDED.** The write-side schema guard does not retract the 50 already
stored members on default, and this lane did not operate or refork default.
The orchestrator must republish/refork the development cluster after landing;
the complete post-fix source population contains zero fabricated members.

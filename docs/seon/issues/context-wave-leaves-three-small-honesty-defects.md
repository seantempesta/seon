---
type: issue
status: open
severity: cleanup
tags: [issue, render, cluster, context]
---

# Three small honesty defects left by the context wave

Three independent, individually small defects found by the wave audit.
Each is one edit at a named owner.

## The namespace renderer drops all but the first docstring line

`src/seon/render/ns.clj` renders a definition's summary through
`first-doc-line`, which takes exactly one line and discards the rest
with no marker, at every distance. The namespace docstring advertises
"first docstring lines" (plural). Either render the whole docstring at
sufficient distance, or emit a marker naming what was withheld; correct
the docstring either way. This survived the namespace renderer's
rewrite, so fix it at the current owner, not the landed one.

## `seed-cluster!` maintains two hand-written pictures of one fact

`src/seon/cluster.clj:786-808` builds `desired` (the transaction data)
and separately `expected-current` (the pulled projection it compares
against). Two hand-written representations of one intent that must be
kept in step by hand; drift means either a transaction on every boot or
a wrong convergence. `instruction-row-changes`
(`src/seon/cluster.clj:343-353`) already shows the right shape —
compare the desired row against a pull of that row's own keys. Derive
the comparison projection from `desired` instead of restating it.

## The index refusal reports every finding, not the blocking ones

`assert-clean-analysis!` (`src/seon/fn.clj:271-275`) filters
`blocking-findings` to `:level :error` and then puts the COMPLETE
`(::analyzer/findings analysis)` in the ex-data. An operator reading
"Static program analysis found blocking errors" sees a wall of
`:level :warning` shadowed-var and unused-binding findings and cannot
tell which one blocked. Report the blocking findings; keep the rest
under a separate key if they are wanted at all.

Related and worth stating in the same edit:
`populate-source!`'s docstring (`src/seon/cluster.clj`) says the
population transactions are "DERIVED, never hand-written" and lists
three of them; it also transacts hand-written instruction rows whose
text is read from a working-directory-relative `AGENTS.md`
(`Files/readString (Path/of "AGENTS.md" …)`), which is neither derived
nor on the classpath.

## Acceptance

All three fixed at their named owners, with the docstrings corrected in
the same commit. No new test is required for the ex-data change; the
docstring-line change carries one assertion that a multi-line docstring
either renders fully or is marked.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`

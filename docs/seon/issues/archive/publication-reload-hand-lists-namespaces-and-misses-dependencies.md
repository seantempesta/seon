---
type: issue
status: superseded
severity: friction
tags: [issue, runtime, operator, class/n3, wave/operator-launch-concurrency]
superseded-by: class-loaded-artifacts-lack-source-identity.md
---

# Publication's live-JVM reload hand-lists namespaces and misses dependencies

## Problem

`bin/seon init` against a live JVM reloads a FIXED list of namespaces
(schema, schema.edn, fn.analyzer, program, fn, db, sci.eval, cluster.*)
before publication. Any dependency of those namespaces that changed but
is not on the list stays stale, and the reload fails with a
No-such-var compile error naming the CALLER, not the stale dependency.

Two occurrences on 2026-08-08 alone:

1. `seon.sci.eval` reloaded against a stale `seon.env` →
   `No such var: env/scope` (cost a restart + full republication).
2. `seon.schema` reloaded against a stale `seon.schema.form` →
   `No such var: form/widen-component-children` (cost the same).

## Expected shape

The reload set is DERIVED, never hand-listed: from the changed files,
reload their transitive dependents in dependency order (the program
graph already records `:seon.fn/calls`; namespace-level requires are in
the analysis). Alternatively the honest minimum: refuse with "this
change requires a fresh JVM" when a changed namespace is outside the
reloadable set — loud, instead of a stale compile error two hops later.

## Acceptance

- A change to any namespace a listed one requires reloads cleanly or
  refuses loudly naming the stale namespace.
- One regression: a synthetic dependency edit through the reload path.

## Live observation — 2026-09-09

The publication-provenance lane observed stale `seon.schema` behavior in
default PID 92059: `render-contract-observation` rejected
`:my.note/agent` with `seon.note/render-notes-ai`'s declared
`[:or :my.note/notes :seon.render/unit]` input. Checked-in commit
`848d08a22` explicitly accepts that input. The same immutable probe returned
false before reloading `seon.schema` and true afterward; contracts were
rearmed with the cluster's projection. No source edit or default lifecycle
action was needed. Publication then passed contract projection. This is
evidence of stale loaded behavior, not proof of the precise current reload
selection cause. The landing note retains the subsequent publication proof:
`docs/prds/context-generation/research/publication-provenance-landing-2026-09-09.md`.

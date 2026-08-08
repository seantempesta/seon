---
type: issue
status: open
severity: friction
tags: [issue, runtime, operator]
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

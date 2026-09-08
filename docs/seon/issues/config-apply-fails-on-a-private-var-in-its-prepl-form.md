---
type: defect
status: open
severity: friction
tags: [operator, config, prepl]
---

# `bin/seon config apply` fails compiling its own prepl form

Observed 2026-09-07 late: `bin/seon --root tmp/juniper-context-live config
apply juniper-context /Users/sean/src/seon/config/default.edn` returned

```
✗ The cluster rejected the prepl operation.
Syntax error compiling at (1:413) … var: seon.web.search/organic-results is not public
```

The operator sends one large form to the cluster prepl to reconcile the
manifest; that form references a private var, so the command cannot work
against any live cluster. Also: a relative manifest path is resolved
against the operator ROOT, not the working directory, so
`config/default.edn` silently names a nonexistent file under the root.

## Why it matters

A new required config fact (two landed today: `:seon.config.eval.result/max-bytes`,
`:seon.config.error/max-evidence-bytes`) makes the development adoption refuse
at instrumentation; `config apply` is the non-destructive way to converge a
live cluster's config and it is broken, so the only path is a full reset.

## To do

Make the prepl form reference public entry points only (or move the
reconciliation into one public function the operator calls), resolve the
manifest path against the working directory, and add the operator drill:
apply the shipped manifest to a running cluster, then `init --dev` converges.

## 2026-09-08 — the class is wider than the symptom

`verify-p1-p6-and-backlog` (`research/verify-p1-p6-and-backlog-2026-09-08.md`)
read the operator: `config apply` splices the manifest's SYMBOL values into
a compiled form, so it is loud only because the referenced var happens to
be private — a public one would silently store the function object as a
config fact. The fix is the same as above: send data, resolve nothing on the
operator side.

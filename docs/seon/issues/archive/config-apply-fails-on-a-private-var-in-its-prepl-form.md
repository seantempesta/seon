---
type: defect
status: resolved
severity: friction
tags: [issue, operator, config]
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

## 2026-09-08 — implementation, verification incomplete

The operator now sends an absolute path string to the public `seon.config/apply!`
file arity and gets the connection through public `seon.operator/connection`.
Manifest symbols never enter compiled prepl source. Paths resolve from the
working directory, including the shared reader used by `start --config`.

A live intermediate probe also exposed the shipped-document versus sparse-overlay
boundary: the shipped `:seon.config/available-processors` decision and
initialization rows are not sparse-overlay inputs. The config owner now recognizes
the shipped document by value and reapplies defaults through its existing compiler;
other selected files retain sparse-overlay admission.

The recurring live operator drill now applies `config/default.edn`, reads back
the complete desired config row, and checks development adoption convergence.
It has NOT run: the subject gate exited 1 before launching tests during concurrent
runner-paths edits. Development adoption independently refused because source
changed during adoption. Keep this issue open until the required gates and live
apply/adopt proof pass. Evidence and unfinished work:
[config-apply landing](../../../prds/context-generation/research/config-apply-landing-2026-09-08.md).

## Resolution — 2026-09-08

The owner superseded the earlier stop rule. Commit `eb66a4b12` repairs the
operator/config boundary. The live `default` sequence succeeded: config apply
performed three operations, then development adoption converged. Independent
read-back matched all 77 desired row entries and confirmed the search projection
is still a symbol. Adopted and published commit IDs both equalled
`6aa04f2d-55d7-5cb0-8f09-cf9a2fd2e7c1`.

The real operator regression applies repository-relative `config/default.edn`,
reads back the desired row, and adopts the published source. Its first run
exposed syntax-quoted wildcard selectors becoming `clojure.core/*`; `6b54338fe`
uses Datahike's equivalent `:*` wildcard. The independent live probe had ordinary
quoted selectors and passed. Probe source and exact output are in the landing
note; probe commit `8fb5365ec`. The namespace gate additionally
exposed a fixture removing the original contract wrapper after `with-redefs`
restored it; `f1ebd4754` removes that extra cleanup and restores the original
schema registration. Final gate tallies are recorded in the landing note.

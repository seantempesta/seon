---
type: issue
status: open
severity: friction
tags: [issue, schema, operator]
---

# A new core predicate and the schema declaring it cannot be adopted in place

Observed 2026-09-07 by `storage-bound-repair-2` while landing the iterative
print-node contract (`5a26941c6`): the edit hook's
`bin/seon --root tmp/juniper-context-live init --dev juniper-context`
refused, and every later hook run repeated:

```text
ADVISORY — current-src publication failed.
seon.schema/canonical-definition violated its contract (invalid-output):
must be a parseable, EDN-readable Malli form
```

The change declares `[:fn … seon.print/node?]` in
`resources/seon/schemas/seon.print.edn` and defines `seon.print/node?` in
`src/seon/print.cljc`. Development adoption ANALYSES the tree before it
reloads namespaces, and the analysis compiles function specs under the
cluster's projection: the new schema resource is already declared, its
predicate Var is not yet loaded in that JVM, so
`seon.schema/malli-form?` answers false for every contract referencing
`:seon.print/node` and `canonical-definition` violates its own output
contract. A fresh JVM adopts the same commit without complaint.

The order is the defect, not the change: a JVM cannot adopt a schema whose
predicate its own reload has not installed yet.

## To do

Either load the changed namespaces before the analysis compiles contracts
(the reload is already part of adoption, just later), or make
`seon.schema/malli-form?` resolve a first-party predicate through
`requiring-resolve` when the form comes from the PACKAGED population rather
than from agent-authored input — the non-loading resolver exists to stop an
authored form loading arbitrary code, which is not this case. Until then, a
change that pairs a new predicate with its declaration needs the development
cluster stopped and started, which the edit hook cannot do.

## Observation 2026-09-09 — context blocks

After `105acca21`, default publication refused at schema population with
`Predicate seon.edit/valid-form-operation? has no admitted callable in the corpus projection.`
A supported JVM evaluation reloaded `seon.edit`, `seon.fs`, `seon.shell`,
then their `my.*` predicate registration call sites successfully. No default
process lifecycle action was taken. A fresh scratch fork had already
compiled and tested those same predicate declarations successfully.

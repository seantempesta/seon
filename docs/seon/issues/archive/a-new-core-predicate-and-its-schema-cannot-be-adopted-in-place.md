---
type: issue
status: resolved
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

## Resolved 2026-09-16 — compilation converges on the predicate's source

The class recurred on `default` (pid 41413) and wedged EVERY publication and
adoption for EVERY agent. `68e95b029` added `seon.search/handle?` and the
`[:fn … seon.search/handle?]` declaration in
`resources/seon/schemas/seon.search.edn:6` together, in one commit — which is
what the standing rule asks for — and it wedged anyway, because a single
publication is not atomic with respect to the JVM: the branch publication
compiles the resource, and only the ADOPTION that follows it reloads the
owner. So pairing the resource with its consumer is NOT sufficient for a
predicate symbol that is NEW to the process.

Measured, in the live JVM, before any repair:

```clojure
(some-> (find-ns 'seon.search) (ns-resolve 'handle?))      ⟹ nil
(some-> (find-ns 'seon.search) (ns-resolve 'ping-map-fn?)) ⟹ #'seon.search/ping-map-fn?
```

The sibling predicates are byte-for-byte the same shape (private `defn-`,
`register-core-predicate!` at load, `src/seon/search.clj:31-66`). Privacy is
irrelevant — `ns-resolve` returns a private Var. They resolve only because
they PREDATE this JVM's load of `seon.search`. Schema resources are read from
the classpath on every publication (2790 forms), so the declaration was live
the instant the file was written.

The second option in the "To do" above was taken, at the one resolver rather
than at a caller: `seon.schema/converged-predicate-var` resolves a predicate
against its own classpath source on the path that would otherwise refuse: an
ALREADY-LOADED namespace whose copy lacks a predicate its source declares is
RELOADED, which is the only thing that replays its registration forms. An
unloaded namespace is left alone and still refuses, so the existing guarantee
that an authored `[:fn …]` cannot make the process require code is intact —
it is the stale MIRROR, not an absent one, that wedges a process — a plain `require` on a loaded namespace is a
no-op, which is precisely why this state could not be left. Convergence runs
ONLY where the alternative is the refusal itself, so it can turn a wedge into
a success and can never change a working compile, and it is attempted at most
once per predicate per state of its source (the classpath resource URL and its
last-modified millis, derived from the classpath and never from the
namespace's name). A surviving refusal now names
`:seon.schema/predicate-namespace` as well as the predicate.

The class regression is
`seon.schema-test/a-declaration-compiles-against-a-predicate-its-loaded-owner-lacks`
over `test/seon/schema/predicate_owner_probe.clj`: it `ns-unmap`s a loaded
namespace's declared predicate — the live state exactly — proves a plain
`require` cannot leave it, and proves compilation converges and binds the Var.
Its second arm proves a genuinely undefined predicate still refuses, naming
both the predicate and the namespace.

Live proof: `seon.search` was reloaded through the supported MCP JVM
evaluation (the same repair as the 2026-09-09 observation above; no process
lifecycle action), and `bin/seon init --dev default` then ran the whole
publication it had been refusing, through `branch publication complete`.
Adoption stops afterwards on an unrelated incompatible schema change —
`:seon.context.capture/prompt` lost `:db/noHistory` at `79c106925`, which
Datahike does not apply to an installed attribute. That is RESET NEEDED for
the orchestrator, recorded in
[the lane note](../../prds/steward-platform/research/environment-carries-it-2026-09-16.md),
and is not this class.

The related hand-maintained reload boundary in
[live-publication-has-a-hand-maintained-predicate-owner-reload](live-publication-has-a-hand-maintained-predicate-owner-reload.md)
stays OPEN: this change derives readiness for a predicate the compile
actually needs, but the operator's own reload list is untouched.

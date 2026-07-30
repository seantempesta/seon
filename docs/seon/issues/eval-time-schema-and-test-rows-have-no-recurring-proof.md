---
type: issue
status: open
severity: blocker
tags: [issue, sci, program-graph, testing]
---

# Eval-time schema and test rows have no recurring proof

## Problem

The original missing runtime proof and the later exact-namespace blocker are
implemented. Runtime schema, function, and test declarations commit canonical
rows, materialize only from the successful terminal transaction report, and
reacquire after a real cluster reopen. `1135d8f39` replaces lossy require-edge
reconstruction with separate exact facts for actual requires, aliases, and
refers, installed through maintained SCI APIs (`2217449`, `98457e8`).

The schema lifecycle surface landed through `913f8177c`: schemas are global;
`schema/unregister!` stages one typed deletion in the isolated registration
delta; dependency/current-data checks run at the terminal transaction; and the
active projection derives from `db-after`. The independent landing-wave audit
found four remaining blockers. Its shared-attribute blocker is fixed: physical
Datahike changes now derive from a diff of the complete current and candidate
global projections, so replacing or removing an entity schema cannot retract a
leaf attribute whose global schema row survives. The real read-eval loop and
dynamic `ns-unmap` are repaired: reply splitting freezes exact source without
resolving future aliases, evaluation reads each source against the SCI state
left by the preceding settled form, and a resolved `ns-unmap` derives its
typed deletion from SCI's actual intern delta in an isolated fork. The build
test census is repaired: the
reader now emits an occurrence fact for every recognized function, schema, or
test declaration independently of durable row construction, and indexing
refuses any occurrence without a canonical identity.

The second independent review (`df346713a`) found that the commit-first
`ns-unmap` repair still projected only removed intern names. SCI also removes
refers and import mappings; an import-only removal therefore returned success
while its isolated fork was discarded. The maintained SCI fork now exposes
one exact namespace-state snapshot/install seam covering interned values and
resolver structure together. Runtime `ns-unmap` carries that isolated state on
the ordinary durable deletion/context request and installs it only from the
successful terminal transaction result. Source is no longer re-evaluated
after commit, and a refused transaction leaves the supplied run ctx unchanged.
The final audit then found that the exact import state was still transient:
fresh acquisition restored inherited `String`. Root `7713bb0bf` and maintained
SCI `1305a90` close that representation gap. Namespace facts now persist import
local symbols with an optional fully qualified target-class symbol; omission is
SCI's exact nil mask for a removed default import. Class objects never enter the
database, and SCI validates/resolves target availability only while installing
the binding facts.

## Evidence

Current recurring proofs establish:

- evaluated canonical schema values rather than reader syntax;
- schema row, derived Datahike attribute, and terminal receipt in one
  transaction;
- failed registration leaving no row, Datahike attribute, staged schema, or
  process-global projection mutation;
- exact live test execution, replacement, and deletion;
- incompatible A-B-A database projections without global registry bleed; and
- schema/function/test materialization after stopping and reopening a cluster.

The sequential REPL and deletion regressions cover both `(alias ...)` and a
computed `require` making reader aliases available to later sources, plus a
qualified `clojure.core/ns-unmap` with computed namespace and name arguments.
The deletion commits before the exact source mutates the run context, is absent
from a freshly acquired context, and remains absent after a real process
restart. The combined reply/reader/program/eval/turn/restart focus passes 78
tests / 506 assertions / 0 failures / 0 errors; the maintained SCI namespace
suite passes 38 tests / 153 assertions / 0 failures / 0 errors.

The final combined independent focused gate after the owner's superseding
cross-namespace `ns-unmap` ruling was 59 tests / 462 assertions / 0 failures /
0 errors.

The two former shortest falsifiers are now recurring successes: runtime renamed
refers retain their target Var identity through restart, and a build source
that renames `clojure.test/deftest` to `dt` produces the exact test row.
Multiple aliases to one target remain distinct, `:as-alias` does not become a
load dependency, and a plain require remains visible for acquisition ordering.

Git archaeology found the already-implemented exact representation in
`57761ddb4` (`feat(program): persist canonical direct edges`). Its JVM SCI
projection and CLJS analyzer projection both represented aliases as
`local -> target namespace` and refers as `local -> qualified target`.
Compressing those effective bindings back into require syntax reintroduced
the information loss.

The full source audit, vendored SCI anchors, probes, and recommendation are in
`docs/prds/sci-execution-runtime/research/runtime-registration-adversarial-audit-2026-07-30.md`.
Schema removal/history evidence is in
`docs/prds/sci-execution-runtime/research/schema-removal-history-probe-2026-07-30.md`:
after current data is retracted, old temporal datoms and the historical schema
row rebuild validation at one `as-of` basis; Datahike's schema map itself does
not time-travel, and `:seon.db/no-history? true` explicitly discards old values.

The adversarial audit's composite-schema falsifier is now a recurring class
test: it replaces an entity schema with a smaller map, removes the entity
schema, proves both global leaf rows and both Datahike attributes survive, and
writes through them. `seon.schema-usage-guard-test` passes 11 tests / 62
assertions / 0 failures / 0 errors.

The adversarial audit's test-drop falsifier is now a recurring refusal:
`(in-ns (symbol "opaque.test"))` followed by a qualified `deftest` cannot
silently disappear. A tools.reader-based per-file census independently counts
function, schema, and test occurrences with multiplicity, and also compares
exact function and test identities. `seon.fn-test` plus
`seon.sci.reader-test` passes 24 tests / 212 assertions / 0 failures / 0
errors.

The re-audit's import-only falsifier is now recurring on both rails. Removing
the inherited `String` import is visible to the next form only after the
namespace context transaction commits; an injected refusal retains `String`
in the original run ctx. The maintained SCI namespace suite now passes 39
tests / 159 assertions / 0 failures / 0 errors on both Clojure 1.10.3 and
1.11.1, and its state round-trip also covers an own intern, alias, refer,
require, and the nil import mask in one snapshot.

The final audit's fresh-acquisition falsifier is also recurring. The terminal
namespace row contains a component with `:seon.ns.import/local 'String` and no
target attribute, a fresh `acquire!` keeps `String` unresolved, and the live
cluster stop/reopen test proves the same mask survives a new process instance.
An explicit import addition persists only its local and fully qualified class
symbols and reacquires through the same SCI operation. The focused root
program/eval/turn/restart gate passed 57 tests / 330 assertions before the
addition-specific regression; the maintained SCI suite passes 40 tests / 160
assertions / 0 failures / 0 errors on Clojure 1.10.3 and 1.11.1.

The final audit proved the temporary finite resolver-operation refusal was
still a silent hand list: `eval` and `apply` routed around it. The JVM build now
has the missing isolated full-source compiler owner. A child JVM reads and
evaluates each top-level form sequentially, records its exact file/line source
span, then takes one final snapshot of actual namespace bindings, Vars, and the
evaluated global schema registry. Final absence therefore handles unmap and
unregister without a second delta mechanism, and definitions created through
`eval` are attributed through their compiler metadata even when they enter a
third namespace and return. All side effects die with that process. A
content-keyed process-local cache reuses only an identical source population
in the same JVM. The inspector launches through the existing `:test` alias
because `test/` is a declared source root and its namespaces require the
test-only flow monitor. Its cache key covers the resolved classpath, every
requested source, every repo-local classpath file (including schema resources
and vendored sources), and every repo-local dependency manifest. External
immutable dependencies key by resolved path. Resolved classpaths are reused
only while those manifests stay byte-identical. Namespace rows preserve exact
empty alias/refer sets and explicit nil-mask components when code unmaps a
default JVM import.

The production/default-parent proof indexes the complete source roots from
`clojure -M:dev`: 116 namespace rows, 1,330 function rows, 552 schema rows, and
608 test rows. Cold evaluation took 16,181 ms; the exact second call returned
the same rows in 114 ms. The cache-invalidation regression independently
changes a requested source, schema resource, vendored/local classpath source,
and dependency manifest, and gets a different key after each change.

The recurring index proof no longer pays one fresh JVM for each immutable
fixture. Its canonical-row, exact-binding, evaluated-REPL, and database
reconciliation assertions share one isolated source population and one exact
row result. The V1→V2→V3 lifecycle remains a real file mutation followed by a
real isolated inspection at every version, so cache invalidation, replacement,
and removal are still end-to-end claims. Changed-test generation 1524 passed 8
tests / 62 assertions / 0 failures / 0 errors in 71 seconds, down from the
observed 4m29 development loop.

Evaluated Var metadata exposed one final serialization defect: Clojure resolves
raw Malli predicate symbols to callable roots, whose default printer emits
unreadable `#object` tags. `seon.schema/canonical-definition` is now the total
inverse of the existing predicate binder. It maps named roots back to qualified
symbols, wraps raw predicate schemas as `[:fn qualified-symbol]`, preserves an
already explicit `[:fn ...]`, and refuses anonymous roots. The direct and
indirect contracted-defn regression (including an already-existing third
namespace) and the former acquisition `#object` falsifier pass independently
in 21.46 s and 24.64 s wall time respectively.

This directly carries forward the mechanisms that survived earlier platforms:
`87ac3f9c6` made analyzer state authoritative, `d33b29cf9` indexed evaluated
registry values, and `56ed96dd9` repaired computed cold-boot schema parity.
The discarded static scanner family is `0c22f8363` / `d7cd70bdd`.

## Owner

`seon.sci.reader` / `seon.sci.eval` / the maintained SCI fork's namespace
binding seam. `seon.program` and `seon.cluster.run` already own the shared row
and terminal transaction contracts and should not gain another registry.

## Acceptance

One shared pure owner defines canonical identities, owned attributes, row
construction, exact replacement, and typed function/test deletion. Build and
runtime call it after their explicit producer admission: build indexes every
function; runtime publishes only fully contracted functions. No transaction
owner restates that contract.

The recurring proof matrix establishes:

- build/runtime canonical row parity over their shared admitted domain;
- evaluated schema values, with failed registration leaving neither a row nor
  staged state, and with the same terminal transaction installing a derived
  Datahike attribute declaration;
- exact function/schema/test replacement and stale source reconciliation;
- `ns-unmap` retracting matching function and test facts with ordinary SCI
  REPL semantics, including a cross-namespace target and an import-only
  mutation, with a refused commit leaving the original ctx unchanged;
- exact resolved test operation identity, so unrelated `foo/deftest` never
  becomes a `:seon.test` row;
- installation from the successful terminal transaction report's exact
  `db-after`, never from receipts or pre-commit declaration mutation;
- current function, schema, and test materialization after cluster reopen;
- two incompatible cluster projections alternating in one JVM without global
  registry bleed;
- renamed `deftest` and renamed `seon.schema/register!` at build time;
- renamed refer, two aliases to one target, and `:as-alias` through a runtime
  terminal transaction and fresh acquisition; and
- an agent-authored target namespace proving acquisition orders target Vars
  before installing referring functions/tests.

The unifying repair is namespace-owned effective binding facts: aliases carry
local + target namespace, imports carry local + optional fully qualified class
symbol, refers carry local + target namespace + target name, and bare
dependency targets remain only where loading/order needs them. A single narrow
public operation in Seon's maintained SCI fork installs those facts into a
context. Do not add accumulated require-source replay or a second registry.

Sequential reading does not add a second parser or persist resolved future
forms. The plan retains exact source spans; its evaluator is the semantic
reader, and `:seon.sci.eval/ending-ns` is a transient fold value distinct from
the durable receipt's starting `:seon.cluster.eval/ns`. This preserves direct
parse/eval divergence queries while giving the next form the real REPL
namespace.

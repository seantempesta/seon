---
type: landing
lane: delete-shape-mirror-opus
created: 2026-09-25
status: stopped at the condition. The data proof holds; atomic conversion is outside this lane's files. Three priced options follow.
source-pin: 9481086e0 (fn.clj and test/runner.clj dirty from other lanes)
---

# C4 schema-shape mirror: condition proof and stop

What exists: `seon.program` encodes every argument, return and guard contract, and every `:seon.schema/shape`,
into recursive content-addressed rows (`seon.fn.schema-shape/shape-row`). `seon.call-preparation` joins those rows
in Datalog, `seon.fn` checks them for drift and fingerprint collisions, and `seon.instrument` digests them into error
identity. The approach would be to read the projection's `:seon.schema.projection/function-contracts` through Malli
(`m/schema`, `m/type`, `m/children`, `m/form`) and delete the row codec.

## Broken or blocking, first

1. **The atomic conversion needs files this lane does not own.** Most of the mirror's readers are Datalog queries in
   `src/seon/call_preparation.clj`: 34 shape references across about 15 queries, which fall under the audit's C3
   budget. The rest are in `src/seon/fn.clj` (another lane), the schema resources `seon.schema.shape*.edn`,
   `seon.schema.map-entry.edn`, `seon.fn.binding.entry.edn` and `seon.db.edn`, `src/seon/error.clj`, and 8 test
   files. Producers and consumers must convert together, so deleting the rows from `program.cljc` alone would leave
   those queries silently empty. **No production edit was made.**
2. **The stored mirror does not see named-reference entries.** For `seon.cluster.wake/route!`, whose argument is
   `:seon.cluster.wake/route-request`, the map-entry query returns `()`. The named shape row has no entries (see
   `encode-form`). A Malli `m/deref-all` walk returns all 8 entries, including `[:seon.cluster.wake/connection
   :seon.db/connection]`. The docstring of `call_preparation.clj`'s `map-entry-query` cites exactly this function,
   and `named-entry-facts` already goes to the compiled registry for named arguments. The mirror is therefore already
   a partial second path. That code is in C3's file, so no issue was filed from this lane. Root should decide
   whether this goes to the C3 owner.
3. **`m/form` of a compiled contract is not EDN when the contract uses predicates.** It carries function objects,
   and `fingerprint` throws "Schema projection fingerprint contains non-EDN data." Any replacement digest must go
   through `seon.schema/canonical-definition` (schema.clj). That function turns the compiled form back into the
   authored form, which the probe confirmed with `canon-is-authored? true`.

## Proof on the real registry (default, JVM mode, read-only; scratch ns removed)

Scale: 8,115 shape rows, 1,155 entries, 7,536 children, 4,932 `:seon.fn/sym`, 2,169 function contracts, 4 supplied
defaults (`:seon.agent/id :seon.db/database-value :my.program/context :seon.db/connection`).

| Consumer (value it reads) | Today (mirror) | Replacement (Malli over projection) | Equal |
|---|---|---|---|
| `call_preparation` `prepared-entry-query`: (sym, entry key, default) | 45 triples, 16.1 ms for both queries | 45 triples. `:catn` children read their last element. 14–16 ms walk plus 15 ms to compile all 2,169 contracts, once per projection | yes, set-equal |
| `call_preparation` `prepared-positional-query`: syms | 577 | 577 | yes, set-equal |
| `map-entry-query` for a named argument (`route!`) | `()` | 8 entries through `m/deref-all` | no. The mirror misses them (see 2 above) |
| Inline map argument entries (`my.agent/branch`, `my.background/await`, `my.background/poll`, `my.issue/add!`) | key plus `pr-str` of the value form | key plus native value form | yes, apart from the string versus native value |
| `instrument` `:seon.error/expected-shape` | `d3e91ac7…`, 1.29 ms (includes the reference definition fingerprints) | `e38ca5ff…`, 0.44 ms (digest of the canonical form) | no. The identity changes, and a changed referenced definition no longer changes it |
| `compiled-in` (argument validators) | EAVT walk of the rows, then compile | compile the arity's child from `function-contracts` | not probed separately; same Malli compile |
| `fn` `drifted-schema-keys` / `shape-dependent-rows` / `assert-consistent!` | guard stored rows | would disappear with the rows | nothing left to guard |

The probe forms were: `(seon.db/q db '[...])` over the shape attributes, and `(m/schema (seon.schema/compilable-form
form preds) (:seon.schema.projection/compile-options p))` for each `function-contracts` entry. Children of `:=>`
arities' `:cat`/`:catn` inputs were taken with `(if (m/schema? c) c (last c))`, then `m/children` of `:map`.
Everything ran in scratch ns `lane-shape-mirror-probe`, which has been removed.

**Verdict.** The condition holds for the data: Malli plus the projection produce every value the call-preparation
consumers read, and one they currently miss. It fails for this lane's ownership and 60-line ceiling: the atomic
producer/consumer cut spans C3's `call_preparation.clj`, `fn.clj`, 5 schema resources, `error.clj`'s identity
digest and 8 test files.

## Three priced options

A. **Simplest viable constraint: keep the mirror and fence it.** Record that `schema_shape` is the only writer and
   `call_preparation` its only reader, and do no conversion. Guarantee: nothing changes. Cost: 0 lines, about 0 h.
   Sacrifice: 385 lines stay, and the named-reference blind spot stays.

B. **Recommended: one atomic cross-owner lane holding `call_preparation.clj`, `fn.clj`, `program.cljc`,
   `instrument.clj` and the shape schema resources.**
   - Call preparation reads a memoized function of the projection value, `(argument-entries projection)`, keyed
     by the projection's own identity. It has been proven set-equal above at about 30 ms per projection, once.
     Argument validators come from the compiled `function-contracts`.
   - `program.cljc` stops writing `:seon.fn.argument/schema`, `rest-*-schema`, `:seon.fn.arity/input|return|
     guard-schema` and `:seon.schema/shape`.
   - `typed-key-facts` and `key-kind` (40 lines) move into `program.cljc`, whose only caller is at :791.
   - `instrument` digests `seon.schema/canonical-definition`.
   - The rest of `schema_shape.clj` and the `seon.schema.shape{,.entry,.child}.edn` resources are deleted.

   Guarantee: one schema codec (Malli), and named arguments are covered. Cost: about 385 lines from schema_shape,
   plus fn.clj's drift/dependents guards and call_preparation's shape queries, against about 80–120 added lines.
   About half a day, plus a root adoption because the schema change is incremental on a branch.
   Sacrifice: contract shapes stop being Datalog-queryable, so an agent answers "who takes key X" by calling a
   function rather than querying. The expected-shape error identity also changes once.

C. **Flatten, don't delete.** Keep one row per argument that stores the authored form as a native EDN value, plus
   indexed top-level entry keys through `typed-key-facts`. Delete the recursive encoder and decoder (`encode-form`,
   `read-form`, `database-form`, `row-signature`, `assert-consistent!`, `reference-fingerprints`, about 250
   lines). Guarantee: top-level Datalog queryability stays. Cost: about 250 deleted and about 60 added, in the same
   cross-owner file set as B, about half a day. Sacrifice: it is still a second representation, it still misses
   named-reference entries unless they are expanded at write time, and it is still under the stamp law's scrutiny.

## Callers in fn.clj (listed for root; not edited)

Line numbers are at the current dirty tree. The audit's :2902/:2940/:3156 have moved.
- `fn.clj:3007` `(schema-shape/prepare-forms projection)`, and `:3251` in the partial path. Deleted under B and C.
- `fn.clj:3045` `(schema-shape/assert-consistent! (filter :seon.schema.shape/fingerprint …))`. Deleted under B and C.
- `fn.clj:3190-3227` `drifted-schema-keys` and `shape-dependent-rows`: the `:seon.schema/shape` fingerprint join and
  the `::reference-fingerprints` meta at :3261. Deleted under B. Under C they become form equality.
- `fn.clj:3100-3108` reads `:seon.fn.arity/input-schema` and `:return-schema` presence. Under B this becomes the
  projection's `function-contracts` membership.

## Changed paths, lines, commits

Only this note. Net source lines: 0. There is no commit of production code. RESET NEEDED: no.

## Root runs (only if option B or C is ruled)

- `bin/test-check default --ns seon.call-preparation-test`
- `bin/test-check default --ns seon.program-test`
- `bin/test-check default --ns seon.fn.publication-test`
- `bin/test-check default --ns seon.fn.shape-dependents-test`
- `bin/test-check default --ns seon.schema.datahike-test`
- `bin/test-check default --ns seon.schema.predicate-metadata-refusal-test`
- `bin/test-check default --ns seon.instrument-test`

## Verification limits

The probes were read-only at one database value. `compiled-in` parity and the supplier-coherence queries
(`supplier-shape-query`, `supplier-return-arms-query`) were not probed separately. No tests were run, per the LOAD
RULE.

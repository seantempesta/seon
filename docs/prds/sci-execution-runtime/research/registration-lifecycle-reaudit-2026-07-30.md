---
type: research
status: complete
tags: [sci, program-graph, schema, datahike, audit]
---

# Registration lifecycle adversarial re-audit — 2026-07-30

## Verdict

The correction wave closes the prior physical-schema blocker and the main
runtime read/eval and durable-deletion falsifiers. It does **not** yet establish
perfect registration at both index time and runtime. Three blockers remain:

1. build indexing still silently misidentifies or drops declarations after
   evaluation-dependent namespace operations;
2. build and runtime construct different canonical schema rows for an admitted
   computed schema form; and
3. commit-first `ns-unmap` discards SCI import removal because that resolver
   delta is represented by neither program identities nor namespace facts.

The owner's schema lifecycle ruling itself is now implemented correctly on
the ordinary path: any current data blocks change or removal, removing current
data permits the operation, ordinary historical data plus the historical
global schema row reconstructs validation at an old basis, and `noHistory`
explicitly does not promise that data.

This audit reviewed the tree at root `0aaa50860`, maintained SCI
`1ed03a6948095ea9bf39f43ec0309d3b4681b3a3`, and maintained Datahike
`b73550bfa37920bae851b4f86904876f2a17a21c`. It also read the prior audit at
`8f17c0ec9`, its issue acceptance, and the deleted implementation at
`e176eb469` before judging the replacements.

## What is genuinely strong

### Complete global physical schema projection closes the old ownership bug

`schema-attribute-change-tx` now derives Datahike declarations from the
complete current and complete candidate global Malli projections, keys both by
`:db/ident`, and changes only unequal declarations
(`src/seon/cluster/run.cljc:593-624`). It no longer infers physical ownership
from only the changed composite form. The recurring class test replaces an
entity schema with a smaller map, then removes it, proves both independently
registered leaf rows and physical attributes survive, and successfully writes
through them (`test/seon/schema_usage_guard_test.clj:277-319`). This directly
falsifies prior blocker 1.

Schemas remain global in the canonical row: only `:seon.schema/key` and
`:seon.schema/form` are owned, with no namespace reference
(`src/seon/program.cljc:22-26`). The physical diff derives from the global
projection, not from a namespace or a hand-maintained ownership table.

### Runtime replies now have a real sequential semantic read

Plan freezing retains exact source spans and asks the splitting pass to defer
unknown alias auto-resolution (`src/seon/cluster/reply.cljc:115-144`). The
run then folds one shared SCI ctx, passes the preceding evaluation's ending
namespace to the next source, and calls the semantic reader only when that
source is about to execute (`src/seon/cluster/loop.cljc:863-925,1079-1083`;
`src/seon/sci/eval.clj:368-399,742-759`).

The prior two runtime falsifiers now succeed through the complete turn path:
an evaluated `(alias ...)` makes `::str/after-alias` readable, and a computed
`require` makes `::sets/after-dynamic-require` readable
(`test/seon/cluster/turn_test.clj:511-537`). Independent span probes also
retained exact later source for a computed alias, computed `in-ns`, syntax
quote, and `#::alias{}`. The splitter may parse a provisional form for
classification, but that form is not the evaluator's semantic input.

This is materially simpler than adding more cases to the old static
`next-reading-context`: runtime meaning comes from the SCI ctx that the
preceding form actually changed.

### Qualified computed cross-namespace deletion is commit-first and durable

The reader recognizes the resolved operation `clojure.core/ns-unmap` while
leaving its arguments unevaluated (`src/seon/sci/reader.cljc:375-385`). The
evaluator runs it in an isolated SCI fork, compares SCI's own before/after
intern sets, emits typed function and test identities, and installs the exact
source into the real run ctx only after the terminal transaction succeeds
(`src/seon/sci/eval.clj:345-359,742-751,819-838,512-531`). SCI's public
snapshot excludes aliases, imports, refers, required namespaces, and other
resolver structure from actual interns (`reference-code/sci/src/sci/core.cljc:
701-718`).

The recurring turn test computes both the target namespace and name, proves
the row absent, and proves fresh acquisition cannot resurrect the Var
(`test/seon/cluster/turn_test.clj:539-564`). The restart test then stops and
reopens a real cluster and proves the same deletion remains absent
(`test/seon/cluster/program_restart_test.clj:125-138,151-205`). This closes the
function/test case from prior blocker 3.

### The schema data and history rule is exact

The terminal transaction computes reverse-transitive dependent schemas,
derives all affected database attributes, and scans current AEVT before
allowing replacement or removal (`src/seon/cluster/run.cljc:561-591,
626-718`). The maintained Datahike fork independently performs the same
current-data refusal for **every** attribute when `:db/ident` is removed, not
only indexed attributes (`reference-code/datahike/src/datahike/db/
transaction.cljc:133-142,289-305`).

The recurring Seon proof establishes all four parts of the owner's ruling:

- current direct, transitive, and entity-child data refuses a schema change or
  removal atomically (`test/seon/schema_usage_guard_test.clj:99-185,222-275`);
- after retracting current data, replacement and removal succeed
  (`test/seon/schema_usage_guard_test.clj:187-220,336-359`);
- ordinary `as-of` data and the historical `:seon.schema` row survive, and a
  projection rebuilt at that basis validates the old value
  (`test/seon/schema_usage_guard_test.clj:336-375`); and
- `:seon.db/no-history? true` deliberately makes the old value unavailable
  (`test/seon/schema_usage_guard_test.clj:377-397`).

Datahike's historical database value still delegates physical schema lookup to
the current origin. The durable historical Seon row, not a time-travelling
Datahike schema map, is what reconstructs the old Malli validator. This is an
honest simulation boundary rather than a claim that removed physical schema
metadata travels back in time.

## Blocker 1 — build indexing still guesses through evaluated reader state

Runtime sequential reading was repaired, but build indexing still reads the
whole file statically. `next-reading-context` can derive only literal `ns`,
literal `in-ns`, and literal `require`; an opaque `in-ns` merely clears
namespace attribution (`src/seon/sci/reader.cljc:442-494`). That loudness works
for functions and tests because their occurrence identity requires an
attributed namespace. It does not work for a global schema whose auto-resolved
keyword was already resolved under stale reader state.

The direct falsifier was:

```clojure
(ns audit.a)
(in-ns (symbol "audit.b"))
(seon.schema/register! ::x :int)
```

The production reader and `program/declaration-row` returned:

```clojure
{:seon.schema/key :audit.a/x, :seon.schema/form ":int"}
```

Ordinary Clojure sequential load semantics returned `:audit.b/x` for the same
third-form `::x`. Clearing the event's `:seon.sci.reader/ns` did not clear or
refuse the stale auto-resolved schema identity. This is a wrong durable global
identity, not just missing source attribution.

Two even shorter silent-drop variants remain:

```clojure
(ns audit.a)
(alias 'schema 'seon.schema)
(schema/register! ::x :int)
```

and

```clojure
(ns audit.a)
(require (if true '[clojure.test :refer [deftest]] '[clojure.set]))
(deftest lost)
```

For both, all three production reader events carried neither a declaration
family nor a declaration identity for the final form. `seon.fn/rows` therefore
has nothing for `unadmitted-declarations` to refuse
(`src/seon/fn.clj:30-61,129-165`).

The independent census cannot catch this class. Its state machine handles
only `ns`, quoted `in-ns`, and `do`; it does not advance standalone `require`
or `alias` bindings (`test/seon/fn_test.clj:189-236`). For schemas it compares
only family counts and deliberately omits identity
(`test/seon/fn_test.clj:160-187,238-305`). Thus a wrong schema identity can
keep counts equal, while a declaration hidden behind the same unsupported
resolver mutation is absent from both production and oracle.

The repair should not add more guesses to `next-reading-context`. Build-time
and eval-time admission are different domains. Build indexing must either
derive effective compiler namespace state from an actual sequential analyzer,
or refuse every top-level resolver mutation it cannot prove before accepting
later declarations. The independent census must compare schema identities too
and must not share the same resolver blind spots.

## Blocker 2 — computed schema rows are not canonical across producers

`seon.program` is one row-shape owner, but its two callers supply different
schema values. Build indexing stores the reader syntax via `pr-str` of the
third form (`src/seon/sci/reader.cljc:355-364`). Runtime evaluation correctly
stores the isolated registration delta's evaluated Malli value
(`src/seon/sci/eval.clj:769-806`).

The exact same admitted source produced different rows:

```clojure
(seon.schema/register! :sample/computed (vector :int))
```

```clojure
;; build
{:seon.schema/key :sample/computed,
 :seon.schema/form "(vector :int)"}

;; runtime
{:seon.schema/key :sample/computed,
 :seon.schema/form "[:int]"}
```

The current parity test covers only a literal schema form
(`test/seon/program_test.clj:26-68`), so it cannot see this mismatch. A shared
canonicalizer cannot make syntax equal to its evaluated value; producer
admission has to settle what build-time schema forms mean. Either build
indexing obtains the already-evaluated registered value, or its explicit
admission policy refuses forms whose canonical value is not statically data.
Until then, “exact index-time/runtime canonical parity” is false.

## Blocker 3 — `ns-unmap` loses SCI import removal

The isolated-intern delta is sufficient for durable function/test deletion,
and a removed refer becomes a changed namespace row. An import is neither.
SCI implements `(ns-unmap ns sym)` over three distinct resolver locations:
`:refers`, ordinary namespace entries, and imports
(`reference-code/sci/src/sci/impl/namespaces.cljc:567-588`). Its own maintained
suite explicitly treats removing `Object`/`String` imports as real
`ns-unmap` semantics
(`reference-code/sci/test/sci/namespaces_test.cljc:247-272`).

Seon's `namespace-interns` intentionally excludes imports, and persisted
namespace binding rows contain only requires, aliases, and refers. Therefore
an import-only removal yields no removed program identity and no namespace
context row. The evaluator discards the isolated fork and returns success.

The production evaluator probe was:

```clojure
;; same supplied run ctx throughout
(resolve 'String) ;=> a Var/class mapping exists
(clojure.core/ns-unmap (find-ns 'user) (symbol "String"))
;; evaluation => {:seon.sci.admit/value nil}, no program row, no error
(resolve 'String) ;=> mapping still exists
```

This is a successful receipt for an operation that did not affect the next
form, violating the real-REPL ruling. The commit-first representation must
carry the complete SCI namespace delta needed to install the operation after
the transaction, including the valid no-program-identity case. Reclassifying
imports as program functions would be wrong; they are resolver state, not
`:seon.fn` facts.

## Verification

Independent gates run against the reviewed tree:

- root registration/schema/runtime selection excluding `seon.cluster.run-test`:
  **95 tests / 620 assertions / 0 failures / 0 errors**;
- `seon.cluster.run-test`: **11 / 67 / 0 / 0**;
- maintained SCI `sci.namespaces-test` on Clojure 1.10.3 and 1.11.1:
  **38 / 153 / 0 / 0 on each version**; and
- maintained Datahike schema focus across `specs`, `clj-hht`, and `clj-pss`:
  **51 / 372 / 0**.

These results strongly support the corrected physical schema, ordinary
history, and main runtime namespace behavior. They do not cover the three
counterexamples above.

## Acceptance boundary

The wave can close after recurring proofs establish all of the following:

- build indexing refuses or correctly resolves every evaluation-dependent
  namespace transition before a function, schema, or test declaration, and an
  independent census compares exact identities for all three families;
- one computed schema source in the shared admitted domain produces byte-equal
  canonical build and runtime rows, or build admission explicitly and loudly
  excludes that source class; and
- an import-only `ns-unmap` changes the next form's supplied run ctx after its
  terminal commit, while the existing computed cross-namespace function/test
  deletion and restart proofs stay green.

Then rerun the complete root registration matrix, both maintained SCI Clojure
versions, the maintained Datahike schema focus, and the reset-boundary cluster
reopen proof.

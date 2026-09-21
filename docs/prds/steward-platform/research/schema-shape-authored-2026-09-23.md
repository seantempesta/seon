---
type: research
status: blocked
created: 2026-09-23
tags: [schema-shape, class/stored-derived, wave/publication-velocity]
---

# Authored schema shapes — reader evidence and baseline

Read end to end: the owning issue
`docs/seon/issues/the-index-cache-retains-4gb-of-expanded-schema-shape-forms.md`,
`src/seon/fn/schema_shape.clj`, and
`resources/seon/schemas/seon.schema.shape.edn`; also read AGENTS.md §2.2
and “SECONDS, NOT MINUTES” and the requested reader ranges.

## Initial protected-hunk finding (superseded by owner ruling)

`src/seon/instrument.clj:714–718` computes an explanation's expected-shape
fingerprint from only `(:seon.schema.shape/form (normalized-form ...))`.
`src/seon/instrument.clj:726–730` repeats that operation for the complete
contract's expected-shape identity. Both discard everything except the
normalized form before calling the one-argument fingerprint function.

The current fingerprint implementation (`src/seon/fn/schema_shape.clj:144–150`)
hashes only its argument's canonical data string. With authored references
preserved, changing a referenced definition leaves that argument unchanged.
For example, authored `:example/value` remains `:example/value` whether its
registry definition is `:string` or `:int`. These diagnostic identities must
use the same dependency-aware fingerprint operation as stored shape rows.
The two protected call sites must therefore retain and supply the reference
identity inputs instead of selecting and hashing only the authored form.
No ambient registry or additional cache is an acceptable workaround.

The requested call-preparation reader is present at
`src/seon/call_preparation.clj:588–613`: its query selects only the form
string, then constructs a form-only row for `row-form`. It will need the
owned-path change to reconstruct structural children from the database.

## Verification boundary

Stopped at the assignment's explicit protected-hunk boundary. No production
files changed; neither protected file was edited, and `default` was not
operated. No scratch root, JVM, tests, or measurements were started. The
issue's 65 MB total and 1,395,278-character maximum are historical evidence,
not measurements from this assignment. Before/after scratch-root numbers,
size regressions, HEAD-load proof, and implementation commit remain owed.
This note is not a landed implementation or a performance claim.

RESET NEEDED when the new stored shape representation lands. No reset was
performed. Resume requires the two protected instrumentation hunks to be
released or changed by their owning lane in coordination with this slice.

## Owner ruling and resumed investigation

The owner released the clean instrumentation/function files and authorized
the two instrumentation hunks if needed, with an immediate pre-edit status
check. Dependency-aware fingerprinting belongs in `seon.fn.schema-shape`;
callers must not implement it. The initial note landed as `7b481dc72` after
the required namespace-load command exited zero. The prior protection is
not a current blocker.

### Dependency ledger

- Malli `schema` creates a pointer for registry references, while `form`
  preserves its authored spelling:
  `reference-code/malli/src/malli/core.cljc:2550–2579`.
- Malli `deref-all` resolves top-level references; `deref-recursive`
  resolves schema pointers throughout the compiled schema but explicitly
  does not traverse `:ref` recursion:
  `reference-code/malli/src/malli/core.cljc:2818–2846`.
- Malli retains validators on the compiled schema itself:
  `reference-code/malli/src/malli/core.cljc:2625–2632`. An additional
  plain-validator cache would duplicate that mechanism.
- Seon's database carries its immutable projection in metadata:
  `src/seon/db.clj:249–269`; `carried-projection` reads it at `:1219`.
- Existing reference discovery uses Malli's walker and reference protocol,
  stopping at canonical references: `src/seon/program.cljc:552–570`.
- Datahike's node cache is count-bounded:
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:463–466`.

### Additional reader and semantic decision

The complete production reader search found an additional direct structural
reader: `src/seon/issue/detect.clj:14–23` pulls entries from
`:seon.schema/shape` without resolving a reference. The schema population
currently constructs that shape by compiling the schema KEY
(`src/seon/program.cljc:839–845`). Preserving that authored keyword removes
the expanded entry rows the detector currently expects. Leaving this reader
unchanged would make its declared-key result empty. An ownership extension
for this reader and its regression has been requested.

Call preparation also uses stored shape equality as semantic equivalence.
`resources/seon/schemas/seon.call-preparation.edn:23–27` explicitly promises
that an equal inline declaration and named declaration converge on the same
fingerprint. `src/seon/call_preparation.clj:321–349` and `:573–585` implement
the matching through fingerprint joins. Authored `:example/value` and
inline `:int` necessarily have different authored identities even when the
registry defines `:example/value` as `:int`.

The three choices submitted under the owner design gate are:

1. **Require named references for supplied defaults (recommended smallest
   constraint).** Match authored identity; retain no second comparison.
   Cost: update the matching contract and regressions. Give up supplying a
   default to an equivalent inline schema or differently named alias.
2. **Preserve inline equivalence.** Derive comparison through the compiled
   registry, carried with the database's existing derivation, without a new
   stored identity or cache. Cost: convert the structural/matching readers
   and extend the regression scope. Give up the direct stored-fingerprint
   join as the complete matching proof.
3. **Retain expanded identity semantics.** Continue treating equivalent
   authored spellings as identical while reducing stored strings. Cost:
   revise the assignment's fingerprint rule. Give up authored identity.

Production edits await this genuine behavior decision, not foreign breakage
or the superseded protected-file claim.

### Scratch baseline progress

Only `tmp/schema-shape-root` was operated. Initial `start schema-shape`
refused because no `current-src` branch existed; MCP status reported its
advertisement stale and health unknown. The subsequent root-scoped `init`
performed a fresh canonical publication. Its progress measured 107,705
population items in 121,388 ms between program population compilation and
population completion. That is baseline publication work, not attribution
of all elapsed time to shape strings. No `default` operations occurred.

### Measured baseline and live semantic probe

Fresh scratch publication: commit
`6ab08cc5-9928-5ed8-bae0-56b27f5582cd`, program digest
`3b0958695672ad886f1dd726b303125766d209e62bdd14ffc43abeb36179f571`.
The operator's complete initialization took 243,342 ms. The subsequent
`start schema-shape` completed; this proof exercised a **new fork**, not a
hot-reloaded Var or development adoption. The publication included the
working-tree test edits present during indexing; those foreign files were
not edited or committed by this assignment. The source is the canonical
published population, not a synthetic roster.

MCP JVM evaluation on root `/Users/sean/src/seon/tmp/schema-shape-root`,
cluster `schema-shape`, returned this complete numeric value in 454 ms:

```clojure
{:rows 4372
 :characters 72877056
 :utf8-bytes 72877170
 :maximum-characters 1563264}
```

Exact measurement form (also the repeatable after-measurement):

```clojure
(let [database (seon.db/db (seon.operator/connection "schema-shape"))]
  (reduce
   (fn [result datom]
     (let [value (:v datom)
           characters (count value)
           bytes (alength (.getBytes ^String value
                                    java.nio.charset.StandardCharsets/UTF_8))]
       (-> result
           (update :rows inc)
           (update :characters + characters)
           (update :utf8-bytes + bytes)
           (update :maximum-characters max characters))))
   {:rows 0 :characters 0 :utf8-bytes 0 :maximum-characters 0}
   (seon.db/datoms database :aevt :seon.schema.shape/form)))
```

The following production-owner probe completed in 2 ms and returned
`{:named-authored :probe/value :inline-authored :int
:same-stored-fingerprint true}`. It verifies the behavior decision rather
than inferring it from the old schema comment:

```clojure
(let [forms {:probe/value :int}
      options {:registry (malli.registry/composite-registry
                          forms (malli.core/default-schemas))}
      named (malli.core/schema :probe/value options)
      inline (malli.core/schema :int options)]
  {:named-authored (malli.core/form named)
   :inline-authored (malli.core/form inline)
   :same-stored-fingerprint
   (= (:seon.schema.shape/fingerprint
       (seon.fn.schema-shape/shape-row named forms))
      (:seon.schema.shape/fingerprint
       (seon.fn.schema-shape/shape-row inline forms)))})
```

There is no after number: no production change has been made pending the
behavior decision. No test result or completed optimization is claimed.

Cleanup completed through `bin/seon --root tmp/schema-shape-root down`:
the operator reported all recorded JVMs stopped and the store flock free.
The assignment's scratch directory was then deleted without following
symlinks. The required namespace-load command exited zero again before
the evidence commit. No owned shell or scratch JVM remains. Markdown lint
reported two unrelated stale Datahike gitlink citations in the wave-3a and
wave-3bc specifications; those documents were left untouched.

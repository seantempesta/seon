---
type: issue
status: resolved
severity: friction
tags: [issue, schema, contract, test]
---

# An incremental projection build refuses the key it just added

Found 2026-09-08 by `instrumented-gate-backlog` while draining the reds the
armed gate exposed
([landing note](../../prds/context-generation/research/instrumented-gate-backlog-landing-2026-09-08.md)).

`seon.schema/projection-with-schema` declares its definition argument as
`:seon.schema/definition` → `:seon.schema/malli-form`, whose predicate is
`seon.schema/malli-form?`. That predicate compiles the form against
`(candidate-registry)` — the AMBIENT declaration population — and not against
the projection the call is extending:

```clojure
;; src/seon/schema.clj
(m/schema (compilable-form decoded {}) {:registry (candidate-registry)})
```

So a caller building a projection one key at a time, where a later definition
references an earlier one, is refused for a reference the projection in its
hand already resolves:

```clojure
(reduce-kv (fn [current k definition]
             (schema/projection-with-schema current k definition
                                            {:seon.schema.admission/source :core}))
           (schema/projection-from-database @connection)
           {base-key   [:int {:seon.db/index true}]
            direct-key [:and {:seon.db/index true} base-key]})
;; => seon.schema/projection-with-schema violated its contract (invalid-input):
;;    must be a parseable, EDN-readable Malli form
```

This is the pre-read/authority shape the owner law names: the CONTRACT
pre-reads a registry that the AUTHORITY (`projection-with-schema`, which
holds `current`) is about to extend, and the two disagree. The predicate is
not wrong about anything it can see; it is asking the wrong world.

## Where it is red

Under the armed gate (`bin/test --all`), roughly fifteen reds across
`seon.schema-usage-guard-test` (four tests), `seon.schema-test`,
`seon.schema.datahike-test`, `seon.schema.edn-test` and `seon.fn-test`. Each
is a suite building a small dependent schema family the way a publication
does. Nothing here is a fixture defect that a fixture can honestly fix: the
call is exactly the incremental build the function exists for.

## Options, unpriced

1. `malli-form?` reads the projection it is being handed, when there is one —
   which means the definition contract has to be able to see the call's other
   argument. Malli can express that with a `:multi`/`:fn` over the whole
   argument vector rather than per argument.
2. `projection-with-schema` declares `:seon.schema/value` for its definition
   and keeps its OWN admission (which already resolves against `current`) as
   the authority — the same repair `seon.schema.datahike`'s bridge functions
   took in `e46df126a`, for the same reason: they walked values their
   declaration claimed were definitions.
3. Callers bind the growing projection around each step, so the ambient
   registry the predicate reads is the one being extended. This works, but it
   is a discipline every caller has to remember — the shape this project
   calls a defect on sight.

Option 2 is the one that matches the ruled repair already in the tree.

## Resolved 2026-09-08 (`instrumented-gate-backlog-2`)

Neither of the filed options: the owner's law dissolved the question instead.
`malli-form?` no longer asks ANY declaration population whether a reference
resolves, because that is not the question a definition contract is for. It
compiles against one structural registry — Malli's own default schemas, plus
an opaque placeholder for every keyword or qualified-symbol reference — and
answers whether the form PARSES. Resolution, acyclicity, and validation stay
where the projection is: `projection-with-schema` compiles the same form
against the registry it is extending and refuses there, naming the key.

The same edit closes
[malli-form-predicate-resolves-the-declaration-population-itself](malli-form-predicate-resolves-the-declaration-population-itself.md):
with nothing to resolve, the classpath re-read it was filed for cannot happen.

One production defect fell out of the repair, because the incremental build
now reaches code it used to be refused before: `predicate-functions-with`
seeded its reduce with `(:seon.schema.projection/predicate-functions
projection)`, which is ABSENT on a projection carrying no bound predicates
(`declaration-projection` builds exactly that shape), so it returned nil into
`compilable-form`, whose declared input is a map. It now seeds with the empty
map the absence means.

Regression:
`seon.schema-test/an-incremental-build-resolves-against-the-projection-in-hand`.

Gate: `bin/test seon.schema-test seon.schema-usage-guard-test
seon.schema.datahike-test seon.schema.edn-test seon.fn-test` went from 38
failing to 6, all six `seon.fn-test` reds present at the baseline commit;
`bin/test --platform` GREEN 73/398/0.

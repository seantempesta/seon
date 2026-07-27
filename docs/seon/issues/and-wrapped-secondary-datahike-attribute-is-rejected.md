---
type: issue
status: open
tags: [issue, database, schema]
severity: friction
---

# And-wrapped secondary Datahike attribute is rejected

## Evidence

An automatic review of the bounded schema-bridge change noticed that
`malli->datahike-attr` validates `:db.secondary/only` by comparing
`(form-head value-form)` with `#{:float :double}`. Unlike
`form->datahike-value-type`, that check does not unwrap Malli `:and`.

The shortest real-registration probe confirms the inherited defect:

```clojure
(schema/register! ::secondary
                  [:and {:db.secondary/only true} :double])
(schema.datahike/malli->datahike-attr ::secondary)
;; throws:
;; A secondary-only attribute must contain floats.
```

The underlying value type is `:db.type/double`, but the guard sees the form
head `:and`. This is independent of the alias-property defect and was not
expanded into that bounded implementation.

## Owner

The B2 schema-EDN wave in `seon.schema.datahike`; admission must derive from
the same resolved value type as declaration.

## Acceptance

- Secondary-only admission derives from the same resolved value type used for
  the Datahike declaration.
- Direct and `:and`-wrapped `:float` and `:double` shapes are accepted.
- A non-floating secondary-only shape still refuses with
  `:seon.error/kind :user-input`.
- One regression covers the wrapper class through the public
  `malli->datahike-attr` call.

## Triage 2026-07-27

- **OPEN-CURRENT.** `src/seon/schema/datahike.cljc:118-146` still tests the
  unresolved `form-head` for `:float`/`:double`, while the value-type path at
  `:55-102` is the code that unwraps `:and`; the described rejection remains
  representable and `test/seon/schema/datahike_test.clj:1-46` has no secondary
  wrapper regression.

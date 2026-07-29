---
type: issue
status: open
severity: friction
tags: [issue, schema, render]
---

# A self-referential registered schema overflows the stack instead of refusing

## Problem

`seon.schema` cannot express a recursive shape, and it does not say so. A
declaration that references itself — directly or through `[:ref …]` — is
accepted by `register!` and then overflows the stack the first time anything
builds the candidate registry, with a `StackOverflowError` from
`seon.schema/bind-predicates` and no message naming the declaration.

Recursion is not an exotic want. Hiccup is a tree, a plan is a tree, a value
panel walks a tree; every one of them is a shape a producer will eventually try
to register. Today the first honest attempt takes down the caller with a JVM
error that names no attribute, which is the opposite of the fail-loud contract:
loud requires legible.

## Evidence

Probe, fresh tree at `627d75e30`, `clojure -M:dev`
(`tmp/n4_hiccup_schema_probe.clj`):

```clojure
(schema/register! :seon.render.probe/hiccup
                  [:or :nil :boolean :string
                   [:vector {:min 1} [:ref :seon.render.probe/hiccup]]])
(schema/valid-candidate-value? :seon.render.probe/hiccup ["div" ["p" "hi"]])
```

```text
Execution error (StackOverflowError) at seon.schema/bind-predicates
  (schema.cljc:81).
null
```

`register!` itself returns normally — resolution is deferred to
`build-projection` — so the failure lands on whichever caller first validates
anything at all, not on the producer that wrote the bad declaration. The plain
self-name form (`[:or :string [:vector :seon.render.probe/h2]]`) reaches the
same walker.

Malli itself supports recursion through a local `{:registry …}` with `:ref` and
`:schema`; the limitation is in Seon's global-population walker, which resolves
references eagerly and has no visited set.

## Impact

N4's render layer needs a hiccup schema. Because recursion is unavailable, the
N4 package-1 contract declares hiccup as
`[:fn {:gen/gen seon.render.hiccup/hiccup-generator} seon.render.hiccup/hiccup?]`
— the supported named-predicate idiom, with a real predicate and an honest
generator. That is a legitimate answer for hiccup specifically (the grammar
belongs to the serializer that enforces it), so this issue does not block N4.
It will block the first producer for whom a predicate is a worse answer than a
schema, and until then it is a booby trap: the failure mode is a stack overflow
in an unrelated caller.

## Owner

`seon.schema/bind-predicates` and the candidate-registry walk in
`src/seon/schema.cljc`. The fix is at the walker, not at any producer.

## Acceptance

- A self-referential declaration either (a) resolves correctly, by carrying a
  visited set through the walk so a reference back to an in-progress
  declaration is bound rather than expanded, or (b) is REFUSED at the one
  admission gate with a message naming the declaration and the cycle.
- The refusal, if that is the answer chosen, happens where the bad declaration
  is submitted — the gate — never in the next unrelated caller.
- One regression per class, at the gate: a directly self-referential
  declaration and a mutually recursive pair.
- No caller works around this by declaring `:any`.

## Notes

Found 2026-07-27 while drafting the N4 render contracts. The probe script is
`tmp/n4_hiccup_schema_probe.clj`.

## Triage 2026-07-29

**REAL-BUT-QUEUED — schema admission.** `bind-predicates` still performs an
unguarded postwalk over the recursive form, but the only demonstrated consumer
was the now-draft render grammar. Keep the generic admission trap queued; do
not fence the render walk with it.

---
type: research
status: complete
tags: [render, error, contracts]
---

# Render-2 kind retirement — 2026-09-21

Read `docs/prds/steward-platform/plan/error-conversion-prd-2026-09-20.md`
and `docs/prds/steward-platform/research/kind-sweep-render-2026-09-20.md`
end to end. This bounded lane used the data-oriented-clojure,
clojure-testing, and repl skills and did not operate the default cluster,
run a cold gate, or create a worktree.

## Result

The five production owners are kind-free and load together. `shorthand`,
`element-with-id`, `node-id`, and `window` now construct the diagnostic base
plus exactly their owning facet. Consumers inspect the required distinguishing
member. Namespace rendering recognizes the database read facet through
`:seon.db/read-operation`; it no longer owns a general error predicate.

The Hiccup facet reuses its required unparseable-tag observation. The missing
lint facet is `:seon.render.lint/absent-element-error`, distinguished by the
requested element id. The value facets now carry substantive stored
observations: missing root description, realization offset, or failed blob
digest. No parallel render facet family was introduced.

## Census

Counts are matching lines for
`:seon.error/kind|seon.error/class|error/error?`, before at HEAD and after:

| File | Before | After |
|---|---:|---:|
| `src/seon/render/hiccup.clj` | 5 | 0 |
| `src/seon/render/lint.clj` | 5 | 0 |
| `src/seon/render/ns.clj` | 1 | 0 |
| `src/seon/render/test.clj` | 0 | 0 |
| `src/seon/render/value.clj` | 5 | 0 |
| `resources/seon/schemas/seon.render.hiccup.edn` | 1 | 0 |
| `resources/seon/schemas/seon.render.lint.edn` | 0 | 0 |
| `resources/seon/schemas/seon.render.value.edn` | 3 | 0 |
| `test/seon/render/lint_test.clj` | 1 | 0 |
| `test/seon/render/ns_test.clj` | 2 | 0 |
| `test/seon/render/value_test.clj` | 5 | 0 |

`test/seon/render/hiccup_test.clj` is held by bridge-step1-registry and was
not edited or overlaid. Its one remaining literal kind fixture is owed to that
holder. No owned inline base-three check or generic error output remains.

## Verification

The ordered discriminator, inline-base, and generic-output searches returned
zero lines. The required load probe returned `:loads`:

```text
clojure -M -e "(require 'seon.render.hiccup 'seon.render.lint
  'seon.render.ns 'seon.render.test 'seon.render.value) (println :loads)"
```

The foreground fast overlay, excluding the held Hiccup test, completed:

```text
56 tests / 390 assertions / 79 failures / 13 errors
run e10f99d6aac9
```

All nine lint tests passed. The changed anonymous-root and realization-failure
value regressions passed. The total tally is not a green claim. The verified
foreign boundary is the concurrent symbol-type cut: current schemas require
qualified symbols while the older namespace/value fixtures and callees in the
HEAD snapshot still submit strings (for example `:seon.fn/sym` and
`:seon.effect/owner`). That refusal cascades into the namespace and structural
value failures. Other existing value-render expectations also remain red.

## Debt and proof owed

- Held: `test/seon/render/hiccup_test.clj`; bridge-step1-registry owes its R8
  conversion and Hiccup namespace test coverage.
- No step-6 base-three consumer debt remains in this slice.
- No section-6 schema ambiguity or ownerless producer was found.
- The orchestrator owes
  `bin/test --paths <these ten changed source/schema/test files> -- seon.render.lint-test seon.render.ns-test seon.render.value-test seon.render.hiccup-test`
  followed by `bin/test --platform`, after the held test and symbol-type cut
  land.

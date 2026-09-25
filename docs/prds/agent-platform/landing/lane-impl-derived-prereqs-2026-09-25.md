---
type: report
status: S0 committed; S2/F1 held by owner direction
created: 2026-09-25
tags: [agent-platform, derived-contracts, schema-shape]
---

# Lane impl-derived-prereqs-opus — S0 landed; S2 and F1 held

Design: `docs/research/agent-platform/derived-contracts-2026-09-25.md` §8 (S0), review
`derived-contracts-review-2026-09-25.md` (511b948c6) "S0 is real and broader than a keyword crash".

## Broken or open first

- **S2 and F1 were not started.** The owner directed one step at a time: S0 only. There were no
  edits to `src/seon/instrument.clj`, `src/seon/fn/analyzer.clj` or `reference-code/clj-kondo`,
  so there is no patch to save.
- **Not verified in default.** Hook publication is paused, so default still runs the old
  `seon.fn.schema-shape`. The regression below has not run: the load rule gives test runs to root.
- **S2 finding, to carry into its resumption (read-only probe on default, pid 35446):**
  - 2,164 loaded Vars carry `:malli/schema`; the projection holds 2,132 function contracts.
  - 81 metadata forms differ from their projection contracts.
  - None of the 31 projection-only contracts is currently loaded.
  - **43 armed Vars have metadata only.** They are host code outside the program: `seon.operator`
    (27, `script/seon/operator.clj`), `seon.dev.*` (12), `dev-cache` (3) and `seon.operator.runtime` (1).
  - Arming from the projection alone would disarm those 43. S2 therefore needs one explicit rule for
    host code that the projection does not name (proposal: keep their `:malli/schema`), and the
    changed-identity path should read the projection only.
  - The test `the-selection-is-declared-vars-with-schemas-and-nothing-else` asserts that
    `(every? #(some? (mi/-schema %)) wrapped)`. That assertion becomes false once a
    projection-only contract arms.

## S0 — what changed (commit eb7e64ffa)

- `src/seon/fn/schema_shape.clj` gains `entry-parts`, which follows Malli's `-parse-entry`
  (`reference-code/malli/src/malli/core.cljc:487-535`, pin `befcd302a8e6`):
  - `k`, `[k]` and `[k properties]` become `[k properties k]`;
  - `[k child]` and `[k properties child]` stay explicit.
- `canonical-map-entry` and `entry-row` both read through it. `read-form` needed no change: the stored
  canonical form is always explicit, so the decoder's appended child is correct.
- Net lines: source +15/−4, test +42.
- `normalization-revision` is not bumped, because no existing fingerprint moves (measured below).

## Evidence (REPL on default, JVM mode, a throwaway copy of the file in a scratch namespace)

**Before, with the installed code** (the compiled registry, `[:map …]` over `:seon.agent/id`):

| Spelling | Result |
|---|---|
| naked `k` | `UnsupportedOperationException: nth not supported on this type: Keyword` |
| `[k]` | `:malli.core/invalid-schema` (a nil child) |
| `[k {:optional true}]` | `:malli.core/invalid-schema` (a nil child) |
| explicit | fingerprints `56cf0849…` and `f62556fe…` (with properties) |

**After: encode, then `d/with`, then pull back through `database-form`, then decode and re-encode:**
- Every spelling gives `:fp-equal true` and the same validation on `{}`, `{:seon.agent/id "a"}` and
  `{:seon.agent/id 1}`.
- The naked, `[k]` and explicit spellings share fingerprint `0d066320a365`.
- `[k {:optional true}]` and `[k {:optional true} k]` share `05eee2b7b7ff`.
- A nested naked map inside an explicit child round-trips.
- Each spelling decodes to the explicit spelling.
- Per-spelling time was 0.7–1.5 ms, and 8.2 ms for the first, cold one.
- The shape rows of every variant are `=` to their explicit twin's rows. So call preparation's
  `map-entry-query`/`named-map-entry-query` (`call_preparation.clj:850-900`), which join on the entry
  key and the entry schema fingerprint, see identical facts. That is acceptance case 8 by construction.

**No drift across the real population:**
- The test encoded all 5,240 registry forms and function contracts with the old code and with the
  new code.
- Differing fingerprints: 0. Errors: 0 before and 0 after.
- Time: 473 ms before, 467 ms after.
- `prepare-forms` took 89 ms.

**Contract check:** the armed `entry-parts` contract accepted all five spellings.

The scratch namespaces `impl-derived-prereqs.schema-shape` and `impl-derived-prereqs-scratch` were
removed from default after the probes.

## Regression

`seon.fn.schema-shape-test/every-malli-map-entry-spelling-stores-one-shape`:
- it covers the naked, `[k]`, `[k properties]` and reordered spellings, and a nested naked entry;
- rows equal their explicit twin;
- it stores, reads back through `database-form` and `compiled-in`, and checks fingerprint equality,
  decoding to the explicit form and the same validation.

## Root runs (serially, quiet window)

After adopting eb7e64ffa:

```sh
bin/test-check default --test seon.fn.schema-shape-test/every-malli-map-entry-spelling-stores-one-shape
bin/test-check default --ns seon.fn.schema-shape-test
bin/test-check default --ns seon.call-preparation-test
```

Baseline for F1 when it resumes. Record the quiet-run clj-kondo lint time before and after the fork
change:

```sh
time clj-kondo --parallel --lint src > /dev/null
```

## Released paths

This lane holds these files and no longer needs them:
- `src/seon/fn/schema_shape.clj` and `test/seon/fn/schema_shape_test.clj` (committed);
- `src/seon/instrument.clj`, `test/seon/instrument_test.clj`, `src/seon/fn/analyzer.clj` and
  `reference-code/clj-kondo` (untouched; release them until S2/F1 resume).

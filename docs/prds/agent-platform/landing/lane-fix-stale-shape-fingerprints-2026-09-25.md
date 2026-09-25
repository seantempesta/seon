---
type: landing
lane: fix-stale-shape-fingerprints
status: stopped
created: 2026-09-25
---

# Lane fix-stale-shape-fingerprints: stopped, the producer is in src/seon/fn.clj

## Broken first

- **The producer is not in this lane's files.** Row selection for incremental
  publication is `seon.fn/desired-rows` (fn.clj:3071). It re-derives schema rows only
  for `changed-schema-keys` (fn.clj:3027), and function arities only for rows in the
  changed files. `seon.program/with-contract-facts` (program.cljc:1109) derives the
  shape of each row it is given and never chooses rows. `seon.cluster` hands
  `:seon.fn/changed-paths` to `seon.fn/index!` (cluster.clj:1721, :1747).
  - No edit to program.cljc or cluster.clj can re-derive a dependent.
  - Widening changed-paths in cluster.clj would not help either. A dependent's form
    is unchanged, so `changed-schema-keys` still leaves it out.
- Call preparation on default stays dead. **RESET NEEDED** after the producer fix.
- No code changed.

## Evidence (read-only MCP JVM probes on default)

- d2604ede9 edited `resources/seon/schemas/seon.env.edn` and `seon.effect.edn`.
  - `changed-schema-keys` selects `:seon.flow/work-launcher`, whose form changed.
  - It skips `:seon.env/environment`, whose form string did not change even though its
    reference fingerprint did.
- `(schema/dependent-schema-keys proj #{:seon.flow/work-launcher})` returns 7 keys in
  2 µs, `:seon.env/environment` among them.
  - `:seon.schema.projection/reverse-function-dependencies` over those 7 keys names 46
    functions.
- The largest reverse closure over every schema key is **797 schemas**. Editing a hub
  schema would re-derive most of the program. Cost follows the closure, as ruled, but
  that closure can be nearly the whole program.

## The fix (for the fn.clj owner, about 30–40 lines)

1. In `desired-rows`, set `selected-forms` to
   `(select-keys packaged-forms (schema/dependent-schema-keys projection (changed-schema-keys request)))`.
   Apply the same widening to the `:seon.schema/key` identities in `index!`'s
   `previous-identities` (fn.clj:~3775). This takes about 3 lines and re-derives
   dependent packaged schema rows.
2. Pull every function in `reverse-function-dependencies` of that closure from
   `:seon.source/previous-database`, excluding those already in `changed-rows`. Read the
   same attributes `file-rows` reads. Feed those rows through `add-contract-facts`,
   and add their identities to `previous-identities` so their arity components are
   replaced.
3. Handle source-declared schema dependents (a `:seon.schema/key` row that is not in
   `packaged-forms`) the same way as step 2.
4. Regression in test/seon/fn_test.clj: publish a world, edit a leaf schema resource,
   publish partially, then assert:
   - no two `:seon.schema.shape` rows share a type and form with different fingerprints;
   - the dependent schema row's fingerprint equals the dependent function arity's
     argument fingerprint.

## Root runs after the fn.clj fix

```
bin/seon init --dev default
bin/test-check default --ns seon.call-preparation-test
```

Then run the census J list in lane-fix-sci-zero-arg-and-names-2026-09-25.md.

## Limits

No test was run (LOAD RULE). Net lines: 0 source, 0 test.

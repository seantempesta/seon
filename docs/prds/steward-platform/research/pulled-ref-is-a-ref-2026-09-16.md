---
type: research
status: active
tags: [schema, database, render, test]
---

# A pulled reference is a reference — 2026-09-16

Assignment: repair the shared reference spelling and the silent prompt failure
for default agent `2393cac275ae`, turn `53b6c6a503ad`, without restarting default.
Read AGENTS.md §§0–3 and §5 and the named implementation grounding before edits.
Also read the complete
[entity-schema versus pulled-shape study](entity-schema-vs-pulled-shape-2026-09-16.md)
before applying the orchestrator's corrections.

## Dependency ledger

- Datahike `reference-code/datahike/src/datahike/db/transaction.cljc:739`:
  `explode` treats a map under a ref attribute as a nested entity, adding the
  reverse ref; `{:db/id n}` names the existing target.
- `src/seon/schema/datahike.clj:37` preserves `:seon.db/ref` during alias
  resolution; `form->datahike-value-type-in` maps that declaration identity to
  `:db.type/ref`. Widening its Malli alternatives does not change storage type.
- `src/seon/db.clj`, `write-value`, `write-many-values`, `write-ref-error`:
  input validation handles nested map grammar and normalizes reference values
  for Malli. `write-entity-value` reads final EAVT values; `write-entity-error`
  validates resolved eids. No writer change is needed.
- `seon.schema.datahike/storable-attribute-in?` derives storability from the
  supplied projection. `seon.db/pull` retains the dependency's reference shape.
- `reference-code/datastar/library/src/plugins/actions/fetch.ts:533` resolves
  non-200 requests without dispatching their response body. A successful HTML
  response enters the existing patch-elements mechanism at line 555.
- `seon.render.transcript/render-session` already renders typed acquisition
  failures. Separate commit `eec636a97` catches only instrument contract
  violations and renders their complete evidence; unrelated exceptions still
  throw. Its [own note](session-prompt-contract-refusal-2026-09-16.md) records
  the justification and HTTP regression.

## Removed local patches

1. `resources/seon/schemas/seon.eval.edn`: `:seon.cluster.eval/run` was a map-only
   pulled-ref shape; now it names its declared attribute.
2. Same file: `:seon.eval/renderer-fn` no longer adds a local map alternative.
3. `resources/seon/schemas/seon.test.edn`: `:seon.test/run` no longer adds one.
4. `resources/seon/schemas/seon.message.edn`: `:seon.message/pulled-reference`
   is deleted. Its four schema entries and two message-renderer contracts
   now name `:seon.db/ref` directly.

The inline `seon.turn/open?` union is removed. `seon.test/changed-since-green`
uses the shared reference declaration intersected with its required function
symbol map; the symbol requirement remains intact.

The fourth alternative is declared once in `:seon.db/ref`, an open map requiring
integer `:db/id`. Arbitrary maps without that key remain outside this spelling.

## Verification in progress

The initial HTTP request returned exactly:

```text
seon.eval/of-agent refused return value at [17 :seon.eval/origin]: expected an integer, got a map. Fix: Supply an integer at [17 :seon.eval/origin].
```

Default remained pid 41413. Runtime health answered. The single read-only MCP
evaluation timed out at 20,000 ms, so no further evaluation was attempted under
the assignment's one-evaluation bound. Publication is observed through hook
result files and the live HTTP response, not inferred from disk edits.

At entry, db_test.clj and turn_test.clj had concurrent edits. The class regression
is in schema_test.clj. After the other lane committed, db_test.clj's existing
reference diagnostic expectation was updated to include the newly admitted
map. The database writer and turn_test.clj were left alone.
Fast iterations use HEAD plus only this assignment's paths. The old
orchestrator-only marker required the test launcher override for the explicitly
authorized fast iteration. No correctness gate (`bin/test`) was requested;
the fast path internally delegates snapshot construction to `bin/test --fast`.

## Live proof, 21:40:50 UTC

The assigned Datastar GET returned HTTP 200, `Datastar-Mode: replace`, and
29,955 response bytes. The first bytes were:

```html
<section class="seon-session" data-ignore-morph="" data-session-loaded="53b6c6a503ad" data-signals="{reread0:false}" id="surface-debug-session_2f_2393cac275ae">
```

The decoded prompt contains 21,708 UTF-8 bytes, SHA-256
`691423bcdb56fbf80d59e4619984d424a8ddfbf6e4e0271269314ab5f19fd2a3`.
Its exact opening is:

```text
seon.flow=> ;; I should understand how this REPL works before I act.
(help)
The prompt shows your namespace seon.flow and is drawn for you. Send only ;; thinking comments and forms.
Results are data: chain them with ->>, sort-by, filter, map, and get-in. Functions are callable by their fully qualified symbols.
```

Chrome observation after reload: “Context at turn 11”, “21,708 bytes · ≈6,781
tokens · 23 emissions · oldest → newest”, and the visible prompt beginning
above. The loading status is gone. This is the existing development JVM after
in-place publication; this assignment never restarted or hot-reloaded it.
The first three hook invocations reported exit 124 even though the later HTTP
observation proves the prompt now renders. No second MCP evaluation was used
to claim source-head equality; that marker remains unverified.

## Additional boundaries exposed by the exhaustive regression

The packaged projection declares 80 map schemas with reference entries:
48 storable maps and 32 other maps, including `:seon.eval/entity`.
Both sets derive from the projection, resolving registry aliases. The
nonstorable subjects receive their declared required entries and reference
maps. The storable subjects transact through `test-support/transacted!`,
which calls `seon.db/transact!`, then pull `[*]` and check every reference
target. The bridge still derives `:db.type/ref` from the shared declaration.
All 48 writes were admitted in the 21:47–21:50 UTC run, exercising final
whole-entity validation of resolved eids. The original return-schema failure
on evaluation origin did not recur.

The exhaustive round-trip run exposed independent
conditions that the shared reference spelling cannot repair:

- Pulled cardinality-many values are vectors, while several whole-entity
  contracts require sets. For example `:seon.ai.model/entity` pulled
  `:seon.ai.model/deepseek-off-peak-windows [{:db/id 9819}]`, which is not a set.
- Datahike rejects a nested reference map in a unique identity attribute
  (`:my.plan/agent`, `:seon.config/agent`, `:seon.runtime/agent`) before the
  ordinary `explode` path: “Expected number or lookup ref for entity id,
  got #:db{:id 9819}”. Ordinary nonidentity nested refs, including evaluation
  origin, did transact and pull successfully.
- The generated `:seon.activation/closure` included empty required sets;
  Datahike stores no datoms for them, so final whole-entity validation reports
  the missing required keys. This is a fixture-generation limitation in that
  run, not evidence against reference-map admission.

The existing `seon.issue-test/issue-worker-opening-links-its-issue` completed
without failures/errors (21:39:03–21:39:15 UTC), including `of-agent` and origin
lookup. The new HTTP test
`seon.render.web-test/session-acquisition-failure-replaces-the-loading-panel`
completed without failures/errors (21:39:37–21:39:38 UTC).
The owner was asked to choose the scope for the independent collection mismatch.
With no scope expansion requested, this review checkpoint retains the exact
whole-map assertion and reports its failures; neither read behavior nor the
assertion was weakened to produce a green result.

The corrected four-namespace run at 21:47–21:50 UTC completed 160 tests and
1,729 assertions, with 26 failures and no errors: 17 whole-map collection
mismatches, one generator failure for a nonstorable request, three expected
diagnostic strings needing “or a map”, and five failures in the existing
history fixture's rejected retractions. The diagnostic expectations and
generator were then corrected. No green whole-map round-trip claim is made.

Final measured results:

- Four named namespaces, 21:54–21:59 UTC: 160 tests / 1,729 assertions,
  25 failures / 0 errors. `seon.db-test` and `seon.turn-test` passed. The
  remaining failures were 17 collection mismatches, three temporary generator
  failures caused by traversing fixture objects in schema properties, and
  the five existing web history fixture failures. The generator now supplies
  canonical connection/database/SCI values through opaque `gen/return`
  generators and owns its file lock in `with-open`, deleting the file on exit.
- Final `seon.schema-test` iteration, 21:59–22:01 UTC: 23 tests / 546
  assertions, exactly 17 failures / 0 errors, all the documented collection
  mismatch. All 32 nonstorable maps validate, including `:seon.eval/entity`
  with `:seon.eval/origin {:db/id 1}`. All 48 storable maps transact, preserve
  their reference targets, and 31 of their raw wildcard pulls validate as
  whole maps. There are no remaining generator failures.
- The UI contract-refusal regression passed in both four-namespace runs.
- The exact requested `curl ... | head -c 2000` was repeated at 21:55 UTC
  and again began with the rendered session element, not a refusal.

These are fast iteration results. No correctness gate or platform suite was
run under the assignment's explicit `bin/test` prohibition. The checkpoint
is ready for review with the whole-map collection proof still red.

## Schema-audit findings and verification boundaries

- D2: `write-value` substitutes `0` for map or sequential refs before Malli
  sees them; the final entity validator sees resolved eids. Recorded in
  [the write normalization issue](../../../seon/issues/write-reference-validation-substitutes-zero-before-malli.md).
- D3: `:seon.eval/entity` has no `:seon.db/attributes` and is not selected
  by `write-entity-schemas`. However, the broader claim that evaluation rows
  receive no whole-entity validation is false: `:seon.cluster.eval/receipt`
  declares attributes, requires the evaluation identity, run, ordinal and
  time, and is selected by that writer. Recorded in
  [the declaration mismatch issue](../../../seon/issues/evaluation-reader-and-writer-use-different-entity-schemas.md).
- The independent collection mismatch is recorded in
  [the pulled-collection issue](../../../seon/issues/wildcard-pulled-collections-do-not-satisfy-entity-set-contracts.md).
- The existing web history test's required-attribute retractions are recorded
  in [the fixture issue](../../../seon/issues/history-prompt-fixture-retracts-required-turn-attributes.md).
- Foreign working-tree changes in the runner, arming, prompt and history
  owners were excluded through the named-path fast snapshot. Later edits to
  transcript.clj after the separate UI commit belong to another lane.
- Markdown hook feedback reports 30 historical gitlink citation mismatches
  elsewhere in the repository; no unrelated documentation was rewritten.
- Hook publication feedback repeatedly reports exit 124. The observed HTTP
  and browser prompt prove effective adoption of the reference spelling in
  default, but not equality of its source marker with the latest publication.

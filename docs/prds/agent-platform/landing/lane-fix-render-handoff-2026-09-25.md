---
type: landing
lane: fix-render-handoff-opus
status: landed-unverified
date: 2026-09-25
authority: docs/prds/agent-platform/landing/lane-fix-writer-digest-2026-09-25.md (P2 producer half);
  docs/prds/agent-platform/landing/lane-render-dispatch-opus-2026-09-24.md (hand-off);
  docs/prds/agent-platform/landing/lane-fix-turn-census-e-2026-09-25.md (item 1, root ruling)
---

# Lane fix-render-handoff (2026-09-25)

## Broken or unproven first

- **Nothing here has run as a test** (load rule). The only checks were clj-kondo (no new errors) and read-only
  JVM probes on default (pid 35446). No changed namespace was loaded into default. Root's runs below are the
  first proof. RESET NEEDED: none.
- **Option 1 is now complete, unproven** (017e95e02, after root freed turn.clj and seon.sci.eval.edn).
  - `evaluate-sources` passes the namespace rows that earlier forms of the settlement will store, as the declared
    `:seon.sci.eval/settled-namespaces`.
  - `definition-rows` digests with `(or settled stored sci)`.
  - Regression: `a-definition-digests-with-the-namespace-row-its-settlement-stores`. A `require` and then a
    contracted defn; both canonical rows are admitted in one write.
  - Unproven: the one-transaction admission of the context row's canonical form.
- **The turn settlement re-merges digested attributes after the digest.** `seon.fn/analyzed-form`
  (fn.clj:1100–1144) replaces the evaluation's row with `merge` of `:seon.fn/invokes`, `:seon.fn/macro?`,
  `:seon.test/subject` and seed keywords. The source comment says the row "keeps the digest its constructor
  derived", but those four are digested.
  - They come from the same kondo analysis `source-rows` read, so the values should be equal. That is unproven.
  - Any SCI-settled row still refused with member `:seon.program/definition-digest` points here. Fix: digest after
    that merge, which is fn.clj outside `source-rows` and not held here.
- **The indexer's `var-row` (fn.clj:821–826) is still a second digest producer.** It digests with the analyzer's
  `namespace-contexts`, and converting it needs the namespace row in `analysis-rows-by-file`. That is not small,
  so it is listed, not changed.
- **Call preparation gap: exact patch for root to route** (call_preparation.clj; not applied).
  - Cause: `named-entry-facts` takes its candidate parent schemas only from the projection's shape-index.
    `shape-row-in` (schema.clj:2039–2067) makes a map a shape only when it has a required attribute, or when it is
    an entity or render-pair map.
  - `:seon.render/unit` is none of these: it is a plain `:map` whose only entry is the optional `:seon.db/db`.
  - Making it a shape would be wrong: every value carrying `:seon.db/db` would then match it in render selection.
  - Fix: also take, as parents, the named schemas that function arguments actually reference. That is a query
    over declared arguments, not a scan of the whole schema population:

    ```clojure
    ;; call_preparation.clj, named-entry-facts (:358)
    -        parents (into #{} (mapcat #(get (:seon.schema.projection/shape-index projection) %))
    -                      (keys by-key))]
    +        ;; A named argument schema requests a default by declaring its entry,
    +        ;; shape or not (`:seon.render/unit` is a plain optional-entry map).
    +        parents (into (into #{} (mapcat #(get (:seon.schema.projection/shape-index projection) %))
    +                            (keys by-key))
    +                      (db/q '[:find [?schema-key ...]
    +                              :where [_ :seon.fn.argument/schema ?shape]
    +                                     [?shape :seon.schema.shape/type ?schema-key]]
    +                            database))]
    ```

  - The existing `(= :map (m/type compiled))` and key/form checks still select only real supplied entries.
  - Cost: one query per `named-entry-facts` derivation, which is already per snapshot. Measure it before landing.
  - Root's call: with 3d0e68452 restored, render units no longer depend on this supply.
- **acquire! now follows root's ruling** (c7bede984, unproven).
  - A context's own definition survives its symbol's first lazy install and any unrelated reacquisition. The lazy
    install records `::context-defined?` on the snapshot's function entry, so a kept definition that is marked
    installed still carries.
  - When a program row's `::function-source` differs between the old and new acquired snapshots, the program
    advances in place over the own definition. `replaced-definitions` computes this. `regenerate-agent-context!`
    applies the same rule to turn forks.
  - The evaluation that crosses that boundary prints `;; PROGRAM CHANGE replaced your definition of <syms> …` in
    its output prefix.
  - Limit: a replacement found on the render path (lazy `ensure-function!` → `acquire!`) has no agent to tell. It
    is announced only when an evaluation crosses the boundary.
  - Regression: `seon.render-simplification-test/a-program-change-replaces-a-private-definition-and-says-so`.
- test/seon/sci/definition_digest_test.clj was added to this row by root.
- **Test expectations not re-derived:**
  - agent-identity (:674) now passes the agent value with its required `:seon.agent/branch`. The four-unit list
    may now differ.
  - block-reference now makes its identity-less entity a `:my.plan/constraint-observation` component. Admission
    of that component is unproven.
- **Timings: none measured** (no runs). Added per-evaluation cost: one `db/pull '[*]` of the namespace row per
  definition evaluation. Plus one `definition-digest-of` per defined row, 0.32 ms per row per the writer-digest
  landing.

## Commits (one fix each)

| commit | + / − | what |
|---|---:|---|
| 8ae207099 | +75 / −37 | Four changes in one fix:<br>• `seon.fn/source-rows` digests through `seon.db/definition-digest-of`.<br>• sci.eval `definition-rows` digests the FINAL row (after spec, workload, test markers and contract facts) with the stored namespace row, else SCI's.<br>• `test-support/revised-row` digests through the owner; the private `namespace-context` helper is deleted.<br>• `seon.fn/stored-namespace-context` is public, since seon.db resolves it.<br>Class regression `seon.sci.definition-digest-test/a-repl-definition-is-admitted-with-the-digest-the-writer-recomputes`: a contracted defn settled by SCI carries the owner's digest, and its canonical row is admitted by the writer. |
| d315faa6f | +3 / −1 | `invoke-selected` omits `:seon.db/db` when its request has none (it was `(:seon.db/db request)` → nil). The kernel's runtime supply answers with the environment-scoped database. This is the producer of the web-debug `invoke refused argument 0 at [:seon.db/db] … got nil` (:406, :478, :1021). |
| 4823bc6fd | +28 / −13 | **Cause of the SCI-redefinition reds:** `acquire!` reset the context env wholesale, so a fixture renderer defined in SCI before the first lazy acquisition was wiped. The subsequent install then bound the compiled root.<br>• Evidence: the :539 blob `71caf2ff…` shows the stack `ensure-function!` → `acquire-function-from-database!` → `acquire!` → `load-core-namespaces!`.<br>• Fix: `acquire!` carries this generation's non-installed Vars and the generation across the reset, the same objects `regenerate-agent-context!` carries.<br>Test updates:<br>• The :379 fixture rows digest their final value; the refusal was `missing :seon.program/definition-digest`, blob `1dadb3cc…`.<br>• :380 calls are symbols.<br>• Renderers elide the database, which the invocation scopes (retained :96 refusal: `q :in [$] … [nil]`).<br>• The :539 SCI-only trap acquires first.<br>• retained :200 `:seon.fn/source` edit goes through `support/revision`.<br>• distance writes `:seon.ns/requires` as a symbol, which is its declared type, and asserts the write. |
| ff4de4449 | +11 / −4 | web-debug tests.<br>• agent-identity: `:seon.agent/agent` now requires `:seon.agent/branch`, and the test's `{:seon.agent/id …}` matched no entity schema. Probe: with the stored agent, `declared-entity-units` includes settings and runtime.<br>• block-reference: its world write was refused (`unowned-entity`, .d/070 :705). Every `referenced-identities` failure followed from that refusal, not from web.clj:1159. |
| 3d0e68452 | +27 / −6 | **PRIORITY 0 (root ruling):** `render-argument` passes `:seon.db/db` in the unit again.<br>• Regression `seon.render-simplification-test/an-agent-neighborhood-renders-its-database-reading-units` walks an agent's neighborhood, as `declared-sources` does, and asserts no unit error and a runtime unit naming its owner.<br>• :158 asserts the unit carries the database. |
| c7bede984 | +43 / −8 | Root ruling on `acquire!`: a changed program row replaces an own definition, and the evaluation says so. Also applied to regenerate. Regression listed above. |
| 017e95e02 | +55 / −8 | Option 1: `:seon.sci.eval/settled-namespaces`, declared on the request (edn +8, turn.clj +7, eval.clj +10). Regression listed above. |
| db63995f8 | +3 / −1 | `transcript/render-session`: an acquisition without prompt text is the caught contract refusal, and it now renders as that failure. Before, `utf8-size` threw an NPE and the route returned 500 (web-test :2371). |

Net: +245 / −78 across src, schema and test, so +167, over nine commits (each ≤75 added).

## Root runs (serially, after adopting through db63995f8)

```
bin/test-check default --test seon.render-simplification-test/an-agent-neighborhood-renders-its-database-reading-units
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --ns seon.sci.definition-digest-test
bin/test-check default --test seon.render-simplification-test/a-program-change-replaces-a-private-definition-and-says-so
bin/test-check default --ns seon.cluster.evaluate-sources-test
bin/test-check default --ns seon.render-simplification-test
bin/test-check default --ns seon.render.retained-test
bin/test-check default --ns seon.render.web-debug-test
bin/test-check default --test seon.render.web-test/session-acquisition-failure-replaces-the-loading-panel
bin/test-check default --ns seon.sci.eval-test
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.test.one-request-test
bin/test-check default --ns seon.turn-test
bin/test-check default --ns seon.db-test
```

- The first two answer root's PRIORITY 0.
- `seon.sci.eval-test` checks the `acquire!` retention against the retained-fork and regeneration tests.
- `fn-test`, `one-request-test` and `turn-test` are the writer-digest landing's expected-red members.

## Out of scope, not filed (root files; no issue path owned here)

- The `analyzed-form` post-digest merge. See the second and third items
  under "Broken or unproven first".
- `:seon.render/unit` absent from the projection's shape-index, so optional named entries are never supplied.

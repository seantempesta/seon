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
- **Option 1 (the settlement's own namespace row) is only half done.** `definition-rows` now digests the FINAL
  row with `(or stored-namespace-row sci-namespace-row)`. That is the landing's written patch.
  - The remaining piece: a turn that changes its `ns` or requires in form N and defines in form N+1 of the same
    settlement. In that case the stored row predates the context row the settlement will write, so the
    definition is refused at the write (loudly).
  - Why it is not done here: each form's request carries the turn's pre-settlement database
    (turn.clj:4690, `(or snapshot (db/db connection))`). Only `seon.turn/evaluate-sources` knows the earlier
    forms' context rows.
  - What it needs: turn.clj (held by fix-turn-census-e-opus) must pass them, through a declared evaluation-request
    key in resources/seon/schemas/seon.sci.eval.edn. Neither file is in this row.
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
- **Call preparation gap (root asked):** `:seon.render/unit`'s optional `:seon.db/db` is never supplied.
  - `named-entry-facts` (call_preparation.clj:358) selects parent schemas from the projection's
    `:seon.schema.projection/shape-index`.
  - Probe on default: `(get shape-index :seon.db/db)` has 45 schemas, and `:seon.render/unit` is not among them.
    Its compiled form is a plain `:map` whose only entry is `[:seon.db/db {:optional true} :seon.db/database-value]`.
  - So 3785970b9's optional-entry change never sees the unit. The index is built only from schemas that have
    shape rows (schema.clj:2330–2352).
  - The fix belongs to schema.clj or call_preparation.clj; neither is held here. The restore in 3d0e68452 makes
    it non-blocking.
- **acquire! keeps a context's own definitions: behaviour change, needs root review.** A context's own Var now
  survives a program acquisition even when that program's row for the same symbol changed. The 9f82663e1
  install check then keeps the context's definition. This follows the 9f82663e1 root ruling and the AGENTS
  "retain private defs" law.
- **New file outside the row:** test/seon/sci/definition_digest_test.clj, which holds the class regression. No
  owned test file exercised the SCI settlement. Root, please add it to this row or reassign it.
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
| db63995f8 | +3 / −1 | `transcript/render-session`: an acquisition without prompt text is the caught contract refusal, and it now renders as that failure. Before, `utf8-size` threw an NPE and the route returned 500 (web-test :2371). |

Net: +147 / −62 across src and test, so +85.

## Root runs (serially, after adopting through db63995f8)

```
bin/test-check default --test seon.render-simplification-test/an-agent-neighborhood-renders-its-database-reading-units
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --test seon.sci.definition-digest-test/a-repl-definition-is-admitted-with-the-digest-the-writer-recomputes
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

- The same-settlement namespace digest and the `analyzed-form` post-digest merge. See the second and third items
  under "Broken or unproven first".
- `:seon.render/unit` absent from the projection's shape-index, so optional named entries are never supplied.

---
type: research
status: complete
lane: census-6ab6ae2a-f
created: 2026-09-25
---

# Adopted-HEAD reds, seventh census (2026-09-25f)

Root ran 65 serial `bin/test-check default` commands from 11:26 to 12:01 local (UTC−6)
(`tmp/orchestrator/verify-6ab6ae2a.txt`, results in `verify-6ab6ae2a-results.txt.d/`). They produced 167 unique
red names. Twelve commands were green: four `--ns` (background 023, seed-preparation 025, admission-digest 040,
task-ingestion 056) and eight single `--test` reruns (005, 010, 018, 026, 028–031).
The run straddled a publication. Each run records `:seon.test.run/loaded-commit-id`, so this census attributes
each run from that fact, not from file times:

| commands | loaded program | source |
|---|---|---|
| 001–048 | `6ab6ae2a` | the checkout at 3b1091d3b (11:23:50), adopted at 11:25 (`lane-fix-task-gate-publication` :88) |
| 049, 050 | `6ab6ae2a`, **stopped `:program-unknown`** | S1 was committed at 11:49:24 and published while these ran |
| 051 | no run recorded | "published but not yet adopted"; every member is long |
| 052–065 | `6ab6b447` | 91eb8e4a5 (S1). It also carries S0 (eb7e64ffa) and slice 2 (0a332e8fc); nothing between 3b1091d3b and 91eb8e4a5 was adopted separately |

**Method.** MCP `eval_clj`, JVM mode, read-only, in the throwaway namespace `census-f-scratch` (removed
afterwards), on default pid 35446. The census read four things:
- `seon.test.runner/run-results` (runner.clj:1800) for all 65 runs: 192 ms.
- `seon.test.runner/latest-results` (runner.clj:1808) for census e's 290 reds: 133 ms.
- The member executions inside census e's window (15:24–16:32Z) for the 40 NEW names: 102 ms.
- One digest probe: 0.3 ms.

It ran no tests, no init and no writes. Working files are in `tmp/census-6ab6ae2a-f/`:
- `assign.tsv`: class, STILL/NEW, census e class, status, commands and name for each red.
- `full.json`: the failure text.
- `e-fate2.tsv`: what happened to each of census e's 290 reds.
- `loaded.edn`: loaded commit and stop cause for each run.
- `final.py`: the assignment script.

## Broken things first

1. **One fixture helper causes 47 of the 167 reds** (class DIGEST-NS, 15 of them NEW). The cause is proven at
   the REPL. `test-support/revised-row` (test_support.clj:752–764) re-digests every `program-row` or
   `program-fn-row` with the namespace row *pulled from the pre-write database*. Fixtures write
   `[(namespace-row db ns) (program-row db …)]` in one transaction, so the pull answers nil. The writer
   (`write-digest-error`, db.clj:4220) digests with the namespace row that the same transaction stores.
   - Probe on `sample.context/f`: the fixture digest `f78f4ebf…` equals `(definition-digest-of p row nil)`.
     The writer's `c9a0e670…` equals `(definition-digest-of p row nsrow)`. These are exactly the two values
     `db-test/one-owner-digests…` reports (db_test.clj:2070).
   - It was introduced by 210a443bb (10:36). Before that commit, `program-row` returned `source-rows`' own
     digest, computed with the namespace row passed in explicitly. 8ae207099 kept the same pull.
   - This is the whole of test-failure-facts (16 of 16) and call-preparation (7 of 7). It is also 8 of db-test's
     16 reds, 5 of fn-test's 13 and 3 of program-test's 6.
2. **A scratch copy of `seon.sci.eval` loaded into default's JVM broke whole-population arming for 4
   instrument-test members** (class PROBENS; all 4 were green in census e).
   - The copy is `m1probe.evalcopy`, loaded by the S1 lane (`lane-impl-repl-m1-2026-09-25.md`:21–22, :194). Its
     contracts name `:seon.sci.eval/restore-selector` and `:seon.sci.eval/restoration-step`, which were
     unpublished until 6ab6b447.
   - Cold arming at 11:47 (command 045, on 6ab6ae2a) failed with `:malli.core/invalid-schema`.
   - The namespace is gone now (probe: `[]`). RERUN.
   - Root question: should `instrument/apply!`'s population exclude scratch namespaces that no program row
     declares? A lane probe must not be able to redden the whole arming census.
3. **Nine requests stopped at the 120 s request bound.** They are 001, 009, 011, 012, 014, 019, 053, 064 and
   065. `--changed seon.program/shapes-in` (065) selected about 1,309 members: it executed 66 and left 1,243
   pending. 80 of census e's reds are unmeasured only because they were pending. That includes 24 in
   cluster.turn-test, whose request 001 was held for 114 s by one hang (owned by fix-turn-hang-opus).
4. **Four real hangs** (class HANG), with the request budget left when each body started: cluster.turn-test
   a-batched-turn (114,141 ms, owned by fix-turn-hang-opus), web-debug turn-details (83,969 ms), agent-test
   routing-conservation (32,524 ms) and turn-test virtual-turns (32,785 ms).
5. **23 members are over 10 s** (§BOUND list). Each needs an issue under the >10 s rule. The largest are
   rereads stale-read (63.8 s), cluster.turn-test function-install (52.9 s), rereads failed-evaluations
   (49.9 s), agent-test restamp-recovery (45.6 s), sci.eval base-context-bindings (31.5 s) and fn-test
   prebuilt-manifest (30.9 s).
6. **The P4 regression from writer-digest round 2 has never been green.** `db-test/a-store-without-program-schema-
   admits-its-writes` (added by 3c300e5d5) and the three blob-test members now pass the digest check. They then
   hit the old `unowned-entity` rule (db.clj:3663, from 74d020fc6). The fixture writes the bare dial map
   `{:seon.config.eval.result/blob-threshold 4096}`, which has no identity (class UNOWNED).

## 1. Split

**Census f reds (167):** 127 STILL (in census e's 290) and 40 NEW.
- Of the 40 NEW, 18 were green in census e's window and 22 have no execution there.
- Of those 22, 5 are tests added since census e: 3c300e5d5, 58d953b7e, 40dae3514, 9bd944d45 and 8ae207099.
  3d0e68452 also added the render-simplification neighborhood regression.

**Census e's 290 reds, re-read here:**

| fate | n | notes |
|---|---:|---|
| STILL red | 127 | several with a changed first failure (§2) |
| **FIXED** | 71 | 51 executed green in this run and 20 reused green evidence for unchanged inputs |
| unmeasured: pending | 80 | 24 cluster.turn-test, 9 agent-test, 8 sci.eval, 6 prompt-test, 4 each for seed-preparation, render.value, fn-test and mcp, and the rest in commands this run never asked for as `--ns` |
| unmeasured: long, excluded | 7 | agent-test ×3 (LOOP), one-request ×3, branch-execution ×1 |
| not requested | 5 | config fresh-jvm, my.catalog ×2, effect-attempt, value-test map-beyond-the-child-bound |

**FIXED by class** (from `e-fate2.tsv`):

| census e class | n | fixed by |
|---|---:|---|
| R-DIGEST | 40 | the fixture digest helpers; 3fa1465b2 and round-2 writer checks 58d953b7e/9bd944d45. They cover effect-test ×11, db-test ×8, reset-edges ×3, reach ×2, program ×2, instrument ×2 and my.program(-mutation) ×3 |
| RDISPATCH | 5 | the render hand-off (3d0e68452, d315faa6f, ff4de4449): render-simplification ×3 and web-debug agent-identity and block-reference |
| GUARDFORM | 4 | test-rows e (193d91391) |
| HANDROW | 4 | 10d1a53ba: my.note ×3 and background |
| SHAPE | 4 | 2a40d478c: turn-loop refused-opening and committed-set; turn-test refused-form and wake-meeting |
| SEEDROW | 3 | test-rows e |
| WORLD | 3 | 049df4a78: oversight, config apply-replaces, sci.eval forks |
| FLAT | 3 | 1a3027f6c / 4f6159e63: sci.eval multi-arity and runtime-metadata; problems missing-model |
| DRIFT | 2 | error-test contract-violations (the H8 race did not recur); turn-test refused-turn-write |
| RENDER, PROBE, FNSYM | 3 | web-test session-acquisition (db63995f8), db-test system-write-never-acknowledged, edit changed-programs |

Census e's classes WDIGEST and TURN are **not** fixed. WDIGEST's crash is gone (3c300e5d5), but those members
now fail as UNOWNED. For TURN, cluster.turn-test was not measured past its hang.

### NEW reds (40) and the commit that made each red (read from the diffs)

| member | class | census e | cause |
|---|---|---|---|
| source-test tombstone; db-test a-program-row-keeps, a-write-seeds, render-declarations, required-program-relations; fn-test arity-mismatch, contract-findings; problems a-fault-resolves; entity-pairs ×2; test-failure-facts publication-replaces | DIGEST-NS | green | **210a443bb** (revised-row re-digests with the pre-write namespace), kept by 8ae207099 |
| call-preparation absent-supplier; db-test evidence-on-a-declaration (58d953b7e), one-owner-digests (40dae3514), ordered-seed-occurrences (9bd944d45) | DIGEST-NS | unmeasured or new | 210a443bb. The three db-test regressions were written against the same helper, so none has been green |
| instrument ×4 | PROBENS | green | none: S1's `m1probe.evalcopy` scratch load (11:47) |
| db-test an-as-of-view (62.8 ms vs 50 ms); schema-test component-bearing (10.7 s) | BOUND | green | none: load. Remeasure |
| sci.definition-digest a-repl-definition (5.8 s) | BOUND | new (8ae207099) | its own first measurement |
| ai-test stop-is-effective (294 ms left); web-test pass-oracle (963 ms left) | BUDGET | green / unmeasured | none: started under 1 s of request budget |
| db-test a-store-without-program-schema | UNOWNED | new (3c300e5d5) | 3c300e5d5's regression meets the older unowned-entity rule |
| issue-test indexed-issues, cited-identities | DOCDRIFT | unmeasured | 7c1483561 (09-21) and d799e6ff4 (09-15) deleted the real notes the tests read |
| issue-test a-token-naming | HANDROW | unmeasured | predates e: the fixture writes symbol `seon.db/*conn*` as `:seon.issue/id` (issue_test.clj:207) |
| issue-test detector-only | DIGEST-EDIT | unmeasured | predates e (79b6d9abd refuses the digestless `[:db/add … :seon.fn/doc]` at issue_test.clj:367) |
| issue-test unchanged-adoption | CUSTODY | unmeasured | predates e |
| wake-test declared-attribute, unindexed | HANDROW | unmeasured | predates e: the `extra-schema` row lacks `:seon.program/definition-digest`, required since f27b96c19 (09-22) |
| wake-test closed-render, non-accepting, routed-attribute, route-render property | LOOP | unmeasured | first measured: the latch never arrives (20.4 s each) |
| render-simplification neighborhood | RDISPATCH | new (3d0e68452) | its own regression asserts no `:seon.error/value` unit, but the walk emits a declared distance-cap elision |
| evaluate-sources one-turn | DRIFT | unmeasured | predates e: a `with-redefs [ai/complete]` stub |
| evaluate-sources ordered-evaluation; agent-test prompt-refusal | SHAPE | unmeasured | predates e |

On the S1 half (052–065), no NEW red's text names S1's code. The four wake-test LOOP members are the only
S1-adjacent candidates, and nothing links them to it.

## 2. Classes, ranked

Ranking: a fix that clears many reds or unblocks measurement first, then platform FIX-SRC, then by count. Each
first line is quoted from the run output.

### 1. DIGEST-NS: the fixture digest ignores the namespace its transaction stores (47; 32 STILL, 15 NEW), FIX in test_support

- **Members:**
  - test-failure-facts ×16 (every member).
  - call-preparation ×7.
  - db-test ×8: a-program-row-keeps, a-write-seeds, arity-components, evidence-on, ordered-seed, render-declarations,
    required-program-relations and one-owner-digests.
  - fn-test ×5: shape/none, mismatch, contract-findings, gates and output.
  - program-test ×3: held-branch digest-map, positional and typed-cross-namespace.
  - entity-pairs ×2, bootstrap intent-membership, cluster.source tombstone, instrument host-diagnostics,
    problems a-fault-resolves, render-simplification unchanged-retained, schema-usage-guard generic-deletion.
- **First line:** `Fixture write was refused at the write: The stored definition digest of [:seon.fn/sym
  sample/supply-marker] is not the one its definition produces. Write the row its canonical writer produced.`
  In each case the transaction opens with `{:seon.ns/name …}`.
- **Producer:** test/seon/test_support.clj:752–764 `revised-row`:
  `(some->> namespace-name (vector :seon.ns/name) (db/pull database '[*]))` returns nil before the write.
  - For a namespace already stored, the two readings agree. Probe on `seon.render.walk`: the `pull '[*]` context
    equals the `declaration-value` context.
- **Verdict:** FIX in the test helper. Production is right: the writer's digest follows `definition-digest-of`'s
  contract ("the namespace row the same transaction leaves stored", db.clj:4204).
- **Fix sketch** (about 4 lines):
  - `program-row` (test_support.clj:782) already builds `(namespace-row database ns)` for `source-rows`. Give
    that same row to `revised-row`, adding a third arity `[database row overrides namespace-row]`.
  - Keep the pull only as the fallback when no row is given.
  - Class regression: `db-test/one-owner-digests…` (already written, currently red).
- **After the fix:** rerun test-failure-facts, call-preparation and db-test first. Program-test held-branch and
  typed-cross-namespace, instrument host-diagnostics and schema-usage-guard generic-deletion build rows through
  other helpers. They join DIGEST-PROD if they stay red.

### 2. DIGEST-PROD: a production settlement stores a digest its final row does not produce (5, STILL), probe, then FIX-SRC

- **Members:**
  - turn-test batch-settlement, settlement-refuses-divergent and unresolved-test-subjects. Each goes through
    `[:db.fn/call #'seon.turn/receipt-settle-call …]`.
  - program-test changed-runtime-redeclaration and opening-basis-divergence. Both go through `seon.fn/analyze-form`
    and then `seon.turn/row-tx` (program_test.clj:379–388), with the namespace already stored first (:873, :913).
- **First line:** `The stored definition digest of [:seon.test/sym fixture.pending/target-test] is not the one its
  definition produces.`
- **Producer, candidate:** src/seon/fn.clj:1083–1147 `analyzed-form`. It `merge`s `:seon.fn/calls`,
  `:references`, `:keywords`, `:writes`, `:call-arities`, `:invokes`, `:seon.test/subject` and seed keywords onto
  `program-row` while keeping the digest the row arrived with. The comment at :1136–1138 says so. The
  render-handoff landing names the same gap (lane-fix-render-handoff :24–29). src/seon/turn.clj:1212 `row-tx`
  is the other candidate.
- **Probe:**
  `(let [[_ r] (seon.fn/analyze-form db src ns row)] [(:seon.program/definition-digest r) (db/definition-digest-of (db/carried-projection db) r (ns-row db ns))])`
  on the program-test fixture.
- **Fix sketch:** digest once, after the merge, through `db/definition-digest-of` (about 3 lines in
  `analyzed-form`). Only if the probe shows a mismatch.

### 3. RDISPATCH: renders still miss the declared pair (13; 12 STILL, 1 NEW), probe → FIX-SRC / UPDATE-TEST

- **Members:**
  - render-simplification: authored-source, distance-spends, renderer-invocation, shared-invocation-cache and
    neighborhood (NEW).
  - web-debug: reverse-declarations, saved-history, agent-page thinking and outline-toggle.
  - entity-pairs: declared-database-input and viewing-agent's-own-renderer.
  - render.retained: equal-committed and adoption-of-unrelated.
- **First lines:**
  - web-debug gives the floor map printout
    `"{:db/id 62645, :seon.cluster.eval/at … :seon.eval/shown \"The component's shown text.\" …}"` instead of
    `"my.agents.history-probe=> (my.plan/plan {})\n#:seon.repl{…}"` (web_debug_test.clj:406, :487, :1028). The
    evaluation's declared REPL pair is not selected.
  - renderer-invocation: `render-ai` returns `{:seon.db/invalid-read true :seon.db/refused-read-operation seon.db/q
    …}`. It is raised inside `call-preparation/derive-snapshot` (call_preparation.clj:453), which is reached from
    `hook` :1537 and `render/invoke-selected` :1104 (render_simplification_test.clj:544).
  - entity-pairs viewing-agent: `seon.cluster.agent/render-identity-ai` is selected, not
    `my.agents.viewer/agent-view` (:253).
  - Cache counts are doubled (`{:discoveries 2 :invocations 2}`, :405, :420; `previews` 2, :805).
  - neighborhood: the only error unit is a declared distance-cap elision whose value carries
    `:seon.ns/requires [nil my.message …]`. A nil require is itself suspect; its producer is the agent namespace
    seed.
- **Producers:**
  - src/seon/render.clj `declared-stage` (:647) and `invoke-selected` (:1070–1104).
  - src/seon/call_preparation.clj:440–453 `derive-snapshot`. It queries through a database the render runtime
    supplies without a carried projection. The render-handoff landing lists this seam as root's call
    (lane-fix-render-handoff :34–60).
- **Verdict:**
  - neighborhood: UPDATE-TEST (exclude declared `:seon.print/bound-by` elisions). Then probe the nil require.
  - The rest: probe `(#'seon.render/selection request :seon.render/ai)` for the web-debug history-probe
    evaluation, and read `:seon.render/refused` candidates.
  - Owner: the render-dispatch lane.

### 4. HANDROW + UNOWNED: fixture rows the complete-entity writer refuses (13; 9 STILL, 4 NEW), UPDATE-TEST

- **UNOWNED ×4** (blob ×3, db-test a-store-without):
  - `Complete component validation refused: unowned-entity … #:seon.config.eval.result{:blob-threshold 4096}`.
  - The rule is db.clj:3663–3664 and it is correct.
  - Fixture fix: install an identity attribute (`:seon.config/cluster`, unique identity) in the raw schema and
    write `{:seon.config/cluster "blob-test" :seon.config.eval.result/blob-threshold 4096}` (blob_test.clj:33–45;
    db_test a-store-without). blob.clj:72 reads the dial from any entity. About 4 lines each.
- **wake-test ×2:** `required key :seon.program/definition-digest … Entity: #:seon.schema{:key
  :seon.cluster.wake-test/notice}` (wake_test.clj:376, :412). Build the row through `schema/canonical-schema-rows`,
  as wake_test.clj:277 already does.
- **issue-test a-token-naming:** `:seon.issue/id` is given the symbol `seon.db/*conn*`; a string is required
  (issue_test.clj:207). Give it a string id.
- **agent-call-edges:** 10d1a53ba fixed the agent row. The next failure is `seon.test/reach-digest refused
  test-symbol at []: expected a namespaced symbol, got a string` (11.1 s). The test passes a string
  test-symbol.
- **my.note forget:** `There is no current note named "temporary"`. The add step no longer lands with the new
  message subject. Probe the add return value.
- **Still as census e said:**
  - db-test component-expanded (`missing-component-schema :seon.db-test/component-child`).
  - error-test complete-error-children (`:seon.error-test/manifest-location`).
  - web-debug reverse-blocks (`unowned-entity … {:hidden 62649}`).
  - web-debug outline-origin (`Started issue tests … only the creator may remove tests`).
- **Probable member:** my.plan-api item-and-plan (`transact!` returns no `:db-after` for a hand agent row,
  plan_api_test.clj:76). Use `agent-tx`.

### 5. FLAT: refusal members read or raised in the wrong place (8, STILL), mixed

- **schema-test refused-projection-source:** the lane's update (1a3027f6c) expects
  `seon.schema/projection-from-database`, but the armed refusal comes one call earlier, from
  `seon.db/carried-projection` (schema_test.clj:1498). UPDATE-TEST.
- **db-test every-public-read:** `"This operation requires a carried schema projection."` replaces the upstream
  value (db_test.clj:661). FIX-SRC in seon.db's read entrance order (db.clj:~1241), unchanged since census e.
- **db-test uncarried-read:** no `projection-fallback` line (:184–191).
- **db-test unknown-read:** `(:seon.error/offending data)` is nil (:2208–2209).
- **db-test nested-native:** `#{:seon.sci.admit/opaque :seon.sci.admit/projection-error}` (:1109).
- **problems missing-projection:** `:seon.db/refused-read-operation` is nil (:575).
- **web-test start-refuses:** `database-effective-config requires configuration naming its cluster` (:2159).
- **bootstrap absent-intent:** `:seon.config/error-key` is nil (:369).

The six items after the first two are as census e described them (§9 there). Its fix sketches stand.

### 6. SHAPE (9; 7 STILL, 2 NEW), UPDATE-TEST or probe

- **agent-test prompt-refusal (NEW):**
  - The test queries `[?error :seon.cluster.prompt/refused …]`. Datahike has no such attribute installed
    (`:installed-declaration nil`).
  - resources/seon/schemas/seon.cluster.prompt.edn:32 declares it only as a returned refusal-value member.
  - UPDATE-TEST: read the refusal the turn returned or the stored error's message, not an unstored member.
- **evaluate-sources ordered-evaluation (NEW):** `(:seon.turn/refused refused)` is nil, not
  `:seon.turn/recorded-content-conflict` (evaluate_sources_test.clj:204; the producer is turn.clj:1508). Probe
  017e95e02's settled-namespaces change.
- **edit:**
  - edit-refusals: `my.edit/form! refused return value at [:my.edit/path]` (FIX-SRC src/my/edit.clj).
  - form-edit-refs: `seon.program/declaration-at refused declarations at [1]: … got nil` (edit_test.clj:378).
- **sci.eval:**
  - declared-row: the shape differs (:1259).
  - evaluation-custody: `(:seon.db/invalid-read …)` is nil (:1807).
- **turn-test:** open-turn-read-refusal (a `:seon.audit/poison` invalid-read, :957) and fault-unit (`Faults (1)`,
  :1951).
- **my.program-mutation native-mutations:** the fork's `seon.turn` namespace changed, and `:seon.error/expected`
  is nil (:72, :74).

### 7. LOOP: latch or terminal events never published (6; 2 STILL, 4 NEW), probe

- **Members:**
  - agent-test failed-turn-transform (20.4 s).
  - agent-test restamp-recovery (45.6 s). Its message is `Disarm could not record the interrupted turn …
    :seon.error/operation seon.db/transact! "This wri…"`, which matches the open fault `did not publish proc stop
    acknowledgement within 20000 ms` (agent.clj:1223) seen in runtime_status.
  - wake-test closed-render ("render fault"), non-accepting-route ("route fault"), routed-attribute ("search
    transaction report"), all 20.4 s, and the route-render property (wake_test.clj:720).
- **Probe:** restamp-recovery's refused write first (read `:seon.error/data` of the committed error). The four
  wake members share one graph fixture. Run one of them alone after HANDROW's wake fix lands, because the
  registration refusal may leave the listener unarmed.

### 8. ERRREC (6, STILL), unchanged from census e §12

error-test declared-schemas-persist (:1265), identity-and-occurrences (:154), row-acquisition (:73); agent-test
install-gate (:1284); turn-loop clean-last-form (:783), refused-phase (:634). Owner: src/seon/error.clj
`recording`/`steward`, src/seon/turn.clj phase-failed. Probe one member first.

### 9. BOUND: completed over the declared bound (9; 6 STILL, 3 NEW), measure

| member | ms | d/e |
|---|---:|---|
| problems absent-facts-produce-no-entries | 25,116 | STILL |
| turn-test transitions-agree (also a property failure, :1801) | 21,000 | STILL |
| turn-test recovery-preserves-terminal-receipts | 20,837 | STILL |
| program-test indexed-and-evaluated | 13,076 | STILL |
| effect capability-reachability | 11,191 | STILL |
| schema-test component-bearing-row | 10,716 | NEW, green in e |
| issue-test worker-opening | 10,643 | STILL |
| sci.definition-digest a-repl-definition | 5,802 | NEW |
| db-test an-as-of-view (inner 50 ms bound) | 62.8 | NEW, green in e |

Other lines over 10 s, inside other classes:
- rereads stale-read 63,790 and failed-evaluations 49,932.
- cluster.turn-test function-install 52,875.
- agent-test restamp 45,620.
- sci.eval base-context-bindings 31,513 (also a real failure: `clojure.test/*` bindings unpublished,
  eval_test.clj:739).
- fn-test prebuilt-manifest 30,949.
- turn-loop prompt-and-call 26,178.
- the five LOOP latches at about 20.4 s.
- web-test next-turn-context 17,870.
- entity-pairs declared-database 11,181.
- agent-call-edges 11,118.

Every one needs an issue.

### 10. Small classes

- **HANG (4, STILL):** listed under Broken 4. `bounded-result` (test.clj:209–312) correctly keeps the branch. FIX
  each body's wait. Only cluster.turn-test is owned (fix-turn-hang-opus). It is just counted here: 2 reds, 45
  members pending behind it, and function-install TURN at 52.9 s.
- **TURN (2, STILL):** cluster.turn-test function-install (`(:seon.fn/spec installed)` is nil, :384); turn-loop
  prompt-and-call (`@requests` is `[]`, :515). The owner is fix-turn-hang-opus and the turn-census-e landing
  (d667e299a).
- **PROBENS (4, NEW):** Broken 2. RERUN seon.instrument-test.
- **KONDO (4, STILL):** as in census e: fn-test ×3 need `(seon.fn.analyzer/analyze-dependencies!)`, and sci.eval
  `s3.missing` needs FIX-SRC in src/seon/fn.clj host classification.
- **BUDGET (3):** ai-test stop (294 ms left), web-test pass-oracle (963 ms), fn-test retracted-identities
  (631 ms). RERUN each in its own `--ns`.
- **DOCDRIFT (2, NEW):** issue-test indexed-issues (`(= 3 (count selected-real))` gives 2, issue_test.clj:33) and
  cited-identities (`complete-publication-takes-seventy-seconds has no opened instant`, :159). The tests read
  real `docs/seon/issues/*.md` files that 7c1483561 and d799e6ff4 deleted. UPDATE-TEST: index fixture note text
  (as `unchanged-issue-adoption` already does), never the live docs tree.
- **CUSTODY (2):**
  - issue-test unchanged-adoption (NEW): `This write names branch :agent-fd8832e30037, which is not the writing
    cluster's branch :agent-fb21d6cd5754` from `seon.issue/index-tx` (issue_test.clj:94, :105). The test adopts
    through a second connection under the first's custody.
  - db-test write-naming-another (STILL, :1728).
  - Probe which connection `seon.issue/adopt!` (issue.clj:1189) binds.
- **DIGEST-EDIT (2):** issue-test detector-only (issue_test.clj:367) and rereads failed-evaluations
  (rereads_test.clj:164) write a bare `[:db/add [:seon.fn/sym …] :seon.fn/doc …]`. UPDATE-TEST: use
  `test-support/revision` (test_support.clj:766). detector-only's second failure, `(string? subject)` given a
  symbol (:345), is a stale string expectation.
- **DRIFT (2):**
  - evaluate-sources one-turn: `drift-added ["seon.ai/complete"]`. A `with-redefs` stub breaks the law.
    UPDATE-TEST: use the injected local provider, as 146b8a5fb did for agent-test.
  - seed-admission seed-entries: `drift-removed` about 100 `seon.sci.eval/*` Vars at about 11:46:50. That points
    at a concurrent unarm or reload of seon.sci.eval, and the S1 lane's REPL work is the only candidate seen.
    Unverified. RERUN.
- **RENDER (2, STILL):** web-test coalesce (7 derivations for 6 commits, :1794) and next-turn-context (:2478,
  17.9 s).

### PROBE: unconfirmed owners (24, STILL). One REPL form each

- fn-test:
  - contracted-rows: the spec, arities and analysis transactions differ (:709).
  - indexing-resolves: 2 `packaged-forms` calls, not 1 (:2682).
  - prebuilt-manifest: `:seon.fn/index-phase` is nil (:1060).
  - publication-writes: `Missing schema declaration :sample.definition-digest/value`.
- sci.eval:
  - a-turn-fork (:1407).
  - both-entrances: `:sci.impl/interrupt` escapes (:2279).
  - one-unloadable-row: `db/pull` of a nil id (:1559).
  - base-context-bindings (:739).
- schema-test:
  - incremental-projection (:1640, `:replaced-contract seon.call-preparation/row-attributes`).
  - error-declared-storable: `:seon.turn/compaction-agent-id` has `:db.unique/identity` (:1465).
  - one-derivation (:1314, the law violation from 1625fb9bc).
- schema.datahike-test ×4:
  - native parity (:122).
  - upsert-identity member `:seon.program/partition` versus `:seon.turn/id` (:33).
  - `declaration-changes` given nil (schema.clj:2729).
  - `:db.secondary/only` refusal.
- reset-edges refused-notes: retracts about 30 real issue ids. It reads the live issue set, so it is a DOCDRIFT
  candidate.
- reset-edges stored-many-members (:130).
- rereads stale-read: the basis advances by 1 (:97).
- reach-test indexed-reach: `reach-row` differs (reach_test.clj:52).
- publication-delta schema-referrers (:152).
- effect oversized-request: the message lacks `:seon.config.eval.result/max-bytes` (:779).
- effect detached-work: `seon.flow/submit! refused work-launcher … got nil` (effect.clj:881).
- schema-usage-guard nonidentical-change: `Long cannot be cast to String`.
- my.plan-api item-and-plan (probably HANDROW, §4).

## 3. The focus namespaces

| namespace | reds (fail/err in summary) | classes | reading |
|---|---|---|---|
| test-failure-facts | 16 (12/26) | DIGEST-NS ×16 | one helper fix should clear it; nothing else is visible behind it |
| issue-test | 6 (25/2) | DOCDRIFT 2, HANDROW 1, DIGEST-EDIT 1, CUSTODY 1, BOUND 1 | 21 of the 25 fails are the two DOCDRIFT members looping over deleted notes |
| db-test | 16 (32/9) | DIGEST-NS 8, FLAT 4, UNOWNED 1, CUSTODY 1, HANDROW 1, BOUND 1 | half is DIGEST-NS; the FLAT four are the real remaining work |
| schema-test | 5 (14/0) | PROBE 3, FLAT 1, BOUND 1 | 12,767 passes; the 14 fails are 5 members, 3 of them census-d probes never taken up |
| error-test | 4 (15/1) | ERRREC 3, HANDROW 1 | unchanged since census d; needs the error.clj probe |
| render-simplification | 6 (15/0) | RDISPATCH 5, DIGEST-NS 1 | 3 fixed by the hand-off; the rest are the snapshot-projection seam and cache counts |
| web-debug | 7 (13/3) | RDISPATCH 4, HANDROW 2, HANG 1 | 2 fixed by ff4de4449; the eval REPL pair is still not selected |

### Commands that report `passed? false` with 0 fail and 0 error, or that ran nothing

| cmd | namespace | why |
|---|---|---|
| 013 | seon.test.one-request-test | all 3 executed are green; **7 long members were excluded** (no `--include-long`). An excluded long member is an unfulfilled obligation and never reads as green (test.clj:2326–2331). Not a defect |
| 062 | seon.oversight-test | 2 executed and 4 reused green; **1 long member excluded** (`a-booted-cluster-tells-its-live-fleet-story`). Not a defect |
| 049 | my.program-test | 2 executed green; the run is `:seon.test.run/stopped :program-unknown` with 3 pending. S1 (6ab6b447) was published while it ran, so `call-detecting-program-change` pushed back (test.clj:216, :291). This is correct detect-and-push-back |
| 050 | my.program-mutation-test | **0 executed**, stopped `:program-unknown` before its first body: the same publication. "no reuse: source 6ab6b447 … published but not yet adopted" |
| 051 | seon.sci.branch-execution-test | **0 executed, no run recorded.** All 9 selected members are declared long, so nothing was selectable without `--include-long`, and reuse was refused because the published source was not adopted |

Also, 046 (call-preparation, 2) and 048 (bootstrap, 1) list pending members at 30.9 s and 18.7 s, well inside
the bound. `:seon.test/pending` also includes members held by another live run's reservation (test.clj:2299,
:2333). The candidates are branches still held by earlier unexited bodies. PROBE: read `::held` for those runs.

## 4. Class → owning files

| rank | class | n | verdict | owning files |
|---:|---|---:|---|---|
| 1 | DIGEST-NS | 47 | FIX test helper | test/seon/test_support.clj:752–764 `revised-row`, :782 `program-row` (from 210a443bb, kept by 8ae207099) |
| 2 | DIGEST-PROD | 5 | probe → FIX-SRC | src/seon/fn.clj:1083–1147 `analyzed-form`; src/seon/turn.clj:1212 `row-tx`, `receipt-settle-call` |
| 3 | RDISPATCH | 13 | probe → FIX-SRC; UPDATE ×1 | src/seon/render.clj :647 `declared-stage`, :1070–1104 `invoke-selected`; src/seon/call_preparation.clj:440–453; test/seon/render_simplification_test.clj (neighborhood) |
| 4 | HANDROW + UNOWNED | 13 | UPDATE-TEST | test/seon/blob_test.clj:33–45; test/seon/db_test.clj (a-store-without, component fixture); test/seon/cluster/wake_test.clj:376, :412; test/seon/issue_test.clj:207; test/seon/agent_call_edges_test.clj; test/my/note_test.clj; test/seon/error_test.clj; test/seon/render/web_debug_test.clj; test/my/plan_api_test.clj:76 |
| 5 | FLAT | 8 | FIX-SRC ×1, UPDATE ×1, probe ×6 | src/seon/db.clj read entrance (~1241); test/seon/schema_test.clj:1498; test/seon/db_test.clj:184–191, :661, :1109, :2208; test/seon/problems_test.clj:575; test/seon/render/web_test.clj:2159; test/seon/bootstrap_test.clj:369 |
| 6 | SHAPE | 9 | UPDATE / FIX-SRC (edit.clj) | test/seon/cluster/agent_test.clj:637; test/seon/cluster/evaluate_sources_test.clj:204 (src/seon/turn.clj:1508); src/my/edit.clj; test/seon/edit_test.clj:378; test/seon/sci/eval_test.clj:1259, :1807; test/seon/turn_test.clj:957, :1951; test/my/program_mutation_test.clj:72–74 |
| 7 | LOOP | 6 | probe | test/seon/cluster/agent_test.clj; test/seon/cluster/wake_test.clj; src/seon/cluster/agent.clj:1223 (proc stop acknowledgement) |
| 8 | ERRREC | 6 | probe → FIX-SRC | src/seon/error.clj (recording, steward); src/seon/turn.clj (phase-failed, escalation) |
| 9 | BOUND | 9 | measure; issues >10 s | each body's operation |
| 10 | HANG | 4 | fix body waits | test/seon/cluster/turn_test.clj (fix-turn-hang-opus); test/seon/render/web_debug_test.clj turn-details; test/seon/cluster/agent_test.clj routing-conservation; test/seon/turn_test.clj virtual-turns |
| 11 | PROBENS | 4 | RERUN; root ruling | src/seon/instrument.clj population (scratch namespaces) |
| 12 | KONDO | 4 | operational + FIX-SRC ×1 | `(seon.fn.analyzer/analyze-dependencies!)`; src/seon/fn.clj host classification |
| 13 | BUDGET | 3 | RERUN | the root command set; `--changed` request sizing |
| 14 | DOCDRIFT | 2 | UPDATE-TEST | test/seon/issue_test.clj:20–40, :140–160 |
| 15 | CUSTODY | 2 | probe | src/seon/issue.clj:1189 `adopt!`; test/seon/issue_test.clj:94–117; test/seon/db_test.clj:1728 |
| 16 | DIGEST-EDIT | 2 | UPDATE-TEST | test/seon/issue_test.clj:367; test/seon/rereads_test.clj:164 |
| 17 | DRIFT | 2 | UPDATE-TEST / RERUN | test/seon/cluster/evaluate_sources_test.clj (`with-redefs`); test/seon/fn/seed_admission_test.clj |
| 18 | TURN | 2 | owned | fix-turn-hang-opus |
| 19 | RENDER | 2 | FIX-SRC | src/seon/render/web.clj (coalesce); test/seon/render/web_test.clj:1794, :2478 |
| — | PROBE | 24 | probe | per item in §2 |

## 5. Closest to fully green

- **Green now:** issue.task-ingestion (8/8), test.admission-digest, background, test.seed-preparation (FIXED since
  e) and render.web-test session-acquisition (FIXED).
- **Green but with unproven long members:** one-request-test (7 long) and oversight-test (1 long). One
  `--include-long` run each.
- **One red, one class:**
  - cluster.source-test (DIGEST-NS), sci.definition-digest (BOUND 5.8 s), fn.seed-admission (DRIFT, rerun), my.note (HANDROW),
    agent-call-edges (HANDROW), ai-test (BUDGET), my.program-mutation (SHAPE), cluster.publication-delta (PROBE),
    my.plan-api (PROBE, likely HANDROW).
- **One class, several members:**
  - **test-failure-facts (16) and call-preparation (7) are both entirely DIGEST-NS.** They are the largest wins
    from one 4-line helper fix.
  - blob-test (3, UNOWNED).
  - render.retained-test (2, RDISPATCH).
  - edit-test (2, SHAPE).
  - instrument-test (5 = PROBENS 4, which should pass on rerun, + DIGEST-NS host-diagnostics 1).

## Verification limits

- The census read results only. The one executed hypothesis is DIGEST-NS: the nil-namespace digest reproduces
  both values the class regression reports. All other producers and fix sketches are inferred from failure text,
  source and diffs.
- "FIXED" includes 20 members whose green came from reuse of evidence for unchanged inputs, not a fresh
  execution in this run.
- The attribution of PROBENS to `m1probe.evalcopy` rests on the arming refusal naming S1's unpublished schema
  keys at 11:47, on the population printed in cold-arming, which includes `#'m1probe.evalcopy/copy-base-ctx`, and
  on the S1 landing's account. The namespace was already gone when this census ran.
- The seed-admission `drift-removed` attribution to a concurrent seon.sci.eval unarm is unverified.
- A NEW red counts as green in census e only when a run in census e's window (15:24–16:32Z) executed it green.
  The 22 others are first measurements, whatever their cause's age.
- RESET NEEDED: none.

## Root runs (serially, after the named fixes)

```
bin/test-check default --ns seon.instrument-test                    ; PROBENS rerun (m1probe gone)
bin/test-check default --ns seon.test-failure-facts-test            ; DIGEST-NS after the revised-row fix
bin/test-check default --ns seon.call-preparation-test              ; DIGEST-NS
bin/test-check default --test seon.db-test/one-owner-digests-a-definition-with-its-stored-namespace
bin/test-check default --ns my.program-test                         ; stopped :program-unknown, rerun
bin/test-check default --ns my.program-mutation-test                ; stopped :program-unknown, rerun
bin/test-check default --ns seon.sci.branch-execution-test --include-long
bin/test-check default --ns seon.test.one-request-test --include-long
bin/test-check default --ns seon.fn.seed-admission-test             ; DRIFT rerun
bin/test-check default --ns seon.ai-test                            ; BUDGET rerun
```

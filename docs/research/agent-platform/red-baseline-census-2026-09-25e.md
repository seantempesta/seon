---
type: research
status: complete
lane: census-6ab6920a-reds
created: 2026-09-25
---

# Adopted-HEAD reds, sixth census (2026-09-25e)

Default adopted source `:seon.source/commit-id #uuid "6ab6920a-…"` at 09:24 local
(`lane-fix-publication-invalid-schema-2026-09-25.md:69`). That is the checkout at de254ecb0, committed at 09:25:13.
The four task-ingestion commits that followed it (1de64b45b, 8ccdd694b, d982afb36, 55cebad48) are not in the
adopted program and cannot cause these reds. Root then ran 141 serial commands from 09:25 to 10:31 local
(`tmp/orchestrator/verify-6ab6920a-results.txt`). They executed 1,560 members and produced 290 unique red names
(`tmp/orchestrator/red-6ab6920a.txt`). 120 of those were also in census d's 169.

**Method.** MCP `eval_clj`, JVM mode, read-only, in the throwaway namespace `census-e-scratch`, on default pid 35446.
The census parsed every `.d/NNN.txt` into per-member failure text and read each run's recorded members through
`@#'seon.test.runner/recorded-member-results` (runner.clj:1618) and `seon.test/failure-message` (test.clj:57).
That covered 85 runs in 153 ms. `seon.test.runner/latest-results` (runner.clj:1808) answered census d's 49 other
reds in 54 ms. Census d's `d-status.txt` supplied each member's earlier status. The census ran no tests, no init
and no writes. Working files are in `tmp/census-6ab6920a-reds/`: `assign.txt` has one line per red (class,
STILL/NEW, status in d, name), `fulld.json` holds the failure text and `final.py` is the assignment script.

## Broken things first

1. **19 of 141 requests stopped at the 120 s request bound.** They are .d/007–010, 012, 020, 021, 023, 024, 027,
   030, 044 (104.7 s), 050, 058, 059 (100.6 s), 076, 077, 087 and 091. Every `--changed` request over about 60
   members hit the bound. The members each one never started are unmeasured, not green. Seven reds are only victims
   of this: they started with 0.7–2.5 s of budget left (class BUDGET).
2. **A new writer check crashes on any store without program schema** (class WDIGEST, 3 reds, caused by 79b6d9abd).
   `seon.db/write-digest-error` calls `(d/datoms database :eavt entity-id :seon.fn/sym)` for every changed entity.
   Datahike throws "Bad entity attribute :seon.fn/sym … not defined in current schema" on a raw branch. That is a
   core fault on a legal write, not a fixture defect. It is FIX-SRC.
3. **Seven test bodies never exited** (class HANG). They are host bodies, which have no per-test interrupt
   (test.clj:255–260), so each held its request until the request bound. wake-routing-conservation alone consumed
   117.9 s of request 027.
4. **Every cluster.turn-test turn ends `:error` before any model call** (class TURN, 21 reds). These are first
   measurements: census d had 44 unknown there. The owner is unconfirmed, and one probe is named below.
5. **Seven lines are over 10 s, and 12 more sit at the 20 s latch timeout** (§BOUND, §LOOP). Each needs an issue
   under the >10 s rule. Root files them or links them.
6. **The census d N1 fix is incomplete.** `my.catalog/reads-canned-label` still refuses with "This write names
   branch :agent-eca6606d0056, which is not the writing cluster's branch …" (class CUSTODY).

## 1. Split

| verdict | n | meaning |
|---|---:|---|
| **routed (don't re-diagnose)** | 94 | 86 stale/missing definition digest (fix-digest-fixtures-opus), 8 missing `:seon.program/analyzed-source-digest` on hand rows (same family; confirm with that lane) |
| **STILL** | 97 | in census d's 169 and the same class, except SEEDROW (3), where 50b0e82bc changed the class |
| **NEW** | 99 | not in census d's 169: 16 green in d, 32 unknown in d, 51 never measured in d |

Of the routed 86 digest reds, 37 were green in census d. They are NEW, caused by 79b6d9abd (the writer refuses a
row whose stored definition digest its row does not produce). One more member of that group is
`render.retained-test/adoption-of-an-unrelated-namespace…`: it edits `:seon.fn/source` in place without a digest
(retained_test.clj:200), so it is the same refusal.

### FIXED since census d: 40 verified green on this run

| namespace | n | census d class confirmed fixed |
|---|---:|---|
| seon.ai-test | 13 | N2 with-redefs leak (146b8a5fb) |
| seon.sci.eval-test | 6 | CFG `current-src` (6db5b2451): public-walk, every-public-capability, schema-and-contract-bounded, call-preparation, acquisition-binds, bare-dir |
| seon.config-test | 4 | FLAT (084b12d15): compiler-ignores, explicit-absence, shipped-initialization. N1 (4ebea3977): two-clusters |
| seon.schema-test | 4 | FLAT/PROBE: component-bearing, no-bound-predicates, pulled-forms, render-declarations |
| seon.contracts-compile-test | 2 | N3 checkout-resource skew (rerun on a clean tree) |
| seon.fn-test | 2 | analyzer-edges, incremental-tempid |
| seon.schema.datahike-test | 2 | edn-backed, fixed-tuples |
| seon.render.web-test | 2 | undeclared-incoming-reference, declared-units (7ea24ee67, bf0ae8fbe) |
| agent-situation, maintenance last-collection, entity-pairs issue-walk, clean-world boot-facts, turn-test history-read | 5 | WORLD (749e69fc1), FLAT, RENDER (47d582c10), N5 (829d6909d), N3 |

**R0 is fixed** (9ebdc9cd4). problems-test, web-debug-test, schema-usage-guard-test, test-failure-facts-test and
render-simplification-test all ran this time. Their reds below are first measurements.

**Not re-measured (9):**
- Deleted by landings: `config-test/apply-converts…` and `sci.eval-test/a-configless-database…` (1e23bc1c6).
- render-source-test ×3: not in this command set.
- `web-test/reconnect-is-repaint-wire-test` and `fn-test/unresolved-call-shapes…`: no result in this run.
- `sci.eval-test` evaluator-remains-live and unloadable-host: pending when request 012 hit the 120 s bound.

## 2. Classes (NEW and STILL), ranked

Ranking: platform FIX-SRC first, then classes that block measurement of other classes, then by member count.
Each first line is quoted from the run output.

### 1. WDIGEST: the digest check crashes a schemaless write (3, NEW, 79b6d9abd), FIX-SRC

- **Members:** blob-test binary-content-round-trips, interrupted-staging and large-binary-write.
- **First line:** `Fixture write was refused at the write: Bad entity attribute :seon.fn/sym at (resolve-datom db 1
  :seon.fn/sym nil nil), not defined in current schema`.
- **Producer:** src/seon/db.clj `write-digest-error` (added at db.clj:~4190 by 79b6d9abd). The
  `(d/datoms database :eavt entity-id %)` over `[:seon.fn/sym :seon.test/sym]` runs on a raw branch that
  blob_test.clj:29–45 opens with only `:seon.config.eval.result/blob-threshold` installed.
- **Fix sketch** (about 3 lines): look at the installed schema first,
  `(filter #(get (dbi/-schema database) %) [:seon.fn/sym :seon.test/sym])`, and return nil when neither attribute
  is installed. Class regression: blob-test itself.

### 2. TURN: cluster.turn-test turns end `:error` at open; no model call and no agent evaluation (21, NEW: unknown in d), probe, then FIX-SRC

- **Members:** every cluster.turn-test red except the 2 in FNSYM, the 2 in BOUND and the HANG member. For example
  an-ordinary-turn-advances…, function-install-reads…, a-waiting-disposition…, a-whole-turn-runs-a-REAL…,
  contracted-redefinition…, an-unreadable-reply… and a-turn-delivers….
- **First lines:**
  - `(= 2 (count evaluations))` gives `0` (turn_test.clj:470, :1703, :1251).
  - `(= :closed (:seon.turn.loop/outcome (last reports)))` gives `:error` (:383).
  - The situations are `[:open :open :open]` (:1578), or twelve `:open` (:1738).
  - `(= 3 (count @requests))` gives `0` (:2315).
  - The error facts are `#{[60760]}`, one per pass (:1745).
- **What is known:**
  - The stubbed `ai/complete` (a `with-redefs` in each body) is never called.
  - Each pass commits one `:seon.error` row and closes `:error`.
  - `seon.turn/turn`'s call phase skips the provider when `(:seon.config.ai/no-provider settings)` holds
    (turn.clj:4321, :4429, :4458, :4479).
  - Default's own config row holds `:seon.config.ai/no-provider true` (probe: `[42401 true "default"]`).
  - web-debug turn-details already renders "Virtual" (transcript.clj:1177–1183) in its fixture world (§WORLD), so
    at least one fixture world does read no-provider true.
  - The other candidate is the unrecognized prompt refusal at turn.clj:4444 (§SHAPE turn-loop
    refused-generated-opening). That refusal is also still an open fault on default.
- **Probe for root (one test, then read the committed error):**
  `bin/test-check default --test seon.cluster.turn-test/function-install-reads-the-case-count-from-cluster-facts`.
  Then read that branch's `[?e :seon.error/id]` message and `:seon.turn.loop/error` from the report. If it is
  no-provider, `provider-targets` (turn.clj:3930) is reading a cluster other than `"turn-test"`, or the fixture
  row inherits it.
- **Fix sketch:**
  - If no-provider: the fixture's `config/apply!` manifest (turn_test.clj:202–219) sets
    `:seon.config.ai/no-provider config/absent`, about 1 line. Check first why `"turn-test"` inherits it.
  - If the prompt refusal: turn.clj:4444 must also treat `:seon.db/invalid-read`, `:seon.schema/expected-value` and
    `:seon.error/at` as refusals, about 3 lines.

### 3. RDISPATCH: renders stop reaching the declared pair (18, NEW; 3 green→red; landed today), UPDATE-TEST plus probe

- **Members:**
  - render.value-test: block-pairs-remain-explicit, declared-producers-still-have-absolute-precedence and
    dir-of-a-large-namespace (all green in d).
  - render-simplification-test: all 8 reds.
  - web-debug-test: agent-identity-groups, block-reference-headers, reverse-declarations, saved-history, agent-page
    comment-as-thinking and outline-toggle.
  - turn-boot root-opening (STILL): "The selected renderer did not return an observation."
- **First lines:**
  - `(= 'seon.error/render-html (#'seon.render/producer request …))` gives `seon.render.value/render-html`
    (value_test.clj:402, :405).
  - `(= (render-pair raw) (render/render-ai …))` gives the floor map printout
    `"{:my.plan.item/done-when …}"` instead of `"Plan step [prepare] 1 open — …"` (value_test.clj:102).
  - `(= database (:seon.db/db argument))` gives `nil` (render_simplification_test.clj:158).
  - `"A:…fixture-a"` appears instead of `"A:…fixture-a:two:H2"` (:454).
- **Producers:** src/seon/render.clj selection, through three commits that landed between 08:52 and 09:15:
  - aa91ecb34: the inline face first.
  - 634d8aaa2: a namespace stage before declared (render.clj:606 `viewing-namespace`, :647 `declared-stage`).
  - b195ae037: the database is a declared input. `:seon.render/unit` becomes `[:map [:seon.db/db {:optional true}]]`,
    11 renderers take the unit, and `render-argument` no longer carries `:seon.db/db`.
- **Verdict:**
  - UPDATE-TEST for render_simplification_test.clj:158, which asserts the retired `:seon.db/db` argument
    (b195ae037 deleted it on purpose).
  - For the rest, probe first:
    `(#'seon.render/selection (render-request connection failure) :seon.render/html)` on value_test.clj:395's
    fixture. It shows whether `declared-stage` rejects `seon.error/render-html` for `:no-same-arity-match` against
    the new unit contract. If it does, that is FIX-SRC in `declared-stage`'s acceptance argument (about 10 lines).
- **Owner:** the render-dispatch-opus lane (its landing notes 11f93a7f9, 549b4d58c, 1d5055245).

### 4. HANG: host bodies that never exit (7; 3 STILL, 4 NEW), FIX in each body's wait

Each entry gives the request budget remaining when the body started:
- agent-test wake-routing-conservation-property: 117,925 ms (STILL).
- agent-test n-agent-parallel-turns-property: 35,232 ms (STILL).
- agent-test pause-during-in-flight-call: 30,643 ms.
- agent-test park-wake-test: 27,873 ms. It also exceeded 41,201 ms against its 5 s bound.
- agent-arming starting-an-issue: 22,025 ms. It also exceeded 42,166 ms.
- cluster.turn-test generated-model-attempt-traces: 17,439 ms.
- sci.eval static-and-runtime-contracted: 9,003 ms (STILL).

The four NEW members were unmeasured or unknown in d.

- **First line:** `Test … did not exit within :seon.test/check-time-limit-ms remainder N ms; thread T remains live
  and keeps its branch until it exits. No further body may start.` (test.clj:296).
- **Producer:** the body's own wait. `bounded-result` (test.clj:230–312) correctly holds the branch until exit. The
  agent-test bodies await flow terminal events that §LOOP shows never arrive. The 146b8a5fb rewrite of
  agent_test.clj (local OpenAI-compatible provider) is the most recent change to those bodies.
- **Verdict:** FIX the body wait (not UPDATE-TEST). Every wait needs a named terminal event and a bound less than
  the remaining request budget. This is the ">10 s" issue set.
- **Fix sketch:** route each `future`/`deref` in agent_test.clj's property bodies through
  `test-support/await-event!` with its 20 s bound, as the §LOOP members already do. About 20 lines. Then rerun
  agent-test alone with `--include-long`.

### 5. LOOP: latch events never published (12; 8 STILL, 4 NEW; 2 green→red), FIX-SRC probe

- **Members:**
  - agent-test: concurrent-arm, disarm-has-a-declared-loud-backstop (21,180 ms > 12 s, green in d),
    disarm-interrupts (green in d), failed-turn-transform, restamp-recovery, routing-conservation-waits,
    routing-proof-rejects and wait-closes.
  - loop-proof: running-fixture and virtual-loop.
  - turn-continue: provider-progress and read-results.
- **First lines:**
  - `The test did not observe its required latch event. :seon.test-support/event
    :seon.cluster.agent-test/interruptible-prelude-entered, timeout-ms 20000`.
  - `The expected terminal database fact did not arrive.` (agent_test.clj:269).
  - `(= {} (:seon.agent/armed @routing))` still holds "racer-a" (agent_test.clj:1109).
  - Durations cluster at 20.4–21.6 s (the 20 s latch), 25.7 s and 40.0 s.
- **Producer:** the turn and call path the agent graph drives. That is the same unconfirmed cause as TURN (no call
  reaches the provider). The disarm pair became red after 146b8a5fb changed their provider from `ai/complete`
  redefinitions to a local server, so an absent provider call would hold every "in-flight call" latch.
- **Verdict:** do the TURN probe first. If the turn never calls the provider, these follow. Otherwise probe
  `disarm-interrupts` alone and read its turn's committed error.

### 6. BUDGET: request-budget victims (7; 1 STILL, 6 NEW), RERUN

- **Members:** bootstrap drive-free (2,450 ms left), call-preparation ×2 (997 and 1,028 ms, green in d),
  agent-identity (2,524 ms), agent-test episode-cap (2,462 ms), agent-test fenced (692 ms, green in d) and web-test
  message-route (926 ms).
- **Verdict:** RERUN each in its own `--ns` request. Nothing here names a defect, except that bootstrap drive-free's
  other run also refused with `seon.render.walk/ordered-episode refused argument 0 at [:seon.repl/candidates 0
  :seon.repl/entry]: expected a map, got nil (bootstrap.clj:701)`. Probe that separately.
- **Structural:** `--changed` over roughly 90 members cannot fit 120 s at 1.3 s per member. Either root splits
  those requests, or `seon.test/run` sizes the request bound from the selected members' declared bounds. The owner
  decides which.

### 7. BOUND: completed but over the declared bound (19; 9 STILL, 10 NEW; 6 green→red), measure

| member | ms | bound | d |
|---|---:|---:|---|
| problems absent-facts-produce-no-entries | 24,870 | 5,000 | unmeasured |
| sci.eval base-context-bindings | 23,232 | 5,000 | STILL |
| turn-test transitions-agree-with-the-model | 22,094 | 5,000 | STILL |
| turn-test recovery-preserves-terminal-receipts | 21,418 | 5,000 | STILL |
| program-test indexed-and-evaluated | 15,387 | 5,000 | STILL |
| flow core-fault-signatures | 11,866 | 5,000 | STILL |
| config fresh-jvm | 11,817 | 5,000 | STILL |
| rereads-panel silent-refresh | 7,642 | 5,000 | STILL |
| cluster.turn-test delimiter-repair | 7,100 | 5,000 | unknown |
| my.program-mutation native-mutations | 5,689 / 6,326 | 5,000 | unmeasured |
| help-trial preflight | 5,651 | 5,000 | STILL |
| contracts-install function-literal | 5,540 / 6,262 | 5,000 | STILL |
| cluster.turn-test prose-only-provider-reply | 5,061 | 5,000 | unknown |
| prompt-test ×6 (held-turn, basis-only, calibration, identical-context, later-evaluations, unobserved-messages) | 5,138–5,957 | 5,000 | green in d |

- **Verdict:** measure the named operation per the >1 s rule. Nothing here is UPDATE-TEST.
- The prompt-test six are a 3–19% overrun on a suite that was green at 03:4xZ. Candidate cost: 3785970b9 plus
  b195ae037, where call preparation now supplies the database per render invocation. Time one prompt-test member
  on de254ecb0's parent 11f93a7f9^ against HEAD before deciding.
- Every line over 10,000 ms needs an issue.

### 8. HANDROW: hand-built or partial rows the complete-entity writer refuses (11; 2 STILL, 9 NEW, all first measured), UPDATE-TEST

- **my.note-test ×4:** `seon.db/transact! refused transaction data at [60770 :seon.message/to]: expected the
  required key :seon.message/to … Entity: #:seon.message{:id "subject-1"}`. Five commands × 4 members = the 20
  lines root counted. note_test.clj:15 writes a bare `[:db/add "subject" :seon.message/id "subject-1"]` as a note
  subject. Fix: write a complete message (`:seon.message/to [:seon.agent/id "bob"]` and `:seon.message/content`),
  or use the agent as the subject. 2 lines.
- **agent-call-edges admitted-declarations:** `refused transaction data at [60752 :seon.agent/branch]: expected
  the required key :seon.agent/branch`. 11 commands × 1 member = the 11 lines root counted.
  agent_call_edges_test.clj:70–74 hand-builds `{:seon.agent/id … :seon.agent/namespace … :seon.agent/settings …}`.
  Fix: `(test-support/agent-tx @connection agent-id namespace-name)` (test_support.clj:720), and keep the
  no-provider setting as a config dial. 5 lines.
- **db-test component-expanded-pull:** `missing-component-schema … :seon.db-test/component-child`. Give the
  fixture's component its entity schema.
- **error-test complete-error-children (STILL):** the same, for `:seon.error-test/manifest-location`.
- **web-debug reverse-blocks:** `unowned-entity … #:seon.render.web-debug-test{:hidden 60760}`. Make the child a
  component of a declared owner.
- **web-debug outline-origin:** `Started issue tests and their authority must be preserved; only the creator may
  remove tests`. The fixture retracts `:seon.issue/tests`. Delete the issue through its owner, or create it
  unstarted.
- **background-test:** `Nothing found for entity id [:seon.fn/sym clojure.core/identity]` (background_test.clj:21,
  `:seon.effect/owner`). Name a declared program function of the world.
- **contracts-plan installed-contract (STILL):** `:example/order-row is not declared … named by
  my.agents.juniper/largest-customer`. Declare it in the fixture.

### 9. FLAT and refusal order (13; 4 STILL, 9 NEW), mixed

- **db-test an-unknown-read-operation:** `(:seon.error/offending data)` and `(:seon.error/member data)` are nil
  (db_test.clj:2145–2146). The test reads a nested `:seon.error/data` from a flat refusal. UPDATE-TEST.
- **db-test uncarried-read:** `:seon.schema/expected-value` and `:seon.schema/refused-value` are nil, and no
  `projection-fallback` line appears (db_test.clj:183–190). Probe: `pull-many` may now find a handed projection
  (`schema/handed-projection`) where the test expects the fallback.
- **db-test every-public-read and config a-refused-read (STILL):** the result is `"This operation requires a
  carried schema projection."` (db.clj:1241), not the upstream error value, for as-of, pull-many and db. The
  projection check runs before the upstream-error pass-through. FIX-SRC in seon.db's read entrance: return an
  upstream `:seon.error/at` value first. About 5 lines.
- **db-test nested-native-reports:** the before and after values admit as `#{:seon.sci.admit/opaque
  :seon.sci.admit/projection-error}` (db_test.clj:1108). Probe: SCI admission of a database report after
  6db5b2451 (projection-state names the caller's cluster).
- **web-test start-refuses-a-flat-process-write:** the message is now `seon.sci.eval/database-effective-config
  requires configuration naming its cluster; this database has none.` That is 6db5b2451's refusal, firing before
  the injected process refusal (web_test.clj:2158). NEW. UPDATE-TEST (seed the cluster), or FIX-SRC if an unnamed
  render start must not need config.
- **sci.eval multi-arity-miss and compiled-runtime-metadata (STILL):** `[:seon.error/data
  :seon.instrument/arglists]` is nil (eval_test.clj:1329, :614). UPDATE-TEST: read the member at the top level
  (census d FLAT a).
- **schema-test refused-projection-source (STILL):** `:seon.schema/refused-value` is nil (schema_test.clj:1498).
  The same flat read. UPDATE-TEST.
- **problems-test missing-model and missing-projection:** `(:seon.render/html properties)` and
  `(:seon.error/operation result)` are nil (problems_test.clj:236, :570). Probe.
- **bootstrap absent-intent-budget:** `(:seon.config/error-key result)` is nil (bootstrap_test.clj:388). Probe
  whether 084b12d15 (flat config refusals built through the error owner) renamed or moved the key.
- **flow fault-committer-runs-with-the-projection (green in d):** the stored `:seon.instrument/check` is nil
  (flow_test.clj:761). Probe the recorded error's members. The candidate is 6d25d9483 (invoke a function value
  through the same arm).

### 10. RENDER (13; 12 STILL, 1 NEW), census d §RENDER

- **mcp-test ×4:** 25,130 and 24,613 bytes against the 8,192-byte window. Nested elisions lose their path.
  runtime-observation also gives an NPE `"x" is null`. FIX-SRC, in the render-floor design (only the floor printer
  clips).
- **value-test map-beyond-the-child-bound:** keys are re-sorted (`:0 :1 :10 …`). src/seon/print.cljc fit-node.
  FIX-SRC.
- **value-test explicit-pull:** `:seon.agent/namespace` is shown as the opening text (value_test.clj:797).
- **history-test form-is-the-third-output (STILL), page-review** (`A declared bound is missing; supply the required
  cap`, with error-key `:seon.config.eval.result/max-nodes`, caps nil, at transcript.clj:673 `candidate-history`),
  **help-test** (`(:seon.turn/id @committed)` is nil, help_test.clj:57), **run6-stall header**, **web coalesce**
  (7 repaints for 6 commits) and **web next-turn-context** (`clojure.core/<=` with 0 args: no positions found).
- **NEW:** web-test session-acquisition-failure gives a 500 with `Cannot invoke "String.getBytes(String)" because
  "text" is null` (web_test.clj:2371). A nil shown text reaches the HTTP body. FIX-SRC at the session panel
  renderer (src/seon/render/web.clj). Probe.

### 11. SHAPE (10, STILL), census d §SHAPE

- **turn-loop refused-generated-opening:** `seon.context/capture-tx refused request at
  [:seon.cluster.prompt/rendered-context :seon.cluster.prompt/text]`. FIX-SRC at turn.clj:4444.
  - The refusal predicate recognizes only `:seon.turn.loop/phase-failed` and `:seon.cluster.prompt/error-agent-id`.
    A render diagnostic reaches `capture-tx` as if it were success.
  - About 3 lines.
  - It may also be TURN's cause (§2).
- **turn-loop the-committed-set:** `committed-test-attributes` of `[:map …]` gives `#{}` (turn_loop_test.clj:679).
  FIX-SRC: read entries through `m/entries`.
- **turn-loop prompt-and-call:** `(:seon.turn/forms opening)` is nil (:478).
- **edit-refusals:** `my.edit/form! refused return value at [:my.edit/path]`. FIX-SRC in src/my/edit.clj.
- **turn-test:**
  - wake-meeting: `[[:error 0]]`, expected released (:415).
  - refused-generated-form: shown is a raw error map (:250).
  - open-turn-read-refusal: a q refusal replaces the expected refusal (:955).
  - settlement-divergent: `:seon.db/transaction-refused` (:1491).
  - fault-unit: 1 fault shown, not 2 (:1950).
  - virtual-turns: 93 datoms, not 48 (:573).
- **sci.eval declared-row:** the shape recorded differs from `[:int {:min 0}]` (:1260).

### 12. ERRREC (7, STILL), census d §ERRREC, unchanged

- **Members:**
  - error-test: declared-schemas-persist (`:seon.agent/error-agent-id` nil, :1265), identity-and-occurrences
    ("Faults (0)", :155) and row-acquisition (`:seon.sci.eval/row-member` nil, :76).
  - error-write-timing: the spy is called 0 times (:17).
  - agent-test install-gate: `:seon.turn.loop/phase-failed` is nil (:1284).
  - turn-loop: refused-phase (no escalation, :630) and clean-last-form (the terminal is nil, :777).
- **Owner:** src/seon/error.clj `recording` and `steward`. Probe one member first (census d).

### 13. FNSYM: string reads of the symbol-typed `:seon.fn/sym` (5; 4 STILL, 1 NEW), UPDATE-TEST

- **First lines:**
  - `seon.fn/tests-reaching refused function-symbol at []: expected a namespaced symbol, got a string`
    (turn_test.clj:2670).
  - `seon.db/pull received "seon.eval.drive/uuid-text" for :seon.fn/sym, whose installed value type is
    :db.type/symbol` (turn_test.clj:1150, :1171).
  - `refused transaction data at [0 1 1]: expected a namespaced symbol, got a string … [:db/add [:seon.fn/sym
    "my.plan/current!"] …]` (rereads_test.clj:164).
  - `(= "fixture-subject/subject" …)` gives `fixture-subject/subject` (edit_test.clj:345). Set of strings versus
    symbols (edit_test.clj:419).
- **Producer:** the tests. db.clj:1757's read refusal (9b5eefbb7, 09-24) predates census d.
- **Fix sketch:** script `[:seon.fn/sym "x/y"]` to `[:seon.fn/sym 'x/y]` once across turn_test.clj (12 sites),
  rereads_test.clj and edit_test.clj, then compare symbols. About 20 changed lines.

### 14. GUARDFORM: printed-form comparison (5, NEW: first measured after R0), UPDATE-TEST

- **First line:** `(= (pr-str (get forms base-key)) (:seon.schema/form …))` gives `"[:int #:seon.db{:index true}]"`
  against `"[:int {:seon.db/index true}]"` (schema_usage_guard_test.clj:270, :300, :395, :545).
- **Producer:** src/seon/schema.clj:3814 stores `(binding [*print-namespace-maps* false] (pr-str …))` (6d84f27fa,
  09-22). The test prints under the default binding.
- **Fix:** compare `(edn/read-string stored)` with the form, or bind the same printer, at 4 sites.
  entity-lifecycle (:454) compares reference edges. Probe it after the other four are fixed.

### 15. WORLD (5; 4 STILL, 1 NEW), census d §WORLD

- **sci.eval acquired-source-context-forks and evaluation-custody:** the result is `["default" "fork-a"]`, not
  `["fork-a"]` (eval_test.clj:517, :1800). The world carries boot's `"default"` cluster.
- **config apply-replaces:** the result is `("default" "fork")` (config_test.clj:611).
- **oversight every-consumer:** `Nothing found for entity id [:seon.config/cluster "unconfigured"]`.
- **NEW web-debug turn-details:** the kinds are `("Virtual")`, not "System" (web_debug_test.clj:91).
  transcript.clj:1177–1183 prints "Virtual" when the opening reads `:seon.config.ai/no-provider true`. A fixture
  world that branches live default inherits default's development overlay. This is evidence for TURN hypothesis 1.
- **Verdict:** UPDATE-TEST. Assert relative to the world's own cluster, as census d said. The structural question
  (should a member world carry the development overlay?) goes to root.

### 16–20. Small classes

- **KONDO (4, STILL):** fn-test declared-function-values, re-index and the-indexer-emits, plus sci.eval
  non-evaluable-agent-source.
  - The message is "clj-kondo holds no analysis of seon.db / seon.effect / s3.missing".
  - For the three fn-test members this is operational: root runs `(seon.fn.analyzer/analyze-dependencies!)`.
  - `s3.missing` is a fictitious fixture namespace, so the analysis can never cover it. There the indexer must
    classify an unanalyzable host namespace as the test's expected `:jvm-fallback`, not refuse. That is FIX-SRC in
    src/seon/fn.clj (census d N4 residue).
- **CUSTODY (4; 3 STILL, 1 NEW):** my.catalog reads-canned-label (a branch mismatch, as in census d), my.catalog
  a-hidden-call (NPE, no evaluation), effect-attempt (red through its nested my.catalog members), and NEW db-test
  write-naming-another-cluster.
  - The NEW member fails `(some? (:db-after elided))`, "the elided arity still commits through the writing
    custody" (db_test.clj:1728).
  - Producer: 4ebea3977 fixed `apply-config!`, `seed-cluster!` and `transacted!` (test_support.clj:215–235).
    my.catalog's nested fixture writes through a path it did not reach.
  - Probe which writer my.catalog's fixture uses.
- **SEEDROW (3, STILL, with a changed cause, 50b0e82bc):** seed-preparation host-body, refused-seed and
  seeds-prepare.
  - Census d's `:seon.test/skip-reason` refusal became `Complete component validation refused: unowned-entity …
    #:seon.test{:fixture-observation "seed-0"}` (seed_preparation_test.clj:24–30).
  - The replacement attribute is identity-less, so the writer treats every seeded row as an unowned component.
  - UPDATE-TEST: seed an attribute with its own identity, or a component under a declared owner. About 4 lines.
- **DRIFT (2):** error-test contract-violations (STILL, H8: `drift-added` 42 `dev-cache/*` and `seon.dev.*` Vars,
  from a namespace load racing the member), and NEW turn-test a-refused-turn-write (`drift-added
  ["seon.ai/complete"]`).
  - The NEW one breaks a law. turn_test.clj and cluster/turn_test.clj still `with-redefs [ai/complete …]` over an
    armed default Var. The drift guard then sees the unarmed stub.
  - UPDATE-TEST: give turn-test the injected local provider that 146b8a5fb gave agent-test.

### PROBE: unconfirmed owners (27; 21 STILL, 6 NEW). One REPL form each before a verdict

- **STILL, as in census d:**
  - fn-test: contracted-rows, indexing-resolves (2 `packaged-forms` calls, not 1), indexing-uses-a-prebuilt-manifest
    (`:seon.fn/index-phase` nil), publication-writes (`Missing schema declaration :sample.definition-digest/value`)
    and settled-form (calls `#{}`).
  - issue-test worker-opening, reset-edges ×2, rereads stale-read.
  - schema-test: incremental-projection, error-declared-storable and one-derivation (the FIX-SRC law violation from
    1625fb9bc).
  - schema.datahike-test ×4.
  - sci.eval: a-turn-fork (the base holds the agent namespace, :1408), both-entrances (`:sci.impl/interrupt`
    escapes) and one-unloadable-row (`db/pull` of a nil id, :1560).
  - contracts-plan refusal-grammar (`Invalid keyword: :my.plan/item/id`).
  - flow fault-committer-own-error-channel (20.3 s).
- **NEW (first measured):**
  - my.plan-api item-and-plan: the steps are `#{}` (plan_api_test.clj:89).
  - await-owner-census: the declared production callers differ from the callers found. A new caller was added,
    probably by 6d25d9483 or 146b8a5fb, and must be declared.
  - agent-arming an-agent-created: the arming attributes include `:seon.schedule.task/owner`
    (agent_arming_test.clj:123). That is a new declared arming attribute. Decide UPDATE-TEST or FIX-SRC in
    src/seon/cluster/wake.clj.
  - db-test system-write-never-acknowledged: the latch "the timed-out transaction to settle later" is never seen.
  - test-failure-facts what-made-it-red: `changed-since-green` answers "No admitted green execution is recorded",
    and the symbols are nil (test_failure_facts_test.clj:361).
  - test.reach-test indexed-reach-rows: `reach-row` differs (reach_test.clj:52).

## 3. Class → owning files

| rank | class | n | verdict | owning files |
|---:|---|---:|---|---|
| — | routed DIGEST + ANALYSIS | 94 | fix-digest-fixtures-opus | test/seon/test_support.clj row helpers; the fixture files in its landing; test/seon/render/retained_test.clj:200; test/seon/test_failure_facts_test.clj:43–51 |
| 1 | WDIGEST | 3 | FIX-SRC | src/seon/db.clj `write-digest-error` (79b6d9abd) |
| 2 | TURN | 21 | probe → FIX-SRC | src/seon/turn.clj:3930 `provider-targets`, :4321–4485 call phase, :4444 refusal predicate; test/seon/cluster/turn_test.clj:175–240 `with-cluster` |
| 3 | RDISPATCH | 18 | probe → FIX-SRC / UPDATE-TEST | src/seon/render.clj :235 `producer-argument`, :606 `viewing-namespace`, :647 `declared-stage`; resources/seon/schemas/seon.render.edn `:unit`; test/seon/render_simplification_test.clj:158 |
| 4 | HANG | 7 | fix body waits | test/seon/cluster/agent_test.clj (property bodies); test/seon/cluster/agent_arming_test.clj; test/seon/cluster/turn_test.clj generated-model; test/seon/sci/eval_test.clj static-and-runtime |
| 5 | LOOP | 12 | probe (after TURN) | test/seon/cluster/agent_test.clj; test/seon/loop_proof_test.clj; test/seon/context_blocks_fixture.clj:204; src/seon/cluster/agent.clj (turn completion) |
| 6 | BUDGET | 7 | RERUN; request sizing | src/seon/test.clj `bounded-result` :230–312 (the request remainder); the root command set |
| 7 | BOUND | 19 | measure; >10 s issues | each body's operation; src/seon/call_preparation.clj and render.clj (prompt-test candidate) |
| 8 | HANDROW | 11 | UPDATE-TEST | test/my/note_test.clj:15; test/seon/agent_call_edges_test.clj:70–74; test/seon/db_test.clj component fixture; test/seon/error_test.clj; test/seon/render/web_debug_test.clj; test/seon/background_test.clj:21; test/seon/contracts_plan_test.clj |
| 9 | FLAT | 13 | FIX-SRC ×2, UPDATE ×4, probe ×7 | src/seon/db.clj:1241 read entrance order; src/seon/sci/eval.clj (6db5b2451 refusal); test/seon/db_test.clj:183–190, :2145, :1108; test/seon/sci/eval_test.clj:614, :1329; test/seon/schema_test.clj:1498; test/seon/problems_test.clj; test/seon/bootstrap_test.clj:388; test/seon/flow_test.clj:761; test/seon/render/web_test.clj:2158 |
| 10 | RENDER | 13 | FIX-SRC (floor), UPDATE | src/seon/cluster.clj (MCP projection); src/seon/print.cljc fit-node; src/seon/render/transcript.clj:673; src/seon/render/web.clj (session panel); test/seon/render/history_test.clj, value_test.clj:797, web_test.clj:1790, :2455; test/seon/help_test.clj; test/seon/run6_stall_test.clj |
| 11 | SHAPE | 10 | FIX-SRC ×3, UPDATE/probe | src/seon/turn.clj:4444 and `committed-test-attributes`; src/my/edit.clj; test/seon/turn_test.clj; test/seon/turn_loop_test.clj:478; test/seon/sci/eval_test.clj:1260 |
| 12 | ERRREC | 7 | probe → FIX-SRC | src/seon/error.clj (recording, steward); src/seon/turn.clj (phase-failed, escalation) |
| 13 | FNSYM | 5 | UPDATE-TEST (scripted) | test/seon/cluster/turn_test.clj (12 sites); test/seon/rereads_test.clj:164; test/seon/edit_test.clj:345, :419 |
| 14 | GUARDFORM | 5 | UPDATE-TEST | test/seon/schema_usage_guard_test.clj:270, :300, :395, :454, :545 (producer src/seon/schema.clj:3814) |
| 15 | WORLD | 5 | UPDATE-TEST + root ruling | test/seon/sci/eval_test.clj:517, :1800; test/seon/config_test.clj:611; test/seon/oversight_test.clj; test/seon/render/web_debug_test.clj:91 |
| 16 | KONDO | 4 | operational + FIX-SRC ×1 | `(seon.fn.analyzer/analyze-dependencies!)`; src/seon/fn.clj host-binding classification (s3.missing) |
| 17 | CUSTODY | 4 | probe → UPDATE-TEST | test/seon/test_support.clj:215–235 (writers); the my.catalog fixture in test/seon/test/effect_attempt_test.clj; test/seon/db_test.clj:1728 |
| 18 | SEEDROW | 3 | UPDATE-TEST | test/seon/test/seed_preparation_test.clj:24–61 |
| 19 | DRIFT | 2 | UPDATE-TEST + harness H8 | test/seon/turn_test.clj and test/seon/cluster/turn_test.clj `with-redefs [ai/complete]`; src/seon/test/runner.clj snapshot guard |
| — | PROBE | 27 | probe | per item above |

## Commit → NEW red map (read from each diff)

| commit | lands | NEW reds |
|---|---|---|
| 79b6d9abd | the writer recomputes each stored definition digest | 37 green→red digest reds (routed), retained adoption (routed) and WDIGEST blob ×3 (FIX-SRC) |
| aa91ecb34, 634d8aaa2, b195ae037 (+3785970b9) | inline face, namespace stage, database as a declared render input | RDISPATCH: render.value ×3 green→red; render-simplification ×8 and web-debug ×6 first measured; prompt-test ×6 duration candidate |
| 6db5b2451 | projection-state names the caller's cluster or refuses | web-test start-refuses (verified by message); db-test nested-native (candidate) |
| 50b0e82bc | seed tests seed an identity-less attribute | SEEDROW ×3 (the cause changed; red in d too) |
| 146b8a5fb | agent-test local provider, no redefs | agent-test disarm ×2 green→red (LOOP) and fenced (BUDGET victim); fixed ai-test ×13 |
| 9ebdc9cd4 | R0: first-load arming compares loaded identities | unblocked 5 namespaces; their 41 first-measured reds are not caused by it |
| none (first measured) | — | TURN 21, HANDROW 9, GUARDFORM 5, FLAT db-test ×3 and problems ×2, the PROBE six |

No NEW red traces to 084b12d15 (config flat refusals: fixed config-test ×3), 4ebea3977 (fixture custody: fixed
two-clusters), 829d6909d (clean-world green), 9f82663e1 (lazy install), 6d25d9483 (kernel invoke; await-owner is a
candidate only) or de254ecb0/48ceb9cad (publication fixes).

## Verification limits

- This census read results only. Every fix sketch and every PROBE owner is inferred from failure text, source and
  diffs, not executed. TURN, RDISPATCH, LOOP and the FLAT probes need the named form.
- The one hypothesis check was a read-only query showing default's config row carries
  `:seon.config.ai/no-provider true`. The census did not observe that a fixture world reads it; only web-debug's
  "Virtual" kind points there.
- The STILL/NEW split uses membership in census d's 169. Members census d recorded as unknown or never measured
  count as NEW here even when their cause predates census d.
- Run `ab581ec319f2` (the nested effect-attempt request) has no member rows, so the my.catalog ×2 text comes from
  .d/006.
- RESET NEEDED: none.

## Root runs (serially, after the named fixes)

```
bin/test-check default --test seon.cluster.turn-test/function-install-reads-the-case-count-from-cluster-facts  ; TURN probe
bin/test-check default --ns seon.blob-test                          ; WDIGEST regression
bin/test-check default --ns seon.render.value-test                  ; RDISPATCH
(seon.fn.analyzer/analyze-dependencies!)                            ; KONDO
bin/test-check default --ns seon.call-preparation-test              ; BUDGET rerun
bin/test-check default --ns seon.cluster.agent-identity-test        ; BUDGET rerun
bin/test-check default --ns seon.cluster.agent-test --include-long  ; HANG/LOOP after TURN
bin/test-check default --ns seon.cluster.prompt-test                ; BOUND timing (parent vs HEAD)
```

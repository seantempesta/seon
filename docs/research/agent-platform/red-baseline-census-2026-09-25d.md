---
type: research
status: complete
lane: census-adopted-reds
created: 2026-09-25
---

# Adopted-HEAD reds, fifth census (2026-09-25d)

Default adopted source commit `6ab5ec54` at 21:36 local (the my.turn re-land 84f5a9fe2 and the adoption read checks
a1b85c60d; `tmp/orchestrator/init-reland-myturn.txt`). Root then ran 133 serial commands
(`tmp/orchestrator/verify-adopted-results-2026-09-25.txt`, 03:37–04:16Z) and executed 874 members. That run produced
169 red names (`tmp/orchestrator/red-adopted-2026-09-25.txt`).

**Method.** MCP `eval_clj`, JVM mode, read-only, in the throwaway namespace `census-d-scratch`, on default pid 35446.
The JVM restarted at 14:24Z, but the store survived. For each command's run id, the census read the member symbols
from `:seon.test.run/members`, then called `@#'seon.test.runner/recorded-member-results` (runner.clj:1618) and
`seon.test/failure-message` (test.clj:57). It did the same for census c's 74 run ids, which reproduced census c's
127 red + 17 unknown exactly. The census ran no tests, no `bin/seon init` and no writes.

`seon.test.runner/latest-results` answered 167 of the 169 in 111 ms. Two exceptions:
- Root re-ran `seon.sci.eval-test` (run `584dfb282905`, 14:26Z) and `entity-pairs` (14:25Z) after the restart. Those
  members were read from the adopted runs (`701808ab9d25`, `07b67c183a8d`, `1f4157d3ea69`) instead.
- `my.catalog` (2) has no run row. Its two members are the nested fixture members of `seon.test.effect-attempt-test`
  (run `ac7b686cf2f7`, which is not in the store), so their failure text comes from the command output.

Working files are in `tmp/census-adopted-reds/`:
- `adopted.txt`, `adopted-extra.txt` and `latest.txt` hold the failure text.
- `c-status.txt` and `d-status.txt` hold each member's census-c status and adopted status.
- `assign.txt` is the class of each member.

## Broken things first

1. **The verification never measured 13 commands.** Their requests were refused before any member ran, with
   "Cannot interpret X: Host-bound declaration X must change through the loaded source files". X was one of five
   helpers that the test-shape sweep added or changed.
   - Blocked: `seon.problems-test`, `seon.render.web-debug-test`, `seon.schema-usage-guard-test`,
     `seon.test-failure-facts-test`, `seon.render-simplification-test` and 8 `--changed` requests.
   - That is why 26 of census c's reds could not be re-measured (§FIXED).
   - It is a NEW defect class, R0 below. The 169 do not include it.
2. **A test's `with-redefs` leaked into 13 later ai-test members** (class N2). No landing caused it.
   - It is the law-breaking `with-redefs` in `seon.cluster.agent-test`.
   - A future thread that never exited left `seon.ai/complete`'s root redefined to a 1-ary stub.
   - The open fault `seon.turn/call-turn` "Wrong number of args (2) passed to: seon.cluster.agent-test/…/held-completer"
     is the same event.
3. **S4a restored host-body member custody, a regression that was already ruled.** db7f1c919 makes every fixture write to
   a second connection refuse with "This write names branch …" (class N1).
   - The landing `lane-realities-commit-4-2026-09-23.md:150` recorded exactly this failure and removed the custody
     then ("They now inherit none, as before").
4. **Test worlds read uncommitted resources** (class N3). At 03:4xZ the checkout held the parked render-floor slice's
   `:seon.print/allocator-defect-error` (`tmp/render-floor-opus/slice2b-worktree.patch:25`). Six members refused
   while compiling the world with "A stored error member must have a storable registered attribute" or
   `:seon.schema/invalid-schema :seon.print/allocator-defect-error`.
   - Default's own store never held that key. The probe answered history count 0 and registry absent.
   - This is census c class 14 (overlay reads the checkout's resources), recurring.
5. **Nine requests reached the 120 s check limit.** Test bodies that did not exit (§BOUND) left 71 members typed
   unknown, including 44 in `seon.cluster.turn-test` and 17 in `seon.render.web-test`.
6. **Seven lines are over 10 s.** They are listed in §BOUND. Every one needs an issue under the >10 s rule. Root
   files them or links them to `docs/seon/issues/` (census b §E).

## 1. Split

Of the 169, 57 were red in census c, 15 were green and 97 were not measured there. Census c's 74 commands did not
include `seon.sci.eval-test`, `effect-test`, `config-test`, `schema-test`, `schema.datahike-test`, `cluster.agent-test`,
`render.web-test` or `edit-test`.

| verdict | n | meaning |
|---|---:|---|
| **NEW, verified** | 33 | caused by a named landing (N1, N4, N5, N6, N7), by run order (N2) or by uncommitted checkout resources (N3); each diff was read |
| **NEW, likely** | 6 | class CFG: seen on 09-24 before census c, possibly widened by N1; probe first |
| **STILL** | 130 | the same class as census c. 57 were red there. The rest were unmeasured in c, but their causes predate every landing checked |

All 15 green→red members are NEW: 13 in N2, `render.value-test/an-explicit-pull…` in N7, and
`turn-test/a-history-read-refusal…` in N3.

### FIXED since census c: 17 verified green on adopted HEAD

- my.agent-test/settings-are-one-owned-component-with-a-derived-render-pair
- my.agent-test/settings-updates-preserve-one-component-and-agent-isolation
- my.plan-test/a-second-agent-renders-through-the-same-defaults
- seon.ai-test/a-backup-at-another-provider-overrides-what-differs
- seon.ai-test/the-shipped-cluster-has-a-primary-and-configured-backup
- seon.cluster-test/a-core-fault-commits-with-the-dial-its-cluster-config-carries
- seon.cluster.agent-namespace-test/source-bearing-namespaces-without-a-steward-derive-one-problem-line
- seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault
- seon.error-test/schema-refusals-are-admitted-at-the-recorder
- seon.error-write-timing-test/retention-validates-expanded-writes-and-retained-target-identities
- seon.maintenance-schema-test/receipt-request-and-operation-result-attributes-are-queryable
- seon.schema-test/every-predicate-schema-declares-what-it-accepts
- seon.test.overlay-world-test/an-agent-branch-request-runs-its-candidate-on-a-clean-world
- seon.turn-loop-test/a-refused-terminal-commit-still-closes-the-run
- seon.turn-loop-test/install-gate-failure-settles-the-started-receipt-as-a-failure
- seon.turn-test/a-terminal-refusal-settles-when-its-run-has-vanished
- seon.turn-test/attempt-model-identity-survives-descriptor-retraction

That confirms census c classes 1 (NP: ai-test ×2), 6 (WORLD: my.plan and agent-namespace), 10 (ERRSHAPE), 11
(READY), 12 (ERRDECL) and 16 (PRED), and part of class 2.

The other census c reds have no result:
- **26 not re-measured**, blocked by R0: problems ×6, web-debug ×9, schema-usage-guard ×8, test-failure-facts ×1,
  mcp ×2.
- **27 never started** in adopted `seon.cluster.turn-test`, a 120 s request.
- **17 unknown in both censuses**, all in cluster.turn-test.

Census c's class 1 fix (3a7ffa69c) is therefore unproven for cluster.turn-test, because that request timed out on
its first member (§BOUND).

## 2. Classes, ranked by member count

Each class gives the first failure line, the producer, a verdict and a fix sketch. The sketches are designs; nothing
was executed.

### BOUND: over the declared bound, or never exited (26, STILL: census c class 8 and census b §E)

- **Did not exit (7):**
  - cluster.turn-test/a-batched-turn…: 116,730 ms remainder
  - turn-test/virtual-turns…: 38,440 ms
  - web-test/reconnect-is-repaint…: 37,985 ms
  - loop-proof/virtual-loop-end-to-end: 49,431 ms
  - agent-test/n-agent-parallel-turns-property: 70,586 ms
  - agent-test/wake-routing-conservation-property: 117,919 ms
  - fn-test/unresolved-call-shapes…: 570 ms remainder. This member was a victim of its request's budget.
- **Never started (2):** agent-test restamp-recovery, routing-conservation.
- **Duration first (17)**, in ms:
  - loop-proof running-fixture: 64,490
  - rereads stale-read: 55,637 (under PROBE)
  - rereads-panel: 45,796
  - rereads failed-evaluations: 42,460
  - fn indexing-uses-a-prebuilt-manifest: 22,649
  - agent failed-turn-transform: 20,350
  - flow fault-committer: 20,310
  - turn recovery-preserves: 19,688
  - turn transitions: 19,583
  - flow core-fault: 12,131
  - web next-turn-context: 11,530
  - program-test indexed-and-evaluated: 10,911
  - issue worker-opening: 10,752
  - sci static-and-runtime: 10,375
  - schema a-component-bearing: 9,888
  - config fresh-jvm: 8,440
  - turn-continue ×2: 8,131 and 7,598
- **Producers:** each body's named operation. Census c's 20.7 s `indexing-uses-a-prebuilt-manifest` is now 22.6 s,
  with `:seon.fn/index-phase` still nil. turn-continue fails inside
  `seon.context-blocks-fixture/submit!` (context_blocks_fixture.clj:204): "Juniper fixture turn did not settle".
- **Verdict:** BOUND. Measure each named operation, per the >1 s rule; nothing here is UPDATE-TEST.
- **Fix sketch:**
  - The did-not-exit members share one owner: `seon.test/bounded-result` keeps the branch, as designed. The fix lies
    in each body's wait: an `await-event!` without a terminal event, or a `future` inside `with-redefs`, as in
    agent-test.
  - First fix N2's held-completer (below), which also leaves a future unexited. Then rerun agent-test alone.
  - Over 10 s: every line above 10,000 ms needs an issue.

### ROWS: hand-built fixture rows the writer refuses (25, STILL: census c class 2)

- **First line:** `Fixture write was refused at the write: seon.db/transact! refused transaction data at [59593
  :seon.program/definition-digest] … Entity: #:seon.schema{:key :seon.effect-test/request}`, or `… [59594
  :seon.agent/branch] … #:seon.agent{:id "effect-agent"}`.
- **Members:**
  - effect-test: all 14.
  - sci.eval-test: agent-contracts-apply…, an-acquired-function…, a-turn-fork-registers…
  - fn incremental-tempid (`#:seon.ns{:name sample.attribute-aware.owner}`).
  - datahike edn-backed.
  - render-source preview-cache and source-contracts.
  - render.retained.
  - web-test an-undeclared-incoming-reference.
  - error-test complete-error-children (`missing-component-schema :seon.error-test/manifest-location`).
  - contracts-plan installed-contract-refusal: `:example/order-row` is undeclared, named by
    `my.agents.juniper/largest-customer`.
- **Producers:**
  - test/seon/effect_test.clj:130–160 (`transact-fixture!`, `install-capability!` and `install-arm-probe!` write
    `:seon.schema/key` rows by hand; blame f3b8eabda/6dc70f30a, which predate f27b96c19 on 09-22).
  - test/seon/render_simplification_test.clj:60–70 (`declare-subject!`, shared by render-source and retained).
  - test/seon/sci/eval_test.clj:1421, :1531, :1642.
  - test/seon/fn_test.clj:2002.
  - The agent rows in web_test and retained_test.
- **Verdict:** UPDATE-TEST. The sweep (10d353039) covered census c's files. These files were unmeasured in census c,
  so the sweep never reached them.
- **Fix sketch** (script it once over the set, about 40 lines):
  - Replace each hand-built `{:seon.schema/key … :seon.schema/form "…"}` with `schema/canonical-schema-rows`, the
    helper bf0a556c3 used for fn-test.
  - Replace each hand-built `{:seon.fn/sym …}` with `program/declaration-row`.
  - Build agents through `test-support/agent-tx`.
  - Give error_test's `:seon.error-test/manifest-location` its component schema.
  - contracts-plan's juniper fixture declares `:example/order-row` in the same change.

### N2: a with-redefs leak from agent-test breaks ai-test (13, NEW; no landing caused it)

- **First line**, all 13: `clojure.lang.ArityException: "Wrong number of args (2) passed to:
  seon.cluster.agent-test/fn--1597046/held-completer--1597047/fn--1597048" at seon.ai-test/… (ai_test.clj:1006)`.
- **Producer:** test/seon/cluster/agent_test.clj:1962–1971.
  - `held-completer` returns `(fn [request] …)`, a 1-ary fn. The stub it wraps, `recording-completer` (:293), is
    2-ary `(fn [_projection request] …)`, and so is `ai/complete`.
  - The body runs inside `(future (with-redefs [recording-completer held-completer] (routing-trial …)))`, and
    `routing-trial` (:1824) does `with-redefs [ai/complete …]`.
  - The 2-arg call throws, so terminal evidence never arrives. The future never exits (run `244eda43468a` errored
    at 38 s), and `ai/complete`'s root stays the stub.
  - ai-test ran next, at 03:55Z, and every real `ai/complete` call hit the stub.
  - The arity mismatch dates from 0f5f849bd (09-20). The ordering exposed it: census c never ran agent-test before
    ai-test.
- **Law:** "Never `alter-var-root`, `with-redefs` … over default's Vars."
- **Verdict:** UPDATE-TEST.
- **Fix sketch** (about 15 lines):
  - Make `held-completer` 2-ary: `(fn [projection request] … (complete projection request))`.
  - Then remove the global redefinition. Pass the completer to `routing-trial` as an argument (it already builds
    the stub). If the cluster handle cannot carry it, delete the observation, as census c ruled for
    `fixture-evaluate`.
  - One class regression: ai-test green after agent-test in one request.

### FLAT: tests or producers disagree on where a flat error's member lives (12, STILL: census b §G)

- **First lines:**
  - `(= expected-key (::config/key data))` gives `nil` (config_test.clj:213, :488, :556).
  - `(get-in failure [:seon.error/data :seon.instrument/arity])` gives `nil` (eval_test.clj:1367, :608).
  - `(:seon.error/member (:seon.error/data outcome))` gives `nil` (schema_test.clj:1503, :446, :981;
    maintenance_test.clj:320; render_source_test.clj:210).
  - `(count (get-in refusal [:seon.error/data :seon.error/problems]))` gives `0` (contracts_plan_test.clj:64).
- **Members:**
  - config-test: apply-converts, compiler-ignores, explicit-absence, shipped-initialization.
  - sci.eval-test: multi-arity-miss, compiled-runtime-metadata.
  - schema-test: refused-projection-source, pulled-forms, render-declarations.
  - maintenance last-collection.
  - render-source anonymous-values.
  - contracts-plan refusal-grammar, whose token estimate is also 151 > 150.
- **Producers:**
  - (a) src/seon/instrument.clj:576–581 puts `:seon.instrument/arity` at the top level; e42f494ab, 09-17. The tests
    read the pre-flat path.
  - (b) src/seon/config.clj:127, 223, 238, 286, 300, 314, 410, 502 and 546 nest `::key` inside `:seon.error/data`
    (E15, e36e71795, 09-23). resources/seon/schemas/seon.config.edn:43 declares `:seon.config/key` as a flat member.
  - config-test apply-converts also uses `with-redefs [db/transact! …]`, which breaks the same law as N2.
- **Verdict:** UPDATE-TEST for (a) and the schema, maintenance, render-source and contracts-plan reads. FIX-SRC for
  (b), per "declare errors as flat data".
- **Fix sketch** (about 30 lines):
  - Script `s/:seon.error\/data {::key/::key/` over config.clj's 9 sites and assert on the top-level key.
  - Script the test reads from `[:seon.error/data X]` to `X` for instrument and `:seon.error/member`.
  - Replace apply-converts' `with-redefs` with a real refused reconcile, for example a desired row that fails
    validation.

### RENDER (11, STILL: census c class 5)

- **mcp-test nested-bulk, sci-top-level and ordinary-mcp:** 25,130 and 24,613 bytes against the 8,192-byte window,
  and nested elisions lose their `:seon.render.data/path`.
  - Producer: the MCP projection (src/seon/cluster.clj), census b §7.
  - FIX-SRC, to be folded into the render-floor design. The owner's ruling: only the floor printer clips.
- **value-test a-map-beyond-the-child-bound:** `(0 1 11 12 … :0 :1 …)` against whole order.
  - Producer: src/seon/print.cljc fit-node re-sort (census c). FIX-SRC in the render-floor lane.
- **history-test and entity-pairs:** UPDATE-TEST.
  - `:seon.message/to` now has a declared pair, `seon.render.transcript/inbox-form`.
  - The requery form is now `seon.test/recorded-result`.
  - After the restart, entity-pairs (run 75a73e559ac6, post-1c) fails instead with "`seon.render/selection` refused
    return value at [:seon.render.selection/stages]: expected a vector, got a vector", a 1c contract defect
    (src/seon/render.clj selection). 1c is not in the adopted HEAD, so the render-dispatch lane owns this.
- **contracts-install function-literal and turn-test a-refused-generated-form:** the selection stages for a
  contract-error value are `:declared :no-match`, `:candidates []`.
  - Producer: render selection (src/seon/render.clj). e7c4deb4c (22:09) makes this a loud finding.
  - Probe on post-1c HEAD before editing.
- **page-review:** "A declared bound is missing; supply the required cap." from
  `seon.render.transcript/render-run-ai`. Probe the missing profile member.
- **web-test declared-units:** the schema order now includes `:seon.agent/branch` and `:seon.agent/archived-tx`.
  UPDATE-TEST (web_test.clj:651).
- **help-test:** `seon.repl/render-ai refused unit … got a lookup-ref vector` (help_test.clj:53), plus a
  `:seon.cluster/config` fixture refusal. UPDATE-TEST.

### SHAPE: a retired call or return shape (7, STILL: census c class 4)

- turn-test a-wake-meeting: `seon.turn/open-turn refused return value at []: expected a map, got a vector.
  Contract: :seon.turn.loop/turn-report`. The test's `:report` fn returns a vector (turn_test.clj:396).
  UPDATE-TEST.
- fn-test settled-form: `seon.turn/resume-turn refused argument 0 at [:seon.turn.loop/work
  :seon.turn.work/situation]` (fn_test.clj:486). UPDATE-TEST, as for 324b0accb in turn-test.
- turn-loop refused-generated-opening: `seon.context/capture-tx refused request at
  [:seon.cluster.prompt/rendered-context :seon.cluster.prompt/text] … Called from seon.turn (turn.clj:4461)`.
  - This is also a live open fault on default.
  - FIX-SRC: turn.clj:4461 passes a refused render as `rendered-context`. It should record the refusal instead.
- edit-test edit-refusals: `my.edit/form! refused return value at [:my.edit/path]`. The output is neither
  `:my.edit/result` nor `:my.edit/error`. FIX-SRC in src/my/edit.clj: the refusal must satisfy `:my.edit/error`.
- turn-boot root-opening: `(:seon.turn/id pass)` is nil (turn_boot_test.clj:95). Probe the drive's pass value.
- turn-loop the-committed-set: `committed-attributes` ignores `[:map]` and `[:map {…}]` (turn_loop_test.clj:679),
  census c PROBE. FIX-SRC in turn.clj (`committed-test-attributes`), reading entries through Malli
  `m/entries`, not by position.
- sci.eval-test bare-dir: the expected `[:or :my.fs/read-result :seon.error/value]` is now
  `[:or … :my.fs/error]` (eval_test.clj:1404). UPDATE-TEST.

### ERRREC: error recording drops or misroutes members (7, STILL: census c classes 3/10)

- error-test:
  - declared-schemas-persist: `:seon.agent/error-agent-id` is nil (error_test.clj:1265).
  - identity-and-occurrences: `error/steward` is nil, and the Faults unit shows 0 (:116, :147–156).
  - row-acquisition: `:seon.sci.eval/row-member` is nil and the stored value is invalid (:73–76).
- agent-test install-gate-failure: `(:seon.turn.loop/phase-failed diagnostic)` is nil (agent_test.clj:1229).
- turn-loop a-refused-phase: no escalation at the limit, `[[] [] [] …]` (turn_loop_test.clj:630).
- turn-loop a-clean-last-form: the drive terminal is nil (:777).
- error-write-timing: the spy is never called (error_write_timing_test.clj:17).
- **Owner:** src/seon/error.clj, the recording path (`recording`, `steward`).
  375a56048 ("a recorded value keeps its declared schema's distinguishing members") did not fix these, and 3d460e2eb
  is related.
- **Verdict:** probe one member at the REPL (`error/steward` on the fixture row) before FIX-SRC. This is a single
  class of `error/recording` dropping undeclared members, as census c §10 hypothesized.

### N3: test worlds compile uncommitted checkout resources (6, NEW; no commit caused it)

- **First line:** `A stored error member must have a storable registered attribute.` (contracts-compile ×2,
  edit-test changed-programs and form-edit), or a result of `{:seon.schema/definition … :error
  :seon.schema/invalid-schema, :identity :seon.print/allocator-defect-error, :member :seon.render.data/path}`
  (turn-test history-read and settlement-divergent).
- **Producer:** the parked render-floor working tree in `resources/seon/schemas/seon.print.edn`, read by the world
  overlay's resource loader (census c class 14). No commit carries `allocator-defect-error`: `git log -S` finds none.
- **Verdict:** RERUN. The working tree is clean now, apart from an untracked `reference-code/http-kit`. The
  structural fix belongs with class 14: the member world compiles the *published* rows of the branch, never the
  checkout's files. Root rules whether this is an adoption-owner issue.

### CFG: effective config missing for the context's cluster (6, NEW likely; probe)

- **First line:** `ExceptionInfo "Effective configuration requires a matching cluster row with every required dial."
  at seon.sci.eval/shown-result (eval.clj:3194)`. In a-selected-render the value is `{:seon.config/error-key
  :seon.config/cluster, :seon.config/missing-effective "current-src", … :available ["default"]}`.
- **Members** (sci.eval-test): public-walk, every-public-capability, schema-and-contract-bounded-allocation,
  call-preparation, acquisition-binds, a-selected-render.
- **Producer:** `seon.render/request-profile` (render.clj:143–156) calls `config/effective` with
  `(:seon.db/db request)` and the cluster that the ctx environment names (`"current-src"`). The database holds only
  `"default"`.
- **Status:** the same message appeared on 09-24 before census c
  (`lane-fix-affected-callers-interpret-2026-09-24.md:24`), so the class predates the landings. Probe whether the
  database is now the member branch's (N1 custody) and the cluster name is the fixture's.
- **Fix sketch:** the context's environment and the database it reads must name one cluster. Either the base ctx
  carries its own custody's cluster name, or `request-profile` refuses by name before `config/effective`. That is
  about 10 lines in render.clj or eval.clj.

### N1: S4a host bodies carry member custody, so fixture writes to their own connection refuse (5, NEW, db7f1c919)

- **First line:** `This write names branch :agent-318a3f1df238, which is not the writing cluster's branch
  :agent-4db392bab115 …` at `seon.config/apply-compiled!` (config.clj:731) via `test-support/apply-config!`
  (test_support.clj:789).
- **Members:**
  - config-test two-clusters-on-one-jvm.
  - sci.eval-test evaluation-custody and acquired-source-context-forks.
  - my.catalog/reads-canned-label.
  - effect-attempt-test/an-agent-capability-stub…, red because its nested my.catalog member is red.
- **Producer:** db7f1c919 changed `seon.test/bounded-result` (test.clj:~242) from `(runner/run-var! test-var {})` to
  `(runner/run-var! test-var (select-keys member [:seon.db/connection]))`. `run-var!` binds `db/*conn*`, and
  `seon.db/foreign-connection-error` (db.clj:457–490) refuses every explicit write to another connection.
- **Prior ruling:** `lane-realities-commit-4-2026-09-23.md:150` saw this exact refusal and removed host-body custody.
- **Verdict:** FIX-SRC, with an owner decision. S4a's own regression
  (`seed-preparation-test/a-host-body…`: "a bare (db/db) in a host body reads its seeded member branch")
  asserts the opposite premise.
- **Options:**
  - (A, recommended) Revert the one line, so host bodies inherit no custody, and delete the bare-`(db/db)`
    assertion. Fixtures use `*member*`. About 5 lines.
  - (B) `test-support/with-database` and `with-cluster` bind `db/*conn*` to the connection they create. About 10
    lines in test_support.clj:662. This keeps S4a.

### WORLD: the test world carries boot's cluster and config row (5, STILL: census c class 6)

- agent-situation: `:seon.turn/turns-remaining 100`, expected 2 (agent_situation_test.clj:37).
- run6-stall: `turns-left 99`, expected 2 (run6_stall_test.clj:86).
- config apply-replaces: `("default" "fork")`, expected `["fork"]` (config_test.clj:613).
- sci.eval-test a-configless-database: `configured "default"` and mode `:panic` (eval_test.clj:731–737).
- help-trial preflight: "The selected cluster needs the ruled initial fixture; no model was called."
  (help_trial_test.clj:48).
- **Producer:** 35a9096c6 seeds `seed-cluster!` "situation", but the agent still resolves the `"default"` cluster's
  dial. The agent's cluster link is not the seeded cluster. The world is the S2c checkpoint (test.clj:~1620).
- **Verdict:** UPDATE-TEST. Create the agent in its seeded cluster: `agent-tx` with `:seon.cluster/name`. Assert
  relative to the world's own config row. About 5 lines per file.

### Singles and small classes

- **N6: S2b-b seed tests use an attribute the member world has not installed** (3, NEW, 17034b4e1 and 4fd4c5b4b).
  - Members: seed-preparation a-host-body, a-refused-seed and seeds-prepare.
  - First line: `Seed entry 0 … refused transaction data at [0 :seon.test/skip-reason]: expected an installed
    attribute, got an undeclared attribute`.
  - `:seon.test/skip-reason` is declared (resources/seon/schemas/seon.test.edn:8) but not installed in the member
    world.
  - UPDATE-TEST: seed an attribute of the fixture's own declared schema. Or probe why the world lacks seon.test
    attributes. About 6 lines.
- **N4: S4b lazy install binds compiled Vars for rows the test changed** (3, NEW, 15daddb0a).
  - Members: sci.eval-test database-provenance, non-evaluable-agent-source and base-context-bindings.
  - First lines:
    - `(= :interpreted (:seon.sci.eval/load-state installed))` gives `:jvm`; `seon.id/valid?` gives `false`
      (eval_test.clj:209, :231).
    - The expected `:jvm-fallback` for `seon.id/valid?` is missing (:269).
    - Base bindings include `clojure.test/*` Vars that are not program declarations (:779), at 16.9 s.
  - Producer: src/seon/sci/eval.clj `install-function-from-database!`. The `overridden-row?` check reads
    `::loaded-database`, which already holds the test's row, so an override is treated as unchanged.
  - FIX-SRC: compare against the compiled Var's source digest, not the snapshot row. Probe first. About 15 lines.
- **KONDO** (3, STILL: census c class 13).
  - Members: fn-test declared-function-values, re-index and the-indexer-emits.
  - First line: "Cannot classify host binding: clj-kondo holds no analysis of seon.db / seon.effect".
  - This is operational: root runs `(seon.fn.analyzer/analyze-dependencies!)`, about 6 s. It was not run after the
    restart.
- **N5: S4c attempt agent in the "clean" world** (2, NEW, 71f45f476).
  - clean-world-test: `the member inherits no :seon.agent/id rows` finds `"attempt-01e0e9c54503"` and its turn
    (clean_world_test.clj:36, :47).
  - my.catalog/a-hidden-call: NPE, from an effect context without its turn.
  - Producer: test.clj `attempt-request-context` makes an attempt agent for every member whose reach includes
    `seon.effect/request*`, and the clean-world test's reach does.
  - UPDATE-TEST: exclude the declared attempt rows. Root rules whether reach should be that wide.
- **SCI** (2, STILL: census c class 9).
  - instrument-test sovereign-fork: `seon.id/valid?` has no `:seon.instrument/interpreted-original`
    (instrument_test.clj:412).
  - branch-execution: "Cannot interpret seon.cluster/write-advertisement!: Unable to resolve symbol:
    fs/write-edn-atomically!" (:455), the open issue
    `docs/seon/issues/affected-callers-fail-to-interpret-against-uninstalled-bindings.md`.
- **N7: render dispatch and explicit pull** (1, NEW).
  - value-test an-explicit-pull: `:seon.agent/namespace` is shown as the agent's opening text, not the pulled
    `{:seon.ns/name … :seon.ns/steward …}` (value_test.clj:797–798).
  - Candidates, both at or before 21:04: 105926712 (attribute-element identity) or 8ebb23736 (render 1a declared
    stage).
  - Probe: `render/selection` on that attribute. FIX-SRC in render.clj, because an explicit pull's nested value is
    not the attribute's declared renderer target.
- **DRIFT** (1, STILL: census c class 17). error-test a-contract-violations: "Worker-global state changed:
  …:drift-added ["seon.contracts-compile-test/check" …]". A namespace load raced the member. Harness H8.

### PROBE: an unconfirmed owner (31). Each needs one REPL form before a verdict

- sci.eval-test:
  - declared-row: the shape recorded `:user/direct-schema`, expected `[:int {:min 0}]` (:1299).
  - unloadable-host: NPE on a nil cause message (:924).
  - evaluator-remains-live: the transaction returned no `:db-after` (:431).
  - both-entrances: `:sci.impl/interrupt` escapes (:2321).
  - one-unloadable-row: `db/pull` of a nil entity id (:1597).
- config a-refused-read: `effective-in` returns a new refusal instead of the identical one (:725).
- oversight: "Nothing found for entity id [:seon.config/cluster "unconfigured"]" from `ensure-cluster-entity!`.
- schema-test:
  - no-bound-predicates: call-preparation validators fail to compile (:1288).
  - incremental-projection: 5 mismatches against the full build (:1646, :1722). This may be N3 skew; rerun first.
  - error-declared-storable: 6 observation attributes carry `:db.unique/identity` (:1471).
  - one-derivation: `seon.fn.schema-shape/form-projection` and `seon.schema/projection-runtime-keys` name
    `:seon.schema.projection/predicate-functions`. That is a FIX-SRC law violation from 1625fb9bc (09-22).
- schema.datahike-test:
  - canonical-parity: baseline drift, 6 missing and many unexpected attributes. UPDATE the baseline, with an
    explanation.
  - error-members: the member is `:seon.program/partition`, not `:seon.turn/id`.
  - fixed-tuples: the transaction returned nil.
  - literal-schemas: `declaration-changes` refused before `[]`, which got nil (schema.clj:2728).
  - unsupported-attributes.
- fn-test:
  - contracted-rows: spec and arities are in different transactions (:695–701).
  - indexing-resolves: `packaged-forms-calls` is 2, not 1 (:2666). This is a cost-regression candidate.
  - publication-writes: "Missing schema declaration :sample.definition-digest/value" (fn_test.clj:1834).
  - analyzer-edges: red in single run `4bedde844582`, green in the namespace run `237ad3bc9b8a`. It is
    order-dependent.
- publication-delta: a referrer is not selected (publication_delta_test.clj:152).
- reset-edges: issue retractions sit in a note transaction (:261), and stored-many violations are listed (:129).
- web coalesce: 7 derivations for 6 commits (web_test.clj:1797).
- turn-test:
  - open-turn-read-refusal: a pull refusal instead of a q refusal (:950).
  - fault-unit: 1 fault shown, not 2 (:1944).
- mcp runtime-observation (rewritten in 27aac3899): 1 signature, not ≥50, and 0 shown (:341–351).
- agent-test:
  - concurrent-arm: the NPE "x is null" and an unterminated executor (:1052–1057).
  - episode-cap: no deferred triggers derived (:1468–1487).
- rereads stale-read: the read evidence is not current, and the basis advanced by one (:94–129).
- turn-loop prompt-and-call: `:seon.ai/thinking :disabled`, expected `:high` (:513, :561). Probe whether 3a7ffa69c's
  development overlay or the test world's config sets it.

## R0: a request refused because a test namespace helper's contract "changed" (13 commands, NEW)

- **First line:** `test request refused: Cannot interpret seon.problems-test/world-namespaces: Host-bound declaration
  seon.problems-test/world-namespaces must change through the loaded source files.` The other helpers:
  `seon.render-simplification-test/declare-subject!`, `seon.render.web-debug-test/declare-schemas!`,
  `seon.test-failure-facts-test/declare!` and `seon.schema-usage-guard-test/held`.
- **Producer:** `seon.test/arm-first-loads!` (src/seon/test.clj:2015–2040, 1ea87e4c2).
  - For a fresh namespace, meaning one this JVM has not loaded, it compares the branch's function contracts with
    the contracts of `(:seon.sci.eval/loaded-database @program-snapshot)`. Any difference refuses.
  - The sweep added or changed these helpers: 25f1692ff, 6d84e265e, 9a4120835, 65893d82f, 0f36ffa54.
  - Probe: default's head projection holds `seon.problems-test/world-namespaces`' contract, and
    `(find-ns 'seon.problems-test)` is nil. So the snapshot's loaded database predates the adoption that published
    those rows.
  - A test namespace is never reloaded, so its rows are never "loaded", and every change to a test helper refuses
    its whole request.
- **Verdict:** FIX-SRC.
- **Fix sketch** (about 10 lines): for a fresh namespace, the file *is* what `require` will load. Compare the
  branch's contract with the row published from the file (the checkout digest at the branch's head), not with the
  snapshot. Alternatively, advance the snapshot's `loaded-database` at adoption for rows whose namespace is not
  loaded. The adoption owner decides which. Class regression: change a test helper's contract, adopt, then
  `--ns` runs.

## 3. Class → owning files

| # | class | n | verdict | owning files |
|---|---|---:|---|---|
| — | PROBE | 31 | probe | per item above |
| 1 | BOUND | 26 | measure, then fix the body's wait | each body; test/seon/cluster/agent_test.clj, turn_test.clj, loop_proof_test.clj, rereads_test.clj, rereads_panel_test.clj, flow_test.clj, context_blocks_fixture.clj:204; src/seon/fn.clj (index phase) |
| 2 | ROWS | 25 | UPDATE-TEST | test/seon/effect_test.clj:130–160; test/seon/render_simplification_test.clj:60–70; test/seon/sci/eval_test.clj:1421,1531,1642; test/seon/fn_test.clj:2002; test/seon/schema/datahike_test.clj; test/seon/render/web_test.clj; test/seon/render/retained_test.clj; test/seon/error_test.clj; test/seon/contracts_plan_test.clj (juniper fixture) |
| 3 | N2 leak | 13 | UPDATE-TEST | test/seon/cluster/agent_test.clj:1962–1971 (+ :1824 `routing-trial`) |
| 4 | FLAT | 12 | FIX-SRC (config) + UPDATE-TEST | src/seon/config.clj:127–546 (9 sites); test/seon/config_test.clj; test/seon/sci/eval_test.clj:608,1367; test/seon/schema_test.clj; test/seon/maintenance_test.clj:320; test/seon/render_source_test.clj:210; test/seon/contracts_plan_test.clj:64 |
| 5 | RENDER | 11 | FIX-SRC ×5, UPDATE ×4, probe ×2 | src/seon/cluster.clj (MCP projection); src/seon/print.cljc (fit-node); src/seon/render.clj (selection); src/seon/render/transcript.clj; test/seon/render/history_test.clj, entity_pairs_test.clj, web_test.clj:651; test/seon/help_test.clj |
| 6 | SHAPE | 7 | UPDATE ×3, FIX-SRC ×3, probe ×1 | test/seon/turn_test.clj:396; test/seon/fn_test.clj:486; src/seon/turn.clj:4461 and `committed-test-attributes`; src/my/edit.clj; test/seon/sci/eval_test.clj:1404; test/seon/turn_boot_test.clj:95 |
| 7 | ERRREC | 7 | probe → FIX-SRC | src/seon/error.clj (recording, steward); src/seon/turn.clj (phase-failed, escalation) |
| 8 | N3 skew | 6 | RERUN; class 14 structural | world overlay resource loader (src/seon/test.clj checkpoint/overlay ~1620–1680); resources/seon/schemas/seon.print.edn (working tree) |
| 9 | CFG | 6 | probe → FIX-SRC | src/seon/render.clj:143–156; src/seon/sci/eval.clj:3186–3194 |
| 10 | N1 custody | 5 | FIX-SRC + owner option A/B | src/seon/test.clj `bounded-result` (db7f1c919); src/seon/db.clj:457–490 (unchanged); test/seon/test_support.clj:662 (option B); test/seon/test/seed_preparation_test.clj:44 |
| 11 | WORLD | 5 | UPDATE-TEST | test/seon/agent_situation_test.clj; test/seon/run6_stall_test.clj; test/seon/config_test.clj:613; test/seon/sci/eval_test.clj:731; test/seon/help_trial_test.clj |
| 12 | N6 seed attr | 3 | UPDATE-TEST | test/seon/test/seed_preparation_test.clj:23–61 |
| 13 | N4 lazy install | 3 | FIX-SRC (probe first) | src/seon/sci/eval.clj `install-function-from-database!` (15daddb0a) |
| 14 | KONDO | 3 | operational | `(seon.fn.analyzer/analyze-dependencies!)` |
| 15 | N5 attempt world | 2 | UPDATE-TEST + ruling | src/seon/test.clj `attempt-request-context` (71f45f476); test/seon/test/clean_world_test.clj |
| 16 | SCI | 2 | FIX-SRC / ruling | src/seon/sci/eval.clj; test/seon/instrument_test.clj:412 |
| 17 | N7 pull render | 1 | probe → FIX-SRC | src/seon/render.clj (selection for explicit pulls) |
| 18 | DRIFT | 1 | harness H8 | src/seon/test/runner.clj snapshot guard |
| R0 | arm-first-loads | 13 cmds | FIX-SRC | src/seon/test.clj:2015–2040 (1ea87e4c2) |

## Commit-to-red map (NEW, each verified by reading the diff)

| commit | lands | reds |
|---|---|---|
| db7f1c919 S4a | host body custody | N1 (5); possibly CFG (6) |
| 15daddb0a S4b | lazy install binds unchanged compiled Vars | N4 (3) |
| 71f45f476 S4c | attempt agent and turn for effect-reaching members | N5 (2) |
| 17034b4e1 S2b-b + 4fd4c5b4b | seed preparation tests | N6 (3) |
| 105926712 or 8ebb23736 render | attribute identity / declared stage | N7 (1), unconfirmed between the two |
| 1ea87e4c2 + sweep commits | arm-first-loads compares with a stale snapshot | R0 (13 commands) |
| none | agent-test with-redefs future, run order | N2 (13) |
| none | uncommitted render-floor resources in the checkout | N3 (6) |

No red traces to 3a7ffa69c (no-provider move), 2b1027b5d (retention refusal) or a1b85c60d (adoption read checks).
Render 1c (44dc30ae6, 22:08 local) landed after this adoption and cannot cause these reds. It does cause the
post-restart entity-pairs contract failure (§RENDER).

## Verification limits

- This census read results only. Every fix sketch and every PROBE owner is inferred from source and diffs, not
  executed.
- The CFG, N4 and N7 attributions need the named probe.
- The 14:26Z re-run of sci.eval-test on the restarted JVM differs from the adopted run:
  - 9 members recorded no completed outcome (typed unknown).
  - `a-configless-database` failed 6 assertions instead of the adopted run's red set.
  - The adopted run is the one reported here.
- `my.catalog` evidence is command text only; run `ac7b686cf2f7` has no row.
- RESET NEEDED: none.

## Root runs (serially, after the named fixes)

```
(seon.fn.analyzer/analyze-dependencies!)                          ; KONDO
bin/test-check default --ns seon.cluster.agent-test --include-long ; after N2
bin/test-check default --ns seon.ai-test                          ; N2 regression: after agent-test
bin/test-check default --ns seon.problems-test                    ; R0
bin/test-check default --ns seon.render.web-debug-test            ; R0
bin/test-check default --ns seon.schema-usage-guard-test          ; R0
bin/test-check default --ns seon.sci.eval-test                    ; N1, N4, CFG
bin/test-check default --ns seon.contracts-compile-test           ; N3 rerun on the clean tree
bin/test-check default --ns seon.edit-test                        ; N3
bin/test-check default --ns seon.cluster.turn-test                ; census c class 1 still unproven
```

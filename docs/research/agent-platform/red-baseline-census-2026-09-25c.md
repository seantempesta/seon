---
type: research
status: complete
lane: census-checkpoint-reds
created: 2026-09-25
---

# Checkpoint reds, fourth census (2026-09-25 02:25Z)

The checkpoint ran 74 serial `bin/test-check default` commands and executed 562 members
(`tmp/orchestrator/verify-checkpoint-results-2026-09-25.txt`, `checkpoint-summary-2026-09-25.txt`). Root estimated about 70
reds from that text. The text prints only the first reds of each namespace, so this census reads the recorded evidence
instead. The read was MCP `eval_clj`, JVM mode, read-only, in the throwaway namespace `census-c-scratch`. It called
`(@#'seon.problems/failed-tests db)` (problems.clj:373) and kept each member whose latest `:seon.test/run` id is one
of the 74 checkpoint run ids. Failure text came from `seon.test/failure-message` (test.clj:57).

**Result: 127 red members, plus 17 typed unknowns.** The unknowns are all in `seon.cluster.turn-test`: "Never started:
request 3a717e13bfe4 stopped (:member-unfinished)". They carry `:seon.test/error-test-symbol`, not `:seon.test/sym`.
The census ran no tests, no `bin/seon init` and no writes.

## Ranked classes

| # | class | n | verdict | owning files |
|---|---|---:|---|---|
| 1 | NP: shipped `no-provider true` skips every provider stand-in | 33 (+17 never started) | **owner ruling**: config policy in the wrong place | config/default.edn:454; src/seon/turn.clj:4479 |
| 2 | ROWS: hand-built fixture rows the writer refuses | 22 | UPDATE-TEST | test/seon/render/web_debug_test.clj, schema_usage_guard_test.clj, test_failure_facts_test.clj, fn_test.clj, error_test.clj, error_write_timing_test.clj, turn_test.clj, page_review_test.clj, problems_test.clj, maintenance_schema_test.clj, my/agent_test.clj (+ owner nit src/seon/cluster/agent.clj:162) |
| 3 | PROBE: singles whose owner is not confirmed | 15 | probe first | see §PROBE |
| 4 | SHAPE: test calls a src function with a retired shape or arity | 12 | UPDATE-TEST | turn_test.clj, turn_loop_test.clj, web_debug_test.clj, cluster/mcp_test.clj, maintenance_test.clj, my/agent_test.clj, fn_test.clj |
| 5 | RENDER: floor printer order, MCP window, selection misses, retired pair expectations | 9 | FIX-SRC ×4, probe ×3, UPDATE ×2 | src/seon/print.cljc (fit-node), src/seon/cluster.clj (MCP projection), src/seon/render.clj (selection); render/history_test.clj, render/entity_pairs_test.clj |
| 6 | WORLD: the checkpoint world carries boot's config row and default's agent namespace rows | 8 | UPDATE-TEST (S2c ruled the world) | problems_test.clj, agent_situation_test.clj, run6_stall_test.clj, cluster/agent_namespace_test.clj, my/plan_test.clj, test/overlay_world_test.clj |
| 7 | JUNIPER: fixture entity schema lacks `:seon.program/partition` | 6 | UPDATE-TEST (one line) | test/seon/context_blocks_fixture.clj:25 |
| 8 | BOUND: over the declared bound, or never exited | 5 | BOUND / probe | fn_test.clj, issue_test.clj, loop_proof_test.clj, turn_test.clj |
| 9 | SCI: acquired contexts lose or refuse names; host-bound override premise | 4 | FIX-SRC (census b §J) / ruling | src/seon/sci/eval.clj; instrument_test.clj |
| 10 | ERRSHAPE: a receipt contract demands error members; phase-failed dropped | 3 | FIX-SRC ×1, probe ×2 | resources/seon/schemas/seon.eval.drive.edn:40–51; src/seon/eval/drive.clj:139 |
| 11 | READY: `readiness` answers `:seon.agent/count nil` in an agentless world | 2 | **FIX-SRC** | src/seon/cluster/boot.clj:461 |
| 12 | ERRDECL: the producer's declared error schema does not validate its observation | 2 | probe → FIX-SRC | src/seon/error.clj:508 (validator); producer in seon.flow fault path |
| 13 | KONDO: clj-kondo cache empty for seon.effect and seon.db | 2 | operational | `(seon.fn.analyzer/analyze-dependencies!)` |
| 14 | SKEW: the JVM loaded program predates HEAD's schema resources | 1 | RERUN after adoption | src/seon/program.cljc (9b4a7522f not loaded) |
| 15 | CANON: `canonical-definition` returns a non-EDN form instead of refusing | 1 | FIX-SRC | src/seon/schema.clj:642 |
| 16 | PRED: a predicate schema has no `:error/message` | 1 | FIX-SRC | resources/seon/schemas/seon.effect.edn:50 |
| 17 | DRIFT: worker-global instrumented set changed mid-run | 1 | harness H8 | src/seon/test/runner.clj snapshot |

Class 1 alone explains the two 120 s requests' failures (§1), the 17 never-started members, and part of the profile
maximum. Fix it first, then rerun `seon.cluster.turn-test`, `seon.turn-test`, `seon.turn-loop-test`,
`seon.turn-continue-test` and `seon.ai-test` before triaging those namespaces further.

## Root's hypotheses, verified or falsified

| hypothesis | finding |
|---|---|
| readiness `:seon.agent/count nil` (8) | **Verified, but 2 members, not 8** (boot-test status, mcp-test runtime-observation-bounds). The 8 counted command outputs, since the same members appear in 4 commands. Class 11. |
| fixtures miss `:seon.program/definition-digest` | **Verified**: 8 members (web-debug 4, schema-usage-guard 2, test-failure-facts 1, fn-test 1). Part of class 2. |
| `seon.db/db` given a keyword | **Verified**: mcp-test passes `::connection` plus `with-redefs boot/readiness`. The observation now calls `db/db` (cluster.clj:685, :704). Retired test premise. Class 4. |
| `namespace-seed-call` returns nil | **Verified, producer found**: the fixture's agent id `"maintenance-schema-test/root"` gives the namespace `my.agents.maintenance-schema-test/root`, and `program/declaration-row` answers nil for it. `namespace-seed-call` (agent.clj:162–184) returns `[nil]` silently; it should refuse by name. Class 2. |
| `seon.id/valid?` called with 0 arguments | **Falsified as a defect**: the test calls `(seon.id/valid?)` with 0 arguments on purpose (instrument_test.clj:433). The real failure is at :412. The base context does not interpret the agent row for the host-bound `seon.id/valid?`, so the fork's `:record` recorder is never reached. Class 9, needs a ruling. |
| error recording misses `:seon.error/at` / declared-schema nil | **Two distinct classes.** (a) `run-receipts` output schema `:seon.eval.drive/evaluation` *requires* `:seon.error/at`, `/layer` and `/operation` on every receipt, including successful ones (seon.eval.drive.edn:48–50). That is FIX-SRC, class 10. (b) Hand-built error sources lack `:seon.error/declared-schema`, which is required since 373820ffd (09-22). The cond at turn.clj:3638–3664 returns nil for them. UPDATE-TEST, class 4. |
| turn/settle! and open-turn argument shapes | **Verified**: retired request shapes (`:seon.sci.eval/evaluation`, `:seon.turn.work/situation`, `:seon.ai/max-tokens`). Class 4. |
| a fixture retracts an agent | **Verified**: my/agent_test.clj:33 `retractEntity` on an agent. "Agents retain their identities" is the ruled writer law. Class 2. |
| `canonical-definition` receives a vector | **Refined**: it *returns* a form that fails its own `:seon.schema/definition` output contract (fn.clj:717 caller), instead of throwing its named "contracts are data" refusal for the `(fn [r] ...)` literal. Class 15. |
| cluster.turn-test and seon.turn-test each ran 120 s | **Verified cause, class 1**: `drive!` loops (e.g. 80 passes) whose provider stand-in is never reached. A member was still running at the 120 s `:seon.test/check-time-limit-ms` (default.edn:388), so 17 members never started. In seon.turn-test, `virtual-turns-use-the-proc...` did not exit (56 s remainder) and `recovery-preserves...` took 19.8 s. |

## Live faults

**StackOverflowError ×11, operation `seon.cluster.agent-test/fixture-evaluate`, frame `clojure.lang.Var.invoke`,
last 01:10:13Z.**
- Producer: test/seon/cluster/agent_test.clj:71–78 and :158–160. `with-connection` defaults its evaluator to the Var
  `#'fixture-evaluate`. Inside the body it runs `(with-redefs [real-evaluate armed-evaluate sci.eval/evaluate
  selected-evaluate] ...)`, where `armed-evaluate` is whatever `sci.eval/evaluate`'s root holds *at that moment*.
- At 01:07:12, `wake-routing-conservation-property` (run 28045eb07b2e) did not exit. Its thread 1974 stayed live
  inside that `with-redefs`, so the global root stayed `#'fixture-evaluate`.
- The next `with-connection` then captured `armed-evaluate = #'fixture-evaluate` and redefined `real-evaluate` to it.
  `fixture-evaluate` calls `real-evaluate`, which is `#'fixture-evaluate`, so the Var invokes itself until the stack
  overflows.
- The frame, the operation and the timing (01:07 → 01:10) all match.
- The live root is restored now: `(class seon.sci.eval/evaluate)` is the instrument wrapper `AFunction$1`.
- Law broken: "Never `alter-var-root`, `with-redefs` ... over default's Vars". The body that never exited makes it
  unrecoverable.
- Fix (UPDATE-TEST, ≤15 lines): `with-connection` calls the real evaluator with no redefinition when no observer is
  supplied. An observer is passed as data on the cluster handle only if `seon.turn/evaluate-sources` gains that member
  (owner ruling). Otherwise delete the observation tests that need it.

**Profile `seon.schema/call-with-projection max 115902 ms`.**
- `call-with-projection` is inclusive. At test.clj:1872 it wraps one member's `bounded-result`.
- The recorded red for `seon.cluster.agent-test/wake-routing-conservation-property` (run 28045eb07b2e, 01:07:12Z)
  reads: "did not exit within :seon.test/check-time-limit-ms remainder **115900** ms; thread 1974 remains live".
- So the 116 s is that one member body: the routing-conservation property, blocked until its request's 120 s check
  limit. It is not a projection build.
- The same thread caused the StackOverflow above.
- The cumulative profile keeps the maximum since JVM start (00:50:43Z) and has no per-call timestamps.
- Other lines: `seon.test/run max 120719` is the cluster.turn-test request (class 1).
  `call-detecting-program-change` and `run-var! max ~64.9 s` are one member at a 65 s remainder.
- **>10 s rule:** needs an issue. It belongs with census b §E ("did not exit"); root files or links it.

## Class details

### 1. NP: shipped `:seon.config.ai/no-provider true` (33 + 17 never started)

- First failure line: `seon.ai-test/the-shipped-cluster...`
  `actual: (not (= #:seon.ai{... :timeout-ms 180000} #:seon.ai{... :no-provider true ...}))`.
  In cluster.turn-test: `(not (= 2 (count evaluations)))` gives 0, and shown/output are nil.
- Producer: df3b03f6d (09-24 15:41, owner "no paid agent calls") set `:seon.config.ai/no-provider true` in
  **config/default.edn:454**, which is the shipped document.
  - Probe: `(:seon.config.ai/no-provider seon.config/defaults)` gives `true`.
  - Every test world therefore compiles it: `with-cluster` → `config/apply!` → `compile-manifest` over the default
    document.
  - turn.clj:4479 checks `no-provider` first in the provider `cond` and freezes `{:seon.ai/text ""}`. As a result, the
    30 `with-redefs [ai/complete ...]` stand-ins in cluster/turn_test.clj are never called, and neither is a refused
    capture branch (turn_loop_test refused-generated-opening).
- Verdict: the owner is wrong about *where* the dial lives, not the dial itself. Operator policy has become a shipped
  default that every test world inherits. **Three options for root and owner:**
  - **(A, recommended)** Move the dial to the development overlay that boot applies to default
    (config/development.edn), or reapply it at boot. The shipped default returns to `:seon.config/absent`, and tests
    are unchanged. Cost: one EDN move, about 3 lines. Sacrifice: a nuke that skips the overlay would call the provider.
  - **(B)** Keep the shipped default and have every provider-stand-in fixture pass
    `:seon.config.ai/no-provider config/absent`, as `:seon.config.ai.backup/model` already does at turn_test.clj:209.
    Cost: about 8 fixture helpers. Sacrifice: every new fixture must remember it.
  - **(C)** Have the test world's checkpoint drop operator dials. Sacrifice: a second config path, which the laws
    forbid.
- The members are listed in the class table. The fn-test settled-form and turn-boot entries are *likely*: their
  symptoms (no calls recorded, no pass id) match, but no run confirms them.

### 2. ROWS: hand-built rows the writer refuses (22)

| sub-rule | n | first line | fix |
|---|---:|---|---|
| missing `:seon.program/definition-digest` (f27b96c19) | 8 | `seon.db/transact! refused transaction data at [49303 :seon.program/definition-digest] ... Entity: #:seon.schema{:key :seon.render.web-debug-test/identity-alias}` | build schema rows with `program/declaration-row` or `schema/canonical-schema-rows` |
| component validation: `unowned-entity`, `missing-component-schema` (74d020fc6) | 6 | `Complete component validation refused: unowned-entity; ... #:seon.schema-usage-guard{:base 7}` | give the data entity an owner or identity in the fixture schema |
| issue tests retracted by a non-creator | 2 | `Started issue tests and their authority must be preserved; only the creator may remove tests.` | error_write_timing retention, turn-test transitions: remove through the creator or delete the step |
| agent row without branch / agent retracted / agent id with `/` | 4 | `refused ... [49313 :seon.agent/branch]`; `Agents retain their identities`; `namespace-seed-call refused return value at [0]: expected a map, got nil` | `test-support/agent-tx`, archive instead of retract, an agent id without `/`. Owner nit: `namespace-seed-call` should refuse by name when `declaration-row` answers nil (agent.clj:183, ≤10 lines) |
| retract of a required ref | 1 | page_review_test.clj:46 `[:db/retract [:seon.turn/id "current"] :seon.turn/agent ...]` answers nil | build the scenario with turns the writer admits |
| same file, assertion after the refusal | 1 | schema_usage_guard_test.clj:440 set mismatch | rerun after the fixture fix |

### 3. PROBE (15), singles with an unconfirmed owner

- cluster.turn-test `a-red-form-routes...` (:1546): an assignment message exists in the world.
- cluster.turn-test `a-turn-hands-its-clusters-projection...` (:3181): the projection was rebuilt once. Possibly a real
  per-commit re-derivation.
- error-test `error-declared-schemas-persist...` (:1263): `:seon.agent/error-agent-id` is nil.
- error-test `error-identity-and-occurrences...` (:116): `error/steward` is nil.
- error-write-timing `program-call-changes...` (:17): the spy is never called.
- fn-test `contracted-rows-carry-queryable-facts...` (:695/:698): spec, arities and analysis are asserted in different
  transactions.
- fn-test `indexing-resolves-its-declaration-world-once...` (:2665): `packaged-forms-calls` is 2, not 1. This is a
  cost regression candidate in src/seon/fn.clj.
- problems-test `missing-model-findings...` (:220): render producers are nil.
- problems-test `missing-projection-is-a-refusal...` (:553): the refusal has no layer or operation.
- web-debug `agent-identity-groups...` (:667): the settings and runtime units are absent.
- reset-edges ×2 (:261, :129): issue retractions in a note transaction, and 6 schema pairs.
- turn-loop `the-committed-set-extracts...` (:683): `committed-attributes` now ignores a propertyless map.
- turn-test `an-open-turn-read-refusal...` (:949).
- turn-test `settlement-refuses-a-divergent...` (:1498): an identical declaration is now refused.

### 4. SHAPE: retired call shapes (12, UPDATE-TEST)

- `seon.turn/settle!`: needs `:seon.sci.eval/evaluation` (turn_test.clj:343).
- `open-turn`: `:seon.turn.work/situation` must satisfy its schema (:395).
- `record-attempt!`: needs `:seon.ai/target :seon.ai/max-tokens` (:56).
- `seon.error/commit-tx`: needs `:seon.error/declared-schema` (:1928); the turn-loop `refuse-phase!` source
  (turn_loop_test.clj:606) likewise.
- `seon.cluster.prompt/effective-ai-settings`: now 3-ary (web_debug_test.clj:1044).
- `seon.error/normalize`: needs `:seon.schema/projection` (web_debug_test.clj:596; run6_stall_test.clj:42, counted in
  class 6).
- Synthetic fixture definitions need database and source (maintenance_test).
- mcp-test ×2: keyword connection plus `with-redefs`.
- `:my.agent/turns-left` left settings in 0dca8534e (09-14): my/agent_test.clj:58.
- fn-test `analyzer-edges`: reads the pre-flat `[:seon.error/data :seon.error/member]` (census b §G).

### 5. RENDER (9)

- **value-test (FIX-SRC, verified).**
  - The probe shows `value-node` returns whole order (keywords, then numbers). HEAD's `seon.print/fit` re-sorts map
    entries by emitted text: fit-node, print.cljc:1189–1202 at HEAD (d0817d49b).
  - Result: `(0 1 10 11 2 ... :0 :1 ...)`. This contradicts the emitter comment "the floor places them once ... never
    re-sorts".
  - The working tree holds an uncommitted print.cljc edit (−346 lines). It is the render-floor lane's, so root should
    confirm that it removes this re-sort.
- **mcp-test ×3 (FIX-SRC, census b §7).** 25,130 and 24,613 bytes against an 8,192-byte window. Nested elisions lose
  their paths.
- **Selection misses (probe).** contracts-plan ×2 and turn-test `a-refused-generated-form`. For a
  `:seon.instrument/contract-error` value, the schema stage has no candidates, so the attribute printer shows the
  refusal and its grammar fragments are missing. The render 1a declared stage (8ebb23736) may not be adopted in the
  JVM, so probe after adoption.
- **Retired expectations (UPDATE).** history-test: `:seon.message/to` now has a declared pair
  (`seon.render.transcript/inbox-form`). entity-pairs: the requery form is now `seon.test/recorded-result`.

### 6. WORLD (8, UPDATE-TEST)

The S2c checkpoint (41f2f0b53, test.clj:1620–1645) seeds boot's config row: `max-episode-runs 100`, and the
`no-provider` from class 1. The program overlay (test.clj:1677) carries default's agent-authored `my.agents.root`
namespace row.
- `turns-remaining 0` is expected, but the world answers 100 (agent_situation_test.clj:34, run6_stall_test.clj:83).
- problems-test ×3 and agent-namespace-test report `my.agents.root` as unowned.
- my.plan-test uses the cluster `"plan-map-defaults"`, which has no config row.
- overlay-world: the extra-schema `sentinel` attribute is installed in the "clean" world (729d50e4b installs the
  candidate's attributes). This conflicts with the test; root rules which one stands.

Fix: assert relative to the fixture's own names, and seed the fixture's cluster through `seed-cluster!`. Each file
needs about 10 lines or fewer.

### 7. JUNIPER (6, UPDATE-TEST, one line)

- First line: `Juniper schema declaration failed: ["An identity-bearing entity schema must declare its partition."]`.
- Producer rule: `assert-entity-partition!` (schema.clj:1514), from 8a069b5e4 on 09-22.
- Fix: test/seon/context_blocks_fixture.clj:25 becomes
  `[:map {:seon.db/attributes true :seon.program/partition :seon.data} ...]`.

### 8–17 (short)

- **8 BOUND.**
  - `indexing-uses-a-prebuilt-manifest` 20.7 s, with `:seon.fn/index-phase` nil.
  - `issue-worker-opening-links-its-issue` 9.7 s.
  - loop-proof `running-fixture` waited 20 s for `armer-stopped`, the same class as census b §E.
  - turn-test `recovery-preserves...` 19.8 s.
  - `virtual-turns...` did not exit. It likely falls under class 1.
  - Each needs its named operation measured, per the >1 s rule.
- **9 SCI.** Per census b §J:
  - `acquisition-orders...`: "authored.target/increment does not name an installed SCI Var".
  - branch-execution: `seon.fs/write-edn-atomically!` interpreted, "Unable to resolve symbol: fs/write-edn-atomically!".
  - error-test `row-acquisition`: `:seon.sci.eval/row-member` is nil.
  - instrument-test: see the hypotheses table. The ruling needed is whether a sovereign fork's compiled wrapper may
    record through the fork's recorder, or whether the test must use an interpretable agent function.
- **10 ERRSHAPE.**
  - The drive evaluation schema requires error-base members, and the producer writes `""` sentinels through
    `get-else` (drive.clj:156–157). Fix: drop `:seon.error/at`, `/layer` and `/operation` from
    `:seon.eval.drive/evaluation`, make `:seon.cluster.eval/error` optional, and omit absent values. That is about 12
    lines.
  - turn-loop ×2: `:seon.turn.loop/phase-failed` is absent from the recorded value (turn.clj:3410), so probe whether
    `error/recording` drops that undeclared member.
- **11 READY.** Probe: `(db/q '[:find (count ?a) . :where [?a :seon.agent/id "census-no-such-agent"]] db)` answers
  `nil`. boot.clj:461 then reports nil in an agentless checkpoint world. Fix:
  `(or (db/q ...) 0)`, one line.
- **12 ERRDECL.** "The supplied error declaration does not validate its observation" (error.clj:517). cluster-test
  core-fault declares `:seon.flow/exception-error`, and error-test schema-refusals declares
  `:seon.schema/validation-refusal`. Probe the failing member with `m/explain` on each observation.
- **13 KONDO.** "clj-kondo holds no analysis of seon.effect / seon.db". Stale cache: root runs
  `(seon.fn.analyzer/analyze-dependencies!)`. Do not change code.
- **14 SKEW.** "A program identity attribute declares no source attribute" for `:seon.error.occurrence/id`. It is the
  open fault 2633e811…, last 02:14Z.
  - The checkout's `source-family?` (program.cljc:17, 9b4a7522f) does not resolve in the JVM.
  - Default's own projection is fine: `shapes-in` gives 6.
  - The world overlay reads HEAD's resources while the old `shapes-in` runs. RERUN after root adopts HEAD. This is
    publication skew, not a code defect.
- **15 CANON.** schema.clj:642: the walk must reach the anonymous callable inside `[:fn {...} f]` and throw its named
  refusal. That is ≤15 lines. Probe first with `(schema/canonical-definition '[:fn {} (fn [r] r)] {})` in a scratch
  namespace.
- **16 PRED.** seon.effect.edn:50: `[:fn {:gen/schema ...} clojure.core/ifn?]` needs
  `:error/message "must be a callable result validator"`. Probe: it is the only registered `:fn` without a message.
- **17 DRIFT.** `dev-cache/*`, `seon.dev.docstring/*` and others were instrumented during the member, so the runner's
  snapshot guard fired. A namespace load raced the member. Harness H8.

## Verification limits

- The census uses latest results only. A member that went green after 01:50Z in a later run is not counted.
- For the PROBE items and the classes marked "likely", the owner was read, not executed.
- The loaded JVM differs from HEAD in at least program.cljc and print.cljc. Several classes (5 selection, 14) must be
  re-read after adoption.
- RESET NEEDED: none. Root should adopt HEAD before rerunning.

## Root runs (serially, after class 1 and adoption)

```
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --ns seon.turn-loop-test
bin/test-check default --ns seon.turn-test
bin/test-check default --ns seon.ai-test
bin/test-check default --test seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault
bin/test-check default --test seon.fn-test/publication-writes-one-definition-digest-per-declaration-family
```

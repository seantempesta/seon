---
type: research
status: diagnosis-complete-with-explicit-proof-limits
created: 2026-09-23
scope: error-constructor E0a–E30; two orchestrator proof rounds
---

# Error constructor reds: attribution and remaining proof

The system already constructs flat errors and tests branch-local program values. Compare each failing boundary with its pre-cut owner before changing anything; preserve the existing constructor and repair the smallest fixture or owner that actually differs.

**No demonstrated E-slice regression was found.** The E13–E30 continuation below covers 43 additional red identities, including 11 duration-only cases with unresolved attribution. In the first proof, twenty-five of the 27 red test identities have a located pre-existing cause or pre-existing fixture/owner disagreement. The install-gate red is localized to pre-existing error observation/settlement, but its complete cause is not established. The parallel property is an unfinished request, not a proved behavioral failure; attributing its elapsed cost to either the cut or the parent requires a comparison this record does not contain. Those two limits must not be turned into a blanket “all reds pre-existing” or a green cut.

Diagnosis only: no source/test edits, publication, reset, restart, test run, extra JVM, worktree, foreign-session interaction or push. Only this report is committed. Net source **0**, net test **0**. Recommendations below are diagnostic dispositions, not an independently reviewed implementation authorization.

## Evidence and runtime boundary

- Cut: `bab838388` through `d49b574be`; parent is `bab838388^`. Reviewed [manifest](error-constructor-cut-manifest-2026-09-23.md) and [landing](../../prds/agent-platform/landing/lane-sol-error-constructor-2026-09-23.md).
- Full recorded output: `tmp/orchestrator/proof-errcons.txt`, 191 lines / 27,542 bytes. Run `39476d5bd9dc`: **112 executed, 1 reused, 475 passes, 17 failures, 23 errors, 120,518 ms**, false. The failure/error counts count observations, not 40 distinct test identities. There are **27 red identities**.
- `bin/seon status` and MCP `runtime_status` both observed default PID **44576**, start **2026-09-23T17:43:28.123Z**. Program archive: `data/source/b1ff7ba6cf9be07701393a3038f7f48eb6f3bb3a`; hook publication off. This includes all E-slices. Checkout HEAD at initial inspection: `bf4f1bf0c`; source/test/dependency WIP is foreign and was not adopted or modified.
- **Degraded boundary:** runtime status reports `seon.ai/complete` replaced by `seon.cluster.agent_test$recording_completer$fn__431327`. The log names live thread **715**, branch **:agent-fe95ec16676c**, at timeout. The property contains that `with-redefs` at `test/seon/cluster/agent_test.clj:639`. Do not assume the test exited or instrumentation was restored. This lane does not repair its state.
- MCP JVM tools were available. All probes used explicit root `/Users/sean/src/seon`, cluster `default`, namespace `diagnosis.error-constructor-20260923`, private session `error-constructor-reds`, `read_only true`, timeout 1000 ms. No default Var was redefined. These are host derivation/read observations, not a rerun of the red tests or a browser observation.
- `git diff bab838388^ d49b574be --` the seven red test files is empty. `src/seon/turn.clj`, `src/seon/sci/eval.clj`, `src/seon/test.clj`, `test/seon/test_support.clj`, and the agent/namespace/test entity schemas are also outside the cut. Being untouched alone is not the attribution proof: the failing input, owner behavior and constructor delta are examined below.
- Source line references below use `d49b574be` unless explicitly called live. History evidence was obtained with `git blame -L START,END d49b574be -- PATH`; this avoids blaming other lanes' current WIP.

## Every red

Abbreviations: **F** = repair a retired fixture/expectation; **O** = preserve wanted behavior and fix its existing owner; **D** = delete an assertion of retired machinery, retaining replacement behavior coverage. These answer the three questions: deleted machinery, retired assumption, or wanted behavior at a surviving seam. Evidence classes are expanded immediately below.

| Red test (namespace prefix `seon.`) | Attribution | Disposition / evidence |
|---|---|---|
| `ai-test/agent-overlay-reads-only-derived-per-agent-attributes` | Pre-existing A; not E4 `c47da5ccc` | F: planner fixture lacks required branch. |
| `background-test/terminal-background-results-open-one-result-only-run` | Pre-existing B; not E6 `a59ebd7e3` | F: absent `clojure.core/identity` program lookup ref. |
| `blob-test/binary-content-round-trips-on-both-sides-of-the-inline-threshold` | Pre-existing C; not E0b `2efb85875` | F: unowned threshold component. Exact earlier red recorded. |
| `blob-test/interrupted-staging-leaves-the-store-unchanged` | Pre-existing C; not E0b | F: same setup refusal before staging. |
| `blob-test/large-binary-write-and-small-chunk-have-bounded-allocation` | Pre-existing C; not E0b | F: same setup refusal before allocation subject. |
| `blob-test/utf8-content-round-trips-through-the-memory-backend` | Pre-existing D; not E0b | O: `stored-binary` input contract rejects the memory backend callback shape. |
| `bootstrap-test/absent-intent-budget-refuses-loudly` | Pre-existing E; not E7 `1bb9a1e71` | O: budget query is not joined to the agent's cluster; inherited default still supplies 1024. |
| `bootstrap-test/every-opening-candidate-subject-is-an-entity-lookup` | Pre-existing F; not E7 | F: test row lacks definition digest at line 181. |
| `bootstrap-test/first-agent-supervision-is-one-self-erasing-system-run` | Pre-existing G; not E7 | F: inherited root message history makes the lesson one form. |
| `bootstrap-test/intent-membership-is-the-only-opening-delta-and-is-budgeted` | Pre-existing F; not E7 | F: synthetic test row lacks definition digest at line 317. |
| `bootstrap-test/reborn-opening-retains-authored-namespace-membership` | Pre-existing F; not E7 | F: test row lacks definition digest at line 253. |
| `call-preparation-test/a-compiled-first-party-call-is-prepared` | Pre-existing H; not E8 `4a5cea540` | O/F: raw compiled probe Vars are unarmed; missing refusal evidence, plus stale whole-vector expectations. |
| `call-preparation-test/a-row-disagreeing-with-its-supplier-is-refused` | Pre-existing F; not E8 | F: shared `sample` namespace fixture lacks digest. |
| `call-preparation-test/a-row-naming-an-absent-supplier-is-refused` | Pre-existing F; not E8 | F: same `sample` fixture. |
| `call-preparation-test/a-row-published-after-a-warm-plan-is-seen-synchronously` | Pre-existing F; not E8 | F: same `sample` fixture. |
| `call-preparation-test/a-second-cluster-compiles-from-its-own-facts` | Pre-existing F; not E8 | F: same `sample` fixture. |
| `call-preparation-test/a-synthetic-third-default-is-derived-from-its-row` | Pre-existing F; not E8 | F: same `sample` fixture. |
| `call-preparation-test/an-acquired-cluster-context-prepares-its-calls` | Pre-existing I; not E8 | F: two unresolved probe symbols; raw `cluster-ctx` has not acquired the program. |
| `call-preparation-test/an-acquired-context-supplies-a-leading-database-to-plan` | Pre-existing I; not E8 | F: same acquisition mistake for `my.plan/plan`. |
| `call-preparation-test/an-undeclared-function-gets-an-empty-plan` | Pre-existing F; not E8 | F: same `sample` fixture. |
| `call-preparation-test/an-unrelated-transaction-refreshes-without-changing-the-row-basis` | Pre-existing F; not E8 | F: same `sample` fixture. |
| `call-preparation-test/call-preparation-cache-coherence-does-not-widen-evaluation-evidence` | Pre-existing F; not E8 | F: `unrelated-evidence-one` namespace fixture lacks digest. |
| `cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function` | Pre-existing J; not E3 `9848f98f7` | F: helper requires one cluster, branch contains default plus identity-cluster. |
| `cluster.agent-test/episode-cap-refusal-test` | Pre-existing K; not E9 `8acaaefeb` | O: unscoped cluster-default cap query defeats the fixture's cap 3. |
| `cluster.agent-test/failed-turn-transform-keeps-a-live-backstop-after-releasing-its-permit` | Pre-existing L; not E9/E5 `6314ef453` | D/F: step's existing finally cancels the backstop after publishing completion; test waits for the cancelled event. |
| `cluster.agent-test/install-gate-failure-closes-with-a-durable-diagnostic` | **Unresolved attribution within pre-existing M boundary; no E-slice delta demonstrated** | O: producer probe retains `phase-failed`; full stored/shown path must be compared. Do not delete the regression based on nils alone. |
| `cluster.agent-test/n-agent-parallel-turns-property` | **Request deadline N; cut-versus-parent cost attribution unproved** | O: unfinished at 8,436 ms remaining; isolate the property request and prove exit. No behavioral verdict yet. |

## Located causes and history

**A — agent branch fixture.** `test/seon/ai_test.clj:605–609` (`3041973a71`) inserts `{:seon.agent/id "planner", :seon.agent/settings {...}}`, without branch. The writer refuses that exact map before `ai/agent-overlay`. `resources/seon/schemas/seon.agent.edn:21` requires `:seon.agent/branch` since `1ada780500`; mode is required at :22 too. E4 changes error literals in AI, not this input or the writer/schema. Use the canonical agent creation transaction with explicit cluster/branch, then the settings component; do not loosen the entity schema.

**B — absent program owner.** `test/seon/background_test.clj:21–22`, `d7b3930ff4`, writes `:seon.effect/owner [:seon.fn/sym 'clojure.core/identity]`. The writer reports that ref absent. Live read confirms it is nil. This is fixture lookup provenance, not evidence of the separate capability-dispatch failure class: the test never reaches dispatch. Supply a real indexed owner with its real capability declaration. E6's two constructed background refusals are downstream of this failed fixture write.

**C — unowned component.** `test/seon/blob_test.clj:38–48`, `3041973a71`, installs a hand-rostered attribute then transacts `{:seon.config.eval.result/blob-threshold 4096}` without an owning configuration entity. All three tests fail in `with-file-blob-store` before the subject. [Cold-gate triage C3](cold-gate-triage-2026-09-22.md) already records the identical binary-round-trip error (raw line 5,503). Keep the real file-store observation where required; seed a complete owned config through its writer instead of weakening component validation.

**D — actual blob owner defect predating E0b.** `src/seon/blob.clj:109–117` was introduced by `24ea81bf58`. Its contract accepts a raw byte array OR `{:input-stream InputStream}`. Konserve memory `-bget` at `reference-code/konserve/src/konserve/memory.cljc:77–82` calls back with `{:input-stream (second (get @state key))}`; `-bassoc` at :83–94 stores the supplied input, which is bytes for this path. Thus the actual input is `{:input-stream <byte[]>}`. The existing body already unwraps it correctly, but the input contract rejects it before entry. This is not the converted content-mismatch error. Smallest owner repair to review: admit the backend's byte-array-or-stream value under the map key, keeping the existing body and output union. The real memory round-trip is the surviving regression.

Dependency pin at cut HEAD: Konserve `5b39fddd6ae58bae7d08880daecd1ad7242ea273`. The callback guarantee and supplied value are above; each blob read invokes it, proportional to blob bytes read, with no whole-program recomputation or new cache needed. Malli `8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`, `reference-code/malli/src/malli/core.cljc:1210` (`-map-schema`) and :2237 (`-function-schema`), owns open maps and arity grouping. No replacement validator is warranted.

**E — missing budget is masked by another cluster.** The test at `test/seon/bootstrap_test.clj:377–397` retracts its own cluster's budget. `src/seon/bootstrap.clj:375–384` binds the agent and cluster independently: no join from that agent to that cluster. Those query lines blame to `9eae1664db` / `3f07beb88d`, before the cut. Canonical branched fixtures now inherit the captured database (`test_support.clj:627–648`, `678009fcd0`), including default's budget. The query therefore still finds a budget and does not construct the missing-budget error. Live default has 1024; the exact converted constructor preserves both supposedly lost keys. Fix the owning query's cluster relationship, not the constructor or this wanted absence assertion.

**F — declaration digests.** `resources/seon/schemas/seon.ns.edn:17` requires `:seon.program/definition-digest` (`f27b96c190`); the test entity requires it at `resources/seon/schemas/seon.test.edn:88`. Three bootstrap synthetic rows (:181–185, :253–258, :317–323) blame to `3041973a71`, `9107232d7c`, `55a0abae02`, `ce83344cee`. None supplies the digest. Seven call-preparation reds share `synthetic-population`, whose namespace literal at `test/seon/call_preparation_test.clj:182` is from `d9b56f3419`. The evidence-coherence test writes another incomplete namespace at :865 (`391e3be127`). These refusals precede the constructor subjects. Produce complete analyzed declarations through the canonical fixture/indexing seam; do not invent a placeholder digest, test or schema exemption. All remain wanted behaviors, with retired fixture assumptions.

**G — the supervision input history changed.** `src/seon/bootstrap.clj:805–806` (`37df160dda`) omits each lesson already demonstrated by root. The observed single source is explicitly the `send? false` branch (:821–826), completing after reading history. The fixture's blanket two-form assertion at `test/seon/bootstrap_test.clj:462–463` assumes empty root history; branched fixtures inherit it. Read-only `root-messaged-agent?` on default is true. Preserve the self-erasing lesson behavior; establish the intended history in the fixture or test the state-dependent one-form result. No error construction participates in generating these source strings.

**H — compiled probe arming / stale refusal assertions.** The failed calls at `test/seon/call_preparation_test.clj:578–592` supply the full declared arity. The existing preparation path leaves explicit values alone; E8 changes incoherence, unavailable supplier, invalid supplier output and ambiguous placement errors, none of those exact-arity success paths. The test passes raw host Vars into SCI (:535–539), expecting their own contracts to reject `1`. Live root classes are the raw `probe_received_connection_QMARK_` and `probe_received_database_QMARK_` implementations, whereas `diagnostic` has a wrapper root. These boolean probes return false for invalid values, and `refusal-data` (:526–536, unchanged) returns its `committed` sentinel for a non-error result: hence all queried keys are nil. The test's namespace is loaded by `seon.test/resolve-test` via `requiring-resolve` (:1317) without an arming operation at that point. Preserve the caller-wins proof, but establish canonical arming after loading before expecting refusal. Additionally, [the earlier consumer-fixture issue](../../seon/issues/turn-consumer-fixtures-read-retired-result-storage.md), “Context-blocks HEAD-only verification — 2026-09-09,” records this test's stale full-vector offending-value expectation. Do not fix five nil assertions merely by changing expected vectors; restore the actual contract boundary first. Historical assertion lines blame to `d934f43554`, `fd93f709a9`, `2add67c856`, `9bf22ecd9b`, not E8.

**I — interpreter construction is not program acquisition.** The tests call `sci.eval/cluster-ctx` directly at :748 and :788, then use raw `sci/eval-string*`. `src/seon/sci/eval.clj:2726–2752` builds the minimal context, installs call preparation and a program-identity listener; it does not call `acquire-program!`. First use through Seon's evaluation/acquisition entrance supplies that step. Relevant lines blame to `430fc91a1a`, `846bfd0f44`, `03bd7cfc9a`, `306f324314`. The three supposedly missing functions have real live program rows and definition digests. This is not absent program data and not evidence of an unregistered capability. Use the canonical acquired test context and real SCI evaluation entrance; the test's lower-level construction bypasses them.

**J — one-cluster fixture assumption.** `test/seon/cluster/agent_identity_test.clj:19–26` seeds identity-cluster on a branch already holding default. At :74 it calls `test-support/agent-tx` without an explicit cluster. `test_support.clj:279–285, 713–719` (`090601ae1a`) explicitly refuses unless there is exactly one cluster. Give `agent/creation-tx` the already-known identity-cluster, as this file's first helper does. The red is not from E3's missing-agent refusal in `src/seon/agent.clj`.

**K — cap query uses any cluster config.** `test/seon/cluster/agent_test.clj:1337–1340` adds cap 3 to a fixture that already contains default. `src/seon/turn.clj:2691–2694` (`820d0ab608`) queries any config's `max-episode-runs` after checking issue/agent overrides; it never scopes that fallback to the agent. The episode-count assertions succeed, while next-work/deferred/prose assertions fail together because the cap is not the intended 3. Same data-flow class as E, at a different owner. Repair cluster selection at that fallback; do not change all five downstream expectations or add a second cap mechanism.

**L — cancelled backstop.** The test (:1016–1066, originating `6a99a7a134`) injects a throwing transform, observes lifecycle completion, then awaits a second backstop fault. `src/seon/turn.clj:5593–5599` publishes ready and cancels the backstop in `finally`, even when the transform throws. Those lines predate the cut (`120caf85e7`, `3f07beb88d`, `cc1f681b5e`). The recorded await error and **20,486.695458 ms** duration failure are this same event mismatch; E5's await constructor neither schedules nor cancels anything. Delete the assertion of the cancelled secondary event if the current fault policy owns the transform exception; preserve a regression proving the original fault, completion and stopped/failed graph. If a post-completion backstop is still required, that conflicts with the existing finally and requires an explicit policy ruling, not a constructor patch.

**M — install-gate evidence, bounded uncertainty.** The two failed expectations are :1120/:1122 (`e7ebe4cfe5`). The test did observe a diagnostic, an evaluation, closure and no installed function; its complaint is specifically absent `phase-failed` on the stored error and shown value. The phase producer at `src/seon/turn.clj:3419–3448` is unchanged; a live read-only call with the test's exact thrown ex-data produces `:seon.turn.loop/phase-failed true` and the phase-failed declaration metadata. Error occurrence selection at `src/seon/error.clj:1406–1412` and the settlement/render path are pre-existing. E0a's changed `prepare` seed is value-equivalent and still merges the source observation last; it does not remove this member. **Not proven:** which subsequent boundary omitted/projected the field, whether the test should read a rendered fact/occurrence instead, or whether this exact full-path red reproduces on the parent. Keep it an O disposition pending one focused parent/current comparison with full diagnostic and shown text. Do not claim the known phase helper probe proves durable settlement.

**N — deadline and retained mutation.** The request reaches the property with only 8,436 ms left. Its 12 trials (1–3 agents each), `test/seon/cluster/agent_test.clj:728–738`, predate E (`11b2ee1a09`, `e4f8bbe07f`). Admission correctly stops after the body fails to exit and retains its branch. The aggregate request timing cannot distinguish a slow property, prior-body cost, missing terminal event, or added constructor overhead. No parent timing exists here. Splitting fixes starvation of later proofs, not the property's five-second body requirement or any live-thread leak. Preserve the concurrency behavior regression and investigate its actual terminal wait with its owner.

## Constructor delta: why the observed nils do not identify an E-slice loss

For an admitted literal with supplied `at`, layer `l`, operation `op`, and remainder `m`:

```clojure
;; Before
(merge {:seon.error/at at :seon.error/layer l :seon.error/operation op} m)
;; After, E0a's positional arity
(seon.error.refusal/diagnostic at l op m)
```

The new arity performs exactly that merge and calls the unchanged map arity. With no `:seon.error/throwable`, it returns the observation unchanged. With one, it retains the pre-existing constructor's whole-cause behavior. Header inputs still evaluate before the remainder; the admitted converted literals do not override header keys. Open-map and function-arity behavior belong to Malli, not a new selector. Cost is O(members), plus existing O(cause links + frames) for a Throwable; runtime overhead still needs the manifest's parent/child measurement.

| Suspected slice/site | Before → after value / actual difference |
|---|---|
| E7 `1bb9a1e71`, `bootstrap/beyond-closure-budget`, :389 | Both retain `:seon.config/error-key`, `:seon.config/required-absent`, `:seon.config/rule`, expected key/message and nil offending budget. Live equality and identical timestamp pass. The red takes the integer branch earlier. |
| E0b `2efb85875`, four blob literals | `binary-threshold`, `verify-stored!`, `stage-binary!`, `get` retain all mismatch/input-stalled members and outer ex-info. None changes `stored-binary`'s rejecting contract or the fixture transaction. |
| E8 `4a5cea540`, five preparation literals | `incoherent`, `unavailable`, two `supply` cases and ambiguous `prepare` retain their supplied-default keys, messages, offending values and nested data. Supplier Throwable remains offending evidence, not a newly consumed throwable input. No contract, host-probe arming or context acquisition change. |
| E9 `8acaaefeb`, `submit-source!` :653/:686 | Missing-namespace and undelivered-wake maps retain refused-agent/member/expected/offending/data. The four agent reds concern cap queries, step cleanup, install settlement and request timing, not either submit-source refusal. |
| E5 `6314ef453`, `await/diagnostic` :45 | Same final diagnostic map in the same outer merge position; observation, requested-member and outcome merge order unchanged. No event policy change. |
| E0a `bab838388`, `error/prepare` :550 | Empty normalization seed now comes from positional constructor; class/frame/chain are still conditionally associated, then source error merged last. Before/after preserves `phase-failed` if handed in. No changed occurrence-selector or settlement rule. |
| E0a deleted `error/refusal-prose` | Deleted mechanism's direct test removed in the same slice; no recorded red names it. The seven red test files were not edited. |

**Slice-caused fix list: empty on available evidence.** Do not revert the positional arity, add a member-copying shim, or rewrite unrelated errors to make this batch green. M and N need the missing proof before any E-slice is exonerated globally on durable behavior or performance.

## Read-only probe receipt

Complete MCP envelopes were inspected: each had a successful `ret`, `windowed? false`, selected default alive. These forms ran in the throwaway namespace named above. Timings below are internal elapsed milliseconds; prepl totals were 2–4 ms. The acquisition/read probe returned no current acquisition refusals; that does not prove test-time arming.

```clojure
;; P1: literal versus converted shape. 0.033833 ms; prepl 2 ms.
(let [start (System/nanoTime)
      at (java.util.Date. 0)
      members {:seon.error/offending nil
               :seon.config/error-key :seon.config.bootstrap/beyond-closure-token-budget
               :seon.config/required-absent :seon.config.bootstrap/beyond-closure-token-budget}
      before (merge {:seon.error/at at :seon.error/layer :seon.bootstrap/opening
                     :seon.error/operation 'seon.bootstrap/beyond-closure-budget} members)
      after (seon.error.refusal/diagnostic at :seon.bootstrap/opening
                                          'seon.bootstrap/beyond-closure-budget members)]
  {:before before :after after :equal (= before after)
   :same-at (identical? at (:seon.error/at after))
   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})
;; equal true; same-at true; both complete six-member maps are identical.

;; P2: inherited inputs. 2.216542 ms; prepl 4 ms.
(let [start (System/nanoTime) database @(seon.cluster.boot/connection "default")]
  {:clusters (seon.db/q '[:find ?name ?budget :where
                         [?c :seon.cluster/name ?name] [?c :seon.cluster/config ?config]
                         [?config :seon.config.bootstrap/beyond-closure-token-budget ?budget]] database)
   :identity (seon.db/pull database [:seon.fn/sym] [:seon.fn/sym 'clojure.core/identity])
   :root-messaged (#'seon.bootstrap/root-messaged-agent? database)
   :budget (#'seon.bootstrap/beyond-closure-budget database "root")
   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})
;; {:clusters #{["default" 1024]}, :identity nil, :root-messaged true, :budget 1024, ...}

;; P3: actual backend value versus named schemas. 0.260208 ms; prepl 2 ms.
(let [start (System/nanoTime) database @(seon.cluster.boot/connection "default")
      projection (seon.schema/projection-from-database database)
      spec (:malli/schema (meta #'seon.blob/stored-binary))
      value {:input-stream (byte-array [1 2])}]
  (seon.schema/call-with-projection projection
    (fn [] {:spec spec
            :valid-stream ((seon.schema/projection-validator projection :seon.blob/input-stream)
                           (:input-stream value))
            :valid-bytes ((seon.schema/projection-validator projection :seon.blob/octet-array)
                          (:input-stream value))
            :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})))
;; valid-stream false; valid-bytes true; spec is the union quoted in D.

;; P4: phase's exact injected exception. 0.147583 ms; prepl 2 ms.
(let [start (System/nanoTime)
      result (#'seon.turn/phase
               (fn [] (throw (ex-info "install gate broke mid-opening"
                                     {:seon.test/install-gate-broke true}))))]
  {:value result :metadata (meta result)
   :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})
;; value: {:seon.error/at #inst "2026-09-23T17:50:02Z",
;; :seon.error/layer :seon.turn/phase, :seon.error/operation seon.turn/phase,
;; :seon.error/message "install gate broke mid-opening", :seon.test/install-gate-broke true,
;; :seon.turn.loop/failed-step seon.turn/phase, :seon.turn.loop/phase-failed true}
;; metadata: #:seon.error{:declared-schema :seon.turn.loop/phase-failed-error}
```

Additional exact read forms (same timing wrapper as P2, with results below):

```clojure
(mapv (fn [sym] [sym (seon.db/pull database
                      [:seon.fn/sym :seon.fn/host-bound? :seon.program/definition-digest :seon.fn/source]
                      [:seon.fn/sym sym])])
      ['seon.call-preparation-test/probe-received-connection?
       'seon.call-preparation-test/probe-received-database? 'my.plan/plan])
;; 0.398709 ms: all present, host-bound? false; digests respectively
;; 58195c09689055bf8d148d9cd380dfe8505e9578c1bc7d669205849a1f147ba9
;; 7b70062c808f8210534fd65b2f76da2bacaacf24ad560ba470c75e374df4d639
;; f9345cc099171ed72183b2da93f1beea9378822a9c986bae43e256437a65c462

(mapv (fn [sym] (let [v (find-var sym)] [sym (some-> v deref class str)]))
      ['seon.error.refusal/diagnostic 'seon.call-preparation-test/probe-received-connection?
       'seon.call-preparation-test/probe-received-database?])
;; 0.042292 ms: class clojure.lang.AFunction$1; then the two raw probe classes in H.

(seon.db/pull database
 [:seon.test.run/id :seon.test.run/git-sha :seon.test.run/namespaces :seon.test.run/include-long?]
 [:seon.test.run/id "39476d5bd9dc"])
;; 0.498792 ms combined with acquired-program read: include-long? false;
;; 17 namespaces, neither my.background-test nor my.program-test; git-sha absent.
;; (seon.sci.eval/acquired-program (:seon.sci.eval/ctx h)) had acquisition-refusals [].
;; h = (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
```

All lane-initiated probes/commands before documentation validation were sub-second. Prior slow operations are **defects to resolve, not justified by “cold” or “expected”**: request `seon.test/run` 120,518 ms; `member-result` max 20,652 ms; `bounded-result` max 20,545 ms; `runner/run-var!` max 20,536 ms. Inclusive profile totals overlap and must not be added. The L test's 20-second await is proportional to an event that its own owner cancels; the request is proportional to selected bodies/trials and acquisition/recording. Nothing here justifies those costs as inherently necessary. The existing [turn-cost issue](../../seon/issues/a-turn-spends-seconds-before-its-provider-call.md) owns the pre-provider cost class; M/N need focused evidence, not another broad run. This report records the supplied over-ten-second defect evidence without authorizing a rerun of that size.

## Missing proof and request split

The log's **132 pending identities** group as follows. Counts are parsed from the actual `pending (not started)` line, not estimated from namespace source.

| Namespace | Pending | Meaning |
|---|---:|---|
| `seon.cluster.agent-test` | 10 | Partially executed namespace; property unfinished separately. |
| `seon.cluster.prompt-test` | 14 | No member started. |
| `seon.cluster.source-test` | 5 | No member started. |
| `seon.contracts-compile-test` | 2 | No member started. |
| `seon.error-test` | 47 | No member started. |
| `seon.error.refusal-test` | 8 | No member started. |
| `seon.refusal-grammar-test` | 3 | No member started. |
| `seon.schema-test` | 43 | No member started. |

Ten pending agent members: `park-wake-test`, `pause-during-in-flight-call-test`, `prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound`, `restamp-recovery-test`, `routing-conservation-waits-for-terminal-evidence`, `routing-proof-rejects-an-empty-production-subject`, `system-source-submission-uses-the-ordinary-durable-run`, `turn-start-has-the-same-declared-loud-completion-backstop`, `wait-closes-in-terminal-tx-test`, `wake-routing-conservation-property`.

Separate omissions: E1's `my.background-test` and E2's `my.program-test` **were not requested at all** (confirmed from the persisted namespace set); they are not deadline casualties. `seon.cluster.boot-test` has ten **long exclusions**, and agent-test has two: `disarm-interrupts-an-in-flight-turn-and-records-it-interrupted`, `disarm-has-a-declared-loud-turn-completion-backstop`. These exclusions are not green and not in the pending count. The record does not demonstrate a completed boot-namespace proof merely because it was selected. `seon.blob-error-test`, await-test and the earlier namespaces are not in the pending set; absence from the red list alone does not establish complete required coverage.

Recommended split, using the existing runner rather than another scheduler:

1. **Orchestrator first observes the unfinished member's actual exit and contract restoration.** The named branch and replaced Var make this concrete. Do not overlap a new proof with that body's retained global mutation or retire its live branch. Recovery remains the orchestrator's operation.
2. **Prioritize the unstarted constructor/contract proofs.** Run separate named requests in this order: `seon.error.refusal-test`, `seon.contracts-compile-test`, `seon.refusal-grammar-test`, `seon.blob-error-test`, then the error-test and schema-test identities. Do not put them behind agent concurrency tests alphabetically again. Add the entirely omitted `my.background-test` and `my.program-test` as independent requests.
3. **Use one identity per request for the unmeasured sets**, especially the 47 error, 43 schema, 14 prompt, 5 source and 10 pending agent members. This avoids cumulative starvation even if a single member is slow. Ordinary command shape:

   ```sh
   bin/test-check --root /Users/sean/src/seon PROOF_CLUSTER \
     --test seon.error.refusal-test/constructor-preserves-open-domain-maps \
     --time-limit-ms 10000
   ```

   `PROOF_CLUSTER` is the orchestrator's existing suitable cluster (or default after recovery); this command is a template, not a claim that such a named cluster was created. `bin/test-check` addresses a cluster handle; it does not take an arbitrary branch keyword. For an owned branch execution handle use `seon.test/run` with that handle, its recording connection, `:named`, one identity and the same request bound.
4. **Keep body and request bounds separate.** Ordinary bodies still have the declared 5,000 ms bound (`seon.test.edn:27`); the 10,000 ms request budget accommodates admission/release, not a larger permitted body. No split can promise an already 20-second body will meet 5 seconds. If a single identity overruns, record its profile/terminal state and fix its owner before retrying; do not repeatedly increase the request limit. Requests over ten seconds or declared-long/platform cases require the owner's existing authorization or a new explicit authorization.
5. **Run M and N separately**, never in the constructor batch. M needs the complete diagnostic/occurrence/shown value on parent and cut. N needs one property request with a full request budget from admission, positive exit and restored Vars, plus parent/current cost evidence. Keep the same actual tested program/input identity; a timeout or unknown may never reuse green.
6. **Platform work stays with the orchestrator.** Derive membership from installed `seon.test/isolated-members`; file-backed blob/source observations and destructive boot tests must run on the platform root with preserved shared caches. Use the existing platform authority and split its selected identities/declared-long cases there; lanes do not run `bin/test` gates. The old record contains a memory-backend fixture observation executed on default; do not infer that this authorizes repeating store-global tests on default. Recheck installed host exclusions.
7. **Close the proof from receipts.** Union the per-request executed/reused/pending/excluded sets against the complete proving roster in the landing, including my.* omissions and long/platform cases. Record body/request timing and proof identity for every batch; every selected identity must be executed, validly reused, or explicitly outstanding. The E12 publication clock and identical parent/child hot-path probe remain separate landing obligations.

This split guarantees that one request's deadline cannot consume the admission budget of a later request. It does not claim the underlying bodies are already within bounds. Complexity is proportional to requested identities and their actual execution; reuse remains the existing content-keyed runner's responsibility. The smallest alternative—one namespace per request—is sufficient only after its total measured bound fits; the original alphabetical multi-namespace 120-second request does not.

## Report validation

All 27 red names were checked against the raw log; none is missing from the table. Relative document links resolve. The scoped citation audit initially required the new file in the index, then found one shortened Malli source path; that path was corrected. No runtime proof is inferred from this documentation audit.

Final scoped audit: `bb script/seon/dev/citations.clj docs/research/agent-platform/error-constructor-reds-2026-09-23.md` — **1 document, 0 failures, 81 ms**. Path-limited staged whitespace check passed.

## E13–E30 continuation: second proof

The preceding sections are the first proof's historical record. This continuation covers every red in `tmp/orchestrator/proof-errcons2.txt` and its `tmp/orchestrator/p-<namespace>.txt` outputs. **There is still no demonstrated slice-caused regression.** Of the **43 new red identities**, 32 have a located pre-existing failing boundary or fixture/owner disagreement; **11 are duration-only and cannot honestly be assigned to either the slices or the parent**. Six of the 32 also have duration failures whose performance attribution remains open. A located first failure does not prove that every downstream assertion would pass after repairing it.

No source/test edits or test reruns were made for this continuation. Read-only MCP probes used the same explicit root, default cluster, throwaway namespace and private session described above. Default was now PID **48902**, start **2026-09-23T18:07:01.344Z**, running archive `data/source/ce73846828a5cc32798ef630b5a574f777646f30`, with hook publication off, no reported replaced roots, and all process pings replying. This is the new proof's runtime boundary, not a claim that the first run's timed-out member was repaired by this lane. Foreign source, test and dependency edits remained untouched. Checkout HEAD at inspection was `2ef84e0d4`; history comparisons below use the committed cut, not those edits.

| Namespace | Run | Executed / reused | Pass / fail / error | Request ms |
|---|---|---:|---:|---:|
| `seon.error.refusal-test` | `aa8dbcda428a` | 8 / 1 | 57 / 0 / 0 | 4468 |
| `seon.contracts-compile-test` | `5b3f80b25a12` | 2 / 0 | 5 / 1 / 0 | 2838 |
| `seon.refusal-grammar-test` | `4786a4abd413` | 3 / 0 | 24 / 1 / 0 | 1712 |
| `seon.blob-error-test` | `7bdf1deaca6f` | 2 / 0 | 5 / 0 / 0 | 1602 |
| `seon.error-test` | `305f375cfdc7` | 47 / 0 | 305 / 15 / 13 | 32358 |
| `seon.schema-test` | `b17cca9910a1` | 43 / 0 | 12667 / 16 / 2 | 57063 |
| `my.background-test` | `c190eefee748` | 3 / 0 | 23 / 0 / 0 | 4478 |
| `my.program-test` | `147ee9a5eaa5` | 5 / 0 | 74 / 2 / 0 | 21002 |
| `seon.cluster.prompt-test` | `18b3e187c7ec` | 14 / 0 | 95 / 16 / 1 | 97609 |
| `seon.cluster.source-test` | `c92bbaf4e2b8` | 5 / 0 | 5 / 2 / 3 | 29921 |

These are orchestrator measurements, not timings introduced or justified by this diagnosis. Every request exceeds one second: the test/fixture owners must explain the measured work and remove redundant derivation. The five requests above ten seconds need that cost investigation before repetition. The schema shell envelope was about 64 seconds; its receipt says 57,063 ms. The schema output is **105,631,661 bytes**, largely whole projection values in a failure; diagnostic serialization is itself work to remove from this comparison. No aggregate profile establishes the cause of one member's duration. Runtime accumulated profile maxima included `seon.test/run` 97,608 ms, `member-result` 14,790 ms, `bounded-result` and `seon.schema/call-with-projection` 14,690 ms, and `seon.test.runner/run-var!` 11,858 ms; these nested totals must not be summed or treated as exclusive costs.

### Slice comparison

Reviewed continuation commits: E13 status `8156322b6`; E14 wake `39218a345`; E15 config `e36e71795`; E16 context `c07e89393`; E17 db `7ef868d59`; E18 edit `12ee273af`; E19 effect `8eb40dfda`; E20 env `517b6e43f`; E21 flow `0c63cd3f3`; E22 issue/opening `8c1581b00`; E23 maintenance `0ef0f9a60`; E24 plan `d5a489534`; E25 render `dd24e4e81`; E26 render/data `c511ae0ff`; E27 render/hiccup `2070c7c75`; E28 render/transcript `7802dfce7`; E29 render/value `a0fb1d5b8`; E30 render/walk `eece20496`. The landing counts 76 converted sites, source −149/test +9. Its static checks do not establish runtime equivalence.

The relevant before/after comparisons are:

- **E11 `d942eeb27`, `src/seon/cluster/prompt.clj:352`:** before a map with `at (Date.)`, layer `:seon.cluster.prompt/capture`, operation `seon.cluster.prompt/capture-mismatch`, expected-value `capture`, refused-value `text`, message and turn-id; after `diagnostic (Date.) layer operation` with precisely those other members. `capture` remains a **string**, while `:seon.schema/expected-value` aliases a **schema key**. This is a preserved invalid value, not a member renamed/dropped or a newly narrowed contract. Parent blame puts both expected/refused-value assignments at `f8861b66e3`, and the mismatch condition at `846d75e9c3`.
- **E17 `7ef868d59`, db:** four converted sites are invalid-read-error (around line 193), projection fallback (1243), temporal database-view refusal (2733), and unique-conflict catch (2987). The first three retain the same error and nested evidence members through the positional constructor. The catch converts an existing map-arity diagnostic call, retaining its Throwable for chain derivation. Neither symbol validation, the armed `carried-projection` input check, nor foreign-connection custody is one of these conversions. The observed failures occur at those earlier owners.
- **E0a `bab838388`, error recorder:** construction of the refusal explaining an invalid declared error changed; the declaration check and recorder's schema-selected occurrence members did not. A contract-error relabeled validation-refusal was invalid on the parent as well. The recorder does not persist every incidental open-map member merely because the constructor preserves it.
- **E12 `d49b574be`, source:** the missing-schema transaction into the scratch connection is unchanged. It refuses before the converted error paths under test. **E2 `1f6295e60`, my/program:** the two new reds assert only elapsed duration, not a changed constructor value. **E16 context/E19 effect:** the offending observation alias/predicate declarations predate these literal conversions. **E28 transcript:** the missing transcript in error-identity follows a refused agent fixture, not a changed transcript refusal value.
- Across the cut, the changed portions of error-test add constructor assertions/remove retired refusal-prose coverage; schema-test changes renderer fixture references. None is a failing body listed below. This negative diff evidence supplements, rather than replaces, the positive failing-value evidence.

### Every new red

Class identifiers below link each identity to the exact evidence/disposition in the following section. **F/O/D** retain the three-question meanings above. **U** means attribution unavailable; it is not a pre-existing verdict or a pass. Namespace prefixes are explicit at each group.

| Red | Slice-caused or pre-existing class | Disposition |
|---|---|---|
| `seon.contracts-compile-test/each-historical-from-zero-break-is-refused-by-name` | Pre-existing C1 | F: earlier armed rejection invalidates expected prose. |
| `seon.refusal-grammar-test/structured-refusals-share-one-actionable-rendering` | Pre-existing C2 | F: string identity refuses before missing-core assertion. |
| `seon.error-test/a-contract-violations-fault-keeps-the-value-that-broke-it` | Pre-existing C3 | F/O: instrumentation lifecycle leaks globally. |
| `seon.error-test/a-fault-observes-the-function-name-without-minting-an-identity` | Pre-existing C4 | F: exact-one-cluster helper. |
| `seon.error-test/a-prepared-message-keeps-its-id-when-the-transaction-repeats` | Pre-existing C4 | F: same fixture. |
| `seon.error-test/an-agent-this-cluster-does-not-have-is-no-attribution-at-all` | Pre-existing C4 | F: same fixture. |
| `seon.error-test/an-unattributable-throwable-goes-to-the-escalation-owner` | Pre-existing C4 | F: same fixture. |
| `seon.error-test/complete-error-children-validate-through-the-writer` | Pre-existing C6 | F: synthetic entity declaration lacks partition. |
| `seon.error-test/error-declared-schemas-persist-through-the-real-occurrence-owner` | Pre-existing C7 | F: incidental agent-id is not a declared turn-error member. |
| `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer` | Pre-existing C4 + C5 | F: refused fixture continues, then inherited rows pollute global counts. |
| `seon.error-test/only-a-throwable-tells-the-attributed-agent` | Pre-existing C4 | F: exact-one-cluster helper. |
| `seon.error-test/recording-refuses-an-unavailable-complete-observation` | Pre-existing C5 | F: branch is not an empty error store. |
| `seon.error-test/recurrence-counting-does-not-require-a-notification-threshold` | Pre-existing C4 | F: exact-one-cluster helper. |
| `seon.error-test/recurrence-identity-uses-declared-schema-and-distinguishing-evidence` | Pre-existing C5 | F: count four owned errors, not all inherited errors. |
| `seon.error-test/row-acquisition-observations-have-no-program-digest-promise` | Pre-existing C7 | F: wrong declared family passed to recorder. |
| `seon.error-test/schema-refusals-are-admitted-at-the-recorder` | Pre-existing C8 | F: instrument contract error mislabeled schema refusal. |
| `seon.error-test/the-message-points-at-the-fact-it-explains` | Pre-existing C4 | F: exact-one-cluster helper. |
| `seon.error-test/the-storm-is-bounded-by-the-signature-count` | Pre-existing C4 | F: same fixture. |
| `seon.schema-test/a-component-bearing-row-validates-its-own-declared-shape` | Pre-existing C9; duration U | F: namespace lacks definition evidence; 10,298.389416 ms. |
| `seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms` | Pre-existing C8 | F: earlier contract has argument-vector evidence. |
| `seon.schema-test/an-incremental-projection-equals-its-full-build` | Pre-existing C10; duration U | F/O: closure equality and non-string testing contexts; 9496.362833 ms. |
| `seon.schema-test/error-declared-schemas-and-their-owned-members-are-storable` | Pre-existing C11 | O: six aliases inherit identity uniqueness. |
| `seon.schema-test/every-predicate-schema-declares-what-it-accepts` | Pre-existing C12 | O: two predicate declarations omit explanatory messages. |
| `seon.schema-test/one-derivation-owns-the-projection-predicate-bindings` | Pre-existing C13 | D/O: source census conflates enumeration with a derivation. |
| `seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector` | Pre-existing C14 | F: assert schema/refused-value. |
| `seon.schema-test/pure-projection-composition-materializes-compiled-entity-indexes` | Pre-existing C6 | F: synthetic entity declaration lacks partition. |
| `seon.schema-test/render-declarations-require-a-contract-that-accepts-their-shape` | Pre-existing C14 | F: assert the owner's declared schema evidence keys. |
| `my.program-test/past-reach-is-advisory-and-source-history-survives-retraction` | U: duration only | 5083.796583 ms; behavioral assertions pass. |
| `my.program-test/repl-documentation-and-supplied-database-use-the-canonical-population` | U: duration only | 5108.620292 ms; behavioral assertions pass. |
| `seon.cluster.prompt-test/a-budget-larger-than-the-history-composes-the-same-bytes` | U: duration only | 7933.858875 ms. |
| `seon.cluster.prompt-test/a-held-turn-can-render-without-a-message-trigger` | U: duration only | 7989.640167 ms. |
| `seon.cluster.prompt-test/a-replay-under-a-selecting-budget-reconstructs-its-own-capture` | Pre-existing C15; duration U | O: malformed mismatch result; 7704.125625 ms. |
| `seon.cluster.prompt-test/basis-only-transactions-do-not-append-history` | U: duration only | 8200.988958 ms. |
| `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior` | U: duration only | 10,351.107625 ms. |
| `seon.cluster.prompt-test/identical-context-does-not-depend-on-a-retained-prompt-cache` | U: duration only | 8443.354417 ms. |
| `seon.cluster.prompt-test/later-evaluations-preserve-the-opening-history` | U: duration only | 8615.215333 ms. |
| `seon.cluster.prompt-test/prompt-prices-the-exact-retained-history` | U: duration only | 7857.627 ms. |
| `seon.cluster.prompt-test/provider-calibration-reports-over-budget-without-refusing` | Pre-existing C16; duration U | F: shared-model usage is inherited; 7962.055958 ms. |
| `seon.cluster.prompt-test/the-prompt-budget-selects-and-still-reports-its-verdict` | U: duration only | 7377.921208 ms. |
| `seon.cluster.prompt-test/unobserved-messages-do-not-rewrite-stored-history` | U: duration only | 8352.52125 ms. |
| `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate` | Pre-existing C17; duration U | O: scratch write violates inherited custody; 11,821.60925 ms. |
| `seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster` | Pre-existing C17; duration U | O: same early refusal; 10,551.391666 ms. |
| `seon.cluster.source-test/source-tombstone-provenance-does-not-prevent-live-removal` | Pre-existing C9 | F: namespace fixture lacks definition digest. |

### Evidence classes and the three questions

Each class states the failing value, pre-cut history and smallest disposition. Unless marked D, these tests do **not** solely test deleted machinery; their wanted behavior should survive. F identifies the retired assumption to repair; O identifies the surviving owner. For U, preserve the wanted behavior and investigate cost; neither deletion nor a relaxed bound follows from the evidence.

**C1 — historical contract checker, not constructor compilation.** The only failing assertion is `test/seon/contracts_compile_test.clj:343`, expecting `has no admitted callable` for two anonymous-predicate historical source strings. Both actually fail earlier: `seon.schema/canonical-definition` refuses its output as not a parseable EDN-readable Malli form. The checker at lines 130–140 catches that and reports `the stored contract does not read back as EDN`; it never reaches the unresolved-predicate check. Those checker/expectation lines blame to `aed7bf9550`. The historical `src/seon/db.clj` filename is fixture text, not today's E17 db implementation. Read-only `check` against the running projection and the private `historical-sources` returned all three historical refusals with the EDN label; the ordinary current-population compile test passed. **F:** assert the appropriate earlier refusal and retain cause evidence; **O** only if the checker must distinguish contract refusal from actual reader failure, improve its existing catch label. Do not change the constructor to produce the old message.

**C2 — grammar fixture invalid before its intended missing member.** `test/seon/refusal_grammar_test.clj:66` supplies `:seon.fn/sym "seon.refusal-grammar-test/incomplete"`; the writer correctly requires a qualified **symbol** at `[0 :seon.fn/sym]`. Line 71 expects `:core` in the message. History for lines 64–74 is `a659850987`, `9bf22ecd9b`, `2df9ecccc2`, `bf24eb0a24`, `35531f67ee`, `f000669b0f`, all preceding E0a. The error retains the supplied request id; no constructor evidence was dropped. **F:** use a valid canonical identity/definition fixture and omit only the member whose diagnostic is tested. Merely changing the string to a symbol may expose a second missing digest; do not pin the test to whichever unrelated invalid field happens to refuse first.

**C3 — instrumentation ownership.** The contract-fault body's assertions pass; runner reports 30 added armed functions, including the contract compilation checker. `test/seon/error_test.clj:455` calls `instrument/apply!` and line 473 calls `instrument/remove!` without the canonical preservation scope; blame `e087497123`. **F/O:** use the existing `preserving-instrumentation-state` helper around the test's lifecycle and preserve wrappers deliberately. The constructor neither adds those Vars nor changes this fixture's cleanup. No default Var was altered by this diagnosis.

**C4 — the one-cluster fixture assumption.** `test/seon/error_test.clj:720–728` seeds `error-test` on an inherited branch and calls `agents-tx`. `test/seon/test_support.clj:713–719` (`090601ae1a`) requires exactly one cluster; the branch fixture (`678009fcd0`) already contains default. Error helper history includes `dd97574183`, `3deb2b89cb`, `ae0e54841b`, `d7b3930ff4`, `b2fefe9d5a`, `9c9b50e3c3`, `a86e93e21d`. Eight tests terminate at this setup; error-identity catches the fixture failure inside `is` and continues into missing steward/message/transcript assertions, including a nil query input at line 147. **F:** explicitly target the test cluster through the existing canonical helper and surface setup refusal immediately. Keep attribution, recurrence, notification and idempotence behaviors; do not repair their downstream owners based on this broken setup.

**C5 — inherited error population.** The proof has 16 inherited errors: recurrence-identity at line 1225 expects 4, gets **20**; unavailable-observation at line 1246 expects an empty database but sees the inherited facts. Error-identity expects 1 error, sees **17**, and expects summary count 6, sees **61**, after its separate C4 setup failure. Its own recurrence/reference assertions distinguish newly written values from the inherited population. These assertion bodies are unchanged by the E cut; the branch-fixture history is C4, and the error fixture/recurrence helpers predate it. **F:** query the test's identities or compare before/after counts for its scope, keeping the requirement that a refused observation writes nothing. Empty-store assumptions are retired, not the recorder behavior.

**C6 — missing program partition in synthetic declarations.** The manifest-root schema at `test/seon/error_test.clj:939–954` (`acee1e21fd`) and optional-entity at `test/seon/schema_test.clj:1144–1152` (`22a1a05677`) declare attribute entities with identity members but omit `:seon.program/partition`. Admission at `src/seon/schema.clj:1490–1507` (`8a069b5e4d`) rejects them before their intended writer/projection assertion. **F:** supply the required partition in the fixture declaration. No error constructor changed schema admission.

**C7 — declared error family controls persistence.** In row-acquisition, the observation at `test/seon/error_test.clj:51–77` is complete, but line 66 invokes `commit-request observation {}`. The helper at lines 730–744 defaults to **`:seon.error/normalization-error`**, then the test expects the stored occurrence to retain row-acquisition members. That family declares base plus data-edn, not row-member/acquisition-observation. In the schema-persistence test, line 1159 expects `:seon.agent/error-agent-id` while declaring **`:seon.turn/error`**, whose domain member is turn-error-id. Open maps accept incidental members; they do not make those members stored attributes of every family. `src/seon/error.clj:1406–1412` selects declared family/base members; history `a86e93e21d`, `2066b8c20a`. **F:** pass the actual `:seon.sci.eval/row-acquisition-error` family in the first case; assert the declared turn family in the second. Do not widen the recorder to persist arbitrary map copies. Live declared-member inspection confirmed all three families.

**C8 — armed boundary precedes the body.** `test/seon/error_test.clj:1052–1058` (`ac39440480`, `a86e93e21d`) calls projection derivation on `{:seon.error-test/unavailable true}`, catches **`:seon.instrument/contract-error`** from `seon.db/carried-projection`, then asks the recorder to declare it **`:seon.schema/validation-refusal`**. The recorder properly refuses the false declaration. The schema-test analogue at line 1578 expects nested `:seon.error/data` member `:seon.schema/database-value` and a scalar offending map; actual evidence is the input argument **vector**. The projection entry at `src/seon/schema.clj:3359–3370` predates the cut (`10dfe0ff40`, `9b8c5b4053`). Live validators give false for validation-refusal, true for instrument/contract-error on that raw value. **F:** assert/record the actual armed refusal, or create a legitimate schema refusal when that family is the subject. Do not disarm contracts or falsify the declared family.

**C9 — definition evidence required.** The namespace fixture at `test/seon/schema_test.clj:1238–1243` (`e7bafc9577`) supplies name/admission-source/aliases but not the required definition evidence; it does not match `:seon.ns/ns` and is classified as unowned-namespace. The requirement's schema history is `f27b96c190`. `test/seon/cluster/source_test.clj:252` (`e89117099c`) writes only the `source-deletion-probe` namespace name and receives an explicit missing `:seon.program/definition-digest` refusal. Its function helper does not make the separate namespace row valid. **F:** use the canonical namespace definition producer; keep component and tombstone behaviors. The schema case's duration remains independently unattributed.

**C10 — two defects in incremental equivalence evidence.** The runner's output contract rejects a context tuple whose second member is the vector `[:removed-declaration leaf]`, while `resources/seon/schemas/seon.test.failure.edn:7` requires a string. The test passes vector labels to `testing` around line 1719; runner at `src/seon/test/runner.clj:194–195` copies context values unchanged (both reporter/schema history `8199364a28`). The hidden result contains five equality failures plus a 9496.362833 ms duration failure. Bounded extraction of the **first** 8,270,929-character equality expression found two 21-key maps differing only in `:seon.schema-test/compiled-forms`. Its differing schema forms contain separately allocated `:gen/gen` Generator closures (for example context-state's otherwise identical generator has object address `0x2a3d8990` versus `0x78f739d7`). `comparable-projection` at `test/seon/schema_test.clj:1675–1692` (`1625fb9bcd`) retains these runtime objects via `m/form`; `src/seon/schema.clj:296–302` resolves generator symbols to generator objects. This is positive evidence of an invalid value-equality oracle, not evidence that all incremental schema semantics agree. **F:** compare canonical declared generator representations instead of object identity, and use string test contexts. **O:** keep runner reporting total for valid clojure.test contexts through its existing formatter. Preserve incremental/full semantic equivalence coverage; separately inspect remaining differences after normalization. Do not classify the duration from this source evidence.

**C11 — observation aliases inherit uniqueness.** Live `malli->datahike-attr-in` returns `:db.unique/identity` for all six failing observation keys:

| Observation | Aliased identity | Declaration blame |
|---|---|---|
| `:seon.test.runner/long-test-ns-hook` | `:seon.ns/name` | `da1855b3f6` |
| `:seon.test.runner/default-cluster-refused` | `:seon.cluster/name` | `b5e3a5e40e` |
| `:seon.message/unknown-recipient` | `:seon.agent/id` | `ae0e54841b` |
| `:seon.context/selection-agent-id` | `:seon.agent/id` | `9ac1586408` |
| `:seon.turn/compaction-agent-id` | `:seon.agent/id` | `f8861b66e3` |
| `:seon.sci.eval/schema-refused` | `:seon.schema/key` | `ae0e54841b` |

The bridge's compiled storage fold merges referenced properties and local properties, and the storage mapping emits native uniqueness. This is the already-open [observation-alias uniqueness issue](../../seon/issues/error-observation-aliases-inherit-native-uniqueness.md), not constructor member loss. **O:** the observation declarations need value semantics, coordinated with the schema/storage owner; retain the regression. Do not strip uniqueness from genuine domain identities or weaken the bridge's native parity globally.

**C12 — incomplete predicate explanations.** Live declaration traversal locates `[:fn seon.cluster/socket-server?]` in `:seon.operator/request`, and `[:fn {:gen/schema [:=> [:cat :seon.schema/value] :boolean]} clojure.core/ifn?]` in `:seon.effect/result-validator`. Both lack `:error/message`; declarations blame `f49187619a` (`resources/seon/schemas/seon.operator.edn:49`, `resources/seon/schemas/seon.effect.edn:51–53`). **O:** add the intended descriptions to those two existing predicate declarations. E19 converted effect error literals, not this schema. This wanted explanatory-contract test stays.

**C13 — source census is broader than ownership.** The assertion at `test/seon/schema_test.clj:1375–1386` (history `3764c7965e`, `28f1a761ed`, `0e8f7d3236`) sees `:seon.schema.projection/predicate-bindings` in two additional forms: `projection-runtime-keys` (`src/seon/schema.clj:2360–2368`, `1625fb9bcd`) and `seon.fn.schema-shape/form-projection` (lines 157–169, `c6db6b3586`). The former merely enumerates runtime keys; finding its literal is not a second derivation. **D:** remove the obsolete implication that every occurrence is a derivation, preserving behavior/ownership coverage. **O:** review the actual form-projection derivation at its owner if it duplicates authority. The current census proves the extra occurrences, not a runtime drift or an E-constructor failure.

**C14 — assertions name an older evidence shape.** `src/seon/schema.clj:3397–3411` (`ac39440480`, `9de4b0ebf9`, `9bf22ecd9b`, `a86e93e21d`) already returns selector refusal evidence under `:seon.schema/refused-value`, while pulled-forms expects `:seon.error/offending`. `render-contract-refusal!` at lines 1972–1988 (`bc1732d26c`, `9bf22ecd9b`, `ac39440480`, `434c01f4c9`, `a86e93e21d`) uses `:seon.schema/expected-value`, `:seon.schema/refused-value`, and data containing `:seon.schema/key`; the test expects generic error expected/offending/member. Its operation assertion passes. **F:** assert the existing declared schema evidence, not the retired generic shape. These schema owners were not converted by E13–E30.

**C15 — capture mismatch owner's pre-existing malformed result.** The before/after value and parent blame are shown above. **O:** the existing capture-mismatch producer must put a valid schema key in expected-value and retain captured/reconstructed text as appropriately declared value evidence. Do not broaden the whole validation-refusal contract merely to admit prompt text. Why this replay produced differing bytes is a second question not answered by the contract refusal. The [older selected-capture issue](../../seon/issues/a-selected-prompt-no-longer-reconstructs-from-the-full-join.md) describes the former render-side check; current prompt source explicitly says that check moved to the composition authority. Therefore that historical issue alone is **not** proof of today's underlying mismatch cause. Keep the reconstruction regression, obtain both byte strings and their selection inputs after the malformed diagnostic is repaired, and compare at the existing owner.

**C16 — model-global calibration fixture.** `test/seon/cluster/prompt_test.clj:422–446` expects zero usage with prior 17, then exactly three samples with ratio 3.2. The model already has usage: actual samples **10**, later ratio **3.5967764231013795**. `calibration-for` at `src/seon/cluster/prompt.clj:78–105` intentionally queries matching model and optionally agent, taking the recent ten; the test uses the shared configured model without agent scope. Owner history `7d06b50ecf`, `f8861b66e3`, `17dd75e89f`; fixture history includes `985a830b56`, `cc9357948c`, `dc1efaf3c9`, `e89117099c`, `a17305d8d6`, `549ab70b58`, `e7ebe4cfe5`. **F:** select a distinct canonical test model/usage population, or scope by the intended agent when testing agent calibration. Preserve the legitimate model-global query and over-budget behavior.

**C17 — scratch publication writes under another branch's custody.** `src/seon/cluster/source.clj:603–617` opens a building-source connection, then transacts missing schema under the test agent's inherited custody. The two errors name `:building-source-48902-…` versus `:agent-b5d91cbe7958` / `:agent-5ae0ec53924f`; they occur before the deliberately injected flat refusal or intended publication assertions. Source history `3ac00fb8ee`, `fa1ff1dbe3`, `0f5f849bd1`, `3b73038f20`; `src/seon/db.clj:466–492` foreign-connection guard history `0b617fb7be`, `f46f9d461e`, `59e51e221a`. **O:** establish the correctly authorized scratch branch custody at the existing source operation boundary; do not disable db's cross-branch guard. Keep cleanup and old-cluster preservation behaviors. No scratch branch or source publication was created by this diagnosis.

**U — duration attribution requires measurements.** The two my/program bodies (`test/my/program_test.clj:76,126`, `a2e16338b1`) and nine prompt bodies have no failing behavioral assertion in these logs. Prompt's planted fixture at `test/seon/cluster/prompt_test.clj:60–80` creates cluster/agent/opening/evaluation and records a preview before the body; its total is not a measurement of the one converted capture refusal. No valid paired parent/current measurement under equivalent data, arming and reuse state is supplied. Thus neither “pre-existing because the test is unchanged” nor “caused by diagnostic because it is new” is justified. The same caveat covers duration components in C9/C10/C15/C16/C17. Keep each test, preserve the five-second body bound, profile the armed owner and fixture separately, and compare parent/current on the same admitted input. These are outstanding performance defects, not an authorization to rerun a 98-second namespace request.

### Read-only reproductions and limits

All forms below were evaluated without redefining default Vars. Use the named throwaway namespace, explicit root/cluster and read-only JVM mode above. `p` below means a local `let` binding of `(seon.schema/projection-from-database @(seon.cluster.boot/connection "default"))`, never a replacement global Var.

- Historical checker: `(seon.contracts-compile-test/check {:seon.contracts-compile-test/root "/Users/sean/src/seon/data/source/ce73846828a5cc32798ef630b5a574f777646f30" :seon.contracts-compile-test/projection p :seon.contracts-compile-test/sources @#'seon.contracts-compile-test/historical-sources})`, selecting filename/message locally. **4.426625 ms**, prepl 6 ms; three EDN-label findings. The original hidden assertion value was read with `(seon.blob/get "5f9ec6c757c22dab8f47a9a3eed6c75618ff9e14a6f449d04dca32b9365620ab")`, **5.465084 ms**, prepl 7 ms. Its larger envelope was windowed; the bounded re-evaluation establishes the cited finding without depending on an unseen tail.
- Armed bad-database probe: catch `ExceptionInfo` ex-data from `(seon.schema/projection-from-database {:seon.error-test/unavailable true})`, validate it with `(seon.schema/projection-validator p :seon.schema/validation-refusal)` and the corresponding instrument/contract-error validator. **0.981917 ms**, prepl 3 ms. Raw operation `seon.db/carried-projection`, member `:seon.fn.arity/input`, offending `[{:seon.error-test/unavailable true}]`, check `:input`; validators **false / true**.
- Storage/schema probe: map `(seon.schema.datahike/malli->datahike-attr-in p k)` over the six C11 keys and select `:db/unique`; inspect entity entries for normalization-error, turn/error and row-acquisition-error through the registry. **0.307541 ms**, prepl 2 ms. Six identity-unique results and the C7 declared member sets. The combined output was windowed; claims here are restricted to the inspected entries.
- Predicate-owner probe, exact form: `(let [start (System/nanoTime)] {:missing (vec (for [[k form] (seon.schema/registered-schemas) node (tree-seq coll? seq form) :when (and (vector? node) (= :fn (first node)) (not (:error/message (second node))))] [k node])) :elapsed-ms (/ (- (System/nanoTime) start) 1e6)})`. **27.157292 ms**, prepl 30 ms, unwindowed; exactly the two C12 owners. Runtime reported the declaration-population fallback's 152 resource reads. This one census was sub-second; it is not a reason to repeat population reads in ordinary paths.
- The schema failure was decoded locally in bounded output, without loading its values into default. First equality extraction/analysis **423.55 ms**, nested form comparison **510.01 ms**. No equality claim is made for the four other hidden comparisons. No tests, fixtures, branch writes or long probes were repeated.

### Smallest fixes, grouped by owner, and remaining proof

**Slice-caused fix list: empty on the evidence inspected.** In particular do not undo `diagnostic`, rename its keys, change its `at` semantics, or widen its output contract to make these reds disappear. This is not proof of the unexecuted roster or of performance equivalence.

| Existing owner | Pre-existing classes and smallest action |
|---|---|
| Contract-checker and refusal-grammar fixtures | C1/C2: target the real first refusal with a valid subject; preserve full cause and wanted actionable rendering. |
| Error test fixtures / canonical test support | C3–C7: preserve instrumentation; name cluster; scope counts to owned facts; declare partition; pass the real error family and assert its declared stored members. |
| Schema test expectations / schema owner | C8–C10/C13/C14: respect armed error families, produce complete namespace rows, compare canonical schema values, remove obsolete source-census implications, and use declared evidence keys. |
| Runner reporting | C10: ensure testing contexts have the declared string representation; avoid enormous whole-projection failure values. |
| Observation schema owners / storage bridge | C11: coordinate value declarations for six identity aliases under the existing issue; keep native identity behavior intact. |
| Operator and effect schema declarations | C12: supply the two missing predicate explanations at their declarations. |
| Prompt producer / calibration fixture | C15/C16: repair malformed mismatch evidence at its producer; separately determine differing replay bytes; isolate calibration input population. |
| Source publication / namespace fixture | C17/C9: authorize scratch branch custody at its owner; use canonical namespace definition evidence. |
| Test/fixture and hot-path performance owners | U and mixed duration reds: paired cost evidence, existing armed profiles, body-bound enforcement; no speculative slice attribution or bound inflation. |

The second proof closes the initial **not-started namespace** gaps for error.refusal, contracts-compile, refusal-grammar, blob-error, error, schema, prompt and source, and supplies the previously omitted my.background/my.program proofs. It does **not** provide receipts for the ten initially pending cluster.agent identities listed above. Retain that exact pending roster until each has a terminal receipt; do not subtract them merely because later namespaces ran. Three new namespace proofs are green (error.refusal, blob-error, my.background); failures elsewhere remain failures.

The split plan above remains applicable, now with measured costs: short constructor/grammar namespaces can remain separate namespace requests; error/schema/prompt/source/my.program must be split by `--test NS/TEST` for diagnosis so one identity cannot consume another's request budget. A request split fixes starvation, **not** a body already exceeding 5000 ms. Run the eleven duration-only members and six mixed-duration members individually only after the orchestrator establishes termination and the appropriate proof cluster/platform classification. Obtain exclusive armed profile evidence and a matched parent/current comparison before choosing a performance owner. Do not overlap tests that mutate instrumentation. For each omitted agent identity, admit one bounded request and record executed/reused/terminal state explicitly. Preserve shared caches for any authorized archive proof; no worktree, second runner, cold gate or default replacement belongs to this lane.

The new per-namespace requests all completed their listed selections, so the original 120-second aggregate deadline no longer explains these new reds. Coverage closure is the union of both proof rosters and outstanding identities, not the sum of pass counts. The orchestrator still owns the cold/platform proof and any recovery. This report's net source/test change remains **0/0**.

Continuation validation: all **43** raw red identities occur exactly once in the continuation table; no relative document link is missing. Scoped citation audit: **1 document, 0 failures, 79 ms**. Path-limited staged whitespace check passed. These documentation checks do not constitute a runtime pass.

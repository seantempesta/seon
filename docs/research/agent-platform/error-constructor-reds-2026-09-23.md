---
type: research
status: diagnosis-complete-with-explicit-proof-limits
created: 2026-09-23
scope: error-constructor E0a–E12; run 39476d5bd9dc
---

# Error constructor reds: attribution and remaining proof

The system already constructs flat errors and tests branch-local program values. Compare each failing boundary with its pre-cut owner before changing anything; preserve the existing constructor and repair the smallest fixture or owner that actually differs.

**No demonstrated E-slice regression was found.** Twenty-five of the 27 red test identities have a located pre-existing cause or pre-existing fixture/owner disagreement. The install-gate red is localized to pre-existing error observation/settlement, but its complete cause is not established. The parallel property is an unfinished request, not a proved behavioral failure; attributing its elapsed cost to either the cut or the parent requires a comparison this record does not contain. Those two limits must not be turned into a blanket “all reds pre-existing” or a green cut.

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

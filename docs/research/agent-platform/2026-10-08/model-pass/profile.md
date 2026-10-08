# Model pass: allocation owners and slice coverage

Measured 2026-10-08 by lane `model-pass-profile`. Notes only. The model entrance wakes an armed agent, refreshes its context, builds and captures a prompt, calls the local HTTP fixture, evaluates one form, and records it. The smallest planned composition is the existing pass with a held transcript and one settlement; remove the redundant work before optimizing the surviving owners.

**The planned slices do not yet buy the ordinary-pass budget.** Project **20–40 MB and 40–100 ms for a warm ordinary model pass**, excluding provider latency and opening/input passes. That likely meets **<200 ms**, but misses **<5 MB** by 4–8×. A base-context miss adds approximately **428 MB and 180–215 ms**. These are estimates from measured surviving work, not measurements of an implemented end state. No target API or performance acceptance is claimed.

## Findings and limits first

- The exact `run-model "default" 1 #{}` workload is a **first model wake on a newly prepared agent**, not steady execution on a long-lived model agent. This run reproduces its approximately 0.9 GB cost. The first wake includes base-context construction; subsequent wakes avoid that construction but still cost **473–578 MB**, with seven durable commits each. Moving construction into an opening pass does not fix its cost.
- The base-context miss is **not unique to the deleted refresh**: `resume-turn` also calls `sci.eval/acquire!` before its fork (`src/seon/turn.clj:5153`). In the four-pass MCP control, pass 2 also rebuilt the base: **463.09 MB / 256.42 ms**, of which acquisition was **215 ms**. Later passes were **32–35 MB / 40–42 ms**. The invalidating input/cache-miss cause was not diagnosed here; do not call this a cache-key bug on this evidence.
- The writer alone allocates **13–14 MB on `async-mixed` platform threads for a warm, one-commit MCP control**. Reducing seven commits to one is necessary and insufficient for a <5 MB complete pass. No P0–P14 or enumerated S4–S6 row explicitly optimizes the surviving checked writer.
- Initial shared-root status: pid 51487, archive `b20762d6b`, health `:faulted`, two open faults, 33 errored receipts and six over-bound warnings. MCP `runtime_status` agreed. No definition, evaluation probe, reload, stop or reset was sent to that JVM.
- The first isolated launch reached readiness in 45,039 ms, then its process disappeared when the tool's launcher session ended. It produced no measurement. A retained launcher session fixed the measurement setup; no platform cause is asserted for that process exit. The measured JVM was pid **48026**, start instant **2026-10-08T01:49:22.507Z**, readiness **8,671 ms**. The initial population/readiness cost is outside the pass windows; existing issue `docs/seon/issues/from-zero-boot-takes-minutes.md` and task `seon.cluster/boot-publication-phases-are-invisible` cover the boot-cost/observation classes.
- Allocation is cumulative **allocated bytes**, decimal MB, not retained heap. No retained-heap improvement is inferred. Concurrent work in this isolated JVM, including the armer and writer, is included. External processes may contend for CPU/disk but their allocation is not included.

## Installation, workload and measurement

The measured immutable archive is **`ded5e35deaee3fb7321fe386def475c4d234c483`**, under `tmp/model-pass-profile/root/data/source/`. All source line references in this note refer to that archive, not the moving checkout. Dependency pins: Datahike `e5453d967b749794944dbdfe64b751519983ba59`, SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`. The first abandoned launch had the immediately earlier `e21fc4674` archive; it is not the measured baseline.

The isolated root's cluster happens to be named `default`; its root, store, process and socket are different from the shared default. The driver used `seon.operator/advertisements` for this **explicit root** and `prepl-value!` for transport. JVM orchestration called `seon.pass-probe/run-model` and `run-mcp`; those enter the actual agent proc/MCP, not an alternate evaluator. Installed Var resolution succeeded for the probe entrants and `seon.turn/system-turn`. No production Var was replaced. Only committed test-support/probe code was required; all measurement forms were local `let`/`do` forms. No paid provider call occurred.

Form: `(apply str "h0-" (repeat 1990 \x))`, fenced for the model, approximately 2 KB of shown value. `run-model` prepares an agent using MCP `my.agent/settings!`, the `support/with-provider` loopback HTTP endpoint and a one-call cap. Preparation and retirement are outside its measured window. Each window begins before the outside wake and ends after the terminal **and idle** proc reports. Consequently the seven commits include the outside-message write; this is a **wake-cycle window**, not seven writes all belonging to the final model settlement. The future input/opening passes must likewise be measured separately and as a cycle.

The installed `test/seon/pass_probe.clj:31–68,129–161,328–358` takes ThreadMXBean totals and platform-thread deltas, a konserve write hook, and armed profile counter deltas. Virtual-thread allocation is charged to carrier platform threads; an absent current-virtual-thread count is never read as zero. Thread-group deltas miss exited threads; the whole-JVM counter includes them. The profiler's outer `begin` and `explain` allocations are outside the allocation window. Inclusive armed milliseconds are **not additive** across nested calls or threads.

### Raw pass rows (no JFR)

| Specimen | Allocated MB | Wall ms | Fixture ms | Durable commits |
|---|---:|---:|---:|---:|
| New model agent 1, first fixture use | 785.837 | 675.047 | 9.869 | 7 |
| New model agent 2 | 751.117 | 522.493 | 0.422 | 7 |
| New model agent 3 | 919.277 | 570.062 | 0.440 | 7 |
| One retained model agent, pass 1 | 926.044 | 629.511 | 0.528 | 7 |
| Same agent, pass 2 | 485.132 | 375.193 | 0.470 | 7 |
| Same agent, pass 3 | 542.719 | 437.960 | 0.651 | 7 |
| Same agent, pass 4 | 578.069 | 428.927 | 0.629 | 7 |
| Separate staged probe, first pass | 928.039 | 601.520 | local fixture | 7 |
| Same staged probe, second pass | 473.371 | 427.246 | local fixture | 7 |
| New MCP SCI agents, three controls | 46.251 / 32.703 / 35.677 | 66.995 / 42.292 / 37.034 | — | 1 each |
| One retained MCP SCI agent, four controls | 36.559 / **463.095** / 34.639 / 32.320 | 39.041 / **256.421** / 40.347 / 42.263 | — | 1 each |

The final first-pass/second-pass pair gives exact platform-thread allocations:

| Thread role | First MB | Second MB |
|---|---:|---:|
| `ForkJoinPool-1-worker` carriers | 819.571 | 353.870 |
| `async-mixed` writers/storage | 107.066 | 118.073 |
| PREPL driver | 0.067 | 0.069 |
| HTTP dispatcher | 0.115 | 0.105 |
| Other live threads combined | 0.101 | 0.056 |
| Total including exited/unattributed threads | **928.039** | **473.371** |

### JFR and the disjoint owner ranking

Two single-pass recordings: a newly prepared agent's first pass and a retained agent's third pass. Capture configured with `jcmd 48026 JFR.configure stackdepth=256`; profile settings, allocation sampling throttle 1000/s and execution sampling period 1 ms. Export used **`jfr print --json --stack-depth 256`**. The first recording contains 580 allocation samples (weighted **990.607 MB**) and 387 execution samples; the warm recording contains 503 allocation samples (weighted **522.489 MB**) and 266 execution samples. Exact window totals were **1010.590 MB / 951.071 ms** and **536.416 MB / 482.740 ms**, respectively. Starting/stopping JFR is inside those exact windows: use the unrecorded rows for baseline latency.

The table partitions allocation sample weights once, using the classifier in the appendix. It is not a sum of inclusive flamegraph totals. It identifies the owning operation, rather than ranking generic instrumentation frames which contain nearly everything. MB are sampled estimates; in particular small rows have few samples. 404/580 first-pass and 126/503 warm allocation stacks reach the 256-frame cap, so the broad context row deliberately combines refresh rendering, prompt rendering and historical projection work. It does not falsely assign an unavailable outer caller. Time is the **unrecorded staged pair's inclusive owner counter**, first / warm, rounded to integer milliseconds; grouped and nested rows cannot be summed.

| Rank by first-pass MB | Owner | First / warm sampled MB | Measured owner ms, first / warm | Cost follows | Covering slice; what remains |
|---|---|---:|---|---|---|
| 1 | `sci.eval/derive-base-ctx`: load core namespaces, build SCI base, acquire program | **427.953 / 0** | **180 / absent**; `acquire-program!` within it 110 / absent | Program namespace/definition population, affected reach and a base-cache miss | **NO explicit performance slice.** S4-06 deletes one caller; `resume-turn` still acquires. S5-06 stops **JVM** acquisition only. New acquisition slice required; construction can move to opening but still must be priced. |
| 2 | Context projection/render: `turn/declared-sources`, `render.walk/neighborhood`, `render.web/derive-context!`, `prompt/prompt`, historical projection and render call reach | **405.342 / 349.994** | declared sources **104 / 88**; prompt **88 / 116** | Rendered neighborhood/reachable program, historical declaration datoms and saved history visited again per wake | **S4-02b, S4-03, S4-04, S4-06, S4-07b** replace repeated opening/refresh/prompt traversal with saved exchanges and append. Cold acquisition survives once, not each call. S4-01 bounds the history entrance. |
| 3 | Checked writer and durable storage, all seven commits | **97.198 / 125.486** (exact thread totals ~108 / 131 in JFR windows) | `transact-call` **140 / 145** for seven; validation **18 / 19**, owning-value validation **12 / 12** inside it | Number and shape of commits; written datoms, owning ancestors/children, encoded values and persistent index nodes | **S4-06/07b, S5-02/05/07a–f, S6-04a/b, P14** remove obsolete records/commits and unify settlement. **NO row fixes the remaining per-commit allocation.** New writer-floor slice required. |
| 4 | Remaining evaluate/record path, outside writer/context categories | **17.738 / 8.384** | batch evaluation **12 / 2**; settlement **24 / 21** includes the writer | Forms, emitted values, diagnostics, recording preparation | **P6/P7/P8/P13, S5-05/07, S6-03.** No analysis occurred for the ordinary form (`derive-form` 0 ms); do not credit P8 with a fictitious large ordinary-pass saving. The form evaluation and one print remain. |
| 5 | Refresh bookkeeping remaining after context/base categories | **11.832 / 16.773** | `system-turn` **357 / 165** includes ranks 1/2 and its recording; no separate self-wall clock | Retained forms/evidence and changed generated reads | **S4-06**, with opening/input conversion **S4-02b/05b**. Entire old refresh disappears; replacement input/opening work has its own budget. |
| 6 | `sci.eval/fork-for-turn` / regeneration, two calls | **11.463 / 12.593** | **12 / 11** | Inherited namespace/Var state, carried private layer and result handles | **S4-06** removes the refresh's fork. **NO enumerated row makes the surviving per-pass fork an in-place retained context.** P11 addresses print-option tax, not this whole namespace walk. New context-retention slice required. |
| 7 | `turn/next-agent-work`, spent/episode-run queries | **9.958 / 6.007** | next-work **8 / 6** (4 / 3 calls), not all episode work | Wakes, call history under the anchor/assignment and repeated work derivation | **S6-05/07** replace episode accounting/work rules and retire permits/backstop. Current redundant-work task is `seon.turn/a-turn-pass-derives-its-work-twice`. |
| 8 | Other: armer, provider settings, query preparation, HTTP/runtime tail | **6.872 / 3.252** | `ai/complete` **3 / 3** inclusive of fixture; no reliable separate clock for every small tail | Agent roster, configured provider keys, query/input and request text sizes | Some generic query cost falls with S4/S6 caller deletion. **NO explicit row covers surviving armer/provider configuration work.** Keep a measured residual row; a new residual-cost slice must establish its budget, not assume zero. |
| — | PREPL/JFR driver | **2.251 / 0 sampled** | Not product work | Recording start/stop | Excluded from end-state projection; warm exact driver allocation was 0.363 MB. |

By the independent named stage clocks, the unrecorded first pass ranks **context construction (104 + 88 = 192 ms), base acquisition (180 ms), then seven durable writes (140 ms)**. The refresh's 357 ms is an inclusive parent, not a fourth cost to add. The retained pair ranks context (88 + 116 = 204 ms), then writes (145 ms); base construction is absent. Remaining evaluation, fork and work clocks are in the table.

Useful overlapping drill-downs, **not additional savings**: first JFR shows `load-core-namespaces!` **162.805 MB**, `acquire-program!` **263.138 MB**, `render-program-evidence` **136.817 MB**, `CachedStorage.accessed` **344.636 MB**, and `validate-write-report` **25.459 MB**. The first two are inside base construction; the cached-storage callback is shared by readers and writer. `storage_string`/encoding is inside the writer row. Removing the caller deletes its dependency work too; do not add these figures again.

## Verified ownership and dependency seams

- **Base construction:** `src/seon/sci/eval.clj:1688` queries admitted core namespaces, including `or-join`; `:2352` acquires the program; `:2991` reconstructs the base, `:3026` keys it by program identity, loaded identity and fault recorder; `:3058` caches it. Inputs are database program and loaded-world identity; reconstruction happens on a miss. Existing task **`seon.sci.eval/acquisition-rebuilds-the-whole-program`** covers the defect class. This profile establishes the expense and miss occurrence, not why these particular input identities differ.
- **Retained-context regeneration:** `sci/eval.clj:2732` walks SCI namespace state, and `:2842` forks/regenerates each pass. SCI's own **`sci/fork`**, `reference-code/sci/src/sci/core.cljc:334–340`, wraps shared environment data in a new atom with a generation; it does not require Seon's full program walk. Preserve private objects and changed program semantics before deleting Seon's extra work. The prospective proof is unchanged-program passes causing zero full namespace reconstruction, plus private-object identity and changed-definition visibility.
- **Context/history:** `turn.clj:1751` traverses `walk/neighborhood` before the provider; `:2056` reads prior forms and refreshes; `:4422` calls prompt construction and captures it. `render.clj:874,891` derives cached render call reach; `db.clj:1643` performs two index lookups per reached symbol. It is proportional to **reach**, not an unconditional whole-program scan. Historical projection fallbacks also appear. S4 must delete those AI callers, not merely hide the output.
- **Writer:** `db.clj:4097` validates owning values and `:5355` validates the final report through Datahike's existing final-report validator, `reference-code/datahike/src/datahike/db/transaction.cljc:1224–1234`. Inputs are final report and installed projection; event is every commit. Reuse the writer and its invariants, not an unchecked alternative. Existing tasks **`seon.db/the-checked-writer-is-slow-on-a-fresh-branch`** and **`seon.db/every-commit-validates-its-write-report-three-ways`** cover the performance class. The latter's old numbers are not this note's measurements. Malli's **`malli.core/validator`**, `reference-code/malli/src/malli/core.cljc:2672–2678`, caches on a compiled schema; this is the supported validation seam, not proof that recompilation is this run's cause.
- **Dependency allocation:** Datahike's `CachedStorage.accessed`, `index/persistent_set.cljc:450–455`, calls the configured cost center, trace logging, stats update and cache hit per accessed node. Samples locate large allocation there. First reduce the upstream scans; any residual dependency optimization needs a new measured slice and must preserve its observation/cache guarantees.
- **Reader/facts:** SCI `parse-next+string`, `core.cljc:400`, returns form and source at one cursor. P7/P13 reuse that seam. This small ordinary form did not exercise definitions, closure admission, bulk output, `set!` or malformed contracts; no savings from P1–P5, P9–P12 are assumed. Those behavioral slices remain valuable without claiming to remove hundreds of MB here.

## End-state estimate and missing slices

For a **warm ordinary model pass after opening/input**, use this explicit provisional envelope:

| Surviving work | Allocated MB estimate | Wall ms estimate | Grounding |
|---|---:|---:|---|
| One checked durable settlement | 10–18 | 15–30 | Actual one-commit MCP writer threads use 13–14 MB; final model settlement is 21–24 ms inclusive. Deleting fields can reduce this; a merged attempt record can increase it. |
| One surviving context fork/regeneration | 5–7 | 5–7 | Two current forks sample 11–13 MB and measure 11–12 ms. Halving is an estimate, not two individually measured equal calls. |
| Reader/eval/print, held-record finish, work/provider preparation, transcript append and scheduling | 5–15 | 20–60 | Observed small remaining rows, reduced by P7/P8/S5/S6; includes allowance for the replacement append and window check. |
| **Rounded ordinary-pass forecast** | **20–40** | **40–100** | Estimated, not green evidence. |

The planned <10 ms append and ≤3× appended-text allocation are requirements, not an observed implementation. Full provider request serialization is still proportional to the transmitted history; a 1/10/200-exchange acceptance matrix is needed. This one/four-wake study cannot establish the 200-exchange budget.

A base miss adds roughly **428 MB / 180–215 ms**: approximately **450–470 MB / 220–315 ms** for an otherwise small pass that must rebuild. If S4 moves it outside the model pass, report it as opening/acquisition instead; the opening's <5 MB/<200 ms target is still not proved. The window corresponding to `run-model` will also contain an outside write and the future input/opening passes. Do not relabel the ordinary-pass estimate as the complete first-wake cost.

Missing work, in order of reaching a credible end state:

1. **Acquisition and context retention** (new slice or explicitly expand a priced row): reuse content-valid bases, diagnose the measured miss, and make unchanged ordinary passes avoid full namespace regeneration. Existing acquisition task above; add deterministic proofs of derivation counts, private-object identity and program-change visibility. S5-06's JVM-only change is not this work.
2. **The remaining writer floor** (new slice under the existing writer tasks): profile one final-shape commit after S5/S6, reduce allocations in validation/encoding/index access without weakening final owning-value checks. Require the complete ordinary pass <5 MB, not merely "fewer transactions". The single-commit control already disproves sufficiency of transaction-count reduction.
3. **Surviving armer/provider/query residual and real history transmission** (new bounded measurement/fix slice): establish a residual allowance and count unchanged recomputations at 1/10/200 exchanges. The sample tail is small relative to today's GB pass but material relative to 5 MB. Its causal split needs more samples before choosing a fix. No invented task identity is assigned here.

Land the already-covered S4 deletions and S5/S6 record unification first; they remove most repeated work and expose the true final writer shape. Price the uncovered slices now so they are not mistaken for savings already promised by P0–P14. The estimate after **only the currently enumerated** slices fails allocation; after the proposed extra work there is no evidence-based <5 MB prediction yet. Measure and enforce it.

## Reproduction and evidence

Local evidence retained under `tmp/model-pass-profile/`: `baseline.edn`, `steady.edn`, `profile.edn`, `warm-profile.edn`, `pass.jfr`, `warm.jfr`, exported JSON, `aggregate.txt`, `classify.txt`, `classify-warm.txt`, `lint.txt`, startup/status receipts and driver forms. JFR recordings include JVM metadata; they are local artifacts, not published attachments. The numeric tables and executable measurement/classification recipes below are committed so the evidence does not depend on chat or temporary files.

Start through `bin/seon --root <owned-root> start default`, retaining the launching shell until cleanup in this tool environment. Verify the archive, pid and start instant before configuring JFR; use the new pid, never reuse 48026 blindly. Read the pinned owners before rerunning on a later revision. No worktree is needed.

Driver transport (Babashka with the committed operator on its classpath):

```clojure
(require '[seon.operator :as op] '[clojure.pprint :as pp])
(let [root "/Users/sean/src/seon/tmp/model-pass-profile/root"
      ad (first (op/advertisements root))]
  (when-not ad (throw (ex-info "No owned JVM" {:root root})))
  (pp/pprint (op/prepl-value! ad (slurp (first *command-line-args*))
                            120000 nil 120000)))
```

Baseline driver form (setup is outside each installed probe window):

```clojure
(do
  (require 'seon.pass-probe)
  {:model (mapv (fn [_] (seon.pass-probe/run-model "default" 1 #{})) (range 3))
   :mcp (mapv (fn [_] (seon.pass-probe/run-mcp "default" "sci" 1 #{} false)) (range 3))})
;; Retained-agent comparison, a separate driver call:
{:model (seon.pass-probe/run-model "default" 4 #{})
 :mcp (seon.pass-probe/run-mcp "default" "sci" 4 #{} false)}
```

JFR settings and export:

```sh
jcmd VERIFIED_PID JFR.configure stackdepth=256
jfr configure --output tmp/model-pass-profile/pass.jfc --input profile.jfc \
  jdk.ObjectAllocationSample#throttle=1000/s jdk.ExecutionSample#period=1ms
jfr print --json --stack-depth 256 \
  --events jdk.ObjectAllocationSample,jdk.ExecutionSample \
  tmp/model-pass-profile/pass.jfr > tmp/model-pass-profile/pass.json
```

The following is the executed first-pass driver form. It uses the installed probe's setup/window/pass owners, calls no replacement Var, and brackets **only the pass**, excluding new-agent preparation and retirement, with JFR. For the staged warm version, queue three identical replies, call `(seon.pass-probe/window world pass)` twice before the recording, retain both results, then record the third pass. The staged version also adds the acquisition and declared-source Vars to `:seon.pass-probe/stages`.

```clojure
(let [agent-id (str "model-pass-profile-" (seon.id/id))
      ledger (atom []) timings (atom [])]
  (seon.test-support/with-provider
   ledger (seon.test-support/reply-queue [(#'seon.pass-probe/fenced (#'seon.pass-probe/filler 0))]) timings
   (fn [server]
     ((#'seon.pass-probe/mcp-pass
       {"agent" agent-id "cluster" "default"
        "code" (str "(my.agent/settings! " (seon.id/printed
                  (merge (seon.test-support/provider-config server)
                         {:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 1})) ")")}))
     (let [world (assoc (#'seon.pass-probe/agent-world "default" agent-id)
                        :seon.pass-probe/ledger ledger
                        :seon.pass-probe/stages
                        '#{seon.turn/system-turn seon.turn/call-turn seon.turn/resume-turn
                           seon.turn/settle! seon.turn/settle-batch! seon.turn/record-evaluated-call
                           seon.turn/fault-window seon.turn/with-fault-windows
                           seon.turn/close-folded-call seon.turn/close-call seon.turn/system-run-call
                           seon.turn/open-call seon.turn/plan-call seon.turn/record-attempt!
                           seon.turn/next-agent-work seon.turn/step
                           seon.cluster.prompt/prompt seon.render/acquire-context!
                           seon.context/capture-tx seon.profile.measurement/significant-tx
                           seon.profile/begin seon.profile/explain seon.eval/of-agent
                           seon.db/transact-call seon.db/validate-write-report seon.db/write-owned-values-error
                           seon.ai/complete seon.sci.eval/acquire seon.sci.eval/fork-for-turn
                           seon.sci.eval/evaluate seon.batch/evaluate-batch
                           seon.turn.batch/analyze-form seon.turn.batch/derive-form
                           seon.render.value/text seon.repl/text})
           pass (#'seon.pass-probe/model-pass world)
           recording (jdk.jfr.Recording. (jdk.jfr.Configuration/create
                       (java.nio.file.Path/of "/Users/sean/src/seon/tmp/model-pass-profile/pass.jfc" (make-array String 0))))]
       (try
         (let [result (seon.pass-probe/window world
                        (fn [] (.start recording)
                          (try (pass) (finally (.stop recording)))))]
           (.dump recording (java.nio.file.Path/of "/Users/sean/src/seon/tmp/model-pass-profile/pass.jfr" (make-array String 0)))
           (assoc result :provider-timings @timings))
         (finally (.close recording)
                  (some-> (:seon.pass-probe/host (#'seon.pass-probe/agent-world "default" agent-id)) seon.cluster.host/retire!)))))))
```

Allocation partition recipe (first matching owner wins; run once per exported file). Count execution samples with the same classifier for supporting CPU evidence, never convert them into exact wall ms.

```python
import json,collections
x=json.load(open('tmp/model-pass-profile/pass.json'))['recording']['events']
def names(v):return [f['method']['type']['name']+'.'+f['method']['name'] for f in (v.get('stackTrace') or {}).get('frames',[])]
def classify(v):
 ns=names(v); s='\n'.join(ns); thread=(v.get('eventThread') or v.get('sampledThread') or {}).get('javaName','')
 if thread.startswith('async-mixed'):return 'writer and storage'
 if 'seon/sci/eval$derive_base_ctx.' in s:return 'base context acquisition'
 if any(k in s for k in ['seon/render/', 'seon/render$', 'seon/turn$declared_sources.', 'seon/cluster/prompt$', 'seon/schema$projection_from_database', 'seon/db$declaration_content_key']):return 'context projection / prompt'
 if 'seon/sci/eval$fork_for_turn.' in s:return 'context fork/regeneration'
 if any(k in s for k in ['seon/turn$next_agent_work', 'seon/turn$spent_runs', 'seon/turn$episode_runs', 'seon/turn$more_agent_work']):return 'work / episode derivation'
 if any(k in s for k in ['seon/turn$fault_window','seon/profile/measurement$']):return 'fault/profile settlement'
 if 'seon/turn$system_turn' in s:return 'refresh remaining'
 if any(k in s for k in ['seon/turn$resume_turn','seon/batch$','seon/turn$evaluate_sources','seon/sci/eval$evaluate']):return 'eval / record remaining'
 if thread.startswith('Clojure Connection'):return 'driver / JFR'
 return 'other'
mb=collections.Counter(); cpu=collections.Counter(); other=collections.Counter(); trunc=collections.Counter()
for e in x:
 v=e['values']; g=classify(v)
 if e['type']=='jdk.ObjectAllocationSample':
  mb[g]+=v['weight']
  if g=='other':other[tuple(n for n in names(v) if n.startswith(('seon/','datahike/')))]+=v['weight']
  if (v.get('stackTrace') or {}).get('truncated'):trunc[g]+=v['weight']
 else:cpu[g]+=1
for g,w in mb.most_common():print(g, round(w/1e6,3), 'CPU samples',cpu[g], 'truncated MB',round(trunc[g]/1e6,3))
print('OTHER STACKS')
for st,w in other.most_common(15):print(round(w/1e6,3),'\n '.join(st))
```

## Landing and verification

- Claimed this note with `bin/lane claim model-pass-profile docs/research/agent-platform/2026-10-08/model-pass/profile.md` before creating it. No code, schema, dependency or test edits; source/test delta **0/0**. Measurement scripts are embedded above, rather than adding program files.
- Foreign boundary: the checkout had another lane's edits in `src/my/program.clj`, `src/seon/sci/eval.clj`, `resources/seon/tasks/my.program.edn` and `test/my/program_mutation_test.clj`, plus untracked P1 test files, at entry. Measurement used the committed archive. Those edits subsequently landed independently; this note neither tests nor claims their performance. Other untracked files and the dirty HTTP-kit working directory were untouched.
- Required full lint: `clj-kondo --lint src script test --cache false` completed in **6,597 ms**, **0 errors**, **1,455 warnings**. Exit 2 is warnings; this is not a zero-findings claim. The note changes no lint input.
- Required proof: `seon.contracts-compile-test/a-check-of-no-sources-has-no-contract-findings`. This is the documentation/no-program-input gate, not a performance regression test. No broader test namespace was run or claimed green.
- Final isolated health observation: `:degraded` solely for unowned namespace `user`, **zero open turns**, no reported open faults; heap-used **331.669 MB**, last reported heap-live **323.281 MB**, 33 platform threads. This is a status snapshot, not a heap-retention experiment. The phase samples above all completed, each fixture supplied one response and the form count was one.
- Cleanup: `bin/seon --root /Users/sean/src/seon/tmp/model-pass-profile/root down` returned `SEON down OK`; the held launcher exited, and its owned disposable root was removed without following archive/cache symlinks. The local JFR/evidence files beside that root are intentionally retained. The commit gate result is recorded in the terminal report. No shared JVM restart is part of a documentation landing.

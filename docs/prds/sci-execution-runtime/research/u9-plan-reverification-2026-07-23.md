---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# U9 plan re-verification — 2026-07-23

Re-verified the U9 deletion plan’s baseline `fe4bfed0c` against current HEAD `bd5638f03394b1efbf353b66316d2989da29c141`. No files were edited.

## Verdict

- **Slice S0a is dependency-ready but not implemented.** B1 is byte-for-byte unchanged: `/agent/{id}` still invokes the dying child renderer through `src/seon/web/datastar.cljs:1074,1093-1096`, and compiled view calls still enter `child-lane` at `src/seon/execution/host.cljs:997-999`.
- **S1 is not ready.** S0a remains mandatory; scheduled functions remain broken; compiled-symbol resolution acquired an additional `seon.eval` consumer.
- **B2’s uncertainty is resolved, not its implementation.** The live probe and
  R46 settled the cluster JVM design, but current source still discards a
  tierless invocation error and falsely closes the scheduled turn.
- **B3 expanded rather than shrank.** R43 separated provenance-based trust classification from resolution, but the new compiled renderer resolver still delegates to `seon.eval/lookup-value`.
- **S4 is not ready.** The census contains exactly 18 pending rows, not approximately 17, and none closed tonight.

## Deletion inventory delta

| Plan row | Verdict | Current evidence |
|---|---|---|
| `src/seon/execution/runtime.cljs` | **Still valid**; 329 LOC | Agent-view implementation remains `:79-227`; child eval `:261-310`; compiled map/main `:312-329`. |
| `src/seon/eval.cljs` | **Drifted**; 5,297 LOC, not 5,300 | Self-host imports remain `:44-53`; bootstrap initialization `:398-448`; `cljs/eval-str` path `:1155-1262`; batch engine reaches `:4985`. The three-line change removed relocated schema registrations, not engine behavior. |
| `src/seon/repl.cljs` | **Still valid**; 124 LOC | Self-host state remains `:48-58,84-116`; the call-free production require remains at `src/seon/web/serve.cljs:50`. |
| `src/seon/analyzer_info.cljs` | **Drifted** | Source remains self-host-specific at `:1-28,71-349`, but `test/seon/analyzer_info_test.cljs:163-174` now contains a portable namespace-reader regression that must migrate to `seon.ns.source` before the rest of this test dies. |
| `execution.cljs` contract | **Still valid** | Contract remains `src/seon/execution.cljs:24-313`. |
| `execution.cljs` acquisition | **Still valid** | `canonical-program` `:482`; `source-digest` `:566`; `invocation-plan` `:572`; `compiled-invocation` `:584`; `prepare-invocations!` `:609`. |
| `execution.cljs` self-host install band | **Still valid** | Exact band remains `:868-1055`: entrypoints `:868,881,918,926,996,1029`. |
| `execution.cljs` child-owner band | **Drifted one line** | Band is now `:1057-1520`; `begin-invocation!` `:1260`, cancellation `:1370`, shutdown `:1383`, receive `:1399`, child start `:1435`, main `:1475`. |
| `execution/host.cljs` child helpers | **Still valid** | `child-lane` and helpers remain `:108,182-305`; spawn `:509`; retire `:650`; child stop `:1338`. |
| `execution/host.cljs` child dispatch | **Still valid** | `invoke-now!` remains `:977-1026`; Bun eval `:991-995`; compiled render child arm `:997-999`; coordinate-absent authored fallback `:1014-1017`. |
| `execution/host.cljs` compiled helpers | **Still valid** | `invoke-plans!` remains `:1233-1261`; `invoke-compiled!` `:1263-1283`. |
| `execution/host.cljs` host survivor | **Still valid** | Host session `:569-649`; configuration `:695`; sampling `:1046-1168`; invocation `:1170-1231`; host cancellation/stop arms `:1285-1382`. |
| `host/session.clj` protocol projection | **Still valid** | Hand projection remains exactly `src/seon/host/session.clj:12-78`. |
| `agent/turn.cljs` local eval | **Drifted** | `eval-parsed!` moved to `src/seon/agent/turn.cljs:535-587`; its sole production caller remains `src/seon/agent/loop.cljs:556-558`. |
| `web/datastar.cljs` agent-view indirection | **Still valid** | Child symbol and call remain `src/seon/web/datastar.cljs:1074,1093-1096`. |
| `runtime/recovery.cljs` trim | **Corrected plan error** | The source is 702 LOC, not 468. Child evidence remains `src/seon/runtime/recovery.cljs:260-300`; artifact-digest evidence remains `:465-476`. |
| `client.cljs` execution-host calls | **Drifted** | Configuration moved to `src/seon/client.cljs:1994-1996`; shutdown moved to `:2610`. |
| Shadow child builds | **Drifted line references; all present** | `:execution` `shadow-cljs.edn:168-177`; integration client `:195-202`; ACME `:207-215`; experimental builds `:411-418,425-432,440-447`. |
| Probe residue | **Still valid** | Tracked sources remain under `tmp/sci-probe/`; untracked `.shadow-cljs-b2/` and `out-b2/` remain. |
| `launch.cljc` execution fields | **Drifted/expanded** | Schemas `src/seon/launch.cljc:26-28,82-84,175-176`; descriptor propagation `:305-322`; artifact binding `:378-399`; later projections `:453-456,526-529,579`. |
| `artifact.clj` execution plumbing | **Materially drifted/expanded** | Child fields remain `script/seon/dev/artifact.clj:47,56-58,71-72`; digest/runtime `:464-482`; manifest propagation `:510-621`; release output `:699-835`; immutable-runtime members `:1012-1275`. Preserve new program-row/client-inventory machinery at `:50-55,401-447,516-521`. |
| `release.clj` execution plumbing | **Materially drifted/expanded** | Protocol/member rows `script/seon/dev/release.clj:24,51-62,98-108,128-138`; runtime identity `:611-626`; packaging `:766-826`; later output/inventory assembly `:1028-1114`. |
| `process.clj` execution plumbing | **Materially drifted/expanded** | Process fields `script/seon/dev/process.clj:40-81`; artifact projection `:267-294`; build vector `:364-370`; digest `:392-411`; descriptor binding `:413-446`; later readiness/spec fields remain at `:538-567,890-925,2613-2638`. |
| `config.clj` execution rows | **Still valid; broader footprint** | Rows remain `script/seon/dev/config.clj:33-40,120-129,305-324,479-488,522-531,583-592`. |
| `eval/bootstrap_cache.cljs` | **Still valid** | Self-host consumer remains `src/seon/eval.cljs:67,435`; diffusion consumer remains `src/seon/diffusion/worker/eval.cljs:80,145`. |
| `eval/receipt.cljc` | **Still survives** | Recovery still requires it at `src/seon/runtime/recovery.cljs:20`; JVM recording remains `src/seon/host/eval.clj:335-344`. |
| `subprocess.cljs` | **Still survives** | Child consumer remains `src/seon/execution/host.cljs:28`; independent consumers remain in `src/seon/repl/autocomplete.cljs:42`, `src/seon/agent/search/internal.cljs:14`, and shell leaves. |
| `host/context.clj` compatibility band | **Still outside U9** | No U9 deletion authority should absorb the compatibility owner independently. |

## Test inventory delta

| Plan row | Verdict | Current evidence |
|---|---|---|
| Direct execution tests | **All present; weights drifted** | Integration driver 226 LOC; host test 1,655; execution test 1,342; runtime test 982. Provenance changes affect `test/seon/execution_test.cljs:980-1113`; reply-policy changes affect retained view coverage at `test/seon/execution/runtime_test.cljs:137-200,406-413`. |
| Turn child stubs | **Still valid** | `test/seon/agent/turn_test.cljs:257-355`. |
| Recovery child-death tests | **Still valid** | `test/seon/runtime/recovery_test.cljs:80-190`. |
| Residual child guidance | **Still valid; corrected path** | Assertions are in `test/seon/ctx_test.cljs:242,287`, not under `test/seon/agent/`. |
| Self-host eval tests | **All present** | The eight deletion candidates under `test/seon/eval/` remain; receipt remains 717 LOC and race-timeout 77 LOC for migration. |
| Analyzer test | **Drifted** | Now 215 LOC; preserve/migrate the portable reader assertion at `test/seon/analyzer_info_test.cljs:163-174`. |
| REPL parity / instrumentation | **Still present** | `test/seon/repl_parity_test.cljs` remains 85 LOC; `test/seon/instrument_smoke_test.cljs` 84 LOC. |

## Blocker delta

| Blocker | Verdict | Delta |
|---|---|---|
| B1 — agent-view child render | **Still unresolved** | No post-plan commit changed `src/seon/web/datastar.cljs:1074,1093-1096` or `src/seon/execution/host.cljs:977-1026`. S0a remains mandatory. |
| B2 — scheduled functions | **Implementation unchanged; investigation resolved** | Broken path remains `src/seon/agent/loop.cljs:515-558,654-669`, `src/seon/agent/turn.cljs:535-554`, and rejection at `src/seon/execution/host.cljs:983-989`. The live falsifier proved a discarded error and false `:done`; R46 settled the durable eval-ready turn/cluster JVM design at `docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1545-1564`. |
| B3 — compiled lookup | **Scope expanded** | Original consumers remain `src/seon/agent/turn.cljs:413-420`, `src/seon/web/router.cljs:169-181`, and `src/seon/web/serve.cljs:497-518,1817-1821`. R43 added `resolve-compiled`, whose CLJS branch still uses `eval/lookup-value`, at `src/seon/render/core.cljc:17-24`; general render and warning consumers now enter it at `src/seon/render.cljc:63-72,756-769,1067-1077` and `src/seon/warn.cljc:805-812`. |

R43 therefore did **not** shrink B3. It removed trust classification from the static table but left resolution dependent on the self-host engine: `src/seon/render/core.cljc:2-7,17-42`. S0c must cover four groups—prompt, route, controls, and general render/warning resolution—not the plan’s original three.

## Consumer re-point ledger

| Row | Current verdict | Evidence |
|---|---|---|
| L1 | **Still valid** | JVM eval remains `src/seon/agent/driver/host.clj:60-66,361-414,482-483`; recording remains `src/seon/host/eval.clj:335-344`. |
| L2 | **Still valid** | Prompt rendering remains in-pod at `src/seon/agent/turn.cljs:460-500`; authored calls use the host door at `src/seon/host/invoke.clj:144-149`. |
| L3 | **Still blocked** | `src/seon/web/datastar.cljs:1074,1093-1096`; `src/seon/execution/host.cljs:997-999`. |
| L4 | **Still blocked; design settled** | `src/seon/agent/loop.cljs:515-558`; tierless rejection `src/seon/execution/host.cljs:983-989`. |
| L5 | **Still valid with the same S1 edge** | Web preparation/invocation `src/seon/web/reactive/call.cljs:136-152`; coordinate-absent fallback `src/seon/execution/host.cljs:1014-1026`. |
| L6 | **Still valid** | Sampling spans both lanes at `src/seon/execution/host.cljs:1046-1053`. |
| L7 | **Still valid** | Result-reference checks remain `src/seon/execution/host.cljs:837-850,993-995,1023-1026`. |
| L8 | **Drifted; expanded** | New central resolver dependency described under B3. |
| L9 | **Still valid** | Pod phases `src/seon/agent/driver/pod.cljs:32-54`; JVM phases `src/seon/agent/driver/host.clj:454-483`. |
| L10 | **Still unresolved by design** | Bun eval still routes to child at `src/seon/execution/host.cljs:990-995`. |
| L11 | **Still valid** | Recovery transition remains database transaction data at `src/seon/runtime/recovery.cljs:360-410`. |
| L12 | **Still valid** | Diffusion bootstrap remains independent at `src/seon/diffusion/worker/eval.cljs:75-84,135-151`. |

No L-row was newly re-pointed or regressed. The only material ledger change is L8’s expanded consumer surface.

## Census cutover delta

There are exactly **18** explicit pending rows, and none closed tonight.

| Rows | Verdict | Evidence |
|---|---|---|
| `my.canvas/{clear!,pinned,save!,show!,state,view}` | **Still pending** | Seeds `test/seon/host_surface_writer_test.clj:171-180`; implementations `src/my/canvas.cljc:47-57,73-212`. |
| `my.data/rows` | **Still pending** | Seed `test/seon/host_surface_writer_test.clj:185`; CLJS implementation `src/my/data.cljs:45-74`. |
| `my.ns/{compact!,full!,functions}` | **Still pending** | Seeds `test/seon/host_surface_writer_test.clj:190-192`; implementations `src/my/ns.cljs:50-121,208-232`. |
| `seon.agent.search/{grep,grep-graph}` | **Still pending** | Seeds `test/seon/host_surface_writer_test.clj:247-248`; implementations `src/seon/agent/search.cljs:153-157,288-300`. |
| `seon.ai/generate-code!` | **Still pending** | Seed `test/seon/host_surface_writer_test.clj:266`; implementation `src/seon/ai.cljs:739-764`. |
| Five `seon.schema/*` introspection functions | **Drifted lines; still pending** | Seeds `test/seon/host_surface_writer_test.clj:286-292`; implementations `src/seon/schema.cljc:696-716,1417-1421,1485-1489,1751-1759`. The registry still exposes only validation, registration, and definition at `src/seon/host/context.clj:754-782`. |

The cutover literal remains false at `test/seon/host_surface_writer_test.clj:23-25`; pending and excluded rows block at `:34-35,316-319,389-393`. The registry-consistency assertion would reject a pending seed that silently became served at `:368-376`.

## Updated readiness

**S0a: READY TO IMPLEMENT, NOT COMPLETE.**

Its natural owner remains the existing view acquisition/render machinery in `src/seon/agent/ctx/driver.cljs:69-104,378-404`. The implementation to move remains `src/seon/execution/runtime.cljs:79-227`, and the live feed still calls the child at `src/seon/web/datastar.cljs:1074-1104`. Retained coverage currently lives in `test/seon/execution/runtime_test.cljs:38-87,810-982`.

**S1: NOT READY** until S0a lands.

**S4: NOT READY** until all 18 census rows are served or receive owner-approved dispositions.

## Post-settle protected-path map

| Lane/boundary | Exclusive paths |
|---|---|
| **U9 S0a** | `src/seon/agent/ctx/driver.cljs`; `src/seon/web/datastar.cljs:1074-1104`; `src/seon/execution/runtime.cljs:79-227`; retained view coverage from `test/seon/execution/runtime_test.cljs`. |
| **Schedfix / R46** | `src/seon/agent/loop.cljs`; `src/seon/agent/schedule.cljs`; `src/seon/agent/turn.cljs:535-587`; `src/seon/agent/driver.cljc`; run-holding process schedule additions; `test/seon/agent/{schedule,ticker}_test.cljs`. U9 must not independently implement its old S0b while this lane owns the accepted design. |
| **U9 S0c** | `src/seon/agent/turn.cljs:413-430`; `src/seon/web/router.cljs:169-181`; `src/seon/web/serve.cljs:497-518,1817-1821`; `src/seon/render/core.cljc`; `src/seon/render.cljc`; `src/seon/warn.cljc`; corresponding turn, serve, render portability/block, schema, and warning tests. |
| **U9 S0d** | `src/seon/eval.cljs:187-239`; `src/seon/agent/turn.cljs:741-776`; `test/seon/eval/race_timeout_test.cljs`. |
| **U9 S0e** | `src/seon/eval/bootstrap_cache.cljs`; `src/seon/diffusion/worker/eval.cljs`; diffusion build/test owners. |
| **U9 S1–S3** | `src/seon/execution.cljs`; `src/seon/execution/host.cljs`; remaining `src/seon/execution/runtime.cljs`; `src/seon/host/session.clj`; `src/seon/client.cljs` execution bands; `shadow-cljs.edn`; `src/seon/launch.cljc`; execution-only portions of `script/seon/dev/{artifact,release,process,config}.clj`; every direct execution test named above. Preserve program-row and client-inventory artifacts while deleting child-artifact members. |
| **Portable analyzer regression migration** | Protect `test/seon/analyzer_info_test.cljs:163-174` until its assertion is moved beside `seon.ns.source`; do not delete it with self-host analyzer semantics. |
| **Census owners, separate from deletion** | `test/seon/host_surface_writer_test.clj`; `src/seon/host/context.clj`; `src/my/{canvas.cljc,data.cljs,ns.cljs}`; `src/seon/agent/search{.cljs,/internal.cljs}`; `src/seon/ai.cljs`; `src/seon/ai/generate_code.cljs`; `src/seon/schema.cljc`. U9 S4 consumes their resolved dispositions; it must not opportunistically port them. |

The former predfix, fixfixture, and R43 protections are settled and no longer block U9 dispatch. The new material protections are the R43-created render-resolution surface, the separate R46 schedfix ownership, expanded program-row/inventory artifact plumbing, and the portable analyzer regression.

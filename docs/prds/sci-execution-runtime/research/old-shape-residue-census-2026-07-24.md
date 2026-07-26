---
type: research
status: active
tags: [research, runtime]
---

# Old-shape residue census (2026-07-24 night)

Read-only sol audit; orchestrator-persisted from the lane final message.

ean/src/seon/src/seon/runtime/recovery.cljs:467) | **Delete/replace**: remove `:seon.execution.host/artifact-digest` recovery coupling; retain only durable claim/receipt recovery facts. |
| `child-platform-contract-section` | [ctx.cljc](/Users/sean/src/seon/src/seon/agent/ctx.cljc:759) | **Delete**: obsolete Bun/self-host/Promise teaching. |
| `platform-contract-section` child arm and child-runtime ownership comment | [ctx.cljc](/Users/sean/src/seon/src/seon/agent/ctx.cljc:794) | **Delete**: cluster JVM is the sole agent-eval contract. |
| Stale execution-child claim in `resume!` documentation | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:137) | **Delete**: pod resume no longer reconstructs an execution child/compiler. |
| Dead namespace wire name `seon.execution.runtime/eval-batch!` | [host/eval.clj](/Users/sean/src/seon/src/seon/host/eval.clj:335), [host/invoke.clj](/Users/sean/src/seon/src/seon/host/invoke.clj:143), [driver/host.clj](/Users/sean/src/seon/src/seon/agent/driver/host.clj:243) | **Delete/rehome**: promote the live wire constant under the surviving host-session protocol; do not retain a symbol naming a deleted namespace. |
| `seon.eval.bootstrap-cache` namespace | [bootstrap_cache.cljs](/Users/sean/src/seon/src/seon/eval/bootstrap_cache.cljs:1) | **Move**: it is live only for Diffusion worker self-host compilation, but remains U9 S0e namespace residue; relocate under `seon.diffusion`. |

Census: **not run**. The focused census invocation could not create Clojure’s classpath cache under the read-only sandbox; the cutover literal remains `false` at [host_surface_writer_test.clj](/Users/sean/src/seon/test/seon/host_surface_writer_test.clj:23). No invented numbers.

UNSWEPT: full `src/my/**` function-by-function classification, non-lifecycle capability wrappers, and any agent-spawn/scheduling surface not directly bound by `seon.host.context`; deletion should audit these next.
tokens used
477,712
HEAD `994f7146e78c`; in-flight R53 edits excluded.

## C — old-shape runtime semantics (5)

| Function | Evidence | Target shape |
|---|---|---|
| `seon.agent.lifecycle/wait` | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:328) | **Value**: return `{:seon.agent.lifecycle/disposition :wait …}`; run-holding process performs the fenced `:waited` close. |
| `seon.agent.lifecycle/complete` | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:500) | **Value**: return terminal `{… :completed :result …}`; run-holding process delivers transcript/result and closes turn/run. |
| `seon.agent.lifecycle/pause` | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:511) | **Value**: return a pause request; driver validates custody/authority and commits the pause facts. |
| `seon.agent.lifecycle/resume` | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:541) | **Value**: return a resume request; driver owns the durable transition and rescheduling. |
| `seon.agent.lifecycle/terminate` | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:612) | **Value**: return a terminate request; driver validates management/root rules and atomically closes/releases custody. |

### D — child-era residue

| Residue | Evidence | Disposition |
|---|---|---|
| `execution-child-retired?` / `execution-child-evidence` | [turn.cljs](/Users/sean/src/seon/src/seon/agent/turn.cljs:374) | **Delete**: dead child-failure classification and its special rethrow path. |
| Child-retired error branch | [turn.cljs](/Users/sean/src/seon/src/seon/agent/turn.cljs:676) | **Delete**: no surviving producer should emit `:seon.execution/child-retired?`. |
| `evidence-projection` | [recovery.cljs](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:260) | **Fact**: replace child PID/exit/signal/stdout artifact evidence with process-loss evidence committed by recovery. |
| `capture-evidence!` / child-artifact recovery matching | [recovery.cljs](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:302), [recovery.cljs](/Users/sean/src/seon/src/seon/runtime/recovery.cljs:467) | **Delete/replace**: remove `:seon.execution.host/artifact-digest` recovery coupling; retain only durable claim/receipt recovery facts. |
| `child-platform-contract-section` | [ctx.cljc](/Users/sean/src/seon/src/seon/agent/ctx.cljc:759) | **Delete**: obsolete Bun/self-host/Promise teaching. |
| `platform-contract-section` child arm and child-runtime ownership comment | [ctx.cljc](/Users/sean/src/seon/src/seon/agent/ctx.cljc:794) | **Delete**: cluster JVM is the sole agent-eval contract. |
| Stale execution-child claim in `resume!` documentation | [lifecycle.cljc](/Users/sean/src/seon/src/seon/agent/lifecycle.cljc:137) | **Delete**: pod resume no longer reconstructs an execution child/compiler. |
| Dead namespace wire name `seon.execution.runtime/eval-batch!` | [host/eval.clj](/Users/sean/src/seon/src/seon/host/eval.clj:335), [host/invoke.clj](/Users/sean/src/seon/src/seon/host/invoke.clj:143), [driver/host.clj](/Users/sean/src/seon/src/seon/agent/driver/host.clj:243) | **Delete/rehome**: promote the live wire constant under the surviving host-session protocol; do not retain a symbol naming a deleted namespace. |
| `seon.eval.bootstrap-cache` namespace | [bootstrap_cache.cljs](/Users/sean/src/seon/src/seon/eval/bootstrap_cache.cljs:1) | **Move**: it is live only for Diffusion worker self-host compilation, but remains U9 S0e namespace residue; relocate under `seon.diffusion`. |

Census: **not run**. The focused census invocation could not create Clojure’s classpath cache under the read-only sandbox; the cutover literal remains `false` at [host_surface_writer_test.clj](/Users/sean/src/seon/test/seon/host_surface_writer_test.clj:23). No invented numbers.

UNSWEPT: full `src/my/**` function-by-function classification, non-lifecycle capability wrappers, and any agent-spawn/scheduling surface not directly bound by `seon.host.context`; deletion should audit these next.

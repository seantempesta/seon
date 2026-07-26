---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Process holding the run LLM transport path audit (2026-07-24)

## Scope

This audit answers one narrow question: did the eleven live
`:provider-error` receipts exercise the long-lived cluster JVM's
`java.net.http` leaf, and what differs from the fresh JVM leaf that succeeded?

The source, tests, issues, roadmaps, and running cluster are outside this
lane's ownership. This report is the only edited path.

## Verdict

**The preserved evidence directly proves that the six failures whose run
the process was captured were executed by the Bun pod, not by the JVM
run-holding process.** The claimed runs record process PID `35849`; the contemporaneous
operator status identifies `35849` as the pod workload and `35766` as the host
JVM. The portable `:seon.agent.run/process` is self-derived from the current process
PID. The pod advertises both render and LLM capabilities, so after it renders a
turn it retains the same claim and executes `:open-attempt`/`:settle-attempt`
through the OpenAI Node SDK. The first five terminal receipts no longer carry
the retracted run-holding process, so calling all eleven pod-owned is a strong inference
from the same process generation and phase mechanics, not direct receipt
evidence.

Consequently, the fresh JVM success is not a long-lived-versus-fresh
`java.net.http` comparison. It compares two different native leaves:

- failing evidence: long-lived Bun pod → `openai` Node SDK → native fetch; and
- successful evidence: fresh JVM → `seon.ai.http/complete` →
  `java.net.http.HttpClient`.

No change to the process-shared JVM `HttpClient`, JVM credential lookup, JVM
request builder, keep-alive behavior, or JVM watchdog can be justified from
these receipts. The accepted JVM gate must first produce a run whose
persisted process PID is the host workload PID.

## Integration disposition

The owner correction is smaller than the audit's original pod-death probe.
The pod's LLM capability and attempt dispatch arms are superseded now that the
JVM leaf exists. Removing them makes the existing portable eligibility check
release the held run after `:rendered`; the cluster JVM then acquires the
LLM/eval phases, and the pod reacquires publication. No second transport,
routing table, or forced process death is required.

The isolated proof stopped before a provider call because the current named
cluster operator reconciles only its target pod, not its target cluster JVM.
That separate prerequisite is tracked by
[[../../../seon/issues/named-cluster-open-does-not-reconcile-jvm-host]] and the
executed transcript in `tmp/orchestrator/claimantllm-gate.log`.

This does not weaken the diagnostic issue. Persisting a bounded flat failure
cause remains necessary, and it is exactly what exposed the path-identity
ambiguity. It changes the next root-cause boundary: prove JVM custody before
diagnosing JVM transport state.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding used here |
|---|---|---|
| Seon live source under test | reply-policy fix `a88e11505` plus UDS ordering fix `0b8ad3537`, as recorded in `tmp/orchestrator/redrive2-gate.log:28-30` | Preserved live run-holding process/process evidence |
| Seon source audit baseline | branch `codex/runtime-reliability-refactor`; audit began from `404bd02be0ff6f9d0de28166ade76ed304a56b86` while concurrent lanes later modified diagnostic owners | `src/seon/agent/driver.cljc`, `driver/pod.cljs`, `driver/host.clj`, `host.clj`, `agent/turn.cljs`, `agent/turn/llm.cljc`, `ai/http.clj`, `ai/openai_compat.cljs`, and portable provider cores |
| Portable claim driver | latest owning commit `411627db8c50c7e665cebf09166e25eba71c036c` for `driver.cljc`; pod serialization commit `433a30b5f2c2f1c040d40d5104a7e029f3667d22` | Process-derived `:seon.agent.run/process`, eligibility, held-epoch continuation, pod leaf capabilities |
| cluster JVM transport | U6b implementation commit `200e847e9`; current `ai/http.clj` owning history includes descriptor-row change `e88057afd915cea876f04480b76806e3c83f4592` | One process-shared JDK client; call-time credential lookup; new request per call |
| OpenAI Node SDK | `6.42.0`; `reference-code/openai-node` at tag `v6.42.0`, SHA `6f849f4ff24f70167bf82d37c8c83e3f8b1c5472`; `bun.lock:236` selects the same release | The actual native leaf used by the recorded pod run-holding process |
| OpenJDK | running `26.0.1` | `java.net.http` implementation used only by the successful fresh JVM falsifier and by a future host-PID run-holding process proof |
| SCI | `reference-code/sci` at `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | Included because the cluster JVM shares the host process and virtual-thread driver, but SCI is not on the LLM HTTP call itself |
| Accepted LLM leaf design | [[llm-http-io-design-2026-07-23]] | Section 6 states the exact L4(b) gate: kill the pod before the LLM phase, then prove the host claims and calls through `java.net.http` |
| U9 deletion design | [[u9-deletion-plan-2026-07-23]] | Its L9 inventory explicitly says the pod remains the interim LLM run-holding process while the cluster JVM also has an LLM leaf |

The source checkout has the exact OpenAI SDK reference source. It does not
have a maintained OpenJDK source checkout under `reference-code/`; the local
runtime supplies OpenJDK 26.0.1. No JDK implementation claim is needed for the
decisive path-identity finding.

## Decisive evidence

### 1. The run-holding process ID is the executing process PID

`src/seon/agent/driver.cljc:29-38` defines one stable process identity as:

```clojure
(str (.pid (java.lang.ProcessHandle/current)) "@" process-start)

```

with the CLJS arm using `process.pid`. The row does not say “cluster JVM”;
its PID must be resolved against the operator's process records.

### 2. The recorded PID is the pod

The contemporaneous status in
`tmp/orchestrator/redrive2-stdout.log:3721-3727` says:

```text
host workload-pid=35766
pod  workload-pid=35849

```

The batch run persisted run-holding process
`35849@2026-07-24T05:21:05.189Z`
(`tmp/orchestrator/redrive2-gate.log:163-168`), and the tiny third run
persisted the same run-holding process (`:231-236`). Those six batch attempts are enough
to identify the path directly. The first five receipts lack a terminal
run-holding process projection. The same still-live generation and the held-claim
mechanics below make pod ownership the supported inference, but the report
does not count that as an independently persisted process identity.

The operator process record independently names the same relationship:
`tmp/seon-operator/processes/pod.edn` records pod workload PID `35849` and Bun
argv `reference-code/bun/build/release/bun out/client/main.js`.

### 3. Claim retention makes the pod path structural

The portable loop authorizes:

- render at `:unstarted`;
- LLM at `:rendered` or `:attempt-open`;
- eval at `:reply-ready` or `:evaling`; and
- publish at `:evaled`

(`src/seon/agent/loop/core.cljc:56-77`).

The pod leaf advertises render, LLM, and publish
(`src/seon/agent/driver/pod.cljs:60-67`). The claim driver executes one step,
re-acquires the resulting immutable database value, and recurs with the same
held claim epoch while the current leaf remains eligible
(`src/seon/agent/driver.cljc:371-419`). Therefore:

```text
pod claims :unstarted
  → pod renders
  → cursor is :rendered
  → same pod leaf is still LLM-eligible
  → pod opens and settles the provider attempt
  → cursor is :reply-ready
  → pod becomes ineligible and releases to the JVM eval run-holding process

```

The JVM host also advertises LLM when its HTTP and blob leaves exist
(`src/seon/agent/driver/host.clj:60-66`), but it cannot take custody away from
the still-eligible pod that already holds the epoch.

### 4. The actual failed native leaf is the Node SDK

The pod's `:open-attempt` and `:settle-attempt` cases call
`turn/llm-phase!` with `ai.dispatch/llm-fn`
(`src/seon/agent/driver/pod.cljs:32-51`). `pod-transport!` wraps that call in an
`AbortController` and the per-attempt Promise race
(`src/seon/agent/turn.cljs:735-787`).

For DeepSeek, dispatch selects `seon.ai.openai-compat/agent-adapter`
(`src/seon/ai/openai_compat.cljs:534-555`). That leaf:

- reads the credential at call time (`:124-145`);
- constructs a new SDK client per attempt with `maxRetries 0`
  (`:271-280`, `:436-452`);
- calls SDK `.create` or `.stream` (`:453-490`); and
- normalizes the SDK error to ordinary data (`:491-496`).

The SDK may reuse its process-native fetch connection pool, but this audit has
no bounded cause proving that such reuse failed. It only establishes that
Node's transport, not JDK `HttpClient`, owned the recorded attempts.

### 5. The JVM fresh and resident code paths

`src/seon/host.clj:324-337` installs the exact var
`seon.ai.http/complete` in the long-lived host map. The host driver calls it
through one deadline wrapper (`src/seon/agent/driver/host.clj:98-125`,
`:454-480`). A true resident-host request therefore differs from the fresh JVM
command only in:

- process lifetime and the process-shared `HttpClient`;
- a virtual driver thread instead of the command's main thread;
- the run-holding process watchdog interrupt wrapper; and
- database-derived request/config values instead of a manually assembled
  request value.

It does **not** differ in provider core, request builder, credential rule, or
JVM native leaf.

The JVM leaf reads environment values per call
(`src/seon/ai/http.clj:18-20`, `:50-57`), creates a new `HttpRequest` and body
publisher per call (`:59-73`), and caches only an `HttpClient` keyed by connect
timeout (`:25`, `:33-48`). Credentials and authorization headers are not
stored on the client. The portable OpenAI-compatible core supplies the same
endpoint, credential candidates, wire body, request timeout, connect timeout,
and response bound on every invocation
(`src/seon/ai/openai_compat/core.cljc:189-230`).

During this audit, read-only process inspection found a nonempty
`DEEPSEEK_API_KEY` of the same length and byte equality as the shell value in
both preserved processes, without printing secret bytes:

```text
host key=set length=35 equality=matches-shell
pod  key=set length=35 equality=matches-shell

```

That falsifies absent or divergent process credentials for both paths.

## Ranked hypotheses and shortest falsifiers

| Rank | Hypothesis | Current judgment | Shortest falsifier |
|---|---|---|---|
| 1 | The observed failures prove a long-lived JVM transport fault | **Falsified.** Both runs with captured claimant identity name the Bun PID; none names the JVM PID. | Join `:seon.agent.run/claimant` PID to contemporaneous operator workload PIDs. Already decisive. |
| 2 | The pod retained the turn and used the Node SDK | **Proven by data and source.** | Follow render → `:rendered` eligibility under the same held epoch; compare receipt process PID with pod PID. |
| 3 | A true resident JVM call fails because the process-shared client's connect-timeout differs from its first request | Plausible only for a future host-PID failure. `process-client` has an immediate explicit error branch. | In the actual host process, capture the restored error message; alternatively invoke the client twice at two resolved connect timeouts in one focused JVM test. |
| 4 | A true resident JVM call fails because a pooled provider connection went stale | Possible in principle, unsupported by current evidence. | In one JVM process: successful request, provider-idle interval, second request through the same client; retain concrete exception class/message. Do not infer this from pod receipts. |
| 5 | JVM auth was captured when the client was constructed | **Falsified by source.** The client contains no credential; env and header are per request. | Call the same process with dynamically supplied credential values and inspect server authorization in the existing real-socket test seam. |
| 6 | JVM request/body objects were reused | **Falsified by source.** A new builder/body publisher is made per call. | Existing local server can record distinct bodies across consecutive calls. |
| 7 | The cluster JVM watchdog or virtual-thread interrupt state caused an immediate failure | Low probability after a true host-PID failure; impossible as explanation for the pod receipts. | Record `Thread.currentThread().isInterrupted()` immediately before `.send` and retain the concrete exception class under a host-PID claim. Each claimed run already uses a fresh virtual thread. |
| 8 | The pod's Node fetch/SDK transport is the underlying provider failure | This is the only native transport compatible with the recorded run-holding process, but the exact subcause is still unproven because receipts dropped it. | Repeat one pod-owned attempt after diagnostic restoration and query its bounded message/classification, or inspect the normalized adapter error before terminal projection. |

## Recommended probe and acceptance boundary

After the named-cluster run-holding process lifecycle is repaired, use the ordinary
capability handoff rather than another standalone JVM command:

1. In the isolated `claimantllm` cluster, record the host and pod workload
   PIDs.
2. Create a real run and require the pod to commit the prompt and
   `:rendered` cursor, then cleanly release its claim because it no longer
   advertises LLM capability.
3. Require the attempt-owning run process PID to
   equal the recorded host workload PID.
4. Query the restored flat cause fields if the attempt fails.
5. Count success only when that host-PID run-holding process writes a successful attempt
   receipt with request/response identity, persists the reply blob, advances
   into eval receipts, and produces the user reply; require the pod run-holding process
   to reacquire and publish afterward.

If step 3 does not prove host custody, stop: the run is not evidence about
`java.net.http`. If it proves host custody and fails, the now-visible message
selects the next smallest JVM falsifier:

- shared-client timeout mismatch → inspect the first/current configured
  connect timeout;
- `ConnectException`/`IOException` → retain class/message and test one second
  call in the same host;
- HTTP status → inspect bounded status/body;
- invalid response → inspect bounded response text and decode cause; or
- watchdog timeout → inspect deadline and interrupt classification.

The final ALIVE claim must name all three identities together: cluster,
host workload PID, and persisted run run-holding process. “Process holding the run” without the process
join is not sufficient evidence while the pod remains an interim LLM run-holding process.

## Test gap

`test/seon/ai/http_test.clj` proves JVM request bytes, authorization, batch and
stream parsing, progress isolation, and watchdog interruption against fresh
local servers (`:77-213`). It does not prove:

- a real run was owned by the host PID;
- consecutive provider calls through the resident host's one client;
- idle connection recovery;
- changed connect-timeout behavior through a durable receipt; or
- cross-process attribution through `:seon.agent.run/process`.

The smallest durable regression for this failure class is therefore not one
more leaf-only success. It is an integrated run-holding process test/proof that records
the process identity owning the attempt and rejects a pod PID when the gate is
specifically asserting the JVM transport.

## Documentation correction

The active program sentence at
`program-synthesis-2026-07-21.md:1148-1154` and the issue's
“live cluster JVM” wording conflate a generic run run-holding process with the JVM host.
Once the diagnostic implementation is integrated, their evidence should be
corrected to say:

- six attempts are directly tied to a long-lived **pod run-holding process** and the
  other five follow the same phase mechanics without retained terminal
  `:seon.agent.run/process`;
- the fresh JVM leaf succeeded;
- the underlying pod failure still needs its retained cause if it remains
  relevant; and
- the cluster JVM transport requires its own host-PID live gate.

That correction should not close the diagnostic-loss issue prematurely. The
loss exists on both native leaves at the durable attempt projection and is a
separate observability defect.

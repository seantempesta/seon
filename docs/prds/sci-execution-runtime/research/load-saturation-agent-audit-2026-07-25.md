---
type: research
status: active
tags: [research, agent, runtime]
---

# Real-agent load and end-to-end timing source audit

## Result

The production real-agent door is `POST /agents/run`. It commits an ordinary
human message, wakes the database-interest-driven cluster JVM driver, waits
for database-derived settlement, and returns durable run/turn/eval evidence.
It is the correct path for the requested 1, 5, 10, 25... concurrency climb.

Two current source facts prevent an honest execution from this audit lane:

1. The requested disposable named cluster is not yet a complete isolated
   runtime. `bin/seon cluster open <name>` reconciles only its pod, not a
   target-owned JVM driver or web-render process. This is the still-open
   blocker
   [[../../../seon/issues/named-cluster-open-does-not-reconcile-jvm-host]].
   A paid call through such a cluster would not exercise the named cluster's
   JVM driver.
2. The current driver persists SCI duration, but it does not persist model-call
   duration, individual transaction duration, context derivation duration, or
   a distinct publish duration. The database can bracket phases with
   transaction instants, but it cannot yield the requested exact end-to-end
   waterfall after the fact. Calling those brackets exact phase durations
   would be false.

No provider call was made in this audit. Therefore this note contains no new
latency, throughput, memory, or cost measurements. It defines the shortest
supported experiment and the boundaries of what that experiment can prove.

## Dependency ledger

| Dependency or mechanism | Revision or version | Maintained source | First-party owner or use |
|---|---|---|---|
| Seon production driver | `28d997e29fcc27685f79788864da2c58be13ce39` | `src/seon/agent/driver.clj` | `src/seon/host.clj:339-343` installs the driver on the cluster JVM |
| Datahike | `caf526850084a9d5846ccd9ea34251fe411e0d6b` | `reference-code/datahike/src/datahike/writer.cljc` | `src/seon/db/writer.clj`; all measurement reads remain behind `seon.db` |
| SCI | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `reference-code/sci/src/sci/core.cljc`; `reference-code/sci/doc/interrupt.md` | `src/seon/sci/eval.clj:81-138` |
| LLM HTTP transport | JDK `26.0.1` `java.net.http.HttpClient` | OpenJDK runtime source | `src/seon/ai/http.clj:107-120` performs the blocking batch `.send` |
| Provider wire reference | `litellm-clj` `14bcdd949c0207d6c4988a3db887a1a7fa1c5522` | `reference-code/litellm-clj/` | Seon's actual production request is its own OpenAI-compatible leaf, not a runtime SDK |
| Production HTTP door | same Seon revision | `src/seon/web/serve.cljs:1499-1600`; `src/seon/web/router.cljs:278-282` | `POST /agents/run` |
| Typed evidence door | same Seon revision | `src/seon/web/serve.cljs:854-919` | loopback-only `POST /_seon/operator/product-evidence` |
| Named-cluster lifecycle | same Seon revision | `script/seon/dev/cluster.clj:233-258`, `318-333` | open and close currently select only `process/pod-id` |

The JDK, heap, flags, artifact digest, cluster, provider/model configuration,
concurrency, machine load, and other running Seon processes must be copied into
every result row. `config/system.edn:184-188` currently declares a 4096 MiB JVM
heap, while `script/seon/dev/process.clj:463-489` shows that the operator places
that value in the actual `-Xmx` argument. The process record or live argv, not
the manifest alone, is the measurement authority.

## Current production path

`driver/start!` subscribes to human-message and run-lease attributes, scans for
pending human messages, and starts one virtual thread per pending message
(`src/seon/agent/driver.clj:495-532`). That virtual thread:

1. commits the run allocation;
2. commits the turn allocation;
3. performs one synchronous provider request;
4. parses the returned Clojure forms;
5. commits the durable form plan;
6. for each form, commits a running eval receipt, evaluates the form, then
   commits the terminal receipt and admitted value.

For the one-form completion prompt below, the current source therefore has six
transactions inside `/agents/run`'s `elapsed_ms` window: human message, run,
turn, plan, running eval, and terminal eval plus lifecycle completion and user
reply. A freshly minted agent adds a birth transaction before that timer.
This is a source-derived count, not a measured duration. It is not the earlier
12-transaction turn path.

The driver passes the human message string directly as `:seon.ai/ctx` and adds
one fixed system prompt (`src/seon/agent/driver.clj:398-419`). It does not run
the earlier context-rendering pipeline. There is also no separate publish
phase: completion and the user reply are admitted in the terminal eval
transaction (`src/seon/agent/driver.clj:210-262`). Any breakdown must record
those facts rather than assigning invented nonzero context or publish times.

The model call is a blocking JDK `HttpClient.send` on the message's virtual
thread (`src/seon/ai/http.clj:107-120`). The driver starts no retry loop and
writes no `:seon.agent.turn/llm-attempts` row
(`src/seon/agent/driver.clj:440-493`). Consequently the response's
`model_transport_evidence` is expected to be absent on this path even when the
provider call succeeds. Absence is an instrumentation gap, not evidence that
no model call happened.

SCI has ten permits by default (`src/seon/host.clj:62`, `333-337`). Its recorded
duration begins only after permit acquisition; semaphore wait is returned in
the in-process record but the driver does not persist it
(`src/seon/sci/eval.clj:81-138`,
`src/seon/agent/driver.clj:160-173`). At concurrency above ten, persisted SCI
duration therefore excludes any SCI-queue delay.

## Shortest cost-minimizing load climb

Do not run this section against a named cluster until the open issue above is
closed and status proves that the named host PID owns the run. Until then, the
only complete source-checkout topology is the coordinated default cluster.
Using it requires owner coordination because it is shared state, not a
disposable measurement cluster.

Once a complete disposable cluster exists:

1. Start it with the supported operator commands:

   ```bash
   bin/seon cluster apply load-saturation
   bin/seon cluster open load-saturation
   bin/seon cluster status load-saturation --edn

   ```

2. Refuse the run unless status identifies current, ready, target-owned host,
   pod, and web-render processes, and the host's actual command line records
   JDK, `-Xmx`, CDS/AOT flags, and artifact digest.
3. Create idle agents without a message through `POST /agents`. This costs no
   model call:

   ```bash
   mkdir -p tmp/load-saturation
   curl -fsS -X POST \
     -H 'Content-Type: application/x-www-form-urlencoded' \
     --data-urlencode 'purpose=Real-agent load saturation probe.' \
     "$LOAD_SATURATION_URL/agents"

   ```

4. Before delivering work, use cluster-qualified `eval_cljs` once to transact
   the cost-bounded execution configuration onto all created agents:

   ```clojure
   (seon.db/transact!
    {:seon.db/tx-data
     [{:seon.agent/id "<AGENT-ID>"
       :seon.ai/agent-provider :deepseek
       :seon.ai/agent-model "deepseek-v4-flash"
       :seon.ai/agent-thinking "false"
       :seon.ai/agent-max-tokens 64
       :seon.ai/agent-max-retries 0
       :seon.ai/wire-stream? false
       :seon.ai/reply-evaluation :batch}]})

   ```

   `:execution` is the configured cost-minimizing candidate
   (`config/system.edn:434-442`), but this checkout does not establish provider
   pricing. Record the current price separately before calling it. The current
   driver ignores the retries field and makes exactly one call; the zero value
   remains useful committed proof of intent.
5. Verify the exact committed model fields with the typed evidence door before
   the first paid request:

   ```bash
   jq -n \
     --arg aid "$LOAD_SATURATION_AGENT_ID" \
     --arg q '[:find ?provider ?model ?thinking ?tokens ?retries ?stream ?evaluation
               :in $ ?aid
               :where
               [?agent :seon.agent/id ?aid]
               [?agent :seon.ai/agent-provider ?provider]
               [?agent :seon.ai/agent-model ?model]
               [?agent :seon.ai/agent-thinking ?thinking]
               [?agent :seon.ai/agent-max-tokens ?tokens]
               [?agent :seon.ai/agent-max-retries ?retries]
               [?agent :seon.ai/wire-stream? ?stream]
               [?agent :seon.ai/reply-evaluation ?evaluation]]' \
     '{"seon.db/query":$q,"seon.db/args":[$aid]}' |
     curl -fsS -X POST \
       -H 'Content-Type: application/json' \
       --data-binary @- \
       "$LOAD_SATURATION_URL/_seon/operator/product-evidence" |
     jq .

   ```

   The expected row is
   `[":deepseek","deepseek-v4-flash","false",64,0,false,":batch"]`.
6. Use one deterministic, one-form prompt:

   ```text
   Return exactly this Clojure form and no prose:
   (seon.agent.lifecycle/complete "LOAD_OK")

   ```

7. Drive rungs 1, 5, 10, and 25 with one request per agent and record the
   request-start spread. `/agents/run` accepts JSON
   `{"input": string, "timeout_ms": integer?, "agent_id": string?}`. Put one
   selected agent ID per line in `tmp/load-saturation/agent-ids.txt`, then run:

   ```bash
   export LOAD_SATURATION_N=5
   export LOAD_SATURATION_PROMPT='Return exactly this Clojure form and no prose: (seon.agent.lifecycle/complete "LOAD_OK")'
   mkdir -p "tmp/load-saturation/n-$LOAD_SATURATION_N"
   head -n "$LOAD_SATURATION_N" tmp/load-saturation/agent-ids.txt |
     xargs -P "$LOAD_SATURATION_N" -I '{}' \
       sh -c '
         agent_id="$1"
         output_dir="$2"
         endpoint="$3"
         prompt="$4"
         jq -n --arg input "$prompt" --arg aid "$agent_id" \
           "{input:\$input,timeout_ms:120000,agent_id:\$aid}" |
           curl -fsS -X POST \
             -H "Content-Type: application/json" \
             --data-binary @- \
             -o "$output_dir/$agent_id.json" \
             -w "http_code=%{http_code} time_total_s=%{time_total}\n" \
             "$endpoint/agents/run" \
             > "$output_dir/$agent_id.curl"
       ' sh '{}' "tmp/load-saturation/n-$LOAD_SATURATION_N" \
         "$LOAD_SATURATION_URL" "$LOAD_SATURATION_PROMPT"

   ```

   `xargs -P` supplies concurrency but not a nanosecond-precise simultaneous
   start. Preserve the per-request start spread if it matters at the observed
   knee. The response JSON and curl timing are separate so `elapsed_ms` can be
   compared with full HTTP wall time.
8. After every rung, inspect provider status, terminal run count, failures,
   host logs, RSS, heap, GC, platform/carrier threads, virtual-thread evidence,
   and database commit throughput. Do not launch the next rung until the first
   failing resource is identified.

The 1, 5, 10, 25 sequence costs 41 provider calls if every rung runs. Stop
after the first provider rate or cost limit. A provider quota, 429, or budget
ceiling is the reached experimental ceiling, not a Seon saturation result.
Do not spend through it.

## What `/agents/run` measures

The returned `elapsed_ms` begins after optional new-agent birth and immediately
before the human-message transaction. It stops when the derived settlement
condition is observed (`src/seon/web/serve.cljs:1522-1550`). It includes:

- message commit and wake delivery;
- run and turn allocation transactions;
- the model call;
- reply parsing and plan commit;
- eval-receipt commits and SCI evaluation;
- terminal lifecycle and reply commit; and
- reactive settlement observation.

It excludes:

- fresh agent birth;
- request-body parsing before `run-agent-task!`;
- final database/evidence projection after settlement;
- JSON response encoding and loopback transfer; and
- client scheduling before the request reaches the server.

Raw curl wall time includes those HTTP/handler edges. The difference between
curl wall time and `elapsed_ms` is an aggregate edge residual, not a single
named phase.

The response's `eval_evidence` also omits `:seon.eval/duration-ms`
(`src/seon/web/serve.cljs:1299-1349`). Retrieve it through the typed evidence
door:

```clojure
[:find ?run-id ?turn-id ?eval-id ?ordinal ?duration-ms ?status
 :in $ ?aid
 :where
 [?agent :seon.agent/id ?aid]
 [?run :seon.agent.run/agent ?agent]
 [?run :seon.agent.run/id ?run-id]
 [?turn :seon.agent.turn/run ?run]
 [?turn :seon.agent.turn/id ?turn-id]
 [?turn :seon.agent.turn/evals ?eval]
 [?eval :seon.eval/id ?eval-id]
 [?eval :seon.eval/ordinal ?ordinal]
 [?eval :seon.eval/duration-ms ?duration-ms]
 [?eval :seon.eval/status ?status]]

```

POST the query as an EDN string in `seon.db/query` and the agent ID in
`seon.db/args` to `/_seon/operator/product-evidence`
(`src/seon/web/serve.cljs:896-919`).

The following current-database query yields the transaction instants that
bracket the model call and eval. These are event timestamps, not transaction
call durations:

```clojure
[:find ?run-id ?turn-id ?eval-id
       ?run-at ?turn-at ?plan-at ?eval-start-at ?eval-end-at ?duration-ms
 :in $ ?aid
 :where
 [?agent :seon.agent/id ?aid]
 [?run :seon.agent.run/agent ?agent]
 [?run :seon.agent.run/id ?run-id ?run-tx]
 [?run-tx :db/txInstant ?run-at]
 [?turn :seon.agent.turn/run ?run]
 [?turn :seon.agent.turn/id ?turn-id ?turn-tx]
 [?turn-tx :db/txInstant ?turn-at]
 [?run :seon.agent.run/plan-digest _ ?plan-tx]
 [?plan-tx :db/txInstant ?plan-at]
 [?turn :seon.agent.turn/evals ?eval]
 [?eval :seon.eval/id ?eval-id ?eval-start-tx]
 [?eval-start-tx :db/txInstant ?eval-start-at]
 [?eval :seon.eval/duration-ms ?duration-ms ?eval-end-tx]
 [?eval-end-tx :db/txInstant ?eval-end-at]]

```

`turn-at` to `plan-at` brackets turn-open commit, provider request, reply
parsing, and plan commit. It is not exact model latency. `eval-start-at` to
`eval-end-at` brackets the terminal transaction around the persisted SCI
duration. It is not exact aggregate commit time.

To inspect status transitions, run the evidence door with
`"seon.db/history?": true` and query the five-position datom including
transaction and added/retracted flag. Do not infer transition order from the
current cardinality-one value alone.

## Instrumentation needed for an exact waterfall

The exact requested breakdown requires monotonic timestamps around these
existing calls in the JVM driver:

- `llm-transport!` at `src/seon/agent/driver.clj:452-453`;
- every `transact!` or `allocate!` call;
- reply parsing;
- SCI permit wait and SCI evaluation; and
- settlement notification at the pod boundary.

The measurement must distinguish transaction queue wait from commit execution.
It must either persist ordinary bounded timing facts with the turn or emit one
correlated JFR/event record that is captured during the run. It must not add a
second runtime path merely for benchmarking. Without this instrumentation, a
valid report can give total wall time, persisted SCI duration, and phase
brackets only. It cannot close the exact end-to-end waterfall clause.

## Idle-agent and memory proof

An idle agent is database data only. `seon.agent/start!` commits birth and
returns it idle (`src/seon/agent.cljs:997-1019`). The JVM driver creates a
virtual thread only for a pending human message
(`src/seon/agent/driver.clj:495-511`). It creates a fresh SCI fork per evaluated
form (`src/seon/sci/eval.clj:100-113`); the driver does not retain one per
agent. Therefore source predicts zero idle-agent threads and zero retained SCI
contexts. Source does not establish zero bytes: each idle agent still has
database datoms and indexes.

Measure, do not merely repeat that prediction:

- record host RSS from `ps -o rss= -p <host-pid>` in KiB, preserving the native
  unit before conversion;
- record heap occupancy and committed/max heap with `jcmd <host-pid>
  GC.heap_info`;
- preserve a class histogram at 0 and each idle-agent count;
- preserve a JDK JSON thread dump or JFR thread evidence before and after
  creating idle agents; and
- repeat at 0, 1, 5, 10, 25 active agents, sampling before delivery, peak
  in-flight, and after settlement.

Every row must state the actual `-Xmx`, JDK, GC, CDS/AOT flags, agent count,
active request count, provider/model, cluster, artifact digest, sample phase,
and other running processes. RSS is process memory, not heap. Whole-megabyte
rounding is an artifact for sub-megabyte deltas; retain KiB and byte-level heap
values.

## Stop conditions and first-failure attribution

At each rung classify the first break by direct evidence:

- provider: HTTP status, retry-after, or provider timeout;
- commit path: transaction latency/throughput plus the writer's single-core
  utilization;
- disk: latency/throughput and fsync evidence;
- heap or GC: occupancy, allocation rate, pause, or OOM evidence;
- SCI: permit wait versus evaluation duration;
- carrier/platform threads: runnable/blocked carriers and thread counts; or
- cost: recorded calls/tokens and the predeclared spend ceiling.

A timeout alone names no resource. A failed `/agents/run` response alone names
no resource. Stop the climb, preserve the response, database value, host log,
process samples, JFR/profile, and provider result, then name the resource from
that evidence.

## Additional source discrepancies

- `src/seon/web/serve.cljs:722` says the supervisor uses
  `bin/seon cluster create`, but the current operator exposes `cluster apply`,
  `open`, `restart`, `close`, `status`, `reset`, `restore`, and `undo`
  (`script/seon/dev/cli.clj:1289-1293`). There is no `cluster create` command.
- The old 2026-07-24 LLM live-proof runbook expects durable attempt rows, turn
  phases, and a separate publish phase. The current driver does not write
  those shapes. Reusing its terminal queries would incorrectly report an
  otherwise successful current run as absent or incomplete.

These discrepancies do not authorize a workaround. The named-cluster lifecycle
owner and current driver/evidence owner must be corrected in place.

## Not measured

This audit did not measure a provider call, a real capability call, streaming,
provider rate limits, paid tokens, context derivation, model latency, exact
transaction latency, per-agent memory, carrier pressure, virtual-thread count,
RSS, heap, GC, disk fsync, or a concurrent real-agent ceiling. It made no
performance claim.

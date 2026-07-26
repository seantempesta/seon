---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# cluster JVM LLM live-proof runbook

## Executed result

The 2026-07-24 execution stopped before a provider call. The originally
specified full-stack environment made `claimantllm` own a second default-flavor
watcher, so reset correctly failed with
`:seon.dev.process.status/containment-uncertain` while the canonical watcher
was live. The supported `bin/seon cluster open claimantllm` shared that
watcher/writer and opened a fresh database, but reconciled only the target pod.
Its immediate status was degraded: the reported host was the default workload,
`:current-spec? false`, and not ready for the target descriptor. No
claimantllm host record existed.

The target pod was stopped cleanly without a DeepSeek request. Do not execute
the paid portion of this runbook until
[[../../../seon/issues/named-cluster-open-does-not-reconcile-jvm-host]] is
closed. The preserved transcript is
`tmp/orchestrator/claimantllm-gate.log`.

## Decision and scope

This is the shortest production-path falsifier for the remaining live blocker:
one fresh agent, one batch DeepSeek request, zero retries, and two small forms
that must become durable eval receipts. It drives the ordinary message wake
path. The pod renders and publishes; the long-lived cluster JVM performs the
LLM transport and eval phases.

Use the already-running default cluster only when the orchestrator explicitly
authorizes that proof. A source edit requires an isolated full-stack cluster,
but the current named-cluster operator cannot yet reconcile its cluster JVM;
the executed result above is the blocking prerequisite. Never reset, restart,
stop, or reconfigure `default` merely to work around that missing target
member.

The ordered proof is:

1. An isolated source-frozen cluster reaches ready with its own host, pod, and
   web-render records.
2. A fresh agent is configured for `:batch` and zero retries.
3. One ordinary `/chat` message opens a run.
4. Historical claim datoms show pod render → JVM LLM/eval → pod publish
   custody, with the provider-owning run-holding process joined to the target host PID.
5. Exactly one attempt terminalizes `:success` with provider response identity.
6. The turn carries a reply blob and terminal eval receipts for the requested
   message and completion forms.
7. The run closes `:completed`, and all five processes remain ready.

Stop at the first failed predicate. A failed attempt with the restored bounded
cause is the root-cause artifact; retries and a larger task add cost but no new
localization.

## Dependency ledger

| Boundary | Selected source/version | Maintained source and demonstrated call site |
|---|---|---|
| Seon source under test | Record `git rev-parse HEAD` at gate start; checkout observed at `404bd02be0ff6f9d0de28166ade76ed304a56b86` during runbook preparation | `src/seon/agent/driver/host.clj` owns `bounded-llm-transport!`, cluster JVM capability installation, and the long-lived call; `src/seon/ai/http.clj` owns the process-shared `HttpClient`, per-call credential lookup, request creation, status classification, and batch/SSE response consumption |
| Portable attempt lifecycle | Same Seon source digest | `src/seon/agent/turn/llm.cljc` opens and terminalizes the attempt; `src/seon/agent/turn/core.cljc` supplies the run-epoch and phase CAS data; `src/seon/agent/driver.cljc` selects the next phase from durable state |
| Durable receipt graph | Same Seon source digest | `src/seon/agent/run.cljs`, `src/seon/agent/turn.cljs`, and `src/seon/eval/receipt.cljc`; the production nested selector is `src/seon/agent/driver.cljc`'s `run-selector` |
| Typed live read boundary | Same Seon source digest | `src/seon/web/serve.cljs` `product-evidence` reads one immutable database value and exposes the loopback-only `POST /_seon/operator/product-evidence`; the earlier live use is `tmp/orchestrator/redrive2-gate.log` |
| Operator isolation | Same Seon source digest | `script/seon/dev/cli.clj` `reset-cluster!` verifies that the requested name equals the explicitly configured cluster; `script/seon/dev/config.clj` derives the cluster name from `SEON_CLUSTER_DIR`; `script/seon/dev/process.clj` owns the five-process graph and generation logs |
| Datahike | `reference-code/datahike` at `caf526850084a9d5846ccd9ea34251fe411e0d6b` | Five-slot historical datoms and `?added` queries are exercised in `reference-code/datahike/test/datahike/test/query_planner_test.clj`; `reference-code/datahike/src/datahike/api/impl.cljc` owns the idempotent `history` database value |
| Proximum | git SHA `9846d3e79e1aee48474bc876d3d563d7137209c6` in `deps.edn` | Selected by the maintained `:writer` basis; no direct query or transaction API is introduced here |
| SCI | `reference-code/sci` at `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | The run-holding process's two reply forms pass through the existing guarded host eval path; this runbook adds no interpreter entry |
| JVM HTTP | OpenJDK `26.0.1` observed during preparation | `java.net.http.HttpClient`, `HttpRequest`, and `HttpResponse` are used only through `src/seon/ai/http.clj`; no standalone leaf call counts as this proof |
| Provider policy grounding | `reference-code/litellm-clj` at `14bcdd949c0207d6c4988a3db887a1a7fa1c5522` | `reference-code/litellm-clj/src/litellm/providers/deepseek.clj` grounds endpoint and DeepSeek request semantics; Seon's maintained provider row and portable core remain the production owners |

The currently active program ledger names this as the earliest unsettled
contract: the final 2026-07-24 redrive reached real provider attempts but
recorded 11/11 `:provider-error` only on the long-lived run-holding process. The identical
persisted prompt succeeded through a fresh JVM leaf. See
`program-synthesis-2026-07-21.md` and
`docs/seon/issues/jvm-claimant-provider-errors-drop-the-diagnostic-cause.md`.

## Isolation and source freeze

The environment below records the original attempted setup, not a currently
valid command sequence. It must not be rerun while a canonical default-flavor
watcher exists. Once the named-cluster lifecycle issue is closed, replace this
section with the operator-supported target invocation, start transcript capture
before reset, and do not enable shell tracing: tracing could print
credential-bearing environment values.

```bash
cd /Users/sean/src/seon

exec > >(tee -a tmp/orchestrator/claimantllm-gate.log) 2>&1

export SEON_CLUSTER_DIR="$PWD/data/clusters/claimantllm"
export SEON_PROC_DIR="$PWD/tmp/seon-operator-claimantllm"
export SEON_WRITER_PROC_DIR="$PWD/tmp/seon-operator-claimantllm"
export SEON_LOG_DIR="$PWD/logs/claimantllm"
export SEON_REQ_SOCK="$PWD/tmp/claimantllm-req.sock"
export SEON_WRITER_REPL_PORT_FILE="$PWD/tmp/claimantllm-writer.port"
export SEON_PORT=0
export SEON_PORT_FILE="$PWD/tmp/claimantllm-pod-http.port"

git status --short --branch
git rev-parse HEAD
bin/seon status

```

The final `bin/seon status` above addresses `claimantllm` because the shell
already selected that cluster. It is a preflight observation, not a default
cluster status check.

Coordinate a source freeze before the next command. `cluster reset` is
destructive only to
`/Users/sean/src/seon/data/clusters/claimantllm/db` and that cluster's generated
package manifests; the environment above gives every supervised process and
socket a `claimantllm`-specific owner.

```bash
bin/seon cluster reset claimantllm
bin/seon status --edn
bin/seon logs host --lines 120

export CLAIMANTLLM_PORT
CLAIMANTLLM_PORT="$(tr -d '\r\n' < "$SEON_PROC_DIR/web-render/http.port")"
export CLAIMANTLLM_URL="http://127.0.0.1:$CLAIMANTLLM_PORT"

curl -fsS "$CLAIMANTLLM_URL/_seon/ready"
git status --short --branch
git rev-parse HEAD

```

Do not count readiness if a build input changed between reset start and the
second source observation. The current log layout is:

- exact process records:
  `tmp/seon-operator-claimantllm/processes/{watcher,writer,host,pod,web-render}.edn`;
- current generation logs:
  `logs/claimantllm/<process>/<generation>.log`;
- supported current-log view:
  the isolated environment plus `bin/seon logs host --lines N`; and
- web port:
  `tmp/seon-operator-claimantllm/web-render/http.port`.

## Create and freeze a one-attempt agent

Create the agent through the production route. The returned body is its
database identity.

```bash
export CLAIMANTLLM_AGENT_ID
CLAIMANTLLM_AGENT_ID="$(
  curl -fsS -X POST \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode \
    'purpose=Prove the long-lived cluster JVM DeepSeek transport and durable receipts.' \
    "$CLAIMANTLLM_URL/agents"
)"
printf 'claimantllm agent: %s\n' "$CLAIMANTLLM_AGENT_ID"

```

Use the Seon MCP `eval_cljs` tool once, cluster-qualified so it cannot select
the default pod:

```clojure
;; MCP arguments:
;; agent_id = "claimantllm/root"
;; code = the form below, with <AGENT-ID> replaced by the returned id.
(seon.db/transact!
 {:seon.db/tx-data
  [{:seon.agent/id "<AGENT-ID>"
    :seon.ai/agent-max-retries 0
    :seon.ai/wire-stream? false
    :seon.ai/reply-evaluation :batch}]})

```

The MCP envelope and the nested transaction envelope must both be successful.
Do not proceed on a read error or `:seon.db/ok? false`. Prove the committed
policy through the HTTP read boundary, which also puts the evidence in the
gate transcript:

```bash
jq -n \
  --arg aid "$CLAIMANTLLM_AGENT_ID" \
  --arg q '[:find ?retries ?stream ?evaluation
            :in $ ?aid
            :where
            [?agent :seon.agent/id ?aid]
            [?agent :seon.ai/agent-max-retries ?retries]
            [?agent :seon.ai/wire-stream? ?stream]
            [?agent :seon.ai/reply-evaluation ?evaluation]]' \
  '{"seon.db/query":$q,"seon.db/args":[$aid]}' |
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$CLAIMANTLLM_URL/_seon/operator/product-evidence" |
  jq .

```

Expected result: one row `[0, false, ":batch"]`, plus database name
`claimantllm`, its basis transaction, and commit ID.

## Drive one production cluster JVM attempt

This message asks the model for two explicit forms. A successful response must
therefore advance beyond LLM transport into durable JVM eval receipts and
ordinary publication.

```bash
curl -fsS -i -X POST \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode \
  'text=CLAIMANTLLM TRANSPORT GATE. Return exactly these two Clojure forms and no prose: (seon.agent.message/user "CLAIMANTLLM_ALIVE") followed by (seon.agent.lifecycle/complete "CLAIMANTLLM_ALIVE").' \
  "$CLAIMANTLLM_URL/chat?agent=$CLAIMANTLLM_AGENT_ID"

```

Expected immediate result: HTTP 204. That response only proves wake
acceptance; it is not provider evidence.

Poll the following terminal query at a two-second cadence. It stays empty
until the run has a terminal reason, turn status and phase, and terminal
attempt outcome:

```bash
jq -n \
  --arg aid "$CLAIMANTLLM_AGENT_ID" \
  --arg q '[:find ?run-id ?run-status ?closed-reason
                   ?turn-id ?turn-status ?phase ?outcome
            :in $ ?aid
            :where
            [?agent :seon.agent/id ?aid]
            [?run :seon.agent.run/agent ?agent]
            [?run :seon.agent.run/id ?run-id]
            [?run :seon.agent.run/status ?run-status]
            [?run :seon.agent.run/closed-reason ?closed-reason]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/id ?turn-id]
            [?turn :seon.agent.turn/status ?turn-status]
            [?turn :seon.agent.turn/phase ?phase]
            [?turn :seon.agent.turn/llm-attempts ?attempt]
            [?attempt :seon.ai.attempt/outcome ?outcome]]' \
  '{"seon.db/query":$q,"seon.db/args":[$aid]}' |
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$CLAIMANTLLM_URL/_seon/operator/product-evidence" |
  jq .

```

Success is exactly one row whose semantic values are:

```clojure
[<run-id> :closed :completed <turn-id> :done :published :success]

```

Stop immediately on `:provider-error`, `:adapter-timeout`,
`:outer-timeout`, `:crashed`, `:error`, or an unavailable process. Preserve the
current host log and the complete attempt pull before doing anything else.

## Durable snapshot proof

The final nested pull intentionally uses `[*]` for attempts. That includes the
diagnostic attributes added by the fix without maintaining a second field
list.

```bash
jq -n \
  --arg aid "$CLAIMANTLLM_AGENT_ID" \
  --arg q '[:find
            (pull ?run
             [:db/id
              :seon.agent.run/id
              :seon.agent.run/trigger
              :seon.agent.run/status
              :seon.agent.run/started-at
              :seon.agent.run/last-beat-at
              :seon.agent.run/process
              :seon.agent.run/claim-epoch
              :seon.agent.run/closed-reason
              :seon.agent.run/result
              :seon.agent.run/closed-at
              {:seon.agent.turn/_run
               [:db/id
                :seon.agent.turn/id
                :seon.agent.turn/at
                :seon.agent.turn/status
                :seon.agent.turn/phase
                :seon.agent.turn/rendered-tx
                :seon.agent.turn/error
                {:seon.agent.turn/prompt-blob [:my.blob/hash]}
                {:seon.agent.turn/reply-blob [:my.blob/hash]}
                {:seon.agent.turn/llm-attempts [*]}
                {:seon.agent.turn/evals [*]}]}])
            :in $ ?aid
            :where
            [?agent :seon.agent/id ?aid]
            [?run :seon.agent.run/agent ?agent]]
  ' \
  '{"seon.db/query":$q,"seon.db/args":[$aid]}' |
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$CLAIMANTLLM_URL/_seon/operator/product-evidence" |
  jq .

```

Required current facts:

- run: `:seon.agent.run/id`, `:status :closed`,
  `:closed-reason :completed`, `:started-at`, and `:closed-at`; the process is
  normally absent after clean close because custody is retracted, while the
  terminal claim epoch reflects each cross-tier handoff;
- turn: `:seon.agent.turn/id`, `:status :done`, `:phase :published`,
  `:rendered-tx`, a prompt blob hash, and a nonempty reply blob hash;
- attempt: exactly one component row with ordinal `0`, outcome `:success`,
  provider `:deepseek`, adapter `:openai-compat`, requested model
  `deepseek-v4-pro`, endpoint
  `https://api.deepseek.com/chat/completions`, batch stream flag `false`,
  reply evaluation `:batch`, config digest, deadline, both timeout layers,
  `:seon.ai.attempt/response-status 200`, response model, and request ID;
- evals: two terminal `:done` receipts with `:seon.eval/ok? true`, stable eval
  ids, sources matching the requested message and completion forms,
  namespaces, timestamps, and result projections; and
- no `:seon.agent.turn/error`, attempt cause fields, or running/open receipt.

The diagnostics patch must additionally make a failed snapshot carry bounded,
flat cause data on its single attempt. The exact expected attributes are:

- `:seon.ai.attempt/error-message`;
- `:seon.ai.attempt/exception-class`;
- `:seon.ai.attempt/exception-message`;
- `:seon.ai.attempt/transport?`;
- `:seon.ai.attempt/timeout?`;
- `:seon.ai.attempt/error-status`;
- `:seon.ai.attempt/error-body`; and
- the already-frozen `:seon.ai.attempt/adapter-timeout-ms` and
  `:seon.ai.attempt/outer-timeout-ms`.

Presence is conditional on what the leaf observed: a transport failure must
not invent an HTTP status, while an HTTP failure must retain status and bounded
body. Their absence on an applicable `:provider-error` fails Step 1 even if a
process log contains the exception. No secret, authorization header, raw
credential, Throwable object, or nested error map may appear.

## Claim and phase history proof

Current state retracts process custody on a clean close. Use the history
database value to prove which long-lived process performed the work and which
transactions advanced the cursor.

```bash
jq -n \
  --arg aid "$CLAIMANTLLM_AGENT_ID" \
  --arg q '[:find ?run-id ?process ?epoch ?beat ?tx ?at
            :in $ ?aid
            :where
            [?agent :seon.agent/id ?aid]
            [?run :seon.agent.run/agent ?agent]
            [?run :seon.agent.run/id ?run-id]
            [?run :seon.agent.run/process ?process ?tx true]
            [?run :seon.agent.run/claim-epoch ?epoch ?tx true]
            [?run :seon.agent.run/last-beat-at ?beat ?tx true]
            [?tx :db/txInstant ?at]]' \
  '{"seon.db/query":$q,"seon.db/args":[$aid],"seon.db/history?":true}' |
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$CLAIMANTLLM_URL/_seon/operator/product-evidence" |
  jq .

```

Expected: ordered added claim rows for pod render, JVM LLM/eval, and pod
publish, normally epochs `1`, `2`, and `3`. Join the middle run-holding process's
PID/start instant to the exact target host generation, and join the first and
last to the target pod generation. A single run-holding process row or a host PID from
another cluster fails the proof.

```bash
jq -n \
  --arg aid "$CLAIMANTLLM_AGENT_ID" \
  --arg q '[:find ?run-id ?turn-id ?phase ?tx ?added ?at
            :in $ ?aid
            :where
            [?agent :seon.agent/id ?aid]
            [?run :seon.agent.run/agent ?agent]
            [?run :seon.agent.run/id ?run-id]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/id ?turn-id]
            [?turn :seon.agent.turn/phase ?phase ?tx ?added]
            [?tx :db/txInstant ?at]]' \
  '{"seon.db/query":$q,"seon.db/args":[$aid],"seon.db/history?":true}' |
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$CLAIMANTLLM_URL/_seon/operator/product-evidence" |
  jq .

```

For the success path, the added phase sequence is:

```clojure
:rendered
:attempt-open
:reply-ready
:evaling
:evaled
:published

```

Every replacement also has a retraction of the preceding cardinality-one
phase. The attempt open and terminal transitions are proven separately by the
attempt's history:

```bash
jq -n \
  --arg aid "$CLAIMANTLLM_AGENT_ID" \
  --arg q '[:find ?turn-id ?attempt-id ?ordinal ?outcome ?tx ?added ?at
            :in $ ?aid
            :where
            [?agent :seon.agent/id ?aid]
            [?run :seon.agent.run/agent ?agent]
            [?turn :seon.agent.turn/run ?run]
            [?turn :seon.agent.turn/id ?turn-id]
            [?turn :seon.agent.turn/llm-attempts ?attempt]
            [?attempt :seon.ai.attempt/id ?attempt-id]
            [?attempt :seon.ai.attempt/ordinal ?ordinal]
            [?attempt :seon.ai.attempt/outcome ?outcome ?tx ?added]
            [?tx :db/txInstant ?at]]' \
  '{"seon.db/query":$q,"seon.db/args":[$aid],"seon.db/history?":true}' |
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    "$CLAIMANTLLM_URL/_seon/operator/product-evidence" |
  jq .

```

Expected ordinal history: add `:open`, retract `:open`, add `:success`; terminal
settlement is one CAS-controlled transaction.

## Raw reply and provider-status evidence

Use `eval_cljs` with `agent_id = "claimantllm/root"` and the turn id from the
snapshot:

```clojure
(seon.agent.debug/turn {:seon.agent.turn/id "<TURN-ID>"})

```

Expected: `:seon.agent.debug/ok? true`, the same prompt and reply blob hashes
resolved to verbatim text, and a reply containing the two requested forms. The
two eval receipts prove those forms were not merely generated; they were
admitted and terminalized.

The pre-fix observability distinction was:

- `src/seon/ai/http.clj` accepts success only when
  `200 <= HttpResponse.statusCode <= 299`, so a durable `:success` plus response
  identity proves a real provider 2xx on the long-lived run-holding process; but
- the old success branch discarded the exact status code, so it could not prove
  literal HTTP 200 rather than another 2xx.

The diagnostics change closes that gap by carrying the non-secret successful
status through the transport result to
`:seon.ai.attempt/response-status`. The final snapshot must show
`:seon.ai.attempt/response-status 200`; inference from `:success` alone remains
insufficient for the user's “real 200” requirement.

## Final readiness and log capture

```bash
bin/seon status
curl -fsS "$CLAIMANTLLM_URL/_seon/ready"
bin/seon logs host --lines 240
git status --short --branch
git rev-parse HEAD

```

The gate log at `tmp/orchestrator/claimantllm-gate.log` must contain:

- source commit and the unchanged pre/post source observation;
- isolated five-process generations and ready response;
- the fresh agent id;
- the committed zero-retry batch policy;
- HTTP 204 wake acceptance;
- immutable database value (`db_name`, basis transaction, commit ID) for every
  decisive query;
- current nested run/turn/attempt/eval proof;
- claim, phase, and attempt history;
- literal provider status 200, response model, and request ID;
- reply blob hash and verbatim reply evidence;
- final process generations and readiness; and
- a plain `ALIVE` or `NOT-ALIVE` verdict.

`ALIVE` means all required facts above exist and no process generation changed
or lost readiness. Anything weaker is `NOT-ALIVE`, with the first failed
predicate and the restored flat cause copied from the attempt.

Keep the isolated cluster up until the implementing agent has archived the two
issues and a reviewer has copied any needed evidence. Then stop only this
cluster, retaining its database and logs:

```bash
bin/seon down
bin/seon status

```

Because the shell still selects `claimantllm`, both commands target only the
isolated operator coordinates.

## Failure modes and shortest responses

| Observation | Meaning | Next action |
|---|---|---|
| Reset refuses because the configured cluster is not `claimantllm` | The isolation environment was not applied to that shell | Stop. Do not substitute `default`; correct the environment and re-run the preflight |
| Build input changes during reset | The artifact digest no longer names a frozen source state | Do not count the checkpoint; coordinate a freeze and rebuild only `claimantllm` |
| Ready fails before the wake | Startup/acquisition defect, not an LLM transport result | Capture isolated process logs and stop; do not call DeepSeek |
| Policy proof is not `[0 false :batch]` | More than one paid attempt or streaming could occur | Stop before `/chat`; fix the test setup |
| HTTP 204 but no run opens | Message wake/claim defect precedes LLM transport | Capture database value and process logs; do not retry |
| Attempt is `:provider-error` with bounded cause | The desired root-cause reproduction succeeded | Stop immediately; preserve cause message/class/status/body and compare the run-holding process generation to the fresh-leaf control |
| Attempt is `:provider-error` without bounded cause | Diagnostics restoration is incomplete | Reopen Step 1; logs do not substitute for receipt data |
| Cause says shared client connect timeout changed | `process-client` retained a different timeout | Fix the one process-shared client owner or make configuration immutable at run-holding process admission; do not add another client |
| Cause is connection reuse/closed-channel/EOF | Long-lived `HttpClient` connection state is implicated | Reproduce against the same client with two sequential requests, then fix retry/reuse at `seon.ai.http`; do not create a cluster JVM-only transport |
| Cause is missing credential | Long-lived process environment/config acquisition differs | Compare the non-secret credential source/class and process generation; never print the key |
| HTTP error | Provider reached; status and bounded body decide auth, request, quota, or provider failure | Stop on 402. Otherwise fix request/config evidence at the existing owner |
| `:success` without response identity or reply blob | Transport returned, but durable settlement is incomplete | Treat as receipt/settlement failure, not alive |
| Reply blob exists but eval receipts are absent/running/error | LLM transport succeeded; the later guarded eval path failed | Preserve the success proof and move the blocker to the first failed eval receipt |
| Run closes other than `:completed` | The requested end-to-end reply was not completed | `NOT-ALIVE`; report the exact close reason and receipts |
| Any default process generation changes | Isolation was violated or another orchestrator acted concurrently | Stop and report; do not claim this isolated gate as proof about default |

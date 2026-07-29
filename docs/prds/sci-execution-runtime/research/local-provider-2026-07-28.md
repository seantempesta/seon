---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Local OpenAI-compatible provider — 2026-07-28

## Project local provider update — 2026-07-29

The project local provider is now the Ollama target descriptor row below. This
supersedes the provider selection in the historical MLX experiment later in
this note; that experiment remains useful evidence, but MLX is not the project
server and was not restarted or benchmarked by the load lane.

**Owner ruling: run one model server at a time.** Every Seon fleet shares this
one endpoint; a fleet does not launch its own Ollama or MLX server.

```clojure
{:seon.ai/endpoint
 "http://127.0.0.1:11434/v1/chat/completions"
 :seon.ai/model "qwen3.5:35b-a3b-coding-nvfp4"
 :seon.ai/max-tokens 8192
 :seon.config.ai/no-auth true
 :seon.ai/timeout-ms 300000}
```

The mixed namespaces are the target schema's actual vocabulary:
endpoint/model/budget/timeout are `:seon.ai/*`, while explicit no-auth remains
`:seon.config.ai/no-auth`. The input configuration uses
`:seon.config.ai/*`; `seon.ai/targets` performs that projection.

Ollama is `0.32.1`; the installed model digest is
`6e73b30f8f1cfa06b979c842ba222ae21dad1e55e7c6748a7d8acad46fb340c4`.
Pin both the eight execution slots and a sane context per slot before
restarting the one shared server:

```bash
launchctl setenv OLLAMA_NUM_PARALLEL 8
launchctl setenv OLLAMA_CONTEXT_LENGTH 32768
```

Then restart the Ollama menu application or the single `ollama serve` process.
Verify the inherited launch environment with:

```bash
test "$(launchctl getenv OLLAMA_NUM_PARALLEL)" = 8
test "$(launchctl getenv OLLAMA_CONTEXT_LENGTH)" = 32768
```

Ollama processes requests in parallel when memory permits and queues them in
order otherwise; `OLLAMA_MAX_QUEUE` bounds that waiting room
([Ollama concurrency FAQ](https://docs.ollama.com/faq)).

The one-command health and identity check is:

```bash
curl -fsS http://127.0.0.1:11434/api/version &&
  ollama list | grep qwen3.5:35b-a3b-coding-nvfp4
```

### Context and memory

The sustained drive accidentally exercised Ollama's VRAM-derived default:
`OLLAMA_CONTEXT_LENGTH=0` selected `num_ctx=262144` for each of eight slots.
That is capacity for 2,097,152 context tokens across the server even though
Seon's agent prompts are small. Pinning 32,768 per slot reduces that aggregate
capacity to 262,144 tokens; 16–32k per slot is ample for this workload.

Owner clarification: the approximately 28.7 GB figure is only the KV
reservation. Batch-processing a 200k-plus-token context across eight streams
also incurs model execution and activation costs; it is not “covered” by that
reservation. Do not use the 262k × 8 default as a memory forecast or as the
project setup.

### Thinking policy and output budget

Thinking stays on: local tokens are free, but excess must be observable and a
completion budget must leave room for visible content. Ollama separates
thinking from visible content in its native chat API and in compatible
streams. Its official model contract gives Qwen thinking a Boolean control;
`low`/`medium`/`high` levels are specifically documented for GPT-OSS, not
Qwen ([Ollama thinking](https://docs.ollama.com/capabilities/thinking)).
The compatible endpoint accepts `reasoning_effort`, but an empirical
`reasoning_effort=low` Qwen call still consumed its complete 4,096-token
budget without visible content. It is therefore not an honest Qwen thinking
budget.

`max_tokens=4,096` was unsafe on the real small-code task shape: both default
thinking and `reasoning_effort=low` reached `finish_reason: length` with zero
visible content. At `max_tokens=8,192`, three consecutive real Seon task
shapes all reached `stop` and emitted the correct terminal form. Their
provider-total completion counts were 68, 1,369, and 1,338; inferred thinking
tokens were 60, 1,361, and 1,330, while each visible form used eight streamed
token chunks. Thus the small calibration had thinking p50 `1,330`, p95
`1,361`, and a 99.1% thinking share. The two 4,096-token no-content cases are
runaway reasoning and are retained as negative evidence, not averaged away.

The sustained verdict is: keep thinking on and keep the 8,192-token bound, but
do not mistake the bound for guaranteed visible output. In the source-stable
10-agent drive, 50/51 requests stopped with the correct form; one consumed all
8,192 tokens in reasoning and emitted no assistant text. Inferred thinking
tokens were p50 865 and p95 3,204, with a 99.24% share of all completion
tokens. The bound turned the runaway into one durable error value instead of
an unbounded request.

The descriptor/request chain now owns this setting. `seon.ai/targets` projects
`:seon.config.ai/max-tokens` to `:seon.ai/max-tokens`, the closed descriptor
and request schemas require it, and `seon.ai/request-body` emits
`"max_tokens"`. The load proxy enforced the same value for this drive and
recorded it on every request; it did not select a second production policy.
Reasoning-level selection remains unbuilt because Ollama's compatible
`reasoning_effort` value did not honestly bound Qwen thinking.

The exact owner-named model completed the source-stable sustained drive. The
load-owned server was stopped after evidence collection; start the one shared
server with the setup above when a fleet needs it. No model download was
required.

## Result

`mlx_lm.server` is listening on `127.0.0.1:8090` with the complete immutable
snapshot
`mlx-community/Qwen3.6-35B-A3B-4bit-DWQ@73c707af4243243b18193444467872d20cff9399`.
The live drive committed one successful `:seon.ai.attempt`, froze three
executable forms, committed three terminal `:done` receipts, and closed the
run with result `"55"`.

At handoff the listener is PID `17469`. Its command is:

```bash
/Users/sean/src/seon/src-needle/.venv/bin/mlx_lm.server \
  --model /Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen3.6-35B-A3B-4bit-DWQ/snapshots/73c707af4243243b18193444467872d20cff9399 \
  --host 127.0.0.1 \
  --port 8090 \
  --log-level INFO \
  --max-tokens 2048 \
  --chat-template-args '{"enable_thinking":false}'
```

Thinking is disabled at the generic MLX chat-template boundary because fresh
Seon consumes the standard OpenAI `message.content`. With MLX's default
thinking behavior, the first bounded smoke returned 64 tokens only under
`message.reasoning` and reached `finish_reason: "length"` before producing
content. With `enable_thinking=false`, the same endpoint returned
`message.content: "LOCAL_QWEN_OK"` and `finish_reason: "stop"`.

## Dependency ledger and machine inventory

- Seon source: `34fa6631a8f5f35b615436de34851890738c3563`, before this
  report commit. The shared checkout already had unrelated N4/UI edits; none
  were read as evidence or included here.
- Provider assembly: `src/seon/ai.cljc:74-111` derives the primary and optional
  backup from the config dials. `src/seon/cluster.clj:506-524` merges that
  derived value whole into the live loop handle. `src/seon/ai.cljc:325-434`
  owns the one JDK HTTP attempt and standard Chat Completions response read.
- SCI: `reference-code/sci` at
  `8fac6e88f32d53a5fd82ebe80640881e317b84fd`.
- Datahike: `reference-code/datahike` at
  `357ffc87c8009f342b239145802e1385d4a18ca9`.
- Serving runtime: MLX-LM `0.31.3`, MLX `0.32.0`, Transformers `5.13.1`,
  and Hugging Face Hub `1.23.0`.
- Model artifact: Hugging Face revision
  `73c707af4243243b18193444467872d20cff9399`, 19 GiB through resolved
  snapshot links, with no broken links. The `config.json` SHA-256 is
  `2baf1070d970ff2645ad8c9cf553e470ec7dddb770ca0502a7627daa5a097d54`.
  It declares `Qwen3_5MoeForConditionalGeneration` and mixed 4/8-bit affine
  quantization despite the published artifact name being Qwen3.6.
- Machine: Apple Silicon `arm64`, 128 GiB physical memory, and 812 GiB free
  on the root volume at discovery time.
- Installed fallback: Ollama `0.32.1` was already serving on port `11434`,
  with `qwen3.5:35b-a3b-coding-nvfp4` installed at 21,909,194,238 bytes,
  digest
  `6e73b30f8f1cfa06b979c842ba222ae21dad1e55e7c6748a7d8acad46fb340c4`.
  It was not used because the exact Qwen3.6 MLX artifact and runtime were
  already complete.
- No `lms`, LM Studio model directory, `vllm`, SGLang, or standalone
  llama.cpp server command/process was found. No download was attempted.

## Working descriptor row

The original proof predates the explicit no-auth target state. Its effective
database dials before the loop was armed were:

```clojure
{:seon.config.ai/endpoint
 "http://127.0.0.1:8090/v1/chat/completions"
 :seon.config.ai/model
 "/Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen3.6-35B-A3B-4bit-DWQ/snapshots/73c707af4243243b18193444467872d20cff9399"
 :seon.config.ai/api-key-variable "LOCAL_LLM_API_KEY"
 :seon.config.ai/timeout-ms 300000}
```

That proof exported `LOCAL_LLM_API_KEY=LOCAL`; the loopback server ignored it.
The current target/request contract instead declares
`:seon.config.ai/no-auth true`, so the request leaf omits authorization
entirely.
No backup model was configured. The full endpoint, rather than a `/v1` base
URL, is correct for fresh `seon.ai`, whose leaf constructs the POST directly.

The production loop now derives this row from database facts, but
`cluster/start!` currently applies only `(config/defaults)` at boot
(`src/seon/cluster.clj:673-679`). The drive therefore computed the ordinary
default manifest, associated the four local values, and supplied that manifest
through a scoped `with-redefs` of `config/defaults` while calling the normal
`cluster/start!`. This is test-time injection only: the resulting config row
was ordinary persisted database data and the normal boot-installed loop made
the model call. The reproducible drive remains at
`tmp/local-qwen-live-drive.clj` and runs with:

```bash
clojure -M:dev -e '(load-file "tmp/local-qwen-live-drive.clj")'
```

The older research recipe's `clojure -M:dev -M` invocation is stale for the
installed Clojure CLI: the second `-M` is treated as a filename. No cluster or
model call occurred on that failed invocation.

## Direct OpenAI-compatible proof and throughput

A non-streaming POST to `/v1/chat/completions`, with thinking disabled at
server startup, returned standard OpenAI-compatible JSON:

```clojure
{:finish-reason "stop"
 :content "(defn sum-1-to-n [n]\n  (reduce + (range 1 (inc n))))\n\n(sum-1-to-n 10)\n\n(my.run/complete \"55\")"
 :prompt-tokens 60
 :completion-tokens 43
 :total-tokens 103
 :wall-seconds 1.01}
```

Measured generation throughput was `43 / 1.01 = 42.6` completion tokens per
second. This is end-to-end non-streaming HTTP wall time, so it includes request
handling and prompt processing; it is a conservative generation-rate
projection, not TTFT. The preceding strict-string smoke returned 6 completion
tokens in 0.43 seconds but is too short to be a useful throughput measure.

## Live Seon turn evidence

The fresh cluster was `local-qwen`, rooted under the dedicated disposable path
`tmp/local-qwen-drive/clusters`. Agent `alice` received one message asking it
to define a sum function, call it with 10, and complete with `"55"`.

Durable result:

```clojure
{:seon.cluster.run/id
 "1f27489e-795b-4a4a-91c8-eb3ba83afc57"
 :seon.cluster.run/plan-digest
 "e070323ca73e1cb733d1d723e7f6143975a9e9934a60c618ef3709d4247125c0"
 :seon.ai/attempts
 [{:seon.ai.attempt/ordinal 0
   :seon.ai.attempt/outcome :success
   :seon.ai/endpoint "http://127.0.0.1:8090/v1/chat/completions"
   :seon.ai/model
   "/Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen3.6-35B-A3B-4bit-DWQ/snapshots/73c707af4243243b18193444467872d20cff9399"}]
 :forms
 [[0 "(defn sum-to-n [n]\n  (reduce + (range 1 (inc n))))"]
  [1 "(sum-to-n 10)"]
  [2 "(my.run/complete \"55\")"]]
 :receipts
 [[0 :done
   "{:seon.sci.admit/reference \"sci.lang.Var\", :seon.sci.admit/name \"#'my.agents.alice/sum-to-n\"}"]
  [1 :done "55"]
  [2 :done
   "{:my.run/disposition :completed, :my.run/result \"55\"}"]]
 :seon.cluster.run/closed-at
 #inst "2026-07-28T01:49:18.409-00:00"}
```

Observed latency:

- trigger to plan frozen: 2,085.626 ms;
- trigger to run closed: 2,596.518 ms.

The cluster was stopped cleanly after the proof. The model listener was
deliberately left running for overnight local-token use.

## No-auth decision

The local MLX server ignores authorization. The original Seon contract did not
admit a no-auth target:

- `:seon.config.ai/api-key-variable` is a required non-empty string;
- `seon.ai/complete` returns `::no-credential` without making a request when
  that environment variable is absent; and
- every transmitted request unconditionally carries `Authorization: Bearer`.

The dummy `LOCAL_LLM_API_KEY=LOCAL` value is explicit evidence of that former
contract. It did not become a provider convention.

Owner decision resolved 2026-07-28 evening: the existing target/request union
now admits exactly one of a non-empty `:seon.ai/api-key-variable` or literal
`:seon.config.ai/no-auth true`. Simple absence was rejected because a forgotten
hosted credential would otherwise become a silent unauthenticated request.
Both and neither are schema-invalid. At the one HTTP leaf, no-auth bypasses
the environment read and the ordinary request header map contains no
`Authorization` entry; credentialed targets retain the prior fail-closed
`::no-credential` result before the send leaf is entered.

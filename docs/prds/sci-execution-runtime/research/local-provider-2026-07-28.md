---
type: research
status: active
tags: [research, agent, runtime, architecture]
---

# Local OpenAI-compatible provider — 2026-07-28

The exact owner-named model is running locally and completed one real Seon
turn. No model download and no Seon source change were required.

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

These were the effective database dials before the loop was armed:

```clojure
{:seon.config.ai/endpoint
 "http://127.0.0.1:8090/v1/chat/completions"
 :seon.config.ai/model
 "/Users/sean/.cache/huggingface/hub/models--mlx-community--Qwen3.6-35B-A3B-4bit-DWQ/snapshots/73c707af4243243b18193444467872d20cff9399"
 :seon.config.ai/api-key-variable "LOCAL_LLM_API_KEY"
 :seon.config.ai/timeout-ms 300000}
```

The live drive exported `LOCAL_LLM_API_KEY=LOCAL`. No backup model was
configured. The full endpoint, rather than a `/v1` base URL, is correct for
fresh `seon.ai`, whose leaf constructs the POST directly.

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
LOCAL_LLM_API_KEY=LOCAL \
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

The local MLX server ignores authorization, but the current Seon contract does
not admit a no-auth target:

- `:seon.config.ai/api-key-variable` is a required non-empty string;
- `seon.ai/complete` returns `::no-credential` without making a request when
  that environment variable is absent; and
- every transmitted request unconditionally carries `Authorization: Bearer`.

The dummy `LOCAL_LLM_API_KEY=LOCAL` value is explicit evidence of that contract
and is acceptable for this proof because the loopback server ignores it. It
must not become a silent provider convention.

Owner decision required: either retain the four-fact target and document that
no-auth OpenAI-compatible servers receive a named dummy credential, or accrete
an explicit no-auth state to the one existing target/leaf contract so absence
omits the header while hosted targets still fail closed. No provider-specific
branch or second transport mechanism is warranted.

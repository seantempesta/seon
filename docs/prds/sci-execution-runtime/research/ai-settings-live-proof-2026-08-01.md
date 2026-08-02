---
type: research
status: complete
tags: [ai, runtime, config, proof]
---

# AI settings live proof — 2026-08-01

## Boundary

This proves sealed ruling #34 through the real agent door and DeepSeek wire.
The isolated operator root was
`tmp/ai-settings-r34.Y7lDTB`; it published `:current-src` commit
`6a6e8c2c-3296-523b-8e61-7c3ee1883a35`, digest
`e0648e06a96612b7b1ad561c675ba677e00d4d54881500eadd7c1f0d6a1d760c`.
Cluster `ai-settings-r34` ran in PID 25041.

The shipped config facts read back as:

```clojure
{:seon.config.ai/model "deepseek-v4-flash"
 :seon.config.ai/thinking :disabled
 :seon.config.ai/max-tokens 65536
 :seon.config.ai/timeout-ms 180000}
```

The timeout is the calibrated remote-call backstop from
`deepseek-v4-flash-calibration-2026-08-01.md`: thinking-high reached
103.817 seconds and Pro reached 108.783 seconds. Maximum tokens remains the
smallest value exercised across the calibration matrix after 8,192 starved.

## Per-agent topology

One cluster held two new agents. `planner` carried the same-ident agent fact
`:seon.config.ai/thinking :high`; `worker` carried no AI setting fact and
therefore inherited the explicit shipped `:disabled` value. Each received one
ordinary inbound message, ran its own graph, and made a real provider call.

Their durable attempt rows read back as:

```clojure
[{:agent "planner"
  :model "deepseek-v4-flash"
  :settings {:seon.config.ai/model "deepseek-v4-flash"
             :seon.config.ai/thinking :high
             :seon.config.ai/max-tokens 65536
             :seon.config.ai/timeout-ms 180000}
  :reasoning-tokens 519
  :finish "stop"}
 {:agent "worker"
  :model "deepseek-v4-flash"
  :settings {:seon.config.ai/model "deepseek-v4-flash"
             :seon.config.ai/thinking :disabled
             :seon.config.ai/max-tokens 65536
             :seon.config.ai/timeout-ms 180000}
  :reasoning-tokens 0
  :finish "stop"}]
```

The planner usage carried
`{"completion_tokens_details" {"reasoning_tokens" 519}}`. The worker usage
had no reasoning-token detail. The effective settings are stored in
`:seon.ai.attempt/settings-edn`, beside usage and finish reason on the attempt
row, not inside the provider usage document.

## Next-turn live config

Before config apply, the worker graph value had JVM identity hash 1357693296.
Applying the sparse cluster manifest
`{:seon.config.ai/model "deepseek-v4-pro"}` converged in three reconcile
operations. The same process remained PID 25041 and the same worker graph
retained identity hash 1357693296.

The worker's ordered attempt receipts prove resolution happened again at the
next `:call`:

```clojure
[{:model "deepseek-v4-flash"
  :settings {:seon.config.ai/model "deepseek-v4-flash"
             :seon.config.ai/thinking :disabled
             :seon.config.ai/max-tokens 65536
             :seon.config.ai/timeout-ms 180000}}
 {:model "deepseek-v4-pro"
  :settings {:seon.config.ai/model "deepseek-v4-pro"
             :seon.config.ai/thinking :disabled
             :seon.config.ai/max-tokens 65536
             :seon.config.ai/timeout-ms 180000}}]
```

Both calls finished with `stop`. No process restart or graph rebuild occurred.

## Recurring gate

The ruling-focused checkpoint passed 148 tests and 643 assertions across
`seon.cluster.turn-test`, `seon.gen.loop-test`, `seon.error-test`,
`seon.config-test`, `seon.schema.edn-test`, `seon.ai-test`,
`seon.ai-stream-fold-test`, and `seon.cluster.loop-test`.

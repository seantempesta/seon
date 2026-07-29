---
type: research
status: complete
tags: [config, runtime, proof]
---

# Config application proof

Every key in `config/default.edn` is enumerated below. The standing completeness
test is `seon.config-application-test`: its ledger must equal the shipped key
set, so a newly registered and defaulted entry cannot pass without acquiring an
application owner and an honest update mode.

`arm-time` means an applied value shapes a structural runtime object on the next
start or re-arm. Applying it does not mutate an already armed object. `live`
means the consumer queries the current database value on each pass. `mixed`
means agent-loop structure carries the arm-time value while the core-fault path
re-reads the database.

| Config entry | Update | Running consumer and proof |
|---|---|---|
| `:seon.config.flow.compute/queue-depth` | arm-time | `seon.flow/start-work-launcher!`; installed launcher configuration equals the applied value |
| `:seon.config.flow.compute/concurrency` | arm-time | `seon.flow/start-work-launcher!`; installed launcher configuration equals the applied value |
| `:seon.config.eval.result/max-depth` | arm-time | `seon.cluster/loop-handle` → `:seon.sci.admit/caps`; armed handle equals applied caps |
| `:seon.config.eval.result/max-collection` | arm-time | `seon.cluster/loop-handle` → `:seon.sci.admit/caps`; armed handle equals applied caps |
| `:seon.config.eval.result/max-string` | arm-time | `seon.cluster/loop-handle` → eval, message, and render admission; armed handle equals applied caps |
| `:seon.config.eval.result/max-nodes` | arm-time | `seon.cluster/loop-handle` → `:seon.sci.admit/caps`; armed handle equals applied caps |
| `:seon.config.eval/time-limit-ms` | arm-time | `seon.cluster/loop-handle` → `submit-evaluation!!`; armed handle equals applied value |
| `:seon.config.error/recurrence-limit` | mixed | armed agent loop plus `seon.cluster/commit-fault!` live read; armed handle equals applied value |
| `:seon.config.error/escalate-to` | mixed | armed agent loop plus `seon.cluster/commit-fault!` live read; armed handle equals applied value |
| `:seon.config/on-core-error` | mixed | armed eval behavior plus error-fanout database read; armed handle equals applied value |
| `:seon.config.message/max-chain` | arm-time | armed loop passes it to `seon.cluster.message/delivery`; handle equals applied value |
| `:seon.config.run/max-episode-runs` | live | `seon.cluster.work/max-episode-runs`; changing applied facts changes the next read without re-arm |
| `:seon.config.web/port` | arm-time | `seon.cluster/serve!`; applied port `0` binds an OS-selected port rather than the name-derived port |
| `:seon.config.render/coalesce-ms` | live | `seon.render.web/coalesce-floor`; changing applied facts changes the next render-pass read |
| `:seon.config.ai/endpoint` | arm-time | `seon.ai/targets` in the armed loop handle; primary descriptor equals applied row |
| `:seon.config.ai/model` | arm-time | `seon.ai/targets` in the armed loop handle; primary descriptor equals applied row |
| `:seon.config.ai/max-tokens` | arm-time | `seon.ai/targets` in the armed loop handle; primary descriptor equals applied row |
| `:seon.config.ai/api-key-variable` | arm-time | credential-backed `seon.ai/targets`; primary descriptor equals applied row |
| `:seon.config.ai/no-auth` | arm-time | no-auth `seon.ai/targets`; a second armed cluster carries no credential-variable field |
| `:seon.config.ai/timeout-ms` | arm-time | `seon.ai/targets` in the armed loop handle; primary descriptor equals applied row |
| `:seon.config.ai.backup/model` | arm-time | `seon.ai/targets`; armed backup descriptor equals applied overrides |
| `:seon.config.ai.backup/endpoint` | arm-time | `seon.ai/targets`; armed backup descriptor equals applied overrides |
| `:seon.config.ai.backup/api-key-variable` | arm-time | `seon.ai/targets`; armed backup descriptor equals applied overrides |
| `:seon.config.ai.backup/timeout-ms` | arm-time | `seon.ai/targets`; armed backup descriptor equals applied overrides |
| `:seon.config.ai.retry/base-delay-ms` | arm-time | `seon.ai/retry-strategy`; armed strategy equals applied row |
| `:seon.config.ai.retry/multiplier` | arm-time | `seon.ai/retry-strategy`; armed strategy equals applied row |
| `:seon.config.ai.retry/jitter-fraction` | arm-time | `seon.ai/retry-strategy`; armed strategy equals applied row |
| `:seon.config.ai.retry/maximum-delay-ms` | arm-time | `seon.ai/retry-strategy`; armed strategy equals applied row |
| `:seon.config.ai.retry/maximum-retries` | arm-time | `seon.ai/retry-strategy`; armed strategy equals applied row |
| `:seon.config.ai.retry/maximum-total-delay-ms` | arm-time | `seon.ai/retry-strategy`; armed strategy equals applied row |

No registered entry is applied-but-never-consumed. The two live entries are
proved after a second `config/apply!` on the running scratch cluster. Structural
entries deliberately remain unchanged in already armed objects; selected start
and the ordinary topology rebuild are their application boundary.

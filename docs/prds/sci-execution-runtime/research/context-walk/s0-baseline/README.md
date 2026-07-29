---
type: research
status: active
tags: [research, agent, context, experiment]
---

# S0 baseline corpus

## Method

The corpus came from one isolated cluster named `context-walk-s0-s1` under
`tmp/context-walk/runtime/clusters`. The default cluster was neither opened nor
modified. Ordinary boot created root with its root block set; the formal agent
creation transaction created helper with the ordinary agent block set and
namespace `my.agents.helper`.

The six triggers ran in table order against those two agents. Chat and routed
problem cases committed ordinary messages. Routed messages carried
`:seon.cluster.message/about` refs to problem facts. Error cases used
`seon.error/commit-tx`, whose explanation messages are the production error
wake. A mock provider returned only
`(my.run/wait "context-walk capture complete")`.

For every provider invocation, the script queried and found the identical
`:seon.context.capture/prompt` bytes already committed. Each `.prompt.txt`
file is written without an added newline, so its bytes are the capture fact's
exact string. `metrics.edn` preserves capture identity, run identity, and basis
transaction.

## Corpus sizes

Bytes are UTF-8 bytes. Estimated tokens use
`seon.ai.tokens/estimate`, the repository's integer-floored `chars / 4`
estimator.

| Case | Agent | Trigger | Bytes | Estimated tokens |
|---|---|---|---:|---:|
| helper chat | helper | chat | 1,333 | 331 |
| root chat | root | chat | 164 | 41 |
| helper routed problem | helper | routed problem | 2,145 | 533 |
| root routed problem | root | routed problem | 164 | 41 |
| helper error wake | helper | error wake | 3,332 | 829 |
| root error wake | root | error wake | 164 | 41 |

## Immediate observations

The helper block prompt accumulates prior messages, paused runs, settlement,
and routed-problem guidance as the sequence progresses. The growth from 331 to
829 estimated tokens is therefore partly trigger shape and partly real
sequential history.

Root's captured prompt is only the fleet-oversight sentence. It changes the
run ids and episode counts but remains 164 bytes for all three triggers. It
does not include the chat request, routed problem, error fact, root identity,
namespace, or execution grammar.

The exact files and machine-readable evidence are:

- `helper-chat.prompt.txt`
- `root-chat.prompt.txt`
- `helper-routed-problem.prompt.txt`
- `root-routed-problem.prompt.txt`
- `helper-error-wake.prompt.txt`
- `root-error-wake.prompt.txt`
- `metrics.edn`

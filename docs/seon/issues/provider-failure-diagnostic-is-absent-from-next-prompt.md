---
type: issue
status: open
severity: friction
tags: [issue, runtime, test, context]
---

# Provider failure diagnostic is absent from the next prompt

## Fresh-base probe — 2026-09-16

The existing `a-lost-model-call-leaves-a-durable-readable-reason` regression
requires the next prompt to include the provider credential failure reason.
Its old fixture used a fake evaluator and read an occurrence message from the
error identity. A candidate using real SCI confirms turn closure and the exact
durable occurrence message. A new incoming message then supplies a real trigger
for the next turn. The new seed commits, but the acquired prompt does not
contain `The environment variable DEEPSEEK_API_KEY is not set.`

Canonical fresh-base in-process run **44784: 3 passes, 1 failure, 0 errors**.
The original assertion is retained; the candidate is not installed in the
test file. This proves the visibility gap, not its cause or a requirement for
automatic retries. The earlier triggerless candidate is not retry evidence.

The [landing](../../prds/context-generation/research/turn-test-reds-cache-2026-09-16.md)
records complete results, the candidate form, and three priced scope options.
The existing context/error owners must settle the visibility obligation before
changing production. Do not introduce a second notification path or weaken the
assertion merely to make this test pass.

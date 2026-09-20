---
type: issue
status: open
severity: friction
tags: [issue, schema, testing, publication]
---

# Renderer codec fixture reports no declared contract

The canonical armed request `adf54170ac72` reports an error in
`seon.schema.datahike-test/agent-authored-render-symbols-cross-the-transaction-function-codec`:
publishing `:probe.render-codec/plan` says the declared input of
`my.agents.render-codec/render-plan-ai` is nil. Evidence:
`tmp/bridge-step2-retirement-construction.log:216`.

The fixture uses the production source analyzer. It now asserts the contract
exists in both the analyzed row and the stored row, before publishing the
shape. Its subsequent fast request cannot execute because snapshot admission
rejects the published base's old foreign-identity error declaration.

On 2026-09-21, the bridge's freshly published scratch cluster `s2`, PID 22508,
accepted the corresponding direct production sequence: namespace, identity
attribute, analyzed renderer through `seon.turn/row-tx`, then the entity schema
with its render symbol. The analyzed and stored specs were both
`[:=> [:cat [:map [:probe.render-codec/id :probe.render-codec/id]]] :seon.render/ai]`.
All four writes succeeded. This narrows the observation to the earlier fixture
execution; it does not establish a green fixture rerun or a root cause.

Acceptance: rerun the existing codec regression after refreshing the published
test base; if it fails, inspect its first analyzed/stored-contract assertion
before attributing the failure to schema navigation. Keep the single existing
regression; do not add a second fixture or weaken render admission.

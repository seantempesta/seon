---
type: issue
status: open
severity: friction
tags: [issue, contract, reply, testing]
---

# Reply sources return a vector member under a map contract

The bridge's armed 55-namespace snapshot at
`acb39cb6372919d94f409ea61c1c4e07526fc35f` printed two errors in
`seon.ai-stream-fold-test/an-empty-normal-stop-is-a-reply-but-malformed-json-is-a-provider-refusal`.
`seon.cluster.reply/sources` refused its return: a collection member was a
vector where the declared contract requires a map. Evidence:
`tmp/bridge-step2-retirement-fast.log:259` and `:295`.

The run stopped at the separately ruled turn-identity/error-facet decision,
so no completed tally exists. This observation has not been attributed to
the bridge conversion or reproduced against a baseline. The current fast
snapshot cannot enter tests until the published base no longer contains the
old bootstrap error identity members.

Owner: reply parsing and its declared output contract. Acceptance: the same
existing malformed/empty-stop regression completes under armed contracts,
with parser results and declared member shape agreeing. Do not suppress the
return check or infer the intended shape from this error alone.

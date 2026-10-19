---
type: issue
status: closed (c23e1f807 deleted the report and every reader; F2 is the stated exception)
severity: defect
tags: [issue, sci, runtime, done-rule]
---

# The host-bound report has no consumer and names the platform's entrants on every change

`seon.sci.eval/host-bound-reaching` is documented as the done rule's derivation of the host-bound functions that run
main's definitions of a branch's change, but no `src` function calls it (`seon.cluster.source/done-refusals` does
not), and `::seon.sci.eval/host-bound-reached` reaches only `:seon.batch/notes`, which no reply reads
(`batch-notes-never-reach-the-mcp-reply.md`). So a branch test that passes through a host-bound function passes on
main's helper and nobody is told (runtime review F2).

It is also too broad to consume as it stands. Measured 2026-10-19 (lane runtime-rereview,
[runtime-rereview](../../research/agent-platform/2026-10-19/runtime-rereview.md) R4): one changed name names
`seon.db/as-of-key` 34, `seon.id/digest` 38, `seon.schema/handed-projection` 35, `seon.print/literal` 30,
`seon.error.refusal/error` 29, `seon.db/q` 28, `seon.ref/words` 27 host-bound functions; the value
`seon.profile/buckets` names 26, including `seon.sci.eval/evaluate`, `seon.sci.arm/arm!`, `seon.cluster/serve!` and test
fixtures (`seon.cluster.agent-test/with-connection`). The index (`seon.sci.eval/reach-index`) runs over every
`:seon.fn/sym`, test namespaces included, and the platform's entrants reach almost everything.

Owner: `seon.sci.runtime/host-bound-reached` and the done rule. Decide which host-bound reaches a branch test can
pass through (the tests' own reach) before anything consumes it.

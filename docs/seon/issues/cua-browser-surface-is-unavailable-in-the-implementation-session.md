---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [browser, tooling, verification]
---

# CUA browser surface is unavailable in the implementation session

The S7 agent-page proof attempted the documented CUA entry point,
`cua.getBrowser({url: "http://127.0.0.1:7994/agent/juniper"})`.
It returned `CUA_REPL_ENABLED_SURFACES is required` before acquiring a browser.
The local route remains reachable: the HTTP response contained 17,840 bytes.
This is a tool availability failure, not evidence that the page fails.

Restore an enabled browser surface and verify that the documented entry point
can open the local agent page. A local Chrome screenshot can separately prove
the page while this connector remains unavailable.

The fallback was exercised successfully: local Chrome rendered the S7 worker
page at `/agent/2393cac275ae`, including its curated issue status block.
The inspected screenshot and exact status text are in
[the S7 landing note](../../prds/steward-platform/research/issue-task-loop-s7-2026-09-17.md).
The connector itself remains unavailable.

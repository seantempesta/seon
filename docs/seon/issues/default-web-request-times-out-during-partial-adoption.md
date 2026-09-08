---
type: issue
status: open
severity: friction
tags: [issue, web, runtime]
---

# Default web request times out during partial adoption

Observed 2026-09-08 while verifying blob retention on default PID 14049.
`curl --max-time 30 http://127.0.0.1:7994` exited 28 after 30,011 ms with
zero bytes and HTTP 000. MCP JVM queries and the ordinary scheduled retention
task still completed on that process (retention completion 18:58:29 UTC).

Full development adoption had failed during concurrent schema edits. This is
context, not an established cause of the HTTP timeout. `src/seon/render/web.clj`
was being edited by another lane; blob retention did not alter that owner.

Recheck the request after successful adoption and observe its terminal response.
The current evidence proves neither healthy browser rendering nor a permanent
web defect. See the [retention landing note](../../prds/context-generation/research/blob-retention-landing-2026-09-08.md).

Issues-sweep independently re-observed the boundary on 2026-09-08:
`curl --max-time 20 http://127.0.0.1:7994` exited 28 after 20.003488 seconds,
HTTP 000, zero bytes. Default MCP JVM probes still returned normally. The
protected web/adoption owners were left unchanged; the cause remains unproven.

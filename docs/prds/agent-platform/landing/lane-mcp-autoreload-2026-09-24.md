---
type: landing
status: landed
created: 2026-09-24
tags: [agent-platform, mcp, autoreload]
---

# MCP source refresh, 2026-09-24

The bridge at `script/seon/dev/mcp.clj:960` reads one JSON-RPC line at a time. Its only loaded `script/seon/dev/` namespace is `seon.dev.mcp` (`:1-7`); the other require is `seon.operator` from `src/`. The loop now checks that file's modification time at most once per second, reloads it with `require :reload`, resolves the new request handler, and preserves its socket/session atom. No watcher or second process is added. Unchanged requests cost one clock read and an atom read; a stamp check costs one file stat. A content digest would detect same-timestamp rewrites but would read the source on every check.

The [MCP 2024-11-05 tools specification](https://modelcontextprotocol.io/specification/2024-11-05/server/tools#capabilities) names `capabilities.tools.listChanged: true` and [`notifications/tools/list_changed`](https://modelcontextprotocol.io/specification/2024-11-05/server/tools#list-changed-notification). The bridge advertises the former and sends the latter before responding when the complete `tools` value changes. A failed reload logs the file and error, restores prior Var roots if loading reached them, and puts `:seon.dev.mcp/reload-error` in the next tool response. The old handler continues serving; a corrected source retries on the next check. The file timestamp is the trigger, so a rewrite retaining exactly the same timestamp is outside this guarantee.

## Evidence and limit

Broken installed proof first: `bin/test-check default --ns seon.dev.mcp-bridge-test` returned `passed? false` (10 executed, 16 reused, 3 failures, 7 errors, 11,308 ms). The existing test expects only `eval_clj`, `runtime_status`, `get_value`, while commit `08d7320d6` added `branch`; other failures resolve removed bridge names and a missing `responsive-advertisement?` owner. These are current test/source mismatches outside owned paths. The main JVM's hook publication is off for its archive source root, so this script-only bridge change was proved in an isolated Babashka subprocess; it was not adopted into the JVM. `bin/seon status` and MCP `runtime_status` both observed the default cluster alive before editing.

Commands: `bb --classpath script:src:resources -e "(require 'seon.dev.mcp) (println :ok)"`; `python3 tmp/mcp_autoreload_probe.py` (disposable local script). The probe copied `mcp.clj` to a temporary project, started `bb --classpath TEMP/script:$PWD/src:$PWD/resources -m seon.dev.mcp` with `SEON_PROJECT_ROOT=TEMP`, sent `initialize` and `tools/list`, changed only the copied docstring/tool name, and sent another request after 1.05 s. It then tried a syntax error and a throw after definitions, checked that the prior tool list remained, corrected the copy, and observed recovery. Assertions checked `listChanged`, the notification, changed tool name, reload error in tool data, prior tool name after partial failure, and final notification. The repository source was not touched by the probe.

Final probe: 100 warm `tools/list` calls took 0.0398/0.0428/0.0921 ms min/median/max; an unchanged source stamp check took 0.5791 ms end to end. Docstring reload took 15.03 ms; changed-tool reload and notification 9.86 ms; syntax failure response 4.62 ms; partial-load failure response 13.16 ms; recovery and notification 10.03 ms. Process RSS after the probe was 85,376 KiB. `git diff --check` passed. Source diff: 40 additions, 10 deletions. Default was not restarted; no cold gate was run.

An MCP process started before this commit has no source-check loop and cannot refresh itself. Its client must reconnect once to launch the updated server; subsequent source edits refresh within the existing session.

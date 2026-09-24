# Lane fix-mcp-error-window — 2026-09-24

The MCP bridge keeps one error-window basis per `[root cluster branch]` in `window-bases`
and reads `seon.error/occurred-since` over (basis, head]. The bridge's `branch` create
already forks from a known database value, so it now returns that value's basis and
seeds the lane's window with it. Datahike keeps no fork record, so this is the only
cheap owner of the fork point.

## Broken or open
- **A lane this bridge did not create has no known start.** This covers branches made elsewhere
  and lanes from before a bridge restart. Datahike records no fork point: the branch key holds
  the source commit with `:config :branch` rewritten (`reference-code/datahike/src/datahike/versioning.cljc:268`).
  Walking back to the first commit on another branch costs about 2.3 ms per commit on raw
  konserve reads. That was 4.7 s for 2001 commits, and 23 s for default's 7380 commits through
  `commit-as-db`, so a walk is not an option. For such a lane, the first eval now returns
  `:seon.dev.mcp/window :unknown` with its start basis and a requery form. It is loud, never
  silent. A durable fix would record the fork basis at `seon.cluster.registry/branch!`. That
  file is not owned by this lane, and nothing is filed yet.
- The shared cluster context (no branch) still starts at 0. Its history belongs to it; this
  lane leaves that behavior as it was.
- Foreign failures seen while testing:
  - `a-lane-owns-its-branch-create-define-call-retire` fails because the SCI entry gate
    refuses an uncontracted `defn`.
  - Publication refusals came from other lanes' edits (`seon.flow/submit!!` unresolved,
    "Source changed during development adoption").
  - One `Node not found in storage` refusal happened during test admission.
  - On an older lane branch, SCI evals error "Host-bound declaration seon.db/carried-projection
    must change through the loaded source files".

## Branch filter check
The rows listed on 2026-09-24 had `:seon.error.occurrence/branch` values of agent-* and
cluster-default. These are core-fault copies written on cluster-default (`fault.clj:125`,
`error.clj:1434`). A lane branch inherits them through Datahike history. They are not
misattributed rows: the occurrence branch is recorded correctly, and the window start was
the defect. A strict occurrence-branch filter would drop core-fault copies that an agent
writes onto a lane, so this lane adds none.

## Evidence
- Before, on the first SCI eval of a fresh branch (`fix-mcp-error-window`, fork basis 536878322):
  `branch-errors` listed 11 signatures with `:since 0`, all inherited.
- After, live through the auto-reloaded bb bridge: `branch create fix-mcp-error-window-2`
  returned `:seon.db/basis-t 536878734`. Its first eval showed no `branch-errors` (745 ms eval
  on a busy JVM; 374 ms before, on a quieter one). On the older lane, one eval showed 2
  signatures that occurred on that lane (`:seon.error.occurrence/branch ["fix-mcp-error-window"]`),
  and the next eval did not repeat them.
- Regression `seon.dev.mcp-bridge-test/a-new-branch-window-opens-at-its-fork-basis`: it creates
  the branch through `execute-branch`, checks the seeded fork basis, and checks that the parent
  history from 0 is non-empty. The window at the fork basis is 0. One error committed on the
  branch shows once, and the next window is 0. Run `9ea80803ec95`: executed 1, pass 7,
  fail 0, 8843 ms wall for the whole request.
- Installation: the running bb MCP server reloads `script/seon/dev/mcp.clj` itself on the next
  request after the file's mtime changes (`-main`, `require 'seon.dev.mcp :reload`). `defonce
  window-bases` survives that reload. No restart is needed; this was observed live.

## Changed
- `script/seon/dev/mcp.clj`: create returns `:seon.db/basis-t` from the same value it forks;
  `execute-branch` seeds the window on create and drops it on retire; an unseeded lane reads
  only the basis and reports an unknown start.
- `test/seon/dev/mcp_bridge_test.clj`: one regression.
- Net: source +26/-9, test +50.

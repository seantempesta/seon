---
type: issue
status: open
severity: friction
tags: [issue, mcp, cljs]
---

# Dev eval_cljs could not await async values

## Problem

The MCP `eval_cljs` surface routed forms through shadow-cljs's nREPL CLJS
REPL, where the JVM compiler analyzes forms at the top level. A top-level
`(await …)` failed the `(:async &env)` macro assert
(`reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975`) — and
shadow reported the assert on stderr with status `done` and value `nil`, so
it did not even render as an error. Even a compiled `^:async` fn was useless:
the node client's `do-invoke` replies synchronously
(`reference-code/shadow-cljs/src/main/shadow/cljs/devtools/client/node.cljs:113`),
so a returned Promise printed unresolved. With the authority-session facade
making essentially every `seon.db` read a Promise, dev-REPL work required
hand-written `.then`/`.catch` chains stashing values on `globalThis` — while
the agent eval surface (`seon.eval/maybe-await-value`,
`src/seon/eval.cljs:1966`) already auto-awaits.

A second confusion: `((fn ^:async [] …))` fails the same assert because the
analyzer reads `:async` only from the fn NAME meta or the `fn` symbol meta
(`cljs/analyzer.cljc:2336-2341`); meta on the arg vector is ignored.
`(fn ^:async f [] …)` and `(^:async fn [] …)` compile.

## Root cause

Transport gap, not an analyzer bug: the dev eval path had no equivalent of
the agent path's Promise auto-await, and no async env for top-level `await`.

## Fix

`script/seon/dev/mcp.clj` bridges both in the transport, reusing the agent
surface's semantics instead of forking eval:

- an await-assert compile failure re-evals the same code inside one
  `(^:async fn [] …)` wrapper, giving the form an async env;
- a result printing as `#object[Promise …]` gets `.then`/`.catch` handlers
  stashing settlement on `globalThis` under a one-shot key; the server polls
  the same pinned session and returns the RESOLVED value. The final fetch
  form returns the raw value, so shadow prints it normally and `*1` binds
  the resolved value;
- a rejection returns the one `seon.error/->map` envelope rendered as an
  MCP error; a Promise still pending at the eval timeout reports the
  `globalThis` key for later inspection;
- the mis-meta `(fn ^:async [] …)` case now fails LOUD with a hint naming
  the name-meta rule.

Both the default/named-session path and the `agent_id`-pinned path use the
same `resolve-async-value` post-processing.

## Proof (2026-07-20, live default cluster via a fresh bin/mcp-server-cljs)

- `(:t (await (seon.db/db)))` → `536870917` (default session) and
  `536870919` (`agent_id` `default/root`).
- `((fn ^:async f [] (await (js/Promise.resolve 41))))` → `41`.
- `(seon.db/query {:seon.db/query '[:find ?id :where [?e :seon.agent/id ?id]]})`
  → `#{["fresh-dancers-behave"] ["root"]}` (raw Promise resolved).
- `(js/Promise.reject (ex-info "boom" {:x 1}))` → `isError true` with the
  `:seon.error/*` map.
- `(+ 1 2)` → `3`; undeclared-var warnings unchanged (sync path untouched).
- `(do (def p-slow …700ms…) (await p-slow))` → `:late` (defs work inside the
  wrapper; poll loop resolves).
- `((fn ^:async [] (await …)))` → error with the name-meta hint.
- `bb` suite `seon.dev.mcp-test`: 21 tests, 67 assertions, 0 failures.

## Residual caveats

- The Promise-stash bridge attaches to `*1` in a follow-up form on the same
  pinned session; a concurrent eval on the same runtime from another MCP
  server could theoretically clobber `*1` in that window. MCP requests are
  served serially per server, so the window is calls-from-two-servers only.
- Bridge polls shift `*2`/`*3` on stateful sessions when a form goes async.
- Already-running MCP clients do not reload stdio servers: a Claude/Codex
  task must restart to pick up the new behavior.

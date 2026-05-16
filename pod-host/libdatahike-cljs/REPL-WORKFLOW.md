# CLJS REPL Workflow (libdatahike-cljs)

Live ClojureScript eval against a running shadow-cljs build, via MCP. Mirror of `mcp__seon__*` for the JVM side. Drives a long-lived Node runtime so the
compile/run/error loop becomes a single eval round-trip.

## 1. Boot the runtime (one-time per session)

```sh
cd /Users/you/src/seon/pod-host/libdatahike-cljs
# Watcher: builds both targets, writes nREPL port to .shadow-cljs/nrepl.port
nohup npx shadow-cljs watch bench repl > /tmp/shadow-watch.log 2>&1 &
# Wait for "Build completed" in /tmp/shadow-watch.log, then:
nohup node out/repl.js > /tmp/repl-runtime.log 2>&1 &
```

The MCP server discovers the nREPL port on every call by reading
`.shadow-cljs/nrepl.port`, so restarting the watcher does not require
restarting MCP. Stop everything with:

```sh
pkill -f "shadow-cljs watch"; pkill -f "node out/repl.js"
```

## 2. MCP tools

Registered in `seon/.mcp.json` under the namespace `seon_cljs`. Once the
orchestrator has reloaded MCP config, the tools become available as
`mcp__seon_cljs__<name>`:

| Tool | What it does |
|------|---|
| `mcp__seon_cljs__eval` | Evaluate CLJS in the watched build. `session_id="default"` auto-creates a singleton `:repl` build session on first call. `timeout_ms` defaults 30s. |
| `mcp__seon_cljs__create_session` | Clone a new piggyback session pivoted into the named `:build`. Returns a 6-char sid you pass to `eval`. |
| `mcp__seon_cljs__list_sessions` | Active sids + shadow nREPL port. |
| `mcp__seon_cljs__stop_session` | Forget a sid locally (does not interrupt running eval). |
| `mcp__seon_cljs__reload_deps` | Trigger shadow's `reload-deps!` (see caveat below). |
| `mcp__seon_cljs__runtime_status` | Shadow port + build/runtime info. |

Smoke from shell (without MCP) — feed JSON-RPC over stdin:

```sh
echo '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"eval","arguments":{"code":"(+ 41 1)"}}}' \
  | bin/mcp-server-cljs 2>/dev/null
```

## 3. Hot reload

`shadow-cljs watch` recompiles `src/**/*.cljs` on save. The `:repl` build's
`seon.podhost.libdatahike.repl` defines `^:dev/after-load on-reload`, so
each save bumps `(:reloads @!state)` and re-prints the banner in
`/tmp/repl-runtime.log`. Edit a function in any source file, save, then
re-eval — the running Node process picks up the new value with no restart.
Verified end-to-end with `hot-reload-marker` (v1 → v2 after edit).

State preservation: `defonce` survives reloads, `def` is overwritten. Use
`defonce` for connection atoms, registries, and singletons.

## 4. Dynamic dep injection

The intended path is `shadow.cljs.devtools.api/reload-deps!` — adds a new
clojars/npm dep at runtime, recomputes the classpath, swaps it into the
running JVM, then recompiles affected builds. The MCP tool `reload_deps`
calls it.

**Caveat:** `reload-deps!` only works when the watcher was launched via
the standalone `shadow-cljs` server, not via `clojure -M:shadow ...`. Our
`npx shadow-cljs watch` _should_ qualify (it goes through `node_modules/.bin/shadow-cljs`
which calls `from-launcher`), but in the current pinned setup
`reload-deps-fn-ref` came back unbound and the API returned
`::standalone-only`. Until that's nailed down: stop+restart the watcher
after editing `deps.edn`. Restart preserves shadow's compile cache, so it
finishes in ~2s for incremental dep adds.

Smoke: `metosin/malli "0.16.4"` was added to `deps.edn` mid-session and a
fresh `npx shadow-cljs watch` brought it onto the classpath and made it
require-able from CLJS.

## 5. Path B (future — not implemented)

Embed shadow's nREPL into seon's JVM via a `:cljs` alias in `seon/deps.edn`
and run `(shadow/repl :bench)` from inside seon's running nREPL. Then the
existing `mcp__seon__eval` switches its session into CLJS — one MCP server,
both runtimes. Requires editing `seon/deps.edn`, which is Track A's lane
(another agent holds it). Migration target; not done in this seat.

---

## Diagnosis sidebar — the cardinality/many compare bug

Discovered while building CLJS-2.5. The bench's `(d/transact! conn batch)`
threw `Cannot compare [object Object] to [object Object]` whenever the
schema included a cardinality/many attribute, regardless of size or which
backend was active.

Root cause is **two compounding incompatibilities** between datahike
`0.7.1624` and persistent-sorted-set `0.3.116`:

1. **`empty-index` opt-key mismatch.** `datahike.index.persistent-set/empty-index`
   calls
   ```clojure
   (psset/sorted-set* {:cmp <cmp-fn> ...})
   ```
   but psset's `btset/from-opts` only honors `:comparator`, not `:cmp`.
   Any index built from empty therefore defaults its comparator to
   `cljs.core/compare`. The fix: make `btset/from-opts` accept `:cmp`
   as an alias.

2. **`insert`'s `psset/lookup` arity collision.** Datahike's `insert`
   uses an existence-check optimization:
   ```clojure
   :cljs (psset/lookup pset datom (dd/index-type->cmp-prefix index-type))
   ```
   In CLJ, `PersistentSortedSet#lookup` has a `(lookup datom cmp)`
   overload that takes a custom comparator. In CLJS, the 3-arg
   `psset/lookup` has no such overload — the 3rd arg is `not-found`.
   With `prefix-cmp` (a function — always truthy) as the not-found
   sentinel, `lookup` returns it on every miss, the `if` branches "found",
   and `insert` silently skips the `conj`. Result: cardinality/many
   duplicates and any insert into an empty-built (avet) index get
   dropped — _and_, combined with bug 1, the lookup's own binary search
   runs `cljs.core/compare` on raw Datom records and throws.

The patches live at the top of `bench.cljs` and `repl.cljs` as `defonce`
forms that `set!` `btset/from-opts` and `datahike.index.persistent-set/insert`
before any DB is created. The fix for `insert` is the simpler "always
`conj` (which is itself idempotent on equal keys)" — we lose the
zero-allocation lookup, gain correctness.

Both bugs likely apply to any CLJS datahike usage with empty AVET indexes
or cardinality/many attributes. Upstream fix would: (a) make psset's
`from-opts` accept `:cmp` for compatibility with datahike, and (b) add a
3-arg `psset/lookup` overload that accepts a custom comparator (or change
datahike's `insert` to use `psset/conj` directly in CLJS, since `conj`
already takes a cmp).

The bench is pinned to `datahike 0.7.1624`; switching to newer versions
should be done deliberately (don't chase HEAD), and the patches removed
only after verifying the upstream fix landed.

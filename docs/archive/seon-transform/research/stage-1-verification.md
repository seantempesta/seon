# Stage 1: System Startup Verification

**Date:** 2025-12-13
**Status:** SUCCESS

## Summary

The Seon system (copied from ml-options-trading) starts correctly using `./bin/run` and supports seamless code reloading via `(reset)`.

## Correct Workflow

### Starting the System

```bash
./bin/run    # Starts everything: XTDB, HTTP (8080), nREPL (7888)

```

This is the canonical way to run the system. It:
- Starts XTDB with persistent storage
- Starts HTTP server on port 8080
- Starts nREPL server on port 7888 (as an Integrant component)
- Logs to stdout
- Ctrl+C to stop

### Connecting and Reloading

Connect your editor to the running nREPL on port 7888, then use:

```clojure
(reset)   ; Reload all changed code and restart components
(status)  ; Check system health

```

Or from command line:

```bash
clj-nrepl-eval -p 7888 "(integrant.repl/reset)"
clj-nrepl-eval -p 7888 "(user/status)"

```

### Key Insight

**DO NOT** start a separate nREPL manually. The system includes nREPL as an Integrant component. Starting an external nREPL would cause port conflicts.

The unified workflow ensures:
- `(reset)` works regardless of how you started the system
- All state is managed through `integrant.repl.state`
- Clean component lifecycle (halt → reload → go)

## Verification Results

### System Startup

```
$ ./bin/run
-=[ml-options starting using the development profile]=-
INFO [ml-options.system:21] - Starting XTDB node... {:storage {:type :local, :path "data/xtdb"}, :compactor {:threads 2}}
INFO [ml-options.system:35] - XTDB node started {:compactor {:threads 2}}
INFO [ml-options.web.jobs:36] - Job manager initialized with XTDB node
INFO [ml-options.web.sse:165] - SSE broadcast initialized {:throttle-ms 100}
INFO [ml-options.web.server:28] - HTTP server started {:port 8080, :bind "0.0.0.0"}
INFO [ml-options.system:77] - Schema registry initialized {:schema-count 154}
INFO [ml-options.system:134] - Initializing DSL executor...
INFO [ml-options.system:95] - Starting nREPL server {:port 7888, :bind "127.0.0.1"}
INFO [ml-options.system:101] - nREPL server started {:port 7888}

```

### System Status

5 components running:
- `:ml-options/xtdb-node` - XTDB database
- `:ml-options.web.server/http-server` - HTTP on port 8080
- `:ml-options/nrepl-server` - nREPL on port 7888
- `:ml-options/schema-registry` - 154 Malli schemas
- `:ml-options/dsl-executor` - DSL execution engine

### XTDB Health

- Node ID: c52afa8d
- Latest TX ID: -1 (empty database, as expected)
- Memory Cache: 2.15 GB free
- Query Performance: ~16ms mean
- Transaction/Query Errors: 0

### Code Reload Test

`(reset)` successfully:
1. Halts all components cleanly
2. Reloads 26 namespaces
3. Restarts all components
4. Returns `:resumed`

## Database Note

The XTDB database is empty (data was not copied from ml-options-trading). This is expected - the 73GB of trading data stays in the original project. Seon starts fresh.

## Conclusion

**VERIFIED** - System runs correctly with `./bin/run` and `(reset)` workflow.

Ready for Stage 2: Rename all `ml-options` references to `seon`.

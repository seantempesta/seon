---
type: research
status: draft
tags: [prd, research, database]
---
# Datalevin Concurrent Access Research

## Executive Summary

**Can multiple processes safely share a Datalevin database?**

**Answer: No, not with embedded/local mode. Yes, with client/server mode.**

Datalevin inherits LMDB's single-process constraint for local/embedded databases. Multiple nREPL processes cannot safely open the same Datalevin directory simultaneously. However, Datalevin provides a client/server mode that allows unlimited concurrent clients.

## LMDB Fundamentals

### POSIX Lock Behavior

From Datalevin's `open-kv` documentation:

> LMDB uses POSIX locks on files, and these locks have issues if one process opens a file multiple times. Because of this, do not mdb_env_open() a file multiple times from a single process. Instead, share the LMDB environment that has opened the file across all threads. Otherwise, if a single process opens the same environment multiple times, closing it once will remove all the locks held on it, and the other instances will be vulnerable to corruption from other processes.

### Key Constraints

1. **Single Writer**: LMDB supports only one write transaction at a time. Writes are serialized - whoever writes later wins.

2. **Multiple Readers**: Concurrent reads are fully supported within a single process. Each reader sees a consistent snapshot.

3. **Single Process Per Database**: Only ONE process should open an LMDB environment. The file locks prevent safe multi-process access.

### What Happens With Multiple Processes?

From `datalevin.binding.cpp/get-rtx`:

```clojure
(raise "Please do not open multiple LMDB connections to the same DB
 in the same process. Instead, a LMDB connection should be held onto
 and managed like a stateful resource. Refer to the documentation of
 `datalevin.core/open-kv` for more details."
       {:cause (.getMessage e)})

```

The test `lmdb_test.clj` confirms this behavior - opening multiple connections leads to lock corruption:

```clojure
(is (thrown-with-msg? Exception #"multiple LMDB"
                      (if/get-value lmdb "a" :something)))

```

## Embedded Mode: Thread-Safe, Not Process-Safe

### Transaction Model

From `doc/transact.md`:

- Read and write transactions are independent and don't block each other
- Writes are serialized - only one thread can write at a time
- Readers see a snapshot consistent to when their read transaction started
- Write transactions create new snapshots visible to subsequent readers

### Within a Single JVM

Datalevin is designed for multi-threaded access within a single process:

```clojure
;; Safe: Multiple threads sharing one connection
(def conn (d/get-conn "/tmp/mydb"))

;; Thread 1
(d/transact! conn [{:name "Alice"}])

;; Thread 2 (concurrent)
(d/q '[:find ?name :where [_ :name ?name]] @conn)

```

The `transact-kv` function uses `locking write-txn` to serialize writes safely.

### Across Multiple Processes

**NOT SAFE**. Two JVM processes cannot safely open the same database:

```clojure
;; Process 1 (nREPL on port 7888)
(def conn (d/get-conn "/tmp/shared-db"))

;; Process 2 (nREPL on port 7889)
(def conn (d/get-conn "/tmp/shared-db"))  ;; DANGER: lock corruption

```

This will corrupt the lock file. Closing one connection removes locks for all.

## Client/Server Mode: The Multi-Process Solution

Datalevin provides a networked client/server mode specifically for multi-process/multi-client scenarios.

### Architecture

From `doc/server.md`:

> The server employs a non-blocking event driven architecture, so it can support a large number of concurrent connected clients. The server event loop runs as a single process. It accepts and segments incoming bytes from the network into messages, then dispatches them to a work stealing thread pool.

### How It Works

1. **Server Process**: One process owns the LMDB database
2. **Clients**: Connect via `dtlv://` URI
3. **Transparent API**: Same functions work for local and remote

```clojure
;; Client 1
(def conn (d/get-conn "dtlv://user:pass@localhost:8898/mydb"))

;; Client 2 (different process)
(def conn (d/get-conn "dtlv://user:pass@localhost:8898/mydb"))

;; Both work safely!

```

### Consistency Model

> Each client always checks `last-modified` time of the remote database before data access, so when multiple clients are accessing the same database on the server, all see the same most up-to-date data, as long as the clients and the server have clock synchronization.

### Running the Server

```bash
# Native binary (faster startup, limited GC)
dtlv serv -r /data/datalevin

# JVM version (better throughput, recommended for production)
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     -jar datalevin-0.9.10-standalone.jar serv -v -r /data/datalevin

```

## Environment Flags

### Relevant Flags

| Flag | Purpose |
|------|---------|
| `:nolock` | Disable locking (caller manages locks) |
| `:rdonly-env` | Open environment read-only |
| `:nosync` | Skip fsync (faster, less durable) |

### The `:nolock` Flag

From `core.clj`:

> `:nolock`, don't do any locking, caller must manage their own locks

This is used in tests but is **dangerous for production** - the caller becomes responsible for all locking.

## Implications for Seon's Agent Architecture

### Current Architecture

Seon agents run as isolated nREPL processes, each with:

- Separate JVM process
- Own nREPL server on unique port
- Own database namespace

### Option 1: Database Per Agent (SAFE)

Each agent gets its own database directory:

```
data/datalevin/
  agent-a1b2/
  agent-c3d4/
  orchestrator/

```

**Pros:**

- Complete isolation
- No coordination needed
- Each agent owns its data

**Cons:**

- No shared state between agents
- Data duplication if agents need same data
- More disk usage

### Option 2: Client/Server Mode (SAFE)

Run a Datalevin server, all agents connect as clients:

```clojure
;; Orchestrator starts server
(start-datalevin-server {:port 8898 :root "data/datalevin"})

;; Each agent connects
(def conn (d/get-conn "dtlv://agent:pass@localhost:8898/seon"))

```

**Pros:**

- Shared data access
- Single source of truth
- Proper write serialization
- RBAC for access control

**Cons:**

- Additional server process to manage
- Network overhead (minimal on localhost)
- Single point of failure (though LMDB is crash-safe)

### Option 3: Orchestrator-Owned Database (HYBRID)

Orchestrator owns the main database, agents get read-only access via snapshots or their own databases:

```clojure
;; Orchestrator owns main DB
(def main-db (d/open-kv "data/datalevin/main"))

;; Agent gets a copy or read-only connection
(d/copy main-db "data/datalevin/agent-snapshot")
(def agent-db (d/open-kv "data/datalevin/agent-snapshot"))

```

**Pros:**

- Clear ownership
- Agents can't corrupt main data

**Cons:**

- Snapshots become stale
- Complex sync logic needed

## Recommendation for Seon

**Use Option 1 (Database Per Agent) for now, with Option 2 (Client/Server) as future upgrade path.**

### Rationale

1. **Current Usage**: Agents are isolated and don't need shared state
2. **Simplicity**: No server process to manage
3. **Safety**: No risk of lock corruption
4. **Migration Path**: Easy to migrate to client/server later

### Implementation Pattern

```clojure
;; Each agent session creates its own database
(defn create-agent-session [{:keys [session-id namespace]}]
  (let [db-path (str "data/datalevin/" session-id)]
    {:db (d/get-conn db-path schema)
     :session-id session-id}))

```

### When to Upgrade to Client/Server

Consider client/server mode when:

- Agents need to query each other's data
- Central audit log required
- Access control between agents needed
- Production deployment with reliability requirements

## Summary

| Scenario | Embedded | Client/Server |
|----------|----------|---------------|
| Single process, multiple threads | SAFE | SAFE |
| Multiple processes, same DB | UNSAFE | SAFE |
| Multiple JVMs, same directory | UNSAFE | SAFE |
| Agent isolation | SAFE (separate DBs) | SAFE |
| Shared data between agents | Not possible | SAFE |

## References

- `reference-code/datalevin/doc/transact.md` - Transaction semantics
- `reference-code/datalevin/doc/server.md` - Client/server architecture
- `reference-code/datalevin/src/datalevin/core.clj` - API documentation
- `reference-code/datalevin/src/datalevin/binding/cpp.clj` - LMDB binding details
- `reference-code/datalevin/test/datalevin/lmdb_test.clj` - Multi-connection test

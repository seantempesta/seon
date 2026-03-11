---
type: prd
status: draft
tags: [prd, database]
---
# Datalevin Connection Deadlock Research

## The Deadlock Code Path

### Step 1: `seon.db/transact!` (src/seon/db.clj:88-113)

Our wrapper spawns a `future` that calls `d/transact!`, then `deref`s it with a 10s timeout. On timeout, it calls `future-cancel` and throws.

### Step 2: `datalevin.conn/transact!` → `-transact!` (reference-code/datalevin/src/datalevin/conn.clj:132-144)

```clojure
(defn- -transact! [conn tx-data tx-meta]
  (let [report (with-transaction [c conn]
                 (assert (conn? c))
                 (with @c tx-data tx-meta))]
    (assoc report :db-after @conn)))
```

### Step 3: `with-transaction` macro (reference-code/datalevin/src/datalevin/conn.clj:60-119)

This is the critical piece:

```clojure
(defmacro with-transaction [[conn orig-conn] & body]
  `(locking ~orig-conn           ;; <--- JAVA MONITOR #1: on the conn atom
     (let [db#  ^DB (deref ~orig-conn)
           s#   (.-store db#)
           old# (db/cache-disabled? s#)]
       (locking (l/write-txn s#)  ;; <--- JAVA MONITOR #2: on the write-txn object
         ...
         ;; For remote (DatalogStore):
         (r/open-transact s#)     ;; <--- BLOCKING network call to server
         ... body ...
         (r/close-transact s#)    ;; <--- Another blocking network call
         ))))
```

Two nested `locking` calls (Java `synchronized` monitors), plus blocking network I/O inside.

### Step 4: Network calls via `datalevin.client` (reference-code/datalevin/src/datalevin/client.clj:35-49)

```clojure
(send-n-receive [this msg]
  (locking bf                    ;; <--- JAVA MONITOR #3: on the ByteBuffer
    (p/write-message-blocking ch bf msg)
    (.clear bf)
    (let [[resp bf'] (p/receive-ch ch bf)]  ;; <--- BLOCKING socket read
      ...)))
```

### The Full Lock Chain

When a remote `d/transact!` is in progress, the thread holds:

1. **Monitor on `conn` atom** — `(locking orig-conn ...)`
2. **Monitor on `write-txn` object** — `(locking (l/write-txn s#) ...)`
3. **Monitor on `ByteBuffer bf`** — `(locking bf ...)` inside `send-n-receive`
4. **Blocked on socket read** — `p/receive-ch` waiting for server response

### What Happens on Timeout

1. `seon.db/transact!` deref times out after 10s
2. `future-cancel` calls `Thread.interrupt()` on the future's thread
3. **But the thread is blocked in `synchronized` (`locking`)** — `Thread.interrupt()` does NOT release Java monitors. It only sets the interrupt flag.
4. If the thread is blocked on socket I/O (`receive-ch`), the interrupt MAY cause an `InterruptedException` or `ClosedByInterruptException` on NIO channels — but the thread is inside nested `locking` blocks, so even if the I/O is interrupted, the monitors are only released when execution exits the `locking` forms.
5. If the socket read completes or throws, the monitors eventually release. **The real problem is when the server is slow/hung** — the thread blocks indefinitely on socket read, holding all three monitors.

### Why Subsequent Writers Deadlock

Any thread calling `d/transact!` on the same `conn` will block at `(locking orig-conn ...)` — the first monitor. Since the timed-out future's thread still holds that monitor (blocked on socket I/O), all subsequent transactions on that connection deadlock permanently.

## Recovery Options WITHOUT Killing the JVM

### Option 1: Close the Socket (RECOMMENDED — works today)

If we can close the underlying `SocketChannel`, the blocked `receive-ch` call will throw `AsynchronousCloseException`. This will unwind through the `locking` blocks, releasing all monitors.

**How:** The Datalevin `ConnectionPool` holds `Connection` objects with a `SocketChannel`. We can:

1. Detect the deadlock (e.g., try `locking conn` with a timeout via `ReentrantLock.tryLock`, or track which thread holds the lock)
2. Access the connection's socket and `.close()` it
3. The blocked thread gets `AsynchronousCloseException`, monitors release
4. Close the old `conn`, create a fresh one via `conn/reconnect!`

**Difficulty:** Medium. The `Connection` and `ConnectionPool` types are package-private (`^:no-doc`). We'd need reflection or to access the `DatalogStore`'s client to get at the pool and its connections.

**Implementation sketch:**

```clojure
(defn force-release-deadlock! [conn]
  ;; 1. Get the DatalogStore from the DB
  (let [store (.-store ^DB @conn)]
    (when (instance? DatalogStore store)
      ;; 2. Get the client from the store (needs reflection)
      (let [client (.client store)
            pool (get-pool client)]
        ;; 3. Close all sockets in the pool's "used" queue
        ;; This forces AsynchronousCloseException on blocked threads
        (doseq [c (.used pool)]
          (.close (.ch c)))))))
```

### Option 2: Thread.stop() (DANGEROUS — last resort only)

`Thread.stop()` is deprecated and unsafe — it releases monitors by throwing `ThreadDeath` but can leave data structures in inconsistent states. Not recommended.

### Option 3: Replace the Connection (partial fix)

Our `conn/reconnect!` already closes the old connection and creates a new one. But if the old connection's monitor is held by a blocked thread, `close` may also block (it calls `send-only` to disconnect). And the *conn atom itself* is the monitor — even with a new Datalevin connection underneath, the old atom is still locked.

**This doesn't work** because the `locking` is on the `conn` atom, not the underlying socket.

### Option 4: Replace the Conn Atom Entirely

Instead of trying to unlock the old atom, create an entirely new conn atom via `d/get-conn` (which returns a fresh atom). Update our connection manager to point to the new atom. The old atom and its thread are abandoned (leaked).

**Difficulty:** Low — our `conn/reconnect!` already does `close + get-or-create-connection!`. The issue is that `close-datalevin-conn` may block. We can wrap it in a future with timeout:

```clojure
(defn reconnect! [...]
  ;; Close old conn in background (may block if deadlocked)
  (when-let [entry (get @connections ns-key)]
    (future (close-datalevin-conn (::connection entry))))
  (swap! connections dissoc ns-key)
  ;; Create fresh connection (new atom, new socket)
  (get-or-create-connection! manager ns-key db-name schema))
```

**Downside:** Leaks a thread + socket. But the socket will eventually time out or be GC'd.

### Option 5: Single Writer Thread (architectural)

Route all `d/transact!` calls through a single dedicated thread (e.g., a core.async channel or single-thread executor). This thread holds the monitors and does the blocking I/O. If it gets stuck, we kill just that thread and start a new one. The calling threads never hold Datalevin monitors — they just submit work and wait on a promise.

**Difficulty:** Medium. We already have `seon.db.datalevin.writer` (the flow-based writer). But `seon.db/transact!` currently calls `d/transact!` directly for zero overhead. We'd need to route all writes through the flow channel.

**This is the cleanest long-term solution** but changes the write path for all code.

## Recovery Options WITH Datalevin Source Changes

### Option A: Replace `locking` with `ReentrantLock.tryLock(timeout)`

In `datalevin.conn/with-transaction`, replace:

```clojure
(locking orig-conn ...)
```

with:

```clojure
(let [lock (.getLock orig-conn)]  ;; store a ReentrantLock in conn metadata
  (if (.tryLock lock 10 TimeUnit/SECONDS)
    (try ... (finally (.unlock lock)))
    (throw (ex-info "Transaction lock timeout" {}))))
```

**Difficulty:** Moderate Datalevin patch. Would need to change conn creation to attach a lock, and update `with-transaction`.

### Option B: Add Socket Timeout

Set `SO_TIMEOUT` on the `SocketChannel` in `datalevin.client/connect-socket`:

```java
.setOption(StandardSocketOptions/SO_TIMEOUT, 30000)
```

This would cause blocked reads to throw after 30s, releasing all monitors naturally.

**Problem:** `SocketChannel` doesn't support `SO_TIMEOUT` (that's for `Socket`). NIO channels use `Selector` for timeouts. Would require more invasive changes to the client protocol.

## Recommended Approach

**Short term (implement now): Option 4 — Replace the Conn Atom**

1. Add deadlock detection to `seon.db.datalevin.conn`: if `seon.db/transact!` times out, mark that conn as "deadlocked" in the manager
2. On next `get-or-create-connection!`, if marked deadlocked, discard the old atom (close in background) and create a fresh connection
3. Log the leaked thread for monitoring

**Scope:** ~2-3 hours. Changes to `seon.db.clj` (notify conn manager on timeout) and `seon.db.datalevin.conn` (deadlock flag + recovery logic).

**Medium term (track as tech debt): Option 5 — Single Writer Thread**

Route all writes through a serialized executor. This eliminates the deadlock class entirely since the calling thread never holds monitors. If the writer thread hangs, kill it and start a new one.

**Scope:** ~1 day. Refactor `seon.db/transact!` to submit to executor, update writer flow.

**Long term: Option 1 — Socket Close**

For immediate recovery of stuck threads (not just creating new connections), implement socket-level interruption via reflection into Datalevin internals. Fragile but effective.

## Should We Fix This Now?

**Yes, implement Option 4 (Replace Conn Atom) now.** The current state is unacceptable — a single hung transaction takes down all writes for the entire system until JVM restart. Option 4 is low-risk, low-scope, and provides automatic recovery. The leaked thread is an acceptable tradeoff vs. system-wide write deadlock.

Track Option 5 (Single Writer Thread) as the proper architectural fix for later.

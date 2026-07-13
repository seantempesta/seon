---
type: component
status: active
tags: [component, database]
---

# Database

`seon.db` is the sole application database API. The Node ClojureScript pod
reads an immutable local Datahike value and forwards writes through one typed
protocol to the JVM `seon.db.server`, the sole writer. There is no embedded
application database, core.async database flow, or second write path.

## Runtime boundary

| Namespace | Runtime | Responsibility |
|---|---|---|
| `seon.db` / `seon.db.internal` | CLJS | Application query, pull, entity, transact, and listener API |
| `seon.db.replica` | CLJS | One attachment lifecycle: request/reply writes, committed-transaction feed, reconnect, replay, and read-your-own-write completion |
| `seon.db.protocol` | CLJC | Fully namespaced request, response, transaction-event, and error schemas |
| `seon.db.transport.uds` | CLJ + CLJS | Length-framed Transit transport only |
| `seon.db.writer` | JVM | Validate, serialize, commit, publish, recover idempotent requests, and page replay |
| `seon.db.registry` | JVM | Live Datahike connection resources keyed by database name |
| `seon.db.backend` | JVM | Compile validated database facts into Datahike backend configuration |
| `seon.db.server` | JVM | Compose and own the writer process lifecycle |

The registry's atom holds opaque live Datahike connection resources that cannot
be persisted. Database identity, schema, agent state, transactions, and
coordinates remain database facts; the resource registry is not a second
source of domain truth.

## One write and replication path

1. Application code calls `seon.db/transact!`.
2. `seon.db.replica` sends a fully namespaced transaction request carrying a
   request id and explicit database name.
3. `seon.db.writer` validates and commits once. Durable private receipt facts
   make an ambiguous retry recoverable without publishing receipt internals as
   application datoms.
4. The writer publishes the committed transaction once. Every attached reader
   applies transactions in order to its local immutable value.
5. A reconnect replays bounded pages from the last complete coordinate before
   accepting buffered live events.

Ordinary reads never cross the socket. A missing or stale feed is repaired by
the same replay mechanism; there is no query-subscription engine or secondary
invalidation bus.

## Focused verification

```bash
bin/test-writer
bin/test-writer seon.db.writer-integration-test
bin/test-cljs --test=seon.db.replica-test
```

The writer gate loads only retained JVM database tests. The CLJS gate exercises
the same replica and `seon.db` surface used by the pod. See [[testing]] for test
selection and [[../architecture/data-model]] for persisted facts.

---
type: research
status: completed
tags: [research, database, agent, flow]
---

# Runtime reconstruction and replay boundary

## TL;DR

Seon currently uses “replay” for three unrelated operations. Only one executes
code, and that path deliberately reconstructs declarations rather than replaying
arbitrary evals:

- program reconstruction evaluates stored namespace/function/schema/test
  declarations into a fresh CLJS runtime;
- turn replay reads forensic facts and blobs without executing anything;
- transaction-feed replay reapplies missed committed datoms to a reader and its
  listeners after a connection gap.

The reliability refactor should rename these operations around their actual
effects. It must never promise to reproduce arbitrary runtime state or re-fire
an eval. Database history restores database facts; it cannot make an external
side effect safe to repeat. Ephemeral eval results already degrade honestly to a
“prior session” value and should remain elided/missing after restart.

## The three current meanings

### Program runtime reconstruction

`seon.client/replay-program-graph!` reads the current agent-authored program
graph and evaluates reconstituted whole namespaces in dependency order. The
compiled package is already loaded and is excluded.

Persisted executable source is currently constrained before it reaches this path:

- a function row is created only for exactly one literal top-level `defn` or
  `defn-` form;
- schema rows retain explicit `seon.schema/register!` calls;
- namespace and test declarations are structural program definitions;
- bare `def`, `do`-wrapped definitions, multi-form source, and arbitrary eval
  expressions execute as scratch but are not teed into the replayable program
  graph.

The comments at `src/seon/eval.cljs:1907-1943` explicitly identify prevention
of the former ghost-message/double-side-effect class as the reason for this
strict form-head gate.

This operation should be renamed conceptually to runtime reconstruction or
program loading. “Replay” incorrectly suggests event sourcing and effect
re-execution.

The schema call-string arm is transitional and is removed by the canonical
Malli-form refactor. Final program loading evaluates namespaces/functions/tests
only; schema runtime reconstruction parses/validates canonical EDN through the
single atomic registry path and never self-host-evals `register!` source.

### Turn forensic reconstruction

`seon.agent.debug/turn` reconstructs a historical observation:

- the database coordinate rendered before the turn;
- stored prompt and reply blobs;
- the turn and eval entities;
- errors; and
- any transaction provenance that follows from the recorded domain facts.

It executes none of them. Follow-up design review rejected a durable transaction
turn ref: enumerating every arbitrary write under a turn is not a required
forensic query and would falsely suggest a complete causal/effect-replay
boundary. Ordinary message/eval/turn/run refs preserve the facts Seon actually
models. The word replay in the UI/API should mean “inspect the recorded turn,”
not “run it again.”

No special durable turn fence is needed merely to inspect history. A write
legitimately committed after asynchronous work must still pass the current run
CAS fence; detached work must be rejected when that fence has closed even if a
Promise retained AsyncLocalStorage. This is runtime lifetime control, not a
durable-execution replay feature.

### Transaction-feed gap recovery

The wire reader records its last applied transaction and asks the sole JVM
writer for missing committed transactions after reconnect. It applies those
datoms in commit order before buffered live frames and suppresses overlap by
watermark.

This is replication/feed recovery. The transactions already happened. The
reader is catching up its immutable database view and firing normal listeners;
it is not asking application code to decide or perform the writes again.

## Ephemeral eval results

Eval values live on `globalThis`, keyed by `result/<eval-id>`. They are process
objects and can include functions, Promises, handles, streams, or third-party
objects that have no honest database representation.

After restart:

- the eval entity remains;
- source, printed output, rendered result EDN where available, success/error,
  and transcript placement remain;
- the live object is absent;
- `lookup-result` and bare `result/<id>` return an explicit prior-session miss
  instructing the agent to re-run the form only if appropriate.

This is correct. Do not attempt to serialize arbitrary values or automatically
re-evaluate source to reconstruct them. The transcript can mark the value
missing or omit old result bodies for brevity while preserving the eval fact.

The current message mentions a “resume marker,” although the persisted
`:seon.db/resume-marker?` metadata has no writer or reader. The UI can derive a
restart boundary from runtime/process facts if one is genuinely needed, or use
plain “not live in this runtime” wording. It should not retain a dead metadata
field merely to support prose.

## What Datahike and Konserve restore

The active pod is a disaggregated Datahike reader over the same file-backed
Konserve store used by the JVM writer:

- `Connection` dereference detects a non-streaming writer, synchronously reads
  the current branch root, and reconstitutes a fresh immutable DB value
  (`reference-code/datahike/src/datahike/connector.cljc:69-78`);
- persistent index nodes are loaded on demand by address and cached in an LRU
  (`reference-code/datahike/src/datahike/index/persistent_set.cljc:430-443`);
- Konserve itself is wrapped in another bounded store cache
  (`reference-code/datahike/src/datahike/store.cljc:24-34`);
- the writer replaces the branch root atomically and index nodes are immutable,
  so readers do lock-free local reads while all writes cross the Unix socket.

This architecture should make database restoration and repeated reads fast
without copying the entire database into each Node process. It does not restore:

- live JavaScript function objects;
- pending Promises or timers;
- open files/sockets;
- external service effects;
- arbitrary eval return objects.

Those are rebuilt only where a safe declaration exists, or remain absent.

## Database time travel

Datahike `as-of`, history, and transaction-feed recovery can return database
state at or through any retained transaction. That is the complete promise:

```text
database state at T, not the external universe at T
```

An eval that launched a missile, sent an email, mutated an external API, or
opened a local resource cannot be safely replayed from its stored source. Seon
must continue treating those effects as completed observations recorded in the
database, not commands to reproduce during restoration.

## Transaction metadata consequences

The database should record post-processing facts, not recovery instructions:

- `:seon.db/user` — which database user asserted the committed datoms;
- `:seon.db/process` — boot, config, or REPL as the factual committing path;
- `:seon.store.wire/write-id` — transport correlation.

Do not persist:

- replay flags;
- resume flags;
- instructions to re-execute an eval;
- serialized arbitrary runtime values;
- operation/status labels derivable from result datoms.

The current `:seon.db/eval-id` is not needed for restoration. If no forensic
query requires every transaction inside one eval, keep current-eval only in
runtime context and remove it from durable transaction metadata.

## Required code changes

- Rename the conceptual program-graph replay path to program/runtime loading.
- Keep strict namespace/function/test declaration-only loading and add no
  generic eval replay; remove schema call-string loading after canonical forms
  land.
- Ensure detached asynchronous callbacks cannot write with a stale turn/run
  context after the CAS fence has closed the run.
- Remove persisted turn/eval transaction correlation; keep those values only in
  runtime/domain entities where they have direct semantics.
- Replace transcript references to a stored resume marker with a derived runtime
  boundary or an honest “not live in this runtime” message.
- Keep transaction-feed replay terminology isolated to replication internals.
- Document Datahike `as-of` as database-state restoration, not runtime/effect
  restoration.
- Preserve result source/output/error facts while eliding or marking absent
  process-local result values after restart.

## Proofs required during implementation

1. A scratch effectful eval executes once and creates no replayable program row.
2. Literal function/test declarations load after restart; a canonical schema
   form restores through the registry path without eval.
3. A prior-session result reference returns an honest missing value without
   executing its source.
4. Turn inspection performs no writes or evals.
5. A detached Promise completing after its run fence closes cannot commit stale
   work despite retaining runtime context.
6. Feed gap recovery applies each already-committed transaction once to local
   listeners.
7. `as-of` queries return historical database facts without invoking domain
   functions.

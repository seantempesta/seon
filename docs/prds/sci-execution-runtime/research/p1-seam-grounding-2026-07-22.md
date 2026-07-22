---
type: research
status: active
tags: [research, agent, architecture]
---

# P1 grounding — effectful-operation surface inventory for THE SEAM

This is the grounded inventory for the P1 owner design. It extends the
authoritative 106-symbol/85-blocker census in
[[w50-surface-census-grounding-2026-07-22]] with call shapes, actual platform
leaves, current JVM-host counterparts, drift, replay classes, and the natural
portable/platform cut. It does not propose another wrapper registry or a second
transport.

The binding owner ruling is the late-night 2026-07-22 rewrite in
[[../program-synthesis-2026-07-21]]: one capability/effect boundary serves
database, blob, filesystem, web, shell, providers, and packages; sync versus
async remains a platform-leaf concern; package-host routing enters the same
door; and only same-source or same-artifact bridges are admissible. The LEFT
side of `test/seon/host_surface_writer_test.clj:13-153` remains the surface
authority. This report does not re-census it.

## Classification caveat the seam owner must settle

The owner ruling asks each capability function to declare one of `pure`,
`idempotent`, or `external`. The same roadmap later declares four replay
classes: PURE, IDEMPOTENT, EXTERNAL, and READ-ONLY
(`docs/prds/sci-execution-runtime/program-synthesis-2026-07-21.md:1679-1685`).
A database query, filesystem read, environment read, or process-table read is
not referentially pure, but is replay-safe in the sense meant by READ-ONLY.
Tables below therefore use **ambiguous: read-only/idempotent** instead of
silently misclassifying such calls. This is a P1 taxonomy decision, not a
source-reading gap.

Likewise, `external` means an operation may affect or depend on an authority
outside the child. Some external operations have an idempotency key or
content-addressed convergence. Those are called out as
**external, receipt-idempotent** or **idempotent publication**, because replay
safety is conditional on preserving that identity.

## Database — exemplar and deepest cut

### Public database-value and request contracts

The child API is already expressed as ordinary data. A database value is the
closed map registered in `src/seon/db/protocol.cljc:230-247`: database name,
store ID, basis transaction `:t`, optional `:as-of`/`:since`, `:history`, and
commit ID. The request maps are closed and colocated in
`src/seon/db.cljs:71-143`. The transaction response is the raw selected report
`{:db-before :db-after :tx-data :tempids :tx-meta}` plus optional managed-id
and recovered-commit evidence (`src/seon/db.cljs:81-92`). Errors at the child
surface are flat `:seon.error/message`, `:seon.error/kind`, and optional
`:seon.error/data` maps (`src/seon/db.cljs:24-29`).

There is pre-seam schema/body drift inside the child too. `cas-assert`,
`query-with-evidence`, `pull-many`, `entity`, `installed-schema`,
`execute-many`, and `index-page` have no public Malli declaration on their vars
at the cited definitions. Shared helpers also consume an internal
`:seon.db/request-id`, and `read-db!` can consume `:seon.db/database-name`
(`src/seon/db.cljs:748-758`), while the closed transaction/query/pull request
schemas do not admit those keys. They are implementation controls, not valid
keys on the schema-instrumented agent calls. THE SEAM must not accidentally
promote them into public options while extracting common code.

| Agent-facing function | Exact child call shape and result | Present platform leaf | JVM-host counterpart and drift | Effect class |
|---|---|---|---|---|
| `seon.db/current-agent-id` | `[] -> string-or-nil`, synchronous (`src/seon/db.cljs:696-700`). | `AsyncLocalStorage.getStore` from `js/require("node:async_hooks")` (`src/seon/db/internal.cljs:17-37`). | No registered counterpart. The host has dynamic `*agent-id*` and derives provenance from it (`src/seon/host/context.clj:59-79`), but does not expose the child function. | **pure relative to the invocation scope**; ambiguous if ambient-scope reads are classified as effects. |
| `seon.db/db` | Async `[]` or `[{:seon.db/database-name name}]`; resolves to a database value or flat error (`src/seon/db.cljs:793-803`). The argument map is closed. | Session descriptor cache, then `resolve-head`/`acquire-database` over UDS (`src/seon/db.cljs:723-758`). | No `db` wrapper. Host-only `head []` returns current head (`src/seon/host/context.clj:979-981`), synchronously, with nested host errors; it cannot select a database name. | **ambiguous: read-only/idempotent**. |
| `seon.db/as-of` | `[database point] -> database`, point is int or instant; synchronous (`src/seon/db.cljs:805-810`). | None: `assoc :as-of point :since nil`. | None needed; host omitted it. | **pure**. |
| `seon.db/since` | `[database point] -> database`, point is int or instant; synchronous (`src/seon/db.cljs:812-817`). | None: `assoc :since point :as-of nil`. | None needed; host omitted it. | **pure**. |
| `seon.db/history` | `[database] -> database`, synchronous (`src/seon/db.cljs:819-823`). | None: `assoc :history true`. | None needed; host omitted it. | **pure**. |
| `seon.db/cas-assert` | `[ref attr value] -> [:db.fn/cas ref attr value value]`, synchronous; no Malli declaration on the public var (`src/seon/db.cljs:825-826`). | None. | None. | **pure** transaction-data constructor. |
| `seon.db/transact!` | Async variadic surface: `[transaction-data]`, `[closed request]`, or `[database transaction-data]`. The request requires `:seon.db/tx-data` and permits `:seon.db/db`, `:seon.db/expected-db`, `:seon.db/tx-meta`, `:seon.db/opts`, and managed generated candidates (`src/seon/db.cljs:71-80,909-947`). Success is the raw transaction report; failure is flat. | Pure normalization/validation and EDN-slot encoding, then one protocol transaction request and multiplexed UDS call (`src/seon/db.cljs:830-907`). Ambiguous delivery retries retain one request ID. | Registered as synchronous `[request]` only (`src/seon/host/context.clj:976-978`). It accepts raw tx-data or a map containing only tx-data plus optional `:seon.capability/op-id`; maps op-id to protocol request ID; queries a durable receipt when caller-supplied; and stamps provenance (`src/seon/host/context.clj:732-817`). Detailed drift is below. | **external, receipt-idempotent only when the same request/op ID survives replay**. Separate child calls mint separate IDs. |
| `seon.db/query` | Async `[request-or-query & inputs]`. A request is recognized only when it is a map containing `:seon.db/query` and no extra inputs; otherwise the first value is the query and remaining values become `:seon.db/args` (`src/seon/db.cljs:990-1003`). Request options: optional database and max work/results/result weight (`src/seon/db.cljs:93-101`). Result is the Datalog result or flat error. | Builds a pure protocol request with attribution/resource caps, then UDS; result selection occurs after the response (`src/seon/db.cljs:954-988`). | Synchronous `[query-form & arguments]`, always resolves current head (`src/seon/host/context.clj:706-712,966-968`). It has no request-map form, explicit database, or resource options. | **ambiguous: read-only/idempotent**. |
| `seon.db/query-with-evidence` | Async `[request]`; implementation expects the same query request map and returns result plus dependency plan, attribute dependencies, cache evidence, and resource evidence, or a flat error (`src/seon/db.cljs:1005-1016`). The public var currently has no Malli schema. | Same query protocol/UDS choke point as `query`. | Synchronous `[query-form & arguments]`, not `[request]` (`src/seon/host/context.clj:679-704,969-972`). It returns the same evidence-key selection on success, but cannot carry the child options/database and nests errors. | **ambiguous: read-only/idempotent**. |
| `seon.db/pull` | Async `[request]`, `[selector entity-id]`, or `[database selector entity-id]`. Request requires pull pattern and ref; optional database and resource caps (`src/seon/db.cljs:102-110,1018-1052`). Returns ordinary entity data or flat error. | Protocol pull request plus UDS; records read evidence (`src/seon/db.cljs:1027-1046`). | Synchronous `[selector entity-id]` only, current head only (`src/seon/host/context.clj:714-730,973-975`). | **ambiguous: read-only/idempotent**. |
| `seon.db/pull-many` | Async `[request]`, `[selector entity-ids]`, or `[database selector entity-ids]`; request replaces ref with `:seon.db/refs` and carries the same resource options (`src/seon/db.cljs:111-119,1054-1081`). Returns eager maps in input order or flat error. | Protocol pull-many request plus the same UDS/read-evidence path. | None. | **ambiguous: read-only/idempotent**. |
| `seon.db/entity` | Async `[request-or-id]` or `[database id]`. A one-arg map containing `:seon.db/ref` is a request; otherwise it delegates to `pull '[*]` (`src/seon/db.cljs:1083-1091`). Options therefore match pull/entity request keys at `src/seon/db.cljs:120-127`. | Same pull/UDS leaf. | None. | **ambiguous: read-only/idempotent**. |
| `seon.db/installed-schema` | Async `[]` or `[request-or-database]`; a database value becomes `{:seon.db/db database}`. Returns installed schema or flat error (`src/seon/db.cljs:1093-1106`). | Protocol schema request over UDS. | None. | **ambiguous: read-only/idempotent**. |
| `seon.db/execute-many` | Async `[request]`; requires `:seon.db/members`, optional one shared database and max result weight (`src/seon/db.cljs:128-133,1108-1162`). It rejects mixed databases and returns `{:seon.db/db database :seon.db/results results}` or flat error. | Pure member attribution/one-database validation, one protocol execute-many request over UDS, then per-member evidence recording. | None. | **ambiguous: read-only/idempotent** for the present bounded read members; the protocol member vocabulary must prevent a future write from inheriting this class accidentally. |
| `seon.db/index-page` | Async `[request]` or `[database options]`; requires index, direction, and limit; optional prefix components, cursor, max result weight, and database (`src/seon/db.cljs:134-143,1164-1194`). Returns datoms, complete flag, and cursor or flat error. | Protocol index-page request over UDS. | None. | **ambiguous: read-only/idempotent**. |

### The one child session and platform leaf

The child does not own a Datahike connection or a local Datahike replica. It
owns ordinary database descriptors and one transport session:

- `!session` is process-local session state (`src/seon/db.cljs:181`); cached
  database descriptors live under `:seon.db/databases`, and response events
  advance that cache (`src/seon/db.cljs:276-280,341-366`).
- `connect-selection!` opens `seon.db.transport.uds/connect!`, negotiates
  protocol capabilities, ensures/acquires the selected database, and caches the
  returned descriptor (`src/seon/db.cljs:495-607`). `open-session!` owns the
  transition (`src/seon/db.cljs:609-638`); `send-request!` is the one call path
  (`src/seon/db.cljs:672-677`).
- The CLJS transport is Transit/framing/request correlation above Bun's native
  UDS socket. Native writes are `socket.write` (`src/seon/db/transport/uds.cljs:402-431`);
  the handler responds to open/data/drain/error/end/close and calls
  `Bun.connect` with a Unix socket path (`src/seon/db/transport/uds.cljs:639-682`);
  `request!` is the Promise-returning transport leaf
  (`src/seon/db/transport/uds.cljs:684-701`).
- The JVM side uses the same Transit JSON and bounded length-prefixed frame
  contract (`src/seon/db/transport/uds.cljc:210-293`), but its native leaf is
  `SocketChannel.open(StandardProtocolFamily/UNIX)` and `.connect`
  (`src/seon/db/transport/uds.cljc:295-302`). One synchronous request is
  `write-frame!` followed by `read-frame` through channel streams
  (`src/seon/db/transport/uds.cljc:355-375`).

That makes the natural capability leaf a same-source protocol operation over
an injected session/call implementation. `Bun.connect` versus JVM
`SocketChannel` and Promise versus blocking return remain platform leaves; the
operation and its exact data contract do not fork.

### JVM writer-session mechanics and exact drift

The current host is no longer a single retained channel. `writer-session`
builds a lazy retained **connection pool** (`src/seon/host/context.clj:192-235`),
opens/adopts members with the UDS handshake (`src/seon/host/context.clj:322-371`),
leases one member per roundtrip, and evicts/replaces failed members
(`src/seon/host/context.clj:237-447`). `writer-call!` performs a blocking call on
the executor and retries/replaces as needed (`src/seon/host/context.clj:470-511,579-622`).

Host provenance is selected from dynamic agent/user/process state
(`src/seon/host/context.clj:59-79`). Reads merge it into protocol requests at
`src/seon/host/context.clj:692-696,723-727`; transaction metadata is stamped at
`src/seon/host/context.clj:799-807`. A transaction's
`:seon.capability/op-id` becomes `:seon.db.protocol/request-id`
(`src/seon/host/context.clj:779-805`), so the writer's existing receipt datom is
the idempotency record (`src/seon/host/context.clj:732-754`).

The wrapper registry entries at `src/seon/host/context.clj:947-981` are
superseded architecture, but they expose the concrete bugs THE SEAM must delete:

| Function | Exact drift from child contract |
|---|---|
| `db` versus host-only `head` | Different symbol and arity. Child `db` is async and may select/acquire by database name; host `head` is synchronous and current-database-only. Host failures are nested `{:seon/error {...}}` through `protocol-error-value` (`src/seon/host/context.clj:624-636`), while child failures are flat. |
| `query` | Child accepts a closed request map **or** query plus variadic inputs; host accepts only query plus variadic arguments. Host always resolves current head, discarding explicit database and max-work/max-results/max-result-weight. Async versus sync also differs. |
| `query-with-evidence` | Child is `[request]`; host is `[query-form & arguments]`. It has the same missing database/resource options and error nesting. The child public var also lacks its otherwise expected Malli declaration. |
| `pull` | Child has request, two-arg, and database-three-arg forms plus resource caps; host exposes only `[selector entity-id]` at current head. Async versus sync and error nesting differ. |
| `transact!` accepted input | Child accepts raw tx-data, the complete closed request, or database plus tx-data. Host accepts one raw/request value only. For a map it reads only tx-data and host-only op-id; it discards/does not support child `db`, `expected-db`, `tx-meta`, `opts`, and generated candidates. Host op-id is absent from the child public schema. |
| `transact!` success | Child returns full `db-before`, `db-after`, `tx-data`, `tempids`, `tx-meta`, optional generated entity IDs, and recovered flag (`src/seon/db.cljs:892-907`). Host returns `{:seon.db/ok? true :seon.capability/op-id ... :db-after <three selected fields> :tempids ...}` and optional `:seon.capability/replayed?` (`src/seon/host/context.clj:756-817`). |
| `transact!` failure | Child catches to a flat `:seon.error/*` map (`src/seon/db.cljs:943-947`). Host nests under `:seon/error` (`src/seon/host/context.clj:624-636`). An unused child helper still constructs a third shape, `{::db/ok? false ::db/error <flat>}` (`src/seon/db/internal.cljs:578-585`); it is platform residue/drift, not the public contract. |
| Omitted surface | Host has no current counterpart for current-agent-id, as-of, since, history, cas-assert, pull-many, entity, installed-schema, execute-many, or index-page. Pure functions should not need a host call, but they must exist from shared source/artifact. The remaining effects need the same operation door rather than bespoke wrappers. |

Host-internal helpers contain useful protocol mechanics but are not substitute
agent contracts. `query-writer-at!` already accepts an explicit immutable
database value (`src/seon/host/context.clj:1503-1519`), and
`transact-writer!` has current-head and explicit-database forms
(`src/seon/host/context.clj:1778-1790`). They demonstrate that the protocol can
preserve the child contract; the drift is in wrapper shaping.

### Portable database logic above the leaf

Candidate same-source `.cljc` logic already has a clear boundary:

- `seon.db.protocol` owns database values, operations, request/response
  constructors, validation, and vocabulary as pure data. The constructors in
  `src/seon/db/protocol.cljc:1378-1500` are the existing shared contract.
- `seon.db.internal` owns Malli-to-Datahike schema transformations,
  transaction normalization/validation, ref coercion, provenance selection,
  and EDN-slot encoding. EDN-slot encoding is pure
  (`src/seon/db/internal.cljs:309-329`); transaction normalization begins at
  `src/seon/db/internal.cljs:331`; provenance selection is pure data at
  `src/seon/db/internal.cljs:561-576` once ambient values are passed in.
- There is **one decode boundary**: `decode-edn-value`/`decode-edn-values`
  reconstruct EDN unions, component children, and set-valued cardinality-many
  attributes from the schema registry (`src/seon/db.cljs:1427-1461`). The
  registry-derived predicates are `src/seon/db/internal.cljs:233-307`. This
  belongs above the platform leaf and must not be duplicated by a host
  response-shaper.
- Request selection, resource cap merging, read attribution, response
  selection, and transaction normalization are portable orchestration. The
  process session atom, AsyncLocalStorage, clocks/timers, Bun socket, and JVM
  channel/pool are platform residue.

## Blob

No blob function is registered in the JVM host today. Blob effects already
decompose into filesystem publication plus the database seam, so a separate
blob transport would duplicate two existing mechanisms.

| Agent-facing function | Exact child call shape and result | Present platform leaf | Effect class |
|---|---|---|---|
| `my.blob/put!` | Async `[{:my.blob/content string, optional :my.blob/media keyword}]`; returns `{:my.blob/ok? boolean :my.blob/hash hash, optional :my.blob/tokens/error}` (`src/my/blob.cljs:125-135,284-305`). | Content hash, overlay lookup, durable filesystem publication, then `seon.db/transact!` projection (`src/my/blob/internal.cljs:233-277`). | **idempotent publication** by content hash, provided the database upsert retains the same identity. |
| `my.blob/get` | Sync `[{:my.blob/hash hash}]`; returns ok/hash/content/tokens or ok/hash/error (`src/my/blob.cljs:144-153,307-334`). | `existsSync` and `readFileSync`, with SHA verification (`src/my/blob/internal.cljs:91-145`). | **ambiguous: read-only/idempotent**. |
| `my.blob/concat!` | Async `[{:my.blob/hashes [hash ...], optional :my.blob/media keyword}]`; same response as `put!` (`src/my/blob.cljs:137-142,336-359`). | Reuses `get`, then the exact `put!` publication choke point. | **idempotent publication** for the same ordered inputs. |
| `my.blob/text` | Async closed request with hash, optional one-based from-line/max-lines, and optional database; returns bounded page metadata or a not-text/error envelope (`src/my/blob.cljs:155-174,363-402`). | Filesystem `get`; only the binary-media branch uses blob `stat`, which bottoms out in database reads. | **ambiguous: read-only/idempotent**. |
| `my.blob/stat` | Async closed request with hash and optional database; returns ok/hash/exists plus optional tokens/media/time/error (`src/my/blob.cljs:176-189,404-454`). | `seon.db/db` plus `seon.db/query`; explicitly no disk read. | **ambiguous: read-only/idempotent**; served wholly by the database seam. |

The durable publication residue is precise: Node `fsyncSync`, `renameSync`,
`openSync`, `closeSync`, `mkdirSync`, `writeFileSync`, `existsSync`, and
`unlinkSync`, plus `node:path` and `crypto.randomUUID`
(`src/my/blob/internal.cljs:147-231`). `node-publication-effects` already makes
fsync, rename, and transact injectable (`src/my/blob/internal.cljs:147-150`),
although other Node calls remain direct.

Above it, validation, path planning given a path algebra, SHA verification,
overlay selection, line paging, text sniffing, concatenation, token estimation,
and response shaping are portable. SHA-256 already has the correct same-source
owner in `src/seon/content_hash.cljc:15-26`; filesystem's separate SHA
implementation should use that same mechanism rather than become another seam
operation.

## Message and lifecycle intersections

These are P2 families, so only their P1 effect intersections are inventoried.
Neither needs a message/lifecycle platform channel: durable behavior funnels
through database operations; ephemeral hosting funnels through the existing
runtime/subprocess-like process owner.

| Agent-facing function | Call shape and result | Effect choke point | Effect class |
|---|---|---|---|
| `seon.agent.message/user` | Async `[content-string] -> message-response` (`src/seon/agent/message.cljs:507-518`). Success is message ID plus hops; failure is direct `:seon.error/message` (`src/seon/agent/message.cljs:236-245`). | Thin wrapper over `message!`; current agent from ALS, database acquisition/reads, managed-id allocation, then one transaction (`src/seon/agent/message.cljs:398-493`). | **external**; generated message identity means a fresh replay is a new message unless the seam supplies the same operation identity. |
| `seon.agent.message/agent` | Async `[agent-id-string content-string] -> same message-response` (`src/seon/agent/message.cljs:520-537`). | Same single `message!` database choke point. | **external**. |
| `seon.agent.lifecycle/wait` | Async `[note-string] -> derive state or direct error`; success `:idle` (`src/seon/agent/lifecycle.cljs:219-243`). | Current-agent ALS, database read/current-run, transaction with `js/Date`. | **external** durable lifecycle transition. |
| `seon.agent.lifecycle/complete` | Async `[result-string]` or `[result-string result-ref]`; same lifecycle result (`src/seon/agent/lifecycle.cljs:389-397`). | Database queries/pulls, optional message transaction and ID allocation, one fenced transaction, `js/Date` (`src/seon/agent/lifecycle.cljs:252-380`). | **external**; stale-basis retry is internal, but replay after commit needs the transaction receipt identity. |
| `seon.agent.lifecycle/pause` | Async `[]` or `[{:seon.agent/id optional}]`; returns `:paused` or direct error (`src/seon/agent/lifecycle.cljs:399-433`). | Authorization reads plus `run/pause!`, which is database-backed. | **external**. |
| `seon.agent.lifecycle/resume` | Async `[]` or the same optional-target map; returns `:running` or direct error (`src/seon/agent/lifecycle.cljs:435-475`). | Database-backed run resume plus admission/process-local drive state. | **external**; durable transition and ephemeral scheduling compose. |
| `seon.agent.lifecycle/terminate` | Async `[agent-id]`; returns `:terminated` or direct error (`src/seon/agent/lifecycle.cljs:515-523`). | Database reads and one CAS/retract/close transaction with `js/Date`; repeated already-terminated reads return `:terminated` (`src/seon/agent/lifecycle.cljs:477-513`). | **idempotent external** at the domain level. |

`message!`'s complete closed map additionally accepts optional explicit database,
from/to refs, and `:core` origin (`src/seon/agent/message.cljs:217-234`); its
portable transaction builder and participant/hop validation should remain above
the database capability. The platform residue is ALS, clock acquisition, and
runtime admission—not a message transport.

## Filesystem

There are no JVM-host filesystem counterparts today. Every entry is synchronous
in the child. Moving it behind THE SEAM must preserve that source call shape;
the JVM leaf may block while a Bun leaf may remain synchronous or return an
awaited value according to the owner ruling.

All ordinary filesystem responses use family-specific `:seon.agent.fs/ok?`,
path, optional data, denial, and error fields (`src/seon/agent/fs.cljs:76-165`).
Anchored edits use the distinct exact union at
`src/seon/agent/fs.cljs:265-281`.

| Agent-facing function | Exact child call shape and result | Present platform leaf | Effect class |
|---|---|---|---|
| `seon.agent.fs/grants` | Sync `[] -> {allowed-roots, read-only?, locked?}` (`src/seon/agent/fs.cljs:314-334`). | Process-local config atom plus live environment reads; path-list delimiter from `node:path` (`src/seon/agent/fs/internal.cljs:52-79`). | **ambiguous: read-only/idempotent**. |
| `read-file` | Sync `[{:path required, :encoding?, :from-line?, :max-lines?}]`; read response includes content/page totals/file SHA or denial/error (`src/seon/agent/fs.cljs:76-93,340-375`). | `node:fs.readFileSync`; SHA currently `node:crypto`. | **ambiguous: read-only/idempotent**. |
| `write-file` | Sync `[{:path, :content, :encoding?}]`; ok/path or denial/error (`src/seon/agent/fs.cljs:95-106,391-410`). | Clojure syntax gate above `node:fs.writeFileSync`. | **external** overwrite; byte-identical repeats converge but operation identity is not recorded. |
| `edit-file` | Sync one map: path plus either from/to/content line mode or old-string/new-string exact mode, optional encoding; returns edit metadata or denial/error (`src/seon/agent/fs.cljs:117-139,439-495`). | `readFileSync` then `writeFileSync`. | **external**, generally non-idempotent. |
| `list-dir` | Sync `[{:path}]`; entries or denial/error (`src/seon/agent/fs.cljs:141-151,497-510`). | `readdirSync`. | **ambiguous: read-only/idempotent**. |
| `stat` | Sync `[{:path}]`; dir/file/mtime or denial/error (`src/seon/agent/fs.cljs:153-165,512-527`). | `statSync`. | **ambiguous: read-only/idempotent**. |
| `file-exists?` | Sync `[{:path}] -> boolean` (`src/seon/agent/fs.cljs:529-535`). | Reuses `stat`; collapses denial/error/missing to false. | **ambiguous: read-only/idempotent**. |
| `home-dir` | Sync `[] -> nonblank string` (`src/seon/agent/fs.cljs:537-546`). | Reads `HOME`/`USERPROFILE` from the environment. Current implementation throws when absent, unlike the family error envelopes. | **ambiguous: read-only/idempotent**; call-shape bug requires an owner ruling before seam freezing. |
| `walk-dir` | Sync map with path and optional extension/glob/skip-hidden/sort/max-results; returns bounded entries/count/truncation/hint or denial/error (`src/seon/agent/fs.cljs:181-199,548-606`). | Recursive `readdirSync`/`statSync`; optional mtime sort (`src/seon/agent/fs/internal.cljs:298-329`). | **ambiguous: read-only/idempotent**. |
| `view` | Sync map with path and optional from-line/max-lines/encoding; returns line-numbered bounded content, totals, and file SHA or denial/error (`src/seon/agent/fs.cljs:202-219,617-658`). | `readFileSync` plus SHA. | **ambiguous: read-only/idempotent**. |
| `replace!` | Sync map requiring path/find/replace; optional expected-count **xor** all?, near, file-sha, encoding; anchored success/failure union (`src/seon/agent/fs.cljs:237-251,719-783`). | `readFileSync`, pure match/fence/syntax logic, then `writeFileSync`. | **external**; file SHA and expected-count are concurrency fences, not a durable replay receipt. |
| `insert!` | Sync map requiring path/content and exactly one of after-line/before-line, optional encoding; same anchored envelope (`src/seon/agent/fs.cljs:253-281,785-840`). | `readFileSync`, pure insertion/syntax logic, then `writeFileSync`. | **external**, non-idempotent. |

Portable logic includes the closed request/response contracts, denial values,
path-scope decision given normalized paths and grants, paging, line edits,
syntax validation, glob compilation/matching, deterministic match cascade,
anchored edit response construction, and bounded walk policy. Platform residue
is `node:fs`, `node:path`, environment/config acquisition, and the process-local
grant atom (`src/seon/agent/fs/internal.cljs:1-109`). The SHA leaf at
`src/seon/agent/fs/internal.cljs:19-28` duplicates the blob/content-hash
primitive and is a clear fewer-mechanisms opportunity.

## Shell

There are no JVM-host shell counterparts. The exact native owner is already
centralized: `seon.subprocess` is documented as the single Bun-native boundary
(`src/seon/subprocess.cljs:1-6`), and its sole spawn leaf is `Bun.spawn`
(`src/seon/subprocess.cljs:18-19`). It owns stream readers, timers,
AbortSignal, process-group signals, output bounds, and resource sampling
(`src/seon/subprocess.cljs:71-259`).

| Agent-facing function | Exact child call shape and result | Present platform leaf | Effect class |
|---|---|---|---|
| `seon.agent.shell/grants` | Sync `[] -> {:seon.agent.shell/granted? boolean}` (`src/seon/agent/shell.cljs:207-215`). | Live environment grant read. | **ambiguous: read-only/idempotent**. |
| `run` | Async map requiring cmd; optional args/cwd/stdin/timeout-ms (`src/seon/agent/shell.cljs:38-44,217-301`). One response union: ran => exit/out/err/token counts/timeout/truncation/optional hint/testrun; could-not-run => ok false plus `:seon.error/*` (`src/seon/agent/shell.cljs:54-77`). | `seon.subprocess/run!` -> `start!` -> `Bun.spawn`; may additionally persist a parsed testrun through the database (`src/seon/agent/shell.cljs:265-298`). | **external**, non-idempotent. |
| `py-run` | Async map requiring source; optional interpreter cmd, args, cwd, timeout; same run response (`src/seon/agent/shell.cljs:46-52,303-329`). | Pure specialization into `run`, source passed as stdin. | **external**. |
| `run-bg!` | Sync map requiring cmd; optional args/cwd/stdin; returns job id/state/cmd or shared job failure (`src/seon/agent/shell.cljs:94-115,336-378`). | `seon.subprocess/start!`; stores child/control functions in process-local `!jobs` and registers completion callbacks (`src/seon/agent/shell/internal.cljs:254-309`). | **external**. |
| `list-jobs` | Sync `[]`; returns ok plus summaries scoped by current agent (`src/seon/agent/shell.cljs:404-418`). | Process-local `!jobs`, ALS agent ID, current time/token estimates. | **ambiguous: read-only/idempotent**. |
| `job-status` | Sync `[{:job-id}]`; success contains job state/cmd/runtime/output token counts/optional exit and testrun, else shared job failure (`src/seon/agent/shell.cljs:420-449`). | Process-local job table and clock. | **ambiguous: read-only/idempotent**. |
| `job-output` | Sync map with job-id and optional stream/since cursor; returns content, next cursor, tokens, truncation, runtime, optional exit, or job failure (`src/seon/agent/shell.cljs:137-157,451-481`). | Process-local bounded captured stream. | **ambiguous: read-only/idempotent**. |
| `job-stop!` | Sync `[{:job-id}]`; returns job ID/new state or shared failure (`src/seon/agent/shell.cljs:159-165,483-496`). | Stored child `kill!` -> process signal; current implementation sends SIGTERM only while running (`src/seon/agent/shell/internal.cljs:311-317`). | **idempotent external**. |

Authorization, argv shaping, cwd policy, Python specialization, error/run
envelopes, testrun parsing, output cursors, job summaries, and output bounds are
portable above the leaf. `Bun.spawn`, Web stream readers, timers, process
signals, resourceUsage, live child handles, clock values, and the process job
table are platform residue. This same subprocess capability can serve package
manager installation/reconciliation; packages do not need another spawn
mechanism.

## Web

There are no JVM-host web counterparts. All three functions currently compose
environment/config, DNS, Bun fetch, blob publication, and database projections.

| Agent-facing function | Exact child call shape and result | Present platform leaf | Effect class |
|---|---|---|---|
| `seon.agent.web/grants` | Sync `[request]`, a closed map with optional config; returns enabled/policy/allowed domains/search backend details (`src/seon/agent/web.cljs:21-72,165-189`). | Environment/config reads. | **ambiguous: read-only/idempotent**. |
| `fetch` | Async closed request requiring URL, optional config/timeout/max-preview/max-age; response union carries fetched/cached URL/status/content metadata, blob hash, bounded preview/links/tokens, or a family error (`src/seon/agent/web.cljs:73-129,236-342`). | URL parse, `Bun.dns.lookup`, AbortController/timer, `Bun.fetch`, streamed body; then `my.blob/put!` and best-effort `seon.db/transact!` (`src/seon/agent/web/internal.cljs:171-181,388-392,431-517`; `src/seon/agent/web.cljs:286-336`). | **external**. A GET is not pure, and the call also publishes blob/database state. |
| `search` | Async closed request requiring query, optional config/max-results/timeout; returns provider-independent results/count and optional answer/queries/error (`src/seon/agent/web.cljs:130-158,371-503`). | Gemini or Serper REST POST through the same Bun fetch function, AbortController/timer, JSON parse, then best-effort DB projection (`src/seon/agent/web/internal.cljs:632-675,728-770`). | **external**, never auto-replay without provider semantics. |

URL/policy checks after DNS results, redirect state machine, capped body
decoding, content classification/extraction, Markdown-link resolution, cache
query construction, Gemini/Serper request-data construction and response
parsing, preview/token calculations, and response envelopes are portable.
Platform residue is environment/config acquisition, URL/DNS implementation,
fetch, AbortController/timers, clocks, and streaming body objects. The HTTP
capability leaf can also serve provider SDK fetch injection and any audited
package boundary HTTP need; direct provider SDK object handling remains a
provider adapter concern above that lower transport leaf.

## Provider and LLM

The census's sole LEFT entry, `seon.ai/generate-code!`, is provider-backed but
is **not** the provider transport. It orchestrates a planning agent through
database/message/runtime mechanisms. The actual LLM effect choke is the
resolved `llm-fn` adapter selected inside the runtime.

| Entry/choke point | Exact call shape and result | Present platform leaf / host counterpart | Effect class |
|---|---|---|---|
| `seon.ai/generate-code!` | Async `[closed generate-request]`; required `:my.plan/goal`, optional description/expect/agent-id. Returns closed success `{ok? true, plan id, planner agent id}` or failure `{ok? false, error, optional plan id}` (`src/seon/ai/generate_code.cljs:91-106`; public var `src/seon/ai.cljs:899-924`). | `generate-code/start-generation!` launches/assigns a planner and records durable plan state (`src/seon/ai/generate_code.cljs:700-764`); its effects funnel through agent/runtime, message, and database capabilities. No host counterpart. | **external**. |
| `seon.ai.dispatch/llm-fn` and returned adapter | `llm-fn [] -> function`; the returned async function consumes one closed `:seon.ai/request` containing ctx, optional system prompt/stream?/AbortSignal, and required immutable config resolution (`src/seon/ai.cljs:641-647`; `src/seon/ai/dispatch.cljs:87-113`). Turn adapter result is text plus optional top-level provider error/raw/evidence. | Process registry atom selects adapter (`src/seon/ai/dispatch.cljs:35-57`). Host exposes only pure provider-locality/frontier-provider wrappers, not LLM invocation (`src/seon/host/context.clj:955-961`). | Function selection is **ambiguous: read-only/idempotent** over loaded process state; invocation is **external**. |
| `seon.ai.openai-compat/complete` | Async `[complete-request]`; request includes ctx and immutable config resolution plus optional system/model/temperature/max tokens/tools/tool choice/extra body/stream/AbortSignal. Result has `:seon.ai/text` and optional error/raw/usage/truncation/tool calls/provider identity/config evidence (`src/seon/ai/openai_compat.cljs:68-99,433-563`). Failures resolve as values. | Official `openai` SDK client (`src/seon/ai/openai_compat.cljs:351-360`), then `.stream` or `.create` (`src/seon/ai/openai_compat.cljs:517-552`). Default is SDK/Node native fetch; dynamic `*fetch*` is the existing injection seam (`src/seon/ai/openai_compat.cljs:342-349`). | **external**, never automatic replay. |
| `seon.ai.anthropic/complete` | Async one closed provider request with the same resolved authority and Anthropic options; returns text plus optional error/raw/usage/provider evidence, never rejection (`src/seon/ai/anthropic.cljs:68-100,318-391`). | Official `@anthropic-ai/sdk` client (`src/seon/ai/anthropic.cljs:300-316`), `.messages.stream`, then `.finalMessage` (`src/seon/ai/anthropic.cljs:367-383`). Default Node native fetch; same dynamic injection pattern. | **external**, never automatic replay. |

Portable logic includes immutable config resolution/provenance, credential
**source** selection once secrets are acquired, provider parameter mapping,
prompt construction, response/error parsing, usage normalization, streaming
form detection, and adapter result shaping. Platform residue is environment
secret acquisition, SDK construction, Node native fetch, AbortSignal/timers,
and SDK stream objects. A single HTTP/fetch capability can underlie both SDK
injection points and web transport; a provider-call capability remains useful
at the higher semantic boundary because SDK streams and provider request
identity are not ordinary web-fetch results.

## Packages and WP-K routing through the same door

Commit `19654064` added only the pure WP-K root. Current `seon.packages` has no
agent-facing effect and no host process:

- install request and ledger-row contracts are ordinary data
  (`src/seon/packages.cljc:21-47`);
- `row->host` derives Bun versus JVM from ecosystem attribute presence
  (`src/seon/packages.cljc:143-149`);
- `npm-manifest`, `deps-manifest`, and `installed` are pure projections
  (`src/seon/packages.cljc:261-325`); and
- `plan-install`/`plan-remove` return transaction data or steering errors
  without performing I/O (`src/seon/packages.cljc:327-409`).

Those functions stay above THE SEAM as same-source `.cljc`. The future WP-B/W
effects enter it as capability operations selected by `row->host`, not as
registry wrappers:

| Future operation named by grounded package design | Contract/effect | Same-door routing and class |
|---|---|---|
| install/update/remove reconcile | Take the admitted WP-K plan/complete ledger rows, derive byte-stable package.json or deps.edn, write the per-cluster manifest, run Bun install or tools.deps/JVM rebuild, launch/swap the generation, then transact pins/generation/served rows. Update is install-upsert; a converged plan is a no-op receipt (`src/seon/packages.cljc:327-409`; `docs/prds/sci-execution-runtime/research/packages-boundary-naming-flows-2026-07-21.md:409-418`). | Compose **filesystem + subprocess + database + package-host lifecycle** capabilities. **External, receipt-idempotent** only when op-id/generation survives retry; converged no-op is idempotent. |
| compiled package boundary invocation | Function symbol plus ordinary args; host returns execution result/error under the same execution envelope. Host selection is `row->host`: npm -> Bun artifact, deps -> JVM artifact. | One capability invocation door routes to the selected package-host leaf. **External** unless the compiled boundary declares a narrower class per function. The package coordinate must not assign the class wholesale. |
| `seon.packages.host/call` | Dev-gated module + export path + args (`docs/prds/sci-execution-runtime/research/packages-boundary-naming-flows-2026-07-21.md:374-382`). | Bun host dynamic module load/`js/require` and call. **External/ambiguous per target export**; generic exploration cannot honestly claim one replay class, so safest default is external. |
| `seon.handle/call` | Handle plus method/export path and ordinary args, recursively resolving tagged handle refs against generation. | Package-host handle table and producer object invocation. **External/ambiguous per operation**. |
| `seon.handle/describe` | Handle -> bounded ordinary description. | Read package-host handle table. **Ambiguous: read-only/idempotent**. |
| `seon.handle/subscribe` and `seon.handle/poll` | Subscribe creates a subscription handle; poll is a cursor-addressed bounded event read. Poll rides handle-call; disposal is ordinary handle disposal (`docs/prds/sci-execution-runtime/research/packages-boundary-naming-flows-2026-07-21.md:420-452`). | Host handle table plus bounded rings. Subscribe is **external**; cursor poll is **ambiguous: read-only/idempotent** and must preserve the cursor on retry. |
| `seon.handle/dispose!` | Handle -> disposal result; disposing target also disposes subscriptions. | Package-host handle table and producer cleanup. **idempotent external**. |

The package design already grounds the transport reuse: both package hosts use
the existing Transit/length-prefixed UDS codec, and the package host speaks the
same execution message contract
(`docs/prds/sci-execution-runtime/research/w6-package-host-design-2026-07-21.md:30-33`).
The refined WP-B scope names the generic op set, concurrent sessions, handle
table, and same UDS extension point
(`docs/prds/sci-execution-runtime/research/packages-boundary-naming-flows-2026-07-21.md:506-522`).
The seam therefore needs an enumerable operation descriptor containing target
host plus effect metadata; it does not need package-specific wrapper
provisioning.

## Fewer-mechanisms findings

| One mechanism | Families it can serve | Grounded consequence |
|---|---|---|
| Same-source operation/request/response schemas | All | `seon.db.protocol.cljc` and `seon.packages.cljc` prove the pattern. Call shape belongs once above platform dispatch; host and child must not reshape it. |
| UDS Transit/frame/session transport | Database, Bun/JVM package hosts, execution hosts | Bun and JVM already implement the same codec. Add enumerable operation routing rather than a protocol per family. |
| Database capability | Database, blob stat/projection, message, lifecycle, web cache/projection, provider config, package ledger | These consumers already funnel into `seon.db`; giving each a host wrapper would duplicate transaction identity, provenance, errors, and replay rules. |
| Filesystem capability | Filesystem, blob publication, package manifests/install roots | Blob requires stronger durable publication primitives than generic write-file, but both can share one platform fs leaf vocabulary rather than direct Node calls plus a second host transport. |
| Content hashing | Blob, filesystem edit fences, package/artifact digests | `seon.content-hash.cljc` is already the same-source owner. Filesystem's direct `node:crypto` hash is duplicate residue. |
| Subprocess capability | Shell and package install/host launch | `seon.subprocess` is already the one Bun-native owner. Package orchestration should call the same operation door with its own authorization and result interpretation. |
| HTTP/fetch leaf | Web fetch/search and provider SDK injection | Web directly owns Bun fetch; both provider SDKs already expose a fetch injection seam. Share transport acquisition while retaining provider-specific request/stream parsing above it. |
| Ambient invocation context | Database provenance, shell job ownership, messages, lifecycle, provider logging | `AsyncLocalStorage` is one platform service, not one capability per family. Pass explicit agent/tx context into portable logic; acquire it once at the leaf. |
| Clock/randomness | Database request IDs, blob temp names/timestamps, message/lifecycle times, jobs, web cache/timeout, package op IDs | Time and UUID generation are hidden effects today. An honest effect ledger either makes them leaf services or ensures the enclosing capability owns and records their values. |

## Seam-owner decisions exposed by the inventory

1. Resolve the three-class versus four-class replay taxonomy. Treating reads as
   pure is false; treating every repeat-safe read as idempotent may be workable,
   but it must be an explicit definition.
2. Freeze the **child call shapes**, not the JVM wrapper shapes. The latter are
   demonstrably lossy and inconsistent.
3. Decide whether ambient agent/transaction context is captured once into the
   operation envelope or acquired by each platform leaf. Portable code should
   receive ordinary values either way.
4. Make operation identity explicit enough that transaction/message/lifecycle,
   blob publication, and package reconcile replay the same operation instead of
   minting a new UUID after a crash.
5. Keep response shaping once. Database currently has flat child errors,
   nested host errors, and an unused third helper envelope; the seam must choose
   the public child contract and delete translations rather than support all
   three.
6. Preserve operation-level effect metadata for package boundary functions.
   `row->host` selects a platform; it cannot infer whether an arbitrary package
   function is pure, idempotent, or external.
7. Resolve `seon.agent.fs/home-dir` before freezing the filesystem contract: it
   claims a string but throws when the environment lacks a home path
   (`src/seon/agent/fs.cljs:537-546`).
8. Decide whether `cas-assert`, `query-with-evidence`, `pull-many`, `entity`,
   `installed-schema`, `execute-many`, and `index-page` receive explicit public
   schemas where absent. Their implementations and request contracts are clear,
   but same-source export generation should not infer them from bodies.

This inventory's shortest design falsifier is exact: if one same-source
capability definition cannot expose the child signatures above unchanged while
routing to Bun or JVM leaves and emitting one response/error shape, the
proposed seam has recreated the current wrapper drift.

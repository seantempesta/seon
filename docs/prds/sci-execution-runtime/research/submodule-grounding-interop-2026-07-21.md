---
type: research
status: active
tags: [research, architecture]
---

# Submodule grounding for cross-runtime interop — 2026-07-21

Eight reference submodules added and read for the package-host /
capability-handle design: `clojure`, `tools.deps`, `playwright`,
`pdf.js`, `cheerio`, `sheetjs`, `mammoth.js`, `sharp` (all shallow,
under `reference-code/`). Note: `sheetjs` uses the maintained
`https://git.sheetjs.com/sheetjs/sheetjs.git` mirror — the GitHub
repo's default branch is a relocation stub ("exit stage left",
commit 3f44ddd) with no source.

## a) JVM runtime lib addition — clojure.repl.deps + tools.deps

Source: `reference-code/clojure/src/clj/clojure/repl/deps.clj`,
`reference-code/tools.deps/src/main/clojure/clojure/tools/deps.clj`.

What can be ADDED at runtime:

- `add-libs` (deps.clj:35–57) takes a map of lib → coord, REPL-only
  (`(when-not *repl* (throw ...))`, deps.clj:40). It resolves the
  transitive set OUT OF PROCESS: `clojure.tools.deps.interop/invoke-tool`
  shells out to the Clojure CLI (`clojure -T:deps -`, interop.clj:60–62)
  invoking `clojure.tools.deps/resolve-added-libs`. The returned
  `:added` lib map's `:paths` (jars/dirs) are converted to URLs and
  pushed into the HIGHEST-level `DynamicClassLoader` via `.addURL`
  (deps.clj:22–33, 50–52). Requires a `DynamicClassLoader` ancestor on
  the context classloader chain, else `IllegalAccessError`
  (deps.clj:31–33). It then merges `:added` into the runtime basis atom
  and reloads `*data-readers*` (deps.clj:53–55).
- `add-lib` (deps.clj:59–83) is `add-libs` for one lib, optionally
  finding the latest coord via `clojure.tools.deps/find-latest-version`
  (tools.deps deps.clj:750–757: procurer types tried `:mvn` then
  `:git`).
- `sync-deps` (deps.clj:85–96) recomputes a basis from `deps.edn` via
  `clojure.tools.deps/create-basis` and add-libs the difference.

Version-conflict behavior with an already-loaded lib:

- `add-libs` FILTERS OUT any requested lib already present in the
  current basis `:libs` before doing anything (deps.clj:41–44); the
  `add-lib` docstring is explicit: "Libs already on the classpath are
  not updated" (deps.clj:62–63).
- Inside `resolve-added-libs` (tools.deps deps.clj:719–748) existing
  coords WIN: `combined (merge add-canon existing) ;; existing coords
  override!` (deps.clj:735). A requested coord whose resolved version
  differs from what resolution kept is only REPORTED in `:conflict`
  (deps.clj:740–744) — nothing is replaced or reloaded.

Removal/replacement is impossible, confirmed:

- `clojure.lang.DynamicClassLoader extends URLClassLoader` and exposes
  only `addURL` (reference-code/clojure/src/jvm/clojure/lang/
  DynamicClassLoader.java:24, 92–93). URLClassLoader has no removal
  API, and the JVM cannot unload classes while their loader is
  reachable. There is no remove/downgrade/replace function anywhere in
  `clojure.repl.deps` or `clojure.tools.deps`.
- Consequence for our design: on the JVM, package ADD is a safe
  incremental runtime operation; package CHANGE or REMOVAL means
  terminate + rebuild the classpath (new basis) + relaunch the package
  host process. The design should treat the JVM host's package set as
  append-only per process lifetime.

Correct tools.deps vocabulary (use these, not invented terms):

- **basis** / **runtime basis** — the merged deps edn map plus
  `:basis-config`, `:argmap`, `:libs`, `:classpath`,
  `:classpath-roots` (create-basis docstring, tools.deps
  deps.clj:657–693; clojure.java.basis ns docstring,
  clojure/src/clj/clojure/java/basis.clj:10–33). Held in an atom,
  `clojure.java.basis.impl/the-basis`, seeded from the
  `clojure.basis` system property (impl.clj:40–50).
- **lib map** — map of lib → coord chosen, the result of
  `resolve-deps` (deps.clj:377–388). Each coord gains `:paths` after
  download.
- **classpath map** and **classpath roots** — `:classpath` (path →
  source identifier `:lib-name`/`:path-key`) and `:classpath-roots`
  (vector of paths in classpath order) (basis.clj:31–33).
- **procurer** — the `mvn`/`git`/`local` acquisition config subset of
  the basis (deps.clj:46, resolve-added-libs docstring:728).
- **coord** and **lib** — per the deps reference; coords are
  procurer-specific maps.

## b) Playwright client/server protocol — the handle precedent

Source: `reference-code/playwright/packages/playwright-core/src/
client/connection.ts`, `client/channelOwner.ts`,
`server/dispatchers/dispatcher.ts`, `packages/protocol/src/
protocol.yml`, plus their own architecture doc
`.claude/skills/playwright-dev/library.md`.

Their exact terms:

- **Channel** — the typed per-object RPC interface, generated from
  `protocol.yml` into `channels.d.ts` (interfaces like `PageChannel`
  with commands, events, and an **initializer**). The client-side
  `_channel` is a JS Proxy that validates params and forwards to the
  connection (channelOwner.ts; library.md "How `_channel` works").
- **guid** — every remote object's identity, shared verbatim between
  the client `ChannelOwner._guid` and the server object/dispatcher
  (connection.ts:192–195; dispatcher.ts). Object references on the
  wire are `{ guid: "..." }`, resolved by validators back to live
  channels (connection.ts:301–311 `_tChannelImplFromWire`).
- **ChannelOwner** — client-side proxy object base class: `_guid`,
  `_type`, `_parent`, child `_objects`, `_initializer`
  (connection.ts:56–71 registers a factory per type; library.md).
- **Dispatcher** / **DispatcherConnection** — server side. A
  Dispatcher wraps one `SdkObject`, translates protocol methods to
  server calls, and owns child dispatchers; `DispatcherConnection`
  maps guid → dispatcher and object → dispatcher (1:1)
  (dispatcher.ts; library.md "Dispatcher Layer").
- **Wire format** — call `{ id, guid, method, params, metadata }`;
  response `{ id, result }` or `{ id, error, log }`; event
  `{ guid, method, params }`; lifecycle `{ guid, method:
  '__create__'|'__adopt__'|'__dispose__', params }`
  (connection.ts:234–289 `dispatch`).

Long-lived remote objects, events, disposal:

- CREATION is server-pushed: the server instantiates a dispatcher and
  sends `__create__` with `{ type, guid, initializer }` to a PARENT
  guid; the client factory builds the typed proxy under that parent
  (connection.ts:265–267, 313–323). Objects form a tree; `__adopt__`
  reparents (connection.ts:274–280); `__dispose__` recursively
  disposes a subtree with a `reason` (connection.ts:282–285;
  dispatcher.ts:121, 223–224).
- EVENTS are plain `{ guid, method, params }` messages emitted on the
  object's channel after schema validation (connection.ts:287–288).
  Subscription is lazy: `_eventToSubscriptionMapping` +
  `updateSubscription(event, enabled)` — the server only sends events
  someone listens to (channelOwner.ts:45, 69–109).
- LEAK CONTROL: dispatchers are bucketed by `_gcBucket` with per-bucket
  caps (JSHandle/ElementHandle 100k, default 10k); exceeding the cap
  disposes the oldest 10% and marks the client object `_wasCollected`;
  later calls on it fail with "The object has been collected to
  prevent unbounded heap growth." (dispatcher.ts:41–46, 84, 146,
  281–296; connection.ts:185–186).
- FAILURE: closing the connection rejects every pending callback with
  one `TargetClosedError` carrying a cause (connection.ts:291–299).
  Cancellation is client-initiated `__abort__` keyed by call id
  (connection.ts:210–217).

Concrete suggestions for our capability handles (see "better seams").

## c) The five JS libraries — API shape and handle needs

**cheerio** (`reference-code/cheerio/src/load.ts`, `cheerio.ts`) —
pure data-in/data-out. `load(html)` parses once (parse5 or
htmlparser2) and returns a `CheerioAPI` querying function closed over
an in-memory DOM; selections (`Cheerio<T>`, cheerio.ts:37) are
ordinary immutable-ish arraylike values; output is `.html()`/
`.text()`/`.toArray()` strings and data. No I/O, no native code, no
worker. Transit-friendly as a single call (html string in, extracted
data out); a handle is only worth it if an agent iteratively queries
one large document — and even then it is a pure cache, not a
stateful resource.

**pdf.js** (`reference-code/pdf.js/src/display/api.js`) — stateful,
worker-based proxy objects, structurally a mini-Playwright.
`getDocument(src)` (api.js:234) returns a loading task; results are
`PDFDocumentProxy` (api.js:675) and `PDFPageProxy` (api.js:1318),
which proxy over a `MessageHandler` port to a `PDFWorker`
(api.js:2056; worker selection via `GlobalWorkerOptions.workerSrc`,
api.js:357, 2077). Rendering is incremental and page-at-a-time, and
documents/pages must be explicitly `destroy()`ed. Pure JS (plus
optional wasm decoders) — no native risk. For text extraction the
whole thing can be wrapped as one transit call (bytes → pages of
text), which is the shape we should default to; interactive
rendering would need a real handle with dispose.

**sheetjs** (`reference-code/sheetjs`, git.sheetjs.com mirror,
v0.20.3, `package.json` main `xlsx.js`) — pure data-in/data-out.
`XLSX.read(data, opts)` → a plain workbook object (`Sheets`,
`SheetNames`); `sheet_to_json`/`json_to_sheet` utilities; `write`
returns bytes. No worker, no native code, no persistent state.
Ideal single-call transit citizen: bytes in, EDN-able rows out. No
handle needed; large workbooks go through blob refs like any big
value.

**mammoth.js** (`reference-code/mammoth.js/lib/index.js`) — pure
promise-based conversion. `convertToHtml`/`convertToMarkdown`/
`extractRawText` (index.js:12–20) open the docx zip and return a
`Result` value carrying `value` plus a `messages` array of
warnings (index.js:32–40 pipeline). No native code, no state
between calls. Single-call transit-friendly; the `messages` array
maps naturally onto our envelope's non-fatal diagnostics rather
than being dropped.

**sharp** (`reference-code/sharp/lib/constructor.mjs`, `src/*.cc`)
— chained-builder object over a NATIVE libvips pipeline. The `Sharp`
constructor implements `stream.Duplex` (constructor.mjs:27) and
defers work to C++ (`pipeline.cc` etc.); binaries come from
platform-specific `@img/sharp-libvips-*` prebuilds
(package.json:169–175). Native risk is real: a libvips crash or OOM
takes down the hosting process, and prebuild availability is
platform-dependent (Bun compatibility of the N-API addon must be
verified before provisioning). Despite the stateful builder API,
each operation is one-shot: build chain → `toBuffer()`. Wrap it as
single-call transit (input bytes + declarative op list → output
bytes + info), never as a long-lived handle; and because of the
native risk, sharp is the strongest argument for keeping the
capability host a SEPARATE crashable process rather than in-pod.

## d) Bun transport and package-dir naming

Source: `reference-code/bun/docs/runtime/child-process.mdx`,
`docs/guides/process/ipc.mdx`, `docs/pm/global-cache.mdx`,
`docs/pm/isolated-installs.mdx`.

- `Bun.spawn` IPC: an `ipc(message, subprocess)` callback plus
  `subprocess.send()` / `process.send()`, i.e. the Node
  `child_process.fork` contract. `serialization: "advanced"`
  (default) uses the JSC `serialize` structured-clone wire — but it
  only works bun↔bun; bun↔node must drop to `serialization: "json"`
  (child-process.mdx:281–307). No ownership transfer of objects.
- Verdict for pod↔package-host transport: nothing better than our
  UDS+transit. Bun IPC is parent↔child only (no reconnect after a
  host restart, no third-party client like the JVM writer), its
  `advanced` wire is engine-specific, and `json` is strictly weaker
  than transit (no keywords, no proper tagged values). Our
  length-prefixed transit over UDS through the one
  `seon.db.transport.uds` codec stays: it is
  runtime-neutral, reconnectable, and already the writer protocol.
  The only thing Bun IPC offers that UDS lacks — automatic channel
  teardown with the child's lifetime — we already get from the
  supervisor owning the process.
- Honest naming from bun's package manager: the **global cache** at
  `~/.bun/install/cache` with `${name}@${version}` subdirectories
  (global-cache.mdx:6); per-project layout is `node_modules` under
  either the **hoisted** or **isolated** *linker* (`--linker
  isolated`), the isolated **store** living at
  `node_modules/.bun/<pkg>@<ver>/` with peer deps encoded in the
  store path (isolated-installs.mdx:29, 88, 116–120); file
  materialization is a **backend** (`hardlink`, `clonefile`,
  `copyfile`, `symlink`; global-cache.mdx:55–66). If U13's `:npm`
  provisioning documents these mechanisms, call them cache /
  linker / store / backend — bun has no concept called "install
  staging".

## Better seams found

Per the owner ruling these are reported, not silently adopted:

1. **Adopt Playwright's handle grammar for capability handles.** If
   any capability needs a long-lived remote object (pdf.js documents
   are the first real case), the proven shape is: guid identity +
   typed channel (schema per type: commands, events, initializer) +
   parentage tree + server-pushed `__create__`/`__dispose__` with a
   dispose REASON + explicit client abort keyed by call id. Our
   current envelope already covers commands; the deltas worth taking
   are (a) an `initializer` snapshot at create so the client renders
   state without a round-trip, (b) dispose-with-reason as a normal
   event so a handle death shows up as data, and (c) parent-scoped
   disposal so dropping one document frees its pages.
2. **Playwright's `_gcBucket` cap is the answer to handle leaks.**
   Per-type caps with oldest-10% collection and a client-visible
   "collected" error (dispatcher.ts:41–46, 281–296;
   connection.ts:185–186) is exactly the "nothing wedges" posture:
   agents that leak handles get steering errors, not a dead host.
3. **Playwright's lazy event subscription** (`updateSubscription`,
   channelOwner.ts:72–109) is the right default if capability events
   ever cross the UDS boundary: the host sends only what has a
   listener, which keeps the feed cost proportional to interest.
4. **JVM package set is append-only per process** — the grounded
   add-libs/DynamicClassLoader evidence (a above) means U13's :maven
   path should distinguish `install` (runtime `addURL`, cheap) from
   `change/remove` (declare new basis → relaunch JVM host). Trying to
   emulate removal in-process has no mechanism to build on.
5. **Default every document library to single-call transit.**
   cheerio/sheetjs/mammoth are pure value transforms; pdf.js text
   extraction can be wrapped as one; only interactive PDF rendering
   justifies a real handle. Handles should be the exception the
   capability schema forces you to declare, not the default wrapper
   shape.
6. **sharp mandates process isolation for native capabilities.**
   libvips prebuilds crash/OOM at native level; keep sharp (and any
   future native addon) in the crashable capability host, and verify
   the N-API addon actually loads under Bun before provisioning it —
   if it does not, sharp becomes the first case for a second host
   flavor (node child) behind the same UDS protocol, which the
   design's host abstraction already permits.
7. **SheetJS source-of-truth moved off GitHub** — any dependency
   ledger entry must pin the git.sheetjs.com mirror; the GitHub repo
   is a stub and will never advance.

---
type: research
status: active
tags: [research, agent, architecture]
---

# W6 package design — per-cluster packages, disposable package hosts, handles

Complete implementable design for W6 ([[../program-synthesis-2026-07-21]]
work-package series): the U13 install redesign (owner ruling 3), the two
disposable per-cluster package hosts (ruling 4), wrapper generation into
the U2 registry, and the `:seon.handle/*` surface (Remote-values-are-
handles addendum). Grounded in the live source and the vendored
dependency checkouts; ends with the dependency-ordered Codex
work-package cut and open owner decisions.

Prior evidence absorbed:
[[audit-benchmark-pkg-readiness-2026-07-21]] (the shared-root hazard
inventory, Option-B recommendation the rulings adopted) and
[[../../package-capabilities/roadmap]] (Phase 0/1 boundary, P1–P7
units).

## 0. Dependency ledger

| Dependency | Where read | What it settles |
|---|---|---|
| Clojure 1.12 add-lib | `reference-code/clojure/src/clj/clojure/repl/deps.clj` — `add-loader-url` (`deps.clj:22-33`: add-only via `DynamicClassLoader.addURL`, throws `IllegalAccessError` without a `DynamicClassLoader` parent), `add-libs` (`deps.clj:35-57`: requires `*repl*` true at `deps.clj:40`, resolves via `clojure.tools.deps/resolve-added-libs`, merges the result into the **basis** via `basis-impl/update-basis!` at `deps.clj:53`, reloads `*data-readers*`) | the anchor's Package-layout claims CONFIRMED: live adds are add-only classloader URLs; the resolution result is a basis. Two operational caveats the design must carry: (a) `add-libs` throws unless `*repl*` is bound true — the package host binds it around the live-add op; (b) resolution shells out to the `clojure` CLI (`tools.deps.interop/invoke-tool`, `interop.clj:60`: `["clojure" "-T:deps" "-"]`, minimum CLI 1.11.1.1347 enforced at `interop.clj:34-38`) — the CLI is a launch prerequisite of the JVM package host |
| tools.deps | `reference-code/tools.deps/src/main/clojure/clojure/tools/deps.clj` — `resolve-deps` (:377), `create-basis` (:657), `resolve-added-libs` (:719) | the launcher-side basis rebuild for change/remove (terminate + rebuild + relaunch) uses `create-basis` over the cluster `deps.edn`; no second resolver is written |
| bun install/cache | `reference-code/bun/docs/pm/global-cache.mdx:6` (global content-addressed cache at `~/.bun/install/cache`, `BUN_INSTALL_CACHE_DIR` override, `${name}@${version}` subdirs — multi-version safe); `docs/pm/lifecycle.mdx:13,35-63` (Bun is default-secure: lifecycle scripts run ONLY for `trustedDependencies`; a user-supplied list REPLACES the built-in list); `docs/pm/lockfile.mdx` (`bun.lock` text lockfile, `--frozen-lockfile`) | shared downloads are bun's own cache, no Seon cache layer; hostile-postinstall containment is bun's own default plus our config-fact trust gate |
| Bun UDS server | `reference-code/bun/packages/bun-types/bun.d.ts:6459-6469` (`UnixSocketOptions {unix: string}`), `:6506` (`Bun.listen(options: UnixSocketOptions)`); first-party client proof `src/seon/db/transport/uds.cljs:579,765` (`Bun.connect` with `"unix"`) | the Bun package host can LISTEN on a UDS socket natively; the missing piece is Seon-side (§2.2): `uds.cljs` today is client-only (`connect!` :272, `request!` :592, `connect-stream!` :623, `close-stream!` :776 — no listener) |
| the one UDS codec | `src/seon/db/transport/uds.cljc` — `encode`/`decode` (:196,:205, Transit JSON), `write-frame!`/`read-frame` (:213,:244, length-prefixed with `maximum-frame-bytes` ceiling), `connect!`/`call!` (:261,:270), read-handler extension point `transit-read-handlers` (:175); CLJS stream side `uds.cljs:623` | both package hosts reuse this codec verbatim; the `seon/handle` tagged type registers into the existing handler maps on both sides — extension by tagged types + new ops, never a protocol swap |
| execution message contract | `src/seon/host.clj:11-67` (docstring inventory: startup/invoke/cancel/shutdown → ready/result/error/stopped + value-sample; one active invocation per session; absolute `deadline-ms`; `result-limit-bytes`), schemas `:103-165`; the seam note `:57-60`: these schemas are the JVM projection of `seon.execution`'s contract, promoted to `.cljc` at W5 | the package hosts speak the SAME envelope; W6 adds no message kinds beyond new `function-symbol` values (§2.1) |
| host session/acceptor idiom | `src/seon/host.clj:1046-1108` (`start!`: writer session, UDS acceptor thread, thread-per-session), `:1122-1141` (`-main` prints `HOST READY <socket>` then parks) | the JVM package host reuses the acceptor/session shape; the READY line is the launcher's readiness probe |
| host writer channel | `src/seon/host/context.clj:182-230` (`writer-session` one retained channel + `call-lock`, reconnect-once `writer-call!`; W0.4 replaces the single channel with the proven pool) | the JVM package host does NOT open a writer channel (§2.1 — stateless, db-free); ledger writes happen on the sci host, which already owns one |
| U2 wrapper registry | `src/seon/host/context.clj:424-523` — `registry` (:424, process-local derived state, rebuilt by re-registration), `register-wrappers!` (:482, agent-authored corpus vars, lazily require-able in every live context via the shared `:load-fn` :511-523, re-register alters the shared var root so every context sees the upgrade), `register-host-wrappers!` (:497, `:sci/built-in` stamped), vars carry real `:arglists`/`:doc` (:470-476) | package wrappers are ordinary registry rows; epoch upgrade across a package-host swap is `register-*-wrappers!` re-registration — the mechanism exists, W6 writes zero new registry code |
| pod child supervision idiom | `src/seon/execution/host.cljs` — lane-keyed state `::children`/`::host-sessions` (:106-109), generation stamp on every entry, `spawn-child!` (:503), `connect-host-session!` (:563-640: session startup as first frame, ready deferred + timeout), `retire-child!` (:642-668), lazy `ensure-entry!` respawn-on-call (:670-685), tier routing by database fact `pull-eval-host-coordinate!` (:807-832 — presence query on `:seon.execution.host/eval-socket-path`) | the respawn-on-demand pattern (dead process → next call re-ensures) and coordinate-fact routing are the proven idioms the package-host client mirrors on the JVM side |
| supervisor scope | `bin/seon` → `script/seon/dev/cli.clj` + `process.clj` — exactly three process ids (`process.clj:25-29`: watcher/writer/pod), spec shape `:355-374` (argv, readiness keyword, ready-timeout, shutdown-grace, digests), explicit operations only (`:1487-1494` restart/retained-restart; no continuous respawn loop anywhere) | bin/seon is an operator, not a respawning daemon; "respawn-on-crash" therefore means client-driven lazy respawn (§2.4), with the supervisor owning reap/shutdown of recorded children. FINDING: `seon.host` itself has no process spec today — launched only by tests/manual `-main` (`host.clj:1122-1131`); §7 |
| cluster layout | `ls data/clusters/default/` → `db`, `blobs` only; `script/seon/dev/cluster.clj:40-59` (cluster-dir, process-dir `tmp/seon-clusters/<name>`, per-cluster log dir, disjoint-path validation via `launch.cljc`) | no package root exists anywhere yet; the coordinate must be added to the launch descriptor, not improvised (audit §5.2) |
| config-fact idiom | `src/seon/config.cljs:104-120` (`:seon.config.render/*` caps registered as `:seon.config/cap`); ruling 7 (named aero key → database fact at boot, agent-relevant limits render into context, rejections name their key) | the §1.4 key names follow the existing `:seon.config.<area>/<limit>` pattern; W1 owns the sweep, W6 lands the accessors |
| retained values / drill | `src/seon/host.clj` `retain-live-value!`/`serve-value-sample!` + `src/seon/render/value.cljc` (get-in/path browser), `result/{id}` binding | handles bind through the existing result-symbol path; the value-sample message already serves bounded projections of live host values — the handle summary rides it unchanged |
| deps.edn authority | `/Users/sean/src/seon/deps.edn` `:writer` alias (maintained Datahike/Konserve/Proximum closure, `-Xmx512m`, module flags) and the `:host` alias composition note | the JVM package host runs on its OWN minimal basis (cluster `deps.edn` + a thin Seon server slice), NOT the writer closure — third-party jars never share a process with Datahike or sci agent contexts |

Absent from `reference-code/`: nothing this design needs. The
add-lib/basis facts asserted in the anchor's Package-layout addendum are
fully grounded above (no mirror needed).

## 1. U13 install redesign

### 1.1 Per-cluster package roots

Ruling 3 layout, using each ecosystem's own manifest names (vocabulary
row: "packages/, package.json, deps.edn — never npm-pkgs"):

```
data/clusters/<name>/packages/
  npm/                     ; the live npm tree
    package.json
    bun.lock
    node_modules/
  npm-staging-<op-id>/     ; §1.3, exists only during an install
  deps.edn                 ; the JVM manifest
  deps-staging-<op-id>.edn ; §1.3

```

- The coordinate is added to the launch descriptor next to `db`/`blobs`
  in `cluster.clj:45-57` (`::launch/packages-dir`), covered by the same
  disjoint-path validation — never a string built at a call site.
- Shared downloads are the ecosystems' own native caches and nothing
  else: bun's global content-addressed cache (`global-cache.mdx:6` —
  `${name}@${version}` subdirs make concurrent multi-version installs
  from two clusters safe), `~/.m2/repository`, `~/.gitlibs`. Seon adds
  no cache layer and no shared resolution tree (package-capabilities
  ruling: resolution trees are not shared-safe).
- The repo-root `package.json`/`bun.lock`/`node_modules` remain
  build-input-only, mutated by humans/W9 — `my.packages/install` never
  touches them (the audit §2a takedown vector stays closed). W9's "root
  package.json reconciled with per-cluster package design" consumes
  this layout.

### 1.2 Ledger facts are the authority; manifests are derived

The durable record of "what this cluster has installed" is database
facts; the on-disk trees are rebuildable projections (derive-don't-
store, same shape as the graduation tier). A fresh checkout of a
cluster's `db` can regenerate `packages/` entirely from the ledger.

Attributes (registered in `src/seon/packages.cljc`, the shared pure
core — §2.1). No ecosystem `:type` stamp: attribute presence finds the
rows (npm rows carry npm attributes, deps rows carry deps attributes),
per the no-kinds rule.

- npm rows (npm's own words — a `package.json` dependencies entry plus
  its lock resolution):
  - `:seon.packages.npm/name` (identity within the cluster, e.g.
    `"cheerio"`),
  - `:seon.packages.npm/range` (the requested range as written into
    `package.json`),
  - `:seon.packages.npm/resolved` + `:seon.packages.npm/integrity`
    (from `bun.lock` after the staged install — the pin evidence).
- JVM rows (deps.edn's own words — a `:deps` entry):
  - `:seon.packages.deps/lib` (qualified symbol, e.g.
    `org.clojure/data.csv`),
  - `:seon.packages.deps/coord` (the coord map as canonical EDN
    string — `:mvn/version`, `:git/sha`, exactly as `deps.edn` holds
    it).
- One packages-root entity per cluster carries
  `:seon.packages/generation` (monotonic int) — bumped by every
  successful swap; the running package host echoes the generation it
  was launched from, and a mismatch is the honest staleness signal for
  handles (§4) and wrappers.
- Provenance is tx metadata (`:seon.db/user`/`:seon.db/process`), never
  copied attributes.
- Removal retracts the row; reconciliation regenerates the manifest
  without it and swaps (§1.3). The manifest generator is a pure
  function ledger-rows → `package.json` string / `deps.edn` string in
  `seon.packages` — one writer of each manifest, no hand edits to live
  trees.

### 1.3 Staged-then-atomic install

One flow for both ecosystems; every step is an errors-as-values
capability op with `:seon.capability/op-id` idempotency (the U2
receipt discipline — audit §2c):

1. **Gate** — the policy facts (§1.4) admit or reject the request with
   a steering error naming the config key.
2. **Record** — transact the ledger row(s) for the requested change
   (the request is now durable; a crash anywhere below is recoverable
   by re-running reconcile).
3. **Stage** — build the complete next tree beside the live one, never
   in it:
   - npm: copy `package.json`+`bun.lock` into
     `npm-staging-<op-id>/`, regenerate `package.json` from the
     ledger, run `bun install --cwd <staging>` as a bounded
     subprocess (deadline `:seon.config.packages/install-deadline-ms`).
     Lifecycle scripts stay off unless the package is named in the
     trust fact (§1.4) — bun's own default-secure posture
     (`lifecycle.mdx:13`) plus our explicit gate; note the
     REPLACES-not-extends semantics of `trustedDependencies`
     (`lifecycle.mdx:58-63`), so the generator writes the complete
     trusted list every time.
   - JVM: write `deps-staging-<op-id>.edn` from the ledger, prove it
     resolves by building a basis from it
     (`tools.deps/create-basis`, `deps.clj:657` — run in the install
     subprocess, not in any live host).
4. **Verify** — npm: a disposable `bun --cwd <staging> -e` probe
   requires each ledger package top-level export; JVM: the basis built
   in step 3 IS the verification (resolution failure = staged error,
   live tree untouched). A failed stage deletes the staging dir and
   returns the error value; the ledger row stays with the failure
   recorded on the op receipt — visible, re-runnable.
5. **Swap + relaunch** — quiesce the affected package host (§2.4 swap
   protocol: wrappers queue bounded-with-deadline), rename the live
   tree aside (`npm` → `npm-old-<op-id>`), rename staging into place,
   bump `:seon.packages/generation`, relaunch the stateless host,
   delete the old tree on ready. Rename is the atomic primitive; the
   boot-time recovery rule is deterministic: a present `npm-old-*`
   with a complete `npm/` deletes the old; a missing `npm/` with an
   `npm-old-*` rolls back. There is never a moment when a running
   process resolves from a half-written tree, because the ONLY reader
   of the tree is the package host and it is down for the rename
   (audit §2a.2's live-mutation hazard is closed structurally, not by
   care).

Live JVM **adds** (ruling: adds are live; change/remove = relaunch)
skip steps 3–5's relaunch: the running JVM package host executes
`clojure.repl.deps/add-libs` in-process — under `binding [*repl* true]`
(`deps.clj:40`), on its `clojure.main`-provided `DynamicClassLoader`
(`deps.clj:26-33`; the launcher always starts the host through
`clojure -M`, which guarantees the loader parent) — then the manifest
regenerates and the generation bumps without a swap. The next relaunch
converges on the manifest; add-only divergence between the live
classloader and `deps.edn` is bounded to one process lifetime by
design. The Bun side has no live-add: every npm change is a swap
(bun cannot add to a loaded module graph safely, and the host restart
is cheap by construction).

### 1.4 Config-fact policy keys (ruling 7)

Registered now as accessors; W1 sweeps them into the aero → `:seon.config`
fact path with the rest. Every rejection names its key.

| Key | Meaning | Default |
|---|---|---|
| `:seon.config.packages/policy` | `[:enum :closed :allowlist :open]` — may agents install at all | `:closed` |
| `:seon.config.packages/allowlist` | set of npm names + deps libs admitted under `:allowlist` | `#{}` |
| `:seon.config.packages/trusted-lifecycle-scripts` | npm packages whose lifecycle scripts may run (written verbatim into `trustedDependencies`) | `#{}` |
| `:seon.config.packages/install-deadline-ms` | bound on one staged install subprocess | hardware-scaled |
| `:seon.config.packages/max-rows` | ledger cap per cluster; overflow rejects with steering | sized for throughput |
| `:seon.config.packages.host/sessions` | client session pool size per package host | small (2–4) |
| `:seon.config.packages.host/call-deadline-ms` | default per-call deadline when the invoke supplies none | modest |
| `:seon.config.packages.host/ready-timeout-ms` | spawn → READY bound | |
| `:seon.config.packages.host/respawn-backoff-ms` | floor between respawns of a crashing host | |
| `:seon.config.packages.host/swap-queue-deadline-ms` | how long wrapper calls queue across a swap before erroring | |
| `:seon.config.packages.host/jvm-heap-mb` | JVM package host `-Xmx`, hardware-computed default | |
| `:seon.config.handle/per-channel-cap` | live handles per channel before oldest-collection (§4) | |
| `:seon.config.handle/summary-token-cap` | handle summary budget via `seon.ai.tokens/estimate` | |

### 1.5 The capability surface

`my.packages/install`, `my.packages/remove`, `my.packages/installed`
(registered host wrappers; agent-facing, map-in/map-out, errors as
values). `installed` is a derived read over the ledger + generation —
never a stored census. The U13 name `my.pkg/install`
(`roadmap.md:36`) is superseded by the vocabulary rule (the
directory's own word, no abbreviation) — owner decision 1 confirms.
C2's admission guard steering ("js-form attempts → the capability")
keeps pointing here unchanged.

## 2. The two disposable package hosts

### 2.1 The shared shape

One design, two platform implementations of the same namespace:
`seon.packages.host` — `src/seon/packages/host.clj` (JVM) and
`src/seon/packages/host.cljs` (Bun). Shared pure mechanics — op
schemas, handle-table transitions, manifest generation, ledger
attributes — live in `src/seon/packages.cljc`. Per the
key-namespaces ruling this is decided by placing the functions first:
the serving functions live in `seon.packages.host`, the pure
package/ledger functions in `seon.packages`, the handle operations in
`seon.handle` (§4) — so the keys are `:seon.packages/*`,
`:seon.packages.npm/*`, `:seon.packages.deps/*`, `:seon.handle/*`,
each named after its real function owner. No new umbrella noun exists
anywhere in this design ("package host" is two existing words; the
messages are the existing execution vocabulary; the values are
handles, playwright's/our dependency's own concept).

Contract properties (both hosts identically):

- **Same UDS envelope** as `seon.host` (`host.clj:11-67`): startup is
  the session's first frame; invoke/cancel/shutdown in; ready/result/
  error/stopped/value-sample-result out; one active invocation per
  session; absolute `deadline-ms`; `result-limit-bytes` bounds every
  result; the length-prefixed Transit codec from
  `seon.db.transport.uds` with its existing frame ceiling. Until W5
  promotes `seon.execution` to `.cljc`, the JVM implementation
  requires the message schemas from `seon.host` (the one existing
  owner of the JVM projection) rather than copying them; the W5
  promotion then moves both consumers in one step.
- **Stateless** — no writer channel, no database session, no sci
  context, no corpus. The ONLY process state is the loaded module
  graph/classpath and the handle table (§4). Ruling 4's loss contract
  is therefore structural: a crash loses runtime state only; every
  durable fact was written by the sci host before/after the call.
- **No eval.** The hosts serve a fixed generic op set; they never
  evaluate agent-authored code. Agent-authored adapter logic runs on
  the sci host (inside its containment) and COMPOSES these ops.
  The ops, expressed as `function-symbol` values in the ordinary
  invoke message (no new message kinds):
  - `seon.packages.host/call` — one argument map: the module (npm
    name / Clojure ns or Java class, the dependency's own
    identifier), an export/var/method path, and ordinary transit
    arguments. Returns ordinary transit data, or a `seon/handle`
    tagged value when the result cannot cross the wire (§4).
  - `seon.handle/call` — handle id + method/function name + args;
    executes where the value lives (the channel capability
    invocation).
  - `seon.handle/dispose` and `seon.handle/describe` — lifecycle +
    bounded summary refresh.
  Java interop needs no eval either: the JVM `call` op resolves
  Clojure vars via `requiring-resolve` and Java members via
  reflection on the loaded classpath — generic, data-driven, closed.
- **Zero third-party code in the core hosts** stays true: the sci
  agent host (`seon.host`) and the pod never load an installed
  package. Robustness-DNA addendum honored: native crashes are
  confined to the disposable processes.

### 2.2 The Bun package host

- Entry: `seon.packages.host.cljs` `-main`; launched with
  working directory `data/clusters/<name>/packages/npm/` so bun's
  resolver serves exactly the cluster tree — per-cluster isolation is
  the process's cwd, not path arithmetic in call sites.
- Serves `Bun.listen({unix: <socket>})` (`bun.d.ts:6506,6459-6469`)
  with the same frame discipline as the client side. **Gap to close
  in place:** `seon.db.transport.uds.cljs` is client-only today
  (`connect!` :272, `connect-stream!` :623); WP-B adds the listener
  arm (`listen-stream!`) to the SAME namespace — one codec owner,
  extended, never a second transport.
- Artifact: one new small shadow build (`:packages-host`) with no
  cljs.js, no renderer, no db session — pure codec + op dispatch +
  `js/require`. This survives W5 (the execution-child builds it
  superficially resembles are deleted there) and is counted in W9's
  build-matrix reconciliation: the matrix still shrinks net (−6 +1).
- Deadline honesty: JS cannot interrupt a hung synchronous native
  call. The host self-enforces deadlines for async work; for a truly
  wedged host the CLIENT enforces them (§2.4: deadline overrun with
  no frame → kill + respawn). Disposable-by-design is the containment,
  not an in-process fence.

### 2.3 The JVM package host

- Entry: `seon.packages.host/-main`, launched as
  `clojure -Sdeps <cluster-basis> -M -m seon.packages.host <edn>` —
  the classpath is the basis built from the cluster's `deps.edn` plus
  the minimal Seon server slice (codec + `seon.packages` +
  `seon.packages.host`). Deliberately NOT the `:writer` closure and
  NOT composed onto the sci host's basis: third-party jars, their
  static initializers, and their native libraries live only here
  (the audit §3 risk inventory is answered by process disposal, the
  only honest answer to `System/exit`, JNI segfaults, and rogue
  executor threads).
- Reuses the `seon.host` acceptor/session idiom (`host.clj:1046-1108`)
  minus everything contextual: no `build-base!`, no graduation, no
  projection, no writer session. Prints the same
  `HOST READY <socket>` line (`host.clj:1135`) as its readiness
  signal.
- Live add (§1.3): `binding [*repl* true] (add-libs …)` in the
  serving process; the `clojure` CLI ≥ 1.11.1.1347 is a doctor-checked
  launch prerequisite (`interop.clj:34-38`; `bin/seon doctor` gains
  the probe).
- Change/remove: terminate (drain per §2.4) → the install flow rebuilds
  the basis from the swapped `deps.edn` (`create-basis`,
  `tools.deps.clj:657`) → relaunch. Registry wrappers queue
  bounded-with-deadline across the swap (the anchor's addendum,
  mechanized in §3).

### 2.4 Lifecycle: who spawns, who respawns, who reaps

Grounded constraint: `bin/seon` is an explicit operator (up/restart/
down; `process.clj:1487-1494`), not a respawning daemon — nothing in
the supervisor watches and relaunches a crashed process. The proven
respawn machinery in this codebase is the pod's lazy
`ensure-entry!`-on-call (`execution/host.cljs:670-685`): a dead
process is respawned by the next call that needs it. The design
follows the client-owns-lifecycle rule one tier down:

- **The sci host is the only client** of both package hosts, and it
  owns their lifecycle: lazy spawn on the first wrapper call
  (ProcessBuilder; Bun binary and clojure CLI from the launch
  descriptor), generation stamp per launch, ready-await on the READY
  line/first frame within
  `:seon.config.packages.host/ready-timeout-ms`, and respawn-on-call
  after a crash with the
  `:seon.config.packages.host/respawn-backoff-ms` floor (a
  crash-looping package errors fast with steering instead of
  spin-respawning).
- **Recorded children:** pid + socket + generation land in the
  cluster's process dir (`tmp/seon-clusters/<name>/`), so
  `bin/seon status` shows them and `bin/seon down` reaps them with
  the rest of the family — the operator owns shutdown/reaping, the
  client owns respawn. This is the division the anchor's "under
  bin/seon supervision" resolves to, given the grounded operator
  semantics (owner decision 3 confirms).
- **Swap protocol** (installs, JVM change/remove, npm any-change):
  the sci-host client flips the lane to `:swapping`, new wrapper
  calls enqueue bounded by
  `:seon.config.packages.host/swap-queue-deadline-ms` (overflow/
  timeout → steering error naming the key), in-flight invocations get
  their remaining deadline to settle, shutdown → rename → relaunch →
  ready → queue drains. The queue is invocation-local coordination
  (an atom is legitimate here — genuinely process-local, like the
  writer channel state at `context.clj:198`).
- **Deadline enforcement:** every invoke carries its absolute
  deadline (existing contract field). The host settles what it can;
  the client additionally arms a watchdog per call — no frame by
  deadline + grace → kill the host, error-value every queued call
  with the honest "the package host was killed at its deadline;
  handles from generation N are gone" steering, respawn lazily.
- **Crash semantics:** ready-before-crash calls settle as
  `:seon/error` values (kind `:agent` for package-induced faults —
  the agent chose the call; never `:core-bug` unless the envelope
  itself was violated). Handles from the dead generation invalidate
  honestly (§4). Nothing wedges: the sci host, pod, and writer never
  block on a package host.

## 3. Wrapper generation into the U2 registry

- A capability's agent-facing functions are ordinary registry rows:
  `register-wrappers!` (`context.clj:482-495`) for agent-authored
  `my.*` wrappers (package-capabilities Phase 0/1: agents author them
  from goal tasks, they remain editable through the recorded
  graduation/edit path), `register-host-wrappers!` (:497) for the
  first-party kernel (`my.packages/*`, `seon.handle/*` — stamped
  `:sci/built-in`).
- Wrapper bodies run on the sci host under its full containment
  (deadline/interrupt/instrumentation — one eval pipeline, no
  parallel guard) and close over the package-host client exactly as
  capability wrappers close over the writer today
  (`context.clj:525-539`). The remote hop is inside the wrapper, not
  visible in its contract.
- **Real docs/arglists are mandatory at registration** (audit §5.6):
  registry vars carry live `:arglists`/`:doc` (`context.clj:470-476`)
  and they render into context — a name-only binding is a rejected
  registration. Phase 0's learning capture judges exactly this
  surface.
- Effectful package calls carry `:seon.capability/op-id` receipts
  like `db/transact!` (U2 discipline) — an interrupted turn never
  double-fires a package side effect silently.
- Epoch upgrade across swaps: after a successful swap the client
  re-registers the affected libs; `register-wrappers!` alters shared
  var roots so every live context sees the new generation
  (`context.clj:489-492`). During the swap the §2.4 queue holds
  calls; there is no window where a wrapper calls a half-swapped
  tree.

## 4. Handles — `:seon.handle/*`

Decision by placing the functions first (key-namespaces ruling):

- **Generic lifecycle operations are channel-independent** — dispose,
  describe, gc, staleness check behave identically for a playwright
  page and a JDBC connection. They live in ONE namespace,
  **`seon.handle`** (`src/seon/handle.cljc`): pure data mechanics +
  the sci-host wrappers `seon.handle/dispose!`, `seon.handle/describe`
  (and the host-side op names §2.1). Therefore the keys are
  `:seon.handle/*` — the namespace where a reader finds the functions
  that operate on the data. Not a vanity namespace: it has real
  functions, on both the client and host sides.
- **Channel-specific operations keep their capability owner** —
  `my.browser/click` takes a page handle; it lives in `my.browser`
  because that is where its function lives. The channel VALUE uses
  the producer's own noun as a qualified keyword
  (`:playwright/page`, `:playwright/browser` — playwright's words,
  translated nowhere).

Shape (facts, transacted by the sci host when a package-host result
comes back tagged):

- `:seon.handle/id` — guid string, identity attribute (row in
  `:seon.entity/id-attr`);
- `:seon.handle/channel` — qualified keyword, the producer's type
  word;
- `:seon.handle/host` — the package-host socket path (the coordinate,
  same idiom as `:seon.execution.host/eval-socket-path`);
- `:seon.handle/generation` — the host launch generation that owns
  the live object;
- `:seon.handle/summary` — bounded printed summary within
  `:seon.config.handle/summary-token-cap`.

Mechanics:

- **Wire:** a new `seon/handle` transit tagged type registered into
  the existing handler maps (`uds.cljc:175` and the `uds.cljs`
  writer/reader) — the codec's designed extension mechanism. The
  package host returns the tagged value when a result cannot encode
  as ordinary transit or is a known channel type; ordinary data
  always crosses as itself.
- **Binding:** the sci host binds the handle through the existing
  `result/{id}` retention (`retain-live-value!` path) — the agent
  addresses it exactly like any large value; `serve-value-sample!`
  pages its summary.
- **Rendering:** through the ONE render-slot dispatch (anchor's
  abridged/addressable mechanism): `:seon.render/ai` → compact remote
  reference ("`#handle playwright/page g3` — act via my.browser/…"),
  `:seon.render/html` → the channel/host card. No new render path.
- **GC:** per-channel cap `:seon.config.handle/per-channel-cap`;
  exceeding it disposes the oldest handle on the owning host and
  retracts its facts, and the NEXT touch of the collected handle
  returns the steering error naming the cap key. Explicit
  `dispose!` retracts + frees eagerly.
- **Staleness:** a call whose handle generation ≠ the host's live
  generation returns the honest error ("the package host restarted;
  this playwright/page no longer exists — re-create it; data you
  extracted before the restart is in your facts"). The facts persist
  as the record THAT a handle existed; liveness is derived from
  generation match, never stored as a status flag.
- **Teaching (W4 rides this):** data crosses; handles for the rest;
  act via channel functions; prefer extracting data over holding
  handles.

## 5. Robustness and hostile gates

Each capability ships its hostile gate (Robustness-DNA addendum);
the package-host battery joins W0.7's permanent surface. Gates are
behavior, never strings:

1. **Crash mid-call** — a `seon.packages.host/call` that segfaults
   the Bun host / `System/exit`s the JVM host: the invocation settles
   as a `:seon/error` value; the sci host, pod, and every other
   agent's turn complete; the next call respawns within backoff;
   `:seon.packages/generation` facts and ledger rows are intact
   (assert facts + envelope kinds, not messages).
2. **Hang past deadline** — a synchronous native spin: the client
   watchdog kills at deadline + grace; queued calls error with the
   config-key-naming steering; respawn works.
3. **Oversized result** — a result over `result-limit-bytes` returns
   the bounded error, host stays up (this is the existing envelope
   rule, proven per-host).
4. **Hostile install** — a package with a malicious postinstall:
   script does not run (bun default-secure + empty trust fact);
   a failing/hanging staged install dies at
   `:seon.config.packages/install-deadline-ms`, staging dir removed,
   live tree byte-identical, pod/host/other-cluster untouched,
   artifact digest unmoved (package-capabilities isolation gate,
   verbatim).
5. **Concurrent cluster installs** — two clusters install different
   versions of one package simultaneously: both resolve, neither
   blocks (shared bun cache is multi-version by layout,
   `global-cache.mdx:6`).
6. **Handle invalidation** — kill the host between handle creation
   and use: the staleness steering comes back as a value; facts
   retract/remain per §4; no wedge.
7. **Swap under load** — an install swap while wrapper calls stream:
   queued calls within the swap deadline all settle post-relaunch
   against the new generation; over-deadline calls error naming
   `:seon.config.packages.host/swap-queue-deadline-ms`.

## 6. What can start before U13 lands

- **Now (Phase 0, package-capabilities P1–P7):** operator pins at the
  repo root; agents author `my.*` wrappers from today's Bun execution
  children (process-isolated, so safe pre-W0-completion). Nothing in
  Phase 0 waits on this design.
- **Now, in parallel with W0/W3:** WP-K (roots + ledger + config
  accessors) touches launch/cluster/schema surfaces only — no overlap
  with the W0.4 pool lane's owned files beyond none (its owners are
  `host/context.clj`/`host.clj`; WP-K owns neither).
- **After WP-K:** both hosts (WP-B, WP-J) — independent of each other,
  parallelizable.
- **Sequenced behind W3's authored-invocation port only:** nothing
  here. Package wrappers are registry vars invoked inside host evals,
  which already work; the W3 punch list is orthogonal.
- **Phase 1 of package-capabilities** (agent-performed installs +
  isolation gates) starts when WP-K + one host + WP-W are accepted.

## 7. Findings (recorded, not fixed here)

1. **`seon.host` has no supervisor spec** — `process.clj:25-29` knows
   only watcher/writer/pod; the sci host is launched by tests or a
   manual `-main` (`host.clj:1122-1131`). U10/U12 (kill/restart with
   live agents) will need the same recorded-child treatment §2.4
   gives package hosts; one mechanism should cover both. Filed for
   the W6/W10 boundary; issue note to be created under
   `docs/seon/issues/` when WP-S lands the mechanism.
2. **`roadmap.md:36` (U13) and the package-capabilities roadmap both
   still say `pkgs/`** — superseded by ruling 3's addendum spelling
   `packages/`; both docs need the one-line update in the same commit
   as WP-K (doc-drift hygiene, W8's vocabulary row).
3. **`seon.db.transport.uds` is two files, one name**
   (`uds.cljc` JVM + `uds.cljs` Bun) — works because each platform
   loads its own, but the `.cljc` extension on a JVM-only file is
   misleading; noting, not renaming (W9 candidate).
4. **`config/system.edn` and `src/seon/agent/ctx.cljs` are dirty in
   the shared tree** (another lane's in-flight W4a follow-on per the
   anchor's execution state) — untouched by this design lane, listed
   so the WP-K implementer knows those paths are owned elsewhere.

## 8. Work-package cut (Codex-implementable, dependency order)

Every spec carries the ruling-10 preamble (read the exact
`reference-code/` sources named in §0; report better seams; use the
dependency's own terms), exact owned paths, shared-tree
path-limited-commit rules, and behavior-not-strings gates.

**WP-K — package roots, ledger, config accessors** (no host yet;
mechanical, `low` effort). Owned paths: NEW
`src/seon/packages.cljc` (attributes, manifest generators, pure
install planning), `src/seon/launch.cljc` (add
`::launch/packages-dir` and its disjoint-path row),
`script/seon/dev/cluster.clj` (coordinate),
`src/seon/config.cljs` (§1.4 accessors), tests. Gate: cluster create
materializes `packages/` with empty manifests; ledger rows →
generated `package.json`/`deps.edn` byte-stable and complete
(including the full `trustedDependencies` list semantics); policy
`:closed` rejects an install request with an error value naming
`:seon.config.packages/policy`; disjointness validation rejects an
overlapping path; CLJS gate green.

**WP-B — Bun package host** (`medium`). Depends on WP-K. Owned paths:
`src/seon/db/transport/uds.cljs` (add `listen-stream!` in place), NEW
`src/seon/packages/host.cljs`, shadow build entry for
`:packages-host`, NEW client half in
`src/seon/host/packages_client.clj` (spawn/ready/respawn/queue/
watchdog per §2.4 — the sci-host side), tests. Gate: startup-first-
frame → ready echo; `seon.packages.host/call` round-trips ordinary
data through a real installed package from a staged tree; deadline
kill + lazy respawn observed (generation increments, envelope kinds
asserted); one-active-invocation-per-session enforced; hostile
entries 1–3 of §5 green; writer + CLJS gates green.

**WP-J — JVM package host** (`medium`). Depends on WP-K; parallel
with WP-B (disjoint owned paths; the shared client
`packages_client.clj` is owned by whichever lands first — the second
lane extends, coordinated in the spec). Owned paths: NEW
`src/seon/packages/host.clj`, launcher (basis from cluster
`deps.edn`, `clojure -M` for the DynamicClassLoader guarantee),
live-add op (`*repl*` binding; doctor probe for CLI ≥ 1.11.1.1347),
tests. Gate: host launches on a basis containing a ledger-installed
lib and calls a var from it; live `add-libs` makes a new lib callable
without relaunch; change/remove terminates + rebuilds basis +
relaunches with queued calls settling inside the swap deadline;
`System/exit` from a package call yields error values + respawn with
zero fact loss; writer gate green.

**WP-H — handles** (`medium`). Depends on one host (either). Owned
paths: NEW `src/seon/handle.cljc`, `seon/handle` tagged type in
`src/seon/db/transport/uds.cljc` + `uds.cljs` handler maps,
`src/seon/host.clj` (bind handles through `retain-live-value!`;
`seon.handle/*` host wrappers), render rows (`seon.handlers.eval` ai
head + html card through the one slot dispatch), config accessors
`:seon.config.handle/*`, tests. Gate: an un-encodable result returns
a tagged handle whose facts land; `seon.handle/call` executes on the
owning host; dispose retracts + frees; per-channel cap collects
oldest with the steering error naming the key; host restart →
staleness steering (generation mismatch, asserted structurally);
ai render ≤ summary token cap via `seon.ai.tokens/estimate`.

**WP-W — install flow + wrapper generation** (`medium`). Depends on
WP-K + at least one host; completes U13. Owned paths:
`src/seon/packages.cljc` (staged-then-atomic flow §1.3, recovery
rule), `src/seon/host/context.clj` (register `my.packages/*` host
wrappers; swap-time re-registration), `packages_client.clj` (swap
protocol), tests. Gate: end-to-end agent-driven install of a real
package (policy `:allowlist`) → ledger facts, lock-pinned
`resolved`/`integrity` facts, generation bump, wrapper vars with real
`:arglists`/`:doc` rendered in context; op-id replay of the same
install is idempotent (receipt discipline); a failed stage leaves the
live tree byte-identical; hostile entries 4–5 + 7 of §5 green; the
package-capabilities Phase 1 isolation gate runs verbatim.

**WP-S — supervision integration + battery** (`low`). Depends on all
above. Owned paths: `script/seon/dev/process.clj` +
`script/seon/dev/cli.clj` (recorded-children status/reap for package
hosts; doctor probe), the permanent package-host hostile battery
under the writer gate, the §7.1 issue note, doc updates
(`roadmap.md:36` U13 row, package-capabilities `pkgs/` → `packages/`,
architecture `toolkit.md`/`agent-runtime.md` affirmative package-host
description). Gate: `bin/seon status` lists live package hosts;
`bin/seon down` reaps them; the full §5 battery green on a live
cluster; docs carry no `pkgs/` spelling
(`rg -n 'pkgs/' docs/ --glob '!**/archive/**'` clean).

## 9. Open owner decisions

1. **`my.packages` vs `my.pkg`** (U13's original name). Recommend
   `my.packages` — the vocabulary rule (the dependency's/directory's
   own word, no abbreviation) and discoverability; the U13 row is
   updated in WP-S either way.
2. **Ledger-first authority** — this design makes database facts the
   authority and the manifests derived projections (a cluster's tree
   is rebuildable from its `db`). The alternative (manifest-first,
   facts as mirror) matches the config-manifest idiom but makes the
   disk tree the truth an agent can't query. Recommend ledger-first
   as designed; it is also what makes op-id idempotent installs and
   the restart-survival gate trivial.
3. **Respawn placement** — client-driven lazy respawn (sci host owns
   spawn/respawn; bin/seon records, reaps, and shows status) per the
   grounded operator semantics, vs teaching bin/seon to babysit.
   Recommend as designed; a supervising daemon is a new mechanism the
   operator deliberately isn't, and the pod↔child precedent is
   proven. Revisit only if U12 shows call-driven respawn is too lazy
   for real fleets.
4. **Eval-free package hosts** — the generic op set (§2.1) keeps
   agent-authored adapter logic on the sci host. The alternative
   (shipping adapter code into the package host) would put a second
   eval surface inside the blast radius the hosts exist to contain.
   Recommend eval-free as designed; complex adapters are wrapper
   composition or graduate into first-party code.
5. **Lifecycle-script trust** — recommend the config-fact allowlist
   (`:seon.config.packages/trusted-lifecycle-scripts`, default empty,
   owner-edited) with bun's default-secure behavior for everything
   else; never auto-trust a package because its install failed
   without scripts.

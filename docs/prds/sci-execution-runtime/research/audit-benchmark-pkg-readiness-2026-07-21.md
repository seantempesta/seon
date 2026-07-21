---
type: research
status: active
tags: [research, agent, architecture]
---

# Audit — agentic-benchmark capability parity + native package access (2026-07-21)

Read-only audit on `codex/runtime-reliability-refactor`. Question: can agents
do everything the agentic benchmarks test, and is the U13 `my.pkg/install`
design sound for cluster-shared, cluster-independent native package access?

Sources read: `docs/prds/sci-execution-runtime/roadmap.md:36` (U13),
`docs/prds/sci-execution-runtime/design.md:74-90` (capability model),
`research/c2-js-bound-audit-2026-07-20.md`, `src/my/AGENTS.md`, the
`seon.agent.*` capability namespaces, `src-inspect-ai/` task/scorer sources,
`bin/seon` + `script/seon/dev/{config,process,cluster,release,changed_test}.clj`,
and `src/seon/launch.cljc`.

## 1. Toolkit vs benchmark needs — the capability surface is nearly complete

What the benchmarks in `src-inspect-ai/` actually require of agents:

| Benchmark surface | Requirement | Existing capability | Verdict |
|---|---|---|---|
| `generators.py:25-27` `shell_use` — filesystem outcomes under a per-run workspace, oracle re-reads files (`tool_scorers.py:70` `check_workspace`) | create/read/write files, run commands | `seon.agent.fs` — `read-file`/`write-file`/`edit-file`/`list-dir`/`stat`/`walk-dir`/`view`/`replace!`/`insert!` (`src/seon/agent/fs.cljs:314-785`); `seon.agent.shell/run` (`src/seon/agent/shell.cljs:217`) | **covered** |
| `generators.py:28-30` `web_fetch` — fetch LOCAL fixture pages, answer from content | HTTP fetch + extraction | `seon.agent.web/fetch` (`src/seon/agent/web.cljs:236`) — markdown extraction, preview + full blob, cache; gated by SEON_WEB + policy | **covered**, one policy footnote below |
| web search (memory: "Serper shipped") | search backend | `seon.agent.web/search` (`src/seon/agent/web.cljs:371`); backend config `config/system.edn:211-213` (`:search-backend :serper`, gemini-grounding alternative); `grants` (`web.cljs:165`) reports the effective backend incl. missing-key `:none` | **covered — yes, Serper exists** |
| `generators.py:31` `file_edit` — seeded files + goal-stated edits | anchored edits | `fs/edit-file`, `replace!`, `insert!` (anchored, rewrite-clj-aware) | **covered** |
| code execution | eval + subprocess + python | the eval loop itself; `shell/run`, `shell/py-run` (`shell.cljs:303`) | **covered** |
| long-running processes | background jobs | `shell/run-bg!`/`list-jobs`/`job-status`/`job-output`/`job-stop!` (`shell.cljs:336-483`); job table volatile globalThis tier | **covered** (jobs do NOT survive pod restart — acceptable for benches, noted) |
| file/code search | grep | `seon.agent.search/grep`, `grep-graph` (`src/seon/agent/search.cljs:153,288`) | **covered** |
| multi-step planning + restart survival (`planning.py` two-phase: phase 1 → pod restart → phase 2 same agent) | durable plan trees | `my.plan` (durable plan trees, dependencies, reconciliation — `src/my/AGENTS.md:29-34`); `my.kb` remember/recall | **covered** |
| tau-bench-like tool use (`tb_agent.py`/`tb2_agent.py`) | typed tool envelopes, multi-turn | the capability envelope pattern (map-in/map-out, errors-as-values) across all of the above; `my.data`/`my.ui`/`my.canvas`/`my.blob` | **covered** |
| Terminal-Bench 2.0 (`tb2_agent.py:1-42`) | full Linux shell inside a pinned amd64 container, Seon runtime injected | `env.exec` driven by the injected runtime; **blocked on arch**: all 89 task images are amd64-only, the Seon overlay bundles arm64 node (`tb2_agent.py` ARCH NOTE) | **infra gap, not a toolkit gap** |

All four capability families are default context: `:seon.eval/home-requires`
aliases `search`/`fs`/`shell`/`web` for every agent
(`config/system.edn:354-370`). Gates are host-owned env/config: `SEON_SHELL`
default-deny (`src/seon/agent/shell/internal.cljs:54-69`), `SEON_FS_LOCK`
(`fs.cljs:296-305`), `SEON_WEB` + `:seon.config/web` policy.

Footnote: the bench fixture server binds loopback
(`generators.py:1096` — `127.0.0.1:0`), while the SSRF-safe `:public-only`
web policy *blocks loopback* (`web.cljs` grants docstring). The owner cluster
runs `:open` (`system.edn:211`), so live bench runs work today, but any bench
cluster configured `:public-only` will fail every `web_fetch` row loudly.
Bench cluster provisioning must pin `:open` or `:allowlist` with the fixture
host.

The one genuine toolkit-shaped gap vs the broader agentic-benchmark landscape:
**no browser/DOM capability** (GAIA-style web navigation beyond fetch) — no
current design covers it (§5).

## 2. U13 :npm path — the install location and the execution host are unsettled

U13 (`roadmap.md:36`): `my.pkg/install` `:npm` = "pod host, `bun add` +
wrapper gen", delivered through the U2 wrapper registry.
`design.md:79-81` says: "npm/JS → the pod's capability server (one Bun
process serves the cluster; agents never hold a JS runtime)."

### 2a. Where node_modules live today: ONE shared root — cluster-dangerous

There is exactly one `package.json` + `bun.lock` + `node_modules`, at the repo
root (verified present; `release.clj:72-73` names them as release members and
`release.clj:828` copies the whole tree into artifacts). Cluster data dirs
(`data/clusters/<name>/` — `cluster.clj:45`) contain only `db` and `blobs`;
**no per-cluster package dir exists anywhere in `bin/seon`'s launch model**.
A `bun add` run "in the pod host" today would mutate the repo-root tree that:

- every cluster's pod loads from (all pods run the same artifact from the
  same checkout);
- the shadow watcher and artifact digest treat as a build input —
  `changed_test.clj:184` classifies `package.json`/`bun.lock` as
  infra-changed files, and the checkpoint/freeze discipline in CLAUDE.md
  makes an agent-triggered mutation a digest-invalidating event;
- `release.clj:1040-1056` resolves the lock-pinned build closure from.

So the current U13 wording ("pod host, bun add") is **not cluster-safe as
written**. Requirements the implementation must add:

1. **Per-cluster package root**: `data/clusters/<name>/pkgs/` (or a sibling)
   with its own `package.json`/`bun.lock`, installed via
   `bun add --cwd <cluster-pkg-root>`. Bun's global content-addressed cache
   (`~/.bun/install/cache`) is shared-safe (concurrent installs from two
   clusters hit the cache, hardlink/copy into their own trees); the *package
   dirs* must be per-cluster. Two clusters' installs then cannot collide.
2. **Live-mutation hazard**: `bun add` into a live tree rewrites existing
   package directories during hoist/dedupe. Loaded ESM/CJS modules are cached
   in-process (safe), but any *not-yet-required lazy import* in a running
   process can observe a half-written dir. Install should build into a
   staging dir and atomically rename (the `my.blob` publish discipline,
   `src/my/AGENTS.md:41-49`, is the house pattern), or the package host
   should restart/re-fork after install. Never `bun add` into the tree a
   running process is actively resolving from.
3. The install is a **config-fact-gated capability write** (U13 already says
   this): the durable fact is the cluster's package ledger row (name, version,
   integrity hash, provenance); the on-disk tree is the derived projection,
   rebuildable from the ledger — same derive-don't-store shape as the
   graduation tier.

### 2b. Where npm code EXECUTES — the C2 reconciliation (OWNER DECISION)

C2's verdict (`research/c2-js-bound-audit-2026-07-20.md:15,60`) was single-tier
JVM host, Bun sci tier unbuilt — but C2 measured the **existing** corpus
(0 organic js in 1037 fixtures + persisted evals). It did not measure benchmark
*needs*, and U13 creates the first genuine js-bound demand: an installed npm
package can only run in a JS runtime. C2 anticipated this: real-js forms on
the host tier get a steering `:seon/error` "→ the Bun tier if one ever exists"
(`c2:60`). `roadmap.md:40-41` defers the optional Bun sci tier decision to
U11. U13 forces the question earlier. Options:

**Option A — execute in the cluster's pod (the pod IS the capability
server).** The wrapper registry generates JVM-side wrapper vars whose bodies
are remote calls to the pod, which `require`s the package and runs the call.
- Pro: zero new processes; the pod already speaks the UDS transport
  (`seon.db.transport.uds`) and already hosts renders for host-tier agents
  (U1.5 divergence, `roadmap.md:407-410`); matches `design.md:80` read
  literally.
- Con: **the pod is the cluster** — web UI, LLM loop, SSE feeds, rendering.
  A package that segfaults Bun, spins the event loop, leaks memory, or calls
  `process.exit` takes down every agent's UI and turn loop at once. This is
  exactly the "nothing wedges" contract the runtime just spent U1-U5 escaping
  for eval. Package code is *less* trusted than agent code (foreign,
  unauditable, native addons).

**Option B — a per-cluster package-host child (one Bun process per cluster,
NOT the pod).** A dedicated Bun child owned by the cluster's supervisor,
loading only `data/clusters/<name>/pkgs/node_modules`, serving the same
length-prefixed transit-UDS envelope the JVM host serves (`seon.host`
semantics, `roadmap.md:243-260`). Wrappers route npm calls there; crash =
child-exited `:seon/error` values + respawn, the already-proven kill-drill
contract (U1 §7, U1.5).
- Pro: blast radius = that cluster's npm calls only; pod and JVM host
  survive; per-cluster node_modules falls out naturally (the child's cwd IS
  the cluster package root); reuses the one transport codec and the
  child-retire/respawn machinery verbatim; consistent with the design thesis
  "every non-local capability is a remote function call" (`design.md:76-77`).
- Con: one more process per cluster (memory floor — B-series measured ~60 MB
  bundle-proportional floor per Bun child, `roadmap.md:120-121`; a package
  host needs no cljs.js so it can be far smaller); a second lane in the
  execution dispatch (but U1.5 already made dispatch lane-keyed:
  `::children` / `::host-sessions`, `roadmap.md:341-344`).

**Option C — revive the full Bun sci tier (variant B child per js-bound
agent).** Oversized for U13: C2 proved no *agent code* needs js eval; only
package *calls* do. A whole sci engine per agent to host `require` is the
second-registry smell.

Recommendation to carry to the owner: **Option B**. It is the only shape
that satisfies both owner constraints simultaneously — cluster-shared access
(all agents' wrappers route to the one cluster package host) and cluster
independence (a package crash cannot take the pod, the writer, the JVM host,
or another cluster). Option A is acceptable only for a demo slice, and it
re-couples agent misbehavior to system availability. **Flagged as an owner
decision** — `design.md:80`'s "the pod's capability server" is ambiguous
between A and B and should be resolved in the design doc when ruled.

### 2c. Wrapper delivery — sound

Delivering npm wrappers through the U2 registry is right: registry vars are
process-local derived state rebuilt by re-registration (`roadmap.md:299-302`),
the shared `:load-fn` makes a post-fork registration require-able in every
live context (`roadmap.md:292-296`), and epoch upgrade covers package version
bumps. Effectful package calls should carry `:seon.capability/op-id` like
`db/transact!` (U2 receipts, `roadmap.md:304-317`) — U13 should say so
explicitly for packages with side effects (network clients etc.).

## 3. :maven path — a loaded jar CAN take down the JVM host

The U13 `:maven` path loads jars into the running JVM agent host
(`seon.host`) via a runtime classloader with an allowlisted binding table.
Honest risk inventory:

- **Static initializers** run at first classload, on the loading thread,
  outside any sci sandbox — arbitrary code, can block, throw, spawn threads,
  or call `System/exit`. The allowlisted binding table gates what *agents*
  can call, not what the jar does at init.
- **Native libraries** (JNI/`System/loadLibrary`) execute unmanaged code; a
  segfault kills the whole JVM — every agent context on that host at once,
  and (since the host retains one writer connection, `roadmap.md:252-254`)
  drops all in-flight capability calls. C1's OOME containment proof
  (`roadmap.md:203-207`, 20/20 survivals) covers *heap* faults in
  interpreted agent code, explicitly "strong evidence not kill-certainty"
  (`roadmap.md:206-207`) — it says nothing about native crashes.
- **Thread spawn**: `Thread/interrupt` containment (`roadmap.md:255-257`)
  only reaches threads the host created; a jar's own executor threads are
  uninterruptible by that mechanism and survive eval deadlines.
- **No SecurityManager**: removed in modern JDKs; there is no in-process
  fence for `System/exit`, file handles, or sockets from loaded jar code.

Isolation options (same ladder as §2b):

1. **Trust gate before load** (cheapest, partial): treat `:maven` install
   like graduation — a package is loadable only after an explicit
   config-fact policy row (U13 already gates on policy) *plus* a static
   scan (declares native libs? shade-bundled JNI?) recorded as facts.
   Catches nothing at runtime but makes the risk a deliberate owner grant.
2. **Isolated child-first URLClassLoader per package**: contains classpath
   pollution and version conflicts; contains NOTHING about crashes,
   `System/exit`, or native code. Necessary for correctness, insufficient
   for availability.
3. **Per-cluster package-host JVM child** (the §2b Option B shape on the JVM
   side): jars load into a disposable JVM speaking the same UDS envelope;
   crash = error values + respawn. The kill drill already proves the
   recovery choreography. This is the only option that upholds "a cluster
   cannot be taken down by its packages."

Recommendation: 1 + 3 for anything not on a first-party-audited allowlist;
2 alone must not be sold as isolation. Same owner decision as §2b — ideally
one ruling covers both runtimes ("package code runs in a per-cluster
disposable host, JS and JVM alike").

## 4. Cluster independence — shared-resource inventory

From `bin/seon` (`script/seon/dev/`) and `src/seon/launch.cljc`:

| Resource | Scope today | Classification |
|---|---|---|
| `data/clusters/<name>/{db,blobs}` (`cluster.clj:45`, `launch.cljc:410-430` disjoint-path validation) | per cluster | **isolated** |
| Branch-cluster blob read-only bases (overlay over source archive, `src/my/AGENTS.md:38-41`) | shared read-only | **shared-safe** |
| Process dirs `tmp/seon-clusters/<name>`, log dirs, http-port files (`cluster.clj:46,54-57`) | per cluster | **isolated** |
| Pod HTTP port: default fixed 7890, acme 7980, autonomous clusters `::launch/http-port 0` = dynamic (`cluster.clj:55`) | per cluster | **isolated** |
| **JVM writer process** — `shared-writer-cluster-descriptor` "using the source writer owner" (`launch.cljc:410-411`, `cluster.clj:48`): every autonomous cluster of a flavor retains the flavor's ONE writer | shared across that flavor's clusters | **shared-dangerous**: writer crash/stall downs every cluster on it; one cluster's transaction storm starves the others. Deliberate (one durable-resource owner), but it means "clusters are independent" currently holds only *between flavors* (default vs acme), not between a flavor's branch clusters. |
| Writer UDS socket + repl-port file (`config.clj:410,418`) | one per writer = shared by its clusters | **shared-dangerous** (same blast radius as the writer) |
| **Shadow watcher + `out/` artifact** — one watcher per source checkout owns the flavor's builds (`process.clj:25-34,189-207` watcher-owner; default owns `test`/client, acme owns `acme-client` with its own cache root per CLAUDE.md) | shared per flavor | **shared-safe with the freeze discipline** (a rebuild mid-checkpoint invalidates digests, but cannot crash a running pod; running pods keep their loaded bundle) |
| **Root `package.json`/`bun.lock`/`node_modules`** (`release.clj:72-73,828`; `changed_test.clj:184`) | ONE per checkout, all flavors + all clusters | **shared-dangerous for U13** — see §2a. Today it is mutated only by humans/ops (shared-safe in practice); the moment `my.pkg/install` targets it, it becomes the cross-cluster takedown vector the owner ruled out. |
| Bun global install cache `~/.bun/install/cache` | machine-global | **shared-safe** (content-addressed, append-only) |
| JVM agent host (`seon.host`) sockets — per-agent tier fact `:seon.execution.host/eval-socket-path` (`roadmap.md:331-333`) | host per flavor/cluster deployment (drills ran one host per drill writer) | **isolated-able**; becomes shared-dangerous if one host serves several clusters and §3 jars load into it |
| Maven local repo `~/.m2` (future `:maven` installs) | machine-global | **shared-safe** as a download cache; the *classpath* built from it must be per-cluster |

Net: the launch model's path-disjointness validation (`launch.cljc:417-430`)
already enforces per-cluster privacy for db/blobs/process dirs. The two real
independence holes are (a) the shared flavor writer — accepted architecture,
worth stating explicitly in the independence claim — and (b) the root
node_modules, which U13 must not touch (§2a).

## 5. What the benchmarks need that NO current design covers

1. **The js execution host for npm packages** — the §2b owner decision. U13
   names install + wrappers but no runtime; C2 retired the only JS tier;
   design.md:80 is one ambiguous clause. Without this ruling U13's `:npm`
   half is unimplementable.
2. **Per-cluster package roots** — no `data/clusters/<name>/pkgs/` exists in
   any launch descriptor; U13 must add the coordinate to
   `cluster.clj`/`launch.cljc`, not improvise a path.
3. **Browser/DOM interaction** — fetch+extract (`web.cljs:236`) covers
   static pages; GAIA-class navigation (click, form, JS-rendered content)
   has no capability and no design line anywhere.
4. **amd64 Seon overlay for Terminal-Bench 2.0** (`tb2_agent.py` ARCH NOTE)
   — the adapter is done; the arm64-only runtime bundle cannot boot in the
   pinned amd64 images. Infra/owner build, tracked nowhere in the U-series.
5. **Background jobs surviving pod restart** — the job table is volatile
   globalThis (`shell.cljs:80-83`); `planning.py`'s restart choreography
   works because plans are database facts, but a bench that expects a
   long-running *process* to survive a restart has no mechanism. Likely
   acceptable (benches re-drive), worth stating as a known boundary.
6. **Package facts as context** — for agents to *use* installed packages,
   the wrapper vars need docs/arglists in context (U2 gives `:arglists`/
   `:doc` on registry vars, `roadmap.md:291-292` — the mechanism exists;
   U13 should require wrapper generation to populate them from package
   typings/README, else agents get name-only bindings they cannot learn).

## Bottom line

Capability parity for tau-bench/file/web/shell/planning-style benchmarks is
essentially **already shipped** in `seon.agent.{fs,shell,web,search}` +
`my.plan`/`my.kb` — the gaps are packaging and isolation, not agent-facing
functions. U13's registry-delivery and policy-gating halves are sound; its
two unwritten halves — *where per-cluster node_modules live* and *which
process executes package code* — are exactly the cluster-independence
requirement, and both point at the same answer (a per-cluster disposable
package host). That is an owner decision and should be ruled before U13
implementation starts.

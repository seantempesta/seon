---
type: research
status: active
tags: [research, agent, flow]
---

# Isolated Node environments + shared package pool — OSS survey

Survey of existing open-source projects that solve "spin up LIGHTWEIGHT isolated
Node environments + a shared package pool" for a long-lived AI-agent runtime, to
decide ADOPT vs COMPOSE for the Seon CLJS pod.

## TL;DR — COMPOSE, don't adopt

**No single project fits our profile (LIGHT threat model + shared-pkg-realtime +
long-lived-reactive + CLJS/Node-host).** Every turnkey "agent sandbox" we found
(E2B, microsandbox, OpenHands, Daytona, Vivaria, Inspect) is built for a
*heavier* threat model than ours — Docker / Firecracker / libkrun microVM
**per task**, designed for adversarial multi-tenant untrusted code, with
hundreds-of-MB and tens-to-hundreds-of-ms spin-up, and an out-of-process /
out-of-language control plane (Python or Go). They are the wrong shape twice
over: too heavy for our reversible-DB-backstop threat model, and they don't host
**inside** our long-lived CLJS Node process.

The lightweight isolate runtimes (workerd, deno_core, isolated-vm) are closer in
spirit but each disqualifies on a hard requirement: workerd can't be embedded as
a library and can't install npm at runtime; deno_core means leaving Node for a
Rust host; isolated-vm is maintenance-mode, separate-heap (no shared npm module
graph), and crashes on Node 25.

**The honest answer is COMPOSE**, and we have already measured the winning
primitives in-repo:

- **Isolation** = `worker_threads` (~8 MB / ~30 ms per worker, `terminate()`
  kills a sync hang in 0.8 ms) for the runaway-eval blast radius, plus **SCI**
  (~0.2 ms in-process interrupt) for interpreted runaways. This already beats
  every microVM option on weight by 1-2 orders of magnitude and matches our
  threat model exactly.
- **Shared package pool** = **pnpm content-addressed store** — one on-disk copy,
  hard-linked into each unit's `node_modules`; "install once, every unit sees it"
  is realtime by construction (a new hard-link appears, no rebuild).
- **Coordination** = our existing datahike single-writer/multi-reader DB.
- **Supervision/lifecycle** = borrow patterns from **Piscina/tinypool** (pool
  lifecycle, `maxMemoryLimitBeforeRecycle`, terminate-and-respawn) and
  **workerpool** (function-by-name registration, process-or-thread isolation).

We don't *adopt* a framework; we *mine* 3-5 repos for patterns (ranked shortlist
below).

**Tiering (see the microVM deep-dive + tiered-verdict sections):** worker_threads
is **Tier 1** — the light, reactive, trusted default. A **microVM** (libkrun /
microsandbox locally on the Mac via HVF; Firecracker on prod-Linux) is **Tier 2**
— the strong-isolation escape hatch, worth its weight ONLY for untrusted /
dangerous / capability-heavy code and the future multi-tenant ("other people's
agents") case. Not now, but planned. Don't pay microVM weight to babysit a
hallucination; pay it to contain a stranger.

## Heavy vs light — why the agent-sandbox frameworks are the wrong tool

Our threat model: **single user, non-adversarial agent**. The sandbox exists to
catch LLM *hallucinations* (a runaway loop, a bad eval), not to contain a hostile
attacker. The DB is a reversible backstop — terminate + restart-from-DB recovers
any unit. We explicitly do NOT need Docker/Firecracker/microVM-grade isolation.

Every turnkey agent-sandbox project inverts that assumption:

| Project | Isolation mechanism | Weight | Threat model | Host language | Fit |
|---|---|---|---|---|---|
| **E2B** | Firecracker microVM per sandbox | ~100s ms start, microVM RAM | adversarial multi-tenant cloud | Python/TS SDK → cloud | No — cloud SaaS, heavy |
| **microsandbox** | libkrun microVM, local-first | microVM-grade | hardware-isolated untrusted code | Rust daemon + MCP | No — microVM weight |
| **OpenHands runtime** | Docker container per agent (mounts docker.sock = root) | container-grade | self-hostable but heavy | Python | No — Docker per agent |
| **Daytona** | Docker container, shared kernel, sub-90 ms | container-grade, AGPL-3.0 | secure AI-code infra | Python/TS/Ruby/Go SDK | No — Docker + AGPL |
| **Vivaria (METR)** | Docker container per task env | container-grade | capability evals | Python/TS | No — eval harness, Docker |
| **Inspect (AISI)** | pluggable: process-jail / Docker / K8s | varies | safety evals, out-of-sandbox control | Python | No — eval harness, OOP control |
| **SWE-agent/SWE-bench** | Docker image per task instance | container-grade | benchmark reproducibility | Python | No — benchmark harness |

The common shape: **microVM/container per task + an out-of-process control plane
in Python/Go**. That's the right design for "run untrusted internet code at scale
for a cloud product." It is dramatically over-built for "stop one trusted agent's
hallucinated `while(true)` from wedging its siblings inside my own Node process,"
which `worker_threads.terminate()` does in 0.8 ms.

## DEEP DIVE — the lightweight-microVM class (Firecracker et al.)

This class deserves its own section because it is the *strong-isolation* answer:
a real second kernel per unit, hardware-enforced (KVM/HVF) memory boundaries.
The question is not "is it good isolation" (it is the best short of separate
hardware) — it's "is its weight and its boundary worth it **for us**, given a
trusted single-user agent + a shared-realtime-npm requirement + a Mac dev box."

### The runtimes — real numbers, maturity, Node-in-guest story

| Project | What it is | Boot | Per-VM overhead | Separate kernel? | Host OS | Node/npm in guest | Maturity / git |
|---|---|---|---|---|---|---|---|
| **Firecracker** | AWS Rust VMM, minimal device model + jailer (chroot/seccomp/ns) | ~**125 ms** to userspace | **<5 MiB** | YES (own Linux kernel) | **Linux + KVM only** | full Linux guest → normal Node + npm inside | very mature, AWS-run (Lambda/Fargate); Apache-2.0; `firecracker-microvm/firecracker` |
| **Cloud Hypervisor** | Rust VMM (Intel-origin, CNCF), richer device model than FC | **<100 ms** userspace | low (few MiB) | YES | **Linux + KVM** (also Windows guests) | full guest → Node/npm | mature, active; Apache-2.0; `cloud-hypervisor/cloud-hypervisor` |
| **libkrun** | microVM **as a C-API library** (embed a VMM in YOUR process) | ~**100 ms** | low | YES | **KVM on Linux, HVF on macOS/Apple Silicon** | full guest → Node/npm; virtio-fs/-vsock/-net | active (containers org, Red Hat); LGPL; `containers/libkrun` |
| **gVisor** | userspace kernel (Go) intercepting guest syscalls — middle ground | container-ish | higher CPU, ~4x I/O, ~1.7x net | **NO** (shared host kernel, syscall sandbox) | **Linux only** | runs Node, but syscall-compat gaps possible | mature, Google-run (GKE Sandbox); Apache-2.0; `google/gvisor` |
| **Kata Containers** | OCI runtime that puts each container in a microVM (CH/QEMU/FC backend) | **150–300 ms** end-to-end | VM-grade | YES | **Linux + KVM** | OCI image → Node/npm | mature, CNCF; Apache-2.0; `kata-containers/kata-containers` |
| **firecracker-containerd** | containerd plugin to run FC microVMs as containers | FC-grade | FC-grade | YES | Linux + KVM | OCI image → Node/npm | maintained but niche; `firecracker-microvm/firecracker-containerd` |
| **weaveworks ignite** | FC microVMs with a container UX + GitOps | FC-grade | FC-grade | YES | Linux + KVM | OCI image → Node/npm | **ARCHIVED Dec 2023, read-only — DEAD, do not use** |

I/O cost is the recurring tax: Kata is ~**84x slower than runc on I/O** and ~9x
on network (VM-exit per op); gVisor ~4x I/O / ~1.7x net. That tax lands exactly
on the chatty, latency-sensitive path — which matters for our reactive readers
(see §DB-latency below), and is invisible for LLM-paced work.

How the agent frameworks build on this: **E2B** = Firecracker microVM per
sandbox (cloud, KVM-Linux). **microsandbox** = **libkrun** (`msb_krun`) — and
notably it is an *embedded* runtime: runs as a **child process of your app, no
daemon, no root**, OverlayFS/PassthroughFS/MemFS backends, OCI images, and a
**Node SDK via NAPI-RS**. microsandbox is the closest existing thing to "microVM
isolation you call from Node" and is the right reference if/when we build the
strong-isolation tier.

### (1) macOS dev story — Firecracker can't, but Apple Silicon CAN

Firecracker (and Cloud Hypervisor, Kata, gVisor, ignite, fc-containerd) require
**KVM = Linux-only**. None run *natively* on macOS — on a Mac they only run
inside a Linux VM. So "develop microVMs on my Apple Silicon laptop" is NOT done
with Firecracker directly. Two real native paths exist on Apple Silicon (HVF):

- **libkrun on macOS (HVF)** — libkrun explicitly supports **HVF on macOS/ARM64**
  (this is what `krunvm`/`krunkit` and Podman-machine's libkrun provider use, and
  what runs Linux microVMs on M-series). So microsandbox/libkrun-style microVMs
  *do* boot locally on the Mac. This is the practical local path today.
- **Apple Containerization framework + `container` CLI** (WWDC 2025, **shipped
  v1.0.0**, ~30k★, **Apache-2.0**, Swift) — runs **each Linux container in its
  own lightweight VM via Virtualization.framework**, with a Swift `vminitd` init,
  EXT4 block devices, **sub-second** boot. This is Apple's blessed, per-container-
  microVM primitive, native to Apple Silicon. `apple/container` (CLI) +
  `apple/containerization` (framework).
- **Virtualization.framework** is the underlying Apple hypervisor API both of the
  above sit on.

Verdict on macOS: local microVM dev IS practical on Apple Silicon, but **not via
Firecracker** — via libkrun (matches our microsandbox reference) or Apple's
`container`. Note a dev/prod split: prod-Linux would likely run Firecracker;
local-Mac would run libkrun/Apple-container. Two VMMs to support is real cost.

### (2) Shared package pool ACROSS microVMs — the hard part

This is where the microVM tier fights our requirement. Each VM has its own kernel
and rootfs, so the trivial worker_threads answer (everyone reads the same on-disk
`node_modules`) does not hold. Options, best to worst for "install once, every VM
sees it realtime":

- **virtio-fs read-only mount of the host pnpm store** — mount the host's pnpm
  content-addressed store (and a shared `node_modules` view) into each guest over
  **virtio-fs**. Host-side `pnpm add` is reflected live in every guest → realtime
  holds. CAVEAT: pnpm's **hard-link** trick is a host-filesystem optimization; it
  does not cross the host→guest boundary — inside the guest you see a virtio-fs
  view, not host inodes, so you give up the hard-link dedup *inside* the guest and
  pay virtio-fs I/O latency on every `require()`. microsandbox already uses
  PassthroughFS/OverlayFS for exactly this kind of host-dir sharing.
- **shared base rootfs image** with packages baked in — fast reads, but **NOT
  realtime**: a new `pnpm add` means rebuilding/republishing the base image and
  rebooting VMs. Defeats the requirement.
- **per-VM OverlayFS** with a shared read-only lower layer (the store) + writable
  upper — this is microsandbox's model; combine with virtio-fs lower for realtime.

Bottom line: realtime-shared-npm across microVMs is *achievable* (virtio-fs RO
mount of the host pnpm store + per-VM overlay) but it's a meaningfully more
complex, slower-read story than the worker_threads case where it's just "the same
directory." The shared-pool requirement actively penalizes the microVM tier.

### (3) DB-access latency from inside a microVM

Our reactive vision wants **sub-ms** re-renders: a reader sees a new datom and
re-derives its view. Cost of reaching the datahike wire by tier:

- **worker_thread (in-process):** same OS process, separate V8 heap. Reaching the
  pod's DB value is a `MessageChannel`/`SharedArrayBuffer` hop or a re-read —
  microsecond-to-low-ms, no kernel/network boundary. Compatible with sub-ms
  reactive re-render.
- **microVM (out-of-kernel):** the guest has NO shared memory with the host. It
  must reach the wire over **vsock or virtio-net + serialization** — a guest→host
  VM-exit round trip plus encode/decode on every read. That's the same VM-exit tax
  that makes Kata ~84x slower on I/O. Realistically tens-of-µs-to-ms per round
  trip, and it scales with read frequency. For a **chatty sub-ms reactive reader,
  the VM boundary hurts** — you cannot cheaply re-derive a view 100x/sec across a
  vsock. For **LLM-paced agent work** (a tool call every few seconds), the hop is
  utterly negligible — totally fine.

This is the decisive technical reason the reactive UI/readers stay in
worker_threads and only *heavy, slow, dangerous* execution goes in a microVM.

### (4) The tiered verdict — YES, this is the right framing

Two tiers, by trust and latency-sensitivity:

- **Tier 1 (default): worker_threads + SCI** — light (~8 MB / ~30 ms), in-process
  (sub-ms DB reads, trivially-shared pnpm `node_modules`), instant kill
  (`terminate()` 0.8 ms, SCI interrupt ~0.2 ms). This hosts the reactive readers,
  UI components, and the trusted single-user agent's normal work. It IS the
  product today.
- **Tier 2 (strong isolation, opt-in): a microVM** — own kernel, hardware
  boundary. Worth its weight ONLY when the code is genuinely untrusted or
  dangerous: arbitrary capability-heavy installs, shell-outs, network-touching
  tools, and crucially the **future multi-tenant case** (other people's agents,
  not just our one trusted user). When that day comes, the reference to adopt is
  **microsandbox/libkrun** (embedded, Node SDK, runs on Mac via HVF), with
  Firecracker as the prod-Linux backend.

A microVM is worth its weight for us precisely when our threat-model assumption
(trusted, single-user, reversible-DB backstop) breaks — i.e. NOT now, but it's
the planned escape hatch. Until then it is over-built. The framing is: **don't
pay microVM weight to babysit a hallucination; pay it to contain a stranger.**

## Lightweight isolate runtimes — closer, but each fails a hard requirement

| Project | What it is | Isolation + weight | npm-realtime | CLJS/Node fit | Maturity | Verdict |
|---|---|---|---|---|---|---|
| **Cloudflare workerd** | C++/V8 isolate server (open-source Workers runtime) | V8 isolate, <5 ms start, shared native APIs (very light) | NO — pre-configured code only, no runtime npm install | NO — **standalone server binary, not embeddable**; explicitly needs an external VM for untrusted code | 8.3k★, Bazel, Apache-2.0, active | Rejected — can't embed, can't npm |
| **deno_core** | Rust crate (Rusty V8 + ops + event loop) | V8 isolate, deny-by-default permissions | N/A (no Node compat in core; no fetch/node modules) | NO — host must be **Rust**, not our Node process | active (Deno) | Rejected — wrong host language |
| **isolated-vm** | Node addon: isolated V8 contexts with own heap | separate-heap isolate, light | NO — separate heap means **no shared npm module graph**; you'd marshal across the boundary | embeddable in Node, but breaks the shared-package premise | **maintenance mode**, 6.0.2 Oct 2025, **crashes on Node 25** | Rejected — maint-mode + no shared modules |
| **vm2** | (predecessor) in-process sandbox | n/a | n/a | n/a | **DEPRECATED, critical CVEs** | Dead — do not use |

Takeaway: V8-isolate-per-unit (workerd/isolated-vm style) gives lighter isolation
than worker_threads, BUT every isolate has its **own heap and its own module
namespace** — which directly defeats "all units share one realtime npm pool." A
worker_thread, by contrast, is a real Node realm: it `require()`s from a normal
`node_modules` on disk, so a pnpm-hard-linked shared store is visible to all of
them with zero marshalling. For our "shared package pool" requirement,
worker_threads is actually the *better* isolation primitive, not a compromise.

## Worker pools / ergonomics — the patterns to borrow

These are libraries, not frameworks; they encode the lifecycle/supervision we'd
otherwise reinvent on raw `worker_threads`:

- **Piscina** (`piscinajs/piscina`) — the reference Node worker-thread pool.
  Fast, modern threading model, `maxMemoryLimitBeforeRecycle` (terminate worker
  when heap exceeds limit, respawn fresh — exactly our "runaway → terminate →
  restart-from-DB" loop), abortable tasks, utilization metrics. ~800 KB.
- **tinypool** (`tinylibs/tinypool`) — 38 KB friendly fork of Piscina, deps
  trimmed; what Vitest uses. Best to *read* for the minimal-core pool lifecycle.
- **workerpool** (`josdejong/workerpool`) — supports BOTH worker_threads and
  child_process isolation, dynamic scaling, **register functions by name** (maps
  onto our "call a fn the agent defined" model). Read for process-vs-thread
  abstraction and dynamic pool sizing.
- (Comlink / threads.js / nanothreads — RPC ergonomics over workers; lower
  priority, our DB already is the message bus.)

## In-browser Node — borrowable tech, but not for us now

- **WebContainers (StackBlitz)** — Node-in-WASM + virtual FS in the browser.
  Impressive, but **proprietary, commercial license required**. Tech is
  interesting (virtual FS, WASM Node) but not borrowable (closed) and not needed
  — our pod runs on real Node, not in a browser.
- **CodeSandbox Nodebox** — similar effort, **appears discontinued**, polyfill-
  based emulation. Not viable.

Neither is relevant: we host on real Node already; Node-in-WASM solves a problem
(no server) we don't have.

## Shared package pool — recommendation: **pnpm content-addressed store**

The requirement is precise: agents install npm packages and **all units pick them
up in realtime without restart**, with one disk copy shared across N isolated
units.

| Option | Mechanism | "Install once, all units share, realtime" | Verdict |
|---|---|---|---|
| **pnpm store** | Global content-addressable store (`~/.pnpm-store`); each project's `node_modules` is **hard-links** into the store | YES — installing adds a store entry + hard-links it into the shared `node_modules`; every worker_thread `require()`s the same on-disk inode immediately, no rebuild | **RECOMMENDED** |
| **Yarn PnP** | No `node_modules`; a `.pnp.cjs` resolution map points imports at zip archives | Partial — single resolution map is elegant, but it requires the **PnP loader hook** in every runtime and regenerating `.pnp.cjs` on install; fights Node's default resolver and CLJS-emitted `require`s | Possible but adds a loader dependency |
| **Bun global cache** | Global cache, but materializes a **full per-project node_modules** (no cross-project hard-link dedup) | NO — doesn't share across units the way pnpm does; also means adopting Bun as runtime | Rejected for this requirement |

**Use the pnpm store.** A single shared `node_modules` directory (hard-linked
from the global content-addressed store) is mounted/visible to every
worker_thread. An agent's `pnpm add foo` writes one store copy and hard-links it
in; because all units resolve against the *same* `node_modules`, the new package
is visible on the next `require()` with no restart — realtime by construction.
This is the canonical "one disk copy, shared per unit" answer and it needs no
loader shim (unlike PnP) and no runtime change (unlike Bun). We can shell out to
the `pnpm` CLI; we do not need to vendor it.

## RANKED SHORTLIST — repos to clone into reference-code/ for a code-dive

Ranked by pattern-mining value for our COMPOSE plan. (Orchestrator clones these.)

1. **Piscina** — `https://github.com/piscinajs/piscina`
   WHY: the canonical worker_threads pool; our isolation + supervision backbone.
   READ: `src/index.ts` worker lifecycle, `maxMemoryLimitBeforeRecycle` +
   recycle path (= terminate-and-restart-from-DB), task abort/cancellation,
   `Piscina#destroy`, and how it threads `AbortSignal` to kill a hung task.

2. **tinypool** — `https://github.com/tinylibs/tinypool`
   WHY: 38 KB minimal-core version of Piscina — the same lifecycle with the noise
   removed; easiest to read end-to-end and the cleanest template for our own thin
   pool. READ: the entire pool state machine; how it differs from Piscina (what
   they cut tells us what's essential).

3. **workerpool** — `https://github.com/josdejong/workerpool`
   WHY: function-by-name registration (maps onto "invoke an agent-defined fn") and
   a single abstraction over BOTH worker_threads and child_process isolation —
   useful if some units need process-level (not thread-level) blast-radius. READ:
   the pool/worker handshake, dynamic sizing, and the thread-vs-process switch.

4. **pnpm** — `https://github.com/pnpm/pnpm`
   WHY: the shared-package-pool mechanics we depend on. READ (don't reimplement —
   understand): `store/` (content-addressable store layout, integrity keys) and
   the hard-link/`@pnpm/cafs`/clone-or-copy logic, to confirm exactly how a fresh
   install becomes instantly visible to a shared `node_modules` and what the store
   path/layout guarantees are.

5. **isolated-vm** — `https://github.com/laverdet/isolated-vm`
   WHY: read-only reference for the V8-isolate-per-unit alternative we rejected —
   to understand the heap/marshalling boundary and *why* it defeats the shared-npm
   premise, so the rejection is documented in code, not just asserted. READ: the
   `Isolate`/`Context`/`Reference` model and the cross-isolate transfer cost.
   (Do NOT take a runtime dependency — maintenance-mode, Node-25-broken.)

### Tier-2 (strong-isolation) repos — clone WHEN we build the microVM tier

6. **microsandbox** — `https://github.com/superradcompany/microsandbox`
   WHY: the single closest existing thing to "microVM isolation called from Node"
   — embedded (child process, no daemon/root), libkrun-based, OCI images, a Node
   SDK via NAPI-RS, runs locally on Apple Silicon via HVF. The template for our
   Tier-2. READ: `msb_krun` VM lifecycle, the host↔guest `agentd` protocol, the
   filesystem backends (OverlayFS/PassthroughFS) — specifically how it shares a
   host directory into the guest (our pnpm-store-over-virtio-fs mechanism), and
   the NAPI-RS Node bindings.

7. **libkrun** — `https://github.com/containers/libkrun`
   WHY: microVM-as-a-C-library, the macOS-capable VMM (HVF on Apple Silicon)
   underneath microsandbox — read to understand the embed-a-VMM-in-your-process
   API and the virtio-fs/-vsock device surface (the DB-over-vsock cost). READ: the
   C API surface, the HVF backend, and the virtio-fs / virtio-vsock device wiring.
   (Honorable mention for prod-Linux: `firecracker-microvm/firecracker` — read the
   API + jailer for the 125 ms / <5 MiB / seccomp model; and Apple's
   `apple/containerization` for the native per-container-VM design on macOS.)

Honorable mention (read online, probably don't clone): **workerd**
(`https://github.com/cloudflare/workerd`) for its "shared native APIs across many
isolates in one process / nanoservice" design philosophy — a north star for
density even though we can't embed it.

## Sources

- Cloudflare workerd — <https://github.com/cloudflare/workerd>,
  <https://blog.cloudflare.com/workerd-open-source-workers-runtime/>,
  <https://developers.cloudflare.com/workers/reference/security-model/>
- isolated-vm — <https://github.com/laverdet/isolated-vm>,
  <https://github.com/laverdet/isolated-vm/issues/541> (Node 25 crash),
  vm2 discontinuation — <https://semgrep.dev/blog/2023/discontinuation-of-node-vm2/>
- E2B / microsandbox / Daytona / OpenHands / Vivaria / Inspect —
  <https://github.com/restyler/awesome-sandbox>,
  <https://github.com/superradcompany/microsandbox>,
  <https://github.com/daytonaio/daytona>,
  <https://github.com/OpenHands/OpenHands/issues/13203>,
  <https://github.com/METR/vivaria>,
  <https://www.aisi.gov.uk/blog/the-inspect-sandboxing-toolkit-scalable-and-secure-ai-agent-evaluations>,
  <https://rywalker.com/research/ai-agent-sandboxes>
- microVM class — Firecracker <https://github.com/firecracker-microvm/firecracker>
  + <https://firecracker-microvm.github.io/>,
  Cloud Hypervisor <https://github.com/cloud-hypervisor/cloud-hypervisor>,
  libkrun <https://github.com/containers/libkrun> + <https://www.sinrega.org/running-microvms-on-m1/>,
  gVisor <https://github.com/google/gvisor>,
  Kata <https://github.com/kata-containers/kata-containers>,
  firecracker-containerd <https://github.com/firecracker-microvm/firecracker-containerd>,
  ignite (ARCHIVED) <https://github.com/weaveworks/ignite>,
  benchmarks <https://northflank.com/blog/kata-containers-vs-firecracker-vs-gvisor>,
  microsandbox internals <https://deepwiki.com/superradcompany/microsandbox>
- macOS microVM — Apple Containerization/`container` (WWDC25)
  <https://github.com/apple/container> + <https://developer.apple.com/videos/play/wwdc2025/346/>,
  <https://www.infoq.com/news/2025/06/apple-container-linux/>
- deno_core — <https://docs.rs/deno_core/latest/deno_core/>,
  <https://github.com/denoland/deno/discussions/21524>
- Worker pools — <https://github.com/piscinajs/piscina>,
  <https://github.com/tinylibs/tinypool>, <https://github.com/josdejong/workerpool>
- WebContainers / Nodebox — <https://blog.stackblitz.com/posts/introducing-webcontainers/>
- pnpm vs PnP vs Bun — <https://github.com/pnpm/pnpm>,
  <https://www.deployhq.com/blog/choosing-the-right-package-manager-npm-vs-yarn-vs-pnpm-vs-bun>,
  <https://betterstack.com/community/guides/scaling-nodejs/pnpm-vs-bun-install-vs-yarn/>

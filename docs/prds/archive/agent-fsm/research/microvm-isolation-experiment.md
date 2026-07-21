---
type: research
status: draft
tags: [research, agent, architecture, flow]
---

# MicroVM Isolation Experiment (mvm) — runnable from a fresh session

A self-contained experiment to validate (or refute) the owner's isolation model
for Seon agents, using **mvm** as the candidate microVM substrate. Park-and-run:
everything needed to execute it cold is here. **Append results to the bottom.**

## Why — the decision this informs

**Threat model RESOLVED (owner, 2026-06-26):** per-agent isolation
(container/microVM) is needed **now**, even for a single trusted user — to
contain an agent's **mistakes** (an errant `fs`/process op destroying the host),
not just malice. So `worker_threads` alone is insufficient; we want a real
kernel/container boundary per agent. This may **promote microVM from Tier-2 to
the default** in `architecture.md` §Isolation.

**Candidate substrate: `mvm`** (`reference-code/mvm`,
github.com/tinylabscom/mvm, Apache-2.0, Rust). A multi-backend microVM tool:
Firecracker (Linux/KVM), Apple Container (macOS 26+, Virtualization.framework),
libkrun (macOS AS/Intel + Linux), Cloud Hypervisor. vsock-only guest contract
(no SSH), virtio-fs sharing, checkpoint/restore snapshots, and it's built **for
AI-agent sandboxing** (deny-first egress policy, sandbox types, warm sessions).

**Scope of adoption — important:** adopt mvm's **isolation layer** (microVM +
warm-session + vsock + virtio-fs + egress policy). Do **NOT** adopt its
agent/tool/MCP framework — that's the "LLM calls a sandbox tool to run code"
topology, the *opposite* of Seon's language-IS-the-harness thesis. We keep our
own run-model loop, eval, `/call` capability routing, and DB-as-bus.

## The model to validate (owner's design)

1. **Snapshot-fork:** all agents run the SAME CLJS bundle, differ only by DATA →
   boot the runtime once, **snapshot**, fast-restore per agent (~30ms on
   Firecracker).
2. **Shared-RO store + network writes:** **share (not copy)** the datahike store
   **read-only** into each agent VM (virtio-fs RO) → *zero-cost local read-only
   peers*; **writes go over vsock** → a host broker → the wire-server (single
   writer). This extends today's pod model (local reads, UDS writes) across the
   VM boundary.
3. **Pinned stateful worker:** one warm VM **per agent** — its runtime
   accumulates state (defs / compile-state / globalThis stash) across evals;
   recycle = a fault event (rare), not routine. (NOT a stateless interchangeable
   pool.)

## THE design-deciding question (Stage 1)

**Can a guest read the shared datahike LMDB/konserve store RO over virtio-fs
COHERENTLY while the host writes?** LMDB's reader↔writer coordination (its
lock-table and free-page reclamation) is designed for **one machine**. Across a host-writer /
guest-reader virtio-fs boundary, the guest's reader registration isn't visible to
the host writer → the host could reclaim pages a guest is mid-read → **stale or
torn reads**. virtio-fs DAX (coherent mmap) + konserve's mostly-immutable nodes
*might* save it — **unknown**. This experiment answers it.

- **PASS** → the zero-cost-RO-peer + network-write model holds.
- **FAIL** → reads must ALSO route over vsock, or we share consistent **snapshots**
  (a frozen db value refreshed on tx), not the live store. The design changes.

## Prereqs (this box: Apple Silicon Mac)

- **RECOMMENDED: macOS 26+** — unlocks the native **Apple Container** backend
  (mvm's preferred + best-tested macOS path; libkrun is the fallback) AND native
  VM snapshots (so Stage 4 snapshot-fork can be tested *here*, not only on Linux).
  Not strictly required for Stage 1 (LMDB coherence is OS/backend-independent).
- **libkrun REQUIRED regardless** — the prebuilt `mvmctl` dynamically links
  `libkrunfw` (won't even run `--version` without it). NOT in core Homebrew —
  needs a tap (check the mvm docs `install/macos.md` and `install.sh`; likely
  `slp/krun`).
- **mvmctl:** prebuilt binary skips the Zig/cargo-zigbuild source build:
  `gh release download <vX.Y.Z> --repo tinylabscom/mvm --pattern 'mvmctl-aarch64-apple-darwin.tar.gz'`.
  Needs ad-hoc codesign for the Hypervisor.framework entitlement (`install.sh`
  does it). (As of this session: prebuilt binary already pulled to
  `scratchpad/mvm-spike/`.)
- **Guest image:** prebuilt `default-microvm-rootfs-aarch64.ext4` + `vmlinux-aarch64`
  from the same release (skips Nix), OR the OCI path (`mvmctl machine run --image
  alpine`). The guest needs **Node** (to run our CLJS) + a **datahike-cljs reader**.
- **Already vendored:** `reference-code/mvm` (submodule).

## Staged test plan

- **Stage 0 — substrate:** install libkrun → `mvmctl doctor` green → boot a VM
  (`mvmctl machine run --image alpine -- uname -a`). Proves the box runs a microVM.
- **Stage 1 — THE DECIDER (LMDB coherence):** virtio-fs RO-mount a real datahike
  store (`data/clusters/default/store`) into the guest. In the guest, run a Node +
  datahike-cljs reader doing `d/q` repeatedly under load **while the host
  wire-server writes continuously**. CHECK: reads are consistent + non-corrupt (no
  torn nodes/crashes; a coherent db value; ideally sees new writes after a basis
  refresh). Cross-check guest reads vs host reads at the same basis-t.
- **Stage 2 — vsock write path:** guest → vsock → host broker → wire-server
  `transact!`. Confirm a write from inside the VM round-trips + is visible to the
  host and to other RO peers.
- **Stage 3 — the real runtime:** boot our actual CLJS pod bundle as a
  warm-session guest service; measure cold-start; confirm the agent loop runs
  inside the VM (shared-RO reads + vsock writes).
- **Stage 4 — snapshot-fork (macOS 26 or Linux):** snapshot the booted-runtime
  VM; restore N times; measure restore time; confirm each restored agent pulls
  its own data from the shared store.

## What the results mean

Stage 1 is make-or-break; the rest is engineering once it passes. **Record
results below**, then feed them into `architecture.md` §Isolation and the worker
model (`docs/prds/agent-fsm/agent-runtime-spec.md`). If microVM proves out, it
likely becomes the **default** per-agent boundary (threat model resolved), with
the keystone *buffer-worker-writes-commit-atomically* fix folding into the
vsock-write path.

## Pointers

- Source: `reference-code/mvm`; docs at mvm.sh.
- Architecture: `architecture.md` §Isolation (today: worker_threads+SCI Tier-1 /
  microVM Tier-2). Spec: `agent-runtime-spec.md`. History: `night-loop-log.md`.
- Memory: `project_agent_fsm_night_build_2026_06_26`.

## Results

_(empty — append findings per stage here)_

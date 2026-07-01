---
type: research
status: active
tags: [research, agent]
---

# mvm live-test — can it give us a cheat-proof REPL-in-VM on THIS machine today?

## TL;DR — NO-GO (today, on this box, without installing system deps)

- **tested-live? NO.** I could not boot a microVM, because I could not even
  produce the `mvmctl` binary. The build fails on a **hard host dependency**
  (`libkrun.h`) that is part of the *default* feature set — there is no
  `--no-default-features` escape that drops it.
- **isolation-proven? NO** (could not run the escape attempt — no VM ever
  booted). The isolation claim remains README-level only; this recon did NOT
  upgrade it to live-evidenced.
- **boot time: N/A** — never reached a boot.
- **single biggest blocker:** `mvmctl`'s default build links `libkrun`
  (the macOS 13-25 Apple-Silicon VMM) via the `libkrun-sys` FFI feature,
  which `crates/mvm-cli/build.rs`'s sibling `crates/deps/libkrun-sys/build.rs`
  enforces by probing for `libkrun.h`. It is **not installed** and is only
  available via a third-party Homebrew tap (`slp/krun`). A *second*,
  independent blocker (`zig` + `cargo-zigbuild`) sits right behind it.

The honest result is a precise "couldn't test, here's exactly why and exactly
what it would take" — see GO/NO-GO at the bottom.

## What mvm is (from the source, not guessed)

`reference-code/mvm` is **mvm v0.16.1** — a Rust workspace (`mvmctl` CLI)
that builds and runs microVMs from Nix flakes, with a vsock-only guest
contract (no SSH, ever). Backends (`README.md` §Backends,
`CLAUDE.md` §"Builder backend selection"):

| Backend | Host | Notes |
|---|---|---|
| Firecracker | Linux + `/dev/kvm` | default on KVM |
| Apple Container / **Vz** | **macOS 26+** Apple Silicon | Virtualization.framework, ships with OS, **no lib install** |
| **libkrun** | **macOS 13-25** Apple Silicon / Intel, Linux+KVM | Hypervisor.framework via the `slp/krun` Homebrew trio |
| Cloud Hypervisor | Linux + KVM | opt-in |

**This box is macOS 15.7.7 (`24G720`), Apple M1 Max, arm64.** That is the
**macOS 13-25** tier → the auto-selected backend is **libkrun**, NOT Vz.
Vz (the install-free path) only auto-selects on **macOS 26+**. So on this
machine the dependency-free Apple path is unavailable; mvm needs the
libkrun trio.

The Python SDK (`sdks/python/mvm/_sandbox.py`) — the
`mvm.Sandbox.create(image=…).exec(…)` / `copy_in` / `copy_out` surface from
the README quickstart — is a **thin wrapper that shells out to the `mvmctl`
binary** (`$MVM_CLI_BIN`, `MVM_SDK_MODE=live` → `mvmctl machine run` /
`machine proc start` / `fs write` / `machine stop`). **No `mvmctl` ⇒ the SDK
does nothing.** So the binary is the gate for everything.

## Install / build state on this machine (evidence)

Nothing was on PATH and none of the host deps were present:

```text
$ which mvmctl mvm cargo nix
mvmctl not found
mvm not found
/Users/sean/.cargo/bin/cargo      # rust present (1.96.0)
nix not found

$ brew list | grep -iE 'krun|gvproxy|passt'   # → exit 1, none installed
$ which zig                                     # zig not found
$ cargo install --list | grep zigbuild          # (none)
```

A previous `cargo build --release` had been started (`target/release/deps`
held 323 rlibs) but produced **no `mvmctl` binary** — it had failed at the
same wall I hit below.

### Build attempt 1 — default build, skip embedded binaries

`MVM_SKIP_EMBED_BINARIES=1 cargo build --release --bin mvmctl` (the
`just test-fast` trick that skips the zig cross-compile). It compiled the
whole tree, then **panicked in the `libkrun-sys` build script**:

```text
warning: mvm-cli@0.16.1: MVM_SKIP_EMBED_BINARIES=1: embedding zero-byte
         host-vm stubs; builder-VM boot is unavailable in this build
error: failed to run custom build command for `libkrun-sys v0.16.1`
  thread 'main' panicked at crates/deps/libkrun-sys/build.rs:26:9:
  libkrun-sys feature is enabled but libkrun.h was not found.
  Checked: /opt/homebrew/include/libkrun.h, /usr/local/include/libkrun.h,
           /usr/include/libkrun.h.
  Install libkrun (`brew install libkrun` on macOS, distro package on Linux)
  or point MVM_LIBKRUN_HEADER at the header path.
```

Note the warning even on the skip-embed path: *"builder-VM boot is
unavailable in this build"* — i.e. skip-embed gets you a binary that
**cannot boot a VM anyway**.

### Build attempt 2 — `--no-default-features` (try to drop libkrun)

```text
$ cargo build --release --bin mvmctl --no-default-features
   Compiling mvmctl v0.16.1 ...
error: failed to run custom build command for `libkrun-sys v0.16.1`
  libkrun-sys feature is enabled but libkrun.h was not found.   # STILL on
error: failed to run custom build command for `mvm-cli v0.16.1`
  error: no such command: `zigbuild`                            # 2nd blocker
```

**The `libkrun-sys` FFI feature cannot be turned off from a `mvmctl`
build.** `crates/mvm-cli/Cargo.toml` line 16 lists `libkrun-sys.workspace
= true` as a non-optional dep, and `mvm-cli`'s own feature wiring turns the
`libkrun-sys/libkrun-sys` FFI feature ON in the default `mvmctl` closure
(`cargo tree -e features -i libkrun-sys` shows
`libkrun-sys feature "libkrun-sys" → mvm-cli feature "libkrun-sys"`). So
even with `--no-default-features`, the header probe fires. (mvmd — the
fleet repo — is the consumer that builds lean with
`default-features = false`; `mvmctl` itself does not have that option for
the libkrun FFI.)

And `--no-default-features` *also* surfaced the **second** hard dep:
`mvm-cli/build.rs` cross-compiles the embedded host-VM binaries
(`mvm-host-vm-init`, `mvm-egress-proxy`) as static
`aarch64-unknown-linux-musl` and needs **`zig` + `cargo-zigbuild`**
(README §"Contributor host setup"). Skipping it (`MVM_SKIP_EMBED_BINARIES=1`)
yields a binary that can't boot the builder VM.

## The two blockers, precisely

1. **`libkrun.h` missing (hard).** From the third-party tap, not core
   Homebrew:
   ```text
   $ brew info slp/krun/libkrun
   Error: No available formula ... This command requires the tap slp/krun.
     brew tap slp/krun
   ```
   The full runtime trio per `CLAUDE.md` §"Host dependencies (macOS)":
   `brew install slp/krun/libkrun slp/krun/libkrunfw slp/krun/gvproxy`.
   `libkrunfw` ships a **TSI-patched Linux kernel** baked into the dylib —
   this is a non-trivial system dependency (a custom kernel blob), not a
   light utility.
2. **`zig` + `cargo-zigbuild` missing (hard, for a boot-capable binary).**
   `brew install zig && cargo install cargo-zigbuild`.

Both are **install-time / system changes**. Per the task constraint ("If
`sudo`/system changes are needed, DON'T do them — report what's required"),
I did **not** tap, install, or cross-compile. These are exactly the kind of
host mutations to clear with the owner first.

## macOS-backend verdict

- **Vz (Apple Virtualization.framework, the install-free path): unavailable
  here.** It auto-selects only on **macOS 26+**; this is **macOS 15.7.7**.
  You could *force* `--builder vz` / `MVM_BUILDER_BACKEND=vz` on 13-25 (the
  docs say it's opt-in there), but that path is explicitly "opt-in only…
  auto-detect won't pick it because the deployment baseline is macOS 26+",
  i.e. not the supported/tested config for this OS.
- **libkrun (the supported macOS 13-25 path): plausible but unverified.**
  libkrun uses Hypervisor.framework and *should* run on an M1, but I could
  not get to the point of proving it — the binary won't build without the
  trio, and a boot additionally needs the builder VM to run `nix build`
  inside itself.

## Node / ClojureScript REPL feasibility (for our agents)

Mechanically feasible **if** mvm boots — but unproven and gated behind the
same wall:

- Guest images are **Nix flakes** via `mkGuest` (README §"Building images").
  `nodejs` is a stock nixpkgs package, so a guest with Node (hence a CLJS
  self-host / nbb-style REPL, or a JVM Clojure if you pull the JDK) is just
  a flake entry — no mvm-side work.
- The exec/file contract the SDK exposes (`_sandbox.py`):
  `Sandbox.create(image=…)`, `.exec(...)`, `.copy_in(host, guest)`,
  `.copy_out(guest, host)`, context-manager teardown. `exec` is **dev-tier
  (live mode) only** — sealed/prod templates refuse it (`SandboxDevOnly`).
  That matters: a *cheat-proof* (sealed) image is exactly the one where
  `exec` is **disabled**, so the "agent evals arbitrary CLJS in the VM"
  use-case lives on the **dev/accessible** posture, not the sealed one.
- **Caveat for our use-case:** every guest image is a `nix build` inside the
  builder VM. That's a heavyweight per-image cost and a Nix toolchain
  dependency — a meaningfully different operational model from "spawn a
  process," and worth weighing before adopting it for per-eval sandboxing.

## The isolation / cheat-proofing claim — status

The property we care about ("from inside the guest, you cannot read/edit a
host-side checker that wasn't explicitly shared") is **claim 1** in mvm's
security model (`CLAUDE.md` §"Security model": *"No host-fs access from a
guest beyond explicit shares"*, backed by per-service uid + seccomp +
`setpriv --no-new-privs`). It is **CI-enforced in mvm's own repo**, but on
THIS machine it remains **read-off-the-docs, NOT live-evidenced** — I never
booted a guest to attempt the escape. This recon did not move that needle;
it moved the *operability* needle (we now know exactly why we can't boot it
here yet).

## GO / NO-GO

**NO-GO today on this machine, without owner-approved system installs.**

To turn this into a real live test, the owner (or an approved step) would
need to, on this macOS 15 / M1 box:

1. `brew tap slp/krun && brew install slp/krun/libkrun slp/krun/libkrunfw slp/krun/gvproxy`
   (installs a custom-kernel dylib — a real system dep).
2. `brew install zig && cargo install cargo-zigbuild`.
3. `cargo build --release` → `cp target/release/mvmctl ~/.local/bin/`.
4. `mvmctl doctor` to confirm the resolved backend = libkrun and the trio is
   found, then `mvmctl machine run --image alpine -- sh -c 'uname -a'` for a
   first boot, and only then the isolation-escape attempt + boot/exec timing.

Estimated effort once deps are in: moderate (a clean `cargo build --release`
of a ~4,350-test workspace + a first builder-VM `nix build`, which is itself
minutes the first time). The deps install is the gate, and it's a
deliberate owner decision because of the custom-kernel libkrunfw blob.

**Recommendation:** don't burn hours forcing it. If a cheat-proof
REPL-in-VM is wanted on macOS, the *clean* path is **macOS 26+** (Vz,
zero lib install) or a **Linux+KVM box** (Firecracker, the Tier-1 path) —
the README itself says "macOS can't run live Firecracker microVMs
natively" and points the full suite at a Hetzner KVM box. On THIS macOS 15
machine, mvm is installable but only via the third-party libkrun trio, and
that's an owner call.

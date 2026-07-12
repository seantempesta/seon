---
type: research
status: completed
tags: [research, agent]
---

# Multi-arch Docker build — Seon canonical image on arm64 AND amd64 (2026-07-06)

`docker/Dockerfile` now builds BOTH `linux/arm64` (native on Apple Silicon)
and `linux/amd64` (emulated under Rosetta). Goal: the project runs on both
processor types, unblocking both bench families — SWE-bench Verified's arm64
instance images AND TB-2's amd64 task images. Both arches were built,
boot-proven end-to-end (wire-server + pod + a real DeepSeek `/agents/run`
reply), and extracted into per-arch overlay volumes.

## Dockerfile changes

Only the three arch-hardcoded downloads were touched; every other layer
already adapts (multi-arch `eclipse-temurin`/`debian` bases, in-stage
`npm ci` so `@vscode/ripgrep` gets the right platform binary, arch-independent
CLJS/Clojure bytecode). Added `ARG TARGETARCH` (buildx auto-populates it) to
the `build` stage; each download now selects asset-name + sha256 from it via a
`case "$TARGETARCH"`. Naming diverges per tool — encoded exactly:

| Tool     | arm64 asset | amd64 asset |
|----------|-------------|-------------|
| Node     | `…-linux-arm64.tar.xz`   | `…-linux-x64.tar.xz`   |
| JRE      | `…_aarch64_linux_…`      | `…_x64_linux_…`        |
| babashka | `…-linux-aarch64-static` | `…-linux-amd64-static` |

### Per-arch sha256 (source of each checksum)

| Tool | arch | sha256 | source |
|------|------|--------|--------|
| Node 22.23.1 | arm64 | `0294e8b915ab75f92c7513d2fcb830ae06e10684e6c603e99a87dbf8835389c1` | `nodejs.org/dist/v22.23.1/SHASUMS256.txt` (unchanged — was already verified) |
| Node 22.23.1 | amd64 | `9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578` | same `SHASUMS256.txt`, `node-v22.23.1-linux-x64.tar.xz` line |
| Temurin 25.0.3+9 JRE | arm64 | `d12d5b19ff7f6c4a99fd4f9eecede2c96e64df7d1f41cc84f2e9c9b38408600b` | `…/OpenJDK25U-jre_aarch64_…tar.gz.sha256.txt` (unchanged — reverified, matches) |
| Temurin 25.0.3+9 JRE | amd64 | `487ad434d8b121ae3902d5ad9cb830cd8e1f75fefad6e2ba80f89d60e3db95d7` | `…/OpenJDK25U-jre_x64_…tar.gz.sha256.txt` |
| babashka 1.12.218 | arm64 | `e9e9190afb0dd33abbcd3aa6c1382184a88a5498800324719be3be6e1aa68302` | release asset `…-linux-aarch64-static.tar.gz.sha256` sidecar |
| babashka 1.12.218 | amd64 | `7bd028cc794732ffde3da31ce4379840893c8e54f1046f92a8dfc4f4b3cddaf8` | release asset `…-linux-amd64-static.tar.gz.sha256` sidecar |

Note: babashka previously had NO sha-check in the Dockerfile; a verified
sha256 was ADDED for both arches (the task requires every download be
sha-verified). Both build logs show `node.tar.xz: OK / jre.tar.gz: OK /
bb.tar.gz: OK` — every download verified on both arches.

## Build commands (buildx, per-arch local tags)

`--load` can't hold a multi-platform manifest in the default docker image
store, so each arch is built to its own tag. The DEFAULT builder
(`desktop-linux`, docker driver) already does amd64 emulation via Rosetta —
no `docker buildx create` / `docker-container` builder was needed.

```bash
docker buildx build --platform linux/arm64 -f docker/Dockerfile -t seon:multiarch-arm64 --load .
docker buildx build --platform linux/amd64 -f docker/Dockerfile -t seon:multiarch-amd64 --load .
```

## Build wall time — native vs emulated

| | arm64 (native) | amd64 (emulated, Rosetta) |
|---|---|---|
| **total** | **86s** | **197s (~3.3 min)** |
| npm ci | 1.7s | 3.1s |
| clojure prep (m2+gitlibs+datahike Java) | 27.8s | 59.9s |
| cljs compile client | 15.9s | 67.8s (~4.3×) |
| cljs compile bootstrap | 7.1s | 30.5s |

Both were cold with respect to their own arch (arm64 and amd64 share no layer
cache — different platform). The emulated cljs compile is the tallest pole
(~4.3× native), consistent with Rosetta-accelerated x86 JIT.

### Emulated-cost verdict

**amd64-under-Rosetta is PRACTICAL for iteration — not a native-only/CI job.**
The whole emulated build is ~3.3 min (only ~2.3× the 86s native), and the
emulated stack boots and answers in ~18s. Rosetta acceleration keeps it in the
"just build it locally" regime; no separate native-amd64 runner is required for
routine work. (A native-amd64 CI job is still the right home for the eventual
`--push` multi-platform manifest, but iteration does not need one.)

## Boot proofs (the load-bearing evidence)

Each image: `docker run` with a fresh named volume, published on 127.0.0.1,
`DEEPSEEK_API_KEY` from env; waited for pod HTTP, then one real
`POST /agents/run` (`{"input": …, "timeout_ms": 90000}` — the door contract
from `src-inspect-ai/.../solver.py:pod_run`). Task: "19 times 23" → expect
`437`. Both replied correctly with `closed_reason :completed`.

| | arm64 (native, port 7997) | amd64 (emulated, port 7996) |
|---|---|---|
| `node process.arch` | arm64 | x64 |
| wire-server ready | 4s | 15s |
| pod listening / auto-boot ready | ~10s | ~28s |
| `/agents/run` reply | **`437`** | **`437`** |
| closed_reason | `:completed` | `:completed` |
| elapsed_ms | 7804 | 17949 |
| turns / evals | 2 / 2 | 3 / 6 |
| model | deepseek / deepseek-v4-pro | deepseek / deepseek-v4-pro |

**This is the first time the Seon stack has ever run as amd64** (design §10
falsifier previously held only on arm64). It holds: bundled JRE 25 + Node
22.23.1 exec directly from `/opt/seon`, the JVM Vector API (`jdk.incubator.vector`,
proximum SIMD) JIT-portable to x86 AVX under emulation, DeepSeek egress from
inside the emulated container. No portability issue found — no `src/seon` change
was needed.

Raw: `arm64-agents-run.json`, `amd64-agents-run.json`,
`{arm64,amd64}-build-steps.txt`.

## Overlay volumes (unblocks both bench families)

Each image's `/opt/seon` tree extracted into its own named volume (mirrors the
slice-3 `cp -a /opt/seon/.` extraction):

```bash
docker volume create seon-runtime-arm64
docker run --rm --entrypoint sh -v seon-runtime-arm64:/dst seon:multiarch-arm64 \
  -c 'cp -a /opt/seon/. /dst/'
docker volume create seon-runtime-amd64
docker run --rm --platform linux/amd64 --entrypoint sh -v seon-runtime-amd64:/dst \
  seon:multiarch-amd64 -c 'cp -a /opt/seon/. /dst/'
```

| volume | size | source image id (digest) | for |
|--------|------|--------------------------|-----|
| `seon-runtime-arm64` | 733.6 MB | `sha256:a69721a0b899ee5351e459c921b51b485ebb919817e39f912616204dcbbfb664` | SWE-bench Verified arm64 instance images |
| `seon-runtime-amd64` | 741.6 MB | `sha256:396680152e53409dab20228ae2fc995bec92da9b010fb6255864c6e34ea1c78f` | TB-2 amd64 task images (the tb2-unit blocker) |

(amd64 overlay sanity-checked: `cp.txt`, `out/client/main.js`, `jre/bin/java`,
`node/bin/node` all present.)

### Follow-up (not done here)

The existing `seon-runtime-slice3` (733 MB) is the OLD arm64 overlay off
`seon:slice1` — left untouched. The SWE-bench arm should REPOINT from
`seon-runtime-slice3` to `seon-runtime-arm64` in a follow-up (both are arm64
`/opt/seon` overlays; `seon-runtime-arm64` is the current-Dockerfile one). Not
changed here to avoid touching the eval lane's pinned harness mid-flight.

## Files

- `arm64-agents-run.json`, `amd64-agents-run.json` — the `/agents/run` replies
- `arm64-build-steps.txt`, `amd64-build-steps.txt` — per-step build logs (durations + sha `: OK` lines)

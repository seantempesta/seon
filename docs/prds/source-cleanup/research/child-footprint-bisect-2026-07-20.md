---
type: research
status: active
tags: [research, agent, architecture]
---

# Execution-child footprint bisect (2026-07-20)

Phase-by-phase memory bisect of one Bun execution child on the live default
cluster, plus controlled artifact-load probes with the same vendored Bun
(`reference-code/bun/build/release/bun`). Follows up
`docs/seon/issues/execution-child-gpu-allocation-dominates-footprint.md`
(mimalloc os_tag 100 mislabeled as IOAccelerator; bare-bun floor 5.9 MB).

## Method

- Controlled loads: `tmp/child-bisect/bisect_load.js` stubs `process.exit`
  and `process.send`, `require`s the exact artifact, swallows the expected
  startup-identity failure, force-GCs, and idles for external `vmmap`.
  This executes every compiled namespace's top-level forms exactly as a real
  child does, without a database session or admission.
- Release bundle built with the EXISTING release machinery only
  (`script/seon/dev/artifact.clj` `build-release-programs!` command shape):
  `SHADOW_CLJS='{:cache-root "tmp/child-bisect/cache"}' clj -M:cljs release
  execution --force-spawn --config-merge '{:output-to
  "tmp/child-bisect/execution/main.js" ...}'` → one 7.5 MB `:simple` file
  (264 files compiled) vs the dev artifact's 58 KB loader + 933 files /
  44 MB `cljs-runtime`.
- Live child: fresh idle agent `smooth-humans-raise` (POST /agents, no
  message), driven from the pod REPL through the one production path
  (`seon.execution.host/invoke-compiled!` → `render-agent-view!`,
  `seon.agent.turn/render-prompt`, `seon.agent.turn/eval-parsed!`),
  `vmmap`ed between invocations (pid 12163). In-child introspection ran
  through the child's own self-host eval (`bun:jsc` heapStats,
  `process.memoryUsage`, `@cljs.env/*compiler*`). Agent terminated after.

## Numbers (vmmap Physical footprint; mimalloc = the "IOAccelerator" dirty)

| Phase | Footprint | mimalloc dirty |
|---|---|---|
| bare vendored bun (`bun -e 'setInterval(...)'`, prior baseline) | 5.9 MB | ~2.7 MB |
| dev artifact loaded (933 files, wrapper) | 89.1 MB | 80.9 MB |
| release `:simple` single bundle loaded (wrapper) | 104.6 MB | 97.1 MB |
| dev artifact loaded under `bun --smol` | 90.0 MB | 82.2 MB |
| dev artifact under node 24 (for comparison) | 118.0 MB | n/a |
| release bundle under node | 114.0 MB | n/a |
| dev artifact with `BUN_JSC_forceRAMSize=64Mi` | 76.2 MB | 68.3 MB |
| LIVE child: ready + one `render-agent-view!` | 180.4 MB | 167.4 MB |
| + first `render-prompt` (ctx render, program prepare) | 214.5 MB | 200.1 MB |
| + first `eval-parsed!` (`(+ 1 2)` batch) | 220.8 MB | 202.5 MB |
| + heavy eval burst (MB-scale `pr-str` probes), then full GC + >60 s | 416.3 MB | 387.3 MB |

In-child steady state after the phases: JSC heap 124 MB live / 348 MB
capacity (`bun:jsc` heapStats after `Bun.gc(true)`), 1.70 M objects —
1.02 M plain Objects, 287 K Arrays, 181 K strings, 141 K Functions, 97 K
JSLexicalEnvironments. Self-host compile-state holds 107 namespaces of
analyzer state, ~5.8 MB as EDN text (a low-tens-of-MB object graph).

## Findings

1. **The dev-artifact hypothesis is falsified.** The compact release
   bundle loads to a HIGHER footprint (104.6 vs 89.1 MB), and node is no
   better (114–118 MB). The ~85–100 MB load cost is the compiled Seon
   program itself — executing every namespace's top-level forms (cljs.core
   plus seon.* metadata, schemas, closures) — not shadow's dev module graph,
   file count, or a Bun defect. A single-bundle child artifact is NOT a
   shrink lever.
2. **Child startup roughly doubles the load cost: +91 MB.** Between
   artifact-load (89 MB) and first-ready (180 MB) the child runs only
   `db/open-session!` + `admission/prepare-committed!` +
   `admit-prepared!` (`seon.execution/start-child!`). The committed
   projection reconstruction / schema activation dominates this band.
   Datahike datoms stay on the JVM authority (the wire carries database
   coordinates, not indexes), so this is projection/validator/program
   state, not a replica copy.
3. **First prompt render costs +34 MB** (context render, program prepare,
   and self-host compile-state install); **the first eval after it costs
   only +6 MB** — the bootstrap analyzer caches are already resident by
   then. The bootstrap/compile-state axis is a minor share (~½ of the
   34 MB band, low tens of MB), not the driver.
4. **Footprint is peak-shaped, not live-shaped.** After a burst of large
   allocations the JSC heap capacity (348 MB) and mimalloc dirty pages
   (387 MB) are retained indefinitely even though live heap returns to
   ~124 MB after full GC. One heavy turn permanently inflates a child from
   ~220 MB to ~416 MB. This — not JS-level retention — explains
   execution-children-retain-hundreds-of-megabytes.
5. **Mislabel confirmed and relabelable.** `MIMALLOC_OS_TAG=240` makes the
   "IOAccelerator" regions render as `Memory Tag 240`
   (`reference-code/bun/vendor/mimalloc/src/options.c:143`, env prefix
   `mimalloc_` at `:643`). Evidence-only; a spawn-env line would make
   future profiles honest.
6. **GC pressure tuning works modestly:** `BUN_JSC_forceRAMSize=64Mi`
   cut load footprint 89→76 MB (–15%); `--smol` did nothing at load.
   forceRAMSize should also bound finding 4's burst retention (untested at
   turn scale).

## Verdict

Per-child steady cost ≈ 180 MB idle-ready, ≈ 220 MB after one prompt+eval,
degrading toward ~400 MB with heavy turns. At N=100 children: **18–22 GB
steady, worst-case ~40 GB** — the 2–4 GB goal is NOT reachable by artifact
packaging, and the issue's ≤50 MB acceptance cannot be met by any existing
build-config flip. The breakdown:

| Share | MB | Lever |
|---|---|---|
| bun floor | 6 | none needed |
| loading the full seon.* program | ~85–100 | shrink the child's require closure (a child needs eval + render + db session, not the whole pod graph); PRD-level |
| session + admission projection | ~91 | leaner child admission (share/prune the committed projection); PRD-level |
| prompt render + compile-state | ~34 | partly bootstrap-cache trim; minor |
| first eval | ~6 | none needed |
| burst retention | unbounded | `BUN_JSC_forceRAMSize` cap + mimalloc purge tuning; cheap experiment, real candidate |

No genuinely-simple fix exists, so no production change ships with this
research. The two cheap follow-ups worth a bounded unit: (a) spawn-env
`BUN_JSC_forceRAMSize` (+ `MIMALLOC_OS_TAG` for honest labels) measured at
turn scale; (b) a dependency-closure audit of `seon.execution.runtime`'s
require graph to size the reachable-code lever before designing anything.

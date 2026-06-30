---
type: orchestrator
status: active
tags: [orchestrator, diffusion, agent, flow]
---

# Co-location performance plan — collapse the oracle round-trip, prep everything offline

> The control loop is starved by ROUND-TRIPS, not by computation. The oracle code
> is already fast (bb parse 0.048 ms warm, node cljs.js eval ~2.6 ms warm — both
> measured). The refine loop measured ~3-4 tok/s effective because every renoise
> iteration pays a full network/process round-trip to reach that fast code. This
> doc designs the fastest co-located architecture and a GPU test plan where ALL
> build/debug is done OFFLINE, so the A100 session is `deploy → verify_fresh →
> measure N` with nothing built during paid GPU time.

## TL;DR

- **The bottleneck is the round-trip, and TODAY it is paid TWICE.** The decode is
  ~130-140 tok/s (~17 tok/forward). The refine loop measured ~3-4 tok/s because the
  *current* closed-loop driver (`tmp/flash-diffgemma/closed_loop.py`) reaches the
  oracle two slow ways at once: (1) the GPU↔driver hop is an **internet RunPod API
  round-trip** (`urllib` to `api.runpod.ai/v2/$EP/run` + a 3 s status poll —
  `closed_loop.py:9-21`), and (2) the oracle itself is **spawned fresh per call**
  (`subprocess.run(["bb","bin/oracle-server"], …)` — `closed_loop.py:24-30`),
  paying bb's ~21 ms cold start EVERY call. Neither uses the persistent-server
  design that already exists (`oracle_shim.py`).
- **Both round-trips collapse with co-location + a persistent server.** Measured
  here, live, this session:
  - persistent bb parse server, warm per-call: **0.048 ms mean** (p50 0.047, min
    0.041, n=500) over the real stdin/stdout pipe;
  - the same call done spawn-per-call (today's `closed_loop.py` path): **20.8 ms
    mean** (p50 20.7, n=15) — a **~430× penalty just from not persisting**, and
    that is BEFORE the internet hop.
  So the fix is two independent levers, both already half-built: **(a) co-locate**
  (kill the internet hop — run the loop inside the worker container), and **(b)
  persist** (use `oracle_shim.py`'s spawn-ONCE server, not `subprocess.run` per
  call).
- **Optimal architecture:** always-on persistent servers started ONCE at worker
  warm-up (beside the model load), reached over a **stdin/stdout pipe** (the
  sequential denoise loop has one in-flight request — a pipe is µs-class and
  simplest; UDS only when a *second concurrent* caller appears). **Two processes
  (bb parse + node eval), not one** — different runtimes, different cadence
  (parse-per-step ~0.05 ms, eval-at-checkpoint ~2.6 ms), and a crash of either is
  isolated from the model. **V8 hotness:** the node eval process does a warmup eval
  at boot so JIT is hot; bb is GraalVM-native AOT already.
- **Eval speed is NOT on the critical path; IPC + persistence is the whole game.**
  A ~2.6 ms warm eval against a ~250 ms GPU forward is ~1 %. Optimizing eval below
  2.6 ms buys nothing measurable; the entire win is removing the ~20-200 ms
  round-trip. Spend the effort on persistence + co-location, not on a faster eval.
- **Pipelining is the second-order win after co-location.** Parse is so cheap
  (0.05 ms) it just runs serially between steps — no benefit to overlapping. The
  ~2.6 ms eval and any retrieve CAN overlap the next GPU forward (async), hiding
  under the ~250 ms step. Co-locate first (the 430×-to-4000× lever); pipeline the
  eval/retrieve checkpoint second (a ~1 % refinement).
- **All-prep-offline GPU plan (A-D):** A. per-iteration oracle latency
  (co-located persistent pipe vs the network path); B. end-to-end refine-loop
  tok/s; C. the KV-cache 62 %-prefill win (needs the image — Cache can't ride
  JSON); D. the `entropy_bound`/tokens-per-forward sweep (the never-swept raw-speed
  lever). Each lists its offline prep so the GPU session is pure measurement.

---

## 1. Bottleneck analysis (numbers, grounded)

### 1.1 Where the time goes

| Stage | Cost | Source |
|---|---|---|
| One GPU denoise forward | ~250 ms (≈17 tok at ~70 tok/s/forward into the ~130 tok/s aggregate) | CLAUDE.md result fields `tokens_per_forward`, `tok_per_s`; ~130-140 tok/s decode |
| bb parse, **persistent** warm | **0.048 ms** mean (p50 0.047, min 0.041, n=500) | measured live this session, real pipe (`oracle_shim.py` self-test) |
| bb parse, **spawn-per-call** | **20.8 ms** mean (p50 20.7, n=15) | measured live this session (reproduced `closed_loop.py:24-30`) |
| bb parse, cold (spawn→1st resp) | 49.4 ms once | measured live; one-time per warm worker |
| node cljs.js eval, warm | ~2.6 ms mean / p99 6-8 ms | `colocated-oracle-package-design-2026-06-28.md` §"DONE" (offline-proven) |
| node cljs.js eval, cold init | ~276 ms once (loads ~15 MB bootstrap cache) | same; one-time, amortized |
| Internet RunPod API round-trip | tens-to-hundreds of ms + a **3 s status poll** | `closed_loop.py:9-21` (`urllib` → `api.runpod.ai`, `poll=3`) |

The arithmetic: a refine loop of K renoise iterations, each costing `forward (250
ms) + oracle round-trip`. With a co-located **persistent** oracle the round-trip is
~0.05 ms — the loop is forward-bound at ~130 tok/s. With the current driver each
iteration adds an internet API call + a 3 s poll granularity + a 21 ms bb respawn,
so K iterations collapse to ~3-4 tok/s effective. **The oracle computation was
never the problem** (0.05 ms ≪ 250 ms); the round-trip and the respawn are.

### 1.2 Two independent round-trips, both removable

1. **The internet hop (GPU↔driver).** `closed_loop.py` runs ON A LAPTOP and drives
   the GPU over the RunPod public API (`run` + `status/<id>` poll). Every
   denoise_to_step / resume_renoise is a separate job submission. Co-location moves
   the loop INTO the worker container (`gpu_worker.py` calls the local oracle
   between its own denoise steps — `co-location-image-build-2026-06-28.md` §5), so
   there is no API hop and no 3 s poll: the loop is a tight in-process Python loop.
2. **The spawn-per-call tax (driver↔oracle).** Even inside the container, calling
   `subprocess.run(["bb", …])` per checkpoint pays bb's ~21 ms cold start every
   time. The fix is the EXISTING `oracle_shim.py` `Oracle` class: `Popen` ONCE at
   warm-up, write one line per checkpoint, read one line back — 0.048 ms warm. The
   shim is built and offline-proven; the gap is that `closed_loop.py` doesn't use
   it (it spawns fresh). The image-wiring snippet (`co-location-image-build §5a`,
   `_oracle()` caching into `_CACHE`) is the persistent path — adopt it.

Net: co-location removes hop (1); the persistent shim removes tax (2). Both are
necessary; either alone leaves a slow leg.

---

## 2. The optimal architecture (each choice justified)

### 2.1 Always-on persistent servers (spawn ONCE at worker boot)

Spawn both oracle servers in the worker warm-up, cached in the module-level
`_CACHE` beside the model (`gpu_worker.py:31`, `_load` at `:50`), so they live for
the warm worker's lifetime and die with the container on scale-to-zero (no explicit
teardown needed — `co-location-image-build §5c`):

```python
# at warm-up, alongside _load (once per warm worker):
def _oracle(kind):                       # kind ∈ {"parse","eval"}
    key = f"oracle_{kind}"
    if key not in _CACHE:
        from oracle_shim import Oracle
        argv = {"parse": ["bb",   "/opt/seon/bin/oracle-server"],
                "eval":  ["node", "/opt/seon/oracle-eval.js", "--serve"]}[kind]
        _CACHE[key] = Oracle(argv)       # Popen ONCE, line-buffered, persistent
    return _CACHE[key]
```

- **Why spawn-once:** measured 0.048 ms warm vs 20.8 ms spawn-per-call — a 430×
  difference that is the dominant non-network cost. The persistent server amortizes
  the ~21 ms bb / ~276 ms node cold init across the entire warm-worker lifetime;
  against the ~66 s model load both cold starts are noise (<0.5 %).
- **Supervision:** the servers are children of the Python worker process. If one
  exits, `_oracle()` should detect a dead `Popen` (`p.poll() is not None`) and
  respawn lazily on the next call — a 21 ms / 276 ms re-warm, rare, isolated. (Add
  the liveness check to the shim; today it assumes the child is alive.)
- **Readiness:** bb's first call pays cold start transparently. The node eval server
  writes `"ready\n"` to stderr after the bootstrap cache loads
  (`worker_eval.cljs` `serve!` `:381`) — the shim should block on that READY line
  before the first eval (the cljs.js init is async). Today `oracle_shim.py` does
  NOT wait for ready; add a `ready_after()` that reads the stderr ready sentinel for
  the eval server (parse needs none — it's synchronous-on-first-call).

### 2.2 Fastest IPC — stdin/stdout pipe (UDS only on concurrency)

| Transport | Latency | Concurrency | Framing | Verdict |
|---|---|---|---|---|
| **stdin/stdout pipe** | **µs-class** (measured 0.048 ms incl. JSON encode/decode + bb compute) | one in-flight | one JSON object per line (built) | **CHOSEN** — matches the strictly-sequential denoise loop |
| Unix domain socket | µs-class (~same as pipe) | many concurrent + request ids | same JSON-line frames | promote to THIS when a 2nd concurrent caller appears |
| Named pipe / FIFO | µs-class | one writer/reader | manual framing | no advantage over the Popen pipe; more setup |
| Shared memory | sub-µs raw copy | needs a sync protocol | hand-rolled | over-engineering — the 0.05 ms is already <0.02 % of a forward |
| Loopback HTTP | ms + parse + a TCP handshake/keepalive | concurrent | HTTP framing overhead | **rejected** — strictly worse than UDS, no benefit |

**Decision: keep the stdin/stdout pipe.** The denoise loop is sequential (one
checkpoint at a time, `colocated-oracle-package-design §4`), so there is exactly one
in-flight request — head-of-line blocking is a non-issue and the pipe is the
simplest deployable that hits 0.048 ms. The measured 0.048 ms ALREADY includes the
JSON encode + pipe write/flush + bb parse + read — i.e. the full round-trip, not
just compute. There is no latency left to extract by switching transports; shared
memory would shave µs off a number that is already 0.02 % of a GPU forward.

**Promotion trigger (documented, not built):** the moment a SECOND concurrent caller
exists — e.g. an out-of-band `retrieve` fired while a denoise stream is mid-flight,
or multiple model streams sharing one sidecar — switch to a loopback **UDS**: the
node bundle opens `net.createServer` on `/tmp/seon-oracle.sock`, frames the SAME
`{op,…}` JSON lines, Python connects with `AF_UNIX`. The `op`/`id` envelope is
transport-agnostic (`colocated-oracle-package-design §4`), so this is a transport
swap with ZERO handler changes. Do not reach for HTTP.

**Expected per-iteration oracle latency, co-located + persistent:**
- parse-only checkpoint: **~0.05 ms** (negligible against the 250 ms forward);
- eval checkpoint: **~2.6 ms** (~1 % of a forward);
- vs the network path it replaces: tens-to-hundreds of ms + 3 s poll granularity.

### 2.3 V8 / native hotness

- **bb (parse):** GraalVM-native AOT — no JIT warmup needed; the only cold cost is
  the `require` + rewrite-clj classpath load (~21 ms once). Nothing to tune.
- **node (eval):** runs on V8. Two cold costs: V8 JIT + the ~15 MB bootstrap
  analysis-cache load (~276 ms once, `worker_eval.cljs`). Keep the process
  persistent AND do a **warmup eval at boot** (e.g. eval `(defn _w [x] (* x x))`
  right after `init-state!` resolves) so the hot compile+eval path is JIT-compiled
  before the first real checkpoint. This turns the first real eval from a
  JIT-cold-path outlier into the steady ~2.6 ms.
- **Is cljs.js self-host (2.6 ms incl. per-eval compile) the right tool?** YES, for
  the eval tier — it is the only path with TRUE CLJS semantics (real analyzer +
  emitter), so it reads `^:async`/`await` + js interop (`(.json x)`) WITHOUT the
  false negatives bb-SCI / cljs-SCI throw on that surface
  (`worker_eval.cljs` ns docstring "Why cljs.js self-host"). The worker emits
  CLJS-flavored code, so fidelity is load-bearing — a faster-but-wrong eval poisons
  the control signal. And 2.6 ms is ~1 % of a forward, so its speed is irrelevant.
  - **A cheaper parse-only fast path already exists** and IS worth using: when a
    checkpoint only needs "is the canvas well-formed" (the per-step renoise
    signal), call the bb **parse** server (0.05 ms), NOT eval. Reserve the 2.6 ms
    eval for the sparser "does it RUN" checkpoint. This is the natural
    parse-per-step / eval-at-checkpoint split (`colocated-oracle-package-design
    §"DONE" step 3`), and it means the expensive tier fires rarely.
  - **Pre-compile/cache common forms?** Not worth it. The 2.6 ms includes the
    compile, but eval is the sparse tier and 2.6 ms ≪ 250 ms. Caching compiled JS
    by form-hash would add machinery to shave a sub-1 %-of-forward cost. Skip until
    measured to matter.

**Honest bottom line on "as fast as C":** eval-speed optimization pays off NOWHERE
on this critical path — 2.6 ms eval vs 250 ms forward. The ONLY thing that matters
is (a) co-location (kill the network hop) and (b) persistence (kill the respawn).
Both are about removing round-trips, not making the computation faster.

### 2.4 Batching / pipelining

- **Parse (0.05 ms):** run it serially between steps. It is 0.02 % of a forward —
  pipelining buys nothing and adds async complexity. Keep it inline.
- **Eval (2.6 ms) + retrieve:** these CAN overlap the next GPU forward. The denoise
  loop is `forward → (oracle) → forward`; the eval verdict for step N's canvas is
  only needed to decide step N+1's renoise. So FIRE the eval async right after step
  N's forward, let it compute (~2.6 ms) WHILE step N+1's forward is already running
  (~250 ms), and consume the verdict before step N+1 completes. The eval thus hides
  entirely under the next forward — its 2.6 ms becomes free.
- **Is the loop serial or pipelineable?** The *generate* steps are serial (each
  forward depends on the prior canvas). But the *oracle* call for step N is
  independent of step N+1's forward START — only its RENOISE decision depends on the
  oracle. So: pipeline = launch the oracle async, begin the next forward
  immediately, apply renoise when the verdict lands. This is a clean overlap because
  the GPU and the bb/node sidecars are different execution resources (GPU vs CPU
  processes) — they run truly concurrently.
- **Sequencing:** co-locate + persist FIRST (the 430×-to-4000× lever). Pipeline the
  eval/retrieve checkpoint SECOND (a ~1 % refinement, only after A/B prove the
  co-located loop is forward-bound). Do not build pipelining before measuring —
  parse is already inline-free and eval may be sparse enough not to need it.

### 2.5 One process vs two — TWO (bb parse + node eval)

Keep them SEPARATE, with the same `{op,…}` JSON-line contract (the settled shape —
`colocated-oracle-package-design §"DONE" step 4`):

- **Different runtimes.** bb is GraalVM-native (~80 MB binary, no build, ~21 ms
  cold); node cljs.js is V8 + the ~15 MB bootstrap cache (~276 ms cold). There is
  no single runtime that does both cheaply — bb-SCI can't do faithful CLJS eval
  (false negatives on async/interop), and running cljs.js for the per-step PARSE
  would pay 2.6 ms where bb pays 0.05 ms (a 50× regression on the HOT tier).
- **Different cadence.** Parse is per-step (every denoise step, 0.05 ms); eval is
  per-checkpoint (sparse, 2.6 ms). Coupling them forces the hot tier onto the heavy
  runtime.
- **Crash isolation.** Two sidecars, each a child of the worker; either can die and
  respawn without touching the model (a model crash = ~66 s reload). One combined
  process is a single point of failure for both tiers.
- **The IPC-hop count is NOT the bottleneck.** "Fewer processes = fewer hops" is
  true but irrelevant here — each hop is 0.05 ms (pipe). The Python worker is the
  single orchestrator; it talks to the parse pipe per step and the eval pipe per
  checkpoint. There is no chained bb→node hop; both answer Python directly. So two
  processes add ZERO extra hops on any single call — each op goes Python→(one
  sidecar)→Python.

**Verdict: two persistent processes, one shared `{op,…}` contract, Python picks the
pipe per op.** This is exactly what `oracle_shim.py` + the `_oracle(kind)` wiring
already express; the only gap is making `_oracle` cache BOTH and adding the eval
ready-wait + liveness respawn.

---

## 3. Critique of the existing co-location plans

The plans are sound and the hard parts (the persistent shim, the byte-identical
parse contract, the node eval bundle, the minimal-src manifest) are BUILT and
offline-proven. What to KEEP and what to CHANGE for performance-optimality:

### Optimal as-is (keep)

- **bb for parse, node for eval, split by tier** (`colocated-oracle-package-design
  §2`). Correct: parse is structural (bb reads CLJS-flavored forms bit-identically,
  0.05 ms), eval needs true CLJS (cljs.js). The fidelity argument is right and the
  measured costs confirm the split.
- **Persistent line server, spawn-once** (`oracle_shim.py`). The 0.048 ms warm vs
  20.8 ms spawn-per-call measurement (this session) validates the whole premise.
- **stdin/stdout pipe, one JSON object per line, `op`/`id` envelope** (§4). Optimal
  for the sequential loop; UDS promotion path documented. Correct call.
- **Reject GraalVM in-process polyglot** (§2). The repo's own sidecar-spike proves
  in-process Substrate-VM + PyTorch SIGSEGVs (`sidecar-spike/prd.md:21`); a crash =
  66 s reload. And the IPC tax it removes (0.05 ms) is noise. Correct rejection.
- **Minimal-src image layer = ONE `.cljc` + bb binary, +0.5 % image**
  (`co-location-image-build §2`). Tight; the build-time oracle gate fails the BUILD
  on a broken bundle (good — no live-worker surprise).

### Change / add for performance-optimality

1. **The closed-loop driver must use the PERSISTENT shim, not `subprocess.run`.**
   `closed_loop.py:24-30` spawns bb fresh per call (20.8 ms each, measured) — this
   is the single biggest unforced error and it is in the driver, not the design.
   When the loop moves in-process (image §5), use `_oracle()` (the cached `Oracle`)
   — NOT a per-call `subprocess.run`. Flag: the committed offline driver demonstrates
   the SLOW pattern; the in-worker wiring must demonstrate the persistent one. (Fix
   = OFFLINE-PREP task O3 below.)
2. **`_oracle()` must cache BOTH servers + add eval-ready-wait + liveness respawn.**
   The image §5a `_oracle()` snippet caches one server and assumes it stays alive
   and is ready. Add: (a) a `kind` arg caching parse AND eval; (b) for eval, block
   on the `"ready\n"` stderr sentinel before the first call (cljs.js init is async,
   `worker_eval.cljs:381`); (c) a `p.poll()` liveness check that lazily respawns a
   dead child. Small, OFFLINE-buildable (task O2).
3. **Add the node-eval V8 warmup eval at boot** (§2.3) so the first real eval is hot
   (~2.6 ms steady, not a JIT-cold outlier). One line after `init-state!` resolves.
   OFFLINE (task O2).
4. **Pipeline the eval/retrieve checkpoint async** (§2.4) — NOT in the current plans.
   It is a ~1 % refinement, so do it AFTER A/B prove the co-located loop is
   forward-bound; but note it now so the in-worker loop is structured to allow it
   (fire-and-consume rather than block). Design-only for now.
5. **KV-cache reuse needs the co-location image** (`kv-section-caching §6`,
   `gpu-session-run-order §"Second-session track"`) — correctly out of the stock
   battery. The CPU-proxy already de-risks the general crop+reuse mechanism
   (bit-exact, `kv-section-caching §6a`); only the DiffusionGemma hybrid-cache
   bit-exactness is GPU-only. Keep this on the image track (test C).

Nothing in the plans is wrong; the one *performance* gap is that the demonstrated
driver (`closed_loop.py`) shows the spawn-per-call anti-pattern, and the in-worker
`_oracle()` needs the both-servers + ready + liveness hardening. Both are offline
fixes.

---

## 4. The GPU test plan — all prep offline, GPU = pure measurement

Each experiment is `deploy → verify_fresh → run measurement N`. Everything below
"OFFLINE PREP" is DONE before the A100 is warm; nothing is built or debugged on GPU
time. Discipline (from `gpu-session-run-order.md`): NOTHING measured before
`verify_fresh` prints FRESH; a knob change is a MOVED number with `worker_sha` +
`params`; scale-to-zero = $0 idle / ~66 s reload.

### A. Per-iteration oracle latency — co-located persistent pipe vs network

**Measures:** the round-trip delta — the core thesis. Co-located persistent pipe
(~0.05 ms parse / ~2.6 ms eval) vs the network path (the RunPod API hop the current
driver pays).

- **Win condition:** co-located persistent per-call oracle latency ≤ 0.2 ms (parse)
  / ≤ 5 ms (eval), AND ≥ 100× faster than the network-driver path on the same
  canvases.
- **GPU action:** drive `denoise_to_step` with the in-worker `_oracle()` wired
  (image), have the worker time each oracle call and return `oracle_ms` per
  checkpoint in the result; compare against a network-driver run on the same prompt.
- **OFFLINE PREP checklist:**
  - [ ] O1 co-location image built + pushed (bb layer + node-eval layer + the
        minimal `.cljc` + `oracle_shim.py`), per `co-location-image-build §3,6`.
  - [x] O2 `_oracle(kind)` wired into `gpu_worker.py` warm-up (both servers cached,
        eval ready-wait, liveness respawn, V8 warmup) — §3 items 2-3.
  - [x] O5 the worker returns `oracle_ms` (per-checkpoint timing) in the result map.
  - [ ] verify_fresh local sha bumped to the new image tag.

### B. End-to-end refine-loop tok/s — co-located vs network

**Measures:** does the loop go from ~3-4 tok/s to ~50-130 tok/s (forward-bound)?

- **Win condition:** co-located closed loop ≥ 50 tok/s effective (target: approaches
  the ~130 tok/s decode ceiling); network-driver loop reproduces the ~3-4 tok/s
  baseline for contrast.
- **GPU action:** run the FULL refine loop (denoise → parse → renoise → re-parse)
  IN-WORKER over K iterations, report effective tok/s; then run the same via the
  network driver (`closed_loop.py`) for the before-number.
- **OFFLINE PREP checklist:**
  - [ ] O1 image (as A).
  - [x] O3 the in-worker closed-loop driver — the refine loop running INSIDE
        `gpu_worker.py` using the persistent `_oracle()` (NOT `closed_loop.py`'s
        `subprocess.run`). This is the §3-item-1 fix: a `mode:"refine_loop"` that
        does denoise_to_step → local parse → resume_renoise → local re-parse in a
        tight Python loop, returning per-iteration `errors_before/after`, `tok_per_s`.
  - [ ] O4 keep `closed_loop.py` (network) as the BASELINE arm, unchanged, for the
        before/after contrast.
  - [ ] verify_fresh bumped.

### C. KV-cache 62 % prefill win — needs the co-location image (Cache can't ride JSON)

**Measures:** the dominant efficiency lever — reusing the encoder `Cache` for
repeated context sections (soul / skill / required-API blocks) drops prefill, which
was 62 % of generation latency at 9k-token context (`co-location-image-build §TL;DR`,
`kv-section-caching`).

- **Win condition:** (1) **bit-exactness** — request-2 (crop + re-fed
  `past_key_values`, suffix-only forward) `sequences == ` request-1 full re-encode
  for a fixed seed on the REAL hybrid cache; (2) **prefill-time drop** proportional
  to the reused-prefix fraction (target: the 62 % prefill shrinks toward the
  divergent-suffix fraction).
- **GPU action:** the worker's `kv_reuse` path (`_kv_reuse_generate`) — two
  requests, the second reusing the first's cropped cache; assert bit-exact, measure
  prefill delta.
- **OFFLINE PREP checklist:**
  - [ ] O1 image (KV reuse needs the Cache in-process — it cannot ride a JSON
        payload, so the co-location image is the precondition; `gpu-session-run-order
        §"Second-session track"`).
  - [ ] keying half already built (`seon.agent.ctx/block-chain-keys`, 3 tests green).
  - [ ] worker-reuse half already built (`_kv_reuse_generate` + `KVPrefixCache` +
        `longest_prefix_hit`, `py_compile`-clean).
  - [ ] CPU-proxy already green (`test_kv_reuse_cpu_proxy.py` — bit-exact crop+reuse,
        de-risks the general mechanism; `kv-section-caching §6a`). Re-run as the
        offline gate.
  - [ ] off-GPU unit gate: `test_kv_walk.py` (13 green) + `test_inject_apply.py`.
  - [ ] force `cache_implementation="dynamic_full"` in the reuse path (the hybrid →
        uniform-full fix, `kv-section-caching §6 #2`) — confirm in code offline.

### D. entropy_bound / tokens-per-forward sweep — the never-swept raw-speed lever

**Measures:** the cheap raw-speed knob — `entropy_bound` (dflt 0.1) sets how many
tokens commit per forward (higher ⇒ more tok/forward ⇒ fewer forwards ⇒ higher
tok/s), traded against quality (`CLAUDE.md` TUNING KNOBS; `north-star` "4→15
tok/forward").

- **Win condition:** an `entropy_bound × tok_per_s × faithful_rate` curve that finds
  the knee — the highest tok/forward that holds the oracle faithful_rate (no quality
  collapse). A MOVED number per setting, not an anecdote.
- **GPU action:** `generate` (or the refine loop) across an `entropy_bound` grid
  (e.g. 0.1, 0.2, 0.3, 0.5), `trace:"entropy"`, recording `tokens_per_forward`,
  `tok_per_s`, and the local-oracle `faithful_rate` per setting.
- **OFFLINE PREP checklist:**
  - [x] O6 the sweep harness — `battery.py` experiment `9_entropy_sweep` (alias
        `D`): `--param entropy_bound=0.05,0.1,0.2,0.3,0.5` (and optional
        `steps=<max_denoising_steps>`) loops the grid, scores each through the local
        oracle (`_skill_good`), emits a scorecard line per setting
        (`{entropy_bound, max_denoising_steps, tokens_per_forward, tok_per_s,
        faithful_rate, worker_sha}`) + the knee. `battery.py D --dry-run` prints the
        plan; `--selfcheck` green.
  - [ ] the param grid decided offline (start coarse: 0.1/0.2/0.3/0.5).
  - [ ] D does NOT need the co-location image (it's a stock-worker knob) — runnable
        in the same warm session as A/B if the image is already up, or on the stock
        image first as the cheapest probe.

### GPU session checklist (the whole session, top to bottom)

```
cd tmp/flash-diffgemma && set -a; . ./.env; set +a
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:<NEW-oracle-tag>
.venv/bin/flash deploy
export DIFFGEMMA_EP=<ep>
python3 verify_fresh.py                 # MUST print FRESH ✓ (sha == local)
# D first if on stock image (cheapest, no image dep) — exp 9_entropy_sweep:
python3 battery.py D --param entropy_bound=0.05,0.1,0.2,0.3,0.5
# Then the co-location image tests:
python3 <A: oracle-latency drive>       # co-located persistent vs network
python3 <B: refine_loop mode>           # tok/s before(network)/after(co-located)
python3 <C: kv_reuse two-request>       # bit-exact + prefill drop
```

Every line lands in a scorecard with `worker_sha` + `params`. NOTHING is built or
debugged here — all of O1-O6 are done before deploy.

---

## 5. Ordered offline-prep backlog (fan-out-ready)

Do these BEFORE the next GPU session. Each is no-GPU, no-deploy. Ordered by
dependency; O1 gates the image-track tests, O2/O3 are the perf fixes, O5/O6 are
harness.

| # | Task | Files | Done-when (offline proof) | Blocks |
|---|---|---|---|---|
| **O1** | Build + push the **co-location image** (bb parse layer + node eval layer + minimal `.cljc` + `oracle_shim.py`). Stage the 3 files into the build context (§6 step 1), append the §3 Dockerfile layer for bb AND a sibling layer for node + `out/worker-oracle-eval/main.js` + `out/bootstrap/`. | `tmp/flash-diffgemma/{Dockerfile,build-image.sh}`, `bin/oracle-server`, `src/seon/repl/internal.cljc`, `out/worker-oracle-eval/main.js`, `out/bootstrap/` | build-time oracle gate passes (`… \| grep -q '"forms":1'`); `docker run` drives BOTH `bb …oracle-server` (parse) and `node …oracle-eval.js --serve` (eval) inside the image (image-build §4b) | A, B, C |
| **O2 ✅** | Harden `_oracle(kind)` in the worker warm-up: cache BOTH servers; eval **ready-wait** on the `"ready\n"` stderr sentinel (`worker_eval.cljs:381`); `p.poll()` **liveness respawn**; node **V8 warmup eval** at boot. Update `oracle_shim.py` with `ready_after()` + a liveness flag. | `tmp/flash-diffgemma/gpu_worker.py` (`_oracle`), `oracle_shim.py` | DONE — `_oracle(kind)` caches BOTH (`oracle_parse`/`oracle_eval`); eval `ready_after()` blocks on the stderr sentinel; `warmup()` primes the hot path; dead-child lazy respawn in `Oracle._ensure`. `refine_loop_dryrun.py`: ready-wait blocked 191 ms then warm eval 0.06 ms; kill→respawn (new pid); shim self-test still green | A, B |
| **O3 ✅** | **In-worker refine loop** — a `mode:"refine_loop"` in `gpu_worker.py`: denoise_to_step → local `_oracle("parse")` → resume_renoise → local re-parse, tight Python loop, persistent shim (NOT `subprocess.run`). Return per-iteration `errors_before/after`, `tok_per_s`, `oracle_ms`. This is the §3-item-1 fix. | `tmp/flash-diffgemma/gpu_worker.py` | DONE — `mode:"refine_loop"` + `_denoise_canvas` helper, bounded by `max_iters`, short-circuits on a clean checkpoint. `refine_loop_dryrun.py` (mock GPU forward, REAL bb oracle over the persistent pipe): ONE persistent server (same pid) every iteration, **0.045 ms** warm vs **25.9 ms** spawn-per-call (~572×) | B |
| **O4 ✅** | Keep `closed_loop.py` (network driver) as the **baseline arm** for B's before-number — unchanged, just confirm it still runs as the contrast. | `tmp/flash-diffgemma/closed_loop.py` | DONE — runs offline against a mocked endpoint (denoise→parse→renoise→re-parse, real bb oracle), import-safe; docstring marks it "NETWORK BASELINE — the slow spawn-per-call contrast, not the production path (see O3)" | B |
| **O5 ✅** | Worker returns **`oracle_ms`** (per-checkpoint oracle timing) in every refine/denoise result, so A measures the in-worker round-trip directly. | `tmp/flash-diffgemma/gpu_worker.py` | DONE — every `refine_loop` iteration carries `oracle_ms` (per-checkpoint pipe round-trip), plus a top-level `oracle_ms_mean`; dry-run shows warm parse ~0.05–0.18 ms (the first call folds the bb classpath load) | A |
| **O6 ✅** | **entropy_bound sweep** experiment in `battery.py` (grid loop, score each through the local oracle, scorecard line per setting). | `tmp/flash-diffgemma/battery.py` | DONE — `exp9`/alias `D`: sweeps `entropy_bound` (dflt grid 0.05/0.1/0.2/0.3/0.5) × optional `max_denoising_steps`, captures `tok_per_s` + `tokens_per_forward` (list→mean) + `faithful_rate` per point, one scorecard line each, finds the knee. `battery.py D --dry-run` prints the grid plan; `--param entropy_bound=… steps=…` tunable; `--selfcheck` green | D |
| O7 *(later)* | **Async pipeline** the eval/retrieve checkpoint (fire async after step N's forward, consume before step N+1 completes). Design-only until A/B prove the co-located loop is forward-bound. | `tmp/flash-diffgemma/gpu_worker.py` | deferred — a ~1 % refinement; structure the O3 loop fire-and-consume so it's a small later change | — |

Bundle-size reminder: the bb layer is +0.5 % of the image; the node eval layer adds
the node binary (~50 MB) + `main.js` (52 KB) + the `out/bootstrap/` cache — still
trivial against the ~15 GB torch image, and the eval tier is what test B/C's
correctness depends on.

---

## Pointers

- `tmp/flash-diffgemma/oracle_shim.py` — the persistent `Oracle` (spawn-once,
  line-protocol); its `__main__` self-test is the 0.048 ms warm proof (re-run any
  time, no GPU).
- `tmp/flash-diffgemma/closed_loop.py:24-30` — the spawn-per-call anti-pattern to
  replace (20.8 ms/call); `:9-21` — the internet API round-trip the co-location
  removes.
- `bin/oracle-server:44,46-47` — the bb parse server + its `*file*`-relative
  classpath (dictates `/opt/seon/bin` + `/opt/seon/src`).
- `src/seon/worker_eval.cljs:381` — the node eval `serve!` ready sentinel + the
  strictly-sequential chained eval; ns docstring — why cljs.js (faithful CLJS).
- `tmp/flash-diffgemma/gpu_worker.py:31,50` — `_CACHE` + `_load` (where `_oracle()`
  caches beside the model); `:818,:862` — the `build_offset_map` canvas spans the
  parse result maps to renoise positions.
- [[research/colocated-oracle-package-design-2026-06-28]] — the oracle package
  shape, bb-vs-node split, `{op,…}` wire, eval ladder (the architecture this
  performance-tunes).
- [[research/co-location-image-build-2026-06-28]] — the image layer + the §5
  spawn-wiring snippet (the persistent `_oracle()`).
- [[research/kv-section-caching-design-2026-06-28]] §6/§6a — the KV-reuse contract +
  the CPU-proxy bit-exact de-risk (test C).
- [[gpu-session-run-order]] — the battery runner + the verify_fresh discipline
  (where D plugs in, and the second-session image track for C).
- [[owner-gpu-runbook]] — the per-step grounding the GPU session executes against.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>

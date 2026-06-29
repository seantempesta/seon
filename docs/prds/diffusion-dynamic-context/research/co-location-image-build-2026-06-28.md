---
type: research
status: active
tags: [research, agent, web, flow]
---

# Co-location image layer — bundle the Seon oracle onto the GPU worker (2026-06-28)

## TL;DR

- **What this is:** the concrete, owner-buildable image LAYER that bakes the
  co-located parse oracle (`bin/oracle-server` + babashka + the one `.cljc` it
  needs + `oracle_shim.py`) onto the existing custom diffusion worker image
  (`tmp/flash-diffgemma/Dockerfile`). It turns the per-checkpoint parse validate
  from a ~100 ms internet round-trip (worker→pod→worker) into a ~0.05 ms local
  pipe call — and that local-validation capability is what unlocks KV-cache reuse
  + the per-step renoise loop. **Top-priority enabler:** Phase-0 measured prefill
  at **62 % of generation latency at 9k-token context**, so killing the round-trip
  is the dominant efficiency lever, not a nicety.
- **The minimal `src/` subset is exactly ONE file.** `bin/oracle-server`
  (`bin/oracle-server:46`) requires `seon.repl.internal`, whose entire `:require`
  is `clojure.string` + `rewrite-clj.parser` + `rewrite-clj.node`
  (`src/seon/repl/internal.cljc:75-78`) — both **babashka built-ins**. So the
  image copies `src/seon/repl/internal.cljc` and nothing else from `src/`. Proven
  in isolation below.
- **Bundle-size delta ≈ 80 MB** (the bb static linux binary) + **~36 KB** (the one
  `.cljc`) + a few KB of glue — trivial against the ~15 GB torch image, and far
  cheaper than the Node path (no node binary, no compiled bundle, no
  `node_modules`).
- **No docker run here.** This doc is the layer spec + the wiring diff + the
  verification recipe; the OWNER builds/pushes/deploys the image (the GPU is the
  owner's single worker per [[CLAUDE]] "How to work here").
- **The actual Dockerfile-fragment + an apply-the-wiring patch are also written
  into the gitignored worker dir** (`tmp/flash-diffgemma/oracle-layer.Dockerfile`,
  `tmp/flash-diffgemma/gpu_worker.oracle.patch`) — they won't commit; this doc is
  the committed source of truth.

---

## 1. Grounding — the existing pieces (cite-checked)

- **`bin/oracle-server`** (committed `45e801d6`) — the persistent bb parse-line
  server. It puts the repo's `src/` on the classpath **relative to the script
  file, not cwd**: `(cp/add-classpath (str (fs/file (fs/parent (fs/parent
  (fs/absolutize *file*))) "src")))` (`bin/oracle-server:44`). So if the script
  lands at `/opt/seon/bin/oracle-server`, it adds `/opt/seon/src` to the classpath
  — **the image layout is dictated by this line** (see §2). It then `(require
  '[seon.repl.internal :as internal] '[cheshire.core :as json])`
  (`bin/oracle-server:46-47`); `cheshire`, `babashka.classpath`, `babashka.fs` are
  all bb built-ins. Pure fn of stdin — no DB, no pod, no build.
- **`seon.repl.internal`** (`src/seon/repl/internal.cljc:75-78`) — `:require
  [clojure.string] [rewrite-clj.parser] [rewrite-clj.node]`. `rewrite-clj` is a bb
  built-in (the same path `bin/test-parser` proves). The file uses reader
  conditionals (`#?(:clj … :cljs …)`, e.g. `internal.cljc:461`); bb reads the
  `:clj` branch — fine, the parse logic is structural and branch-agnostic. **No
  other `seon.*` require anywhere in the file** (grep over the 677 lines: the only
  `seon.` hits are inside docstrings/prose).
- **`oracle_shim.py`** (`tmp/flash-diffgemma/oracle_shim.py`, gitignored) — the
  Python `Oracle` class the worker spawns: `Oracle(argv)` does
  `subprocess.Popen(argv, stdin=PIPE, stdout=PIPE, text=True, bufsize=1)` and
  `parse()` writes one `{op,code,…}` JSON line + reads one line back. Already
  offline-proven over the real pipe (its `__main__` self-test: cold ~21 ms, warm
  ~0.05–0.12 ms over 500 calls). **Runtime-agnostic by construction** — swap
  bb↔Node = change `argv` only.
- **The base image** (`tmp/flash-diffgemma/Dockerfile`) — `FROM
  runpod/flash:py3.12-latest`, force-reinstalls the pristine cu128 torch triple +
  transformers 5.11.0, with a build-time smoke-test gate. The oracle layer is
  **added on top** of this (after the smoke gate), changing nothing about the
  torch stack.
- **The worker warm-up** (`tmp/flash-diffgemma/gpu_worker.py`) — `_load(tok)`
  (`gpu_worker.py:41`) loads + caches the model in the module-level `_CACHE`
  (`gpu_worker.py:31`), loaded once per warm worker. The eval-renoise modes
  `denoise_to_step` (`gpu_worker.py:476`) and `resume_renoise`
  (`gpu_worker.py:534`) currently decode the partial canvas + `offset_map` and
  **return them to the caller** — the parse/eval happens REMOTELY in the pod, then
  the caller calls back with `renoise_spans`. That is the round-trip the
  co-located oracle collapses.

---

## 2. Image layout (dictated by `bin/oracle-server:44`)

`oracle-server` resolves `src/` as `<grandparent-of-script>/src`. Pick the stable
install root `/opt/seon` and the layout falls out:

```
/opt/seon/
├── bin/oracle-server                 # the bb script (COPY from repo bin/)
├── src/seon/repl/internal.cljc       # the ONE .cljc parse-forms needs
└── oracle_shim.py                    # the Python Oracle (image-resident, stable)
```

With the script at `/opt/seon/bin/oracle-server`, `(fs/parent (fs/parent …))` =
`/opt/seon`, so it adds `/opt/seon/src` to the classpath and finds
`seon/repl/internal.cljc`. **cwd-independent** — the worker can spawn it from any
working directory.

### Minimal-src manifest (copy EXACTLY these, nothing more)

| Path in image | From repo | Size | Why |
|---|---|---|---|
| `/opt/seon/bin/oracle-server` | `bin/oracle-server` | ~3 KB | the line server |
| `/opt/seon/src/seon/repl/internal.cljc` | `src/seon/repl/internal.cljc` | ~36 KB | `parse-forms` (the only transitive `.cljc` dep) |
| `/opt/seon/oracle_shim.py` | `tmp/flash-diffgemma/oracle_shim.py` | ~5 KB | the Python `Oracle` spawn helper |

Everything else `oracle-server` needs (`clojure.string`, `rewrite-clj`,
`cheshire`, `babashka.fs`, `babashka.classpath`) is **inside the bb binary** — no
other repo files, no pods, no `deps.edn`, no `node_modules`.

---

## 3. The Dockerfile layer (append AFTER the smoke-test gate)

Pin babashka to **v1.12.212** (the version verified locally + in the colocation
design). The Linux static binary is self-contained (no JVM, no glibc surprises).

```dockerfile
# ---------------------------------------------------------------------------
# SEON CO-LOCATION ORACLE LAYER (parse tier)
# Bakes the babashka parse-validator beside the model so the diffusion worker
# validates a partial canvas LOCALLY (~0.05 ms pipe) instead of an internet
# round-trip to the pod (~100 ms). parse-forms is pure structural rewrite-clj —
# bb reads CLJS-flavored canvas forms bit-identically to the pod, no shadow
# build, no Node. See docs/prds/diffusion-dynamic-context/research/
# co-location-image-build-2026-06-28.md.
# ---------------------------------------------------------------------------
ARG BB_VERSION=1.12.212
RUN curl -fsSL \
      "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
      -o /tmp/bb.tar.gz \
 && tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb \
 && rm /tmp/bb.tar.gz \
 && bb --version

# The MINIMAL oracle payload — bin/oracle-server + the ONE .cljc parse-forms
# needs + the Python Oracle shim. Layout MUST be <root>/bin + <root>/src because
# oracle-server adds <grandparent-of-script>/src to the classpath (relative to
# *file*, not cwd) — bin/oracle-server:44.
COPY bin/oracle-server                 /opt/seon/bin/oracle-server
COPY src/seon/repl/internal.cljc       /opt/seon/src/seon/repl/internal.cljc
COPY oracle_shim.py                    /opt/seon/oracle_shim.py
RUN chmod +x /opt/seon/bin/oracle-server

# BUILD-TIME ORACLE GATE: a broken bundle fails the BUILD, not the live worker.
# Proves bb + the classpath wiring + parse-forms resolve inside the image.
RUN echo '{"op":"parse","code":"(+ 1 2)"}' | bb /opt/seon/oracle-server \
      | grep -q '"forms":1' \
 && echo "SEON ORACLE OK"
```

Notes:

- **`COPY` build context.** `build-image.sh` builds with context `.`
  (`tmp/flash-diffgemma/`), so the `COPY` sources must resolve from there. The
  three files do NOT all live under `tmp/flash-diffgemma/`, so the owner must
  stage them into the build context first (a one-liner in `build-image.sh`, §6) —
  OR run the build from the repo root with a wider context. Staging into the
  worker dir is the least-surprising option and is what the §6 steps do.
- **`bb /opt/seon/oracle-server`** — note the script gate calls
  `/opt/seon/oracle-server`, but the COPY puts it at `/opt/seon/bin/oracle-server`
  (required by the classpath math). The prompt's smoke string
  `bb /opt/seon/oracle-server` therefore needs the script at `/opt/seon/` while
  the classpath resolution needs it under `bin/`. **Resolved: keep the script at
  `/opt/seon/bin/oracle-server` (classpath-correct) and add a convenience symlink
  `/opt/seon/oracle-server -> bin/oracle-server`** so BOTH paths work and the
  `*file*` resolution still sees `bin/` as the parent (a symlink's `*file*` is the
  link path; `fs/absolutize` does NOT resolve the symlink, so
  `/opt/seon/oracle-server`'s grandparent is `/` — WRONG). **Therefore: do NOT
  symlink; always invoke the real path `bb /opt/seon/bin/oracle-server`.** The
  verification recipe (§4) and the wiring (§5) use the `bin/` path. (Flagged
  because the prompt's smoke line omits `bin/` — use the `bin/` path.)
- The static binary has **no runtime deps** — no `apt-get`, no JDK. One `RUN`
  download, untar, done.

---

## 4. Verification recipe (owner-run; docker OR local, no GPU)

Three escalating checks. The first needs no docker at all.

### 4a. Local, no docker — prove the minimal subset is self-contained

Already executed offline (2026-06-28) and PASSES. Reproduce:

```bash
# Stage the EXACT image payload into an isolated root and run from a FOREIGN cwd
ROOT=$(mktemp -d)
mkdir -p "$ROOT/bin" "$ROOT/src/seon/repl"
cp bin/oracle-server                 "$ROOT/bin/oracle-server"
cp src/seon/repl/internal.cljc       "$ROOT/src/seon/repl/internal.cljc"
cd /tmp     # prove cwd-independence
echo '{"op":"parse","code":"(+ 1 2)"}'            | bb "$ROOT/bin/oracle-server"
# => {"forms":1,"tier":"parse","errors":[],"op":"parse"}
echo '{"op":"parse","id":7,"code":"(def m [[v] ...)"}' | bb "$ROOT/bin/oracle-server"
# => ...errors:[{"error-kind":"unmatched-delimiter","span":[0,16]...}],"op":"parse","id":7
```

Observed live: the `(+ 1 2)` line returns `{"forms":1,…}`, the unmatched-bracket
line returns the `unmatched-delimiter` span, an `eof` case
(`(defn mean [xs] (/ (reduce + xs) (count xs`) returns `forms:0` +
`error-kind:"eof"`. Proves the **single `.cljc` + bin script** is the complete
classpath, from a foreign cwd.

### 4b. Inside the built image (after the owner builds with `--load`)

```bash
# Build locally WITHOUT push (validates both the smoke gate AND the oracle gate)
cd tmp/flash-diffgemma
REGISTRY=local TAG=oracle-test docker buildx build --platform linux/amd64 \
  --load -t local:oracle-test .

# The exact prompt check, inside the image:
docker run --rm --entrypoint bash local:oracle-test -c \
  'echo "{\"op\":\"parse\",\"code\":\"(+ 1 2)\"}" | bb /opt/seon/bin/oracle-server'
# EXPECT: {"forms":1,"tier":"parse","errors":[],"op":"parse"}

# Prove the Python shim drives it end-to-end inside the image:
docker run --rm --entrypoint python3.12 local:oracle-test -c '
import sys; sys.path.insert(0, "/opt/seon")
from oracle_shim import Oracle
o = Oracle(["bb", "/opt/seon/bin/oracle-server"])
print(o.parse("(defn mean [xs] (/ (reduce + xs) (count xs)))"))   # forms:1, errors:[]
print(o.parse("(def m [[v] ...)"))                                # unmatched-delimiter
o.close()'
```

If the build-time oracle gate (`… | grep -q '"forms":1'`) passes, the layer is
sound before this even runs — the gate fails the BUILD on a broken bundle.

### 4c. On the live worker (after deploy) — optional smoke

Add a one-off worker mode or shell into the running container; the
`worker_sha`/`verify_fresh.py` discipline ([[CLAUDE]] "Deployment stability")
already proves WHICH code is live. The oracle layer's identity is the **image
tag** (it's image-resident, not deploy-bundled), so a tag bump = the oracle's
version stamp.

---

## 5. Worker spawn wiring (snippet for the owner to apply — do NOT auto-apply)

Two edits to `tmp/flash-diffgemma/gpu_worker.py`. The full patch is also written
to `tmp/flash-diffgemma/gpu_worker.oracle.patch` (gitignored).

### 5a. Spawn the oracle ONCE at warm-up (cache beside the model)

Add an `_oracle()` helper near `_load` (`gpu_worker.py:41`), caching into the same
`_CACHE` (`gpu_worker.py:31`) so the bb process is spawned once per warm worker
and reused for every checkpoint:

```python
# add near the top imports (gpu_worker.py:1)
import sys
sys.path.insert(0, "/opt/seon")          # image-resident oracle layer
ORACLE_ARGV = ["bb", "/opt/seon/bin/oracle-server"]   # parse tier (swap to node for eval tier)

def _oracle():
    """Spawn (and cache) the co-located bb parse server ONCE per warm worker —
    the same lifecycle as _load's model cache. Reused for every denoise
    checkpoint; ~0.05 ms warm per parse vs ~100 ms internet round-trip.
    Returns None if the oracle layer is absent (graceful: caller falls back to
    the remote-parse round-trip)."""
    if "oracle" not in _CACHE:
        try:
            from oracle_shim import Oracle
            _CACHE["oracle"] = Oracle(ORACLE_ARGV)
        except Exception as e:
            _CACHE["oracle"] = None
            _CACHE["oracle_err"] = f"{type(e).__name__}: {e}"[:200]
    return _CACHE["oracle"]
```

### 5b. Parse LOCALLY in the eval-renoise modes (replace the round-trip)

In `denoise_to_step` (`gpu_worker.py:476`), after `canvas_text, offset_map =
build_offset_map(...)` (`gpu_worker.py:504`), call the local oracle and fold the
parse result into the response — so the caller no longer has to round-trip the
canvas back to the pod just to parse it:

```python
            canvas_text, offset_map = build_offset_map(tkz, canvas_ids)   # :504 (unchanged)

            # --- CO-LOCATED PARSE (was: returned canvas_text for the pod to parse remotely) ---
            oracle = _oracle()
            if oracle is not None:
                pr = oracle.parse(canvas_text)              # ~0.05 ms LOCAL pipe call
                info["oracle"] = "local-bb"
                info["parse"] = pr                          # {forms, tier, errors:[{error-kind,span,source}]}
                # char spans -> canvas token positions, ready for resume_renoise
                info["renoise_positions"] = span_to_positions(pr["errors"], offset_map)
            else:
                info["oracle"] = "absent"                   # graceful: pod parses remotely (old path)
                info["oracle_err"] = _CACHE.get("oracle_err")
```

This is the lever: with `parse` + `renoise_positions` computed ON the worker, the
worker can drive the renoise loop locally (clamp GOOD positions, re-noise the bad
spans — the existing `good_clamp_for_renoise` / `resume_renoise` machinery,
`gpu_worker.py:549`) **without shipping the canvas to the pod and back per step**,
which is what makes KV-cache reuse + the per-step loop viable. `span_to_positions`
+ `build_offset_map` are already imported (`gpu_worker.py:4`).

The shim is `op`-dispatched, so when the faithful CLJS **eval** tier is wanted,
change `ORACLE_ARGV` to the Node bundle (`["node","/opt/seon/oracle.js","--serve"]`)
and call `oracle.call("eval", canvas_text, **{"budget-ms":50})` — **no Python
change** beyond argv (the design's runtime-agnostic property).

### 5c. Lifecycle note

The bb process is a child of the worker container; when the worker scales to zero
it dies with the container. No explicit `close()` needed in the scale-to-zero
path, but adding `oracle and oracle.close()` to any worker-shutdown hook is
harmless hygiene. The cold-start barrier (bb boot + `require` + rewrite-clj load,
~21 ms) is paid on the FIRST `denoise_to_step` of a warm worker, ~0.04 % of the
~66 s model load — noise.

---

## 6. Deploy steps (owner-run; rebuild tag → push → verify-fresh)

The oracle layer is image-resident, so deploying it = **a new image tag** (the
same force-fresh mechanism the CLAUDE.md runbook already uses). Steps:

```bash
cd tmp/flash-diffgemma

# 1. Stage the three oracle files into the docker build context (context = `.`).
#    (build-image.sh builds with context `tmp/flash-diffgemma/`, so the COPY
#     sources must resolve from here.)
cp ../../bin/oracle-server            ./oracle-server          # -> COPY bin/oracle-server  (adjust COPY src, see note)
mkdir -p ./src/seon/repl
cp ../../src/seon/repl/internal.cljc  ./src/seon/repl/internal.cljc
# oracle_shim.py already lives here.

# 2. Append the §3 layer to the Dockerfile (or include oracle-layer.Dockerfile).
#    Adjust the COPY sources to the staged paths:
#      COPY oracle-server              /opt/seon/bin/oracle-server
#      COPY src/seon/repl/internal.cljc /opt/seon/src/seon/repl/internal.cljc
#      COPY oracle_shim.py             /opt/seon/oracle_shim.py

# 3. Rebuild + push under a NEW tag (bump cu128-v1 -> cu128-v2-oracle).
REGISTRY=docker.io/seantempesta TAG=cu128-v2-oracle ./build-image.sh

# 4. Force-fresh deploy (new imageName is STRUCTURAL -> server recycles the warm
#    worker; endpoint id preserved).
export FLASH_GPU_IMAGE=docker.io/seantempesta/diffgemma-worker:cu128-v2-oracle
.venv/bin/flash deploy
python3 verify_fresh.py          # MUST print FRESH ✓ before trusting any result

# 5. Drive the eval-renoise mode and confirm the local oracle fired:
export DIFFGEMMA_EP=<id from deploy>
python -u client.py '{"mode":"denoise_to_step","prompt":"write mean over a vector","denoise_steps":8}'
#   EXPECT in the result: "oracle":"local-bb", "parse":{...}, "renoise_positions":[...]
#   If "oracle":"absent" -> the layer COPY/path is wrong; check oracle_err.
```

**Build-context caveat (flagged):** the three files do not all live under
`tmp/flash-diffgemma/`. Either stage them (step 1, simplest) or build from the
repo root with an explicit `-f`. Staging keeps `build-image.sh` untouched except
the COPY source paths. If staging, add `src/` and `oracle-server` to the worker
dir's `.gitignore` so they aren't accidentally tracked (they're copies of
committed repo files).

### Bundle-size delta

| Item | Size | Note |
|---|---|---|
| `bb` static linux-amd64 binary | **~80 MB** | self-contained (rewrite-clj + cheshire + SCI inside) |
| `src/seon/repl/internal.cljc` | ~36 KB | the only `.cljc` |
| `bin/oracle-server` + `oracle_shim.py` | ~8 KB | glue |
| **Total layer** | **~80 MB** | vs the ~15 GB torch image → **+0.5 %** |

Far cheaper than the Node eval-tier path (node binary ~50 MB + a compiled bundle);
the parse tier needs neither. When the faithful CLJS eval tier is added later, the
Node binary + `out/worker-oracle/main.js` join this layer (design §4) — but the
parse tier alone (today's hot job) is bb-only.

---

## 7. What is NOT done here (honest scope)

- **No docker build/push/deploy run** — owner-owned (GPU is the owner's single
  worker). This doc + the gitignored fragment/patch are the build inputs.
- **No live-worker edit** — §5 is a snippet/patch for the owner to apply, not
  applied to `gpu_worker.py`.
- **Parse tier only.** The faithful CLJS **eval** tier (Node + cljs.js, design §5
  of the package-design doc) is a later layer; the wiring is argv-swap-ready but
  not built.
- **The build-context staging** (§6 step 1) is the one manual wrinkle — the COPY
  sources span the repo (`bin/`, `src/`) and the worker dir (`oracle_shim.py`),
  while the build context is the worker dir. Staging resolves it; flagged so it
  isn't a surprise.

## Pointers

- [[colocated-oracle-package-design-2026-06-28]] — the oracle package shape, the
  bb-vs-Node tier split, the `{op,…}` wire, the eval-tier ladder. THIS doc is its
  "image layer" realization (its §2 "Image layer" next-step, now concrete).
- [[custom-image-and-seon-colocation-2026-06-28]] — why the custom image is kept
  (the co-location latency play) + the base-layer torch finding.
- `bin/oracle-server:44,46-47` — the `*file*`-relative classpath (dictates
  `/opt/seon/bin` + `/opt/seon/src`) + the requires.
- `src/seon/repl/internal.cljc:75-78` — the entire require set (→ the one-file
  manifest).
- `tmp/flash-diffgemma/{Dockerfile,build-image.sh,gpu_worker.py,oracle_shim.py}` —
  the base image + the worker the layer extends (gitignored).

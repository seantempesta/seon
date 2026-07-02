#!/usr/bin/env bash
# Build + push the custom DiffusionGemma Flash worker image (with the co-located
# Seon oracle baked in: bb parse tier + node cljs.js eval tier).
#
# The image bakes a PRISTINE torch+transformers cu128 stack (see Dockerfile) so
# the broken base-image torch 2.9.1 is never used, PLUS the Seon oracle layer
# (babashka + bin/oracle-server + the one .cljc + the node eval bundle + its
# ~15 MB bootstrap cache + oracle_shim.py). The build-time gates in the Dockerfile
# are the real validation: a bad torch triple OR a broken oracle bundle fails the
# build HERE, not the live A100 worker.
#
# REGISTRY DECISION (owner): set REGISTRY to a registry the RunPod workers can
# pull from. RunPod workers CANNOT reach a private corp registry
# (proget.repo.symbotic.corp). Options:
#   - Public Docker Hub:  REGISTRY=docker.io/<your-user>/diffgemma-worker
#   - AWS ECR (private):  REGISTRY=376530424260.dkr.ecr.us-east-2.amazonaws.com/diffgemma-worker
#                         (private ECR needs RunPod registry-auth creds wired in
#                          the endpoint/template — extra step, see docs)
# DO NOT push to proget.repo.symbotic.corp — RunPod cannot pull it.
#
# After a successful push, export the tag before deploy:
#   export FLASH_GPU_IMAGE=$REGISTRY:$TAG
# then `flash deploy` (FLASH_GPU_IMAGE replaces the base for the code @Endpoint,
# and is a STRUCTURAL change so it also recycles stale workers — endpoint id
# preserved). Bump TAG (cu128-v2-oracle, ...) on every rebuild.
set -euo pipefail
cd "$(dirname "$0")"

# The repo root (this dir is tmp/flash-diffgemma under it).
REPO="$(cd ../.. && pwd)"

# ---------------------------------------------------------------------------
# stage_oracle — copy the four committed repo artifacts the oracle layer COPYs
# into the build context (./oracle-build/), so the Dockerfile's COPY sources all
# resolve from context `.`. These are COPIES of committed repo files; ./oracle-build
# is under tmp/ → gitignored, never tracked. (oracle_shim.py already lives here.)
# ---------------------------------------------------------------------------
# The cljs-runtime chunk dir the :node-script bundle SHADOW_IMPORTs at runtime
# (the :simple build is a thin loader, NOT a single self-contained file — it
# loads ~192 .js chunks). main.js hardcodes SHADOW_IMPORT_PATH to a CWD/__dirname-
# relative dev path; stage_oracle copies the dir beside the bundle and rewrites
# that line to `__dirname + '/cljs-runtime'` so the bundle is self-locating in
# the image regardless of cwd.
CLJS_RUNTIME="$REPO/.shadow-cljs/builds/worker-oracle-eval/dev/out/cljs-runtime"

stage_oracle() {
  echo "Staging oracle build artifacts into ./oracle-build ..."
  [ -d "$CLJS_RUNTIME" ] || {
    echo "MISSING $CLJS_RUNTIME — rebuild the eval bundle first:" >&2
    echo "  (cd $REPO && clj -M:cljs compile worker-oracle-eval)" >&2
    exit 1
  }
  rm -rf ./oracle-build
  mkdir -p ./oracle-build/bin ./oracle-build/src/seon/repl ./oracle-build/oracle-eval
  # parse tier
  cp "$REPO/bin/oracle-server"               ./oracle-build/bin/oracle-server
  cp "$REPO/src/seon/repl/internal.cljc"     ./oracle-build/src/seon/repl/internal.cljc
  # eval tier: bundle + its chunk dir + the analysis cache
  cp "$REPO/out/worker-oracle-eval/main.js"  ./oracle-build/oracle-eval/main.js
  cp -R "$CLJS_RUNTIME"                      ./oracle-build/oracle-eval/cljs-runtime
  cp -R "$REPO/out/bootstrap"                ./oracle-build/bootstrap
  # Make the bundle self-locating: SHADOW_IMPORT_PATH = __dirname + '/cljs-runtime'
  # (collapses the original dev-path line + its `__dirname == '.'` fallback).
  perl -0pi -e "s|var SHADOW_IMPORT_PATH = .*?;\nif \(__dirname == '\.'\) \{ SHADOW_IMPORT_PATH = \".*?\"; \}|var SHADOW_IMPORT_PATH = __dirname + '/cljs-runtime';|s" \
      ./oracle-build/oracle-eval/main.js
  grep -q "SHADOW_IMPORT_PATH = __dirname + '/cljs-runtime'" ./oracle-build/oracle-eval/main.js \
      || { echo "patch FAILED: SHADOW_IMPORT_PATH not rewritten (bundle header changed?)" >&2; exit 1; }
  # Sanity: every COPY source must exist (fail loud before a slow buildx).
  for f in ./oracle-build/bin/oracle-server \
           ./oracle-build/src/seon/repl/internal.cljc \
           ./oracle-build/oracle-eval/main.js \
           ./oracle-build/oracle-eval/cljs-runtime/cljs/core.js \
           ./oracle-build/bootstrap/index.transit.json \
           ./oracle_shim.py; do
    [ -e "$f" ] || { echo "MISSING staged oracle artifact: $f" >&2; exit 1; }
  done
  echo "  staged: $(du -sh ./oracle-build | cut -f1) (bb-bin pulled at build time)"
}

# REQUIRED: the owner must set this to a RunPod-reachable registry (see above).
REGISTRY="${REGISTRY:?set REGISTRY to a RunPod-reachable registry, e.g. docker.io/<user>/diffgemma-worker}"
TAG="${TAG:-cu128-v2-oracle}"
IMAGE="$REGISTRY:$TAG"

stage_oracle

echo "Building $IMAGE for linux/amd64 ..."
# RunPod workers are x86_64 → must build linux/amd64 (qemu emulation on Apple
# silicon). --push streams straight to the registry. Drop --push and use --load
# to validate the build-time gates (torch smoke + oracle parse/eval) locally
# WITHOUT a registry:  PUSH=--load ./build-image.sh
PUSH="${PUSH:---push}"
docker buildx build \
  --platform linux/amd64 \
  -t "$IMAGE" \
  "$PUSH" \
  .

echo
echo "Built $IMAGE"
echo "Next:  export FLASH_GPU_IMAGE=$IMAGE  &&  flash deploy  &&  python3 verify_fresh.py"

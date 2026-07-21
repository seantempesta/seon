#!/usr/bin/env bash
# Build + push the custom DiffusionGemma Flash worker image.
#
# The image bakes a PRISTINE torch+transformers cu128 stack (see Dockerfile) so
# the broken base-image torch 2.9.1 is never used. The build-time smoke-test
# gate in the Dockerfile is the real validation: a bad version triple fails the
# build here, not the live A100 worker.
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
# preserved). Bump TAG (cu128-v2, ...) on every rebuild.
set -euo pipefail
cd "$(dirname "$0")"

# REQUIRED: the owner must set this to a RunPod-reachable registry (see above).
REGISTRY="${REGISTRY:?set REGISTRY to a RunPod-reachable registry, e.g. docker.io/<user>/diffgemma-worker}"
TAG="${TAG:-cu128-v1}"
IMAGE="$REGISTRY:$TAG"

echo "Building $IMAGE for linux/amd64 ..."
# RunPod workers are x86_64 → must build linux/amd64 (qemu emulation on Apple
# silicon). --push streams straight to the registry. Drop --push and use --load
# to validate the smoke-test gate locally WITHOUT a registry.
docker buildx build \
  --platform linux/amd64 \
  -t "$IMAGE" \
  --push \
  .

echo
echo "Pushed $IMAGE"
echo "Next:  export FLASH_GPU_IMAGE=$IMAGE  &&  flash deploy"

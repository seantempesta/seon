# Snapshot of the live Flash worker (lives at tmp/flash-diffgemma/, gitignored).
# gpu_worker.py  = the @Endpoint (clean: torch+transformers from the custom
#                  image, NetworkVolume diffgemma-vol on EU-RO-1, HF/pip caches
#                  pointed at /runpod-volume). Runtime torch hacks DELETED.
# Dockerfile     = custom FLASH_GPU_IMAGE base (FROM runpod/flash:py3.12-latest;
#                  pristine cu128 torch 2.9.0 / torchvision 0.24.0 / torchaudio
#                  2.9.0 + transformers 5.11.0; build-time smoke-test gate).
# build-image.sh = buildx linux/amd64 build+push; REGISTRY is the open owner
#                  decision (see script header). Stops at the push.
# client.py      = async /run+poll driver.
# See ../infra-flash-runpod.md. Keys are in tmp/flash-diffgemma/.env (NOT here).

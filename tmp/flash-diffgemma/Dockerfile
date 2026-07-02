# Custom RunPod Flash GPU worker image for DiffusionGemma.
#
# WHY THIS EXISTS: the stock `runpod/flash:py3.12-latest` base ships a broken,
# half-upgraded torch 2.9.1+cu128 whose torch._dynamo/config.py is internally
# inconsistent (`Config() got an unexpected keyword argument 'deprecated'`) and
# ABI-mismatched with the base image's transformers. We replace that torch with
# a PRISTINE, matched cu128 triple at IMAGE-BUILD time so the worker never runs
# pip for the heavy stack and never races a corrupt torch. The build-time
# smoke-test gate below PROVES (verified on linux/amd64) that clean torch 2.9.0
# imports torch._dynamo, transformers 5.11.0, and the model class with no error.
#
# Stay on the flash GPU base so the runpod/flash handler + Python 3.12 layout
# stay intact (FLASH_GPU_IMAGE swaps the base for our *code* @Endpoint; the
# bundled handler still imports our function — see grounding doc §b/§f).
FROM runpod/flash:py3.12-latest

# Known-good matched triple (paper's verified combo + cu128 wheels):
#   torch 2.9.0 / torchvision 0.24.0 / torchaudio 2.9.0  (versions pair 1:1)
#   transformers 5.11.0  (supports DiffusionGemmaForBlockDiffusion + accept_canvas)
# --force-reinstall so the base image's broken torch 2.9.1 is fully overwritten,
# not left half-shadowed on sys.path.
RUN python3.12 -m pip install --no-cache-dir --force-reinstall \
      --index-url https://download.pytorch.org/whl/cu128 \
      torch==2.9.0 torchvision==0.24.0 torchaudio==2.9.0

# transformers + decode/runtime deps. PyPI index (these are not on the torch
# index); --no-deps would strip transformers' own requirements, so let it pull
# them but it must NOT pull a different torch — pin keeps torch pristine.
RUN python3.12 -m pip install --no-cache-dir \
      "transformers==5.11.0" accelerate sentencepiece pillow

# BUILD-TIME SMOKE-TEST GATE: a bad combo fails the BUILD, not the live worker.
# This is the REAL validation — importing the model class forces transformers'
# masking_utils to resolve its torch.nn.attention.flex_attention imports against
# the installed torch. If torch._dynamo is internally inconsistent (the broken
# base-image 2.9.1 failure mode) or transformers references a torch symbol this
# torch lacks, the import raises and the image is never produced.
#
# NOTE: the grounding doc's gate did `from torch.nn.attention.flex_attention
# import setup_compilation_env` — that PUBLIC symbol does NOT exist in torch 2.9.0
# (it's the private `_set_compilation_env`; the old worker shim patched a
# different module, torch._higher_order_ops.utils). Importing it directly is a
# bogus probe; what actually matters is that transformers itself imports clean,
# which this exercises.
RUN python3.12 -c "import torch, torch._dynamo; import transformers; from transformers import DiffusionGemmaForBlockDiffusion; print('SMOKE OK torch', torch.__version__, 'transformers', transformers.__version__)"

# ===========================================================================
# SEON CO-LOCATION ORACLE LAYER — bake the Seon oracle beside the model so the
# diffusion worker validates a partial canvas LOCALLY instead of an internet
# round-trip to the pod. TWO tiers, two persistent line-servers (one shared
# {op,…} JSON-line contract — oracle_shim.py drives EITHER by argv):
#
#   parse tier  — `bb /opt/seon/bin/oracle-server`            (~0.05 ms warm)
#                 pure structural rewrite-clj; per-step "is the canvas well-formed".
#   eval  tier  — `node /opt/seon/oracle-eval.js --serve`     (~2.6 ms warm)
#                 faithful cljs.js self-host; sparse "does it RUN" checkpoint.
#
# Spec + verification recipe: docs/prds/diffusion-dynamic-context/research/
# co-location-image-build-2026-06-28.md + colocation-performance-plan.md §O1.
#
# BUILD-CONTEXT NOTE: this Dockerfile builds with context `.` (tmp/flash-diffgemma).
# The repo artifacts are STAGED into ./oracle-build/ first by build-image.sh
# (stage_oracle), so every COPY source below resolves inside the context.
# ===========================================================================

# --- parse tier: babashka (GraalVM-native AOT, no JVM, self-contained ~80 MB).
# Pin to the version verified locally + in the colocation design. The Linux
# static binary carries rewrite-clj + cheshire + SCI inside — no apt, no JDK.
ARG BB_VERSION=1.12.212
RUN curl -fsSL \
      "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
      -o /tmp/bb.tar.gz \
 && tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb \
 && rm /tmp/bb.tar.gz \
 && bb --version

# Layout MUST be <root>/bin + <root>/src — oracle-server adds <grandparent-of-
# script>/src to the classpath relative to *file*, not cwd (bin/oracle-server:44).
# So the bb script lands at /opt/seon/bin/oracle-server → it finds /opt/seon/src.
# (Do NOT symlink /opt/seon/oracle-server: fs/absolutize won't resolve the link,
#  so its grandparent would be `/` — always invoke the real bin/ path.)
COPY oracle-build/bin/oracle-server               /opt/seon/bin/oracle-server
COPY oracle-build/src/seon/repl/internal.cljc     /opt/seon/src/seon/repl/internal.cljc
COPY oracle_shim.py                               /opt/seon/oracle_shim.py
RUN chmod +x /opt/seon/bin/oracle-server

# --- eval tier: node + the cljs.js self-host bundle + its ~7.5 MB chunk dir +
# the ~15 MB analysis cache. node is already on the flash base (verify in the
# gate). The :node-script bundle is a thin loader that SHADOW_IMPORTs ~192 .js
# chunks from a sibling `cljs-runtime/` dir; build-image.sh stages that dir beside
# main.js and rewrote SHADOW_IMPORT_PATH to `__dirname + '/cljs-runtime'`, so the
# bundle is self-locating. The bootstrap analysis cache resolves from SEON_BOOTSTRAP.
COPY oracle-build/oracle-eval                      /opt/seon/oracle-eval
COPY oracle-build/bootstrap                        /opt/seon/bootstrap
ENV SEON_BOOTSTRAP=/opt/seon/bootstrap

# BUILD-TIME ORACLE GATE — a broken bundle fails the BUILD, not the live worker.
#   parse: drive bb with a known form, assert exactly one form parsed.
#   eval:  drive the node bundle one-shot, assert (+ 1 2) => "3".
# Either failing aborts the image (a grep -q returning non-zero → RUN fails).
RUN echo '{"op":"parse","code":"(+ 1 2)"}' | bb /opt/seon/bin/oracle-server \
      | grep -q '"forms":1' \
 && echo "SEON ORACLE PARSE OK" \
 && echo '(+ 1 2)' | node /opt/seon/oracle-eval/main.js \
      | grep -q '"value":"3"' \
 && echo "SEON ORACLE EVAL OK"

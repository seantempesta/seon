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

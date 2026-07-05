"""FROZEN CUDA artifacts — the RunPod A100 worker, quarantined.

Superseded by seon_diffusion.control (the guided loop) + worker (MLX
local). The WIRE CONTRACT is the living part; this code is kept verbatim
for revive-by-need (a future RunPod/H100 deployment). Not part of the
living import surface; import-guard test only. Pure helpers were
extracted to seon_diffusion.common — the copies in diffgemma_common.py
here are part of the frozen artifact.
"""

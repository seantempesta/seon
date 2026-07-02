import os, sys, json, asyncio
from runpod_flash import Endpoint, GpuType, DataCenter, NetworkVolume, PodTemplate

# Warm-worker model cache: persists across requests within one live worker, so a
# burst of calls loads the 50GB model ONCE. Scales to zero after idle_timeout.
_CACHE = {}

# NetworkVolume: caches the ~50GB DiffusionGemma snapshot + pip wheels so cold
# starts mount it (seconds) instead of re-downloading (minutes). Idempotent by
# name+datacenter; survives `flash undeploy`. Volume AND endpoint MUST share
# EU-RO-1 (a volume is single-DC; the endpoint reading it pins to that DC).
_VOL = NetworkVolume(name="diffgemma-vol", size=200, datacenter=DataCenter.EU_RO_1)

@Endpoint(
    name="diffgemma",
    gpu=GpuType.NVIDIA_A100_80GB_PCIe,   # 80GB → BF16 (~50GB) + long-context KV
    datacenter=DataCenter.EU_RO_1,        # MUST equal the volume's DC
    volume=_VOL,                          # mounted at /runpod-volume
    workers=(0, 1),                       # scale-to-zero; $0 when idle
    idle_timeout=600,                     # stay warm 10 min between bursts
    flashboot=True,
    template=PodTemplate(containerDiskInGb=120),
    # torch is force-stripped from Flash deps and comes ONLY from the custom
    # FLASH_GPU_IMAGE (Dockerfile). transformers is a pure-python wheel and also
    # lives in the image; listing it here is belt-and-suspenders, harmless.
    dependencies=["transformers==5.11.0", "accelerate", "sentencepiece", "pillow"],
    env={
        "HF_TOKEN": os.environ.get("HF_TOKEN", ""),
        "HF_HOME": "/runpod-volume/hf",
        "HF_HUB_CACHE": "/runpod-volume/hf/hub",
        "PIP_CACHE_DIR": "/runpod-volume/pipcache",
    },
    execution_timeout_ms=1_500_000,
)
def diffgemma(**payload):
    import time, traceback
    MID = "google/diffusiongemma-26B-A4B-it"
    tok = os.environ.get("HF_TOKEN")

    # Clean image: torch + transformers come pre-installed and matched from the
    # custom FLASH_GPU_IMAGE. No runtime pip, no torch._dynamo probe, no
    # setup_compilation_env shim — those were symptom-patches for the broken
    # base-image torch 2.9.1 and are gone now that the image ships a pristine
    # torch 2.9.0 + transformers 5.11.0 (cu128) triple.
    import torch
    import transformers
    info = {
        "transformers": transformers.__version__,
        "torch": torch.__version__,
        "cuda": torch.cuda.is_available(),
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "vram_gb": round(torch.cuda.get_device_properties(0).total_memory / 1e9, 1)
                   if torch.cuda.is_available() else None,
    }

    if payload.get("mode") == "probe":
        from transformers import AutoConfig, DiffusionGemmaForBlockDiffusion
        info["class_ok"] = bool(DiffusionGemmaForBlockDiffusion)
        try:
            cfg = AutoConfig.from_pretrained(MID, token=tok)
            info["config_ok"] = True
            info["model_type"] = getattr(cfg, "model_type", None)
        except Exception as e:
            info["config_ok"] = False
            info["config_err"] = f"{type(e).__name__}: {e}"[:200]
        return info

    # --- full generate (text-only) ---
    try:
        from transformers import AutoTokenizer, DiffusionGemmaForBlockDiffusion
        t0 = time.time()
        if "model" not in _CACHE:
            _CACHE["tok"] = AutoTokenizer.from_pretrained(MID, token=tok)
            _CACHE["model"] = DiffusionGemmaForBlockDiffusion.from_pretrained(
                MID, dtype="auto", device_map="auto", token=tok,
                attn_implementation="eager")
        info["load_s"] = round(time.time() - t0, 1)
        tkz, model = _CACHE["tok"], _CACHE["model"]
        msgs = [{"role": "user", "content": payload["prompt"]}]
        inp = tkz.apply_chat_template(
            msgs, tokenize=True, add_generation_prompt=True,
            return_dict=True, return_tensors="pt").to(model.device)
        nprompt = int(inp["input_ids"].shape[-1])
        g0 = time.time()
        out = model.generate(**inp, max_new_tokens=payload.get("max_new_tokens", 256))
        gen_s = time.time() - g0
        ncomp = int(out.shape[-1]) - nprompt
        info.update({
            "text": tkz.decode(out[0][nprompt:], skip_special_tokens=True),
            "prompt_tokens": nprompt, "completion_tokens": ncomp,
            "gen_s": round(gen_s, 2),
            "tok_per_s": round(ncomp / gen_s, 1) if gen_s else None,
        })
    except Exception as e:
        info["gen_error"] = f"{type(e).__name__}: {e}"[:300]
        info["trace"] = traceback.format_exc()[-1200:]
    return info


async def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "probe"
    if mode == "probe":
        r = await diffgemma({"mode": "probe"})
    else:
        prompt = ("Write an idiomatic Clojure function `mean` that returns the "
                  "average of a vector of numbers. Reply with ONLY the code in a "
                  "```clojure block.")
        r = await diffgemma({"mode": "generate", "prompt": prompt, "max_new_tokens": 256})
    print("RESULT:", json.dumps(r, indent=2))

if __name__ == "__main__":
    asyncio.run(main())

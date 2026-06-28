---
type: research
status: active
tags: [research, agent]
---

# Parser as a per-step generation oracle (2026-06-28)

## TL;DR

Seon's real agent-reply parser (`seon.repl.internal/parse-forms`, rewrite-clj)
plus the parinfer repair layer (`seon.repair`) is a viable **per-step commit
oracle** for guided generation — the `accept_canvas`-style decision the diffusion
thesis needs, but usable today against any noisy generator. Measured over 5
diverse real-LLM programs and 124 injected corruptions:

- **92.7% of injected errors detected** by the parser, instantly, no model call.
- Of detected, the parser's `:error-kind` splits them into **SAFE auto-fix (95)**
  vs **FLAG-for-re-noise+lookup (20)**.
- **100% (95/95) of the SAFE class measured to re-parse clean** after
  `seon.repair` — recovery is a guarantee, not a label, so a generation loop
  fixes them in place with **zero model round-trips**.
- The remaining **3.2% are "masked-divergent"** — the corruption still parses but
  *means something else*; only the eval cage can catch these. This is the
  syntactic/semantic boundary the diffusion papers predicted.

A strong-model A/B (gemini-3.5-flash, repl-skill in context vs not) was **null** —
flash writes clean Clojure regardless, 0 errors either way. That is itself the
finding: the mechanism's value is on **noisy** generation (a diffusion model's
per-step commits, a weak model), not capable autoregressive output.

## Why this matters for the diffusion thesis

The buzzsaw needs a cheap, tight feedback collar between denoise steps. This
measures exactly that collar's economics:

- **SAFE kinds (`:eof`/`:unmatched-delimiter`) → mechanical repair, no model
  call.** 95/124 corruptions. The diffusion loop re-balances the canvas in place
  (parinfer) and continues — the expensive generator is never re-invoked.
- **FLAG kinds (`:invalid-token`, and `:odd-map`/`:bad-metadata`) → re-noise +
  retrieval.** 20/124. These are where the model must re-decide, and where the
  embedding/program-graph lookup earns its keep (the wrong-fn-name / wrong-API
  blind spot the Transformer Lab paper measured at AUROC 0.471 — entropy can't
  self-detect them, so an external oracle must).
- **Masked-divergent → eval cage.** 3.2%. Syntax is clean but meaning changed;
  only running it (Seon's SCI eval) catches the divergence. Two-tier detection,
  exactly as predicted: syntactic (parser) → semantic (eval) → factual
  (retrieval).

## Method (fully reproducible)

Generator: `agy` (gemini-3.5-flash) produced 5 diverse programs (nested
`db/transact!`, a `defn` with `let`/`cond`/threading, a `reduce`/`map`/`filter`
pipeline, a `defn` returning a nested data literal, and a deeply-nested employee
transact). Corruptions, one per candidate site:

1. **Closer-drop** — delete each `)`/`]`/`}` (the dominant LLM / diffusion error)
   → exercises the SAFE path.
2. **Number-mangle** — append a letter to each number token (`220000` → `220000z`)
   → exercises the FLAG path (`:invalid-token`).

Each corrupted variant is run through `parse-forms`; `:read` entries are
classified by `:error-kind`; SAFE variants are then run through
`seon.repair/repair-source` (with the real `parse-forms` re-parse injected as the
`reads?` gate) and re-checked for clean parse. Masked variants (no `:read`) are
compared form-by-form (`:form` sexprs) against the clean original to separate
*benign* (same meaning) from *divergent* (silently changed meaning).

Scripts: `scratchpad/abtest/{sim2,emit-safe}.clj` (bb, parse-forms only) +
the pod recovery eval (`seon.repair`). Inner-loop test gate: `bin/test-parser`
(0.3s).

## Results

| metric | value |
|---|---|
| corruptions injected | 124 |
| **detected by parser** | **115 (92.7%)** |
| error-kind mix | `:unmatched-delimiter` 57 · `:eof` 38 · `:invalid-token` 20 |
| → SAFE auto-fixable | 95 |
| → FLAG re-noise+lookup | 20 |
| **SAFE measured to re-parse clean after `seon.repair`** | **95 / 95 = 100%** |
| masked-benign (parses, same meaning) | 5 (4.0%) |
| masked-divergent (parses, MEANING CHANGED) | 4 (3.2%) |

## Honest limits

- Closer-drop + number-mangle are a **subset** of real diffusion noise (token
  substitutions, transpositions, and mid-token corruption aren't modeled here).
  The detection/recovery split should be re-measured against **real DiffusionGemma
  canvases** once the RunPod env is unblocked (still blocked on the custom torch
  image; see [[index]]).
- The **3.2% masked-divergent** rate is a genuine parser blind spot by
  construction — it is the eval tier's job, and quantifying how reliably the SCI
  cage catches it is the next measurement.
- The strong-model A/B null means a **noisier live generator** (DeepSeek-on-acme,
  or the diffusion model) is needed for an end-to-end "skill guides generation"
  number; the corruption sim is a clean proxy, not a substitute.

## Feeds into

- The `repl` skill (`.claude/skills/repl/SKILL.md`) encodes this SAFE-vs-FLAG
  taxonomy as agent-facing guidance: what the REPL auto-fixes vs what you must fix
  / look up.
- The parser changes this rests on shipped to the **general** segmenter
  (`seon.repl.internal`), not a fork: `:span`/`:error-kind`, the `#_`-discard fix,
  and PRONG 1/2 (orphan-drop + shred-collapse).

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
  syntactic/semantic boundary the diffusion papers predicted. **The eval cage
  catches ~91.5% of masked-divergent corruptions** (62.5% as a hard error, no
  reference needed); its ~8.5% residual is dead-data mutation (data off the
  program's live path) — the semantic/factual boundary one tier further out. See
  "Eval-tier catch of masked-divergent".

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
  construction — it is the eval tier's job. **NOW MEASURED:** the SCI cage catches
  **~91.5%** of masked-divergent corruptions (62.5% via a hard error alone, no
  reference needed); the ~8.5% it misses are **dead-data mutations** — corruption
  of data off the program's live path. See "Eval-tier catch of masked-divergent"
  below.
- The strong-model A/B null means a **noisier live generator** (DeepSeek-on-acme,
  or the diffusion model) is needed for an end-to-end "skill guides generation"
  number; the corruption sim is a clean proxy, not a substitute. **DONE for
  DeepSeek-on-acme — also null (guided trended worse); see "Live acme drive"
  below.** The diffusion-model arm remains.

## Live acme drive — DeepSeek A/B, repl-skill in context vs not (2026-06-28)

The corruption sim's strong-model A/B was null because gemini-flash writes clean
Clojure. We re-ran the A/B with a **noisier live generator** — a real DeepSeek
agent on the isolated **acme** cluster (pod 7980, store `data/clusters/acme`),
driven on an identical, deliberately delimiter-stressful, deeply-nested
form-heavy task (nested `db/transact!` of three nested person maps → a `defn`
querying them → a `let` mapping over the result; then two harder rounds: a
single deeply-nested company literal, and a `defn` with nested destructuring +
`reduce` + `->>`). Two fresh dedicated agents, eval rows attributed by
`:seon.eval/agent` and filtered to the post-task window:

- **A — baseline:** repl skill NOT loaded.
- **B — guided:** `(my.skills/load :repl)` first (verified: a `:skill/repl`
  context block on the agent before the task turns).

### Results (real counts, read back from the acme store)

| condition | total evals | clean forms | **read failures** | auto-repaired | runtime (semantic) fails |
|---|---|---|---|---|---|
| A baseline | 117 | 115 | **1** | 0 | 1 |
| B guided | 85 | 73 | **2** | 1 | 8 |

- **Read-failure (parse) rate:** baseline **0.85%** (1/117) vs guided **2.4%**
  (2/85). Parse-level including parinfer auto-repair: baseline 0.85% vs guided
  **3.5%** (3/85).
- **Delta: the skill did NOT reduce broken-form evals — guided trended HIGHER.**
  No reduction was observed at this scale.

### What the failures actually were (the honest read)

Pulling the failing `:seon.eval/source` strings is more informative than the
counts:

- **Read failures in BOTH conditions were stray single delimiters**, not the
  unbalanced-nesting the deep task was meant to provoke: baseline's was a prose
  line beginning with a stray `)`; guided's two were stray **backticks**
  (`` `:seon.db/unbridgeable-attrs` `` / `` (`:my.agent…/…`) ``) →
  `:invalid-token`. The FLAG class, not the SAFE/auto-fix class. Parinfer
  auto-repair fired only **once total** (one guided `db/transact!`), confirming
  DeepSeek almost never emits the missing-closer error the repair collar targets.
- **The dominant failure mode was semantic, not syntactic** — and a chunk of it
  was **prose-wrapped-in-parens**: the model wrote English asides as `(…)` lists
  — `(Engineering, Design, Product)`, `(nested maps and vectors)`,
  `(name, departments, teams, members, skills)`, `(schema registration)` — which
  parse fine, then throw "undefined symbol" at eval. This is *exactly* the mistake
  the repl skill calls out ("reasoning is `;` prose, never a `(` form"), yet the
  guided agent produced MORE of them, not fewer.
- Guided's higher fail count is largely **behavioral divergence**: with the skill
  loaded it went down a schema-registration path (`schema/register!` hitting
  `:seon.db/unbridgeable-attrs`) and an undefined-verb path (`(complete …)`,
  `(message/user …)`) — a different, harder sub-problem, not a parser effect.

### Conclusion + honest limits

The live DeepSeek drive **confirms and extends the gemini-flash null**: even a
noisier capable model writes Clojure clean enough at the *syntactic* level that
the parser-repair collar barely engages (≈1% read-failure rate, ~0–1 auto-repairs
per ~100 evals), so loading the `repl` skill produced **no measurable reduction
in broken-form evals** — guided was if anything slightly worse, dominated by
inter-agent behavioral variance and DeepSeek's *semantic* (not delimiter) error
profile. Limits: N is two single agents per condition, so the comparison is
confounded by autonomous divergence (B ran a schema-heavy path); a rigorous
number needs **many agents per condition** (pass^k) or a genuinely weak/diffusion
generator whose per-step commits actually produce the unbalanced-delimiter noise
the collar is built for. The mechanism's value remains where the sim predicted:
**noisy per-step generation**, not capable autoregressive agents.

Harness note (fixed in passing): the skill seeder
(`my.skills/list-skill-files`) used `readdirSync` Dirent flags, whose
`.isDirectory?` is **false for a symlink** — so `.claude/skills`'s symlinked
`seon-skills/*` skills (incl `repl`) silently never seeded; only the two real
dirs did. Switched to `statSync` (follows links). Without this the guided
condition could not have loaded `:repl` on the acme pod at all.

## Eval-tier catch of masked-divergent (2026-06-28)

The third tier of the detection story — **does running the form in Seon's SCI
cage actually catch a corruption the parser provably can't?** Measured directly:
**~91.5% of masked-divergent corruptions are caught by eval** (natural-weighted),
of which **62.5% throw a hard error with no oracle at all**; **~8.5% are truly
silent** — and the silent misses are a precise, explainable class.

### Why a new corpus + a unified corruption model

The original corpus's 4 masked-divergent variants (the 3.2% above) are **not
measurable as-is**: all four are `:db/id` string mangles (`"order-1"` →
`"order-1z"`) inside a `(db/transact! conn …)` that references undefined `conn`.
Eval errors *identically* on the clean and corrupted form (undefined `conn`
before the mangled datum is ever reached) — the corruption is invisible to eval
not because eval is blind but because the **outcome isn't observable**. The task's
own caveat. So two changes, both toward a cleaner measurement:

1. **Unified corruption model: delete ONE character at each position.** This
   subsumes the earlier closer-drop (delimiter chars) AND token mutation
   (alphanumerics) under one diffusion-faithful noise model — a single wrong/
   dropped character is exactly a diffusion per-step commit error. Masked-
   divergent = the deletions that **parse clean yet whose sexpr differs**.
2. **Self-contained pure-expression corpus** (14 hand-authored forms — `map`/
   `filter`/`reduce`/`group-by`/`get-in`/`merge`/`case` over **inline literal
   data**, no external vars, no side effects). Every original evals to a concrete
   value (verified: 14/14 `:ok`), so a meaning change is **eval-observable** by
   construction. (`agy` was quota-empty; the corpus is the substrate, the
   corruption+eval is the measurement.)

A key structural finding falls out of the unified model: **single delimiter
deletions almost never produce masked-divergent — they unbalance and the parser
catches them.** Over the 14-form corpus, 1598 one-char deletions split into 282
parser-detected (`:read`), 369 no-op (same sexpr), and **928 masked-divergent**.
By deleted-char category the 928 are: `:alpha` 585, `:whitespace` 101, `:digit`
96, `:punct` 83, `:delimiter` 63 — i.e. masked-divergence is overwhelmingly a
**token-level** phenomenon (mutate a symbol/keyword/number that stays parseable),
not a structural one. That is exactly the tier split the boundary predicts:
delimiters → parser, token-substitution-that-parses → eval.

### Method

Each masked-divergent variant's source is evaluated through the real cage —
`(seon.eval/eval @seon.repl/!compile-state src)` on the live `:client` runtime
(the bootstrap-cljs self-host compiler + always-on Malli instrumentation;
`seon.eval/eval` never throws, returns `{:ok true :value v}` | `{:ok false
:error …}`). Every original is evaled once; each divergent is compared to its
original's result. Forms are **pure expressions** so eval mutates no store — safe
on the live runtime. Classification per divergent variant:

- **CAUGHT-BY-ERROR** — divergent `{:ok false}` (analyzer/instrument/runtime
  throw) while original `{:ok true}`. The strong catch: the cage flags it with
  **no reference needed**.
- **DIVERGES-IN-VALUE** — both `{:ok true}` but `(not= div-value orig-value)`.
  The cage ran it clean; catching the divergence needs an external check (a
  comparator / a test / the reference value).
- **MISSED** — both `{:ok true}` and `(= div-value orig-value)`. Truly silent.

Stratified sample of 50 per deleted-char category (250 total) so each category's
rate is well-estimated; headline numbers are re-weighted to the natural 928
distribution. Scripts: `scratchpad/abtest/{gen-divergent3.clj}` (bb, corruption +
classify + sample) + the in-pod async harness reading `sample.edn` and stashing
`__catch_out` to `globalThis`.

### Results

Per deleted-char category (50-each stratified sample):

| deleted char | N (of 928) | **catch** | err-only | diverges-in-value | **missed** |
|---|---|---|---|---|---|
| `:delimiter` | 63 | 100.0% | 100.0% | 0% | 0.0% |
| `:whitespace` | 101 | 100.0% | 60.0% | 40.0% | 0.0% |
| `:punct` | 83 | 94.0% | 90.0% | 4.0% | 6.0% |
| `:alpha` | 585 | 90.0% | 64.0% | 26.0% | 10.0% |
| `:digit` | 96 | 84.0% | 8.0% | 76.0% | 16.0% |

Natural-distribution-weighted over all 928 masked-divergent corruptions:

| metric | value |
|---|---|
| masked-divergent corruptions | 928 (of 1598 one-char deletions) |
| **eval-tier CATCH (error OR value-diff)** | **849 / 928 = 91.5%** |
| → CAUGHT-BY-ERROR (no reference needed) | 580 / 928 = **62.5%** |
| → DIVERGES-IN-VALUE (needs a comparator/test) | 269 / 928 = 29.0% |
| **MISSED (truly silent)** | **79 / 928 = 8.5%** |

### What gets caught vs missed (concrete)

- **CAUGHT-BY-ERROR (delimiter):** dropping the leading `(` of
  `(let [people …] …)` reparses as the symbol `let` followed by a vector
  `[people …]` — a structurally different sexpr that references undefined
  `people` → eval throws *"people is not defined — this form ran NOTHING."* A
  delimiter deletion that *rebalances* (rather than unbalancing) lands in the
  parser's blind spot, and the cage catches it the instant it runs.
- **DIVERGES-IN-VALUE (digit):** `(merge {:a 1 :b 2} {:b 20 :c 30} {:d 40})` with
  a digit dropped → `:c 30`→`:c 3`. Parses clean, evals clean, but returns
  `{:a 1 :b 20 :c 3 :d 40}` ≠ original `{… :c 30 …}` — observable iff you have
  the reference.
- **MISSED (the honest blind spot):** both misses below are **dead-data
  mutations** — the corrupted datum is off the program's live computation path:
  - `:e [10 20 30]` → `:e [10 2 30]`, but the form only reads `(nth (:e m) 2)` =
    `30`. Result `45` either way. The `20`→`2` change is real but **never
    observed**.
  - one order's `:id`→`:d` (`{:id 2 …}`→`{:d 2 …}`), but the computation filters
    `:paid?` and sums `:total` — it **never reads `:id`**. Result `170` either
    way.

  This is the precise characterization of the eval tier's residual blind spot:
  **eval catches divergence only on the live path.** A corruption to data the
  program never observes is invisible to *running* it — it can only be caught by
  a tier that compares against **intent** (a test, a spec, the factual/retrieval
  oracle). This is the semantic→factual boundary, one tier further out.

### Honest limits + the combined two-tier number

- **62.5% is the "free" rate** a per-step diffusion oracle gets with no reference
  (re-eval, flag on thrown error). The 91.5% requires a comparator/test/reference
  value — available when guiding toward a known target, not in pure open
  generation. State both, never just the 91.5%.
- The corpus is **pure self-contained expressions** — chosen so divergence is
  observable, but real agent code is full of `db/`, undefined-until-defined
  vars, and side effects where (as the original corpus showed) eval can error
  *identically* and mask the corruption. The 91.5% is an **upper-ish bound** for
  the favorable, observable case; the live-agent rate is lower and bounded below
  by the 62.5% error-catch.
- **Combined parser + eval:** of all meaning-altering corruptions (parser-detected
  282 + masked-divergent 928 = 1210), the two cheap syntactic+semantic tiers
  together catch **(282 + 849)/1210 = 93.5%**. The residual ~6.5% are the
  dead-data silent class — neither parsing nor running surfaces them; only an
  intent-level (factual/retrieval) oracle can. The three-tier
  syntactic→semantic→factual story closes exactly where the diffusion papers
  predicted: each tier has a provable blind spot the next tier covers, and the
  last residual is genuinely irreducible without a model of intent.

## Feeds into

- The `repl` skill (`.claude/skills/repl/SKILL.md`) encodes this SAFE-vs-FLAG
  taxonomy as agent-facing guidance: what the REPL auto-fixes vs what you must fix
  / look up.
- The parser changes this rests on shipped to the **general** segmenter
  (`seon.repl.internal`), not a fork: `:span`/`:error-kind`, the `#_`-discard fix,
  and PRONG 1/2 (orphan-drop + shred-collapse).

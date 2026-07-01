---
type: research
status: active
tags: [research, agent]
---

# Self-Evolving Memory — RunPod/Deployment Consultation Handoff

**Purpose.** Bring the RunPod/deployment agent up to speed on the self-evolving-memory
initiative and get a grounded recommendation for its **Phase-2 deployment** — Linux +
GPU on RunPod, reusing the diffusion track's existing Flash deploy process. This doc is
self-contained; the transfer prompt at the bottom can be handed over verbatim.

---

## The initiative in five sentences

We want "store + retrieve data" to be Seon's strongest capability, and instead of
hand-designing the memory API (we already hand-built `my.kb/remember` and it works), we
want to **evolve** it: a proposer LLM writes a candidate `(defn store! …)`/`(defn recall …)`
design (persisted as `:seon.fn`/`:seon.schema` datoms), we score it by spawning **two cold
child agents** — a storer and a *different* retriever — that store facts → **restart** →
retrieve novel questions under distractors, an **objective host-side checker selects** while
Gemini-Flash only diagnoses, and we keep designs in a **quality-diversity archive**
(accept-iff-better / revert-on-regression). The **anti-cheat** is that the probe bank +
answer key + checker live in the **wire-server host process**, which the pod (where agents
eval) structurally cannot reach. The **GO/NO-GO bar** is honest: an evolved design must beat
`my.kb/remember` on a held-out battery by more than the noise band, or we report NO-GO. This
is a known 2025-26 subfield (EvolveMem/MemEvolve); our differentiated bits are the homoiconic
datahike substrate (genome = real code datom) and the fresh-child-after-restart fitness.

Full depth: the spec + seven research docs under `docs/prds/agent-fsm/research/`
(see the index at the bottom).

---

## Where it stands (2026-07-01)

- **Spec written, in owner review** — `self-evolving-memory-spike-spec-2026-06-30.md`
  (goal/GO-NO-GO, architecture, fitness harness, anti-cheat, isolation, Milestone-1,
  build order, risks, open questions).
- **Two-phase deployment plan (owner-set):**
  - **Phase 1 — Mac, now:** Milestone-1 on the existing gym + the pod↔wire process
    boundary. **No VM needed** — the anti-cheat property (agent can't read the host-side
    key) is already enforced by the pod↔wire process split. Proves the machinery.
  - **Phase 2 — Linux + GPU (RunPod), after:** the hardened + scaled run — real sealed
    isolation for arbitrary agent-generated code, the full QD archive + battery, the
    GO/NO-GO call. **This is what we need your consult on.**
- **Isolation findings so far:** `mvm` (microVM CLI) is a confirmed NO-GO on the dev Mac
  (macOS 15/M1 needs a libkrun custom-kernel blob; the install-free Apple-`Vz` backend is
  macOS 26+ only). The **real sealed-microVM tier is Linux+KVM / Firecracker** — which is
  why Phase-2 wants a Linux box. On macOS there's also a sealed-vs-`exec` tension (the
  cheat-proof sealed image disables `exec`, which our child REPL needs); **Linux+KVM gives
  sealed AND `exec` at once**, resolving it.
- **Harness:** the pluggable sandbox provider means the loop code doesn't change between
  phases — only *where the cold children execute* changes (gym/process-boundary on Mac →
  Firecracker/Docker provider on the Linux box). inspect-ai (vendored) has a
  `SandboxEnvironment` registry + remote-sandbox support if we want to orchestrate from the
  Mac and execute on RunPod.
- **Three open owner-decisions** (not blockers for the consult): proposer model + budget;
  Milestone-1 battery sizes (starting point N≈15 facts / M≈15 distractors / |Q|≈10 / k≈3);
  gym vs inspect-ai as the Milestone-1 host (recommendation: gym).

---

## Why you (the RunPod/deployment agent)

You already own a working RunPod deployment process (Flash deploy, the `.env`/`RUNPOD_API_KEY`
pattern, scale-to-zero, GPU/A100, vLLM + transformers model-serving from the diffusion track).
Phase-2 wants to reuse all of it. Two concrete levers only you can assess:

1. **Linux+KVM isolation on RunPod.** Phase-2's hardened isolation is Firecracker microVMs
   (vsock-only, sealed, still `exec`-able). **But RunPod instances are typically containers —
   can we run Firecracker / nested KVM *inside* a RunPod pod (privileged `/dev/kvm`, nested
   virt), or is Docker the practical ceiling there?** This decides Phase-2's isolation model.
   (If nested-KVM is blocked, Docker-in-RunPod + the pod↔wire host boundary is the fallback —
   still gives the core anti-cheat property, just less defense-in-depth on code-exec.)

2. **GPU-served local models to cut cost at scale.** The loop runs *many* trials — per
   candidate design: 1 proposer call + 2 cold-child drives + 1 diagnosis + 1 hack-veto. Over
   hundreds of candidates that's a lot of tokens. Can we **serve local/open models on the GPU**
   (reuse the diffusion track's vLLM/transformers infra) for the **high-volume, weak-model
   roles** — the cold children + diagnosis — while keeping a **strong API model only for the
   proposer**? That could drop per-run cost by an order of magnitude.
   - **Bonus synergy to weigh:** the diffusion track's whole thesis is a *cheap, controllable
     Clojure-generation oracle* (parse/eval/retrieval-guided). That is a natural fit for the
     **candidate PROPOSER** in this loop — DiffusionGemma could generate the `store!`/`recall`
     genomes under the same parse/eval control signal. Worth your read on feasibility.

---

## What we need back

A grounded Phase-2 recommendation covering:
1. **Deployment shape:** run the whole Seon stack on RunPod (pod = Node/CLJS + wire-server =
   JVM datahike + the loop — one box, simplest ops) **vs** keep dev on the Mac and use RunPod
   purely as the isolated executor (children run on RunPod, orchestrated from the Mac). Recommend
   one, with the reuse of your existing deploy process.
2. **Isolation model on RunPod:** Firecracker/nested-KVM if possible, else Docker + the
   pod↔wire host boundary — with the real answer on what RunPod actually permits.
3. **GPU inference plan:** which roles run on GPU-served local models vs API; rough throughput
   + $/run for a Phase-2 batch; whether the diffusion oracle can be the proposer.
4. **Deployment gotchas** from your RunPod experience relevant to a long-running eval loop
   (Flash quirks, scale-to-zero vs keep-warm, image build, secrets, persistence of the
   datahike store across restarts).

Fold the recommendation into the spec's §5 (Isolation) + a new deployment section.

---

## Doc index (read order)

- `self-evolving-memory-spike-spec-2026-06-30.md` — **the plan** (read first).
- `self-evolving-memory-survey-2026-06-29.md` — lit survey + 9-pt anti-cheat playbook.
- `evolving-memory-implementations-deep-dive-2026-06-29.md` — vendored repos map.
- `evolution-engines-deep-dive-2026-06-30.md` — fork EvolveMem + grafts.
- `fitness-anticheat-deep-dive-2026-06-30.md` — the pod↔wire protected boundary.
- `memory-op-design-space-2026-06-30.md` — the 7 evolvable axes.
- `inspect-ai-harness-deep-dive-2026-06-30.md` — the sandbox/scorer harness + mvm provider.
- `mvm-live-test-2026-06-30.md` — why mvm is a Mac NO-GO / Linux is the sealed tier.
- Vendored source to reuse: `reference-code/inspect-ai` (sandbox registry), `reference-code/mvm`
  (microVM SDK), `reference-code/SimpleMem` (EvolveMem engine), `reference-code/Voyager`
  (self-verifier), `reference-code/re-bench` (protected scoring), `reference-code/flash`
  (RunPod deploy — yours).

---

## Transfer prompt (hand this to the RunPod agent)

> You've been running the RunPod/Flash deployment + GPU work for the diffusion track. A second
> Seon initiative — **self-evolving memory** — now needs a **Phase-2 deployment on Linux+GPU**,
> and we want to reuse your RunPod deploy process rather than reinvent it. **Read
> `docs/prds/agent-fsm/research/self-evolving-memory-runpod-handoff-2026-07-01.md` first** (it
> has the full context + doc index), then the spec
> `docs/prds/agent-fsm/research/self-evolving-memory-spike-spec-2026-06-30.md`.
>
> The loop: a proposer LLM writes candidate `store!`/`recall` memory designs; each is scored by
> spawning two cold child agents (store → restart → retrieve under distractors); an objective
> host-side checker in the wire-server selects; a QD archive keeps winners; GO = beat the
> hand-built `my.kb/remember`. Phase 1 (Mac, gym, no VM) proves the machinery; **Phase 2 (your
> lane) is the hardened + scaled run.**
>
> **Consult on, and write up as a recommendation (fold into the spec's §5 + a deployment
> section):**
> 1. **Deployment shape** — whole Seon stack on RunPod (pod=Node/CLJS + wire-server=JVM datahike
>    + loop) vs RunPod-as-isolated-executor orchestrated from the Mac. Reuse your Flash process;
>    say what changes for a long-running (not scale-to-zero) eval loop + how the datahike store
>    persists across restarts.
> 2. **Isolation on RunPod** — can we run Firecracker / nested-KVM microVMs *inside* a RunPod pod
>    (privileged `/dev/kvm`, vsock-only sealed VMs that still allow `exec`)? If not, is Docker +
>    the pod↔wire host boundary the ceiling? Give the real answer for RunPod, not the theory.
> 3. **GPU inference at scale** — can we serve local/open models (reuse your vLLM/transformers
>    infra) for the high-volume weak roles (the two cold children + the Flash diagnosis) while
>    keeping a strong API model only for the proposer? Rough throughput + $/Phase-2-batch. And
>    assess whether the **diffusion oracle (DiffusionGemma, parse/eval-guided) could be the
>    candidate proposer** — it already does controlled Clojure generation.
> 4. **Gotchas** from your RunPod experience relevant here (Flash keep-warm vs scale-to-zero,
>    image build, secrets, store persistence).
>
> Write your findings to `docs/prds/agent-fsm/research/self-evolving-memory-runpod-deployment-<date>.md`
> and note what you'd change in the spec. Don't build yet — this is a deployment consult so we
> can commit Phase-2 with your process, not a from-scratch one.

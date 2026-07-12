---
type: research
status: draft
tags: [research, agent]
---

# Minimal-context experiment — A/B/C strip test — 2026-06-28

Three fresh DeepSeek child agents minted by `root` on the live default pod
(7890), each given the IDENTICAL un-coached task and run to completion. Before
driving, each agent's `:seon.agent/ctx` block set was shaped per-agent (no pod
reset, no `src/` edits) to strip context progressively:

- **A — FULL (baseline):** `lqj-2606281709`, seed context untouched.
- **B — LEAN:** `gOn-2606281709`, dropped the always-on-but-unused `:live-tile` block.
- **C — AGGRESSIVE:** `BnP-2606281709`, dropped `:live-tile` AND `:namespaces`
  (the 40% bloat block — the home-ns + `my.kb`/`my.skills`/`todo` manuals).

Task (sent as a `:human`-origin message from `[:seon.user/id "user"]`):
*"Learn two things about your runtime, remember them, and tell me which matters more."*

Method note: agents minted via `seon.agent/start!` are created IDLE and their
wake trigger is NOT armed by `create!` (only the boot path arms). They had to be
armed via `seon.client/rearm-wake-triggers!` before the message would wake them
— record this; it is a real gotcha for any future per-agent drive harness.

## TL;DR

- **B (drop `:live-tile`) is a free win.** B behaved IDENTICALLY to A: planned
  with `seon.agent.todo`, registered/reused `my.kb.runtime` schema, transacted
  facts, queried them back, delivered a correct recommendation, and called
  `seon.agent.lifecycle/complete`. The `:live-tile` block (~638 ctx tokens) was
  never referenced. **Confirmed droppable.**
- **C (also drop `:namespaces`) BROKE.** Without the `my.kb` manual rendered in
  context, C **hallucinated a schema-registration API that does not exist**
  (`(seon.schema/register! {:seon.schema.attr/name … :seon.schema.kind/string})`)
  and looped on it. After 49 evals (26 failures, 53%) it had **stored nothing**
  and **messaged no one**; its run was still churning. A & B finished in 31 / 35
  evals. The `:namespaces` block is **load-bearing as the discovery manual**, not
  decorative.
- **The cost is concentrated and the failure is concentrated in the SAME block.**
  `:namespaces` ≈ 12,931 ctx tokens (the single biggest block) — and it is also
  the one block whose removal stops the agent from functioning. The implication
  for Core's #42 is decisive: **trim how `:namespaces` RENDERS, do not remove it.**

## Token sizes (measured BEFORE driving — the clean baseline)

Measured via `seon.agent.turn/render-prompt` (THE single prompt producer) +
`seon.ai/effective-system-prompt`, sized with `seon.ai.tokens/estimate`
(`chars/4`).

| Agent | ctx tokens | system tokens | **full prompt** | vs A |
|-------|-----------:|--------------:|----------------:|-----:|
| A — FULL | 17,141 | 3,114 | **20,255** | — |
| B — LEAN (−live-tile) | 16,503 | 3,114 | **19,617** | −638 |
| C — AGGRESSIVE (−live-tile −namespaces) | 3,572 | 3,114 | **6,687** | **−13,568** |

Derived per-block cost (pre-drive deltas):

- **`:namespaces` ≈ 12,931 ctx tok** (B − C) — the home-ns + `my.kb` + `my.skills`
  + `todo` manuals rendered in full. ~78% of A's context body; ~64% of A's whole
  prompt.
- **`:live-tile` ≈ 638 ctx tok** (A − B) — fresh-agent size (the prior end-of-run
  drive measured 1.28k once a human view existed).
- **system prompt = 3,114 tok** — always-on hardcoded mechanics (`message/user`,
  comment levels, etc.), rides the system role, not a strippable ctx block.
- **C's residual 3,572 ctx tok** = `:skills-catalog` (~896) + `:skill/repl`
  (~949) + the transcript masthead + the reactive blocks
  (`:warnings`/`:open-todos`/`:inventory`/`:relevant-source`), which render
  near-empty for a fresh agent and grow only on demand.

So **B got 3% leaner; C got 67% leaner** — but C paid for it with total task
failure.

## Did each agent function? (live evidence)

### A — FULL: SUCCESS

Closed `:completed` in 31 evals (8 failed — alias typos + one transient
`message/user`). Transacted into the existing `:my.kb.runtime` schema and
queried it back. Delivered:

> "## Two Runtime Facts — Learned & Stored … ### 1. Node.js Runtime Environment
> The pod runs on **Node.js v24.2.0** with **226 cached modules**. This is a
> single long-running process — not a fresh sandbox per turn. …"

Completion summary: *"Learned two runtime facts, stored them, and recommended
eval performance as the more impactful one."*

### B — LEAN (−live-tile): SUCCESS — indistinguishable from A

Closed `:completed` in 35 evals (15 failed — same `db/` unqualified-alias and
`message/user` tail as A, not context-related). Transacted
`{:my.kb.runtime/slug "memory-usage" …}`, queried it back, delivered:

> "**1. Memory usage** — I use ~128 MB RSS with ~54 MB of V8 heap … healthy for
> a long-running Node service … **2. Module system** — 226 cached CommonJS
> modules …"

Completion summary: *"Learned memory usage (~54 MB V8 heap, healthy) and module
system (226 cached CommonJS modules, 63 builtins), stored both in my.kb.runtime,
told user memory matters more day-to-day."*

**Verdict: dropping `:live-tile` cost nothing.** The block was never referenced;
the task ("tell me which matters more") was answered with a message, exactly as
in A.

### C — AGGRESSIVE (−namespaces): BROKE

Run still `:open` after **49 evals, 26 failed (53%), 0 stored rows, 0 messages
sent.** C lost the `my.kb` manual and could not discover the real
`seon.schema/register!` signature. It invented a map-based API out of whole
cloth and failed on it repeatedly:

```clojure
;; C's eval — a hallucinated registration API that DOES NOT EXIST
(seon.schema/register! {:seon.schema.attr/name :my.kb.runtime/body
                        :seon.schema.attr/doc  "Full body text…"
                        :seon.schema.attr/kind :seon.schema.kind/string …})
;; => :malli.core/invalid-arity
```

(The real call the manual teaches is `(seon.schema/register! :my.kb.runtime/body :string)`.)
C cycled through `:malli.core/invalid-arity`, `:malli.core/invalid-schema`, and
`:malli.core/invalid-input`, then began trying to *read back a loaded result to
"find the register! signature"* — i.e. burning its turn budget trying to
re-derive the exact knowledge the `:namespaces` block hands every other agent
for free.

Notably C still KNEW `seon.agent.todo/add!`/`plan!` and attempted `seon.db/query`
— those verbs survive in the system prompt + skill/repl. What it specifically
lost was **the `my.kb` storage workflow manual** (register → transact → query),
which lives ONLY in the `:namespaces` block. Memory-write is exactly the
capability the namespaces block underwrites.

## Answers to the owner's questions

1. **How lean did B and C get?** B = 19,617 full-prompt tok (−638, ~3% off A).
   C = 6,687 full-prompt tok (−13,568, ~67% off A). Nearly all the savings is the
   one `:namespaces` block (~12.9k ctx tok).
2. **Did B (drop `:live-tile`) work as well as A?** Yes — identical success,
   same plan→store→query→deliver→complete arc. `:live-tile` is confirmed
   droppable from the minimal base (it is a render-on-demand surface; load it via
   the skill only when the task is a "show me / render a tile" job).
3. **Did C (drop `:namespaces`) still function?** No. It lost the `my.kb` manual
   and could not store knowledge — it hallucinated a fake `register!` API and
   looped, storing nothing and never answering. This says the `:namespaces` block
   is **essential as the discovery manual**, but the 12.9k-token cost is the
   *rendering* (every manual in full, every turn), not the necessity → this is a
   **render-trim target (#42), not a removal candidate.**

## Recommended MINIMAL viable context (the keep-set)

**Keep (load-bearing, proven):**

- **system prompt** (3,114 tok) — the hardcoded mechanics; not a ctx block.
- **`:skills-catalog`** (~896 tok) — the on-demand load index; agents read it and
  load exactly the relevant skills.
- **`:skill/repl`** (~949 tok) — comment levels + the `message/user` example; pulls
  weight given the read-error tail.
- **`:namespaces` (KEEP, but TRIM)** — required for the `my.kb` storage workflow
  and home-ns discovery; C proves removal breaks memory. The 12.9k cost must come
  down via *rendering* (see below), not deletion.
- **reactive blocks** `:warnings`/`:open-todos`/`:inventory`/`:relevant-source`
  — near-zero when empty, self-vanishing; keep (they cost nothing until they
  carry data).
- **`:transcript`** — the agent's own history; non-negotiable.

**Drop from the always-on base:**

- **`:live-tile`** (~638–1,300 tok) — never used by a "tell me" task. Surface it
  via the `ui-canvas` skill on demand, not always-on.

**Needs Core render-trim (NOT removal) — `:namespaces` #42:**

- Render the `my.kb` / `my.skills` / `todo` manuals as **lean signatures +
  one-line docstrings**, with the full worked-example body behind an on-demand
  expand (the same load-on-demand pattern that already works for skills). The
  *worked example* of `register!` is the load-bearing part C missed — keep that
  one example, drop the prose around it. Target: bring `:namespaces` from ~12.9k
  toward the ~4k base without losing the register→transact→query example that
  underwrites memory-write.

**Net minimal base (projected after a `:namespaces` trim to ~4k):** system 3.1k +
skills-catalog 0.9k + skill/repl 0.9k + trimmed namespaces ~4k + empty reactive
+ small transcript ≈ **~9k full-prompt tokens** for a fresh agent — vs today's
20.3k — *without* losing the storage capability C lost. The free 3% (`:live-tile`)
is bankable today; the big 64% is unlocked by trimming, not removing,
`:namespaces`.

## Caveats / skeptic's notes

- The `message/user is not defined` blip hit A and B too but is the known
  `init-message-verbs!` runtime-resolution fragility, **orthogonal to context
  leanness** — both recovered and delivered (msgs-out = 2 each). It did not
  confound the A/B/C verdict.
- A & B partly *reused* the `my.kb.runtime` schema that a prior drive
  (`ogS-2606281649`) had already registered. That makes their storage path
  easier than a cold store — but it is the SAME advantage C had access to and
  failed to use, because C couldn't discover the existing schema or the
  `register!` call without the manual. The comparison is fair: identical store,
  identical task, only the context differs.
- Token sizes are pre-drive (clean). Post-drive prompts grow with transcript +
  warnings; the experiment's measurement is the fresh baseline, which is what the
  minimal-base question is about.

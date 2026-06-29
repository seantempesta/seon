---
type: issue
status: active
tags: [issue, orchestrator]
---

# Repo rough edges & onboarding audit (2026-06-28)

Honest read of "how rough is this for someone else who clones it?" — walked the
documented boot path, ran read-only commands, and cross-checked the front-door
docs against the actual scripts. Bottom line: **the README front door is
genuinely good** (accurate Quickstart, real requirements table, working
`bin/seon prep`/`start all`/`status`). The sharp edges are one layer behind it:
the architecture docs a newcomer is told to read next describe the *paused* JVM
track as the present, plus a couple of config contradictions and a stale
CONTRIBUTING. None of the documented commands are outright broken.

## TOP sharpest edges / onboarding gaps (prioritized)

### 1. The "how it works today" docs describe the PAUSED JVM track as the present — HIGH
- **Who it hurts:** new contributor / agent building a mental model.
- `README.md` reading-order (lines 176-184) sends newcomers to
  `docs/seon/architecture/overview.md` ("how the moving parts fit today") and
  `docs/seon/_dashboard.md` ("system map. Start here").
- But `overview.md` opens (titled **"Current State: How Seon Works Today"**)
  with `ensure-instance!`, ctx atoms + `.setDynamic` `*ctx*`, `topology/request!`,
  "remote agent JVM" Nippy TCP hops, clj-kondo code-graph — **all
  `[JVM track — paused]` machinery**. The active CLJS-pod model (render-of-the-DB
  context, wire-server UDS writer, tx-listener loop) is the README's whole pitch
  but is not what this doc leads with. The doc was even *touched today*
  (`refactor(boot)`), so it reads as current while teaching the paused model.
- `_dashboard.md` has the same problem: "Active focus" header is dated
  **2026-05-23**, its milestone table lists **M6/M7/M8 as `not-started`** while
  `README.md` (lines 151-153) lists the same milestones as **`prototyped`**, and
  its "Components (What Exists)" tables describe the JVM core.
- **Fix direction (NOT a quick win — content rework):** make `overview.md` lead
  with the pod/wire-server model and fence the JVM narrative under an explicit
  "[JVM track — paused]" heading; reconcile the dashboard milestone table with
  the README and refresh the 2026-05-23 active-focus block. *This is the #1 fix.*

### 2. `SEON_EMBED` defaults ON in `bin/seon`, README says "off by default" — MED-HIGH, quick win
- **Who it hurts:** new user; anyone reasoning about what's active.
- `bin/seon:130` → `export SEON_EMBED="${SEON_EMBED:-1}"` (**on** unless the user
  sets it empty). `README.md:170` says the embeddings flag is "(off by default)"
  and ".env.example:66" ships it commented out (`# SEON_EMBED=1`), implying off.
- Degrades gracefully (without a Vertex/`GEMINI_API_KEY` the writes are
  "lazy/no-op" per `.env.example:63-69`), so it's not a crash — but it's a
  documented contradiction that will confuse the first person who greps for why
  embedding code is running when the README told them it's off.
- **Fix:** either flip the `bin/seon` default to empty/off to match the README,
  or update README + `.env.example` to say "on by default, no-op without creds."
  One-line change either way.

### 3. README front door never mentions `.env` (the real config surface) — MED, quick win
- **Who it hurts:** new user.
- The Quickstart (lines 101-108) only does `export DEEPSEEK_API_KEY=...`. That
  works because `bin/seon:128` sources `.env` with env vars taking precedence —
  but the actual persisted-config mechanism is a **170-line `.env.example`** that
  the README never names. There is **no `cp .env.example .env` step**, so a user
  who edits `.env.example` directly gets nothing loaded, and a user who wants any
  non-key setting (`SEON_AI_PROVIDER`, `SEON_SOUL`, ports) has no pointer to it.
- **Fix:** add one line to the Quickstart: `cp .env.example .env` and "edit it
  for keys/provider; env vars override."

### 4. `CONTRIBUTING.md` is stale and JVM-flavored — MED, quick win
- **Who it hurts:** new contributor.
- `CONTRIBUTING.md:28` says "tests via `clojure.test`" — but the **active track
  is the CLJS pod**, tests run via `bin/test-cljs` (`cljs.test`, ~160s). Line 30
  says "Run the project's tests before opening a PR" with **no command**.
- No mention of `bin/seon`, the pod, the dev hook, or `CLAUDE.md` (the actual
  contributor orientation). A human contributor following CONTRIBUTING literally
  would run the wrong test path.
- **Fix:** point CONTRIBUTING at `CLAUDE.md` + `bin/test-cljs`; correct the
  test-framework line.

### 5. Always-on Malli instrumentation throws at runtime on a wrong/absent schema — MED (footgun)
- **Who it hurts:** new contributor / agent writing a public fn.
- There is **no "off" mode** (`CLAUDE.md` "Function Instrumentation"): every
  public fn with `:malli/schema` is validated on every call; a wrong schema is a
  *runtime* throw, not a lint warning. A `:malli.core/invalid-output` on an
  async fn returning a Promise is a known sharp corner (`clojurescript` skill).
- Well-documented for agents that read `CLAUDE.md`, invisible to a human who
  starts from README → CONTRIBUTING. **Fix:** one line in CONTRIBUTING linking
  the instrumentation section.

### 6. Shared-tree git trap + pod-wedge are documented only for agents — MED (footgun)
- **Who it hurts:** agent / second concurrent contributor.
- `git add -A` on the shared multi-agent tree and overlapping `cljs.test`
  runs/never-resolving Promises wedging the shared async continuation
  (`restart pod` to recover) are real footguns. They live in `CLAUDE.md` +
  MEMORY but nowhere a solo external contributor would look. Lower likelihood for
  a single human, high impact when hit.

### 7. Minor: JDK "22+" vs resolver targeting 25 — LOW
- `README.md:82` says Java 22+ and "auto-selects a 22+ JDK";
  `bin/_java-home-resolver:20` sets `SEON_JAVA_VERSION="25"` (22 is the asserted
  hard floor). A user with *exactly* JDK 22 should still pass the floor assert,
  but the resolver *prefers* 25 — worth a half-sentence so a 22-only user isn't
  surprised. Not a contradiction, just under-specified.

## Anything actually BROKEN?
- **No.** Every documented command checked works: `bin/seon status` returns the
  live process table, `bin/seon prep`/`start all`/`cmd_prep` exist as real verbs,
  `npm install` deps resolve, `.env` is sourced. The closest thing to a
  "documented-wrong" defect is the `SEON_EMBED` default (#2) — a doc/behavior
  mismatch, not a failure.

## Quick wins (≤30 min each) vs structural
- **Quick wins:** #2 (`SEON_EMBED` default/doc), #3 (`cp .env.example .env`
  line), #4 (CONTRIBUTING test command + `CLAUDE.md` pointer), #7 (JDK note),
  the dashboard milestone-table reconcile in #1.
- **Structural:** #1's `overview.md` rewrite (lead with the pod model, fence the
  paused JVM narrative) — this is the highest-leverage fix because it's the
  canonical "how it works today" doc the README itself points newcomers to.

## #1 recommendation
Fix the **architecture front door behind the README**, not the README. The
Quickstart already gets a user *running*; the failure is the next click. Rewrite
`docs/seon/architecture/overview.md` so its "How Seon Works Today" sections
describe the **CLJS pod + wire-server** reality (context = render of the DB,
loop = fn of the DB, UDS writes, local lazy reads) and move the
`ensure-instance!`/`topology/request!`/agent-JVM material under one clearly
labeled **"[JVM track — paused]"** section. Then bring `_dashboard.md`'s
milestone table and 2026-05-23 active-focus block into line with the README so
the three "start here" docs tell one story.

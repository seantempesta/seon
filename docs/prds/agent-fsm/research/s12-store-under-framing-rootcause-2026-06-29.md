---
type: research
status: active
tags: [research, agent, database, gym]
---

# s12 store-under-framing — root-cause (why agent A doesn't persist) — 2026-06-29

## TL;DR

The store-proactively guidance (`60dfe087`) is **present and salient in agent A's
prompt, and A internalizes it** — A's very FIRST eval mints a 4-step plan that
includes an explicit *"Store findings as `my.kb.*` rows"* todo. So the failure is
**NOT** "framing reads as reply-to-B, store feels redundant," and **NOT** "guidance
absent/buried." The live post-guidance trace shows the real gap:

**Agent A never REACHES the store step.** A's plan correctly gates storing
`:after` the research/trace steps, then A burns all of its turns stuck in a
**research-friction loop** and the run dies before it ever stores: it reads the
**wrong file** (`src/seon/db.clj` — the paused JVM track — instead of `db.cljs` /
the validators in `db/internal.cljs`), uses `(println …)` whose REPL value is `nil`
(stdout isn't echoed in the transcript) and re-tries the same dead read three turns
running, and never falls back to `render-namespace` on `seon.db.internal`. It stored
**zero** findings because the antecedent "trace" todo never completed — not because
it judged storing optional.

A **separate, serious bug** poisoned the drive: when A finally ran
`(seon.agent.ctx/render-namespace {:seon.ns/name :seon.schema})`, the run's schema
registry got **wiped** (`record-eval!` then fails on `[:seon.agent.turn/id …]`
unregistered, `close-turn!` fails, the next gym query throws). The rendered
`seon.schema` signature block literally contains `(seon.schema/clear-all! [])` as a
signature example, and `clear-all!` executed. This must be fixed before ANY clean
s12 re-measure — it caps the run at agent A and prevents a scorecard.

**Hypothesis (evidence-backed):** s12 Gap A is **store-gated-on-research-completion
× research-phase friction**, not a store-motivation gap. The single highest-leverage
isolated next change is to reframe the store guidance from *batch-at-the-end*
("persist a row per claim BEFORE you reply") to **incremental** ("store each claim
the moment you verify it — a grep hit with a `path:line` is already a storable
finding; don't wait until the whole investigation is done"). Prerequisite: fix the
`render seon.schema → clear-all!` registry-wipe.

## How it was measured (the ONE authorized drive)

- SHA `26b219f2` (HEAD; carries the `60dfe087` store-proactively guidance).
- `SEON_GYM_PAID=s12-run8-two-agent-consultation SEON_AI_PROVIDER=deepseek
  node out/test/test.js --test=seon.gym.database-memory-drive-test`
  (the temporary k=2 instrument `test/seon/gym/database_memory_drive_test.cljs`,
  hermetic scratch conn, DeepSeek adapter + judge).
- The run **threw mid-flight** (the `clear-all!` poisoning below), so the scorecard
  EDN did not regenerate — but agent A's full turn-by-turn behavior IS captured in
  `logs/turns/WjI-2606290621/{1..6}/{prompt,response}.{txt,edn}` (gym forces debug
  capture ON). That per-turn trace is the live evidence here.
- Cross-check: the prior pre-guidance k=2 dump
  `tmp/dbmem-s12-run8-two-agent-consultation.edn` (SHA `d2ca231b`) corroborates the
  thrash pattern — A ran **47 evals / 26 evals** across the two runs with 5 / 2 repo
  greps and stored **0** findings (`:a-stored-at-least-two-findings-with-provenance
  rows=[]` ×2).

## Step 1 — the guidance is IN A's prompt (verbatim, `logs/turns/WjI-2606290621/1-JcI-2606290621/prompt.txt`)

```
; - Store what you verify, without being asked: transact the fact,
;   reusing the shared :my.kb/* provenance attrs. Knowledge nobody
;   stored is research the next agent pays for again. A research task
;   is not done until each distinct claim is a durable my.kb.<domain>
;   row with :my.kb/source-* provenance — persist a row per claim
;   BEFORE you reply. Storing is not optional follow-up; it IS the
;   deliverable of research.
```

Present, high in the always-on `STANDING TEACHINGS` block, on A's very first turn.
The `findings` content block (`seon.agent.ctx.findings`) renders `""` for A — A is
the FIRST agent, the store is empty — so the ONLY store cue A sees is this
system-text bullet. It is there and it is salient.

## Step 2 — agent A's actual ordered behavior (verbatim, run 1, all 6 internal turns)

A's task message: *"How does seon validate schemas at transact time? Walk me through
what actually happens when I transact an attribute that was never registered. Store
what you find so the next agent doesn't have to redo this."*

| turn | what A evaluated | outcome |
|---|---|---|
| 1 | `(todo/done! …)` the ✉ message-todo; `(todo/plan! …)` a 4-step plan: **trace → unreg → STORE findings as my.kb.* rows (`:after [trace unreg]`) → reply (`:after [store]`)**; `(seon.agent.search/grep {…"defn transact!"})` → hits `seon.db:249`, **`seon.db.internal`**; `(render-namespace {:seon.ns/name :seon.db})` | A **plans to store** (guidance landed). Render shows seon.db is signatures-only + `(:require [seon.db.internal …])`. |
| 2 | `(let [fs (js/require "fs") src (fs/readFileSync "/Users/sean/src/seon/src/seon/db.clj" "utf8")] (count src))` | reads **`db.clj`** (the PAUSED JVM track), not `db.cljs` / `db/internal.cljs`; gets a char count only. |
| 3 | `(let [… src (readFileSync ".../db.clj") chunk (subs …)] (println chunk) nil)`; `(grep {…"schema.*valid"})` | `(println …)` ⇒ `;=> nil`; stdout is NOT echoed in the transcript. A: *"The println output should appear above."* |
| 4 | `(let [… (readFileSync ".../db.clj") idx (.indexOf src "defn validate-attrs!") …] (println chunk) nil)` | `;=> nil` again. A: *"the println output didn't appear … stdout isn't captured."* |
| 5 | same read but RETURNS `chunk` (no println) | reads `validate-attrs!` from **`db.clj`** — wrong file (CLJS validators are in `db/internal.cljs`; A even said *"I need the CLJS version"*). |
| 6 | `(seon.agent.ctx/render-namespace {:seon.ns/name :seon.schema})` | render returns the `seon.schema` signature block → **registry wiped** (see Step 4); run dies. |

A authored **zero** real `db/transact!` / `schema/register!` forms across all 6
turns (every `transact!`/`register!` string in the logs is inside RENDERED namespace
text, grep results, or the plan title — grep-verified). A's `store` todo
(`oGF-2606290621`, `:after ["trace" "unreg"]`) **never fired** because its antecedent
`trace` todo never completed.

Agent B never ran — the process died inside agent A's run.

## Step 3 — the framing is NOT the blocker (hypothesis falsification)

- **"Framing reads as research-&-report-to-B, store feels redundant with the reply"
  — FALSIFIED.** A's first move plans an explicit store step; A did not skip storing
  as redundant, it never got there.
- **"Guidance absent / buried" — FALSIFIED.** Quoted verbatim from A's turn-1 prompt,
  high in STANDING TEACHINGS.
- **"A terminates believing the task done after researching" — FALSIFIED in spirit.**
  A did not terminate-as-done; it was still mid-research (re-reading source) when the
  run was killed by the registry wipe. (In the pre-guidance d2ca231b dump A *did*
  burn 47/26 evals and stop without storing — same upstream friction, just survived
  longer.)
- **Model-ceiling — PARTIALLY supported.** The specific frictions (reading `.clj`
  not `.cljs`, trusting `println`'s `nil`, not pivoting to `render-namespace`) are
  weak-model behaviors. But they're addressable by context, so this isn't a pure
  ceiling yet.

## Step 4 — SEPARATE BUG (blocks any clean re-measure): `render seon.schema` wipes the registry

`(seon.agent.ctx/render-namespace {:seon.ns/name :seon.schema})` renders the
signatures, one of which is the example form `(seon.schema/clear-all! [])`
(`; Clear all registered schemas. USE WITH CAUTION — only for testing.`). Immediately
after, the log fills with:

```
[seon.eval/record-eval!] tx FAILED: Unregistered attributes in transaction:
  [:seon.agent.turn/id :seon.agent.turn/evals] … — source: (seon.schema/clear-all! [])
… (seon.schema/current-keys []) … (seon.schema/discard-registrations! [ks]) …
seon.agent.turn/close-turn!: turn close-tx FAILED … :seon.db/unregistered-attrs
  [:seon.agent.turn/id :seon.agent.turn/status :seon.agent.turn/llm-usage …]
```

i.e. the registry got cleared mid-turn (the `record-eval!` "sources" are the
`seon.schema` signature examples themselves), so every subsequent persist — eval
records, the turn-close tx, the next gym predicate query — fails on now-unregistered
core attrs. Two candidate mechanisms (both Core's): (a) the segmenter extracted the
signature-example forms `(seon.schema/clear-all! [])` from the rendered string and
executed them; or (b) the agent re-emitted/ran them. Either way: **inspecting
`seon.schema` can destroy the running schema registry.** This is independent of
db-memory and should be fixed regardless; it also means **my one authorized drive
could not produce a scorecard** — the s12 re-measure must wait on this fix.

## Hypothesis (grounded)

s12 Gap A = **store-gated-on-research-completion × research-phase friction**, not a
store-motivation gap. The store guidance succeeded at the planning layer (A planned
to store) but the deliverable never lands because (1) A's own plan defers storing to
a terminal step `:after` the trace, and (2) the trace never completes — A loops on
wrong-file reads and `println`-nil confusion. Storing is **never reached**, not
judged optional.

## Ranked next-isolated-change proposals (do NOT implement — measure each alone)

**#0 (prerequisite, Core): fix the `render seon.schema → clear-all!` registry wipe.**
Not a "close s12" change, but a hard blocker: no clean s12 scorecard can be produced
until inspecting `seon.schema` stops wiping the registry. Measure: render
`seon.schema` in a gym/scratch run and assert `(seon.schema/registered? …)` for a
core attr survives.

**#1 (TOP, Core — single context edit): reframe store guidance batch → INCREMENTAL.**
Change the always-on bullet from *"persist a row per claim BEFORE you reply"*
(end-gated, which A modeled as a terminal `:after` step) to *"store each claim the
moment you verify it — a grep hit with a `file:line` is already a storable finding;
don't wait until the whole investigation is finished. Storing as you go beats one
batch at the end (which you may never reach)."* Targets the actual failure (store
never reached behind stuck research) and is a GENERAL research-discipline improvement,
not s12-shaped.
- *Confound/overfit risk:* LOW–MEDIUM. General "store-as-you-go" is good practice in
  every db-memory scenario; not answer-shaped. Risk: nudging more frequent stores
  could lift eval-error-rate slightly — watch that axis.
- *Measure:* after #0, re-drive `s12` ALONE at k=2 (DeepSeek) — pass iff A stores ≥2
  provenance rows; THEN the full FREE+paid battery — keep iff no other competency
  drops (`finding-storage-shape` green, `s32` 2/2).

**#2 (Core/UI — broader, do NOT bundle): reduce research friction.** Point agents at
`render-namespace`/`seon.db.internal` for source (not raw `js/require "fs"` on
`.clj`), and/or make `read-file` resolve `.cljs` over the paused `.clj` sibling,
and/or surface that `println`'s value is `nil` (read the returned value, not stdout).
- *Risk:* HIGH confound — multiple changes, touches tooling, not isolatable. Defer;
  it's the deeper root but not a single measurable lever. Capture the `.clj`-vs-`.cljs`
  read-target confusion as its own task.

**#3 (accept, if #1 doesn't close it): track s12 as a known-hard `pass^k` bar.** If,
after #0+#1, a clean k=2 still fails because DeepSeek can't navigate the source under
this framing, the residue is a model ceiling — record s12 as a pass^k watch-bar
rather than chase it with ever-more guidance (which risks overfit). Re-test on a
stronger model before concluding ceiling.

## Live proofs (this is the evidence, not inference)

- A's plan-with-store-step + the 6-turn read-loop: `logs/turns/WjI-2606290621/*/response.txt`.
- Guidance present in A's prompt: `logs/turns/WjI-2606290621/1-JcI-2606290621/prompt.txt` (lines ~150–157).
- Registry wipe: `tmp/s12-rootcause-drive.log` (`record-eval!` failures + `close-turn!` fail after the `seon.schema` render).
- Pre-guidance corroboration (A: 47/26 evals, 0 stored): `tmp/dbmem-s12-run8-two-agent-consultation.edn` (SHA `d2ca231b`).

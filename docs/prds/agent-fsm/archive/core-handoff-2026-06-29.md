---
type: archive
status: archived
tags: [archive, agent, flow]
---

> Superseded — a dated status queue; the current we-are-here + open decisions live
> in [[../roadmap]] (open owner decisions incl. #66; escape-clipping #43 shipped).
> Kept as history.

# Core handoff — context/render queue (2026-06-29)

Owner-reviewed, live-verified queue for the **Core** lane on `feature/agent-fsm`.
Every item is verified against the running pod (7890) as of 2026-06-29 — see the
verdict table + evidence index below. Lane rule: Core owns the engine
(`seon.agent.ctx`, `seon.render` engine, `seon.config`, `seon.eval`, the parser,
the `my.*` schemas, `seon.repl`); the UI lane measures (gym + live drives) and
will re-measure each item after it lands.

## Verified queue status (live-checked — trustworthy)

Evidence: [[research/core-queue-verification-2026-06-29]] (commit `eb955006`).

| Ask | Verdict | One-line |
|---|---|---|
| #40 turn at/status | ✅ **CLOSE — not real** | `seon.db` validates per-attribute, never entity-level; the "required" prop is never enforced (`close-turn!` omits `at` and succeeds). |
| **Rendering / #74 / #42** | 🔴 REAL — signature-trim active | `seon.agent` + long-tail `my.*` render `:signature` (inert comments); owner-rejected. |
| #73 alias collision | 🔴 REAL (softened by an error-hint) | `db/`/`todo/` undefined in agent-authored `my.*` nses. |
| #56 toolkit unqualified | 🔴 REAL | `my.ui`/`my.data`/`my.tile` need full qualification. |
| #83 writes-tests | 🔴 REAL/OPEN | no test-writing cue in always-on; `namespaces.cljs:89` calls deftests "noise". |
| #71 cljs skill gotcha | 🟡 PENDING (doc) | `(fn [])` vs `constantly` arity-0. |
| #43 / #45 / #66 / #81 / #88 | 🟡 OWNER-DECISION pending | do NOT implement without the owner (see § B). |

---

## A. Approved work — implement

### A1 — [P0] Rendering policy: kill signatures → full-source + curation

**Owner decision (UPDATED 2026-06-29):** signatures are acceptable **IF rendered as
comments** — Core is already trying this; this is now an **EXPERIMENT to measure**, NOT
a kill. **U will measure** whether signature-as-comment hurts toolkit adoption / agent
comprehension vs full source (drive + gym scorecard: toolkit-calls, eval-error, judge).
**Full-source + curation (below) is the FALLBACK** if signatures lose the measurement.
The render-prominence law still holds for COMPOSITION verbs (`my.*` toolkit) — keep those
FULL regardless; the signature experiment is for the long tail (`seon.agent`, simple-call
verbs). Curation (which nses render) remains the real token lever either way.

**Why (evidence):**
- The `:signature` form is a wall of commented-out fn headers — name + destructured
  arglist + malli spec + a one-line doc, **no body**. Concrete diff (live, `todo/add!`):
  - `:signature`: `; [fn seon.agent.todo/add!]  (… [{::keys [title description owner from parent depends-on]}])  :spec […]` + a single `;` doc line.
  - `:full`: the real `(defn ^:async add! "<full docstring>" {:malli/schema …} […] (let […] (cond …)))`.
- It reads as "not real code," drops docstring nuance (which keys optional, what the
  lookup-refs do), and **removes the worked example** — which DROVE `my.data` adoption
  to **0×** last night (the render-prominence law). See [[research/namespaces-trim-validation-2026-06-28]]
  + the law in [[CLAUDE]] ("render-prominence").
- Token reality: trimming `todo` to signatures saves ~4.6k (4,077 vs 8,719 tok) — not
  worth the confusion. Curation (rendering fewer nses full) is the real lever.

**The model (owner's words):** *the agent changes to a namespace and it renders that
current ns in full + the other required non-third-party namespaces too.* So:

- **ALWAYS full:** (a) the agent's **current ns** (`cur-ns`), (b) the **`my.*` toolkit**
  (`canonical-full-my-ns` — the worked examples), (c) **other required non-third-party
  nses** — the nses the current work depends on (resolve via the ns's `:seon.ns/requires`
  edges and/or an explicit always-list, see A3).
- **NOT rendered up front:** the long tail. The agent brings one into view by
  **navigating to it** — `(seon.agent.ctx/render-namespace {:seon.ns/name … :seon.render/detail :full})`
  already exists; make "change to a namespace" the supported, discoverable verb.
- **Third-party nses:** keep whatever they render today (they currently fall through to
  `:full`); they're external, out of the curation set.

**Change (Core) — experiment-aware (do NOT abort the experiment):**
1. **KEEP the signature-as-comment path** — Core is testing it; do NOT remove the
   `:signature` branch (`body-detail` L240-263) or `verb-signature-whitelist` (L145) yet.
   **Exception:** the `my.*` COMPOSITION toolkit stays `:full` regardless (render-prominence
   law — signature-trimming it drove `my.data` adoption to 0×). **U measures** signature-as-
   comment vs full (toolkit-adoption, eval-error, judge). **Only if signatures measurably
   hurt** → THEN retire the `:signature` branch + whitelist and fall back to full-only.
2. **The token lever is CURATION either way** (signatures or full): replace the hardcoded
   selection (`canonical-full-my-ns` / `full-source-whitelist` / `verb-signature-whitelist`,
   L121/145/168) with `cur-ns ∪ toolkit ∪ required-non-third-party ∪ <config always-list>` —
   render *fewer* nses, not (only) compress bodies. Wire the always-list to `seon.config`
   (this IS the A3 / #42 work — do them together).
3. Keep `render-namespace … :detail :full` as the navigation verb; surface it to the agent
   (the "change to a namespace" affordance).

**Acceptance:** current-ns + toolkit + required nses render (toolkit always full); a
non-required ns is absent until navigated to; namespaces block tokens drop via *fewer nses*.
**U re-measures**: (a) the signature-as-comment-vs-full experiment, (b) namespaces block-tokens
under lean-vs-full curation configs, (c) a fn-authoring drive (does the agent still reach the
right verbs?).

**Reframes #74:** `todo` is NOT a signature-trim now — it's just one more ns governed by the
curation set. The signature *form* is the measured experiment, not a decided kill.

### A2 — Agent authoring ergonomics (bundle with A1 — same "navigate + author" UX)

These make full-source-with-navigation actually *usable*: the agent reads full source,
then writes code against it.

- **#73 — aliases must be REAL in the ns form, not magic.** REAL (live: `db/query` in
  `(ns my.foo)` → "not defined"). The home ns gets `[seon.db :as db]`/`[message]`/`[todo]`
  via `home-ns-require-specs` (`src/seon/eval.cljs:1204-1221`), but a NEW agent-authored ns
  gets none. **OWNER CONSTRAINT (2026-06-29): NO magic always-present aliases.** Do NOT make
  `db` invisibly resolvable everywhere. Instead — consistent with the navigation model (A1)
  and "code-as-data, the runtime IS the database" — **when the agent switches to / authors a
  namespace, MERGE the standard `:require` aliases the code uses INTO that ns's real
  `(ns … (:require …))` form — additively, never clobbering the agent's own requires** (add
  `[seon.db :as db]`, `[seon.agent.todo :as todo]`, … only for aliases actually used and not
  already present) so the source is genuinely real,
  correct, persisted, and inspectable — the alias resolves because it's *actually required in
  that ns*, not because of an invisible injection. This is the same "no-magic home-ns require"
  direction as commit `98aff9ab`. Keep the existing error-hint ("Did you mean db/query?")
  as the nudge, but the root fix is the real-require rewrite on ns-switch. Evidence:
  [[research/core-queue-verification-2026-06-29]] (#73 repro), [[CLAUDE]] (the alias law).
- **#56 / NAMING POLICY — OWNER DECISION (2026-06-29): full paths in EXAMPLES, real-required
  short aliases in CODE.** One rule, two contexts, no magic — everything real and inspectable:
  1. **REPL examples + ALL rendered context** (toolkit worked examples, `my.kb` manual, skills,
     rendered docstrings, any code we show the agent): **ALWAYS full namespace paths** —
     `seon.db/transact!`, `my.ui/status-line`, `seon.agent.todo/add!`. Never a bare
     `(status-line …)` or an unexplained alias in an example; the full path teaches which ns it
     lives in.
  2. **Authored namespace code**: when a fn uses another ns, that ns is **properly `:require`d in
     the real `(ns …)` form**, and a **short `:as` alias is available** — so in-code `db/transact!`
     / `ui/status-line` work because `[seon.db :as db]` / `[my.ui :as ui]` are genuinely IN the
     form (the #73 ns-rewrite provides them). The abbreviations are real, not magic.
  So **#56 as filed (magic unqualified `:refer`) is REJECTED.** Instead the standard short-alias
  set (`db`, `message`, `todo`, `ui`, `data`, `tile`, …) is made available as **REAL requires**
  when the agent authors/navigates a ns (rides #73). **Core actions:** (a) make every rendered
  example use the full namespace path; (b) provide the standard `:as` alias set as real requires
  in the #73 ns-rewrite so short forms work in authored code without magic. **Open micro-question
  (flagged, separate):** the lifecycle verbs `wait`/`complete`/… are currently `:refer`d
  unqualified — keep them, or move to a required alias (`lifecycle/wait`) for uniformity? Default
  = leave as-is unless the owner wants it uniform.

**Acceptance (A2):** a drive that authors a `my.*` ns can use `db/`/`todo/` aliases AND
unqualified `my.ui/*` without "not defined". **U re-measures** the fn-authoring drive
eval-error-rate (was ~60 errors/drive from this class).

### A3 — [P0] Explicit-listing config (#42)

REAL — both halves unbuilt. No `:seon.config/namespaces` section exists; ns selection is
hardcoded (`namespaces.cljs:143/165/197/240`). Skills still use `:include`/`:exclude` +
`#profile {:default :minimal}` — **the pattern the owner rejected**; there's no `:load` key.

**Owner's spec (no hardcoded profile sets):**
- **`:namespaces`** — an explicit always-render list (in addition to current-ns + toolkit,
  which are always full per A1). This IS the curation knob A1 needs.
- **Current-ns setting** — a toggle/setting for auto-rendering the agent's current ns in
  full (default on).
- **Skills** — explicit listing as an override, OR load-all by default; retire the
  `#profile :default/:minimal` sets. (Note the standing law: agents rarely load skills, so
  the always-on base is what matters — keep that in mind when designing the skills knob.)

**Acceptance:** a config manifest can name the always-render nses + the current-ns setting
+ the skills load policy, through the real `seon.config` seam (runtime EDN, no rebuild).
A1's selection logic reads from it. **U re-measures** namespaces block-tokens under two
configs (lean vs full) to confirm the curation lever works.

### A4 — Writes-tests always-on cue (#83)

REAL/OPEN. Root cause (measured): `plan-resume-across-restart` 0/3 + `todo-multistep-tracking`
0/3 both fail `:wrote-a-test-for-the-fn` — the weak agent designs a schema + writes the fn
but **never writes a `deftest`**. Test-writing guidance exists ONLY in the
`data-oriented-clojure` skill body (which agents rarely load); the **always-on context has
none**, and `src/seon/agent/ctx/namespaces.cljs:89` actively elides deftests from rendered
ns source as "noise" — so the agent gets neither the instruction nor a worked example.
Evidence: [[research/full-battery-triage-2026-06-29]], coordination.md routing note.

**Fix (Core, always-on base / `my.kb`):**
1. Hoist a concise cue into the always-on context: *"every fn you define needs a `deftest`;
   a task isn't done until its fn is tested."* (Same one-sentence-always-on pattern that
   took canvas-drive 1/3→3/3.)
2. Reconsider the blanket `:89` deftest elision — keep **one visible `deftest` worked
   example** in rendered ns source (render-prominence: the example IS the teaching), the
   way the toolkit keeps one worked example.

**Acceptance / U-measures:** re-drive `plan-resume-across-restart` + `todo-multistep-tracking`
at k=3 — keep IFF `:wrote-a-test` passes + the scenarios lift + no battery regression. If the
guidance lands but the weak model STILL won't write tests → it's a ceiling (fold into the §B
weak-model-tier decision), NOT more guidance.

### A5 — clojurescript skill gotcha (#71) [doc, low]

Capture the `(fn [])` vs `constantly` arity-0 dispatch gotcha in the clojurescript skill.
PENDING. Small doc edit.

### Close — #40 (verified not-real)

Mark closed. `turn.cljs:92-93` marks `at`/`status` required in the `:map`, but `seon.db`
validates per-attribute only (`db/internal.cljs:761-822`) — never the whole entity — so the
property is never enforced. `close-turn!` omits `at` every turn and succeeds. (Enforcing
entity-required keys would be a NEW `seon.db` feature, out of scope.)

---

## B. Owner-decisions — do NOT implement without the owner

| # | Decision | State / evidence |
|---|---|---|
| **#88** | The `:keeps-the-repl-clean` / prose-eval fix is **eval-time**: `prose-token?` (`src/seon/repl/internal.cljc:175-188`) treats any `(…)` list as runnable, so **English parentheticals in agent prose get evaluated** (`(results Abk and fvV both return correct data)` → "not defined") and inflate eval-error battery-wide AND error live agents. Proposed fix: demote a `(…)`-list with an undefined head + all-bare-undefined tokens to prose (a real broken call keeps a namespaced/core head, still counts). **Tradeoff:** could mask a genuine typo'd call → owner-gated. **Do NOT relax the 0.2 cap.** | Core flagged eval-time + owner-knob, not shipped. Evidence: [[research/repl-clean-calibration-2026-06-29]]. |
| **#81** | **s12 residual store gap.** After all db-memory fixes, the agent searches but never persists (0/2, robust). Pick: (1) weak-model ceiling — confirm with a stronger model, track as known-hard pass^k; (2) present-vs-persist tension — canvas-first competing with store-to-DB; (3) over-scoped scenario — split store/consult/handoff. | [[research/s12-store-under-framing-rootcause-2026-06-29]], [[research/database-memory-drive-2026-06-28]]. |
| **#66** | `:kind` Category B — purge the WORD from value-classification (`:seon.error/kind`, `:seon.warn/kind` → `class`/`shape`, a real multi-file refactor) vs stop at entity-kinds (done). | Taste/consistency, not correctness. |
| **#43** | Context blocks must ESCAPE value-clipping — render full? | Pending. |
| **#45** | DISABLE the inventory-block now (the #38 rework is designed-not-built)? | Inventory-block is live in root's ctx right now. |

> **Cross-cutting theme for the owner:** #81 and #83's fallback both point at **weak-model
> behavior ceilings** (won't persist / won't write tests even when told). The cheap way to
> resolve that class is to confirm a couple with a **stronger model** — if it passes, they're
> tier-decisions (track via pass^k), not context bugs to keep tuning.

---

## C. Suggested order

1. **A1 + A3 together** (rendering policy + the config that drives curation) — the P0 unlock.
2. **A2** (#73 + #56) — bundle so full-source-with-navigation is usable.
3. **A4** (#83 writes-tests cue) — then U re-drives plan-resume + todo.
4. **A5** (#71 doc), **close #40**.
5. Surface **§B** to the owner (esp. #88 + the stronger-model confirmation for #81/#83).

After each lands: `bin/seon cluster reset default` (shared pod), then U re-measures with
`bin/gym-scorecard --paid` per the acceptance notes.

---

## Evidence index

- **Verification (this queue):** [[research/core-queue-verification-2026-06-29]]
- **Full-battery baseline + triage:** [[research/full-battery-triage-2026-06-29]]
- **s12 root-cause:** [[research/s12-store-under-framing-rootcause-2026-06-29]] · **db-memory drive:** [[research/database-memory-drive-2026-06-28]]
- **repl-clean / prose-eval (#88):** [[research/repl-clean-calibration-2026-06-29]]
- **Night running report:** [[research/overnight-2026-06-28]]
- **Live channel + routing notes:** [[coordination]] (findings-content, Gap A, grep `.clj`, repl-clean, writes-tests entries)
- **Laws + lane charter:** [[CLAUDE]]
- **Key source:** `src/seon/agent/ctx/namespaces.cljs` (`body-detail` L240, whitelists L121/145/168, deftest-noise L89) · `src/seon/eval.cljs` (`home-ns-require-specs` L1204, `home-ns-refer-toolkit-nses` L441) · `src/seon/repl/internal.cljc` (`prose-token?` L175-188) · `src/seon/agent/turn.cljs` (L92-93)

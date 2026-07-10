---
type: research
status: active
tags: [research, agent]
---

# Instruction-surface migration — the always-on floor vs. skill depth (2026-07-06)

Audit + design only. No `src/` edits. EXTENDS the REPL-scoped audit
([[repl-usage-instruction-audit-2026-07-06]]) from eval/REPL mechanics to
**every agent-facing instruction surface**. Owner frame: **load-bearing
instruction an agent needs EVERY turn must be ALWAYS-ON — never trapped behind
the agent choosing to `(load)` a skill (opt-in = unreliable).** Concentrate the
universal floor into the live `seon.agent.ctx/system-text`; keep deep reference
in skills, reachable by pointer. Migration is SELECTIVE — system-text is paid
every turn (~6000 tok today), so it stays tight.

## TL;DR — the decision

- **The skill mechanism is already two-tier, and that is the crux.** Each skill
  seeds an always-on **L0 catalog line** (name + `Use when…` description,
  priority-12 block, EVERY agent) plus an **L2 full body** that renders ONLY
  while `(my.skills/load :name)`d (priority-30 volatile band). The default
  load-set is `[:repl]` — so **`repl`'s body is the ONE always-on skill body;
  the other five bodies are opt-in.** The catalog trigger reaches every agent,
  but acting on it (loading) is the agent's choice — exactly the unreliable
  opt-in the owner names.
- **The RENDERED agent corpus is `seon-skills/` (6 skills), NOT `.claude/skills`
  (10).** `config/system.edn` pins `:seon.config/dirs ["seon-skills"]`. The 4
  extra `.claude/skills` dirs (`browser-automation`, `clojure-testing`,
  `datastar-web-ui`, `seon-context-config`) are **Claude-Code-dev-only REAL
  dirs — not symlinks, not in the agent catalog.** (DRIFT: `my.skills` +
  `list-skill-files` docstrings still say ".claude/skills symlinks the shared
  seon-skills/* dirs" and treat `.claude/skills` as the corpus — stale; fix in
  the same pass.)
- **The single sharpest gap: there is NO always-on `^:async`/`await` rule.** An
  agent that writes a bare top-level `(await result/<id>)` gets a self-host
  throw, and the only teaching is the NON-default `/clojurescript` body. This is
  the proven "trapped-but-load-bearing" case. It MUST move to system-text. (This
  is the SAME line the REPL audit's §3c already earmarks — owned jointly, added
  once.)
- **Almost everything else load-bearing is ALREADY always-on.** system-text
  already carries: errors-are-values, register!-before-transact, namespaced DB
  attrs, def-persistence, result/<id> vars, show-don't-tell + auto-run tiles,
  discover-don't-hallucinate, build-your-environment, full-qualify the toolkit,
  plan/message/lifecycle doctrine. The skills mostly DEEPEN these, not gate them.
- **The migration is therefore SMALL:** ~4–6 new floor lines beyond what the
  REPL/A9 pass already adds (async rule [shared with A9], the `:malli/schema`-is-
  enforced line, extend "namespaced keys" to ALL maps, one no-`:kind` floor
  line). Net add ≈ 150 tok on a ~6000-tok block (~2.5%). Everything else STAYS
  in skills as depth, reachable by the always-on catalog trigger + `(load)`.
- **Hairiness: 2. Sequence: A8 (⟹ constant) → A9 (REPL concentration + subsection
  labels + the async line) → THIS (fold the cross-skill floor lines into the same
  labeled block + add skill back-pointers), bundled with A9 (same file).** Does
  NOT block the bench handoff — the floor is a reliability refinement; agents
  cope via catalog+load today.

---

## 1 — AUDIT: every agent-facing instruction surface

"Agent-facing" = renders into a live pod agent's prompt. Six surface classes.

### 1a — `system-text` (ctx.cljs:1101-1445) — THE always-on block

A byte-stable `def` emitted as the LLM `system` message (~23.9K chars ≈ **~6000
tokens**, every turn, in the cache prefix). NOT a `:seon.agent.ctx/block`, NOT
seed-copied — read LIVE every turn, so an edit reaches ALL agents next turn.
Content by topic (already catalogued line-by-line in the REPL audit §1a; here the
BEHAVIORAL half, which that audit deferred):

- WHERE-YOU-ARE / LIVE-CONTEXT / TRANSCRIPT-IS-EVAL'ABLE / EVAL-MECHANICS /
  FORMS-NOT-RESULTS / #code / RESULT-VARS / DEF-PERSISTENCE / ERRORS-ARE-VALUES
  — the REPL cluster (REPL audit owns).
- THE RENDERING SYSTEM + AUTO-RUN + SHOW-DON'T-TELL (1224-1242).
- THE SHARED STORE — register! before transact, namespaced attr keys (1244-1249).
- THE NAMESPACES BELOW — curated view, discover-don't-hallucinate, grep/inventory,
  pin via render-namespaces (1251-1267).
- BUILD YOUR ENVIRONMENT — create a ns, write real requires, register! a schema,
  colocate fns, full-qualify the my.* toolkit (1269-1287).
- STANDING TEACHINGS — consult-stored-knowledge-first, my.kb/remember, small
  tight fns + :test examples, plan!/active!/done! + resume-after-restart, ✉
  message-steps, markdown replies (1289-1343).
- MESSAGING + LIFECYCLE — message/user, message/agent, wait, complete, delegate!,
  finishing-is-an-act, report=data-message=pointer, sliding turn cap (1345-1445).

**Gap:** NO `^:async`/`await` rule anywhere in the block (grep-confirmed — only
"start! is async" appears, incidentally). **Stale self-description:** the
docstring still says "Usage teaching lives in the rendered namespace sources …
never here" — false (the REPL audit flagged this too).

### 1b — The skill corpus (`seon-skills/`, 6 skills) + the two-tier render

Mechanism (`my.skills.cljs` + `config.cljs:135-189`):

| Layer | What renders | When | Reaches |
|---|---|---|---|
| **L0 catalog** (`catalog-block`, prio 12) | one `;`-line/skill: name + `Use when…` desc + ●/○ | ALWAYS | every agent |
| **L2 body** (`skill-block`, prio 30) | the whole SKILL.md, `;`-commented + token-cost footer | only while `(load :name)`d | that agent |

Default `:my.skills/load` = `[:repl]` (`config.cljs:219`, `my.skills.cljs:58`) →
**`repl` body always-on; `clojurescript`/`data-oriented-clojure`/`datahike`/
`data-modeling`/`ui-live-tiles` bodies are opt-in.** Agents `(load)`/`(unload)`
to dial depth in; cost is derived (unload → the body + its token cost vanish).

Per-skill LOAD-BEARING content that an agent could NEED but MISS because the body
is opt-in (the migration-candidate hunt):

- **`repl`** (always-on) — NOT trapped. Duplicates system-text on comment-levels,
  what-runs-vs-dropped, `#code` (REPL audit owns the dedup). Unique depth:
  parinfer auto-fix rules, `:read`-error taxonomy, rewrite-clj segmentation.
- **`clojurescript`** (opt-in) — **the trapped set:** (1) `^:async`/`await` — bare
  top-level await throws "await can only be used in async contexts"; the ONLY
  teaching, and system-text has none → **MIGRATE**. (2) "you get data, not
  Promises" — `^:async` verbs auto-await and feel synchronous → **MIGRATE**
  (compressed, folds with (1)). Depth that STAYS: `(fn [])` strict-arity vs
  `constantly`, `(.then p :kw)` silent no-op, thenable-blind auto-await,
  instrumentation async/sync wedge. (`(def x 42)` non-persistence + bare
  `result/<id>` re-reference are ALREADY in system-text — not trapped.)
- **`data-oriented-clojure`** (opt-in) — the MINDSET skill. Trapped-and-floor-worthy:
  (a) **every map key is a namespaced keyword / no bare keys** — system-text only
  says it for DB attrs → **MIGRATE the general floor**. (b) **public fns carry
  `:malli/schema`; it is enforced at runtime and throws on mismatch** — nowhere
  always-on → **MIGRATE one line** (BUILD YOUR ENVIRONMENT). (c) **entities are
  attributes + connections — no `:type`/`:kind`** — in NO always-on surface,
  load-bearing for every data-writing agent → **MIGRATE one floor line**. Depth
  that STAYS: derive-don't-store rationale, db-is-a-value threading, reduce-over-
  loops, `:or`-present-nil footgun, provenance-on-tx, fix-in-place/no-foo-v2,
  write-a-real-test-ns. (home-ns aliases + full-qualify-toolkit are ALREADY in
  system-text.)
- **`datahike`** (opt-in) — the EXEMPLAR of legitimately-deep-STAYS. Its floor
  (register! before transact, namespaced attrs, transact! returns an envelope /
  read `::ok?`, store-inventory discovery) is ALREADY in system-text. Everything
  else — `:find` shapes, refs/components/identity, CAS, as-of/history, the
  ref-join and lookup-ref-type traps — is deep mechanics, correctly gated behind
  `(load :datahike)`.
- **`data-modeling`** (opt-in) — pure DESIGN depth (identity vs ref vs component,
  optional=absent, shared-shapes, map-in/map-out, the schema-is-the-generator).
  Nothing beyond the DoC/datahike floor is universal. STAYS entire.
- **`ui-live-tiles`** (opt-in) — the INTENT (show-don't-tell, set your tile,
  auto-run views, full-qualify in my.*) is ALREADY always-on in THE RENDERING
  SYSTEM. Deep how — `:seon.render.live-tile/content` write, my.ui/my.tile/my.data
  helper sets, safelisted class vocabulary, compact/expanded faces, `seon.render/
  block` — correctly STAYS behind `(load)`.

**The 4 `.claude/skills`-only dirs are NOT agent-facing** (real dirs, not in
`seon-skills/`): `browser-automation`, `clojure-testing`, `datastar-web-ui`,
`seon-context-config` are Claude-Code-dev skills. Out of migration scope. Note
`clojure-testing`'s cljs.test guidance is already mirrored in DoC's
"write a real test ns" for agents — no gap.

### 1c — `my.kb.shared/instructions-block` (kb/shared.cljs:89) — cluster runtime append

Priority-10 always-on block, but SEEDS EMPTY (`seed-tx-data` → one `{::id
"shared"}`, no rows). Its OWN docstring correctly states the shipped behavioral
teachings live in system-text, NOT here — it is a runtime human/agent APPEND
surface, reactive (vanishes when empty). Carries no mechanics today. **Not a
migration target; leave.** (It is the RIGHT home for a human's later durable
standing order, distinct from hardcoded mechanics.)

### 1d — Per-verb docstrings rendered in the `:namespaces` block

The `my.*` corpus + current-ns + requires render in FULL source (per `src/my/
CLAUDE.md` "every line is agent-facing teaching"). Docstrings teach a verb's OWN
call-usage — correct and colocated. BEHAVIORAL leakage beyond call-usage is
minimal and already governed: line-1 ≤72-char summary rule; the `;=>`/`«shape»`
RESULT-echo representation is owned by the value-representation audits (do NOT
re-touch). **Leave** — a verb's own example stays with the verb.

### 1e — Identity file-blocks (SOUL.md / AGENTS.md) + masthead/resume

`SOUL.md`/`AGENTS.md` render as `file-block`s ONLY when `SEON_SOUL` is not off —
and **the default cluster runs `SEON_SOUL=false`** (config.cljs:142), so they are
OFF by default. They carry IDENTITY, not mechanics — not a load-bearing-mechanics
home. `transcript/masthead` + `resume-marker-line` carry block-specific runtime
cues and already defer to system-text (REPL audit §1d). **Leave all.**

### 1f — The default-ctx block set (config.cljs:135-189)

`shared-instructions`(10) · `skills-catalog`(12) · `namespaces`(20) ·
`live-tile`(35) · `warnings`(40) · `jobs`(42) · `test-failures`(43) · `plan`(45) ·
`relevant-source`(48) · `subagents`(96) · `findings`(97) · `transcript`(100).
All are DERIVED views (reactive, vanish when empty) — none is a static-instruction
home except the catalog (1b) and the empty shared-instructions (1c). Confirms
system-text is the single hardcoded-mechanics artifact.

---

## 2 — TABULATION: instruction topic × surface × class × load-bearing?

Class: **A** = always-on (system-text or L0 catalog / prio-≤12 block) · **L** =
L2 skill body (opt-in `(load)`) · **S** = seeded/runtime-append · **R** =
rendered-conditionally (docstring/file-block). ★ = load-bearing every-turn.
**TRAP** = load-bearing but only reachable via an opt-in surface.

| Topic | system-text | skill body | other | Class | LB | Verdict |
|---|---|---|---|---|---|---|
| REPL loop mechanics (forms/results/#code/vars/def) | ✓ | repl(dup) | masthead cue | A | ★ | REPL audit owns; keep in system-text |
| **`^:async`/`await` + Promise auto-await** | ✗ | clojurescript | AGENT.md(non-agent) | **L** | ★ | **TRAP → MIGRATE** (shared w/ A9) |
| errors-are-values / envelope shape | ✓ | doc, datahike | — | A | ★ | covered |
| register! before transact + namespaced ATTRS | ✓ | datahike | — | A | ★ | covered |
| **every MAP key namespaced / no bare keys** | partial (DB only) | doc | — | A/**L** | ★ | **extend floor → MIGRATE** |
| **public fn `:malli/schema` enforced (throws)** | ✗ | doc, data-modeling | — | **L** | ★ | **TRAP → MIGRATE 1 line** |
| **entities = attributes+connections, no `:kind`** | ✗ | doc, datahike, data-modeling | — | **L** | ★ | **TRAP → MIGRATE 1 floor line** |
| show-don't-tell / set your tile / auto-run | ✓ | ui-live-tiles | — | A | ★ | covered (intent A, how L) |
| full-qualify the my.* toolkit in authored ns | ✓ | doc, ui | src/my/CLAUDE | A | ★ | covered |
| discover-don't-hallucinate / store-inventory | ✓ | datahike | — | A | ★ | covered |
| plan!/resume / message/complete / finishing | ✓ | — | — | A | ★ | covered (one home) |
| derive-don't-store rationale | implied | doc, ui | — | **L** | ○ | depth STAYS |
| db-is-a-value / thread-once | ✗ | doc, datahike | — | L | ○ | depth STAYS |
| query `:find` shapes / refs / CAS / as-of | ✗ | datahike | — | L | ○ | depth STAYS (exemplar) |
| identity vs ref vs component / optional=absent | ✗ | data-modeling, datahike | — | L | ○ | depth STAYS |
| my.ui/my.tile/my.data helpers + safelist classes | ✗ | ui-live-tiles | src/my source | L | ○ | depth STAYS |
| parinfer/`:read`-error repair taxonomy | ✗ | repl | — | A(body) | ○ | depth (repl already A) |
| callable/thenable/instrument gotchas | ✗ | clojurescript | — | L | ○ | depth STAYS |
| cluster-wide standing orders | ✗ | — | kb.shared(empty) | S | ○ | runtime append home |
| identity/personality | ✗ | — | SOUL/AGENTS (off) | R | ○ | off by default; not mechanics |

**Duplications** (dedupe to a pointer): comment-levels, what-runs-vs-dropped,
`#code` — system-text ↔ `repl` (REPL audit owns). No new contradictions beyond
the REPL audit's `;=>`-vs-`⟹` glyph drift (A8/A9 own).

---

## 3 — DESIGN: the migration

### 3a — The MIGRATION LIST (move INTO system-text)

Each: the rule, its source skill, one-line rationale. All land as tight
`;`-lines inside the labeled subsections (§3c), NOT new paragraphs.

1. **`^:async`/`await` + auto-await** (from `clojurescript`): *"`await` only
   INSIDE an `^:async` fn — a bare top-level `(await x)` throws. `^:async` verbs
   (db/transact!, plan/*) auto-resolve to DATA, so they read as synchronous."*
   — Rationale: every agent writing or calling an async verb hits this every
   turn; the throw is silent-to-context today. (SAME line A9 §3c earmarks —
   added once, jointly owned.)
2. **Every map key is a namespaced keyword** (from `data-oriented-clojure`):
   extend the existing THE-SHARED-STORE namespaced-attr line to *"…and every key
   in every map you write (not just DB attrs) is a namespaced keyword — bare
   `:status`/`:ok` are refused."* — Rationale: the DB gate rejects bare attrs
   already; stating the general rule prevents the round-trip.
3. **`:malli/schema` is enforced at runtime** (from `data-oriented-clojure` /
   `data-modeling`): one clause in BUILD YOUR ENVIRONMENT — *"a public fn's
   `:malli/schema` is INSTRUMENTED — it validates args+return on every call and
   throws on a mismatch, so a wrong schema is a runtime bug, not a doc nit."* —
   Rationale: an agent that writes a verb needs to know the schema bites, or it
   reads an instrument error with no context.
4. **Entities are attributes + connections — no `:type`/`:kind`** (from three
   skills): one floor line by THE SHARED STORE — *"an entity has no kind/type —
   it IS its attributes + refs; never model a `:kind`/`:type` field, FIND by
   attribute presence."* — Rationale: appears load-bearing in datahike +
   data-modeling + doc yet is in NO always-on surface; the single most-repeated
   correction.

**Budget:** ~4–6 lines beyond the REPL/A9 pass ≈ **~150 tok** on ~6000
(~2.5%). Defensible: each closes a genuine always-on GAP, none duplicates
existing prose, and each is a floor rule (one sentence), not a deep dive.

### 3b — What legitimately STAYS in skills (depth, reachable by pointer)

The always-on **catalog line** already gives every agent each skill's `Use when…`
trigger; `(load :name)` pulls the body. STAYS as opt-in depth:

- **`datahike`** — all query/transact/pull/upsert/refs/CAS/as-of mechanics + the
  read traps. The exemplar of "deep reference stays."
- **`data-modeling`** — every schema-DESIGN decision + generative testing.
- **`data-oriented-clojure`** — derive-don't-store rationale, db-threading,
  reduce-over-loops, `:or` footgun, provenance-on-tx, no-foo-v2, test-ns.
- **`clojurescript`** — Promise-detection/thenable, arity/keyword-callback
  gotchas, the instrumentation async wedge, self-host quirks.
- **`ui-live-tiles`** — the tile-content write, my.ui/my.tile/my.data helper
  sets, safelisted classes, compact/expanded faces, `seon.render/block`.
- **`repl`** — parinfer repair + `:read` taxonomy (already always-on body via
  the `[:repl]` default; unique depth beyond the migrated REPL floor).

**The line, stated:** a rule MIGRATES iff it is (i) universal (every agent, not
just data-modelers or UI-builders) AND (ii) one-sentence-statable AND (iii)
its VIOLATION is silent/costly without the rule in front of the agent. A rule
STAYS iff it is task-specific depth OR needs a worked example to land — the
catalog trigger + `(load)` is sufficient for those.

### 3c — Target `system-text` STRUCTURE (`;;;`-labeled, after A9 + this pass)

One coherent block, top→bottom static→volatile, with the REPL audit's subsection
brackets absorbing this pass's floor lines (new items marked ⟩):

```
;;; ── WHERE YOU ARE ──          REPL / CLJS / Node / no-JVM
;;; ── REPL USAGE ──             (REPL audit / A9 owns this run)
      transcript-is-eval'able · what-runs-vs-dropped · forms-not-results
      · #code (1-liner + →repl skill) · result/<id> vars · def-persistence
      · errors-are-values · report-real-values · comment-levels (1 line →conventions)
    ⟩ ASYNC: await only inside ^:async; verbs auto-resolve to data   [MIG 1]
;;; ── HOW YOU WRITE DATA ──     (the floor, was THE SHARED STORE)
      register! before transact
    ⟩ every map key is a namespaced keyword — bare keys refused         [MIG 2]
    ⟩ entities = attributes + connections; no :type/:kind               [MIG 4]
;;; ── THE RENDERING SYSTEM ──   render twins · auto-run · show-don't-tell
;;; ── YOUR CONTEXT + NAMESPACES ── curated view · discover-don't-hallucinate
;;; ── BUILD YOUR ENVIRONMENT ── ns + requires · register! · colocate · full-qualify
    ⟩ :malli/schema is instrumented — validates + throws                [MIG 3]
;;; ── STANDING TEACHINGS ──     consult-stored-first · remember · small fns · plan/resume
;;; ── MESSAGING + LIFECYCLE ──  message/complete/wait/delegate · finishing-is-an-act
```

No new block, no new mechanism — ONE authority, made structured, absorbing the
four floor lines. (A dedicated "instruction" block would duplicate the frame and
split the authority — the parallel-system trap; rejected, same as the REPL
audit's §3a.)

### 3d — SKILL updates (keep skills current, de-emphasized as the HOME)

Each skill that loses (or shares) a load-bearing rule gets a ONE-LINE pointer at
the top of the relevant section — "the core rule is always in your context; this
is the deep dive" — so the skill stays accurate, not stale, and reads as depth:

- **`clojurescript`** — under `## ^:async and await`, prepend: *"The one-line
  rule (await only inside `^:async`; verbs auto-resolve to data) is ALWAYS in
  your context. This section is the depth: the gotchas below."* Keep the
  arity/thenable/wedge material verbatim.
- **`data-oriented-clojure`** — under "Every map key…", "Public fns carry
  `:malli/schema`", and "Entity = attributes + connections", prepend a single
  shared note: *"These three floor rules are always in your context; the skill
  keeps the WHY + the worked reflexes."*
- **`datahike`** / **`data-modeling`** — one line near the top of the "no
  entity kinds" step: *"The no-`:kind` floor is always-on; here is how it plays
  out in queries/design."* No content removed.
- **`ui-live-tiles`** — already opens by deferring to the always-on live-tile
  section ("This skill is the deep version of that section") — leave as the
  model.
- **`repl`** — trim the three duplicated topics (comment-levels, what-runs,
  `#code`) to pointers back to system-text (REPL audit §3d owns this edit).
- **`my.skills` + `list-skill-files` docstrings** — fix the stale
  ".claude/skills symlinks seon-skills/*" claim: the corpus is `seon-skills/`
  (manifest), `.claude/skills` is the Claude-Code-dev dir (real dirs). Same pass.

### 3e — ANTI-DRIFT: keep it from re-scattering

The owner rule is "no hand-maintained lists — every exception is a computed
structural rule." Applied here:

1. **Structural convention (the primary defense):** system-text is the SINGLE
   home for a universal load-bearing rule; a skill may only DEEPEN a rule, and
   a deepened rule is PREFIXED by a pointer back to system-text. So "where does a
   load-bearing rule live?" has one structural answer — the same code-as-data /
   one-mechanism discipline as the ONE-mechanism table.
2. **Warn-only lint (composes with the REPL audit's glyph lint, do NOT build a
   second linter):** extend the existing dev markdown/docstring linters to flag a
   `seon-skills/*/SKILL.md` section that STATES a rule verbatim also present in
   `system-text` WITHOUT a "always in your context" pointer prefix — a duplication
   detector, not a name list. Cheap heuristic: a skill line that reproduces a
   `system-text` sentence ≥N tokens and lacks the pointer marker warns.
3. **Fix system-text's own docstring** (shared with the REPL audit): delete the
   false "usage teaching … never here"; replace with the true statement that
   system-text is the always-on floor (mechanics + the four migrated rules),
   per-verb examples live in docstrings, and deep reference is the skill bodies
   reachable by `(load)`.

### 3f — SEQUENCING + hairiness

- **Hairiness: 2.** Prose additions (~4–6 lines) to one `def` + one-line pointer
  prepends to four skill files + one docstring fix + a stale-corpus-doc fix. No
  new mechanism, schema, or block; the lint reuses an existing dev linter.
- **Order:** **A8** (`result-marker`/⟹ constant wired to emit sites) → **A9**
  (REPL-usage concentration: the `;;;` subsection labels + the async line +
  glyph interpolation) → **THIS** (fold the three cross-skill floor lines into
  the SAME labeled subsections + the skill back-pointers + the corpus-doc fix).
- **Bundle with A9** — it touches the identical file (`ctx.cljs` system-text) and
  the same skill bodies; doing them in one edit window avoids double-churning
  ctx.cljs and the skills, and the async line is literally shared. This pass is
  the "widen A9 from REPL-mechanics to the universal floor" increment.
- **Does NOT block the bench handoff.** The floor is a reliability refinement
  (the catalog trigger + `(load)` gets agents there today, just less reliably);
  the only hard capability gap it closes — the missing always-on await rule — is
  already A9's earmarked add. Land as a tracked follow-up row in the dual-code-
  paths registry alongside A8/A9.

---

## Complexity artifacts found

- **Two-tier skills with a `[:repl]`-only always-on body** — the load-bearing
  `^:async`/`await` rule sits in the OPT-IN `clojurescript` body while `repl`
  alone is always-on. Subsuming system: `system-text` (the live floor).
  RECOMMENDATION: migrate the 4 floor lines (§3a), keep bodies as depth. ASK
  owner: approve the always-on-floor-vs-skill-depth line as drawn in §3b?
- **Stale corpus/symlink docstrings** — `my.skills` + `list-skill-files` claim
  `.claude/skills` is the corpus and symlinks `seon-skills/*`; the manifest pins
  `seon-skills` and `.claude/skills` holds REAL dirs (4 of them Claude-Code-only,
  not agent-facing). Subsuming system: `config/skills-dir` (the one resolver).
  RECOMMENDATION: fix the two docstrings in the same pass (§3d). ASK owner:
  confirm `seon-skills/` is the intended sole agent corpus and the 4 extra
  `.claude/skills` dirs are dev-only.
- **system-text self-description is false** — its docstring says usage teaching
  "never" lives there while ~60% of it is exactly that (shared with the REPL
  audit). Subsuming system: the block itself. RECOMMENDATION: rewrite the
  docstring (§3e.3). ASK owner: fold into the A9 edit.
- **`shared-instructions` block seeds empty + is priority-10 always-on** — a
  correct runtime-append home, but worth confirming it is never (re)purposed as a
  second mechanics home (it would then rival system-text). Subsuming system:
  system-text for mechanics, kb.shared for human/agent runtime orders.
  RECOMMENDATION: keep the split; the block's own docstring already states it.

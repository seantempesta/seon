---
type: research
status: active
tags: [research, agent]
---

# Value-representation consolidation — one authority for how a result looks (2026-07-06)

Inventory + design only. No `src/` edits. Consolidates every place that
STATICALLY shows a tool/eval RESULT in agent-facing context toward the ONE code
path that renders a real result, so (i) representation is a single swappable
edit, (ii) examples MATCH the runtime, and (iii) the example shape stops
teaching the fabrication shape.

## TL;DR — the decision

- **The runtime already has ONE authority for LIVE results:**
  `seon.render.value/render-ai` (the bounded skeleton) → `seon.agent.ctx/format-eval-row`
  (the `;=> <value> ; result/<id>` line). Real results render with a **single**
  `;=>` + a resolvable `result/<id>` handle + runtime-only glyphs
  (`⟨N tokens⟩`, `‹partial view …›`). This layer is clean (T4 observer: A7-honest
  across 25 drives).
- **The example corpus is INCONSISTENT and half of it teaches the wrong shape.**
  The five capability tools (`fs`/`shell`/`web`/`search`/`testrun`) write their
  docstring result echoes as **double** `;; =>` / `;;=>` — which is EXACTLY the
  fabrication shape DeepSeek emits (observer §2: fabricated echoes use double
  `;;=>` + invented ids). `db.cljs` already uses the RIGHT convention (single
  `;=>` + `«…»` "this is a SHAPE, not an answer"); `my.*` is mixed.
- **So example-format == fabricated-format != runtime-format** on the tools that
  matter most. Our own docstrings are a training corpus for the fabrication
  echo, and they don't match what the agent actually sees.
- **Recommendation (minimal, non-hairy): OPTION C+.** Standardize EVERY example
  echo to the runtime's single `;=>` and adopt `db.cljs`'s `«…»` shape-marker so
  an example reads as a SHAPE an agent would never type as a runtime value; add
  one warn-only lint rule in `seon.dev` to keep it from drifting. Leave the
  runtime as-is. Reject option B (bare `=> value`) — it breaks the eval'able-
  context north-star and collides with the fabrication neutralizer. Defer option
  A (generate examples from the renderer) as a follow-up. Hairiness of C+: **2**.
  Coordinate the completion-side with `fabrication-complete-gate-2026-07-06.md`
  (that owns the behavioral gate; this owns the representation).

---

## 1 — Inventory: every static result-echo in agent-facing context

"Agent-facing" = renders into an agent's prompt on the ACTIVE pod: `.cljs` tool
docstrings (compact/expanded namespace cards), the always-on `system-text`, and
the skills corpus. The `.clj` files (`render.clj`, `graph/*`, `web/browser.clj`,
`repl.clj`, `ns/*` …) are the **PAUSED JVM track — they do NOT render to a pod
agent**; ~90 `;; =>` hits there are out of scope (noted so the grep isn't
mistaken for agent surface).

Marker legend: **single** `;=>` = runtime shape; **double** `;; =>` / `;;=>` =
the fabrication shape.

| Corpus | File | Echoes | Marker used | Verdict |
|---|---|---|---|---|
| Capability tools | `src/seon/agent/shell.cljs` | 4 | **double `;; =>`** | WRONG — == fabrication shape |
| Capability tools | `src/seon/agent/web.cljs` | 3 | **double `;; =>`** | WRONG |
| Capability tools | `src/seon/agent/fs.cljs` | 2 | **double `;; =>`** | WRONG |
| Capability tools | `src/seon/agent/search.cljs` | 2 | **double `;; =>`** | WRONG |
| Capability tools | `src/seon/agent/testrun.cljs` | 1 | **double `;; =>`** | WRONG |
| Toolkit `my.*` | `src/my/data.cljs` | 4 | mixed (2 double, 2 single) | INCONSISTENT |
| Toolkit `my.*` | `src/my/blob.cljs` | 2 | single `;=>` | OK (shape-y) |
| Toolkit `my.*` | `src/my/kb.cljs` | 1 | single `;=>` | OK |
| Toolkit `my.*` | `src/my/skills.cljs` | 1 | double `;; =>` | WRONG |
| Toolkit `my.*` | `src/my/kb/shared.cljs` | 1 | double `;; =>` | WRONG |
| DB verbs | `src/seon/db.cljs` | 16 | single `;=>` + `«…»` shapes | **BEST — the model to copy** |
| Always-on | `src/seon/agent/ctx.cljs` `system-text` | teaches `;=>` (single) 3× | single `;=>` | CORRECT — already runtime-faithful |
| Skills (rendered) | `seon-skills/datahike/SKILL.md` | 13 | mixed | corpus, standardize |
| Skills (rendered) | `seon-skills/{data-modeling,data-oriented-clojure,ui-canvas}` + `datahike/references/*` | ~5 | mixed | corpus, standardize |

Counts across the agent-facing `.cljs` subset: **18 double vs 19 single** — a
near-even split with NO convention, and the split falls exactly on the axis that
matters: the capability tools (the ones a SWE agent leans on) are uniformly on
the wrong (fabrication) marker, while `db.cljs` is uniformly on the right one.

**Corpus confirmation:** the corpus that RENDERS to agents is `seon-skills/`
(manifest `config/system.edn:19` `:seon.config/dirs ["seon-skills"]`;
`.claude/skills` merely symlinks it and is the human-edit path). So
`seon-skills/**` is in scope; `.claude/skills` is not a second corpus.

**No example-rendering helper exists** — every echo above is a hand-written
static string in a docstring, read by the analyzer at index time. That is the
structural blocker to "one edit changes runtime + examples together" (§5).

## 2 — How the RUNTIME actually renders a real result

Traced: `seon.render.value/render-ai` (value.cljs:517 — bounded skeleton, honest
markers) → `seon.eval/render-result-edn` (stores `:seon.eval/result-edn`) →
`seon.agent.ctx/format-eval-row` (ctx.cljs:708 — composes the row). The docstring
of `format-eval-row` states the canonical shape verbatim:

```
; add 1 and 2
(+ 1 2)
;=> 3 ; result/EVLabc-123
```

- **Single `;=>`** (a COMMENT, so re-evaluating the transcript runs only the
  forms — the eval'able-context north-star).
- Trailing **` ; result/<id>`** — a live var handle that RESOLVES (re-reference
  returns the stashed value). Prior-session evals drop the handle.
- Failures: `;=> ✗ <guidance>` (no handle). Coalesced runs:
  `;=> ✗ N× <signature> …` (transcript.cljs:396).
- Clipped values carry runtime-only glyphs the agent would never type:
  `‹partial view of map N keys› … ⟨N tokens⟩`, `⟨⚠ TRUNCATED at 50 of N tokens⟩`,
  `(N of M)` on the handle.

Real rendered row (T4 observer §2, poker-d3 turn-5 prompt):
`;=> {…} ; result/wBL-2607061755` — single `;=>`, id resolves.

The `system-text` block (ctx.cljs:1100-1123) ALREADY teaches this exactly: "each
form's `;=>` value result", "the runtime writes the values, you write the forms",
"STOP … the runtime … shows you the real `;=>` value next turn". **The runtime
and the always-on framing are already single-`;=>` consistent. The DOCSTRING
EXAMPLES are the only thing out of step** — and they are the densest, most
copied surface (a per-verb card the agent reads before every tool call).

## 3 — Runtime vs example vs fabricated (the core mismatch)

| Aspect | RUNTIME (real) | EXAMPLE (capability docstrings) | FABRICATED (DeepSeek) |
|---|---|---|---|
| Marker | single `;=>` | **double `;; =>`** | **double `;;=>`** |
| Handle | ` ; result/<id>` (resolves) | usually absent | ` ; result/result-3` (invented, never resolves) |
| Glyphs | `⟨N tokens⟩` `‹partial…›` (runtime-only) | none | none |
| Provenance | appended by composer, AFTER the neutralizer | static string | hand-typed by model |

**The damning alignment: example-marker (double) == fabricated-marker (double)
!= runtime-marker (single).** Our capability-tool docstrings are literally a
worked example of the fabrication echo. The observer already flagged the double-
vs-single tell as the surface distinguisher (though not reliable as a gate —
an adversarial model can type single `;=>`). But at the TEACHING layer it is
load-bearing: an in-context example on the double marker primes the model toward
the shape it should never produce.

Note the runtime's DEFENSE already exists and confirms the design intent:
`seon.agent.ctx/neutralize-result-claims` (ctx.cljs:678) strips both `;; =>`
(commented) and bare `=>` (column-0) claims from model-authored channels,
rewriting them to `; [unverified narration — not a real result]`. It is
provenance-gated (runs on `:seon.eval/narration`/`source` only), so the real
composed `;=>` line is never touched. **The runtime already treats the double/
bare `=>` shape as untrusted — yet our own docstrings teach it.** Aligning the
examples to single `;=>` closes that contradiction.

## 4 — The consolidation design (ranked, with hairiness 1-5)

### Option A — one `render-value-for-agent`, examples GENERATED from it
Make `render-ai`/`format-eval-row` the sole authority AND generate the docstring
echoes from it (a REPL authoring helper, or a doc-render pass that runs the verb
on a fixture and formats via the real composer). Examples then can't drift and
are byte-identical to the runtime.
- **Feasibility:** low-medium. Docstrings are static strings read by the
  analyzer at index time; there is no doc-render hook, and running a capability
  verb (shell/web/fs — gated, side-effecting) at doc time is unsafe. A REPL
  helper that PRINTS the canonical form for a hand-supplied fixture value is the
  realistic sub-form (author pastes the output), but that's assistive, not
  enforcing.
- **Hairiness: 4** (new mechanism — a doc example generator — against the
  one-mechanism rule; side-effecting verbs make live-fixture generation unsafe).

### Option B — switch runtime + examples to REPL-authentic bare `=> value`
Render results as `=> value` (no leading `;`), indentation/prefix an agent
wouldn't type, to match a raw REPL's stdout and unify the mental model.
- **Feasibility:** REJECT. It breaks the **eval'able-context north-star** (the
  transcript must re-eval as valid Clojure running ONLY the forms — a bare
  `=> value` line is not a comment and would be read as a form/parse error). It
  also directly collides with `neutralize-result-claims`, whose DOMINANT case is
  a bare column-0 `=> value` fabrication (ctx.cljs:659 "the DOMINANT
  fabrication") — the runtime deliberately treats that shape as untrusted. You
  cannot make the runtime's real output the exact shape the runtime strips as
  fake.
- **Hairiness: 5** (fights two settled invariants). Rejected.

### Option C — standardize every example to the runtime's single `;=>`
Rewrite the 12 double-marker capability/`my.*`/skill echoes to single `;=>`, and
adopt `db.cljs`'s `«…»` shape-marker (`;=> «map: ::ok? true, ::exit 0, …»`) so an
example visibly SHOWS A SHAPE, not a concrete runtime value with a fake handle.
Add one warn-only lint rule in `seon.dev` that flags `;; =>` / bare `=>` in a
docstring and suggests `;=>`. Leave the runtime untouched.
- **Feasibility:** high. Pure docstring edits + one small linter (the dev-hook
  markdown/docstring linters are the precedent). The `«…»` convention already
  exists and works (`db.cljs:112` even carries the prose rule "Every `;=>` below
  is a SHAPE, not an answer — REPORT WHAT YOU COMPUTED").
- **Hairiness: 2.** This is C+ (C plus the shape-marker + lint).

**Ranking: C+ (2) > A (4) > B (rejected).** C+ delivers (i) one representation
authority already in place, (ii) REPL-expectation match (single `;=>` is what the
runtime and system-text already teach), and (iii) undercuts fabrication (the
example no longer models the double-`;;=>` echo; a `«shape»` is unmistakably not
a runtime value). A becomes a clean follow-up ONCE C+ has made the target format
uniform — a generator only helps after there's one format to generate.

### Comment-level interaction (conventions "Comment levels")
The fix is REQUIRED by the convention independently: a result echo is an INLINE
result comment on a form → it is **prose/inline → single `;`** per the rule
(conventions.md:868 "inline comment → `;`"). Double `;;` is reserved for a
block-comment standing ABOVE a form — which a `;; => value` echo is NOT. So the
capability docstrings' `;; =>` violate the comment-level rule too; moving to
single `;=>` fixes representation AND comment-level compliance in one edit. The
`«…»` guillemet is content inside the single-`;` comment, no marker conflict. Do
NOT add a `;;; ` structure bracket around examples — examples are prose, not
runtime-structure.

## 5 — Iteration/experimentation ergonomics

The owner wants "how a value looks" to be ONE swappable edit. Today:

- **For LIVE results it already is** — `render-ai` + the `seon.config` render
  caps (`value-max-depth/keys/items/string`, `verbatim-cap`, per the
  value-bounding map) are the single dial. Changing live representation = edit
  `render/value.cljs` + tune manifest caps. Good.
- **For EXAMPLES it is NOT** — ~30 hardcoded static strings across 11 files, on
  two different markers. Change the representation and every example drifts
  silently; that drift is precisely what produced the fabrication-shape corpus.

What C+ buys ergonomically: after standardizing, all examples share ONE format
(single `;=>` + `«shape»`), so the follow-up generator (Option A) has a single
target and the warn-lint prevents re-drift. The blocker to full "one edit updates
both" is structural (static docstrings, no doc-render hook, side-effecting
verbs) — worth a follow-up, not worth blocking C+ on.

## 6 — Recommendation + hairiness verdict

**Do OPTION C+ now (hairiness 2):**

1. Rewrite the 12 double-marker echoes (`shell` 4, `web` 3, `fs` 2, `search` 2,
   `testrun` 1, `skills` 1, `kb/shared` 1, `data` 2) + the mixed skills-corpus
   echoes to single `;=>`.
2. Adopt `db.cljs`'s `«…»` shape convention on every EXAMPLE echo (an example is
   a shape, not a value-with-handle) — do NOT invent fake `result/<id>` handles
   in docstrings; that is the fabrication shape.
3. Add a warn-only `seon.dev` lint: a docstring line matching `;; *=>` or a
   bare column-0 `=>` suggests single `;=>` (mirrors the existing docstring/
   markdown linters; no hand-maintained list — a structural regex rule).
4. Leave `render-ai`/`format-eval-row`/system-text untouched (already correct).

**Defer OPTION A (hairiness 4)** as a follow-up: a REPL authoring helper that
prints the canonical `format-eval-row` form for a supplied fixture, so an author
pastes real runtime output instead of hand-writing it. Do not build the live-
fixture doc generator (side-effecting verbs, new mechanism).

**Reject OPTION B.**

**Coordinate, don't duplicate:** the completion-side (agents fabricating echoes
+ `complete` in one reply) is owned by `fabrication-complete-gate-2026-07-06.md`
(the derived `complete`-gate). This document owns only the REPRESENTATION — make
the example shape stop teaching fabrication and match the runtime. The two are
complementary: the gate refuses the false completion; C+ removes the in-context
template for the fake echo. Neither alone is sufficient; both are cheap.

## Complexity artifacts found

- **Two markers for one concept** (`src/seon/agent/{shell,web,fs,search,testrun}.cljs`
  double `;; =>` vs `src/seon/db.cljs` single `;=>` + `«…»`) — the existing
  system that should subsume it: `format-eval-row`'s single `;=>` is the one
  runtime authority; the double-marker docstrings are residue. RECOMMENDATION:
  standardize to single `;=>` (Option C+), warn-lint to prevent re-drift. ASK
  owner: approve C+ and the follow-up generator (A) as separate units?
- **~30 hand-written static example strings** with no generator vs the ONE live
  renderer (`render-ai`) — a latent parallel "representation" surface.
  RECOMMENDATION: follow-up authoring helper (A), not a blocker.

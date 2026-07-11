---
type: research
status: completed
tags: [research, agent]
---

# Agent context introspection — DeepSeek agents critique their own context

Live DeepSeek-driven seon-agents were asked to do varied work AND be blunt
critics of the exact context they were reading. Every complaint below is
grounded against the byte-exact rendered context (fetched from each pod's
`GET /agent/root/debug` two-pane inspector, which renders through the same
`seon.agent.inspect/ctx-preview`) or against source. Verbatim quotes are
marked with their grounding location.

## TL;DR verdict

**The current context is largely CLEAR and the agents like it.** All three
subjects independently praised the namespace cards + inline `:malli/schema`,
the `my.*` toolkit split (`my.ui`/`my.canvas`/`my.data`), the `my.plan` verbs,
the `#code` heredoc, and the skills load/unload token economy — and they SHIPPED
real work through it (coding agent: 7/7 pytest green in 62 evals; DB agent:
registered a schema + stored + queried back 3 books; render agent: a live
Store-Inventory tile first-try). **The new bare-`⟹` grammar READ CLEARLY.**
No agent misread a `⟹` result line as a comment, and **no agent fabricated a
result value or mis-cited a `⟹` figure.** Every agent said the same thing: it
is clear "once you see it in action."

**But there are real, grounded defects.** The single sharpest is a
self-inflicted own-goal: the anti-fabrication marker
`;; [unverified narration — not a real result]` **misfires on genuine
prior-turn result handles**, actively teaching the agent to distrust its own
cross-turn result references — the exact opposite of the marker's purpose. The
other load-bearing issues are redundancy in the result-value instructions, a
section label that lies (`agents` = the shared-instructions file), a granted
capability that is invisible (shell), and a plan section rendered in UTC while
everything around it is local time.

Counts: **5 substantive drives** (3 clean critiques — exploration, DB-memory,
coding; 1 completed-but-critique-light — render; 1 partial — planning, timed
out/superseded, critique recovered from the transcript) + **3 inconclusive**
(the first wave: 3 concurrent drives on 3 pods all hit DeepSeek request
timeouts, 0 evals — see the transport note at the end; this is NOT a context
finding, it is a driving-concurrency constraint).

---

## Ranked findings

### 1. [HIGHEST] The `[unverified narration]` marker tags REAL result handles as fabrications

- **Surface:** transcript grammar / anti-fabrication marker (the "values you
  JUST computed" recap block).
- **Verbatim rendered text** (grounded, `gram-a` ctx, the recap block):

  ```
  ; result/haD-2607091837 ;; [unverified narration — not a real result]
  ; result/epo-2607091837 ;; [unverified narration — not a real result]
  ; result/cvR-2607091837 ;; [unverified narration — not a real result]
  ; result/QCH-2607091837 ;; [unverified narration — not a real result]
  ; result/AJc-2607091837 ;; [unverified narration — not a real result]
  ```

  These are real result vars from the agent's own prior turn (they returned
  `:my.books/title` etc.).
- **Agent reaction (verbatim, DB-memory agent):** "The unverified-narration
  tag is actively harmful. On turn 5, result lines from my PREVIOUS run
  (haD-2607091837, epo-2607091837, etc.) appeared tagged as
  `;; [unverified narration — not a real result]`. These WERE real results —
  I saw them return schema keywords on turn 4. Tagging prior-run results as
  unverified makes me **doubt whether result references survive across
  turns.**" (Independently re-flagged in the same run's second critique: "…is
  wrong and makes me doubt whether I can trust result references across
  turns.")
- **Why it's a defect:** The marker exists to neutralize *agent-typed fake*
  `⟹` lines (system text, grounded: "…and does nothing — the pod neutralizes
  it to [unverified narration]"). Applying it to the recap of genuine result
  handles inverts its intent and erodes trust in the one mechanism that flows
  values forward. This is the highest-leverage fix because it undermines the
  whole result-var contract.
- **Fix:** The "values you JUST computed" recap must render real prior-turn
  result ids WITHOUT the neutralizer tag; reserve the tag strictly for lines
  the agent authored as prose that mimic a result.

### 2. The result-value instructions are triplicated, and the truncation warning leads with a prohibition instead of the fix

- **Surface:** system instructions (redundancy) + truncation warning.
- **Agent reaction (DB-memory, verbatim):** "Redundancy in system
  instructions. The EVAL MECHANICS, REPORT THE VALUE, and RESULT VARS sections
  all say overlapping things about how results work. The Correct shape / Wrong
  shape example appears at least twice. This is roughly 200 tokens of repeated
  instruction." (Exploration agent independently: the `⟹…⟸` grammar is
  explained across "three different paragraphs" — "I did have to figure it out
  by cross-referencing three.")
- **Truncation warning, grounded** (`ctx.cljs:609-610`): `"is clipped, the
  live value is COMPLETE⟩"` + `"; Never summarize or quote beyond the shown "
  budget`. **Coding agent (verbatim):** "The truncation warning is
  self-contradictory — it says 'never summarize' but also 'the DISPLAY is
  clipped, the live value is COMPLETE.' If the value IS complete in the result
  var, why is the display clipped? … The warning should lead with 'Use
  `result/<id>` to access the full value' rather than 'Never summarize.'"
- **Why it's a defect:** ~200 tokens of repeated instruction on the hottest
  cache prefix, and the clip warning buries its one actionable sentence under a
  scold.
- **Fix:** Collapse EVAL MECHANICS / REPORT THE VALUE / RESULT VARS into one
  tight block with a single Correct/Wrong example; reword the clip message to
  lead with "Bind `result/<id>` for the full value —" then the caution.

### 3. The `agents` section bracket labels the shared-instructions file, not an agent roster

- **Surface:** section label.
- **Grounded** (`gram-a` ctx): `agents (14,130 tokens) ; # Seon — Shared
  Instructions` and the body line `**Every Codex instance reads this file** …`.
- **Agent reaction (exploration, verbatim):** "The 'agents' section at the very
  top is actually a SHARED INSTRUCTIONS file, not an agent roster. … I
  initially expected this to be a list of running agents. The bracket label
  `agents` is misleading — it should be something like `shared-instructions`
  or `seon-md`."
- **Why it's a defect:** `agents` reads as "the other agents," and this is the
  single largest section (14k tok) so the mislabel is costly to skim past.
- **Fix:** Rename the section bracket to `shared-instructions`.
- **Side note (minor):** the rendered file says "Every **Codex** instance"
  (the on-disk `CLAUDE.md` says "Claude") — a provider-neutralizer
  substitution leaking a foreign product name into the agent-facing text.
  Worth a glance but not load-bearing.

### 4. A granted capability is invisible: `SEON_SHELL=1` but no shell verb in the cards

- **Surface:** missing namespace card.
- **Grounded:** grepping the full rendered context for `shell` / `child_process`
  / `seon.agent.shell` / `exec` returns nothing in the namespace cards, yet the
  pod is launched with `SEON_SHELL=1`.
- **Agent reaction (coding, verbatim):** "The task asked me to 'use your shell
  verb to run its tests' but `seon.agent.shell` isn't in my namespace's
  requires. I had to discover `js/require("child_process")` on my own."
- **Why it's a defect:** the agent completed the task, but only by falling back
  to raw Node interop — the granted, presumably-safer shell verb is
  undiscoverable. A capability the operator granted should be visible in
  context.
- **Fix:** surface the shell namespace card whenever `SEON_SHELL` is granted
  (same reactive pattern as the fs card).

### 5. The plan section renders UTC while everything around it is local time

- **Surface:** plan rendering.
- **Grounded** (`gram-c` errored-turn ctx): plan item `✉ dnr-2607091832
  [2026-07-09 22:32] EXPLORATION TASK…` sits next to the message line `◀ from
  user @ 18:32:09` and the tile line `Thursday, July 9 · 06:34 PM` /
  `America/New_York`. Same event, `22:32` vs `18:32` — a 4-hour gap.
- **Agent reaction (exploration, verbatim):** "The 'plan' section says
  'Recently completed' but the items are from a PRIOR run. The timestamps are
  `2026-07-09 22:35` but the current time in the status line is `2026-07-09
  18:35` — the completed items are in the FUTURE (by ~4 hours). … It made me
  stop and wonder whether I should trust the plan state."
- **Why it's a defect:** future-dated plan items directly undercut trust in the
  plan surface — the one place the agent looks to know what is already done.
- **Fix:** render plan timestamps in the agent's local zone (same formatter the
  status line / message lines already use).

### 6. The `findings` section shows a bare blob SHA with no summary

- **Surface:** findings section.
- **Grounded** (`gram-c` ctx): `findings (88 tokens) ; stored findings — your
  accumulated knowledge, most-recent first.` … `; my.blob #2538:
  8bb7d6bbcd9d9491cca3259f621901c876969aa495d0b2ddd6935dc8ce042a40`.
- **Agent reaction (exploration, verbatim):** "a SHA256 hash as the sole
  summary line is useless for scanning. I'd need to pull every one to know
  what's stored. A one-line claim summary next to each blob would make this
  scannable."
- **Why it's a defect:** the section's whole job is recall, and it's
  unscannable — the agent must `pull` each blob to learn anything.
- **Fix:** render a one-line claim/title next to each blob eid; keep the hash
  as a secondary handle.

### 7. fs-denied warning gives backwards advice, and line-numbered `fs/view` breaks `fs/replace!` exact-match

- **Surface:** fs warning + edit protocol.
- **Grounded** (`fs.cljs:341`): `"granted root is often an ANCESTOR of the
  directory you happen to be in."` The pods' grant is a *subdirectory*
  (`SEON_FS_ROOT=…/tmp/t4-drive`), and the DB agent's real denial envelope
  rendered inline (grounded, `gram-a` transcript):

  ```
  (fs/read-file {:seon.agent.fs/path "src/my/kb.cljs"}) ⟹
    {:seon.agent.fs/ok? false, :seon.agent.fs/path "src/my/kb.cljs",
     :seon.agent.fs/error "path outside allowed-roots
       [\"/Users/sean/src/seon/tmp/t4-drive\"]"} ⟸ result/SsC-2607091837
  ```
- **Agent reaction (DB-memory, verbatim):** "the granted root is often an
  ANCESTOR of where you happen to be looking. In my case the grant was … a
  SUBDIRECTORY, not an ancestor. The advice is backwards." **Coding agent,
  verbatim:** "the error message says 'copy the EXACT text, including
  whitespace' — when I DID copy it from `fs/view`, the tab-prefixed line
  numbers (`N<tab>`) made it impossible to copy exactly. The `fs/read-file`
  content (no line numbers) should be the canonical source for matching, but
  the error doesn't suggest using it."
- **Why it's a defect:** the warning assumes the common broad-grant case and
  misleads on narrow grants; and the edit loop hands the agent line-numbered
  text then demands an exact-match that the line numbers break — this cost the
  coding agent several turns. (Directly relevant to the edit-protocol arc.)
- **Fix:** reword the fs warning to "your grant may be NARROWER than where you
  are looking — check `grants` for your actual allowed roots"; make
  `fs/replace!` match against the de-numbered content (or point the error at
  `fs/read-file`).

### 8. The `my.kb` manual hides its multi-field schema inside elided fn bodies

- **Surface:** manual namespace card.
- **Grounded** (`my.kb` card): the card registers only `my.kb/claim`,
  `/confidence`, `/source*` and shows fns like `titles-by-author`,
  `title+rating`, `retitle-source!` that clearly operate on a `my.kb.source`
  entity with title/rating/author — but every fn body is `…` (elided), so the
  `:my.kb.source/*` `register!` calls (inside `build-kb-example!`) are
  invisible.
- **Agent reaction (DB-memory, verbatim):** "The `remember` function stores one
  claim string … But the task wants multi-field data (title + author + rating
  + reason). The manual does not show the pattern for that. I had to infer:
  register my own schema, transact directly."
- **Why it's a defect:** the manual advertises a multi-field pattern via its fn
  names but the actual schema definition is compiled away, so the agent can't
  follow it and rolls its own — defeating the "self-describing manual" intent.
  (The agent's task still succeeded, but by re-deriving what the manual meant
  to teach.)
- **Fix:** surface the `my.kb.source/*` `register!` calls in the card (they are
  data, not body logic), or show `build-kb-example!`'s body un-elided as the
  worked example.

---

## Lesser findings / noise (agents flagged, lower leverage)

- **"Read as a note, not code" warning is a mixed signal and repeats
  per-line.** Coding agent: the bare-map warning "ALSO shows the map's keys —
  which makes me think it was partially parsed"; and "'⚠ Read as a note, not
  code: vector.' … repeated 5 times for the same bare vector — the
  deduplication should be per-form, not per-line." Fix: dedupe per form; don't
  echo the inert value's structure.
- **`⟸` arrow direction is mildly counterintuitive.** Coding agent: "my eye
  wants to parse `⟸` as 'pointing to' the result id, but it's actually a
  delimiter." (Everyone still parsed it correctly — cosmetic.)
- **Errored-run tombstone with no diagnostic.** Both a `gram-b` and `gram-c`
  transcript carried `run for root closed :error after 1 turn` from the
  timed-out first wave; coding agent: "a status line without context … a
  tombstone, not a diagnostic." Fix: when a run closes `:error`, surface the
  cause line in the transcript.
- **canvas section describes the tile in prose instead of showing its
  current state.** Exploration agent: "If the tile is my primary surface, I
  should see its CURRENT rendered state — or at least a compact summary — not
  just its source and a prose description."
- **Function-Instrumentation rules are duplicated** in both the system section
  and the (shared-instructions) `agents` section. Pick one canonical location.
- **`[JVM track — paused]` markers are pervasive** (~30-40% of the system
  section is about a track the pod can't use). Exploration agent suggests one
  banner instead of inline markers everywhere.
- **backtick-inside-a-string reader error is mislabeled.** DB-memory agent's
  own critique message (containing backtick-quoted identifiers) was discarded
  with "Invalid character: backtick found while reading keyword." Fix: message
  should say "unescaped backtick inside a string literal." (Note: the system
  text DOES warn about backticks; the failure still bit, and the error text
  misdirects.)

---

## What read CLEAR — do NOT break these

Every agent volunteered praise for these; they are load-bearing wins:

- **Namespace cards with inline `:malli/schema`.** Exploration agent: "the
  killer feature — I never have to guess what shape a fn expects or returns."
  Render agent: "The `:malli/schema` on the tile fn … validated cleanly on
  first try."
- **The `my.*` toolkit split** (`my.ui` static / `my.canvas` interactive /
  `my.data` aggregation) — "three small namespaces, each with a clear job."
  Render agent built a working tile with `my.ui/section` + `status-line` +
  `table` composition "straightforward … exactly one form" to wire.
- **`my.plan` verbs** read naturally; the plan section's done/open/frontier at
  a glance is "useful."
- **The `#code` heredoc** for foreign code — "elegant … zero escaping."
- **The skills load/unload token economy** — a good, legible mechanism.
- **The bare-`⟹ <value> ⟸ result/<id>` grammar itself.** Exploration agent:
  "clear ONCE YOU SEE IT IN ACTION. The double-arrow bookends are visually
  distinctive, and the `result/<id>` handle is the obvious way to reference a
  prior value." **No agent misread a `⟹` line as a comment, and none
  fabricated or mis-cited a result value.** The only friction is that its
  explanation is split across three sub-sections (see finding 2).

---

## Grammar verdict (the headline question)

**The new bare-`⟹` grammar succeeded.** Across all drives, agents read `⟹
<value> ⟸ result/<id>` lines as runtime output (not comments), cited real
result handles, and — critically — **did not fabricate any result values.**
The remaining grammar friction is entirely in the *teaching*, not the *form*:
the explanation is triplicated and split across EVAL MECHANICS / REPORT THE
VALUE / RESULT VARS, so agents "had to cross-reference three paragraphs" to
assemble the rule. Consolidate that (finding 2) and the grammar is done.

**The neutralizer, by contrast, backfired** — not because bare-`⟹` is
confusing, but because the "values you JUST computed" recap stamps genuine
prior-turn result handles with `[unverified narration — not a real result]`
(finding 1). That marker is the one place the new anti-fabrication machinery
is doing net harm, and it is the top fix.

---

## Method + honesty notes

- **Grounding path:** `GET http://127.0.0.1:<port>/agent/root/debug` renders the
  byte-exact `ctx-preview`; I stripped tags to text and grepped for each
  complaint. Every verbatim rendered quote above was located in that output or
  in source (`fs.cljs`, `ctx.cljs`, `my.kb.cljs` card).
- **Inconclusive drives:** the first wave drove all 3 pods concurrently; all
  three DeepSeek calls timed out (`:seon.ai/timeout? true`, 0 evals). Re-running
  a single drive alone succeeded immediately. **Driving-concurrency
  constraint: even across 3 different pods, 3 simultaneous ~30k-token DeepSeek
  requests time out.** Run drives one at a time. (Transport, not context.)
- **The planning drive** (17 turns) completed its work but the run was
  superseded by the ticker's autonomous turn (`halt beat fence lost —
  superseded; loop terminates`) and returned an empty reply; its critique was
  recovered from the transcript (it re-raised findings 1, 2, 5, 7). It also
  surfaced two runtime robustness signals outside the context-clarity scope: a
  `:user-input` tx of a `nil` attribute (`attr nil is not installed…`) and a
  `record-eval!` "DATA LOSS — bare eval row … failed with no tee rows to drop"
  on a `(complete …)` call. Flagged for the runtime lane, not for context.
- **Duplicate-plan-step caveat (honesty):** the "two identical plan steps"
  finding is real, but on `gram-a` it was partly induced by my own retry
  (the timed-out first-wave message and the re-sent message each minted a
  step). The underlying defect still stands — an errored, 0-eval turn should
  not leave a durable plan step — but the count was inflated by the retry.

The 3 clusters were left running and untouched.

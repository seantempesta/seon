---
type: research
status: active
tags: [research, render, agent]
---

# Fresh-eyes WTF audit — REPL transcript context PRD, 2026-08-10

Auditor charter (owner, verbatim): "I still have no idea how this is supposed
to work even after reading the PRD. Launch a Fable agent to audit it with
fresh eyes and all WTFs should be addressed." I read
[`repl-transcript-context-prd-2026-08-10.md`](repl-transcript-context-prd-2026-08-10.md)
(all 1,543 lines),
[`agent-interface-economy-2026-08-10.md`](agent-interface-economy-2026-08-10.md),
and
[`repl-transcript-context-prd-critique-2026-08-10.md`](repl-transcript-context-prd-critique-2026-08-10.md)
end to end, with no session context, by design.

## Verdict

**The document's core explanation does not exist and must be written fresh.**
Every fact needed to understand the design is present — but scattered so that
the one-sentence idea ("the prompt is the agent's own REPL session replayed
from stored facts; the web page is a second printing of the same session") is
never stated anywhere. The reader must assemble it from: a 9-clause "Decision
in one sentence," a storage-verification section at line 573, a footnote about
a hexadecimal address, and the acceptance contract. The worked examples — the
part the owner reads first — are unlabeled REPL scrollback where input, output,
stored, injected, and not-yet-built content are typographically identical.

The PRD is technically sound (the critique already forced that); it is
pedagogically broken. Every fix below is ready-to-apply prose.

Rename residue check: **clean.** No occurrence of "session units",
"seon.program/faces", "roster", or "the desk" survives in the PRD (verified by
search; the only "session" hits are links to the superseded 2026-08-01 file,
which correctly keep their historical names).

---

## WTF-1 — There is no "what is this thing" a newcomer can absorb — BLOCKER

**Where a reader breaks.** The document opens with provenance ("line-by-line
design for the owner ruling…"), then "Decision in one sentence" — which is
one 80-word sentence naming eight unintroduced concepts (agent-history value,
walk, agent root, `seon.print`, AI text target, agent profile, Hiccup target,
page profile). The plain claim the whole design rests on — *nothing is
assembled; the prompt is the agent's own stored REPL history printed back* —
appears nowhere in plain words.

**Fix — insert the following section in full, immediately after the title and
the two-sentence provenance paragraph, before "Decision in one sentence":**

> ## How this works (read this first)
>
> An agent in Seon operates a real Clojure REPL. Every model reply is parsed
> into ordered forms; every form is evaluated; every evaluation settles to a
> value. Both halves of every exchange are already durable database facts:
> the form's exact submitted text is stored at
> `:seon.cluster.run.form/source`, and the value it produced is stored at
> `:seon.cluster.eval/result-edn` as serialized print-node data (large values
> spill to a blob). This was verified against the live `default` cluster on
> 2026-08-10 — see [Storage verification](#storage-verification--live-default-door-mode-2026-08-10).
>
> This PRD makes one move: **the agent's prompt IS its own REPL session,
> replayed from those stored facts.** To build the model's context we do not
> assemble prose, summaries, headers, or schema walls; we query the agent's
> ordered history — bootstrap forms, inbound messages, submitted forms,
> settled values — and print it exactly as REPL scrollback:
> `namespace=> form`, then the value that form actually produced. The web
> page for the same agent is not a different report; it is the same ordered
> history printed to a second target (HTML Hiccup instead of text) and fitted
> with a different size profile. **The prompt and the page are two printings
> of one session.**
>
> Three consequences do all the work:
>
> 1. **Nothing re-executes at render time.** Rendering a history never
>    evaluates a form. Forms are display text; values are recorded facts,
>    decoded and printed. The only code that runs during rendering is a
>    declared render producer — a pure function whose argument is the value
>    being rendered — and the generic value printer beneath it.
> 2. **Exactly one form is synthesized.** Every agent form is verbatim stored
>    source. The single exception is an inbound message: arrival is displayed
>    as an injected `(my.message/read "<id>")` form whose value is the stored
>    message fact — an honest, rerunnable read the system writes on the
>    agent's behalf. Bootstrap is not synthesized either: its forms execute
>    once, for real, when the agent is created, and their receipts are stored
>    like any other run's; every later prompt replays them from facts.
> 3. **Teaching is demonstrated retrieval.** A fresh agent's opening context
>    is its stored bootstrap session: it looks as if the agent already ran
>    `(help)`, a `require`, a few `seon.program/docs` calls, one successful
>    `defn`, and its task-message read — each with the actual value it
>    returned. There is no API wall or schema dump; anything deeper is one
>    more `docs` call away.
>
> Everything else in this document is consequence: one pure derivation
> (`seon.render.walk/history`) returning the ordered form/value entries; two
> independent projection passes (AI text under the agent profile, HTML under
> the page profile) that must agree on entry identities but not bytes; a
> debug route whose pane is byte-equal to the next provider prompt; a `/`
> system view that is the same walk rooted at the cluster under a preview
> profile; and a deletion list for every mechanism this replaces (the
> separate transcript assembler, the schema wall, comment-framed headers,
> per-family budgets, the fleet append).
>
> Vocabulary used throughout: a **target** is a print destination — AI text
> or HTML Hiccup (the two P's of the REPL, per ruling 3). A **profile** is a
> declared fit policy (token budget, max depth, max children, composition) a
> consumer selects; fitting never changes which entries exist, only how much
> of each value is shown. The **walk** is the existing bounded traversal of
> database refs outward from a root entity. **Open** maps/entries admit
> extra keys under the accretion rule.

## WTF-2 — Where forms and values come from is revealed at line 573, in an appendix position — BLOCKER

**Where a reader breaks.** Questions the reader carries through the whole
first half — are these forms stored or synthesized? are values re-computed? —
are answered only by "Storage verification — live `default`, door mode"
(line 573) and by a footnote about a hex address (line 190). The "Exact
history-entry grammar" table's column is titled "Derived form," which actively
misleads: agent forms are *stored*, not derived; only the message read is
synthesized.

**Fix (three edits):**

1. The "How this works" insertion above answers it early and plainly (done in
   WTF-1).
2. In "Exact history-entry grammar," rename the column "Derived form" →
   **"Form (as displayed)"** and insert this sentence directly above the
   table: *"Provenance rule: every form in this table is verbatim stored
   `:seon.cluster.run.form/source` except the inbound-message read, which is
   the one synthesized form; every value is the recorded
   `:seon.cluster.eval/result-edn` (or the pulled message fact), decoded and
   printed — never recomputed."*
3. Retitle the storage section "Storage verification — the facts already
   exist (live `default`, 2026-08-10)" and add as its first sentence: *"This
   section proves the claim made in How-this-works: both halves of every
   exchange are already stored facts, so this PRD is render-side unification
   plus deletions, with no new storage."* ("door mode" — see WTF-10.)

## WTF-3 — "Nothing runs at render time" is never stated — HIGH

**Where a reader breaks.** Seeing `(my.message/read "inbound-…")` and
`(seon.render.walk/history …)` printed inside a transcript, a fresh reader
reasonably assumes rendering re-runs reads, or that displaying a form
executes it. Ruling 9's "cheap by construction" gate is meaningless until the
reader knows what could have been executed. The document implies it (retained
calls, zero-invocation counters) but never says it.

**Fix.** Covered by How-this-works consequence 1 (WTF-1). Additionally,
insert one sentence at the top of "Printer behavior by agent-history entry":
*"None of this evaluates any form: the printer consumes stored/derived
values, and the only executed code is a declared render producer whose
argument is the value being rendered, invoked through the existing guarded
kernel (`src/seon/render.clj:238-265`)."*

## WTF-4 — The worked examples do not label input, output, or provenance — BLOCKER

**Where a reader breaks.** This is the owner's literal complaint. Worked
example A is 60 lines of undifferentiated ```text``` in which the model's
input, the recorded output, injected system forms, and target-not-yet-built
values look identical. The provenance caveat ("Every `seon.program/docs`
result above is a **target result**…") arrives *after* the block, applies to
only some exchanges, and never marks which lines it governs.

**Fix.** Restructure both worked examples (A and B) into numbered exchanges
in the approved convention — one-line description, provenance tag, separated
IN/OUT fenced blocks — and keep the flat REPL text block *after* them under
the heading "What the model literally sees" (the flat block remains the byte
authority; the exchanges are the explanation). Provenance tags are exactly
three: `[stored]` (a real receipt), `[injected]` (the one synthesized
message-read form), `[target]` (not built yet; Phase 3 re-executes and pastes
actual bytes). Ready-to-apply rewrites of three representative exchanges from
example A; apply the same pattern to the rest:

> #### Exchange 1 — the standing orientation `[stored — bootstrap receipt]`
>
> IN (exact `:seon.cluster.run.form/source`):
>
> ```clojure
> (help)
> ```
>
> OUT (recorded value, decoded from `:seon.cluster.eval/result-edn`):
>
> ```clojure
> "You are task-agent-17 in a Seon cluster, operating a real Clojure REPL. Your reply is read as ordered forms; each form settles to the value printed below it. …"
> ```
>
> #### Exchange 4 — pull the run API's docs `[target — Phase 3 re-executes this and pastes the actual bytes]`
>
> IN:
>
> ```clojure
> (seon.program/docs {:seon.program/identities ['my.run] :seon.program/detail :summary})
> ```
>
> OUT (target value; `seon.program/docs` is unbuilt — current `doc` prints
> prose and returns `nil`, `src/seon/sci/eval.clj:1111-1122`):
>
> ```clojure
> [{:seon.ns/name my.run,
>   :seon.ns/public-functions
>   [{:seon.program/name my.run/complete, :seon.fn/arglists ([result]),
>     :seon.fn/doc "Finish this run with a reply for its requester."}
>    …]}]
> ```
>
> #### Exchange 12 — the task arrives `[injected — the one synthesized form; the value is the stored message fact]`
>
> IN (written by the system on the agent's behalf; honest and rerunnable):
>
> ```clojure
> (my.message/read "inbound-536871250-0")
> ```
>
> OUT (the pulled admitted message row):
>
> ```clojure
> {:seon.cluster.message/id "inbound-536871250-0",
>  :seon.cluster.message/to [:seon.cluster.agent/id "task-agent-17"],
>  :seon.cluster.message/content "Inspect the failed invoice import, explain the cause, and propose the smallest durable fix."}
> ```

Also apply to the mid-turn debug example and four-agent system example: each
gets one leading line stating what is input and what is display, e.g. for the
debug pane: *"Everything below is OUTPUT — the exact text the next provider
call receives; nothing here is typed by the owner."*

## WTF-5 — The total order is three unlabeled tuples, and unsettled-form display is defined 500 lines away — HIGH

**Where a reader breaks.** The order spec is three bare vectors
(`[1 at-ms 0 tx ordinal message-id]`) with no position labels. The reader
cannot verify the claimed property ("a message sorts before the run it
triggers") without reverse-engineering the tuple. And "a form without a
terminal receipt remains as an unsettled entry" never says what that *looks
like*; the answer (prompt+form, no value line, HTML `data-settled="false"`)
is only inferable from the debug example.

**Fix.** Replace the order block with:

> Entries sort by one lexicographically compared key vector; positions are:
>
> - **bootstrap form** — `[0 ordinal form-id]`: band 0 (bootstrap is always
>   the prefix), the form's ordinal within the bootstrap run, form identity
>   as the final tie-break;
> - **inbound message** — `[1 at-ms 0 tx ordinal message-id]`: band 1
>   (everything after bootstrap), the message's recorded arrival instant in
>   milliseconds, sub-band 0 (a message sorts before any form at the same
>   instant — so an arrival precedes the run it triggers), then transaction,
>   per-transaction ordinal, and message identity breaking same-transaction
>   ties;
> - **run form** — `[1 run-opened-ms 1 ordinal form-id]`: band 1, the owning
>   run's opening instant, sub-band 1, the form's ordinal within its run,
>   form identity.
>
> Every position is a stored or derived fact; no wall-clock read happens at
> render time. **An unsettled form** (no terminal receipt yet) stays in its
> ordinal position: the text target prints the prompt and exact form with no
> value beneath it — the transcript may end mid-exchange, exactly like a
> REPL awaiting a result — and the HTML target marks the entry
> `data-settled="false"`. Unsettled forms are never dropped, reordered, or
> given placeholder values. Runs named by `:seon.cluster.run/supersedes`
> are excluded entirely (the session-curation adoption rule).

(The position glosses above are the auditor's forced reading of the tuples;
the PRD lane must confirm each position name against
`seon.render.walk/history`'s implementation when it lands, per Phase 3
regression 2.)

## WTF-6 — "What actually changes" exists only as a 16-row survive/delete table — MEDIUM

**Where a reader breaks.** Question 6 ("what changes vs today, honestly?")
is answerable only by reading the whole deletion-inventory table plus the ABI
section plus five phase descriptions. There is no one honest list.

**Fix.** Insert this subsection immediately before "Current-state and
deletion inventory" (the table survives as the evidence behind it):

> ### What changes, in one list
>
> **Deleted** (git is the archive):
> `src/seon/render/transcript.clj` whole (the separate history assembler:
> its queries, six-entry tail, family rank order, invented token budget,
> AI/HTML assembly); the AI walk prose/comment assembler
> (`walk.clj:568-671` — `;; d0 · [:lookup]` headers and metadata);
> `compact-ai-text` and the namespace schema-wall closure
> (`ns.clj:354-475`); per-family size policies
> (`transcript.clj:26-30,700-752`, `ns.clj:320-330`); the outside-walk
> `fleet-call` append and bespoke fleet table (`web.clj:336-375`,
> `oversight.clj:261-301`); "renderer unavailable" substitute
> representations (`render.clj:479-519`, `walk.clj:424-438`); the
> deliberate-fault bootstrap lessons (`bootstrap.edn:49-70`);
> latest-capture as the debug pane's authority (captures stay as
> forensics).
>
> **Schema changes** (the only ones): new `:seon.render/producer-request`
> (nested value/context producer argument; `:seon.render/unit` disappears
> with its final consumer after all 49 contracts convert); new
> `:seon.render.view/*` (one cluster-global current-view entity — id, root
> ref, subject ref); one new profile value
> `:seon.render.profile/preview` using only existing profile keys; the
> agent renderer repointed to the one history producer
> (`seon.cluster.agent.edn`); new `bootstrap.edn` content in the same
> stored shape.
>
> **New functions:** `seon.render.walk/history` (the one pure data
> derivation), `seon.program/docs` (bulk data docs; bare `doc` keeps
> printing and returning nil under DOC-1), `my.message/read` (the injected
> honest message read), `my.view/current` and `my.view/show` (root's view
> retrieval and navigation effect).
>
> **Unchanged:** storage — form sources and settled results are already
> facts (verified live below), so no new history storage exists; the public
> `seon.render/walk` string contract; the SSE package/delta/keyframe
> delivery; provider capture facts; the guarded producer-invocation kernel.

## WTF-7 — "Decision in one sentence" is not one readable sentence — MEDIUM

**Fix.** Replace the current paragraph with:

> An agent's provider context, its web page, its debug pane, and the cluster
> system view are one thing: the agent's stored REPL history — every form it
> ran and the value each produced — derived once by
> `seon.render.walk/history` and printed independently to two targets (AI
> text, HTML Hiccup) under per-surface profiles. One derivation, two
> printings; no second assembler, no shared physical tee.

(The current second sentence about "not a requirement that two differently
fitted targets share one physical tee" survives as the last clause above;
delete the rest.)

## WTF-8 — Forward references to option names the reader has not met — MEDIUM

**Where a reader breaks.** "recommended Option HUMAN-2" (Example B, line
271), "under recommended SCHEMA-1" (line 400), "under recommended PREVIEW-1"
(line 869), and the whole "Live NESTED-2 decision probe" section (line 841)
all precede their option blocks by 100–700 lines.

**Fix.** Add a defining parenthetical at each first use:

- Example B intro: "…under recommended Option HUMAN-2 *(defined in the
  option blocks below: the message arrives as an injected honest
  `(my.message/read "id")` form rather than a pasted literal)*."
- SCHEMA-1 first use: "…under recommended SCHEMA-1 *(schema definitions
  appear only when a displayed API references them or the agent asks)*…"
- PREVIEW-1 first use: "…under recommended PREVIEW-1 *(an agent's preview is
  its most-recently-changed settled block, any family)*…"
- Open the NESTED probe section with: *"This probe decides the NESTED option
  block below — whether a value nested inside another value may select a
  declared producer, and whether checking for one is affordable at every
  node."*

## WTF-9 — Bootstrap execution semantics live in a footnote about a hex address — HIGH

**Where a reader breaks.** Whether bootstrap forms actually *execute* (and
when) is the difference between "honest replay" and "fabricated transcript."
The only evidence is the pre-example sentence "The hexadecimal object address
is the stored printed representation from the creation run" — which presumes
the reader already knows there *is* a creation run, and refers to an address
the reader has not yet seen.

**Fix.** Covered in How-this-works consequence 2. Additionally, replace that
sentence with: *"Bootstrap forms execute once, for real, when the agent is
created; their receipts are stored like any other run's, which is why the
`#object[sci.lang.Namespace 0x1a2b3c4d …]` value below shows a stable stored
address rather than one regenerated per prompt."* — and move it to directly
precede Exchange 2 (the `in-ns` exchange) once WTF-4's restructure lands.

## WTF-10 — Unexplained jargon at first use — MEDIUM

Each item is one insertion at its first occurrence:

- **"two P targets"** (line 26): append "*(the P of Read-Eval-Print: one
  printer, two destinations — AI text and HTML Hiccup)*". The whole
  P-in-REPL framing lives only in the ruling doc; the PRD must not assume
  the reader sat through it.
- **"door mode"** (Storage verification heading, line 573): no reader knows
  this term. Replace with the plain condition it names (the effect-door
  configuration the probe ran under) or delete it from the heading; if it
  matters to reproduction, one sentence in the section body must say what it
  is.
- **"c:p"** (lines 1053, 1068): first use must read "completion:prompt token
  ratio (c:p)". The abbreviation is otherwise defined only implicitly by a
  Measured-baselines row 120 lines later.
- **"acquired candidates"** (probe table, line 895): first use precedes its
  definition (in the Decision paragraph). Add at first use: "*(producer
  candidates derived once per acquired program generation, keyed by target
  and accepted output shape — defined under Decision below)*".
- **"open form/value entries" / "open maps"**: gloss once at first use —
  "*(open: extra keys admissible, per the accretion rule)*".
- **"walk"** and **"profile"**: defined by the WTF-1 vocabulary paragraph.

## WTF-11 — Option blocks state options but not stakes — MEDIUM

**Where a reader breaks.** Each block gives three priced options, but a
fresh reader (the owner, ruling) cannot tell what breaks or what is won by
choosing. NESTED is the worst: nothing says why "producer selection inside
print values" matters at all.

**Fix.** Add one "Stakes:" line under each block heading:

- **NESTED:** "Stakes: whether a producer an agent authored for a shape
  takes effect when that shape appears *inside* another value — and whether
  looking for one makes every render slow. (Ruling 8 proved top-level
  auto-selection live; this decides nesting.)"
- **HUMAN:** "Stakes: the bytes the model sees every time a person messages
  it — the highest-frequency injected form in the system."
- **PROSE:** "Stakes: whether a model reply containing only prose (no forms)
  is representable in history, or stays a loud no-forms result."
- **SCHEMA:** "Stakes: replaces the 43-line, 3,459-token schema wall
  measured in every root context."
- **DOC:** "Stakes: whether bare `doc` stays Clojure-familiar
  (print + nil) or silently changes meaning."
- **OPENING / MINIMUM:** "Stakes: the fixed token cost prepaid by every
  fresh agent, and the experiment that proves which forms earn their place."
- **DEBUG-LIVE / PREVIEW / SYSTEM-ROUTE** (ruled): "Stakes: [one line
  restating what the ruling bought]" — e.g. for DEBUG-LIVE: "the debug pane
  equals the next provider prompt byte-for-byte instead of showing a stale
  capture or nothing."

## WTF-12 — "The prompt bytes ARE the text projection" is stated only obliquely — MEDIUM

**Where a reader breaks.** The strongest claim in the design — the provider
prompt is byte-equal to the history's text projection, which is why the
debug pane can be proven correct — is scattered across "prompt acquisition
… acquires the agent-history text projection" (deletion table) and "debug
bytes equal prompt-acquisition bytes" (Phase 4 regression).

**Fix.** One sentence at the end of the "One agent-history owner" section:
*"Prompt acquisition does not assemble anything of its own: the provider
context IS the text projection of this history under the agent profile,
which is what makes the debug pane provable — at one database value its
bytes must equal the next acquired provider context exactly."*

## WTF-13 — Phase numbering silently disagrees with the ruling document — LOW

The ruling doc's plan skeleton has Phases 1–4; this PRD has Phases 0–7 with
different content. A reader cross-reading them will try to map "Phase 2" to
"Phase 2" and fail.

**Fix.** One sentence at the top of "Implementation phases": *"These phases
supersede the coarser 4-phase skeleton in the ruling document; the mapping
is skeleton-1 → Phases 1–2, skeleton-2 → Phase 3, skeleton-3 → Phases 4–6,
skeleton-4 → the bad-output classes that survive Phase 7."*

## WTF-14 — The grammar table's "Settled success" row implies two rows per exchange — LOW

The table has separate "Agent form" and "Settled success" rows whose form
cells say "Exact source" and "Same agent form; never a second form" — a
reader briefly parses this as two entries. The prose above ("one entry owns
one form and its optional settled value") already forbids that.

**Fix.** Add one sentence under the table: *"These families are attribute
patterns on ONE entry each, not separate entries: 'settled success/error'
rows describe how an agent-form entry's value slot fills when its receipt
settles — the form is never repeated."*

---

## Top 5 (for the final report)

1. **WTF-1** — no plain "how this works" page; the core explanation must be
   written fresh (draft supplied above, ready to insert).
2. **WTF-4** — worked examples are unlabeled scrollback; no IN/OUT/provenance
   separation (rewrite convention + three model exchanges supplied).
3. **WTF-2** — the stored-forms/recorded-values/one-synthesized-form truth is
   buried at line 573 and contradicted by a column titled "Derived form".
4. **WTF-9 + WTF-3** — bootstrap-executes-once and nothing-re-executes are
   the two facts that make the design honest, and neither is stated plainly.
5. **WTF-5** — the total order is three unlabeled tuples and the unsettled
   form's appearance is defined only by inference from a distant example.

## What is this thing (page-one answer)

Seon agents already talk through a real REPL, and both halves of every
exchange are already database facts: the exact form text the model submitted
and the printed value it settled to. This PRD stops assembling prompts and
pages out of prose, summaries, and schema walls, and instead replays the
agent's own stored REPL session: the prompt the model sees, the agent's web
page, the debug pane, and the cluster overview are all the same ordered list
of form/value exchanges, printed to two destinations (plain text for the
model, Hiccup for the browser) at different sizes. Nothing re-executes at
render time; only one form (the message-arrival read) is ever synthesized;
a fresh agent's "instructions" are just its bootstrap session — real forms it
actually ran at creation, with the real values they returned.

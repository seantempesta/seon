---
type: research
status: draft
tags: [research, render, design]
---

# The one pipeline — candidate design sketch (for iteration, not ruled)

Orchestrator, 2026-08-14 evening. This is the elegance pass the owner
asked for: the smallest coherent shape that makes every audited defect
class UNCONSTRUCTABLE rather than discouraged. It is written assuming
particular answers to the open questions (Q0-a, Q1-i, Q4 seam-A-only —
marked inline); if the owner rules differently the affected paragraph
changes, not the skeleton. Companion evidence:
[renderer-reaudit](renderer-reaudit-2026-08-14.md),
[value-printer-archaeology](value-printer-archaeology-2026-08-14.md),
the in-flight [seam-hole census](seam-hole-census-2026-08-14.md) and
[deletion register](deletion-register-2026-08-14.md).

## The whole system in one sentence

One walk derives an ordered vector of blocks from one database value;
one recursive printer gives every block its two faces through one
selection chain; the AI seam is `join` plus a member-level budget; the
HTML seam is the namespace view's arrangement of the same vector — and
every stage hands the next a TYPED value the next stage requires, so a
value that skipped a stage cannot cross a seam.

```text
raw value ──[admit]──▶ node tree ──[walk+select]──▶ block vector ──┬─[seam A]─▶ prompt bytes
 (hostile)  guarded,    closed        one chain,      (ordered,     └─[seam B']─▶ namespace view
            caps,       grammar,      re-entered      identified,
            elisions    elisions      per node        both faces)
```

(Admission's storage caps are the PRD's seam B; the HTML seam is B' —
no budget, arrangement only.)

## Stage 1 — admission: the only code that touches a raw value

`seon.sci.admit` stays the one guarded walk (interrupt-fn, node
budget), gaining the archive's lazy-realization guard: a poisoned
realization degrades to an `::object` marker naming the cause —
admission PROMISES it never throws. Its grammar change is one rule:
**the bare elision marker becomes unrepresentable.** The node schema
requires count/path/bound-by and requery-or-refusal on every `::elided`
/ `::pruned` / `::truncated-string` node, so `enrich-elisions` and the
fabricated-sentence defect (reaudit §2.2) are deleted rather than
fixed. Everything downstream is finite, pure, closed-grammar data —
no safety reasoning survives past this line.

## Stage 2 — one selection per unit; faces terminal; the floor composes the unclaimed (RULED 35/36)

**Corrected against the owner's ruling (35) — the earlier frames
recommendation is dead.** Selection runs once per unit through the ONE
chain — explicit projection key; the owning-namespace face discovered
through ordinary program facts (ruling 36: a defined function IS pull
data; no registration, no second discovery mechanism); the
schema-attached face; the floor. **A declared face is TERMINAL: it
owns its value's output.** Composition lives where it lives today — in
the floor — but promoted to first quality: for a value nothing
claimed, the floor walk detects nested registered shapes and composes
their declared faces (the `project-node*` mechanism, kept at its mount,
made excellent). Nested quality INSIDE a declared face is a curation
duty under ruling 34: a face that `pr-str`s its nested values (today's
`seon.error` evidence) is a defect fixed AT that face — typically by
the face calling the floor for that sub-value as an ordinary function
call — never by machinery inserted underneath it.

What still falls out:

- one chain, one calling convention; the hardcoded floor-symbol set
  dies (the floor is the declared last rung, not a special-cased pair
  of symbols);
- functions render through a default face whose output is the FORM
  that generates their data (ruling 36) — the agent's REPL, the
  panels, and `doc`/`dir` are all this one mechanism;
- ruling 34's census tracks which families still ride the floor in
  either projection — the floor being GOOD never makes it the goal.

## Stage 3 — the printer: sample→emit, two sinks, one elision value

The synthesis from the archaeology: admission already produced the
bounded skeleton (sample); emit runs once with the archive's
`fits?`-layout (inline when it fits at this indent, else one child per
line), payload-first degradation (`dominant-string` promotion), the
verbatim probe for tiny values, and the current tee (text + structural
hiccup in one traversal — P-TEE stands). The `fit` convergence loop and
`fit-terminal`'s second character pass are deleted; **the printer has
no budget** (owner's seam correction).

The [prior-art survey](value-browser-prior-art-2026-08-14.md) supplies
the exact mechanics: reveal's `reduced`-propagating op stream bounds an
arbitrary value in ONE traversal (the direct replacement for the
re-emitting fit loop), and its `sf-wider-than?` is a `fits?` probe
costing O(width) not O(value); orchard's independent `max-atom-length`
vs `max-value-length` bounds structurally kill the halve-the-payload
inversion, and its page-size+1 probe answers "is there more" without
counting (uncountable renders as a typed `?`, never a guess); reveal's
never-force-a-deref discipline (realized?-gated, pending/failed state
names) and its semantic fill vocabulary extend our face set; malli's
relevance masking (elide by relevance, preserve indices/lengths) is the
principled generalization of dominant-string promotion; and the elision
node's `next-offset` should be designed as exactly what a Datastar
scroll/intersect handler posts back, unifying the drill protocol with
the HTML window. Presentation WINDOWS (a `/data`
page, an explicitly small view) are explicit request options that
produce elision values — never ambient defaults.

**Q0 RULED (ledger ruling 33) — simpler than any drafted option: no
regime bit at all.** Parity means framing fidelity (no comment
scaffolding, no narration; the transcript reads as a real REPL), never
stock elision bytes. There is ONE elision face everywhere — compact,
shape-bearing (`type, count, what remains, requery identity` at the cut
point; never a trailing annotation line) — and it fires only at
extremes. Defaults are sized so ordinary generated content (`help`
output, larger inter-agent messages, opening episodes) prints WHOLE:
this is the system's default printer and must be trustworthy without
options. The `::length 32`/`::level 8` defaults and bare `...`/`#`
faces die; the already-`known-divergence` parity elision rows are
rewritten to the one face. P-TEE stands untouched — one face means one
token stream.

## Stage 4 — the two seams, typed shut

**Seam A (model call):** consumes only pipeline-produced history
entries — records carrying block identity, basis, form, and the AI
bytes — and is, for now, a `join` in order and NOTHING else: ruling 37
defers all budget machinery until the pipeline works. The interim
depth knob is acquisition config; wrong content at a depth is fixed by
moving data around, not clipping. When budgets return, the ruled
design is member-level whole-or-chip (shape marker + requery identity)
measuring AI text only with the observed calibration. The four
existing budget loops die now regardless — their owners are deleted
with or without a successor budget. The reply-medium reminder and
every other prompt tail becomes an ordinary instruction block.

**Seam B' (namespace view):** consumes only serialized block packages
(the existing revisioned keyframe/delta machinery). The namespace view
arranges the same vector — newest-basis emphasis, disclosure,
windowing; no budget, no re-query, no re-walk. `session-timeline`, the
second lexer, `hiccup/raw` splices of AI text, and the `pop`/`conj`
surgery all die because the seam refuses raw hiccup and raw strings.

The [seam-hole census](seam-hole-census-2026-08-14.md) grounds this
stage in the live tree: 26 AI-side and 22 HTML-side holes, reduced to
SEVEN choke points (four of which are deletions) — one assembly type
at seam A; one agent-facing print exit; delete `::length`/`::level`;
admission owns every stored string; faces return data; HTML stays
hiccup until one linted delivery point (today it collapses to a string
at `web.clj:275`, which is why five sites re-splice with
`hiccup/raw`); no budget on the HTML path. Its two live-defect finds
reshape the work: the documented assembly (`walk/prose`) is DEAD CODE
— the real prompt is assembled in `web.clj/history-text`, and
`effect/context-suffix` has never reached a live prompt; and agent
print output is stored UNBOUNDED against a docstring that claims
otherwise.

**Unconstructability, concretely — three enforcement layers:**

1. **Types at the seams.** Prompt assembly and the web writer accept
   the pipeline's record types and refuse strings/bare hiccup — a
   violation is the stage-contract panic (dev) / error fact (prod).
2. **Graph-query censuses asserted empty** (generalizing
   `seon.fn/text-boundary-report`): callers of `emit-*`/`pr-str`
   feeding a seam outside the printer's owners; hiccup-with-content in
   non-face functions; budget-shaped calls outside seam A. Each census
   is one regression, subject-present by construction. Prerequisite
   named by the hole census: the analyzer must index CORE-call edges
   (`pr-str`, `str/join`, `subs` currently create no `:seon.fn/calls`
   edges), or a "no pr-str at a boundary" census is vacuous — the
   precise reason `text-boundary-report` and `seon.render.lint` are
   blind today.
3. **Grammar.** The bare elision, the un-identified block, and the
   budgetless-profile NPE are unrepresentable in the schemas, so the
   floor of every check is "it could not have been constructed."

## What this deletes (headline; arithmetic in the deletion register)

Both private fit engines (ns, transcript) and the prompt
distance-decrement loop; the dead `:summary` tier; `fit`'s convergence
loop + `fit-terminal`; `enrich-elisions` + three hand-rolled elision
phrasings; `project-node*`'s special position (absorbed into the walk);
`session-timeline` + the second lexer + `generic-entity`'s private
dump; the hand-written `<dt>/<dd>` twins (replaced by
`declared-attributes` through the chain); the hardcoded placeholder
literals (×4); the residual local caps (reaudit §3). Revived: ~4-500
lines of archive printer mechanics + the 192-line highlighter. The net
should be strongly negative — the register lane is doing the honest
arithmetic.

## Where each audited defect class dies

| Defect class | Killed by |
|---|---|
| silent truncation / bare `...` | grammar (elision value only) + Q0 face rule |
| narrated results | results-are-data + faces-as-frames (stage 2) |
| placeholder swallows | stage-contract panic/error-fact (PRD §2) |
| split fences / mid-form cuts | member-level seam A; printer has no budget |
| markup evicting prompt entries | seam A measures AI text only |
| specialist swallows subtree | selection re-entered per node |
| parallel content paths (web) | seam B' type refusal + census 2 |
| stale cached blocks | chain-hash invalidation (PRD §4 revival) |
| fabricated elision sentences | bare marker unrepresentable |
| two chains / dead step 2 | selection is one rung in one recursion |

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

## Stage 2 — one walk, one selection, re-entered per node (Q1-i)

The inversion that makes it turtles all the way down: **selection is
not a step before the printer; it is a rung inside it.** The emit walk,
at every node, asks the ONE chain — explicit projection key on the
value; unique contract-fitting public function in the owning namespace
when the pull edge carries one (Q3); the schema-attached face; the
structural face (the floor's grammar emit). A specialist face is a
FRAME, not a terminal: it renders its own value and its output enters
the sinks as an identified fragment, while nested schema'd values
inside it resolve through the same chain (the current `project-node*`
mechanism, promoted from under the floor to being the walk itself; the
existing `:seon.render/rendering` re-entrance guard carries over). A
face that genuinely owns its whole subtree (source text) declares
that — opting OUT of child composition is data, not the default.

Consequences that fall out for free:

- there is no separate "floor engine" — the floor is the face of last
  resort in the same recursion, so "one renderer" is literally one
  function;
- `seon.error/render-ai`'s raw `pr-str` of evidence dissolves: evidence
  is a nested value, so it composes through the chain like everything
  else;
- the two argument conventions and the hardcoded floor-symbol set die —
  a face is a face.

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

**The Q0 regime bit (assuming Q0-a):** the elision NODE always carries
its full facts; which TEXT it emits is a face decision made by the
render request's position — `:seon.print/parity` true exactly for
REPL-result positions in the transcript (stock `...`/`#`, byte-faithful
to a real REPL, `repl_parity_test` intact), rich text (`… +N more;
requery …`) everywhere else; the hiccup face is always rich. One value,
two spellings, zero information loss — the collision dissolves into a
one-boolean face choice at a named seam instead of two half-merged
regimes.

## Stage 4 — the two seams, typed shut

**Seam A (model call):** consumes only pipeline-produced history
entries — records carrying block identity, basis, form, and the AI
bytes. Budgeting is member-level against the prompt token budget with
the cluster-observed calibration: whole entries enter, or elide as
whole chips carrying requery identity. It measures AI text ONLY
(rip-out #19). The reply-medium reminder and every other prompt tail
becomes an ordinary instruction block — `prompt/text` is a `join`, and
nothing else.

**Seam B' (namespace view):** consumes only serialized block packages
(the existing revisioned keyframe/delta machinery). The namespace view
arranges the same vector — newest-basis emphasis, disclosure,
windowing; no budget, no re-query, no re-walk. `session-timeline`, the
second lexer, `hiccup/raw` splices of AI text, and the `pop`/`conj`
surgery all die because the seam refuses raw hiccup and raw strings.

**Unconstructability, concretely — three enforcement layers:**

1. **Types at the seams.** Prompt assembly and the web writer accept
   the pipeline's record types and refuse strings/bare hiccup — a
   violation is the stage-contract panic (dev) / error fact (prod).
2. **Graph-query censuses asserted empty** (generalizing
   `seon.fn/text-boundary-report`): callers of `emit-*`/`pr-str`
   feeding a seam outside the printer's owners; hiccup-with-content in
   non-face functions; budget-shaped calls outside seam A. Each census
   is one regression, subject-present by construction.
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

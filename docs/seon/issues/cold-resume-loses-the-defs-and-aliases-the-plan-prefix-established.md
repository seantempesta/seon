---
type: issue
status: open
severity: blocker
tags: [issue, agent-runtime, run-loop, sci]
---

# Cold resume loses the defs and aliases the plan prefix established

## Problem

A plan is an ordered fold: form 1 may `(require '[clojure.string :as str])` or
`(def x 1)`, and form 7 may use `str/join` or `x`. Within one process pass this
works because the fold threads one `sci` ctx and each form's defs accumulate in
it (`src/seon/sci/eval.clj:292-390` — a supplied ctx is used AS GIVEN,
deliberately, so the fold shares defs).

**Cold resume does not restore that ctx.** `loop/turn :resume` creates a fresh
`sci/fork`, queries only the target ordinal's source, and evaluates it
(`src/seon/cluster/loop.cljc:689-749`). Every def, require, alias and refer
established by ordinals 1..N-1 is gone. A resumed form 7 that referenced form
1's work fails with an unbound var or an unresolvable alias — not because the
agent wrote anything wrong, but because the process died between ordinals.

This is two distinct losses with the same cause:

- **evaluation context** — defs and requires the earlier forms installed;
- **reading context** — the aliases and refers those same forms established,
  which `::alias/kw` and syntax quote need at *read* time, before evaluation is
  even reached.

## Evidence

Found while falsifying the parse-primitives plan
(`docs/prds/sci-execution-runtime/research/parse-plan-falsification-2026-07-29.md`
SB2) and recorded in that plan's §1.6
(`docs/prds/sci-execution-runtime/plan/parse-primitives-plan-2026-07-29.md`).

The durable facts are narrower than resume needs: `run/plan-call` commits form
id, run, ordinal and **source only** (`src/seon/cluster/run.cljc:391-411`;
`resources/seon/schema/run.edn:59-70,93-96`). Nothing durable describes what the
prefix installed.

Note the failure is silent in the ordinary case: a plan whose forms are
independent resumes correctly, so the defect only appears for exactly the plans
that compose — which is the shape the generate-code loop is built around.

## Why the parse-primitives plan does not close it

That plan's S3 restores the **reading** context by re-reading the ordered plan
prefix, read-only, from a starting context frozen on the run. That is necessary
and it is not sufficient: a resumed form that *calls* a def from an earlier form
still fails at eval time, because nothing re-establishes the evaluation context.

Deliberately so — the crash model says nothing re-executes, so the fix cannot be
"replay the prefix." Re-evaluating forms 1..N-1 to rebuild the ctx would execute
capability requests a second time.

## Owner

`seon.cluster.loop` (the resume path), with `seon.sci.eval` for whatever ctx
contract the answer needs.

## Acceptance criteria

1. A plan whose form N depends on a def or require from form 1 either resumes
   correctly after a `kill -9` between the two, or fails with a flat error that
   names the lost prefix as the cause — never an unexplained unbound var.
2. Whatever restores it re-executes nothing: no capability request from an
   already-terminal ordinal runs twice.
3. One recurring test covers the class — a composing plan, killed mid-fold,
   resumed — rather than a point test per symbol kind.
4. The answer states its relationship to `:seon.agent.run/process` custody and
   to the generate-code fold ruling (a failed form stops the fold), so a resumed
   plan and a red-stopped plan do not disagree about what "remaining work" is.

## Related

- `docs/seon/issues/a-failed-form-does-not-stop-the-fold.md` — the sibling
  question about what the fold does after a red form.
- `docs/prds/sci-execution-runtime/plan/parse-primitives-plan-2026-07-29.md`
  §1.6 — the reading-context half, and D8's frozen starting context.

## Live reproduction and superseding recommendation 2026-07-29 (REPL lane)

Reproduced deterministically in-process, no `kill -9` required: the precondition
is not a crash but a fold STARTING mid-plan with a fresh ctx, which is just
facts (terminal receipts on the prefix). `tmp/repl-experiments/cold_resume_probe.clj`:

- `["(def x 41)" "(inc x)"]`, ordinal 0 pre-settled → ordinal 1 errors
  `Unable to resolve symbol: x` (evaluation context lost);
- `["(require '[clojure.string :as s])" "(s/upper-case \"hi\")"]` → ordinal 1
  errors `Unable to resolve symbol: s/upper-case` (reading context lost);
- `["(+ 1 1)" "(+ 2 2)"]` → ordinal 1 **succeeds**, which is the sibling issue's
  safety hole rather than this issue's failure.

Two corrections to the analysis above, both measured:

- a failed form does **not** break the fold — `next-ordinal` branches on the
  TRANSACTION outcome, not the evaluation (`loop.cljc:1053-1062`), so a plan
  with a red form runs to completion in one pass. That answers
  `a-failed-form-does-not-stop-the-fold.md` empirically;
- consequently the only route into cold resume is genuine custody loss.

**Recommendation: archive as SUPERSEDED rather than fix.** A plan is a fold over
one ctx, so a fold starting at ordinal k > 0 executes a different program than
the agent authored. The dissolution — "a plan may only be executed by a fold
that starts at its FIRST ordinal; an open planned run whose first unsettled
ordinal is not its first is an interruption to settle" — makes this issue
unrepresentable and closes
`boot-recovery-executes-unstarted-plan-suffix-after-interruption.md` at the same
time. Full argument, alternatives considered, exact edit list and the ownership
boundary that stopped the lane from landing it:
`docs/prds/sci-execution-runtime/research/repl-workflows-2026-07-29.md` §7.
Awaiting an owner ruling because it reverses a deliberate documented contract
and deletes two sealed test oracles.

## Triage 2026-07-29

**PRESSING — parser-merge wave.** Resume still forks a fresh SCI context while
durable form rows retain source rather than the evaluated prefix environment.
The reading-context half and this no-reexecution constraint must be settled
together by the parser-merge wave.

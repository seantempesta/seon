---
type: research
status: active
tags: [research, render, agent, context, review]
---

# Evolving session — independent fresh-context review

Reviewer: a fresh-context Fable lane, 2026-08-12. Per the assignment I read,
end to end and in order:
[self-generating-context-prd-2026-08-11.md](../plan/self-generating-context-prd-2026-08-11.md)
(all 36 rulings),
the "How this works" section plus worked example A of
[repl-transcript-context-prd-2026-08-10.md](../plan/repl-transcript-context-prd-2026-08-10.md),
[incremental-invalidation-design-2026-08-11.md](incremental-invalidation-design-2026-08-11.md),
and
[env-once-execution-design-2026-08-11.md](env-once-execution-design-2026-08-11.md);
then the landed generator source: `git show` of `3ef28735a` (live help
situation), `f7220b81f` (print-node frontier), `91f536c36` (symbol-frontier
episode), `0e39f6d11` (usage walkthrough render), `1559764d9` (wake reason +
turn budget), `b8e49538c` (explained fixed point), and `16f022fc9` (live-pull
episode generation), plus the current `src/my/run.clj`,
`src/seon/cluster/work.clj:540-620`, and `src/seon/render/walk.clj`
(`ordered-episode`). I did not read `tmp/orchestrator/` summaries or any
`evolving-session-exploration*` document.

## Verdict

**The design is sound.** The one-line system — context is a generated,
executed, append-only REPL history, invalidated by read evidence, taught by
suite-gated usage tests — is simpler than what it replaces, its bounded-turtles
argument (ruling 34) actually holds in the landed code, and the hard problems
that remain are placement, discipline, and cost-shape problems, not holes in
the model. Three load-bearing properties check out against source:

- **Generation is already a turn situation, not a render-proc job.**
  `next-agent-work` derives `:generate` for a system-authored run with no
  unexecuted form (`src/seon/cluster/work.clj:581-592`), and `generate-turn`
  in the run loop appends one form and folds it through the ordinary
  `resume-turn` (`src/seon/cluster/loop.clj:1576-1637`). Receipts therefore
  come from the one run fold; the render proc never transacts. This is the
  right shape and most of unknown 1 is already answered by it.
- **The prefix is machine-checked, not hoped for.** `bootstrap/next-entry`
  re-derives the episode and throws `::prefix-drift` when the stored receipts
  disagree with the regenerated prefix — determinism is a loud invariant in
  the code, not a doc claim.
- **The teaching cannot rot silently.** `my.run/usage-form` refuses to render
  when the indexed `:seon.test/usage` fact is absent, so the suite gate and
  the rendered demonstration are wired to the same declaration.

The main soundness risk I found is not in the rulings but in the landed
generator's reading of ruling 29: candidate emission is **supply-push** (emit
everything in the pull that becomes dependency-ready) where the ruling's
macroexpansion frame is naturally **demand-pull** (emit only what the action
arc's forms require). That one inversion is simultaneously the survey-loop
risk (unknown 6), the token-lean risk (ruling 7), and part of the cost risk
(unknown 8). It is fixable without touching the model. Details under 6.

## The unknowns, interrogated

### 1. Where wake-time generation runs — answered: the turn proc, as work

The dichotomy in the question (run-open derivation vs render proc) is false;
the landed code already contains the third option and it is correct:
**generation is a derived work situation executed by the agent's own turn
proc.** The wake law survives untouched — the listener routes, the render proc
stays read-only — because the turn proc is already the one place that holds
run custody and transacts during a wake. Extend exactly this to the mid-life
case:

- A message wake opens a run as today. Before prompt acquisition, the same
  `next-agent-work`-style derivation asks the generator for gap-closure
  entries (ruling 32): the pull diff against the retained history's shown
  bases yields the delta forms; each is appended `:author :system` to the
  freshly opened run and folded through `resume-turn`, exactly like the
  opening episode's forms; then the prompt derives. One mechanism for T0 and
  TN; "generate until no dependency-ready system form, then the model turn"
  is one sentence.
- Custody is a non-question under this placement: the run is already claimed
  by the process whose turn proc is executing; the generated forms are forms
  of that run.
- Latency is real but bounded and on the right thread: one pull (the open
  cold-pull issue is the same cost either way) plus executing a handful of
  delta reads. The owner's speed stance applies — a fast implementation
  exists (warm pull measured 1.9 ms; the delta reads are per-new-id lookups).
- The render proc's passive appends (ruling 6, page morphs between turns)
  remain **display-side only**: it re-renders retained blocks from evidence
  and updates the page, but the durable history entries for the agent's
  *prompt* are minted only at the next turn's generate phase. This split —
  passive bytes for eyes now, durable receipts at the next turn — resolves
  the "where do the receipts come from" question by never needing receipts
  outside a turn.

The one cost of this placement: an agent that never turns again accumulates
no history entries even as its page stays live. That is correct, not a gap —
context exists for turns.

### 2. Demonstration side-effects — embrace, with provenance; do not sandbox

The walkthrough (`my.run/walkthrough`) really does plant `largest`, a
contracted redefinition, and a `largest-usage` deftest in every agent's
namespace, and index parity (ruling 17) makes the contracted defn and test
real program facts. I considered the three candidate treatments:

- **Throwaway namespace**: rejected. The demonstration's whole point is "your
  namespace is empty — this function will be its first resident"; moving it
  to a scratch namespace teaches the wrong location and creates a second
  namespace convention (a mechanism bootstrap-only, violating ruling 26's
  "demonstrations are uniform").
- **Retraction at episode close**: rejected. It makes the history dishonest —
  the agent's remembered episode shows a defn whose fact no longer exists,
  so the first `dir` of its own namespace contradicts its own memory. That is
  precisely the class of silent lie the loud-failures ethos bans.
- **Embrace**: correct, with two supports that already exist. (a) Provenance
  is queryable, not inferred: the forms carry `:author :system` and the
  settlement transaction carries `:seon.db/process` tx-meta, so "is this a
  demo artifact" is a Datalog query today — no new fact needed. (b) The
  namespace is the agent's own, so N agents × one demo is namespace-scoped
  noise, not global pollution; facts are cheap and session curation
  (`:seon.cluster.run/supersedes`) is the eventual reclamation surface.

Two genuine defects to fix inside "embrace", both small: the demo's
`:seon.test/usage true` on `largest-usage` makes every agent's namespace
advertise a canonical usage demonstration for a function nobody asked for —
the demo test should be an ordinary test (the usage flag belongs on the
*system's* my.run walkthrough test, not replicated into every agent); and the
demo should not squat names an agent plausibly wants (`largest` is fine;
review demo vocabulary once, at the declaration).

### 3. Corrections under append-only — converges, given one discipline

Append-only correction is the REPL's own native idiom: re-running a form is
how every REPL user corrects a stale view, and "latest occurrence of the same
form wins" is knowledge the model already has from pretraining. The mechanism
converges because corrections here are not free-form apologies — they are
**evidence-gated re-executions of the same logical read at a newer basis**
(invalidation report §4). Same form text, newer value, later position; with
freshest-nearest-the-turn ordering (ruling 4) the correction structurally
outranks the stale entry. A fixed render function also invalidates correctly,
because code is facts and the retained call's static/code evidence changes.

The discipline that makes this true: **corrections must be re-observations,
never prose annotations.** A generated comment "; the earlier listing was
wrong" is an inference; a re-run listing at the new basis is a fact. Ruling 33
(comments render from tx-meta only) already implies this — state it as the
correction rule explicitly.

What does NOT converge is token mass, but that is unknown 8 / deferred
compaction, and the invalidation report already names the right shape: compact
by keeping the latest observation per logical call id and start a new prompt
generation with a new prefix. Nothing about append-only correction forces a
worse compaction later. Non-problem, one sentence of ruling needed.

### 4. Turn-budget semantics — forced `:wait` with a typed condition

More is landed than the question assumes: `episode-capped?` +
`deferred-triggers` (`src/seon/cluster/work.clj:540-554`) already make a
capped agent derive no work for agent-sent triggers while an outside trigger
resets the count. The undesigned edge is the agent's own experience at zero.
Recommendation, from the existing pieces:

- `turns-remaining` is already `(help)` data (ruling 30, commit `1559764d9`).
  The generated delta at every turn should re-observe it when it changed —
  the budget is then a countdown the agent watches, not a surprise.
- At zero with an undisposed run, the system force-settles the run as
  **`:wait`** with a flat typed condition (budget exhausted, the count, the
  resetting event) via the existing undisposed-at/honest-notice mechanism
  (16672698d). Never a silent stop (violates loud failures), never a forced
  `:completed` (fabricates a reply the agent didn't make), never
  `:interrupted` (nothing crashed). `:wait`'s own semantics — "the condition
  needed to continue" — describe budget exhaustion exactly, so no new
  disposition value is needed.
- The wait condition is data, so root's preview renders it and root can
  decide to message (= reset via outside trigger) or not. The budget thereby
  composes with ruling 36: resumption is a message, like all work.

### 5. Determinism — forms by derivation; values by storage

The precise statement worth pinning: **given (database value, program
generation, agent identity), the generated episode's ordered form SOURCES are
byte-identical.** Values are real execution results and are deterministic
only via storage: each executes once, its receipt is immutable, and every
replay prints the same receipt. So "same agent state ⇒ byte-identical
episode" decomposes into two different guarantees with two different
regressions:

1. **Form determinism** — pure: `ordered-episode` twice on one pull result
   yields identical `:seon.repl/key` sequences and identical
   `entry-source` bytes. The landed `::prefix-drift` check in
   `bootstrap/next-entry` is exactly this invariant enforced live; the
   regression is that check exercised deliberately (mutate an irrelevant
   fact, regenerate, assert no drift).
2. **Value stability** — by receipts: prompt N+1 begins byte-for-byte with
   prompt N (the append test the invalidation report already specifies).
   Never assert value bytes across re-execution — that would be asserting
   the database didn't move, which is not this design's claim.

One real defect this surfaces: printed values containing identity hashes
(worked example A's `#object[sci.lang.Namespace 0x1a2b3c4d ...]`) are
nondeterministic *inside* guarantee 2's own terms only if anything ever
re-prints rather than replays — but they are also ugly output (standing
order) and useless to the model. The printer should render such host objects
by their stable name, not their hex identity. Fix at the printer, once.

### 6. Survey-loop risk — the fix is demand-pull generation; this is my main divergence

The ablation evidence in the PRD already contains the answer: HALF (keeps the
worked demonstration, drops breadth) matched FULL; QUARTER/FLOOR (keep
breadth-ish discovery, drop the demonstration) never acted. **The
demonstration is the anti-survey device; discovery is only its supporting
cast.** Ruling 29's macroexpansion frame says the same thing structurally:
explanations are emitted *for the symbols the emitted forms contain* —
demand, flowing backward from action.

The landed generator inverts this. `direct-candidates` and
`listing-candidates` (`16f022fc9`, `src/seon/bootstrap.clj`) enumerate the
whole distance-3 pull and emit every candidate that becomes dependency-ready;
`ordered-episode` then orders that supply. Nothing bounds discovery to what
the arc needs; a richer neighborhood generates a longer survey prefix, and
the agent learns by imitation that surveying is what one does here. That is
the survey loop, built in.

The principled cap is therefore not a ratio or a count — it is the
**generation direction**: select the action arc first (the demonstration
entries, the task-message read, the disposition — all known before any
discovery is emitted), compute its explained-set closure under ruling 29, and
emit *only* that closure as the discovery prefix. A discovery entry with no
demanded symbol downstream is never generated. Then instrument, don't cap:
discovery:action entry counts per episode and injections-per-turn (ruling 11)
are the learning-curve gauges. The agent imitating the history then imitates
"look up exactly what you're about to use", which is the behavior we want.

### 7. Delta-form honesty — one mechanism, not N arities

Delta arities on every callable would be API growth and a smell. Neither is
needed, because the database already owns "since" and the call-preparation
seam already owns database supply:

- Every read that matters accepts (or is prepared with) the optional
  `:seon.db/database-value` argument (ruling 35 legitimizes exactly this).
  The honest delta form is therefore the SAME function supplied with
  Datahike's own `since` database — one mechanism, the dependency's
  vocabulary, zero new arities: the generated form spells the basis, and the
  basis comes from the retained entry the history already stores (ruling 32).
- Where a since-database alone cannot express the read honestly (joins
  needing current values filtered by newness), ruling 32's second clause
  already covers it: the generator emits the full listing's honest delta as
  **per-id reads of only the new ids** from the pull diff — plain calls,
  fully re-runnable, no API change at all.

So: no delta arities anywhere; `{:since basis}` sugar on a specific inbox-like
function is admissible only where it is genuinely that function's semantics,
and even then it should desugar to the one since-database mechanism. If the
implementation finds itself adding a since parameter to a third function,
that is the smell detector firing.

### 8. Explained-set cost — non-problem in principle; the current shape is the known-slow first cut

The explained set is a **monotone fold over an append-only sequence**. That
is the best possible cost structure: derived state that rides the value it
derives from (the ethos rule, verbatim). The fast implementation is to carry
`{frontier, explained, emitted-count}` as an accumulator alongside the
retained history and extend it per append — O(new entry) per generation,
never O(history). Nothing in the model prevents this; the sets only grow, and
append-only means no entry's contribution is ever un-earned.

The landed code is the dog-slow version three times over: `next-entry`
re-pulls the whole neighborhood, re-renders every candidate, and re-runs
`ordered-episode` from scratch **per generated form**, making the opening
episode O(n²) in pulls and renders. By the owner's stated standard this does
not indict the design — but it should be named as the restructure target:
one generation pass per wake emitting the whole ready suffix (which also cuts
transaction count), with the incremental fold, gets the walk-style 23s→7ms
arc. Where it would genuinely bite even after that: `form-symbols` over
retained forms is linear and trivial; the full-history `::prefix-drift`
audit is the one intentionally O(history) check, and it can move to a
checksum over the fold accumulator (compare one digest, keep the full replay
as the regression, not the hot path).

## What the rulings miss

1. **The mid-life generate phase is unruled.** Ruling 36 gates T1/T2 on this
   PRD cycle; the recommendation in unknown 1 (generation as the turn proc's
   pre-prompt phase, same `:generate` mechanics as the opening) should become
   a ruling so nobody builds it into the render proc or run-open transaction.
2. **The correction rule** (unknown 3): corrections are re-observations at a
   newer basis, never prose annotations. One sentence, kills a whole class of
   generated-comment editorializing.
3. **Demo artifact provenance and the usage-flag replication** (unknown 2):
   embrace is implied but unruled, and the per-agent `:seon.test/usage`
   stamping is an unintended consequence nobody has ruled on.
4. **Generation direction** (unknown 6): ruling 29 states the fixed point but
   not the direction; the landed code chose supply-push. Rule demand-pull
   explicitly.
5. **Printer identity-hashes** (unknown 5): host objects printed with hex
   identities are both nondeterministic and useless; the printer should own a
   stable rendering. Small, but it blocks the byte-level regressions.
6. **Zero-budget disposition** (unknown 4): forced `:wait` with a typed
   condition; currently only the derivation side (deferred triggers) exists.

## Recommended shape — simplest, powerful, fast

One paragraph, the whole system as I would build it:

An agent's context is one append-only history. Two writers exist and both are
the turn proc: (1) at every turn, before prompt acquisition, a **generate
phase** folds gap-closure forms — chosen demand-first: the action arc (task
read, demonstration on first episode, disposition) fixes the demanded symbol
set, ruling 29's closure emits exactly the discovery that set requires, and
ruling 32's delta rule (since-database or per-new-id reads, one mechanism)
closes the time gap — each form appended `:author :system` and executed
through the ordinary run fold with real receipts; (2) the model's own reply
forms. The render proc stays passive and read-only: it re-renders retained
blocks on evidence-gated wakes for the page, and its output never becomes a
prompt entry except through the next turn's generate phase. Generation state
(frontier, explained set, shown bases) is a monotone fold carried with the
retained history, extended per append, digest-checked against the receipts.
Corrections are re-runs at a newer basis; freshest-nearest ordering makes
them win. Budget exhaustion force-settles `:wait` with a typed condition;
resumption is a message. Determinism is pinned as form-byte determinism per
(db, program, agent) plus prefix stability by receipts — never value-byte
determinism across execution. Compaction, when evidence forces it, keeps the
latest observation per logical call id in a new prompt generation.

Nothing in that paragraph needs a mechanism Seon does not already have.

## Top divergences from the current design/implementation

1. **Demand-pull generation** — invert `direct-candidates`/
   `listing-candidates` from "emit everything ready in the pull" to "emit the
   action arc's explained-set closure only". This is the survey-loop fix, the
   lean-context fix, and a cost fix at once.
2. **One generation pass per wake, incremental fold** — replace per-form
   full re-derivation (`next-entry`'s re-pull + full `ordered-episode` per
   form) with one pass emitting the ready suffix, carrying
   frontier/explained/bases as an accumulator on the retained history;
   `::prefix-drift` becomes a digest comparison on the hot path.
3. **Embrace demo artifacts, but strip the replicated usage flag** — demo
   facts are real work with queryable provenance (`:author :system` +
   tx-meta), never retracted, never sandboxed; the demo's deftest must not
   carry `:seon.test/usage true` into every agent's namespace.

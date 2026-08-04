---
type: prd
status: active
tags: [prd, agent, context, sci, runtime, curation]
---

# Session curation — PRD (2026-08-04)

Errors in a session's rendered history breed errors: a non-thinking model
pattern-matches on what is in front of it, so a transcript full of failed
probes and refusals degrades every later turn while inflating token cost.
Session curation repairs this continuously: when a run closes with eval
errors, an editor agent proposes a corrected ordered vector of form
sources, the system re-executes that vector on a branch forked at the
run's opening basis, and — only if the replay is clean and reaches the
equivalent result — commits the curated run as facts that supersede the
original in the rendered transcript. The history the agent reads next is
shorter, clean, and TRUE, because it happened: curation is compaction by
re-derivation, never by summary, and the original run remains queryable
forensics on the same append-only branch.

This also serves the bootstrap program directly: curated winning sessions
are pre-pruned mining material for bootstrap candidates, and inline
curation is Arm C of the bootstrap-drive experiment
([bootstrap-vector-design-2026-08-01.md](bootstrap-vector-design-2026-08-01.md)).

## Evidence base

Eight independent lanes — four problems, each investigated by one sol and
one Opus lane that never read each other's output:

| Problem | sol | Opus |
|---|---|---|
| Namespace semantics | [report](../research/session-curation-namespace-semantics-2026-08-04.md) | [report](../research/session-curation-namespace-semantics-opus-2026-08-04.md) |
| Effect visibility | [report](../research/session-curation-effect-visibility-2026-08-04.md) | [report](../research/session-curation-effect-visibility-opus-2026-08-04.md) |
| Replay mechanics | [report](../research/session-curation-replay-mechanics-2026-08-04.md) | [report](../research/session-curation-replay-mechanics-opus-2026-08-04.md) |
| Transcript supersession | [report](../research/session-curation-transcript-supersession-2026-08-04.md) | [report](../research/session-curation-transcript-supersession-opus-2026-08-04.md) |

Where the pairs converge the design below treats the question as settled;
every divergence is listed in §8. All claims cited here carry file:line
or live-probe evidence in the linked reports.

## 1. Settled by convergent evidence

**S1 — Adoption is projection-level supersession, never branch-head
adoption.** Both replay lanes independently recommend (A) with the same
decisive reason: Datahike `merge!` computes nothing, so moving the
branch head requires `force-branch!` — documented `git reset --hard` —
which discards every fact other agents committed on the shared branch
after the fork point, including delivered messages. Supersession is one
append-only transaction (proven live: 34 datoms, closed run, invisible
to `next-agent-work`). The cluster branch stays append-only; git remains
the archive metaphor (curation is a rebase whose reflog is the database
itself).

**S2 — One `:seon.cluster.run/supersedes` ref plus ONE derived
active-runs rule.** Both transcript lanes independently designed the
same seam: four queries currently decide receipt visibility, and the
existing bootstrap exclusion is already duplicated inconsistently across
them; a fifth hand-placed filter would make the elision marker lie
("elided by budget" vs "replaced"). The rule is derived once and joined
by all four queries (`not-join` gives supersession chains free), with
the bootstrap exclusion re-expressed through it. Messages remain a
separate compaction boundary (sol). Supersession and token-bound
compaction are adjacent, not identical — different rules (membership vs
fit), one shared regeneration boundary (both lanes).

**S3 — The replay engine already exists.** Probed end to end by the
Opus replay lane and confirmed by sol: `registry/branch!` forks at an
ancestor commit in ~24-38 ms; an independent `sci.eval/cluster-ctx`
builds over the fork (~1.9 s); `seon.cluster.loop/turn` with a
fork-scoped cluster record executes a caller-provided plan — correct
values, closed run, zero error receipts, no model call. `seed-tx`
generalizes to existing agents (probed: 19 datoms, `:resume` derived at
ordinal 0). The build is one extracted `system-run-tx` with `seed-tx`
as its first caller — extraction, not construction.

**S4 — Acceptance judges declared content, never datoms or strings.**
Identical redeclaration is idempotent by identity but churns 69 datoms;
`result-edn` strings hide print faces. The acceptance predicate for a
curated replay composes existing grading seams
([grader-in-fact-space-2026-08-01.md](grader-in-fact-space-2026-08-01.md)):
zero `:seon.cluster.eval/error` receipts, `terminal-state` +
`completed-result` equivalence via `read-result`, and declaration
equivalence over declared content. Blobs cross branches byte-identical
through the shared physical store (probed), so curated receipts reuse
the fork's result-blob digests; only small fact rows are re-transacted
onto the agent's branch, through `plan-tx` so `form-sources`' run+ordinal
join holds, under a DISTINCT curated run id (receipt ids derive from the
run id — the distinct id is the collision mechanism, not a convention).

**S5 — Pinning derives from observed effects; prediction is advisory
only.** The capability door already records per-form provenance — the
request identity IS `[run-id form-ordinal effect-ordinal]` with owner
and handler refs — so "which forms in run R crossed the door" is one
join today. Message delivery is traceable through the form's terminal
transaction. Static reachability cannot yet be trusted: the form-Var
walk fails open on host-bound capability Vars (fix in flight, §6), and
agent-authored functions install no `:seon.fn/calls` edges, so
reachability is vacuous exactly where curation needs it. Therefore v1
pins by RECEIPTS (what actually happened), and fails closed on the one
channel with no causation fact: a run containing an agent-issued bare
`seon.db/transact!` is not curated until write provenance exists (§3).

**S6 — Agent id and namespace are already decoupled; occupancy is not
enforced and mostly should not be.** Assignment
(`:seon.cluster.agent/namespace`) is `:db.unique/value`; identity is the
id. Qualified foreign `defn` is refused by Clojure semantics; `in-ns`,
`intern`, `alter-var-root` reach any namespace cluster-wide. The one
durable admission seam is `receipt-settle-call` → `program-row-tx` —
where an ownership refusal would be ~5 lines, and where BOTH lanes
locate any future check; the Opus lane recommends against building the
refusal at all (no evidence of a real problem — the no-hobbling ruling;
`work/form-owner` routing and objective O4 depend on cross-namespace
reach; the editor itself is a non-owner by definition). The accretive
alternative both designs support: record `:seon.fn/author` at that seam,
refusing nothing. Owner question Q2.

## 2. The curation loop (v1 shape)

1. **Trigger** — run closes with ≥1 eval-error receipt AND no unpinnable
   barrier (§3 fail-closed list). Run boundary only in v1; no mid-run
   curation.
2. **Editor** — an ordinary agent; its context is the messy run
   rendered plainly; its deliverable is DATA: an ordered vector of form
   sources via `my.run/complete`. Pinned effectful forms are presented
   as fixed points that must appear unchanged and in order. The editor
   keeps one instructive failed form when the failure taught something
   (the repeat-mistake guard); that is ordering in the curated sources,
   no marking mechanism needed.
3. **Replay** — fork at the run's opening commit; execute the curated
   vector as a system-authored run (`system-run-tx`) driven by the
   existing loop; no model call.
4. **Accept** — S4 predicate. Any failure: keep the original, commit the
   editor's report as data, done.
5. **Adopt** — commit curated receipts (reusing fork blob digests) onto
   the agent's branch under the curated run id with
   `:seon.cluster.run/supersedes` → original run; the derived
   active-runs rule does the rest. The agent's next run renders clean
   history; the original stays queryable.

The original agent experiences nothing (silent adoption); attribution as
a rendered lesson is deferred (owner question Q3).

## 3. Missing-fact ledger (each one attribute/ref at an existing seam)

Per the standing principle: the missing fact is the defect. All were
independently identified; none requires new mechanism.

| # | Missing fact | Seam | Consumer |
|---|---|---|---|
| F1 | `:seon.cluster.run/supersedes` ref + derived active-runs rule | run schema + transcript's four visibility queries | S2 adoption |
| F2 | run's opening COMMIT ID (only basis `t` recorded; recovery is an O(n) parent walk) | run open-tx | S3 fork point |
| F3 | starting namespace as a run fact ("run this vector as agent Y starting in X") | plan-tx / system-run-tx | replay fidelity |
| F4 | committed ending-ns per eval (`:seon.sci.eval/ending-ns` is derived then dropped; fold seeds nil and falls back to the reader's static track — order-dependent attribution, probed) | receipt settle | replay fidelity + forensics |
| F5 | `:seon.fn/author` on program rows (rows carry no author today) | `program-row-tx` | provenance, curation attribution |
| F6 | message → issuing-form ref (provenance lives only in a derived id STRING; absent for assignment messages though the delivery request already carries the ordinal) | message commit | S5 pinning |
| F7 | tx provenance on agent-issued `seon.db/transact!` (`:tx-meta` from the already-bound `effect/*context*`: run + form ordinal) | `seon.db/transact!` | lifts the S5 fail-closed barrier |
| F8 | normalized capability FAMILY on door receipts (owner/handler recorded; fs/web/llm/db family is a derivation away) | effect door | pinning policy by family |
| F9 | `:seon.fn/calls` edges for agent-authored defns (today only clj-kondo-indexed first-party source has edges; agent code is a graph leaf) | program-row install | advisory prediction, workload derivation |
| F10 | session-image rows bypass the `program-row-tx` choke point | session image | closes the "one admission seam" claim |
| F11 | test→function call edge (`:seon.test` rows carry only sym/ns/source) | test indexing | Q6 quality gate; definition-time accretion testing |

## 4. Namespace decisions folded in

- `agent-namespace` string-builds `my.agents.<id>` instead of reading
  the assigned-namespace fact (a naming convention standing in for a
  fact the database holds; overlaps the open
  `evals-ignore-the-agents-assigned-namespace` issue). Fix by reading
  the assignment (its inverse `owner-of` already exists).
- F3/F4 above make "which namespace did this form run in" durable — the
  precondition for a curated replay to claim it reproduced the session.
- Vocabulary correction landed with this PRD: the pinned SCI's
  generation-aware fork makes forked Vars copy-on-write, so `sci/fork`
  IS admissible for candidate contexts (verified against
  `reference-code/sci/src/sci/core.cljc:337` + live probe; supersedes
  the 2026-08-02 leak probe, which predated the pin). AGENTS.md row
  updated.

## 5. Experiment integration

Arm C of the bootstrap drive: help-only seed + inline curation.
Measures: rescue rate (sessions that would have failed), token delta
(curated context size), and mining quality (curated winners vs raw
winners as bootstrap candidates — curated transcripts are pre-pruned by
construction). Blocked on the transcript-ordering fix (§6) like Arms
A/B; NOT blocked on F6-F9 because generation-zero objectives are
in-branch only (fail-closed barriers never trigger).

## 6. In-flight fixes this PRD depends on or produced

- **Transcript same-instant ordering** (BLOCKER for every arm): receipts
  settling in the same millisecond render in lexical receipt-id order —
  the live bootstrap renders scrambled. Issue:
  [transcript-orders-same-instant-receipts-lexically.md](../../../seon/issues/transcript-orders-same-instant-receipts-lexically.md).
  Fix lane running (numeric ordinal tie-break + regression).
- **Capability walk fails open** on host-bound capability Vars
  (`capability-free-references?` true for `(my.fs/read …)`) — weakens an
  existing admissibility guarantee beyond curation. Issue:
  [capability-reachability-cannot-see-capability-calls.md](../../../seon/issues/capability-reachability-cannot-see-capability-calls.md).
  Fix lane running (fail-closed classification + regression).
- Filed by lanes, not blocking:
  [program-row-replacement-churns-identical-redeclarations.md](../../../seon/issues/program-row-replacement-churns-identical-redeclarations.md),
  [forked-cluster-inherits-the-ancestors-config-cluster-name.md](../../../seon/issues/forked-cluster-inherits-the-ancestors-config-cluster-name.md),
  [transcript-orders-same-instant-receipts-lexically.md](../../../seon/issues/transcript-orders-same-instant-receipts-lexically.md).

## 7. Implementation order

1. **W1 — facts: DONE (2026-08-04).** F1 + F2 + F3 + F4 are declared
   and recorded at their existing seams. One derived active-runs rule
   owns bootstrap pinning and supersession for all four transcript
   visibility queries. Gate: the explicit transcript, run, and loop
   namespaces pass 47 tests / 311 assertions, including chained
   supersession before token accounting, opening commit + starting
   namespace facts, and committed ending namespace across a resumed
   fold. Non-superseded transcript histories remain green.
2. **W2 — engine:** extract `system-run-tx` (seed-tx first caller);
   fork-at-opening-commit + replay + S4 acceptance as one owner
   (`seon.cluster.curate` or per owner ruling). Gate: replay a seeded
   messy run, adopt, render clean; crash mid-curation leaves the
   original untouched (adoption is one transaction).
3. **W3 — editor + trigger:** run-boundary trigger, editor agent
   spec, pinned-form presentation, fail-closed barrier check (door
   receipts + message trace + bare-transact detection). Gate: Arm C
   runs end to end on generation-zero objectives.
4. **W4 — provenance lifts:** F5-F8 as independent slices, each lifting
   one fail-closed barrier or enriching pinning; F9/F10 with their own
   owners.

## 8. sol/Opus divergences (the pairing's yield)

- **Ownership refusal:** sol frames it as admissible at the choke point;
  Opus recommends against building it (no-hobbling). → owner Q2.
- **Message traceability:** sol "traceable through the terminal
  transaction" vs Opus "provenance only inside the derived id string" —
  both true; the tx-join is honest but indirect, hence F6.
- **`sci/fork` isolation:** sol falsified a recorded repo claim; Opus
  did not test it. Independently verified at source and adopted (§4).
- **Everything else converged**, including both lanes independently
  choosing (A), the same supersedes-ref design, the same choke point,
  and the same fail-closed transact stance — strong calibration signal
  for single-lane research on adjacent questions.

## 9. Owner rulings (2026-08-04 conversational session; recorded in
[plan/README.md](README.md) "Rulings 2026-08-04")

- **Q1 RULED: adopted**, extended by the per-run fork-context ruling:
  each run evaluates in a fresh `sci/fork` of the cluster's base ctx;
  cross-agent propagation is through the durable graph (admission →
  fact → acquisition), never the live ctx. The curation loop below is
  sealed; W1 starts after the in-flight fix lanes land.
- **Q2 RULED: gate durable placement, and record the author.** Owner:
  "we can gate where they can define things." Both land at the ONE
  admission seam (`program-row-tx`): `:seon.fn/author` recorded on
  every program row, and durable definition placement checked against
  namespace assignment. Live-tier definitions need no gate — they are
  fork-private and evaporate, which is what makes the placement gate
  consistent with the no-hobbling ruling (nothing an agent does in its
  own fork is restricted; only the shared durable graph has admission,
  exactly like schema registration). Session-image rows (F10) must
  route through the same seam before the gate is meaningful.
- **Q3 RULED: silent adoption.** The verified curated run REPLACES the
  messy span as the agent's history ("the new and better Agent A");
  the agent is parked between runs and never observes the swap. The
  editor's scratch branch/fork and every losing future are deleted
  (optionally mined first for bootstrap candidates).
- **Q3a (sharpening, same session): the editor's branch is never the
  verification branch.** The editor's own session may be arbitrarily
  messy; its deliverable is data. Acceptance judges only the fresh
  mechanical replay at the original opening basis — which also proves
  the vector is self-contained (a vector depending on state the editor
  built up mid-session fails the clean replay and is not adopted).
- **Q3b (sharpening, same session): destructive means WRITES, not
  door-crossings.** Database writes are branch-immutable and always
  curable. READ effects (web reads, fs reads) are fine ground: the
  replay re-executes them, and the acceptance equivalence gate catches
  material divergence. WRITES that left the branch (delivered messages,
  real file edits, web writes, external state) make a span destructive:
  the derived trigger fails closed from receipts, and the editor
  additionally REJECTS with a reason when it judges the task
  destructive — the honest fallback for what receipts cannot see.
  Named missing fact: per-request read/write classification on door
  receipts, DECLARED by the capability leaf (joins F8). Mechanism
  (owner, same session): the workload-classification pattern applied a
  second time — defn METADATA on the few capability leaves (as
  `^{:seon.workload :io}` already is, lifted to a `:seon.fn/*` fact at
  index time), e.g. a mutation marker distinguishing `my.fs/read` from
  `my.edit`/web writes; chains DERIVE by reachability over
  `:seon.fn/calls`; never annotate everything, never a hand list. A
  span is destructive when its receipts (or, advisorily, its call
  graph) reach a mutation-marked leaf — reads never trip it.
- **Q4 (still open, recommend: defer)** — mid-run curation and
  message-boundary compaction stay out until run-boundary evidence
  exists.
- **Q5 RULED: adoption is single-future; there is no merge.** The
  editor forks the agent's context at the error; the proof runs at the
  span's opening basis; one clean proof adopts and supersedes; every
  other branch and fork is deleted. N candidate editors ⇒ one adopted
  winner, losers discarded whole (optionally mined), never merged.
- **Q6 RULED: shared-graph propagation is the quality gate.** A shared
  defn/schema/test merges to main when its DEPENDENTS' tests pass
  against the candidate; other agents acquire from main at their next
  run boundary. Enabling fact: the test→function edge (F11).

## 10. Ugly-output roll-up (standing order)

Reported across the eight lanes, for the render-quality loop: ~2 MB
transaction reports in agent-facing results (`:db-before`/`:db-after`
serialized whole — two lanes hit it independently); ~3 MB agent-creation
results; `config/effective` returning `{}` yielding a 5 KB missing-key
wall (issue filed by the Opus replay lane); `:seon.db/rejected` faces
showing bare entity ids (an agent cannot see WHICH agent owns a
namespace); every jvm-mode exception reporting the io-prepl serving
frame instead of the throw site; a leaked host NPE ("fut is null");
`defn` returning a string while `def` returns a var face (REPL parity);
error receipts without triage rendering as naked prose; the inert
`:summary` detail tier; unscoped ~19k-token `runtime_status` JSON; no
public render-unit constructor (seven required keys discovered through
five successive contract violations).

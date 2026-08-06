---
type: research
status: complete
tags: [bootstrap, agents, experiment, context, sci]
---

# Bootstrap redesign groundwork — 2026-08-05

## Purpose and boundary

This is groundwork for an owner design session after the grader-repair wave
produces trustworthy O1–O5 data. It does not choose a bootstrap, alter the
shipped vector, or propose implementation work.

The current evidence supports three statements and no stronger ones:

1. the input-ref discovery exemplar transferred strongly on O2;
2. the whole shipped vector underperformed help-only on one O1 matrix, but
   that inversion is unreplicated and does not identify a harmful form; and
3. the old O4 and O5 scores cannot select bootstrap content because O4 graded
   one run instead of the causal episode and O5 targeted a deleted refusal.

The shipped resource has **13 executable plan forms**, not 14. The historical
14-entry description counts a separate banner plus those 13 forms. The banner
does not exist in the current resource or resolver. This report retains the
14-slot design numbering only so old discussions remain traceable.

## Grounding and evidence limits

I read the following end to end before writing this report:

- the repository [AGENTS.md](../../../../AGENTS.md) and the localized
  [SCI execution-runtime runbook](../AGENTS.md);
- the [data-oriented Clojure skill](../../../../.agents/skills/data-oriented-clojure/SKILL.md);
- [bootstrap baseline](bootstrap-baseline-2026-08-04.md);
- [bootstrap vector design](../plan/bootstrap-vector-design-2026-08-01.md);
- [bootstrap concept graph](../plan/bootstrap-concept-graph-2026-08-04.md);
- [state of the program](../plan/state-of-the-program-2026-08-05.md), including
  P9 and R6;
- [agent desk and checkout PRD](../plan/agent-desk-and-checkout-prd-2026-08-05.md),
  including sections 5–6; and
- [grader-repair wave](../plan/grader-wave-2026-08-05.md).

I also read the complete current
[`resources/seon/bootstrap.edn`](../../../../resources/seon/bootstrap.edn),
[`src/seon/bootstrap.clj`](../../../../src/seon/bootstrap.clj), and the
bootstrap correctness tests in
[`test/seon/bootstrap_test.clj`](../../../../test/seon/bootstrap_test.clj).

The baseline report's raw reports and database workspaces were under a
gitignored `tmp/bootstrap-drives-rerun-20260805T000202Z/` root. That root is no
longer present; the program record says the frozen `tmp/` evidence was deleted.
Therefore the form-by-form transcript column below is limited to the committed
aggregate table, the embedded O4 pair, and the committed Arm-B winner extracts.
It does not pretend to independently re-mine the missing Arm-A transcripts.

## Dependency ledger

| Dependency or mechanism | Selected revision | Grounding used here |
|---|---|---|
| Seon source | `700b0f65b90e2c4d60c64433e2f6996f1a24f062` | The resource forms; `seon.bootstrap/packaged-forms`, `ordered-sources`, `agent-sources`, `plan-digest`, and `seed-tx`; bootstrap tests |
| Datahike | `56f1c62105b7087f0cac13162f9fd54b1690986e` | `reference-code/datahike`; `seon.db/q` and `pull` are the bootstrap's fact-resolution substrate |
| SCI | `2db3358cba913b6fbbe49c7b5b34d7ac72715924` | `reference-code/sci`; forms execute in SCI, and the desk PRD's per-turn fork result was probed against this pin |
| Malli | `0.20.0`; vendored source `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli`; complete durable-function contracts and the `:any` refusal are teaching subjects |
| Token estimator | Seon source revision above | `seon.ai.tokens/estimate` is exactly integer-floored `chars / 4`; no tokenizer dependency |
| Experiment harness | Baseline launch `dbef794ab`; current repair spec at source revision above | The old matrix supplies hypotheses only; the queued repair wave supplies the next trustworthy comparison |

The current resolver is simple and important to preserve conceptually. It
reads and validates one classpath vector, installs ordinal facts and one digest,
queries the cluster's held plan in ordinal order, substitutes the agent
namespace token, assigns each form to the agent or `user` namespace, and freezes
those sources into an ordinary system-authored run. Form content can change
without a new executor. The current resolver is nevertheless cluster-plan-only;
the ruled generic cluster/agent initial-forms resolver with most-specific-wins
is a later owner, not something this report assumes already exists.

## The current vector, form by form

### Token accounting

All “authored tokens” below were recomputed from the current resource with
`seon.ai.tokens/estimate`. They are invariant source or static-help costs, not
provider-reported usage. The 13 source strings total **245 estimated tokens**.
The help prose adds **405**, for **650 invariant authored tokens**. Actual
transcript cost is larger because each form's printed output and returned value
also render; those results depend on the database, namespace, docstrings, and
error renderer. The 2026-08-01 live design measured the then-current complete
transcript at approximately 990 tokens, but that historical total is not an
exact current total.

| Design slot | Resource ordinal | Form | What it is intended to teach | Current authored tokens | What the recorded transcripts/results actually support |
|---:|---:|---|---|---:|---|
| 0 | — | Separate `Seon REPL …` banner | Minimal place and identity | 0 current; historically ~10 | The banner is absent from the current resource and resolver. No current transfer claim is available. |
| 1 | 0 | `(help)` plus prose | Reply-as-forms, batching, REPL display, graph discovery, durable contracts, messaging, and run dispositions | 1 source + 405 prose | Help-only agents completed O1 10/10. Every embedded O1 winner authored, ran, and completed a contracted function in three receipts. Help-only O3 winners independently wrote Datalog. The embedded O4 main agent used `my.message/send` and `my.run/wait`. This proves help is sufficient for those observed acts, not that every sentence transferred. |
| 2 | 1 | `(in-ns '{{seon.ns/name}})` | The assigned namespace, by entering it | 6 | It put the bootstrap in the intended namespace. Its visible result was an ugly `sci.lang.Namespace` object face. No objective isolates a downstream benefit. |
| 3 | 2 | `(dir my.run)` | Namespace discovery and the available dispositions | 3 | Agents used `complete` and `wait`, but help names both and help-only agents also used them. The matrix cannot attribute that behavior to `dir`. |
| 4 | 3 | `(doc my.run/complete)` | Graph-backed function documentation and completion semantics | 5 | Agents completed runs, but help-only O1 did so more often. No predicate measures doc use. Historical output was one of the largest forms; current output size remains docstring-dependent. |
| 5 | 4 | `(dir my.message)` | Peer messaging is discoverable | 4 | The embedded help-only O4 main agent sent a message without this form. Old O4 grades are invalid, so there is no measured arm effect for this lesson. |
| 6 | 5 | Count all `:seon.fn/sym` rows | The program graph is real and large; a scalar query stays small | 15 | O3 asks a related count, yet shipped scored 2/10 and help-only 3/10. The generic count did not show positive transfer to the namespace-public-function query. |
| 7 | 6 | Query functions whose input refs include `:my.run/result` | Discover a callable function by declared data shape | 37 | This is the strongest positive datum: shipped O2 passed P2a/P2b 10/10; help-only passed 0/10. The intervention is still the whole 12-form difference, but this form directly matches P2a and is the leading causal explanation. |
| 8 | 7 | Invalid `largest` contract using `:any` | The surviving undefined-contract refusal face | 50 | The form produced the intended refusal, but its header was ugly. Old O5 graded the deleted open-map refusal, so there is no valid transfer score for this beat yet. |
| 9 | 8 | Concrete repaired `largest` contract | Replace `:any`, make absence explicit with optional output keys, and persist the function | 75 | Shipped O1 was 8/10 while help-only was 10/10; help-only winners already wrote concrete contracts. This does not prove the repair is harmful, but it proves the worked function is not required for O1 competence. O5 must measure whether the repair sequence transfers. |
| 10 | 9 | Call `largest` on two rows | Run what was authored and inspect a happy-path result | 14 | Every embedded help-only O1 winner also ran its own function. No predicate isolates transfer from this demonstration. |
| 11 | 10 | `(largest)` | Arity failure as an ordinary REPL event | 2 | No objective measures recovery from this event. It rendered a noisy nested lint-rejection map, so its demonstrated attention cost is known while its benefit is not. |
| 12 | 11 | `(largest [])` | Probe an empty-input edge case | 3 | No objective measures edge-case probing. The help-only O1 winners ran the supplied case but the committed extract does not show a separate edge-case probe. |
| 13 | 12 | Query `largest`'s stored `:seon.fn/spec` | A contracted definition became a queryable program fact | 30 | O1 P1a established durable function rows in 8/10 shipped and 10/10 help-only attempts. The query demonstrates the fact, but the matrix shows it is not necessary for agents to create one. No predicate isolates later persistence-query reuse. |

### What the vector is demonstrably buying

- **Keep as a live hypothesis:** the input-ref discovery query. O2 supplies a
  10/10 versus 0/10 transfer result that its repaired rerun can confirm.
- **Competence already present:** contracted function authorship, invocation,
  and completion. Help-only O1 was perfect in the first matrix.
- **No demonstrated benefit yet:** namespace entry, `dir my.run`,
  `doc my.run/complete`, `dir my.message`, the generic function count, happy
  call, arity failure, empty-input probe, and persistence query.
- **Not yet measured honestly:** the `:any` refusal → concrete repair beat,
  because O5's old predicate was stale.
- **Not measured at all by the old score:** multi-agent continuation, because
  O4 stopped observation when `wait` correctly closed the initiating run.

This is not a deletion recommendation. It is the smallest honest partition of
what the matrix did and did not measure.

## The O1-inversion hypothesis

### Falsifiable statement

For `deepseek-v4-flash` with thinking disabled on O1, the 12 forms after
`(help)` reduce success relative to `(help)` alone because they spend attention
or anchor the model on examples for a task it already knows how to perform.

The intervention named by that hypothesis is **the whole additional vector**.
The current result does not license “teaching hurts,” “Malli examples hurt,” or
“the `largest` example hurts.” Those require form-level ablations.

### Replication interpretation fixed before seeing the result

- **Directional confirmation:** help-only beats shipped again in the dedicated
  20-run replication and the same direction appears in the repaired full
  matrix. A repeated gap of at least the original two wins per ten would make
  the attention/anchoring explanation materially stronger, though still not
  identify the culprit.
- **Directional refutation:** shipped beats help-only in both new comparisons.
  The original 8/10 versus 10/10 should then be treated as run/provider
  variation, not a bootstrap property.
- **Inconclusive:** ties, a one-attempt wobble, or opposite directions between
  the dedicated replication and matrix. With small cells, that means “no
  demonstrated O1 benefit” rather than “demonstrated harm.”

### What each outcome implies

| Replication outcome | Implication for the vector |
|---|---|
| Confirms | Remove already-known Clojure workflow from the default candidate and retain only Seon-specific, independently measured lessons. Run drop-one or add-one ablations before blaming `largest`, arity, or any other individual form. |
| Refutes | Do not optimize for the old inversion. Judge the fuller vector on repaired O2–O5 transfer and attention cost; generic demonstrations still need positive evidence to earn permanent space. |
| Inconclusive | Prefer the simpler vector on O1 because extra teaching has shown no benefit there, but let repaired O2 and O5 preserve specific forms that demonstrate transfer. Increase repetitions or use paired seeds before making a causal deletion. |

The strongest prior is therefore deliberately weak: the model likely knows
ordinary Clojure function work; it likely does not know Seon's queryable
program graph, admission refusals, desk/shared split, database branch semantics,
or repair ownership.

## Ruled material absent from the current teaching

The estimates in this section price compact candidate wording, not settled
bootstrap copy. Static prose uses `chars / 4`; dynamic form output is a range
until its owner and renderer exist.

| Material | Current status | Minimal teaching shape | Estimated marginal cost | Feasibility and measurement note |
|---|---|---|---:|---|
| Two-world desk contract | Ruled; W-A is landed; current help only distinguishes contracted from uncontracted `defn` and ambiguously says both “what you define here stays” and “your next run starts fresh” | Add two compact help sentences mapping the desk to a working tree and durable work to shared facts/functions; once W-C exists, execute `(my.branch/status)` to show desk versus committed | 0 new forms + ~50 prose tokens; optional status form is 1 form + 4 source tokens + bounded result | The wording must preserve the real nuance: desk facts restore across turns/JVM bounce, remain agent-scoped and non-shared, and disappear only on explicit session/agent clear or reset. “Temporary” must not be taught as “lost next turn.” |
| Fast-forward-only git framing | Ruled; absent | One short help paragraph: database history has no merge, index, remotes, or conflicts; refusal leads to revision/proof; real disk git remains `my.shell` + git | 0 forms + ~38 prose tokens | This deliberately spends sentences on a strong prior instead of teaching the full ten-row analogy table. Whether `rebase -i` belongs in the minimal paragraph is an owner wording decision. |
| `my.branch` verbs | Ruled namespace and verbs; W-C is not landed in current source | After W-C, `(dir my.branch)` plus compact prose separating database branches from disk git; `doc` remains on-demand | 1 form + 3 source tokens + estimated 25–80 result tokens; optional prose ~43 | It cannot honestly ship before W-C exists. One `dir` should expose the live surface without freezing a hand list; the actual output must be measured after docstrings land. The PRD lists checkout/log/diff/status; root vocabulary also names fork, so source truth must settle the final live set before teaching. |
| Errors-I-own repair priority | Ruled; no such query is in the current bootstrap | One bounded query form followed by one compact rule: repair assigned errors first; success is their disappearance on re-query; no acknowledgement | 1 form; source provisionally 40–100 tokens + ~39 prose; result must be profile-bounded | Exact cost is not yet honestly priceable because the owning agent-scoped query and its bounded output face are not settled in the current bootstrap. The existing error facts carry agent refs, but the bootstrap must use the final query owner, not embed a copied Datalog policy. An unbounded error result would be worse than no lesson. |
| `:any` refusal → repair | Already present as resource ordinals 7–8; **measured incorrectly**, not untaught | Keep the two executed forms only if repaired O5 shows transfer; otherwise teach the rule compactly and leave the refusal discoverable on demand | 2 forms, 125 current source tokens; dynamic error/result output extra; historical refusal-repair footprint was ~217 | O5 must first target `:seon.schema/undefined-contract`. The current refusal's cryptic header and the repair's 75-token source make this the largest obvious ablation candidate if transfer remains absent. |

The four genuinely absent lessons add roughly **127 static prose tokens** in
their compact sentence forms before `my.branch` prose, plus one dynamic errors
query and, after W-C, one three-token `dir` source. This is small in context
window terms and potentially large in attention terms; the matrix, not the
window size, must decide.

## The tabled concept graph (R6)

### Prior idea, summarized

The prior discussion correctly reframed teaching as a **bipartite graph**, not
a concept-to-concept route:

- concept rows name teachable facts and per-model priors;
- form rows declare concepts they teach and prerequisites they require;
- profile tags choose target concepts without a hand-maintained roster;
- a walk selects an ordered, prerequisite-valid, low-cost set of forms;
- repeated touches have diminishing value weighted by need; and
- grader predicates update per-model prior evidence.

The proposed optimization problem is weighted set cover with precedence. At
this scale its computational complexity is irrelevant. The valuable part is
queryable coverage: every target concept is taught before it is required,
every help sentence accounts for a concept, and every measured predicate can
point back to the lesson it evaluates.

### Honest feasibility read

**Feasible now as representation and audit.** Annotating the existing forms
with coarse concepts and prerequisite edges would make coverage, orphaned
prose, and redundancy queryable. It fits the database-first model and can sit
upstream of the existing ordered-source/digest/run mechanism. No runtime
router is needed for that value.

**Premature now as optimizer.** The evidence contains one strong concept-level
signal (O2), one whole-vector anomaly (O1), one weak/no-signal objective (O3),
and two invalid old objectives (O4/O5). Per-model numeric priors, concept
weights, decay, and an admissibility threshold would mostly encode author
opinion with decimal precision. The first useful slice remains annotations +
coverage query; even profiles should wait until there are genuinely different
target populations.

**The cost model needs repair before optimization.** Static source and help
cost are known at plan time. Dynamic `dir`, `doc`, query, and error results are
known only after execution against a database and renderer. “Token cost over
rendered bytes at walk time” is not directly available to the current
population-time resolver. A practical graph must distinguish static authored
cost from a measured or bounded result-cost distribution; otherwise it will
prefer a three-token form whose output is hundreds of tokens.

**The resolver prerequisite is real.** The concept graph can emit today's
cluster vector, but per-agent/per-model/per-profile selection should use the
ruled generic initial-forms resolver with most-specific-wins. Building a
parallel bootstrap selector on top of `packaged-forms` would violate the one
mechanism rule.

**Calibration needs causal ablations.** A whole-vector A/B result cannot update
twelve independent concept priors honestly. O2 can update the find-by-shape
hypothesis; repaired O5 can update refusal-repair. The remaining concepts need
drop-one/add-one arms or objectives whose predicates are explicitly connected
to their form/concept facts.

**Recommendation for the owner session's option set, not a decision:** retain
the concept graph as a likely audit/data representation; do not authorize an
optimizer until the repaired matrix and at least one concept-level ablation
show that automatic selection has evidence to operate on.

## Candidate bootstrap shapes for the post-grader session

Prediction bands below are deliberately coarse: **high** means an expected
8–10 winners per ten, **medium** 4–7, **low** 0–3. They are priors for selecting
the next experiment, not findings. O4 predictions have the lowest confidence
until the causal-episode grader runs; O5 predictions are conditional on the
repaired `:any` predicate.

| Candidate shape | Contents and trade-off | O1 | O2 | O3 | O4 | O5 |
|---|---|---:|---:|---:|---:|---:|
| A. Minimal help + strong priors | One revised help form carrying only loop-external facts, the desk/shared split, ff-only framing, messaging/lifecycle, and errors-first policy. Drop all worked Clojure, query, arity, and refusal forms. Cheapest and cleanest; assumes ordinary function work, Datalog adaptation, and refusal repair are discoverable. | High | Low | Low | Medium | Low |
| B. Task-forced discovery | Revised minimal help, then one first-run orientation task that requires the agent to use `dir`/`doc`/`seon.db/q`, inspect `my.branch/status`, query owned errors, and repair one `:any` refusal without showing a full worked solution. It spends model work rather than a large pinned demonstration and produces receipts the grader can inspect. | High | Medium–high | Medium | Medium | Medium–high |
| C. Graduated by measured competence | Coarse concept facts and fixed per-model/profile vectors. High-prior Clojure forms are absent; the O2 query and O5 repair remain until untaught controls pass; desk/git/errors lessons enter as their owners land. A failed concept-specific check selects reinforcement for a later fresh attempt, never mutates the current run invisibly. | High | High | Medium | Medium–high | High |

### How the repaired matrix selects among them

- If O1 inversion confirms while O2 remains near 10/10 versus 0/10, reject
  both the current full vector and pure help-only as final shapes. Prefer B or
  C: remove generic competence teaching but preserve explicit Seon discovery.
- If O1 refutes and O2 remains strong, the current vector is not shown harmful,
  but each unmeasured form still has to earn its attention cost. Candidate C
  can begin as a static evidence table without an optimizer.
- If repaired O5 shows a large taught-arm advantage, preserve the two-form
  refusal beat or an equally effective task-forced version. If both arms are
  high, treat `:any` repair as a strong prior and compress it into help. If both
  remain low, the current beat is ineffective and needs a newly authored form,
  not more repetitions of the same one.
- If repaired O4 shows no arm difference, the current help prose is probably
  enough for messaging lifecycle and no extra `dir`/`doc` forms are justified.
  If only the taught arm succeeds, identify whether `dir my.message`, run docs,
  or another form transferred with a targeted ablation.
- If O3 remains low in every arm, the generic all-function count is not a
  teaching form for namespace-scoped graph queries. Candidate B should force
  the exact discovery operation; Candidate C should record that concept as
  low-prior until an untaught control succeeds.

Candidate B is the smallest experiment that differs semantically from the old
static wall. Candidate C has the best long-term fit with per-model evidence but
the highest risk of building machinery around noisy priors. Candidate A is the
essential control and may itself be the right production answer only if O2 and
O5 cease to need explicit teaching.

## Ugly output and evidence-quality findings

Bootstrap-visible ugliness already recorded in the baseline remains relevant
to redesign because output bytes consume attention:

- `(in-ns ...)` exposes a `#object[sci.lang.Namespace …]` implementation face;
- the `:any` beat begins with `Execution error () at (REPL:1).` before its
  useful repair explanation;
- `(largest)` prints a deeply nested multiline lint-rejection map;
- `(help)`, `dir`, and `doc` print useful content and then a separate `nil`;
- provider parse failures can leave only the objective visible to the agent,
  while the report carries HTTP 200, `:seon.ai/unparseable-body`, “closed,” and
  a large serialized error face; and
- the historical raw transcript/report root cited by the baseline is gone, so
  committed extracts are now the only durable form-level evidence.

One additional operator-visible ugly result appeared during this read-only
groundwork: `bin/seon status` printed “The managed root already has a different
creator” followed by a raw nested EDN map containing process identities. It did
not block this research, and no process, cluster, session, or source file was
changed in response.

## Questions the owner session can now answer with data

1. Did the O1 inversion replicate directionally, refute, or remain
   inconclusive?
2. Does repaired O5 show that an executed refusal-repair beat transfers?
3. Does repaired O4 show that help already teaches messaging and continuation,
   or does a specific discovery form matter?
4. Is O2's discovery advantage stable enough that the input-ref query is a
   default lesson rather than a development-profile lesson?
5. Which of A, B, or C should become the next measured candidate—not the final
   bootstrap—and which single ablation would distinguish it from the runner-up?

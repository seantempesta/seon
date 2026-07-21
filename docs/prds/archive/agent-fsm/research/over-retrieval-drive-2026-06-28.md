---
type: research
status: active
tags: [agent, gym, flow]
---

# Over-retrieval facet drive — x12, k=3, DeepSeek (2026-06-28)

## TL;DR

`x12-narrow-question-no-over-retrieval` (`:over-retrieval` competency) was
driven at **k=3** on the paid DeepSeek adapter in a hermetic gym (scratch
`:memory` conn, no live-pod touch). **The over-retrieval facet is HANDLED.**
The over-pull penalty predicate (`:b-did-not-pull-off-kind` — B queried the
unrelated book kind ZERO times) passed **3/3**, and every query B actually
wrote was a NARROW, targeted attribute-presence query scoped to exactly the
dentist contact (`[?e :my.contact/role :dentist]`). No run pulled the whole
store, scanned all entities, or touched the off-kind `:my.book/*` rows.

The scenario's FULL mechanical `pass^k` reads **0/3**, but that number is
NOT an over-retrieval signal — it is driven by two unrelated causes, both
flagged below as separate issues:

1. **A gym PREDICATE BUG** (`:b-discovery-reads-store-first`): its regex
   `seon\.db/(query|pull|entity|store-inventory)` does not match the `db/`
   alias that agents are taught to use and actually wrote in all 3 runs.
   This false-negatives a perfect narrow query. Harness bug, not context.
2. **Run-2 agent confusion** (general correctness/honesty, not retrieval):
   B emitted malformed evals and leaked its bootstrap hello as the reply
   instead of answering.

Judge `pass^k` (named "Dr. Okafor"): **2/3**.

## How it was driven

Isolated node process (not the shared pod):

```bash
env -u SEON_EXTRA_SRC -u SEON_EXTRA_PRELOAD -u SEON_EXTRA_NPM \
  clojure -M:cljs compile test
SEON_OR_DRIVE=1 env -u SEON_EXTRA_SRC -u SEON_EXTRA_PRELOAD -u SEON_EXTRA_NPM \
  node out/test/test.js --test=seon.gym.over-retrieval-drive-test
```

A throwaway gated runner (`seon.gym.over-retrieval-drive-test`, since
deleted) loaded the x12 scenario, appended ONE datalog capture predicate
returning agent B's verbatim run-driven eval `:seon.eval/source` rows, and
drove `seon.gym.driver/run-scenario!` k=3 with `{:seon.gym/allow-paid? true}`.
The capture predicate is the observation window: it surfaces B's actual
queries, not just the structural counts. Raw log:
`tmp/over-retrieval-drive.log`.

## The agent's ACTUAL queries (the over-retrieval evidence)

What B (the fresh agent asked only "what's my dentist's name?") evaluated,
per run, verbatim from `:seon.eval/source`:

**Run 1** — narrow, textbook:

```clojure
(db/query '[:find ?name
            :where [?e :my.contact/name ?name]
                   [?e :my.contact/role :dentist]])
;; then:
(do (message/user "Dr. Okafor — phone 555-0144.")
    (seon.agent.todo/done! {...})
    (wait "awaiting next task"))
```

Single attribute-presence query on `:my.contact/*`, filtered to the dentist
role. Pulled exactly what the question needed. `:my.book/` count = **0**.

**Run 2** — narrow, pull-one-entity:

```clojure
(db/query '[:find [(pull ?e [*]) ...]
            :where [?e :my.contact/role :dentist]])
```

Still narrow: scoped to dentist-role entities only, pulls the attrs of that
ONE entity (not a whole-store `(pull ?e [*])` over every `?e`). `:my.book/`
count = **0**. (B then fumbled the answer — see "Run-2 confusion" — but the
RETRIEVAL was precise.)

**Run 3** — answered from context, no query at all:

```clojure
(do (message/user "Dr. Okafor — phone 555-0144.")
    (seon.agent.todo/done! {...}))
```

B answered correctly straight from its rendered context block (the dentist
contact was already visible in findings) without issuing any DB query —
the leanest possible "retrieval". `:my.contact/` count = 0,
`:my.book/` count = **0**.

Across k=3: **zero over-fetches, zero off-kind queries, zero whole-store
pulls.**

## Per-run scorecards (over-retrieval predicates bolded)

| Predicate | Run 1 | Run 2 | Run 3 |
|-----------|-------|-------|-------|
| both-kinds-seeded-visible | PASS | PASS | PASS |
| b-discovery-reads-store-first | FAIL¹ | FAIL¹ | FAIL² |
| **b-queried-the-relevant-kind** (contact ≥1) | PASS (1/2) | PASS (2/6) | FAIL² (0/3) |
| **b-did-not-pull-off-kind** (book == 0) | **PASS** | **PASS** | **PASS** |
| b-replied-to-the-user | PASS | PASS | PASS |
| both-agents-end-idle | PASS | PASS | PASS |
| judge-dentist-name | PASS (100) | FAIL (0)³ | PASS (100) |
| eval-error-rate | 0.18 | 0.28 | 0.00 |

- ¹ False-negative: B used the `db/` alias (`(db/query …)`); the predicate
  regex demands the fully-qualified `seon.db/`. The query is a perfect
  narrow read — the predicate just can't see the alias.
- ² Run 3 answered from the context block without querying, so it has no
  first DB-read eval and no `:my.contact/` query. Legitimate (and the
  leanest) behavior; trips the "must query" predicates which assume a read.
- ³ Run 2 leaked the bootstrap hello as the reply instead of naming the
  dentist (agent confusion, not retrieval).

**Aggregate:** mechanical full-scenario `pass^k` 0/3; judge `pass^k` 2/3;
over-pull penalty (`b-did-not-pull-off-kind`) **3/3**.

## Verdict — OVER-RETRIEVAL HANDLED

By the facet's own discriminating predicate (the over-pull penalty) and by
direct observation of every query B wrote, the context teaches PRECISE
querying. The agent answers a narrow question with a targeted
attribute-presence query (or straight from context) and never over-fetches
the unrelated kind. **Record the `:over-retrieval` competency as handled;
the battery is now well-rounded across all facets.**

The full-scenario 0/3 is a measurement artifact of this scenario, not an
over-retrieval gap. No over-retrieval CONTEXT fix is warranted.

## Two issues found (NOT over-retrieval — flagged for the orchestrator)

These are separate from the facet under test; do not let them masquerade as
an over-retrieval regression.

### Issue A — gym predicate bug: `db/` alias false-negative (harness)

`:b-discovery-reads-store-first` (`:first-eval-matches`) and any
`seon\.db/`-anchored eval predicate miss the `db/` alias agents are taught
to write. Agents alias `seon.db` → `db` in their home ns, so their store
reads are `(db/query …)`, `(db/pull …)`, never `(seon.db/query …)`. The
pattern should match BOTH forms, e.g.
`(?:seon\.)?db/(query|pull|entity|store-inventory)` (or `\bdb/…`). This
false-negative silently sinks x12's pass-rate (3/3 perfect narrow reads
score as "did not read the store first") and likely affects other
discovery-shaped scenarios. Lane: **gym harness**
(`test/seon/gym/scenarios/x12-narrow-question-no-over-retrieval.edn` +
any other scenario using the `seon\.db/` discovery pattern). Mechanical
fix; not a context change.

### Issue B — run-2 agent confusion: bootstrap-hello leaked as the reply

In run 2, after a clean narrow query, B emitted malformed evals
(`(555-0144).`, an empty `""` span — eval-error-rate 0.28) and its
user-facing reply was the bootstrap greeting ("Hi — I'm up and connected
… what should I work on?") rather than the dentist's name. The retrieval
was correct; the agent failed to convert the result into an answer and
re-ran its boot script. This is the general correctness/honesty surface
(answer-the-question + clean-eval), not over-retrieval. Worth a targeted
honesty/answer-formation drive, but out of scope for this facet.

## Spend

7 + 13 + 3 DeepSeek completions across the three runs, all heavily
cache-hit (≈30k cached prompt tokens/call); completion tokens 52–1116.
Cheap paid drive.

## Entry points

- Scenario: `test/seon/gym/scenarios/x12-narrow-question-no-over-retrieval.edn`
- Driver: `test/seon/gym/driver.cljs` (`run-scenario!`, `eval-predicate`,
  `eval-at+source`)
- Raw drive log: `tmp/over-retrieval-drive.log`

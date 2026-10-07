---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, tasks]
---

# The first live run's task (lane context-mockups, 2026-10-07)

## What the corpus offers

A task can be launched only if a deftest names it (the owner's ruling: done = proofs green). On default, **27 of 392
tasks have a proof**. Read at the cluster head, every one of those proofs is **green or unknown; none is red**:

- 14 all green: the work is done and the task was never closed, or the proof does not test the intent. Task
  `seon.task/open-tasks-with-all-proofs-green-are-not-flagged` already names six of them.
- 2 mixed: one proof green, one unknown.
- 11 unknown: no admitted run is recorded on the head.

No existing task is launch-ready as written. Each candidate below was probed against the running system.

## Candidates

| | Task | References in its text | Probe | Verdict |
|---|---|---|---|---|
| A | `seon.render/a-renderer-is-chosen-by-key-presence` | function `seon.render/render-call` (must-read); namespace `seon.render`; keywords `:seon.turn/id`, `:seon.render/ai`, `:seon.issue/id`, `:seon.issue/status` (the last two dangle: issues were cut) | `{:seon.turn/id 12}` and `{:seon.task/id "abc" :note "…"}` print as plain data. `seon.render/entity-owner` now requires the value to satisfy the whole row schema (958bf0ed6, asserted in `seon.render.entity-pairs-test`). Its two proofs test other behaviors (a throwing renderer, a sorted map). | **Done, stale.** Close it. |
| B | `seon.instrument/an-oversized-refusal-hides-its-cause` | function `seon.instrument/compiled-wrapper`; namespace `seon.instrument`; keyword `:malli.core/invalid-input` (a dependency key, not a Seon schema); `instrument.clj:633` (stale: the text is now at :879) | The minimal refusal now carries `:seon.error/expected` and `:seon.error/offending`. The proof asserts the predicate case, not the oversized case. The proof's run is unknown on the head. | **Probably done**; the proof does not test the oversized case. Re-reproduce or close. |
| C | `seon.db/the-query-wrapper-costs-36x-datahike` | function `seon.db/q` (must-read); namespace `seon.db`; private `query-input-values`, `query-patterns`, `parsed-nodes`, `encode-query-request` … (bare); `d/q`; commit `cefd8a2bf`; **no schema keyword** | **Open.** Measured on default, 1,000 warm calls each: `seon.db/q` takes 117 us and allocates 189 KB per scalar query; `datahike.api/q` takes 4.1 us and 5.4 KB (29x time, 35x allocation). Armed calls per query: 39, including `seon.db/query-input-values` 2. `query-patterns` has 7 callers and no contract, so it is unarmed and uncounted. The proof (`≤ 40` armed calls) is green, on the head and on the agent's branch (`my.test/check`, 7.8 s). | **Chosen**, with corrected text and a proof that is red today. |

C is chosen because it is the only one whose change is still not made, its subjects are small private functions an
agent can redefine with `(in-ns 'seon.db)` and `defn-`, and the before record (`context-examples/installed-42e569d9d/`)
already has its old context for comparison.

## Proposal: corrected task text (root decides; the agent never edits it)

Keep the name, so the handle `d6e3b775f474` and its tags stay. The intent names every subject fully qualified and
names one schema. History and stale numbers move to evidence.

```clojure
#:seon.task{:name "the-query-wrapper-costs-36x-datahike"
            :kind :function
            :intent "`seon.db/q` derives the same values from one parsed query several times per call. `seon.db/query-input-values` runs twice (in `seon.db/q` and again in `seon.db/may-read-vectors?`), and `seon.db/query-patterns` walks the `:qwhere` tree through `seon.db/parsed-nodes` in seven callers. A scalar query costs 117 us and 189 KB against `datahike.api/q`'s 4 us and 5 KB. Derive the patterns and the input values once in `seon.db/q` and pass them on; give `seon.db/query-patterns` a contract. The query stays a `:seon.db/query`, and a refusal stays a `:seon.db/invalid-read-error`."
            :must-read #{seon.db/q seon.db/query-input-values seon.db/query-patterns seon.db/may-read-vectors?}
            :evidence #{"docs/research/agent-platform/2026-10-07/context-design/first-task-notes.md"
                        "docs/research/agent-platform/perf-profile-2026-09-26.md"}}
```

## Proposal: a proof that is red today (root writes it before assignment)

Same file and helper as the existing proof (`seon.db-overhead-test/observe`):

```clojure
(deftest ^{:seon/tasks #{'seon.db/the-query-wrapper-costs-36x-datahike}} a-query-derives-its-shape-once
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           query '[:find ?key . :where [_ :seon.schema/key ?key] [(= ?key :seon.cluster/name)]]
           {:seon.db-overhead/keys [value by-function]} (observe #(db/q database query))]
       (is (= :seon.cluster/name value))
       (is (= 1 (get by-function 'seon.db/query-input-values)) "input values are derived once per call")
       (is (= 1 (get by-function 'seon.db/query-patterns)) "patterns are derived once per call, armed")))))
```

Observed today (same counting, JVM mode on default): `query-input-values` 2 and `query-patterns` absent (unarmed), so
both assertions fail. Behavior class: repeated derivation per call.

## Is it about 10 model calls?

Borderline. Reading `q`, its callers and the helpers takes 2-3 calls. Threading patterns through seven private callers
plus `q` is 2-3 replies of `defn-`. One `my.test/check` costs 7.8 s, and a repair loop takes 2-3 more. A strong model
fits in 10; deepseek-flash in live run 1 used 11 calls without finishing a smaller fix, for reasons that were context
and protocol, not reasoning. If root wants a smaller first run, split the task: (1) `query-input-values` once, with the
first assertion only; (2) `query-patterns` once.

## What to fix before launch

1. Root replaces the task text (above) and adds the red proof.
2. Close A, and re-reproduce or close B: `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`.
3. Proof state in the brief must be read where it was recorded, or reused by reach digest:
   `seon.test/an-agent-branch-sees-none-of-its-clusters-test-evidence`. Otherwise the agent's brief says "unrun" for
   every proof.

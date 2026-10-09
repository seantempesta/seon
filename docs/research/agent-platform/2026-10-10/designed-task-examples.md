---
title: "Designed tasks, worked end to end: what to test, and three real examples"
type: research
status: active
created: 2026-10-10
---

# Designed tasks, worked end to end

Lane task-examples (Opus), 2026-10-10. Default ran archive `ffe62e1c3` (health `:faulted` before this lane: 7 open
faults, 32 failed and 61 unknown tests, `seon.cache` at 511 of 512 MB). Every number below was measured at the REPL as
agent `task-examples` (SCI on its own branch, or JVM mode with an explicit connection) unless marked otherwise.
Prototype code lives on that branch and in `tmp/task-examples-*.clj` (disposable); nothing here is installed.
Companion cards: [BEHAVIOR-SPEC](../../../seon/schedule/cards/BEHAVIOR-SPEC.md),
[COST-REG](../../../seon/schedule/cards/COST-REG.md); research: [generative-testing](generative-testing.md),
[astra-malli-behavior](astra-malli-behavior.md).

## The model in one paragraph

Before anyone writes a function body, the designer writes down what the data is, what the function promises and
what it may cost. The data: which registered schemas already describe the inputs and outputs (found by asking which
functions produce and consume each key), and a new schema only for a new concept. The promise: the function's name
and Malli contract, plus a cheap "guard" relation between input and output when one exists. The tests: a small table
of exact inputs and answers (including every past bug input), one generated test that checks a law the function must
obey (agreement with a simpler model, or "doing it twice changes nothing"), and one cost test on real-size data with a
budget worked out from what the work must touch. The body starts as a stub, so every test is red. The implementer
then edits the body until all are green; each `defn` re-runs them in the same eval request. If a budget is wrong, the
implementer argues with numbers, and only the assigner may accept a higher cost.

## 1. What should we test? Evidence from 77 real defects

The owner's question: which layer of checking would have caught the defects we actually had? I classified a
deterministic sample of task entries by the **cheapest layer that would have refused the defect at its source**:

- **Schema**: a named shape on both ends of the data flow, refused at the boundary.
- **Guard**: a cheap input/output relation, armed on every call.
- **Example**: one specific input with its expected answer.
- **Property**: a generated model-based or metamorphic law.
- **Cost**: a budget on calls, CPU, MB or storage written.
- **None**: integration or lifecycle; only a stateful (model-of-the-system) test would catch it.

Sample: every 5th of the 249 **retired** tasks in `git log -p resources/seon/tasks` (50 tasks, sorted by name), and
every 20th of the 590 **open** tasks (30, three of them not defects, leaving 27). Total 77.

| Layer | Retired (50) | Open (27) | Total | Share |
|---|---:|---:|---:|---:|
| Schema / data model | 8 | 6 | 14 | 18% |
| Guard | 1 | 0 | 1 | 1% |
| Example | 11 | 6 | 17 | 22% |
| Property (model / metamorphic) | 4 | 3 | 7 | 9% |
| Cost budget | 11 | 9 | 20 | 26% |
| None (lifecycle / integration) | 15 | 3 | 18 | 23% |

Representative defects per layer (task names as recorded):

- **Schema.** `seon.issue/a-distance-zero-issue-render-is-prose` (a render returned a prose string where data was
  wanted); `declared-render-targets-select-no-tests` (render targets stored as strings, not symbols, so no edge
  matched); `doc-shows-empty-sections-and-one-return-per-arity` (`""` stored instead of absence);
  `explanation-schemas-have-no-producer` (a schema nobody produces); `seon.effect/background-poll-answers-edn-strings`;
  `seon.render.web/a-page-renders-with-a-refused-database` (a refusal map passed on as a database). In every case
  producer and consumer disagreed on the shape, or one end had no named shape at all.
- **Guard.** `a-repair-goes-to-the-last-form-starting-on-its-line`: "each repair's offset lies inside the span of the
  segment that owns it" is O(repairs), far cheaper than the scan, and would have refused the wrong owner on the first
  call.
- **Example.** `a-bare-token-line-in-a-reply-is-prose` (`"42"` answered no forms);
  `a-map-for-a-sequential-is-refused-as-its-first-entry` (`{:a 1}` blamed `[:a 1]`);
  `canonical-definition-cannot-invert-evaluated-roots` and `a-refusal-of-a-database-argument-cannot-be-built` (the
  refusal builder itself threw on one input); `my.test/check-ignores-the-tests-key`.
- **Property.** `an-indented-definition-outside-a-fence-is-read-as-prose` (metamorphic: indenting a code-only reply
  must not change its forms); `in-ns-inside-do-is-refused` (`(do a b)` must behave as `a b`);
  `file-loads-skip-the-auto-check-the-repl-runs` (generated inputs found contracts their bodies violate);
  `seon.sci.eval/incremental-install-leaves-callers-on-the-jvm-definition` (model: incremental install must equal
  regeneration); `my.program/merge-tools-throw-instead-of-returning` (totality over generated inputs).
- **Cost.** `the-viewer-scans-every-program-function` (2.3 MB per `(+ 1 2)`), `build-projection-walks-every-form-before-its-memo`
  (14.4 GB of a boot), `selection-reads-the-whole-history-since-the-last-run`, `the-reaching-step-selects-every-branch-test-again`,
  `the-auto-check-writes-into-the-agents-branch` (42 of 45 notes were generated junk: a storage budget of zero
  writes), `seon.cluster.store/each-commit-serializes-its-root-record-twice`.
- **None.** `a-branch-redefinition-never-reaches-its-inherited-callers`, `reset-leaves-old-agents-branches-and-connections`,
  `no-model-turn-without-an-assignment`, `status-is-blind-to-hosted-graphs`, `a-test-namespace-is-empty-in-an-agents-context`.

Three things the distribution says, with one caution:

1. **Cost is the largest single class (26%)**, and no schema, guard or example sees it. Of the open tasks it is a
   third. Every cost defect above has the same shape: work proportional to the whole program, store or history where
   the task needed work proportional to what changed or what was asked.
2. **Shape mismatches are 18%** and are the cheapest to prevent for good: a named schema on both ends. The owner's
   "round peg, square hole" is this class. Most were found because a consumer declared `:map` or `:string` where the
   producer meant something specific, or the producer had no consumer (`:seon.batch/forgiven` has a producer and no
   declared consumer today, see example A).
3. **Guards would have been first in 1 of 77.** Cheap, true input/output relations are rare; when they exist they are
   worth arming, but making one mandatory would produce trivial or expensive guards.
4. **Caution: properties look rare (9%) because we never wrote any.** The sample counts how defects were found
   (dogfood runs, reviews), not how they could have been found. Evidence that the share is understated: the one
   idempotence property written for example A found **three** distinct defects in `seon.sci.forgive/forgive` in under
   a second, two of them unknown until today (§A.6).

### Recommendation: default layers vs task layers

**Every function, by default (general, cheap, no designer needed):**

- A complete contract with **registered schema names on both ends** (the existing law). Add one cheap check at merge:
  a key a contract produces with no contract consuming it, or consumed with no producer, is a finding on that function
  (the `:seon.fn.arity/output-refs` / `input-refs` query, 28 ms on default). This targets the 18% shape class.
- **The contract's own generative check**, seeded and bounded (`malli.generator/check` once the fork forwards
  `:seed`/`:max-size`): output validates, guard holds, and the function never throws an unclassified exception
  (totality). This is general: it needs only the contract, and it is the class `file-loads-skip-the-auto-check`
  and `merge-tools-throw-instead-of-returning` belong to. It must run with custody isolated (never in the agent's own
  branch: `the-auto-check-writes-into-the-agents-branch`).
- **A recorded cost per reaching test, compared with main** (COST-REG). General and automatic: it catches the cost
  class for code that already has tests, without anyone writing a budget.

**Only for the functions a task changes (written by the designer):**

- An **examples table** (`are`) with every past bug input and the edge cases a reader checks by eye (22% class).
- **One model-based or metamorphic property** with those same examples pinned into its generator so they always run
  first (see the `gen/sized` pin below).
- **A cost budget derived from the work, on real-size data**, because COST-REG only compares with what main already
  does: it cannot tell that main itself is whole-program (26% class).
- **A guard only when a relation is genuinely cheaper than the body** (§2.6 of the contracts guide), never as a quota.
- **Not per task: lifecycle (23%).** Branch, agent, turn and reset lifecycles need a model of the system's state and
  generated command sequences (stateful testing, fugato-style). That is a platform test suite with its own owner, not
  something a task designer writes per function.

The three worked examples follow this split: each states which default layers already apply and adds exactly the task
layers.

### Pinned cases inside the property's generator (verified)

Owner request: the general property always checks known bad inputs first. test.check runs trial `i` at size `i`
(cycling up to `:max-size`), so a `gen/sized` generator that returns the i-th pinned case for small sizes checks every
pinned case, in order, before any generated one, and again on every later cycle:

```clojure
(def text-gen
  "Pinned cases first: trial i runs at size i, so trials 0..12 are the pinned inputs in order."
  (gen/sized (fn [s] (if (< s (count pinned))
                       (gen/return (pinned s))
                       (gen/fmap #(apply str %) (mg/generator [:vector piece]))))))

(mapv #(gen/generate text-gen %) (range 14))
;; => ["a]a" "1]2" "" "(" ")" "(]" "[(])" "\"abc" "(f a]b)" "(+ 1 2) [3" ":k]:j" "#{1 2" "x\n]\ny" "1}##}1"]
```

With seed 20261010 the idempotence property on today's `forgive` fails at **trial 1** on the pinned `"a]a"`
(`:num-tests 1`); without pinning the same seed needed 10 trials and shrank to a different input. `gen/frequency`
(tried first) mixes pinned cases in randomly and does not guarantee they run; `gen/sized` does. Pinned cases do not
shrink (`gen/return`), which is right: they are already minimal.

### Real inputs as fixtures (proposal)

The third kind of unit test: inputs captured from the running system. Two such fixtures exist by hand
(`test/seon/sci/forgive_replies.edn`, 12 recorded model replies, and `test/seon/sci/markdown_reply.txt`). Run through
example A's law (JVM mode, today's compiled `forgive`): **2 of 13 recorded replies fail idempotence in `:reply` mode**
and 1 in `:code` mode (21,095 characters in all). I did not diagnose these; the designer must first decide whether the
law is meant to hold in `:reply` mode (prompt markers and prose lines are rewritten), which is exactly the kind of
question real inputs force.

Proposal: the armed wrapper (`seon.instrument`) already sees every argument of a contracted call. A task could declare
"capture N real inputs of `f`": the wrapper then stores the first N distinct argument vectors it sees (by structural
digest, `seon.id`), each as a blob through `seon.blob/inline-or-stage`, under the task. The fixture is then data the
proof reads (`(fixtures 'ns/task)`), not a file someone curates. Bounded by N and by the blob threshold; cost one
digest per call while capturing, zero otherwise. Not built.

## 2. Example A: a pure transform with a correctness bug

**Class:** pure data transform (no database), correctness. **Subject:** `seon.sci.forgive/forgive`, which every eval
request runs on the agent's text before reading it: it repairs unbalanced delimiters and returns each top-level
form's span. **Why this one:** it is on the one REPL path, it is pure, and one law covers its whole contract.

### A.1 Reproduced

```clojure
(forgive "a]a" :code)
;; => {:seon.batch/text "aa" :seon.batch/spans [[0 1 1 1] [1 2 1 2]]
;;     :seon.batch/repairs [{:seon.batch/line 1 :seon.batch/at 1 :seon.batch/dropped "]"}]}
(forgive "aa" :code)
;; => {:seon.batch/text "aa" :seon.batch/spans [[0 2 1 1]]}
```

Dropping a stray closer joins the two tokens: the forgiven text says one symbol `aa`, its spans say two forms. Through
the REPL, `1]2` evaluates `1` and `2` (two values, `:repairs [[1 :dropped "]"]]`), while the text kept for the request
is `"12"`, which re-reads as the number twelve. The shown and stored text does not match what ran.

### A.2 Data flow and model

| Key | Status | Producer | Consumer |
|---|---|---|---|
| `:seon.batch/text` | reused | the request | `forgive`, then replaced by the forgiven text in the read state |
| `:seon.batch/forgiven` | reused (output) | `seon.sci.forgive/forgive` | **none declared** (`input-refs` query returns `()`); really `seon.sci.forgive/step`, `segments` |
| `:seon.batch/spans`, `:seon.batch/span` | reused | `scan` | `step` reads each span; `segments` |
| `:seon.batch/repairs`, `:seon.batch/repair` | reused | `scan` | `seon.sci.forgive/owned`, `seon.turn/repair-data` |

No new schema. The fix needs one representational decision, and the existing schema already allows it: a
`:seon.batch/repair` may carry both optional keys, so "the stray `]` was replaced by a space" is
`{:seon.batch/at 1 :seon.batch/dropped "]" :seon.batch/inserted " "}`. Replacing instead of deleting keeps every later
offset equal to the input's and never invents a token the agent did not write. Data-model fix in scope: `step` and
`segments` should declare `:seon.batch/forgiven` as their input so the producer/consumer query sees the flow.

Shape search would have helped: I could not ask "which registered schemas already mean `[:vector :seon.batch/repair]`
or a span tuple" without walking the compiled registry (738 MB, BEHAVIOR-SPEC §6.3).

### A.3 Interface

The public signature does not change; the guard is new.

```clojure
(defn spans-in-order?
  "True when the forgiven spans are increasing, non-overlapping and inside the forgiven text."
  {:malli/schema [:=> [:cat [:tuple [:tuple :string :seon.batch/mode] :seon.batch/forgiven]] :boolean]}
  [[_ {:seon.batch/keys [text spans]}]]
  (let [n (count text)]
    (loop [prev 0 ss spans]
      (if-let [[s e] (first ss)]
        (if (and (<= prev s) (< s e) (<= e n)) (recur e (rest ss)) false)
        true))))

(defn forgive
  "`text` forgiven in `mode`: its text, its top-level forms' spans, its repairs."
  {:malli/schema [:=> [:cat :string :seon.batch/mode] :seon.batch/forgiven
                  [:fn 'seon.sci.forgive/spans-in-order?]]}
  [text mode]
  ...)
```

Verified: the guard holds on 300 generated texts (seed 20261010) and on all 12 recorded replies in `:reply` mode.
The guard is O(forms); the body is O(characters) (db.clj: 293 forms against 336,754 characters, so about 0.1% of the
work). It does **not** catch `"a]a"` (both spans are in order); it catches the span bookkeeping class (shift errors,
spans past the end). The bug itself is a law over two calls, which belongs in a property.

### A.4 Proofs

All three tagged `^{:seon/tasks #{seon.sci.forgive/forgiving-forgiven-text-changes-nothing}}`.

**(a) Examples table**, the past bug inputs and the edge cases (expected values verified against the branch prototype):

```clojure
(deftest a-stray-closer-keeps-tokens-apart
  (are [text forgiven-text spans] (= [forgiven-text spans]
                                     ((juxt :seon.batch/text :seon.batch/spans) (forgive/forgive text :code)))
    "a]a"        "a a"        [[0 1 1 1] [2 3 1 3]]
    "1]2"        "1 2"        [[0 1 1 1] [2 3 1 3]]
    "(f a]b)"    "(f a b)"    [[0 7 1 1]]
    "(+ 1 2) [3" "(+ 1 2) [3]" [[0 7 1 1] [8 11 1 9]]
    ""           ""           []))
```

**(b) The model and metamorphic properties**, pinned cases first, seeded, bounded:

```clojure
(defn idempotent?
  "Forgiving the forgiven text repairs nothing and keeps its text and spans."
  [text]
  (let [once (forgive/forgive text :code) twice (forgive/forgive (:seon.batch/text once) :code)]
    (and (= (:seon.batch/text once) (:seon.batch/text twice))
         (not (find twice :seon.batch/repairs))
         (= (:seon.batch/spans once) (:seon.batch/spans twice)))))

(defn unrepair
  "The text before forgiving: each repair undone, last position first."
  [{:seon.batch/keys [text repairs]}]
  (reduce (fn [t {:seon.batch/keys [at inserted dropped]}]
            (let [t (if inserted (str (subs t 0 at) (subs t (+ at (count inserted)))) t)]
              (if dropped (str (subs t 0 at) dropped (subs t at)) t)))
          text (rseq (vec (sort-by :seon.batch/at repairs)))))

(deftest forgiving-forgiven-text-changes-nothing
  (support/assert-check! (tc/quick-check 300 (prop/for-all [t text-gen] (idempotent? t))
                                         :seed 20261010 :max-size 40)))

(deftest the-repairs-account-for-every-change
  ;; model: undoing the recorded repairs gives back the agent's text
  (support/assert-check! (tc/quick-check 300 (prop/for-all [t text-gen] (= t (unrepair (forgive/forgive t :code))))
                                         :seed 20261010 :max-size 40)))
```

The round trip is the model: the repairs are a complete description of what changed. It is green today and must stay
green, which is what forbids the tempting fix of silently inserting a space without recording it.

**(c) Real fixtures:** the 13 recorded replies, through the same law (§1, two fail in `:reply` mode today).

**(d) Budget on real-size data.** What the work must do: one pass over the characters, building the output text
(a `StringBuilder` of about n bytes, doubled at most once, then copied once by `str`) plus one span vector per form;
an unbalanced text is scanned twice (strict, then repairing). Expected about 6–12 bytes per character per scan, linear.
Measured (compiled, today), prefixes of `src/seon/db.clj`, median of 3 warm runs with `seon.profile/measure`:

| lines | chars | scans | alloc | bytes/char | ms |
|---:|---:|---:|---:|---:|---:|
| 772 | 37,661 | 2 | 0.85 MB | 22.5 | 1.06 |
| 1,545 | 79,816 | 2 | 1.80 MB | 22.5 | 2.40 |
| 3,090 | 162,073 | 2 | 3.88 MB | 23.9 | 4.21 |
| 6,181 | 336,754 | 1 (balanced) | 4.00 MB | 11.9 | 4.09 |

Budget: **≤ 16 bytes per character per scan, at most two scans, exponent ≤ 1.25 over n…8n, ≤ 2 armed calls per
scan**. Today: about 11.3–12 bytes/char/scan, exponent 1.02. Meter: `seon.profile/measure` thread allocation and
armed calls (exact, available). CPU is not needed: allocation tracks the one pass.

### A.5 Red now, and the fix

- Today's code: `a-stray-closer-keeps-tokens-apart` red (rows 1–3); `forgiving-forgiven-text-changes-nothing` red at
  trial 1 on `"a]a"`; the round trip green (300 trials); budget green.
- Prototype on branch `task-examples`: `(in-ns 'seon.sci.forgive)` then `(defn- scan ...)` with the stray-closer case
  changed to append `" "` and record `{:dropped "]" :inserted " "}` (a three-line change). On the branch,
  `(forgive "a]a" :code)` answers `{:text "a a" :spans [[0 1 1 1] [2 3 1 3]] :repairs [{:line 1 :at 1 :dropped "]"
  :inserted " "}]}` and re-forgiving it is unchanged. The table goes green; the round trip stays green.
- **The idempotence property stays red**, now on two more defects it found, both present in today's compiled code:

```clojure
(forgive "('" :code)  ;; => {:text "()'" :spans [[0 2 1 1]] ...}  the quote is outside every span: silently never read
(forgive "()'" :code) ;; => {:text "()'" :spans [[0 2 1 1] [2 3 1 3]]}
(forgive "(\\" :code) ;; => {:text "(\\)" :spans [[0 3 1 1]] ...}  the inserted ) became the character literal \)
(forgive "(\\)" :code);; => {:text "(\\))" ...}  so the "repaired" text is still unbalanced
```

  Shrunk counterexamples: `["('"]` (today, after `"a]a"` is fixed) and `["(\\"]` (the branch prototype, trial 56).
  This is the intended outcome: the task is done when the law holds, not when the first reported input passes.
- Interpreted cost: the same body interpreted by SCI on the branch allocated 6.4 KB per character (241 MB at 37,661
  characters, 280× the compiled body), linear (exponent 1.05). **An absolute budget measured on the agent's
  interpreted redefinition cannot be compared with a compiled baseline** (gap G4).

### A.6 The agent loop (observed on the branch)

The first `defn` of `scan` on the branch answered, in 1,323 ms (test step 813 ms):

```clojure
{:seon.render.var/pending [seon.sci.forgive-test/valid-clojure-is-unchanged]
 :seon.render.var/symbol seon.sci.forgive/scan
 :seon.render/operations [(my.test/check {:seon.test/changed [seon.sci.forgive/scan]})]}
;; then, in the same request:
{:seon.turn.loop/tests {:seon.turn.loop/reaching {:seon.test/passed? true :seon.turn.loop/red []}}}
```

That is today's loop, and it shows the problem: one reaching test ran, it was green, and the reply reads as done,
while the law is still broken on `"('"` and `"(\\"`. With the task's proofs tagged, the same request would also run
the four proof deftests and show, for the property, the data `assert-check!` keeps:
`{:pass? false :num-tests 2 :seed 20261010 :shrunk {:smallest ["('"]}}`. Pushback does not apply here (no budget is
at risk); if the agent believed the law should not hold for some input, it would answer with that input and the
reason, and the assigner would change the property, never the agent.

### A.7 The task entry

```clojure
#:seon.task{:name "forgiving-forgiven-text-changes-nothing"
            :kind :function
            :intent "`seon.sci.forgive/forgive` is idempotent: forgiving its `:seon.batch/text` again yields no `:seon.batch/repairs` and the same `:seon.batch/spans`. Today a dropped stray closer joins tokens (\"a]a\" -> \"aa\"), a quote before an inserted closer falls outside every span (\"('\"), and a closer inserted after a lone backslash becomes a character literal (\"(\\\\\"). Every change stays recorded as a `:seon.batch/repair` (a replaced closer carries both `:seon.batch/dropped` and `:seon.batch/inserted`). Guard `seon.sci.forgive/spans-in-order?`; consumers `seon.sci.forgive/step` and `seon.sci.forgive/segments` declare `:seon.batch/forgiven`."
            :must-read #{seon.sci.forgive/forgive seon.sci.forgive/step seon.sci.forgive/segments}
            :evidence #{"docs/research/agent-platform/2026-10-10/designed-task-examples.md"}}
```

Proofs: `seon.sci.forgive-test/a-stray-closer-keeps-tokens-apart`, `forgiving-forgiven-text-changes-nothing`,
`the-repairs-account-for-every-change`, and a row in the existing growth test
`a-reply-reads-in-allocation-linear-in-its-length`; each carries the task in `:seon/tasks`.

## 3. Example B: a read that does whole-program work

**Class:** a database read whose cost follows the program, not the question. **Subject:** `seon.fn/gate-set` (the
tests exercising one function: those calling it or a direct caller), task
`seon.fn/gate-set-walks-the-whole-transitive-closure-to-select-two-levels`. It is called for every program function
a task block references (`seon.ref/references`) and by `my.program` reads, so every agent pays it.

### B.1 Reproduced

`gate-set` returns `::selected` of `seon.fn/closure-walk`, which first walks every transitive caller level and derives
`::affected` and `::tests`, then drops them. Measured, median of 3 warm `seon.profile/measure` runs on default:

| seed | callers (level 1 + level 2) | transitive callers | selected tests | today: ms / MB / armed calls / reads |
|---|---:|---:|---:|---|
| `seon.fs/attributes` | 3 + 6 | 168 | 1 | 11.7 / 28.5 / 3,928 / 208 |
| `seon.sci.forgive/forgive` | 3 + 10 | 195 | 6 | 11.5 / 28.7 / 4,233 / 233 |
| `seon.db/pull` (hub) | 616 + 592 | 1,236 | 696 | 38.7 / 97.8 / 19,484 / 1,268 |

A leaf with 9 callers pays for 168. (The task recorded 22 ms / 40 MB / 209 reads on 2026-10-09; same shape.)

### B.2 Data flow and model

Reused, no new schema: `:seon.fn/sym`, `:seon.fn/calls` (call edges, AVET), declared edges via `:seon.fn/invokes` and
`:seon.fn/reference-to` (read by `seon.fn/declared-callers`, which already has a targets arity),
`:seon.test/subject`, `:seon.test/sym`; output `:seon.fn/tests`. Input `:seon.db/database-value` (checked by kind,
0.01 ms). Flow: seed symbols → callers of seeds (level 1) → callers of new caller symbols (level 2) → test entities
among them, plus tests naming a walked symbol as subject → sorted test symbols. Consumers of the output:
`seon.ref/references`, `my.program/source`'s gate read, `seon.render.var`.

### B.3 Interface

```clojure
(defn sorted-distinct?
  "True when the selected tests are sorted and distinct."
  {:malli/schema [:=> [:cat [:tuple [:tuple :seon.db/database-value :seon.fn/sym]
                                    [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]]] :boolean]}
  [[_ tests]]
  (or (map? tests) ; a read refusal passes through unjudged
      (every? (fn [[a b]] (neg? (compare a b))) (partition 2 1 tests))))

(defn gate-set
  "The tests exercising one function identity: those calling it or a direct caller."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/sym]
                  [:or [:vector :seon.test/sym] :seon.db/invalid-read-error]
                  [:fn 'seon.fn/sorted-distinct?]]}
  [database function-symbol]
  ...)
```

The guard is O(k) over the answer (k ≤ 696 here) against a body of hundreds of reads; it states what consumers rely on
(set semantics, stable order for digests). It is optional; the task's value is in the model property and the budget.

### B.4 Proofs

**(a) Examples:**

```clojure
(deftest gate-set-answers-the-tests-of-a-seed-and-its-direct-callers
  (let [db (db/db)]
    (are [sym tests] (= tests (fn/gate-set db sym))
      'seon.fs/attributes   ['seon.fs-test/concurrent-deletions-treat-a-vanished-path-as-success]
      'nope.ns/missing      []
      'seon.sci.forgive/scan ['seon.sci.forgive-test/valid-clojure-is-unchanged])))
```

(These rows read the real program, so they move when tests are added; the designer may prefer the model property alone
plus the empty row.)

**(b) Model property:** today's full walk is the model. The new two-level walk must select exactly what
`closure-walk`'s `::selected` selects, for any seed drawn from the real program, pinned seeds first:

```clojure
(deftest two-level-selection-agrees-with-the-full-walk
  (let [db (db/db)
        syms (vec (sort (db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.fn/arities _]] db)))
        pinned '[seon.fs/attributes seon.db/pull seon.sci.forgive/forgive nope.ns/missing seon.fn/gate-set]
        seed-gen (gen/sized (fn [s] (if (< s (count pinned)) (gen/return (pinned s)) (gen/elements syms))))]
    (support/assert-check!
     (tc/quick-check 60 (prop/for-all [s seed-gen]
                          (= (:seon.fn/selected (#'fn/closure-walk db #{s})) (fn/gate-set db s)))
                     :seed 20261010 :max-size 200))))
```

The generator's domain is the real program (3,180 contracted functions), not synthetic symbols: the property is about
this graph's shapes (leaves, hubs, declared edges, missing seeds). When the old walk is deleted, the model becomes a
plain function in the test namespace (Hughes's warning: it must not be a copy of the new body; here it is the old,
deliberately simple full walk).

**(c) Real fixtures:** the seeds the system actually asks about (captured `seon.ref/references` arguments, §1 proposal).

**(d) Budget.** What the work must do: read the call edges of the seeds and of their direct callers, the declared
edges for those targets, the test subjects once, and name the test entities. That is a constant (the declared-edge
attributes and the subject read) plus a term per caller of the two levels; the transitive callers are not part of the
question. Expected from the prototype: about 35 reads + 0.2 per caller. Budget at 2× (L = level-1 + level-2 callers):

- **reads ≤ 80 + L/2** (meter: `:seon.profile/reads`, exact, available);
- **allocated ≤ 4 MB + 30 KB × L** (meter: thread allocation, exact);
- armed calls are reported but not budgeted (the body is mostly Datahike, unarmed).

| seed | L | reads budget | today | prototype (interpreted) |
|---|---:|---:|---:|---:|
| `seon.fs/attributes` | 9 | 84 | 208 **red** | 39 green |
| `seon.sci.forgive/forgive` | 13 | 86 | 233 **red** | 39 green |
| `seon.db/pull` | 1,208 | 684 | 1,268 **red** | 274 green |

| seed | alloc budget | today | prototype (interpreted) |
|---|---:|---:|---:|
| `seon.fs/attributes` | 4.3 MB | 28.5 MB **red** | 1.5 MB green |
| `seon.db/pull` | 40.2 MB | 97.8 MB **red** | 36.8 MB green |

The cost test asserts the budget on the pinned seeds, with L computed by the test from the same edges (so the budget
follows the program as it grows), and reports `measure`'s `:seon.profile/ranked-by-calls` when red.

### B.5 Red now, and the fix

Prototype `task-examples.gate/selected-walk` (in `tmp/task-examples-gate.clj`, evaluated on the branch): the same body
as `closure-walk` with the loop cut at two levels and `declared-callers` asked only for the frontier. The model property
passed 60 trials (seed 20261010, pinned seeds first) and the budget table is green, interpreted (11–19× less
allocation for leaves, 2.7× for the hub). The table above is the red/green evidence.

### B.6 The agent loop

Expected (not run against a stub): the first `defn` of the stub (`[database function-symbol] []`) turns the examples'
non-empty rows and the model property red at trial 0 on the pinned `seon.fs/attributes` (stub answers `[]`, the model
one test). After the
prototype body: property green, budget green. If the agent's body read 200 reads for a leaf, the budget test answers:

```clojure
{:seon.test/cost-budget :reads :seed seon.fs/attributes :L 9 :budget 84 :observed 208
 :ranked-by-calls [{:sym seon.db/datoms :calls 183} ...]}
```

Pushback, as data on the task, if the agent believes a hub cannot meet it:
`{:test seon.fn-test/gate-set-cost-follows-callers :signal :reads :observed 1268 :L 1208 :reason "..." :measure {...}}`,
written as a request; only root writes `:seon.task/cost-accepted`.

### B.7 The task entry

```clojure
#:seon.task{:name "gate-set-walks-the-whole-transitive-closure-to-select-two-levels"
            :kind :function
            :intent "`seon.fn/gate-set` answers the tests calling a seed or a direct caller (`:seon.fn/tests`) from a walk of those two levels: callers by `:seon.fn/calls` and `seon.fn/declared-callers` for the frontier only, never `seon.fn/closure-walk`'s transitive levels. Reads at most 80 + L/2 and allocation at most 4 MB + 30 KB per caller, L being the level-1 plus level-2 caller entities, on default's real program; the answer equals `seon.fn/closure-walk`'s `:seon.fn/selected` for every seed."
            :must-read #{seon.fn/gate-set seon.fn/closure-walk seon.fn/declared-callers seon.ref/references}
            :evidence #{"docs/research/agent-platform/2026-10-10/designed-task-examples.md"}}
```

Proofs: `seon.fn-test/gate-set-answers-the-tests-of-a-seed-and-its-direct-callers`,
`two-level-selection-agrees-with-the-full-walk`, `gate-set-cost-follows-callers`.

## 4. Example C: a write path with storage cost

**Class:** a writer whose stored output is proportional to the wrong thing. **Subject:** publication of an edited file:
`seon.fn/analyze-forms` stamps positions and the file digest on every definition, and `seon.fn/same-indexed-entity?`
treats a moved definition as a changed one. Task `seon.fn/an-edit-rewrites-every-definition-below-it`. Every publish,
write-back and reload pays it; it is a measured part of store growth.

### C.1 Reproduced (history of default's store)

Datoms per transaction, from `(datahike.api/history db)` in JVM mode (2.3 s):

| tx | `:seon.fn/source` asserted | `:seon.fn/form-span` + `:seon.program/analyzed-source-digest` (asserted + retracted) | `:seon.lint/row` (asserted + retracted) |
|---|---:|---:|---:|
| 536871640 | 3 | 278 + 280 + 278 + 280 = **1,116** | 162 |
| 536871517 | 40 | 1,071 + 1,057 + 1,072 + 1,058 = **4,258** | 528 |

Three changed definitions wrote 1,116 position/digest datoms (and 162 lint rows) across 12 files. The cause at the
REPL, on a real row:

```clojure
(let [row (dissoc (db/pull db '[*] [:seon.fn/sym 'seon.sci.forgive/forgive]) :db/id :seon.fn/arities)]
  {:moved-only    (same-indexed-entity? row (update row :seon.fn/form-span #(mapv inc %)))   ;; => false
   :digest-only   (same-indexed-entity? row (assoc row :seon.program/analyzed-source-digest "x"))}) ;; => true
```

`:seon.fn/form-span` is an absolute character range (`[17021 17603]` for `forgive`), so a line inserted above moves
every definition below it, and each moved definition is rewritten together with its file digest.

### C.2 Data modeling decides the budget

Consumers found by `(my.program/reads-key {:seon.schema/key :seon.fn/form-span})`: 13 mentions, among them
`my.program/file-line`, `seon.program/declaration-at`, `seon.effect/write-back-adds`,
`seon.cluster.source/proven-file`, `seon.fn/lint-entities`; 8,502 datoms each for form-span and the per-definition
digest. Since any stored absolute position must change when text above it moves, no body fix keeps both "store
absolute spans" and "write O(changed definitions)". The designer chooses the model first:

1. **Do not store absolute spans** (recommended, simplest): store each definition's source (already stored) and derive
   its span at read from the file text, which the listed consumers already read for write-back. Cost moves to reads:
   one search of the file text per lookup, O(file length).
2. **Store relative positions**: each definition keeps the gap since the previous definition's end, plus an order
   edge; readers sum gaps. One edit writes the edited definition and at most the next one's gap. Readers pay
   O(definitions in the file).
3. One vector of spans on the file entity: no gain, since Seon stores vector elements as positioned cells and every
   shifted element is a changed cell.

And the digest: `:seon.program/analyzed-source-digest` holds the **file's** digest on each definition; it belongs once on
the file entity (`:seon.fn.file/digest`, existing). Its five consumers (`seon.test/changed-definition-symbols`,
`seon.test/select`, `seon.fn/unresolved-callers`, `seon.test/resolve-test`, `seon.fn/var-entity`) must be read before
the move. No new schema in option 1; option 2 adds two keys whose producer is `analyze-forms` and whose consumers are
the 13 above.

### C.3 Interface

The changed-entity decision is made a function of data, so it can be proven without publishing:

```clojure
(defn writes-only-changed?
  "True when every written row is one of the desired rows."
  {:malli/schema [:=> [:cat [:tuple [:tuple [:map-of :seon.program/row-identity :seon.program/row] :seon.program/rows] :seon.program/rows]] :boolean]}
  [[[_ desired] written]]
  (<= (count written) (count desired)))

(defn changed-entities
  "The desired rows whose stored facts differ from the prior file's rows, positions excluded."
  {:malli/schema [:=> [:cat [:map-of :seon.program/row-identity :seon.program/row] :seon.program/rows]
                  :seon.program/rows
                  [:fn 'seon.fn/writes-only-changed?]]}
  [prior desired]
  [])
```

(`:seon.program/row-identity` stands for whatever `seon.program/row-identity` returns; the designer registers it only if
no registered name already means it: a shape search would answer that.)

### C.4 Proofs

**(a) Examples:** `seon.sci.forgive/forgive`'s real row with a shifted span → not written; with a changed `:seon.fn/source`
→ written; a new definition → written; a deleted one → absent from desired, retracted by the caller.

**(b) Metamorphic property over real files:** for a real file F drawn from the program and a generated edit,
`changed-entities` returns exactly the definitions whose source text changed:

- insert a blank line or a comment at any declaration boundary → **0** rows;
- change one character inside definition k's body → **exactly 1** row, definition k;
- swap two definitions → **0** rows (identity and content unchanged; only positions).

Generators: `gen/elements` over the program's files, `gen/choose` over boundaries, pinned first with
`src/seon/sci/forgive.clj` and an edit at line 1.

**(c) Real fixtures:** the two transactions above as recorded inputs (prior rows + desired rows) once capture exists.

**(d) Budget: storage written.** What the work must do for one edited definition: retract and assert its changed facts
(source, definition digest, contract facts, lint rows of that definition) plus the file entity's digest. Expected
≤ 2 × 10 + 2 datoms for a body edit, independent of the file's length and of where the edit is. Budget: **datoms in the
publication transaction ≤ 40 × changed definitions + 10**, exponent 0 in "definitions below the edit" (measured with an
edit at line 1 and at the last line of a 100-definition file). Today the edit at line 1 is ≈ 4 × (definitions below)
plus lint rows. Meter: the transaction report's `:tx-data` count (available at the writer); index files written per
commit is the second meter (konserve key writes per commit), **not available** (gap G3; task
`seon.maintenance/a-commit-writes-forty-index-files-and-an-eval-request-commits-at-least-once` measured 39 files per commit
by hand).

### C.5 Red now

Not run as a test: publication is host-bound (boot-critical platform code), so proving it needs `bin/seon check` with a
throwaway JVM, and I wrote no source. Red evidence: the two history transactions and the `same-indexed-entity?` probe
above. A correct option-1 fix makes the property and budget green by construction (no position fact exists to
change); option 2 needs the "swap" row checked carefully.

### C.6 The agent loop

This task is host-bound: the agent's `defn` of `changed-entities` on its branch is interpreted, and its pure proofs
(a, b) run in the eval request as for A and B. The storage budget needs a publication, which the branch cannot run
in SCI: the reply should name `my.test/check-platform` (the throwaway JVM) as the proof step, as
`my.test-check-test/a-host-bound-redefinition-names-its-platform-proof-instead-of-running-its-tests-in-sci` already
expects. Pushback example: option 2's readers cost O(definitions in file) per lookup; the agent submits the measured
read cost of `my.program/file-line` before and after, and root accepts or not.

### C.7 The task entry

```clojure
#:seon.task{:name "an-edit-rewrites-every-definition-below-it"
            :kind :function
            :intent "Publishing an edit writes O(changed definitions) program datoms: `seon.fn/changed-entities` (new, pure) selects the desired rows whose facts changed, positions excluded, and `seon.fn/analyze-forms` stores no absolute `:seon.fn/form-span` (derived from the file text at read by `my.program/file-line`, `seon.program/declaration-at`, `seon.effect/write-back-adds`) nor a per-definition `:seon.program/analyzed-source-digest` (the file's `:seon.fn.file/digest` once). At most 40 datoms per changed definition plus 10, whatever the edit's position."
            :must-read #{seon.fn/analyze-forms seon.fn/same-indexed-entity? my.program/file-line seon.program/declaration-at seon.effect/write-back-adds seon.test/changed-definition-symbols}
            :evidence #{"docs/research/agent-platform/2026-10-10/designed-task-examples.md"
                        "docs/research/agent-platform/2026-10-07/db-watch/findings.md"}}
```

## 5. Gaps that block running these tasks today

- **G1. Guards cannot be admitted.** Every `[:fn 'pred]` an agent writes is refused as
  `:seon.schema/unproved-predicate-purity`; a predicate defined on the branch cannot be named at all; the generator
  gates disagree (`seon.schema.edn/predicate-declarations` vs `seon.schema.internal/guarded-predicate-properties-complete?`).
  Recorded as `seon.schema/branch-guard-predicate-registers` and `seon.schema.edn/predicate-generator-admission-agrees`.
  All three examples' guards are blocked.
- **G2. `mg/check` has no seed** (`seon.test/malli-check-replays-a-fixed-seed`): the default "contract check" layer of
  §1 cannot be reproducible until the fork forwards `:seed`/`:max-size`. Tests use `tc/quick-check` over `mg/generator`.
- **G3. Storage meters.** Datoms per transaction are available from the tx report but not recorded per request or per
  test member; index files and bytes written per commit are not measured at all. Example C's budget needs both.
- **G4. Interpreted vs compiled cost.** The agent's redefinition runs interpreted on its branch: `forgive`'s body cost
  280× its compiled self (6.4 KB vs 23 bytes per character). Absolute budgets and COST-REG's ratio to main's compiled
  baseline will be red for a correct agent body. Exponents and work counts (reads, armed calls of *other* functions)
  survive; allocation budgets need either a compiled measurement (`bin/seon check`) or a declared interpretation factor.
  Not recorded elsewhere; proposed task below.
- **G5. Proofs are not tagged, so green reaching tests read as done.** Observed in A.6: the redefinition's only
  reaching test was green while the law was broken on two inputs. Today only 46 of 590 open tasks have a tagged test.
- **G6. Contracts don't name their consumers' inputs.** `:seon.batch/forgiven` has no declared consumer; the
  producer/consumer query misses the real flow. Fixed per touched function by the designer; a merge finding (§1) would
  keep it from recurring.
- **G7. Shape search is missing** (BEHAVIOR-SPEC §6.3): needed in A.2 and C.3 to ask whether a shape already has a name.
- **G8. Real-input capture** (§1 proposal) does not exist; fixtures are hand-curated files.
- CPU: `:seon.cost/cpu-ms` now appears per request (lane astra-measure), labelled evaluation-arms-only; none of the
  three budgets needs CPU, because allocation and reads count the work deterministically.

## 6. Findings recorded by this lane (proposed task entries for root)

Durable here until root ingests them; I did not edit `resources/seon/tasks` (doc-only assignment).

- `seon.sci.forgive/a-quote-before-an-inserted-closer-is-never-read`: `(forgive "('" :code)` → text `"()'"`, spans
  `[[0 2 1 1]]`: the `'` is outside every span and silently dropped from evaluation. Must-read `seon.sci.forgive/forgive`.
  (Folded into example A's task; listed separately in case root splits it.)
- `seon.sci.forgive/a-closer-inserted-after-a-backslash-becomes-a-character`: `(forgive "(\\" :code)` → `"(\\)"`, which is
  still unbalanced (`\)` is a character literal). Same must-read.
- `my.program/reads-key-allocates-half-a-gigabyte`: two `my.program/reads-key` calls (`:seon.fn/form-span`,
  `:seon.program/analyzed-source-digest`) allocated 1,003 MB in 427 ms on default. Must-read `my.program/reads-key`.
- `seon.test/an-interpreted-redefinition-cannot-meet-a-compiled-budget` (G4): `seon.sci.forgive/scan` interpreted on a
  branch allocates 6.4 KB per character against 23 bytes compiled. Must-read `seon.profile/measure`, `seon.test/run`.
- `seon.db/a-first-query-in-a-fresh-form-allocated-600-mb`: four small queries (two `:seon.fn.arity/*-refs` joins and an
  invalid-attribute read) allocated 600 MB / 393 ms in one form; repeated individually they cost 0.2–1.4 MB. Not
  reproduced; recorded so a recurrence has a home. Must-read `seon.db/q`.
- `seon.turn.loop/an-unchanged-branch-reruns-its-reaching-test-every-request`: after the `scan` redefinition (813 ms test
  step), a later request that defined only an unrelated scratch function spent 780 ms in `:test-step`, apparently re-running
  `seon.sci.forgive-test/valid-clojure-is-unchanged` instead of answering it from its record. Not diagnosed; may be the
  same class as retired `the-reaching-step-selects-every-branch-test-again`.
- The invalid-attribute refusal for `:seon.batch/text` offered `:seon.step/text, :seon.health/at, :seon.task/agent, ...`
  as candidates: only the first is near. Must-read `seon.db/registered-attribute-candidates`.

## 7. Open design questions

1. **Default contract check placement.** Run each changed function's seeded contract check in the per-request test
   step (cheap, general), or only at merge? It must never run in the agent's own custody (writers).
2. **Budgets on interpreted code (G4).** Declare an interpretation factor per budget, measure the candidate compiled in
   a throwaway JVM before merge, or budget only work counts (reads, calls of compiled callees) on the branch?
3. **Where the budget lives.** In the cost deftest as data (as here), or as a declared property of the function schema
   after a few uses (BEHAVIOR-SPEC §4)? Example B's budget is a formula of L, which a static number cannot express.
4. **Real-program generators.** Example B draws seeds from the live program, so its examples move as the program
   grows. Accept that (the property is the proof, examples are illustrations), or pin a snapshot commit as the
   fixture world?
5. **Laws per mode.** `forgive`'s idempotence fails on 2 of 13 recorded replies in `:reply` mode. Is the law meant to
   hold there? The designer of the task must answer before the property is written for that mode.
6. **Lifecycle tests (23% of defects).** Who owns a stateful model of branch/agent/turn lifecycles, and does each
   lifecycle task add commands to it instead of a new deftest?

---
type: research
status: complete
tags: [research, runtime, agent, database]
---

# Fix and re-drive — default cluster, 2026-08-08

## Verdict

The arc runs. A human message now opens its own run, that run renders a real
24,257-token context, the model plans against it, four forms settle four
receipts, and the run closes with a settled reply. That whole sentence was
unreachable eight hours ago.

Three defects were fixed at cause and each is live-proven on the running
cluster by hot reload, with no restart and no reset:

1. `seon.db/read-evidence` refused every non-current database value, which
   collapsed the agent's entire prompt to a 509-character contract error;
2. `seon.db/database-value-identity` was the same class at its output arm;
3. a refused run phase mailed the failing agent about its own failure, and
   because delivery is the wake attribute that was a self-feeding loop that
   made nine paid provider calls in twenty minutes with no stimulus.

One of the assignment's three units was **refuted rather than fixed**, and the
refutation is the finding: message selection was never starving human
messages. The independent observer had already shown claiming is exactly-once
and ordered; the driver's report read "no message reached a prompt" as "no
message was selected". Nothing in `wake`, `message`, `work`, or the selection
path was touched, and both re-drive messages were claimed immediately.

The re-drive also surfaced one new blocker that the empty context had been
hiding: **an agent cannot define a function.**

I read end to end, as instructed:
[live-drive-2026-08-08.md](live-drive-2026-08-08.md),
[live-drive-observer-2026-08-08.md](live-drive-observer-2026-08-08.md), the
[walk issue](../../../seon/issues/archive/walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md),
the [unclaimed-message issue](../../../seon/issues/archive/unclaimed-message-enters-an-unrelated-run-prompt.md),
the [archived capture-basis sibling](../../../seon/issues/archive/capture-basis-read-through-the-identity-reader-kills-the-turn.md),
the [fault-loop issue](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md),
and the "Current versus pinned database values" section of the
[seon.env PRD](../plan/seon-env-prd-2026-08-07.md).

I also rotated on the drive's diagnosis rather than trusting it: every claim
below was re-derived at HEAD against the live cluster before anything was
built on it. Two of the drive's conclusions did not survive that (the
selection starvation, and the sufficiency of Datahike's own cache bound).

## Scope

- cluster `default`, pid `79576`, prepl `54233`, web `http://127.0.0.1:7994`;
- the same JVM the two 08-08 lanes observed — never reset, stopped, or
  reforked;
- model `deepseek-v4-flash`, owner-pre-authorized;
- commits `a7683f0ae`, `8510a021d`, `7f0cb6bda`, `072a6b25e`.

## Unit 1 — the as-of reader class

### What the class actually is

A database value read through a reader that is not total over Datahike's four
value shapes. The run loop holds a non-current one on **every turn**, because
`seon.cluster.run/opening-db` renders each turn at its run's opening basis —
an `AsOfDB`. So a current-only reader does not degrade the system, it deletes
the agent.

It also fails silently, which is why it took two lanes to find: `select-keys`
over a value with no `:cache-context` returns `{}` without a word, and the
first complaint arrives frames later at an output contract that names the
wrong `[:or]` branch.

### Two-sided falsifier at HEAD, all four shapes

Before, on the live cluster:

```clojure
{:current  [:ok  (:datahike.cache/connection-id :datahike.cache/generation
                  :datahike.read/attributes :datahike.read/revision)]
 :as-of    [:threw "seon.db/read-evidence violated its contract …"]
 :since    [:threw "seon.db/read-evidence violated its contract …"]
 :history  [:threw "seon.db/read-evidence violated its contract …"]}
```

The observer's extension is confirmed: `history` and `since` fail identically
to `as-of`. The four-shape criterion is necessary, not speculative.

### The sweep — every seon.db reader across the four views

Measured on the live cluster, per reader per shape:

| Reader | current | as-of | since | history |
|---|---|---|---|---|
| `basis-t` | ok | ok | ok | ok |
| `commit-id` | ok | ok | ok | ok |
| `committed-value-identity` | ok | ok | ok | ok |
| `as-of` / `since` / `history` | ok | ok | ok | ok |
| `pull` / `pull-many` / `entity` / `datoms` | ok | ok | ok | ok |
| `read-evidence-current?` | ok | ok | ok | ok |
| **`database-value-identity`** | ok | **threw** | **threw** | **threw** |
| **`read-evidence`** | ok | **threw** | **threw** | **threw** |

Exactly two instances, and no third. `:cache-context` is now the only
db-internal read by map key in first-party source, and it is guarded by
`instance? DB`.

### The fix, grounded in the dependency

Datahike derives its OWN query-cache key the same way
(`reference-code/datahike/src/datahike/query.cljc:2658-2671`), so the revision
now mirrors it through the `IHistory` interface (`db/interface.cljc:118-120`)
rather than a map key:

- a committed raw `DB` by its `:cache-context`;
- an `AsOfDB` over a committed origin by the ORIGIN's context plus its fixed
  time point, via `dbi/-origin` and `dbi/-time-point`;
- since, history, filtered, and speculative by nothing at all — which is
  exactly what Datahike's `committed-value-identity` reports by returning nil.
  That absence is now STATED as `:datahike.read/cache-eligible? false` instead
  of produced as nils, and `read-evidence-current?` replays the read rather
  than comparing revisions.

`database-value-identity` now returns a flat error value naming `basis-t` as
the total reader, instead of building a map with a nil commit id and throwing.

### The half the drive's diagnosis would have missed

Copying Datahike's bound literally re-broke the drive. Its guard requires the
time point to be **strictly** past the origin's max-tx. A run renders at the
instant it opens, when its opening transaction IS the origin's max-tx, so no
as-of the run loop ever holds is strictly past — every one fell to the
no-identity arm, which the live cluster's sovereign acquired schema (predating
that arm) then refused.

The re-drive's first turn went straight back to 509 characters, and the probe
named the cause exactly:

```clojure
{:run "c9c653a5-…" :time-point 536871136 :origin-max-tx 536871139
 :strictly-past? true}   ; true NOW; false at the instant it rendered
```

An as-of value is a fixed point at ANY committed time point — its content is
the datoms with tx <= that point, and the origin advancing never changes them
— and the revision already carries the origin's commit id, so the bound is now
`<=`. Datahike's stricter one is its own cache-admission policy, not a
correctness requirement. This is a deliberate, documented divergence from the
vendored source.

### Live proof

Hot-reloading `seon.db` into the running JVM, no restart:

| Capture basis | Prompt characters |
|---|---:|
| 536871107 | 509 |
| **536871125** | **78,836** ← after the reader fix |
| 536871136 | 509 ← the strictly-past bound |
| **536871141** | **80,834** ← after the `<=` fix |

### The class regression

`seon.db-test/every-database-value-reader-answers-for-all-four-view-shapes`
drives `basis-t`, `database-value-identity`, `read-evidence`, and
`read-evidence-current?` over current, as-of, since, and history of one
database. `an-as-of-view-is-keyed-on-its-own-fixed-point` pins the revision's
meaning in both directions and pins the max-tx boundary that cost the re-drive
its first turn. Both green. This discharges the regression owed by the
archived capture-basis sibling, generalized as instructed.

## Unit 2 — the fault loop (and the refuted starvation)

### Selection was never the defect

The orchestrator's relay was right and the driver's report was wrong. The
observer's census showed exactly-once, in-order claiming at all 84 samples,
with `LIVE-DRIVE-0808-A` claimed by a run of its own. Confirmed after the
context fix, with an error backlog present:

| Message | Run | Opened |
|---|---|---|
| `inbound-536871134-0` (`LIVE-DRIVE-0808-C`) | `c9c653a5-…` | 05:36:37Z |
| `inbound-536871139-0` (`LIVE-DRIVE-0808-D`) | `d95c5c42-…` | 05:39:16Z |

Each is its own run's recorded trigger. No fairness change was made anywhere.
The reopened issue is re-archived with that refutation written into it.

### The loop, and its actual cause

Two paths committed a wake about a run's own failure, and only one was
governed:

1. `seon.error/commit-tx` — the ONE designed owner, bounded by a
   per-signature recurrence fence, and already skipping a recurrence
   escalation to the attributed agent. Its docstring names the cycle
   explicitly: error -> message -> wake -> turn -> error.
2. `seon.cluster.loop/refusal-terminal-data` — a second copy that `dissoc`ed
   the escalation dial to silence owner 1 and hand-rolled its own
   `"A run phase failed: …"` message. Unbounded, and it never asked who had
   failed. `error-tx`'s own docstring predicts this: "a second copy of it is
   how one of them quietly stops escalating."

On a single-agent cluster `:seon.config.error/escalate-to` names root and root
is the only agent — verified live, four such self-escalation messages present
— so every refused phase of root's woke root about root.

Path 2 now refuses to escalate to the agent whose run was refused.
Cross-agent escalation is unchanged.

Class regression:
`seon.cluster.loop-test/a-refused-phase-never-escalates-to-the-agent-whose-run-was-refused`,
asserting BOTH directions, because asserting only the self case would leave
the fix indistinguishable from switching escalation off.

### What I deliberately did NOT do, and why

The correct end state is to DELETE path 2 and fold its intent into
`seon.error/commit-tx`, so run-phase escalation is bounded by the same fence
as everything else. I wrote that change, then reverted it:
`seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`
pins the per-failure cross-agent escalation as intended behavior, and
`turn_test.clj` was a protected path for this lane. Recorded as the follow-on
in the owning issue, together with the fact that path 2 is still unbounded
across agents.

## Unit 3 — the re-drive

### The arc, end to end

Run `d95c5c42-7307-4484-9a32-86e30bf0e29b`, triggered by human message
`inbound-536871139-0`, opened 05:39:16Z, closed 05:40:11Z. Four forms, four
receipts, no missing receipt, two errors:

| # | Form | Outcome |
|---:|---|---|
| 0 | `(seon.db/q '[:find ?a ?v :in $ ?e :where [?e ?a ?v]] 25564)` | read the message that woke it |
| 1 | `(defn live-drive-marker … {:malli/schema [:=> [:cat] [:map …]]} …)` | **`seon.program/declaration-row violated its contract`** |
| 2 | `(live-drive-marker)` | `Unable to resolve symbol` (consequence of 1) |
| 3 | `(my.run/complete "…")` | completed, run closed |

The model read its instruction, planned four coherent forms in the right
order, and did exactly what was asked. Both failures are one system defect.

### Token profile — the 08-06 explosion, measurable again

| Measure | 2026-08-06 | 08-08 morning | 08-08 re-drive |
|---|---:|---:|---:|
| Prompt characters | 135,272 | 509 | 80,834 |
| Prompt tokens (provider) | 44,306 | 225 | **24,257** |
| Prompt cache hit | 17,792 | 0 | 2,688 |
| Completion tokens | 7,329 | 10,502 | 5,313 |
| of which reasoning | 7,179 | 9,840 | 5,077 |
| Total | 51,635 | 10,727 | 29,570 |
| Completion : prompt | 0.17 | 46.7 | **0.22** |

**Token sentinel verdict.** The inverse explosion is gone; the ratio is back
under 1. The 08-06 explosion is real but 45% smaller: 24,257 against 44,306
for a comparable turn. The observer's law holds and is now measured from both
ends — a starved prompt cost 46× its own size in reasoning, a real prompt
costs a fifth of its size. Cache hit recovered to 2,688 but is far below
08-06's 17,792, worth a look by whoever owns prompt-prefix stability.

24k for one turn on a cluster with one agent and no work done is still too
large and remains the open sentinel.

### Instruction fidelity

Partial, and honestly so. The agent completed with the map serialized as a
STRING — `(my.run/complete "{:live-drive/phase :reopened …}")` — rather than as
a value. Worth a look by whoever owns the `my.run/complete` contract render:
if the toolkit's own example shows a string, the model is copying it.

### Reproduced from other lanes' findings

- **46 forms / 41 receipts** (the observer's receipt gap): **NOT reproduced.**
  Run `d95c5c42` recorded 4 forms and 4 receipts. No evidence added to that
  issue.
- **The fault loop**: not reproduced after the fix. No unstimulated provider
  call occurred.

## New defect filed

[Let an agent define a contracted function](../../../seon/issues/an-agent-cannot-define-a-contracted-function.md)
— blocker. `seon.program/declaration-row` violates its own output contract the
moment a `defn` carries a `:malli/schema`, so the agent cannot define anything
durable and the next form fails unresolved. Three required keys are absent
from the row it builds: `:seon.ns/name`, `:seon.schema.admission/source`,
`:seon.schema/key`. Five occurrences on the cluster, two of them at boot
before any agent turn. This is the first thing a healthy agent tries and the
first thing that fails; the empty context was hiding it.

## Issues closed

- [Give an as-of database value a dependency revision](../../../seon/issues/archive/walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md)
  — resolved and archived, with both instances, the deliberate divergence from
  Datahike's bound, and the two follow-ons recorded.
- [Keep an unclaimed message out of an unrelated run's prompt](../../../seon/issues/archive/unclaimed-message-enters-an-unrelated-run-prompt.md)
  — the reopening is withdrawn on its own evidence and the note re-archived.
- [Stop a failed turn from waking itself through its own fault message](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md)
  — partial resolution appended; stays open for the second-mechanism deletion
  and the cross-agent bound.

## Gates

- `bin/test seon.db-test seon.cluster.loop-test` — 44 tests, 321 assertions,
  1 failure: `nested-native-reports-admit-reference-identities-not-database-walks`,
  an admission-size assertion untouched by this work.
- `bin/test seon.cluster.turn-test seon.cluster.armed-test seon.error-test` —
  turn-test and error-test green; armed-test's two failures reproduce
  identically with the change stashed and are pre-existing.
- `bin/test --platform` — exit 0.
- Full suite HELD per the lane spec.

## Ugly output, verbatim

**An error value becomes a cast exception one frame later.** `seon.db/basis-t`
correctly returned a flat error value for an unbound connection, and the very
next arithmetic on it produced:

```text
class clojure.lang.PersistentArrayMap cannot be cast to class java.lang.Number
```

Errors-as-values only helps if the value survives being used. This is the same
shape as the observer's `count` over an error map returning 3.

**The error names the wrong branch of its own `[:or]`.** The value that failed
was attribute-set shaped — the SECOND alternative — but the complaint reported
the first's `"should be :all"`, and the model acted on that wrong hint,
proposing `:datahike.read/attributes :all` as the fix. A multi-arm schema
should report the arm it matched furthest into, not the first.

**A set prints as a bare `#`.** In the agent's own prompt:
`{:value #, :message "should be :all"}`. Confirmed still present.

**"1 more subtree; requery refused".** Two of the five `declaration-row`
errors render as `… 1 more subtree; requery refused: no stable identity was
supplied at path [] offset 0 with :seon.render.profile/unspecified`. The
reader is told a subtree exists and then refused it, on a durable fact that
plainly has an entity identity.

**`(take 2 (seon.db/datoms db :eavt))` costs 15.5 seconds.** Uniform across
all four view shapes, so not a view defect — but two datoms should not cost
fifteen seconds, and the laziness is evidently not reaching the caller.

**A query over a HistoricalDB allocated 187 GB and hit the 30 s door limit.**
One `:find` with a single `[?e :db/ident _]` clause. Not chased to cause.

**Nested calls in a Datalog clause fail as a raw cast exception.**
`[(subs ?s 0 (min 150 (count ?s))) ?src]` returns
`class clojure.lang.Keyword cannot be cast to class java.lang.String` from
`String.compareTo`, which names neither the clause nor the restriction.

**`eval_clj`'s 30 s ceiling with no dial** remains the single biggest friction
in observing a live turn — a provider call takes 60-90 s, so every observation
must be chopped into sub-30 s polls.

## What is genuinely in good shape

- **Hot reload is the real thing.** Three separate production fixes reached a
  live cluster mid-drive with no restart, no reset, and no lost state, and
  each one's effect was visible in the very next committed fact.
- **The database made every diagnosis a query.** Captures, revisions,
  triggers, receipts, attempts, usage, and escalation recipients were all
  plain reads; not one probe needed process memory.
- **The public message boundary**: HTTP 204 in 62 ms and 175 ms, both admitted
  and immediately queryable.
- **Custody stayed clean throughout**: one open run at a time, released on
  close, across a drive that included two hot reloads of the loop's own
  namespace.
- **Errors stayed values.** Two agent mistakes in the settled run became
  durable receipts and the loop never wobbled.
- **The two lanes' reports were worth more than either alone.** The observer
  refuted the driver's starvation claim and extended its as-of finding to
  `history`; the driver found the defect the observer priced. Neither was
  right by itself, and the disagreement is what made the fix correct.

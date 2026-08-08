---
type: issue
status: resolved
severity: blocker
tags: [issue, database, render, context, live-drive]
---

# Give an as-of database value a dependency revision

## Problem

`seon.db/read-evidence` refuses every read taken against an as-of database
value, so `seon.render/walk` fails whole and the agent's ENTIRE rendered
context becomes one 127-token error string. A real DeepSeek turn then runs
with no context at all.

`seon.db/dependency-revision` (`src/seon/db.clj:256-276`) reads
`(:cache-context database)` as a MAP KEY. `datahike.db.DB` carries
`:cache-context`; `datahike.db.AsOfDB` does not. The revision map therefore
comes back with `:datahike.cache/connection-id` nil,
`:datahike.cache/generation` nil, and `:datahike.read/attributes` a set rather
than `:all`, and `read-evidence`'s output contract throws.

This is the same class as the capture-basis defect fixed in the same drive:
**reading a database value through a map key breaks for every non-current
value shape.** Datahike's own interface (`dbi/-max-tx`, `d/commit-id`) is the
reader; `:cache-context` currently has no interface equivalent.

## Evidence

Live on cluster `default` (pid 79576), 2026-08-08, basis 536871000:

```clojure
(let [db   (seon.db/db conn)
      asof (datahike.api/as-of db 536870990)
      ev   (fn [d] (try [:ok (first (seon.db/read-evidence
                                     [{:seon.db/db d
                                       :seon.db/source-argument-position 0
                                       :datahike.read/dependency-plan :all}]))]
                     (catch Throwable t [:threw (ex-message t)])))]
  {:current (ev db) :asof (ev asof)})
```

```clojure
{:current [:ok {:seon.db/source-argument-position 0
                :datahike.read/dependency-plan :all
                :datahike.read/revision
                {:datahike.cache/connection-id
                 ["28506195-c708-3720-9f98-58561569cea7" "cluster-default"]
                 :datahike.cache/generation "62b582bb-a988-4373-b6ef-4f5ebf8ce831"
                 :datahike.read/attributes :all
                 :datahike.read/revision "6a76b2d6-8a09-584d-abcd-e04d7724663d"}}]
 :asof    [:threw "seon.db/read-evidence violated its contract (invalid-output): ..."]}
```

Direct cause, same session:

```clojure
(:cache-context (datahike.api/as-of db 536870990))  ;=> nil
(type (datahike.api/as-of db 536870990))            ;=> datahike.db.AsOfDB
```

The consequence, verbatim — this is the COMPLETE prompt committed as context
capture `a7e24a23-14b7-41ab-8a96-5f3c06a9a8ee-context-536870998`
(509 characters, 127 estimated tokens, one contribution named `walk`):

```text
;; (seon.render/walk) => error
Walk failed: seon.db/read-evidence violated its contract (invalid-output): [#:datahike.read{:revision {:datahike.cache/connection-id [{:value nil, :message "missing required key"}], :datahike.cache/generation [{:value nil, :message "missing required key"}], :datahike.read/attributes [{:value #, :message "should be :all"}], :datahike.read/revision [… 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}}]
```

DeepSeek-flash was sent exactly that and, rationally, spent the whole turn
debugging it: attempt `a7e24a23-...-attempt-0` recorded 225 prompt tokens,
10,502 completion tokens of which **9,840 reasoning**, finish reason `stop`.
Every one of the thirteen forms it planned was about the error; the requested
completion value never appeared.

## Owner

`seon.db/dependency-revision` and `seon.db/read-evidence` (`src/seon/db.clj`),
with `resources/seon/schemas/seon.db.edn`'s `:seon.db/read-evidence`
declaration.

## Acceptance

- A read against an as-of, since, or history value produces a well-formed
  dependency revision — most likely one keyed on the fixed point itself, since
  such a value is immutable and can never go stale.
- `seon.render/walk` against a run's opening basis renders the full context,
  not an error.
- The revision is read through a database INTERFACE call, not a `:cache-context`
  map key, so no fourth value shape can reintroduce the class.
- One class regression asserts `read-evidence` totality across all four value
  shapes (current, as-of, since, history).

## Resolution — 2026-08-08

Commits `a7683f0ae` (the reader), `072a6b25e` (the fixed-point bound), and
`8510a021d` (the class regression).

The revision is now derived exactly as Datahike derives its own query-cache
key (`reference-code/datahike/src/datahike/query.cljc:2658-2671`), through the
`IHistory` interface rather than a map key:

- a committed raw `DB` by its `:cache-context`;
- an `AsOfDB` over a committed origin by the ORIGIN's context plus its fixed
  time point, read with `dbi/-origin` and `dbi/-time-point`;
- since, history, filtered, and speculative values by nothing, because they
  carry no committed identity — Datahike's own `committed-value-identity`
  returns nil for them. That absence is now STATED as
  `:datahike.read/cache-eligible? false` rather than produced as nils, and
  `read-evidence-current?` replays such a read instead of comparing.

One deliberate divergence, and it is the whole second half of this fix.
Datahike's cache key requires the time point to be STRICTLY past. Copied
literally, that bound excluded every as-of the run loop ever holds — a run
renders at the instant it opens, when its opening transaction IS the origin's
max-tx — so the first re-drive turn went straight back to the 509-character
error. An as-of value is a fixed point at any committed time point (its
content is the datoms with tx <= that point, and the origin advancing never
changes them), and the revision already carries the origin's commit id, so the
bound is now `<=`. Datahike's stricter one is its own cache-admission policy.

The sibling instance is fixed in the same wave: `database-value-identity`
built a map with a nil commit id for any non-committed value and threw. It now
returns a flat error value naming `basis-t` as the reader that answers for
every shape.

Live proof on cluster `default` (pid 79576), by hot reload, no restart: the
context capture went from 509 characters to 78,836 and then 80,834, and the
next two human messages each opened their own run and reached a settled
reply.

The class regression owed by the archived sibling is written and covers both
instances — `seon.db-test/every-database-value-reader-answers-for-all-four-view-shapes`
plus `an-as-of-view-is-keyed-on-its-own-fixed-point`, which pins the max-tx
boundary that cost the re-drive its first turn.

Two follow-ons deliberately NOT taken here, both recorded rather than fixed:

1. The AI projection still fails WHOLE. One render call's contract violation
   replaced the agent's entire prompt, while the HTML projection of the same
   blocks degraded per block and rendered 255 articles around 34 embedded
   errors. That asymmetry is the reason this defect was catastrophic rather
   than cosmetic, and it belongs to the render owner.
2. `:datahike.read/attributes`, `:datahike.read/revision`,
   `:datahike.read/time-point`, and `:datahike.read/cache-eligible?` are OURS
   in Datahike's namespace. Only `:datahike.read/dependency-plan` is actually
   Datahike's. The drift predates this change; the new keys follow the local
   convention rather than splitting the map across two namespaces.

## Note for whoever fixes this

Consider whether the turn should render at the run's opening basis at all. The
capture's `basis-t` does not record the as-of point: `dbi/-max-tx` on an
`AsOfDB` returns the ORIGIN's max-tx (probed: as-of 536870990 reports
536870997), so the capture id names the current basis while the content is
historical. That is a separate honesty defect in the same seam.

## Independent verification — observer lane, 2026-08-08

The observer lane reproduced this without reference to the driver's analysis
and confirms it. Three additions.

**`history` fails the same way, not only `as-of`.** One probe over the three
value shapes, on cluster `default` (pid 79576):

```clojure
{:label :current, :evidence-ok? true,
 :revision [[:datahike.cache/attribute-revisions :datahike.cache/connection-id
             :datahike.cache/generation :datahike.read/attributes]]}
{:label :as-of,   :evidence-ok? false, :cc nil,
 :err "CONTRACT: seon.db/read-evidence violated its contract (invalid-output)…"}
{:label :history, :evidence-ok? false, :cc nil,
 :err "CONTRACT: seon.db/read-evidence violated its contract (invalid-output)…"}
```

So the acceptance criterion naming all four shapes is the right one; `history`
is confirmed broken today, not merely suspected.

**The failure is silent at the point of loss, loud only much later.**
`dependency-revision` (`src/seon/db.clj:260-262`) builds its identity with
`select-keys`, and `select-keys` over a value with no `:cache-context` returns
`{}` rather than failing. The two required keys are dropped without a word,
and the first complaint arrives frames later at `read-evidence`'s output arm.
Whatever the fix, the read of the identity should refuse where the identity is
missing.

**Every prompt on this cluster is this error — five for five.** Context
captures at bases 536870998, 536871016, 536871026, 536871041 and one later are
each exactly 509 characters / 127 estimated tokens with identical content. The
agent has never once seen its instructions, its message, or its REPL.

**Two faces inside the error mislead the reader, and demonstrably misled the
model.** The value is branch-2 shaped (an attribute set), but the `[:or]`
reports branch 1's complaint, `"should be :all"`. The model spent its turn
acting on that: its plan proposed `:datahike.read/attributes :all` as the fix.
The set also prints as a bare `#` (`{:value #, :message "should be :all"}`),
which is not a legible face for a set.

**Cost of leaving it running.** Because each failed turn commits a fault
message that wakes the next turn, this defect does not fail once — it loops,
at 225 prompt tokens and ~6,700 completion tokens per lap. Four laps in four
minutes produced 26,952 completion tokens, 23,641 of them reasoning. See
[Stop a failed turn from waking itself through its own fault message](a-failed-turn-wakes-itself-through-its-own-fault-message.md).

---
type: research
status: active
tags: [research, render, context, sci, agent, curation]
---

# Session curation as transcript supersession (2026-08-04)

Question: a curator rewrites a messy run into a corrected form vector,
verifies it by re-execution on a fork, and the agent's NEXT context must
render the curated session instead of the original — as a PROJECTION
change, never history mutation.

Documents read END TO END for this lane, as instructed:
`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md`
and `src/seon/render/transcript.clj` (708 lines, whole file). Also read
completely: `src/seon/bootstrap.clj`, the run-transition owners in
`src/seon/cluster/run.clj:250-600`, and
`docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md`.

Live evidence: scratch cluster `xcurate0804` (own cluster, never
`default`), started for this lane. Every number below was produced on
that cluster today, not inferred. Probe helper committed at
`docs/prds/sci-execution-runtime/research/scripts/curation-supersession-probe-2026-08-04.clj`.

**Note on the 08-01 document's status.** Its gap 2 ("pinning is a real
change to the transcript projection") is now LANDED — commit `f3a64c38b`
"Pin bootstrap transcript prefix". `transcript.clj` today carries
`pinned-receipt-ids` (:177-186), the `::pinned?` stamp (:378-382), and a
pinned/elided/tail output shape (:540-585). The document's §1 gap list
should be read as historical from that point on. What it says about the
elision WALK direction is still exactly right, and is quoted precisely in
§2 below.

---

## 0. Headline

Three things, in order of consequence:

1. **The pinned bootstrap currently renders OUT OF ORDER in live agent
   context** — `(help)`, then the arity-error beat, then the persistence
   query, THEN `(in-ns …)`, and the refusal→repair pair at positions
   7-8. Cause: all 13 bootstrap receipts settle in the same millisecond
   and `entry-order` (`transcript.clj:361-365`) breaks the tie on the
   receipt ID STRING, so ordinal 10 sorts before ordinal 2. Filed as
   `docs/seon/issues/transcript-orders-same-instant-receipts-lexically.md`.
   This blocks the bootstrap experiment the 08-01 document exists to run,
   and it is the same ordering seam curation must plug into.
2. **Supersession is ONE seam, not scattered** — but it is not the seam
   the bootstrap-exclusion precedent uses. It must be a derived VISIBLE
   RECEIPT SET feeding both the candidate window and `history-count`,
   or the elision marker starts lying (§2.3).
3. **The renderer cannot see a fork's receipts at all** (probed), while
   result BLOBS written on a fork ARE readable from the parent branch
   (probed). So curation commits verified receipt FACTS onto the agent's
   own branch and reuses the fork's blob digests unchanged (§3).

---

## 1. How the projection selects and orders runs and receipts today

### 1.1 It does not select by run — with exactly one exception

`projection` (`transcript.clj:603-654`) is the whole selection owner. Its
inputs are one database value, one agent id, and one token budget; there
is no run argument, no ordering by run, and no notion of a current run.
Entries are gathered by AGENT:

- `recent-message-rows` (:140-154) — messages to or from the agent,
  `:order-by [?at :desc ?id :desc]`, `:limit`;
- `recent-receipt-rows` (:156-171) — every receipt of every run of the
  agent, same ordering, same limit — **except** it binds
  `?bootstrap-run-id` to `(bootstrap/run-id agent-id)` and excludes it
  with `[(not= ?run-id ?bootstrap-run-id)]`;
- `recent-comment-rows` (:173-175) — forms with no receipt whose source
  reads as zero events (comment-only input), via `comment-form-rows`
  (:112-130);
- `pinned-receipt-ids` (:177-186) — every receipt of exactly the
  bootstrap run, unlimited.

`candidate-entity-ids` (:188-213) merges the three limited streams, sorts
them descending by `[at kind-rank id]`, takes `limit`, then unconditionally
concatenates the pinned bootstrap receipt ids in front of the survivors.
`history` (:367-384) pulls the three selectors, builds entries, stamps
`::pinned?` by comparing `::run-id` to `(bootstrap/run-id agent-id)`, and
sorts by `entry-order`.

`entry-order` (:361-365) is `[(.getTime ::at) kind-rank ::id]` with
kind-rank `message 0, attempt 1, input 2, eval 3`. **Run identity is not
in the sort key.** Ordering is purely by receipt/message instant.

Measured on `xcurate0804` after seeding two synthetic runs for agent
`root` (probe helper `seed-run!`, three forms in `probe:R`, two in
`probe:C` one second later):

```clojure
{:pinned 13                            ; bootstrap:root, all of it
 :entries [["probe:R" :eval :full] ["probe:R" :eval :full]
           ["probe:R" :eval :full] ["probe:C" :eval :full]
           ["probe:C" :eval :full]]
 :elided 0 :minimum 1960}
```

Both runs render, interleaved by time. There is no supersession today and
nothing in the projection is aware that two runs could describe the same
work.

### 1.2 Where supersession plugs in

There are exactly **four** places that decide which receipts an agent
sees, and they are all inside `transcript.clj`:

| Site | Line | Role |
|---|---:|---|
| `receipt-count` | 94-104 | the DENOMINATOR — every receipt of every run |
| `recent-receipt-rows` | 156-171 | the candidate window (bootstrap excluded) |
| `pinned-receipt-ids` | 177-186 | the pinned prefix (bootstrap only) |
| `comment-form-rows` | 112-130 | comment-only forms, all runs |

The bootstrap exclusion is the existing precedent for a run-scoped filter
and it is already duplicated across two of those sites, with a third
(`receipt-count`) deliberately NOT filtered because pinned receipts must
stay in the total. That asymmetry is exactly the trap supersession falls
into (§2.3). So the honest answer to "one seam or scattered" is:

> **Today it is one FILE but four QUERIES. Supersession must not be a
> fifth hand-placed `not=`; it must be one derived visible-run rule that
> all four queries join, and the bootstrap exclusion should be re-expressed
> through the same rule rather than sitting beside it.**

That is a small refactor with a real payoff: "which runs does this agent's
transcript show?" becomes a Datalog question with one answer, which is
what the standing declared-and-queryable principle demands.

---

## 2. The elision walk, exactly — and what supersession does to it

### 2.1 The walk

The 08-01 document's §1 gap 2 says elision "walks backwards from the
newest and counts the dropped prefix into `::elided`". That is still
precisely what `projection` (:603-654) does. Exactly:

1. `total` = `history-count` = messages + **all** receipts + comment-only
   forms (:132-138).
2. `candidate-limit` = `max(6, requested-budget)` — the token budget
   doubles as a ROW COUNT (the known defect in
   `render-token-budgets-are-private-dials-no-producer-supplies.md`).
3. `entries` = `history` for that limit; `pinned` = the `::pinned?` subset
   rendered `:full` unconditionally, never budget-tested; `candidates` =
   the rest, ascending by time.
4. `unacquired` = `total - pinned-count - acquired` — the rows the row
   limit never fetched.
5. `minimum` = the token cost of the pinned prefix plus a bare marker
   claiming everything else was elided. `budget = max(requested, minimum)`
   — **the pinned prefix can never be squeezed out; it raises the budget
   floor instead.** Measured: `minimum` = 1930 tokens for a fresh agent
   with the 13-form bootstrap and nothing else, 1960 with `probe:R`/`probe:C`
   present.
6. The loop runs `index` from `acquired-1` DOWN to 0 — newest to oldest —
   accumulating `newer`. Each entry is offered as `:full` if it is within
   the last `recent-entry-count` (6, :24-28), else `best-summary`. The
   first entry that does not fit **terminates the walk**, and
   `::elided` becomes `unacquired + index + 1`: everything older stops
   being considered. Elision is therefore a strict OLDEST-FIRST prefix
   drop with a newest-first fill, exactly as documented.

Measured budget sweep on the seeded agent (13 pinned + 5 tail):

| budget | pinned | tail entries | elided |
|---:|---:|---|---:|
| 0 | 13 | — | 5 |
| 1960 | 13 | — | 5 |
| 2000 | 13 | — | 5 |
| 2050 | 13 | `probe:C` ×1 | 4 |
| 2100 | 13 | `probe:C` ×1 | 4 |
| 4000 | 13 | all 5 | 0 |

The marker text is `marker-text` (:540-543): *"N middle transcript entries
elided by the token budget"* when a pinned prefix exists, *"older"*
otherwise.

### 2.2 The `:summary` tier is inert — measured

`best-summary` (:597-601) builds `projected-entry unit entry :summary`.
`projected-entry` (:482-492) passes `detail` to `message-text`,
`input-text`, and `receipt-text` — and **all three ignore it**: their
signatures are `[unit entry _detail]` (:423, :459, :463). A `:summary`
candidate is therefore byte-identical to its `:full` candidate, so it can
only fit when the `:full` that just failed would also have fit. Confirmed
by the sweep above: no `:summary` entry ever appears at any budget. The
transcript has TWO tiers today — full or gone — not three.

This matters for the whole compaction story (§5), and its owner is the
existing issue
`docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md`,
which already owns `best-summary`'s cost; the inertness is a new
observation for that same owner rather than a second issue.

### 2.3 What supersession does to the token accounting

A curated run is normally SHORTER than what it replaces — that is the
point. Two consequences, both real:

**(a) The denominator lies unless supersession is applied to
`history-count`.** If supersession is filtered only in
`recent-receipt-rows`, then `receipt-count` still counts the superseded
receipts, `unacquired` inherits them, and the marker tells the agent
*"7 older transcript entries elided by the token budget"* when in fact
those seven were REPLACED by three that are fully rendered right there.
That is not a rounding error; it is the projection asserting a false
reason. The bootstrap gets away with the same asymmetry only because
pinned receipts are counted in `total` AND rendered, so the arithmetic
nets to zero (verified: total 18 = 13 pinned + 5 acquired, `unacquired`
0). Supersession has no such luck.

**(b) A shorter run buys tail depth, silently and correctly.** Because
the budget loop is newest-first over whatever candidates exist, replacing
seven noisy entries with three clean ones simply lets the walk reach
further back before it terminates. Nothing needs to be told about it —
provided (a) is fixed, the accounting stays honest by construction. The
curated run also shrinks `minimum` when it supersedes something inside
the pinned region, though nothing pinned is superseded in the design
recommended here (§6.5).

**(c) The row-count coupling gets slightly worse.** `candidate-limit`
counts ROWS but is derived from TOKENS, and superseded rows are fetched
and discarded. With supersession expressed inside the candidate query
(as recommended) the discard happens in Datalog rather than in Clojure,
so the limit at least applies to visible rows. This does not fix the
underlying tokens-as-rows defect, which stays with its existing owner.

---

## 3. Rendered fidelity: does the renderer care which branch or basis?

### 3.1 The renderer has no branch or basis concept at all

Every read in `transcript.clj` goes through the single `:seon.db/db`
value in the unit (:605, :369-374) and, for reasoning blobs only, the
`:seon.store/branch-connection` (:508, :524). No selector mentions a
branch, a commit ID, a basis `:t`, or a store ID; no receipt attribute
carries provenance of where it was executed. `resources/seon/schemas/seon.cluster.eval.edn`
has `id, run, ordinal, at, ns, result-edn, result-blob, result-size,
error, triage-edn, interrupted-at, output` plus `:seon.problems/id` and
`:seon.error/kind` — nothing about origin.

So the renderer does not care, and CANNOT care: **the question does not
arise, because a foreign branch's receipts are not in the database value
being rendered.** Probed directly today:

```clojure
;; fork :curate-probe-0804 from :cluster-xcurate0804, seed run "probe:F"
{:fork-has-F true :parent-has-F false
 :fork-receipt {:seon.cluster.eval/id "[\"probe:F\" 0]"
                :seon.cluster.eval/result-edn "6"}}
```

Curated receipts executed on a fork are invisible to the agent's next
context until they are COMMITTED AS FACTS ON THE AGENT'S OWN BRANCH.
There is no rendering path around this and no configuration that changes
it. That is the load-bearing constraint on the whole design.

### 3.2 The one thing that DOES cross branches: blobs

`blob/get` resolves `(:store @connection)` (`blob.clj:17-18`), and every
branch of one process-root store is backed by the SAME physical konserve
directory. Probed:

```clojure
{:mine  "/Users/sean/src/seon/data/clusters/store"
 :other "/Users/sean/src/seon/data/clusters/store"
 :same-object? false
 :my-branch :cluster-xcurate0804 :other-branch :cluster-opusns}
;; put! through the other branch's connection, get through mine:
{:digest "aa6bbcc32b…" :read-back-from-my-branch "cross-branch blob probe 2026-08-04"}
```

A blob written through a fork's connection is readable through the
parent's connection, byte-identical, because blob keys are content
digests in one shared backing. **So a curated receipt may carry the
fork's `:seon.cluster.eval/result-blob` digest unchanged** — the large
values do not need to be re-transacted or re-executed on the parent, only
the small fact rows do. This is what makes the design cheap.

Caveat to record rather than solve: konserve GC is an open hazard in this
program. If a blob-collecting sweep is ever scoped to a branch's reachable
set, a curated fact row on branch A pointing at a digest first written on
retired branch B is exactly the shape that would break. Anyone building
GC must treat digests as store-wide, not branch-local.

### 3.3 Assumptions that would make a foreign receipt render wrongly

Given the facts must be re-committed anyway, these are the fidelity
hazards to respect when writing those rows:

1. **`form-sources` joins by RUN + ORDINAL, not by receipt ref**
   (:215-229): `[?receipt :seon.cluster.eval/run ?run] [?receipt … ordinal]
   [?form … ?run] [?form … ordinal]`. A curated receipt whose ordinal does
   not have a matching frozen form entity on the SAME run renders with
   `::source` nil, i.e. a prompt line reading `my.agents.x=> ` with no
   form. Curated receipts must be committed with their run's frozen plan,
   through the ordinary `run/plan-tx` path — not as bare receipt rows.
2. **Identity is derived from the run id.** `plan-call` builds
   `form-id = (pr-str [run-id ordinal])` (`run.clj:452`) and
   `receipt-start-call` builds the same shape for
   `:seon.cluster.eval/id` (`run.clj:531`). Both are `:db.unique/identity`.
   A curated run with its OWN id therefore collides with nothing; a
   curated run reusing the original's id would UPSERT onto the original
   receipts — that is history mutation and is forbidden by the framing.
   Distinct run id is not a convention here, it is the mechanism.
3. **`:seon.cluster.eval/at` decides position.** Nothing else orders the
   transcript (§1.1). Stamping curated receipts at curation time places
   the whole curated run at the END of the session, after work that
   actually happened later. See §6.4.
4. **`:seon.cluster.eval/ns` must be present** or `input-entry`/receipt
   prompts fall back through `:seon.cluster.run.form/ns` to the agent's
   namespace to `user` (:352-359, :331). The fork's evaluation knows the
   real namespace-in-effect; carry it.
5. **`:seon.cluster.eval/result-size` must be the ORIGINAL size**, not the
   truncated string's length. `capped-result?` (:309-317) derives the
   "this was capped" face from `result-size > (count result-edn)`, and
   `receipt-settle-tx` will silently default size to the string length if
   the caller omits it (`run.clj:568-571`). Copy the fork's size.

None of these is about the branch; all of them are about writing a
complete receipt. The renderer is branch-agnostic and stays that way.

---

## 4. Agent experience: keeping an instructive failure

The concern is real and correctly stated: a silently swapped history
removes "I tried X and it failed", and a model that never sees the
failure can walk back into it. The proposal — the curator KEEPS one
instructive failed form when it is pedagogically load-bearing — is the
right shape. The finding is that **it needs no marking mechanism at all,
and adding one would be a defect.**

Evidence:

- A kept failure is simply a form in the curated sources vector that
  genuinely fails again on the fork. The plan fold continues past eval
  errors (08-01 §1, `loop.cljc:1436`), so a failing form does not abort
  the curated run; it settles a receipt carrying `:seon.cluster.eval/error`
  and, where present, `:seon.error/kind`, `:seon.cluster.eval/triage-edn`,
  and `:seon.problems/id`. `receipt-entry` (:319-341) already lifts all of
  those, and `receipt-text` (:463-480) renders them through the declared
  producer `seon.cluster.run/render-receipt-ai`. The failure face the agent
  sees is the real one, not a description of one.
- This is exactly the mechanism the bootstrap already relies on: its
  forms 8→9 are a deliberate admission refusal followed by its repair
  (08-01 §3), carried by ordering alone. Verified live in the shipped
  bootstrap on this cluster — receipts 7 and 8 are the two `defn largest`
  forms, the first refused, the second admitted. No attribute distinguishes
  them; adjacency does.
- **Nothing today marks a receipt as instructive**, and nothing should.
  A `:seon.curation/pedagogical? true` flag would be stored derived state
  in the exact banned shape: it is not a fact about what happened, it is a
  note about why the curator chose an ordering, and it would immediately
  want a renderer that says "note: this failure is kept on purpose" —
  comment-prefixed prose about output, which the comment grammar forbids.

So: **instructiveness is expressed by INCLUSION AND ORDER in the curated
sources, and by nothing else.** Failure-then-repair adjacency is the
lesson; the curator's judgment lives in what it chose to keep, which is
already fully recorded — the curated run's frozen plan IS that decision,
queryable form by form.

One genuine gap worth naming: an agent has no way to ask "what did the
curator drop?". The superseded run's facts survive untouched on the
branch, so the answer is one query away — but no agent-facing function
exposes it. That belongs with the same missing surface the 08-01 document
already defers (§7 Q3: agent-facing read by `:seon.cluster.eval/id`), and
should not be invented separately for curation.

---

## 5. Is supersession the same projection mechanism compaction needs?

**Partly — and the distinction is worth keeping sharp, because collapsing
them produces a worse design.**

Argued from `projection` (:603-654), which has exactly two levers:

- **Lever 1 — MEMBERSHIP.** `candidate-entity-ids` + `history-count`
  decide which facts are eligible at all.
- **Lever 2 — FIT.** The newest-first loop decides how many eligible
  entries survive the budget and at what detail.

Supersession is lever 1: it is not budget-driven, it does not vary with
the token bound, and a superseded entry must not be counted as
budget-elided (§2.3). Compaction as ruled is lever 2: it elides earlier
work by budget and *"elided entries keep their receipt identities visible
so anything remains one re-query away"*
(`plan/repl-session-context-2026-08-01.md:143-146`). Superseded entries
are not "one re-query away from the window" in that sense — they are not
part of this session's story any more, though their facts remain queryable
forever.

So they are NOT one rule. They ARE one mechanism in the sense that
matters:

1. **The projection is already a pure function of (database value, agent,
   budget) with nothing stored.** Both compaction and curation are
   therefore just new FACTS that change the input. Neither needs a job,
   a queue, a stored window, or a rewrite pass. That is the strongest
   argument for doing curation as supersession at all, and it is visible
   directly in the code: there is no cache, no memo, and no persisted
   projection anywhere in the file.
2. **Both need the same currently-missing piece: a REGENERATION
   BOUNDARY.** The compaction ruling's point is that the window should be
   recomputed at discrete boundaries so the prompt-cache prefix is stable
   between them; today `projection` is recomputed on every render (twice
   per block, once for `render-ai` and once for `render-html`, :666, :673).
   A committed curated run is exactly such a boundary — the same class of
   event as crossing the token bound. One boundary concept serves both.
3. **Both need the detail policy that §2.2 shows is inert.** Compaction
   without a working `:summary` tier is just truncation; curation benefits
   from the same tier when a superseded-but-referenced entry should print
   small rather than vanish.

Recommendation: implement supersession as lever 1 now (it is small,
independent, and does not touch the budget loop), and let it establish the
regeneration-boundary vocabulary that compaction will reuse. Do not try to
express supersession as a budget rule; a curated run that is longer than
the original would then be silently dropped by the budget, which is
absurd.

---

## 6. Recommended design

### 6.1 One fact: a run supersedes a run

Declare on `resources/seon/schemas/seon.cluster.run.edn`:

```clojure
:supersedes :seon.db/ref     ; the run this curated run replaces
```

A ref, on the CURATED run, pointing BACKWARD. Not a status on the
superseded run — that would be mutation of the record of what happened,
and it could not express a chain.

Visibility is then derived, not stored, and handles chains for free:

```clojure
;; a run is visible when nothing supersedes it
[?run :seon.cluster.run/agent ?agent]
(not-join [?run] [_ :seon.cluster.run/supersedes ?run])
```

C1 supersedes R, C2 supersedes C1 ⇒ only C2 is visible, with no epoch, no
generation counter, and no cleanup pass. "What was the original?" is a
ref walk. "What has been curated?" is a query. This satisfies the
declared-and-queryable principle with one attribute.

### 6.2 One seam: a visible-receipts rule shared by all four queries

Add one Datalog rule (or one private helper producing the same clause set)
used by `receipt-count`, `recent-receipt-rows`, `pinned-receipt-ids`, and
`comment-form-rows`. Re-express the bootstrap exclusion through it rather
than beside it: the bootstrap run is visible-and-pinned, a superseded run
is not visible, everything else is visible-and-not-pinned. That removes
the duplicated `not=` and makes the four sites structurally incapable of
disagreeing — which is the specific failure §2.3 predicts.

Acceptance for this seam: with a superseded run present, `total`,
`::elided`, and the rendered entries agree, and the marker never counts a
replaced entry as budget-elided.

### 6.3 Verification provenance on the curated run

Record what the curation was proven against, in the dependency's own
vocabulary (not an invented "coordinate" map): the fork's branch and the
`:datahike/commit-id` the fork was created from. Two plain attributes on
the curated run entity. This makes "was this curated session actually
re-executed, and against what?" a query, which the grader story needs
anyway.

### 6.4 Timestamps: inherit the superseded run's position

Curated receipts must NOT be stamped at curation time, or the curated
session jumps to the end of the transcript, after work that genuinely
happened later (§1.1 — `entry-order` is time-only). Stamp the curated run
`:seon.cluster.run/opened-at` and its receipts' `:seon.cluster.eval/at`
from the superseded run's own window, so the curated session occupies the
position of the session it replaces. This is honest: it says "this is what
that stretch of the session amounts to", which is the whole claim.

That requires the ordering fix in §6.5 to be landed FIRST, because
same-instant receipts are precisely what this produces.

### 6.5 Prerequisite: fix `entry-order`

`entry-order` (:361-365) must break same-instant ties by the run's
`opened-at` and then by the receipt/form ORDINAL (an integer already on
every receipt and form), falling back to id only for messages. Without
this, any curated run committed in one transaction renders scrambled —
and, as §0 records, the shipped bootstrap is scrambled TODAY for exactly
this reason. Issue:
`docs/seon/issues/transcript-orders-same-instant-receipts-lexically.md`.

The bootstrap must not be superseded, incidentally: it is pinned, it is
system-authored, and re-curating it would mean the agent's teaching prefix
drifts per agent. Curation applies to the tail.

### 6.6 What NOT to build

- No `:seon.curation/pedagogical?` marker (§4).
- No stored/compacted transcript, no rewrite pass, no second projection.
- No branch or basis awareness in the renderer (§3.1) — commit the facts
  instead.
- No re-transacting of blob CONTENT: reuse the fork's digests (§3.2).
- No supersession expressed through the budget loop (§5).

---

## 7. Ugly or friction-producing output met while doing this work

Reported per the standing order; none is this lane's to fix.

1. **The pinned bootstrap renders out of order** — §0 item 1. The most
   consequential ugly output found: the one artifact the whole bootstrap
   design is about is scrambled in every agent's context today. Issue
   filed.
2. **A config contract violation prints ~5 KB of `missing required key`.**
   Calling `seon.config/result-caps` with a database value instead of the
   effective settings map produced a single-line message enumerating every
   config dial, blob-elided mid-word (`"…search-api-key-varia"`). The
   message names none of what was actually passed. Belongs with the
   existing predicate/contract-diagnostics work
   (`predicate-schema-violations-humanize-to-unknown-error.md`).
3. **A render unit has no public constructor.** Assembling one by hand to
   probe the transcript took five successive contract failures to
   discover the seven required keys (`:seon.db/db`,
   `:seon.store/branch-connection`, `:seon.cluster.agent/id`,
   `:seon.sci.eval/ctx`, `:seon.sci.admit/caps`, `:seon.sci.eval/time-limit-ms`,
   `:seon.config/on-core-error`, plus a `:seon.render.call/captured-reads`
   atom). Each failure named one missing key. Friction for every future
   render probe.
4. **The `:summary` detail tier renders identical bytes to `:full`**
   (§2.2) — an inert middle tier that costs a full re-serialization per
   probe. Owner: `render-token-budgets-are-private-dials-no-producer-supplies.md`.

## 8. Probe inventory

- `research/scripts/curation-supersession-probe-2026-08-04.clj` — `seed-run!` (open/claim/plan/
  start/settle a whole synthetic run) and `projection-shape`.
- Cluster `xcurate0804`, agent `root`; synthetic runs `probe:R` (3 forms),
  `probe:C` (2 forms), `probe:M` (12 forms at one instant), and fork
  branch `:curate-probe-0804` (retired after the probe).
- Every table and code block in §1-§3 is output from those probes.

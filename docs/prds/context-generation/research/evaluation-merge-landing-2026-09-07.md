---
type: research
status: complete
date: 2026-09-07
tags: [research, run-loop, datahike, storage, repl, render]
---

# One entity per (run, ordinal) — 2026-09-07

Landing note for the `evaluation-merge` program step, against
[the agent record and the REPL response](../plan/agent-record-and-repl-response-prd-2026-09-07.md)
§5 and §7 step 2, and the deletion half the
[prompt-and-entity-merge landing note](prompt-and-entity-merge-landing-2026-09-07.md)
§4 left open.

## 1. What merged

The frozen form and its receipt were two entities with two
`:db.unique/identity` attributes describing one thing. They are one
`:seon.cluster.eval` entity now: `run/plan-call` mints it with `source`,
`comment`, `ns`, `author`, `ordinal` and `at` and NO terminal fact, and
`run/receipt-settle-call` accretes the terminal facts onto that same entity.
`run/terminal?` is unchanged — absence of a terminal fact is still exactly
"running".

Deleted with the family:

- `resources/seon/schemas/seon.cluster.run.form.edn` and every
  `:seon.cluster.run.form/*` reference (791 references across 74 files at
  `1e330e133`, 0 at HEAD);
- `:seon.cluster.run/forms` and the component back-edges `plan-call` wrote
  purely so a reverse walk would be a component;
- `run/form-identity` and the qualified-string workaround for the ambiguity
  class documented at the old `run.clj:694-718`. `receipt-identity` is the
  one derivation, and a derived identity string now names at most one entity
  by construction rather than by convention;
- `loop/form-data`;
- the two-spellings arm of `repl/entity-emission`;
- `transcript/undisposed-run-text`'s own `system=> (pr-str form)` grammar
  (the audit's third): the entry says what the run's own `:seon.render/ai`
  says, and the run's evaluations render through `seon.repl/text` like every
  other entry;
- `run/generation-complete-call` / `-tx` and
  `:seon.cluster.run/generation-complete-request` — see §4.

## 2. What collapsed rather than being renamed

- **`plan-call` mints the evaluations.** The loop used to freeze the plan
  (form rows, first ordinal derived INSIDE the transaction from the run's
  existing form count) and then mint the receipts in the same transaction
  from `(range)` — starting at 0 regardless. A run that had generated
  ordinals before its model call therefore froze forms at 1..n and
  evaluations at 0..n-1: the twin disagreed with itself about which ordinal
  a source belonged to. One entity minted at one place cannot.
- **`work/next-ordinal` is one query.** It was two result sets joined in
  Clojure because the source lived on one entity and the terminal facts on
  the other.
- **`loop/fold-evaluations` replaces `form-data` + `fold-namespace`.** The
  resumed fold reads the run's evaluations ONCE and takes both the ordered
  sources to resume and the namespace in effect (the greatest settled
  predecessor's `:seon.sci.eval/ending-ns`) from that one read, instead of
  a pull per ordinal plus an ordered query.
- **`run/refresh-call`** pulled the prior form and then pulled its receipt by
  a second identity; it reads one entity, and the run it appends now mints
  its evaluation rather than a form with no evaluation — which
  `receipt-settle-call` would have refused as `::no-such-receipt`.
- **`run/record-evaluated-call`** compared `::sources` and `::evaluations` as
  two recorded families; it compares one, and `recorded-evaluation` gained
  `author` and `comment` so the comparison did not get weaker.
- **`transcript`'s `:input` entry kind is gone.** `comment-form-rows` selected
  frozen forms with no receipt at the same ordinal; with one entity that
  not-join is self-satisfying and always empty. A comment-only source is now
  an ordinary evaluation that never settles, which `repl/text` already renders
  as a comment and a prompt line with no response. `form-selector`,
  `form-sources`, `input-entry`, `recent-comment-rows` and the `:input`
  ordering arm went with it.
- **`work/form-settlement`** derived `:unevaluated` from "no receipt entity".
  It derives it from "no `:seon.cluster.eval/at`" — frozen and never started.
  All seven states survive; `every-form-has-exactly-one-of-the-seven-derived-states`
  still asserts all seven in order.

## 3. Datoms per (run, ordinal), measured

Measured on the lane's scratch cluster `merge-step`
(`bin/seon --root tmp/merge-step-root start merge-step`) at a fresh boot, on
the root agent's bootstrap run — 1 run, 4 ordinals — by
`(count (datahike.api/datoms db :eavt eid))` per entity.

| | before (`4f8cd788f`) | after (`caef3850e`) |
|---|---|---|
| form entities | 4 / 24 datoms (6.00 each) | 0 / 0 |
| evaluation entities | 4 / 107 datoms (26.75 each) | 4 / 107 datoms (26.75 each) |
| `:seon.cluster.run/forms` back-edges on the run | 4 (1 per ordinal) | 0 |
| the run entity itself | 12 datoms | 8 datoms |
| **per (run, ordinal)** | **33.75** | **26.75** |
| **the whole run** | **143** | **115** |

**7.00 datoms per (run, ordinal) removed, none added** — 20.7%. The evaluation
entity's own datom count is IDENTICAL before and after (107 for four
ordinals), which is the point: every attribute the form entity carried was
already on the evaluation, so the merge is pure deletion. That is 1.00 datom
more than the landing note of the morning predicted (it counted the form
entity's 6 and not the run-side component back-edge each form also cost).

`:seon.cluster.run.form/*` attributes installed on the fresh cluster after
the change: **none**. `:seon.cluster.run/forms` installed: **false**.

### 3.1 The invariant, live

After seeding the Juniper fixture on the same scratch cluster (nine runs,
nine ordinals, all generated openings):

```clojure
{:runs 9 :evals 9 :pairs 9 :max-entities-per-run-ordinal 1}
```

and the debug AI feed
(`/feed/juniper?debug=true&output=:seon.render/ai`, 182 `#:seon.repl{…}`
responses) contains **zero** `system=>` lines and **zero** `"Renderer
unavailable."` sentences. The third grammar is gone from a live page, not
only from the source.

## 4. `generation-complete` was dead in production

PRD §6 deletes `generation-complete-call` together with
`:seon.cluster.work/situation`. It is deletable on its own, and the reason is
stronger than the PRD's: **no production path ever reached the edge it
wrote.**

`generation-complete-call`'s whole body is the `:generate` → `:call`
situation edge plus its fences. Its only caller is `loop/generate-turn`, in
the arm `(if bootstrap? close generation-complete)`. `bootstrap?` is
`(= run-id (bootstrap/run-id agent-id))`. A `:generate` run exists only
through `run/generated-run-tx`, whose only production caller is
`seon.bootstrap/seed-tx` (`bootstrap.clj:796`), and `seed-tx` binds
`id` to `(run-id agent-id)` (`bootstrap.clj:770`). So `bootstrap?` is always
true and the other arm was unreachable.

A generated run that has nothing left to generate closes. The two tests that
exercised the dead edge —
`seon.cluster.run-test/an-agent-plan-appends-after-the-settled-generated-prefix`
and the `generation-complete-tx` setup inside
`seon.cluster.loop-test`'s refused-prompt test — are deleted and rewritten
respectively; the refused-prompt test now opens an ordinary claimed run,
which is what it was actually testing.

## 5. `:seon.cluster.work/situation` — three options, not implemented

See the report; this note records the archaeology.

**It is two different things under one keyword.**

1. **The instruction**, the `:dispatch` key of the `:seon.cluster.work/next`
   multi-schema, values `:resume :call :generate :open :close`. It is owner
   design, sealed in `0e1e9dc01` (2026-07-27) with the ruling in its own
   message: *"an instruction must be visible in the VALUE, never inferred
   from an absent key"*. Nothing in this program argues against it.
2. **The stored attribute** on the run entity, values `:call` and
   `:generate` only, written by `run/open-call` (defaulting to `:call`) and
   by `generated-run-tx`. THIS is the `:type`/`:kind` stamp PRD §6 wants
   gone. Its five readers are `plan-call` (`::not-call-situation`),
   `append-generated-call` (`::not-generate-situation`), `recover-call`
   (`generated?`), `loop/claim-orphan` and `work/next-agent-work`.

**The PRD's stated replacement does not hold.** Note 3 §5.4 says
"`:generate` is exactly system-authored evaluations and no `plan-digest`".
A generated run is opened with ZERO evaluations (`generated-run-tx` opens and
claims and nothing more), and a fresh ordinary run before its model reply
also has zero evaluations and no digest. Authorship cannot separate them at
the one moment the loop must, which is why this lane did not implement the
deletion.

**The three options, simplest first.**

**Option 1 — delete the stored attribute; derive `:generate` from the run's
own deterministic identity.** `generate` runs are exactly bootstrap runs
(§4): `(= run-id (bootstrap/run-id agent-id))` already decides the loop's
only branch on it. `next-agent-work`, `plan-call`, `append-generated-call`,
`recover-call` and `claim-orphan` read that instead of a datom.
*Guarantee:* the returned work value is unchanged, byte for byte; the
`:multi` and its five branches are untouched; one attribute and 88
references die.
*Cost:* about a day. `seon.cluster.run` and `seon.cluster.work` gain a
dependency on `seon.bootstrap`'s id derivation (`seon.bootstrap` already
requires `seon.cluster.run`, so the derivation must move down, not the
require up).
*What we give up:* a generated run that is not a bootstrap run becomes
unrepresentable — true today, but by accident rather than by contract. And
it substitutes a NAME-SHAPED derivation for a stamp, which is the second of
the three banned substitutes in AGENTS.md §2.2. Not recommended for that
reason alone.

**Option 2 — delete the stored attribute; make the generator a REF, and read
its presence.** `generated-run-tx` asserts `:seon.cluster.run/generator`
pointing at the entity that fills the run (the bootstrap task message it
already carries as its trigger, or the agent). Presence = generate, absence =
call, which is the same shape as `:seon.cluster.run/process` for custody and
`:seon.cluster.run/closed-at` for openness.
*Guarantee:* no enum stamp, no naming convention, no protocol-shape change;
the instruction stays exactly as sealed in `0e1e9dc01`; `next-agent-work`'s
two arms become attribute-presence reads; every existing refusal keeps its
name and its meaning.
*Cost:* about a day and a half. One new attribute in `seon.cluster.run.edn`,
one write site, five read sites, and the boot-installability proof
(`committed-attributes`) picks it up for free because it derives from the
declared entity map.
*What we give up:* an attribute is added where the PRD asked for a deletion —
though it removes an enum and its five-value schema, so the net is a
`:type`-stamp traded for a connection. **Recommended.**

**Option 3 — what PRD §6 literally says: derive `:generate` from authorship
and digest absence.** For that to be decidable, a generated run must be
opened WITH its first generated evaluation, so that "system-authored
evaluations and no plan-digest" is true from the first instant.
`bootstrap/seed-tx` would derive its first entry inside the open
transaction, and `append-generated-call`'s `expected == ordinal` fence would
count from one instead of zero.
*Guarantee:* authorship genuinely says it; no new attribute, no convention,
and the deletion the PRD asked for lands exactly as written.
*Cost:* two to three days. It reorders the boot path: `bootstrap/next-entry`
must run before the run it belongs to exists, which today reads the run to
decide what comes next. That is a real inversion, not a rename, and it
touches the one path whose failure mode is "the cluster does not boot".
*What we give up:* the ability to open a generated run before knowing its
first form, and — for the duration — cheap confidence in the boot path.


## 6. Gate tallies

Every attribution below is measured in a detached worktree at
`3f8801d62` — the commit immediately before this step's first commit —
`tmp/merge-step-baseline`, `reference-code` symlinked to the main tree's
submodules. Nothing is asserted.

### 6.1 `seon.render.transcript-test` at the baseline

**19 tests / 288 assertions / 27 failures, 0 errors — nine red tests:**
`a-tight-budget-degrades-then-elides-loudly`,
`every-generated-history-is-ordered-total-and-token-bounded`,
`malformed-receipt-bytes-and-any-unique-about-stay-replayable`,
`one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`,
`populated-history-restores-the-repl-fidelity-checklist`,
`receipt-content-enters-the-shared-capped-floor`,
`same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order`,
`supersession-chains-vanish-before-token-accounting`,
`tight-budgets-pull-only-a-budget-derived-newest-candidate-set`.

Eight are the disabled-rendering-limits and message-sentence families
`repl-grammar-greens-2026-09-07.md` §1.2 already recorded. The ninth,
**`one-reply-reads-identically-…` — this program step's named PROOF — was
ALREADY RED at the baseline**, turned red by the `print-and-admission` lane's
`3f8801d62` (a `set!` of `*print-length*` now survives the next form). That
lane's own landing note §3.2 names the repair and hands it here because
`src/seon/render/transcript.clj` and its test belong to this lane:
`receipt-selector` never pulled `:seon.print/length` / `/level` although
`bounded-result` reads them, so every store-derived render printed under the
shipped default while the in-memory one printed under the agent's choice.
Both halves are done here: the two attributes are in the selector, and the
two assertions that stated the old defect now state the ruled behaviour.

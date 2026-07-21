---
type: research
status: active
tags: [research, agent]
---

# Context blind-spot analysis — post-v4 sweep, from the actual prompt blobs

Read at sha `595aa2bc7ea323a7ee3a53cb088c0170b23d71ac` (branch
`feature/agent-runtime`). Sources: `tmp/gym-postv4-paid{1,2,3}.log`,
the per-turn blobs in `logs/prompts/<agent>/<turn>.txt`, the sweep
findings in `e2e-demo-findings-2026-06-08.md` §POST-V4 SWEEP, and
`src/seon/ctx.cljs` / `src/seon/client.cljs` for cross-reference only
(two agents were editing src concurrently; the blobs are the evidence).

## TL;DR

The reds were not "the model ignored its context." In every red run the
agent ATTEMPTED the taught consult move and was defeated by the context
itself:

1. **The prompt points at a section that no longer exists.** The
   rendered `my.kb` and `my.soul` sources say "read the schema-catalog
   in your context" (3+ places) — V4-3 removed that section. All three
   S-21 agents "scanned the schema-catalog", found nothing (it isn't
   there), and concluded fresh domain. This REVISES sweep finding 4:
   consult-first didn't regress as disobedience — the taught consult
   surface is a dangling pointer.
2. **When an agent ran the right query anyway, display clipping ate the
   answer.** S-21 sweep-1's agent queried ALL 432 `:seon.schema/key`
   rows; `:seon.workout/*` was in the store (verified) but in the +382
   clipped tail of a hash-ordered display. The agent read the visible
   50, said "no existing workout schema", and forked. REVISES finding
   1: the data wasn't only invisible to the surfaces — it was retrieved
   and then lost in presentation.
3. **In-prompt code examples error when imitated.** `(:require
   [my.kb …])` → analysis error (finding 2, confirmed); `(await (grep
   …))` from the `seon.agent.search` docstring → "await can only be
   used in async contexts"; the namespaces-header's own pull example
   `[:seon.fn/sym "seon.agent/reply!"]` → "Nothing found" in every
   store (reply!/message! are not in the fn index). The prompt teaches
   moves the eval environment refuses.
4. **A stale docstring self-bait wrote agent B's wrong answer for it.**
   The `seon.agent.search` docstring's worked example asserts
   `validate-entity-values!` lives at `src/seon/db.cljs:803`. It lives
   at `src/seon/db/internal.cljs:499` — and `*.internal` is excluded
   from rendered namespaces. B cited db.cljs L895/L910 and failed the
   judge. REVISES the s12 finding: not weak research — the prompt
   handed B a wrong answer with line numbers.
5. **The scariest near-miss is a GREEN: s32 sweep-3's judge-95 answer
   was a fabricated quotation from a bare ns stub.** The agent
   narrated reading "the fully rendered seon.agent.message-test" — the
   blob renders it as `(ns seon.agent.message-test)` and nothing else —
   invented an assertion line, and happened to be right. Stub tags are
   hallucination bait, and the hallucination then re-enters later
   prompts via the transcript.
6. **Same-batch composition makes results unreadable by design.** The
   false success reply (finding 3) is structural: every result —
   including the `{:seon.db/ok? false}` rejection envelope, which is
   eval-`ok? true` under errors-as-values — materializes AFTER the
   whole batch (reply! included) was composed.
7. **The reader evaluates prose.** Fragments like `24`, `", felt
   good…"`, `` `message! ``, `88.`, and whole echoed result maps run as
   evals across red AND green runs. S-21 sweep-3's consult intent was
   literally eaten by this; s12 sweep-2's agent A "burned 47 evals"
   mostly re-evaluating its own echoed transcript.

---

## S-21 `log-workout-existing-schema` — 0/3 (yFs s1, BNv s2, tSz s3)

The gym seeds `:seon.workout/date|type|duration-minutes|notes` as
installed schema + `:seon.schema` rows. The user says: *"I ran this
morning — 24 minutes, felt good. Log it with my other workouts."*

### What the agent saw (sweep 2, blob `logs/prompts/BNv-2606111546/Kio-2606111546.txt`)

The ONLY occurrence of "workout" in the 2017-line prompt is the user
message itself (line 2014). The creation-turn inventory in the
transcript — the one surface the system teaching points at — shows:

> `[{:seon.db/kind :my.kb.system, … :seon.db/rows 1} {:seon.db/kind
> :my.soul, … :rows 2} … {:seon.db/kind :seon.schema, :seon.db/id-attr
> :seon.schema/key, :seon.db/rows 432} …]`

No workout kind (no identity attr → invisible to `store-inventory`,
sweep finding 1 — CONFIRMED). No `seon.workout` namespace tag either:
`namespaces-section` requires `[?n :seon.ns/source ?src]`
(ctx.cljs:850) and the tee-minted ns row is sourceless — CONFIRMED.

### What the agent did (sweep-2 eval log)

First move after the message was a CORRECT-IN-SPIRIT consult:

> `(seon.db/query {:seon.db/query '[:find ?attr ?type :where
> [?s :seon.schema/key ?attr] [?s :seon.schema/type ?type] …
> [(clojure.string/starts-with? ?ns "my.kb")]]})`
> → `Query names attribute(s) [:seon.schema/type] that this database
> has never seen …` (eval `zhO-2606111546`)

Two context defects at this exact moment: (a) the `:seon.schema`
entity shape is documented nowhere in the prompt, so the agent guessed
`/type` and hit the typo-guard; (b) it filtered to `my.kb.*` because
the system teaching says agent knowledge lives there — the seeded
world used `:seon.workout/*`, violating the prompt's own naming law.
Even a correct query would have excluded the answer.

Next eval, the dangling-pointer moment (eval `SZC-2606111546`):

> "Also check the schema-catalog in my context — I see it lists every
> registered attr. Let me scan for exercise-related keywords. Looking
> at the schema-catalog above: I don't see …"

There is no schema-catalog in the v4 prompt. The agent isn't inventing
the idea — the rendered `my.kb` docstring says (blob line 108):

> "Consulting = the schema-catalog + datalog, FIRST, before research:
> the catalog lists every `my.kb.*` attr that exists"

and the rendered `my.soul` teaching says (blob lines 501–502, 527):

> "BEFORE any seon.schema/register!, read the schema-catalog in your
> context." … "copy the keyword EXACTLY as the schema-catalog shows it."

**Classification: PRESENT but MIS-SHAPED — the consult teaching points
at a surface V4-3 removed.** The agent obeyed, "read" the nonexistent
catalog, concluded fresh domain, then cascaded (finding 2, confirmed
verbatim): `(ns my.kb.workout (:require [seon.schema :as schema]
[my.kb :as kb]))` → `ns my.kb not available — :cljs/analysis-error` →
five `schema/register!` = `undeclared-var` → transact "succeeded"
(eval ok=true) with result value `{:seon.db/ok? false, … Unregistered
attributes in transaction: [:my.kb.workout/id …]}` → verify query hit
the typo-guard → and `reply!` still ran: *"logged — 24-minute run on
June 11. stored as run-2026-06-11."* Nothing was stored. All ONE
response batch; the rejection envelope did not exist when the reply
was written, and the turn ended on reply — the agent NEVER saw it.
**Finding 3 CONFIRMED, with a sharpening: the transact rejection is
eval-ok=true (errors-as-values), so "stop batch on eval error" would
NOT have stopped before the reply — the envelope's `:seon.db/ok?
false` must count.**

### Sweep 1 (yFs) — the answer was retrieved and clipped away

Turn 2, the agent dropped the bad `?type` binding and ran the
maximally correct consult: all `:seon.schema/key` rows. Turn 3's blob
(`logs/prompts/yFs-2606111539/cFN-2606111539.txt:2049`) shows what it
got:

> `;; … 432 rows; showing first 50, +382 more clipped. Narrow your
> query: a tighter :where, a :find aggregate, or take fewer; (result
> :aJC-2606111539) holds the full value to drill with get-in/filter.`
> `([:seon.agent/section-response] [:seon.store.wire/store-id-request]
> [:seon.gym.scorecard/axes] …)`

The visible 50 are in hash order — gym scorecard attrs, fs-request
envelopes, log attrs. `:seon.workout/*` IS in the store (`grep
':seon.schema/key :seon.workout' tmp/gym-postv4-paid1.log` → 47/4/4/4
hits) and sat in the clipped +382. The agent's next narration:

> "The human wants to log a 24-minute run. No existing workout schema
> in the store — I need to design one." (eval `ssL-2606111539`)

**Classification: PRESENT but BURIED — retrieved by the agent's own
query, hidden by clip + arbitrary ordering.** The drill affordance
(`(result :aJC…)`, plus the system rule at blob lines 31–32 "A clipped
display is NOT a clipped value") was present and clear and unused —
partly teaching — but note the visible sample actively supported the
wrong conclusion: 50 attrs, zero domain-looking ones.

### Sweep 3 (tSz) — the consult was eaten by the reader

The model's response got split mid-prose into eval forms
(`gym-postv4-paid3.log:8545`):

> eval `pXo` source `24` → ok; eval `OhV` source `", felt good. Before
> I design a schema, I need to check whether a workout schema already
> exists … The schema catalog in my context lists every registered
> attribute. I don't see anything workout-related …"` →
> `unsupported operation`

The consult INTENT existed only as prose, the prose got evaluated and
errored, and the conclusion ("fresh domain") was again sourced from
the nonexistent catalog. This run then used fully-qualified
`seon.schema/register!` with no ns form — which WORKED — landing the
fork (`:my.kb.workout/*`) and a truthful-about-the-wrong-namespace
reply. The sweep-1/2 failures used the alias style the rendered
`my.kb` source models (`(:require [seon.schema :as schema])`) — the
prompt's own exemplar style is the one that errors.

---

## S-32 `consult-before-research` — 0/3 mech (QPD s1, VqI s2, Bvu s3)

Question: *"What does seon.agent/message! return — the full transact
report or something smaller?"* Seeded: 4 `:my.kb.codebase` claim rows
(one carrying the answer).

### What the agents saw

Turn-1 blob (`logs/prompts/QPD-2606111538/BzI-2606111538.txt`): the
inventory row `{:seon.db/kind :my.kb.codebase, :seon.db/id-attr
:my.kb.codebase/claim, :seon.db/rows 4}` renders ~10 lines above the
user question. The claim TEXT renders nowhere (salience 0/N —
confirmed). And the only other mention of the kind in the prompt is
the warnings section (blob line 1983) framing it as BROKEN:

> `[unmarked-entity-kinds] Rows are STORED under an identity attr but
> no registered :map schema marked {:seon.db/entity true} declares
> that kind — its entities are invisible to the catalog and the
> renderer. … Affecting: :my.kb.codebase/claim, :seon.handler/key …
> Please correct before moving on.`

So the one pointer to the seeded knowledge is a kind+count row with no
content scent, and a warning that says those entities are "invisible
to the catalog" (the removed catalog again) and need fixing.
**Classification: existence PRESENT, relevance ABSENT.** A kind named
`my.kb.codebase` gives a code question no reason to look there before
the primary source. Sweep finding 4's escalation (richer inventory
rows carrying attr names) is the right general mechanism; CONFIRMED
with the added distractor evidence.

### What the agents did — the taught pull move fails on the asked-about fn

Sweep 2 (VqI) and sweep 3 (Bvu) both went for the program graph — the
move the namespaces-header itself teaches:

> `;; (seon.db/pull {:seon.db/pull-pattern '[:seon.fn/sym :seon.fn/doc
> :seon.fn/source] :seon.db/ref [:seon.fn/sym "seon.agent/reply!"]})`
> (ctx.cljs:807–812, rendered in every prompt)

Bvu, eval `ILZ-2606111552`, character-faithful imitation:

> `(seon.db/pull {… :seon.db/ref [:seon.fn/sym "seon.agent/message!"]})`
> → `Nothing found for entity id [:seon.fn/sym "seon.agent/message!"]`

Verified against the boot tee tx (`gym-postv4-paid3.log:253`): the fn
index carries `seon.agent/add-section! complete! remove-section!
replied-since-inbound? set-purpose!` — **NOT `message!`, NOT
`reply!`** (they carry no `:malli/schema` meta and aren't in
`curated-substrate-vars`, client.cljs:878–898; the roster is
specced-fns + a hand list). The header's OWN example would return
"Nothing found" in every store. Bvu then retried with a symbol and got
`Cannot compare function String() { [native code] } to function …` —
a JS-comparator stack leak at the decision point, no typo-guard-grade
legibility. After 12 failed consults it gave up and replied.

**Classification: MIS-SHAPED — the prompt teaches a lookup whose
canonical example fails, against a partially-populated index, with
illegible failure modes.**

### The sweep-3 "PASS 95" is a fabrication that happened to be true

The sweep notes say Bvu "answered from the rendered `seon.agent`
source." The blobs contradict this: `defn message!` appears in ZERO of
Bvu's six blobs, and `seon.agent.message-test` renders as a bare stub
(`logs/prompts/Bvu-2606111552/wqo-2606111552.txt:748`):

> `<namespace name="seon.agent.message-test">`
> `(ns seon.agent.message-test)`
> `</namespace>`

Yet the final narration (eval `bjS-2606111552`):

> "The test ns is fully rendered in `<namespace
> name="seon.agent.message-test">`. … I can just read the test source
> directly — it's in my context. … seon.agent.message-test line:
> 'message! returns an envelope with :seon.agent.message/ok?'"

An invented quotation from an empty tag — correct by luck, scored 95,
and the invented claim then re-enters its own later prompts via the
transcript (`Bvu/xnA-2606111552.txt:2176`). **REVISES the sweep's
consult-anchor note: v4's full-source body did NOT make consult and
prompt-knowledge converge here; a stub tag invited the model to
hallucinate a body.** This is the most important near-miss in the
sweep: the same mechanism on a question where the model's prior is
wrong produces a confident judge-failing answer with fake provenance.

Sweep 1 (QPD) grep'd first and hit the (since-fixed) fs answer-key
leak; chronology shows the same prose-split mangling (evals `1500`,
`` `message! ``, `88.`).

---

## S-12 `run8-two-agent-consultation` sweep 2 — judges 40/30 (A=RnA, B=LHy)

### Agent A "stored 0 findings" — actually stored findings with forked provenance

A registered a clean domain schema and transacted twice, ok=true
(evals `qeZ`, `qdP`: `:my.kb.system-internals/id
"schema-validati…"`). The predicate
(`consults-findings-run8.edn:32–36`) keys on the SHARED attrs:

> `[?f :my.kb/source-path _] [?f :my.kb/source-line _]
> [?f :my.kb/confidence _]`

A minted `:my.kb.system-internals/verified-at` instead of using
`:my.kb/verified-at` (sweep-1's Mej likewise minted
`:my.kb.seon.db.validation/source-file|source-line`). The teaching
exists twice (my.kb docstring "— :my.kb/source-path … are already
registered; just transact them on your rows"; system STANDING
TEACHINGS, ctx.cljs:737) but only as comments — no rendered example
ever SHOWS a transact whose row mixes `:my.kb.<domain>/*` attrs with
`:my.kb/*` attrs, and one-namespace-per-row is the prompt's loudest
pattern everywhere else. **Classification: PRESENT but MIS-SHAPED
(told, never shown — and contradicted by the ambient pattern).**
REVISES finding 5: the stores-proactively behavior partially landed;
the predicate is blind to provenance forks, so "0 findings" overstates
the failure. Also: ~half of A's 47 evals are its own echoed transcript
re-evaluated by the reader (result maps, `` ` ``-fragments, a fake
`my.agent.RnA-…=>` prompt line, one `Unexpected EOF` reading its own
tx-report echo) — the eval-count burn is mostly reader mangling, not
deliberation.

### Agent B — both defects came from one docstring

B's first eval is verbatim the search-ns docstring recipe
(`logs/prompts/LHy-2606111547/GdW-2606111547.txt:799`):

> `(await (seon.agent.search/grep {:seon.agent.search/pattern
> "validate-entity-values!" …}))`

→ `Assert failed: await can only be used in async contexts` — twice,
then a `go` attempt (undeclared). Every `await` in the prompt sits
inside substrate `^:async` source; nothing states the agent-REPL
convention (top-level promises resolve without `await`). Ten evals
lost to async mechanics. Then the same docstring's FICTIONAL example
output (blob lines 802–805):

> `;; [{:seon.agent.search/path "/Users/me/src/seon/src/seon/db.cljs"`
> `;;   :seon.agent.search/line-number 803`
> `;;   :seon.agent.search/line-text "(defn- validate-entity-values!"} …]`

asserts the judge's exact target fn lives in `db.cljs` — stale: it
lives at `src/seon/db/internal.cljs:499` (`validate-attrs!` :422),
verified at this sha, and `*.internal` is excluded from rendered
namespaces (ctx.cljs `included-ns?`) so no rendered surface corrects
it. B read db.cljs (791 lines), replied citing "src/seon/db.cljs L895"
and L910 — line numbers beyond the file's length, extrapolated from
the docstring's 803 — and added the misstatement:

> "An unregistered attr is caught first (and currently throws — that's
> a bug, not the envelope contract)."

The throw-site/catch-site split feeds this: `internal.cljs:952`'s own
docstring says the validate fns "throw"; the catch that converts to an
envelope is ~20 lines away in `transact!*`, and B never saw internal
at all. **Classification: needed info ABSENT from every rendered and
readable surface B touched; a WRONG version PRESENT with file+line
specificity.** REVISES the sweep's s12 narrative: B's wrong file and
the returned misstatement class are substrate-authored, not
model-authored.

---

## Beyond the reds

- **No live (post-reset, non-gym) prompt blobs exist** —
  `logs/prompts/` dirs at 1531/1534 predate the reset; 1538+ are the
  sweeps. The live minted agent has taken no LLM turn, so the
  fresh-world "sections rendering empty" check can't be done from
  blobs; finding 6 (first-boot seed ordering) is NOT independently
  verified here.
- **A standing substrate self-warning renders in every prompt of every
  agent** (`[:warnings 653]` constant across all sweep-2 turn
  profiles): `unmarked-entity-kinds` fires on `:seon.handler/key`
  ("carried unmarked by :seon.handler/register!-response") — a
  substrate-owned kind agents can't and shouldn't fix, with "Please
  correct before moving on." Permanent unactionable noise trains
  agents to skim warnings (uniformity-canary class: the substrate
  violating its own entity-marker rule).
- **`:open-todos` never appears in section-chars** — empty-render
  behavior, by design; not a defect.
- **The prose-split reader defect appears in green runs too** (s32
  sweep-1 passed-axes turns contain `1500`, `88.` evals) — it is
  load-bearing only when the eaten fragment was the consult intent
  (s21-3) or when echo re-evaluation inflates eval/turn caps (s12-2:
  A's 7 turns / 47 evals).

## Ranked blind-spot table

| # | Blind spot | Evidence (blob/log) | Class | General mechanism (no scenario-shaped hints) | vs sweep findings |
|---|---|---|---|---|---|
| 1 | Teachings reference the REMOVED schema-catalog (my.kb:108, my.soul:502+527, warnings text) — 3/3 s21 agents "read" it and concluded fresh domain | `BNv…/Kio…txt:108,502,527`; eval `SZC`; `paid3.log:8545` | mis-shaped (dangling pointer) | Render-time prompt lint: every surface/section a rendered teaching names must exist in the same render; teachings derive from the live section/fn registry, not prose | REVISES f4 (agents DID consult), feeds f1 |
| 2 | Clipped display in hash order hid a retrieved answer (432 rows, visible 50 had no domain attr; `:seon.workout` in the +382) | `yFs…/cFN…txt:2049`; store check 47 hits | buried | Deterministic (sorted/namespace-grouped) ordering for clipped seq displays, so a 50-row window is a meaningful sample; keep the drill teaching | REVISES f1 (presentation, not only surfaces) |
| 3 | Seeded domain invisible to ALL rendered surfaces (no identity attr → no inventory row; sourceless ns row → no tag; no catalog) | `Kio…txt` inventory; ns-tag list; ctx.cljs:850 | absent | f1's own hypotheses are right and general: render sourceless ns rows owning member rows via `reconstituted-ns-source`; inventory rows carry attr names + non-identity kind derivation | CONFIRMS f1, adds: the data IS one correct `:seon.schema/key` query away |
| 4 | Same-batch reply: ALL results (incl. `{:seon.db/ok? false}` envelopes, which are eval-ok=true) arrive after the batch incl. `reply!` was composed; turn ends on reply | evals `bjV`→`qzE` (`paid2.log:8522+`) | temporally absent (architecture) | Batch policy treating `…/ok? false` envelope values as errors: stop remaining forms / hold a trailing `reply!`; or require reply in a post-results batch | CONFIRMS f3, sharpens: eval-error-only abort is insufficient |
| 5 | Prompt examples that ERROR when imitated: my.kb require-alias style; bare `(await …)` in search docstring; namespaces-header pull whose own example sym (`reply!`) — like `message!` — is absent from the fn index | evals `ssL/vor…`, `JUp/Hju`; `ILZ` "Nothing found"; `paid3.log:253` roster; client.cljs:878 | mis-shaped | Self-testing prompt examples: boot/gym smoke EVALS every code example rendered into prompts from a real agent ns and fails loud on error/empty ("the prompt IS a REPL session" applied to its own examples) | CONFIRMS f2, EXTENDS to await + fn-index gaps |
| 6 | Stale docstring self-bait: search-ns example output pins `validate-entity-values!` to `db.cljs:803` (truth: `internal.cljs:499`); internal excluded from rendered nses; B cited db.cljs L895/L910, judge 30 | `LHy…/GdW…txt:799–810`; reply `GKA` | mis-shaped (stale + wrong) | Docstring examples must not embed concrete repo file/line claims as fiction — generate example outputs from a live probe, or use obviously-fake placeholders | REVISES s12-B narrative (substrate-authored error) |
| 7 | Stub `<namespace>` tags are hallucination bait: agent fabricated a quote from the EMPTY `seon.agent.message-test` tag; lucky-correct, judge 95; fabrication re-enters later prompts via transcript | `Bvu…/wqo…txt:748`; eval `bjS`; `xnA…txt:2176` | mis-shaped (tag implies content) | Stub tags self-describe their emptiness actionably (the existing header note didn't land — move the "fns are :seon.fn rows, pull like this" line INTO each stub tag body, with a pull that works, see #5) | NEW; REVISES the f4 note "answered from rendered source" |
| 8 | Reader evaluates prose/echoed transcript as forms (`24`, `", felt good…"`, result maps, backtick fragments) — ate s21-3's consult intent, inflated s12-2 A to 47 evals | `paid3.log:8545`; `OhV/frJ/Lsb/ksr/Gpw` evals | harness (response contract) | Reader discards non-form segments (or anything that fails a read) instead of evaluating fragments; reject echoes of the `…=>` prompt marker | NEW (explains parts of f5's eval burn) |
| 9 | Shared-provenance rule told, never shown: both s12 A-agents stored findings with self-minted provenance attrs; predicate counts 0 | evals `rxG…/qeZ/qdP`; `consults-findings-run8.edn:32` | mis-shaped (no worked example of a mixed-ns row) | The my.kb docstring's worked example shows an actual transact whose row carries domain + shared `:my.kb/*` attrs together (general: examples show the full move, not a comment saying "just transact them") | REVISES f5 (storage happened; provenance forked; predicate blind to it) |
| 10 | Inventory rows carry no content scent (kind+count), and the only other mention of the seeded kind is a warning calling it broken/"invisible to the catalog" | `QPD…/BzI…txt:1983,2006` | relevance absent | f4's planned escalation (richer inventory rows w/ attr names) — already general; plus #11 below for the warning | CONFIRMS f4's salience half |
| 11 | Standing substrate self-warning (`:seon.handler/key` unmarked) in 100% of prompts with "Please correct before moving on" — unactionable, desensitizing | `[:warnings 653]` constant; `Kio…txt:1977` | noise | Fix the substrate registration (the warning is a genuine uniformity canary); warnings surface only agent-actionable items | NEW |
| 12 | Store-lookup failures are illegible at the decision point: `:seon.schema` entity shape undocumented (guessed `/type` → typo-guard), lookup-ref type mismatch → raw JS comparator error | evals `zhO`, `MOI/AbL` | mis-shaped (error legibility) | Extend the typo-guard pattern to pull/lookup-ref misses: name the id-attr's value type and a working example (same class as V4-4 result-var legibility) | EXTENDS f1/f4 |

Not assessed: finding 6 (first-boot seed ordering — no live blobs
exist to check) and finding 7 (judge-rubric staleness — though #6
above shows the same staleness class already bit on the docstring
side; `internal.cljs:422/:499` re-verified correct at this sha).

## Integrity note

Items 2, 5, 7, and 9 include a teaching component (an agent ignored a
present affordance at least once), but in every red the dominant cause
was a context defect reachable by a general mechanism above. No
recommendation here encodes scenario answers; where the only fix would
have been answer-shaped (e.g. "render the workout schema"), the
general form (render sourceless-but-membered nses; richer inventory
rows; sorted clips) is what's listed.

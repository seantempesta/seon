---
type: prd
status: draft
tags: [prd, agent]
---

# V3-E — SHOW DON'T TELL: turn-0 opens with REAL demonstrated evals (2026-06-11)

Implementation PRD for the V3-E unit of
[[context-v3-code-first-2026-06-10]] (the "SHOW DON'T TELL" bullet),
reshaped by the user's 2026-06-11 requirements:

1. **The demos are REAL evals** — actually executed at render time
   against the live db value, so they can NEVER go stale. No templated
   results, no copy-pasted example output that drifts from reality.
2. **A demo eval that FAILS is a CANARY** — the substrate's own
   orientation queries breaking means something is deeply wrong. It
   must raise loudly (warn-registry cluster + inspector), never render
   a broken result quietly into every prompt.

Why this works (the P8 evidence): the measured verdict
([[cljs-finish-clj-pivot-plan-2026-06-09]] §10, 5 paid runs +
corrected-world re-run) showed **instructions-as-data moved behavior**
— consult-first went 5/5 under anchored predicates once
`my.kb.instruction` rows rendered as data the agent could see and
query. V3-E generalizes the lesson: the agent imitates what it SEES
RAN, far more reliably than it obeys what it is told. The agent wakes
mid-session in a REPL where the orientation queries already ran —
imitation over obedience.

Prerequisite reading: the V3-E bullet in
[[context-v3-code-first-2026-06-10]], the pull-own-entity demo in
[[agent-self-context-spec-2026-06-10]] ("Show don't tell" §2),
`src/seon/ctx.cljs` (composer + `transcript-section` +
`format-eval-row`), `src/seon/warn.cljs` (the `checks` registry),
`src/seon/agent/todo.cljs`, `src/my/kb/instruction.cljs`.

## 1. The demo set

A small FIXED list — **four executed demos** plus one deliberately
NON-executed write shape (§4). Substrate-authored, not configurable
per-agent (agents customize via `:seon.agent/ctx` sections, not by
editing the substrate's orientation). Each entry: the exact form, what
its REAL result teaches, and which existing section it replaces or
shrinks. **Per the standing method, conversions are measured ONE per
unit** (§6) — the demo lands and its displaced section dies in the
same unit, gym-scorecarded.

### D1 — open work items

```clojure
(seon.agent.todo/list-open {})
;; ⇒ {:seon.agent.todo/ok? true
;;    :seon.agent.todo/todos [{:seon.agent.todo/id "…" …} …]}
```

- **Teaches:** the todo verbs exist and return ENVELOPES (errors are
  values — `::ok?` + data, the discipline every toolbelt verb shares);
  the agent's open work is queryable, not narrated.
- **Replaces:** the `:open-todos` section (priority 45,
  `seon.agent.todo/open-todos-section`) — the demo's real result IS
  the open-todos list. The section's one prose line ("complete! when
  finished") moves into a `;;` comment above the demo. The
  empty-result case is itself a lesson (`::todos []` renders honestly
  — derived view, vanishes-when-done semantics shown, not told).

### D2 — the catalog is a query, not a wall

```clojure
(seon.db/query
  {:seon.db/query '[:find ?k ?id-attr
                    :where [?e :seon.schema/key ?k]
                           [?e :seon.schema/id-attr ?id-attr]]})
;; ⇒ #{[:seon.fn :seon.fn/sym] [:my.kb.instruction :my.kb.instruction/id] …}
```

- **Teaches:** every entity KIND in the system is one datalog query
  away; the id-attr is how you reach instances; `seon.db/query`'s
  map-in shape. The result is live — register a kind, it appears.
- **Shrinks:** the `:schema-catalog` section's **domain-attrs wall**
  (`domain-attrs-block`) — the passive listing converts to this
  executed query + the reuse-contract line as a comment. The
  kinds-block (grouped attrs + counts) and the finding-claims block
  (#26 salience fix) STAY in `:schema-catalog` for now — they carry
  per-attr type/unit information the compact query result does not,
  and `reuses-schemas` is a demo-critical axis we will not gamble in
  the same unit (§6 risk note).

### D3 — consult your instructions (the my.kb consult demo)

```clojure
(seon.db/query
  {:seon.db/query '[:find ?priority ?id ?text
                    :where [?i :my.kb.instruction/id ?id]
                           [?i :my.kb.instruction/priority ?priority]
                           [?i :my.kb.instruction/text ?text]]})
;; ⇒ #{[10 "consult-before-research" "Consult stored knowledge FIRST: …"] …}
```

- **Teaches:** instructions are DATA in the first worked `my.kb`
  domain — the same datalog that reads them can read ANY `my.kb.*`
  knowledge; the consult move is demonstrated, not preached. The
  result delivers the actual cluster guidance (the instructions
  arrive AS a query result).
- **Replaces:** the `:instructions` section (priority 15,
  `my.kb.instruction/instructions-section`) — same rows, now shown as
  what they are (query output) instead of a rendered prose block. The
  runtime-editability note (identity upsert on `:my.kb.instruction/id`)
  stays as the demo's leading comment.
- **Placement consequence:** instructions move from priority 15
  (static-zone) to the demos block in the dynamic zone. That is
  correct — instruction rows are runtime-editable, so they were
  always a cache-bust risk in the prefix; as a demo result they are
  honestly dynamic. (Gym must confirm guidance still bites from the
  later position — §6 unit E3.)

### D4 — pull your own entity (the self-context demo)

```clojure
(seon.db/pull
  {:seon.db/pull-pattern '[:seon.agent/id :seon.agent/state
                           {:seon.agent/ctx [:seon.ctx/name
                                             :seon.ctx/priority
                                             :seon.render/ai]}]
   :seon.db/ref [:seon.agent/id "<own-id>"]})
;; ⇒ {:seon.agent/id "…" :seon.agent/state :idle
;;    :seon.agent/ctx [{:seon.ctx/name :purpose :seon.ctx/priority 12
;;                      :seon.render/ai "Your human created you for: …"} …]}
```

- **Teaches:** the agent IS an entity; its purpose and its own context
  sections are visible datoms it can read and (via
  `seon.agent/add-section!` / `set-purpose!`) transact. This is the
  worked example mandated by [[agent-self-context-spec-2026-06-10]]
  §"Show don't tell" item 2.
- **Shrinks:** the seeded `:your-sections` computed section
  (`seon.ctx/own-sections-section`, priority 13) — its formatted list
  is subsumed by the pull's real result. The seed's pointer line
  ("edit with add-section! / remove-section! / set-purpose!") moves
  into the demo's comment. The `:purpose` seed itself is NOT touched
  — it stays a high-priority section (anti-drift constant), the demo
  merely shows it as data.

### NOT in the executed set — the message/reply shape

`(seon.agent/reply! {:seon.agent.message/content "…"})` is the single
most behavior-critical form, but it is a WRITE — see §4. It stays in
`:capabilities` as a worked source example. The demos section carries
one closing comment pointing at it, so the orientation narrative ends
on the move the agent must make:

```text
;; Writes are NOT demonstrated here (a demo runs every render — a write
;; would mutate per render). The reply!/transact worked examples are in
;; <capabilities> above; they end every question-turn.
```

## 2. Execution model

### The registry

A substrate `def` in `seon.ctx` (the V3-C home):

```clojure
(def demos
  "The turn-0 orientation demos — REAL forms, REALLY EXECUTED at every
   render against the live db value. READS ONLY (§purity). Fixed,
   substrate-authored, ordered."
  [{:seon.ctx.demo/name    :open-todos
    :seon.ctx.demo/comment ";; Your open work — complete! each when finished."
    :seon.ctx.demo/form    "(seon.agent.todo/list-open {})"
    :seon.ctx.demo/run     (fn [input] (todo/list-open {}))}
   …])
```

- `:seon.ctx.demo/form` is the string the agent SEES (the lesson —
  copyable, runnable as-is at its prompt).
- `:seon.ctx.demo/run` is what actually executes. Form/fn drift is
  guarded by a **parity test** (§5): the suite evals each form string
  through the pod's bootstrap compiler in an agent universe and
  asserts the rendered result equals the run-fn's rendered result.
  (We do not eval the form string at render time — self-hosted eval
  per render is heavyweight and would write `:seon.eval` rows, a
  mutation.)
- Schemas registered for the demo map (`:seon.ctx.demo/*` keywords ↔
  the `seon.ctx` code ns — keyword honesty rule). The registry is
  data so `check-demo-evals` (§3) and the gym predicate (§5) run the
  SAME list — one source of truth.

### Render

A new section fn `seon.ctx/demos-section`, slotted into
`substrate-default-ctx`:

```clojure
{:seon.ctx/name :demos :seon.ctx/priority 48
 :seon.render/ai 'seon.ctx/demos-section}
```

- **Placement: priority 48** — strictly AFTER every byte-stable /
  semi-static section (system 10 … namespace-context 30, warnings 40)
  and immediately BEFORE `:transcript` (50). Turn-0's transcript zone
  therefore OPENS with the demos: the agent reads `<orientation>`
  evals, then its real session history, then the prompt — one
  continuous REPL narrative. The provider-cacheable prefix is
  untouched (demos results change with the db; they are dynamic by
  nature — §5 caching).
- **Style: identical to the transcript's eval rendering** —
  `> <form>\n<result>` per `format-eval-row`'s visual grammar, inside
  an `<orientation>` wrapper with one header comment:

```text
<orientation>
;; These queries RAN just now against the live store — results are real.
;; Run any of them yourself; copy the shapes.

;; Your open work — complete! each when finished.
> (seon.agent.todo/list-open {})
{:seon.agent.todo/ok? true :seon.agent.todo/todos […]}

> (seon.db/query {:seon.db/query '[:find ?k ?id-attr :where …]})
#{[:seon.fn :seon.fn/sym] …}
…
</orientation>
```

- **Execution: pure reads of the render input's db value** (sub-ms —
  all four are AEVT-/EAVT-local lookups on small datom counts;
  measure once in the unit, but the live-store evidence and the
  existing per-render catalog queries already establish this class of
  query as sub-ms). Demos receive the SAME input map every section fn
  gets (`{:seon.db/db db :seon.agent/id id :seon.agent/entity entity}`)
  — D4 takes its own ref from `:seon.agent/id`; D1 must run inside the
  agent's conn scope (see Open questions — `list-open` reads
  `db/*conn*`).
- **Per-demo char budget: 800 chars** via the existing `cap-result`
  (smaller than `eval-render-cap`'s 1500 — orientation results should
  be glanceable; the transcript's own evals keep 1500). Four demos ≤
  ~3.5k chars total, less than the sections they displace. A clipped
  demo result uses `cap-result-body`'s guiding clip (teaches
  narrowing) — a big result here is itself a smell the gym will
  surface.

## 3. Staleness-proof + canary

**Staleness is impossible by construction** — there is no stored or
templated result to go stale; what renders is what the query returned
against the db value of THIS render. A schema rename, an attr removal,
a broken verb — anything that would have silently rotted a
documentation example instead breaks the demo eval, which triggers the
canary.

**FAILURE = the demo's run-fn throws, or returns a fail envelope**
(`::ok? false` for envelope-shaped verbs like `list-open`). An EMPTY
successful result (no todos, no instructions on a virgin store) is NOT
a failure — it renders honestly and teaches the derived-view model.

Three simultaneous surfaces — never silently dropped:

1. **Inline honest error (the prompt):** the demo renders a short
   error line in place of its result, in the same REPL grammar:

   ```text
   > (seon.agent.todo/list-open {})
   ;; DEMO FAILED: <deepest real message> — this is a substrate
   ;; orientation query; its failure is a substrate bug, not yours.
   ```

   The existing section guard (`render-section` catch) remains the
   outer backstop, but demos catch per-demo so three healthy demos
   still render when one breaks.

2. **The canary — a `seon.warn` registry check.** New
   `check-demo-evals` conj'd into `seon.warn/checks`, with the
   standard `::check-request → ::check-response` contract: it runs
   the SAME `seon.ctx/demos` registry against the request's db value
   and reports each failing demo as an affected entry
   (`:seon.warn/sym` = the demo name, `:seon.warn/where` = the form),
   clustered under one explanation:

   ```text
   [demo-evals-failing] The substrate's own orientation demos are
   failing — turn-0 context is degraded for EVERY agent and something
   is deeply wrong below you (schema drift, broken verb, bad store).
   ```

   Because `warnings-section` (priority 40) renders ABOVE the demos
   and is GLOBAL (runtime checks are cross-agent by design), every
   agent in the cluster sees the canary before it sees the broken
   demo — and it self-heals the render after the cause is fixed
   (derived, nothing stored, per reactive-context).

3. **The inspector:** both surfaces arrive free —
   `assemble-context`'s `:seon.render/section-texts` carries the
   `:warnings` cluster and the `:demos` error line, so the inspector's
   context view shows the canary without new plumbing.

Yes, the demos execute twice when the canary fires its check (once in
`demos-section`, once in `check-demo-evals`). That is the
reactive-context trade — derived, stateless, no shared "last result"
cache — and at sub-ms per read it costs nothing. Do NOT introduce a
result-passing side channel between the section and the check.

## 4. Purity rule — demos are READS only

A demo executes on EVERY render: every agent turn, every inspector
view, every gym predicate evaluation. A write-shaped demo would
therefore transact per render — non-idempotent store growth, broken
render purity (sections are pure fns of the db — the load-bearing
reactive-context invariant), and mutated gym fixture worlds.

**Decision:** the write shapes (`reply!`, `transact!`,
`add-section!`/`set-purpose!`, todo `add!`/`complete!`) are taught as
**worked source examples in `:capabilities` and in the verbs'
docstrings — NOT as source-not-executed pseudo-demos inside the
orientation block.**

Justification for rejecting the source-not-executed alternative: the
orientation block's entire value is the invariant *"everything between
these tags REALLY RAN"*. Mixing executed `>` lines with displayed-only
`>` lines teaches the agent that some transcript-styled evals are
fake — which corrodes trust in exactly the surface we are building
trust in, and reopens the staleness hole for the non-executed entries
(their shown "results" would be templated again). One closing comment
(§1) bridges to the capabilities examples instead. When P6 lands the
verbs' full visible source, the write shapes get the code-as-data
treatment there.

## 5. Gym + caching interaction

- **Free regression coverage:** parity worlds boot byte-identical to
  live (095a00b), so EVERY gym scenario render executes all four demos
  against the fixture world — `list-open`, the catalog query, the
  instruction rows, and the self-pull are now exercised on every run
  of every scenario, stub tier included, at zero added spend.
- **`demo-evals-healthy` stub predicate:** a standing predicate
  (driver vocabulary: runs on every scenario) that calls
  `seon.warn/check-demo-evals` against the post-run world db and
  expects `:seon.warn/affected` empty. Binary, mechanical, stub-tier.
  It lands beside the demo registry in unit E1 so any later substrate
  change that breaks an orientation query fails the suite, not just
  the live prompt.
- **Form/run parity test:** a CLJS test that bootstrap-evals each
  `:seon.ctx.demo/form` string in a test agent universe and asserts
  its rendered result equals the run-fn's rendered result over the
  same db value (drift guard for §2's two-representation risk).
- **Caching: dynamic zone only.** Demos sit at priority 48 — after
  the byte-stable prefix the provider cache keys on. Their results
  legitimately change datom-by-datom (that is the point); nothing
  about them is bucketed/fuzzed because nothing before them busts.
  Rule for the future: a demo must NEVER move into the static zone —
  if a demo's result were byte-stable enough to cache, it should be a
  static section, not a demo.

## 6. Unit breakdown + predicted scorecard movements

Standing method: **one section conversion per unit, full suite once
per unit, gym-scorecard each** before the next conversion. Each unit
is one agent, ≤7 files, explicit fence, never stash/checkout/reset.

### Unit E1 — the mechanism + first conversion (:open-todos → D1)

Registry (`seon.ctx/demos` + schemas) + `demos-section` (+ priority-48
slot) + per-demo error handling + `check-demo-evals` in `seon.warn` +
the `demo-evals-healthy` stub predicate + the form/run parity test.
First conversion: delete `:open-todos` from `substrate-default-ctx`
and `open-todos-section`'s context wiring (the pure blocks
`open-todos-block` may die with it if nothing else calls them —
no-legacy rule). Ships with ONLY D1 enabled; D2–D4 land in their own
units. The **agreement property test** (all classifier surfaces
classify identically — carried into V3-E by the bullet) lands here
too if V3-C hasn't already absorbed it.

- Predicted movement: none expected on graded axes (todos are
  S-21/S-12-peripheral) — this unit's bar is **no regression** plus
  the new stub predicate green. `terminates-clean` and reply
  discipline must hold.

### Unit E2 — catalog conversion (domain-attrs wall → D2)

`domain-attrs-block` converts to the executed query; kinds-block and
finding-claims stay (§1 D2).

- Predicted movement: **`reuses-schemas` (S-21) is the at-risk axis**
  — the wall currently shows attr names + types + units; the compact
  query result shows kinds + id-attrs. If S-21 forks a parallel attr
  again (the run-4 regression), the unit REVERTS and the wall stays —
  correctness of the lesson > size. Upside case: `searches-first`
  improves marginally (the demo shows the db-first move).

### Unit E3 — instructions conversion (:instructions → D3)

`:instructions` section (priority 15) deleted from
`substrate-default-ctx`; `instructions-section`/`instructions-block`
die or shrink to the demo's run-fn; guidance now arrives as D3's
result in the dynamic zone.

- Predicted movement: **S-32 consult is already 5/5 — it is the
  regression bar, not the headroom.** The remaining headroom these
  demos target is (a) **S-12 `consults-findings`** (two-agent consult
  — agent B's first eval consulting agent A's findings: demonstrated
  datalog-over-my.kb should transfer to datalog-over-findings better
  than the prose instruction did), (b) **S-32 `searches-first`
  inverted** (stability of NOT researching), and (c)
  **`reply-every-asked-turn` discipline** holding with the
  instruction now rendered later in the context. A drop on any of
  these reverts the unit.

### Unit E4 — self-pull demo (D4) + :your-sections shrink

D4 enabled; the seeded `:your-sections` section's formatted list is
dropped from `seed-sections` (the `:purpose` seed stays untouched).
Coordinate with P8's specialist scenario.

- Predicted movement: the specialist-gym predicates (section rows
  present, post-restart render contains them, `add-section!` actually
  used when asked to specialize) — D4 is the mechanism by which the
  agent KNOWS its sections are editable data. `models-work-directed`
  may move on scenarios that ask for self-configuration.

### Scorecard summary

| Unit | Converts | Axis watched | Expectation |
|---|---|---|---|
| E1 | :open-todos → D1 | terminates-clean, reply discipline | no regression + stub predicate green |
| E2 | domain-attrs wall → D2 | reuses-schemas (S-21) | hold; revert on fork |
| E3 | :instructions → D3 | S-12 consults-findings; S-32 stays 5/5 | S-12 moves up; S-32 holds |
| E4 | :your-sections → D4 | specialist predicates, models-work-directed | P8 scenario passes |

## 7. Open questions

1. **Conn scope for D1.** `list-open` reads `db/*conn*` and resolves
   the calling agent from scope; `assemble-context` receives a db
   VALUE. The agent-loop render path runs inside the agent's universe
   (conn bound), but inspector calls may not. Either the inspector's
   assemble call wraps in `(seon.db/with-agent …)` (preferred — the
   inspector should render exactly what the agent renders), or D1's
   run-fn falls back to the pure `open-todos` read with the input's
   agent ref. Decide in E1 against the live inspector path.
2. **`:seon.schema/id-attr` over-match pollutes D2.** Live-store
   evidence (plan §"Unit specs") shows EIGHT request/response
   envelopes carrying `:seon.schema/id-attr` via the
   `derive-entity-id-attr` over-match — D2's result would teach those
   as entity kinds. The over-match fix is a separate small unit;
   sequence it BEFORE E2 or add the `:seon.schema/render-fn` clause to
   D2's form (matching the catalog's own gate) so the demo teaches
   true kinds either way.
3. **Demos block vs. inside `<transcript>`.** Decided: own
   `<orientation>` block at 48 (separately testable, doesn't
   complicate `transcript-section`'s budget walk). If the gym shows
   the agent treats orientation evals as "its own past actions" less
   from a separate block, an A/B folding them into the transcript's
   head is cheap.
4. **Does the canary need an inspector tile?** §3's three surfaces
   ride existing plumbing. A dedicated red tile is tempting but
   tiles/labels are PARKED (user call pending) — do not build one in
   this lane.

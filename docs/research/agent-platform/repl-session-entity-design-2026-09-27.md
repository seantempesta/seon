---
type: research
status: research and phased plan for Astra review; no source changed; owner decisions open in §10
created: 2026-09-27
branch: refactor/agent-platform
source-pin: 5d537eaf3 (checkout); live default pid 22787, commit 6ab93d43, basis 536879662
lane: design-repl-session-opus
---

# The agent's REPL session: research and plan for the agent → run → evaluation → namespace model

What exists: an agent row owns a `:seon.agent/runtime` component (identity `:seon.runtime/agent`) that
already holds the agent's runs (`:seon.runtime/turns`), the latest trigger and its listens; each run owns
evaluations by reverse ref (`:seon.cluster.eval/run`), each evaluation stores the namespace it ran in
(`:seon.cluster.eval/ns`, a ref) and the one it ended in (`:seon.sci.eval/ending-ns`, a symbol). "Where the
agent is" is held nowhere: four readers re-derive it by scanning evaluations or fall back to the agent's home
namespace. The principal Clojure approach of the target: the runtime component *is* the session; add one ref,
`:seon.runtime/ns`, asserted by the run's one settlement transaction with the namespace SCI was in after the
last form, and make every run start, every system turn and the namespace half of the context read that ref.
Everything else in this document is evidence for that choice, the adjacent defects the inventory found, and
the order to fix them.

Recommendation in one line: **session = the existing runtime component; current namespace = one optional ref
on it, upserting the namespace identity in the settlement; per-evaluation `:seon.cluster.eval/ns` stays;
`:seon.turn/starting-ns`, stored `:seon.sci.eval/ending-ns`, `previous-ending-namespace`, `fold-namespace`
and the submit path's forced namespace disappear** (§6, about +40/−150 production lines).

---

## 1. The graph, drawn from the agent (the organizing axis)

Owner principles applied (2026-09-27): everything is agent-centric; what hangs off the agent's record, and
how, decides context; a namespace carries namespace information, never agent information; changing
namespace swaps the namespace half of context and keeps the agent half.

```
agent  [:seon.agent/id "root"]                                   (data partition, seon.agent.edn:47-59)
│ scalar  :seon.agent/branch  :seon.agent/archived-tx  :seon.agent/admission-closed-tx
│ ref     :seon.agent/namespace ──────────► ns  (HOME: assignment, not unique, seon.agent.edn:118-121)
│ (proposed elsewhere) ref :seon.agent/cluster ──► [:seon.cluster/name n]   (config design 10192e1ee; §6e, Q9)
│ comp    :seon.agent/plan      ──► my.plan  (steps …)
│ comp    :seon.agent/settings  ──► config overlay
│ comp    :seon.agent/runtime   ──► runtime  (identity :seon.runtime/agent ── back-ref to agent)
│                                   ├ comp-many :seon.runtime/turns ──► run*        (hop 2)   DUP of run→agent
│                                   ├ ref       :seon.runtime/trigger ──► message   (hop 2)   MIRROR of latest run's trigger
│                                   └ comp-many :seon.runtime/listens ──► listen pattern
│                                   (target) + ref :seon.runtime/ns ──► ns  CURRENT namespace   (hop 2)
│ reverse :seon.turn/_agent      ◄── run*   (AVET on ref; the same relation as runtime/turns)
│ reverse :seon.message/_to      ◄── message*        (wake component, routed by attribute)
│ reverse :seon.issue/_agent, :my.note/_agent, :seon.error/of-steward (render units, seon.agent.edn:36-45)
│
run  [:seon.turn/id]  (data)                 hop 2 (via runtime) or hop 1 (reverse :seon.turn/agent)
│ :seon.turn/agent ref · :seon.turn/starting-ns ref (RE-DERIVED each open) · :seon.turn/trigger ref
│ :seon.turn/handled refs · :seon.turn/opened-tx/closed-tx tx refs · reply/reply-blob/reply-size
│ :seon.turn.work/situation (:call/:generate; absent for submissions) · attempts comp · disposition/result
│ reverse :seon.cluster.eval/_run ◄── evaluation*                                   hop 3
│
evaluation [:seon.cluster.eval/id = id/evaluation(run, ordinal)]  (data)
│ run ref · ordinal · at · author · source · comment · ns REF (where it ran) · ending-ns SYMBOL
│ shown · error · diagnostics · output · duration/costs · renderer(-fn) · faults refs · read-basis
│ comp-many read-evidence ──► {dependency-plan STRING (up to 1.5 MB), revision, source-argument-position}
│
ns [:seon.ns/name]  (PROGRAM partition, seon.ns.edn:10-15)
  :seon.ns/steward ref → agent (written-by steward-call; excluded from program attributes)
  declared ns: definition-digest, source, requires, aliases, … ; identity-only ns: name alone
```

What context walks today, and how (file:line; "walk" = ref traversal from the agent, "scan" = a query not
rooted at this agent's own edges or proportional to more than this agent's history):

| Context need | Reached today | Kind |
|---|---|---|
| Agent's evaluation history (prompt) | `seon.eval/of-agent` agent → runtime → turns → reverse eval/run (`src/seon/eval.clj:44-56`), all evaluations, pulled with `[* {ns …} {read-evidence [*]}]` | walk, unbounded, decodes read evidence |
| History in the transcript/page | `transcript/candidate-entity-ids` via `:seon.turn/agent` rules, a limit and a bootstrap pin (`render/transcript.clj:112-184`) | second walk over the *other* edge |
| Refresh of generated reads | `turn/latest-evaluations` again agent → runtime → turns → evals with read evidence (`turn.clj:2033-2064`) | third walk, same decode |
| Current namespace | `previous-ending-namespace` max eval eid via `:seon.turn/agent` (`turn.clj:597-610`); `fold-namespace` scans a run's evals then `starting-ns` (`turn.clj:4533-4546`); else `agent-namespace` home (`sci/eval.clj:431-449`) | scan / fallback |
| Namespace half of context | `walk/acquired-root-namespace-eid` reads **`:seon.agent/namespace` (home)** (`render/walk.clj:603-612`); `declared-sources` segments the opening in home (`turn.clj:1979-2030`, caller `:2233`, `:5478`) | walk from the wrong ref |
| Namespace of a historical form | eval `ns` ref, else run → agent → home, else `'user` (`render/transcript.clj:278-283`) | three-way fallback |
| Latest trigger | `:seon.runtime/trigger` mirror, rendered as a generated read each system turn (`transcript.clj:1040-1060`) | stored mirror |

## 2. Inventory

### 2a. Writers of the run/evaluation/namespace facts

| Fact | Type, card. | Writer (file:line) | Transaction |
|---|---|---|---|
| run open: `:seon.turn/id agent situation opened-tx [trigger] [starting-ns]` + `{:seon.agent/runtime {:seon.runtime/agent a :seon.runtime/turns [run] [:seon.runtime/trigger t]}}` | refs/values, one; turns many-comp | `open-call` `turn.clj:433-469` | open tx (`open-tx`) |
| provider plan: `reply-size`, **`starting-ns` := requested or `previous-ending-namespace` or home** (`:704-714`, write `:731-732`), namespace rows (`namespace-rows :619-637`), eval intent rows with planned `ns` (`source-rows :639-675`) | | `plan-call` `turn.clj:677-734`, caller `freeze!` `:4745-4780` (no starting-ns passed) | intent tx |
| system/submitted run: open + plan in one call; namespace = requested or previous-ending or home (`:774-781`) | | `system-run-plan` `turn.clj:756-790`; `submission-open-tx :809`; `system-run-if-admitted-call :820` | first durable write or settlement |
| generated run open with **caller's** starting-ns (`:897-912`); appended forms use `:seon.ns/name` of **home** (`turn.clj:5549`) | | `generated-run-tx`, `append-generated-call :846-895` | per-append tx |
| recorded (page) run: run + starting-ns + evals + namespace rows | | `record-evaluated-call` `turn.clj:1561-1630` | one tx |
| evaluation terminal facts incl. `ns` (overwrites planned) and `ending-ns` | ns ref (lookup `[:seon.ns/name x]`), ending-ns symbol | `evaluation-facts` `turn.clj:105-160` from `sci/eval.clj:4216-4227` (success), `:4272-4273` (failure: ending = start), `:4331-4356` (unrun) | settlement: `settle-batch!` `turn.clj:3857-3920` (one tx for the run), `settle!` `:4168`, refusal arm `:4074-4152` |
| read evidence components | comp-many; plan string | `receipt-read-evidence-tx` `turn.clj:1463-1480` | settlement |
| namespace identity rows | `{:db/id "namespace:x" :seon.ns/name x}` | `namespace-rows` `turn.clj:619-637` (plan, record) — **not** at settlement | plan / record |
| home namespace assignment | ref | `agent/creation-tx` (`cluster/agent.clj:240`), host `cluster/host.clj:67` | creation |

Callers that pass a starting namespace meaning "home": `bootstrap.clj:316` (`my.agents.root`), `:343`,
`issue.clj:1407`, the submit path `cluster/agent.clj:697-712` (always: requested, else home), which is why a
submitted run never continues where the last one ended (root's `ns-probe`, 2026-09-27).

### 2b. Readers that re-derive agent state

| Reader | What it derives | How | Flag |
|---|---|---|---|
| `previous-ending-namespace` `turn.clj:597-610` | where the agent is | max eval eid over `:seon.turn/agent`, then pull ending-ns | SCAN, O(agent evals) |
| `fold-namespace` `turn.clj:4533-4546` | ns before ordinal k when resuming | last ending-ns of earlier evals, else run starting-ns | SCAN + duplicate of the loop's threading |
| `resume-turn` `turn.clj:5330-5334` | run's evaluation start ns | submission ns, fold-namespace, home | three sources |
| `evaluate-sources` loop `turn.clj:5073-5087`, `:5184` | per-form ns | threads in-memory ending-ns | correct (the authority) |
| `sci.eval/agent-namespace` callers `turn.clj:2233, 4745, 5334, 5478, 5549`, `sci/eval.clj:3347, 4537` | "the agent's namespace" | home ref | used as *current* at `:2233/:5478/:5549` (system turns) and `:4745` (provider segmentation) |
| `walk/acquired-root-namespace-eid` `render/walk.clj:603-612` | namespace half of context | home ref | WRONG REF |
| `transcript/receipt-entry` `render/transcript.clj:258-301` | form ns, ending ns | eval ns → run → agent → home → `'user` | fallback chain |
| `repl/emission` `repl.clj:178, 216` | `:seon.repl/ns` shown when ending ≠ prompt | stored ending-ns | derivable from next prompt |
| MCP JVM value `cluster.clj:389` | ns, ending-ns in MCP reply | evaluation value | in-memory, fine |
| `form-owner` `turn.clj:2599-2624`; `render/namespace-owner` `render.clj:1736-1746` | who owns a form / a failing renderer | agent whose **home** is the ns | assignment used as ownership (the steward fact exists: `problems.clj` `unstewarded-namespaces` says so) |
| `stored-record-content` `turn.clj:1534-1559` | re-record equality | compares starting-ns | goes with starting-ns |

### 2c. Duplicated facts, mirrors, scans, hand lists

1. **One relation stored twice**: run ↔ agent as `:seon.turn/agent` (47 source mentions across 10 files) and
   `:seon.runtime/turns` (readers `turn.clj:575, 2059`, `eval.clj:49`, `cluster/status.clj:137-142`,
   `cluster/agent.clj:335-342`, `render/web.clj:3211`, `render/transcript.clj:1149, 2431`). Both
   attributes declare the same history renderer (`seon.turn.edn` `:agent`; `seon.runtime.edn` `:turns`).
2. **Mirror**: `:seon.runtime/trigger` = the latest run's `:seon.turn/trigger`, written by `open-call :468`,
   re-rendered to the agent as a diff every system turn (§3 cost).
3. **Two namespace spellings on one evaluation**: `ns` (ref) and `ending-ns` (symbol). Within a run, form
   k's `ending-ns` equals form k+1's `ns` by the loop (`turn.clj:5184`); only the last form's is new
   information, and that is exactly the session's current namespace.
4. **`:seon.turn/starting-ns`** equals the ordinal-0 evaluation's `ns` whenever the run has a form; it is a
   stored copy of a derivable value with its own readers (`fold-namespace`, `stored-record-content`).
5. **Four history selectors** (`of-agent`, transcript candidates, `latest-evaluations`, and the
   `seon.context` selection/contribution family `context.clj:76-256`, which has no caller outside
   `context.clj` in src/script/test; default holds 0 contribution rows): a deletion candidate.
6. **`:seon.error.occurrence/agent` beside `/turn`** (`seon.error.occurrence.edn:7-8`): the turn already
   names the agent. Lower priority; errors can occur outside runs, so this may be justified; not changed
   here.
7. **Stored label**: `:seon.turn.work/situation` (`:call`/`:generate`) is the "situation stamp" the turn
   PRD §2 lists as removed; 23 of root's 117 evaluations belong to runs carrying it (15 `:call`, 8
   `:generate`), the other 94 to submitted runs without it.
   Not changed here.
8. **Author**: all 117 of root's evaluations on default are `:author :system`, including outside-agent MCP
   forms such as `(/ 1 0)` and `(m/validate int? 1)`. MCP SCI is "one ordinary run of root"
   (`script/seon/dev/mcp.clj:777`), so those forms are the agent's own. Question §10 Q7.

## 3. Measurements (default, read-only, JVM mode, 2026-09-27)

Default is small: 113 agents (root plus 112 hosted `mcp/<branch>` rows that carry a runtime and a home of
`user`; their runs live on their branches), 59 runs and 117 evaluations, all root's. Every one of the 487
`:seon.ns/name` rows on default has a definition digest (no identity-only rows exist there today).

| Measure | Value |
|---|---|
| datoms per run entity | 7 (26 runs), 8 (7), 10 (26) |
| datoms per evaluation entity | 11–20, plus read-evidence components: 1,353 datoms over 451 components |
| `:datahike.read/dependency-plan` total | **17,572,807 characters** in 451 values (286 distinct); max 1,501,080; median 409 |
| `previous-ending-namespace` | 0.10–0.17 ms warm (1.4 ms first); O(agent evaluations) |
| `sci.eval/agent-namespace` | 0.085 ms |
| two-hop pull agent → runtime → ref (the target read's shape) | 0.030 ms via `seon.db/pull`, 0.015 ms raw |
| `[:seon.ns/name sym]` lookup | 0.017 ms |
| **`seon.eval/of-agent` root (default selector)** | **1,553–1,819 ms** |
| same, selector without read evidence | 0.41 ms |
| raw `datahike.api/pull-many` of the same 117 with the same selector | 3.1 ms |
| `seon.db/pull` `[*]` of one evaluation whose evidence plan is 1.5 MB | 126 ms (raw 0.08 ms): the cost is Seon's decode of the plan string |
| **`turn/latest-evaluations` root** (every system turn's refresh input) | **1,557–1,604 ms** |
| `transcript/history` root, limit 200 | 19–30 ms |
| stored history tokens (source + shown, chars/4) | 34,593 tokens over 117 evaluations; 29,868 (86%) are three repeated generated reads: messages 15,413 (25 runs), `seon.error/for-agent` 8,455 (26), runtime trigger/listens 6,000 (25) |
| namespace half (`directory-value`, raw data, chars/4) | `my.agents.root` 32 tokens / 3.4 ms; `seon.repl` 2,726 / 16.7 ms; `my.program` 4,857 / 17 ms; `seon.cluster.agent` 4,448 / 23 ms; `seon.turn` 11,284 / 58 ms (before the floor's budget) |

Probe forms are in §11. The one-second results name their owner: `seon.db/pull`'s decode of stored
`:datahike.read/dependency-plan` values reached through `seon.eval/of-agent` (`eval.clj:18-20` default
selector) and `turn/latest-evaluations` (`turn.clj:2054-2056`). The largest plan belongs to the generated read
`(seon.error/for-agent (seon.db/db) "root")`: its `:seon.db/read-index-patterns` enumerate every error
pattern entity, so its size grows with the cluster's error population and is re-stored every system turn.

## 4. Problems, ranked

1. **History acquisition costs 1.5–1.8 s per call and grows with every stored read** (§3). Both the prompt's
   history (`of-agent`) and the refresh (`latest-evaluations`) decode every evaluation's read-evidence plans,
   which the renderer does not show. Owner: `seon.db/read-evidence` (what a plan stores) and the two
   selectors (what they pull). No existing issue found (`docs/seon/issues` search for `dependency-plan`
   returns only the related `datahike-pull-find-dependencies-widen-to-all.md` and
   `bound-pull-selector-evidence-retains-all-attributes.md`). Out of this model's scope; slice P1.
2. **The current namespace is held nowhere and chosen five ways.** Requested → previous-ending → home in
   `plan-call`/`system-run-plan`; the submit path always forces requested-or-home (`cluster/agent.clj:697-712`),
   so MCP and page submissions never continue; system turns and appended generated forms run in home
   (`turn.clj:2233, 5478, 5549`); resume uses submission → fold → home (`:5330-5334`); the page recorder
   uses the caller's. Evidence: root's `ns-probe` run 2 started in `user` after run 1 ended in
   `probe.alpha`.
3. **A form after a nested `in-ns` in the same run settles a lookup ref to an absent namespace [infer, probe
   P0.1].** Segmentation follows only a top-level `in-ns` (lane t4-c probe), so `namespace-rows` writes no
   row for `(do (in-ns 'x) …)`. The loop then evaluates the next form in `x` and its evaluation carries
   `:seon.cluster.eval/ns [:seon.ns/name x]` (`sci/eval.clj:4226`), asserted verbatim by
   `receipt-terminal-assertions` (`turn.clj:1446-1459`). Root's probe showed that a lookup ref to a missing
   `:seon.ns/name` refuses the transaction. Predicted outcome: the whole run's settlement refuses and the
   refusal arm records it.
4. **The namespace half of context follows home, not where the agent is** (`walk.clj:603-612`,
   `declared-sources` in home). After `in-ns`, the agent keeps seeing its home namespace's directory. This
   is the mixing the owner's two-part rule forbids.
5. **Provider replies are read in one namespace and evaluated in another [infer].** `freeze!` segments in
   home (`turn.clj:4745-4758`); `plan-call` starts the run at previous-ending; `evaluate-sources`
   re-assigns each form's namespace from the thread (`:5086-5087`). `::kw` and alias resolution at
   segmentation and at evaluation can then disagree for any run after an `in-ns`.
6. **One relation stored twice, one mirror** (§2c 1–2), with two walks that can disagree (the transcript
   pins bootstrap runs; `of-agent` does not) and a runtime block that re-sends the latest trigger to the
   agent every system turn (6,000 tokens over 25 runs on root).
7. **Assignment used as ownership** in `form-owner` and `render/namespace-owner`, contradicting
   `:seon.ns/steward` (the one ownership fact). A form in a namespace two agents are assigned to routes to
   whichever the query returns first.
8. **Dead selection family** `context.clj:76-256` (selection/append/compact of contributions): no caller.
9. **Text claims contradict code**: `script/seon/dev/mcp.clj:406` and `:777` say the run writer refuses
   another namespace (it accepts it); `cluster/agent.clj:715-722` reports a named namespace with no entity as
   "requires an agent with an assigned namespace" (`test/seon/cluster/agent_test.clj:481` asserts the wanted
   behavior, per lane t4-c).
10. **Author stamp**: outside-agent MCP forms stored as `:system` (§2c 8).

## 5. Context: the agent half and the namespace half

The owner's rule (2026-09-27) makes context two independent selections:

| Half | Selected by | Contents | Same for every agent in the ns? | Today's source |
|---|---|---|---|---|
| **Agent** | refs from the agent's record | its evaluations across all namespaces (each shows the `ns=>` prompt it ran in, from its own `ns` ref), plan, settings, messages, faults routed to it, tasks, namespaces it stewards (reverse `:seon.ns/steward`) | no | `of-agent` walk + opening units (`seon.agent.edn:36-45`) |
| **Namespace** | the session's current-ns ref | that namespace's `dir`/`doc`: definitions, contracts, schemas, docs, tests, its steward | yes | home ref (`walk.clj:603-612`) — wrong |

Selection in the target:

- Agent half: agent → reverse `:seon.turn/agent` → reverse `:seon.cluster.eval/run` (both AVET on refs,
  proportional to this agent's history), selector without read evidence (P1). Every turn: the latest
  evaluations and the wake components; on demand: older history. Owner ruling §13 "ALL turns shown by
  default" stands until compaction; the bound is compaction, not a clip.
- Namespace half: agent → runtime → `:seon.runtime/ns` (one 30 µs pull), then the existing namespace render
  (`seon.render.ns/render-ai`). Under additive context (turn PRD §14), the half is a generated read keyed by
  the namespace, `(dir <current-ns>)`: after an `in-ns` it is a new distinct read form, so the refresh
  algorithm appends it as status `:none` at the next system turn with no new mechanism; returning to a
  namespace whose `dir` is unchanged appends nothing.
- Token budget per turn, measured: agent half grows with history (34.6k tokens now for root, 86% repeated
  generated reads); namespace half 30–11,300 tokens raw per namespace entered, once per change.

**`:seon.ns/steward` is namespace information** and stays on the namespace: it answers "where do faults and
requests about *this namespace* go", is the same for every agent viewing it, and is written by
`steward-call`, not by the steward agent's runs. The agent-specific reading — "which namespaces do I
steward" — is the reverse ref from the agent (`:seon.error/of-steward` and a reverse steward unit), never a
copy on the agent. **`:seon.agent/namespace` is agent information** (its assignment) and stays on the agent;
the defect is only that context uses it as the namespace half's selector (problem 4) and ownership uses it as
stewardship (problem 7). No namespace information is copied onto agent records today.

## 6. Target model

### 6a. Schema sketches (EDN, additions and retirements only)

```clojure
;; resources/seon/schemas/seon.runtime.edn — the session (unchanged identity, data partition)
#:seon.runtime{:ns [:and {:description "The namespace this agent's REPL is in: SCI's *ns* after the last
                         form its latest settled run evaluated. Written only by run settlement; the next
                         run, system turns and the namespace half of context start here. Optional: absent
                         before the first settlement, and swept if the namespace is retracted, when runs
                         start in :seon.agent/namespace."}
                    :seon.db/ref]
               :entity [:map {… unchanged …}
                        [:seon.runtime/agent :seon.runtime/agent]
                        [:seon.runtime/ns {:optional true} :seon.runtime/ns]   ; NEW
                        [:seon.runtime/turns {:optional true} :seon.runtime/turns] ; Q4
                        [:seon.runtime/trigger {:optional true} :seon.runtime/trigger] ; Q4
                        [:seon.runtime/listens {:optional true} :seon.runtime/listens]]}

;; seon.turn.edn — retire :seon.turn/starting-ns from :seon.turn/turn and from every request schema
;;   (system-run-request, generated-run-request, record-evaluated(-call)-request)
;; seon.agent.edn — retire [:seon.turn/starting-ns {:optional true}] from :seon.agent/source-submission-request
;; seon.cluster.eval.edn — retire [:seon.sci.eval/ending-ns …] from :receipt and :settle-request;
;;   :seon.sci.eval/ending-ns stays in seon.sci.eval.edn:207 as the in-memory evaluation key
```

The settlement adds, in the same `settle-batch!` transaction (and the single-form `settle!`, refusal arm,
`append-generated` settlement and `record-evaluated-call`), exactly:

```clojure
{:db/id "namespace:probe.eta" :seon.ns/name 'probe.eta}              ; upsert: 0 datoms if it exists
{:seon.agent/id "root"
 :seon.agent/runtime {:seon.runtime/agent <agent-eid>                 ; identity upsert, as open-call does
                      :seon.runtime/ns "namespace:probe.eta"}}        ; 0 datoms if unchanged
```

The name is the in-memory `:seon.sci.eval/ending-ns` of the last evaluation in the batch (the loop already
threads it, `turn.clj:5184`, from SCI's `*ns*` captured at `sci/eval.clj:4755`, so nested `in-ns` is
covered, answering t4-c's point (b)). The same `namespace-rows` helper also upserts every evaluated form's
`ns`, closing problem 3. The shape is the one `open-call` already commits every run (`turn.clj:464-469`);
root's probe showed the namespace upsert plus ref is 4 datoms total for a new namespace.

Readers after the change:

| Need | Target read |
|---|---|
| run start (plan-call, system-run-plan, generated runs, submit path, resume) | `(or runtime-ns home)`; one helper in `seon.turn`, replacing `previous-ending-namespace`, `fold-namespace`, and the submit path's lookup |
| provider segmentation (`freeze!`), system turns, appended generated forms | the same helper (fixes problems 2, 5) |
| namespace half of context | `acquired-root-namespace-eid` reads `runtime/ns` before home (fixes 4) |
| where a past form ran | its own `:seon.cluster.eval/ns` ref (no fallback chain) |
| `:seon.repl/ns` "you moved" | dropped from the emission: the next entry's `ns=>` prompt shows it, and the next turn opens at the session namespace; MCP replies add the session namespace once per run |

### 6b. The ref target for a namespace `in-ns` created (t4-c (a))

Today's declaration (`resources/seon/schemas/seon.ns.edn:10-15`): `:seon.ns/name` is `[:symbol
{:seon.db/identity true :seon.program/row-schema :seon.ns/ns :seon.program/source-attribute
:seon.ns/source}]` and the `:seon.ns/ns` entity map declares `:seon.program/partition :seon.program`, with the
comment at `:17-18`: "A namespace the program's declarations name exists whether or not a form declared it;
only a declared one has a definition digest." So an identity-only row is already an admitted shape of a
program-partition entity. What merges is decided by digest: `program/changed-identities`
(`src/seon/program.cljc:614-655`) starts from entities whose `:seon.program/definition-digest` changed, so a
row holding only `:seon.ns/name` never enters a merge delta or a write-back. `unstewarded-namespaces`
requires `:seon.ns/source` (`problems.clj`), so it ignores them too.

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A (recommended). Upsert the identity-only row at settlement** (today's `namespace-rows`, moved to where the name is known) | ref resolves in the same tx; the scratch `in-ns` writes no digest, so no merge, write-back or program change | ~5 lines: call the existing helper from the settlement builders | an identity-only program row remains on the agent's branch after the agent leaves; it is invisible to merge and problems; a later `(ns x)` declaration upserts onto it |
| B. Session stores a symbol (`[:symbol]`, a token observation per the data-modeling skill) | survives retraction of the namespace; no row | ~0 lines; readers resolve `[:seon.ns/name sym]` (17 µs) | contradicts the owner's "it should be a ref"; a reader that pulls through the session cannot follow it; per-evaluation `ns` stays a ref and still needs A for problem 3 |
| C. Split identity: a data-partition "namespace name" entity beside `:seon.ns/ns` | partition purity | a second identity for one thing (AGENTS: one schema per identity) | two identities to keep consistent; rejected |

A leaves one open edge to prove, not to design: a `defn` in an identity-only namespace. `analyze-settlement`
throws when `seon.fn/analyze-form` answers `:seon.fn/namespace-unresolvable` (`turn.clj:1013-1033`); whether
a bare row counts as resolvable is probe P0.2.

### 6c. What disappears

| Removed | Where | Lines (approx.) |
|---|---|---|
| `previous-ending-namespace` | `turn.clj:597-610` | −14 |
| `fold-namespace` + its call | `turn.clj:4533-4546`, `:5330-5334` | −17 |
| starting namespace derivations + refusals + writes | `plan-call :704-714, :727-732`; `system-run-plan :774-781, :787`; `generated-run-tx :906-912`; `open-call :442-463`; `record-evaluated-call :1573-1579, :1620`; `stored-record-content :1546-1549` | −40 |
| submit path's namespace lookup and misreported refusal | `cluster/agent.clj:697-722` (and `:750` key) | −25 |
| home/`'user` fallback chain for a form's namespace | `render/transcript.clj:278-283` | −5 |
| stored `ending-ns` plumbing | `turn.clj:154-156, 1441, 1720-1721, 4512`; `repl.clj:178, 216-217, 510`; `transcript.clj:65, 298, 504, 1732` | −15 |
| starting-ns in writers meaning home | `bootstrap.clj:316, 343`; `issue.clj:1407` | −3 (keys only) |
| schema entries | `seon.turn.edn` (turn + 4 requests), `seon.agent.edn:137-138`, `seon.cluster.eval.edn` receipt + settle-request | −20 (EDN) |
| added: `:seon.runtime/ns` schema, session namespace helper, settlement rows, walk selector | `seon.runtime.edn`, `turn.clj`, `render/walk.clj` | +40 |

Net about **−100 production lines** for P2+P3 before tests. Tests: 76 mentions of starting-ns and 47 of
ending-ns across src/script/test; about 20 test files (listed in §8) convert in the same commit.

### 6d. Adoption on default's live store

`:seon.runtime/ns` is an accretion: an optional attribute on a data-partition entity; publication installs
it incrementally. Retiring `:seon.turn/starting-ns` (59 datoms) and `:seon.sci.eval/ending-ns` (117 datoms)
uses in-place retirement: `f9fc0e0d0` orders a retired attribute's retraction after the program rows whose
publication converts its writers, and `da10c8912` exercised it for two config dials. The retirement must
land in the same commit as the writer conversions, or the first settlement after adoption asserts a retired
attribute. Existing data: no migration. The first settlement of each agent writes its session namespace; until
then runs start in home, which is what they do today for every submission. The owner allows disposable data;
nothing needs a reset. Existing runtime components gain the attribute by upsert.

### 6e. The agent's cluster (config read-scope design, 10192e1ee)

That design proposes one more attachment to the agent record: `:seon.agent/cluster`, an optional ref to
`[:seon.cluster/name n]`, written by `creation-tx` from the `:seon.cluster/name` every creation request
already carries and drops (`seon.agent.edn:84-89`; `cluster/agent.clj:217-241`), read by one
`seon.ai/agent-settings [db agent-id]` (today's private `cluster/prompt.clj:48-72`). It is an agent-record
decision, so it is priced here (Q9–Q12) beside the session.

Facts that bear on it:

- **A prior owner ruling deleted this attribute.** Turn PRD §2 (`agent-record-and-turn-loop-prd-2026-09-07.md`,
  "Questioned and REMOVED from the record"): "cluster attribute — exactly one `:seon.cluster/name` entity
  exists per branch (probed); deleted, with ONE derivation 'the cluster entity of this database value'
  replacing the eleven call sites". Re-adding it reverses that ruling; the owner should say so explicitly.
- **Default still has one cluster row**: `[:find [?n ...] :where [_ :seon.cluster/name ?n]]` answers
  `["default"]`, and `:seon.config/cluster` likewise. The config design's "arbitrary cluster row" sites are
  unsafe only in a database value holding several cluster rows (test worlds, or a branch that received
  another cluster's rows). Partial P0.3, read-only over default's store roster (4 branches;
  the hosted agents' branches are already retired): `:cluster-default` 1 cluster row / 113 agents,
  `:checkpoint-default-f311e0fc…` 1 / 0, `:current-src` 0 / 0, `:db` has neither attribute. No value holds
  two cluster rows; test-harness worlds were not measured.
- **Cluster, branch and session nest.** A branch belongs to one cluster; an agent's session lives in the
  database value of its custody branch; so the session needs no cluster or branch key: identity by agent
  is exact within a value, and each branch holds its own copy of the session (a hosted agent's rows exist
  on default with no runs, and its runs and session on its branch). The cluster question is therefore
  independent of the session design, except that both are "what hangs off the agent".
- **Context**: the cluster name is plumbing for settings resolution, not something the agent's context
  shows; attaching it does not change the agent half's token cost.

## 7. Prior art, reconciled

From `docs/research/agent-platform/repl-session-prior-art-2026-09-27.md` (08bd655aa):

| Lesson | Adopted? |
|---|---|
| 1. Session holds the current ns, written once per message (nREPL `session.clj:207`) | **Yes**: once per run, in the settlement. `:db/noHistory` not needed: an unchanged namespace re-asserted emits no datom (data-modeling guide §5, `transaction.cljc:587-625`), so the attribute only changes when the agent moves, and its history answers "where was the agent at t" cheaply. |
| 2. Each form records its ns | **Yes**, already: `:seon.cluster.eval/ns`. The "after" namespace is not stored per form (it is the next form's ns, or the session's). |
| 3. History as entities with ordinal under a unique (session, ordinal) key | **Already**: `seon.id/evaluation(run, ordinal)` is that key. No tuple needed. |
| 4. Point from the record to the session in one direction, not a growing cardinality-many | **Partly a conflict.** The agent → runtime edge is one component (does not grow): kept, because the owner's agent-centric rule wants a ref from the agent. `:seon.runtime/turns` *is* the growing list the lesson warns against, and duplicates `:seon.turn/agent`: Q4 recommends deleting it and walking the reverse ref, which is still a walk from the agent (AVET on a ref, proportional to this agent). |
| 5. Keep document order and run order distinct | Run order only; views are derived. No change. |
| 6. Store value digests, not printed text | **Conflict with Seon law**: AGENTS "Save exact shown text"; turn PRD §15 stores shown text deliberately as the observation. Not adopted. The render cache is already content-keyed (`seon.cache`). |
| 7. Resume from facts; retention is a policy | **Yes**: the session ns is a fact; resume starts there. Compaction keeps it (Q5). |
| 8. Tool/render evaluations never move the session | **Yes by construction**: render forms evaluate with no run (`sci/eval.clj:4060-4110`), so no settlement writes the session. System turns are runs but start and (never `in-ns`ing) end at the session ns, so they leave it unchanged. |

Probes the survey asked for: (a) one end-of-run write is exact — yes, because the write takes SCI's `*ns*`
after the last form, not the basis, and every form keeps its own `ns`; P0.1 proves it live. (b) `rseek` vs
whole-history pull: the measured cost is the evidence decode (1.5 s), not the walk (3 ms raw); P1 first. (c)
nobody reads the session ns's history: the attribute is new, so true by construction.

## 8. Phased plan

Every slice is one loadable commit that converts its callers and tests; root runs the named tests centrally.

| Phase | Owner files | Change | Tests (root runs) | Risk |
|---|---|---|---|---|
| **P0 probes** (no source) | none; disposable branch at root's discretion | P0.1 two-form run `(do (in-ns 'p.x)) (+ 1 2)` and a 3-form run with top-level `in-ns` at form 2; record settlement outcome (confirms or refutes problem 3). P0.2 a `defn` after `in-ns` to an identity-only namespace. P0.3 count `:seon.cluster/name` rows in a hosted agent's branch value and in a test-harness world (decides Q9). | — | none |
| **P1 read evidence off the history path** | `src/seon/db.clj` (read-evidence plan contents), `src/seon/eval.clj`, `src/seon/turn.clj` (`latest-evaluations` selector) | Pull no `dependency-plan` for display; store bounded plans (patterns the evidence currency check needs, not every matched entity). Separate issue; needs its own design review. | `seon.eval-test`, `seon.turn-test`, read-evidence currency tests | medium: currency checks must keep reading what they need |
| **P2a session write + read** | `resources/seon/schemas/seon.runtime.edn`, `src/seon/turn.clj`, `src/seon/cluster/agent.clj` | add `:seon.runtime/ns`; settlement upserts namespaces and the session; one start-namespace helper used by plan/system/generated/submit/resume/segmentation/system turns | regressions R1–R4, R6 (§9) in `test/seon/cluster/agent_test.clj`, `test/seon/turn_test.clj` | settlement tx shape; refusal arm |
| **P2b retire the copies** | `seon.turn.edn`, `seon.agent.edn`, `seon.cluster.eval.edn`, `src/seon/repl.clj`, `src/seon/render/transcript.clj`, `src/seon/bootstrap.clj`, `src/seon/issue.clj`, `src/seon/cluster.clj`, the ~20 test files naming starting-ns/ending-ns (`receipt_set_attributes_test`, `settlement_receipt_provenance_test`, `data_shapes_test`, `concurrency_independence_test`, `repl_test`, `repl_parity_test`, `turn_disposition_test`, `loop_proof_test`, `agent_situation_test`, `fn_test`, `turn_loop_test`, `turn_test`, `cluster/prompt_test`, `render_source_test`, `cluster/evaluate_source(s)_test`, `cluster/host_test`, `cluster/agent_test`, `cluster/turn_test`, `cluster/problem_routing_test`) | delete starting-ns and stored ending-ns; scripted sweep then hand-fix | those namespaces + `seon.cluster.program-change-test` for retirement | wide test sweep; must be one commit with P2a's writers or a HEAD that asserts retired attributes |
| **P2c texts** | `script/seon/dev/mcp.clj` | correct `:406`, `:777`; SCI `namespace` parameter: Q8 | `seon.dev.mcp-bridge-test` | tool contract wording |
| **P3 namespace half** | `src/seon/render/walk.clj`, `src/seon/turn.clj` (`declared-sources` caller) | namespace half from `runtime/ns`; `(dir <ns>)` generated read keyed by it | R5 in `test/seon/cluster/prompt_test.clj` | opening byte identity changes once (turn PRD §16a provisional) |
| **P4 one history relation** (after Q4) | `seon.runtime.edn`, `turn.clj`, `eval.clj`, `cluster/status.clj`, `cluster/agent.clj`, `render/web.clj`, `render/transcript.clj` | delete `:seon.runtime/turns` and `:seon.runtime/trigger`; readers walk reverse `:seon.turn/agent`; latest trigger = trigger of the newest run | status, transcript, web debug, turn tests | 10 readers; agent retraction no longer cascades runs (decide in Q4) |
| **P5 deletions** | `src/seon/context.clj`, `seon.context.contribution.edn` | delete selection/append/compact (after a current-reference check) | `seon.context-test` if present | low |
| **P6 ownership** | `turn.clj` `form-owner`, `render.clj` `namespace-owner` | read `:seon.ns/steward` instead of assignment | problem routing tests | routing changes for shared namespaces |

Order: P0 → P2a+P2b (one landing, the owner's direct request) → P3 → P1 in parallel (different owner files
except `turn.clj`, so after P2) → P4/P5/P6 as owner rulings arrive. P2a+P2b is about +40/−140 production
lines, inside the ~100-added-lines rule.

## 9. Class regressions to add

- **R1** 3-form run, form 2 is a top-level `(in-ns 'probe.b)`: form 1's `ns` is the start, forms 2–3 record
  `probe.b` as follows; session ns = `probe.b`; the next run's form 0 runs in `probe.b`.
- **R2** the same with a nested `(do (in-ns 'probe.c))` at form 1 of 2: settlement commits (problem 3);
  form 2's `ns` ref resolves to an identity-only row; session = `probe.c`.
- **R3** scratch namespace: after R2, `program/changed-identities` from the run's base contains no
  `[:seon.ns/name probe.c]`, and a `prepare-merge!` of the agent's branch has no namespace in its delta.
- **R4** submission and system turn continue: a submitted run and the following system turn both start at
  the session ns, not home.
- **R5** rendering reads the session: after `in-ns`, the next prompt contains `(dir probe.b)`'s generated
  read and still contains the earlier evaluations with their `my.agents.x=>` prompts (agent half kept,
  namespace half swapped).
- **R6** retraction: `my.program/remove-ns!` of the session's namespace sweeps `:seon.runtime/ns`; the
  next run starts in home; compaction (`turn/compact!`) leaves the session ns intact.

## 10. Open questions for the owner

**Q1. Where the session's current namespace lives.**
(a) *Recommended*: `:seon.runtime/ns` on the existing runtime component: no new entity, identity and upsert
already exist, ~+10 lines schema/writer. Sacrifice: the component is named `runtime`, not the 09-07 PRD's
ruled `:seon.agent/sci` (`agent-record-and-repl-response-prd-2026-09-07.md` §3 "`:seon.agent/sci` … current
namespace, surviving defs"). (b) Rename runtime to `:seon.agent/sci` while adding: honors the ruled name;
~+30/−30 lines and a retirement of four attributes. (c) A scalar ref on the agent, `:seon.agent/ns`: one
hop, 1 line, but beside `:seon.agent/namespace` (home) the two are easy to confuse and it is not "its own
entity" as asked.

**Q2. Identity-only namespace rows a run creates.** (a) *Recommended*: program-partition identity-only rows
(today's shape, §6b A); ~5 lines; they never merge (no digest). (b) Session holds a symbol; 0 lines; not a
ref. (c) Declare identity-only namespace rows data-partition by a separate entity schema: a second identity,
rejected.

**Q3. Per-evaluation namespace.** (a) *Recommended*: keep `:seon.cluster.eval/ns` (where it ran), retire
stored `ending-ns`; −15 lines. (b) Keep both: 0 lines, duplicate stays. (c) Only the session holds a
namespace: history loses where each form ran, against the owner's rule that history shows it.

**Q4. The run ↔ agent relation.** (a) Status quo: two edges, two walks; 0 lines. (b) *Recommended*: delete
`:seon.runtime/turns` and the `:seon.runtime/trigger` mirror; history is the reverse of `:seon.turn/agent`
(one hop from the agent, prior-art lesson 4, turn PRD §2); ~−40 lines over 10 readers; agent retraction
stops cascading runs, so archival keeps them (it retains agents anyway). (c) Delete `:seon.turn/agent` and
keep the component: 47 mentions to convert, and run → agent becomes two hops.

**Q5. Compaction and archival.** (a) *Recommended*: compaction retracts evaluations only; the session ns and
runs survive; the regenerated opening renders the namespace half from the session. (b) Compaction also
resets the session to home: a restart-like clean slate, surprising after an `in-ns`. Archival: unchanged
(retained record).

**Q6. A refused settlement's namespace.** (a) *Recommended*: the refusal arm still writes the session ns of
the last form that evaluated, because SCI already moved (defs in that namespace exist in the context). (b)
Leave the session unchanged on refusal: the next run starts where the context no longer is.

**Q7. Authorship of outside-agent MCP forms.** (a) Record `:agent` for MCP SCI runs (they are the outside
agent's own forms): small change in the submission path; affects the refresh's system-only rules. (b) Keep
`:system`: 0 lines; history mislabels. Recommendation: (a), as its own slice.

**Q8. Explicit starting namespace on a request.** (a) *Recommended*: remove the request key; a caller that
wants another namespace sends `(in-ns 'x)` as its first form, which then shows in history; MCP's SCI
`namespace` parameter prepends that form. (b) Keep the key as an override that also moves the session:
two ways to move. (c) Keep it as a one-run override that does not move the session: the nREPL `:ns`
semantics (prior art §1), but a third namespace source.

**Q9. How an agent reaches its cluster** (config design 10192e1ee; §6e).
(a) One-hop ref `:seon.agent/cluster` written at creation (that design's recommendation): ~1 schema line,
~1 writer line, the settings reader moved (net ~0); explicit, survives a value holding several cluster
rows. Sacrifice: reverses turn PRD §2's deletion; a second statement of a fact the database value already
implies; agents created before it refuse agent-scoped reads, so **RESET NEEDED** on default (data is
disposable; no backfill migration). (b) Derive "the cluster entity of this database value" once, as PRD §2
ruled, and make the writer refuse a second `:seon.cluster/name` in one value: 0 stored facts, no reset;
cost ~10 lines for the refusal plus converting the arbitrary-row sites to the one derivation; sacrifice:
fails where a value legitimately holds several cluster rows, if any exists (P0.3 decides). (c) Reach it
through the session (session per agent+cluster): rejected; the session already lives inside one cluster's
value. **Recommendation: (b) if P0.3 finds one cluster row in every value an agent runs in; otherwise (a).**

**Q10. Deletion semantics of the agent → cluster ref, if Q9(a).** (a) Optional ref, sweeps when a cluster row
is retracted (that design's choice; accretion): the agent then refuses settings reads by name. (b) Required
ref: a cluster row cannot be retracted while agents name it; validation must select the agent rows. No
writer retracts a cluster row today to my knowledge (unverified); recommend (a).

**Q11. A hosted agent admitted on both the cluster and its branch (`cluster/host.clj:5-67`), if Q9(a).**
(a) `home-call` refuses a cluster mismatch as it refuses a branch mismatch: a few lines, one more refusal.
(b) Trust both writes to carry the same name: 0 lines; a divergence surfaces later as wrong settings.
Recommend (a).

**Q12. An issue worker's cluster (A12), if Q9(a).** (a) Inherit the creating parent's cluster (that design's
proposal). (b) The issue's cluster. In one database value these are the same cluster; they differ only for
a cross-cluster assignment, which nothing creates today. Recommend (a), and let the creation writer refuse a
worker whose parent and issue name different clusters rather than choosing silently.

## 11. Evidence (read-only probes, JVM mode, cluster default, session default)

All forms ran with `read_only true` against `(seon.db/db (seon.cluster.boot/connection "default"))`; none
transacted. Timings are wall-clock on the calling thread, warm unless the first sample.

1. Counts: `(count (datahike.api/datoms d :aevt a))` for `:seon.agent/id` 113, `:seon.turn/id` 59,
   `:seon.cluster.eval/id` 117, `:seon.cluster.eval/ns` 117, `:seon.sci.eval/ending-ns` 117,
   `:seon.turn/starting-ns` 59, `:seon.ns/name` 487, `:seon.runtime/turns` 59; basis 536879662.
2. Namespace agreement over all 117 evaluations (query joining run, starting-ns, home, eval ns, ending-ns):
   0 rows where start ≠ home, eval ns ≠ start, or ending ≠ eval ns: default holds no namespace movement.
3. `seon.eval/of-agent d "root"` ×5: 1,552.9 / 1,593.9 / 1,635.2 / 1,561.3 / 1,818.7 ms; with selector
   `[:seon.cluster.eval/source :seon.eval/shown]`: 3.4 / 0.51 / 0.43 / 0.41 / 0.42 ms.
4. Same 117 entities: `datahike.api/pull-many` full selector 3.5/3.1/3.1 ms; `seon.db/pull-many` full
   1,501/1,532/1,530 ms; `seon.db/pull-many [{:seon.cluster.eval/read-evidence [*]}]` 0.4–0.8 ms, while
   `[*]` 1,544–1,571 ms.
5. Per entity `seon.db/pull [*]`: slowest 129.1 ms (eid 55732, source `(seon.error/for-agent (seon.db/db)
   "root")`), sum 1,536.9 ms; per attribute on 55732 only `:seon.cluster.eval/read-evidence` costs (125.9
   ms); its component 55733 holds a 1,501,080-character `:datahike.read/dependency-plan`.
6. Plans: 451 values, 17,572,807 characters, 286 distinct, median 409.
7. `turn/latest-evaluations d "root"`: 1,558 / 1,604 / 1,557 ms. `transcript/history d nil "root" 200`: 30 /
   22.5 / 19.2 ms; `candidate-entity-ids`: 19.4 / 0.8 / 0.6 ms.
8. Namespace reads (µs, 5 warm samples): two-hop `seon.db/pull` 30–58; raw 15–18;
   `previous-ending-namespace` 103–118; `agent-namespace` 85–91; `[:seon.ns/name …]` lookup 16–18.
9. `seon.sci.eval/directory-value d ns true`, chars/4 of `pr-str`: listed in §3.
10. Root's agent row: `:seon.agent/{branch id namespace plan runtime settings}`; runtime 34520 holds
    `:seon.runtime/{agent trigger}` and 59 `turns`; home namespace row has a definition digest and
    `:seon.ns/steward`.

11. Store roster (`seon.cluster.registry/roster` of the running instance's store; each head read with
    `seon.cluster.source/commit-database` and released): counts of `:seon.cluster/name` / `:seon.agent/id`
    datoms per branch as in §6e.

Unavailable evidence, not claimed: no live multi-form `in-ns` settlement (P0.1/P0.2 need a disposable
branch; this lane did not create one), no full prompt build timing through `seon.cluster.prompt/prompt`
(it requires the agent's SCI context and writes the render cache), no test runs.

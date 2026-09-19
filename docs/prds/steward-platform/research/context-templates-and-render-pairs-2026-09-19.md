---
type: research
status: complete
created: 2026-09-19
tags: [render, context, templates, agent]
---

# Context templates and render pairs — how an agent's opening is generated today

Read-only research lane. Every claim carries `file:line`; every number was
measured statically from the working tree at branch `steward-platform`
(HEAD `b9c4cfa58`, other lanes' uncommitted edits present and untouched). No
JVM, gate, operator or lane was launched; the two recorded openings quoted
below are the committed bytes, not a live capture.

Authorities read end to end: `AGENTS.md` §1, §2.4, §3 (the vocabulary rows for
render function, `:seon.render/ai`, block, system turn, the agent's history,
compaction, render profile); `docs/seon/architecture/context.md`;
`docs/seon/architecture/ui.md`; the turn PRD §13–§18d
(`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md:879-1352`);
`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md`,
`issue-context-trials-2026-09-16.md`, `task-prototype-2026-09-16.md`;
`docs/prds/steward-platform/research/issue-family-opening-2026-09-16.txt`,
`live-trial-1-2026-09-17.md` + `live-trial-1-opening-2026-09-17.txt`,
`entity-pairs-2026-09-16.md`, `render-no-fallback-2026-09-17.md`.
`namespace-data-model-2026-09-16.md` and `issue-triage-design-2026-09-17.md`
were read in the sections this note cites, not end to end; that limit is
stated rather than hidden.

The measurement scripts are
`/private/tmp/.../scratchpad/entities.py` (scratch, quoted verbatim in §1.1)
and `pairs.py`/`resolve.py`; the one that produces the census numbers is
reproduced inline so it can be rerun.

---

## 0. Verdict in one paragraph

The owner's model — **"link data on the agent's entity and it auto-renders
into teachable thinking comments and forms"** — is already the implemented
mechanism, not an aspiration. `seon.turn/declared-sources`
(`src/seon/turn.clj:1883`) builds the opening by walking the agent entity
through `seon.render.walk/neighborhood` (`src/seon/render/walk.clj:718`) at
distance 1, taking each block's rendered AI **source**, parsing it into forms
(`seon.turn/planned-sources`, `src/seon/turn.clj:3083`) and evaluating and
storing them as ordinary evaluations. Membership is one list of attributes,
`:seon.render/units`, declared on the agent's entity map
(`resources/seon/schemas/seon.agent.edn:6-14`). Three things are missing:
(1) **render-pair coverage is 23 of 145 stored entity maps (15.9 %)**, so the
value printer — explicitly a last resort — is the answer for 84 %;
(2) **the issue block, the one entity meant to BE the template, is the only
agent unit whose renderer returns `:string` instead of `:seon.render/source`**
(`src/seon/issue.clj:733`), so it emits prose rather than a comment and a
form — it is precisely outside the owner's model;
(3) **a template's linked entities never reach the agent's context**, because
`declared-acquisition` (`src/seon/render/walk.clj:664-700`) orders the units
of the WALK ROOT only, and the opening walks at distance 1
(`src/seon/render/walk.clj:733`). The issue's own units
(`:seon.issue/functions :seon.issue/tests :seon.issue/errors
:seon.issue/members`, `resources/seon/schemas/seon.issue.edn:46`) are
therefore declared and never rendered into an opening.

---

## 1. Render-pair coverage, measured

### 1.1 The script

```python
# for each resources/seon/schemas/*.edn, read the top-level registry map,
# keep every key whose form contains :seon.db/attributes true (a STORED
# entity map), and record its :seon.render/ai and :seon.render/html —
# including the #:seon.render{:ai … :html …} namespaced-map spelling.
for k, v in top_level_forms(src, start):
    if ':seon.db/attributes true' not in v: continue
    ai   = re.search(r':seon\.render/ai\s+([^\s,}\]]+)', v) or \
           re.search(r'#:seon\.render\{\s*:ai\s+([^\s,}\]]+)', v)
    html = re.search(r':seon\.render/html\s+([^\s,}\]]+)', v) or \
           re.search(r'#:seon\.render\{[^}]*:html\s+([^\s,}\]]+)', v)
```

### 1.2 The numbers

| measure | value |
|---|---:|
| `.edn` files under `resources/seon/schemas/` | 210 |
| stored entity maps (`:seon.db/attributes true`) | **145** |
| declaring BOTH `:seon.render/ai` and `:seon.render/html` | **23 (15.9 %)** |
| declaring exactly one half of the pair | **0** |
| declaring neither — the default attribute-map printer answers | **122 (84.1 %)** |
| …of those, error entities merged onto `:seon.error/base` | 59 |
| …of those, ordinary domain entities | 63 |
| stored entity maps declaring `:seon.render/units` | **2** (`seon.agent/agent`, `seon.issue/issue`) |
| attribute-level (unit) render pairs declared | **8** |
| distinct `:seon.render/ai` declaration sites of any kind | 392 (290 of them the one shared `seon.error/render-ai` on declared error classes) |

One correction to the census the detector cannot see: `:seon.eval/entity`
(`resources/seon/schemas/seon.eval.edn:6-9`) declares
`#:seon.render{:ai seon.repl/render-ai :html seon.repl/render-html}` — the
history pair, the single most-used pair in the system — but does **not**
declare `:seon.db/attributes true`, so it is outside both the table above and
the `seon.issue.detect/entity-map-without-pair` detector's own query
(`src/seon/issue/detect.clj:74-77`, which requires `[?e :seon.db/attributes true]`).
That is an absence-reads-as-health hole in the detector: the evaluation entity
is stored and paired, yet neither set contains it.

### 1.3 The 23 paired entity maps — every pair symbol resolves

Column 4 is `rg`-equivalent resolution of the symbol to a `defn` in `src/`;
column 5 is reachability from the agent entity's declared units at the
distance the opening actually walks (1).

| entity map | `:seon.render/ai` | `:seon.render/html` | resolves | in an opening? |
|---|---|---|---|---|
| `my.note/note` | `seon.note/render-note-ai` | `seon.note/render-note-html` | `src/seon/note.clj:56` / `:111` | yes (`:my.note/_agent`) |
| `my.plan/entity` | `seon.plan/render-plan-ai` | `seon.plan/render-plan-html` | `src/seon/plan.clj:1446` / `:1479` | yes (`:seon.agent/plan`) |
| `my.plan.item/item` | `seon.plan/render-item-ai` | `seon.plan/render-item-html` | `src/seon/plan.clj:1322` / `:1354` | no (inside the plan block) |
| `seon.agent/agent` | `seon.cluster.agent/render-identity-ai` | `…/render-identity-html` | `src/seon/cluster/agent.clj:224` / `:258` | yes (the root block) |
| `seon.ai/attempt` | `seon.ai/attempt-ai` | `seon.ai/attempt-html` | `src/seon/ai.clj:109` / `:115` | no |
| `seon.ai.model/provider-entity` | `seon.ai/provider-ai` | `seon.ai/provider-html` | `src/seon/ai.clj:260` / `:270` | no |
| `seon.ai.model/entity` | `seon.ai/model-ai` | `seon.ai/model-html` | `src/seon/ai.clj:193` / `:228` | no |
| `seon.cluster/cluster` | `seon.cluster/render-ai` | `seon.cluster/render-html` | `src/seon/cluster.clj:202` / `:217` | no |
| `seon.cluster.instruction/instruction` | `…/instruction-ai` | `…/instruction-html` | `src/seon/cluster/instruction.clj:68` / `:74` | no |
| `seon.config/settings` | `seon.agent/render-settings-ai` | `seon.agent/render-settings-html` | `src/seon/agent.clj:153` / `:159` | yes (`:seon.agent/settings`) |
| `seon.context.capture/capture` | `seon.context/capture-ai` | `seon.context/capture-html` | `src/seon/context.clj:440` / `:451` | no |
| `seon.effect/receipt` | `seon.effect/render-ai` | `seon.effect/render-html` | `src/seon/effect.clj:71` / `:101` | no |
| `seon.error/error` | `seon.error/render-ai` | `seon.error/render-html` | `src/seon/error.clj:1852` / `:1864` | yes (`:seon.error/of-steward`) |
| `seon.fn/fn` | `seon.render.ns/function-ai` | `seon.render.ns/function-html` | `src/seon/render/ns.clj:844` / `:876` | **no — see §2.3** |
| `seon.issue/issue` | `seon.issue/render-ai` | `seon.issue/render-html` | `src/seon/issue.clj:730` / `:738` | yes (`:seon.issue/_agent`) |
| `seon.message/message` | `seon.cluster.message/render-ai` | `…/render-html` | `src/seon/cluster/message.clj:322` / `:330` | yes (`:seon.message/_to`) |
| `seon.ns/ns` | `seon.render.ns/render-ai` | `seon.render.ns/render-html` | `src/seon/render/ns.clj:895` / `:928` | yes (`:seon.agent/namespace`) |
| `seon.ns.alias/binding` | `seon.render.ns/render-alias-ai` | `…-html` | `src/seon/render/ns.clj:304` / `:317` | no |
| `seon.ns.import/binding` | `seon.render.ns/render-import-ai` | `…-html` | `src/seon/render/ns.clj:349` / `:358` | no |
| `seon.ns.refer/binding` | `seon.render.ns/render-refer-ai` | `…-html` | `src/seon/render/ns.clj:325` / `:339` | no |
| `seon.runtime/entity` | `seon.render.transcript/render-runtime-ai` | `…-html` | `src/seon/render/transcript.clj:1025` / `:2356` | yes (`:seon.agent/runtime`) |
| `seon.test/test` | `seon.render.test/render-ai` | `seon.render.test/render-html` | `src/seon/render/test.clj:41` / `:92` | **no — see §2.3** |
| `seon.turn/turn` | `seon.render.transcript/render-run-ai` | `…/render-run-html` | `src/seon/render/transcript.clj:712` / `:826` | no (inside runtime) |

**Zero broken pair symbols.** Every declared pair resolves to a `defn` in
`src/`, and `seon.schema/build-projection` would refuse one that did not:
`render-declarations-in` (`src/seon/schema.clj:1563`) collects every declared
`:seon.render/ai|html|form` and `render-contract-refusal!`
(`src/seon/schema.clj:1756-1761`) refuses the whole projection when the named
function's contract is not coherent with the schema. That is the gate §3
depends on.

### 1.4 The 63 unpaired ordinary domain entities

Ranked below in §5(b). Notable members: `seon.schema/schema`,
`seon.fn.file/file`, `seon.lint/finding`, `seon.issue.citation/citation`,
`seon.test.failure/failure`, `seon.test.report/report`,
`seon.test.member/member`, `seon.error.occurrence/occurrence`,
`seon.error.evidence/entity`, `seon.error.location/entity`,
`seon.instrument.humanized/entity`, `seon.schedule/schedule`,
`seon.schedule.task/task`, `seon.db.process/process`, `seon.operator/footprint`,
and the whole 16-member `seon.maintenance.result/*` family.

The 59 error entities merged onto `:seon.error/base` are `[:and
{:seon.db/attributes true} :seon.error/base [:map {:seon.db/attributes true}
…]]` (example: `resources/seon/schemas/seon.program.edn:245-252`). Whether
those select `seon.error/error`'s pair through `schema-producers`'
`matching-shapes-in` or produce an ambiguity refusal
(`src/seon/render.clj:323-378`) is a live question this read-only lane could
not settle; it needs one probe against a running projection and should not be
assumed either way.

---

## 2. The agent entity's context graph

### 2.1 The mechanism, end to end

1. `seon.turn/system-turn` (`src/seon/turn.clj:2072`) asks
   `declared-sources` (`:1883`) for the declared opening.
2. `declared-sources` calls `seon.render.walk/neighborhood`
   (`src/seon/render/walk.clj:718`) on `[:seon.agent/id <id>]` with
   `:seon.render/output :seon.render/ai` and no distance → **distance 1**
   (`:733`).
3. `declared-acquisition` (`:664`) reads `declared-concerns` (`:90`) — the
   `:seon.render/units` vector on every schema shape the ROOT entity matches —
   and orders the walk's members by it.
4. Per member, `neighborhood` (`:766-780`) builds a render request, stamping
   `:seon.render.walk/attribute` only for a member it synthesized FOR an
   attribute (`scoped-attribute`, `:701`), and calls `render/render-call`.
5. `seon.render/render-call` (`src/seon/render.clj:1370`) selects a producer
   through five ordered stages (`:445`) — explicit-value, explicit-request,
   **namespace**, schema, floor — and, when the selected producer's declared
   return is `:seon.render/source` or `:seon.render/source-blocks`
   (`source-producer?`, `:265`; `source-return?`, `:252`), records the text as
   `:seon.render.call/source` (`:1502`).
6. `declared-sources` (`:1899-1921`) takes only those source blocks, parses
   them with `planned-sources` (`src/seon/turn.clj:3083`), and
   `system-turn` evaluates and stores each as one `:seon.eval` entity.
7. The prompt is then nothing but those stored evaluations:
   `seon.render.walk/history` (`src/seon/render/walk.clj:929`) renders
   `seon.eval/of-agent` through `seon.repl/render-ai`
   (`src/seon/repl.clj:447`), and `seon.repl/text` (`:255`) is the one grammar.

So a "template" today is literally: **an attribute named in
`:seon.render/units` on the agent entity map, whose value's entity schema
declares an AI pair returning `:seon.render/source`.** That is the whole
contract. The owner's model holds exactly.

### 2.2 The agent's declared units — all 8

`resources/seon/schemas/seon.agent.edn:6-14`:

```clojure
:seon.render/units
[:seon.agent/plan :seon.issue/_agent :seon.message/_to :seon.agent/settings
 :my.note/_agent :seon.agent/namespace :seon.agent/runtime :seon.error/of-steward]
```

| unit | attribute declares | block comes from | emits source? |
|---|---|---|---|
| `:seon.agent/plan` | component + `:seon.render/ai seon.plan/render-plan-ai` (`seon.agent.edn:plan`) | synthesized FOR the attribute | **yes** (`:seon.render/source`, `src/seon/plan.clj:1444`) |
| `:seon.issue/_agent` | nothing — `:seon.issue/agent` is a listened ref with no render property (`seon.issue.edn:29-32`) | the REACHED issue entities, through `seon.issue/issue`'s pair | **no** — returns `:string` (`src/seon/issue.clj:733`) |
| `:seon.message/_to` | `:seon.render/ai seon.cluster.message/render-inbox-ai` + `:seon.render/form` (`seon.message.edn:22-29`) | synthesized FOR the attribute (reverse + form present) | **yes** (`src/seon/cluster/message.clj:411`) |
| `:seon.agent/settings` | component + pair (`seon.agent.edn:settings`) | synthesized | yes |
| `:my.note/_agent` | pair + `:seon.render/form` (`my.note.edn:agent`) | synthesized | yes |
| `:seon.agent/namespace` | plain ref, no render property | the REACHED namespace entity, `seon.ns/ns`'s pair | yes |
| `:seon.agent/runtime` | component, **no render property** | the REACHED runtime entity, `seon.runtime/entity`'s pair | yes |
| `:seon.error/of-steward` | `:seon.render/derived true` + pair + form (`seon.error.edn:217-221`) | synthesized (derived) | yes |

`declared-acquisition` synthesizes a block for a unit only when the attribute
carries `:seon.render/derived`, or is a reverse ref with `:seon.render/form`,
or is a forward component with the requested output property
(`src/seon/render/walk.clj:676-680`). Otherwise the unit contributes the
entities the walk REACHED through it. `:seon.issue/_agent` and
`:seon.agent/namespace` are in the second class.

### 2.3 What a "task/issue template" contributes today — and what it cannot

`seon.issue/issue` declares four units
(`resources/seon/schemas/seon.issue.edn:46`):

```clojure
:seon.render/units [:seon.issue/functions :seon.issue/tests
                    :seon.issue/errors :seon.issue/members]
```

**None of them render in an agent opening.** `declared-acquisition` reads the
units of `root` only — the first member of `:seon.render.walk/order`
(`src/seon/render/walk.clj:667-670`) — and the root of the opening walk is the
AGENT. The issue is a distance-1 member; its own units are consulted only when
the issue is itself the walk root (the `/data` browser and the issue's own
page). So the function to refactor, the test to make green and the error to
answer — the entity-pairs lane's whole P2 deliverable
(`entity-pairs-2026-09-16.md`) — are declared, implemented, verified
(17/0/0 and 24/0/0 on the canonical fixture) and **invisible to the worker
they were built for**.

What the issue block does contribute is `seon.issue/status-text`
(`src/seon/issue.clj:698-716`): title, open/resolved, turns remaining, the
exact done-condition form, and per test `passed` / `failed` / `not run` with
the failure message. All of it derived at render time by `status-view`
(`:718`) from the unit's own database — correct per the owner law of
2026-08-29 (derive at the authority).

### 2.4 The recorded openings, measured

| recording | total bytes | the issue block | share |
|---|---:|---:|---:|
| `issue-family-opening-2026-09-16.txt` | 9,901 | **2,904** | 29.3 % |
| `live-trial-1-opening-2026-09-17.txt` | 4,880 | **313** | 6.4 % |
| (same file) `(help)` block | 4,880 | 3,186 | 65.3 % |

**2026-09-16 — the issue block, verbatim (long lines wrapped here with `↩`;
the elision is mine and marked, the bytes are not elided in the file):**

```
user=> ;; My issue. Its tests define done; (my.test/check ...) runs them.
(my.issue/status {:seon.issue/id "agent-form-calls-to-core-namespaces-are-not-indexed"})
#:seon.repl{:value {:db/id 43695, :seon.issue/agent #:db{:id 55159}, ↩
 :seon.issue/budget 1, ↩
 :seon.issue/check-form (my.test/check #:seon.test{:changed [...]}), ↩
 :seon.issue/commits ["5deb40e4e" "7e35df213" "924fdbf3a" "f402c5d3d"], ↩
 :seon.issue/functions #{ ↩
   "Restart the JVM to remove stale loaded Var clojure.core/=; it is absent ↩
    from the published program graph." ↩
   "Restart the JVM to remove stale loaded Var clojure.core/let; …" ↩
   "Restart the JVM to remove stale loaded Var clojure.test/deftest; …" ↩
   "Restart the JVM to remove stale loaded Var clojure.test/is; …" ↩
   "Restart the JVM to remove stale loaded Var my.turn/wait; …" ↩
   "Restart the JVM to remove stale loaded Var seon.db/db; …" ↩
   "Restart the JVM to remove stale loaded Var seon.db/q; …" ↩
   "Restart the JVM to remove stale loaded Var seon.fn/analyze-form; …" ↩
   "Restart the JVM to remove stale loaded Var seon.fn/analyze-forms; …" ↩
   "Restart the JVM to remove stale loaded Var seon.operator/connection; …"}, ↩
 :seon.issue/id "agent-form-calls-to-core-namespaces-are-not-indexed", ↩
 :seon.issue/path "docs/seon/issues/…md", ↩
 :seon.issue/problem "A run form's `:seon.fn/calls` edges are recorded … ↩
   [ELIDED HERE BY THIS NOTE — the file carries the full 1,050-byte problem ↩
    text a second time, byte-identical to :my.plan/objective above it], ↩
 :seon.issue/severity :friction, :seon.issue/status :open, ↩
 :seon.issue/tests #{{:seon.issue.test/state :verified, …, ↩
   :seon.test/pass-count 7, …}}, ↩
 :seon.issue/title "Historical call-edge analyses need re-evaluation"}, ↩
 :result result/e8e04ecc86529, :ms 331}
```

**Judged against the owner's bar** ("teachable thinking comments and forms
explaining what the data is, formatted for ideal and concise background"):
it fails on every clause. The comment is teachable; everything after it is a
raw attribute map through the value printer — the explicit last resort. The
`:db/id 43695` and `#:db{:id 55159}` are unusable by the agent (refork-unstable,
the exact failure `render-no-fallback-2026-09-17.md` records elsewhere). Ten
members of `:seon.issue/functions` are diagnostic STRINGS ("Restart the JVM to
remove stale loaded Var …") where function refs belong — a linked-entity
relation carrying error prose. The 1,050-byte problem text is duplicated
verbatim from the plan block two evaluations earlier. It is 29 % of the whole
opening and teaches nothing. **UGLY OUTPUT IS A DEFECT; this is the sighting.**

**2026-09-17 trial-1 — the same block after `render-ai` was rewritten
(313 bytes, verbatim):**

```
Issue 9b2c2e7bd6d2: still open; 8 turns remaining.
Public function seon.test.arm/arm-contracts! declares no contract
Done condition: (my.test/check
  {:seon.test/changed ["seon.test.runner-test/initialization-acquires-one-projection"]})
seon.test.runner-test/initialization-acquires-one-projection: not verified
```

Concise, honest, and it names the exact completing call — a real improvement.
But it is **prose, with no prompt line, no thinking comment and no executed
form**: `seon.issue/render-ai`'s declared return is `:string`
(`src/seon/issue.clj:733`), so `seon.render/source-producer?`
(`src/seon/render.clj:265`) is false for it, `source-return?` (`:252`) maps
`:string` to `#{:other}`, and `declared-sources` collects no form from it
(`src/seon/turn.clj:1903-1917`). Compare `seon.plan/render-plan-ai`
(`src/seon/plan.clj:1444`, returns `:seon.render/source`) and
`seon.cluster.message/render-ai` (`src/seon/cluster/message.clj:322`, returns
`[:maybe :seon.render/source]`), both of which emit `;; comment` + form.

The fix traded ugliness for the owner's model. The block the whole issue
family exists to deliver is the ONE agent unit that is not a thinking comment
and a form. That is gap #1.

(The trial-1 file records four evaluations and 4,880 bytes
(`live-trial-1-2026-09-17.md:105-113`) while showing four responses with no
prompt lines, so the committed `.txt` is a responses-only capture, not the
prompt grammar. I could not establish read-only which evaluation carried the
issue bytes; the note states that limit rather than inventing a path.)

### 2.5 Two more measured facts about the opening

- **`(help)` is 65 % of a worker's opening** (3,186 of 4,880 bytes). It is a
  fixed 14-line vector plus two derived lines (turn PRD §18a); it is the
  largest single block in the context and it is not a function of the agent's
  data beyond `<ns>` and `Tools:`.
- **The plan block re-states the issue problem verbatim** as
  `:my.plan/objective` (`live-trial-1-opening-2026-09-17.txt`, and the same
  duplication in the 2026-09-16 file). `seon.issue/start!` copies
  `:seon.issue/problem` into the plan objective
  (`issue-family-spec-2026-09-16.md` §2), so every worker pays for the same
  bytes twice. That is a derive-or-die violation: the objective should be the
  issue ref, not a copy.

---

## 3. Can an agent author and install its own render function today?

**Yes, through two seams, both already gated. Nothing new is needed to make
the first one work; the second needs one clarification.**

### 3.1 The namespace selection stage — already the agent-authored seam

`seon.render/selection` runs five stages in order
(`src/seon/render.clj:445`): `:explicit-value`, `:explicit-request`,
**`:namespace`**, `:schema`, `:floor`. The `:namespace` stage
(`namespace-candidates`, `:272-305`) takes **every public contracted function
in the render request's owning namespace**, from the live SCI program snapshot
(`seon.sci.kernel/public-functions-in`, `src/seon/sci/kernel.clj:140-152`),
and keeps the ones whose declared contract accepts the render argument and
returns the requested output schema
(`schema/function-accepts-and-returns-in?`, or `source-producer?` for AI).
Ambiguity (more than one fit) is a typed refusal (`ambiguity`, `:306`).

The owning namespace comes from the walk:
`seon.render.walk/acquired-namespace-name` (`src/seon/render/walk.clj:620`)
returns the one namespace the member or its forward refs name. For the agent's
own entity that is `:seon.agent/namespace` — the agent's own namespace. So:

> **An agent that admits a public function in its own namespace whose contract
> is `[:=> [:cat <the value's schema>] :seon.render/source]` becomes the
> selected AI producer for that value, two stages AHEAD of the schema pair.**

This is selection by contract fit, exactly as the mission asks. It is live
today and needs no schema write.

### 3.2 The schema-property seam — also open, with one gate

An agent can `seon.schema/register!` at runtime; SCI registrations carry
`{:seon.schema.admission/source :agent}` (`src/seon/schema/edn.clj:606`,
`:614`) and go through `admit-changed-identities` →
`assert-complete-contract!` → the same `render-contract-refusal!`
(`src/seon/schema.clj:1756-1761`) that gates a core declaration. So declaring
`:seon.render/ai my.agents.x/render-thing` on a schema is admitted iff the
named function exists in the program graph with a coherent contract — the same
gating as any definition, as the mission asks for.

The one agent-specific restriction found is unrelated to rendering: an
`:agent`-sourced registration referencing a predicate whose transitive call
graph is not proved pure is refused (`src/seon/schema.clj:1301-1320`).

### 3.3 What is actually missing

1. **Nothing teaches it.** No `my.*` function, no `help` line, and no `dir`
   entry names render authoring. `(help)`'s `Tools:` line
   (`live-trial-1-opening-2026-09-17.txt`) lists twelve `my.*` namespaces and
   none of them is about rendering. An agent cannot discover this seam.
2. **No agent-facing surface for "render this value my way".** The natural
   shape is `my.render/pair!` (declare an AI/HTML pair on a schema key I own)
   and `my.render/preview` (render one value through a candidate producer
   before installing) — both thin wrappers over `seon.schema/register!` and
   `seon.render/selection`, per the `my.*`/`seon.*` layering law.
3. **A unit on the agent for agent-authored blocks.** Today the only way an
   agent adds a block to its own opening is to edit
   `resources/seon/schemas/seon.agent.edn`. `:seon.render/units` is a file
   fact, not an agent-writable one. §4/§5 propose closing this by giving the
   template entity — not the agent map — the unit list, and having the
   template's OWN units render (the gap in §2.3).
4. **Provenance.** `seon.program/overrides` (`src/seon/program.cljc:20`)
   already derives "current admission is `:agent` in an indexed `src`
   namespace" as a query. A render pair authored by an agent is an override by
   that same derivation; no new flag is needed, but no surface shows it.

---

## 4. Chat — how a human message reaches context, and what a conversation needs

### 4.1 Today, end to end

1. The web message form POSTs to `/agent/{id}/message`
   (`src/seon/render/route.clj:17`); `seon.render.web/inbound`
   (`src/seon/render/web.clj:2881`) calls
   `seon.cluster.message/inbound-tx` (`src/seon/cluster/message.clj:149`),
   which mints `{:seon.message/id … :seon.message/to [:seon.agent/id id]
   :seon.message/content …}` — and deliberately **no `:seon.message/from`**
   (`:167-169`).
2. `:seon.message/from` is the inside marker
   (`resources/seon/schemas/seon.message.edn:109-115`,
   `:seon.wake/inside true`). Its absence makes a human message an **outside
   wake**, which refills the agent's turn bound — the one structural
   difference between talking to an agent and an agent talking to itself.
3. `:seon.message/to` is `:seon.wake/listen true :seon.wake/opens-turn? true`
   (`seon.message.edn:22-25`): the datom itself wakes the agent's graph. No
   dispatcher.
4. Unansweredness is derived, never stored: `seon.turn/unanswered-wakes`
   (`src/seon/turn.clj:2935`) keeps wake datoms whose `:t` is greater than
   `latest-answering-turn-t` (`:2908`) — the opening `:t` of the latest
   accepted ordinary reply. `unanswered-triggers` (`:2988`) projects that onto
   messages. **This is the done-predicate D1 a conversation already has.**
5. The messages render into the opening through the `:seon.message/_to` unit →
   `seon.cluster.message/render-inbox-ai` (`src/seon/cluster/message.clj:411`)
   → one `;; comment` + a reverse-ref pull, and each reached message through
   `render-ai` (`:322`) → `;; I should read this message and decide how to
   respond.` + `(my.message/read {…})`. This block IS already in the owner's
   model.

### 4.2 What a persistent conversation needs — reuse before add

Existing facts that already carry the whole shape:

| need | existing fact | `file:line` |
|---|---|---|
| who said it | `:seon.message/from` (absent = outside) | `seon.message.edn:109` |
| to whom | `:seon.message/to` (listened, opens a turn) | `:22` |
| the text | `:seon.message/content` | `:130` |
| when | the transaction's `:db/txInstant` (turn PRD §18c) | — |
| what it is about | `:seon.message/about` (a subject identity token, survives deletion) | `:31` |
| **the thread** | `:seon.message/caused-by` — "the earlier message that caused this message", indexed ref | `:103-108` |
| answered? | `seon.turn/unanswered-wakes` by `:t` | `src/seon/turn.clj:2935` |
| handled by which turn | `:seon.turn/handled` (a set of peer refs written at settlement) | `resources/seon/schemas/seon.turn.edn:44` |

**Nothing needs to be added for a conversation to exist. A conversation IS the
transitive closure of `:seon.message/caused-by`.** What is missing is exactly
one thing: a way to render it.

Proposal, in the spirit of "derive, do not remember":

- **No new entity.** A conversation is the `caused-by` chain rooted at a
  message with no `caused-by` whose `to` or `from` is this agent. Add one
  derived attribute on the agent map, `:seon.message/of-agent`, declared
  `:seon.render/derived true` with an AI/HTML pair, exactly the way
  `:seon.error/of-steward` is declared today
  (`resources/seon/schemas/seon.error.edn:217-221`). It joins to the open
  threads, newest last.
- **Attributes: none new.** If a thread needs a human-readable handle, reuse
  `:seon.message/about` on the root message; do not mint a title attribute.
- **Render pair:** `seon.cluster.message/render-thread-ai` emitting
  `;; <n> messages in this thread; the last is unanswered.` plus one
  `seon.db/pull` over the chain, and `…-html` for the page. Reuses
  `render-ai` per message so a message alone and a message in a thread state
  the same facts — the invariant `inbox-html` already keeps
  (`src/seon/cluster/message.clj:434-437`).
- **Done-predicate:** unchanged — `unanswered-wakes` by `:t`. A conversation
  is "done" for this turn when the agent's reply's opening `:t` covers the
  latest inbound wake. There is nothing to store and nothing to settle.
- **Why it is NOT an issue:** an issue's done-predicate is a set of tests or a
  detector's silence (`seon.issue/tests-done-query`,
  `issue-family-spec-2026-09-16.md` §2). A conversation's is a `:t` comparison
  that already exists and already refills the turn bound. Forcing it into the
  issue family would require inventing a test for prose — which
  `task-prototype-2026-09-16.md` §2 already tried and honestly disclaimed
  ("prose quality is not machine-verifiable and is not claimed").

---

## 5. Proposals

### (a) The data model of a context template

**One family, and it is the one that already exists — with the template/instance
split removed rather than added, and the units moved.**

Five lines:

1. **A template is a `:seon.render/units` vector plus a done-predicate,
   nothing else.** It is already a schema property
   (`resources/seon/schemas/seon.issue.edn:46`) and a query; no `:type`, no
   `:kind`, no stamp.
2. **An instance is an entity of that family with an `:seon.issue/agent`** —
   exactly the existing "a row with no agent is unstarted" rule
   (`task-prototype-2026-09-16.md` §5, carried into the issue family). Do not
   re-introduce a second entity.
3. **The subject is a ref, the identity is a value.** `:seon.issue/functions`,
   `/tests`, `/errors`, `/keys`, `/namespaces` already are refs; the
   detector-plus-subject identity (`seon.issue/subject-id`,
   `src/seon/issue.clj:478`) already prevents duplicates. Nothing new.
4. **The linked entities must render** — the single blocking change: make a
   template's OWN `:seon.render/units` contribute blocks when it is a
   distance-1 member of the agent walk (§2.3, `src/seon/render/walk.clj:667`).
5. **The block must emit source, not prose** — change
   `seon.issue/render-ai`'s declared return from `:string` to
   `:seon.render/source` and have it emit `;; thinking comment` + the exact
   completing form, the way `seon.plan/render-plan-ai` does
   (`src/seon/plan.clj:1444-1478`).

The five work kinds the owner named, expressed in this model with **no new
attribute**:

| work | subject refs | done-predicate | extra units it needs rendered |
|---|---|---|---|
| refactor a function | `:seon.issue/functions` | the linked tests verified | `seon.fn/fn` pair (exists, `src/seon/render/ns.clj:844`) |
| improve a schema | `:seon.issue/keys` | detector silent (`entity-map-without-pair`) | a `seon.schema/schema` pair — **does not exist** |
| write tests for F | `:seon.issue/functions` + `:seon.issue/tests` | `seon.test/verified?` on the reach digest | `seon.test/test` pair (exists, `src/seon/render/test.clj:41`) |
| respond to a fault | `:seon.issue/errors` | occurrence count stops rising / test green | `seon.error/error` pair (exists) + `seon.error.occurrence/occurrence` — **missing** |
| converse with the user | — (§4: not an issue) | `unanswered-wakes` `:t` | a thread pair — **missing** |

**Reuse-before-add argument:** `issue` already owns identity, subject refs,
budget, agent assignment, the listened first wake
(`seon.issue.edn:29-32`), settlement and `resolved-tx`. `my.task`
(`task-prototype-2026-09-16.md`) was already superseded into it by the owner's
"one family" ruling. `my.plan` owns ordering and the per-step done-query. Two
lists exist; a third would be the defect. The only genuinely new thing the
owner's model needs is that a template's units render — a change in
`seon.render.walk`, not a new family.

### (b) Render-pair gaps to close first, ranked by openings affected

Ranked by how many agent openings each would change, given §2.3's fix landing
first (without it, ranks 1–4 affect zero openings):

| # | gap | why it ranks here |
|---|---|---|
| 1 | **`seon.issue/render-ai` must return `:seon.render/source`** (`src/seon/issue.clj:733`) | affects **every worker opening**; it is the one unit outside the owner's model |
| 2 | **`declared-acquisition` must order a distance-1 member's own units** (`src/seon/render/walk.clj:667`) | affects every worker opening; unlocks the already-verified `seon.fn` and `seon.test` pairs |
| 3 | `seon.schema/schema` pair (unpaired, `resources/seon/schemas/seon.schema.edn`) | the "improve a schema" template has no way to show its subject; also the detector's own subject type |
| 4 | `seon.error.occurrence/occurrence` + `seon.error.evidence/entity` + `seon.error.location/entity` (all unpaired) | the "respond to a fault" template; `seon.error/error`'s pair already renders, its evidence does not |
| 5 | `seon.test.failure/failure` and `seon.test.report/report` (unpaired) | a red test's *why* prints as a raw map inside an otherwise paired test block |
| 6 | `seon.fn.file/file`, `seon.issue.citation/citation`, `seon.lint/finding` (unpaired) | the citation/span/lint links every issue note carries |
| 7 | the message-thread derived pair (§4.2) | affects every conversational agent, root included |
| 8 | the 59 `:seon.error/base`-merged error entities | probe first (§1.4): they may already select `seon.error/error`'s pair, or may be an ambiguity refusal |
| 9 | `seon.eval/entity` missing `:seon.db/attributes true` (`seon.eval.edn:6`) | not a render gap but a detector blind spot — fix so the census is honest |
| 10 | the 16 `seon.maintenance.result/*` maps and `seon.schedule/*` | operator-facing; no agent opening today |

### (c) How an agent authors and installs its own render function

No new mechanism; three thin additions, each reusing an existing owner.

1. **Teach the seam that already works.** One `(help)` line and one `dir`
   entry naming it: *"A public function in your namespace whose contract
   returns `:seon.render/source` becomes the renderer for values it accepts;
   `(seon.render/selection {…})` explains the choice."* `seon.render/selection`
   (`src/seon/render.clj:566`) and `selection-inspection` (`:594`) are already
   the explanation — they need an agent-facing name, not new code.
2. **`my.render/preview`** — render one value through a candidate producer and
   return the bytes plus the selection stages, without installing. Pure read
   over `seon.render/render-call` + `selection-inspection`.
3. **`my.render/pair!`** — declare `:seon.render/ai`/`:seon.render/html` on a
   schema key, a thin write over `seon.schema/register!`. It is admitted
   exactly like a definition: `render-contract-refusal!`
   (`src/seon/schema.clj:1756`) refuses a pair whose function's contract is
   not coherent with the schema, and the registration carries
   `{:seon.schema.admission/source :agent}` (`src/seon/schema/edn.clj:606`).
   Provenance is already derivable through `seon.program/overrides`
   (`src/seon/program.cljc:20`); store no flag.

For the HTML half the same function gate applies with `:seon.render/hiccup`;
`seon.render/raw-output` (`src/seon/render.clj:1215-1252`) already refuses a
producer that returns the wrong shape, and `AGENTS.md` §2.4's "authored render
functions run through the bounded SCI invocation owner and return values"
means no transport change is needed.

### (d) Naming

The vocabulary law (AGENTS.md §3): Clojure's own name, else the closest
integration seam's name, else coin once. Neither concept has a name in
Clojure, Datahike, Malli, SCI or core.async, so both fall to rule 3 — which
makes the cost argument decisive.

**Measured rename cost (this tree, HEAD `b9c4cfa58`):**

| area | occurrences of `steward` | files |
|---|---:|---:|
| `resources/` | 19 | 6 |
| `src/` | 86 | ~10 |
| `test/` | 72 | — |
| `docs/` | 3,471 | — |
| **total** | **3,649** | **501** |

The load-bearing identifiers are few: attributes `:seon.ns/steward`
(`resources/seon/schemas/seon.ns.edn:27-37`, 33 occurrences),
`:seon.error/steward` and `:seon.error/of-steward`
(`seon.error.edn:147-221`), `:my.agent/steward` (`my.agent.edn:3`); functions
`seon.cluster.agent/steward-of`, `seon.cluster.agent/steward-call`,
`seon.error/steward` (`src/seon/error.clj:1475`),
`seon.problems/unstewarded-namespaces` (`src/seon/problems.clj:262`). Under
the "database data is disposable by ruling" rule an attribute rename is a
reset, not a migration, so the real cost is the ~177 `src`+`test`+`resources`
occurrences plus a docs pass; call it one lane-day, and the `steward-platform`
PRD directory name is the largest single docs cost (it can stay as a dated
folder name).

**Three names for "the one agent a namespace answers for":**

| candidate | grounding | argument | cost |
|---|---|---|---|
| **`:seon.ns/maintainer`** (recommended) | the dependency ecosystem's own word — `deps.edn`, Git, clj-kondo all say maintainer; nothing in Clojure/Datahike names this | says "takes care of" without implying exclusivity or working-in; reads correctly next to `:seon.agent/namespace` (assignment), which is the distinction the owner is protecting | full rename |
| **`:seon.ns/agent`** | the system's own two nouns, no third | symmetrical with `:seon.agent/namespace`; zero new vocabulary, which rule 3 prefers | full rename, **and** it collides in prose with assignment — the very confusion being fixed |
| **`:seon.ns/routes-to`** | names the mechanism as the code states it: `error → :seon.fn/ns → :seon.ns/steward → message` (`src/seon/error.clj:1475-1484`, `:2008`) | cannot be confused with assignment, because it describes what actually happens; makes `seon.problems/unstewarded-namespaces` read as "namespaces nothing routes to" | full rename; a relation-shaped attribute name is unlike every other name in the population |

**Three names for the context-template concept (today `seon.issue`):**

| candidate | grounding | argument | cost |
|---|---|---|---|
| **`seon.finding`** (recommended) | the word already in the schema population: `seon.lint/finding` (`resources/seon/schemas/seon.lint.edn`), `seon.fn.contract.finding` — reuse before add | a detector produces findings; a human authors one; "open finding" and "resolved finding" read naturally; the detector docstring already says "the subjects that fail one standard" (`src/seon/issue/detect.clj:1-10`) | collides with two existing families that would want folding into it — which is arguably the point, but is a second lane |
| **`seon.standard`** | `seon.issue/detect`'s own prose and the owner's ruling "checklists are detectors" (`issue-family-spec-2026-09-16.md` §8) | the entity records *a standard this subject does not meet*; makes the detector-first design self-describing | the entity is the unmet instance, not the standard, so the name is one step off the thing |
| **`seon.work`** (`:seon.work/item`) | plain, and matches `next-agent-work` / `:seon.turn.work/situation` already in `src/seon/turn.clj:2753` | shortest, no collision, and the turn loop already speaks "work" | genuinely a coinage; loses the "something is wrong here" meaning that makes a detector's output honest |

The owner's objection to "issue" is that it names a defect report while the
concept is a context template for any work. Note that `seon.finding` keeps the
defect reading; if the owner wants the template concept to cover ordinary
feature work and conversation setup too, `seon.work` is the only one of the
three that does not smuggle a defect in. That is a decision, not a
recommendation this lane should make.

---

## 6. Boundary

Read-only. No file outside this note was modified; no JVM, gate, operator or
lane was started. Every number is a static measurement of the working tree at
`b9c4cfa58` with other lanes' uncommitted edits present. Three things stated
as open rather than answered: whether `:seon.error/base`-merged entity maps
select `seon.error/error`'s pair or refuse as ambiguous (§1.4); which
evaluation carried the issue bytes in the trial-1 capture (§2.4); and whether
an `:agent`-sourced re-registration of a core schema key is admitted in
practice (§3.2 found the gate but no prohibition). Each needs one live probe.

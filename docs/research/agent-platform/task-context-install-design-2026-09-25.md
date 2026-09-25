---
type: design
status: "Opus first pass, awaiting Astra review; no source edited"
created: 2026-09-25
scope: One task-context function generates every agent's context and bounds the reads re-checked before a turn; every namespace or neighbourhood dump on the agent path and every context builder outside it retires
source-pin: 2d23029cb (src ranges valid there); AGENTS.md at ba41af0c0 ("The Seon agent is the perspective")
aligned-with: language-requirements-2026-09-24.md §§1.9, 5, 5a, 7.10, 11; query-render-building-block-2026-09-25.md §§3b, 3d, 3h, 4; one-render-path-design-2026-09-25.md §§1-3, 5; agent-context-render-pipeline-2026-09-24.md §§1, 4
---

# Task context: one function, one view, one refresh set

**What exists.** An agent's opening is `turn/system-turn` → `declared-sources` → `walk/neighborhood` from
`[:seon.agent/id a]`. The walk follows the agent schema's eight concerns, including `:seon.agent/namespace`, whose
AI face is the namespace page. It also follows the identity renderer's issue openings (`seon.issue.opening`, seven
dialled candidates). The refresh before a turn, `system-plan`, re-checks the declared sources and **every retained
read-only evaluation in the agent's whole history**. To choose them, `latest-evaluations` pulls every evaluation with
all of its read evidence.

**Data flow after this design.** The flow runs in four steps.

1. The task, its subjects, proofs and must-reads are one held database value.
2. `seon.issue/task-view` turns that value into a plain **view** value: seeds, hop-1 content rows, hop-2 handles,
   and the set of view members.
3. The opening is an ordinary evaluation, `(my.task/context)`. The REPL evaluates it once and records it. It is
   printed through the view schema's pair by `seon.render/units`: AI for the prompt, HTML for the owner's debug page.
4. The refresh re-checks that evaluation, plus only those retained agent reads whose subjects are members of the
   view.

**Principal Clojure approach.** A small function of one database value returns data. Selection is a set
intersection. Rendering is the existing selection ladder and floor. No registry, cache, walk or renderer is added.

## 0. Broken things first

1. **The prototype is gone.** `scratch.task-context` was never committed: `git log -S scratch.task-context --all`
   finds only the requirement text (b1af1d100) and an MCP landing note (3dbf90999). No session transcript defines it.
   The default JVM (pid 37460, started 2026-09-25T19:31:55Z) has no such namespace:
   `(find-ns 'scratch.task-context)` answered `nil`. This design re-derives it from requirements §5a and re-measures
   on real tasks (§2). The 7.7 KB / 0.3 ms figure in §5a.4 is not reproducible evidence.
2. **`:seon.help/about` is not installed.** `(seon.db/q '[:find ?s :where [_ :seon.help/about ?s]] db)` was
   refused: "seon.db/q cannot read uninstalled attribute :seon.help/about". The taught-vocabulary rule of §5a.4 has
   no data today. §3.3 derives it from installed data instead.
3. **Choosing refresh candidates costs about 3.3 s per system turn for root, in proportion to the whole history's
   evidence.** On default, `(#'seon.turn/latest-evaluations db "root")` took 3,254 ms and then 3,369 ms. It read 45
   distinct forms over 108 evaluations and pulled **18,673** read-evidence rows (`turn.clj:1925-1957`: `[* …
   {:seon.cluster.eval/read-evidence [*]}]`). The same query without the evidence pull took **3.1 ms**. Default was
   under a concurrent test load, so absolute times are inflated; the ratio is the finding. Only 2 of the 45 passed
   `read-only-evaluation?`, with 8 reads between them. Almost all of the 3.3 s buys nothing.
4. **Two effect calls pass the read-only test.** `(my.turn/complete …)` and `(my.agent/done)` are the only
   evaluations `read-only-evaluation?` (`turn.clj:1959-1975`) admits for root. They leave no effect or receipt row,
   and their evidence names no turn attribute, so `generated-read-fault` answered nil for both. Today's refresh can
   therefore re-evaluate a completion call as a "generated read". Under §4 they are outside every task view, so
   they are never re-run. The producer defect stays: effect calls should record their effect. See the
   out-of-scope findings (§9).
5. **A task must-read names no definition.** Task `132fee0467c8` must-reads `seon.sci.eval/definition-rows`. There is
   no function row for it (`[0 0 0]` characters of doc, spec and source), and `rg definition-rows src` finds
   nothing. The task writer admitted a dangling link, although requirement 4.1 says "Links resolve". See §9.
6. **Pre-fix evidence is still stored.** The read-sink fix `ca17618c0` was committed at 19:35:09Z, after default
   started (19:31:55Z). The stored `(quote 1)` still carries 7,286 reads, and `;; Trying a vector.\n[]` carries 7,282.
   The counts in §2.3 include that noise. The view rule in §4 excludes it by subject either way.
7. **`seon.sci.eval/evaluate` is 29,253 characters of source.** It is task `132fee0467c8`'s subject, and requirement
   5.8 shows a subject whole. That one function is 61% of that task's hop-1 content (§2.2). The context is honest;
   the function is oversized (AGENTS "Address oversized functions … with shrink assignments").

## 1. Current owners (file:line at 2d23029cb)

### 1a. Opening and prompt context today

| Step | Owner | Lines | What it does |
|---|---|---:|---|
| Opening entry | `seon.turn/system-turn` `turn.clj:2077-2252` | 176 | agent namespace, `declared-sources`, `latest-evaluations`, `system-plan`, evaluate or preview each selected read, diff, append |
| Declared sources | `seon.turn/declared-sources` `turn.clj:1870-1923` | 54 | calls `walk/neighborhood` from `[:seon.agent/id a]`, collects each unit's source blocks, re-reads them with `planned-sources` (B3 print-then-read) |
| Acquisition | `seon.render.walk/neighborhood` `walk.clj:710-807` | 98 | follows `:seon.render/units` of the agent schema (`seon.agent.edn`, `:agent`): plan, `:seon.issue/_agent`, `:seon.message/_to`, settings, `:my.note/_agent`, **`:seon.agent/namespace`**, runtime, `:seon.error/of-steward` |
| Identity concern | `seon.cluster.agent/render-identity-ai` `agent.clj:278-304` (+ `identity-form` `:246-252`, `render-id-ai` `:306-310`) | 39 | `(help)`, the identity pull, then one `seon.issue.opening/source` block per issue the agent works |
| Issue openings | `seon.issue.opening` (whole file) | 233 | seven dialled candidates (`:bare` … `:minimal-retrieval`), plus `context` |
| Namespace concern | `seon.render.ns/render-ai` `ns.clj:750-781` via `ai-text`/`full-ai-text`/`compact-ai-*` (`:469-550`) | ~135 | the namespace page as AI text, reached through `:seon.agent/namespace` |
| Bootstrap opening candidates | `seon.bootstrap` `:204-731` (`entries` … `next-entry`) | 502 (definition lines) | direct/listing/usage-demonstration/subject candidates and `next-entry`. **No production caller**: the program graph lists only `seon.bootstrap-test/…` for `next-entry`, and `listing-candidates` is its only walk caller |
| Prompt assembly | `seon.cluster.prompt/prompt` `prompt.clj:421` over `walk/history` `walk.clj:926-974` | — | renders saved evaluations; whole-unit selection (`select` `:301`). **Kept**: history is the transcript, not context generation |

### 1b. Refresh today

- `system-plan` (`turn.clj:1977-2017`) concatenates the declared sources with every `(vals latest)` entry that passes
  `read-only-evaluation?` and has no `generated-read-fault` (`:2024-2075`). It deduplicates by `source-key` and gives
  each entry `:unchanged`/`:changed`/`:none` through `db/read-evidence-current?` (`db.clj:1139`). For `:changed` it
  adds `db/read-evidence-changes` (`db.clj:1041`).
- The candidate set is the agent's **whole history**, not the namespace: `latest-evaluations` joins agent → runtime →
  turns → evaluations.

### 1c. Task data today

| Datum | Where | Installed? |
|---|---|---|
| Task entity `:seon.task/task` (id, ns, name, kind, intent, must-read, evidence) | `resources/seon/schemas/seon.task.edn`; EDN in `resources/seon/tasks/{seon.fn,seon.schema,seon.sci.eval}.edn` | yes: three tasks in default |
| Subjects and proofs | `:seon.task/tasks` string values on fn/ns/test rows (`fn.clj:618-630`); `seon.issue/task-edges` `issue.clj:716-732` | yes |
| Openness | `seon.issue/open-tasks` `issue.clj:734-740` | yes |
| Findings | `seon.issue/task-findings` `:742-752` (dangling, triage) | yes |
| Must-read / see-also edges on definitions | `:seon.fn/must-read`, `:seon.fn/see-also` (`seon.task.edn` `:links`) | yes (requirement 5a.6 is now met) |
| Agent → task | none | **no attribute** |
| Issue → worker | `:seon.issue/agent`; `seon.issue.opening/links` | yes; legacy family, migrating per 7.11 |
| Program edges | `:seon.fn/calls`, `:seon.fn/references` (indexed symbols), `:seon.fn/keywords`, `:seon.fn/writes` | yes |

## 2. REPL proof on real data (default, JVM mode, read-only, one form each)

### 2.1 The three real tasks

`(seon.issue/task-edges db h)` for each handle:

| Task | Subjects | Proofs | Must-read |
|---|---|---|---|
| `132fee0467c8` definitions-bypass-entry-gate | `seon.sci.eval/evaluate`, ns `seon.sci.restoration-test` | 3 deftests in `seon.sci.entry-gate-test` | `evaluate`, `definition-rows` (dangling) |
| `300aca1ad57c` tests-reaching-saturates | `seon.fn/tests-reaching`, `seon.fn/gate-set` | none (raised) | `reverse-closure`, `gate-sets` |
| `cea47b015b23` agents-can-validate | ns `seon.schema` | none (raised) | three `seon.schema` functions |

### 2.2 Seeds and hop sizes (the prototype rule, re-derived)

- **Seeds** are: subjects; must-reads; each proof's `:seon.fn/calls` that have a first-party function row; and each
  proof's `:seon.fn/keywords` that are registered schema keys.
- **Excluded** are functions in namespaces the agent's namespace requires. That is root's `:seon.ns/requires`:
  `[my.turn seon.bootstrap seon.db clojure.test my.message]`, the help "Tools:" set.
- **Hop 1** is doc + contract + source of each function seed, the projection form of each key seed, and each proof's
  source.
- **Hop 2** is the name sets of callers, reaching tests and first-party callees of each function seed.

All three tasks were computed in **27 ms**, with naive per-seed pulls.

| Task | Fn seeds | Key seeds | Hop 1 chars | Hop 2 as full name sets | Hop 2 counts (callers / tests / callees) |
|---|---|---:|---:|---:|---|
| 132fee | `evaluate`, `entry-gate-test/evaluate-in`, `test-support/fork-cluster-ctx`, `test-support/with-database` | 15 | 48,145 | 99,831 | 85 / **929** / 64 |
| 300aca | `tests-reaching`, `gate-set`, `gate-sets`, `reverse-closure` | 0 | 3,546 | 2,018 | 15 / 15 / 4 |
| cea47 | 3 `seon.schema` functions (+ ns `seon.schema`) | 0 | 1,894 | 8,245 | 55 / 81 / 2 |

Per-seed hop-1 sizes (doc, spec, source) include:

- `evaluate` [1691 61 29253];
- `with-database` [795 400 1661];
- `fork-cluster-ctx` [232 148 1312];
- `evaluate-in` [0 0 420];
- `gate-sets` [154 251 902].

**What the numbers teach:**

- **Hop 2 must be handles, never name sets** (req 1.9; building block §3d). 929 reaching tests come from the two
  harness functions. Rendered as a count plus the expanding call (`;; tests 929 (my.program/tests-reaching
  'seon.test-support/with-database)`), hop 2 costs about 60 characters per seed and edge: about 0.7 KB for the
  largest task, independent of fan-in.
- **The test harness is taught vocabulary.** It is the canonical world builder (AGENTS "Test the real system"), not a
  subject. Excluding it takes 5.2 KB of hop 1 and all 929 tests off 132fee.
- **Estimated rendered context** (hop 1 + handles):
  - 132fee: about 44 KB, of which 29 KB is `evaluate`'s source (§0.7);
  - 300aca: about 4.3 KB;
  - cea47: about 2.4 KB.

  For comparison, root's stored prompt captures in this store are 5.7–96.5 KB (31 captures), and the
  2026-09-24 capture was 680 KB.

### 2.3 Read counts: today's refresh versus the view rule

Store: 108 evaluations carry read evidence, with **21,110** evidence rows. Root's `latest-evaluations` holds 45
forms and **18,673** evidence rows.

| Rule | Evaluations examined | Evidence rows pulled | Reads re-checked | Time |
|---|---:|---:|---:|---:|
| Today: `latest-evaluations` + `read-only-evaluation?` | 45 | 18,673 | 8 (2 evaluations: the two effect calls, §0.4) | 3,254–3,369 ms |
| View rule, subject = the evaluation's own **form constants**: agent → evaluations with source only, then constants ∩ view | 108 | 0 until selected | 0 for the 132fee view; 9 evaluations for a view holding `:example/amount`, `my.agents.root/largest`, `seon.fn/functions-using` | 3.1 ms query + 5.1 ms constants |
| View rule, subject = the **evidence's** pattern values, query arguments and pull eids | 45 | 18,673 | 60 reads in 2 evaluations for 132fee (all platform reads that named `evaluate`, pre-sink-fix noise); 0 for 300aca | 40.9 ms classify, after the 3.3 s pull |

The form-constant rule selects by what the **agent** asked about. It never reads evidence to decide, and it ignores
platform reads that leaked into an old sink. Of 18,673 rows, 682 had no attributable subject.

## 3. Design: the smallest composition

### 3.1 `seon.issue/task-view` (acquisition, in the task owner)

```clojure
(seon.issue/task-view database handle viewer-namespace)
;=> #:seon.task.view{:task {…task row…} :subjects #{…} :proofs [{:seon.test/sym … :seon.test/source …}]
;                    :content [fn-row | key-form | missing-symbol …]   ; hop 1, in seed order
;                    :handles [{:seon.task.view/seed f :seon.task.view/edge :callers :seon.task.view/count 85}]
;                    :members #{symbols, keys, handle, [attr value] lookups}}   ; the view, used by §4
;  | :seon.db/invalid-read-error | :seon.task/no-task-error
```

- **Seeds.** Seeds are `task-edges` subjects ∪ task must-read ∪ each proof's first-party `:seon.fn/calls` ∪ each
  proof's registered `:seon.fn/keywords` ∪ `:seon.fn/must-read` of the subjects. Two groups are removed:
  - functions in the viewer namespace's `:seon.ns/requires`;
  - functions named by `seon.bootstrap/help-value`'s `:seon.fn/see-also` edges.

  This is taught vocabulary from installed data (§3.3).
- **Hop 1 is content.** Function rows are pulled with the function-face selector. Key seeds carry their projection
  form. A seed with no row and no key is a `missing` entry: "you create this" when its namespace is a subject's,
  otherwise "unresolved".
- **Hop 2 is counts only.** Callers, reaching tests, first-party callees and open tasks come from the indexed
  `:seon.fn/calls` and `:seon.task/tasks`. They are counted with `[:find (count ?c) …]`, so hop 2 never realizes a
  name set.
- **Members** are every seed, subject, proof and must-read symbol, the task handle, and each seed's
  `[:seon.fn/sym f]` / `[:seon.test/sym t]` / `[:seon.ns/name n]` lookup. It is a derived value, never stored.
- **Cost.** O(seeds × (1 pull + 3 indexed counts)). Seeds are bounded by what the author wrote (proofs, must-reads),
  never by namespace size. Measured shape: 27 ms for three tasks with naive code; the target is under 10 ms warm per
  task. A seed count beyond `:seon.config.eval.result/max-nodes` returns the view with a named cut, as a building-block
  continuation. No new dial.

### 3.2 Render: the one pipeline, two faces

- `:seon.task.view/view` declares its pair in `seon.task.edn`:
  - AI: `seon.issue/render-task-view-ai`;
  - HTML: `seon.issue/render-task-view-html`.
- Each face returns a declared `:seon.render/composition` (building block §3d):
  - the task row as its value;
  - each `:content` function row as a child **value**, selected by the ladder, which picks the function face
    (`seon.render.ns/function-ai`/`-html`, `ns.clj:699-742`);
  - each handle as a child handle line.
- One floor budget B covers the whole view (building block §3d; one-render-path §3d). The subject's source renders
  whole through the function face. Everything else is clipped only by the floor.
- **The owner's debug page renders the same recorded evaluation** through its HTML face (AGENTS "The Seon agent is
  the perspective"). There is no page-side acquisition.
- **Before `units` lands (building block S2)**, the pair runs through today's `render-call` schema stage. There is no
  interim renderer. The composition branch of `raw-output` is the building block's +8 lines, not this design's.

### 3.3 Taught vocabulary without a new attribute

- The help "Tools:" line is already derived from `:seon.ns/requires` (`bootstrap.clj:40-48`).
- The functions the help prose teaches by name are `seon.db/q`, `pull`, `transact!` and `seon.test-support`'s world
  builders. They become one `:seon/see-also [...]` entry on `help-value`'s attribute map, which the analyzer already
  ingests as `:seon.fn/see-also` (`fn.clj:621-630`).
- The rule is: `taught = publics of requires ∪ help-value's see-also`. That costs +1 metadata line and no attribute.
- The help-as-data topics of the render-pipeline design §4 would later replace this with `:seon.help/about`. That is a
  separate cut.

### 3.4 The opening is one ordinary evaluation

`declared-sources` becomes a function of the agent's task, not a walk:

```clojure
[{:seon.cluster.eval/source "(help)"}
 {:seon.cluster.eval/source "(my.task/context)"}]   ; or the no-task value (§5)
```

- `my.task/context` is a thin `my.*` adapter, about 6 lines. It supplies database, agent and viewer namespace as
  contract defaults (req 1.13) and calls `task-view`.
- The REPL evaluates it once, records it and binds `result/e…`. Since `ca17618c0`, the evaluation's read evidence is
  exactly the reads `task-view` made. So the task-context evaluation's own currency check **is** "has the view
  changed".
- There is no print-then-read-back step (B3 disappears for the opening) and no pre-execution (building block §3b,
  evaluation lifecycle).
- `planned-sources` stays: it has eight other callers.
- **The situation line stays as the agent's fixed facts:** unread count, turns left and trigger. It is
  `render-situation-ai`, `agent.clj:396-430`, over `bootstrap/situation`. Its facts are the agent's own, not a
  namespace.

## 4. Refresh draws from the view

`system-plan` keeps its shape. Only its candidate source changes:

1. **Candidates.** Candidates are the declared forms (§3.4) ∪ retained evaluations whose **form constants** intersect
   the current view's `:members`. The constants are the symbols, keywords, strings and lookup vectors reachable in the
   evaluation's reader forms.
   - This needs a first query without the evidence pull. That query is today's `latest-evaluations` join with
     `[:db/id :seon.cluster.eval/source :seon.cluster.eval/ordinal {:seon.cluster.eval/ns [:seon.ns/name]}]`,
     measured at 3.1 ms.
   - `read-only-evaluation?` then runs only on the intersecting ones.
   - Evidence is pulled only for the survivors.
2. **Currency.** For each candidate, `read-evidence-current?` and `read-evidence-changes` run unchanged. Currency
   stays with the database owner.
3. **Reads outside the view** follow four rules:
   - they are **not** re-checked, re-evaluated or evidence-pulled;
   - their saved shown text stays in history exactly as shown, as a historical observation of that turn (AGENTS
     "Recover facts, not execution"; one-render-path §5 whole units);
   - no staleness claim is made about them either way;
   - the agent re-runs one on demand like any form.

   When the task changes, the view changes, and the next turn's candidate set follows it automatically.
4. **The view is the recorded one.** The members come from the current turn's `(my.task/context)` value, which is the
   newest evaluation. It is not recomputed separately, so opening and refresh cannot disagree.

**Cost.**

| Step | Cost |
|---|---|
| Candidate selection | O(retained evaluations × form size), about 8 ms for 108 evaluations measured, instead of O(retained evidence rows), 3.3 s |
| Currency | O(in-view evidence), 0–60 rows in the probes |
| Target | the whole plan under 50 ms for root's history |

Parent-versus-change timing is required at landing (AGENTS "Hot-path commits").

## 5. Agents without a task: small and loud

- The agent → task link is `:seon.agent/task`, one handle value, never a ref (tasks never ride program merge, req
  7.13). It costs 2 declaration lines. §8 Q1 prices the alternatives.
- **Without it**, `(my.task/context)` returns `:seon.task/no-task-error`, a named schema over `:seon.error/base`:
  - `:seon.error/member :seon.agent/task`;
  - `:seon.error/expected :seon.task/handle`;
  - the message "No task: this agent's context is generated from its task. Ask root, or `(my.task/assign!
    "<handle>")`";
  - `:seon.task/open` holding the count of open task handles plus the call that lists them.
- The opening is then `(help)`, the situation line and that error. That is under 1 KB, printed as any error value
  (req 12.2).
- There is **no namespace, neighbourhood or history-dump fallback**. The refresh candidate set is the declared forms
  only, because the view is empty.
- Root today has no task, so root falls under this rule unless §8 Q3 option B is taken.

## 6. Every context builder, and what happens to it

AGENTS "The Seon agent is the perspective": the task context is the one context, and MCP, tests and web render it.

### 6a. On the agent path (retired by this design)

| Path | Range | Lines | Fate |
|---|---|---:|---|
| `seon.bootstrap` opening candidates `entries` … `next-entry` | `bootstrap.clj:204-731` | 502 | **delete** (T0); production-unreachable. Its two `bootstrap-test` cases go with it |
| `declared-sources` walk | `turn.clj:1870-1923` | 54 | rewritten as the §3.4 task forms (~14) |
| identity AI concern with issue openings: `render-identity-ai`, `render-id-ai`, `identity-form` | `agent.clj:246-252, 278-310` | 39 | delete; identity is `(my.agent/whoami)` on demand. `render-identity-html` stays for the page |
| `seon.issue.opening` (seven candidates, dial, `context`) | whole file | 233 | delete (T3), conditional on issue → task migration (7.11); an issue worker's task becomes its `:seon.agent/task` |
| `latest-evaluations` evidence pull | `turn.clj:1925-1957` | 33 | rewritten as §4.1 (~24) |
| `system-plan` history concat | `turn.clj:1977-2017` | 41 | rewritten to the view candidates (~36) |
| namespace AI face as opening concern (`:seon.agent/namespace` via walk) | `ns.clj:469-550, 750-781` | ~135 | **no longer reached from the opening**. Kept for an explicit request and the namespace page; not credited |

### 6b. Outside the agent path (views that build their own context)

| Builder | Where | Lines | Fate |
|---|---|---:|---|
| Transcript session/outline/ledger projection | `seon.render.transcript` | 2,486 file | its context and prompt views read the recorded evaluations, including the task-context one, and the capture. Acquisition adapters are booked as building block **Q6** (204) |
| Debug page context retention | `web.clj:2537-2610` `derive-context!` | 74 | building block **Q5**; the page shows the recorded task-context evaluation's HTML face |
| Agent-facing ambient walk | `render.clj:1812-1976` `seon.render/walk` + helpers | 159 | building block **Q2** |
| Eval driver transcript | `eval/drive.clj:288-300` `full-transcript` | 13 | read the turn's `:seon.context.capture/prompt` (exact bytes), never re-derive |
| MCP | `script/seon/dev/mcp.clj` | — | builds no context today, and outside agents therefore get **none**. Under the law, a `branch` created for a task sets `:seon.agent/task` and its first evaluation is `(my.task/context)` on the same path. Open, §8 Q5 |
| Test harness | `test/seon/test_support.clj` | — | no context builder found (`render-context-channel` only injects a profile). Tests call `system-turn`/`prompt` (the owners); keep |

### 6c. Ledger

Credit counts whole old definitions removed, or rewritten sizes. Lines booked elsewhere are named, not recounted.

| Slice | Change | Source + | Source − | Declarations |
|---|---|---:|---:|---:|
| T0 | delete bootstrap's production-unreachable opening candidates | 0 | 502 | −8 est. (the root-acquisition, unmatched-source and prefix-drift error keys those functions raise, `seon.bootstrap.edn:2-8`, if no other raiser remains) |
| T1 | `task-view` (~45), `my.task/context` (~6), task forms in `declared-sources` (~14), no-task branch (~4), help-value see-also (1); delete identity AI concern and `declared-sources` walk | 70 | 93 | +14 (`:seon.agent/task` 2, view schema + pair 6, no-task error 6) |
| T2 | view-bounded refresh: §4.1 selection in `latest-evaluations`/`system-plan` (~60 rewritten), `in-view?` (~8) | 68 | 74 | 0 |
| T3 | delete `seon.issue.opening`; issue workers get `:seon.agent/task` (conditional on 7.11) | 5 | 233 | −1 (`:seon.config.render/issue-opening` dial) |
| **Total** | | **143** | **902** | **+5 net** |

Every slice stays under 100 added lines. Net source is **−759**, before tests: one class regression per slice, about
+120 lines, with the deleted bootstrap and issue-opening tests credited when they land. Without T3, net is −531.

## 7. Simplest alternative

**Keep the walk and drop `:seon.agent/namespace`, then add a `:seon.agent/task` concern whose AI face is the view.**

- It is smaller (about +40 lines).
- It keeps a second context producer: the walk over plan, notes, messages and faults. That is exactly the
  neighbourhood dump the owner ruled out.
- It keeps the print-then-read-back step.
- It does not bound the refresh, which still concatenates the whole history.

**Rejected.** Its one virtue, reusing walk acquisition, is available anyway: `task-view` is plain data rendered by
`units`.

## 8. Open questions for the owner (priced options; simplest viable first)

1. **How an agent names its task.**
   - **A (recommended):** a `:seon.agent/task` handle value on the agent, set by root/`create!`/MCP `branch`
     (2 declaration lines, about 1 hour; one task per agent at a time).
   - **B:** derive it from a stored attempt entity (3b.4); about 1–2 days, blocked on attempts being stored; the
     honest many-attempts model.
   - **C:** reuse `:seon.issue/agent`; zero lines now; blocked on 7.11 and keeps the legacy family alive.
2. **What a read's subject is, for refresh.**
   - **A (recommended):** the evaluation's form constants (8 ms for 108 evaluations; misses a read through a
     `result/e…` handle or a computed value, which is then simply not refreshed).
   - **B:** the evidence's pattern values, indexed as a new `:seon.db/read-subjects` many-attribute written at capture;
     about 0.5 day plus capture cost; accurate at run time.
   - **C:** both, a union; the costs of A plus B.
3. **Root and other taskless agents.**
   - **A:** §5 as written; root's opening is help + situation + no-task error; about 0 extra lines; root must ask
     for work.
   - **B (recommended):** A, plus a standing root task in `resources/seon/tasks/seon.cluster.edn` whose must-reads
     are the status, faults and agents verbs; about 5 EDN lines, hours; root's context is then also task-derived.
   - **C:** refuse to open a turn without a task; about 5 lines; strictest, but it stops root.
4. **Taught vocabulary.**
   - **A (recommended):** requires ∪ `help-value` `:seon/see-also`; 1 line, now.
   - **B:** install help topics with `:seon.help/about` (render-pipeline §4, about 60 lines + 40 EDN, about 1 day).
   - **C:** no exclusion; 0 lines; hop 1 carries harness sources (+5.2 KB on 132fee).
5. **Messages and outside agents.**
   - **A (recommended):** the trigger message is part of the situation line; other messages, notes and the plan are
     on demand; MCP `branch` takes a task handle; about 10 lines.
   - **B:** add messages whose subject is in the view as view content; about 20 lines, plus a message-subject edge
     that does not exist.
   - **C:** keep the messages concern beside the task; 0 lines, but it is a second context producer.

## 9. Unresolved risks and out-of-scope findings

This lane writes one file, so these are recorded here. Root files them. Existing issues were not searched for
§§9.2–9.4.

1. **Not implemented; nothing installed.** Sizes are estimates. The probe code was an inline `let`: no definitions,
   no writes. Timings are single samples under concurrent test load on default.
2. **Effect calls pass `read-only-evaluation?`** (§0.4). The producer is `my.turn/complete` and `my.agent/done`,
   which record no effect row. Suggested issue: `docs/seon/issues/effect-calls-pass-the-read-only-refresh-test.md`.
3. **The task writer admits a dangling must-read** (§0.5, `seon.sci.eval/definition-rows`). The owner is
   `seon.issue/file-declarations`, against requirement 4.1. Suggested issue:
   `docs/seon/issues/task-must-read-links-are-not-resolved.md`.
4. **`latest-evaluations` pulls all history evidence** (§0.3). T2 fixes it. If T2 waits, it is a >1 s defect on
   every system turn. Suggested issue: `docs/seon/issues/system-plan-pulls-every-retained-read.md`.
5. **Bootstrap's opening candidates are production-dead** (502 lines, §6a T0). Before deleting, verify that no
   schema render property or `resolve` call names them: `rg` over `resources/` found only its error keys.
6. **Requirement §5a.4's 7.7 KB figure is unreproducible**, because the prototype is lost (§0.1). The spec's row
   should cite this design's §2.2 measurements instead; that is a correction for its owner.
7. **This design depends on `ca17618c0`** (the form-scoped sink) being loaded. Default predates it (§0.6).
   **RESET NEEDED: no.** Root adopts it with the next restart.

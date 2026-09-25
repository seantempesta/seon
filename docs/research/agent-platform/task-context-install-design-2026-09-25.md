---
type: design
status: "Opus revision 2 (owner's question 2026-09-25 answered in §3); awaiting Astra review; no source edited"
created: 2026-09-25
scope: The agent's task generates its whole context, rendered by one declared pair out to N hops; the reads re-checked before a turn are bounded by that context; every namespace or neighbourhood dump on the agent path and every context builder outside it retires
source-pin: 2d23029cb (src ranges valid there); AGENTS.md at ba41af0c0 ("The Seon agent is the perspective")
aligned-with: language-requirements-2026-09-24.md §§1.9, 1.13, 5, 5a, 7.2, 7.7, 7.10, 11, 12; query-render-building-block-2026-09-25.md §§0.3, 3b, 3d, 3h, 4; one-render-path-design-2026-09-25.md §§1-3, 5; agent-context-render-pipeline-2026-09-24.md §§1, 4
---

# Task context: the agent's task, one render, one refresh set

**What exists.** An agent's opening runs `turn/system-turn` → `declared-sources` → `walk/neighborhood` from
`[:seon.agent/id a]`. The walk follows the agent schema's eight concerns. One of them is `:seon.agent/namespace`,
whose AI face is the namespace page. The agent schema's own AI renderer, `render-identity-ai`, is already "a render
function for the agent that creates its context": it returns the opening's source blocks, including one issue
opening per issue the agent works. The refresh before a turn, `system-plan`, re-checks the declared sources and
**every retained read-only evaluation in the agent's whole history**. To choose them, `latest-evaluations` pulls
every evaluation with all of its read evidence.

**The data flow after this design:**

1. The agent names its task: `:seon.agent/task`, a task handle value.
2. One function of one held database value, `seon.cluster.agent/context`, follows agent → task → the task's
   references out to N hops. Hop 1 is content; later hops are addresses. It returns a `:seon.agent/context` value.
3. That value's declared pair is the agent's context render. The AI face is the prompt; the HTML face is the owner's
   debug page. Both run through `seon.render/units`: one pipeline, two faces.
4. The opening is that one call, evaluated once through the ordinary REPL path.
5. The refresh re-checks the call itself, plus only those retained agent reads whose subjects are members of the
   context.

**The principal Clojure approach.** Small functions of one value return data. Hop limits are plain optional map
inputs with defaults. Selection is set intersection. Rendering uses the existing ladder and floor. No registry, cache,
walk, dial or renderer is added.

## 0. Broken things first

1. **The prototype is gone.** `scratch.task-context` was never committed. `git log -S scratch.task-context --all`
   finds only the requirement text (b1af1d100) and an MCP landing note (3dbf90999), and no session transcript
   defines it. The default JVM (pid 37460, started 2026-09-25T19:31:55Z) has no such namespace:
   `(find-ns 'scratch.task-context)` answered `nil`. This design re-derives the rule from requirements §5a and
   re-measures it on real tasks (§2). The 7.7 KB / 0.3 ms figure in §5a.4 is not reproducible evidence.
2. **`:seon.help/about` is not installed.** The query `[:find ?s :where [_ :seon.help/about ?s]]` was refused:
   "seon.db/q cannot read uninstalled attribute :seon.help/about". The taught-vocabulary rule of §5a.4 has no data
   today. §3.5 derives it from installed data instead.
3. **Choosing refresh candidates costs about 3.3 s per system turn for root, in proportion to all of root's history
   evidence.** On default, `(#'seon.turn/latest-evaluations db "root")` took 3,254 ms and then 3,369 ms. It read 45
   distinct forms over 108 evaluations and pulled **18,673** read-evidence rows (`turn.clj:1925-1957`: `[* …
   {:seon.cluster.eval/read-evidence [*]}]`). The same join without the evidence pull took **3.1 ms**. A concurrent
   test load inflated the absolute times; the ratio is the finding. Only 2 of the 45 passed `read-only-evaluation?`,
   with 8 reads between them.
4. **Two effect calls pass the read-only test.** `(my.turn/complete …)` and `(my.agent/done)` are the only
   evaluations `read-only-evaluation?` (`turn.clj:1959-1975`) admits for root. They leave no effect or receipt row,
   and `generated-read-fault` answered nil for both. Today's refresh can therefore re-run a completion call as a
   "generated read". Under §4 these calls are outside every context, so they are never re-run. The producer defect
   stays (§9).
5. **A task must-read names no definition.** Task `132fee0467c8` must-reads `seon.sci.eval/definition-rows`, which
   has no function row, and `rg definition-rows src` finds nothing. The task writer admitted a dangling link, although
   requirement 4.1 says links resolve (§9).
6. **Pre-fix evidence is still stored.** The read-sink fix `ca17618c0` was committed at 19:35:09Z, after default
   started. The stored `(quote 1)` still carries 7,286 reads. The counts in §2.4 include that noise.
7. **`seon.sci.eval/evaluate` is 29,253 characters of source.** It is the subject of `132fee0467c8`, and requirement
   5.8 shows a subject whole. The context is honest; the function is oversized.

## 1. Current owners (file:line at 2d23029cb)

### 1a. Opening and prompt context today

| Step | Owner | Lines | What it does |
|---|---|---:|---|
| Opening entry | `seon.turn/system-turn` `turn.clj:2077-2252` | 176 | agent namespace, `declared-sources`, `latest-evaluations`, `system-plan`, then evaluates or previews each selected read, diffs and appends |
| Declared sources | `seon.turn/declared-sources` `turn.clj:1870-1923` | 54 | calls `walk/neighborhood` from `[:seon.agent/id a]` and collects each unit's source blocks. It re-reads them with `planned-sources`: print, then read back |
| Acquisition | `seon.render.walk/neighborhood` `walk.clj:710-807` | 98 | follows the agent schema's `:seon.render/units` (`seon.agent.edn`): plan, `:seon.issue/_agent`, `:seon.message/_to`, settings, `:my.note/_agent`, **`:seon.agent/namespace`**, runtime and `:seon.error/of-steward` |
| The agent's AI pair | `seon.cluster.agent/render-identity-ai` `agent.clj:278-304`, plus `identity-form` `:246-252` and `render-id-ai` `:306-310` | 39 | `(help)`, the identity pull, then one `seon.issue.opening/source` block per issue |
| Issue openings | `seon.issue.opening` (whole file) | 233 | seven dialled candidates (`:bare` … `:minimal-retrieval`) and `context` |
| Namespace concern | `seon.render.ns/render-ai` `ns.clj:750-781`, via `ai-text`, `full-ai-text` and `compact-ai-*` (`:469-550`) | ~135 | the namespace page as AI text |
| Bootstrap opening candidates | `seon.bootstrap` `:204-731` (`entries` … `next-entry`) | 502 (definition lines) | **no production caller**. The program graph lists only `seon.bootstrap-test/…` callers of `next-entry` |
| Prompt assembly | `seon.cluster.prompt/prompt` `prompt.clj:421` over `walk/history` `walk.clj:926-974` | — | renders the saved transcript and selects whole units. **Kept** |

### 1b. Refresh today

- `system-plan` (`turn.clj:1977-2017`) concatenates the declared sources with every `(vals latest)` entry that passes
  `read-only-evaluation?` and has no `generated-read-fault` (`:2024-2075`).
- It classifies each one with `db/read-evidence-current?` (`db.clj:1139`), and each changed one with
  `db/read-evidence-changes` (`db.clj:1041`).
- The candidate set is the agent's whole history.

### 1c. Task data today

| Datum | Where | Installed? |
|---|---|---|
| Task entity (id, ns, name, kind, intent, must-read, evidence) | `seon.task.edn`; EDN in `resources/seon/tasks/{seon.fn,seon.schema,seon.sci.eval}.edn` | yes: three tasks |
| Subjects and proofs | `:seon.task/tasks` string values on fn/ns/test rows (`fn.clj:618-630`); `seon.issue/task-edges` `issue.clj:716-732` | yes |
| Openness and findings | `seon.issue/open-tasks` `:734`, `task-findings` `:742` | yes |
| Must-read and see-also on definitions | `:seon.fn/must-read`, `:seon.fn/see-also` | yes |
| Task lifecycle | `task-delta` `issue.clj:618-637`: a removed entry becomes `[:db/retractEntity …]` (`:634`); `adopt-tasks-call` reconciles published tasks by handle | yes |
| Agent → task | none | **no attribute** |
| Program edges | `:seon.fn/calls` and `:seon.fn/references` (indexed symbols), `:seon.fn/keywords`, `:seon.fn/writes` | yes |

## 2. REPL proof on real data (default, JVM mode, read-only, one form each)

### 2.1 The three real tasks

| Task | Subjects | Proofs | Must-read |
|---|---|---|---|
| `132fee0467c8` definitions-bypass-entry-gate | `seon.sci.eval/evaluate`, ns `seon.sci.restoration-test` | 3 deftests in `seon.sci.entry-gate-test` | `evaluate`, `definition-rows` (dangling) |
| `300aca1ad57c` tests-reaching-saturates | `seon.fn/tests-reaching`, `seon.fn/gate-set` | none (raised) | `reverse-closure`, `gate-sets` |
| `cea47b015b23` agents-can-validate | ns `seon.schema` | none (raised) | three `seon.schema` functions |

### 2.2 Hop 1: seeds and content

**Seed rule.** Seeds are:

- the task's subjects;
- its must-reads;
- each proof's first-party `:seon.fn/calls`;
- each proof's registered `:seon.fn/keywords`.

**Taught vocabulary is excluded:**

- functions in the viewer namespace's `:seon.ns/requires` (root: `[my.turn seon.bootstrap seon.db clojure.test
  my.message]`);
- `seon.test-support`.

Measured with the harness **not yet** excluded:

| Task | Fn seeds | Key seeds | Hop 1 chars |
|---|---|---:|---:|
| 132fee | `evaluate`, `entry-gate-test/evaluate-in`, `test-support/fork-cluster-ctx`, `test-support/with-database` | 15 | 48,145 |
| 300aca | `tests-reaching`, `gate-set`, `gate-sets`, `reverse-closure` | 0 | 3,546 |
| cea47 | 3 `seon.schema` functions (+ ns `seon.schema`) | 0 | 1,894 |

With the harness excluded, 132fee's hop 1 is 2 function seeds and **31,425** characters. `evaluate` accounts for
[1691 61 29253] characters of doc, spec and source.

### 2.3 N hops, re-measured

The frontier at hop k+1 is the first-party, non-taught callees and callers of hop k, minus everything already seen.
"Tests" counts the tests that call each frontier member.

| Task | Hop 1 members / content | Hop 2 members / names | Hop 3 members / names | Tests reaching hops 1 / 2 / 3 |
|---|---|---|---|---|
| 132fee | 2 / 31,425 chars | 78 / 5,609 chars | 393 / 27,766 chars | 66 / 397 / 1,536 |
| 300aca | 4 / 3,546 | 13 / 866 | 90 / 6,077 | 20 / 27 / 473 |
| cea47 | 3 / 1,894 | 55 / 3,938 | 294 / 21,050 | 81 / 319 / 1,752 |

- **Time.** N=2 for all three tasks took **24 ms** with row pulls memoized within the call. N=3 took **1,556 ms**
  with naive per-symbol pulls: 15,605 pulls, a single sample under test load.
- **Growth.** Each hop multiplies the frontier by 5–7 and its names by 5–7. Reaching tests grow faster still.
- **Conclusion.** Rendering hop 3 as names doubles 132fee's context and makes cea47's eleven times larger. Hop 3 and
  beyond must therefore be **counts per hop-2 member plus the expanding call**, never names. Tests beyond the proofs
  are always a count plus a call.

### 2.4 Read counts: today's refresh versus the context rule

The store holds 108 evaluations with read evidence, **21,110** evidence rows in all. Root's `latest-evaluations` holds
45 forms and **18,673** rows.

| Rule | Evaluations examined | Evidence rows pulled | Reads re-checked | Time |
|---|---:|---:|---:|---:|
| Today: `latest-evaluations` + `read-only-evaluation?` | 45 | 18,673 | 8 (the two effect calls, §0.4) | 3,254–3,369 ms |
| Context rule, subject = the evaluation's own **form constants** | 108 | 0 until selected | 0 for the 132fee context; 9 evaluations for a context holding `:example/amount`, `my.agents.root/largest`, `seon.fn/functions-using` | 3.1 ms join + 5.1 ms constants |
| Context rule, subject = the **evidence's** pattern values and arguments | 45 | 18,673 | 60 reads in 2 evaluations for 132fee, all platform reads from before the sink fix | 40.9 ms, after the 3.3 s pull |

### 2.5 The render ladder on an agent-owned map

This probe used `#'seon.render/declared-producers` on default. The installed order is `[:explicit-value :namespace
:declared :floor]`.

| Value | Selected AI producer |
|---|---|
| `{:seon.agent/id "root"}` | `seon.cluster.agent/render-identity-ai` |
| a pull of `[:seon.agent/id :seon.agent/branch]` | `seon.cluster.agent/render-identity-ai` |
| `{:seon.message/id "x" :seon.message/to {:seon.agent/id "root"}}` | `seon.cluster.message/render-ai` |

Any map that carries only agent attributes selects the agent pair, including a one-key identity leaf.

## 3. The owner's question, answered

The owner asked: "Should we just have a render function for the agent that creates its context? The task is an
entity right? So we can use that to accumulate data on the turn? Or should we have an agents entity attach the task
and then the context is a render function on that?"

### 3.1 The three alternatives, priced

| Option | What it is | Cost | What it sacrifices |
|---|---|---|---|
| **1. A render function for the agent creates its context** | The agent schema's AI pair acquires agent → task → N hops and returns the context. This is today's shape: `render-identity-ai` + concerns | about +50 / −93 lines; hours | **Acquisition runs wherever an agent-owned map renders.** §2.5 shows `{:seon.agent/id "root"}` selects the agent pair, so a `whoami` result, a pulled identity leaf, or (once nested values get the ladder, req 11.1) an agent inside a message would each run the N-hop walk: 8 ms at N=2, about 0.5 s at N=3. **The context exists only as printed text,** not as a value (req 12.1), so AI and HTML cannot share one tree (req 11.3). It breaks the ruled "no graph work in an AI renderer" (building block §0.3; function face §1.1; one-REPL review §6) |
| **2. The task entity accumulates the turn's data** | Turns, attempts and results are written onto the task; context = the task's render | about 1 day: new task attributes plus writer changes | **Tasks are authored data, reconciled by handle.** `task-delta` retracts a removed entry's entity (`issue.clj:634`), and `adopt-tasks-call` reconciles published tasks, so runtime facts on a task are erased or rewritten on re-ingestion. Tasks never ride merge (req 7.13), and runtime facts are separate entities pointing at the task (req 2.8, 7.7). Two agents on one task (3b.4) would write into one row |
| **3. The agent entity attaches the task; the context is a render function on that** (**recommended, refined in §3.2**) | `:seon.agent/task` names the task. One function returns the context value; its declared pair renders it | T1 in §6c: +89 / −93 source lines, +14 declaration lines | Nothing the owner asked for. It adds one call boundary between acquiring and rendering, and that boundary is what makes the context a value, its reads evidence, and its two faces one tree |

### 3.2 Root's four points: verified, refuted or refined

1. **The agent references its task.** Verified, as a **handle value, not a ref**. A ref would be swept silently when
   ingestion retracts or re-creates the task entity (`issue.clj:634`). Task ids are stored as values by rule (req 7.2;
   `seon.task.edn` `:tasks` "as string values (never refs)"). A handle that names no task is caught loudly by the
   existing dangling check (`task-findings`, extended to agents: one `or` clause). Cost: 2 declaration lines.
2. **The context is the agent's declared AI render, out to N hops.** Verified for the **face**; refined for the
   **acquisition**.
   - The context is rendered by one declared pair, AI and HTML, under the existing selection law. That pair belongs
     to the context **value** `:seon.agent/context`, not to the bare agent entity. Option 1's hazards (§3.1) come
     from putting the N-hop walk behind a pair that every agent-owned map selects.
   - The agent entity's own pair shrinks to identity, the task handle and the expanding call
     (`(seon.cluster.agent/context {})`). So rendering an agent anywhere shows the address of its context, not the
     context.
   - **N is a contract-declared input with a default; the caller wins.** It is a plain optional map key with the
     function's own `:or` default (`{:keys [seon.render/distance] :or {distance 2}}`). That needs no supplier row,
     because the caller's argument simply wins. `:seon.render/distance` is already registered (`seon.render.edn:129`,
     `[:int {:min 0}]`), so no new key is needed.
   - **A "hop budget" as a presentation budget is refuted.** Only the floor clips, under one budget B (AGENTS "Apply
     presentation limits once"). The lawful hop bound is query work: the existing `:seon.config.eval.result/max-nodes`
     cap the walk already uses. Past it, the value carries a named cut with its requery.
   - The HTML face of the same value is the debug page.
3. **Turn data stays on turns; tasks accumulate nothing.** Verified (option 2's sacrifices). No new turn attribute is
   needed either: the task a turn served is `(:seon.agent/task (d/as-of db turn-tx))`, because AGENTS says to use
   `history`/`as-of` for time and not to store a stamp. If a by-task query of turns proves hot, a `:seon.turn/task`
   handle value costs 1 line; that is not needed now.
4. **No separate `my.task/context` evaluation.** Verified, with one clarification. The opening's single generated
   form is **the call itself**: `(seon.cluster.agent/context {})`, with database and agent id supplied as the
   installed defaults (`:seon.db/db`, `:seon.agent/id`). The REPL evaluates it once, records it, and prints its value
   through its declared pair. That is "simply that render being called" as a REPL does it: the value is shown by its
   renderer. It stays an evaluation because that is how its reads become evidence (since `ca17618c0` the sink covers
   the evaluation and its shown result). Without that, §4 has nothing to re-check. The first-pass `my.task/context`
   adapter is dropped.

### 3.3 `seon.cluster.agent/context`: acquisition

```clojure
(seon.cluster.agent/context {:seon.db/db db :seon.agent/id "root" :seon.render/distance 2})
;=> #:seon.agent{:id "root" :task "132fee0467c8"
;                :context #:seon.task.view{:task {…row…}
;                                          :proofs [{:seon.test/sym … :seon.test/source …}]  ; hop 1
;                                          :content [fn-row | key-form | missing …]         ; hop 1
;                                          :addresses [{:seon.task.view/hop 2 :seon.fn/sym f :seon.task.view/edge :callee}]
;                                          :counts [{:seon.task.view/hop 3 :seon.fn/sym f :seon.task.view/count 7}]
;                                          :members #{…}}}                                   ; §4
;  | :seon.task/no-task-error | :seon.db/invalid-read-error
```

- **Where the code lives.** The context value belongs to the agent owner. The task-reference walk is
  `seon.issue/task-view`, next to `task-edges` in the task owner. The agent function pulls the agent's task handle
  and calls it: about 10 lines, plus about 50 for `task-view`.
- **Hop 1 is content:**
  - the task row;
  - the proofs' sources (the tests that must pass);
  - each seed's function row, pulled with the function-face selector;
  - each key seed's projection form;
  - a `missing` entry for a seed with no row: "you create this" when its namespace is a subject's, otherwise
    unresolved.
- **Hop 2 is names:** first-party callees and callers, by symbol (§2.3: 0.9–5.6 KB).
- **Hop ≥3 is counts:** per hop-2 member, a count and the expanding call, never names.
- **Tests beyond the proofs** are always a count and `(my.program/tests-reaching 'f)`.
- **Members** are every hop-1 and hop-2 symbol, key and lookup, plus the task handle.
- **Cost.** O(Σ frontier_k × (1 pull + 2 indexed lookups)), with row pulls memoized within the call. N=2 measured at
  24 ms for three tasks. The target is under 10 ms warm per task and a named cut past `max-nodes`.

### 3.4 The render: one pair, two faces, the one pipeline

- `:seon.agent/context` declares its pair in `seon.agent.edn`:
  - AI: `seon.cluster.agent/render-context-ai`;
  - HTML: `seon.cluster.agent/render-context-html`.
- Each face returns a `:seon.render/composition` (building block §3d):
  - the task row as its value;
  - each hop-1 function row as a child **value**, which the ladder hands to the function face
    (`seon.render.ns/function-ai`/`-html`, `ns.clj:699-742`);
  - addresses and counts as handle lines.
- The renderer does no graph work: every read happened in the evaluated call.
- The floor's one budget B covers the whole context. The subject's source renders whole through the function face.
- The debug page renders the recorded evaluation's HTML face. There is no page-side acquisition.
- Before `units` lands (building block S2), the pair runs through today's `render-call`.

### 3.5 Taught vocabulary without a new attribute

- The rule is `taught = publics of the viewer namespace's :seon.ns/requires ∪ help-value's :seon.fn/see-also`.
- The first set is the help "Tools:" set (`bootstrap.clj:40-48`).
- The second is one `:seon/see-also ['seon.db/q 'seon.db/pull 'seon.db/transact! …harness…]` entry on
  `help-value`. The analyzer already ingests it (`fn.clj:621-630`).
- That costs 1 line. Help topics with `:seon.help/about` can replace it later.

### 3.6 The opening

```clojure
[{:seon.cluster.eval/source "(help)"}
 {:seon.cluster.eval/source "(seon.cluster.agent/context {})"}]
```

- `declared-sources` returns these instead of walking. It has no print-then-read-back step and no pre-execution.
- The situation line stays: unread count, turns left and the trigger (`render-situation-ai` `agent.clj:396-430`).
  These are the agent's own facts.
- `planned-sources` stays; it has eight other callers.

## 4. Refresh draws from the context

`system-plan` keeps its shape. Only its candidates change.

1. **Candidates.** The candidates are the §3.6 forms, plus retained evaluations whose **form constants** (symbols,
   keywords, strings and lookup vectors in their reader forms) intersect the newest context value's `:members`.
   - Selection first runs the history join without the evidence pull (3.1 ms measured).
   - Then `read-only-evaluation?` runs only on the matches.
   - Evidence is pulled only for the survivors.
2. **Currency.** `read-evidence-current?` and `read-evidence-changes` run unchanged, in the database owner. The
   context call's own evidence is exactly the reads that built it. Its currency is therefore "has the context
   changed".
3. **Reads outside the context:**
   - they are not re-checked, re-run or evidence-pulled;
   - their saved shown text stays in history as that turn's observation;
   - no staleness claim is made about them;
   - the agent re-runs one on demand.

   When the task changes, the next context and candidate set follow.
4. **Cost.** Selection is O(retained evaluations × form size): about 8 ms for 108 evaluations, instead of
   O(retained evidence rows) at 3.3 s. Currency is O(in-context evidence). The target for the whole plan is under
   50 ms; parent-versus-change timing is required at landing.

## 5. Agents without a task: small and loud

- Without `:seon.agent/task`, the call returns `:seon.task/no-task-error` over `:seon.error/base`:
  - member `:seon.agent/task`;
  - expected `:seon.task/handle`;
  - the message "No task: this agent's context is generated from its task. Ask root to assign one.";
  - the open-task count plus the call that lists them.
- The opening is `(help)`, the situation line and that error: under 1 KB.
- There is no namespace, neighbourhood or history fallback. Refresh re-checks the declared forms only.
- Root has no task today; see §8 Q2.

## 6. Every context builder, and what happens to it

### 6a. On the agent path

| Path | Range | Lines | Fate |
|---|---|---:|---|
| `seon.bootstrap` opening candidates, `entries` … `next-entry` | `bootstrap.clj:204-731` | 502 | **delete** (T0); production-unreachable, with its two test cases |
| `declared-sources` walk | `turn.clj:1870-1923` | 54 | rewritten as the §3.6 forms (~12) |
| agent AI pair with issue openings: `render-identity-ai`, `render-id-ai`, `identity-form` | `agent.clj:246-252, 278-310` | 39 | rewritten to identity + task handle + expanding call (~12) |
| `seon.issue.opening` | whole file | 233 | delete (T3), conditional on the issue → task migration (7.11) |
| `latest-evaluations` evidence pull | `turn.clj:1925-1957` | 33 | rewritten as §4.1 (~24) |
| `system-plan` history concat | `turn.clj:1977-2017` | 41 | rewritten to context candidates (~36) |
| namespace AI face as an opening concern | `ns.clj:469-550, 750-781` | ~135 | no longer reached from the opening; kept for explicit requests and the namespace page; not credited |

### 6b. Views outside the agent path (AGENTS "The Seon agent is the perspective")

| Builder | Where | Lines | Fate |
|---|---|---:|---|
| Transcript session, outline and ledger projection | `seon.render.transcript` | 2,486 file | reads the recorded evaluations, including the context call, and the capture; its adapters are booked as building block Q6 (204) |
| Debug page context retention | `web.clj:2537-2610` `derive-context!` | 74 | building block Q5; the page shows the context call's HTML face |
| Ambient agent-facing walk | `render.clj:1812-1976` `seon.render/walk` | 159 | building block Q2 |
| Eval driver transcript | `eval/drive.clj:288-300` `full-transcript` | 13 | reads the turn's `:seon.context.capture/prompt` bytes |
| MCP | `script/seon/dev/mcp.clj` | — | builds none, so outside agents get none. A `branch` for a task sets `:seon.agent/task`, and its first evaluation is the context call (§8 Q4) |
| Test harness | `test/seon/test_support.clj` | — | none found; tests call the owners |

### 6c. Ledger

| Slice | Change | Source + | Source − | Declarations |
|---|---|---:|---:|---:|
| T0 | delete bootstrap's production-unreachable opening candidates | 0 | 502 | −8 est. (`seon.bootstrap.edn:2-8` error keys, if no other raiser remains) |
| T1 | `task-view` (~50), `agent/context` (~10), opening forms (~12), agent pair rewrite (~12), context pair (~20 of `render-context-ai/-html` over the composition), no-task branch (~4), see-also (1); deletes the walk use and the old agent pair | 109 | 93 | +14 (`:seon.agent/task` 2, `:seon.agent/context` + pair 6, no-task error 6) |
| T2 | context-bounded refresh (§4) | 68 | 74 | 0 |
| T3 | delete `seon.issue.opening`; issue workers get `:seon.agent/task` | 5 | 233 | −1 (the issue-opening dial) |
| **Total** | | **182** | **902** | **+5** |

T1 exceeds its first-pass estimate by the context pair's 20 lines. Every slice stays under 100 added lines except
T1, at 109. T1 splits cleanly: T1a is acquisition plus the opening (89 lines); T1b is the pair (20 lines, landing
with or after building block S3's composition). Net source is **−720**, before about +120 lines of regressions.
Without T3, net is −492.

## 7. Simplest alternative

**Option 1 as it stands:** drop `:seon.agent/namespace` from the agent's concerns and add `:seon.agent/task` as a
concern, keeping the walk and `render-identity-ai`. That is about +40 lines. It keeps plan, notes, messages and faults
as a second producer, the print-then-read-back step, the unbounded refresh, and the §3.1 hazards. Rejected.

## 8. Open questions for the owner (priced; simplest viable first)

1. **What a read's subject is, for refresh.**
   - **A (recommended):** form constants: 8 ms for 108 evaluations. It misses reads through a `result/e…` handle,
     which are then simply not refreshed.
   - **B:** evidence pattern values, as an indexed `:seon.db/read-subjects` written at capture; about 0.5 day plus
     capture cost.
   - **C:** both; the costs of A and B.
2. **Root and other taskless agents.**
   - **A:** §5 as written; 0 lines; root must ask for work.
   - **B (recommended):** A, plus a standing root task in `resources/seon/tasks/seon.cluster.edn` whose must-reads
     are the status, faults and agents verbs; about 5 EDN lines, hours.
   - **C:** refuse to open a turn without a task; about 5 lines; this stops root.
3. **Default N.**
   - **A (recommended):** N=2 (hop 1 content, hop 2 names, hop 3 counts only when the caller asks for N=3); measured
     24 ms and 2–37 KB.
   - **B:** N=1 (content only; hop 2 as counts); smallest, but the agent must ask to see callers.
   - **C:** N=3 with names; +6–21 KB and about 0.5 s per task (§2.3).
4. **Messages and outside agents.**
   - **A (recommended):** the trigger message is in the situation line; other messages, notes and the plan are on
     demand; MCP `branch` takes a task handle; about 10 lines.
   - **B:** messages whose subject is in the context become content; about 20 lines, plus a message-subject edge that
     does not exist.
   - **C:** keep the messages concern; 0 lines, but it is a second producer.

## 9. Unresolved risks and out-of-scope findings

This lane writes one file, so root files these. Existing issues were not searched for 2–4.

1. **Not implemented; nothing installed.** The probes were inline `let` forms, and each timing is a single sample
   under concurrent test load.
2. **Effect calls pass `read-only-evaluation?`** (§0.4). Suggested issue:
   `docs/seon/issues/effect-calls-pass-the-read-only-refresh-test.md`.
3. **The task writer admits a dangling must-read** (§0.5), against requirement 4.1. Suggested issue:
   `docs/seon/issues/task-must-read-links-are-not-resolved.md`.
4. **`latest-evaluations` pulls all history evidence** (§0.3), a >1 s cost on every system turn until T2. Suggested
   issue: `docs/seon/issues/system-plan-pulls-every-retained-read.md`.
5. **Bootstrap's opening candidates are production-dead.** Before T0, verify that no render property or `resolve`
   names them; `rg` over `resources/` found only their error keys.
6. **Requirement §5a.4's 7.7 KB figure is unreproducible** (§0.1). Its row should cite §2.2–2.3 here.
7. **Nested values do not get the ladder today** (req 11.1 "nested open"), so option 1's hazard for an agent inside a
   message is the target behaviour, not today's. A top-level agent-owned map (§2.5) triggers it today.
8. **This design depends on `ca17618c0` being loaded;** default predates it. **RESET NEEDED: no.**

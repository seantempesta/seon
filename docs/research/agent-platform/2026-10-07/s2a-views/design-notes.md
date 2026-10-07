---
type: design
status: proposed (gate 1: dual review, then owner approval before code)
created: 2026-10-07
tags: [design, agent-loop, deletion, debug]
---

# S2a: delete the dead debug context views (lane s2a-views)

**What exists.** The agent debug page (`/agent/<id>/debug`, `seon.render.web/debug-response`) shows the old prompt
assembly five ways: the turn session ("Context at turn N"), the outline, the ledger's "Full context as sent", the
"context as sent" panel and the walk graph page (`?n=`). Each one either re-runs the prompt assembly
(`seon.render/acquire-context!` → `seon.render.web/derive-context!` → `seon.render.walk/history`, or
`seon.cluster.prompt/prompt`) or reads the stored prompt capture. The turn ledger, the page's default view, also gets
its data through that assembly, behind a `::transcript/ledger?` flag inside `derive-context!`.

**After.** The views of the assembly are deleted. The ledger stays, and reads `seon.eval/of-agent` with an explicit
selector that pulls no read evidence. Nothing on the debug page calls `acquire-context!`, `prompt/prompt` or the
capture any more. The principal Clojure approach is deletion plus one in-place conversion: three private functions
(`ledger-acquisition`, `ledger-evaluations` and the public `acquire-ledger-data`) collapse into one private
`ledger-data` over `seon.eval/of-agent`.

Happy-path check (lane-rules "One happy path"): this slice serves the **context** step by removing four extra
producers of "what the model saw", and removes debug display work from the prompt path (`derive-context!`). It adds
no mechanism. The system turn, compaction, the prompt, the capture and stored read evidence are untouched. S4 deletes
them, together with every display of their data.

Revision: HEAD `ec5f13d2f`. Default ran `0c2a12900` (identical `src/seon/render/*` to HEAD), pid 69486, before root's
restart. `file:line` references are HEAD's.

## 1. Probe and measured cost (default, 2026-10-07)

`bin/seon status` and MCP `runtime_status`: default is `:faulted`, with 2 open faults (both linked to tasks
`3d2fb1b41dc8` and `seon.repl/response`'s 8), 4 over-bound warnings and 1 cache-discipline warning. None of them is
in S2a's files. The store had been reset since r-delete. Only root has evaluations: 113, on 89 turns
(`:generate` 1, `:call` 21, nil 67).

JVM-mode probes only called installed functions inside a `let`. They defined nothing and wrote nothing. One SCI agent
(`s2a-views`) filed the tasks below on default's connection. Its branch `:agent-06f93e0d9c80` is retired (59 ms).

**Today, over HTTP (`curl`, `127.0.0.1:7994`):**

| Request | Time | Body |
|---|---|---|
| `/agent/root/debug` (ledger page), first request after any root write | 2.57 s (×2) | 117 KB |
| the same, warm (retained `::ai-calls`) | 0.71-0.83 s | 117 KB |
| `?turn=c62a…` datastar (session view, deleted) | 3.53 s | **1.23 MB** |
| `?turn=…&context=true` (Full context, deleted) | 2.19 s | 1.21 MB |
| `?outline=true` (deleted) | 1.70 s | 615 KB |
| `?turn=…&ledger=true` (survivor) | 1.69 s cold, 0.70 s warm | 116 KB |
| `?sent=true` (deleted) | 26 ms (no provider) | 147 KB |
| `?n=2` (graph page, deleted) | 0.35 s | 164 KB |

Every request over 1 s is over budget. Every root evaluation (including each MCP `eval_clj`) invalidates the retained
AI history, so the ledger pays the cold 2.6 s again on the next request.

**The surviving ledger's parts, measured on root in JVM mode (thread-allocated bytes):**

| Step | ms | MB |
|---|---|---|
| `seon.eval/of-agent` with today's ledger selector (includes `{:seon.cluster.eval/read-evidence [*]}`) | 57 | 106 |
| the same selector without read evidence (the S2a selector) | 13-21 | 31-40 |
| `seon.eval/of-agent` default selector (did not refuse on this fresh store) | 18 | 43 |
| `repl/entity-emission` × 113 | 200 | **345** |
| `repl/text` × 113 | 22 | 49 |
| `emission-label` × 113 | 0.8 | 1.6 |
| `turn-entities` | 11 | 26 |
| `ledger-entities` (of which `turn-kind`: 609 ms / 1,144 MB) | **634** | **1,165** |
| `ledger-effects` | 30 | **186** |
| `session-budget`, `fault-problems` | 4 | 7 |

Two notes on these numbers:

- **`turn-kind` dominates.** For each `:call` turn without attempts it rebuilds that turn's opening database
  (`turn/opening-db`, transcript.clj:1066) and re-reads the no-provider dial. That costs about 29 ms and 54 MB per
  turn, linear in `:call` turns.
- **The default selector's refusal did not reproduce here.** The persisted task is
  `seon.eval/of-agent-default-selector-refuses-on-roots-stored-read-plans`; r-delete's `6ccd16292e4f` was dropped as
  its duplicate. It does not reproduce on this fresh store. The explicit selector is still required: the default pulls
  `{:seon.cluster.eval/read-evidence [*]}`, and that costs 3.5× in time and allocation even when it does not refuse.

**Tasks filed** (each on default; ids verified by query):

- `seon.render.transcript/turn-kind-reads-each-call-turns-opening-database` (`a9a7bb6e6f29`);
- `seon.render.transcript/ledger-effects-allocates-per-turn-interval` (`ec70b6017334`);
- `seon.repl/entity-emission-allocates-megabytes-per-evaluation` (`82b1b5c78c1d`).

**Other finding.** `(doc seon.task/create!)` printed every `:map` entry's key schema twice (`[[:fn …] [:fn …]]`).
That is ugly output, but it is the S1 lookups lane's `doc`. It is reported here; no task was filed, to avoid
duplicating S1's design.

## 2. What is deleted (Vars, callers, tests)

### 2.1 `src/seon/render/transcript.clj`: 20 Vars, 448 def lines (script `scratchpad/s2a-views/rmdefs.py`)

| Var (line) | Lines | Its only callers | Reason |
|---|---|---|---|
| `attempt-html` 1094 | 11 | `render-session` | session view |
| `session-url` 1109 | 3 | `session-header` toggle, `render-session(-loading)` | session route |
| `outline-url` 1118 | 3 | `render-session-loading` | outline route |
| `session-origin` 1122 | 5 | `render-session`, `outline-unit` | session/outline |
| `render-session-loading` 1231 | 27 | web `debug-response` (2 sites) | deferred session |
| `render-session` 1259 | 115 | web `debug-turn-response` `:full`, `render-ledger-context` | acquires the prompt |
| `outline-signal`, `outline-turn-kind`, `outline-calibration`, `outline-renderer`, `outline-unit`, `outline-everything`, `render-outline` 1412-1611 | 194 | web `debug-outline-response` | outline; `requiring-resolve`s `prompt/agent-calibration`, `prompt/prompt`, `sci.kernel/context-projection` |
| `ledger-acquisition` 1613, `ledger-evaluations` 1629 | 20 | `render-ledger(-turn)` | the ledger through `acquire-context!`, replaced by `ledger-data` |
| `ledger-context-id` 1641, `render-ledger-context` 1865 | 11 | `ledger-turn-body`, web `:context` | "Full context as sent" |
| `prefix-subject` 1966, `render-captured-prefix` 1972, `prefix-problem` 1988 | 59 | `session-problems`, the schema pair | compares stored prompt captures (an atom swap on `render/shared-cache`) |

`acquire-ledger-data` (1730, public, called only by web `derive-context!` 2522) is converted in place into the private
`ledger-data` (§3). `emission-label` stays without its read-evidence branch (§3).

ns requires that go: `seon.ai.tokens`, `seon.env`, `seon.render` (no use survives; verified by grep on the cut file).
Everything else still has callers.

### 2.2 `src/seon/render/web.clj`: 11 Vars, 322 def lines

| Var (line) | Lines | Only caller |
|---|---|---|
| `debug-outline-response` 3213 | 15 | `debug-response` `?outline=` |
| `graph-node-name` 3229, `inclusion-paths` 3239, `edge-kind` 3250, `node-attributes-html` 3270, `context-graph-model` 3292, `graph-legend-html` 3352, `graph-debug-href` 3370, `graph-node-label` 3377 | 154 | `graph-debug-html` |
| `graph-debug-html` 3407 | 138 | `debug-response` `?n=` |
| `sent-context-html` 3391 | 15 | `debug-response` `?sent=` (calls `prompt/prompt` via `requiring-resolve`) |

The shared helpers they used keep other callers: `forward-attribute`, `many-attribute?`, `projection-form`,
`scalar-label`, `json-script-text`, `render.walk/graph` (via `walk/related` 1241) and `graph-structure`. They stay.
The `seon.ai.tokens` require goes. `reader.edn` stays (used at 181).

### 2.3 Resources

- `resources/seon/schemas/seon.render.transcript.edn`: delete `:seon.render.transcript/prefix-subject`, which is a
  value schema with the `render-captured-prefix` AI pair. No stored data uses it, but it is a schema resource, so it
  needs publication and adoption on a branch of the live store (§6).
- `resources/public/css/input.css`: about 100 lines whose selectors name only classes no surviving source emits
  (census `scratchpad/s2a-views/deadclasses.txt`: `seon-outline-*`, `seon-graph-page`/`-node`/`-faces`/…,
  `seon-session-rereads`/`-raw`/`-gutter`/`-line`/…). Lines with mixed selectors are edited by hand. `output.css`
  is regenerated with `bin/css`.

### 2.4 Tests deleted (behavior retired with its mechanism, clojure-testing §3 rule 1)

| Test | Lines |
|---|---|
| `web_debug_test`: `the-debug-outline-is-one-line-per-turn-and-one-line-per-unit`, `the-outline-toggle-serves-the-exact-stored-shown-text`, `the-outlines-show-everything-is-the-composed-prompt`, helpers `outline-fixture`, `outline-nodes` | ≈ 175 |
| `web_debug_test`: `the-graph-debug-page-shows-both-faces-in-graph-order-at-every-n`, `…-reads-a-named-branch-read-only`, `the-graph-groups-a-many-relationship-into-one-counted-node`, helper `graph-model` (and the `clojure.data.json` require, if orphaned) | ≈ 120 |
| `web_debug_test/ledger-derives-shared-data-once`: its behavior (ledger data retained inside the AI acquisition cache, counted with `with-redefs-fn`) is retired. Converted, see §3 | — |
| `transcript_test/a-refused-render-profile-is-passed-on-never-used-as-the-prefix-profile` | 21 |
| `web_test/next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake`: a **golden**. Its declared behavior ("the colourised reader … in acquisition order", "the raw toggle") is the deleted session view. Its system-turn assertions belong to S4. **Root's call** (clojure-testing §3: goldens are discussed with root). Walk-delete records root's earlier approval | 73 |

## 3. What survives, and what each converts to

| Survivor | Today | After | Label |
|---|---|---|---|
| `transcript/ledger-data` (private; replaces `ledger-acquisition`, `ledger-evaluations`, `acquire-ledger-data`) | `acquire-context!` with `::ledger? true` and a selector that includes `{:seon.cluster.eval/read-evidence [*]}` | `(seon.eval/of-agent db agent-id ledger-selector)`. The selector is today's ledger list without the read-evidence entry; it is explicit, never the default. Then the same per-evaluation map (`::emission`, `::label`, `::outcome`) grouped by run, `ledger-entities`, `ledger-effects`. It returns `{::evaluations ::rows}` or the read refusal (`:seon.db/invalid-read-error`, `:seon.agent/no-such-agent-error`, the `of-agent` contract's variants) | BEHAVIOR CHANGE: no retention across requests (§5) |
| `transcript/emission-label` | the title of the attribute the stored read plan depended on, else the matching renderer's schema, else "Evaluation N" | the matching renderer's schema title, else "Evaluation N". Its projection is `(db/carried-projection database)`, not `requiring-resolve`d `sci.kernel/context-projection` of the ctx | BEHAVIOR CHANGE: a label that came from read evidence now falls to renderer or ordinal. Measured on root: all 113 labels were already "Evaluation N" |
| `transcript/render-ledger`, `render-ledger-turn` | `ledger-acquisition`, `::calibrations` | `ledger-data`. `render-ledger-turn`'s contract changes from `:seon.cluster.prompt/request` (a D1c schema) to `render-ledger`'s own unit contract plus `:seon.turn/id` | EQUIVALENT apart from the rows below |
| `transcript/ledger-turn-body` | queries `:seon.context.capture/prompt` and prices it with `tokens/estimate` and a calibration; "Full context as sent" `<details>` fetching `?context=true` | capture query, calibration and that `<details>` deleted | BEHAVIOR CHANGE (view deleted) |
| `::contributed-bytes`, `emission-byte-count`, `::bytes`, the strip's `width`/`largest`/`data-added-bytes`, churn group `::bytes`, the heading's byte count | the size of each evaluation's segment in the composed prompt | deleted: "context added" is a measure of the prompt assembly. The strip keeps colour, tone and problem marks; the heading keeps its emission count | BEHAVIOR CHANGE (prompt measure deleted). Alternative: `(blob/utf8-size (repl/text emission))`, which matched the walked bytes on 110 of 111 emissions (one declared-render emission differed), +2 lines |
| `transcript/session-problems` | 10 rules + fault rules, including `prefix-problem` | the same without `prefix`; signature unchanged | BEHAVIOR CHANGE (rule deleted) |
| `transcript/problems-html` | `#{:churn :prefix :repeated}` warning set; "Prefix stable on …" passed-check text | `:prefix` removed from both | EQUIVALENT for the remaining rules |
| `transcript/session-header` | "As the model saw it"/"Turn ledger" toggle (`::raw?`, `session-url`) | toggle deleted; "agent \| debug", "Latest", actions and message form kept | BEHAVIOR CHANGE (view deleted) |
| `transcript/turn-kind` | per `:call` turn: `turn/opening-db` + `config/agent-cluster-dial` | **Root decides** (task `a9a7bb6e6f29`). (a) Unchanged: the ledger stays about 0.9-1.0 s on root and grows about 29 ms per `:call` turn. (b) A `:call` turn without attempts is "Virtual" from its own facts: 10 lines deleted, about 600 ms and 1.1 GB removed on root. Only the deleted session test asserts "Virtual"; whether a provider-enabled `:call` turn can close with no attempt row is UNVERIFIED. **Recommend (b)**, with that one check traced before code | (b) BEHAVIOR CHANGE |
| web `debug-turn-response` | 3 arities; views `:card :context :ledger :full` | one arity `[service agent-id turn-id view]`, views `:card :ledger`. `debug-response` passes `(if (get query "card") :card :ledger)`, so a datastar `?turn=X` serves the ledger with X selected | BEHAVIOR CHANGE (`?turn=X` alone used to serve the session) |
| web `debug-response` | branches `?outline=`, `?sent=`, `?n=`, `?prompt=` (session-loading), and `:else` `prompt-section` (`render-session-loading` on the subject inspection page) | those branches and `prompt-section` deleted; the agent page always renders `render-ledger`; `?record=`, `?turn=` and the subject inspection stay | BEHAVIOR CHANGE (views deleted) |
| web `debug-query`, `debug-query-strings` | `::prompt-preview?` / `:prompt "true"` | deleted | EQUIVALENT for every other key |
| web `shell` | loads cytoscape and `seon-graph.js` when `viewer-namespace` or `::graph?` | `viewer-namespace` only (the entity graph `debug-graph-html` still needs them) | EQUIVALENT for surviving pages |
| web `derive-context!` | `ledger-data` branch under `::transcript/ledger?` (2520-2527) | deleted. The prompt path no longer carries debug display work | EQUIVALENT for the prompt (the flag was never set on the prompt path) |
| web `session-controls`, `change-context`, `context-action-response`, `render/acquire-context!` | the System turn, Virtual turn and Compact actions through `acquire-context!` | **unchanged in S2a**. They are controls of live mechanisms (the system turn runs before every provider turn until S4), and `change-context` is reached through `acquire-context!`, which S4 deletes. S4 converts all of them in one commit | — |
| `seon.cluster.prompt/agent-calibration` | callers: `outline-calibration`, `acquire-ledger-data` | no src caller left; only `prompt_test`. **Left for S4**, which deletes the whole `seon.cluster.prompt` namespace, so S2a stays file-disjoint from S4 | — |

### Tests converted

- **`web_debug_test/turn-details-use-the-loop-opening-and-exact-segments`** (45-368). Delete:
  - the `render-session` assertions (`listed` kinds; `detail`, `exact` and `byte-count`; origins; "cache-hit tokens";
    the `debug-response` body equal to the session; the "re-read" text at 322);
  - the `prefix-problem` block (330-349);
  - the "strip accounts for every byte in current context" assertion;
  - `:prefix 0` from `expected`.

  Convert:
  - `#'transcript/ledger-evaluations` → `::evaluations` of `#'transcript/ledger-data`;
  - `(#'web/debug-turn-response service "root" provider-id)` → the 4-arity form, still 404;
  - the `debug-response` `?turn=` assertion compares against `render-ledger` of the unit with that turn selected.

  The ledger, acquisition, problems and budget assertions stay.
- **`web_debug_test/blocks-use-the-values-schema-documentation`**: delete the `emission-label` read-evidence
  assertion; the `block-metadata` assertions stay.
- **`web_debug_test/ledger-derives-shared-data-once`**: delete the `with-redefs-fn` counters and the retention
  assertions. Assert on `ledger-data`'s rows instead: `::effects ::details` names "message sent to root", and
  `::attempt ::usage` carries 100 in / 12 out per turn.
- **`web_debug_test/passive-ledger-never-reconstructs-directory`**: it uses `with-redefs` on
  `sci.eval/directory-value` and `repl/render-directory-ai`. Convert it to `support/armed-calls` if both are armed;
  this is UNVERIFIED. If they are not, keep the test and file the conversion (clojure-testing §3, 15-minute rule).
- **`web_context_test/selected-session-defers-prompt-acquisition`** becomes the slice's **class regression**: the
  ordinary `/agent/root/debug` makes **zero** `seon.render/acquire-context!` calls (today one), counted by
  `support/armed-calls`. The `?prompt=true` half and the inspection page's "Session" assertions go. A rename is not
  needed; the docstring and the assertion message change.
- **`web_test`**:
  - `inspecting-the-page-writes-no-run-evaluation-or-fault-facts` (916),
    `each-agent-has-an-isolated-debug-route` (2010) and `debug-algorithm-carries-the-render-evaluation-inputs` (2347)
    fetch `/agent/<id>/debug` instead of `?prompt=true`.
  - `#surface-debug-session_2f_<id>` still exists (`session-id` stays on the ledger section).
  - The three-action assertion is unchanged.
- **`turn_test`** 621: delete `(is (str/includes? html "seon-ledger-full-context"))`.
- **Unchanged**:
  - `run6_stall_test` (`session-problems` 3-arg, first rule still `:stalled`);
  - `rereads_panel_test` (`render-ledger`, the stale-but-unchanged rule);
  - `web_context_test/context-and-page-do-not-demand-the-render-proc` (it tests `acquire-context!`, which is S4's).

### Docs, skills and tasks corrected in the same commit (false current claims)

- `.agents/skills/datastar-web-ui/SKILL.md:66-68`: "uses the turn ledger and the acquired session component; entity
  inspection shares that session component" becomes "the turn ledger, read from `seon.eval/of-agent`".
- `docs/seon/specs/render-dedup.md` (draft): §1 inventory rows, §2 D3/D4/D5 and §3 items 4, 5 and 7 describe the
  deleted views. Revise them in place to say the views are deleted and the ledger reads `of-agent`.
- `resources/seon/tasks/seon.render.web.edn` `the-render-cache-keeps-every-debug-view`: `:must-read` names
  `graph-debug-html`, and its `::graph-calls` part is dissolved. Re-point it to `seon.render/render-call` and say
  what remains (`::invocations`).
- `resources/seon/tasks/seon.render.edn` `landing-reds-2-render-bow-outs`: `:must-read` names `render-session`;
  re-point it to `seon.repl/entity-emission` and strike the dissolved clause.
- `docs/seon/issues/class-readerless-duplicate-mechanisms-survive-cuts.md:87` names `render-session-html`. That name
  is already absent from HEAD; it is historical, so leave it.

## 4. Duplicates and library seams

Duplicates removed:

- four acquisitions of one prompt (session, outline, outline "everything", sent);
- two graph pages: the walk graph goes and the entity graph `debug-graph-html` stays;
- two "AI face beside HTML face" experiments (the graph page's `face` closure goes, `experiment-preview-html` stays);
- a ledger acquisition riding inside the prompt-path cache.

These are render-dedup's D3, D4, D5 and §3.5. render-dedup kept every view; the approved agent-loop spec (§1.4: context
is forms that ran) makes them views of a mechanism being retired.

Library seams:

- Datahike pull through `seon.eval/of-agent` (the owner, eval.clj:17), with a narrowed selector, as its 3-arity
  contract allows;
- no cache: Caffeine via `seon.cache` is not warranted, because the uncached ledger (§5) is cheaper than today's cached
  one, cold or warm.

## 5. Smallest composition, cost and alternatives

**Cost after S2a**, estimated from the measured parts on root (113 evaluations, 89 turns), every request, with no
retention:

| Variant | Ledger request cost | vs today |
|---|---|---|
| (b) `turn-kind` from own facts | ≈ 300 ms, ≈ 650 MB | today: 2.6 s cold after any write, 0.7 s warm |
| (a) `turn-kind` unchanged | ≈ 0.9-1.0 s, ≈ 1.8 GB | — |

The remaining allocation is `entity-emission` (345 MB, task `82b1b5c78c1d`) and `ledger-effects` (186 MB, task
`ec70b6017334`). Both are pre-existing and already paid by today's ledger. They stay over the 10 MB budget and are
reported, not hidden.

Work is proportional to the agent's evaluations and turns (unchanged); the per-evaluation AI render walk
(`render-call` × evaluations, the retained-call bookkeeping) is gone.

**Lines (estimate, `wc -l` delta):**

| Area | Delta | Breakdown |
|---|---|---|
| transcript.clj | ≈ −500 | −448 defs, ≈ −55 in conversions, `ledger-data` ≈ +22 against `acquire-ledger-data`'s 25 |
| web.clj | ≈ −365 | −322 defs, ≈ −45 in `debug-response`, `debug-turn-response`, `debug-query*`, `derive-context!` |
| schema | −6 | |
| input.css | ≈ −100 | |
| **src total** | **≈ −870 Clojure, −970 with CSS** | |
| tests | ≈ −390 without the golden, −463 with it | |

Nothing new is added: no namespace, schema, cache or helper. `ledger-data` replaces three functions.

**Alternatives considered:**

1. **Walk-delete's saved patch** (`scratchpad/walk-delete/slice-b1-debug-views.patch`). It still applies to HEAD's
   index (`git apply --check --cached`: clean): −1,207/+137, six files. It goes further than S2a:
   - it also deletes the ledger's display of system-turn data (generated/opening sections, `churn`,
     stale-but-unchanged, re-read story, strip widths) and the System turn/Compact actions;
   - it still needs `sci.kernel/context-projection` from the ctx.

   Those are displays and controls of mechanisms that still run until S4. Deleting them now blinds the debug page to
   most of a provider turn's commits for the S2a→S4 interval, and splits S4's "convert every caller" across two
   slices. Judged: reuse its `ledger-selector` and `ledger-data` shape (close to §3), but not its system-turn
   deletions. Root may choose it instead if the interval's blindness is acceptable; it saves S4 about 330 transcript
   lines.
2. **Keep the ledger on `acquire-context!`** and delete only the other views. Smaller diff (≈ −650), but the ledger
   keeps the prompt path's cache and its 2.6 s cold cost, and S4 must convert it anyway. Rejected.
3. **Keep `::contributed-bytes` from `repl/text`** (+2 lines, keeps the strip widths). An option, not recommended:
   it keeps a prompt measure under a new definition.

## 6. Risks, tests that reach them, and claims

**Risks:**

1. **File ownership.** `src/seon/render/transcript.clj` is claimed by lane **s5a-reply**, with uncommitted hunks at
   333-760 (`fault-values`, `with-errors`, `held-entry`). S2a's hunks are 1061-2275 (disjoint), but one file has one
   owner. S2a code waits for s5a-reply to commit or release the file, or S2a sends root a patch. `web.clj`, both
   schema/task resources, the tests and the CSS are unclaimed today.
2. **Hidden callers.** `requiring-resolve` and quoted symbols escape clj-kondo. Grepped: the only quoted references to
   deleted names are the schema pair (`render-captured-prefix`) and the two task files (§3). Before commit, re-run
   the grep over `src test resources .agents docs/seon`.
3. **Schema resource change** (`prefix-subject`). Prove it by publishing and adopting on a branch of the live store
   (CLAUDE.md "Change one loadable slice"). No stored datoms use it.
4. **`turn-kind` option (b)**: trace `seon.turn/record-attempt!` to confirm that a provider-enabled `:call` turn always
   records an attempt row, or the label is wrong for that case.
5. **Datastar URLs in browsers.** Open pages that hold `?prompt=true`, `?outline=true` or `?n=` URLs get the plain
   ledger (`?prompt`, `?outline`) or the ledger page (`?n`). They get no 404, by the cond fall-through. Verify browser
   paint separately.

**Tests that reach the change** (name them in `bin/lane commit --test`):

- `seon.render.web-debug-test`;
- `seon.render.web-context-test`;
- `seon.render.transcript-test`;
- `seon.render.web-test` (the three converted deftests; the namespace may exceed 120 s, so name members);
- `seon.turn-test` (the ledger-turn deftest at 590-630);
- `seon.run6-stall-test`;
- `seon.rereads-panel-test`.

**Expected S0 numbers after:** unchanged within noise. S2a touches no stage of the model pass or the MCP pass; the
`derive-context!` edit removes a branch the prompt never takes. Verified by re-running `seon.pass-probe/run-mcp` and
`run-model` on the parent and the change. The debug ledger gets its own before/after row: §1's HTTP table plus
`ledger-data` ms/MB.

**Claims needed** (`bin/lane claim s2a-views …`):

- `src/seon/render/transcript.clj` (after s5a-reply) and `src/seon/render/web.clj`;
- `resources/seon/schemas/seon.render.transcript.edn`;
- `resources/public/css/input.css` and `output.css`;
- `resources/seon/tasks/seon.render.edn` and `seon.render.web.edn`;
- `.agents/skills/datastar-web-ui/SKILL.md` and `docs/seon/specs/render-dedup.md`;
- `test/seon/render/{web_debug_test,web_context_test,transcript_test,web_test}.clj` and `test/seon/turn_test.clj`.

## 7. Design check

The design held against the code. Four things were awkward:

- **The ledger's data was entangled with the prompt.** It came from the prompt assembly (`walk/history` segments), so
  "contributed bytes" is a prompt measure. Deleting it is the honest cut.
- **`turn-kind` is the real cost** of the surviving ledger, and it is not a view of the assembly. It needs root's
  ruling, (a) or (b).
- **Two survivor tests use Var redefinition.** One is deleted with its behavior; the other converts if its targets
  are armed (UNVERIFIED).
- **Walk-delete's patch is a correct but wider cut.** It collides with S4's scope; §5 alternative 1 prices it.

**Untraced (UNVERIFIED):**

- `record-attempt!` on a provider-enabled `:call` turn;
- whether `sci.eval/directory-value` and `repl/render-directory-ai` are armed;
- exact CSS line count after hand-editing mixed selectors;
- browser paint.

**RESET NEEDED:** none for S2a. Root restarted default during this design; nothing here requires another restart.

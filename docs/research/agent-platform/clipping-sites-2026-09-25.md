---
type: research
status: current
created: 2026-09-25
scope: Every clipping, truncation, top-N, window and budget site in src, script and resources, with a KEEP/CONVERT/DELETE verdict against the no-budgets ruling, and what the floor value printer needs.
---
# Clipping sites — inventory against "no budgets anywhere" (2026-09-25)

Read-only audit, HEAD `a6b8baaff` plus the uncommitted test edits in the shared
tree (no `src/`, `script/`, `resources/` edits pending). Method: `rg` over
`src script resources` for budgets, `subs`/`take`/`take-last`, top-N, elision
producers, print-length/level, windows; each hit read in place. No JVM was run;
caller counts are `rg` hit counts, not runtime reach.

Ruling (AGENTS.md "Apply presentation limits once", owner 2026-09-25): render
functions render less data; the floor value printer is the only clipper.

**What "the floor value printer" is today.** Two halves in `seon.render.value`:
(a) the projection-free core `bounded-text` / `floor` (`src/seon/render/value.clj:37-131`,
compiled `text-limits`), and (b) the profiled structural renderer
`prepare` → `value-node*` → `seon.print/fit` (`value.clj:473-653`,
`src/seon/print.cljc:1146-1345`), reached by `render-ai`/`render-ai-data`.
Both are KEEP as the floor; (b) itself still contains a budget (item 2).

Verdicts: **KEEP** = is the floor, or a storage/query-work bound or deadline;
**CONVERT** = replace with a render function that renders less; **DELETE** = a
second clipping path. Ranked by callers depending on the site.

## Ranked inventory

| # | Site | Clips | Callers | Verdict |
|---|------|-------|---------|---------|
| 1 | `value.clj:473-653` `value-node*`/`prepare`, `render-ai` (`value.clj:727`) | children at `:max-children`, depth at `:max-depth`, per-node `remaining` counter | every AI render: default render fallback `src/seon/render.clj:271,511`, `sci/eval.clj:3212`, `error.clj:499-500,925`, `repl.clj:475`, `cluster.clj:467`, `problems.clj:632`, `test/runner.clj:112`, `render/transcript.clj:443`, `ai.clj:116` | **KEEP** (floor) |
| 2 | `print.cljc:1294-1345` `fit` token search; `value.clj:630` `(volatile! token-budget)`; `value.clj:484,523` `:bound-by :token-budget` | re-fits children/depth/string limits until `tokens/estimate` of the emitted text is under `:seon.render.profile/token-budget` | same as 1 (prepare calls `print/fit`, `value.clj:633`) | **CONVERT inside the floor**: fixed structural limits only (children, depth, string chars); delete the search loop and the budget-named node counter |
| 3 | `src/seon/render.clj:63-92` `agent-render-profile` → `:seon.render.profile/token-budget` from `:seon.config.render.agent/token-budget` | supplies the budget for 2 and 9 | 15 hits / 7 files | **DELETE the key** once 2 and 9 land; profile keeps `max-depth`, `max-children`, `max-string-length` |
| 4 | `value.clj:65-131` `bounded-text`, `floor` | 400 chars/value, 8 items, depth 3, 12 ex-data entries, 8 links, 6 frames, 8192 chars | `flow.clj:229-230`, `cluster/store.clj:616-617`, `cluster/boot.clj:289,328,365,370,525-526,661`, `test.clj:212,1793`, `test/runner.clj:111,1729` | **KEEP** (floor). d8d80331d uses it correctly. Nit: `flow.clj:224` and `store.clj:613` also `prn` the raw `(ex-message panic)` beside the floor text, unbounded and duplicated; drop it, floor already prints it |
| 5 | `render/walk.clj:305` `shown (min width query-width)`; `walk.clj:212-230` names `:seon.render.profile/max-children` as bound | entity connections cut at the presentation limit inside the query walk | every neighbourhood render | **DELETE** the presentation half; keep `query-width` (`pull :limit`, `walk.clj:84-92`), a query-work bound. The floor clips children |
| 6 | `cluster/prompt.clj:232-299` `retained-count`, `dropped-elision`, `:seon.config.ai/prompt-token-budget` (`prompt.clj:366,458`) | drops oldest whole evaluation units to fit the prompt budget | every turn's prompt | **KEEP, owner confirm**: selects whole prompt units (AGENTS text still permits it) against the model's context window, clips no value. If "no budgets anywhere" includes this, the replacement is compaction, not a cut |
| 7 | `test/runner.clj:118-130` `message-text` | an `is` message longer than `token-budget` *characters* goes through `report-value` | every failing assertion | **DELETE** the budget comparison: always `report-value`; the floor clips, the whole message stays on the stored failure (`runner.clj:173`) |
| 8 | `test/runner.clj:91-116` `report-value` (aa00e58fc) | Throwable → `floor`, else `render-ai` | every failure report (`runner.clj:138-140,171-172`) | **KEEP** (routes to floor). Later: let the floor render a Throwable itself so callers stop branching (see "floor needs") |
| 9 | `cluster.clj:629-656` `observation-top` 10, `bounded-face` (300-char `subs`, top-10), `bounded-summary` (drops collections); used `cluster.clj:691-740` (20368330a, aea26027a) | MCP `runtime_status` faults, readiness, flow, replaced roots | 10 hits / 1 file; feeds `bin/seon status` (#10) | **DELETE** both helpers and `take observation-top`. The observation already crosses the prepl through `mcp-project` → `render.value/prepare` (floor). Keep the `select-keys` (that is rendering less: signature, operation, last-at, class, head message) and the counts |
| 10 | `script/seon/operator.clj:1435-1453` `status-top` 5, `top`, `short-message` 300 (19c2bbbc3); `status-summary` 1490 | terminal status: top 5 per family, messages over 300 chars replaced by length | 1 (`-main` 1516) | **CONVERT**: an AI render function for the status response that renders counts plus each open fault's signature/operation/class/last-at (no message bodies), emitted by `render.value`; drop `status-top` and `short-message` |
| 11 | `problems.clj:587-633` `ai-prose` (0039ff531) | family lines through `render-ai` under the profile | problems prose on every page/prompt | **KEEP** (floor). Correct shape: family-keyed data, floor clips with requery `(seon.problems/problems (seon.db/db) {})` |
| 12 | `render/transcript.clj:950-964` history `(take limit)` with `limit` = `:seon.config.render.agent/max-children`; `transcript.clj:672-675` `max-nodes` candidate count; `transcript.clj:148-165` `recent … (take limit)` | turns/entries cut at a render-profile number before rendering | transcript AI/HTML | **CONVERT**: the history render function declares which turns it shows (newest N as its own declared query, count named); stop reading a presentation dial at the query |
| 13 | `cluster.clj:555-610` `mcp-get-value` string `subs` window + `render.value/window` (`value.clj:297-352`) | page of a stored artifact, size `max-collection`/print width | 1 (`get_value`, `script/seon/dev/mcp.clj:891`); `window`'s only caller | **DELETE** as a second path: `get_value` = `prepare` of the drilled value at the requested offset (the floor's elision already names `next-offset`) |
| 14 | `render/web.clj:616-650` reverse connections `(take width sources)` at `max-collection` | HTML entity page, after the query read them all | generic entity HTML | **DELETE**: "never clip HTML"; the query already paid; show whole or use the existing pager |
| 15 | `render/ns.clj:414-424` `omission-value`, `included-count` (`ns.clj:561-584,640-697,724-732,836`) | nothing: every caller passes the full count | ns AI/HTML | **DELETE** dead parameter and elision |
| 16 | `render/ns.clj:121,216,513,558,614` `referenced-schema-cap` 40 | referenced schemas in a namespace render | ns render | **CONVERT**: render referenced schema keys (names) whole; definitions are one query away |
| 17 | `bootstrap.clj:371-395,518-560` `:seon.config.bootstrap/beyond-closure-token-budget` | opening intent candidates admitted while spent tokens ≤ budget | every opening | **CONVERT**: the opening renders each subject through its render function (form/name), no spend loop |
| 18 | `test.clj:1550-1559` `world-refusal` (4e12026e7) | offending value replaced by `(bounded-text … 400)` inside ex-data | test-world refusals | **CONVERT**: keep the native offending value in ex-data (memory); floor clips when printed (`test.clj:212`). Then delete `bounded-text`'s caller-limit arity (only tests use it) |
| 19 | `test/runner.clj:647-656` `bounded-drift` take 10; `runner.clj:980-988` reach-digest message "first 10" | names in error data and message text | drift / reach refusals | **CONVERT**: whole sets as data members, counts kept; message states counts; floor clips on render |
| 20 | `script/seon/dev/mcp.clj:27,281-286` `form-preview` 60 chars; `mcp.clj:40,654,680-683` `max-window-errors` 20 | bridge echo of the caller's form; branch error window | MCP bridge | **DELETE**: the errors travel through `mcp-valf` projection (floor); the form is the caller's own |
| 21 | `script/seon/dev/docstring.clj:342-357`, `markdown.clj:1203-1205` `max-length` "(truncated)"; `changed_test.clj:186-188,342,367` 180-char / take 4/6/20; `citations.clj:120` take 3 | dev-tool report text | dev scripts only | **DELETE** (low): print whole or data; not agent-facing |

### KEEP — storage bounds, query-work bounds, deadlines, explicit agent requests

- `sci/eval.clj:3502-3538` printed output refuses at `:max-bytes` (storage, exact text kept).
- `sci/admit.clj:275` strings admitted whole; admission caps are storage bounds; `script/seon/dev/mcp.clj:373-400` only translates admission's `:seon.sci.admit/elided` into an elision node.
- `error.clj:231-245,330-345,440-475` fault-fact inline evidence halved under the declared inline byte bound; whole value in the evidence blob. Storage, but budget-shaped (a halving search); revisit if facts move whole to blobs.
- `print.cljc:377-387,612-630` `::length`/`::level` and `sci/eval.clj:2398-2410,3713-3733`: the agent's own `*print-length*`/`*print-level*`, an explicit Clojure request; `prepare` nils them (`value.clj:634-635`).
- `my/fs.clj:40`, `fs/jvm.clj:276-525` read windows; `my/edit.clj` line windows; `web/search.clj:27` `max-results`: agent-requested bounds.
- `walk.clj:84-92` pull `:limit`, `ns.clj:39-49` read bounds, `schema.clj:4056` `shape-input-key-limit`, `schema/admission.clj:59` similar-key limit, `call_preparation.clj:1369,1401` take 2 (ambiguity), `ai/tokens.cljc:125` calibration window: query/algorithm work.
- `profile.clj:152-165,231,286` top-N by total/max: the report's meaning is a ranking (flag: owner may prefer the floor to clip one sorted list).
- `sci/kernel.clj:223` 12 arm-site frames, `oversight.clj:36` ping window, `effect.clj:663-700` time limits, `turn.clj:2642-2690` issue budgets: execution bounds, not presentation.
- `render/transcript.clj:574` HTML `<details>` summary first line (body whole), `transcript.clj:2383` last 3 expanded: HTML disclosure, nothing omitted.

## What the floor needs to be the only clipper

1. **Structural limits, no token search.** `print/fit` (`print.cljc:1316-1345`) emits the
   whole candidate and estimates tokens once per loop iteration, shrinking children, depth
   and strings together; each iteration is a full re-emit. Replace with one pass under fixed
   `max-children`, `max-depth`, `max-string-length`; the per-node `remaining` volatile
   (`value.clj:630`, decremented `value.clj:492`) goes or becomes a named node limit.
   `fit-text`'s default string limit is `tokens/estimate-chars budget` (`print.cljc:1321`):
   make `max-string-length` required.
2. **One ordering, done once.** `value-node*` selects `limit+1` children with `ordered-head`
   (`value.clj:425-445`, e0420d106): O(n log k) but still one `print/compare-values` pass over
   every entry of every visited map/set. `fit-node` then re-sorts map entries and set items by
   `emit-text` of each key/item (`print.cljc:1251-1261`) — a second sort that prints every
   retained key to text. Delete `fit-node`'s sort; the tree arrives ordered.
3. **Offsets in `prepare`.** Elisions carry `:seon.render.data/next-offset`, but `prepare`'s
   `display-value` (`value.clj:354-362`) always starts at 0, which is why `get_value` built its
   own window (#13). `prepare` must honour the cursor's offset for the selected path.
4. **`window`'s sort** (`value.clj:297-303`) orders maps and sets by `pr-str` of each key or
   whole element, O(n log n) with a full print per member; if kept at all it must use
   `compare-values` + `ordered-head` from the offset. Preferred: delete with #13.
5. **Throwables inside the structural renderer.** Callers branch Throwable → `floor`
   (`runner.clj:110-111`); `value-node*` should render a Throwable node through `link-lines`
   so every caller gets one entry point.
6. **One set of numbers.** `text-limits` (compiled, `value.clj:37-41`) and the profile's
   structural limits (config) are two independent limit sets for the same printer; state
   which governs which output, or derive one from the other.

## Summary

- Floor (KEEP): `render.value/floor`, `bounded-text`, `prepare`/`render-ai`, `problems/ai-prose`, `runner/report-value`, flow/store panic print.
- Budget inside the floor to convert: `print/fit` token search + `:token-budget` key and node counter (every AI render).
- DELETE (second paths): walk presentation limit, runner `message-text` budget, `bounded-face`/`bounded-summary`/top-10, `get_value` window + `render.value/window`, HTML reverse-connection cap, ns dead omission, dev-bridge form preview and 20-error window, dev-tool truncations.
- CONVERT (render less): `bin/seon status` summary, transcript history `max-children`/`max-nodes`, ns referenced-schema cap, opening intent budget, test-world 400-char offending, runner drift/reach "first 10".
- Owner question: `:seon.config.ai/prompt-token-budget` whole-unit selection (#6).

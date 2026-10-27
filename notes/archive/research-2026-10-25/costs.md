# Work model of the agent-view design (read-only analysis, 2026-10-24)

Symbols: A agents, E evals on one branch, T model turns of a run, U units on a page, V visible units, k attributes a commit changes, d datoms of a commit, S schema entries (660 forms, 69 with a declared render, 367 attributes), R reference-bearing strings on screen.
Measured on the live root (7888) with `seon.profile/measure!` (ticks = engine work, bytes = allocated), n <= 100 work, nothing transacted. Today's main has 126k datoms, 49 evals, 0 agents, 0 turns, so every per-E, per-A and per-T figure below is derived from a per-item measurement times n. Treat those derivations as estimates.

## 0. Findings that change the design

1. **The changed-attribute set is not on any root-side tx report for an agent branch.** An agent writes its own branch (`seon.agent/transact!` -> `dw/apply!`, run-ahead) and commits only at idle or a turn's end (`commit!`). Root's writer sees `:agent-commit {branch t}` (writer.clj:207), the render proc sees `{:type :commit :agent :t :txs}` (agent_proc.clj:213). The `main-commit` path (writer.clj:35, `:main {:t :ops :db}`) exists only for main. Two ways to get the attributes, both existing:
   - (a) `datahike.api/datoms-diff` (db.cljc:258, the fork's own): the datoms held by exactly one of two db values, "the stored subtrees both share are skipped", O(changed). `seon.reconcile/index idx from db` already uses it per watched attribute. Called on `:eavt` with `[]` it yields every changed datom, so k attrs = `(into #{} (map :a))`. Measured: diff of two equal values 5.2k ticks, 19 KB, 0 calls (O(1), shared root). Root holds both values already: `[:branches view :db]` is the branch db it last read, and the new one arrives with each `:branch-db` answer.
   - (b) the agent sums `(:tx-data r)` attrs in `transact!` and sends them on `:committed`: O(d) extra, plus a new message field.
   Recommend (a): no new message field, one mechanism (reconciler's), also catches writes that bypass `transact!`.
2. **A commit is O(turn), not O(eval).** Evals applied between commits are invisible to root until `commit!`. For a live "tiles update as it works" page the design needs the agent to commit per eval (a cost: one `dw/commit-pending!` plus an LMDB write each) or the page shows turn granularity. Decide this first; it sets the event rate.
3. **Reference resolution costs about 1.6M ticks and 5.4 MB per string, not the parse.** `seon.ref/parse` of a 150-char string: 25k ticks, 86 KB, 32 calls. `docs/cited` plus `seon.ref/kept`: 1.66M ticks, 1,464 calls, almost all fixed (a 10x longer string costs only +14%). The likely cause is `names-something?` slurping and parsing a `docs/x.md#anchor` page on every call (ref.clj:601) and the schema lookup `mr/schema contract/registry` for keywords. Cache by content: key = digest of the cited file for a path#anchor (results reused by input digest, AGENTS §5), the db's `:var/name` datoms for names. Not by string alone: resolution depends on db.
4. **Printing a large string is O(size) despite the budget**: `print-value` of a 1 MB string with budget 2000 = 23.6M ticks, 392 MB allocated (about 390 bytes per char). A 40,000-entry map: 4.1M ticks, 21.9 MB (20,000 entries: 3.1M), so maps stop near the budget but not strings. A 1 MB `:value` in a tool result is a 392 MB allocation spike per print on a 16 GB target. Fix at the producer (the printer takes a prefix of a string before tokenizing), then the handle `result/eN` reads the rest.

## 1. Per commit on a branch

| step | inputs | today | design |
|---|---|---|---|
| changed attrs | two db values | none; whole page re-walked | `datoms-diff` O(d) (finding 1) |
| unit lookup | k attrs, U units | n/a | O(k) with an inverted index attr -> unit ids, kept per view (a map built when a unit's providers are recorded). Not a scan of U |
| provider re-run | matching units m | n/a | m x provider cost; a `q` or `pull` reading attr set D re-runs only if D intersects the changed set |
| digest compare | m results | per unit `(= input old)` over all U units (structural `=` is O(size) when the values are equal but not identical) | m digests (hash of the result, O(result)) |
| patch | changed units c | `chunk-of` O(bytes of c units), serialized once per view (web.clj:268) | same, one chunk per commit per view, not per tab |

**What the render proc does per event today** (proc.clj `pass`, called on every `:state`, `:main`, `:branch` answer; an event always triggers `ask-state`):
- `system-value` + `render/ai sys` of root's whole context: runs every pass for every watcher, O(A) (frequencies over agents, `take-last` of each agent's `:evals`). Measured for 0 agents: 10k ticks; scales linearly with A.
- `page-value`/`render/page` for each watched view: `walk` does `d/pull '[*]` of the entity and one pull of every reverse unit with `{a ['*]}`. For an agent page that is all E evals (pull of one eval: 87k ticks, 506 KB), all T turns, all messages, plus per node `(d/datoms :eavt eid)` for `:seon.render/t`, then `cite` over every prose string of every node (finding 3), then `rows` (sort O(n log n)). **O(E + T) per commit per watched agent page, with a 1.6M-tick reference resolution per prose string.** Root's own page unit is `:seon.agent/_root` + `:seon.cluster.eval/_agent`: O(A + total evals). Measured root page for 50 nodes: 553k ticks, 2.3 MB walk plus 88k ticks `ui/page`; at E = 1000 evals about 20 x the pull = ~2M ticks plus cites, estimate 20-50M ticks per commit.
- `step-units`: only `shown` rows render, but the input compare runs over all units on the page. So today the page is **O(page) to compute, O(changed) to send and render**. The design's unit/attribute index makes compute O(changed) as well.
- `main-info` (when main's t moved): `task/tasks` 11.6M ticks, 64 MB; `reconcile/index db` (full, not the `idx from db` form) 94.9M ticks, 398 MB, ~700 tasks. That is O(tasks) per main commit (every merge, every run fact on main) and the largest step in the file. `reconcile/index idx from db` already exists (diff based); use it here. Same lesson as perf-2026-10-24 "Next wins" 1.
- `index-of` + `(not= idx (:index st))`: O(A) per pass; sent to the web proc whenever any agent's status changes.

## 2. Per model request

Today (`seon.context/render` + `messages`): each request runs `model-turns` (one `d/q` over the run's turns plus a `d/pull` of every earlier turn: T pulls), then `run-text`, which calls `render` again on `commit-as-db` of the first turn's basis, which recomputes `current-state` (the 115M tick, 512 MB "context render" in perf-2026-10-24, 8x regression noted there). `kept-turns` is cheap (`rseq`, `reduced` at the budget: O(kept)); `messages` is O(kept). So today O(T) pulls + 2 current-state renders per request, no reuse of the serialized prefix.

Design:
- **Prefix = O(new).** The message list is a fold over evals in tx order, each eval's messages a pure function of its record (assistant prose + call, tool message printed once). Keep `{eval-content-id -> messages}` in the agent process (content id is a hash already, `:seon.cluster.eval/id`). A request is `(into prefix (messages-of new-evals))` then the dynamic section: O(new + dynamic). The previous request's vector is the shared prefix, byte-identical by construction, which is what the provider's cache needs. Replace the `run-text` recompute: the first user message is stored once (the basis exists already), never re-rendered.
- **Dynamic section** O(V + R): hiccup of the visible units (V from the view's expanded set) + per-reference render. R is dominated by finding 3: 1.66M ticks per distinct cited string today; with content-keyed caching it is O(strings that changed since the last request), usually 0-2 (the new prose/thinking). Re-render only references whose unit digest changed; a task card or var card is a unit like any other, so it reuses the same cache as the page.
- History cut (`:history-budget`) and the "fallback if too big" need `select/estimate` per message: 2.5k ticks per 1 KB; keep the estimate with the cached messages so it is O(1) per eval.

## 3. Render lookup by schema key, branch override

- Global lookup is O(1) and cheap: `declared k face` 128 ticks, `schema-of` 67, `properties` 63. `contract/forms` is a baked map (660 keys); `by-identity` is a def (4 entries).
- Per-branch registry today: `seon.repl/branch-registry` (repl.clj:257) holds `{:key schema-changes :registry r}` in `@agent/state`, rebuilt only when `(program/schema-changes db)` changes; keying costs `schema-changes` = 177k ticks, 1.1 MB per eval (two passes over `:schema/key`/`:schema/source` datoms: O(S entries), measured at 1 call), recompile 5 ms (comment). It is per agent process and per eval, not per commit, and it is not where the render proc reads: the render proc (`render/render-of`) reads `contract/forms` only, i.e. main's compile-time forms. The design's override is therefore a new read path, not an existing one.
- A db query "render registered for k on branch b" (`[:find ?r :where [?e :schema/key k] [?e :schema/form ?f]]` over `:avet`/`:aevt`): one index seek O(log n) = about 100-300 ticks per lookup. The expensive part is decoding (`sedn/decode` of `:schema/form`) and Malli compile, which must be cached by form digest.
- O(changed) alternative: per view keep `{k -> render-sym}` for the overrides only; refresh it from the same `datoms-diff` of the commit when `:schema/key`/`:schema/form` is in the changed attrs (the unit-lookup mechanism of §1 applies, "schema entries" are just another attribute). Lookup order: view's override map, then `contract/forms`. Zero cost on commits that touch no schema.

## 4. Sidebar with N agents

Today: root's `:state` view holds one map per agent including a window of its evals; every event asks it again (O(A) to answer and to render `system-value`/`render/ai`), `index-of` is O(A) per pass, and `render/page` of root pulls every agent and every eval (O(A + E_total)) per pass. The page lists only `:shown` rows, but the walk does not.

| N | per commit today (estimate) | design with an index per view |
|---|---|---|
| 10 | ~1-2M ticks (negligible) | O(k) |
| 100 | ~10-20M | O(k) |
| 1,000 | ~100-200M (comparable to one context render) | O(k) |
| 10,000 | ~1-2G ticks and every agent commit rebuilds the whole index/page | O(k) |

Design: one tile unit per agent whose provider reads that agent's own attributes `[:seon.agent/status, id, task]` (a `pull` with a fixed spec => `pull-spec-attribute-dependencies`). A commit on agent X's branch changes X's attrs, not Y's. But the sidebar reads **main** (status facts that root writes), so main's commit must be diffed too: `datoms-diff` main_prev vs main_new gives the changed entity ids, and a unit keyed by agent eid is looked up by entity, not just attribute. So the unit index needs `attr -> units` AND `e -> units` for tiles whose reads are by entity. Without the entity key, an `:seon.agent/status` change re-runs all A tiles (O(A) per status change, A status changes per boot = O(A^2)). The visible-rows rule (`:shown`, `more`) keeps the render at O(V): a sidebar of 10,000 must be a windowed list, not 10,000 units; offscreen tiles hold no unit. The agent's own dynamic section shows "sidebar summaries": cap that to the agents it can message (those it has a thread with, or K most recent), not A.

## 5. Long run and large values

- 1,000 evals: page walk O(E) per commit today (86.9k ticks per eval pull => 87M ticks at E = 1000, plus the `:seon.render/t` datom read and cites per node), so ~100M+ ticks per commit, growing; the design's per-commit work does not depend on E (diff is O(d), only visible evals are units). Model request: today T pulls (T = turns, maybe 300 for 1000 evals); design O(new). The history budget still bounds the tokens, but with a cached prefix the cut should be made once per eval (a rolling start index), not recomputed from the tail each request; moving the cut start invalidates the provider's cache once per cut, so cut in large steps.
- `fold-evals` (identical form+answer folded) breaks "one handle per tool call" and the byte-identical prefix; the design drops it, fine, but do not keep it for the page.
- 1 MB map: printer stops near its budget (4.1M ticks, 21.9 MB for 40k entries). 1 MB string: 23.6M ticks, 392 MB (finding 4): fix at the printer. The value itself is held once in the agent process (`result/eN` binding), never in the store; what the store records is the printed text plus the handle, so store growth per eval is O(budget), not O(value). Make that explicit in the spec'd result map (`:printed` is budget-bounded, `:bytes` states the full size).
- Reference extraction on a 1 MB string: `parse` is linear (25k ticks per 150 chars => ~170M ticks per MB), so cite only strings with `prose?` schemas (already the rule in `render/texts`), never tool results, and skip strings over a size.

## Steps that are O(history) or O(all agents)

| step | where | now | O(changed) alternative |
|---|---|---|---|
| page walk of an agent | render/walk + page | O(E + T) per commit per watcher | per-unit providers + `datoms-diff` (above) |
| root page walk | `:seon.agent/_root`, `:seon.cluster.eval/_agent` | O(A + sum E) per pass | windowed agent list, per-agent tile units |
| `system-value`/`render/ai` | proc `pass` | O(A) per pass, every event | sections as units; re-render one when its inputs' digest changes |
| `main-info` | proc.clj | 95M ticks, 398 MB per main commit | `reconcile/index idx from db` (exists) |
| model request prefix | context/render | O(T) pulls + 2 current-state | cached message vector keyed by eval id |
| reference resolution | ref/kept | 1.66M ticks per string | cache by content digest of the cited file / var datoms |
| large string print | print-value | O(size), 392 B/char | prefix before tokenizing |
| schema keying | repl/branch-registry | 177k ticks per eval | `datoms-diff` on `:schema/key` or tx attrs |

Not measured: a non-trivial `datoms-diff` (live root has no agent branch to diff), the cost of `pull-spec-attribute-dependencies` in our Datahike fork (named in the design; not found in `src/` or the vendored `reference-code/datahike`, only the vendored `pull_api.cljc:107` declares it), per-turn pull cost (0 turns on main).

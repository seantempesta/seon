# Dig: tests read a reply's data, not its printed text

Task: `seon.test-support/tests-read-the-evaluations-recorded-data-not-printed-text`
(`resources/seon/tasks/seon.test-support.edn`). Read-only dig, pre-design. HEAD at
the start of the dig: 8dcabebd2. Line numbers below are dated evidence for this
dig only; the symbols are the references.

## Summary

- The harness reads the MCP JSON text back with `clojure.core/read-string`. Every
  value a test asserts (`:seon.repl/value`, `:seon.repl/error`, `:seon.repl/ms`,
  `:seon.repl/handle`) is therefore the value printer's text, parsed again.
- The data already exists in-process before the print: `seon.cluster.agent/evaluate-source!`
  (and `seon.test-support/evaluate`) return each evaluation with `:seon.sci.admit/value`
  (the live value), `:seon.eval/shown` (the printed text), `:seon.cluster.eval/error`,
  `:seon.eval/duration-ms`, `:seon.eval/allocated-bytes`, and the handle.
- `seon.mcp/eval-clj` is the one place that loses it: its request cost
  (`:seon.repl/request`) and the test step (`:seon.turn.loop/tests`) are printed into
  texts inside `eval-clj` and never returned as data. That is the one gap a data
  source has to close for `mcp-request` and `mcp-tests`.
- Recommended source: for value, error, handle and timing assertions, the
  evaluation map the one path already returns (in-process, no JSON, no re-read); for
  printed-text assertions, the shown string as a string (`:seon.eval/shown`), never
  re-read. The `reply` reader, its catch, and the `::cut`/`::cause`/`::tag`/`::form`
  shapes can then be deleted.
- Call sites: 92 qualified `reply`/`mcp-reply` uses in 47 test files (the task's
  "about 93"); 139 with the other readers (`mcp-replies` 10, `mcp-request` 20,
  `mcp-tests` 11, `mcp-text` 3, `mcp-texts` 3). Zero in `src/`.
- The catch in 130526a8b is a fence: it answers a read failure with a map that
  looks like a reply. The root cause is that the reader parses a lossy text at all.

## 1. Readers in `test/seon/test_support.clj`

| Symbol (line) | Takes | Returns | Notes |
|---|---|---|---|
| `reply` (903) | one `#:seon.repl{…}` text | the map read back; `#object` tags as `{::tag ::form}`; a read failure as `{::cut text ::cause msg}` | `read-string` with `*read-eval*` false; the catch added by 130526a8b |
| `mcp-parsed` (917, private) | parsed MCP result | `[[text reply]…]` for every content item | calls `reply` on every item, including the cost item and the test step |
| `mcp-evaluation-items` (922, private) | parsed MCP result | `[[text reply]…]` minus items whose reply has `:seon.turn.loop/tests` or `:seon.repl/request` | the filter is by data, after parsing |
| `mcp-replies` (930) | parsed result | vector of evaluation replies | `mapv second` of the above |
| `mcp-texts` (936) | parsed result | vector of evaluation texts | `mapv first` |
| `mcp-text` (942) | parsed result | the last evaluation's text | `peek` |
| `mcp-request` (949) | parsed result | `:seon.repl/request` of the item carrying it, or nil | a cost map, printed then read back |
| `mcp-reply` (955) | parsed result | `(reply (mcp-text result))` | the usual entry |
| `mcp-tests` (961) | parsed result | `:seon.turn.loop/tests` of the item carrying it, or nil | a printed value read back |
| `evaluate` (400) | an eval request map | the last form's evaluation map: `:seon.sci.admit/value`, `:seon.eval/shown`, `:seon.cluster.eval/error`, `:seon.repl/handle`, reads, output | in-process; no text read back; the one path (`seon.batch/evaluate-batch`) |
| `code-request` (375) | a world and text | a `seon.turn.batch/code-request` | used by `evaluate` |
| `with-running-agent` (1390), `reply-queue` (1448) | | | fixtures; not readers |

`reply` is documented as "a tagged literal the reader cannot construct (an `#object`)
stays data too". That comment exists for the printer's output, not for any value the
evaluation holds.

## 2. The path a test's form takes

1. Test: `(support/mcp-reply (mcp/call json))`, or the `answer` helper in
   `test/seon/request_cost_test.clj` and `test/seon/mcp_entrance_test.clj`, which parse
   the JSON with `json/read-str`.
2. `seon.mcp/call` (493): string in, string out. `decoded` (JSON to a map), then
   `eval-clj` (416) for the `eval_clj` tool, then `result` (145), which writes
   `{"content": [{"type":"text","text":…}], "isError":…}`.
3. `eval-clj` (416): `custody` (the agent's branch and context), then `sci-run` (298)
   in SCI mode or `jvm-run` in JVM mode. It appends one `printed` text: the request cost,
   `{:seon.repl/request …}`, built by `seon.batch/request-cost` and never returned.
4. `sci-run` (298) calls `seon.cluster.agent/evaluate-source!` (811), the one agent
   run. Its `answer` already holds `:seon.turn.loop/evaluated-sources` (each with
   `:seon.sci.eval/evaluation`), `:seon.turn.loop/tests`, `:seon.turn.loop/refusal`
   and `:seon.db/db`. This is the point where the data exists as Clojure values.
5. `observed` (235) reads the run's saved evaluations back through
   `seon.render.transcript/history-entries` and answers each text as
   `(or response shown error "")`, where `response` is the stored
   `:seon.render.history/printed-value`. The texts are what the agent's next context
   reads, which is the MCP contract.
6. Each evaluation's shown text was made by `seon.sci.eval/shown-result` (4384): it
   returns `:seon.sci.admit/value`, `:seon.eval/shown` (4462), `:seon.eval/shown-tokens`,
   and the value's size facts. The entity records `:seon.eval/shown` and a live value
   that stays in its world (not copied).
7. The REPL emission map (`:seon.repl/value`, `:seon.repl/error`, `:seon.repl/ms`,
   `:seon.repl/alloc-mb`, `:seon.repl/handle`, `:seon.repl/findings` …) is built by
   `seon.repl` (`by-key`, line 185). `:seon.repl/value` is `value-text` (121), which
   is `:seon.eval/shown`, spliced into the map text by `reply-map` (237). `:seon.repl/ms`
   is `:seon.eval/duration-ms` (line 175, 212), `:seon.repl/alloc-mb` is
   `:seon.eval/allocated-bytes` (217).
8. `printed` (130) writes each item text: `value-printer` through `eval/shown-result`
   for values, or `eval/shown-failure` for errors.
9. The test receives the JSON string; `json/read-str` gives the texts; `reply`
   (903) reads each text back as EDN.

Where the data already exists as Clojure values, before the JSON:

- In-process, same path: `evaluate-source!` answer (step 4) and `seon.test-support/evaluate`
  (400, which is `seon.batch/evaluate-batch` with `seon.turn/evaluate-row` and
  `seon.turn/show-row`, the same stages `sci-run` uses). Both give
  `:seon.sci.admit/value`, `:seon.eval/shown`, `:seon.cluster.eval/error`, `:seon.repl/handle`,
  and `:seon.eval/duration-ms`.
- Precedent in tests already reading the live value this way: `test/seon/repl_parity_test.clj`
  (lines 55 to 59), `test/seon/render_simplification_test.clj` (804),
  `test/seon/reset_edges_test.clj` (172), `test/seon/effect_test.clj` (847).
- Not in data anywhere: the request cost map (computed in `eval-clj`, printed only) and the
  test step's map (a printed value). These two need `eval-clj` to return data and
  `call` to print it.
- The cache: `seon.cache/lookup` (160) derives by `[kind content-key]` and is not a handle
  lookup. The handle is an id in the reply; the live value is on the evaluation map. A
  test that needs a handle's value reads `:seon.sci.admit/value` from the evaluation
  (no cache query).

Caveat on which value is "the reply": `shown-result` says a later stage can replace a
result after it was printed (a disposition refusal, a delivery, a settlement refusal).
`observed` answers the saved, settled text. So `:seon.sci.admit/value` is the value
before any replacement, and the saved `:seon.render.history/printed-value` is what the
agent sees. A test that asserts what the agent sees must read the settled record (the
evaluation entity pulled by its id, or `observed`'s data), not the in-process map.

## 3. Call sites (by what they assert)

Counts from `grep` over `test/`, `src/`, `script/` (qualified forms, `support/` and
`test-support/` aliases). The bare word `reply` in `src/` is unrelated prose.

- `reply` 53, `mcp-reply` 39, total 92. Matches the task's "about 93". Top files:
  `test/seon/repl_test.clj` 7, `test/seon/help_test.clj` 7, `test/seon/turn_continue_test.clj` 6,
  `test/seon/request_cost_test.clj` 6, `test/seon/eval_alloc_test.clj` 6,
  `test/seon/cluster/db_flow_test.clj` 6, `test/seon/cluster/turn_test.clj` 5,
  `test/seon/mcp_entrance_test.clj` 4, `test/seon/turn_test.clj` 3, `test/my/program_test.clj` 3.
- `mcp-request` 20 (`request_cost_test.clj` 13, `eval_alloc_test.clj` 3, `turn_test.clj` 2, `pass_cost_test.clj` 1, `node_cache_test.clj` 1):
  asserts cost keys (`:seon.cost/ms`, `:seon.cost/stages`), and stage presence.
- `mcp-tests` 11 (`cluster/turn_test.clj` 3, `my/test_check_test.clj` 3, `request_cost_test.clj` 2, `turn/batch_test.clj` 1, others): asserts
  the test step's `:seon.turn.loop/tests` value (red, green, reaching).
- `mcp-replies` 10 and `mcp-text` 3 and `mcp-texts` 3: asserts count and position of evaluation texts
  (`mcp_entrance_test.clj` line 274: `(count (support/mcp-texts result))`).
- Values and keys asserted on `reply` results (grep of `:seon.repl/*` keys read): `:seon.repl/value` 117 (most),
  `:seon.repl/handle` 34, `:seon.repl/error` 34, `:seon.repl/findings` 14, `:seon.repl/ms` 11,
  `:seon.repl/form` 10, `:seon.repl/result` 9, `:seon.repl/request` 6.
- Printed-text assertions (text read directly, not via `reply`): 35 uses of `mcp-text` and
  `reply-text`/`agent-text` (`repl_differential_p5_test.clj` 29, and the printer and render tests),
  plus about 4 `str/includes?`/`re-find` on those texts. These are the printer's own tests
  (the CLAUDE rule allows printed text only there and in declared goldens).

Typical shapes:
- `(is (= :ping (:seon.repl/value (support/mcp-reply ping))))`: a value, read from the printed text.
- `(:seon.repl/ms (support/mcp-reply ping) 0)`: a timing, read from the printed text.
- `(support/mcp-tests body)`: the test step's map, read from the printed text.
- `(support/mcp-request reply)`: the cost map, read from the printed text.

## 4. Earlier attempts and why read-string was chosen

- `20e7f2f8f` (2026-09-28, "transcript-test: responses asserted as the data they read back as"):
  introduced `seon.test-support/reply` with `read-string` on the reply text. Goal: assert keys
  (`:value`, `:out`, `:error`) instead of substrings of the rendered history. The data source was the
  MCP/printed text, because the transcript test went through text. The `*default-data-reader-fn*`
  was added for `#object`s, which the printer prints.
- `7737b8844` (2026-10-07): moved `with-running-agent`, `reply-queue` and provider fixtures into
  test-support; `support/evaluate` started returning the form's reads. The in-process evaluation map
  was already available and used by parity tests.
- `86f2dfbbf` (2026-10-08, "tests read an eval_clj reply's REPL item by its data"): added `mcp-parsed`,
  `mcp-text`, `mcp-reply`, `mcp-tests`. The fix was for position: the automatic test step adds a
  content item. The filter is by data (`:seon.turn.loop/tests`), but each item is still read back.
- `6098f0359`, `f0d96f147`, `98d836c57` (2026-10-07/08): `f0d96f147` gave `support/evaluate` a `:seon.test/rows`
  flag that returns the derive stage's row; `98d836c57` reverted it (the rows tests move to a run-fold rewrite).
  Data was reachable here and the flag was dropped, not the data source.
- `130526a8b` (2026-10-14): the catch below.

Why text was chosen: the first readers were written for the MCP path, which only has text, and the
in-process map was not yet the harness's source. No commit records a decision that printed text is
better than data. The printed text is a render of the data that the printer cuts.

`git log -S'defn reply'` names 7737b8844 and 20e7f2f8f; `-S'defn mcp-reply'` names 86f2dfbbf.

## 5. The catch in 130526a8b (quoted)

From `git show 130526a8b -- test/seon/test_support.clj`:

    (defn reply
      ...
      (binding [*read-eval* false
                *default-data-reader-fn* (fn [tag form] {::tag tag ::form form})]
    -    (read-string text)))
    +    (try (read-string text)
    +         (catch RuntimeException cause
    +           {::cut text ::cause (ex-message cause)}))))

Its message: "seon.test-support/reply read-string threw on a printer elision marker inside a map,
turning a real failure into a misleading reader error. It now answers {::cut text ::cause message}.
The MCP entrance crosses JSON, so printed text is the only data there; no caller depended on the
throw (0 callers converted)."

Assessment: the commit's own premise is the cause. "Printed text is the only data there" is true
only for the JSON bridge, not for the harness, which has the evaluation's data in process (section 2).
The catch answers a read failure with a reply-shaped map, so a test asserting `(= :x (:seon.repl/value r))`
fails for a different reason than the cut, and `::cut` is a shape no producer makes. CLAUDE.md
"NO GUARDS OR FENCES" applies.

## 6. Recommendation

Smallest data source per assertion kind:

| Assertion | Data source | Where it is produced |
|---|---|---|
| a value (`:seon.repl/value`) | `:seon.sci.admit/value` of the evaluation | `seon.sci.eval/shown-result` |
| a value as the agent sees it (saved) | `:seon.render.history/printed-value` of the settled entry (a string, not re-read) | `seon.render.transcript/history-entries` |
| an error (`:seon.repl/error`) | `:seon.cluster.eval/error` (flat error map) | evaluation record |
| a handle (`:seon.repl/handle`) | `:seon.repl/handle` on the evaluation map | `seon.repl` / `shown-result` |
| timings (`:seon.repl/ms`, `alloc-mb`) | `:seon.eval/duration-ms`, `:seon.eval/allocated-bytes` | evaluation record |
| test step (`mcp-tests`) | `:seon.turn.loop/tests` on the `evaluate-source!` answer | `seon.turn.loop` |
| request cost (`mcp-request`) | the cost map, returned as data by `seon.mcp/eval-clj` (new) | `seon.batch/request-cost` |
| printed text (printer tests) | `:seon.eval/shown` as a string | `shown-result` |

Changes, in one owner each:

1. `seon.mcp`: split `eval-clj` so the run's data (the `answer`, the cost map) is returned, and `call`
   prints it. Then a test reads `mcp-request` as data. `call`'s JSON stays the bridge contract.
2. `test/seon/test_support.clj`: replace `reply` and `mcp-reply` with readers over the data above.
   Remove `reply`'s `read-string`, its `*default-data-reader-fn*`, its catch, and the `::tag`/`::form`/`::cut`/`::cause` shapes.
   `mcp-parsed` no longer parses each item; `mcp-evaluation-items` becomes a filter on data.
3. Tests that assert printed text keep it as a string (`:seon.eval/shown`), read without `read-string`.
4. JSON-bridge tests (`mcp_entrance_test`, `request_cost_test`, `eval_alloc_test`) keep `json/read-str` on
   the protocol shape (count of items, `isError`), and move value assertions to data.
5. The task's proof: `seon.test-support-test/a-cut-reply-is-answered-as-data` is rewritten to assert that a
   cut value is visible on `:seon.eval/shown` (text as a string) and `:seon.sci.admit/value` (live map), with no reader.
   The task entry in `resources/seon/tasks/seon.test-support.edn` should name that test.

What can be deleted once the callers move:

- `seon.test-support/reply` and its catch, binding and `::cut`/`::cause`/`::tag`/`::form` shapes.
- `mcp-parsed`'s per-item read, `mcp-evaluation-items`' post-parse filter, `mcp-text`/`mcp-texts`
  as readers of text (kept only for the printer tests, as strings).
- The comment "a tagged literal the reader cannot construct (an `#object`) stays data too", once no test
  reads a printed `#object`.

Open question for the owner: `mcp-reply` and `mcp-request` are one entry (`eval_clj` answers a texts vector).
Moving cost to data means `eval-clj` returns a map, which changes the `eval-clj` schema
(`[:tuple [:vector :string] :boolean]`). That is a small platform change; it needs its own red test.

## Verification limits

- Read-only dig. I ran no tests and changed no source or tests. Counts come from `grep` on the working tree
  at HEAD 8dcabebd2 and may differ from a later commit.
- I did not confirm at the REPL that `support/evaluate` returns `:seon.sci.admit/value` on every path
  (`shown-result` sets it; `evaluate` merges `:seon.batch/shown-map`). Five tests already read it, so it is
  reached in practice.
- I did not check whether `:seon.render.history/printed-value` always equals `:seon.eval/shown`. The two are
  produced by different stages (`history-entries` versus `shown-result`); the settled-text difference is a
  claim to test before the design.
- The request cost is the only value I found with no data form outside `eval-clj`. If another exists, the table in section 6 changes.

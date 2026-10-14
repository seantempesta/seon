# Prototype: what the reply data is at the REPL (Sonnet)

Companion to `reply-data-dig.md` (6ff790bb7). Read-and-measure only; no source edits. HEAD at start b40b5881a.
Probes: MCP `eval_clj` in JVM mode as root (read-only: `seon.test-support/evaluate` settles nothing; `seon.cluster.agent/evaluate-source!`
called as agent `proto-sonnet`, so its run wrote to that agent's branch, not main), and SCI mode as agent `proto-sonnet` for a
throwaway `deftest` (`support/with-database` needs a test member handle: it refuses by name in plain JVM mode, so the
`support/evaluate` probe ran under `clojure.test/run-test-var` on the agent's branch, namespace `proto.probe`).
Timings are single warm-ish runs on a quiet default JVM; first `evaluate-source!` after idle took ~1.3 s (JIT/GC in the window), later ones 65 ms.

## Headline corrections to the dig

1. **`:seon.render.history/printed-value` is not `:seon.eval/shown`.** It is `seon.repl/response` of the emission: the whole
   `#:seon.repl{:result … :ms … :value 3}` map text. Never equal; for `(+ 1 2)`: shown `"3"`, printed-value
   `"#:seon.repl{:result result/e62e…, :ms 0, :alloc-mb 0.0, :value 3}"`. It also carries settle-time `:diagnostics` and `:findings`
   that `:seon.eval/shown` lacks (see Q2).
2. **The settled evaluation has no `:seon.sci.admit/value`.** On the `evaluate-source!` path, `(:seon.sci.eval/evaluation source)` in
   `:seon.turn.loop/evaluated-sources` lacks the key on every case I ran (ordinary, thrown, bad contract, render-failing value,
   `ns-unmap!` contract refusal). It holds `:seon.eval/shown`, `:seon.eval/value-class`, `:seon.eval/value-tokens`,
   `:seon.cluster.eval/error` (a string, not the error map), `:seon.eval/duration-ms`. The live value only exists on `support/evaluate`'s
   map (unsettled) and in the agent's context under the handle.
3. Request cost and the test step are as the dig says; the test step IS data on the answer (Q3/Q4).

## Q1. `:seon.sci.admit/value` on every path of `seon.test-support/evaluate`

Form (SCI, agent proto-sonnet, inside `(deftest probe1 (s/with-database (fn [connection] …)))`, `ctx` = `(s/fork-cluster-ctx connection)`,
request `{:seon.sci.eval/ctx ctx :seon.db/db (db/db connection) :seon.cluster.eval/ns [:seon.ns/name 'user] :seon.cluster.eval/source SRC
:seon.sci.admit/caps (config/result-caps (config/effective (db/db connection) "default")) :seon.sci.eval/time-limit-ms 2000}`),
then `clojure.test/run-test-var`. Total test 331 ms.

| Source | admit/value? | value | keys of returned map | ms |
|---|---|---|---|---|
| `(+ 1 2)` | yes | 3 | reads, ns, cpu-ns, allocated-bytes, duration-ms, elisions, shown, shown-tokens, value-class, value-tokens, print/options, admit/record, admit/value, ending-ns | 26.8 (cold) |
| `(throw (ex-info "boom" {:a 1}))` | yes | a flat `:seon.error` map (the error value, with chain/frames: very large) | reads, **`:seon.cluster.eval/error`**, ns, duration-ms, shown, print/options, admit/record, admit/value, ending-ns | 9.3 |
| `(nope.ns/f 1)` (unresolved symbol) | yes | flat `:seon.error` map | same as throw | 62.0 |
| `(+ 1` (unclosed) | yes | **1** (reader repairs the delimiter) | as ordinary | 4.7 |
| `)` (reader error, no form) | **no** | none | **`[]`: an empty map** (`:seon.batch/unread` also absent) | 2.1 |
| `(defn f [x] x)` | yes | the Var `#'user/f` | ordinary + `:seon.eval/renderer` | 6.9 |
| `(map inc (range 5))` | yes | `(1 2 3 4 5)` | ordinary | 5.2 |

- Answer: `support/evaluate` sets the key on every path that evaluates a form, errors included (it is the error map). It does NOT on
  an unreadable source (`)`): it returns `{}`, because `(peek entries)` is nil. A test asserting an unreadable source must read the
  empty map or the batch's `:seon.batch/unread`, not a value.
- Not set up in `support/evaluate` by design: refusal by entry policy and render failure never occur there, because it runs only the
  evaluate and show stages (`:seon.batch/settle`, `derive`, `facts`, `apply` are dissoc'd). I could not trigger a real entry-policy
  (`:gate`) refusal: I found no short form that reaches it (grep for "entry policy" in `src/seon/turn.clj`, `batch.clj`, `sci/eval.clj` finds no site). Closest observed
  settle-time refusal is a contract naming a missing schema: `(defn h {:malli/schema [:=> [:cat :nope/x] :int]} [a] 1)`.
  On the settled path it shows `:seon.cluster.eval/error` present on the evaluation and `:diagnostics` (`:seon.batch/derive-step`) in the
  printed reply, while `:seon.eval/shown` is still `{:seon.render.var/symbol proto.probe/h}`.
- Render failure: `{:seon.render/ai (fn [_] (throw (ex-info "render-boom" {})))}` through `evaluate-source!` returned
  no `:seon.cluster.eval/error`, `:seon.eval/shown` = `{:seon.render/ai #object[clojure.lang.AFunction/1 0x…]}` (the value printed
  as is) and a `:diagnostics [{… :seon.render.unknown/call …}]` in the printed reply. I did not run this via `support/evaluate`
  (not done for time); `shown-result`'s docstring (sci/eval) says printing is total and a renderer failure is a flat error beside the value.
- `evaluate-source!` (settled): keys of the answer for `(+ 1 2)` are `:seon.cost/counts :seon.db/db :seon.turn/id :seon.turn.loop/evaluated-sources :seon.turn.loop/stages`
  (plus `:seon.turn.loop/tests` when a test step ran, `:seon.turn.loop/refusal` on refusal). For `)` the answer IS the error map:
  keys `:seon.error/type … :seon.cluster.reply/no-forms :seon.agent/refused-source-agent`, `:seon.error/at` set, zero evaluated sources.
  Each evaluated source has keys `:seon.cluster.eval/ordinal`, `:seon.sci.eval/evaluation`, `:seon.turn.loop/admitted-form`; the
  evaluation lacks admit/value (headline 2).

## Q2. printed-value vs shown

Never equal as strings (headline 1). Observed relationship: printed-value contains shown (`shown-in-printed?` true on all 6 settled
cases) but adds the reply map's other keys. Differences found:
- bad-contract defn: printed has `:diagnostics [{:seon.error/layer :seon.batch/derive … :seon.error/operation seon.batch/derive-step …}]`; shown is only the Var render.
- `ns-unmap!` given a wrong first argument: second form's printed has `:error {…contract-error…}`; evaluation `:seon.cluster.eval/error` is a string while
  shown is the printed error map; first form of that run carries `:findings [{:seon.repl/finding :uncontracted …}]` in printed only.
- thrown form (`(throw (ex-info "boom" {}))`): shown `{:seon.error/message "boom", :seon.sci.eval/stacktrace [[proto.probe 1 1]]}`,
  printed `#:seon.repl{:result …, :ms 0, :error {:seon.error/message "boom" …}}` (value key absent, error key instead).
So the settled value the agent sees is the reply map; a later stage (derive/facts/findings) adds keys but I did not observe it replace
the value itself (the shown value text stayed a substring of the reply in all cases). Whether a delivery or settlement refusal replaces
the value text I could not reproduce: it needs a disposition/delivery form (no such form tried). Cannot show they are equal; they are structurally different.

## Q3. Where `eval-clj` computes `:seon.repl/request`, smallest split

`seon.mcp/eval-clj` (src/seon/mcp.clj, `defn- eval-clj`): `started` (`System/nanoTime`), `counters`/`shared`/`entrance`, then the cond over `sci-run`/`jvm-run`
returning `[texts failed? run-stages counts]`, then the last expression conjoins
`(printed {:seon.repl/request (-> (seon.batch/request-cost started (merge stages run-stages) counts) (with-diff main cluster-name) (with-shared counters shared entrance))} code nil cluster-name)`.
Measured live cost of that map on a trivial run: `:ms` 56 to 112, `:stages {:custody :read :evaluate :settle :print :unattributed}`.

Smallest split (not implemented):
- `sci-run`/`jvm-run` currently return the tuple `[texts failed? stages counts]` and drop the `answer`, hence the test step and tests are
  printed inside `sci-run` and lost as data. Add the answer's test step as a fifth member, e.g. return a map
  `{:texts [..] :failed? bool :stages {..} :counts {..} :seon.turn.loop/tests tests-or-nil}` (schema `:seon.mcp/run`).
- New public function `seon.mcp/eval-data` (the body of today's `eval-clj`, up to but not including the final `printed`) answering
  `{:seon.mcp/texts [string] :seon.mcp/failed? boolean :seon.repl/request <cost map> :seon.turn.loop/tests <map or nil>}`.
  `eval-clj` becomes `(let [d (eval-data req)] [(conj (:seon.mcp/texts d) (printed {:seon.repl/request (:seon.repl/request d)} code nil cluster-name)) (:seon.mcp/failed? d)])`
  so the JSON contract and `[:tuple [:vector :string] :boolean]` schema are unchanged. A test harness caller (`seon.test-support/mcp-request` and
  `mcp-tests`) calls `eval-data` instead of `call`+JSON, or `call` is given a data sibling; text still goes through the single printer for the JSON path.
- Cost: `with-diff`/`with-shared` run once either way; no extra work.
- Caveat: the `:print` stage is measured inside `sci-run`, before the cost item itself is printed, so the data value equals what is printed today (the printer only formats it).

## Q4. Three representative call sites, text vs data

Same input, JVM probe (`seon.repl/response` and `#'seon.repl/response-entries`; `response-entries` is private):

1. Value assertion, `test/seon/repl_test.clj` `printed-output-is-separate-from-the-value` (`support/reply (repl/response emission)`):
   - Today: `(select-keys (support/reply text) [:seon.repl/out :seon.repl/value])` -> `{:seon.repl/out "hi\n", :seon.repl/value nil}` (read-string of
     `"#:seon.repl{:out \"hi\\n\", :ms 1, :value nil}"`, 5.9 ms for read + response).
   - Data: the test constructs the emission itself; the data is already in hand: `(:seon.cluster.eval/output emission)` = `"hi\n"`, `(:seon.eval/shown emission)` = `"nil"`.
     `response-entries` returns ordered pairs of already printed strings `[[:seon.repl/out "\"hi\\n\""] [:seon.repl/ms "1"] [:seon.repl/value "nil"]]`.
     This test's subject is the printer's key-order/format, so it is a printer test; reading `:seon.eval/shown` as a string is the honest form.
2. Error assertion, same file, `an-error-response-carries-no-value`:
   - Today: `{:seon.repl/error "Divide by zero"}` after read-string.
   - Data: `(:seon.cluster.eval/error emission)` = `"Divide by zero"`; `response-entries` = `[[:seon.repl/ms "0"] [:seon.repl/error "\"Divide by zero\""]]`
     (no `:seon.repl/value` pair, which is the behavior the test asserts: assert key absence on the pairs vector, no read).
3. Test-step assertion, `test/seon/cluster/turn_test.clj` ~2875 (`(test-support/mcp-tests (eval-result …))`):
   - Today: reads the last content item as EDN and extracts `:seon.turn.loop/tests`.
   - Data: `(:seon.turn.loop/tests (agent/evaluate-source! …))` for `(in-ns 'proto.probe) (clojure.test/deftest zz (clojure.test/is true))` is
     `{:seon.turn.loop/reaching {:seon.test/passed? true, :seon.turn.loop/red []}}`, identical to what `mcp-tests` returns from the printed item.
     Answer keys `[:seon.cost/counts :seon.db/db :seon.turn/id :seon.turn.loop/evaluated-sources :seon.turn.loop/stages :seon.turn.loop/tests]`.
     Cost: 1260 ms for that evaluate-source! (the test world run; 1199 ms of it tests in the earlier measure `:tests {:ms 1199.0 :count 1}`).

Observation for the design: two of the three `reply` sites in `repl_test` read the output of `seon.repl/response` for a hand-built emission, with no evaluation
anywhere; the replacement there is the emission data itself, not the evaluation map. The evaluation-map route (dig section 6) fits sites that go through `mcp/call`.

## Not determined

- A real entry-policy (`:gate`) refusal and a delivery/settlement refusal that replaces the value after printing: no reproducing form found; Q2 is therefore "shown
  and printed-value differ always; no observed replacement of the value text".
- `support/evaluate` against a render-failing value (run only through `evaluate-source!`).
- Anything on `eval-clj`'s JVM mode `jvm-run` request cost beyond the trivial runs shown; costs above are single runs.

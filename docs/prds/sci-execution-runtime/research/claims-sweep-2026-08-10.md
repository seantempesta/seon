---
type: research
status: active
tags: [research, runtime]
---

# Independent live claims sweep — 2026-08-10

I read both required authorities end to end before probing:
[transfer-prompt-2026-08-08.md](../plan/transfer-prompt-2026-08-08.md) and
[overnight-report-2026-08-08.md](../plan/overnight-report-2026-08-08.md).
No lane report was accepted as evidence.

The selected live target was cluster `default`, pid 31570, start instant
`2026-08-08T12:24:15Z`, advertised at prepl port 60427. All default-cluster
operations were reads or process-local evaluation. The only database writes
were the authorized recovery falsifier in `v3-scratch`; that cluster was
stopped afterward. No paid model call was made.

## Verdicts

| Claim | Verdict | Deciding observation |
|---|---|---|
| 1. Production call preparation | PROVEN-LIVE | The pid-31570 context carried installed state, and a door call omitted a required database argument yet returned `"root"`. |
| 2. Boot recovery marks interrupted runs | PROVEN-LIVE | A dead-held scratch run crossed a real stop/start and came back closed, unheld, and durably stamped; `:avet` still returned zero. |
| 3. Control-only replies refuse loudly | PROVEN-LIVE | The loaded reply boundary returned `:seon.cluster.reply/no-forms` for the exact control-markup shape. |
| 4. Per-model token calibration | PROVEN-LIVE | `deepseek-v4-flash` fitted 3.1286 chars/token with a 1.485% band from eight live observations; the fallback had no band. |
| 5. Provider transport and durable truncation | REFUTED | Shared transport and partial preservation are live, but `:seon.ai.attempt/truncation` is absent from the installed schema and has zero facts. |
| 6. Eight unreadable root claims | PROVEN-LIVE | Status repeated eight warnings; all files were readable stale local formats, not corrupt or foreign claims. |

## 1. Production call preparation

**Verdict: PROVEN-LIVE.**

The falsifier had two arms: the live production context must carry the state
installed by `seon.sci.eval/cluster-ctx`, and a real door call must succeed only
if the hook inserts a declared argument. A fixture-built context would satisfy
neither arm.

### JVM probe — installed state on pid 31570

```clojure
(do
  (require '[seon.operator.runtime :as runtime]
           '[seon.call-preparation :as cp])
  (let [instance (get @runtime/running-instances "default")
        ctx (:seon.sci.eval/ctx instance)
        state (get ctx cp/carrier)]
    {:pid (get-in instance [:seon.boot/advertisement :seon.boot/pid])
     :ctx-present? (some? ctx)
     :call-preparation-state-present? (some? state)
     :state-class (some-> state class str)
     :state-keys (some-> state deref keys sort vec)}))
```

Raw return:

```clojure
{:pid 31570
 :ctx-present? true
 :call-preparation-state-present? true
 :state-class "class clojure.lang.Atom"
 :state-keys [:seon.call-preparation/plans
              :seon.call-preparation/snapshot]}
```

The loaded source also has the direct production chain:
`stand-cluster-runtime!` builds `bare-ctx` with `sci.eval/cluster-ctx`, and
`cluster-ctx` wraps the acquired context with `call-preparation/install`.

### Door probe — required database argument omitted

`seon.cluster.agent/owner-of` declares `[database namespace-name]`; it has no
one-argument implementation. Door mode received only the namespace:

```clojure
(seon.cluster.agent/owner-of 'my.agents.root)
```

Relevant door return:

```clojure
{:seon.dev.mcp/text "\"root\""
 :seon.sci.admit/record {:seon.eval/outcome :ok}}
```

Without call preparation this is a wrong-arity call. Returning `"root"` proves
the live fork received the supplied database value and queried it.

## 2. Boot recovery marks interrupted runs

**Verdict: PROVEN-LIVE.**

The read-only default query found no run-level interruption facts and its
instance reported zero recovered runs, so default alone could not prove the
transition:

```clojure
{:pid 31570
 :ready-recovered-runs 0
 :interrupted-run-count nil
 :interrupted-runs []}
```

I therefore used `v3-scratch`. The falsifier created an agent and a zero-receipt
run, opened and claimed the run under a deliberately dead process identity,
then crossed the real operator stop/start boundary:

```clojure
(do
  (require '[seon.operator.runtime :as runtime]
           '[seon.db :as db]
           '[seon.cluster.run :as run])
  (let [connection (:seon.boot/cluster-connection
                    (get @runtime/running-instances "v3-scratch"))
        now (java.util.Date.)
        run-id "v3-claims-sweep-recovery"]
    (db/transact! connection
                  [{:seon.cluster.agent/id "v3-claims-sweep-agent"}])
    (db/transact!
     connection
     (run/open-tx
      {:seon.cluster.run/id run-id
       :seon.cluster.run/agent
       [:seon.cluster.agent/id "v3-claims-sweep-agent"]
       :seon.cluster.run/opened-at now}))
    (db/transact!
     connection
     (run/claim-tx
      {:seon.cluster.run/id run-id
       :seon.cluster.run/process "definitely-dead-process"
       :seon.cluster.run/live-processes #{"definitely-dead-process"}
       :seon.cluster.run/now now}))))
```

The pre-restart full query returned:

```clojure
[["v3-claims-sweep-recovery" "definitely-dead-process"]]
```

After `bin/seon stop v3-scratch` and `bin/seon start v3-scratch`, the deciding
full-query probe returned:

```clojure
{:boot-recovered-runs 2
 :full-query
 [["v3-claims-sweep-recovery"
   "2026-08-10T19:43:20Z"
   "2026-08-10T19:43:20Z"]]
 :avet-count 0
 :holder-count 0}
```

The two instants are `:seon.cluster.run/interrupted-at` and
`:seon.cluster.run/closed-at`. Custody was retracted. The simultaneous
`:avet-count 0` reproduces the non-indexed-attribute trap against the same
database value; the full Datalog query is the evidence.

This crossed the production boot owner, not `run/recover-tx` called directly:
`stand-cluster-runtime!` invokes `recover-runs!`, which submits
`run/recover-tx`; `interrupt-stamps` asserts the run-level fact even when no
receipt exists. The scratch cluster was stopped after the query.

## 3. Control-token reply leak

**Verdict: PROVEN-LIVE.**

The falsifier called the loaded reply boundary directly with the observed
DeepSeek control-markup shape and no Clojure form:

```clojure
(do
  (require '[seon.cluster.reply :as reply])
  (reply/sources
   "<assistant1>\n<｜｜DSML｜｜AgentThoughts>thinking only</｜｜DSML｜｜AgentThoughts>"
   'my.agents.root))
```

Raw live return:

```clojure
{:seon.cluster.reply/no-forms true
 :seon.error/kind :seon.cluster.reply/no-forms
 :seon.error/message
 "The reply carried no Clojure forms — its whole text read as prose. Prose runs nothing and settles nothing; write the Clojure you want evaluated."
 :seon.error/data
 {:seon.cluster.reply/text
  "<assistant1>\n<｜｜DSML｜｜AgentThoughts>thinking only</｜｜DSML｜｜AgentThoughts>"}}
```

Source review agrees with the probe: `plan-sources` returns empty for pure
prose, and `sources` converts that empty result into the explicit marker-bearing
flat refusal. No provider call was involved.

## 4. Token calibration

**Verdict: PROVEN-LIVE.** The overnight report's `n=1` caveat is no longer the
current database state; the current sample count is eight.

The deciding JVM probe queried the attempt/capture join used by
`seon.cluster.prompt/model-calibration`, called that production function over
the same database value, and returned the literal shipped fallback:

```clojure
(let [model "deepseek-v4-flash"
      rows (seon.db/q
            '[:find ?attempt ?run ?characters ?usage-edn
              :in $ ?model
              :where
              [?attempt :seon.ai/model ?model]
              [?attempt :seon.ai.attempt/usage-edn ?usage-edn]
              [?attempt :seon.ai.attempt/run ?run]
              [?capture :seon.context.capture/run ?run]
              [?capture :seon.ai.tokens/characters ?characters]]
            database model)]
  {:calibration
   (seon.cluster.prompt/model-calibration database model)
   :observations rows
   :shipped seon.ai.tokens/shipped-calibration
   :shipped-has-relative-error?
   (contains? seon.ai.tokens/shipped-calibration
              :seon.ai.tokens/relative-error)})
```

Raw calibration and fallback:

```clojure
{:calibration
 {:seon.ai.tokens/chars-per-token 3.1285757081516494
  :seon.ai.tokens/basis :seon.ai.tokens/observed
  :seon.ai.tokens/sample-count 8
  :seon.ai.tokens/relative-error 0.01485228858666398}
 :shipped
 {:seon.ai.tokens/chars-per-token 4.0
  :seon.ai.tokens/basis :seon.ai.tokens/shipped-constant
  :seon.ai.tokens/sample-count 0}
 :shipped-has-relative-error? false}
```

The eight exact `[characters prompt_tokens]` observations were:

```clojure
[[57881 18600]
 [53468 16891]
 [63669 20545]
 [59666 19188]
 [57011 18270]
 [62549 19888]
 [61190 19660]
 [54525 17173]]
```

The facts do not carry a contamination classification, so this sweep does not
claim that every newer observation came from a semantically healthy prompt.
It proves the current fitted mechanism, current `n`, measured band, and honest
band-free fallback.

## 5. Provider transport and durable truncation

**Verdict: REFUTED.** Commit `8c6c2d90c` is live for transport and value
semantics, but its remaining durable-fact half is still absent.

### Live value/transport falsifier

The JVM probe read the loaded process client twice, then handed the loaded
private `streamed-completion` one valid SSE delta followed by
`IOException("closed", IOException("v3 reset"))`:

```clojure
{:shared-client-identical? true
 :client-class "jdk.internal.net.http.HttpClientFacade"
 :text "(+ 1 2)"
 :top-level-error nil
 :truncation-kind :seon.ai/stream-truncated
 :cause-chain ["java.io.IOException: closed"
               "java.io.IOException: v3 reset"]}
```

This proves that the loaded code holds one `HttpClient`, that a read failure
ends the line sequence rather than unwinding through the fold, and that
already-read text survives as an ordinary completion with explicit truncation.

### Durable fact falsifier

Against the live default database:

```clojure
{:installed?
 (contains? (:schema database) :seon.ai.attempt/truncation)
 :fact-count
 (or (seon.db/q
      '[:find (count ?v) .
        :where [_ :seon.ai.attempt/truncation ?v]]
      database)
     0)}
```

Raw return:

```clojure
{:installed? false :fact-count 0}
```

A source search found no declaration or settlement owner for the attribute.
The existing blocker now carries this current evidence:
[a-mid-stream-provider-disconnect-discards-the-whole-turn.md](../../../seon/issues/a-mid-stream-provider-disconnect-discards-the-whole-turn.md).

## 6. Eight unreadable operator root claims

**Verdict: PROVEN-LIVE.** The reported warning count and the stale-format cause
both hold. The records are readable EDN, not corruption.

`bin/seon status` printed exactly eight `record unreadable … The external claim
is invalid.` lines for these ids:

```text
1ff66f77-6d55-351a-a96a-37d657a5d485
248afabb-cf21-3ddc-bf11-a184cb660922
48988dac-d0d8-3aa8-b78f-f3d1526a3279
5387ef50-a343-3b68-8482-cd08a2bcecfa
abf5c438-fb25-36d7-9242-31ddacc034c7
d5428332-877b-3131-89f3-34ce6141ac20
d967ce8a-11aa-3cfe-95f8-f5ab3119664b
e78a5916-c83a-3e1a-bd06-05c77c26df11
```

The deciding JVM probe read each file with `clojure.edn/read-string`, applied
the loaded private `seon.operator.state/root-claim?`, and checked its managed
path without changing it:

| Record | EDN map | Current validator | Creator shape | Root exists |
|---|---:|---:|---|---:|
| `1ff66f77…` | yes | false | missing creator; destroyed record | yes |
| `248afabb…` | yes | false | `:seon.dev.process/*`, string instant | yes |
| `48988dac…` | yes | false | `:seon.dev.process/*`, string instant | no |
| `5387ef50…` | yes | false | `:seon.dev.process/*`, string instant | yes |
| `abf5c438…` | yes | false | `:seon.dev.process/*`, string instant | no |
| `d5428332…` | yes | false | `:seon.dev.process/*`, string instant | yes |
| `d967ce8a…` | yes | false | `:seon.dev.process/*`, string instant | yes |
| `e78a5916…` | yes | false | `:seon.dev.process/*`, string instant | no |

Seven records name this exact repository root but use the deleted creator keys
and a string start instant; current validation requires positive
`:seon.boot/pid` and an `inst?` `:seon.boot/start-instant`. The eighth is an
even older destroyed record with neither creator nor repository-root. All
managed paths are historical `tmp/` or `target/` test/proof roots under this
checkout: five remain and three are absent. They are stale local formats, not
foreign roots. None was deleted.

The existing issue was updated with this characterization:
[pre-rename-root-claims-are-unreadable-noise-on-every-status.md](../../../seon/issues/pre-rename-root-claims-are-unreadable-noise-on-every-status.md).

## Additional live blocker observed

Starting `v3-scratch` without prior config was sufficient to reach boot
recovery, but applying the shipped manifest afterward failed before
`seon.config/apply!`:

```text
var: seon.web.search/organic-results is not public
```

`config/default.edn` supplies that symbol while `src/seon/web/search.clj`
declares it with `defn-`. This is committed state and was recorded in the
existing web-config blocker:
[web-config-dials-ship-without-shipped-defaults.md](../../../seon/issues/web-config-dials-ship-without-shipped-defaults.md).

## Calibration

Five claims held live. The single consequential refutation is that provider
stream truncation still cannot become a durable attempt fact: recovery of
partial text exists only in the returned value today. The strongest positive
result is boot recovery itself—the exact zero-receipt class crossed a real
stop/start and remained queryable while the tempting `:avet` probe lied.

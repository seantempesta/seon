---
type: research
status: active
tags: [render, runtime, test]
---

# Runtime HTML — 2026-09-10

Bounded runtime-html lane. Read end to end: `AGENTS.md`, the assigned
runtime issue, `resources/seon/schemas/seon.runtime.edn`, its declared pair
in `src/seon/render/transcript.clj`, `src/seon/render/hiccup.clj`, the message
render functions in `src/seon/cluster/message.clj`, and
`src/seon/render/block.clj`. Also read the plan README, working edge, and
the Clojure, REPL, testing, and Datastar web UI skills. No subagents.

## Dependency ledger and archaeology

- Datahike pull follows explicit transaction and reverse refs in one
  immutable database value: `reference-code/datahike/src/datahike/pull_api.cljc:528–556`.
  Existing first-party idiom: the runtime pull and `history-run-selector`
  in `src/seon/render/transcript.clj`. No new stored timestamps or counts.
- Message HTML belongs to `src/seon/cluster/message.clj:382`; it owns
  sender/recipient/content and transaction-time display. Runtime composes
  this pair and uses `seon.render.route/path` for identity links.
- Hiccup child sequences compose inline and text escapes by default:
  `src/seon/render/hiccup.clj`, `append-node!`, `append-escaped!`, `->string`.
  `src/seon/render/block.clj:61` owns the stable surrounding surface id.
- Blob-backed replies use the supplied connection and existing verified
  content read, `src/seon/blob.clj:346`; the test stores its reply through
  `blob/put!` at line 293. This is the existing transport for durable bulky
  replies, not a new summary or copied first-line attribute.
- Java Date milliseconds provide duration arithmetic; SimpleDateFormat
  provides the server-local HTTP fallback. Each time element also carries
  its ISO datetime and Datastar `data-text` using browser-local
  `toLocaleTimeString()`. Elapsed state duration has a server-render-time
  fallback and browser `Date.now()` minute expression, so mounting cached
  HTML recalculates elapsed minutes without storing a duration or clock.
- Git archaeology: `1d47ebb7f` improved only the runtime AI selector;
  `5af34fc38` left HTML as turn headers. This change keeps the AI function
  byte-for-byte unchanged and replaces only runtime HTML presentation.

## Before

HTTP GET of the assigned default URL succeeded. Extracted runtime text:

```text
Runtime
Idle
Trigger: e06af466
Turns (5)
Turn 3ded5c0bc8a4
Opened
#inst "2026-09-10T20:37:46.400-00:00"
Trigger
e06af466
Evaluations
0
Reply
Recorded
```

The remaining four headers repeated that shape. This is a dated excerpt;
another lane reseeded Juniper while this lane worked.

## Implementation and boundaries

The runtime HTML pull acquires opened/closed transaction instants, trigger
messages, reverse evaluation refs, and replies together. The renderer
sorts turns newest first; labels state and elapsed duration; composes the
message pair with a descriptive message link; displays listen attributes
as chips; and shows opened time, duration/open, trigger, evaluation count,
and first reply line in a compact table. A missing runtime is an explicit
diagnostic, never an empty healthy runtime. An absent historical trigger
is labelled as unrecorded; only the newest turn may use the runtime's
latest trigger. Separately stored replies are read through their pulled
blob digest and the supplied connection, then show the same first line.
A caller without that connection gets an explicit “Reply stored separately”
message instead of a fabricated or empty reply.

The generic numeric reference dump is OUTSIDE this pair:
`src/seon/render/web.clj:1144–1155` collects ids;
`:1294–1313` uses them as both label and target in the surrounding block
header. That protected path was not edited. The issue remains open for
the render owner to replace that dump with identity links or nothing.

MCP status reported unknown health/Flow after 30 seconds. `(+ 1 1)`
returned 2, but returning pulled maps timed out at 10 and 20 seconds.
Returning the same pull through `pr-str` succeeded in 631 ms using the
supported MCP tool. This matches the existing MCP envelope issue; it is
not evidence that connection acquisition itself hangs. No replacement
transport, default stop, or default refork was used.

Verification results and the post-adoption HTTP text follow below.

## Gates

Commands (all paths owned by this lane; HEAD-plus-paths excludes concurrent
root-walk and fixture edits):

```sh
bin/test-fast --paths src/seon/render/transcript.clj test/seon/render/runtime_test.clj -- seon.render.runtime-test seon.render.web-debug-test
bin/test --paths src/seon/render/transcript.clj test/seon/render/runtime_test.clj docs/seon/issues/runtime-block-html-is-raw-ids-and-instants.md docs/prds/context-generation/research/runtime-html-landing-2026-09-10.md -- seon.render.runtime-test seon.render.web-debug-test
bin/test --paths src/seon/render/transcript.clj test/seon/render/runtime_test.clj docs/seon/issues/runtime-block-html-is-raw-ids-and-instants.md docs/prds/context-generation/research/runtime-html-landing-2026-09-10.md --platform
```

Fast: 10 tests / 65 assertions, zero failures or errors. The first attempt
caught this lane's missing required evaluation timestamp; corrected using
the canonical schema, not by weakening it. The isolated gate also includes
the subsequently added absent-runtime check: **10 tests / 70 assertions,
zero failures or errors**, coordinator-and-tests 39 seconds. Successful
gate root `run.yB5LXy` was removed by the runner. Snapshot basis:
`bfcda66cdbfa6098a65c6249e0f9b5e6cc489d92`.

Platform: **84 tests / 505 assertions, zero failures or errors**;
coordinator-and-tests 83 seconds. Successful root `run.nMRYkK` removed by
the runner. No `--all` or `--full` invocation.

The regression checks exact expected AI output before and after HTML
rendering, real message-pair composition, identity links, newest-first
ordering, evaluation counts, reply first lines, open and idle states,
minute duration arithmetic, and explicit absence diagnostics. Independent
source comparison against the snapshot basis: runtime AI function **1,073
bytes**, unchanged, SHA-256
`e3eaa22c308bcd1293b1cd0b8b4ee47ec8e99d28c330626800f6302153d6e940`.

First implementation commit: `237c4c572`. Final review added blob-backed
reply coverage and browser recalculation of cached elapsed time. The
blob-capable fast run passed **10 tests / 67 assertions**. Final isolated
gate: **10 tests / 71 assertions, zero failures or errors**; 38 seconds
coordinator-and-tests, successful root `run.tGiDeU` removed. Its snapshot
basis was `237c4c572100170c609a50ca73ddd792f8cb2d45` plus only this lane's paths.

Final platform rerun: **84 tests / 505 assertions, zero failures or errors**;
84 seconds coordinator-and-tests; successful root `run.Qt3a5w` removed.

Kondo emitted only existing unused namespace/import/binding and shadowed
binding warnings in the older transcript code; no new lint errors.

Reproducible source-byte check:

```python
import hashlib, subprocess
path = 'src/seon/render/transcript.clj'
basis = 'bfcda66cdbfa6098a65c6249e0f9b5e6cc489d92'
before = subprocess.check_output(['git', 'show', basis + ':' + path], text=True)
after = open(path).read()
def runtime_ai(source):
    start = source.index('(defn render-runtime-ai\n')
    return source[start:source.index('\n\n', start)].encode()
assert runtime_ai(before) == runtime_ai(after)
print(len(runtime_ai(after)), hashlib.sha256(runtime_ai(after)).hexdigest())
```

HTTP text extraction used Python's HTML parser, not a regex or browser
inference. CUA reported “No browser is available”; browser paint and
client-side timezone conversion are therefore unverified. The test and
HTTP evidence cover emitted Hiccup/HTML and server-local time text.

```sh
curl --max-time 20 -sS -w '%{http_code} %{time_total}\n' http://127.0.0.1:7994/ns/my.agents.juniper/debug -o tmp/runtime-html-after.html
python3 - <<'PY'
from html.parser import HTMLParser
class RuntimeText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0
    def handle_starttag(self, tag, attrs):
        if dict(attrs).get('class') == 'seon-runtime':
            self.depth = 1
        elif self.depth:
            self.depth += 1
    def handle_endtag(self, tag):
        if self.depth:
            self.depth -= 1
    def handle_data(self, text):
        if self.depth:
            print(text)
RuntimeText().feed(open('tmp/runtime-html-after.html').read())
PY
```

## Live default observation

After the final functions were hot-reloaded by development adoption, GET
returned **HTTP 200 in 1.696967 seconds**. The emitted runtime section has
five table rows, no `#inst` literals and no numeric database-id display.
The UUID and digest below are literal message content, retained through
the message pair. Juniper had no authored listens at this observation;
the canonical regression separately proves the chip with a real listen.

Full runtime text (adjacent text-node whitespace normalized):

```text
Runtime
Idle since 14:56:52 (5 min)
Woke on
Message from outside this cluster: The feed  failed with :seon.await/backstop-fired. Inspect error 9c931b6e-8128-4552-8290-abb5ca69e00d; the proc survived and no work was re-executed. Signature: b2bbe875e0db427eca8cd87f78ac785c480976a9d0c150d46ce9d44d045b259e.
Outside this cluster → Agent juniper
2026-09-10T20:56:48.362Z
The feed  failed with :seon.await/backstop-fired. Inspect error 9c931b6e-8128-4552-8290-abb5ca69e00d; the proc survived and no work was re-executed. Signature: b2bbe875e0db427eca8cd87f78ac785c480976a9d0c150d46ce9d44d045b259e.
This message has no agent sender to address a reply to.
Listening: No listened attributes.
Turns (5)
Opened | Duration | Trigger | Evaluations | Reply
14:56:50 | 2 s | Message from outside this cluster: The feed  failed with :seon.await/backstop-fired. Inspect error 9c931b6e-8128-4552-8290-abb5ca69e00d; the proc survived and no work was re-executed. Signature: b2bbe875e0db427eca8cd87f78ac785c480976a9d0c150d46ce9d44d045b259e. | 1 | ;; The orders are read: Ada has 60+55=115, Bea 100, Cy 40. Step juniper/read's done-when is met. I should mark it complete, then move to defining largest-customer.
14:56:49 | 0 ms | No recorded trigger | 5 | (help)
14:46:45 | 2 s | Message from root: Find the customer with the largest order total with a contracted function and a test, add an order of 40 for them, and tell me the customer and both totals. | 2 | ;; I should read the orders themselves before designing the query or function.
14:46:45 | 0 ms | No recorded trigger | 2 | (seon.agent/effective-settings)
14:46:44 | 0 ms | No recorded trigger | 9 | (help)
```

Two explicit `bin/seon init --dev default --changed
src/seon/render/transcript.clj` attempts completed loaded definitions, SCI
acquisition, and JVM instrumentation, then exited 1 with **“Source changed
during development adoption; the next edit must converge it.”** The first
also waited over 300 seconds behind other publication commands under the
operator lifecycle lock. No holder was stopped or modified. These are
hot-reloaded-Var and HTTP proofs, not a claim of sealed adoption. The last
observed adopted marker was `6aa31517-2ccf-50a2-a61a-9f0a9261b8e2`, while
current source was `6aa31aa0-c1f1-5101-abea-a9958c95a3c9`.

The convergence observation uses the supported MCP JVM tool, wrapping this
form in `pr-str` to avoid its known result-envelope problem:

```clojure
(let [instance (get @seon.operator.runtime/running-instances "default")
      database @(:seon.boot/cluster-connection instance)
      adopted (seon.db/q '[:find ?v .
                          :where [?e :seon.cluster/name "default"]
                          [?e :seon.source/commit-id ?v]] database)
      current (:seon.source/commit-id
               (seon.cluster.source/current (:seon.store/store instance)))]
  {:seon.probe/adopted adopted
   :seon.probe/current current
   :seon.probe/converged? (and (some? adopted) (= adopted current))})
```

---
type: research
status: measured; historical warm increase not attributable to one commit; no production fix
---

# Warm request regression, 2026-10-14

The request goes through `seon.mcp/eval-clj`, `seon.turn/settle-batch!`, the checked writer,
`seon.turn/test-step`, and the MCP reply printer. This investigation measures that path on default
and in committed throwaway JVMs, compares adjacent revisions, and keeps the request's own stage costs.

## Findings

- The first ten warm requests on default reproduced the reported slowdown: **50.9 ms**, range
  **43.4–99.7**, versus the historical 38.6 ms median. Ten later requests on the **same default JVM and
  same code** measured **39.5 ms**, range **35.9–48.2**. Nothing was restarted or reloaded by this lane.
- Repeated, freshly booted endpoint archives measured warm requests at **35.2 ms on e9bcf36d2** and
  **35.4 ms on de1f48c23** after the defn series. The first comparison was 39.9 versus 49.7 ms.
  The same-HEAD rerun moved more than that apparent between-revision regression. There is no stable
  binary good/bad predicate for the historical warm increase; naming a guilty commit would exceed the evidence.
- The reported defn test-step **137.9 ms** and print **7.0 ms** did not recur as typical costs:
  default's ten defns measured **45.2 [37.7–75.2] ms** and **2.4 [2.0–3.0] ms**, respectively.
- **86430115b does add time to the defn test-step stage:** its adjacent pair is
  **20.1 → 36.3 ms**. It removes more from settlement, **72.2 → 51.9 ms**, while the full request is
  **233.9 → 228.8 ms** and the outer measured MCP window is **243.5 → 238.6 ms**.
  This is a reproduced redistribution following a context-correctness fix, not an end-to-end regression.
- The writer and render-cache comparisons below do not establish the historical large slowdown.
  No production change is proposed for acceptance without a measured end-to-end win. Existing projection
  recomputation and test setup work remain concrete optimization candidates, with their limits stated below.

## Method and evidence

Authority: [post-restart sentinel](sentinel-after-restart.md), commit 31689e835;
[historical baseline](../2026-10-12/sentinel-baseline.md); `seon.sentinel/eval-probe` and
`seon.sentinel/summary`. [Raw evidence](warm-request-regression-data.edn) holds every measured request's
cost, CPU/allocation scopes and stages, the throwaway profile rankings, exact forms, revision ids,
boot phases, and process-identity cleanup results. [The driver](warm-request-regression.bb) reproduces
the archive experiment. No test or production source was changed.

There are **17 throwaway JVM runs, 610 measured throwaway requests, and 30 measured default requests**.
Each series has **n=10**, with no measured sample removed. Tables show **median [minimum–maximum]**, in ms.
Median here averages the middle two observations. The installed sentinel instead selects the lower-middle
total-time sample and takes that sample's stages; its original defn figure is one observation.
Here each stage has its own median, so medians of columns need not add up to the median total.
An absent stage is shown as absent, not claimed to have run in zero time.

Default probes used the installed sentinel in JVM mode as root. The inner SCI evaluations used only this
lane's agent branches, namespace `sentinel.scratch`. The warm agent discarded two arithmetic requests,
then recorded ten; the definition probe used ten fresh agent ids and exactly
`(defn sentinel-probe-inc [x] (inc x))`. The missing-contract finding is inherent in the original sentinel
probe, not a proposed production definition. The later warm confirmation used another own agent and
the same two-discard/ten-record procedure. All owned default branches were retired through
`seon.sentinel/retire-agents!`; no source was loaded into default. Database reads used the explicit
connection `(seon.cluster.boot/connection "default")`.

For historical revisions the sentinel file does not always exist. The dated driver therefore submits
the same inner `seon.mcp/eval-clj` call through `seon.profile/measure`, using the committed operator's
`seon.operator/launched-throwaway!` and `seon.operator/live-root-value!`, the archive/launch mechanism
used by `bin/seon check --at`. Each run boots one fresh disposable store, never a copy of default.
The probe order is two discarded arithmetic requests, ten warm requests, ten fresh defns, and ten more
arithmetic requests on the original warm agent (`warm-after`). Starting with the 73968333e run, the
driver also retires its ten defn branches and records ten further arithmetic requests (`warm-retired`).
No two of this lane's JVMs ran together. Each stopped using its actual `(pid, start-instant)` before
its owned disposable root was removed. Source archives and pinned dependencies were used throughout.

The comparison controls code and probe, not the host's scheduling or default's accumulated store history.
Other lanes' check JVMs were observed during the early experiments; later process observations showed only
default and this lane's JVM. Global CPU/profile counters include other threads, GC and JIT. Their costs
cannot be summed into a request's named stages. Repetition, not an assumed quiet machine, determines
which changes this report attributes. The later endpoint comparison is retained alongside the first,
not substituted for it.

Reproduce one archive run from the repository (run serially):

```sh
SEON_CHECKOUT=/Users/sean/src/seon bb --config /dev/null \
  --classpath data/source/de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6/script:data/source/de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6/src:data/source/de1f48c23ce0da42f5961bbc44a2dec5a03e5ab6/resources \
  docs/research/agent-platform/2026-10-14/warm-request-regression.bb \
  /Users/sean/src/seon e9bcf36d2 tmp/warm-request-regression/repeat.edn
```

The caller creates the output's parent directory. The driver needs the named committed operator archive
already prepared by `bin/seon`. This is a dated experiment using private operator seams, not a new
supported operator command. It preserves raw costs rather than depending on a historical summary's shape.

## Default reproduction

| Stage or total | Default warm, first | Default defn | Default warm, repeat |
|---|---:|---:|---:|
| total | 50.9 [43.4–99.7] | 287.7 [255.4–354.5] | 39.5 [35.9–48.2] |
| custody | 0.5 [0.4–0.8] | 96.9 [86.5–117.2] | 0.3 [0.2–2.7] |
| fork | 0.4 [0.3–1.3] | 0.3 [0.3–19.7] | 0.3 [0.3–0.5] |
| read | 0.1 [0.1–0.2] | 0.1 [0.1–0.2] | 0.1 [0.1–0.2] |
| evaluate | 1.5 [1.1–4.0] | 25.2 [21.5–34.6] | 1.0 [0.8–1.2] |
| derive | 0.0 [0.0–0.4] | 14.2 [12.4–42.5] | 0.0 [0.0–0.0] |
| facts | absent | 0.0 [0.0–0.0] | absent |
| apply | absent | 0.0 [0.0–0.0] | absent |
| settle | 35.0 [30.2–68.2] | 73.7 [66.4–91.7] | 29.3 [26.9–35.4] |
| test-step | absent | 45.2 [37.7–75.2] | absent |
| print | 1.2 [0.9–4.1] | 2.4 [2.0–3.0] | 0.8 [0.7–3.3] |
| unattributed | 10.6 [8.6–23.5] | 22.6 [20.7–32.1] | 6.8 [6.2–10.1] |

The first warm outlier includes GC/JIT activity; it remains in the range and raw data.
All inner probes are sub-second and report `:failed? false`. One outer five-defn collection request
took 1,568 ms and reported `:slow`: it is the sum of five measured requests, not an individual request.
The later ten-warm collection took 773.5 ms including its two discarded requests and recording.

Default stayed pid 10712, start instant 2026-10-14T02:58:28.434Z, source de1f48c23. Health was degraded
at entry. At exit there were no missing readiness layers and no open turns, but health still reported
untested hot functions, 2 agent-error signatures, 6 errored receipts, 1 failed test and 4 unowned namespaces.
Those are shared-system observations, not a claim that this lane diagnosed or cleared them. The initial
non-root JVM probe was refused because JVM mode requires root; the corrected probes used root with private
inner SCI agents. An exploratory cache-count form first dereferenced the Var only, then correctly read
the contained atom; neither mistake changed source or the runtime configuration.

## Revision search

The source-path history was inspected for `seon.turn`, `seon.db`, `seon.test`, `seon.sci.eval`,
`seon.mcp`, `seon.batch`, `seon.render`, and the value printer. Endpoint measurements were followed by
the explicitly suspected writer/render parent pairs, intermediate revisions bae97ee71, b182e514c,
73968333e, the ab8f218d4 parent pair, and then the 86430115b parent pair. The table preserves experiment
order. This is a measured interval search with adjacent-commit checks; a conventional wall-time binary
bisect was stopped when the same revision crossed the proposed threshold on repetition.

`76599e44a-parent` is 5b99122dd; `35cafc1dc-parent` is 5a08c13af;
`ab8f218d4-parent` is e3515306a; `86430115b-parent` is e737eb453.
Full ids are in the evidence. Datahike is pinned to c9e57c6fb65365d5ce92b59854fb02ea59054fe7 at both
endpoints. Konserve changes from e4334756413917305968333bf305e658c177765d to
26444d372bf97de219d55c06d5d23bd6f5cfc634 at af0a94471 (TieredStore binary-range delegation).
Each archive uses its own pin; this investigation did not rewrite dependency history to hold that change out.

### Arithmetic after the definition series

| Revision/run | Total | Settle | Test step | Print | Residual |
|---|---:|---:|---:|---:|---:|
| `e9bcf36d2` | 39.9 [37.2–62.6] | 25.5 [24.9–34.9] | 2.3 [2.0–3.3] | 1.0 [0.8–9.3] | 8.5 [7.2–17.2] |
| `de1f48c23` | 49.7 [42.2–52.3] | 32.8 [28.4–37.8] | absent | 1.1 [0.9–1.2] | 11.4 [9.4–18.1] |
| `76599e44a-parent` | 58.2 [45.6–63.2] | 41.1 [32.7–44.6] | absent | 1.2 [0.9–1.8] | 9.8 [9.0–15.2] |
| `76599e44a` | 57.0 [48.9–62.6] | 36.9 [31.6–43.2] | absent | 1.4 [1.2–6.6] | 13.1 [11.6–16.3] |
| `35cafc1dc-parent` | 46.7 [41.9–50.4] | 34.1 [32.0–37.7] | absent | 0.9 [0.7–1.1] | 7.9 [7.4–11.9] |
| `35cafc1dc` | 38.7 [36.0–43.5] | 26.0 [24.4–29.7] | absent | 0.9 [0.8–1.5] | 9.1 [8.1–13.1] |
| `bae97ee71` | 36.0 [33.3–50.1] | 24.3 [21.6–40.6] | absent | 0.8 [0.7–1.0] | 7.3 [6.6–20.6] |
| `73968333e` | 35.3 [33.4–36.7] | 22.7 [21.5–23.6] | absent | 0.8 [0.7–0.9] | 9.4 [6.6–10.6] |
| `b182e514c` | 35.7 [32.3–41.1] | 24.5 [22.7–29.2] | absent | 0.8 [0.7–1.0] | 7.1 [6.5–9.9] |
| `ab8f218d4-parent` | 37.1 [32.2–43.9] | 24.6 [22.9–33.0] | absent | 0.9 [0.7–2.1] | 7.6 [6.8–10.1] |
| `ab8f218d4` | 36.3 [32.2–40.5] | 24.9 [22.0–27.7] | absent | 0.9 [0.7–3.5] | 7.3 [6.6–11.8] |
| `de1f48c23-repeat` | 35.4 [32.4–48.0] | 25.0 [22.7–26.7] | absent | 0.8 [0.7–5.1] | 7.6 [6.9–19.6] |
| `86430115b-parent` | 35.5 [32.4–39.9] | 23.4 [22.4–26.3] | absent | 0.9 [0.7–5.3] | 6.9 [6.7–9.9] |
| `86430115b` | 35.4 [32.0–39.4] | 23.5 [22.3–29.5] | absent | 0.8 [0.7–4.1] | 7.2 [6.5–9.7] |
| `e9bcf36d2-repeat` | 35.2 [32.4–45.8] | 22.8 [21.4–26.1] | 2.1 [1.9–3.0] | 0.9 [0.7–1.2] | 7.1 [6.4–17.8] |
| `76599e44a-parent-repeat` | 54.5 [45.6–64.5] | 36.6 [32.5–43.3] | absent | 1.1 [0.9–4.8] | 11.1 [9.4–17.0] |
| `76599e44a-repeat` | 45.9 [38.4–47.5] | 30.6 [25.5–35.5] | absent | 1.1 [0.9–1.8] | 10.1 [8.1–13.6] |

### Fresh-agent definition

| Revision/run | Total | Settle | Test step | Print | Residual |
|---|---:|---:|---:|---:|---:|
| `e9bcf36d2` | 280.8 [251.2–348.7] | 75.7 [66.1–102.8] | 57.6 [45.1–75.3] | 2.7 [2.2–3.0] | 14.8 [12.6–17.7] |
| `de1f48c23` | 263.3 [237.0–386.9] | 58.7 [57.5–72.2] | 41.2 [31.3–102.8] | 2.9 [2.5–3.7] | 23.9 [18.9–45.2] |
| `76599e44a-parent` | 280.5 [245.2–325.3] | 64.4 [56.3–67.5] | 39.8 [31.9–85.3] | 2.8 [2.5–3.7] | 21.8 [20.6–28.5] |
| `76599e44a` | 314.4 [291.1–416.1] | 75.2 [66.9–86.1] | 49.5 [39.3–111.4] | 3.8 [2.7–5.4] | 28.5 [23.9–45.8] |
| `35cafc1dc-parent` | 268.6 [256.0–362.6] | 61.1 [58.9–81.8] | 38.7 [33.2–96.1] | 2.8 [2.3–4.6] | 23.7 [19.6–29.6] |
| `35cafc1dc` | 240.8 [220.8–332.5] | 55.0 [52.0–73.0] | 36.5 [28.7–90.7] | 2.8 [2.3–4.1] | 21.0 [18.9–26.4] |
| `bae97ee71` | 211.6 [207.7–260.4] | 64.3 [59.8–82.1] | 21.5 [20.0–33.4] | 2.0 [1.8–2.6] | 11.6 [10.5–13.3] |
| `73968333e` | 225.5 [221.6–293.7] | 67.7 [63.0–89.1] | 19.9 [19.0–35.2] | 2.5 [2.2–3.5] | 18.7 [16.9–27.9] |
| `b182e514c` | 219.9 [211.7–289.1] | 63.6 [57.8–85.6] | 20.2 [18.6–32.8] | 2.3 [2.0–3.8] | 18.1 [16.5–36.8] |
| `ab8f218d4-parent` | 230.6 [223.5–294.3] | 70.8 [62.0–91.1] | 20.4 [18.6–31.6] | 2.5 [2.0–3.3] | 19.6 [17.4–30.1] |
| `ab8f218d4` | 239.0 [233.1–304.0] | 73.6 [65.0–92.3] | 20.7 [19.7–35.3] | 2.6 [2.3–3.6] | 19.7 [18.7–28.8] |
| `de1f48c23-repeat` | 225.1 [214.4–337.3] | 49.5 [45.8–65.0] | 34.6 [28.2–93.4] | 2.6 [2.1–4.5] | 19.9 [16.4–27.0] |
| `86430115b-parent` | 233.9 [223.7–302.0] | 72.2 [62.2–97.4] | 20.1 [18.5–32.4] | 2.7 [2.1–3.6] | 18.5 [18.0–31.7] |
| `86430115b` | 228.8 [218.0–353.8] | 51.9 [49.1–77.0] | 36.3 [28.9–94.2] | 2.4 [2.0–3.6] | 18.3 [16.9–38.6] |
| `e9bcf36d2-repeat` | 224.6 [217.8–293.6] | 59.0 [55.2–81.4] | 39.0 [36.9–67.2] | 2.1 [1.9–2.4] | 12.0 [10.3–20.7] |
| `76599e44a-parent-repeat` | 287.0 [267.6–447.6] | 72.2 [57.9–98.5] | 44.3 [34.1–121.3] | 3.3 [2.5–4.7] | 23.5 [18.8–39.6] |
| `76599e44a-repeat` | 257.7 [251.9–383.6] | 59.4 [54.3–79.7] | 43.8 [31.1–108.2] | 3.5 [2.7–11.0] | 22.5 [21.1–34.1] |

### First arithmetic series, before definitions

These are the two-discard/ten-record samples closest to the original sentinel's warm procedure.
They are included to expose JVM warm-up and ordering, rather than selecting only the later, faster series.

| Revision/run | Total | Settle | Test step | Print | Residual |
|---|---:|---:|---:|---:|---:|
| `e9bcf36d2` | 46.9 [39.9–63.4] | 28.7 [25.2–39.6] | 3.5 [2.5–3.9] | 1.1 [0.9–3.2] | 10.4 [8.5–15.1] |
| `de1f48c23` | 50.6 [41.8–59.3] | 31.6 [28.1–37.1] | absent | 1.1 [1.0–2.8] | 12.1 [9.0–18.9] |
| `76599e44a-parent` | 44.9 [35.3–80.5] | 29.7 [24.2–40.7] | absent | 1.1 [0.8–15.3] | 11.1 [8.0–20.5] |
| `76599e44a` | 55.8 [46.5–66.3] | 36.8 [30.9–41.1] | absent | 1.6 [1.1–4.8] | 14.3 [9.5–20.1] |
| `35cafc1dc-parent` | 50.3 [39.2–60.1] | 31.4 [26.5–39.8] | absent | 1.1 [0.9–1.5] | 10.8 [8.7–16.2] |
| `35cafc1dc` | 44.3 [36.2–49.7] | 28.8 [24.2–30.9] | absent | 1.1 [0.9–4.3] | 11.3 [8.7–13.6] |
| `bae97ee71` | 38.7 [30.9–66.4] | 25.7 [20.7–44.1] | absent | 1.0 [0.8–3.3] | 9.6 [7.4–15.4] |
| `73968333e` | 37.2 [31.9–70.4] | 24.6 [21.6–49.5] | absent | 0.9 [0.8–2.9] | 9.1 [7.3–14.1] |
| `b182e514c` | 39.8 [32.4–55.5] | 26.7 [22.2–34.0] | absent | 1.0 [0.8–2.7] | 9.5 [7.4–14.9] |
| `ab8f218d4-parent` | 41.0 [32.2–68.7] | 26.7 [21.4–33.5] | absent | 1.2 [0.8–16.3] | 10.1 [7.6–15.4] |
| `ab8f218d4` | 47.6 [35.5–62.5] | 29.6 [24.0–38.2] | absent | 1.3 [0.8–4.6] | 11.2 [8.2–14.7] |
| `de1f48c23-repeat` | 37.8 [33.9–49.3] | 25.2 [22.5–30.9] | absent | 1.0 [0.8–1.2] | 9.4 [7.7–12.9] |
| `86430115b-parent` | 44.0 [33.3–60.6] | 28.0 [22.9–38.3] | absent | 1.2 [0.8–3.0] | 11.3 [7.6–14.5] |
| `86430115b` | 41.4 [31.5–54.8] | 27.0 [21.2–35.1] | absent | 1.1 [0.8–1.5] | 9.6 [7.4–16.7] |
| `e9bcf36d2-repeat` | 41.6 [35.0–56.0] | 26.1 [23.1–34.2] | 2.7 [2.1–3.3] | 0.9 [0.8–3.9] | 8.7 [6.8–14.3] |
| `76599e44a-parent-repeat` | 50.3 [45.4–75.3] | 31.3 [29.3–49.6] | absent | 1.6 [1.0–4.2] | 12.1 [10.4–17.8] |
| `76599e44a-repeat` | 54.8 [37.5–143.3] | 34.3 [24.7–116.8] | absent | 1.5 [0.9–3.1] | 14.4 [8.7–18.2] |

## Attribution at the owners

### Settlement and the writer

`seon.turn/settle-batch!` commits the evaluation and its facts synchronously through `seon.db`.
One arithmetic request needs one small recording transaction, not a walk of every program declaration.
The recorded warm transaction count is one throughout the repeated endpoint comparison.

76599e44a changes `seon.db/prepared-write`: `seon.db/digested-calls` wraps transaction functions,
`seon.db/digested-call` examines their emitted operations recursively, and
`seon.db/namespace-derivation` requests digest derivation for namespaces they actually write.
At the pinned Datahike seam `datahike.db.transaction/apply-db-op` for `:db.fn/call`, the function receives
the in-transaction database and its returned operations are processed by the transaction engine.
The additional Seon work is proportional to the emitted transaction operations and, when a namespace
changes, declarations in that namespace. The wrapper does not itself scan every stored namespace on an
ordinary arithmetic recording. Its correctness purpose is real: a namespace emitted by a transaction
function must invalidate the same declarations as a directly submitted namespace.

The first parent pair added 123 attributed warm calls (5,229 → 5,352) and about 0.08 MB of writer
allocation (6.37 → 6.45 MB), while the later warm request median fell 58.2 → 57.0 ms and settlement fell
41.1 → 36.9 ms. The first defn pair rose 280.5 → 314.4 ms across several stages, so this was investigated
with another parent/change pair rather than accepted as a wrapper verdict.

The repeated parent/change pair reverses the first defn increase: **287.0 → 257.7 ms**,
with test step **44.4 → 43.8 ms**, print **3.3 → 3.5 ms**, and settle **72.2 → 59.4 ms**.
Warm-after is **54.5 → 45.9 ms**, settle **36.6 → 30.6 ms**, residual **11.1 → 10.1 ms**.
The first warm series is instead 50.3 → 54.8 ms. These conflicting order-sensitive deltas do not
support charging the historical warm increase to 76599e44a; they do show why the first pair alone
was insufficient. The wrapper's extra traversal is visible, but its marginal latency is unresolved.

Do not revert digest derivation or exempt recording transactions. A next writer experiment can measure
emitted-operation traversal and Malli validation separately while keeping
`seon.turn.batch-test/a-retargeted-alias-leaves-no-stale-digest-for-the-test-world` green. No specific
traversal removal in this report has a proven end-to-end win.

### The test-step increase belongs to context acquisition

ab8f218d4 makes `seon.test/clean-request-handle` derive the candidate program rather than copy an
execution's potentially different base. Its pair stays near 20 ms for the test step (20.4 → 20.7).
86430115b subsequently stops `seon.sci.eval/install-evaluated-rows!` from transferring an agent's Var
roots into the shared execution base. It uses `seon.sci.eval/record-settled-rows!` to record the settled
basis in the evaluating context; the base acquires the program by content at its next use.

`seon.turn/test-step` calls `seon.cluster.agent/acquire-context!` after a changed definition, before
running proof/reach selection and judging results. That now acquires the changed program rather than
using the base that settlement had marked current. The adjacent pair locates the change in time:
test step +16.2 ms, settle −20.3 ms, full request −5.1 ms. Defn attributed calls increase
14,467.5 → 15,307.5 and reads 149 → 160; all-thread allocation is 250.8 → 255.4 MB. These are supporting
observations, not a replacement for the measured full request. Later HEAD still has a 34.6 ms test-step
median, consistent with this transition. It does not reproduce the historical 137.9 ms observation.

Proposed experiment: separate selection and result judgment over the held immutable database from SCI
acquisition needed to execute selected tests. A scratch definition with no assigned proof should pay for
its changed reach and evidence, and acquire a test execution only if one is needed. First measure whether
this removes work or merely defers it to the next request. `seon.test/run` already delays
`seon.test/clean-request-handle` until an admitted runnable group exists; adding another copy of that
mechanism there would solve nothing. Preserve the context-correctness regression
`seon.test.redefined-callee-test/a-branch-acquired-from-a-writers-base-runs-the-program-its-head-holds`.
This is a proposal at the earlier acquisition owner, not a tested patch or a request to undo 86430115b.
Related existing work: `seon.test/one-test-run-repeats-whole-program-setup`.

### Print and render

The actual render-invocation cache change is 35cafc1dc. Although 978f7122c has a render-cache title,
its actual diff changes request environment/config acquisition in `seon.batch` and `seon.turn.world`;
the attribution above follows diffs, not titles. `seon.render/call-cache-evidence` and
`seon.render/invocation-cache-key` derive content keys for a miss; their work includes the forms/contracts
population and the render's reach. That can be disproportionate to one small value and deserves its own
hit/miss experiment. This pair's defn print median is 2.8 → 2.8 ms (unrounded 2.85 → 2.75), not 2.5 → 7.0.

The reply's `:print` also does not time only `seon.render/render-call`: `seon.mcp/sci-run` measures
`seon.mcp/observed` and the reply's saved evaluation/history rendering after the agent has evaluated.
The initial shown value is produced within the evaluation pipeline. A cache-miss result for a different
context render cannot be charged directly to this stage. No print regression was reproduced here.

### Residual and whole-program recomputation

`seon.batch/request-cost` defines `:unattributed` as total wall time minus the named, rounded-down
stage intervals. It is not a function or an owner. 360e1f2ea stops running the test step on requests
with no program change and no stale proof; the old arithmetic test-step cost (about 2 ms in the repeated
baseline) becomes absent, while `seon.turn/step-due?` is still evaluated outside that timed stage.
The residual's membership therefore changes across the interval. Its historical +122% is not evidence
that a particular unnamed function doubled its work.

There is nevertheless real whole-program work in the profiles. In de1f48c23's repeated run, the last
warm-after window includes `seon.schema/range-differences` (10 calls, 10 self-ms),
`seon.schema/retained-population` (25 calls, 8 self-ms), and `seon.db/declaration-content-key`
(7 calls, 6 self-ms). They disappear from the top-20 self ranking after the ten defn branches are retired.
The same pattern occurs at earlier revisions, so it is not new at either named writer/render commit.
Warm-after → warm-retired wall time does **not** consistently improve: for repeated HEAD it is
35.4 → 38.4 ms. Global rankings overlap async work; they neither locate those milliseconds inside
settlement nor prove that retirement would fix request latency.

The producer is `seon.db/carried-projection` and its content derivation: durable projection caching
uses 24 entries for aliases of retained populations, and cache misses can call
`seon.db/declaration-content-key` over all declaration datoms, followed by schema population comparisons.
Arithmetic changes no declarations. The desired recomputation cost is therefore proportional to changed
projection inputs, with an unchanged-content reuse, rather than proportional to the whole program after
unrelated branch churn. Use Datahike's content/attribute revisions and the existing `seon.cache` owner;
do not add another cache, a writer stamp, or simply enlarge the hardcoded bound.

Existing tasks already cover this defect class:
`seon.schema/the-durable-projection-cache-evicts-a-commits-entry-across-tests-sharing-a-jvm`,
`seon.schema/schema-only-derivations-are-rederived-per-projection`, and
`seon.cache/one-weighted-cache-under-a-memory-cap`. This report supplies further workload evidence,
not a new duplicate task or a claim of proven cache eviction on each observed request.

The repeated endpoint allocation figures are also explicit: warm all-thread windows are
30.3 → 75.1 MB, writer allocation 6.21 → 6.43 MB, and outer entrance-thread allocations about
13.10 → 13.67 MiB. Whole-process allocation includes unrelated work and must not be presented as
request-owned allocation. Defn all-thread windows are 262.6 → 260.1 MB. The near-equal warm time
does not make the larger all-thread allocation disappear; the projection-work experiment above is the
next way to determine its owner. Raw GC/JIT and attribution labels are retained in the evidence.

## Proposed proof and acceptance

Before assigning either optimization, reproduce a red `seon.profile/measure` assertion through the
MCP entrance with fixed inputs. For projection reuse, vary retained branches while holding program
content and the requested arithmetic unchanged. For test acquisition, use a no-reaching-test definition,
a definition with a reaching test, and a callee redefinition that changes its caller's answer.
Measure the definition plus its following request so deferred work cannot masquerade as a gain.
Select a budget from the measured parent cost and the work being removed, not an invented millisecond target.
Run parent/change on repeated quiet JVMs and compare complete request wall time, allocation and output;
call counts only support the proof. Keep the existing digest and context-correctness tests.

No mechanism is changed here because none of the proposed reductions has passed that proof. The original
warm spike remains an observed but unassigned runtime event; the defn stage shift has a named commit
and a measured full-request comparison. This is not acceptance of a measured production regression.

## Validation and foreign boundary

`clj-kondo --lint src script test --cache false` completed in 9,196 ms with **0 errors, 1,664 warnings**.
The warnings are in the existing tree; this lane changes only these three research artifacts.
The commit gate names these existing vars that cover the measurement representation and stage semantics:

- `seon.sentinel-test/sentinel-summary-reduces-probes-to-baseline-figures`
- `seon.request-cost-test/a-reply-carries-its-request-cost`
- `seon.pass-cost-test/a-request-that-changed-nothing-runs-no-test-step`

No namespace-wide green is claimed. In particular, 86430115b's own historical evidence lists
`seon.pass-cost-test/jvm-render-settings-are-acquired-once-per-pass` among pre-existing reds; it is not
selected as proof here. The separate design lane's deliberately red
`seon.design-prototype-test/a-pathless-reference-section-is-designed` is also outside this slice.
Other lanes' modified dependency working trees, documents, task artifacts and sessions were left alone;
all historical runs used pinned committed archives. The default runtime remained on de1f48c23 while
repository HEAD advanced independently. Broad tiers and foreign failures remain root's responsibility.
All 17 throwaway processes confirmed exit; no disposable root remained. Their boot phases ranged
from 42,008 to 69,331 ms and are outside the measured request windows; exact preparation/readiness
and total cleanup durations are in the data, under the assignment's explicit throwaway-JVM authorization.
Net production/test source change: **0/0 lines**. No default restart, worktree, merge, push or provider run.

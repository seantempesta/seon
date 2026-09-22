---
type: landing
status: consumer implemented; four fixture/context errors; scan deletion fails its prerequisite
created: 2026-09-22
---

# Changed-identity wrapper adoption — README §4 step 1.3, wave C file half

The per-context prerequisite for deleting call-time projection selection **fails**.
The independent consumer cut narrows adoption to the supplied identities and reads
retained compiled contracts. It does not claim complete step 1.3 or the ≤2× call-cost
target. The scan stays under README §7's explicit failed-probe rule. No operation
published into, stopped, reset, or reloaded the repository's default (PID 51528).

## Ownership and decision boundary

Owned source: `src/seon/instrument.clj`,
`resources/seon/schemas/seon.instrument.edn`, `test/seon/instrument_test.clj`.
`src/seon/test/arm.clj` did not require a caller conversion. No excluded owner changed.
The two context contracts and missing copied-root installation are the existing
[development-adoption issue](../../../seon/issues/development-adoption-can-mix-host-and-sci-generations.md),
not a new defect class.

The already landed producer `dcc15e5b3` sends function lookup refs. It includes
reloaded interns and schema referrers; it unmaps deleted functions before arming.
The `39a337013` interpreted-row installation and fork re-arm are retained unchanged.
`install-jvm-root!` still copies a host root (`sci/eval.clj:819`); acquisition chooses
its interpreted closure from function definition digests (`:1854`), and the fork
re-arm selects only roots bearing `::instrument/interpreted-original` (`:2449`).
A schema-only contract difference does not supply that marker to a copied root.

The scratch probe calls the real `install-jvm-root!` over a real SCI context. With
host contract `[:=> [:cat [:map [:user/value :int]]] :int]` and receiving contract
`[:=> [:cat [:map [:user/value :string]]] :int]`, it observes:

```edn
{:copied-host-wrapper true
 :fork-rearm-eligible false
 :current-scan {:value 1}
 :retained-host-contract
 {:refused true
  :message "user/wrappers-context-probe refused argument 0 (0-based) at [:user/value]: expected an integer, got a string. Fix: Supply an integer at [:user/value]. Called from sci.lang.Var (lang.cljc:217)."}}
```

The retained-host arm is a diagnostic simulation using the existing
`compiled-wrapper`, not an edit to production or a claim of full two-cluster
integration. This negative direct-call case is sufficient to refuse deletion;
full direct/indirect/two-cluster proof remains owed by the excluded SCI seam.
The user was offered three concrete scopes: keep scan and land the independent
consumer (recommended); extend ownership to SCI installation/contract-difference
closure with its owner; or stop the slice pending that prerequisite. No permission
to edit SCI was inferred from elapsed time.

## Consumer behavior

* Present `:seon.instrument/changed-identities`: resolve only those loaded Vars,
  arm each retained declared contract, and unwrap selected retired/unarmable Vars.
  An empty set does no arming. Every other wrapper object remains unchanged.
* Absent member: preserve complete cold collection and unchanged-wrapper reuse.
* `compiled-wrapper` reads the compiled function Schema from the projection registry.
  Declarations not retained in a cold/test projection still compile their authored
  contract against that supplied projection; the caller API is unchanged.
* The extra packaged projection construction in `apply!` leaves. The host fallback
  captures the supplied arming projection. The per-call projection selector remains.
* Registered count is the selected armable count; instrumented count remains the
  complete JVM census. That census still visits loaded Vars; this is not a claim
  that every operation in `apply!` is proportional to the changed set.
* Registration compilation failures now name the function and underlying message.

## Dependency contract

Malli gitlink `606083c5c5b388e84d169c7080af33ed3ec242ae`:
`reference-code/malli/src/malli/instrument.clj:8` unwraps the original callable;
`:16–39` defines primitive exclusion/root wrapping;
`core.cljc:2201–2220` supplies per-arity input/output/guard enforcement and a
non-returning report boundary; `:3082` validates a function Schema.
Seon's `schema/projection-registry` (`schema.clj:454–503`) retains those compiled
Schemas by function symbol. The wrapper receives the projection, original callable,
contract identity and policy; registry acquisition happens at wrapper construction,
not through a new cache. SCI `copy-var*` (`core.cljc:112–136`) copies the host value,
which is why changing host selection requires the per-context caller conversion.

## Runtime and measurement boundary

Initial `bin/seon status` and MCP `runtime_status` observed PID 51528, all proc pings
replying, and 14 errored receipts. The lane made no default mutation.

The isolated checkout is `tmp/wrappers-wt`, detached at `b02dcde7a`, with only owned
source changes over that HEAD and the vendored dependencies linked. Leaf/core
measurement docstring edits occur only there. Shared HEAD later acquired
`seon.cluster.agent/release-context!` while the published fixture base lacked its
schema: the shared-tree path-limited runs refused during full arming. The isolated
checkout avoids that foreign boundary; no other lane's session or files were used.

Before code changes, `bin/seon --root tmp/wrappers-root reset --force` reached all
readiness layers in **121,767 ms**, PID 65717. MCP during publication correctly
reported unknown health; the prerequisite probe used the existing raw
`seon.operator/prepl-value!` transport at that point. PID 65717 later disappeared
without a terminal diagnostic in the retained process log. Its termination cause
was not established. Restart of the same root reached readiness in **6,248 ms**,
PID 69306; it was deliberately armed before the measurements (1,815 Vars, positive
`seon.id/valid?` wrapper metadata), then explicitly stopped and exit observed.

| Publication clock | Before: wrappers replaced | Before ms | After: wrappers replaced | After ms |
|---|---:|---:|---:|---:|
| Unchanged alignment | 0 | 505.569 | 0 | 4,093.257 |
| `my.note` docstring adoption | 3 | 5,515.045 | 3 | 5,778.534 |
| `seon.id` docstring adoption | 1,674 | 28,791.910 | 1,674 | 28,467.430 |

These time the actual `cluster/refresh-source!` development-adoption boundary,
including publication and namespace reload. They are not isolated arming times.
Wrapper replacement uses before/after root object identity, following the committed
`measure-publication-path-2026-09-22.sh` / `measure-publication-reloads-2026-09-22.clj`
clock's census. No default hook publication or browser paint is claimed.

The initial microprobe before explicit arming is discarded as unarmed evidence.
The positively armed baseline (five million-call samples after five warmups) is:

```edn
{:bare-ns [315.701916 314.720791 299.549542 321.144417 327.900958]
 :armed-ns [1891.650042 1875.497584 1748.530958 1805.859666 1902.921625]
 :heap-used-bytes 2385546408}
```

It overlapped other host test work, so absolute timings are observations, not a
controlled historical 1,157 ns reproduction. Post-change measurements:

```edn
{:bare-ns [292.602583 275.600709 222.805292 209.809542 189.576584]
 :armed-ns [1315.437625 1354.747459 1279.577292 1260.587291 1276.576416]
 :heap-used-bytes 2829166264}
```

Median before = 1,875.498 ns armed / 315.702 ns bare (5.94×); after =
1,279.577 ns / 222.805 ns (5.74×). No call-cost regression is observed, but the
≤2× target is **unmet** and these host-load-sensitive observations do not prove a
causal speedup. Heap-used samples are not retained-memory deltas: no GC-normalized
allocation claim is made. The surviving hot path is rest-argument allocation,
compilation-recursion check, argument/ambient projection selection, projection
wrapper-cache lookup, Malli arity/input/output/guard checks, and the exception/value
record-mode boundary. Per-item nanosecond attribution remains unmeasured.

After-change reset, using the same required command, reached all readiness layers
in **180,027 ms**, PID 74095, source commit `6ab2d9b9-e494-5d03-aec6-30ee132b2eb9`.
MCP JVM read (243 ms) confirmed the installed schema:
`[:set [:tuple [:= :seon.fn/sym] :qualified-symbol]]`. Boot itself had zero host
wrappers; explicit `apply!` then registered/instrumented **1,815** and positively
identified the `seon.id/valid?` wrapper before timing. This distinguishes process
readiness from actual arming. Both leaf and core adoptions ended with **1,815**
wrappers. An extra retained-contract probe returned
`{:retained-schema true :predicate-binding-count 0 :valid true}`; it intercepts the
existing binder while constructing a wrapper from a retained Schema.

After-core source commit: `6ab2da9e-cbc0-5db0-976d-b8f30deb9c1e`.
The counts before/after are equal because the old broad pass already avoided
replacing unchanged wrappers; the new behavior avoids inspecting unrelated
contracts, not Clojure's required namespace reload. No overall adoption speedup or
sub-second publication claim follows from these numbers.

## Focused regressions and limits

Three new tests cover the four requested invariants: exactly one replacement,
unchanged wrapper identity (also empty-delta identity), retired contract unwrapping,
and cold complete population equality. The obsolete assumption that packaged
projections lack predicate bindings is corrected in the existing cold regression.
Three policy/evidence tests that re-armed the whole JVM repeatedly now send only
their subject identities and retain their behavior assertions. Their measured body
times fell from 7,400.900 / 11,916.729 / 5,064.417 ms (failed five-second bounds)
to approximately 71 / 222 / 71 ms in the final run; no duration bound was raised.

Commands use only `bin/test-fast --paths src/seon/instrument.clj
resources/seon/schemas/seon.instrument.edn test/seon/instrument_test.clj --
seon.instrument-test`; no lane cold gate, platform gate, or suite ran.

The first run exposed an owned temporary private arity change under the fixture's
older retained contract; the signature was kept unchanged. Later shared-HEAD runs
failed at the foreign `release-context!` schema boundary. They are not passes.
Exact logs: `tmp/wrappers-test.log`, `tmp/wrappers-test-2.log`,
`tmp/wrappers-test-3.log`. The isolated launcher initially refused recording because its checkout lacked
`current-src`. After its exit and a no-holder check, the empty local recorder data
was moved aside and `tmp/wrappers-wt/data` linked to the real scratch root's `data`.
No manifest, readiness, or result was fabricated; the installed recorder used the
scratch's actual live prepl and published store.

Final isolated run **`6f13dfc53706`**: **50 executed, 0 reused, 301 assertions,
0 failures, 4 errors**. Log: `tmp/wrappers-wt/tmp/wrappers-test-final.log` (copied
outside the disposable checkout before cleanup). Entering arming:
**1,714 registered/instrumented, 1,686 program-armable**, 107 program namespaces.
All three new regressions passed, covering the four requested invariants; the cold
regression independently compares actual wrapper membership and both counts with
`instrument/armable`, the same population owner `test.arm/arm-contracts!` uses.
This is **not a green namespace run**. The four errors occur before their wanted
assertions at these excluded producer boundaries:

* `a-sci-only-arity-miss-names-its-program-graph-arglists` and
  `host-diagnostics-use-the-loaded-vars-arglists`: the canonical
  `test-support/program-fn-row` three-argument path calls `fn/source-rows` with a
  namespace row missing required `:seon.program/definition-digest` (`test_support.clj:1026`).
* `a-sovereign-sci-fork-acquires-its-own-recorder` and
  `sci-installed-contracts-enforce-declared-schemas-and-refusals-in-both-dials`:
  context acquisition refuses “The JVM's loaded source commit is unknown.”
  (`sci/eval.clj:858`).

The first two require the canonical fixture producer; the latter two require the
loaded-source/context fixture seam. No fixture guard was weakened, no fake source
commit supplied, and no excluded owner changed. Integration/platform proof remains
the orchestrator's responsibility.

## Executable measurement forms

The following scripts are retained here as committed executable evidence; extract
each code block to its named `tmp/` path. They use the operator's existing transport,
and never invoke the repository default. Scratch roots must be absolute for adoption.

### `tmp/wrappers-context-probe.clj`

```clojure
(let [projection (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))
      sym 'user/wrappers-context-probe
      contract-a [:=> [:cat [:map [:user/value :int]]] :int]
      contract-b [:=> [:cat [:map [:user/value :string]]] :int]
      policy {:seon.config/on-core-error :panic
              :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes seon.config/defaults)}
      caps (seon.config/result-caps seon.config/defaults)
      p1 (seon.schema/projection-with-function-contract projection sym contract-a {:seon.schema.admission/source :core})
      p2 (seon.schema/projection-with-function-contract projection sym contract-b {:seon.schema.admission/source :core})
      candidate (intern 'user 'wrappers-context-probe (fn [_] 1))
      ctx (assoc (sci.core/init {}) :seon.sci.kernel/installed-functions (atom #{}))
      call (fn [] (sci.core/eval-string* ctx "(user/wrappers-context-probe {:user/value \"branch\"})"))
      observe (fn [f] (try {:value (f)} (catch Throwable e {:refused true :message (ex-message e)})))]
  (try
    (alter-meta! candidate assoc :malli/schema contract-a)
    (#'seon.instrument/arm-var! candidate contract-a p1 p1 caps policy)
    (#'seon.sci.eval/install-jvm-root! ctx sym)
    (let [installed @(sci.core/resolve ctx sym)
          scanned-host @candidate
          baseline (seon.schema/call-with-projection p2 #(observe call))
          fixed (#'seon.instrument/compiled-wrapper p1 sym contract-a (malli.instrument/-f->original @candidate) caps policy)]
      (alter-var-root candidate (constantly fixed))
      (#'seon.sci.eval/install-jvm-root! ctx sym)
      {:boundary :scratch-jvm-install-jvm-root
       :copied-host-wrapper (identical? installed scanned-host)
       :fork-rearm-eligible (boolean (:seon.instrument/interpreted-original (meta installed)))
       :current-scan baseline
       :retained-host-contract (seon.schema/call-with-projection p2 #(observe call))
       :context-contract (malli.core/form (malli.registry/schema (:seon.schema.projection/registry p2) sym))})
    (finally (ns-unmap 'user 'wrappers-context-probe))))
```

### `tmp/wrappers-run-probe.clj`

```clojure
(require '[clojure.edn :as edn] '[seon.operator :as operator])
(let [[root form-path] *command-line-args*
      endpoint (edn/read-string (slurp (str root "/data/clusters/default/prepl.edn")))]
  (prn (operator/prepl-value! endpoint (slurp form-path) 10000)))
```

### `tmp/wrappers-measure.clj`

```clojure
(require '[clojure.edn :as edn] '[seon.operator :as operator])
(let [[root mode path] *command-line-args*
      endpoint (edn/read-string (slurp (str root "/data/clusters/default/prepl.edn")))
      form (case mode
             "adopt"
             `(let [before# (:seon.instrument/roots (seon.instrument/state))
                    start# (System/nanoTime)
                    outcome# (seon.cluster/refresh-source! ~root [~path] "default")]
                {:elapsed-ms (/ (- (System/nanoTime) start#) 1e6)
                 :wrappers-before (count before#)
                 :wrappers-after (count (seon.instrument/instrumented))
                 :wrappers-replaced (count (filter (fn [[v# f#]] (not (identical? f# @v#))) before#))
                 :result outcome#})
             "micro"
             '(let [wrapped seon.id/valid?
                    bare (malli.instrument/-f->original wrapped)
                    value "0123456789ab"
                    n 1000000
                    run (fn [f]
                          (let [start (System/nanoTime)]
                            (dotimes [_ n] (f 12 value))
                            (/ (double (- (System/nanoTime) start)) n)))]
                (dotimes [_ 5] (run bare) (run wrapped))
                {:bare-ns (mapv (fn [_] (run bare)) (range 5))
                 :armed-ns (mapv (fn [_] (run wrapped)) (range 5))
                 :heap-used-bytes (- (.totalMemory (Runtime/getRuntime)) (.freeMemory (Runtime/getRuntime)))}) )]
  (prn (operator/prepl-value! endpoint (pr-str form) 60000)))
```

### `tmp/wrappers-retained-probe.clj`

```clojure
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.schema/projection-from-database db)
      symbol 'seon.id/valid?
      retained (malli.registry/schema (:seon.schema.projection/registry projection) symbol)
      compiled-wrapper (malli.instrument/-f->original @#'seon.instrument/compiled-wrapper)
      binder @#'seon.instrument/bind-contract-predicates
      calls (atom 0)
      original (malli.instrument/-f->original seon.id/valid?)
      caps (seon.config/result-caps seon.config/defaults)
      policy {:seon.config/on-core-error :panic
              :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes seon.config/defaults)}]
  (with-redefs-fn {#'seon.instrument/bind-contract-predicates (fn [& args] (swap! calls inc) (apply binder args))}
    (fn []
      (let [wrapped (compiled-wrapper projection symbol (:malli/schema (meta #'seon.id/valid?)) original caps policy)]
        {:retained-schema (malli.core/schema? retained)
         :predicate-binding-count @calls :valid (wrapped 12 "0123456789ab")}))))
```

## Control, final load, sizes and cleanup

The unchanged-HEAD control used the same isolated checkout and installed launcher:
`bin/test-fast --paths src/seon/test/arm.clj -- seon.instrument-test`.
That path was unchanged: the launcher printed **no snapshot differences from
`b02dcde7a`**. Run **`019d975d572b`** recorded **47 executed, 0 reused,
289 assertions, 3 failures, 4 errors**. All four errors are the same named
fixture/context errors in the candidate. The failures were two whole-JVM policy
re-arm duration overruns and the obsolete absent-predicate-binding assertion.
Thus the remaining four errors predate this consumer cut; they are still errors,
not waived passes. The candidate adds three passing regression tests and removes
those three baseline failures without increasing any bound.

Final shared-checkout load, with only the ordinary `require` path and no database
publication, exited **0**:

```sh
clojure -M -e "(require 'seon.instrument) (println :wrappers-load-ok)"
```

`git diff --check` passed. Final source sizes: `instrument.clj` **980 → 1,005**
(+45/−20); schema **135 → 139** (+4/−0); test **1,463 → 1,528** (+91/−26).
This is the changed-identity consumer feature, not the larger A1 deletion target.
The adoption timings precede the final docstring-only clarification in the owner;
final focused tests and the shared load read the final source bytes. The from-zero
schema bytes match the final resource exactly.

Raw envelopes, logs, scripts and the exact owned patch are retained under
`tmp/orchestrator/wrappers-evidence/`. In particular, `wrappers-test-final.log`,
`wrappers-test-control.log`, `scratch-process.log`, the before/after `.edn` clocks,
`wrappers-context-probe.edn`, `wrappers-retained-probe.edn`, and `shared-load.log`
survive scratch deletion. The executable forms above are committed with this note.

Both owned operator stop requests completed; their exact JVM identities exited.
All owned test/probe sessions exited, including the accidentally queued launcher,
which was terminated only after matching PID 73693 and its start instant/command.
`lsof +D` found no holders of the scratch checkout. Symlinks were unlinked without
following their targets, then the scratch root and isolated checkout were removed.
Owned failed test snapshots were already removed by the launcher sweep; the queued
snapshot's TERM cleanup reported its removal. No other lane's process, source, or
session was operated. The final MCP health read still finds **default PID 51528**,
all proc pings replying and the same 14 errored receipts. Hook publication remains
configured off (`.claude/seon-hook.edn`, `:current-source {:enabled false}`).

**Remaining boundary:** complete step 1.3 still requires the excluded SCI owner's
per-context copied-root installation and contract-difference caller closure, then
the full two-context probe, scan deletion and ≤2× call-cost proof. The four existing
fixture/context errors and orchestrator-owned integration/platform proof also
remain. This landing does not claim those requirements complete.

Implementation commit: **`e0577a6fb` — Arm only changed function identities during
adoption**. It contains exactly the three owned source/test paths and this landing
note. The subsequent documentation-only commit records that immutable identity;
no additional runtime change or publication follows.

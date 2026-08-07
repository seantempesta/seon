---
type: research
status: complete
tags: [research, runtime, flow]
---

# Phase 0 falsifier — the environment travels as data through flow

Subject: Phase 0(c) of the sealed
[seon.env PRD](../plan/seon-env-prd-2026-08-07.md) — "a flow submission
carrying the environment as data delivers it to `:io` work" — plus the proc
`:args` half of the same claim, run live against the real
`seon.flow` work launcher and the real process-root executors.

Both authorities were read END TO END before any probe was written: the
sealed PRD [seon-env-prd-2026-08-07.md](../plan/seon-env-prd-2026-08-07.md)
and its flow grounding
[environment-mechanism-flow-2026-08-07.md](environment-mechanism-flow-2026-08-07.md),
whose §5 verdict is the contract falsified here.

No production source was edited. Probes live in `tmp/env-probes/`.

## Verdicts

| # | Claim | Verdict |
|---|---|---|
| a | `:io` work receives exactly its submission's environment on the virtual-thread executor, with no dynamic bindings present | **HOLDS** |
| b | `:compute` work receives exactly its submission's environment | **HOLDS** (data path); the ambient carrier is still live via `bound-fn*` |
| c | `complete!` callbacks receive exactly their submission's environment | **HOLDS** (data path); ambient carrier still live via `bound-fn*` |
| d | No cross-submission or cross-launcher environment read ever occurs | **HOLDS** — 0 of 540 observations |
| e | A stop → `create-flow` → start cycle re-delivers proc `:args`, ~ms scale | **HOLDS** — 0.034–0.44 ms per rebuild |
| — | Report §5.5: declaring `:params` makes a missing environment a start-time refusal "for free" | **FALSIFIED** |
| — | Report §4.5 side finding: the work-launcher graph passes only `:compute-exec`, so its loop escapes the process root's executor | **CONFIRMED structurally, INERT today** |

Overall: `{:probe/verdict :pass}` across 3 repetitions.

## The contract validated

The PRD names the environment's contents with the EXISTING key names
(§ "The value"): `:seon.db/connection`, `:seon.db/db`,
`:seon.schema/projection`, `:seon.boot/cluster-name`,
`:seon.cluster.agent/id`, `:seon.cluster.run/id`,
`:seon.cluster.run.form/ordinal`, `:seon.flow/work-launcher`,
`:seon.sci.admit/caps`. It does not name the CARRIER key; the flow report's
verdict writes it `::flow/environment` in `seon.flow`'s own namespace. The
probe carries it under `:seon.env/environment`, which is the naming this
report recommends: the value's owner is `seon.env`, and the same key then
reads identically on a submission map, in proc `:args`, and on a request map
(PRD § "Carriage" enumerates all three media). One key, one owner, three
media — `::flow/environment` would make the flow namespace the owner of a
value that crosses two media flow knows nothing about.

The exact shapes exercised:

```clojure
;; IO submission — seon.flow/submit!
{:seon.flow/submission-id  ...
 :seon.flow/work-fn        (fn [arg] ...)   ; arg carries the environment
 :seon.flow/complete!      (fn [terminal] ...) ; terminal carries it too
 :seon.env/environment     {:seon.boot/cluster-name ... :seon.db/connection ...}}

;; Compute submission — seon.flow/submit!!
{:seon.flow/submission-id  ...
 :seon.flow/workload       :compute
 :seon.flow/time-limit-ms  15000
 :seon.flow/work-fn        (fn [arg] ...)
 :seon.env/environment     {...}}

;; Proc :args — seon.flow/var-process
(sflow/var-process #'step :io {:seon.env/environment {...}})
```

Today's `submit!`/`submit!!` do not perform the merge, so the probe performs
it at the submission boundary: it reads the environment OUT OF THE SUBMISSION
MAP (`(get submission :seon.env/environment)` — a value, at submission time)
and merges it into the map handed to the work-fn and to `complete!`
(`tmp/env-probes/flow_env_carriage.clj`, `io-submission` / `compute-submission`).
That is exactly the merge the flow report asks production to make at
`src/seon/flow.clj:366` (io work-fn), `:281` (compute work-fn), and in the
`::complete!` call at `src/seon/flow.clj:321`. Everything downstream of that
merge — the admission buffer, the launcher proc, the executors, the terminal
callback — is the real production path, unmodified.

## Evidence

### (a)-(d) Carriage under repetition and concurrency

`flow-env-carriage/carriage-probe`: 3 real work launchers
(`seon.flow/start-work-launcher!`, compute concurrency 4, io concurrency 8,
queue depth 64 each), 3 concurrent submitter threads, 40 submissions per
launcher alternating `:io` and `:compute`, distinct environment values per
launcher, repeated 3 times. 540 work observations plus 180 `complete!`
observations in total.

Every round, identically:

```clojure
{:io-count 60 :compute-count 60 :complete-count 60
 :io-environment-exact? true
 :compute-environment-exact? true
 :complete-environment-exact? true
 :cross-environment-reads []
 :io-ambient-clean? true
 :io-all-virtual? true
 :compute-ambient-decoy-count 60
 :complete-ambient-decoy-count 60}
```

The decoy is the load-bearing part of the design. Every submitter thread runs
inside

```clojure
(binding [seon.db/*conn* "DECOY-connection-from-submitter-binding-frame"
          seon.effect/*request-context* {:seon.env/marker "DECOY-request-context"}]
  ...)
```

so a work-fn that read the ambient carrier instead of the data would return
the decoy, and one that read the WRONG submission's data would return another
launcher's marker. Neither ever happened.

What the ambient columns say about the two halves today:

- **`:io` work: `:io-ambient-clean? true`, 60/60.** Inside the io work-fn
  both dynamic Vars are at their root `nil`, on a virtual thread
  (`:virtual? true`, unnamed, a fresh one per task). This is the audit's
  `probe_work_launcher_binding` failure reproduced from the other side: the
  bindings are simply not there, and the environment arrived anyway because
  it was data.
- **`:compute` work and `complete!`: 60/60 carry the DECOY.** Not a probe
  defect — it is `(bound-fn* work-fn)` at `src/seon/flow.clj:673` and
  `(bound-fn* complete!)` at `src/seon/flow.clj:618` doing exactly what the
  flow report calls "currently lucky". The data path delivered the correct
  environment in every one of those 120 observations regardless, which is the
  point: the two mechanisms currently coexist, and only one of them works on
  both halves. Deleting the `bound-fn*` sites is what makes the surviving
  mechanism observable — while they remain, a submission that FORGOT its
  environment would still appear to work on `:compute` and fail only on
  `:io`, which is the exact failure signature the audit found.

Sample io observation (verbatim):

```clojure
{:workload :io :launcher 0 :n 0
 :received {:seon.boot/cluster-name "cluster-0"
            :seon.cluster.agent/id "cluster-0-agent"
            :seon.db/connection "connection:cluster-0"
            :seon.env/marker "marker-0"}
 :ambient {:seon.db/*conn* nil
           :seon.effect/*request-context* nil
           :thread-id 82 :thread-name "" :virtual? true}}
```

### (e) Rebuild re-delivers proc `:args`

`flow-env-carriage/rebuild-probe`: one `:io` proc built through
`seon.flow/var-process` with `{:seon.env/environment env}` as its args, then
5 × (stop → `create-flow` → start → resume).

```clojure
{:cycles 5
 :delivery-count 6          ; initial start + 5 rebuilds
 :expected-delivery-count 6
 :every-delivery-exact? true
 :rebuild-ms [0.055958 0.038875 0.039209 0.034292 0.036459]
 :max-rebuild-ms 0.055958}
```

An earlier run under a colder JVM measured `[0.443875 0.098709 0.047042
0.063708 0.047875]`. So the whole stop/create/start/resume cycle with an
environment in `:args` is tens of microseconds to sub-millisecond — well
inside the ~0.3 ms figure AGENTS.md already records for topology rebuild, and
no measurable cost from carrying the environment. The environment delivered
on every rebuild was value-identical to the original, with zero extra code:
delivery happens at `spi/start`, which re-reads `pdescs`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:166-167`;
`flow/spi.clj:19-22` mandates it).

### FALSIFIED — `:params` does not enforce the environment's presence

The flow report's recommendation 5 says declaring
`:params {::flow/environment "…"}` makes `impl.clj:257`'s
`(assert (or (not params) args) "must provide :args if :params")` enforce
presence "for free". It does not. Probed directly: a proc declaring `:params`
and given NO `:args` at all started cleanly, and its init arity received

```clojure
{:refused? false
 :init-received {:received nil
                 :arg-keys [:clojure.core.async.flow/pid]
                 :thread-id 3}}
```

The reason is in the source: `start-proc` calls
`(spi/start proc {:pid pid :args (assoc args ::flow/pid pid) ...})`
(`flow/impl.clj:155-162`). `(assoc nil ::flow/pid pid)` is a non-empty map, so
`args` is ALWAYS truthy by the time the assertion sees it. The assertion can
only fire if a launcher is started outside `impl/create-flow`. Declaring
`:params` therefore remains worthwhile for `describe`/`datafy` visibility, but
**it is documentation, not a gate.**

Consequence for the design: the refusal must be Seon's own, at Seon's own
construction door. `seon.flow/var-process` already throws on a non-Var step
and on a `:mixed` workload (`src/seon/flow.clj:100-110`); requiring
`:seon.env/environment` in its `args` map is the same one-line refusal in the
same place, and it is the only place that actually runs. Likewise `submit!`
and `submit!!` must refuse a submission without the key themselves — the flow
report's recommendation 4 is right and recommendation 5's "for free" is not.

### Side finding CONFIRMED (structurally) — the launcher graph's `:io-exec`

`src/seon/flow.clj:528` supplies `:compute-exec` only. flow's resolver is
`(get-exec [_ context] (or (execs context) (disp/executor-for context)))`
(`flow/impl.clj:148`), so with no `:io-exec` the launcher proc's own run loop
(`impl.clj:262` → `:323`) resolves core.async's global memoized `:io`
executor (`impl/dispatch.clj:98-111`) rather than the process root's.

Measured both ways with a probe proc that records its run-loop thread:

```clojure
:run-loop-without-io-exec {:thread-id 820 :thread-name ""  :virtual? true}
:run-loop-with-io-exec    {:thread-id 822 :thread-name "probe-explicit-io-exec"
                           :virtual? false}
```

So the escape is real and demonstrable. **But it has no live consequence
today**, and this qualification matters: the process root's `:io` executor IS
core.async's global one —
`resources/seon/operator/runtime.clj:17-22` defines
`:io (async.dispatch/executor-for :io)`, and the probe confirms
`:root-io-is-global-io? true`. The root's `:compute` is genuinely its own
(`:root-compute-is-global-compute? false`), which is why the graph bothers to
pass `:compute-exec`.

The defect is therefore latent and structural: the root cannot today express
a distinct `:io` executor, and if it ever did, every Seon flow graph's run
loops would silently keep using the global one. Filed as
[the work-launcher graph does not pass its root :io executor](../../../seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md).

## Probe inventory

- `tmp/env-probes/flow_env_carriage.clj` — namespace `flow-env-carriage`,
  entry point `(flow-env-carriage/run)` → `{:probe/verdict :pass|:fail ...}`.
  No test framework. Sub-probes: `carriage-probe` (a–d), `rebuild-probe` (e
  plus the `:params` falsification), `executor-probe` + `run-loop-placement`
  (the side finding).

Run:

```bash
clojure -M:dev -e '(load-file "tmp/env-probes/flow_env_carriage.clj")
                   (clojure.pprint/pprint
                    ((requiring-resolve (quote flow-env-carriage/run))))'
```

`(run {:repetitions 1})` for a fast pass; `(run {:launcher-count N
:per-launcher M :cycles C :repetitions R})` to widen it.

## What this means for Phase 3

1. **The mechanic is sound.** The environment as a submission key and as proc
   `:args` survives the exact hop that drops bindings today, under
   concurrency, with repetition, across several launchers, with an actively
   hostile decoy installed in the ambient carriers.
2. **Both `bound-fn*` sites must go in the same change as the merge**, not
   after. While they remain, `:compute` and `complete!` keep reading a
   binding frame, so a forgotten environment is invisible on one half and
   fatal on the other — the failure mode the audit already paid for.
   `src/seon/flow.clj:618`, `:673`, and the third at `:917`
   (`join-error-fanout!`).
3. **Refusal is Seon's job.** `var-process` requires
   `:seon.env/environment` in `args`; `submit!`/`submit!!` require it on the
   submission. flow's `:params` assertion will not do it.
4. **Recommend `:seon.env/environment` as the carrier key**, not
   `::flow/environment`: the same key must read identically on a submission,
   in proc args, and on a web request map, and its owner is `seon.env`.
5. **The rebuild path costs nothing.** Live topology change with an
   environment in args stays sub-millisecond.

## Ugly output

- **Virtual threads report an empty `.getName`.** Every `:io` observation
  came back `:thread-name ""`, which is useless for telling two tasks apart;
  `.threadId` is the only usable identity. Anything in Seon that logs or
  renders a thread NAME for diagnosis will render a blank on exactly the
  executor where the interesting failures live. Worth checking wherever
  thread identity reaches a fault fact or a debug page.
- **`clojure -M:dev` prints two banner lines before any result** — an
  incubator-module warning and an `environ` java-home overwrite warning — on
  every single probe invocation. Noise on a surface an agent reads.

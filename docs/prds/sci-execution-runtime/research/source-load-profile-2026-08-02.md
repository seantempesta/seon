---
type: research
status: active
tags: [research, runtime]
---

# Source-load profile, 2026-08-02

## Verdict

Cold `(require 'seon.artifact)` still spends most of its time loading dependency
source, not Seon source. Across three categorized profiles, the 433-load
closure averaged **10,570 ms** inside `clojure.core/load`:

| source ownership | namespaces | mean exclusive load, ms | share |
|---|---:|---:|---:|
| vendored/git source dependencies | 205 | **6,014** | **56.9%** |
| jar and platform dependencies | 176 | **3,534** | **33.4%** |
| first-party `src/` | 52 | **1,022** | **9.7%** |

The source-dependency share is 5.9 times the complete first-party share.
`konserve.tiered` and `datahike.connector` alone average 2,005 ms of exclusive
load. The standard AOT cache should therefore begin with dependency source;
first-party AOT cannot be the biggest win in this closure.

A perfect removal of the measured 6,014 ms source-dependency cost would leave
about 4,556 ms. AOT still runs namespace initialization and will not remove the
whole cost, so this is an upper bound, not a prediction. The profile gives a
dependency-only cache enough theoretical headroom to attempt the under-five-
second acceptance gate without putting first-party iteration behind AOT.

## Conditions

| condition | value |
|---|---|
| measured | 2026-08-02 |
| checkout at final census | `abd99ce12ba994d9cd22caa8fe626e064a476512` |
| machine | Apple arm64, macOS 26.5.2 (25F84) |
| JDK | OpenJDK 26.0.1, Homebrew, 64-bit Server VM |
| Clojure CLI | 1.12.5.1654 |
| project Clojure | 1.12.5 |
| launch | `clojure -M:dev` |
| JVM flags from `:dev` | incubator vector module, native access, G1, `MaxRAMPercentage=12.5`, periodic GC 30 s |
| process state | one fresh JVM per sample; filesystem caches were not flushed |

This was a shared-tree velocity incident, not a frozen build checkpoint. Other
lanes committed and edited first-party files while the samples ran. The
profiled closure nevertheless stayed exactly 433 load calls in every complete
instrumented sample, its category split stayed stable, and the same dependency
names occupied the top ranks. The conclusion about dependency dominance does
not depend on the moving first-party tail. No load failure blocked the
measurement.

## Uninstrumented cold require

The matched source-load command was:

```bash
clojure -M:dev -e "(time (require 'seon.artifact))"
```

Five valid fresh-JVM results were 11,047.858, 11,382.475, 9,976.416,
11,851.777, and 11,149.349 ms. Their mean is **11,081.575 ms**, median
**11,149.349 ms**, and range **9,976.416–11,851.777 ms**. One additional
invocation emitted JVM startup diagnostics but no `time` result and is not in
the sample. These results reproduce the reported 11.8-second class and remain
over the owner's ten-second bound at the median.

## Profiling method

`clojure.core/load` is the correct seam for this measurement. `load-one` calls
it with the namespace root resource
(`reference-code/clojure/src/clj/clojure/core.clj:6039-6048`), and
`clojure.lang.RT/load` performs the class-versus-source selection
(`reference-code/clojure/src/jvm/clojure/lang/RT.java:423-460`).

The profiler temporarily replaces the `load` Var, maintains the actual nested
load stack, and subtracts child duration from each parent. Thus `self-ns` is an
exclusive cost and category totals sum to the observed outer load; inclusive
`total-ns` remains available to show the dependency closure rooted at a
namespace. It records the actual dependency order first and sorts only for the
top-cost presentation.

The source category is derived from the source resource URL, not a namespace
roster:

- a URL under this checkout's `src/` is first-party;
- a URL under `reference-code/` or the resolved `.gitlibs/libs/` checkout is a
  source dependency; and
- every other URL is jar/platform, including Clojure source entries inside
  jars and relative platform loads.

The complete reproducible form below emits the summary and all namespace rows.
It was run in a fresh JVM for every sample.

```clojure
(let [original-load @#'clojure.core/load
      rows (atom [])
      stack (atom [])
      source-url
      (fn [path]
        (let [base (clojure.string/replace-first path #"^/" "")
              loader (clojure.lang.RT/baseLoader)]
          (some #(when-let [url (.getResource loader (str base %))]
                   (str url))
                [".clj" ".cljc"])))
      category
      (fn [url]
        (cond
          (and url
               (clojure.string/includes?
                url "/Users/sean/src/seon/src/"))
          :first-party

          (and url
               (or (clojure.string/includes? url "/reference-code/")
                   (clojure.string/includes? url "/.gitlibs/libs/")))
          :source-dependency

          :else :jar-or-platform))
      profiled-load
      (fn [& paths]
        (doseq [path paths]
          (let [start (System/nanoTime)]
            (swap! stack conj {:path path :start start :child 0})
            (try
              (original-load path)
              (finally
                (let [end (System/nanoTime)
                      frame (peek @stack)
                      total (- end (:start frame))
                      self (- total (:child frame))]
                  (swap! stack pop)
                  (swap! rows conj
                         {:path path :total-ns total :self-ns self})
                  (when (seq @stack)
                    (swap! stack update-in
                           [(dec (count @stack)) :child]
                           + total))))))))
      start (System/nanoTime)]
  (with-redefs [clojure.core/load profiled-load]
    (require 'seon.artifact))
  (let [elapsed (- (System/nanoTime) start)
        annotated
        (mapv (fn [row]
                (let [url (source-url (:path row))]
                  (assoc row :source-url url :category (category url))))
              @rows)]
    (prn {:elapsed-ns elapsed
          :sum-self-ns (reduce + (map :self-ns annotated))
          :count (count annotated)
          :category-totals
          (into (sorted-map)
                (map (fn [[k rs]]
                       [k {:namespace-count (count rs)
                           :self-ns (reduce + (map :self-ns rs))}]))
                (group-by :category annotated))})
    (doseq [row (sort-by :self-ns > annotated)]
      (prn row))))
```

Exclusive self time summed to within 0.4 ms of the separately measured outer
load in each categorized run. The wrapper overhead is present in both the row
sum and outer timer; the separate uninstrumented sample above is the acceptance
baseline.

## Category raw results

| run | first-party, ms | jar/platform, ms | source dependency, ms | outer load, ms |
|---:|---:|---:|---:|---:|
| 1 | 1,085.757 | 3,712.215 | 6,259.959 | 11,058.334 |
| 2 | 968.590 | 3,536.839 | 5,855.987 | 10,361.826 |
| 3 | 1,012.939 | 3,351.790 | 5,927.085 | 10,292.086 |
| **mean** | **1,022.429** | **3,533.615** | **6,014.344** | **10,570.748** |

The 205 source-dependency loads include maintained checkouts for Datahike,
Konserve, clj-kondo, SCI, http-kit, and Datastar. A slower 12,658 ms diagnostic
run grouped those same source rows further: Konserve 2,793 ms, Datahike
2,746 ms, clj-kondo 1,058 ms, SCI 528 ms, Datastar 63 ms, and http-kit 39 ms.
That single family split is diagnostic rather than an averaged result, but it
agrees with both the stable top-namespace ranks and the source category's
dominance.

## Top 15 namespaces by exclusive load cost

The table averages three complete 433-row profiles. Raw values preserve the
observed machine weather; ranking uses the mean.

| rank | namespace | owner | raw self time, ms | mean, ms |
|---:|---|---|---|---:|
| 1 | `konserve.tiered` | source dependency | 1,178.602, 1,188.225, 1,095.999 | **1,154.276** |
| 2 | `datahike.connector` | source dependency | 899.204, 857.013, 797.137 | **851.118** |
| 3 | `superv.async` | jar/platform | 571.719, 572.434, 621.184 | **588.445** |
| 4 | `konserve.impl.defaults` | source dependency | 379.434, 424.542, 333.073 | **379.016** |
| 5 | `konserve.filestore` | source dependency | 330.465, 328.626, 288.657 | **315.916** |
| 6 | `clojure.core.async` | jar/platform | 308.742, 318.608, 307.927 | **311.759** |
| 7 | `taoensso.encore` | jar/platform | 261.580, 302.617, 242.042 | **268.746** |
| 8 | `seon.cluster` | first-party | 252.000, 285.014, 239.084 | **258.699** |
| 9 | `datahike.writer` | source dependency | 252.622, 235.545, 260.065 | **249.410** |
| 10 | `konserve.core` | source dependency | 195.796, 186.526, 179.287 | **187.203** |
| 11 | `datahike.query` | source dependency | 183.265, 161.262, 176.928 | **173.818** |
| 12 | `datahike.versioning` | source dependency | 144.416, 153.951, 137.205 | **145.191** |
| 13 | `datahike.writing` | source dependency | 135.137, 131.151, 140.522 | **135.603** |
| 14 | `malli.core` | jar/platform | 147.686, 115.689, 110.882 | **124.752** |
| 15 | `clj-kondo.impl.analyzer` | source dependency | 123.558, 130.140, 116.600 | **123.433** |

The next three were `clj-cbor.codec`, `clojure.core.async.impl.go`, and
`sci.impl.analyzer`; small changes in rank around that boundary do not change
the cache decision.

## Implications for the cache experiment

1. Cache dependency source first. It owns 56.9% of measured exclusive load,
   while all first-party namespaces together own only 9.7%.
2. Do not treat `reference-code/` alone as the dependency set. Konserve is
   resolved through `.gitlibs/libs/` and owns the single largest namespace;
   the cache input must derive from the effective classpath.
3. Keep core.async as a separately measured caveat. It is in the jar/platform
   category, `clojure.core.async` itself averages 312 ms, and
   `clojure.core.async.impl.go` is just below the top 15 at 116 ms. Excluding
   core.async from an initial cache leaves a material but bounded residual and
   avoids deciding the pinned go/IOC behavior from this profile alone.
4. Jar/platform source remains 3.53 seconds. A vendored-source-only cache is
   the smallest experiment with enough theoretical headroom, but the cold
   three-run under-five-second gate must decide whether it is sufficient.

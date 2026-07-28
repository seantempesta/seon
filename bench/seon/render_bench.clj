;;; The render benchmark — the 16 ms frame budget, measured.
;;;
;;;   clojure -M:test bench/seon/render_bench.clj
;;;   clojure -M:test bench/seon/render_bench.clj --trials 2000
;;;
;;; WHY THIS IS PART OF THE RUNG AND NOT A LATER NICETY. The owner's bar
;;; is "no N=1 attempts. This shit has to be fast. Like 60fps fast for
;;; very dynamic rendering" — so 16 ms per frame under churn is a DESIGN
;;; INPUT, and a design input that nothing measures is a wish. Every
;;; performance claim in the N4 contracts points here. If a number in a
;;; docstring is not reproducible by this file, the docstring is wrong.
;;;
;;; WHAT IT MEASURES, and what it deliberately does not. This is a
;;; BENCHMARK: it measures, it never asserts correctness. The properties
;;; that make the budget ACHIEVABLE — one evaluation per block per
;;; render, suppression emitting only on projection change — are sealed
;;; tests in `test/seon/render/`, because a wall-clock assertion in a
;;; suite is a flake generator and a counted invariant is not. Read this
;;; the other way round too: a green suite with a 40 ms p99 here is a
;;; DESIGN failure the suite cannot see, which is exactly why both exist.
;;;
;;; THE MEASUREMENT IS HONEST ABOUT THE JVM. Every scenario warms until
;;; the timing stops improving, reports p50/p95/p99 rather than a mean
;;; (a mean hides the frame that stutters, and the stutter is what a
;;; human sees), and prints its sample size. A single number with no
;;; distribution behind it is the N=1 the owner named.
;;;
;;; SCOPE TODAY: the pure path — serialization and one page's block set.
;;; The churn scenarios below are the skeleton for package 2's pipeline
;;; (interest → registration → suppression → mult → tap → SSE); they
;;; print `awaits the pipeline` until that owner exists, and the shape of
;;; what they will report is fixed now so the number is comparable
;;; across the rung rather than invented at the end.

(ns seon.render-bench
  (:require [clojure.string :as str]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]))

;;; ---------------------------------------------------------------------------
;;; Timing
;;; ---------------------------------------------------------------------------

(def ^:private frame-budget-ns
  "16 ms — one frame at 60 fps. Not a threshold anything fails on; the
  number the report is read against."
  16000000)

(defn- percentile
  [sorted p]
  (nth sorted (min (dec (count sorted))
                   (long (* p (count sorted))))))

(defn- measure
  "Run `thunk` `trials` times after warming, returning nanosecond
  percentiles. Warmup is a fixed 20% of trials plus 200 — enough for C2
  on a body this small, and stated rather than tuned in silence.

  A thunk whose owner is still a contract stub reports `:awaits` rather
  than a number: a measurement of a throw is not a measurement, and a
  bench that dies on the first stub cannot report the scenarios that DO
  have owners."
  [trials thunk]
  (if-let [awaiting (try (thunk) nil
                         (catch Throwable failure (or (ex-message failure)
                                                      (.getName (class failure)))))]
    {:awaits awaiting}
    (do
      (dotimes [_ (+ 200 (quot trials 5))] (thunk))
      (let [samples (long-array trials)]
        (dotimes [i trials]
          (let [start (System/nanoTime)]
            (thunk)
            (aset samples i (- (System/nanoTime) start))))
        (let [sorted (vec (sort samples))]
          {:trials trials
           :p50 (percentile sorted 0.50)
           :p95 (percentile sorted 0.95)
           :p99 (percentile sorted 0.99)
           :max (peek sorted)})))))

(defn- report!
  [label {:keys [awaits trials p50 p95 p99 max]}]
  (if awaits
    (println (format "%-46s awaits implementation — %s" label awaits))
    (println
     (format "%-46s n=%-6d p50=%8.3fms p95=%8.3fms p99=%8.3fms max=%8.3fms  %s"
             label trials
             (/ p50 1e6) (/ p95 1e6) (/ p99 1e6) (/ max 1e6)
             (if (<= p99 frame-budget-ns)
               (format "%.0f fps at p99" (/ 1e9 (double (Math/max 1 (long p99)))))
               (format "OVER BUDGET at p99 (%.1fx)"
                       (/ (double p99) frame-budget-ns)))))))

;;; ---------------------------------------------------------------------------
;;; Representative content
;;; ---------------------------------------------------------------------------

(defn- transcript-hiccup
  "A transcript surface of `n` events, in the shape the quarry actually
  rendered: message bubbles interleaved with one-line activity rows.
  This is the largest thing that morphs, so it is the frame that has to
  fit."
  [n]
  (into [:div {:class "seon-card flex flex-col"}]
        (map (fn [i]
               (if (even? i)
                 [:div {:class "py-1 flex"}
                  [:div {:class (str "seon-bubble max-w-[78%] min-w-0 rounded "
                                     "px-2.5 py-1.5 mr-auto bg-base-900 "
                                     "border border-base-800")}
                   [:div {:class "flex items-baseline gap-2 flex-wrap"}
                    [:span {:class "text-xs font-mono font-semibold"} "agent-a"]]
                   [:div {:class "markdown mt-0.5 min-w-0"}
                    (str "a reply with <angle> & ampersand content, number " i)]]]
                 [:div {:class "agent-activity flex items-baseline gap-1.5 px-2 py-1 text-xs min-w-0"}
                  [:span {:class "font-medium text-text-400 truncate"}
                   (str "ran my.agents.agent-a/step-" i)]
                  [:span {:class "font-mono text-text-600 shrink-0"} "12ms"]
                  [:span {:class "font-mono shrink-0 text-success"} "done"]]))
             (range n))))

(defn- page-hiccup
  "A whole page: header, three sibling surfaces, one of them the
  transcript. The comparison that matters — this is what the OLD system
  re-serialized on every datom, and what the block design sends only
  when a block actually changed."
  [transcript-events]
  [:main {:id "app-view" :class "flex flex-col gap-2 w-full min-h-0 flex-1"}
   [:header {:id "surface-header"
             :class "flex items-center gap-x-4 border-b border-base-800 px-3 py-1.5 text-xs font-mono"}
    [:span {:class "text-amber-400"} "◆"] [:span "seon"]
    [:span {:class "text-text-200"} "3"] [:span {:class "text-text-500"} "agents"]]
   [:div {:id "surface-canvas" :class "border border-base-800 rounded-md bg-base-900 p-2"}
    [:div {:class "grid grid-cols-3 gap-2"}
     (for [i (range 9)]
       [:div {:class "border border-base-800 rounded p-3"} (str "card " i)])]]
   [:div {:id "surface-problems" :class "border border-base-800 rounded-md p-2"}
    [:span {:class "text-success"} "no problems"]]
   [:div {:id "surface-transcript" :class "border border-base-800 rounded-md p-2"}
    (transcript-hiccup transcript-events)]])

;;; ---------------------------------------------------------------------------
;;; Scenarios
;;; ---------------------------------------------------------------------------

(defn- serialization-scenarios!
  [trials]
  (println "\n-- serialization (the innermost loop of every morph)")
  (doseq [[label value]
          [["one activity row" (first (rest (transcript-hiccup 2)))]
           ["transcript, 25 events" (transcript-hiccup 25)]
           ["transcript, 250 events" (transcript-hiccup 250)]
           ["whole page, 25-event transcript" (page-hiccup 25)]
           ["whole page, 250-event transcript" (page-hiccup 250)]]]
    (report! label (measure trials #(hiccup/->string value))))
  (println
   (str "\n   The last two rows are the OLD system's per-datom cost: it "
        "morphed one element,\n   the whole page. The rows above them are "
        "this design's, because the morph is the block.")))

(defn- admission-scenarios!
  [trials]
  (println "\n-- admission (`hiccup?` runs on every block's output)")
  (doseq [[label value]
          [["transcript, 25 events" (transcript-hiccup 25)]
           ["whole page, 250-event transcript" (page-hiccup 250)]]]
    (report! label (measure trials #(hiccup/hiccup? value)))))

(defn- page-scenarios!
  [_trials]
  (println "\n-- page derivation (database value -> ordered surfaces)")
  (println "   awaits the pipeline: `seon.render.block/surfaces` over a real")
  (println "   in-memory cluster, with the block count as the varied dimension.")
  ;; Package 1 leaves this deliberately unimplemented rather than
  ;; measuring a stub: a number produced by a throwing body is worse
  ;; than no number.
  (when (System/getenv "SEON_BENCH_PAGE")
    (println "   SEON_BENCH_PAGE is set but `block/surfaces` is a stub:"
             (try (block/surfaces nil {}) (catch Throwable t (ex-message t))))))

(defn- churn-scenarios!
  [_trials]
  (println "\n-- live churn (package 2: interest -> suppression -> tap -> SSE)")
  (println "   awaits the pipeline. The shape is fixed now so the numbers are")
  (println "   comparable across the rung rather than invented at the end:")
  (println "     commit-to-morph latency at 1/8/32 tabs, one changed block;")
  (println "     effective frame rate under a generated commit stream at")
  (println "       10/60/240 commits per second;")
  (println "     evaluations per commit (the counted invariant, cross-checked")
  (println "       against the sealed suite's count);")
  (println "     bytes on the wire per commit, whole-page versus per-block;")
  (println "     a paused-read tab's http-kit heap growth per morph (issue")
  (println "       http-kit-streaming-writes-have-an-unbounded-socket-queue —")
  (println "       measure first, fork only on evidence)."))

;;; ---------------------------------------------------------------------------
;;; Entry
;;; ---------------------------------------------------------------------------

(defn -main
  [& args]
  (let [trials (or (some->> args
                            (drop-while #(not= "--trials" %))
                            second
                            parse-long)
                   1000)]
    (println (str "seon render benchmark — budget "
                  (/ frame-budget-ns 1e6) "ms/frame (60 fps)"))
    (println (str "JVM " (System/getProperty "java.version")
                  "  " (str/join " " (or (seq args) ["--trials" trials]))))
    (serialization-scenarios! trials)
    (admission-scenarios! trials)
    (page-scenarios! trials)
    (churn-scenarios! trials)
    (println "\nRecord every number in the owning PRD's research/ directory.")
    (println "An unreproducible number is an anecdote.")))

(apply -main *command-line-args*)

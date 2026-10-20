;; Builds tests-run.edn and tests-run.md from the raw check reports (SEON_TEST_RESULT_FILE EDN, first run and one re-run of the reds).
;; Usage: bb tests-run.clj SCRATCH-DIR  (res-a?.edn = first run in 8 batches, res-rerun.edn = re-run of the reds)
(require '[clojure.edn :as edn] '[clojure.string :as str])
(def cdir "docs/research/agent-platform/2026-10-20/census/")
(def sd (first *command-line-args*))
(defn results [f] (:seon.test/results (edn/read-string (slurp f))))
(def first-run (into {} (map (juxt :seon.test/sym identity)) (mapcat #(results (str sd "/res-a" % ".edn")) "abcdefgh")))
(def rerun (into {} (map (juxt :seon.test/sym identity)) (results (str sd "/res-rerun.edn"))))
(def static (into {} (map (juxt :census/sym identity)) (edn/read-string (slurp (str cdir "tests.edn")))))
(def idea-map (edn/read-string (slurp (str cdir "tests-idea-map.edn"))))
(def titles (into {} (for [l (str/split-lines (slurp (str sd "/titles.txt")))
                           :let [[_ n t] (re-find #"^\d+:## (\d+)\. (.*)$" l)]] [(parse-long n) t])))
(defn red? [m] (pos? (+ (:seon.test/fail-count m) (:seon.test/error-count m))))
(defn n-assert [m] (+ (:seon.test/pass-count m) (:seon.test/fail-count m) (:seon.test/error-count m)))
(defn first-failure [m]
  (let [r (first (:seon.test.failure/reports m))]
    {:type (:seon.test.failure/type r) :message (:seon.test.failure/message r) :line (:seon.test.failure/line r)}))
(defn clip [s n] (when s (let [s (str/replace s #"\s+" " ")] (subs s 0 (min n (count s))))))
(defn classify [m r2]
  (cond
    (not (red? m)) (if (pos? (n-assert m)) :meaningful :no-assertions)
    (and r2 (not (red? r2))) :flaky
    (str/includes? (str (:seon.test/failure-message m)) "exceeded its declared duration") :timeout
    :else :red))
(def rows
  (vec (for [[sym m] (sort-by key first-run)
             :let [r2 (rerun sym) c (classify m r2)]]
         (cond-> {:census/sym sym :census/class c
                  :census/result (cond (not (red? m)) :pass (pos? (:seon.test/error-count m)) :error :else :fail)
                  :census/assertions (n-assert m) :census/pass (:seon.test/pass-count m)
                  :census/fail (:seon.test/fail-count m) :census/error (:seon.test/error-count m)
                  :census/ms (Math/round (:seon.test.member/run-ms m))
                  :census/alloc-mb (Math/round (/ (:seon.test.member/alloc-bytes m) 1048576.0))
                  :census/static-asserts (:census/asserts (static sym))
                  :census/long? (boolean (:census/long? (static sym)))
                  :census/ideas (vec (for [[i ss] idea-map :when (some #{sym} ss)] i))}
           (red? m) (assoc :census/first-failure (update (first-failure m) :message clip 200)
                           :census/rerun (when r2 {:result (if (red? r2) :red :pass) :ms (Math/round (:seon.test.member/run-ms r2))})))))) 
(spit (str cdir "tests-run.edn") (str "[\n" (str/join "\n" (map pr-str rows)) "\n]\n"))
(def by (into {} (map (juxt :census/sym identity)) rows))
(def tot (frequencies (map :census/class rows)))
(def meaningful (filter #(= :meaningful (:census/class %)) rows))
(def md (java.lang.StringBuilder.))
(defn p [& xs] (.append md (str (apply str xs) "\n")))
(p "---\ntitle: \"Cited-proof run: do the 20 ideas' deftests run green and assert something? (census-tests)\"\ntype: research\n---\n")
(p "# Cited-proof run (census-tests, 2026-10-20)\n")
(p "Every deftest cited in [ideas.md](ideas.md) (the four wildcards expanded to their namespaces: 228 distinct vars) ran on throwaway check JVMs booted by `bin/seon check --live-shape` at HEAD bc1a20635, 8 batches of 30 (the batch reports are `SEON_TEST_RESULT_FILE` EDN). Reds were re-run once in a batch of their own. Per-test data is in [tests-run.edn](tests-run.edn); the builder is [tests-run.clj](tests-run.clj), the idea-to-tests map [tests-idea-map.edn](tests-idea-map.edn).\n")
(p "Assertion counts are the report maps' pass+fail+error counters; `:meaningful` means green with at least one counted assertion. Whether an assertion is on data rather than printed text was not judged (inspect the test before porting). The run-ms column is the runner's own member time inside a batch, so a test near its 5 s bound can flip with load.\n")
(p "## Totals\n")
(p "| class | tests |\n|---|---|")
(doseq [c [:meaningful :no-assertions :red :timeout :retired-assumption :flaky]] (p "| `" c "` | " (get tot c 0) " |"))
(p "| total | " (count rows) " |\n")
(p "Batch runs: 8 batches, all 228 executed, 0 reused. Batches 1-6 reported red, 7-8 green. Re-run of the 18 first-run reds: 4 turned green (`:flaky`: three missed their 5 s bound or a 10 ms read bound under load, one is a cost-table row), 14 stayed red (12 `:red`, 2 `:timeout`: `seon.mine-test/an-ended-attempt-is-graded-once-on-main` ran 50 s against a 20 s bound; `seon.cluster.db-flow-test/a-filed-tasks-design-prove-implement-chain-merges-with-no-hand-act` ran 262 s, with 7 failures and 2 errors, the chain stalled instead of ending done).\n")
(p "No test was classed `:retired-assumption`: none of the reds could be tied to a deleted behaviour without more than a read of the test; the porter confirms each red against the code before deciding whether the idea or the test is stale. No cited test has zero assertions, and none is a stored-result-only pass: every green here ran this session.\n")
(p "## Reds, timeouts and flakes (first failure)\n")
(p "| test | class | ideas | first failure | re-run | ms |\n|---|---|---|---|---|---|")
(doseq [r (remove #(contains? #{:meaningful :no-assertions} (:census/class %)) rows)]
  (p "| `" (:census/sym r) "` | " (:census/class r) " | " (str/join "," (:census/ideas r)) " | "
     (str/replace (str (:message (:census/first-failure r))) "|" "/") " (line " (:line (:census/first-failure r)) ") | "
     (some-> r :census/rerun :result name) " | " (:census/ms r) " |"))
(p "\n## Per idea\n")
(p "Proofs = distinct cited tests (wildcards expanded). A test cited by two ideas counts for both.\n")
(p "| # | idea | proofs | meaningful | red | timeout | flaky | verdict |\n|---|---|---|---|---|---|---|---|")
(def idea-rows
  (for [[i ss] (sort idea-map) :let [cs (map #(:census/class (by %)) ss) f (frequencies cs) mn (get f :meaningful 0)]]
    {:i i :n (count ss) :f f :mn mn}))
(doseq [{:keys [i n f mn]} idea-rows]
  (p "| " i " | " (clip (titles i) 70) " | " n " | " mn " | " (get f :red 0) " | " (get f :timeout 0) " | " (get f :flaky 0) " | "
     (cond (zero? n) "no cited proofs" (zero? mn) "NO meaningful proof" (= mn n) "all meaningful" :else (str (- n mn) " not green")) " |"))
(let [none (remove #(pos? (:n %)) (map #(assoc % :i (:i %)) idea-rows))
      zero (filter #(and (pos? (:n %)) (zero? (:mn %))) idea-rows)]
  (p "\n## Ideas lacking a meaningful proof\n")
  (p "Ideas with no cited deftest at all (their spec lives in prose and must be written as proofs before porting): "
     (if (seq none) (str/join ", " (map #(str "#" (:i %) " " (clip (titles (:i %)) 50)) none)) "none") ".\n")
  (p "Weakest health (fewer than 70% of cited proofs meaningful): "
   (str/join ", " (for [{:keys [i n mn]} idea-rows :when (< mn (* 0.7 n))] (str "#" i " (" mn "/" n ")"))) ".\n")
  (p "Ideas whose cited proofs are all non-green: " (if (seq zero) (str/join ", " (map #(str "#" (:i %)) zero)) "none") ".\n"))
(spit (str cdir "tests-run.md") (str md))
(println tot (count rows) (map (juxt :i :n :mn) idea-rows))

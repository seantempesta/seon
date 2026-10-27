;; Lab: score merges into main and live runs against notes/rules-score/rules.edn.
;; Run: bb notes/rules-score/score.clj [REPO] [MEASURE-DIR]
;; Reads git and files only; writes nothing but stdout. The score is computed, never stored.
(require '[clojure.java.shell :refer [sh]] '[clojure.string :as str] '[clojure.edn :as edn])

(def here (-> *file* java.io.File. .getParentFile .getPath))
(def repo (or (first *command-line-args*) "/Users/sean/src/seon-next"))
(def measure-dir (or (second *command-line-args*)
                     "/private/tmp/claude-501/-Users-sean-src-seon-next/2489cf55-c241-4f3c-b807-be3323bdfd1f/scratchpad/measure"))
(def rules (edn/read-string (slurp (str here "/rules.edn"))))
(def scored (into {} (map (juxt :id identity)) (:scored rules)))

(defn git [& args]
  (let [{:keys [out exit err]} (apply sh "git" "-C" repo args)]
    (when-not (zero? exit) (binding [*out* *err*] (println "git" args err)))
    out))
(defn lines [s] (if (str/blank? s) [] (str/split-lines s)))

;; ---- facts from a tree -------------------------------------------------------------------
(def def-re #"^\((?:[\w.-]+/)?def[\w-]*\s+(?:\^(?:\{[^}]*\}|\S+)\s+)*([^\s\[\(\)\"]+)")
(defn def-names
  "Every top-level def name under src at REV: {file #{name}}."
  [rev]
  (reduce (fn [m l] (let [[_ f code] (re-find #"^[^:]+:([^:]+):(.*)$" l)
                          n (some-> (re-find def-re code) second)]
                      (if n (update m f (fnil conj #{}) n) m)))
          {} (lines (git "grep" "-E" "^\\((\\w|[.-])*/?def" rev "--" "src"))))
(defn all-names [by-file] (into #{} cat (vals by-file)))
(defn deftests [rev]
  (into #{} (keep #(second (re-find #"^\S+:\(deftest\s+(?:\^\S+\s+)*(\S+)" %)))
        (lines (git "grep" "-E" "^\\(deftest " rev "--" "test"))))

(defn forms
  "Top-level forms of FILE at REV, split at lines starting with '(': {name text}."
  [rev file]
  (let [{:keys [out exit]} (sh "git" "-C" repo "show" (str rev ":" file))]
    (if-not (zero? exit) {}
      (->> (partition-by (let [n (volatile! 0)] #(do (when (str/starts-with? % "(") (vswap! n inc)) @n))
                         (lines out))
           (keep (fn [ls] (when-let [n (some-> (re-find def-re (first ls)) second)] [n (str/join "\n" ls)])))
           (into {})))))

;; ---- facts from a diff -------------------------------------------------------------------
;; a deleted file's "+++" is /dev/null; take its name from "---"
(defn diff-all [p m & paths]
  (let [raw (lines (apply git "diff" "-U0" "--no-color" p m "--" paths))]
    (loop [[l & more] raw old nil f nil acc {}]
      (cond (nil? l) acc
            (str/starts-with? l "--- ") (recur more (let [x (subs l 4)] (when (not= x "/dev/null") (subs x 2))) nil acc)
            (str/starts-with? l "+++ ") (recur more old (let [x (subs l 4)] (if (= x "/dev/null") old (subs x 2))) acc)
            (and f (str/starts-with? l "+")) (recur more old f (update-in acc [f :+] (fnil conj []) (subs l 1)))
            (and f (str/starts-with? l "-")) (recur more old f (update-in acc [f :-] (fnil conj []) (subs l 1)))
            :else (recur more old f acc)))))

(defn count-re [re ls] (count (filter #(re-find re %) ls)))
(defn net [d files re] (- (count-re re (mapcat #(get-in d [% :+]) files)) (count-re re (mapcat #(get-in d [% :-]) files))))

(def clj? #(re-find #"\.clj[cs]?$" %))
(def main-names (all-names (def-names "main")))
(def main-log (git "log" "--format=%H %s%n%b" "main"))
(def merge-log (lines (slurp (str repo "/notes/merge-log.md"))))

(defn merge-log-line [sha]
  (some #(when (str/includes? (subs % 0 (min 25 (count %))) (subs sha 0 9)) %) merge-log))

(defn merge-facts [sha]
  (let [p (str sha "^1") m sha
        files (->> (lines (git "diff" "--name-status" p m "--" "src")) (map #(str/split % #"\t")))
        src (filter #(clj? (last %)) files)
        d (diff-all p m "src" "resources/seon/config/default.edn" "resources/seon/schemas")
        srcfiles (filter #(and (str/starts-with? % "src/") (clj? %)) (keys d))
        added (mapcat #(get-in d [% :+]) srcfiles)
        removed (mapcat #(get-in d [% :-]) srcfiles)
        pdefs (def-names p) pall (all-names pdefs) mdefs (def-names m) mall (all-names mdefs)
        new-defs (remove pall mall) gone-defs (remove mall pall)
        changed (count (for [f srcfiles :let [a (forms p f) b (forms m f)] [n t] b :when (and (a n) (not= t (a n)))] n))
        reused (into #{} (for [f srcfiles
                               l (get-in d [f :+])
                               :when (not (re-find def-re l))
                               [tok q nm] (re-seq #"(?:([A-Za-z][\w.-]*)/)?([A-Za-z*+!?<>=-][\w*+!?<>=.-]*)" l)
                               :let [nm (or nm tok)]
                               :when (if q (pall nm) (get-in pdefs [f nm]))
                               :when (not (some #{nm} new-defs))]
                           nm))
        pins (count (filter #(re-find #"^:160000 160000" %) (lines (git "diff" "--raw" p m "--" "reference-code"))))
        cfg (let [f "resources/seon/config/default.edn"] (net d [f] #"^\s*\{?\s*:seon\.config[\w.]*/[\w?!*-]+\s"))
        schema (net d (filter #(str/starts-with? % "resources/seon/schemas") (keys d)) #"^\s*\{?\s*:[\w.]+/[\w?!*<>-]+\s+\[")
        t-before (deftests p) t-after (deftests m)
        msg (git "log" "-1" "--format=%B" sha)
        ml (merge-log-line sha)
        verdict-text (or ml msg)
        reverted? (or (boolean (re-find (re-pattern (str "(?i)revert[^\\n]*" (subs sha 0 9))) main-log))
                      (boolean (some-> ml (str/includes? "REVERTED"))))
        red? (boolean (re-find #"\bRED\b" (or ml "")))
        green? (boolean (re-find #"(?i)prove (OK|\S+ \S+ OK)|all green|prove: all green|run all gre" verdict-text))
        net-src (- (count added) (count removed))]
    {:sha (subs sha 0 10)
     :branch (second (re-find #"Merge (?:branch ')?([\w/.-]+)" (first (lines msg))))
     :net-src net-src :src+ (count added) :src- (count removed)
     :net-ns (- (count (filter #(= "A" (first %)) src)) (count (filter #(= "D" (first %)) src)))
     :config cfg :schema schema
     :new-defs (count new-defs) :gone-defs (count gone-defs) :changed-defs changed
     :alive (when (seq new-defs) (/ (count (filter main-names new-defs)) (double (count new-defs))))
     :gone-stay (count (remove main-names gone-defs))
     :reused (count reused)
     :new-tests (count (remove t-before t-after)) :removed-tests (count (remove t-after t-before))
     :catch (net d srcfiles #"\(catch\s")
     :clock (net d srcfiles #"System/(currentTimeMillis|nanoTime)|Thread/sleep|\(\s*(?:[\w.-]+/)?timeout\s")
     :dates (net d srcfiles #"\b20\d\d-\d\d-\d\d\b")
     :pins pins
     :green green? :red red? :reverted reverted?
     :names-deletion (boolean (re-find #"(?i)delet|replac|instead of|one path" msg))}))

(defn clampw [id x] (let [{:keys [weight cap]} (scored id) v (* weight x)] (max (- cap) (min cap v))))
(defn merge-points [f]
  {:outcome/merged-green (clampw :outcome/merged-green (if (and (:green f) (not (:reverted f))) 1 0))
   :outcome/reverted (clampw :outcome/reverted (if (:reverted f) 1 0))
   :outcome/added-forms-survive (if (:alive f) (clampw :outcome/added-forms-survive (- (* 2 (:alive f)) 1)) 0)
   :outcome/deletions-stick (clampw :outcome/deletions-stick (:gone-stay f))
   :reuse/calls-existing-vars (clampw :reuse/calls-existing-vars (:reused f))
   :reuse/improve-not-add (clampw :reuse/improve-not-add (- (:changed-defs f) (:new-defs f)))
   :growth/net-src-lines (clampw :growth/net-src-lines (:net-src f))
   :growth/new-namespaces (clampw :growth/new-namespaces (max 0 (:net-ns f)))
   :growth/new-config-keys (clampw :growth/new-config-keys (max 0 (:config f)))
   :growth/new-schema-attrs (clampw :growth/new-schema-attrs (max 0 (:schema f)))
   :growth/names-what-it-replaces (clampw :growth/names-what-it-replaces (if (and (pos? (:net-src f)) (not (:names-deletion f))) 1 0))
   :proofs/added-with-change (clampw :proofs/added-with-change (:new-tests f))
   :tests/merged-with-a-red (clampw :tests/merged-with-a-red (if (:red f) 1 0))
   :laws/new-catch (clampw :laws/new-catch (max 0 (:catch f)))
   :laws/new-clock (clampw :laws/new-clock (max 0 (:clock f)))
   :laws/dates-in-src (clampw :laws/dates-in-src (max 0 (:dates f)))
   :laws/fix-in-dependency (clampw :laws/fix-in-dependency (:pins f))})

;; ---- live runs ---------------------------------------------------------------------------
(def stale-db1 #{"seon.model/the-transport-failure-docstring-is-true" "seon.context/a-proof-that-never-ran-says-so"
                 "seon.print/a-long-name-cut-says-its-chars" "seon.print/a-map-shows-its-first-entry"})
(defn read-edn [f] (let [x (java.io.File. f)] (when (.exists x) (edn/read-string (slurp x)))))
(defn runs []
  (for [tag ["staged2" "whole2" "db1"]
        :let [rs (read-edn (str measure-dir "/" tag ".edn"))
              reps (read-edn (str measure-dir "/" tag ".repeats.edn"))]
        r rs
        :let [n (count (:per-turn r))]
        :when (pos? n)]
    (let [green (contains? #{:green :merged} (:outcome r))
          turns (if (neg? (:turns r)) n (:turns r))
          rep (when reps (count (filter #(= (:run r) (:run %)) reps)))
          errs (reduce + (keep last (:per-turn r)))
          pts {:run/green (clampw :run/green (if green 1 0))
               :run/turns-to-green (clampw :run/turns-to-green (if green (- 20 turns) (- turns)))
               :run/capped (clampw :run/capped (if (#{:cap :waiting} (:outcome r)) 1 0))
               :run/repeats (if rep (clampw :run/repeats rep) 0)
               :run/errors (clampw :run/errors errs)}]
      {:set tag :task (:task r) :outcome (:outcome r) :turns turns :repeats (or rep :not-measured) :errors errs
       :stale (and (= tag "db1") (boolean (stale-db1 (:task r))))
       :points pts :score (reduce + (vals pts))})))

;; ---- known judgments ---------------------------------------------------------------------
(def judged
  {"9c47ace0b8" [:low "view-parts: the parts layer, deleted later as a second system (b20d48ab82)"]
   "330ab1b3f0" [:low "ctx-atom 4.1 derived members: superseded within the hour (no derived data in ctx)"]
   "59a742ee30" [:low "view-reads-story: reads the derived members, superseded with them"]
   "98c6b97c0c" [:low "delete-stages: reverted at the owner's word (255e4d8ff2)"]
   "830ef818e8" [:high "model-reads-the-page 4.2: one path, ai faces and seon.screen deleted, src -109"]
   "b8c6180959" [:high "model-reads-the-page: agent-less branch deleted, src -44"]
   "6929ca4e93" [:high "ctx-atom: no derived data, story/related/born deleted, src -53"]
   "fbb279900d" [:high "delete-dead-merge-path: src -53, self-review worth it"]
   "11d34aacd5" [:high "self-review worth it"] "a2b0f78892" [:high "self-review worth it"]
   "205b0dc41d" [:high "self-review worth it"] "fd5fe513f4" [:high "self-review worth it"]
   "0595c749d7" [:high "self-review worth it"] "79a2aa9a5e" [:low "self-review questionable"]})

(defn -main []
  (let [shas (concat (lines (git "log" "--merges" "--first-parent" "--format=%H" "620fb31dda..main"))
                     (map #(str/trim (git "rev-parse" %)) ["11d34aacd" "a2b0f7889" "205b0dc41" "fd5fe513f" "fbb279900" "0595c749d" "79a2aa9a5"]))
        scoredm (->> shas
                     (map (fn [s] (let [f (merge-facts s) pts (merge-points f)]
                                    (assoc f :points pts :score (Math/round (double (reduce + (vals pts))))))))
                     (sort-by :score >) vec)
        n (count scoredm)]
    (println "## Merges, ranked (" n ")")
    (println "| rank | sha | branch | score | net src | new/changed/gone defs | alive | reused | new tests | removed tests (gated) | catch/clock | verdict | judged |")
    (println "|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    (doseq [[i f] (map-indexed vector scoredm)]
      (println (str "| " (inc i) " | " (:sha f) " | " (str/replace (or (:branch f) "") "jolt/" "") " | " (:score f)
                    " | " (:net-src f) " | " (:new-defs f) "/" (:changed-defs f) "/" (:gone-defs f)
                    " | " (if (:alive f) (format "%.2f" (:alive f)) "-") " | " (:reused f) " | " (:new-tests f) " | " (:removed-tests f)
                    " | " (:catch f) "/" (:clock f)
                    " | " (cond (:reverted f) "reverted" (:red f) "red recorded" (:green f) "green" :else "?")
                    " | " (some-> (judged (:sha f)) first name) " |")))
    (println "\n## Known judgments")
    (let [rank (into {} (map-indexed (fn [i f] [(:sha f) (inc i)]) scoredm))
          sc (into {} (map (juxt :sha :score)) scoredm)
          hi (for [[s [j]] judged :when (= j :high) :when (sc s)] s)
          lo (for [[s [j]] judged :when (= j :low) :when (sc s)] s)
          pairs (for [h hi l lo] (compare (sc h) (sc l)))
          auc (/ (+ (count (filter pos? pairs)) (* 0.5 (count (filter zero? pairs)))) (double (count pairs)))
          median (:score (nth scoredm (quot n 2)))]
      (println "| sha | judged | score | rank of" n "| agrees (high above median / low below) | why judged |")
      (println "|---|---|---|---|---|---|")
      (doseq [[s [j why]] (sort-by (comp rank key) judged) :when (sc s)]
        (println "|" s "|" (name j) "|" (sc s) "|" (rank s) "|"
                 (if (= j :high) (> (sc s) median) (< (sc s) median)) "|" why "|"))
      (println (format "\nPairwise concordance (AUC, high above low): %.2f over %d pairs; median score %d" auc (count pairs) median)))
    (println "\n## Live runs, ranked")
    (println "| set | task | outcome | turns | repeats | errors | stale (db1) | score |")
    (println "|---|---|---|---|---|---|---|---|")
    (let [rs (sort-by :score > (runs))]
      (doseq [r rs]
        (println "|" (:set r) "|" (:task r) "|" (name (:outcome r)) "|" (:turns r) "|" (:repeats r) "|" (:errors r) "|" (:stale r) "|" (:score r) "|"))
      (let [st (filter :stale rs) all (count rs)
            ranks (keep-indexed (fn [i r] (when (:stale r) (inc i))) rs)]
        (println (format "\nStale db1 runs: %d runs, %d turns, ranks %s of %d, mean score %.1f (all runs %.1f)"
                         (count st) (reduce + (map :turns st)) (vec ranks) all
                         (double (/ (reduce + (map :score st)) (max 1 (count st))))
                         (double (/ (reduce + (map :score rs)) all))))))))

(-main)

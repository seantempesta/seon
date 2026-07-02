#!/usr/bin/env bb
;;; acme/gym/diffusion_gym.bb — a CONSUMER-OWNED diffusion-gym driver.
;;;
;;; The diffusion gym = a SCENARIO (a task + canned/real model responses) + a
;;; PREDICATE set + a SCORECARD, scored through Seon's CO-LOCATED ORACLE
;;; (`bin/oracle-server` parse-raw + the `worker-oracle-eval` bundle). This
;;; driver lets a THIRD PARTY define their OWN scenario + predicates in `acme/`
;;; and score model output WITHOUT touching `src/seon` — the oracle is reached
;;; purely by PATH (it is a pure, cwd-independent function of its input).
;;;
;;; Zero-coupling proof (see docs/prds/diffusion-dynamic-context/research/
;;; gym-third-party-adoption-2026-06-28.md §"Oracle reach"):
;;;   - `bin/oracle-server` puts seon's `src/` on the bb classpath RELATIVE TO
;;;     THE SCRIPT, so it loads `seon.repl.internal` no matter the cwd. Pure
;;;     parse — no DB, no pod, no cluster. Reachable by absolute path.
;;;   - `out/worker-oracle-eval/main.js` resolves its bootstrap cache from
;;;     `SEON_BOOTSTRAP` (we pass `$SEON_ROOT/out/bootstrap`), so it too runs
;;;     from any cwd. Both are build artifacts of the seon checkout the
;;;     consumer already has — neither is the default CLUSTER (7890); the gym
;;;     never touches a live store.
;;;
;;; Usage:
;;;   SEON_ROOT=/path/to/seon bb acme/gym/diffusion_gym.bb <scenario.edn> [--eval] [--assert]
;;;   bin/acme gym-diffusion <scenario.edn> [--eval] [--assert]   ; SEON_ROOT wired
;;;
;;; --eval   also run the EVAL tier (heavier self-host bundle); off by default
;;;          so a parse+structural scorecard stays fast.
;;; --assert exit non-zero unless the verdict == the scenario's
;;;          :acme.gym.scenario/expect-verdict (the offline self-test gate).
;;;
;;; Output: a `SEON-GYM SCORECARD …` line per arm + a durable EDN card under
;;; `tmp/acme/gym-card-<scenario>-<run>.edn`. Pass/fail is the DATA; exit 0
;;; iff a valid scorecard was produced (or, with --assert, iff the verdict matched).

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[babashka.process :as p])

;;; --------------------------------------------------------------------------
;;; Paths — the oracle binaries inside the seon checkout (by PATH, not import).
;;; --------------------------------------------------------------------------
(def seon-root
  (or (System/getenv "SEON_ROOT")
      ;; default: walk up from this script until we find bin/oracle-server.
      (loop [d (fs/parent (fs/absolutize *file*))]
        (cond
          (nil? d) (throw (ex-info "set SEON_ROOT — could not locate seon checkout" {}))
          (fs/exists? (fs/file d "bin" "oracle-server")) (str d)
          :else (recur (fs/parent d))))))

(def oracle-parse-bin (str (fs/file seon-root "bin" "oracle-server")))
(def oracle-eval-bin  (str (fs/file seon-root "out" "worker-oracle-eval" "main.js")))
(def oracle-bootstrap (str (fs/file seon-root "out" "bootstrap")))

;;; --------------------------------------------------------------------------
;;; Oracle clients — spawn ONCE per tier, feed N JSON lines, read N lines back.
;;; The wire is byte-identical across bb (parse) and node (eval): one JSON
;;; object per line, `id` echoed (src/seon/worker_eval.cljs + bin/oracle-server).
;;; --------------------------------------------------------------------------
(defn- jlines [reqs]
  (str (str/join "\n" (map json/generate-string reqs)) "\n"))

(defn- read-jlines [out]
  (->> (str/split-lines (str/trim out))
       (filter #(str/starts-with? (str/triml %) "{"))
       (map #(json/parse-string % true))))

(defn oracle-parse-batch
  "codes -> vector of {:forms n :errors [...]} aligned to input order. parse-raw
   = NO fence strip, spans index the EXACT string (the canvas basis)."
  [codes]
  (let [reqs (map-indexed (fn [i c] {:op "parse-raw" :id i :code c}) codes)
        out  (:out @(p/process ["bb" oracle-parse-bin]
                               {:in (jlines reqs) :out :string :err :string}))
        by-id (into {} (map (juxt :id identity)) (read-jlines out))]
    (mapv (fn [i] (let [r (by-id i)] {:forms (:forms r 0) :errors (:errors r)}))
          (range (count codes)))))

(defn oracle-eval-batch
  "codes -> vector of {:ok bool :error {...}} aligned to input order, via the
   self-host eval bundle in --serve mode (SEON_BOOTSTRAP pins the cache)."
  [codes]
  (let [reqs (map-indexed (fn [i c] {:op "eval" :id i :code c}) codes)
        out  (:out @(p/process ["node" oracle-eval-bin "--serve"]
                               {:in (jlines reqs) :out :string :err :string
                                :extra-env {"SEON_BOOTSTRAP" oracle-bootstrap}}))
        by-id (into {} (map (juxt :id identity)) (read-jlines out))]
    (mapv (fn [i] (let [r (by-id i)] {:ok (boolean (:ok r)) :error (:error r)}))
          (range (count codes)))))

;;; --------------------------------------------------------------------------
;;; Scoring — predicates are DATA (consumer-authored). Each predicate is a fn
;;; of the code text + its oracle results -> boolean.
;;; --------------------------------------------------------------------------
(def fence #"(?s)```(?:clojure|clj)?\s*(.*?)```")

(defn strip-fence [text]
  (let [m (re-find fence (or text ""))]
    (str/trim (if m (second m) (or text "")))))

(defn vacuous?
  "F2: a spec PRESENT but not a faithful contract — empty [:map] or a bare :any
   in the schema region. Parses + instruments GREEN yet rejects nothing."
  [code]
  (and (str/includes? code ":malli/schema")
       (boolean (or (re-find #"\[:map\s*\]" code)
                    (re-find #":any\b" code)))))

(defn eval-predicate
  "pred (data) + the attempt's code/parse/eval -> boolean, or nil if the
   predicate cannot be evaluated (e.g. :oracle-eval without --eval)."
  [pred code parse evalr]
  (case (:acme.gym.predicate/kind pred)
    :oracle-parse (and (>= (:forms parse 0) 1) (empty? (:errors parse)))
    :oracle-eval  (when evalr (:ok evalr))
    :contains     (str/includes? code (:acme.gym.predicate/needle pred))
    :absent       (not (str/includes? code (:acme.gym.predicate/needle pred)))
    :not-vacuous  (not (vacuous? code))
    (throw (ex-info "unknown predicate kind" {:pred pred}))))

(defn score-attempt [scenario code parse evalr]
  (let [preds   (:acme.gym.scenario/predicates scenario)
        results (into {} (map (fn [pred]
                                [(:acme.gym.predicate/id pred)
                                 (eval-predicate pred code parse evalr)]))
                      preds)
        ;; "faithful" = every named gate that is APPLICABLE (non-nil) holds.
        gates   (:acme.gym.scenario/faithful-when scenario)
        applic  (->> gates (map results) (remove nil?))
        faithful (and (seq applic) (every? true? applic))]
    (assoc results :faithful faithful)))

;;; --------------------------------------------------------------------------
;;; Run / aggregate / verdict.
;;; --------------------------------------------------------------------------
(defn run-scenario [scenario eval?]
  (let [arms      (:acme.gym.scenario/arms scenario)
        ;; flatten every arm's responses into ONE oracle batch (one spawn each).
        flat      (vec (for [[arm responses] arms, [idx text] (map-indexed vector responses)]
                         {:arm arm :idx idx :code (strip-fence text)}))
        codes     (mapv :code flat)
        parses    (oracle-parse-batch codes)
        evals     (if eval? (oracle-eval-batch codes) (vec (repeat (count codes) nil)))
        scored    (mapv (fn [{:keys [arm idx code]} pr ev]
                          {:arm arm :idx idx
                           :score (score-attempt scenario code pr ev)})
                        flat parses evals)]
    (group-by :arm scored)))

(defn- rate [rows k]
  (let [vs (keep #(get-in % [:score k]) rows)]
    (if (empty? vs) 0.0 (/ (double (count (filter true? vs))) (count rows)))))

(defn aggregate [scenario by-arm]
  (into {}
        (for [[arm rows] by-arm]
          [arm (merge {:arm arm :n (count rows)
                       :faithful-rate (rate rows :faithful)}
                      (into {} (for [pred (:acme.gym.scenario/predicates scenario)
                                     :let [id (:acme.gym.predicate/id pred)]]
                                 [id (rate rows id)])))])))

(defn verdict [scenario agg]
  (let [{:keys [treatment baseline margin]
         :or {margin 0.10}} (:acme.gym.scenario/compare scenario)
        t (get-in agg [treatment :faithful-rate])
        b (get-in agg [baseline :faithful-rate])
        d (- t b)]
    {:treatment treatment :baseline baseline :delta d :margin margin
     :decision (cond (>= d margin) :EARNS
                     (<= d 0)      :KILL
                     :else         :MARGINAL)}))

;;; --------------------------------------------------------------------------
;;; Emit.
;;; --------------------------------------------------------------------------
(defn run-id [] (subs (str (random-uuid)) 0 8))

(defn -main [& argv]
  (let [eval?    (boolean (some #{"--eval"} argv))
        assert?  (boolean (some #{"--assert"} argv))
        path     (first (remove #(str/starts-with? % "--") argv))
        _        (when-not path (println "usage: diffusion_gym.bb <scenario.edn> [--eval] [--assert]") (System/exit 2))
        scenario (edn/read-string (slurp path))
        scen-id  (name (:acme.gym.scenario/id scenario))
        rid      (run-id)
        by-arm   (run-scenario scenario eval?)
        agg      (aggregate scenario by-arm)
        v        (verdict scenario agg)
        card     {:acme.gym.scorecard/scenario (:acme.gym.scenario/id scenario)
                  :acme.gym.scorecard/run-id rid
                  :acme.gym.scorecard/eval-tier eval?
                  :acme.gym.scorecard/oracle-root seon-root
                  :acme.gym.scorecard/arms (vec (vals agg))
                  :acme.gym.scorecard/verdict v}
        out-dir  (fs/file "tmp" "acme")
        out-file (str (fs/file out-dir (str "gym-card-" scen-id "-" rid ".edn")))]
    (fs/create-dirs out-dir)
    (spit out-file (with-out-str (clojure.pprint/pprint card)))
    (println (format "\n  DIFFUSION-GYM SCORECARD  scenario=%s  run=%s  eval-tier=%s"
                     scen-id rid (boolean eval?)))
    (doseq [arm-id (keys (:acme.gym.scenario/arms scenario))
            :let [a (get agg arm-id)]]
      (println (format "  SEON-GYM SCORECARD  arm=%-20s n=%d  faithful-rate=%.3f"
                       (name arm-id) (:n a) (:faithful-rate a))))
    (println (format "\n  Δ(%s − %s) faithful = %+.3f   (margin %.2f)"
                     (name (:treatment v)) (name (:baseline v)) (:delta v) (:margin v)))
    (println (format "  VERDICT [%s]" (name (:decision v))))
    (println (format "  CARD-FILE %s" out-file))
    (if assert?
      (let [want (:acme.gym.scenario/expect-verdict scenario)]
        (if (= want (:decision v))
          (do (println (format "  ASSERT PASS — verdict == expect-verdict (%s)" (name want)))
              (System/exit 0))
          (do (println (format "  ASSERT FAIL — got %s, expected %s" (name (:decision v)) (name want)))
              (System/exit 1))))
      (System/exit 0))))

(apply -main *command-line-args*)

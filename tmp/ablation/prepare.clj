(ns ablation.prepare
  "Recover the W1 opening history and construct the four ablation prompts."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.render.history-test]
            [seon.render.walk :as walk])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private source-log
  "tmp/orchestrator/w1-integration-stdout.log")

(def ^:private output-directory "tmp/ablation/generated")
(def ^:private prompt-prefix "my.agents.w1-history-proof-5=> ")
(def ^:private opening-start "+BEGIN OPENING HISTORY VERBATIM\n")
(def ^:private opening-end "\n+END OPENING HISTORY VERBATIM")

(def task
  (str
   "In your namespace, define a durable contracted function named "
   "cluster-agent-count that takes no arguments and returns the count of "
   "cluster facts carrying :seon.cluster.agent/id. Call it once. Then query "
   "the program graph for your function's :seon.fn/spec and return that "
   "contract value once. Complete only after all three operations succeed; "
   "finish by calling my.run/complete with a plain string reply that names "
   "the function, the count, and the contract."))

(def ^:private task-entry
  {:seon.render.history/form '(my.message/read "minimum-context-task")
   :seon.render.history/bytes
   (str prompt-prefix "(my.message/read \"minimum-context-task\")\n"
        "From outside this cluster to w1-history-proof-5: " task)})

(defn- sha-256
  [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- recover-opening
  [log-text]
  (let [begin (str/index-of log-text opening-start)
        _ (assert (some? begin) "The W1 opening-history start marker is absent.")
        content-start (+ begin (count opening-start))
        end (str/index-of log-text opening-end content-start)
        _ (assert (some? end) "The W1 opening-history end marker is absent.")]
    (->> (str/split-lines (subs log-text content-start end))
         (map #(if (str/starts-with? % "+") (subs % 1) %))
         (str/join "\n"))))

(defn- entry
  [text form-source]
  (let [heading (str prompt-prefix form-source)
        start (str/index-of text heading)
        _ (assert (some? start) (str "History entry is absent: " form-source))
        next-start (str/index-of text (str "\n\n" prompt-prefix)
                                 (+ start (count heading)))
        entry-bytes (subs text start (or next-start (count text)))]
    {:seon.render.history/form (edn/read-string form-source)
     :seon.render.history/bytes entry-bytes}))

(def ^:private toolkit-form
  "(db/q (quote [:find [(pull ?entity [*]) ...] :where [?entity :seon.cluster/toolkit]]) db)")

(defn- remove-toolkit-detail
  [text]
  (let [heading (str prompt-prefix toolkit-form)]
    (loop [remaining text
           removed 0]
      (if-let [start (str/index-of remaining heading)]
        (let [next-start (str/index-of remaining (str "\n\n" prompt-prefix)
                                       (+ start (count heading)))
              _ (assert (some? next-start)
                        "A toolkit detail entry has no following history entry.")]
          (recur (str (subs remaining 0 start)
                      (subs remaining (+ next-start 2)))
                 (inc removed)))
        (do
          (assert (= 7 removed)
                  (str "Expected seven distance-2 toolkit entries; removed "
                       removed "."))
          remaining)))))

(defn- append-task
  [prompt]
  (str prompt "\n\n" (:seon.render.history/bytes task-entry)))

(defn- ordered-clean
  [entries]
  (let [ordered (walk/order-history (vec entries))
        clean? (#'seon.render.history-test/define-before-use? ordered)]
    (assert clean? "The variant violates define-before-use.")
    ordered))

(defn- construct-variant
  [variant-name source-history entries]
  (let [entries (ordered-clean (conj (vec entries) task-entry))
        prompt (append-task source-history)]
    {:minimum-context.variant/name variant-name
     :minimum-context.variant/model "deepseek-v4-flash"
     :minimum-context.variant/task task
     :minimum-context.variant/entries entries
     :minimum-context.variant/prompt prompt
     :minimum-context.variant/prompt-tokens (tokens/estimate prompt)
     :minimum-context.variant/define-before-use? true}))

(defn- write-variant!
  [variant-value]
  (let [variant-label (name (:minimum-context.variant/name variant-value))
        prompt-file (io/file output-directory (str variant-label ".txt"))]
    (spit prompt-file (:minimum-context.variant/prompt variant-value))
    (assoc (dissoc variant-value :minimum-context.variant/prompt)
           :minimum-context.variant/prompt-file (.getPath prompt-file))))

(defn -main
  "Recover, verify, construct, and token-measure every ablation variant."
  [& _]
  (let [opening (recover-opening (slurp source-log))
        _ (assert (= 43627 (count opening))
                  "Recovered history does not have 43,627 Unicode characters.")
        _ (assert (= 43810 (alength (.getBytes opening StandardCharsets/UTF_8)))
                  "Recovered history does not have 43,810 UTF-8 bytes.")
        _ (assert (= "d5077f2638d83245b515f208132a75bd3cb1dfc04f931e0e39792519b5318625"
                     (sha-256 opening))
                  "Recovered history digest differs from the W1 capture.")
        help (entry opening "(help)")
        namespace-entry (entry opening "(in-ns 'my.agents.w1-history-proof-5)")
        dir-message (entry opening "(dir my.message)")
        dir-run (entry opening "(dir my.run)")
        own-namespace
        (entry opening
               "(db/q (quote [:find [(pull ?entity [*]) ...] :where [?entity :seon.cluster.agent/namespace]]) db)")
        requires
        (entry opening
               "(db/q (quote [:find [(pull ?entity [*]) ...] :where [?entity :seon.ns/requires]]) db)")
        full (construct-variant :full opening [])
        half (construct-variant :half (remove-toolkit-detail opening)
                                [dir-message dir-run])
        quarter (construct-variant
                 :quarter
                 (str/join "\n\n"
                           (map :seon.render.history/bytes
                                [help namespace-entry dir-message dir-run
                                 own-namespace requires]))
                 [help namespace-entry dir-message dir-run own-namespace requires])
        floor (construct-variant
               :floor
               (str/join "\n\n"
                         (map :seon.render.history/bytes
                              [help namespace-entry requires]))
               [help namespace-entry requires])
        _ (.mkdirs (io/file output-directory))
        variants (mapv write-variant! [full half quarter floor])
        measurements
        (mapv #(select-keys % [:minimum-context.variant/name
                               :minimum-context.variant/prompt-tokens
                               :minimum-context.variant/define-before-use?
                               :minimum-context.variant/prompt-file])
              variants)]
    (spit (io/file output-directory "opening-history.txt") opening)
    (spit (io/file output-directory "variants.edn")
          (str (pr-str variants) "\n"))
    (spit (io/file output-directory "measurements.edn")
          (str (pr-str measurements) "\n"))
    (println (pr-str measurements))))

#!/usr/bin/env bb
;; Port-cost inventory: classify every public defn in src/my/**.cljs as
;; :pure (portable as-is), :db-boundary (calls seon.db / other ^:async
;; host verbs — .cljc-able through a protocol client), or :js-bound
;; (js interop / Promise / ^:async idioms in the body).
(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(defn defn-blocks [s]
  ;; split on top-level (defn — crude but adequate for counting; each block
  ;; runs until the next top-level open paren at column 0.
  (let [lines (str/split-lines s)
        tops (keep-indexed (fn [i l] (when (re-find #"^\((defn|def )" l) i)) lines)
        tops (vec tops)]
    (for [[a b] (map vector tops (concat (rest tops) [(count lines)]))
          :let [block (str/join "\n" (subvec (vec lines) a b))]
          :when (str/starts-with? block "(defn")]
      block)))

(defn classify [block]
  (let [private? (str/includes? block "defn-")
        async? (re-find #"\^:async|\(await |js/Promise" block)
        js? (re-find #"js/|#js|\(\.\-|\(\. |\(\.[a-zA-Z]" block)
        db? (re-find #"db/transact!|db/query|db/pull|db/entity|db/db\b|blob/" block)]
    {:name (second (re-find #"\(defn-? \^?[:a-z]*\s*([^\s]+)" block))
     :private? (boolean private?)
     :class (cond
              (and async? db?) :db-boundary-async
              async? :js-async
              js? :js-interop
              db? :db-boundary
              :else :pure)}))

(def results
  (for [f (fs/glob "src/my" "**.cljs")
        :let [s (slurp (str f))]
        b (defn-blocks s)]
    (assoc (classify b) :file (str f))))

(def public-results (remove :private? results))

(println "TOTAL public defns:" (count public-results))
(doseq [[k v] (sort-by (comp - val) (frequencies (map :class public-results)))]
  (println (format "  %-18s %3d  (%.0f%%)" k v (* 100.0 (/ v (count public-results))))))
(println)
(println "Per-file breakdown:")
(doseq [[f rs] (sort-by key (group-by :file public-results))]
  (println (format "  %-28s total %2d  pure %2d  js %2d  db %2d"
                   (str/replace f "src/my/" "")
                   (count rs)
                   (count (filter #(= :pure (:class %)) rs))
                   (count (filter #(#{:js-interop :js-async} (:class %)) rs))
                   (count (filter #(#{:db-boundary :db-boundary-async} (:class %)) rs)))))
(println)
(println "Sample :pure fns:" (str/join ", " (take 8 (map :name (filter #(= :pure (:class %)) public-results)))))
(println "Sample :js fns:  " (str/join ", " (take 8 (map :name (filter #(#{:js-interop :js-async} (:class %)) public-results)))))

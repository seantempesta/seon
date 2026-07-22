(ns seon.test.source-scan
  "Shared source and namespace-form scanning for conformance tests."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]))

(defn source-files
  "Clojure source paths recursively beneath `dir`."
  [dir]
  (mapcat
    (fn [entry]
      (let [path (.join np dir (.-name entry))]
        (cond
          (.isDirectory entry) (source-files path)
          (re-find #"\.clj[sc]?$" path) [path]
          :else [])))
    (.readdirSync fs dir #js {:withFileTypes true})))

(defn sanitized-ns-form
  "The raw first `ns` form with strings and comments replaced by spaces."
  [source]
  (let [start (.search source #"\(ns(?:\s|$)")]
    (when-not (neg? start)
      (loop [i start depth 0 in-string? false escaped? false in-comment? false out ""]
        (when (< i (count source))
          (let [c (subs source i (inc i))]
            (cond
              in-comment?
              (recur (inc i) depth false false (not= c "\n") (str out " "))

              in-string?
              (cond
                escaped? (recur (inc i) depth true false false (str out " "))
                (= c "\\") (recur (inc i) depth true true false (str out " "))
                (= c "\"") (recur (inc i) depth false false false (str out " "))
                :else (recur (inc i) depth true false false (str out " ")))

              (= c ";")
              (recur (inc i) depth false false true (str out " "))

              (= c "\"")
              (recur (inc i) depth true false false (str out " "))

              (= c "(")
              (recur (inc i) (inc depth) false false false (str out c))

              (= c ")")
              (let [next-depth (dec depth)
                    next-out (str out c)]
                (if (zero? next-depth)
                  next-out
                  (recur (inc i) next-depth false false false next-out)))

              :else
              (recur (inc i) depth false false false (str out c)))))))))

(defn require-matches
  "Matches from a bracket-anchored require pattern in an `ns` form."
  [pattern ns-form]
  (re-seq pattern (or ns-form "")))

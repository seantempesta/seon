(ns seon.diffusion-fence-test
  "Conformance tests for the preserved diffusion-tree require fence."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]
    [cljs.test :refer [deftest is]]
    [clojure.string :as str]))

(def ^:private fenced-require-pattern
  #"\[\s*(seon\.(?:diffusion(?:\.[A-Za-z0-9_.-]+)+|worker-eval|worker-validator))(?=[\s\]\}:])")

(def ^:private allowed-source-paths
  #{"src/seon/worker_eval.cljs"
    "src/seon/worker_validator.cljs"})

(def ^:private allowlist
  [{::file "src/seon/eval.cljs"
    ::date "2026-07-21"
    ::reason "dies at W5 (deletion inventory); remove this row with that band"}])

(defn- source-files
  [dir]
  (mapcat
    (fn [entry]
      (let [path (.join np dir (.-name entry))]
        (cond
          (.isDirectory entry) (source-files path)
          (re-find #"\.clj[sc]?$" path) [path]
          :else [])))
    (.readdirSync fs dir #js {:withFileTypes true})))

(defn- sanitized-ns-form
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

(defn- fenced-requires
  [source]
  (mapv second (re-seq fenced-require-pattern (or (sanitized-ns-form source) ""))))

(defn- allowed-tree-path?
  [file]
  (or (str/starts-with? file "src/seon/diffusion/")
      (contains? allowed-source-paths file)))

(defn- fence-edges
  []
  (let [cwd (.cwd js/process)]
    (->> (source-files (.join np cwd "src"))
         (mapcat
           (fn [path]
             (let [file (str/replace (.relative np cwd path) #"\\" "/")]
               (when-not (allowed-tree-path? file)
                 (map (fn [required-ns]
                        {::file file ::required-ns required-ns})
                      (fenced-requires (.readFileSync fs path "utf-8")))))))
         vec)))

(deftest main-system-never-requires-the-diffusion-tree
  (let [edges (fence-edges)
        allowlisted-files (set (map ::file allowlist))
        allowlisted-edges (filterv #(contains? allowlisted-files (::file %)) edges)
        violations (remove #(contains? allowlisted-files (::file %)) edges)
        used-allowlist-files (set (map ::file allowlisted-edges))
        stale-allowlist-files (remove used-allowlist-files allowlisted-files)]
    (is (empty? violations)
        (str "Main-system source requires fenced namespaces:\n"
             (str/join "\n" (map #(str (::file %) " requires " (::required-ns %))
                                  violations))))
    (is (empty? stale-allowlist-files)
        (str "Remove stale diffusion-fence allowlist rows: "
             (str/join ", " stale-allowlist-files)))))

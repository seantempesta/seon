(ns seon.diffusion-fence-test
  "Conformance tests for the preserved diffusion-tree require fence."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]
    [cljs.test :refer [deftest is]]
    [clojure.string :as str]
    [seon.test.source-scan :as source-scan]))

(def ^:private fenced-require-pattern
  #"\[\s*(seon\.(?:diffusion(?:\.[A-Za-z0-9_.-]+)+|worker-eval|worker-validator))(?=[\s\]\}:])")

(def ^:private allowed-source-paths
  #{"src/seon/worker_eval.cljs"
    "src/seon/worker_validator.cljs"})

(def ^:private allowlist
  [{::file "src/seon/eval.cljs"
    ::date "2026-07-21"
    ::reason "dies at W5 (deletion inventory); remove this row with that band"}])

(defn- fenced-requires
  [source]
  (mapv second (source-scan/require-matches
                 fenced-require-pattern
                 (source-scan/sanitized-ns-form source))))

(defn- allowed-tree-path?
  [file]
  (or (str/starts-with? file "src/seon/diffusion/")
      (contains? allowed-source-paths file)))

(defn- fence-edges
  []
  (let [cwd (.cwd js/process)]
    (->> (source-scan/source-files (.join np cwd "src"))
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

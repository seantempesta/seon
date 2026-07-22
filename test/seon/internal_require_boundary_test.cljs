(ns seon.internal-require-boundary-test
  "Conformance tests for the parent-only `.internal` require law."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]
    [cljs.test :refer [deftest is]]
    [clojure.string :as str]))

(def ^:private internal-require-pattern
  #"\[\s*([A-Za-z0-9_.-]+\.internal)(?=[\s\]\}:])")

(def ^:private allowlist
  [{::required-ns "seon.repl.internal"
    ::date "2026-07-21"
    ::reason "NS-0.5c held — owner ruling: repl-autosuggest is experimental/parked; rename+seam-repairs land at the owner's explicit handoff"}
   {::required-ns "my.plan.internal"
    ::date "2026-07-21"
    ::reason "NS-0.5c held — owner ruling: repl-autosuggest is experimental/parked; rename+seam-repairs land at the owner's explicit handoff"}])

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

(defn- declared-ns
  [ns-form]
  (some->> ns-form
           (re-find #"^\(ns\s+([^\s()]+)")
           second))

(defn- parent-ns
  [internal-ns]
  (subs internal-ns 0 (- (count internal-ns) (count ".internal"))))

(defn- namespace-records
  []
  (let [cwd (.cwd js/process)]
    (->> (source-files (.join np cwd "src"))
         (keep (fn [path]
                 (let [ns-form (sanitized-ns-form (.readFileSync fs path "utf-8"))]
                   (when-let [namespace (declared-ns ns-form)]
                     {::file (str/replace (.relative np cwd path) #"\\" "/")
                      ::namespace namespace
                      ::ns-form ns-form}))))
         vec)))

(defn- internal-nses
  [records]
  (into #{}
        (comp (map ::namespace)
              (filter #(str/ends-with? % ".internal")))
        records))

(defn- require-violations
  [records]
  (let [internals (internal-nses records)]
    (->> records
         (mapcat
           (fn [{::keys [file namespace ns-form]}]
             (keep (fn [[_ required-ns]]
                     (let [expected-parent (parent-ns required-ns)]
                       (when (and (contains? internals required-ns)
                                  (not= namespace expected-parent))
                         {::file file
                          ::namespace namespace
                          ::required-ns required-ns
                          ::expected-parent expected-parent})))
                   (re-seq internal-require-pattern ns-form))))
         vec)))

(defn- violation-message
  [violations]
  (str "Only an internal namespace's parent may require it:\n"
       (str/join
         "\n"
         (map #(str (::file %) ": " (::namespace %) " requires "
                    (::required-ns %) "; expected parent "
                    (::expected-parent %))
              violations))))

(deftest only-parents-require-internal-namespaces
  (let [violations (require-violations (namespace-records))
        allowlisted-nses (set (map ::required-ns allowlist))
        allowlisted-violations (filterv #(contains? allowlisted-nses
                                                   (::required-ns %))
                                         violations)
        unallowlisted-violations (remove #(contains? allowlisted-nses
                                                    (::required-ns %))
                                         violations)
        used-allowlist-nses (set (map ::required-ns allowlisted-violations))
        stale-allowlist-nses (remove used-allowlist-nses allowlisted-nses)]
    (is (empty? unallowlisted-violations)
        (violation-message unallowlisted-violations))
    (is (empty? stale-allowlist-nses)
        (str "Remove stale internal-require allowlist rows: "
             (str/join ", " stale-allowlist-nses)))))

(deftest violation-message-identifies-the-required-repair
  (let [fixture [{::file "src/seon/example.cljs"
                  ::namespace "seon.example"
                  ::ns-form "(ns seon.example (:require [seon.db.internal :as dbi]))"}
                 {::file "src/seon/db/internal.cljs"
                  ::namespace "seon.db.internal"
                  ::ns-form "(ns seon.db.internal)"}]
        violations (require-violations fixture)
        message (violation-message violations)]
    (is (= 1 (count violations)))
    (is (str/includes? message "src/seon/example.cljs"))
    (is (str/includes? message "seon.db.internal"))
    (is (str/includes? message "expected parent seon.db"))))

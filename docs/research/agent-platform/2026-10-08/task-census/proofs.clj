#!/usr/bin/env bb
;; Prose proofs to tagged proofs. For every task with no `:seon/tasks` tag, read the
;; qualified test symbols its intent names after "Proof"/"proof:"/"Done when" and resolve
;; each to an existing deftest (`ns/name`, or `…/name` when exactly one deftest has that
;; name). Prints `task<TAB>test<TAB>file`, or `task<TAB>-<TAB>needs-proof`. With `--apply`
;; adds the task symbol to each resolved deftest's `:seon/tasks` metadata in the files named
;; by `--files` (the free ones), leaving the rest of each file as it was.
;;   bb docs/research/agent-platform/2026-10-08/task-census/proofs.clj [--apply --files a.clj,b.clj]
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(defn clj-files [root]
  (filter #(and (.isFile %) (re-find #"\.(clj|cljc)$" (.getName %))) (file-seq (io/file root))))

(def deftest-re #"\(deftest\s+(?:\^(?::[^\s]+|\{(?:[^{}]|\{[^{}]*\})*\})\s+)*([^\s\)\(\[]+)")

(def tests
  ;; [ns name] -> path
  (into {} (for [file (clj-files "test")
                 :let [text (slurp file)
                       test-ns (some-> (re-find #"\(ns\s+(?:\^\{[^}]*\}\s+|\^:[^\s]+\s+)*([^\s\)]+)" text) second)]
                 :when test-ns
                 [_ test-name] (re-seq deftest-re text)]
             [[test-ns test-name] (str file)])))

(def by-name (group-by second (keys tests)))

(def tagged
  (into #{} (for [root ["src" "test" "script"] file (clj-files root)
                  [_ body] (re-seq #":seon/tasks\s+#\{([^}]*)\}" (slurp file))
                  token (re-seq #"[A-Za-z0-9.*+!?<>=_-]+/[A-Za-z0-9.*+!?<>=_-]+" body)]
              token)))

(defn proof-text
  "The intent's text from its first proof or done-when phrase on."
  [intent]
  (some->> (re-find #"(?is)(?:\bproofs?\b|\bdone when\b).*" intent)))

(defn resolve-test [token]
  (let [[test-ns test-name] (str/split token #"/" 2)]
    (cond (and (not (str/starts-with? test-ns "…")) (tests [test-ns test-name])) [test-ns test-name]
          (and (str/starts-with? test-ns "…") (= 1 (count (by-name test-name)))) (first (by-name test-name)))))

(defn proofs [intent]
  (->> (re-seq #"(?:…|[a-z][a-z0-9.-]*-test)/[a-z0-9][A-Za-z0-9*+!?<>=_-]*[A-Za-z0-9?!]" (or (proof-text intent) ""))
       (keep resolve-test) distinct))

(def rows
  (for [file (sort (.listFiles (io/file "resources/seon/tasks")))
        :let [n (.getName file) task-ns (subs n 0 (- (count n) 4))]
        entry (edn/read-string (slurp file))
        :let [sym (str task-ns "/" (:seon.task/name entry))]
        :when (not (tagged sym))]
    [sym (proofs (:seon.task/intent entry))]))

(defn add-tag
  "`text` with `sym` added to the `:seon/tasks` metadata of deftest `test-name`."
  [text test-name sym]
  (let [quoted (java.util.regex.Pattern/quote test-name)
        with-set (re-pattern (str "(\\(deftest\\s+\\^\\{[^}]*:seon/tasks #\\{)([^}]*\\}[^}]*\\}\\s+" quoted ")(?=[\\s\\)])"))
        with-map (re-pattern (str "(\\(deftest\\s+\\^\\{)((?:[^{}]|\\{[^{}]*\\})*\\}\\s+" quoted ")(?=[\\s\\)])"))
        bare (re-pattern (str "(\\(deftest\\s+)(" quoted ")(?=[\\s\\)])"))
        tag (str "'" sym)]
    (cond (re-find with-set text) (str/replace-first text with-set (str "$1" tag " $2"))
          (re-find with-map text) (str/replace-first text with-map (str "$1:seon/tasks #{" tag "} $2"))
          (re-find bare text) (str/replace-first text bare (str "$1^{:seon/tasks #{" tag "}} $2"))
          :else (throw (ex-info "deftest not found" {:test test-name})))))

(let [args (set *command-line-args*)
      free (some->> *command-line-args* (drop-while #(not= "--files" %)) second (#(str/split % #",")) set)]
  (doseq [[sym found] rows]
    (if (seq found)
      (doseq [t found] (.println System/out (str sym "\t" (str/join "/" t) "\t" (tests t))))
      (.println System/out (str sym "\t-\tneeds-proof"))))
  (when (args "--apply")
    (doseq [[path pairs] (group-by first (for [[sym found] rows t found] [(tests t) (second t) sym]))
            :when (free path)]
      (spit path (reduce (fn [text [_ test-name sym]] (add-tag text test-name sym)) (slurp path) pairs))
      (.println System/out (str "tagged " path " " (count pairs))))))

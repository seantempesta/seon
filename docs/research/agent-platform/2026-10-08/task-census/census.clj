#!/usr/bin/env bb
;; Task census: one row per task declared in resources/seon/tasks/*.edn, with the
;; signals a reviewer needs to classify it. Run from the checkout root:
;;   bb docs/research/agent-platform/2026-10-08/task-census/census.clj > docs/research/agent-platform/2026-10-08/task-census/census.tsv
;; Columns: sym, ns-exists, missing must-read vars, tags (holders), ledger rows, cards,
;; missing evidence paths, deletion signals (planned row patterns over name + intent + must-read).
;; Static text only: the running system's own check is seon.task/task-findings.
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(defn files [root exts]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile %) (some (fn [e] (str/ends-with? (.getName %) e)) exts)))
       (map str)))

(def source-files (mapcat #(files % [".clj" ".cljc" ".bb"]) ["src" "test" "script"]))

(def defined-re
  #"\((?:defn-?|def|defmacro|defmulti|defonce|deftest|defprotocol|defrecord|deftype|defmethod)\s+(?:\^(?::[^\s]+|\{[^}]*\})\s+)*([^\s\)\]\(]+)")

(defn ns-of [text] (some-> (re-find #"\(ns\s+(?:\^\{[^}]*\}\s+|\^:[^\s]+\s+)*([^\s\)]+)" text) second symbol))

(def namespaces
  ;; ns symbol -> {:path :defined #{names}} over src, test and script
  (into {} (for [path source-files
                 :let [text (slurp path) ns (ns-of text)]
                 :when ns]
             [ns {:path path :defined (into #{} (map second) (re-seq defined-re text))}])))

(defn var-exists? [sym]
  (if-let [ns (some-> (namespace sym) symbol)]
    (boolean (some-> (namespaces ns) :defined (contains? (name sym))))
    (contains? namespaces sym)))

(defn tag-sets
  "Every `:seon/tasks #{...}` set in source, as [path symbol] pairs."
  [path]
  (let [text (slurp path)]
    (for [[_ body] (re-seq #":seon/tasks\s+#\{([^}]*)\}" text)
          token (re-seq #"[A-Za-z0-9.*+!?<>=_-]+/[A-Za-z0-9.*+!?<>=_-]+" body)]
      [path (symbol token)])))

(def tags (group-by second (mapcat tag-sets source-files)))

(def ledger-path "docs/seon/schedule/agent-loop-ledger.md")
(def ledger-lines (str/split-lines (slurp ledger-path)))
(def card-files (files "docs/seon/schedule/cards" [".md"]))
(def card-texts (into {} (map (juxt identity slurp)) card-files))

(defn row-id [line] (some-> (re-find #"^\|\s*([^|(]+?)\s*[(|]" line) second str/trim))

(defn ledger-rows [s] (->> ledger-lines (filter #(str/includes? % s)) (keep row-id) distinct))
(defn cards [s] (->> card-texts (filter #(str/includes? (val %) s)) (map #(str/replace (.getName (io/file (key %))) #"\.md$" ""))))

(def deletion-signals
  [[#"(?i)\bsteps?\b|seon\.step|steps!" "F8 steps shelved"]
   [#":seon\.(?:program|task|effect)/kind|\bkinds?\b|\borigin\b|:seon\.agent/mode|admission/source|core/agent tag" "NK no kinds"]
   [#"(?i)receipt" "EN-1/RP-15a receipts"]
   [#"seon\.fault|(?i)\bfaults?\b" "E-SDELETE fault into error"]
   [#"(?i)propos" "GV-4/GV-5 merge request"]
   [#"\b64\b" "64-handle bound"]
   [#"(?i)reaching tests?[^.]*\bmerge\b|only at merge" "reaching tests auto-run"]
   [#"bounded-text|\bdatum\b|plain-projection|floor printer|\bfloor\b" "PR printer rows"]
   [#"seon\.context/|seon\.cluster\.prompt|(?i)\bcapture\b|compact|prompt selection|\bopening\b|system-turn|virtual-turn|seed-tx|declared-sources|refresh" "S4 context deletion"]
   [#"settle-batch|close-turn|open-tx|run open|stage-reply|plan-tx|recover-tx|backstop|parking|apply-pending|with-submission|settle-evaluated" "RP/EN/S6 turn+recording"]
   [#"seon\.call-preparation|row machinery|positional" "AU-Q1 call-preparation"]
   [#"retire-branch|create-branch|propose-merge|cluster-branch" "GV renames"]
   [#"(?i)uncovered|coverage gate" "MA-10 coverage gate"]
   [#"my\.turn/complete|my\.agent/done|seon\.run/complete" "F7 completion"]])

(defn signals [text] (->> deletion-signals (filter #(re-find (first %) text)) (map second)))

(defn tasks []
  (for [path (sort (files "resources/seon/tasks" [".edn"]))
        :let [ns (symbol (subs (.getName (io/file path)) 0 (- (count (.getName (io/file path))) 4)))]
        entry (edn/read-string (slurp path))]
    (assoc entry :ns ns :path path :sym (symbol (str ns) (:seon.task/name entry)))))

(defn row [{:keys [ns sym] :seon.task/keys [name intent must-read evidence]}]
  (let [s (str sym)]
    {:sym s
     :ns-exists (contains? namespaces ns)
     :missing-vars (str/join " " (sort (map str (remove var-exists? must-read))))
     :tags (str/join " " (sort (distinct (map first (tags sym)))))
     :ledger (str/join " " (ledger-rows s))
     :cards (str/join " " (cards s))
     :missing-evidence (str/join " " (remove #(.exists (io/file %)) evidence))
     :signals (str/join "; " (signals (str name " " intent " " (str/join " " must-read))))}))

(def columns [:sym :ns-exists :missing-vars :tags :ledger :cards :missing-evidence :signals])

(println (str/join "\t" (map name columns)))
(doseq [r (map row (tasks))] (println (str/join "\t" (map #(str (get r %)) columns))))
(binding [*out* *err*]
  (println "dangling tags (tag with no task):"
           (sort (map str (remove (set (map :sym (tasks))) (keys tags))))))

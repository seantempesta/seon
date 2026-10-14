(ns seon.rename-flow-actions
  "db<->flow's decision renamed so `plan` names only the task field (TASK-CHAIN S1a, commit 1), by syntax with rewrite-clj.

  clojure -Sdeps '{:paths [\"script\" \"src\" \"resources\"]}' -M -m seon.rename-flow-actions report ROOT
  ... -m seon.rename-flow-actions write ROOT

  Renames the keywords of namespace `seon.flow.plan` to `seon.flow.actions` (also the qualifier of a
  `#:seon.flow.plan` map and the destructuring key `:seon.flow.plan/keys`), the vars
  `seon.cluster.agent/plan`, `plan-facts` and `apply-plan!` to `launch-actions`, `launch-facts` and
  `apply-actions!` (fully qualified, through any alias of `seon.cluster.agent`, and unqualified inside that
  namespace's own file), and the same inside strings that read as Clojure. `seon.cluster.agent/next-actions`
  already names the ended assignments' derivation, so the start derivation takes `launch-actions`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [seon.rename-task-intent :as base]
            [seon.render.value :as value]))

(def ^:private old-ns "seon.flow.plan")
(def ^:private new-ns "seon.flow.actions")
(def ^:private agent-ns "seon.cluster.agent")
(def ^:private agent-file "src/seon/cluster/agent.clj")

(def ^:private var-renames {"plan" "launch-actions" "plan-facts" "launch-facts" "apply-plan!" "apply-actions!"})

(defn- string-node?
  "Whether `node` is a string literal."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [node]
  (contains? node :lines))

(defn- aliases
  "The aliases under which the parsed file `root` requires `seon.cluster.agent`, including its full name."
  {:malli/schema [:=> [:cat :any] [:set :string]]}
  [root]
  (letfn [(walk [node]
            (cond-> (if (and (n/inner? node) (= :vector (n/tag node)))
                      (let [items (remove n/whitespace-or-comment? (n/children node))
                            [a b c] items]
                        (if (and a (= agent-ns (str (:value a))) b (= ":as" (n/string b)) c)
                          #{(str (:value c))}
                          #{}))
                      #{})
              (n/inner? node) (into (mapcat walk (n/children node)))))]
    (into #{agent-ns} (walk root))))

(defn- renamed-symbol
  "The replacement symbol node for token `node`, or nil: a qualified var of this rename, or in the
  agent's own file an unqualified one."
  {:malli/schema [:=> [:cat :any [:set :string] :boolean] :any]}
  [node alias-set own-file?]
  (when (and (= :token (n/tag node)) (symbol? (:value node)))
    (let [s (:value node) ns-part (namespace s) nm (name s)]
      (cond (and ns-part (contains? alias-set ns-part) (var-renames nm))
            (n/token-node (symbol ns-part (var-renames nm)))
            (and (nil? ns-part) own-file? (var-renames nm))
            (n/token-node (symbol (var-renames nm)))))))

(defn- renamed-keyword
  "The replacement keyword node for `node`, or nil: a full keyword of namespace `seon.flow.plan`."
  {:malli/schema [:=> [:cat :any] :any]}
  [node]
  (when (and (= :token (n/tag node)) (keyword? (:k node)) (not (:namespaced? node))
             (= old-ns (namespace (:k node))))
    (n/keyword-node (keyword new-ns (name (:k node))))))

(declare rewrite)

(defn- rewrite-string
  "A string node reading as Clojure, rewritten as forms and escaped again; `node` when nothing changes or it does not read."
  {:malli/schema [:=> [:cat :any [:set :string] :boolean] :any]}
  [node alias-set _own-file?]
  (let [value (n/sexpr node)]
    (if-not (re-find #"seon\.flow\.plan|plan" value)
      node
      (let [forms (try (p/parse-string-all value) (catch Exception _ nil))
            new-value (some-> forms (rewrite alias-set false) n/string)]
        (if (or (nil? new-value) (= value new-value))
          node
          (let [esc (fn [s] (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))
                lines (if (= :multi-line (n/tag node))
                        (mapv esc (str/split new-value #"\n" -1))
                        [(str/replace (esc new-value) "\n" "\\n")])
                out (n/string-node lines)]
            (when-not (= new-value (n/sexpr out))
              (throw (ex-info "A rewritten string does not read back to its value." {:value new-value})))
            out))))))

(defn rewrite
  "`node` with the rename applied."
  {:malli/schema [:=> [:cat :any [:set :string] :boolean] :any]}
  [node alias-set own-file?]
  (cond
    (renamed-keyword node) (renamed-keyword node)
    (renamed-symbol node alias-set own-file?) (renamed-symbol node alias-set own-file?)
    (string-node? node) (rewrite-string node alias-set own-file?)
    (and (= :map-qualifier (n/tag node)) (= (str ":" old-ns) (n/string node)))
    (n/map-qualifier-node false new-ns)
    (n/inner? node) (n/replace-children node (mapv #(rewrite % alias-set own-file?) (n/children node)))
    :else node))

(defn- file-rewrite
  "The new text of file `rel` under `root`, or nil when unchanged."
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [root rel]
  (let [text (slurp (io/file root rel))]
    (when (re-find #"plan" text)
      (let [parsed (p/parse-string-all text)
            _ (when-not (= text (n/string parsed)) (throw (ex-info "A file does not round-trip." {:file rel})))
            new-text (n/string (rewrite parsed (aliases parsed) (= rel agent-file)))]
        (when (not= text new-text) new-text)))))

(defn changed
  "The relative paths whose text the rename changes, with the new text."
  {:malli/schema [:=> [:cat :string] [:map-of :string :string]]}
  [root]
  (into (sorted-map) (keep (fn [rel] (when-let [t (file-rewrite root rel)] [rel t]))) (remove #{"script/seon/rename_flow_actions.clj" "script/seon/rename_task_intent.clj"} (base/sweep-files root))))

(defn -main
  "Report or write the rename under ROOT: report ROOT | write ROOT."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& [mode root]]
  (let [result (changed root)]
    (when (= "write" mode)
      (doseq [[rel text] result] (spit (io/file root rel) text)))
    (println (value/core-text {:files (count result) :paths (vec (keys result))} 0))
    (shutdown-agents)))

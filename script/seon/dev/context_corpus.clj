;; The context corpus: the forms an agent actually runs, scored by explicit rules.
;;
;; Run from the checkout against the running default cluster:
;;   bb --classpath script:src:resources script/seon/dev/context_corpus.clj . [AGENT]
;; Each form enters `seon.mcp/call` as an SCI agent (default agent
;; "context-corpus", its own branch); prepl is transport only. The JVM
;; estimates tokens with `seon.ai.tokens/estimate`; this script reads each
;; text with edamame and applies the rules below. Output: one EDN row per
;; form, then a Markdown table.
;;
;; Rules (a row fails each rule it breaks):
;;   :data      every reply text reads as exactly one EDN form
;;   :noise     no internal bookkeeping (turn/commit ids, error stamps,
;;              connections, SCI contexts, render profiles, stack frames)
;;   :duplicate no string of 60+ characters or collection of 12+ leaves appears twice
;;   :tail      a form that changed no definition carries no tests block
;;   :next      an error or cut value offers a runnable form
;;   :type      an error carries :seon.error/type
;;   :placeholder no taught form carries a value an agent cannot fill
;;              ("/path/to", "...", <slot>)
;;   :size      reply tokens within the form's ceiling (what its content needs)
(require '[seon.operator :as operator] '[cheshire.core :as json]
         '[clojure.string :as str] '[edamame.core :as e])

(def root (or (first *command-line-args*) "."))
(def agent-id (or (second *command-line-args*) "context-corpus"))
(def advertisement (operator/advertisement root "default"))

(defn host
  "One form's EDN value from the running JVM."
  [code]
  (operator/prepl-value! advertisement code 300000))

(defn call
  "The MCP call `tool` with `arguments`: its texts and their token estimates."
  [tool arguments]
  (host (str "(let [r (clojure.data.json/read-str (seon.mcp/call "
             (json/generate-string (json/generate-string {:name tool :arguments arguments}))
             ")) texts (mapv #(get % \"text\") (get r \"content\"))]"
             " {:texts texts :error? (get r \"isError\")"
             "  :tokens (reduce + 0 (map seon.ai.tokens/estimate texts))})")))

(defn evaluate
  "Evaluate `code` as the corpus agent."
  [code]
  (call "eval_clj" {:agent agent-id :cluster "default" :mode "sci" :code code}))

(def setup
  "Unscored forms that give the corpus its subjects: a contracted function, a
  red test and an unread message, all on the corpus agent's branch."
  ["(ns corpus.scratch (:require [clojure.test :refer [deftest is]]))"
   "(defn corpus-add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (+ a b))"
   "(when (empty? (my.message/inbox)) (my.message/send {:my.message/to \"context-corpus\" :my.message/content \"Corpus: an unread message.\"}))"])

(def corpus
  "Each form an agent runs: its label, code, ceiling in tokens and what it is."
  [{:label "help" :code "(help)" :ceiling 450}
   {:label "current-state, unread" :code "(seon.context/current-state)" :ceiling 150}
   {:label "current-state, red proof" :code "(seon.context/current-state (seon.db/db) \"live-flash-4\")" :ceiling 700}
   {:label "doc fn" :code "(doc seon.db/pull)" :ceiling 300}
   {:label "source seon fn" :code "(clojure.repl/source seon.ai.tokens/estimate)" :ceiling 300}
   {:label "source library fn" :code "(clojure.repl/source clojure.core/some-fn)" :ceiling 400}
   {:label "dir ns" :code "(dir my.message)" :ceiling 300}
   {:label "defn" :code "(defn corpus-add {:malli/schema [:=> [:cat :int :int] :int]} [a b] (+ a b))"
    :ceiling 80 :changes? true}
   {:label "failing test step" :code "(deftest corpus-red (is (= 3 (corpus-add 1 1))))" :ceiling 300
    :changes? true}
   {:label "contract refusal" :code "(corpus-add \"x\" 1)" :ceiling 200 :error? true}
   {:label "arity error" :code "(corpus-add 1)" :ceiling 150 :error? true}
   {:label "unresolved symbol" :code "(frobnicate 1)" :ceiling 120 :error? true}
   {:label "uninstalled attribute" :code "(seon.db/pull [:corpus/nothing] [:seon.ns/name 'corpus.scratch])"
    :ceiling 150 :error? true}
   {:label "big query (cut)" :code "(seon.db/q '[:find ?s :where [_ :seon.fn/sym ?s]])" :ceiling 1600 :cut? true}
   {:label "my.program/breaks" :code "(my.program/breaks {:seon.program/subject 'seon.db/pull})" :ceiling 600}
   {:label "my.test/check" :code "(my.test/check {})" :ceiling 250}
   {:label "my.message/inbox" :code "(my.message/inbox)" :ceiling 150}
   {:label "runtime_status" :tool "runtime_status" :ceiling 800}])

(def noise-keys
  #{:seon.turn/id :seon.source/commit-id :seon.error/at :seon.error/layer :seon.db/connection :seon.db/db
    :seon.sci/ctx :seon.sci.admit/caps :db/id :seon.cluster.reply/text})

(def noise-namespaces #{"seon.render.profile" "seon.sci.admit"})

(defn parsed
  "The forms of `text`, or nil when it is not exactly one EDN form."
  [text]
  (try (let [forms (e/parse-string-all text {:readers (fn [_] identity) :auto-resolve name
                                             :regex true :fn true :quote true})]
         (when (= 1 (count forms)) forms))
       (catch Exception _ nil)))

(defn nodes
  "Every subvalue of `forms`."
  [forms]
  (tree-seq coll? seq forms))

(defn noise
  "The internal bookkeeping found in `forms` and `texts`."
  [forms texts]
  (into (sorted-set)
        (concat (keep #(when (and (keyword? %) (or (noise-keys %) (noise-namespaces (namespace %)))) (str %))
                      (nodes forms))
                (when (some #(re-find #"\.java:\d+|#object\[|#error \{" %) texts) ["host frames/objects"]))))

(defn weight
  "A subvalue's size: a string's characters, a collection's leaves, else 0."
  [v]
  (cond (string? v) (if (<= 60 (count v)) 60 0)
        (coll? v) (count (remove coll? (tree-seq coll? seq v)))
        :else 0))

(defn duplicates
  "Large subvalues (60+ characters or 12+ leaves) appearing more than once.

  Each is described by its count, leaves and head."
  [forms]
  (->> (nodes forms)
       (filter #(<= 12 (weight %)))
       frequencies
       (keep (fn [[v n]] (when (< 1 n)
                           {:times n :leaves (weight v)
                            :head (if (string? v) (subs v 0 30) (first (remove coll? (tree-seq coll? seq v))))})))
       (sort-by :leaves >) (take 3) vec))

(defn runnable?
  "Whether `forms` hold a list form headed by a symbol."
  [forms]
  (some #(and (seq? %) (symbol? (first %)) (not= 'quote (first %))) (nodes forms)))

(defn score
  "Score one corpus item's reply."
  [{:keys [ceiling changes? error? cut?] :as item} {:keys [texts tokens]}]
  (let [readings (map parsed texts)
        forms (vec (mapcat identity (remove nil? readings)))
        joined (str/join "\n" texts)
        found-noise (noise forms texts)
        dups (duplicates forms)
        failures (cond-> []
                   (some nil? readings) (conj :data)
                   (seq found-noise) (conj :noise)
                   (seq dups) (conj :duplicate)
                   (and (not changes?) (str/includes? joined ":seon.turn.loop/tests")) (conj :tail)
                   (and (or error? cut?) (not (runnable? forms))) (conj :next)
                   (and error? (not (str/includes? joined ":seon.error/type"))) (conj :type)
                   (re-find #"/path/to|\\?\"\.\.\.\\?\"|<[a-z -]+>" joined) (conj :placeholder)
                   (< ceiling tokens) (conj :size))]
    (assoc (select-keys item [:label :code :ceiling])
           :texts texts :tokens tokens :failures failures :noise (vec found-noise) :duplicates dups)))

(defn run-item
  "Evaluate and score one corpus item."
  [item]
  (score item (if (= "runtime_status" (:tool item))
                (call "runtime_status" {:cluster "default"})
                (evaluate (:code item)))))

(doseq [code setup] (evaluate code))
(def rows (mapv run-item corpus))
(doseq [row rows] (prn row))  ; the rows carry the reply texts for inspection
(println)
(println "| # | Form | Tokens / ceiling | Failing rules | Noise | Duplicates |")
(println "|---|------|------------------|---------------|-------|------------|")
(doseq [[i {:keys [label tokens ceiling failures] found :noise dups :duplicates}] (map-indexed vector rows)]
  (println (format "| %d | %s | %d / %d | %s | %s | %s |" (inc i) label tokens ceiling
                   (if (seq failures) (str/join " " (map name failures)) "-")
                   (if (seq found) (str/join " " found) "-")
                   (if (seq dups) (str/join "; " (map #(str (:head %) " x" (:times %)) dups)) "-"))))
(println (format "\nTotal: %d tokens, %d rule failures over %d forms."
                 (reduce + (map :tokens rows)) (reduce + (map (comp count :failures) rows)) (count rows)))

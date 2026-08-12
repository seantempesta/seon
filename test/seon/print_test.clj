(ns seon.print-test
  "Generative laws for the one admitted-value emitter."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render.hiccup :as hiccup]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support]))

(def ^:private no-cuts
  {:seon.print/length nil
   :seon.print/level nil
   :seon.print/width 0
   :seon.print/namespace-maps? true
   :seon.print/table? false})

(def ^:private options-generator
  (gen/let [length (gen/one-of [(gen/return nil) (gen/choose 0 8)])
            level (gen/one-of [(gen/return nil) (gen/choose 0 8)])
            width (gen/choose 0 80)
            namespace-maps? gen/boolean
            table? (gen/elements [:derived true false])]
    {:seon.print/length length
     :seon.print/level level
     :seon.print/width width
     :seon.print/namespace-maps? namespace-maps?
     :seon.print/table? table?}))

(def ^:private admission-caps
  (config/result-caps (config/defaults)))

(defn- admitted-node
  [value]
  (edn/read-string
   (:seon.cluster.eval/result-edn
    (admit/admit {:seon.sci.admit/value value
                  :seon.sci.admit/interrupt-fn (fn [])
                  :seon.sci.admit/caps admission-caps
                  :seon.config/on-core-error :record}))))

(deftest print-nodes-expose-symbols-and-entity-identities-without-shape-rules
  (let [node (admitted-node
              {:frontier/symbol 'my.run/complete
               :frontier/namespace 'my.message
               :frontier/entity
               {:seon.cluster.message/id "task-1"
                :frontier/nested
                [[:seon.ns/name 'my.run]
                 {:seon.cluster.agent/id "worker"}]}})]
    (is (= #{'my.run/complete
             'my.message
             'my.run
             [:seon.cluster.message/id "task-1"]
             [:seon.ns/name 'my.run]
             [:seon.cluster.agent/id "worker"]}
           (print/references
            #{:seon.cluster.message/id :seon.ns/name
              :seon.cluster.agent/id}
            node)))
    (is (not (contains? (print/references #{} node)
                        [:seon.cluster.message/id "task-1"]))
        "identity recognition comes only from schema-derived attributes")))

(defn- sci-value
  [source]
  (sci/eval-string source))

(defn- compiled-node-schema
  []
  (let [registry (:seon.schema.projection/registry
                  (schema/current-projection))]
    (m/schema :seon.print/node {:registry registry})))

(defn- lexical-hiccup-text
  "Text tokens in a hiccup sink result, excluding structural chrome."
  [hiccup]
  (cond
    (string? hiccup) hiccup
    (sequential? hiccup)
    (let [[tag & body] hiccup
          attributes (when (map? (first body)) (first body))
          body (if attributes (next body) body)]
      (if (or (= :summary tag)
              (= "seon-print-visual" (:class attributes)))
        ""
        (apply str (map lexical-hiccup-text body))))
    :else ""))

(defn- normalize-whitespace
  [text]
  (str/replace text #"\s+" " "))

(defn- normalize-nan
  [value]
  (walk/postwalk
   (fn [item]
     (if (and (number? item) (Double/isNaN (double item)))
       ::nan
       item))
   value))

(defn- print-summaries
  [hiccup]
  (filter
   (fn [node]
     (and (vector? node)
          (= :summary (first node))
          (= "seon-print-summary" (get-in node [1 :class]))))
   (tree-seq sequential? seq hiccup)))

(declare readable-value)

(def ^:private unreadable-faces
  #{:seon.print/record
    :seon.print/var
    :seon.print/type
    :seon.print/class
    :seon.print/object
    :seon.print/truncated-string
    :seon.print/failed
    :seon.print/elided
    :seon.print/pruned})

(defn- readable-entry?
  [entry]
  (and (vector? entry)
       (= 2 (count entry))
       (some? (readable-value (first entry)))
       (some? (readable-value (second entry)))))

(defn- readable-value
  "Independent semantic oracle for the readable grammar partition."
  [node]
  (let [face (:seon.print/face node)]
    (when-not (contains? unreadable-faces face)
      (case face
        :seon.print/nil [::readable nil]
        :seon.print/boolean [::readable (:seon.print/value node)]
        :seon.print/number [::readable (:seon.print/value node)]
        :seon.print/keyword [::readable (:seon.print/value node)]
        :seon.print/symbol [::readable (:seon.print/value node)]
        :seon.print/char [::readable (:seon.print/value node)]
        :seon.print/string [::readable (:seon.print/value node)]
        :seon.print/inst [::readable (:seon.print/value node)]
        :seon.print/uuid [::readable (:seon.print/value node)]
        :seon.print/vector
        (let [children (mapv readable-value (:seon.print/items node))]
          (when (every? some? children)
            [::readable (mapv second children)]))
        :seon.print/list
        (let [children (mapv readable-value (:seon.print/items node))]
          (when (every? some? children)
            [::readable (apply list (map second children))]))
        :seon.print/set
        (let [children (mapv readable-value (:seon.print/items node))]
          (when (every? some? children)
            [::readable (set (map second children))]))
        :seon.print/map
        (when (every? readable-entry? (:seon.print/entries node))
          [::readable
           (into {}
                 (map (fn [[key-node value-node]]
                        [(second (readable-value key-node))
                         (second (readable-value value-node))]))
                 (:seon.print/entries node))])
        :seon.print/throwable
        (readable-value (:seon.print/value node))
        nil))))

(deftest ^{:seon.test/long
           "94.303 s pool: 200 generated grammar validation, text/Hiccup emission, and EDN read-back trials."}
  p-total-generated-grammar-emits-and-readable-faces-round-trip
  (let [compiled (compiled-node-schema)
        generator (mg/generator compiled)
        check
        (tc/quick-check
         200
         (prop/for-all [node generator]
           (let [text (print/emit-text node no-cuts)
                 hiccup (print/emit-hiccup node no-cuts)
                 readable (readable-value node)]
             (and (m/validate compiled node)
                  (string? text)
                  (vector? hiccup)
                  (or (nil? readable)
                      (= (normalize-nan (second readable))
                         (normalize-nan
                          (edn/read-string
                           {:readers {'error identity}}
                           text)))))))
         :seed 202608010301)]
    (test-support/assert-check! check "P-TOTAL failed.")))

(deftest ^{:seon.test/long
           "93.195 s pool: 200 generated text/Hiccup lexical-equivalence trials over print options."}
  p-tee-generated-grammar-cannot-disagree
  (let [compiled (compiled-node-schema)
        generator (mg/generator compiled)
        check
        (tc/quick-check
         200
         (prop/for-all [node generator
                        options options-generator]
           (let [{:seon.print/keys [text hiccup]}
                 (print/emit-both node options)]
             (= (normalize-whitespace text)
                (normalize-whitespace (lexical-hiccup-text hiccup)))))
         :seed 202608010302)]
    (test-support/assert-check! check "P-TEE failed.")))

(deftest structural-summaries-carry-readable-child-text
  (let [hiccup (print/emit-hiccup
                (admitted-node {:alpha [1 2] :beta {:nested true}})
                no-cuts)
        summaries (print-summaries hiccup)]
    (is (seq summaries) "the representative value emits disclosures")
    (is (every? (fn [[_ _ & children]]
                  (some #(and (string? %) (not (str/blank? %))) children))
                summaries)
        "every print summary owns a visible label without CSS")))

(deftest tagged-envelope-never-collides-with-authored-print-keywords
  (let [value {:seon.print/face :seon.print/elided
               :seon.print/value :seon.print/pruned
               :nested [:seon.print/object
                        {:seon.print/class "authored"}]}
        node (admitted-node value)]
    (is (= value
           (edn/read-string (print/emit-text node no-cuts))))
    (is (not (str/includes? (print/emit-text node no-cuts) "#object[")))
    (is (not (str/includes? (print/emit-text node no-cuts) "...")))))

(deftest stock-length-level-and-honest-special-faces
  (let [list-node (admitted-node '(0 1 2))
        nested-node (admitted-node {:a {:b 1}})]
    (is (= "(0 1 2)" (print/emit-text list-node no-cuts)))
    (is (= "(0 1 ...)"
           (print/emit-text list-node (assoc no-cuts :seon.print/length 2))))
    (is (= "(...)"
           (print/emit-text list-node (assoc no-cuts :seon.print/length 0))))
    (is (= "#"
           (print/emit-text list-node (assoc no-cuts :seon.print/level 0))))
    (is (= "{:a #}"
           (print/emit-text nested-node (assoc no-cuts :seon.print/level 1))))
    (is (= "##Inf" (print/emit-text (admitted-node Float/POSITIVE_INFINITY)
                                     no-cuts)))
    (is (= "##-Inf" (print/emit-text (admitted-node Float/NEGATIVE_INFINITY)
                                      no-cuts)))
    (is (= "##NaN" (print/emit-text (admitted-node Float/NaN) no-cuts)))))

(deftest honest-named-and-object-faces
  (let [namespace-text (print/emit-text
                        (admitted-node (sci-value "(create-ns 'face.ns)"))
                        no-cuts)
        atom-node (admitted-node (atom {:private/value 42}))
        atom-text (print/emit-text atom-node no-cuts)
        function-text (print/emit-text
                       (admitted-node (sci-value "(fn named_face [] 1)"))
                       no-cuts)]
    (is (= "#object[sci.lang.Namespace \"face.ns\"]" namespace-text))
    (is (= "#'user/face_var"
           (print/emit-text
            (admitted-node (sci-value "(def face_var 1) #'face_var"))
            no-cuts)))
    (is (= "java.lang.String"
           (print/emit-text (admitted-node String) no-cuts)))
    (is (= "#user.FaceRecord{:a 1}"
           (print/emit-text
            (admitted-node
             (sci-value "(defrecord FaceRecord [a]) (->FaceRecord 1)"))
            no-cuts)))
    (is (= #{:seon.print/face :seon.print/class}
           (set (keys atom-node)))
        "an IDeref is represented only by its object marker and stable type")
    (is (= :seon.print/object (:seon.print/face atom-node)))
    (is (= "clojure.lang.Atom" (:seon.print/class atom-node)))
    (is (= "#object[clojure.lang.Atom]" atom-text))
    (is (str/starts-with? function-text "#object["))
    (is (not (str/includes? function-text "@"))
        "generic host toString identity never reaches the print node")
    (is (not (str/includes? function-text "$"))
        "function class names are demunged")))

(deftest agent-facing-object-faces-are-byte-stable-across-processes
  (let [expression
        (str "(require '[sci.core :as sci] '[seon.print :as print] "
             "'[seon.sci.admit :as admit]) "
             "(let [values [(sci/eval-string \"(create-ns 'stable.ns)\") "
             "(atom 1)] rendered "
             "(mapv (fn [value] "
             "(let [node (:seon.sci.admit/print-node "
             "(admit/admit-value {:seon.sci.admit/value value "
             ":seon.sci.admit/interrupt-fn (fn []) "
             ":seon.sci.admit/caps "
             "{:seon.config.eval.result/max-depth 8 "
             ":seon.config.eval.result/max-collection 32 "
             ":seon.config.eval.result/max-string 4096 "
             ":seon.config.eval.result/max-nodes 4096} "
             ":seon.config/on-core-error :record}))] "
             "(print/emit-text node " (pr-str no-cuts) "))) values)] "
             "(print (pr-str rendered)))")
        run #(shell/sh "java" "-cp" (System/getProperty "java.class.path")
                       "clojure.main" "-e" expression)
        left (run)
        right (run)
        left-rendered (last (str/split-lines (:out left)))
        right-rendered (last (str/split-lines (:out right)))]
    (is (zero? (:exit left)) (:err left))
    (is (zero? (:exit right)) (:err right))
    (is (= (pr-str ["#object[sci.lang.Namespace \"stable.ns\"]"
                    "#object[clojure.lang.Atom]"])
           left-rendered))
    (is (= left-rendered right-rendered))))

(deftest refitting-a-truncated-collection-preserves-its-honest-elision
  (let [total 210
        node {:seon.print/face :seon.print/vector
              :seon.print/items
              [{:seon.print/face :seon.print/symbol
                :seon.print/value 'seon.bootstrap}
               {:seon.print/face :seon.print/elided
                :seon.print/omitted (dec total)
                :seon.print/elision-unit :children
                :seon.render.data/total total
                :seon.render.data/path []
                :seon.render.data/next-offset 1
                :seon.render.profile/id :seon.render.profile/agent
                :seon.print/requery-id [:seon.db/query :requires]}]}
        fitted (print/fit
                node
                {:seon.render.profile/id :seon.render.profile/agent
                 :seon.render.profile/token-budget 1
                 :seon.render.profile/max-depth 8
                 :seon.render.profile/max-children 1
                 :seon.render.profile/composition :multiline})
        items (:seon.print/items fitted)
        elision (if items (peek items) fitted)
        rendered (if items (dec (count items)) 0)]
    (is (= :seon.print/elided (:seon.print/face elision)))
    (is (= total (:seon.render.data/total elision)))
    (is (= (- total rendered) (:seon.print/omitted elision)))
    (is (= [:seon.db/query :requires] (:seon.print/requery-id elision)))
    (is (not (contains? elision :seon.print/requery-refusal)))))

(deftest throwable-face-is-readable-error-data
  (let [failure (ex-info "outer" {:outer true}
                         (ex-info "root" {:root 42}))
        text (print/emit-text (admitted-node failure) no-cuts)]
    (is (str/starts-with? text "#error "))
    (is (= (Throwable->map failure)
           (edn/read-string {:readers {'error identity}} text)))))

(deftest derived-table-is-one-text-and-html-face
  (let [node (admitted-node [{:a 1 :b 'x} {:a 22 :b 'yy}])
        options (assoc no-cuts :seon.print/table? :derived)
        {:seon.print/keys [text hiccup]} (print/emit-both node options)]
    (is (= "\n| :a | :b |\n|----+----|\n|  1 |  x |\n| 22 | yy |\n"
           text))
    (is (= text (lexical-hiccup-text hiccup)))
    (is (hiccup/hiccup? hiccup))
    (is (str/includes? (hiccup/->string hiccup) "<table"))
    (is (= :table (-> hiccup (get-in [3 0]))))))

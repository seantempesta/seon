(ns seon.print-test
  "Generative laws for the one admitted-value emitter."
  (:require [clojure.edn :as edn]
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

(deftest p-total-generated-grammar-emits-and-readable-faces-round-trip
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

(deftest p-tee-generated-grammar-cannot-disagree
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
        coincidental-identity-node
        (assoc atom-node :seon.print/address "0x1f42ab")
        function-text (print/emit-text
                       (admitted-node (sci-value "(fn named_face [] 1)"))
                       no-cuts)]
    (is (re-matches #"#object\[sci\.lang\.Namespace 0x[0-9a-f]+ \"face\.ns\"\]"
                    namespace-text))
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
    (is (= #{:seon.print/face :seon.print/class :seon.print/address}
           (set (keys atom-node)))
        "an IDeref is represented only by its object marker, type, and identity")
    (is (= :seon.print/object (:seon.print/face atom-node)))
    (is (= "clojure.lang.Atom" (:seon.print/class atom-node)))
    (is (re-matches #"0x[0-9a-f]+" (:seon.print/address atom-node)))
    (is (= (str "#object[" (:seon.print/class atom-node) " "
                (:seon.print/address atom-node) "]")
           atom-text))
    (is (= "#object[clojure.lang.Atom 0x1f42ab]"
           (print/emit-text coincidental-identity-node no-cuts))
        "value-like digits inside a permitted identity remain identity bytes")
    (is (str/starts-with? function-text "#object["))
    (is (not (str/includes? function-text "$"))
        "function class names are demunged")))

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

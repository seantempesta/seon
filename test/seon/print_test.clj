(ns seon.print-test
  "Generative laws for the one admitted-value emitter."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [sci.core :as sci]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render :as render]
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
  (config/result-caps config/defaults))

(defn- admitted-node
  [value]
  (:seon.sci.admit/print-node
    (admit/admit-value {:seon.sci.admit/value value
                  :seon.sci.admit/interrupt-fn (fn [])
                  :seon.sci.admit/caps admission-caps
                  :seon.config/on-core-error :record})))

(defn- admitted-member-node
  "The print node for a value admitted as a MEMBER of an ordinary collection.

  A bare host reference at the ROOT of an admission is
  `:seon.sci.admit/reason :unserializable` — a description of a value is not a
  value, so nothing is stored for it (the storage-bound wave, 2026-09-07).
  The object FACE is still what the grammar produces for that reference where
  it sits inside a value that IS stored, which is what these trials are
  about."
  [value]
  (first (:seon.print/items (admitted-node [value]))))

(defn- observed-query-shape
  []
  (let [text (apply str (repeat 36 \x))]
    (mapv (fn [row]
            (into {:db/id row}
                  (map (fn [field]
                         [(keyword "seon.fn" (str "field-" field)) text]))
                  (range 7)))
          (range 116))))

(deftest print-nodes-expose-symbols-and-entity-identities-without-shape-rules
  (let [node (admitted-node
              {:frontier/symbol 'my.turn/complete
               :frontier/namespace 'my.message
               :frontier/entity
               {:seon.message/id "task-1"
                :frontier/nested
                [[:seon.ns/name 'my.turn]
                 {:seon.agent/id "worker"}]}})]
    (is (= #{'my.turn/complete
             'my.message
             'my.turn
             [:seon.message/id "task-1"]
             [:seon.ns/name 'my.turn]
             [:seon.agent/id "worker"]}
           (print/references
            #{:seon.message/id :seon.ns/name
              :seon.agent/id}
            node)))
    (is (not (contains? (print/references #{} node)
                        [:seon.message/id "task-1"]))
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

(deftest a-generated-set-node-can-never-hold-a-duplicate-item
  ;; THE CLASS: a dishonest generator. `::set` items came from
  ;; `(gen/vector inner 0 3)`, so two structurally equal children — or two
  ;; different nodes that PRINT the same literal, like `::nil` and the
  ;; `::projected` node whose value is the string nil — could occupy one set
  ;; node. A real set holds no duplicate, so the grammar it generated was
  ;; not the grammar the emitter is total over, and the EDN read-back refused
  ;; with `Duplicate key`. The same hazard is a map or record key.
  ;;
  ;; This is the cheap, deterministic falsifier for the class; the long
  ;; round-trip property is the same statement under 200 trials.
  (let [samples (gen/sample print/node-generator 300)
        readable
        (fn [node]
          (let [text (print/emit-text node no-cuts)]
            (try [::read (edn/read-string {:readers {'error identity}} text)]
                 (catch Throwable _ [::text text]))))
        container-items
        (fn [face key-fn]
          (into []
                (comp (filter (fn [node] (= face (:seon.print/face node))))
                      (map (fn [node]
                             (mapv (fn [item] (readable (key-fn item)))
                                   (or (:seon.print/items node)
                                       (:seon.print/entries node))))))
                samples))
        offenders
        (fn [rows] (into [] (remove #(= (count %) (count (set %)))) rows))
        set-rows (container-items :seon.print/set identity)
        map-rows (container-items :seon.print/map first)]
    (is (pos? (count set-rows)) "the sample genuinely contains set nodes")
    (is (= [] (offenders set-rows))
        "no generated set node holds two items the reader calls equal")
    (is (= [] (offenders map-rows))
        "and no generated map node holds two keys it calls equal")))

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

(defn- emitted-both
  "Emit one value through the tee sink WITHOUT the public node contract.

  `seon.print/emit-both` declares `:seon.print/node`, and an undeclared or
  absent face is not one — under the armed contracts every cluster runs
  under (and the gate now runs under) that call is refused before the
  emitter is reached. The floor being asserted here is the emitter's own:
  whatever face arrives, both sinks answer with the same flat diagnostic
  rather than a second exception."
  [node options]
  (let [text (print/text-sink options)
        hiccup (print/hiccup-sink)]
    (#'print/emit-node node (print/tee-sink text hiccup) options 0 [])
    {:seon.print/text (#'print/sink-result text)
     :seon.print/hiccup (#'print/sink-result hiccup)}))

(deftest terminal-emission-is-total-for-an-unknown-or-absent-face
  (doseq [node [{:seon.print/face :fixture/unknown
                 :fixture/value 1}
                {:fixture/value 1}]]
    (let [{:seon.print/keys [text hiccup]}
          (emitted-both node no-cuts)
          error (edn/read-string text)]
      (is (str/includes? text ":seon.print/unknown-face"))
      (is (str/includes? text (pr-str (:seon.print/face node))))
      (is (= (vec (sort-by str (keys node)))
             (get-in error [:seon.error/data :seon.print/node-keys])))
      (is (= (normalize-whitespace text)
             (normalize-whitespace (lexical-hiccup-text hiccup)))))))

(deftest fit-bounds-a-terminal-projection-and-names-what-it-omitted
  ;; `fit` IS the AI context generation boundary's one elision (owner ruling,
  ;; 2026-09-07). Callers that are not generating AI context do not call it:
  ;; `seon.render/fit-terminal` fits only `:seon.render/ai`, and the HTML
  ;; projection is emitted whole. Asserted HERE at the bounder, and at the
  ;; render seam by
  ;; `seon.render.web-test/a-five-megabyte-value-is-elided-for-ai-and-complete-for-html`.
  (let [profile (assoc (render/agent-render-profile
                        (test-support/effective-config))
                       :seon.render.profile/token-budget 16
                       :seon.render.profile/max-depth 8
                       :seon.render.profile/max-children 32
                       :seon.render.profile/composition :multiline
                       :seon.print/requery-id
                       [:seon.render.call/id :fixture/long])
        long-text (apply str (repeat 512 "outward "))
        fitted (print/fit {:seon.print/face :seon.print/projected
                           :seon.render/output :seon.render/ai
                           :seon.print/value long-text}
                          profile)]
    (is (= :seon.print/elided (:seon.print/face fitted))
        "an over-budget projection becomes a declared elision value")
    (is (= :characters (:seon.print/elision-unit fitted)))
    (is (= (count long-text) (:seon.render.data/total fitted))
        "the elision states the whole size it was cut from")
    (is (pos? (:seon.print/omitted fitted)))
    (is (< (:seon.print/omitted fitted) (:seon.render.data/total fitted))
        "a cut never omits its whole subject: the floor keeps what fits")
    (is (pos? (count (:seon.print/prefix fitted)))
        "and the characters that fit ride the cut as its prefix")
    (is (= [:seon.render.call/id :fixture/long]
           (:seon.print/requery-id fitted))
        "and it carries the identity the reader asks again with")
    (let [shown (edn/read-string (print/render-elision-ai fitted))]
      ;; AGENTS.md §2.4: the size an agent budgets by is estimated tokens.
      ;; The declared counts stay characters — they are the reader's
      ;; coordinates into the value, and a requery executes against them.
      (is (= :characters (:seon.print/elision-unit shown)))
      (is (= (tokens/estimate-of-characters (:seon.print/omitted fitted))
             (:seon.ai.tokens/estimate shown)))
      (is (= (:seon.print/omitted fitted) (:seon.print/omitted shown)))
      (is (= (:seon.print/prefix fitted) (:seon.print/prefix shown))))))

(deftest fit-preserves-breadth-and-long-strings
  (let [text (apply str (repeat 36 \x))
        line-width (:seon.print/width (print/default-options))
        profile (render/agent-render-profile
                 (test-support/effective-config))
        fitted
        (print/fit
         (admitted-node (observed-query-shape))
         profile)
        long-string-fit
        (print/fit
         (admitted-node (apply str (repeat (* 2 line-width) \y)))
         (assoc profile :seon.render.profile/token-budget 1))
        emitted
        (print/emit-text
         fitted
         (assoc (print/default-options)
                :seon.print/length nil
                :seon.print/level nil))]
    (is (str/includes? emitted (pr-str text))
        "a one-line string remains readable before structural breadth")
    (is (< (count (:seon.print/items fitted)) 116)
        "breadth past the profile's budget is cut, not carried")
    (is (= :seon.print/elided
           (:seon.print/face (peek (:seon.print/items fitted))))
        "and the cut is a declared elision value, never a silent drop")
    (is (= :seon.print/elided (:seon.print/face long-string-fit))
        "an over-budget string becomes a declared elision value")
    ;; The cut is not taken at the display width, and it is not taken at
    ;; nothing either: the floor keeps what the budget admits and the offset
    ;; names it.
    (is (pos? (:seon.render.data/next-offset long-string-fit))
        "the characters that fit are kept, never elided to nothing")
    (is (< (:seon.render.data/next-offset long-string-fit) line-width)
        "a string is never cut at the display width")
    (is (= (* 2 line-width) (:seon.render.data/total long-string-fit))
        "and the elision states the whole size it was cut from")))

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
                        (admitted-member-node
                         (sci-value "(create-ns 'face.ns)"))
                        no-cuts)
        atom-node (admitted-member-node (atom {:private/value 42}))
        atom-text (print/emit-text atom-node no-cuts)
        function-text (print/emit-text
                       (admitted-member-node
                        (sci-value "(fn named_face [] 1)"))
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
        "function class names are demunged")
    (testing "and a bare host reference at the root is missing, not described"
      (is (= :unserializable
             (:seon.sci.admit/reason
              (admit/admit {:seon.sci.admit/value (atom 1)
                            :seon.sci.admit/interrupt-fn (fn [])
                            :seon.sci.admit/caps admission-caps
                            :seon.config/on-core-error :record})))))))

(deftest agent-facing-object-faces-are-byte-stable-across-processes
  (let [expression
        (str "(require '[sci.core :as sci] '[seon.print :as print] "
             "'[seon.sci.admit :as admit]) "
             "(let [values [(sci/eval-string \"(create-ns 'stable.ns)\") "
             "(atom 1)] rendered "
             "(mapv (fn [value] "
             ;; admitted as a MEMBER: a bare host reference at the root is
             ;; missing, and this trial is about the object face's bytes
             "(let [node (first (:seon.print/items "
             "(:seon.sci.admit/print-node "
             "(admit/admit-value {:seon.sci.admit/value [value] "
             ":seon.sci.admit/interrupt-fn (fn []) "
             ":seon.sci.admit/caps "
             (pr-str
              (config/result-caps (test-support/effective-config)))
             " "
             ":seon.config/on-core-error :record}))))] "
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
                (assoc (render/agent-render-profile
                        (test-support/effective-config))
                       :seon.render.profile/token-budget 1
                       :seon.render.profile/max-depth 8
                       :seon.render.profile/max-children 1
                       :seon.render.profile/composition :multiline))
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
        {:seon.print/keys [text hiccup]} (print/emit-both node options)
        nested-text
        (print/emit-text
         (admitted-node {:rows [{:a 1 :b 'x} {:a 22 :b 'yy}]})
         options)]
    (is (= "\n| :a | :b |\n|----+----|\n|  1 |  x |\n| 22 | yy |\n"
           text))
    (is (= text (lexical-hiccup-text hiccup)))
    (is (hiccup/hiccup? hiccup))
    (is (str/includes? (hiccup/->string hiccup) "<table"))
    (is (= :table (-> hiccup (get-in [3 0]))))
    (is (= "{:rows [{:a 1, :b x} {:a 22, :b yy}]}" nested-text))
    (is (not (str/includes? nested-text "| :a |")))))

(deftest whole-value-elision-preserves-count-and-requery
  (let [text (apply str (repeat 512 "z"))
        fitted
        (print/fit
         {:seon.print/face :seon.print/string :seon.print/value text}
         (assoc (render/agent-render-profile
                 (test-support/effective-config))
                :seon.render.profile/token-budget 1
                :seon.render.profile/max-depth 1
                :seon.render.profile/max-children 1
                :seon.render.profile/composition :single-line
                :seon.print/requery-id [:seon.message/id "message-1"]))]
    (is (= :seon.print/elided (:seon.print/face fitted)))
    (is (= 512 (:seon.render.data/total fitted)))
    (is (= [:seon.message/id "message-1"]
           (:seon.print/requery-id fitted)))))

(deftest an-object-node-never-renders-as-empty-brackets
  ;; THE CLASS THE NODE CARRIES IS THE ONLY CONTENT AN OBJECT FACE HAS. An
  ;; emitter that drops it renders `#object[]`, and absence then reads as
  ;; content: nothing distinguishes an unrenderable value from an empty one
  ;; (docs/seon/issues/object-print-node-renders-as-empty-object.md).
  (is (= "#object[clojure.lang.Atom]"
         (print/emit-text {:seon.print/face :seon.print/object
                           :seon.print/class "clojure.lang.Atom"}
                          no-cuts)))
  (is (= "#object[clojure.lang.Atom]"
         (print/emit-text {:seon.print/face :seon.print/object
                           :seon.print/name "clojure.lang.Atom"}
                          no-cuts))
      "a stored node naming its class under the shared name key still prints it")
  (is (= "#object[sci.lang.Namespace \"face.ns\"]"
         (print/emit-text {:seon.print/face :seon.print/object
                           :seon.print/class "sci.lang.Namespace"
                           :seon.print/rep "\"face.ns\""}
                          no-cuts))
      "a node carrying a stable representation keeps it beside the class")
  (let [nameless (print/emit-text {:seon.print/face :seon.print/object} no-cuts)]
    (is (not= "#object[]" nameless))
    (is (str/includes? nameless ":seon.print/object-without-class")
        "a node that names no class says so instead of printing nothing")))

(deftest canonical-order-belongs-to-print-nodes
  (let [left (admitted-node (array-map :z 1 :a 2 :m 3))
        right (admitted-node (array-map :m 3 :a 2 :z 1))]
    (is (= "{:a 2, :m 3, :z 1}" (print/emit-text left no-cuts)))
    (is (= (print/emit-both left no-cuts) (print/emit-both right no-cuts)))
    (is (= "[3 1 2]" (print/emit-text (admitted-node [3 1 2]) no-cuts)))
    (is (= "(3 1 2)" (print/emit-text (admitted-node '(3 1 2)) no-cuts)))))

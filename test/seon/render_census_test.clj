(ns seon.render-census-test
  "Census of the law that nothing calls a render function by name: the value printer selects and calls it.
  The render functions are the ones the schema pairs declare, read from the packaged schema forms and from the
  Clojure source that declares pairs beside them; usages come from clj-kondo's analysis of `src`."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [seon.census :as census]
            [seon.db :as db]
            [seon.fn.analyzer :as analyzer]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(def ^:private printer-namespaces
  "The namespaces that may call a render: the printer's and the render owner's."
  (into #{} (map census/file-namespace) census/render-files))

(def ^:private entry-functions
  "What the printer set must define for the census to be looking at the printer."
  '#{seon.render/render-call seon.print/emit-both})

(def ^:private render-entries
  "Functions that call a selected render: no function outside the printer set calls them."
  '#{seon.render/render-call seon.render/declared-projection seon.render/selection})

(def ^:private text-as-content
  "AI renders whose answer is text by design, not a rendering of data as text."
  '#{my.program/render-source-ai seon.cluster.instruction/instruction-ai})

(def ^:private source-files (delay (census/files ["src"] [".clj" ".cljc"])))

(def ^:private faces
  "Render symbols by face."
  [:map-of :keyword [:set :qualified-symbol]])

(defn declared
  "Render symbols by face, declared in `data`, schema forms or source forms, at any depth."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                   :seon.schema.admission/reason "Schema forms and source forms are any data."}]]
                  faces]}
  [data]
  (let [by-face (fn by-face [x]
                  (concat (when (map? x) (for [k [:seon.render/ai :seon.render/html :seon.render/form]
                                               :let [v (get x k)
                                                     v (if (and (seq? v) (= 'quote (first v))) (second v) v)]
                                               :when (qualified-symbol? v)]
                                           [k v]))
                          (when (coll? x) (mapcat by-face (if (map? x) (mapcat identity x) x)))))]
    (reduce (fn [acc [k v]] (update acc k (fnil conj #{}) v)) {} (by-face data))))

(defn render-faces
  "The declared render symbols by face from the packaged schema forms and every Clojure source form."
  {:malli/schema [:=> [:cat] faces]}
  []
  (apply merge-with into
         (declared (vals (schema.edn/packaged-forms)))
         (for [path @source-files :let [forms (census/top-level-forms path)] :when (vector? forms)]
           (declared forms))))

(defn- qualified
  "The qualified symbol a usage names."
  {:malli/schema [:=> [:cat :map] :qualified-symbol]}
  [usage]
  (symbol (str (::analyzer/to usage)) (str (::analyzer/name usage))))

(defn- outside-printer
  "Usages in `analysis` of a symbol of `targets` from a namespace outside `printer`, as sites."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol] [:set :symbol]] [:vector :map]]}
  [analysis targets printer]
  (vec (distinct (for [usage (::analyzer/var-usages analysis)
                       :when (and (contains? targets (qualified usage))
                                  (not (contains? printer (::analyzer/from usage))))]
                   {:in (::analyzer/from usage) :var (::analyzer/from-var usage) :uses (qualified usage)}))))

(defn- printer-live?
  "True when the analysis defines every entry function inside the printer set."
  {:malli/schema [:=> [:cat :map [:set :symbol]] :boolean]}
  [analysis printer]
  (let [defined (into #{} (comp (filter #(contains? printer (::analyzer/ns %)))
                                (map #(symbol (str (::analyzer/ns %)) (str (::analyzer/name %)))))
                      (::analyzer/var-definitions analysis))]
    (every? defined entry-functions)))

(def ^:private analysis
  (delay (analyzer/analyze {::analyzer/paths ["src"]})))

(defn- planted
  "The analysis of one planted source `text`, as a file of src."
  {:malli/schema [:=> [:cat :string] :map]}
  [text]
  (analyzer/analyze {::analyzer/sources {(.getCanonicalPath (io/file "src/seon/census_planted.clj")) text}}))

(deftest ^{:seon.test/long "Analyzes every source file of src through clj-kondo once; measured 2.7 to 4.1 seconds."
           :seon.test/long-ms 30000}
  no-render-function-is-called-outside-the-printer
  (let [declared-faces (render-faces)
        renders (into #{} (mapcat val) declared-faces)
        sites (outside-printer @analysis renders printer-namespaces)]
    (is (seq (:seon.render/ai declared-faces)) "the census found declared AI renders")
    (is (> (count renders) 100) "the census read the schema pairs and the source pairs")
    (is (printer-live? @analysis printer-namespaces) "the printer set defines the entries the census guards")
    (is (= [] sites) "each site calls a declared render function outside the printer set")))

(deftest ^{:seon.test/long "Analyzes every source file of src through clj-kondo once; measured 2.7 to 4.1 seconds."
           :seon.test/long-ms 30000}
  no-selection-entry-is-called-outside-the-printer
  (is (= [] (outside-printer @analysis render-entries printer-namespaces))
      "each site calls render-call, declared-projection or selection outside the printer set"))

(defn- admits-string?
  "True when `output`, a contract's output form, accepts a string under `registry`."
  {:malli/schema [:=> [:cat :any :any] :boolean]}
  [registry output]
  (boolean (m/validate (m/schema output {:registry registry}) "x")))

(defn- outputs
  "The output forms of the arities of a function contract form."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [contract]
  (->> (tree-seq vector? seq contract)
       (filter #(and (vector? %) (= :=> (first %))))
       (mapv #(nth % 2))))

(defn- string-renders
  "The AI render symbols outside the printer set and `text-as-content` whose contract output admits a string."
  {:malli/schema [:=> [:cat :any [:set :qualified-symbol]] [:vector :qualified-symbol]]}
  [registry ai-renders]
  (vec (sort (for [sym ai-renders
                   :let [v (try (requiring-resolve sym) (catch Throwable _ nil))
                         contract (:malli/schema (meta v))]
                   :when (and contract (not (contains? printer-namespaces (symbol (namespace sym))))
                              (not (contains? text-as-content sym))
                              (some #(admits-string? registry %) (outputs contract)))]
               sym))))

(deftest ^{:seon.test/long "Reads every packaged schema and source file once and resolves each declared AI render; measured about 3 seconds."
           :seon.test/long-ms 30000}
  no-ai-render-returns-text
  (support/with-database
   (fn [connection]
     (let [registry (:seon.schema.projection/registry (db/carried-projection (db/db connection)))
           ai-renders (:seon.render/ai (render-faces))]
       (is (> (count ai-renders) 40) "the census found the declared AI renders")
       (is (= [] (string-renders registry ai-renders))
           "each AI render's output schema admits a string and its pair does not declare text as content")))))

(deftest the-render-rules-catch-a-planted-call-and-a-string-render
  (let [calls (planted (str "(ns seon.census-planted (:require [seon.render :as render] [seon.error :as error]))\n"
                            "(defn f [x] (render/render-call x))\n(defn g [x] (error/render-chain-ai x))"))
        renders '#{seon.error/render-chain-ai}]
    (is (= #{'seon.render/render-call} (into #{} (map :uses) (outside-printer calls render-entries printer-namespaces)))
        "a planted render-call is caught")
    (is (= #{'seon.error/render-chain-ai} (into #{} (map :uses) (outside-printer calls renders printer-namespaces)))
        "a planted call of a declared render is caught")
    (is (= [] (outside-printer calls render-entries #{'seon.census-planted})) "the printer set is spared")
    (is (= {:seon.render/ai '#{a/b}} (declared '{:seon.render/ai a/b})) "a declared pair is read from data")
    (is (= {:seon.render/ai '#{a/b}} (declared '[{:seon.render/ai (quote a/b)}])) "and from a quoted source form")
    (is (admits-string? m/default-registry :string) "a string output is detected")
    (is (not (admits-string? m/default-registry [:map [:a :string]])) "a nested string is not")))

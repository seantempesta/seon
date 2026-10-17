(ns seon.render-bench
  "The render benchmark: what every declared render pair, and the value printer alone, produce on pinned samples.

  `pairs` reads the declared render pairs from the schema registry, the lookup `seon.render/declared-producers`
  uses. `samples-of` picks the pinned inputs: real values of each pair's schema from a database, a generated value
  of the schema where it holds none, and printer-only data with no render. `resample!` writes them to
  `samples-path`; every function that reads or writes a file takes the checkout `root`, since a JVM runs a
  committed archive and its working directory is not the checkout. `bench!` shows every sample through `seon.render.value/show` under the agent profile's budget on
  both faces and writes `results/<label>.edn`; `diff-runs` and `noise` compare two runs. The sample set is data,
  so two runs at different commits measure the same inputs. Run from the JVM REPL of a cluster:
  `(seon.render-bench/bench! custody)`; `-main` prints a diff or stores a noise band from two stored runs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.printer-baseline :as baseline]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.schema.internal :as internal]
            [seon.sci.eval :as sci.eval]))

(def directory
  "Where the benchmark's samples, runs and noise band live."
  "docs/research/agent-platform/2026-10-16/render-bench")

(defn samples-path
  "The pinned sample set under the checkout `root`."
  {:malli/schema [:=> [:cat :string] :string]}
  [root]
  (str root "/" directory "/samples.edn"))

(defn noise-path
  "The measured run-to-run noise band under the checkout `root`."
  {:malli/schema [:=> [:cat :string] :string]}
  [root]
  (str root "/" directory "/noise.edn"))

(defn run-path
  "The file the run named `label` is stored in under the checkout `root`."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [root label]
  (str root "/" directory "/results/" label ".edn"))

(def ^:private npe-task
  "The task a declared program render's unbound-Var failure belongs to."
  'seon.render/interpreted-declared-renders-invoke-an-unbound-var)

(def ^:private faces
  "The two faces measured: the output each is selected for and the answer member that carries it."
  {:ai {:seon.render/output :seon.render/ai :seon.render/outputs #{:text}}
   :html {:seon.render/output :seon.render/html :seon.render/outputs #{:hiccup}}})

(def ^:private per-schema "Real samples kept for each schema: the largest, a middle one and the smallest." 3)

(def ^:private candidate-limit "Entities read to find a schema's real samples." 400)

(def ^:private node-limit "Largest sample kept, in nodes: a larger value is a probe of its own." 20000)

(def ^:private warm-calls "Unmeasured calls before the samples." 1)

(def ^:private measured-calls "Measured calls per sample and face." 5)

(def ^:private generator-seed "The seed every generated sample uses." 1)

(defn- schema-properties
  "The properties `seon.render/declared-producers` reads from schema `k`: its own and its entity properties.
  A schema the registry cannot construct answers `{::unreadable type}`."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map]}
  [registry k]
  (try (let [s (mr/schema registry k)]
         (merge (m/properties s) (when (m/schema? s) (internal/entity-properties s))))
       (catch clojure.lang.ExceptionInfo refused {::unreadable (:type (ex-data refused))})))

(defn unreadable
  "The schema keys of `projection`'s registry that cannot be constructed, with the Malli failure type."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector [:tuple :qualified-keyword :keyword]]]}
  [projection]
  (let [registry (:seon.schema.projection/registry projection)]
    (->> (keys (mr/schemas registry))
         (filter qualified-keyword?)
         (keep (fn [k] (when-let [type (::unreadable (schema-properties registry k))] [k type])))
         (sort-by first)
         vec)))

(defn pairs
  "The declared render pairs of `projection` as `{:schema :ai :html}`."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :map]]}
  [projection]
  (let [registry (:seon.schema.projection/registry projection)]
    (->> (keys (mr/schemas registry))
         (filter qualified-keyword?)
         (keep (fn [k]
                 (let [p (schema-properties registry k)]
                   (when (or (:seon.render/ai p) (:seon.render/html p))
                     (cond-> {:schema k}
                       (:seon.render/ai p) (assoc :ai (:seon.render/ai p))
                       (:seon.render/html p) (assoc :html (:seon.render/html p)))))))
         (sort-by :schema)
         vec)))

(defn- function-output
  "The output schemas of the contract of `function`."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol] [:vector :seon.schema/value]]}
  [projection function]
  (let [contract (get (:seon.schema.projection/function-contracts projection) function)]
    (if (vector? contract)
      (case (first contract)
        :=> [(nth contract 2 nil)]
        :function (into [] (keep #(when (vector? %) (nth % 2 nil))) (rest contract))
        [])
      [])))

(defn admits-string?
  "True when a contract of `function` lists a string among its outputs."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol] :boolean]}
  [projection function]
  (true? (some (fn [output]
                 (some #(or (= :string %) (and (vector? %) (= :string (first %)))) (tree-seq coll? seq output)))
               (function-output projection function))))

(defn- edn-data?
  "True when `value` is built only of plain EDN collections and scalars: a value a fixture can hold."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (cond
    (record? value) false
    (map? value) (every? (fn [[k v]] (and (edn-data? k) (edn-data? v))) value)
    (or (vector? value) (set? value) (sequential? value)) (every? edn-data? value)
    :else (or (nil? value) (boolean? value) (number? value) (string? value) (keyword? value) (symbol? value)
              (uuid? value) (inst? value) (char? value))))

(defn- size
  "The number of nodes of `value`."
  {:malli/schema [:=> [:cat :seon.schema/value] :int]}
  [value]
  (count (tree-seq coll? seq value)))

(defn- required-attributes
  "The attributes schema `k` requires of an entity, from the projection's shape rows."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword] [:set :qualified-keyword]]}
  [projection k]
  (:seon.schema/required-attrs (get (into {} (:seon.schema.projection/shape-rows projection)) k) #{}))

(defn- fits?
  "True when `value` satisfies schema `k` and, an error, is of exactly the type `k` names."
  {:malli/schema [:=> [:cat [:=> [:cat :seon.schema/value] :boolean] :qualified-keyword :seon.schema/value] :boolean]}
  [valid? k value]
  (and (map? value)
       (valid? value)
       (or (not (contains? value :seon.error/type)) (= k (:seon.error/type value)))))

(defn- spread
  "Up to `n` of `candidates` ordered by size: the largest, then evenly down to the smallest."
  {:malli/schema [:=> [:cat [:vector :map] pos-int?] [:vector :map]]}
  [candidates n]
  (let [ordered (vec (reverse (sort-by :nodes candidates)))
        total (count ordered)]
    (if (<= total n)
      ordered
      (mapv #(nth ordered (quot (* % (dec total)) (dec n))) (range n)))))

(defn- owned-elsewhere?
  "True when `value` is an entity of another row schema than `k` that declares its own render: its identity
  attribute's `:seon.program/row-schema` names that schema, so the value is that entity, not a `k`."
  {:malli/schema [:=> [:cat :map :qualified-keyword :map] :boolean]}
  [registry k value]
  (boolean (some (fn [attribute]
                   (let [row (:seon.program/row-schema (schema-properties registry attribute))]
                     (and row (not= row k)
                          (let [p (schema-properties registry row)] (or (:seon.render/ai p) (:seon.render/html p))))))
                 (filter qualified-keyword? (keys value)))))

(defn real-samples
  "Up to `per-schema` real values of schema `k` read from `database`.
  They are entities that hold every attribute `k` requires and satisfy it, and
  are not another row schema's rendered entity (`owned-elsewhere?`), each
  pinned by its entity id and content digest."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :qualified-keyword] [:vector :map]]}
  [database projection k]
  (let [attributes (required-attributes projection k)
        registry (:seon.schema.projection/registry projection)
        valid? (schema/projection-validator projection k)]
    (if (empty? attributes)
      []
      (let [entities (take candidate-limit
                           (db/q (into '[:find [?e ...] :where] (map (fn [a] ['?e a])) (sort attributes)) database))
            candidates (into []
                             (comp (map (fn [e] (db/pull database '[*] e)))
                                   (filter #(and (edn-data? %) (fits? valid? k %) (not (owned-elsewhere? registry k %))))
                                   (map (fn [v] {:value v :nodes (size v) :source {:db/id (:db/id v) :digest (id/id v)}}))
                                   (filter #(<= (:nodes %) node-limit)))
                             entities)]
        (spread candidates per-schema)))))

(defn generated-sample
  "A value of schema `k` generated under `generator-seed`, or `{:no-sample reason}`.
  An error variant gets the type `k` names; the reason is the generator's refusal
  type, or `:no-generated-value-fits` when nothing generated satisfies `k`."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword] :map]}
  [projection k]
  (let [registry (:seon.schema.projection/registry projection)
        valid? (schema/projection-validator projection k)
        generated (try (mg/generate k {:registry registry :seed generator-seed :size 8})
                       (catch clojure.lang.ExceptionInfo refused {::refused (:type (ex-data refused))}))
        value (some #(when (and (edn-data? %) (fits? valid? k %)) %)
                    [generated (when (map? generated) (assoc generated :seon.error/type k))])]
    (cond
      value {:value value :nodes (size value) :source {:generated {:seed generator-seed} :digest (id/id value)}}
      (::refused generated) {:no-sample (::refused generated)}
      (map? generated) {:no-sample :no-generated-value-is-plain-data-that-fits}
      :else {:no-sample :no-generated-value-fits})))

(defn- printer-only
  "The samples that have no render: plain data of the shapes the value printer meets."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [word (fn [i] (str "w" (Integer/toString (* 7919 (inc i)) 36)))
        sentence (fn [i n] (str/join " " (map #(word (+ i %)) (range n))))]
    [{:id "plain/small-map" :value {:a 1 :b "two" :c [3 4]}}
     {:id "plain/flat-map-200" :value (into (sorted-map) (map (fn [i] [(keyword (str "k" i)) (word i)])) (range 200))}
     {:id "plain/long-string-3000-words" :value (sentence 0 3000)}
     {:id "plain/multiline-string" :value (str/join "\n" (map #(sentence % 12) (range 400)))}
     {:id "plain/vector-5000-numbers" :value (vec (range 5000))}
     {:id "plain/vector-2000-maps" :value (mapv (fn [i] {:id i :name (word i) :tags #{(keyword (word i))}}) (range 2000))}
     {:id "plain/nested-depth-12" :value (reduce (fn [inner i] {(keyword (str "level" i)) inner :sibling (word i)}) {:leaf "end"} (range 12))}
     {:id "plain/set-1000" :value (set (map word (range 1000)))}
     {:id "plain/tuples-1500"
      :value (mapv (fn [i] [(symbol (str "seon.bench" (mod i 40)) (str "f" i)) (sentence i (+ 3 (mod i 17)))]) (range 1500))}
     {:id "plain/wide-and-deep"
      :value {:rows (mapv (fn [i] {:id i :values (mapv #(* i %) (range 30)) :note (sentence i 8)}) (range 60))
              :meta {:a {:b {:c {:d {:e (sentence 0 100)}}}}}}}]))

(defn samples-of
  "The pinned sample set read from `custody`'s database and projection.
  A sample is `{:id :schema :kind :value :source}`: a real value, a generated one or printer-only data; a schema
  with none is listed in `:no-sample`."
  {:malli/schema [:=> [:cat :map] :map]}
  [{database :seon.db/db}]
  (let [projection (db/carried-projection database)
        by-schema (mapv (fn [{k :schema :as pair}]
                          (let [real (real-samples database projection k)
                                generated (when (empty? real) (generated-sample projection k))]
                            (cond
                              (seq real) (assoc pair :found real :kind :real)
                              (:value generated) (assoc pair :found [generated] :kind :generated)
                              :else (assoc pair :found [] :kind :none :reason (:no-sample generated)))))
                        (pairs projection))]
    {:samples (into (vec (mapcat (fn [{k :schema :keys [found kind]}]
                                   (map-indexed (fn [i s] (assoc (dissoc s :nodes) :id (str (symbol k) "#" i) :schema k :kind kind))
                                                found))
                                 by-schema))
                    (map #(assoc % :kind :printer-only))
                    (printer-only))
     :no-sample (vec (keep #(when (= :none (:kind %)) {:schema (:schema %) :reason (:reason %)}) by-schema))
     :unreadable (unreadable projection)
     :pairs (mapv #(dissoc % :found :kind :reason) by-schema)}))

(defn resample!
  "Read the sample set from `custody` and pin it in `samples-path`."
  {:malli/schema [:=> [:cat :map :string [:map [:commit :string] [:date :string]]] :map]}
  [custody root {:keys [commit date]}]
  (let [recorded (assoc (samples-of custody) :commit commit :date date)]
    (fs/write-edn-atomically! (samples-path root) recorded)
    recorded))

(defn read-samples
  "The pinned sample set."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (edn/read-string (slurp (samples-path root))))

(defn- root-cause
  "The innermost cause of `failure`."
  {:malli/schema [:=> [:cat :seon.error/throwable] :seon.error/throwable]}
  [failure]
  (loop [t failure] (if-let [cause (ex-cause t)] (recur cause) t)))

(defn- failure-data
  "What the bench keeps of a render's failure: its class or error type, its message and the task a known one belongs to."
  {:malli/schema [:=> [:cat :seon.schema/value] :map]}
  [failure]
  (cond
    (instance? Throwable failure)
    (let [t (root-cause failure)]
      (cond-> {:class (.getName (class t)) :message (or (ex-message t) "")}
        (instance? NullPointerException t) (assoc :task npe-task)))
    (map? failure) (cond-> {:message (:seon.error/message failure "")}
                     (:seon.error/type failure) (assoc :type (:seon.error/type failure)))
    :else {:message "a failure that is neither an error map nor a Throwable"}))

(defn- cut-data
  "The cuts of an answer as `[path omitted total bound-by]` in answer order."
  {:malli/schema [:=> [:cat :map] [:vector [:tuple [:maybe :seon.render.data/path] [:maybe :seon.print/omitted] :any [:maybe :seon.print/bound-by]]]]}
  [answer]
  (mapv (juxt :seon.render.data/path :seon.print/omitted :seon.render.data/total :seon.print/bound-by)
        (:seon.render.value/cuts answer)))

(defn- text-render?
  "True when the answer's root is a render's text: a projected string where the value was a map a render was offered."
  {:malli/schema [:=> [:cat :map :seon.schema/value] :boolean]}
  [answer sample]
  (let [root (:seon.render.value/tree answer)]
    (boolean (and (map? sample)
                  (empty? (:seon.render.value/diagnostics answer))
                  (= :seon.print/projected (:seon.print/face root))
                  (string? (:seon.print/value root))))))

(defn- median-sample
  "The measured call with the median wall time."
  {:malli/schema [:=> [:cat [:vector :map]] :map]}
  [measured]
  (nth (vec (sort-by :seon.profile/ms measured)) (quot (dec (count measured)) 2)))

(defn measure-sample
  "Show `sample` on `face` through `seon.render.value/show`, requested as `schema` (its producer's declared
  schema, `:seon.render/schema`) when given, and answer the row.
  It holds the agent profile's budget, the selected producer, ms and allocation (median of `measured-calls` after `warm-calls`), the render output's
  tokens, the printed tokens, the cuts with their paths, whether the render answered text, and any error."
  {:malli/schema [:=> [:cat :map [:enum :ai :html] :seon.schema/value [:maybe :qualified-keyword]] :map]}
  [custody face sample schema]
  (let [request (cond-> (merge (baseline/unit custody sample nil) (faces face))
                  schema (assoc :seon.render/schema schema))
        show #(value/show request)
        first-call (try (profile/measure show) (catch Throwable t {::thrown t}))]
    (if-let [thrown (::thrown first-call)]
      {:face face :error (failure-data thrown)}
      (let [_ (dotimes [_ (dec warm-calls)] (show))
            measured (vec (repeatedly measured-calls #(profile/measure show)))
            median (median-sample measured)
            answer (:seon.profile/value median)
            diagnostics (:seon.render.value/diagnostics answer)]
        (cond-> {:face face
                 :producer (get-in answer [:seon.render.value/tree :seon.render.call/selected-producer])
                 :ms (:seon.profile/ms median)
                 :alloc-bytes (:seon.profile/alloc-bytes median)
                 :samples-ms (mapv :seon.profile/ms measured)
                 :out-tokens (tokens/estimate-of-characters (:seon.render.value/whole-characters answer 0))
                 :printed-tokens (:seon.render.value/tokens answer 0)
                 :cuts (cut-data answer)
                 :text? (text-render? answer sample)}
          (seq diagnostics) (assoc :error (failure-data (:seon.render.value/failure (first diagnostics)))))))))

(defn- commit-of
  "The commit the running program was loaded from, and whether its source is uncommitted.
  A JVM runs the archive `data/source/<commit>`, whose directory name is the commit; any other
  working directory is the checkout `root`, whose HEAD and uncommitted source changes answer."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (let [directory-name (.getName (io/file (System/getProperty "user.dir")))
        archive? (= "source" (.getName (.getParentFile (io/file (System/getProperty "user.dir")))))
        head (if archive? directory-name (str/trim (:out (shell/sh "git" "-C" root "rev-parse" "HEAD"))))
        dirty (when-not archive?
                (remove str/blank? (str/split-lines (:out (shell/sh "git" "-C" root "status" "--porcelain" "--" "src" "resources")))))]
    {:commit head :short (subs head 0 9) :dirty? (boolean (seq dirty))}))

(defn measure-all
  "Every pinned sample of `samples` measured on both faces under `custody`, each requested as its schema.
  A row names its sample, its schema and the render the schema declares for the face; a row whose selected
  producer differs from the declared one is a selection miss (`:miss? true`)."
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [custody {:keys [samples] declared-pairs :pairs}]
  (let [projection (db/carried-projection (:seon.db/db custody))
        declared (into {} (map (juxt :schema identity)) declared-pairs)]
    (vec (for [sample samples
               face [:ai :html]
               :let [expected (get-in declared [(:schema sample) face])]]
           (let [row (merge {:sample (:id sample) :schema (:schema sample) :kind (:kind sample) :render expected}
                            (when expected {:admits-string? (admits-string? projection expected)})
                            (measure-sample custody face (:value sample) (:schema sample)))]
             (cond-> row
               (and expected (not= expected (:producer row))) (assoc :miss? true)))))))

(defn reach
  "How many of `run`'s declared AI renders some row selected, of those its samples declare, with each selection
  miss as `[schema declared selected]`."
  {:malli/schema [:=> [:cat :map] :map]}
  [run]
  (let [rows (filter #(and (= :ai (:face %)) (:render %)) (:rows run))]
    {:declared (count (set (map :render rows)))
     :reached (count (set (keep #(when (= (:render %) (:producer %)) (:render %)) rows)))
     :misses (vec (distinct (keep #(when (not= (:render %) (:producer %)) [(:schema %) (:render %) (:producer %)]) rows)))}))

(defn render-cuts
  "The pinned samples whose selected render's AI output the printer cut, each shown once under `custody`.
  A row is `{:sample :producer :cuts}`; samples no render was selected for are the printer's own."
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [custody {:keys [samples]}]
  (let [request (merge (baseline/unit custody {} nil) (:ai faces))]
    (into []
          (keep (fn [sample]
                  (let [answer (value/show (cond-> (assoc request :seon.render/value (:value sample))
                                             (:schema sample) (assoc :seon.render/schema (:schema sample))))
                        producer (get-in answer [:seon.render.value/tree :seon.render.call/selected-producer])]
                    (when (and producer (seq (:seon.render.value/cuts answer)))
                      {:sample (:id sample) :producer producer :cuts (cut-data answer)}))))
          samples)))

(defn selection-misses
  "The pinned samples whose AI face, shown once under `custody` and requested as the sample's schema, selects a
  producer other than the AI render that schema declares: `{:sample :schema :declared :selected}` each."
  {:malli/schema [:=> [:cat :map :map] [:vector :map]]}
  [custody {:keys [samples] declared-pairs :pairs}]
  (let [request (merge (baseline/unit custody {} nil) (:ai faces))
        declared (into {} (map (juxt :schema :ai)) declared-pairs)]
    (into []
          (keep (fn [{k :schema :as sample}]
                  (when-let [expected (declared k)]
                    (let [answer (value/show (assoc request :seon.render/value (:value sample) :seon.render/schema k))
                          selected (get-in answer [:seon.render.value/tree :seon.render.call/selected-producer])]
                      (when (not= expected selected)
                        {:sample (:id sample) :schema k :declared expected :selected selected})))))
          samples)))

(defn bench!
  "Measure every pinned sample on both faces and store `results/<label>.edn`.
  `label` defaults to the short commit."
  {:malli/schema [:function [:=> [:cat :map :string] :map] [:=> [:cat :map :string [:maybe :string]] :map]]}
  ([custody root] (bench! custody root nil))
  ([custody root label]
   (let [pinned (read-samples root)
         head (commit-of root)
         run (merge head
                    {:label (or label (:short head))
                     :samples-commit (:commit pinned)
                     :basis (db/basis-t (:seon.db/db custody))
                     :budget (:seon.render.profile/token-budget (:seon.render/profile (baseline/unit custody {} nil)))
                     :rows (measure-all custody pinned)})]
     (io/make-parents (run-path root "x"))
     (fs/write-edn-atomically! (run-path root (:label run)) run)
     run)))

(defn read-run
  "The stored run named `label`."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [root label]
  (edn/read-string (slurp (run-path root label))))

(defn- row-key "The identity of a row across runs." [row] [(:sample row) (:face row)])

(defn- relative
  "The change from `a` to `b` as a fraction of the larger, 0 when both are 0."
  {:malli/schema [:=> [:cat number? number?] number?]}
  [a b]
  (let [high (max (abs a) (abs b))]
    (if (zero? high) 0.0 (/ (double (- b a)) high))))

(defn- percentile
  "The `p` quantile of `xs`, 0 for none."
  {:malli/schema [:=> [:cat [:sequential number?] number?] number?]}
  [xs p]
  (if (empty? xs) 0.0 (nth (vec (sort xs)) (min (dec (count xs)) (long (* p (count xs)))))))

(defn noise
  "The run-to-run noise of two runs of one commit over the rows both measured.
  It is the 95th percentile of the relative change in ms and in allocation
  and of the absolute change in ms."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [a b]
  (let [index (into {} (map (juxt row-key identity)) (:rows b))
        both (keep (fn [row] (let [other (index (row-key row))]
                               (when (and other (:ms row) (:ms other)) [row other])))
                   (:rows a))]
    {:rows (count both)
     :ms-relative-p95 (percentile (map (fn [[x y]] (abs (relative (:ms x) (:ms y)))) both) 0.95)
     :alloc-relative-p95 (percentile (map (fn [[x y]] (abs (relative (:alloc-bytes x) (:alloc-bytes y)))) both) 0.95)
     :ms-absolute-p95 (percentile (map (fn [[x y]] (abs (- (:ms x) (:ms y)))) both) 0.95)
     :commit (:commit a)}))

(defn- stored-noise
  "The stored noise band, else a coarse default."
  {:malli/schema [:=> [:cat :string] :map]}
  [root]
  (if (.exists (io/file (noise-path root)))
    (edn/read-string (slurp (noise-path root)))
    {:ms-relative-p95 0.25 :alloc-relative-p95 0.05 :ms-absolute-p95 1.0}))

(defn- changed
  "The difference of row `a` to row `b`: tokens, cuts added and removed, and ms and allocation beyond twice `limits`."
  {:malli/schema [:=> [:cat [:maybe :map] [:maybe :map] :map] :map]}
  [a b limits]
  (let [paths (fn [row] (set (map first (:cuts row))))
        ms-moved? (and (:ms a) (:ms b)
                       (> (abs (relative (:ms a) (:ms b))) (* 2 (:ms-relative-p95 limits)))
                       (> (abs (- (:ms b) (:ms a))) (* 2 (:ms-absolute-p95 limits))))
        alloc-moved? (and (:alloc-bytes a) (:alloc-bytes b)
                          (> (abs (relative (:alloc-bytes a) (:alloc-bytes b))) (* 2 (:alloc-relative-p95 limits))))]
    (cond-> {}
      (not= (:out-tokens a) (:out-tokens b)) (assoc :out-tokens [(:out-tokens a) (:out-tokens b)])
      (not= (:printed-tokens a) (:printed-tokens b)) (assoc :printed-tokens [(:printed-tokens a) (:printed-tokens b)])
      (seq (remove (paths b) (paths a))) (assoc :cuts-removed (vec (remove (paths b) (paths a))))
      (seq (remove (paths a) (paths b))) (assoc :cuts-added (vec (remove (paths a) (paths b))))
      (not= (some? (:error a)) (some? (:error b))) (assoc :error [(:error a) (:error b)])
      (not= (:producer a) (:producer b)) (assoc :producer [(:producer a) (:producer b)])
      ms-moved? (assoc :ms [(:ms a) (:ms b)])
      alloc-moved? (assoc :alloc-bytes [(:alloc-bytes a) (:alloc-bytes b)]))))

(defn diff-runs
  "What changed from run `a` to run `b`, per sample and face.
  Ms and allocation count only beyond twice the stored noise; rows present in
  one run only are listed as `:only-in-a` and `:only-in-b`."
  {:malli/schema [:=> [:cat :string :map :map] :map]}
  [root a b]
  (let [limits (stored-noise root)
        a-rows (into {} (map (juxt row-key identity)) (:rows a))
        b-rows (into {} (map (juxt row-key identity)) (:rows b))]
    {:from (select-keys a [:label :commit :basis :samples-commit])
     :to (select-keys b [:label :commit :basis :samples-commit])
     :noise limits
     :changes (vec (keep (fn [k] (let [c (changed (a-rows k) (b-rows k) limits)]
                                   (when (seq c) (assoc c :sample (first k) :face (second k)))))
                         (sort (filter b-rows (keys a-rows)))))
     :only-in-a (vec (remove b-rows (keys a-rows)))
     :only-in-b (vec (remove a-rows (keys b-rows)))}))

(defn- path-text
  "The names of a cut path joined by slashes."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]] :string]}
  [path]
  (str/join "/" (map #(if (or (keyword? %) (symbol? %)) (str (symbol %)) (str %)) path)))

(defn- change-line
  "One change as data: the sample, the face and each changed member's before and after."
  {:malli/schema [:=> [:cat :map] :map]}
  [change]
  (-> change
      (update :cuts-added #(some->> % (mapv path-text)))
      (update :cuts-removed #(some->> % (mapv path-text)))
      (->> (remove (comp nil? val)) (into {}))))

(defn diff-text
  "The diff of runs `a` and `b` shown through the value printer: its summary, then the changed rows."
  {:malli/schema [:=> [:cat :map :string :map :map] :string]}
  [custody root a b]
  (let [{:keys [changes] :as diff} (diff-runs root a b)
        request (assoc (baseline/unit custody (assoc diff :changes (mapv change-line changes)) 4096)
                       :seon.render/outputs #{:text})]
    (:seon.render.value/text (value/show request))))

(defn noise-text
  "Store the noise of runs `a` and `b` in `noise-path` and show it through the value printer."
  {:malli/schema [:=> [:cat :map :string :map :map] :string]}
  [custody root a b]
  (let [measured (noise a b)]
    (fs/write-edn-atomically! (noise-path root) measured)
    (:seon.render.value/text (value/show (assoc (baseline/unit custody measured 1024) :seon.render/outputs #{:text})))))
(defn running-custody
  "The custody of the live cluster `cluster-name` in this JVM, read-only: its database and a fork of its context."
  {:malli/schema [:=> [:cat :string] :map]}
  [cluster-name]
  (let [instance (get @runtime/running-instances cluster-name)
        connection (:seon.boot/cluster-connection instance)
        database (db/db connection)]
    {:seon.db/db database
     :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (:seon.sci.eval/ctx instance) database connection)
     :seon.boot/cluster-name cluster-name}))

(defn -main
  "`diff A B` shows what changed between stored runs; `noise A B` stores their noise band.
  Both read the live cluster in this JVM, `default` unless `SEON_CLUSTER` names another."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& [command a b]]
  (let [custody (running-custody (or (System/getenv "SEON_CLUSTER") "default"))
        root (System/getProperty "user.dir")]
    (case command
      "diff" (println (diff-text custody root (read-run root a) (read-run root b)))
      "noise" (println (noise-text custody root (read-run root a) (read-run root b)))
      (println "usage: diff A B | noise A B")))
  nil)

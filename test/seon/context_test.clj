(ns seon.context-test
  "The sealed context-blocks suite (contract section 7.1, 2026-07-28).

  Fixed seeds 2026072801-2026072809; every planted identity derives
  from its test's seed, no wall clock and no `random-uuid` participates
  in an oracle. Oracles are independent ledgers derived from planted
  facts — never the producer compared with itself: expected ordering is
  recomputed here from the literal band table, expected texts from the
  planted marker contents, expected truncation from `subs`, and the
  expansion property drives a test-only reference walker.

  THE SUITE OWNS ITS OWN PROJECTIONS, the way the block suite does:
  what it proves is that the MECHANISM is generic, and a suite that
  reached for `seon.problems` would prove two things by accident."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.context :as context]
            [seon.flow :as seon.flow]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.schema :as schema]
            [seon.test-support :as support])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Projections this suite owns, and the invocation ledger
;;; ---------------------------------------------------------------------------

(def ^:dynamic *invocations*
  "Records every projection invocation as {name, db}. The ledger the
  reduction and identity oracles read."
  nil)

(defn- note!
  [name db]
  (when *invocations*
    (swap! *invocations* conj {::name name ::db db})))

(defn- marker-content
  "The planted content of the block's own marker message, or nil."
  [db block-name]
  (d/q '[:find ?content .
         :in $ ?id
         :where
         [?m :seon.cluster.message/id ?id]
         [?m :seon.cluster.message/content ?content]]
       db (str "marker-" (name block-name))))

(defn echo-ai
  "Says its planted marker content; omits itself when none is planted."
  [unit]
  (let [name (:seon.render.block/name unit)]
    (note! name (:seon.db/db unit))
    (marker-content (:seon.db/db unit) name)))

(defn throwing-ai
  [_unit]
  (throw (ex-info "this projection is deliberately broken" {::planted true})))

(def planted-flat-error
  {:seon.error/kind ::planted-failure
   :seon.error/message "the planted failure"
   :seon.error/data {::planted true}})

(defn flat-error-ai
  "Returns a flat error VALUE — failure as data, never a throw."
  [_unit]
  planted-flat-error)

(defn scoped-ai
  "Reads exactly the messages addressed to the unit's agent, plus the
  shared agent's — the scope the oracle enumerates from planted rows."
  [unit]
  (let [db (:seon.db/db unit)
        contents (fn [agent-id]
                   (d/q '[:find [?content ...]
                          :in $ ?agent-id
                          :where
                          [?agent :seon.cluster.agent/id ?agent-id]
                          [?m :seon.cluster.message/to ?agent]
                          [?m :seon.cluster.message/content ?content]]
                        db agent-id))]
    (str/join "\n"
              (sort (into (vec (contents (:seon.cluster.agent/id unit)))
                          (contents "shared"))))))

(defn omitting-ai [unit] (note! (:seon.render.block/name unit) nil) nil)
(defn omitting-html [unit] (note! (:seon.render.block/name unit) nil) nil)

(defn speaking-ai
  [unit]
  (note! (:seon.render.block/name unit) nil)
  (str "ai-" (name (:seon.render.block/name unit))))

(defn speaking-html
  [unit]
  (note! (:seon.render.block/name unit) nil)
  [:p (str "html-" (name (:seon.render.block/name unit)))])

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(def ^:private bands
  "The band order, restated as the LITERAL the oracle sorts by — the
  implementation must match this table, not the other way around."
  [:anchor :program :authored :continuity :dynamic])

(defn- expected-order
  "The independent ordering oracle: (band ordinal, priority, name)."
  [blocks]
  (vec (sort-by (juxt (fn [block]
                        (.indexOf ^java.util.List bands
                                  (get block :seon.render.block/band :dynamic)))
                      :seon.render.block/priority
                      :seon.render.block/name)
                blocks)))

(defn- block-row
  [name priority band & {:as more}]
  (merge {:seon.render.block/name name
          :seon.render.block/priority priority
          :seon.render.block/band band}
         more))

(def ^:private now (Date. 1700000000000))

(defn- plant!
  "One agent with `blocks`, its trigger message, and the held run whose
  creating transaction records that trigger — exactly the loop's own
  claim-early shape."
  [connection {:keys [agent-id blocks run-id message-id content trigger?]
               :or {trigger? true}}]
  (d/transact connection
              [{:seon.cluster.agent/id agent-id
                :seon.cluster.agent/blocks blocks}
               {:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content content
                :seon.cluster.message/at now}])
  (d/transact connection
              (cond-> {:tx-data [{:seon.cluster.run/id run-id
                                  :seon.cluster.run/agent
                                  [:seon.cluster.agent/id agent-id]
                                  :seon.cluster.run/opened-at now}
                                 {:seon.cluster.agent/id agent-id
                                  :seon.cluster.agent/run
                                  [:seon.cluster.run/id run-id]}]}
                trigger? (assoc :tx-meta
                                {:seon.db/trigger
                                 [:seon.cluster.message/id message-id]}))))

(defn- request
  [run-id agent-id]
  {:seon.cluster.run/id run-id
   :seon.cluster.agent/id agent-id
   :seon.sci.admit/caps caps})

(defn- marker!
  [connection block-name content]
  (d/transact connection
              [{:seon.cluster.message/id (str "marker-" (name block-name))
                :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                :seon.cluster.message/content content
                :seon.cluster.message/at now}]))

(defn- eavt-census
  [db]
  (into #{} (map (fn [datom] [(:e datom) (:a datom) (:v datom)]))
        (d/datoms db :eavt)))

(defn- check!
  [label result]
  (is (true? (:result result))
      (str label " failed: " (pr-str result))))

(def ^:private name-alphabet
  [:alpha :bravo :charlie :delta :echo :foxtrot])

;;; ---------------------------------------------------------------------------
;;; 1. Determinism — seed 2026072801
;;; ---------------------------------------------------------------------------

(deftest context-determinism-property
  (check!
   "context determinism"
   (tc/quick-check
    15
    (prop/for-all [chosen (gen/not-empty
                           (gen/vector-distinct
                            (gen/elements name-alphabet) {:max-elements 5}))
                   priorities (gen/vector (gen/choose 0 99) 6)
                   picked-bands (gen/vector (gen/elements bands) 6)
                   changed-index gen/nat
                   salt (gen/choose 0 999999)]
      (support/with-database
        (fn [connection]
          (let [blocks (vec (map-indexed
                             (fn [index name]
                               (block-row name
                                          (nth priorities index)
                                          (nth picked-bands index)
                                          :seon.render/ai `echo-ai))
                             chosen))
                content-of (fn [name] (str "planted-" salt "-" name))]
            (plant! connection {:agent-id "agent-a" :blocks blocks
                                :run-id (str "run-" salt)
                                :message-id (str "m-" salt)
                                :content "the trigger"})
            (doseq [name chosen]
              (marker! connection name (content-of name)))
            (let [db (d/db connection)
                  ask #(prompt/prompt % (request (str "run-" salt) "agent-a"))
                  once (ask db)
                  twice (ask db)
                  expected (mapv (comp content-of :seon.render.block/name)
                                 (expected-order blocks))
                  changed (nth chosen (mod changed-index (count chosen)))
                  _ (marker! connection changed
                             (str "revised-" salt "-" changed))
                  later (ask (d/db connection))
                  texts (fn [rendered]
                          (mapv :seon.context.contribution/text
                                (:seon.context/contributions rendered)))]
              (and (= (:seon.cluster.prompt/text once)
                      (:seon.cluster.prompt/text twice))
                   (= (:seon.context/contributions once)
                      (:seon.context/contributions twice))
                   (= expected (texts once))
                   (= (mapv (fn [name]
                              (if (= name changed)
                                (str "revised-" salt "-" changed)
                                (content-of name)))
                            (mapv :seon.render.block/name
                                  (expected-order blocks)))
                      (texts later))))))))
    :seed 2026072801)))

;;; ---------------------------------------------------------------------------
;;; 2. Placement and omission — seed 2026072802
;;; ---------------------------------------------------------------------------

(deftest placement-and-omission-property
  (check!
   "placement and omission"
   (tc/quick-check
    15
    (prop/for-all [shapes (gen/not-empty
                           (gen/vector
                            (gen/elements [:ai-only :html-only :twin
                                           :conditional-nil])
                            1 5))
                   salt (gen/choose 0 999999)]
      (support/with-database
        (fn [connection]
          (let [named (map-indexed
                       (fn [index shape]
                         [(nth name-alphabet index) shape])
                       shapes)
                blocks (mapv (fn [[name shape]]
                               (apply block-row name 10 :dynamic
                                      (case shape
                                        :ai-only [:seon.render/ai `speaking-ai]
                                        :html-only [:seon.render/html
                                                    `speaking-html]
                                        :twin [:seon.render/ai `speaking-ai
                                               :seon.render/html
                                               `speaking-html]
                                        :conditional-nil
                                        [:seon.render/ai `omitting-ai
                                         :seon.render/html `omitting-html])))
                             named)
                ;; one constant speaking anchor, so the reduced text is
                ;; never empty whatever combination generates (the
                ;; sealed text schema is {:min 1} — an agent with no
                ;; speaking AI block has no prompt, which is its own
                ;; refusal class, not this property's subject)
                blocks (into [(block-row :keel 0 :anchor
                                         :seon.render/ai `speaking-ai)]
                             blocks)
                run-id (str "run-" salt)]
            (plant! connection {:agent-id "agent-a" :blocks blocks
                                :run-id run-id
                                :message-id (str "m-" salt)
                                :content "the trigger"})
            (binding [*invocations* (atom [])]
              (let [db (d/db connection)
                    rendered (prompt/prompt db (request run-id "agent-a"))
                    surfaces (block/surfaces
                              db {:seon.cluster.agent/id "agent-a"
                                  :seon.render/kind :seon.render/html
                                  :seon.sci.admit/caps caps})
                    page (block/page db {:seon.cluster.agent/id "agent-a"
                                         :seon.sci.admit/caps caps})
                    speaking-ai-names (into #{}
                                            (comp (filter
                                                   (fn [[_ shape]]
                                                     (#{:ai-only :twin} shape)))
                                                  (map first))
                                            named)
                    html-names (into #{}
                                     (comp (filter
                                            (fn [[_ shape]]
                                              (#{:html-only :twin
                                                 :conditional-nil} shape)))
                                           (map first))
                                     named)
                    conditional-names (into #{}
                                            (comp (filter
                                                   (fn [[_ shape]]
                                                     (= :conditional-nil shape)))
                                                  (map first))
                                            named)]
                (and
                 ;; expected invocations per block: one AI render for
                 ;; the prompt, and one HTML render per html derivation
                 ;; this test performs (the surfaces probe AND the page
                 ;; — two derivations at one value, counted honestly)
                 (= (into {:keel 1}
                          (map (fn [[name shape]]
                                 [name (case shape
                                         :ai-only 1
                                         :html-only 2
                                         :twin 3
                                         :conditional-nil 3)]))
                          named)
                    (frequencies (map ::name @*invocations*)))
                 (= (conj speaking-ai-names :keel)
                    (into #{} (map :seon.render.block/name)
                          (:seon.context/contributions rendered)))
                 (= html-names
                    (into #{} (map :seon.render.block/name) surfaces))
                 (every? (fn [surface]
                           (if (contains? conditional-names
                                          (:seon.render.block/name surface))
                             (nil? (get surface :seon.render/output))
                             (some? (get surface :seon.render/output))))
                         surfaces)
                 (every? (fn [name]
                           (some (fn [node] (= (block/slot name) node))
                                 (tree-seq sequential? seq (vec page))))
                         conditional-names))))))))
    :seed 2026072802)))

;;; ---------------------------------------------------------------------------
;;; 3. Error isolation — seed 2026072803
;;; ---------------------------------------------------------------------------

(deftest error-isolation-test
  (support/with-database
    (fn [connection]
      (let [blocks [(block-row :left 0 :anchor :seon.render/ai `echo-ai)
                    (block-row :gone 10 :dynamic
                               :seon.render/ai 'no.such.ns/nope)
                    (block-row :thrower 20 :dynamic
                               :seon.render/ai `throwing-ai)
                    (block-row :valued 30 :dynamic
                               :seon.render/ai `flat-error-ai)
                    (block-row :right 40 :dynamic :seon.render/ai `echo-ai)]]
        (plant! connection {:agent-id "agent-a" :blocks blocks
                            :run-id "run-2026072803"
                            :message-id "m-2026072803"
                            :content "the trigger"})
        (marker! connection :left "left-planted-2026072803")
        (marker! connection :right "right-planted-2026072803")
        (let [rendered (prompt/prompt (d/db connection)
                                      (request "run-2026072803" "agent-a"))
              by-name (into {}
                            (map (juxt :seon.render.block/name identity))
                            (:seon.context/contributions rendered))]
          (testing "neighbour bytes equal the planted literals"
            (is (= "left-planted-2026072803"
                   (:seon.context.contribution/text (by-name :left))))
            (is (= "right-planted-2026072803"
                   (:seon.context.contribution/text (by-name :right)))))
          (testing "every failure contributes a statement naming its block"
            (doseq [name [:gone :thrower :valued]]
              (let [record (by-name name)]
                (is (some? record) (str name " contributes"))
                (is (str/includes? (:seon.context.contribution/text record)
                                   (str name))
                    "the agent is told WHICH context is incomplete"))))
          (testing "the nested error is the closed :seon.error/value exactly"
            (doseq [name [:gone :thrower :valued]]
              (is (schema/valid-candidate-value?
                   :seon.error/value
                   (get (by-name name) :seon.error/value))
                  (str name "'s record carries a valid flat value")))
            (is (= planted-flat-error
                   (get (by-name :valued) :seon.error/value))
                "a returned flat error rides through EXACTLY"))
          (testing "isolation: order and neighbours are unaffected"
            (is (= [:left :gone :thrower :valued :right]
                   (mapv :seon.render.block/name
                         (:seon.context/contributions rendered))))))))))

;;; ---------------------------------------------------------------------------
;;; 4. Reduction ledger — seed 2026072804
;;; ---------------------------------------------------------------------------

(deftest prompt-reduction-ledger-property
  (check!
   "reduction ledger"
   (tc/quick-check
    15
    (prop/for-all [chosen (gen/not-empty
                           (gen/vector-distinct
                            (gen/elements name-alphabet) {:max-elements 5}))
                   spoken (gen/vector gen/boolean 6)
                   salt (gen/choose 0 999999)]
      (support/with-database
        (fn [connection]
          (let [speaks? (into {} (map-indexed
                                  (fn [index name]
                                    [name (nth spoken index)])
                                  chosen))
                blocks (mapv (fn [name]
                               (block-row name 10 :dynamic
                                          :seon.render/ai `echo-ai))
                             chosen)
                run-id (str "run-" salt)]
            (plant! connection {:agent-id "agent-a" :blocks blocks
                                :run-id run-id
                                :message-id (str "m-" salt)
                                :content "the trigger"})
            (doseq [name chosen :when (speaks? name)]
              (marker! connection name (str "says-" salt "-" (name name))))
            (binding [*invocations* (atom [])]
              (let [ordered-names (mapv :seon.render.block/name
                                        (expected-order blocks))
                    rendered (prompt/prompt (d/db connection)
                                            (request run-id "agent-a"))
                    expected-speaking (filterv speaks? ordered-names)]
                (and
                 (= ordered-names (mapv ::name @*invocations*))
                 (= expected-speaking
                    (mapv :seon.render.block/name
                          (:seon.context/contributions rendered)))
                 (= (mapv (fn [name] (str "says-" salt "-" (name name)))
                          expected-speaking)
                    (mapv :seon.context.contribution/text
                          (:seon.context/contributions rendered)))
                 (= (range (count expected-speaking))
                    (map :seon.context.contribution/position
                         (:seon.context/contributions rendered)))
                 (= (str/join "\n\n"
                              (map (fn [name]
                                     (str "says-" salt "-" (name name)))
                                   expected-speaking))
                    (:seon.cluster.prompt/text rendered)))))))))
    :seed 2026072804)))

;;; ---------------------------------------------------------------------------
;;; 5. Scope — seed 2026072806
;;; ---------------------------------------------------------------------------

(deftest scope-property
  (check!
   "projection scope"
   (tc/quick-check
    15
    (prop/for-all [to-a (gen/choose 1 3)
                   to-b (gen/choose 1 3)
                   to-shared (gen/choose 1 3)
                   salt (gen/choose 0 999999)]
      (support/with-database
        (fn [connection]
          (let [blocks [(block-row :inbox 0 :dynamic
                                   :seon.render/ai `scoped-ai)]
                tag (fn [owner index] (str "tag-" salt "-" owner "-" index))
                rows (fn [owner n]
                       (map (fn [index]
                              {:seon.cluster.message/id
                               (str salt "-" owner "-" index)
                               :seon.cluster.message/to
                               [:seon.cluster.agent/id owner]
                               :seon.cluster.message/content (tag owner index)
                               :seon.cluster.message/at now})
                            (range n)))]
            (d/transact connection [{:seon.cluster.agent/id "shared"}])
            (plant! connection {:agent-id "agent-a" :blocks blocks
                                :run-id (str "run-a-" salt)
                                :message-id (str "ask-a-" salt)
                                :content (str "ask-a-" salt)})
            (plant! connection {:agent-id "agent-b" :blocks blocks
                                :run-id (str "run-b-" salt)
                                :message-id (str "ask-b-" salt)
                                :content (str "ask-b-" salt)})
            (d/transact connection {:tx-data
                                    (vec (concat (rows "agent-a" to-a)
                                                 (rows "agent-b" to-b)
                                                 (rows "shared" to-shared)))})
            (let [db (d/db connection)
                  text-for (fn [agent-id run-id]
                             (:seon.cluster.prompt/text
                              (prompt/prompt db (request run-id agent-id))))
                  expected (fn [owner ask n]
                             (str/join "\n"
                                       (sort (concat [ask]
                                                     (map (fn [i] (tag owner i))
                                                          (range n))
                                                     (map (fn [i]
                                                            (tag "shared" i))
                                                          (range to-shared))))))
                  text-a (text-for "agent-a" (str "run-a-" salt))
                  text-b (text-for "agent-b" (str "run-b-" salt))]
              (and (= (expected "agent-a" (str "ask-a-" salt) to-a) text-a)
                   (= (expected "agent-b" (str "ask-b-" salt) to-b) text-b)
                   (not (str/includes? text-a (str "tag-" salt "-agent-b")))
                   (not (str/includes? text-b
                                       (str "tag-" salt "-agent-a")))))))))
    :seed 2026072806)))

;;; ---------------------------------------------------------------------------
;;; 6. Membership collision — seed 2026072807
;;; ---------------------------------------------------------------------------

(deftest membership-collision-property
  (check!
   "membership collision"
   (tc/quick-check
    25
    (prop/for-all [installed-names (gen/not-empty
                                    (gen/vector-distinct
                                     (gen/elements name-alphabet)
                                     {:max-elements 4}))
                   derived-names (gen/vector-distinct
                                  (gen/elements name-alphabet)
                                  {:max-elements 3})
                   salt (gen/choose 0 999999)]
      (support/with-database
        (fn [connection]
          (let [installed (mapv (fn [name]
                                  (block-row name 10 :authored
                                             :seon.render/ai `echo-ai))
                                installed-names)
                derived-rows (mapv (fn [name]
                                     (block-row name 50 :dynamic
                                                :seon.render/ai `speaking-ai))
                                   derived-names)]
            (d/transact connection [{:seon.cluster.agent/id "agent-a"
                                     :seon.cluster.agent/blocks installed}])
            (with-redefs [block/derived (fn [_db _agent-id] derived-rows)]
              (let [db (d/db connection)
                    before (eavt-census db)
                    colliding (set/intersection (set installed-names)
                                                (set derived-names))
                    outcome (support/refusal-data
                             (fn [] (block/membership db "agent-a")))
                    after (eavt-census (d/db connection))]
                (and
                 (= before after)
                 (if (seq colliding)
                   (and (map? outcome)
                        (= :seon.render.block/name-collision
                           (:seon.render.block/rule outcome))
                        (contains? colliding
                                   (:seon.render.block/name outcome))
                        (contains? outcome :seon.render.block/installed)
                        (contains? outcome :seon.render.block/derived))
                   (= (mapv :seon.render.block/name
                            (expected-order (into installed derived-rows)))
                      (mapv :seon.render.block/name
                            (block/membership db "agent-a")))))))))))
    :seed 2026072807)))

;;; ---------------------------------------------------------------------------
;;; 7. Caps and expansion — seed 2026072808
;;; ---------------------------------------------------------------------------

(defn- chain-surfaces
  "A slot graph: block i slots block i+1 `branching` times; the last
  block either leafs out or closes a cycle back to block 0."
  [depth branching cycle?]
  (let [names (mapv (fn [index] (keyword (str "b" index))) (range depth))]
    (mapv (fn [index]
            {:seon.render.block/name (nth names index)
             :seon.render/surface-id (block/surface-id (nth names index))
             :seon.render/kind :seon.render/html
             :seon.render/output
             (cond
               (< (inc index) depth)
               (into [:div] (repeat branching
                                    (block/slot (nth names (inc index)))))
               cycle? [:div (block/slot (nth names 0))]
               :else [:span "leaf"])})
          (range depth))))

(defn- reference-expand
  "The TEST-ONLY depth-first left-to-right counter: an independent
  walker computing admitted positions, elision, depth and cycle notes
  for slot-only graphs, per the sealed expansion rules."
  [hiccup surfaces tight]
  (let [by-id (into {} (map (juxt :seon.render/surface-id identity)) surfaces)
        remaining (atom (long (:seon.config.eval.result/max-nodes tight)))
        max-depth (long (:seon.config.eval.result/max-depth tight))]
    (letfn [(note [hole text] (conj (subvec hole 0 2) text))
            (slot? [node] (and (vector? node)
                               (map? (nth node 1 nil))
                               (contains? (nth node 1) :data-slot)))
            (walk [node visited depth]
              (if (neg? (swap! remaining dec))
                [:span {:class "seon-expansion-elided"}
                 "elided — this page is larger than the configured caps"]
                (cond
                  (slot? node)
                  (let [id (:id (nth node 1))
                        slot-name (:data-slot (nth node 1))]
                    (cond
                      (contains? visited id)
                      (note node (str "cycle: " slot-name
                                      " is already being expanded on this path"))
                      (>= depth max-depth)
                      (note node (str "not expanded: " slot-name
                                      " is deeper than the configured depth"))
                      :else
                      (if-let [found (by-id id)]
                        (walk (:seon.render/output found)
                              (conj visited id) (inc depth))
                        (note node (str "no block named " slot-name
                                        " — install one and this fills itself")))))
                  (vector? node)
                  (let [attributed? (map? (nth node 1 nil))
                        prefix (subvec node 0 (if attributed? 2 1))]
                    (into prefix
                          (map (fn [child] (walk child visited depth)))
                          (subvec node (count prefix))))
                  (sequential? node)
                  (doall (map (fn [child] (walk child visited depth)) node))
                  :else node)))]
      (walk hiccup #{} 0))))

(deftest caps-and-expansion-property
  (check!
   "caps and expansion"
   (tc/quick-check
    30
    (prop/for-all [depth (gen/choose 1 6)
                   branching (gen/choose 1 3)
                   cycle? gen/boolean
                   max-nodes (gen/choose 1 64)
                   max-depth (gen/choose 1 6)]
      (let [tight (assoc caps
                         :seon.config.eval.result/max-nodes max-nodes
                         :seon.config.eval.result/max-depth max-depth)
            surfaces (chain-surfaces depth branching cycle?)
            root (:seon.render/output (first surfaces))]
        (= (reference-expand root surfaces tight)
           (block/expand root {:seon.render/surfaces surfaces
                               :seon.sci.admit/caps tight}))))
    :seed 2026072808))
  (testing "the SAME caps bound AI admission: the expected truncation is
            computed independently with subs"
    (support/with-database
      (fn [connection]
        (let [long-text (apply str (repeat 64 "abcdefgh"))
              tight (assoc caps :seon.config.eval.result/max-string 32)]
          (plant! connection {:agent-id "agent-a"
                              :blocks [(block-row :loud 0 :dynamic
                                                  :seon.render/ai `echo-ai)]
                              :run-id "run-2026072808"
                              :message-id "m-2026072808"
                              :content "the trigger"})
          (marker! connection :loud long-text)
          (let [rendered (prompt/prompt
                          (d/db connection)
                          (assoc (request "run-2026072808" "agent-a")
                                 :seon.sci.admit/caps tight))]
            (is (= (subs long-text 0 32)
                   (:seon.cluster.prompt/text rendered))
                "a projection cannot flood the prompt"))))))
  (testing "and the SAME caps bound the generic panel"
    (let [tight (assoc caps :seon.config.eval.result/max-collection 8)]
      (is (str/includes?
           (hiccup/->string
            (block/data-panel {:seon.render/value (vec (range 100))
                               :seon.sci.admit/caps tight}))
           "elided")))))

;;; ---------------------------------------------------------------------------
;;; 8-10. Trigger and refusals — seed 2026072809
;;; ---------------------------------------------------------------------------

(def ^:private trigger-blocks
  [(block-row :trigger 90 :dynamic :seon.render/ai 'seon.context/trigger-ai)])

(deftest held-run-trigger-test
  (support/with-database
    (fn [connection]
      (plant! connection {:agent-id "agent-a" :blocks trigger-blocks
                          :run-id "run-2026072809"
                          :message-id "m-a-2026072809"
                          :content "planted content A 2026072809"})
      (d/transact connection
                  [{:seon.cluster.message/id "m-b-2026072809"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "planted content B 2026072809"
                    :seon.cluster.message/at now}])
      (let [db (d/db connection)]
        (testing "the ledger: message/trigger independently establishes A"
          (is (= "m-a-2026072809" (message/trigger db "run-2026072809"))))
        (let [rendered (prompt/prompt db (request "run-2026072809" "agent-a"))
              text (:seon.cluster.prompt/text rendered)]
          (is (str/includes? text "planted content A 2026072809"))
          (is (not (str/includes? text "planted content B 2026072809"))
              "a message arriving between open and :call cannot displace
               the recorded cause")
          (testing "and the captured prompt contains A's planted content"
            (let [outcome (store/transact!
                           connection
                           (context/capture-tx
                            {:seon.cluster.run/id "run-2026072809"
                             :seon.cluster.prompt/rendered-context rendered}))]
              (is (nil? (:seon.error/kind outcome))))
            (let [captured (d/q '[:find ?prompt .
                                  :where
                                  [?c :seon.context.capture/prompt ?prompt]]
                                @connection)]
              (is (str/includes? captured "planted content A 2026072809"))
              (is (= text captured)
                  "the capture records the exact bytes"))))))))

(deftest missing-trigger-refusal-test
  (support/with-database
    (fn [connection]
      (plant! connection {:agent-id "agent-a" :blocks trigger-blocks
                          :run-id "run-no-trigger-2026072809"
                          :message-id "m-2026072809"
                          :content "unclaimed content"
                          :trigger? false})
      (let [db (d/db connection)
            before (eavt-census db)
            outcome (support/refusal-data
                     (fn [] (prompt/prompt
                             db (request "run-no-trigger-2026072809"
                                         "agent-a"))))]
        (is (map? outcome) "it refused rather than committing")
        (is (= :seon.cluster.prompt/no-trigger
               (:seon.cluster.prompt/rule outcome))
            "the deepest non-empty ex-data names the rule")
        (is (= before (eavt-census (d/db connection)))
            "before/after sorted datoms are identical — a refusal writes
             nothing and no partial rendered context exists")))))

(deftest missing-input-refusal-test
  (support/with-database
    (fn [connection]
      (plant! connection
              {:agent-id "agent-a"
               :blocks [(block-row :needy 0 :dynamic
                                   :seon.render/ai `speaking-ai
                                   :seon.context/inputs
                                   #{:seon.cluster.run/live-processes})]
               :run-id "run-input-2026072809"
               :message-id "m-input-2026072809"
               :content "the trigger"})
      (binding [*invocations* (atom [])]
        (let [db (d/db connection)
              outcome (support/refusal-data
                       (fn [] (prompt/prompt
                               db (request "run-input-2026072809"
                                           "agent-a"))))]
          (testing "the request without the input refuses BEFORE any
                    projection runs"
            (is (= :seon.render.block/missing-input
                   (:seon.render.block/rule outcome)))
            (is (contains? (:seon.context/inputs outcome)
                           :seon.cluster.run/live-processes)
                "naming the absent key")
            (is (= [:needy] (:seon.render.block/names outcome))
                "and the block that declared it")
            (is (= [] @*invocations*) "the invocation ledger is empty"))
          (testing "with the input, results reproduce and the capture
                    records the snapshot"
            (let [snapshot #{"pid-1@1" "pid-2@2"}
                  rendered (prompt/prompt
                            db (assoc (request "run-input-2026072809"
                                               "agent-a")
                                      :seon.cluster.run/live-processes
                                      snapshot))]
              (is (= "ai-needy" (:seon.cluster.prompt/text rendered)))
              (is (= [{::name :needy ::db nil}] @*invocations*))
              (let [outcome (store/transact!
                             connection
                             (context/capture-tx
                              {:seon.cluster.run/id "run-input-2026072809"
                               :seon.cluster.prompt/rendered-context rendered
                               :seon.cluster.run/live-processes snapshot}))]
                (is (nil? (:seon.error/kind outcome))))
              (is (= snapshot
                     (set (d/q '[:find [?process ...]
                                 :where
                                 [_ :seon.cluster.run/live-processes
                                  ?process]]
                               @connection)))))))))))

;;; ---------------------------------------------------------------------------
;;; 11. Capture before the provider — seed 2026072805
;;; ---------------------------------------------------------------------------

(def ^:private process
  (cluster/process-identity {:seon.boot/pid 2805
                             :seon.boot/start-instant now}))

(defn- with-work-launcher
  [body]
  (seon.flow/install-work-launcher!
   {::seon.flow/configuration
    {:seon.config.flow.compute/queue-depth 2
     :seon.config.flow.compute/concurrency 1}})
  (try
    (body)
    (finally
      (seon.flow/stop-installed-work-launcher!))))

(deftest capture-before-provider-test
  (support/with-database
    (fn [connection]
      (with-work-launcher
       (fn []
      (d/transact connection
                  [{:seon.cluster.agent/id "agent-a"
                    :seon.cluster.agent/blocks
                    [(block-row :identity 0 :anchor
                                :seon.render/ai 'seon.context/identity-ai)
                     (block-row :broken 10 :dynamic
                                :seon.render/ai 'no.such.ns/nope)
                     (block-row :trigger 90 :dynamic
                                :seon.render/ai 'seon.context/trigger-ai)]}
                   {:seon.cluster.message/id "m-2026072805"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "capture the widgets"
                    :seon.cluster.message/at now}])
      (let [handle {:seon.store/branch-connection connection
                    :seon.cluster.run/process process
                    :seon.ai/primary
                    {:seon.ai/endpoint "http://127.0.0.1:1/v1"
                     :seon.ai/model "probe"
                     :seon.ai/api-key-variable "SEON_AI_TEST_KEY"
                     :seon.ai/timeout-ms 200}
                    :seon.ai.retry/strategy
                    {:seon.ai.retry/base-delay-ms 1
                     :seon.ai.retry/multiplier 2.0
                     :seon.ai.retry/jitter-fraction 0.0
                     :seon.ai.retry/maximum-delay-ms 1
                     :seon.ai.retry/maximum-retries 0
                     :seon.ai.retry/maximum-total-delay-ms 0}
                    :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
                    :seon.config.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.config.error/recurrence-limit 3
                    :seon.config.message/max-chain 2
                    :seon.sci.admit/caps caps}
            order (atom [])
            turn! (fn []
                    ;; the AGENT-SCOPED derivation (F2 §3.2): this
                    ;; fixture always drove one agent's turns
                    (let [found (work/next-agent-work
                                 @connection
                                 {:seon.cluster.agent/id "agent-a"
                                  :seon.cluster.run/process process
                                  :seon.cluster.work/now (Date.)})]
                      (cluster.loop/turn {:seon.cluster.loop/cluster handle
                                          :seon.cluster.work/next found}
                                         (Date.))
                      found))
            _ (turn!)
            basis-before-call (:max-tx @connection)
            _ (with-redefs [ai/complete
                            (fn [request]
                              (swap! order conj
                                     {::captures
                                      (count
                                       (d/q '[:find ?c :where
                                              [?c :seon.context.capture/id _]]
                                            @connection))
                                      ::prompt (:seon.ai/prompt request)})
                              {:seon.ai/text "(my.run/complete \"done\")"})]
                (turn!))
            run-id (d/q '[:find ?id . :where [_ :seon.cluster.run/id ?id]]
                        @connection)
            capture (d/pull @connection
                            '[* {:seon.context.capture/contributions [*]}]
                            [:seon.context.capture/id
                             (str run-id "-context-" basis-before-call)])]
        (testing "the capture's datoms exist BEFORE the recorded call"
          (is (= 1 (count @order)) "one provider call")
          (is (= 1 (::captures (first @order)))
              "the capture was durable when the provider was called"))
        (testing "capture id and contribution ids equal the derived
                  (run, basis-t, position) identities"
          (is (some? (:db/id capture)))
          (is (= basis-before-call (:seon.context.capture/basis-t capture)))
          (is (= (::prompt (first @order))
                 (:seon.context.capture/prompt capture))
              "the exact bytes sent are the bytes captured")
          (let [rows (sort-by :seon.context.contribution/position
                              (:seon.context.capture/contributions capture))]
            (is (= (map (fn [position]
                          (str run-id "-context-" basis-before-call
                               "-" position))
                        (range (count rows)))
                   (map :seon.context.contribution/id rows)))
            (testing "rows carry hash/tokens/position/band and neither a
                      stored kind nor stored text"
              (doseq [row rows]
                (is (re-matches #"[0-9a-f]{64}"
                                (:seon.context.contribution/hash row)))
                (is (int? (:seon.context.contribution/tokens row)))
                (is (keyword? (:seon.context.contribution/band row)))
                (is (not (contains? row :seon.render/kind)))
                (is (not (contains? row :seon.context.contribution/text)))))
            (testing "a failed row is error-key presence"
              (let [failed (first (filter (fn [row]
                                            (= :broken
                                               (:seon.render.block/name row)))
                                          rows))]
                (is (= :seon.render/unresolvable (:seon.error/kind failed)))
                (is (string? (:seon.context.contribution/error failed)))
                (is (every? (fn [row]
                              (not (contains? row :seon.error/kind)))
                            (remove (fn [row]
                                      (= :broken
                                         (:seon.render.block/name row)))
                                    rows))
                    "and a clean row has none")))))
        (testing "no attempt row exists whose run lacks a capture at the
                  prompt's basis (property 6)"
          (is (= 1 (count (d/q '[:find ?attempt :where
                                 [?attempt :seon.ai.attempt/ordinal _]]
                               @connection)))))))))))

;;; ---------------------------------------------------------------------------
;;; 12. Exact database identity — seed 2026072801
;;; ---------------------------------------------------------------------------

(deftest exact-database-identity-test
  (support/with-database
    (fn [connection]
      (plant! connection {:agent-id "agent-a"
                          :blocks [(block-row :echo 0 :dynamic
                                              :seon.render/ai `echo-ai)]
                          :run-id "run-identity-2026072801"
                          :message-id "m-identity-2026072801"
                          :content "the trigger"})
      (marker! connection :echo "content-at-t1-2026072801")
      (let [db-1 (d/db connection)
            t-1 (:max-tx db-1)
            _ (marker! connection :echo "content-at-t2-2026072801")
            db-2 (d/db connection)
            ask (fn [db]
                  (binding [*invocations* (atom [])]
                    (let [rendered (prompt/prompt
                                    db (request "run-identity-2026072801"
                                                "agent-a"))]
                      {::rendered rendered
                       ::received (::db (first @*invocations*))})))
            at-1 (ask db-1)
            at-2 (ask db-2)
            as-of (ask (d/as-of db-2 t-1))]
        (testing "the invocation ledger and the returned :seon.db/db name
                  the requested value"
          (is (identical? db-1 (::received at-1)))
          (is (identical? db-1 (:seon.db/db (::rendered at-1))))
          (is (identical? db-2 (::received at-2))))
        (testing "no result produced for one identity satisfies another"
          (is (= "content-at-t1-2026072801"
                 (:seon.cluster.prompt/text (::rendered at-1))))
          (is (= "content-at-t2-2026072801"
                 (:seon.cluster.prompt/text (::rendered at-2))))
          (is (= "content-at-t1-2026072801"
                 (:seon.cluster.prompt/text (::rendered as-of)))
              "the as-of value answers as its basis, not as the head"))
        (testing "since and history values reach the projection as the
                  exact value requested"
          (doseq [value [(d/since db-2 (java.util.Date. 0))
                         (d/history db-2)]]
            (binding [*invocations* (atom [])]
              (render/render
               {:seon.render/unit
                (block/unit {:seon.db/db value
                             :seon.cluster.agent/id "agent-a"
                             :seon.sci.admit/caps caps}
                            (block-row :echo 0 :dynamic
                                       :seon.render/ai `echo-ai))
                :seon.render/kind :seon.render/ai})
              (is (identical? value (::db (first @*invocations*)))))))))))

;;; ---------------------------------------------------------------------------
;;; 13. Colocation — seed 2026072802
;;; ---------------------------------------------------------------------------

(deftest colocation-test
  (support/with-database
    (fn [connection]
      (plant! connection
              {:agent-id "agent-a"
               ;; the neighbourhood view, which retired `:interruption`:
               ;; "your previous run was cut" is a fact about a RUN, so
               ;; it is now the run family's own lens, reached by walking
               ;; the agent's connections
               :blocks [(block-row :namespace 80 :dynamic
                                   :seon.render/ai
                                   'seon.render.agent/namespace-ai)
                        (block-row :trigger 90 :dynamic
                                   :seon.render/ai 'seon.context/trigger-ai)]
               :run-id "run-colocation-2026072802"
               :message-id "m-colocation-2026072802"
               :content "the trigger"})
      (let [ask (fn [] (prompt/prompt (d/db connection)
                                      (request "run-colocation-2026072802"
                                               "agent-a")))
            names (fn [rendered]
                    (mapv :seon.render.block/name
                          (:seon.context/contributions rendered)))
            empty-state (ask)
            census-after-empty (eavt-census (d/db connection))
            _ (ask)
            census-after-second (eavt-census (d/db connection))
            _ (d/transact connection
                          [{:seon.cluster.run/id "run-cut-2026072802"
                            :seon.cluster.run/agent
                            [:seon.cluster.agent/id "agent-a"]
                            :seon.cluster.run/opened-at (Date. 1000)
                            :seon.cluster.run/closed-at (Date. 2000)}])
            census-after-plant (eavt-census (d/db connection))
            warned (ask)]
        (testing "empty plan-style facts derive the content alternative
                  with no interruption teaching"
          (is (= [:namespace :trigger] (names empty-state)))
          (is (not (str/includes? (str/lower-case
                                   (:seon.cluster.prompt/text empty-state))
                                  "interrupt"))))
        (testing "non-empty facts derive the teaching, from planted state"
          (is (= [:namespace :trigger] (names warned)))
          (is (str/includes? (str/lower-case
                              (:seon.cluster.prompt/text warned))
                             "interrupt")))
        (testing "no acknowledgement write, ever: derivations change no
                  datom, and the diff between states is only domain facts"
          (is (= census-after-empty census-after-second))
          (is (= census-after-plant (eavt-census (d/db connection))))
          (let [added (set/difference census-after-plant census-after-empty)
                attrs (into #{} (map second) added)]
            (is (seq added))
            (is (every? (fn [attr]
                          (or (= "seon.cluster.run" (namespace attr))
                              (= :seon.cluster.agent/run attr)
                              (= :seon.db/user attr)
                              (= :seon.db/process attr)
                              (= :db/txInstant attr)))
                        attrs)
                (str "only domain facts changed: " attrs))))))))

;;; ---------------------------------------------------------------------------
;;; 14. Root/agent symmetry — seed 2026072807
;;; ---------------------------------------------------------------------------

(deftest root-agent-symmetry-test
  (support/with-database
    (fn [connection]
      (plant! connection {:agent-id "root"
                          :blocks [(block-row :fleet 0 :anchor
                                              :seon.render/ai `echo-ai)
                                   (block-row :common 10 :dynamic
                                              :seon.render/ai `echo-ai)]
                          :run-id "run-root-2026072807"
                          :message-id "m-root-2026072807"
                          :content "root trigger"})
      (plant! connection {:agent-id "agent-a"
                          :blocks [(block-row :workbench 0 :anchor
                                              :seon.render/ai `echo-ai)
                                   (block-row :common 10 :dynamic
                                              :seon.render/ai `echo-ai)]
                          :run-id "run-agent-2026072807"
                          :message-id "m-agent-2026072807"
                          :content "agent trigger"})
      (doseq [[block-name content] {:fleet "root-tag-2026072807"
                                    :workbench "agent-tag-2026072807"
                                    :common "common-tag-2026072807"}]
        (marker! connection block-name content))
      (let [db (d/db connection)
            ask (fn [run-id agent-id]
                  (prompt/prompt db (request run-id agent-id)))
            root (ask "run-root-2026072807" "root")
            agent (ask "run-agent-2026072807" "agent-a")
            names (fn [rendered]
                    (mapv :seon.render.block/name
                          (:seon.context/contributions rendered)))]
        (testing "the same functions route both memberships"
          (is (= [:fleet :common] (names root)))
          (is (= [:workbench :common] (names agent))))
        (testing "each prompt carries exactly its planted tags"
          (is (str/includes? (:seon.cluster.prompt/text root)
                             "root-tag-2026072807"))
          (is (str/includes? (:seon.cluster.prompt/text agent)
                             "agent-tag-2026072807"))
          (is (not (str/includes? (:seon.cluster.prompt/text root)
                                  "agent-tag-2026072807")))
          (is (not (str/includes? (:seon.cluster.prompt/text agent)
                                  "root-tag-2026072807"))))
        (testing "root is an agent: nothing root-shaped exists to branch
                  on, so membership is the same one function too"
          (is (= [:fleet :common]
                 (mapv :seon.render.block/name
                       (block/membership db "root")))))))))

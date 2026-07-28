(ns seon.render.block-test
  "Sealed acceptance draft for blocks — the one page/prompt mechanism.

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27, N4 package 1). The
  implementation lane makes these green by implementing
  `seon.render.block` ONLY — schemas and tests are byte-sealed.

  THE SUITE OWNS ITS OWN PROJECTIONS, deliberately, the way
  `seon.render-test` does. It renders blocks whose render functions are
  defined right here, so what it proves is that the MECHANISM is generic
  — that root, an agent, `/data`, the header and a canvas are the same
  code path with different data. A suite that reached for
  `seon.problems` to prove the block mechanism would be proving the two
  together and telling us which is broken only by accident.

  Nothing here is a root test, because there is nothing root-shaped to
  test: root is an agent whose blocks differ, and the absence of a root
  branch in this suite is the assertion.

  One fresh in-memory database per test, created and deleted inside the
  test, matching every other suite in the tree.

  Seeds are fixed; generated inputs are functions of their seed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

;;; ---------------------------------------------------------------------------
;;; Projections this suite owns
;;; ---------------------------------------------------------------------------

(def ^:dynamic *evaluations*
  "Counts calls per block name. The 'one block, one evaluation' property
  is about COUNTING work, so the projections have to be countable."
  nil)

(def ^:dynamic *graph* nil)
(def ^:dynamic *database-value* nil)

(defn- counted!
  [name]
  (when *evaluations* (swap! *evaluations* update name (fnil inc 0))))

(defn header-html
  "A layout: its hiccup contains slots, which is the only thing that
  makes it a layout."
  [unit]
  (counted! :header)
  [:header {:class "flex gap-2"}
   [:span "seon"]
   (block/slot :body)])

(defn body-html
  [unit]
  (counted! :body)
  [:div {:class "p-2"} (str "agent " (:seon.cluster.agent/id unit))])

(defn body-ai
  [unit]
  (counted! :body)
  (str "the body of " (:seon.cluster.agent/id unit)))

(defn reads-the-database-html
  "Proves the unit carries the exact database value: this counts agents
  through the db it was handed, never an ambient one."
  [unit]
  [:p (str "agents: "
           (count (d/q '[:find ?a :where [?a :seon.cluster.agent/id _]]
                       (:seon.db/db unit))))])

(defn not-hiccup-html
  "The mistake the grammar exists to catch: a sibling key returned
  inside the hiccup."
  [_unit]
  [:div {:class "a"} {:seon.render/ai "oops"}])

(defn throwing-html
  [_unit]
  (throw (ex-info "this projection is broken" {::deliberate true})))

(defn slots-a-missing-block-html
  [_unit]
  [:div (block/slot :no-such-block)])

(defn cycle-one-html
  [_unit]
  [:div "one" (block/slot :cycle-two)])

(defn cycle-two-html
  [_unit]
  [:div "two" (block/slot :cycle-one)])

(defn generated-html
  "Project one node in the generated block graph."
  [unit]
  (let [name (:seon.render.block/name unit)
        {:keys [outcome targets]} (get *graph* name)]
    (counted! name)
    (when-not (identical? *database-value* (:seon.db/db unit))
      (throw (ex-info "projection received the wrong database value"
                      {::name name})))
    (if (= :failure outcome)
      (throw (ex-info "generated projection failure" {::name name}))
      (into [:section {:data-graph-block (subs (str name) 1)}]
            (map block/slot)
            targets))))

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(def ^:private agent-id "agent-a")
(def ^:private other-agent-id "agent-b")

(defn- with-database
  [blocks body]
  (support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id agent-id
                               :seon.cluster.agent/blocks blocks}
                              {:seon.cluster.agent/id other-agent-id}])
      (binding [*evaluations* (atom {})]
        (body connection)))))

(defn- block-map
  [name priority & {:as slots}]
  (merge {:seon.render.block/name name :seon.render.block/priority priority} slots))

(def ^:private two-blocks
  [(block-map :header 0 :seon.render/html `header-html)
   (block-map :body 10 :seon.render/html `body-html :seon.render/ai `body-ai)])

(def ^:private caps
  "The four result dials, by the names `seon.sci.admit` already takes.
  ONE definition for both callers, deliberately: expansion walks a graph
  that can fan out and can cycle, and the data panel walks a value that
  can be enormous — the same problem the admission codec already solved,
  so both take that solution's dials rather than a second set to drift
  from them. Supplied EXPLICITLY everywhere, because a renderer that
  invented its own bounds would be exactly that second set."
  (config/result-caps (config/defaults)))

(defn- html-request []
  {:seon.cluster.agent/id agent-id
   :seon.render/kind :seon.render/html
   :seon.sci.admit/caps caps})

(defn- page-request []
  {:seon.cluster.agent/id agent-id :seon.sci.admit/caps caps})

;;; ---------------------------------------------------------------------------
;;; The address
;;; ---------------------------------------------------------------------------

(deftest surface-id-is-the-one-derivation
  (is (= "surface-transcript" (block/surface-id :transcript)))
  (testing "the hole and the surface carry the same id, by construction"
    (let [hole (block/slot :transcript)]
      (is (= "surface-transcript" (get-in hole [1 :id]))
          "a second string concatenation anywhere is how these drift"))))

(deftest surface-ids-are-injective
  ;; Two blocks sharing an id would silently morph over each other. A
  ;; qualified name keeps its namespace and nothing is dropped.
  (support/assert-check!
   (tc/quick-check
    300
    (prop/for-all [[a b] (gen/such-that
                          (fn [[a b]] (not= a b))
                          (gen/tuple gen/keyword-ns gen/keyword-ns)
                          100)]
      (not= (block/surface-id a) (block/surface-id b)))
    :seed 202607280201)
   "distinct names, distinct ids")
  (testing "a namespace survives, so :a/x and :b/x are two ids"
    (is (not= (block/surface-id :a/x) (block/surface-id :b/x)))))

(deftest a-slot-is-a-marker-and-not-a-resolution
  ;; The layout that emits it knows nothing about what fills it.
  (let [hole (block/slot :body)]
    (is (true? (hiccup/hiccup? hole)))
    (is (= "" (last hole)) "an EMPTY hole — expansion fills it")))

;;; ---------------------------------------------------------------------------
;;; The derivation
;;; ---------------------------------------------------------------------------

(def ^:private graph-names
  [:graph/a :graph/b :graph/c :graph/d :graph/e :graph/f])

(def ^:private graph-generator
  (gen/bind
   (gen/choose 1 (count graph-names))
   (fn [size]
     (let [names (subvec graph-names 0 size)
           targets (conj names :graph/missing)]
       (gen/let [priorities (gen/vector (gen/choose 0 20) size)
                 declarations (gen/vector gen/boolean size)
                 outcomes (gen/vector (gen/elements [:success :failure]) size)
                 edges (gen/vector
                        (gen/vector (gen/elements targets) 0 2)
                        size)
                 max-depth (gen/choose 1 5)
                 max-nodes (gen/choose 1 40)]
         {:nodes
          (mapv (fn [name priority declared? outcome node-targets]
                  {:name name
                   :priority priority
                   :declared? declared?
                   :outcome outcome
                   :targets node-targets})
                names priorities declarations outcomes edges)
          :caps (assoc caps
                       :seon.config.eval.result/max-depth max-depth
                       :seon.config.eval.result/max-nodes max-nodes)})))))

(defn- oracle-surfaces
  [nodes]
  (->> nodes
       (filter :declared?)
       (sort-by (juxt :priority :name))
       vec))

(defn- oracle-roots
  [nodes]
  (let [surfaces (oracle-surfaces nodes)
        slotted (into #{}
                      (comp (filter #(= :success (:outcome %)))
                            (mapcat :targets)
                            (map block/surface-id))
                      surfaces)
        top (filterv #(not (contains? slotted (block/surface-id (:name %))))
                     surfaces)]
    (if (and (empty? top) (seq surfaces)) surfaces top)))

(defn- page-root-name
  [element]
  (let [attributes (nth element 1 nil)]
    (some-> (or (:data-graph-block attributes)
                (:data-block attributes))
            keyword)))

(defn- graph-markers
  [page]
  (keep (fn [node]
          (when (and (vector? node) (map? (nth node 1 nil)))
            (:data-graph-block (nth node 1))))
        (tree-seq sequential? seq page)))

(defn- marker-depth
  [node]
  (if (and (vector? node) (map? (nth node 1 nil)))
    (let [own (if (:data-graph-block (nth node 1)) 1 0)]
      (+ own
         (reduce max 0
                 (map marker-depth
                      (subvec node 2)))))
    (if (sequential? node)
      (reduce max 0 (map marker-depth node))
      0)))

(defn- locally-explained-hole?
  [node]
  (or (not (and (vector? node)
                (map? (nth node 1 nil))
                (contains? (nth node 1) :data-slot)))
      (let [explanation (last node)]
        (and (string? explanation) (not (str/blank? explanation))))))

(deftest generated-block-graphs-preserve-the-graph-contract
  (support/assert-check!
   (tc/quick-check
    24
    (prop/for-all [{:keys [nodes caps]} graph-generator]
      (let [blocks (mapv
                    (fn [{:keys [name priority declared?]}]
                      (cond-> (block-map name priority)
                        declared? (assoc :seon.render/html `generated-html)))
                    nodes)
            graph (into {} (map (juxt :name identity)) nodes)
            expected-surfaces (oracle-surfaces nodes)
            expected-names (mapv :name expected-surfaces)
            expected-roots (mapv :name (oracle-roots nodes))]
        (with-database
          blocks
          (fn [connection]
            (let [db (d/db connection)
                  request {:seon.cluster.agent/id agent-id
                           :seon.render/kind :seon.render/html
                           :seon.sci.admit/caps caps}]
              (binding [*graph* graph
                        *database-value* db]
                (reset! *evaluations* {})
                (let [surfaces (block/surfaces db request)]
                  (and
                   (= expected-names
                      (mapv :seon.render.block/name surfaces))
                   (= (zipmap expected-names (repeat 1)) @*evaluations*)
                   (every?
                    (fn [surface]
                      (let [node (get graph (:seon.render.block/name surface))]
                        (= (= :failure (:outcome node))
                           (contains? surface :seon.error/value))))
                    surfaces)
                   (do
                     (reset! *evaluations* {})
                     (let [page-request
                           {:seon.cluster.agent/id agent-id
                            :seon.sci.admit/caps caps}
                           page (block/page db page-request)
                           evaluations @*evaluations*
                           again (do
                                   (reset! *evaluations* {})
                                   (block/page db page-request))]
                       (and
                        (= expected-roots (mapv page-root-name page))
                        (= (zipmap expected-names (repeat 1)) evaluations)
                        (= page again)
                        (every? locally-explained-hole?
                                (tree-seq sequential? seq page))
                        (<= (count (graph-markers page))
                            (:seon.config.eval.result/max-nodes caps))
                        (<= (marker-depth page)
                            (inc (:seon.config.eval.result/max-depth caps)))
                        (every? hiccup/hiccup? page)))))))))))
    :seed 202607280205)
   "generated block graph")))

(deftest an-agent-with-no-blocks-derives-nothing
  ;; A legitimate agent, not an error, and not a cue to substitute a
  ;; default set: an absent block tree means no blocks.
  (with-database two-blocks
    (fn [connection]
      (is (= [] (block/blocks (d/db connection) other-agent-id)))
      (is (= [] (block/surfaces (d/db connection)
                                {:seon.cluster.agent/id other-agent-id
                                 :seon.render/kind :seon.render/html
                                 :seon.sci.admit/caps caps}))))))

(deftest each-agent-owns-its-own-set
  ;; Two agents may each own a `:transcript`; the name is not a store
  ;; identity, and one agent's blocks are never visible from another.
  (with-database [(block-map :transcript 0 :seon.render/html `body-html)]
    (fn [connection]
      (let [db (d/db connection)]
        (is (= [:transcript] (mapv :seon.render.block/name (block/blocks db agent-id))))
        (is (= [] (block/blocks db other-agent-id)))))))

;;; ---------------------------------------------------------------------------
;;; Presence decides placement
;;; ---------------------------------------------------------------------------

(deftest a-block-that-does-not-declare-the-kind-is-omitted
  ;; The whole selection mechanism, and the reason no block carries a
  ;; flag saying where it goes: an html-only widget costs the prompt
  ;; zero tokens.
  (with-database two-blocks
    (fn [connection]
      (let [db (d/db connection)
            names (fn [kind]
                    (mapv :seon.render.block/name
                          (block/surfaces db {:seon.cluster.agent/id agent-id
                                              :seon.render/kind kind
                                              :seon.sci.admit/caps caps})))]
        (is (= [:header :body] (names :seon.render/html)))
        (is (= [:body] (names :seon.render/ai))
            "the header declares no ai render and is not in the prompt")))))

(deftest a-nil-render-key-is-omitted-like-an-absent-key
  ;; Nil exists only on in-memory units; durable render declarations are
  ;; nil-free. The router's declaration predicate is the one rule in both.
  (with-redefs [block/blocks
                (fn [_db _agent-id]
                  [(block-map :nil-html 0 :seon.render/html nil)])]
    (is (= [] (block/surfaces nil (html-request)))
        "nil is omission, never a kind-not-declared error surface")))

(deftest a-block-declaring-neither-kind-renders-nowhere-and-is-not-an-error
  (with-database [(block-map :data-only 5)]
    (fn [connection]
      (let [db (d/db connection)]
        (is (= [:data-only] (mapv :seon.render.block/name (block/blocks db agent-id))))
        (is (= [] (block/surfaces db (html-request))))))))

;;; ---------------------------------------------------------------------------
;;; Isolation — a broken block costs one card
;;; ---------------------------------------------------------------------------

(deftest an-unresolvable-projection-is-a-card-naming-the-symbol
  (with-database [(block-map :gone 0 :seon.render/html 'no.such.ns/nope)]
    (fn [connection]
      (let [[surface] (block/surfaces (d/db connection) (html-request))]
        (is (= :seon.render/unresolvable
               (:seon.error/kind (:seon.error/value surface)))
            "the landed router's value, passed through rather than re-wrapped")))))

(deftest output-that-is-not-the-kinds-grammar-is-a-card-naming-the-block
  ;; The quarry's silent bug made loud: the old serializer elided a bare
  ;; map child, the page looked fine, and nobody learned.
  (with-database [(block-map :sloppy 0 :seon.render/html `not-hiccup-html)]
    (fn [connection]
      (let [[surface] (block/surfaces (d/db connection) (html-request))
            failure (:seon.error/value surface)]
        (is (seon.schema/valid-candidate-value? :seon.error/value failure))
        (is (= :sloppy (:seon.render.block/name surface))
            "the block is named, because the block is what is broken")))))

(deftest every-surface-validates-whatever-the-projection-did
  ;; One standing totality property over the whole failure space, rather
  ;; than one test per way a block can be wrong.
  (support/assert-check!
   (tc/quick-check
    50
    (prop/for-all [projection (gen/elements [`body-html `throwing-html
                                             `not-hiccup-html
                                             'no.such.ns/nope
                                             `slots-a-missing-block-html])]
      (with-database [(block-map :subject 0 :seon.render/html projection)]
        (fn [connection]
          (let [[surface] (block/surfaces (d/db connection) (html-request))]
            (seon.schema/valid-candidate-value? :seon.render/surface surface)))))
    :seed 202607280202)
   "surface totality"))

;;; ---------------------------------------------------------------------------
;;; Placement
;;; ---------------------------------------------------------------------------

(deftest expansion-fills-holes-to-fixpoint
  (with-database two-blocks
    (fn [connection]
      (let [db (d/db connection)
            rendered (block/page db (page-request))]
        (is (= 1 (count rendered))
            "the body is slotted BY the header, so only the header is top-level")
        (is (= [:header {:class "flex gap-2"}
                [:span "seon"]
                [:div {:class "p-2"} (str "agent " agent-id)]]
               (first rendered))
            "the hole is gone and the surface is in its place")))))

(deftest a-slot-cycle-is-refused-at-the-hole-that-closes-it
  ;; The visited set on the path is the observable fact; a depth counter
  ;; would be a magic number standing in for it.
  (with-database
    [(block-map :cycle-one 0 :seon.render/html `cycle-one-html)
     (block-map :cycle-two 10 :seon.render/html `cycle-two-html)]
    (fn [connection]
      (let [rendered (block/page (d/db connection) (page-request))]
        (is (seq rendered) "the page still renders")
        (is (true? (every? hiccup/hiccup? rendered)))
        (is (re-find #"cycle" (pr-str rendered))
            "and says a cycle is why the hole is not filled")))))

;;; ---------------------------------------------------------------------------
;;; Generic default + specialist
;;; ---------------------------------------------------------------------------

(defn violation?
  "A specialist rule: a pure predicate over the value's OWN attributes.
  Nothing about it is render-specific, which is the point — the producer
  already knows how to recognize its own facts."
  [value]
  (= :malli/invalid-input (:seon.error/kind value)))

(defn always?
  [_value]
  true)

(defn broken-rule?
  [_value]
  (throw (ex-info "this rule is broken" {::deliberate true})))

(def ^:private selection
  {:seon.render/kind :seon.render/ai
   :seon.render/default `body-ai
   :seon.render/specialists [[`violation? `body-html]]})

(deftest selection-always-answers-with-a-projection
  ;; The oracle knows the four rule meanings without calling `select`:
  ;; broken and missing rules refuse, `always?` accepts, and `violation?`
  ;; accepts only the registered error kind. Order decides the first match.
  (support/assert-check!
   (tc/quick-check
    200
    (prop/for-all [violation? gen/boolean
                   rule-kinds (gen/vector
                               (gen/elements
                                [:violation :always :broken :missing])
                               0 6)]
      (let [value {:seon.error/kind
                   (if violation? :malli/invalid-input :a/other)}
            pair (fn [index rule-kind]
                   [(case rule-kind
                      :violation `violation?
                      :always `always?
                      :broken `broken-rule?
                      :missing 'no.such.ns/nope)
                    (symbol "seon.render.block-test"
                            (str "projection-" index))])
            rules (mapv pair (range) rule-kinds)
            expected
            (or
             (some (fn [[rule-kind [_ projection]]]
                     (when (or (= :always rule-kind)
                               (and (= :violation rule-kind) violation?))
                       projection))
                   (map vector rule-kinds rules))
             `body-ai)]
        (= expected
           (block/select
            value
            (assoc selection :seon.render/specialists rules)))))
    :seed 202607280203)
   "specialist selection"))

(deftest the-generic-html-default-renders-anything
  ;; The kind's floor: nothing is unrenderable, and no producer has to
  ;; write a renderer before it can see its value.
  (support/assert-check!
   (tc/quick-check
    200
    (prop/for-all [value gen/any-printable]
      (let [panelled (block/data-panel {:seon.render/value value
                                        :seon.sci.admit/caps caps})]
        (and (hiccup/hiccup? panelled)
             ;; NOT vacuous: it must really panel, not answer with the
             ;; missing-caps card. A totality property whose subject
             ;; short-circuits is the absence-of-signal-as-health class.
             (= "seon-data-panel" (:class (nth panelled 1))))))
    :seed 202607280204)
   "data-panel totality")
  (testing "the value is actually in there"
    (is (re-find #"widgets"
                 (hiccup/->string
                  (block/data-panel {:seon.render/value {:label "widgets"}
                                     :seon.sci.admit/caps caps})))))
  (testing "it panels the unit itself when no value key is present"
    (is (hiccup/hiccup? (block/data-panel {:seon.render.block/name :x
                                           :seon.sci.admit/caps caps}))))
  (testing "nil and absent render values panel the same unit"
    (let [unit {:seon.render.block/name :x :seon.sci.admit/caps caps}]
      (is (= (block/data-panel unit)
             (block/data-panel (assoc unit :seon.render/value nil))))))
  (testing "nil and absent declarations are equally absent from the panel"
    (let [unit {:seon.render.block/name :x :seon.sci.admit/caps caps}]
      (is (= (block/data-panel unit)
             (block/data-panel (assoc unit :seon.render/html nil))))))
  (testing "and never prints the projection declarations back at the reader"
    ;; the SYMBOL, not the substring: the wrapper's own CSS class is
    ;; legitimately called seon-data-panel
    (is (not (re-find #"seon\.render\.block/data-panel"
                      (pr-str (block/data-panel
                               {:seon.render/html `block/data-panel
                                :seon.sci.admit/caps caps
                                :seon.render/value {:a 1}}))))))
  (testing "an oversized value is elided and SAYS it was elided"
    (let [panelled (block/data-panel
                    {:seon.render/value (vec (range 500))
                     :seon.sci.admit/caps caps})]
      (is (re-find #"elided" (hiccup/->string panelled))
          "a reader must never have to guess whether a marker was the data")))
  (testing "no caps is a legible card, never an invented bound"
    (let [refused (block/data-panel {:seon.render/value {:a 1}})]
      (is (hiccup/hiccup? refused))
      (is (re-find #"caps" (hiccup/->string refused))))))

(deftest a-nil-entity-declaration-uses-the-generic-html-default
  ;; Ref expansion follows the router's declaration rule too: a nil
  ;; declaration is absent, so it cannot suppress the generic backstop.
  (with-redefs [block/entity-unit
                (fn [_db _lookup]
                  {:seon.render.block/name :referenced
                   :seon.render/html nil})]
    (let [expanded
          (block/expand (block/entity-slot 1)
                        {:seon.render/surfaces []
                         :seon.sci.admit/caps caps
                         :seon.db/db ::database})]
      (is (str/includes? (hiccup/->string expanded) "seon-data-panel")
          "the nil declaration does not become a kind-not-declared note"))))

(defn caps-panel
  "The generic default reached the way a producer reaches it: the block
  points at a projection that supplies the caps it was configured with.
  Package 2's pipeline threads those from config facts; here the seam is
  explicit so the test proves the panel, not the fallback."
  [unit]
  (block/data-panel (assoc unit :seon.sci.admit/caps caps)))

(deftest a-block-can-point-at-the-generic-default-like-any-other-symbol
  ;; The pattern needs no router change and no block change: the default
  ;; is an ordinary projection named by an ordinary symbol.
  (with-database [(block-map :anything 0 :seon.render/html `caps-panel)]
    (fn [connection]
      (let [[surface] (block/surfaces (d/db connection) (html-request))]
        (is (nil? (:seon.error/value surface)))
        (is (hiccup/hiccup? (:seon.render/output surface)))
        (is (= "seon-data-panel" (:class (nth (:seon.render/output surface) 1)))
            "it really panelled — the caps card would mean it did not")))))

;;; ---------------------------------------------------------------------------
;;; Writing a block set
;;; ---------------------------------------------------------------------------

(deftest install-upserts-by-name-within-one-agent
  (with-database [(block-map :body 10 :seon.render/html `body-html)]
    (fn [connection]
      (let [db (d/db connection)
            tx (block/install-tx db agent-id
                                 [(block-map :body 99 :seon.render/html `body-html)
                                  (block-map :extra 5 :seon.render/html `body-html)])]
        (d/transact connection tx)
        (let [after (block/blocks (d/db connection) agent-id)]
          (is (= [:extra :body] (mapv :seon.render.block/name after)))
          (is (= 99 (:seon.render.block/priority (second after)))
              "the same name was replaced, not duplicated"))))))

(deftest install-replaces-a-block-wholesale
  ;; A merge would make a block's fields un-deletable: removing
  ;; `:seon.render/ai` from a block must remove it from the prompt.
  (with-database [(block-map :body 10
                             :seon.render/html `body-html
                             :seon.render/ai `body-ai)]
    (fn [connection]
      (d/transact connection
                  (block/install-tx (d/db connection) agent-id
                                    [(block-map :body 10 :seon.render/html `body-html)]))
      (is (= [] (block/surfaces (d/db connection)
                                {:seon.cluster.agent/id agent-id
                                 :seon.render/kind :seon.render/ai
                                 :seon.sci.admit/caps caps}))
          "the ai render is gone, so the block is out of the prompt"))))

(deftest installing-nothing-is-no-transaction
  ;; Converged means zero writes — the rule `seon.reconcile` proved.
  (with-database two-blocks
    (fn [connection]
      (is (= [] (block/install-tx (d/db connection) agent-id []))))))

;;; ---------------------------------------------------------------------------
;;; Ref-following — the same walk, following connections
;;; ---------------------------------------------------------------------------

(defn error-html
  "A renderer that EMBEDS its ref rather than printing it. This is the
  whole ref-following idiom: name the connection, and expansion renders
  whatever is on the other end in place."
  [unit]
  [:div {:class "error"}
   [:span (:seon.error/message unit)]
   (when-let [run (:seon.error/run unit)]
     (block/entity-slot (:db/id run)))])

(defn form-html
  "Points back at its run — which is how an entity graph cycles."
  [unit]
  [:div {:class "form"}
   [:span (:seon.cluster.run.form/source unit)]
   (when-let [run (:seon.cluster.run.form/run unit)]
     (block/entity-slot (:db/id run)))])

(defn run-html
  [unit]
  [:div {:class "run"}
   [:span (str "run " (:seon.cluster.run/id unit))]
   ;; a cardinality-many ref: every child becomes its own hole
   (for [form (sort-by :db/id (:seon.cluster.run/forms unit))]
     (block/entity-slot (:db/id form)))])

(def ^:private ref-attributes
  [:seon.cluster.agent/id
   :seon.cluster.run/id :seon.cluster.run/agent :seon.cluster.run/opened-at
   :seon.cluster.run/forms
   :seon.cluster.run.form/id :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal :seon.cluster.run.form/source
   :seon.error/id :seon.error/kind :seon.error/message :seon.error/at
   :seon.error/signature :seon.error/process
   :seon.error/run
   :seon.render/html])

(defn- with-ref-database
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema ref-attributes))
      (d/transact connection
                  [{:seon.cluster.agent/id agent-id}
                   {:db/id -1
                    :seon.cluster.run/id "run-7f21"
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at #inst "2026-07-28T00:00:00.000-00:00"
                    :seon.cluster.run/forms
                    [{:seon.cluster.run.form/id "form-0"
                      :seon.cluster.run.form/ordinal 0
                      :seon.cluster.run.form/source "(+ 1 2)"}
                     {:seon.cluster.run.form/id "form-1"
                      :seon.cluster.run.form/ordinal 1
                      :seon.cluster.run.form/source "(my.run/complete \"3\")"}]}
                   {:seon.error/id "err-7f21"
                    :seon.error/kind :seon.ai/timeout
                    :seon.error/message "the model did not answer"
                    :seon.error/at #inst "2026-07-28T00:00:01.000-00:00"
                    :seon.error/signature "sig-1"
                    :seon.error/process "1234-1700000000000"
                    :seon.error/run -1}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- expansion
  [db]
  {:seon.render/surfaces [] :seon.sci.admit/caps caps :seon.db/db db})

(deftest a-rendered-unit-embeds-its-refs-as-units
  ;; TASK #11's recursive falsifier, the owner's own example: an error,
  ;; the run it interrupted, and that run's forms, as ONE expanded page.
  ;; Nothing here is page-specific — it is the block walk following
  ;; connections instead of names.
  (with-ref-database
    (fn [connection]
      (d/transact connection
                  [{:seon.error/id "err-7f21" :seon.render/html `error-html}
                   {:seon.cluster.run/id "run-7f21" :seon.render/html `run-html}])
      (let [db (d/db connection)
            unit (block/entity-unit db [:seon.error/id "err-7f21"])
            expanded (block/expand
                      (:seon.render/output
                       (render/render {:seon.render/unit unit
                                       :seon.render/kind :seon.render/html}))
                      (expansion db))
            html (hiccup/->string expanded)]
        (is (hiccup/hiccup? expanded))
        (is (str/includes? html "the model did not answer") "the error")
        (is (str/includes? html "run run-7f21") "its run, followed one hop")
        (is (str/includes? html "(+ 1 2)") "and the run's forms, two hops")
        (is (str/includes? html "my.run/complete") "every one of them")))))

(deftest an-entity-with-no-renderer-still-renders
  ;; What makes /data work with zero authoring: a ref to something
  ;; nobody wrote a renderer for falls to the kind's generic default,
  ;; which can project anything.
  (with-ref-database
    (fn [connection]
      (d/transact connection
                  [{:seon.error/id "err-7f21" :seon.render/html `error-html}])
      (let [db (d/db connection)
            unit (block/entity-unit db [:seon.error/id "err-7f21"])
            expanded (block/expand
                      (:seon.render/output
                       (render/render {:seon.render/unit unit
                                       :seon.render/kind :seon.render/html}))
                      (expansion db))
            html (hiccup/->string expanded)]
        (is (str/includes? html "seon-data-panel")
            "the run has no renderer, so the generic default projected it")
        (is (str/includes? html "run-7f21")
            "and its data is legible without anybody having authored it")))))

(deftest a-dangling-ref-is-a-note-and-not-a-dead-page
  (with-ref-database
    (fn [connection]
      (let [db (d/db connection)
            expanded (block/expand [:div (block/entity-slot 99999999)]
                                   (expansion db))]
        (is (hiccup/hiccup? expanded))
        (is (str/includes? (hiccup/->string expanded) "Nothing in the database")
            "a dangling ref is a fact about the database, not a reason to stop")))))

(deftest ref-following-without-a-database-refuses-in-place
  (let [expanded (block/expand [:div (block/entity-slot 1)]
                               {:seon.render/surfaces [] :seon.sci.admit/caps caps})]
    (is (hiccup/hiccup? expanded))
    (is (str/includes? (hiccup/->string expanded) "needs a database value"))))

(deftest a-ref-cycle-is-refused-at-the-hole-that-closes-it
  ;; THE REASON THE VISITED SET IS LOAD-BEARING HERE and merely helpful
  ;; for blocks: a value tree cannot cycle, and an entity graph
  ;; routinely does — a run points at its forms and each form points
  ;; back at its run.
  (with-ref-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.run.form/id "form-0"
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-7f21"]}])
      (let [db (d/db connection)
            run-id (:db/id (d/pull db [:db/id] [:seon.cluster.run/id "run-7f21"]))
            form-id (:db/id (d/pull db [:db/id] [:seon.cluster.run.form/id "form-0"]))]
        (d/transact connection
                    [{:db/id run-id :seon.render/html `run-html}
                     {:db/id form-id :seon.render/html `form-html}])
        (let [db (d/db connection)
              expanded (block/expand [:div (block/entity-slot run-id)]
                                     (expansion db))]
          (is (hiccup/hiccup? expanded))
          (is (str/includes? (hiccup/->string expanded) "cycle")
              "run → form → run stops where it closes"))))))

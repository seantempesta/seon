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
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]))

;;; ---------------------------------------------------------------------------
;;; Projections this suite owns
;;; ---------------------------------------------------------------------------

(def ^:dynamic *evaluations*
  "Counts calls per block name. The 'one block, one evaluation' property
  is about COUNTING work, so the projections have to be countable."
  nil)

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

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(def ^:private attributes
  [:seon.cluster.agent/id :seon.cluster.agent/blocks
   :seon.block/name :seon.block/priority
   :seon.render/ai :seon.render/html])

(def ^:private agent-id "agent-a")
(def ^:private other-agent-id "agent-b")

(defn- with-database
  [blocks body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection (schema.datahike/malli->datahike-schema attributes))
      (d/transact connection [{:seon.cluster.agent/id agent-id
                               :seon.cluster.agent/blocks blocks}
                              {:seon.cluster.agent/id other-agent-id}])
      (binding [*evaluations* (atom {})]
        (body connection))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- block-map
  [name priority & {:as slots}]
  (merge {:seon.block/name name :seon.block/priority priority} slots))

(def ^:private two-blocks
  [(block-map :header 0 :seon.render/html `header-html)
   (block-map :body 10 :seon.render/html `body-html :seon.render/ai `body-ai)])

(defn- html-request []
  {:seon.cluster.agent/id agent-id :seon.render/kind :seon.render/html})

(defn- check!
  [label result]
  (is (true? (:result result))
      (str label " failed: " (pr-str result))))

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
  (check!
   "distinct names, distinct ids"
   (tc/quick-check
    300
    (prop/for-all [[a b] (gen/such-that
                          (fn [[a b]] (not= a b))
                          (gen/tuple gen/keyword-ns gen/keyword-ns)
                          100)]
      (not= (block/surface-id a) (block/surface-id b)))
    :seed 202607280201))
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

(deftest blocks-are-ordered-by-priority-then-name
  (with-database
    [(block-map :zebra 10 :seon.render/html `body-html)
     (block-map :alpha 10 :seon.render/html `body-html)
     (block-map :first 0 :seon.render/html `body-html)]
    (fn [connection]
      (is (= [:first :alpha :zebra]
             (mapv :seon.block/name (block/blocks (d/db connection) agent-id)))
          "priority ascending, name as the stable tiebreak"))))

(deftest an-agent-with-no-blocks-derives-nothing
  ;; A legitimate agent, not an error, and not a cue to substitute a
  ;; default set: an absent block tree means no blocks.
  (with-database two-blocks
    (fn [connection]
      (is (= [] (block/blocks (d/db connection) other-agent-id)))
      (is (= [] (block/surfaces (d/db connection)
                                {:seon.cluster.agent/id other-agent-id
                                 :seon.render/kind :seon.render/html}))))))

(deftest each-agent-owns-its-own-set
  ;; Two agents may each own a `:transcript`; the name is not a store
  ;; identity, and one agent's blocks are never visible from another.
  (with-database [(block-map :transcript 0 :seon.render/html `body-html)]
    (fn [connection]
      (let [db (d/db connection)]
        (is (= [:transcript] (mapv :seon.block/name (block/blocks db agent-id))))
        (is (= [] (block/blocks db other-agent-id)))))))

(deftest the-unit-carries-the-exact-database-value
  ;; Never ambient. A projection that consulted a latest value would
  ;; render at a basis the rest of the page was not rendered at.
  (with-database [(block-map :counter 0 :seon.render/html `reads-the-database-html)]
    (fn [connection]
      (let [before (d/db connection)
            _ (d/transact connection [{:seon.cluster.agent/id "agent-c"}])
            after (d/db connection)
            at (fn [db] (-> (block/surfaces db (html-request))
                            first :seon.render/output))]
        (is (= [:p "agents: 2"] (at before)))
        (is (= [:p "agents: 3"] (at after))
            "the same block at two database values renders two pages")))))

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
                    (mapv :seon.block/name
                          (block/surfaces db {:seon.cluster.agent/id agent-id
                                              :seon.render/kind kind})))]
        (is (= [:header :body] (names :seon.render/html)))
        (is (= [:body] (names :seon.render/ai))
            "the header declares no ai render and is not in the prompt")))))

(deftest a-block-declaring-neither-kind-renders-nowhere-and-is-not-an-error
  (with-database [(block-map :data-only 5)]
    (fn [connection]
      (let [db (d/db connection)]
        (is (= [:data-only] (mapv :seon.block/name (block/blocks db agent-id))))
        (is (= [] (block/surfaces db (html-request))))))))

(deftest one-block-is-evaluated-once-per-render
  ;; The property the 32-tab falsifier scales up: the prompt and every
  ;; tab read ONE value, because there is one place a block is rendered.
  (with-database two-blocks
    (fn [connection]
      (block/surfaces (d/db connection) (html-request))
      (is (= {:header 1 :body 1} @*evaluations*)))))

;;; ---------------------------------------------------------------------------
;;; Isolation — a broken block costs one card
;;; ---------------------------------------------------------------------------

(deftest a-projection-that-throws-becomes-a-card-and-spares-its-siblings
  (with-database
    [(block-map :broken 0 :seon.render/html `throwing-html)
     (block-map :body 10 :seon.render/html `body-html)]
    (fn [connection]
      (let [rendered (block/surfaces (d/db connection) (html-request))
            [broken body] rendered]
        (is (= 2 (count rendered)) "the broken block keeps its place")
        (is (= :broken (:seon.block/name broken)))
        (is (= "surface-broken" (:seon.render/surface-id broken))
            "and keeps its address, so its error has somewhere to go")
        (is (seon.schema/valid-candidate-value?
             :seon.error/value (:seon.error/value broken)))
        (is (nil? (:seon.render/output broken))
            "failure is INSTEAD of output, never beside it")
        (is (= [:div {:class "p-2"} (str "agent " agent-id)]
               (:seon.render/output body))
            "the sibling is untouched")))))

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
        (is (= :sloppy (:seon.block/name surface))
            "the block is named, because the block is what is broken")))))

(deftest every-surface-validates-whatever-the-projection-did
  ;; One standing totality property over the whole failure space, rather
  ;; than one test per way a block can be wrong.
  (check!
   "surface totality"
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
    :seed 202607280202)))

;;; ---------------------------------------------------------------------------
;;; Placement
;;; ---------------------------------------------------------------------------

(deftest expansion-fills-holes-to-fixpoint
  (with-database two-blocks
    (fn [connection]
      (let [db (d/db connection)
            rendered (block/page db agent-id)]
        (is (= 1 (count rendered))
            "the body is slotted BY the header, so only the header is top-level")
        (is (= [:header {:class "flex gap-2"}
                [:span "seon"]
                [:div {:class "p-2"} (str "agent " agent-id)]]
               (first rendered))
            "the hole is gone and the surface is in its place")))))

(deftest a-block-nobody-slots-is-a-top-level-card
  ;; Several top-level blocks are the normal case — root cards. Nothing
  ;; declares this; it is derived from what the hiccup says.
  (with-database
    [(block-map :one 0 :seon.render/html `body-html)
     (block-map :two 10 :seon.render/html `body-html)]
    (fn [connection]
      (is (= 2 (count (block/page (d/db connection) agent-id)))))))

(deftest a-slot-naming-a-block-the-agent-does-not-own-self-heals
  ;; The quarry's behaviour, kept: name the block, and the next render
  ;; fills it. A throw here would cost the page.
  (with-database [(block-map :layout 0 :seon.render/html `slots-a-missing-block-html)]
    (fn [connection]
      (let [[page] (block/page (d/db connection) agent-id)]
        (is (true? (hiccup/hiccup? page)))
        (is (re-find #"no-such-block" (pr-str page))
            "the hole says which block is missing")))))

(deftest a-slot-cycle-is-refused-at-the-hole-that-closes-it
  ;; The visited set on the path is the observable fact; a depth counter
  ;; would be a magic number standing in for it.
  (with-database
    [(block-map :cycle-one 0 :seon.render/html `cycle-one-html)
     (block-map :cycle-two 10 :seon.render/html `cycle-two-html)]
    (fn [connection]
      (let [rendered (block/page (d/db connection) agent-id)]
        (is (seq rendered) "the page still renders")
        (is (true? (every? hiccup/hiccup? rendered)))
        (is (re-find #"cycle" (pr-str rendered))
            "and says a cycle is why the hole is not filled")))))

(deftest a-failed-block-puts-its-error-where-it-belongs
  (with-database
    [(block-map :layout 0 :seon.render/html `header-html)
     (block-map :body 10 :seon.render/html `throwing-html)]
    (fn [connection]
      (let [[page] (block/page (d/db connection) agent-id)]
        (is (re-find #"body" (pr-str page))
            "the failing block's card is inside the layout's hole")))))

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

(deftest a-specialist-wins-when-the-values-own-attributes-select-it
  (is (= `body-html
         (block/select {:seon.error/kind :malli/invalid-input} selection))
      "computed from the fact, at the place the unit is built")
  (is (= `body-ai (block/select {:seon.error/kind :a/other} selection))
      "and everything else gets the kind's generic default"))

(deftest no-specialists-is-the-ordinary-case-and-needs-no-special-code
  (is (= `body-ai
         (block/select {:seon.error/kind :malli/invalid-input}
                       (assoc selection :seon.render/specialists [])))))

(deftest the-first-accepting-specialist-wins
  ;; Ordering is the producer's judgement; nothing scores specificity.
  (is (= `body-html
         (block/select {:seon.error/kind :malli/invalid-input}
                       (assoc selection :seon.render/specialists
                              [[`violation? `body-html]
                               [`always? `header-html]]))))
  (is (= `header-html
         (block/select {}
                       (assoc selection :seon.render/specialists
                              [[`violation? `body-html]
                               [`always? `header-html]])))))

(deftest a-broken-rule-costs-its-own-specialist-and-nothing-else
  ;; Selection runs where units are built, and that is often the error
  ;; path: a rule that throws must not become a second error.
  (doseq [rules [[[`broken-rule? `body-html]]
                 [['no.such.ns/nope `body-html]]
                 [[`broken-rule? `body-html] [`always? `header-html]]]]
    (let [chosen (block/select {} (assoc selection :seon.render/specialists rules))]
      (is (qualified-symbol? chosen))
      (is (not= `body-html chosen)
          (str "a rule that cannot answer does not accept: " (pr-str rules))))))

(deftest selection-always-answers-with-a-projection
  ;; One standing totality property: whatever the value and whatever the
  ;; rules do, a producer gets a symbol it can put on the unit.
  (check!
   "selection totality"
   (tc/quick-check
    200
    (prop/for-all [value gen/any-printable
                   rules (gen/vector
                          (gen/elements [[`violation? `body-html]
                                         [`always? `header-html]
                                         [`broken-rule? `body-html]
                                         ['no.such.ns/nope `body-html]])
                          0 4)]
      (qualified-symbol?
       (block/select value (assoc selection :seon.render/specialists rules))))
    :seed 202607280203)))

(def ^:private caps
  "The four result dials, by the names `seon.sci.admit` already takes.
  Supplied EXPLICITLY: a panel that invented its own bounds would be a
  second set of size dials drifting from the config facts."
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(deftest the-generic-html-default-renders-anything
  ;; The kind's floor: nothing is unrenderable, and no producer has to
  ;; write a renderer before it can see its value.
  (check!
   "data-panel totality"
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
    :seed 202607280204))
  (testing "the value is actually in there"
    (is (re-find #"widgets"
                 (hiccup/->string
                  (block/data-panel {:seon.render/value {:label "widgets"}
                                     :seon.sci.admit/caps caps})))))
  (testing "it panels the unit itself when no value key is present"
    (is (hiccup/hiccup? (block/data-panel {:seon.block/name :x
                                           :seon.sci.admit/caps caps}))))
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
          (is (= [:extra :body] (mapv :seon.block/name after)))
          (is (= 99 (:seon.block/priority (second after)))
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
                                 :seon.render/kind :seon.render/ai}))
          "the ai render is gone, so the block is out of the prompt"))))

(deftest installing-nothing-is-no-transaction
  ;; Converged means zero writes — the rule `seon.reconcile` proved.
  (with-database two-blocks
    (fn [connection]
      (is (= [] (block/install-tx (d/db connection) agent-id []))))))

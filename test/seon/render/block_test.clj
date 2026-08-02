(ns seon.render.block-test
  "The retained render address, floor, and bounded expansion primitives."
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
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

(def ^:private agent-id "agent-a")

(def ^:private caps
  "The four result dials, by the names `seon.sci.admit` already takes.
  ONE definition for both callers, deliberately: expansion walks a graph
  that can fan out and can cycle, and the data panel walks a value that
  can be enormous — the same problem the admission codec already solved,
  so both take that solution's dials rather than a second set to drift
  from them. Supplied EXPLICITLY everywhere, because a renderer that
  invented its own bounds would be exactly that second set."
  (config/result-caps (config/defaults)))

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
  (testing "router and admission request data is never presented as domain data"
    (let [html (hiccup/->string
                (block/data-panel
                 {:seon.render.block/name :x
                  :seon.render/would-fall-to-floor? true
                  :seon.render/namespace 'my.viewer
                  :seon.sci.admit/caps caps}))]
      (is (not (str/includes? html "would-fall-to-floor")))
      (is (not (str/includes? html "max-collection")))
      (is (not (str/includes? html "my.viewer")))))
  (testing "nil is a value while an absent value key panels the unit"
    (let [unit {:seon.render.block/name :x :seon.sci.admit/caps caps}]
      (is (not= (block/data-panel unit)
                (block/data-panel (assoc unit :seon.render/value nil))))
      (is (str/includes?
           (hiccup/->string
            (block/data-panel (assoc unit :seon.render/value nil)))
           "nil"))))
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
    (let [narrow-caps
          (assoc caps :seon.config.eval.result/max-collection 4)
          panelled (block/data-panel
                    {:seon.render/value (vec (range 20))
                     :seon.sci.admit/caps narrow-caps})]
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

;;; ---------------------------------------------------------------------------
;;; Distance — the hops of neighborhood one render was asked for
;;; (owner ruling, 2026-07-28 post-midnight; seal revision)
;;; ---------------------------------------------------------------------------

(defn- neighborhood
  "The error, expanded at `hops`, as html. The seeded chain is the
  owner's own example and is two hops deep: the error delegates to its
  run, the run delegates to each of its forms."
  [db hops]
  (let [unit (block/entity-unit db [:seon.error/id "err-7f21"])]
    (hiccup/->string
     (block/expand
      (:seon.render/output
       (render/render {:seon.render/unit unit
                       :seon.render/kind :seon.render/html}))
      (cond-> (expansion db)
        hops (assoc :seon.render/distance hops))))))

(deftest distance-is-the-hops-a-render-may-spend
  ;; Distance is an argument TO the renderer: `error-html` delegates
  ;; unconditionally, and what the expansion FOLLOWS is what the request
  ;; paid for. One hop reaches the run; two reach the run's forms.
  (with-ref-database
    (fn [connection]
      (d/transact connection
                  [{:seon.error/id "err-7f21" :seon.render/html `error-html}
                   {:seon.cluster.run/id "run-7f21" :seon.render/html `run-html}])
      (let [db (d/db connection)
            at (fn [hops] (neighborhood db hops))]
        (testing "distance 0 renders the unit itself and follows nothing"
          (let [html (at 0)]
            (is (str/includes? html "the model did not answer") "the error")
            (is (not (str/includes? html "run run-7f21"))
                "no hop was paid for, so no neighbor was rendered")
            (is (str/includes? html "past the requested render distance")
                "and the hole SAYS why it is empty")))
        (testing "distance 1 reaches the neighbor and stops there"
          (let [html (at 1)]
            (is (str/includes? html "run run-7f21") "one hop, one neighbor")
            (is (not (str/includes? html "(+ 1 2)"))
                "the neighbor was rendered at distance 0, so it reached nothing")))
        (testing "distance N reaches N hops"
          (let [html (at 2)]
            (is (str/includes? html "run run-7f21"))
            (is (str/includes? html "(+ 1 2)") "the run's forms, two hops out")
            (is (str/includes? html "my.run/complete"))))
        (testing "more distance than the graph has is not an error"
          (is (= (at 2) (at 7))
              "the walk simply runs out of connections"))))))

(deftest an-absent-distance-is-byte-identical-to-before-the-accretion
  ;; THE ACCRETION PROOF: requires no more (a caller that says nothing
  ;; gets exactly what it got) and provides no less. The caps alone bound
  ;; a distance-free expansion, which is what this function always did.
  (with-ref-database
    (fn [connection]
      (d/transact connection
                  [{:seon.error/id "err-7f21" :seon.render/html `error-html}
                   {:seon.cluster.run/id "run-7f21" :seon.render/html `run-html}])
      (let [db (d/db connection)]
        (is (= (neighborhood db nil) (neighborhood db 2))
            "the seeded chain is two hops, so an unbounded walk equals it")
        (is (str/includes? (neighborhood db nil) "my.run/complete")
            "and it really did walk the whole chain")))))

(deftest distance-never-overrides-the-admission-caps
  ;; It SELECTS WITHIN them. A depth dial of 1 stops the walk however
  ;; many hops the request was willing to pay for.
  (with-ref-database
    (fn [connection]
      (d/transact connection
                  [{:seon.error/id "err-7f21" :seon.render/html `error-html}
                   {:seon.cluster.run/id "run-7f21" :seon.render/html `run-html}])
      (let [db (d/db connection)
            shallow (assoc caps :seon.config.eval.result/max-depth 1)
            html (hiccup/->string
                  (block/expand
                   (:seon.render/output
                    (render/render
                     {:seon.render/unit (block/entity-unit
                                         db [:seon.error/id "err-7f21"])
                      :seon.render/kind :seon.render/html}))
                   {:seon.render/surfaces []
                    :seon.sci.admit/caps shallow
                    :seon.render/distance 9
                    :seon.db/db db}))]
        (is (str/includes? html "run run-7f21") "one hop is within the depth")
        (is (not (str/includes? html "(+ 1 2)"))
            "the configured depth stopped the walk, not the distance")))))

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

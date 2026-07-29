(ns seon.context-pilot-test
  "The namespace+distance context pilot — an agent's prompt IS the
  rendered view of its own namespace at a distance.

  WHAT THIS SUITE PROVES, and it is one claim in four parts (owner
  ruling 2026-07-28 post-midnight #2): the agent's neighbourhood facts
  are IN the derived prompt at distance 1, ABSENT at distance 0, DEEPEN
  at distance 2, and every neighbour is rendered by ITS OWNER'S lens
  rather than by anything the block knows. If the last part were false
  the mechanism would be a hand-written prompt section wearing a walk's
  clothes.

  It also holds the FUNERAL's coverage proof. `:interruption` and
  `:continuity` were retired from the seeded membership because the run
  and receipt family lenses say what they said; the classes those blocks
  owned are asserted here against the surviving mechanism, so the
  deletion is evidenced rather than asserted.

  ONE FRESH IN-MEMORY DATABASE PER TEST, created and deleted inside the
  test, matching every other suite in the tree. Seeds are fixed
  (2026072830+); generated inputs are functions of their seed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.run :as run]
            [seon.error :as error]
            [seon.render.agent :as agent]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.walk :as walk]
            [seon.test-support :as support])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; The world — seed 2026072830
;;; ---------------------------------------------------------------------------

(def ^:private caps
  "The same four dials the eval door carries. Deliberately the production
  shape rather than a test-local set: the walk's node budget and its
  reverse-neighbour width are these, and a suite that invented its own
  would be proving a mechanism nothing ships."
  {:seon.config.eval.result/max-depth 8
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(def ^:private agent-id "pilot")
(def ^:private run-id "run-2026072830")
(def ^:private previous-run-id "run-2026072830-previous")
(def ^:private message-id "m-2026072830")

(defn- plant!
  "One agent with a world worth rendering: a peer, the message it was
  asked, a PREVIOUS run that paused with a note and left a receipt, and
  the held run this prompt is for. Every fact is one a real turn commits."
  [connection]
  (d/transact connection [{:seon.cluster.agent/id agent-id}
                          {:seon.cluster.agent/id "peer"}])
  (d/transact connection (agent/seed-tx (d/db connection) agent-id))
  (d/transact connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content "count the widgets"
                :seon.cluster.message/at (Date. 1700000001000)}])
  (d/transact connection
              [{:seon.cluster.run/id previous-run-id
                :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                :seon.cluster.run/opened-at (Date. 1700000002000)
                :seon.cluster.run/closed-at (Date. 1700000009000)
                :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))
                :seon.cluster.run/forms
                [{:seon.cluster.run.form/id "f-2026072830"
                  :seon.cluster.run.form/run
                  [:seon.cluster.run/id previous-run-id]
                  :seon.cluster.run.form/ordinal 0
                  :seon.cluster.run.form/source
                  "(my.run/wait \"waiting on peer\")"}]}
               {:seon.cluster.eval/id "e-2026072830"
                :seon.cluster.eval/run [:seon.cluster.run/id previous-run-id]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/at (Date. 1700000003000)
                :seon.cluster.eval/result-edn
                (pr-str {:my.run/disposition :wait
                         :my.run/note "peer owes me the widget count"})}])
  (d/transact connection
              {:tx-data [{:seon.cluster.run/id run-id
                          :seon.cluster.run/agent
                          [:seon.cluster.agent/id agent-id]
                          :seon.cluster.run/opened-at (Date. 1700000010000)}
                         {:seon.cluster.agent/id agent-id
                          :seon.cluster.agent/run [:seon.cluster.run/id run-id]}]
               :tx-meta {:seon.db/trigger
                         [:seon.cluster.message/id message-id]}}))

(defn- prompt-at
  "The derived prompt text at one distance. The ONE production path —
  `seon.cluster.prompt`, unchanged, reducing the membership it already
  reduced; the neighbourhood view is simply one more block in it."
  [connection distance]
  (:seon.cluster.prompt/text
   (prompt/prompt (d/db connection)
                  (cond-> {:seon.cluster.run/id run-id
                           :seon.cluster.agent/id agent-id
                           :seon.sci.admit/caps caps}
                    distance (assoc :seon.render/distance distance)))))

(defn- with-world [body]
  (support/with-database
    (fn [connection] (plant! connection) (body connection))))

;;; ---------------------------------------------------------------------------
;;; 1. Distance selects how much of the world the prompt contains
;;; ---------------------------------------------------------------------------

(deftest the-prompt-is-the-rendered-neighbourhood
  (with-world
    (fn [connection]
      (let [at-1 (prompt-at connection 1)]
        (testing "the agent's own runs are in its prompt, by their own lens"
          (is (str/includes? at-1 previous-run-id))
          (is (str/includes? at-1 run-id)))
        (testing "so are the messages sent to it"
          (is (str/includes? at-1 "count the widgets")))
        (testing "and the block wrote none of those sentences: they are
                  the run and message families' own prose"
          (is (str/includes? at-1 "It paused, leaving this note")))))))

(deftest distance-zero-follows-nothing
  (with-world
    (fn [connection]
      (let [at-0 (prompt-at connection 0)]
        (testing "the agent itself still renders — distance 0 is identity
                  only, not silence"
          (is (str/includes? at-0 (str "Agent " agent-id))))
        (testing "but nothing it is connected to is followed"
          (is (not (str/includes? at-0 previous-run-id)))
          (is (not (str/includes? at-0 "It paused"))))))))

(deftest distance-two-deepens-rather-than-repeats
  (with-world
    (fn [connection]
      (let [at-1 (prompt-at connection 1)
            at-2 (prompt-at connection 2)]
        (testing "a second hop reaches what the first could not: the
                  previous run's own forms and receipts"
          ;; the scaffold's execution grammar mentions `my.run/wait`
          ;; too, so the falsifier must name the FORM THE AGENT WROTE
          (is (not (str/includes? at-1 "(my.run/wait \"waiting on peer\")")))
          (is (str/includes? at-2 "(my.run/wait \"waiting on peer\")"))
          (is (str/includes? at-2 "Form 0")))
        (testing "and the first hop's facts are still there"
          (is (str/includes? at-2 previous-run-id)))
        (testing "each hop costs something — deeper is strictly larger"
          (is (< (count at-1) (count at-2))))))))

(deftest the-implied-distance-is-one
  (with-world
    (fn [connection]
      ;; the default is written in exactly one place
      ;; (`seon.render.block/distance`), so a caller that says nothing
      ;; asks for the ordinary reach and no caller has to know the number
      (is (= (prompt-at connection nil) (prompt-at connection 1))))))

;;; ---------------------------------------------------------------------------
;;; 1b. The HTML twin is the same neighbourhood on the agent page
;;; ---------------------------------------------------------------------------

(deftest the-agent-page-is-the-rendered-neighbourhood
  (with-world
    (fn [connection]
      (let [page (block/page
                  (d/db connection)
                  {:seon.cluster.agent/id agent-id
                   :seon.sci.admit/caps caps
                   :seon.render/distance 2})
            html (apply str (map hiccup/->string page))]
        (testing "the page has the same run and message facts as the prompt"
          (is (str/includes? html previous-run-id))
          (is (str/includes? html run-id))
          (is (str/includes? html "count the widgets")))
        (testing "and a second hop uses the form and receipt family twins"
          (is (str/includes? html "(my.run/wait &quot;waiting on peer&quot;)"))
          (is (str/includes? html "peer owes me the widget count")))
        (testing "the namespace is one ordinary identified surface"
          (is (= 1 (count page)))
          (is (str/includes? html "id=\"surface-namespace\""))
          (is (str/includes? html "class=\"seon-card seon-neighborhood\"")))))))

(deftest every-family-html-twin-preserves-its-ai-facts
  (let [pairs [[run/render-ai run/render-html
                {:seon.cluster.run/id "run-html"
                 :seon.cluster.run/opened-at (Date. 1700000000000)}]
               [run/render-form-ai run/render-form-html
                {:seon.cluster.run.form/ordinal 4
                 :seon.cluster.run.form/source "(inc 4)"}]
               [run/render-receipt-ai run/render-receipt-html
                {:seon.cluster.eval/ordinal 4
                 :seon.cluster.eval/result-edn "5"
                 :seon.cluster.eval/output "counted"}]
               [message/render-ai message/render-html
                {:seon.cluster.message/content "hello from outside"}]
               [agent/agent-ai agent/agent-html
                {:seon.cluster.agent/id "html-agent"}]
               [error/render-ai error/render-html
                {:seon.error/id "html-error"
                 :seon.error/kind :seon.test/failure
                 :seon.error/message "the test failed"}]]]
    (doseq [[ai html unit] pairs]
      (let [text (ai unit)
            rendered (html unit)]
        (is (string? text))
        (is (= text (get-in rendered [2 1]))
            (str "the HTML twin must carry its AI twin's facts: " text))))))

;;; ---------------------------------------------------------------------------
;;; 2. Every hop is rendered by its OWNER's lens
;;; ---------------------------------------------------------------------------

(deftest each-neighbour-is-rendered-by-its-family
  (with-world
    (fn [connection]
      (let [node (walk/neighborhood
                  {:seon.db/db (d/db connection)
                   :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
                   :seon.render/kind :seon.render/ai
                   :seon.render/floor `block/data-prose
                   :seon.sci.admit/caps caps
                   :seon.render/distance 2})
            projections (into #{}
                              (map :seon.render/projection)
                              (tree-seq :seon.render.walk/neighbours
                                        :seon.render.walk/neighbours
                                        node))]
        (testing "the capture records which projection produced each hop,
                  so a rendered neighbourhood is re-derivable"
          (is (contains? projections 'seon.render.agent/agent-ai))
          (is (contains? projections 'seon.cluster.run/render-ai))
          (is (contains? projections 'seon.cluster.message/render-ai))
          (is (contains? projections 'seon.cluster.run/render-receipt-ai)))
        (testing "and every node names the connection it was reached
                  through, rather than leaving the reader to infer it"
          (is (contains? (into #{}
                               (map :seon.render.walk/attribute)
                               (:seon.render.walk/neighbours node))
                         :seon.cluster.run/agent)))))))

(deftest a-family-with-no-lens-still-renders-through-the-floor
  (support/with-database
    (fn [connection]
      ;; a bare agent with one connection nobody wrote a lens for. The
      ;; floor is what makes "nothing is unrenderable" true by
      ;; construction rather than by everyone remembering to write one.
      (d/transact connection [{:seon.cluster.agent/id agent-id}])
      (d/transact connection
                  [{:seon.cluster.run/id run-id
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 1700000010000)}])
      (let [node (walk/neighborhood
                  {:seon.db/db (d/db connection)
                   :seon.render.walk/lookup [:seon.cluster.run/id run-id]
                   :seon.render/kind :seon.render/log ; no family declares it
                   :seon.render/floor `block/data-prose
                   :seon.sci.admit/caps caps
                   :seon.render/distance 1})]
        (is (= `block/data-prose (:seon.render/projection node)))
        (is (str/includes? (:seon.render/output node) run-id))))))

(deftest the-viewers-override-wins-over-the-family-and-holds-for-the-walk
  (with-world
    (fn [connection]
      (let [node (walk/neighborhood
                  {:seon.db/db (d/db connection)
                   :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
                   :seon.render/kind :seon.render/ai
                   :seon.render/floor `block/data-prose
                   :seon.sci.admit/caps caps
                   :seon.render/distance 2
                   :seon.render/overrides
                   {:seon.cluster.run/run `block/data-prose}})
            runs (filter (comp #{:seon.cluster.run/agent
                                 :seon.cluster.agent/run}
                               :seon.render.walk/attribute)
                         (:seon.render.walk/neighbours node))]
        (testing "the viewer's lens for a type replaces the owning
                  family's default"
          (is (seq runs))
          (is (every? (comp #{`block/data-prose} :seon.render/projection)
                      runs))
          (is (not (str/includes? (walk/prose node) "It paused, leaving"))))
        (testing "and the VIEWER IS CONSTANT: the agent's own lens still
                  rendered the root, so perspective never shifted to an
                  intermediate namespace"
          (is (= 'seon.render.agent/agent-ai
                 (:seon.render/projection node))))))))

(deftest each-html-hop-is-rendered-by-its-family
  (with-world
    (fn [connection]
      (let [node (walk/neighborhood
                  {:seon.db/db (d/db connection)
                   :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
                   :seon.render/kind :seon.render/html
                   :seon.render/floor `block/data-panel
                   :seon.sci.admit/caps caps
                   :seon.render/distance 2})
            projections (into #{}
                              (map :seon.render/projection)
                              (tree-seq :seon.render.walk/neighbours
                                        :seon.render.walk/neighbours
                                        node))]
        (is (contains? projections 'seon.render.agent/agent-html))
        (is (contains? projections 'seon.cluster.run/render-html))
        (is (contains? projections 'seon.cluster.message/render-html))
        (is (contains? projections 'seon.cluster.run/render-form-html))
        (is (contains? projections 'seon.cluster.run/render-receipt-html))))))

;;; ---------------------------------------------------------------------------
;;; 3. The apparatus is not the world
;;; ---------------------------------------------------------------------------

(deftest the-view-does-not-walk-into-itself
  (with-world
    (fn [connection]
      (let [text (prompt-at connection 2)]
        (testing "an agent's blocks are how it is being looked at, not
                  something it is connected to — the first derivation
                  walked into them and rendered `:identity` against a
                  block entity, announcing an agent with no id"
          (is (not (str/includes? text "You are agent .")))
          (is (not (str/includes? text ":seon.render.block/name"))))
        (testing "and transaction entities are the database's own
                  bookkeeping rather than neighbours"
          (is (not (str/includes? text ":db/txInstant"))))))))

(deftest one-neighbour-is-rendered-once
  (with-world
    (fn [connection]
      ;; the held run is BOTH a forward ref from the agent and a reverse
      ;; ref back to it. Two mentions of one entity is one entity.
      (let [text (prompt-at connection 1)]
        (is (= 1 (count (re-seq (re-pattern (str "Run " run-id ",")) text))))))))

;;; ---------------------------------------------------------------------------
;;; 4. The funeral's coverage — what the retired blocks used to say
;;; ---------------------------------------------------------------------------

(deftest the-retired-continuity-block-is-covered-by-the-run-lens
  (with-world
    (fn [connection]
      ;; `:continuity` said "you paused, leaving yourself this note".
      ;; The note IS the last form's admitted value, already durable in
      ;; the receipt, and a run is one hop from its agent — so the run's
      ;; own lens carries it at the ordinary reach.
      (is (str/includes? (prompt-at connection 1)
                         "peer owes me the widget count")))))

(deftest the-retired-interruption-block-is-covered-by-the-run-lens
  (support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id agent-id}])
      (d/transact connection (agent/seed-tx (d/db connection) agent-id))
      (d/transact connection
                  [{:seon.cluster.message/id message-id
                    :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                    :seon.cluster.message/content "count the widgets"
                    :seon.cluster.message/at (Date. 1700000001000)}])
      ;; a previous run cut mid-fold: form 0 settled, form 1 interrupted
      (d/transact connection
                  [{:seon.cluster.run/id previous-run-id
                    :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
                    :seon.cluster.run/opened-at (Date. 1700000002000)
                    :seon.cluster.run/plan-digest (apply str (repeat 64 "b"))
                    :seon.cluster.run/forms
                    [{:seon.cluster.run.form/id "cut-0"
                      :seon.cluster.run.form/run
                      [:seon.cluster.run/id previous-run-id]
                      :seon.cluster.run.form/ordinal 0
                      :seon.cluster.run.form/source "(+ 1 1)"}
                     {:seon.cluster.run.form/id "cut-1"
                      :seon.cluster.run.form/run
                      [:seon.cluster.run/id previous-run-id]
                      :seon.cluster.run.form/ordinal 1
                      :seon.cluster.run.form/source "(my.fs/write \"x\")"}]}
                   {:seon.cluster.eval/id "cut-e-0"
                    :seon.cluster.eval/run
                    [:seon.cluster.run/id previous-run-id]
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/at (Date. 1700000003000)
                    :seon.cluster.eval/result-edn "2"}
                   {:seon.cluster.eval/id "cut-e-1"
                    :seon.cluster.eval/run
                    [:seon.cluster.run/id previous-run-id]
                    :seon.cluster.eval/ordinal 1
                    :seon.cluster.eval/at (Date. 1700000004000)
                    :seon.cluster.eval/interrupted-at (Date. 1700000005000)}])
      (d/transact connection
                  {:tx-data [{:seon.cluster.run/id run-id
                              :seon.cluster.run/agent
                              [:seon.cluster.agent/id agent-id]
                              :seon.cluster.run/opened-at (Date. 1700000010000)}
                             {:seon.cluster.agent/id agent-id
                              :seon.cluster.agent/run
                              [:seon.cluster.run/id run-id]}]
                   :tx-meta {:seon.db/trigger
                             [:seon.cluster.message/id message-id]}})
      (let [text (prompt-at connection 1)]
        (testing "the cut fold, its ordinal, its missing results and the
                  no-retry doctrine — all of it, from the run's lens"
          (is (str/includes? text "interrupted at form 1"))
          (is (str/includes? text "1 result(s) are missing"))
          (is (str/includes? text "nothing was retried")))
        (testing "and it is said ONCE, not once per consumer"
          (is (= 1 (count (re-seq #"interrupted at form" text)))))))))

(deftest a-clean-world-says-nothing-about-a-crash-that-did-not-happen
  (with-world
    (fn [connection]
      (let [text (prompt-at connection 1)]
        (is (not (str/includes? (str/lower-case text) "interrupted at form")))
        (is (not (str/includes? (str/lower-case text) "was interrupted")))))))

;;; ---------------------------------------------------------------------------
;;; 5. Derivation is a read — the standing no-write property
;;; ---------------------------------------------------------------------------

(deftest deriving-a-neighbourhood-commits-nothing
  (with-world
    (fn [connection]
      (let [before (:max-tx (d/db connection))]
        (dotimes [_ 3] (prompt-at connection 2))
        (is (= before (:max-tx (d/db connection)))
            "a render is a projection of the database, never a write")))))

(deftest two-derivations-of-one-database-value-are-the-same-value
  (with-world
    (fn [connection]
      ;; equality suppression, the re-derivable capture and the whole
      ;; live-update pipeline depend on this. Ordering that varied by
      ;; map iteration, or an inst printed through the rendering
      ;; machine's locale, would break all three quietly.
      (is (= (prompt-at connection 2) (prompt-at connection 2))))))

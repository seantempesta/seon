(ns seon.run
  "Construct completion and waiting values for an agent session."
  (:require [seon.error.refusal]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn render-namespace-ai
  "Present my.turn as the lifecycle protocol, in use order."
  {:malli/schema [:=> [:cat :my.turn/namespace-unit] :seon.render/ai]}
  [unit]
  (let [database (:seon.db/db unit)
        docs
        (when database
          (into
           {}
           (db/q '[:find ?sym ?doc
                   :in $ [?sym ...]
                   :where
                   [?function :seon.fn/sym ?sym]
                   [?function :seon.fn/doc ?doc]]
                 database
                 '[my.turn/complete my.turn/wait])))]
    (str (:seon.ns/doc unit)
         "\n\n1. complete — "
         (or (get docs 'my.turn/complete)
             "Finish completed work with a reply for its requester.")
         "\n\n2. wait — "
         (or (get docs 'my.turn/wait)
             "Finish paused work with the condition needed to continue."))))

(defn walkthrough
  "The executable lifecycle walkthrough used by the opening episode."
  {:malli/schema [:=> [:cat] :seon.repl/entries]}
  []
  [{:seon.repl/comment
    "; My namespace is empty — this function will be its first resident."
    :seon.repl/form
    '(defn largest [rows]
       (or (last (sort-by :example/amount rows)) {}))}
   {:seon.repl/comment
    (str "; Works. But without a :malli/schema it stays my scratch — "
         "nobody else can rely on it.")
    :seon.repl/form
    '(defn ^{:malli/schema
             [:=>
              [:cat [:sequential
                     [:map [:example/label :string]
                      [:example/amount :int]]]]
              [:map [:example/label {:optional true} :string]
               [:example/amount {:optional true} :int]]]}
       largest
       [rows]
       (or (last (sort-by :example/amount rows)) {}))}
   {:seon.repl/comment
    "; Is the contract actually enforced? Try to break it once."
    :seon.repl/form '(largest :not-a-row-sequence)}
   {:seon.repl/comment
    (str "; Good — a wrong call is an error value, not a crash. Now pin "
         "the behavior with a test others will find as my usage example.")
    :seon.repl/form
    '(clojure.test/deftest largest-usage
       (clojure.test/is
        (= {:example/label "b" :example/amount 9}
           (largest [{:example/label "a" :example/amount 3}
                     {:example/label "b" :example/amount 9}])))
       (clojure.test/is (= {} (largest []))))}
   {:seon.repl/comment
    "; Defined, contracted, proven. Report back and close this run."
    :seon.repl/form
    '(my.turn/complete
      {:my.turn/result (str "Built largest: a contracted function returning the row with "
           "the greatest :example/amount, or {} for empty input; its usage "
           "test is green.")})}])

(defn usage-form
  "Render my.turn's listing followed by its canonical usage walkthrough.

  The usage declaration is the executable teaching source. Rendering the
  namespace refuses loudly when the indexed usage test is absent, so the
  generated opening cannot silently retain a hand-copied demonstration after
  its recurring anti-rot gate disappears."
  {:malli/schema
   [:=> [:cat [:or :my.turn/namespace-unit :my.turn/usage-unit]]
    :seon.render/form]}
  [unit]
  (let [database (:seon.db/db unit)
        usage-test
        (when database
          (db/q '[:find ?test-symbol .
                 :where
                 [?test :seon.test/usage true]
                 [?test :seon.test/sym ?test-symbol]
                 [?test :seon.fn/calls seon.run/walkthrough]]
               database))]
    (when (and database (nil? usage-test))
      (throw
       (ex-info "my.turn has no declared usage walkthrough."
                {:my.turn/usage-walkthrough-absent true
                 :seon.error/message
                 "my.turn has no declared usage walkthrough."
                 :seon.ns/name 'my.turn})))
    (into [{:seon.repl/form '(dir 'my.turn)}] (walkthrough))))

;;; ---------------------------------------------------------------------------
;;; The two dispositions
;;; ---------------------------------------------------------------------------

(defn wait
  "Finish this run without a reply and record what you await.

  Takes a non-blank continuation note and returns a wait disposition or a flat
  error. Use `wait` when this run cannot finish until a named event or reply;
  include everything the later run will need in the note."
  {:malli/schema [:=> [:cat :my.turn/note]
                  [:or :my.turn/wait :seon.error/value]]}
  [note]
  (if (or (not (string? note)) (str/blank? note))
    (seon.error.refusal/diagnostic (java.util.Date.) :my.turn/disposition 'seon.run/wait
     {:my.turn/blank-note true
     :seon.error/message
     "wait needs a note saying what you are waiting for, as a string."})
    {:my.turn/disposition :wait
     :my.turn/note note}))

(defn complete
  "Finish this run with a reply for its requester.

  Takes non-blank reply text and returns a completed disposition or a flat
  error. Use `complete` when the requested work is finished and this text is
  the real reply its requester should receive."
  {:malli/schema [:=> [:cat :my.turn/result]
                  [:or :my.turn/completed :seon.error/value]]}
  [result]
  ; agent-facing: a wrong TYPE is an agent mistake too — the error
  ; value answers, str/blank? on a non-string would throw
  (if (or (not (string? result)) (str/blank? result))
    (seon.error.refusal/diagnostic (java.util.Date.) :my.turn/disposition 'seon.run/complete
     {:my.turn/blank-result true
     :seon.error/message
     "complete needs the reply text you want delivered, as a string."})
    {:my.turn/disposition :completed
     :my.turn/result result}))

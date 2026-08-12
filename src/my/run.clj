(ns my.run
  "The lifecycle protocol for every run.

  Every run ends by calling `complete` or `wait`. An undisposed run is
  unfinished work: it has neither answered its requester nor recorded what
  must happen before work can continue."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn render-namespace-ai
  "Present my.run as the lifecycle protocol, in use order."
  {:malli/schema [:=> [:cat :my.run/namespace-unit] :string]}
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
                 ["my.run/complete" "my.run/wait"])))]
    (str (:seon.ns/doc unit)
         "\n\n1. complete — "
         (or (get docs "my.run/complete")
             "Finish completed work with a reply for its requester.")
         "\n\n2. wait — "
         (or (get docs "my.run/wait")
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
    '(clojure.test/deftest ^{:seon.test/usage true} largest-usage
       (clojure.test/is
        (= {:example/label "b" :example/amount 9}
           (largest [{:example/label "a" :example/amount 3}
                     {:example/label "b" :example/amount 9}])))
       (clojure.test/is (= {} (largest []))))}
   {:seon.repl/comment
    "; Defined, contracted, proven. Report back and close this run."
    :seon.repl/form
    '(my.run/complete
      (str "Built largest: a contracted function returning the row with "
           "the greatest :example/amount, or {} for empty input; its usage "
           "test is green."))}])

(defn usage-form
  "Render my.run's listing followed by its canonical usage walkthrough.

  The usage declaration is the executable teaching source. Rendering the
  namespace refuses loudly when the indexed usage test is absent, so the
  generated opening cannot silently retain a hand-copied demonstration after
  its recurring anti-rot gate disappears."
  {:malli/schema
   [:=> [:cat [:or :my.run/namespace-unit :my.run/usage-unit]]
    :seon.repl/entries]}
  [unit]
  (let [database (:seon.db/db unit)
        usage-test
        (when database
          (db/q '[:find ?test-symbol .
                 :where
                 [?test :seon.test/usage true]
                 [?test :seon.test/sym ?test-symbol]
                 [?test :seon.fn/calls ?function]
                 [?function :seon.fn/sym "my.run/walkthrough"]]
               database))]
    (when (and database (nil? usage-test))
      (throw
       (ex-info "my.run has no declared usage walkthrough."
                {:seon.error/kind ::usage-walkthrough-absent
                 :seon.ns/name 'my.run})))
    (into [{:seon.repl/form '(dir 'my.run)}] (walkthrough))))

;;; ---------------------------------------------------------------------------
;;; The two dispositions
;;; ---------------------------------------------------------------------------

(defn wait
  "Finish this run without a reply and record what you await.

  Takes a non-blank continuation note and returns a wait disposition or a flat
  error. Use `wait` when this run cannot finish until a named event or reply;
  include everything the later run will need in the note."
  {:malli/schema [:=> [:cat :my.run/note]
                  [:or :my.run/wait :seon.error/value]]}
  [note]
  (if (or (not (string? note)) (str/blank? note))
    {:seon.error/kind ::blank-note
     :seon.error/message
     "wait needs a note saying what you are waiting for, as a string."}
    {:my.run/disposition :wait
     :my.run/note note}))

(defn complete
  "Finish this run with a reply for its requester.

  Takes non-blank reply text and returns a completed disposition or a flat
  error. Use `complete` when the requested work is finished and this text is
  the real reply its requester should receive."
  {:malli/schema [:=> [:cat :my.run/result]
                  [:or :my.run/completed :seon.error/value]]}
  [result]
  ; agent-facing: a wrong TYPE is an agent mistake too — the error
  ; value answers, str/blank? on a non-string would throw
  (if (or (not (string? result)) (str/blank? result))
    {:seon.error/kind ::blank-result
     :seon.error/message
     "complete needs the reply text you want delivered, as a string."}
    {:my.run/disposition :completed
     :my.run/result result}))

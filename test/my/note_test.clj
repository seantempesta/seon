(ns my.note-test
  "The minimal durable current-note home and its rebirth property."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [my.note :as note]
            [seon.db :as db]
            [seon.schema]
            [seon.test-support :as support]))

(defn- with-notes
  [f]
  (support/with-database
    (fn [connection]
      (db/transact! connection
                    [{:seon.cluster.agent/id "alice"}
                     {:seon.cluster.agent/id "bob"}
                     {:seon.cluster.message/id "subject-1"}])
      (f connection))))

(deftest add-upserts-current-content-by-identity
  (with-notes
    (fn [connection]
      (is (= {:my.note/id "design"
              :my.note/agent
              (db/q '[:find ?agent .
                      :where [?agent :seon.cluster.agent/id "alice"]]
                    @connection)
              :my.note/content "Prefer one current fact."
              :my.note/about
              (db/q '[:find ?subject .
                      :where [?subject :seon.cluster.message/id "subject-1"]]
                    @connection)}
             (note/add! "design" "Prefer one current fact."
                        [:seon.cluster.message/id "subject-1"]
                        connection "alice")))
      (is (= "Prefer current facts only."
             (:my.note/content
              (note/add! "design" "Prefer current facts only."
                         connection "alice"))))
      (is (= 1
             (db/q '[:find (count ?note) .
                     :where [?note :my.note/id "design"]]
                   @connection)))
      (is (= "Prefer current facts only."
             (db/q '[:find ?content .
                     :where
                     [?note :my.note/id "design"]
                     [?note :my.note/content ?content]]
                   @connection)))
      (is (seon.schema/valid-candidate-value?
           :my.note/note
           (first (note/notes @connection "alice")))))))

(deftest forget-retracts-current-entity-and-history-keeps-content
  (with-notes
    (fn [connection]
      (note/add! "temporary" "Remember until done." connection "alice")
      (is (= "temporary"
             (note/forget! "temporary" connection "alice")))
      (is (nil? (db/pull @connection '[*] [:my.note/id "temporary"])))
      (is (= ["Remember until done."]
             (db/q '[:find [?content ...]
                     :where
                     [?note :my.note/id "temporary"]
                     [?note :my.note/content ?content]]
                   (db/history @connection))))
      (let [missing (note/forget! "temporary" connection "alice")]
        (is (= :my.note/not-found (:seon.error/kind missing)))
        (is (seon.schema/valid-candidate-value?
             :seon.error/value missing))))))

(deftest notes-is-agent-scoped-current-and-whole
  (with-notes
    (fn [connection]
      (doseq [index (range 55)]
        (note/add! (format "note-%02d" index)
                   (str "content " index)
                   connection "alice"))
      (note/add! "bob-only" "Bob's note." connection "bob")
      (let [current (note/notes @connection "alice")]
        (is (= 55 (count current)))
        (is (= "note-00" (:my.note/id (first current))))
        (is (= "note-54" (:my.note/id (last current))))
        (is (not-any? #(= "bob-only" (:my.note/id %)) current))
        (is (seon.schema/valid-candidate-value? :my.note/notes current))))))

(deftest rebirth-renders-only-the-current-facts
  (with-notes
    (fn [connection]
      (note/add! "kept" "Old content." connection "alice")
      (note/add! "kept" "Current content." connection "alice")
      (note/add! "forgotten" "Must disappear." connection "alice")
      (note/forget! "forgotten" connection "alice")
      (let [current (note/notes @connection "alice")
            ai (note/render-notes-ai current)
            html (note/render-notes-html current)]
        (testing "the collection is rebuilt from current facts alone"
          (is (= ["kept"] (mapv :my.note/id current)))
          (is (str/includes? ai "Current content."))
          (is (not (str/includes? ai "Old content.")))
          (is (not (str/includes? ai "Must disappear."))))
        (testing "all three declared projections accept the collection"
          (is (= '(my.note/notes) (note/render-notes-form current)))
          (is (= :section (first html)))
          (is (seon.schema/valid-candidate-value? :seon.render/ai ai))
          (is (seon.schema/valid-candidate-value?
               :seon.render/hiccup html)))))))

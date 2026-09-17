(ns my.program-query-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(deftest predicate-arguments-are-bindings-not-nested-clojure-evaluations
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           pulled (db/pull database [:seon.fn/sym]
                           [:seon.fn/sym "my.note/forget!"])
           nested (db/q database
                        '[:find [?s ...] :where
                          [?f :seon.fn/sym ?s]
                          [(clojure.string/starts-with? (str ?s) "my.")]])
           bound (db/q database
                       '[:find [?s ...] :where
                         [?f :seon.fn/sym ?s]
                         [(str ?s) ?text]
                         [(clojure.string/starts-with? ?text "my.")]])
           direct (db/q database
                        '[:find [?s ...] :where
                          [?f :seon.fn/sym ?s]
                          [(clojure.string/starts-with? ?s "my.")]])]
       (is (= "my.note/forget!" (:seon.fn/sym pulled)))
       (is (= [] nested))
       (is (seq bound))
       (is (= (set direct) (set bound)))
       (is (some #{(:seon.fn/sym pulled)} bound))
       (println (pr-str {:seon.program.probe/pulled pulled
                        :seon.program.probe/nested nested
                        :seon.program.probe/bound-count (count bound)
                        :seon.program.probe/direct-count (count direct)}))))))

(deftest scalar-attribute-bindings-preserve-the-renderer-value-type
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           schema-key :seon.program/breakage
           pulled (db/pull database [:seon.render/ai] [:seon.schema/key schema-key])
           scalar (db/q database
                        '[:find ?renderer . :in $ ?key ?property :where
                          [?schema :seon.schema/key ?key]
                          [?schema ?property ?renderer]]
                        schema-key :seon.render/ai)
           literal (db/q database
                         '[:find ?renderer . :in $ ?key :where
                           [?schema :seon.schema/key ?key]
                           [?schema :seon.render/ai ?renderer]] schema-key)
           collection (db/q database
                            '[:find ?property ?renderer
                              :in $ ?key [?property ...] :where
                              [?schema :seon.schema/key ?key]
                              [?schema ?property ?renderer]]
                            schema-key [:seon.render/ai])]
       (is (= 'seon.render.value/render-ai (:seon.render/ai pulled)))
       (is (= (:seon.render/ai pulled) scalar literal))
       (println (pr-str {:seon.program.probe/pulled pulled
                        :seon.program.probe/scalar scalar
                        :seon.program.probe/literal literal
                        :seon.program.probe/collection collection}))))))

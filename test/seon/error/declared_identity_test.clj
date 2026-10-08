(ns seon.error.declared-identity-test
  "The producer declares an error's identity; settlement and recording read it
  (the value's `:seon.error/type`) and guess nothing
  from its members (agent-path root-cause audit M3/E6)."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(defn- declared-and-valid?
  "The value names its declared schema, and that schema accepts it."
  [projection expected value]
  (let [declared (:seon.error/type value)]
    (and (= expected declared)
         ((schema/projection-validator projection declared) value))))

(deftest ^{:seon/tasks #{'seon.error/s1-left-metadata-readers-and-stamps-in-three-test-files 'seon.turn/refusal-schemas-are-guessed-by-key}}
  producers-declare-the-identity-settlement-records
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           projection (db/carried-projection database)
           read-refusal (db/q '[:find ?e . :where [?e :no.such/declared-identity-probe _]] database)
           ambiguous (#'render/ambiguity 'declared-identity.fixture :seon.render/ai
                                         ['declared-identity.fixture/a 'declared-identity.fixture/b])]
       (is (declared-and-valid? projection :seon.db/invalid-read-error read-refusal))
       (is (declared-and-valid? projection :seon.render/ambiguous-error ambiguous))
       (is (= :seon.render/request-error
              (:seon.error/type (render/request-profile {:seon.render/profile read-refusal})))
           "a relabelling producer declares the identity it relabels to")))))

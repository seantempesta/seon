(ns seon.blob-error-test
  (:require [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest publication-preserves-a-refused-root-transaction
  (support/with-database
   (fn [connection]
     (let [refusal {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.turn/transition
                    :seon.error/operation 'seon.turn/close-call
                    :seon.error/message "The turn is not open."
                    :seon.turn/rule :seon.turn/not-open}
           result (blob/with-publication! connection [] (fn [] refusal))]
       (is (identical? refusal result))
       (is ((schema/projection-validator (schema/handed-projection)
                                        :seon.turn/refused-error) result))))))

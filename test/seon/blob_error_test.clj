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

(deftest stalled-input-keeps-the-complete-producer-observation
  (support/with-database
   (fn [connection]
     (with-open [input (proxy [java.io.InputStream] []
                        (read ([] 0) ([buffer] 0) ([buffer offset length] 0)))]
       (let [refusal (support/refusal-data #(blob/stage-binary! connection input))]
         (is (inst? (:seon.error/at refusal)))
         (is (= {:seon.error/at (:seon.error/at refusal)
                 :seon.error/layer :seon.blob/storage
                 :seon.error/operation 'seon.blob/stage-binary!
                 :seon.error/offending 0 :seon.blob/input-stalled 0
                 :seon.error/message "Blob input stream made no progress."
                 :seon.blob/size 0 :seon.error/member :seon.blob/input-stream
                 :seon.error/expected "a positive read or EOF"
                 :seon.error/data {:seon.blob/size 0}}
                refusal))
         (is ((schema/projection-validator (schema/handed-projection)
                                           :seon.blob/input-stalled-error) refusal)))))))

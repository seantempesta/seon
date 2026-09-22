(ns seon.test.gitlink-digest-test
  "A gitlink input carries the declared file-digest shape into publication."
  (:require [clojure.test :refer [deftest is]]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [seon.test.cache :as cache]))

(deftest every-gitlink-input-digest-has-the-declared-file-digest-shape
  (let [pins (cache/gitlink-digests (System/getProperty "user.dir"))
        inputs (cache/input-digests (System/getProperty "user.dir"))
        form (get (schema.edn/packaged-forms) :seon.fn.file/digest)
        valid? (m/validator form)]
    (is (seq pins) "the checkout has submodule gitlinks to digest")
    (is (every? #(contains? inputs %) (keys pins))
        "each gitlink is a publication input")
    (is (every? valid? (vals pins))
        (str "each gitlink digest satisfies :seon.fn.file/digest " (pr-str form)))
    (is (every? valid? (map inputs (keys pins))))))

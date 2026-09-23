(ns seon.fn.caller-lint-test
  "Caller files are relinted only when a callee's signature changes (B1 §2a step 9)."
  (:require [clojure.test :refer [deftest is]]
            [seon.fn]
            [seon.test-support :as support]))

(deftest only-a-signature-change-selects-caller-files
  ;; This body's own branch of the canonical fixture; `seon.id/digest` has
  ;; callers in many files.
  (let [connection (:seon.db/connection (support/execution-handle nil))
        callee [:seon.fn/sym (symbol "seon.id" "digest")]
        callers-after #(seon.fn/caller-files (support/transacted! connection [(assoc % :db/id callee)]))]
    (is (= #{} (callers-after {:seon.fn/doc "A changed docstring."}))
        "a docstring edit relints no caller")
    (is (= #{} (callers-after {:seon.fn/source "(defn digest [length parts] :changed-body)"}))
        "a body edit relints no caller")
    (is (seq (callers-after {:seon.fn/inline? true}))
        "an inline-status change relints the direct callers")))

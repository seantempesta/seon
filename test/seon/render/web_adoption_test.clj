(ns seon.render.web-adoption-test
  "A listening server follows a redefined handler without rebinding."
  (:require [clojure.test :refer [deftest is]]
            [seon.render.web :as web]
            [seon.render.web-test :as web-test]
            [seon.test-support :as support]))

(deftest listening-server-follows-the-handler-var
  (support/preserving-instrumentation-state
   (fn []
     (#'web-test/with-server
      (fn [_ server _]
        (let [original web/handler
              calls (atom 0)]
          (is (= 200 (.statusCode (#'web-test/fetch server "/css/input.css"))))
          (with-redefs [web/handler
                        (fn [service]
                          (swap! calls inc)
                          (original service))]
            (is (= 200 (.statusCode (#'web-test/fetch server "/css/input.css"))))
            (is (= 1 @calls)
                "The already-listening socket invokes the current handler Var."))
          (is (= 200 (.statusCode (#'web-test/fetch server "/css/input.css"))))))))))

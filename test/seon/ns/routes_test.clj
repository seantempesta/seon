(ns seon.ns.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.ns.routes :as routes]))

;;; ---------------------------------------------------------------------------
;;; Form Data Round Trip Tests
;;;
;;; These tests exercise the pure plumbing in seon.ns.routes — form-body
;;; parsing of qualified-keyword field names, and 404 handling when the
;;; target namespace/function does not resolve. They use a synthetic
;;; placeholder namespace (`seon.test.placeholder`) since the routes layer
;;; treats `:namespace`/`:function` path params as opaque symbols until
;;; they are passed to `actions/resolve-action`.
;;; ---------------------------------------------------------------------------

(deftest form-data-round-trip-test
  (testing "qualified keywords survive form POST round trip"
    ;; Simulates what Datastar sends with contentType:'form'
    ;; Form field name=":seon.test.placeholder/exercise" value="Pull-up"
    ;; arrives as URL-encoded: %3Aseon.test.placeholder%2Fexercise=Pull-up
    ;; routes/parse-form-body decodes to {:seon.test.placeholder/exercise "Pull-up"}
    (let [form-body ":seon.test.placeholder/exercise=Pull-up&:seon.test.placeholder/sets=4"]
      (is (= {:seon.test.placeholder/exercise "Pull-up"
              :seon.test.placeholder/sets "4"}
             (#'routes/parse-form-body form-body))))))

;;; ---------------------------------------------------------------------------
;;; Validation Tests
;;; ---------------------------------------------------------------------------

(deftest validation-rejects-bad-input-test
  (testing "function not found returns 404"
    (let [request {:request-method :post
                   :uri "/ns/seon.test.placeholder/nonexistent-fn!"
                   :path-params {:namespace "seon.test.placeholder"
                                 :function "nonexistent-fn!"}
                   :query-string ""
                   :body ""}
          response (routes/function-call-handler request)]
      (is (= 404 (:status response))))))

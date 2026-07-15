(ns seon.web.serve-test
  "The pod HTTP dispatch — the CSRF / same-origin guard on state-changing POSTs.

   Loopback BINDING is not a security boundary: a page on any site the human
   visits can `no-cors` POST to 127.0.0.1. The browser attaches an `Origin`
   header on such cross-site requests, so the dispatch refuses any POST whose
   Origin is present and NOT loopback. Absent Origin (curl / the agent /
   non-browser) is allowed; the pod's own loopback UI is allowed."
  (:require
    [cljs.test :refer [deftest is testing]]
    [goog.object :as gobj]
    [seon.agent.run :as run]
    [seon.runtime.admission :as admission]
    [seon.web.serve :as serve]))

(deftest agent-run-timeout-uses-explicit-value-or-run-policy
  (with-redefs [run/effective-deadline-ms
                (fn [request]
                  (is (= {:seon.db/db :frozen-db
                          :seon.agent/id "agent-1"}
                         request))
                  1800000)]
    (is (= 9000
           ((deref #'serve/agent-run-timeout-ms)
            :frozen-db "agent-1" 9000))
        "an explicit Inspect timeout remains an explicit experiment input")
    (is (= 1800000
           ((deref #'serve/agent-run-timeout-ms)
            :frozen-db "agent-1" nil))
        "absence derives from the database-backed run owner")))

(deftest eval-evidence-is-request-scoped-and-stably-ordered
  (let [first-at (js/Date. 1000)
        second-at (js/Date. 2000)
        outside-at (js/Date. 3000)
        pulls {100 {:seon.eval/source "(first)"
                    :seon.eval/ok? true}
               101 {:seon.eval/source "(second)"
                    :seon.eval/ok? false
                    :seon.eval/narration "kept as bounded data"}
               102 {:seon.eval/source "(outside)"
                    :seon.eval/ok? true}}]
    (is (= [{:eval_id "eval-a"
             :at "1970-01-01T00:00:01.000Z"
             :ok true
             :source "(first)"}
            {:eval_id "eval-b"
             :at "1970-01-01T00:00:02.000Z"
             :ok false
             :source "(second)"
             :narration "kept as bounded data"}]
           ((deref #'serve/project-eval-evidence)
             [[102 12 "eval-c" outside-at]
              [101 11 "eval-b" second-at]
              [100 10 "eval-a" first-at]]
             #{10 11}
             #(get pulls %))))))

(defn- req-with-origin
  ([origin] (req-with-origin origin nil))
  ([origin host]
   (let [h #js {}]
     (when origin (gobj/set h "origin" origin))
     (when host   (gobj/set h "host" host))
     #js {:headers h})))

(deftest same-origin-allows-loopback-and-absent-refuses-cross-site
  (testing "absent Origin (curl / the agent / non-browser) is allowed"
    (is (true? (serve/same-origin? (req-with-origin nil)))))
  (testing "the pod's own loopback UI origins are allowed (no Host → loopback fallback)"
    (is (true? (serve/same-origin? (req-with-origin "http://127.0.0.1:7890"))))
    (is (true? (serve/same-origin? (req-with-origin "http://localhost:7890"))))
    (is (true? (serve/same-origin? (req-with-origin "http://[::1]:7890")))))
  (testing "a genuine same-origin request behind a non-loopback front is allowed (Host matches)"
    (is (true? (serve/same-origin? (req-with-origin "https://pod.example" "pod.example")))))
  (testing "a cross-site Origin (any internet page the human visits) is refused"
    (is (false? (serve/same-origin? (req-with-origin "https://evil.example.com"))))
    (is (false? (serve/same-origin? (req-with-origin "http://attacker.test:80"))))
    (testing "even when a Host header is present but does NOT match the Origin"
      (is (false? (serve/same-origin? (req-with-origin "https://evil.example.com" "127.0.0.1:7890")))))))

(defn- readiness-response
  "Status and body written by the live readiness handler."
  []
  (let [!response (atom {})
        response #js {:writeHead
                      (fn [status _headers]
                        (swap! !response assoc ::status status))
                      :end
                      (fn [body]
                        (swap! !response assoc ::body body))}]
    ((deref #'serve/handle-readiness!) nil response)
    @!response))

(deftest readiness-tracks-admission-after-startup
  (let [prior (admission/state)]
    (try
      (reset! @#'admission/!state
              {::admission/status :available
               ::admission/generation 17})
      (is (= 200 (::status (readiness-response))))
      (reset! @#'admission/!state
              {::admission/status :unavailable
               ::admission/generation 17
               ::admission/reason "injected publication failure"})
      (let [response (readiness-response)]
        (is (= 503 (::status response)))
        (is (re-find #":seon.runtime.admission/status :unavailable"
                     (::body response))))
      (let [!response (atom {})
            response #js {:writeHead
                          (fn [status _headers]
                            (swap! !response assoc ::status status))
                          :end
                          (fn [body]
                            (swap! !response assoc ::body body))}]
        (serve/create-agent!
          {:seon.http/node-req nil
           :seon.http/node-res response})
        (is (= 503 (::status @!response)))
        (is (re-find #"Runtime program generation is unavailable"
                     (::body @!response))
            "nil request proves refusal occurred before body parsing"))
      (finally
        (reset! @#'admission/!state prior)))))

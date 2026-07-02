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
    [seon.web.serve :as serve]))

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

(ns seon.web-test
  "Root's web proc (lane W2) as a plain function: a UI call becomes root's requests (and,
  for root's functions, its record on main); a patch is framed once for Datastar and offered to
  every tab of its view; a tab that opened gets the view's keyframe; a callback written as
  data serializes to the stateless /call route. Run: bin/test seon.web-test"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.core.async :as a]
            [seon.web :as w] [seon.web.html :as h] [seon.render.proc :as p] [seon.contract]))

(def ^:private index {:a1 {:pid 11 :branch :agent-1 :status :idle} :a2 {:pid 12 :branch :agent-2 :status :killed}
                      :a3 {:pid 13 :branch :agent-3 :status :busy}})

(deftest calls-become-requests
  (testing "the REPL box: an eval in that agent, origin :ui"
    (is (= [[{:op :eval :id :a1 :forms "(+ 1 2)" :origin :ui} :a1]]
           (w/requests {:var 'seon.root/submit! :agent :a1 :forms "(+ 1 2)"} index))))
  (testing "all=>: one eval per live agent, the ended ones left out"
    (is (= [:a1 :a3] (mapv second (w/requests {:var 'seon.root/submit! :agent :all :forms "1"} index)))))
  (testing "merge!: gated by the agent's tests, on its branch; no deadline (the child's exit ends a run)"
    (is (= {:op :merge-tests :id :a1 :merge-branch :agent-1 :data-attrs :all}
           (ffirst (w/requests {:var 'seon.root/merge! :agent :a1} index)))))
  (testing "merge! of an ended agent: root refuses it (seon.root/request, the API fn's own table), so no request"
    (is (= [] (w/requests {:var 'seon.root/merge! :agent :a2} index))))
  (testing "interrupt!: an effect this proc performs, no request"
    (is (= [] (w/requests {:var 'seon.root/interrupt! :agent :a1} index))))
  (testing "run-tests!: the agent's tests, with no deadline"
    (is (= [[{:op :tests :id :a1} :a1]] (w/requests {:var 'seon.root/run-tests! :agent :a1} index))))
  (testing "spawn n with the form's task"
    (let [rs (w/requests {:var 'seon.root/spawn! :agent :root :args {:n 3 :task "x"}} index)]
      (is (= 3 (count rs)))
      (is (every? #(= {:op :spawn :opts {:task "x"}} (first %)) rs))))
  (testing "an agent's own fn: evaluated there through the allowlist check"
    (is (= "(seon.web/ui-call (quote app.x/f) {:k 1})"
           (:forms (ffirst (w/requests {:var 'app.x/f :agent :a1 :args {:k 1}} index)))))))

(defn ^:seon/callable callable-probe [args] (assoc args :ran true))
(defn not-callable-probe [args] args)

(deftest ui-call-allowlist
  (is (= {:k 1 :ran true} (w/ui-call `callable-probe {:k 1})))
  (is (= :seon.render/not-callable (:seon.error/type (w/ui-call `not-callable-probe {}))))
  (is (= :seon.render/not-callable (:seon.error/type (w/ui-call 'no.such/fn {})))))

(deftest callbacks-are-data
  (is (= "<button data-on:click=\"@post('/call?fn=seon.root%2Fkill%21&amp;agent=a1')\">x</button>"
         (h/html [:button {:seon/call 'seon.root/kill! :seon/agent :a1} "x"])))
  (is (str/includes? (h/html [:form {:seon/call 'app.x/f :seon/args {:k 1}} [:input {:name "v"}]])
                     "contentType:'form'")))

(deftest framing
  (is (= "event: datastar-patch-elements\ndata: elements <p id=\"x\">a</p>\n\n" (w/patch-event "<p id=\"x\">a</p>")))
  (is (= (str "event: datastar-patch-elements\ndata: elements <i id=\"a\"></i>\n\n"
              "event: datastar-patch-elements\ndata: selector #grid\ndata: mode append\ndata: elements <b></b>\n\n"
              "event: datastar-patch-elements\ndata: selector #card-a2\ndata: mode remove\n\n")
         (w/chunk-of {:view :root :frags [["a" "<i id=\"a\"></i>"]] :appends [["#grid" "<b></b>"]] :removes ["card-a2"]}))))

(deftest tabs
  (let [st (#'w/web-proc {:port 0})
        root-tab (a/chan 8) zoom-tab (a/chan 8)
        [st out] (#'w/web-proc st :http {:op :open :view :root :ch root-tab})
        [st _] (#'w/web-proc st :http {:op :open :view :a1 :ch zoom-tab})]
    (testing "an opened tab asks render for its view's keyframe, and the views watched"
      (is (some #(= {:op :keyframe :view :root} %) (:to-render out)))
      (is (some #(= :views (:op %)) (:to-render out))))
    (testing "a stale tab gets the keyframe, the other view's tab nothing"
      (let [[st _] (#'w/web-proc st :from-render {:op :keyframe :view :root :html "<html><body><div id=\"app\">k</div></body></html>"})]
        (is (str/includes? (a/poll! root-tab) "data: elements <div id=\"app\">k</div>"))
        (is (nil? (a/poll! zoom-tab)))
        (testing "then a patch of its view, serialized once, offered to it alone"
          (let [[st _] (#'w/web-proc st :from-render {:op :patch :view :root :frags [["t" "<i id=\"t\"></i>"]] :appends [] :removes []})]
            (is (str/includes? (a/poll! root-tab) "<i id=\"t\"></i>"))
            (is (nil? (a/poll! zoom-tab)))
            (is (= 1 (:patches st)))))))))

(deftest a-root-function-is-recorded-with-its-answer
  ;; task seon.render/one-owner-per-view-path (B9): root's function called from the page is
  ;; recorded on main with what root answered, when it answers; never a made-up "requested"
  (let [st (#'w/web-proc {:port 0})
        [_ out] (#'w/web-proc (assoc st :index index) :http {:op :call :call {:var 'seon.root/kill! :agent :a1}})
        [req] (:to-api out)]
    (is (= 1 (count (:to-api out))) "the function's request only: nothing is recorded before root answers")
    (is (not (str/includes? (pr-str out) "requested")))
    (let [rp {:rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events [] :event-rows [] :views {} :zoom {} :merges 0}
          [_ o] (#'p/render-proc rp :answers {:tag (:tag req) :r {:ok true :killed :a1}})
          w (first (filter #(= :write (get-in % [:req :op])) (:to-api o)))
          rec (some :seon.cluster.eval/record (get-in w [:req :req :tx-data]))]
      (is (= "(seon.root/kill! :a1)" (:form rec)))
      (is (= :ui (:origin rec)))
      (is (str/includes? (str (:shown rec)) ":killed :a1") "what root answered"))))

(deftest a-listener-that-cannot-bind-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): a port taken is data, the one error map
  (let [taken (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (try
      (let [st (#'w/web-proc {:port (.getLocalPort taken)})]
        (is (false? (:listening st)))
        (is (seon.contract/error? (:error st)) (pr-str (:error st))))
      (finally (.close taken)))))

(ns seon.web-test
  "Root's web proc (session W2) as a plain function: a UI call becomes root's requests (and,
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
  (testing "merge!: no such request; a branch reaches main only through a merge task (the reconciler's octopus merge), so root refuses it (seon.root/request, the API fn's own table): its refused answer, for the asker"
    (let [[[r id] & more] (w/requests {:var 'seon.root/merge! :agent :a1} index)]
      (is (= :seon.root/refused (:seon.error/type r)))
      (is (= :a1 id))
      (is (nil? more))))
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
      (let [[st _] (#'w/web-proc st :from-render {:op :keyframe :view :root :html "<div id=\"app\">k</div>"})]
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
    (let [rp {:rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :zoom {} :merges 0}
          [_ o] (#'p/render-proc rp :answers {:tag (:tag req) :r {:ok true :killed :a1}})
          w (first (filter #(= :write (get-in % [:req :op])) (:to-api o)))
          e (some #(when (:seon.eval/record %) %) (get-in w [:req :req :tx-data]))
          rec (:seon.eval/record e)]
      (is (= "(seon.root/kill! :a1)" (:code rec)))
      (is (= "user" (:seon.eval/by e)))
      (is (str/includes? (seon.print/record-text rec) ":killed :a1") "what root answered"))))

(deftest a-refused-ui-call-reaches-the-stream-and-main
  ;; root refuses a call of one of its fns that is none of its requests, before any request: the refused
  ;; answer goes to the render proc as the call's answer (shown, and recorded on main as an :err item), not dropped
  (let [st (#'w/web-proc {:port 0})
        [_ out] (#'w/web-proc (assoc st :index index) :http {:op :call :call {:var 'seon.root/nope! :agent :a2}})
        [m] (:to-render out)]
    (is (empty? (:to-api out)) "no request: root refused it")
    (is (= :refused (:op m)))
    (is (= :seon.root/refused (:seon.error/type (:r m))))
    (let [rp {:rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :zoom {} :merges 0}
          [_ o] (#'p/render-proc rp :from-web m)
          w (first (filter #(= :write (get-in % [:req :op])) (:to-api o)))
          rec (some :seon.eval/record (get-in w [:req :req :tx-data]))]
      (is (= "(seon.root/nope! :a2)" (:code rec)))
      (is (str/includes? (seon.print/record-text rec) "not one of root's requests") "what root refused, and why"))))

(deftest a-listener-that-cannot-bind-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): a port taken is data, the one error map
  (let [taken (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (try
      (let [st (#'w/web-proc {:port (.getLocalPort taken)})]
        (is (false? (:listening st)))
        (is (seon.contract/error? (:error st)) (pr-str (:error st))))
      (finally (.close taken)))))

(deftest a-vector-headed-by-a-function-is-a-call
  ;; Reagent's call form: [f value opts] writes what (f value opts) answers, expanded the same
  ;; way; a keyword head stays an element (Jolt answers fn? true for a keyword)
  (let [row (fn [v opts] [:li {:class (name (:as opts :block))} (str v) (when (pos? v) [(fn [w _] [:b (str "inner " w)]) (dec v) {}])])]
    (is (= "<ul><li class=\"inline\">1<b>inner 0</b></li></ul>" (seon.web.html/html [:ul [row 1 {:as :inline}]])))
    (is (= "<a href=\"/\">x</a>" (seon.web.html/html [:a {:href "/"} "x"])) "a keyword head is an element")))

(deftest an-unread-cut-form-never-reaches-the-render-proc
  ;; seon.web/open-form, the edge: the browser's text after a row's id reads as EDN or is refused
  (let [open-form @#'seon.web/open-form]
    (is (nil? (open-form "c12-chat")) "a row's own toggle names no form")
    (is (= '(drop 1 (get-in result/e5 [:value :proofs])) (open-form "c12-chat/(drop 1 (get-in result/e5 [:value :proofs]))")) "a form's symbols hold a slash")
    (is (= :seon.web/unread (open-form "c12-chat/(drop 1")) "unreadable text")))

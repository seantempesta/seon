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

;; docs/render.md#a-part-prints-as-its-render: a printed part names its render with a symbol, and a value the page draws may hold what an
;; agent wrote, so a symbol head in data is drawn as data and resolves nothing; only a var or a function a render itself returned is a call
(def ^:private called? (atom false))
(defn ^:private witness [& _] (reset! called? true) [:b "called"])

(deftest a-symbol-in-stored-data-calls-nothing
  (reset! called? false)
  (let [out (h/html ['seon.web-test/witness "arg"])]
    (is (not @called?) "the function the symbol names did not run")
    (is (str/includes? out "arg") (str "its contents are drawn: " out))
    (is (not (str/includes? out "called")) out))
  (testing "a var head, which only a render's own code holds, is still a call"
    (is (str/includes? (h/html [#'witness]) "called"))
    (is @called?)))

(deftest calls-become-requests
  (testing "the REPL box: an eval in that agent, origin :ui"
    (is (= [[{:op :eval :id :a1 :forms "(+ 1 2)" :origin :ui} :a1]]
           (w/requests {:var 'seon.root/submit! :agent :a1 :forms "(+ 1 2)"} index))))
  (testing "all=>: one eval per live agent, the ended ones left out"
    (is (= [:a1 :a3] (mapv second (w/requests {:var 'seon.root/submit! :agent :all :forms "1"} index)))))
  (testing "interrupt!: an effect this proc performs, no request"
    (is (= [] (w/requests {:var 'seon.root/interrupt! :agent :a1} index))))
  (testing "run-tests!: the agent proves its branch through the test service, as root's judge does (task seon.proofs/the-dashboards-run-tests-asks-the-service)"
    (is (= [[{:op :judge :id :a1} :a1]] (w/requests {:var 'seon.root/run-tests! :agent :a1} index))))
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
              "event: datastar-patch-elements\ndata: selector [id=\"card-a2\"]\ndata: mode remove\n\n")
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
            (is (= 1 (:patches st)))
            (testing "a page sent whole by a pass (:whole: a patch it could not place) reaches every tab of its view, stale or not"
              (let [_ (#'w/web-proc st :from-render {:op :keyframe :view :root :html "<div id=\"app\">w</div>"})]
                (is (nil? (a/poll! root-tab)) "a keyframe asked for reaches only a stale tab"))
              (let [_ (#'w/web-proc st :from-render {:op :keyframe :whole true :view :root :html "<div id=\"app\">w</div>"})]
                (is (str/includes? (str (a/poll! root-tab)) "<div id=\"app\">w</div>"))
                (is (nil? (a/poll! zoom-tab)))))))))))

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
          e (some #(when (:seon.eval/response %) %) (get-in w [:req :req :tx-data]))]
      (is (= "(seon.root/kill! :a1)" (:seon.eval/code e)))
      (is (= "user" (:seon.eval/by e)))
      (is (str/includes? (seon.print/record-text (assoc e :seon.eval/response (seon.db/response-of nil e))) ":killed :a1") "what root answered"))))

(deftest an-eval-opened-on-a-page-is-no-eval-of-roots
  ;; the browser pass (3b-root): opening an agent's eval on its page called seon.root/opened!, and each
  ;; open was recorded as an eval in root's conversation. Opening is a reader's view of the eval,
  ;; written as the fact (:seon.agent/opened), never root's work
  (let [st (#'w/web-proc {:port 0})
        [_ out] (#'w/web-proc (assoc st :index index) :http {:op :call :call {:var 'seon.root/opened! :agent :a1 :args {:key "b1/a1" :eval "e9"}}})
        [req] (:to-api out)]
    (is (= :write (get-in req [:req :op])) (pr-str req))
    (let [rp {:rid 0 :pending {} :watch #{} :waiting {} :kf-wanted #{} :new-events [] :events [] :views {} :zoom {} :merges 0}
          [_ o] (#'p/render-proc rp :answers {:tag (:tag req) :r {:ok true}})]
      (is (not-any? #(some :seon.eval/response (get-in % [:req :req :tx-data])) (:to-api o)) "no eval recorded for root"))))

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
          e (some #(when (:seon.eval/response %) %) (get-in w [:req :req :tx-data]))]
      (is (= "(seon.root/nope! :a2)" (:seon.eval/code e)))
      (is (str/includes? (seon.print/record-text (assoc e :seon.eval/response (seon.db/response-of nil e))) "not one of root's requests") "what root refused, and why"))))

(deftest a-listener-that-cannot-bind-is-the-one-error-map
  ;; task seon.render/one-owner-per-view-path (B2): a port taken is data, the one error map
  (let [taken (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (try
      (let [st (#'w/web-proc {:port (.getLocalPort taken)})]
        (is (false? (:listening st)))
        (is (seon.contract/error? (:error st)) (pr-str (:error st))))
      (finally (.close taken)))))

(deftest a-vector-headed-by-a-function-is-a-call
  ;; Reagent's call form: [f opts value] writes what (f opts value) answers, expanded the same
  ;; way; a keyword head stays an element (Jolt answers fn? true for a keyword)
  (let [row (fn [opts v] [:li {:class (name (:as opts :block))} (str v) (when (pos? v) [(fn [_ w] [:b (str "inner " w)]) {} (dec v)])])]
    (is (= "<ul><li class=\"inline\">1<b>inner 0</b></li></ul>" (seon.web.html/html [:ul [row {:as :inline} 1]])))
    (is (= "<a href=\"/\">x</a>" (seon.web.html/html [:a {:href "/"} "x"])) "a keyword head is an element")))

(deftest an-unread-cut-form-never-reaches-the-render-proc
  ;; seon.web/open-form, the edge: the browser's text after a row's id reads as EDN or is refused
  (let [open-form @#'seon.web/open-form]
    (is (nil? (open-form "c12-chat")) "a row's own toggle names no form")
    (is (= '(drop 1 (get-in result/e5 [:value :proofs])) (open-form "c12-chat/(drop 1 (get-in result/e5 [:value :proofs]))")) "a form's symbols hold a slash")
    (is (= :seon.web/unread (open-form "c12-chat/(drop 1")) "unreadable text")))

(clojure.test/deftest a-cross-origin-call-is-refused
  ;; seon.web/handler's edge: a page of another origin is refused before the call reaches the proc; the dashboard's own page, and a client naming no origin, are served
  (let [ch (a/chan 8)
        handle (w/handler ch)
        post (fn [headers]
               (handle {:request-method :post :uri "/call" :query-string "fn=seon.root%2Fsubmit%21"
                        :headers headers :body (java.io.StringReader. "")}))
        own (str "http://127.0.0.1:" (seon.config/value :seon.config.web/port))]
    (doseq [headers [{"origin" "https://evil.example"} {"sec-fetch-site" "cross-site"}]]
      (clojure.test/is (= 403 (:status (post headers))) (str "refused: " (pr-str headers)))
      (clojure.test/is (nil? (a/poll! ch)) (str "and nothing sent: " (pr-str headers))))
    (doseq [headers [{} {"origin" own} {"sec-fetch-site" "same-origin"}]]
      (clojure.test/is (= 204 (:status (post headers))) (str "served: " (pr-str headers)))
      (clojure.test/is (= :call (:op (a/poll! ch))) (str "the call reached the proc: " (pr-str headers))))))

;; In-flight output (task seon.render/one-walk-per-view, rule 10; docs/render.md#in-flight-output-streams): an effect's
;; text held while its stream is open and appended by id in the tabs of its agent's chat that have its in-flight element,
;; a tab caught up with all the text held when the element is first drawn on it (or redrawn by a keyframe)
(def ^:private keyed
  "Root's agents index as the render proc sends it: each agent's stable key beside its per-boot id."
  (into {} (for [[id a] index] [id (assoc a :key (str "b1/" (name id)))])))

(def ^:private call-el
  "The in-flight element a1's call b1/a1/3 has on a page (seon.render.ui/reply-hiccup draws it)."
  "<div id=\"app\"><div id=\"b1_2Fa1_2F3\" class=\"seon-stream\" data-ignore-morph=\"\" data-stream-in=\"b1/a1/3\"></div></div>")

(defn- stream-tabs
  "The web proc with a1's chat open in two tabs (one by its id, one by its branch) and a3's in a third, each keyframed
  with html (a1's drawing its call's in-flight element when html is call-el), and the agents' index."
  ([] (stream-tabs call-el))
  ([html]
   (let [st (#'w/web-proc {:port 0})
         [st _] (#'w/web-proc st :from-render {:op :index :agents keyed})
         a1 (a/chan 64) a1b (a/chan 64) a3 (a/chan 64)
         [st _] (#'w/web-proc st :http {:op :open :view [:chat :a1] :ch a1})
         [st _] (#'w/web-proc st :http {:op :open :view [:chat :agent-1] :ch a1b})
         [st _] (#'w/web-proc st :http {:op :open :view [:chat :a3] :ch a3})
         st (reduce (fn [st v] (first (#'w/web-proc st :from-render {:op :keyframe :view v :html html})))
                    st [[:chat :a1] [:chat :agent-1] [:chat :a3]])]
     (doseq [c [a1 a1b a3]] (a/poll! c))
     [st a1 a1b a3])))

(def ^:private msg {:stream "b1/a1/3/0/message" :in "b1/a1/3" :part "message" :agent "b1/a1"})
(def ^:private part-sel "[id=\"b1_2Fa1_2F3_2F0_2Fmessage\"]")

(deftest a-stream-appends-to-its-agents-chat
  (let [[st a1 a1b a3] (stream-tabs)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "Reading <the> task"))]
    (doseq [c [a1 a1b]]
      (let [first-chunk (a/poll! c)]
        (is (str/includes? first-chunk "data: selector [id=\"b1_2Fa1_2F3\"]\ndata: mode append\n")
            (str "the part goes into the call's element, in a1's chat by its id and by its branch: " first-chunk))
        (is (str/includes? first-chunk "data-stream-part=\"message\" id=\"b1_2Fa1_2F3_2F0_2Fmessage\">Reading &lt;the&gt; task</div>")
            "with its text, escaped")))
    (is (nil? (a/poll! a3)) "another agent's chat gets nothing")
    (let [[_ _] (#'w/web-proc st :streams (assoc msg :offset 18 :text " now"))]
      (is (= (str "event: datastar-patch-elements\ndata: selector " part-sel "\ndata: mode append\ndata: elements  now\n\n") (a/poll! a1))
          "then each message is its text alone, appended to the part"))))

(deftest a-stream-gap-appends-an-ellipsis
  (let [[st a1] (stream-tabs)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "abc"))
        _ (a/poll! a1)
        ;; offsets 3-9 were dropped before this proc (the sliding in-port): the text says so
        [st _] (#'w/web-proc st :streams (assoc msg :offset 10 :text "xyz"))]
    (is (str/includes? (a/poll! a1) "data: elements …xyz\n"))
    (let [[_ _] (#'w/web-proc st :streams (assoc msg :offset 13 :text "!"))]
      (is (str/includes? (a/poll! a1) "data: elements !\n") "and carries on from the new offset"))))

(deftest a-stream-that-starts-before-its-element-is-drawn-reaches-the-tab-when-it-is
  ;; found live on 7888 (2026-11-08): the model streams as soon as its call is committed, and the page draws the call's
  ;; element only after root reads that commit, so a part given to a tab before then was appended to nothing and lost
  (let [[st a1] (stream-tabs "<div id=\"app\"></div>")
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "Reading "))
        [st _] (#'w/web-proc st :streams (assoc msg :offset 8 :text "the task"))]
    (is (nil? (a/poll! a1)) "nothing while the tab has no element to hold it")
    (let [[st _] (#'w/web-proc st :from-render {:op :patch :view [:chat :a1] :frags [["app" call-el]] :appends [] :removes []})
          patch (a/poll! a1) caught (a/poll! a1)]
      (is (str/includes? patch "b1_2Fa1_2F3") "the patch that draws the element")
      (is (str/includes? caught ">Reading the task</div>") (str "then the part with all the text so far: " caught))
      (let [[st _] (#'w/web-proc st :from-render {:op :patch :view [:chat :a1] :frags [["app" call-el]] :appends [] :removes []})]
        (a/poll! a1)
        (is (nil? (a/poll! a1)) "a later patch that keeps the element catches nothing up again")
        (let [[_ _] (#'w/web-proc st :streams (assoc msg :offset 16 :text "."))]
          (is (str/includes? (a/poll! a1) (str "data: selector " part-sel "\ndata: mode append\ndata: elements .\n")) "and appends go on"))))))

(deftest a-tab-opened-mid-stream-gets-the-text-so-far
  (let [[st a1] (stream-tabs)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "Thinking"))
        _ (a/poll! a1)
        late (a/chan 64)
        [st _] (#'w/web-proc st :http {:op :open :view [:chat :a1] :ch late})
        [st _] (#'w/web-proc st :from-render {:op :keyframe :view [:chat :a1] :html call-el})
        _ (a/poll! late)
        caught (a/poll! late)]
    (is (str/includes? caught ">Thinking</div>") (str "its keyframe draws the element, and the part follows with all the text: " caught))
    (let [[_ _] (#'w/web-proc st :streams (assoc msg :offset 8 :text " more"))]
      (is (str/includes? (a/poll! late) "data: elements  more\n") "then appends")
      (is (str/includes? (a/poll! a1) "data: elements  more\n") "as for a tab that saw the start"))))

(deftest a-stale-tab-is-caught-up-by-its-keyframe
  ;; a part sits in an element Datastar does not morph (data-ignore-morph), so a keyframe keeps the part as the tab had it;
  ;; what it missed while stale comes as the part replaced with all the text held
  (let [[st a1] (stream-tabs)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "abc"))
        _ (a/poll! a1)
        t (some (fn [[t tab]] (when (= a1 (:ch tab)) t)) (:tabs st))
        st (update st :stale conj t)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 3 :text "def"))
        _ (is (nil? (a/poll! a1)) "a stale tab is sent nothing")
        [_ _] (#'w/web-proc st :from-render {:op :keyframe :view [:chat :a1] :html call-el})
        _ (a/poll! a1)
        caught (a/poll! a1)]
    (is (str/includes? caught (str "data: selector " part-sel "\ndata: mode replace\n")) (str "the part replaced: " caught))
    (is (str/includes? caught ">abcdef</div>") "with all the text held")))

(deftest an-end-and-an-ended-agent-leave-no-stream-entries
  (let [[st] (stream-tabs)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "a"))
        [st _] (#'w/web-proc st :streams {:stream "b1/a1/3/reasoning" :in "b1/a1/3" :part "reasoning" :agent "b1/a1" :offset 0 :text "b"})
        [st _] (#'w/web-proc st :streams {:stream "b1/a3/1/reasoning" :in "b1/a3/1" :part "reasoning" :agent "b1/a3" :offset 0 :text "c"})
        [st _] (#'w/web-proc st :streams (assoc msg :end true))]
    (is (= 2 (:streams (#'w/stats st))) "the ended stream's entry is gone")
    (is (not-any? #(= [ "b1/a1" "b1/a1/3/0/message"] %) (mapcat :parts (vals (:tabs st)))) "no tab holds its part")
    (let [[st _] (#'w/web-proc st :from-render {:op :index :agents (assoc-in keyed [:a1 :status] :killed)})]
      (is (= 1 (:streams (#'w/stats st))) "an agent whose process ended takes its open streams with it")
      (is (not-any? #(= "b1/a1" (first %)) (mapcat :parts (vals (:tabs st)))) "no tab keeps what it held of them")
      (is (not-any? #{"b1/a1/3"} (mapcat :has (vals (:tabs st)))) "nor its in-flight element"))))

(deftest a-streams-blank-text-and-newlines-reach-the-page
  ;; found by review (2026-11-08): patch-event drops a blank element and split-lines drops a trailing newline, so a
  ;; message of a newline or spaces vanished from the streamed text
  (let [[st a1] (stream-tabs)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 0 :text "a"))
        _ (a/poll! a1)
        [st _] (#'w/web-proc st :streams (assoc msg :offset 1 :text "\n"))
        nl (a/poll! a1)
        [_ _] (#'w/web-proc st :streams (assoc msg :offset 2 :text "  "))
        sp (a/poll! a1)]
    (is (str/includes? nl "data: mode append\ndata: elements \ndata: elements \n\n") (str "a newline is two lines Datastar joins with one: " (pr-str nl)))
    (is (str/includes? sp "data: elements   \n") (str "spaces are kept: " (pr-str sp)))))

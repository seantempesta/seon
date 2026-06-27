(ns seon.web.reactive.transform-test
  "Render-time rewrite — agent fn-call / fn-ref handler slots → standard
   Datastar `@post('/call?…')`. Behavioral assertions on the rewritten
   structure + the call descriptor that decodes back out of the URL — NOT
   brittle full-string matches on the emitted Datastar expression."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.web.reactive.transform :as transform]))

(def ^:private on-click (keyword "data-on:click"))
(def ^:private on-submit (keyword "data-on:submit"))

(defn- action->params
  "Pull the URLSearchParams out of a rewritten `@post('/call?…')` action
   string (the URL is the bit inside the single quotes)."
  [action]
  (let [url (second (re-find #"@post\('([^']*)'\)" action))]
    (.-searchParams (js/URL. (str "http://x" url)))))

;; ---------------------------------------------------------------------------
;; fn-CALL — a seq with a symbol head; args bound at render time.
;; ---------------------------------------------------------------------------

(deftest fn-call-slot-rewrites-to-standard-datastar-post
  (let [out    (transform/transform-hiccup
                 'my.agent.tst
                 [:button {:on-click (list 'cancel-order! "o-1")} "Cancel"])
        attrs  (second out)
        action (get attrs on-click)]
    (testing "the on-click key becomes a standard data-on:click (old key gone)"
      (is (contains? attrs on-click))
      (is (not (contains? attrs :on-click))))
    (testing "the value is a standard Datastar @post(…) to the call door — no bespoke macro"
      (is (string? action))
      ;; behavior, not the exact route: it's a datastar @post to a /call door
      ;; (flat or hierarchical both route to the gate); namespace-as-route is
      ;; verified by the fn param below, so don't pin the path structure.
      (is (str/starts-with? action "@post('"))
      (is (str/includes? action "/call?")))
    (let [sp (action->params action)]
      (testing "namespace is the route — the bare handler qualified to the authoring ns"
        (is (= "my.agent.tst/cancel-order!" (.get sp "fn"))))
      (testing "render-time args ride the query, transit-serialized, decoding back"
        (is (= ["o-1"] (transform/decode-args (.get sp "args"))))))))

(deftest fn-call-keeps-an-already-qualified-symbol
  (let [out   (transform/transform-hiccup
                'my.agent.tst
                [:button {:on-click (list 'my.agent.other/do-it 1 2)}])
        sp    (action->params (get (second out) on-click))]
    (testing "an explicitly-qualified handler symbol is NOT re-qualified"
      (is (= "my.agent.other/do-it" (.get sp "fn"))))
    (testing "all render-time args survive the round trip in order"
      (is (= [1 2] (transform/decode-args (.get sp "args")))))))

(deftest fn-call-args-with-apostrophe-stay-quote-safe
  ;; A string arg containing an apostrophe must not break the single-quoted
  ;; @post('…') expression — the encoder %27-escapes it; it decodes back whole.
  (let [out   (transform/transform-hiccup
                'my.agent.tst
                [:button {:on-click (list 'note! "O'Brien")}])
        sp    (action->params (get (second out) on-click))]
    (is (= ["O'Brien"] (transform/decode-args (.get sp "args"))))))

;; ---------------------------------------------------------------------------
;; fn-REF — a bare/qualified symbol; args from click-time signals (no ?args).
;; ---------------------------------------------------------------------------

(deftest fn-ref-slot-rewrites-without-args
  (let [out    (transform/transform-hiccup
                 'my.agent.tst
                 [:form {:on-submit 'submit-order!}])
        attrs  (second out)
        action (get attrs on-submit)
        sp     (action->params action)]
    (testing "the on-submit key becomes data-on:submit"
      (is (contains? attrs on-submit)))
    (testing "the fn-ref qualifies to the authoring ns"
      (is (= "my.agent.tst/submit-order!" (.get sp "fn"))))
    (testing "NO render-time args — they come from click-time signals"
      (is (nil? (.get sp "args"))))))

;; ---------------------------------------------------------------------------
;; Non-handlers + core hiccup are untouched (the rewrite is a no-op there).
;; ---------------------------------------------------------------------------

(deftest non-handler-attrs-and-plain-datastar-untouched
  (let [in  [:div {:class "x"
                   :data-on:click "@post('/sse')"   ; already a Datastar string
                   :id "y"}
             [:span "hi"]]
        out (transform/transform-hiccup 'my.agent.tst in)]
    (testing "a tile with no fn-call/fn-ref handler slots is returned unchanged"
      (is (= in out)))))

(deftest nested-handler-slots-are-rewritten
  (let [out (transform/transform-hiccup
              'my.agent.tst
              [:div {:class "wrap"}
               [:button {:on-click (list 'a!)} "A"]
               [:button {:on-click 'b!} "B"]])
        [_ _ btn-a btn-b] out]
    (testing "a fn-call nested in a child is rewritten"
      (is (= "my.agent.tst/a!" (.get (action->params (get (second btn-a) on-click)) "fn"))))
    (testing "a fn-ref nested in a child is rewritten"
      (is (= "my.agent.tst/b!" (.get (action->params (get (second btn-b) on-click)) "fn"))))))

;; ---------------------------------------------------------------------------
;; Args codec round-trips a representative value mix.
;; ---------------------------------------------------------------------------

(deftest args-codec-round-trips
  (let [args [{:seon.x/a 1} "two" 3 :four [5 6]]]
    (is (= args (transform/decode-args (transform/encode-args args))))))

;; ---------------------------------------------------------------------------
;; Security — decode-args is DATA-ONLY (the /call RCE regression).
;; Transit-JSON decodes ["~#list",[…]] into a real seq and "~$sym" into a real
;; symbol. In the old synthesize-a-form-and-eval path a list arg pr-str'd as
;; EVALUABLE code, so a crafted ?args= broke out of the capability gate and ran
;; arbitrary code (RCE). The decoder now refuses any code-shaped arg before it
;; can reach the invoke path — belt-and-suspenders behind resolve-and-apply.
;; ---------------------------------------------------------------------------

(deftest decode-args-refuses-a-list-arg
  ;; The PoC shape: a vector whose element is a transit LIST holding a symbol —
  ;; `[(js/require "child_process")]`. Refused, never returned as args.
  (let [payload "[[\"~#list\",[\"~$js/require\",\"child_process\"]]]"]
    (is (thrown? :default (transform/decode-args payload))
        "a list arg (the RCE break-out shape) must be refused, not decoded")))

(deftest decode-args-refuses-a-symbol-arg
  (let [payload (transform/encode-args [(symbol "evil")])]
    (is (thrown? :default (transform/decode-args payload))
        "a bare symbol arg must be refused")))

(deftest decode-args-refuses-a-non-vector-top-level
  ;; A top-level transit list (not wrapped in a vector) is refused too.
  (is (thrown? :default (transform/decode-args "[\"~#list\",[1,2]]"))))

(deftest decode-args-allows-pure-data
  ;; The happy path stays open: scalars, vectors, maps, sets, keywords decode.
  (let [args [{:seon.x/a 1} "two" 3 :four [5 6] #{7 8} true]]
    (is (= args (transform/decode-args (transform/encode-args args))))))

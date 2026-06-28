(ns my.tile-test
  "my.tile is the INTERACTIVE canvas surface — controls (button/input/
   select/toggle/form) an agent wires to its OWN fns. Two contracts, both
   pure (no db, no async):

     1. EVERY helper returns the `:seon.render/html-response` envelope —
        `:seon.render/hiccup` (a keyword-head vector with a wired handler
        slot the render-time transform rewrites into a Datastar @post) AND
        `:seon.render/ai` (a compact line NAMING the control + its fn, so
        the human's button and the agent's description can't drift).

     2. The handler slot carries a fn-CALL `(list 'f arg…)` or a fn-REF
        `'f` VERBATIM — my.tile never emits a raw action string (the gate
        only authorizes a fn the agent defined). The
        `seon.web.reactive.transform` step (covered in transform_test) is
        what turns it into Datastar; here we pin that the slot is present
        and typed, and that bound fields carry `data-bind`."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [my.tile :as tile]
    [seon.web.reactive.transform :as xf]))

(deftest button-fn-call-carries-handler-slot-and-mirrors
  (let [r (tile/button {:my.tile/label  "Approve"
                        :my.tile/action (list 'approve! "o-7")})
        h (:seon.render/hiccup r)
        [_tag attrs label] h]
    (testing "hiccup is a keyword-head button with the fn-CALL in :on-click VERBATIM"
      (is (= :button (first h)))
      (is (= (list 'approve! "o-7") (:on-click attrs)))
      (is (= "Approve" label))
      (is (str/includes? (:class attrs) "cursor-pointer")
          "emits a safelisted control class"))
    (testing "ai names the control AND the fn it calls"
      (is (= "[button: \"Approve\" → approve! \"o-7\"]" (:seon.render/ai r))))
    (testing "the slot survives the render-time transform into a wired @post"
      (let [t (xf/transform-hiccup 'my.agent.x h)
            action (:data-on:click (second t))]
        (is (str/includes? action "@post('/agent/x/call?fn=my.agent.x%2Fapprove!"))
        (is (str/includes? action "args="))
        (is (nil? (:on-click (second t))) "raw slot is gone — rewritten")))))

(deftest button-fn-ref-mirrors
  (let [r (tile/button {:my.tile/label "Submit" :my.tile/action 'submit!})]
    (is (= 'submit! (:on-click (second (:seon.render/hiccup r)))))
    (is (= "[button: \"Submit\" → submit!]" (:seon.render/ai r)))))

(deftest input-binds-signal-and-mirrors
  (let [r (tile/input {:my.tile/field "note" :my.tile/label "Note"
                       :my.tile/placeholder "type…"})
        s (pr-str (:seon.render/hiccup r))]
    (testing "the input carries data-bind to the signal + the placeholder"
      (is (str/includes? s ":data-bind \"note\""))
      (is (str/includes? s "type…")))
    (testing "ai names the field → signal"
      (is (= "[input: Note → signal \"note\"]" (:seon.render/ai r))))))

(deftest select-lists-options-and-binds
  (let [r (tile/select {:my.tile/field "tier"
                        :my.tile/options [["free" "Free"] ["pro" "Pro"]]})
        s (pr-str (:seon.render/hiccup r))]
    (is (str/includes? s ":data-bind \"tier\""))
    (is (str/includes? s "Free"))
    (is (str/includes? s "Pro"))
    (is (= "[select: tier → signal \"tier\" | options: Free, Pro]"
           (:seon.render/ai r)))))

(deftest toggle-binds-boolean-signal
  (let [r (tile/toggle {:my.tile/field "live" :my.tile/label "Live updates"})
        s (pr-str (:seon.render/hiccup r))]
    (is (str/includes? s ":type \"checkbox\""))
    (is (str/includes? s ":data-bind \"live\""))
    (is (= "[toggle: Live updates → signal \"live\"]" (:seon.render/ai r)))))

(deftest form-composes-fields-and-wires-submit
  (let [note (tile/input  {:my.tile/field "note" :my.tile/label "Note"})
        tier (tile/select {:my.tile/field "tier"
                           :my.tile/options [["free" "Free"] ["pro" "Pro"]]})
        r    (tile/form {:my.tile/submit 'save-note!
                         :my.tile/label  "Save"
                         :my.tile/fields [note tier]})
        h    (:seon.render/hiccup r)
        s    (pr-str h)]
    (testing "the form wires its submit as a fn-REF (signals → one map arg)"
      (is (= :form (first h)))
      (is (= 'save-note! (:on-submit (second h)))))
    (testing "it STACKS each field's hiccup (composition holds) + appends a submit button"
      (is (str/includes? s ":data-bind \"note\""))
      (is (str/includes? s ":data-bind \"tier\""))
      (is (str/includes? s "Save"))
      (is (some #(and (vector? %) (= :button (first %))) (drop 2 h))
          "a submit button is appended"))
    (testing "ai joins every field's ai under the form line"
      (let [ai (:seon.render/ai r)]
        (is (str/includes? ai "[form → save-note!]"))
        (is (str/includes? ai "signal \"note\""))
        (is (str/includes? ai "signal \"tier\""))
        (is (str/includes? ai "[submit: \"Save\"]"))))
    (testing "the whole form transforms to a wired Datastar @post on submit"
      (let [t (xf/transform-hiccup 'my.agent.x h)]
        (is (str/includes?
              (:data-on:submit (second t))
              "@post('/agent/x/call?fn=my.agent.x%2Fsave-note!')")
            "no render-time args — the body signals become the fn's map arg")
        (is (str/includes? (pr-str t) ":data-bind \"note\"")
            "data-bind passes through the transform untouched")))))

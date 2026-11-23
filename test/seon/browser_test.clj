(ns seon.browser-test
  "Unit, mock-IPC state-machine, and seed=42 generative tests for `seon.browser` and
   `seon.browser.measure`."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.browser :as browser]
            [seon.browser.measure :as measure]))

(defn- mock-browser-state []
  (let [sent (atom [])
        st0 (browser/browser-proc {:port 0 :width 1280 :height 820})
        st (assoc st0 :handle {:send! (fn [cmd] (swap! sent conj cmd) true)
                               :stop! (fn [] nil)})]
    [st sent]))

(deftest retry-helpers-test
  (testing "next-retry-delay grows then caps at 2000"
    (is (= [250 500 1000 2000 2000 2000] (mapv browser/next-retry-delay [0 1 2 3 4 10]))))
  (testing "loaded? detects blank vs rendered document"
    (is (browser/loaded? {:app true}))
    (is (browser/loaded? {:children 3}))
    (is (not (browser/loaded? {:children 0 :readyState "complete"})))
    (is (not (browser/loaded? {:error "boom"})))
    (is (not (browser/loaded? nil))))
  (testing "retry-exhausted? bounds by attempts and deadline"
    (is (not (browser/retry-exhausted? 3 12 1000 30000)))
    (is (browser/retry-exhausted? 12 12 1000 30000))
    (is (browser/retry-exhausted? 3 12 30000 30000))))

(deftest measure-js-builders-test
  (testing "measure-boxes-js builds safe IIFE with default and custom selectors"
    (let [js-def (measure/measure-boxes-js)
          js-str (measure/measure-boxes-js ".card[data-x=\"a'b\"]")
          js-opt (measure/measure-boxes-js {:selector "#seon-view-unit" :max-elements 42})
          js-nil (measure/measure-boxes-js nil)]
      (is (= js-def js-nil))
      (doseq [needle ["getBoundingClientRect" "getComputedStyle" "scrollWidth" "clientWidth"
                      "overflowY" "data-chosen-fn" "data-min-width" "data-entity"
                      "innerWidth" "docScrollW" "Math.round" "catch(e)"]]
        (is (str/includes? js-def needle) (str "Missing " needle)))
      (is (str/includes? js-def "[data-chosen-fn], .surface-entity-item, #seon-view-unit, #seon-header, #seon-sidebar"))
      (is (str/includes? js-str "\\\"a'b\\\""))
      (is (str/includes? js-opt "maxEls=42"))))

  (testing "measure-html-snippet-js builds off-screen #seon-app probe with finally cleanup"
    (let [js (measure/measure-html-snippet-js "<div class=\"card\" data-min-width=\"320\">Hi \"quote\"</div>" 420)
          js-clamp (measure/measure-html-snippet-js "<span>x</span>" 5)]
      (doseq [needle ["seon-app" "data-seon-measure-probe" "slotW=420" "visibility:hidden"
                      "getBoundingClientRect" "scrollWidth" "overflow-x?" "finally{" "removeChild"]]
        (is (str/includes? js needle) (str "Missing " needle)))
      (is (str/includes? js "Hi \\\"quote\\\""))
      (is (str/includes? js-clamp "slotW=32"))))

  (testing "stage-hiccup-js and clear-stage-js manage #seon-preview-stage cleanly"
    (let [st-dark (measure/stage-hiccup-js "<div class=\"card\">A</div>" 360 :dark)
          st-light (measure/stage-hiccup-js "<div class=\"card\">A</div>" 640 "light")
          st-auto (measure/stage-hiccup-js "<div>B</div>" 1100)
          clr (measure/clear-stage-js)]
      (is (str/includes? st-dark "seon-preview-stage"))
      (is (str/includes? st-dark "stageW=360"))
      (is (str/includes? st-dark "color-scheme:dark"))
      (is (str/includes? st-light "stageW=640"))
      (is (str/includes? st-light "color-scheme:light"))
      (is (str/includes? st-auto "theme=null"))
      (is (str/includes? clr "seon-preview-stage"))
      (is (str/includes? clr "removeChild")))))

(deftest analyze-pixel-fit-and-calibrate-hints-test
  (testing "clean fit with intentional vertical scrolling passes :ok? true"
    (let [raw {:viewport {:innerWidth 1280 :innerHeight 820 :docScrollW 1280 :docScrollH 1400 :overflowX false}
               :elements [{:selector "#seon-sidebar" :id "seon-sidebar" :w 240 :h 772
                           :scroll-w 240 :scroll-h 1200 :client-w 240 :client-h 772
                           :overflow-x? false :overflow-y? true :scrollable-y? true :area 185280}
                          {:selector ".surface-entity-item" :entity "42"
                           :chosen-fn "seon.render.entities/render-var-sig" :hint-min-w 320
                           :w 540 :h 160 :scroll-w 540 :scroll-h 160 :client-w 540 :client-h 160
                           :overflow-x? false :overflow-y? false :scrollable-y? false :area 86400}
                          {:selector ".surface-entity-item" :entity "43"
                           :chosen-fn "seon.render.entities/render-var-chip" :hint-min-w 160
                           :w 128 :h 28 :scroll-w 128 :scroll-h 28 :client-w 128 :client-h 28
                           :overflow-x? false :overflow-y? false :scrollable-y? false :area 3584}]}
          res (measure/analyze-pixel-fit raw)
          cal (measure/calibrate-hints res)]
      (is (true? (:ok? res)))
      (is (false? (:doc-overflow-x res)))
      (is (empty? (:clipped-items res)))
      (is (= 1 (count (:scrollable-items res))))
      (is (= "#seon-sidebar" (:selector (first (:scrollable-items res)))))
      (is (= {:count 1 :max-w 540 :max-h 160 :max-area 86400
              :any-overflow-x? false :hint-min-w 320 :suggested-min-w 320}
             (get-in res [:by-renderer 'seon.render.entities/render-var-sig])))
      (is (= {:count 1 :max-w 128 :max-h 28 :max-area 3584
              :any-overflow-x? false :hint-min-w 160 :suggested-min-w 128}
             (get-in res [:by-renderer 'seon.render.entities/render-var-chip])))
      (is (= 0 (get-in cal ['seon.render.entities/render-var-sig :delta-w])))
      (is (= -32 (get-in cal ['seon.render.entities/render-var-chip :delta-w])))
      (is (str/includes? (:summary res) "1280x820 ok"))))

  (testing "overflowing card and unintentional vertical clip fail :ok? and raise suggested-min-w"
    (let [raw {:viewport {:innerWidth 430 :innerHeight 820 :docScrollW 510 :docScrollH 900 :overflowX true}
               :elements [{:selector ".surface-entity-item" :entity "99"
                           :chosen-fn "seon.render.entities/render-var-full" :hint-min-w 540
                           :w 400 :h 300 :scroll-w 612 :scroll-h 300 :client-w 400 :client-h 300
                           :overflow-x? true :overflow-y? false :scrollable-y? false :area 120000}
                          {:selector "#seon-header" :id "seon-header"
                           :w 430 :h 48 :scroll-w 430 :scroll-h 72 :client-w 430 :client-h 48
                           :overflow-x? false :overflow-y? true :scrollable-y? false :area 20640}]}
          res (measure/analyze-pixel-fit raw)
          cal (measure/calibrate-hints res)]
      (is (false? (:ok? res)))
      (is (true? (:doc-overflow-x res)))
      (is (= 2 (count (:clipped-items res))))
      (is (empty? (:scrollable-items res)))
      (is (= 612 (get-in res [:by-renderer 'seon.render.entities/render-var-full :suggested-min-w])))
      (is (= {:measured-w 400 :measured-h 300 :measured-area 120000
              :hint-min-w 540 :suggested-min-w 612 :delta-w 72 :overflow-x? true}
             (get cal 'seon.render.entities/render-var-full)))
      (is (str/includes? (:summary res) "OVERFLOW"))))

  (testing "error, nil, JSON-string, and normalize-image-frame edge cases"
    (let [err-res (measure/analyze-pixel-fit {:error "js boom"})
          nil-res (measure/analyze-pixel-fit nil)
          json-res (measure/analyze-pixel-fit
                    "{\"viewport\":{\"innerWidth\":800,\"innerHeight\":600,\"docScrollW\":800,\"docScrollH\":600,\"overflowX\":false},\"elements\":[]}")
          snip-ok (measure/normalize-snippet-measurement {:w 320 :h 96 :area 30720 :scroll-w 320 :scroll-h 96 :overflow-x? false})
          snip-err (measure/normalize-snippet-measurement {:error "bad html"})
          frame (measure/normalize-image-frame {:mime "image/jpeg" :base64 "AA==" :width 360 :height 240} "card · 360px" 360 {:w 360 :h 180})]
      (is (false? (:ok? err-res)))
      (is (false? (:ok? nil-res)))
      (is (true? (:ok? json-res)))
      (is (= 800 (get-in json-res [:viewport :w])))
      (is (true? (:ok? snip-ok)))
      (is (= 30720 (:area snip-ok)))
      (is (false? (:ok? snip-err)))
      (is (= {:seon/schema :seon.media/image} (meta frame)))
      (is (= {:seon.media/mime "image/jpeg" :seon.media/base64 "AA=="
              :seon.media/width 360 :seon.media/height 240
              :seon.media/label "card · 360px" :seon.media/dom {:w 360 :h 180}}
             frame)))))

(deftest browser-measure-generative-seed42-test
  (testing "seed=42 deterministic generative checks across pure measure contracts"
    (let [check-fn (fn [v]
                     (let [sch (:malli/schema (meta v))
                           f @v]
                       (is (some? sch) (str "Missing schema on " v))
                       (cond
                         (= :=> (first sch))
                         (let [[_ args-s ret-s] sch
                               val-ret (m/validator ret-s)]
                           (doseq [i (range 25)]
                             (let [args (mg/generate args-s {:seed (+ 42 i) :size 5})
                                   out (apply f args)]
                               (is (val-ret out) (str "Invalid return for " v " args=" (pr-str args))))))
                         (= :function (first sch))
                         (doseq [ar (rest sch)
                                 :let [[_ args-s ret-s] ar
                                       val-ret (m/validator ret-s)]
                                 i (range 15)]
                           (let [args (mg/generate args-s {:seed (+ 42 i) :size 5})
                                 out (apply f args)]
                             (is (val-ret out) (str "Invalid return for " v " args=" (pr-str args))))))))]
      (doseq [v [#'measure/measure-boxes-js
                 #'measure/measure-html-snippet-js
                 #'measure/stage-hiccup-js
                 #'measure/clear-stage-js
                 #'measure/normalize-snippet-measurement
                 #'measure/normalize-image-frame
                 #'measure/analyze-pixel-fit
                 #'measure/calibrate-hints]]
        (check-fn v)))))

(deftest browser-proc-measure-ops-test
  (testing "headless/disabled browser-proc returns graceful fallback immediately"
    (let [st (browser/browser-proc {:port 0})]
      (doseq [op [:measure-dom :measure-snippet :render-hiccup-frames :snapshot]]
        (let [rch (a/chan 1)
              [_ _] (browser/browser-proc st :req {:op op :reply-ch rch})
              ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
          (is (map? ans) (str "Expected immediate reply for " op))
          (is (false? (:ok? ans)))
          (is (= :browser-unavailable (:error ans)))))))

  (testing ":measure-dom dispatches eval_js and analyzes WKWebView reply"
    (let [[st sent] (mock-browser-state)
          rch (a/chan 1)
          [st1 _] (browser/browser-proc st :req {:op :measure-dom :selector ".card" :reply-ch rch})
          cmd (first @sent)
          _ (is (= "eval_js" (:op cmd)))
          _ (is (str/includes? (:code cmd) ".card"))
          [_ _] (browser/browser-proc st1 :ipc
                                      {:id (:id cmd) :ok true
                                       :value {:viewport {:innerWidth 1280 :innerHeight 820 :docScrollW 1280 :docScrollH 820 :overflowX false}
                                               :elements [{:selector ".card" :chosen-fn "seon.render.entities/render-var-sig"
                                                           :hint-min-w 320 :w 320 :h 120 :scroll-w 320 :scroll-h 120
                                                           :client-w 320 :client-h 120 :overflow-x? false :overflow-y? false
                                                           :scrollable-y? false :area 38400}]}})
          ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
      (is (true? (:ok? ans)))
      (is (= 1 (:element-count ans)))
      (is (= 320 (get-in ans [:by-renderer 'seon.render.entities/render-var-sig :suggested-min-w])))))

  (testing ":measure-snippet supports HTML and Hiccup plus timeout/error handling"
    (let [[st sent] (mock-browser-state)
          rch (a/chan 1)
          [st1 _] (browser/browser-proc st :req {:op :measure-snippet :hiccup [:div {:class "card" :data-min-width 320} "Hi"] :slot-w 400 :reply-ch rch})
          cmd (first @sent)
          _ (is (= "eval_js" (:op cmd)))
          _ (is (str/includes? (:code cmd) "slotW=400"))
          [_ _] (browser/browser-proc st1 :ipc
                                      {:id (:id cmd) :ok true
                                       :value {:slot-w 400 :w 400 :h 88 :area 35200
                                               :scroll-w 400 :scroll-h 88 :client-w 400 :client-h 88
                                               :overflow-x? false :overflow-y? false :hint-min-w 320}})
          ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
      (is (true? (:ok? ans)))
      (is (= {:w 400 :h 88 :area 35200 :scroll-w 400 :scroll-h 88 :overflow-x? false}
             (select-keys ans [:w :h :area :scroll-w :scroll-h :overflow-x?]))))

    (let [[st sent] (mock-browser-state)
          rch (a/chan 1)
          [st1 _] (browser/browser-proc st :req {:op :measure-snippet :html "<div>x</div>" :slot-w 300 :reply-ch rch})
          cmd (first @sent)
          [_ _] (browser/browser-proc st1 :timer {:ev :timeout :id (:id cmd)})
          ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
      (is (false? (:ok? ans)))
      (is (= :measure-timeout (:error ans)))))

  (testing ":render-hiccup-frames sequences stage -> snapshot -> clear across widths"
    (let [[st sent] (mock-browser-state)
          rch (a/chan 1)
          [st1 _] (browser/browser-proc st :req {:op :render-hiccup-frames
                                                 :html "<div class=\"card\">Demo</div>"
                                                 :widths [360 640] :theme :dark :label-prefix "demo"
                                                 :reply-ch rch})
          c1 (last @sent)
          _ (is (= "eval_js" (:op c1)))
          _ (is (str/includes? (:code c1) "stageW=360"))
          [st2 _] (browser/browser-proc st1 :ipc {:id (:id c1) :ok true :value {:slot-w 360 :w 360 :h 180 :scroll-w 360 :scroll-h 180 :overflow-x? false :overflow-y? false}})
          c2 (last @sent)
          _ (is (= "snapshot" (:op c2)))
          _ (is (= 360 (:width c2)))
          [st3 _] (browser/browser-proc st2 :ipc {:id (:id c2) :ok true :mime "image/jpeg" :width 360 :height 220 :base64 "AAA=" :dom {:url "/"}})
          c3 (last @sent)
          _ (is (= "eval_js" (:op c3)))
          _ (is (str/includes? (:code c3) "stageW=640"))
          [st4 _] (browser/browser-proc st3 :ipc {:id (:id c3) :ok true :value {:slot-w 640 :w 640 :h 140 :scroll-w 640 :scroll-h 140 :overflow-x? false :overflow-y? false}})
          c4 (last @sent)
          _ (is (= "snapshot" (:op c4)))
          _ (is (= 640 (:width c4)))
          [st5 _] (browser/browser-proc st4 :ipc {:id (:id c4) :ok true :mime "image/jpeg" :width 640 :height 220 :base64 "BBB=" :dom {:url "/"}})
          c5 (last @sent)
          _ (is (= "eval_js" (:op c5)))
          _ (is (str/includes? (:code c5) "removeChild"))
          [_ _] (browser/browser-proc st5 :ipc {:id (:id c5) :ok true :value {:cleared true :existed true}})
          ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
      (is (true? (:ok? ans)))
      (is (= [360 640] (:widths ans)))
      (is (= 2 (count (:frames ans))))
      (is (= ["demo · 360px" "demo · 640px"] (mapv :seon.media/label (:frames ans))))
      (is (= ["AAA=" "BBB="] (mapv :seon.media/base64 (:frames ans))))
      (is (every? #(= {:seon/schema :seon.media/image} (meta %)) (:frames ans)))))

  (testing "edge helpers degrade cleanly when binary unavailable and route via WritePort"
    (let [m-res (browser/measure-live-url! "http://127.0.0.1:9998/" {:binary-path "/nonexistent/seon-browser"})
          r-res (browser/render-hiccup-frames! "http://127.0.0.1:9998/" [:div "hi"] {:binary-path "/nonexistent/seon-browser"})
          req-ch (a/chan 1)
          _ (a/go (let [req (a/<! req-ch)]
                    (a/put! (:reply-ch req) {:ok? true :widths (:widths req)
                                             :frames [(measure/normalize-image-frame {:base64 "X=" :width 360 :height 220} "360px" 360 {:w 360 :h 100})]})
                    (a/close! (:reply-ch req))))
          ch-res (browser/render-hiccup-frames! req-ch [:div "hi"] {:widths [360]})]
      (is (false? (:ok? m-res)))
      (is (= :browser-unavailable (:error m-res)))
      (is (false? (:ok? r-res)))
      (is (= :browser-unavailable (:error r-res)))
      (is (true? (:ok? ch-res)))
      (is (= 1 (count (:frames ch-res)))))))

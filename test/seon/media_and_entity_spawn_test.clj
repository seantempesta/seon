(ns seon.media-and-entity-spawn-test
  "End-to-end proofs for native multi-width `:seon.media/image` rendering, multimodal OpenAI Wire
   attachment with KV-cache prefix stability, `(user/render-preview! ...)`, server browser ops,
   and 1-click `:op :spawn-on-entity` + Entity Explorer Composer."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.browser :as browser]
            [seon.code :as code]
            [seon.code.graph :as graph]
            [seon.error :as serr]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as helpers]
            [seon.model :as model]
            [seon.model.wire :as wire]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.media :as rmedia]
            [seon.render.surface.inspector :as inspector]
            [seon.schema :as schema]
            [seon.server :as server]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as txr]
            [seon.web :as web]
            [seon.web.actions :as actions]))

(defn- txr-call! [st op-map]
  (let [rch (a/chan 1)
        [st' out] (txr/transactor-proc st :tx (assoc op-map :reply-ch rch))
        reply (first (a/alts!! [rch (a/timeout 5000)]))]
    {:st st' :out out :reply reply}))

(def ^:private media-renderer-stub-src
  (str "(ns seon.render.media\n"
       "  (:require [clojure.string :as str] [seon.render.links :as rlinks]))\n"
       "(defn render-image-chip\n"
       "  {:malli/schema [:=> [:cat [:map [:seon.media/image :seon.media/image]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]\n"
       "   :cost/ticks 500000}\n"
       "  [{img :seon.media/image}] (seon.render.media/render-image-chip {:seon.media/image img}))\n"
       "(defn render-image-card\n"
       "  {:malli/schema [:=> [:cat [:map [:seon.media/image :seon.media/image]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]\n"
       "   :cost/ticks 1000000}\n"
       "  [{img :seon.media/image}] (seon.render.media/render-image-card {:seon.media/image img}))\n"))

(deftest media-schema-and-uniform-renderer-discovery-test
  (with-test-store [{:keys [conn]}]
    (testing "Malli schema validation & Datahike persistence for :seon.media/image"
      (let [uid (random-uuid)
            img {:seon.media/id uid
                 :seon.media/mime "image/jpeg"
                 :seon.media/base64 "AAECAwQFBgc="
                 :seon.media/width 360
                 :seon.media/height 167
                 :seon.media/label "360px"
                 :seon.media/dom {:w 360 :h 167 :scroll-w 360 :overflow-x? false}}]
        (is (:ok? (schema/validate-against @conn :seon.media/image img)))
        (d/transact conn {:tx-data [img]})
        (let [pulled (d/pull @conn '[*] [:seon.media/id uid])]
          (is (= uid (:seon.media/id pulled)))
          (is (= "image/jpeg" (:seon.media/mime pulled)))
          (is (= "AAECAwQFBgc=" (:seon.media/base64 pulled)))
          (is (= 360 (:seon.media/width pulled)))
          (is (= 167 (:seon.media/height pulled))))))

    (testing "fit/discover-renderers mechanically discovers render-image-chip & render-image-card"
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/media.clj" media-renderer-stub-src false)})
      (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? false})})
      (let [db @conn
            disc (fit/discover-renderers db [:seon.media/image])
            uid (random-uuid)
            ok-img {:seon.media/id uid :seon.media/mime "image/jpeg" :seon.media/base64 "AAECAw=="
                    :seon.media/width 360 :seon.media/height 167 :seon.media/label "360px"
                    :seon.media/dom {:w 360 :h 167 :scroll-w 360 :overflow-x? false}}
            clip-img (assoc ok-img :seon.media/dom {:w 360 :h 167 :scroll-w 480 :overflow-x? true})
            chip-h (:seon.ui/hiccup (rmedia/render-image-chip {:seon.media/image ok-img}))
            clip-chip-h (:seon.ui/hiccup (rmedia/render-image-chip {:seon.media/image clip-img}))
            card-h (:seon.ui/hiccup (rmedia/render-image-card {:seon.media/image ok-img}))
            card-html (render/hiccup->html card-h)
            card-txt (render/hiccup->text card-h)
            narrow (fit/fit-projections db [{:entity :img1 :schema :seon.media/image :data ok-img}]
                                        {:target :html :avail-w 240 :area-budget 200000})
            wide (fit/fit-projections db [{:entity :img1 :schema :seon.media/image :data ok-img}]
                                      {:target :html :avail-w 640 :area-budget 400000})]
        (is (= ['seon.render.media/render-image-card 'seon.render.media/render-image-chip] disc))
        (is (= 180 (:min-width (fit/measure-hiccup chip-h))))
        (is (= 360 (:min-width (fit/measure-hiccup card-h))))
        (is (str/includes? (render/hiccup->html chip-h) "ok"))
        (is (str/includes? (render/hiccup->html clip-chip-h) "clipped"))
        (is (str/includes? card-html "media-preview-img"))
        (is (str/includes? card-html "data:image/jpeg;base64,AAECAw=="))
        (is (str/includes? card-txt "360×167"))
        (is (not (str/includes? card-txt "AAECAw==")))
        (is (= 'seon.render.media/render-image-chip (get-in narrow [:selected 0 :chosen-fn])))
        (is (= 'seon.render.media/render-image-card (get-in wide [:selected 0 :chosen-fn])))))))

(deftest multimodal-wire-formatting-and-kv-cache-stability-test
  (with-test-store [{:keys [conn]}]
    (let [u1 (random-uuid)
          u2 (random-uuid)
          u3 (random-uuid)
          b64-1 "FAKEBASE64IMAGEONE=="
          b64-2 "FAKEBASE64IMAGETWO=="
          b64-3 "FAKEBASE64IMAGETHREE=="
          img1 {:seon.media/id u1 :seon.media/mime "image/jpeg" :seon.media/base64 b64-1
                :seon.media/width 360 :seon.media/height 167 :seon.media/label "360px"
                :seon.media/dom {:w 360 :h 167 :overflow-x? false}}
          img2 {:seon.media/id u2 :seon.media/mime "image/png" :seon.media/base64 b64-2
                :seon.media/width 640 :seon.media/height 148 :seon.media/label "640px"
                :seon.media/dom {:w 640 :h 148 :overflow-x? true}}
          img3 {:seon.media/id u3 :seon.media/mime "image/jpeg" :seon.media/base64 b64-3
                :seon.media/width 1100 :seon.media/height 124 :seon.media/label "1100px"
                :seon.media/dom {:w 1100 :h 124 :overflow-x? false}}
          ev-id (random-uuid)]
      (d/transact conn {:tx-data [img1 img2 img3
                                  {:seon.agent.eval/id ev-id :seon.agent.eval/agent :agent/vis
                                   :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                   :seon.agent.eval/call-id "tc-ev" :seon.agent.eval/handle 'result/e1
                                   :seon.agent.eval/code "(user/render-preview! [:div])"
                                   :seon.agent.eval/value "{:ok? true}"
                                   :seon.agent.eval/images [[:seon.media/id u3]]}]})
      (let [db @conn
            frames-val {:seon.media/frames [(dissoc img1 :seon.media/base64)
                                            (dissoc img2 :seon.media/base64)]
                        :summary "Rendered 2 frames: 360x167 ok, 640x148 overflow-x"}
            txt-single (wire/format-eval-output {:handle 'result/e1 :ok? true :value (pr-str img1) :result-schema :seon.media/image :db db})
            txt-frames (wire/format-eval-output {:handle 'result/e2 :ok? true :value (pr-str frames-val) :result-schema :seon.media/frames :db db})
            hist-t1 [{:turn 1 :calls [{:id "c1" :handle 'result/e2 :code "(user/render-preview! [:div])"
                                       :value (pr-str frames-val)}]}]
            hist-t1-ref [{:turn 1 :calls [{:id "tc-ev" :handle 'result/e1 :code "(user/render-preview! [:div])"
                                           :value "{:ok? true}"}]}]
            hist-t2 (conj hist-t1 {:turn 2 :calls [{:id "c2" :handle 'result/e3 :code "(+ 1 2)" :value "3"}]})
            w-on (model/build-agent-wire-messages {:vision? true}
                                                  {:agent-id :agent/vis :branch :branch/vis :walk-text "W"
                                                   :history hist-t1 :eval-db db :main-db db})
            w-evref (model/build-agent-wire-messages {:vision? true}
                                                     {:agent-id :agent/vis :branch :branch/vis :walk-text "W"
                                                      :history hist-t1-ref :eval-db db :main-db db})
            w-off (model/build-agent-wire-messages {:vision? false}
                                                   {:agent-id :agent/vis :branch :branch/vis :walk-text "W"
                                                    :history hist-t1 :eval-db db :main-db db})
            w-aged (model/build-agent-wire-messages {:vision? true}
                                                    {:agent-id :agent/vis :branch :branch/vis :walk-text "W"
                                                     :history hist-t2 :eval-db db :main-db db})
            tail-parts (:content (last (:messages w-on)))
            img-blocks (filterv #(= "image_url" (:type %)) tail-parts)
            cap-blocks (filterv #(= "text" (:type %)) tail-parts)]
        (testing "format-eval-output strips raw base64 and emits crisp telemetry"
          (is (not (str/includes? txt-single b64-1)))
          (is (str/includes? txt-single "Image frame [360px]: 360×167px · overflow-x=false"))
          (is (str/includes? txt-single (str u1)))
          (is (str/includes? txt-frames "Rendered 2 frames: 360x167 ok, 640x148 overflow-x"))
          (is (str/includes? txt-frames "Image frame [640px]: 640×148px · overflow-x=true"))
          (is (not (str/includes? txt-frames b64-2))))

        (testing "vision? true attaches native OpenAI image_url blocks with captions from DB refs"
          (is (= 2 (count (:ephemeral-eval-images w-on))))
          (is (= 2 (count img-blocks)))
          (is (= (str "data:image/jpeg;base64," b64-1) (get-in img-blocks [0 :image_url :url])))
          (is (= (str "data:image/png;base64," b64-2) (get-in img-blocks [1 :image_url :url])))
          (is (some #(str/includes? (:text %) "Rendered Hiccup preview [360px · 360×167 · overflowX=false]:") cap-blocks))
          (is (some #(str/includes? (:text %) "Rendered Hiccup preview [640px · 640×148 · overflowX=true]:") cap-blocks)))

        (testing ":seon.agent.eval/images ref on eval entity resolves into multimodal blocks"
          (is (= 1 (count (:ephemeral-eval-images w-evref))))
          (is (= b64-3 (:base64 (first (:ephemeral-eval-images w-evref))))))

        (testing "vision? false omits eval images, and older turns age out with stable prefix-hash"
          (is (empty? (:ephemeral-eval-images w-off)))
          (is (not (some #(and (sequential? (:content %)) (some (fn [p] (= "image_url" (:type p))) (:content %)))
                         (:messages w-off))))
          (is (empty? (:ephemeral-eval-images w-aged)))
          (is (not (some #(and (sequential? (:content %)) (some (fn [p] (= "image_url" (:type p))) (:content %)))
                         (:messages w-aged))))
          (is (= (:prefix-hash w-on) (:prefix-hash w-off) (:prefix-hash w-aged))))))))

(deftest render-preview-helper-and-server-browser-ops-test
  (with-test-store [{:keys [conn]}]
    (testing "(user/render-preview! ...) persists :seon.media/image entities and returns compact summary"
      (let [mock-render (fn [html {:keys [widths theme]}]
                          (is (str/includes? html "Hello preview"))
                          (is (= :dark theme))
                          {:ok? true
                           :widths widths
                           :frames (mapv (fn [w]
                                           {:seon.media/mime "image/jpeg"
                                            :seon.media/base64 (str "MOCKB64W" w)
                                            :seon.media/width w
                                            :seon.media/height 150
                                            :seon.media/label (str w "px")
                                            :seon.media/dom {:w w :h 150 :overflow-x? false}})
                                         widths)})
            res (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/prev}]
                  (helpers/render-preview! [:div {:class "card"} "Hello preview"]
                                           {:widths [360 640 1100] :theme :dark :render-fn mock-render}))
            res-fn (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/prev}]
                     (helpers/render-preview! (fn [m] {:seon.ui/hiccup [:div (:txt m)]})
                                              {:input {:txt "Hello preview"} :widths [360] :render-fn mock-render}))]
        (is (= :seon.media/frames (:seon/schema (meta res))))
        (is (= 3 (count (:seon.media/frames res))))
        (is (= "Rendered 3 frames: 360x150 ok, 640x150 ok, 1100x150 ok" (:summary res)))
        (is (every? #(nil? (:seon.media/base64 %)) (:seon.media/frames res)))
        (is (every? #(uuid? (:seon.media/id %)) (:seon.media/frames res)))
        (is (= 1 (count (:seon.media/frames res-fn))))
        (let [db @conn
              ids (mapv :seon.media/id (:seon.media/frames res))
              pulled (mapv #(d/pull db '[*] [:seon.media/id %]) ids)
              imgs (wire/extract-recent-eval-images db [{:turn 1 :calls [{:id "p1" :code "(user/render-preview! ...)" :value (pr-str res)}]}] 4)]
          (is (= ["MOCKB64W360" "MOCKB64W640" "MOCKB64W1100"] (mapv :seon.media/base64 pulled)))
          (is (= ["MOCKB64W360" "MOCKB64W640" "MOCKB64W1100"] (mapv :base64 imgs))))))

    (testing "optional real seon-browser capture when binary & standing server are available"
      (when (browser/default-binary-path)
        (let [live-up? (try (= 200 (:status (wire/http-get! "http://127.0.0.1:9998/" [])))
                            (catch Throwable _ false))]
          (when live-up?
            (let [res (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/real-prev}]
                        (helpers/render-preview! [:div {:class "card"} [:strong "Real WKWebView"]]
                                                 {:widths [360] :theme :dark :direct? true}))]
              (is (= 1 (count (:seon.media/frames res))))
              (let [fid (:seon.media/id (first (:seon.media/frames res)))
                    stored (d/pull @conn '[*] [:seon.media/id fid])]
                (is (pos? (count (str (:seon.media/base64 stored "")))))))))))

    (testing "server-proc routes :spawn-on-entity to :tx and handles :render-hiccup-frames non-blockingly"
      (let [in-ch (a/chan 4)
            st (server/server-proc {:in-ch in-ch :conn conn :cfg {} :repo-dir nil})
            rch (a/chan 1)
            [_ out-spawn] (server/server-proc st :ext {:req {:op :spawn-on-entity :focus-ref [:seon.system/id :root]} :reply-ch rch})
            rch-b (a/chan 1)
            [_ out-b] (server/server-proc st :ext {:req {:op :render-hiccup-frames :hiccup [:div "hi"]
                                                         :binary-path "/nonexistent/seon-browser"}
                                                   :reply-ch rch-b})
            ans-b (first (a/alts!! [rch-b (a/timeout 3000)]))]
        (is (= :spawn-on-entity (get-in out-spawn [:tx 0 :op])))
        (is (nil? out-b))
        (is (false? (:ok? ans-b)))
        (is (= :browser-unavailable (:error ans-b)))))))

(deftest spawn-on-entity-end-to-end-and-retract-safety-test
  (with-test-store [{:keys [conn cfg]}]
    (d/transact conn {:tx-data (code/file-import-tx @conn "src/demo/alpha.clj"
                                                    "(ns demo.alpha \"Alpha ns.\")\n(defn compute {:malli/schema [:=> [:cat :int] :int] :cost/ticks 10000} [x] (inc x))\n" false)})
    (let [err-id (random-uuid)]
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/role :coordinator :seon.agent/label "Root"}
                                  {:seon.task/id :task/demo-focus :seon.task/title "Demo Focus Task" :seon.task/scope 'demo.alpha :seon.task/plan "Do work" :seon.task/done? false}
                                  (serr/error-tx {:id err-id :kind :eval/demo-boom :severity :error :source :eval
                                                  :var 'demo.alpha/compute :message "Boom on compute"})
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                   :seon.user/view-mode :inspect
                                   :seon.user/inspect-ref [:seon.code.var/name 'demo.alpha/compute]
                                   :seon.user/inspect-trail [[:seon.system/id :root]]}]})
      (let [st0 (txr/transactor-proc {:conn conn :cfg cfg :repo-dir nil :commit-git? false :fork-out nil})
            r1 (txr-call! st0 {:op :spawn-on-entity :focus-ref [:seon.code.var/name 'demo.alpha/compute]
                               :prompt "Optimize compute for negative inputs" :role :worker :parent :user})]
        (testing "spawn-on-entity on a var derives scope, creates task, pins, targets, switches view, and survives retractEntity"
          (let [reply (:reply r1)
                aid (:agent reply)
                br (:branch reply)
                db @conn
                ag (d/pull db '[*] [:seon.agent/id aid])
                vs (rdata/user-view-state db)
                msgs (rdata/agent-messages-in db aid)
                var-eid (:e (first (d/datoms db :avet :seon.code.var/name 'demo.alpha/compute)))]
            (is (true? (:ok? reply)))
            (is (= "agent" (namespace aid)))
            (is (not= :main br))
            (is (contains? (set (d/branches conn)) br))
            (is (= 'demo.alpha (:seon.agent/scope ag)))
            (is (= var-eid (get-in ag [:seon.agent/target :db/id])))
            (is (= [[:seon.code.var/name 'demo.alpha/compute]] (:seon.agent/pinned ag)))
            (is (qualified-keyword? (:seon.agent/task ag)))
            (is (some? (:seon.task/id (d/pull db '[:seon.task/id] [:seon.task/id (:seon.agent/task ag)]))))
            (is (= 1 (count msgs)))
            (is (= :chat (:seon.agent.message/type (first msgs))))
            (is (= "Optimize compute for negative inputs" (:seon.agent.message/body (first msgs))))
            (is (= aid (:viewing-id vs)))
            (is (= :session (:view-mode vs)))
            (is (nil? (:inspect-ref vs)))
            (d/transact conn {:tx-data [[:db/retractEntity [:seon.code.var/name 'demo.alpha/compute]]]})
            (let [ag-after (d/pull @conn '[*] [:seon.agent/id aid])]
              (is (= aid (:seon.agent/id ag-after)))
              (is (= br (:seon.agent/branch ag-after)))
              (is (= [[:seon.code.var/name 'demo.alpha/compute]] (:seon.agent/pinned ag-after))))))

        (let [r2 (txr-call! (:st r1) {:op :spawn-on-entity :focus-ref [:seon.task/id :task/demo-focus]
                                      :prompt "Execute demo task" :role :worker :parent :user})
              r3 (txr-call! (:st r2) {:op :spawn-on-entity :focus-ref [:seon.error/id err-id]
                                      :prompt "Fix demo-boom" :role :worker :parent :user})
              r4 (txr-call! (:st r3) {:op :spawn-on-entity :focus-ref [:seon.system/id :root]
                                      :prompt "Audit system health" :role :worker :parent :user})]
          (testing "spawn-on-entity on task, error, and [:seon.system/id :root]"
            (let [ag-t (d/pull @conn '[*] [:seon.agent/id (:agent (:reply r2))])
                  ag-e (d/pull @conn '[*] [:seon.agent/id (:agent (:reply r3))])
                  ag-s (d/pull @conn '[*] [:seon.agent/id (:agent (:reply r4))])
                  vs-s (rdata/user-view-state @conn)]
              (is (true? (:ok? (:reply r2))))
              (is (= :task/demo-focus (:seon.agent/task ag-t)))
              (is (= 'demo.alpha (:seon.agent/scope ag-t)))
              (is (not= :main (:seon.agent/branch ag-t)))
              (is (some? (get-in ag-t [:seon.agent/target :db/id])))

              (is (true? (:ok? (:reply r3))))
              (is (= 'demo.alpha (:seon.agent/scope ag-e)))
              (is (some? (get-in ag-e [:seon.agent/target :db/id])))
              (is (= [[:seon.error/id err-id]] (:seon.agent/pinned ag-e)))

              (is (true? (:ok? (:reply r4))))
              (is (not= :main (:seon.agent/branch ag-s)))
              (is (some? (get-in ag-s [:seon.agent/target :db/id])))
              (is (= [[:seon.system/id :root]] (:seon.agent/pinned ag-s)))
              (is (= (:agent (:reply r4)) (:viewing-id vs-s))))))

        (testing "spawn-on-entity-form! callable action + Entity Explorer composer rendering"
          (is (true? (:seon/callable (meta #'web/spawn-on-entity-form!))))
          (let [call-res (actions/invoke-call! {:conn conn}
                                               {:call-form (list 'seon.web/spawn-on-entity-form! [:seon.task/id :task/demo-focus])
                                                :form-fields {:prompt "Ship it"}})
                task-html (render/hiccup->html
                           (inspector/render-inspector-body
                            {:main-db @conn :branch-db @conn :viewing-id :agent/root
                             :inspect-ref [:seon.task/id :task/demo-focus] :slot-budget {:avail-w 1000}}))
                agent-html (render/hiccup->html
                            (inspector/render-inspector-body
                             {:main-db @conn :branch-db @conn :viewing-id :agent/root
                              :inspect-ref [:seon.agent/id :agent/root] :slot-budget {:avail-w 1000}}))]
            (is (true? (:ok? call-res)))
            (is (= {:op :spawn-on-entity :focus-ref [:seon.task/id :task/demo-focus]
                    :prompt "Ship it" :role :worker :parent :user}
                   (get-in call-res [:async :req])))
            (is (str/includes? task-html "insp-spawn-composer"))
            (is (str/includes? task-html "Start an agent focused on Demo Focus Task…"))
            (is (str/includes? task-html "seon.web%2Fspawn-on-entity-form%21"))
            (is (not (str/includes? agent-html "insp-spawn-composer")))))))))

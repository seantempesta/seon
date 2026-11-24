(ns seon.derived-preview-test
  "Comprehensive proofs for derived viewport vision, HTTP 400 multimodal fallback,
   zero-DB-bloat in-memory LRU auto-preview, inspector live derived preview, agent
   viewport forwarding & auto-preview attachment, REPL shortcuts, and seed=42 generative checks."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.http-client :as http]
            [seon.agent :as agent]
            [seon.browser.preview :as preview]
            [seon.code :as code]
            [seon.code.files :as cfiles]
            [seon.code.graph :as graph]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as helpers]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.surface.inspector :as inspector]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(deftest multimodal-http-400-fallback-test
  (testing "strip-image-parts strips image_url parts and preserves plain messages"
    (let [msgs [{:role "system" :content "System"}
                {:role "user" :content [{:type "text" :text "Check card"}
                                        {:type "image_url" :image_url {:url "data:image/jpeg;base64,AA=="}}
                                        {:type "text" :text "DOM ok"}]}
                {:role "user" :content [{:type "image_url" :image_url {:url "data:image/png;base64,BB=="}}]}
                {:role "assistant" :content "Done"}]
          stripped (model/strip-image-parts msgs)]
      (is (= "System" (:content (nth stripped 0))))
      (is (= "Check card\nDOM ok" (:content (nth stripped 1))))
      (is (= "" (:content (nth stripped 2))))
      (is (= "Done" (:content (nth stripped 3))))))

  (testing "multimodal-unsupported-error? classifies HTTP 400/422 multimodal rejections accurately"
    (is (true? (model/multimodal-unsupported-error? 400 "{\"error\":\"unknown variant `image_url`, expected `text`\"}")))
    (is (true? (model/multimodal-unsupported-error? 400 "invalid content type for text model")))
    (is (true? (model/multimodal-unsupported-error? 422 "Model does not support image inputs")))
    (is (true? (model/multimodal-unsupported-error? 400 "vision not enabled")))
    (is (true? (model/multimodal-unsupported-error? 400 "multimodal payloads unsupported")))
    (is (false? (model/multimodal-unsupported-error? 400 "context_length_exceeded")))
    (is (false? (model/multimodal-unsupported-error? 500 "image_url error")))
    (is (false? (model/multimodal-unsupported-error? nil "image_url"))))

  (testing "openai-stream-chat! retries once text-only on HTTP 400 multimodal rejection"
    (let [calls (atom [])
          sse-ok "data: {\"choices\":[{\"delta\":{\"content\":\"OK text\"}}]}

data: [DONE]

"
          wire-msgs [{:role "system" :content "S"}
                     {:role "user" :content [{:type "text" :text "Inspect UI"}
                                             {:type "image_url" :image_url {:url "data:image/jpeg;base64,AAAA"}}]}]]
      (with-redefs [http/post (fn [url req]
                                (let [n (count (swap! calls conj [url (json/read-str (:body req) :key-fn keyword)]))]
                                  (if (= 1 n)
                                    {:status 400 :body "{\"error\":\"unknown variant `image_url`\"}"}
                                    {:status 200 :body (java.io.ByteArrayInputStream. (.getBytes sse-ok "UTF-8"))})))]
        (let [ans (model/openai-stream-chat! {:base-url "https://text-only.example.test" :model "text-only" :api-key "k"}
                                             nil [] "" wire-msgs nil)]
          (is (= 2 (count @calls)))
          (is (vector? (get-in (second (first @calls)) [:messages 1 :content])))
          (is (= "Inspect UI" (get-in (second (second @calls)) [:messages 1 :content])))
          (is (= "OK text" (:thought ans))))))))

(deftest derive-sample-input-three-tiers-test
  (with-test-store [{:keys [conn]}]
    (testing "Tier 1 (:db) pulls live matching entity from Datahike before examples or generation"
      (d/transact conn {:tx-data [{:seon.task/id :task/live-demo
                                   :seon.task/title "Live DB Task"
                                   :seon.task/done? false}]})
      (let [var-ent {:seon.code.var/name (quote seon.render.entities/render-task-card)
                     :seon.code.var/input [:seon.task/entity]
                     :seon.code.var/output [:seon.ui/hiccup]
                     :seon.code.var/schema [:=> {:examples [[[{:seon.task/entity {:seon.task/id :task/ex :seon.task/title "Ex"}}]
                                                             {:seon.ui/hiccup [:div "Ex"]}]]}
                                            [:cat [:map [:seon.task/entity :seon.task/entity]]]
                                            [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
            sample (preview/derive-renderer-sample-input @conn var-ent)]
        (is (= :db (:source sample)))
        (is (= :task/live-demo (get-in sample [:args 0 :seon.task/entity :seon.task/id])))
        (is (string? (:input-hash sample)))))

    (testing "Tier 2 (:example) uses Malli :examples when no matching DB entity exists"
      (let [var-ent {:seon.code.var/name (quote seon.render.status/render-status-chip)
                     :seon.code.var/input [:seon.ui.status/entity]
                     :seon.code.var/output [:seon.ui/hiccup]
                     :seon.code.var/schema [:=> {:examples [[[{:seon.ui.status/entity {:seon.ui.status/id :status/ex
                                                                                       :seon.ui.status/agent :agent/root
                                                                                       :seon.ui.status/label "Example Status"
                                                                                       :seon.ui.status/pct 50
                                                                                       :seon.ui.status/stage :researching}}]
                                                             {:seon.ui/hiccup [:span "Example Status"]}]]}
                                            [:cat [:map [:seon.ui.status/entity :seon.ui.status/entity]]]
                                            [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
            sample (preview/derive-renderer-sample-input @conn var-ent)]
        (is (= :example (:source sample)))
        (is (= "Example Status" (get-in sample [:args 0 :seon.ui.status/entity :seon.ui.status/label])))))

    (testing "Tier 3 (:generated) synthesizes deterministic seed=42 input when neither DB nor :examples exists"
      (let [var-ent {:seon.code.var/name (quote demo.ui/render-badge)
                     :seon.code.var/output [:seon.ui/hiccup]
                     :seon.code.var/schema [:=> [:cat [:map [:demo.ui/title :string] [:demo.ui/count :int]]]
                                            [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}
            s1 (preview/derive-renderer-sample-input @conn var-ent)
            s2 (preview/derive-renderer-sample-input @conn var-ent)]
        (is (= :generated (:source s1)))
        (is (= s1 s2) "seed=42 generation must be 100% deterministic")
        (is (string? (get-in s1 [:args 0 :demo.ui/title])))
        (is (integer? (get-in s1 [:args 0 :demo.ui/count])))))

    (testing "Returns nil when un-derivable (e.g. non-generatable :seon.host/db without examples)"
      (is (nil? (preview/derive-renderer-sample-input
                 @conn
                 {:seon.code.var/name (quote demo.ui/needs-db)
                  :seon.code.var/schema [:=> [:cat :seon.host/db] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}))))))

(deftest eval-preview-lru-and-zero-db-bloat-test
  (with-test-store [{:keys [conn]}]
    (preview/clear-preview-cache!)
    (d/transact conn {:tx-data [{:seon.task/id :task/lru-demo
                                 :seon.task/title "LRU Task Card"
                                 :seon.task/done? false}]})
    (let [db0 @conn
          tx0 (:max-tx db0)
          p1 (preview/eval-renderer-preview db0 (quote seon.render.entities/render-task-card))
          p2 (preview/eval-renderer-preview db0 (quote seon.render.entities/render-task-card))
          calls (atom 0)
          mock-browser (fn [html {:keys [widths]}]
                         (swap! calls inc)
                         {:ok? true
                          :widths widths
                          :frames (mapv (fn [w]
                                          {:seon.media/mime "image/jpeg"
                                           :seon.media/base64 (str "LRUFRAME" w)
                                           :seon.media/width w
                                           :seon.media/height 160
                                           :seon.media/label (str "task-card · " w "px")
                                           :seon.media/dom {:w w :h 160 :overflow-x? false}})
                                        widths)})
          f1 (preview/render-var-preview-frames! mock-browser db0 (quote seon.render.entities/render-task-card) {:widths [360 640] :theme :dark})
          f2 (preview/render-var-preview-frames! mock-browser db0 (quote seon.render.entities/render-task-card) {:widths [360 640] :theme :dark})]
      (is (true? (:ok? p1)))
      (is (= :db (:source p1)))
      (is (false? (:cached? p1)))
      (is (vector? (:hiccup p1)))
      (is (str/includes? (:html p1) "LRU Task Card"))
      (is (true? (:cached? p2)))
      (is (= (:html p1) (:html p2)))
      (is (true? (:ok? f1)))
      (is (false? (:cached? f1)))
      (is (= 2 (count (:frames f1))))
      (is (= "LRUFRAME360" (:base64 (first (:frames f1)))))
      (is (true? (:cached? f2)))
      (is (= 1 @calls) "Second render-var-preview-frames! must hit in-memory preview-lru")
      (is (= tx0 (:max-tx @conn)) "ZERO DB datom writes from derived preview!")
      (is (empty? (d/datoms @conn :aevt :seon.media/id)) "ZERO :seon.media/id rows written to LMDB!"))))

(deftest inspector-live-derived-preview-test
  (with-test-store [{:keys [conn]}]
    (preview/clear-preview-cache!)
    (let [ok-src (str "(ns seon.render.entities)\n"
                      "(defn render-task-card\n"
                      "  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}\n"
                      "  [m] (seon.render.entities/render-task-card m))\n")
          broken-ns (quote seon.demo.broken-preview)
          broken-sym (quote seon.demo.broken-preview/render-boom)]
      (create-ns broken-ns)
      (intern broken-ns (quote render-boom)
              (with-meta (fn [_] (throw (ex-info "intentional broken renderer" {:boom true})))
                         {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]]
                                         [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}))
      (try
        (d/transact conn {:tx-data (concat
                                    [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/label "Root"}
                                     {:seon.task/id :task/insp-demo :seon.task/title "Inspector Preview Task" :seon.task/done? false}]
                                    (code/file-import-tx @conn "src/seon/render/entities.clj" ok-src false)
                                    (code/file-import-tx @conn "src/seon/demo/broken_preview.clj"
                                                         (str "(ns seon.demo.broken-preview)\n"
                                                              "(defn render-boom {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [_] (throw (ex-info \"boom\" {})))\n")
                                                         false))})
        (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? false})})
        (let [db @conn
              tx-before (:max-tx db)
              ok-html (render/hiccup->html
                       (inspector/render-inspector-body
                        {:main-db db :branch-db db :viewing-id :agent/root
                         :inspect-ref [:seon.code.var/name (quote seon.render.entities/render-task-card)]
                         :slot-budget {:avail-w 1000}}))
              boom-html (render/hiccup->html
                         (inspector/render-inspector-body
                          {:main-db db :branch-db db :viewing-id :agent/root
                           :inspect-ref [:seon.code.var/name broken-sym]
                           :slot-budget {:avail-w 1000}}))]
          (is (str/includes? ok-html "insp-derived-preview"))
          (is (str/includes? ok-html "Live Derived Preview"))
          (is (str/includes? ok-html "live DB"))
          (is (str/includes? ok-html "Inspector Preview Task"))
          (is (str/includes? ok-html "Sample Input EDN"))
          (is (str/includes? boom-html "insp-derived-preview"))
          (is (str/includes? boom-html "Preview error:"))
          (is (str/includes? boom-html "intentional broken renderer"))
          (is (= tx-before (:max-tx @conn)) "Inspector preview writes 0 DB datoms"))
        (finally
          (remove-ns broken-ns))))))

(deftest agent-viewport-dims-and-auto-preview-wiring-test
  (with-test-store [{:keys [conn cfg]}]
    (preview/clear-preview-cache!)
    (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/viewport-screenshot? true}
                                {:seon.user/id :user/default :seon.user/viewport-w 430 :seon.user/viewport-h 932 :seon.user/theme :dark}
                                {:seon.agent/id :agent/ui-dev :seon.agent/branch :branch/ui-dev :seon.agent/goal "Polish card"}
                                {:seon.task/id :task/ui-1 :seon.task/title "Card Task" :seon.task/done? false}
                                {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/ui-dev
                                 :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                 :seon.agent.eval/code "(user/edit-fn! (quote seon.render.entities/render-task-card) \"a\" \"b\")"
                                 :seon.agent.eval/value "{:ok? true :target seon.render.entities/render-task-card}"}]})
    (let [src (str "(ns seon.render.entities)\n"
                   "(defn render-task-card\n"
                   "  {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}\n"
                   "  [m] (seon.render.entities/render-task-card m))\n")]
      (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/render/entities.clj" src false)})
      (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? false})}))
    (store/branch! conn :main :branch/ui-dev)
    (testing "Config export includes :seon.config/viewport-screenshot? true"
      (is (true? (store/config-val @conn nil :seon.config/viewport-screenshot?)))
      (is (str/includes? (str (cfiles/render-config-file @conn "resources/seon/config/default.edn"))
                         ":seon.config/viewport-screenshot? true")))
    (testing "handle-model-request forwards user viewport dims and auto-renders edited Hiccup renderer"
      (let [fork-out (a/chan 8)
            st0 (agent/agents-proc {:conn conn :cfg cfg :fork-out fork-out :fork-agent-in (a/chan 8)
                                    :max-os-procs 2 :browser-enabled? true :vision? true})
            req-msg {:ev :child-frame :agent-id :agent/ui-dev :pid 101
                     :frame {:ev :model-request :req-id "r-1" :branch :branch/ui-dev}}
            [st1 out1] (agent/agents-proc st0 :fork-ev req-msg)
            breqs (:browser-req out1)
            snap-req (some #(when (= :snapshot (:op %)) %) breqs)
            prev-req (some #(when (= :render-hiccup-frames (:op %)) %) breqs)]
        (is (= 430 (:width snap-req)) "Forwards :seon.user/viewport-w from :user/default")
        (is (= 932 (:height snap-req)) "Forwards :seon.user/viewport-h from :user/default")
        (is (some? prev-req) "Auto-triggers :render-hiccup-frames for edited :seon.ui/hiccup renderer")
        (is (str/includes? (:html prev-req) "Card Task"))
        (a/put! (:reply-ch prev-req)
                {:ok? true :widths [360 640]
                 :frames [{:seon.media/mime "image/jpeg" :seon.media/base64 "PREV360"
                           :seon.media/width 360 :seon.media/height 150
                           :seon.media/label "render-task-card · 360px" :seon.media/dom {:w 360 :h 150 :overflow-x? false}}]})
        (let [prev-ev (first (a/alts!! [(:preview-res-ch st1) (a/timeout 2000)]))
              [st2 out2] (agent/agents-proc st1 :preview-res prev-ev)
              [st3 out3] (agent/agents-proc st2 :browser-res
                                            {:req-id "r-1" :agent-id :agent/ui-dev
                                             :snapshot {:route "/" :width 430 :height 932 :mime "image/jpeg" :base64 "SNAP430" :dom {}}})
              wire-msgs (get-in out3 [:request 0 :wire-messages])
              tail-parts (:content (peek wire-msgs))
              img-urls (keep #(get-in % [:image_url :url]) tail-parts)]
          (is (nil? out2) "Waits until both snapshot and preview complete")
          (is (empty? (:pending-browser st3)))
          (is (some #(str/includes? % "SNAP430") img-urls))
          (is (some #(str/includes? % "PREV360") img-urls))
          (is (empty? (d/datoms @conn :aevt :seon.media/id)) "Zero DB image bloat from auto-preview"))))))

(deftest repl-shortcuts-and-seed42-generative-test
  (with-test-store [{:keys [conn]}]
    (preview/clear-preview-cache!)
    (d/transact conn {:tx-data [{:seon.task/id :task/repl-demo :seon.task/title "REPL Shortcut Task" :seon.task/done? false}]})
    (let [mock-r (fn [html {:keys [widths]}]
                   {:ok? true :widths widths
                    :frames (mapv (fn [w]
                                    {:seon.media/mime "image/jpeg" :seon.media/base64 (str "R" w)
                                     :seon.media/width w :seon.media/height 140
                                     :seon.media/label (str w "px") :seon.media/dom {:w w :h 140 :overflow-x? false}})
                                  widths)})]
      (testing "(user/render-preview! var-sym) auto-derives input and defaults to :persist? false"
        (let [tx0 (:max-tx @conn)
              res (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/repl-t}]
                    (helpers/render-preview! (quote seon.render.entities/render-task-card)
                                             {:widths [360] :render-fn mock-r}))]
          (is (false? (:persisted? res)))
          (is (= :db (:source res)))
          (is (= 1 (count (:seon.media/frames res))))
          (is (= tx0 (:max-tx @conn)) "0 DB writes on default :persist? false")))

      (testing "(user/ui-patch! ...) edits renderer, derives preview, and returns {:ok? :edit :preview :dom}"
        (let [demo-ns (quote seon.demo.ui-patch-target)
              demo-var (quote seon.demo.ui-patch-target/render-pill)
              init-src (str "(ns seon.demo.ui-patch-target)\n"
                            "(defn render-pill\n"
                            "  {:malli/schema [:=> {:examples [[[{:seon.task/entity {:seon.task/id :task/p :seon.task/title \"Pill\"}}] {:seon.ui/hiccup [:span \"Old\"]}]]}\n"
                            "                  [:cat [:map [:seon.task/entity :seon.task/entity]]]\n"
                            "                  [:map [:seon.ui/hiccup :seon.ui/hiccup]]]}\n"
                            "  [{t :seon.task/entity}]\n"
                            "  {:seon.ui/hiccup [:span {:class \"badge\"} \"Old: \" (:seon.task/title t)]})\n")]
          (try
            (create-ns demo-ns)
            (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/ui_patch_target.clj" init-src false)})
            (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/repl-t}]
              (let [res (helpers/ui-patch! demo-var "\"Old: \"" "\"New: \"" {:browser? false})]
                (is (true? (:ok? res)))
                (is (true? (get-in res [:edit :ok?])))
                (is (true? (get-in res [:preview :ok?])))
                (is (str/includes? (get-in res [:preview :html]) "New: REPL Shortcut Task"))
                (is (map? (:dom res)))
                (is (empty? (d/datoms @conn :aevt :seon.media/id)))))
            (finally
              (remove-ns demo-ns)))))

      (testing "seed=42 generative checks on all new public pure functions"
        (let [db @conn]
          (doseq [vsym [(quote seon.model/strip-image-parts)
                        (quote seon.model/multimodal-unsupported-error?)
                        (quote seon.browser.preview/hiccup-renderer-var?)
                        (quote seon.browser.preview/format-preview-summary)
                        (quote seon.browser.preview/attach-preview-frames-to-messages)]]
            (let [r (schema/stress-var db vsym {:samples 100 :seed 42})]
              (is (true? (:ok? r)) (str "seed=42 stress failed for " vsym ": " (pr-str r))))))))))

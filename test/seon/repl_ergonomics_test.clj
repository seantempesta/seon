(ns seon.repl-ergonomics-test
  "Proofs for native REPL aliases (`fs`/`proc`), live introspecting `(user/help)`,
   consistent `render-preview!` `:ok? true`, Turn-0 helper discoverability,
   smart scoped viewport vision gating, and `seon.code.reach/changed-form-ops`."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.agent :as agent]
            [seon.browser.preview :as preview]
            [seon.code.reach :as reach]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as helpers]
            [seon.health.static :as hstatic]
            [seon.render :as render]
            [seon.render.entities.work :as rwork]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(deftest repl-and-user-aliases-test
  (testing "install-agent-helpers! installs fs (jolt.fs) and proc (jolt.process) aliases in both repl and user"
    (helpers/install-agent-helpers!)
    (let [rns (find-ns 'repl)
          uns (find-ns 'user)
          ral (when rns (ns-aliases rns))
          ual (when uns (ns-aliases uns))]
      (is (some? rns))
      (is (= 'jolt.fs (some-> (get ral 'fs) ns-name)))
      (is (= 'jolt.process (some-> (get ral 'proc) ns-name)))
      (is (= 'jolt.fs (some-> (get ual 'fs) ns-name)))
      (is (= 'jolt.process (some-> (get ual 'proc) ns-name)))
      (is (true? (binding [*ns* rns] (eval '(fs/exists? "AGENTS.md")))))
      (is (true? (binding [*ns* rns] (eval '(var? #'proc/process))))))))

(deftest live-introspecting-help-test
  (testing "(seon.repl/help) dynamically discovers all installed user/* helpers with truthful metadata"
    (helpers/install-agent-helpers!)
    (let [listing (repl/help)
          callers-info (repl/help 'callers)
          preview-hits (repl/help "preview")
          ui-info (repl/help 'user/ui-patch!)
          miss-sym (repl/help 'no-such-helper-xyz)
          miss-any (repl/help 42)]
      (is (> (count listing) 60) "Must surface all installed helpers, not a stale 35-item list")
      (doseq [h '[render-preview! ui-patch! explore! similar set-status! clear-status!
                  context-trace diff-context rules put-rule! ask! await! answer! callers edit-fn!]]
        (is (contains? listing h) (str "Expected " h " in (user/help) listing"))
        (is (string? (get listing h))))
      (is (not (contains? listing 'ctx)) "Non-fn undocumented atom ctx should not clutter help listing")
      (is (= 'user/callers (:var callers-info)))
      (is (string? (:doc callers-info)))
      (is (str/includes? (:doc callers-info) ":seon.code.var/callers"))
      (is (not (str/includes? (:doc callers-info) "not maps")) "Stale callers lie must be gone")
      (is (some? (:arglists callers-info)))
      (is (some? (:malli/schema callers-info)))
      (is (contains? preview-hits 'render-preview!))
      (is (contains? preview-hits 'ui-patch!))
      (is (some? (get-in preview-hits ['render-preview! :malli/schema])))
      (is (= 'user/ui-patch! (:var ui-info)))
      (is (some? (:malli/schema ui-info)))
      (is (= :unknown-helper (:error miss-sym)))
      (is (= :unknown-helper (:error miss-any))))))

(deftest render-preview-ok-and-zero-writes-test
  (with-test-store [{:keys [conn]}]
    (preview/clear-preview-cache!)
    (d/transact conn {:tx-data [{:seon.task/id :task/prev-ok :seon.task/title "Preview OK Task" :seon.task/done? false}]})
    (let [mock-r (fn [_ {:keys [widths]}]
                   {:ok? true :widths widths
                    :frames (mapv (fn [w]
                                    {:seon.media/mime "image/jpeg" :seon.media/base64 (str "OK" w)
                                     :seon.media/width w :seon.media/height 120
                                     :seon.media/label (str "ok · " w "px") :seon.media/dom {:w w :h 120 :overflow-x? false}})
                                  widths)})
          tx0 (:max-tx @conn)
          res-eph (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/prev-t}]
                    (helpers/render-preview! 'seon.render.entities/render-task-card {:widths [480] :render-fn mock-r}))]
      (testing "Default ephemeral render-preview! returns :ok? true, :persisted? false, and 0 DB writes"
        (is (true? (:ok? res-eph)))
        (is (false? (:persisted? res-eph)))
        (is (= :seon.media/frames (:seon/schema (meta res-eph))))
        (is (= 1 (count (:seon.media/frames res-eph))))
        (is (= tx0 (:max-tx @conn))))
      (testing "Persisted render-preview! (:persist? true) returns :ok? true and :persisted? true"
        (let [res-per (binding [fcalls/*agent-ctx* {:conn conn :branch :main :agent-id :agent/prev-t}]
                        (helpers/render-preview! 'seon.render.entities/render-task-card {:widths [480] :render-fn mock-r :persist? true :no-cache? true}))]
          (is (true? (:ok? res-per)))
          (is (true? (:persisted? res-per)))
          (is (= :seon.media/frames (:seon/schema (meta res-per))))
          (is (> (:max-tx @conn) tx0)))))))

(deftest smart-viewport-vision-gating-test
  (testing "Pure should-capture-viewport? policy covers root, viewed, UI-editor, backend worker, and overrides"
    (is (true? (agent/should-capture-viewport? {:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :agent/root :viewing-agent :agent/other :has-ui-previews? false})))
    (is (true? (agent/should-capture-viewport? {:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :ns/seon.store :viewing-agent :ns/seon.store :has-ui-previews? false})))
    (is (true? (agent/should-capture-viewport? {:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :agent/ui-dev :viewing-agent :agent/root :has-ui-previews? true})))
    (is (false? (agent/should-capture-viewport? {:browser-enabled? true :global-enabled? true :agent-override nil :agent-id :ns/seon.store :viewing-agent :agent/root :has-ui-previews? false})))
    (is (false? (agent/should-capture-viewport? {:browser-enabled? true :global-enabled? true :agent-override false :agent-id :agent/root :viewing-agent :agent/root :has-ui-previews? true})))
    (is (true? (agent/should-capture-viewport? {:browser-enabled? true :global-enabled? false :agent-override true :agent-id :ns/seon.store :viewing-agent :agent/root :has-ui-previews? false})))
    (is (false? (agent/should-capture-viewport? {:browser-enabled? false :global-enabled? true :agent-override true :agent-id :agent/root :viewing-agent :agent/root :has-ui-previews? true}))))

  (testing "agents-proc :model-request skips WKWebView snapshot for non-viewed backend workers but captures root/viewed/overridden agents"
    (with-test-store [{:keys [conn cfg]}]
      (preview/clear-preview-cache!)
      (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/viewport-screenshot? true}
                                  {:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/goal "Root"}
                                  {:seon.agent/id :ns/seon.store :seon.agent/branch :branch/store-bg :seon.agent/goal "Backend"}
                                  {:seon.agent/id :ns/seon.opt-in :seon.agent/branch :branch/opt-in :seon.agent/goal "Opt-in" :seon.config/viewport-screenshot? true}
                                  {:seon.agent/id :agent/opt-out :seon.agent/branch :branch/opt-out :seon.agent/goal "Opt-out" :seon.config/viewport-screenshot? false}
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root] :seon.user/viewport-w 1280 :seon.user/viewport-h 820}]})
      (store/branch! conn :main :branch/store-bg)
      (store/branch! conn :main :branch/opt-in)
      (store/branch! conn :main :branch/opt-out)
      (let [st0 (agent/agents-proc {:conn conn :cfg cfg :fork-out (a/chan 8) :fork-agent-in (a/chan 8)
                                    :max-os-procs 2 :browser-enabled? true :vision? true})
            [_ out-bg] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :ns/seon.store :pid 1
                                                        :frame {:ev :model-request :req-id "bg-1" :branch :branch/store-bg}})
            [_ out-root] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :agent/root :pid 2
                                                          :frame {:ev :model-request :req-id "rt-1" :branch :main}})
            [_ out-in] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :ns/seon.opt-in :pid 3
                                                        :frame {:ev :model-request :req-id "in-1" :branch :branch/opt-in}})]
        (is (empty? (:browser-req out-bg)) "Non-viewed backend worker must incur 0 WKWebView snapshot requests")
        (is (= 1 (count (:request out-bg))) "Non-viewed backend worker proceeds directly to model request")
        (is (some #(= :snapshot (:op %)) (:browser-req out-root)) ":agent/root must capture live viewport snapshot")
        (is (some #(= :snapshot (:op %)) (:browser-req out-in)) "Explicit per-agent :viewport-screenshot? true must capture snapshot")
        (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :ns/seon.store]}]})
        (let [[_ out-viewed] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :ns/seon.store :pid 4
                                                             :frame {:ev :model-request :req-id "vw-1" :branch :branch/store-bg}})
              [_ out-opt-out] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :agent/opt-out :pid 5
                                                               :frame {:ev :model-request :req-id "oo-1" :branch :branch/opt-out}})]
          (is (some #(= :snapshot (:op %)) (:browser-req out-viewed)) "Currently viewed agent must capture live viewport snapshot")
          (is (empty? (:browser-req out-opt-out)) "Explicit per-agent :viewport-screenshot? false must skip snapshot"))))))

(deftest turn0-helpers-reach-and-seed42-test
  (testing "Turn-0 render-ctx-snapshot surfaces new REPL aliases and helpers"
    (let [txt (render/hiccup->text (:seon.ui/hiccup (rwork/render-ctx-snapshot {:seon.ctx/snapshot {:seon.agent/id :ag/t :seon.agent/branch :branch/t}})))]
      (doseq [tok ["jolt.fs" "jolt.process" "user/ui-patch!" "user/render-preview!" "user/explore!" "user/similar" "user/set-status!" "user/help"]]
        (is (str/includes? txt tok) (str "Turn-0 ctx snapshot should mention " tok)))))
  (testing "seed=42 generative checks & changed-form-ops commit-as-db path"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [vsym ['seon.agent/should-capture-viewport? 'seon.browser.preview/normalize-ephemeral-frame]]
          (let [r (schema/stress-var db vsym {:samples 100 :seed 42})]
            (is (true? (:ok? r)) (str "seed=42 stress failed for " vsym ": " (pr-str r)))))
        (let [cid0 (d/commit-id db)]
          (d/transact conn {:tx-data [{:seon.code.form/key "seon.demo/f1" :seon.code.form/ns 'seon.demo
                                       :seon.code.form/order 1 :seon.code.form/src "(defn f1 [] 1)"
                                       :seon.code.form/hash (store/sha256-hex "f1" 16) :seon.code.form/test? false}]})
          (let [cid1 (d/commit-id @conn)]
            (d/transact conn {:tx-data [{:seon.code.form/key "seon.demo/f2" :seon.code.form/ns 'seon.demo
                                         :seon.code.form/order 2 :seon.code.form/src "(defn f2 [] 2)"
                                         :seon.code.form/hash (store/sha256-hex "f2" 16) :seon.code.form/test? false}]})
            (let [ops-head (reach/changed-form-ops conn cid0 (d/commit-id @conn))
                  ops-past (reach/changed-form-ops conn cid0 cid1)]
              (is (= #{"seon.demo/f1" "seon.demo/f2"} (set (map :seon.code.form/key (:upserts ops-head)))))
              (is (= #{"seon.demo/f1"} (set (map :seon.code.form/key (:upserts ops-past)))))
              (is (empty? (hstatic/silent-catch-warnings @conn 'seon.code.reach))))))))))

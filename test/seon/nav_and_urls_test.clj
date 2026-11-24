(ns seon.nav-and-urls-test
  "End-to-end proofs for canonical clean URLs, `/` Home agreement, orthogonal `?tx=<n>`,
   universal Back/Forward navigation stack, Close/Explain exit fixes, User-Mode composer
   auto-eval gating, 1:1 browser history JS sync, and hot-merge auto-morph."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            
            [seon.render.links :as rlinks]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor.user :as txu]
            [seon.web :as web]
            [seon.web.actions :as actions]
            [seon.web.css :as wcss]
            [seon.web.router :as router]
            [seon.browser :as browser]
            [seon.render :as render]
            [seon.web.js :as wjs]))

(defn- run-show! [conn op]
  (let [{:keys [tx-data]} (txu/show-user-tx @conn op)]
    (d/transact conn {:tx-data tx-data})
    (ui/user-view-state @conn)))

(defn- run-action! [conn form & [fields]]
  (let [res (actions/invoke-call! {:conn conn} (cond-> {:call-form form} fields (assoc :form-fields fields)))]
    (is (:ok? res) (str "invoke-call! failed: " (pr-str res)))
    (when-let [req (get-in res [:async :req])]
      (case (:op req)
        :show-user (run-show! conn req)
        :transact (d/transact conn {:tx-data (:tx-data req)})
        nil))
    res))

(deftest ^{:cost/ticks 90000000} canonical-urls-home-agreement-and-tx-param-test
  (testing "`/` maps to :agent/root :canvas everywhere, clean canonical URLs round-trip cleanly with zero ugly encoding, and orthogonal `?tx=<n>` preserves every view mode"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main
                                   :seon.agent/router? true :seon.agent/label "Root"}
                                  {:seon.agent/id :ns/seon.web :seon.agent/parent :agent/root :seon.agent/branch :branch/web
                                   :seon.agent/label "Web"}
                                  {:seon.code.doc/id "docs/render.md#overview" :seon.code.doc/path "docs/render.md"
                                   :seon.code.doc/order 1 :seon.code.doc/heading "Overview" :seon.code.doc/body "Body"}
                                  {:seon.code.doc/id "AGENTS.md#0-start-here" :seon.code.doc/path "AGENTS.md"
                                   :seon.code.doc/order 1 :seon.code.doc/heading "Start" :seon.code.doc/body "Rules"}]})
      (let [db @conn
            home-op (router/path->show-user-op db "/")
            dl-op (web/parse-deep-link db "/")]
        ;; 1. `/` split-brain fixed: BOTH router and parse-deep-link agree 100%
        (is (= {:op :show-user :viewing :agent/root :mode :canvas :clear-inspect true :as-of-tx :live} home-op))
        (is (= home-op dl-op))
        (is (= :system (:mode (router/path->show-user-op db "/system"))))

        ;; 2. Clean canonical URLs emitted by user-state->path + exact round-trip
        (doseq [[vs expected-url exp-mode exp-viewing exp-tx]
                [[{:viewing-id :agent/root :view-mode :canvas} "/" :canvas :agent/root :live]
                 [{:viewing-id :agent/root :view-mode :session} "/chat" :session :agent/root :live]
                 [{:viewing-id :agent/root :view-mode :context} "/explain" :context :agent/root :live]
                 [{:viewing-id :agent/root :view-mode :system} "/system" :system nil :live]
                 [{:viewing-id :ns/seon.web :view-mode :canvas} "/agent/ns~seon.web" :canvas :ns/seon.web :live]
                 [{:viewing-id :ns/seon.web :view-mode :session} "/agent/ns~seon.web/chat" :session :ns/seon.web :live]
                 [{:viewing-id :ns/seon.web :view-mode :context} "/agent/ns~seon.web/explain" :context :ns/seon.web :live]
                 [{:viewing-id :agent/root :view-mode :canvas :as-of-tx 12345} "/?tx=12345" :canvas :agent/root 12345]
                 [{:viewing-id :agent/root :view-mode :session :as-of-tx 12345} "/chat?tx=12345" :session :agent/root 12345]
                 [{:viewing-id :agent/root :view-mode :system :as-of-tx 12345} "/system?tx=12345" :system nil 12345]
                 [{:viewing-id :ns/seon.web :view-mode :session :as-of-tx 12345} "/agent/ns~seon.web/chat?tx=12345" :session :ns/seon.web 12345]]]
          (let [u (router/user-state->path db vs)
                op (router/path->show-user-op db u)]
            (is (= expected-url u) (str "Unexpected canonical URL for " vs))
            (is (= exp-mode (:mode op)) (str "Mode mismatch for " u))
            (when exp-viewing (is (= exp-viewing (:viewing op)) (str "Viewing mismatch for " u)))
            (is (= exp-tx (:as-of-tx op)) (str "Tx mismatch for " u))))

        ;; 3. Clean Signal, Branch, Docs, and Var+tx URLs (zero ugly %2F/%23/%3A)
        (doseq [[ref expected-url]
                [[[:seon.signal/kind :health] "/signals/health"]
                 [[:seon.signal/kind :proof] "/signals/proof"]
                 [[:seon.signal/kind :flow] "/signals/flow"]
                 [[:seon.signal/kind :context] "/signals/context"]
                 [[:seon.signal/kind :error] "/signals/error"]
                 [[:seon.branch/name :main] "/branches/main"]
                 [[:seon.branch/name :branch/canvas-first-ui] "/branches/canvas-first-ui"]
                 [[:seon.code.doc/id "docs/render.md#overview"] "/docs/render.md?section=overview"]
                 [[:seon.code.doc/id "AGENTS.md#0-start-here"] "/docs/AGENTS.md?section=0-start-here"]]]
          (let [p (router/entity-ref->path db ref)
                op (router/path->show-user-op db p)
                p-tx (router/user-state->path db {:viewing-id :agent/root :view-mode :inspect :inspect-ref ref :as-of-tx 12345})
                op-tx (router/path->show-user-op db p-tx)]
            (is (= expected-url p) (str "Expected clean URL for " ref))
            (is (not (str/includes? p "%2F")))
            (is (not (str/includes? p "%23")))
            (is (not (str/includes? p "%3A")))
            (is (= ref (:inspect-ref op)))
            (is (= :inspect (:mode op)))
            (is (= ref (:inspect-ref op-tx)))
            (is (= 12345 (:as-of-tx op-tx)))
            (is (= :inspect (:mode op-tx)))))

        ;; 4. Var + ?tx=12345 and Doc + ?section + ?explain=1 + ?tx=12345
        (is (= "/var/seon.web/handler?tx=12345"
               (router/user-state->path db {:viewing-id :agent/root :view-mode :inspect
                                            :inspect-ref [:seon.code.var/name (quote seon.web/handler)] :as-of-tx 12345})))
        (let [doc-url (router/user-state->path db {:viewing-id :agent/root :view-mode :context
                                                   :inspect-ref [:seon.code.doc/id "docs/render.md#overview"] :as-of-tx 12345})
              doc-op (router/path->show-user-op db doc-url)]
          (is (= "/docs/render.md?section=overview&explain=1&tx=12345" doc-url))
          (is (= [:seon.code.doc/id "docs/render.md#overview"] (:inspect-ref doc-op)))
          (is (= :context (:mode doc-op)))
          (is (= 12345 (:as-of-tx doc-op))))

        ;; 5. Decode aliases still work cleanly (never emitted)
        (is (= :session (:mode (router/path->show-user-op db "/session"))))
        (is (= :context (:mode (router/path->show-user-op db "/context"))))
        (is (= :context (:mode (router/path->show-user-op db "/root/debug"))))
        (is (= :session (:mode (router/path->show-user-op db "/agent/ns~seon.web/session"))))
        (is (= [:seon.code.doc/id "docs/render.md#overview"]
               (:inspect-ref (router/path->show-user-op db "/doc/docs/render.md#overview"))))

        ;; 6. rlinks/ent-link passes db cleanly and dead rdata/agent-href & debug-href are removed
        (let [a (rlinks/ent-link db [:seon.signal/kind :health] "Healthy" "pill")]
          (is (= "/signals/health" (:href (second a)))))
        (is (nil? (ns-resolve (quote seon.render.data) (quote agent-href))))
        (is (nil? (ns-resolve (quote seon.render.data) (quote debug-href))))))))

(deftest ^{:cost/ticks 90000000} universal-back-forward-close-and-explain-return-test
  (testing "Universal Back/Forward works across Canvas -> Chat -> Entity1 -> Entity2 -> System including first-hop Back, Close never traps in :inspect, and Explain returns to origin mode"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/label "Root"}
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root] :seon.user/view-mode :canvas}]})
      (let [e1 [:seon.signal/kind :health]
            e2 [:seon.branch/name :main]]
        ;; Canvas -> Chat -> Entity 1 -> Entity 2 -> System
        (run-action! conn (quote (seon.web/set-view-mode! :session)))
        (is (= :session (:view-mode (ui/user-view-state @conn))))
        (is (true? (:nav-can-back? (ui/user-view-state @conn))))
        (run-action! conn (list (quote seon.web/inspect-entity!) e1))
        (let [vs (ui/user-view-state @conn)]
          (is (= e1 (:inspect-ref vs)))
          (is (= :inspect (:view-mode vs)))
          (is (true? (:nav-can-back? vs)) "First-hop Back from Entity 1 must be enabled!"))
        ;; First-hop Back test right from Entity 1 -> Chat!
        (run-action! conn (quote (seon.web/inspect-back!)))
        (let [vs (ui/user-view-state @conn)]
          (is (nil? (:inspect-ref vs)) "First-hop Back must retract :inspect-ref")
          (is (= :session (:view-mode vs)) "First-hop Back must restore Chat (:session)")
          (is (true? (:nav-can-forward? vs)))
          (is (= :back (:nav-op vs))))
        ;; Forward back to Entity 1, then push Entity 2 -> System
        (run-action! conn (quote (seon.web/inspect-forward!)))
        (is (= e1 (:inspect-ref (ui/user-view-state @conn))))
        (is (= :forward (:nav-op (ui/user-view-state @conn))))
        (run-action! conn (list (quote seon.web/inspect-entity!) e2))
        (run-action! conn (quote (seon.web/set-view-mode! :system)))
        (is (= :system (:view-mode (ui/user-view-state @conn))))
        (is (nil? (:inspect-ref (ui/user-view-state @conn))))

        ;; Walk Back 4 hops: System -> Entity 2 -> Entity 1 -> Chat -> Canvas
        (run-action! conn (quote (seon.web/inspect-back!)))
        (is (= e2 (:inspect-ref (ui/user-view-state @conn))))
        (is (= [e1] (:inspect-trail (ui/user-view-state @conn))))
        (run-action! conn (quote (seon.web/inspect-back!)))
        (is (= e1 (:inspect-ref (ui/user-view-state @conn))))
        (run-action! conn (quote (seon.web/inspect-back!)))
        (is (= :session (:view-mode (ui/user-view-state @conn))))
        (is (nil? (:inspect-ref (ui/user-view-state @conn))))
        (run-action! conn (quote (seon.web/inspect-back!)))
        (is (= :canvas (:view-mode (ui/user-view-state @conn))))
        (is (false? (:nav-can-back? (ui/user-view-state @conn))))

        ;; Walk Forward 2 hops: Canvas -> Chat -> Entity 1
        (run-action! conn (quote (seon.web/inspect-forward!)))
        (is (= :session (:view-mode (ui/user-view-state @conn))))
        (run-action! conn (quote (seon.web/inspect-forward!)))
        (is (= e1 (:inspect-ref (ui/user-view-state @conn))))
        (is (= :inspect (:view-mode (ui/user-view-state @conn))))

        ;; Close x exits :inspect back to previous non-inspect mode (:session), NEVER trapped on :inspect!
        (run-action! conn (quote (seon.web/inspect-close!)))
        (let [vs (ui/user-view-state @conn)]
          (is (nil? (:inspect-ref vs)))
          (is (= :session (:view-mode vs)))
          (is (not= :inspect (:view-mode vs))))

        ;; Enter Explain from Canvas -> Back to Explore returns to Canvas (NOT hardcoded :session!)
        (run-action! conn (quote (seon.web/set-view-mode! :canvas)))
        (run-action! conn (quote (seon.web/set-explain-lens! true)))
        (is (= :context (:view-mode (ui/user-view-state @conn))))
        (run-action! conn (quote (seon.web/set-explain-lens! false)))
        (is (= :canvas (:view-mode (ui/user-view-state @conn))) "Explain exit must return to Canvas, not hardcoded :session")

        ;; :nav-op :replace updates tx without spamming :nav-back; :nav-op :pop restores target cleanly
        (let [nb-before (count (:nav-back (ui/user-view-state @conn)))
              seq-before (:nav-seq (ui/user-view-state @conn))
              vs1 (run-show! conn {:op :show-user :as-of-tx 111 :nav-op :replace})]
          (is (= 111 (:as-of-tx vs1)))
          (is (= nb-before (count (:nav-back vs1))))
          (is (= :replace (:nav-op vs1)))
          (is (> (:nav-seq vs1) seq-before)))
        (run-action! conn (quote (seon.web/navigate-url! "/chat" {:nav-op :pop})))
        (let [vs-pop (ui/user-view-state @conn)]
          (is (= :session (:view-mode vs-pop)))
          (is (= :pop (:nav-op vs-pop))))))))

(deftest ^{:cost/ticks 90000000} user-mode-composer-history-js-hot-morph-and-seed42-test
  (testing "User-mode composer never auto-evals parens/emoticons, Dev mode enables clj-expr?, url-sync-js syncs 1:1 history, hot-merge auto-morphs web-proc, and seed=42 generative checks pass"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/label "Root"}
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root] :seon.user/view-mode :canvas}]})
      ;; 1. Normal User Mode (expert-mode? falsey): parens & emoticons go to :send-message, NOT :eval!
      (doseq [msg ["(quick question) how are you?" ":) thanks" "[draft] hello"]]
        (let [res (actions/invoke-call! {:conn conn} {:call-form (quote (seon.web/chat-agent-form! :agent/root))
                                                      :form-fields {:text msg}})]
          (is (:ok? res))
          (is (= :send-message (get-in res [:async :req :op])) (str "User mode must not auto-eval: " msg))
          (is (= msg (get-in res [:async :req :body])))))

      ;; 2. Developer Mode (:seon.user/expert-mode? true): Clojure forms dispatch :eval!
      (run-action! conn (quote (seon.web/toggle-expert-mode!)))
      (let [res (actions/invoke-call! {:conn conn} {:call-form (quote (seon.web/chat-agent-form! :agent/root))
                                                    :form-fields {:text "(+ 1 2)"}})]
        (is (:ok? res))
        (is (= :eval (get-in res [:async :req :op])))
        (is (= "(+ 1 2)" (get-in res [:async :req :code]))))
      ;; Toggle back off and test :lab-prefs {:expert-mode? true} fallback too
      (run-action! conn (quote (seon.web/toggle-expert-mode!)))
      (run-show! conn {:op :show-user :lab-prefs {:expert-mode? true}})
      (let [res (actions/invoke-call! {:conn conn} {:call-form (quote (seon.web/chat-agent-form! :agent/root))
                                                    :form-fields {:text "(+ 3 4)"}})]
        (is (= :eval (get-in res [:async :req :op]))))

      ;; 3. url-sync-js 1:1 browser history tokens
      (doseq [needle ["history.state" "data-nav-seq" "data-nav-op" "history.back" "history.forward"
                      "history.pushState" "history.replaceState" "seon.web/inspect-back!"
                      "seon.web/inspect-forward!" "{:nav-op :pop}"]]
        (is (str/includes? wcss/url-sync-js needle) (str "url-sync-js missing " needle)))

      ;; 4. Hot-merge auto-morph detection + web-proc :db [:merged ...] broadcast
      (is (true? (web/code-or-merge-commit? [:merged :branch/demo] [])))
      (is (true? (web/code-or-merge-commit? :code-merged [])))
      (is (true? (web/code-or-merge-commit? :transact [{:a :seon.code.form/src}])))
      (is (true? (web/code-or-merge-commit? :transact [{:a :seon.route/pattern}])))
      (is (false? (web/code-or-merge-commit? :send-message [{:a :seon.agent.message/body}])))
      (is (false? (web/code-or-merge-commit? nil nil)))
      (let [broadcasts (atom [])
            st0 (web/web-proc {:port 0 :conn conn :server-in-ch (a/chan 8)
                               :listener {:port 0 :set-page! (fn [_]) :broadcast! #(swap! broadcasts conj %)
                                          :tab-count (fn [] 1) :set-delegate! (fn [_])}})
            st-stale (assoc-in st0 [:walk :units "seon-view-unit" :html] "<div id=\"seon-view-unit\">stale</div>")
            [st1 _] (web/web-proc st-stale :db {:reason [:merged :branch/ui] :tx-data []})]
        (is (not= "<div id=\"seon-view-unit\">stale</div>" (get-in st1 [:walk :units "seon-view-unit" :html])))
        (is (some #(str/includes? % "seon-view-unit") @broadcasts)
            "Hot-merge [:merged ...] notice must re-render & broadcast SSE morph even with empty tx-data"))

      ;; 5. 100-sample seed=42 Malli generative checks on edited pure functions
      (let [db @conn]
        (doseq [sym (quote [seon.web.router/path->show-user-op
                            seon.web.router/entity-ref->path
                            seon.web.router/user-state->path
                            seon.web.router/match-path
                            seon.web.router/core-routes-tx
                            seon.web/code-or-merge-commit?
                            seon.transactor.user/norm-ref
                            seon.render.data/user-view-state
                            seon.web.actions/inspect-close!
                            seon.web.actions/inspect-back!
                            seon.web.actions/inspect-forward!])]
          (let [res (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (:ok? res)
                (str sym " failed seed=42 generative check: " (pr-str res)))))))))

(deftest ^{:cost/ticks 90000000} nav-ids-chat-scroll-and-headless-guard-test
  (testing "Header nav stable IDs, counted suppressPop url-sync-js, immediate/VT sticky-scroll-js, and headless viewport guard"
    (with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (router/core-routes-tx)})
      (d/transact conn {:tx-data [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/label "Root"}
                                  {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                   :seon.user/view-mode :canvas :seon.user/expert-mode? true}]})
      (let [h0 (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {} :active-count 0}))]
        (is (str/includes? h0 "id=\"hdr-nav-back\""))
        (is (str/includes? h0 "id=\"hdr-nav-fwd\"")))
      (run-action! conn '(seon.web/set-view-mode! :session))
      (run-action! conn '(seon.web/inspect-back!))
      (let [h1 (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {} :active-count 0}))]
        (is (str/includes? h1 "id=\"hdr-nav-back\""))
        (is (str/includes? h1 "id=\"hdr-nav-fwd\"")))
      (let [res (schema/stress-var @conn 'seon.browser/headless-url {:samples 100 :seed 42})]
        (is (:ok? res) (str "headless-url failed seed=42 check: " (pr-str res)))))

    ;; url-sync-js counted suppressPop (never boolean) + per-op fallback timers + rAF arm on datastar-fetch
    (doseq [needle ["suppressPop=0" "suppressPop+=1" "suppressPop>0" "suppressPop-=1"
                    "supTimers" "requestAnimationFrame(arm)" "history.back()" "history.forward()"]]
      (is (str/includes? wcss/url-sync-js needle) (str "url-sync-js missing " needle)))
    (is (not (str/includes? wcss/url-sync-js "suppressPop=false")))

    ;; sticky-scroll-js immediate init + load + activeViewTransition/datastar-fetch resnap + .ws-timeline/.stage-scroll + zero MutationObserver
    (doseq [needle ["document.readyState==='loading'" "window.addEventListener('load'"
                    "activeViewTransition" "updateCallbackDone" "datastar-fetch"
                    ".ws-timeline" ".stage-scroll" "#cv-pending,#cv-live-prose" ".composer-inp"]]
      (is (str/includes? wjs/sticky-scroll-js needle) (str "sticky-scroll-js missing " needle)))
    (is (not (str/includes? wjs/sticky-scroll-js "MutationObserver")))

    ;; viewport-sync-js headless guards + seon.browser/headless-url
    (doseq [needle ["window.__seonPreviewBusy" "#seon-headless" "seon-preview-stage"]]
      (is (str/includes? wjs/viewport-sync-js needle) (str "viewport-sync-js missing " needle)))
    (is (= "http://127.0.0.1:9998/#seon-headless" (browser/headless-url "http://127.0.0.1:9998/")))
    (is (= "http://127.0.0.1:9998/chat?tx=5#seon-headless" (browser/headless-url "http://127.0.0.1:9998/chat?tx=5")))
    (is (= "http://127.0.0.1:9998/#existing" (browser/headless-url "http://127.0.0.1:9998/#existing")))
    (is (= "" (browser/headless-url "")))))

(deftest ^{:cost/ticks 90000000} browser-snapshot-enrichment-and-auto-recover-test
  (testing "browser-proc :snapshot chains :snapshot-enrich-dom, falls back on timeout, auto-recovers Missing #seon-app, and restores size on :clear"
    (let [mk-mock (fn []
                    (let [sent (atom [])
                          st0 (browser/browser-proc {:port 0 :width 1280 :height 820})]
                      [(assoc st0 :url "http://127.0.0.1:9998/#seon-headless"
                                  :handle {:send! (fn [cmd] (swap! sent conj cmd) true) :stop! (fn [] nil)})
                       sent]))]
      ;; 1. Enriched snapshot merges visual defects & preserves Obj-C :dom keys
      (let [[st sent] (mk-mock)
            rch (a/chan 1)
            [st1 _] (browser/browser-proc st :req {:op :snapshot :req-id :r1 :agent-id :agent/root :reply-ch rch})
            c1 (first @sent)
            _ (is (= "snapshot" (:op c1)))
            [st2 out-mid] (browser/browser-proc st1 :ipc {:id (:id c1) :ok true :mime "image/jpeg" :width 1280 :height 820
                                                         :base64 "SNAP1" :dom {:viewing ":agent/root" :mode "canvas" :theme "dark"
                                                                               :overflowX false :svgCount 2 :url "/"}})
            c2 (last @sent)
            _ (is (nil? out-mid))
            _ (is (= "eval_js" (:op c2)))
            _ (is (str/includes? (:code c2) "visual-defects"))
            [st3 out3] (browser/browser-proc st2 :ipc {:id (:id c2) :ok true
                                                       :value {:viewport {:innerWidth 1280 :innerHeight 820 :docScrollW 1280 :docScrollH 820 :overflowX false}
                                                               :elements [{:selector "#seon-header" :w 1280 :h 48 :scroll-w 1280 :scroll-h 48 :client-w 1280 :client-h 48 :overflow-x? false :overflow-y? false :scrollable-y? false}]
                                                               :visual-defects []}})
            ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)
            dom (get-in ans [:snapshot :dom])]
        (is (true? (:ok? ans)))
        (is (= "SNAP1" (get-in ans [:snapshot :base64])))
        (is (= ":agent/root" (:viewing dom)))
        (is (= "dark" (:theme dom)))
        (is (= 2 (:svgCount dom)))
        (is (true? (:visual-ok? dom)))
        (is (= [] (:visual-defects dom)))
        (is (= 0 (:clipped-count dom)))
        (is (string? (:summary dom)))
        (is (= 1 (:snap-count st3)))
        (is (= (:snapshot ans) (:last-snap st3)))
        (is (= (:snapshot ans) (get-in out3 [:agents-res 0 :snapshot])))
        (is (= (:snapshot ans) (get-in out3 [:web-snap 0 :snapshot])))
        (is (nil? (:verify st3))))

      ;; 2. Enrichment timeout gracefully returns original snapshot
      (let [[st sent] (mk-mock)
            rch (a/chan 1)
            [st1 _] (browser/browser-proc st :req {:op :snapshot :req-id :r2 :agent-id :agent/root :reply-ch rch})
            c1 (first @sent)
            [st2 _] (browser/browser-proc st1 :ipc {:id (:id c1) :ok true :mime "image/jpeg" :width 1280 :height 820
                                                   :base64 "SNAP2" :dom {:url "/chat" :theme "dark"}})
            c2 (last @sent)
            [st3 out3] (browser/browser-proc st2 :timer {:ev :timeout :id (:id c2)})
            ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
        (is (true? (:ok? ans)))
        (is (= "SNAP2" (get-in ans [:snapshot :base64])))
        (is (= {:url "/chat" :theme "dark"} (get-in ans [:snapshot :dom])))
        (is (= 1 (:snap-count st3)))
        (is (= (:snapshot ans) (get-in out3 [:web-snap 0 :snapshot]))))

      ;; 3. Missing #seon-app triggers background verify in both :snapshot-enrich-dom and :measure-dom (once)
      (let [[st sent] (mk-mock)
            rch (a/chan 1)
            [st1 _] (browser/browser-proc st :req {:op :snapshot :reply-ch rch})
            c1 (first @sent)
            [st2 _] (browser/browser-proc st1 :ipc {:id (:id c1) :ok true :base64 "BOOT503" :dom {:url "/"}})
            c2 (last @sent)
            [st3 out3] (browser/browser-proc st2 :ipc {:id (:id c2) :ok true
                                                       :value {:viewport {:innerWidth 1280 :innerHeight 820 :docScrollW 1280 :docScrollH 820 :overflowX false}
                                                               :elements []
                                                               :visual-defects ["Missing #seon-app root (page may still be booting)"]}})
            ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)
            c3 (last @sent)]
        (is (true? (:ok? ans)))
        (is (false? (get-in ans [:snapshot :dom :visual-ok?])))
        (is (some? (:verify st3)))
        (is (= "eval_js" (:op c3)))
        (is (str/includes? (:code c3) "seon-app"))
        (is (seq (:clojure.core.async.flow/report out3))))

      (let [[st sent] (mk-mock)
            rch (a/chan 1)
            [st1 _] (browser/browser-proc st :req {:op :measure-dom :reply-ch rch})
            c1 (first @sent)
            [st2 out2] (browser/browser-proc st1 :ipc {:id (:id c1) :ok true
                                                       :value {:viewport {:innerWidth 1280 :innerHeight 820 :docScrollW 1280 :docScrollH 820 :overflowX false}
                                                               :elements []
                                                               :visual-defects ["Missing #seon-app root (page may still be booting)"]}})
            ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
        (is (false? (:ok? ans)))
        (is (some? (:verify st2)))
        (is (seq (:clojure.core.async.flow/report out2)))
        (let [n0 (count @sent)
              rch2 (a/chan 1)
              [st3 _] (browser/browser-proc st2 :req {:op :measure-dom :reply-ch rch2})
              c2 (last @sent)
              [st4 out4] (browser/browser-proc st3 :ipc {:id (:id c2) :ok true
                                                         :value {:viewport {:innerWidth 1280 :innerHeight 820 :docScrollW 1280 :docScrollH 820 :overflowX false}
                                                                 :elements []
                                                                 :visual-defects ["Missing #seon-app root (page may still be booting)"]}})]
          (is (= (:verify st2) (:verify st4)))
          (is (nil? out4))
          (is (= (+ n0 1) (count @sent)))))

      ;; 4. :render-hiccup-frames :clear phase restores WKWebView size
      (let [[st sent] (mk-mock)
            rch (a/chan 1)
            [st1 _] (browser/browser-proc st :req {:op :render-hiccup-frames :html "<div>x</div>" :widths [360] :reply-ch rch})
            c1 (last @sent)
            [st2 _] (browser/browser-proc st1 :ipc {:id (:id c1) :ok true :value {:slot-w 360 :w 360 :h 120 :scroll-w 360 :scroll-h 120 :overflow-x? false :overflow-y? false}})
            c2 (last @sent)
            [st3 _] (browser/browser-proc st2 :ipc {:id (:id c2) :ok true :mime "image/jpeg" :width 360 :height 220 :base64 "AAA=" :dom {:url "/"}})
            c3 (last @sent)
            [_ _] (browser/browser-proc st3 :ipc {:id (:id c3) :ok true :value {:cleared true :existed true}})
            ans (a/alt!! rch ([v] v) (a/timeout 500) :hung)]
        (is (true? (:ok? ans)))
        (is (some #(and (= "resize" (:op %)) (= 1280 (:width %)) (= 820 (:height %))) @sent))))))
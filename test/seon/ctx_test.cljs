(ns seon.ctx-test
  "Pure context formatting after database-value-pinned acquisition."
  (:require
    [cljs.test :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.config :as config]))

(deftest selected-blocks-are-ordinary-data
  (let [entity {:seon.agent/ctx
                [{:seon.agent.ctx/name :late
                  :seon.agent.ctx/priority 20
                  :seon.render/ai "late"}
                 {:seon.agent.ctx/name :early
                  :seon.agent.ctx/priority 10
                  :seon.render/ai "early"}]}
        blocks (ctx/selected-agent-blocks entity nil)]
    (is (= [:early :late] (mapv :seon.agent.ctx/name blocks)))
    (is (every? #(not (contains? % :seon.db/db)) blocks))))

(deftest acquired-context-formats-without-a-database
  (let [entity {:seon.agent.ctx/cache-breakpoint 10
                :seon.agent/ctx
                [{:seon.agent.ctx/name :stable
                  :seon.agent.ctx/priority 10
                  :seon.render/ai "stable"}
                 {:seon.agent.ctx/name :volatile
                  :seon.agent.ctx/priority 20
                  :seon.render/ai "volatile"}]}
        rendered (ctx/rendered-context-from-entity
                   {:seon.agent/entity entity
                    :seon.agent.ctx/selected-blocks
                    [{:seon.agent.ctx/name :stable
                      :seon.agent.ctx/priority 10
                      :seon.render/ai "stable"}
                     {:seon.agent.ctx/name :volatile
                      :seon.agent.ctx/priority 20
                      :seon.render/ai "volatile"}]})
        text (:seon.render/text rendered)]
    (is (str/includes? text "stable"))
    (is (str/includes? text ctx/stable-boundary))
    (is (str/includes? text "volatile"))
    (is (= [:stable :volatile]
           (mapv :seon.agent.ctx/name
                 (:seon.agent.ctx/rendered-blocks rendered))))))

(deftest profile-selects-and-overrides-stored-blocks
  (let [entity {:seon.agent/ctx
                [{:seon.agent.ctx/name :one
                  :seon.agent.ctx/priority 10
                  :seon.render/ai "stored"}
                 {:seon.agent.ctx/name :two
                  :seon.agent.ctx/priority 20
                  :seon.render/ai "two"}]}
        profile [{:seon.agent.ctx/name :one
                  :seon.agent.ctx/priority 5
                  :seon.render/ai "profile"}]
        rendered (ctx/rendered-context-from-entity
                   {:seon.agent/entity entity
                    :seon.agent.ctx/selected-blocks
                    [{:seon.agent.ctx/name :one
                      :seon.agent.ctx/priority 5
                      :seon.render/ai "profile"}]
                    :seon.agent.ctx/profile profile})]
    (is (str/includes? (:seon.render/text rendered) "profile"))
    (is (not (str/includes? (:seon.render/text rendered) "two")))
    (is (not (str/includes? (:seon.render/text rendered)
                            ctx/stable-boundary)))))

(deftest manifest-file-block-renders-fresh-and-omits-when-absent
  ;; The GENERAL manifest→file-block path (owner ruling 2026-07-20: KEEP):
  ;; a manifest block map carrying :seon.agent.ctx/file-path + the two
  ;; file-block render symbols survives the ONE decode
  ;; (config/resolve-agent-context) verbatim, renders the file FRESH each
  ;; render, orders by priority, and OMITS the section while the file is
  ;; absent (reactive, no fallback).
  (let [fs   (js/require "fs")
        cwd  (.cwd js/process)
        path "tmp/ctx-file-block-test.md"
        abs  (str cwd "/" path)]
    (.mkdirSync fs (str cwd "/tmp") #js {:recursive true})
    (.writeFileSync fs abs "# Notes\nremember the falsifier")
    (try
      (let [configuration
            {:seon.config/id config/cluster-config-id
             :seon.config/agent-context
             {:seon.agent/ctx
              [{:seon.agent.ctx/name      :notes
                :seon.agent.ctx/priority  30
                :seon.agent.ctx/file-path path
                :seon.render/ai   'seon.agent.ctx/file-block-ai
                :seon.render/html 'seon.agent.ctx/file-block-html}
               {:seon.agent.ctx/name     :tail
                :seon.agent.ctx/priority 100
                :seon.render/ai          "tail body"}]}}
            resolved (config/resolve-agent-context
                       "worker-fb" nil configuration)
            [fb tail] (:seon.agent/ctx resolved)
            ;; Mirror the async prompt owner: each symbol slot resolves to
            ;; its literal result before the pure assembly tail runs.
            render-selected
            (fn []
              (ctx/rendered-context-from-entity
                {:seon.agent/entity {:seon.agent/ctx []}
                 :seon.agent.ctx/selected-blocks
                 [(assoc fb :seon.render/ai
                         (ctx/file-block-ai {:seon.render/node fb}))
                  tail]}))]
        (testing "the decode preserves the file-block declaration verbatim"
          (is (= path (:seon.agent.ctx/file-path fb)))
          (is (= 'seon.agent.ctx/file-block-ai (:seon.render/ai fb)))
          (is (= 'seon.agent.ctx/file-block-html (:seon.render/html fb))))
        (testing "file present → section renders, priority-ordered"
          (let [rendered (render-selected)
                text     (:seon.render/text rendered)]
            (is (str/includes? text "; remember the falsifier")
                "file content arrives `;`-quoted in the prompt")
            (is (= [:notes :tail]
                   (mapv :seon.agent.ctx/name
                         (:seon.agent.ctx/rendered-blocks rendered))))
            (is (< (str/index-of text "remember the falsifier")
                   (str/index-of text "tail body"))
                "priority 30 renders before priority 100")))
        (testing "file edited → next render re-reads fresh"
          (.writeFileSync fs abs "an edited line")
          (is (str/includes? (:seon.render/text (render-selected))
                             "; an edited line")))
        (testing "file absent → section omitted, no fallback"
          (.unlinkSync fs abs)
          (let [rendered (render-selected)]
            (is (= [:tail]
                   (mapv :seon.agent.ctx/name
                         (:seon.agent.ctx/rendered-blocks rendered))))
            (is (not (str/includes? (:seon.render/text rendered) "notes"))))))
      (finally
        (when (.existsSync fs abs) (.unlinkSync fs abs))))))

(deftest split-context-without-boundary-is-all-volatile
  (is (= {:seon.render/stable-text ""
          :seon.render/volatile-text "hello"}
         (ctx/split-context "hello"))))

(deftest chain-keys-diverge-after-first-change
  (let [a [{:seon.render/text "a"} {:seon.render/text "b"}]
        b [{:seon.render/text "a"} {:seon.render/text "c"}]
        ka (:seon.agent.ctx/chain-hashes
             (ctx/block-chain-keys {:seon.agent.ctx/blocks a
                                    :seon.agent/id "agent"}))
        kb (:seon.agent.ctx/chain-hashes
             (ctx/block-chain-keys {:seon.agent.ctx/blocks b
                                    :seon.agent/id "agent"}))]
    (is (= (first ka) (first kb)))
    (is (not= (second ka) (second kb)))))

(deftest system-text-has-no-local-database-injection
  (is (not (str/includes? ctx/system-text "db/*conn*")))
  (is (not (str/includes? ctx/system-text ":seon.db/db")))
  (is (not (str/includes? ctx/system-text ":seon.db/ok?")))
  (is (str/includes? ctx/system-text ":seon.error/message")))

(deftest incomplete-eval-row-remains-renderable
  ;; A partially assembled or historical row may not yet carry `ok?`.
  ;; Rendering context must keep that absence inside ordinary data instead of
  ;; violating cap-result's boolean contract and retiring the execution child.
  (is (str/includes?
        (ctx/format-eval-row
          {:seon.eval/id "pending-eval"
           :seon.eval/source "(+ 1 2)"}
          false)
        "(+ 1 2)")))

(deftest interrupted-eval-row-explains-the-one-fresh-child-recovery
  (let [rendered
        (ctx/format-eval-row
         {:seon.eval/id "interrupted-eval"
          :seon.eval/status :interrupted
          :seon.eval/source "(loop [] (recur))"
          :seon.eval/ok? false
          :seon.runtime.recovery/_eval
          [{:seon.runtime.recovery/id "recovery-123"
            :seon.runtime.recovery/detail "deadline exceeded"
            :seon.runtime.recovery/diagnostic-blob
            {:my.blob/hash "sha256-diagnostic"}}]}
         true)]
    (is (str/includes? rendered "execution child stopped"))
    (is (str/includes? rendered "scratch definitions were discarded"))
    (is (str/includes? rendered "dead result handles are omitted"))
    (is (str/includes? rendered
                       "Committed database facts and program definitions remain"))
    (is (str/includes? rendered
                       "reloads the current functions, schemas, and tests"))
    (is (str/includes? rendered "Automatic recovery runs once"))
    (is (str/includes? rendered "deadline exceeded"))
    (is (str/includes? rendered "recovery recovery-123"))
    (is (str/includes? rendered "evidence blob sha256-diagnostic"))
    (is (not (str/includes? rendered "result/interrupted-eval")))))

(deftest context-transactions-classify-native-database-results
  (is (= {::ctx/ok? false
          ::ctx/error "install! transact failed: writer unavailable"}
         (@#'ctx/transaction-result
           "install!" [:doctrine]
           {:seon.error/message "writer unavailable"
            :seon.error/kind :system})))
  (is (= {::ctx/ok? true ::ctx/names [:doctrine]}
         (@#'ctx/transaction-result
           "install!" [:doctrine]
           {:db-before {} :db-after {} :tx-data []}))))

(ns seon.cite-test
  "Tests for `seon.cite` atomic citation extraction, live `:seon.code.doc/references` linking,
   stale-ref retraction, backfill, stale citation audit, and `:rule.expand/doc-by-ref`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cite :as cite]
            [seon.code :as code]
            [seon.rule :as rules]
            [seon.schema :as schema]
            [seon.test-fixture :refer [with-test-store]]))

(deftest parse-citations-and-generative-seed-test
(testing "Extracts vars, namespaces, schemas, and doc links while ignoring prose, URLs, flags, and globs"
  (let [sample (str "Prose mentions seon.code/eval-forms, seon.rule, and :seon.task/id without backticks.\n"
                    "Use `seon.code/eval-forms`, `seon.main/-main`, `seon.fork.calls/*agent-ctx*`, "
                    "`seon.render/hiccup->html`, ```seon.rule```, `:seon.task/id`, `:seon.code.doc/references`, "
                    "`docs/agent.md#top`, [Render](docs/render.md#sec), [Plan](docs/plan.md), "
                    "and [Principles](../AGENTS.md#0-start-here).\n"
                    "Ignore `http://127.0.0.1:9998/`, `https://example.com/seon.code/foo`, "
                    "[ext](https://example.com/docs/render.md), ![img](docs/img.md), "
                    "`--model-base-url`, `-eu`, `:seon.task/*`, `seon.health.*`, `docs/*.md`, "
                    "`docs/<file>.md#<slug>`, `result/e<n>`, `src/seon/code/io.clj`, "
                    "`resources/seon/rules.edn`, `bin/seon`, `deps.edn`, `0.26.0`, and `0/3`.\n"
                    "```clojure\n(seon.fake/in-fence :seon.fake/ignored)\n```")
        res (cite/parse-citations sample)]
    (is (= #{'seon.code/eval-forms 'seon.main/-main
             'seon.fork.calls/*agent-ctx* 'seon.render/hiccup->html}
           (:vars res)))
    (is (= #{'seon.rule} (:nss res)))
    (is (= #{:seon.task/id :seon.code.doc/references} (:schemas res)))
    (is (= #{"docs/agent.md#top" "docs/render.md#sec" "docs/plan.md" "AGENTS.md#0-start-here"}
           (:docs res)))
    (is (= {:vars #{} :nss #{} :schemas #{} :docs #{}}
           (cite/parse-citations "   \n  ")))))

(testing "100-sample seed=42 generative check passes on random strings"
  (let [res (schema/stress-var nil 'seon.cite/parse-citations {:samples 100 :seed 42})]
    (is (:ok? res) (pr-str res))
    (is (= 101 (:ran res))))))

(deftest file-import-tx-links-and-retracts-doc-refs-test
  (with-test-store [{:keys [conn]}]
    (let [{:keys [entity dh-decls]}
          (schema/validate-schema-registration! @conn {:key :seon.demo.cite/item :form :string})]
      (when (seq dh-decls) (d/transact conn {:tx-data dh-decls}))
      (d/transact conn {:tx-data [entity]}))
    (d/transact conn {:tx-data (code/file-import-tx
                                @conn "src/seon/demo/cite_a.clj"
                                "(ns seon.demo.cite-a)\n(defn alpha [x] x)\n(defn beta [x] (inc x))\n" false)})
    (d/transact conn {:tx-data (code/file-import-tx
                                @conn "src/seon/demo/cite_b.clj"
                                "(ns seon.demo.cite-b)\n(defn gamma [x] x)\n" false)})

    (let [md-v1 (str "# Guide\n\nUses `seon.demo.cite-a/alpha`, `seon.demo.cite-b`, "
                     "`:seon.demo.cite/item`, and `seon.demo.cite-a/missing-fn`.\n")
          _ (d/transact conn {:tx-data (code/file-import-tx @conn "docs/demo_cite.md" md-v1 false)})
          db1 @conn
          alpha-eid (:e (first (d/datoms db1 :avet :seon.code.var/name 'seon.demo.cite-a/alpha)))
          beta-eid  (:e (first (d/datoms db1 :avet :seon.code.var/name 'seon.demo.cite-a/beta)))
          nsb-eid   (:e (first (d/datoms db1 :avet :seon.code.ns/name 'seon.demo.cite-b)))
          sch-eid   (:e (first (d/datoms db1 :avet :seon.schema/key :seon.demo.cite/item)))
          resolved  (cite/resolve-doc-refs db1 md-v1)
          pull-refs #(into #{} (map :db/id)
                           (:seon.code.doc/references
                            (d/pull % '[{:seon.code.doc/references [:db/id]}]
                                    [:seon.code.doc/id "docs/demo_cite.md#guide"])))]
      (testing "resolve-doc-refs and file-import-tx link true :db.type/ref entities losslessly"
        (is (= #{alpha-eid nsb-eid sch-eid} (:refs resolved)))
        (is (= #{'seon.demo.cite-a/missing-fn} (:unresolved resolved)))
        (is (= #{alpha-eid nsb-eid sch-eid} (pull-refs db1)))
        (is (= md-v1 (code/render-path db1 "docs/demo_cite.md")))
        (is (= 1 (count (d/datoms db1 :avet :seon.code.doc/references alpha-eid)))))

      (testing "editing doc retracts stale :seon.code.doc/references and adds new refs"
        (let [md-v2 "# Guide\n\nNow uses `seon.demo.cite-a/beta` and `seon.demo.cite-b`.\n"
              _ (d/transact conn {:tx-data (code/file-import-tx @conn "docs/demo_cite.md" md-v2 false)})
              db2 @conn]
          (is (= #{beta-eid nsb-eid} (pull-refs db2)))
          (is (empty? (d/datoms db2 :avet :seon.code.doc/references alpha-eid)))
          (is (empty? (d/datoms db2 :avet :seon.code.doc/references sch-eid)))
          (is (= 1 (count (d/datoms db2 :avet :seon.code.doc/references beta-eid))))))

      (testing "removing all citations retracts every remaining reference"
        (let [md-v3 "# Guide\n\nPlain prose only.\n"
              _ (d/transact conn {:tx-data (code/file-import-tx @conn "docs/demo_cite.md" md-v3 false)})
              db3 @conn]
          (is (empty? (pull-refs db3)))
          (is (empty? (d/datoms db3 :avet :seon.code.doc/references beta-eid)))
          (is (empty? (d/datoms db3 :avet :seon.code.doc/references nsb-eid))))))))

(deftest backfill-stale-citations-and-cite-rules-test
  (with-test-store [{:keys [conn]}]
    (d/transact conn {:tx-data (code/file-import-tx
                                @conn "src/seon/demo/backfill.clj"
                                "(ns seon.demo.backfill)\n(defn live-fn [x] x)\n" false)})
    ;; Seed doc entities directly without enrich-doc-sections + add one bogus ref to test stale retraction
    (d/transact conn {:tx-data [{:seon.code.doc/id "docs/bf.md#good"
                                 :seon.code.doc/path "docs/bf.md"
                                 :seon.code.doc/order 0
                                 :seon.code.doc/heading "# Good\n"
                                 :seon.code.doc/body "Uses `seon.demo.backfill/live-fn`, `seon.demo.backfill`, and `:seon.task/id`.\n"
                                 :seon.code.doc/references #{[:seon.schema/key :seon.task/title]}}
                                {:seon.code.doc/id "docs/bf.md#stale"
                                 :seon.code.doc/path "docs/bf.md"
                                 :seon.code.doc/order 1
                                 :seon.code.doc/heading "## Stale\n"
                                 :seon.code.doc/body "Cites `seon.demo.backfill/deleted-fn` and `:seon.demo.backfill/gone-schema`.\n"}]})
    (let [bf-tx (cite/backfill-doc-refs-tx @conn)]
      (is (seq bf-tx))
      (d/transact conn {:tx-data bf-tx}))

    (let [db @conn
          fn-eid    (:e (first (d/datoms db :avet :seon.code.var/name 'seon.demo.backfill/live-fn)))
          ns-eid    (:e (first (d/datoms db :avet :seon.code.ns/name 'seon.demo.backfill)))
          tid-eid   (:e (first (d/datoms db :avet :seon.schema/key :seon.task/id)))
          title-eid (:e (first (d/datoms db :avet :seon.schema/key :seon.task/title)))
          good-eid  (:e (first (d/datoms db :avet :seon.code.doc/id "docs/bf.md#good")))
          good-refs (into #{} (map :db/id)
                          (:seon.code.doc/references
                           (d/pull db '[{:seon.code.doc/references [:db/id]}] good-eid)))
          stale (cite/stale-doc-citations db)]
      (testing "backfill-doc-refs-tx links expected refs, retracts stale ones, and is idempotent"
        (is (= #{fn-eid ns-eid tid-eid} good-refs))
        (is (not (contains? good-refs title-eid)))
        (is (empty? (cite/backfill-doc-refs-tx db))))

      (testing "stale-doc-citations flags deleted/nonexistent seon.* vars and schemas"
        (is (= 1 (count stale)))
        (is (= "docs/bf.md#stale" (:doc-id (first stale))))
        (is (= ['seon.demo.backfill/deleted-fn] (:missing-vars (first stale))))
        (is (= [:seon.demo.backfill/gone-schema] (:missing-schemas (first stale)))))

      (testing "cite-rules discover docs linked via :seon.code.doc/references in seon.rules/step-rules"
        (let [base-wm (rules/add-facts (rules/empty-wm)
                                       [[fn-eid :ctx/kind :seon.code.var/entity]
                                        [ns-eid :ctx/kind :seon.code.ns/entity]]
                                       {:rule :seed :stratum 0})
              res (rules/step-rules nil base-wm {:main db :branch db :code db} (cite/cite-rules) nil)
              wm (:wm res)]
          (is (= 1 (:recomputed res)))
          (is (= 2 (get-in res [:runs :rule.expand/doc-by-ref :fired])))
          (is (= #{good-eid} (rules/wm-vals wm fn-eid :rel/cited-by-doc)))
          (is (= #{good-eid} (rules/wm-vals wm ns-eid :rel/cited-by-doc)))
          (is (= :seon.code.doc/entity (rules/wm-scalar wm good-eid :ctx/kind)))
          (is (= "docs/bf.md#good" (rules/wm-scalar wm good-eid :seon.code.doc/id)))
          (is (contains? (rules/wm-vals wm good-eid :view/tag) :tag/doc-ref)))))))

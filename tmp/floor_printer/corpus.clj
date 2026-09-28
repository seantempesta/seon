(ns tmp.floor-printer.corpus
  "About 200 real values that flow through Seon today, collected read-only from
  default. `(corpus conn)` answers [{:label :category :value}]. Disposable probe
  (tmp/), evaluated into a scratch namespace of default's JVM."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.value]
            [seon.config :as config]))

(defn- sample
  "Up to n entity ids holding `attribute`, spread over the index."
  [database attribute n]
  (let [eids (mapv :e (take 4000 (d/datoms database :avet attribute)))
        step (max 1 (quot (count eids) n))]
    (vec (take n (take-nth step eids)))))

(defn- pulls
  [database category attribute n selector]
  (for [eid (sample database attribute n)]
    {:label (str category " " eid) :category category
     :value (db/pull database selector eid)}))

(defn- thrown [f] (try (f) nil (catch Throwable t t)))

(defn corpus
  [conn]
  (let [database @conn
        cfg (config/effective database "default")
        eval-eids (sample database :seon.cluster.eval/id 12)
        fn-sym (fn [eid] (:seon.fn/sym (db/pull database [:seon.fn/sym] eid)))]
    (vec
     (concat
      ;; REPL replies and settled results (evaluation entities: source, shown text, evidence)
      (pulls database "evaluation" :seon.cluster.eval/id 12 '[*])
      [{:label "reply map" :category "reply"
        :value {:seon.repl/value {:a 1 :b [1 2 3]} :result 'result/eebc560a4e54a :ms 8}}
       {:label "reply with error" :category "reply"
        :value {:seon.repl/value nil :seon.repl/error (ex-info "boom" {:k 1}) :result 'result/e0 :ms 3}}]
      ;; turns, agents, messages
      (pulls database "turn" :seon.turn/id 10 '[*])
      (pulls database "agent" :seon.agent/id 10 '[*])
      (pulls database "agent nested" :seon.agent/id 3 '[* {:seon.agent/config [*]}])
      (pulls database "message" :seon.message/id 10 '[*])
      ;; program entities
      (pulls database "function" :seon.fn/sym 20 '[*])
      (pulls database "schema" :seon.schema/key 12 '[*])
      (pulls database "namespace" :seon.ns/name 6 '[*])
      (pulls database "fn file" :seon.fn.file/relative-path 4 '[*])
      ;; tests
      (pulls database "test" :seon.test/sym 10 '[*])
      (pulls database "test run" :seon.test.run/id 10 '[*])
      (pulls database "test report" :seon.test.report/id 10 '[*])
      ;; errors
      (pulls database "error" :seon.error/id 8 '[*])
      (pulls database "error occurrence" :seon.error.occurrence/id 8 '[*])
      ;; tasks and issues
      (pulls database "task" :seon.task/id 8 '[*])
      (pulls database "issue" :seon.issue/id 8 '[*])
      (pulls database "lint" :seon.lint/id 4 '[*])
      [;; live errors
       {:label "ex-info chain" :category "throwable"
        :value (ex-info "outer" {:seon.error/operation 'x/y :args [1 2 3]}
                        (ex-info "middle" {:deep {:a {:b {:c (range 50)}}}}
                                 (IllegalStateException. "root cause")))}
       {:label "schema attribute error" :category "throwable"
        :value (thrown #(d/datoms database :avet :no.such/attribute))}
       {:label "arity exception" :category "throwable" :value (thrown #((fn [a] a)))}
       {:label "NPE" :category "throwable" :value (thrown #(.length ^String (identity nil)))}
       {:label "db invalid read" :category "error value"
        :value (db/q database '[:find ?x :where [?x :no.such/attr]])}
       {:label "pull invalid" :category "error value" :value (db/pull database '[*] [:no.such/id "x"])}
       ;; database values
       {:label "db value" :category "db" :value database}
       {:label "entity" :category "db" :value (d/entity database (first eval-eids))}
       {:label "tx report (d/with)" :category "db"
        :value (d/with database [{:db/id -1 :seon.lint/id "floor-printer-probe"}])}
       {:label "query: all fn syms" :category "query"
        :value (db/q database '[:find ?s :where [_ :seon.fn/sym ?s]])}
       {:label "query: fn sym + doc" :category "query"
        :value (db/q database '[:find ?s ?d :where [?e :seon.fn/sym ?s] [?e :seon.fn/doc ?d]])}
       {:label "query: scalar count" :category "query"
        :value (db/q database '[:find (count ?e) . :where [?e :seon.fn/sym]])}
       {:label "datoms seq" :category "db" :value (take 500 (d/datoms database :eavt))}
       {:label "schema map" :category "db" :value (:schema database)}
       {:label "pull many fns" :category "query"
        :value (mapv #(db/pull database '[*] %) (sample database :seon.fn/sym 40))}
       {:label "35 messages" :category "query"
        :value (mapv #(db/pull database '[*] %) (sample database :seon.message/id 35))}
       {:label "fn files" :category "query"
        :value (mapv #(db/pull database '[*] %) (sample database :seon.fn.file/relative-path 20))}
       {:label "fn syms vector" :category "query" :value (mapv fn-sym (sample database :seon.fn/sym 300))}
       {:label "config effective" :category "config" :value cfg}
       {:label "agent render profile" :category "config" :value (render/agent-render-profile cfg)}
       ;; long strings
       {:label "source file" :category "string" :value (slurp "src/seon/render/value.clj")}
       {:label "docstring" :category "string" :value (:doc (meta #'seon.render.value/prepare))}
       {:label "5M string" :category "string" :value (apply str (repeat 5000000 "x"))}
       {:label "multiline log" :category "string"
        :value (str/join "\n" (map #(str "line " % " of a log with some text") (range 400)))}
       ;; lazy and infinite
       {:label "range infinite" :category "lazy" :value (range)}
       {:label "iterate" :category "lazy" :value (iterate #(* 2 %) 1)}
       {:label "lazy map of maps" :category "lazy" :value (map (fn [i] {:i i :sq (* i i)}) (range))}
       {:label "200k vector" :category "big" :value (vec (range 200000))}
       {:label "200k set" :category "big" :value (set (range 200000))}
       {:label "deep nesting 500" :category "big"
        :value (reduce (fn [acc i] {:level i :child acc}) {} (range 500))}
       {:label "wide map 500 keys" :category "big"
        :value (into {} (map (fn [i] [(keyword "k" (str i)) i])) (range 500))}
       {:label "table of 100 rows" :category "big"
        :value (mapv (fn [i] {:row/id i :row/name (str "name-" i) :row/tags #{:a :b}
                              :row/body (apply str (repeat 20 "lorem "))})
                     (range 100))}
       ;; host objects
       {:label "atom" :category "host" :value (atom {:a 1})}
       {:label "self atom" :category "host" :value (let [a (atom nil)] (reset! a a) a)}
       {:label "thread" :category "host" :value (Thread/currentThread)}
       {:label "java HashMap" :category "host" :value (java.util.HashMap. {"a" 1 "b" [1 2]})}
       {:label "byte array" :category "host" :value (byte-array 1000)}
       {:label "fn object" :category "host" :value inc}
       {:label "var" :category "host" :value #'seon.render.value/prepare}
       {:label "namespace" :category "host" :value (find-ns 'seon.render.value)}
       {:label "class" :category "host" :value String}
       {:label "file" :category "host" :value (java.io.File. "/tmp")}
       {:label "connection" :category "host" :value conn}
       {:label "sorted-map symbol keys" :category "host" :value (sorted-map 'b 2 'a 1)}
       {:label "java map in map" :category "host" :value {:m (java.util.TreeMap. {"z" 1})}}]))))

(ns seon.health.closure
  "Closed-world DB-fact AST verification for Seon's Flow graph (`check-flow-closure`)
   and Web/UI action surface (`check-ui-closure`), combined in `check-closure` (< 35 ms)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]))

(defn- parse-form-src [src]
  (when (and (string? src) (not (str/blank? src)))
    (let [s1 (if (str/includes? src "::")
               (str/replace src #"::([a-zA-Z0-9._-]+/)?([a-zA-Z0-9*+!_?<>=.-]+)" ":seon.auto/$2")
               src)
          s2 (if (str/includes? s1 "#'")
               (str/replace s1 #"#'([a-zA-Z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)" "(quote $1)")
               s1)]
      (try
        {:ok? true :ast (binding [*read-eval* false] (read-string s2))}
        (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))})))))

(defn- ast-of [src]
  (let [r (parse-form-src src)]
    (when (:ok? r) (:ast r))))

(defn- pull-form-src [db fkey]
  (when (and db (contains? (:schema db) :seon.code.form/key))
    (when-let [e (:e (first (d/datoms db :avet :seon.code.form/key fkey)))]
      (:v (first (d/datoms db :eavt e :seon.code.form/src))))))

(defn- case-dispatch-keys [ast]
  (reduce (fn [acc n]
            (if (and (seq? n) (= (quote case) (first n)))
              (reduce (fn [a [k _]]
                        (cond
                          (keyword? k) (conj a k)
                          (seq? k) (into a (filter keyword? k))
                          :else a))
                      acc (partition 2 (drop 2 n)))
              acc))
          #{} (tree-seq coll? seq ast)))

(defn- proc-ports-from-ast [ast]
  (when ast
    (when-let [desc (some #(when (and (map? %) (map? (:ins %)) (map? (:outs %))) %)
                          (tree-seq coll? seq ast))]
      {:ins (set (keys (:ins desc))) :outs (set (keys (:outs desc)))})))

(def ^:private flow-proc-forms
  {:server "seon.server/server-proc"
   :transactor "seon.transactor/transactor-proc"
   :reconciler "seon.reconciler/reconciler-proc"
   :agents "seon.agent/agents-proc"
   :browser "seon.browser/browser-proc"
   :model "seon.model/model-proc"
   :test "seon.test/test-proc"
   :git "seon.git-proc/git-proc"
   :web "seon.web/web-proc"
   :vectors "seon.vector.flow/vectors-proc"})

(defn- prob [cls subj msg]
  {:class cls :severity :error :subject subj :message msg})

(defn- check-topology-ports [procs conns]
  (if-not (and (seq procs) (seq conns))
    []
    (let [wired-outs (into #{} (map first) conns)
          unwired (for [[pid {:keys [outs]}] procs, o outs :when (not (contains? wired-outs [pid o]))]
                    (prob :flow-unwired-out-port (str pid "/" (name o))
                          (str "Declared Flow :outs port " [pid o] " is not wired in seon.main/flow-connections.")))
          bad-conns (mapcat (fn [[[sp so] [dp di]]]
                              (cond-> []
                                (and (contains? procs sp) (not (contains? (:outs (get procs sp)) so)))
                                (conj (prob :flow-unknown-port (str sp "/" (name so))
                                            (str "flow-connections references undeclared :outs port " [sp so])))
                                (and (contains? procs dp) (not (contains? (:ins (get procs dp)) di)))
                                (conj (prob :flow-unknown-port (str dp "/" (name di))
                                            (str "flow-connections references undeclared :ins port " [dp di])))))
                            conns)]
      (vec (concat unwired bad-conns)))))

(defn- extract-tx-ops-in-ast [ast]
  (reduce (fn [acc n]
            (if (and (map? n) (contains? n :tx))
              (reduce (fn [a m] (if (and (map? m) (keyword? (:op m))) (conj a (:op m)) a))
                      acc (tree-seq coll? seq (:tx n)))
              acc))
          #{} (tree-seq coll? seq ast)))

(defn- stream-handles-error? [ast]
  (boolean
   (and ast
        (some (fn [n]
                (or (and (seq? n) (= (quote =) (first n)) (some #{:error} (rest n)))
                    (and (seq? n) (= (quote case) (first n))
                         (some (fn [[k _]] (or (= :error k) (and (seq? k) (some #{:error} k))))
                               (partition 2 (drop 2 n))))))
              (tree-seq coll? seq ast)))))

(defn- has-tx-output-key? [ast]
  (boolean
   (and ast
        (some (fn [n]
                (or (and (map? n) (contains? n :tx))
                    (and (seq? n) (= (quote assoc) (first n)) (some #{:tx} (rest n)))))
              (tree-seq coll? seq ast)))))

(defn- extract-ev-out-child-evs [fork-ast]
  (reduce (fn [acc n]
            (if (and (seq? n) (>= (count n) 3)
                     (= (quote ev-out) (second n))
                     (map? (nth n 2))
                     (keyword? (:ev (nth n 2)))
                     (= (quote child-type) (:type (nth n 2))))
              (conj acc (:ev (nth n 2)))
              acc))
          #{} (tree-seq coll? seq fork-ast)))

(defn- callable-metadata-in-src? [src]
  (boolean (and (string? src)
                (str/includes? src ":seon/callable")
                (or (str/includes? src "^:seon/callable")
                    (re-find #"\{:[^}]*:seon/callable\s+true" src)))))

(defn- web-var-info [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/ns))
    {}
    (let [fk->src (reduce (fn [m dt]
                            (let [e (:e dt)
                                  fk (:v (first (d/datoms db :eavt e :seon.code.form/key)))
                                  src (:v (first (d/datoms db :eavt e :seon.code.form/src)))]
                              (if (and fk src) (assoc m fk src) m)))
                          {} (d/datoms db :avet :seon.code.form/ns (quote seon.web)))]
      (into {}
            (keep (fn [dt]
                    (let [e (:e dt)
                          vsym (:v (first (d/datoms db :eavt e :seon.code.var/name)))
                          fk (:v (first (d/datoms db :eavt e :seon.code.var/form)))
                          src (get fk->src fk)]
                      (when vsym [vsym {:callable? (callable-metadata-in-src? src) :form-key fk}]))))
            (d/datoms db :avet :seon.code.var/ns (quote seon.web))))))

(defn- action-arities [db sym]
  (when (and db (qualified-symbol? sym) (contains? (:schema db) :seon.code.var/name))
    (let [asym (symbol "seon.web.actions" (name sym))
          ve (:e (first (d/datoms db :avet :seon.code.var/name asym)))
          als (when ve (:v (first (d/datoms db :eavt ve :seon.code.var/arglists))))]
      (when (seq als)
        (into #{} (keep #(when (vector? %) (if (some (fn [x] (= (quote &) x)) %) :variadic (count %)))) als)))))

(defn- unwrap-qsym [x]
  (cond
    (qualified-symbol? x) x
    (and (seq? x) (= (quote quote) (first x)) (qualified-symbol? (second x))) (second x)
    :else nil))

(defn- inspect-ui-handler [attr h]
  (cond
    (and (seq? h) (= (quote quote) (first h)) (seq? (second h)))
    (let [call (second h) s (unwrap-qsym (first call))]
      (when (and s (= "seon.web" (namespace s)))
        {:attr attr :sym s :arg-count (count (rest call))}))

    (and (seq? h) (= (quote list) (first h)))
    (let [s (unwrap-qsym (second h))]
      (when (and s (= "seon.web" (namespace s)))
        {:attr attr :sym s :arg-count (count (drop 2 h))}))

    (and (vector? h) (seq h))
    (let [s (unwrap-qsym (first h))]
      (when (and s (= "seon.web" (namespace s)))
        {:attr attr :sym s :arg-count (count (rest h))}))

    (and (qualified-symbol? h) (= "seon.web" (namespace h)))
    {:attr attr :sym h :arg-count 0}

    :else nil))

(defn- strip-def-docstring [ast]
  (if (and (seq? ast) (#{(quote defn) (quote defn-) (quote def)} (first ast))
           (>= (count ast) 4) (string? (nth ast 2)))
    (concat (take 2 ast) (drop 3 ast))
    ast))

(defn- extract-ui-event-calls [ast scan-js-strings?]
  (reduce (fn [acc n]
            (cond
              (map? n)
              (reduce (fn [a attr]
                        (if-let [c (when (contains? n attr) (inspect-ui-handler attr (get n attr)))]
                          (conj a c) a))
                      acc [:on-click :on-submit :on-change])

              (and (seq? n) (= (quote list) (first n)))
              (let [s (unwrap-qsym (second n))]
                (if (and s (= "seon.web" (namespace s)))
                  (conj acc {:attr :call-form :sym s :arg-count (count (drop 2 n))})
                  acc))

              (and scan-js-strings? (string? n) (str/includes? n "(seon.web/"))
              (reduce (fn [a [_ fn-s]] (conj a {:attr :js-call :sym (symbol fn-s) :arg-count nil}))
                      acc (re-seq #"\((seon\.web/[a-zA-Z0-9*+!_?<>=-]+)" n))

              :else acc))
          [] (tree-seq coll? seq (strip-def-docstring ast))))

(defn- check-one-ui-call [db wvars key {:keys [attr sym arg-count]}]
  (let [info (get wvars sym)
        base (cond
               (nil? info) [(prob :ui-missing-action (str key "->" sym)
                                  (str key " references missing seon.web action " sym "."))]
               (not (:callable? info)) [(prob :ui-non-callable-action (str key "->" sym)
                                              (str key " invokes " sym " which is not marked ^:seon/callable."))]
               :else [])]
    (if-not (= attr :on-submit)
      base
      (let [nf (if (str/ends-with? (name sym) "-form!")
                 []
                 [(prob :ui-submit-non-form-handler (str key "->" sym)
                        (str key " :on-submit calls " sym " which does not end in -form!."))])
            ar (when (and info (integer? arg-count)) (action-arities db sym))
            need (when (integer? arg-count) (+ 2 (long arg-count)))
            am (if (and ar need (not (or (contains? ar :variadic) (contains? ar need))))
                 [(prob :ui-arity-mismatch (str key "->" sym)
                        (str key " :on-submit passes " need " args to " sym " (arities " ar ")."))]
                 [])]
        (into base (concat nf am))))))

(defn- ui-target-nss [db]
  (sort (filter (fn [nsym]
                  (let [s (name nsym)]
                    (and (not (str/ends-with? s "-test"))
                         (not= nsym (quote seon.render))
                         (or (str/starts-with? s "seon.render.")
                             (= nsym (quote seon.web.css))
                             (= nsym (quote seon.web.js))))))
                (map :v (d/datoms db :aevt :seon.code.ns/name)))))

(defonce ^:private ^:seon.flow/edge form-ast-cache (atom {}))

(defonce ^:private ^:seon.flow/edge closure-report-cache (store/lru-cache 16))

(defn- ^:seon.flow/edge cached-by-hash [k f]
  (let [m @form-ast-cache]
    (if (contains? m k)
      (get m k)
      (let [v (f)]
        (when (> (count m) 2048) (reset! form-ast-cache {}))
        (swap! form-ast-cache assoc k v)
        v))))

(defn- pull-form-ast [db fkey]
  (when (and db (contains? (:schema db) :seon.code.form/key))
    (when-let [e (:e (first (d/datoms db :avet :seon.code.form/key fkey)))]
      (let [h (:v (first (d/datoms db :eavt e :seon.code.form/hash)))]
        (if (and (string? h) (not (str/blank? h)))
          (cached-by-hash [:ast h] #(ast-of (:v (first (d/datoms db :eavt e :seon.code.form/src)))))
          (ast-of (:v (first (d/datoms db :eavt e :seon.code.form/src)))))))))

(defn- extract-sanitizer-actions [db]
  (when-let [ast (pull-form-ast db "seon.render.sanitize/allowed-seon-web-actions")]
    (vec (distinct (keep #(let [s (unwrap-qsym %)] (when (and s (= "seon.web" (namespace s))) s))
                         (tree-seq coll? seq ast))))))

(defn- check-fork-and-stream-edges [db]
  (let [fork-ast (pull-form-ast db "seon.fork/run-fork-parent-loop!")
        fev-ast (pull-form-ast db "seon.agent/handle-fork-ev")
        fork-ps (if (and fork-ast fev-ast)
                  (let [emitted (extract-ev-out-child-evs fork-ast)
                        handled (case-dispatch-keys fev-ast)]
                    (mapv #(prob :unhandled-fork-ev (str "seon.agent/handle-fork-ev:" (name %))
                                 (str "seon.fork emits child :ev " % " unhandled by seon.agent/handle-fork-ev."))
                          (sort (set/difference emitted handled))))
                  [])
        stream-ast (pull-form-ast db "seon.web/handle-stream-msg")
        stream-ps (if (and stream-ast (not (stream-handles-error? stream-ast)))
                    [(prob :unhandled-stream-status "seon.web/handle-stream-msg::error"
                           "seon.web/handle-stream-msg does not explicitly handle :status :error.")]
                    [])
        merr-ast (pull-form-ast db "seon.error/model-error-outputs")
        dmr-ast (pull-form-ast db "seon.agent/deliver-model-response")
        dup-ps (if (and merr-ast dmr-ast (has-tx-output-key? merr-ast) (has-tx-output-key? dmr-ast))
                 [(prob :duplicate-model-error-tx "seon.agent/deliver-model-response"
                        "seon.agent/deliver-model-response duplicates :tx :seon.error/* already emitted by seon.error/model-error-outputs.")]
                 [])]
    (vec (concat fork-ps stream-ps dup-ps))))

(defn- flow-connections-from-db [db]
  (when-let [ast (pull-form-ast db "seon.main/flow-connections")]
    (some #(when (and (vector? %) (seq %) (every? (fn [x] (and (vector? x) (= 2 (count x)))) %)) %)
          (tree-seq coll? seq ast))))

(defn- tx-ops-by-eid [db e]
  (let [h (:v (first (d/datoms db :eavt e :seon.code.form/hash)))
        compute (fn []
                  (let [src (:v (first (d/datoms db :eavt e :seon.code.form/src)))]
                    (if (and src (str/includes? src ":tx") (str/includes? src ":op"))
                      (let [fk (:v (first (d/datoms db :eavt e :seon.code.form/key)))
                            ast (ast-of src)]
                        {:key fk :ops (if ast (extract-tx-ops-in-ast ast) #{})})
                      {:key nil :ops nil})))]
    (if (and (string? h) (not (str/blank? h)))
      (cached-by-hash [:tx-scan h] compute)
      (compute))))

(defn- check-tx-ops-in-ns [db tx-handled nsym]
  (if-not (and db (contains? (:schema db) :seon.code.form/ns))
    []
    (reduce (fn [acc dt]
              (let [{:keys [key ops]} (tx-ops-by-eid db (:e dt))]
                (if (seq ops)
                  (reduce (fn [a op]
                            (if (contains? tx-handled op)
                              a
                              (conj a (prob :unhandled-transactor-op (str key "->" op)
                                            (str key " emits :tx op " op " unhandled by seon.transactor/transactor-proc.")))))
                          acc ops)
                  acc)))
            [] (d/datoms db :avet :seon.code.form/ns nsym))))

(defn check-flow-closure
  "Pure DB-fact AST checker verifying Flow topology port closure, `:fork-ev` closure (`:fork-refused`),
   `:transactor` `:op` closure, `:web` `:stream` `:error` handling, and single-owner model-error `:tx`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
   :cost/ticks 40000000 :cost/bytes 33554432}
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/key))
    {:ok? true :problems [] :checked {:procs 0 :connections 0}}
    (let [procs (into {} (keep (fn [[pid fk]]
                                 (when-let [p (proc-ports-from-ast (pull-form-ast db fk))]
                                   [pid p]))
                               flow-proc-forms))
          conns (or (flow-connections-from-db db) [])
          tx-ast (pull-form-ast db "seon.transactor/transactor-proc")
          tx-handled (if tx-ast (case-dispatch-keys tx-ast) #{})
          tx-ps (if (seq tx-handled)
                  (vec (mapcat #(check-tx-ops-in-ns db tx-handled %)
                               (quote [seon.reconciler seon.task.gate seon.agent seon.test seon.git-proc seon.error seon.vector.flow])))
                  [])
          ps (vec (sort-by (juxt #(name (:class %)) :subject)
                           (concat (check-topology-ports procs conns)
                                   (check-fork-and-stream-edges db)
                                   tx-ps)))]
      {:ok? (empty? ps) :problems ps :checked {:procs (count procs) :connections (count conns)}})))

(defn- analyze-form-by-eid [db e js?]
  (let [h (:v (first (d/datoms db :eavt e :seon.code.form/hash)))
        compute (fn []
                  (let [src (:v (first (d/datoms db :eavt e :seon.code.form/src)))]
                    (if (and src (or (str/includes? src "seon.web/") (str/includes? src ":on-submit")))
                      (let [fk (:v (first (d/datoms db :eavt e :seon.code.form/key)))
                            ast (ast-of src)]
                        {:key fk :calls (if ast (extract-ui-event-calls ast js?) [])})
                      {:key nil :calls nil})))]
    (if (and (string? h) (not (str/blank? h)))
      (cached-by-hash [:ui-scan h js?] compute)
      (compute))))

(defn check-ui-closure
"Pure DB-fact AST checker verifying every UI/JS/sanitizer seon.web action exists and is `^:seon/callable`,
 `invoke-call!` is not exposed as `^:seon/callable`, `:on-submit` handlers end in `-form!` with matching arity,
 and `seon.web.actions/invoke-call!` passes form maps safely on empty POSTs."
{:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
 :cost/ticks 40000000 :cost/bytes 33554432}
[db]
(if-not (and db (contains? (:schema db) :seon.code.var/ns))
  {:ok? true :problems [] :checked {:web-vars 0 :call-sites 0}}
  (let [wvars (web-var-info db)
        inv-src (pull-form-src db "seon.web.actions/invoke-call!")
        san-acts (or (extract-sanitizer-actions db) [])
        base-ps (cond-> []
                  (get-in wvars [(quote seon.web/invoke-call!) :callable?])
                  (conj (prob :internal-dispatcher-callable "seon.web/invoke-call!"
                              "Internal dispatcher seon.web/invoke-call! must not be marked ^:seon/callable."))
                  (and inv-src (not (or (str/includes? inv-src "form-handler-sym?") (str/includes? inv-src "-form!"))))
                  (conj (prob :ui-unsafe-empty-form-dispatch "seon.web.actions/invoke-call!"
                              "seon.web.actions/invoke-call! drops empty form-fields for -form! handlers.")))
        san-ps (mapcat (fn [sym]
                         (let [info (get wvars sym)]
                           (cond
                             (nil? info) [(prob :sanitizer-dead-action (str "seon.render.sanitize->" sym)
                                                (str "Sanitizer allowlist permits non-existent action " sym "."))]
                             (not (:callable? info)) [(prob :ui-non-callable-action (str "seon.render.sanitize->" sym)
                                                            (str "Sanitizer action " sym " is not marked ^:seon/callable."))]
                             :else [])))
                       san-acts)
        call-cnt (volatile! 0)
        ui-ps (mapcat (fn [nsym]
                        (let [js? (or (= nsym (quote seon.web.css)) (= nsym (quote seon.web.js)))]
                          (mapcat (fn [dt]
                                    (let [{:keys [key calls]} (analyze-form-by-eid db (:e dt) js?)]
                                      (if (and (seq calls) (not= key "seon.render.sanitize/allowed-seon-web-actions"))
                                        (do (vswap! call-cnt + (count calls))
                                            (mapcat #(check-one-ui-call db wvars key %) calls))
                                        [])))
                                  (d/datoms db :avet :seon.code.form/ns nsym))))
                      (ui-target-nss db))
        ps (vec (sort-by (juxt #(name (:class %)) :subject) (distinct (concat base-ps san-ps ui-ps))))]
    {:ok? (empty? ps) :problems ps :checked {:web-vars (count wvars) :sanitizer-actions (count san-acts) :call-sites @call-cnt}})))

(defn- ^:seon.flow/edge db-cache-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    [(get-in db [:config :store :id])
     (get-in db [:config :branch] :main)
     (try (d/commit-id db) (catch Throwable _ nil))
     (:max-tx db)]))

(defn check-closure
  "Runs both `check-flow-closure` and `check-ui-closure` over `db` in one combined closed-world report (< 35 ms)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :map]
   :cost/ticks 80000000 :cost/bytes 67108864}
  [db]
  (let [compute (fn []
                  (let [t0 (System/nanoTime)
                        fl (check-flow-closure db)
                        ui (check-ui-closure db)
                        probs (into (vec (:problems fl)) (:problems ui))
                        ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)]
                    {:ok? (and (:ok? fl) (:ok? ui))
                     :ms ms
                     :counts (frequencies (map :class probs))
                     :flow fl
                     :ui ui
                     :problems probs}))
        ck (db-cache-key db)]
    (if ck
      (store/lru-get! closure-report-cache 16 ck compute)
      (compute))))
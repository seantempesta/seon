(ns w4-base-pinning-hazard
  "W4 part (c) — the current behavior's correctness hazard, demonstrated.

  Seon's acquire! evaluates every committed agent-authored defn ONCE into
  the cluster's shared base ctx (src/seon/sci/eval.clj:1253-1292, via
  install-row! -> sci/eval-form). Phase 0 finding 2 says such a function
  pins the ctx it was created against, so it resolves the BASE
  environment, not the calling fork's.

  This probe pins down exactly HOW BAD that is, which is what the Phase 2
  design conversation needs:

    1. reproduce the base-pinning with the maintained fork's
       call-preparation hook (the Phase 0 technique);
    2. establish the blast radius: does a base-pinned function of cluster A
       ever resolve cluster B's environment? (separate base ctx per
       cluster);
    3. separate cluster-scoped members (identical in base and fork) from
       turn-scoped members (differ every turn) — only the latter are
       actually wrong;
    4. show the difference between SILENT wrongness (a stale value present
       in the base environment) and a LOUD refusal (member absent from the
       base environment);
    5. check the lazy-install substrate that already exists
       (seon.sci.kernel ensure-function! + ::installed-functions): sci/fork
       is (update ctx :env ...) only, so every other ctx key -- including
       an atom -- is shared by identity with the base.

  Run: see docs/prds/sci-execution-runtime/research/env-phase1-w4-probes/RUN.md"
  (:require [sci.core :as sci]
            [sci.impl.vars :as sci-vars]))

;;; ---------------------------------------------------------------------------
;;; The hook, identical in shape to the Phase 0 probe: fill a declared and
;;; absent argument from the RUNTIME ctx's environment.

(defn who-am-i
  "Host capability leaf declaring the turn's agent id and the cluster's
  connection — one turn-scoped member and one cluster-scoped member."
  [agent-id connection]
  {:saw/agent agent-id :saw/connection connection})

(def ^:private declarations
  {'my/who-am-i {:arity 2
                 :env-keys [:seon.cluster.agent/id :seon.db/connection]}})

(defn- call-preparation-hook [ctx v args]
  (let [sym (sci-vars/toSymbol v)
        {:keys [arity env-keys]} (get declarations sym)]
    (if (or (nil? arity) (>= (count args) arity))
      args
      (let [environment (:seon.env/environment ctx)
            missing (remove #(contains? environment %) env-keys)]
        (if (seq missing)
          (reduced {:seon.error/kind :seon.env/unavailable
                    :seon.error/message
                    (str "Cannot call " sym ": " (pr-str (vec missing))
                         " absent from the environment on the runtime ctx.")})
          (into (vec args) (map #(get environment %)) env-keys))))))

(defn- ctx-with [environment]
  (let [my-ns (sci/create-ns 'my)]
    (assoc (sci/init {:namespaces
                      {'my {'who-am-i (sci/new-var 'who-am-i who-am-i
                                                   {:ns my-ns})}}
                      :call-preparation-hook call-preparation-hook})
           :seon.env/environment environment)))

;;; ---------------------------------------------------------------------------
;;; 1 + 3 + 4. One cluster: base-installed program fn, called from a turn fork.

(def ^:private program-source
  "(defn report-identity [] (my/who-am-i))")

(defn- one-cluster-turn-scope []
  ;; The cluster's base environment as boot would build it today: cluster
  ;; members present. Two variants for the turn-scoped agent id: present
  ;; (a boot-time placeholder) and absent.
  (let [cluster-conn :conn-CLUSTER-A
        base-with-stale-agent
        (ctx-with {:seon.db/connection cluster-conn
                   :seon.cluster.agent/id :agent-BASE-PLACEHOLDER})
        base-without-agent
        (ctx-with {:seon.db/connection cluster-conn})
        install! (fn [ctx] (sci/eval-string* ctx program-source) ctx)
        turn-fork (fn [base agent]
                    (assoc (sci/fork base)
                           :seon.env/environment
                           {:seon.db/connection cluster-conn
                            :seon.cluster.agent/id agent}))]
    (install! base-with-stale-agent)
    (install! base-without-agent)
    (let [silent (sci/eval-string* (turn-fork base-with-stale-agent :agent-TURN-7)
                                   "(report-identity)")
          loud (sci/eval-string* (turn-fork base-without-agent :agent-TURN-7)
                                 "(report-identity)")
          re-created (let [f (turn-fork base-with-stale-agent :agent-TURN-7)]
                       (sci/eval-string* f program-source)
                       (sci/eval-string* f "(report-identity)"))]
      {:base-pinned-with-stale-member
       {:expected :agent-TURN-7
        :actual (:saw/agent silent)
        :silently-wrong? (not= :agent-TURN-7 (:saw/agent silent))
        :cluster-member-still-correct? (= cluster-conn (:saw/connection silent))
        :value silent}
       :base-pinned-with-absent-member
       {:flat-error? (= :seon.env/unavailable (:seon.error/kind loud))
        :value loud}
       :re-created-in-the-fork
       {:expected :agent-TURN-7
        :actual (:saw/agent re-created)
        :correct? (= :agent-TURN-7 (:saw/agent re-created))}
       :finding
       "Within one cluster the pinning is wrong ONLY for turn-scoped members. A stale member in the base environment is silently wrong; an absent one refuses loudly. Re-creating the defn in the fork fixes it."})))

;;; ---------------------------------------------------------------------------
;;; 2. Two clusters: is there any cross-cluster leak from base pinning?

(defn- two-cluster-blast-radius []
  (let [base-a (ctx-with {:seon.db/connection :conn-CLUSTER-A
                          :seon.cluster.agent/id :agent-A})
        base-b (ctx-with {:seon.db/connection :conn-CLUSTER-B
                          :seon.cluster.agent/id :agent-B})
        _ (do (sci/eval-string* base-a program-source)
              (sci/eval-string* base-b program-source))
        from-a-fork (sci/eval-string* (sci/fork base-a) "(report-identity)")
        from-b-fork (sci/eval-string* (sci/fork base-b) "(report-identity)")
        ;; The one crossing that WOULD leak: cluster B's fork given cluster
        ;; A's already-created function object.
        fn-object-crossing
        (let [f (sci/eval-string* base-a "report-identity")
              b-fork (sci/fork base-b)]
          (sci/eval-string* (assoc b-fork :seon.env/environment
                                   {:seon.db/connection :conn-CLUSTER-B
                                    :seon.cluster.agent/id :agent-B})
                            "(report-identity)")
          {:cluster-a-fn-called-from-b-fork (:saw/connection (f))
           :note "calling cluster A's fn OBJECT directly; only possible if a fn value crosses a cluster boundary"})]
    {:cluster-a-fork (:saw/connection from-a-fork)
     :cluster-b-fork (:saw/connection from-b-fork)
     :cross-cluster-leak-via-forks?
     (not (and (= :conn-CLUSTER-A (:saw/connection from-a-fork))
               (= :conn-CLUSTER-B (:saw/connection from-b-fork))))
     :fn-object-crossing fn-object-crossing
     :finding
     "Each cluster has its OWN base ctx, so a base-pinned fn can never resolve another cluster's environment through forking. The only cross-cluster path is a fn OBJECT crossing clusters, which the sci report's invariant already forbids (fns never cross a turn boundary; they round-trip through source)."}))

;;; ---------------------------------------------------------------------------
;;; 5. sci/fork shares every non-:env ctx key by identity.

(defn- fork-shares-non-env-keys []
  (let [installed (atom #{'my/already})
        base (assoc (ctx-with {}) ::installed-functions installed)
        fork (sci/fork base)]
    (swap! (::installed-functions fork) conj 'my/added-in-fork)
    {:same-atom-identity? (identical? (::installed-functions base)
                                      (::installed-functions fork))
     :base-sees-forks-write? (contains? @(::installed-functions base)
                                        'my/added-in-fork)
     :env-atom-distinct? (not (identical? (:env base) (:env fork)))
     :finding
     "sci/fork is (update ctx :env ...) ONLY (reference-code/sci/src/sci/core.cljc:340-346). Every other ctx key is shared by identity, so seon.sci.kernel's ::installed-functions atom is one set for the base and all its forks: a lazy installer built on ensure-function! would see a base-installed function as already installed and SKIP re-creating it in the fork — reproducing the exact hazard it exists to fix."}))

(defn run
  "Execute every falsifier and return one data map."
  []
  {:probe/name "w4 (c) — the base-pinning correctness hazard, scoped"
   :probe/sci-branch "seon-env-hook-probe (reference-code/sci working tree, superproject pin untouched)"
   :probe/findings
   {:one-cluster-turn-scope (one-cluster-turn-scope)
    :two-cluster-blast-radius (two-cluster-blast-radius)
    :fork-shares-non-env-keys (fork-shares-non-env-keys)}})

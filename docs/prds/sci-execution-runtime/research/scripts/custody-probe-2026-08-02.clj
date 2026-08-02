(require '[sci.core :as sci] '[seon.cluster] '[seon.db :as db] '[seon.cluster.store])
(def ctx (sci/init {}))
(sci/add-namespace! ctx 'seon.cluster (ns-interns 'seon.cluster))
(sci/add-namespace! ctx 'seon.db (ns-interns 'seon.db))
(sci/add-namespace! ctx 'seon.cluster.store (ns-interns 'seon.cluster.store))
(println "PRIVATE-VAR-DEREF:"
         (try (sci/eval-string* ctx "@seon.cluster/running-instances")
              (catch Throwable t [:threw (ex-message t)])))
(println "PRIVATE-STORE-HOLDER:"
         (try (sci/eval-string* ctx "@seon.cluster/root-store-holder")
              (catch Throwable t [:threw (ex-message t)])))
(println "ALTER-VAR-ROOT-CORE:"
         (try (sci/eval-string* ctx "(alter-var-root #'seon.db/q (constantly :pwned))")
              (catch Throwable t [:threw (ex-message t)])))
(println "seon.db/q now =" seon.db/q)
(println "DYN-BIND-ESCAPE-THREAD:"
         (try (sci/eval-string* ctx "(deref (future @seon.db/*conn*))")
              (catch Throwable t [:threw (ex-message t)])))
(println "COUNT-INTERNS seon.cluster:" (count (ns-interns 'seon.cluster))
         "private:" (count (filter (comp :private meta val) (ns-interns 'seon.cluster))))

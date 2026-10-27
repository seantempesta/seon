# Query/pull attribute tracking: facts (read-only, root REPL port 7888, db = @(:conn sys), n<=100)

1. PARSER: Datahike (our fork) ALREADY extracts attribute deps. datahike.query/query-attribute-dependencies
   (public as datahike.api/query-attribute-dependencies; spec line 617) and query-dependency-plan
   (query.cljc:2980; takes query + args, no db). Parse is memoized (memoized-parse-query, query.cljc:3189; datalog.parser).
   - `(d/query-attribute-dependencies '[:find ?t :where [?r :seon.run/id ?run] [?t :seon.turn/run ?r]])` => #{:seon.run/id :seon.turn/run}
   - SAME query with a pull in :find (even `[:seon.turn/id]`, even given args and a db) => :all.
     Cause: extract-find-pull-source-deps (query.cljc:2918) calls pull-spec-attribute-dependencies with NO db, so any plain
     attribute may be a component => :all. `[*]` => :all always (wildcard).
   - Pull side with a db works: `(datahike.pull-api/pull-spec-attribute-dependencies db (datalog.parser.pull/parse-pull pat))`
     (pull_api.cljc:107) =>
       [:seon.turn/id {:seon.turn/evals [:seon.eval/code]}]  #{:seon.eval/code :seon.turn/evals :seon.turn/id}
       [:seon.turn/id :seon.turn/_run]                       #{:seon.turn/id :seon.turn/run}   (reverse ref reported as forward attr)
       [{:seon.turn/_run [:seon.run/id]}]                    #{:seon.run/id :seon.turn/run}
       [*]                                                   :all
   => Recipe: where-deps from query-attribute-dependencies on the query with pull find-elements replaced by plain vars,
      union pull-spec-attribute-dependencies db (parse-pull pat) per pull. Any :all (wildcard) = depends on everything
      (or expand * via (keys (:schema db)) for known attrs, wildcard also reads entity's other attrs).
   Cost (100 runs, ms each): query-attribute-dependencies cached 0.006; fresh distinct query parse+plan 0.019; pull-spec deps 0.18.
2. :offset/:limit: SUPPORTED. `(d/q {:query '[:find ?id :where [?t :seon.task/id ?id]] :args [db] :limit 3})` => 3 rows;
   :offset 2 :limit 3 => 3 rows (different); with :order-by '[?id :desc] (flat form; [[0 :desc]] and [['?id :desc]] are INVALID)
   => ordered vector. Without :order-by result is a set, so offset/limit is over unordered rows: always pair with :order-by.
3. `(d/q '[:find (pull ?e [:seon.task/id]) . :in $ ?e] db e)` => {:seon.task/id "seon.ref/the-parser-reads-with-options-built-once"}
   and `{:query '[:find [(pull ?e [..]) ...] :in $ [?e ...]] :args [db [e]]}` => vector of maps. Works (nil e throws entity-id/syntax).
4. CHANGED ATTRS PER COMMIT: tx-report :tx-data is already on root's path: seon.writer (writer.clj:137-143,176-182) holds `rep`
   from d/transact/d/merge-db and builds ops via pdb/report->tx and meta via report->tx-meta (db.clj:370-385), passing to main-commit
   with (:db-after rep). Changed attrs = (into #{} (map :a) (:tx-data rep)), O(|delta|), no diff needed (includes the tx entity's own
   :db/txInstant etc; filter (not= e tx)). Note merge-db reports: ops come from report->tx too, so same path.
   d/datoms-diff (used reconcile.clj:277, merge.clj:203, program.clj:857, union.clj:59) is for when only two dbs are at hand:
   measured db vs db-with(+1 datom change): :eavt [] 0.33 ms; :aevt [attr] 0.11 ms per call (returns [gone added]); skips shared subtrees,
   so O(delta log N). (Adjacent commits not measured: as-of gave nil here, history as-of not available on the live root; db-with probe used.)
   Cheapest: tx-data from the writer (free). Use datoms-diff only for db pairs without a report.
5. d/listen: datahike.api/listen exists (#'datahike.api/listen -> datahike.core/listen!, conn-atom callbacks with the tx-report).
   NOT used anywhere in src (grep listen: only web.clj HTTP listener). Root's writer is the only transact caller (:sync backend), so
   the natural hook is the writer proc emitting a changed-attrs event, not d/listen (callbacks run in the committing thread, and the
   spec warns against transact from listeners).

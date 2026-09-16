; Evaluate through MCP JVM mode on default. One fresh canonical base prevents
; an earlier development test's delayed manifest from supplying old contracts.
(require 'seon.cluster 'seon.test 'seon.test-support 'seon.test.runner 'seon.fn
         'seon.schema 'seon.instrument 'seon.operator
         'seon.render.web-context-test)
(seon.schema/call-with-projection
 (#'seon.test.runner/packaged-test-projection "cold-page-kills")
 (fn []
   (with-redefs [seon.test-support/source-manifest
                 (delay (seon.fn/build-manifest {:seon.fn/roots seon.fn/source-roots}))]
     (let [base (#'seon.test-support/create-base nil)]
       (try
         (with-redefs-fn
           {#'seon.test-support/database-base (delay base)}
           (fn []
             (load-file "test/seon/render/web_context_test.clj")
             {:cold-page-kills/armed
              (mapv #(boolean (:seon.instrument/var (meta (deref %))))
                    [#'seon.cluster/project-next-prepl-value! #'seon.cluster/mcp-valf])
              :cold-page-kills/result
              (seon.test/run
               #'seon.render.web-context-test/read-only-mcp-return-preserves-root-page
               (seon.operator/connection "default"))}))
         (finally (#'seon.test-support/close-base! base)))))))

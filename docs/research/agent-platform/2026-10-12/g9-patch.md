# g9 patch for root: retire `my.program/a-function-called-through-its-callers-is-reached-by-their-tests`

The fix is `seon.fn/closure-walk`: the depth walk continues past two levels until a walked level holds a test.
`my.program-test/a-function-called-through-its-callers-is-reached-by-their-tests` is green.

Apply once m3 releases the held files:

1. Delete the entry `#:seon.task{:name "a-function-called-through-its-callers-is-reached-by-their-tests" ...}` from
   `resources/seon/tasks/my.program.edn` (the last entry; its `:must-read` ends with
   `seon.render.value/make-node`).
2. In `test/my/program_test.clj` remove the metadata
   `^{:seon/tasks #{'my.program/a-function-called-through-its-callers-is-reached-by-their-tests}}` before the deftest
   of the same name. Keep the deftest as the regression.

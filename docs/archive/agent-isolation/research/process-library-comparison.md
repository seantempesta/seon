# Process Library Comparison for Claude SDK

**Date**: 2026-01-09
**Status**: Research Complete

## Executive Summary

Clojure 1.12 introduced `clojure.java.process`, a built-in namespace for process spawning. This document compares it with `babashka.process` to determine which is more suitable for the Clojure Claude SDK implementation.

**Recommendation**: Use `clojure.java.process` as the primary process API. It's built into Clojure 1.12+, has no external dependencies, and provides all necessary functionality for the Claude SDK use case.

## 1. Library Overview

### clojure.java.process (Clojure 1.12+)

- **Status**: Built-in, officially supported by Clojure team
- **Dependencies**: None (part of clojure.core)
- **Philosophy**: Minimal, functional wrapper around Java ProcessBuilder
- **Documentation**: https://clojure.github.io/clojure/clojure.java.process-api.html

### babashka.process

- **Status**: Third-party library, widely used
- **Dependencies**: babashka/fs
- **Philosophy**: Convenience-focused, shell scripting patterns
- **Documentation**: https://github.com/babashka/process

## 2. API Comparison

### Process Spawning

```clojure
;; clojure.java.process
(require '[clojure.java.process :as p])
(def proc (p/start {:dir "/tmp" :env {"VAR" "value"}} "echo" "hello"))

;; babashka.process
(require '[babashka.process :as bp])
(def proc (bp/process {:dir "/tmp" :extra-env {"VAR" "value"}} "echo" "hello"))
```

### Stream Access

```clojure
;; clojure.java.process - explicit functions
(p/stdin proc)   ; => OutputStream
(p/stdout proc)  ; => InputStream
(p/stderr proc)  ; => InputStream

;; babashka.process - record fields
(:in proc)       ; => OutputStream
(:out proc)      ; => InputStream
(:err proc)      ; => InputStream
```

### Waiting for Completion

```clojure
;; clojure.java.process
(.waitFor proc)              ; blocks, returns exit code
@(p/exit-ref proc)           ; deref-able reference

;; babashka.process
@proc                        ; deref blocks, returns process map with :exit
(bp/check proc)              ; waits and throws on non-zero exit
```

### Capturing Output as String

```clojure
;; clojure.java.process - requires manual slurp
(let [proc (p/start "ls")]
  (.waitFor proc)
  (slurp (p/stdout proc)))

;; Or use exec (blocks and returns stdout as string):
(p/exec "ls" "-la")  ; => "file1\nfile2\n..."

;; babashka.process - built-in :string option
@(bp/process {:out :string} "ls")  ; => {:out "file1\nfile2\n" :exit 0 ...}
(bp/sh "ls" "-la")                  ; convenience function
```

### Piping Input

```clojure
;; clojure.java.process - manual byte writing
(let [proc (p/start "cat")
      stdin (p/stdin proc)]
  (.write stdin (.getBytes "hello\n"))
  (.close stdin)
  (slurp (p/stdout proc)))

;; babashka.process - accepts string directly
@(bp/process {:in "hello\n" :out :string} "cat")
```

### Environment Variables

```clojure
;; clojure.java.process
;; :env replaces entire environment (inherits by default)
;; :clear-env removes inherited vars first
(p/start {:env {"VAR" "val"}} "cmd")
(p/start {:clear-env true :env {"VAR" "val"}} "cmd")

;; babashka.process
;; :env replaces, :extra-env adds to existing
(bp/process {:extra-env {"VAR" "val"}} "cmd")
(bp/process {:env {"VAR" "val"}} "cmd")
```

## 3. Feature Comparison Matrix

| Feature | clojure.java.process | babashka.process |
|---------|---------------------|------------------|
| Built-in (no deps) | Yes (1.12+) | No |
| Async process start | Yes | Yes |
| String input/output | Manual | Built-in |
| Pipeline support | Manual | Built-in (`->`) |
| Exit code checking | Manual | `check` function |
| Tokenization | No | Yes (`tokenize`) |
| Shutdown hooks | No | Yes |
| Windows support | Basic | Enhanced |
| Process destruction | `.destroy` | `destroy`, `destroy-tree` |

## 4. Claude SDK Requirements Analysis

The Clojure Claude SDK needs:

1. **Async process spawning** - Both libraries support this
2. **Stdin/stdout streaming** - Both support via InputStream/OutputStream
3. **Environment variables** - Both support this
4. **Working directory** - Both support `:dir`
5. **Exit code monitoring** - Both support this
6. **Process destruction** - Both support this

### What We DON'T Need

- String convenience functions (we use JSON line-by-line)
- Pipeline support (single process)
- Tokenization (we build args programmatically)
- Shutdown hooks (we manage lifecycle explicitly)

## 5. Verified Tests with clojure.java.process

Tested on 2026-01-09 in seon.claude.sdk REPL session.

### Test 1: Basic Process Spawning

```clojure
(require '[clojure.java.process :as process])

(process/exec "echo" "Hello from clojure.java.process!")
;; => "Hello from clojure.java.process!\n"
```

### Test 2: Environment and Directory

```clojure
(process/exec {:dir "/tmp" :env {"MY_VAR" "test-value"}}
              "sh" "-c" "echo $MY_VAR && pwd")
;; => "test-value\n/private/tmp\n"
```

### Test 3: Async Process with Stream Handling

```clojure
(let [proc (process/start "cat")
      stdin (process/stdin proc)
      stdout (process/stdout proc)]
  (.write stdin (.getBytes "test input from Clojure\n"))
  (.close stdin)
  {:output (slurp stdout)
   :exit (.exitValue proc)})
;; => {:output "test input from Clojure\n", :exit 0}
```

### Test 4: Claude Code CLI Integration

```clojure
(defn spawn-claude-code [{:keys [model cwd max-turns permission-mode]
                          :or {permission-mode "plan"
                               cwd "/Users/sean/src/seon"}}]
  (let [args (cond-> ["node" "/private/tmp/package/cli.js"
                      "--output-format" "stream-json"
                      "--input-format" "stream-json"
                      "--verbose"
                      "--permission-mode" permission-mode]
               model (into ["--model" model])
               max-turns (into ["--max-turns" (str max-turns)]))
        env (assoc (into {} (System/getenv))
                   "CLAUDE_CODE_ENTRYPOINT" "sdk-clj")
        proc (apply process/start {:dir cwd :env env} args)]
    {:process proc
     :stdin (process/stdin proc)
     :stdout (process/stdout proc)
     :exit-ref (process/exit-ref proc)}))
```

### Test 5: Multi-turn Conversation

```clojure
;; Turn 1: Simple math question
;; => {:assistant-content "4", :num_turns 1}

;; Turn 2: Follow-up using context
;; => {:assistant-content "12", :num_turns 1}
```

### Test 6: Tool Use Flow

```clojure
;; Task: Read CONVENTIONS.md and summarize
;; Message flow:
;;   1. system (init)
;;   2. assistant (text response)
;;   3. assistant (tool_use: Read)
;;   4. user (tool_use_result)
;;   5. assistant (final answer)
;;   6. result (success)

;; Tool call captured:
{:type "tool_use"
 :id "toolu_01S8Ka9uKG3C29CmY7SEAGvi"
 :name "Read"
 :input {:file_path "/Users/sean/src/seon/CONVENTIONS.md" :limit 10}}

;; Final result:
{:result "The main topic is Malli schema patterns..."
 :num_turns 2
 :total_cost_usd 0.01143744}
```

## 6. Recommendation

**Use `clojure.java.process`** for the Clojure Claude SDK because:

1. **Zero dependencies** - Built into Clojure 1.12, which Seon already uses
2. **Sufficient functionality** - All required features are present
3. **Official support** - Maintained by Clojure core team
4. **Simpler mental model** - Closer to Java ProcessBuilder
5. **No babashka/fs dependency** - babashka.process pulls in babashka/fs

The convenience features in babashka.process (`:in "string"`, `:out :string`, pipelines) are not needed for the Claude SDK use case, where we:
- Write JSON lines to stdin (byte-by-byte)
- Read JSON lines from stdout (line-by-line)
- Don't need shell-style pipelines

## 7. Implementation Notes

### Key Requirements for Claude SDK

```clojure
;; 1. IMPORTANT: --verbose flag is REQUIRED with stream-json
;; Without it: "Error: When using --print, --output-format=stream-json requires --verbose"

;; 2. Environment variable for SDK identification
(assoc env "CLAUDE_CODE_ENTRYPOINT" "sdk-clj")

;; 3. Model name format (full identifier)
;; Correct: "claude-3-5-haiku-20241022"
;; Wrong: "claude-haiku-3-5"

;; 4. Permission modes
;; "plan" - read-only, no tool execution
;; "bypassPermissions" - auto-approve all tools
;; "default" - prompt for dangerous operations
```

### Gotchas Discovered

1. Process stdin defaults to `:pipe` but must be accessed immediately
2. Reader/writer must be properly closed or process hangs
3. Exit code 1 with empty stdout usually means CLI error - check stderr
4. Multi-turn requires new user message after each result

## 8. References

- [clojure.java.process API docs](https://clojure.github.io/clojure/clojure.java.process-api.html)
- [babashka.process README](https://github.com/babashka/process)
- [Clojure 1.12 release notes](https://clojure.org/news/2023/04/14/clojure-1-12-alpha2)
- [Process library comparison examples](https://github.com/frenchy64/clojure.java.process-examples)

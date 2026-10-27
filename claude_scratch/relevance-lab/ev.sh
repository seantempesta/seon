#!/bin/zsh
# eval a file's code on my worktree root (port 10080): ev.sh FILE [AGENT] [TIMEOUT]
python3 -c 'import sys,json;print("{:op :eval :agent \""+sys.argv[2]+"\" :code "+json.dumps(open(sys.argv[1]).read())+"}")' "$1" "${2:-root}" | nc -w ${3:-300} 127.0.0.1 10080

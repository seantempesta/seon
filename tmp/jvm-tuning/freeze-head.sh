#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
freeze_name="${1:-frozen-tree}"

if [[ ! "$freeze_name" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "freeze name must be one path component" >&2
  exit 2
fi

frozen_tree="$repo_root/tmp/jvm-tuning/$freeze_name"

if [[ -e "$frozen_tree" ]]; then
  echo "refusing to replace existing frozen tree: $frozen_tree" >&2
  exit 2
fi

mkdir -p "$frozen_tree"
git -C "$repo_root" archive HEAD | tar -x -C "$frozen_tree"
git -C "$repo_root" submodule status | while read -r revision path _; do
  if [[ "$revision" == -* ]]; then
    continue
  fi
  revision="${revision#[+U]}"
  mkdir -p "$frozen_tree/$path"
  git -C "$repo_root/$path" archive "$revision" | tar -x -C "$frozen_tree/$path"
done
(cd "$frozen_tree" && clojure -X:deps prep)
git -C "$repo_root" rev-parse HEAD > "$frozen_tree/.jvm-tuning-head"
printf '%s\n' "$frozen_tree"

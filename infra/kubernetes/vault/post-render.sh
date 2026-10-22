#!/bin/sh
# Official chart hardcodes insecure exec readiness. Apply a separately reviewed YAML patch.
set -eu
patch_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
render_dir=$(mktemp -d)
trap 'rm -rf "$render_dir"' EXIT HUP INT TERM
cat > "$render_dir/rendered.yaml"
cp "$patch_root/readiness-patch.yaml" "$render_dir/readiness-patch.yaml"
cp "$patch_root/readiness-kustomization.yaml" "$render_dir/kustomization.yaml"
kubectl --context docker-desktop kustomize "$render_dir"

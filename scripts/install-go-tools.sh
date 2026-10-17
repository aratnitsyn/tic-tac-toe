#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version=2.1.6
os="$(uname -s | tr '[:upper:]' '[:lower:]')"
arch="$(uname -m)"
case "$arch" in
    arm64 | aarch64) arch=arm64 ;;
    x86_64) arch=amd64 ;;
    *) echo "Unsupported architecture: $arch" >&2; exit 1 ;;
esac
case "$os" in
    darwin | linux) ;;
    *) echo "Unsupported platform: $os" >&2; exit 1 ;;
esac
archive="golangci-lint-${version}-${os}-${arch}.tar.gz"
download_dir="$(mktemp -d)"
trap 'rm -rf "$download_dir"' EXIT
base_url="https://github.com/golangci/golangci-lint/releases/download/v${version}"
curl --fail --location --retry 3 "$base_url/$archive" -o "$download_dir/$archive"
curl --fail --location --retry 3 "$base_url/golangci-lint-${version}-checksums.txt" -o "$download_dir/checksums.txt"
expected="$(awk -v name="$archive" '$2 == name { print $1 }' "$download_dir/checksums.txt")"
actual="$(shasum -a 256 "$download_dir/$archive" | awk '{print $1}')"
if [[ -z "$expected" || "$expected" != "$actual" ]]; then
    echo "golangci-lint checksum mismatch" >&2
    exit 1
fi
tar -xzf "$download_dir/$archive" -C "$download_dir"
mkdir -p "$repo_root/.cache/bin"
install -m 755 "$download_dir/golangci-lint-${version}-${os}-${arch}/golangci-lint" "$repo_root/.cache/bin/golangci-lint"
"$repo_root/.cache/bin/golangci-lint" version

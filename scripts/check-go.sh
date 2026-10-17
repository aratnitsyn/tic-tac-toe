#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lint_bin="${GOLANGCI_LINT:-$repo_root/.cache/bin/golangci-lint}"
if [[ ! -x "$lint_bin" ]]; then
    echo "Install golangci-lint 2.1.6: scripts/install-go-tools.sh" >&2
    exit 1
fi
if [[ "$("$lint_bin" version)" != *"version 2.1.6 "* ]]; then
    echo "Expected golangci-lint 2.1.6" >&2
    exit 1
fi
for service in matchmaking realtime; do
    (
        cd "$repo_root/services/$service"
        unformatted="$(gofmt -l .)"
        if [[ -n "$unformatted" ]]; then
            echo "Run gofmt on: $unformatted" >&2
            exit 1
        fi
        go vet ./...
        "$lint_bin" run --config "$repo_root/.golangci.yml" ./...
        go test ./...
        go build -o "build/$service" .
    )
done

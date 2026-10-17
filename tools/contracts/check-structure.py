#!/usr/bin/env python3
"""Check local JSON references and basic consistency, not schema conformance."""

import json
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2] / "contracts"
documents = {path.resolve(): json.loads(path.read_text()) for path in ROOT.rglob("*.json")}


def walk(value, path):
    if isinstance(value, list):
        for child in value:
            walk(child, path)
    elif isinstance(value, dict):
        if "$ref" in value:
            reference = value["$ref"]
            filename, _, fragment = reference.partition("#")
            if "://" in filename:
                raise ValueError("Remote reference not allowed: " + reference)
            target = (path.parent / filename).resolve() if filename else path
            current = documents[target]
            for part in fragment.lstrip("/").split("/") if fragment else []:
                token = unquote(part).replace("~1", "/").replace("~0", "~")
                current = current[int(token)] if isinstance(current, list) else current[token]
        if isinstance(value.get("required"), list) and "properties" in value:
            missing = set(value["required"]) - set(value["properties"])
            if missing:
                raise ValueError("Required property not declared: " + str(missing))
        for child in value.values():
            walk(child, path)


operation_ids = set()
for path, content in documents.items():
    walk(content, path)
    if "openapi" in content:
        for route, methods in content["paths"].items():
            if not route.startswith("/"):
                raise ValueError("Route must be absolute within server base path")
            for operation in methods.values():
                operation_id = operation["operationId"]
                if operation_id in operation_ids:
                    raise ValueError("Duplicate operationId: " + operation_id)
                operation_ids.add(operation_id)
print(f"Parsed {len(documents)} JSON files; local refs and {len(operation_ids)} operation IDs checked.")
print("NOT checked: OpenAPI/JSON Schema conformance, fixture validity, compatibility.")

#!/usr/bin/env python3
"""Validate or idempotently seed the Tiffin realm and Authorization Services.

Passwords and tokens are accepted only through owner-readable files. The tracked
state contains role/resource names, never credentials. Existing objects outside
the tiffin ownership prefix are not removed or rewritten.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_STATE = ROOT / "product" / "tiffin-local" / "authorization-state.json"
SAFE_ID = re.compile(r"^[a-z][a-z0-9-]{1,62}$")
SCOPE_ID = re.compile(r"^[a-z][a-z0-9-]{1,31}\.[a-z][a-z0-9-]{1,31}$")


class SeedError(ValueError):
    pass


def load_json(path: pathlib.Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def exact(value: dict[str, Any], required: set[str], label: str) -> None:
    if set(value) != required:
        raise SeedError(f"{label} fields must be exactly: {', '.join(sorted(required))}")


def validate(state: Any) -> dict[str, Any]:
    if not isinstance(state, dict):
        raise SeedError("authorization state must be an object")
    exact(
        state,
        {"schemaVersion", "realm", "ownershipTag", "resourceServer", "resources", "roleAccess"},
        "authorization state",
    )
    if state["schemaVersion"] != 1 or state["realm"] != "tiffin":
        raise SeedError("only Tiffin authorization state version 1 is supported")
    if not isinstance(state["ownershipTag"], str) or not state["ownershipTag"].startswith("tiffin-"):
        raise SeedError("ownershipTag must use the tiffin- prefix")
    server = state["resourceServer"]
    if not isinstance(server, dict):
        raise SeedError("resourceServer must be an object")
    exact(server, {"id", "name"}, "resourceServer")
    if not SAFE_ID.fullmatch(server["id"]) or not isinstance(server["name"], str):
        raise SeedError("resourceServer has an invalid id or name")

    resources: dict[str, set[str]] = {}
    for item in state["resources"]:
        if not isinstance(item, dict):
            raise SeedError("resource entries must be objects")
        exact(item, {"id", "displayName", "uris", "scopes"}, "resource")
        if not SAFE_ID.fullmatch(item["id"]) or item["id"] in resources:
            raise SeedError("resource ids must be unique safe identifiers")
        if not isinstance(item["displayName"], str) or not item["displayName"].strip():
            raise SeedError("resource displayName must be non-empty")
        if not isinstance(item["uris"], list) or not item["uris"] or any(
            not isinstance(uri, str) or not uri.startswith("/") or any(c.isspace() for c in uri)
            for uri in item["uris"]
        ):
            raise SeedError("resource uris must be non-empty absolute paths")
        if not isinstance(item["scopes"], list) or not item["scopes"] or any(
            not isinstance(scope, str) or not SCOPE_ID.fullmatch(scope) for scope in item["scopes"]
        ):
            raise SeedError("resource scopes must use bounded dotted names")
        if len(item["scopes"]) != len(set(item["scopes"])):
            raise SeedError("resource scopes must be unique")
        resources[item["id"]] = set(item["scopes"])

    roles: set[str] = set()
    for item in state["roleAccess"]:
        if not isinstance(item, dict):
            raise SeedError("roleAccess entries must be objects")
        exact(item, {"role", "resources"}, "roleAccess")
        role = item["role"]
        if not isinstance(role, str) or not SAFE_ID.fullmatch(role) or role in roles:
            raise SeedError("roleAccess roles must be unique safe identifiers")
        roles.add(role)
        if not isinstance(item["resources"], dict) or not item["resources"]:
            raise SeedError("each role must reference at least one resource")
        for resource_id, scopes in item["resources"].items():
            if resource_id not in resources:
                raise SeedError(f"role {role} references unknown resource {resource_id}")
            if not isinstance(scopes, list) or not scopes or set(scopes) - resources[resource_id]:
                raise SeedError(f"role {role} contains an unknown scope for {resource_id}")
    return state


def read_secret(path: pathlib.Path) -> str:
    resolved = path.resolve()
    descriptor = os.open(resolved, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        details = os.fstat(descriptor)
        if not stat.S_ISREG(details.st_mode) or details.st_mode & 0o077:
            raise SeedError(f"secret file must be owner-only: {resolved}")
        raw = os.read(descriptor, 4097)
    finally:
        os.close(descriptor)
    if len(raw) > 4096:
        raise SeedError("secret file is too large")
    value = raw.decode("utf-8").rstrip("\r\n")
    if not value or any(character.isspace() for character in value):
        raise SeedError("secret file must contain one non-whitespace value")
    return value


class Keycloak:
    def __init__(self, base_url: str, token: str, realm: str):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.realm = realm

    def request(self, method: str, path: str, body: Any | None = None) -> Any:
        data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:500]
            raise SeedError(f"Keycloak {method} {path} failed with HTTP {exc.code}: {detail}") from None


def admin_token(base_url: str, username: str, password: str) -> str:
    data = urllib.parse.urlencode(
        {"grant_type": "password", "client_id": "admin-cli", "username": username, "password": password}
    ).encode()
    request = urllib.request.Request(
        base_url.rstrip("/") + "/realms/master/protocol/openid-connect/token",
        data=data,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        raise SeedError(f"Keycloak admin authentication failed with HTTP {exc.code}") from None
    token = result.get("access_token")
    if not isinstance(token, str) or not token:
        raise SeedError("Keycloak did not issue an admin access token")
    return token


def by_name(items: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    return next((item for item in items if item.get("name") == name), None)


def seed(state: dict[str, Any], api: Keycloak) -> dict[str, int]:
    realm_path = "/admin/realms/" + urllib.parse.quote(api.realm, safe="")
    realm = api.request("GET", realm_path)
    realm.update(
        {
            "registrationAllowed": True,
            "resetPasswordAllowed": True,
            "rememberMe": True,
            "verifyEmail": False,
            "loginTheme": "tiffin",
            "accountTheme": "tiffin",
        }
    )
    api.request("PUT", realm_path, realm)

    roles = {
        item["name"]: item
        for item in api.request("GET", realm_path + "/roles?first=0&max=200")
    }
    wanted_roles = {entry["role"] for entry in state["roleAccess"]}
    missing_roles = wanted_roles - set(roles)
    if missing_roles:
        raise SeedError("realm lacks required roles: " + ", ".join(sorted(missing_roles)))
    default_role = realm.get("defaultRole")
    if isinstance(default_role, dict) and default_role.get("id"):
        api.request("POST", realm_path + f"/roles-by-id/{default_role['id']}/composites", [roles["customer"]])

    groups = api.request("GET", realm_path + "/groups?briefRepresentation=false&first=0&max=200")
    def flatten(nodes: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return [child for node in nodes for child in ([node] + flatten(node.get("subGroups", [])))]
    seattle = by_name(flatten(groups), "seattle")
    if seattle and seattle.get("id"):
        api.request("PUT", realm_path + "/default-groups/" + seattle["id"])

    client_id = state["resourceServer"]["id"]
    query = urllib.parse.urlencode({"clientId": client_id})
    clients = api.request("GET", realm_path + "/clients?" + query)
    client_body = {
        "clientId": client_id,
        "name": state["resourceServer"]["name"],
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "standardFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True,
        "authorizationServicesEnabled": True,
        "attributes": {"tiffin.authorization.owner": state["ownershipTag"]},
    }
    if clients:
        client_uuid = clients[0]["id"]
        api.request("PUT", realm_path + "/clients/" + client_uuid, {**clients[0], **client_body})
    else:
        api.request("POST", realm_path + "/clients", client_body)
        clients = api.request("GET", realm_path + "/clients?" + query)
        if len(clients) != 1:
            raise SeedError("resource-server client was not created deterministically")
        client_uuid = clients[0]["id"]

    authz = realm_path + "/clients/" + client_uuid + "/authz/resource-server"
    scope_items = api.request("GET", authz + "/scope?first=0&max=500")
    scopes = {item["name"]: item for item in scope_items}
    for scope_name in sorted({scope for resource in state["resources"] for scope in resource["scopes"]}):
        body = {"name": scope_name, "displayName": scope_name.replace(".", " ").title()}
        current = scopes.get(scope_name)
        if current:
            api.request("PUT", authz + "/scope/" + current["id"], {**current, **body})
        else:
            api.request("POST", authz + "/scope", body)
    scopes = {item["name"]: item for item in api.request("GET", authz + "/scope?first=0&max=500")}

    resource_items = api.request("GET", authz + "/resource?first=0&max=500")
    resources = {item["name"]: item for item in resource_items}
    for item in state["resources"]:
        name = "tiffin-" + item["id"]
        body = {
            "name": name,
            "displayName": item["displayName"],
            "uris": item["uris"],
            "ownerManagedAccess": False,
            "scopes": [{"name": scope} for scope in item["scopes"]],
            "attributes": {"tiffin.authorization.owner": [state["ownershipTag"]]},
        }
        current = resources.get(name)
        if current:
            api.request("PUT", authz + "/resource/" + current["_id"], {**current, **body})
        else:
            api.request("POST", authz + "/resource", body)
    resources = {
        item["name"]: item for item in api.request("GET", authz + "/resource?first=0&max=500")
    }

    policies = {
        item["name"]: item for item in api.request("GET", authz + "/policy?first=0&max=500")
    }
    for access in state["roleAccess"]:
        name = "tiffin-role-" + access["role"]
        body = {
            "name": name,
            "description": f"Tiffin realm role {access['role']}",
            "type": "role",
            "logic": "POSITIVE",
            "decisionStrategy": "UNANIMOUS",
            "roles": [{"id": roles[access["role"]]["id"], "required": True}],
        }
        current = policies.get(name)
        if current:
            api.request("PUT", authz + "/policy/role/" + current["id"], body)
        else:
            api.request("POST", authz + "/policy/role", body)
    policies = {
        item["name"]: item for item in api.request("GET", authz + "/policy?first=0&max=500")
    }

    permissions = {
        item["name"]: item for item in api.request("GET", authz + "/permission?first=0&max=500")
    }
    permission_count = 0
    for access in state["roleAccess"]:
        policy = policies["tiffin-role-" + access["role"]]
        for resource_id, allowed_scopes in sorted(access["resources"].items()):
            name = "tiffin-" + access["role"] + "-" + resource_id
            body = {
                "name": name,
                "description": f"{access['role']} access to {resource_id}",
                "type": "scope",
                "logic": "POSITIVE",
                "decisionStrategy": "UNANIMOUS",
                "resources": [resources["tiffin-" + resource_id]["_id"]],
                "scopes": [scopes[scope]["id"] for scope in allowed_scopes],
                "policies": [policy["id"]],
            }
            current = permissions.get(name)
            if current:
                api.request("PUT", authz + "/permission/scope/" + current["id"], body)
            else:
                api.request("POST", authz + "/permission/scope", body)
            permission_count += 1

    return {
        "roles": len(wanted_roles),
        "scopes": len(scopes),
        "resources": len(state["resources"]),
        "permissions": permission_count,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("validate", "apply"))
    parser.add_argument("--state", type=pathlib.Path, default=DEFAULT_STATE)
    parser.add_argument("--base-url", default="http://127.0.0.1:38180")
    parser.add_argument("--admin-user-file", type=pathlib.Path)
    parser.add_argument("--admin-password-file", type=pathlib.Path)
    args = parser.parse_args()
    try:
        state = validate(load_json(args.state.resolve()))
        if args.command == "validate":
            result = {
                "valid": True,
                "roles": len(state["roleAccess"]),
                "resources": len(state["resources"]),
                "scopes": len({scope for item in state["resources"] for scope in item["scopes"]}),
            }
        else:
            if args.admin_user_file is None or args.admin_password_file is None:
                raise SeedError("apply requires --admin-user-file and --admin-password-file")
            username = read_secret(args.admin_user_file)
            password = read_secret(args.admin_password_file)
            token = admin_token(args.base_url, username, password)
            result = {"applied": True, **seed(state, Keycloak(args.base_url, token, state["realm"]))}
        print(json.dumps(result, sort_keys=True))
        return 0
    except (OSError, UnicodeError, json.JSONDecodeError, SeedError) as exc:
        print(json.dumps({"valid": False, "error": str(exc)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

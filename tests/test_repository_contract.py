import json
import pathlib
import subprocess
import sys
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
REALM = ROOT / "product/tiffin-local/import/tiffin-realm.json"
AUTHORIZATION = ROOT / "product/tiffin-local/authorization-state.json"


class RepositoryContractTests(unittest.TestCase):
    def test_authorization_state_passes_the_public_validator(self):
        result = subprocess.run(
            [sys.executable, "scripts/tiffin_authorization.py", "validate"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(
            json.loads(result.stdout),
            {"resources": 8, "roles": 5, "scopes": 16, "valid": True},
        )

    def test_realm_contains_only_bounded_public_demo_identities(self):
        realm = json.loads(REALM.read_text(encoding="utf-8"))
        humans = [user for user in realm["users"] if "serviceAccountClientId" not in user]

        self.assertEqual(len(humans), 9)
        self.assertEqual({user["groups"][0] for user in humans}, {
            "/cities/seattle", "/cities/austin", "/cities/platform"
        })
        for user in humans:
            self.assertEqual(user["email"], f'{user["username"]}@tiffin.example')
            self.assertEqual(user["credentials"], [{
                "type": "password",
                "value": f'{user["username"]}-lab',
                "temporary": False,
            }])
            self.assertNotIn("admin", user["username"])

    def test_development_client_secrets_are_explicitly_low_value(self):
        realm = json.loads(REALM.read_text(encoding="utf-8"))
        secrets = [client["secret"] for client in realm["clients"] if "secret" in client]

        self.assertGreaterEqual(len(secrets), 1)
        self.assertTrue(all(secret.startswith("lab-only-") for secret in secrets))

    def test_identity_and_authorization_contracts_are_consistent(self):
        realm = json.loads(REALM.read_text(encoding="utf-8"))
        state = json.loads(AUTHORIZATION.read_text(encoding="utf-8"))
        realm_roles = {role["name"] for role in realm["roles"]["realm"]}
        authorization_roles = {entry["role"] for entry in state["roleAccess"]}

        self.assertEqual(authorization_roles, {
            "customer", "restaurant-manager", "courier", "city-admin", "platform-admin"
        })
        self.assertTrue(authorization_roles.issubset(realm_roles))

    def test_public_documents_contain_no_persian_specific_letters(self):
        forbidden = set("پچژگک‌ی")
        candidates = [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
        for path in candidates:
            text = path.read_text(encoding="utf-8")
            self.assertFalse(forbidden.intersection(text), path)

    def test_public_theme_does_not_reuse_the_legacy_customer_yellow(self):
        forbidden = {
            "--tiffin-gold", "#f6c431", "#f5c94d", "#f4c430", "#f2c94c",
            "#fac928", "#ffd547", "#e6b520", "#c99512", "#e3b11d",
            "#c99612", "#ffda65", "#ddb43d", "#c69312", "#ffdf75",
        }
        candidates = sorted((ROOT / "themes" / "tiffin").rglob("*.css"))

        self.assertGreaterEqual(len(candidates), 2)
        for path in candidates:
            text = path.read_text(encoding="utf-8").lower()
            self.assertFalse(any(marker in text for marker in forbidden), path)


if __name__ == "__main__":
    unittest.main()

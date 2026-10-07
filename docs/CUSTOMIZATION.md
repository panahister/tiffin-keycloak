# Customization guide

This repository is intentionally source-driven. The table below identifies the authoritative location for
each change.

| Change | Source file or interface |
|---|---|
| Realm settings, clients, audiences, groups, seeded users | `product/tiffin-local/import/tiffin-realm.json` |
| Roles, resources, URIs, scopes, and permissions | `product/tiffin-local/authorization-state.json` |
| Idempotent authorization application | `scripts/tiffin_authorization.py` |
| Login and registration styling | `themes/tiffin/login/` |
| Account styling | `themes/tiffin/account/` |
| Lifecycle event mapping and privacy | `provider/src/main/java/.../IdentityEventMapper.java` |
| Kafka/outbox runtime policy | provider environment variables and `ProviderConfig.java` |
| Outbox schema | `db/migrations/` |
| Topic names and local partitions | `product/tiffin-local/docker/prepare-topics.sh` |

## Keycloak Admin Console

In the local stack, open the Keycloak Admin Console and select the `tiffin` realm.

- Users and group membership: **Users** and **Groups**.
- Realm role definitions and assignments: **Realm roles** and the user's **Role mapping** tab.
- Client audiences and service accounts: **Clients**.
- Resource server model: **Clients → tiffin-authorization → Authorization**.
- Localization: **Realm settings → Localization**.
- Login and account themes: **Realm settings → Themes**.

Console changes are useful for investigation, but reproducible changes must be reflected in source. A
realm re-import may replace or omit manual state, and a reviewer cannot audit configuration that exists
only in one database.

## Add a role

1. Add the realm role to `tiffin-realm.json`.
2. Add exactly one `roleAccess` entry to `authorization-state.json`.
3. Grant only existing scopes and resources.
4. Update the backend Access policy and its allowed/denied tests.
5. Run repository validation and the integrated role scenario.

## Add a resource or scope

1. Add a bounded resource identifier, non-empty URI set, and dotted scope names to the state file.
2. Map the scope only to roles that need it.
3. Implement and test authorization in the owning backend; Keycloak metadata alone is not enforcement.
4. Apply the seed twice to prove idempotency.

## Change the theme

Keep authentication semantics and accessibility intact. Password visibility is an icon-only control inside
the input, with an accessible label. Locale layout uses logical CSS properties and must be checked in
English/LTR and Arabic/RTL at desktop and mobile widths. Visible prose belongs in message bundles, not CSS
generated content.

## Production adaptation

Remove every seeded user and development secret. Require TLS, secure cookies, managed secret files,
email verification, production redirect origins, provider vulnerability acceptance, Kafka encryption and
ACLs, replicated topics, database backup/restore, and operational alerting. The public realm is a local
reference, not a hardened production realm.

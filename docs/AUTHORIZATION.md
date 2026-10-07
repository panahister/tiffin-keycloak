# Authorization model

The Tiffin realm separates identity administration from business authorization. Roles and tenant groups
are issued by Keycloak; each backend remains responsible for the final decision on every request.

## Realm roles

| Role | Purpose |
|---|---|
| `customer` | Browse, place and cancel own orders, track delivery, and manage own notifications |
| `restaurant-manager` | Manage a restaurant catalog and media, and operate its kitchen queue |
| `courier` | Participate in dispatch and report delivery position |
| `city-admin` | Govern city-scoped business identities and role decisions |
| `platform-admin` | Govern cross-city identity and authorization state |
| `service` | Identify a trusted service-to-service client; not a human role |

## Tenant groups

Users belong to `/cities/seattle`, `/cities/austin`, or `/cities/platform`. A protocol mapper writes the
group's `tenant_id` attribute into access tokens. A UI city selector never changes this authority.

## Resources and scopes

The reviewed state defines eight resources: catalog, orders, kitchen, dispatch, tracking, notifications,
media, and identity administration. Sixteen dotted scopes describe the supported actions. Role policies
connect these scopes to business roles; the seed uses an `UNANIMOUS` decision strategy.

The exact machine-readable model is
[`product/tiffin-local/authorization-state.json`](../product/tiffin-local/authorization-state.json).

## Apply the seed

First validate without a running Keycloak instance:

```bash
python3 scripts/tiffin_authorization.py validate
```

For a running local realm, place the Keycloak administrator username and password in separate owner-only
files, then apply:

```bash
chmod 600 /path/to/admin-user /path/to/admin-password
python3 scripts/tiffin_authorization.py apply \
  --base-url http://127.0.0.1:38180 \
  --admin-user-file /path/to/admin-user \
  --admin-password-file /path/to/admin-password
```

The script refuses credentials passed as command-line values, world-readable secret files, unknown state
fields, unsafe identifiers, unknown scopes, and unknown resources. It does not delete objects outside the
Tiffin ownership tag.

## Change control

Treat resource, scope, role, and URI changes as an authorization contract migration:

1. Update the closed state file.
2. Update realm roles or groups when required.
3. Add validator and backend policy tests.
4. Run `validate`, apply twice, and confirm identical counts.
5. Exercise allowed and denied calls for every affected role and tenant.
6. Review stale-token behavior; existing tokens retain old claims until refreshed or expired.

Do not add a generic role editor to the product UI as a shortcut. Administration remains a privileged,
audited identity-boundary operation.

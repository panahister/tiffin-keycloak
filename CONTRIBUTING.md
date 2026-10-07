# Contributing

Changes must preserve Keycloak as the identity source of truth, keep credentials out of business events,
and maintain idempotent authorization and outbox behavior.

Before opening a pull request:

```bash
python3 scripts/tiffin_authorization.py validate
python3 -m unittest discover -s tests -v
docker build --file product/tiffin-local/docker/Dockerfile --tag tiffin-keycloak:local .
```

Add a regression for defects. Authorization changes require allowed and denied role/tenant evidence.
Event changes require versioning, privacy review, duplicate-delivery behavior, retry/dead-letter behavior,
and consumer compatibility. Theme changes require English/LTR and Arabic/RTL review at desktop and mobile
widths.

Never commit real users, credentials, tokens, private URLs, production client secrets, database content,
or environment files. Follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and report vulnerabilities through
the private process in [SECURITY.md](SECURITY.md).

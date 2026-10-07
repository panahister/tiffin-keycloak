## Contract change

Describe the identity, event, authorization, migration, or theme behavior being changed.

## Security and compatibility

Describe privacy, credential, tenant, idempotency, retry/dead-letter, stale-token, and consumer impact.

## Evidence

- [ ] `python3 scripts/tiffin_authorization.py validate`
- [ ] `python3 -m unittest discover -s tests -v`
- [ ] Source image build and provider self-test
- [ ] Relevant allowed and denied paths
- [ ] No real credentials, personal data, private URLs, or machine paths

## Limits

State what this change does not prove or support.

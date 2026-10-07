# Security policy

Report suspected vulnerabilities privately through
[GitHub Security Advisories](https://github.com/panahister/tiffin-keycloak/security/advisories/new). Do not
publish credentials, tokens, personal data, private realm exports, or exploitable deployment details in a
public issue.

Include the affected revision, prerequisites, impact, minimal reproduction, and known mitigation. The
maintainer will investigate and coordinate disclosure after a fix or mitigation is available. No fixed
response time is promised for this proof-of-concept project.

Only the latest commit on `main` is evaluated for security fixes. The public realm contains explicit local
development credentials and secrets; none is acceptable in a shared or production environment.

Consumers are responsible for TLS, secure cookies, secret rotation and custody, redirect-origin policy,
email verification, administrator MFA, database hardening and backup, Kafka encryption/ACLs, image and
dependency scanning, monitoring, incident response, and compliance obligations.

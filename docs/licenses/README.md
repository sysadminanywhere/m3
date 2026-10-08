# M3 licensing

The first-publication model is free internal business use of Community, without code
modification or redistribution of customer builds; Scale adds licensed execution
capacity, multiple pools, autoscaling and external authentication rights.
This model is source-available, not OSI open source.

The public distribution contains Community implementations. Private Scale modules
are supplied in the official full distribution. The same full Docker image operates
for free in Community mode until paid features are activated by a valid entitlement.
Presence of private code alone does not require purchase. Paid activation and the
free right to run the image are separate grants. After the paid term and grace period,
the free Community-mode grant continues with the same image and retained data.

Community source and modules are governed by the Community agreement. Private Scale
modules and free use of the full distribution are governed by the Scale agreement.
The module boundary is implemented by separate Maven modules and the private sibling checkout. See [build instructions](../code-distribution.md).
For each release, list the covered modules and versions. Include both agreements
and applicable third-party licenses/notices in the full image; include the Community
agreement and applicable third-party notices in the public distribution. General
distribution terms must be finalized before publication; customer order fields are
required only for paid activation, not for Community-mode use.

- [Community agreement](COMMUNITY-LICENSE.md): English draft Community terms.
- [Scale agreement](SCALE-LICENSE.md): English draft commercial terms and order specification.

The source code and license have not yet been published. The root
[license notice](../../LICENSE.md) identifies the proposed Community / Scale terms.
Before first publication, complete the rights-holder identity, release contents,
contributor permissions and applicable-law fields. Third-party components retain
their respective licenses. The M3 agreements cover only materials owned by, or
properly licensed to, the rights holder. Runtime checks enforce entitlements in the
supplied application; they are not a technical guarantee against source tampering.

No acceptance, payment or publication is performed by these changes. Prices and
commercial dates are order fields. The current verifier supports finite terms with
14 days of grace; perpetual terms require a separate implementation.

## External authentication entitlement

Signed payloads may include `"externalAuthentication": true`. Missing or false means
no right, including for older Scale documents. Only a verified document for this
installation, within its validity/grace period, enables it. No database migration
is required because the entire signed document is already persisted.

Issuer example, in the private checkout: add `--external-authentication` to `scripts/ScaleOrders.py quote`.
The flag is recorded in the quote and included in the signed entitlement. Transfers
preserve it; renewal quotes explicitly select the purchased features.

`ScaleLicenseService.requireExternalAuthentication()` is the server-side guard for
future connectors. Each external login and continued use of external sessions must
call it; provider setup and role mapping also need authorization checks. Local admin
login must remain available when the right expires. OIDC/LDAP connectors are not
implemented by this change. No existing external login path exists to guard today.

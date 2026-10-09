# M3 licensing

The licensing model is free internal business use of Community, without code
modification or redistribution of customer builds; Scale adds licensed execution
capacity, multiple pools, autoscaling and external authentication rights.
This model is source-available, not OSI open source.

Users may process customers' data and provide services to customers through their
M3 installations without transferring copies of M3. This use does not by itself
constitute redistribution or sublicensing. Paid Scale use remains subject to the
order's installation, capacity, feature and term limits.
Contractors may install and administer M3 on the user's behalf in the user's
infrastructure. The user is responsible for their compliance; contractor activity
under paid Scale activation remains within the same order limits.

After termination of Community rights or the full distribution's free grant for
breach, the user retains permission to run the installed software solely to read
and export already stored data, without starting new message reception, processing
or delivery jobs. No separate export agreement is required. Termination of paid
Scale activation alone still permits ordinary use under the Community terms.

The public distribution contains Community implementations. Private Scale components
are supplied in the official full distribution. The same full Docker image operates
for free in Community mode until paid features are activated by a valid entitlement.
Presence of private code alone does not require purchase. Paid activation and the
free right to run the image are separate grants. After the paid term and grace period,
the free Community-mode grant continues with the same image and retained data.

M3's own source, documentation and corresponding executable components originating
from `m3` are governed by the Community agreement. M3's own private components
originating from `m3-scale` and free use of the full distribution are governed by
the Scale agreement. Copying Community materials into the Scale build or combining
them in one image does not change their Community terms. Third-party materials
retain their respective licenses regardless of repository or distribution format.
The code boundary is implemented by separate standalone projects and source selection during the Scale build. See [build instructions](../../README.md).
For each release, list the covered components and versions. Include both agreements
and applicable third-party licenses/notices in the full image; include the Community
agreement and applicable third-party notices in the public distribution. Customer order fields are
required only for paid activation, not for Community-mode use.

The general agreements apply to all releases distributed by the rights holder with
those agreements; they do not require a release-specific version field. Release
documentation identifies covered components and versions, and paid Scale orders
specify the supplied versions and components.

- [Community agreement](COMMUNITY-LICENSE.md): English Community terms, version 1.0.
- [Scale agreement](SCALE-LICENSE.md): English Scale terms, version 1.0, with a paid-order template.

The root [license notice](../../LICENSE.md) summarizes the Community / Scale terms.
The agreements take effect for each user upon acceptance, without a fixed effective date.
Installation, startup or use after the agreements have been provided with an
opportunity to read them constitutes acceptance, subject to mandatory applicable
law. No separate express confirmation is required. Provide both agreements before
installation, startup or use of the full distribution, including through its Docker
download documentation; provide the Community agreement for the public distribution.
Acceptance alone does not create a paid Scale order or a payment obligation.
For each distribution, provide the agreements as described above and identify
release contents. Igor Markin has confirmed ownership
of the project's own M3 code; third-party dependencies remain separately licensed. The
agreements do not select a country's law or an exclusive court; applicable law and
jurisdiction are determined under applicable legal rules. Third-party components retain
their respective licenses. The M3 agreements cover only materials owned by, or
properly licensed to, the rights holder. Runtime checks enforce entitlements in the
supplied application; they are not a technical guarantee against source tampering.

Prices and commercial dates are paid-order fields. Blank order fields do not
restrict the free Community-mode grant. The current verifier supports finite terms with
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

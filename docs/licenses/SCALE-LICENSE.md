# M3 Scale Commercial License Agreement вЂ” Draft 1.0

Rights holder / licensor: [FULL NAME], [COUNTRY], [DETAILS AND CONTACT].
User: the individual or organization using the official full distribution.
For a paid order, licensee: [ORGANIZATION / INDIVIDUAL NAME AND DETAILS].
Paid order, if applicable: [NUMBER], [DATE].
Covered materials: the private Scale components and their documentation owned by,
or properly licensed to, the licensor and identified in `m3-scale` for [VERSION], including its licensing, capacity policy and activation components.
The official full distribution combines Community and Scale components, including
in a Docker image. Community components remain governed by the
[Community agreement](COMMUNITY-LICENSE.md); this agreement does not replace their
independent free-use grant. Third-party components retain their respective licenses.

1. **Free distribution grant and paid activation.** The licensor grants the user a
   nonexclusive, free-of-charge right to download, install, start and use the official
   full distribution for its own purposes, including company operations, in Community
   mode. Internal copies, including copies in private internal image registries,
   and backups are permitted. This free grant has no fixed expiration date or
   installation count limit. No purchase, paid order or signed entitlement is required
   for Community mode. Private components may remain installed, and license verification
   and activation components may run, while paid features are disabled. This is a free
   operating mode, not a time-limited trial. The mere presence of Scale code in an
   official image does not create a payment obligation.

   Paid activation is a separate nonexclusive right to enable and use the Scale
   features expressly specified in an order, for its agreed installation, capacity
   and term, upon fulfillment of the order's payment conditions and installation
   of its valid signed entitlement. Activation uses the same full distribution;
   replacing the image solely to change license mode is not required.

   Code modification, redistribution of customer builds, transfer of covered copies
   to third parties and sublicensing are not permitted without written permission,
   whether the distribution operates in Community or Scale mode, subject to mandatory
   applicable law. Documented settings, routing rules and independent API clients
   are permitted as described in the Community agreement. Internal backup copies do
   not authorize concurrent paid use beyond the order's agreed installation scope.
2. **Paid order specification.** These fields apply only to paid activation. They
   are not prerequisites for free use of the official full distribution in Community
   mode. Before concluding a paid order, the parties complete:

   | Term | Value |
   | --- | --- |
   | Version / supplied components | [VERSION AND MODULES] |
   | Installation ID | [UUID] |
   | Maximum concurrently executing worker slots | [1вЂ“1000] |
   | Additional worker pools | [PERMITTED / NOT PERMITTED] |
   | Autoscaling | [YES / NO] |
   | External authentication | [YES / NO; SUPPLIED CONNECTORS] |
   | Start and end of term | [UTC START DATE], [UTC END DATE] |
   | Price, currency and taxes | [AMOUNT], [CURRENCY], [TAXES] |
   | Payment procedure and deadline | [TERMS] |
   | Test / standby / disaster recovery installations | [TERMS] |
   | Updates and support | [PERIOD, SCOPE AND SERVICE LEVEL] |
   | Renewal, capacity upgrades and refunds | [TERMS] |

3. **Technical confirmation.** Entitlements are confirmed by a signed offline document
   bound to the Installation ID. A signature does not replace this agreement or proof
   of payment. The server checks slot capacity, autoscaling, dates and the separate
   `externalAuthentication` flag. Additional pools are available when capacity exceeds
   one slot; the order specification must match this model. An external authentication
   entitlement does not itself supply a connector. Keycloak/AD connectors are not yet
   implemented in the current release and must not be sold as working features.
   Delivery of a connector requires a specified version and agreed order contents.
4. **End of term.** The current implementation supports finite expiration dates and a
   technical grace period of 14 days after expiration. Previously enabled features
   remain available during that period. Afterwards, the paid activation right ends,
   and new operations are limited to Community mode; active work finishes, and data,
   reading and export remain available. The free distribution grant continues,
   allowing use of the same official full image without renewal or reinstallation.
   Expiration or nonrenewal alone does not terminate that free grant or the Community
   agreement. External authentication is not permitted after the grace period;
   its future implementation must retain access through a local administrator.
   Removing the document or presenting an invalid signature returns the installation
   to Community without a grace period; free use remains permitted. Renewal of paid
   activation is not automatic and requires a new order and signed document.
5. **Transfer.** Transfer to another Installation ID is performed by the licensor upon
   confirmation that use of the previous installation has ceased. An offline document
   does not provide remote revocation of the previous license. Concurrent use of two
   installations is permitted only under expressly agreed order terms.
6. **Liability, termination and data.** Warranties and liability are governed by
   [AGREED TERMS], subject to mandatory statutory rights. Breach remedy and termination
   procedure: [TERMS]. Expiration or termination of paid activation alone does not
   terminate free Community use. Any termination of the free grant for a material
   breach requires notice and a 30-day opportunity to remedy the breach, consistent
   with the Community agreement. Independent Community and third-party rights remain
   governed by their respective agreements. Data belongs to the user; issuing a
   license does not grant the licensor rights to that data.
7. **Applicable law and disputes.** [APPLICABLE LAW AND DISPUTE RESOLUTION PROCEDURE].

Status: draft. Uncompleted fields do not establish a price, term, support commitment
or jurisdiction. Complete the general distribution terms before first publication;
complete a paid order before selling paid activation. Uncompleted paid-order fields
do not impose a purchase requirement on Community-mode use once the general agreement
is finalized. Third-party component licenses remain unaffected by this agreement.

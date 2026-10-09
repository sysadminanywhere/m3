# M3 Scale Commercial License Agreement - 1.0

Rights holder / licensor: Igor Markin.
Effective date: this agreement takes effect for the user upon the user's acceptance.
Applicability: all official M3 full distributions supplied by the licensor with this agreement.
Acceptance: by installing, starting or using the covered materials after this
agreement has been provided and the user has had an opportunity to read it, the
user accepts this agreement. No separate express confirmation is required.
A user who does not agree must not install, start or use the covered materials.
This acceptance provision is subject to mandatory applicable law. Acceptance alone
does not constitute a paid order or create a payment obligation.
User: the individual or organization using the official full distribution.
For paid activation, the licensee is the individual or organization identified in
the completed paid order.
Covered materials: M3's own private Scale source code and documentation originating
from the `m3-scale` repository, and the corresponding executable components, owned
by, or properly licensed to, the licensor and supplied with this agreement, including
its licensing, capacity policy and activation components. Community materials
originating from `m3` remain covered by the Community agreement, including when
copied into the `m3-scale` build. Third-party materials retain their respective licenses.
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

   Permitted use in Community mode and under paid activation includes processing
   customers' data and providing services to customers through the user's M3
   installation, without transferring copies of M3 to those customers. Such use
   does not by itself constitute redistribution or sublicensing. Paid use remains
   subject to the order's installation, capacity, feature and term limits. Rights
   in customers' data remain with their respective holders.

   Paid activation is a separate nonexclusive right to enable and use the Scale
   features expressly specified in an order, for its agreed installation, capacity
   and term, upon fulfillment of the order's payment conditions and installation
   of its valid signed entitlement. Activation uses the same full distribution;
   replacing the image solely to change license mode is not required.

   Contractors acting on the user's behalf may install and administer M3 in the
   user's infrastructure under the same terms. The user is responsible for their
   compliance. Access and copying solely for these activities within the user's
   infrastructure do not constitute prohibited transfer or sublicensing. Paid use
   by contractors counts toward the same order limits and grants no independent
   paid activation rights.

   Code modification, redistribution of customer builds, transfer of covered copies
   to third parties and sublicensing are not permitted without written permission,
   except for contractor access and copying expressly permitted above. These
   restrictions apply in both Community and Scale mode, subject to mandatory
   applicable law. Documented settings, routing rules and independent API clients
   are permitted as described in the Community agreement. Internal backup copies do
   not authorize concurrent paid use beyond the order's agreed installation scope.
2. **Paid order specification.** These fields apply only to paid activation. They
   are not prerequisites for free use of the official full distribution in Community
   mode. Before concluding a paid order, the parties complete:

   | Term | Value |
   | --- | --- |
   | Licensee | [ORGANIZATION / INDIVIDUAL NAME AND DETAILS] |
   | Paid order number and date | [NUMBER], [DATE] |
   | Version / supplied components | [VERSION AND MODULES] |
   | Installation ID | [UUID] |
   | Maximum concurrently executing worker slots | [1-1000] |
   | Additional worker pools | [PERMITTED / NOT PERMITTED] |
   | Autoscaling | [YES / NO] |
   | External authentication | [YES / NO; SUPPLIED CONNECTORS] |
   | Start and end of term | [UTC START DATE], [UTC END DATE] |
   | Price, currency and taxes | [AMOUNT], [CURRENCY], [TAXES] |
   | Payment procedure and deadline | [TERMS] |
   | Licensee email for notices | [EMAIL ADDRESS] |
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
6. **Liability, termination and data.** The materials are provided "as is", without
   a promise of uninterrupted operation, suitability for special requirements or
   absence of errors. The user is responsible for backups and configuration of its
   environment. Warranty and liability exclusions apply only to the extent permitted
   by applicable law; mandatory user rights remain unaffected. Support, updates,
   service levels and any additional warranties or liability commitments apply only
   as expressly agreed in the paid order or a separate written agreement. Paid
   activation alone does not include a support or service-level commitment.
   For a material breach of the paid activation terms, the licensor will give the
   licensee notice identifying the breach and allow 30 days after receipt of that
   notice to remedy it. Notices concerning a paid order, including notices of breach,
   are sent to the licensee email address specified in that order. The licensee must
   keep that address current and inform the licensor of any change. If the breach
   is not remedied within that period, the paid activation rights terminate,
   and the licensee must cease using the paid Scale
   features. Continued technical activation does not authorize use after termination.
   Expiration or termination of paid activation alone does not
   terminate free Community use. Any termination of the free grant for a material
   breach requires notice and 30 days after receipt to remedy the breach, consistent
   with the Community agreement. Independent Community and third-party rights remain
   governed by their respective agreements. Data belongs to the user; issuing a
   license does not grant the licensor rights to that data.
   If the free distribution grant is also terminated for breach, the user retains
   a limited right to run the installed covered materials solely to read and export
   data already stored in the installation. This right does not require a separate
   agreement and does not authorize new message reception, processing or delivery
   jobs, or continued use of paid Scale features. Termination of paid activation
   alone does not restrict processing independently permitted in Community mode.
7. **Applicable law and disputes.** This agreement does not designate a particular
   country's law or an exclusive court. Applicable law and jurisdiction are determined
   under the applicable conflict-of-laws and jurisdiction rules. Mandatory statutory
   rights remain unaffected.

The paid-order fields in section 2 are a template to complete before selling paid
activation. Blank fields do not establish a paid entitlement, price, term or support
commitment and do not impose a purchase requirement on Community-mode use.
This agreement must accompany the official full distribution and be provided with
an opportunity to read it before installation, startup or use. Acceptance is governed
by the acceptance provision above. Third-party component licenses remain unaffected.

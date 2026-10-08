# M3 Community License Agreement вЂ” Draft 1.0

Status: draft for first publication. Complete the fields below before release.
This is a source-available license, not an open-source license.

Rights holder: [RIGHTS HOLDER FULL NAME].
Country and contact: [COUNTRY], [CONTACT].
Release and effective date: [VERSION], [DATE].
Covered materials: the publicly supplied M3 Community source, executable application and documentation
owned by, or properly licensed to, the rights holder for [VERSION]. Private Scale code is covered by
the [M3 Scale agreement](SCALE-LICENSE.md), including when supplied in the same
official Docker image. Distribution format does not change the license applicable to each component.

1. **License grant.** The rights holder grants an individual or organization a
   nonexclusive, free-of-charge right to install and use the covered M3 Community
   materials for its own purposes, including commercial activities and internal
   company operations. This right has no fixed expiration date or installation
   count limit. The user retains all rights to its own data.
   It applies to Community components in both the standalone Community distribution
   and the official full distribution. Running the full distribution in Community
   mode is additionally authorized by the Scale agreement's free distribution grant;
   it does not require a purchased Scale entitlement.
2. **Permitted activities.** Running the software, configuring documented settings,
   connecting systems through documented interfaces, making backups and making
   internal copies are permitted. If source code is supplied, reading it and
   compiling it without changes for the user's own use are permitted. Settings,
   routing rules and independent programs using the API are not modifications
   to M3 code.
3. **Restrictions.** Modifying covered source or executable code, creating
   derivative versions, or publishing, selling, transferring or distributing
   customer builds of M3 to third parties requires separate written permission.
   These restrictions include transfers without payment. This agreement does not
   authorize transferring copies to other legal entities, including affiliates
   and contractors; they may obtain an official release from the rights holder.
   Restrictions apply only to the extent permitted by mandatory applicable law.
4. **Features.** Community includes local accounts, one worker pool and one
   concurrently executing worker slot, together with the message processing,
   delivery and troubleshooting features described for Community in the release
   documentation. Additional capacity, additional pools, autoscaling and external
   authentication, including Keycloak/OIDC, AD/LDAP and similar integrations,
   implemented by the private Scale components require a separate Scale entitlement.
   The public Community distribution does not include those private implementations.
   Their presence in an official full image does not require payment while the
   installation operates in Community mode and paid features remain disabled.
   Access to source code or a configuration setting does not itself grant a Scale
   entitlement. Circumventing paid-feature checks in the covered materials is prohibited.
5. **Third-party components.** Dependencies and other third-party materials remain
   subject to their own licenses. This agreement does not restrict rights granted
   by those licenses. Required third-party notices must be retained. The restrictions
   above apply to the covered M3 materials, not to independently licensed components.
6. **Warranties and liability.** The materials are provided "as is", without a promise
   of uninterrupted operation, suitability for special requirements or absence of
   errors. The user is responsible for backups and configuration of its environment.
   Warranty and liability exclusions apply only to the extent permitted by applicable
   law; mandatory user rights remain unaffected.
7. **Breach.** For a material breach, the rights holder will give notice and allow
   30 days to remedy it. If the breach is not remedied, rights under this agreement
   terminate. Termination does not transfer the user's data to the rights holder
   or terminate independent rights under third-party licenses. A separate agreement
   may permit data export after termination.
8. **Applicable law and contact.** [APPLICABLE LAW AND DISPUTE RESOLUTION PROCEDURE].
   Requests for additional rights should be sent to [CONTACT].

A grant under this draft requires a completed agreement for the specific release
and presentation of that agreement to the user before acceptance. This file does
not establish that a user has accepted the agreement.

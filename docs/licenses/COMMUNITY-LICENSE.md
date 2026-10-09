# M3 Community License Agreement - 1.0

This is a source-available license, not an open-source license.

Rights holder: Igor Markin.
Effective date: this agreement takes effect for the user upon the user's acceptance.
Applicability: all M3 Community releases distributed by the rights holder with this agreement.
Acceptance: by installing, starting or using the covered materials after this
agreement has been provided and the user has had an opportunity to read it, the
user accepts this agreement. No separate express confirmation is required.
A user who does not agree must not install, start or use the covered materials.
This acceptance provision is subject to mandatory applicable law.
Covered materials: M3's own source code and documentation originating from the `m3`
repository, and the corresponding executable components, owned by, or properly
licensed to, the rights holder and distributed with this agreement. These materials
remain Community components when included in a full distribution or copied into
the `m3-scale` build. Third-party materials retain their respective licenses.
M3's own private Scale components originating from `m3-scale` are covered by
the [M3 Scale agreement](SCALE-LICENSE.md), including when supplied in the same
official Docker image. Distribution format does not change the license applicable to each component.

1. **License grant.** The rights holder grants an individual or organization a
   nonexclusive, free-of-charge right to install and use the covered M3 Community
   materials for its own purposes, including commercial activities and internal
   company operations. This right has no fixed expiration date or installation
   count limit. The user retains all rights to its own data.
   Permitted use includes processing customers' data and providing services to
   customers through the user's M3 installation, without transferring copies of M3
   to those customers. Such use does not by itself constitute redistribution or
   sublicensing. Rights in customers' data remain with their respective holders.
   The license grant applies to Community components in both the standalone Community distribution
   and the official full distribution. Running the full distribution in Community
   mode is additionally authorized by the Scale agreement's free distribution grant;
   it does not require a purchased Scale entitlement.
2. **Permitted activities.** Running the software, configuring documented settings,
   connecting systems through documented interfaces, making backups and making
   internal copies are permitted. If source code is supplied, reading it and
   compiling it without changes for the user's own use are permitted. Settings,
   routing rules and independent programs using the API are not modifications
   to M3 code. Contractors acting on the user's behalf may install and administer
   M3 in the user's infrastructure under the same terms. The user is responsible
   for their compliance. Access and copying solely for these activities within
   the user's infrastructure do not constitute prohibited transfer or sublicensing.
3. **Restrictions.** Modifying covered source or executable code, creating
   derivative versions, or publishing, selling, transferring or distributing
   customer builds of M3 to third parties requires separate written permission.
   These restrictions include transfers without payment. This agreement does not
   authorize transferring copies to other legal entities, including affiliates
   and contractors, except for contractor access and copying expressly permitted
   under section 2; those entities may otherwise obtain an official release from
   the rights holder.
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
   30 days after receipt of the notice to remedy it. If the breach is not remedied,
   rights under this agreement terminate except for the limited data-access right
   below. Termination does not
   transfer the user's data to the rights holder or terminate independent rights
   under third-party licenses. After termination, the user retains a limited right
   to run the installed covered materials solely to read and export data already
   stored in the installation. This right does not require a separate agreement
   and does not authorize new message reception, processing or delivery jobs.
8. **Applicable law and disputes.** This agreement does not designate a particular
   country's law or an exclusive court. Applicable law and jurisdiction are determined
   under the applicable conflict-of-laws and jurisdiction rules. Mandatory statutory
   rights remain unaffected.

This agreement must accompany the covered materials and be provided to the user
with an opportunity to read it before installation, startup or use. Merely including
this file in a distribution does not establish that a user has accepted the agreement;
acceptance is governed by the acceptance provision above.

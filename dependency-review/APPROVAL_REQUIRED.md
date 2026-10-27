# Dependency update candidate — NOT APPROVED

Spring Boot plugin and BOM: 4.1.2. The plugin classpath constrains Commons Lang to 3.20.0. Project and buildscript dependency locks are candidates, not trust approvals.

All 732 files in artifact-inventory.PENDING.json require explicit human decisions. This includes 619 Gradle verification XML entries and 113 additional Maven POMs needed for Gradle module discovery. The observed XML omits those POMs; all 732 must be pinned by the eventual policy. No operational gradle/verification-metadata.xml or approval marker is installed.

The existing Colima network-none review successfully ran clean check: 266 executed, 237 NOT_RUN. Exact-version OSV queries for all 388 GAVs returned no matching advisories at review time. This is not a safety certificate or a formal BE PASS.

B/C trust gaps, signer identity and expiry/weak-signature exceptions, unexecuted integrations, final policy and canonical metadata require human review. Workspace Release remains NOT READY.

Evidence and approval plan are in the companion Workspace branch workspace/be-security-dependency-candidate: BE_SECURITY_UPDATE_REPORT.md, BE_SECURITY_DEPENDENCY_INVENTORY.csv and BE_SECURITY_APPROVAL_REQUIRED.md. Pin the final review commits and all manifest/lock/XML hashes in the approval record.

# Third-party licenses

Reviewed: 2026-10-24.

The original sa2web-file-parser code is licensed under [Apache-2.0](LICENSE).
Dependencies and container components retain their own licenses. The complete
container image includes GPL-family software and is not exclusively Apache-2.0.

## Scope

This review covers the current Gradle declarations, 92 resolved runtime JARs,
locally cached Maven POMs, bundled JAR notices, the Dockerfile, and package copyright
records from the locally built `sa2web-file-parser:amd64-verify` image. It is a
focused review, not a complete SBOM. Recheck the exact image digest and architecture
for each release because base images and OS packages can change.

## Runtime and container components

| Component | Where used | License information |
| --- | --- | --- |
| Eclipse Temurin / OpenJDK 21 | Runtime base `eclipse-temurin:21-jre-jammy`; a JDK is also present in the Gradle build stage | GPL-2.0 with the Classpath Exception. Qualifying independent Java modules may retain their own licenses; the runtime retains its own licensing obligations. |
| Bash, Coreutils, Tar and other GNU tools | Ubuntu base image | GPL-family terms, commonly GPL-3.0-or-later for these utilities, with component-specific exceptions and notices. |
| GNU libc (`libc6`) | Native runtime libraries | Primarily LGPL-2.1-or-later, with separately licensed portions. |
| GCC runtime (`libgcc-s1`, `libstdc++6`) | Native runtime libraries | GPL-family terms with applicable GCC runtime exceptions and component-specific notices. |
| GNU Readline (`libreadline8`) | OS dependencies in the inspected image | GPL-3.0-or-later. |
| JBIG-KIT (`libjbig0`) | Native imaging dependencies in the inspected image | Package copyright declares GPL-2.0-or-later. |
| Tesseract OCR | Installed by the Dockerfile with English and Simplified Chinese language data | Tesseract itself is Apache-2.0; its native dependencies have separate licenses. |

The OS entries are confirmed examples, not an exhaustive package list. The inspected
snapshot includes Bash 5.1-6ubuntu1.1, Coreutils 8.32-4.1ubuntu1.4,
Readline 8.1.2-1 and JBIG-KIT 2.1-3.1ubuntu0.22.04.1.
Inspect `/usr/share/doc/<package>/copyright` and `/usr/share/common-licenses/`
in the actual release image. A package copyright file can cover multiple licenses.

Sources: [Temurin FAQ](https://adoptium.net/docs/faq) and
[Tesseract documentation](https://tesseract-ocr.github.io/tessdoc/), alongside the
installed package copyright files.

## Java dependencies with GPL-family license options

| Resolved artifact | Available licenses and evidence |
| --- | --- |
| `org.codelibs:jhighlight:1.1.1` | CDDL-1.0 OR LGPL-2.1-or-later. The cached POM lists both; [upstream permits choosing either](https://github.com/codelibs/jhighlight#license). |
| `com.github.albfernandez:juniversalchardet:2.5.0` | MPL-1.1 with GPL/LGPL alternatives. The cached POM names GPL-3.0 and LGPL-3.0; [upstream](https://github.com/albfernandez/juniversalchardet#license) describes GPL-2.0-or-later and LGPL-2.1-or-later. The [2.5.0 release contains the MPL license](https://github.com/albfernandez/juniversalchardet/blob/v2.5.0/LICENSE). Check exact release source notices before selecting a GPL-family alternative. |
| `org.eclipse.angus:jakarta.mail:2.0.5` | EPL-2.0 with GPL-2.0 plus Classpath Exception as a conditional secondary license. The bundled `META-INF/NOTICE.md` makes the secondary license subject to EPL-2.0 conditions. [Source project](https://github.com/eclipse-ee4j/mail). |

These dependencies do not require choosing a GPL-family option. CDDL, MPL and EPL
still have their own notice and covered-source obligations. Preserve upstream
notices and record the chosen license when packaging. Some JAXB and Angus notices
mention GPL-licensed tools among historical third-party content; that text alone
does not make their BSD-3-Clause / Eclipse Distribution License artifacts GPL-only.

No AGPL declaration was identified in the reviewed dependency metadata. This is
not a certification of all bundled code or future dependency versions.

## Distribution requirements

The project's Apache-2.0 license does not replace third-party licenses. Merely
including separate OS tools does not establish that all parser code must become
GPL; exceptions and the actual manner of combination matter. Linking additional
GPL-only code requires a new review.

When distributing Java bundles, container images or offline image archives:

1. Preserve copyright notices, license texts and applicable exceptions.
2. Inventory exact dependency and OS package versions for the released artifacts.
3. Supply corresponding source and required build material, or a valid source offer,
   through a method allowed by each applicable license. This document's upstream
   links alone do not fulfill every source distribution requirement.
4. Meet applicable LGPL replacement/relinking requirements and MPL/CDDL/EPL
   covered-source availability requirements; document third-party modifications.

This document records findings. It does not supply a complete corresponding-source
bundle or certify a binary release as compliant. See the
[GNU GPL FAQ](https://www.gnu.org/licenses/gpl-faq.en.html) for discussion of
aggregation, interpreters and source distribution.

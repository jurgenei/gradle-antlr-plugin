# Gradle ANTLR Plugin

![Conformance](https://img.shields.io/badge/Conformance-Check--All%20Passing-brightgreen)

[![Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/org.flowdigger.gradle.antlr?label=Plugin%20Portal)](https://plugins.gradle.org/plugin/org.flowdigger.gradle.antlr)
[![Build and Test](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml)
[![JUnit Report](https://img.shields.io/badge/JUnit-Report-blue?logo=githubactions)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml)
[![Coverage CI](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml)
[![CodeQL](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/codeql.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/codeql.yml)
[![Dependency Check](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/dependency-check.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/dependency-check.yml)
[![SpotBugs Security](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/spotbugs-security.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/spotbugs-security.yml)
[![Checkstyle](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/checkstyle.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/checkstyle.yml)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](https://github.com/jurgenei/gradle-antlr-plugin/security/dependabot)
[![Coverage](https://codecov.io/gh/jurgenei/gradle-antlr-plugin/graph/badge.svg?branch=main)](https://codecov.io/gh/jurgenei/gradle-antlr-plugin)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21+-green.svg)](https://www.oracle.com/java/)
[![Gradle](https://img.shields.io/badge/gradle-9.5+-blue.svg)](https://gradle.org/)

## Position in XIR spine

- **Problem solved**: deterministic extraction of parser output into XML/XIR AST artifacts from source corpora.
- **Relation to XIR**: observation layer feeding derivation pipelines.
- **Why it matters**: turns language syntax into structured evidence for lineage and governance applications.

```text
Structured Knowledge -> XIR -> Observation -> Derivation -> Knowledge -> Applications
```

Next repository in reading path: [xir-g4](https://github.com/jurgenei/xir-g4).

## What plugin adds

- `xmlast` task (`org.flowdigger.gradle.antlr.XmlAstGradleTask`)
- `antlrG4XmlAst` task (`org.flowdigger.gradle.antlr.XmlAstG4GradleTask`)
- `antlrG4ToSchema` task (`org.flowdigger.gradle.xml.G4toSchemaTask`)
- runtime classpath wiring from Java `main` source set
- `classes` dependency wiring for `XmlAstGradleTask` and `XmlAstTask`

Legacy compatibility plugin id also available:

- `org.flowdigger.gradle.antlr.g4`
- adds `g4XmlAst` and `g4ToSchema`

## Task catalog

### `xmlast`

Purpose: convert source files (typically SQL) to XML AST or S-expression output.

Defaults:

- `sourceDirectory`: `src/main/sql`
- `destinationDirectory`: `target/xmlast`
- `includes`: `['**/*.sql']`
- `targetExtension`: `.xml`
- `startRule`: `script`
- `outputFormat`: `compact`

Minimal sample:

```groovy
tasks.named('xmlast', org.flowdigger.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xmlast'))

    parserClassName.set('org.flowdigger.parsers.PlSqlParser')
    lexerClassName.set('org.flowdigger.parsers.PlSqlLexer')
    startRule.set('script')

    includes.set(['**/*.sql'])
    targetExtension.set('.xml')
}
```

S-expression sample:

```groovy
tasks.register('xirast', org.flowdigger.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xir-ast'))

    parserClassName.set('org.flowdigger.parsers.PlSqlParser')
    lexerClassName.set('org.flowdigger.parsers.PlSqlLexer')
    startRule.set('script')

    includes.set(['**/*.sql'])
    targetExtension.set('.xir')
    outputFormat.set('beautified') // compact|beautified
}
```

### `antlrG4XmlAst`

Purpose: convert `.g4` files to XML AST with ANTLRv4 defaults preconfigured.

Preconfigured defaults:

- `grammar`: `antlr4`
- `parserClassName`: `org.flowdigger.parsers.ANTLRv4Parser`
- `lexerClassName`: `org.flowdigger.parsers.ANTLRv4Lexer`
- `startRule`: `grammarSpec`
- `includes`: `['**/*.g4']`

Sample:

```groovy
tasks.named('antlrG4XmlAst', org.flowdigger.gradle.antlr.XmlAstG4GradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/antlr'))
    destinationDirectory.set(layout.buildDirectory.dir('antlr-g4-xmlast'))
    targetExtension.set('.xml')
}
```

### `antlrG4ToSchema`

Purpose: derive grammar model + AST schema S-expression artifacts from `.g4` files.

Preconfigured defaults:

- `parserClassName`: `org.flowdigger.parsers.ANTLRv4Parser`
- `lexerClassName`: `org.flowdigger.parsers.ANTLRv4Lexer`
- `startRule`: `grammarSpec`
- `schemaOutputExtension`: `.schema.xir`
- `modelOutputExtension`: `.model.xir`

Sample (file-set mode):

```groovy
tasks.named('antlrG4ToSchema', org.flowdigger.gradle.xml.G4toSchemaTask) {
    fileset('src/main/antlr') {
        include '**/*.g4'
    }
    outputDir.set(layout.buildDirectory.dir('g4-model'))
    failOnError.set(true)
}
```

Run:

```bash
./gradlew xmlast
./gradlew antlrG4XmlAst
./gradlew antlrG4ToSchema
./gradlew xirast
```

## Quick start

Sample uses only:

- `java`
- `org.flowdigger.gradle.antlr`

Parser/Lexer classes come from regular runtime dependency jar. No grammar-specific Gradle plugin required.

```groovy
plugins {
    id 'java'
    id 'org.flowdigger.gradle.antlr'
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation 'org.antlr:antlr4-runtime'

    // Example parser jar. Replace with your own parser artifact.
    implementation 'org.flowdigger.gradle:gradle-antlr-plsql-plugin'
}

tasks.named('xmlast', org.flowdigger.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xmlast'))

    parserClassName.set('org.flowdigger.parsers.PlSqlParser')
    lexerClassName.set('org.flowdigger.parsers.PlSqlLexer')
    startRule.set('script')

    includes.set(['**/*.sql'])
    targetExtension.set('.xml')
    continueOnError.set(true)
}
```

Run:

```bash
./gradlew xmlast
```

## G4 compatibility id

Use only when you need `g4XmlAst` or `g4ToSchema` tasks from legacy id:

```groovy
plugins {
    id 'java'
    id 'org.flowdigger.gradle.antlr.g4'
}
```

# Gradle ANTLR Plugin

![Conformance](https://img.shields.io/badge/Conformance-Check--All%20Passing-brightgreen)

[![Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/name.jurgenei.gradle.antlr?label=Plugin%20Portal)](https://plugins.gradle.org/plugin/name.jurgenei.gradle.antlr)
[![Build and Test](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml)
[![Coverage CI](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml)
[![Coverage](https://codecov.io/gh/jurgenei/gradle-antlr-plugin/graph/badge.svg)](https://codecov.io/gh/jurgenei/gradle-antlr-plugin)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## What plugin adds

- `xmlast` task (`name.jurgenei.gradle.antlr.XmlAstGradleTask`)
- `antlrG4XmlAst` task (`name.jurgenei.gradle.antlr.XmlAstG4GradleTask`)
- `antlrG4ToSchema` task (`name.jurgenei.gradle.xml.G4toSchemaTask`)
- runtime classpath wiring from Java `main` source set
- `classes` dependency wiring for `XmlAstGradleTask` and `XmlAstTask`

Legacy compatibility plugin id also available:

- `name.jurgenei.gradle.antlr.g4`
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
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xmlast'))

    parserClassName.set('name.jurgenei.parsers.PlSqlParser')
    lexerClassName.set('name.jurgenei.parsers.PlSqlLexer')
    startRule.set('script')

    includes.set(['**/*.sql'])
    targetExtension.set('.xml')
}
```

S-expression sample:

```groovy
tasks.register('xirast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xir-ast'))

    parserClassName.set('name.jurgenei.parsers.PlSqlParser')
    lexerClassName.set('name.jurgenei.parsers.PlSqlLexer')
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
- `parserClassName`: `name.jurgenei.parsers.ANTLRv4Parser`
- `lexerClassName`: `name.jurgenei.parsers.ANTLRv4Lexer`
- `startRule`: `grammarSpec`
- `includes`: `['**/*.g4']`

Sample:

```groovy
tasks.named('antlrG4XmlAst', name.jurgenei.gradle.antlr.XmlAstG4GradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/antlr'))
    destinationDirectory.set(layout.buildDirectory.dir('antlr-g4-xmlast'))
    targetExtension.set('.xml')
}
```

### `antlrG4ToSchema`

Purpose: derive grammar model + AST schema S-expression artifacts from `.g4` files.

Preconfigured defaults:

- `parserClassName`: `name.jurgenei.parsers.ANTLRv4Parser`
- `lexerClassName`: `name.jurgenei.parsers.ANTLRv4Lexer`
- `startRule`: `grammarSpec`
- `schemaOutputExtension`: `.schema.xir`
- `modelOutputExtension`: `.model.xir`

Sample (file-set mode):

```groovy
tasks.named('antlrG4ToSchema', name.jurgenei.gradle.xml.G4toSchemaTask) {
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
- `name.jurgenei.gradle.antlr`

Parser/Lexer classes come from regular runtime dependency jar. No grammar-specific Gradle plugin required.

```groovy
plugins {
    id 'java'
    id 'name.jurgenei.gradle.antlr'
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation 'org.antlr:antlr4-runtime'

    // Example parser jar. Replace with your own parser artifact.
    implementation 'name.jurgenei.gradle:gradle-antlr-plsql-plugin'
}

tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xmlast'))

    parserClassName.set('name.jurgenei.parsers.PlSqlParser')
    lexerClassName.set('name.jurgenei.parsers.PlSqlLexer')
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
    id 'name.jurgenei.gradle.antlr.g4'
}
```

# Gradle ANTLR Plugin

![Conformance](https://img.shields.io/badge/Conformance-Check--All%20Passing-brightgreen)

[![Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/name.jurgenei.gradle.antlr?label=Plugin%20Portal)](https://plugins.gradle.org/plugin/name.jurgenei.gradle.antlr)
[![Build and Test](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml)
[![Coverage CI](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml)
[![Coverage](https://codecov.io/gh/jurgenei/gradle-antlr-plugin/graph/badge.svg)](https://codecov.io/gh/jurgenei/gradle-antlr-plugin)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21+-green.svg)](https://www.oracle.com/java/)
[![Gradle](https://img.shields.io/badge/gradle-8+-blue.svg)](https://gradle.org/)

Plugin Portal page: https://plugins.gradle.org/plugin/name.jurgenei.gradle.antlr

## What Plugin Adds

- `xmlast` task (`name.jurgenei.gradle.antlr.XmlAstGradleTask`)
- runtime classpath wiring from Java `main` source set
- `classes` dependency wiring for `XmlAstGradleTask` and `XmlAstTask`

Legacy compatibility plugin id also available:

- `name.jurgenei.gradle.antlr.g4`
- adds `g4XmlAst` and `g4ToClass`

## Quick Start (No Extra Gradle Plugin Dependencies)

Sample uses only:

- `java`
- `name.jurgenei.gradle.antlr`

Parser/Lexer classes come from regular runtime dependency jar. No grammar-specific Gradle plugin required.

```groovy
plugins {
    id 'java'
    id 'name.jurgenei.gradle.antlr' version '0.1.5'
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation 'org.antlr:antlr4-runtime:4.13.2'

    // Example: parser jar published independently.
    // Replace with your own parser artifact.
    implementation 'name.jurgenei.gradle:gradle-antlr-plsql-plugin:0.1.3'
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

## G4 Compatibility ID

Use only when you need `g4XmlAst` or `g4ToClass` tasks from legacy id:

```groovy
plugins {
    id 'java'
    id 'name.jurgenei.gradle.antlr.g4' version '0.1.5'
}
```


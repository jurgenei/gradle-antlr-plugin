# Gradle ANTLR Plugin (`name.jurgenei.gradle.antlr`)

[![Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/name.jurgenei.gradle.antlr?label=Plugin%20Portal)](https://plugins.gradle.org/plugin/name.jurgenei.gradle.antlr)
![Java](https://img.shields.io/badge/Java-21%2B-007396?logo=openjdk)
![Gradle](https://img.shields.io/badge/Gradle-8%2B-02303A?logo=gradle)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
[![CI](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml)

Turn SQL files into XML AST files in your Gradle build.

This plugin is designed for teams that need reliable, repeatable SQL parsing in CI/CD and local development without custom shell scripts.

## Why You Would Use This

- Build lineage inputs from SQL procedures, functions, and views
- Feed SQL ASTs into XSLT/JSON transforms and governance pipelines
- Keep parser execution as a normal Gradle task (incremental-friendly, scriptable, easy to automate)
- Handle large file sets with configurable execution model and parallelism

## Requirements

- Java 21+
- Gradle 8+

## Install

```groovy
plugins {
    id 'name.jurgenei.gradle.antlr' version '0.1.1'
}
```

Plugin Portal page: https://plugins.gradle.org/plugin/name.jurgenei.gradle.antlr

## Quick Start (Most Common Setup)

This setup uses already generated parser/lexer classes on your runtime classpath.

```groovy
plugins {
    id 'java'
    id 'name.jurgenei.gradle.antlr' version '0.1.1'
}

repositories {
    mavenCentral()
}

tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.buildDirectory.dir('xmlast'))

    parserClassName.set('com.example.sql.PlSqlParser')
    lexerClassName.set('com.example.sql.PlSqlLexer')
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

## Core Use Cases

### 1) Parse a full SQL tree for lineage ingestion

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(file('/path/to/oracle'))
    destinationDirectory.set(file('/path/to/oracle.output'))
    includes.set([
        '{buss,cons,sdp,dsa}/procedures/*.sql',
        '{buss,cons,sdp,dsa}/procedures/**/*.sql'
    ])
}
```

### 2) Keep builds moving even with a few parser errors

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    continueOnError.set(true)
    failOnError.set(false)
    failOnTransformationError.set(false)
    suppressStackTrace.set(true)
}
```

### 3) Improve throughput on larger file sets

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    executionModel.set('VIRTUAL_THREADS')
    parallelism.set(3)
}
```

### 3b) Recommended memory settings for very large batches

For large runs (for example, tens of thousands of files), tune the new memory-pressure controls:

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    // Keep queue depth bounded to avoid excessive in-memory backlog.
    maxInFlightJobs.set(16)
    // Run cache-pressure cleanup more frequently during long runs.
    cachePressureCheckInterval.set(32)
    // Start pressure mitigation before heap usage gets too close to max.
    memoryPressureThresholdPercent.set(80)
}
```

### 4) Force a clean re-parse when needed

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    force.set(true)
}
```

## Output Behavior

For each selected source file, one XML file is created in the destination tree with relative path preserved.

- Input: `src/main/sql/demo/query.sql`
- Output: `build/xmlast/demo/query.xml`

## Configuration Reference (`XmlAstGradleTask`)

Most-used properties:

- `sourceDirectory`: input root
- `destinationDirectory`: output root
- `includes` / `excludes`: glob filters
- `parserClassName` / `lexerClassName`: parser coordinates
- `startRule`: parser entry point
- `targetExtension`: usually `.xml`
- `force`: bypass timestamp checks
- `continueOnError`, `failOnError`, `failOnTransformationError`, `suppressStackTrace`
- `executionModel`: `SEQUENTIAL`, `PLATFORM_THREADS`, `VIRTUAL_THREADS`
- `parallelism`: worker cap for threaded models
- `compression`: compact rule-chain output
- `catalogFile` / `catalogGrammar`: optional catalog-driven config
- `runtimeClasspath`: where parser/runtime classes are loaded from

## Catalog-Based Setup (Team-Friendly)

Use a shared `catalog.xml` to keep parser details out of build scripts.

`catalog.xml`:

```xml
<catalog>
  <grammar
      name="plsql"
      parser="com.example.sql.PlSqlParser"
      lexer="com.example.sql.PlSqlLexer"
      start-rule="script"/>
</catalog>
```

`build.gradle`:

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    catalogFile.set(layout.projectDirectory.file('catalog.xml'))
    catalogGrammar.set('plsql')
}
```

## Optional: Grammar Source (`.g4`) Coordinates

If your parser/lexer are provided as grammar sources, you can point the task directly to `.g4` locations.

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    parserClassName.set('https://example.org/grammars/PlSqlParser.g4')
    lexerClassName.set('https://example.org/grammars/PlSqlLexer.g4')
    startRule.set('script')
}
```

## Troubleshooting

- `ClassNotFoundException` for parser/lexer:
  - Ensure parser classes are on `runtimeClasspath`
  - Ensure generation/compile steps run before `xmlast`
- `NoSuchMethodException` for start rule:
  - Confirm `startRule` matches a real parser entry method
- `catalogGrammar is required when catalogFile is configured`:
  - Set both `catalogFile` and `catalogGrammar`
- Build runs but no output files:
  - Verify include patterns and source directory
  - Use `force=true` for one clean pass

## Typical Workflow in CI

```bash
./gradlew clean xmlast
```

Then consume generated XML from `destinationDirectory` in downstream tasks.

## Version

Current plugin version in examples: `0.1.1`.

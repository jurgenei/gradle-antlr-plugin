# gradle-antlr-plugin

![Java](https://img.shields.io/badge/Java-21%2B-007396?logo=openjdk)
![Gradle](https://img.shields.io/badge/Gradle-8%2B-02303A?logo=gradle)
![ANTLR](https://img.shields.io/badge/ANTLR-4.13.x-blue)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
![Status](https://img.shields.io/badge/status-active-success)
[![CI](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/ci.yml)
[![Coverage CI](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml/badge.svg)](https://github.com/jurgenei/gradle-antlr-plugin/actions/workflows/coverage.yml)
[![Coverage](https://codecov.io/gh/jurgenei/gradle-antlr-plugin/branch/main/graph/badge.svg)](https://codecov.io/gh/jurgenei/gradle-antlr-plugin)

Gradle plugin for converting SQL source trees into XML AST files using dynamic ANTLR parser/lexer loading.

## Highlights

- plugin id: `xmlast`
- default task: `xmlast` (`XmlAstGradleTask`)
- legacy task type: `XmlAstTask`
- dynamic runtime parser mode:
  - `parserClassName` (class name or `.g4` coordinate)
  - `lexerClassName` (class name or `.g4` coordinate)
  - `startRule`
  - `compression`
- catalog support (`catalog.xml`) with required attributes:
  - `name`
  - `parser`
  - `lexer`
  - `start-rule`
  - optional: `runtimeGrammar` (metadata only)

## Latest Insights (May 2026)

- fail-fast input validation is now enforced in main conversion entry points (`convertFileTree`, `convertFileTreeWithStats`, task execution)
- runtime conversion flow was refactored into focused internal methods (clearer orchestration, easier maintenance and testing)
- bounded parallel execution is supported through `executionModel` + `parallelism`
  - `SEQUENTIAL`
  - `PLATFORM_THREADS`
  - `VIRTUAL_THREADS`
- end-of-run conversion summary includes:
  - files processed / files with errors / success percentage
  - estimated sequential time
  - total time
  - average time per file
  - execution profile with speedup multiplier
- per-file parse output includes duration and size metadata (`<file> <duration>s <lines>:<bytes> parsed`)
- JaCoCo coverage is integrated in CI with Codecov upload and badge support

## Requirements

- Java 21+
- Gradle 8+

## Test Coverage

Generate coverage report + enforce minimum threshold (line coverage >= 20%):

```bash
./gradlew coverage
```

Coverage report outputs:

- XML: `build/reports/jacoco/test/jacocoTestReport.xml`
- HTML: `build/reports/jacoco/test/html/index.html`

CI also publishes coverage summary and uploads JaCoCo XML to Codecov for the badge.

### Codecov Setup (remaining steps)

This project uses **Gradle + JaCoCo** (not `pytest`) and uploads:
`build/reports/jacoco/test/jacocoTestReport.xml`

1. Open Codecov setup for repo: `https://app.codecov.io/github/jurgenei/gradle-antlr-plugin/new`
2. In Codecov, copy the repository upload token
3. In GitHub repo settings, add secret:
   - `Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret`
   - Name: `CODECOV_TOKEN`
   - Value: `<token from Codecov>`
4. Trigger the `Coverage` workflow (push or re-run)
5. Verify in workflow summary and Codecov UI that upload succeeds

If `CODECOV_TOKEN` is missing, CI will skip Codecov upload and print a notice in the job summary.

## Apply Plugin

```groovy
plugins {
    id 'java'
    id 'xmlast'
}
```

## Quick Start (Dynamic ANTLR)

```groovy
plugins {
    id 'java'
    id 'antlr'
    id 'xmlast'
}

repositories {
    mavenCentral()
}

dependencies {
    antlr 'org.antlr:antlr4:4.13.1'
    implementation 'org.antlr:antlr4-runtime:4.13.1'
}

generateGrammarSource {
    arguments += ['-package', 'com.example.sql']
}

tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))

    // Class-name mode.
    parserClassName.set('com.example.sql.MySqlParser')
    lexerClassName.set('com.example.sql.MySqlLexer')
    startRule.set('script')
    compression.set(false)

    includes.set(['**/*.sql'])
    targetExtension.set('.xml')
}
```

Run:

```bash
./gradlew xmlast
```

Example with bounded parallelism:

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    executionModel.set('VIRTUAL_THREADS')
    parallelism.set(4)
    continueOnError.set(true)
}
```

## Catalog Configuration

`catalog.xml`:

```xml
<catalog>
  <grammar
      name="plsql"
      runtimeGrammar="oracle"
      parser="com.example.sql.MySqlParser"
      lexer="com.example.sql.MySqlLexer"
      start-rule="script"/>
</catalog>
```

Task configuration:

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))

    // class names load generated parser/lexer classes from runtime classpath
    parserClassName.set('com.example.sql.MySqlParser')
    lexerClassName.set('com.example.sql.MySqlLexer')

    catalogFile.set(layout.projectDirectory.file('catalog.xml'))
    catalogGrammar.set('plsql')
}
```

When catalog is configured, the selected entry can override:

- parser entry rule (`start-rule`)

The selected catalog entry also provides default parser/lexer class names when
`parserClassName` / `lexerClassName` are not set directly on the task.

Catalog parser/lexer coordinates can also point to grammar sources (`.g4`), including:

- local file paths
- `file:` URIs
- `http(s)` URLs
- protocol-less host paths (for example `localhost:8080/MiniParser.g4`)

When `.g4` coordinates are used, the plugin generates and compiles parser/lexer classes at runtime.

## Runtime Grammar Source Mode (`.g4`)

You can point task properties directly to grammar sources:

```groovy
tasks.named('xmlast', name.jurgenei.gradle.antlr.XmlAstGradleTask) {
    sourceDirectory.set(layout.projectDirectory.dir('src/main/sql'))
    destinationDirectory.set(layout.projectDirectory.dir('build/xmlast'))

    // Grammar-source mode.
    parserClassName.set('localhost:8080/MiniParser.g4')
    lexerClassName.set('localhost:8080/MiniLexer.g4')
    startRule.set('root')

    // Required so runtime compilation can resolve ANTLR + custom superclasses.
    runtimeClasspath.from(sourceSets.main.runtimeClasspath)
}
```

### `superClass` support in grammars

Runtime generation supports lexer and parser superclasses, for example:

```antlr
lexer grammar ANTLRv4Lexer;

options {
  superClass = name.jurgenei.parsers.LexerAdaptor;
}
```

```antlr
parser grammar ANTLRv4Parser;

options {
  tokenVocab = ANTLRv4Lexer;
  superClass = name.jurgenei.parsers.ParserBase;
}
```

Superclass types must be available on `runtimeClasspath`.

## Task Types

### `XmlAstGradleTask` (recommended)

Main properties:

- `sourceDirectory`
- `destinationDirectory`
- `includes` / `excludes`
- `targetExtension`
- `force`
- `executionModel` (`SEQUENTIAL`, `PLATFORM_THREADS`, `VIRTUAL_THREADS`)
- `parallelism`
- `failOnError`
- `failOnTransformationError`
- `continueOnError`
- `suppressStackTrace`
- `parserClassName` / `lexerClassName` / `startRule`
- `compression`
- `enableDFAMonitoring`
- `catalogFile` / `catalogGrammar`
- `runtimeClasspath`

### `XmlAstTask` (legacy-compatible)

Main properties:

- `sourceTrees`
- `destinationDirectory`
- `includes`
- `targetExtension`
- `parserClassName` / `lexerClassName` / `startRule`
- `compression`
- `catalogFile` / `catalogGrammar`
- `runtimeClasspath`

## Output

For each selected source file, the plugin writes one XML file under destination directory preserving relative paths.

Example:

- input: `src/main/sql/demo/query.sql`
- output: `build/xmlast/demo/query.xml`

## Development

Run all tests:

```bash
./gradlew test
```

Run a specific functional test:

```bash
./gradlew test --tests 'name.jurgenei.gradle.antlr.XmlAstPluginFunctionalTest'
```

## Troubleshooting

- `ClassNotFoundException` for parser/lexer classes:
  - ensure grammar generation and compilation run before `xmlast`.
  - verify `runtimeClasspath` includes generated class directories and ANTLR runtime.
- task fails when using catalog URL/`.g4` coordinates:
  - parser and lexer must both be `.g4` coordinates (or both class names).
  - for `localhost:port/...` coordinates, ensure the local server is reachable during task execution.
  - ensure `runtimeClasspath` includes required superclass types if grammars use `options { superClass = ...; }`.
- `NoSuchMethodException` for start rule:
  - verify `startRule` matches an actual parser entry method (e.g. `script`, `grammarSpec`).
  - if using catalog, confirm `start-rule` is present and correct.
- `catalogGrammar is required when catalogFile is configured`:
  - set both `catalogFile` and `catalogGrammar` on the task.
- parse failures on valid input:
  - verify the selected parser/lexer classes match the target grammar version.
  - run with stacktrace for diagnostics:

```bash
./gradlew xmlast --stacktrace
```
- guard-clause validation failures (for example blank `startRule`, invalid `executionModel`, non-positive `parallelism`):
  - check task configuration values first; these failures now happen early by design.

## Status

This module is the plugin core extracted from `gradle-antlr-xml-plugin` and now focuses on dynamic ANTLR parsing workflows.

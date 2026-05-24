# gradle-antlr-plugin

![Java](https://img.shields.io/badge/Java-21%2B-007396?logo=openjdk)
![Gradle](https://img.shields.io/badge/Gradle-8%2B-02303A?logo=gradle)
![ANTLR](https://img.shields.io/badge/ANTLR-4.13.x-blue)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
![Status](https://img.shields.io/badge/status-active-success)

Gradle plugin for converting SQL source trees into XML AST files using dynamic ANTLR parser/lexer loading.

## Highlights

- plugin id: `xmlast`
- default task: `xmlast` (`XmlAstGradleTask`)
- legacy task type: `XmlAstTask`
- dynamic runtime parser mode:
  - `parserClassName`
  - `lexerClassName`
  - `startRule`
- catalog support (`catalog.xml`) with required attributes:
  - `name`
  - `parser`
  - `lexer`
  - `start-rule`
  - optional: `runtimeGrammar` (metadata only)

## Requirements

- Java 21+
- Gradle 8+

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

    // still required in dynamic mode to load generated classes
    parserClassName.set('com.example.sql.MySqlParser')
    lexerClassName.set('com.example.sql.MySqlLexer')
    startRule.set('script')

    includes.set(['**/*.sql'])
    targetExtension.set('.xml')
}
```

Run:

```bash
./gradlew xmlast
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

## Task Types

### `XmlAstGradleTask` (recommended)

Main properties:

- `sourceDirectory`
- `destinationDirectory`
- `includes` / `excludes`
- `targetExtension`
- `force`
- `failOnError`
- `failOnTransformationError`
- `parserClassName` / `lexerClassName` / `startRule`
- `catalogFile` / `catalogGrammar`
- `runtimeClasspath`

### `XmlAstTask` (legacy-compatible)

Main properties:

- `sourceTrees`
- `destinationDirectory`
- `includes`
- `targetExtension`
- `parserClassName` / `lexerClassName` / `startRule`
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

## Status

This module is the plugin core extracted from `gradle-antlr-xml-plugin` and now focuses on dynamic ANTLR parsing workflows.

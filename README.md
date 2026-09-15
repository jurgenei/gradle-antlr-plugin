Why You Would Use This

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
## G4 Support Merged

`gradle-antlr-g4-plugin` behavior now lives in this plugin.

- Core id: `name.jurgenei.gradle.antlr`
- Legacy compatibility id: `name.jurgenei.gradle.antlr.g4`
- G4 tasks now available from merged core implementation: `g4XmlAst`, `g4ToClass`

Legacy id is rerouted and planned for decommission after 2 release cycles.

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
    continueOnError.set(true### Quick Start for `.g4` grammars + AST classes

```groovy
plugins {
    id 'java'
    id 'name.jurgenei.gradle.antlr.g4' version '0.1.1'
}

tasks.named('g4ToClass', name.jurgenei.gradle.xml.G4toClassTask) {
    fileset('src/main/antlr') {
        include '**/*.g4'
    }
    outputDir.set(layout.buildDirectory.dir('g4-classes'))
}
```


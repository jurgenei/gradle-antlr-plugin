# gradle-antlr-plugin

Core Gradle plugin extracted from `gradle-antlr-xml-plugin`.

## What is included

- Plugin entrypoint: `name.jurgenei.gradle.antlr.XmlAstPlugin`
- Task APIs: `XmlAstTask`, `XmlAstGradleTask`
- Catalog support: XML catalog loader in `name.jurgenei.gradle.antlr.catalog`

## Current status

This is the first refactor slice. Grammar definitions are moved to separate repositories:

- `../antlr-grammars-plsql`
- `../antlr-grammars-g4`

At this stage the plugin task API is extracted and keeps runtime converter loading behavior via reflection.

## Validate

```bash
./gradlew test
```

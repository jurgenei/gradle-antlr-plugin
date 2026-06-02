# Release Notes (Next Tag)

Date: 2026-05-29
Scope: `gradle-antlr-plugin` release notes sync for `0.1.1`

## Highlights

- Version line remains on `0.1.1` for the release branch.
- Dynamic parser/lexer validation and error surfacing improvements remain part of this cycle.
- Security/quality gates and coverage workflow support remain in place.

## Notes

- This release-note update is a repository-level sync entry for the release branch.
- Any ongoing source edits in working tree should be reviewed and released separately as needed.

## Quality Automation

- Added baseline `qodana.yaml` for JVM community linting on JDK 21.
- Added `.github/workflows/qodana_code_quality.yml` aligned with CI branch policy (`main`/`master`).
- Qodana workflow uses read-only permissions and publishes scan results without auto-fixes.


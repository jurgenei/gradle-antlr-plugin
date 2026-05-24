# Mini Grammar Test Data

These fixtures target:

- `src/test/resources/grammars/mini/lexer.g4`
- `src/test/resources/grammars/mini/parser.g4`

## Layout

- `valid/`: inputs expected to parse successfully using `script` as the entry rule
- `invalid/`: inputs expected to fail parsing

All keyword tokens are uppercase because `MiniLexer` defines explicit uppercase keyword rules.


lexer grammar MiniLexer;

@header {
package name.jurgenei.gradle.antlr.mini;
}

SELECT: 'SELECT';
FROM: 'FROM';
WHERE: 'WHERE';
ORDER: 'ORDER';
BY: 'BY';
AS: 'AS';
ASC: 'ASC';
DESC: 'DESC';
INSERT: 'INSERT';
INTO: 'INTO';
VALUES: 'VALUES';

SEMI: ';';
COMMA: ',';
LPAREN: '(';
RPAREN: ')';
STAR: '*';
PLUS: '+';
MINUS: '-';
DIV: '/';

EQ: '=';
NEQ: '!=' | '<>';
LT: '<';
LTE: '<=';
GT: '>';
GTE: '>=';

QUOTED_IDENTIFIER: '"' ('""' | ~["])* '"';
IDENTIFIER: [a-zA-Z_] [a-zA-Z_0-9]*;
DECIMAL: [0-9]+ ('.' [0-9]+)?;
STRING: '\'' ('\'\'' | ~['\r\n])* '\'';

LINE_COMMENT: '--' ~[\r\n]* -> skip;
BLOCK_COMMENT: '/*' .*? '*/' -> skip;
WS: [ \t\r\n]+ -> skip;

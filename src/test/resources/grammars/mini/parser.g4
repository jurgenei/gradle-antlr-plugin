parser grammar MiniParser;

options { tokenVocab=MiniLexer; }

// Entry point used by converter integration tests.
script
    : statement (SEMI statement)* SEMI? EOF
    ;

statement
    : selectStatement
    | insertStatement
    ;

selectStatement
    : SELECT selectList FROM tableRef (WHERE expression)? (ORDER BY orderItem (COMMA orderItem)*)?
    ;

insertStatement
    : INSERT INTO tableRef LPAREN identifierList RPAREN VALUES LPAREN expressionList RPAREN
    ;

selectList
    : STAR
    | selectItem (COMMA selectItem)*
    ;

selectItem
    : expression (AS? identifier)?
    ;

tableRef
    : identifier
    ;

orderItem
    : expression (ASC | DESC)?
    ;

identifierList
    : identifier (COMMA identifier)*
    ;

expressionList
    : expression (COMMA expression)*
    ;

expression
    : LPAREN expression RPAREN                                  #groupExpr
    | MINUS expression                                          #unaryMinusExpr
    | left=expression op=(STAR | DIV) right=expression          #mulDivExpr
    | left=expression op=(PLUS | MINUS) right=expression        #addSubExpr
    | left=expression op=(EQ | NEQ | LT | LTE | GT | GTE) right=expression #cmpExpr
    | identifier LPAREN (expressionList)? RPAREN                #functionExpr
    | literal                                                   #literalExpr
    | identifier                                                #identifierExpr
    ;

literal
    : STRING
    | DECIMAL
    ;

identifier
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    ;


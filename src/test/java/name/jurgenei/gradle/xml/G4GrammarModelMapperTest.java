package org.flowdigger.gradle.xml;

import org.flowdigger.ast.core.AstClassDeriver;
import org.flowdigger.ast.core.model.AstInheritance;
import org.flowdigger.ast.core.model.AstRelation;
import org.flowdigger.ast.core.model.Cardinality;
import org.flowdigger.ast.core.model.GrammarModel;
import org.flowdigger.ast.core.model.RelationKind;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class G4GrammarModelMapperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mapsRuleSemanticsFor1B2A3A() throws Exception {
        final File grammarFile = temporaryFolder.newFile("mini.g4");
        Files.writeString(grammarFile.toPath(), sampleGrammar(), StandardCharsets.UTF_8);

        final GrammarModel model = new G4GrammarModelMapper().map(
                grammarFile,
                "org.flowdigger.parsers.ANTLRv4Lexer",
                "org.flowdigger.parsers.ANTLRv4Parser",
                "grammarSpec",
                getClass().getClassLoader());

        final var ast = new AstClassDeriver().derive(model);
        Assert.assertTrue(ast.relations().contains(new AstRelation("Assignment", "target", "Identifier", Cardinality.ONE, RelationKind.REL)));
        Assert.assertTrue(ast.relations().contains(new AstRelation("Assignment", "value", "Expression", Cardinality.ONE, RelationKind.REL)));
        Assert.assertTrue(ast.inheritances().contains(new AstInheritance("FunctionCall", "Expression")));
        Assert.assertFalse(ast.classes().stream().anyMatch(c -> c.name().equals("Terminator")));

        final String xir = new G4GrammarModelXirWriter().write(model);
        Assert.assertTrue(xir.contains("(rule assignment"));
    }

    private static String sampleGrammar() {
        return """
                grammar Mini;

                assignment
                  : target=identifier '=' value=expression ';'
                  ;

                expression
                  : functionCall
                  | binaryExpression
                  | literalExpr
                  ;

                functionCall : identifier ;
                binaryExpression : identifier ;
                literalExpr : INT ;
                identifier : nameToken ;
                nameToken : ID ;
                terminator : ';' ;

                ID : [a-zA-Z_][a-zA-Z0-9_]* ;
                INT : [0-9]+ ;
                WS : [ \t\r
                ]+ -> skip ;
                """;
    }
}


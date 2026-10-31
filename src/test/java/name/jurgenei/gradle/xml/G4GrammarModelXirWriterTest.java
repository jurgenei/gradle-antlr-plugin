package org.flowdigger.gradle.xml;

import org.flowdigger.ast.core.model.ChoiceNode;
import org.flowdigger.ast.core.model.GrammarModel;
import org.flowdigger.ast.core.model.GrammarNode;
import org.flowdigger.ast.core.model.GrammarRule;
import org.flowdigger.ast.core.model.LabelNode;
import org.flowdigger.ast.core.model.LiteralNode;
import org.flowdigger.ast.core.model.OptionalNode;
import org.flowdigger.ast.core.model.Repeat1Node;
import org.flowdigger.ast.core.model.RepeatNode;
import org.flowdigger.ast.core.model.RuleRefNode;
import org.flowdigger.ast.core.model.SequenceNode;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class G4GrammarModelXirWriterTest {

    @Test
    public void writesAllSupportedNodeTypesWithEscapedLiterals() {
        final GrammarNode body = new ChoiceNode(List.of(
                new SequenceNode(List.of(
                        new LabelNode("target", new RuleRefNode("identifier")),
                        new OptionalNode(new LiteralNode("\"quoted\\\\path\"")),
                        new RepeatNode(new RuleRefNode("item")),
                        new Repeat1Node(new RuleRefNode("term")))),
                new LiteralNode("fallback")));

        final GrammarModel model = new GrammarModel(List.of(new GrammarRule("root", body)));

        final String xir = new G4GrammarModelXirWriter().write(model);

        Assert.assertTrue(xir.contains("(rule root "));
        Assert.assertTrue(xir.contains("(choice "));
        Assert.assertTrue(xir.contains("(sequence "));
        Assert.assertTrue(xir.contains("(label target (ruleRef identifier))"));
        Assert.assertTrue(xir.contains("(optional (literal "));
        Assert.assertTrue(xir.contains("(repeat (ruleRef item))"));
        Assert.assertTrue(xir.contains("(repeat1 (ruleRef term))"));
        Assert.assertTrue(xir.contains("(literal \"fallback\")"));
        Assert.assertTrue("Expected escaped quote in literal output", xir.contains("\\\"quoted"));
        Assert.assertTrue("Expected escaped backslash in literal output", xir.contains("\\\\path"));
    }

    @Test
    public void failsWhenRuleBodyIsNull() {
        final GrammarModel model = new GrammarModel(List.of(new GrammarRule("broken", null)));
        Assert.assertThrows(NullPointerException.class, () -> new G4GrammarModelXirWriter().write(model));
    }
}

package name.jurgenei.gradle.xml;

import name.jurgenei.ast.core.model.ChoiceNode;
import name.jurgenei.ast.core.model.GrammarModel;
import name.jurgenei.ast.core.model.GrammarNode;
import name.jurgenei.ast.core.model.GrammarRule;
import name.jurgenei.ast.core.model.LabelNode;
import name.jurgenei.ast.core.model.LiteralNode;
import name.jurgenei.ast.core.model.OptionalNode;
import name.jurgenei.ast.core.model.Repeat1Node;
import name.jurgenei.ast.core.model.RepeatNode;
import name.jurgenei.ast.core.model.RuleRefNode;
import name.jurgenei.ast.core.model.SequenceNode;

/**
 * Writes GrammarModel into compact S-expression format.
 */
public final class G4GrammarModelSexprWriter {

    public String write(final GrammarModel grammarModel) {
        final StringBuilder out = new StringBuilder();
        for (GrammarRule rule : grammarModel.rules()) {
            out.append("(rule ").append(rule.name()).append(" ");
            writeNode(rule.body(), out);
            out.append(")\n");
        }
        return out.toString();
    }

    private void writeNode(final GrammarNode node, final StringBuilder out) {
        if (node instanceof SequenceNode seq) {
            out.append("(sequence");
            for (GrammarNode child : seq.elements()) {
                out.append(' ');
                writeNode(child, out);
            }
            out.append(')');
            return;
        }
        if (node instanceof ChoiceNode choice) {
            out.append("(choice");
            for (GrammarNode child : choice.alternatives()) {
                out.append(' ');
                writeNode(child, out);
            }
            out.append(')');
            return;
        }
        if (node instanceof OptionalNode optional) {
            out.append("(optional ");
            writeNode(optional.node(), out);
            out.append(')');
            return;
        }
        if (node instanceof RepeatNode repeat) {
            out.append("(repeat ");
            writeNode(repeat.node(), out);
            out.append(')');
            return;
        }
        if (node instanceof Repeat1Node repeat1) {
            out.append("(repeat1 ");
            writeNode(repeat1.node(), out);
            out.append(')');
            return;
        }
        if (node instanceof LabelNode label) {
            out.append("(label ").append(label.label()).append(' ');
            writeNode(label.node(), out);
            out.append(')');
            return;
        }
        if (node instanceof RuleRefNode ref) {
            out.append("(ruleRef ").append(ref.ruleName()).append(')');
            return;
        }
        if (node instanceof LiteralNode lit) {
            out.append("(literal \"").append(escape(lit.text())).append("\")");
            return;
        }
        throw new IllegalArgumentException("Unsupported node type: " + node.getClass().getName());
    }

    private String escape(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}


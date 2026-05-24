package name.jurgenei.gradle.antlr.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads XML grammar catalogs with entries such as:
 * {@code <grammar name="plsql" runtimeGrammar="oracle" parser="..." lexer="..." start-rule="script"/>}.
 */
public final class GrammarCatalogLoader {

    /**
     * Loads and validates a grammar catalog file.
     *
     * @param file catalog XML file.
     * @return parsed catalog.
     * @throws IllegalArgumentException when XML is invalid or required attributes are missing.
     */
    public GrammarCatalog load(final File file) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Catalog file must exist: " + file);
        }

        final Document document = parseDocument(file);
        final Element root = document.getDocumentElement();
        if (root == null || !"catalog".equals(root.getTagName())) {
            throw new IllegalArgumentException("Catalog root element must be <catalog>");
        }

        final Path baseDirectory = file.toPath().toAbsolutePath().getParent();
        final Map<String, GrammarCatalogEntry> entries = new LinkedHashMap<>();
        final NodeList grammarNodes = root.getChildNodes();
        for (int i = 0; i < grammarNodes.getLength(); i++) {
            final Node node = grammarNodes.item(i);
            if (!(node instanceof Element grammarElement)) {
                continue;
            }
            if (!"grammar".equals(grammarElement.getTagName())) {
                continue;
            }

            final String name = requireAttribute(grammarElement, "name");
            final String parser = requireAttribute(grammarElement, "parser");
            final String lexer = requireAttribute(grammarElement, "lexer");
            final String startRule = requireAttribute(grammarElement, "start-rule");
            final String runtimeGrammar = optionalAttribute(grammarElement, "runtimeGrammar");

            final GrammarCatalogEntry entry = new GrammarCatalogEntry(name, runtimeGrammar, parser, lexer, startRule);
            // Resolve eagerly so malformed path/URL values fail at configuration time.
            entry.resolveParserUri(baseDirectory);
            entry.resolveLexerUri(baseDirectory);

            if (entries.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate grammar name in catalog: " + name);
            }
            entries.put(name, entry);
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Catalog must contain at least one <grammar> entry");
        }

        return new GrammarCatalog(entries);
    }

    private Document parseDocument(final File file) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory.newDocumentBuilder().parse(file);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to parse catalog XML: " + file, ex);
        }
    }

    private String requireAttribute(final Element element, final String name) {
        final String value = optionalAttribute(element, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing attribute '" + name + "' on <grammar>");
        }
        return value;
    }

    private String optionalAttribute(final Element element, final String name) {
        final String value = element.getAttribute(name);
        return value.isBlank() ? null : value.trim();
    }
}


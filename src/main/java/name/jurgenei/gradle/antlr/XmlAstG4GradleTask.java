package name.jurgenei.gradle.antlr;

import org.gradle.api.model.ObjectFactory;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.util.List;

/**
 * G4-flavored {@link XmlAstGradleTask} with ANTLRv4 parser defaults.
 */
@DisableCachingByDefault(because = "XmlAstGradleTask performs external parser loading and file-system driven conversion not yet declared for safe caching")
public abstract class XmlAstG4GradleTask extends XmlAstGradleTask {

    @Inject
    public XmlAstG4GradleTask(final ObjectFactory objects) {
        super(objects);
        LanguageTaskDefaults.of(
                "antlr4",
                "name.jurgenei.parsers.ANTLRv4Parser",
                "name.jurgenei.parsers.ANTLRv4Lexer",
                "grammarSpec",
                List.of("**/*.g4"))
            .applyTo(this);
    }
}


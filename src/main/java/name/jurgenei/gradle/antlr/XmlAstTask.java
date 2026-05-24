package name.jurgenei.gradle.antlr;

import name.jurgenei.gradle.antlr.catalog.GrammarCatalogEntry;
import name.jurgenei.gradle.antlr.catalog.GrammarCatalogLoader;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Legacy Gradle task model for converting source trees to XML AST output.
 *
 * <p>This type is kept for compatibility with existing builds. New build scripts should
 * prefer {@link XmlAstGradleTask}, which exposes a more idiomatic Gradle property model.</p>
 */
public abstract class XmlAstTask extends DefaultTask {

    private static final String CONVERTER_CLASS = "name.jurgenei.parsers.ToAstBatchConverter";

    private final ConfigurableFileCollection sourceTrees;
    private final DirectoryProperty destinationDirectory;
    private final Property<String> grammar;
    private final Property<String> targetExtension;
    private final Property<Integer> parallelism;
    private final Property<String> executionModel;
    private final ListProperty<String> includes;
    private final RegularFileProperty catalogFile;
    private final Property<String> catalogGrammar;
    private final ConfigurableFileCollection runtimeClasspath;

    @Inject
    public XmlAstTask(final ObjectFactory objects) {
        sourceTrees = objects.fileCollection();
        destinationDirectory = objects.directoryProperty();
        grammar = objects.property(String.class).convention("oracle");
        targetExtension = objects.property(String.class).convention(".xml");
        parallelism = objects.property(Integer.class).convention(1);
        executionModel = objects.property(String.class).convention("SEQUENTIAL");
        includes = objects.listProperty(String.class).convention(List.of("**/*.sql"));
        catalogFile = objects.fileProperty();
        catalogGrammar = objects.property(String.class);
        runtimeClasspath = objects.fileCollection();
        destinationDirectory.convention(getProject().getLayout().getProjectDirectory().dir("target/sqlxmlast"));
    }

    @InputFiles
    public ConfigurableFileCollection getSourceTrees() {
        return sourceTrees;
    }

    @OutputDirectory
    public DirectoryProperty getDestinationDirectory() {
        return destinationDirectory;
    }

    @Input
    public Property<String> getGrammar() {
        return grammar;
    }

    @Input
    public Property<String> getTargetExtension() {
        return targetExtension;
    }

    @Input
    public Property<Integer> getParallelism() {
        return parallelism;
    }

    @Input
    public Property<String> getExecutionModel() {
        return executionModel;
    }

    @Input
    public ListProperty<String> getIncludes() {
        return includes;
    }

    @Optional
    @InputFile
    public RegularFileProperty getCatalogFile() {
        return catalogFile;
    }

    @Optional
    @Input
    public Property<String> getCatalogGrammar() {
        return catalogGrammar;
    }

    @Classpath
    public ConfigurableFileCollection getRuntimeClasspath() {
        return runtimeClasspath;
    }

    /**
     * Executes conversion for all configured source trees.
     *
     * <p>Each source root is scanned using include patterns and converted through
     * {@code SqlXmlAstBatchConverter}, preserving relative output paths.</p>
     */
    @TaskAction
    public void convertSqlTrees() {
        if (sourceTrees.isEmpty()) {
            throw new GradleException("sqlxmlast requires at least one source tree");
        }

        final Set<File> roots = sourceTrees.getFiles();
        if (roots.isEmpty()) {
            throw new GradleException("sqlxmlast sourceTrees resolved to no directories");
        }

        final File destinationDir = destinationDirectory.get().getAsFile();
        final List<String> includePatterns = includes.get();
        final String selectedGrammar = resolveEffectiveGrammar();

        try (URLClassLoader classLoader = createRuntimeClassLoader()) {
            final Class<?> converterClass = classLoader.loadClass(CONVERTER_CLASS);
            final Object converter = converterClass.getDeclaredConstructor().newInstance();
            converterClass.getMethod("setGrammar", String.class).invoke(converter, selectedGrammar);
            converterClass.getMethod("setTargetExtension", String.class).invoke(converter, targetExtension.get());
            converterClass.getMethod("setParallelism", int.class).invoke(converter, parallelism.get());
            converterClass.getMethod("setExecutionModel", String.class)
                    .invoke(converter, executionModel.get().toUpperCase(Locale.ROOT));
            final Method convertMethod = converterClass
                    .getMethod("convertFileTree", File.class, List.class, File.class);

            for (File root : roots) {
                if (!root.isDirectory()) {
                    continue;
                }
                final List<File> files = collectMatchingFiles(root.toPath(), includePatterns);

                if (!files.isEmpty()) {
                    convertMethod.invoke(converter, root, files, destinationDir);
                }
            }
        } catch (Exception ex) {
            throw new GradleException("sqlxmlast conversion failed", ex);
        }
    }

    private String resolveEffectiveGrammar() {
        if (!catalogFile.isPresent()) {
            return grammar.get();
        }

        if (!catalogGrammar.isPresent() || catalogGrammar.get().isBlank()) {
            throw new GradleException("catalogGrammar is required when catalogFile is configured");
        }

        final GrammarCatalogLoader loader = new GrammarCatalogLoader();
        final GrammarCatalogEntry entry = loader.load(catalogFile.get().getAsFile()).require(catalogGrammar.get());
        getLogger().info(
                "Resolved catalog grammar '{}' -> runtimeGrammar='{}' (parser={}, lexer={})",
                entry.getName(),
                entry.resolveRuntimeGrammar(),
                entry.getParser(),
                entry.getLexer());
        return entry.resolveRuntimeGrammar();
    }

    private URLClassLoader createRuntimeClassLoader() throws Exception {
        final URL[] runtimeUrls = runtimeClasspath.getFiles().stream()
                .map(File::toPath)
                .map(Path::toUri)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .toArray(URL[]::new);

        return new URLClassLoader(runtimeUrls, getClass().getClassLoader());
    }

    private List<File> collectMatchingFiles(final Path root, final List<String> includePatterns) {
        final List<PathMatcher> matchers = includePatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .collect(Collectors.toCollection(ArrayList::new));

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesAny(root.relativize(path), matchers))
                    .map(Path::toFile)
                    .sorted(Comparator.comparing(File::getAbsolutePath))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception ex) {
            throw new GradleException("Failed scanning source tree " + root, ex);
        }
    }

    private boolean matchesAny(final Path relativePath, final List<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relativePath)) {
                return true;
            }
        }
        return false;
    }
}


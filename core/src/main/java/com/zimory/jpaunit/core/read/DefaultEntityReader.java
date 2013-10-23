package com.zimory.jpaunit.core.read;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import com.esotericsoftware.yamlbeans.YamlConfig;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import com.zimory.jpaunit.core.context.JpaUnitConfig;
import com.zimory.jpaunit.core.util.PathUtil;
import com.zimory.jpaunit.core.annotation.ShouldMatchJpaDataSet;
import com.zimory.jpaunit.core.annotation.UsingJpaDataSet;
import com.zimory.jpaunit.core.context.TestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DefaultEntityReader implements EntityReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultEntityReader.class);

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private static final String YAML_ENTRY_SEPARATOR = LINE_SEPARATOR;

    private final YamlConfig yamlConfig;
    private final JpaUnitConfig config;

    @Inject
    public DefaultEntityReader(final YamlConfig yamlConfig, final JpaUnitConfig config) {
        this.yamlConfig = yamlConfig;
        this.config = config;
    }

    @Override
    public Set<Object> readSetupEntities(final TestContext testContext) throws IOException {
        final List<Method> methods = testContext.getSetupMethods();
        final String raw = readRaw(methods, UsingJpaDataSet.class);

        return readEntities(raw);
    }

    @Override
    public Set<Object> readExpectEntities(final TestContext testContext) throws IOException {
        final ImmutableList<Method> methods = testContext.getExpectMethods();
        final String raw = readRaw(methods, ShouldMatchJpaDataSet.class);

        return readEntities(raw);
    }

    private String readRaw(final Collection<Method> methods, final Class<? extends Annotation> annotationCls) throws IOException {
        final StringBuilder buf = new StringBuilder(DEFAULT_BUFFER_SIZE);

        for (final Iterator<Method> methodIterator = methods.iterator(); methodIterator.hasNext();) {
            final Method m = methodIterator.next();

            final Annotation a = m.getAnnotation(annotationCls);
            if (a == null) {
                continue;
            }

            final String[] paths;

            if (a instanceof UsingJpaDataSet) {
                paths = PathUtil.getRelativeSetupPaths(m);
            } else if (a instanceof ShouldMatchJpaDataSet) {
                paths = PathUtil.getRelativeExpectPaths(m);
            } else {
                throw new IllegalStateException("Can't happen");
            }

            for (final Iterator<String> pathIterator = Iterators.forArray(paths); pathIterator.hasNext();) {
                final String yamlPath = PathUtil.formatYamlPath(config.getDatasetDir(), pathIterator.next());
                final InputStream resource = getClass().getResourceAsStream(yamlPath);
                Preconditions.checkNotNull(resource, "resource not found: %s", yamlPath);

                CharStreams.copy(new InputStreamReader(resource), buf);

                if (pathIterator.hasNext()) {
                    buf.append(YAML_ENTRY_SEPARATOR);
                }
            }

            if (methodIterator.hasNext()) {
                buf.append(YAML_ENTRY_SEPARATOR);
            }
        }

        return buf.toString();
    }

    private Set<Object> readEntities(final String raw) {
        final Set<Object> entities = Sets.newLinkedHashSet();
        final YamlReader reader = newYamlReader(new StringReader(raw));

        try {
            for (Object obj = reader.read(); obj != null; obj = reader.read()) {
                entities.add(obj);
            }
        } catch (final YamlException e) {
            LOGGER.debug("YAML raw source:\n{}", padSourceWithLineNumbers(raw));

            throw new RuntimeException(e);
        } finally {
            try {
                reader.close();
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }

        return entities;
    }

    private static String padSourceWithLineNumbers(final String raw) {
        final String[] lines = raw.split(LINE_SEPARATOR);
        final StringBuilder buf = new StringBuilder(raw.length() + lines.length);
        final int lineNumberPadding = (int) Math.log10(lines.length) + 1;
        final String lineFormat = "%" + lineNumberPadding + "d | %s%n";

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            buf.append(String.format(lineFormat, i, line));
        }

        return buf.toString();
    }

    private YamlReader newYamlReader(final Reader r) {
        return new YamlReader(r, yamlConfig);
    }

}

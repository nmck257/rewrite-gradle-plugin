/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle.isolated;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.protobuf.ProtoParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class ResourceParser {
    private static final Logger logger = Logging.getLogger(ResourceParser.class);
    private final List<String> exclusions;
    private final int sizeThresholdMb;

    public ResourceParser(Project project, RewriteExtension extension) {
        this.exclusions = mergeExclusions(project, extension);
        this.sizeThresholdMb = extension.getSizeThresholdMb();
    }

    private static List<String> mergeExclusions(Project project, RewriteExtension extension) {
        return Stream.concat(
                project.getSubprojects().stream()
                        .map(subproject -> project.getProjectDir().toPath().relativize(subproject.getProjectDir().toPath()) + "/**"),
                extension.getExclusions().stream()
        ).collect(toList());
    }

    public List<SourceFile> parse(Path baseDir, Path projectDir, Collection<Path> alreadyParsed, ExecutionContext ctx) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        sourceFiles.addAll(parseSourceFiles(new JsonParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new XmlParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new YamlParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new PropertiesParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(new ProtoParser(), baseDir, projectDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(HclParser.builder().build(), baseDir, projectDir, alreadyParsed, ctx));
        return sourceFiles;
    }

    // Used for calculating task inputs
    public List<Path> listSources(Path baseDir, Path searchDir) {
        List<Path> sources = new ArrayList<>();
        sources.addAll(listSources(new JsonParser(), baseDir, searchDir));
        sources.addAll(listSources(new XmlParser(), baseDir, searchDir));
        sources.addAll(listSources(new YamlParser(), baseDir, searchDir));
        sources.addAll(listSources(new PropertiesParser(), baseDir, searchDir));
        sources.addAll(listSources(new ProtoParser(), baseDir, searchDir));
        sources.addAll(listSources(HclParser.builder().build(), baseDir, searchDir));
        return sources;
    }

    public List<Path> listSources(Parser<?> parser, Path baseDir, Path searchDir) {
        try (Stream<Path> paths = Files.find(searchDir, 16, (path, attrs) -> {
            if (!parser.accept(path)) {
                return false;
            }

            for (Path pathSegment : searchDir.relativize(path)) {
                String pathStr = pathSegment.toString();
                if ("target".equals(pathStr) || "build".equals(pathStr) || "out".equals(pathStr) ||
                        ".gradle".equals(pathStr) || "node_modules".equals(pathStr) || ".metadata".equals(pathStr)) {
                    return false;
                }
            }

            long fileSize = attrs.size();
            if (attrs.isDirectory() || fileSize == 0) {
                return false;
            }

            for (String exclusion : exclusions) {
                PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                if (matcher.matches(baseDir.relativize(path))) {
                    return false;
                }
            }

            return sizeThresholdMb <= 0 || fileSize <= sizeThresholdMb * 1024L * 1024L;
        })) {
            return paths.collect(toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<? extends SourceFile> parseSourceFiles(
            Parser<?> parser,
            Path baseDir,
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) {

        try (Stream<Path> paths = Files.find(searchDir, 16, (path, attrs) -> {
            if (!parser.accept(path)) {
                return false;
            }

            for (Path pathSegment : searchDir.relativize(path)) {
                String pathStr = pathSegment.toString();
                if ("target".equals(pathStr) || "build".equals(pathStr) || "out".equals(pathStr) ||
                        ".gradle".equals(pathStr) || "node_modules".equals(pathStr) || ".metadata".equals(pathStr)) {
                    return false;
                }
            }

            long fileSize = attrs.size();
            if (attrs.isDirectory() || fileSize == 0) {
                return false;
            }

            if (alreadyParsed.contains(path)) {
                return false;
            }

            for (String exclusion : exclusions) {
                PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                if (matcher.matches(baseDir.relativize(path))) {
                    alreadyParsed.add(path);
                    return false;
                }
            }

            if ((sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L)) {
                alreadyParsed.add(path);
                logger.lifecycle("Skipping parsing " + path + " as its size + " + fileSize / (1024L * 1024L) +
                        "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                return false;
            }

            return true;
        })) {
            List<Path> resourceFiles = paths.collect(toList());
            alreadyParsed.addAll(resourceFiles);
            return parser.parse(resourceFiles, baseDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
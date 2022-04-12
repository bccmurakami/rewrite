/*
 * Copyright 2020 the original author or authors.
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
package org.openrewrite.maven.internal;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.internal.grammar.VersionRangeLexer;
import org.openrewrite.maven.internal.grammar.VersionRangeParser;
import org.openrewrite.maven.internal.grammar.VersionRangeParserBaseVisitor;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.MavenMetadata;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.maven.tree.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor
public class VersionRequirement {
    private static final Logger logger = LoggerFactory.getLogger(VersionRequirement.class);

    @Nullable
    private final VersionRequirement nearer;

    private final VersionSpec versionSpec;

    @Nullable
    private volatile transient String selected;

    public static VersionRequirement fromVersion(String requested, int depth) {
        return new VersionRequirement(null, VersionSpec.build(requested, depth == 0));
    }

    public VersionRequirement addRequirement(String requested) {
        if (versionSpec instanceof DirectRequirement) {
            return this;
        }

        VersionSpec newRequirement = VersionSpec.build(requested, false);

        VersionRequirement next = this;
        while (next != null) {
            if (next.versionSpec.equals(newRequirement)) {
                return this;
            }
            next = next.nearer;
        }

        return new VersionRequirement(this, newRequirement);
    }

    interface VersionSpec {
        static VersionSpec build(String requested, boolean direct) {
            if ("LATEST".equals(requested)) {
                return DynamicVersion.LATEST;
            } else if ("RELEASE".equals(requested)) {
                return DynamicVersion.RELEASE;
            } else if (requested.contains("[") || requested.contains("(")) {
                // for things like the profile activation block of where the range is unclosed but maven still handles it, e.g.
                // https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.12.0-rc2/jackson-databind-2.12.0-rc2.pom
                if (!(requested.contains("]") || requested.contains(")"))) {
                    requested = requested + "]";
                }

                VersionRangeParser parser = new VersionRangeParser(new CommonTokenStream(new VersionRangeLexer(
                        CharStreams.fromString(requested))));

                parser.removeErrorListeners();
                parser.addErrorListener(new LoggingErrorListener());

                return new VersionRangeParserBaseVisitor<VersionSpec>() {
                    @Override
                    public VersionSpec visitVersionRequirement(VersionRangeParser.VersionRequirementContext ctx) {
                        return new RangeSet(ctx.range().stream()
                                .map(range -> {
                                    Version lower, upper;
                                    if (range.bounds().boundedLower() != null) {
                                        Iterator<TerminalNode> versionIter = range.bounds().boundedLower().Version().iterator();
                                        lower = toVersion(versionIter.next());
                                        upper = versionIter.hasNext() ? toVersion(versionIter.next()) : null;
                                    } else if (range.bounds().unboundedLower() != null) {
                                        lower = null;
                                        upper = toVersion(range.bounds().unboundedLower().Version());
                                    } else {
                                        lower = toVersion(range.bounds().exactly().Version());
                                        upper = toVersion(range.bounds().exactly().Version());
                                    }
                                    return new Range(
                                            range.CLOSED_RANGE_OPEN() != null, lower,
                                            range.CLOSED_RANGE_CLOSE() != null, upper
                                    );
                                })
                                .collect(toList())
                        );
                    }

                    private Version toVersion(TerminalNode version) {
                        return new Version(version.getText());
                    }
                }.visit(parser.versionRequirement());
            }
            return direct ?
                    new DirectRequirement(requested) :
                    new SoftRequirement(requested);
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    private static class DirectRequirement extends SoftRequirement {
        private DirectRequirement(String version) {
            super(version);
        }
    }

    @Data
    private static class SoftRequirement implements VersionSpec {
        final String version;
    }

    private enum DynamicVersion implements VersionSpec {
        LATEST,
        RELEASE;

        public boolean matches(Version version) {
            return this == LATEST || !version.toString().endsWith("-SNAPSHOT");
        }
    }

    @Value
    private static class RangeSet implements VersionSpec {
        List<Range> ranges;

        public boolean matches(Version version) {
            for (Range range : ranges) {
                boolean lowerMatches = true;
                if (range.lower != null) {
                    int lowComp = range.lower.compareTo(version);
                    lowerMatches = lowComp == 0 ? range.lowerClosed : lowComp < 0;
                }
                if (!lowerMatches) {
                    return false;
                }

                boolean upperMatches = true;
                if (range.upper != null) {
                    int upperComp = range.upper.compareTo(version);
                    upperMatches = upperComp == 0 ? range.upperClosed : upperComp > 0;
                }
                if (upperMatches) {
                    return true;
                }
            }
            return false;
        }
    }

    @Value
    private static class Range {
        boolean lowerClosed;
        @Nullable
        Version lower;

        boolean upperClosed;

        @Nullable
        Version upper;

        @Override
        public String toString() {
            return (lowerClosed ? "[" : "(") + lower + "," + upper +
                    (upperClosed ? ']' : ')');
        }
    }

    @Nullable
    public String resolve(Supplier<Iterable<String>> availableVersions) {
        if (selected == null) {
            selected = cacheResolved(availableVersions);
        }
        return selected;
    }

    @Nullable
    private String cacheResolved(Supplier<Iterable<String>> availableVersions) {
        boolean hasHardRequirements = false;

        String nearestSoftRequirement = null;
        VersionRequirement next = this;
        while (next != null) {
            VersionSpec spec = next.versionSpec;
            if (spec instanceof DirectRequirement) {
                // dependencies defined in the project POM always win
                return ((DirectRequirement) spec).getVersion();
            } else if (spec instanceof SoftRequirement) {
                nearestSoftRequirement = ((SoftRequirement) spec).version;
            } else {
                hasHardRequirements = true;
            }
            next = next.nearer;
        }

        if (!hasHardRequirements) {
            return nearestSoftRequirement;
        }

        Version latest = null;
        for (String availableVersion : availableVersions.get()) {
            Version version = new Version(availableVersion);

            boolean matchesAllHardRequirements = true;
            next = this;
            while (next != null) {
                VersionSpec spec = next.versionSpec;
                if (spec instanceof DynamicVersion) {
                    matchesAllHardRequirements &= ((DynamicVersion) spec).matches(version);
                } else if (spec instanceof RangeSet) {
                    matchesAllHardRequirements &= ((RangeSet) spec).matches(version);
                }
                next = next.nearer;
            }

            if (matchesAllHardRequirements && (latest == null || version.compareTo(latest) > 0)) {
                latest = version;
            }
        }

        if (latest == null) {
            // hard requirements are non-overlapping
            return null;
        }

        return latest.toString();
    }

    @Nullable
    public String resolve(GroupArtifact groupArtifact, MavenPomDownloader downloader, List<MavenRepository> repositories) {
        return resolve(() -> {
            MavenMetadata metadata = downloader.downloadMetadata(groupArtifact, null, repositories);
            return metadata.getVersioning().getVersions();
        });
    }

    private static class LoggingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            logger.warn("Syntax error at line {}:{} {}", line, charPositionInLine, msg);
        }
    }
}

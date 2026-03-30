package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class PythonDistribution {
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EDITABLE_PATTERN = Pattern.compile("\"editable\"\\s*:\\s*true");

    private PythonDistribution() {
    }

    static boolean isMetadataPath(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isDirectory(path) && (name.endsWith(".dist-info") || name.endsWith(".egg-info"));
    }

    static DistributionMetadata readDistributionMetadata(Path metadataPath) {
        var metadataFile = Files.exists(metadataPath.resolve("METADATA"))
                ? metadataPath.resolve("METADATA")
                : metadataPath.resolve("PKG-INFO");
        if (!Files.exists(metadataFile)) {
            throw new PluginException("Python package metadata is missing from " + metadataPath + ".");
        }

        Map<String, List<String>> fields;
        try {
            fields = parseMetadataFields(Files.readString(metadataFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PluginException("Unable to read Python package metadata from " + metadataPath + ".", e);
        }

        var distributionName = firstField(fields, "Name");
        var version = firstField(fields, "Version");
        if (distributionName == null || distributionName.isBlank()) {
            throw new PluginException("Python package " + metadataPath + " does not declare a distribution name.");
        }
        if (version == null || version.isBlank()) {
            throw new PluginException("Python package " + metadataPath + " does not declare a version.");
        }

        var authors = new ArrayList<String>();
        var author = firstField(fields, "Author");
        if (author != null && !author.isBlank()) {
            authors.add(author);
        }
        if (authors.isEmpty()) {
            var authorEmail = firstField(fields, "Author-email");
            if (authorEmail != null && !authorEmail.isBlank()) {
                authors.add(authorEmail);
            }
        }

        return new DistributionMetadata(
                distributionName,
                version,
                defaultString(firstField(fields, "Summary")),
                authors,
                resolveWebsite(fields),
                resolveDependencyNames(metadataPath, fields)
        );
    }

    static EntryPointSpec readEntryPoint(Path metadataPath, String distributionName) {
        var entryPointsFile = metadataPath.resolve("entry_points.txt");
        if (!Files.exists(entryPointsFile)) {
            return null;
        }

        Map<String, Map<String, String>> sections;
        try {
            sections = parseIniSections(Files.readString(entryPointsFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PluginException("Unable to read Python entry points from " + metadataPath + ".", e);
        }

        var groupEntries = sections.get("allaystone");
        if (groupEntries == null || groupEntries.isEmpty()) {
            return null;
        }
        if (groupEntries.size() != 1) {
            throw new PluginException("Python package " + distributionName + " must define exactly one [allaystone] entry point.");
        }

        var entry = groupEntries.entrySet().iterator().next();
        var entryName = normalizeDistributionName(entry.getKey());
        var expectedDistributionName = "allaystone-" + entryName;
        if (!normalizeDistributionName(distributionName).equals(expectedDistributionName)) {
            throw new PluginException(
                    "Installed distribution " + distributionName + " must match the allaystone entry point name " + entry.getKey() + "."
            );
        }

        var value = entry.getValue().trim();
        var separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new PluginException("Invalid allaystone entry point value: " + value + ".");
        }

        return new EntryPointSpec(entryName, value, value.substring(0, separator), value.substring(separator + 1));
    }

    static InstallationSource readInstallationSource(Path metadataPath) {
        var directUrlFile = metadataPath.resolve("direct_url.json");
        if (!Files.exists(directUrlFile)) {
            return null;
        }

        final String content;
        try {
            content = Files.readString(directUrlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PluginException("Unable to read direct_url.json from " + metadataPath + ".", e);
        }

        var matcher = URL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }

        Path sourcePath = null;
        try {
            sourcePath = Path.of(URI.create(matcher.group(1)));
        } catch (RuntimeException ignored) {
        }
        return new InstallationSource(sourcePath, EDITABLE_PATTERN.matcher(content).find());
    }

    private static Map<String, List<String>> parseMetadataFields(String content) throws IOException {
        var values = new LinkedHashMap<String, List<String>>();
        try (var reader = new BufferedReader(new StringReader(content))) {
            String currentKey = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    currentKey = null;
                    continue;
                }
                if ((line.startsWith(" ") || line.startsWith("\t")) && currentKey != null) {
                    var currentValues = values.get(currentKey);
                    var lastIndex = currentValues.size() - 1;
                    currentValues.set(lastIndex, currentValues.get(lastIndex) + "\n" + line.strip());
                    continue;
                }

                var separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }

                currentKey = line.substring(0, separator);
                values.computeIfAbsent(currentKey, ignored -> new ArrayList<>())
                        .add(line.substring(separator + 1).trim());
            }
        }
        return values;
    }

    private static Map<String, Map<String, String>> parseIniSections(String content) throws IOException {
        var sections = new LinkedHashMap<String, Map<String, String>>();
        try (var reader = new BufferedReader(new StringReader(content))) {
            String currentSection = null;
            String line;
            while ((line = reader.readLine()) != null) {
                var stripped = line.trim();
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith(";")) {
                    continue;
                }
                if (stripped.startsWith("[") && stripped.endsWith("]")) {
                    currentSection = stripped.substring(1, stripped.length() - 1).trim();
                    sections.computeIfAbsent(currentSection, ignored -> new LinkedHashMap<>());
                    continue;
                }
                if (currentSection == null) {
                    continue;
                }

                var separator = stripped.indexOf('=');
                if (separator <= 0) {
                    continue;
                }

                sections.get(currentSection).put(
                        stripped.substring(0, separator).trim(),
                        stripped.substring(separator + 1).trim()
                );
            }
        }
        return sections;
    }

    private static String firstField(Map<String, List<String>> fields, String name) {
        var values = fields.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private static String resolveWebsite(Map<String, List<String>> fields) {
        var homePage = firstField(fields, "Home-page");
        if (homePage != null && !homePage.isBlank()) {
            return homePage;
        }

        var projectUrls = fields.get("Project-URL");
        if (projectUrls == null) {
            return "";
        }
        for (var projectUrl : projectUrls) {
            var separator = projectUrl.indexOf(',');
            if (separator >= 0 && separator < projectUrl.length() - 1) {
                return projectUrl.substring(separator + 1).trim();
            }
            if (!projectUrl.isBlank()) {
                return projectUrl;
            }
        }
        return "";
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static List<String> resolveDependencyNames(Path metadataPath, Map<String, List<String>> fields) {
        var dependencyNames = new LinkedHashSet<String>();
        var requirements = fields.get("Requires-Dist");
        if (requirements != null) {
            requirements.stream()
                    .map(PythonDistribution::parseRequirementName)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(dependencyNames::add);
        }

        if (!dependencyNames.isEmpty()) {
            return List.copyOf(dependencyNames);
        }

        var legacyEggInfoRequirements = metadataPath.resolve("requires.txt");
        if (!Files.exists(legacyEggInfoRequirements)) {
            return List.of();
        }

        try {
            Files.readAllLines(legacyEggInfoRequirements, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .filter(line -> !(line.startsWith("[") && line.endsWith("]")))
                    .map(PythonDistribution::parseRequirementName)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(dependencyNames::add);
        } catch (IOException e) {
            throw new PluginException("Unable to read Python dependency metadata from " + legacyEggInfoRequirements + ".", e);
        }

        return List.copyOf(dependencyNames);
    }

    private static String parseRequirementName(String requirement) {
        var stripped = requirement.strip();
        if (stripped.isEmpty()) {
            return null;
        }

        var end = stripped.length();
        for (var i = 0; i < stripped.length(); i++) {
            var ch = stripped.charAt(i);
            if (Character.isWhitespace(ch) || ch == '[' || ch == '(' || ch == ';') {
                end = i;
                break;
            }
        }

        var name = stripped.substring(0, end).strip();
        return name.isEmpty() ? null : normalizeDistributionName(name);
    }

    static String normalizeDistributionName(String value) {
        var normalized = value.strip().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        if (normalized.isBlank()) {
            throw new PluginException("Plugin names must not be blank.");
        }
        return normalized;
    }

    record DistributionMetadata(
            String distributionName,
            String version,
            String summary,
            List<String> authors,
            String website,
            List<String> dependencyNames
    ) {
    }

    record EntryPointSpec(
            String name,
            String rawValue,
            String moduleName,
            String attributePath
    ) {
    }

    record InstallationSource(Path path, boolean editable) {
    }
}

package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.metadatacenter.model.ModelNodeNames;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts artifact bodies between the caller's format and the wire format. The CEDAR REST API
 * accepts only JSON today, but the caller may speak YAML (the ecosystem's exchange currency), so:
 *
 * <ul>
 *   <li><strong>inbound</strong> ({@link #toJson}): YAML or JSON (auto-detected) → canonical CEDAR
 *       JSON for the request body, via {@code cedar-artifact-library};</li>
 *   <li><strong>outbound</strong> ({@link #toYaml}): the server's JSON response → compact (or
 *       expanded) YAML for the caller.</li>
 * </ul>
 *
 * <p>Conversion is keyed on the {@link ArtifactType} of the tool, so the right reader/renderer is
 * used. When the REST API gains YAML support, the inbound conversion becomes a passthrough.
 */
final class ArtifactCodec
{
  static final String JSON_LD_ID = "@id";

  private static final ObjectMapper JACKSON = new ObjectMapper();
  private static final JsonArtifactReader JSON_READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();
  private static final YamlArtifactReader YAML_READER = new YamlArtifactReader(true);

  private ArtifactCodec() {}

  /** Incoming artifact (YAML or JSON, auto-detected) → canonical CEDAR JSON object. */
  static ObjectNode toJson(String text, ArtifactType type)
  {
    if (looksLikeJson(text))
      return asObjectNode(text);

    LinkedHashMap<String, Object> map = parseYamlMap(text);
    return switch (type) {
      case TEMPLATE -> JSON_RENDERER.renderTemplateSchemaArtifact(YAML_READER.readTemplateSchemaArtifact(map));
      case ELEMENT -> JSON_RENDERER.renderElementSchemaArtifact(YAML_READER.readElementSchemaArtifact(map));
      case FIELD -> JSON_RENDERER.renderFieldSchemaArtifact(YAML_READER.readFieldSchemaArtifact(map));
      case INSTANCE -> JSON_RENDERER.renderTemplateInstanceArtifact(YAML_READER.readTemplateInstanceArtifact(map));
    };
  }

  /** Server JSON response → compact (or expanded) YAML for the caller. */
  static String toYaml(String json, ArtifactType type, boolean compact)
  {
    ObjectNode node = asObjectNode(json);
    Artifact artifact = switch (type) {
      case TEMPLATE -> JSON_READER.readTemplateSchemaArtifact(node);
      case ELEMENT -> JSON_READER.readElementSchemaArtifact(node);
      case FIELD -> JSON_READER.readFieldSchemaArtifact(node);
      case INSTANCE -> JSON_READER.readTemplateInstanceArtifact(node);
    };
    return YamlSerializer.getYAML(artifact, compact, false);
  }

  /**
   * Force the top-level {@code @id} to JSON {@code null} for a create: the server assigns the real
   * identity and returns it. Overwrites whatever the caller's artifact carried (e.g. an @id minted
   * by cedar-artifact-mcp).
   */
  static void nullifyTopLevelId(ObjectNode node)
  {
    node.putNull(JSON_LD_ID);
  }

  /** An artifact detected for validation: its kind and the JSON body to send to the server. */
  record Detected(ArtifactType type, String json) {}

  /**
   * Detect the artifact kind and produce the JSON body for {@code /command/validate}. JSON input is
   * sent <em>as-is</em> (validate the artifact exactly as received — the "from the wild" case);
   * YAML is converted to JSON via the matching reader/renderer.
   */
  static Detected forValidation(String text)
  {
    if (looksLikeJson(text)) {
      ArtifactType type = detectFromJson(asObjectNode(text));
      return new Detected(type, text);
    }
    LinkedHashMap<String, Object> map = parseYamlMap(text);
    ArtifactType type = detectFromYaml(map);
    return new Detected(type, compactJson(toJson(text, type)));
  }

  private static ArtifactType detectFromJson(ObjectNode node)
  {
    String typeIri = firstType(node);
    if (typeIri != null) {
      if (ModelNodeNames.TEMPLATE_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri)) return ArtifactType.TEMPLATE;
      if (ModelNodeNames.ELEMENT_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri)) return ArtifactType.ELEMENT;
      if (ModelNodeNames.FIELD_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri)
          || ModelNodeNames.STATIC_FIELD_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri)) return ArtifactType.FIELD;
    }
    if (node.hasNonNull(ModelNodeNames.SCHEMA_IS_BASED_ON)) return ArtifactType.INSTANCE;
    throw new IllegalArgumentException(
        "could not determine artifact kind from @type — pass a recognizable CEDAR artifact");
  }

  private static ArtifactType detectFromYaml(LinkedHashMap<String, Object> map)
  {
    Object type = map.get("type");
    if (type != null) {
      switch (type.toString().trim().toLowerCase()) {
        case "template": return ArtifactType.TEMPLATE;
        case "element": return ArtifactType.ELEMENT;
        case "field": return ArtifactType.FIELD;
        case "instance": return ArtifactType.INSTANCE;
        default: /* fall through */
      }
    }
    if (map.containsKey("isBasedOn")) return ArtifactType.INSTANCE;
    throw new IllegalArgumentException(
        "could not determine artifact kind from the YAML 'type:' field");
  }

  private static String firstType(ObjectNode node)
  {
    JsonNode typeNode = node.path("@type");
    if (typeNode.isTextual())
      return typeNode.asText();
    if (typeNode.isArray() && typeNode.size() >= 1 && typeNode.get(0).isTextual())
      return typeNode.get(0).asText();
    return null;
  }

  static ObjectNode asObjectNode(String text)
  {
    try {
      JsonNode node = JACKSON.readTree(text);
      if (!(node instanceof ObjectNode objectNode))
        throw new IllegalArgumentException("expected a JSON object, got " + node.getNodeType());
      return objectNode;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON parse failed: " + e.getOriginalMessage(), e);
    }
  }

  static String prettyJson(JsonNode node)
  {
    try {
      return JACKSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  static String compactJson(JsonNode node)
  {
    try {
      return JACKSON.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  private static boolean looksLikeJson(String text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) continue;
      return c == '{';
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static LinkedHashMap<String, Object> parseYamlMap(String text)
  {
    Object parsed = newYaml().load(text);
    if (!(parsed instanceof Map))
      throw new IllegalArgumentException("artifact YAML must be a mapping at the top level");
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    ((Map<Object, Object>) parsed).forEach((k, v) -> map.put(String.valueOf(k), v));
    return map;
  }

  /**
   * A SnakeYAML instance whose resolver does not implicitly type date-like scalars as timestamps
   * (CEDAR keeps them as strings) — same configuration the artifact library's own tooling uses.
   */
  private static Yaml newYaml()
  {
    LoaderOptions loaderOptions = new LoaderOptions();
    DumperOptions dumperOptions = new DumperOptions();
    return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions),
        dumperOptions, loaderOptions, new NoTimestampResolver());
  }

  private static final class NoTimestampResolver extends Resolver
  {
    @Override protected void addImplicitResolvers()
    {
      addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
      addImplicitResolver(Tag.INT, INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, MERGE, "<");
      addImplicitResolver(Tag.NULL, NULL, "~nN\0");
      addImplicitResolver(Tag.NULL, EMPTY, null);
      // Tag.TIMESTAMP intentionally omitted: date-like scalars stay strings.
    }
  }
}

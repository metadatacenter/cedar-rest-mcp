package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON helpers for the REST tools. This MCP speaks the CEDAR server's own wire format — JSON —
 * end to end: artifacts go in as JSON and come back as JSON. It does no YAML conversion and
 * carries no dependency on {@code cedar-artifact-library}; converting between YAML (the ecosystem's
 * exchange currency) and JSON is {@code cedar-artifact-mcp}'s job — its {@code *_to_json} and
 * {@code *_to_yaml} tools exist for exactly this boundary. The orchestrating LLM converts a YAML
 * artifact to JSON with {@code *_to_json} before persisting it here, and renders a fetched JSON
 * artifact back to YAML with {@code *_to_yaml} for display.
 *
 * <p>So this class is just Jackson plumbing: parse, pretty/compact serialize, null the create-time
 * {@code @id}, and detect an artifact's kind from its {@code @type} (for {@code validate_artifact},
 * the one tool that isn't already per-kind).
 */
final class ArtifactCodec
{
  static final String JSON_LD_ID = "@id";

  // CEDAR JSON-LD type IRIs and keys used to identify an artifact's kind. Inlined rather than
  // pulled from cedar-model-library so this MCP needs no CEDAR dependency; these are stable
  // parts of the CEDAR model vocabulary.
  private static final String TEMPLATE_TYPE_IRI = "https://schema.metadatacenter.org/core/Template";
  private static final String ELEMENT_TYPE_IRI = "https://schema.metadatacenter.org/core/TemplateElement";
  private static final String FIELD_TYPE_IRI = "https://schema.metadatacenter.org/core/TemplateField";
  private static final String STATIC_FIELD_TYPE_IRI = "https://schema.metadatacenter.org/core/StaticTemplateField";
  private static final String SCHEMA_IS_BASED_ON = "schema:isBasedOn";

  private static final ObjectMapper JACKSON = new ObjectMapper();

  private ArtifactCodec() {}

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
   * Detect the artifact kind of a JSON body for {@code /command/validate}, sending it on as-is
   * (validate the artifact exactly as received).
   */
  static Detected forValidation(String json)
  {
    ObjectNode node = asObjectNode(json);
    return new Detected(detectFromJson(node), json);
  }

  private static ArtifactType detectFromJson(ObjectNode node)
  {
    String typeIri = firstType(node);
    if (typeIri != null) {
      if (TEMPLATE_TYPE_IRI.equals(typeIri)) return ArtifactType.TEMPLATE;
      if (ELEMENT_TYPE_IRI.equals(typeIri)) return ArtifactType.ELEMENT;
      if (FIELD_TYPE_IRI.equals(typeIri) || STATIC_FIELD_TYPE_IRI.equals(typeIri)) return ArtifactType.FIELD;
    }
    if (node.hasNonNull(SCHEMA_IS_BASED_ON)) return ArtifactType.INSTANCE;
    throw new IllegalArgumentException(
        "could not determine artifact kind from @type — pass a recognizable CEDAR artifact");
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

  /**
   * Whether {@code text} is JSON (the only format this MCP accepts). Used to give a caller who
   * passed YAML a pointed redirect to cedar-artifact-mcp rather than a cryptic parse failure.
   */
  static boolean looksLikeJson(String text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) continue;
      return c == '{';
    }
    return false;
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
}

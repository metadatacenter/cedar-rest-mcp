package org.metadatacenter.cedar.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Builds the CRUD tools — {@code get / create / update / delete} for each {@link ArtifactType} —
 * against a {@link CedarHttp}. The four artifact kinds differ only by path segment and tool noun,
 * so the tools are generated rather than written out as 16 near-identical classes; the server
 * registers them in a loop.
 *
 * <p>Conventions: artifact IDs are IRIs, URL-encoded into the path. Bodies are accepted as YAML or
 * JSON and sent as canonical JSON; responses come back as compact YAML by default. {@code create}
 * nulls the top-level {@code @id} so the server assigns one. Non-2xx responses surface the server's
 * status and body as an error result (errors are content).
 */
final class ArtifactCrudTools
{
  /** A built tool paired with its handler, ready to hand to {@code McpServer...toolCall}. */
  record RegisteredTool(
      McpSchema.Tool tool,
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {}

  private ArtifactCrudTools() {}

  static List<RegisteredTool> all(CedarHttp http)
  {
    List<RegisteredTool> tools = new ArrayList<>();
    for (ArtifactType type : ArtifactType.values()) {
      tools.add(getTool(type, http));
      tools.add(createTool(type, http));
      tools.add(updateTool(type, http));
      tools.add(deleteTool(type, http));
    }
    return tools;
  }

  // ---------------------------------------------------------------- get

  private static RegisteredTool getTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));
    properties.put("isCompact", isCompactProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("get_" + type.noun)
        .title("Fetch a CEDAR " + type.noun + " from the server")
        .description(
            "Fetches a CEDAR " + type.noun + " from the CEDAR server by its @id (IRI). Returns the "
                + "artifact as compact YAML (pass isCompact:false for the expanded, fully-provenanced "
                + "form). Reproduce the returned YAML verbatim — do not drop @id lines or summarize.")
        .inputSchema(schema(properties, List.of("id")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String id = str(args, "id");
          if (id == null || id.isBlank())
            return error("id is required (the artifact's @id IRI)");
          CedarHttp.CedarResponse response;
          try {
            response = http.request("GET", idPath(type, id), null);
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(response, type, readCompact(args));
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- create

  private static RegisteredTool createTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", artifactProperty(type));
    properties.put("isCompact", isCompactProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("create_" + type.noun)
        .title("Create a CEDAR " + type.noun + " on the server")
        .description(
            "Creates a new CEDAR " + type.noun + " on the CEDAR server (it is placed in your home "
                + "folder). The artifact's top-level @id is set to null on submission; the server "
                + "assigns the real @id and returns the created artifact (as compact YAML). WRITES to "
                + "the server. Supply the artifact inline (YAML or JSON) exactly as you have it — a "
                + "large artifact inline is fine; do not reformat, re-serialize, or otherwise massage "
                + "the content.")
        .inputSchema(schema(properties, List.of("artifact")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String text = str(args, "artifact");
          if (text == null || text.isBlank())
            return error("artifact is required and must not be blank");
          ObjectNode body;
          try {
            body = ArtifactCodec.toJson(text, type);
            ArtifactCodec.nullifyTopLevelId(body);
          } catch (RuntimeException e) {
            return error("artifact could not be parsed/converted: " + e.getMessage());
          }
          CedarHttp.CedarResponse response;
          try {
            response = http.request("POST", "/" + type.pathSegment, ArtifactCodec.compactJson(body));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(response, type, readCompact(args));
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- update

  private static RegisteredTool updateTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));
    properties.put("artifact", artifactProperty(type));
    properties.put("isCompact", isCompactProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("update_" + type.noun)
        .title("Update a CEDAR " + type.noun + " on the server")
        .description(
            "Updates an existing CEDAR " + type.noun + " on the server (PUT) by its @id (IRI). The "
                + "@id in the artifact body must match the id argument. Returns the updated artifact "
                + "as compact YAML. WRITES to the server. Supply the artifact inline (YAML or JSON) "
                + "exactly as you have it — do not reformat or massage the content.")
        .inputSchema(schema(properties, List.of("id", "artifact")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String id = str(args, "id");
          if (id == null || id.isBlank())
            return error("id is required (the artifact's @id IRI)");
          String text = str(args, "artifact");
          if (text == null || text.isBlank())
            return error("artifact is required and must not be blank");
          ObjectNode body;
          try {
            body = ArtifactCodec.toJson(text, type);
          } catch (RuntimeException e) {
            return error("artifact could not be parsed/converted: " + e.getMessage());
          }
          CedarHttp.CedarResponse response;
          try {
            response = http.request("PUT", idPath(type, id), ArtifactCodec.compactJson(body));
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          return artifactResult(response, type, readCompact(args));
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- delete

  private static RegisteredTool deleteTool(ArtifactType type, CedarHttp http)
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("id", idProperty(type));

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("delete_" + type.noun)
        .title("Delete a CEDAR " + type.noun + " on the server")
        .description(
            "Permanently deletes a CEDAR " + type.noun + " from the server by its @id (IRI). "
                + "DESTRUCTIVE and irreversible — confirm with the user before calling. WRITES to the "
                + "server.")
        .inputSchema(schema(properties, List.of("id")))
        .build();

    BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
        (exchange, request) -> {
          Map<String, Object> args = args(request);
          String id = str(args, "id");
          if (id == null || id.isBlank())
            return error("id is required (the artifact's @id IRI)");
          CedarHttp.CedarResponse response;
          try {
            response = http.request("DELETE", idPath(type, id), null);
          } catch (RuntimeException e) {
            return error(e.getMessage());
          }
          if (response.isSuccess())
            return success("Deleted " + type.noun + ": " + id);
          return error("CEDAR returned HTTP " + response.status() + ": " + response.body());
        };

    return new RegisteredTool(tool, handler);
  }

  // ---------------------------------------------------------------- helpers

  /** Turn an artifact response into compact YAML, or surface a non-2xx as an error result. */
  private static McpSchema.CallToolResult artifactResult(
      CedarHttp.CedarResponse response, ArtifactType type, boolean compact)
  {
    if (!response.isSuccess())
      return error("CEDAR returned HTTP " + response.status() + ": " + response.body());
    try {
      return success(ArtifactCodec.toYaml(response.body(), type, compact));
    } catch (RuntimeException e) {
      // The server returned 2xx but a body we can't model (unexpected shape) — hand back the raw
      // JSON rather than failing, so the caller still sees what came over the wire.
      return success(response.body());
    }
  }

  private static Map<String, Object> idProperty(ArtifactType type)
  {
    return Map.of("type", "string", "description",
        "The " + type.noun + "'s @id — the full CEDAR IRI (e.g. "
            + "https://repo.metadatacenter.org/" + type.pathSegment + "/<uuid>). URL-encoding is "
            + "handled for you; pass the plain IRI.");
  }

  private static Map<String, Object> artifactProperty(ArtifactType type)
  {
    return Map.of("type", "string", "description",
        "The CEDAR " + type.noun + " as YAML or JSON (auto-detected). Sent to the server as JSON. "
            + "Pass it inline, verbatim.");
  }

  private static Map<String, Object> isCompactProperty()
  {
    return Map.of("type", "boolean", "default", Boolean.TRUE, "description",
        "Whether to return the artifact as lean compact YAML (default true) or the expanded, "
            + "fully-provenanced form (false).");
  }

  private static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required)
  {
    return new McpSchema.JsonSchema("object", properties, required, Boolean.FALSE, null, null);
  }

  private static String idPath(ArtifactType type, String id)
  {
    return "/" + type.pathSegment + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  private static Map<String, Object> args(McpSchema.CallToolRequest request)
  {
    return request.arguments() == null ? Map.of() : request.arguments();
  }

  private static String str(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static boolean readCompact(Map<String, Object> args)
  {
    Object raw = args.get("isCompact");
    return raw instanceof Boolean b ? b : true;
  }

  private static McpSchema.CallToolResult success(String text)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, text)))
        .isError(false)
        .build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

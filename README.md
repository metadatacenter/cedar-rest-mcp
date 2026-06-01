# cedar-rest-mcp

An MCP server that manages CEDAR **artifacts** — templates, elements, fields, and instances —
through the CEDAR **resource-server REST API**. It is the I/O counterpart to
[`cedar-artifact-mcp`](../cedar-artifact-mcp): that one builds, converts, and validates artifacts
in memory; this one persists them to, and fetches them from, a live CEDAR server.

See [DESIGN.md](./DESIGN.md) for the principles and [ROADMAP.md](./ROADMAP.md) for scope and
deferred work.

## Tools

`get / create / update / delete` for each artifact kind, plus server-side validation:

| Tool | Method | Endpoint |
|---|---|---|
| `get_{template,element,field,instance}` | GET | `/{type}/{id}` |
| `create_{…}` | POST | `/{type}` |
| `update_{…}` | PUT | `/{type}/{id}` |
| `delete_{…}` | DELETE | `/{type}/{id}` |
| `validate_artifact` | POST | `/command/validate?resource_type=…` |

- **`id`** is the artifact's `@id` (full IRI); URL-encoding into the path is handled for you.
- **`artifact`** is accepted as **YAML or JSON** (auto-detected) and sent as JSON; responses come
  back as **compact YAML** (`isCompact:false` for the expanded form).
- **`create`** sets the top-level `@id` to `null`; the server assigns the real `@id` and returns
  the created artifact. **`update`** keeps the `@id` (must match the `id` argument).
- **`delete` is destructive and irreversible** — confirm before calling.
- **`validate_artifact`** auto-detects the kind and calls the server's authoritative validator;
  returns `{validates, warnings, errors}`. (No artifact is created.)

Artifact discovery (search, folder listing) is **out of scope** — you operate by IRI: fetch what
you can name, or what a `create` just returned.

## Configuration

Set in the MCP client's config (e.g. `~/.claude.json`), never in source or chat:

```json
"cedar-rest": {
  "command": "/usr/bin/java",
  "args": ["-jar", "/path/to/cedar-rest-mcp/target/cedar-rest-mcp-0.1.0-SNAPSHOT-all.jar"],
  "env": {
    "CEDAR_API_KEY": "apiKey <your-key>",
    "CEDAR_BASE_URL": "https://resource.metadatacenter.org"
  }
}
```

- `CEDAR_API_KEY` — required for any live call (bare or `apiKey `-prefixed).
- `CEDAR_BASE_URL` — defaults to production (`https://resource.metadatacenter.org`); point it at a
  local CEDAR stack for development.

## Build

```bash
mvn package          # builds target/cedar-rest-mcp-0.1.0-SNAPSHOT-all.jar (shaded, executable)
mvn test             # unit tests (run against a fake HTTP transport; no live server needed)
```

## Instances: complete them first

The CEDAR server requires an instance to carry every template field. A *sparse* instance (what
`cedar-artifact-mcp` produces by default) will be rejected. Complete it first — e.g.
`cedar-artifact-mcp`'s `instance_to_json` given the template — then `create`/`update` it here.

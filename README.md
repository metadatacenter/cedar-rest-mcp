# cedar-rest-mcp

An MCP server that manages CEDAR **artifacts** — templates, elements, fields, and instances —
through the CEDAR **resource-server REST API**. It is the I/O counterpart to
[`cedar-artifact-mcp`](../cedar-artifact-mcp): that one builds, converts, and validates artifacts
in memory; this one persists them to, and fetches them from, a live CEDAR server.

See [DESIGN.md](./DESIGN.md) for the principles and [ROADMAP.md](./ROADMAP.md) for scope and
deferred work.

## Example workflow

A typical session looks like the following — natural-language prompts the user gives the LLM,
which it translates into REST MCP tool calls against a live CEDAR server. This MCP is the
**persistence half** of the pipeline: author and shape an artifact in memory with
[`cedar-artifact-mcp`](../cedar-artifact-mcp), then hand it here to validate, save, fetch, update,
or remove it. Artifacts go in as **YAML or JSON** (auto-detected) and come back as **compact**
YAML — the lean view that drops provenance; pass `isCompact: false` for the fully-provenanced
form.

Assume the LLM already has a Patient Study template in hand, authored with `cedar-artifact-mcp`:

```yaml
type: template
name: Patient Study
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
```

*Check it against CEDAR's validator before I save it.*

```json
{ "validates": true, "warnings": [], "errors": [] }
```

`validate_artifact` posts the artifact to the server's authoritative validator and returns its
report. Nothing is created — it is a dry run, usable on any artifact including ones pulled from
elsewhere (the `validate_*` per-kind tools behave the same).

*Save it to CEDAR.*

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/0e8f3a91-7d2c-4b6a-9e1f-5a8c2d0b4e63
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
```

`create_template` blanks the top-level `@id` before sending, so the **server** mints the identity
and returns it — the `id:` above is the server's, not anything you supplied. Two server behaviors
to expect: it **requires** a `version` and `status` (both supplied automatically on the YAML
path), and on store it **rewrites** the JSON-Schema `title` / `description` (it leaves
`schema:name` / `schema:description` alone).

*Fetch it back.*

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/0e8f3a91-7d2c-4b6a-9e1f-5a8c2d0b4e63
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
```

`get_template` takes the artifact's `@id` (the full IRI); URL-encoding into the path is handled for
you.

*Bump it to version 1.0.0 and update it.*

`update_template` keeps the `@id` — the `id` argument and the body's `@id` must agree — and returns
the stored artifact. Unlike `create`, nothing is re-minted.

*Now create an instance with Patient Name = Alice and Age = 30.*

The CEDAR server requires an instance to carry **every** template field; a sparse instance is
rejected. So the LLM first asks `cedar-artifact-mcp` to produce a complete JSON instance
(`instance_to_json`, given the template), then persists it here with `create_instance` — which,
like `create_template`, lets the server assign the instance's `@id`.

*Delete the template.*

`delete_template` is **destructive and irreversible**, so the LLM confirms with the user before
calling it, then removes the artifact by IRI.

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
mvn verify           # + integration tests, but the live ones are excluded by default
```

### Live integration tests

`CedarLifecycleIT` exercises validate → create → get → delete against a **real** CEDAR server,
using canned templates from the sibling `cedar-artifact-library` checkout, and deletes whatever it
creates. It is tagged `live` and **excluded from the default build** (mirroring
`bioportal-term-mcp`'s `live` pytest marker). Run it on demand:

```bash
CEDAR_API_KEY="apiKey <key>" CEDAR_BASE_URL="https://resource.metadatacenter.org" \
  mvn verify -Plive
```

Without `CEDAR_API_KEY` the live tests self-skip. Each test cleans up after itself (a failed
cleanup fails the test).

## Instances: complete them first

The CEDAR server requires an instance to carry every template field. A *sparse* instance (what
`cedar-artifact-mcp` produces by default) will be rejected. Complete it first — e.g.
`cedar-artifact-mcp`'s `instance_to_json` given the template — then `create`/`update` it here.

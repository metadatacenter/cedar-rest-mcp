# Design

Principles governing what belongs in `cedar-rest-mcp`. Read before adding a tool or input.

## Principle 1 — Artifacts only, over one REST API

This MCP wraps exactly one thing: the CEDAR **resource server** REST API, and only its
**artifact** surface — templates, template-elements, template-fields, template-instances. CRUD
plus server-side validation. Everything else the resource server offers — folders, categories,
search, users, groups, permissions, index maintenance — is out of scope (see ROADMAP.md).

## Principle 2 — The server is the system of record

`cedar-rest-mcp` does not model, mutate, or reason about artifacts beyond format conversion. The
CEDAR server owns identity (`@id`), validation, versioning, and persistence. This MCP is a thin,
honest conduit to it. Artifact *construction* and in-memory validation/conversion live in
`cedar-artifact-mcp`; the two compose (build there, persist here).

## Principle 3 — YAML in and out, JSON on the wire

The REST API accepts only JSON today, but the ecosystem's exchange currency is compact YAML. So a
tool accepts YAML *or* JSON from the caller (auto-detected), converts to canonical JSON for the
request via `cedar-artifact-library`, and renders JSON responses back to **compact YAML** by
default (`isCompact:false` for the expanded form). When the REST API gains YAML support, the
inbound conversion becomes a passthrough (ROADMAP.md).

## Principle 4 — `@id` on create vs update

`create_*` forces the top-level `@id` to JSON `null` so the **server** assigns the identity (and
returns it). It overwrites whatever the caller's artifact carried — notably an `@id` minted by
`cedar-artifact-mcp`. `update_*` preserves the `@id` (it identifies the artifact; the path `{id}`
and body `@id` must agree).

Only the **top-level** `@id` is nulled. Nested element/field `@id`s are submitted exactly as the
artifact carries them — this is the correct behaviour: the server assigns the artifact's own
identity and accepts whatever the nested children carry. (Artifacts produced by `cedar-artifact-mcp`
are child-`@id`-less anyway — it mints a top-level `@id` only — so the common path submits no
nested `@id`s to begin with.)

## Principle 5 — Errors are content

A non-2xx response is returned as a `CallToolResult` with `isError=true` carrying the HTTP status
and the server's response body — not an MCP protocol error. The LLM can read it and react. The
same applies to parse/convert failures.

## Principle 6 — Writes are real; deletes are dangerous

`create` / `update` / `delete` mutate a live server. `delete_*` is **destructive and
irreversible**; its description instructs confirming with the user first. The MCP can't enforce
confirmation, so the tool surface makes the danger unmistakable. Reads and `validate_artifact` are
side-effect-free.

## Principle 7 — Stateless; config and secrets from the environment

No session state — every call carries its IRIs and artifact. The base URL (`CEDAR_BASE_URL`) and
API key (`CEDAR_API_KEY`) come from the environment (set in the MCP client's config); the key is
never logged or echoed, only placed in the `Authorization` header.

## Principle 8 — The HTTP boundary is a seam

All network I/O goes through the `CedarHttp` interface. The production implementation
(`DefaultCedarHttp`) uses `java.net.http`; tests inject a fake, so handlers, the codec, the
`@id`-null rule, path encoding, and error handling are all verified without a live server.

## Note — instances must be complete before create/update

The CEDAR server requires an instance to carry every template field (the template's JSON Schema
marks them required). A *sparse* instance (the form `cedar-artifact-mcp` produces) will be
rejected. Complete it first — e.g. `cedar-artifact-mcp`'s `instance_to_json` given the template —
then create/update it here. `cedar-rest-mcp` does not fetch the template to inflate (it has no
template on hand); revisit if that ergonomic becomes worth the coupling.

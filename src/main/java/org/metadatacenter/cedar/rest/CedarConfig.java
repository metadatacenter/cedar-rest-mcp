package org.metadatacenter.cedar.rest;

/**
 * Runtime configuration for the CEDAR resource-server connection, read from the environment:
 *
 * <ul>
 *   <li>{@code CEDAR_API_KEY} — the CEDAR API key (required for any live call). May be given
 *       bare or already prefixed with {@code apiKey }.</li>
 *   <li>{@code CEDAR_BASE_URL} — resource-server base URL; defaults to production.</li>
 * </ul>
 *
 * <p>The key is never logged or echoed; only {@link #authorizationHeader()} consumes it.
 */
public final class CedarConfig
{
  private static final String DEFAULT_BASE_URL = "https://resource.metadatacenter.org";

  private final String baseUrl;
  private final String apiKey;

  CedarConfig(String baseUrl, String apiKey)
  {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  public static CedarConfig fromEnv()
  {
    String base = System.getenv("CEDAR_BASE_URL");
    if (base == null || base.isBlank())
      base = DEFAULT_BASE_URL;
    base = base.trim();
    while (base.endsWith("/"))
      base = base.substring(0, base.length() - 1);

    String key = System.getenv("CEDAR_API_KEY");
    return new CedarConfig(base, key == null ? "" : key.trim());
  }

  public String baseUrl() { return baseUrl; }

  public boolean hasApiKey() { return !apiKey.isBlank(); }

  /**
   * The {@code Authorization} header value CEDAR expects: {@code apiKey <key>}. Tolerates a key
   * that already carries the {@code apiKey } prefix.
   */
  public String authorizationHeader()
  {
    return apiKey.startsWith("apiKey ") ? apiKey : "apiKey " + apiKey;
  }
}

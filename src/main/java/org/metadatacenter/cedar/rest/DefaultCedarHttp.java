package org.metadatacenter.cedar.rest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Production {@link CedarHttp} over {@code java.net.http.HttpClient}. Adds the
 * {@code Authorization: apiKey <key>} header and JSON content negotiation, and turns transport
 * failures into a {@link RuntimeException} the calling tool surfaces as an error result.
 */
public final class DefaultCedarHttp implements CedarHttp
{
  private final CedarConfig config;
  private final HttpClient client;

  public DefaultCedarHttp(CedarConfig config)
  {
    this.config = config;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  @Override public CedarResponse request(String method, String pathAndQuery, String jsonBody)
  {
    HttpRequest.BodyPublisher bodyPublisher = jsonBody == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(jsonBody);

    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(config.baseUrl() + pathAndQuery))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", config.authorizationHeader())
        .header("Accept", "application/json")
        .method(method, bodyPublisher);
    if (jsonBody != null)
      builder.header("Content-Type", "application/json");

    try {
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new CedarResponse(response.statusCode(), response.body());
    } catch (Exception e) {
      throw new RuntimeException(
          "HTTP " + method + " " + pathAndQuery + " to CEDAR failed: " + e.getMessage(), e);
    }
  }
}

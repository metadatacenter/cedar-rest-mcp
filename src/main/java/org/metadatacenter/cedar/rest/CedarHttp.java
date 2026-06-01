package org.metadatacenter.cedar.rest;

/**
 * The HTTP seam to the CEDAR resource server. An interface so tool handlers can be unit-tested
 * against a fake transport without a live server (the production implementation is
 * {@link DefaultCedarHttp}).
 */
public interface CedarHttp
{
  /**
   * Send a request to the resource server.
   *
   * @param method       HTTP method (GET / POST / PUT / DELETE)
   * @param pathAndQuery path (and any query string) relative to the base URL, e.g.
   *                     {@code /templates/https%3A%2F%2F...}; the implementation prepends the base URL
   * @param jsonBody     request body as a JSON string, or {@code null} for no body
   * @return the status and body of the response
   */
  CedarResponse request(String method, String pathAndQuery, String jsonBody);

  /** Status and (string) body of a resource-server response. */
  record CedarResponse(int status, String body)
  {
    public boolean isSuccess() { return status >= 200 && status < 300; }
  }
}

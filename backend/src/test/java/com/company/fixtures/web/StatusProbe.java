package com.company.fixtures.web;

/**
 * Test-only: the HTTP status of a GET against the running application, whatever it is, sent with a
 * {@code USER} token ({@link #status}) or without one ({@link #anonymousStatus}).
 */
public final class StatusProbe {

  private StatusProbe() {}

  public static int status(int port, String path) {
    return LenientRestClient.forPort(port)
        .get()
        .uri(path)
        .retrieve()
        .toBodilessEntity()
        .getStatusCode()
        .value();
  }

  public static int anonymousStatus(int port, String path) {
    return LenientRestClient.anonymous(port)
        .get()
        .uri(path)
        .retrieve()
        .toBodilessEntity()
        .getStatusCode()
        .value();
  }
}

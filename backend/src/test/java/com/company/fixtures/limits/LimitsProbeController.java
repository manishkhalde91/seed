package com.company.fixtures.limits;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Test-only endpoints behind the request limits. Every handler counts its invocations, so a test
 * can tell whether a request reached it; responses stay small whatever the request size.
 */
@RestController
@RequestMapping(LimitsProbeController.BASE)
public class LimitsProbeController {

  public static final String BASE = "/probe-limits";
  public static final String BODY = BASE + "/body";
  public static final String WRITES = BASE + "/writes";
  public static final String UPLOAD = BASE + "/upload";
  public static final String FORM = BASE + "/form";

  private final ApiResponses responses;
  private final AtomicInteger invocations = new AtomicInteger();

  public LimitsProbeController(ApiResponses responses) {
    this.responses = responses;
  }

  public record Payload(String text) {}

  public int invocations() {
    return invocations.get();
  }

  public void reset() {
    invocations.set(0);
  }

  /** A JSON body read by Jackson: answers the length of its text. */
  @PostMapping(path = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<Integer>> body(@RequestBody Payload payload) {
    invocations.incrementAndGet();
    return responses.created(
        "Body read", payload.text().length(), URI.create(BODY + "/" + invocations.get()));
  }

  /** Any method: a {@code POST} is answered 201, everything else 200. */
  @RequestMapping(
      path = "/writes",
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE
      })
  public ResponseEntity<ApiResponse<Integer>> write(HttpMethod method) {
    int invocation = invocations.incrementAndGet();
    return method == HttpMethod.POST
        ? responses.created("Created", invocation, URI.create(WRITES + "/" + invocation))
        : responses.ok(method.name(), invocation);
  }

  /** A form post, parsed by the container: answers the length of the parameter. */
  @PostMapping(path = "/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<ApiResponse<Integer>> form(@RequestParam("text") String text) {
    invocations.incrementAndGet();
    return responses.ok("Form read", text.length());
  }

  /** A multipart upload: answers the file size. */
  @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<Long>> upload(@RequestParam("file") MultipartFile file) {
    invocations.incrementAndGet();
    return responses.ok("Uploaded", file.getSize());
  }
}

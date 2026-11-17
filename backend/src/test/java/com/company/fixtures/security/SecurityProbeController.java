package com.company.fixtures.security;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.actor.SystemActor;
import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints behind method security: one per role, and one that answers with the current
 * actor and logs a line in the request, inside a {@code system:x} scope, and after it.
 */
@RestController
@RequestMapping(SecurityProbeController.BASE)
public class SecurityProbeController {

  public static final String BASE = "/probe-security";
  public static final String USER_ONLY = BASE + "/user";
  public static final String ADMIN_ONLY = BASE + "/admin";
  public static final String ACTOR = BASE + "/actor";
  public static final String ASYNC = BASE + "/async";

  public static final String HANDLER_LINE = "security probe handler line";
  public static final String SYSTEM_LINE = "security probe system line";
  public static final String AFTER_SCOPE_LINE = "security probe after-scope line";
  public static final String SYSTEM_SCOPE = "system:x";

  private static final Logger LOG = LoggerFactory.getLogger(SecurityProbeController.class);

  private final ApiResponses responses;
  private final CurrentActor currentActor;

  public SecurityProbeController(ApiResponses responses, CurrentActor currentActor) {
    this.responses = responses;
    this.currentActor = currentActor;
  }

  @GetMapping("/user")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).USER)")
  public ResponseEntity<ApiResponse<String>> userOnly() {
    return responses.ok("User probe", currentActor.id());
  }

  @GetMapping("/admin")
  @PreAuthorize("hasRole(T(com.company.seed.security.Roles).ADMIN)")
  public ResponseEntity<ApiResponse<String>> adminOnly() {
    return responses.ok("Admin probe", currentActor.id());
  }

  /** Async processing: the response (and the summary line) is written by the ASYNC dispatch. */
  @GetMapping("/async")
  @PreAuthorize("isAuthenticated()")
  public Callable<ResponseEntity<ApiResponse<String>>> async() {
    return () -> responses.ok("Async", "done");
  }

  @GetMapping("/actor")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<String>> actor() {
    LOG.info(HANDLER_LINE);
    SystemActor.run(SYSTEM_SCOPE, () -> LOG.info(SYSTEM_LINE));
    LOG.info(AFTER_SCOPE_LINE);
    return responses.ok("Actor", currentActor.id());
  }
}

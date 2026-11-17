package com.company.seed.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The actuator exclusion is decided on the container-normalised path, never the raw URI. */
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

  private final RequestLoggingFilter filter = new RequestLoggingFilter();

  @Test
  void rawActuatorPrefixThatNormalisesToAnotherRouteIsLogged(CapturedOutput output)
      throws Exception {
    filter(request("/actuator/..;/probe/ok", "/probe/ok"));

    assertThat(output.getAll()).contains("GET UNMATCHED -> 200 in ");
  }

  @Test
  void actuatorRequestIsNotLogged(CapturedOutput output) throws Exception {
    filter(request("/actuator/health", "/actuator/health"));

    assertThat(output.getAll()).doesNotContain(" -> 200 in ");
  }

  private static MockHttpServletRequest request(String requestUri, String servletPath) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setServletPath(servletPath);
    return request;
  }

  private void filter(MockHttpServletRequest request) throws Exception {
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
  }
}

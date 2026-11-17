package com.company.fixtures.web;

import com.company.seed.common.api.ApiResponse;
import com.company.seed.common.api.ApiResponses;
import com.company.seed.common.api.PageQuery;
import com.company.seed.common.api.PageResult;
import com.company.seed.common.api.Sortable;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only: a paged endpoint outside any feature module, so the {@link PageQuery} binding and the
 * envelope documentation are tested without the sample. It answers one item echoing the bound query
 * ({@code sort} as {@code field:DIRECTION,…}).
 */
@RestController
public class PagingProbeController {

  public static final String PATH = "/probe/paging";

  private final ApiResponses responses;

  public PagingProbeController(ApiResponses responses) {
    this.responses = responses;
  }

  @GetMapping(PATH)
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<List<PagingProbeItem>>> page(
      @Sortable(
              fields = {"name", "rank"},
              defaultSort = "rank,desc")
          PageQuery query) {
    String sort =
        query.sort().stream()
            .map(order -> order.field() + ":" + order.direction())
            .collect(Collectors.joining(","));
    return responses.page(
        "Probe page",
        new PageResult<>(
            List.of(new PagingProbeItem(query.page(), query.size(), sort)),
            query.page(),
            query.size(),
            1));
  }
}

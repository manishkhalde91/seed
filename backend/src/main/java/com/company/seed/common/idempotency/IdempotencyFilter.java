package com.company.seed.common.idempotency;

import com.company.seed.common.actor.CurrentActor;
import com.company.seed.common.error.ErrorCode;
import com.company.seed.common.error.web.ApiErrorResponseWriter;
import com.company.seed.common.logging.CorrelationIdFilter;
import com.company.seed.common.logging.LogSafe;
import com.company.seed.common.logging.RequestLoggingFilter;
import com.company.seed.common.web.RequestCharsets;
import com.company.seed.common.web.WriteMethods;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

/**
 * {@value #HEADER} support (api-standards.md §1). Only {@code POST}, {@code PUT}, {@code PATCH} and
 * {@code DELETE} requests carrying the header take part; everything else passes through untouched.
 *
 * <p>Form ({@code application/x-www-form-urlencoded}) and {@code multipart/form-data} writes also
 * pass through untouched, key or not: the container parses their parameters and parts from the
 * request stream, which this filter would otherwise have to consume.
 *
 * <p>A key must match {@link CorrelationIdFilter#TOKEN}, otherwise the request is answered 400
 * {@code MALFORMED_REQUEST}. The body is read whole; its size is bounded by the global limit,
 * {@code seed.limits.max-body-size} ({@code RequestSizeFilter}): a body read past it throws {@code
 * PayloadTooLargeException} before any claim, answered 413 by the error handling. The request's
 * fingerprint is the SHA-256 of method, raw request URI, raw query and raw body. The key is claimed
 * by committing an {@code IN_PROGRESS} row with a fresh claim token before the handler runs; a
 * database failure while claiming is answered 503 {@code SERVICE_UNAVAILABLE}. A record older than
 * {@code seed.idempotency.retention} counts as absent. If the key is already taken:
 *
 * <ul>
 *   <li>a different fingerprint (any state) is answered 409 {@code IDEMPOTENCY_KEY_REUSED};
 *   <li>a {@code COMPLETED} row is replayed (status, {@code Content-Type}, {@code Location}, body)
 *       with {@value #REPLAYED_HEADER}{@code : true}, without running the handler;
 *   <li>an {@code IN_PROGRESS} row claimed less than {@code seed.idempotency.abandon-after} ago is
 *       answered 409 {@code IDEMPOTENCY_REQUEST_IN_PROGRESS} with {@code Retry-After: 1}; an older
 *       one is taken over (a new claim token) and the handler runs.
 * </ul>
 *
 * After the handler, a 2xx or 4xx response is stored and the row completed; anything else (a 1xx,
 * 3xx or 5xx, a 401, 403, 408 or 429, an exception escaping the chain, an error or redirect sent by
 * the container, a response body larger than {@code seed.idempotency.max-stored-response-size})
 * releases the row so a retry runs again. Both writes match this request's claim token, so a
 * request whose claim was taken over changes nothing (and is logged at WARN). Async handlers are
 * finished by the final ASYNC dispatch.
 *
 * <p>Records are scoped to the caller, {@link CurrentActor#id()}: the same key from two callers is
 * two independent records, and nobody replays another caller's response.
 *
 * <p>Runs after Spring Security's filter chain ({@value #ORDER}, after its default {@code -100})
 * and after the write rate limit ({@code WriteRateLimitFilter}, so a replay costs a token like any
 * write), so a request is authenticated (and its caller known) before its key is looked at: a
 * replay needs a valid token, carries the security headers, and a 401 is never stored. A replay
 * does not pass {@code @PreAuthorize} again, so a caller who lost a role within the retention
 * window can still replay their own earlier successful response; nothing new is written. It still
 * runs inside {@link RequestLoggingFilter}, so replays and this filter's own answers carry the
 * correlation ID and get a summary line. Bodies are never logged.
 */
@Component
@Order(IdempotencyFilter.ORDER)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyFilter extends OncePerRequestFilter {

  /** Filter order: right after Spring Security's filter chain ({@code -100}). */
  public static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10;

  /** Request header carrying the key. */
  public static final String HEADER = "Idempotency-Key";

  /** Response header marking a replayed response. */
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  static final String MALFORMED_MESSAGE = "Invalid Idempotency-Key header";
  static final String REUSED_MESSAGE =
      "This Idempotency-Key was already used for a different request";
  static final String IN_PROGRESS_MESSAGE =
      "A request with this Idempotency-Key is in progress; retry later";
  static final String UNAVAILABLE_MESSAGE = "Service unavailable";

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);
  private static final Set<String> FORM_TYPES =
      Set.of("application/x-www-form-urlencoded", "multipart/form-data");
  private static final String CLAIM_ATTRIBUTE = IdempotencyFilter.class.getName() + ".claim";
  private static final String RETRY_AFTER_SECONDS = "1";
  private static final int CLAIM_ATTEMPTS = 3;

  /**
   * Client errors that say nothing about the request itself (who sent it, or when), so a retry must
   * run again: never stored.
   */
  private static final Set<Integer> NEVER_STORED =
      Set.of(
          HttpServletResponse.SC_UNAUTHORIZED,
          HttpServletResponse.SC_FORBIDDEN,
          HttpServletResponse.SC_REQUEST_TIMEOUT,
          429);

  private final IdempotencyRecordRepository repository;
  private final IdempotencyProperties properties;
  private final ApiErrorResponseWriter errorWriter;
  private final CurrentActor currentActor;
  private final Clock clock;

  /**
   * The repository is resolved lazily: filters are created while the embedded server starts, before
   * the rest of the context, and an eager dependency would pull the database, Flyway and JPA into
   * that early phase.
   */
  public IdempotencyFilter(
      @Lazy IdempotencyRecordRepository repository,
      IdempotencyProperties properties,
      ApiErrorResponseWriter errorWriter,
      CurrentActor currentActor,
      Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.errorWriter = errorWriter;
    this.currentActor = currentActor;
    this.clock = clock;
  }

  /** A claim this request holds: the caller, the key and its own token. */
  private record Claim(String caller, String key, UUID token) {}

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !WriteMethods.ALL.contains(request.getMethod()) || request.getHeader(HEADER) == null;
  }

  /** The ASYNC dispatch of a keyed request finishes its claim. */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAsyncDispatch(request)) {
      asyncDispatch(request, response, chain);
      return;
    }
    if (isForm(request)) {
      LOG.debug("{} ignored on a form or multipart request", HEADER);
      chain.doFilter(request, response);
      return;
    }
    String key = validKey(request);
    if (key == null) {
      LOG.debug("Rejected a malformed {} header", HEADER);
      errorWriter.write(
          request,
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          ErrorCode.MALFORMED_REQUEST,
          MALFORMED_MESSAGE);
      return;
    }
    byte[] body = request.getInputStream().readAllBytes();
    String requestHash = fingerprint(request, body);
    ClaimAttempt attempt = tryClaim(request, response, currentActor.id(), key, requestHash);
    if (attempt.failure() != null) {
      LOG.error("Could not claim {} {}", HEADER, LogSafe.clean(key), attempt.failure());
      errorWriter.write(
          request,
          response,
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          ErrorCode.SERVICE_UNAVAILABLE,
          UNAVAILABLE_MESSAGE);
    } else if (attempt.claim().isPresent()) {
      runHandler(new CachedBodyRequest(request, body), response, chain, attempt.claim().get());
    }
  }

  /** The outcome of claiming: the claim (empty if already answered), or the database failure. */
  private record ClaimAttempt(Optional<Claim> claim, RuntimeException failure) {}

  /** {@link #claimOrAnswer}, with a database failure returned rather than thrown. */
  private ClaimAttempt tryClaim(
      HttpServletRequest request,
      HttpServletResponse response,
      String caller,
      String key,
      String requestHash)
      throws IOException {
    try {
      return new ClaimAttempt(claimOrAnswer(request, response, caller, key, requestHash), null);
    } catch (RuntimeException ex) {
      return new ClaimAttempt(Optional.empty(), ex);
    }
  }

  /** Whether the body is a form or multipart, whose content the container parses itself. */
  private static boolean isForm(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null
        && FORM_TYPES.contains(contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT));
  }

  /** The single header value if it is a valid key; {@code null} if malformed or repeated. */
  private static String validKey(HttpServletRequest request) {
    var values = Collections.list(request.getHeaders(HEADER));
    if (values.size() != 1 || !CorrelationIdFilter.TOKEN.matcher(values.getFirst()).matches()) {
      return null;
    }
    return values.getFirst();
  }

  /**
   * Claims the key (or takes over an abandoned claim) and returns the claim; otherwise answers the
   * request itself (replay or 409) and returns empty.
   */
  private Optional<Claim> claimOrAnswer(
      HttpServletRequest request,
      HttpServletResponse response,
      String caller,
      String key,
      String requestHash)
      throws IOException {
    UUID token = UUID.randomUUID();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant claimedAtOrBefore = now.minus(properties.abandonAfter());
    Instant expiredBefore = now.minus(properties.retention());
    for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
      if (repository.claim(caller, key, requestHash, token, now, expiredBefore)) {
        return Optional.of(new Claim(caller, key, token));
      }
      Optional<IdempotencyRecord> existing = repository.findByKey(caller, key, expiredBefore);
      if (existing.isEmpty()) {
        continue; // released between the claim and the read: claim again
      }
      IdempotencyRecord record = existing.get();
      if (!record.requestHash().equals(requestHash)) {
        LOG.info("{} {} reused for a different request", HEADER, LogSafe.clean(key));
        errorWriter.write(
            request,
            response,
            HttpServletResponse.SC_CONFLICT,
            ErrorCode.IDEMPOTENCY_KEY_REUSED,
            REUSED_MESSAGE);
        return Optional.empty();
      }
      if (record.status() == IdempotencyStatus.COMPLETED) {
        LOG.info("{} {} replayed", HEADER, LogSafe.clean(key));
        replay(response, record.response());
        return Optional.empty();
      }
      if (!record.updatedAt().isAfter(claimedAtOrBefore)
          && repository.takeOver(
              caller, key, requestHash, record.claimToken(), token, claimedAtOrBefore, now)) {
        LOG.info("{} {} taken over from an abandoned request", HEADER, LogSafe.clean(key));
        return Optional.of(new Claim(caller, key, token));
      }
      if (record.updatedAt().isAfter(claimedAtOrBefore)) {
        break;
      }
      // Lost a take-over race or the row changed meanwhile: look again.
    }
    LOG.info("{} {} is in progress", HEADER, LogSafe.clean(key));
    response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    errorWriter.write(
        request,
        response,
        HttpServletResponse.SC_CONFLICT,
        ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
        IN_PROGRESS_MESSAGE);
    return Optional.empty();
  }

  private void runHandler(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain, Claim claim)
      throws ServletException, IOException {
    CapturingResponse capturing = new CapturingResponse(response);
    try {
      chain.doFilter(request, capturing);
    } catch (Throwable ex) {
      release(claim).ifPresent(ex::addSuppressed);
      throw ex;
    }
    if (isAsyncStarted(request)) {
      request.setAttribute(CLAIM_ATTRIBUTE, claim);
      return;
    }
    finish(claim, capturing);
  }

  /** The final ASYNC dispatch stores or releases what the original dispatch left open. */
  private void asyncDispatch(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Claim claim = (Claim) request.getAttribute(CLAIM_ATTRIBUTE);
    CapturingResponse capturing = WebUtils.getNativeResponse(response, CapturingResponse.class);
    if (claim == null || capturing == null) {
      chain.doFilter(request, response);
      return;
    }
    try {
      chain.doFilter(request, response);
    } catch (Throwable ex) {
      request.removeAttribute(CLAIM_ATTRIBUTE);
      release(claim).ifPresent(ex::addSuppressed);
      throw ex;
    }
    if (!isAsyncStarted(request)) {
      request.removeAttribute(CLAIM_ATTRIBUTE);
      finish(claim, capturing);
    }
  }

  /**
   * Stores a 2xx or 4xx response (except {@link #NEVER_STORED}), releases anything else (or a body
   * over {@code seed.idempotency.max-stored-response-size}), then sends the response. If the
   * database write fails, the client still gets its response (the handler's work is done) and the
   * failure is logged: a row left {@code IN_PROGRESS} answers duplicates 409 until abandon-after.
   */
  private void finish(Claim claim, CapturingResponse capturing) throws IOException {
    int status = capturing.getStatus();
    boolean storable =
        !capturing.answeredByContainer()
            && (isSuccess(status) || isClientError(status))
            && !NEVER_STORED.contains(status);
    boolean tooLarge = storable && capturing.getContentSize() > properties.maxStoredResponseBytes();
    if (tooLarge) {
      LOG.warn(
          "Response for {} {} is over the stored-response size limit; sent but not stored",
          HEADER,
          LogSafe.clean(claim.key()));
    }
    Optional<RuntimeException> failure =
        storable && !tooLarge ? complete(claim, capturing, status) : release(claim);
    failure.ifPresent(
        ex ->
            LOG.warn(
                "Could not record the outcome of {} {}", HEADER, LogSafe.clean(claim.key()), ex));
    capturing.copyBodyToResponse();
  }

  private Optional<RuntimeException> complete(
      Claim claim, CapturingResponse capturing, int status) {
    StoredResponse stored =
        new StoredResponse(
            status,
            capturing.getContentType(),
            capturing.getHeader(HttpHeaders.LOCATION),
            capturing.getContentAsByteArray());
    return write(
        claim,
        () ->
            repository.complete(
                claim.caller(), claim.key(), claim.token(), stored, clock.instant()));
  }

  private Optional<RuntimeException> release(Claim claim) {
    return write(claim, () -> repository.release(claim.caller(), claim.key(), claim.token()));
  }

  /**
   * Runs a write bound to this claim. A {@code false} result means another request took the claim
   * over (the handler ran twice), which is logged; a database failure is returned, not thrown.
   */
  private static Optional<RuntimeException> write(Claim claim, BooleanSupplier statement) {
    try {
      if (!statement.getAsBoolean()) {
        LOG.warn(
            "{} {} was taken over by another request; this request's outcome is not recorded",
            HEADER,
            LogSafe.clean(claim.key()));
      }
      return Optional.empty();
    } catch (RuntimeException ex) {
      return Optional.of(ex);
    }
  }

  private static void replay(HttpServletResponse response, StoredResponse stored)
      throws IOException {
    byte[] body = stored.body();
    response.setStatus(stored.status());
    if (stored.contentType() != null) {
      response.setContentType(stored.contentType());
    }
    if (stored.location() != null) {
      response.setHeader(HttpHeaders.LOCATION, stored.location());
    }
    response.setHeader(REPLAYED_HEADER, "true");
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
    response.flushBuffer();
  }

  private static boolean isSuccess(int status) {
    return status >= 200 && status < 300;
  }

  private static boolean isClientError(int status) {
    return status >= 400 && status < 500;
  }

  /**
   * SHA-256 (hex) of method, {@code \n}, the raw request URI (still encoded, context path
   * included), {@code \n}, the raw query (empty if none), {@code \n} and the raw body bytes.
   */
  static String fingerprint(HttpServletRequest request, byte[] body) {
    String query = request.getQueryString() == null ? "" : request.getQueryString();
    String head = request.getMethod() + "\n" + request.getRequestURI() + "\n" + query + "\n";
    MessageDigest digest = sha256();
    digest.update(head.getBytes(StandardCharsets.UTF_8));
    digest.update(body);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
    }
  }

  /** The request with its body read once and served again to the handler. */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() throws UnsupportedEncodingException {
      return new BufferedReader(
          new InputStreamReader(
              new ByteArrayInputStream(body), RequestCharsets.of(getCharacterEncoding())));
    }
  }

  private static final class ByteArrayServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream input;

    ByteArrayServletInputStream(byte[] body) {
      this.input = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return input.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      return input.read(buffer, offset, length);
    }

    @Override
    public boolean isFinished() {
      return input.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      throw new UnsupportedOperationException("Non-blocking reads are not supported");
    }
  }

  /**
   * Buffers the response, and notes when the handler hands the answer to the container ({@code
   * sendError}, {@code sendRedirect}): that body is written later by an ERROR dispatch, so it
   * cannot be stored.
   */
  private static final class CapturingResponse extends ContentCachingResponseWrapper {

    private boolean answeredByContainer;

    CapturingResponse(HttpServletResponse response) {
      super(response);
    }

    boolean answeredByContainer() {
      return answeredByContainer;
    }

    @Override
    public void sendError(int status) throws IOException {
      answeredByContainer = true;
      super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
      answeredByContainer = true;
      super.sendError(status, message);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
      answeredByContainer = true;
      super.sendRedirect(location);
    }
  }
}

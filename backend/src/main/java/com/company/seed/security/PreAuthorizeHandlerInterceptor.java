package com.company.seed.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks a handler's {@code @PreAuthorize} before Spring MVC binds and validates its arguments, so
 * a caller without the role gets 403 (or 401 without authentication) rather than a 400 that
 * describes the request body. The expression on the method, else on its class (meta-annotations
 * included), is evaluated with the method-security expression handler (so the role hierarchy
 * applies). An expression that refers to method arguments ({@code #name}) cannot be evaluated
 * before binding and is left to the method-security proxy, which still checks every expression.
 */
final class PreAuthorizeHandlerInterceptor implements HandlerInterceptor {

  private final MethodSecurityExpressionHandler expressionHandler;
  private final Map<Method, Optional<Expression>> expressions = new ConcurrentHashMap<>();

  PreAuthorizeHandlerInterceptor(MethodSecurityExpressionHandler expressionHandler) {
    this.expressionHandler = expressionHandler;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    Optional<Expression> expression =
        expressions.computeIfAbsent(handlerMethod.getMethod(), method -> parse(handlerMethod));
    if (expression.isEmpty()) {
      return true;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    EvaluationContext context =
        expressionHandler.createEvaluationContext(
            () -> authentication,
            new SimpleMethodInvocation(handlerMethod.getBean(), handlerMethod.getMethod()));
    if (!ExpressionUtils.evaluateAsBoolean(expression.get(), context)) {
      throw new AccessDeniedException("Access denied by @PreAuthorize before argument binding");
    }
    return true;
  }

  private Optional<Expression> parse(HandlerMethod handlerMethod) {
    PreAuthorize rule =
        AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
    if (rule == null) {
      rule =
          AnnotatedElementUtils.findMergedAnnotation(
              handlerMethod.getBeanType(), PreAuthorize.class);
    }
    if (rule == null || rule.value().contains("#")) {
      return Optional.empty();
    }
    return Optional.of(expressionHandler.getExpressionParser().parseExpression(rule.value()));
  }
}

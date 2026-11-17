package com.company.seed.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link PreAuthorizeHandlerInterceptor}, with the same role hierarchy as method
 * security, for every handler.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class MethodSecurityWebConfig implements WebMvcConfigurer {

  private final ObjectProvider<RoleHierarchy> roleHierarchy;
  private final ApplicationContext applicationContext;

  /** The role hierarchy is absent only in test slices that leave out {@link SecurityConfig}. */
  MethodSecurityWebConfig(
      ObjectProvider<RoleHierarchy> roleHierarchy, ApplicationContext applicationContext) {
    this.roleHierarchy = roleHierarchy;
    this.applicationContext = applicationContext;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    DefaultMethodSecurityExpressionHandler expressionHandler =
        new DefaultMethodSecurityExpressionHandler();
    roleHierarchy.ifAvailable(expressionHandler::setRoleHierarchy);
    expressionHandler.setApplicationContext(applicationContext);
    registry.addInterceptor(new PreAuthorizeHandlerInterceptor(expressionHandler));
  }
}

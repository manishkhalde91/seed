package com.company.seed.common.api;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Lets every controller take a {@link PageQuery} parameter, and fails startup when a {@link
 * Sortable} whitelist is inconsistent (a default sort that does not parse or names a field outside
 * {@code fields}), instead of failing every list request.
 */
@Configuration(proxyBeanMethods = false)
class PageQueryWebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new PageQueryArgumentResolver());
  }

  @Bean
  static SmartInitializingSingleton sortableWhitelistCheck(ApplicationContext context) {
    return () ->
        context
            .getBeansOfType(RequestMappingHandlerMapping.class)
            .values()
            .forEach(
                mapping ->
                    mapping
                        .getHandlerMethods()
                        .values()
                        .forEach(
                            handler ->
                                Arrays.stream(handler.getMethodParameters())
                                    .filter(p -> p.hasParameterAnnotation(Sortable.class))
                                    .forEach(
                                        p ->
                                            PageQueryArgumentResolver.check(
                                                p.getParameterAnnotation(Sortable.class),
                                                handler.toString()))));
  }
}

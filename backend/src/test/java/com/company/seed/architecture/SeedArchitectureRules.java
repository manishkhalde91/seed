package com.company.seed.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Layering rules for the seed backend (backend-architecture.md §5, §6; SPEC CAP-2).
 *
 * <p>Layer patterns (api, infrastructure) are limited to {@code com.company..} so that third-party
 * packages such as {@code io.opentelemetry.api} are never taken for a layer.
 *
 * <p>The rules are written against package patterns, not against {@code com.company.seed}, so the
 * same rule objects can be checked against the production classes ({@link ArchitectureTest}) and
 * against the deliberately broken fixtures in {@code com.company.fixtures} (which prove each rule
 * reports a violation). Every rule allows an empty subject, because most layers have no classes
 * until feature modules are added.
 */
public final class SeedArchitectureRules {

  private static final String DOMAIN = "..domain..";
  private static final String APPLICATION = "..application..";
  private static final String API = "com.company..api..";
  private static final String INFRASTRUCTURE = "com.company..infrastructure..";
  private static final String PERSISTENCE_ADAPTER = "com.company..infrastructure.persistence..";
  private static final String FEATURE = "..feature..";
  private static final String FEATURE_DOMAIN = "..feature.*.domain..";
  private static final String JPA = "jakarta.persistence..";
  private static final String SPRING_DATA = "org.springframework.data";
  private static final String SPRING_DATA_REPOSITORY =
      "org.springframework.data.repository.Repository";
  private static final String COMMON_API = "com.company..common.api..";
  private static final String COMMON_ERROR = "com.company..common.error..";
  private static final String COMMON_ERROR_WEB = "com.company..common.error.web..";
  private static final String REST_CONTROLLER =
      "org.springframework.web.bind.annotation.RestController";
  private static final String RESPONSE_ENTITY = "org.springframework.http.ResponseEntity";
  private static final String REQUEST_MAPPING =
      "org.springframework.web.bind.annotation.RequestMapping";
  private static final String CONTROLLER = "org.springframework.stereotype.Controller";
  private static final String MESSAGE_MAPPING =
      "org.springframework.messaging.handler.annotation.MessageMapping";
  private static final String SUBSCRIBE_MAPPING =
      "org.springframework.messaging.simp.annotation.SubscribeMapping";
  private static final String PRE_AUTHORIZE =
      "org.springframework.security.access.prepost.PreAuthorize";

  /**
   * Handler candidates: public methods, plus request-mapped methods of any visibility (Spring
   * accepts package-private handlers). Compiler-generated bridge and synthetic methods are skipped.
   */
  private static final DescribedPredicate<JavaMethod> PUBLIC_OR_REQUEST_MAPPED =
      DescribedPredicate.describe(
          "public or request-mapped",
          method ->
              !method.getModifiers().contains(JavaModifier.BRIDGE)
                  && !method.getModifiers().contains(JavaModifier.SYNTHETIC)
                  && (method.getModifiers().contains(JavaModifier.PUBLIC)
                      || method.isMetaAnnotatedWith(REQUEST_MAPPING)));

  /**
   * Request handlers: methods mapped with {@code @RequestMapping} or one of its shortcuts ({@code
   * GetMapping}, ...). Other public controller methods ({@code @InitBinder}, {@code
   * ExceptionHandler}, {@code @ModelAttribute}) are not requests a client can make.
   */
  private static final DescribedPredicate<JavaMethod> REQUEST_MAPPED =
      DescribedPredicate.describe(
          "request-mapped",
          method ->
              !method.getModifiers().contains(JavaModifier.BRIDGE)
                  && !method.getModifiers().contains(JavaModifier.SYNTHETIC)
                  && method.isMetaAnnotatedWith(REQUEST_MAPPING));

  /**
   * Message handlers a STOMP client can reach: methods mapped with {@code @MessageMapping} or
   * {@code @SubscribeMapping} (a {@code @MessageExceptionHandler} is not one).
   */
  private static final DescribedPredicate<JavaMethod> MESSAGE_MAPPED =
      DescribedPredicate.describe(
          "message-mapped",
          method ->
              !method.getModifiers().contains(JavaModifier.BRIDGE)
                  && !method.getModifiers().contains(JavaModifier.SYNTHETIC)
                  && (method.isMetaAnnotatedWith(MESSAGE_MAPPING)
                      || method.isMetaAnnotatedWith(SUBSCRIBE_MAPPING)));

  /**
   * The web layer: every api package except common.api, which holds the shared response and paging
   * contracts (PageResult, ApiResponse) that domain repository interfaces and application services
   * may use.
   */
  private static final DescribedPredicate<JavaClass> WEB_API_LAYER =
      resideInAPackage(API).and(not(resideInAPackage(COMMON_API))).as("an api (web) layer");

  private static final DescribedPredicate<JavaClass> SPRING_DATA_INTERFACE =
      assignableTo(SPRING_DATA_REPOSITORY).as("Spring Data repository interfaces");

  public static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(JPA, "org.springframework..", INFRASTRUCTURE)
          .orShould()
          .dependOnClassesThat(WEB_API_LAYER)
          .as(
              "domain classes import no jakarta.persistence, org.springframework, infrastructure or"
                  + " api types")
          .allowEmptyShould(true);

  public static final ArchRule APPLICATION_IS_ADAPTER_FREE =
      noClasses()
          .that()
          .resideInAPackage(APPLICATION)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(INFRASTRUCTURE, JPA, "org.springframework.web..")
          .orShould()
          .dependOnClassesThat(WEB_API_LAYER)
          .as(
              "application classes import no infrastructure, api, jakarta.persistence or"
                  + " org.springframework.web types")
          .allowEmptyShould(true);

  public static final ArchRule SPRING_DATA_ONLY_IN_PERSISTENCE_ADAPTERS =
      classes()
          .that(SPRING_DATA_INTERFACE)
          .and()
          .doNotHaveFullyQualifiedName(SPRING_DATA_REPOSITORY)
          .should()
          .resideInAPackage(PERSISTENCE_ADAPTER)
          .as("Spring Data interfaces live only in infrastructure.persistence")
          .allowEmptyShould(true);

  public static final ArchRule API_USES_NO_REPOSITORY =
      noClasses()
          .that(WEB_API_LAYER)
          .should()
          .dependOnClassesThat(simpleNameEndingWith("Repository"))
          .as("api classes depend on no *Repository")
          .allowEmptyShould(true);

  public static final ArchRule FEATURE_REPOSITORY_INTERFACES_IN_DOMAIN =
      classes()
          .that()
          .areInterfaces()
          .and()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .resideInAPackage(FEATURE)
          .and(not(SPRING_DATA_INTERFACE))
          .should()
          .resideInAPackage(FEATURE_DOMAIN)
          .as("feature repository interfaces live in feature/<module>/domain")
          .allowEmptyShould(true);

  public static final ArchRule NO_FIELD_INJECTION =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

  public static final ArchRule NO_JACKSON_2_DATABIND =
      noClasses()
          .should()
          .dependOnClassesThat(resideInAnyPackage("com.fasterxml.jackson.databind.."))
          .as("no class uses Jackson 2 databind (com.fasterxml.jackson.databind); use Jackson 3")
          .allowEmptyShould(true);

  /**
   * Every public method of a {@code @RestController} returns {@code
   * ResponseEntity<ApiResponse<...>>} with the {@code common.api} envelope (api-standards.md §3).
   * Actuator and OpenAPI endpoints are outside {@code com.company} and so exempt.
   */
  public static final ArchRule REST_CONTROLLERS_RETURN_ENVELOPE =
      methods()
          .that(PUBLIC_OR_REQUEST_MAPPED)
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.company..")
          .and()
          .areDeclaredInClassesThat()
          .areMetaAnnotatedWith(REST_CONTROLLER)
          .should(returnEnvelope())
          .as(
              "public and request-mapped @RestController methods return ResponseEntity<ApiResponse<...>>")
          .allowEmptyShould(true);

  /** {@code common.error} (except its web adapter) is thrown from the domain, so has no Spring. */
  public static final ArchRule COMMON_ERROR_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage(COMMON_ERROR)
          .and()
          .resideOutsideOfPackage(COMMON_ERROR_WEB)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..")
          .as("common.error classes (outside common.error.web) import no org.springframework types")
          .allowEmptyShould(true);

  /**
   * Deletion is a soft delete through the domain (data-and-migrations.md §5), so no Spring Data
   * interface offers a hard delete: no {@code delete*} or {@code remove*} method, declared or
   * inherited (from {@code CrudRepository}, {@code JpaRepository}, ...), and no {@code @Query}
   * under any name whose statement starts with {@code DELETE}.
   */
  public static final ArchRule SPRING_DATA_DECLARES_NO_DELETE =
      classes()
          .that(SPRING_DATA_INTERFACE)
          .and()
          .areInterfaces()
          .and()
          .resideOutsideOfPackage("org.springframework.data..")
          .should(offerNoDeleteMethods())
          .as(
              "Spring Data interfaces declare and inherit no delete*/remove* or DELETE-query methods")
          .allowEmptyShould(true);

  /**
   * Deny by default at the method level too (security.md §2): every request-mapped or
   * message-mapped ({@code @MessageMapping}, {@code @SubscribeMapping}) method of a
   * {@code @Controller} or {@code @RestController} carries {@code @PreAuthorize}, directly or
   * through a meta-annotation, so a new handler never relies on the URL or destination rules alone.
   */
  public static final ArchRule CONTROLLER_METHODS_ARE_PRE_AUTHORIZED =
      methods()
          .that(REQUEST_MAPPED.or(MESSAGE_MAPPED))
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("com.company..")
          .and()
          .areDeclaredInClassesThat()
          .areMetaAnnotatedWith(CONTROLLER)
          .should()
          .beMetaAnnotatedWith(PRE_AUTHORIZE)
          .as(
              "request- and message-mapped @Controller/@RestController methods carry"
                  + " @PreAuthorize, directly or through a meta-annotation")
          .allowEmptyShould(true);

  /** The audit members a client never sends (data-and-migrations.md §4). */
  private static final Set<String> AUDIT_FIELDS =
      Set.of("createdAt", "createdBy", "updatedAt", "updatedBy", "deletedAt", "deletedBy");

  private static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";
  private static final String JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty";

  /**
   * Audit fields are filled by the server, never taken from a request (data-and-migrations.md §4):
   * no type a method in an api package takes as its {@code @RequestBody} has a field or record
   * component named, or {@code @JsonProperty}-named, {@code createdAt}, {@code createdBy}, {@code
   * updatedAt}, {@code updatedBy}, {@code deletedAt} or {@code deletedBy}.
   */
  public static final ArchRule REQUEST_BODIES_CARRY_NO_AUDIT_FIELDS =
      classes()
          .that()
          .resideInAPackage("..api..")
          .should(takeNoRequestBodyWithAuditFields())
          .as("@RequestBody types of api classes have no audit fields")
          .allowEmptyShould(true);

  public static final List<ArchRule> ALL =
      List.of(
          DOMAIN_IS_FRAMEWORK_FREE,
          APPLICATION_IS_ADAPTER_FREE,
          SPRING_DATA_ONLY_IN_PERSISTENCE_ADAPTERS,
          API_USES_NO_REPOSITORY,
          FEATURE_REPOSITORY_INTERFACES_IN_DOMAIN,
          NO_FIELD_INJECTION,
          NO_JACKSON_2_DATABIND,
          REST_CONTROLLERS_RETURN_ENVELOPE,
          COMMON_ERROR_IS_FRAMEWORK_FREE,
          SPRING_DATA_DECLARES_NO_DELETE,
          CONTROLLER_METHODS_ARE_PRE_AUTHORIZED,
          REQUEST_BODIES_CARRY_NO_AUDIT_FIELDS);

  private static ArchCondition<JavaMethod> returnEnvelope() {
    return new ArchCondition<>("return ResponseEntity<ApiResponse<...>>") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean satisfied = isEnvelope(method.getReturnType());
        String message =
            method.getFullName()
                + " returns "
                + method.getReturnType().getName()
                + " in "
                + method.getSourceCodeLocation();
        events.add(new SimpleConditionEvent(method, satisfied, message));
      }
    };
  }

  /** A record component is a private, final, non-static field of the same name. */
  private static ArchCondition<JavaClass> takeNoRequestBodyWithAuditFields() {
    return new ArchCondition<>("take no @RequestBody with audit fields") {
      @Override
      public void check(JavaClass api, ConditionEvents events) {
        for (JavaMethod method : api.getMethods()) {
          for (JavaParameter parameter : method.getParameters()) {
            if (!parameter.isAnnotatedWith(REQUEST_BODY)) {
              continue;
            }
            JavaClass body = parameter.getRawType();
            for (JavaField field : body.getAllFields()) {
              String name = jsonName(field);
              if (!field.getModifiers().contains(JavaModifier.STATIC)
                  && AUDIT_FIELDS.contains(name)) {
                events.add(
                    SimpleConditionEvent.violated(
                        api,
                        method.getFullName()
                            + " takes "
                            + body.getName()
                            + " with the audit field "
                            + name
                            + " in "
                            + api.getSourceCodeLocation()));
              }
            }
          }
        }
      }
    };
  }

  /** The JSON member name of a field: its {@code @JsonProperty} value, else its own name. */
  private static String jsonName(JavaField field) {
    return field.getAnnotations().stream()
        .filter(annotation -> annotation.getRawType().getName().equals(JSON_PROPERTY))
        .flatMap(annotation -> annotation.get("value").stream())
        .map(Object::toString)
        .filter(value -> !value.isEmpty())
        .findFirst()
        .orElse(field.getName());
  }

  private static ArchCondition<JavaClass> offerNoDeleteMethods() {
    return new ArchCondition<>("offer no hard delete methods") {
      @Override
      public void check(JavaClass repository, ConditionEvents events) {
        for (JavaMethod method : repository.getAllMethods()) {
          if (method.getName().startsWith("delete")
              || method.getName().startsWith("remove")
              || runsADeleteQuery(method)) {
            events.add(
                SimpleConditionEvent.violated(
                    repository,
                    repository.getName()
                        + " offers "
                        + method.getFullName()
                        + " in "
                        + repository.getSourceCodeLocation()));
          }
        }
      }
    };
  }

  /** A Spring Data {@code @Query} whose statement starts with {@code DELETE}, in any case. */
  private static boolean runsADeleteQuery(JavaMethod method) {
    return method.getAnnotations().stream()
        .filter(annotation -> annotation.getRawType().getSimpleName().equals("Query"))
        .filter(annotation -> annotation.getRawType().getPackageName().startsWith(SPRING_DATA))
        .flatMap(annotation -> annotation.get("value").stream())
        .map(value -> value.toString().stripLeading().toUpperCase(Locale.ROOT))
        .anyMatch(statement -> statement.startsWith("DELETE"));
  }

  /** {@code ResponseEntity<ApiResponse<T>>} where ApiResponse is the common.api record. */
  private static boolean isEnvelope(JavaType returnType) {
    if (!(returnType instanceof JavaParameterizedType entity)
        || !entity.toErasure().getName().equals(RESPONSE_ENTITY)
        || entity.getActualTypeArguments().size() != 1
        || !(entity.getActualTypeArguments().get(0) instanceof JavaParameterizedType body)) {
      return false;
    }
    JavaClass bodyClass = body.toErasure();
    return bodyClass.getSimpleName().equals("ApiResponse")
        && resideInAPackage(COMMON_API).test(bodyClass);
  }

  private SeedArchitectureRules() {}
}

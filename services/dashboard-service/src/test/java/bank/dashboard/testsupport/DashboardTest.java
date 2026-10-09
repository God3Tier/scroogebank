package bank.dashboard.testsupport;

import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots the service for a test with the two values a developer machine cannot
 * supply.
 *
 * <p>These are inlined properties rather than a {@code src/test/resources/application.yml}
 * for a reason worth keeping: a test {@code application.yml} shadows the real
 * one on the classpath, so the service would boot under test configuration
 * that nobody deployed. Assertions about production behaviour -- that
 * springdoc stays unpublished without the {@code local} profile, for instance
 * -- would then be asserting the test file's contents. Inlined properties sit
 * above the real {@code application.yml} instead of replacing it.
 *
 * <p>No Cognito pool is dialled: {@code SecurityConfig} builds its decoder from
 * the pool's key URL, which is fetched only when a real token arrives, and
 * tests authenticate through {@code SecurityMockMvcRequestPostProcessors.jwt()}
 * instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "OIDC_ISSUER_URI=https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_test",
        "COGNITO_APP_CLIENT_ID=test-app-client"
})
public @interface DashboardTest {
}

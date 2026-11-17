// Runs the same mock-oauth2-server as infrastructure/compose.yaml, without Docker, with the same
// config (../config.json: issuer "seed", demo-user and demo-admin). Local development only.
//   backend/gradlew -p infrastructure/oidc/standalone installDist      (once)
//   JSON_CONFIG_PATH=$PWD/infrastructure/oidc/config.json SERVER_PORT=8090 \
//     infrastructure/oidc/standalone/build/install/mock-oidc/bin/mock-oidc
// Or through Gradle: backend/gradlew -p infrastructure/oidc/standalone run
// Port: OIDC_PORT (default 8090); keep OIDC_ISSUER_URI's port equal to it.
plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("no.nav.security:mock-oauth2-server:6.1.5")
}

application {
    mainClass.set("no.nav.security.mock.oauth2.StandaloneMockOAuth2ServerKt")
}

tasks.named<JavaExec>("run") {
    environment("JSON_CONFIG_PATH", file("../config.json").absolutePath)
    environment("SERVER_PORT", System.getenv("OIDC_PORT") ?: "8090")
}

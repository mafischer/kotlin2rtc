plugins {
    `java-test-fixtures`
}

description = "kotlin2rtc core: HTTP transport for cameras on the local network, HTTP Digest, SOAP XML, RTSP URLs, logging and clock hooks"

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
}

// The shared test helpers and camera fixtures (src/testFixtures) serve the modules' tests only: never published
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
// The sources variant is added by the publishing plugin later in the configuration
afterEvaluate {
    configurations.findByName("testFixturesSourcesElements")?.let { javaComponent.withVariantsFromConfiguration(it) { skip() } }
}

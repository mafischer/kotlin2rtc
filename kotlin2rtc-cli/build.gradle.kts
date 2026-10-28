plugins {
    application
}

description = "kotlin2rtc command line sample: discover, probe, talk"

dependencies {
    implementation(project(":kotlin2rtc-onvif"))
    implementation(project(":kotlin2rtc-rtsp"))
    testImplementation(testFixtures(project(":kotlin2rtc-core")))
    testImplementation(libs.okhttp.mockwebserver)
}

application {
    mainClass.set("io.github.mafischer.kotlin2rtc.cli.MainKt")
    applicationName = "kotlin2rtc"
}

description = "kotlin2rtc Tapo: TP-Link Tapo private-protocol backchannel (planned, no code yet; see README.md)"

dependencies {
    api(project(":kotlin2rtc-core"))
    api(project(":kotlin2rtc-codec"))
}

dependencies {
    testImplementation(testFixtures(project(":kotlin2rtc-core")))
}

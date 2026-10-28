description = "kotlin2rtc ONVIF: WS-Discovery, the ONVIF device/media/PTZ client, speaker level and the camera probe"

dependencies {
    api(project(":kotlin2rtc-core"))
}

dependencies {
    testImplementation(testFixtures(project(":kotlin2rtc-core")))
}

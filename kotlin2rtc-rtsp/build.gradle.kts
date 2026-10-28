description = "kotlin2rtc RTSP: the ONVIF Profile T audio backchannel client (sending audio to a camera's speaker)"

dependencies {
    api(project(":kotlin2rtc-core"))
    api(project(":kotlin2rtc-codec"))
}

dependencies {
    testImplementation(testFixtures(project(":kotlin2rtc-core")))
}

description = "kotlin2rtc codecs: G.711, G.726, AAC framing, L16, RTP/RTCP packetization and backchannel audio encoding"

dependencies {
    api(project(":kotlin2rtc-core"))
}

dependencies {
    testImplementation(testFixtures(project(":kotlin2rtc-core")))
}

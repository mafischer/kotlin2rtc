package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.testing.Fixtures

/** The camera fixtures ([Fixtures]) and helpers to build and read SOAP in tests */
object OnvifFixtures {
    fun read(name: String): String = Fixtures.read(name)

    /** A SOAP envelope the way many cameras write it (SOAP-ENV prefix) */
    fun envelope(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:ter="http://www.onvif.org/ver10/error">
        <SOAP-ENV:Body>$body</SOAP-ENV:Body></SOAP-ENV:Envelope>"""

    fun soap(status: Int = 200, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(status, mapOf("Content-Type" to OnvifSoap.CONTENT_TYPE) + headers, body.encodeToByteArray())

    /** The first element named [name] in a request's envelope */
    fun HttpRequest.element(name: String): String? = body?.decodeToString()?.let { SoapXml.parse(it).descendant(name)?.text }

    /** The name of the request's operation (the Body's first element) */
    val HttpRequest.operation: String? get() = body?.decodeToString()?.let { SoapXml.parse(it).descendant("Body")?.children?.firstOrNull()?.name }
}

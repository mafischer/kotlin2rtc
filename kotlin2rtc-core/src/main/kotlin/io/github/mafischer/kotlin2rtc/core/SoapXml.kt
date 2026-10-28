package io.github.mafischer.kotlin2rtc.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * The answers of cameras, read by local name: every vendor picks its own prefixes (`s:`, `SOAP-ENV:`, `env:`, none), so
 * elements are matched without the namespace prefix. Documents
 * with a DOCTYPE are refused: no entities, no external resources.
 */
public object SoapXml {
    private val doctype = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)

    /** Parses [text]; throws [XmlParseException] when it is not well-formed XML */
    public fun parse(text: String): XmlElement {
        val source = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (source.isEmpty()) throw XmlParseException("empty")
        if (doctype.containsMatchIn(source)) throw XmlParseException("doctype")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // The JDK's parser knows this feature; Android's refuses unknown features and has no DTD support
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return try {
            XmlElement(factory.newDocumentBuilder().parse(InputSource(StringReader(source))).documentElement)
        } catch (e: SAXException) {
            throw XmlParseException("xml")
        } catch (e: IOException) {
            throw XmlParseException("xml")
        } catch (e: ParserConfigurationException) {
            throw XmlParseException("parser")
        }
    }

    /** Escapes a value for an element's text or an attribute (user names and profile tokens go into the envelope) */
    public fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}

/** Not well-formed XML, or a document this parser refuses (a DOCTYPE); [detail] says which */
public class XmlParseException(public val detail: String) : Exception("invalid xml: $detail")

/** An element of a parsed answer, looked up by local name */
public class XmlElement internal constructor(private val element: Element) {
    /** The name without its prefix */
    public val name: String get() = element.localName ?: element.tagName.substringAfter(':')

    /** The text content, trimmed */
    public val text: String get() = element.textContent.orEmpty().trim()

    public val children: List<XmlElement>
        get() {
            val nodes = element.childNodes
            return (0 until nodes.length).map(nodes::item).filter { it.nodeType == Node.ELEMENT_NODE }.map { XmlElement(it as Element) }
        }

    /** The attribute [name], whatever its prefix */
    public fun attribute(name: String): String? {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val local = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (local == name) return attribute.nodeValue
        }
        return null
    }

    public fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }

    public fun children(name: String): List<XmlElement> = children.filter { it.name == name }

    /** The first element named [name] below this one, depth first */
    public fun descendant(name: String): XmlElement? = descendants(name).firstOrNull()

    /** Every element named [name] below this one, in document order (not below a match) */
    public fun descendants(name: String): List<XmlElement> = children.flatMap { if (it.name == name) listOf(it) else it.descendants(name) }

    /** The element at the path of child names, e.g. `path("Resolution", "Width")` */
    public fun path(vararg names: String): XmlElement? = names.fold(this as XmlElement?) { current, name -> current?.child(name) }
}

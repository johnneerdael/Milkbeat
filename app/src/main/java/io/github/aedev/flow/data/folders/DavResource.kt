package io.github.aedev.flow.data.folders

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class DavResource(
    val href: String,
    val isCollection: Boolean,
    val length: Long?,
    val modified: Long,
    val contentType: String?,
)

private const val DAV_NAMESPACE = "DAV:"

private class DavProps {
    var collection = false
    var length: Long? = null
    var modified = 0L
    var contentType: String? = null

    fun merge(other: DavProps) {
        collection = collection || other.collection
        length = length ?: other.length
        modified = modified.takeIf { it != 0L } ?: other.modified
        contentType = contentType ?: other.contentType
    }
}

internal fun parseMultiStatus(input: InputStream): List<DavResource> {
    val parser = Xml.newPullParser()
    try {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        parser.nextTag()
        if (!parser.isDav("multistatus")) throw IOException("Not a multistatus response")
        return buildList {
            while (parser.nextTag() != XmlPullParser.END_TAG) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (parser.isDav("response")) {
                    readResponse(parser)?.let(::add)
                } else {
                    skipElement(parser)
                }
            }
        }
    } catch (error: XmlPullParserException) {
        throw IOException("Malformed multistatus response", error)
    }
}

private fun XmlPullParser.isDav(name: String): Boolean = namespace == DAV_NAMESPACE && this.name == name

private fun readResponse(parser: XmlPullParser): DavResource? {
    var href: String? = null
    var responseStatusOk = true
    val props = DavProps()
    var anySuccess = false
    while (parser.nextTag() != XmlPullParser.END_TAG) {
        when {
            parser.isDav("href") -> {
                val text = parser.nextText().trim()
                if (href == null) href = text
            }

            parser.isDav("status") -> {
                responseStatusOk = isSuccessStatus(parser.nextText())
            }

            parser.isDav("propstat") -> {
                val propstat = readPropstat(parser)
                if (propstat != null) {
                    anySuccess = true
                    props.merge(propstat)
                }
            }

            else -> {
                skipElement(parser)
            }
        }
    }
    if (!anySuccess || !responseStatusOk || href.isNullOrEmpty()) return null
    return DavResource(href, props.collection, props.length, props.modified, props.contentType)
}

private fun readPropstat(parser: XmlPullParser): DavProps? {
    val props = DavProps()
    var statusOk = true
    while (parser.nextTag() != XmlPullParser.END_TAG) {
        when {
            parser.isDav("status") -> {
                statusOk = isSuccessStatus(parser.nextText())
            }

            parser.isDav("prop") -> {
                readProp(parser, props)
            }

            else -> {
                skipElement(parser)
            }
        }
    }
    return props.takeIf { statusOk }
}

private fun readProp(
    parser: XmlPullParser,
    props: DavProps,
) {
    while (parser.nextTag() != XmlPullParser.END_TAG) {
        when {
            parser.isDav("resourcetype") -> {
                while (parser.nextTag() != XmlPullParser.END_TAG) {
                    if (parser.isDav("collection")) props.collection = true
                    skipElement(parser)
                }
            }

            parser.isDav("getcontentlength") -> {
                props.length =
                    parser
                        .nextText()
                        .trim()
                        .toLongOrNull()
                        ?.takeIf { it >= 0 }
            }

            parser.isDav("getlastmodified") -> {
                props.modified = parseHttpDate(parser.nextText())
            }

            parser.isDav("getcontenttype") -> {
                props.contentType =
                    parser
                        .nextText()
                        .trim()
                        .substringBefore(';')
                        .lowercase()
                        .ifEmpty { null }
            }

            else -> {
                skipElement(parser)
            }
        }
    }
}

private fun parseHttpDate(text: String): Long =
    runCatching { ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrDefault(0L)

private fun isSuccessStatus(line: String): Boolean =
    line
        .trim()
        .split(' ')
        .getOrNull(1)
        ?.startsWith("2") == true

private fun skipElement(parser: XmlPullParser) {
    var depth = 1
    while (depth > 0) {
        when (parser.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
        }
    }
}

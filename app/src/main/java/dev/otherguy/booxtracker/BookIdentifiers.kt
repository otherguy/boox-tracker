package dev.otherguy.booxtracker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

data class BookIdentifiers(val isbns: Set<String>, val title: String?, val author: String?, val tags: Map<String, Set<String>> = emptyMap()) {
    fun json(): JSONObject = JSONObject().put("isbns", org.json.JSONArray(isbns.sorted())).put("title", title ?: JSONObject.NULL)
        .put("author", author ?: JSONObject.NULL).put("tags", JSONObject().apply { tags.forEach { (key, values) -> put(key, org.json.JSONArray(values.sorted())) } })
    companion object {
        fun fromJson(value: JSONObject): BookIdentifiers {
            fun values(array: org.json.JSONArray?) = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
            val tags = value.optJSONObject("tags") ?: JSONObject()
            return BookIdentifiers(values(value.optJSONArray("isbns")), value.optString("title").takeUnless { it.isBlank() || it == "null" }, value.optString("author").takeUnless { it.isBlank() || it == "null" }, tags.keys().asSequence().associateWith { values(tags.optJSONArray(it)) })
        }
    }
}

fun identifierTags(raw: String, scheme: String? = null): Map<String, Set<String>> {
    val result = mutableMapOf<String, MutableSet<String>>()
    raw.split(',').forEach { part ->
        val text = part.trim().removePrefix("urn:")
        val url = runCatching { java.net.URI(text) }.getOrNull()
        val pair = if (url?.host == "hardcover.app" && url.scheme == "https") {
            val path = url.path.trim('/').split('/')
            when {
                path.size == 4 && path[0] == "books" && path[2] == "editions" -> "hardcover-edition" to path[3]
                path.size == 2 && path[0] == "books" -> "hardcover-slug" to path[1]
                else -> return@forEach
            }
        } else if (!scheme.isNullOrBlank()) {
            scheme.lowercase() to text
        } else if (text.contains(':')) {
            text.substringBefore(':').lowercase() to text.substringAfter(':')
        } else {
            "isbn" to text
        }
        var (key, value) = pair
        key = when (key) {
            "isbn10", "isbn13", "isbn-10", "isbn-13" -> "isbn"
            "amazon", "mobi-asin" -> "asin"
            "hardcover" -> if (value.matches(Regex("[0-9]+"))) "hardcover-id" else "hardcover-slug"
            else -> key
        }
        value = when (key) {
            "isbn" -> isbn13(value) ?: return@forEach
            "asin" -> value.uppercase().takeIf { it.matches(Regex("[A-Z0-9]{10}")) } ?: return@forEach
            "goodreads", "hardcover-id", "hardcover-edition" -> value.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true } ?: return@forEach
            "hardcover-slug" -> value.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,299}")) } ?: return@forEach
            "storygraph", "fable", "margins" -> value.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,299}")) } ?: return@forEach
            else -> return@forEach
        }
        result.getOrPut(key) { mutableSetOf() }.add(value)
    }
    return result
}

private fun mergeTags(a: Map<String, Set<String>>, b: Map<String, Set<String>>) = (a.keys + b.keys).associateWith { a[it].orEmpty() + b[it].orEmpty() }

class SyncProblem(val code: String) : Exception(code)

/** A UUID in its canonical 8-4-4-4-12 hex form, the identifier shape of Fable and StoryGraph book records. */
internal val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

fun isbn13(value: String): String? {
    val compact = value.trim().replace(Regex("(?i)^(?:urn:)?isbn[: ]*"), "").replace(Regex("[ -]"), "").uppercase()
    if (compact.matches(Regex("[0-9]{13}")) && (compact.startsWith("978") || compact.startsWith("979"))) {
        val sum = compact.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum()
        return compact.takeIf { sum % 10 == 0 }
    }
    if (!compact.matches(Regex("[0-9]{9}[0-9X]"))) return null
    val sum = compact.mapIndexed { i, c -> (if (c == 'X') 10 else c.digitToInt()) * (10 - i) }.sum()
    if (sum % 11 != 0) return null
    val prefix = "978" + compact.take(9)
    val checksum = (10 - prefix.mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 1 else 3 }.sum() % 10) % 10
    return prefix + checksum
}

fun isbn10(isbn: String): String? {
    if (isbn13(isbn) != isbn || !isbn.startsWith("978")) return null
    val prefix = isbn.substring(3, 12)
    val checksum = (11 - prefix.mapIndexed { i, c -> c.digitToInt() * (10 - i) }.sum() % 11) % 11
    return prefix + if (checksum == 10) "X" else checksum.toString()
}

private fun xmlValues(bytes: ByteArray, read: (XmlPullParser) -> Unit) {
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(bytes.inputStream(), null)
    }
    while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.DOCDECL) throw SyncProblem("epub_doctype_not_supported")
        read(parser)
    }
}

fun readEpubIdentifiers(file: File): BookIdentifiers = ZipFile(file).use { zip ->
    fun metadata(path: String, limit: Int): ByteArray {
        if (path.startsWith('/') || path.split('/').any { it == ".." } || path.contains('\\') || path.contains(':')) throw SyncProblem("epub_unsafe_metadata_path")
        val entry = zip.getEntry(path) ?: throw SyncProblem("epub_metadata_missing")
        if (entry.size > limit) throw SyncProblem("epub_metadata_too_large")
        return zip.getInputStream(entry).use { stream ->
            readLimited(stream, limit)
        }
    }
    val rootfiles = mutableListOf<String>()
    xmlValues(metadata("META-INF/container.xml", 64 * 1024)) { parser ->
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile" && parser.getAttributeValue(null, "media-type") == "application/oebps-package+xml") {
            parser.getAttributeValue(null, "full-path")?.let(rootfiles::add)
        }
    }
    val opf = rootfiles.singleOrNull() ?: throw SyncProblem("epub_ambiguous_package")
    var tags = emptyMap<String, Set<String>>()
    var title: String? = null
    var author: String? = null
    var metadataDepth: Int? = null
    var field: String? = null
    var fieldDepth = 0
    var scheme: String? = null
    val text = StringBuilder()
    xmlValues(metadata(opf, 2 * 1024 * 1024)) { parser ->
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "metadata") metadataDepth = parser.depth
                if (metadataDepth != null && parser.namespace == "http://purl.org/dc/elements/1.1/" && parser.name in setOf("identifier", "title", "creator")) {
                    field = parser.name
                    fieldDepth = parser.depth
                    text.clear()
                    scheme = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == "scheme" }?.let { parser.getAttributeValue(it) }
                }
            }

            XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (field != null) text.append(parser.text)

            XmlPullParser.END_TAG -> {
                if (field != null && parser.depth == fieldDepth) {
                    val value = text.toString().trim()
                    when (field) {
                        "identifier" -> tags = mergeTags(tags, identifierTags(value, scheme))
                        "title" -> if (title == null) title = value.take(300)
                        "creator" -> if (author == null) author = value.take(300)
                    }
                    field = null
                }
                if (metadataDepth == parser.depth && parser.name == "metadata") metadataDepth = null
            }
        }
    }
    BookIdentifiers(tags["isbn"].orEmpty(), title, author, tags)
}

fun readLimited(stream: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) throw SyncProblem("response_too_large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class BookIdentifierRepository(private val context: Context, private val store: DiagnosticsStore) {
    fun read(book: JSONObject): BookIdentifiers {
        val tags = identifierTags(book.raw("ISBN") ?: "")
        val database = BookIdentifiers(tags["isbn"].orEmpty(), book.raw("title"), book.raw("authors"), tags)
        val tree = store.get("ebook.tree")?.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return database
        val filename = book.raw("filename")?.takeIf { it.endsWith(".epub", true) } ?: return database
        val document = try {
            find(tree, filename)
        } catch (error: SyncProblem) {
            if (error.code == "epub_not_in_folder") return database else throw error
        }
        val cacheKey = "ebook.identity.2.${digest(tree.toString() + document.id + document.modified + document.size)}"
        fun combined(value: BookIdentifiers): BookIdentifiers {
            val merged = mergeTags(database.tags, value.tags)
            return BookIdentifiers(database.isbns + value.isbns, database.title?.takeIf { it.isNotBlank() } ?: value.title, database.author?.takeIf { it.isNotBlank() } ?: value.author, merged)
        }
        store.get(cacheKey)?.let { return combined(BookIdentifiers.fromJson(JSONObject(it))) }
        val temp = File.createTempFile("metadata-", ".epub", context.cacheDir)
        try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > 128L * 1024 * 1024) throw SyncProblem("epub_too_large")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw SyncProblem("epub_unreadable")
            val identity = readEpubIdentifiers(temp)

            if (document.modified > 0 && document.size > 0) {
                store.put(cacheKey, identity.json().toString())
            }
            return combined(identity)
        } catch (_: SyncProblem) {
            return database
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            return database
        } catch (_: java.util.zip.ZipException) {
            return database
        } finally {
            temp.delete()
        }
    }

    private data class Document(val id: String, val modified: Long, val size: Long)

    private fun find(tree: Uri, filename: String): Document {
        val directories = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val visited = mutableSetOf<String>()
        val matches = mutableListOf<Document>()
        var count = 0
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            if (!visited.add(directory)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
            context.contentResolver.query(children, arrayOf("document_id", "_display_name", "mime_type", "last_modified", "_size"), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (++count > 10_000) throw SyncProblem("epub_folder_too_large")
                    val id = cursor.getString(0)
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        directories.add(id)
                    } else if (cursor.getString(1) == filename) {
                        matches.add(Document(id, cursor.getLong(3), cursor.getLong(4)))
                    }
                }
            } ?: throw SyncProblem("epub_folder_unreadable")
        }
        if (matches.isEmpty()) throw SyncProblem("epub_not_in_folder")
        return matches.singleOrNull() ?: throw SyncProblem("epub_filename_ambiguous")
    }
}

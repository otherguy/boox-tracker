package dev.otherguy.booxtracker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

fun requireEbookFolder(context: Context, store: DiagnosticsStore): Uri {
    val uri = store.get("ebook.tree")?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: throw SyncProblem("ebook_folder_required")
    if (!context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) throw SyncProblem("ebook_permission_denied")
    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
        if (!it.moveToFirst() || it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) throw SyncProblem("ebook_folder_unreadable")
    } ?: throw SyncProblem("ebook_folder_unreadable")
    return uri
}

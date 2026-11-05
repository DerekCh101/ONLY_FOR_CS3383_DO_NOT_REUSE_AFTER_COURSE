package com.opwait.ashoreprospect.domain

import java.util.UUID

data class TypewriterImageRef(
    val id: String,
    val name: String = "image.png",
)

data class TypewriterDiaryEntry(
    val date: String,
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var body: String = "",
    val images: MutableList<TypewriterImageRef> = mutableListOf(),
) {
    fun hasContent(): Boolean =
        title.isNotBlank() || body.isNotBlank() || images.isNotEmpty()
}

data class TypewriterListItem(
    var text: String = "",
    var done: Boolean = false,
)

data class TypewriterList(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "List",
    val items: MutableList<TypewriterListItem> = mutableListOf(),
)

data class TypewriterPage(
    val id: String = UUID.randomUUID().toString(),
    var parentId: String? = null,
    var kind: String = "note",
    var title: String = "Note",
    var body: String = "",
    val images: MutableList<TypewriterImageRef> = mutableListOf(),
    var pos: Int = 0,
)

data class TypewriterBundle(
    val diary: MutableList<TypewriterDiaryEntry> = mutableListOf(),
    val lists: MutableList<TypewriterList> = mutableListOf(),
    val pages: MutableList<TypewriterPage> = mutableListOf(),
    var lastUpdatedAt: String? = null,
) {
    fun pruneEmptyDiary() {
        diary.removeAll { !it.hasContent() }
    }

    fun hasSubstance(): Boolean =
        diary.any { it.hasContent() } ||
            lists.any { it.items.any { item -> item.text.isNotBlank() } || it.title.isNotBlank() } ||
            pages.any { it.body.isNotBlank() || it.images.isNotEmpty() || it.title.isNotBlank() }

    fun diaryAt(date: String): TypewriterDiaryEntry? = diary.firstOrNull { it.date == date }

    fun diaryFor(date: String): TypewriterDiaryEntry =
        diaryAt(date) ?: TypewriterDiaryEntry(date = date).also { diary.add(it) }
}

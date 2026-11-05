package com.opwait.ashoreprospect.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.opwait.ashoreprospect.data.local.BlockedEntity
import com.opwait.ashoreprospect.data.local.TaskEntity
import com.opwait.ashoreprospect.data.remote.BlockedDto
import com.opwait.ashoreprospect.data.remote.TaskDto
import com.opwait.ashoreprospect.domain.BlockedTime
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.VaultCrypto
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.TaskLifeCycle
import com.opwait.ashoreprospect.domain.TaskStateKind
import com.opwait.ashoreprospect.domain.TypewriterBundle
import com.opwait.ashoreprospect.domain.TypewriterDiaryEntry
import com.opwait.ashoreprospect.domain.TypewriterImageRef
import com.opwait.ashoreprospect.domain.TypewriterList
import com.opwait.ashoreprospect.domain.TypewriterListItem
import com.opwait.ashoreprospect.domain.TypewriterHtml
import com.opwait.ashoreprospect.domain.TypewriterPage
import com.opwait.ashoreprospect.domain.Workflow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    durationMinutes = duration * 60,
    state = TaskStateKind.fromApi(state),
    startingDate = parseDate(startingDate),
    startingTime = parseTime(startingTime),
    deadlineDate = parseDate(deadlineDate),
    deadlineTime = parseTime(deadlineTime),
    placedDate = parseDate(placedDate),
    placedTime = parseTime(placedTime),
    lifeCycle = runCatching { TaskLifeCycle.valueOf(lifecycle) }.getOrDefault(TaskLifeCycle.UNSCHEDULED),
    workflow = runCatching { Workflow.valueOf(workflow) }.getOrDefault(Workflow.UNFINISHED),
    placementUpdatedAt = parseInstant(placementUpdatedAt),
    lastUpdatedAt = parseInstant(lastUpdatedAt),
    description = description,
    category = category,
    subcategory = subcategory,
    descriptionDone = descriptionDone,
    parentId = parentId,
)

fun Task.toEntity(userId: String?): TaskEntity = TaskEntity(
    id = id,
    title = title,
    duration = durationHours,
    state = state.apiName,
    placedDate = placedDate?.toString(),
    placedTime = placedTime?.toHhMmSs(),
    deadlineDate = deadlineDate?.toString(),
    deadlineTime = deadlineTime?.toHhMmSs(),
    startingDate = startingDate?.toString(),
    startingTime = startingTime?.toHhMmSs(),
    lifecycle = lifeCycle.name,
    workflow = workflow.name,
    placementUpdatedAt = placementUpdatedAt?.toString(),
    lastUpdatedAt = lastUpdatedAt?.toString(),
    userId = userId,
    description = description,
    category = category,
    subcategory = subcategory,
    descriptionDone = descriptionDone,
    parentId = parentId,
)

fun TaskDto.toDomain(): Task? {
    val tid = id?.trim().orEmpty()
    if (tid.isEmpty()) return null
    return Task(
        id = tid,
        title = title.orEmpty(),
        durationMinutes = (duration ?: 1.0) * 60,
        state = TaskStateKind.fromApi(state),
        startingDate = parseDate(startingDate),
        startingTime = parseTime(startingTime),
        deadlineDate = parseDate(deadlineDate),
        deadlineTime = parseTime(deadlineTime),
        placedDate = parseDate(placedDate),
        placedTime = parseTime(placedTime),
        lifeCycle = runCatching { TaskLifeCycle.valueOf(lifecycle!!.uppercase()) }
            .getOrDefault(TaskLifeCycle.UNSCHEDULED),
        workflow = runCatching { Workflow.valueOf(workflow!!.uppercase()) }
            .getOrDefault(Workflow.UNFINISHED),
        placementUpdatedAt = parseInstant(placementUpdatedAt),
        lastUpdatedAt = parseInstant(lastUpdatedAt),
        description = description.orEmpty(),
        category = category.orEmpty(),
        subcategory = subcategory.orEmpty(),
        descriptionDone = descriptionDone == true,
        parentId = parentId.orEmpty().trim(),
    )
}

fun Task.toDto(): TaskDto = TaskDto(
    id = id,
    title = title,
    duration = durationHours,
    state = state.apiName,
    placedDate = placedDate?.toString(),
    placedTime = placedTime?.toHhMmSs(),
    deadlineDate = deadlineDate?.toString(),
    deadlineTime = deadlineTime?.toHhMmSs(),
    startingDate = startingDate?.toString(),
    startingTime = startingTime?.toHhMmSs(),
    lifecycle = lifeCycle.name,
    workflow = workflow.name,
    placementUpdatedAt = placementUpdatedAt?.toString(),
    lastUpdatedAt = lastUpdatedAt?.toString(),
    category = category,
    subcategory = subcategory,
    description = description,
    descriptionDone = descriptionDone,
    parentId = parentId,
)

fun BlockedEntity.toDomain(): BlockedTime = BlockedTime(
    id = id,
    title = title,
    weekday = weekday,
    specificDate = parseDate(specificDate),
    startTime = parseTime(startTime) ?: LocalTime.MIDNIGHT,
    endTime = parseTime(endTime) ?: LocalTime.MIDNIGHT,
    lastUpdatedAt = parseInstant(lastUpdatedAt),
    isException = isException,
)

fun BlockedTime.toEntity(userId: String?): BlockedEntity = BlockedEntity(
    id = id,
    title = title,
    weekday = weekday,
    specificDate = specificDate?.toString(),
    startTime = startTime.toHhMmSs(),
    endTime = endTime.toHhMmSs(),
    lastUpdatedAt = lastUpdatedAt?.toString(),
    isException = isException,
    userId = userId,
)

fun BlockedDto.toDomain(): BlockedTime? {
    val bid = id?.trim().orEmpty()
    val start = parseTime(startTime) ?: return null
    val end = parseTime(endTime) ?: return null
    if (bid.isEmpty()) return null
    return BlockedTime(
        id = bid,
        title = title ?: "blocked",
        weekday = weekday,
        specificDate = parseDate(specificDate),
        startTime = start,
        endTime = end,
        lastUpdatedAt = parseInstant(lastUpdatedAt),
        isException = isException == true,
    )
}

fun BlockedTime.toDto(): BlockedDto = BlockedDto(
    id = id,
    title = title,
    weekday = weekday,
    specificDate = specificDate?.toString(),
    startTime = startTime.toHhMmSs(),
    endTime = endTime.toHhMmSs(),
    lastUpdatedAt = lastUpdatedAt?.toString(),
    isException = isException,
)

private val gson = Gson()

fun TypewriterBundle.toJson(): String {
    pruneEmptyDiary()
    val root = JsonObject()
    val diaryArr = JsonArray()
    for (d in diary) {
        diaryArr.add(
            JsonObject().apply {
                addProperty("date", d.date)
                addProperty("id", d.id)
                addProperty("title", d.title)
                addProperty("body", TypewriterHtml.storedHtmlOrSealed(d.body))
                add("images", imagesArray(d.images))
            },
        )
    }
    val listsArr = JsonArray()
    for (l in lists) {
        val items = JsonArray()
        for (it in l.items) {
            items.add(
                JsonObject().apply {
                    addProperty("text", TypewriterHtml.storedHtmlOrSealed(it.text))
                    addProperty("done", it.done)
                },
            )
        }
        listsArr.add(
            JsonObject().apply {
                addProperty("id", l.id)
                addProperty("title", l.title)
                add("items", items)
            },
        )
    }
    val pagesArr = JsonArray()
    for (p in pages) {
        pagesArr.add(
            JsonObject().apply {
                addProperty("id", p.id)
                addProperty("parent_id", p.parentId)
                addProperty("kind", p.kind)
                addProperty("title", p.title)
                addProperty("body", TypewriterHtml.storedHtmlOrSealed(p.body))
                addProperty("pos", p.pos)
                add("images", imagesArray(p.images))
            },
        )
    }
    root.add("diary", diaryArr)
    root.add("lists", listsArr)
    root.add("pages", pagesArr)
    return gson.toJson(root)
}

fun looksLikeTypewriterJson(content: String?): Boolean {
    val raw = content?.trim().orEmpty()
    if (raw.isEmpty()) return true
    if (VaultSeal.isSealed(raw)) return true
    if (!raw.startsWith("{")) return false
    val root = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull() ?: return false
    return root.has("diary") || root.has("lists") || root.has("pages")
}

/** Null when the blob is HTML or other non-Typewriter text — never treat that as an empty notebook. */
fun parseTypewriterMaybeSealed(content: String?, lastUpdatedAt: String?): TypewriterBundle? {
    if (content.isNullOrBlank()) return TypewriterBundle(lastUpdatedAt = lastUpdatedAt)
    if (VaultSeal.isSealed(content)) {
        val opened = VaultCrypto.open(content)
        if (VaultSeal.isSealed(opened)) return null
        if (!looksLikeTypewriterJson(opened)) return null
        return parseTypewriterBundle(opened, lastUpdatedAt).also {
            VaultCrypto.openBundle(it)
            TypewriterHtml.normalizeBundle(it)
        }
    }
    if (!looksLikeTypewriterJson(content)) return null
    return parseTypewriterBundle(content, lastUpdatedAt).also {
        VaultCrypto.openBundle(it)
        TypewriterHtml.normalizeBundle(it)
    }
}

fun parseTypewriterBundle(content: String?, lastUpdatedAt: String?): TypewriterBundle {
    val bundle = TypewriterBundle(lastUpdatedAt = lastUpdatedAt)
    if (content.isNullOrBlank()) return bundle
    val root = runCatching { gson.fromJson(content, JsonObject::class.java) }.getOrNull() ?: return bundle
    root.getAsJsonArray("diary")?.forEach { el ->
        val o = el.asJsonObject
        bundle.diary.add(
            TypewriterDiaryEntry(
                date = o.str("date") ?: return@forEach,
                id = o.str("id") ?: java.util.UUID.randomUUID().toString(),
                title = o.str("title").orEmpty(),
                body = TypewriterHtml.storedHtmlOrSealed(o.str("body").orEmpty()),
                images = o.images(),
            ),
        )
    }
    root.getAsJsonArray("lists")?.forEach { el ->
        val o = el.asJsonObject
        val items = mutableListOf<TypewriterListItem>()
        o.getAsJsonArray("items")?.forEach { itemEl ->
            val it = itemEl.asJsonObject
            items.add(
                TypewriterListItem(
                    text = TypewriterHtml.storedHtmlOrSealed(it.str("text").orEmpty()),
                    done = it.bool("done"),
                ),
            )
        }
        bundle.lists.add(
            TypewriterList(
                id = o.str("id") ?: java.util.UUID.randomUUID().toString(),
                title = o.str("title") ?: "List",
                items = items,
            ),
        )
    }
    root.getAsJsonArray("pages")?.forEach { el ->
        val o = el.asJsonObject
        bundle.pages.add(
            TypewriterPage(
                id = o.str("id") ?: java.util.UUID.randomUUID().toString(),
                parentId = o.str("parent_id"),
                kind = o.str("kind") ?: "note",
                title = o.str("title") ?: "Note",
                body = TypewriterHtml.storedHtmlOrSealed(o.str("body").orEmpty()),
                images = o.images(),
                pos = o.get("pos")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            ),
        )
    }
    return bundle
}

private fun imagesArray(images: List<TypewriterImageRef>): JsonArray {
    val arr = JsonArray()
    for (img in images) {
        arr.add(
            JsonObject().apply {
                addProperty("id", img.id)
                addProperty("name", img.name)
            },
        )
    }
    return arr
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { !it.isJsonNull }?.asBoolean == true

private fun JsonObject.images(): MutableList<TypewriterImageRef> {
    val out = mutableListOf<TypewriterImageRef>()
    getAsJsonArray("images")?.forEach { el ->
        val o = el.asJsonObject
        val id = o.str("id") ?: o.str("sha256") ?: return@forEach
        out.add(TypewriterImageRef(id = id, name = o.str("name") ?: "image.png"))
    }
    return out
}

private fun parseTime(raw: String?): LocalTime? {
    if (raw.isNullOrBlank()) return null
    var t = raw.trim()
    if (t.length >= 8) t = t.substring(0, 8)
    else if (t.length == 5) t = "$t:00"
    return runCatching { LocalTime.parse(t) }.getOrNull()
}

private fun parseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    return runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
}

private fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw) }.getOrNull()
}

private fun LocalTime.toHhMmSs(): String = "%02d:%02d:%02d".format(hour, minute, second)

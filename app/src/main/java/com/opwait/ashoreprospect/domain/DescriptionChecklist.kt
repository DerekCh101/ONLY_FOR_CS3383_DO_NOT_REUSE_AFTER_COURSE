package com.opwait.ashoreprospect.domain

/** Node `description` stores checklist rows as `[x]` / `[ ]` lines. Do not invent other markup. */
object DescriptionChecklist {
    data class Line(var done: Boolean, var text: String)

    fun parse(raw: String?): MutableList<Line> {
        val s = raw.orEmpty()
        if (s.isBlank()) return mutableListOf(Line(false, ""))
        val out = s.lines().map { line ->
            val t = line.trimEnd()
            when {
                t.startsWith("[x]", ignoreCase = true) -> Line(true, t.substring(3).trimStart())
                t.startsWith("[ ]") -> Line(false, t.substring(3).trimStart())
                t.isBlank() -> Line(false, "")
                else -> Line(false, t)
            }
        }.toMutableList()
        if (out.isEmpty()) out.add(Line(false, ""))
        return out
    }

    fun encode(lines: List<Line>): String =
        lines
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() || it.done }
            .joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }

    fun allDone(lines: List<Line>): Boolean {
        val body = lines.filter { it.text.isNotBlank() }
        return body.isNotEmpty() && body.all { it.done }
    }
}

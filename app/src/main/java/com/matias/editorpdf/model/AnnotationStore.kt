package com.matias.editorpdf.model

/** Anotaciones del documento abierto, con deshacer/rehacer. Se usa solo desde el hilo principal. */
class AnnotationStore {

    class Change(val removed: List<Annotation>, val added: List<Annotation>)

    private val pages = HashMap<Int, MutableList<Annotation>>()
    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    var version = 0L
        private set
    private var savedVersion = 0L

    val isDirty: Boolean get() = version != savedVersion
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    var onChanged: (() -> Unit)? = null

    fun forPage(page: Int): List<Annotation> = pages[page] ?: emptyList()

    fun all(): List<Annotation> = pages.keys.sorted().flatMap { pages[it].orEmpty() }

    fun load(list: List<Annotation>, dirty: Boolean) {
        pages.clear()
        undoStack.clear()
        redoStack.clear()
        list.forEach { insert(it) }
        version++
        savedVersion = if (dirty) -1 else version
        onChanged?.invoke()
    }

    fun markSaved(atVersion: Long) {
        savedVersion = atVersion
        onChanged?.invoke()
    }

    fun commit(change: Change) {
        if (change.removed.isEmpty() && change.added.isEmpty()) return
        applyRaw(change.removed, change.added)
        push(change)
    }

    /** Aplica sin registrar en el historial (el borrador lo agrupa y luego llama a [record]). */
    fun applySilently(removed: List<Annotation>, added: List<Annotation>) {
        applyRaw(removed, added)
        onChanged?.invoke()
    }

    fun record(change: Change) = push(change)

    /**
     * Reemplaza [old] (recién agregada) por [replacement] sin crear un paso nuevo de deshacer:
     * se usa cuando el resaltado se ajusta al texto un instante después de dibujarlo.
     */
    fun amendLast(old: Annotation, replacement: List<Annotation>) {
        val top = undoStack.lastOrNull()
        if (top == null || top.added.none { it.id == old.id }) return
        applyRaw(listOf(old), replacement)
        undoStack[undoStack.size - 1] = Change(top.removed, top.added.filter { it.id != old.id } + replacement)
        onChanged?.invoke()
    }

    fun find(id: String): Annotation? = pages.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }

    fun undo(): Boolean {
        val c = undoStack.removeLastOrNull() ?: return false
        applyRaw(c.added, c.removed)
        redoStack.addLast(c)
        onChanged?.invoke()
        return true
    }

    fun redo(): Boolean {
        val c = redoStack.removeLastOrNull() ?: return false
        applyRaw(c.removed, c.added)
        undoStack.addLast(c)
        onChanged?.invoke()
        return true
    }

    private fun push(change: Change) {
        undoStack.addLast(change)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        onChanged?.invoke()
    }

    private fun applyRaw(removed: List<Annotation>, added: List<Annotation>) {
        for (a in removed) pages[a.page]?.removeAll { it.id == a.id }
        for (a in added) insert(a)
        version++
    }

    private fun insert(a: Annotation) {
        val list = pages.getOrPut(a.page) { ArrayList() }
        list.removeAll { it.id == a.id }
        list.add(a)
    }

    companion object {
        private const val MAX_HISTORY = 300
    }
}

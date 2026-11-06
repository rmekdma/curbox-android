package neth.iecal.todo

/** Maps an observed foreground package to its learning subject. */
class SubjectPackages(
    private val korean: String = "com.enuma.todohangeul",
    private val math: String = "com.enuma.todomath",
    private val english: String = "com.enuma.todoenglish"
) {
    fun packageFor(subject: Subject): String = when (subject) {
        Subject.KOREAN -> korean
        Subject.MATH -> math
        Subject.ENGLISH -> english
    }

    fun subjectFor(packageName: String?): Subject? = Subject.entries.firstOrNull { packageFor(it) == packageName }
}

package fulguris.search

/**
 * The suggestion choices.
 */
enum class Suggestions(val index: Int) {
    NONE(0),
    GOOGLE(1),
    DUCK(2);

    companion object {
        fun from(value: Int): Suggestions {
            return when (value) {
                0 -> NONE
                1 -> GOOGLE
                2 -> DUCK
                else -> DUCK
            }
        }
    }
}

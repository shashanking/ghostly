package com.shashank.ghostly

/**
 * Which little fellow this is. The body, motion and behaviour are shared — only the silhouette
 * (ears, whiskers) changes between them, drawn by [GhostView].
 */
enum class Species(val id: String, val label: String) {
    GHOST("ghost", "Ghost"),
    CAT("cat", "Cat ghost"),
    DOG("dog", "Dog ghost");

    companion object {
        val DEFAULT = GHOST

        fun fromId(id: String?): Species = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

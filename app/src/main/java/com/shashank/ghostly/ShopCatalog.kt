package com.shashank.ghostly

import android.content.Context

/**
 * Everything the Shop sells, and what owning it means.
 *
 * Three shelves:
 * - **Treats** are used up the moment they are given. Each moves his needs by its own amounts.
 * - **Wardrobe** is bought once and kept: a hat, something for his face, something round his
 *   neck. One of each can be worn at a time, and [OutfitPainter] draws it on him.
 * - **Emojis** are bought once and kept too. The one he has on pops up over his head when he is
 *   happy — petted, fed, playing, or just pleased with himself out on the overlay.
 *
 * Prices are in tokens only. Nothing here is sold for money.
 */
enum class ShopShelf(val label: String) {
    TREATS("Treats"),
    WARDROBE("Wardrobe"),
    EMOJIS("Emojis"),
}

/** Where on him a wardrobe item goes, and the Prefs key that remembers what is there. */
enum class OutfitSlot(val label: String, val prefsKey: String) {
    HEAD("Hats", Prefs.KEY_WEAR_HEAD),
    FACE("Face", Prefs.KEY_WEAR_FACE),
    NECK("Neck", Prefs.KEY_WEAR_NECK),
    EMOJI("Emojis", Prefs.KEY_WEAR_EMOJI),
}

/** What a treat does to him, in the same 0..100 units as [PetStats]. [calm] comes off his anger. */
data class TreatEffect(
    val hunger: Float = 0f,
    val energy: Float = 0f,
    val happiness: Float = 0f,
    val calm: Float = 0f,
)

data class ShopItem(
    val id: String,
    val name: String,
    val blurb: String,
    val shelf: ShopShelf,
    val price: Int,
    /** Treats and emojis are shown as their emoji; wardrobe items are shown on him instead. */
    val emoji: String? = null,
    val slot: OutfitSlot? = null,
    val effect: TreatEffect? = null,
    /** The old Gift: he unwraps it rather than eating it — see [GhostPlayground.startGift]. */
    val unwraps: Boolean = false,
) {
    val keeps: Boolean get() = shelf != ShopShelf.TREATS
}

object ShopCatalog {

    val TREATS = listOf(
        ShopItem(
            "treat_cookie", "Cookie", "A crunchy little pick-me-up", ShopShelf.TREATS, 1, "🍪",
            effect = TreatEffect(hunger = 20f, happiness = 12f, calm = 8f),
        ),
        ShopItem(
            "treat_strawberry", "Strawberry", "Sweet, juicy, gone in one bite", ShopShelf.TREATS, 1, "🍓",
            effect = TreatEffect(hunger = 12f, happiness = 18f, calm = 5f),
        ),
        ShopItem(
            "treat_mochi", "Mochi", "Squishy and very soothing", ShopShelf.TREATS, 2, "🍡",
            effect = TreatEffect(hunger = 25f, happiness = 15f, calm = 15f),
        ),
        ShopItem(
            "treat_boba", "Bubble tea", "Energy in a cup, with pearls", ShopShelf.TREATS, 2, "🧋",
            effect = TreatEffect(hunger = 10f, energy = 25f, happiness = 12f),
        ),
        ShopItem(
            "treat_donut", "Donut", "A sprinkled ring of joy", ShopShelf.TREATS, 2, "🍩",
            effect = TreatEffect(hunger = 32f, happiness = 12f, calm = 5f),
        ),
        ShopItem(
            "treat_icecream", "Ice cream", "Cold, sweet, and all his", ShopShelf.TREATS, 3, "🍦",
            effect = TreatEffect(hunger = 18f, happiness = 30f, calm = 12f),
        ),
        ShopItem(
            "treat_cake", "Cake", "Party-size. He'll talk about it for days", ShopShelf.TREATS, 4, "🎂",
            effect = TreatEffect(hunger = 45f, happiness = 35f, calm = 20f),
        ),
        ShopItem(
            "treat_gift", "Gift", "The real apology — wins him back when he's upset", ShopShelf.TREATS, 3, "🎁",
            effect = TreatEffect(happiness = 30f, calm = 40f),
            unwraps = true,
        ),
    )

    val WARDROBE = listOf(
        ShopItem("head_bow", "Pink bow", "Tied just so, a little to one side", ShopShelf.WARDROBE, 10, slot = OutfitSlot.HEAD),
        ShopItem("head_party", "Party hat", "Every day is his birthday now", ShopShelf.WARDROBE, 15, slot = OutfitSlot.HEAD),
        ShopItem("head_beanie", "Cozy beanie", "Knitted, with a pom-pom", ShopShelf.WARDROBE, 20, slot = OutfitSlot.HEAD),
        ShopItem("head_flowers", "Flower crown", "Picked from a meadow nobody's seen", ShopShelf.WARDROBE, 25, slot = OutfitSlot.HEAD),
        ShopItem("head_witch", "Witch hat", "Spooky season, all year round", ShopShelf.WARDROBE, 30, slot = OutfitSlot.HEAD),
        ShopItem("head_halo", "Halo", "A very good ghost indeed", ShopShelf.WARDROBE, 40, slot = OutfitSlot.HEAD),
        ShopItem("head_crown", "Tiny crown", "Royalty, but make it small", ShopShelf.WARDROBE, 50, slot = OutfitSlot.HEAD),

        ShopItem("face_blush", "Rosy cheeks", "Permanently a little flustered", ShopShelf.WARDROBE, 8, slot = OutfitSlot.FACE),
        ShopItem("face_round", "Round glasses", "Reads everything over your shoulder", ShopShelf.WARDROBE, 15, slot = OutfitSlot.FACE),
        ShopItem("face_hearts", "Heart shades", "Sees everything in love", ShopShelf.WARDROBE, 25, slot = OutfitSlot.FACE),
        ShopItem("face_stars", "Star shades", "A star, and he knows it", ShopShelf.WARDROBE, 25, slot = OutfitSlot.FACE),

        ShopItem("neck_bowtie", "Bow tie", "Dressed for a very small party", ShopShelf.WARDROBE, 12, slot = OutfitSlot.NECK),
        ShopItem("neck_bell", "Little bell", "Jingles when he bobs", ShopShelf.WARDROBE, 15, slot = OutfitSlot.NECK),
        ShopItem("neck_scarf", "Knit scarf", "Ghosts get cold too", ShopShelf.WARDROBE, 20, slot = OutfitSlot.NECK),
    )

    val EMOJIS = listOf(
        ShopItem("emoji_heart", "Sparkle heart", "Pops up when he's happy", ShopShelf.EMOJIS, 8, "💖", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_sparkles", "Sparkles", "A little shimmer of joy", ShopShelf.EMOJIS, 8, "✨", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_boo", "Boo", "A ghost for your ghost", ShopShelf.EMOJIS, 10, "👻", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_blossom", "Blossom", "Soft and springy", ShopShelf.EMOJIS, 10, "🌸", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_star", "Star", "For a star performance", ShopShelf.EMOJIS, 10, "⭐", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_music", "Tune", "He hums when he's happy", ShopShelf.EMOJIS, 10, "🎵", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_clover", "Clover", "Lucky to have you", ShopShelf.EMOJIS, 10, "🍀", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_rainbow", "Rainbow", "After the grumpy bit", ShopShelf.EMOJIS, 12, "🌈", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_butterfly", "Butterfly", "Flutters off his head", ShopShelf.EMOJIS, 12, "🦋", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_pumpkin", "Pumpkin", "Spooky and proud of it", ShopShelf.EMOJIS, 12, "🎃", slot = OutfitSlot.EMOJI),
        ShopItem("emoji_fire", "On fire", "For a streak worth bragging about", ShopShelf.EMOJIS, 15, "🔥", slot = OutfitSlot.EMOJI),
    )

    val ALL: List<ShopItem> = TREATS + WARDROBE + EMOJIS

    private val byId = ALL.associateBy { it.id }

    fun find(id: String?): ShopItem? = id?.let { byId[it] }

    fun shelf(shelf: ShopShelf): List<ShopItem> = ALL.filter { it.shelf == shelf }

    fun owns(context: Context, item: ShopItem): Boolean = item.id in Prefs.ownedItems(context)

    fun isWorn(context: Context, item: ShopItem): Boolean =
        item.slot != null && Prefs.worn(context, item.slot.prefsKey) == item.id

    enum class BuyResult { BOUGHT, ALREADY_OWNED, CANT_AFFORD }

    /** Buys a keepsake and puts it straight on him. Treats are not bought here — they are given,
     *  through [Emotions.giveTreat]. */
    fun buy(context: Context, item: ShopItem): BuyResult {
        if (!item.keeps) return BuyResult.CANT_AFFORD
        if (owns(context, item)) return BuyResult.ALREADY_OWNED
        if (!Emotions.spend(context, item.price)) return BuyResult.CANT_AFFORD
        Prefs.addOwnedItem(context, item.id)
        wear(context, item)
        return BuyResult.BOUGHT
    }

    fun wear(context: Context, item: ShopItem) {
        val slot = item.slot ?: return
        if (!owns(context, item)) return
        Prefs.setWorn(context, slot.prefsKey, item.id)
    }

    fun takeOff(context: Context, item: ShopItem) {
        val slot = item.slot ?: return
        if (Prefs.worn(context, slot.prefsKey) == item.id) Prefs.setWorn(context, slot.prefsKey, null)
    }
}

/** What he has on right now, one item (or nothing) per slot. */
data class Outfit(
    val head: String? = null,
    val face: String? = null,
    val neck: String? = null,
    val emoji: String? = null,
) {
    /** The emoji he pops, as the character itself. */
    val emojiChar: String? get() = ShopCatalog.find(emoji)?.emoji

    fun with(item: ShopItem): Outfit = when (item.slot) {
        OutfitSlot.HEAD -> copy(head = item.id)
        OutfitSlot.FACE -> copy(face = item.id)
        OutfitSlot.NECK -> copy(neck = item.id)
        OutfitSlot.EMOJI -> copy(emoji = item.id)
        null -> this
    }

    companion object {
        val NONE = Outfit()

        /** Only what is actually owned: a worn id with nothing behind it is ignored. */
        fun load(context: Context): Outfit {
            val owned = Prefs.ownedItems(context)
            fun slot(key: String) = Prefs.worn(context, key)?.takeIf { it in owned && ShopCatalog.find(it) != null }
            return Outfit(
                head = slot(Prefs.KEY_WEAR_HEAD),
                face = slot(Prefs.KEY_WEAR_FACE),
                neck = slot(Prefs.KEY_WEAR_NECK),
                emoji = slot(Prefs.KEY_WEAR_EMOJI),
            )
        }
    }
}

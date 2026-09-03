package com.shashank.ghostly

/**
 * A momentary face, on top of whatever mood he is in.
 *
 * Moods last (content, sad, angry); expressions are reactions — a grin at a treat, a yawn before
 * a nap, a baffled look, a swoon when he is run right down. They expire on their own.
 */
enum class Expression {
    NONE,

    /** A plain grin. */
    SMILE,

    /** Eyes squeezed shut, wider grin — the petted, well-fed face. */
    DELIGHTED,

    /** Heavy lids and a yawn. Not asleep yet: this is the warning. */
    SLEEPY,

    /** One brow up, a wavering mouth — he has no idea what just happened. */
    CONFUSED,

    /** X eyes and a swirl. Reserved for genuinely run down. */
    FAINT,
}

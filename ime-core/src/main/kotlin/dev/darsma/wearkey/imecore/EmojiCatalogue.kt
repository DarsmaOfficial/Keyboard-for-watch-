package dev.darsma.wearkey.imecore

/**
 * The emoji offered by the emoji layer, grouped into categories (spec §11 v0.3).
 *
 * ## Why a curated list rather than a full Unicode table
 *
 * The complete emoji set is ~3,800 characters. On a 466 px round display that is thousands of
 * swipes through a grid where each glyph is the size of a fingertip — unusable, and it would push
 * a large table into an APK that is meant to stay small. This list is the subset people actually
 * send: roughly the top few hundred by usage, which covers the overwhelming majority of real use.
 *
 * ## Why no skin-tone or ZWJ sequences
 *
 * Skin-tone modifiers and zero-width-joiner families (👨‍👩‍👧, 🏳️‍🌈) are multi-codepoint sequences.
 * They render correctly only if the platform font has the composed glyph, and on a watch with an
 * older `NotoColorEmoji.ttf` an unsupported sequence degrades into two or three separate glyphs —
 * visibly broken, and worse, the *committed text* is still the sequence, so the user sends
 * something they did not see. Single codepoints render or do not; there is no half-broken state.
 *
 * Every entry here is a single Unicode scalar, which is also what makes [EMOJI] safely iterable by
 * code point.
 */
object EmojiCatalogue {

    /** A named group of emoji, in the order the layer presents them. */
    data class Category(val id: String, val emoji: List<String>)

    /**
     * Categories in usage order, most-sent first.
     *
     * Smileys lead because they dominate real message content; flags and symbols are last because
     * they are searched for deliberately rather than browsed.
     */
    val CATEGORIES: List<Category> = listOf(
        Category(
            "smileys",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
                "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
                "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳",
                "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖", "😫",
                "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳",
                "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭",
                "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😦", "😧",
                "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢",
                "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👻",
                "💀", "☠", "👽", "🤖", "💩", "🤡"
            )
        ),
        Category(
            "gestures",
            listOf(
                "👍", "👎", "👌", "🤌", "🤏", "✌", "🤞", "🤟", "🤘", "🤙",
                "👈", "👉", "👆", "👇", "☝", "✋", "🤚", "🖐", "🖖", "👋",
                "🤝", "🙏", "✍", "💪", "🦾", "🦵", "🦶", "👂", "👃", "🧠",
                "👀", "👁", "👄", "💋", "🩸"
            )
        ),
        Category(
            "hearts",
            listOf(
                "❤", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                "❣", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮",
                "✝", "☪", "🕉", "☸", "✡", "🔯", "🕎", "☯", "☦", "🛐"
            )
        ),
        Category(
            "animals",
            listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
                "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒", "🐔",
                "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴",
                "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🦗", "🕷", "🦂",
                "🐢", "🐍", "🦎", "🐙", "🦑", "🦐", "🦀", "🐡", "🐠", "🐟",
                "🐬", "🐳", "🐋", "🦈", "🐊", "🐅", "🐆", "🦓", "🦍", "🐘",
                "🦏", "🐪", "🐫", "🦒", "🐄", "🐎", "🐖", "🐏", "🐑", "🐐",
                "🦌", "🐕", "🐩", "🐈", "🐓", "🦃", "🕊", "🐇", "🐁", "🐀"
            )
        ),
        Category(
            "food",
            listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍈",
                "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦",
                "🥬", "🥒", "🌶", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠", "🥐",
                "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓",
                "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🥗", "🍝", "🍜",
                "🍲", "🍛", "🍣", "🍱", "🥟", "🍤", "🍙", "🍚", "🍥", "🥠",
                "🍦", "🍧", "🍨", "🍩", "🍪", "🎂", "🍰", "🧁", "🥧", "🍫",
                "🍬", "🍭", "🍮", "🍯", "☕", "🍵", "🧃", "🥤", "🍺", "🍻",
                "🥂", "🍷", "🥃", "🍸", "🍹", "🍾"
            )
        ),
        Category(
            "activity",
            listOf(
                "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🎱", "🏓",
                "🏸", "🥅", "🏒", "🏑", "🥍", "🏏", "⛳", "🏹", "🎣", "🤿",
                "🥊", "🥋", "🎽", "🛹", "🛷", "⛸", "🥌", "🎿", "⛷", "🏂",
                "🏋", "🤼", "🤸", "⛹", "🤺", "🤾", "🏌", "🏇", "🧘", "🏄",
                "🏊", "🚴", "🚵", "🎯", "🎮", "🎲", "🎰", "🎳", "🎪", "🎨",
                "🎭", "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷", "🎺", "🎸",
                "🎻", "🏆", "🥇", "🥈", "🥉", "🏅"
            )
        ),
        Category(
            "travel",
            listOf(
                "🚗", "🚕", "🚙", "🚌", "🚎", "🏎", "🚓", "🚑", "🚒", "🚐",
                "🚚", "🚛", "🚜", "🛴", "🚲", "🛵", "🏍", "🚨", "🚔", "🚍",
                "✈", "🛫", "🛬", "🚀", "🛸", "🚁", "⛵", "🚤", "🛥", "🛳",
                "⚓", "🚉", "🚂", "🚆", "🚇", "🚊", "🗺", "🗿", "🗽", "🗼",
                "🏰", "🏯", "🏟", "🎡", "🎢", "🎠", "⛲", "⛱", "🏖", "🏝",
                "🏔", "⛰", "🌋", "🗻", "🏕", "⛺", "🏠", "🏡", "🏘", "🏢",
                "🏥", "🏦", "🏨", "🏪", "🏫", "🏬", "⛪", "🕌", "🕍", "⛩"
            )
        ),
        Category(
            "objects",
            listOf(
                "⌚", "📱", "💻", "⌨", "🖥", "🖨", "🖱", "💽", "💾", "💿",
                "📷", "📸", "📹", "🎥", "📞", "☎", "📟", "📠", "📺", "📻",
                "🧭", "⏱", "⏲", "⏰", "🕰", "⌛", "⏳", "📡", "🔋", "🔌",
                "💡", "🔦", "🕯", "🧯", "🛢", "💸", "💵", "💴", "💶", "💷",
                "💰", "💳", "💎", "⚖", "🧰", "🔧", "🔨", "⚒", "🛠", "⛏",
                "🔩", "⚙", "🧱", "⛓", "🧲", "🔫", "💣", "🧨", "🔪", "🗡",
                "⚔", "🛡", "🚬", "⚰", "⚱", "🏺", "🔮", "📿", "💈", "⚗",
                "🔭", "🔬", "🕳", "💊", "💉", "🌡", "🚽", "🚿", "🛁", "🧴",
                "🧷", "🧹", "🧺", "🧻", "🧼", "🧽", "🔑", "🗝", "🚪", "🛏",
                "🛋", "🪑", "🖼", "🛍", "🎁", "🎈", "🎏", "🎀", "🎊", "🎉"
            )
        ),
        Category(
            "symbols",
            listOf(
                "✅", "❌", "❎", "✔", "☑", "⭕", "🔴", "🟠", "🟡", "🟢",
                "🔵", "🟣", "⚫", "⚪", "🟤", "🔶", "🔷", "🔸", "🔹", "🔺",
                "🔻", "⬛", "⬜", "◼", "◻", "▪", "▫", "🔲", "🔳", "⚠",
                "🚫", "❗", "❓", "❕", "❔", "‼", "⁉", "💯", "🔥", "⭐",
                "🌟", "✨", "⚡", "💥", "💫", "💦", "💨", "🕐", "➕", "➖",
                "➗", "✖", "♾", "💲", "💱", "™", "©", "®", "🔝", "🔚",
                "🔙", "🔛", "🔜", "⬆", "⬇", "⬅", "➡", "↗", "↘", "↙",
                "↖", "↕", "↔", "🔄", "🔁", "🔂", "▶", "⏸", "⏹", "⏺",
                "⏭", "⏮", "🔀", "🔊", "🔇", "🔔", "🔕", "📢", "📣", "💬",
                "💭", "🗯", "♻", "🔱", "⚜", "🆗", "🆕", "🆓", "🆙", "🆒"
            )
        )
    )

    /** Every emoji, flattened — used for the recents store's validity check. */
    val ALL: Set<String> = CATEGORIES.flatMapTo(LinkedHashSet()) { it.emoji }
}

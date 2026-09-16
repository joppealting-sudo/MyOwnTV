package tv.own.owntv.provider.solcon

/** Pure routing decision so multicast integration cannot disturb the existing OwnTV ladder. */
object SolconPlaybackRoute {
    enum class Target {
        MEDIA3_MULTICAST,
        MPV_MULTICAST,
        EXISTING,
    }

    fun decide(url: String, forceMpv: Boolean): Target {
        val stream = SolconStreamPolicy.classify(url)
        if (!stream.isMulticast) return Target.EXISTING
        return if (forceMpv) Target.MPV_MULTICAST else Target.MEDIA3_MULTICAST
    }
}

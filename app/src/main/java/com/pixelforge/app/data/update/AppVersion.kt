package com.pixelforge.app.data.update

/**
 * Semantic version parser and comparator for release tags such as "v1.1.0" or "1.2.0-rc1".
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease, ignoreCase = true)
        }
    }

    companion object {
        fun parse(raw: String?): AppVersion? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            if (trimmed.isEmpty()) return null

            val dashIndex = trimmed.indexOf('-')
            val core = if (dashIndex >= 0) trimmed.substring(0, dashIndex) else trimmed
            val preRelease = if (dashIndex >= 0) {
                trimmed.substring(dashIndex + 1).ifBlank { null }
            } else {
                null
            }

            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 4) return null
            val numbers = parts.map { part -> part.toIntOrNull() ?: return null }
            if (numbers.any { it < 0 }) return null

            return AppVersion(
                major = numbers.getOrElse(0) { 0 },
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                preRelease = preRelease
            )
        }
    }
}

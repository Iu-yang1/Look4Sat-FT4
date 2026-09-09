package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import java.net.URI

internal const val RELEASE_REPOSITORY = "https://github.com/Iu-yang1/Look4Sat-FT4"
internal val UPDATE_MIRRORS = listOf(
    "https://ghfast.top/",
    "https://gh.llkk.cc/",
    "https://github.moeyy.xyz/"
)

internal fun parseReleasePage(html: String, mirror: String, supportedAbis: List<String>): LatestRelease? {
    if (mirror.isNotEmpty() && mirror !in UPDATE_MIRRORS) return null
    val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
        .firstOrNull { htmlAttribute(it.value, "property").equals("og:url", true) }?.value ?: return null
    val releaseUrl = htmlAttribute(meta, "content")?.let(::decodeHtmlEntities)?.removePrefix(mirror) ?: return null
    if (!releaseUrl.startsWith("$RELEASE_REPOSITORY/releases/tag/", ignoreCase = true)) return null
    val tag = releaseUrl.substringAfterLast('/')
    if (!tag.matches(Regex("[vV]?[0-9]+(?:\\.[0-9]+)+(?:-[A-Za-z0-9]+(?:\\.[0-9]+)*)?"))) return null
    val assets = Regex("<a\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { anchor ->
        val href = htmlAttribute(anchor.value, "href")?.let(::decodeHtmlEntities) ?: return@mapNotNull null
        val absolute = if (href.startsWith('/')) "https://github.com$href" else href
        val canonical = canonicalDownloadUrl(absolute) ?: return@mapNotNull null
        if (!canonical.startsWith("$RELEASE_REPOSITORY/releases/download/$tag/", true)) return@mapNotNull null
        val name = runCatching { URI(canonical).path.substringAfterLast('/') }.getOrNull() ?: return@mapNotNull null
        ReleaseAsset(name, canonical, "", "uploaded", -1L)
    }.distinctBy { it.url }.toList()
    val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(html)?.groupValues?.get(1)?.let(::htmlText)?.substringBefore('·')?.removePrefix("Release ")?.trim() ?: tag
    val description = Regex(
        "<div[^>]*class=[\"'][^\"']*markdown-body[^\"']*[\"'][^>]*>(.*?)</div>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    ).find(html)?.groupValues?.get(1)?.let(::htmlText).orEmpty()
    return LatestRelease(tag, title, description, selectReleaseAsset(assets, tag, supportedAbis)?.url?.let { mirror + it })
}

private fun htmlAttribute(tag: String, name: String): String? =
    Regex("\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(tag)?.groupValues?.get(2)

private fun htmlText(value: String): String = decodeHtmlEntities(
    value.replace(Regex("<(br\\s*/?|/p|/li|/h[1-6])>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
        .replace(Regex("<[^>]+>"), "")
).trim()

private fun decodeHtmlEntities(value: String): String = value.replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&nbsp;", " ").replace("&amp;", "&")

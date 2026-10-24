package io.github.basekt.admin

import io.github.basekt.schema.Extensible
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.extension
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** The key a resource's [AdminLink]s are kept under in [Resource.extensions]. */
internal const val ADMIN_LINKS_KEY: String = "admin.links"

/**
 * A link from a record in the admin panel to a page of the host app, such as the record on the public site. The panel
 * shows it on the record's page and on its row in the list, and opens it in a new tab.
 */
@Serializable
public data class AdminLink(
    /** The text of the button, such as `View on site`. */
    val label: String,
    /**
     * Where it goes: a path on the same server (`/lessons/{id}`) or an `http` or `https` address. `{id}` and `{field}`
     * stand for the record's id and the value of one of its fields, written into the address percent-encoded. A link
     * is left out of a record that has no value for one of them.
     */
    val url: String,
)

/** Collects the links of [adminLinks]. */
public class AdminLinksBuilder internal constructor() {
    private val links = mutableListOf<AdminLink>()

    /** Adds the link [label] to [url], which may hold `{id}` and `{field}` placeholders. See [AdminLink.url]. */
    public fun link(label: String, url: String) {
        links += AdminLink(label, url)
    }

    internal fun build(): List<AdminLink> = links.toList()
}

/**
 * Gives this resource links from the admin panel to the host app's own pages:
 *
 * ```kotlin
 * resource("lessons") {
 *     // fields ...
 *     adminLinks {
 *         link("View on site", "/lessons/{id}")
 *         link("Preview", "/preview?slug={slug}")
 *     }
 * }
 * ```
 *
 * Calling it again replaces the links it set before. Install `BaseKtAdmin` to use them: it refuses a link that names a
 * field the resource doesn't have, or that points anywhere but a path or an `http` or `https` address.
 */
public fun Extensible.adminLinks(block: AdminLinksBuilder.() -> Unit) {
    extension(ADMIN_LINKS_KEY, ListSerializer(AdminLink.serializer()), AdminLinksBuilder().apply(block).build())
}

/** Gives this resource one link from the admin panel to the host app. See [adminLinks]. */
public fun Extensible.adminLink(label: String, url: String): Unit = adminLinks { link(label, url) }

/** The resource's links to the host app, in the order they were declared. */
public val Resource.adminLinks: List<AdminLink> get() = extension<List<AdminLink>>(ADMIN_LINKS_KEY).orEmpty()

private val placeholder = Regex("""\{([^{}]*)}""")
private val fieldName = Regex("[A-Za-z][A-Za-z0-9_]*")

/** Whether [url] is a path on the same server or an `http` or `https` address, with no spaces or control characters. */
internal fun isSafeLinkUrl(url: String): Boolean {
    if (url.any { it <= ' ' || it.code == 127 }) return false
    return (url.startsWith("/") && !url.startsWith("//")) || url.startsWith("https://") || url.startsWith("http://")
}

/** Returns what is wrong with the links this schema's resources declare. Empty means every one can be shown. */
public fun Schema.adminLinkProblems(): List<String> {
    val problems = mutableListOf<String>()
    for (resource in resources) {
        for (link in resource.adminLinks) {
            val at = "Link '${link.label}' of '${resource.name}'"
            if (link.label.isBlank() || link.label.length > 40) problems += "$at needs a label of 1 to 40 characters."
            if (!isSafeLinkUrl(link.url)) {
                problems += "$at has the address '${link.url}', which isn't a path starting with '/' or an http or https address."
                continue
            }
            val unplaced = link.url.replace(placeholder, "")
            if ('{' in unplaced || '}' in unplaced) problems += "$at has an unmatched brace in '${link.url}'."
            for (match in placeholder.findAll(link.url)) {
                val name = match.groupValues[1]
                val field = resource.field(name)
                when {
                    name == Resource.ID -> Unit
                    !fieldName.matches(name) -> problems += "$at has the placeholder '{$name}', which isn't a field name."
                    field == null -> problems += "$at names '{$name}', which isn't a field of '${resource.name}'."
                    field.hidden -> problems += "$at names '{$name}', a hidden field."
                }
            }
        }
    }
    return problems
}

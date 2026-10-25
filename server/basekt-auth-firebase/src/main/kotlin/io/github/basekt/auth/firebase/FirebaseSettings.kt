package io.github.basekt.auth.firebase

import io.ktor.server.config.ApplicationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The settings of Firebase ID tokens: the Firebase project they're for, how much clock skew to allow, and which custom
 * claims hold a caller's roles and record-scope attributes.
 *
 * A token is accepted when Google signed it (RS256, with a key from [FirebaseKeys]), its issuer is [issuer], its
 * audience is [projectId], it isn't expired, and its `iat` and `auth_time` aren't in the future. [clockSkew] is allowed
 * on each of those times.
 */
public class FirebaseSettings(
    /** The Firebase project id, the audience of its ID tokens. */
    public val projectId: String,
    /** The difference between this server's clock and Google's that the time checks allow. */
    public val clockSkew: Duration = 30.seconds,
    /** The custom claim that lists a caller's roles, as an array of strings or one string of names. */
    public val rolesClaim: String = "roles",
    /** The custom claims read as record-scope attributes, each named after its claim. */
    public val attributeClaims: List<String> = emptyList(),
    /** The realm named in `WWW-Authenticate` challenges. */
    public val realm: String = "firebase",
) {
    init {
        require(projectId.isNotBlank() && projectId.none { it == '/' || it.isWhitespace() }) {
            "A Firebase project id is a non-blank name without slashes or spaces, not \"$projectId\"."
        }
        require(clockSkew >= Duration.ZERO && clockSkew <= MAX_CLOCK_SKEW) {
            "The clock skew must be between 0 and $MAX_CLOCK_SKEW, not $clockSkew."
        }
        require(rolesClaim.isNotBlank()) { "The roles claim needs a name." }
        require(attributeClaims.none { it.isBlank() }) { "Attribute claims need names." }
    }

    /** The `iss` claim of the project's ID tokens. */
    public val issuer: String get() = "https://securetoken.google.com/$projectId"

    override fun toString(): String =
        "FirebaseSettings(projectId=$projectId, clockSkew=$clockSkew, rolesClaim=$rolesClaim, attributeClaims=$attributeClaims, realm=$realm)"

    /** Reads settings from configuration. */
    public companion object {
        /** The most clock skew the settings accept. */
        public val MAX_CLOCK_SKEW: Duration = 5.minutes

        /**
         * Reads `projectId`, and the optional `clockSkew` (an ISO-8601 duration such as `PT30S`), `rolesClaim`,
         * `attributeClaims` and `realm`, from [config], such as the application's `auth.firebase` section.
         */
        public fun from(config: ApplicationConfig): FirebaseSettings = FirebaseSettings(
            projectId = config.property("projectId").getString(),
            clockSkew = config.propertyOrNull("clockSkew")?.getString()?.let(Duration::parseIsoString) ?: 30.seconds,
            rolesClaim = config.propertyOrNull("rolesClaim")?.getString() ?: "roles",
            attributeClaims = config.propertyOrNull("attributeClaims")?.getList() ?: emptyList(),
            realm = config.propertyOrNull("realm")?.getString() ?: "firebase",
        )
    }
}

package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.ContractFirst
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** requiredIf (SPEC §4.2.1) and roles (SPEC §4.9) on file inputs, read by [Docuconf.checkContract]. */
class ContractConditionsTest {
    @TempDir
    lateinit var root: Path

    private val contract = ContractFirst.parse(
        """
        {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract",
         "metadata": {"name": "relay", "generator": {"language": "cue", "sdk": "hand", "version": "1"}},
         "roles": ["web", "worker"],
         "vars": {
           "DOCUCONF_ROLE": {"type": "enum", "description": "The role this workload runs", "required": true, "values": ["web", "worker"]},
           "TLS_ENABLED": {"type": "bool", "description": "Serve HTTPS instead of HTTP", "default": false}
         },
         "files": {
           "allowlist": {"type": "text", "description": "Clients allowed over TLS", "path": "/etc/relay/allow.txt", "requiredIf": {"TLS_ENABLED": true}},
           "robots": {"type": "text", "description": "The robots.txt the web server serves", "path": "/etc/web/robots.txt", "required": true, "roles": ["web"]}
         }}
        """,
    )

    private fun env(vararg pairs: Pair<String, String>) = mapOf("DOCUCONF_FILE_ROOT" to root.toString(), *pairs)

    @Test
    fun aMissingFileNamesItsCondition() {
        val r = Docuconf.checkContract(contract, env("DOCUCONF_ROLE" to "worker", "TLS_ENABLED" to "true"))
        assertIs<ContractFirst.Result.Failure>(r)
        assertEquals(
            listOf("allowlist: file_missing: file $root/etc/relay/allow.txt does not exist, and is required when TLS_ENABLED is true"),
            r.violations.map { it.toString() },
        )
    }

    @Test
    fun aFileOfAnotherRoleIsOptional() {
        assertIs<ContractFirst.Result.Success>(Docuconf.checkContract(contract, env("DOCUCONF_ROLE" to "worker")))
        val web = Docuconf.checkContract(contract, env("DOCUCONF_ROLE" to "web"))
        assertIs<ContractFirst.Result.Failure>(web)
        assertEquals(listOf("robots: file_missing: file $root/etc/web/robots.txt does not exist"), web.violations.map { it.toString() })
        Files.createDirectories(root.resolve("etc/web"))
        Files.writeString(root.resolve("etc/web/robots.txt"), "User-agent: *\n")
        val ok = Docuconf.checkContract(contract, env("DOCUCONF_ROLE" to "web"))
        assertIs<ContractFirst.Result.Success>(ok)
        assertEquals("User-agent: *\n", ok.values.file("robots"))
    }
}

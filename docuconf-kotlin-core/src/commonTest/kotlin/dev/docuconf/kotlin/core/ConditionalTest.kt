package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** requiredIf (SPEC §4.2.1), roles (SPEC §4.9) and includes (SPEC §4.8) in the contract-first mode. */
class ConditionalTest {
    private fun contract(body: String) = ContractFirst.parse(
        """{"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract",
            "metadata": {"name": "relay", "generator": {"language": "cue", "sdk": "hand", "version": "1"}}, $body}""",
    )

    private val relay = contract(
        """
        "vars": {
          "APP_ENV": {"type": "string", "description": "Which profile file the app loads", "default": "development"},
          "TLS_ENABLED": {"type": "bool", "description": "Serve HTTPS instead of HTTP", "default": false},
          "CA_SUBJECT": {"type": "string", "description": "Subject of the client CA", "requiredIf": {"TLS_ENABLED": true}},
          "LOG_FORMAT": {"type": "enum", "description": "Log output format", "values": ["text", "json"], "default": "text"},
          "LOG_SCHEMA_URL": {"type": "url", "description": "Schema the logs follow", "requiredIf": {"LOG_FORMAT": "json"}},
          "PORT": {"type": "int", "description": "Port to listen on", "default": 8080},
          "ACME_EMAIL": {"type": "string", "description": "Contact for certificate renewal", "requiredIf": {"PORT": 443}}
        },
        "files": {
          "allowlist": {"type": "text", "description": "Clients allowed over TLS", "path": "/etc/relay/allow.txt", "requiredIf": {"TLS_ENABLED": true}}
        },
        "profiles": {"selector": "APP_ENV", "default": "development", "defaults": {"production": {"TLS_ENABLED": true}}}
        """,
    )

    /** Reads no file: every file input is absent, `file_missing` when required. */
    private val noFiles = object : ContractFirst.Inputs {
        override fun loadFile(spec: FileSpec, env: Map<String, String>) = ContractFirst.LoadedFile(
            null,
            if (spec.required) listOf(Violation(Codes.FILE_MISSING, spec.name, "${spec.path} does not exist")) else emptyList(),
        )

        override fun readOverlay(spec: OverlaySpec, env: Map<String, String>) = ContractFirst.LoadedOverlay(null)
    }

    private fun violations(c: Contract, env: Map<String, String>): List<String> {
        val r = ContractFirst.check(c, env, noFiles)
        assertIs<ContractFirst.Result.Failure>(r, "$env")
        return r.violations.map { "[${it.code}] ${it.input}: ${it.message}" }.sorted()
    }

    @Test
    fun conditionsOffLeaveTheInputsOptional() {
        val r = ContractFirst.check(relay, emptyMap(), noFiles)
        assertIs<ContractFirst.Result.Success>(r)
        assertNull(r.values["CA_SUBJECT"])
        assertNull(r.values["allowlist"])
    }

    @Test
    fun messagesNameTheConditionWithItsValueAsJson() {
        assertEquals(
            listOf(
                "[file_missing] allowlist: /etc/relay/allow.txt does not exist, and is required when TLS_ENABLED is true",
                "[missing_required] ACME_EMAIL: is required when PORT is 443",
                "[missing_required] CA_SUBJECT: is required when TLS_ENABLED is true",
                "[missing_required] LOG_SCHEMA_URL: is required when LOG_FORMAT is \"json\"",
            ),
            violations(relay, mapOf("TLS_ENABLED" to "true", "LOG_FORMAT" to "json", "PORT" to "443")),
        )
    }

    @Test
    fun conditionsReadTheLoadedValue() {
        // The selected profile's default turns TLS on; the environment turns it off again.
        assertEquals(
            listOf(
                "[file_missing] allowlist: /etc/relay/allow.txt does not exist, and is required when TLS_ENABLED is true",
                "[missing_required] CA_SUBJECT: is required when TLS_ENABLED is true",
            ),
            violations(relay, mapOf("APP_ENV" to "production")),
        )
        assertIs<ContractFirst.Result.Success>(ContractFirst.check(relay, mapOf("APP_ENV" to "production", "TLS_ENABLED" to "false"), noFiles))
        // The condition variable's own default.
        val onDefault = contract(""""vars": {
            "PORT": {"type": "int", "description": "Port to listen on", "default": 443},
            "ACME_EMAIL": {"type": "string", "description": "Contact for certificate renewal", "requiredIf": {"PORT": 443}}}""")
        assertEquals(listOf("[missing_required] ACME_EMAIL: is required when PORT is 443"), violations(onDefault, emptyMap()))
        // The wire form is parsed first: TRUE and +443 are the condition's values.
        assertEquals(
            listOf(
                "[file_missing] allowlist: /etc/relay/allow.txt does not exist, and is required when TLS_ENABLED is true",
                "[missing_required] ACME_EMAIL: is required when PORT is 443",
                "[missing_required] CA_SUBJECT: is required when TLS_ENABLED is true",
            ),
            violations(relay, mapOf("TLS_ENABLED" to "TRUE", "PORT" to "+443")),
        )
    }

    @Test
    fun anInvalidConditionVariableMakesNothingRequired() {
        assertEquals(listOf("TLS_ENABLED"), violations(relay, mapOf("TLS_ENABLED" to "yes")).map { it.substringAfter("] ").substringBefore(":") })
    }

    @Test
    fun anEmptyValueIsUnset() {
        assertEquals(
            listOf("[missing_required] LOG_SCHEMA_URL: is required when LOG_FORMAT is \"json\""),
            violations(relay, mapOf("LOG_FORMAT" to "json", "LOG_SCHEMA_URL" to "")),
        )
    }

    private val shop = contract(
        """
        "roles": ["web", "worker"],
        "vars": {
          "DOCUCONF_ROLE": {"type": "enum", "description": "The role this workload runs", "required": true, "values": ["web", "worker"]},
          "DATABASE_URL": {"type": "string", "description": "Database connection string", "required": true},
          "ALLOWED_ORIGINS": {"type": "list", "items": "string", "description": "Origins the web server accepts", "required": true, "roles": ["web"]},
          "QUEUES": {"type": "list", "items": "string", "description": "Queues the worker takes jobs from", "required": true, "roles": ["worker"]},
          "CACHE_ENABLED": {"type": "bool", "description": "Cache rendered pages", "default": true, "roles": ["web"]},
          "CACHE_URL": {"type": "string", "description": "Page cache address", "requiredIf": {"CACHE_ENABLED": true}, "roles": ["web"]}
        },
        "files": {
          "robots": {"type": "text", "description": "The robots.txt the web server serves", "path": "/etc/shop/robots.txt", "required": true, "roles": ["web"]}
        }
        """,
    )

    @Test
    fun rolesNarrowTheContractToTheWorkload() {
        assertEquals(listOf("web", "worker"), shop.roles)
        assertEquals("worker", ContractFirst.role(shop, mapOf("DOCUCONF_ROLE" to "worker")))
        assertIs<ContractFirst.Result.Success>(ContractFirst.check(shop, mapOf("DOCUCONF_ROLE" to "worker", "DATABASE_URL" to "db", "QUEUES" to "default"), noFiles))
        assertEquals(
            listOf(
                "[file_missing] robots: /etc/shop/robots.txt does not exist",
                "[missing_required] ALLOWED_ORIGINS: required, but not set",
                "[missing_required] CACHE_URL: is required when CACHE_ENABLED is true",
            ),
            violations(shop, mapOf("DOCUCONF_ROLE" to "web", "DATABASE_URL" to "db")),
        )
    }

    @Test
    fun withoutAValidRoleTheRoleIsTheOneProblem() {
        assertEquals(listOf("[missing_required] DOCUCONF_ROLE: required, but not set"), violations(shop, mapOf("DATABASE_URL" to "db")))
        assertNull(ContractFirst.role(shop, mapOf("DOCUCONF_ROLE" to "cron")))
        val unknown = violations(shop, mapOf("DOCUCONF_ROLE" to "cron", "DATABASE_URL" to "db"))
        assertEquals(1, unknown.size)
        assertTrue(unknown.single().startsWith("[not_in_enum] DOCUCONF_ROLE:"), unknown.single())
    }

    @Test
    fun readsIncludes() {
        val c = contract(""""includes": ["go-runtime@1", "otel@1"], "vars": {"GOMAXPROCS": {"type": "int", "description": "Maximum OS threads running Go code", "min": 1}}""")
        assertEquals(listOf("go-runtime@1", "otel@1"), c.includes)
        assertEquals(emptyList(), relay.includes)
        assertNull(relay.roles)
    }

    private fun refusal(body: String): List<String> = assertFailsWith<DeclarationException> { contract(body) }.problems

    private fun assertRefused(body: String, vararg expected: String) {
        val problems = refusal(body)
        for (e in expected) assertTrue(problems.any { e in it }, "expected \"$e\" in $problems")
    }

    private val tls = """"TLS_ENABLED": {"type": "bool", "description": "Serve HTTPS instead of HTTP"}"""

    @Test
    fun refusesMalformedRequiredIf() {
        val p = "requiredIf must name one variable"
        assertRefused(""""vars": {$tls, "A": {"type": "string", "description": "Some input", "requiredIf": {}}}""", "A: $p and its value")
        assertRefused(""""vars": {$tls, "A": {"type": "string", "description": "Some input", "requiredIf": {"TLS_ENABLED": true, "B": 1}}}""", "A: $p and its value")
        assertRefused(""""vars": {$tls, "A": {"type": "string", "description": "Some input", "requiredIf": "TLS_ENABLED"}}""", "A: $p and its value")
        assertRefused(""""vars": {$tls, "A": {"type": "string", "description": "Some input", "requiredIf": {"TLS_ENABLED": 1.5}}}""", "A: $p and a string, integer or boolean value")
        assertRefused(""""vars": {$tls, "A": {"type": "string", "description": "Some input", "requiredIf": {"TLS_ENABLED": null}}}""", "A: $p and a string, integer or boolean value")
    }

    @Test
    fun refusesRequiredIfOnTheWrongVariable() {
        val input = """"type": "string", "description": "Some input""""
        assertRefused(""""vars": {"A": {$input, "requiredIf": {"B": "x"}}}""", "A: requiredIf must name another declared variable, not B")
        assertRefused(""""vars": {"A": {$input, "requiredIf": {"A": "x"}}}""", "A: requiredIf must name another declared variable, not A")
        assertRefused(
            """"vars": {"S": {"type": "string", "description": "A secret value", "secret": true}, "A": {$input, "requiredIf": {"S": "x"}}}""",
            "A: requiredIf names S, which is not a plain string, int, bool or enum variable",
        )
        assertRefused(
            """"vars": {"D": {"type": "duration", "description": "A timeout value"}, "A": {$input, "requiredIf": {"D": "1m"}}}""",
            "A: requiredIf names D, which is not a plain string, int, bool or enum variable",
        )
        assertRefused(
            """"files": {"cert": {"type": "text", "description": "Some text file", "path": "/etc/app/cert.txt", "requiredIf": {"NOPE": true}}}""",
            "file cert: requiredIf must name another declared variable, not NOPE",
        )
    }

    @Test
    fun refusesAValueTheConditionVariableCannotHold() {
        val input = """"type": "string", "description": "Some input""""
        // A bool is never an int, nor an int a bool.
        assertRefused(""""vars": {$tls, "A": {$input, "requiredIf": {"TLS_ENABLED": 1}}}""", "A: requiredIf: TLS_ENABLED is 1 is not a value TLS_ENABLED can have")
        assertRefused(
            """"vars": {"PORT": {"type": "int", "description": "Port to listen on"}, "A": {$input, "requiredIf": {"PORT": true}}}""",
            "A: requiredIf: PORT is true is not a value PORT can have",
        )
        assertRefused(
            """"vars": {"PORT": {"type": "int", "description": "Port to listen on", "max": 1000}, "A": {$input, "requiredIf": {"PORT": 1443}}}""",
            "A: requiredIf: PORT is 1443 is not a value PORT can have",
        )
        assertRefused(
            """"vars": {"F": {"type": "enum", "description": "Log output format", "values": ["text", "json"]}, "A": {$input, "requiredIf": {"F": "xml"}}}""",
            "A: requiredIf: F is \"xml\" is not a value F can have",
        )
        assertRefused(""""vars": {$tls, "A": {$input, "requiredIf": {"TLS_ENABLED": "true"}}}""", "A: requiredIf: TLS_ENABLED is \"true\" is not a value TLS_ENABLED can have")
    }

    @Test
    fun refusesARequiredIfInputThatIsRequiredDefaultedOrDeprecated() {
        val cond = """"description": "Some input", "requiredIf": {"TLS_ENABLED": true}"""
        assertRefused(""""vars": {$tls, "A": {"type": "string", $cond, "required": true}}""", "A: an input with requiredIf cannot be required or have a default")
        assertRefused(""""vars": {$tls, "A": {"type": "string", $cond, "default": "x"}}""", "A: an input with requiredIf cannot be required or have a default")
        assertRefused(""""vars": {$tls, "A": {"type": "string", $cond, "deprecated": {"message": "Use B instead"}}}""", "A: a conditionally required input cannot be deprecated")
        assertRefused(
            """"vars": {$tls}, "files": {"f": {"type": "text", $cond, "path": "/etc/app/f.txt", "required": true}}""",
            "file f: an input with requiredIf cannot be required or have a default",
        )
    }

    private val roleVar = """"DOCUCONF_ROLE": {"type": "enum", "description": "The role this workload runs", "required": true, "values": ["web", "worker"]}"""

    @Test
    fun refusesMalformedRoles() {
        assertRefused(""""roles": [], "vars": {}""", "roles: roles must list at least one role")
        assertRefused(""""roles": "web", "vars": {}""", "contract: roles must be a list of strings")
        assertRefused(""""roles": ["web", "Worker"], "vars": {$roleVar}""", "roles: role \"Worker\" must be a DNS label")
        assertRefused(""""roles": ["web", "web"], "vars": {$roleVar}""", "roles: role web is listed twice")
        assertRefused(
            """"roles": ["web", "worker"], "vars": {$roleVar, "A": {"type": "string", "description": "Some input", "roles": ["web", "web"]}}""",
            "A: role web is listed twice",
        )
    }

    @Test
    fun refusesInputRolesOutsideTheContracts() {
        assertRefused(
            """"roles": ["web", "worker"], "vars": {$roleVar, "A": {"type": "string", "description": "Some input", "roles": ["cron"]}}""",
            "A: role cron is not one of the contract's roles, web, worker",
        )
        assertRefused(""""vars": {"A": {"type": "string", "description": "Some input", "roles": ["web"]}}""", "A: has roles, but the contract declares none")
        assertRefused(
            """"vars": {}, "files": {"f": {"type": "text", "description": "Some text file", "path": "/etc/app/f.txt", "roles": ["web"]}}""",
            "file f: has roles, but the contract declares none",
        )
    }

    @Test
    fun refusesAMissingOrWrongRoleVariable() {
        val p = "DOCUCONF_ROLE: a contract with roles declares it as a required enum whose values are the roles, web, worker, used by every role"
        assertRefused(""""roles": ["web", "worker"], "vars": {}""", p)
        val e = """"type": "enum", "description": "The role this workload runs""""
        assertRefused(""""roles": ["web", "worker"], "vars": {"DOCUCONF_ROLE": {$e, "values": ["web", "worker"]}}""", p)
        assertRefused(""""roles": ["web", "worker"], "vars": {"DOCUCONF_ROLE": {$e, "required": true, "values": ["worker", "web"]}}""", p)
        assertRefused(""""roles": ["web", "worker"], "vars": {"DOCUCONF_ROLE": {$e, "required": true, "values": ["web", "worker"], "roles": ["web"]}}""", p)
        assertRefused(""""roles": ["web", "worker"], "vars": {"DOCUCONF_ROLE": {"type": "string", "description": "The role this workload runs", "required": true}}""", p)
    }

    @Test
    fun refusesMalformedIncludes() {
        assertRefused(""""includes": ["otel"], "vars": {}""", "includes: \"otel\" is not a fragment name, name@major")
        assertRefused(""""includes": ["otel@1", "otel@1"], "vars": {}""", "includes: otel@1 is listed twice")
        assertRefused(""""includes": "otel@1", "vars": {}""", "contract: includes must be a list of strings")
    }
}

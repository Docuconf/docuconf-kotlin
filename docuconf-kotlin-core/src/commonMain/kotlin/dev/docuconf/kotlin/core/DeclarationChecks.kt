package dev.docuconf.kotlin.core

/** A declaration that cannot produce a valid contract. Raised at definition time (SPEC §11.2 item 2). */
public class DeclarationException(public val problems: List<String>) :
    IllegalStateException("invalid docuconf declaration:\n" + problems.joinToString("\n") { "  - $it" })

/**
 * Checks a declaration the way the meta-schema would, so mistakes surface when the app starts or the
 * contract is exported, not when the platform rejects it.
 */
public object DeclarationChecks {
    private val envName = Regex("^[A-Z][A-Z0-9_]*$")
    private val serviceName = Regex("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$")
    private val inputName = Regex("^[a-z]([-a-z0-9]{0,40}[a-z0-9])?$")
    private val absPath = Regex("^/[A-Za-z0-9._/-]+$")
    private val featureFlag = Regex("^(FF|FEATURE|FEATURE_FLAG|ENABLE)_")

    /** Mount directories a file input may not hide (`#ReservedDirs` in the meta-schema). */
    public val reservedDirs: Set<String> = setOf(
        "/", "/app", "/bin", "/boot", "/dev", "/etc", "/etc/pki", "/etc/ssl",
        "/etc/ssl/certs", "/home", "/lib", "/lib64", "/opt", "/proc", "/root",
        "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/usr/lib", "/usr/local",
        "/usr/share", "/var", "/var/lib", "/var/run",
    )

    public data class Result(val errors: List<String>, val warnings: List<String>)

    /** Returns every problem with [contract]. */
    public fun check(contract: Contract): Result {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        if (!serviceName.matches(contract.service)) errors += "service name \"${contract.service}\" must be a DNS label ([a-z0-9-], at most 63 characters)"

        val seen = HashSet<String>()
        for (v in contract.vars) {
            val p = "${v.name}:"
            if (!seen.add(v.name)) errors += "$p declared more than once"
            if (!envName.matches(v.name)) errors += "$p environment variable names must match ^[A-Z][A-Z0-9_]*$"
            if (featureFlag.containsMatchIn(v.name)) warnings += "$p looks like a feature flag; flags that change without a rollout belong in a flag service, not the contract (SPEC §10)"
            checkDescription(p, v.description, errors)
            if (v.required && v.default != null) errors += "$p a required variable cannot have a default"
            if (v.secret && v.default != null) errors += "$p a secret cannot have a default"
            if (v.secret && v.examples.isNotEmpty()) errors += "$p a secret cannot have examples"
            v.pattern?.let { pat -> Re2.unsupportedFeature(pat)?.let { errors += "$p pattern uses $it, which RE2 does not support" } }
            if (v.minLength != null && v.maxLength != null && v.minLength > v.maxLength) errors += "$p minLength is greater than maxLength"
            if (v.minItems != null && v.maxItems != null && v.minItems > v.maxItems) errors += "$p minItems is greater than maxItems"
            if ((v.itemMin != null || v.itemMax != null) && (v.type != VarType.LIST || v.items != ListItems.INT)) errors += "$p itemMin and itemMax only apply to lists of int"
            if (v.itemMin != null && v.itemMax != null && v.itemMin > v.itemMax) errors += "$p itemMin is greater than itemMax"
            if (v.min != null && v.max != null && v.min.asDouble() > v.max.asDouble()) errors += "$p min is greater than max"
            for (d in listOfNotNull(v.minDuration, v.maxDuration)) {
                if (!Durations.isGo(d)) errors += "$p \"$d\" is not a Go duration such as 30s or 1h30m"
            }
            if (v.minDuration != null && v.maxDuration != null && Durations.isGo(v.minDuration) && Durations.isGo(v.maxDuration) &&
                Durations.parseGo(v.minDuration)!! > Durations.parseGo(v.maxDuration)!!
            ) {
                errors += "$p min is longer than max"
            }
            if (v.type == VarType.ENUM && v.values.isNullOrEmpty()) errors += "$p an enum needs at least one value"
            if (v.type == VarType.LIST && v.items == null) errors += "$p a list needs an item type"
            if (v.schemes != null && v.schemes.isEmpty()) errors += "$p schemes cannot be empty"
            v.deprecated?.replacedBy?.let { if (!envName.matches(it)) errors += "$p deprecated.replacedBy must be a variable name" }
            v.default?.let { checkDefault(v, it)?.let { msg -> errors += "$p default $msg" } }
        }

        val names = HashSet<String>()
        val mounts = HashMap<String, String>()
        val pathEnvs = HashSet<String>()
        for (f in contract.files) {
            val p = "file ${f.name}:"
            if (!names.add(f.name)) errors += "$p declared more than once"
            if (!inputName.matches(f.name)) errors += "$p file input names must be DNS labels starting with a letter (^[a-z]([-a-z0-9]{0,40}[a-z0-9])?$)"
            checkDescription(p, f.description, errors)
            if (!isNormalisedAbsolute(f.path)) {
                errors += "$p path \"${f.path}\" must be absolute and normalised"
            } else {
                val dir = if (f.type == FileType.TLS) f.path else f.path.substringBeforeLast('/').ifEmpty { "/" }
                if (dir in reservedDirs) errors += "$p mounting at $dir would hide a directory the image needs; choose a dedicated directory"
                mounts.put(dir, f.name)?.let { other -> errors += "$p shares its mount directory $dir with $other" }
            }
            f.pathEnv?.let { pe ->
                if (!envName.matches(pe)) errors += "$p pathEnv must be an environment variable name"
                if (contract.variable(pe) != null) errors += "$p pathEnv $pe is also declared as a variable"
                if (!pathEnvs.add(pe)) errors += "$p pathEnv $pe is used by another file input"
            }
            f.maxSize?.let { if (it <= 0) errors += "$p maxSize must be positive" }
            f.pattern?.let { pat -> Re2.unsupportedFeature(pat)?.let { errors += "$p pattern uses $it, which RE2 does not support" } }
            f.minRemaining?.let { if (!Durations.isGo(it)) errors += "$p minRemaining \"$it\" is not a Go duration such as 720h" }
            f.minCertificates?.let { if (it < 1) errors += "$p minCertificates must be at least 1" }
            if (f.type == FileType.CONFIG && f.format == null) errors += "$p a config file needs a format"
            if (f.type == FileType.KEYSTORE && f.keystoreFormat == null) errors += "$p a keystore needs a format"
            f.passwordVar?.let { pv ->
                val v = contract.variable(pv)
                if (v == null || !v.secret) errors += "$p passwordVar $pv must name a declared secret variable"
            }
        }
        val overlayNames = HashSet<String>()
        for (o in contract.overlays) {
            val p = "overlay ${o.name}:"
            if (!overlayNames.add(o.name)) errors += "$p declared more than once"
            if (!inputName.matches(o.name)) errors += "$p overlay names must be DNS labels starting with a letter (^[a-z]([-a-z0-9]{0,40}[a-z0-9])?$)"
            o.description?.let { checkDescription(p, it, errors) }
            if (o.keySeparator != ":" && o.keySeparator != ".") errors += "$p keySeparator must be \":\" or \".\""
            if (!isNormalisedAbsolute(o.path)) {
                errors += "$p path \"${o.path}\" must be absolute and normalised"
            } else {
                val dir = o.mountDir
                if (dir in reservedDirs) errors += "$p mounting at $dir would hide a directory the image needs; choose a dedicated directory such as /app/config"
                mounts.put(dir, "overlay ${o.name}")?.let { other -> errors += "$p shares its mount directory $dir with $other" }
            }
        }
        if (contract.overlays.isNotEmpty()) {
            for (v in contract.vars) {
                if (v.secret || v.configKey == null) continue
                for (o in contract.overlays) {
                    if (v.configKey.split(o.keySeparator).size > MAX_KEY_DEPTH) {
                        warnings += "${v.name}: configKey ${v.configKey} is deeper than $MAX_KEY_DEPTH levels, so overlay ${o.name} cannot carry it"
                    }
                }
            }
        }
        return Result(errors, warnings)
    }

    /** How deep a `configKey` may nest in an overlay (`#MaxKeyDepth` in the meta-schema). */
    public const val MAX_KEY_DEPTH: Int = 8

    private fun isNormalisedAbsolute(path: String): Boolean =
        absPath.matches(path) && !path.contains("//") && !path.endsWith("/") && path.split('/').none { it == "." || it == ".." }

    /** Throws [DeclarationException] when [contract] has errors; returns the warnings. */
    public fun require(contract: Contract): List<String> {
        val r = check(contract)
        if (r.errors.isNotEmpty()) throw DeclarationException(r.errors)
        return r.warnings
    }

    private fun checkDescription(p: String, description: String, errors: MutableList<String>) {
        if (ValueChecks.codePointCount(description) < 5) errors += "$p a description of at least 5 characters is required"
    }

    /** Checks a default against the variable's own constraints, as the meta-schema and SPEC §4.3 require. */
    private fun checkDefault(v: VarSpec, d: JsonValue): String? {
        val (spec, raw) = when (v.type) {
            VarType.STRING, VarType.URL, VarType.ENUM -> v to ((d as? JsonValue.Str)?.value ?: return "must be a string")
            VarType.INT -> v to ((d as? JsonValue.Int)?.value?.toString() ?: return "must be an integer")
            VarType.FLOAT -> v to when (d) {
                is JsonValue.Float -> formatFloat(d.value)
                is JsonValue.Int -> d.value.toString()
                else -> return "must be a number"
            }
            VarType.BOOL -> v to ((d as? JsonValue.Bool)?.value?.toString() ?: return "must be true or false")
            VarType.DURATION -> {
                val s = (d as? JsonValue.Str)?.value ?: return "must be a Go duration string"
                if (!Durations.isGo(s)) return "\"$s\" is not a Go duration"
                v.copy(durationEncoding = DurationEncoding.GO) to s
            }
            VarType.LIST -> {
                if (d !is JsonValue.Arr) return "must be a list"
                v.copy(listEncoding = ListEncoding.JSON) to d.toString()
            }
            VarType.JSON -> v to d.toString()
        }
        if (v.type != VarType.STRING && raw.isEmpty()) return "cannot be empty"
        // A non-RE2 pattern is reported on its own; check the rest of the default without it.
        val checkable = if (spec.pattern != null && Re2.unsupportedFeature(spec.pattern) != null) spec.copy(pattern = null) else spec
        val problems = ValueChecks.check(checkable.copy(required = false), raw)
        return if (problems.isEmpty()) null else "violates its own constraints: " + problems.joinToString("; ") { it.message }
    }
}

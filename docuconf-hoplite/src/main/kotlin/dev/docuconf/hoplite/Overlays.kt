package dev.docuconf.hoplite

import com.sksamuel.hoplite.ArrayNode
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.NullNode
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.transformer.PathNormalizer
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigFormat
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.JsonSchemaValidator
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.ListEncoding
import dev.docuconf.kotlin.core.OverlaySpec
import dev.docuconf.kotlin.core.ValueChecks
import dev.docuconf.kotlin.core.VarType
import dev.docuconf.kotlin.core.Violation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/** An overlay found at boot, parsed. [root] is null when the file is absent or empty. */
internal class LoadedOverlay(val spec: OverlaySpec, val path: Path, val root: Node?)

/**
 * Config-file overlays (SPEC §4.7): where they are, whether their directory is safe to mount, and
 * their values, checked like environment values before Hoplite layers the file.
 */
internal object Overlays {
    /** The path the app reads: `DOCUCONF_FILE_ROOT` prepended, as for file inputs. */
    fun resolve(spec: OverlaySpec, fileRoot: String?): Path =
        Path.of(if (!fileRoot.isNullOrEmpty()) fileRoot.trimEnd('/') + spec.path else spec.path)

    /**
     * Directories holding files the app ships with, as far as docuconf can tell: where the config
     * class was loaded from (the app's jar or classes directory), and each base config file given
     * as a file path rather than a classpath resource.
     */
    fun shippedDirs(type: KClass<*>, baseSources: List<String>, classLoader: ClassLoader): Map<Path, String> {
        val out = LinkedHashMap<Path, String>()
        try {
            type.java.protectionDomain?.codeSource?.location?.toURI()?.let { Path.of(it) }?.let { code ->
                val dir = if (Files.isRegularFile(code)) code.parent else code
                if (dir != null) out[normal(dir)] = "the app's code ($code)"
            }
        } catch (_: Exception) {
            // No code source (a custom class loader): nothing to compare against.
        }
        for (s in baseSources) {
            if (classLoader.getResource(s.removePrefix("/")) != null && !Files.isRegularFile(Path.of(s))) continue
            Path.of(s).parent?.let { out.putIfAbsent(normal(it), "the base config file $s") }
        }
        return out
    }

    /** Rejects an overlay whose directory holds files the app ships with: mounting it would hide them. */
    fun checkDirs(overlays: List<OverlaySpec>, path: (OverlaySpec) -> Path, shipped: Map<Path, String>) {
        val errors = overlays.mapNotNull { o ->
            val dir = path(o).parent ?: return@mapNotNull null
            shipped[normal(dir)]?.let { what ->
                "overlay ${o.name}: ${o.path} is in a directory holding $what; the platform mounts the overlay's directory, " +
                    "which would hide those files. Use a directory of its own, such as /app/config."
            }
        }
        if (errors.isNotEmpty()) throw DeclarationException(errors)
    }

    private fun normal(p: Path): Path = p.toAbsolutePath().normalize()

    /** Reads one overlay. A missing file is fine; an unreadable or malformed one is a violation. */
    fun load(spec: OverlaySpec, path: Path, classLoader: ClassLoader, violations: MutableList<Violation>): LoadedOverlay {
        val parser = parserFor(spec.format, classLoader)
            ?: throw DeclarationException(listOf("overlay ${spec.name}: no Hoplite parser for ${spec.format.wire} on the classpath; add ${parserModule(spec.format)}"))
        if (!Files.exists(path)) return LoadedOverlay(spec, path, null)
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            violations += Violation(Codes.FILE_UNREADABLE, spec.name, "overlay $path is not a readable file")
            return LoadedOverlay(spec, path, null)
        }
        val node = try {
            Files.newInputStream(path).use { parser.load(it, path.toString()) }
        } catch (e: Exception) {
            violations += Violation(Codes.FILE_MALFORMED, spec.name, "overlay $path is not valid ${spec.format.wire}: ${e.message?.lineSequence()?.firstOrNull()?.trim()}")
            return LoadedOverlay(spec, path, null)
        }
        return when (node) {
            is MapNode -> LoadedOverlay(spec, path, node)
            Undefined, is NullNode -> LoadedOverlay(spec, path, null)
            else -> {
                violations += Violation(Codes.FILE_MALFORMED, spec.name, "overlay $path must hold a map of keys")
                LoadedOverlay(spec, path, null)
            }
        }
    }

    /** The node at a variable's property path, or [Undefined]. Keys match as Hoplite matches them. */
    fun at(root: Node, path: List<String>): Node = path.fold(root) { n, seg ->
        if (n !is MapNode) return Undefined
        val direct = n.atKey(seg)
        if (direct !is Undefined) direct else n.atKey(PathNormalizer.transformPathElement(seg))
    }

    /**
     * Checks one overlay value exactly like an environment value (SPEC §4.7): the node is turned into
     * the string the variable would have in the environment, then checked with [ValueChecks].
     */
    fun check(v: VarBinding, node: Node, overlay: OverlaySpec, options: ValueChecks.Options): List<Violation> {
        val where = "(from overlay ${overlay.name}, key ${v.spec.configKey})"
        if (v.spec.secret) {
            return listOf(
                Violation(
                    Codes.INVALID_TYPE, v.spec.name,
                    "is set in overlay ${overlay.name}; overlays are ConfigMaps, so a secret must come from the environment",
                ),
            )
        }
        fun bad(what: String) = listOf(Violation(Codes.INVALID_TYPE, v.spec.name, "is $what, not a ${v.spec.type.wire} $where"))
        var spec = v.spec.copy(required = false)
        val raw: String = when (v.spec.type) {
            VarType.LIST -> when (node) {
                is ArrayNode -> {
                    spec = spec.copy(listEncoding = ListEncoding.JSON)
                    JsonValue.Arr(node.elements.map { toJson(it) }).toString()
                }
                is StringNode -> node.value
                else -> return bad(kind(node))
            }
            VarType.JSON -> when {
                node is StringNode -> node.value
                // Hoplite's YAML parser reads every scalar as a string; validate as config files are.
                overlay.format == ConfigFormat.YAML && spec.schema != null ->
                    return JsonSchemaValidator.validate(spec.schema!!, toJson(node), lenientScalars = true)
                        .map { Violation(Codes.SCHEMA_MISMATCH, v.spec.name, "$it $where") }
                else -> toJson(node).toString()
            }
            else -> when (node) {
                is StringNode -> node.value
                is LongNode -> node.value.toString()
                is DoubleNode -> node.value.toString()
                is BooleanNode -> node.value.toString()
                else -> return bad(kind(node))
            }
        }
        if (raw.isEmpty() && v.spec.type != VarType.STRING) return bad("empty")
        return ValueChecks.check(spec, raw, options).map { it.copy(message = "${it.message} $where") }
    }

    private fun kind(n: Node) = when (n) {
        is MapNode -> "a map"
        is ArrayNode -> "a list"
        else -> "not a scalar"
    }
}

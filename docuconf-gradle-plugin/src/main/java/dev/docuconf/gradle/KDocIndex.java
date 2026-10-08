package dev.docuconf.gradle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the KDoc of constructor parameters in Kotlin source, for docuconf descriptions and details
 * (SPEC section 14.7). A lexical scan, not a compiler: it tracks {@code class} and {@code object}
 * declarations by their brackets, skips strings and comments, and keys each KDoc
 * {@code <package>.<Outer>.<Inner>#<parameter>}. A KDoc directly before a {@code val} or {@code var}
 * parameter (after its annotations and modifiers) is used; otherwise an {@code @property} or
 * {@code @param} tag of the class's KDoc.
 */
final class KDocIndex {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.`]+)");
    private static final Set<String> MODIFIERS = Set.of("private", "public", "internal", "protected", "override",
            "open", "final", "lateinit", "const", "data", "value", "inline", "sealed", "abstract", "enum", "annotation", "inner");
    private static final Pattern TAG = Pattern.compile("^@(property|param)\\s+(\\w+)\\s*(.*)$");

    private KDocIndex() {
    }

    private record Scope(String name, int depth) {
    }

    /** The KDoc of every documented constructor parameter in {@code source}, by key. */
    static Map<String, String> scan(String source) {
        Map<String, String> own = new LinkedHashMap<>();
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher pm = PACKAGE.matcher(source);
        String pkg = pm.find() ? pm.group(1).replace("`", "") + "." : "";
        Deque<Scope> scopes = new ArrayDeque<>();
        int depth = 0;
        String pending = null;
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("/**", i) && !source.startsWith("/**/", i)) {
                int end = source.indexOf("*/", i + 3);
                if (end < 0) {
                    break;
                }
                String text = text(source.substring(i + 3, end));
                int j = skipToDeclaration(source, end + 2);
                String[] decl = declaration(source, j);
                if (decl != null && (decl[0].equals("val") || decl[0].equals("var")) && !scopes.isEmpty()) {
                    own.put(key(pkg, scopes) + "#" + decl[1], text);
                } else if (decl != null && (decl[0].equals("class") || decl[0].equals("object"))) {
                    String cls = (scopes.isEmpty() ? pkg : key(pkg, scopes) + ".") + decl[1];
                    for (Map.Entry<String, String> t : tagTexts(text).entrySet()) {
                        tags.put(cls + "#" + t.getKey(), t.getValue());
                    }
                }
                i = end + 2;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(source.charAt(j))) {
                    j++;
                }
                String word = source.substring(i, j);
                boolean member = i > 0 && (source.charAt(i - 1) == '.' || source.charAt(i - 1) == ':' && i > 1 && source.charAt(i - 2) == ':');
                if (!member && (word.equals("class") || word.equals("object") || word.equals("interface"))) {
                    String[] decl = declaration(source, i);
                    pending = decl == null ? null : decl[1];
                } else if (word.equals("fun") || word.equals("val") || word.equals("var")) {
                    pending = null;
                }
                i = j;
            } else {
                if (c == '(' || c == '{') {
                    depth++;
                    if (pending != null) {
                        scopes.push(new Scope(pending, depth));
                        pending = null;
                    }
                } else if (c == ')' || c == '}') {
                    if (!scopes.isEmpty() && scopes.peek().depth() == depth) {
                        Scope closed = scopes.pop();
                        // After the constructor's ")" the class body "{" may follow.
                        pending = c == ')' ? closed.name() : null;
                    }
                    depth--;
                }
                i++;
            }
        }
        Map<String, String> out = new LinkedHashMap<>(tags);
        out.putAll(own);
        return out;
    }

    private static String key(String pkg, Deque<Scope> scopes) {
        List<String> names = new ArrayList<>();
        scopes.descendingIterator().forEachRemaining(s -> names.add(s.name()));
        return pkg + String.join(".", names);
    }

    /** Skips white space, annotations ({@code @Doc("...")}, {@code @field:Foo}) and modifiers. */
    private static int skipToDeclaration(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '@') {
                i++;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == ':')) {
                    i++;
                }
                if (i < n && s.charAt(i) == '(') {
                    int level = 0;
                    while (i < n) {
                        char d = s.charAt(i);
                        if (d == '"') {
                            i++;
                            while (i < n && s.charAt(i) != '"') {
                                i += s.charAt(i) == '\\' ? 2 : 1;
                            }
                        } else if (d == '(') {
                            level++;
                        } else if (d == ')' && --level == 0) {
                            i++;
                            break;
                        }
                        i++;
                    }
                }
            } else {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) {
                    j++;
                }
                if (j > i && MODIFIERS.contains(s.substring(i, j))) {
                    i = j;
                } else {
                    return i;
                }
            }
        }
        return i;
    }

    /** {@code [keyword, name]} for {@code val x}, {@code var x}, {@code class X} or {@code object X} at {@code i}, else null. */
    private static String[] declaration(String s, int i) {
        Matcher m = Pattern.compile("\\G(val|var|class|object|interface)\\s+`?([A-Za-z_][A-Za-z0-9_]*)").matcher(s);
        m.region(i, s.length());
        return m.lookingAt() ? new String[] {m.group(1), m.group(2)} : null;
    }

    /** A comment's text: the leading {@code *} of each line and the common indentation removed. */
    static String text(String body) {
        String[] lines = body.split("\r?\n", -1);
        List<String> out = new ArrayList<>();
        for (int k = 0; k < lines.length; k++) {
            String l = lines[k];
            l = k == 0 ? l.strip() : l.replaceFirst("^\\s*\\* ?", "");
            out.add(l.stripTrailing());
        }
        while (!out.isEmpty() && out.get(0).isBlank()) {
            out.remove(0);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        return String.join("\n", out);
    }

    /** The text of each {@code @property name} and {@code @param name} tag, which runs to the next tag. */
    private static Map<String, String> tagTexts(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String name = null;
        StringBuilder sb = null;
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("@")) {
                if (name != null) {
                    out.putIfAbsent(name, sb.toString().strip());
                }
                Matcher m = TAG.matcher(t);
                name = m.matches() ? m.group(2) : null;
                sb = m.matches() ? new StringBuilder(m.group(3)) : null;
            } else if (sb != null) {
                sb.append('\n').append(line);
            }
        }
        if (name != null) {
            out.putIfAbsent(name, sb.toString().strip());
        }
        return out;
    }
}

package dev.docuconf.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class KDocIndexTest {
    private static final String SOURCE = """
            package com.example.shop

            import dev.docuconf.hoplite.Doc

            /**
             * Shop settings.
             *
             * @property region Cloud region.
             *   Set by the platform.
             * @property workers ignored: the parameter's own KDoc wins
             */
            @DocuconfService(name = "shop")
            data class ShopConfig(
                /**
                 * Background workers.
                 *
                 * - Raise it when the queue backs up.
                 */
                @Min(1) @Doc(details = "a ) in a string") val workers: Int = 4,
                // not KDoc
                val region: String = "eu",
                val limits: Limits = Limits(),
            ) {
                /** Nested limits. */
                data class Limits(
                    /** Most items in one order. */
                    private val maxItems: Int = 10,
                )

                companion object {
                    val DEFAULT = "/** not a comment */"
                }
            }

            data class Other(
                /** Other value. */ val value: String,
            )
            """;

    @Test
    void indexesConstructorParameterKDoc() {
        Map<String, String> docs = KDocIndex.scan(SOURCE);
        assertEquals("Background workers.\n\n- Raise it when the queue backs up.", docs.get("com.example.shop.ShopConfig#workers"));
        assertEquals("Cloud region.\n  Set by the platform.", docs.get("com.example.shop.ShopConfig#region"));
        assertEquals("Most items in one order.", docs.get("com.example.shop.ShopConfig.Limits#maxItems"));
        assertEquals("Other value.", docs.get("com.example.shop.Other#value"));
        assertEquals(4, docs.size(), docs.toString());
    }

    @Test
    void writesAPropertiesFileThatRoundTrips() throws Exception {
        Map<String, String> docs = Map.of("a.B#c", " Lead: space = and # and 日本\nnext\\line");
        StringWriter w = new StringWriter();
        DocuconfKDocTask.write(new java.util.TreeMap<>(docs), w);
        Properties p = new Properties();
        p.load(new StringReader(w.toString()));
        assertEquals(docs.get("a.B#c"), p.getProperty("a.B#c"));
    }
}

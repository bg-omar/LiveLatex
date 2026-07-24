package com.omariskandarani.livelatex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewAnnouncerTest {

    @Test
    fun parseVersionFromPluginXml_readsVersion() {
        val xml = """
            <idea-plugin>
              <id>com.omariskandarani.livelatex</id>
              <version>0.0.10</version>
              <name>LiveLatex</name>
            </idea-plugin>
        """.trimIndent()
        assertEquals("0.0.10", WhatsNewAnnouncer.parseVersionFromPluginXml(xml))
    }

    @Test
    fun parseVersionFromPluginXml_trimsWhitespace() {
        val xml = "<idea-plugin><version>  1.2.3-eap  </version></idea-plugin>"
        assertEquals("1.2.3-eap", WhatsNewAnnouncer.parseVersionFromPluginXml(xml))
    }

    @Test
    fun parseVersionFromPluginXml_caseInsensitiveTag() {
        val xml = "<idea-plugin><VERSION>2.0.0</VERSION></idea-plugin>"
        assertEquals("2.0.0", WhatsNewAnnouncer.parseVersionFromPluginXml(xml))
    }

    @Test
    fun parseVersionFromPluginXml_missingReturnsNull() {
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml("<idea-plugin><id>x</id></idea-plugin>"))
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml(""))
    }

    @Test
    fun parseVersionFromPluginXml_blankReturnsNull() {
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml("<idea-plugin><version>   </version></idea-plugin>"))
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml("<idea-plugin><version></version></idea-plugin>"))
    }

    @Test
    fun parseVersionFromPluginXml_malformedReturnsNull() {
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml("<idea-plugin><version>0.0.10"))
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml("not xml at all"))
        assertNull(WhatsNewAnnouncer.parseVersionFromPluginXml("<version>"))
    }
}

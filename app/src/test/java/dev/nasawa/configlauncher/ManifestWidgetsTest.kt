package dev.nasawa.configlauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** #299 NW-2: provider visibility without QUERY_ALL_PACKAGES, and widgets/ kept out of backup and transfer (D2). */
class ManifestWidgetsTest {
    private fun src(p: String) = File(p).takeIf { it.isFile } ?: File("app/$p")
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun queriesAppWidgetUpdateAndNoBindPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(src("src/main/AndroidManifest.xml"))
        val queries = doc.getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        val actions = queries.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") } }
        assertTrue(actions.toString(), "android.appwidget.action.APPWIDGET_UPDATE" in actions)
        val text = src("src/main/AndroidManifest.xml").readText()
        assertFalse(text.contains("BIND_APPWIDGET"))
        assertFalse(text.contains("<uses-permission android:name=\"android.permission.QUERY_ALL_PACKAGES\""))
    }

    @Test fun widgetsExcludedFromCloudBackupAndDeviceTransfer() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(src("src/main/res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element
            val ex = s.getElementsByTagName("exclude").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).let { e -> e.getAttribute("domain") to e.getAttribute("path") } } }
            assertTrue("$section: $ex", ("file" to "widgets/") in ex)
            assertTrue("$section: $ex", ("file" to "consent/") in ex)
        }
    }
}

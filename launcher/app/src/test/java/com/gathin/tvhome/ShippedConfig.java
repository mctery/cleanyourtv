package com.gathin.tvhome;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/** The launcher's own config.xml, read the way Android would, for tests of what it ships with. */
final class ShippedConfig {
    private static Document doc;

    private ShippedConfig() {
    }

    private static Document doc() {
        if (doc == null) {
            try {
                // Unit tests run from the module's folder.
                doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new File("src/main/res/values/config.xml"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return doc;
    }

    static List<String> array(String name) {
        NodeList arrays = doc().getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element a = (Element) arrays.item(i);
            if (!name.equals(a.getAttribute("name"))) continue;
            List<String> out = new ArrayList<>();
            NodeList items = a.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) out.add(items.item(j).getTextContent().trim());
            return out;
        }
        throw new IllegalArgumentException("no string-array " + name);
    }

    /** What the launcher knows about apps out of the box. */
    static XmbCatalog.Known known() {
        return new XmbCatalog.Known(array("xmb_video_apps"), array("xmb_music_apps"), array("xmb_menu_apps"));
    }
}

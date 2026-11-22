package com.zhixiaogang.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 轻量 xlsx 读取工具（不依赖第三方库）：解压 xlsx（zip）后解析 sharedStrings.xml 与首个工作表，
 * 返回按行列组织的字符串矩阵。仅支持 .xlsx（OpenXML）。
 */
public final class XlsxParser {

    private XlsxParser() {
    }

    /** 解析 xlsx 输入流，返回行 × 列的字符串矩阵（行内按列索引对齐，空单元格为 ""）。 */
    public static List<List<String>> parseRows(InputStream in) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), readAll(zis));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("文件不是有效的 xlsx（无法解压内容）");
        }

        List<String> shared = parseSharedStrings(entries.get("xl/sharedStrings.xml"));
        byte[] sheet = entries.get("xl/worksheets/sheet1.xml");
        if (sheet == null) {
            List<String> names = new ArrayList<>(entries.keySet());
            Collections.sort(names);
            for (String name : names) {
                if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                    sheet = entries.get(name);
                    break;
                }
            }
        }
        if (sheet == null) {
            throw new IllegalArgumentException("xlsx 中未找到工作表数据");
        }
        return parseSheet(sheet, shared);
    }

    private static List<String> parseSharedStrings(byte[] xml) throws Exception {
        List<String> list = new ArrayList<>();
        if (xml == null) {
            return list;
        }
        Document doc = newBuilder().parse(new ByteArrayInputStream(xml));
        NodeList siNodes = doc.getElementsByTagName("si");
        for (int i = 0; i < siNodes.getLength(); i++) {
            Element si = (Element) siNodes.item(i);
            NodeList tNodes = si.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < tNodes.getLength(); k++) {
                sb.append(tNodes.item(k).getTextContent());
            }
            list.add(sb.toString());
        }
        return list;
    }

    private static List<List<String>> parseSheet(byte[] xml, List<String> shared) throws Exception {
        Document doc = newBuilder().parse(new ByteArrayInputStream(xml));
        List<List<String>> rows = new ArrayList<>();
        NodeList rowNodes = doc.getElementsByTagName("row");
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element row = (Element) rowNodes.item(i);
            NodeList cellNodes = row.getElementsByTagName("c");
            List<String> values = new ArrayList<>();
            for (int j = 0; j < cellNodes.getLength(); j++) {
                Element c = (Element) cellNodes.item(j);
                int col = colIndex(c.getAttribute("r"), j);
                while (values.size() <= col) {
                    values.add("");
                }
                values.set(col, cellValue(c, shared));
            }
            rows.add(values);
        }
        return rows;
    }

    /** 由单元格引用（如 B5）解析 0 基列索引；引用缺失时回退到顺序索引。 */
    private static int colIndex(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) {
            return fallback;
        }
        int col = 0;
        boolean found = false;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
                found = true;
            } else if (ch >= 'a' && ch <= 'z') {
                col = col * 26 + (ch - 'a' + 1);
                found = true;
            } else {
                break;
            }
        }
        return found ? col - 1 : fallback;
    }

    private static String cellValue(Element c, List<String> shared) {
        String type = c.getAttribute("t");
        if ("inlineStr".equals(type)) {
            // inlineStr：文本位于 <c><is><t>…</t></is></c>，需递归取 is 下所有 t 并拼接
            Element is = directChild(c, "is");
            if (is == null) {
                return "";
            }
            NodeList tNodes = is.getElementsByTagName("t");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < tNodes.getLength(); k++) {
                sb.append(tNodes.item(k).getTextContent());
            }
            return sb.toString();
        }
        String v = directText(c, "v");
        if ("s".equals(type)) {
            if (v == null || v.trim().isEmpty()) {
                return "";
            }
            try {
                int idx = (int) Double.parseDouble(v.trim());
                return idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
            } catch (Exception e) {
                return "";
            }
        }
        return v == null ? "" : v.trim();
    }

    /** 取元素的直接子元素文本（避免与更深层同名元素混淆） */
    private static String directText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return n.getTextContent();
            }
        }
        return null;
    }

    /** 取元素的第一个指定名称的直接子元素；不存在返回 null */
    private static Element directChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // 防 XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}

package io.github.aaroua5.legacyexplorer;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A tiny XML tree that remembers the line number of every element, so tool answers can point at
 * {@code file:line}. DTDs and schemas are never fetched: legacy files reference URLs that may be dead,
 * and an analysis tool should not make network calls.
 */
record XmlTree(String name, Map<String, String> attributes, int line, List<XmlTree> children, StringBuilder text) {

    String attr(String key) {
        return attributes.get(key);
    }

    String textValue() {
        return text.toString().trim();
    }

    /** Element name without a namespace prefix, e.g. {@code jms:listener} becomes {@code listener}. */
    String localName() {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    List<XmlTree> childrenNamed(String localName) {
        return children.stream().filter(c -> c.localName().equals(localName)).toList();
    }

    String childText(String localName) {
        return childrenNamed(localName).stream().findFirst().map(XmlTree::textValue).orElse(null);
    }

    void walk(Consumer<XmlTree> visitor) {
        visitor.accept(this);
        for (XmlTree c : children) c.walk(visitor);
    }

    static XmlTree parse(Path file) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        Deque<XmlTree> stack = new ArrayDeque<>();
        List<XmlTree> roots = new ArrayList<>();
        factory.newSAXParser().parse(new InputSource(new StringReader(Files.readString(file))), new DefaultHandler() {
            private Locator locator;

            @Override
            public void setDocumentLocator(Locator locator) {
                this.locator = locator;
            }

            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new StringReader("")); // never fetch DTDs
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts) {
                Map<String, String> attrs = new LinkedHashMap<>();
                for (int i = 0; i < atts.getLength(); i++) attrs.put(atts.getQName(i), atts.getValue(i));
                XmlTree node = new XmlTree(qName, attrs, locator.getLineNumber(), new ArrayList<>(), new StringBuilder());
                if (stack.isEmpty()) roots.add(node); else stack.peek().children().add(node);
                stack.push(node);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                stack.pop();
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (!stack.isEmpty()) stack.peek().text().append(ch, start, length);
            }
        });
        return roots.get(0);
    }
}
